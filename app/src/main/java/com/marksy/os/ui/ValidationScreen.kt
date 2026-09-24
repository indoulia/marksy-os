package com.marksy.os.ui

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
        modifier = Modifier.fillMaxSize().background(MarksyTheme.Background).padding(horizontal = 18.dp),
        contentPadding = PaddingValues(bottom = padding.calculateBottomPadding() + 20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (!started) {
            item {
                Text(
                    "Start a 30-day validation to measure how Marksy performs on your real notifications. " +
                        "Metrics are collected automatically on this device; nothing is estimated or back-filled.",
                    color = MarksyTheme.TextSecondary, fontSize = 13.sp
                )
                Button(onClick = { repo.start(); version++ }, colors = ButtonDefaults.buttonColors(containerColor = MarksyTheme.PrimaryEmerald)) {
                    Text("Start 30-day validation", color = Color.Black)
                }
            }
            return@LazyColumn
        }
        val r = report
        if (r == null) {
            item { Text("Building report...", color = MarksyTheme.TextMuted, fontSize = 13.sp) }
            return@LazyColumn
        }
        item {
            Text("Day ${r.daysElapsed} of 30 · ${r.daysWithData} day(s) with data", color = MarksyTheme.TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            if (r.gaps.isNotEmpty()) Text("No data on: ${r.gaps.joinToString()}", color = MarksyTheme.YellowImportant, fontSize = 11.sp)
        }
        item {
            val t = r.totals
            Column(Modifier.fillMaxWidth().background(MarksyTheme.Surface, RoundedCornerShape(12.dp)).padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                fun line(label: String, value: String) = label to value
                listOf(
                    line("Captured", t.captured.toString()),
                    line("Classified", "${t.classified} (${r.classificationRate?.let { "%.1f%%".format(it * 100) } ?: "n/a"})"),
                    line("Est. accuracy (from your corrections)", r.estimatedAccuracy?.let { "%.1f%%".format(it * 100) } ?: "n/a"),
                    line("Duplicates / cross-source", "${t.duplicates} / ${t.crossSourceDuplicates}"),
                    line("Important detected", t.important.toString()),
                    line("Interactions / corrections", "${t.interactions} / ${t.corrections}"),
                    line("Rule runs / learning signals", "${t.ruleExecutions} / ${t.learningSignals}"),
                    line("AI calls / fallbacks", "${t.aiCalls} / ${t.aiFailures}"),
                    line("Failures", t.failures.toString()),
                    line("Avg processing", t.avgProcessingMs?.let { "$it ms" } ?: "n/a"),
                    line("Avg listener uptime", t.uptimePct?.let { "%.1f%%".format(it) } ?: "n/a"),
                    line("Storage / battery", "${t.dbKb?.let { "$it KB" } ?: "n/a"} / ${t.batteryPct?.let { "$it%" } ?: "n/a"}")
                ).forEach { (l, v) ->
                    Row(Modifier.fillMaxWidth()) {
                        Text(l, color = MarksyTheme.TextSecondary, fontSize = 12.sp, modifier = Modifier.weight(1f))
                        Text(v, color = MarksyTheme.TextPrimary, fontSize = 12.sp)
                    }
                }
            }
        }
        item {
            Button(onClick = {
                val text = ValidationReport.toMarkdown(r)
                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_SUBJECT, "Marksy Intelligence Validation Report").putExtra(Intent.EXTRA_TEXT, text)
                context.startActivity(Intent.createChooser(send, "Share validation report"))
            }, colors = ButtonDefaults.buttonColors(containerColor = MarksyTheme.PrimaryEmerald)) { Text("Share report (counts only)", color = Color.Black) }
        }
        item { Text("By day", color = MarksyTheme.PrimaryEmerald, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
        items(r.days.reversed()) { d ->
            Text(
                "${d.day}: ${d.captured} captured · ${d.important} important · ${d.duplicates} dup · ${d.failures} fail" +
                    (d.uptimePct?.let { " · up %.0f%%".format(it) } ?: "") + if (!d.hasData) " · no data" else "",
                color = if (d.hasData) MarksyTheme.TextSecondary else MarksyTheme.TextMuted, fontSize = 11.sp
            )
        }
        item { Text("By source", color = MarksyTheme.PrimaryEmerald, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
        items(r.sources.take(20)) { s ->
            Text("${s.source}: ${s.captured} captured · ${s.duplicates} dup · ${s.corrections} corrected · ${s.failures} fail", color = MarksyTheme.TextSecondary, fontSize = 11.sp)
        }
        if (r.failureLog.isNotEmpty()) {
            item { Text("Recent failures", color = MarksyTheme.PrimaryEmerald, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
            items(r.failureLog.take(10)) { f ->
                Text("${java.util.Date(f.at)} · ${f.connectorId} · ${f.type} ${f.detail.orEmpty()}", color = MarksyTheme.TextMuted, fontSize = 10.sp)
            }
        }
        item {
            TextButton(onClick = { scope.launch { repo.start(); version++ } }) { Text("Restart validation from today", color = MarksyTheme.TextMuted, fontSize = 11.sp) }
        }
    }
}
