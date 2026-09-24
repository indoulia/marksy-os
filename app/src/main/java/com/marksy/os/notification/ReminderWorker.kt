package com.marksy.os.notification

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.marksy.os.MainActivity
import com.marksy.os.data.ActionPlatform
import com.marksy.os.data.ActionState
import com.marksy.os.data.MarksyContainer
import java.util.concurrent.TimeUnit

/** Real device capabilities for EPIC-014. Nothing here reaches the network. */
class AndroidActionPlatform(private val context: Context) : ActionPlatform {
    override fun canLaunch(sourcePackage: String): Boolean =
        context.packageManager.getLaunchIntentForPackage(sourcePackage) != null

    override fun canPostNotifications(): Boolean =
        (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
            context.getSystemService(NotificationManager::class.java).areNotificationsEnabled()

    override fun launch(sourcePackage: String): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(sourcePackage) ?: return false
        return runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true }.getOrDefault(false)
    }

    // Unique per action + KEEP makes recovery rescheduling idempotent.
    override fun scheduleReminder(actionId: Long, atMillis: Long) {
        val delay = (atMillis - System.currentTimeMillis()).coerceAtLeast(0L)
        val request = OneTimeWorkRequestBuilder<ReminderWorker>()
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .setInputData(workDataOf(ReminderWorker.KEY_ACTION_ID to actionId))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork("marksy-reminder-$actionId", ExistingWorkPolicy.KEEP, request)
    }
}

class ReminderWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val actionId = inputData.getLong(KEY_ACTION_ID, -1L).takeIf { it > 0 } ?: return Result.failure()
        val state = MarksyContainer.actions(applicationContext).completeReminder(actionId) { _, event ->
            post(actionId, event?.title?.ifBlank { null } ?: "Marksy reminder", event?.sourceName.orEmpty())
        }
        return if (state == ActionState.FAILED) Result.failure() else Result.success()
    }

    // The body never leaves Marksy; the captured title is only shown when the device is unlocked.
    private fun post(actionId: Long, title: String, source: String): Boolean {
        val platform = AndroidActionPlatform(applicationContext)
        if (!platform.canPostNotifications()) return false
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Reminders", NotificationManager.IMPORTANCE_DEFAULT))
        }
        val open = PendingIntent.getActivity(
            applicationContext, actionId.toInt(),
            Intent(applicationContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val public = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle("Marksy reminder")
            .setContentText(if (source.isBlank()) "Open Marksy" else source)
            .build()
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(title.take(80))
            .setContentText(if (source.isBlank()) "Reminder" else "Reminder · $source")
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            // Explicit redacted version: the lock screen shows only "Marksy reminder" and the source app.
            .setPublicVersion(public)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        return runCatching { manager.notify(NOTIFICATION_BASE + actionId.toInt(), notification); true }.getOrDefault(false)
    }

    companion object {
        const val KEY_ACTION_ID = "actionId"
        private const val CHANNEL_ID = "marksy_reminders"
        private const val NOTIFICATION_BASE = 40_000
    }
}
