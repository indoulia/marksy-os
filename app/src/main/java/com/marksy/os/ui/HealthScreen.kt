package com.marksy.os.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.marksy.os.intelligence.MarksyHealth

/** EPIC-022: live runtime health from real counters and system state; refreshes every 30 s while open. */
@Composable
fun HealthScreen(padding: PaddingValues, load: suspend () -> MarksyHealth.Report) {
    val report by produceState<MarksyHealth.Report?>(null) {
        while (true) {
            value = runCatching { load() }.getOrNull() ?: value
            kotlinx.coroutines.delay(30_000)
        }
    }
    val r = report
    LazyColumn(
        modifier = Modifier.fillMaxSize().background(MarksyTheme.Background).padding(horizontal = 18.dp),
        contentPadding = PaddingValues(bottom = padding.calculateBottomPadding() + 20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (r == null) {
            item { Text("Checking Marksy...", color = MarksyTheme.TextMuted, fontSize = 13.sp) }
            return@LazyColumn
        }
        item { Text(r.summary, color = color(r.level), fontSize = 16.sp, fontWeight = FontWeight.Bold) }
        if (r.diagnostics.isNotEmpty()) {
            items(r.diagnostics) { d ->
                Column(Modifier.fillMaxWidth().border(1.dp, color(d.level), RoundedCornerShape(12.dp)).padding(10.dp)) {
                    Text(d.title, color = MarksyTheme.TextPrimary, fontSize = 13.sp)
                    d.action?.let { Text(it, color = MarksyTheme.TextMuted, fontSize = 11.sp) }
                }
            }
        }
        item { Text("Connectors", color = MarksyTheme.PrimaryEmerald, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp)) }
        item { ConnectorSetupCard() }
        items(r.connectors) { c ->
            Column(Modifier.fillMaxWidth().background(MarksyTheme.Surface, RoundedCornerShape(12.dp)).padding(10.dp)) {
                Text("${c.label} · ${c.status}", color = color(c.level), fontSize = 13.sp)
                Text(
                    "Uptime 24h: ${c.uptimePercent?.let { "%.0f%%".format(it) } ?: "no lifecycle data yet"} · captured today: ${c.capturedToday}",
                    color = MarksyTheme.TextMuted, fontSize = 11.sp
                )
            }
        }
        item { Text("Market stream", color = MarksyTheme.PrimaryEmerald, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp)) }
        item { FeedStatsCard() }
        item { Text("Runtime", color = MarksyTheme.PrimaryEmerald, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp)) }
        items(r.metrics) { m ->
            Row(Modifier.fillMaxWidth()) {
                Text(m.label, color = MarksyTheme.TextSecondary, fontSize = 12.sp, modifier = Modifier.weight(1f))
                Text(m.value, color = MarksyTheme.TextPrimary, fontSize = 12.sp)
            }
        }
    }
}

private fun color(level: MarksyHealth.Level): Color = when (level) {
    MarksyHealth.Level.OK -> MarksyTheme.PrimaryEmerald
    MarksyHealth.Level.WARNING -> MarksyTheme.YellowImportant
    MarksyHealth.Level.CRITICAL -> MarksyTheme.RedUrgent
}

/** Where a market-data problem sits: socket (connects/drops), stream (tick age), or decoding. */
@Composable
private fun FeedStatsCard() {
    val stats by com.marksy.os.upstox.UpstoxFeed.stats.collectAsStateWithLifecycle()
    val lastTick by com.marksy.os.upstox.UpstoxFeed.lastTickAt.collectAsStateWithLifecycle()
    val freshness = rememberFeedFreshness()
    val now = System.currentTimeMillis()
    val tone = when (freshness) {
        com.marksy.os.upstox.FeedFreshness.LIVE, com.marksy.os.upstox.FeedFreshness.CLOSED -> MarksyTheme.PrimaryEmerald
        com.marksy.os.upstox.FeedFreshness.OFF -> MarksyTheme.TextSecondary
        else -> MarksyTheme.YellowImportant
    }
    fun ago(t: Long) = if (t <= 0) "never" else ((now - t) / 1000).let { if (it < 90) "${it}s ago" else "${it / 60}m ago" }
    Column(Modifier.fillMaxWidth().background(MarksyTheme.Surface, RoundedCornerShape(12.dp)).padding(10.dp)) {
        Text("Upstox feed · ${freshness.name.lowercase().replace('_', ' ').replaceFirstChar { it.titlecase() }}", color = tone, fontSize = 13.sp)
        Text(
            "Last tick ${ago(lastTick)} · ${stats.subscribed} instruments · ${stats.ticks} updates · connects ${stats.connects}, drops ${stats.drops}" +
                (if (stats.undecodable + stats.outOfOrder > 0) " · ${stats.undecodable} undecodable, ${stats.outOfOrder} out of order" else "") +
                (stats.lastError?.let { " · last error: $it" } ?: ""),
            color = MarksyTheme.TextMuted, fontSize = 11.sp
        )
    }
}
