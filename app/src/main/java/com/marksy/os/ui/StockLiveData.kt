package com.marksy.os.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.marksy.os.upstox.Candle
import com.marksy.os.upstox.ChartRange
import com.marksy.os.upstox.UpstoxApiClient
import com.marksy.os.upstox.UpstoxAuthException
import com.marksy.os.upstox.UpstoxCandles
import com.marksy.os.upstox.UpstoxFeed
import com.marksy.os.upstox.UpstoxInstruments
import com.marksy.os.upstox.UpstoxQuote
import com.marksy.os.upstox.UpstoxTokenStore
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.ZoneId

/** Everything Upstox knows about [symbol], fetched live and held only in memory while the page is open. */
@Composable
fun rememberStockLive(symbol: String, range: ChartRange, refreshKey: Any): StockLive {
    val context = LocalContext.current.applicationContext
    val store = remember { UpstoxTokenStore(context) }
    val client = remember { UpstoxApiClient { store.getToken() } }
    val hasToken = remember(refreshKey) { store.hasToken() }
    val zone = ZoneId.of("Asia/Kolkata")
    // null = still resolving; Result(null) = Upstox has no such instrument.
    val key by produceState<Result<String?>?>(null, symbol) {
        value = runCatching { UpstoxInstruments.load(context); UpstoxInstruments.keyFor(symbol) }
    }
    val instrument = key?.getOrNull()
    val quote by produceState<Result<UpstoxQuote>?>(null, instrument, hasToken, refreshKey) {
        val k = instrument?.takeIf { hasToken } ?: return@produceState
        while (true) {
            value = runCatching { client.quote(k) }
            if (!UpstoxFeed.isMarketOpen()) break
            delay(QUOTE_REFRESH_MS)
        }
    }
    val candles by produceState<List<Candle>?>(null, instrument, range, hasToken, refreshKey) {
        value = null
        val k = instrument?.takeIf { hasToken } ?: return@produceState
        value = runCatching { client.candles(k, range, LocalDate.now(zone), zone) }.getOrDefault(emptyList())
    }
    // A year of daily candles gives the 52-week range, period returns and average volume.
    val daily by produceState(emptyList<Candle>(), instrument, hasToken, refreshKey) {
        val k = instrument?.takeIf { hasToken } ?: return@produceState
        value = runCatching { client.candles(k, ChartRange.Y1, LocalDate.now(zone), zone) }.getOrDefault(emptyList())
    }
    val q = quote?.getOrNull()
    val error = quote?.exceptionOrNull()
    val note = when {
        !hasToken -> "Connect Upstox in More → Upstox market data for the live price, chart and market depth."
        key != null && instrument == null -> "Upstox doesn't list $symbol, so there's no live price or chart."
        error is UpstoxAuthException -> "Your Upstox token has expired. Paste a fresh one in More → Upstox market data."
        error != null -> "Live data unavailable right now: ${error.message}"
        else -> null
    }
    return StockLive(
        quote = q,
        candles = candles.takeIf { hasToken && instrument != null },
        yearRange = UpstoxCandles.range(daily),
        returns = q?.let { UpstoxCandles.returns(daily, it.lastPrice) }.orEmpty(),
        averageVolume = UpstoxCandles.averageVolume(daily, 20),
        isin = instrument?.substringAfter('|')?.takeIf { it.startsWith("IN") && it.length == 12 },
        note = note,
        streaming = q != null && UpstoxFeed.isMarketOpen()
    )
}

private const val QUOTE_REFRESH_MS = 5_000L
