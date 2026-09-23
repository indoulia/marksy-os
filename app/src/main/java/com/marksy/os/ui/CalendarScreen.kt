package com.marksy.os.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.marksy.os.data.local.NotificationEventEntity
import java.time.Instant
import java.time.LocalDate
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
    val today = remember { LocalDate.now() }
    var selectedDay by rememberSaveable { mutableIntStateOf(today.dayOfMonth) }
    val month = remember(monthOffset) { YearMonth.now().plusMonths(monthOffset.toLong()) }
    val zone = remember { ZoneId.systemDefault() }
    val monthEvents = events.filter {
        val date = Instant.ofEpochMilli(it.postedAt).atZone(zone).toLocalDate()
        date.year == month.year && date.monthValue == month.monthValue
    }
    val dayCounts = monthEvents.groupingBy { Instant.ofEpochMilli(it.postedAt).atZone(zone).dayOfMonth }.eachCount()
    val maxCount = dayCounts.values.maxOrNull() ?: 0
    val firstDay = month.atDay(1).dayOfWeek.value % 7
    val selectedEvents = if (selectedDay > 0) monthEvents.filter {
        Instant.ofEpochMilli(it.postedAt).atZone(zone).dayOfMonth == selectedDay
    }.sortedByDescending { it.postedAt } else emptyList()

    Column(
        Modifier.padding(padding).padding(horizontal = 18.dp, vertical = 4.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Card(colors = CardDefaults.cardColors(containerColor = Surface), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = { monthOffset--; selectedDay = if (monthOffset == 0) today.dayOfMonth else -1 }) { Text("‹", fontSize = 20.sp, color = Primary) }
                    Text(month.format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.getDefault())), color = TextPrimary, fontWeight = FontWeight.SemiBold)
                    TextButton(onClick = { monthOffset++; selectedDay = if (monthOffset == 0) today.dayOfMonth else -1 }) { Text("›", fontSize = 20.sp, color = Primary) }
                }
                Row(Modifier.fillMaxWidth()) {
                    listOf("S", "M", "T", "W", "T", "F", "S").forEach { Text(it, color = TextMuted, fontSize = 11.sp, textAlign = TextAlign.Center, modifier = Modifier.weight(1f).padding(bottom = 2.dp)) }
                }
                val cellCount = ((firstDay + month.lengthOfMonth() + 6) / 7) * 7
                (0 until cellCount).chunked(7).forEach { week ->
                    Row(Modifier.fillMaxWidth()) {
                        week.forEach { cell ->
                            val day = cell - firstDay + 1
                            if (day in 1..month.lengthOfMonth()) {
                                val count = dayCounts[day] ?: 0
                                val isToday = month == YearMonth.from(today) && day == today.dayOfMonth
                                val isSelected = selectedDay == day
                                Box(Modifier.weight(1f).height(38.dp).clickable { selectedDay = day }, contentAlignment = Alignment.Center) {
                                    Box(
                                        Modifier.size(32.dp).clip(CircleShape)
                                            .background(if (isSelected) Primary else heatColor(count, maxCount))
                                            .border(if (isToday && !isSelected) 1.5.dp else 0.dp, if (isToday) Primary else Color.Transparent, CircleShape),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(day.toString(), color = if (isSelected) Color.Black else if (isToday) Primary else TextPrimary, fontSize = 13.sp, fontWeight = if (isSelected || isToday) FontWeight.Bold else FontWeight.Normal)
                                    }
                                }
                            } else Spacer(Modifier.weight(1f).height(38.dp))
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 6.dp, end = 4.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                    Text("Quiet", color = TextMuted, fontSize = 10.sp)
                    listOf(1, 2, 3, 4).forEach { Box(Modifier.padding(horizontal = 2.dp).size(10.dp).clip(CircleShape).background(heatColor(it, 4))) }
                    Text("Busy", color = TextMuted, fontSize = 10.sp)
                }
            }
        }
        Spacer(Modifier.height(12.dp))
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

// Busier days get a stronger bubble so heavy days stand out at a glance.
private fun heatColor(count: Int, maxCount: Int): Color =
    if (count <= 0 || maxCount <= 0) Color.Transparent
    else Primary.copy(alpha = 0.12f + 0.48f * count / maxCount)
