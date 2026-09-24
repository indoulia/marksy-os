package com.marksy.os.market

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MarketModelsTest {
    @Test
    fun parsesMarketSummary() {
        val json = JSONObject(
            """
            {
              "asOf": "2026-09-24T09:43:21+05:30",
              "marketStatus": "MARKET_HOURS",
              "regime": "BULLISH_LOW_VOL",
              "advanceDecline": 0.62,
              "volume": 145000000,
              "volatility": 12.4,
              "indexes": [
                {"name": "NIFTY 50", "value": 25143.2, "changePct": 0.42, "change": 105.1}
              ],
              "sectorLeaders": [{"sector": "IT", "averageChangePct": 1.8}],
              "sectorLaggards": [{"sector": "PSU Bank", "averageChangePct": -0.9}],
              "topGainers": [{"symbol": "HAL", "name": "Hindustan Aeronautics", "price": 4100.0, "changePercent": 3.1, "change": 123.2, "volume": 500000}],
              "topLosers": []
            }
            """
        )

        val summary = MarketSummaryDto.parse(json)

        assertEquals("MARKET_HOURS", summary.marketStatus)
        assertEquals("2026-09-24T09:43:21+05:30", summary.asOf)
        assertEquals(1, summary.indexes.size)
        assertEquals("NIFTY 50", summary.indexes[0].name)
        assertEquals(105.1, summary.indexes[0].change!!, 1e-9)
        assertEquals("HAL", summary.topGainers.single().symbol)
        assertTrue(summary.topLosers.isEmpty())
        assertEquals("IT", summary.sectorLeaders.single().sector)
    }

    @Test
    fun missingOptionalMarketSummaryFieldsYieldNulls() {
        val summary = MarketSummaryDto.parse(JSONObject("""{"asOf": "2026-09-24T00:00:00Z", "marketStatus": "CLOSED", "indexes": [], "topGainers": [], "topLosers": [], "sectorLeaders": [], "sectorLaggards": []}"""))

        assertNull(summary.regime)
        assertNull(summary.advanceDecline)
        assertTrue(summary.indexes.isEmpty())
    }

    @Test
    fun parsesLiveQuotesResponse() {
        val json = JSONObject(
            """
            {
              "asOf": "2026-09-24T09:43:21+05:30",
              "marketSession": "MARKET_HOURS",
              "quotes": [
                {"symbol": "RELIANCE", "name": "Reliance Industries", "price": 1452.3, "prevClose": 1440.1, "changePercent": 0.85, "state": "LIVE", "provider": "upstox-v3-ws", "receivedAt": "2026-09-24T09:43:20Z", "ageSeconds": 1}
              ]
            }
            """
        )

        val response = LiveQuotesResponseDto.parse(json)

        assertEquals("MARKET_HOURS", response.marketSession)
        val quote = response.quotes.single()
        assertEquals("RELIANCE", quote.symbol)
        assertEquals("LIVE", quote.state)
        assertEquals(1, quote.ageSeconds)
    }

    @Test
    fun parsesLiveFeedHealth() {
        val json = JSONObject(
            """{"upstoxEnabled": true, "liveFeedEnabled": true, "feedState": "STREAMING", "fallbackActive": false, "cachedInstruments": 2888}"""
        )

        val health = LiveFeedHealthDto.parse(json)

        assertTrue(health.upstoxEnabled)
        assertEquals("STREAMING", health.feedState)
        assertEquals(2888, health.cachedInstruments)
    }

    @Test
    fun parsesIndexHistory() {
        val json = JSONObject(
            """
            {"name": "NIFTY50", "granularity": "DAILY", "points": [
              {"date": "2026-09-23T00:00:00Z", "close": 25100.0, "high": 25200.0, "low": 25000.0, "volume": 123456}
            ]}
            """
        )

        val history = IndexHistoryDto.parse(json)

        assertEquals("NIFTY50", history.name)
        assertEquals(1, history.points.size)
        assertEquals(25100.0, history.points[0].close, 1e-9)
    }

    @Test
    fun parsesSectorOptionList() {
        val json = org.json.JSONArray("""[{"name": "IT", "stockCount": 42}]""")

        val sectors = SectorOptionDto.parseList(json)

        assertEquals(1, sectors.size)
        assertEquals(42, sectors[0].stockCount)
    }
}
