package com.marksy.os.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.assertCountEquals
import com.marksy.os.market.ActivePredictionDto
import com.marksy.os.market.ActivePredictionPageDto
import com.marksy.os.market.MarketApiClient
import com.marksy.os.market.MarketIntelligenceRepository
import com.marksy.os.market.LedgerTipDto
import com.marksy.os.market.MyTipDto
import com.marksy.os.market.MyTipPageDto
import org.junit.Assert.assertEquals
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

    private fun tip(symbol: String, status: String, outcome: String? = null) = MyTipDto(
        LedgerTipDto(
            tipId = symbol, symbol = symbol, direction = "BUY", entryLow = 100.0, entryHigh = 100.0, entryBasis = null, target = 110.0, stopLoss = null,
            horizonSessions = 5, horizonBasis = null, firstSeenAt = "2026-10-01T04:00:00Z", status = status, entryStatus = null, outcome = outcome, reason = null,
            enteredSession = null, closedSession = null, closedAt = null, exitPrice = null, promisedReturn = null, actualReturn = if (outcome == null) null else 0.1,
            predictionId = null, channel = null, caller = null, latestProgress = null
        ),
        null, null, emptyList(), 0
    )

    private val requested = mutableListOf<String?>()

    private fun repository(vararg calls: ActivePredictionDto) = MarketIntelligenceRepository(object : MarketApiClient {
        override suspend fun myTips(status: String?, cursor: String?): MyTipPageDto {
            requested += status
            return MyTipPageDto(listOf(if (status == "ACTIVE") tip("HAL", "ACTIVE") else tip("TCS", "TARGET_HIT", "SUCCESS")), null)
        }
        override suspend fun marketSummary() = throw NotImplementedError()
        override suspend fun liveQuotes(symbols: List<String>?) = throw NotImplementedError()
        override suspend fun liveFeedHealth() = throw NotImplementedError()
        override suspend fun indexHistory(name: String, range: String) = throw NotImplementedError()
        override suspend fun sectors() = throw NotImplementedError()
        override suspend fun instrument(symbol: String, includeCalls: Boolean) = throw NotImplementedError()
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

        compose.setContent { TradingIntelligenceScreen(PaddingValues(), selectedFilter = "Setups", marketRepository = repo) }
        compose.waitForIdle()

        compose.onAllNodesWithText("NTPCGREEN", substring = true).assertCountEquals(2)
    }

    // Open is the default and asks the ledger only for active tips; History's default lists the closed ones with their outcome.
    @Test
    fun openShowsActiveTipsAndHistoryShowsClosedOnes() {
        val repo = repository()
        val view = mutableStateOf(TradingFilters.first())
        compose.setContent { TradingIntelligenceScreen(PaddingValues(), selectedFilter = view.value, marketRepository = repo) }
        compose.waitForIdle()
        compose.onNodeWithText("HAL").assertIsDisplayed()
        compose.onAllNodesWithText("TCS").assertCountEquals(0)

        view.value = "History"
        compose.waitForIdle()
        compose.onNodeWithText("Target hit").assertIsDisplayed()
        compose.onAllNodesWithText("HAL").assertCountEquals(0)
        assertEquals(listOf("ACTIVE", "CLOSED"), requested)
    }

    @Test
    fun askAndLegacyArgsOpenTheRightView() {
        assertEquals(listOf("Open", "Setups", "History"), TradingFilters)
        assertEquals("History" to HistoryScope.MARKSY, tradingRoute(TradingMarksyResults))
        assertEquals("Setups" to null, tradingRoute("Setups"))
        assertEquals("Open" to null, tradingRoute(null))
        assertEquals("History · Marksy closed", tradingTitleNote("History", null, historyScope = HistoryScope.MARKSY))
        assertEquals("Open · Following", tradingTitleNote("Open", null, tipsStatus = MyTipsStatus.FOLLOWING))
    }
}
