package com.marksy.os.ui

import com.marksy.os.data.local.DeliveryState
import com.marksy.os.data.local.NotificationEventEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class TradingInsightTest {
    private fun event(
        title: String,
        state: String = DeliveryState.PENDING.name,
        trading: Boolean = true,
        insightSummary: String? = null,
        insightAction: String? = null,
        insightConfidence: Float? = null
    ) = NotificationEventEntity(
        id = 42L,
        sourcePackage = "com.upstox.pro",
        sourceName = "Upstox",
        sourceKey = "key-42",
        title = title,
        body = "BUY 10 RELIANCE",
        postedAt = 1_000L,
        category = if (trading) "TRADING" else "MESSAGES",
        priority = 100,
        confidence = .96f,
        isTrading = trading,
        deliveryState = state,
        insightSummary = insightSummary,
        insightAction = insightAction,
        insightConfidence = insightConfidence
    )

    @Test fun executedOrderBecomesExecutionInsight() {
        val insight = event("Order Executed").toTradingInsight()
        assertNotNull(insight)
        assertEquals("Trade execution detected", insight?.headline)
        assertEquals("Waiting for Marksy", insight?.status)
        assertEquals(42L, insight?.eventId)
    }

    @Test fun rejectedOrderGetsRejectedHeadline() {
        val insight = event("Order Rejected").toTradingInsight()
        assertEquals("Order rejected", insight?.headline)
    }

    @Test fun cancelledOrderGetsCancelledHeadline() {
        val insight = event("Order Cancelled").toTradingInsight()
        assertEquals("Order cancelled", insight?.headline)
    }

    @Test fun deliveredEventShowsMarksyResponseState() {
        val insight = event("Trade confirmation", DeliveryState.DELIVERED.name).toTradingInsight()
        assertEquals("Marksy response received", insight?.status)
        assertEquals(DeliveryState.DELIVERED.name, insight?.deliveryState)
    }

    @Test fun persistedMarksyResponseIsExposedWithoutFabrication() {
        val insight = event(
            "Trade confirmation",
            DeliveryState.DELIVERED.name,
            insightSummary = "Momentum remains favorable.",
            insightAction = "WATCH",
            insightConfidence = .81f
        ).toTradingInsight()
        assertEquals("Momentum remains favorable.", insight?.marksySummary)
        assertEquals("WATCH", insight?.marksyAction)
        assertEquals(.81f, insight?.marksyConfidence)
    }

    @Test fun failedEventShowsFailureState() {
        val insight = event("Trade confirmation", DeliveryState.FAILED.name).toTradingInsight()
        assertEquals("Delivery failed", insight?.status)
    }

    @Test fun nonTradingEventCannotBecomeTradingInsight() {
        assertNull(event("New message", trading = false).toTradingInsight())
    }
}
