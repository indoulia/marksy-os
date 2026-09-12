package com.marksy.os.data

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.marksy.os.data.local.MarksyDatabase

/**
 * Keeps ordinary notifications short-lived while preserving trading context
 * for a longer local audit/intelligence window.
 */
class RetentionWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        return try {
            val dao = MarksyDatabase.getInstance(applicationContext).notificationEventDao()
            dao.pruneExpired(System.currentTimeMillis())
            Result.success()
        } catch (e: Exception) {
            // Retention is local housekeeping. A transient database failure should
            // retry instead of silently waiting for the next daily schedule.
            Result.retry()
        }
    }
}
