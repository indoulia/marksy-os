package com.marksy.os.intelligence

import com.marksy.os.data.local.ConnectorEventEntity

/**
 * EPIC-022 runtime health. [Inputs] is gathered from real runtime state (DB counters, connector
 * lifecycle log, system services); [evaluate] is pure so stale detection and diagnostics are testable.
 * Nothing here contains notification content.
 */
object MarksyHealth {
    enum class Level { OK, WARNING, CRITICAL }

    data class ConnectorInput(
        val id: String,
        val label: String,
        /** Whether the OS currently grants the permission this connector needs. */
        val permissionGranted: Boolean,
        /** Connector-specific configuration present (e.g. a WhatsApp watch-list). */
        val configured: Boolean,
        val lifecycle: List<ConnectorEventEntity>,
        val stateBeforeWindow: ConnectorEventEntity?,
        val capturedToday: Long,
        val failures24h: Int
    )

    data class Inputs(
        val nowMillis: Long,
        val windowStart: Long,
        val capturedToday: Long,
        val captured7d: Long,
        val duplicates7d: Long,
        val unclassified7d: Long,
        val processingFailures7d: Long,
        val captureFailures7d: Long,
        val important7d: Long,
        val processingMsSum7d: Long,
        val processingCount7d: Long,
        val deliveryDelayMsSum7d: Long,
        val lastCaptureAt: Long?,
        val intelligenceBacklog: Int,
        val pendingTradingDeliveries: Int,
        val failedTradingDeliveries: Int,
        val pendingReminders: Int,
        val aiCalls7d: Int,
        val aiFailures7d: Int,
        val aiAvgLatencyMs: Double?,
        val databaseBytes: Long,
        val eventRows: Int,
        val batteryPercent: Int?,
        val charging: Boolean?,
        val batteryOptimizationExempt: Boolean?,
        val connectors: List<ConnectorInput>
    )

    data class ConnectorHealth(val id: String, val label: String, val level: Level, val status: String, val uptimePercent: Double?, val capturedToday: Long)
    data class Diagnostic(val level: Level, val title: String, val action: String?)
    data class Metric(val label: String, val value: String)

    data class Report(
        val level: Level,
        val summary: String,
        val stale: Boolean,
        val connectors: List<ConnectorHealth>,
        val metrics: List<Metric>,
        val diagnostics: List<Diagnostic>
    )

    /** Share of [from, to] during which the connector was connected, from its lifecycle log. */
    fun uptime(events: List<ConnectorEventEntity>, before: ConnectorEventEntity?, from: Long, to: Long): Double? {
        val lifecycle = events.filter { it.type == ConnectorEventEntity.CONNECTED || it.type == ConnectorEventEntity.DISCONNECTED }.sortedBy { it.at }
        if (lifecycle.isEmpty() && before == null) return null
        var connected = before?.type == ConnectorEventEntity.CONNECTED
        var cursor = from
        var up = 0L
        lifecycle.filter { it.at in from..to }.forEach { e ->
            if (connected) up += e.at - cursor
            cursor = e.at
            connected = e.type == ConnectorEventEntity.CONNECTED
        }
        if (connected) up += to - cursor
        return if (to > from) up * 100.0 / (to - from) else null
    }

