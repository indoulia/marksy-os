package com.marksy.os.portfolio

import com.marksy.os.upstox.UpstoxAuthException
import com.marksy.os.upstox.UpstoxHolding
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class UpstoxHoldingsSourceTest {
    private fun u(symbol: String, qty: Long = 10, name: String = "$symbol LTD", ltp: Double = 100.0, close: Double? = 98.0) =
        UpstoxHolding("INE000000001", name, symbol, "NSE_EQ|INE000000001", "NSE", qty, 90.0, ltp, close)

    @Test fun mapsUpstoxHoldingsWithTodaysTokenAndDropsEmptyOnes(): Unit = runBlocking {
        var sent: String? = null
        val source = UpstoxHoldingsSource({ "token" }) { t -> sent = t; listOf(u("suzlon"), u("BEL", qty = 0)) }
        val ok = source.fetch(1_000L) as HoldingsResult.Ok
        assertEquals("token", sent)
        assertEquals(
            HoldingsSnapshot("upstox", listOf(Holding("SUZLON", "suzlon LTD", "INE000000001", "NSE_EQ|INE000000001", HoldingType.STOCK, 10, 90.0, 100.0, 98.0)), 1_000L),
            ok.snapshot
        )
    }

    @Test fun etfsAreToldApartBySymbolOrName() {
        assertEquals(HoldingType.ETF, UpstoxHoldingsSource.toHolding(u("NIFTYBEES"))!!.type)
        assertEquals(HoldingType.ETF, UpstoxHoldingsSource.toHolding(u("MON100", name = "MOTILAL OSWAL NASDAQ 100 ETF"))!!.type)
        assertEquals(HoldingType.ETF, UpstoxHoldingsSource.toHolding(u("SETFNIF50", name = "SBI-ETF NIFTY 50"))!!.type)
        assertEquals(HoldingType.STOCK, UpstoxHoldingsSource.toHolding(u("HDFCBANK", name = "HDFC BANK LTD"))!!.type)
    }

    @Test fun missingLastPriceFallsBackToCloseThenAverage() {
        assertEquals(98.0, UpstoxHoldingsSource.toHolding(u("A", ltp = 0.0))!!.lastPrice, 0.0)
        assertEquals(90.0, UpstoxHoldingsSource.toHolding(u("A", ltp = 0.0, close = null))!!.lastPrice, 0.0)
    }

    @Test fun signInStateDecidesTheResult(): Unit = runBlocking {
        assertTrue(UpstoxHoldingsSource({ null }) { error("must not call Upstox") }.fetch(0) is HoldingsResult.SignedOut)
        assertTrue(UpstoxHoldingsSource({ "t" }) { throw UpstoxAuthException("401") }.fetch(0) is HoldingsResult.SignedOut)
        assertEquals(HoldingsResult.Failed("timeout"), UpstoxHoldingsSource({ "t" }) { throw IOException("timeout") }.fetch(0))
    }

    @Test fun upstoxIsTheOnlyLiveProvider() {
        val catalog = PortfolioProviders.catalog(UpstoxHoldingsSource({ null }) { emptyList() })
        assertEquals(listOf("upstox", "kite", "groww", "cdsl_nsdl"), catalog.map { it.id })
        assertEquals(listOf(true, false, false, false), catalog.map { it.available })
    }

    @Test fun snapshotSurvivesItsJson() {
        val s = HoldingsSnapshot(
            "upstox",
            listOf(
                Holding("SUZLON", "Suzlon Energy", "INE040H01021", "NSE_EQ|INE040H01021", HoldingType.STOCK, 1000, 62.4, 54.1, 56.95, "Power"),
                Holding("NIFTYBEES", "Nippon Nifty BeES", "INF204KB14I2", "NSE_EQ|INF204KB14I2", HoldingType.ETF, 400, 248.6, 284.35, null)
            ),
            5L
        )
        assertEquals(s, HoldingsSnapshot.fromJson(s.toJson()))
        assertNull(HoldingsSnapshot.fromJson("garbage"))
    }
}
