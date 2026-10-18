package com.marksy.os.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.assertCountEquals
import com.marksy.os.market.ActivePredictionDto
import com.marksy.os.market.ActivePredictionPageDto
import com.marksy.os.market.ChannelRefDto
import com.marksy.os.market.ClosedPredictionDto
import com.marksy.os.market.ClosedPredictionPageDto
import com.marksy.os.market.ScorecardHeadlineDto
import com.marksy.os.market.MarketApiClient
import com.marksy.os.market.MarketIntelligenceRepository
import com.marksy.os.market.LedgerTipDto
import com.marksy.os.market.MyTipDto
import com.marksy.os.market.MyTipPageDto
import com.marksy.os.market.EntityScorecardDto
import com.marksy.os.market.EntityScorecardListDto
import com.marksy.os.market.PerformanceSummaryDto
import com.marksy.os.market.ScorecardBodyDto
import com.marksy.os.market.ScorecardCountsDto
import com.marksy.os.market.ScorecardFilterEchoDto
import com.marksy.os.market.ScorecardPerformanceDto
import com.marksy.os.market.ScorecardTrustDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.CompletableDeferred
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
            predictionId = null, channel = ChannelRefDto(1, "5paisa", "BROKER_APP"), caller = null, latestProgress = null
        ),
        ScorecardHeadlineDto(14, 3, 11, 6, 4, 1, 1, 54.5, 57.0, 0.012, 62), null, emptyList(), 0
    )

    // The feed reads its lists concurrently on Dispatchers.IO, so a plain list can lose a write.
    private val requested = java.util.concurrent.CopyOnWriteArrayList<String?>()
    private var closedCalls: List<ClosedPredictionDto> = emptyList()

    private fun closedCall(id: Int, symbol: String, tipId: String?, status: String = "STOP_LOSS_HIT", reason: String? = null) = ClosedPredictionDto(
        id = id, symbol = symbol, asOf = "2026-09-30", horizonDays = 3, predictedReturn = 0.05, realizedReturn = -0.03, outcome = "FAILURE", excludedReason = null,
        ledger = tipId?.let {
            LedgerTipDto(
                tipId = it, symbol = symbol, direction = "BUY", entryLow = 100.0, entryHigh = 100.0, entryBasis = null, target = 105.0, stopLoss = 97.0,
                horizonSessions = 3, horizonBasis = null, firstSeenAt = "2026-09-30T07:06:44Z", status = status, entryStatus = null,
                outcome = if (status == "INVALIDATED") null else "FAILURE", reason = reason, enteredSession = null, closedSession = null, closedAt = null,
                exitPrice = null, promisedReturn = null, actualReturn = if (status == "INVALIDATED") null else -0.044365, predictionId = id,
                channel = null, caller = null, latestProgress = null
            )
        }
    )
    // Repository reads run on Dispatchers.IO, which waitForIdle does not wait for.
    private fun awaitText(text: String) = compose.waitUntil(20_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    private val scorecardFilters = mutableListOf<String>()
    private var summaryReads = 0

    private fun engineCard(id: Int, name: String, invalidated: Int = 12, open: Int = 5) = EntityScorecardDto(
        "caller", id, name, 7, "Marksy",
        ScorecardBodyDto(
            ScorecardCountsDto(total = 55 + invalidated + open, open = open, successful = 20, failed = 25, expired = 10, completed = 55, exited = 4,
                invalidated = invalidated, unscorable = 0, dataUnresolved = 0),
            ScorecardPerformanceDto(44.4, 55.6, 36.4, -0.004, null, null, null, null, null),
            ScorecardTrustDto(40, 55, 10, null, null, invalidated)
        ),
        engine = true
    )

    private var closedPage: suspend (String?) -> ClosedPredictionPageDto = { ClosedPredictionPageDto(closedCalls, null) }

    private fun repository(vararg calls: ActivePredictionDto, laterPage: List<ActivePredictionDto>? = null) = MarketIntelligenceRepository(object : MarketApiClient {
        override suspend fun myTips(status: String?, cursor: String?): MyTipPageDto {
            requested += status
            return MyTipPageDto(listOf(if (status == "ACTIVE") tip("HAL", "ACTIVE") else tip("TCS", "TARGET_HIT", "SUCCESS")), null)
        }
        override suspend fun scorecards(entity: String, filter: String): EntityScorecardListDto {
            scorecardFilters += "$entity?$filter"
            return EntityScorecardListDto("v", entity, ScorecardFilterEchoDto("LAST_30_DAYS", null, null, "FIRST_SEEN", null, null, null, null, null, "2026-10-15T00:00:00Z"),
                listOf(engineCard(3, "Rating engine"), engineCard(2, "Prediction engine")))
        }
        override suspend fun performanceSummary(range: String): PerformanceSummaryDto? { summaryReads++; return null }
        override suspend fun closedPredictions(cursor: String?) = closedPage(cursor)
        override suspend fun marketSummary() = throw NotImplementedError()
        override suspend fun liveQuotes(symbols: List<String>?) = throw NotImplementedError()
        override suspend fun liveFeedHealth() = throw NotImplementedError()
        override suspend fun indexHistory(name: String, range: String) = throw NotImplementedError()
        override suspend fun sectors() = throw NotImplementedError()
        override suspend fun instrument(symbol: String, includeCalls: Boolean) = throw NotImplementedError()
        override suspend fun activePredictions(cursor: String?) =
            if (cursor == null) ActivePredictionPageDto(calls.toList(), laterPage?.let { "p2" }) else ActivePredictionPageDto(laterPage.orEmpty(), null)
        override suspend fun activePrediction(id: Int) = throw NotImplementedError()
        override suspend fun ipos(stage: String?, query: String?) = throw NotImplementedError()
        override suspend fun ipoAttention(limit: Int) = throw NotImplementedError()
        override suspend fun ipoStageCounts() = throw NotImplementedError()
        override suspend fun ipoDetail(id: String) = throw NotImplementedError()
        override suspend fun ipoHistory(id: String) = throw NotImplementedError()
    })

    // Regression: History's count read 19 while later pages were still loading, then jumped to 79.
    @Test
    fun aListSettlesOnlyOnceItsUpFrontPagesAreRead() = runBlocking {
        val lastPage = CompletableDeferred<Unit>()
        closedPage = { c ->
            if (c == null) ClosedPredictionPageDto(listOf(closedCall(1, "TCS", "t1")), "p2")
            else { lastPage.await(); ClosedPredictionPageDto(listOf(closedCall(2, "INFY", "t2")), null) }
        }
        val feed = TradingFeed(repository())
        val loading = launch { feed.load() }
        withTimeout(10_000) { while (feed.marksyClosed.items.isEmpty()) delay(10) }
        assertFalse(feed.marksyClosed.settled)
        lastPage.complete(Unit)
        loading.join()
        assertTrue(feed.marksyClosed.settled)
        assertEquals(2, feed.marksyClosed.items.size)
    }

    // Regression: two Marksy calls for the same symbol crashed the list ("Key pick-NTPCGREEN was already used").
    // Invalidated calls must not be offered as setups.
    @Test
    @Config(sdk = [34], qualifiers = "w411dp-h1600dp")
    fun twoCallsForTheSameSymbolBothRenderAndInvalidatedOnesAreLeftOut() {
        val repo = repository(call(1, 100.0), call(2, 104.0), call(3, 106.0, state = "INVALIDATED"))

        compose.setContent { TradingIntelligenceScreen(PaddingValues(), selectedFilter = "Setups", marketRepository = repo) }
        compose.waitForIdle()

        compose.onAllNodesWithText("NTPCGREEN", substring = true).assertCountEquals(2)
        // Every setup is still to buy, so the tab already says what "Waiting to buy" and "Marksy setup" would.
        compose.onAllNodesWithText("Waiting to buy").assertCountEquals(0)
        compose.onAllNodesWithText("Marksy setup", substring = true).assertCountEquals(0)
    }

    // Regression: the active feed is keyset-paged on a re-rankable score, so a later page can repeat a call; Ended crashed on the repeated key.
    @Test
    fun aCallRepeatedOnALaterPageRendersOnce() {
        val ended = call(1, 100.0, state = "DATA_UNRESOLVED")
        val repo = repository(ended, call(2, 104.0), laterPage = listOf(ended))

        compose.setContent { TradingIntelligenceScreen(PaddingValues(), selectedFilter = "History", marketRepository = repo, historyScope = HistoryScope.MARKSY) }
        // Both pages must have loaded, or a single row would pass without the repeat ever arriving.
        compose.waitUntil(20_000) { compose.onAllNodesWithText("Loading", substring = true).fetchSemanticsNodes().isEmpty() && compose.onAllNodesWithText("NTPCGREEN").fetchSemanticsNodes().isNotEmpty() }

        compose.onAllNodesWithText("NTPCGREEN", substring = true).assertCountEquals(1)
    }

    // Open is the default and lists active tips; History lists the closed ones with their outcome. Both feeds load up front for the counts.
    // A tip's Trust chip is its channel's Trust Score from the ledger's scorecard.
    @Test
    fun openShowsActiveTipsAndHistoryShowsClosedOnes() {
        val repo = repository()
        val view = mutableStateOf(TradingFilters.first())
        compose.setContent { TradingIntelligenceScreen(PaddingValues(), selectedFilter = view.value, marketRepository = repo) }
        awaitText("HAL")
        compose.onNodeWithText("HAL").assertIsDisplayed()
        compose.onNodeWithText("5paisa", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Trust 62 ›").assertIsDisplayed()
        compose.onAllNodesWithText("TCS").assertCountEquals(0)
        // Open lists live tips only, so "Active" on each card repeats the tab.
        compose.onAllNodesWithText("Active").assertCountEquals(0)

        view.value = "History"
        awaitText("Target hit")
        compose.onNodeWithText("Target hit").assertIsDisplayed()
        compose.onAllNodesWithText("HAL").assertCountEquals(0)
        assertEquals(listOf("ACTIVE", "CLOSED"), requested.sortedBy { it })
    }

    // The strip is the Prediction engine's ledger scorecard as the server sent it: completed tips count from their real entry.
    @Test
    fun marksyTrackRecordIsThePredictionEngineLedgerScorecard() {
        compose.setContent { TradingIntelligenceScreen(PaddingValues(), selectedFilter = "History", marketRepository = repository(), historyScope = HistoryScope.MARKSY) }
        awaitText("LAST 30 DAYS · 55 COMPLETED")

        compose.onNodeWithText("20 succeeded · 25 failed · 10 expired · 36.4% hit").assertIsDisplayed()
        assertEquals(listOf("caller?period=LAST_30_DAYS"), scorecardFilters)
        assertEquals(0, summaryReads)
    }

    @Test
    fun trackRecordPicksThePredictionEngineAndWordsItsRecord() {
        val prediction = engineCard(2, "Prediction engine", invalidated = 0, open = 0)
        assertEquals(prediction, predictionEngineCard(listOf(engineCard(3, "Rating engine"), prediction)))
        assertEquals(null, predictionEngineCard(listOf(engineCard(3, "Rating engine"))))
        val strip = TradingLists.historySummary(prediction.body)!!
        assertEquals("LAST 30 DAYS · 55 COMPLETED", strip.caption)
        assertEquals("−0.40%", strip.big)
        assertEquals(null, TradingLists.historySummary(null))
    }

    // Regression (device, 2026-10-15): Inbox's long filter list pushed Close and Add screenshot off screen, then scrolled Captured tips out of sight.
    @Test
    fun capturedFilterPanelLeavesItsButtonsOnScreen() {
        compose.setContent { CapturedInboxView(emptyList(), PaddingValues(), {}, {}, {}, {}, {}, false, {}, CaptureInbox()) }
        compose.onNodeWithContentDescription("Filters").performClick()
        compose.waitForIdle()

        compose.onNodeWithContentDescription("Close filters").assertIsDisplayed()
        compose.onNodeWithContentDescription("Add screenshot").assertIsDisplayed()
        compose.onNodeWithText("Captured tips").assertIsDisplayed()
    }

    // Prod 2026-10-15: KIRLOSBROS predictions 2003 and 2004 share ledger tip 46 (2004's own tip is its MERGED_DUPLICATE follower).
    @Test
    fun closedRowsFoldOnlyOnASharedLedgerTipAndKeepEveryPrediction() {
        val items = listOf(
            closedCall(1, "KIRLOSBROS", "46"), closedCall(2, "KIRLOSBROS", "46"),
            closedCall(3, "PGHL", "50"), closedCall(4, "ALBERTDAVD", "51"),
            closedCall(5, "WEWIN", "60", "INVALIDATED", "SOURCE_EXIT_BEFORE_ENTRY"),
            closedCall(6, "NOLEDGER", null), closedCall(7, "NOLEDGER", null)
        )
        val sections = closedSections(items)

        assertEquals(listOf(1 to 2, 3 to 1, 4 to 1, 6 to 1, 7 to 1), sections.shown.map { it.row.id to it.predictions })
        assertEquals(listOf(5), sections.invalidated.map { it.row.id })
        assertEquals(items.size, (sections.shown + sections.invalidated).sumOf { it.predictions })
    }

    @Test
    @Config(sdk = [34], qualifiers = "w411dp-h1600dp")
    fun invalidatedCallsFoldBelowTheScoredOnesAndOpenOnTap() {
        closedCalls = listOf(
            closedCall(1, "KIRLOSBROS", "46"), closedCall(2, "KIRLOSBROS", "46"), closedCall(3, "PGHL", "50"),
            closedCall(5, "WEWIN", "60", "INVALIDATED", "SOURCE_EXIT_BEFORE_ENTRY"), closedCall(8, "VISACHROME", "61", "INVALIDATED", "SOURCE_EXIT_BEFORE_ENTRY")
        )
        compose.setContent { TradingIntelligenceScreen(PaddingValues(), selectedFilter = "History", marketRepository = repository(), historyScope = HistoryScope.MARKSY) }
        awaitText("PGHL")

        compose.onAllNodesWithText("KIRLOSBROS").assertCountEquals(1)
        compose.onNodeWithText("2 predictions", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Voided before buy").assertIsDisplayed()
        compose.onNodeWithText("2 tips · voided before anyone could buy · not scored").assertIsDisplayed()
        compose.onAllNodesWithText("WEWIN").assertCountEquals(0)

        compose.onNodeWithText("Voided before buy").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("WEWIN").assertIsDisplayed()
        compose.onNodeWithText("VISACHROME").assertIsDisplayed()
        // Only the fold names the state; its cards don't repeat it.
        compose.onAllNodesWithText("Voided before buy").assertCountEquals(1)

        compose.onAllNodesWithText("Voided before buy")[0].performClick()
        compose.waitForIdle()
        compose.onAllNodesWithText("WEWIN").assertCountEquals(0)
    }

    // Following lists its own feed's tips: open in Open, closed in History with voided-before-buy folded apart; All stays mine + Marksy.
    @Test
    fun followingScopeListsTheFollowingFeedsTips() {
        val lists = TradingLists.build(
            listOf(tip("HAL", "ACTIVE")), emptyList(), emptyList(), emptyList(), null, emptyMap(), java.time.LocalDate.of(2026, 10, 16),
            followingOpen = listOf(tip("BEL", "ACTIVE")), followingClosed = listOf(tip("TCS", "TARGET_HIT", "SUCCESS"), tip("VOID", "INVALIDATED"))
        )

        assertEquals(listOf("BEL"), lists.open(OpenScope.FOLLOWING).map { it.symbol })
        assertEquals(listOf("HAL", "BEL"), lists.open(OpenScope.ALL).map { it.symbol })
        assertEquals(listOf("TCS"), lists.history(HistoryScope.FOLLOWING).map { it.symbol })
        assertEquals(listOf("VOID"), lists.voided(HistoryScope.FOLLOWING).map { it.symbol })
        assertEquals(listOf("TCS"), lists.history(HistoryScope.ALL).map { it.symbol })
        assertEquals("History · Following", tradingTitleNote("History", null, historyScope = HistoryScope.FOLLOWING))
    }

    @Test
    fun askAndLegacyArgsOpenTheRightView() {
        assertEquals(listOf("Open", "Setups", "History"), TradingFilters)
        assertEquals("History" to HistoryScope.MARKSY, tradingRoute(TradingMarksyResults))
        assertEquals("Setups" to null, tradingRoute("Setups"))
        assertEquals("Open" to null, tradingRoute(null))
        assertEquals("History · Marksy", tradingTitleNote("History", null, historyScope = HistoryScope.MARKSY))
        assertEquals("History", tradingTitleNote("History", null))
        assertEquals("Open · Following", tradingTitleNote("Open", null, openScope = OpenScope.FOLLOWING))
    }
}
