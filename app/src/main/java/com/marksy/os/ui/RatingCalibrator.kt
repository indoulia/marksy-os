package com.marksy.os.ui

import android.content.Context
import com.marksy.os.market.ClosedPredictionDto
import com.marksy.os.market.MarketDataState
import com.marksy.os.market.MarketIntelligenceRepository
import com.marksy.os.rating.ActiveRating
import com.marksy.os.rating.Factor
import com.marksy.os.rating.Horizon
import com.marksy.os.rating.RatingCalibration
import com.marksy.os.rating.RatingConfig
import com.marksy.os.upstox.Candle
import com.marksy.os.upstox.UpstoxApiClient
import com.marksy.os.upstox.UpstoxCandles
import com.marksy.os.upstox.UpstoxIndices
import com.marksy.os.upstox.UpstoxInstruments
import com.marksy.os.upstox.UpstoxTokenStore
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId

/** Runs the rating calibration on this device from Marksy's closed calls and Upstox history; keeps the result. */
object RatingCalibrator {
    data class Saved(val version: String, val adopted: Boolean, val samples: Int, val testSamples: Int, val baseTest: Double, val test: Double, val ranAt: Long, val trend: Double, val seasonality: Double)

    private const val PREFS = "marksy_rating_calibration"
    private const val MAX_CALLS = 400
    private val zone = ZoneId.of("Asia/Kolkata")

    /** Applies a previously adopted calibration at app start. */
    fun load(context: Context): Saved? {
        val s = read(context) ?: return null
        if (s.adopted) ActiveRating.config = configFor(s)
        return s
    }

    fun read(context: Context): Saved? = runCatching {
        val o = JSONObject(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("last", null) ?: return null)
        Saved(o.getString("version"), o.getBoolean("adopted"), o.getInt("samples"), o.getInt("testSamples"), o.getDouble("baseTest"), o.getDouble("test"), o.getLong("ranAt"), o.getDouble("trend"), o.getDouble("seasonality"))
    }.getOrNull()

    private fun configFor(s: Saved): RatingConfig {
        val v1 = RatingConfig.V1
        return v1.copy(version = s.version, weights = v1.weights + (Horizon.SHORT to v1.weights.getValue(Horizon.SHORT) + mapOf(Factor.TREND to s.trend, Factor.SEASONALITY to s.seasonality)))
    }

    suspend fun run(context: Context, market: MarketIntelligenceRepository, progress: (String) -> Unit): Saved {
        val app = context.applicationContext
        val store = UpstoxTokenStore(app)
        check(store.hasToken()) { "Connect Upstox in More first: calibration needs price history." }
        val client = UpstoxApiClient { store.getToken() }

        progress("Loading Marksy's closed calls…")
        val calls = mutableListOf<ClosedPredictionDto>()
        var cursor: String? = null
        do {
            val page = (market.closedPredictions(cursor) as? MarketDataState.Loaded)?.value ?: break
            calls += page.items
            cursor = page.nextCursor
        } while (cursor != null && calls.size < MAX_CALLS)
        val usable = calls.filter { it.realizedReturn != null && it.excludedReason == null && day(it.asOf) != null }
            .sortedBy { day(it.asOf) }.takeLast(MAX_CALLS)
        check(usable.size >= 60) { "Only ${usable.size} scored calls so far; calibration needs at least 60." }

        UpstoxInstruments.load(app)
        val first = usable.minOf { day(it.asOf)!! }
        val last = usable.maxOf { day(it.asOf)!! }
        val nifty = client.dailyCandles(UpstoxIndices.NIFTY_50, first.minusDays(400), last)
        val samples = mutableListOf<RatingCalibration.Sample>()
        val bySymbol = usable.groupBy { it.symbol }
        bySymbol.entries.forEachIndexed { i, (symbol, symbolCalls) ->
            progress("Rebuilding past ratings: ${i + 1} of ${bySymbol.size} stocks")
            val key = UpstoxInstruments.keyFor(symbol) ?: return@forEachIndexed
            val daily = runCatching { client.dailyCandles(key, first.minusDays(400), last) }.getOrNull() ?: return@forEachIndexed
            val monthly = runCatching { client.monthlyCandles(key, last) }.getOrDefault(emptyList())
            symbolCalls.forEach { c -> pointInTime(c, daily, monthly, nifty)?.let(samples::add) }
            delay(120)
        }
        progress("Tuning weights on ${samples.size} calls…")
        val result = RatingCalibration.calibrate(samples, version = "marksy-rating-1-cal-$last")
        val short = result.config.weights.getValue(Horizon.SHORT)
        val saved = Saved(result.config.version, result.adopted, result.samples, result.testSamples, result.baseTest, result.test, System.currentTimeMillis(),
            short[Factor.TREND] ?: 0.0, short[Factor.SEASONALITY] ?: 0.0)
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("last", JSONObject()
            .put("version", saved.version).put("adopted", saved.adopted).put("samples", saved.samples).put("testSamples", saved.testSamples)
            .put("baseTest", saved.baseTest).put("test", saved.test).put("ranAt", saved.ranAt).put("trend", saved.trend).put("seasonality", saved.seasonality).toString()).apply()
        ActiveRating.config = if (saved.adopted) configFor(saved) else RatingConfig.V1
        return saved
    }

    /** The rating inputs as they stood when the call was made: candles up to that day, months before it, no fundamentals. */
    private fun pointInTime(c: ClosedPredictionDto, daily: List<Candle>, monthly: List<Candle>, nifty: List<Candle>): RatingCalibration.Sample? {
        val date = day(c.asOf) ?: return null
        val end = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val monthStart = date.withDayOfMonth(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val d = daily.filter { it.time < end }.takeIf { it.size >= 30 } ?: return null
        val price = d.last().close
        val live = StockLive(
            daily = d, index = nifty.filter { it.time < end }, monthly = monthly.filter { it.time < monthStart },
            returns = UpstoxCandles.returns(d, price), yearRange = UpstoxCandles.range(d.takeLast(250))
        )
        val inputs = StockRatingInputs.from(live, StockFundamentals(), null, date, zone)
            .copy(price = price, callUpsidePct = c.predictedReturn?.let { it * 100 })
        return RatingCalibration.Sample(inputs, c.realizedReturn ?: return null)
    }

    private fun day(iso: String?): LocalDate? = iso?.let {
        runCatching { OffsetDateTime.parse(it).atZoneSameInstant(zone).toLocalDate() }.getOrNull()
            ?: runCatching { Instant.parse(it).atZone(zone).toLocalDate() }.getOrNull()
            ?: runCatching { LocalDate.parse(it.take(10)) }.getOrNull()
    }
}
