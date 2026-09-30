package com.marksy.os.market

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder

interface MarketApiClient {
    suspend fun marketSummary(): MarketSummaryDto
    suspend fun liveQuotes(symbols: List<String>? = null): LiveQuotesResponseDto
    suspend fun liveFeedHealth(): LiveFeedHealthDto
    suspend fun indexHistory(name: String, range: String): IndexHistoryDto
    suspend fun sectors(): List<SectorOptionDto>
    suspend fun instrument(symbol: String, includeCalls: Boolean = false): InstrumentLifecycleDto
    suspend fun activePredictions(cursor: String? = null): ActivePredictionPageDto
    suspend fun activePrediction(id: Int): ActivePredictionDto
    suspend fun ipos(stage: String? = null, query: String? = null): List<IpoListItemDto>
    suspend fun ipoAttention(limit: Int = 4): List<IpoAttentionItemDto>
    suspend fun ipoStageCounts(): IpoStageCountsDto
    suspend fun ipoDetail(id: String): IpoDetailDto
    suspend fun ipoHistory(id: String): List<IpoHistoryEntryDto>
    /** Marksy's full analysis behind one recommendation, kept raw so every section can be shown. */
    suspend fun recommendation(id: Int): JSONObject = JSONObject()
    suspend fun closedPredictions(cursor: String? = null): ClosedPredictionPageDto = ClosedPredictionPageDto(emptyList(), null)
    suspend fun performanceSummary(range: String = "30d"): PerformanceSummaryDto? = null
    suspend fun performanceBreakdown(dimension: String): PerformanceBreakdownDto? = null
    /** The signed-in reader's watched IPOs; watching is server-side so it follows the account. */
    suspend fun trackedIpos(): List<IpoTrackedItemDto> = emptyList()
    suspend fun setIpoTracking(id: String, tracking: Boolean): IpoTrackingStateDto = throw MarketApiException("IPO watching is not supported")
    /** Tip ledger (spec §9) under the bearer session; `filter` is a §8.3 query string from `ScorecardQuery.filterParams`. */
    suspend fun myTips(status: String?, cursor: String?): MyTipPageDto = MyTipPageDto(emptyList(), null)
    suspend fun myScorecard(filter: String): ScorecardDto = throw MarketApiException("Scorecards are not supported")
    suspend fun scorecards(entity: String, filter: String): EntityScorecardListDto = throw MarketApiException("Scorecards are not supported")
    suspend fun scorecardSummary(filter: String): ScorecardSummaryDto = throw MarketApiException("Scorecards are not supported")
    suspend fun scorecard(entity: String, id: Int, filter: String): ScorecardDto = throw MarketApiException("Scorecards are not supported")
    suspend fun tipDetail(tipId: String): TipDetailDto = throw MarketApiException("Tip detail is not supported")
}

/** [status] is the HTTP status, or 401 when no session exists, so a screen can word the failure instead of showing it. */
class MarketApiException(message: String, val status: Int? = null) : IOException(message)

/** HTTPS-enforced, `X-API-Key`-authenticated client for the `marksy-api` market-intelligence
 * surface, structurally mirroring `MarksyTipsApiClient` (raw `HttpURLConnection`, `{data, meta}`
 * envelope). Uses a separate scoped credential — never the EPIC-803 tips integration key. */
class RealMarketApiClient(private val authRepository: com.marksy.os.gateway.AuthRepository, baseUrl: String) : MarketApiClient {
    private val base: String = normalizeBaseUrl(baseUrl)

    override suspend fun marketSummary(): MarketSummaryDto =
        MarketSummaryDto.parse(getData("$base/market/summary"))

    override suspend fun liveQuotes(symbols: List<String>?): LiveQuotesResponseDto {
        val query = symbols?.takeIf { it.isNotEmpty() }?.joinToString(",")?.let { "?symbols=${encode(it)}" } ?: ""
        return LiveQuotesResponseDto.parse(getData("$base/market/live$query"))
    }

    override suspend fun liveFeedHealth(): LiveFeedHealthDto =
        LiveFeedHealthDto.parse(getData("$base/market/live/health"))

