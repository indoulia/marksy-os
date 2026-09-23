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
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.marksy.os.MainActivity
import com.marksy.os.data.MarksyContainer
import java.util.concurrent.TimeUnit

/** "Remind me" on a captured notification: one WorkManager job per event, re-scheduling replaces it. */
object ReminderScheduler {
    const val EXTRA_EVENT_ID = "com.marksy.os.EVENT_ID"
    private const val CHANNEL_ID = "marksy_reminders"
    private const val KEY_EVENT_ID = "eventId"

    private fun workName(eventId: Long) = "reminder-$eventId"

    fun schedule(context: Context, eventId: Long, atMillis: Long) {
        val delay = (atMillis - System.currentTimeMillis()).coerceAtLeast(0L)
        val request = OneTimeWorkRequestBuilder<ReminderWorker>()
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .setInputData(workDataOf(KEY_EVENT_ID to eventId))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(workName(eventId), ExistingWorkPolicy.REPLACE, request)
    }

    fun cancel(context: Context, eventId: Long) {
        WorkManager.getInstance(context).cancelUniqueWork(workName(eventId))
    }

    internal fun eventId(data: androidx.work.Data): Long = data.getLong(KEY_EVENT_ID, -1L)

    internal fun post(context: Context, eventId: Long, title: String, text: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Reminders", NotificationManager.IMPORTANCE_HIGH))
        }
        val open = PendingIntent.getActivity(
            context,
            eventId.toInt(),
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(EXTRA_EVENT_ID, eventId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        NotificationManagerCompat.from(context).notify(eventId.toInt(), notification)
    }
}

class ReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val eventId = ReminderScheduler.eventId(inputData)
        if (eventId < 0) return Result.success()
        val dao = MarksyContainer.database(applicationContext).notificationEventDao()
        val event = dao.findById(eventId) ?: return Result.success()
        ReminderScheduler.post(
            applicationContext,
            eventId,
            "Reminder: ${event.title.ifBlank { event.sourceName }}",
            listOf(event.sourceName, event.body).filter { it.isNotBlank() }.joinToString(" · ")
        )
        dao.setReminder(eventId, null)
        return Result.success()
    }
}
