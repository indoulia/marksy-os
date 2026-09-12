package com.marksy.os.ui

import com.marksy.os.data.local.DeliveryState
import com.marksy.os.data.local.NotificationEventEntity

/**
 * Presentation-only trading insight model.
 * V1 never invents a recommendation when Marksy has not responded.
 */
data class TradingInsight(
    val eventId: Long,
    val headline: String,
    val source: String,
    val eventType: String,
    val confidence: Float,
    val deliveryState: String,
    val status: String,
    val body: String,
    val marksySummary: String? = null,
    val marksyAction: String? = null,
    val marksyConfidence: Float? = null
)

/** Keep confidence display bounded even if a future classifier/backend returns bad values. */
fun confidencePercent(confidence: Float): Int =
    (confidence.coerceIn(0f, 1f) * 100f).toInt()

private fun tradingHeadline(title: String): String = when {
    title.contains("rejected", ignoreCase = true) -> "Order rejected"
    title.contains("cancel", ignoreCase = true) -> "Order cancelled"
    title.contains("execut", ignoreCase = true) || title.contains("fill", ignoreCase = true) -> "Trade execution detected"
    title.contains("order", ignoreCase = true) -> "Trading order detected"
    else -> "Trading event detected"
}

private fun deliveryStatus(deliveryState: String): String = when (deliveryState) {
    DeliveryState.DELIVERED.name -> "Marksy response received"
    DeliveryState.PENDING.name -> "Waiting for Marksy"
    DeliveryState.IN_FLIGHT.name -> "Sending to Marksy"
    DeliveryState.FAILED.name -> "Delivery failed"
    else -> "Local only"
}

fun NotificationEventEntity.toTradingInsight(): TradingInsight? {
    if (!isTrading) return null

    return TradingInsight(
        eventId = id,
        headline = tradingHeadline(title),
        source = sourceName,
        eventType = category,
        confidence = confidence.coerceIn(0f, 1f),
        deliveryState = deliveryState,
        status = deliveryStatus(deliveryState),
        body = body,
        marksySummary = insightSummary?.takeIf { it.isNotBlank() },
        marksyAction = insightAction?.takeIf { it.isNotBlank() },
        marksyConfidence = insightConfidence?.coerceIn(0f, 1f)
    )
}
