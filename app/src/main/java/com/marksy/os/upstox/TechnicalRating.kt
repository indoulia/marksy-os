package com.marksy.os.upstox

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

enum class Signal { BULLISH, NEUTRAL, BEARISH }

enum class Trend(val label: String) {
    VERY_BULLISH("Very bullish"), BULLISH("Bullish"), NEUTRAL("Neutral"), BEARISH("Bearish"), VERY_BEARISH("Very bearish")
}

/** One scored input: [value] is the indicator or average; [ema] is the matching exponential average for moving averages. */
data class Reading(val name: String, val value: Double, val signal: Signal, val note: String? = null, val ema: Double? = null)

data class TechnicalRating(
    val movingAverages: List<Reading>,
    val indicators: List<Reading>,
    val crossovers: List<Reading>,
    val trend: Trend,
    val asOf: Long
) {
    val all get() = movingAverages + indicators + crossovers
}

enum class PivotMethod { CLASSIC, FIBONACCI, CAMARILLA }

data class Pivots(val method: PivotMethod, val pivot: Double, val r1: Double, val r2: Double, val r3: Double, val s1: Double, val s2: Double, val s3: Double, val from: Long)

/** Daily-candle technicals, computed on the device from Upstox history. Rules are the common textbook ones. */
object Technicals {
    private val MA_PERIODS = listOf(5, 10, 20, 50, 100, 200)
    private val CROSSES = listOf(5 to 20, 20 to 50, 50 to 200)
    private const val MIN_CANDLES = 20

    fun sma(values: List<Double>, n: Int): Double? = if (values.size < n || n <= 0) null else values.takeLast(n).average()

    /** EMA seeded with the first [n]-value SMA; one value per input from index n-1. */
    fun emaSeries(values: List<Double>, n: Int): List<Double> {
        if (values.size < n) return emptyList()
        val k = 2.0 / (n + 1)
        val out = ArrayList<Double>(values.size - n + 1)
        out += values.take(n).average()
        for (i in n until values.size) out += values[i] * k + out.last() * (1 - k)
        return out
    }

    /** Wilder RSI for every close from index [n]. */
    fun rsiSeries(closes: List<Double>, n: Int = 14): List<Double> {
        if (closes.size <= n) return emptyList()
        val diffs = closes.zipWithNext { a, b -> b - a }
        var gain = diffs.take(n).sumOf { max(it, 0.0) } / n
        var loss = diffs.take(n).sumOf { max(-it, 0.0) } / n
        fun value() = if (loss == 0.0) 100.0 else 100 - 100 / (1 + gain / loss)
        val out = mutableListOf(value())
        for (d in diffs.drop(n)) {
            gain = (gain * (n - 1) + max(d, 0.0)) / n
            loss = (loss * (n - 1) + max(-d, 0.0)) / n
            out += value()
        }
        return out
    }

    fun rsi(closes: List<Double>, n: Int = 14): Double? = rsiSeries(closes, n).lastOrNull()

    /** MACD(12,26) line and its 9-period signal. */
    fun macd(closes: List<Double>): Pair<Double, Double>? {
        val fast = emaSeries(closes, 12)
        val slow = emaSeries(closes, 26)
        if (slow.isEmpty()) return null
        val line = slow.indices.map { i -> fast[i + 14] - slow[i] }
        val signal = emaSeries(line, 9).lastOrNull() ?: return null
        return line.last() to signal
    }

    private fun fastK(c: List<Candle>, end: Int, n: Int): Double? {
        if (end + 1 < n) return null
        val window = c.subList(end + 1 - n, end + 1)
        val hi = window.maxOf { it.high }
        val lo = window.minOf { it.low }
        return if (hi > lo) (c[end].close - lo) / (hi - lo) * 100 else null
    }

    /** Slow %K: the fast %K over [n] sessions averaged across the last [smooth]. */
    fun stochastic(c: List<Candle>, n: Int = 14, smooth: Int = 3): Double? {
        val ks = (c.size - smooth until c.size).map { if (it < 0) null else fastK(c, it, n) }
        return if (ks.any { it == null }) null else ks.filterNotNull().average()
    }

    fun stochRsi(closes: List<Double>, n: Int = 14): Double? {
        val r = rsiSeries(closes, n).takeLast(n).takeIf { it.size == n } ?: return null
        val hi = r.max()
        val lo = r.min()
        return if (hi > lo) (r.last() - lo) / (hi - lo) * 100 else null
    }

    fun williamsR(c: List<Candle>, n: Int = 14): Double? = fastK(c, c.lastIndex, n)?.let { it - 100 }

