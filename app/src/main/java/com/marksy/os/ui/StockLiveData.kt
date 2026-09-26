package com.marksy.os.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.marksy.os.upstox.BalancePeriod
import com.marksy.os.upstox.Candle
import com.marksy.os.upstox.CompanyProfile
import com.marksy.os.upstox.CorporateAction
import com.marksy.os.upstox.FinancialSeries
import com.marksy.os.upstox.KeyRatio
import com.marksy.os.upstox.NewsItem
import com.marksy.os.upstox.Peer
import com.marksy.os.upstox.UpstoxFundamentals
import com.marksy.os.upstox.ChartRange
import com.marksy.os.upstox.UpstoxApiClient
import com.marksy.os.upstox.UpstoxAuthException
import com.marksy.os.upstox.UpstoxCandles
import com.marksy.os.upstox.UpstoxFeed
import com.marksy.os.upstox.UpstoxInstruments
import com.marksy.os.upstox.UpstoxQuote
import com.marksy.os.upstox.UpstoxTokenStore
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
        streaming = q != null && UpstoxFeed.isMarketOpen(),
        daily = daily,
        key = instrument?.takeIf { hasToken }
    )
}

/** Upstox fundamentals and news for an equity [key] (`NSE_EQ|ISIN`); each part fills in on its own so one failure hides only its section. */
data class StockFundamentals(
    val profile: CompanyProfile? = null,
    val ratios: List<KeyRatio> = emptyList(),
    val quarterly: List<FinancialSeries> = emptyList(),
    val yearly: List<FinancialSeries> = emptyList(),
    val balance: List<BalancePeriod> = emptyList(),
    val cashFlow: List<FinancialSeries> = emptyList(),
    val shareholding: List<FinancialSeries> = emptyList(),
    val actions: List<CorporateAction> = emptyList(),
    val peers: List<Pair<String?, Peer>> = emptyList(),
    val news: List<NewsItem> = emptyList()
)

@Composable
fun rememberStockFundamentals(key: String?, refreshKey: Any): StockFundamentals {
    val context = LocalContext.current.applicationContext
    val client = remember { UpstoxTokenStore(context).let { store -> UpstoxApiClient { store.getToken() } } }
    val state by produceState(StockFundamentals(), key, refreshKey) {
        val isin = key?.substringAfter('|')?.takeIf { it.startsWith("IN") && it.length == 12 } ?: return@produceState
        suspend fun <T> part(block: suspend () -> T): T? = try { block() } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { null }
        coroutineScope {
            launch { part { UpstoxFundamentals.profile(client.fundamentals(isin, "profile")) }?.let { value = value.copy(profile = it) } }
            launch { part { UpstoxFundamentals.ratios(client.fundamentals(isin, "key-ratios")) }?.let { value = value.copy(ratios = it) } }
            launch { part { UpstoxFundamentals.statement(client.fundamentals(isin, "income-statement", "?time_period=quarterly"), "income_statement") }?.let { value = value.copy(quarterly = it) } }
            launch { part { UpstoxFundamentals.statement(client.fundamentals(isin, "income-statement", "?time_period=yearly"), "income_statement") }?.let { value = value.copy(yearly = it) } }
            launch { part { UpstoxFundamentals.balance(client.fundamentals(isin, "balance-sheet")) }?.let { value = value.copy(balance = it) } }
            launch { part { UpstoxFundamentals.statement(client.fundamentals(isin, "cash-flow"), "cash_flow") }?.let { value = value.copy(cashFlow = it) } }
            launch { part { UpstoxFundamentals.shareholding(client.fundamentals(isin, "share-holdings")) }?.let { value = value.copy(shareholding = it) } }
            launch { part { UpstoxFundamentals.actions(client.fundamentals(isin, "corporate-actions")) }?.let { value = value.copy(actions = it) } }
            launch {
                part { UpstoxFundamentals.peers(client.fundamentals(isin, "competitors")) }?.let { peers ->
                    runCatching { UpstoxInstruments.load(context) }
                    value = value.copy(peers = peers.map { UpstoxInstruments.symbolForKey(it.instrumentKey) to it })
                }
            }
            launch { part { client.news(key) }?.let { value = value.copy(news = it) } }
        }
    }
    return state
}

private const val QUOTE_REFRESH_MS = 5_000L
