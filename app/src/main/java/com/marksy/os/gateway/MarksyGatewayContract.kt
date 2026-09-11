package com.marksy.os.gateway

/**
 * Android-side transport contract for trading events.
 *
 * This is intentionally backend-agnostic. The concrete wire format and endpoint
 * must be aligned with the existing Marksy Gateway before HTTP is enabled.
 */
data class MarksyTradingEventRequest(
    val contractVersion: Int = 1,
    val eventId: Long,
    val source: String,
    val sourcePackage: String,
    val title: String,
    val body: String,
    val category: String,
    val priority: Int,
    val confidence: Float,
    val occurredAt: Long,
    val idempotencyKey: String
)

data class MarksyInsight(
    val eventId: Long,
    val summary: String,
    val action: String? = null,
    val confidence: Float? = null
)
