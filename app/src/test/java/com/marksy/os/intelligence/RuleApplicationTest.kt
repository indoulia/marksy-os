package com.marksy.os.intelligence

import com.marksy.os.data.local.DeliveryState
import com.marksy.os.data.local.NotificationEventEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuleApplicationTest {
    @Test
    fun archiveRuleMarksCapturedEventArchived() {
        val result = RuleApplication.apply(
            listOf(RuleEngine.Rule("archive", "Archive bills", category = "BILLS", action = RuleEngine.Action.ARCHIVE)),
            event(category = "BILLS", priority = 40)
        )

        assertTrue(result.archived)
        assertTrue(result.event.archived)
        assertEquals(40, result.event.priority)
        assertEquals(DeliveryState.NOT_APPLICABLE.name, result.event.deliveryState)
    }

    @Test
    fun priorityRulesIncreasePriorityAndClampAt100() {
        val result = RuleApplication.apply(
            listOf(
                RuleEngine.Rule("highlight", "Highlight", action = RuleEngine.Action.HIGHLIGHT),
                RuleEngine.Rule("trade", "Trading priority", action = RuleEngine.Action.MARK_TRADING_PRIORITY)
            ),
            event(category = "TRADING", priority = 80, isTrading = true, deliveryState = DeliveryState.PENDING.name)
        )

        assertEquals(100, result.event.priority)
        assertFalse(result.event.archived)
    }

    @Test
    fun archiveTradingEventRemainsPendingForMarksyDelivery() {
        val result = RuleApplication.apply(
            listOf(RuleEngine.Rule("archive", "Archive trading", action = RuleEngine.Action.ARCHIVE)),
            event(category = "TRADING", priority = 60, isTrading = true, deliveryState = DeliveryState.PENDING.name)
        )

        assertTrue(result.event.archived)
        assertEquals(DeliveryState.PENDING.name, result.event.deliveryState)
    }

    private fun event(
        category: String,
        priority: Int,
        isTrading: Boolean = false,
        deliveryState: String = DeliveryState.NOT_APPLICABLE.name
    ) = NotificationEventEntity(
        sourcePackage = "com.example.app",
        sourceName = "Example",
        sourceKey = "key-${System.nanoTime()}",
        eventFingerprint = "fingerprint-${System.nanoTime()}",
        title = "Example event",
        body = "Example body",
        postedAt = 1_000L,
        category = category,
        priority = priority,
        confidence = 0.9f,
        isTrading = isTrading,
        deliveryState = deliveryState
    )
}
