package com.marksy.os.gateway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MarksyTipPayloadFixtureTest {
    @Test
    fun richTradingNotificationProducesCanonicalFieldsAndContext() {
        val request = MarksyTradingEventRequest(
            eventId = 42L,
            source = "ICICI Direct",
            sourcePackage = "com.icicidirect",
            title = "HLEGLAS BUY order executed",
            body = "Symbol: HLEGLAS Entry: ₹367.95 Target: ₹390 Stop Loss: ₹355 Horizon: 5 days Confidence: 70% Rationale: breakout",
            category = "TRADING",
            priority = 10,
            confidence = 0.96f,
            occurredAt = 1_757_650_000_000L,
            idempotencyKey = "msg-2026-09-12-0001"
        )

        val payload = MarksyTipPayloadBuilder.from(request)!!
        val json = payload.toJson()

        assertEquals("HLEGLAS", payload.symbol)
        assertEquals("BUY", payload.direction)
        assertEquals(367.95, payload.entryPrice!!, 0.001)
        assertEquals(390.0, payload.targetPrice!!, 0.001)
        assertEquals(355.0, payload.stopLoss!!, 0.001)
        assertEquals(5, payload.horizonDays)
        assertEquals(0.70, payload.confidence!!, 0.001)
        assertEquals("msg-2026-09-12-0001", json.getString("sourceReference"))
        assertEquals("com.icicidirect", json.getString("sourcePackage"))
        assertEquals(42L, json.getLong("eventId"))
    }

    @Test
    fun missingOptionalTradeFieldsAreNotInvented() {
        val request = MarksyTradingEventRequest(
            eventId = 7L,
            source = "Upstox",
            sourcePackage = "com.upstox.pro",
            title = "HLEGLAS BUY alert",
            body = "Symbol: HLEGLAS",
            category = "TRADING",
            priority = 8,
            confidence = 0.90f,
            occurredAt = 1_757_650_000_000L,
            idempotencyKey = "msg-2"
        )

        val payload = MarksyTipPayloadBuilder.from(request)!!
        assertEquals("HLEGLAS", payload.symbol)
        assertEquals("BUY", payload.direction)
        assertNull(payload.entryPrice)
        assertNull(payload.targetPrice)
        assertNull(payload.stopLoss)
        assertNull(payload.horizonDays)
        assertNull(payload.confidence)
        assertNull(payload.rationale)
    }

    @Test
    fun unsupportedTradingNotificationWithoutSymbolIsRejected() {
        val request = MarksyTradingEventRequest(
            eventId = 8L,
            source = "Upstox",
            sourcePackage = "com.upstox.pro",
            title = "Order executed",
            body = "BUY order executed at ₹100",
            category = "TRADING",
            priority = 8,
            confidence = 0.90f,
            occurredAt = 1_757_650_000_000L,
            idempotencyKey = "msg-3"
        )
        assertNull(MarksyTipPayloadBuilder.from(request))
    }

    @Test
    fun ordinaryAllCapsWordsAreNotAcceptedWithoutTradeContext() {
        val request = MarksyTradingEventRequest(
            eventId = 9L,
            source = "Upstox",
            sourcePackage = "com.upstox.pro",
            title = "UNUSUAL VOLUME ALERT",
            body = "DETECTED on the market",
            category = "TRADING",
            priority = 8,
            confidence = 0.90f,
            occurredAt = 1_757_650_000_000L,
            idempotencyKey = "msg-4"
        )
        assertNull(MarksyTipPayloadBuilder.from(request))
    }

    @Test
    fun tradeContextStillRejectsKnownNoiseWords() {
        val request = MarksyTradingEventRequest(
            eventId = 10L,
            source = "Upstox",
            sourcePackage = "com.upstox.pro",
            title = "BUY ORDER EXECUTED",
            body = "UNUSUAL VOLUME ALERT DETECTED",
            category = "TRADING",
            priority = 8,
            confidence = 0.90f,
            occurredAt = 1_757_650_000_000L,
            idempotencyKey = "msg-5"
        )
        assertNull(MarksyTipPayloadBuilder.from(request))
    }

    @Test
    fun labelledSymbolWinsEvenWhenItMatchesAnAllCapsNoisePattern() {
        val request = MarksyTradingEventRequest(
            eventId = 11L,
            source = "Upstox",
            sourcePackage = "com.upstox.pro",
            title = "BUY ORDER EXECUTED",
            body = "Symbol: HLEGLAS UNUSUAL VOLUME ALERT",
            category = "TRADING",
            priority = 8,
            confidence = 0.90f,
            occurredAt = 1_757_650_000_000L,
            idempotencyKey = "msg-6"
        )
        assertEquals("HLEGLAS", MarksyTipPayloadBuilder.from(request)!!.symbol)
    }

    // Regression: the SMS sender name ("KISHAN") was sent as the symbol instead of the called stock.
    @Test
    fun smsCallSendsTheCalledInstrumentNotTheSender() {
        val request = MarksyTradingEventRequest(
            eventId = 976L,
            source = "Messages",
            sourcePackage = "com.google.android.apps.messaging",
            title = "KISHAN ENTERPRISE",
            body = "KISHAN ENTERPRISE: Dear Client \nBUY | CROPSTER AGRO | \nEntry ₹2.82 | Target ₹10 | SL ₹2 | \nTime: 1-2 Months",
            category = "TRADING",
            priority = 100,
            confidence = 0.85f,
            occurredAt = 1_757_650_000_000L,
            idempotencyKey = "msg-7"
        )
        val payload = MarksyTipPayloadBuilder.from(request)!!
        assertEquals("CROPSTER AGRO", payload.symbol)
        assertEquals("BUY", payload.direction)
    }
}
