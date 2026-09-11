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

        var failed = false
        for (event in pending) {
            val attempts = event.deliveryAttempts + 1
            dao.updateDeliveryState(event.id, DeliveryState.IN_FLIGHT.name, attempts, System.currentTimeMillis())

            val request = event.toMarksyTradingEventRequest()
            if (request == null) {
                dao.updateDeliveryState(event.id, DeliveryState.FAILED.name, attempts, System.currentTimeMillis())
                failed = true
                continue
            }

            val result = try {
                client.analyze(request)
            } catch (t: Throwable) {
                kotlin.Result.failure(t)
            }

            if (result.isSuccess) {
                dao.updateDeliveryState(event.id, DeliveryState.DELIVERED.name, attempts, System.currentTimeMillis())
            } else {
                dao.updateDeliveryState(event.id, DeliveryState.PENDING.name, attempts, System.currentTimeMillis())
                failed = true
            }
        }

        return if (failed) Result.retry() else Result.success()
    }

    companion object {
        private const val TAG = "TradingDeliveryWorker"
        private const val BATCH_SIZE = 10
        private const val STALE_IN_FLIGHT_MS = 30L * 60 * 1000
    }
}
