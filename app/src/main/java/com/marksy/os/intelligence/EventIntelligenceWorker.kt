package com.marksy.os.intelligence

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.marksy.os.data.local.MarksyDatabase
import java.util.concurrent.TimeUnit

/**
 * Background backfill for rows captured before EPIC-010, rows whose inline processing failed,
 * and rows from an older pipeline VERSION. Bounded per run so it never monopolises the device.
 */
class EventIntelligenceWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = try {
        val db = MarksyDatabase.getInstance(applicationContext)
        val pipeline = EventIntelligencePipeline(db.notificationEventDao(), graph = ContextGraph(db.contextGraphDao()))
        var batches = 0
        while (batches < MAX_BATCHES_PER_RUN && pipeline.processPending() > 0) batches++
        if (batches == MAX_BATCHES_PER_RUN) schedule(applicationContext, replace = true)
        Result.success()
    } catch (e: Exception) {
        Result.retry()
    }

    companion object {
        private const val WORK_NAME = "marksy-event-intelligence-backfill"
        private const val MAX_BATCHES_PER_RUN = 10

        fun schedule(context: Context, replace: Boolean = false) {
            val request = OneTimeWorkRequestBuilder<EventIntelligenceWorker>()
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME,
                if (replace) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.KEEP,
                request
            )
        }
    }
}
