package com.marksy.os.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.TrendingDown
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import com.marksy.os.market.ScorecardText
import com.marksy.os.market.ScorecardTrustDto
import com.marksy.os.market.SeriesPointDto
import com.marksy.os.market.TopSymbolDto
import com.marksy.os.market.TrustBandDto
import java.time.LocalDate

private const val RECENT_PREVIEW = 5

/** One channel or caller laid out like the stock page: trust hero, KPI tiles, outcome donut, returns chart, then its calls. */
@Composable
internal fun ScorecardDetailScreen(
    repository: MarketIntelligenceRepository, source: ScorecardSource, query: ScorecardQuery, bottomPadding: Dp,
    followed: Set<FollowKey>?, onToggleFollow: (FollowKey, String, Boolean) -> Unit,
    onOpenSource: (ScorecardSource) -> Unit, onOpenStock: (String) -> Unit
) {
    val state by produceState<MarketDataState<ScorecardDetailDto>>(MarketDataState.Loading, source, query) { value = repository.scorecardDetail(source, query) }
    ScorecardDetailContent(state, source, bottomPadding, followed, onToggleFollow, onOpenSource, onOpenStock) { tipId, dismiss ->
        TipDetailDialog(repository, tipId, onOpenStock = { dismiss(); onOpenStock(it) }, onDismiss = dismiss)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ScorecardDetailContent(
    state: MarketDataState<ScorecardDetailDto>, source: ScorecardSource, bottomPadding: Dp,
    followed: Set<FollowKey>?, onToggleFollow: (FollowKey, String, Boolean) -> Unit,
    onOpenSource: (ScorecardSource) -> Unit, onOpenStock: (String) -> Unit,
    tipDialog: @Composable (String, () -> Unit) -> Unit = { _, _ -> }
) {
    var openTip by remember(source) { mutableStateOf<String?>(null) }
    openTip?.let { id -> tipDialog(id) { openTip = null } }
    var allRecent by remember(source) { mutableStateOf(false) }
    MarksyList(Modifier.background(MarksyTheme.Background), bottom = bottomPadding) {
        when (state) {
            is MarketDataState.Loaded -> {
                val d = state.value
                val name = d.card.name ?: source.name
                val following = Follows.isFollowing(followed, source.followKey, d.card.following)
                item(key = "hero") { Hero(d, name, following) { onToggleFollow(source.followKey, name, it) } }
                item(key = "kpis") { KpiTiles(d) }
                item(key = "donut") { OutcomeDonut(d) }
                item(key = "returns") { ReturnsCard(d.series) }
                item(key = "horizon") {
                    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(MarksySpace.ListGap)) {
                        HorizonBars(d.byHorizon, Modifier.weight(1f).fillMaxHeight())
                        DeliveredGauge(d.card.body.performance.returnRealizationPct, Modifier.weight(1f).fillMaxHeight())
                    }
                }
                if (d.recent.isNotEmpty()) {
                    item(key = "recent-head") {
                        SectionLabel("Recent calls") {
                            if (d.recent.size > RECENT_PREVIEW) MarksyButton(if (allRecent) "Show less" else "Show all recent", { allRecent = !allRecent }, style = MarksyButtonStyle.Text)
                        }
                    }
                    items(if (allRecent) d.recent else d.recent.take(RECENT_PREVIEW), key = { "recent-${it.tipId}" }) { t -> CallRow(t) { openTip = t.tipId } }
                }
                if (d.topSymbols.isNotEmpty()) {
                    item(key = "symbols-head") { SectionLabel("Most called") }
                    item(key = "symbols") {
                        MarksyCard {
                            LazyRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(MarksySpace.Gap), verticalAlignment = Alignment.CenterVertically) {
                                items(d.topSymbols, key = { it.symbol }) { s -> Pill("${s.symbol} · ${s.calls}", compact = true) { onOpenStock(s.symbol) } }
                            }
                        }
                    }
                }
                d.callers?.takeIf { it.isNotEmpty() }?.let { callers ->
                    item(key = "callers-head") { SectionLabel("Callers") }
                    items(callers) { c -> CallerRow(c) { onOpenSource(ScorecardSource.caller(c)) } }
                }
            }
            is MarketDataState.Unavailable -> item { EmptyState("Marksy is not connected", "Sign in to your Marksy account in More.") }
            is MarketDataState.Error -> item { EmptyState("Scorecard unavailable", state.message) }
            else -> item { MarksyLoader("Loading ${source.name}…") }
        }
    }
}

