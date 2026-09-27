package com.marksy.os.upstox

import java.time.Instant
import java.time.ZoneId
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Indicator series for chart overlays, one value (or null while warming up) per candle. */
object ChartOverlays {
    data class Bands(val mid: List<Double?>, val upper: List<Double?>, val lower: List<Double?>)

    fun ema(values: List<Double>, n: Int): List<Double?> {
        val series = Technicals.emaSeries(values, n)
        return List(values.size - series.size) { null } + series
    }

    fun bollinger(closes: List<Double>, n: Int = 20, k: Double = 2.0): Bands {
        val mid = ArrayList<Double?>(closes.size); val upper = ArrayList<Double?>(closes.size); val lower = ArrayList<Double?>(closes.size)
        closes.indices.forEach { i ->
            if (i < n - 1) { mid += null; upper += null; lower += null; return@forEach }
            val w = closes.subList(i - n + 1, i + 1)
            val m = w.average()
            val sd = sqrt(w.sumOf { (it - m) * (it - m) } / n)
            mid += m; upper += m + k * sd; lower += m - k * sd
        }
        return Bands(mid, upper, lower)
    }

    /** Volume-weighted typical price, restarting at each session (IST trading day). */
    fun vwap(candles: List<Candle>, zone: ZoneId = ZoneId.of("Asia/Kolkata")): List<Double?> {
        var day: java.time.LocalDate? = null
        var pv = 0.0; var vol = 0.0
        return candles.map { c ->
            val d = Instant.ofEpochMilli(c.time).atZone(zone).toLocalDate()
            if (d != day) { day = d; pv = 0.0; vol = 0.0 }
            pv += (c.high + c.low + c.close) / 3 * c.volume; vol += c.volume
            if (vol > 0) pv / vol else null
        }
    }

    /** Supertrend(n, mult): the trailing band value and whether the trend is up, per candle. */
    fun supertrend(c: List<Candle>, n: Int = 10, mult: Double = 3.0): List<Pair<Double, Boolean>?> {
        if (c.size <= n) return List(c.size) { null }
        val out = MutableList<Pair<Double, Boolean>?>(c.size) { null }
        var atr = (1..n).sumOf { tr(c, it) } / n
        var upper = 0.0; var lower = 0.0; var up = true
        for (i in n until c.size) {
            if (i > n) atr = (atr * (n - 1) + tr(c, i)) / n
            val mid = (c[i].high + c[i].low) / 2
            val basicUpper = mid + mult * atr; val basicLower = mid - mult * atr
            // Bands only ratchet toward price while the previous close stayed inside them.
            upper = if (i == n || basicUpper < upper || c[i - 1].close > upper) basicUpper else upper
            lower = if (i == n || basicLower > lower || c[i - 1].close < lower) basicLower else lower
            up = if (i == n) c[i].close >= mid else if (up) c[i].close >= lower else c[i].close > upper
            out[i] = (if (up) lower else upper) to up
        }
        return out
    }

    private fun tr(c: List<Candle>, i: Int) = max(c[i].high, c[i - 1].close) - min(c[i].low, c[i - 1].close)
}
