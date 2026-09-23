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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.marksy.os.EmptyState
import com.marksy.os.data.local.DeliveryState
import com.marksy.os.data.local.NotificationEventEntity
import com.marksy.os.intelligence.SmartInboxModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SmartInboxScreen(
    events: List<NotificationEventEntity>,
    padding: PaddingValues,
    onEventSelected: (NotificationEventEntity) -> Unit,
    selectedFilterName: String,
    onFilterSelected: (String) -> Unit,
    onArchive: (NotificationEventEntity) -> Unit = {},
    onDelete: (NotificationEventEntity) -> Unit = {},
    onHide: (NotificationEventEntity) -> Unit = {}
) {
    var searchQuery by rememberSaveable { mutableStateOf("") }

    val filter = SmartInboxModel.Filter.entries.firstOrNull { it.name == selectedFilterName }
        ?: SmartInboxModel.Filter.ALL
    
    val filtered = remember(events, filter, searchQuery) {
        val base = SmartInboxModel.filter(events, filter)
        if (searchQuery.isBlank()) base
        else base.filter { 
            it.title.contains(searchQuery, ignoreCase = true) ||
            it.body.contains(searchQuery, ignoreCase = true) ||
            it.sourceName.contains(searchQuery, ignoreCase = true)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MarksyTheme.Background)
            .padding(padding)
    ) {
        // Header & Search Bar
        Column(Modifier.padding(horizontal = 18.dp, vertical = 12.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Smart Inbox",
                    color = MarksyTheme.TextPrimary,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold
                )
                Text("${filtered.size} shown", color = MarksyTheme.TextMuted, fontSize = 12.sp)
            }

            Spacer(Modifier.height(12.dp))

            // Search Bar Input
            CompactTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = "Search notifications...",
                leadingIcon = Icons.Default.Search,
                cornerRadius = 20.dp,
                trailing = if (searchQuery.isNotEmpty()) {
                    {
                        IconButton(onClick = { searchQuery = "" }, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Default.Close, contentDescription = "Clear search", tint = MarksyTheme.TextSecondary, modifier = Modifier.size(16.dp))
                        }
                    }
                } else null
            )

            Spacer(Modifier.height(12.dp))

            // Filter Chips Row
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SmartInboxModel.Filter.entries.forEach { item ->
                    val isSelected = filter == item
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .background(if (isSelected) MarksyTheme.PrimaryEmerald else MarksyTheme.Surface)
                            .border(
                                1.dp,
                                if (isSelected) MarksyTheme.PrimaryEmerald else MarksyTheme.BorderGlow,
                                RoundedCornerShape(20.dp)
                            )
                            .clickable { onFilterSelected(item.name) }
                            .padding(horizontal = 16.dp, vertical = 7.dp)
                    ) {
                        Text(
                            item.label,
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
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(bottom = 20.dp)
        ) {
            if (filtered.isEmpty()) {
                item {
                    EmptyState(
                        "Nothing here yet.",
                        "New notifications matching this filter will appear here."
                    )
                }
            } else {
                items(filtered, key = { it.id }) { event ->
                    SwipeActionsRow(onDelete = { onDelete(event) }, onArchive = { onArchive(event) }, onHide = { onHide(event) }) {
                        InboxNotificationCard(event = event) { onEventSelected(event) }
                    }
                }
            }
        }
    }
}

