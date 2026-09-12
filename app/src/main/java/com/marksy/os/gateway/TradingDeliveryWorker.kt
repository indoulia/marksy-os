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
        val dao = MarksyContainer.database(applicationContext).notificationEventDao()
        val client = MarksyGatewayProvider.client()
        val now = System.currentTimeMillis()

        // A process death/cancellation can leave an event IN_FLIGHT. Requeue only
        // entries older than the safety window so an active request is not duplicated.
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
            val claimed = dao.claimPendingTrading(
                eventId = event.id,
                attempts = attempts,
                attemptedAt = System.currentTimeMillis()
            )
            if (claimed != 1) continue

            // WorkManager can stop this worker between the claim and the network call.
            // Return the event to PENDING so it is eligible for the next run.
            if (isStopped) {
                dao.updateDeliveryState(event.id, DeliveryState.PENDING.name, attempts, System.currentTimeMillis())
                return Result.success()
            }

            val request = event.toMarksyTradingEventRequest()
            if (request == null) {
                dao.updateDeliveryState(event.id, DeliveryState.FAILED.name, attempts, System.currentTimeMillis())
                Log.w(TAG, "Trading event ${event.id} rejected by local gateway mapping")
                continue
            }

            val result: Result<MarksyInsight> = try {
                client.analyze(request)
            } catch (cancellation: CancellationException) {
                dao.updateDeliveryState(
                    event.id,
                    DeliveryState.PENDING.name,
                    attempts,
                    System.currentTimeMillis()
                )
                throw cancellation
            } catch (t: Throwable) {
                Result.failure(t)
            }

            result.fold(
                onSuccess = { insight ->
                    val receivedAt = System.currentTimeMillis()
                    dao.markDeliveredWithInsight(
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
                },
                onFailure = { error ->
                    val state = TradingDeliveryPolicy.retryState(error)
                    dao.updateDeliveryState(
                        event.id,
                        state.name,
                        attempts,
                        System.currentTimeMillis()
                    )
                    if (state == DeliveryState.PENDING) {
                        retryRequested = true
                    } else {
                        Log.w(TAG, "Trading event ${event.id} permanently rejected: ${error.message}")
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
