package com.marksy.os.clock

import android.content.SharedPreferences
import com.marksy.os.MarksyFormat
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import java.util.Locale

/** The two zones Home and the clock page show, and the zone maths around them; plain functions, no Android. */
object ClockZones {
    val DEFAULT_FIRST: ZoneId = ZoneId.of("America/New_York")
    val DEFAULT_SECOND: ZoneId = ZoneId.of("America/Chicago")

    /** Zones shown before the user searches. */
    val COMMON: List<ZoneId> = listOf(
        "America/New_York", "America/Chicago", "America/Denver", "America/Los_Angeles", "America/Toronto", "UTC", "Europe/London",
        "Europe/Berlin", "Asia/Dubai", "Asia/Kolkata", "Asia/Singapore", "Asia/Tokyo", "Australia/Sydney"
    ).map { ZoneId.of(it) }

    private val REGIONS = setOf("Africa", "America", "Antarctica", "Asia", "Atlantic", "Australia", "Europe", "Indian", "Pacific")

    /** A saved id that is missing or no longer valid falls back to that slot's default. */
    fun decode(first: String?, second: String?): Pair<ZoneId, ZoneId> =
        (parse(first) ?: DEFAULT_FIRST) to (parse(second) ?: DEFAULT_SECOND)

    private fun parse(id: String?): ZoneId? = id?.let { runCatching { ZoneId.of(it) }.getOrNull() }

    fun convert(date: LocalDate, time: LocalTime, from: ZoneId, to: ZoneId): ZonedDateTime =
        date.atTime(time).atZone(from).withZoneSameInstant(to)

    /** "+1 day" / "−1 day" when [to] falls on a different calendar date than [from]. */
    fun dayShift(from: ZonedDateTime, to: ZonedDateTime): String? {
        val days = ChronoUnit.DAYS.between(from.toLocalDate(), to.toLocalDate())
        return when {
            days == 0L -> null
            days > 0 -> "+$days day"
            else -> "${MarksyFormat.MINUS}${-days} day"
        }
    }

    /** DST-aware short name (EDT in summer, EST in winter). */
    fun abbreviation(time: ZonedDateTime): String {
        val id = time.zone.id
        // Android's zone data renders Asia/Kolkata as "GMT+05:30".
        if (id == "Asia/Kolkata" || id == "Asia/Calcutta") return "IST"
        val short = java.util.TimeZone.getTimeZone(time.zone).getDisplayName(time.zone.rules.isDaylightSavings(time.toInstant()), java.util.TimeZone.SHORT, Locale.US)
        return if (short.startsWith("GMT") && short.length > 3) offsetLabel(time) else short
    }

    /** "UTC−4", "UTC+5:30". */
    fun offsetLabel(time: ZonedDateTime): String {
        val total = time.offset.totalSeconds
        if (total == 0) return "UTC"
        val abs = kotlin.math.abs(total)
        val minutes = abs / 60 % 60
        return "UTC" + (if (total < 0) MarksyFormat.MINUS else "+") + abs / 3600 + (if (minutes == 0) "" else ":" + minutes.toString().padStart(2, '0'))
    }

    fun cityName(zone: ZoneId): String = if (zone.id == "UTC") "UTC" else zone.id.substringAfterLast('/').replace('_', ' ')

    private fun regionName(zone: ZoneId): String = if (zone.id.contains('/')) zone.id.substringBefore('/') else ""

    class Entry(val zone: ZoneId, val city: String, val region: String, val abbreviation: String, val offset: String)

    fun entry(zone: ZoneId, at: Instant): Entry {
        val time = at.atZone(zone)
        return Entry(zone, cityName(zone), regionName(zone), abbreviation(time), offsetLabel(time))
    }

    fun allZones(): List<ZoneId> =
        (ZoneId.getAvailableZoneIds().filter { it.substringBefore('/') in REGIONS && it.contains('/') } + "UTC").sorted().map { ZoneId.of(it) }

    /** Zones matching [query] by city, region, abbreviation or offset; [COMMON] when blank; at most [limit]. */
    fun search(query: String, at: Instant, zones: List<ZoneId> = allZones(), limit: Int = 30): List<Entry> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return COMMON.map { entry(it, at) }
        return zones.asSequence().map { entry(it, at) }
            .filter { q in it.city.lowercase() || q in it.region.lowercase() || q in it.abbreviation.lowercase() || q in it.offset.lowercase() }
            .sortedBy { if (it.city.lowercase().startsWith(q)) 0 else 1 }
            .take(limit).toList()
    }
}

/** Persists the two chosen zone ids. */
class ClockPrefs(private val prefs: SharedPreferences) {
    fun load(): Pair<ZoneId, ZoneId> = ClockZones.decode(prefs.getString(KEY_FIRST, null), prefs.getString(KEY_SECOND, null))

    fun save(first: ZoneId, second: ZoneId) {
        prefs.edit().putString(KEY_FIRST, first.id).putString(KEY_SECOND, second.id).apply()
    }

    companion object {
        const val NAME = "world_clock"
        private const val KEY_FIRST = "zone_1"
        private const val KEY_SECOND = "zone_2"
    }
}
