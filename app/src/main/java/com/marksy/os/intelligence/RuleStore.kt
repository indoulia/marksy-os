package com.marksy.os.intelligence

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Persistent local rule configuration. Rule content never leaves the device. */
class RuleStore(context: Context) {
    private val preferences = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    fun load(): List<RuleEngine.Rule> {
        val raw = preferences.getString(KEY_RULES, null) ?: return defaultRules()
        return runCatching {
            val json = JSONArray(raw)
            buildList {
                for (index in 0 until minOf(json.length(), MAX_RULES)) {
                    val item = json.getJSONObject(index)
                    val name = item.optString("name").trim().take(MAX_NAME)
                    if (name.isBlank()) continue
                    val action = runCatching {
                        RuleEngine.Action.valueOf(item.optString("action", RuleEngine.Action.HIGHLIGHT.name))
                    }.getOrDefault(RuleEngine.Action.HIGHLIGHT)
                    val conditionJson = item.optJSONObject("condition")
                    val parsed = conditionJson?.let { RuleEngine.conditionFromJson(it) }
                    val corrupt = conditionJson != null && parsed == null
                    // A corrupt tree keeps the rule visible but disabled and never-matching (empty OR), instead of matching everything or vanishing.
                    val condition = if (corrupt) RuleEngine.Condition.AnyOf(emptyList()) else parsed
                    add(
                        RuleEngine.Rule(
                            id = item.optString("id").trim().take(MAX_ID).ifBlank { "rule-$index" },
                            name = name,
                            enabled = item.optBoolean("enabled", true) && !corrupt,
                            sourcePackage = item.optString("sourcePackage").trim().take(MAX_FILTER).ifBlank { null },
                            category = item.optString("category").trim().take(MAX_FILTER).uppercase().ifBlank { null },
                            containsText = item.optString("containsText").trim().take(MAX_FILTER).ifBlank { null },
                            action = action,
                            condition = condition,
                            priority = item.optInt("priority", 0).coerceIn(-100, 100),
                            version = item.optInt("version", 1).coerceAtLeast(1)
                        )
                    )
                }
            }.takeIf { it.isNotEmpty() } ?: defaultRules()
        }.getOrElse { defaultRules() }
    }

    fun save(rules: List<RuleEngine.Rule>) {
        val json = JSONArray()
        val previous = runCatching { load() }.getOrDefault(emptyList()).associateBy { it.id }
        rules.take(MAX_RULES).map { versioned(previous[it.id], it) }.forEach { rule ->
            json.put(JSONObject().apply {
                put("id", rule.id.take(MAX_ID))
                put("name", rule.name.trim().take(MAX_NAME))
                put("enabled", rule.enabled)
                put("sourcePackage", rule.sourcePackage?.trim()?.take(MAX_FILTER) ?: "")
                put("category", rule.category?.trim()?.take(MAX_FILTER)?.uppercase() ?: "")
                put("containsText", rule.containsText?.trim()?.take(MAX_FILTER) ?: "")
                put("action", rule.action.name)
                rule.condition?.let { put("condition", RuleEngine.conditionToJson(it)) }
                put("priority", rule.priority)
                put("version", rule.version)
            })
        }
        preferences.edit().putString(KEY_RULES, json.toString()).apply()
    }

    fun reset() = save(defaultRules())

    /** Any change to what a rule matches or does bumps its version; renaming or toggling does not. */
    private fun versioned(old: RuleEngine.Rule?, new: RuleEngine.Rule): RuleEngine.Rule {
        if (old == null) return new
        val base = maxOf(old.version, new.version)
        val changed = old.copy(enabled = new.enabled, name = new.name, version = new.version) != new
        return new.copy(version = if (changed) base + 1 else base)
    }

    companion object {
        private const val FILE_NAME = "marksy_rules"
        private const val KEY_RULES = "rules_v1"
        private const val MAX_RULES = 25
        private const val MAX_NAME = 60
        private const val MAX_FILTER = 120
        private const val MAX_ID = 80

        fun defaultRules(): List<RuleEngine.Rule> = listOf(
            RuleEngine.Rule("trading-priority", "Trading notifications", category = "TRADING", action = RuleEngine.Action.MARK_TRADING_PRIORITY),
            RuleEngine.Rule("failed-analysis", "Trading attention", category = "TRADING", action = RuleEngine.Action.HIGHLIGHT),
            RuleEngine.Rule("payments", "Payment notifications", category = "PAYMENTS", action = RuleEngine.Action.HIGHLIGHT)
        )
    }
}
