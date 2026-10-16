package com.marksy.os.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.marksy.os.EmptyState
import com.marksy.os.MarksyFormat
import com.marksy.os.market.CallerScorecardDto
import com.marksy.os.market.FollowKey
import com.marksy.os.market.Follows
import com.marksy.os.market.HorizonHitDto
import com.marksy.os.market.LedgerCalls
import com.marksy.os.market.LedgerTipDto
import com.marksy.os.market.MarketDataState
import com.marksy.os.market.MarketIntelligenceRepository
import com.marksy.os.market.ScorecardDetailDto
import com.marksy.os.market.ScorecardQuery
import com.marksy.os.market.ScorecardSource
import com.marksy.os.market.ScorecardSources
import com.marksy.os.market.SeriesPointDto
import com.marksy.os.market.TopSymbolDto

private const val RECENT_PREVIEW = 5

private val HorizonShort = mapOf("INTRADAY" to "Intraday", "UP_TO_1_WEEK" to "≤ 1 week", "UP_TO_1_MONTH" to "≤ 1 month", "LONGER_THAN_1_MONTH" to "> 1 month")

/** One channel, finfluencer or engine: its trust score and why, outcomes, returns, horizons, then its tips and who tips there. */
@Composable
internal fun ScorecardDetailScreen(
    repository: MarketIntelligenceRepository, source: ScorecardSource, query: ScorecardQuery, bottomPadding: Dp,
    followed: Set<FollowKey>?, onToggleFollow: (FollowKey, String, Boolean) -> Unit, onBack: () -> Unit,
    onOpenSource: (ScorecardSource) -> Unit, onOpenStock: (String) -> Unit
) {
    val state by produceState<MarketDataState<ScorecardDetailDto>>(MarketDataState.Loading, source, query) { value = repository.scorecardDetail(source, query) }
    var ticket by remember { mutableStateOf<TradeIntent?>(null) }
    ticket?.let { TradeTicketSheet(it) { ticket = null } }
    ScorecardDetailContent(state, source, bottomPadding, followed, onToggleFollow, onBack, onOpenSource, onOpenStock) { tipId, dismiss ->
        TipSheetFor(
            repository, tipId, onDismiss = dismiss, onTrade = { dismiss(); ticket = it },
            onOpenStock = { dismiss(); onOpenStock(it) }, onScore = { dismiss(); onOpenSource(it) }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ScorecardDetailContent(
    state: MarketDataState<ScorecardDetailDto>, source: ScorecardSource, bottomPadding: Dp,
    followed: Set<FollowKey>?, onToggleFollow: (FollowKey, String, Boolean) -> Unit, onBack: () -> Unit,
    onOpenSource: (ScorecardSource) -> Unit, onOpenStock: (String) -> Unit,
    tipSheet: @Composable (String, () -> Unit) -> Unit = { _, _ -> }
) {
    var openTip by remember(source) { mutableStateOf<String?>(null) }
    openTip?.let { id -> tipSheet(id) { openTip = null } }
    var allRecent by remember(source) { mutableStateOf(false) }
    val d = (state as? MarketDataState.Loaded)?.value
    val name = d?.card?.name ?: source.name
    Column(Modifier.fillMaxSize().background(MarksyTheme.Background)) {
        DetailHeader(
            name, d?.let(ScorecardSources::kind), d?.let { Follows.isFollowing(followed, source.followKey, it.card.following) }, onBack
        ) { onToggleFollow(source.followKey, name, it) }
        MarksyList(Modifier.weight(1f), bottom = bottomPadding) {
            when {
                d != null -> {
                    item(key = "score") { ScoreCard(d) }
                    item(key = "tiles") { Tiles(d) }
                    item(key = "outcomes") { Outcomes(d) }
                    if (d.series.isNotEmpty()) item(key = "returns") { ReturnsCard(d.series) }
                    d.byHorizon.filter { it.completed > 0 }.takeIf { it.isNotEmpty() }?.let { rows -> item(key = "horizon") { HorizonCard(rows) } }
                    if (d.recent.isNotEmpty()) item(key = "recent") {
                        MarksyCard {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Caption("RECENT TIPS", Modifier.weight(1f))
                                if (d.recent.size > RECENT_PREVIEW) Text(
                                    if (allRecent) "Show less" else "Show all ${d.recent.size}", color = MarksyTheme.PrimaryEmerald, style = MarksyType.Meta, fontWeight = FontWeight.SemiBold,
                                    maxLines = 1, modifier = Modifier.clip(MarksyShape.Chip).clickable(role = Role.Button) { allRecent = !allRecent }.padding(vertical = MarksySpace.Tight)
                                )
                            }
                            Column {
                                (if (allRecent) d.recent else d.recent.take(RECENT_PREVIEW)).forEach { t ->
                                    MarksyDivider()
                                    RecentRow(t) { openTip = t.tipId }
                                }
                            }
                        }
                    }
                    d.callers?.takeIf { it.isNotEmpty() }?.let { callers ->
                        item(key = "callers") {
                            MarksyCard {
                                Caption("FINFLUENCERS IN THIS CHANNEL")
                                Column {
                                    callers.forEach { c ->
                                        MarksyDivider()
                                        CallerRow(c) { onOpenSource(ScorecardSource.caller(c)) }
                                    }
                                }
                            }
                        }
                    }
                    if (d.topSymbols.isNotEmpty()) item(key = "symbols") {
                        FlowRow(
                            Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(MarksySpace.Inner),
                            verticalArrangement = Arrangement.spacedBy(MarksySpace.Inner), itemVerticalAlignment = Alignment.CenterVertically
                        ) {
                            Caption("MOST TIPPED")
                            d.topSymbols.forEach { s -> SymbolChip(s) { onOpenStock(s.symbol) } }
                        }
                    }
                }
                state is MarketDataState.Unavailable -> item { EmptyState("Marksy is not connected", "Sign in to your Marksy account in More.") }
                state is MarketDataState.Error -> item { EmptyState("Scorecard unavailable", state.message) }
                else -> item { MarksyLoader("Loading ${source.name}…") }
            }
        }
    }
}

@Composable
private fun Caption(text: String, modifier: Modifier = Modifier) =
    Text(text, color = MarksyTheme.TextMuted, style = MarksyType.PriceLabel, maxLines = 1, modifier = modifier)

@Composable
private fun DetailHeader(name: String, kind: String?, following: Boolean?, onBack: () -> Unit, onFollow: (Boolean) -> Unit) {
    // The star's 44dp touch box sits 4dp outside its circle, so the end inset is 4dp short of the gutter.
    Row(
        Modifier.fillMaxWidth().padding(start = MarksySpace.Gutter, end = MarksySpace.Section, top = MarksySpace.Tight, bottom = MarksySpace.Tight),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(MarksySpace.ListGap)
    ) {
        HeaderIconBadge(Icons.Default.ChevronLeft, "Back to Trust", onBack)
        Column(Modifier.weight(1f).heightIn(min = MarksySize.Touch), verticalArrangement = Arrangement.Center) {
            Text(name, color = MarksyTheme.TextPrimary, style = MarksyType.Heading, maxLines = 1, overflow = TextOverflow.Ellipsis)
            kind?.let { Text(it, color = MarksyTheme.TextSecondary, style = MarksyType.Meta, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        following?.let { FollowStar(it, name, onFollow, size = MarksySize.HeaderButton) }
    }
}

@Composable
private fun ScoreCard(d: ScorecardDetailDto) {
    val b = d.card.body
    val score = b.trust.trustScore
    MarksyCard {
        Row(horizontalArrangement = Arrangement.spacedBy(MarksySpace.Gap)) {
            Text(score?.toString() ?: "—", color = score?.let { trackColor(TipTrack.band(it)) } ?: MarksyTheme.TextMuted, style = MarksyType.Display, maxLines = 1, modifier = Modifier.alignByBaseline())
            Text("/ 100 trust", color = MarksyTheme.TextSecondary, style = MarksyType.Small, maxLines = 1, modifier = Modifier.alignByBaseline())
            Text(
                "${b.counts.completed} closed · ${b.counts.open} open", color = MarksyTheme.TextSecondary, style = MarksyType.Meta, maxLines = 1,
                modifier = Modifier.weight(1f).alignByBaseline(), textAlign = TextAlign.End
            )
        }
        Text(TrustModel.scoreWhy(b.trust), color = MarksyTheme.TextSecondary, style = MarksyType.Small)
        HitRateBand(d)
    }
}

/** The likely hit-rate range shaded on 0–100 with the observed rate marked; a progress line until a source is scored. */
@Composable
private fun HitRateBand(d: ScorecardDetailDto) {
    val b = d.card.body
    val t = b.trust
    val hit = b.performance.hitRatePct
    val band = d.trustBand
    val bar = ScorecardGraphics.hitRateBand(band, hit)
    Column(verticalArrangement = Arrangement.spacedBy(MarksySpace.Tight)) {
        Canvas(Modifier.fillMaxWidth().height(14.dp)) {
            val mid = size.height / 2
            val h = 8.dp.toPx()
            val r = h / 2
            val span = size.width - h
            drawLine(MarksyTheme.SurfaceRaised, Offset(r, mid), Offset(size.width - r, mid), h, StrokeCap.Round)
            if (bar != null) {
                drawLine(MarksyTheme.Positive.copy(alpha = .35f), Offset(r + span * bar.low, mid), Offset(r + span * bar.high, mid), h, StrokeCap.Round)
                bar.marker?.let { m -> (r + span * m).let { x -> drawLine(MarksyTheme.TextPrimary, Offset(x, 0f), Offset(x, size.height), 3.dp.toPx(), StrokeCap.Round) } }
            } else if (t.minimumCompleted > 0) {
                val done = (t.completed.toFloat() / t.minimumCompleted).coerceIn(0f, 1f)
                if (done > 0f) drawLine(MarksyTheme.TextMuted, Offset(r, mid), Offset(r + span * done, mid), h, StrokeCap.Round)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            if (band != null) {
                Text(hit?.let { "hit rate ${MarksyFormat.percent(it, 0, signed = false)}" }.orEmpty(), color = MarksyTheme.TextSecondary, style = MarksyType.Meta, maxLines = 1)
                Text(
                    "likely ${Math.round(band.low * 100)}–${Math.round(band.high * 100)}% with ${t.completed} closed",
                    color = MarksyTheme.TextSecondary, style = MarksyType.Meta, maxLines = 1
                )
            } else Text(TrustModel.progress(b).second, color = MarksyTheme.TextSecondary, style = MarksyType.Meta, maxLines = 1)
        }
    }
}

@Composable
private fun Tiles(d: ScorecardDetailDto) {
    val p = d.card.body.performance
    val ret = p.avgActualReturn
    MarksyStatRow(Modifier.height(IntrinsicSize.Min)) {
        MarksyStat("Avg from entry", LedgerCalls.returnText(ret) ?: "—", Modifier.weight(1f).fillMaxHeight(), ret?.let { ScorecardGraphics.returnTone(it) } ?: MarksyTheme.TextPrimary)
        MarksyStat("Delivered", p.returnRealizationPct?.let { MarksyFormat.percent(it, 0, signed = false) } ?: "—", Modifier.weight(1f).fillMaxHeight(), note = "of promised")
        MarksyStat("Avg days", p.avgDaysToCompletion?.let { MarksyFormat.number(it, 1) } ?: "—", Modifier.weight(1f).fillMaxHeight(), note = "to close")
    }
}

@Composable
private fun Outcomes(d: ScorecardDetailDto) {
    val c = d.card.body.counts
    MarksyCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Caption("OUTCOMES", Modifier.weight(1f))
            if (c.invalidated > 0) Text("${c.invalidated} voided before buy · not scored", color = MarksyTheme.TextMuted, style = MarksyType.Caption, maxLines = 1)
        }
        OutcomeBar(c.successful, c.failed, c.expired, c.open, height = 8.dp)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            listOf("Target" to c.successful, "Stop" to c.failed, "Expired" to c.expired, "Open" to c.open).forEach { (label, n) ->
                Text("$label $n", color = MarksyTheme.TextSecondary, style = MarksyType.Small, maxLines = 1)
            }
        }
    }
}

/** Cumulative returns tip by tip: what the source delivered against what its targets promised. */
@Composable
private fun ReturnsCard(series: List<SeriesPointDto>) {
    val points = if (series.size == 1) series + series else series
    val realised = points.map { it.realisedCum * 100 }
    val promised = points.map { it.promisedCum * 100 }
    val tint = ScorecardGraphics.returnTone(realised.last())
    MarksyCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(MarksySpace.Gap)) {
            Caption("RETURNS · DELIVERED VS PROMISED", Modifier.weight(1f))
            LegendLine(tint, dashed = false, "delivered")
            LegendLine(MarksyTheme.TextSecondary, dashed = true, "promised")
        }
        Canvas(Modifier.fillMaxWidth().height(70.dp)) {
            val (lo, hi) = ScorecardGraphics.bounds(realised, promised)
            val pad = 3.dp.toPx()
            fun at(p: Pair<Float, Float>) = Offset(p.first * size.width, pad + p.second * (size.height - 2 * pad))
            fun line(pts: List<Offset>) = Path().apply { pts.forEachIndexed { i, o -> if (i == 0) moveTo(o.x, o.y) else lineTo(o.x, o.y) } }
            val zero = at(0f to ScorecardGraphics.chartPoints(listOf(0.0), lo, hi).first().second).y
            drawLine(MarksyTheme.Divider, Offset(0f, zero), Offset(size.width, zero), 1.dp.toPx())
            drawPath(line(ScorecardGraphics.chartPoints(promised, lo, hi).map(::at)), MarksyTheme.TextSecondary, style = Stroke(1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))))
            drawPath(line(ScorecardGraphics.chartPoints(realised, lo, hi).map(::at)), tint, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}

@Composable
private fun LegendLine(color: Color, dashed: Boolean, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(MarksySpace.Tight)) {
        Canvas(Modifier.size(12.dp, 3.dp)) {
            drawLine(
                color, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 2.dp.toPx(),
                pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 2.dp.toPx())) else null
            )
        }
        Text(label, color = MarksyTheme.TextSecondary, style = MarksyType.Caption, maxLines = 1)
    }
}

