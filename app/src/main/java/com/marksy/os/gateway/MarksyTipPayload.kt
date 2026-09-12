package com.marksy.os.gateway

import org.json.JSONObject

/** Conservative parser for the fields the Tips API can accept from a notification. */
data class MarksyTipPayload(
    val symbol: String,
    val source: String,
    val sourceReference: String,
    val direction: String? = null,
    val entryPrice: Double? = null,
    val targetPrice: Double? = null,
    val stopLoss: Double? = null,
    val horizonDays: Int? = null,
    val confidence: Double? = null,
    val rationale: String? = null,
    val tipAsOf: String
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("symbol", symbol)
        put("source", source)
        put("sourceReference", sourceReference)
        direction?.let { put("direction", it) }
        entryPrice?.let { put("entryPrice", it) }
        targetPrice?.let { put("targetPrice", it) }
        stopLoss?.let { put("stopLoss", it) }
        horizonDays?.let { put("horizonDays", it) }
        confidence?.let { put("confidence", it) }
        rationale?.let { put("rationale", it) }
        put("tipAsOf", tipAsOf)
    }
}

object MarksyTipPayloadBuilder {
    fun from(request: MarksyTradingEventRequest): MarksyTipPayload? {
        val text = "${request.title} ${request.body}"
        val symbol = extractSymbol(text) ?: return null
        val direction = when {
            Regex("\\bBUY\\b", RegexOption.IGNORE_CASE).containsMatchIn(text) -> "BUY"
            Regex("\\bSELL\\b", RegexOption.IGNORE_CASE).containsMatchIn(text) -> "SELL"
            else -> null
        }
        return MarksyTipPayload(
            symbol = symbol,
            source = request.source,
            sourceReference = request.idempotencyKey,
            direction = direction,
            entryPrice = extractNumber(text, "(?:entry|entry price|executed at|filled at|avg(?:erage)? price)"),
            targetPrice = extractNumber(text, "(?:target|target price)"),
            stopLoss = extractNumber(text, "(?:stop loss|stoploss|sl)"),
            horizonDays = extractDays(text),
            confidence = extractPercent(text)?.div(100.0),
            rationale = extractRationale(text),
            tipAsOf = java.time.Instant.ofEpochMilli(request.occurredAt).toString()
        )
    }

    private fun extractSymbol(text: String): String? {
        val labelled = Regex("(?i)(?:symbol|scrip|ticker|stock)\\s*[:=-]?\\s*([A-Z][A-Z0-9.-]{2,14})")
            .find(text)?.groupValues?.getOrNull(1)
        if (!labelled.isNullOrBlank()) return labelled.uppercase()

        val excluded = setOf("BUY", "SELL", "ORDER", "EXECUTED", "TRADE", "PRICE", "TARGET", "STOP", "LOSS", "MARKET", "LIMIT", "QTY", "QUANTITY", "PERCENT", "NSE", "BSE", "INR", "UPI", "P&L")
        return Regex("\\b[A-Z][A-Z0-9.-]{2,14}\\b").findAll(text)
            .map { it.value.uppercase() }
            .firstOrNull { it !in excluded }
    }

    private fun extractNumber(text: String, label: String): Double? =
        Regex("(?i)$label\\s*[:=-]?\\s*(?:₹|INR)?\\s*([0-9]+(?:\\.[0-9]+)?)")
            .find(text)?.groupValues?.getOrNull(1)?.toDoubleOrNull()

    private fun extractDays(text: String): Int? =
        Regex("(?i)\\b(?:horizon|holding|for)\\s*[:=-]?\\s*(\\d{1,3})\\s*(?:days?|d)\\b")
            .find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()?.takeIf { it in 1..365 }

    private fun extractPercent(text: String): Double? =
        Regex("(?i)\\b(?:confidence|probability)\\s*[:=-]?\\s*(\\d{1,3}(?:\\.\\d+)?)\\s*%")
            .find(text)?.groupValues?.getOrNull(1)?.toDoubleOrNull()?.takeIf { it in 0.0..100.0 }

    private fun extractRationale(text: String): String? =
        Regex("(?i)\\b(?:rationale|reason)\\s*[:=-]\\s*(.{1,200})")
            .find(text)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() }
}
