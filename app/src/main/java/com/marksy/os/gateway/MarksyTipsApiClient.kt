package com.marksy.os.gateway

import kotlinx.coroutines.CancellationException
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

/** Direct client for the confirmed Marksy Tips API. */
class MarksyTipsApiClient(
    private val integrationKey: String,
    baseUrl: String = DEFAULT_MARKSY_API_BASE_URL
) : MarksyGatewayClient {
    private val apiBaseUrl = normalizeBaseUrl(baseUrl)

    override suspend fun analyze(request: MarksyTradingEventRequest): Result<MarksyInsight> {
        if (integrationKey.isBlank()) return Result.failure(IllegalStateException("MARKSY_INTEGRATION_KEY is not configured"))
        return try {
            val payload = MarksyTipPayloadBuilder.from(request)
                ?: throw IllegalArgumentException("Trading notification does not contain a safe symbol candidate")
            val created = postTip(payload)
            if (created.status.equals("FAILED", ignoreCase = true)) {
                throw MarksyTerminalException("Marksy rejected the trading tip")
            }
            if (created.tipId.isBlank()) {
                Result.success(MarksyInsight(request.eventId, "Marksy tip status: ${created.status}", created.status))
            } else {
                Result.success(fetchTip(created.tipId, request.eventId, created.status))
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            Result.failure(error)
        }
    }

    private fun postTip(payload: MarksyTipPayload): CreatedTip {
        val data = execute("POST", "$apiBaseUrl/tips", payload.toJson()).getJSONObject("data")
        return CreatedTip(data.optString("tipId"), data.optString("status", "UNKNOWN").boundedText(MAX_STATUS_CHARS))
    }

    private fun fetchTip(tipId: String, eventId: Long, createdStatus: String): MarksyInsight {
        val data = execute("GET", "$apiBaseUrl/tips/$tipId").getJSONObject("data")
        val comparison = data.optJSONObject("comparison")
        val marksyView = data.optJSONObject("marksyView")
        val verdict = comparison?.optString("verdict")?.boundedText(MAX_SHORT_TEXT_CHARS)?.takeIf { it.isNotBlank() } ?: "NO_VIEW"
        val reasons = comparison?.stringList("verdictReasons").orEmpty()
        val recommendation = marksyView?.optString("recommendation")
            ?.boundedText(MAX_LONG_TEXT_CHARS)
            ?.takeIf { it.isNotBlank() }
        val probability = marksyView?.finiteDouble("probability")
        val summary = buildString {
            append(verdict)
            if (reasons.isNotEmpty()) append(" — ").append(reasons.joinToString("; "))
            if (recommendation != null) append(" | ").append(recommendation)
        }.boundedText(MAX_SUMMARY_CHARS).ifBlank { "Marksy comparison available." }

        return MarksyInsight(
            eventId = eventId,
            summary = summary,
            action = (recommendation ?: createdStatus).boundedText(MAX_LONG_TEXT_CHARS),
            confidence = marksyView?.finiteDouble("confidence")?.toFloat()?.coerceIn(0f, 1f)
                ?: probability?.toFloat()?.coerceIn(0f, 1f),
            verdict = verdict,
            verdictReasons = reasons,
            recommendation = recommendation,
            probability = probability,
            opportunityScore = marksyView?.finiteDouble("opportunityScore"),
            trustScore = marksyView?.finiteDouble("trustScore"),
            trustQuality = marksyView?.optString("trustQuality")?.boundedText(MAX_SHORT_TEXT_CHARS)?.takeIf { it.isNotBlank() },
            uncertaintyLevel = marksyView?.optString("uncertaintyLevel")?.boundedText(MAX_SHORT_TEXT_CHARS)?.takeIf { it.isNotBlank() },
            entryPrice = marksyView?.finiteDouble("entryPrice"),
            targetPrice = marksyView?.finiteDouble("targetPrice"),
            stopLoss = marksyView?.finiteDouble("stopLoss"),
            upsidePct = marksyView?.finiteDouble("upsidePct"),
            horizonDays = marksyView?.optInt("horizonDays")?.takeIf { it > 0 },
            levelState = marksyView?.optString("levelState")?.boundedText(MAX_SHORT_TEXT_CHARS)?.takeIf { it.isNotBlank() },
            modelVersion = marksyView?.optString("modelVersion")?.boundedText(MAX_SHORT_TEXT_CHARS)?.takeIf { it.isNotBlank() },
            asOf = marksyView?.optString("asOf")?.boundedText(MAX_SHORT_TEXT_CHARS)?.takeIf { it.isNotBlank() },
            failedCriteria = marksyView?.stringList("failedCriteria").orEmpty(),
            decisionOutcome = marksyView?.optString("decisionOutcome")?.boundedText(MAX_SHORT_TEXT_CHARS)?.takeIf { it.isNotBlank() },
            evidence = marksyView?.stringList("evidence").orEmpty(),
            marksySource = comparison?.optString("marksySource")?.boundedText(MAX_SHORT_TEXT_CHARS)?.takeIf { it.isNotBlank() },
            marksyView = comparison?.optString("marksyView")?.boundedText(MAX_LONG_TEXT_CHARS)?.takeIf { it.isNotBlank() },
            tipId = tipId.boundedText(MAX_SHORT_TEXT_CHARS),
            rawResponseJson = data.toString().take(MAX_RESPONSE_CHARS)
        )
    }

    private fun execute(method: String, url: String, payload: JSONObject? = null): JSONObject {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("X-Marksy-Integration-Key", integrationKey)
            doInput = true
            if (payload != null) doOutput = true
        }
        try {
            if (payload != null) {
                connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.bufferedReader()?.use { reader ->
                val buffer = CharArray(4096)
                val builder = StringBuilder()
                while (true) {
                    val read = reader.read(buffer)
                    if (read < 0) break
                    builder.append(buffer, 0, read)
                    if (builder.length > MAX_HTTP_RESPONSE_CHARS) {
                        throw IOException("Marksy Tips API response exceeded the safety limit")
                    }
                }
                builder.toString()
            }.orEmpty()
            if (code !in 200..299) {
                val detail = errorDetail(response)
                if (code in 400..499) throw MarksyTerminalException("Marksy Tips API returned HTTP $code$detail")
                throw IOException("Marksy Tips API returned HTTP $code$detail")
            }
            return JSONObject(response).also { envelope ->
                if (!envelope.has("data") || !envelope.has("meta")) {
                    throw IOException("Marksy Tips API returned an invalid response envelope")
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun errorDetail(response: String): String = try {
        val envelope = JSONObject(response)
        val error = envelope.optJSONObject("error")
        val message = error?.optString("message")?.takeIf { it.isNotBlank() }
            ?: envelope.optString("message").takeIf { it.isNotBlank() }
        message?.let { ": ${it.take(MAX_ERROR_DETAIL_CHARS)}" } ?: ""
    } catch (_: Exception) {
        ""
    }

    private data class CreatedTip(val tipId: String, val status: String)

    private companion object {
        const val CONNECT_TIMEOUT_MS = 10_000
        const val READ_TIMEOUT_MS = 20_000
        const val MAX_HTTP_RESPONSE_CHARS = 100_000
        const val MAX_RESPONSE_CHARS = 50_000
        const val MAX_ERROR_DETAIL_CHARS = 300
        const val MAX_LIST_ITEMS = 20
        const val MAX_STATUS_CHARS = 100
        const val MAX_SHORT_TEXT_CHARS = 200
        const val MAX_LONG_TEXT_CHARS = 1_000
        const val MAX_SUMMARY_CHARS = 2_000
    }
}

class MarksyTerminalException(message: String) : IOException(message)

private const val DEFAULT_MARKSY_API_BASE_URL = "https://marksy.indoulia.com/api/v1"

private fun normalizeBaseUrl(value: String): String {
    val trimmed = value.trim().trimEnd('/')
    if (trimmed.isBlank()) return DEFAULT_MARKSY_API_BASE_URL
    val uri = runCatching { URI(trimmed) }.getOrNull()
        ?: throw IllegalArgumentException("MARKSY_API_BASE_URL is not a valid URL")
    require(uri.scheme.equals("https", ignoreCase = true)) { "MARKSY_API_BASE_URL must use HTTPS" }
    require(uri.host?.isNotBlank() == true) { "MARKSY_API_BASE_URL must include a host" }
    return trimmed
}

private fun JSONObject.finiteDouble(name: String): Double? =
    optDouble(name).takeIf { it.isFinite() }

private fun JSONObject.stringList(name: String): List<String> =
    optJSONArray(name)?.let { array ->
        (0 until minOf(array.length(), MAX_LIST_ITEMS)).mapNotNull { i ->
            array.optString(i).boundedText(MAX_LONG_TEXT_CHARS).takeIf { it.isNotBlank() }
        }
    }.orEmpty()

private fun String.boundedText(maxChars: Int): String = take(maxChars)
