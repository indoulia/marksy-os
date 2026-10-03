package com.marksy.os.plan

import com.marksy.os.MarksyFormat
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** How plan items read on cards and alerts: "Today", "In 3 days · 28 Sep", "Overdue · 2 days", "₹1,25,000". */
object PlanText {
    fun dueLabel(dueAt: Long?, now: Long, zone: ZoneId = ZoneId.systemDefault()): String? {
        if (dueAt == null) return null
        val due = Instant.ofEpochMilli(dueAt).atZone(zone)
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val days = ChronoUnit.DAYS.between(today, due.toLocalDate())
        val label = when {
            days == 0L -> "Today"
            days == 1L -> "Tomorrow"
            days == -1L -> "Overdue · yesterday"
            days < -1L -> "Overdue · ${-days} days"
            days < 7L -> "In $days days · ${MarksyFormat.day(due.toLocalDate())}"
            due.year != today.year -> MarksyFormat.fullDay(due)
            else -> MarksyFormat.day(due.toLocalDate())
        }
        // Dues sit at the alert hour; anything else (follow-ups, timed tasks) shows its time.
        val timed = due.hour != PlanRules.ALERT_HOUR || due.minute != 0
        return if (timed && days >= 0) "$label · ${MarksyFormat.time(due)}" else label
    }

    fun amount(minor: Long?): String? {
        if (minor == null) return null
        return MarksyFormat.rupees(minor / 100.0, if (minor % 100 == 0L) 0 else 2)
    }
}
