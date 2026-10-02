package com.marksy.os.alerts

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
import com.marksy.os.market.IpoDetailDto
import com.marksy.os.market.IpoDetailFormatter
import com.marksy.os.market.IpoLifecycle
import com.marksy.os.market.MarketDataState
import com.marksy.os.market.bounds
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object IpoBandAlertRules {
    /** What to tell the reader once the band is out, or null while it is not. */
    fun message(detail: IpoDetailDto): String? {
        val (lo, hi) = detail.summary.terms?.priceBand.bounds() ?: return null
        val lot = IpoLifecycle.lotSize(detail.summary)?.let { " · lot of $it" }.orEmpty()
        return "₹${IpoDetailFormatter.number(lo)}–${IpoDetailFormatter.number(hi)} a share$lot"
    }
}

/** IPOs the reader asked to hear about once their price band is out (id → company name); kept on the phone. */
object IpoBandWatch {
    private const val PREFS = "marksy_ipo_band_watch"
    private const val KEY = "watching"
    private val _watching = MutableStateFlow<Map<String, String>?>(null)

    fun watching(context: Context): StateFlow<Map<String, String>?> { if (_watching.value == null) _watching.value = read(context); return _watching.asStateFlow() }

    fun current(context: Context): Map<String, String> = _watching.value ?: read(context).also { _watching.value = it }

    fun set(context: Context, id: String, name: String, on: Boolean) {
        val next = current(context).let { if (on) it + (id to name) else it - id }
        write(context, next)
        if (next.isEmpty()) IpoBandAlertWorker.cancel(context) else IpoBandAlertWorker.schedule(context)
    }

    private fun read(context: Context): Map<String, String> = runCatching {
        val o = JSONObject(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "{}") ?: "{}")
        o.keys().asSequence().associateWith { o.optString(it) }
    }.getOrDefault(emptyMap())

    private fun write(context: Context, watching: Map<String, String>) {
        _watching.value = watching
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, JSONObject(watching).toString()).apply()
    }
}

/** Checks every few hours whether a watched IPO's price band is out, then notifies once and stops watching it. */
class IpoBandAlertWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val watching = IpoBandWatch.current(applicationContext)
        if (watching.isEmpty()) { cancel(applicationContext); return Result.success() }
        val repository = MarksyContainer.marketIntelligence(applicationContext)
        watching.forEach { (id, name) ->
            val detail = (repository.ipoDetail(id) as? MarketDataState.Loaded)?.value ?: return@forEach
            IpoBandAlertRules.message(detail)?.let { text ->
                post(applicationContext, id, name, text)
                IpoBandWatch.set(applicationContext, id, name, on = false)
            }
        }
        return Result.success()
    }

    private fun post(context: Context, id: String, name: String, text: String) {
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val manager = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "IPO alerts", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(
            context, id.hashCode(), Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle("$name price band is out")
            .setContentText("$text · Marksy IPOs")
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { manager.notify(id.hashCode(), notification) }
    }

    companion object {
        private const val WORK = "marksy-ipo-band-alerts"
        private const val CHANNEL_ID = "marksy_ipo_alerts"
        fun schedule(context: Context) = WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<IpoBandAlertWorker>(3, TimeUnit.HOURS).setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
        )
        fun cancel(context: Context) = WorkManager.getInstance(context).cancelUniqueWork(WORK)
    }
}
