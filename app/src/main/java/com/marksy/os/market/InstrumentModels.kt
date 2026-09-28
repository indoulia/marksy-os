package com.marksy.os.market

import org.json.JSONObject

data class InstrumentMarketDto(val lastClosePrice: Double?, val asOfSessionDate: String?, val freshnessState: String?) {
    companion object {
        fun parse(json: JSONObject) = InstrumentMarketDto(
            lastClosePrice = json.doubleOrNull("lastClosePrice"),
            asOfSessionDate = json.textOrNull("asOfSessionDate"),
            freshnessState = json.textOrNull("freshnessState")
        )
    }
}

data class InstrumentPredictionEntryDto(
    val predictionId: Int,
    val asOf: String,
    val horizonDays: Int,
    val entryPrice: Double,
    val targetPrice: Double?,
    val stopLoss: Double?,
    val probabilityAtPublication: Double,
    val confidenceAtPublication: Double,
    val lifecycleState: String,
    val lifecycleDetail: String,
    val isTerminal: Boolean,
    val currentPrice: Double?,
    val currentReturn: Double?,
    val targetProgress: Double?,
    val stopProgress: Double?,
    val outcomeStatus: String,
    val realizedReturnPct: Double?,
    val hasResolvedOutcome: Boolean,
    val evidenceItemCount: Int,
    val recommendationId: Int? = null,
    val observedDays: Int? = null,
    val isSupersededByRevision: Boolean = false
) {
    companion object {
        fun parse(json: JSONObject) = InstrumentPredictionEntryDto(
            predictionId = json.intOrNull("predictionId") ?: 0,
            asOf = json.textOrNull("asOf") ?: "",
            horizonDays = json.intOrNull("horizonDays") ?: 0,
            entryPrice = json.doubleOrNull("entryPrice") ?: 0.0,
            targetPrice = json.doubleOrNull("targetPrice"),
            stopLoss = json.doubleOrNull("stopLoss"),
            probabilityAtPublication = json.doubleOrNull("probabilityAtPublication") ?: 0.0,
            confidenceAtPublication = json.doubleOrNull("confidenceAtPublication") ?: 0.0,
            lifecycleState = json.textOrNull("lifecycleState") ?: "UNAVAILABLE",
            lifecycleDetail = json.textOrNull("lifecycleDetail") ?: "",
            isTerminal = json.boolOrFalse("isTerminal"),
            currentPrice = json.doubleOrNull("currentPrice"),
            currentReturn = json.doubleOrNull("currentReturn"),
            targetProgress = json.doubleOrNull("targetProgress"),
            stopProgress = json.doubleOrNull("stopProgress"),
            outcomeStatus = json.textOrNull("outcomeStatus") ?: "PENDING",
            realizedReturnPct = json.doubleOrNull("realizedReturnPct"),
            hasResolvedOutcome = json.boolOrFalse("hasResolvedOutcome"),
            evidenceItemCount = json.intOrNull("evidenceItemCount") ?: 0,
            recommendationId = json.intOrNull("recommendationId"),
            observedDays = json.intOrNull("observedDays"),
            isSupersededByRevision = json.boolOrFalse("isSupersededByRevision")
        )
    }
}

data class InstrumentLifecycleDto(
    val symbol: String,
    val companyName: String?,
    val exchange: String,
    val sector: String?,
    val isActive: Boolean,
    val market: InstrumentMarketDto,
    val predictionCount: Int,
    val openPredictionCount: Int,
    val predictions: List<InstrumentPredictionEntryDto>
) {
    companion object {
        fun parse(json: JSONObject) = InstrumentLifecycleDto(
            symbol = json.textOrNull("symbol") ?: "",
            companyName = json.textOrNull("companyName"),
            exchange = json.textOrNull("exchange") ?: "",
            sector = json.textOrNull("sector"),
            isActive = json.boolOrFalse("isActive"),
            market = InstrumentMarketDto.parse(json.optJSONObject("market") ?: JSONObject()),
            predictionCount = json.intOrNull("predictionCount") ?: 0,
            openPredictionCount = json.intOrNull("openPredictionCount") ?: 0,
            predictions = json.optJSONArray("predictions").objects().map(InstrumentPredictionEntryDto::parse)
        )
    }
}