    fun evaluate(i: Inputs): Report {
        val diagnostics = mutableListOf<Diagnostic>()
        val connectors = i.connectors.map { c ->
            val up = uptime(c.lifecycle, c.stateBeforeWindow, i.windowStart, i.nowMillis)
            val lastLifecycle = (c.lifecycle + listOfNotNull(c.stateBeforeWindow)).filter { it.type == ConnectorEventEntity.CONNECTED || it.type == ConnectorEventEntity.DISCONNECTED }.maxByOrNull { it.at }
            val (level, status) = when {
                !c.permissionGranted -> Level.CRITICAL to "Permission not granted"
                !c.configured -> Level.WARNING to "Not configured"
                lastLifecycle?.type == ConnectorEventEntity.DISCONNECTED -> Level.CRITICAL to "Disconnected"
                c.failures24h > 0 -> Level.WARNING to "${c.failures24h} failure${if (c.failures24h == 1) "" else "s"} in 24h"
                else -> Level.OK to "Running"
            }
            if (!c.permissionGranted) diagnostics += Diagnostic(Level.CRITICAL, "${c.label}: permission is off, nothing is being captured", "Grant access in Settings")
            else if (lastLifecycle?.type == ConnectorEventEntity.DISCONNECTED) diagnostics += Diagnostic(Level.CRITICAL, "${c.label} was disconnected by Android", "Toggle the permission off and on, or reopen Marksy")
            ConnectorHealth(c.id, c.label, level, status, up, c.capturedToday)
        }

        val freshnessMs = i.lastCaptureAt?.let { i.nowMillis - it }
        val listenerOk = connectors.firstOrNull()?.level != Level.CRITICAL
        val stale = freshnessMs == null || freshnessMs > STALE_MS
        if (stale && listenerOk) diagnostics += Diagnostic(
            Level.WARNING,
            if (freshnessMs == null) "No notification has been captured yet" else "No notification captured for ${freshnessMs / HOUR}h",
            "Check notification access and that Marksy is not battery-restricted"
        )
        if (i.batteryOptimizationExempt == false) diagnostics += Diagnostic(Level.WARNING, "Battery optimisation may stop Marksy in the background", "Allow unrestricted battery use for Marksy")
        if (i.intelligenceBacklog > BACKLOG_WARN) diagnostics += Diagnostic(Level.WARNING, "${i.intelligenceBacklog} events are waiting for intelligence processing", "Processing resumes automatically in the background")
        if (i.failedTradingDeliveries > 0) diagnostics += Diagnostic(Level.WARNING, "${i.failedTradingDeliveries} trading events failed Marksy delivery", "Check the Marksy gateway connection")
        val captureFail = i.captureFailures7d + i.processingFailures7d
        if (captureFail > 0) diagnostics += Diagnostic(Level.WARNING, "$captureFail capture/processing failures in 7 days", null)
        if (i.aiCalls7d > 0 && i.aiFailures7d * 2 > i.aiCalls7d) diagnostics += Diagnostic(Level.WARNING, "Most AI calls fell back to deterministic logic", null)

        val level = when {
            diagnostics.any { it.level == Level.CRITICAL } -> Level.CRITICAL
            diagnostics.isNotEmpty() -> Level.WARNING
            else -> Level.OK
        }
        val avgProcessing = if (i.processingCount7d > 0) i.processingMsSum7d / i.processingCount7d else null
        val avgDelay = if (i.captured7d > 0) i.deliveryDelayMsSum7d / i.captured7d else null
        val metrics = listOf(
            Metric("Captured today", i.capturedToday.toString()),
            Metric("Captured / day (7d avg)", "%.1f".format(i.captured7d / 7.0)),
            Metric("Processing latency", avgProcessing?.let { "$it ms" } ?: "n/a"),
            Metric("Capture delay", avgDelay?.let { "$it ms" } ?: "n/a"),
            Metric("Duplicates (7d)", i.duplicates7d.toString()),
            Metric("Unclassified (7d)", i.unclassified7d.toString()),
            Metric("Important (7d)", i.important7d.toString()),
            Metric("Last capture", freshnessMs?.let { ago(it) } ?: "never"),
            Metric("Queue", "${i.intelligenceBacklog} to process · ${i.pendingTradingDeliveries} trading to deliver · ${i.pendingReminders} reminders"),
            Metric("AI", if (i.aiCalls7d == 0) "no calls (deterministic)" else "${i.aiCalls7d} calls · ${i.aiFailures7d} fallbacks · ${i.aiAvgLatencyMs?.toLong() ?: 0} ms"),
            Metric("Storage", "${i.databaseBytes / 1024} KB · ${i.eventRows} events"),
            Metric("Battery", listOfNotNull(i.batteryPercent?.let { "$it%" }, i.charging?.let { if (it) "charging" else "on battery" },
                i.batteryOptimizationExempt?.let { if (it) "unrestricted" else "optimised" }).joinToString(" · ").ifBlank { "n/a" })
        )
        val summary = when (level) {
            Level.OK -> "Marksy is healthy"
            Level.WARNING -> "Marksy needs attention (${diagnostics.size})"
            Level.CRITICAL -> "Marksy is not capturing reliably"
        }
        return Report(level, summary, stale, connectors, metrics, diagnostics)
    }

    private fun ago(ms: Long) = when {
        ms < MINUTE -> "just now"
        ms < HOUR -> "${ms / MINUTE} min ago"
        ms < DAY -> "${ms / HOUR} h ago"
        else -> "${ms / DAY} d ago"
    }

    private const val MINUTE = 60_000L
    private const val HOUR = 60 * MINUTE
    private const val DAY = 24 * HOUR
    const val STALE_MS = 12 * HOUR
    private const val BACKLOG_WARN = 50
}
