package com.marksy.os.alerts

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.marksy.os.MainActivity
import com.marksy.os.data.MarksyContainer
import com.marksy.os.market.MarketDataState
import com.marksy.os.market.TipAlertDto
import java.util.concurrent.TimeUnit

/** Which of the server's tip alerts become phone notifications: unread, newer than the last one noticed, once. */
object TipAlertNotices {
    private const val GROUP_THRESHOLD = 3

    data class Plan(val notify: List<TipAlertDto>, val summary: Boolean, val lastSeenId: Long)

    /** The first poll only records where it is, so a customer's alert history never floods the shade. */
    fun plan(alerts: List<TipAlertDto>, lastSeenId: Long?): Plan {
        val newest = maxOf(lastSeenId ?: 0L, alerts.maxOfOrNull { it.id } ?: 0L)
        if (lastSeenId == null) return Plan(emptyList(), summary = false, lastSeenId = newest)
        val fresh = alerts.filter { it.unread && it.alertType in TipAlertDto.TYPES && it.id > lastSeenId }.sortedByDescending { it.id }
        return Plan(fresh, summary = fresh.size > GROUP_THRESHOLD, lastSeenId = newest)
    }

    fun title(alertType: String): String = when (alertType) {
        "TIP_NEW" -> "New call"
        "TIP_ENTERED" -> "Entry reached"
        else -> "Call closed"
    }

    fun summaryTitle(alerts: List<TipAlertDto>): String = "${alerts.size} tip alerts"
}

/** Posts tip alerts with the server's own (already masked) message text; a tap opens My tips · Following. */
object TipAlertNotifier {
    private const val CHANNEL_ID = "marksy_tip_alerts"
    private const val GROUP = "com.marksy.os.TIP_ALERTS"
    private const val SUMMARY_ID = 0x71A7
    private const val PREFS = "marksy_tip_alerts"
    private const val LAST_SEEN = "last_seen_alert_id"
    private const val ASKED = "asked_notification_permission"

    fun allowed(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /** One-time: true the first time a follow could want the permission, never again. */
    fun shouldAskPermission(context: Context): Boolean {
        if (allowed(context)) return false
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(ASKED, false)) return false
        prefs.edit().putBoolean(ASKED, true).apply()
        return true
    }

    fun lastSeen(context: Context): Long? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(LAST_SEEN, -1L).takeIf { it >= 0 }

    fun remember(context: Context, id: Long) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong(LAST_SEEN, id).apply()

    fun post(context: Context, plan: TipAlertNotices.Plan) {
        if (plan.notify.isEmpty() || !allowed(context)) return
        val manager = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Tip alerts", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(
            context, SUMMARY_ID,
            Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN, MainActivity.OPEN_FOLLOWING)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        fun builder() = NotificationCompat.Builder(context, CHANNEL_ID).setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentIntent(open).setAutoCancel(true).setGroup(GROUP)
        plan.notify.forEach { alert ->
            val notification = builder().setContentTitle(TipAlertNotices.title(alert.alertType)).setContentText(alert.message)
                .setStyle(NotificationCompat.BigTextStyle().bigText(alert.message)).setWhen(alert.triggeredAt).build()
            runCatching { manager.notify(alert.id.toInt(), notification) }
        }
        if (plan.summary) {
            val inbox = NotificationCompat.InboxStyle().also { style -> plan.notify.take(5).forEach { style.addLine(it.message) } }
            val summary = builder().setContentTitle(TipAlertNotices.summaryTitle(plan.notify)).setStyle(inbox).setGroupSummary(true).build()
            runCatching { manager.notify(SUMMARY_ID, summary) }
        }
    }
}

/** Every 15 minutes (WorkManager's minimum), reads the server's newest tip alerts and notifies the ones not yet seen. */
class TipAlertWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val context = applicationContext
        return when (val alerts = MarksyContainer.marketIntelligence(context).tipAlerts()) {
            is MarketDataState.Loaded, is MarketDataState.Empty -> {
                val plan = TipAlertNotices.plan((alerts as? MarketDataState.Loaded)?.value.orEmpty(), TipAlertNotifier.lastSeen(context))
                TipAlertNotifier.post(context, plan)
                TipAlertNotifier.remember(context, plan.lastSeenId)
                Result.success()
            }
            is MarketDataState.Error -> Result.retry()
            else -> Result.success()
        }
    }

    companion object {
        private const val WORK = "marksy-tip-alerts"

        fun schedule(context: Context) = WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<TipAlertWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
        )
    }
}
