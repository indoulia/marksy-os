package com.marksy.os.ui

import com.marksy.os.gateway.MarketState
import java.util.Locale

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.marksy.os.EmptyState

@Composable
fun TradingIntelligenceScreen(
    insights: List<TradingInsight>,
    padding: PaddingValues,
    market: MarketState = MarketState.Loading,
    selectedFilter: String = TradingFilters.first(),
    onFilterSelected: (String) -> Unit = {},
    onOpenStock: (String) -> Unit = {},
    marketRepository: com.marksy.os.market.MarketIntelligenceRepository? = null,
    setupReports: List<SetupReport> = emptyList()
) {
    // Marksy supplies what to show (picks, movers, targets); prices tick live from the user's Upstox feed.
    // External calls (broker apps, SMS, chat) parsed into side / symbol / levels, shown immediately.
    val calls = remember(insights) { insights.mapNotNull { i -> com.marksy.os.notification.TradeCallParser.parse(i.title, i.body)?.let { i to it } } }
    val callIds = remember(calls) { calls.map { it.first.eventId }.toSet() }
    val liveSymbols = remember(calls) { calls.map { it.second.symbol }.distinct() }
    val live = rememberUpstoxQuotes(liveSymbols)
    var ticket by remember { mutableStateOf<TradeIntent?>(null) }
    ticket?.let { TradeTicketSheet(it) { ticket = null } }

    Box(
        Modifier
            .fillMaxSize()
            .background(MarksyTheme.Background)
            .padding(padding)
            .consumeWindowInsets(padding)
    ) {
    Column(Modifier.fillMaxSize()) {
        if (selectedFilter == TAB_PREDICTIONS && marketRepository != null) PredictionsView(marketRepository, OneHandListBottomPadding, onOpenStock) else
        if (selectedFilter == TAB_PICKS) SetupsView(marketRepository, setupReports, OneHandListBottomPadding, onOpenStock) else
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(top = 10.dp, bottom = OneHandListBottomPadding)
        ) {
            when (selectedFilter) {
                TAB_CALLS -> {
                    if (calls.isEmpty()) item { EmptyState("No calls captured yet.", "Buy/sell calls from your broker apps, SMS and chats appear here the moment they arrive.") }
                    itemsIndexed(calls, key = { _, (i, _) -> "call-${i.eventId}" }) { _, (insight, call) ->
                        val quote = live[call.symbol]
                        Box(Modifier.clickable { onOpenStock(call.symbol) }) {
                            TradingSignalCard(
                                symbol = call.symbol,
                                price = quote?.lastPrice?.let(::rupees) ?: "—",
                                change = quote?.changePct?.let(::signedPct) ?: "",
                                signalType = call.side.name,
                                headline = listOfNotNull(insight.source, call.horizon, compactTime(insight.postedAt)).joinToString(" · "),
                                entry = call.entry?.let(::rupees) ?: "—",
                                target = call.target?.let(::rupees) ?: "—",
                                stopLoss = call.stopLoss?.let(::rupees) ?: "—",
                                confidence = insight.marksyConfidence?.let { confidencePct(it.toDouble()) } ?: "—",
                                alignment = listOfNotNull(insight.marksyVerdict?.let { "Marksy: $it" }, insight.status).joinToString(" · "),
                                onTrade = { ticket = TradeIntent(call.symbol, if (call.side.name == "SELL") TradeSide.SELL else TradeSide.BUY, quote?.lastPrice ?: call.entry, call.target, call.stopLoss) }
                            )
                        }
                    }
                }
                else -> {
                    val others = insights.filterNot { it.eventId in callIds }
                    if (others.isEmpty()) {
                        item {
                            EmptyState(
                                "No other trading events captured yet.",
                                "Order executions and confirmations from your broker apps appear here."
                            )
                        }
                    } else {
                        items(others, key = { it.eventId }) { insight ->
                            val symbol = remember(insight.eventId) { com.marksy.os.gateway.MarksyTipPayloadBuilder.symbolOf(insight.title, insight.body) }
                            CapturedInsightCard(insight, symbol?.let { { onOpenStock(it) } })
                        }
                    }
                }
            }
        }
    }
    OneHandControls(
        filters = TradingFilters.map { it to it },
        selectedFilter = selectedFilter,
        onFilterSelected = onFilterSelected
    )
    }
}

private const val TAB_PICKS = "Setups"
private const val TAB_PREDICTIONS = "Predictions"
private const val TAB_CALLS = "Calls"
private const val TAB_CAPTURED = "Captured"
val TradingFilters = listOf(TAB_PICKS, TAB_PREDICTIONS, TAB_CALLS, TAB_CAPTURED)

/** Title superscript: the tab, plus on Marksy's tabs the session its latest picks come from. */
fun tradingTitleNote(filter: String, scan: com.marksy.os.market.LatestScanDto?): String =
    filter + (if (filter == TAB_PICKS || filter == TAB_PREDICTIONS) com.marksy.os.market.PicksBasis.day(scan?.scanSessionDate)?.let { " · $it" } else null).orEmpty()


