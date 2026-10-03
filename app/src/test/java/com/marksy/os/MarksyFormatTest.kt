package com.marksy.os

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

class MarksyFormatTest {
    @Test
    fun rupeesUseIndianGroupingAndATrueMinus() {
        assertEquals("₹1,23,456.78", MarksyFormat.rupees(123456.784))
        assertEquals("₹12,34,567", MarksyFormat.rupees(1234566.6, decimals = 0))
        assertEquals("−₹12.50", MarksyFormat.rupees(-12.5))
        assertEquals("+₹999.00", MarksyFormat.signedRupees(999.0))
        assertEquals("−₹1,000.00", MarksyFormat.signedRupees(-1000.0))
    }

    @Test
    fun percentagesAreSignedUnlessZero() {
        assertEquals("+1.23%", MarksyFormat.percent(1.234))
        assertEquals("−0.50%", MarksyFormat.percent(-0.5))
        assertEquals("0.00%", MarksyFormat.percent(-0.001))
        assertEquals("12.3%", MarksyFormat.percent(12.34, decimals = 1, signed = false))
    }

    @Test
    fun datesReadDayMonthTime() {
        val at = ZonedDateTime.of(2026, 10, 3, 9, 5, 0, 0, ZoneId.of("Asia/Kolkata"))
        assertEquals("3 Oct", MarksyFormat.day(LocalDate.of(2026, 10, 3)))
        assertEquals("3 Oct, 09:05", MarksyFormat.dayTime(at))
        assertEquals("09:05", MarksyFormat.time(at))
        assertEquals("Sat, 3 Oct", MarksyFormat.weekdayDay(LocalDate.of(2026, 10, 3)))
        assertEquals("3 Oct 2026", MarksyFormat.fullDay(LocalDate.of(2026, 10, 3)))
        assertEquals("October 2026", MarksyFormat.monthYear(LocalDate.of(2026, 10, 3)))
        assertEquals("9:05 AM", MarksyFormat.time12(at))
        assertEquals("Sat", MarksyFormat.weekday(at))
        assertEquals("Sat, 09:05", MarksyFormat.weekdayTime(at))
        assertEquals("Oct 26", MarksyFormat.shortMonth(at))
        assertEquals("Oct", MarksyFormat.month(java.time.Month.OCTOBER))
        assertEquals("October", MarksyFormat.month(java.time.Month.OCTOBER, short = false))
    }

    @Test
    fun plainNumbersSuitTextFields() {
        assertEquals("1234.50", MarksyFormat.plain(1234.5))
        assertEquals("-3", MarksyFormat.plain(-3.0, decimals = 0))
    }
}
