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
    private val health: LiveFeedHealthDto? = null,
    private val ledgerError: Throwable? = null,
    private val followList: FollowListDto = FollowListDto(emptyList(), 200),
    private val followError: Throwable? = null,
    private val canonical: (FollowKey) -> FollowKey = { it }
) : MarketApiClient {
    val seenWhileSaving = mutableListOf<Set<FollowKey>?>()
    override suspend fun marketSummary(): MarketSummaryDto = summaryError?.let { throw it } ?: summary!!
    override suspend fun liveQuotes(symbols: List<String>?): LiveQuotesResponseDto = liveQuotesResponse!!
    override suspend fun liveFeedHealth(): LiveFeedHealthDto = health!!
    override suspend fun indexHistory(name: String, range: String): IndexHistoryDto = throw NotImplementedError()
    override suspend fun sectors(): List<SectorOptionDto> = emptyList()
    override suspend fun instrument(symbol: String, includeCalls: Boolean): InstrumentLifecycleDto = throw NotImplementedError()
    override suspend fun activePredictions(cursor: String?): ActivePredictionPageDto = predictionPage!!
    override suspend fun activePrediction(id: Int): ActivePredictionDto = throw NotImplementedError()
    override suspend fun ipos(stage: String?, query: String?): List<IpoListItemDto> = ipoList
    override suspend fun ipoAttention(limit: Int): List<IpoAttentionItemDto> = emptyList()
    override suspend fun ipoStageCounts(): IpoStageCountsDto = throw NotImplementedError()
    override suspend fun ipoDetail(id: String): IpoDetailDto = throw NotImplementedError()
    override suspend fun ipoHistory(id: String): List<IpoHistoryEntryDto> = emptyList()
    override suspend fun myTips(status: String?, cursor: String?): MyTipPageDto = ledgerError?.let { throw it } ?: MyTipPageDto(emptyList(), null)
    override suspend fun myScorecard(filter: String): ScorecardDto = throw ledgerError ?: NotImplementedError()
    override suspend fun tipDetail(tipId: String): TipDetailDto = throw ledgerError ?: NotImplementedError()
    var followReads = 0
    override suspend fun follows(): FollowListDto = followList.also { followReads++ }
    override suspend fun follow(key: FollowKey): FollowDto {
        seenWhileSaving += MarketIntelligenceRepository.followed.value
        followError?.let { throw it }
        return followOf(canonical(key))
    }
    override suspend fun unfollow(key: FollowKey): FollowListDto {
        seenWhileSaving += MarketIntelligenceRepository.followed.value
        followError?.let { throw it }
        return FollowListDto(followList.items.filter { it.key != canonical(key) }, 200)
    }
}

private val noRecord = ScorecardHeadlineDto(0, 0, 0, 0, 0, 0, 0, null, null, null, null)

private fun followOf(key: FollowKey) = FollowDto(key, "Entity ${key.id}", null, null, null, false, "2026-09-30T04:00:00Z", noRecord)

private fun summary(marketStatus: String = "MARKET_HOURS") = MarketSummaryDto(
    asOf = "2026-09-24T09:43:21+05:30", marketStatus = marketStatus, regime = null, advanceDecline = null,
    volume = null, volatility = null, indexes = emptyList(), sectorLeaders = emptyList(), sectorLaggards = emptyList(),
    topGainers = emptyList(), topLosers = emptyList()
)

class MarketIntelligenceRepositoryTest {
    @Test
    fun emptyPredictionPageStillPublishesItsLatestScan() = runBlocking {
        val scan = LatestScanDto("2026-09-28", "2026-09-28T10:47:00Z", "PROVISIONAL", null)
        val repository = MarketIntelligenceRepository(FakeMarketApiClient(predictionPage = ActivePredictionPageDto(emptyList(), null, scan)))

        val state = repository.activePredictions()

        assertTrue(state is MarketDataState.Empty)
        assertEquals(scan, MarketIntelligenceRepository.latestScan.value)
    }

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

