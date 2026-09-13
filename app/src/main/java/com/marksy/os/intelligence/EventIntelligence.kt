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

        when (event.deliveryState) {
            DeliveryState.FAILED.name -> {
                score += 15
                reasons += "Marksy delivery needs attention"
            }
            DeliveryState.IN_FLIGHT.name -> {
                score += 5
                reasons += "Marksy analysis in progress"
            }
            DeliveryState.PENDING.name -> {
                if (event.isTrading) reasons += "Waiting for Marksy analysis"
            }
            DeliveryState.DELIVERED.name -> {
                if (event.isTrading) reasons += "Marksy response received"
            }
        }

        val age = (nowMillis - event.postedAt).coerceAtLeast(0L)
        when {
            age <= FRESH_WINDOW_MS -> {
                score += 5
                reasons += "Recent"
            }
            age >= STALE_WINDOW_MS && event.isTrading && event.deliveryState == DeliveryState.PENDING.name -> {
                score += 10
                reasons += "Trading analysis is taking longer than expected"
            }
        }

        if (event.confidence >= HIGH_CONFIDENCE) reasons += "High classification confidence"
        if (event.priority >= 85) reasons += "High base priority"

        val bounded = score.coerceIn(0, 100)
        return Result(
            eventId = event.id,
            attentionScore = bounded,
            attentionLevel = levelFor(bounded),
            threadKey = threadKey(event),
            reasons = reasons.distinct().ifEmpty { listOf("Standard notification") }
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
    private const val STALE_WINDOW_MS = 15 * 60 * 1000L
    private const val HIGH_CONFIDENCE = 0.90f
}
