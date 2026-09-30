package com.marksy.os.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.marksy.os.EmptyState
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
import java.util.Locale

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
    LazyColumn(
        Modifier.fillMaxSize().background(MarksyTheme.Background).padding(horizontal = 18.dp),
        contentPadding = PaddingValues(top = 10.dp, bottom = bottomPadding),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
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
                    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        HorizonBars(d.byHorizon, Modifier.weight(1f).fillMaxHeight())
                        DeliveredGauge(d.card.body.performance.returnRealizationPct, Modifier.weight(1f).fillMaxHeight())
                    }
                }
                if (d.recent.isNotEmpty()) item(key = "recent") { RecentCalls(d.recent) { openTip = it } }
                if (d.topSymbols.isNotEmpty()) item(key = "symbols") {
                    Column(Modifier.scoreCard(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        CardTitle("Most called")
                        // One 48dp-tall scrolling row is the touch target, so each chip stays tight.
                        LazyRow(Modifier.fillMaxWidth().height(48.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            items(d.topSymbols, key = { it.symbol }) { s -> SymbolChip(s) { onOpenStock(s.symbol) } }
                        }
                    }
                }
                d.callers?.takeIf { it.isNotEmpty() }?.let { callers ->
                    item(key = "callers") {
                        Column(Modifier.scoreCard(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            CardTitle("Callers")
                            callers.forEach { c -> CallerRow(c) { onOpenSource(ScorecardSource.caller(c)) } }
                        }
                    }
                }
            }
            is MarketDataState.Unavailable -> item { EmptyState("Marksy is not connected", "Sign in to your Marksy account in More.") }
            is MarketDataState.Error -> item { EmptyState("Scorecard unavailable", state.message) }
            else -> item { MarksyLoader("Loading ${source.name}...") }
        }
    }
}

@Composable
private fun CardTitle(text: String) = Text(text, color = MarksyTheme.TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)

@Composable
private fun Hero(d: ScorecardDetailDto, name: String, following: Boolean, onToggleFollow: (Boolean) -> Unit) {
    val since = d.card.filter.startDate?.let { runCatching { "since ${ScorecardText.date(LocalDate.parse(it))}" }.getOrNull() }
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        TrustRing(d.card.body.trust.trustScore, 120.dp, 12.dp, 40.sp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Column {
                Text(name, color = MarksyTheme.TextPrimary, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 26.sp)
                Text(listOfNotNull(ScorecardSources.kind(d), since).joinToString(" · "), color = MarksyTheme.TextSecondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            HitRateBandBar(d.trustBand, d.card.body.performance.hitRatePct, d.card.body.trust)
            FollowPill(following, onToggleFollow)
        }
    }
}

@Composable
private fun HitRateBandBar(band: TrustBandDto?, hitRatePct: Double?, trust: ScorecardTrustDto) {
    val bar = ScorecardGraphics.hitRateBand(band, hitRatePct)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
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
                Text("0", color = MarksyTheme.TextMuted, fontSize = 10.sp)
                Text(ScorecardGraphics.hitRateRangeText(band), color = MarksyTheme.TextMuted, fontSize = 10.sp)
                Text("100", color = MarksyTheme.TextMuted, fontSize = 10.sp)
            } else Text("${trust.completed}/${trust.minimumCompleted} completed for a hit-rate range", color = MarksyTheme.TextMuted, fontSize = 10.sp, maxLines = 1)
        }
    }
}

