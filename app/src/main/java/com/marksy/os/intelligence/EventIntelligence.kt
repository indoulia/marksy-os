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

    fun analyze(event: NotificationEventEntity, nowMillis: Long = System.currentTimeMillis()): Result =
        score(event, nowMillis)

    /**
     * Time-independent importance persisted by the EPIC-010 pipeline. Identical to [analyze]
     * minus the age-based adjustments, so a stored score never silently decays or inflates.
     */
    fun importance(event: NotificationEventEntity): Result = score(event, nowMillis = null)

    private fun score(event: NotificationEventEntity, nowMillis: Long?): Result {
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

        val age = nowMillis?.let { (it - event.postedAt).coerceAtLeast(0L) }
        if (age != null) when {
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

    /** Prefers the pipeline's persisted (possibly cross-source) key; falls back to the V2 derivation. */
    fun threadKey(event: NotificationEventEntity): String =
        event.threadKey?.takeIf { it.isNotBlank() } ?: legacyThreadKey(event)

    internal fun legacyThreadKey(event: NotificationEventEntity): String {
        val symbol = SYMBOL_PATTERN.findAll("${event.title} ${event.body}".uppercase(Locale.ROOT))
            .map { it.value }
            .firstOrNull { it !in NOISE_SYMBOLS }
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
        "OPENED", "CLOSED", "QTY", "PNL", "INR", "OTP",
        // Common non-ticker words that appear in trading notification copy.
        "UPDATE", "MOVED", "MOVE", "TO", "NEW", "CONFIRMATION", "REJECTED", "CANCELLED"
    )

    private const val FRESH_WINDOW_MS = 30 * 60 * 1000L
    private const val STALE_WINDOW_MS = 15 * 60 * 1000L
    private const val HIGH_CONFIDENCE = 0.90f
}
