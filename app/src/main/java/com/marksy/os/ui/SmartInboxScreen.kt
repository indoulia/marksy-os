package com.marksy.os.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import com.marksy.os.intelligence.PersonalLearning
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
    actions: InboxActions = InboxActions(),
    learningProfile: PersonalLearning.Profile = PersonalLearning.Profile.EMPTY
) {
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var actionThreadKey by rememberSaveable { mutableStateOf<String?>(null) }

    val filter = SmartInboxModel.Filter.entries.firstOrNull { it.name == selectedFilterName }
        ?: SmartInboxModel.Filter.ALL

    // Re-evaluated every minute so snoozed threads reappear on time without new data arriving.
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            kotlinx.coroutines.delay(60_000)
            value = System.currentTimeMillis()
        }
    }
    val inbox = remember(events, filter, searchQuery, now, learningProfile) {
        SmartInboxModel.inbox(events, filter, searchQuery, now, learningProfile)
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
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(MarksyTheme.Surface)
                        .border(1.dp, MarksyTheme.BorderGlow, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.Tune,
                        contentDescription = "Filter",
                        tint = MarksyTheme.PrimaryEmerald,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            // Search Bar Input
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
                placeholder = { Text("Search notifications...", color = MarksyTheme.TextMuted, fontSize = 13.sp) },
                leadingIcon = {
                    Icon(
                        Icons.Default.Search,
                        contentDescription = "Search",
                        tint = MarksyTheme.TextSecondary,
                        modifier = Modifier.size(18.dp)
                    )
                },
                singleLine = true,
                shape = RoundedCornerShape(25.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = MarksyTheme.Surface,
                    unfocusedContainerColor = MarksyTheme.Surface,
                    focusedBorderColor = MarksyTheme.PrimaryEmerald,
                    unfocusedBorderColor = MarksyTheme.BorderGlow,
                    focusedTextColor = MarksyTheme.TextPrimary,
                    unfocusedTextColor = MarksyTheme.TextPrimary
                )
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
            if (inbox.isEmpty) {
                item {
                    EmptyState(
                        "Nothing here yet.",
                        "New notifications matching this filter will appear here."
                    )
                }
            } else {
                inbox.sections.forEach { (bucket, threads) ->
                    if (threads.isEmpty()) return@forEach
                    item(key = "header-${bucket.name}") {
                        Text(
                            "${bucket.label} · ${threads.size}",
                            color = MarksyTheme.TextSecondary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    }
                    items(threads, key = { "t-" + it.key }) { thread ->
                        InboxNotificationCard(
                            thread = thread,
                            onClick = {
                                actions.markSeen(thread.allIds)
                                onEventSelected(thread.latest)
                            },
                            onLongClick = { actionThreadKey = thread.key }
                        )
                    }
                }
            }
            if (inbox.snoozedCount > 0) {
                item(key = "snoozed") {
                    Text(
                        "${inbox.snoozedCount} snoozed",
                        color = MarksyTheme.TextMuted,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(vertical = 6.dp)
                    )
                }
            }
        }
    }

    val actionThread = actionThreadKey?.let { key -> inbox.sections.values.flatten().firstOrNull { it.key == key } }
    if (actionThread != null) {
        ThreadActionsDialog(actionThread, actions, onDismiss = { actionThreadKey = null })
    }
}

/** Thread-level inbox actions; each receives every row id the thread represents. */
data class InboxActions(
    val markSeen: (List<Long>) -> Unit = {},
    val resolve: (List<Long>) -> Unit = {},
    val reopen: (List<Long>) -> Unit = {},
    val snooze: (List<Long>, Long) -> Unit = { _, _ -> },
    val archive: (List<Long>) -> Unit = {},
    val prefer: (PersonalLearning.Subject, PersonalLearning.Preference?) -> Unit = { _, _ -> }
)

