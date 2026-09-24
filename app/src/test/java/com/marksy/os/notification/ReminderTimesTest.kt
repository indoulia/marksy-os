package com.marksy.os.notification

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneOffset

class ReminderTimesTest {
    private val zone = ZoneOffset.UTC
    private fun at(hour: Int, minute: Int = 0) = LocalDateTime.of(2026, 9, 23, hour, minute).toInstant(zone).toEpochMilli()
    private fun tomorrow(hour: Int) = LocalDateTime.of(2026, 9, 24, hour, 0).toInstant(zone).toEpochMilli()

    @Test
    fun afternoonOffersHourEveningAndTomorrowMorning() {
        val options = ReminderTimes.options(at(14, 30), zone)

        assertEquals(listOf("In 1 hour", "This evening", "Tomorrow morning"), options.map { it.label })
        assertEquals(listOf(at(15, 30), at(18), tomorrow(9)), options.map { it.atMillis })
    }

    @Test
    fun lateEveningDropsThisEvening() {
        assertEquals(listOf("In 1 hour", "Tomorrow morning"), ReminderTimes.options(at(19), zone).map { it.label })
    }
}
