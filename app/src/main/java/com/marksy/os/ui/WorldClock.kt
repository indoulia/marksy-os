package com.marksy.os.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.filled.Close
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.marksy.os.AppContext
import com.marksy.os.MarksyFormat
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import java.util.Locale

/** Home-zone (IST) plus one user-chosen zone, for scheduling across time zones. */
object WorldClock {
    val HOME_ZONE: ZoneId = ZoneId.of("Asia/Kolkata")
    val DEFAULT_SECOND_ZONE: ZoneId = ZoneId.of("America/New_York")

    val CHOICES: List<Pair<ZoneId, String>> = listOf(
        "America/New_York" to "New York (Eastern)",
        "America/Chicago" to "Chicago (Central)",
        "America/Denver" to "Denver (Mountain)",
        "America/Los_Angeles" to "Los Angeles (Pacific)",
        "America/Toronto" to "Toronto",
        "UTC" to "UTC",
        "Europe/London" to "London",
        "Europe/Berlin" to "Berlin / Paris",
        "Asia/Dubai" to "Dubai",
        "Asia/Singapore" to "Singapore",
        "Asia/Tokyo" to "Tokyo",
        "Australia/Sydney" to "Sydney"
    ).map { (id, name) -> ZoneId.of(id) to name }

    fun convert(date: LocalDate, time: LocalTime, from: ZoneId, to: ZoneId): ZonedDateTime =
        date.atTime(time).atZone(from).withZoneSameInstant(to)

    /** "+1 day" / "−1 day" when [to] falls on a different calendar date than [from]. */
    fun dayShift(from: ZonedDateTime, to: ZonedDateTime): String? {
        val days = ChronoUnit.DAYS.between(from.toLocalDate(), to.toLocalDate())
        return when {
            days == 0L -> null
            days > 0 -> "+$days day"
            else -> "${com.marksy.os.MarksyFormat.MINUS}${-days} day"
        }
    }

    /** DST-aware short name (EDT in summer, EST in winter). */
    // Android's zone data renders Asia/Kolkata as "GMT+05:30", so name the home zone explicitly.
    fun abbreviation(time: ZonedDateTime): String =
        if (time.zone == HOME_ZONE) "IST" else java.util.TimeZone.getTimeZone(time.zone).getDisplayName(time.zone.rules.isDaylightSavings(time.toInstant()), java.util.TimeZone.SHORT, Locale.US)

    fun parseZone(id: String?): ZoneId = runCatching { ZoneId.of(id) }.getOrNull() ?: DEFAULT_SECOND_ZONE

    fun cityName(zone: ZoneId): String = CHOICES.firstOrNull { it.first == zone }?.second ?: zone.id
}

/** Second clock zone, persisted locally and observable by Home and More. */
object WorldClockSettings {
    private const val PREFS = "world_clock"
    private const val KEY_ZONE = "second_zone"
    private fun prefs() = AppContext.get().getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    val secondZone: MutableState<ZoneId> by lazy { mutableStateOf(WorldClock.parseZone(prefs().getString(KEY_ZONE, null))) }

    fun setSecondZone(zone: ZoneId) {
        secondZone.value = zone
        prefs().edit().putString(KEY_ZONE, zone.id).apply()
    }
}

/** Two compact live clocks (IST + chosen zone); tap opens the converter. */
@Composable
fun WorldClockPair(modifier: Modifier = Modifier) {
    val second by WorldClockSettings.secondZone
    var showConverter by remember { mutableStateOf(false) }
    val now by produceState(ZonedDateTime.now()) {
        while (true) {
            value = ZonedDateTime.now()
            delay(60_000L - (System.currentTimeMillis() % 60_000L))
        }
    }
    Column(
        modifier.clip(MarksyShape.Chip).clickable { showConverter = true }.padding(horizontal = MarksySpace.Inner, vertical = MarksySpace.Hair),
        horizontalAlignment = Alignment.End
    ) {
        listOf(WorldClock.HOME_ZONE, second).forEach { zone ->
            val time = now.withZoneSameInstant(zone)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(WorldClock.abbreviation(time), color = MarksyTheme.TextMuted, style = MarksyType.Caption, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(6.dp))
                Text(MarksyFormat.time12(time), color = MarksyTheme.TextPrimary, style = MarksyType.Lead, fontWeight = FontWeight.Bold)
            }
        }
    }
    if (showConverter) TimeZoneConverterDialog(second) { showConverter = false }
}

