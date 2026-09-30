package com.marksy.os.gateway

import com.marksy.os.market.LedgerCalls
import com.marksy.os.market.LedgerTipDto
import kotlinx.coroutines.CancellationException
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

/** Direct client for the confirmed Marksy Tips API. */
open class MarksyTipsApiClient(
    private val authRepository: com.marksy.os.gateway.AuthRepository,
    baseUrl: String = DEFAULT_MARKSY_API_BASE_URL
) : MarksyGatewayClient {
    private val apiBaseUrl = normalizeBaseUrl(baseUrl)

    override suspend fun capture(eventId: Long, message: CapturedMessage): Result<MarksyInsight> = try {
        val data = execute("POST", "$apiBaseUrl/tips/ingest-text", message.toJson()).getJSONObject("data")
        val kind = data.str("kind").ifBlank { "RECORDED" }.boundedText(MAX_STATUS_CHARS)
        val tipId = data.str("tipId").trim()
        val insight = if (tipId.isBlank()) recordedOnly(eventId, kind) else try {
            fetchTip(tipId, eventId, kind)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (fetchError: Throwable) {
            // Finding M5: the POST already recorded the tip server-side; a failed comparison fetch
            // (a 4xx such as 404, or an IO error) must not fail the row -- it stays a recorded receipt.
            recordedOnly(eventId, kind)
        }
        Result.success(insight)
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Throwable) {
        Result.failure(error)
    }

    override suspend fun captureList(): Result<Set<String>> = try {
        Result.success(parseCaptureList(execute("GET", "$apiBaseUrl/channels/capture-list").getJSONObject("data")))
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Throwable) {
        Result.failure(error)
    }

    suspend fun marketSnapshot(): MarketSnapshot =
        MarketSnapshot.parse(execute("GET", "$apiBaseUrl/dashboard/snapshot?limit=10").getJSONObject("data"))

    // UNPARSED and orphan EXIT receipts have no tip, so there is no comparison to fetch.
    private fun recordedOnly(eventId: Long, kind: String) = MarksyInsight(
        eventId = eventId,
        summary = if (kind == "EXIT") "Exit recorded; no open call to close" else "Recorded by Marksy; not read as a call yet",
        action = kind
    )

    private suspend fun fetchTip(tipId: String, eventId: Long, kind: String): MarksyInsight =
        ledgerInsight(eventId, kind, tipId, execute("GET", "$apiBaseUrl/tips/$tipId").getJSONObject("data"))

    // internal open: a test seam so a fake HTTP layer can be substituted without a real (HTTPS-only) server.
    internal open suspend fun execute(method: String, url: String, payload: JSONObject? = null): JSONObject {
        // Not a MarksyTerminalException: a lapsed/never-established session is retryable --
        // the user may sign back in before the next delivery attempt. Treating it as terminal
        // would permanently FAIL every queued trading tip the moment a session expires.
        val token = authRepository.currentToken() ?: throw IOException("Not signed in to Marksy")
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = false
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer $token")
            doInput = true
            if (payload != null) doOutput = true
        }
        try {
            if (payload != null) {
                connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            }
            val code = connection.responseCode
            if (code in REDIRECT_CODES) {
                throw MarksyTerminalException("Marksy Tips API redirect refused")
            }
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.bufferedReader()?.use { reader ->
                val buffer = CharArray(4096)
                val builder = StringBuilder()
                while (true) {
                    val read = reader.read(buffer)
                    if (read < 0) break
                    builder.append(buffer, 0, read)
                    if (builder.length > MAX_HTTP_RESPONSE_CHARS) throw IOException("Marksy Tips API response exceeded the safety limit")
                }
                builder.toString()
            }.orEmpty()
            if (code !in 200..299) throw httpFailure(code, errorDetail(response))
            return JSONObject(response).also { envelope ->
                if (!envelope.has("data") || !envelope.has("meta")) throw IOException("Marksy Tips API returned an invalid response envelope")
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
    } catch (_: Exception) { "" }

}

/** The tip as the ledger tracks it (spec §9 `GET /tips/{id}`); Marksy's EPIC-803 comparison is no longer read. */
internal fun ledgerInsight(eventId: Long, kind: String, tipId: String, data: JSONObject): MarksyInsight {
    val ledger = data.optJSONObject("ledger")
    val summary = ledger?.let(LedgerTipDto::parse)?.let { tip ->
        listOfNotNull(LedgerCalls.state(tip), LedgerCalls.progressText(tip)).joinToString(" · ")
    } ?: "Recorded by Marksy; tracking starts with the next session"
    return MarksyInsight(
        eventId = eventId,
        summary = summary.boundedText(MAX_SUMMARY_CHARS),
        action = kind,
        tipId = tipId.boundedText(MAX_SHORT_TEXT_CHARS),
        rawResponseJson = ledger?.toString()?.take(MAX_RESPONSE_CHARS)
    )
}

private const val CONNECT_TIMEOUT_MS = 10_000
private const val READ_TIMEOUT_MS = 20_000
private const val MAX_HTTP_RESPONSE_CHARS = 100_000
private const val MAX_RESPONSE_CHARS = 50_000
private const val MAX_ERROR_DETAIL_CHARS = 300
private const val MAX_STATUS_CHARS = 100
private const val MAX_SHORT_TEXT_CHARS = 200
private const val MAX_SUMMARY_CHARS = 2_000
private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)

class MarksyTerminalException(message: String) : IOException(message)

internal fun httpFailure(code: Int, detail: String = ""): IOException {
    val message = "Marksy Tips API returned HTTP $code$detail"
    return if (code in 400..499 && code !in RETRYABLE_CLIENT_CODES) MarksyTerminalException(message) else IOException(message)
}

// Session, timeout and rate-limit failures are about the account, not the tip; retry once they clear.
private val RETRYABLE_CLIENT_CODES = setOf(401, 403, 408, 425, 429)

private const val DEFAULT_MARKSY_API_BASE_URL = "https://marksy.indoulia.com/api/v1"

private fun normalizeBaseUrl(value: String): String {
    val trimmed = value.trim().trimEnd('/')
    if (trimmed.isBlank()) return DEFAULT_MARKSY_API_BASE_URL
    val uri = runCatching { URI(trimmed) }.getOrNull() ?: throw IllegalArgumentException("MARKSY_API_BASE_URL is not a valid URL")
    require(uri.scheme.equals("https", ignoreCase = true)) { "MARKSY_API_BASE_URL must use HTTPS" }
    require(uri.host?.isNotBlank() == true) { "MARKSY_API_BASE_URL must include a host" }
    return trimmed
}

private fun String.boundedText(maxChars: Int): String = take(maxChars)

// Android's optString turns a JSON null into the text "null".
private fun JSONObject.str(name: String): String = if (isNull(name)) "" else optString(name)
