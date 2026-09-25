package com.marksy.os.plan

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

object PlanRules {
    const val ALERT_HOUR = 9
    private const val DAY_MS = 24 * 60 * 60 * 1000L

    /** Critical from one day before the due time (and while overdue) until done. */
    fun isCritical(status: PlanStatus, dueAt: Long?, now: Long): Boolean =
        status != PlanStatus.DONE && dueAt != null && dueAt - now <= DAY_MS

    /** Dues alert 3 days and 1 day ahead and on the day; tasks and follow-ups only at their own time. */
    fun alertTimes(kind: PlanKind, dueAt: Long, now: Long): List<Long> {
        val times = if (kind == PlanKind.TASK || kind == PlanKind.FOLLOW_UP) listOf(dueAt)
        else listOf(dueAt - 3 * DAY_MS, dueAt - DAY_MS, dueAt)
        return times.filter { it > now }
    }

    fun next(dueAt: Long, recurrence: Recurrence, zone: ZoneId = ZoneId.systemDefault()): Long? {
        val at = Instant.ofEpochMilli(dueAt).atZone(zone)
        return when (recurrence) {
            Recurrence.NONE -> null
            Recurrence.MONTHLY -> at.plusMonths(1).toInstant().toEpochMilli()
            Recurrence.YEARLY -> at.plusYears(1).toInstant().toEpochMilli()
        }
    }

    fun nextBirthday(month: Int, day: Int, now: Long, zone: ZoneId = ZoneId.systemDefault()): Long {
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        fun inYear(year: Int) = LocalDate.of(year, month, 1).let { it.withDayOfMonth(day.coerceAtMost(it.lengthOfMonth())) }
        val thisYear = inYear(today.year)
        return atAlertHour(if (thisYear < today) inYear(today.year + 1) else thisYear, zone)
    }

    fun atAlertHour(date: LocalDate, zone: ZoneId = ZoneId.systemDefault()): Long =
        date.atTime(ALERT_HOUR, 0).atZone(zone).toInstant().toEpochMilli()
}
