package com.marksy.os.gateway

import org.json.JSONObject

/**
 * Rich wire payload for a Marksy trading tip.
 *
 * The canonical Tips API fields are kept at the top level. The additional
 * event fields preserve the complete notification context so the Marksy side
 * can start consuming more of the event without requiring an Android update.
 */
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
    val tipAsOf: String,
    val eventId: Long,
    val sourcePackage: String,
    val title: String,
    val body: String,
    val category: String,
    val priority: Int,
    val notificationConfidence: Double,
    val occurredAt: String,
    val contractVersion: Int
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
        put("eventId", eventId)
        put("sourcePackage", sourcePackage)
        put("title", title)
        put("body", body)
        put("category", category)
        put("priority", priority)
        put("notificationConfidence", notificationConfidence)
        put("occurredAt", occurredAt)
        put("contractVersion", contractVersion)
    }
}

object MarksyTipPayloadBuilder {
    fun from(request: MarksyTradingEventRequest): MarksyTipPayload? {
        val text = "${request.title} ${request.body}"
        val symbol = extractSymbol(request.title, request.body, text) ?: return null
        val direction = when {
            Regex("\\bBUY\\b", RegexOption.IGNORE_CASE).containsMatchIn(text) -> "BUY"
            Regex("\\bSELL\\b", RegexOption.IGNORE_CASE).containsMatchIn(text) -> "SELL"
            else -> null
        }
        val occurredAt = java.time.Instant.ofEpochMilli(request.occurredAt).toString()
        return MarksyTipPayload(
            symbol = symbol,
            source = request.source,
            sourceReference = request.idempotencyKey,
            direction = direction,
            entryPrice = extractNumber(text, "(?:entry|entry price|executed at|filled at|avg(?:erage) price)"),
            targetPrice = extractNumber(text, "(?:target|target price)"),
            stopLoss = extractNumber(text, "(?:stop loss|stoploss|sl)"),
            horizonDays = extractDays(text),
            confidence = extractPercent(text)?.div(100.0),
            rationale = extractRationale(text),
            tipAsOf = occurredAt,
            eventId = request.eventId,
            sourcePackage = request.sourcePackage,
            title = request.title,
            body = request.body,
            category = request.category,
            priority = request.priority,
            notificationConfidence = request.confidence.toDouble(),
            occurredAt = occurredAt,
            contractVersion = request.contractVersion
        )
    }

    private fun extractSymbol(title: String, body: String, text: String): String? {
        val labelled = Regex("(?i)(?:symbol|scrip|ticker|stock)\\s*[:=-]?\\s*([A-Z][A-Z0-9.-]{2,14})")
            .find(text)?.groupValues?.getOrNull(1)
        if (!labelled.isNullOrBlank()) return labelled.uppercase()
        // A parsed call names the instrument after the side; the first caps word is often the SMS sender.
        com.marksy.os.notification.TradeCallParser.parse(title, body)?.let { return it.symbol }

        // Do not guess a ticker from arbitrary ALL-CAPS notification prose.
        // An unlabelled symbol is accepted only when strong trade context exists.
        val hasTradeContext = Regex(
            "(?i)\\b(?:BUY|SELL|ORDER|EXECUTED|FILLED|TRADE|POSITION|QTY|QUANTITY|ENTRY|TARGET|STOP\\s*LOSS|AVG(?:ERAGE)?\\s*PRICE)\\b"
        ).containsMatchIn(text)
        if (!hasTradeContext) return null

        val excluded = setOf(
            "BUY", "SELL", "ORDER", "EXECUTED", "FILLED", "TRADE", "POSITION", "OPENED", "CLOSED",
            "PRICE", "ENTRY", "TARGET", "STOP", "LOSS", "MARKET", "LIMIT", "QTY", "QUANTITY",
            "PERCENT", "CONFIDENCE", "PROBABILITY", "UNUSUAL", "VOLUME", "ALERT", "DETECTED",
            "NSE", "BSE", "INR", "UPI", "P&L", "PNL"
        )
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
