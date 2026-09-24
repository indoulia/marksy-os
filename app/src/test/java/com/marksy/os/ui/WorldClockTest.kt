package com.marksy.os.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

class WorldClockTest {
    private val newYork = ZoneId.of("America/New_York")

    @Test
    fun convertsMeetingTimeAcrossZonesHonouringDaylightSaving() {
        // 8 AM New York in September is EDT (UTC-4) → 17:30 IST.
        val ist = WorldClock.convert(LocalDate.of(2026, 9, 24), LocalTime.of(8, 0), newYork, WorldClock.HOME_ZONE)
        assertEquals(LocalTime.of(17, 30), ist.toLocalTime())
        // January is EST (UTC-5) → 18:30 IST.
        val winter = WorldClock.convert(LocalDate.of(2026, 1, 15), LocalTime.of(8, 0), newYork, WorldClock.HOME_ZONE)
        assertEquals(LocalTime.of(18, 30), winter.toLocalTime())
    }

    @Test
    fun reportsDayShiftBetweenZones() {
        val from = LocalDate.of(2026, 9, 24).atTime(22, 0).atZone(newYork)
        val to = from.withZoneSameInstant(WorldClock.HOME_ZONE)
        assertEquals("+1 day", WorldClock.dayShift(from, to))
        assertEquals("−1 day", WorldClock.dayShift(to, from))
        assertNull(WorldClock.dayShift(from, from.withZoneSameInstant(ZoneId.of("America/Chicago"))))
    }

    @Test
    fun abbreviationTracksDaylightSaving() {
        val summer = LocalDate.of(2026, 7, 1).atStartOfDay(newYork)
        val winter = LocalDate.of(2026, 1, 1).atStartOfDay(newYork)
        assertEquals("EDT", WorldClock.abbreviation(summer))
        assertEquals("EST", WorldClock.abbreviation(winter))
        assertEquals("IST", WorldClock.abbreviation(summer.withZoneSameInstant(WorldClock.HOME_ZONE)))
    }

    @Test
    fun unknownStoredZoneFallsBackToDefault() {
        assertEquals(WorldClock.DEFAULT_SECOND_ZONE, WorldClock.parseZone("Not/AZone"))
        assertEquals(ZoneId.of("Europe/London"), WorldClock.parseZone("Europe/London"))
    }
}