@Composable
private fun KpiTiles(d: ScorecardDetailDto) {
    val p = d.card.body.performance
    val c = d.card.body.counts
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            KpiTile(p.hitRatePct?.let { String.format(Locale.US, "%.1f%%", it) } ?: "—", "Hit rate", Modifier.weight(1f).fillMaxHeight()) {
                ValueRing(ScorecardGraphics.ringFraction(p.hitRatePct), ScorecardGraphics.tone(p.hitRatePct), 48.dp, 6.dp, dashed = p.hitRatePct == null)
            }
            val ret = p.avgActualReturn
            KpiTile(LedgerCalls.returnText(ret) ?: "—", "Avg return", Modifier.weight(1f).fillMaxHeight(), ScorecardGraphics.returnTone(ret).takeIf { ret != null }) {
                IconDisc(
                    if ((ret ?: 0.0) < 0) Icons.AutoMirrored.Filled.TrendingDown else Icons.AutoMirrored.Filled.TrendingUp, ScorecardGraphics.returnTone(ret),
                    when { ret == null -> MarksyTheme.SurfaceRaised; ret < 0 -> MarksyTheme.BadgeUrgentBg; else -> MarksyTheme.BadgeTradingBg }
                )
            }
        }
        Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.weight(1f).fillMaxHeight().scoreCard(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("${c.completed}", color = MarksyTheme.TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
                    Text("/${c.total}", color = MarksyTheme.TextMuted, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                }
                val done = ScorecardGraphics.shares(listOf(c.completed, (c.total - c.completed).coerceAtLeast(0))).first()
                Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(MarksyTheme.SurfaceRaised)) {
                    if (done > 0f) Box(Modifier.fillMaxWidth(done).fillMaxHeight().background(MarksyTheme.SecondaryCyan))
                }
                Text("Completed", color = MarksyTheme.TextSecondary, fontSize = 11.sp)
            }
            KpiTile(p.avgDaysToCompletion?.let { String.format(Locale.US, "%.1fd", it) } ?: "—", "To outcome", Modifier.weight(1f).fillMaxHeight()) {
                IconDisc(Icons.Default.Schedule, MarksyTheme.BlueFinance, MarksyTheme.BadgeFinanceBg)
            }
        }
    }
}

