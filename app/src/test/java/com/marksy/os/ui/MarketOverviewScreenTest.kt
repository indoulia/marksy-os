package com.marksy.os.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.marksy.os.market.MarketDataState
import com.marksy.os.market.MarketMoverDto
import com.marksy.os.market.MarketSummaryDto
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MarketOverviewScreenTest {
    @get:Rule val compose = createComposeRule()

    private fun summary(gainers: List<MarketMoverDto> = emptyList()) = MarketSummaryDto(
        asOf = "2026-09-24T09:43:21+05:30", marketStatus = "MARKET_HOURS", regime = "BULLISH_LOW_VOL",
        advanceDecline = null, volume = null, volatility = null,
        indexes = listOf(com.marksy.os.market.IndexQuoteDto("NIFTY 50", 25143.2, 0.42, 105.1)),
        sectorLeaders = emptyList(), sectorLaggards = emptyList(), topGainers = gainers, topLosers = emptyList()
    )

    @Test
    fun loadedStateShowsIndexAndMarketStatus() {
        compose.setContent { MarketOverviewScreen(state = MarketDataState.Loaded(summary()), padding = androidx.compose.foundation.layout.PaddingValues()) }

        compose.onNodeWithText("MARKET_HOURS").assertExists()
        compose.onNodeWithText("NIFTY 50").assertExists()
    }

    @Test
    fun unavailableStateShowsExplicitMessageNotZero() {
        compose.setContent { MarketOverviewScreen(state = MarketDataState.Unavailable, padding = androidx.compose.foundation.layout.PaddingValues()) }

        compose.onNodeWithText("Market Intelligence is not configured", substring = true).assertExists()
    }

    @Test
    fun errorStateShowsMessage() {
        compose.setContent { MarketOverviewScreen(state = MarketDataState.Error("HTTP 500"), padding = androidx.compose.foundation.layout.PaddingValues()) }

        compose.onNodeWithText("HTTP 500", substring = true).assertExists()
    }

    @Test
    fun loadingStateShowsChecking() {
        compose.setContent { MarketOverviewScreen(state = MarketDataState.Loading, padding = androidx.compose.foundation.layout.PaddingValues()) }

        compose.onNodeWithText("Checking market", substring = true).assertExists()
    }

    @Test
    fun degradedFeedHealthShowsStaleFooterNotLive() {
        val health = MarketDataState.Stale(
            com.marksy.os.market.LiveFeedHealthDto(upstoxEnabled = true, liveFeedEnabled = true, feedState = "DEGRADED", fallbackActive = true, cachedInstruments = 2888),
            ageSeconds = null
        )

        compose.setContent { MarketOverviewScreen(state = MarketDataState.Loaded(summary()), padding = androidx.compose.foundation.layout.PaddingValues(), health = health) }

        compose.onNodeWithText("degraded", substring = true).assertExists()
        compose.onNodeWithText("fallback", substring = true).assertExists()
    }
}
