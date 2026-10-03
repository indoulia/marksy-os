package com.marksy.os.ui

import java.time.ZoneId
import java.time.Instant
import com.marksy.os.MarksyFormat
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.marksy.os.data.ValidationRepository
import com.marksy.os.intelligence.ValidationReport
import kotlinx.coroutines.launch

/** EPIC-023: the running 30-day validation, from automatically collected counters only. */
@Composable
fun ValidationScreen(repo: ValidationRepository, padding: PaddingValues) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var version by remember { mutableIntStateOf(0) }
    val report by produceState<ValidationReport.Report?>(null, version) {
        runCatching { repo.snapshot() }
        value = runCatching { repo.report() }.getOrNull()
    }
    val started = repo.startDay() != null

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(MarksyTheme.Background).padding(horizontal = MarksySpace.Gutter),
        contentPadding = PaddingValues(bottom = padding.calculateBottomPadding() + MarksySpace.Gutter),
        verticalArrangement = Arrangement.spacedBy(MarksySpace.ListGap)
    ) {
        if (!started) {
            item {
                Text(
                    "Start a 30-day validation to measure how Marksy performs on your real notifications. " +
                        "Metrics are collected automatically on this device; nothing is estimated or back-filled.",
                    color = MarksyTheme.TextSecondary, style = MarksyType.Body
                )
                MarksyButton("Start 30-day validation", onClick = { repo.start(); version++ })
            }
            return@LazyColumn
        }
        val r = report
        if (r == null) {
            item { MarksyLoader("Building report…") }
            return@LazyColumn
        }
        item {
            Text("Day ${r.daysElapsed} of 30 · ${r.daysWithData} day(s) with data", color = MarksyTheme.TextPrimary, style = MarksyType.Lead, fontWeight = FontWeight.Bold)
            if (r.gaps.isNotEmpty()) Text("No data on: ${r.gaps.joinToString()}", color = MarksyTheme.YellowImportant, style = MarksyType.Meta)
        }
        item {
            val t = r.totals
            MarksyCard(spacing = MarksySpace.Hair) {
                fun line(label: String, value: String) = label to value
                listOf(
                    line("Captured", MarksyFormat.number(t.captured.toDouble(), 0)),
                    line("Classified", "${t.classified} (${r.classificationRate?.let { MarksyFormat.percent(it * 100, 1, signed = false) } ?: "n/a"})"),
                    line("Est. accuracy (from your corrections)", r.estimatedAccuracy?.let { MarksyFormat.percent(it * 100, 1, signed = false) } ?: "n/a"),
                    line("Duplicates / cross-source", "${t.duplicates} / ${t.crossSourceDuplicates}"),
                    line("Important detected", MarksyFormat.number(t.important.toDouble(), 0)),
                    line("Interactions / corrections", "${t.interactions} / ${t.corrections}"),
                    line("Rule runs / learning signals", "${t.ruleExecutions} / ${t.learningSignals}"),
                    line("AI calls / fallbacks", "${t.aiCalls} / ${t.aiFailures}"),
                    line("Failures", MarksyFormat.number(t.failures.toDouble(), 0)),
                    line("Avg processing", t.avgProcessingMs?.let { "$it ms" } ?: "n/a"),
                    line("Avg listener uptime", t.uptimePct?.let { MarksyFormat.percent(it, 1, signed = false) } ?: "n/a"),
                    line("Storage / battery", "${t.dbKb?.let { "$it KB" } ?: "n/a"} / ${t.batteryPct?.let { "$it%" } ?: "n/a"}")
                ).forEach { (l, v) ->
                    Row(Modifier.fillMaxWidth()) {
                        Text(l, color = MarksyTheme.TextSecondary, style = MarksyType.Small, modifier = Modifier.weight(1f))
                        Text(v, color = MarksyTheme.TextPrimary, style = MarksyType.Small)
                    }
                }
            }
        }
        item {
            MarksyButton("Share report (counts only)", onClick = {
                val text = ValidationReport.toMarkdown(r)
                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_SUBJECT, "Marksy Intelligence Validation Report").putExtra(Intent.EXTRA_TEXT, text)
                context.startActivity(Intent.createChooser(send, "Share validation report"))
            })
        }
        item { SectionLabel("By day") }
        items(r.days.reversed()) { d ->
            Text(
                "${d.day}: ${d.captured} captured · ${d.important} important · ${d.duplicates} dup · ${d.failures} fail" +
                    (d.uptimePct?.let { " · up ${MarksyFormat.percent(it, 0, signed = false)}" } ?: "") + if (!d.hasData) " · no data" else "",
                color = if (d.hasData) MarksyTheme.TextSecondary else MarksyTheme.TextMuted, style = MarksyType.Meta
            )
        }
        item { SectionLabel("By source") }
        items(r.sources.take(20)) { s ->
            Text("${s.source}: ${s.captured} captured · ${s.duplicates} dup · ${s.corrections} corrected · ${s.failures} fail", color = MarksyTheme.TextSecondary, style = MarksyType.Meta)
        }
        if (r.failureLog.isNotEmpty()) {
            item { SectionLabel("Recent failures") }
            items(r.failureLog.take(10)) { f ->
                Text("${MarksyFormat.dayTime(Instant.ofEpochMilli(f.at).atZone(ZoneId.systemDefault()))} · ${f.connectorId} · ${f.type} ${f.detail.orEmpty()}", color = MarksyTheme.TextMuted, style = MarksyType.Caption)
            }
        }
        item {
            MarksyButton("Restart validation from today", onClick = { scope.launch { repo.start(); version++ } }, style = MarksyButtonStyle.Text, color = MarksyTheme.TextMuted)
        }
    }
}
