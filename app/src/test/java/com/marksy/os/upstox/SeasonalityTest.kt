package com.marksy.os.upstox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class SeasonalityTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private fun month(y: Int, m: Int, open: Double, close: Double, high: Double = maxOf(open, close), low: Double = minOf(open, close)) =
        Candle(LocalDate.of(y, m, 1).atStartOfDay(zone).toInstant().toEpochMilli(), open, high, low, close, 1)

    @Test fun monthlyReturnsCompareWithThePreviousMonthsClose() {
        val t = Seasonality.table(listOf(month(2024, 11, 100.0, 110.0), month(2024, 12, 110.0, 99.0), month(2025, 1, 99.0, 108.9)), zone)
        assertEquals(listOf(2025, 2024), t.keys.toList())
        assertEquals(10.0, t.getValue(2024)[10]!!, 1e-9)
        assertEquals(-10.0, t.getValue(2024)[11]!!, 1e-9)
        assertEquals(10.0, t.getValue(2025)[0]!!, 1e-9)
        assertNull(t.getValue(2025)[1])
    }

    @Test fun monthSummaryCountsNegativeYearsAndExtremes() {
        val candles = listOf(
            month(2022, 8, 100.0, 100.0), month(2022, 9, 100.0, 90.0),
            month(2023, 8, 90.0, 100.0), month(2023, 9, 100.0, 120.0),
            month(2024, 8, 120.0, 100.0), month(2024, 9, 100.0, 95.0)
        )
        val s = Seasonality.summary(Seasonality.table(candles, zone), 9)!!
        assertEquals(3, s.years)
        assertEquals(2, s.negative)
        assertEquals(20.0, s.best.first, 1e-9)
        assertEquals(2023, s.best.second)
        assertEquals(-10.0, s.worst.first, 1e-9)
        assertEquals(2022, s.worst.second)
        assertEquals(-7.5, s.averageLoss!!, 1e-9)
        assertEquals(5.0 / 3 * 1, s.average, 1e-9)
    }

    @Test fun longReturnsUseTheCloseBeforeEachPeriodAndYtdTheLastYearEnd() {
        val candles = (0 until 72).map { i -> val d = LocalDate.of(2020, 10, 1).plusMonths(i.toLong()); month(d.year, d.monthValue, 100.0 + i, 101.0 + i) }
        // Last candle: Sep 2026, close 172. End of 2025 (Dec) close = 101 + 62 = 163.
        val r = Seasonality.longReturns(candles, lastPrice = 172.0, zone = zone)
        assertEquals((172.0 - 163.0) / 163.0 * 100, r.getValue("YTD"), 1e-9)
        // Three years back from Sep 2026 is the Sep 2023 close (101 + 35 = 136).
        assertEquals((172.0 - 136.0) / 136.0 * 100, r.getValue("3Y"), 1e-9)
        assertEquals(setOf("YTD", "3Y", "5Y"), r.keys)
    }

    @Test fun allTimeRangeSpansEveryMonth() {
        val r = Seasonality.allTime(listOf(month(2001, 1, 10.0, 12.0, high = 15.0, low = 4.0), month(2020, 3, 40.0, 30.0, high = 44.0, low = 28.0)))!!
        assertEquals(4.0, r.first.first, 1e-9)
        assertEquals(44.0, r.second.first, 1e-9)
    }

    @Test fun betaIsTheSlopeOfDailyReturnsAgainstTheIndex() {
        val days = (0 until 40).map { LocalDate.of(2026, 1, 1).plusDays(it.toLong()).atStartOfDay(zone).toInstant().toEpochMilli() }
        var m = 100.0; var s = 100.0
        val index = mutableListOf<Candle>(); val stock = mutableListOf<Candle>()
        days.forEachIndexed { i, t ->
            val r = if (i % 2 == 0) .01 else -.008
            if (i > 0) { m *= 1 + r; s *= 1 + 2 * r }
            index += Candle(t, m, m, m, m, 1); stock += Candle(t, s, s, s, s, 1)
        }
        assertEquals(2.0, Technicals.beta(stock, index, zone)!!, 1e-6)
    }
}
