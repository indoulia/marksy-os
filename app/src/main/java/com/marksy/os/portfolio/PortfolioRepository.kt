package com.marksy.os.portfolio

import android.content.Context
import com.marksy.os.upstox.Candle
import com.marksy.os.upstox.ChartRange
import com.marksy.os.upstox.UpstoxApiClient
import com.marksy.os.upstox.UpstoxCandles
import com.marksy.os.upstox.UpstoxFundamentals
import com.marksy.os.upstox.UpstoxIndices
import com.marksy.os.upstox.UpstoxOAuthStore
import com.marksy.os.upstox.UpstoxTokenStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONObject
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap

enum class PortfolioConnection { NOT_CONNECTED, SIGNED_IN, SIGNED_OUT }

/** Daily candles per holding symbol plus NIFTY 50, for period bases and the chart. */
data class PortfolioHistory(val daily: Map<String, List<Candle>>, val nifty: List<Candle>)

/** Holdings from Upstox straight to this phone (never via Marksy), cached encrypted for the signed-out morning. */
class PortfolioRepository(context: Context) {
    private val app = context.applicationContext
    val store = UpstoxOAuthStore(app)
    private val analytics = UpstoxTokenStore(app)
    private val upstox = UpstoxHoldingsSource({ store.accessToken() }) { token -> UpstoxApiClient { token }.longTermHoldings() }
    val providers: List<PortfolioProvider> = PortfolioProviders.catalog(upstox)

    fun cached(): HoldingsSnapshot? = store.holdings()?.let(HoldingsSnapshot::fromJson)

    fun connection(now: Long = System.currentTimeMillis()): PortfolioConnection = when {
        store.accessToken(now) != null -> PortfolioConnection.SIGNED_IN
        store.holdings() != null -> PortfolioConnection.SIGNED_OUT
        else -> PortfolioConnection.NOT_CONNECTED
    }

    /** A rejected or expired token signs out but keeps the app keys and the cached holdings. */
    suspend fun refresh(now: Long = System.currentTimeMillis()): HoldingsResult = when (val r = upstox.fetch(now)) {
        is HoldingsResult.Ok -> HoldingsResult.Ok(withSectors(r.snapshot)).also { store.saveHoldings(it.snapshot.toJson()) }
        is HoldingsResult.SignedOut -> r.also { store.clearToken() }
        is HoldingsResult.Failed -> r
    }

    // Candles and sector profiles are market data: the Analytics Token when saved, else today's sign-in.
    private fun market() = UpstoxApiClient { analytics.getToken() ?: store.accessToken() }

    private suspend fun withSectors(snapshot: HoldingsSnapshot): HoldingsSnapshot {
        val known = cached()?.holdings.orEmpty().mapNotNull { h -> h.sector?.let { h.isin to it } }.toMap()
        val gate = Semaphore(4)
        val holdings = coroutineScope {
            snapshot.holdings.map { h ->
                async {
                    val sector = when {
                        h.type == HoldingType.ETF -> "ETFs"
                        known[h.isin] != null -> known[h.isin]
                        h.isin.isBlank() -> null
                        else -> gate.withPermit { quietly { UpstoxFundamentals.profile(market().fundamentals(h.isin, "profile")).sector } }
                    }
                    h.copy(sector = sector)
                }
            }.awaitAll()
        }
        return snapshot.copy(holdings = holdings)
    }

    suspend fun history(holdings: List<Holding>, today: LocalDate): PortfolioHistory = coroutineScope {
        val gate = Semaphore(4)
        val from = today.minusDays(372)
        val nifty = async { gate.withPermit { daily(UpstoxIndices.NIFTY_50, from, today) } }
        val daily = holdings.map { h -> async { h.symbol to gate.withPermit { daily(h.instrumentKey, from, today) } } }.awaitAll().toMap()
        PortfolioHistory(daily, nifty.await())
    }

    suspend fun intraday(holdings: List<Holding>, today: LocalDate): Map<String, List<Candle>> = coroutineScope {
        val gate = Semaphore(4)
        holdings.map { h -> async { h.symbol to gate.withPermit { quietly { market().candles(h.instrumentKey, ChartRange.D1, today, IST) }.orEmpty() } } }
            .awaitAll().toMap()
    }

    private suspend fun daily(key: String, from: LocalDate, today: LocalDate): List<Candle> {
        val cacheKey = "$today|$key"
        DAILY[cacheKey]?.let { return it }
        return quietly { market().dailyCandles(key, from, today) }?.also { if (it.isNotEmpty()) DAILY[cacheKey] = it }.orEmpty()
    }

    fun hidden(now: Long): Set<String> = hiddenMap().filterValues { it > now }.keys

    fun hide(symbol: String, now: Long) {
        val map = hiddenMap().filterValues { it > now } + (symbol to PortfolioFlags.hiddenUntil(now))
        store.saveHidden(JSONObject(map as Map<*, *>).toString())
    }

    fun unhideAll() = store.saveHidden("")

    /** Forgets the keys, token, holdings and hidden cards, plus Upstox's login cookies in the sign-in WebView. */
    fun disconnect() {
        store.disconnect()
        DAILY.clear()
        runCatching {
            android.webkit.CookieManager.getInstance().removeAllCookies(null)
            android.webkit.WebStorage.getInstance().deleteAllData()
        }
    }

    private fun hiddenMap(): Map<String, Long> = runCatching {
        val o = JSONObject(store.hidden() ?: return emptyMap())
        o.keys().asSequence().associateWith { o.getLong(it) }
    }.getOrDefault(emptyMap())

    private suspend fun <T> quietly(block: suspend () -> T?): T? = try { block() } catch (e: CancellationException) { throw e } catch (_: Exception) { null }

    companion object {
        val IST: ZoneId = ZoneId.of("Asia/Kolkata")
        // One fetch of a year's daily candles per instrument per day, shared by every Portfolio visit.
        private val DAILY = ConcurrentHashMap<String, List<Candle>>()

        fun bases(history: PortfolioHistory?, period: PortfolioPeriod): Map<String, Double> =
            history?.daily.orEmpty().mapNotNull { (symbol, candles) -> UpstoxCandles.bases(candles)[period.label]?.let { symbol to it } }.toMap()
    }
}
