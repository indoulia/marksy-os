package com.marksy.os.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.marksy.os.market.MarketDataState
import com.marksy.os.market.MarketIntelligenceRepository
import com.marksy.os.market.PerformanceBreakdownDto
import com.marksy.os.market.PerformanceSummaryDto
import com.marksy.os.upstox.UpstoxFeed
import com.marksy.os.upstox.UpstoxInstruments
import com.marksy.os.upstox.UpstoxRestStats
import java.util.Locale
import kotlinx.coroutines.launch

@Composable
private fun HealthCard(title: String, tone: Color, source: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().background(MarksyTheme.Surface, RoundedCornerShape(12.dp)).padding(10.dp)) {
        Text(title, color = tone, fontSize = 13.sp)
        content()
        Text(source, color = MarksyTheme.TextMuted.copy(alpha = .7f), fontSize = 9.sp, modifier = Modifier.padding(top = 3.dp))
    }
}

@Composable
private fun Line(text: String) = Text(text, color = MarksyTheme.TextMuted, fontSize = 11.sp)

/** Marksy's own Upstox ingestion (server side), as the backend reports it. */
@Composable
internal fun MarksyFeedCard(repo: MarketIntelligenceRepository) {
    val health by produceState<MarketDataState<com.marksy.os.market.LiveFeedHealthDto>>(MarketDataState.Loading, repo) { value = repo.liveFeedHealth() }
    when (val h = health) {
        is MarketDataState.Loaded, is MarketDataState.Stale -> {
            val v = (h as? MarketDataState.Loaded)?.value ?: (h as MarketDataState.Stale).value
            val ok = v.feedState == "STREAMING" && !v.fallbackActive
            HealthCard("Marksy market feed · ${v.feedState.lowercase().replace('_', ' ')}", if (ok) MarksyTheme.PrimaryEmerald else MarksyTheme.YellowImportant, "marksy-api /market/live/health") {
                Line("${v.cachedInstruments} instruments cached · fallback ${if (v.fallbackActive) "active" else "off"} · live feed ${if (v.liveFeedEnabled) "on" else "off"}")
            }
        }
        is MarketDataState.Error -> HealthCard("Marksy market feed · unreachable", MarksyTheme.RedUrgent, "marksy-api /market/live/health") { Line(h.message) }
        MarketDataState.Unavailable -> HealthCard("Marksy market feed · not signed in", MarksyTheme.TextSecondary, "marksy-api") { Line("Sign in to Marksy in More.") }
        else -> Unit
    }
}

/** Upstox REST calls this app made in this session, by endpoint family. */
@Composable
internal fun UpstoxRestCard() {
    val stats by UpstoxRestStats.stats.collectAsStateWithLifecycle()
    val failing = stats.values.sumOf { it.failures }
    val calls = stats.values.sumOf { it.calls }
    HealthCard(
        if (calls == 0) "Upstox REST · no calls yet" else "Upstox REST · $calls calls, $failing failed",
        when { calls == 0 -> MarksyTheme.TextSecondary; failing * 5 > calls -> MarksyTheme.RedUrgent; failing > 0 -> MarksyTheme.YellowImportant; else -> MarksyTheme.PrimaryEmerald },
        "this device, this session"
    ) {
        stats.entries.sortedByDescending { it.value.calls }.forEach { (family, s) ->
            Line("$family: ${s.calls} · ${s.failures} failed · ${s.averageMs} ms avg${s.lastError?.let { " · last error: ${it.take(60)}" }.orEmpty()}")
        }
    }
}

/** Instruments a screen subscribed to that have never ticked: a missing or wrongly keyed instrument. */
@Composable
internal fun SilentInstrumentsLine() {
    val quotes by UpstoxFeed.quotes.collectAsStateWithLifecycle()
    val silent = remember(quotes) { UpstoxFeed.subscribedKeys().filter { it !in quotes } }
    if (silent.isEmpty()) return
    val names = silent.take(5).map { k -> UpstoxInstruments.symbolForKey(k) ?: k.substringAfter('|') }
    Text("${silent.size} subscribed instrument${if (silent.size == 1) " has" else "s have"} not ticked: ${names.joinToString(", ")}${if (silent.size > 5) "…" else ""}",
        color = MarksyTheme.YellowImportant, fontSize = 11.sp)
}

