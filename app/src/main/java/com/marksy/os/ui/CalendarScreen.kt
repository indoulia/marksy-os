package com.marksy.os.ui

import androidx.compose.material3.IconButton
import com.marksy.os.MarksyFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.marksy.os.data.local.NotificationEventEntity
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

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
    fun shiftMonth(by: Int) { monthOffset += by; selectedDay = if (monthOffset == 0) today.dayOfMonth else -1 }
    val swipePx = with(LocalDensity.current) { 56.dp.toPx() }
    val selectedEvents = if (selectedDay > 0) monthEvents.filter {
        Instant.ofEpochMilli(it.postedAt).atZone(zone).dayOfMonth == selectedDay
    }.sortedByDescending { it.postedAt } else emptyList()

    Column(
        Modifier.padding(padding).padding(horizontal = MarksySpace.Gutter, vertical = MarksySpace.Tight)
            .verticalScroll(rememberScrollState())
    ) {
        Column(Modifier.fillMaxWidth().marksyCard().pointerInput(Unit) {
            // Swipe left = next month, right = previous.
            var dragged = 0f
            detectHorizontalDragGestures(
                onDragStart = { dragged = 0f },
                onDragEnd = { if (dragged <= -swipePx) shiftMonth(1) else if (dragged >= swipePx) shiftMonth(-1) }
            ) { _, delta -> dragged += delta }
        }) {
            Column(Modifier.padding(horizontal = MarksySpace.Gap, vertical = MarksySpace.Tight)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    IconButton(onClick = { shiftMonth(-1) }) { Text("‹", style = MarksyType.Title, color = MarksyTheme.PrimaryEmerald) }
                    Text(MarksyFormat.monthYear(month), color = MarksyTheme.TextPrimary, style = MarksyType.Subhead)
                    IconButton(onClick = { shiftMonth(1) }) { Text("›", style = MarksyType.Title, color = MarksyTheme.PrimaryEmerald) }
                }
                Row(Modifier.fillMaxWidth()) {
                    listOf("S", "M", "T", "W", "T", "F", "S").forEach { Text(it, color = MarksyTheme.TextMuted, style = MarksyType.Meta, textAlign = TextAlign.Center, modifier = Modifier.weight(1f).padding(bottom = MarksySpace.Hair)) }
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
                                        Modifier.size(MarksySize.Avatar).clip(CircleShape)
                                            .background(if (isSelected) MarksyTheme.PrimaryEmerald else heatColor(count, maxCount))
                                            .border(if (isToday && !isSelected) 1.5.dp else 0.dp, if (isToday) MarksyTheme.PrimaryEmerald else Color.Transparent, CircleShape),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(day.toString(), color = if (isSelected) MarksyTheme.OnAccent else if (isToday) MarksyTheme.PrimaryEmerald else MarksyTheme.TextPrimary, style = MarksyType.Body, fontWeight = if (isSelected || isToday) FontWeight.Bold else FontWeight.Normal)
                                    }
                                }
                            } else Spacer(Modifier.weight(1f).height(38.dp))
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top = MarksySpace.Hair, bottom = MarksySpace.Inner, end = MarksySpace.Tight), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                    Text("Quiet", color = MarksyTheme.TextMuted, style = MarksyType.Caption)
                    listOf(1, 2, 3, 4).forEach { Box(Modifier.padding(horizontal = MarksySpace.Hair).size(MarksySize.Dot).clip(CircleShape).background(heatColor(it, 4))) }
                    Text("Busy", color = MarksyTheme.TextMuted, style = MarksyType.Caption)
                }
            }
        }
        Spacer(Modifier.height(MarksySpace.CardPadding))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (selectedDay > 0) MarksyFormat.weekdayDay(month.atDay(selectedDay.coerceAtMost(month.lengthOfMonth()))) else "Select a day", color = MarksyTheme.TextPrimary, style = MarksyType.Heading)
            if (selectedDay > 0) {
                Spacer(Modifier.width(MarksySpace.ListGap))
                Text(
                    "${selectedEvents.size} ${if (selectedEvents.size == 1) "message" else "messages"}",
                    color = MarksyTheme.PrimaryEmerald, style = MarksyType.Small, fontWeight = FontWeight.Bold,
                    modifier = Modifier.clip(MarksyShape.Chip).background(MarksyTheme.SurfaceRaised)
                        .border(MarksySpace.Border, MarksyTheme.PrimaryEmerald, MarksyShape.Chip).padding(horizontal = MarksySpace.ListGap, vertical = MarksySpace.Tight)
                )
            }
        }
        Spacer(Modifier.height(MarksySpace.Gap))
        if (selectedDay <= 0) {
            InlineEmpty("Tap any marked day to inspect its notifications.")
        } else if (selectedEvents.isEmpty()) {
            InlineEmpty("No meaningful notifications captured on this day.")
        } else {
            selectedEvents.forEach { event ->
                MarksyCard(onClick = { onEventSelected(event) }, border = if (event.isTrading) MarksyTheme.PrimaryEmerald else MarksyTheme.BorderGlow, modifier = Modifier.padding(bottom = MarksySpace.Inner)) {
                    Text(event.sourceName, color = if (event.isTrading) MarksyTheme.PrimaryEmerald else MarksyTheme.TextSecondary, fontWeight = FontWeight.SemiBold, style = MarksyType.Small)
                    Text(event.title.ifBlank { "Notification" }, color = MarksyTheme.TextPrimary, style = MarksyType.Subhead, fontWeight = FontWeight.Medium, maxLines = 2)
                    if (event.body.isNotBlank()) Text(event.body, color = MarksyTheme.TextSecondary, style = MarksyType.Small, maxLines = 2, modifier = Modifier.padding(top = MarksySpace.Tight))
                }
            }
        }
    }
}

// Busier days get a stronger bubble so heavy days stand out at a glance.
private fun heatColor(count: Int, maxCount: Int): Color =
    if (count <= 0 || maxCount <= 0) Color.Transparent
    else MarksyTheme.PrimaryEmerald.copy(alpha = 0.12f + 0.48f * count / maxCount)