    fun roc(closes: List<Double>, n: Int = 12): Double? =
        if (closes.size <= n) null else closes[closes.lastIndex - n].takeIf { it > 0 }?.let { (closes.last() - it) / it * 100 }

    private fun trueRange(c: List<Candle>, i: Int) = max(c[i].high, c[i - 1].close) - min(c[i].low, c[i - 1].close)

    fun atr(c: List<Candle>, n: Int = 14): Double? {
        if (c.size <= n) return null
        var atr = (1..n).sumOf { trueRange(c, it) } / n
        for (i in n + 1 until c.size) atr = (atr * (n - 1) + trueRange(c, i)) / n
        return atr
    }

    /** Wilder ADX with +DI and -DI. */
    fun adx(c: List<Candle>, n: Int = 14): Triple<Double, Double, Double>? {
        if (c.size < 2 * n + 1) return null
        var tr = 0.0; var plus = 0.0; var minus = 0.0
        var adx = 0.0
        val dxs = mutableListOf<Double>()
        var pdi = 0.0; var mdi = 0.0
        for (i in 1 until c.size) {
            val up = c[i].high - c[i - 1].high
            val down = c[i - 1].low - c[i].low
            val pdm = if (up > down && up > 0) up else 0.0
            val mdm = if (down > up && down > 0) down else 0.0
            if (i <= n) { tr += trueRange(c, i); plus += pdm; minus += mdm } else { tr = tr - tr / n + trueRange(c, i); plus = plus - plus / n + pdm; minus = minus - minus / n + mdm }
            if (i < n) continue
            pdi = if (tr > 0) 100 * plus / tr else 0.0
            mdi = if (tr > 0) 100 * minus / tr else 0.0
            val dx = if (pdi + mdi > 0) 100 * abs(pdi - mdi) / (pdi + mdi) else 0.0
            if (dxs.size < n) { dxs += dx; if (dxs.size == n) adx = dxs.average() } else adx = (adx * (n - 1) + dx) / n
        }
        return if (dxs.size < n) null else Triple(adx, pdi, mdi)
    }

    /** Ultimate Oscillator (7, 14, 28). */
    fun ultimate(c: List<Candle>): Double? {
        if (c.size < 29) return null
        fun avg(n: Int): Double? {
            val idx = (c.size - n until c.size)
            val bp = idx.sumOf { c[it].close - min(c[it].low, c[it - 1].close) }
            val tr = idx.sumOf { trueRange(c, it) }
            return if (tr > 0) bp / tr else null
        }
        val a = avg(7) ?: return null
        val b = avg(14) ?: return null
        val d = avg(28) ?: return null
        return 100 * (4 * a + 2 * b + d) / 7
    }

    /** Annualised % standard deviation of daily log returns. */
    fun volatility(c: List<Candle>): Double? {
        val r = c.takeLast(253).zipWithNext { a, b -> if (a.close > 0 && b.close > 0) ln(b.close / a.close) else null }.filterNotNull()
        if (r.size < 20) return null
        val mean = r.average()
        return sqrt(r.sumOf { (it - mean) * (it - mean) } / (r.size - 1)) * sqrt(252.0) * 100
    }

    /** Slope of the stock's daily returns on the index's, over the sessions both have. */
    fun beta(stock: List<Candle>, index: List<Candle>, zone: ZoneId): Double? {
        fun returns(c: List<Candle>) = c.zipWithNext().mapNotNull { (a, b) ->
            if (a.close > 0) Instant.ofEpochMilli(b.time).atZone(zone).toLocalDate() to (b.close - a.close) / a.close else null
        }.toMap()
        val s = returns(stock)
        val m = returns(index)
        val days = s.keys.intersect(m.keys)
        if (days.size < 20) return null
        val xs = days.map { m.getValue(it) }
        val ys = days.map { s.getValue(it) }
        val mx = xs.average()
        val my = ys.average()
        val variance = xs.sumOf { (it - mx) * (it - mx) }
        return if (variance == 0.0) null else xs.indices.sumOf { (xs[it] - mx) * (ys[it] - my) } / variance
    }

    private fun band(v: Double, bullBelow: Double, bearAbove: Double) = when {
        v < bullBelow -> Signal.BULLISH
        v > bearAbove -> Signal.BEARISH
        else -> Signal.NEUTRAL
    }

