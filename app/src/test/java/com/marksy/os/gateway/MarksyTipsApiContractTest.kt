package com.marksy.os.gateway

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarksyTipsApiContractTest {

    @Test
    fun createdTipResponseSupportsConfirmedEnvelope() {
        val envelope = JSONObject("""
            {"data":{"tipId":"tip-123","status":"COMPARED","receivedAt":"2026-09-12T09:30:00+05:30"},"meta":{}}
        """.trimIndent())

        val data = envelope.getJSONObject("data")
        assertTrue(envelope.has("data"))
        assertTrue(envelope.has("meta"))
        assertEquals("tip-123", data.getString("tipId"))
        assertEquals("COMPARED", data.getString("status"))
    }

    @Test
    fun unresolvedSymbolResponseRemainsAValidTipResult() {
        val data = JSONObject("""
            {"tipId":"tip-456","status":"UNRESOLVED_SYMBOL","receivedAt":"2026-09-12T09:31:00+05:30"}
        """.trimIndent())

        assertEquals("tip-456", data.getString("tipId"))
        assertEquals("UNRESOLVED_SYMBOL", data.getString("status"))
    }

    @Test
    fun comparisonResponseMapsAllDecisionStates() {
        listOf("AGREE", "PARTIAL", "DISAGREE", "NO_VIEW").forEach { verdict ->
            val comparison = JSONObject().put("verdict", verdict)
            assertEquals(verdict, comparison.getString("verdict"))
        }
    }

    @Test
    fun marksyViewContainsConfirmedAnalysisFields() {
        val view = JSONObject("""
            {
              "recommendation":"BUY",
              "probability":0.72,
              "opportunityScore":81.0,
              "confidence":0.74,
              "trustScore":0.88,
              "trustQuality":"HIGH",
              "uncertaintyLevel":"LOW",
              "entryPrice":367.95,
              "targetPrice":390.0,
              "stopLoss":355.0,
              "upsidePct":5.99,
              "horizonDays":5,
              "levelState":"VALID",
              "modelVersion":"v1",
              "asOf":"2026-09-12T09:35:00+05:30",
              "failedCriteria":[],
              "decisionOutcome":"PUBLISH",
              "evidence":["breakout"]
            }
        """.trimIndent())

        assertEquals("BUY", view.getString("recommendation"))
        assertEquals(0.72, view.getDouble("probability"), 0.0001)
        assertEquals(81.0, view.getDouble("opportunityScore"), 0.0001)
        assertEquals(0.88, view.getDouble("trustScore"), 0.0001)
        assertEquals(367.95, view.getDouble("entryPrice"), 0.0001)
        assertEquals(390.0, view.getDouble("targetPrice"), 0.0001)
        assertEquals(355.0, view.getDouble("stopLoss"), 0.0001)
        assertEquals(5, view.getInt("horizonDays"))
        assertTrue(view.getJSONArray("evidence").length() > 0)
        assertEquals(0, view.getJSONArray("failedCriteria").length())
    }

    @Test
    fun invalidEnvelopeIsDetectable() {
        val envelope = JSONObject("""{"data":{"tipId":"tip-1"}}""")
        assertTrue(envelope.has("data"))
        assertFalse(envelope.has("meta"))
    }
}
