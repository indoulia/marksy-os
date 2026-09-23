package com.marksy.os.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.marksy.os.data.local.NotificationEventEntity

@Composable
fun DailyDigestScreen(
    events: List<NotificationEventEntity> = emptyList(),
    padding: PaddingValues = PaddingValues()
) {
    val digest = DailyDigestModel.build(events)

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MarksyTheme.Background)
            .padding(padding),
        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 4.dp, bottom = 18.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Today • " + java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("d MMM", java.util.Locale.getDefault())),
                    color = MarksyTheme.TextSecondary,
                    fontSize = 13.sp
                )
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF2B2200)),
                    contentAlignment = Alignment.Center
                ) {
                    Text("☀️", fontSize = 18.sp)
                }
            }
        }

        // Today in 60 Seconds Card
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MarksyTheme.Surface),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, MarksyTheme.PrimaryEmerald, RoundedCornerShape(16.dp))
            ) {
                Column(Modifier.padding(18.dp)) {
                    Text(digest.title, color = MarksyTheme.TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(14.dp))

                    DigestItemRow(
                        icon = Icons.Default.Notifications,
                        iconColor = MarksyTheme.PrimaryEmerald,
                        bgColor = MarksyTheme.BadgeTradingBg,
                        text = "${digest.totalNotifications.coerceAtLeast(84)} notifications received"
                    )
                    DigestItemRow(
                        icon = Icons.Default.Bolt,
                        iconColor = MarksyTheme.YellowImportant,
                        bgColor = MarksyTheme.BadgeImportantBg,
                        text = "${digest.attentionEvents.size.coerceAtLeast(11)} required attention"
                    )
                    DigestItemRow(
                        icon = Icons.Default.ShowChart,
                        iconColor = MarksyTheme.PrimaryEmerald,
                        bgColor = MarksyTheme.BadgeTradingBg,
                        text = "${digest.tradingEvents.size.coerceAtLeast(4)} trading opportunities"
                    )
                    DigestItemRow(
                        icon = Icons.Default.AccountBalance,
                        iconColor = MarksyTheme.BlueFinance,
                        bgColor = MarksyTheme.BadgeFinanceBg,
                        text = "₹12,540 credited via UPI"
                    )
                    DigestItemRow(
                        icon = Icons.Default.Email,
                        iconColor = Color(0xFF82B1FF),
                        bgColor = Color(0xFF0F1B2E),
                        text = "2 emails pending reply"
                    )
                    DigestItemRow(
                        icon = Icons.Default.LocalShipping,
                        iconColor = MarksyTheme.OrangeDelivery,
                        bgColor = MarksyTheme.BadgeDeliveryBg,
                        text = "1 delivery arriving tomorrow"
                    )

                    Spacer(Modifier.height(16.dp))

                    Button(
                        onClick = { },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = MarksyTheme.PrimaryEmerald),
                        shape = RoundedCornerShape(25.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Share, contentDescription = null, tint = Color.Black, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Share Digest", color = Color.Black, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        // Tomorrow's Reminders Card
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MarksyTheme.Surface),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, MarksyTheme.BorderGlow, RoundedCornerShape(16.dp))
            ) {
                Column(Modifier.padding(18.dp)) {
                    Text("Tomorrow's Reminders", color = MarksyTheme.TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(12.dp))

                    ReminderRow(icon = Icons.Default.Email, text = "Reply to AGM email")
                    ReminderRow(icon = Icons.Default.ShowChart, text = "Watch Reliance opening range")
                    ReminderRow(icon = Icons.Default.LocalShipping, text = "Delivery between 1–3 PM")
                    ReminderRow(icon = Icons.Default.Groups, text = "Team call at 10 AM")
                }
            }
        }
    }
}

@Composable
private fun DigestItemRow(
    icon: ImageVector,
    iconColor: Color,
    bgColor: Color,
    text: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(bgColor),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = iconColor, modifier = Modifier.size(14.dp))
        }
        Spacer(Modifier.width(10.dp))
        Text(text, color = MarksyTheme.TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun ReminderRow(icon: ImageVector, text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = MarksyTheme.PrimaryEmerald, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(10.dp))
        Text(text, color = MarksyTheme.TextSecondary, fontSize = 13.sp)
    }
}
