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
            val now = System.currentTimeMillis()
            // Memory and learning run before pruning so events about to expire are still observed.
            MarksyContainer.memory(applicationContext).ingest()
            MarksyContainer.learning(applicationContext).run {
                sweepIgnored(now)
                pruneExpired(now)
            }
            dao.pruneExpired(now)
            MarksyDatabase.getInstance(applicationContext).contextGraphDao().pruneOrphanLinks()
            MarksyDatabase.getInstance(applicationContext).ruleExecutionDao().pruneOrphans()
            MarksyDatabase.getInstance(applicationContext).aiInvocationDao().prune(now - 45L * 24 * 60 * 60 * 1000)
            // Lifecycle/failure log and counters are kept longer than a 30-day validation window.
            MarksyDatabase.getInstance(applicationContext).connectorDao().prune(now - 45L * 24 * 60 * 60 * 1000)
            MarksyDatabase.getInstance(applicationContext).metricsDao().prune(java.time.LocalDate.now().minusDays(120).toString())
            Result.success()
        } catch (e: Exception) {
            // Retention is local housekeeping. A transient database failure should
            // retry instead of silently waiting for the next daily schedule.
            Result.retry()
        }
    }
}
