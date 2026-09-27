package com.marksy.os.upstox

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** This session's Upstox REST calls by endpoint family, for Marksy Health (is the source up, and how slow?). */
object UpstoxRestStats {
    data class Stat(val calls: Int = 0, val failures: Int = 0, val totalMs: Long = 0, val lastError: String? = null, val lastSuccessAt: Long = 0) {
        val averageMs: Long get() = if (calls == 0) 0 else totalMs / calls
        fun plus(ok: Boolean, ms: Long, error: String?, at: Long) = copy(
            calls = calls + 1, failures = failures + if (ok) 0 else 1, totalMs = totalMs + ms,
            lastError = if (ok) lastError else error, lastSuccessAt = if (ok) at else lastSuccessAt
        )
    }

    private val _stats = MutableStateFlow<Map<String, Stat>>(emptyMap())
    val stats: StateFlow<Map<String, Stat>> = _stats.asStateFlow()

    fun family(url: String): String {
        val path = url.substringAfter("upstox.com/").substringBefore('?')
        return when {
            "market-quote/ltp" in path -> "ltp"
            "market-quote/quotes" in path -> "quotes"
            "historical-candle" in path -> "candles"
            path.contains("option/") -> "options"
            "fundamentals" in path -> "fundamentals"
            "news" in path -> "news"
            else -> "other"
        }
    }

    fun record(url: String, ok: Boolean, ms: Long, error: String? = null) {
        val f = family(url)
        _stats.update { it + (f to (it[f] ?: Stat()).plus(ok, ms, error, System.currentTimeMillis())) }
    }
}
