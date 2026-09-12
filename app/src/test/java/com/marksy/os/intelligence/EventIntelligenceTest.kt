package com.marksy.os.intelligence

import com.marksy.os.data.local.DeliveryState
import com.marksy.os.data.local.NotificationEventEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EventIntelligenceTest {
    private fun event(
        id: Long = 1,
        title: String = "Order Executed",
        body: String = "BUY HLEGLAS at ₹367.95",
        priority: Int = 100,
        trading: Boolean = true,
        deliveryState: String = DeliveryState.PENDING.name,
        postedAt: Long = 1_000_000L
    ) = NotificationEventEntity(
        id = id,
        sourcePackage = "com.upstox.pro",
        sourceName = "Upstox",
        sourceKey = "key-$id",
        eventFingerprint = "fp-$id",
        title = title,
        body = body,
        postedAt = postedAt,
        category = if (trading) "TRADING" else "MESSAGES",
        priority = priority,
        confidence = .96f,
        isTrading = trading,
        deliveryState = deliveryState
    )

    @Test
    fun tradingEventGetsHighAttention() {
        val result = EventIntelligence.analyze(event(), nowMillis = 1_000_000L)

        assertTrue(result.attentionScore >= 90)
        assertEquals(EventIntelligence.AttentionLevel.CRITICAL, result.attentionLevel)
        assertTrue(result.reasons.contains("Trading event"))
        assertTrue(result.reasons.contains("Recent"))
    }

    @Test
    fun failedDeliveryRaisesAttention() {
        val result = EventIntelligence.analyze(
            event(priority = 60, deliveryState = DeliveryState.FAILED.name),
            nowMillis = 1_000_000L
        )

        assertTrue(result.attentionScore >= 70)
        assertEquals(EventIntelligence.AttentionLevel.HIGH, result.attentionLevel)
        assertTrue(result.reasons.contains("Marksy delivery needs attention"))
    }

    @Test
    fun scoreIsBounded() {
        val result = EventIntelligence.analyze(event(priority = 100), nowMillis = 1_000_000L)
        assertEquals(100, result.attentionScore)
    }

    @Test
    fun sameTradingSymbolFromSameSourceSharesThread() {
        val first = event(id = 1, title = "Order Executed", body = "BUY HLEGLAS at ₹367.95")
        val second = event(id = 2, title = "Target update", body = "HLEGLAS target moved to ₹390")

        assertEquals(
            EventIntelligence.analyze(first).threadKey,
            EventIntelligence.analyze(second).threadKey
        )
    }

    @Test
    fun differentTradingSourcesDoNotShareThread() {
        val first = event(id = 1)
        val second = event(id = 2).copy(sourcePackage = "com.zerodha.kite3", sourceName = "Zerodha")

        assertTrue(EventIntelligence.analyze(first).threadKey != EventIntelligence.analyze(second).threadKey)
    }

    @Test
    fun groupingProducesThreadBuckets() {
        val events = listOf(
            event(id = 1, title = "Order Executed", body = "BUY HLEGLAS at ₹367.95"),
            event(id = 2, title = "Target update", body = "HLEGLAS target moved to ₹390"),
            event(id = 3, title = "Order Executed", body = "BUY RELIANCE at ₹1,200")
        )

        val groups = EventIntelligence.groupByThread(events)

        assertEquals(2, groups.size)
        assertTrue(groups.values.any { it.size == 2 })
    }
}
