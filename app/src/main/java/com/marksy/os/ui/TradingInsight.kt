package com.marksy.os.ui

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

fun NotificationEventEntity.toTradingInsight(): TradingInsight? {
    if (!isTrading) return null

    val headline = when {
        title.contains("rejected", ignoreCase = true) -> "Order rejected"
        title.contains("cancel", ignoreCase = true) -> "Order cancelled"
        title.contains("execut", ignoreCase = true) || title.contains("fill", ignoreCase = true) -> "Trade execution detected"
        title.contains("order", ignoreCase = true) -> "Trading order detected"
        else -> "Trading event detected"
    }

    return TradingInsight(
        eventId = id,
        headline = headline,
        source = sourceName,
        eventType = category,
        confidence = confidence,
        deliveryState = deliveryState,
        status = when (deliveryState) {
            "DELIVERED" -> "Marksy response received"
            "PENDING" -> "Waiting for Marksy"
            "IN_FLIGHT" -> "Sending to Marksy"
            "FAILED" -> "Delivery failed"
            else -> "Local only"
        },
        body = body,
        marksySummary = insightSummary,
        marksyAction = insightAction,
        marksyConfidence = insightConfidence
    )
}
