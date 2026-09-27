package com.marksy.os.alerts

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
import com.marksy.os.upstox.UpstoxApiClient
import com.marksy.os.upstox.UpstoxFeed
import com.marksy.os.upstox.UpstoxInstruments
import com.marksy.os.upstox.UpstoxTokenStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.TimeUnit

data class PriceAlert(val id: Long, val symbol: String, val price: Double, val above: Boolean, val createdAt: Long)

object PriceAlertRules {
    fun above(target: Double, lastPrice: Double) = target >= lastPrice

    fun triggered(alerts: List<PriceAlert>, prices: Map<String, Double>): List<Pair<PriceAlert, Double>> =
        alerts.mapNotNull { a -> prices[a.symbol]?.takeIf { p -> if (a.above) p >= a.price else p <= a.price }?.let { a to it } }
}

/** Price alerts kept on this device; each fires once and is then removed. */
object PriceAlertStore {
    private const val PREFS = "marksy_price_alerts"
    private const val KEY = "alerts"
    private val _alerts = MutableStateFlow<List<PriceAlert>?>(null)

    fun alerts(context: Context): StateFlow<List<PriceAlert>?> { if (_alerts.value == null) _alerts.value = read(context); return _alerts.asStateFlow() }

    fun add(context: Context, symbol: String, price: Double, lastPrice: Double): PriceAlert {
        val alert = PriceAlert(System.currentTimeMillis(), symbol.trim().uppercase(), price, PriceAlertRules.above(price, lastPrice), System.currentTimeMillis())
        write(context, current(context) + alert)
        PriceAlertWorker.schedule(context)
        return alert
    }

    fun remove(context: Context, ids: Collection<Long>) {
        val left = current(context).filterNot { it.id in ids }
        write(context, left)
        if (left.isEmpty()) PriceAlertWorker.cancel(context)
    }

    fun current(context: Context): List<PriceAlert> = _alerts.value ?: read(context).also { _alerts.value = it }

    private fun read(context: Context): List<PriceAlert> = runCatching {
        val a = JSONArray(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "[]"))
        (0 until a.length()).map { i -> a.getJSONObject(i).let { o -> PriceAlert(o.getLong("id"), o.getString("symbol"), o.getDouble("price"), o.getBoolean("above"), o.optLong("createdAt")) } }
    }.getOrDefault(emptyList())

    private fun write(context: Context, alerts: List<PriceAlert>) {
        val a = JSONArray().apply { alerts.forEach { put(JSONObject().put("id", it.id).put("symbol", it.symbol).put("price", it.price).put("above", it.above).put("createdAt", it.createdAt)) } }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, a.toString()).apply()
        _alerts.value = alerts
    }

    /** Fires whatever [prices] (symbol → last price) crossed, then forgets those alerts. */
    fun check(context: Context, prices: Map<String, Double>) {
        val fired = PriceAlertRules.triggered(current(context), prices)
        if (fired.isEmpty()) return
        fired.forEach { (a, p) -> PriceAlertNotifier.post(context, a, p) }
        remove(context, fired.map { it.first.id })
    }

    /** While the app runs, watch alert symbols on the live feed so alerts fire on the tick, not the next worker run. */
    suspend fun monitor(context: Context) {
        val app = context.applicationContext
        runCatching { UpstoxInstruments.load(app) }
        combine(alerts(app), UpstoxFeed.quotes) { alerts, quotes -> alerts.orEmpty() to quotes }.collect { (alerts, quotes) ->
            if (alerts.isEmpty()) return@collect
            val keys = alerts.map { it.symbol }.distinct().mapNotNull { s -> UpstoxInstruments.keyFor(s)?.let { it to s } }.toMap()
            UpstoxFeed.watch(keys.keys)
            check(app, keys.mapNotNull { (k, s) -> quotes[k]?.let { s to it.lastPrice } }.toMap())
        }
    }
}

object PriceAlertNotifier {
    const val EXTRA_OPEN_SYMBOL = "com.marksy.os.OPEN_SYMBOL"
    private const val CHANNEL_ID = "marksy_price_alerts"

    fun post(context: Context, alert: PriceAlert, price: Double) {
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val manager = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Price alerts", NotificationManager.IMPORTANCE_HIGH))
        val open = PendingIntent.getActivity(
            context, alert.id.toInt(),
            Intent(context, MainActivity::class.java).putExtra(EXTRA_OPEN_SYMBOL, alert.symbol).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        fun rupees(v: Double) = "₹" + String.format(Locale.getDefault(), "%,.2f", v)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle("${alert.symbol} ${if (alert.above) "rose above" else "fell below"} ${rupees(alert.price)}")
            .setContentText("Now ${rupees(price)} · Marksy price alert")
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { manager.notify(alert.id.toInt(), notification) }
    }
}

/** Checks alerts every 15 minutes while NSE is open, for when the app isn't running. */
class PriceAlertWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val alerts = PriceAlertStore.current(applicationContext)
        if (alerts.isEmpty()) { cancel(applicationContext); return Result.success() }
        if (!UpstoxFeed.isMarketOpen()) return Result.success()
        val store = UpstoxTokenStore(applicationContext)
        if (!store.hasToken()) return Result.success()
        return try {
            UpstoxInstruments.load(applicationContext)
            val keys = alerts.map { it.symbol }.distinct().mapNotNull { s -> UpstoxInstruments.keyFor(s)?.let { it to s } }.toMap()
            val ltp = UpstoxApiClient { store.getToken() }.ltp(keys.keys.toList())
            PriceAlertStore.check(applicationContext, ltp.mapNotNull { (k, q) -> keys[k]?.let { it to q.lastPrice } }.toMap())
            Result.success()
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
            com.marksy.os.ai.DiagLog.i("MarksyAlerts", "check failed: ${e.javaClass.simpleName}")
            Result.retry()
        }
    }

    companion object {
        private const val WORK = "marksy-price-alerts"
        fun schedule(context: Context) = WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK, ExistingPeriodicWorkPolicy.KEEP, PeriodicWorkRequestBuilder<PriceAlertWorker>(15, TimeUnit.MINUTES).build()
        )
        fun cancel(context: Context) = WorkManager.getInstance(context).cancelUniqueWork(WORK)
    }
}
