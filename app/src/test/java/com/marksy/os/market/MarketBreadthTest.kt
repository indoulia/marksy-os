package com.marksy.os.market

import org.junit.Assert.assertEquals
import org.junit.Test

class MarketBreadthTest {
    @Test
    fun countsAdvancesDeclinesAndUnchangedIgnoringMissingChanges() {
        val b = MarketBreadth.of(listOf(1.2, 0.3, -0.8, 0.0, 0.001, null, -2.0))

        assertEquals(MarketBreadth(advances = 2, declines = 2, unchanged = 2), b)
        assertEquals(6, b.total)
    }

    @Test
    fun mostActiveRanksByTradedValueNotVolume() {
        val active = MarketBreadth.mostActive(
            mapOf(
                "PENNY" to MarketBreadth.Traded(volume = 9_000_000, price = 10.0),    // ₹9 Cr
                "HEAVY" to MarketBreadth.Traded(volume = 100_000, price = 3_000.0),   // ₹30 Cr
                "MID" to MarketBreadth.Traded(volume = 1_000_000, price = 200.0),     // ₹20 Cr
                "NONE" to MarketBreadth.Traded(volume = null, price = 500.0)
            ),
            limit = 2
        )

        assertEquals(listOf("HEAVY", "MID"), active.map { it.first })
    }
}
