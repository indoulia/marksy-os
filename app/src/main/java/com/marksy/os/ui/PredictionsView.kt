package com.marksy.os.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
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
import com.marksy.os.market.ActivePredictionDto
import com.marksy.os.market.ClosedPredictionDto
import com.marksy.os.market.MarketDataState
import com.marksy.os.market.MarketIntelligenceRepository
import com.marksy.os.market.PerformanceSummaryDto
import com.marksy.os.market.PicksBasis
import java.util.Locale

/** A cursor-paged list that grows as its last row scrolls into view. */
@Stable
private class Paged<T>(private val fetch: suspend (String?) -> MarketDataState<Pair<List<T>, String?>>) {
    var items by mutableStateOf<List<T>>(emptyList())
    var state by mutableStateOf<MarketDataState<Unit>>(MarketDataState.Loading)
    var hasMore by mutableStateOf(true)
    private var cursor: String? = null
    private var busy = false

    suspend fun more() {
        if (busy || !hasMore) return
        busy = true
        when (val r = fetch(cursor)) {
            is MarketDataState.Loaded -> { items = items + r.value.first; cursor = r.value.second; hasMore = cursor != null; state = MarketDataState.Loaded(Unit) }
            is MarketDataState.Empty -> { hasMore = false; state = if (items.isEmpty()) MarketDataState.Empty else MarketDataState.Loaded(Unit) }
            is MarketDataState.Error -> { hasMore = false; if (items.isEmpty()) state = r }
            is MarketDataState.Unavailable -> { hasMore = false; state = r }
            else -> Unit
        }
        busy = false
    }
}

