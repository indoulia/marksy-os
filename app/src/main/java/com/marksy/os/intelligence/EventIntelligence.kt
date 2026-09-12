package com.marksy.os.intelligence

import com.marksy.os.data.local.DeliveryState
import com.marksy.os.data.local.NotificationEventEntity
import java.util.Locale

/**
 * Deterministic V2 "Understand" layer. It turns a stored event into a small,
 * explainable intelligence envelope without calling an LLM or the network.
 */
object EventIntelligence {
    enum class AttentionLevel { CRITICAL, HIGH, NORMAL, LOW }

    data class Result(
        val eventId: Long,
        val attentionScore: Int,
        val attentionLevel: AttentionLevel,
        val threadKey: String,
        val reasons: List<String>
    )

    fun analyze(event: NotificationEventEntity, nowMillis: Long = System.currentTimeMillis()): Result {
        var score = event.priority.coerceIn(0, 100)
        val reasons = mutableListOf<String>()

        if (event.isTrading) {
            score += 10
            reasons += "Trading event"
        }

        if (event.deliveryState == DeliveryState.FAILED.name) {
            score += 15
            reasons += "Marksy delivery needs attention"
        }

        val age = (nowMillis - event.postedAt).coerceAtLeast(0L)
        if (age <= FRESH_WINDOW_MS) {
            score += 5
            reasons += "Recent"
        }

        if (event.confidence >= HIGH_CONFIDENCE) {
            reasons += "High classification confidence"
        }

        val bounded = score.coerceIn(0, 100)
        return Result(
            eventId = event.id,
            attentionScore = bounded,
            attentionLevel = levelFor(bounded),
            threadKey = threadKey(event),
            reasons = reasons.ifEmpty { listOf("Standard notification") }
        )
    }

    fun groupByThread(
        events: List<NotificationEventEntity>,
        nowMillis: Long = System.currentTimeMillis()
    ): Map<String, List<Result>> = events
        .map { analyze(it, nowMillis) }
        .groupBy { it.threadKey }

    private fun levelFor(score: Int): AttentionLevel = when {
        score >= 90 -> AttentionLevel.CRITICAL
        score >= 70 -> AttentionLevel.HIGH
        score >= 40 -> AttentionLevel.NORMAL
        else -> AttentionLevel.LOW
    }

    /**
     * Prefer a recognizable uppercase market symbol for trading threads.
     * Otherwise use a normalized source/category/title key. This is deliberately
     * conservative: it groups obvious continuations but never claims semantic
     * equivalence across unrelated sources.
     */
    private fun threadKey(event: NotificationEventEntity): String {
        val symbol = SYMBOL_PATTERN.find("${event.title} ${event.body}".uppercase(Locale.ROOT))
            ?.value
            ?.takeUnless { it in NOISE_SYMBOLS }
        return if (symbol != null && event.isTrading) {
            "${event.sourcePackage.trim().lowercase(Locale.ROOT)}|trading|$symbol"
        } else {
            val title = event.title.trim().lowercase(Locale.ROOT)
                .replace(Regex("\\s+"), " ")
                .take(80)
            "${event.sourcePackage.trim().lowercase(Locale.ROOT)}|${event.category.lowercase(Locale.ROOT)}|$title"
        }
    }

    private val SYMBOL_PATTERN = Regex("\\b[A-Z][A-Z0-9.-]{1,14}\\b")
    private val NOISE_SYMBOLS = setOf(
        "BUY", "SELL", "ORDER", "TRADE", "EXECUTED", "FILLED", "AT", "AVG", "PRICE",
        "TARGET", "STOP", "LOSS", "PROFIT", "P&L", "MARKET", "ALERT", "POSITION",
        "OPENED", "CLOSED", "QTY", "PNL", "INR", "OTP"
    )

    private const val FRESH_WINDOW_MS = 30 * 60 * 1000L
    private const val HIGH_CONFIDENCE = 0.90f
}
