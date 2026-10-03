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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CandlestickChart
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.marksy.os.MarksyFormat
import com.marksy.os.market.InstrumentLifecycleDto
import com.marksy.os.market.LedgerCalls
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
    minutes: Int? = null,
    onMinutesSelected: (Int?) -> Unit = {},
    mentions: List<com.marksy.os.data.local.NotificationEventEntity> = emptyList(),
    onEventSelected: (com.marksy.os.data.local.NotificationEventEntity) -> Unit = {},
    fundamentals: StockFundamentals = StockFundamentals(),
    onOpenSymbol: (String) -> Unit = {},
    analysis: org.json.JSONObject? = null,
    onOpenTip: (String) -> Unit = {},
    followed: Set<com.marksy.os.market.FollowKey>? = null,
    onToggleFollow: ((com.marksy.os.market.FollowKey, String, Boolean) -> Unit)? = null
) {
    val instrument = (state as? MarketDataState.Loaded)?.value ?: (state as? MarketDataState.Stale)?.value
    // A new symbol (e.g. a tapped peer) opens at its header, not at the previous stock's scroll position.
    val listState = rememberSaveable(symbol, saver = LazyListState.Saver) { LazyListState() }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().background(MarksyTheme.Background).padding(horizontal = MarksySpace.Gutter),
        contentPadding = PaddingValues(top = 0.dp, bottom = padding.calculateBottomPadding() + 20.dp),
        verticalArrangement = Arrangement.spacedBy(MarksySpace.ListGap)
    ) {
        item { PriceHeader(instrument, symbol, live) }
        // Channels are listed only with a call here, but every engine is, so an engine-only box needs a tip.
        val calls = instrument?.calls?.takeIf { c -> c.engines.any { it.tips.isNotEmpty() } || c.channels.isNotEmpty() }
        calls?.let { c -> item(key = "calls") { CallsBox(c, live.quote?.lastPrice, analysis, onOpenTip, followed, onToggleFollow) } }
        live.note?.let { note -> item { Text(note, color = MarksyTheme.TextMuted, style = MarksyType.Meta) } }
        val levels = LedgerCalls.chartLevels(LedgerCalls.leadingMarksyCall(instrument?.calls))
        if (live.quote != null || live.candles != null) item { ChartCard(live, range, onRangeSelected, levels, minutes, onMinutesSelected) }
        live.quote?.let { q ->
            item { StatsCard(q, live) }
            item { TechnicalCard(live.daily, q.lastPrice) }
            if (live.monthly.size >= 13) item { SeasonalityCard(live.monthly, symbol ?: instrument?.symbol.orEmpty()) }
            if (q.bids.isNotEmpty() || q.asks.isNotEmpty()) item { DepthCard(q) }
        }
        live.key?.let { k -> item(key = "fno-$k") { DerivativesCard(k) } }
        fundamentalsContent(fundamentals, live.quote?.lastPrice, onOpenSymbol)
        if (mentions.isNotEmpty()) {
            item { Text("In your notifications", color = MarksyTheme.TextPrimary, style = MarksyType.Subhead, fontWeight = FontWeight.SemiBold) }
            items(mentions.take(5), key = { "mention-${it.id}" }) { e ->
                Column(
                    Modifier.fillMaxWidth().marksyCard().clickable { onEventSelected(e) }.padding(MarksySpace.CardPadding)
                ) {
                    Text(e.title.ifBlank { e.sourceName }, color = MarksyTheme.TextPrimary, style = MarksyType.Body, fontWeight = FontWeight.Medium, maxLines = 1)
                    Text(e.body, color = MarksyTheme.TextSecondary, style = MarksyType.Small, maxLines = 2)
                    Text("${e.sourceName} · ${MarksyFormat.dayTime(at(e.postedAt))}", color = MarksyTheme.TextMuted, style = MarksyType.Caption)
                }
            }
        }
    }
}

