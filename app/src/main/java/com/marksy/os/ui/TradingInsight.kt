package com.marksy.os.ui

import com.marksy.os.data.local.DeliveryState
import com.marksy.os.data.local.NotificationEventEntity
import org.json.JSONObject

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
    val marksyConfidence: Float? = null,
    val marksyTipId: String? = null,
    val marksyVerdict: String? = null,
    val marksyVerdictReasons: List<String> = emptyList(),
    val marksyRecommendation: String? = null,
    val marksyProbability: Double? = null,
    val marksyOpportunityScore: Double? = null,
    val marksyTrustScore: Double? = null,
    val marksyTrustQuality: String? = null,
    val marksyUncertaintyLevel: String? = null,
    val marksyEntryPrice: Double? = null,
    val marksyTargetPrice: Double? = null,
    val marksyStopLoss: Double? = null,
    val marksyUpsidePct: Double? = null,
    val marksyHorizonDays: Int? = null,
    val marksyLevelState: String? = null,
    val marksyModelVersion: String? = null,
    val marksyAsOf: String? = null,
    val marksyFailedCriteria: List<String> = emptyList(),
    val marksyDecisionOutcome: String? = null,
    val marksyEvidence: List<String> = emptyList(),
    val marksySource: String? = null,
    val marksyView: String? = null
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

    val response = marksyResponseJson?.let(::parseMarksyResponse)
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
        marksyConfidence = insightConfidence?.coerceIn(0f, 1f),
        marksyTipId = marksyTipId,
        marksyVerdict = response?.verdict,
        marksyVerdictReasons = response?.verdictReasons.orEmpty(),
        marksyRecommendation = response?.recommendation,
        marksyProbability = response?.probability,
        marksyOpportunityScore = response?.opportunityScore,
        marksyTrustScore = response?.trustScore,
        marksyTrustQuality = response?.trustQuality,
        marksyUncertaintyLevel = response?.uncertaintyLevel,
        marksyEntryPrice = response?.entryPrice,
        marksyTargetPrice = response?.targetPrice,
        marksyStopLoss = response?.stopLoss,
        marksyUpsidePct = response?.upsidePct,
        marksyHorizonDays = response?.horizonDays,
        marksyLevelState = response?.levelState,
        marksyModelVersion = response?.modelVersion,
        marksyAsOf = response?.asOf,
        marksyFailedCriteria = response?.failedCriteria.orEmpty(),
        marksyDecisionOutcome = response?.decisionOutcome,
        marksyEvidence = response?.evidence.orEmpty(),
        marksySource = response?.marksySource,
        marksyView = response?.marksyView
    )
}

private data class MarksyResponseSnapshot(
    val verdict: String?,
    val verdictReasons: List<String>,
    val recommendation: String?,
    val probability: Double?,
    val opportunityScore: Double?,
    val trustScore: Double?,
    val trustQuality: String?,
    val uncertaintyLevel: String?,
    val entryPrice: Double?,
    val targetPrice: Double?,
    val stopLoss: Double?,
    val upsidePct: Double?,
    val horizonDays: Int?,
    val levelState: String?,
    val modelVersion: String?,
    val asOf: String?,
    val failedCriteria: List<String>,
    val decisionOutcome: String?,
    val evidence: List<String>,
    val marksySource: String?,
    val marksyView: String?
)

private fun parseMarksyResponse(raw: String): MarksyResponseSnapshot? = runCatching {
    val data = JSONObject(raw)
    val comparison = data.optJSONObject("comparison")
    val view = data.optJSONObject("marksyView")
    MarksyResponseSnapshot(
        verdict = comparison?.stringOrNull("verdict"),
        verdictReasons = comparison?.stringList("verdictReasons").orEmpty(),
        recommendation = view?.stringOrNull("recommendation"),
        probability = view?.finiteDouble("probability"),
        opportunityScore = view?.finiteDouble("opportunityScore"),
        trustScore = view?.finiteDouble("trustScore"),
        trustQuality = view?.stringOrNull("trustQuality"),
        uncertaintyLevel = view?.stringOrNull("uncertaintyLevel"),
        entryPrice = view?.finiteDouble("entryPrice"),
        targetPrice = view?.finiteDouble("targetPrice"),
        stopLoss = view?.finiteDouble("stopLoss"),
        upsidePct = view?.finiteDouble("upsidePct"),
        horizonDays = view?.optInt("horizonDays")?.takeIf { it > 0 },
        levelState = view?.stringOrNull("levelState"),
        modelVersion = view?.stringOrNull("modelVersion"),
        asOf = view?.stringOrNull("asOf"),
        failedCriteria = view?.stringList("failedCriteria").orEmpty(),
        decisionOutcome = view?.stringOrNull("decisionOutcome"),
        evidence = view?.stringList("evidence").orEmpty(),
        marksySource = comparison?.stringOrNull("marksySource"),
        marksyView = comparison?.stringOrNull("marksyView")
    )
}.getOrNull()

private fun JSONObject.stringOrNull(name: String): String? =
    optString(name).takeIf { it.isNotBlank() }

private fun JSONObject.finiteDouble(name: String): Double? =
    optDouble(name).takeIf { it.isFinite() }

private fun JSONObject.stringList(name: String): List<String> =
    optJSONArray(name)?.let { array ->
        (0 until array.length()).mapNotNull { i ->
            array.optString(i).takeIf { it.isNotBlank() }
        }
    }.orEmpty()
