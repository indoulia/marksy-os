package com.marksy.os.portfolio

import com.marksy.os.upstox.Candle
import kotlin.math.abs

enum class PortfolioPeriod(val label: String, val title: String, val days: Long) {
    D1("1D", "Today", 1), W1("1W", "1 week", 7), M1("1M", "1 month", 30), M3("3M", "3 months", 91), Y1("1Y", "1 year", 365)
}

enum class PortfolioMetric { PERIOD, TOTAL }

enum class HoldingSort(val label: String) {
    MOVE("Biggest ₹ move"), BEST("Best %"), WORST("Worst %"), VALUE("Largest holding"), NAME("Name A–Z")
}

enum class AllocationBy(val label: String) { SECTOR("Sector"), HOLDING("Holding"), TYPE("Type") }

data class LivePrice(val last: Double, val previousClose: Double?)

data class HoldingRow(
    val holding: Holding,
    val price: Double,
    val value: Double,
    val invested: Double,
    val weightPct: Double,
    val dayPnl: Double?,
    val dayPct: Double?,
    val periodPnl: Double?,
    val periodPct: Double?,
    val totalPnl: Double,
    val totalPct: Double?
) {
    fun pnl(metric: PortfolioMetric): Double? = if (metric == PortfolioMetric.TOTAL) totalPnl else periodPnl
    fun pct(metric: PortfolioMetric): Double? = if (metric == PortfolioMetric.TOTAL) totalPct else periodPct
}

data class PortfolioTotals(val value: Double, val invested: Double, val periodPnl: Double, val periodPct: Double?, val totalPnl: Double, val totalPct: Double?)

data class AllocationSlice(val name: String, val pct: Double)

object PortfolioMath {
    private const val DAY_MS = 86_400_000L

    /** [live] is keyed by instrument key; [bases] (period start price by symbol) is ignored for 1D, which uses the previous close. */
    fun rows(holdings: List<Holding>, live: Map<String, LivePrice>, period: PortfolioPeriod, bases: Map<String, Double>): List<HoldingRow> {
        val priced = holdings.map { h ->
            val l = live[h.instrumentKey]
            Triple(h, l?.last?.takeIf { it > 0 } ?: h.lastPrice, (l?.previousClose ?: h.previousClose)?.takeIf { it > 0 })
        }
        val total = priced.sumOf { (h, price, _) -> h.quantity * price }
        return priced.map { (h, price, prev) ->
            val qty = h.quantity.toDouble()
            val value = qty * price
            val invested = qty * h.averagePrice
            val base = (if (period == PortfolioPeriod.D1) prev else bases[h.symbol])?.takeIf { it > 0 }
            HoldingRow(
                holding = h, price = price, value = value, invested = invested,
                weightPct = if (total > 0) value / total * 100 else 0.0,
                dayPnl = prev?.let { qty * (price - it) }, dayPct = prev?.let { (price / it - 1) * 100 },
                periodPnl = base?.let { qty * (price - it) }, periodPct = base?.let { (price / it - 1) * 100 },
                totalPnl = value - invested, totalPct = if (h.averagePrice > 0) (price / h.averagePrice - 1) * 100 else null
            )
        }
    }

    fun totals(rows: List<HoldingRow>): PortfolioTotals {
        val value = rows.sumOf { it.value }
        val invested = rows.sumOf { it.invested }
        val based = rows.filter { it.periodPnl != null }
        val pnl = based.sumOf { it.periodPnl ?: 0.0 }
        val start = based.sumOf { it.value } - pnl
        return PortfolioTotals(
            value, invested, pnl, if (based.isNotEmpty() && start > 0) pnl / start * 100 else null,
            value - invested, if (invested > 0) (value - invested) / invested * 100 else null
        )
    }

    /** Symbols in display order; the page re-ranks only on load, refresh, or a metric, period or sort change. */
    fun order(rows: List<HoldingRow>, metric: PortfolioMetric, sort: HoldingSort): List<String> = when (sort) {
        HoldingSort.MOVE -> rows.sortedByDescending { abs(it.pnl(metric) ?: 0.0) }
        HoldingSort.BEST -> rows.sortedByDescending { it.pct(metric) ?: Double.NEGATIVE_INFINITY }
        HoldingSort.WORST -> rows.sortedBy { it.pct(metric) ?: Double.POSITIVE_INFINITY }
        HoldingSort.VALUE -> rows.sortedByDescending { it.value }
        HoldingSort.NAME -> rows.sortedBy { it.holding.symbol }
    }.map { it.holding.symbol }

    /** [rows] in a settled [order]; holdings that arrived since go last, largest first. */
    fun arrange(rows: List<HoldingRow>, order: List<String>): List<HoldingRow> {
        val index = order.withIndex().associate { (i, s) -> s to i }
        return rows.sortedWith(compareBy<HoldingRow> { index[it.holding.symbol] ?: Int.MAX_VALUE }.thenByDescending { it.value })
    }

    fun allocation(rows: List<HoldingRow>, by: AllocationBy): List<AllocationSlice> = rows.groupBy {
        when (by) {
            AllocationBy.SECTOR -> it.holding.sector ?: "Other"
            AllocationBy.HOLDING -> it.holding.symbol
            AllocationBy.TYPE -> it.holding.type.label
        }
    }.map { (name, group) -> AllocationSlice(name, group.sumOf { it.weightPct }) }.sortedByDescending { it.pct }

    /** Value at each candle time: Σ qty × close, each holding carrying its last close forward (its first close before it starts). */
    fun valueSeries(holdings: List<Holding>, candles: Map<String, List<Candle>>): List<Pair<Long, Double>> {
        val times = candles.values.flatMap { c -> c.map { it.time } }.distinct().sorted()
        return times.map { t ->
            t to holdings.sumOf { h ->
                val c = candles[h.symbol].orEmpty()
                h.quantity * (c.lastOrNull { it.time <= t }?.close ?: c.firstOrNull()?.close ?: h.lastPrice)
            }
        }
    }

    /** The daily candles inside [period], ending at the latest one. */
    fun window(daily: List<Candle>, period: PortfolioPeriod): List<Candle> {
        val end = daily.lastOrNull()?.time ?: return emptyList()
        return daily.filter { it.time >= end - period.days * DAY_MS }
    }
}
