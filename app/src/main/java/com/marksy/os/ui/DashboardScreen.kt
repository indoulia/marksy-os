package com.marksy.os.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.marksy.os.data.local.DeliveryState
import com.marksy.os.data.local.NotificationEventEntity
import com.marksy.os.intelligence.DashboardSnapshot
import com.marksy.os.intelligence.EventIntelligence
import com.marksy.os.intelligence.dashboardAgeLabel

private val DashboardSurface = Color(0xFF101613)
private val DashboardRaised = Color(0xFF151C18)
private val DashboardPrimary = Color(0xFF72D49A)
private val DashboardText = Color(0xFFE8F1EC)
private val DashboardSecondary = Color(0xFF9AA9A1)
private val DashboardMuted = Color(0xFF657169)

/** Complete Home dashboard presentation. It renders only the supplied snapshot/events. */
@Composable
fun DashboardScreen(
    snapshot: DashboardSnapshot,
    events: List<NotificationEventEntity>,
    onEventSelected: (NotificationEventEntity) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(modifier = modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Column(Modifier.padding(horizontal = 18.dp, vertical = 20.dp)) {
                Text("MARKSY OS", color = DashboardPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.6.sp)
                Spacer(Modifier.height(7.dp))
                Text("Less noise. More intelligence.", color = DashboardText, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(5.dp))
                Text("One view of what needs attention, what Marksy is analyzing, and what changed recently.", color = DashboardSecondary, fontSize = 14.sp)
                Spacer(Modifier.height(18.dp))
                MetricRow(snapshot)
                Spacer(Modifier.height(12.dp))
                TradingHealthCard(snapshot)
            }
        }
        item { SectionTitle("AI attention") }
        if (snapshot.topAttention.isEmpty()) {
            item { EmptyDashboardCard("Nothing needs attention yet.", "Marksy OS will surface high-priority events here.") }
        } else {
            items(snapshot.topAttention, key = { it.eventId }) { result ->
                events.firstOrNull { it.id == result.eventId }?.let { event ->
                    AttentionCard(result, event, snapshot.generatedAt) { onEventSelected(event) }
                }
            }
        }
        item { SectionTitle("Activity mix") }
        item { BreakdownCard("Categories", snapshot.categoryCounts) }
        item { BreakdownCard("Sources", snapshot.sourceCounts) }
        item { SectionTitle("Latest activity") }
        if (events.isEmpty()) {
            item { EmptyDashboardCard("Everything is quiet.", "Captured events will appear here when they arrive.") }
        } else {
            items(events.take(5), key = { it.id }) { event -> CompactEventCard(event, snapshot.generatedAt) { onEventSelected(event) } }
        }
        item { Spacer(Modifier.height(8.dp)) }
    }
}

@Composable
private fun MetricRow(snapshot: DashboardSnapshot) = Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    Metric("Inbox", snapshot.totalEvents.toString(), Modifier.weight(1f))
    Metric("Trading", snapshot.tradingEvents.toString(), Modifier.weight(1f))
    Metric("Attention", snapshot.importantEvents.toString(), Modifier.weight(1f))
}

@Composable
private fun Metric(label: String, value: String, modifier: Modifier) = Card(modifier = modifier, colors = CardDefaults.cardColors(containerColor = DashboardSurface)) {
    Column(Modifier.padding(12.dp)) {
        Text(value, color = DashboardText, fontSize = 21.sp, fontWeight = FontWeight.Bold)
        Text(label, color = DashboardSecondary, fontSize = 11.sp)
    }
}