/** Did Marksy's calls work? Outcomes are counted only after each call's horizon closes. */
@Composable
internal fun PredictionValidationCard(repo: MarketIntelligenceRepository) {
    val week by produceState<PerformanceSummaryDto?>(null, repo) { value = (repo.performanceSummary("7d") as? MarketDataState.Loaded)?.value }
    val month by produceState<PerformanceSummaryDto?>(null, repo) { value = (repo.performanceSummary("30d") as? MarketDataState.Loaded)?.value }
    val horizons by produceState<PerformanceBreakdownDto?>(null, repo) { value = (repo.performanceBreakdown("horizon") as? MarketDataState.Loaded)?.value }
    fun pct(f: Double?) = f?.let { "${(it * 100).toInt()}%" } ?: "—"
    fun ret(f: Double?) = f?.let { String.format(Locale.US, "%+.1f%%", it * 100) } ?: "—"
    val m = month
    HealthCard(
        m?.let { "Prediction validation · 30d target ${pct(it.targetHitRate)} of ${it.closedCount} closed" } ?: "Prediction validation · loading",
        when { m == null -> MarksyTheme.TextSecondary; (m.targetHitRate ?: 0.0) >= (m.stopLossRate ?: 1.0) -> MarksyTheme.PrimaryEmerald; else -> MarksyTheme.YellowImportant },
        "marksy-api /performance · closed calls only, scored after their horizon"
    ) {
        listOfNotNull(week?.let { "7 days" to it }, m?.let { "30 days" to it }).forEach { (label, s) ->
            Line("$label: ${s.closedCount} closed · target ${pct(s.targetHitRate)} · stop ${pct(s.stopLossRate)} · expired ${pct(s.horizonExpiryRate)} · avg ${ret(s.avgRealizedReturn)}${if (s.smallSample) " · small sample" else ""}")
        }
        horizons?.items?.sortedBy { it.key.filter(Char::isDigit).toIntOrNull() ?: 99 }?.takeIf { it.isNotEmpty() }?.let { items ->
            Text("By horizon", color = MarksyTheme.TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 4.dp))
            items.forEach { i -> Line("${i.key.filter(Char::isDigit).ifEmpty { i.key }}-day: ${i.closedCount} closed · target ${pct(i.targetHitRate)} · avg ${ret(i.avgRealizedReturn)}${if (i.smallSample) " · small sample" else ""}") }
        }
    }
}

/** Tune the Marksy rating on this device against how past calls actually did; adopted only if it wins on held-out calls. */
@Composable
internal fun RatingCalibrationCard(repo: MarketIntelligenceRepository) {
    val context = androidx.compose.ui.platform.LocalContext.current.applicationContext
    val latest by RatingCalibrator.latest.collectAsStateWithLifecycle()
    val running by RatingCalibrator.progress.collectAsStateWithLifecycle()
    val error by RatingCalibrator.error.collectAsStateWithLifecycle()
    val s = latest ?: remember(latest) { RatingCalibrator.read(context) }
    HealthCard(
        when {
            s == null -> "Marksy rating · V1 weights (not calibrated)"
            s.adopted -> "Marksy rating · calibrated on ${s.samples} calls"
            else -> "Marksy rating · V1 kept (calibration didn't beat it)"
        },
        if (s?.adopted == true) MarksyTheme.PrimaryEmerald else MarksyTheme.TextSecondary,
        "point-in-time Upstox history + marksy-api closed calls · short-term weights only"
    ) {
        s?.let {
            Line(String.format(Locale.US, "Held-out %d calls: rank correlation V1 %.2f → tuned %.2f · trend %.2f, seasonality %.2f", it.testSamples, it.baseTest, it.test, it.trend, it.seasonality))
            Line("Run ${java.text.SimpleDateFormat("d MMM, HH:mm", Locale.getDefault()).format(java.util.Date(it.ranAt))}")
        }
        error?.let { Text(it, color = MarksyTheme.YellowImportant, fontSize = 11.sp) }
        Row(Modifier.padding(top = 6.dp)) {
            running?.let { Text(it, color = MarksyTheme.TextSecondary, fontSize = 11.sp) } ?: Pill("Calibrate now") { RatingCalibrator.start(context, repo) }
        }
    }
}
