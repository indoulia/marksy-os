package com.marksy.os.upstox

import org.junit.Assert.assertEquals
import org.junit.Test

class UpstoxRestStatsTest {
    @Test fun callsAreGroupedByEndpointFamily() {
        assertEquals("ltp", UpstoxRestStats.family("https://api.upstox.com/v3/market-quote/ltp?instrument_key=x"))
        assertEquals("quotes", UpstoxRestStats.family("https://api.upstox.com/v2/market-quote/quotes?instrument_key=x"))
        assertEquals("candles", UpstoxRestStats.family("https://api.upstox.com/v3/historical-candle/intraday/x/minutes/5"))
        assertEquals("options", UpstoxRestStats.family("https://api.upstox.com/v2/option/chain?instrument_key=x"))
        assertEquals("fundamentals", UpstoxRestStats.family("https://api.upstox.com/v2/fundamentals/INE/profile"))
    }

    @Test fun statsCountFailuresAndAverageLatency() {
        val s = UpstoxRestStats.Stat().plus(ok = true, ms = 100, error = null, at = 1).plus(ok = false, ms = 300, error = "HTTP 500", at = 2)
        assertEquals(2, s.calls)
        assertEquals(1, s.failures)
        assertEquals(200L, s.averageMs)
        assertEquals("HTTP 500", s.lastError)
        assertEquals(1L, s.lastSuccessAt)
    }
}
