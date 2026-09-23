package com.marksy.os.notification

import java.time.Instant
import java.time.ZoneId

/** Quick "Remind me" choices, Gmail-snooze style. */
object ReminderTimes {
    data class Option(val label: String, val atMillis: Long)

    private const val EVENING_HOUR = 18
    private const val MORNING_HOUR = 9

    fun options(nowMillis: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): List<Option> {
        val now = Instant.ofEpochMilli(nowMillis).atZone(zone)
        val evening = now.toLocalDate().atTime(EVENING_HOUR, 0).atZone(zone)
        val morning = now.toLocalDate().plusDays(1).atTime(MORNING_HOUR, 0).atZone(zone)
        return buildList {
            add(Option("In 1 hour", now.plusHours(1).toInstant().toEpochMilli()))
            // Only offered while it is still at least an hour away.
            if (now.plusHours(1).isBefore(evening)) add(Option("This evening", evening.toInstant().toEpochMilli()))
            add(Option("Tomorrow morning", morning.toInstant().toEpochMilli()))
        }
    }
}
