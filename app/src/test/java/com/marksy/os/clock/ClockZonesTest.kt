package com.marksy.os.clock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

class ClockZonesTest {
    private val newYork = ZoneId.of("America/New_York")
    private val chicago = ZoneId.of("America/Chicago")
    private val kolkata = ZoneId.of("Asia/Kolkata")

    @Test fun defaultsWhenNothingSaved() {
        assertEquals(newYork to chicago, ClockZones.decode(null, null))
    }

    @Test fun badSavedIdFallsBackPerSlot() {
        assertEquals(ZoneId.of("Europe/London") to chicago, ClockZones.decode("Europe/London", "Not/AZone"))
    }

    @Test fun conversionFollowsDaylightSavingAcrossTheBoundary() {
        // US DST began 2026-03-08; India has none, so the gap moves from 10:30 to 9:30.
        val before = ClockZones.convert(LocalDate.of(2026, 3, 7), LocalTime.of(8, 0), newYork, kolkata)
        val after = ClockZones.convert(LocalDate.of(2026, 3, 9), LocalTime.of(8, 0), newYork, kolkata)
        assertEquals(LocalTime.of(18, 30), before.toLocalTime())
        assertEquals(LocalTime.of(17, 30), after.toLocalTime())
    }

    @Test fun newYorkAndChicagoStayOneHourApartAcrossDst() {
        val to = ClockZones.convert(LocalDate.of(2026, 3, 8), LocalTime.of(12, 0), newYork, chicago)
        assertEquals(LocalTime.of(11, 0), to.toLocalTime())
    }

    @Test fun dayShiftIsReportedWhenTheDateChanges() {
        val from = LocalDate.of(2026, 9, 24).atTime(22, 0).atZone(newYork)
        val to = from.withZoneSameInstant(kolkata)
        assertEquals("+1 day", ClockZones.dayShift(from, to))
        assertEquals("−1 day", ClockZones.dayShift(to, from))
        assertNull(ClockZones.dayShift(from, from.withZoneSameInstant(chicago)))
    }

    @Test fun abbreviationsFollowDst() {
        val summer = LocalDate.of(2026, 7, 1).atTime(12, 0).atZone(newYork)
        val winter = LocalDate.of(2026, 1, 15).atTime(12, 0).atZone(newYork)
        assertEquals("EDT", ClockZones.abbreviation(summer))
        assertEquals("EST", ClockZones.abbreviation(winter))
        assertEquals("CDT", ClockZones.abbreviation(summer.withZoneSameInstant(chicago)))
        assertEquals("IST", ClockZones.abbreviation(summer.withZoneSameInstant(kolkata)))
    }

    @Test fun zoneLabelShowsOffsetOnceWhenAbbreviationRepeats() {
        assertEquals("UTC", ClockZones.zoneLabel("UTC", "UTC"))
        assertEquals("EDT · UTC−4", ClockZones.zoneLabel("EDT", "UTC−4"))
        val at = Instant.parse("2026-07-01T12:00:00Z")
        assertEquals("BST", ClockZones.abbreviation(at.atZone(ZoneId.of("Europe/London"))))
        assertEquals("JST", ClockZones.abbreviation(at.atZone(ZoneId.of("Asia/Tokyo"))))
    }

    @Test fun resultRowsAlwaysIncludePhoneOnce() {
        val tokyo = ZoneId.of("Asia/Tokyo")
        assertEquals(listOf(newYork, chicago, tokyo, kolkata), ClockZones.resultZones(kolkata, newYork, chicago, tokyo))
        assertEquals(listOf(newYork, chicago), ClockZones.resultZones(newYork, newYork, chicago, chicago))
    }

    @Test fun searchMatchesCityAbbreviationAndOffset() {
        val at = Instant.parse("2026-07-01T12:00:00Z")
        assertTrue(ClockZones.search("tokyo", at).any { it.zone.id == "Asia/Tokyo" })
        assertTrue(ClockZones.search("edt", at).any { it.zone.id == "America/New_York" })
        assertTrue(ClockZones.search("utc+5:30", at).any { it.zone.id == "Asia/Kolkata" })
        assertEquals(ClockZones.COMMON.size, ClockZones.search("", at).size)
    }
}
