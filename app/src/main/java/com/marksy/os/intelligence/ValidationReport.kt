package com.marksy.os.intelligence

import com.marksy.os.data.Metric
import com.marksy.os.data.MetricsRecorder
import com.marksy.os.data.local.ConnectorEventEntity
import com.marksy.os.data.local.MetricCounterEntity
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * EPIC-023 "Marksy Intelligence Validation Report". Built only from counters and gauges collected
 * automatically at runtime; days without data are reported as gaps, never filled in.
 */
object ValidationReport {
    const val GAUGE_DB_BYTES = "gauge_db_bytes"
    const val GAUGE_EVENT_ROWS = "gauge_event_rows"
    const val GAUGE_BATTERY_PCT = "gauge_battery_pct"
    const val GAUGE_BATTERY_EXEMPT = "gauge_battery_exempt"
    const val GAUGE_UPTIME_PCT_X100 = "gauge_listener_uptime_pct_x100"
    const val GAUGE_HOURS_SINCE_CAPTURE = "gauge_hours_since_capture"

    data class DayRow(
        val day: LocalDate, val captured: Long, val classified: Long, val duplicates: Long, val crossSourceDuplicates: Long,
        val important: Long, val interactions: Long, val corrections: Long, val ruleExecutions: Long, val learningSignals: Long,
        val aiCalls: Long, val aiFailures: Long, val failures: Long, val avgProcessingMs: Long?, val uptimePct: Double?,
        val dbKb: Long?, val batteryPct: Long?, val hoursSinceCapture: Long?
    ) {
        val hasData: Boolean get() = captured > 0 || uptimePct != null || dbKb != null
    }

    data class SourceRow(val source: String, val captured: Long, val duplicates: Long, val corrections: Long, val failures: Long)

    data class Report(
        val start: LocalDate,
        val end: LocalDate,
        val daysElapsed: Int,
        val daysWithData: Int,
        val gaps: List<LocalDate>,
        val days: List<DayRow>,
        val sources: List<SourceRow>,
        val totals: DayRow,
        val classificationRate: Double?,
        /** Lower-bound estimate: classified events the user did not correct. Labelled as such. */
        val estimatedAccuracy: Double?,
        val failureLog: List<ConnectorEventEntity>
    )

    fun build(start: LocalDate, end: LocalDate, counters: List<MetricCounterEntity>, failures: List<ConnectorEventEntity>): Report {
        val all = counters.filter { it.scope == MetricsRecorder.SCOPE_ALL }
        fun v(day: String, metric: String) = all.filter { it.day == day && it.metric == metric }.sumOf { it.value }
        fun g(day: String, metric: String) = all.firstOrNull { it.day == day && it.metric == metric }?.value

        val days = generateSequence(start) { it.plusDays(1) }.takeWhile { !it.isAfter(end) }.map { d ->
            val k = d.toString()
            val pc = v(k, Metric.PROCESSING_COUNT)
            DayRow(
                d, v(k, Metric.CAPTURED), v(k, Metric.CLASSIFIED), v(k, Metric.DUPLICATE), v(k, Metric.CROSS_SOURCE_DUPLICATE),
                v(k, Metric.IMPORTANT), v(k, Metric.USER_INTERACTION), v(k, Metric.CORRECTION), v(k, Metric.RULE_EXECUTION), v(k, Metric.LEARNING_SIGNAL),
                v(k, Metric.AI_CALL), v(k, Metric.AI_FAILURE), v(k, Metric.CAPTURE_FAILED) + v(k, Metric.PROCESSING_FAILED),
                if (pc > 0) v(k, Metric.PROCESSING_MS_SUM) / pc else null,
                g(k, GAUGE_UPTIME_PCT_X100)?.let { it / 100.0 }, g(k, GAUGE_DB_BYTES)?.let { it / 1024 },
                g(k, GAUGE_BATTERY_PCT), g(k, GAUGE_HOURS_SINCE_CAPTURE)
            )
        }.toList()

        val sources = counters.filter { it.scope.startsWith("source:") }.groupBy { it.scope.removePrefix("source:") }.map { (src, rows) ->
            fun s(m: String) = rows.filter { it.metric == m }.sumOf { it.value }
            SourceRow(src, s(Metric.CAPTURED), s(Metric.DUPLICATE), s(Metric.CORRECTION), s(Metric.CAPTURE_FAILED) + s(Metric.PROCESSING_FAILED))
        }.sortedWith(compareByDescending<SourceRow> { it.captured }.thenBy { it.source })

        val procCount = all.filter { it.metric == Metric.PROCESSING_COUNT }.sumOf { it.value }
        val uptimes = days.mapNotNull { it.uptimePct }
        val totals = DayRow(
            end, days.sumOf { it.captured }, days.sumOf { it.classified }, days.sumOf { it.duplicates }, days.sumOf { it.crossSourceDuplicates },
            days.sumOf { it.important }, days.sumOf { it.interactions }, days.sumOf { it.corrections }, days.sumOf { it.ruleExecutions },
            days.sumOf { it.learningSignals }, days.sumOf { it.aiCalls }, days.sumOf { it.aiFailures }, days.sumOf { it.failures },
            if (procCount > 0) all.filter { it.metric == Metric.PROCESSING_MS_SUM }.sumOf { it.value } / procCount else null,
            uptimes.takeIf { it.isNotEmpty() }?.average(), days.lastOrNull { it.dbKb != null }?.dbKb,
            days.lastOrNull { it.batteryPct != null }?.batteryPct, days.mapNotNull { it.hoursSinceCapture }.maxOrNull()
        )
        val categoryCorrections = counters.filter { it.scope == "correction:category" && it.metric == Metric.CORRECTION }.sumOf { it.value }
        val elapsed = (ChronoUnit.DAYS.between(start, end) + 1).toInt()
        return Report(
            start, end, elapsed, days.count { it.hasData }, days.filterNot { it.hasData }.map { it.day }, days, sources, totals,
            classificationRate = if (totals.captured > 0) totals.classified.toDouble() / totals.captured else null,
            estimatedAccuracy = if (totals.classified > 0) 1.0 - categoryCorrections.toDouble() / totals.classified else null,
            failureLog = failures.sortedByDescending { it.at }.take(50)
        )
    }

