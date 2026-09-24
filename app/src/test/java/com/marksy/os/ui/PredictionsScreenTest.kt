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

private class FixturePredictionsClient(private val page: ActivePredictionPageDto) : MarketApiClient {
    override suspend fun marketSummary() = throw NotImplementedError()
    override suspend fun liveQuotes(symbols: List<String>?) = throw NotImplementedError()
    override suspend fun liveFeedHealth() = throw NotImplementedError()
    override suspend fun indexHistory(name: String, range: String) = throw NotImplementedError()
    override suspend fun sectors() = emptyList<SectorOptionDto>()
    override suspend fun instrument(symbol: String) = throw NotImplementedError()
    override suspend fun activePredictions(cursor: String?) = page
    override suspend fun activePrediction(id: Int) = throw NotImplementedError()
    override suspend fun ipos(stage: String?, query: String?) = emptyList<IpoListItemDto>()
    override suspend fun ipoAttention(limit: Int) = emptyList<IpoAttentionItemDto>()
    override suspend fun ipoStageCounts() = throw NotImplementedError()
    override suspend fun ipoDetail(id: String) = throw NotImplementedError()
    override suspend fun ipoHistory(id: String) = emptyList<IpoHistoryEntryDto>()
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PredictionsScreenTest {
    @get:Rule val compose = createComposeRule()

    private fun prediction(symbol: String) = ActivePredictionDto(
        predictionId = 1, symbol = symbol, companyName = "$symbol Ltd", exchange = "NSE", price = 100.0,
        targetPrice = 110.0, stopLoss = 90.0, horizon = 5, remainingTradingDays = 3,
        distanceToTargetPercent = 10.0, distanceToStopLossPercent = -10.0, confidence = 0.7,
        trustScore = 0.6, trustQuality = "MEDIUM", status = "OPEN", lifecycleState = "ACTIVE",
        isActionableNow = true, lifecycleDetail = null, entryPrice = 95.0, compositeOpportunityScore = 0.6
    )

    @Test
    fun listedPredictionsAreTappableToOpenSymbol() {
        val repository = MarketIntelligenceRepository(FixturePredictionsClient(ActivePredictionPageDto(listOf(prediction("RELIANCE")), null)))
        var opened: String? = null

        compose.setContent { PredictionsScreen(repository = repository, padding = PaddingValues(), onOpenSymbol = { opened = it }) }
        compose.waitForIdle()
        compose.onNodeWithText("RELIANCE").performClick()

        assert(opened == "RELIANCE")
    }

    @Test
    fun emptyPredictionsShowsEmptyState() {
        val repository = MarketIntelligenceRepository(FixturePredictionsClient(ActivePredictionPageDto(emptyList(), null)))

        compose.setContent { PredictionsScreen(repository = repository, padding = PaddingValues(), onOpenSymbol = {}) }
        compose.waitForIdle()

        compose.onNodeWithText("No active predictions", substring = true).assertExists()
    }
}