/** Marksy's open calls with where price sits between stop and target, and closed calls with their outcome. */
@Composable
internal fun PredictionsView(repository: MarketIntelligenceRepository, bottomPadding: Dp, onOpenStock: (String) -> Unit) {
    var showClosed by rememberSaveable { mutableStateOf(false) }
    val open = remember { Paged { c -> repository.activePredictions(c).map { it.items to it.nextCursor } } }
    val closed = remember { Paged { c -> repository.closedPredictions(c).map { it.items to it.nextCursor } } }
    val summary by produceState<PerformanceSummaryDto?>(null) { value = (repository.performanceSummary() as? MarketDataState.Loaded)?.value }
    LaunchedEffect(Unit) { open.more() }
    LaunchedEffect(showClosed) { if (showClosed && closed.items.isEmpty()) closed.more() }
    val quotes = rememberUpstoxQuotes(remember(open.items) { open.items.map { it.symbol }.distinct() })
    val paged = if (showClosed) closed else open
    // Invalidated calls stay listed for honesty but never count or rank as open.
    val (live, invalidated) = remember(open.items) { open.items.partition { it.lifecycleState !in INVALIDATED } }
    var showInvalidated by rememberSaveable { mutableStateOf(false) }

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 18.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(top = 8.dp, bottom = bottomPadding)
    ) {
        item(key = "record") { TrackRecordStrip(summary) }
        item(key = "segments") {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Pill("Open" + (if (open.items.isEmpty()) "" else " (${live.size}${if (open.hasMore) "+" else ""})"), selected = !showClosed) { showClosed = false }
                Pill("Closed", selected = showClosed) { showClosed = true }
            }
        }
        when (val s = paged.state) {
            is MarketDataState.Loading -> item { MarksyLoader("Loading Marksy calls...") }
            is MarketDataState.Unavailable -> item { EmptyState("Marksy is not connected", "Sign in to your Marksy account in More.") }
            is MarketDataState.Error -> item { EmptyState("Calls unavailable", s.message) }
            is MarketDataState.Empty -> item { EmptyState(if (showClosed) "No closed calls yet" else "No open Marksy calls", if (showClosed) "Calls appear here once their target, stop or horizon is reached." else "New calls appear here as Marksy makes them.") }
            else -> {
                if (showClosed) items(closed.items, key = { "c-${it.id}" }) { ClosedCallRow(it) { onOpenStock(it.symbol) } }
                else {
                    if (live.isEmpty() && !open.hasMore) item { EmptyState("No live Marksy calls", "Every open call has been invalidated; new calls appear here as Marksy makes them.") }
                    items(live, key = { "o-${it.predictionId}" }) { p -> OpenCallRow(p, quotes[p.symbol]?.lastPrice) { onOpenStock(p.symbol) } }
                    if (invalidated.isNotEmpty()) item(key = "inv-toggle") {
                        Text(
                            "${if (showInvalidated) "Hide" else "Show"} ${invalidated.size} invalidated call${if (invalidated.size == 1) "" else "s"}",
                            color = MarksyTheme.TextSecondary, fontSize = 12.sp,
                            modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable { showInvalidated = !showInvalidated }.padding(horizontal = 4.dp, vertical = 6.dp)
                        )
                    }
                    if (showInvalidated) items(invalidated, key = { "i-${it.predictionId}" }) { p ->
                        Box(Modifier.alpha(0.55f)) { OpenCallRow(p, quotes[p.symbol]?.lastPrice) { onOpenStock(p.symbol) } }
                    }
                }
                if (paged.hasMore) item(key = "more-$showClosed-${paged.items.size}") {
                    LaunchedEffect(Unit) { paged.more() }
                    MarksyLoader("Loading more...")
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
    val shape = RoundedCornerShape(12.dp)
    Column(Modifier.fillMaxWidth().clip(shape).background(MarksyTheme.Surface).border(1.dp, MarksyTheme.BorderGlow, shape).padding(horizontal = 12.dp, vertical = 8.dp)) {
        Text("Track record · ${s.range.replace("d", " days")} · ${s.closedCount} closed${if (s.smallSample) " · small sample" else ""}", color = MarksyTheme.TextMuted, fontSize = 11.sp)
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Stat("Target", s.targetHitRate?.let(::share), MarksyTheme.PrimaryEmerald)
            Stat("Stopped", s.stopLossRate?.let(::share), MarksyTheme.RedUrgent)
            Stat("Expired", s.horizonExpiryRate?.let(::share), MarksyTheme.TextSecondary)
            Stat("Avg return", s.avgRealizedReturn?.let(::returnPct), if ((s.avgRealizedReturn ?: 0.0) >= 0) MarksyTheme.PrimaryEmerald else MarksyTheme.RedUrgent)
        }
    }
}

@Composable
private fun Stat(label: String, value: String?, tint: Color) {
    Column {
        Text(value ?: "—", color = if (value == null) MarksyTheme.TextMuted else tint, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        Text(label, color = MarksyTheme.TextMuted, fontSize = 10.sp)
    }
}

@Composable
internal fun OpenCallRow(p: ActivePredictionDto, livePrice: Double?, note: String? = null, onClick: () -> Unit) {
    val price = livePrice ?: p.price
    val buy = p.targetPrice >= p.entryPrice
    val shape = RoundedCornerShape(12.dp)
    Column(
        Modifier.fillMaxWidth().clip(shape).background(MarksyTheme.Surface)
            .border(1.dp, if (p.isActionableNow) MarksyTheme.PrimaryEmerald else MarksyTheme.BorderGlow, shape)
            .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 9.dp).animateContentSize()
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(p.symbol, color = MarksyTheme.TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Text(
                if (buy) "BUY" else "SELL", color = if (buy) MarksyTheme.PrimaryEmerald else MarksyTheme.RedUrgent, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 6.dp).clip(RoundedCornerShape(6.dp)).background(MarksyTheme.SurfaceRaised).padding(horizontal = 5.dp, vertical = 1.dp)
            )
            Text(p.companyName.orEmpty(), color = MarksyTheme.TextMuted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(start = 6.dp))
            price?.let { Text(callRupees(it), color = MarksyTheme.TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
        }
        CallRange(p.stopLoss, p.entryPrice, p.targetPrice, price, Modifier.fillMaxWidth().padding(vertical = 6.dp).height(8.dp))
        Row {
            Text("SL ${callRupees(p.stopLoss)}", color = MarksyTheme.RedUrgent, fontSize = 10.sp)
            Text("Entry ${callRupees(p.entryPrice)}", color = MarksyTheme.TextMuted, fontSize = 10.sp, modifier = Modifier.weight(1f).padding(horizontal = 6.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            Text("Target ${callRupees(p.targetPrice)}", color = MarksyTheme.PrimaryEmerald, fontSize = 10.sp)
        }
        Text(
            listOfNotNull(
                p.remainingTradingDays?.let { "$it day${if (it == 1) "" else "s"} left" } ?: "${p.horizon}-day call",
                if (note == null) PicksBasis.day(p.scanSessionDate)?.let { "$it call" } else null,
                "conf ${(if (p.confidence <= 1) p.confidence * 100 else p.confidence).toInt()}%",
                p.lifecycleState.takeIf { it != "UNAVAILABLE" }?.let(::words),
                p.lifecycleDetail
            ).joinToString(" · "),
            color = MarksyTheme.TextSecondary, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp)
        )
        // Own line: which session and when the pick was made must never be ellipsized away.
        note?.let { Text(it, color = MarksyTheme.TextMuted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 1.dp)) }
    }
}

/** Stop → target as one bar; the filled part runs from entry to the current price. */
@Composable
private fun CallRange(stop: Double, entry: Double, target: Double, price: Double?, modifier: Modifier) {
    val green = MarksyTheme.PrimaryEmerald; val red = MarksyTheme.RedUrgent; val track = MarksyTheme.SurfaceRaised; val tick = MarksyTheme.TextSecondary
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
            drawCircle(Color.White, radius = h * 0.75f, center = Offset(p, h / 2))
        }
        drawRect(tick, topLeft = Offset(e - 1f, -2f), size = Size(2f, h + 4f))
    }
}

