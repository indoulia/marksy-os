package com.marksy.os.market

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InstrumentModelsTest {
    @Test
    fun parsesInstrumentLifecycle() {
        val json = JSONObject(
            """
            {
              "symbol": "RELIANCE", "companyName": "Reliance Industries", "exchange": "NSE", "sector": "Energy", "isActive": true,
              "market": {"lastClosePrice": 1452.3, "asOfSessionDate": "2026-09-23T18:30:00Z", "freshnessState": "FRESH"},
              "predictionCount": 3, "openPredictionCount": 1,
              "predictions": [
                {
                  "predictionId": 501, "asOf": "2026-09-20T09:15:00Z", "horizonDays": 5,
                  "entryPrice": 1420.0, "targetPrice": 1470.0, "stopLoss": 1390.0,
                  "probabilityAtPublication": 0.71, "confidenceAtPublication": 0.8,
                  "lifecycleState": "ACTIVE", "lifecycleDetail": "Tracking toward target", "isTerminal": false,
                  "currentPrice": 1452.3, "currentReturn": 2.27, "targetProgress": 0.64, "stopProgress": 0.0,
                  "outcomeStatus": "PENDING", "realizedReturnPct": null, "hasResolvedOutcome": false,
                  "evidenceItemCount": 4
                }
              ]
            }
            """
        )

        val instrument = InstrumentLifecycleDto.parse(json)

        assertEquals("RELIANCE", instrument.symbol)
        assertEquals(1452.3, instrument.market.lastClosePrice!!, 1e-9)
        assertEquals("FRESH", instrument.market.freshnessState)
        val prediction = instrument.predictions.single()
        assertEquals(501, prediction.predictionId)
        assertEquals("ACTIVE", prediction.lifecycleState)
        assertFalse(prediction.hasResolvedOutcome)
        assertEquals(4, prediction.evidenceItemCount)
    }

    @Test
    fun instrumentWithNoPredictionsYieldsEmptyList() {
        val json = JSONObject(
            """{"symbol": "NEWCO", "companyName": null, "exchange": "NSE", "sector": null, "isActive": true, "market": {"lastClosePrice": null, "asOfSessionDate": null, "freshnessState": null}, "predictionCount": 0, "openPredictionCount": 0, "predictions": []}"""
        )

        val instrument = InstrumentLifecycleDto.parse(json)

        assertTrue(instrument.predictions.isEmpty())
        assertNull(instrument.market.lastClosePrice)
    }

    @Test
    fun parsesActivePredictionWithCompositeScore() {
        val json = JSONObject(
            """
            {
              "predictionId": 501, "symbol": "RELIANCE", "companyName": "Reliance Industries", "exchange": "NSE",
              "price": 1452.3, "targetPrice": 1470.0, "stopLoss": 1390.0, "horizon": 5, "remainingTradingDays": 2,
              "distanceToTargetPercent": 1.2, "distanceToStopLossPercent": -4.3,
              "confidence": 0.8, "trustScore": 0.77, "trustQuality": "HIGH",
              "status": "OPEN", "lifecycleState": "ACTIONABLE_NOW", "isActionableNow": true, "lifecycleDetail": "Within entry band",
              "entryPrice": 1420.0, "compositeOpportunityScore": 0.641613
            }
            """
        )

        val prediction = ActivePredictionDto.parse(json)

        assertEquals(501, prediction.predictionId)
        assertEquals("ACTIONABLE_NOW", prediction.lifecycleState)
        assertTrue(prediction.isActionableNow)
        assertEquals(0.641613, prediction.compositeOpportunityScore!!, 1e-9)
    }

    @Test
    fun parsesActivePredictionPageWithCursor() {
        val envelope = JSONObject(
            """
            {"data": [{"predictionId": 1, "symbol": "A", "companyName": null, "exchange": "NSE", "price": null, "targetPrice": 10.0, "stopLoss": 8.0, "horizon": 1, "remainingTradingDays": null, "distanceToTargetPercent": null, "distanceToStopLossPercent": null, "confidence": 0.5, "trustScore": null, "trustQuality": null, "status": "OPEN", "lifecycleState": "WATCHING", "isActionableNow": false, "lifecycleDetail": null, "entryPrice": 9.0, "compositeOpportunityScore": 0.5}],
             "meta": {"requestId": "r1", "timestamp": "2026-09-24T00:00:00Z", "pageSize": 25, "nextCursor": "abc123"}}
            """
        )

        val page = ActivePredictionPageDto.parse(envelope)

        assertEquals(1, page.items.size)
        assertEquals("abc123", page.nextCursor)
    }

    @Test
    fun missingNextCursorMeansLastPage() {
        val envelope = JSONObject("""{"data": [], "meta": {"requestId": "r1", "timestamp": "2026-09-24T00:00:00Z", "pageSize": 25}}""")

        val page = ActivePredictionPageDto.parse(envelope)

        assertTrue(page.items.isEmpty())
        assertNull(page.nextCursor)
    }
}
