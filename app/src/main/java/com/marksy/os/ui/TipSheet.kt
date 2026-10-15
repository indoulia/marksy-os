package com.marksy.os.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.marksy.os.MarksyFormat
import com.marksy.os.market.AlsoTippedDto
import com.marksy.os.market.LedgerCalls
import com.marksy.os.market.MarketDataState
import com.marksy.os.market.MarketIntelligenceRepository
import com.marksy.os.market.ProgressPointDto
import com.marksy.os.market.ScorecardSource
import java.time.LocalDate
import java.time.ZoneId

/** One tip in a bottom sheet: its track, the price since it was tipped, prices, scores, other sources and the way out. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TipSheet(
    m: TipCardModel, repository: MarketIntelligenceRepository?, onDismiss: () -> Unit, onTrade: (TradeIntent) -> Unit,
    onOpenStock: (String) -> Unit, onScore: (ScorecardSource) -> Unit
) {
    val t = m.ledger
    val progress by produceState<List<ProgressPointDto>>(emptyList(), t?.tipId) {
        value = t?.tipId?.takeIf { it.isNotEmpty() }?.let { id -> (repository?.tipProgress(id) as? MarketDataState.Loaded)?.value }.orEmpty()
    }
    var daily by remember { mutableStateOf<AlsoTippedDto?>(null) }
    if (t != null && repository != null) daily?.let { o -> DayByDayDialog(repository, t, o) { daily = null } }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = MarksyTheme.Surface) {
        TipSheetContent(m, progress, onTrade, onOpenStock, onScore) { daily = it }
    }
}

@Composable
internal fun TipSheetContent(
    m: TipCardModel, progress: List<ProgressPointDto>, onTrade: (TradeIntent) -> Unit, onOpenStock: (String) -> Unit, onScore: (ScorecardSource) -> Unit,
    onSourceRow: (AlsoTippedDto) -> Unit
) {
    val t = m.ledger
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = MarksySpace.Wide).padding(bottom = MarksySpace.Wide),
        verticalArrangement = Arrangement.spacedBy(MarksySpace.CardPadding)
    ) {
        SheetHeader(m)
        m.track?.let { PriceTrackView(it, backdrop = MarksyTheme.Surface, large = true, block = false) }
        m.track?.let { PriceChart(it, progress) }
        SheetPrices(m, progress)
        if (m.scores.isNotEmpty()) SheetScores(m.scores, onScore)
        if (t != null && t.alsoBy.isNotEmpty()) AlsoTippedBy(t, onRow = onSourceRow)
        t?.let { Timeline(it) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(MarksySpace.Gap)) {
            (m.trade ?: m.track?.takeIf { it.live }?.let { TipTrack.trade(m.symbol, it) })?.let { trade -> MarksyButton("${if (trade.side == TradeSide.SELL) "Sell" else "Buy"} ${m.symbol}", { onTrade(trade) }, Modifier.weight(1f)) }
            MarksyButton("View stock", { onOpenStock(m.symbol) }, Modifier.weight(1f), style = MarksyButtonStyle.Outlined, color = MarksyTheme.TextPrimary)
        }
    }
}

/** The sheet for a tip known only by id; a tip the ledger cannot serve says why in a dialog. */
@Composable
internal fun TipSheetFor(
    repository: MarketIntelligenceRepository, tipId: String, onDismiss: () -> Unit, onTrade: (TradeIntent) -> Unit,
    onOpenStock: (String) -> Unit, onScore: (ScorecardSource) -> Unit
) {
    val detail by produceState<MarketDataState<com.marksy.os.market.TipDetailDto>>(MarketDataState.Loading, tipId) { value = repository.tipDetail(tipId) }
    val t = (detail as? MarketDataState.Loaded)?.value?.ledger
    val quotes = rememberUpstoxQuotes(remember(t?.symbol) { listOfNotNull(t?.symbol) })
    when {
        t != null -> TipSheet(TipCards.ledger(t, quotes[t.symbol]?.lastPrice, LocalDate.now(ZoneId.of("Asia/Kolkata"))), repository, onDismiss, onTrade, onOpenStock, onScore)
        detail is MarketDataState.Loading -> Unit
        else -> MarksyDialog(
            onDismissRequest = onDismiss,
            title = { Text("Tip") },
            text = {
                Text(
                    when (val d = detail) {
                        is MarketDataState.Error -> d.message
                        MarketDataState.Unavailable -> "Sign in to your Marksy account in More."
                        else -> "This tip is not in the ledger yet."
                    }, color = MarksyTheme.TextSecondary
                )
            },
            confirmButton = { MarksyButton("Done", onDismiss, style = MarksyButtonStyle.Text) }
        )
    }
}

