package com.marksy.os.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.assertCountEquals
import com.marksy.os.gateway.MarketSnapshot
import com.marksy.os.gateway.MarketState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TradingIntelligenceScreenTest {
    @get:Rule val compose = createComposeRule()

    private fun pick(target: Double) = MarketSnapshot.MarketOpportunity(
        symbol = "NTPCGREEN", name = "NTPC Green", price = 98.0, changePct = null, entryPrice = 97.0,
        targetPrice = target, stopLoss = 90.0, upsidePct = null, horizonDays = 5, confidence = 0.9, score = null, status = "ACTIVE"
    )

    // Regression: two Marksy picks for the same symbol crashed the list ("Key pick-NTPCGREEN was already used").
    @Test
    fun twoPicksForTheSameSymbolBothRenderWithoutCrashing() {
        val snapshot = MarketSnapshot(
            marketStatus = "MARKET_HOURS", indices = emptyList(),
            opportunities = listOf(pick(100.0), pick(104.0)),
            gainers = emptyList(), losers = emptyList(), asOf = null
        )

        compose.setContent { TradingIntelligenceScreen(emptyList(), PaddingValues(), MarketState.Loaded(snapshot, 0L)) }

        compose.onAllNodesWithText("NTPCGREEN", substring = true).assertCountEquals(2)
    }
}