data class ActivePredictionDto(
    val predictionId: Int,
    val symbol: String,
    val companyName: String?,
    val exchange: String,
    val price: Double?,
    val targetPrice: Double,
    val stopLoss: Double,
    val horizon: Int,
    val remainingTradingDays: Int?,
    val distanceToTargetPercent: Double?,
    val distanceToStopLossPercent: Double?,
    val confidence: Double,
    val trustScore: Double?,
    val trustQuality: String?,
    val status: String,
    val lifecycleState: String,
    val isActionableNow: Boolean,
    val lifecycleDetail: String?,
    val entryPrice: Double,
    val compositeOpportunityScore: Double?,
    val lastPriceAt: String? = null,
    val scanSessionDate: String? = null,
    val publishedAt: String? = null,
    val dataBasis: String? = null
) {
    companion object {
        fun parse(json: JSONObject) = ActivePredictionDto(
            predictionId = json.intOrNull("predictionId") ?: 0,
            symbol = json.textOrNull("symbol") ?: "",
            companyName = json.textOrNull("companyName"),
            exchange = json.textOrNull("exchange") ?: "",
            price = json.doubleOrNull("price"),
            targetPrice = json.doubleOrNull("targetPrice") ?: 0.0,
            stopLoss = json.doubleOrNull("stopLoss") ?: 0.0,
            horizon = json.intOrNull("horizon") ?: 0,
            remainingTradingDays = json.intOrNull("remainingTradingDays"),
            distanceToTargetPercent = json.doubleOrNull("distanceToTargetPercent"),
            distanceToStopLossPercent = json.doubleOrNull("distanceToStopLossPercent"),
            confidence = json.doubleOrNull("confidence") ?: 0.0,
            trustScore = json.doubleOrNull("trustScore"),
            trustQuality = json.textOrNull("trustQuality"),
            status = json.textOrNull("status") ?: "UNKNOWN",
            lifecycleState = json.textOrNull("lifecycleState") ?: "UNAVAILABLE",
            isActionableNow = json.boolOrFalse("isActionableNow"),
            lifecycleDetail = json.textOrNull("lifecycleDetail"),
            entryPrice = json.doubleOrNull("entryPrice") ?: 0.0,
            compositeOpportunityScore = json.doubleOrNull("compositeOpportunityScore"),
            lastPriceAt = json.textOrNull("lastPriceAt"),
            scanSessionDate = json.textOrNull("scanSessionDate"),
            publishedAt = json.textOrNull("publishedAt"),
            dataBasis = json.textOrNull("dataBasis")
        )
    }
}

/** The discovery scan behind Marksy's picks: which session's close, when published, PROVISIONAL or FINAL. */
data class LatestScanDto(val scanSessionDate: String?, val publishedAt: String?, val dataBasis: String?, val nextScanAt: String?) {
    companion object {
        fun parse(json: JSONObject) = LatestScanDto(
            json.textOrNull("scanSessionDate"), json.textOrNull("publishedAt"), json.textOrNull("dataBasis"), json.textOrNull("nextScanAt")
        )
    }
}

data class ActivePredictionPageDto(val items: List<ActivePredictionDto>, val nextCursor: String?, val latestScan: LatestScanDto? = null) {
    companion object {
        fun parse(envelope: JSONObject): ActivePredictionPageDto {
            val items = envelope.optJSONArray("data").objects().map(ActivePredictionDto::parse)
            val meta = envelope.optJSONObject("meta")
            // Older backends send no meta.latestScan; the newest call's own scan is the next best answer.
            val latestScan = meta?.optJSONObject("latestScan")?.let(LatestScanDto::parse)
                ?: items.filter { it.scanSessionDate != null }.maxWithOrNull(compareBy({ it.scanSessionDate }, { it.publishedAt }))
                    ?.let { LatestScanDto(it.scanSessionDate, it.publishedAt, it.dataBasis, null) }
            return ActivePredictionPageDto(items, meta?.textOrNull("nextCursor"), latestScan)
        }
    }
}
