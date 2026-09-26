package com.marksy.os.upstox

import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId

/** How one calendar month has gone across the years: [best] and [worst] are (% change, year). */
data class MonthSummary(
    val month: Int,
    val years: Int,
    val negative: Int,
    val best: Pair<Double, Int>,
    val worst: Pair<Double, Int>,
    val averageGain: Double?,
    val averageLoss: Double?,
    val average: Double
)

/** Month-by-month history from Upstox monthly candles. */
object Seasonality {
    private fun ym(c: Candle, zone: ZoneId) = YearMonth.from(Instant.ofEpochMilli(c.time).atZone(zone))

    /** Year (newest first) -> 12 monthly % changes, null where there is no candle. */
    fun table(monthly: List<Candle>, zone: ZoneId): Map<Int, Array<Double?>> {
        val out = sortedMapOf<Int, Array<Double?>>(compareByDescending { it })
        monthly.forEachIndexed { i, c ->
            val m = ym(c, zone)
            // Compare with last month's close; a gap in the history falls back to the month's own open.
            val base = monthly.getOrNull(i - 1)?.takeIf { ym(it, zone) == m.minusMonths(1) }?.close ?: c.open
            if (base > 0) out.getOrPut(m.year) { arrayOfNulls(12) }[m.monthValue - 1] = (c.close - base) / base * 100
        }
        return out
    }

    fun summary(table: Map<Int, Array<Double?>>, month: Int): MonthSummary? {
        val values = table.mapNotNull { (year, row) -> row[month - 1]?.let { it to year } }
        if (values.isEmpty()) return null
        val gains = values.filter { it.first >= 0 }.map { it.first }
        val losses = values.filter { it.first < 0 }.map { it.first }
        return MonthSummary(
            month, values.size, losses.size, values.maxBy { it.first }, values.minBy { it.first },
            gains.takeIf { it.isNotEmpty() }?.average(), losses.takeIf { it.isNotEmpty() }?.average(), values.map { it.first }.average()
        )
    }

    /** Average % change for each calendar month, null for months with no history. */
    fun monthlyAverages(table: Map<Int, Array<Double?>>): List<Double?> =
        (0 until 12).map { m -> table.values.mapNotNull { it[m] }.takeIf { it.isNotEmpty() }?.average() }

    /** YTD from last year's final close, and 3Y/5Y/10Y from the close of the same month that many years back. */
    fun longReturns(monthly: List<Candle>, lastPrice: Double, zone: ZoneId): Map<String, Double> {
        val last = monthly.lastOrNull()?.let { ym(it, zone) } ?: return emptyMap()
        val closes = monthly.associate { ym(it, zone) to it.close }
        fun from(base: Double?) = base?.takeIf { it > 0 }?.let { (lastPrice - it) / it * 100 }
        return buildMap {
            from(closes[YearMonth.of(last.year - 1, 12)])?.let { put("YTD", it) }
            listOf(3, 5, 10).forEach { n -> from(closes[last.minusYears(n.toLong())])?.let { put("${n}Y", it) } }
        }
    }

    /** (all-time low, its month) to (all-time high, its month) over the monthly history. */
    fun allTime(monthly: List<Candle>): Pair<Pair<Double, Long>, Pair<Double, Long>>? {
        if (monthly.isEmpty()) return null
        val lo = monthly.minBy { it.low }
        val hi = monthly.maxBy { it.high }
        return (lo.low to lo.time) to (hi.high to hi.time)
    }
}
