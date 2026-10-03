package com.marksy.os.ui

import com.marksy.os.MarksyFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.marksy.os.intelligence.MarksyHealth

/** EPIC-022: live runtime health from real counters and system state; refreshes every 30 s while open. */
@Composable
fun HealthScreen(padding: PaddingValues, market: com.marksy.os.market.MarketIntelligenceRepository? = null, load: suspend () -> MarksyHealth.Report) {
    val report by produceState<MarksyHealth.Report?>(null) {
        while (true) {
            value = runCatching { load() }.getOrNull() ?: value
            kotlinx.coroutines.delay(30_000)
        }
    }
    val r = report
    LazyColumn(
        modifier = Modifier.fillMaxSize().background(MarksyTheme.Background).padding(horizontal = MarksySpace.Gutter),
        contentPadding = PaddingValues(bottom = padding.calculateBottomPadding() + 20.dp),
        verticalArrangement = Arrangement.spacedBy(MarksySpace.ListGap)
    ) {
        if (r == null) {
            item { MarksyLoader("Checking Marksy…") }
            return@LazyColumn
        }
        item { Text(r.summary, color = color(r.level), style = MarksyType.Lead, fontWeight = FontWeight.Bold) }
        if (r.diagnostics.isNotEmpty()) {
            items(r.diagnostics) { d ->
                Column(Modifier.fillMaxWidth().marksyCard(color(d.level)).padding(MarksySpace.CardPadding)) {
                    Text(d.title, color = MarksyTheme.TextPrimary, style = MarksyType.Body)
                    d.action?.let { Text(it, color = MarksyTheme.TextMuted, style = MarksyType.Meta) }
                }
            }
        }
        item { SectionLabel("Connectors") }
        item { ConnectorSetupCard() }
        items(r.connectors) { c ->
            Column(Modifier.fillMaxWidth().marksyCard().padding(MarksySpace.CardPadding)) {
                Text("${c.label} · ${c.status}", color = color(c.level), style = MarksyType.Body)
                Text(
                    "Uptime 24h: ${c.uptimePercent?.let { MarksyFormat.percent(it, 0, signed = false) } ?: "no lifecycle data yet"} · captured today: ${c.capturedToday}",
                    color = MarksyTheme.TextMuted, style = MarksyType.Meta
                )
            }
        }
        // Data sources first, then prediction quality, so a data outage is never read as a bad call.
        item { SectionLabel("Markets") }
        item(key = "feed") { FeedStatsCard() }
        item(key = "silent") { SilentInstrumentsLine() }
        item(key = "rest") { UpstoxRestCard() }
        market?.let { m ->
            item(key = "marksy-feed") { MarksyFeedCard(m) }
            item(key = "validation") { PredictionValidationCard(m) }
        }
        item { SectionLabel("Runtime") }
        items(r.metrics) { m ->
            Row(Modifier.fillMaxWidth()) {
                Text(m.label, color = MarksyTheme.TextSecondary, style = MarksyType.Small, modifier = Modifier.weight(1f))
                Text(m.value, color = MarksyTheme.TextPrimary, style = MarksyType.Small)
            }
        }
    }
}

private fun color(level: MarksyHealth.Level): Color = when (level) {
    MarksyHealth.Level.OK -> MarksyTheme.Positive
    MarksyHealth.Level.WARNING -> MarksyTheme.Warning
    MarksyHealth.Level.CRITICAL -> MarksyTheme.Negative
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
    Column(Modifier.fillMaxWidth().marksyCard().padding(MarksySpace.CardPadding)) {
        Text("Upstox feed · ${freshness.name.lowercase().replace('_', ' ').replaceFirstChar { it.titlecase() }}", color = tone, style = MarksyType.Body)
        Text(
            "Last tick ${ago(lastTick)} · ${stats.subscribed} instruments · ${stats.ticks} updates · connects ${stats.connects}, drops ${stats.drops}" +
                (if (stats.undecodable + stats.outOfOrder > 0) " · ${stats.undecodable} undecodable, ${stats.outOfOrder} out of order" else "") +
                (stats.lastError?.let { " · last error: $it" } ?: ""),
            color = MarksyTheme.TextMuted, style = MarksyType.Meta
        )
    }
}
