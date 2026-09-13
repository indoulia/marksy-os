package com.marksy.os.intelligence

import com.marksy.os.data.local.DeliveryState
import org.junit.Assert.assertEquals
import org.junit.Test

class EventLifecycleTest {
    @Test
    fun archivedNonTradingEventHasNoDeliveryWork() {
        val decision = EventLifecycle.afterRuleEvaluation(isTrading = false, matchedArchiveRule = true)
        assertEquals(EventLifecycle.Outcome.ARCHIVE, decision.outcome)
        assertEquals(DeliveryState.NOT_APPLICABLE, decision.nextDeliveryState)
    }

    @Test
    fun archivedTradingEventRemainsDeliverable() {
        val decision = EventLifecycle.afterRuleEvaluation(isTrading = true, matchedArchiveRule = true)
        assertEquals(EventLifecycle.Outcome.ARCHIVE, decision.outcome)
        assertEquals(DeliveryState.PENDING, decision.nextDeliveryState)
    }

    @Test
    fun tradingEventStartsPending() {
        assertEquals(DeliveryState.PENDING, EventLifecycle.afterRuleEvaluation(true, false).nextDeliveryState)
    }

    @Test
    fun deliveryOutcomesAreDeterministic() {
        assertEquals(DeliveryState.DELIVERED, EventLifecycle.afterDeliverySuccess().nextDeliveryState)
        assertEquals(DeliveryState.PENDING, EventLifecycle.afterTransientDeliveryFailure().nextDeliveryState)
        assertEquals(DeliveryState.FAILED, EventLifecycle.afterTerminalDeliveryFailure().nextDeliveryState)
        assertEquals(DeliveryState.PENDING, EventLifecycle.afterCancellation().nextDeliveryState)
    }
}