@Composable
private fun ThreadActionsDialog(
    thread: SmartInboxModel.InboxThread,
    actions: InboxActions,
    onDismiss: () -> Unit
) {
    fun run(block: () -> Unit) { block(); onDismiss() }
    val ids = thread.allIds
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MarksyTheme.Surface,
        title = { Text(thread.latest.title.ifBlank { thread.latest.sourceName }, color = MarksyTheme.TextPrimary, fontSize = 16.sp, maxLines = 2) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Why am I seeing this?", color = MarksyTheme.PrimaryEmerald, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                thread.why.take(8).forEach { Text("• $it", color = MarksyTheme.TextSecondary, fontSize = 12.sp) }
                Spacer(Modifier.height(8.dp))
                if (thread.unread) TextButton(onClick = { run { actions.markSeen(ids) } }) { Text("Mark read") }
                if (thread.bucket == SmartInboxModel.Bucket.RESOLVED) {
                    TextButton(onClick = { run { actions.reopen(ids) } }) { Text("Reopen") }
                } else {
                    TextButton(onClick = { run { actions.resolve(ids) } }) { Text("Mark resolved") }
                }
                TextButton(onClick = { run { actions.snooze(ids, System.currentTimeMillis() + 60 * 60 * 1000L) } }) { Text("Snooze 1 hour") }
                TextButton(onClick = { run { actions.snooze(ids, nextMorningMillis()) } }) { Text("Snooze until tomorrow 9:00") }
                TextButton(onClick = { run { actions.archive(ids) } }) { Text("Archive") }
                // Explicit corrections (EPIC-012) outrank anything learned.
                PersonalLearning.subjectsOf(thread.latest)
                    .filter { it.type != PersonalLearning.SubjectType.CATEGORY }
                    .forEach { subject ->
                        TextButton(onClick = { run { actions.prefer(subject, PersonalLearning.Preference.ALWAYS_IMPORTANT) } }) { Text("Always important: ${subject.label}") }
                        TextButton(onClick = { run { actions.prefer(subject, PersonalLearning.Preference.LESS_IMPORTANT) } }) { Text("Less from ${subject.label}") }
                    }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}

private fun nextMorningMillis(): Long {
    val zone = java.time.ZoneId.systemDefault()
    return java.time.LocalDate.now(zone).plusDays(1).atTime(9, 0).atZone(zone).toInstant().toEpochMilli()
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun InboxNotificationCard(
    thread: SmartInboxModel.InboxThread,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val event = thread.latest
    val (appIcon, iconBg, pillLabel, pillText, pillBg) = resolveSourceStyle(event)

    Card(
        colors = CardDefaults.cardColors(containerColor = MarksyTheme.Surface),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MarksyTheme.BorderGlow, RoundedCornerShape(16.dp))
            .clip(RoundedCornerShape(16.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick, onLongClickLabel = "Thread actions")
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
                    Text(
                        event.sourceName.ifBlank { "System" },
                        color = MarksyTheme.TextPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        formatInboxTime(event.postedAt),
                        color = MarksyTheme.TextMuted,
                        fontSize = 10.sp
                    )
                }

                Spacer(Modifier.height(4.dp))

                Text(
                    event.title.ifBlank { "Notification event" },
                    color = if (thread.unread) MarksyTheme.TextPrimary else MarksyTheme.TextSecondary,
                    fontSize = 12.sp,
                    fontWeight = if (thread.unread) FontWeight.Bold else FontWeight.Medium,
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
                val meta = buildList {
                    if (thread.count > 1) add("${thread.count} in thread")
                    if (thread.duplicates.isNotEmpty()) add("+${thread.duplicates.size} duplicate")
                    if (thread.sources.size > 1) add(thread.sources.joinToString(" · "))
                }
                if (meta.isNotEmpty()) {
                    Text(
                        meta.joinToString("  ·  "),
                        color = MarksyTheme.PrimaryEmerald,
                        fontSize = 10.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 4.dp)
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
