package com.marksy.os.gateway

/** Android-side transport contract for trading events. */
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

/**
 * Marksy's response is kept richer than the current UI needs so future screens
 * can expose the complete comparison/evaluation without another transport change.
 */
data class MarksyInsight(
    val eventId: Long,
    val summary: String,
    val action: String? = null,
    val confidence: Float? = null,
    val verdict: String? = null,
    val verdictReasons: List<String> = emptyList(),
    val recommendation: String? = null,
    val probability: Double? = null,
    val opportunityScore: Double? = null,
    val trustScore: Double? = null,
    val trustQuality: String? = null,
    val uncertaintyLevel: String? = null,
    val entryPrice: Double? = null,
    val targetPrice: Double? = null,
    val stopLoss: Double? = null,
    val upsidePct: Double? = null,
    val horizonDays: Int? = null,
    val levelState: String? = null,
    val modelVersion: String? = null,
    val asOf: String? = null,
    val failedCriteria: List<String> = emptyList(),
    val decisionOutcome: String? = null,
    val evidence: List<String> = emptyList(),
    val marksySource: String? = null,
    val marksyView: String? = null
)
