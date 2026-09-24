package com.marksy.os.market

import org.json.JSONArray
import org.json.JSONObject

private const val MAX_ITEMS = 50

internal fun JSONObject.textOrNull(name: String): String? =
    if (isNull(name)) null else optString(name).trim().take(500).ifBlank { null }

internal fun JSONObject.doubleOrNull(name: String): Double? =
    if (isNull(name)) null else optDouble(name).takeIf { it.isFinite() }

internal fun JSONObject.longOrNull(name: String): Long? =
    if (isNull(name)) null else optLong(name)

internal fun JSONObject.intOrNull(name: String): Int? =
    if (isNull(name)) null else optInt(name)

internal fun JSONObject.boolOrFalse(name: String): Boolean = optBoolean(name, false)

internal fun JSONArray?.objects(): List<JSONObject> =
    if (this == null) emptyList() else (0 until minOf(length(), MAX_ITEMS)).mapNotNull { optJSONObject(it) }

data class IndexQuoteDto(val name: String, val value: Double, val changePct: Double, val change: Double?) {
    companion object {
        fun parse(json: JSONObject) = IndexQuoteDto(
            name = json.textOrNull("name") ?: "",
            value = json.doubleOrNull("value") ?: 0.0,
            changePct = json.doubleOrNull("changePct") ?: 0.0,
            change = json.doubleOrNull("change")
        )
    }
}

data class MarketMoverDto(val symbol: String, val name: String, val price: Double?, val changePercent: Double, val change: Double?, val volume: Long?) {
    companion object {
        fun parse(json: JSONObject) = MarketMoverDto(
            symbol = json.textOrNull("symbol") ?: "",
            name = json.textOrNull("name") ?: "",
            price = json.doubleOrNull("price"),
            changePercent = json.doubleOrNull("changePercent") ?: 0.0,
            change = json.doubleOrNull("change"),
            volume = json.longOrNull("volume")
        )
    }
}

data class SectorMoveDto(val sector: String, val averageChangePct: Double) {
    companion object {
        fun parse(json: JSONObject) = SectorMoveDto(
            sector = json.textOrNull("sector") ?: "",
            averageChangePct = json.doubleOrNull("averageChangePct") ?: 0.0
        )
    }
}

data class MarketSummaryDto(
    val asOf: String,
    val marketStatus: String,
    val regime: String?,
    val advanceDecline: Double?,
    val volume: Long?,
    val volatility: Double?,
    val indexes: List<IndexQuoteDto>,
    val sectorLeaders: List<SectorMoveDto>,
    val sectorLaggards: List<SectorMoveDto>,
    val topGainers: List<MarketMoverDto>,
    val topLosers: List<MarketMoverDto>
) {
    companion object {
        fun parse(json: JSONObject) = MarketSummaryDto(
            asOf = json.textOrNull("asOf") ?: "",
            marketStatus = json.textOrNull("marketStatus") ?: "UNKNOWN",
            regime = json.textOrNull("regime"),
            advanceDecline = json.doubleOrNull("advanceDecline"),
            volume = json.longOrNull("volume"),
            volatility = json.doubleOrNull("volatility"),
            indexes = json.optJSONArray("indexes").objects().map(IndexQuoteDto::parse),
            sectorLeaders = json.optJSONArray("sectorLeaders").objects().map(SectorMoveDto::parse),
            sectorLaggards = json.optJSONArray("sectorLaggards").objects().map(SectorMoveDto::parse),
            topGainers = json.optJSONArray("topGainers").objects().map(MarketMoverDto::parse),
            topLosers = json.optJSONArray("topLosers").objects().map(MarketMoverDto::parse)
        )
    }
}

data class LiveQuoteDto(
    val symbol: String,
    val name: String?,
    val price: Double?,
    val prevClose: Double?,
    val changePercent: Double?,
    val state: String,
    val provider: String?,
    val receivedAt: String?,
    val ageSeconds: Int?
) {
    companion object {
        fun parse(json: JSONObject) = LiveQuoteDto(
            symbol = json.textOrNull("symbol") ?: "",
            name = json.textOrNull("name"),
            price = json.doubleOrNull("price"),
            prevClose = json.doubleOrNull("prevClose"),
            changePercent = json.doubleOrNull("changePercent"),
            state = json.textOrNull("state") ?: "UNAVAILABLE",
            provider = json.textOrNull("provider"),
            receivedAt = json.textOrNull("receivedAt"),
            ageSeconds = json.intOrNull("ageSeconds")
        )
    }
}

data class LiveQuotesResponseDto(val asOf: String, val marketSession: String, val quotes: List<LiveQuoteDto>) {
    companion object {
        fun parse(json: JSONObject) = LiveQuotesResponseDto(
            asOf = json.textOrNull("asOf") ?: "",
            marketSession = json.textOrNull("marketSession") ?: "UNKNOWN",
            quotes = json.optJSONArray("quotes").objects().map(LiveQuoteDto::parse)
        )
    }
}

data class LiveFeedHealthDto(
    val upstoxEnabled: Boolean,
    val liveFeedEnabled: Boolean,
    val feedState: String,
    val fallbackActive: Boolean,
    val cachedInstruments: Int
) {
    companion object {
        fun parse(json: JSONObject) = LiveFeedHealthDto(
            upstoxEnabled = json.boolOrFalse("upstoxEnabled"),
            liveFeedEnabled = json.boolOrFalse("liveFeedEnabled"),
            feedState = json.textOrNull("feedState") ?: "UNKNOWN",
            fallbackActive = json.boolOrFalse("fallbackActive"),
            cachedInstruments = json.intOrNull("cachedInstruments") ?: 0
        )
    }
}

data class IndexHistoryPointDto(val date: String, val close: Double, val high: Double, val low: Double, val volume: Long) {
    companion object {
        fun parse(json: JSONObject) = IndexHistoryPointDto(
            date = json.textOrNull("date") ?: "",
            close = json.doubleOrNull("close") ?: 0.0,
            high = json.doubleOrNull("high") ?: 0.0,
            low = json.doubleOrNull("low") ?: 0.0,
            volume = json.longOrNull("volume") ?: 0L
        )
    }
}

data class IndexHistoryDto(val name: String, val granularity: String, val points: List<IndexHistoryPointDto>) {
    companion object {
        fun parse(json: JSONObject) = IndexHistoryDto(
            name = json.textOrNull("name") ?: "",
            granularity = json.textOrNull("granularity") ?: "DAILY",
            points = json.optJSONArray("points").objects().map(IndexHistoryPointDto::parse)
        )
    }
}

data class SectorOptionDto(val name: String, val stockCount: Int) {
    companion object {
        fun parse(json: JSONObject) = SectorOptionDto(name = json.textOrNull("name") ?: "", stockCount = json.intOrNull("stockCount") ?: 0)
        fun parseList(array: JSONArray) = array.objects().map(::parse)
    }
}
