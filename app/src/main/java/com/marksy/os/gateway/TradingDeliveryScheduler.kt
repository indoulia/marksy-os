package com.marksy.os.gateway

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

object TradingDeliveryScheduler {
    private const val WORK_NAME = "marksy-trading-delivery"
    private const val IMMEDIATE_WORK_NAME = "marksy-trading-delivery-immediate"
    private const val BACKOFF_DELAY_SECONDS = 30L

    fun schedule(context: Context) {
        val workManager = WorkManager.getInstance(context)
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val request = PeriodicWorkRequestBuilder<TradingDeliveryWorker>(15, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_DELAY_SECONDS, TimeUnit.SECONDS)
            .build()

        workManager.enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }

    /**
     * Requests a prompt delivery attempt after a new trading event is stored.
     * Network constraints still apply, and the periodic worker remains the
     * durable fallback if this one-time request cannot run immediately.
     *
     * KEEP avoids replacing a delivery request that is already queued/running.
     * The worker drains a batch of pending trading events, so one request is
     * sufficient even when several notifications arrive close together.
     */
    fun requestImmediateDelivery(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val request = OneTimeWorkRequestBuilder<TradingDeliveryWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_DELAY_SECONDS, TimeUnit.SECONDS)
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            IMMEDIATE_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            request
        )
    }

    /**
     * Removes queued/running delivery work when the user explicitly clears
     * local data. This prevents an old queued job from processing records
     * after the local store has been wiped.
     */
    fun cancelPendingDelivery(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(IMMEDIATE_WORK_NAME)
    }
}
