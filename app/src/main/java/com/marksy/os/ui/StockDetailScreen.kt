package com.marksy.os.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.marksy.os.market.InstrumentLifecycleDto
import com.marksy.os.market.MarksyCallView
import com.marksy.os.market.MarksyCalls
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
    val streaming: Boolean = false,
    val daily: List<Candle> = emptyList(),
    val key: String? = null,
    val monthly: List<Candle> = emptyList(),
    val index: List<Candle> = emptyList()
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
    onEventSelected: (com.marksy.os.data.local.NotificationEventEntity) -> Unit = {},
    fundamentals: StockFundamentals = StockFundamentals(),
    onOpenSymbol: (String) -> Unit = {},
    analysis: org.json.JSONObject? = null,
    ratingSource: com.marksy.os.rating.RatingSource = com.marksy.os.rating.LocalRatingSource
) {
    val instrument = (state as? MarketDataState.Loaded)?.value ?: (state as? MarketDataState.Stale)?.value
    val ist = java.time.ZoneId.of("Asia/Kolkata")
    val ratingInputs = remember(live, fundamentals, instrument) { StockRatingInputs.from(live, fundamentals, instrument?.predictions, java.time.LocalDate.now(ist), ist) }
    val rating by androidx.compose.runtime.produceState<com.marksy.os.rating.RatingResult?>(null, ratingInputs) { value = ratingSource.rating(symbol.orEmpty(), ratingInputs) }
    // A new symbol (e.g. a tapped peer) opens at its header, not at the previous stock's scroll position.
    val listState = rememberSaveable(symbol, saver = LazyListState.Saver) { LazyListState() }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().background(MarksyTheme.Background).padding(horizontal = 18.dp),
        contentPadding = PaddingValues(top = 0.dp, bottom = padding.calculateBottomPadding() + 20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item { PriceHeader(instrument, symbol, live) }
        val calls = instrument?.let { MarksyCalls.view(it.predictions) } ?: MarksyCallView.None
        if (calls != MarksyCallView.None) item { MarksyCallCard(calls, live.quote?.lastPrice, analysis) }
        rating?.let { r -> item { MarksyRatingCard(r) } }
        live.note?.let { note -> item { Text(note, color = MarksyTheme.TextMuted, fontSize = 11.sp) } }
        val levels = (calls as? MarksyCallView.Active)?.primary?.let { p -> listOfNotNull(p.targetPrice?.let { "Target" to it }, "Entry" to p.entryPrice, p.stopLoss?.let { "Stop" to it }) }.orEmpty()
        if (live.quote != null || live.candles != null) item { ChartCard(live, range, onRangeSelected, levels) }
        live.quote?.let { q ->
            item { StatsCard(q, live) }
            item { TechnicalCard(live.daily, q.lastPrice) }
            if (live.monthly.size >= 13) item { SeasonalityCard(live.monthly, symbol ?: instrument?.symbol.orEmpty()) }
            if (q.bids.isNotEmpty() || q.asks.isNotEmpty()) item { DepthCard(q) }
        }
        fundamentalsContent(fundamentals, live.quote?.lastPrice, onOpenSymbol)
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
private fun ChartCard(live: StockLive, range: ChartRange, onRangeSelected: (ChartRange) -> Unit, levels: List<Pair<String, Double>> = emptyList()) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(MarksyTheme.Surface).border(1.dp, MarksyTheme.BorderGlow, RoundedCornerShape(14.dp)).padding(12.dp)) {
        val candles = live.candles
        var selected by remember(candles) { mutableStateOf<Int?>(null) }
        val pick = selected?.let { candles?.getOrNull(it) }
        // 1D is measured from the previous close, as brokers show it; longer ranges from the range's first open.
        val base = if (range == ChartRange.D1) live.quote?.prevClose ?: candles?.firstOrNull()?.open else candles?.firstOrNull()?.open
        val last = candles?.lastOrNull()?.close
        fun change(v: Double?) = v?.let { base?.takeIf { it > 0 }?.let { b -> (v - b) / b * 100 } }
        val overall = if (range == ChartRange.D1 && live.quote?.changePct != null) live.quote.changePct else change(last)
        val pct = if (pick != null) change(pick.close) else overall
        fun tintOf(v: Double?) = if ((v ?: live.quote?.change ?: 0.0) >= 0) MarksyTheme.PrimaryEmerald else MarksyTheme.RedUrgent
        val tint = tintOf(pct)
        val day = SimpleDateFormat("d MMM", Locale.getDefault())
        // After hours the intraday feed can be empty and history may lag a session; say which day the chart is.
        val session = candles?.lastOrNull()?.time?.let { day.format(Date(it)) }
        val stale = range == ChartRange.D1 && session != null && live.quote?.lastTradeTime?.let { day.format(Date(it)) } != session
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            val headline = when {
                pick != null -> "₹${money(pick.close)}  " + (pct?.let { String.format(Locale.US, "%+.2f%%", it) } ?: "")
                pct != null -> "${String.format(Locale.US, "%+.2f%%", pct)} ${if (range == ChartRange.D1) "today" else "in ${range.label}"}"
                else -> ""
            }
            Text(headline, color = tint, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, modifier = Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                ChartRange.entries.forEach { r ->
                    val on = r == range
                    Box(
                        Modifier.size(30.dp).clip(CircleShape).background(if (on) MarksyTheme.PrimaryEmerald else MarksyTheme.SurfaceRaised).clickable { onRangeSelected(r) },
                        contentAlignment = Alignment.Center
                    ) { Text(r.label, color = if (on) Color.Black else MarksyTheme.TextSecondary, fontSize = 10.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Medium) }
                }
            }
        }
        val detail = when {
            pick != null -> "${ChartAxis.readout(range, pick.time)} · H ${money(pick.high)} · L ${money(pick.low)} · Vol ${compact(pick.volume)}"
            !candles.isNullOrEmpty() -> listOfNotNull(if (stale) "$session session" else null, "Low ₹${money(candles.minOf { it.low })} · High ₹${money(candles.maxOf { it.high })}").joinToString(" · ")
            else -> ""
        }
        Text(detail, color = MarksyTheme.TextMuted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Box(Modifier.fillMaxWidth().height(200.dp).padding(top = 8.dp), contentAlignment = Alignment.Center) {
            when {
                candles == null -> MarksyLoader("Loading chart...")
                candles.size < 2 -> Text("No chart data for ${range.label}", color = MarksyTheme.TextMuted, fontSize = 12.sp)
                else -> PriceChart(candles, range, tintOf(overall), reference = live.quote?.prevClose?.takeIf { range == ChartRange.D1 }, selected = selected, onSelect = { selected = it }, levels = levels)
            }
        }
    }
}

