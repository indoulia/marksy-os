package com.marksy.os.upstox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class ChartOverlaysTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private fun at(day: Int, h: Int, m: Int) = LocalDateTime.of(2026, 9, day, h, m).atZone(zone).toInstant().toEpochMilli()
    private fun candle(t: Long, close: Double, vol: Long = 100) = Candle(t, close, close + 1, close - 1, close, vol)

    @Test fun emaIsAlignedToItsInputAndSeededWithTheSma() {
        val e = ChartOverlays.ema(listOf(1.0, 2.0, 3.0, 4.0, 5.0), 3)
        assertEquals(5, e.size)
        assertNull(e[1])
        assertEquals(2.0, e[2]!!, 1e-9)
        assertEquals(3.0, e[3]!!, 1e-9)
    }

    @Test fun bollingerBandsCollapseOnAFlatSeries() {
        val b = ChartOverlays.bollinger(List(25) { 100.0 })
        assertNull(b.mid[18])
        assertEquals(100.0, b.upper[24]!!, 1e-9)
        assertEquals(100.0, b.lower[24]!!, 1e-9)
    }

    @Test fun vwapRestartsEachSession() {
        val c = listOf(candle(at(24, 9, 15), 100.0), candle(at(24, 9, 20), 110.0, vol = 300), candle(at(25, 9, 15), 200.0))
        val v = ChartOverlays.vwap(c, zone)
        assertEquals(100.0, v[0]!!, 1e-9)
        assertEquals(107.5, v[1]!!, 1e-9)
        assertEquals(200.0, v[2]!!, 1e-9)
    }

    @Test fun supertrendFollowsTheTrendSide() {
        val up = ChartOverlays.supertrend((0 until 40).map { candle(at(1 + it % 28, 10, 0) + it, 100.0 + it * 2) })
        val lastUp = up.last()!!
        assertTrue(lastUp.second && lastUp.first < 100.0 + 39 * 2)
        val down = ChartOverlays.supertrend((0 until 40).map { candle(at(1 + it % 28, 10, 0) + it, 200.0 - it * 2) })
        val lastDown = down.last()!!
        assertTrue(!lastDown.second && lastDown.first > 200.0 - 39 * 2)
    }
}
