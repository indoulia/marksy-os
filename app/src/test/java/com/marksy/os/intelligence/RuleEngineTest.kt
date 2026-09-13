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