/** Enter a time in either zone and read it off in the other, e.g. "8 AM EDT" → "5:30 PM IST". */
@Composable
fun TimeZoneConverterDialog(secondZone: ZoneId, onDismiss: () -> Unit) {
    // Default to setting the foreign time, since that's what people quote ("8 AM EST").
    var fromZone by remember { mutableStateOf(secondZone) }
    var seed by remember { mutableStateOf(ZonedDateTime.now(secondZone).truncatedTo(ChronoUnit.HOURS).plusHours(1)) }
    val toZone = if (fromZone == secondZone) WorldClock.HOME_ZONE else secondZone

    MarksyDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Time zone converter", color = MarksyTheme.TextPrimary, modifier = Modifier.weight(1f))
                IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, contentDescription = "Close", tint = MarksyTheme.TextSecondary) }
            }
        },
        text = {
            key(fromZone, seed) {
                var hour12 by remember { mutableIntStateOf(seed.hour.let { if (it % 12 == 0) 12 else it % 12 }) }
                var minute by remember { mutableIntStateOf(seed.minute - seed.minute % MINUTE_STEP) }
                var pm by remember { mutableStateOf(seed.hour >= 12) }
                val time = LocalTime.of(hour12 % 12 + if (pm) 12 else 0, minute)
                val from = seed.toLocalDate().atTime(time).atZone(fromZone)
                val to = from.withZoneSameInstant(toZone)
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    ZoneLabel(from, fromZone)
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Wheel((1..12).map { it.toString() }, hour12 - 1) { hour12 = it + 1 }
                        Text(":", color = MarksyTheme.TextPrimary, style = MarksyType.Display, modifier = Modifier.padding(horizontal = MarksySpace.Tight))
                        Wheel((0 until 60 step MINUTE_STEP).map { it.toString().padStart(2, '0') }, minute / MINUTE_STEP) { minute = it * MINUTE_STEP }
                        Spacer(Modifier.width(8.dp))
                        Wheel(listOf("AM", "PM"), if (pm) 1 else 0) { pm = it == 1 }
                    }
                    IconButton(onClick = { seed = to; fromZone = toZone }) {
                        Icon(Icons.Default.SwapVert, contentDescription = "Swap zones", tint = MarksyTheme.PrimaryEmerald)
                    }
                    Box(
                        Modifier.fillMaxWidth().clip(MarksyShape.Card).background(MarksyTheme.Surface)
                            .border(MarksySpace.Border, MarksyTheme.PrimaryEmerald, MarksyShape.Card).padding(MarksySpace.Section),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            ZoneLabel(to, toZone)
                            Text(MarksyFormat.time12(to), color = MarksyTheme.PrimaryEmerald, style = MarksyType.Display)
                            val shift = WorldClock.dayShift(from, to)
                            Text(
                                MarksyFormat.weekdayDay(to) + (shift?.let { "  ($it)" } ?: ""),
                                color = if (shift != null) MarksyTheme.Warning else MarksyTheme.TextSecondary,
                                style = MarksyType.Small
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            MarksyButton("Now", onClick = { seed = ZonedDateTime.now(fromZone) }, style = MarksyButtonStyle.Text)
        }
    )
}

private const val MINUTE_STEP = 5
private val WheelItemHeight = 40.dp

/** Scroll-to-pick column that snaps to the middle row; no keyboard needed. */
@Composable
private fun Wheel(items: List<String>, initialIndex: Int, onSelected: (Int) -> Unit) {
    val state = rememberLazyListState(initialIndex)
    val selected by remember { derivedStateOf { state.firstVisibleItemIndex.coerceIn(items.indices) } }
    LaunchedEffect(state) {
        snapshotFlow { state.isScrollInProgress to state.firstVisibleItemIndex }
            .collect { (scrolling, index) -> if (!scrolling) onSelected(index.coerceIn(items.indices)) }
    }
    Box(Modifier.width(64.dp).height(WheelItemHeight * 3), contentAlignment = Alignment.Center) {
        Box(
            Modifier.fillMaxWidth().height(WheelItemHeight).clip(MarksyShape.Chip)
                .background(MarksyTheme.BadgeTradingBg).border(MarksySpace.Border, MarksyTheme.PrimaryEmerald, MarksyShape.Chip)
        )
        LazyColumn(
            state = state,
            flingBehavior = rememberSnapFlingBehavior(state),
            contentPadding = PaddingValues(vertical = WheelItemHeight),
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxSize()
        ) {
            itemsIndexed(items) { index, label ->
                Box(Modifier.height(WheelItemHeight).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text(
                        label,
                        color = if (index == selected) MarksyTheme.PrimaryEmerald else MarksyTheme.TextMuted,
                        style = if (index == selected) MarksyType.Display else MarksyType.Lead,
                        fontWeight = if (index == selected) FontWeight.Bold else FontWeight.Medium
                    )
                }
            }
        }
    }
}

@Composable
private fun ZoneLabel(time: ZonedDateTime, zone: ZoneId) {
    Text(
        "${WorldClock.abbreviation(time)} · ${if (zone == WorldClock.HOME_ZONE) "India" else WorldClock.cityName(zone)}",
        color = MarksyTheme.TextSecondary, style = MarksyType.Small, fontWeight = FontWeight.SemiBold
    )
}

/** Picker for the second Home clock, used from More & Settings. */
@Composable
fun SecondZonePickerDialog(onDismiss: () -> Unit) {
    val current by WorldClockSettings.secondZone
    val now = remember { ZonedDateTime.now() }
    MarksyDialog(
        onDismissRequest = onDismiss,
        title = { Text("Second clock", color = MarksyTheme.TextPrimary) },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(MarksySpace.Hair)) {
                items(WorldClock.CHOICES, key = { it.first.id }) { (zone, name) ->
                    val selected = zone == current
                    Row(
                        Modifier.fillMaxWidth().clip(MarksyShape.Chip)
                            .background(if (selected) MarksyTheme.PrimaryEmerald else Color.Transparent)
                            .clickable { WorldClockSettings.setSecondZone(zone); onDismiss() }
                            .padding(horizontal = MarksySpace.CardPadding, vertical = MarksySpace.ListGap),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(name, color = if (selected) MarksyTheme.OnAccent else MarksyTheme.TextPrimary, style = MarksyType.Subhead, fontWeight = FontWeight.Normal, modifier = Modifier.weight(1f))
                        Text(
                            WorldClock.abbreviation(now.withZoneSameInstant(zone)),
                            color = if (selected) MarksyTheme.OnAccent else MarksyTheme.TextMuted, style = MarksyType.Small, fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        },
        confirmButton = { MarksyButton("Close", onClick = onDismiss, style = MarksyButtonStyle.Text, color = MarksyTheme.TextSecondary) }
    )
}
