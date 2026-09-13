package com.marksy.os.intelligence

import com.marksy.os.data.local.NotificationEventEntity

/**
 * Deterministic local rule evaluation. Rules only describe safe app-local reactions;
 * they never place brokerage orders or send notification content to third parties.
 */
object RuleEngine {
    enum class Action { HIGHLIGHT, ARCHIVE, MARK_TRADING_PRIORITY }

    data class Rule(
        val id: String,
        val name: String,
        val enabled: Boolean = true,
        val sourcePackage: String? = null,
        val category: String? = null,
        val containsText: String? = null,
        val action: Action = Action.HIGHLIGHT
    )

    fun matches(rule: Rule, event: NotificationEventEntity): Boolean {
        if (!rule.enabled) return false
        if (rule.sourcePackage != null && rule.sourcePackage != event.sourcePackage) return false
        if (rule.category != null && rule.category != event.category) return false
        if (rule.containsText != null) {
            val needle = rule.containsText.trim()
            if (needle.isEmpty()) return false
            val haystack = "${event.title} ${event.body}".lowercase()
            if (!haystack.contains(needle.lowercase())) return false
        }
        return true
    }

    fun matchingRules(rules: List<Rule>, event: NotificationEventEntity): List<Rule> =
        rules.filter { matches(it, event) }
}
