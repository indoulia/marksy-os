package com.marksy.os.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class CompactTimeTest {
    private val ist = ZoneId.of("Asia/Kolkata")
    private val now = ZonedDateTime.of(2026, 9, 28, 12, 45, 0, 0, ist)
    private fun label(at: ZonedDateTime) = compactTime(at.toInstant().toEpochMilli(), now.toInstant().toEpochMilli(), ist)

    // User: "almost impossible" to tell when a notification arrived — "HH:mm" alone or "3d ago" hid the day.
    @Test fun labelCarriesTheDayOnceItIsNotToday() {
        assertEquals("12:31", label(now.withHour(12).withMinute(31)))
        assertEquals("Yesterday 18:05", label(now.minusDays(1).withHour(18).withMinute(5)))
        assertEquals("Sat, 26 Sep, 08:29", label(now.minusDays(2).withHour(8).withMinute(29)))
        assertEquals("16 Sep, 08:29", label(now.minusDays(12).withHour(8).withMinute(29)))
        assertEquals("16 Sep 2025", label(now.minusYears(1).minusDays(12)))
    }
}