@Composable
private fun KpiTile(value: String, label: String, modifier: Modifier, valueColor: Color? = null, graphic: @Composable () -> Unit) {
    Row(modifier.scoreCard(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        graphic()
        Column {
            Text(value, color = valueColor ?: MarksyTheme.TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace, maxLines = 1)
            Text(label, color = MarksyTheme.TextSecondary, fontSize = 11.sp, maxLines = 1)
        }
    }
}

@Composable
private fun IconDisc(icon: ImageVector, tint: Color, fill: Color) {
    Box(Modifier.size(48.dp).clip(CircleShape).background(fill), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
    }
}

@Composable
private fun OutcomeDonut(d: ScorecardDetailDto) {
    val c = d.card.body.counts
    val parts = listOf(
        Triple("Target hit", c.successful, MarksyTheme.PrimaryEmerald), Triple("Stop hit", c.failed, MarksyTheme.RedUrgent),
        Triple("Expired", c.expired, MarksyTheme.TextMuted), Triple("Open", c.open, MarksyTheme.SecondaryCyan),
        Triple("Invalidated", c.invalidated, MarksyTheme.TextMuted.copy(alpha = .4f))
    )
    val segments = ScorecardGraphics.donut(parts.map { it.second })
    Row(Modifier.scoreCard(), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(132.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val w = 18.dp.toPx()
                val topLeft = Offset(w / 2, w / 2)
                val arc = Size(size.width - w, size.height - w)
                drawArc(MarksyTheme.SurfaceRaised, 0f, 360f, false, topLeft, arc, style = Stroke(w))
                segments.forEachIndexed { i, (start, sweep) ->
                    if (sweep > 0f) drawArc(parts[i].third, -90f + start * 360f, sweep * 360f, false, topLeft, arc, style = Stroke(w))
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("${c.total}", color = MarksyTheme.TextPrimary, fontSize = 24.sp, fontWeight = FontWeight.Black, lineHeight = 26.sp)
                Text("CALLS", color = MarksyTheme.TextSecondary, fontSize = 9.sp, letterSpacing = 1.sp)
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            parts.forEach { (label, n, color) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(color))
                    Spacer(Modifier.width(8.dp))
                    Text(label, color = MarksyTheme.TextSecondary, fontSize = 13.sp, modifier = Modifier.weight(1f), maxLines = 1)
                    Text("$n", color = MarksyTheme.TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium, fontFamily = FontFamily.Monospace)
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
    Column(Modifier.scoreCard(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Returns", color = MarksyTheme.TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            ReturnRange.entries.forEach { r ->
                val on = r == range
                val fill by androidx.compose.animation.animateColorAsState(if (on) MarksyTheme.PrimaryEmerald else Color.Transparent, label = "range")
                Box(Modifier.clip(RoundedCornerShape(10.dp)).background(fill).clickable { range = r }.padding(horizontal = 7.dp, vertical = 4.dp)) {
                    Text(r.label, color = if (on) Color.Black else MarksyTheme.TextSecondary, fontSize = 11.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Medium)
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) {
            if (points.isEmpty()) Text("No closed calls in ${if (range == ReturnRange.ALL) "this period" else range.label}", color = MarksyTheme.TextMuted, fontSize = 12.sp)
            else Canvas(Modifier.fillMaxSize()) {
                val (lo, hi) = ScorecardGraphics.bounds(realised, promised)
                val pad = 6.dp.toPx()
                fun at(p: Pair<Float, Float>) = Offset(p.first * size.width, pad + p.second * (size.height - 2 * pad))
                val zero = at(0f to ScorecardGraphics.chartPoints(listOf(0.0), lo, hi).first().second).y
                drawLine(MarksyTheme.TextMuted, Offset(0f, zero), Offset(size.width, zero), 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx())))
                drawText(measurer, "0%", Offset(2.dp.toPx(), (zero - 15.dp.toPx()).coerceAtLeast(0f)), TextStyle(color = MarksyTheme.TextMuted, fontSize = 10.sp))
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
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(14.dp, 3.dp).background(tint))
                Spacer(Modifier.width(6.dp))
                Text("Avg realised${realised.lastOrNull()?.let { String.format(Locale.US, " %+.2f%%", it) } ?: ""}", color = MarksyTheme.TextSecondary, fontSize = 11.sp)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Canvas(Modifier.size(14.dp, 3.dp)) {
                    drawLine(MarksyTheme.SecondaryCyan, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx())))
                }
                Spacer(Modifier.width(6.dp))
                Text("Avg promised${promised.lastOrNull()?.let { String.format(Locale.US, " %+.2f%%", it) } ?: ""}", color = MarksyTheme.TextSecondary, fontSize = 11.sp)
            }
            Spacer(Modifier.weight(1f))
            if (calls > 0) Text("$calls closed", color = MarksyTheme.TextMuted, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
        }
    }
}

private val HorizonLabels = mapOf("INTRADAY" to "Intra", "UP_TO_1_WEEK" to "≤1W", "UP_TO_1_MONTH" to "≤1M", "LONGER_THAN_1_MONTH" to ">1M")

@Composable
private fun HorizonBars(buckets: List<HorizonHitDto>, modifier: Modifier) {
    Column(modifier.scoreCard(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        CardTitle("By horizon")
        Row(Modifier.fillMaxWidth().height(96.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            buckets.forEach { b ->
                Column(Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
                        val share = ((b.hitRatePct ?: 0.0) / 100).coerceIn(.04, 1.0).toFloat()
                        Box(Modifier.widthIn(max = 22.dp).fillMaxWidth().fillMaxHeight(share).clip(RoundedCornerShape(6.dp)).background(ScorecardGraphics.tone(b.hitRatePct)))
                    }
                    Text(HorizonLabels[b.horizon] ?: b.horizon, color = MarksyTheme.TextMuted, fontSize = 9.sp, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun DeliveredGauge(realizationPct: Double?, modifier: Modifier) {
    val (share, tint) = ScorecardGraphics.gauge(realizationPct)
    Column(modifier.scoreCard(), verticalArrangement = Arrangement.spacedBy(6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Delivered vs promised", color = MarksyTheme.TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.align(Alignment.Start))
        Spacer(Modifier.weight(1f))
        Canvas(Modifier.size(120.dp, 66.dp)) {
            val w = 12.dp.toPx()
            val d = size.width - w
            val topLeft = Offset(w / 2, w / 2)
            drawArc(MarksyTheme.SurfaceRaised, 180f, 180f, false, topLeft, Size(d, d), style = Stroke(w, cap = StrokeCap.Round))
            if (share > 0f) drawArc(tint, 180f, 180f * share, false, topLeft, Size(d, d), style = Stroke(w, cap = StrokeCap.Round))
        }
        Text(
            realizationPct?.let { String.format(Locale.US, "%.0f%%", it) } ?: "—", color = if ((realizationPct ?: 0.0) < 0) MarksyTheme.RedUrgent else MarksyTheme.TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun RecentCalls(recent: List<LedgerTipDto>, onOpen: (String) -> Unit) {
    var all by remember(recent) { mutableStateOf(false) }
    Column(Modifier.scoreCard(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CardTitle("Recent calls")
            Spacer(Modifier.weight(1f))
            if (recent.size > RECENT_PREVIEW) Text(
                if (all) "Show less" else "Show all recent", color = MarksyTheme.PrimaryEmerald, fontSize = 12.sp,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { all = !all }.padding(horizontal = 4.dp, vertical = 2.dp)
            )
        }
        (if (all) recent else recent.take(RECENT_PREVIEW)).forEach { t -> CallRow(t) { onOpen(t.tipId) } }
    }
}

@Composable
private fun CallRow(t: LedgerTipDto, onClick: () -> Unit) {
    val active = LedgerCalls.isActive(t)
    val dot = when {
        active -> MarksyTheme.SecondaryCyan
        t.outcome == "SUCCESS" -> MarksyTheme.PrimaryEmerald
        t.outcome == "FAILURE" -> MarksyTheme.RedUrgent
        else -> MarksyTheme.TextMuted
    }
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(end = OneHandRowEndClearance), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(dot))
            Text(t.symbol, color = MarksyTheme.TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            t.direction?.let { side ->
                val buy = side == "BUY"
                TagChip(side, if (buy) MarksyTheme.PrimaryEmerald else MarksyTheme.RedUrgent, if (buy) MarksyTheme.BadgeTradingBg else MarksyTheme.BadgeUrgentBg)
            }
            Text("seen ${LedgerCalls.day(t.firstSeenAt)}", color = MarksyTheme.TextMuted, fontSize = 11.sp, maxLines = 1, modifier = Modifier.weight(1f))
            ReturnBadge(if (active) t.latestProgress?.returnToDate else t.actualReturn, fontSize = 12.sp, waiting = active)
        }
        val entry = ScorecardGraphics.entryPosition(t)
        val now = ScorecardGraphics.levelPosition(t)
        if (entry != null && now != null) Canvas(Modifier.fillMaxWidth().height(14.dp)) {
            val mid = size.height / 2
            val h = 6.dp.toPx()
            val loss = MarksyTheme.RedUrgent.copy(alpha = .35f).compositeOver(MarksyTheme.Surface)
            drawLine(MarksyTheme.PrimaryEmerald.copy(alpha = .35f).compositeOver(MarksyTheme.Surface), Offset(h / 2, mid), Offset(size.width - h / 2, mid), h, StrokeCap.Round)
            drawCircle(loss, h / 2, Offset(h / 2, mid))
            drawLine(loss, Offset(h / 2, mid), Offset((size.width * entry).coerceAtLeast(h / 2), mid), h, StrokeCap.Butt)
            val x = (size.width * now).coerceIn(7.dp.toPx(), size.width - 7.dp.toPx())
            drawCircle(MarksyTheme.Surface, 10.dp.toPx(), Offset(x, mid))
            drawCircle(MarksyTheme.TextPrimary, 7.dp.toPx(), Offset(x, mid))
        }
    }
}

@Composable
private fun SymbolChip(s: TopSymbolDto, onClick: () -> Unit) {
    val r = s.avgActualReturn
    val (fg, bg, edge) = when {
        r == null || r == 0.0 -> Triple(MarksyTheme.TextSecondary, MarksyTheme.SurfaceRaised, MarksyTheme.BorderGlow)
        r > 0 -> Triple(MarksyTheme.PrimaryEmerald, MarksyTheme.BadgeTradingBg, MarksyTheme.BorderGlow)
        else -> Triple(MarksyTheme.RedUrgent, MarksyTheme.BadgeUrgentBg, MarksyTheme.RedUrgent.copy(alpha = .3f))
    }
    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier.clip(shape).background(bg).border(1.dp, edge, shape).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(s.symbol, color = fg, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        Text(
            "${s.calls}", color = fg, fontSize = 9.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, lineHeight = 10.sp,
            modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(fg.copy(alpha = .16f)).padding(horizontal = 5.dp, vertical = 1.dp)
        )
    }
}

@Composable
private fun CallerRow(c: CallerScorecardDto, onClick: () -> Unit) {
    val h = c.scorecard
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(end = OneHandRowEndClearance),
        horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically
    ) {
        TrustRing(h.trustScore, 36.dp, 4.dp, 12.sp, caption = false)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(c.name, color = MarksyTheme.TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            OutcomeBar(h.successful, h.failed, h.expired, h.open, height = 4.dp)
        }
        ReturnBadge(h.avgActualReturn, fontSize = 11.sp)
    }
}
