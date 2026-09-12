package com.marksy.os.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.marksy.os.data.local.NotificationEventEntity
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val Surface = Color(0xFF101613)
private val Raised = Color(0xFF151C18)
private val Primary = Color(0xFF72D49A)
private val TextPrimary = Color(0xFFE8F1EC)
private val TextSecondary = Color(0xFF9AA9A1)
private val TextMuted = Color(0xFF657169)

@Composable
fun CalendarScreen(
    events: List<NotificationEventEntity>,
    padding: PaddingValues,
    onEventSelected: (NotificationEventEntity) -> Unit
) {
    var monthOffset by rememberSaveable { mutableIntStateOf(0) }
    var selectedDay by rememberSaveable { mutableIntStateOf(-1) }
    val month = remember(monthOffset) { YearMonth.now().plusMonths(monthOffset.toLong()) }
    val zone = remember { ZoneId.systemDefault() }
    val monthEvents = events.filter {
        val date = Instant.ofEpochMilli(it.postedAt).atZone(zone).toLocalDate()
        date.year == month.year && date.monthValue == month.monthValue
    }
    val firstDay = month.atDay(1).dayOfWeek.value % 7
    val selectedEvents = if (selectedDay > 0) monthEvents.filter {
        Instant.ofEpochMilli(it.postedAt).atZone(zone).dayOfMonth == selectedDay
    }.sortedByDescending { it.postedAt } else emptyList()

    Column(
        Modifier.padding(padding).padding(horizontal = 18.dp, vertical = 16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text("Calendar", color = TextPrimary, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(5.dp))
        Text("Browse your notification history by day.", color = TextSecondary, fontSize = 13.sp)
        Spacer(Modifier.height(14.dp))
        Card(colors = CardDefaults.cardColors(containerColor = Surface), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = { monthOffset--; selectedDay = -1 }) { Text("‹") }
                    Text(month.format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.getDefault())), color = TextPrimary, fontWeight = FontWeight.SemiBold)
                    TextButton(onClick = { monthOffset++; selectedDay = -1 }) { Text("›") }
                }
                Row(Modifier.fillMaxWidth()) {
                    listOf("S", "M", "T", "W", "T", "F", "S").forEach { Text(it, color = TextMuted, fontSize = 11.sp, modifier = Modifier.weight(1f).padding(vertical = 5.dp)) }
                }
                val cellCount = ((firstDay + month.lengthOfMonth() + 6) / 7) * 7
                (0 until cellCount).chunked(7).forEach { week ->
                    Row(Modifier.fillMaxWidth()) {
                        week.forEach { cell ->
                            val day = cell - firstDay + 1
                            if (day in 1..month.lengthOfMonth()) {
                                val hasEvents = monthEvents.any { Instant.ofEpochMilli(it.postedAt).atZone(zone).dayOfMonth == day }
                                TextButton(onClick = { selectedDay = day }, modifier = Modifier.weight(1f)) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text(day.toString(), color = if (selectedDay == day) Primary else TextPrimary, fontWeight = if (selectedDay == day) FontWeight.Bold else FontWeight.Normal)
                                        Text(if (hasEvents) "•" else " ", color = Primary, fontSize = 9.sp)
                                    }
                                }
                            } else Spacer(Modifier.weight(1f).height(48.dp))
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(18.dp))
        Text(if (selectedDay > 0) "$selectedDay ${month.month.name.lowercase().replaceFirstChar { it.uppercase() }}" else "Select a day", color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        if (selectedDay <= 0) {
            Text("Tap any marked day to inspect its notifications.", color = TextSecondary, fontSize = 13.sp)
        } else if (selectedEvents.isEmpty()) {
            Text("No meaningful notifications captured on this day.", color = TextSecondary, fontSize = 13.sp)
        } else {
            selectedEvents.forEach { event ->
                Card(onClick = { onEventSelected(event) }, colors = CardDefaults.cardColors(containerColor = if (event.isTrading) Raised else Surface), modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                    Column(Modifier.padding(13.dp)) {
                        Text(event.sourceName, color = if (event.isTrading) Primary else TextSecondary, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                        Spacer(Modifier.height(3.dp))
                        Text(event.title.ifBlank { "Notification" }, color = TextPrimary, fontWeight = FontWeight.Medium, maxLines = 2)
                        if (event.body.isNotBlank()) Text(event.body, color = TextSecondary, fontSize = 12.sp, maxLines = 2, modifier = Modifier.padding(top = 3.dp))
                    }
                }
            }
        }
    }
}
