package com.marksy.os.upstox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UpstoxMarketDataTest {
    private val quoteBody = """
        {"status":"success","data":{"NSE_EQ:INFY":{
          "ohlc":{"open":1010.0,"high":1021.5,"low":1004.2,"close":1014.5},
          "depth":{"buy":[{"quantity":120,"price":1014.4,"orders":3},{"quantity":80,"price":1014.3,"orders":2}],
                   "sell":[{"quantity":50,"price":1014.6,"orders":1}]},
          "instrument_token":"NSE_EQ|INE009A01021","symbol":"INFY","last_price":1014.5,"volume":5234567,"average_price":1012.87,
          "net_change":-6.3,"total_buy_quantity":412000,"total_sell_quantity":389000,"lower_circuit_limit":918.7,
          "upper_circuit_limit":1122.9,"last_trade_time":"1790323199000"}}}
    """.trimIndent()

    @Test fun fullQuoteReadsPriceRangesCircuitsAndDepth() {
        val q = UpstoxQuote.parse(quoteBody)
        assertEquals(1014.5, q.lastPrice, 0.0)
        assertEquals(1020.8, q.prevClose!!, 1e-9)
        assertEquals(-0.617, q.changePct!!, 1e-3)
        assertEquals(1021.5, q.high!!, 0.0)
        assertEquals(1122.9, q.upperCircuit!!, 0.0)
        assertEquals(5_234_567L, q.volume)
        assertEquals(listOf(1014.4, 1014.3), q.bids.map { it.price })
        assertEquals(50L, q.asks.single().quantity)
        assertEquals(1_790_323_199_000L, q.lastTradeTime)
    }

    // Upstox returns candles newest first; charts and ranges need oldest first.
    @Test fun candlesAreOldestFirstAndGiveRangesAndTheLastSession() {
        val body = """{"status":"success","data":{"candles":[
            ["2026-09-25T15:25:00+05:30",1015,1016,1014,1014.5,1200,0],
            ["2026-09-25T09:15:00+05:30",1010,1012,1008,1011,5000,0],
            ["2026-09-24T15:25:00+05:30",1019,1021,1018,1020.8,900,0]]}}"""
        val candles = UpstoxCandles.parse(body)
        assertEquals(listOf(1020.8, 1011.0, 1014.5), candles.map { it.close })
        assertEquals(1008.0 to 1021.0, UpstoxCandles.range(candles))
        assertEquals(listOf(1011.0, 1014.5), UpstoxCandles.lastSession(candles, java.time.ZoneId.of("Asia/Kolkata")).map { it.close })
        assertNull(UpstoxCandles.range(emptyList()))
    }

    // Returns over standard periods come from the year of daily candles already fetched for the 52-week range.
    @Test fun returnsAndAverageVolumeFromDailyCandles() {
        val day = 86_400_000L
        val end = 1_790_000_000_000L
        val daily = (0..365).map { i -> Candle(end - (365 - i) * day, 0.0, 0.0, 0.0, 100.0 + i, 1_000L + i) }
        val r = UpstoxCandles.returns(daily, lastPrice = 465.0)
        assertEquals(listOf("1W", "1M", "3M", "6M", "1Y"), r.keys.toList())
        assertEquals((465.0 - 458.0) / 458.0 * 100, r.getValue("1W"), 1e-9)
        assertEquals((465.0 - 100.0) / 100.0 * 100, r.getValue("1Y"), 1e-9)
        // Seen on device: a year of daily candles starts a day after the 1-year mark; the first candle is the base.
        assertEquals((465.0 - 101.0) / 101.0 * 100, UpstoxCandles.returns(daily.drop(1), 465.0).getValue("1Y"), 1e-9)
        assertEquals((1346L + 1365L) / 2, UpstoxCandles.averageVolume(daily, 20))
        assertEquals(emptyMap<String, Double>(), UpstoxCandles.returns(emptyList(), 1.0))
    }

    @Test fun chartRangesMapToUpstoxCandleEndpoints() {
        val key = "NSE_EQ|INE009A01021"
        val today = LocalDate.of(2026, 9, 25)
        assertEquals("historical-candle/intraday/NSE_EQ%7CINE009A01021/minutes/5", ChartRange.D1.path(key, today))
        assertEquals("historical-candle/NSE_EQ%7CINE009A01021/minutes/5/2026-09-25/2026-09-18", ChartRange.D1.fallbackPath(key, today))
        assertEquals("historical-candle/NSE_EQ%7CINE009A01021/minutes/30/2026-09-25/2026-09-18", ChartRange.W1.path(key, today))
        assertEquals("historical-candle/NSE_EQ%7CINE009A01021/days/1/2026-09-25/2025-09-25", ChartRange.Y1.path(key, today))
        assertEquals("historical-candle/NSE_EQ%7CINE009A01021/weeks/1/2026-09-25/2021-09-25", ChartRange.Y5.path(key, today))
    }

    @Test fun indexKeysWithSpacesArePathEncoded() {
        val today = LocalDate.of(2026, 9, 25)
        assertEquals("historical-candle/NSE_INDEX%7CNifty%2050/days/1/2026-09-25/2025-09-25", ChartRange.Y1.path("NSE_INDEX|Nifty 50", today))
        assertEquals("historical-candle/NSE_INDEX%7CNifty%2050/minutes/5/2026-09-25/2026-09-18", ChartRange.D1.fallbackPath("NSE_INDEX|Nifty 50", today))
    }
}
