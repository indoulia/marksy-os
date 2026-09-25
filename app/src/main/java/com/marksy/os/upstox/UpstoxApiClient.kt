package com.marksy.os.upstox

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class UpstoxAuthException(message: String) : IOException(message)

/** Read-only Upstox market-data client (quotes and candles). Never add order endpoints against this token. */
class UpstoxApiClient(private val token: () -> String?) {
    suspend fun ltp(instrumentKeys: List<String>): Map<String, UpstoxLtp> = withContext(Dispatchers.IO) {
        val query = URLEncoder.encode(instrumentKeys.joinToString(","), "UTF-8")
        UpstoxLtp.parseResponse(get("$BASE_URL/market-quote/ltp?instrument_key=$query")).also { quotes ->
            val missing = instrumentKeys.filterNot { it in quotes }
            if (missing.isNotEmpty()) com.marksy.os.ai.DiagLog.i("MarksyUpstox", "ltp ok ${quotes.size}/${instrumentKeys.size}; missing=$missing")
        }
    }

    /** Full quote with OHLC, circuits and five-level depth (v2; v3 has no full quote). */
    suspend fun quote(instrumentKey: String): UpstoxQuote = withContext(Dispatchers.IO) {
        UpstoxQuote.parse(get("$V2_URL/market-quote/quotes?instrument_key=${URLEncoder.encode(instrumentKey, "UTF-8")}"))
    }

    suspend fun candles(instrumentKey: String, range: ChartRange, today: java.time.LocalDate, zone: java.time.ZoneId): List<Candle> = withContext(Dispatchers.IO) {
        val candles = UpstoxCandles.parse(get("$BASE_URL/${range.path(instrumentKey, today)}"))
        if (range != ChartRange.D1 || candles.isNotEmpty()) candles
        else UpstoxCandles.lastSession(UpstoxCandles.parse(get("$BASE_URL/${range.fallbackPath(instrumentKey, today)}")), zone)
    }

    private fun get(url: String): String {
        val bearer = token() ?: throw UpstoxAuthException("No Upstox token saved")
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
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
            return body
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val BASE_URL = "https://api.upstox.com/v3"
        const val V2_URL = "https://api.upstox.com/v2"
        // Five years of weekly candles is the largest response (~260 rows); 1W of 30-minute bars is similar.
        const val MAX_RESPONSE_CHARS = 400_000
    }
}
