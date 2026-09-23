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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.marksy.os.data.local.DeliveryState
import com.marksy.os.data.local.NotificationEventEntity
import com.marksy.os.intelligence.DashboardSnapshot
import com.marksy.os.intelligence.EventIntelligence
import com.marksy.os.intelligence.dashboardAgeLabel

@Composable
fun DashboardScreen(
    snapshot: DashboardSnapshot,
    events: List<NotificationEventEntity>,
    onEventSelected: (NotificationEventEntity) -> Unit,
    onCategorySelected: (String) -> Unit = {},
    onOpenTimeline: () -> Unit = {},
    onOpenCalendar: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var selectedTimeFilter by remember { mutableStateOf("Today") }

    LazyColumn(
        modifier = modifier.background(MarksyTheme.Background),
        contentPadding = PaddingValues(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Column(Modifier.padding(horizontal = 18.dp, vertical = 12.dp)) {
                // Header Branding
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(
                                    Brush.linearGradient(
                                        listOf(
                                            MarksyTheme.PrimaryEmerald,
                                            MarksyTheme.AccentGreen
                                        )
                                    )
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "M",
                                color = Color.Black,
                                fontWeight = FontWeight.Black,
                                fontSize = 18.sp
                            )
                        }
                        Spacer(Modifier.width(10.dp))
                        Text(
                            "MARKSY",
                            color = MarksyTheme.TextPrimary,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.2.sp
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "OS",
                            color = MarksyTheme.PrimaryEmerald,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.2.sp
                        )
                        Spacer(Modifier.width(8.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .background(MarksyTheme.BadgeTradingBg)
                                .border(1.dp, MarksyTheme.PrimaryEmerald, RoundedCornerShape(12.dp))
                                .padding(horizontal = 7.dp, vertical = 2.dp)
                        ) {
                            Text(
                                "V2",
                                color = MarksyTheme.PrimaryEmerald,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(MarksyTheme.SurfaceRaised)
                            .border(1.dp, MarksyTheme.BorderGlow, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.Person,
                            contentDescription = "Profile",
                            tint = MarksyTheme.PrimaryEmerald,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                Spacer(Modifier.height(14.dp))

                // Time Filter Pills
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("Today", "This Week", "All Time").forEach { filter ->
                        val isSelected = filter == selectedTimeFilter
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(20.dp))
                                .background(if (isSelected) MarksyTheme.PrimaryEmerald else MarksyTheme.Surface)
                                .border(
                                    1.dp,
                                    if (isSelected) MarksyTheme.PrimaryEmerald else MarksyTheme.BorderGlow,
                                    RoundedCornerShape(20.dp)
                                )
                                .clickable { selectedTimeFilter = filter }
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

                Spacer(Modifier.height(16.dp))

                // Greeting & Notification Counter Card
                Card(
                    colors = CardDefaults.cardColors(containerColor = MarksyTheme.Surface),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, MarksyTheme.BorderGlow, RoundedCornerShape(16.dp))
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(28.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFF2B2200)),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("☀️", fontSize = 14.sp)
                            }
                            Spacer(Modifier.width(10.dp))
                            Column {
                                Text(
                                    "Good Morning",
                                    color = MarksyTheme.TextPrimary,
                                    fontSize = 17.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    "Let's make it a productive day!",
                                    color = MarksyTheme.TextSecondary,
                                    fontSize = 12.sp
                                )
                            }
                        }

                        Spacer(Modifier.height(16.dp))

                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Row(verticalAlignment = Alignment.Bottom) {
                                    Text(
                                        "${snapshot.totalEvents}",
                                        color = MarksyTheme.TextPrimary,
                                        fontSize = 36.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        "Notifications",
                                        color = MarksyTheme.TextSecondary,
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Medium,
                                        modifier = Modifier.padding(bottom = 6.dp)
                                    )
                                }
                                Text(
                                    "↘ -42% vs yesterday",
                                    color = MarksyTheme.PrimaryEmerald,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }

                            // Mini Bar Sparkline
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                verticalAlignment = Alignment.Bottom,
                                modifier = Modifier.height(36.dp)
                            ) {
                                listOf(18, 28, 14, 32, 24, 42, 20).forEach { height ->
                                    Box(
                                        modifier = Modifier
                                            .width(5.dp)
                                            .height(height.dp)
                                            .clip(RoundedCornerShape(3.dp))
                                            .background(
                                                if (height > 30) MarksyTheme.PrimaryEmerald else MarksyTheme.BorderGlow
                                            )
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    QuickAccessButton("Timeline", Icons.Default.Timeline, onOpenTimeline, Modifier.weight(1f))
                    QuickAccessButton("Calendar", Icons.Default.CalendarMonth, onOpenCalendar, Modifier.weight(1f))
                }

                Spacer(Modifier.height(14.dp))

                // Category Quick Cards Grid
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        CategoryGridCard(
                            title = "Trading",
                            count = snapshot.tradingEvents,
                            subtitle = "4 high priority",
                            icon = Icons.Default.ShowChart,
                            iconColor = MarksyTheme.PrimaryEmerald,
                            bgColor = MarksyTheme.BadgeTradingBg,
                            onClick = { onCategorySelected("Trading") },
                            modifier = Modifier.weight(1f)
                        )
                        CategoryGridCard(
                            title = "Important",
                            count = snapshot.importantEvents,
                            subtitle = "Requires action",
                            icon = Icons.Default.Bolt,
                            iconColor = MarksyTheme.YellowImportant,
                            bgColor = MarksyTheme.BadgeImportantBg,
                            onClick = { onCategorySelected("Important") },
                            modifier = Modifier.weight(1f)
                        )
                        CategoryGridCard(
                            title = "Messages",
                            count = snapshot.categoryCounts["MESSAGES"] ?: 0,
                            subtitle = "2 important",
                            icon = Icons.Default.Chat,
                            iconColor = MarksyTheme.SecondaryCyan,
                            bgColor = MarksyTheme.BadgeFinanceBg,
                            onClick = { onCategorySelected("Messages") },
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        CategoryGridCard(
                            title = "Emails",
                            count = snapshot.categoryCounts["EMAIL"] ?: 0,
                            subtitle = "Gmail & Outlook",
                            icon = Icons.Default.Email,
                            iconColor = Color(0xFF82B1FF),
                            bgColor = Color(0xFF0F1B2E),
                            onClick = { onCategorySelected("Emails") },
                            modifier = Modifier.weight(1f)
                        )
                        CategoryGridCard(
                            title = "Banking",
                            count = snapshot.categoryCounts["BANKING"] ?: 0,
                            subtitle = "1 transaction",
                            icon = Icons.Default.AccountBalance,
                            iconColor = MarksyTheme.BlueFinance,
                            bgColor = MarksyTheme.BadgeFinanceBg,
                            onClick = { onCategorySelected("Banking") },
                            modifier = Modifier.weight(1f)
                        )
                        CategoryGridCard(
                            title = "Delivery",
                            count = snapshot.categoryCounts["DELIVERY"] ?: 0,
                            subtitle = "Arriving tomorrow",
                            icon = Icons.Default.LocalShipping,
                            iconColor = MarksyTheme.OrangeDelivery,
                            bgColor = MarksyTheme.BadgeDeliveryBg,
                            onClick = { onCategorySelected("Delivery") },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                Spacer(Modifier.height(14.dp))

                // Market Pulse Card
                MarketPulseCard()

                Spacer(Modifier.height(14.dp))

                // AI Summary Card
                AiSummaryBanner()
            }
        }

        item { SectionTitle("AI Attention Required") }

        if (snapshot.topAttention.isEmpty()) {
            item {
                EmptyDashboardCard(
                    "Nothing needs attention yet.",
                    "Marksy OS will surface high-priority events here."
                )
            }
        } else {
            // Keys are namespaced per section: the same event can be in both Attention and Latest Activity.
            items(snapshot.topAttention, key = { "attention-${it.eventId}" }) { result ->
                events.firstOrNull { it.id == result.eventId }?.let { event ->
                    AttentionCard(result, event, snapshot.generatedAt) { onEventSelected(event) }
                }
            }
        }

        item { SectionTitle("Latest Activity") }
        if (events.isEmpty()) {
            item {
                EmptyDashboardCard(
                    "Everything is quiet.",
                    "Captured events will appear here when they arrive."
                )
            }
        } else {
            items(events.take(5), key = { "latest-${it.id}" }) { event ->
                CompactEventCard(event, snapshot.generatedAt) { onEventSelected(event) }
            }
        }
    }
}

@Composable
private fun CategoryGridCard(
    title: String,
    count: Int,
    subtitle: String,
    icon: ImageVector,
    iconColor: Color,
    bgColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MarksyTheme.Surface),
        shape = RoundedCornerShape(14.dp),
        onClick = onClick,
        modifier = modifier.border(1.dp, MarksyTheme.BorderGlow, RoundedCornerShape(14.dp))
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .clip(CircleShape)
                        .background(bgColor),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(icon, contentDescription = title, tint = iconColor, modifier = Modifier.size(16.dp))
                }
                Text("$count", color = MarksyTheme.TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(8.dp))
            Text(title, color = MarksyTheme.TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = MarksyTheme.TextMuted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun QuickAccessButton(label: String, icon: ImageVector, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MarksyTheme.Surface),
        shape = RoundedCornerShape(14.dp),
        onClick = onClick,
        modifier = modifier.border(1.dp, MarksyTheme.BorderGlow, RoundedCornerShape(14.dp))
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, contentDescription = null, tint = MarksyTheme.PrimaryEmerald, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(label, color = MarksyTheme.TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Icon(Icons.Default.ChevronRight, contentDescription = null, tint = MarksyTheme.TextMuted, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun MarketPulseCard() {
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.TrendingUp,
                        contentDescription = null,
                        tint = MarksyTheme.PrimaryEmerald,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Market Pulse", color = MarksyTheme.TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                }
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

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text("NIFTY 50", color = MarksyTheme.TextSecondary, fontSize = 11.sp)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("25,143", color = MarksyTheme.TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.width(6.dp))
                        Text("+0.8%", color = MarksyTheme.PrimaryEmerald, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
                Column {
                    Text("SENSEX", color = MarksyTheme.TextSecondary, fontSize = 11.sp)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("82,391", color = MarksyTheme.TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.width(6.dp))
                        Text("+0.7%", color = MarksyTheme.PrimaryEmerald, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            Spacer(Modifier.height(10.dp))
            Divider(color = MarksyTheme.BorderGlow)
            Spacer(Modifier.height(10.dp))

            Text("3 new trading alerts • 12 other notifications", color = MarksyTheme.TextMuted, fontSize = 11.sp)
        }
    }
}

@Composable
private fun AiSummaryBanner() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(
                Brush.linearGradient(
                    listOf(
                        Color(0xFF0F261C),
                        Color(0xFF0C1B13)
                    )
                )
            )
            .border(1.dp, MarksyTheme.PrimaryEmerald, RoundedCornerShape(16.dp))
            .padding(16.dp)
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(MarksyTheme.BadgeTradingBg),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.AutoAwesome,
                        contentDescription = null,
                        tint = MarksyTheme.PrimaryEmerald,
                        modifier = Modifier.size(16.dp)
                    )
                }
                Spacer(Modifier.width(10.dp))
                Text("AI Summary", color = MarksyTheme.PrimaryEmerald, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "Reliance and ICICI Bank showed strong activity this morning. You have 2 important emails and 1 delivery arriving tomorrow.",
                color = MarksyTheme.TextPrimary,
                fontSize = 13.sp,
                lineHeight = 18.sp
            )
        }
    }
}

@Composable
private fun SectionTitle(title: String) = Text(
    title,
    color = MarksyTheme.TextPrimary,
    fontSize = 18.sp,
    fontWeight = FontWeight.Bold,
    modifier = Modifier.padding(horizontal = 18.dp, vertical = 4.dp)
)

@Composable
private fun AttentionCard(
    result: EventIntelligence.Result,
    event: NotificationEventEntity,
    nowMillis: Long,
    onClick: () -> Unit
) = Card(
    colors = CardDefaults.cardColors(
        containerColor = if (result.attentionLevel == EventIntelligence.AttentionLevel.CRITICAL) MarksyTheme.BadgeUrgentBg else MarksyTheme.Surface
    ),
    shape = RoundedCornerShape(14.dp),
    modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 18.dp)
        .border(1.dp, MarksyTheme.BorderGlow, RoundedCornerShape(14.dp)),
    onClick = onClick
) {
    Column(Modifier.padding(14.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                event.sourceName.ifBlank { "Unknown source" },
                color = if (event.isTrading) MarksyTheme.PrimaryEmerald else MarksyTheme.TextSecondary,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(
                        if (result.attentionLevel == EventIntelligence.AttentionLevel.CRITICAL) MarksyTheme.BadgeUrgentBg else MarksyTheme.BadgeTradingBg
                    )
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            ) {
                Text(
                    attentionLabel(result),
                    color = if (result.attentionLevel == EventIntelligence.AttentionLevel.CRITICAL) MarksyTheme.RedUrgent else MarksyTheme.PrimaryEmerald,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            event.title.ifBlank { "Untitled notification" },
            color = MarksyTheme.TextPrimary,
            fontWeight = FontWeight.Medium,
            fontSize = 14.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(6.dp))
        Text(
            result.reasons.take(2).joinToString(" • "),
            color = MarksyTheme.TextMuted,
            fontSize = 11.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            "${result.attentionScore}/100 • ${dashboardAgeLabel(event.postedAt, nowMillis)}",
            color = MarksyTheme.TextMuted,
            fontSize = 10.sp,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}

private fun attentionLabel(result: EventIntelligence.Result): String = when (result.attentionLevel) {
    EventIntelligence.AttentionLevel.CRITICAL -> "CRITICAL"
    EventIntelligence.AttentionLevel.HIGH -> "HIGH ATTENTION"
    EventIntelligence.AttentionLevel.NORMAL -> "NORMAL"
    EventIntelligence.AttentionLevel.LOW -> "LOW"
}

@Composable
private fun CompactEventCard(
    event: NotificationEventEntity,
    nowMillis: Long,
    onClick: () -> Unit
) = Card(
    colors = CardDefaults.cardColors(containerColor = MarksyTheme.Surface),
    shape = RoundedCornerShape(14.dp),
    modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 18.dp)
        .border(1.dp, MarksyTheme.BorderGlow, RoundedCornerShape(14.dp)),
    onClick = onClick
) {
    Column(Modifier.padding(13.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                event.sourceName.ifBlank { "Unknown source" },
                color = if (event.isTrading) MarksyTheme.PrimaryEmerald else MarksyTheme.TextSecondary,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (event.isTrading) {
                Text(
                    deliveryLabel(event.deliveryState),
                    color = MarksyTheme.PrimaryEmerald,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
        Text(
            event.title.ifBlank { "Untitled notification" },
            color = MarksyTheme.TextPrimary,
            fontSize = 13.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp)
        )
        Text(
            dashboardAgeLabel(event.postedAt, nowMillis),
            color = MarksyTheme.TextMuted,
            fontSize = 10.sp,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}

@Composable
private fun EmptyDashboardCard(title: String, description: String) = Card(
    colors = CardDefaults.cardColors(containerColor = MarksyTheme.Surface),
    shape = RoundedCornerShape(14.dp),
    modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 18.dp)
        .border(1.dp, MarksyTheme.BorderGlow, RoundedCornerShape(14.dp))
) {
    Column(Modifier.padding(16.dp)) {
        Text(title, color = MarksyTheme.TextPrimary, fontWeight = FontWeight.SemiBold)
        Text(description, color = MarksyTheme.TextSecondary, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
    }
}

private fun deliveryLabel(state: String): String = when (state) {
    DeliveryState.DELIVERED.name -> "MARKSY ✓"
    DeliveryState.PENDING.name -> "PENDING"
    DeliveryState.IN_FLIGHT.name -> "ANALYZING"
    DeliveryState.FAILED.name -> "FAILED"
    else -> "LOCAL"
}
