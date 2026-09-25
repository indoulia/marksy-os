package com.marksy.os.upstox

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId

data class DepthLevel(val price: Double, val quantity: Long, val orders: Int)

/** One `GET /v2/market-quote/quotes` entry: everything Upstox shows above a stock's chart. */
data class UpstoxQuote(
    val lastPrice: Double,
    val prevClose: Double?,
    val open: Double?,
    val high: Double?,
    val low: Double?,
    val volume: Long?,
    val averagePrice: Double?,
    val upperCircuit: Double?,
    val lowerCircuit: Double?,
    val totalBuyQty: Long?,
    val totalSellQty: Long?,
    val lastTradeTime: Long?,
    val bids: List<DepthLevel>,
    val asks: List<DepthLevel>
) {
    val change: Double? get() = prevClose?.let { lastPrice - it }
    val changePct: Double? get() = prevClose?.takeIf { it > 0 }?.let { (lastPrice - it) / it * 100 }

    companion object {
        fun parse(body: String): UpstoxQuote = upstoxData(body) { data ->
            val q = data.keys().asSequence().firstOrNull()?.let(data::getJSONObject) ?: throw IOException("Upstox returned no quote")
            val last = q.getDouble("last_price")
            val ohlc = q.optJSONObject("ohlc")
            val depth = q.optJSONObject("depth")
            UpstoxQuote(
                lastPrice = last,
                // net_change is measured from the previous close; ohlc.close is only a fallback.
                prevClose = q.num("net_change")?.let { last - it } ?: ohlc?.num("close"),
                open = ohlc?.num("open"), high = ohlc?.num("high"), low = ohlc?.num("low"),
                volume = q.num("volume")?.toLong(), averagePrice = q.num("average_price"),
                upperCircuit = q.num("upper_circuit_limit"), lowerCircuit = q.num("lower_circuit_limit"),
                totalBuyQty = q.num("total_buy_quantity")?.toLong(), totalSellQty = q.num("total_sell_quantity")?.toLong(),
                lastTradeTime = q.optString("last_trade_time").toLongOrNull(),
                bids = levels(depth?.optJSONArray("buy")), asks = levels(depth?.optJSONArray("sell"))
            )
        }

        private fun levels(a: JSONArray?): List<DepthLevel> = (0 until (a?.length() ?: 0)).mapNotNull { i ->
            val l = a!!.optJSONObject(i) ?: return@mapNotNull null
            DepthLevel(l.optDouble("price"), l.optLong("quantity"), l.optInt("orders")).takeIf { it.price > 0 }
        }
    }
}

data class Candle(val time: Long, val open: Double, val high: Double, val low: Double, val close: Double, val volume: Long)

object UpstoxCandles {
    /** Upstox lists candles newest first as `[time, open, high, low, close, volume, oi]`; returned oldest first. */
    fun parse(body: String): List<Candle> = upstoxData(body) { data ->
        val rows = data.optJSONArray("candles") ?: return@upstoxData emptyList()
        (0 until rows.length()).mapNotNull { i ->
            val c = rows.optJSONArray(i) ?: return@mapNotNull null
            runCatching { Candle(OffsetDateTime.parse(c.getString(0)).toInstant().toEpochMilli(), c.getDouble(1), c.getDouble(2), c.getDouble(3), c.getDouble(4), c.optLong(5)) }.getOrNull()
        }.sortedBy { it.time }
    }

    fun range(candles: List<Candle>): Pair<Double, Double>? =
        if (candles.isEmpty()) null else candles.minOf { it.low } to candles.maxOf { it.high }

    private val PERIODS = listOf("1W" to 7L, "1M" to 30L, "3M" to 91L, "6M" to 182L, "1Y" to 365L)

    /** % change to [lastPrice] from the close on or before each period's start; periods the data doesn't reach are left out. */
    fun returns(daily: List<Candle>, lastPrice: Double): Map<String, Double> {
        val end = daily.lastOrNull()?.time ?: return emptyMap()
        return PERIODS.mapNotNull { (label, days) ->
            val start = end - days * DAY_MS
            // A fetched year can begin a few days after the exact mark (weekends, holidays); the first candle stands in.
            val base = (daily.lastOrNull { it.time <= start } ?: daily.first().takeIf { it.time - start <= PERIOD_SLACK_MS })
                ?.close?.takeIf { it > 0 } ?: return@mapNotNull null
            label to (lastPrice - base) / base * 100
        }.toMap()
    }

    fun averageVolume(daily: List<Candle>, sessions: Int): Long? = daily.takeLast(sessions).takeIf { it.isNotEmpty() }?.map { it.volume }?.average()?.toLong()

    private const val DAY_MS = 86_400_000L
    private const val PERIOD_SLACK_MS = 5 * DAY_MS

    /** The latest trading day's candles: the 1D chart when the market is closed. */
    fun lastSession(candles: List<Candle>, zone: ZoneId): List<Candle> {
        fun day(c: Candle) = Instant.ofEpochMilli(c.time).atZone(zone).toLocalDate()
        val last = candles.lastOrNull()?.let(::day) ?: return emptyList()
        return candles.filter { day(it) == last }
    }
}

enum class ChartRange(val label: String) {
    D1("1D"), W1("1W"), M1("1M"), Y1("1Y"), Y5("5Y");

    /** Path under the v3 API for this range's candles. */
    fun path(key: String, today: LocalDate): String {
        val k = URLEncoder.encode(key, "UTF-8")
        return when (this) {
            D1 -> "historical-candle/intraday/$k/minutes/5"
            W1 -> "historical-candle/$k/minutes/30/$today/${today.minusDays(7)}"
            M1 -> "historical-candle/$k/days/1/$today/${today.minusMonths(1)}"
            Y1 -> "historical-candle/$k/days/1/$today/${today.minusYears(1)}"
            Y5 -> "historical-candle/$k/weeks/1/$today/${today.minusYears(5)}"
        }
    }

    /** Before the open and on holidays intraday is empty, so 1D shows the last session from recent history. */
    fun fallbackPath(key: String, today: LocalDate): String =
        "historical-candle/${URLEncoder.encode(key, "UTF-8")}/minutes/5/$today/${today.minusDays(7)}"
}

private fun JSONObject.num(name: String): Double? = if (has(name) && !isNull(name)) optDouble(name).takeIf { !it.isNaN() } else null

private fun <T> upstoxData(body: String, read: (JSONObject) -> T): T = try {
    val root = JSONObject(body)
    if (root.optString("status") != "success") {
        val message = root.optJSONArray("errors")?.optJSONObject(0)?.optString("message")?.takeIf { it.isNotBlank() }
        throw IOException("Upstox: ${message ?: "request failed"}")
    }
    read(root.getJSONObject("data"))
} catch (e: JSONException) {
    throw IOException("Upstox returned an unreadable response", e)
}