@Composable
private fun HorizonCard(rows: List<HorizonHitDto>) {
    MarksyCard {
        Caption("TARGET HIT BY HORIZON")
        rows.forEach { h ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(MarksySpace.Gap)) {
                Text(HorizonShort[h.horizon] ?: h.horizon, color = MarksyTheme.TextSecondary, style = MarksyType.Small, maxLines = 1, modifier = Modifier.width(72.dp))
                Box(Modifier.weight(1f).height(MarksySize.Dot).clip(CircleShape).background(MarksyTheme.Track)) {
                    val share = ((h.hitRatePct ?: 0.0) / 100).coerceIn(0.0, 1.0).toFloat()
                    if (share > 0f) Box(Modifier.fillMaxWidth(share).fillMaxHeight().clip(CircleShape).background(MarksyTheme.Positive))
                }
                Row(Modifier.width(84.dp), horizontalArrangement = Arrangement.End) {
                    Text(h.hitRatePct?.let { MarksyFormat.percent(it, 0, signed = false) } ?: "—", color = MarksyTheme.TextPrimary, style = MarksyType.Small, maxLines = 1)
                    Text(" · ${h.completed}", color = MarksyTheme.TextMuted, style = MarksyType.Small, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun RecentRow(t: LedgerTipDto, onClick: () -> Unit) {
    val ret = if (LedgerCalls.isActive(t)) t.latestProgress?.returnToDate else t.actualReturn
    Row(
        Modifier.fillMaxWidth().heightIn(min = MarksySize.Touch).clickable(role = Role.Button, onClickLabel = "Open ${t.symbol} tip", onClick = onClick),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(MarksySpace.ListGap)
    ) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(MarksySpace.Inner)) {
            Text(t.symbol, color = MarksyTheme.TextPrimary, style = MarksyType.Body, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            t.direction?.let { SideBadge(it == "SELL") }
            Text("· ${LedgerCalls.day(t.firstSeenAt)}", color = MarksyTheme.TextMuted, style = MarksyType.Small, maxLines = 1)
        }
        Text(TipTrack.status(t), color = trackColor(TipTrack.statusTone(t)), style = MarksyType.Meta, maxLines = 1)
        Text((LedgerCalls.returnText(ret) ?: "—") + " ›", color = ScorecardGraphics.returnTone(ret), style = MarksyType.Body, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

@Composable
private fun CallerRow(c: CallerScorecardDto, onClick: () -> Unit) {
    val h = c.scorecard
    Row(
        Modifier.fillMaxWidth().heightIn(min = MarksySize.Touch).clickable(role = Role.Button, onClickLabel = "Open ${c.name} record", onClick = onClick),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(MarksySpace.Gap)
    ) {
        Text(c.name, color = MarksyTheme.TextPrimary, style = MarksyType.Body, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Text(
            "${h.completed} closed" + (h.hitRatePct?.let { " · ${MarksyFormat.percent(it, 0, signed = false)} hit" } ?: ""),
            color = MarksyTheme.TextSecondary, style = MarksyType.Meta, maxLines = 1
        )
        ScoreBadge(h.trustScore)
    }
}

@Composable
private fun SymbolChip(s: TopSymbolDto, onClick: () -> Unit) {
    val ret = s.avgActualReturn
    Row(
        Modifier.clip(MarksyShape.Pill).background(MarksyTheme.SurfaceRaised).clickable(role = Role.Button, onClickLabel = "View ${s.symbol}", onClick = onClick)
            .padding(horizontal = MarksySpace.Gap, vertical = MarksySpace.Tight)
    ) {
        Text("${s.symbol} ${s.calls}", color = MarksyTheme.TextSecondary, style = MarksyType.Meta, maxLines = 1)
        ret?.let { Text(" · " + MarksyFormat.percent(it * 100, 1), color = ScorecardGraphics.returnTone(it), style = MarksyType.Meta, maxLines = 1) }
    }
}
