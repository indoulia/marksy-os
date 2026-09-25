package com.marksy.os.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.marksy.os.EmptyState
import com.marksy.os.market.InstrumentLifecycleDto
import com.marksy.os.market.InstrumentPredictionEntryDto
import com.marksy.os.market.MarketDataState
import com.marksy.os.upstox.Candle
import com.marksy.os.upstox.ChartRange
import com.marksy.os.upstox.DepthLevel
import com.marksy.os.upstox.UpstoxQuote
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Upstox data for the page; [candles] null means still loading, [note] explains missing live data. */
data class StockLive(
    val quote: UpstoxQuote? = null,
    val candles: List<Candle>? = null,
    val yearRange: Pair<Double, Double>? = null,
    val returns: Map<String, Double> = emptyMap(),
    val averageVolume: Long? = null,
    val isin: String? = null,
    val note: String? = null,
    val streaming: Boolean = false
)

@Composable
fun StockDetailScreen(
    state: MarketDataState<InstrumentLifecycleDto>,
    padding: PaddingValues,
    symbol: String? = null,
    live: StockLive = StockLive(),
    range: ChartRange = ChartRange.D1,
    onRangeSelected: (ChartRange) -> Unit = {},
    mentions: List<com.marksy.os.data.local.NotificationEventEntity> = emptyList(),
    onEventSelected: (com.marksy.os.data.local.NotificationEventEntity) -> Unit = {}
) {
    val instrument = (state as? MarketDataState.Loaded)?.value ?: (state as? MarketDataState.Stale)?.value
    LazyColumn(
        modifier = Modifier.fillMaxSize().background(MarksyTheme.Background).padding(horizontal = 18.dp),
        contentPadding = PaddingValues(top = 12.dp, bottom = padding.calculateBottomPadding() + 20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item { PriceHeader(instrument, symbol, live) }
        live.note?.let { note -> item { Text(note, color = MarksyTheme.TextMuted, fontSize = 11.sp) } }
        if (live.quote != null || live.candles != null) item { ChartCard(live, range, onRangeSelected) }
        live.quote?.let { q ->
            item { StatsCard(q, live) }
            if (q.bids.isNotEmpty() || q.asks.isNotEmpty()) item { DepthCard(q) }
        }
        if (mentions.isNotEmpty()) {
            item { Text("In your notifications", color = MarksyTheme.TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }
            items(mentions.take(5), key = { "mention-${it.id}" }) { e ->
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MarksyTheme.Surface).clickable { onEventSelected(e) }.padding(10.dp)
                ) {
                    Text(e.title.ifBlank { e.sourceName }, color = MarksyTheme.TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                    Text(e.body, color = MarksyTheme.TextSecondary, fontSize = 12.sp, maxLines = 2)
                    Text("${e.sourceName} · ${SimpleDateFormat("d MMM, HH:mm", Locale.getDefault()).format(Date(e.postedAt))}", color = MarksyTheme.TextMuted, fontSize = 10.sp)
                }
            }
        }
        item { Text("Marksy", color = MarksyTheme.TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }
        when (state) {
            is MarketDataState.Loading -> item { MarksyLoader("Checking instrument...") }
            is MarketDataState.Unavailable -> item { EmptyState("Market Intelligence is not configured", "Add a Market API key in More → Configure Gateway.") }
            is MarketDataState.Error -> item { EmptyState("Instrument unavailable", state.message) }
            is MarketDataState.Empty -> item { EmptyState("Not found", "Marksy doesn't track this symbol yet.") }
            is MarketDataState.Loaded -> marksyContent(state.value)
            is MarketDataState.Stale -> marksyContent(state.value)
        }
    }
}

@Composable
private fun PriceHeader(instrument: InstrumentLifecycleDto?, symbol: String?, live: StockLive) {
    Column {
        Text(instrument?.companyName ?: symbol ?: instrument?.symbol.orEmpty(), color = MarksyTheme.TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Text(listOfNotNull(instrument?.symbol ?: symbol, instrument?.exchange?.ifBlank { null }, instrument?.sector, live.isin?.let { "ISIN $it" }).joinToString(" · "), color = MarksyTheme.TextSecondary, fontSize = 12.sp)
        val q = live.quote
        val price = q?.lastPrice ?: instrument?.market?.lastClosePrice
        if (price != null) Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.Bottom) {
            Text("₹${money(price)}", color = MarksyTheme.TextPrimary, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(8.dp))
            val change = q?.change
            val pct = q?.changePct
            if (change != null && pct != null) {
                Text(
                    "${if (change >= 0) "+" else ""}${money(change)} (${String.format(Locale.US, "%+.2f%%", pct)})",
                    color = if (change >= 0) MarksyTheme.PrimaryEmerald else MarksyTheme.RedUrgent, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(bottom = 3.dp)
                )
            } else if (q == null) {
                Text("last close", color = MarksyTheme.TextMuted, fontSize = 12.sp, modifier = Modifier.padding(bottom = 4.dp))
            }
            Spacer(Modifier.weight(1f))
            if (live.streaming) Text(
                "LIVE", color = MarksyTheme.PrimaryEmerald, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 4.dp).clip(RoundedCornerShape(8.dp)).background(MarksyTheme.BadgeTradingBg).padding(horizontal = 6.dp, vertical = 2.dp)
            )
        }
        q?.lastTradeTime?.let { Text("Last trade ${SimpleDateFormat("d MMM, HH:mm", Locale.getDefault()).format(Date(it))}", color = MarksyTheme.TextMuted, fontSize = 10.sp) }
    }
}

@Composable
private fun ChartCard(live: StockLive, range: ChartRange, onRangeSelected: (ChartRange) -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(MarksyTheme.Surface).border(1.dp, MarksyTheme.BorderGlow, RoundedCornerShape(14.dp)).padding(12.dp)) {
        val candles = live.candles
        val first = candles?.firstOrNull()?.open
        val last = candles?.lastOrNull()?.close
        // 1D is measured from the previous close, as brokers show it; longer ranges from the range's first open.
        val pct = if (range == ChartRange.D1 && live.quote?.changePct != null) live.quote.changePct
            else if (first != null && last != null && first > 0) (last - first) / first * 100 else null
        val up = (pct ?: live.quote?.change ?: 0.0) >= 0
        val day = SimpleDateFormat("d MMM", Locale.getDefault())
        // After hours the intraday feed can be empty and history may lag a session; say which day the chart is.
        val session = candles?.lastOrNull()?.time?.let { day.format(Date(it)) }
        val stale = range == ChartRange.D1 && session != null && live.quote?.lastTradeTime?.let { day.format(Date(it)) } != session
        pct?.let {
            Text("${String.format(Locale.US, "%+.2f%%", it)} ${if (range == ChartRange.D1) "today" else "in ${range.label}"}",
                color = if (up) MarksyTheme.PrimaryEmerald else MarksyTheme.RedUrgent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
        if (stale) Text("Chart shows the $session session", color = MarksyTheme.TextMuted, fontSize = 10.sp)
        Box(Modifier.fillMaxWidth().height(180.dp).padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
            when {
                candles == null -> MarksyLoader("Loading chart...")
                candles.size < 2 -> Text("No chart data for ${range.label}", color = MarksyTheme.TextMuted, fontSize = 12.sp)
                else -> PriceLine(candles, if (up) MarksyTheme.PrimaryEmerald else MarksyTheme.RedUrgent)
            }
        }
        candles?.takeIf { it.isNotEmpty() }?.let { c ->
            Text("Range low ₹${money(c.minOf { it.low })} · high ₹${money(c.maxOf { it.high })}", color = MarksyTheme.TextMuted, fontSize = 10.sp)
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            ChartRange.entries.forEach { r ->
                val selected = r == range
                Text(
                    r.label, color = if (selected) Color.Black else MarksyTheme.TextSecondary, fontSize = 12.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                    modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(if (selected) MarksyTheme.PrimaryEmerald else MarksyTheme.SurfaceRaised)
                        .clickable { onRangeSelected(r) }.padding(horizontal = 14.dp, vertical = 5.dp)
                )
            }
        }
    }
}

@Composable
private fun PriceLine(candles: List<Candle>, color: Color) {
    Canvas(Modifier.fillMaxSize()) {
        val closes = candles.map { it.close }
        val lo = closes.min()
        val span = (closes.max() - lo).takeIf { it > 0 } ?: 1.0
        val step = size.width / (closes.size - 1)
        fun y(v: Double) = (size.height * (1 - (v - lo) / span)).toFloat()
        val line = Path().apply { closes.forEachIndexed { i, v -> if (i == 0) moveTo(0f, y(v)) else lineTo(i * step, y(v)) } }
        val fill = Path().apply { addPath(line); lineTo(size.width, size.height); lineTo(0f, size.height); close() }
        drawPath(fill, Brush.verticalGradient(listOf(color.copy(alpha = .25f), Color.Transparent)))
        drawPath(line, color, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
        drawCircle(color, 3.dp.toPx(), Offset(size.width, y(closes.last())))
    }
}

@Composable
private fun StatsCard(q: UpstoxQuote, live: StockLive) {
    val year = live.yearRange
    val stats = listOf(
        "Open" to q.open?.let(::money), "Prev. close" to q.prevClose?.let(::money),
        "Day high" to q.high?.let(::money), "Day low" to q.low?.let(::money),
        "52W high" to year?.second?.let(::money), "52W low" to year?.first?.let(::money),
        "Volume" to q.volume?.let(::count), "Avg. price" to q.averagePrice?.let(::money),
        "Upper circuit" to q.upperCircuit?.let(::money), "Lower circuit" to q.lowerCircuit?.let(::money),
        "Avg. volume (20D)" to live.averageVolume?.let(::count),
        "From 52W high" to year?.second?.takeIf { it > 0 }?.let { String.format(Locale.US, "%+.1f%%", (q.lastPrice - it) / it * 100) }
    ).filter { it.second != null }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(MarksyTheme.Surface).border(1.dp, MarksyTheme.BorderGlow, RoundedCornerShape(14.dp)).padding(12.dp)) {
        if (live.returns.isNotEmpty()) Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            live.returns.forEach { (label, pct) ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(label, color = MarksyTheme.TextMuted, fontSize = 10.sp)
                    Text(String.format(Locale.US, "%+.1f%%", pct), color = if (pct >= 0) MarksyTheme.PrimaryEmerald else MarksyTheme.RedUrgent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        stats.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                row.forEach { (label, value) ->
                    Column(Modifier.weight(1f)) {
                        Text(label, color = MarksyTheme.TextMuted, fontSize = 11.sp)
                        Text(value!!, color = MarksyTheme.TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
        if (q.low != null && q.high != null) RangeBar("Day range", q.low, q.high, q.lastPrice)
        year?.let { (lo, hi) -> RangeBar("52-week range", lo, hi, q.lastPrice) }
    }
}

@Composable
private fun RangeBar(label: String, low: Double, high: Double, price: Double) {
    Column(Modifier.fillMaxWidth().padding(top = 10.dp)) {
        Text(label, color = MarksyTheme.TextMuted, fontSize = 11.sp)
        val at = if (high > low) ((price - low) / (high - low)).coerceIn(0.0, 1.0).toFloat() else .5f
        Canvas(Modifier.fillMaxWidth().height(14.dp)) {
            val mid = size.height / 2
            drawLine(Color(0x33FFFFFF), Offset(0f, mid), Offset(size.width, mid), 4.dp.toPx(), StrokeCap.Round)
            drawLine(MarksyTheme.PrimaryEmerald, Offset(0f, mid), Offset(size.width * at, mid), 4.dp.toPx(), StrokeCap.Round)
            drawCircle(Color.White, 5.dp.toPx(), Offset(size.width * at, mid))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("₹${money(low)}", color = MarksyTheme.TextSecondary, fontSize = 11.sp)
            Text("₹${money(high)}", color = MarksyTheme.TextSecondary, fontSize = 11.sp)
        }
    }
}

@Composable
private fun DepthCard(q: UpstoxQuote) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(MarksyTheme.Surface).border(1.dp, MarksyTheme.BorderGlow, RoundedCornerShape(14.dp)).padding(12.dp)) {
        Text("Market depth", color = MarksyTheme.TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
            DepthSide("Bid", q.bids, MarksyTheme.PrimaryEmerald, Modifier.weight(1f))
            Spacer(Modifier.width(12.dp))
            DepthSide("Ask", q.asks, MarksyTheme.RedUrgent, Modifier.weight(1f))
        }
        val buy = q.totalBuyQty
        val sell = q.totalSellQty
        if (buy != null && sell != null && buy + sell > 0) {
            val share = buy.toFloat() / (buy + sell)
            Row(Modifier.fillMaxWidth().padding(top = 10.dp).height(6.dp).clip(RoundedCornerShape(3.dp))) {
                Box(Modifier.weight(share.coerceAtLeast(.01f)).fillMaxHeight().background(MarksyTheme.PrimaryEmerald))
                Box(Modifier.weight((1 - share).coerceAtLeast(.01f)).fillMaxHeight().background(MarksyTheme.RedUrgent))
            }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Buy ${count(buy)} (${(share * 100).toInt()}%)", color = MarksyTheme.TextSecondary, fontSize = 11.sp)
                Text("Sell ${count(sell)}", color = MarksyTheme.TextSecondary, fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun DepthSide(title: String, levels: List<DepthLevel>, tint: Color, modifier: Modifier) {
    Column(modifier) {
        Row(Modifier.fillMaxWidth()) {
            Text(title, color = MarksyTheme.TextMuted, fontSize = 10.sp, modifier = Modifier.weight(1f))
            Text("Orders", color = MarksyTheme.TextMuted, fontSize = 10.sp, modifier = Modifier.weight(1f))
            Text("Qty", color = MarksyTheme.TextMuted, fontSize = 10.sp)
        }
        levels.take(5).forEach { l ->
            Row(Modifier.fillMaxWidth().padding(top = 3.dp)) {
                Text(money(l.price), color = tint, fontSize = 12.sp, modifier = Modifier.weight(1f))
                Text(l.orders.toString(), color = MarksyTheme.TextSecondary, fontSize = 12.sp, modifier = Modifier.weight(1f))
                Text(count(l.quantity), color = MarksyTheme.TextPrimary, fontSize = 12.sp)
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.marksyContent(instrument: InstrumentLifecycleDto) {
    item {
        val market = instrument.market
        val priceLine = if (market.lastClosePrice != null) "Last close: ${market.lastClosePrice}" else "Last close: unavailable"
        val freshnessDetails = listOfNotNull(market.asOfSessionDate, market.freshnessState)
        val line = if (freshnessDetails.isNotEmpty()) "$priceLine (${freshnessDetails.joinToString(", ")})" else priceLine
        Text(line, color = MarksyTheme.TextSecondary, fontSize = 13.sp)
    }
    if (instrument.predictions.isEmpty()) {
        item { EmptyState("No predictions yet", "Marksy has not published a prediction for this instrument.") }
    } else {
        items(instrument.predictions) { prediction -> PredictionHistoryRow(prediction) }
    }
}

@Composable
private fun PredictionHistoryRow(prediction: InstrumentPredictionEntryDto) {
    Column(Modifier.fillMaxWidth().border(1.dp, MarksyTheme.BorderGlow, RoundedCornerShape(12.dp)).padding(10.dp)) {
        Text(prediction.lifecycleState, color = MarksyTheme.PrimaryEmerald, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Text(prediction.lifecycleDetail, color = MarksyTheme.TextSecondary, fontSize = 12.sp)
        Text("Entry ${prediction.entryPrice} · Target ${prediction.targetPrice ?: "-"} · Stop ${prediction.stopLoss ?: "-"}", color = MarksyTheme.TextMuted, fontSize = 11.sp)
        Text("${prediction.evidenceItemCount} evidence item(s) recorded", color = MarksyTheme.TextMuted, fontSize = 11.sp)
    }
}

/** Indian grouping, two decimals: 1234567.5 -> "12,34,567.50". */
internal fun money(v: Double): String {
    val negative = v < 0
    val cents = Math.round(kotlin.math.abs(v) * 100)
    return (if (negative) "-" else "") + indian(cents / 100) + "." + (cents % 100).toString().padStart(2, '0')
}

internal fun count(v: Long): String = indian(v)

private fun indian(n: Long): String {
    val s = n.toString()
    if (s.length <= 3) return s
    val head = s.dropLast(3)
    return head.reversed().chunked(2).joinToString(",").reversed() + "," + s.takeLast(3)
}
