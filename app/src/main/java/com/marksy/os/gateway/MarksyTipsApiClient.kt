package com.marksy.os.gateway

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Direct client for the confirmed Marksy Tips API. */
class MarksyTipsApiClient(
    private val integrationKey: String,
    private val baseUrl: String = "https://marksy.indoulia.com/api/v1"
) : MarksyGatewayClient {

    override suspend fun analyze(request: MarksyTradingEventRequest): Result<MarksyInsight> {
        if (integrationKey.isBlank()) {
            return Result.failure(IllegalStateException("MARKSY_INTEGRATION_KEY is not configured"))
        }

        return runCatching {
            val symbol = extractSymbol(request.title, request.body)
                ?: throw IllegalArgumentException("Trading notification does not contain a safe symbol candidate")

            val created = postTip(request, symbol)
            if (created.tipId.isBlank()) {
                MarksyInsight(
                    eventId = request.eventId,
                    summary = "Marksy tip status: ${created.status}",
                    action = created.status
                )
            } else {
                fetchTip(created.tipId, request.eventId, created.status)
            }
        }
    }

    private fun postTip(request: MarksyTradingEventRequest, symbol: String): CreatedTip {
        val payload = JSONObject().apply {
            put("symbol", symbol)
            put("source", request.source)
            put("sourceReference", request.idempotencyKey)
            put("tipAsOf", java.time.Instant.ofEpochMilli(request.occurredAt).toString())
        }
        val data = execute("POST", "$baseUrl/tips", payload).getJSONObject("data")
        return CreatedTip(data.optString("tipId"), data.optString("status", "UNKNOWN"))
    }

    private fun fetchTip(tipId: String, eventId: Long, createdStatus: String): MarksyInsight {
        val data = execute("GET", "$baseUrl/tips/$tipId").getJSONObject("data")
        val comparison = data.optJSONObject("comparison")
        val marksyView = data.optJSONObject("marksyView")
        val verdict = comparison?.optString("verdict")?.takeIf { it.isNotBlank() } ?: "NO_VIEW"
        val reasons = comparison?.optJSONArray("verdictReasons")?.let { array ->
            (0 until array.length()).mapNotNull { i ->
                array.optString(i).takeIf { it.isNotBlank() }
            }
        }.orEmpty()
        val summary = buildString {
            append(verdict)
            if (reasons.isNotEmpty()) append(" — ").append(reasons.joinToString("; "))
        }
        val recommendation = marksyView?.optString("recommendation")?.takeIf { it.isNotBlank() }
        val probability = marksyView?.optDouble("probability")?.takeIf { it.isFinite() }
        return MarksyInsight(
            eventId = eventId,
            summary = summary.ifBlank { "Marksy comparison available." },
            action = recommendation ?: createdStatus,
            confidence = probability?.toFloat()
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
                connection.outputStream.use {
                    it.write(payload.toString().toByteArray(Charsets.UTF_8))
                }
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw IOException("Marksy Tips API returned HTTP $code")
            return JSONObject(response).also { envelope ->
                if (!envelope.has("data") || !envelope.has("meta")) {
                    throw IOException("Marksy Tips API returned an invalid response envelope")
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    /** Conservative extraction; no symbol is invented when the notification is ambiguous. */
    private fun extractSymbol(title: String, body: String): String? {
        val text = "$title $body"
        val labelled = Regex("(?i)(?:symbol|scrip|stock|ticker)\\s*[:=-]?\\s*([A-Z][A-Z0-9.-]{2,14})")
            .find(text)?.groupValues?.getOrNull(1)
        if (!labelled.isNullOrBlank()) return labelled

        val candidates = Regex("\\b[A-Z][A-Z0-9.-]{2,14}\\b").findAll(text).map { it.value }.toList()
        val excluded = setOf("BUY", "SELL", "ORDER", "EXECUTED", "TRADE", "PRICE", "TARGET", "STOP", "LOSS", "MARKET", "LIMIT", "QTY", "PERCENT", "NSE", "BSE", "INR", "P&L")
        return candidates.firstOrNull { it !in excluded }
    }

    private data class CreatedTip(val tipId: String, val status: String)

    private companion object {
        const val CONNECT_TIMEOUT_MS = 10_000
        const val READ_TIMEOUT_MS = 20_000
    }
}