@Composable
private fun PriceHeader(instrument: InstrumentLifecycleDto?, symbol: String?, live: StockLive) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(instrument?.companyName ?: symbol ?: instrument?.symbol.orEmpty(), color = MarksyTheme.TextPrimary, style = MarksyType.Heading, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f, fill = false))
            (instrument?.symbol ?: symbol)?.let { WatchlistButton(it, Modifier.padding(start = 4.dp)) }
        }
        Text(listOfNotNull(instrument?.symbol ?: symbol, instrument?.exchange?.ifBlank { null }, instrument?.sector, live.isin?.let { "ISIN $it" }).joinToString(" · "), color = MarksyTheme.TextSecondary, style = MarksyType.Small)
        val q = live.quote
        val price = q?.lastPrice ?: instrument?.market?.lastClosePrice
        if (price != null) Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.Bottom) {
            Text(MarksyFormat.rupees(price), color = MarksyTheme.TextPrimary, style = MarksyType.Display, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(8.dp))
            val change = q?.change
            val pct = q?.changePct
            if (change != null && pct != null) {
                Text(
                    "${if (change >= 0) "+" else ""}${money(change)} (${MarksyFormat.percent(pct)})",
                    color = if (change >= 0) MarksyTheme.Positive else MarksyTheme.Negative, style = MarksyType.Subhead, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(bottom = 3.dp)
                )
            } else if (q == null) {
                Text("last close", color = MarksyTheme.TextMuted, style = MarksyType.Small, modifier = Modifier.padding(bottom = 4.dp))
            }
            Spacer(Modifier.weight(1f))
            if (live.streaming) MarksyBadge("LIVE", MarksyTheme.Positive, MarksyTheme.BadgeTradingBg, Modifier.padding(bottom = 4.dp))
        }
        q?.lastTradeTime?.let { Text("Last trade ${MarksyFormat.dayTime(at(it))}", color = MarksyTheme.TextMuted, style = MarksyType.Caption) }
    }
}

@Composable
private fun ChartCard(
    live: StockLive, range: ChartRange, onRangeSelected: (ChartRange) -> Unit, levels: List<Pair<String, Double>> = emptyList(),
    minutes: Int? = null, onMinutesSelected: (Int?) -> Unit = {}
) {
    val prefs = rememberChartPrefs()
    var tuning by remember { mutableStateOf(false) }
    if (tuning) ChartSettingsDialog(prefs, range, minutes ?: range.defaultMinutes, onMinutesSelected) { tuning = false }
    Column(Modifier.fillMaxWidth().marksyCard().padding(MarksySpace.CardPadding)) {
        val candles = live.candles
        var selected by remember(candles) { mutableStateOf<Int?>(null) }
        val pick = selected?.let { candles?.getOrNull(it) }
        // 1D is measured from the previous close, as brokers show it; longer ranges from the range's first open.
        val base = if (range == ChartRange.D1) live.quote?.prevClose ?: candles?.firstOrNull()?.open else candles?.firstOrNull()?.open
        val last = candles?.lastOrNull()?.close
        fun change(v: Double?) = v?.let { base?.takeIf { it > 0 }?.let { b -> (v - b) / b * 100 } }
        val overall = if (range == ChartRange.D1 && live.quote?.changePct != null) live.quote.changePct else change(last)
        val pct = if (pick != null) change(pick.close) else overall
        fun tintOf(v: Double?) = if ((v ?: live.quote?.change ?: 0.0) >= 0) MarksyTheme.Positive else MarksyTheme.Negative
        val tint = tintOf(pct)
        fun day(ms: Long) = MarksyFormat.day(at(ms).toLocalDate())
        // After hours the intraday feed can be empty and history may lag a session; say which day the chart is.
        val session = candles?.lastOrNull()?.time?.let { day(it) }
        val stale = range == ChartRange.D1 && session != null && live.quote?.lastTradeTime?.let { day(it) } != session
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            val headline = when {
                pick != null -> "${MarksyFormat.rupees(pick.close)}  " + (pct?.let { MarksyFormat.percent(it) } ?: "")
                pct != null -> "${MarksyFormat.percent(pct)} ${when (range) { ChartRange.D1 -> "today"; ChartRange.MAX -> "all time"; else -> "in ${range.label}" }}"
                else -> ""
            }
            Text(headline, color = tint, style = MarksyType.Body, fontWeight = FontWeight.SemiBold, maxLines = 1, modifier = Modifier.weight(1f))
            ChartIconButton(if (prefs.candles) Icons.Default.CandlestickChart else Icons.Default.ShowChart, if (prefs.candles) "Show line" else "Show candles") { prefs.candles = !prefs.candles }
            Spacer(Modifier.width(6.dp))
            ChartIconButton(Icons.Default.Tune, "Indicators and interval", active = prefs.overlays.isNotEmpty()) { tuning = true }
        }
        val detail = when {
            pick != null && prefs.candles -> "${ChartAxis.readout(range, pick.time)} · O ${money(pick.open)} H ${money(pick.high)} L ${money(pick.low)} C ${money(pick.close)} · Vol ${compact(pick.volume)}"
            pick != null -> "${ChartAxis.readout(range, pick.time)} · H ${money(pick.high)} · L ${money(pick.low)} · Vol ${compact(pick.volume)}"
            !candles.isNullOrEmpty() -> listOfNotNull(if (stale) "$session session" else null, "Low ${MarksyFormat.rupees(candles.minOf { it.low })} · High ${MarksyFormat.rupees(candles.maxOf { it.high })}").joinToString(" · ")
            else -> ""
        }
        Text(detail, color = MarksyTheme.TextMuted, style = MarksyType.Caption, maxLines = 1, overflow = TextOverflow.Ellipsis)
        val overlays = remember(candles, prefs.overlays, range) { candles?.let { chartOverlays(it, prefs.overlays, range) }.orEmpty() }
        if (overlays.isNotEmpty()) Row(Modifier.padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            overlays.distinctBy { it.label }.forEach { o -> Text(o.label, color = o.color, style = MarksyType.Caption, fontWeight = FontWeight.SemiBold) }
        }
        Box(Modifier.fillMaxWidth().height(if (prefs.volume) 224.dp else 200.dp).padding(top = 8.dp), contentAlignment = Alignment.Center) {
            when {
                candles == null -> MarksyLoader("Loading chart…")
                candles.size < 2 -> Text("No chart data for ${range.label}", color = MarksyTheme.TextMuted, style = MarksyType.Small)
                else -> PriceChart(
                    candles, range, tintOf(overall), reference = live.quote?.prevClose?.takeIf { range == ChartRange.D1 }, selected = selected, onSelect = { selected = it },
                    levels = levels, candleMode = prefs.candles, overlays = overlays, showVolume = prefs.volume
                )
            }
        }
        // Ranges sit under the chart, as on Upstox and Groww, so the header keeps room for the move.
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            ChartRange.entries.forEach { r ->
                val on = r == range
                val fill by androidx.compose.animation.animateColorAsState(if (on) MarksyTheme.PrimaryEmerald else Color.Transparent, label = "range")
                Box(
                    Modifier.clip(MarksyShape.Pill).background(fill).clickable { onRangeSelected(r) }.padding(horizontal = 7.dp, vertical = 4.dp),
                    contentAlignment = Alignment.Center
                ) { Text(r.label, color = if (on) MarksyTheme.OnAccent else MarksyTheme.TextSecondary, style = MarksyType.Meta, fontWeight = if (on) FontWeight.Bold else FontWeight.Medium) }
            }
        }
    }
}

