package com.marksy.os.portfolio

import com.marksy.os.alerts.PriceAlert
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class PortfolioFlagsTest {
    private fun row(symbol: String = "SUZLON", price: Double = 100.0, avg: Double = 100.0, prev: Double? = 100.0, weight: Double = 5.0, dayPnl: Double? = null) = HoldingRow(
        Holding(symbol, symbol, "", "NSE_EQ|$symbol", HoldingType.STOCK, 10, avg, price, prev), price, price * 10, avg * 10, weight,
        dayPnl ?: prev?.let { 10 * (price - it) }, prev?.let { (price / it - 1) * 100 }, null, null, (price - avg) * 10, (price / avg - 1) * 100
    )
    private fun alert(price: Double, symbol: String = "SUZLON") = PriceAlert(price.toLong(), symbol, price, above = price > 100, createdAt = 0)
    private fun kinds(r: HoldingRow, alerts: List<PriceAlert> = emptyList()) = PortfolioFlags.flags(r, alerts).map { it.kind }

    @Test fun quietHoldingHasNoFlags() {
        assertEquals(emptyList<FlagKind>(), kinds(row(price = 103.9, prev = 100.0, avg = 115.0)))
    }

    @Test fun aDayMoveOfFourPercentEitherWayIsFlagged() {
        val fell = PortfolioFlags.flags(row(price = 95.9, prev = 100.0, avg = 95.9), emptyList()).single()
        assertEquals(FlagKind.DAY_MOVE to FlagSeverity.CRITICAL, fell.kind to fell.severity)
        assertEquals("Fell 4.1% today", fell.reason)
        val rose = PortfolioFlags.flags(row(price = 104.1, prev = 100.0, avg = 104.1), emptyList()).single()
        assertEquals(FlagSeverity.POSITIVE, rose.severity)
        assertEquals("Rose 4.1% today", rose.reason)
        assertEquals(emptyList<FlagKind>(), kinds(row(price = 96.1, prev = 100.0, avg = 96.1)))
    }

    @Test fun fifteenPercentBelowAverageIsFlagged() {
        assertEquals("15.1% below your average", PortfolioFlags.flags(row(price = 84.9, prev = 84.9, avg = 100.0), emptyList()).single().reason)
        assertEquals(listOf(FlagKind.BELOW_AVERAGE), kinds(row(price = 85.0, prev = 85.0, avg = 100.0)))
        assertEquals(emptyList<FlagKind>(), kinds(row(price = 85.1, prev = 85.1, avg = 100.0)))
    }

    @Test fun withinFourPercentOfAnAlertEitherSideIsFlagged() {
        assertEquals(listOf(FlagKind.NEAR_ALERT), kinds(row(), listOf(alert(96.1))))
        assertEquals(listOf(FlagKind.NEAR_ALERT), kinds(row(), listOf(alert(103.9))))
        assertEquals(emptyList<FlagKind>(), kinds(row(), listOf(alert(95.9), alert(103.9, symbol = "INFY"))))
        assertEquals("2.0% from your ₹98.00 alert", PortfolioFlags.flags(row(), listOf(alert(96.5), alert(98.0))).single().reason)
    }

    @Test fun twentyPercentOfThePortfolioIsFlagged() {
        assertEquals("20% of your portfolio", PortfolioFlags.flags(row(weight = 20.0), emptyList()).single().reason)
        assertEquals(emptyList<FlagKind>(), kinds(row(weight = 19.9)))
    }

    @Test fun lanePutsCriticalFirstThenTheBiggestRupeeMoveAndSkipsHidden() {
        val conc = row("HDFCBANK", weight = 22.0, dayPnl = 2_196.0)
        val fell = row("SUZLON", price = 95.0, prev = 100.0, avg = 95.0, dayPnl = -2_850.0)
        val below = row("TATAMOTORS", price = 84.0, prev = 85.0, avg = 100.0, dayPnl = -2_070.0)
        val quiet = row("INFY")
        assertEquals(listOf("SUZLON", "TATAMOTORS", "HDFCBANK"), PortfolioFlags.needsALook(listOf(conc, quiet, below, fell), emptyList(), emptySet()).map { it.row.holding.symbol })
        assertEquals(listOf("TATAMOTORS", "HDFCBANK"), PortfolioFlags.needsALook(listOf(conc, below, fell), emptyList(), setOf("SUZLON")).map { it.row.holding.symbol })
    }

    @Test fun primaryReasonIsTheMostSevere() {
        val r = row(price = 104.5, prev = 100.0, avg = 104.5)
        assertEquals(FlagKind.NEAR_ALERT, PortfolioFlags.needsALook(listOf(r), listOf(alert(106.0)), emptySet()).single().primary.kind)
    }

    @Test fun hideTodayLastsUntilTheNextOpen() {
        fun ist(at: String) = LocalDateTime.parse(at).atZone(ZoneId.of("Asia/Kolkata")).toInstant().toEpochMilli()
        assertEquals(ist("2026-10-03T09:15"), PortfolioFlags.hiddenUntil(ist("2026-10-02T11:00")))
        assertEquals(ist("2026-10-02T09:15"), PortfolioFlags.hiddenUntil(ist("2026-10-02T08:00")))
    }

    @Test fun exactThresholdsFlagDespiteFloatingPoint() {
        // Each case sits exactly on its threshold in rupees and paise, but its double lands a hair inside.
        fun rows(vararg h: Holding) = PortfolioMath.rows(h.toList(), emptyMap(), PortfolioPeriod.D1, emptyMap())
        val big = Holding("BIG", "BIG", "", "k0", HoldingType.STOCK, 1, 1_000_000.0, 1_000_000.0, 1_000_000.0)
        assertEquals(listOf(FlagKind.BELOW_AVERAGE), kinds(rows(Holding("A", "A", "", "k1", HoldingType.STOCK, 1, 13.0, 11.05, 11.05), big).first()))
        assertEquals(listOf(FlagKind.DAY_MOVE), kinds(rows(Holding("B", "B", "", "k2", HoldingType.STOCK, 1, 10.56, 10.56, 11.0), big).first()))
        val near = rows(Holding("SUZLON", "S", "", "k3", HoldingType.STOCK, 1, 10.0, 10.0, 10.0), big).first()
        assertEquals(listOf(FlagKind.NEAR_ALERT), kinds(near, listOf(PriceAlert(1, "SUZLON", 10.40, true, 0))))
        val heavy = rows(Holding("C", "C", "", "k4", HoldingType.STOCK, 3, 0.70, 0.70, 0.70), Holding("D", "D", "", "k5", HoldingType.STOCK, 1, 8.40, 8.40, 8.40))
        assertEquals(listOf(FlagKind.CONCENTRATION), kinds(heavy.first()))
    }
}