    fun rate(daily: List<Candle>): TechnicalRating? {
        if (daily.size < MIN_CANDLES) return null
        val closes = daily.map { it.close }
        val price = closes.last()
        val mas = MA_PERIODS.mapNotNull { n ->
            val s = sma(closes, n) ?: return@mapNotNull null
            Reading("SMA $n", s, if (price >= s) Signal.BULLISH else Signal.BEARISH, if (price >= s) "Price above" else "Price below", emaSeries(closes, n).lastOrNull())
        }
        val crosses = CROSSES.mapNotNull { (a, b) ->
            val fast = sma(closes, a) ?: return@mapNotNull null
            val slow = sma(closes, b) ?: return@mapNotNull null
            Reading("SMA $a × $b", fast - slow, if (fast >= slow) Signal.BULLISH else Signal.BEARISH, if (fast >= slow) "$a above $b" else "$a below $b")
        }
        val indicators = buildList {
            rsi(closes)?.let { add(Reading("RSI (14)", it, band(it, 30.0, 70.0), zone(it, 30.0, 70.0))) }
            macd(closes)?.let { (line, signal) -> add(Reading("MACD (12,26,9)", line, if (line >= signal) Signal.BULLISH else Signal.BEARISH, if (line >= signal) "Above signal" else "Below signal")) }
            stochastic(daily)?.let { add(Reading("Stochastic (14,3)", it, band(it, 20.0, 80.0), zone(it, 20.0, 80.0))) }
            stochRsi(closes)?.let { add(Reading("Stoch RSI (14)", it, band(it, 20.0, 80.0), zone(it, 20.0, 80.0))) }
            williamsR(daily)?.let { add(Reading("Williams %R (14)", it, band(it, -80.0, -20.0), zone(it, -80.0, -20.0))) }
            roc(closes)?.let { add(Reading("ROC (12)", it, if (it >= 0) Signal.BULLISH else Signal.BEARISH)) }
            adx(daily)?.let { (a, p, m) ->
                val s = if (a < 25) Signal.NEUTRAL else if (p >= m) Signal.BULLISH else Signal.BEARISH
                add(Reading("ADX (14)", a, s, if (a < 25) "Weak trend" else if (p >= m) "Strong uptrend" else "Strong downtrend"))
            }
            ultimate(daily)?.let { add(Reading("Ultimate osc. (7,14,28)", it, band(it, 30.0, 70.0), zone(it, 30.0, 70.0))) }
        }
        val all = mas + indicators + crosses
        val score = (all.count { it.signal == Signal.BULLISH } - all.count { it.signal == Signal.BEARISH }).toDouble() / all.size
        val trend = when {
            score >= .5 -> Trend.VERY_BULLISH
            score >= .15 -> Trend.BULLISH
            score > -.15 -> Trend.NEUTRAL
            score > -.5 -> Trend.BEARISH
            else -> Trend.VERY_BEARISH
        }
        return TechnicalRating(mas, indicators, crosses, trend, daily.last().time)
    }

    private fun zone(v: Double, low: Double, high: Double) = when { v < low -> "Oversold"; v > high -> "Overbought"; else -> null }

    /** The rating at each of the last [weeks] weeks' final session, newest first. */
    fun history(daily: List<Candle>, weeks: Int = 6, zone: ZoneId): List<Pair<Candle, Trend>> {
        fun week(c: Candle) = Instant.ofEpochMilli(c.time).atZone(zone).toLocalDate().with(DayOfWeek.MONDAY)
        val lastOfWeek = daily.indices.filter { i -> i == daily.lastIndex || week(daily[i + 1]) != week(daily[i]) }
        return lastOfWeek.takeLast(weeks).reversed().mapNotNull { i -> rate(daily.subList(0, i + 1))?.let { daily[i] to it.trend } }
    }

    /** Levels for [today] from the last full session before it. */
    fun pivots(daily: List<Candle>, today: LocalDate, zone: ZoneId, method: PivotMethod = PivotMethod.CLASSIC): Pivots? {
        val s = daily.lastOrNull { Instant.ofEpochMilli(it.time).atZone(zone).toLocalDate() < today } ?: return null
        val (h, l, c) = Triple(s.high, s.low, s.close)
        val p = (h + l + c) / 3
        val r = h - l
        return when (method) {
            PivotMethod.CLASSIC -> Pivots(method, p, 2 * p - l, p + r, h + 2 * (p - l), 2 * p - h, p - r, l - 2 * (h - p), s.time)
            PivotMethod.FIBONACCI -> Pivots(method, p, p + .382 * r, p + .618 * r, p + r, p - .382 * r, p - .618 * r, p - r, s.time)
            PivotMethod.CAMARILLA -> Pivots(method, p, c + r * 1.1 / 12, c + r * 1.1 / 6, c + r * 1.1 / 4, c - r * 1.1 / 12, c - r * 1.1 / 6, c - r * 1.1 / 4, s.time)
        }
    }
}
