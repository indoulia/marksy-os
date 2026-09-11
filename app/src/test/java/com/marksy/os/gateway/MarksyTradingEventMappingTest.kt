package com.marksy.os.gateway

import com.marksy.os.data.local.NotificationEventEntity
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

class MarksyTradingEventMappingTest {
    @Test
    fun inconsistentTradingFlagIsNotForwarded() {
        val event = event(category = "MESSAGES", isTrading = true)

        assertNull(event.toMarksyTradingEventRequest())
    }

    @Test
    fun blankSourceKeyIsNotForwarded() {
        val event = event(category = "TRADING", isTrading = true, sourceKey = "   ")

        assertNull(event.toMarksyTradingEventRequest())
    }

    @Test
    fun validTradingEventIsForwardable() {
        val event = event(category = "TRADING", isTrading = true, sourceKey = "upstox-123")

        assertNotNull(event.toMarksyTradingEventRequest())
    }

    private fun event(
        category: String,
        isTrading: Boolean,
        sourceKey: String = "key"
    ) = NotificationEventEntity(
        id = 7L,
        sourcePackage = "com.upstox.pro",
        sourceName = "Upstox",
        sourceKey = sourceKey,
        title = "Order executed",
        body = "BUY 10 RELIANCE",
        postedAt = 1_700_000_000_000L,
        category = category,
        priority = 3,
        confidence = 0.99f,
        isTrading = isTrading
    )
}
