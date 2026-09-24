package com.marksy.os.market

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeMarketApiClient(
    private val summary: MarketSummaryDto? = null,
    private val summaryError: Throwable? = null,
    private val liveQuotesResponse: LiveQuotesResponseDto? = null,
    private val predictionPage: ActivePredictionPageDto? = null,
    private val ipoList: List<IpoListItemDto> = emptyList(),
    private val health: LiveFeedHealthDto? = null
) : MarketApiClient {
    override suspend fun marketSummary(): MarketSummaryDto = summaryError?.let { throw it } ?: summary!!
    override suspend fun liveQuotes(symbols: List<String>?): LiveQuotesResponseDto = liveQuotesResponse!!
    override suspend fun liveFeedHealth(): LiveFeedHealthDto = health!!
    override suspend fun indexHistory(name: String, range: String): IndexHistoryDto = throw NotImplementedError()
    override suspend fun sectors(): List<SectorOptionDto> = emptyList()
    override suspend fun instrument(symbol: String): InstrumentLifecycleDto = throw NotImplementedError()
    override suspend fun activePredictions(cursor: String?): ActivePredictionPageDto = predictionPage!!
    override suspend fun activePrediction(id: Int): ActivePredictionDto = throw NotImplementedError()
    override suspend fun ipos(stage: String?, query: String?): List<IpoListItemDto> = ipoList
    override suspend fun ipoAttention(limit: Int): List<IpoAttentionItemDto> = emptyList()
    override suspend fun ipoStageCounts(): IpoStageCountsDto = throw NotImplementedError()
    override suspend fun ipoDetail(id: String): IpoDetailDto = throw NotImplementedError()
    override suspend fun ipoHistory(id: String): List<IpoHistoryEntryDto> = emptyList()
}

private fun summary(marketStatus: String = "MARKET_HOURS") = MarketSummaryDto(
    asOf = "2026-09-24T09:43:21+05:30", marketStatus = marketStatus, regime = null, advanceDecline = null,
    volume = null, volatility = null, indexes = emptyList(), sectorLeaders = emptyList(), sectorLaggards = emptyList(),
    topGainers = emptyList(), topLosers = emptyList()
)

class MarketIntelligenceRepositoryTest {
    @Test
    fun unconfiguredClientYieldsUnavailable() = runBlocking {
        val repository = MarketIntelligenceRepository(client = null)

        val state = repository.overview()

        assertTrue(state is MarketDataState.Unavailable)
    }

    @Test
    fun successfulFetchYieldsLoaded() = runBlocking {
        val repository = MarketIntelligenceRepository(FakeMarketApiClient(summary = summary()))

        val state = repository.overview()

        assertTrue(state is MarketDataState.Loaded)
        assertEquals("MARKET_HOURS", (state as MarketDataState.Loaded).value.marketStatus)
    }

    @Test
    fun thrownIOExceptionYieldsError() = runBlocking {
        val repository = MarketIntelligenceRepository(FakeMarketApiClient(summaryError = java.io.IOException("boom")))

        val state = repository.overview()

        assertTrue(state is MarketDataState.Error)
    }

    @Test
    fun marketApiExceptionYieldsError() = runBlocking {
        val repository = MarketIntelligenceRepository(FakeMarketApiClient(summaryError = MarketApiException("HTTP 422")))

        val state = repository.overview()

        assertTrue(state is MarketDataState.Error)
        assertTrue((state as MarketDataState.Error).message.contains("422"))
    }

    @Test
    fun jsonExceptionYieldsError() = runBlocking {
        val repository = MarketIntelligenceRepository(FakeMarketApiClient(summaryError = org.json.JSONException("malformed response")))

        val state = repository.overview()

        assertTrue(state is MarketDataState.Error)
    }

    @Test
    fun emptyPredictionPageYieldsEmpty() = runBlocking {
        val repository = MarketIntelligenceRepository(FakeMarketApiClient(predictionPage = ActivePredictionPageDto(items = emptyList(), nextCursor = null)))

        val state = repository.activePredictions()

        assertTrue(state is MarketDataState.Empty)
    }

    @Test
    fun nonEmptyPredictionPageYieldsLoaded() = runBlocking {
        val page = ActivePredictionPageDto(
            items = listOf(
                ActivePredictionDto(
                    predictionId = 1, symbol = "A", companyName = null, exchange = "NSE", price = null,
                    targetPrice = 10.0, stopLoss = 8.0, horizon = 1, remainingTradingDays = null,
                    distanceToTargetPercent = null, distanceToStopLossPercent = null, confidence = 0.5,
                    trustScore = null, trustQuality = null, status = "OPEN", lifecycleState = "WATCHING",
                    isActionableNow = false, lifecycleDetail = null, entryPrice = 9.0, compositeOpportunityScore = 0.5
                )
            ),
            nextCursor = "next"
        )
        val repository = MarketIntelligenceRepository(FakeMarketApiClient(predictionPage = page))

        val state = repository.activePredictions()

        assertTrue(state is MarketDataState.Loaded)
        assertEquals(1, (state as MarketDataState.Loaded).value.items.size)
    }

    @Test
    fun emptyIpoListYieldsEmpty() = runBlocking {
        val repository = MarketIntelligenceRepository(FakeMarketApiClient(ipoList = emptyList()))

        val state = repository.ipos(stage = "OPEN")

        assertTrue(state is MarketDataState.Empty)
    }

    @Test
    fun liveFeedHealthIsLoadedWhenStreamingAndNotFallenBack() = runBlocking {
        val health = LiveFeedHealthDto(upstoxEnabled = true, liveFeedEnabled = true, feedState = "STREAMING", fallbackActive = false, cachedInstruments = 2888)
        val repository = MarketIntelligenceRepository(FakeMarketApiClient(health = health))

        val state = repository.liveFeedHealth()

        assertTrue(state is MarketDataState.Loaded)
        assertEquals("STREAMING", (state as MarketDataState.Loaded).value.feedState)
    }

    @Test
    fun liveFeedHealthIsStaleWhenFeedNotStreaming() = runBlocking {
        val health = LiveFeedHealthDto(upstoxEnabled = true, liveFeedEnabled = true, feedState = "DEGRADED", fallbackActive = false, cachedInstruments = 2888)
        val repository = MarketIntelligenceRepository(FakeMarketApiClient(health = health))

        val state = repository.liveFeedHealth()

        assertTrue(state is MarketDataState.Stale)
    }

    @Test
    fun liveFeedHealthIsStaleWhenFallbackIsActiveEvenIfFeedStateSaysStreaming() = runBlocking {
        val health = LiveFeedHealthDto(upstoxEnabled = true, liveFeedEnabled = true, feedState = "STREAMING", fallbackActive = true, cachedInstruments = 2888)
        val repository = MarketIntelligenceRepository(FakeMarketApiClient(health = health))

        val state = repository.liveFeedHealth()

        assertTrue(state is MarketDataState.Stale)
    }

    @Test
    fun liveFeedHealthIsUnavailableWhenNotConfigured() = runBlocking {
        val repository = MarketIntelligenceRepository(client = null)

        val state = repository.liveFeedHealth()

        assertTrue(state is MarketDataState.Unavailable)
    }
}
