package com.marksy.os.portfolio

import org.junit.Assert.assertEquals
import org.junit.Test

class PortfolioViewTest {
    private val snapshot = HoldingsSnapshot("upstox", listOf(Holding("A", "A", "", "k", HoldingType.STOCK, 1, 1.0, 1.0, 1.0)), 0)

    @Test fun aFailedFirstLoadIsNotShownAsNotConnected() {
        assertEquals(PortfolioView.LOAD_FAILED, PortfolioView.of(null, PortfolioConnection.SIGNED_IN, loading = false, error = "timeout"))
        assertEquals(PortfolioView.CONNECT, PortfolioView.of(null, PortfolioConnection.NOT_CONNECTED, loading = false, error = null))
        assertEquals(PortfolioView.LOADING, PortfolioView.of(null, PortfolioConnection.SIGNED_IN, loading = true, error = null))
        assertEquals(PortfolioView.HOLDINGS, PortfolioView.of(snapshot, PortfolioConnection.SIGNED_OUT, loading = false, error = "timeout"))
        assertEquals(PortfolioView.EMPTY, PortfolioView.of(snapshot.copy(holdings = emptyList()), PortfolioConnection.SIGNED_IN, loading = false, error = null))
    }
}
