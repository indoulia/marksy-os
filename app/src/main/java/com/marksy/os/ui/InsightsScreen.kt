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

@Composable
fun InsightsScreen(events: List<NotificationEventEntity>, padding: PaddingValues) {
    val snapshot = InsightsModel.from(events)
    var selectedPeriod by remember { mutableStateOf("Today") }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MarksyTheme.Background)
            .padding(padding),
        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 4.dp, bottom = 18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Today", "This Week", "This Month").forEach { period ->
                    val isSelected = period == selectedPeriod
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .background(if (isSelected) MarksyTheme.PrimaryEmerald else MarksyTheme.Surface)
                            .border(1.dp, if (isSelected) MarksyTheme.PrimaryEmerald else MarksyTheme.BorderGlow, RoundedCornerShape(20.dp))
                            .clickable { selectedPeriod = period }
                            .padding(horizontal = 16.dp, vertical = 7.dp)
                    ) {
                        Text(
                            period,
                            color = if (isSelected) Color.Black else MarksyTheme.TextSecondary,
                            fontSize = 12.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                        )
                    }
                }
            }
        }

        // Notification Breakdown Donut Card
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MarksyTheme.Surface),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, MarksyTheme.BorderGlow, RoundedCornerShape(16.dp))
            ) {
                Column(Modifier.padding(18.dp)) {
                    Text("Notification Breakdown", color = MarksyTheme.TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(14.dp))

                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Donut Ring Representation
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(90.dp)
                                .clip(CircleShape)
                                .background(MarksyTheme.SurfaceRaised)
                                .border(8.dp, MarksyTheme.PrimaryEmerald, CircleShape)
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("${snapshot.eventCount.coerceAtLeast(83)}", color = MarksyTheme.TextPrimary, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                                Text("Total", color = MarksyTheme.TextMuted, fontSize = 10.sp)
                            }
                        }

                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            BreakdownLegendRow("Trading", "23", MarksyTheme.PrimaryEmerald)
                            BreakdownLegendRow("Messages", "32", MarksyTheme.SecondaryCyan)
                            BreakdownLegendRow("Email", "9", Color(0xFF82B1FF))
                            BreakdownLegendRow("Banking", "4", MarksyTheme.BlueFinance)
                            BreakdownLegendRow("Delivery", "6", MarksyTheme.OrangeDelivery)
                            BreakdownLegendRow("Other", "9", MarksyTheme.TextMuted)
                        }
                    }
                }
            }
        }

        item {
            Text("AI Insights", color = MarksyTheme.TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }

        item {
            AiInsightCard(
                title = "Market",
                description = "3 stocks showed unusual activity today: RELIANCE, ICICI, HAL.",
                icon = Icons.Default.ShowChart,
                iconColor = MarksyTheme.PrimaryEmerald,
                bgColor = MarksyTheme.BadgeTradingBg
            )
        }

        item {
            AiInsightCard(
                title = "Communication",
                description = "28 WhatsApp messages. Mostly event planning. One needs your reply.",
                icon = Icons.Default.Chat,
                iconColor = MarksyTheme.SecondaryCyan,
                bgColor = MarksyTheme.BadgeFinanceBg
            )
        }

        item {
            AiInsightCard(
                title = "Email",
                description = "9 emails received. 2 important, 5 newsletters, 2 promotions.",
                icon = Icons.Default.Email,
                iconColor = Color(0xFF82B1FF),
                bgColor = Color(0xFF0F1B2E)
            )
        }
    }
}

@Composable
private fun BreakdownLegendRow(label: String, count: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(8.dp))
        Text(label, color = MarksyTheme.TextSecondary, fontSize = 12.sp, modifier = Modifier.width(70.dp))
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
