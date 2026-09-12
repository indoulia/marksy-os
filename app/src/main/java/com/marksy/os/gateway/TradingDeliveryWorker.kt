package com.marksy.os.gateway

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.marksy.os.data.MarksyContainer
import com.marksy.os.data.local.DeliveryState
import kotlinx.coroutines.CancellationException

class TradingDeliveryWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result {
        return try {
            deliverPending()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            Log.w(TAG, "Trading delivery storage operation failed; retrying")
            Result.retry()
        }
    }

    private suspend fun deliverPending(): Result {
        val dao = MarksyContainer.database(applicationContext).notificationEventDao()
        val client = MarksyGatewayProvider.client()
        val now = System.currentTimeMillis()

        dao.recoverStaleInFlight(TradingDeliveryPolicy.staleCutoff(now))
        val pending = dao.findPendingTrading(TradingDeliveryPolicy.BATCH_SIZE)
        if (pending.isEmpty()) return Result.success()

        if (client is UnconfiguredMarksyGatewayClient) {
            Log.i(TAG, "Trading delivery deferred: Marksy Gateway is not configured")
            return Result.success()
        }

        var retryRequested = false
        for (event in pending) {
            if (isStopped) return Result.success()

            val attempts = event.deliveryAttempts + 1
            val claimed = dao.claimPendingTrading(event.id, attempts, System.currentTimeMillis())
            if (claimed != 1) continue

            if (isStopped) {
                dao.updateInFlightDeliveryState(event.id, DeliveryState.PENDING.name, attempts, System.currentTimeMillis())
                return Result.success()
            }

            val request = event.toMarksyTradingEventRequest()
            if (request == null) {
                dao.updateInFlightDeliveryState(event.id, DeliveryState.FAILED.name, attempts, System.currentTimeMillis())
                Log.w(TAG, "Trading event ${event.id} rejected by local gateway mapping")
                continue
            }

            val result: Result<MarksyInsight> = try {
                client.analyze(request)
            } catch (cancellation: CancellationException) {
                dao.updateInFlightDeliveryState(event.id, DeliveryState.PENDING.name, attempts, System.currentTimeMillis())
                throw cancellation
            } catch (t: Throwable) {
                Result.failure(t)
            }

            result.fold(
                onSuccess = { insight ->
                    val receivedAt = System.currentTimeMillis()
                    val updated = dao.markDeliveredWithInsight(
                        eventId = event.id,
                        state = DeliveryState.DELIVERED.name,
                        attempts = attempts,
                        attemptedAt = receivedAt,
                        summary = insight.summary,
                        action = insight.action,
                        confidence = insight.confidence,
                        receivedAt = receivedAt,
                        tipId = insight.tipId,
                        responseJson = insight.rawResponseJson
                    )
                    if (updated != 1) Log.i(TAG, "Trading event ${event.id} was already transitioned by another worker")
                },
                onFailure = { error ->
                    val state = TradingDeliveryPolicy.retryState(error)
                    val updated = dao.updateInFlightDeliveryState(
                        event.id,
                        state.name,
                        attempts,
                        System.currentTimeMillis()
                    )
                    if (updated != 1) {
                        Log.i(TAG, "Trading event ${event.id} was already transitioned by another worker")
                    } else if (state == DeliveryState.PENDING) {
                        retryRequested = true
                    } else {
                        // Deliberately do not log the exception message: backend error
                        // details must never become a notification-content side channel.
                        Log.w(TAG, "Trading event ${event.id} permanently rejected")
                    }
                }
            )
        }
        return if (retryRequested) Result.retry() else Result.success()
    }

    private companion object {
        const val TAG = "MarksyTradingDelivery"
    }
}
