package com.marksy.os.intelligence

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Small persistent local rule store. Only rule configuration is persisted. */
class RuleStore(context: Context) {
    private val preferences = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    fun load(): List<RuleEngine.Rule> {
        val raw = preferences.getString(KEY_RULES, null) ?: return defaultRules()
        return runCatching {
            val json = JSONArray(raw)
            buildList {
                for (index in 0 until json.length()) {
                    val item = json.getJSONObject(index)
                    add(
                        RuleEngine.Rule(
                            id = item.getString("id"),
                            name = item.getString("name"),
                            enabled = item.optBoolean("enabled", true),
                            sourcePackage = item.optString("sourcePackage").takeIf { it.isNotBlank() },
                            category = item.optString("category").takeIf { it.isNotBlank() },
                            containsText = item.optString("containsText").takeIf { it.isNotBlank() },
                            action = RuleEngine.Action.valueOf(item.optString("action", RuleEngine.Action.HIGHLIGHT.name))
                        )
                    )
                }
            }
        }.getOrElse { defaultRules() }
    }

    fun save(rules: List<RuleEngine.Rule>) {
        val json = JSONArray()
        rules.forEach { rule ->
            json.put(JSONObject().apply {
                put("id", rule.id)
                put("name", rule.name)
                put("enabled", rule.enabled)
                put("sourcePackage", rule.sourcePackage ?: "")
                put("category", rule.category ?: "")
                put("containsText", rule.containsText ?: "")
                put("action", rule.action.name)
            })
        }
        preferences.edit().putString(KEY_RULES, json.toString()).apply()
    }

    fun reset() = save(defaultRules())

    companion object {
        private const val FILE_NAME = "marksy_rules"
        private const val KEY_RULES = "rules_v1"

        fun defaultRules(): List<RuleEngine.Rule> = listOf(
            RuleEngine.Rule("trading-priority", "Trading notifications", category = "TRADING", action = RuleEngine.Action.MARK_TRADING_PRIORITY),
            RuleEngine.Rule("failed-analysis", "Trading attention", category = "TRADING", action = RuleEngine.Action.HIGHLIGHT),
            RuleEngine.Rule("payments", "Payment notifications", category = "PAYMENTS", action = RuleEngine.Action.HIGHLIGHT)
        )
    }
}
