package com.marksy.os.gateway

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.marksy.os.data.local.DeliveryState
import com.marksy.os.data.local.MarksyDatabase

class TradingDeliveryWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val dao = MarksyDatabase.getInstance(applicationContext).notificationEventDao()
        val client = MarksyGatewayProvider.client()
        val now = System.currentTimeMillis()

        dao.recoverStaleInFlight(now - STALE_IN_FLIGHT_MS)
        val pending = dao.findPendingTrading(BATCH_SIZE)
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

            if (isStopped) {
                dao.updateDeliveryState(
                    event.id,
                    DeliveryState.PENDING.name,
                    attempts,
                    System.currentTimeMillis()
                )
                return Result.success()
            }

            val request = event.toMarksyTradingEventRequest()
            if (request == null) {
                dao.updateDeliveryState(event.id, DeliveryState.FAILED.name, attempts, System.currentTimeMillis())
                Log.w(TAG, "Trading event ${event.id} rejected by local gateway mapping")
                continue
            }

            val result: kotlin.Result<MarksyInsight> = try {
                client.analyze(request)
            } catch (t: Throwable) {
                kotlin.Result.failure(t)
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
                onFailure = {
                    dao.updateDeliveryState(event.id, DeliveryState.PENDING.name, attempts, System.currentTimeMillis())
                    retryRequested = true
                }
            )
        }

        return if (retryRequested) Result.retry() else Result.success()
    }

    companion object {
        private const val TAG = "TradingDeliveryWorker"
        private const val BATCH_SIZE = 10
        private const val STALE_IN_FLIGHT_MS = 30L * 60 * 1000
    }
}
