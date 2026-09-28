package com.marksy.os.ui

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

private val CLOCK = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH)
private val WEEKDAY = DateTimeFormatter.ofPattern("EEE HH:mm", Locale.ENGLISH)
private val DAY = DateTimeFormatter.ofPattern("d MMM HH:mm", Locale.ENGLISH)
private val OLD = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)

/** When a notification arrived, as short as it can be while still naming the day: "12:31", "Yesterday 18:05", "Sat 08:29", "16 Sep 08:29". */
internal fun compactTime(postedAt: Long, now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): String? {
    if (postedAt <= 0) return null
    val at = Instant.ofEpochMilli(postedAt).atZone(zone)
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val days = ChronoUnit.DAYS.between(at.toLocalDate(), today)
    return when {
        days <= 0L -> at.format(CLOCK)
        days == 1L -> "Yesterday ${at.format(CLOCK)}"
        days < 7L -> at.format(WEEKDAY)
        at.year == today.year -> at.format(DAY)
        else -> at.format(OLD)
    }
}
