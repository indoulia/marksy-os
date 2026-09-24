package com.marksy.os.data

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import com.marksy.os.connector.ConnectorRegistry
import com.marksy.os.data.local.MarksyDatabase
import com.marksy.os.intelligence.EventIntelligencePipeline
import com.marksy.os.intelligence.MarksyHealth
import com.marksy.os.notification.NotificationListenerStatus
import com.marksy.os.notification.WhatsAppConnectorStatus
import com.marksy.os.notification.WhatsAppSenderWatchlist
import java.time.LocalDate

/** Gathers real runtime state for [MarksyHealth.evaluate]. Reads counts and system state only. */
class HealthRepository(private val context: Context, private val clock: () -> Long = System::currentTimeMillis) {
    private val db = MarksyDatabase.getInstance(context)

    suspend fun report(): MarksyHealth.Report = MarksyHealth.evaluate(inputs())

    suspend fun inputs(): MarksyHealth.Inputs {
        val now = clock()
        val dayStart = now - DAY
        val metrics = MetricsRecorder(db.metricsDao(), clock)
        val today = LocalDate.parse(metrics.day(now))
        val rows = metrics.range(today.minusDays(6), today).filter { it.scope == MetricsRecorder.SCOPE_ALL }
        fun sum(metric: String, days: List<String>? = null) = rows.filter { it.metric == metric && (days == null || it.day in days) }.sumOf { it.value }
        val todayKey = listOf(today.toString())
        val ai = db.aiInvocationDao().outcomesSince(now - 7 * DAY)
        val aiCalls = ai.sumOf { it.count }
        val eventDao = db.notificationEventDao()
        val connectorDao = db.connectorDao()

        val connectors = listOf(
            Triple(ConnectorRegistry.NOTIFICATION_LISTENER, NotificationListenerStatus.isEnabled(context), true),
            Triple(ConnectorRegistry.WHATSAPP_WATCHLIST, WhatsAppConnectorStatus.isAccessibilityServiceEnabled(context), WhatsAppSenderWatchlist.get(context).isNotEmpty())
        ).map { (d, granted, configured) ->
            val todayScope = metrics.range(today, today).filter { it.scope == MetricsRecorder.connector(d.id) && it.metric == Metric.CAPTURED }.sumOf { it.value }
            MarksyHealth.ConnectorInput(
                id = d.id, label = d.label, permissionGranted = granted, configured = configured,
                lifecycle = connectorDao.eventsSince(d.id, dayStart), stateBeforeWindow = connectorDao.lastLifecycleBefore(d.id, dayStart),
                capturedToday = todayScope, failures24h = connectorDao.failuresSince(dayStart, 100).count { it.connectorId == d.id }
            )
        }

        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val status = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val power = context.getSystemService(PowerManager::class.java)

        return MarksyHealth.Inputs(
            nowMillis = now,
            windowStart = dayStart,
            capturedToday = sum(Metric.CAPTURED, todayKey),
            captured7d = sum(Metric.CAPTURED),
            duplicates7d = sum(Metric.DUPLICATE),
            unclassified7d = sum(Metric.UNCLASSIFIED),
            processingFailures7d = sum(Metric.PROCESSING_FAILED),
            captureFailures7d = sum(Metric.CAPTURE_FAILED),
            important7d = sum(Metric.IMPORTANT),
            processingMsSum7d = sum(Metric.PROCESSING_MS_SUM),
            processingCount7d = sum(Metric.PROCESSING_COUNT),
            deliveryDelayMsSum7d = sum(Metric.DELIVERY_DELAY_MS_SUM),
            lastCaptureAt = eventDao.lastCreatedAt(),
            intelligenceBacklog = eventDao.countNeedingIntelligence(EventIntelligencePipeline.VERSION),
            pendingTradingDeliveries = eventDao.countDeliveryState("PENDING") + eventDao.countDeliveryState("IN_FLIGHT"),
            failedTradingDeliveries = eventDao.countDeliveryState("FAILED"),
            pendingReminders = db.eventActionDao().pendingOfType("REMIND").size,
            aiCalls7d = aiCalls,
            aiFailures7d = ai.filter { it.outcome != "OK" && it.outcome != "NO_MODEL" }.sumOf { it.count },
            aiAvgLatencyMs = ai.filter { it.outcome == "OK" }.takeIf { it.isNotEmpty() }?.let { ok -> ok.sumOf { it.avgLatencyMs * it.count } / ok.sumOf { it.count } },
            databaseBytes = listOf("", "-wal", "-shm").sumOf { context.getDatabasePath("marksy_os.db$it").takeIf { f -> f.exists() }?.length() ?: 0L },
            eventRows = eventDao.countAll(),
            batteryPercent = if (level >= 0 && scale > 0) level * 100 / scale else null,
            charging = if (status == -1) null else status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL,
            batteryOptimizationExempt = power?.isIgnoringBatteryOptimizations(context.packageName),
            connectors = connectors
        )
    }

    private companion object { const val DAY = 24 * 60 * 60 * 1000L }
}