@Composable
private fun SheetHeader(m: TipCardModel) {
    Row(verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(MarksySpace.Tight)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(MarksySpace.Gap)) {
                Text(m.symbol, color = MarksyTheme.TextPrimary, style = MarksyType.Title, maxLines = 1)
                SideBadge(m.sell)
                Text(m.status, color = trackColor(m.statusTone), style = MarksyType.Small, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (m.meta.isNotEmpty()) Text(m.meta, color = MarksyTheme.TextMuted, style = MarksyType.Small, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        m.returnFraction?.let { r ->
            Column(horizontalAlignment = Alignment.End) {
                Text(TipTrack.percent(r).orEmpty(), color = trackColor(m.track?.tone ?: if (r >= 0) TrackTone.POSITIVE else TrackTone.NEGATIVE), style = MarksyType.Title)
                Text("from entry", color = MarksyTheme.TextMuted, style = MarksyType.Meta)
            }
        }
    }
}

/** The closes since the tip, between dashed target and stop lines; hidden until the backend sends two closes. */
@Composable
private fun PriceChart(track: PriceTrack, progress: List<ProgressPointDto>) {
    val points = progress.mapNotNull { p -> p.close?.let { p.sessionDate to it } }
    if (points.size < 2) return
    val pulse = rememberPulse(track.live)
    val line = if (track.returnFraction == null) MarksyTheme.TextPrimary else trackColor(track.tone)
    val levels = listOfNotNull(track.stop, track.target, track.recommended, track.entryRef) + points.map { it.second }
    val lo = levels.min(); val hi = levels.max()
    Column(Modifier.fillMaxWidth().clip(MarksyShape.Field).background(MarksyTheme.Background).padding(MarksySpace.ListGap), verticalArrangement = Arrangement.spacedBy(MarksySpace.Tight)) {
        Box(Modifier.fillMaxWidth().height(120.dp)) {
            Canvas(Modifier.fillMaxSize()) {
                val pad = 8.dp.toPx()
                fun y(v: Double) = if (hi == lo) size.height / 2 else (pad + (1 - (v - lo) / (hi - lo)) * (size.height - 2 * pad)).toFloat()
                fun x(i: Int) = size.width * i / (points.size - 1)
                val dash = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx()))
                track.target?.let { drawLine(MarksyTheme.Positive, Offset(0f, y(it)), Offset(size.width, y(it)), 1.dp.toPx(), pathEffect = dash) }
                track.stop?.let { drawLine(MarksyTheme.Negative, Offset(0f, y(it)), Offset(size.width, y(it)), 1.dp.toPx(), pathEffect = dash) }
                track.entryRef?.let { drawLine(MarksyTheme.TextMuted, Offset(0f, y(it)), Offset(size.width, y(it)), 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 4.dp.toPx()))) }
                val path = Path().apply { points.forEachIndexed { i, (_, v) -> if (i == 0) moveTo(x(i), y(v)) else lineTo(x(i), y(v)) } }
                drawPath(path, line, style = Stroke(2.dp.toPx(), join = StrokeJoin.Round))
                val r = 4.5.dp.toPx()
                track.recommended?.let { v ->
                    drawCircle(MarksyTheme.Background, r, Offset(r, y(v)))
                    drawCircle(MarksyTheme.TextPrimary, r, Offset(r, y(v)), style = Stroke(2.dp.toPx()))
                }
                val end = Offset(x(points.size - 1), y(points.last().second))
                pulseRing(pulse.value, end, r, line)
                drawCircle(line, r, end)
            }
            // The labels sit on the chart's fill so the dashed lines pass behind them.
            track.target?.let { Text("T ${MarksyFormat.number(it)}", color = MarksyTheme.Positive, style = MarksyType.Caption, modifier = Modifier.align(Alignment.TopStart).background(MarksyTheme.Background).padding(end = MarksySpace.Tight)) }
            track.stop?.let { Text("SL ${MarksyFormat.number(it)}", color = MarksyTheme.Negative, style = MarksyType.Caption, modifier = Modifier.align(Alignment.BottomStart).background(MarksyTheme.Background).padding(end = MarksySpace.Tight)) }
        }
        val today = LocalDate.now(ZoneId.of("Asia/Kolkata"))
        val days = points.map { (d, _) -> TipTrack.date(d) }
        val shown = listOf(0, points.size / 2, points.size - 1).distinct()
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            shown.forEach { i -> Text(days[i]?.let { if (it == today) "today" else MarksyFormat.day(it) } ?: points[i].first, color = MarksyTheme.TextMuted, style = MarksyType.Caption) }
        }
    }
}