    // 4b review M1: ledger screens show copy for 401, 403 and 404, and a signed-out reader gets the sign-in state, never raw HTTP.
    @Test
    fun ledgerHttpErrorsBecomeCopy() = runBlocking {
        fun repo(message: String, status: Int) = MarketIntelligenceRepository(FakeMarketApiClient(ledgerError = MarketApiException(message, status)))
        assertEquals(MarketDataState.Unavailable, repo("Marksy Market API returned HTTP 401: expired", 401).myTips(null))
        assertEquals(MarketDataState.Unavailable, repo("Not signed in to Marksy", 401).tipDetail("t1"))
        assertEquals(MarketDataState.Error("Your account doesn't have access to tip records yet"), repo("Marksy Market API returned HTTP 403", 403).tipDetail("t1"))
        assertEquals(MarketDataState.Error("Not available yet"), repo("Marksy Market API returned HTTP 404", 404).myScorecard(ScorecardQuery()))
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

    // Follow state is the server's: optimistic while saving, then whatever the server answered.
    @Test
    fun aFollowSettlesOnTheServersCanonicalKeyAndAnUnfollowOnItsList() = runBlocking {
        val client = FakeMarketApiClient(
            followList = FollowListDto(listOf(followOf(FollowKey.channel(3)), followOf(FollowKey.channel(1))), 200),
            canonical = { if (it == FollowKey.channel(7)) FollowKey.channel(1) else it }
        )
        val repository = MarketIntelligenceRepository(client)

        repository.follows()
        val followed = repository.setFollowing(FollowKey.channel(7), follow = true)
        val unfollowed = repository.setFollowing(FollowKey.channel(3), follow = false)

        assertEquals(setOf(FollowKey.channel(3), FollowKey.channel(1), FollowKey.channel(7)), client.seenWhileSaving.first())
        assertEquals(MarketDataState.Loaded(setOf(FollowKey.channel(3), FollowKey.channel(1))), followed)
        assertEquals(MarketDataState.Loaded(setOf(FollowKey.channel(1))), unfollowed)
        assertEquals(setOf(FollowKey.channel(1)), MarketIntelligenceRepository.followed.value)
    }

    @Test
    fun aRefusedFollowRevertsOnlyItsOwnKeyAndIsWordedLikeTheLedger() = runBlocking {
        fun repo(status: Int) = MarketIntelligenceRepository(FakeMarketApiClient(
            followList = FollowListDto(listOf(followOf(FollowKey.caller(9))), 200),
            followError = MarketApiException("Marksy Market API returned HTTP $status", status)
        ))
        val forbidden = repo(403).also { it.follows() }.setFollowing(FollowKey.channel(3), follow = true)

        assertEquals(MarketDataState.Error("Your account doesn't have access to tip records yet"), forbidden)
        assertEquals(setOf(FollowKey.caller(9)), MarketIntelligenceRepository.followed.value)
        assertEquals(MarketDataState.Unavailable, repo(401).also { it.follows() }.setFollowing(FollowKey.caller(9), follow = false))
        assertEquals(setOf(FollowKey.caller(9)), MarketIntelligenceRepository.followed.value)
    }

    @Test
    fun theFollowLimitIsWordedAndAnUnknownSetIsReReadBeforeToggling() = runBlocking {
        val client = FakeMarketApiClient(
            followList = FollowListDto(listOf(followOf(FollowKey.caller(9))), 200),
            followError = MarketApiException("Marksy Market API returned HTTP 422", 422)
        )
        val repository = MarketIntelligenceRepository(client)
        MarketIntelligenceRepository.forgetFollows()

        val refused = repository.setFollowing(FollowKey.channel(3), follow = true)

        assertEquals(MarketDataState.Error(Follows.LIMIT_TEXT), refused)
        assertEquals(1, client.followReads)
        assertEquals(setOf(FollowKey.caller(9)), client.seenWhileSaving.single()!! - FollowKey.channel(3))
        assertEquals(setOf(FollowKey.caller(9)), MarketIntelligenceRepository.followed.value)
        assertEquals("You can follow up to 200 sources", Follows.failureText(refused, follow = true, name = "Upstox"))
        assertEquals("Couldn't unfollow Upstox", Follows.failureText(MarketDataState.Error("x"), follow = false, name = "Upstox"))
    }
}