    override suspend fun indexHistory(name: String, range: String): IndexHistoryDto =
        IndexHistoryDto.parse(getData("$base/market/indices/${encode(name)}/history?range=${encode(range)}"))

    override suspend fun sectors(): List<SectorOptionDto> =
        SectorOptionDto.parseList(getDataArray("$base/market/sectors"))

    override suspend fun instrument(symbol: String, includeCalls: Boolean): InstrumentLifecycleDto =
        InstrumentLifecycleDto.parse(getData(instrumentUrl(symbol, includeCalls)))

    // Split out so the URL (incl. the include=calls flag) is checkable without a live server.
    internal fun instrumentUrl(symbol: String, includeCalls: Boolean): String =
        "$base/instruments/${encode(symbol)}" + if (includeCalls) "?include=calls" else ""

    override suspend fun activePredictions(cursor: String?): ActivePredictionPageDto {
        val query = cursor?.let { "?cursor=${encode(it)}" } ?: ""
        return ActivePredictionPageDto.parse(getEnvelope("$base/predictions/active$query"))
    }

    override suspend fun closedPredictions(cursor: String?): ClosedPredictionPageDto =
        ClosedPredictionPageDto.parse(getEnvelope("$base/tracking/predictions?status=closed&pageSize=50" + (cursor?.let { "&cursor=${encode(it)}" } ?: "")))

    override suspend fun performanceBreakdown(dimension: String): PerformanceBreakdownDto =
        PerformanceBreakdownDto.parse(getData("$base/performance/breakdown?dimension=${encode(dimension)}"))

    override suspend fun performanceSummary(range: String): PerformanceSummaryDto =
        PerformanceSummaryDto.parse(getData("$base/performance/summary?range=${encode(range)}"))

    override suspend fun activePrediction(id: Int): ActivePredictionDto =
        ActivePredictionDto.parse(getData("$base/predictions/active/$id"))

    override suspend fun ipos(stage: String?, query: String?): List<IpoListItemDto> {
        val params = listOfNotNull(stage?.let { "stage=${encode(it)}" }, query?.let { "q=${encode(it)}" })
        val suffix = if (params.isEmpty()) "" else "?" + params.joinToString("&")
        // The IPO list is far larger than other responses (hundreds of issues with details).
        val items = execute("$base/ipos$suffix", IPO_MAX_RESPONSE_CHARS).getJSONArray("data")
        com.marksy.os.ai.DiagLog.i("MarksyMarket", "ipos stage=$stage items=${items.length()}")
        return IpoListItemDto.parseList(items)
    }

    override suspend fun ipoAttention(limit: Int): List<IpoAttentionItemDto> =
        IpoAttentionItemDto.parseList(getDataArray("$base/ipos/attention?limit=$limit"))

    override suspend fun ipoStageCounts(): IpoStageCountsDto =
        IpoStageCountsDto.parse(getData("$base/ipos/counts"))

    override suspend fun ipoDetail(id: String): IpoDetailDto =
        IpoDetailDto.parse(getData("$base/ipos/${encode(id)}"))

    override suspend fun recommendation(id: Int): JSONObject = getData("$base/recommendations/$id")

    override suspend fun ipoHistory(id: String): List<IpoHistoryEntryDto> =
        IpoHistoryEntryDto.parseList(getDataArray("$base/ipos/${encode(id)}/history"))

    override suspend fun trackedIpos(): List<IpoTrackedItemDto> =
        IpoTrackedItemDto.parseList(getDataArray("$base/ipos/tracked"))

    override suspend fun setIpoTracking(id: String, tracking: Boolean): IpoTrackingStateDto =
        IpoTrackingStateDto.parse(execute("$base/ipos/${encode(id)}/tracking", method = if (tracking) "POST" else "DELETE").getJSONObject("data"))

    override suspend fun myTips(status: String?, cursor: String?): MyTipPageDto {
        val params = listOfNotNull("pageSize=$MY_TIPS_PAGE_SIZE", status?.let { "status=${encode(it)}" }, cursor?.let { "cursor=${encode(it)}" })
        return MyTipPageDto.parse(getEnvelope("$base/me/tips?" + params.joinToString("&")))
    }

