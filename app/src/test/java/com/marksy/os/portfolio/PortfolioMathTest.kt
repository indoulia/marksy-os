package com.marksy.os.portfolio

import com.marksy.os.upstox.Candle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PortfolioMathTest {
    private fun h(symbol: String, qty: Long, avg: Double, ltp: Double, prev: Double?, sector: String? = null, type: HoldingType = HoldingType.STOCK) =
        Holding(symbol, symbol, "", "NSE_EQ|$symbol", type, qty, avg, ltp, prev, sector)

    private val suzlon = h("SUZLON", 1000, 62.40, 54.10, 56.95, "Power")
    private val infy = h("INFY", 40, 1512.40, 1836.00, 1858.20, "IT")

    @Test fun livePriceOverridesTheSnapshotAndTodayUsesPreviousClose() {
        val r = PortfolioMath.rows(listOf(suzlon), mapOf("NSE_EQ|SUZLON" to LivePrice(55.0, 57.0)), PortfolioPeriod.D1, emptyMap()).single()
        assertEquals(55.0, r.price, 1e-9)
        assertEquals(55_000.0, r.value, 1e-6)
        assertEquals(62_400.0, r.invested, 1e-6)
        assertEquals(-2_000.0, r.dayPnl!!, 1e-6)
        assertEquals(-3.5088, r.dayPct!!, 1e-3)
        assertEquals(-2_000.0, r.periodPnl!!, 1e-6)
        assertEquals(-7_400.0, r.totalPnl, 1e-6)
        assertEquals(-11.859, r.totalPct!!, 1e-3)
    }

    @Test fun periodPnlUsesTheBaseAndLeavesOutHoldingsWithoutOne() {
        val rows = PortfolioMath.rows(listOf(suzlon, infy), emptyMap(), PortfolioPeriod.M1, mapOf("SUZLON" to 61.13))
        val s = rows.first { it.holding.symbol == "SUZLON" }
        assertEquals(1000 * (54.10 - 61.13), s.periodPnl!!, 1e-6)
        assertNull(rows.first { it.holding.symbol == "INFY" }.periodPnl)
        val t = PortfolioMath.totals(rows)
        assertEquals(s.periodPnl!!, t.periodPnl, 1e-6)
        assertEquals(s.periodPnl!! / (s.value - s.periodPnl!!) * 100, t.periodPct!!, 1e-9)
        assertEquals(54_100.0 + 73_440.0, t.value, 1e-6)
        assertEquals(62_400.0 + 60_496.0, t.invested, 1e-6)
    }

    @Test fun noPreviousCloseMeansNoMoveToday() {
        val r = PortfolioMath.rows(listOf(h("NEW", 5, 100.0, 110.0, null)), emptyMap(), PortfolioPeriod.D1, emptyMap()).single()
        assertNull(r.dayPnl)
        assertNull(r.periodPnl)
        assertNull(PortfolioMath.totals(listOf(r)).periodPct)
    }

    @Test fun weightsShareThePortfolio() {
        val rows = PortfolioMath.rows(listOf(suzlon, infy), emptyMap(), PortfolioPeriod.D1, emptyMap())
        assertEquals(100.0, rows.sumOf { it.weightPct }, 1e-9)
        assertEquals(54_100.0 / 127_540.0 * 100, rows.first().weightPct, 1e-9)
    }

    @Test fun rowsRankByRupeeMoveNotPercent() {
        // SUZLON's 5.0% fall costs ₹2,850; INFY's 1.2% fall costs ₹888.
        val rows = PortfolioMath.rows(listOf(infy, suzlon), emptyMap(), PortfolioPeriod.D1, emptyMap())
        assertEquals(listOf("SUZLON", "INFY"), PortfolioMath.order(rows, PortfolioMetric.PERIOD, HoldingSort.MOVE))
        assertEquals(listOf("INFY", "SUZLON"), PortfolioMath.order(rows, PortfolioMetric.TOTAL, HoldingSort.BEST))
        assertEquals(listOf("SUZLON", "INFY"), PortfolioMath.order(rows, PortfolioMetric.TOTAL, HoldingSort.WORST))
        assertEquals(listOf("INFY", "SUZLON"), PortfolioMath.order(rows, PortfolioMetric.PERIOD, HoldingSort.NAME))
    }

    @Test fun settledOrderIgnoresLaterTicksAndAppendsNewHoldings() {
        val ticked = PortfolioMath.rows(
            listOf(suzlon, infy, h("BEL", 250, 248.3, 402.15, 395.4)),
            mapOf("NSE_EQ|INFY" to LivePrice(1700.0, 1858.2)), PortfolioPeriod.D1, emptyMap()
        )
        assertEquals(listOf("SUZLON", "INFY", "BEL"), PortfolioMath.arrange(ticked, listOf("SUZLON", "INFY")).map { it.holding.symbol })
    }

    @Test fun allocationGroupsBySectorHoldingAndType() {
        val etf = h("NIFTYBEES", 400, 248.6, 284.35, 283.1, type = HoldingType.ETF)
        val rows = PortfolioMath.rows(listOf(suzlon, infy, etf), emptyMap(), PortfolioPeriod.D1, emptyMap())
        val bySector = PortfolioMath.allocation(rows, AllocationBy.SECTOR)
        assertEquals(listOf("Other", "IT", "Power"), bySector.map { it.name })
        assertEquals(100.0, bySector.sumOf { it.pct }, 1e-9)
        assertEquals(listOf("Stocks", "ETFs"), PortfolioMath.allocation(rows, AllocationBy.TYPE).map { it.name })
        assertEquals(listOf("NIFTYBEES", "INFY", "SUZLON"), PortfolioMath.allocation(rows, AllocationBy.HOLDING).map { it.name })
    }

    @Test fun valueSeriesCarriesEachHoldingsLastCloseForward() {
        fun c(t: Long, close: Double) = Candle(t, close, close, close, close, 0)
        val a = h("A", 10, 1.0, 5.0, null)
        val b = h("B", 2, 1.0, 9.0, null)
        val none = h("C", 1, 1.0, 7.0, null)
        val series = PortfolioMath.valueSeries(listOf(a, b, none), mapOf("A" to listOf(c(1, 4.0), c(3, 6.0)), "B" to listOf(c(2, 10.0))))
        assertEquals(listOf(1L to 67.0, 2L to 67.0, 3L to 87.0), series)
    }

    @Test fun windowKeepsThePeriodsDailyCandles() {
        val day = 86_400_000L
        val daily = (0..400).map { Candle(it * day, 1.0, 1.0, 1.0, 1.0, 0) }
        assertEquals(8, PortfolioMath.window(daily, PortfolioPeriod.W1).size)
        assertEquals(366, PortfolioMath.window(daily, PortfolioPeriod.Y1).size)
    }
}
