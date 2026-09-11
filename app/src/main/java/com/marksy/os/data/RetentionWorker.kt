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
        val dao = MarksyDatabase.getInstance(applicationContext).notificationEventDao()
        val now = System.currentTimeMillis()

        dao.deleteOldNonTrading(now - SEVEN_DAYS_MS)
        dao.deleteOldTrading(now - THIRTY_DAYS_MS)
        return Result.success()
    }

    companion object {
        private const val SEVEN_DAYS_MS = 7L * 24 * 60 * 60 * 1000
        private const val THIRTY_DAYS_MS = 30L * 24 * 60 * 60 * 1000
    }
}
