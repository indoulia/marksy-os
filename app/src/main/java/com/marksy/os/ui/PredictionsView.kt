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
import com.marksy.os.market.EntityScorecardDto
import com.marksy.os.market.ScorecardBodyDto
import com.marksy.os.market.ScorecardEntity
import com.marksy.os.market.ScorecardPeriod
import com.marksy.os.market.ScorecardQuery
import com.marksy.os.market.ScorecardSources
import com.marksy.os.market.PicksBasis
import com.marksy.os.upstox.FeedFreshness
import com.marksy.os.upstox.UpstoxLtp
import java.time.LocalDate
import java.time.ZoneId

/** A cursor-paged list that grows as its last row scrolls into view; with [key], a row a later page repeats is kept once (a repeated list key crashes the list). */
@Stable
internal class Paged<T>(private val key: ((T) -> Any)? = null, private val fetch: suspend (String?) -> MarketDataState<Pair<List<T>, String?>>) {
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
            is MarketDataState.Loaded -> { items = (items + r.value.first).let { all -> key?.let(all::distinctBy) ?: all }; cursor = r.value.second; hasMore = cursor != null; state = MarketDataState.Loaded(Unit) }
            is MarketDataState.Empty -> { hasMore = false; state = if (items.isEmpty()) MarketDataState.Empty else MarketDataState.Loaded(Unit) }
            is MarketDataState.Error -> { hasMore = false; failed = true; if (items.isEmpty()) state = r }
            is MarketDataState.Unavailable -> { hasMore = false; state = r }
            else -> Unit
        }
        busy = false
    }
}

