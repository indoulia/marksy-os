package com.marksy.os.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.marksy.os.data.local.DeliveryState
import com.marksy.os.data.local.NotificationEventEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val TimelineSurface = Color(0xFF101613)
private val TimelineRaised = Color(0xFF151C18)
private val TimelinePrimary = Color(0xFF72D49A)
private val TimelineText = Color(0xFFE8F1EC)
private val TimelineSecondary = Color(0xFF9AA9A1)
private val TimelineMuted = Color(0xFF657169)

/**
 * Chronological view of meaningful local events. OTHER noise is excluded by the
 * repository query before reaching this screen.
 */
@Composable
fun TimelineScreen(
    events: List<NotificationEventEntity>,
    padding: PaddingValues,
    onEventSelected: (NotificationEventEntity) -> Unit = {}
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(padding),
        contentPadding = PaddingValues(18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Text("Timeline", color = TimelineText, fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(5.dp))
            Text(
                "A chronological view of meaningful notifications captured on this device.",
                color = TimelineSecondary,
                fontSize = 13.sp
            )
            Spacer(Modifier.height(14.dp))
        }

        if (events.isEmpty()) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = TimelineSurface),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(18.dp)) {
                        Text("Timeline is empty.", color = TimelineText, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(5.dp))
                        Text(
                            "Meaningful events will appear here after Marksy OS receives notifications.",
                            color = TimelineSecondary,
                            fontSize = 13.sp
                        )
                    }
                }
            }
        } else {
            items(events, key = { it.id }) { event ->
                TimelineEventCard(event) { onEventSelected(event) }
            }
        }
    }
}

@Composable
private fun TimelineEventCard(event: NotificationEventEntity, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(
            containerColor = if (event.isTrading) TimelineRaised else TimelineSurface
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(15.dp)) {
            Text(formatTimestamp(event.postedAt), color = TimelineMuted, fontSize = 11.sp)
            Spacer(Modifier.height(6.dp))
            Text(
                event.sourceName,
                color = if (event.isTrading) TimelinePrimary else TimelineSecondary,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(4.dp))
            Text(event.title, color = TimelineText, fontWeight = FontWeight.Medium)
            if (event.body.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(event.body, color = TimelineSecondary, fontSize = 13.sp, maxLines = 3)
            }
            Spacer(Modifier.height(7.dp))
            Text(
                buildString {
                    append(event.category.lowercase().replaceFirstChar { it.uppercase() })
                    if (event.isTrading) {
                        append(" • ")
                        append(
                            when (event.deliveryState) {
                                DeliveryState.DELIVERED.name -> "Marksy response received"
                                DeliveryState.PENDING.name -> "Queued for Marksy"
                                DeliveryState.IN_FLIGHT.name -> "Sending to Marksy"
                                DeliveryState.FAILED.name -> "Delivery failed"
                                else -> "Trading event"
                            }
                        )
                    }
                },
                color = TimelineMuted,
                fontSize = 11.sp
            )
        }
    }
}

private fun formatTimestamp(timestamp: Long): String =
    SimpleDateFormat("dd MMM yyyy • HH:mm", Locale.getDefault()).format(Date(timestamp))
