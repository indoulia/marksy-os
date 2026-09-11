package com.marksy.os.data

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

object RetentionScheduler {
    private const val WORK_NAME = "marksy-local-retention"
    private const val STARTUP_WORK_NAME = "marksy-local-retention-startup"

    fun schedule(context: Context) {
        val workManager = WorkManager.getInstance(context)
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
            .build()

        val startupRequest = OneTimeWorkRequestBuilder<RetentionWorker>()
            .setConstraints(constraints)
            .build()
        workManager.enqueueUniqueWork(
            STARTUP_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            startupRequest
        )

        val periodicRequest = PeriodicWorkRequestBuilder<RetentionWorker>(1, TimeUnit.DAYS)
            .setConstraints(constraints)
            .build()
        workManager.enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            periodicRequest
        )
    }
}