    override suspend fun myScorecard(filter: String): ScorecardDto = ScorecardDto.parse(getData("$base/me/scorecard" + query(filter)))

    override suspend fun scorecards(entity: String, filter: String): EntityScorecardListDto =
        EntityScorecardListDto.parse(getData("$base/scorecards?entity=${encode(entity)}" + (if (filter.isEmpty()) "" else "&$filter")))

    override suspend fun scorecardSummary(filter: String): ScorecardSummaryDto =
        ScorecardSummaryDto.parse(getData("$base/scorecards/summary" + query(filter)))

    override suspend fun scorecard(entity: String, id: Int, filter: String): ScorecardDto =
        ScorecardDto.parse(getData("$base/scorecards/${encode(entity)}/$id" + query(filter)))

    override suspend fun tipDetail(tipId: String): TipDetailDto = TipDetailDto.parse(getData("$base/tips/${encode(tipId)}"))

    private fun query(filter: String) = if (filter.isEmpty()) "" else "?$filter"

    private suspend fun getEnvelope(url: String): JSONObject = execute(url)
    private suspend fun getData(url: String): JSONObject = execute(url).getJSONObject("data")
    private suspend fun getDataArray(url: String): org.json.JSONArray = execute(url).getJSONArray("data")

    private suspend fun execute(url: String, maxChars: Int = MAX_RESPONSE_CHARS, method: String = "GET"): JSONObject {
        val token = authRepository.currentToken() ?: throw MarketApiException("Not signed in to Marksy", 401)
        return withContext(Dispatchers.IO) {
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = false
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Authorization", "Bearer $token")
                doInput = true
                if (method == "POST") { doOutput = true; setFixedLengthStreamingMode(0) }
            }
            try {
                val code = connection.responseCode
                if (code in REDIRECT_CODES) throw MarketApiException("Marksy Market API redirect refused")
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                val response = stream?.bufferedReader()?.use { reader ->
                    val buffer = CharArray(4096)
                    val builder = StringBuilder()
                    while (true) {
                        val read = reader.read(buffer)
                        if (read < 0) break
                        builder.append(buffer, 0, read)
                        if (builder.length > maxChars) throw IOException("Marksy Market API response exceeded the safety limit (${maxChars / 1_000_000}M chars)")
                    }
                    builder.toString()
                }.orEmpty()
                if (code !in 200..299) {
                    val detail = errorDetail(response)
                    if (code in 400..499) throw MarketApiException("Marksy Market API returned HTTP $code$detail", code)
                    throw IOException("Marksy Market API returned HTTP $code$detail")
                }
                return@withContext JSONObject(response).also { envelope ->
                    if (!envelope.has("data") || !envelope.has("meta")) throw IOException("Marksy Market API returned an invalid response envelope")
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } finally {
                connection.disconnect()
            }
        }
    }

    private fun errorDetail(response: String): String = try {
        val envelope = JSONObject(response)
        val error = envelope.optJSONObject("error")
        val message = error?.optString("message")?.takeIf { it.isNotBlank() } ?: envelope.optString("message").takeIf { it.isNotBlank() }
        message?.let { ": ${it.take(300)}" } ?: ""
    } catch (_: Exception) { "" }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    private companion object {
        const val CONNECT_TIMEOUT_MS = 10_000
        const val READ_TIMEOUT_MS = 20_000
        const val MAX_RESPONSE_CHARS = 4_000_000
        const val IPO_MAX_RESPONSE_CHARS = 16_000_000
        const val MY_TIPS_PAGE_SIZE = 50
        val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)

        fun normalizeBaseUrl(value: String): String {
            val trimmed = value.trim().trimEnd('/')
            require(trimmed.isNotBlank()) { "MARKET_API_BASE_URL must not be blank" }
            val uri = runCatching { URI(trimmed) }.getOrNull() ?: throw IllegalArgumentException("MARKET_API_BASE_URL is not a valid URL")
            require(uri.scheme.equals("https", ignoreCase = true)) { "MARKET_API_BASE_URL must use HTTPS" }
            require(uri.host?.isNotBlank() == true) { "MARKET_API_BASE_URL must include a host" }
            return trimmed
        }
    }
}