@Composable
private fun CardTitle(text: String) = Text(text.uppercase(), color = MarksyTheme.TextSecondary, style = MarksyType.Label)

@Composable
private fun Hero(d: ScorecardDetailDto, name: String, following: Boolean, onToggleFollow: (Boolean) -> Unit) {
    val since = d.card.filter.startDate?.let { runCatching { "since ${ScorecardText.date(LocalDate.parse(it))}" }.getOrNull() }
    Row(horizontalArrangement = Arrangement.spacedBy(MarksySpace.Wide), verticalAlignment = Alignment.CenterVertically) {
        TrustRing(d.card.body.trust.trustScore, 120.dp, 12.dp, MarksyType.Display)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(MarksySpace.Gap)) {
            Column {
                Text(name, color = MarksyTheme.TextPrimary, style = MarksyType.Display, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(listOfNotNull(ScorecardSources.kind(d), since).joinToString(" · "), color = MarksyTheme.TextSecondary, style = MarksyType.Small, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            HitRateBandBar(d.trustBand, d.card.body.performance.hitRatePct, d.card.body.trust)
            FollowPill(following, onToggleFollow)
        }
    }
}

@Composable
private fun HitRateBandBar(band: TrustBandDto?, hitRatePct: Double?, trust: ScorecardTrustDto) {
    val bar = ScorecardGraphics.hitRateBand(band, hitRatePct)
    Column(verticalArrangement = Arrangement.spacedBy(MarksySpace.Tight)) {
        Canvas(Modifier.fillMaxWidth().height(14.dp)) {
            val mid = size.height / 2
            val h = 8.dp.toPx()
            val r = h / 2
            drawLine(MarksyTheme.SurfaceRaised, Offset(r, mid), Offset(size.width - r, mid), h, StrokeCap.Round)
            val span = size.width - h
            if (bar != null) {
                drawLine(ScorecardGraphics.tone(hitRatePct), Offset(r + span * bar.low, mid), Offset(r + span * bar.high, mid), h, StrokeCap.Round)
                bar.marker?.let { m -> (r + span * m).let { x -> drawLine(MarksyTheme.TextPrimary, Offset(x, 0f), Offset(x, size.height), 2.dp.toPx()) } }
            } else if (trust.minimumCompleted > 0) {
                val done = (trust.completed.toFloat() / trust.minimumCompleted).coerceIn(0f, 1f)
                if (done > 0f) drawLine(MarksyTheme.TextMuted, Offset(r, mid), Offset(r + span * done, mid), h, StrokeCap.Round)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            if (band != null) {
                Text("0", color = MarksyTheme.TextMuted, style = MarksyType.Caption)
                Text(ScorecardGraphics.hitRateRangeText(band), color = MarksyTheme.TextMuted, style = MarksyType.Caption)
                Text("100", color = MarksyTheme.TextMuted, style = MarksyType.Caption)
            } else Text("${trust.completed}/${trust.minimumCompleted} completed for a hit-rate range", color = MarksyTheme.TextMuted, style = MarksyType.Caption, maxLines = 1)
        }
    }
}

@Composable
private fun KpiTiles(d: ScorecardDetailDto) {
    val p = d.card.body.performance
    val c = d.card.body.counts
    val ret = p.avgActualReturn
    MarksyStatRow(Modifier.height(IntrinsicSize.Min)) {
        MarksyStat("Hit rate", p.hitRatePct?.let { MarksyFormat.percent(it, 1, signed = false) } ?: "—", Modifier.weight(1f).fillMaxHeight(), ScorecardGraphics.tone(p.hitRatePct).takeIf { p.hitRatePct != null } ?: MarksyTheme.TextPrimary)
        MarksyStat("Avg return", LedgerCalls.returnText(ret) ?: "—", Modifier.weight(1f).fillMaxHeight(), ScorecardGraphics.returnTone(ret).takeIf { ret != null } ?: MarksyTheme.TextPrimary)
        MarksyStat("Completed", "${c.completed}/${c.total}", Modifier.weight(1f).fillMaxHeight())
        MarksyStat("To outcome", p.avgDaysToCompletion?.let { MarksyFormat.number(it, 1) + "d" } ?: "—", Modifier.weight(1f).fillMaxHeight())
    }
}

@Composable
private fun OutcomeDonut(d: ScorecardDetailDto) {
    val c = d.card.body.counts
    val parts = listOf(
        Triple("Target hit", c.successful, MarksyTheme.PrimaryEmerald), Triple("Stop hit", c.failed, MarksyTheme.Negative),
        Triple("Expired", c.expired, MarksyTheme.TextMuted), Triple("Open", c.open, MarksyTheme.SecondaryCyan),
        Triple("Invalidated", c.invalidated, MarksyTheme.TextMuted.copy(alpha = .4f))
    )
    val segments = ScorecardGraphics.donut(parts.map { it.second })
    MarksyCard {
      Row(horizontalArrangement = Arrangement.spacedBy(MarksySpace.Gap), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(96.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val w = 14.dp.toPx()
                val topLeft = Offset(w / 2, w / 2)
                val arc = Size(size.width - w, size.height - w)
                drawArc(MarksyTheme.SurfaceRaised, 0f, 360f, false, topLeft, arc, style = Stroke(w))
                segments.forEachIndexed { i, (start, sweep) ->
                    if (sweep > 0f) drawArc(parts[i].third, -90f + start * 360f, sweep * 360f, false, topLeft, arc, style = Stroke(w))
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("${c.total}", color = MarksyTheme.TextPrimary, style = MarksyType.Heading)
                Text("CALLS", color = MarksyTheme.TextSecondary, style = MarksyType.Caption)
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(MarksySpace.Inner)) {
            parts.forEach { (label, n, color) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(MarksySize.Dot).clip(CircleShape).background(color))
                    Spacer(Modifier.width(MarksySpace.Gap))
                    Text(label, color = MarksyTheme.TextSecondary, style = MarksyType.Body, modifier = Modifier.weight(1f), maxLines = 1)
                    Text("$n", color = MarksyTheme.TextPrimary, style = MarksyType.Body, fontWeight = FontWeight.Medium)
                }
            }
        }
      }
    }
}

@Composable
private fun ReturnsCard(series: List<SeriesPointDto>) {
    var range by remember { mutableStateOf(ReturnRange.ALL) }
    val points = remember(series, range) { ScorecardGraphics.window(series, range).let { if (it.size == 1) it + it else it } }
    val calls = remember(series, range) { series.takeLast(ScorecardGraphics.window(series, range).size).sumOf { it.n } }
    val realised = points.map { it.first }
    val promised = points.map { it.second }
    val tint = ScorecardGraphics.returnTone(realised.lastOrNull())
    val measurer = rememberTextMeasurer()
    Column(verticalArrangement = Arrangement.spacedBy(MarksySpace.ListGap)) {
    SectionLabel("Returns") {
        Row(horizontalArrangement = Arrangement.spacedBy(MarksySpace.Tight)) {
            ReturnRange.entries.forEach { r -> Pill(r.label, selected = r == range, compact = true) { range = r } }
        }
    }
    MarksyCard {
        Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
            if (points.isEmpty()) Text("No closed calls in ${if (range == ReturnRange.ALL) "this period" else range.label}", color = MarksyTheme.TextMuted, style = MarksyType.Small)
            else Canvas(Modifier.fillMaxSize()) {
                val (lo, hi) = ScorecardGraphics.bounds(realised, promised)
                val pad = 6.dp.toPx()
                fun at(p: Pair<Float, Float>) = Offset(p.first * size.width, pad + p.second * (size.height - 2 * pad))
                val zero = at(0f to ScorecardGraphics.chartPoints(listOf(0.0), lo, hi).first().second).y
                drawLine(MarksyTheme.TextMuted, Offset(0f, zero), Offset(size.width, zero), 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx())))
                drawText(measurer, "0%", Offset(2.dp.toPx(), (zero - 15.dp.toPx()).coerceAtLeast(0f)), MarksyType.Caption.copy(color = MarksyTheme.TextMuted))
                val r = ScorecardGraphics.chartPoints(realised, lo, hi).map(::at)
                val pr = ScorecardGraphics.chartPoints(promised, lo, hi).map(::at)
                fun line(pts: List<Offset>) = Path().apply { pts.forEachIndexed { i, o -> if (i == 0) moveTo(o.x, o.y) else lineTo(o.x, o.y) } }
                drawPath(line(pr), MarksyTheme.SecondaryCyan, style = Stroke(2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 5.dp.toPx()))))
                val area = line(r).apply { lineTo(r.last().x, zero); lineTo(r.first().x, zero); close() }
                drawPath(area, tint.copy(alpha = .15f))
                drawPath(line(r), tint, style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                drawCircle(tint, 4.dp.toPx(), r.last())
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(MarksySpace.Gap), verticalAlignment = Alignment.CenterVertically) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(14.dp, 3.dp).background(tint))
                Spacer(Modifier.width(MarksySpace.Inner))
                Text("Avg realised${realised.lastOrNull()?.let { " " + MarksyFormat.percent(it) } ?: ""}", color = MarksyTheme.TextSecondary, style = MarksyType.Meta)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Canvas(Modifier.size(14.dp, 3.dp)) {
                    drawLine(MarksyTheme.SecondaryCyan, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx())))
                }
                Spacer(Modifier.width(MarksySpace.Inner))
                Text("Avg promised${promised.lastOrNull()?.let { " " + MarksyFormat.percent(it) } ?: ""}", color = MarksyTheme.TextSecondary, style = MarksyType.Meta)
            }
            Spacer(Modifier.weight(1f))
            if (calls > 0) Text("$calls closed", color = MarksyTheme.TextMuted, style = MarksyType.Meta)
        }
    }
    }
}

