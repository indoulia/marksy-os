package com.marksy.os.connector

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.marksy.os.data.MarksyContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/** Registered pull connectors. SMS is absent on purpose: READ_SMS is a restricted permission, so SMS stays notification-only. */
object SyncConnectors {
    fun all(context: Context): List<SyncConnector> = listOf(
        CalendarConnector(AndroidCalendarSource(context.applicationContext)),
        GmailConnector(HttpGmailApi(), tokens = null)
    )

    fun syncer(context: Context) = ConnectorSyncer(MarksyContainer.ingestion(context.applicationContext), PrefsSyncStore(context.applicationContext))
}

/** Background sync so connectors keep working with no screen open and across process death. */
class ConnectorSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val outcomes = syncAll(SyncConnectors.syncer(applicationContext), SyncConnectors.all(applicationContext))
        if (shouldRetry(outcomes, runAttemptCount)) Result.retry() else Result.success()
    }

    companion object {
        private const val TAG = "MarksyConnector"

        /** Each connector is isolated: one failing provider neither blocks nor rolls back the others. */
        internal suspend fun syncAll(syncer: ConnectorSyncer, connectors: List<SyncConnector>): List<ConnectorSyncer.Outcome> = connectors.map { c ->
            runCatching { syncer.sync(c) }.getOrElse { e ->
                if (e is kotlinx.coroutines.CancellationException) throw e
                com.marksy.os.ai.DiagLog.w(TAG, "${runCatching { c.descriptor.id }.getOrDefault("?")}: unexpected ${e.javaClass.simpleName}")
                ConnectorSyncer.Outcome.Failed(ConnectorException.Kind.TRANSIENT)
            }
        }

        /** Only retryable failures (network/provider) retry, and only a bounded number of times; auth/permission wait for the user. */
        internal fun shouldRetry(outcomes: List<ConnectorSyncer.Outcome>, attempt: Int) =
            outcomes.any { it is ConnectorSyncer.Outcome.Failed && it.retryable } && attempt < MAX_RETRIES

        private const val PERIODIC = "marksy-connector-sync"
        private const val NOW = "marksy-connector-sync-now"
        private const val MAX_RETRIES = 3

        fun schedule(context: Context) {
            val wm = WorkManager.getInstance(context)
            wm.enqueueUniquePeriodicWork(
                PERIODIC, ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<ConnectorSyncWorker>(30, TimeUnit.MINUTES).setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 60, TimeUnit.SECONDS).build()
            )
            wm.enqueueUniqueWork(NOW, ExistingWorkPolicy.REPLACE, OneTimeWorkRequestBuilder<ConnectorSyncWorker>().build())
        }

        fun cancel(context: Context) = WorkManager.getInstance(context).cancelUniqueWork(PERIODIC)
    }
}