/** History's Marksy lists: the track record with its closed calls, or the calls that ended early, each with its outcome. Live calls are in Setups. */
@Composable
internal fun PredictionsView(repository: MarketIntelligenceRepository, showEnded: Boolean, bottomPadding: Dp, onOpenStock: (String) -> Unit) {
    // Keyset-paged on a re-rankable score: a re-rank between pages can repeat a call (kept once) or skip one (open marksy-api follow-up).
    val open = remember { Paged<ActivePredictionDto>({ it.predictionId }) { c -> repository.activePredictions(c).map { it.items to it.nextCursor } } }
    val closed = remember { Paged { c -> repository.closedPredictions(c).map { it.items to it.nextCursor } } }
    val record by produceState<ScorecardBodyDto?>(null) {
        value = (repository.scorecards(ScorecardEntity.ENGINE, TrackRecordQuery) as? MarketDataState.Loaded)?.value?.items?.let(::predictionEngineCard)?.body
    }
    LaunchedEffect(Unit) { closed.more(); open.more() }
    // Withdrawn or unpriced calls still sit in the active feed; they never count or rank as open.
    val ended = remember(open.items) { open.items.filterNot(LedgerCalls::isLive) }
    val quotes = rememberUpstoxQuotes(remember(ended) { ended.map { it.symbol }.distinct() })
    val feed = rememberFeedFreshness()
    val paged = if (showEnded) open else closed
    val sections = remember(closed.items) { closedSections(closed.items) }
    var invalidatedOpen by rememberSaveable { mutableStateOf(false) }
    val endedHint = "Calls withdrawn, invalidated or left without market data before closing appear here."

    MarksyList(bottom = bottomPadding) {
        // The record summarises closed calls only, so it sits above them and not above ended ones.
        if (!showEnded) item(key = "record") { TrackRecordStrip(record) }
        when (val s = paged.state) {
            is MarketDataState.Loading -> item { MarksyLoader("Loading Marksy calls…") }
            is MarketDataState.Unavailable -> item { EmptyState("Marksy is not connected", "Sign in to your Marksy account in More.") }
            is MarketDataState.Error -> item { EmptyState("Calls unavailable", s.message) }
            is MarketDataState.Empty -> item { EmptyState(if (showEnded) "No ended calls" else "No closed calls yet", if (showEnded) endedHint else "Calls appear here once their target, stop or horizon is reached.") }
            else -> {
                if (!showEnded) {
                    items(sections.shown, key = { "c-${it.row.id}" }) { c -> ClosedCallRow(c.row, c.predictions) { onOpenStock(c.row.symbol) } }
                    if (sections.shown.isEmpty() && !closed.hasMore) item(key = "no-scored") { InlineEmpty("No scored calls yet") }
                }
                else {
                    if (ended.isEmpty() && !open.hasMore) item { EmptyState("No ended calls", endedHint) }
                    items(ended, key = { "e-${it.predictionId}" }) { p -> OpenCallRow(p, quotes[p.symbol], feed, note = LedgerCalls.endedLine(p)) { onOpenStock(p.symbol) } }
                }
                if (paged.hasMore) item(key = "more-$showEnded-${paged.items.size}") {
                    LaunchedEffect(Unit) { paged.more() }
                    MarksyLoader("Loading more…")
                }
                if (!showEnded && sections.invalidated.isNotEmpty()) {
                    val n = sections.invalidated.size
                    item(key = "invalidated-fold") {
                        FoldRow(
                            "Invalidated before entry",
                            "$n call${if (n == 1) "" else "s"}${if (closed.hasMore) " so far" else ""} · voided before entry, so excluded from scored results",
                            invalidatedOpen
                        ) { invalidatedOpen = !invalidatedOpen }
                    }
                    if (invalidatedOpen) items(sections.invalidated, key = { "i-${it.row.id}" }) { c -> ClosedCallRow(c.row, c.predictions) { onOpenStock(c.row.symbol) } }
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

// The ledger's §8.1 record, not `/performance/summary`: that price-only label record also counts calls withdrawn before entry.
private val TrackRecordQuery = ScorecardQuery(entity = ScorecardEntity.ENGINE, period = ScorecardPeriod.LAST_30_DAYS)
// marksy-api `CALLER_PREDICTION_ENGINE`: the engine whose calls these lists show.
private const val PREDICTION_ENGINE = "Prediction engine"

internal fun predictionEngineCard(cards: List<EntityScorecardDto>): EntityScorecardDto? =
    cards.firstOrNull { ScorecardSources.isEngine(it) && it.name == PREDICTION_ENGINE }

@Composable
private fun TrackRecordStrip(record: ScorecardBodyDto?) {
    val r = record ?: return
    val c = r.counts
    if (c.total == 0) return
    val ret = r.performance.avgActualReturn
    MarksyRowCard {
        MarksyCardHeader("Marksy track record") {
            // The completed count is in the note: on the header it pushed the title into an ellipsis on the phone.
            Text("${TrackRecordQuery.label()} of calls", color = MarksyTheme.TextMuted, style = MarksyType.Meta, maxLines = 1)
        }
        Row(Modifier.fillMaxWidth().padding(top = MarksySpace.Tight), horizontalArrangement = Arrangement.SpaceBetween) {
            Stat("Succeeded", "${c.successful}", MarksyTheme.PrimaryEmerald)
            Stat("Failed", "${c.failed}", MarksyTheme.Negative)
            Stat("Expired", "${c.expired}", MarksyTheme.TextSecondary)
            Stat("Avg return", LedgerCalls.returnText(ret), ScorecardGraphics.returnTone(ret))
        }
        Text(trackRecordNote(r), color = MarksyTheme.TextMuted, style = MarksyType.Caption, modifier = Modifier.padding(top = MarksySpace.Tight))
    }
}

// Server §8.1: invalidated, unscorable and data-unresolved calls are counted but never enter a rate or a return.
internal fun trackRecordNote(r: ScorecardBodyDto): String {
    val c = r.counts
    val left = listOfNotNull(
        c.invalidated.takeIf { it > 0 }?.let { "$it invalidated" },
        c.unscorable.takeIf { it > 0 }?.let { "$it unscorable" },
        c.dataUnresolved.takeIf { it > 0 }?.let { "$it without market data" },
        c.open.takeIf { it > 0 }?.let { "$it open" }
    )
    return listOfNotNull(
        r.performance.hitRatePct?.let { "Hit rate ${MarksyFormat.percent(it, 1, signed = false)} of ${c.completed} completed" },
        left.takeIf { it.isNotEmpty() }?.let { (if (it.size == 1) it.first() else it.dropLast(1).joinToString(", ") + " and " + it.last()) + " not counted" },
        "avg return is per completed call from its entry"
    ).joinToString(" · ")
}

@Composable
private fun Stat(label: String, value: String?, tint: Color) {
    Column {
        Text(value ?: "—", color = if (value == null) MarksyTheme.TextMuted else tint, style = MarksyType.Subhead, fontWeight = FontWeight.Bold)
        Text(label, color = MarksyTheme.TextMuted, style = MarksyType.Caption)
    }
}

@Composable
internal fun OpenCallRow(p: ActivePredictionDto, quote: UpstoxLtp?, feed: FeedFreshness, note: String? = null, onClick: () -> Unit) {
    val bar = CallPrice.of(quote, feed, p.price, p.lastPriceAt, LocalDate.now(ZoneId.of("Asia/Kolkata")))
    val price = bar.price
    val buy = p.targetPrice >= p.entryPrice
    MarksyRowCard(
        Modifier.animateContentSize(), border = if (p.isActionableNow) MarksyTheme.PrimaryEmerald else MarksyTheme.BorderGlow, onClick = onClick
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(p.symbol, color = MarksyTheme.TextPrimary, style = MarksyType.Subhead, fontWeight = FontWeight.Bold)
            MarksyBadge(if (buy) "BUY" else "SELL", if (buy) MarksyTheme.Positive else MarksyTheme.Negative, MarksyTheme.SurfaceRaised, Modifier.padding(start = MarksySpace.Inner))
            Text(p.companyName.orEmpty(), color = MarksyTheme.TextMuted, style = MarksyType.Meta, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(start = MarksySpace.Inner))
            price?.let {
                Column(horizontalAlignment = Alignment.End) {
                    Text(callRupees(it), color = MarksyTheme.TextPrimary, style = MarksyType.Body, fontWeight = FontWeight.SemiBold)
                    bar.label?.let { l -> Text(l, color = if (bar.live) MarksyTheme.PrimaryEmerald else MarksyTheme.TextMuted, style = MarksyType.Caption, maxLines = 1) }
                }
            }
        }
        CallRange(p.stopLoss, p.entryPrice, p.targetPrice, price, Modifier.fillMaxWidth().padding(vertical = MarksySpace.Inner).height(8.dp))
        Row {
            Text("SL ${callRupees(p.stopLoss)}", color = MarksyTheme.Negative, style = MarksyType.Caption)
            Text("Entry ${callRupees(p.entryPrice)}", color = MarksyTheme.TextMuted, style = MarksyType.Caption, modifier = Modifier.weight(1f).padding(horizontal = MarksySpace.Inner), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            Text("Target ${callRupees(p.targetPrice)}", color = MarksyTheme.PrimaryEmerald, style = MarksyType.Caption)
        }
        // Server-computed (TPR-001) and only on a live call, so an ended or closed one never shows a live value.
        p.ledger?.takeIf { LedgerCalls.isLive(p) }?.let { t ->
            LedgerCalls.targetProgressLine(t)?.let {
                Text(it, color = MarksyTheme.TextSecondary, style = MarksyType.Meta, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = MarksySpace.Tight))
            }
            if (t.targetProgress?.status == LedgerCalls.TARGET_PROGRESS_AVAILABLE) Text(LedgerCalls.targetProgressNote(bar.live), color = MarksyTheme.TextMuted, style = MarksyType.Caption)
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

/** One ledger call and how many predictions the ledger merged into it (a MERGED_DUPLICATE follower reads the tip it follows). */
internal data class ClosedCall(val row: ClosedPredictionDto, val predictions: Int)

/** Calls the ledger voided before entry sit apart from the rest, which keep their server order. */
internal data class ClosedSections(val shown: List<ClosedCall>, val invalidated: List<ClosedCall>)

// Rows fold only on a shared ledger tip id, never on symbol, outcome or return; a row without a ledger stays its own call.
internal fun closedSections(items: List<ClosedPredictionDto>): ClosedSections {
    val calls = items.groupBy { c -> c.ledger?.tipId?.takeIf { it.isNotEmpty() }?.let { "t:$it" } ?: "p:${c.id}" }
        .values.map { ClosedCall(it.first(), it.size) }
    val (invalidated, shown) = calls.partition { it.row.ledger?.status == LedgerCalls.STATUS_INVALIDATED }
    return ClosedSections(shown, invalidated)
}

@Composable
private fun ClosedCallRow(c: ClosedPredictionDto, predictions: Int = 1, onClick: () -> Unit) {
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
                listOfNotNull(c.predictedReturn?.let { "called ${returnPct(it)}" }, "${c.horizonDays}-day", c.asOf?.let { runCatching { MarksyFormat.day(java.time.LocalDate.parse(it.take(10))) }.getOrDefault(it.take(10)) }, c.excludedReason?.let { "not scored" }, "$predictions predictions".takeIf { predictions > 1 })
                    .joinToString(" · "),
                color = MarksyTheme.TextMuted, style = MarksyType.Meta
            )
        }
        LedgerCalls.closedReturn(c)?.let { Text(returnPct(it), color = if (it >= 0) MarksyTheme.PrimaryEmerald else MarksyTheme.Negative, style = MarksyType.Subhead, fontWeight = FontWeight.Bold) }
      }
    }
}

private fun words(s: String) = s.lowercase().replace('_', ' ').replaceFirstChar { it.titlecase() }
// Returns arrive as fractions (0.032 = 3.2%).
private fun returnPct(f: Double) = MarksyFormat.percent(f * 100, 2)
internal fun callRupees(v: Double) = MarksyFormat.rupees(v)
