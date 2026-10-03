package com.marksy.os.ui

import android.content.Context
import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.marksy.os.AppContext
import com.marksy.os.MarksyFormat
import com.marksy.os.clock.ClockPrefs
import com.marksy.os.clock.ClockZones
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import kotlin.math.roundToInt

/** The two user-chosen zones, shared by Home and the clock page; persisted in [ClockPrefs]. */
object WorldClockSettings {
    private fun prefs() = ClockPrefs(AppContext.get().getSharedPreferences(ClockPrefs.NAME, Context.MODE_PRIVATE))

    val zones: MutableState<Pair<ZoneId, ZoneId>> by lazy { mutableStateOf(prefs().load()) }

    fun set(first: ZoneId, second: ZoneId) {
        zones.value = first to second
        prefs().save(first, second)
    }
}

/** Two compact live clocks in the chosen zones; tap opens the clock page. */
@Composable
fun WorldClockPair(modifier: Modifier = Modifier) {
    val (first, second) = WorldClockSettings.zones.value
    var showConverter by remember { mutableStateOf(false) }
    val now by produceState(ZonedDateTime.now()) {
        while (true) {
            value = ZonedDateTime.now()
            delay(60_000L - (System.currentTimeMillis() % 60_000L))
        }
    }
    Column(
        modifier.clip(MarksyShape.Chip).clickable { showConverter = true },
        horizontalAlignment = Alignment.End
    ) {
        listOf(first, second).forEach { zone ->
            val time = now.withZoneSameInstant(zone)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(ClockZones.abbreviation(time), color = MarksyTheme.TextMuted, style = MarksyType.Caption, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(MarksySpace.Inner))
                Text(MarksyFormat.time12(time), color = MarksyTheme.TextPrimary, style = MarksyType.Lead, fontWeight = FontWeight.Bold)
            }
        }
    }
    if (showConverter) TimeZoneConverterDialog { showConverter = false }
}

private enum class ZoneSlot { First, Second, Source }

/**
 * The clock page: pick a date, time and the zone it is in, and read that moment in both chosen zones (and the source).
 * Each chosen zone can be changed from the zone list.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimeZoneConverterDialog(onDismiss: () -> Unit) {
    val (first, second) = WorldClockSettings.zones.value
    val phone = remember { ZoneId.systemDefault() }
    var source by remember { mutableStateOf(phone) }
    val start = remember { ZonedDateTime.now(phone).let { it.plusMinutes(((5 - it.minute % MINUTE_STEP) % MINUTE_STEP).toLong()) } }
    var date by remember { mutableStateOf(start.toLocalDate()) }
    var hour12 by remember { mutableIntStateOf(start.hour.let { if (it % 12 == 0) 12 else it % 12 }) }
    var minute by remember { mutableIntStateOf(start.minute) }
    var pm by remember { mutableStateOf(start.hour >= 12) }
    var wheelsKey by remember { mutableIntStateOf(0) }
    var picking by remember { mutableStateOf<ZoneSlot?>(null) }
    var pickingDate by remember { mutableStateOf(false) }

    val from = date.atTime(LocalTime.of(hour12 % 12 + if (pm) 12 else 0, minute)).atZone(source)

    MarksyDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("World clock", color = MarksyTheme.TextPrimary, modifier = Modifier.weight(1f))
                IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, contentDescription = "Close", tint = MarksyTheme.TextSecondary) }
            }
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(MarksySpace.Gap)) {
                SectionLabel("Time in")
                Row(horizontalArrangement = Arrangement.spacedBy(MarksySpace.Gap), verticalAlignment = Alignment.CenterVertically) {
                    val options = listOf(phone, first, second).distinct()
                    options.forEach { zone ->
                        val label = ClockZones.abbreviation(from.withZoneSameInstant(zone)).let { if (zone == phone) "Phone" else it }
                        Pill(label, selected = zone == source) { source = zone }
                    }
                    Pill(if (source in options) "Other…" else ClockZones.abbreviation(from), selected = source !in options) { picking = ZoneSlot.Source }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { date = date.minusDays(1) }) { Icon(Icons.Default.ChevronLeft, contentDescription = "Previous day", tint = MarksyTheme.TextSecondary) }
                    Pill(MarksyFormat.weekdayDay(date)) { pickingDate = true }
                    IconButton(onClick = { date = date.plusDays(1) }) { Icon(Icons.Default.ChevronRight, contentDescription = "Next day", tint = MarksyTheme.TextSecondary) }
                }
                key(wheelsKey) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                        Wheel((1..12).map { it.toString() }, hour12 - 1) { hour12 = it + 1 }
                        Text(":", color = MarksyTheme.TextPrimary, style = MarksyType.Display, modifier = Modifier.padding(horizontal = MarksySpace.Tight))
                        Wheel((0 until 60 step MINUTE_STEP).map { it.toString().padStart(2, '0') }, minute / MINUTE_STEP) { minute = it * MINUTE_STEP }
                        Spacer(Modifier.width(MarksySpace.Gap))
                        Wheel(listOf("AM", "PM"), if (pm) 1 else 0) { pm = it == 1 }
                    }
                }
                MarksyGroupCard {
                    ZoneResult("First clock", from, from.withZoneSameInstant(first)) { picking = ZoneSlot.First }
                    MarksyDivider()
                    ZoneResult("Second clock", from, from.withZoneSameInstant(second)) { picking = ZoneSlot.Second }
                    if (source != first && source != second) {
                        MarksyDivider()
                        ZoneResult("Source", from, from, null)
                    }
                }
            }
        },
        confirmButton = {
            MarksyButton("Now", style = MarksyButtonStyle.Text, onClick = {
                val now = ZonedDateTime.now(source)
                date = now.toLocalDate()
                hour12 = now.hour.let { if (it % 12 == 0) 12 else it % 12 }
                minute = now.minute - now.minute % MINUTE_STEP
                pm = now.hour >= 12
                wheelsKey++
            })
        }
    )

    picking?.let { slot ->
        ZonePickerDialog(
            title = when (slot) { ZoneSlot.First -> "First clock"; ZoneSlot.Second -> "Second clock"; ZoneSlot.Source -> "Time is in" },
            onDismiss = { picking = null },
            onPick = { zone ->
                when (slot) {
                    ZoneSlot.First -> WorldClockSettings.set(zone, second)
                    ZoneSlot.Second -> WorldClockSettings.set(first, zone)
                    ZoneSlot.Source -> source = zone
                }
                picking = null
            }
        )
    }

    if (pickingDate) {
        val state = rememberDatePickerState(initialSelectedDateMillis = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { pickingDate = false },
            confirmButton = {
                MarksyButton("OK", style = MarksyButtonStyle.Text, onClick = {
                    state.selectedDateMillis?.let { date = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() }
                    pickingDate = false
                })
            },
            dismissButton = { MarksyButton("Cancel", onClick = { pickingDate = false }, style = MarksyButtonStyle.Text) }
        ) { DatePicker(state) }
    }
}

/** One zone's reading: name, date (with a day shift against the source), the time, and an optional Change action. */
@Composable
private fun ZoneResult(heading: String, from: ZonedDateTime, at: ZonedDateTime, onChange: (() -> Unit)?) {
    Row(Modifier.fillMaxWidth().padding(vertical = MarksySpace.Tight), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(heading.uppercase(), color = MarksyTheme.TextMuted, style = MarksyType.Label)
            Text("${ClockZones.cityName(at.zone)} · ${ClockZones.abbreviation(at)}", color = MarksyTheme.TextSecondary, style = MarksyType.Small, fontWeight = FontWeight.SemiBold)
            val shift = ClockZones.dayShift(from, at)
            Text(
                MarksyFormat.weekdayDay(at) + (shift?.let { "  ($it)" } ?: ""),
                color = if (shift != null) MarksyTheme.Warning else MarksyTheme.TextMuted,
                style = MarksyType.Meta
            )
        }
        Text(MarksyFormat.time12(at), color = MarksyTheme.PrimaryEmerald, style = MarksyType.Display)
        onChange?.let { MarksyButton("Change", onClick = it, style = MarksyButtonStyle.Text) }
    }
}

