package com.marksy.os.gateway

import com.marksy.os.data.local.NotificationEventEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MarksyGatewayClientTest {
    @Test
    fun nonTradingEventsAreNotMappedForGatewayDelivery() {
        val event = event(isTrading = false)

        assertNull(event.toMarksyTradingEventRequest())
    }

    @Test
    fun tradingEventsMapWithStableIdempotencyKey() {
        val event = event(isTrading = true, id = 42L, sourceKey = "notification-key")

        val request = event.toMarksyTradingEventRequest()

        requireNotNull(request)
        assertEquals(42L, request.eventId)
        assertEquals("notification-key", request.idempotencyKey)
        assertEquals(1, request.contractVersion)
    }

    @Test
    fun unconfiguredClientDoesNotPretendDeliverySucceeded() = runBlocking {
        val request = requireNotNull(event(isTrading = true).toMarksyTradingEventRequest())

        val result = UnconfiguredMarksyGatewayClient().analyze(request)

        assertEquals(false, result.isSuccess)
    }

    private fun event(
        isTrading: Boolean,
        id: Long = 1L,
        sourceKey: String = "key"
    ) = NotificationEventEntity(
        id = id,
        sourcePackage = "com.upstox.pro",
        sourceName = "Upstox",
        sourceKey = sourceKey,
        eventFingerprint = sourceKey,
        title = "Order update",
        body = "Order executed",
        postedAt = 1_700_000_000_000L,
        category = if (isTrading) "TRADING" else "MESSAGES",
        priority = 3,
        confidence = 0.99f,
        isTrading = isTrading
    )
}
