package com.marksy.os.ui

import com.marksy.os.MarksyFormat
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** When a notification arrived, as short as it can be while still naming the day: "12:31", "Yesterday 18:05", "Sat, 26 Sep, 08:29", "16 Sep, 08:29". */
internal fun compactTime(postedAt: Long, now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): String? {
    if (postedAt <= 0) return null
    val at = Instant.ofEpochMilli(postedAt).atZone(zone)
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val days = ChronoUnit.DAYS.between(at.toLocalDate(), today)
    return when {
        days <= 0L -> MarksyFormat.time(at)
        days == 1L -> "Yesterday ${MarksyFormat.time(at)}"
        days < 7L -> "${MarksyFormat.weekdayDay(at)}, ${MarksyFormat.time(at)}"
        at.year == today.year -> MarksyFormat.dayTime(at)
        else -> MarksyFormat.fullDay(at)
    }
}
