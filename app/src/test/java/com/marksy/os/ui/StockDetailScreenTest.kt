package com.marksy.os.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.marksy.os.market.*
import org.json.JSONObject
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StockDetailScreenTest {
    @get:Rule val compose = createComposeRule()

    private val record = ScorecardHeadlineDto(1, 0, 1, 0, 1, 0, 0, 0.0, 0.0, -0.03, null)
    private val noRecord = ScorecardHeadlineDto(0, 0, 0, 0, 0, 0, 0, null, null, null, null)

    private fun withdrawn() = LedgerTipDto(
        tipId = "t-1", symbol = "RELIANCE", direction = "BUY", entryLow = 1420.0, entryHigh = 1420.0, entryBasis = "STATED",
        target = 1470.0, stopLoss = 1390.0, horizonSessions = 5, horizonBasis = "ENGINE", firstSeenAt = "2026-09-20T09:15:00Z",
        status = "SOURCE_EXIT", entryStatus = "ENTERED", outcome = "FAILURE", reason = null, enteredSession = 1, closedSession = 2,
        closedAt = "2026-09-23T10:00:00Z", exitPrice = 1377.4, promisedReturn = 0.035211, actualReturn = -0.03, predictionId = 501,
        channel = ChannelRefDto(7, "Marksy", "MARKSY"), caller = CallerRefDto(1, "Prediction engine"), latestProgress = null
    )

    private fun calls(vararg tips: LedgerTipDto) = InstrumentCallsDto(
        "2026-09-30T10:00:00Z", 90,
        listOf(EngineCallsDto(1, "Prediction engine", 7, record, tips.toList()), EngineCallsDto(2, "Rating engine", 7, noRecord, emptyList())),
        emptyList()
    )

    private fun instrument(calls: InstrumentCallsDto?) = InstrumentLifecycleDto(
        symbol = "RELIANCE", companyName = "Reliance Industries", exchange = "NSE", sector = "Energy", isActive = true,
        market = InstrumentMarketDto(lastClosePrice = 1452.3, asOfSessionDate = "2026-09-23T18:30:00Z", freshnessState = "FRESH"),
        predictionCount = 1, openPredictionCount = 0,
        // The prediction's monitor event still says INVALIDATED; the page must believe the tip.
        predictions = listOf(InstrumentPredictionEntryDto.parse(JSONObject(
            """{"predictionId": 501, "lifecycleState": "INVALIDATED", "lifecycleDetail": "Invalidated by decay", "isTerminal": true, "outcomeStatus": "OPEN"}"""
        ))),
        calls = calls
    )

    @Test
    fun aWithdrawnLosingMarksyCallShowsAsAFailedExitNotAnInvalidation() {
        compose.setContent { StockDetailScreen(state = MarketDataState.Loaded(instrument(calls(withdrawn()))), padding = PaddingValues()) }

        compose.onNodeWithText("Past calls (1)").performClick()
        compose.onNodeWithText("Exited · Failed", substring = true).assertExists()
        compose.onNodeWithText("−3.00%", substring = true).assertExists()
        compose.onNodeWithText("Invalidated", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Rating engine").assertExists()
    }

    @Test
    fun aStockWithoutAnyCallShowsNoCallsBox() {
        compose.setContent { StockDetailScreen(state = MarketDataState.Loaded(instrument(calls())), padding = PaddingValues()) }

        compose.onNodeWithText("Reliance Industries").assertExists()
        compose.onNodeWithText("MARKSY").assertDoesNotExist()
    }

    @Test
    fun unconfiguredMarksyIsHidden() {
        compose.setContent { StockDetailScreen(state = MarketDataState.Unavailable, padding = PaddingValues()) }

        compose.onNodeWithText("not configured", substring = true).assertDoesNotExist()
        compose.onNodeWithText("MARKSY").assertDoesNotExist()
    }
}
