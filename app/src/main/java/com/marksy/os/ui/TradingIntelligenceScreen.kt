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
    onInsightSelected: (TradingInsight) -> Unit = {}
) {
    var selectedFilter by remember { mutableStateOf(TAB_PICKS) }
    val snapshot = (market as? MarketState.Loaded)?.snapshot

    Box(
        Modifier
            .fillMaxSize()
            .background(MarksyTheme.Background)
            .padding(padding)
            .consumeWindowInsets(padding)
    ) {
    Column(Modifier.fillMaxSize()) {
        // Header
        Column(Modifier.padding(horizontal = 18.dp, vertical = 8.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Trading Intelligence",
                    color = MarksyTheme.TextPrimary,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold
                )
                snapshot?.marketStatus?.let { status ->
                    val open = status.equals("OPEN", ignoreCase = true) || status.equals("LIVE", ignoreCase = true)
                    val tint = if (open) MarksyTheme.PrimaryEmerald else MarksyTheme.TextSecondary
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (open) MarksyTheme.BadgeTradingBg else MarksyTheme.SurfaceRaised)
                            .border(1.dp, tint, RoundedCornerShape(12.dp))
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(Modifier.size(6.dp).clip(CircleShape).background(tint))
                        Spacer(Modifier.width(5.dp))
                        Text(if (open) "LIVE" else status.uppercase(), color = tint, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
            Text(selectedFilter, color = MarksyTheme.TextMuted, fontSize = 12.sp)
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(bottom = OneHandListBottomPadding)
        ) {
            when (selectedFilter) {
                TAB_PICKS -> {
                    val picks = snapshot?.opportunities.orEmpty()
                    if (picks.isEmpty()) item { EmptyState("No Marksy picks right now.", marketMessage(market, "Top opportunities from Marksy will appear here.")) }
                    items(picks, key = { "pick-${it.symbol}" }) { pick ->
                        val reference = pick.entryPrice ?: pick.price
                        TradingSignalCard(
                            symbol = pick.symbol,
                            price = pick.price?.let(::rupees) ?: "—",
                            change = pick.changePct?.let(::signedPct) ?: "",
                            signalType = if (reference == null || pick.targetPrice >= reference) "BUY" else "SELL",
                            headline = pick.name,
                            entry = pick.entryPrice?.let(::rupees) ?: "—",
                            target = rupees(pick.targetPrice),
                            stopLoss = rupees(pick.stopLoss),
                            confidence = confidencePct(pick.confidence),
                            alignment = listOfNotNull(
                                pick.score?.let { "Score ${it.toInt()}" },
                                pick.horizonDays?.let { "$it-day horizon" },
                                pick.upsidePct?.let { "upside ${signedPct(it)}" }
                            ).joinToString(" · ")
                        )
                    }
                }
                TAB_MOVERS -> {
                    val movers = snapshot?.gainers.orEmpty() + snapshot?.losers.orEmpty()
                    if (movers.isEmpty()) item { EmptyState("No movers yet.", marketMessage(market, "Top gainers and losers from Marksy will appear here.")) }
                    items(movers, key = { "mover-${it.symbol}-${it.changePct}" }) { mover ->
                        TradingTickerCard(mover.symbol, mover.price?.let(::rupees) ?: mover.name, signedPct(mover.changePct))
                    }
                }
                TAB_INDICES -> {
                    val indices = snapshot?.indices.orEmpty()
                    if (indices.isEmpty()) item { EmptyState("No index data.", marketMessage(market, "Market indices from Marksy will appear here.")) }
                    items(indices, key = { "index-${it.name}" }) { index ->
                        TradingTickerCard(index.name, String.format(Locale.getDefault(), "%,.2f", index.value), signedPct(index.changePct))
                    }
                }
                else -> {
                    if (insights.isEmpty()) {
                        item {
                            EmptyState(
                                "No trading events captured yet.",
                                "Brokerage orders and market notifications will appear here when captured."
                            )
                        }
                    } else {
                        items(insights, key = { it.eventId }) { insight ->
                            CapturedInsightCard(insight) { onInsightSelected(insight) }
                        }
                    }
                }
            }
        }
    }
    OneHandControls(
        filters = listOf(TAB_PICKS, TAB_MOVERS, TAB_INDICES, TAB_CAPTURED).map { it to it },
        selectedFilter = selectedFilter,
        onFilterSelected = { selectedFilter = it }
    )
    }
}

private const val TAB_PICKS = "Marksy picks"
private const val TAB_MOVERS = "Movers"
private const val TAB_INDICES = "Indices"
private const val TAB_CAPTURED = "Captured"

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
    alignment: String
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
            modifier = Modifier.padding(10.dp),
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
    onClick: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MarksyTheme.Surface),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MarksyTheme.BorderGlow, RoundedCornerShape(14.dp)),
        onClick = onClick
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
                Text(insight.status, color = MarksyTheme.TextMuted, fontSize = 10.sp)
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
