package com.marksy.os.data

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.marksy.os.data.local.MarksyDatabase

/**
 * Bounds local notification storage. Ordinary events are intentionally short-lived;
 * trading events get a longer local window for intelligence/audit context.
 */
class RetentionWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val dao = MarksyDatabase.getInstance(applicationContext).notificationEventDao()
        val now = System.currentTimeMillis()

        // DAO cleanup is conservative: the current schema stores one retention
        // timestamp, so keep a bounded 7-day local inbox. Trading-specific
        // long-term retention can be introduced when the event lifecycle gains
        // explicit state/forwarding timestamps.
        dao.deleteOlderThan(now - SEVEN_DAYS_MS)
        return Result.success()
    }

    companion object {
        private const val SEVEN_DAYS_MS = 7L * 24 * 60 * 60 * 1000
    }
}
