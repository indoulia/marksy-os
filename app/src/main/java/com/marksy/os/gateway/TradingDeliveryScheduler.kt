package com.marksy.os.gateway

import android.content.Context
import android.os.Build
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
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
     * The worker drains the whole pending queue, so one request is sufficient
     * even when several notifications arrive close together.
     */
    fun requestImmediateDelivery(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val request = OneTimeWorkRequestBuilder<TradingDeliveryWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_DELAY_SECONDS, TimeUnit.SECONDS)
            // Below API 31 expedited work needs a foreground notification the worker does not provide.
            .apply { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST) }
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            IMMEDIATE_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            request
        )
    }

    /**
     * Cancels both delivery paths when local data is explicitly cleared.
     * The next app startup schedules the durable periodic path again.
     */
    fun cancelPendingDelivery(context: Context) {
        val workManager = WorkManager.getInstance(context)
        workManager.cancelUniqueWork(IMMEDIATE_WORK_NAME)
        workManager.cancelUniqueWork(WORK_NAME)
    }
}
