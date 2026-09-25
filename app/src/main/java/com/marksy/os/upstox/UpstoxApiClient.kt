package com.marksy.os.upstox

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class UpstoxAuthException(message: String) : IOException(message)

/** Read-only Upstox market-data client (LTP only). Never add order endpoints against this token. */
class UpstoxApiClient(private val token: () -> String?) {
    suspend fun ltp(instrumentKeys: List<String>): Map<String, UpstoxLtp> = withContext(Dispatchers.IO) {
        val bearer = token() ?: throw UpstoxAuthException("No Upstox token saved")
        val query = URLEncoder.encode(instrumentKeys.joinToString(","), "UTF-8")
        val connection = (URL("$BASE_URL/market-quote/ltp?instrument_key=$query").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 8_000
            readTimeout = 8_000
            instanceFollowRedirects = false
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Authorization", "Bearer $bearer")
        }
        try {
            val code = connection.responseCode
            val body = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { reader ->
                    val text = reader.readText()
                    if (text.length > MAX_RESPONSE_CHARS) throw IOException("Upstox response exceeded the safety limit")
                    text
                }.orEmpty()
            if (code == 401) throw UpstoxAuthException("Upstox rejected the token (expired or revoked)")
            if (code !in 200..299) {
                val detail = runCatching { UpstoxLtp.parseResponse(body) }.exceptionOrNull()?.message ?: "Upstox returned HTTP $code"
                throw IOException(detail)
            }
            UpstoxLtp.parseResponse(body).also { quotes ->
                val missing = instrumentKeys.filterNot { it in quotes }
                if (missing.isNotEmpty()) com.marksy.os.ai.DiagLog.i("MarksyUpstox", "ltp ok ${quotes.size}/${instrumentKeys.size}; missing=$missing")
            }
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val BASE_URL = "https://api.upstox.com/v3"
        const val MAX_RESPONSE_CHARS = 200_000
    }
}
