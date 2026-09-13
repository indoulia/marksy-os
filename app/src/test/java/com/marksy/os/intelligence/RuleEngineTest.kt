package com.marksy.os.intelligence

import com.marksy.os.data.local.NotificationEventEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuleEngineTest {
    @Test
    fun disabledRuleNeverMatches() {
        val event = event(category = "TRADING", title = "BUY order")
        val rule = RuleEngine.Rule("r1", "Trading", enabled = false, category = "TRADING")
        assertFalse(RuleEngine.matches(rule, event))
    }

    @Test
    fun categoryAndTextConditionsMustBothMatch() {
        val event = event(category = "TRADING", title = "BUY HLEGLAS order")
        val rule = RuleEngine.Rule("r1", "Highlight", category = "TRADING", containsText = "hleglas")
        assertTrue(RuleEngine.matches(rule, event))
    }

    @Test
    fun sourceConditionSeparatesIdenticalEventsAcrossSources() {
        val event = event(sourcePackage = "com.zerodha", category = "TRADING")
        val rule = RuleEngine.Rule("r1", "Zerodha", sourcePackage = "com.upstox")
        assertFalse(RuleEngine.matches(rule, event))
    }

    @Test
    fun matchingRulesReturnsOnlyApplicableEnabledRules() {
        val event = event(category = "PAYMENTS", title = "Payment received")
        val rules = listOf(
            RuleEngine.Rule("a", "Payments", category = "PAYMENTS"),
            RuleEngine.Rule("b", "Trading", category = "TRADING"),
            RuleEngine.Rule("c", "Disabled", enabled = false, category = "PAYMENTS")
        )
        assertEquals(listOf("a"), RuleEngine.matchingRules(rules, event).map { it.id })
    }

    @Test
    fun highlightRuleRaisesPriorityWithoutExceedingMaximum() {
        val event = event(category = "PAYMENTS").copy(priority = 90)
        val rules = listOf(RuleEngine.Rule("a", "Highlight", category = "PAYMENTS", action = RuleEngine.Action.HIGHLIGHT))
        assertEquals(100, RuleEngine.evaluate(rules, event).priority)
    }

    @Test
    fun tradingPriorityRuleAddsItsOwnBoost() {
        val event = event(category = "TRADING").copy(priority = 50)
        val rules = listOf(RuleEngine.Rule("a", "Trading", category = "TRADING", action = RuleEngine.Action.MARK_TRADING_PRIORITY))
        assertEquals(70, RuleEngine.evaluate(rules, event).priority)
    }

    @Test
    fun multipleMatchingRulesAccumulateAndClampPriority() {
        val event = event(category = "TRADING").copy(priority = 80)
        val rules = listOf(
            RuleEngine.Rule("a", "Trading", category = "TRADING", action = RuleEngine.Action.MARK_TRADING_PRIORITY),
            RuleEngine.Rule("b", "Highlight", category = "TRADING", action = RuleEngine.Action.HIGHLIGHT)
        )
        val evaluation = RuleEngine.evaluate(rules, event)
        assertEquals(listOf("a", "b"), evaluation.matchedRules.map { it.id })
        assertEquals(100, evaluation.priority)
    }

    private fun event(
        sourcePackage: String = "com.example",
        category: String = "OTHER",
        title: String = "Notification"
    ) = NotificationEventEntity(
        id = 1L,
        sourcePackage = sourcePackage,
        sourceName = sourcePackage,
        sourceKey = "$sourcePackage-1",
        eventFingerprint = "fp-1",
        title = title,
        body = "",
        postedAt = System.currentTimeMillis(),
        category = category,
        priority = 50,
        confidence = 0.9f,
        isTrading = category == "TRADING"
    )
}
