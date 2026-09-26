package com.marksy.os.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.marksy.os.market.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StockDetailScreenTest {
    @get:Rule val compose = createComposeRule()

    private fun instrument() = InstrumentLifecycleDto(
        symbol = "RELIANCE", companyName = "Reliance Industries", exchange = "NSE", sector = "Energy", isActive = true,
        market = InstrumentMarketDto(lastClosePrice = 1452.3, asOfSessionDate = "2026-09-23T18:30:00Z", freshnessState = "FRESH"),
        predictionCount = 1, openPredictionCount = 1,
        predictions = listOf(
            InstrumentPredictionEntryDto(
                predictionId = 501, asOf = "2026-09-20T09:15:00Z", horizonDays = 5, entryPrice = 1420.0,
                targetPrice = 1470.0, stopLoss = 1390.0, probabilityAtPublication = 0.71, confidenceAtPublication = 0.8,
                lifecycleState = "ACTIVE", lifecycleDetail = "Tracking toward target", isTerminal = false,
                currentPrice = 1452.3, currentReturn = 2.27, targetProgress = 0.64, stopProgress = 0.0,
                outcomeStatus = "PENDING", realizedReturnPct = null, hasResolvedOutcome = false, evidenceItemCount = 4
            )
        )
    )

    @Test
    fun openCallLeadsThePageUnderThePrice() {
        compose.setContent { StockDetailScreen(state = MarketDataState.Loaded(instrument()), padding = PaddingValues()) }

        compose.onNodeWithText("Reliance Industries").assertExists()
        compose.onNodeWithText("MARKSY CALL").assertExists()
        compose.onNodeWithText("Tracking toward target").assertExists()
    }

    @Test
    fun onlyPastCallsSayThereIsNoActiveCall() {
        val closed = instrument().predictions.single().copy(isTerminal = true, outcomeStatus = "TARGET_HIT", realizedReturnPct = 3.5)
        compose.setContent { StockDetailScreen(state = MarketDataState.Loaded(instrument().copy(predictions = listOf(closed))), padding = PaddingValues()) }

        compose.onNodeWithText("No active Marksy call").assertExists()
        compose.onNodeWithText("1 past call · 1 hit target", substring = true).assertExists()
    }

    @Test
    fun noMarksyDataShowsNoMarksySection() {
        val noPredictions = instrument().copy(predictions = emptyList(), predictionCount = 0, openPredictionCount = 0)
        compose.setContent { StockDetailScreen(state = MarketDataState.Loaded(noPredictions), padding = PaddingValues()) }
        compose.onNodeWithText("MARKSY CALL").assertDoesNotExist()
        compose.onNodeWithText("No active Marksy call").assertDoesNotExist()
    }

    @Test
    fun unconfiguredMarksyIsHidden() {
        compose.setContent { StockDetailScreen(state = MarketDataState.Unavailable, padding = PaddingValues()) }

        compose.onNodeWithText("not configured", substring = true).assertDoesNotExist()
        compose.onNodeWithText("MARKSY CALL").assertDoesNotExist()
    }
}
