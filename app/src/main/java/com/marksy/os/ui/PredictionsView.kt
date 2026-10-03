package com.marksy.os.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.marksy.os.EmptyState
import com.marksy.os.MarksyFormat
import com.marksy.os.market.ActivePredictionDto
import com.marksy.os.market.ClosedPredictionDto
import com.marksy.os.market.LedgerCalls
import com.marksy.os.market.MarketDataState
import com.marksy.os.market.MarketIntelligenceRepository
import com.marksy.os.market.PerformanceSummaryDto
import com.marksy.os.market.PicksBasis

/** A cursor-paged list that grows as its last row scrolls into view. */
@Stable
internal class Paged<T>(private val fetch: suspend (String?) -> MarketDataState<Pair<List<T>, String?>>) {
    var items by mutableStateOf<List<T>>(emptyList())
    var state by mutableStateOf<MarketDataState<Unit>>(MarketDataState.Loading)
    var hasMore by mutableStateOf(true)
    /** A later page failed, so the list stopped short of its end. */
    var failed by mutableStateOf(false)
    private var cursor: String? = null
    private var busy = false

    suspend fun more() {
        if (busy || !hasMore) return
        busy = true
        when (val r = fetch(cursor)) {
            is MarketDataState.Loaded -> { items = items + r.value.first; cursor = r.value.second; hasMore = cursor != null; state = MarketDataState.Loaded(Unit) }
            is MarketDataState.Empty -> { hasMore = false; state = if (items.isEmpty()) MarketDataState.Empty else MarketDataState.Loaded(Unit) }
            is MarketDataState.Error -> { hasMore = false; failed = true; if (items.isEmpty()) state = r }
            is MarketDataState.Unavailable -> { hasMore = false; state = r }
            else -> Unit
        }
        busy = false
    }
}

/** Results: Marksy's track record, closed calls and calls that ended early, each with its outcome. Live calls are in Setups. */
@Composable
internal fun PredictionsView(repository: MarketIntelligenceRepository, bottomPadding: Dp, onOpenStock: (String) -> Unit) {
    var showEnded by rememberSaveable { mutableStateOf(false) }
    val open = remember { Paged { c -> repository.activePredictions(c).map { it.items to it.nextCursor } } }
    val closed = remember { Paged { c -> repository.closedPredictions(c).map { it.items to it.nextCursor } } }
    val summary by produceState<PerformanceSummaryDto?>(null) { value = (repository.performanceSummary() as? MarketDataState.Loaded)?.value }
    LaunchedEffect(Unit) { closed.more(); open.more() }
    // Withdrawn or unpriced calls still sit in the active feed; they never count or rank as open.
    val ended = remember(open.items) { open.items.filterNot(LedgerCalls::isLive) }
    val quotes = rememberUpstoxQuotes(remember(ended) { ended.map { it.symbol }.distinct() })
    val paged = if (showEnded) open else closed
    val endedHint = "Calls withdrawn, invalidated or left without market data before closing appear here."

    MarksyList(bottom = bottomPadding) {
        item(key = "record") { TrackRecordStrip(summary) }
        item(key = "segments") {
            Row(horizontalArrangement = Arrangement.spacedBy(MarksySpace.Inner)) {
                Pill("Closed", selected = !showEnded) { showEnded = false }
                Pill("Ended" + (if (ended.isEmpty()) "" else " (${ended.size}${if (open.hasMore) "+" else ""})"), selected = showEnded) { showEnded = true }
            }
        }
        when (val s = paged.state) {
            is MarketDataState.Loading -> item { MarksyLoader("Loading Marksy calls…") }
            is MarketDataState.Unavailable -> item { EmptyState("Marksy is not connected", "Sign in to your Marksy account in More.") }
            is MarketDataState.Error -> item { EmptyState("Calls unavailable", s.message) }
            is MarketDataState.Empty -> item { EmptyState(if (showEnded) "No ended calls" else "No closed calls yet", if (showEnded) endedHint else "Calls appear here once their target, stop or horizon is reached.") }
            else -> {
                if (!showEnded) items(closed.items, key = { "c-${it.id}" }) { ClosedCallRow(it) { onOpenStock(it.symbol) } }
                else {
                    if (ended.isEmpty() && !open.hasMore) item { EmptyState("No ended calls", endedHint) }
                    items(ended, key = { "e-${it.predictionId}" }) { p -> OpenCallRow(p, quotes[p.symbol]?.lastPrice, note = LedgerCalls.endedLine(p)) { onOpenStock(p.symbol) } }
                }
                if (paged.hasMore) item(key = "more-$showEnded-${paged.items.size}") {
                    LaunchedEffect(Unit) { paged.more() }
                    MarksyLoader("Loading more…")
                }
            }
        }
    }
}