private data class SheetCell(val caption: String, val value: String, val color: Color, val note: String?)

@Composable
private fun SheetPrices(m: TipCardModel, progress: List<ProgressPointDto>) {
    val track = m.track
    val t = m.ledger
    val quotes = rememberUpstoxQuotes(remember(m.symbol) { listOf(m.symbol) })
    val feed = rememberFeedFreshness()
    val now = CallPrice.of(quotes[m.symbol], feed, t?.targetProgress?.price, t?.targetProgress?.priceAt, LocalDate.now(ZoneId.of("Asia/Kolkata")))
    val (best, worst) = TipTrack.extremes(progress)
    val tone = track?.let { trackColor(it.tone) } ?: MarksyTheme.TextPrimary
    val cells = listOfNotNull(
        track?.recommendedText?.let { SheetCell("RECOMMENDED", it, MarksyTheme.TextPrimary, t?.firstSeenAt?.let(TipTrack::dayTime)) },
        track?.entryText?.let { SheetCell("ENTRY", it, MarksyTheme.Warning, if (track.bought) t?.enteredOn?.let(TipTrack::date)?.let { d -> "bought ${MarksyFormat.day(d)}" } else null) },
        track?.currentText?.let { SheetCell("CURRENT", it, if (track.live && track.returnFraction != null) tone else MarksyTheme.TextPrimary, now.label?.takeIf { quotes[m.symbol] != null || track.live }) },
        track?.slot?.let { SheetCell(it.caption, it.text.substringBefore(" · "), if (it.exited) tone else MarksyTheme.TextSecondary, it.note) },
        best?.let { (v, d) -> SheetCell("BEST", TipTrack.percent(v).orEmpty(), MarksyTheme.Positive, d) },
        worst?.let { (v, d) -> SheetCell("WORST", TipTrack.percent(v).orEmpty(), MarksyTheme.Negative, d) }
    )
    CellGrid(cells.map { c -> { CellBox(c.caption, c.value, c.color, c.note) } })
}

@Composable
private fun CellGrid(cells: List<@Composable () -> Unit>) {
    Column(verticalArrangement = Arrangement.spacedBy(MarksySpace.Inner)) {
        cells.chunked(3).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(MarksySpace.Inner)) {
                row.forEach { cell -> Box(Modifier.weight(1f)) { cell() } }
                repeat(3 - row.size) { Box(Modifier.weight(1f)) {} }
            }
        }
    }
}