private val HorizonLabels = mapOf("INTRADAY" to "Intra", "UP_TO_1_WEEK" to "≤1W", "UP_TO_1_MONTH" to "≤1M", "LONGER_THAN_1_MONTH" to ">1M")

@Composable
private fun HorizonBars(buckets: List<HorizonHitDto>, modifier: Modifier) {
    MarksyCard(modifier) {
        CardTitle("By horizon")
        Row(Modifier.fillMaxWidth().height(72.dp), horizontalArrangement = Arrangement.spacedBy(MarksySpace.Gap)) {
            buckets.forEach { b ->
                Column(Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(MarksySpace.Tight)) {
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
                        val share = ((b.hitRatePct ?: 0.0) / 100).coerceIn(.04, 1.0).toFloat()
                        Box(Modifier.widthIn(max = 22.dp).fillMaxWidth().fillMaxHeight(share).clip(MarksyShape.Chip).background(ScorecardGraphics.tone(b.hitRatePct)))
                    }
                    Text(HorizonLabels[b.horizon] ?: b.horizon, color = MarksyTheme.TextMuted, style = MarksyType.Caption, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun DeliveredGauge(realizationPct: Double?, modifier: Modifier) {
    val (share, tint) = ScorecardGraphics.gauge(realizationPct)
    MarksyCard(modifier) {
        CardTitle("Delivered vs promised")
        Spacer(Modifier.weight(1f))
        Canvas(Modifier.size(100.dp, 56.dp).align(Alignment.CenterHorizontally)) {
            val w = 10.dp.toPx()
            val d = size.width - w
            val topLeft = Offset(w / 2, w / 2)
            drawArc(MarksyTheme.SurfaceRaised, 180f, 180f, false, topLeft, Size(d, d), style = Stroke(w, cap = StrokeCap.Round))
            if (share > 0f) drawArc(tint, 180f, 180f * share, false, topLeft, Size(d, d), style = Stroke(w, cap = StrokeCap.Round))
        }
        Text(
            realizationPct?.let { MarksyFormat.percent(it, 0, signed = false) } ?: "—", color = if ((realizationPct ?: 0.0) < 0) MarksyTheme.Negative else MarksyTheme.TextPrimary, style = MarksyType.Heading,
            modifier = Modifier.align(Alignment.CenterHorizontally))
    }
}

@Composable
private fun CallRow(t: LedgerTipDto, onClick: () -> Unit) {
    val active = LedgerCalls.isActive(t)
    val dot = when {
        active -> MarksyTheme.SecondaryCyan
        t.outcome == "SUCCESS" -> MarksyTheme.PrimaryEmerald
        t.outcome == "FAILURE" -> MarksyTheme.Negative
        else -> MarksyTheme.TextMuted
    }
    MarksyRowCard(onClick = onClick) {
      Column(Modifier.padding(end = OneHandRowEndClearance), verticalArrangement = Arrangement.spacedBy(MarksySpace.Inner)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(MarksySpace.Gap)) {
            Box(Modifier.size(MarksySize.Dot).clip(CircleShape).background(dot))
            Text(t.symbol, color = MarksyTheme.TextPrimary, style = MarksyType.Subhead, fontWeight = FontWeight.Bold, maxLines = 1)
            t.direction?.let { side ->
                val buy = side == "BUY"
                TagChip(side, if (buy) MarksyTheme.PrimaryEmerald else MarksyTheme.Negative, if (buy) MarksyTheme.BadgeTradingBg else MarksyTheme.BadgeUrgentBg)
            }
            Text("seen ${LedgerCalls.day(t.firstSeenAt)}", color = MarksyTheme.TextMuted, style = MarksyType.Meta, maxLines = 1, modifier = Modifier.weight(1f))
            ReturnBadge(if (active) t.latestProgress?.returnToDate else t.actualReturn, style = MarksyType.Small, waiting = active)
        }
        val entry = ScorecardGraphics.entryPosition(t)
        val now = ScorecardGraphics.levelPosition(t)
        if (entry != null && now != null) Canvas(Modifier.fillMaxWidth().height(14.dp)) {
            val mid = size.height / 2
            val h = 6.dp.toPx()
            val loss = MarksyTheme.Negative.copy(alpha = .35f).compositeOver(MarksyTheme.Surface)
            drawLine(MarksyTheme.PrimaryEmerald.copy(alpha = .35f).compositeOver(MarksyTheme.Surface), Offset(h / 2, mid), Offset(size.width - h / 2, mid), h, StrokeCap.Round)
            drawCircle(loss, h / 2, Offset(h / 2, mid))
            drawLine(loss, Offset(h / 2, mid), Offset((size.width * entry).coerceAtLeast(h / 2), mid), h, StrokeCap.Butt)
            val x = (size.width * now).coerceIn(7.dp.toPx(), size.width - 7.dp.toPx())
            drawCircle(MarksyTheme.Surface, 10.dp.toPx(), Offset(x, mid))
            drawCircle(MarksyTheme.TextPrimary, 7.dp.toPx(), Offset(x, mid))
        }
      }
    }
}

@Composable
private fun CallerRow(c: CallerScorecardDto, onClick: () -> Unit) {
    val h = c.scorecard
    MarksyRowCard(onClick = onClick) {
      Row(
        Modifier.padding(end = OneHandRowEndClearance),
        horizontalArrangement = Arrangement.spacedBy(MarksySpace.Gap), verticalAlignment = Alignment.CenterVertically
      ) {
        TrustRing(h.trustScore, 40.dp, 5.dp, MarksyType.Small, caption = false)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(MarksySpace.Inner)) {
            Text(c.name, color = MarksyTheme.TextPrimary, style = MarksyType.Body, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            OutcomeBar(h.successful, h.failed, h.expired, h.open, height = 4.dp)
        }
        ReturnBadge(h.avgActualReturn, style = MarksyType.Meta)
      }
    }
}
