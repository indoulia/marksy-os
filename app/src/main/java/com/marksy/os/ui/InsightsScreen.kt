package com.marksy.os.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.marksy.os.data.local.NotificationEventEntity
import com.marksy.os.intelligence.InsightsModel
import com.marksy.os.intelligence.HomePeriod
import com.marksy.os.intelligence.SmartInboxModel
import com.marksy.os.gateway.MarketState
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke

@Composable
fun InsightsScreen(
    events: List<NotificationEventEntity>,
    padding: PaddingValues,
    market: MarketState = MarketState.Loading,
    onCategorySelected: (String) -> Unit = {}
) {
    var period by remember { mutableStateOf(HomePeriod.TODAY) }
    val now = remember(events, period) { System.currentTimeMillis() }
    val periodEvents = remember(events, period) { InsightsModel.inPeriod(events, period, now) }
    val breakdown = remember(events, period) { InsightsModel.breakdown(events, period, now) }
    val observations = remember(periodEvents) { InsightsModel.from(periodEvents, now).observations }
    val snapshot = (market as? MarketState.Loaded)?.snapshot

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MarksyTheme.Background)
            .padding(padding),
        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 4.dp, bottom = 18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HomePeriod.entries.forEach { option ->
                    val isSelected = option == period
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .background(if (isSelected) MarksyTheme.PrimaryEmerald else MarksyTheme.Surface)
                            .border(1.dp, if (isSelected) MarksyTheme.PrimaryEmerald else MarksyTheme.BorderGlow, RoundedCornerShape(20.dp))
                            .clickable { period = option }
                            .padding(horizontal = 16.dp, vertical = 7.dp)
                    ) {
                        Text(
                            option.label,
                            color = if (isSelected) Color.Black else MarksyTheme.TextSecondary,
                            fontSize = 12.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                        )
                    }
                }
            }
        }

        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MarksyTheme.Surface),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, MarksyTheme.BorderGlow, RoundedCornerShape(16.dp))
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("Notification Breakdown", color = MarksyTheme.TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(12.dp))
                    if (breakdown.isEmpty()) {
                        Text("No notifications in this period.", color = MarksyTheme.TextSecondary, fontSize = 12.sp)
                    } else {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            DonutChart(breakdown.map { it.second to categoryColor(it.first) }, periodEvents.size)
                            Spacer(Modifier.width(20.dp))
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                breakdown.forEach { (category, count) ->
                                    BreakdownLegendRow(categoryLabel(category), count.toString(), categoryColor(category)) {
                                        onCategorySelected(categoryLabel(category))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        val insights = buildList {
            val tradingCount = periodEvents.count { it.isTrading }
            val movers = snapshot?.gainers.orEmpty().take(2) + snapshot?.losers.orEmpty().take(1)
            if (tradingCount > 0 || movers.isNotEmpty()) {
                val parts = mutableListOf<String>()
                if (tradingCount > 0) parts += "$tradingCount trading notification${if (tradingCount == 1) "" else "s"}."
                if (movers.isNotEmpty()) parts += "Marksy movers: " + movers.joinToString { "${it.symbol} ${String.format(java.util.Locale.US, "%+.1f%%", it.changePct)}" } + "."
                add(Triple("Market", parts.joinToString(" "), "TRADING"))
            }
            InsightsModel.categoryInsight(periodEvents, "MESSAGES", "message", "messages", now)?.let { add(Triple("Messages", it, "MESSAGES")) }
            InsightsModel.categoryInsight(periodEvents, "EMAIL", "email", "emails", now)?.let { add(Triple("Email", it, "EMAIL")) }
            InsightsModel.categoryInsight(periodEvents, "WORK", "work notification", "work notifications", now)?.let { add(Triple("Work", it, "WORK")) }
            InsightsModel.categoryInsight(periodEvents, "DELIVERY", "delivery update", "delivery updates", now)?.let { add(Triple("Delivery", it, "DELIVERY")) }
        }

        if (insights.isNotEmpty()) {
            item { Text("Insights", color = MarksyTheme.TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold) }
            items(insights.size) { i ->
                val (title, description, category) = insights[i]
                AiInsightCard(title, description, categoryIcon(category), categoryColor(category), MarksyTheme.SurfaceRaised)
            }
        }

        if (observations.isNotEmpty()) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MarksyTheme.Surface),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth().border(1.dp, MarksyTheme.BorderGlow, RoundedCornerShape(14.dp))
                ) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Patterns", color = MarksyTheme.TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        observations.forEach { Text("• $it", color = MarksyTheme.TextSecondary, fontSize = 12.sp, lineHeight = 16.sp) }
                    }
                }
            }
        }
    }
}

@Composable
private fun DonutChart(slices: List<Pair<Int, Color>>, total: Int) {
    val track = MarksyTheme.SurfaceRaised
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(96.dp)) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = Stroke(width = 12.dp.toPx())
            val inset = stroke.width / 2
            val arcSize = Size(size.width - stroke.width, size.height - stroke.width)
            drawArc(track, 0f, 360f, useCenter = false, topLeft = Offset(inset, inset), size = arcSize, style = stroke)
            val sum = slices.sumOf { it.first }.coerceAtLeast(1)
            var start = -90f
            slices.forEach { (count, color) ->
                val sweep = 360f * count / sum
                drawArc(color, start, sweep - 1.5f, useCenter = false, topLeft = Offset(inset, inset), size = arcSize, style = stroke)
                start += sweep
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("$total", color = MarksyTheme.TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text("Total", color = MarksyTheme.TextMuted, fontSize = 10.sp)
        }
    }
}

private fun categoryLabel(category: String): String =
    SmartInboxModel.Filter.entries.firstOrNull { it.category == category }?.label
        ?: category.lowercase().replaceFirstChar { it.uppercase() }

private fun categoryColor(category: String): Color = when (category) {
    "TRADING" -> MarksyTheme.PrimaryEmerald
    "MESSAGES" -> MarksyTheme.SecondaryCyan
    "EMAIL" -> Color(0xFF82B1FF)
    "BANKING", "PAYMENTS", "BILLS" -> MarksyTheme.BlueFinance
    "DELIVERY" -> MarksyTheme.OrangeDelivery
    "WORK" -> MarksyTheme.YellowImportant
    "PROMOTIONS" -> Color(0xFFCE93D8)
    else -> MarksyTheme.TextMuted
}

private fun categoryIcon(category: String): ImageVector = when (category) {
    "TRADING" -> Icons.Default.ShowChart
    "MESSAGES" -> Icons.Default.Chat
    "EMAIL" -> Icons.Default.Email
    "DELIVERY" -> Icons.Default.LocalShipping
    "WORK" -> Icons.Default.Work
    else -> Icons.Default.Notifications
}

@Composable
private fun BreakdownLegendRow(label: String, count: String, color: Color, onClick: () -> Unit) {
    Row(Modifier.clip(RoundedCornerShape(6.dp)).clickable(onClick = onClick).padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(8.dp))
        Text(label, color = MarksyTheme.TextSecondary, fontSize = 12.sp, modifier = Modifier.width(84.dp))
        Text(count, color = MarksyTheme.TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun AiInsightCard(
    title: String,
    description: String,
    icon: ImageVector,
    iconColor: Color,
    bgColor: Color
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
            verticalAlignment = Alignment.Top
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(bgColor),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = iconColor, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column {
                Text(title, color = MarksyTheme.TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(3.dp))
                Text(description, color = MarksyTheme.TextSecondary, fontSize = 12.sp, lineHeight = 16.sp)
            }
        }
    }
}