@Composable
private fun ClosedCallRow(c: ClosedPredictionDto, onClick: () -> Unit) {
    val o = c.outcome.orEmpty().uppercase()
    val tint = when { "TARGET" in o || "SUCCESS" in o -> MarksyTheme.PrimaryEmerald; "STOP" in o || "FAIL" in o -> MarksyTheme.RedUrgent; else -> MarksyTheme.TextSecondary }
    val shape = RoundedCornerShape(12.dp)
    Row(
        Modifier.fillMaxWidth().clip(shape).background(MarksyTheme.Surface).border(1.dp, MarksyTheme.BorderGlow, shape)
            .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(c.symbol, color = MarksyTheme.TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Text(c.outcome?.let(::words) ?: "Closed", color = tint, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 8.dp))
            }
            Text(
                listOfNotNull(c.predictedReturn?.let { "called ${returnPct(it)}" }, "${c.horizonDays}-day", c.asOf?.take(10), c.excludedReason?.let { "not scored" })
                    .joinToString(" · "),
                color = MarksyTheme.TextMuted, fontSize = 11.sp
            )
        }
        c.realizedReturn?.let { Text(returnPct(it), color = if (it >= 0) MarksyTheme.PrimaryEmerald else MarksyTheme.RedUrgent, fontSize = 14.sp, fontWeight = FontWeight.Bold) }
    }
}

internal val INVALIDATED = setOf("INVALIDATED", "DATA_UNRESOLVED")
private fun words(s: String) = s.lowercase().replace('_', ' ').replaceFirstChar { it.titlecase() }
private fun share(f: Double) = "${(f * 100).toInt()}%"
// Returns arrive as fractions (0.032 = 3.2%).
private fun returnPct(f: Double) = String.format(Locale.US, "%+.1f%%", f * 100)
internal fun callRupees(v: Double) = "₹" + String.format(Locale.getDefault(), "%,.2f", v)
