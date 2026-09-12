package com.marksy.os.gateway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Deterministic fixture coverage for the Android -> Marksy Tips wire payload. */
class MarksyTipPayloadFixtureTest {
    @Test
    fun richTradingNotificationProducesCanonicalAndContextFields() {
        val request = MarksyTradingEventRequest(
            eventId = 42L,
            source = "ICICIDirect",
            sourcePackage = "com.icicidirect",
            title = "HLEGLAS BUY",
            body = "Entry 367.95 Target 390 Stop Loss 355 for 5 days confidence 70% rationale: breakout",
            category = "TRADING",
            priority = 3,
            confidence = 0.97f,
            occurredAt = 1_789_123_456_000L,
            idempotencyKey = "event-42"
        )

        val payload = MarksyTipPayloadBuilder.from(request)
        requireNotNull(payload)
        val json = payload.toJson()

        assertEquals("HLEGLAS", json.getString("symbol"))
        assertEquals("ICICIDirect", json.getString("source"))
        assertEquals("event-42", json.getString("sourceReference"))
        assertEquals("BUY", json.getString("direction"))
        assertEquals(367.95, json.getDouble("entryPrice"), 0.0001)
        assertEquals(390.0, json.getDouble("targetPrice"), 0.0001)
        assertEquals(355.0, json.getDouble("stopLoss"), 0.0001)
        assertEquals(5, json.getInt("horizonDays"))
        assertEquals(0.70, json.getDouble("confidence"), 0.0001)
        assertEquals(42L, json.getLong("eventId"))
        assertEquals("com.icicidirect", json.getString("sourcePackage"))
        assertEquals("TRADING", json.getString("category"))
        assertEquals(3, json.getInt("priority"))
        assertEquals(0.97, json.getDouble("notificationConfidence"), 0.0001)
        assertEquals(1, json.getInt("contractVersion"))
    }

    @Test
    fun missingOptionalTradeFieldsAreNotInvented() {
        val request = MarksyTradingEventRequest(
            eventId = 7L,
            source = "Upstox",
            sourcePackage = "com.upstox.pro",
            title = "RELIANCE market alert",
            body = "Unusual volume detected",
            category = "TRADING",
            priority = 2,
            confidence = 0.91f,
            occurredAt = 1_789_123_456_000L,
            idempotencyKey = "event-7"
        )

        val payload = MarksyTipPayloadBuilder.from(request)
        requireNotNull(payload)
        val json = payload.toJson()

        assertEquals("RELIANCE", json.getString("symbol"))
        assertFalse(json.has("direction"))
        assertFalse(json.has("entryPrice"))
        assertFalse(json.has("targetPrice"))
        assertFalse(json.has("stopLoss"))
        assertFalse(json.has("horizonDays"))
        assertFalse(json.has("confidence"))
        assertFalse(json.has("rationale"))
    }

    @Test
    fun unsupportedTradingNotificationWithoutSymbolIsRejected() {
        val request = MarksyTradingEventRequest(
            eventId = 8L,
            source = "Upstox",
            sourcePackage = "com.upstox.pro",
            title = "BUY order executed",
            body = "Order executed successfully",
            category = "TRADING",
            priority = 2,
            confidence = 0.9f,
            occurredAt = 1_789_123_456_000L,
            idempotencyKey = "event-8"
        )

        assertTrue(MarksyTipPayloadBuilder.from(request) == null)
    }
}
