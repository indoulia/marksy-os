package com.marksy.os.intelligence

import com.marksy.os.data.local.DeliveryState
import com.marksy.os.data.local.NotificationEventEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DashboardSnapshotTest {
    private fun event(
        id: Long,
        category: String = "MESSAGES",
        priority: Int = 40,
        trading: Boolean = false,
        state: String = DeliveryState.NOT_APPLICABLE.name,
        postedAt: Long = id * 1_000L,
        source: String = "WhatsApp"
    ) = NotificationEventEntity(
        id = id,
        sourcePackage = if (source == "Upstox") "com.upstox.pro" else "com.whatsapp",
        sourceName = source,
        sourceKey = "key-$id",
        eventFingerprint = "fp-$id",
        title = if (trading) "Order Executed" else "Message",
        body = if (trading) "BUY HLEGLAS at ₹367.95" else "Hello",
        postedAt = postedAt,
        category = category,
        priority = priority,
        confidence = .95f,
        isTrading = trading,
        deliveryState = state
    )

    @Test
    fun snapshotAggregatesCoreMetricsAndCategories() {
        val events = listOf(
            event(1, category = "MESSAGES", priority = 30),
            event(2, category = "BANKING", priority = 75),
            event(3, category = "TRADING", priority = 80, trading = true, state = DeliveryState.DELIVERED.name, source = "Upstox")
        )

        val snapshot = DashboardSnapshot.from(events, nowMillis = 10_000L)

        assertEquals(3, snapshot.totalEvents)
        assertEquals(1, snapshot.tradingEvents)
        assertEquals(2, snapshot.importantEvents)
        assertEquals(1, snapshot.criticalEvents)
        assertEquals(0, snapshot.pendingTrading)
        assertEquals(0, snapshot.failedTrading)
        assertEquals(1, snapshot.deliveredTrading)
        assertEquals(1, snapshot.categoryCounts["TRADING"])
        assertEquals(1, snapshot.categoryCounts["BANKING"])
        assertEquals(1, snapshot.sourceCounts["Upstox"])
        assertEquals(DashboardSnapshot.TradingHealth.CLEAR, snapshot.tradingHealth)
    }

    @Test
    fun failedTradingRequiresAction() {
        val snapshot = DashboardSnapshot.from(
            listOf(event(1, category = "TRADING", priority = 90, trading = true, state = DeliveryState.FAILED.name)),
            nowMillis = 2_000L
        )

        assertEquals(1, snapshot.failedTrading)
        assertEquals(DashboardSnapshot.TradingHealth.ACTION_REQUIRED, snapshot.tradingHealth)
    }

    @Test
    fun pendingTradingIsAnalyzing() {
        val snapshot = DashboardSnapshot.from(
            listOf(event(1, category = "TRADING", trading = true, state = DeliveryState.PENDING.name)),
            nowMillis = 2_000L
        )

        assertEquals(DashboardSnapshot.TradingHealth.ANALYZING, snapshot.tradingHealth)
    }

    @Test
    fun topAttentionIsBoundedAndSorted() {
        val events = (1L..8L).map { id ->
            event(id, priority = id.toInt() * 10, postedAt = id * 1_000L)
        }

        val snapshot = DashboardSnapshot.from(events, nowMillis = 20_000L)

        assertEquals(DashboardSnapshot.TOP_ATTENTION_LIMIT, snapshot.topAttention.size)
        assertTrue(snapshot.topAttention.zipWithNext().all { (a, b) -> a.attentionScore >= b.attentionScore })
        assertEquals(8L, snapshot.topAttention.first().eventId)
    }

    @Test
    fun dashboardAgeLabelUsesCompactRelativeTime() {
        val now = 24L * 60L * 60L * 1000L
        assertEquals("Just now", dashboardAgeLabel(now - 20_000L, now))
        assertEquals("5m ago", dashboardAgeLabel(now - 5 * 60_000L, now))
        assertEquals("2h ago", dashboardAgeLabel(now - 2 * 60 * 60_000L, now))
        assertEquals("2d ago", dashboardAgeLabel(now - 2 * 24 * 60 * 60_000L, now))
    }
}