/** Searchable zone list: city or region, abbreviation, UTC offset. */
@Composable
fun ZonePickerDialog(title: String, onPick: (ZoneId) -> Unit, onDismiss: () -> Unit) {
    var query by remember { mutableStateOf("") }
    val now = remember { Instant.now() }
    val all = remember { ClockZones.allZones() }
    val matches = remember(query) { ClockZones.search(query, now, all) }
    MarksyDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, color = MarksyTheme.TextPrimary) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(MarksySpace.Gap)) {
                MarksySearchField(query, { query = it }, "Search city, region or EDT", lifted = false)
                Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(MarksySpace.Gap)) {
                    if (matches.isEmpty()) InlineEmpty("No matching zone")
                    matches.forEach { entry ->
                        MarksyRowCard(onClick = { onPick(entry.zone) }) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(entry.city, color = MarksyTheme.TextPrimary, style = MarksyType.Subhead)
                                    if (entry.region.isNotEmpty()) Text(entry.region, color = MarksyTheme.TextMuted, style = MarksyType.Meta)
                                }
                                Text("${entry.abbreviation} · ${entry.offset}", color = MarksyTheme.TextSecondary, style = MarksyType.Small, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { MarksyButton("Close", onClick = onDismiss, style = MarksyButtonStyle.Text, color = MarksyTheme.TextSecondary) }
    )
}

private const val MINUTE_STEP = 5
private val WheelItemHeight = 40.dp

/** Scroll-to-pick column that snaps to the middle row; no keyboard needed. */
@Composable
private fun Wheel(items: List<String>, initialIndex: Int, onSelected: (Int) -> Unit) {
    val state = rememberScrollState()
    val itemPx = with(LocalDensity.current) { WheelItemHeight.toPx() }
    val selected by remember { derivedStateOf { (state.value / itemPx).roundToInt().coerceIn(items.indices) } }
    LaunchedEffect(state) {
        state.scrollTo((initialIndex * itemPx).roundToInt())
        snapshotFlow { state.isScrollInProgress }.filter { !it }.collect {
            val index = (state.value / itemPx).roundToInt().coerceIn(items.indices)
            state.animateScrollTo((index * itemPx).roundToInt())
            onSelected(index)
        }
    }
    Box(Modifier.width(64.dp).height(WheelItemHeight * 3), contentAlignment = Alignment.Center) {
        Box(
            Modifier.fillMaxWidth().height(WheelItemHeight).clip(MarksyShape.Chip)
                .background(MarksyTheme.BadgeTradingBg).border(MarksySpace.Border, MarksyTheme.PrimaryEmerald, MarksyShape.Chip)
        )
        Column(Modifier.fillMaxWidth().height(WheelItemHeight * 3).verticalScroll(state), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(WheelItemHeight))
            items.forEachIndexed { index, label ->
                Box(Modifier.height(WheelItemHeight).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text(
                        label,
                        color = if (index == selected) MarksyTheme.PrimaryEmerald else MarksyTheme.TextMuted,
                        style = if (index == selected) MarksyType.Display else MarksyType.Lead,
                        fontWeight = if (index == selected) FontWeight.Bold else FontWeight.Medium
                    )
                }
            }
            Spacer(Modifier.height(WheelItemHeight))
        }
    }
}
