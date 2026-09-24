package com.marksy.os.data

import android.content.Context
import com.marksy.os.data.local.MarksyDatabase
import com.marksy.os.data.local.MetricCounterEntity
import com.marksy.os.intelligence.MarksyHealth
import com.marksy.os.intelligence.ValidationReport
import java.time.LocalDate

/** EPIC-023: validation period bookkeeping, daily gauge snapshots and the report. */
class ValidationRepository(private val context: Context, private val clock: () -> Long = System::currentTimeMillis) {
    private val prefs = context.getSharedPreferences("marksy_validation", Context.MODE_PRIVATE)
    private val db = MarksyDatabase.getInstance(context)
    private val metrics = MetricsRecorder(db.metricsDao(), clock)

    fun startDay(): LocalDate? = prefs.getString(KEY_START, null)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

    fun start(): LocalDate {
        val today = LocalDate.parse(metrics.day())
        prefs.edit().putString(KEY_START, today.toString()).apply()
        return today
    }

    /**
     * Records today's gauges from live state. Idempotent within a day (last snapshot wins), called
     * by the daily RetentionWorker and whenever the report is opened.
     */
    suspend fun snapshot() {
        val inputs = HealthRepository(context, clock).inputs()
        val day = metrics.day()
        val listener = inputs.connectors.firstOrNull()
        val uptime = listener?.let { MarksyHealth.uptime(it.lifecycle, it.stateBeforeWindow, inputs.windowStart, inputs.nowMillis) }
        val gauges = listOfNotNull(
            ValidationReport.GAUGE_DB_BYTES to inputs.databaseBytes,
            ValidationReport.GAUGE_EVENT_ROWS to inputs.eventRows.toLong(),
            inputs.batteryPercent?.let { ValidationReport.GAUGE_BATTERY_PCT to it.toLong() },
            inputs.batteryOptimizationExempt?.let { ValidationReport.GAUGE_BATTERY_EXEMPT to if (it) 1L else 0L },
            uptime?.let { ValidationReport.GAUGE_UPTIME_PCT_X100 to (it * 100).toLong() },
            inputs.lastCaptureAt?.let { ValidationReport.GAUGE_HOURS_SINCE_CAPTURE to (inputs.nowMillis - it) / HOUR }
        )
        gauges.forEach { (name, value) -> db.metricsDao().set(MetricCounterEntity(day, MetricsRecorder.SCOPE_ALL, name, value)) }
    }

    suspend fun report(): ValidationReport.Report? {
        val start = startDay() ?: return null
        val today = LocalDate.parse(metrics.day())
        val end = minOf(today, start.plusDays(DAYS - 1))
        val startMillis = start.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        return ValidationReport.build(start, end, metrics.range(start, end), db.connectorDao().failuresSince(startMillis, 200))
    }

    companion object {
        const val DAYS = 30L
        private const val KEY_START = "start_day"
        private const val HOUR = 60 * 60 * 1000L
    }
}
