package com.marksy.os.plan

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/** How plan items read on cards and alerts: "Today", "In 3 days · 28 Sep", "Overdue · 2 days", "₹1,25,000". */
object PlanText {
    private val dayMonth = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)
    private val dayMonthYear = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)
    private val time = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)

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
            days < 7L -> "In $days days · ${due.format(dayMonth)}"
            due.year != today.year -> due.format(dayMonthYear)
            else -> due.format(dayMonth)
        }
        // Dues sit at the alert hour; anything else (follow-ups, timed tasks) shows its time.
        val timed = due.hour != PlanRules.ALERT_HOUR || due.minute != 0
        return if (timed && days >= 0) "$label · ${due.format(time)}" else label
    }

    fun amount(minor: Long?): String? {
        if (minor == null) return null
        val rupees = indianGrouping(minor / 100)
        val paise = minor % 100
        return if (paise == 0L) "₹$rupees" else "₹$rupees.${paise.toString().padStart(2, '0')}"
    }

    private fun indianGrouping(n: Long): String {
        val s = n.toString()
        if (s.length <= 3) return s
        val head = s.dropLast(3).reversed().chunked(2).joinToString(",").reversed()
        return "$head,${s.takeLast(3)}"
    }
}
