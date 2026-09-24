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
    suspend fun instrument(symbol: String): InstrumentLifecycleDto
    suspend fun activePredictions(cursor: String? = null): ActivePredictionPageDto
    suspend fun activePrediction(id: Int): ActivePredictionDto
    suspend fun ipos(stage: String? = null, query: String? = null): List<IpoListItemDto>
    suspend fun ipoAttention(limit: Int = 4): List<IpoAttentionItemDto>
    suspend fun ipoStageCounts(): IpoStageCountsDto
    suspend fun ipoDetail(id: String): IpoDetailDto
    suspend fun ipoHistory(id: String): List<IpoHistoryEntryDto>
}

class MarketApiException(message: String) : IOException(message)

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

    override suspend fun instrument(symbol: String): InstrumentLifecycleDto =
        InstrumentLifecycleDto.parse(getData("$base/instruments/${encode(symbol)}"))

    override suspend fun activePredictions(cursor: String?): ActivePredictionPageDto {
        val query = cursor?.let { "?cursor=${encode(it)}" } ?: ""
        return ActivePredictionPageDto.parse(getEnvelope("$base/predictions/active$query"))
    }

    override suspend fun activePrediction(id: Int): ActivePredictionDto =
        ActivePredictionDto.parse(getData("$base/predictions/active/$id"))

    override suspend fun ipos(stage: String?, query: String?): List<IpoListItemDto> {
        val params = listOfNotNull(stage?.let { "stage=${encode(it)}" }, query?.let { "q=${encode(it)}" })
        val suffix = if (params.isEmpty()) "" else "?" + params.joinToString("&")
        return IpoListItemDto.parseList(getDataArray("$base/ipos$suffix"))
    }

    override suspend fun ipoAttention(limit: Int): List<IpoAttentionItemDto> =
        IpoAttentionItemDto.parseList(getDataArray("$base/ipos/attention?limit=$limit"))

    override suspend fun ipoStageCounts(): IpoStageCountsDto =
        IpoStageCountsDto.parse(getData("$base/ipos/counts"))

    override suspend fun ipoDetail(id: String): IpoDetailDto =
        IpoDetailDto.parse(getData("$base/ipos/${encode(id)}"))

    override suspend fun ipoHistory(id: String): List<IpoHistoryEntryDto> =
        IpoHistoryEntryDto.parseList(getDataArray("$base/ipos/${encode(id)}/history"))

    private suspend fun getEnvelope(url: String): JSONObject = execute(url)
    private suspend fun getData(url: String): JSONObject = execute(url).getJSONObject("data")
    private suspend fun getDataArray(url: String): org.json.JSONArray = execute(url).getJSONArray("data")

    private suspend fun execute(url: String): JSONObject {
        val token = authRepository.currentToken() ?: throw MarketApiException("Not signed in to Marksy")
        return withContext(Dispatchers.IO) {
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = false
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Authorization", "Bearer $token")
                doInput = true
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
                        if (builder.length > MAX_RESPONSE_CHARS) throw IOException("Marksy Market API response exceeded the safety limit")
                    }
                    builder.toString()
                }.orEmpty()
                if (code !in 200..299) {
                    val detail = errorDetail(response)
                    if (code in 400..499) throw MarketApiException("Marksy Market API returned HTTP $code$detail")
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
        const val MAX_RESPONSE_CHARS = 200_000
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