@Composable
private fun StatsCard(q: UpstoxQuote, live: StockLive) {
    val year = live.yearRange
    val stats = listOf(
        "Open" to q.open?.let(::money), "Prev. close" to q.prevClose?.let(::money), "Avg. price" to q.averagePrice?.let(::money),
        "Day high" to q.high?.let(::money), "Day low" to q.low?.let(::money), "Volume" to q.volume?.let(::compact),
        "52W high" to year?.second?.let(::money), "52W low" to year?.first?.let(::money),
        "From 52W high" to year?.second?.takeIf { it > 0 }?.let { String.format(Locale.US, "%+.1f%%", (q.lastPrice - it) / it * 100) },
        "Upper circuit" to q.upperCircuit?.let(::money), "Lower circuit" to q.lowerCircuit?.let(::money),
        "Traded value" to q.volume?.let { v -> q.averagePrice?.let { "₹" + compact(Math.round(v * it)) } },
        "Avg. vol. (20D)" to live.averageVolume?.let(::compact),
        "Vol. vs 20D" to q.volume?.let { v -> live.averageVolume?.takeIf { it > 0 }?.let { String.format(Locale.US, "%.1f×", v.toDouble() / it) } },
        "ATR (14)" to com.marksy.os.upstox.Technicals.atr(live.daily)?.let(::money),
        "Volatility (1Y)" to com.marksy.os.upstox.Technicals.volatility(live.daily)?.let { String.format(Locale.US, "%.1f%%", it) },
        "Beta (NIFTY 50)" to com.marksy.os.upstox.Technicals.beta(live.daily, live.index, java.time.ZoneId.of("Asia/Kolkata"))?.let { String.format(Locale.US, "%.2f", it) }
    ).filter { it.second != null }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(MarksyTheme.Surface).border(1.dp, MarksyTheme.BorderGlow, RoundedCornerShape(14.dp)).padding(12.dp)) {
        val zone = java.time.ZoneId.of("Asia/Kolkata")
        val long = remember(live.monthly, q.lastPrice) { com.marksy.os.upstox.Seasonality.longReturns(live.monthly, q.lastPrice, zone) }
        val returns = listOf("1W", "1M", "3M", "6M", "YTD", "1Y", "3Y", "5Y", "10Y").mapNotNull { k -> (live.returns[k] ?: long[k])?.let { k to it } }
        if (returns.isNotEmpty()) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            returns.forEach { (label, raw) ->
                val pct = if (kotlin.math.abs(raw) < .05) 0.0 else raw
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(label, color = MarksyTheme.TextMuted, fontSize = 10.sp)
                    Text(String.format(Locale.US, "%+.1f%%", pct), color = if (pct >= 0) MarksyTheme.PrimaryEmerald else MarksyTheme.RedUrgent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        stats.chunked(3).forEach { row ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                row.forEach { (label, value) ->
                    Column(Modifier.weight(1f)) {
                        Text(label, color = MarksyTheme.TextMuted, fontSize = 11.sp, maxLines = 1)
                        Text(value!!, color = MarksyTheme.TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    }
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
        if (q.low != null && q.high != null) RangeBar("Day range", q.low, q.high, q.lastPrice)
        year?.let { (lo, hi) -> RangeBar("52-week range", lo, hi, q.lastPrice) }
        com.marksy.os.upstox.Seasonality.allTime(live.monthly)?.let { (lo, hi) ->
            val month = SimpleDateFormat("MMM yyyy", Locale.getDefault())
            RangeBar("All-time range (since ${month.format(Date(live.monthly.first().time))})", lo.first, hi.first, q.lastPrice)
            Text("Low in ${month.format(Date(lo.second))} · high in ${month.format(Date(hi.second))}", color = MarksyTheme.TextMuted, fontSize = 10.sp)
        }
        VolumeTrend(live.daily, live.averageVolume)
    }
}

/** The last seven sessions' volume against the 20-day average. */
@Composable
private fun VolumeTrend(daily: List<Candle>, average: Long?) {
    val days = daily.takeLast(7).takeIf { it.size >= 2 } ?: return
    val peak = (days.maxOf { it.volume }.toDouble()).coerceAtLeast(average?.toDouble() ?: 0.0).takeIf { it > 0 } ?: return
    val day = SimpleDateFormat("d MMM", Locale.getDefault())
    Column(Modifier.fillMaxWidth().padding(top = 12.dp)) {
        Row {
            Text("Volume trend", color = MarksyTheme.TextMuted, fontSize = 11.sp, modifier = Modifier.weight(1f))
            average?.let { Text("20D avg ${compact(it)}", color = MarksyTheme.TextMuted, fontSize = 10.sp) }
        }
        Row(Modifier.fillMaxWidth().height(90.dp).padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Bottom) {
            days.forEach { c ->
                val above = average != null && c.volume > average
                Column(Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Bottom) {
                    Text(if (c.volume >= 10_000_000) String.format(Locale.US, "%.1fCr", c.volume / 1e7) else if (c.volume >= 100_000) "${c.volume / 100_000}L" else count(c.volume), color = MarksyTheme.TextSecondary, fontSize = 8.sp, maxLines = 1)
                    Box(Modifier.fillMaxWidth(.7f).fillMaxHeight((c.volume / peak * .6).toFloat().coerceAtLeast(.02f)).clip(RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp))
                        .background(if (above) MarksyTheme.PrimaryEmerald.copy(alpha = .8f) else MarksyTheme.TextMuted.copy(alpha = .5f)))
                    Text(day.format(Date(c.time)), color = MarksyTheme.TextMuted, fontSize = 8.sp, maxLines = 1)
                }
            }
        }
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
        val deepest = (q.bids + q.asks).take(10).maxOfOrNull { it.quantity }?.takeIf { it > 0 } ?: 1L
        Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
            DepthSide("Buy", q.bids, MarksyTheme.PrimaryEmerald, sell = false, deepest, Modifier.weight(1f))
            Spacer(Modifier.width(10.dp))
            DepthSide("Sell", q.asks, MarksyTheme.RedUrgent, sell = true, deepest, Modifier.weight(1f))
        }
        val buy = q.totalBuyQty
        val sell = q.totalSellQty
        if (buy != null && sell != null && buy + sell > 0) {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Text("Total", color = MarksyTheme.TextSecondary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text(count(buy), color = MarksyTheme.TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(10.dp))
                Text(count(sell), color = MarksyTheme.TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text("Total", color = MarksyTheme.TextSecondary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
            val share = buy.toFloat() / (buy + sell)
            Row(Modifier.fillMaxWidth().padding(top = 8.dp).height(6.dp).clip(RoundedCornerShape(3.dp))) {
                Box(Modifier.weight(share.coerceAtLeast(.01f)).fillMaxHeight().background(MarksyTheme.PrimaryEmerald))
                Box(Modifier.weight((1 - share).coerceAtLeast(.01f)).fillMaxHeight().background(MarksyTheme.RedUrgent))
            }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("${Math.round(share * 100)}% buyers", color = MarksyTheme.TextSecondary, fontSize = 11.sp)
                Text("${100 - Math.round(share * 100)}% sellers", color = MarksyTheme.TextSecondary, fontSize = 11.sp)
            }
        }
    }
}

/** One side of the book, mirrored like a broker's: Qty · Orders · Price for buys, Price · Orders · Qty for sells. */
@Composable
private fun DepthSide(title: String, levels: List<DepthLevel>, tint: Color, sell: Boolean, deepest: Long, modifier: Modifier) {
    @Composable
    fun RowScope.cells(qty: String, orders: String, price: String, qtyColor: Color, priceColor: Color, size: Int) {
        val first = if (sell) price to priceColor else qty to qtyColor
        val last = if (sell) qty to qtyColor else price to priceColor
        Text(first.first, color = first.second, fontSize = size.sp, modifier = Modifier.weight(1f))
        Text(orders, color = MarksyTheme.TextMuted, fontSize = size.sp, modifier = Modifier.weight(.6f), textAlign = TextAlign.Center)
        Text(last.first, color = last.second, fontSize = size.sp, modifier = Modifier.weight(1f), textAlign = TextAlign.End)
    }
    Column(modifier) {
        Text(title, color = tint, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Row(Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 2.dp)) { cells("Qty", "Orders", "Price", MarksyTheme.TextMuted, MarksyTheme.TextMuted, 10) }
        Box(Modifier.fillMaxWidth().height(1.dp).background(tint.copy(alpha = .5f)))
        levels.take(5).forEach { l ->
            Box(Modifier.fillMaxWidth().padding(top = 3.dp).height(22.dp), contentAlignment = Alignment.CenterStart) {
                // The bar grows from the centre of the book, so the two sides read against each other.
                Box(
                    Modifier.align(if (sell) Alignment.CenterStart else Alignment.CenterEnd)
                        .fillMaxWidth((l.quantity.toFloat() / deepest).coerceIn(0f, 1f)).fillMaxHeight().background(tint.copy(alpha = .12f))
                )
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    cells(count(l.quantity), l.orders.toString(), money(l.price), MarksyTheme.TextPrimary, tint, 12)
                }
            }
        }
    }
}

/** Indian grouping, two decimals: 1234567.5 -> "12,34,567.50". */
internal fun money(v: Double): String {
    val negative = v < 0
    val cents = Math.round(kotlin.math.abs(v) * 100)
    return (if (negative) "-" else "") + indian(cents / 100) + "." + (cents % 100).toString().padStart(2, '0')
}

internal fun count(v: Long): String = indian(v)

/** Indian units for big counts: 13138735 -> "1.31 Cr", 452000 -> "4.52 L". */
internal fun compact(v: Long): String = when {
    v >= 10_000_000 -> money(v / 1e7) + " Cr"
    v >= 100_000 -> money(v / 1e5) + " L"
    else -> indian(v)
}

private fun indian(n: Long): String {
    val s = n.toString()
    if (s.length <= 3) return s
    val head = s.dropLast(3)
    return head.reversed().chunked(2).joinToString(",").reversed() + "," + s.takeLast(3)
}