internal inline fun <T, R> MarketDataState<T>.map(f: (T) -> R): MarketDataState<R> = when (this) {
    is MarketDataState.Loaded -> MarketDataState.Loaded(f(value))
    is MarketDataState.Stale -> MarketDataState.Loaded(f(value))
    is MarketDataState.Error -> this
    MarketDataState.Empty -> MarketDataState.Empty
    MarketDataState.Loading -> MarketDataState.Loading
    MarketDataState.Unavailable -> MarketDataState.Unavailable
}

@Composable
private fun TrackRecordStrip(summary: PerformanceSummaryDto?) {
    val s = summary ?: return
    if (s.closedCount == 0) return
    MarksyRowCard {
        MarksyCardHeader("Track record") {
            Text("${s.range.replace("d", " days")} · ${s.closedCount} closed${if (s.smallSample) " · small sample" else ""}", color = MarksyTheme.TextMuted, style = MarksyType.Meta)
        }
        Row(Modifier.fillMaxWidth().padding(top = MarksySpace.Tight), horizontalArrangement = Arrangement.SpaceBetween) {
            Stat("Target", s.targetHitRate?.let(::share), MarksyTheme.PrimaryEmerald)
            Stat("Stopped", s.stopLossRate?.let(::share), MarksyTheme.Negative)
            Stat("Expired", s.horizonExpiryRate?.let(::share), MarksyTheme.TextSecondary)
            Stat("Avg return", s.avgRealizedReturn?.let(::returnPct), if ((s.avgRealizedReturn ?: 0.0) >= 0) MarksyTheme.PrimaryEmerald else MarksyTheme.Negative)
        }
    }
}

@Composable
private fun Stat(label: String, value: String?, tint: Color) {
    Column {
        Text(value ?: "—", color = if (value == null) MarksyTheme.TextMuted else tint, style = MarksyType.Subhead, fontWeight = FontWeight.Bold)
        Text(label, color = MarksyTheme.TextMuted, style = MarksyType.Caption)
    }
}

@Composable
internal fun OpenCallRow(p: ActivePredictionDto, livePrice: Double?, note: String? = null, onClick: () -> Unit) {
    val price = livePrice ?: p.price
    val buy = p.targetPrice >= p.entryPrice
    MarksyRowCard(
        Modifier.animateContentSize(), border = if (p.isActionableNow) MarksyTheme.PrimaryEmerald else MarksyTheme.BorderGlow, onClick = onClick
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(p.symbol, color = MarksyTheme.TextPrimary, style = MarksyType.Subhead, fontWeight = FontWeight.Bold)
            MarksyBadge(if (buy) "BUY" else "SELL", if (buy) MarksyTheme.Positive else MarksyTheme.Negative, MarksyTheme.SurfaceRaised, Modifier.padding(start = MarksySpace.Inner))
            Text(p.companyName.orEmpty(), color = MarksyTheme.TextMuted, style = MarksyType.Meta, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(start = MarksySpace.Inner))
            price?.let { Text(callRupees(it), color = MarksyTheme.TextPrimary, style = MarksyType.Body, fontWeight = FontWeight.SemiBold) }
        }
        CallRange(p.stopLoss, p.entryPrice, p.targetPrice, price, Modifier.fillMaxWidth().padding(vertical = MarksySpace.Inner).height(8.dp))
        Row {
            Text("SL ${callRupees(p.stopLoss)}", color = MarksyTheme.Negative, style = MarksyType.Caption)
            Text("Entry ${callRupees(p.entryPrice)}", color = MarksyTheme.TextMuted, style = MarksyType.Caption, modifier = Modifier.weight(1f).padding(horizontal = MarksySpace.Inner), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            Text("Target ${callRupees(p.targetPrice)}", color = MarksyTheme.PrimaryEmerald, style = MarksyType.Caption)
        }
        Text(
            listOfNotNull(
                p.remainingTradingDays?.let { "$it day${if (it == 1) "" else "s"} left" } ?: "${p.horizon}-day call",
                if (note == null) PicksBasis.day(p.scanSessionDate)?.let { "$it call" } else null,
                "conf ${MarksyFormat.percent(if (p.confidence <= 1) p.confidence * 100 else p.confidence, 0, signed = false)}",
                LedgerCalls.lifecycleWord(p)?.let(::words),
                p.lifecycleDetail
            ).joinToString(" · "),
            color = MarksyTheme.TextSecondary, style = MarksyType.Meta, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = MarksySpace.Tight)
        )
        // Own line: which session and when the pick was made must never be ellipsized away.
        note?.let { Text(it, color = MarksyTheme.TextMuted, style = MarksyType.Meta, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = MarksySpace.Border)) }
    }
}

