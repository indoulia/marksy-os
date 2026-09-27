package com.marksy.os.pulse

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.marksy.os.MainActivity
import com.marksy.os.data.MarksyContainer
import com.marksy.os.ui.DailyDigestModel
import com.marksy.os.upstox.UpstoxFeed
import com.marksy.os.upstox.UpstoxIndices
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/** What the Pulse says; [publicText] is the lock-screen version and never names a sender, merchant or amount. */
data class PulseText(val title: String, val text: String, val publicText: String) {
    companion object {
        fun build(total: Int, attention: Int, busiest: List<Pair<String, Int>>, next: String?, index: Pair<String, Double>?): PulseText {
            val market = index?.let { (name, pct) -> "$name ${String.format(Locale.US, "%+.2f%%", pct)}" }
            val need = when (attention) { 0 -> null; 1 -> "1 needs your attention"; else -> "$attention need your attention" }
            val title = listOfNotNull(need ?: if (total == 0) "All clear" else "Nothing needs you", market).joinToString(" · ").ifBlank { "All clear" }
            val text = if (total == 0) "No notifications captured yet today." else listOfNotNull(
                "$total notifications today.",
                busiest.takeIf { it.isNotEmpty() }?.take(2)?.joinToString(prefix = "Busiest: ", postfix = ".") { (n, c) -> "$n ($c)" },
                next?.let { "Next: $it" }
            ).joinToString(" ")
            val publicText = listOfNotNull("$total notifications today" + if (attention > 0) ", $attention need attention" else "", market).joinToString(" · ")
            return PulseText(title, text, publicText)
        }
    }
}

/** Marksy Pulse: a quiet, ongoing notification with today's summary, readable at a glance on the lock screen. */
object MarksyPulse {
    private const val PREFS = "marksy_pulse"
    private const val CHANNEL_ID = "marksy_pulse"
    private const val NOTIFICATION_ID = 7_070
    private const val WORK = "marksy-pulse"

    fun enabled(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("on", false)

    suspend fun setEnabled(context: Context, on: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("on", on).apply()
        val wm = WorkManager.getInstance(context)
        if (on) {
            wm.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, PeriodicWorkRequestBuilder<PulseWorker>(30, TimeUnit.MINUTES).build())
            update(context)
        } else {
            wm.cancelUniqueWork(WORK)
            context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
        }
    }

    suspend fun update(context: Context) {
        if (!enabled(context)) return
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val now = System.currentTimeMillis()
        val dayStart = java.time.LocalDate.now().atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        val db = MarksyContainer.database(context)
        val digest = DailyDigestModel.build(db.notificationEventDao().findInRange(dayStart, now, 5_000), now)
        val next = db.planItemDao().all().filter { it.completedAt == null && (it.dueAt ?: 0) >= dayStart }.minByOrNull { it.dueAt!! }?.let { p ->
            listOfNotNull(p.title, p.amountMinor?.let { "₹" + String.format(Locale.getDefault(), "%,d", it / 100) }, SimpleDateFormat("d MMM", Locale.getDefault()).format(Date(p.dueAt!!))).joinToString(" · ")
        }
        // Only a price the live feed has now; an old one would pass for today's move.
        val index = UpstoxFeed.quotes.value[UpstoxIndices.NIFTY_50]?.changePct?.takeIf { UpstoxFeed.lastTickAt.value > dayStart }?.let { "NIFTY 50" to it }
        post(context, PulseText.build(digest.totalNotifications, digest.attentionEvents.size, digest.topSources, next, index))
    }

    private fun post(context: Context, p: PulseText) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Marksy Pulse", NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) })
        }
        val open = PendingIntent.getActivity(context, NOTIFICATION_ID, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP), PendingIntent.FLAG_IMMUTABLE)
        val ask = PendingIntent.getActivity(
            context, NOTIFICATION_ID + 1,
            Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN, MainActivity.OPEN_ASK).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val public = NotificationCompat.Builder(context, CHANNEL_ID).setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentTitle("Marksy Pulse").setContentText(p.publicText).build()
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentTitle(p.title)
            .setContentText(p.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(p.text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(public)
            .setContentIntent(open)
            .addAction(0, "Ask Marksy", ask)
            .build()
        runCatching { manager.notify(NOTIFICATION_ID, notification) }
    }
}

class PulseWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        runCatching { MarksyPulse.update(applicationContext) }
        return Result.success()
    }
}