@Composable
private fun CellBox(caption: String, value: String, color: Color, note: String?, onClick: (() -> Unit)? = null, suffix: String? = null) {
    Column(
        Modifier.fillMaxWidth().clip(MarksyShape.Chip).background(MarksyTheme.SurfaceRaised)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(horizontal = MarksySpace.ListGap, vertical = MarksySpace.Gap),
        verticalArrangement = Arrangement.spacedBy(MarksySpace.Hair)
    ) {
        Text(caption, color = MarksyTheme.TextMuted, style = MarksyType.PriceLabel, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, color = color, style = MarksyType.Subhead, maxLines = 1)
            suffix?.let { Text(it, color = MarksyTheme.TextMuted, style = MarksyType.Meta, maxLines = 1) }
        }
        note?.let { Text(it, color = MarksyTheme.TextMuted, style = MarksyType.Caption, maxLines = 2, overflow = TextOverflow.Ellipsis) }
    }
}

@Composable
private fun SheetScores(scores: List<SheetScore>, onScore: (ScorecardSource) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(MarksySpace.Inner)) {
        Text("SCORES", color = MarksyTheme.TextMuted, style = MarksyType.PriceLabel)
        CellGrid(scores.map { s ->
            { CellBox(s.caption, "${s.score}", trackColor(TipTrack.band(s.score)), s.note, s.source?.let { src -> { onScore(src) } }, suffix = " / 100".takeIf { s.caption.startsWith("MARKSY") }) }
        })
    }
}

@Composable
private fun AlsoTippedBy(t: com.marksy.os.market.LedgerTipDto, onRow: (AlsoTippedDto) -> Unit) {
    Column {
        Text("ALSO TIPPED BY · ${t.alsoBy.size}", color = MarksyTheme.TextMuted, style = MarksyType.PriceLabel, modifier = Modifier.padding(bottom = MarksySpace.Tight))
        t.alsoBy.forEach { o ->
            MarksyDivider()
            Row(Modifier.fillMaxWidth().clickable(onClickLabel = "Day by day") { onRow(o) }.padding(vertical = MarksySpace.Inner), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    listOfNotNull(TipTrack.alsoSource(t, o), TipTrack.alsoWhen(t, o)).joinToString(" · "),
                    color = MarksyTheme.TextPrimary, style = MarksyType.Small, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
                )
                o.recommendedPrice?.let { Text(TipTrack.price(it), color = MarksyTheme.TextSecondary, style = MarksyType.Small, maxLines = 1, modifier = Modifier.padding(start = MarksySpace.Gap)) }
            }
        }
    }
}

/** Recommended → bought → exit, each with its day when the ledger has one. */
@Composable
private fun Timeline(t: com.marksy.os.market.LedgerTipDto) {
    val closed = t.closedAt?.let(TipTrack::date)?.let(MarksyFormat::day)
    val steps = listOfNotNull(
        Triple("Recommended " + LedgerCalls.day(t.firstSeenAt), true, MarksyTheme.TextPrimary).takeIf { t.firstSeenAt.isNotBlank() },
        when {
            TipTrack.voided(t) -> null
            TipTrack.waiting(t) -> Triple("Waiting to buy", false, MarksyTheme.Warning)
            else -> Triple(listOfNotNull("Bought", t.enteredOn?.let(TipTrack::date)?.let(MarksyFormat::day)).joinToString(" "), true, MarksyTheme.Positive)
        },
        when {
            TipTrack.voided(t) -> Triple(listOfNotNull("Voided", closed).joinToString(" "), true, MarksyTheme.TextMuted)
            LedgerCalls.isActive(t) -> Triple("Exit pending", false, MarksyTheme.TextMuted)
            else -> Triple(listOfNotNull("Exited", closed).joinToString(" "), true, MarksyTheme.TextSecondary)
        }
    )
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(MarksySpace.Inner)) {
        steps.forEachIndexed { i, (label, done, color) ->
            if (i > 0) MarksyDivider(Modifier.weight(1f))
            Box(Modifier.size(8.dp).clip(CircleShape).then(if (done) Modifier.background(color) else Modifier.border(MarksySpace.Border, color, CircleShape)))
            Text(label, color = MarksyTheme.TextSecondary, style = MarksyType.Meta, maxLines = 1)
        }
    }
}
