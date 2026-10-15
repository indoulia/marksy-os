package com.marksy.os.ui

import com.marksy.os.MarksyFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import com.marksy.os.capture.CaptureMessages
import com.marksy.os.data.MarksyContainer
import com.marksy.os.intelligence.MarksyHealth
import com.marksy.os.notification.NotificationListenerStatus

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
    MarksyList(Modifier.background(MarksyTheme.Background).padding(bottom = padding.calculateBottomPadding())) {
        if (r == null) {
            item { MarksyLoader("Checking Marksy…") }
            return@MarksyList
        }
        item { Text(r.summary, color = color(r.level), style = MarksyType.Lead, fontWeight = FontWeight.Bold) }
        if (r.diagnostics.isNotEmpty()) {
            items(r.diagnostics) { d ->
                MarksyCard(border = color(d.level)) {
                    MarksyCardHeader(d.title)
                    d.action?.let { Text(it, color = MarksyTheme.TextMuted, style = MarksyType.Meta) }
                }
            }
        }
        item { SectionLabel("Connectors") }
        item { ConnectorSetupCard() }
        item {
            MarksyGroupCard {
                r.connectors.forEachIndexed { i, c ->
                    if (i > 0) MarksyDivider()
                    Column(Modifier.padding(vertical = MarksySpace.Gap)) {
                        Text("${c.label} · ${c.status}", color = color(c.level), style = MarksyType.Body)
                        Text(
                            "Uptime 24h: ${c.uptimePercent?.let { MarksyFormat.percent(it, 0, signed = false) } ?: "no lifecycle data yet"} · captured today: ${c.capturedToday}",
                            color = MarksyTheme.TextMuted, style = MarksyType.Meta
                        )
                    }
                }
            }
        }
        item { SectionLabel("Capture") }
        item { CaptureHealthCard() }
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
        item {
            MarksyGroupCard {
                r.metrics.forEachIndexed { i, m ->
                    if (i > 0) MarksyDivider()
                    Row(Modifier.fillMaxWidth().padding(vertical = MarksySpace.Gap)) {
                        Text(m.label, color = MarksyTheme.TextSecondary, style = MarksyType.Small, modifier = Modifier.weight(1f))
                        Text(m.value, color = MarksyTheme.TextPrimary, style = MarksyType.Small)
                    }
                }
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
    MarksyGroupCard {
        MarksyCardHeader("Upstox feed", trailing = { Text(freshness.name.lowercase().replace('_', ' ').replaceFirstChar { it.titlecase() }, color = tone, style = MarksyType.Body) })
        Text(
            "Last tick ${ago(lastTick)} · ${stats.subscribed} instruments · ${stats.ticks} updates · connects ${stats.connects}, drops ${stats.drops}" +
                (if (stats.undecodable + stats.outOfOrder > 0) " · ${stats.undecodable} undecodable, ${stats.outOfOrder} out of order" else "") +
                (stats.lastError?.let { " · last error: $it" } ?: ""),
            color = MarksyTheme.TextMuted, style = MarksyType.Meta
        )
    }
}

@Composable
private fun CaptureHealthCard() {
    val context = LocalContext.current.applicationContext
    val dao = remember { MarksyContainer.database(context).captureDao() }
    val counts by dao.observeCandidateCounts().collectAsStateWithLifecycle(emptyList())
    val open by dao.observeOpenWorkflows().collectAsStateWithLifecycle(emptyList())
    val lastFailure by produceState<String?>(null, counts, open) { value = dao.lastFailureCode() }
    CaptureHealthRows(NotificationListenerStatus.isEnabled(context), counts.associate { it.state to it.n }, open.size, lastFailure)
}

/** Capture's state in plain words: permissions asked, tips by state, teasers waiting, and the last failure code explained. */
@Composable
internal fun CaptureHealthRows(access: Boolean, byState: Map<String, Int>, teasers: Int, lastFailure: String?) {
    val tips = listOf("to review" to (byState["EXTRACTED"] ?: 0) + (byState["REVIEW_REQUIRED"] ?: 0), "accepted" to (byState["ACCEPTED"] ?: 0), "rejected" to (byState["REJECTED"] ?: 0), "expired" to (byState["EXPIRED"] ?: 0))
        .filter { it.second > 0 }.joinToString(" · ") { "${it.second} ${it.first}" }
    val rows = listOf(
        Triple("Notification access", if (access) "On" else "Off, tips can't be caught", if (access) MarksyTheme.Positive else MarksyTheme.Negative),
        Triple("Screen capture", "Asked every time", MarksyTheme.TextPrimary),
        Triple("Photos", "System picker, no permission", MarksyTheme.TextPrimary),
        Triple("Tips", tips.ifEmpty { "None yet" }, MarksyTheme.TextPrimary),
        Triple("Teasers waiting", "$teasers", MarksyTheme.TextPrimary),
        Triple("Last failure", lastFailure?.let { CaptureMessages.failure(it) } ?: "None", if (lastFailure != null) MarksyTheme.Warning else MarksyTheme.TextPrimary)
    )
    MarksyGroupCard {
        rows.forEachIndexed { i, (label, value, tone) ->
            if (i > 0) MarksyDivider()
            Row(Modifier.fillMaxWidth().padding(vertical = MarksySpace.Gap), horizontalArrangement = Arrangement.spacedBy(MarksySpace.Gap)) {
                Text(label, color = MarksyTheme.TextSecondary, style = MarksyType.Small)
                Text(value, color = tone, style = MarksyType.Small, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
            }
        }
    }
}
