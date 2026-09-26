package com.marksy.os.upstox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class TechnicalRatingTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private val start = LocalDate.of(2025, 9, 1)

    /** One candle per weekday from [start], closing at each of [closes]; high/low one rupee either side. */
    private fun days(closes: List<Double>): List<Candle> {
        var d = start
        return closes.map { c ->
            while (d.dayOfWeek.value > 5) d = d.plusDays(1)
            Candle(d.atStartOfDay(zone).toInstant().toEpochMilli(), c, c + 1, c - 1, c, 1000).also { d = d.plusDays(1) }
        }
    }

    @Test fun smaAveragesTheLastNCloses() {
        assertEquals(4.0, Technicals.sma(listOf(1.0, 2.0, 3.0, 4.0, 5.0), 3)!!, 1e-9)
        assertNull(Technicals.sma(listOf(1.0, 2.0), 3))
    }

    @Test fun rsiIsFiftyForEqualGainsAndLossesAndHundredForOnlyGains() {
        val alternating = (0..14).map { if (it % 2 == 0) 10.0 else 11.0 }
        assertEquals(50.0, Technicals.rsi(alternating)!!, 1e-9)
        assertEquals(100.0, Technicals.rsi((0..20).map { 10.0 + it })!!, 1e-9)
        assertNull(Technicals.rsi((0..10).map { it.toDouble() }))
    }

    @Test fun oscillatorsReadACloseAtTheTopOfItsRange() {
        val rising = days((0..30).map { 100.0 + it })
        // Close 130 against a 14-day range of 116..131 (lows/highs are one rupee either side).
        assertEquals(-1.0 / 15 * 100, Technicals.williamsR(rising)!!, 1e-6)
        assertTrue(Technicals.stochastic(rising)!! > 90)
    }

    @Test fun steadyUptrendRatesBullishWithOverboughtOscillators() {
        val daily = days((0 until 260).map { 100.0 * Math.pow(1.004, it.toDouble()) })
        val r = Technicals.rate(daily)!!
        assertEquals(6, r.movingAverages.size)
        assertTrue(r.movingAverages.all { it.signal == Signal.BULLISH })
        assertTrue(r.crossovers.all { it.signal == Signal.BULLISH })
        assertEquals(Signal.BEARISH, r.indicators.first { it.name.startsWith("RSI") }.signal)
        assertEquals(Signal.BULLISH, r.indicators.first { it.name.startsWith("MACD") }.signal)
        assertEquals(Trend.VERY_BULLISH, r.trend)
    }

    @Test fun downtrendRatesBearish() {
        val daily = days((0 until 260).map { 500.0 * Math.pow(0.996, it.toDouble()) })
        val r = Technicals.rate(daily)!!
        assertTrue(r.movingAverages.all { it.signal == Signal.BEARISH })
        assertTrue(r.trend == Trend.BEARISH || r.trend == Trend.VERY_BEARISH)
    }

    @Test fun shortHistoryRatesOnlyWhatItCanMeasure() {
        val r = Technicals.rate(days((0 until 60).map { 100.0 + it }))!!
        assertEquals(listOf("SMA 5", "SMA 10", "SMA 20", "SMA 50"), r.movingAverages.map { it.name })
        assertEquals(listOf("SMA 5 × 20", "SMA 20 × 50"), r.crossovers.map { it.name })
        assertNull(Technicals.rate(days(listOf(1.0, 2.0))))
    }

    @Test fun historyRatesEachWeeksLastSessionNewestFirst() {
        val daily = days((0 until 260).map { 100.0 + it })
        val history = Technicals.history(daily, weeks = 4, zone = zone)
        assertEquals(4, history.size)
        assertEquals(daily.last().time, history.first().first.time)
        assertTrue(history.zipWithNext().all { (a, b) -> a.first.time > b.first.time })
    }

    @Test fun classicPivotsComeFromTheLastSessionBeforeToday() {
        val daily = days(listOf(100.0, 110.0, 120.0))
        val today = LocalDate.of(2025, 9, 3)
        // Session of 2 Sept: high 111, low 109, close 110.
        val p = Technicals.pivots(daily, today, zone)!!
        assertEquals(110.0, p.pivot, 1e-9)
        assertEquals(111.0, p.r1, 1e-9)
        assertEquals(109.0, p.s1, 1e-9)
        assertEquals(112.0, p.r2, 1e-9)
        assertEquals(108.0, p.s2, 1e-9)
        assertEquals(daily[1].time, p.from)
    }
}
