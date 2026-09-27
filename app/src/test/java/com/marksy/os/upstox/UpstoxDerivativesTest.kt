package com.marksy.os.upstox

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UpstoxDerivativesTest {
    private fun side(ltp: Double, oi: Long, iv: Double) = """{"market_data":{"ltp":$ltp,"volume":10,"oi":$oi,"close_price":1},"option_greeks":{"iv":$iv,"delta":0.5}}"""
    private fun row(strike: Int, ceOi: Long, peOi: Long) =
        """{"expiry":"2026-10-27","strike_price":$strike,"underlying_spot_price":1510.0,"call_options":${side(20.0, ceOi, 21.0)},"put_options":${side(15.0, peOi, 23.0)}}"""

    @Test fun chainGivesPcrHighestOiStrikesAndAWindowAroundTheMoney() {
        val body = """{"status":"success","data":[${(1400..1620 step 20).joinToString(",") { row(it, ceOi = if (it == 1600) 90_000 else 10_000, peOi = if (it == 1440) 80_000 else 5_000) }}]}"""
        val chain = UpstoxDerivatives.chain(body)

        assertEquals(1510.0, chain.spot!!, 1e-9)
        assertEquals(1600.0, chain.resistance!!, 1e-9)
        assertEquals(1440.0, chain.support!!, 1e-9)
        assertEquals((80_000.0 + 11 * 5_000) / (90_000 + 11 * 10_000), chain.pcr!!, 1e-9)
        val window = chain.aroundSpot(2)
        assertEquals(listOf(1480.0, 1500.0, 1520.0, 1540.0), window.map { it.strike })
    }

    @Test fun expiriesAreDistinctAndSorted() {
        val body = """{"status":"success","data":[{"expiry":"2026-11-24","instrument_type":"CE"},{"expiry":"2026-10-27","instrument_type":"PE"},{"expiry":"2026-10-27","instrument_type":"CE"},{"expiry":"2026-10-27","instrument_type":"FUT"}]}"""
        assertEquals(listOf("2026-10-27", "2026-11-24"), UpstoxDerivatives.expiries(body))
    }
}
