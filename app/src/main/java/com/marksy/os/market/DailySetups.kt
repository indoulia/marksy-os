package com.marksy.os.market

import org.json.JSONObject

/** Reads the `marksy-tips/v1` DAILY_SETUPS block a scheduled report carries (same contract as marksy-api's external_tip_ingest). */
object DailySetups {
    data class Setup(
        val id: String?,
        val symbol: String,
        val long: Boolean,
        val entryLow: Double?,
        val entryHigh: Double?,
        val stopLoss: Double?,
        val targets: List<Double>,
        val horizonDays: Int?,
        val confidencePct: Int?,
        val rationale: String?
    )

    data class Report(val source: String, val reportDate: String?, val setups: List<Setup>)

    private const val SCHEMA_PREFIX = "marksy-tips/"
    private const val DAILY_SETUPS = "DAILY_SETUPS"
    private val FENCE = Regex("```[a-zA-Z]*\\s*(\\{.*?\\})\\s*```", RegexOption.DOT_MATCHES_ALL)

    fun parse(text: String): Report? {
        if (!text.contains(SCHEMA_PREFIX)) return null
        // A fenced block first, as the reports are written; else the text itself or its outermost braces.
        val candidates = FENCE.findAll(text).map { it.groupValues[1] } +
            sequenceOf(text.trim(), text.substring(text.indexOf('{').coerceAtLeast(0), text.lastIndexOf('}') + 1))
        val payload = candidates.mapNotNull { runCatching { JSONObject(it) }.getOrNull() }
            .firstOrNull { it.optString("schema").startsWith(SCHEMA_PREFIX) } ?: return null
        if (payload.optString("reportType") != DAILY_SETUPS) return null
        val setups = payload.optJSONArray("predictions").objects().mapNotNull(::setup)
        return Report(payload.textOrNull("source") ?: "Daily setups", payload.textOrNull("reportDate"), setups)
    }

    private fun setup(p: JSONObject): Setup? {
        val symbol = p.textOrNull("symbol")?.uppercase() ?: return null
        val entry = p.doubleOrNull("entryPrice")
        val confidence = p.doubleOrNull("confidence")?.let { if (it <= 1.0) it * 100 else it }?.toInt()
        return Setup(
            id = p.textOrNull("id"),
            symbol = symbol,
            long = p.textOrNull("direction")?.uppercase() !in setOf("SHORT", "SELL"),
            entryLow = p.doubleOrNull("entryLow") ?: entry,
            entryHigh = p.doubleOrNull("entryHigh") ?: entry,
            stopLoss = p.doubleOrNull("stopLoss"),
            targets = listOfNotNull(p.doubleOrNull("target1"), p.doubleOrNull("target2"), p.doubleOrNull("target3")),
            horizonDays = p.intOrNull("horizonDays"),
            confidencePct = confidence,
            rationale = p.textOrNull("rationale")
        )
    }
}
