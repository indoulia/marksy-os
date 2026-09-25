package com.marksy.os.notification

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
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
import com.marksy.os.data.local.PlanItemEntity
import com.marksy.os.plan.PlanAlarms
import com.marksy.os.plan.PlanKind
import com.marksy.os.plan.PlanRules
import com.marksy.os.plan.PlanStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

/** One unique WorkManager job per plan item and alert stage (3 days, 1 day, on the day); re-scheduling replaces them. */
class PlanAlarmScheduler(context: Context, private val clock: () -> Long = System::currentTimeMillis) : PlanAlarms {
    private val app = context.applicationContext

    override fun schedule(item: PlanItemEntity) {
        val due = item.dueAt ?: return
        PlanRules.alertTimes(PlanKind.valueOf(item.kind), due, clock()).forEach { at ->
            val request = OneTimeWorkRequestBuilder<PlanAlarmWorker>()
                .setInitialDelay(at - clock(), TimeUnit.MILLISECONDS)
                .setInputData(workDataOf(KEY_ITEM_ID to item.id))
                .build()
            WorkManager.getInstance(app).enqueueUniqueWork(workName(item.id, stage(due - at)), ExistingWorkPolicy.REPLACE, request)
        }
    }

    override fun cancel(itemId: Long) {
        STAGES.forEach { WorkManager.getInstance(app).cancelUniqueWork(workName(itemId, it)) }
    }

    companion object {
        const val EXTRA_OPEN_PLAN = "com.marksy.os.OPEN_PLAN"
        internal const val KEY_ITEM_ID = "planItemId"
        private const val CHANNEL_ID = "marksy_reminders"
        private const val DAY_MS = 24 * 60 * 60 * 1000L
        private val STAGES = listOf("3d", "1d", "day")
        // Notification ids apart from ReminderScheduler's (event ids).
        internal fun notificationId(itemId: Long) = (1_000_000_000L + itemId).toInt()

        private fun workName(itemId: Long, stage: String) = "plan-$itemId-$stage"
        private fun stage(lead: Long) = when { lead >= 3 * DAY_MS -> "3d"; lead >= DAY_MS -> "1d"; else -> "day" }

        internal fun post(context: Context, item: PlanItemEntity, now: Long) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) return
            val manager = context.getSystemService(NotificationManager::class.java)
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Reminders", NotificationManager.IMPORTANCE_HIGH))
            }
            val id = notificationId(item.id)
            val open = PendingIntent.getActivity(
                context, id,
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra(EXTRA_OPEN_PLAN, true),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val done = PendingIntent.getBroadcast(
                context, id,
                Intent(context, PlanDoneReceiver::class.java).putExtra(KEY_ITEM_ID, item.id),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val text = listOfNotNull(com.marksy.os.plan.PlanText.dueLabel(item.dueAt, now), com.marksy.os.plan.PlanText.amount(item.amountMinor)).joinToString(" · ")
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_popup_reminder)
                .setContentTitle(item.title)
                .setContentText(text)
                .setContentIntent(open)
                .addAction(0, "Mark done", done)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .build()
            NotificationManagerCompat.from(context).notify(id, notification)
        }
    }
}

class PlanAlarmWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val id = inputData.getLong(PlanAlarmScheduler.KEY_ITEM_ID, -1L)
        val item = MarksyContainer.database(applicationContext).planItemDao().get(id) ?: return Result.success()
        if (item.status != PlanStatus.DONE.name) PlanAlarmScheduler.post(applicationContext, item, System.currentTimeMillis())
        return Result.success()
    }
}

class PlanDoneReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra(PlanAlarmScheduler.KEY_ITEM_ID, -1L).takeIf { it >= 0 } ?: return
        NotificationManagerCompat.from(context).cancel(PlanAlarmScheduler.notificationId(id))
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try { MarksyContainer.plan(context).setStatus(id, PlanStatus.DONE) } finally { pending.finish() }
        }
    }
}
