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

        dao.deleteOldNonTrading(RetentionPolicy.nonTradingCutoff(now))
        dao.deleteOldTrading(RetentionPolicy.tradingCutoff(now))
        return Result.success()
    }
}