/** Stop → target as one bar; the filled part runs from entry to the current price. */
@Composable
private fun CallRange(stop: Double, entry: Double, target: Double, price: Double?, modifier: Modifier) {
    val green = MarksyTheme.PrimaryEmerald; val red = MarksyTheme.Negative; val track = MarksyTheme.SurfaceRaised; val tick = MarksyTheme.TextSecondary
    Canvas(modifier) {
        val span = target - stop
        if (span == 0.0) return@Canvas
        fun x(v: Double) = (((v - stop) / span).coerceIn(0.0, 1.0) * size.width).toFloat()
        val h = size.height
        drawRoundRect(track, size = Size(size.width, h), cornerRadius = CornerRadius(h / 2))
        val e = x(entry)
        price?.let { now ->
            val p = x(now)
            drawRoundRect(if (p >= e) green else red, topLeft = Offset(minOf(e, p), 0f), size = Size(kotlin.math.abs(p - e).coerceAtLeast(2f), h), cornerRadius = CornerRadius(h / 2))
            drawCircle(MarksyTheme.TextPrimary, radius = h * 0.75f, center = Offset(p, h / 2))
        }
        drawRect(tick, topLeft = Offset(e - 1f, -2f), size = Size(2f, h + 4f))
    }
}

@Composable
private fun ClosedCallRow(c: ClosedPredictionDto, onClick: () -> Unit) {
    val o = c.outcome.orEmpty().uppercase()
    val tone = c.ledger?.let(LedgerCalls::tone)
    val tint = when {
        tone == LedgerCalls.Tone.POSITIVE -> MarksyTheme.PrimaryEmerald
        tone == LedgerCalls.Tone.NEGATIVE -> MarksyTheme.Negative
        tone != null -> MarksyTheme.TextSecondary
        "TARGET" in o || "SUCCESS" in o -> MarksyTheme.PrimaryEmerald
        "STOP" in o || "FAIL" in o -> MarksyTheme.Negative
        else -> MarksyTheme.TextSecondary
    }
    MarksyRowCard(onClick = onClick) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(c.symbol, color = MarksyTheme.TextPrimary, style = MarksyType.Subhead, fontWeight = FontWeight.Bold)
                Text(LedgerCalls.closedLabel(c) ?: c.outcome?.let(::words) ?: "Closed", color = tint, style = MarksyType.Meta, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = MarksySpace.Gap))
            }
            Text(
                listOfNotNull(c.predictedReturn?.let { "called ${returnPct(it)}" }, "${c.horizonDays}-day", c.asOf?.let { runCatching { MarksyFormat.day(java.time.LocalDate.parse(it.take(10))) }.getOrDefault(it.take(10)) }, c.excludedReason?.let { "not scored" })
                    .joinToString(" · "),
                color = MarksyTheme.TextMuted, style = MarksyType.Meta
            )
        }
        LedgerCalls.closedReturn(c)?.let { Text(returnPct(it), color = if (it >= 0) MarksyTheme.PrimaryEmerald else MarksyTheme.Negative, style = MarksyType.Subhead, fontWeight = FontWeight.Bold) }
      }
    }
}

private fun words(s: String) = s.lowercase().replace('_', ' ').replaceFirstChar { it.titlecase() }
private fun share(f: Double) = MarksyFormat.percent(f * 100, 0, signed = false)
// Returns arrive as fractions (0.032 = 3.2%).
private fun returnPct(f: Double) = MarksyFormat.percent(f * 100, 2)
internal fun callRupees(v: Double) = MarksyFormat.rupees(v)
