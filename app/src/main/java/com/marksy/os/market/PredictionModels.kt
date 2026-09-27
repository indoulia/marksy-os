package com.marksy.os.market

import org.json.JSONObject

/** One closed Marksy prediction from `GET /tracking/predictions?status=closed`. */
data class ClosedPredictionDto(
    val id: Int,
    val symbol: String,
    val asOf: String?,
    val horizonDays: Int,
    val predictedReturn: Double?,
    val realizedReturn: Double?,
    val outcome: String?,
    /** Set when the row is excluded from current evaluation (a known-contaminated cohort). */
    val excludedReason: String?
) {
    companion object {
        fun parse(json: JSONObject) = ClosedPredictionDto(
            id = json.intOrNull("id") ?: 0,
            symbol = json.textOrNull("symbol") ?: "",
            asOf = json.textOrNull("asOf"),
            horizonDays = json.intOrNull("horizonDays") ?: 0,
            predictedReturn = json.doubleOrNull("predictedReturn"),
            realizedReturn = json.doubleOrNull("realizedReturn"),
            outcome = json.textOrNull("outcome"),
            excludedReason = json.textOrNull("historicalExclusionReason") ?: json.textOrNull("historicalEvaluationStatus")
        )
    }
}

data class ClosedPredictionPageDto(val items: List<ClosedPredictionDto>, val nextCursor: String?) {
    companion object {
        fun parse(envelope: JSONObject) = ClosedPredictionPageDto(
            envelope.optJSONArray("data").objects().map(ClosedPredictionDto::parse).filter { it.symbol.isNotBlank() },
            envelope.optJSONObject("meta")?.textOrNull("nextCursor")
        )
    }
}

/** Marksy's track record over a range (`GET /performance/summary`); rates are fractions of closed calls. */
data class PerformanceSummaryDto(
    val range: String,
    val predictionCount: Int,
    val closedCount: Int,
    val targetHitRate: Double?,
    val stopLossRate: Double?,
    val horizonExpiryRate: Double?,
    val avgRealizedReturn: Double?,
    val smallSample: Boolean
) {
    companion object {
        fun parse(json: JSONObject) = PerformanceSummaryDto(
            range = json.textOrNull("range") ?: "",
            predictionCount = json.intOrNull("predictionCount") ?: 0,
            closedCount = json.intOrNull("closedCount") ?: 0,
            targetHitRate = json.doubleOrNull("targetHitRate"),
            stopLossRate = json.doubleOrNull("stopLossRate"),
            horizonExpiryRate = json.doubleOrNull("horizonExpiryRate"),
            avgRealizedReturn = json.doubleOrNull("avgRealizedReturn"),
            smallSample = json.boolOrFalse("smallSample")
        )
    }
}
