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
        if (integrationKey.isBlank()) return Result.failure(IllegalStateException("MARKSY_INTEGRATION_KEY is not configured"))
        return runCatching {
            val payload = MarksyTipPayloadBuilder.from(request)
                ?: throw IllegalArgumentException("Trading notification does not contain a safe symbol candidate")
            val created = postTip(payload)
            if (created.tipId.isBlank()) {
                MarksyInsight(request.eventId, "Marksy tip status: ${created.status}", created.status)
            } else {
                fetchTip(created.tipId, request.eventId, created.status)
            }
        }
    }

    private fun postTip(payload: MarksyTipPayload): CreatedTip {
        val data = execute("POST", "$baseUrl/tips", payload.toJson()).getJSONObject("data")
        return CreatedTip(data.optString("tipId"), data.optString("status", "UNKNOWN"))
    }

    private fun fetchTip(tipId: String, eventId: Long, createdStatus: String): MarksyInsight {
        val data = execute("GET", "$baseUrl/tips/$tipId").getJSONObject("data")
        val comparison = data.optJSONObject("comparison")
        val marksyView = data.optJSONObject("marksyView")
        val verdict = comparison?.optString("verdict")?.takeIf { it.isNotBlank() } ?: "NO_VIEW"
        val reasons = comparison?.stringList("verdictReasons").orEmpty()

        val recommendation = marksyView?.optString("recommendation")?.takeIf { it.isNotBlank() }
        val probability = marksyView?.finiteDouble("probability")
        val summary = buildString {
            append(verdict)
            if (reasons.isNotEmpty()) append(" — ").append(reasons.joinToString("; "))
            if (recommendation != null) append(" | ").append(recommendation)
        }.ifBlank { "Marksy comparison available." }

        return MarksyInsight(
            eventId = eventId,
            summary = summary,
            action = recommendation ?: createdStatus,
            confidence = marksyView?.finiteDouble("confidence")?.toFloat() ?: probability?.toFloat(),
            verdict = verdict,
            verdictReasons = reasons,
            recommendation = recommendation,
            probability = probability,
            opportunityScore = marksyView?.finiteDouble("opportunityScore"),
            trustScore = marksyView?.finiteDouble("trustScore"),
            trustQuality = marksyView?.optString("trustQuality")?.takeIf { it.isNotBlank() },
            uncertaintyLevel = marksyView?.optString("uncertaintyLevel")?.takeIf { it.isNotBlank() },
            entryPrice = marksyView?.finiteDouble("entryPrice"),
            targetPrice = marksyView?.finiteDouble("targetPrice"),
            stopLoss = marksyView?.finiteDouble("stopLoss"),
            upsidePct = marksyView?.finiteDouble("upsidePct"),
            horizonDays = marksyView?.optInt("horizonDays")?.takeIf { it > 0 },
            levelState = marksyView?.optString("levelState")?.takeIf { it.isNotBlank() },
            modelVersion = marksyView?.optString("modelVersion")?.takeIf { it.isNotBlank() },
            asOf = marksyView?.optString("asOf")?.takeIf { it.isNotBlank() },
            failedCriteria = marksyView?.stringList("failedCriteria").orEmpty(),
            decisionOutcome = marksyView?.optString("decisionOutcome")?.takeIf { it.isNotBlank() },
            evidence = marksyView?.stringList("evidence").orEmpty(),
            marksySource = comparison?.optString("marksySource")?.takeIf { it.isNotBlank() },
            marksyView = comparison?.optString("marksyView")?.takeIf { it.isNotBlank() },
            tipId = tipId,
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

    private data class CreatedTip(val tipId: String, val status: String)

    private companion object {
        const val CONNECT_TIMEOUT_MS = 10_000
        const val READ_TIMEOUT_MS = 20_000
        const val MAX_RESPONSE_CHARS = 50_000
    }
}

private fun JSONObject.finiteDouble(name: String): Double? =
    optDouble(name).takeIf { it.isFinite() }

private fun JSONObject.stringList(name: String): List<String> =
    optJSONArray(name)?.let { array ->
        (0 until array.length()).mapNotNull { i ->
            array.optString(i).takeIf { it.isNotBlank() }
        }
    }.orEmpty()