@Composable
private fun TradingHealthCard(snapshot: DashboardSnapshot) {
    val (headline, detail) = when (snapshot.tradingHealth) {
        DashboardSnapshot.TradingHealth.ACTION_REQUIRED -> "Trading needs attention" to "${snapshot.failedTrading} event(s) failed to reach Marksy."
        DashboardSnapshot.TradingHealth.ANALYZING -> "Marksy is analyzing" to "${snapshot.pendingTrading} trading event(s) are waiting for a response."
        DashboardSnapshot.TradingHealth.CLEAR -> "Trading intelligence is clear" to "${snapshot.deliveredTrading} trading event(s) have received a Marksy response."
        DashboardSnapshot.TradingHealth.QUIET -> "Trading is quiet" to "No trading events are currently retained."
        DashboardSnapshot.TradingHealth.LOCAL -> "Trading events are local" to "Some trading events are retained without a completed Marksy response."
    }
    Card(colors = CardDefaults.cardColors(containerColor = DashboardRaised), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(15.dp)) {
            Text(headline, color = DashboardPrimary, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(detail, color = DashboardSecondary, fontSize = 13.sp)
        }
    }
}

@Composable
private fun SectionTitle(title: String) = Text(title, color = DashboardText, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 18.dp, vertical = 5.dp))

@Composable
private fun AttentionCard(result: EventIntelligence.Result, event: NotificationEventEntity, nowMillis: Long, onClick: () -> Unit) = Card(
    colors = CardDefaults.cardColors(containerColor = if (event.isTrading) DashboardRaised else DashboardSurface),
    modifier = Modifier.fillMaxWidth(),
    onClick = onClick
) {
    Column(Modifier.padding(horizontal = 15.dp, vertical = 13.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(event.sourceName.ifBlank { "Unknown source" }, color = if (event.isTrading) DashboardPrimary else DashboardSecondary, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${result.attentionScore}/100", color = DashboardPrimary, fontSize = 11.sp)
        }
        Spacer(Modifier.height(5.dp))
        Text(event.title.ifBlank { "Untitled notification" }, color = DashboardText, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(result.reasons.joinToString(" • "), color = DashboardMuted, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 5.dp))
        Text(dashboardAgeLabel(event.postedAt, nowMillis), color = DashboardMuted, fontSize = 10.sp, modifier = Modifier.padding(top = 5.dp))
    }
}

@Composable
private fun BreakdownCard(title: String, values: Map<String, Int>) = Card(colors = CardDefaults.cardColors(containerColor = DashboardSurface), modifier = Modifier.fillMaxWidth()) {
    Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Text(title, color = DashboardText, fontWeight = FontWeight.SemiBold)
        values.entries.take(5).forEach { (name, count) ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(name.lowercase().replaceFirstChar { it.uppercase() }, color = DashboardSecondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(count.toString(), color = DashboardText, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun CompactEventCard(event: NotificationEventEntity, nowMillis: Long, onClick: () -> Unit) = Card(colors = CardDefaults.cardColors(containerColor = DashboardSurface), modifier = Modifier.fillMaxWidth(), onClick = onClick) {
    Column(Modifier.padding(13.dp)) {
        Text(event.sourceName.ifBlank { "Unknown source" }, color = if (event.isTrading) DashboardPrimary else DashboardSecondary, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(event.title.ifBlank { "Untitled notification" }, color = DashboardText, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
        Text(if (event.isTrading) deliveryLabel(event.deliveryState) else dashboardAgeLabel(event.postedAt, nowMillis), color = DashboardMuted, fontSize = 10.sp, modifier = Modifier.padding(top = 5.dp))
    }
}

@Composable
private fun EmptyDashboardCard(title: String, description: String) = Card(colors = CardDefaults.cardColors(containerColor = DashboardSurface), modifier = Modifier.fillMaxWidth()) {
    Column(Modifier.padding(17.dp)) {
        Text(title, color = DashboardText, fontWeight = FontWeight.SemiBold)
        Text(description, color = DashboardSecondary, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
    }
}

private fun deliveryLabel(state: String): String = when (state) {
    DeliveryState.DELIVERED.name -> "Marksy response received"
    DeliveryState.PENDING.name -> "Waiting for Marksy"
    DeliveryState.IN_FLIGHT.name -> "Sending to Marksy"
    DeliveryState.FAILED.name -> "Delivery failed"
    else -> "Local only"
}
