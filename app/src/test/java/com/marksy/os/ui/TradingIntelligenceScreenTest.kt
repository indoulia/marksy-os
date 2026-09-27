package com.marksy.os.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.assertCountEquals
import com.marksy.os.market.ActivePredictionDto
import com.marksy.os.market.ActivePredictionPageDto
import com.marksy.os.market.MarketApiClient
import com.marksy.os.market.MarketIntelligenceRepository
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TradingIntelligenceScreenTest {
    @get:Rule val compose = createComposeRule()

    private fun call(id: Int, target: Double, state: String = "ACTIVE") = ActivePredictionDto(
        predictionId = id, symbol = "NTPCGREEN", companyName = "NTPC Green", exchange = "NSE", price = 98.0, targetPrice = target, stopLoss = 90.0,
        horizon = 5, remainingTradingDays = 3, distanceToTargetPercent = null, distanceToStopLossPercent = null, confidence = 0.9, trustScore = null,
        trustQuality = null, status = "ACTIVE", lifecycleState = state, isActionableNow = true, lifecycleDetail = null, entryPrice = 97.0, compositeOpportunityScore = null
    )

    private fun repository(vararg calls: ActivePredictionDto) = MarketIntelligenceRepository(object : MarketApiClient {
        override suspend fun marketSummary() = throw NotImplementedError()
        override suspend fun liveQuotes(symbols: List<String>?) = throw NotImplementedError()
        override suspend fun liveFeedHealth() = throw NotImplementedError()
        override suspend fun indexHistory(name: String, range: String) = throw NotImplementedError()
        override suspend fun sectors() = throw NotImplementedError()
        override suspend fun instrument(symbol: String) = throw NotImplementedError()
        override suspend fun activePredictions(cursor: String?) = ActivePredictionPageDto(calls.toList(), null)
        override suspend fun activePrediction(id: Int) = throw NotImplementedError()
        override suspend fun ipos(stage: String?, query: String?) = throw NotImplementedError()
        override suspend fun ipoAttention(limit: Int) = throw NotImplementedError()
        override suspend fun ipoStageCounts() = throw NotImplementedError()
        override suspend fun ipoDetail(id: String) = throw NotImplementedError()
        override suspend fun ipoHistory(id: String) = throw NotImplementedError()
    })

    // Regression: two Marksy calls for the same symbol crashed the list ("Key pick-NTPCGREEN was already used").
    // Invalidated calls must not be offered as setups.
    @Test
    fun twoCallsForTheSameSymbolBothRenderAndInvalidatedOnesAreLeftOut() {
        val repo = repository(call(1, 100.0), call(2, 104.0), call(3, 106.0, state = "INVALIDATED"))

        compose.setContent { TradingIntelligenceScreen(emptyList(), PaddingValues(), marketRepository = repo) }
        compose.waitForIdle()

        compose.onAllNodesWithText("NTPCGREEN", substring = true).assertCountEquals(2)
    }
}