private fun marketMessage(market: MarketState, loaded: String): String = when (market) {
    MarketState.Loading -> "Loading market data…"
    MarketState.NotConfigured -> "Connect the Marksy gateway in Settings to load market data."
    is MarketState.Unavailable -> "Marksy market data is unavailable right now."
    is MarketState.Loaded -> loaded
}

private fun rupees(value: Double): String = "₹" + String.format(Locale.getDefault(), "%,.2f", value)

private fun signedPct(value: Double): String = String.format(Locale.US, "%+.2f%%", value)

// Marksy reports confidence as 0–1 on some routes and 0–100 on others.
private fun confidencePct(value: Double): String = "${(if (value <= 1.0) value * 100 else value).toInt()}%"

private fun changeColor(change: String): Color = if (change.startsWith("-")) MarksyTheme.RedUrgent else MarksyTheme.PrimaryEmerald

@Composable
private fun TradingSignalCard(
    symbol: String,
    price: String,
    change: String,
    signalType: String,
    headline: String,
    entry: String,
    target: String,
    stopLoss: String,
    confidence: String,
    alignment: String,
    onTrade: (() -> Unit)? = null
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MarksyTheme.Surface),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MarksyTheme.PrimaryEmerald, RoundedCornerShape(16.dp))
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(24.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF381212)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("🔥", fontSize = 12.sp)
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(
                            symbol,
                            color = MarksyTheme.TextPrimary,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                        WatchlistButton(symbol, Modifier.padding(start = 2.dp))
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(top = 2.dp)
                    ) {
                        Text(price, color = MarksyTheme.TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.width(6.dp))
                        Text(change, color = changeColor(change), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }

                // Buy Action Button
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(
                            Brush.linearGradient(
                                listOf(
                                    MarksyTheme.PrimaryEmerald,
                                    MarksyTheme.AccentGreen
                                )
                            )
                        )
                        .clickable(enabled = onTrade != null, onClickLabel = "Open $signalType ticket") { onTrade?.invoke() }
                        .padding(horizontal = 20.dp, vertical = 8.dp)
                ) {
                    Text(signalType, color = Color.Black, fontSize = 12.sp, fontWeight = FontWeight.Black)
                }
            }

            Spacer(Modifier.height(6.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Link,
                    contentDescription = null,
                    tint = MarksyTheme.PrimaryEmerald,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(headline, color = MarksyTheme.TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            }

            Spacer(Modifier.height(8.dp))

            // Details Grid
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MarksyTheme.SurfaceRaised)
                    .padding(8.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text("Entry", color = MarksyTheme.TextMuted, fontSize = 10.sp)
                    Text(entry, color = MarksyTheme.TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
                Column {
                    Text("Target", color = MarksyTheme.TextMuted, fontSize = 10.sp)
                    Text(target, color = MarksyTheme.PrimaryEmerald, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
                Column {
                    Text("Stop Loss", color = MarksyTheme.TextMuted, fontSize = 10.sp)
                    Text(stopLoss, color = MarksyTheme.RedUrgent, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
                Column {
                    Text("Confidence", color = MarksyTheme.TextMuted, fontSize = 10.sp)
                    Text(confidence, color = MarksyTheme.PrimaryEmerald, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
            }

            Spacer(Modifier.height(6.dp))

            // Alignment Banner
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(MarksyTheme.BadgeTradingBg)
                    .padding(8.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Verified,
                        contentDescription = null,
                        tint = MarksyTheme.PrimaryEmerald,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        alignment,
                        color = MarksyTheme.PrimaryEmerald,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
}

@Composable
private fun TradingTickerCard(
    symbol: String,
    price: String,
    change: String
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MarksyTheme.Surface),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MarksyTheme.BorderGlow, RoundedCornerShape(14.dp))
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(MarksyTheme.BadgeFinanceBg),
                    contentAlignment = Alignment.Center
                ) {
                    Text("📊", fontSize = 12.sp)
                }
                Spacer(Modifier.width(8.dp))
                Text(symbol, color = MarksyTheme.TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(price, color = MarksyTheme.TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(6.dp))
                Text(change, color = changeColor(change), fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun CapturedInsightCard(
    insight: TradingInsight,
    onClick: (() -> Unit)?
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MarksyTheme.Surface, disabledContainerColor = MarksyTheme.Surface),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MarksyTheme.BorderGlow, RoundedCornerShape(14.dp)),
        onClick = onClick ?: {},
        enabled = onClick != null
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    insight.source.ifBlank { "Trading Source" },
                    color = MarksyTheme.PrimaryEmerald,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp
                )
                Text(listOfNotNull(compactTime(insight.postedAt), insight.status).joinToString(" · "), color = MarksyTheme.TextMuted, fontSize = 10.sp)
            }
            Spacer(Modifier.height(4.dp))
            Text(
                insight.headline.ifBlank { "Trading Alert" },
                color = MarksyTheme.TextPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold
            )
            if (insight.body.isNotBlank()) {
                Text(
                    insight.body,
                    color = MarksyTheme.TextSecondary,
                    fontSize = 12.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    }
}
