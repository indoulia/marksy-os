package com.marksy.os.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.marksy.os.data.local.DeliveryState
import com.marksy.os.data.local.NotificationEventEntity
import com.marksy.os.intelligence.SmartInboxModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val InboxSurface = Color(0xFF101613)
private val InboxRaised = Color(0xFF151C18)
private val InboxPrimary = Color(0xFF72D49A)
private val InboxText = Color(0xFFE8F1EC)
private val InboxSecondary = Color(0xFF9AA9A1)
private val InboxMuted = Color(0xFF657169)

@Composable
fun SmartInboxScreen(events: List<NotificationEventEntity>, padding: PaddingValues, onEventSelected: (NotificationEventEntity) -> Unit) {
    var selectedFilterName by rememberSaveable { mutableStateOf(SmartInboxModel.Filter.ALL.name) }
    val filter = SmartInboxModel.Filter.entries.firstOrNull { it.name == selectedFilterName } ?: SmartInboxModel.Filter.ALL
    val filtered = SmartInboxModel.filter(events, filter)
    val sections = remember(filtered) { SmartInboxModel.section(filtered) }
    LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Text("Smart Inbox", color = InboxText, fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(5.dp))
            Text("Grouped by context. Ranked by what deserves attention.", color = InboxSecondary, fontSize = 13.sp)
            Spacer(Modifier.height(14.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SmartInboxModel.Filter.entries.forEach { item -> FilterChip(selected = filter == item, onClick = { selectedFilterName = item.name }, label = { Text(item.label) }) }
            }
        }
        if (filtered.isEmpty()) item { EmptyState("Nothing here yet.", "New notifications matching this filter will appear here.") }
        else {
            inboxSection("Needs attention", sections.needsAttention, onEventSelected)
            inboxSection("Recent", sections.recent, onEventSelected)
            inboxSection("Quiet", sections.quiet, onEventSelected)
        }
    }
}

private fun LazyListScope.inboxSection(title: String, threads: List<SmartInboxModel.Thread>, onEventSelected: (NotificationEventEntity) -> Unit) {
    if (threads.isEmpty()) return
    item(key = "header-$title") { Text(title.uppercase(Locale.ROOT), color = InboxMuted, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp, bottom = 2.dp)) }
    items(threads, key = { it.key }) { InboxThreadCard(it, onEventSelected) }
}

@Composable
private fun InboxThreadCard(thread: SmartInboxModel.Thread, onEventSelected: (NotificationEventEntity) -> Unit) {
    val event = thread.latest
    Card(colors = CardDefaults.cardColors(containerColor = if (thread.highestAttention >= 70) InboxRaised else InboxSurface), modifier = Modifier.fillMaxWidth(), onClick = { onEventSelected(event) }) {
        Column(Modifier.padding(14.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(event.sourceName.ifBlank { "Unknown source" }, color = if (event.isTrading) InboxPrimary else InboxSecondary, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text("${thread.highestAttention}/100", color = InboxPrimary, fontSize = 11.sp, modifier = Modifier.padding(start = 10.dp))
            }
            Spacer(Modifier.height(6.dp))
            Text(event.title.ifBlank { "Untitled notification" }, color = InboxText, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(7.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(thread.primaryReason, color = InboxSecondary, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text(formatInboxTime(event.postedAt), color = InboxMuted, fontSize = 11.sp, modifier = Modifier.padding(start = 10.dp))
            }
            if (thread.count > 1) Text("${thread.count} related events", color = InboxPrimary, fontSize = 11.sp, modifier = Modifier.padding(top = 7.dp))
            if (event.isTrading && event.deliveryState != DeliveryState.NOT_APPLICABLE.name) Text(inboxDeliveryLabel(event.deliveryState), color = InboxPrimary, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

private fun formatInboxTime(timestamp: Long): String = SimpleDateFormat("dd MMM • HH:mm", Locale.getDefault()).format(Date(timestamp))

private fun inboxDeliveryLabel(state: String): String = when (state) {
    DeliveryState.PENDING.name -> "Pending Marksy analysis"
    DeliveryState.IN_FLIGHT.name -> "Sending to Marksy"
    DeliveryState.DELIVERED.name -> "Marksy analysis received"
    DeliveryState.FAILED.name -> "Analysis failed"
    else -> state.lowercase(Locale.ROOT).replace('_', ' ').replaceFirstChar { it.uppercase() }
}
