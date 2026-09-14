package com.marksy.os.ui

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
    onInsightSelected: (TradingInsight) -> Unit = {}
) {
    var selectedFilter by remember { mutableStateOf("Signals") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MarksyTheme.Background)
            .padding(padding)
    ) {
        // Header
        Column(Modifier.padding(horizontal = 18.dp, vertical = 12.dp)) {
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
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(MarksyTheme.BadgeTradingBg)
                        .border(1.dp, MarksyTheme.PrimaryEmerald, RoundedCornerShape(12.dp))
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(MarksyTheme.PrimaryEmerald)
                        )
                        Spacer(Modifier.width(5.dp))
                        Text("LIVE", color = MarksyTheme.PrimaryEmerald, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // Filter Chips
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf("Signals", "Positions", "Watchlist", "News").forEach { filter ->
                    val isSelected = filter == selectedFilter
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .background(if (isSelected) MarksyTheme.PrimaryEmerald else MarksyTheme.Surface)
                            .border(
                                1.dp,
                                if (isSelected) MarksyTheme.PrimaryEmerald else MarksyTheme.BorderGlow,
                                RoundedCornerShape(20.dp)
                            )
                            .clickable { selectedFilter = filter }
                            .padding(horizontal = 16.dp, vertical = 7.dp)
                    ) {
                        Text(
                            filter,
                            color = if (isSelected) Color.Black else MarksyTheme.TextSecondary,
                            fontSize = 12.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                        )
                    }
                }
            }
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            // Featured Reliance Signal Card
            item {
                TradingSignalCard(
                    symbol = "RELIANCE",
                    price = "₹1,452.30",
                    change = "+2.8%",
                    signalType = "BUY",
                    headline = "Breakout confirmed with high volume",
                    entry = "₹1,450",
                    target = "₹1,488",
                    stopLoss = "₹1,435",
                    confidence = "89%",
                    alignment = "3 independent sources aligned in last 18 minutes."
                )
            }

            // Watch Signal Card
            item {
                TradingWatchCard(
                    symbol = "ICICI BANK",
                    price = "₹1,248.60",
                    change = "+1.6%",
                    badge = "Watch",
                    note = "Volume spike 2.8x average",
                    confidence = "76%"
                )
            }

            // Ticker Card
            item {
                TradingTickerCard(
                    symbol = "NIFTY 50",
                    price = "25,143.20",
                    change = "+0.8%"
                )
            }

            item {
                Spacer(Modifier.height(4.dp))
                Text(
                    "Captured Trading Activity",
                    color = MarksyTheme.TextPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
            }

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
        Column(Modifier.padding(16.dp)) {
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
                        Text(change, color = MarksyTheme.PrimaryEmerald, fontSize = 12.sp, fontWeight = FontWeight.Bold)
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

            Spacer(Modifier.height(10.dp))

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

            Spacer(Modifier.height(12.dp))

            // Details Grid
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MarksyTheme.SurfaceRaised)
                    .padding(12.dp),
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

            Spacer(Modifier.height(10.dp))

            // Alignment Banner
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(MarksyTheme.BadgeTradingBg)
                    .padding(10.dp)
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
private fun TradingWatchCard(
    symbol: String,
    price: String,
    change: String,
    badge: String,
    note: String,
    confidence: String
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MarksyTheme.Surface),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MarksyTheme.BorderGlow, RoundedCornerShape(16.dp))
    ) {
        Column(Modifier.padding(16.dp)) {
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
                                .background(MarksyTheme.BadgeFinanceBg),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("ℹ️", fontSize = 12.sp)
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(
                            symbol,
                            color = MarksyTheme.TextPrimary,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(top = 2.dp)
                    ) {
                        Text(price, color = MarksyTheme.TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.width(6.dp))
                        Text(change, color = MarksyTheme.PrimaryEmerald, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }

                // Watch Pill Button
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(Color(0xFF0288D1))
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                ) {
                    Text(badge, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }

            Spacer(Modifier.height(10.dp))

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(note, color = MarksyTheme.TextSecondary, fontSize = 12.sp)
                Text("Confidence: $confidence", color = MarksyTheme.PrimaryEmerald, fontSize = 11.sp, fontWeight = FontWeight.Bold)
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
            modifier = Modifier.padding(14.dp),
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
                Text(change, color = MarksyTheme.PrimaryEmerald, fontSize = 12.sp, fontWeight = FontWeight.Bold)
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
