package com.marksy.os.ui

import com.marksy.os.MarksyFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
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
        contentPadding = PaddingValues(start = MarksySpace.Gutter, end = MarksySpace.Gutter, top = MarksySpace.Tight, bottom = MarksySpace.Gutter),
        verticalArrangement = Arrangement.spacedBy(MarksySpace.ListGap)
    ) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(MarksySpace.Gap)) {
                HomePeriod.entries.forEach { option ->
                    Pill(option.label, selected = option == period) { period = option }
                }
            }
        }

        item {
            MarksyCard {
                Text("Notification Breakdown", color = MarksyTheme.TextPrimary, style = MarksyType.Lead, fontWeight = FontWeight.Bold)
                if (breakdown.isEmpty()) {
                    InlineEmpty("No notifications in this period")
                } else {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        DonutChart(breakdown.map { it.second to categoryColor(it.first) }, periodEvents.size)
                        Spacer(Modifier.width(MarksySpace.Gutter))
                        Column(verticalArrangement = Arrangement.spacedBy(MarksySpace.Tight)) {
                            breakdown.forEach { (category, count) ->
                                BreakdownLegendRow(categoryLabel(category), MarksyFormat.number(count.toDouble(), 0), categoryColor(category)) {
                                    onCategorySelected(categoryLabel(category))
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
                if (movers.isNotEmpty()) parts += "Marksy movers: " + movers.joinToString { "${it.symbol} ${MarksyFormat.percent(it.changePct, 1)}" } + "."
                add(Triple("Market", parts.joinToString(" "), "TRADING"))
            }
            InsightsModel.categoryInsight(periodEvents, "MESSAGES", "message", "messages", now)?.let { add(Triple("Messages", it, "MESSAGES")) }
            InsightsModel.categoryInsight(periodEvents, "EMAIL", "email", "emails", now)?.let { add(Triple("Email", it, "EMAIL")) }
            InsightsModel.categoryInsight(periodEvents, "WORK", "work notification", "work notifications", now)?.let { add(Triple("Work", it, "WORK")) }
            InsightsModel.categoryInsight(periodEvents, "DELIVERY", "delivery update", "delivery updates", now)?.let { add(Triple("Delivery", it, "DELIVERY")) }
        }

        if (insights.isNotEmpty()) {
            item { SectionLabel("Insights") }
            items(insights.size) { i ->
                val (title, description, category) = insights[i]
                AiInsightCard(title, description, categoryIcon(category), categoryColor(category), MarksyTheme.SurfaceRaised)
            }
        }

        if (observations.isNotEmpty()) {
            item {
                MarksyCard {
                    Text("Patterns", color = MarksyTheme.TextPrimary, style = MarksyType.Subhead, fontWeight = FontWeight.Bold)
                    observations.forEach { Text("• $it", color = MarksyTheme.TextSecondary, style = MarksyType.Small) }
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
            Text("$total", color = MarksyTheme.TextPrimary, style = MarksyType.Title)
            Text("Total", color = MarksyTheme.TextMuted, style = MarksyType.Caption)
        }
    }
}

private fun categoryLabel(category: String): String =
    SmartInboxModel.Filter.entries.firstOrNull { it.category == category }?.label
        ?: category.lowercase().replaceFirstChar { it.uppercase() }

private fun categoryColor(category: String): Color = when (category) {
    "TRADING" -> MarksyTheme.PrimaryEmerald
    "MESSAGES" -> MarksyTheme.SecondaryCyan
    "EMAIL" -> MarksyTheme.EmailBlue
    "BANKING", "PAYMENTS", "BILLS" -> MarksyTheme.BlueFinance
    "DELIVERY" -> MarksyTheme.OrangeDelivery
    "WORK" -> MarksyTheme.YellowImportant
    "PROMOTIONS" -> MarksyTheme.PurpleWork
    "MARKET" -> MarksyTheme.PrimaryDark
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
    Row(Modifier.clip(MarksyShape.Chip).clickable(onClick = onClick).padding(vertical = MarksySpace.Hair), verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.size(MarksySize.Dot).clip(CircleShape).background(color))
        Spacer(Modifier.width(MarksySpace.Gap))
        Text(label, color = MarksyTheme.TextSecondary, style = MarksyType.Small, modifier = Modifier.width(84.dp))
        Text(count, color = MarksyTheme.TextPrimary, style = MarksyType.Small, fontWeight = FontWeight.Bold)
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
    MarksyCard {
        Row(verticalAlignment = Alignment.Top) {
            Box(
                modifier = Modifier
                    .size(MarksySize.Avatar)
                    .clip(CircleShape)
                    .background(bgColor),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = iconColor, modifier = Modifier.size(MarksySize.Icon))
            }
            Spacer(Modifier.width(MarksySpace.CardPadding))
            Column {
                Text(title, color = MarksyTheme.TextPrimary, style = MarksyType.Subhead, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(MarksySpace.Tight))
                Text(description, color = MarksyTheme.TextSecondary, style = MarksyType.Small)
            }
        }
    }
}
