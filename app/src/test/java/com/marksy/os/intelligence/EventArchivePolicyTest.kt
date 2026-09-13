package com.marksy.os.intelligence

import com.marksy.os.data.local.DeliveryState
import org.junit.Assert.assertEquals
import org.junit.Test

class EventArchivePolicyTest {
    @Test
    fun archiveNonTradingEventKeepsDeliveryNotApplicable() {
        val decision = EventLifecycle.afterRuleEvaluation(
            isTrading = false,
            matchedArchiveRule = true
        )

        assertEquals(EventLifecycle.Outcome.ARCHIVE, decision.outcome)
        assertEquals(DeliveryState.NOT_APPLICABLE, decision.nextDeliveryState)
    }

    @Test
    fun archiveTradingEventDoesNotCancelMarksyDelivery() {
        val decision = EventLifecycle.afterRuleEvaluation(
            isTrading = true,
            matchedArchiveRule = true
        )

        assertEquals(EventLifecycle.Outcome.ARCHIVE, decision.outcome)
        assertEquals(DeliveryState.PENDING, decision.nextDeliveryState)
    }

    @Test
    fun activeTradingEventRemainsPending() {
        val decision = EventLifecycle.afterRuleEvaluation(
            isTrading = true,
            matchedArchiveRule = false
        )

        assertEquals(EventLifecycle.Outcome.KEEP_ACTIVE, decision.outcome)
        assertEquals(DeliveryState.PENDING, decision.nextDeliveryState)
    }

    @Test
    fun activeNonTradingEventNeedsNoDelivery() {
        val decision = EventLifecycle.afterRuleEvaluation(
            isTrading = false,
            matchedArchiveRule = false
        )

        assertEquals(EventLifecycle.Outcome.KEEP_ACTIVE, decision.outcome)
        assertEquals(DeliveryState.NOT_APPLICABLE, decision.nextDeliveryState)
    }
}