    /** Plain-text export for the user to share themselves. Contains counts only. */
    fun toMarkdown(r: Report): String = buildString {
        fun pct(x: Double?) = x?.let { "%.1f%%".format(it * 100) } ?: "n/a"
        appendLine("# Marksy Intelligence Validation Report")
        appendLine()
        appendLine("Period: ${r.start} to ${r.end} (day ${r.daysElapsed} of 30, ${r.daysWithData} with data)")
        if (r.gaps.isNotEmpty()) appendLine("Days with no data: ${r.gaps.joinToString()}")
        appendLine()
        val t = r.totals
        appendLine("## Totals")
        appendLine("- Captured events: ${t.captured}")
        appendLine("- Classified: ${t.classified} (${pct(r.classificationRate)})")
        appendLine("- Estimated classification accuracy (classified minus user category corrections): ${pct(r.estimatedAccuracy)}")
        appendLine("- Duplicates suppressed at ingestion: ${t.duplicates}; cross-source duplicates folded: ${t.crossSourceDuplicates}")
        appendLine("- Important events detected: ${t.important}")
        appendLine("- User interactions: ${t.interactions}; corrections: ${t.corrections}")
        appendLine("- Rule executions: ${t.ruleExecutions}; learning signals: ${t.learningSignals}")
        appendLine("- AI calls: ${t.aiCalls}; AI fallbacks: ${t.aiFailures}")
        appendLine("- Capture/processing failures: ${t.failures}")
        appendLine("- Avg processing latency: ${t.avgProcessingMs?.let { "$it ms" } ?: "n/a"}")
        appendLine("- Avg listener uptime: ${t.uptimePct?.let { "%.1f%%".format(it) } ?: "n/a"}")
        appendLine("- Worst freshness gap: ${t.hoursSinceCapture?.let { "$it h" } ?: "n/a"}")
        appendLine("- Storage: ${t.dbKb?.let { "$it KB" } ?: "n/a"}; battery at last snapshot: ${t.batteryPct?.let { "$it%" } ?: "n/a"}")
        appendLine()
        appendLine("## By day")
        appendLine("| Day | Captured | Classified | Dup | Important | Interactions | Corrections | Failures | Uptime | Proc ms |")
        appendLine("|---|---|---|---|---|---|---|---|---|---|")
        r.days.forEach { d ->
            appendLine("| ${d.day} | ${d.captured} | ${d.classified} | ${d.duplicates} | ${d.important} | ${d.interactions} | ${d.corrections} | ${d.failures} | ${d.uptimePct?.let { "%.0f%%".format(it) } ?: "-"} | ${d.avgProcessingMs ?: "-"} |")
        }
        appendLine()
        appendLine("## By source")
        appendLine("| Source | Captured | Duplicates | Corrections | Failures |")
        appendLine("|---|---|---|---|---|")
        r.sources.forEach { appendLine("| ${it.source} | ${it.captured} | ${it.duplicates} | ${it.corrections} | ${it.failures} |") }
        if (r.failureLog.isNotEmpty()) {
            appendLine()
            appendLine("## Failures (latest ${r.failureLog.size})")
            r.failureLog.forEach { appendLine("- ${java.time.Instant.ofEpochMilli(it.at)} ${it.connectorId}${it.adapterId?.let { a -> "/$a" } ?: ""}: ${it.type} ${it.detail.orEmpty()}") }
        }
    }
}