@Composable
private fun InboxNotificationCard(
    event: NotificationEventEntity,
    onClick: () -> Unit
) {
    val (appIcon, iconBg, pillLabel, pillText, pillBg) = resolveSourceStyle(event)

    Card(
        colors = CardDefaults.cardColors(containerColor = MarksyTheme.Surface),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MarksyTheme.BorderGlow, RoundedCornerShape(16.dp)),
        onClick = onClick
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.Top
        ) {
            // App Icon Circle
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(iconBg),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    appIcon,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
            }

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                        if (!event.isRead) {
                            Box(Modifier.size(7.dp).clip(CircleShape).background(MarksyTheme.PrimaryEmerald))
                            Spacer(Modifier.width(6.dp))
                        }
                        Text(
                            event.sourceName.ifBlank { "System" },
                            color = MarksyTheme.TextPrimary,
                            fontSize = 14.sp,
                            fontWeight = if (event.isRead) FontWeight.Normal else FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        if (event.kept) {
                            Spacer(Modifier.width(4.dp))
                            Icon(Icons.Default.Star, contentDescription = "Kept", tint = MarksyTheme.YellowImportant, modifier = Modifier.size(13.dp))
                        }
                        if (event.remindAt != null) {
                            Spacer(Modifier.width(4.dp))
                            Icon(Icons.Default.Alarm, contentDescription = "Reminder set", tint = MarksyTheme.PrimaryEmerald, modifier = Modifier.size(13.dp))
                        }
                    }
                    Text(
                        formatInboxTime(event.postedAt),
                        color = if (event.isRead) MarksyTheme.TextMuted else MarksyTheme.PrimaryEmerald,
                        fontSize = 10.sp,
                        fontWeight = if (event.isRead) FontWeight.Normal else FontWeight.Bold
                    )
                }

                Spacer(Modifier.height(4.dp))

                Text(
                    event.title.ifBlank { "Notification event" },
                    color = if (event.isRead) MarksyTheme.TextSecondary else MarksyTheme.TextPrimary,
                    fontSize = 12.sp,
                    fontWeight = if (event.isRead) FontWeight.Normal else FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                if (event.body.isNotBlank()) {
                    Text(
                        event.body,
                        color = MarksyTheme.TextMuted,
                        fontSize = 11.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }

            Spacer(Modifier.width(8.dp))

            // Pill Badge
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(pillBg)
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text(
                    pillLabel,
                    color = pillText,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

private data class SourceStyle(
    val icon: ImageVector,
    val iconBg: Color,
    val pillLabel: String,
    val pillText: Color,
    val pillBg: Color
)

private fun resolveSourceStyle(event: NotificationEventEntity): SourceStyle {
    val src = event.sourceName.lowercase()
    val title = event.title.lowercase()
    val category = event.category.uppercase()

    return when {
        event.isTrading || src.contains("zerodha") || src.contains("groww") || src.contains("upstox") -> SourceStyle(
            icon = Icons.Default.ShowChart,
            iconBg = Color(0xFFC62828),
            pillLabel = if (title.contains("breakout") || event.priority >= 80) "Urgent" else "Trading",
            pillText = if (title.contains("breakout") || event.priority >= 80) MarksyTheme.RedUrgent else MarksyTheme.PrimaryEmerald,
            pillBg = if (title.contains("breakout") || event.priority >= 80) MarksyTheme.BadgeUrgentBg else MarksyTheme.BadgeTradingBg
        )
        src.contains("whatsapp") -> SourceStyle(
            icon = Icons.Default.Chat,
            iconBg = Color(0xFF2E7D32),
            pillLabel = "Grouped",
            pillText = MarksyTheme.PrimaryEmerald,
            pillBg = MarksyTheme.BadgeTradingBg
        )
        src.contains("gmail") || src.contains("mail") || category == "WORK" -> SourceStyle(
            icon = Icons.Default.Email,
            iconBg = Color(0xFF1565C0),
            pillLabel = "Important",
            pillText = MarksyTheme.YellowImportant,
            pillBg = MarksyTheme.BadgeImportantBg
        )
        src.contains("icici") || src.contains("bank") || src.contains("upi") || category == "PAYMENTS" || category == "BANKING" -> SourceStyle(
            icon = Icons.Default.AccountBalance,
            iconBg = Color(0xFF0288D1),
            pillLabel = "Finance",
            pillText = MarksyTheme.BlueFinance,
            pillBg = MarksyTheme.BadgeFinanceBg
        )
        src.contains("swiggy") || src.contains("zomato") || category == "DELIVERY" -> SourceStyle(
            icon = Icons.Default.LocalShipping,
            iconBg = Color(0xFFE65100),
            pillLabel = "Delivery",
            pillText = MarksyTheme.OrangeDelivery,
            pillBg = MarksyTheme.BadgeDeliveryBg
        )
        else -> SourceStyle(
            icon = Icons.Default.Notifications,
            iconBg = Color(0xFF37474F),
            pillLabel = if (event.priority >= 70) "Priority" else "Notice",
            pillText = if (event.priority >= 70) MarksyTheme.PrimaryEmerald else MarksyTheme.TextSecondary,
            pillBg = MarksyTheme.SurfaceRaised
        )
    }
}

private fun formatInboxTime(timestamp: Long): String =
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestamp))