@Composable
private fun ChartIconButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, active: Boolean = false, onClick: () -> Unit) {
    Icon(
        icon, contentDescription = label, tint = if (active) MarksyTheme.OnAccent else MarksyTheme.PrimaryEmerald,
        modifier = Modifier.size(28.dp).clip(CircleShape).background(if (active) MarksyTheme.PrimaryEmerald else MarksyTheme.SurfaceRaised)
            .border(MarksySpace.Border, MarksyTheme.BorderGlow, CircleShape).clickable(onClick = onClick).padding(5.dp)
    )
}

@Composable
private fun StatsCard(q: UpstoxQuote, live: StockLive) {
    val year = live.yearRange
    val stats = listOf(
        "Open" to q.open?.let(::money), "Prev. close" to q.prevClose?.let(::money), "Avg. price" to q.averagePrice?.let(::money),
        "Day high" to q.high?.let(::money), "Day low" to q.low?.let(::money), "Volume" to q.volume?.let(::compact),
        "52W high" to year?.second?.let(::money), "52W low" to year?.first?.let(::money),
        "From 52W high" to year?.second?.takeIf { it > 0 }?.let { MarksyFormat.percent((q.lastPrice - it) / it * 100, 1) },
        "Upper circuit" to q.upperCircuit?.let(::money), "Lower circuit" to q.lowerCircuit?.let(::money),
        "Traded value" to q.volume?.let { v -> q.averagePrice?.let { "₹" + compact(Math.round(v * it)) } },
        "Avg. vol. (20D)" to live.averageVolume?.let(::compact),
        "Vol. vs 20D" to q.volume?.let { v -> live.averageVolume?.takeIf { it > 0 }?.let { MarksyFormat.number(v.toDouble() / it, 1) + "×" } },
        "ATR (14)" to com.marksy.os.upstox.Technicals.atr(live.daily)?.let(::money),
        "Volatility (1Y)" to com.marksy.os.upstox.Technicals.volatility(live.daily)?.let { MarksyFormat.percent(it, 1, signed = false) },
        "Beta (NIFTY 50)" to com.marksy.os.upstox.Technicals.beta(live.daily, live.index, java.time.ZoneId.of("Asia/Kolkata"))?.let(::money)
    ).filter { it.second != null }
    Column(Modifier.fillMaxWidth().marksyCard().padding(MarksySpace.CardPadding)) {
        val zone = java.time.ZoneId.of("Asia/Kolkata")
        val long = remember(live.monthly, q.lastPrice) { com.marksy.os.upstox.Seasonality.longReturns(live.monthly, q.lastPrice, zone) }
        val returns = listOf("1W", "1M", "3M", "6M", "YTD", "1Y", "3Y", "5Y", "10Y").mapNotNull { k -> (live.returns[k] ?: long[k])?.let { k to it } }
        if (returns.isNotEmpty()) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            returns.forEach { (label, raw) ->
                val pct = if (kotlin.math.abs(raw) < .05) 0.0 else raw
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(label, color = MarksyTheme.TextMuted, style = MarksyType.Caption)
                    Text(MarksyFormat.percent(pct, 1), color = if (pct >= 0) MarksyTheme.Positive else MarksyTheme.Negative, style = MarksyType.Body, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        stats.chunked(3).forEach { row ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                row.forEach { (label, value) ->
                    Column(Modifier.weight(1f)) {
                        Text(label, color = MarksyTheme.TextMuted, style = MarksyType.Meta, maxLines = 1)
                        Text(value!!, color = MarksyTheme.TextPrimary, style = MarksyType.Body, fontWeight = FontWeight.SemiBold, maxLines = 1)
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
            Text("Low in ${month.format(Date(lo.second))} · high in ${month.format(Date(hi.second))}", color = MarksyTheme.TextMuted, style = MarksyType.Caption)
        }
        VolumeTrend(live.daily, live.averageVolume)
    }
}

/** The last seven sessions' volume against the 20-day average. */
@Composable
private fun VolumeTrend(daily: List<Candle>, average: Long?) {
    val days = daily.takeLast(7).takeIf { it.size >= 2 } ?: return
    val peak = (days.maxOf { it.volume }.toDouble()).coerceAtLeast(average?.toDouble() ?: 0.0).takeIf { it > 0 } ?: return
    Column(Modifier.fillMaxWidth().padding(top = 12.dp)) {
        Row {
            Text("Volume trend", color = MarksyTheme.TextMuted, style = MarksyType.Meta, modifier = Modifier.weight(1f))
            average?.let { Text("20D avg ${compact(it)}", color = MarksyTheme.TextMuted, style = MarksyType.Caption) }
        }
        Row(Modifier.fillMaxWidth().height(90.dp).padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Bottom) {
            days.forEach { c ->
                val above = average != null && c.volume > average
                Column(Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Bottom) {
                    Text(if (c.volume >= 10_000_000) MarksyFormat.number(c.volume / 1e7, 1) + "Cr" else if (c.volume >= 100_000) "${c.volume / 100_000}L" else count(c.volume), color = MarksyTheme.TextSecondary, style = MarksyType.Caption, maxLines = 1)
                    Box(Modifier.fillMaxWidth(.7f).fillMaxHeight((c.volume / peak * .6).toFloat().coerceAtLeast(.02f)).clip(RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp))
                        .background(if (above) MarksyTheme.Positive.copy(alpha = .8f) else MarksyTheme.TextMuted.copy(alpha = .5f)))
                    Text(MarksyFormat.day(at(c.time).toLocalDate()), color = MarksyTheme.TextMuted, style = MarksyType.Caption, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun RangeBar(label: String, low: Double, high: Double, price: Double) {
    Column(Modifier.fillMaxWidth().padding(top = 10.dp)) {
        Text(label, color = MarksyTheme.TextMuted, style = MarksyType.Meta)
        val at = if (high > low) ((price - low) / (high - low)).coerceIn(0.0, 1.0).toFloat() else .5f
        Canvas(Modifier.fillMaxWidth().height(14.dp)) {
            val mid = size.height / 2
            drawLine(MarksyTheme.Divider, Offset(0f, mid), Offset(size.width, mid), 4.dp.toPx(), StrokeCap.Round)
            drawLine(MarksyTheme.PrimaryEmerald, Offset(0f, mid), Offset(size.width * at, mid), 4.dp.toPx(), StrokeCap.Round)
            drawCircle(MarksyTheme.TextPrimary, 5.dp.toPx(), Offset(size.width * at, mid))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(MarksyFormat.rupees(low), color = MarksyTheme.TextSecondary, style = MarksyType.Meta)
            Text(MarksyFormat.rupees(high), color = MarksyTheme.TextSecondary, style = MarksyType.Meta)
        }
    }
}

@Composable
private fun DepthCard(q: UpstoxQuote) {
    Column(Modifier.fillMaxWidth().marksyCard().padding(MarksySpace.CardPadding)) {
        Text("Market depth", color = MarksyTheme.TextPrimary, style = MarksyType.Body, fontWeight = FontWeight.SemiBold)
        val deepest = (q.bids + q.asks).take(10).maxOfOrNull { it.quantity }?.takeIf { it > 0 } ?: 1L
        Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
            DepthSide("Buy", q.bids, MarksyTheme.Positive, sell = false, deepest, Modifier.weight(1f))
            Spacer(Modifier.width(10.dp))
            DepthSide("Sell", q.asks, MarksyTheme.Negative, sell = true, deepest, Modifier.weight(1f))
        }
        val buy = q.totalBuyQty
        val sell = q.totalSellQty
        if (buy != null && sell != null && buy + sell > 0) {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Text("Total", color = MarksyTheme.TextSecondary, style = MarksyType.Small, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text(count(buy), color = MarksyTheme.TextPrimary, style = MarksyType.Small, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(10.dp))
                Text(count(sell), color = MarksyTheme.TextPrimary, style = MarksyType.Small, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text("Total", color = MarksyTheme.TextSecondary, style = MarksyType.Small, fontWeight = FontWeight.SemiBold)
            }
            val share = buy.toFloat() / (buy + sell)
            Row(Modifier.fillMaxWidth().padding(top = 8.dp).height(6.dp).clip(MarksyShape.Badge)) {
                Box(Modifier.weight(share.coerceAtLeast(.01f)).fillMaxHeight().background(MarksyTheme.Positive))
                Box(Modifier.weight((1 - share).coerceAtLeast(.01f)).fillMaxHeight().background(MarksyTheme.Negative))
            }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("${Math.round(share * 100)}% buyers", color = MarksyTheme.TextSecondary, style = MarksyType.Meta)
                Text("${100 - Math.round(share * 100)}% sellers", color = MarksyTheme.TextSecondary, style = MarksyType.Meta)
            }
        }
    }
}

/** One side of the book, mirrored like a broker's: Qty · Orders · Price for buys, Price · Orders · Qty for sells. */
@Composable
private fun DepthSide(title: String, levels: List<DepthLevel>, tint: Color, sell: Boolean, deepest: Long, modifier: Modifier) {
    @Composable
    fun RowScope.cells(qty: String, orders: String, price: String, qtyColor: Color, priceColor: Color, style: TextStyle) {
        val first = if (sell) price to priceColor else qty to qtyColor
        val last = if (sell) qty to qtyColor else price to priceColor
        Text(first.first, color = first.second, style = style, modifier = Modifier.weight(1f))
        Text(orders, color = MarksyTheme.TextMuted, style = style, modifier = Modifier.weight(.6f), textAlign = TextAlign.Center)
        Text(last.first, color = last.second, style = style, modifier = Modifier.weight(1f), textAlign = TextAlign.End)
    }
    Column(modifier) {
        Text(title, color = tint, style = MarksyType.Small, fontWeight = FontWeight.Bold)
        Row(Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 2.dp)) { cells("Qty", "Orders", "Price", MarksyTheme.TextMuted, MarksyTheme.TextMuted, MarksyType.Caption) }
        Box(Modifier.fillMaxWidth().height(MarksySpace.Border).background(tint.copy(alpha = .5f)))
        levels.take(5).forEach { l ->
            Box(Modifier.fillMaxWidth().padding(top = 3.dp).height(22.dp), contentAlignment = Alignment.CenterStart) {
                // The bar grows from the centre of the book, so the two sides read against each other.
                Box(
                    Modifier.align(if (sell) Alignment.CenterStart else Alignment.CenterEnd)
                        .fillMaxWidth((l.quantity.toFloat() / deepest).coerceIn(0f, 1f)).fillMaxHeight().background(tint.copy(alpha = .12f))
                )
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    cells(count(l.quantity), l.orders.toString(), money(l.price), MarksyTheme.TextPrimary, tint, MarksyType.Small)
                }
            }
        }
    }
}

/** Indian grouping, two decimals: 1234567.5 -> "12,34,567.50". */
internal fun money(v: Double): String = (if (v < 0) MarksyFormat.MINUS else "") + MarksyFormat.number(v)

internal fun count(v: Long): String = MarksyFormat.number(v.toDouble(), 0)

private fun at(ms: Long) = java.time.Instant.ofEpochMilli(ms).atZone(java.time.ZoneId.systemDefault())

/** Indian units for big counts: 13138735 -> "1.31 Cr", 452000 -> "4.52 L". */
internal fun compact(v: Long): String = when {
    v >= 10_000_000 -> money(v / 1e7) + " Cr"
    v >= 100_000 -> money(v / 1e5) + " L"
    else -> count(v)
}
