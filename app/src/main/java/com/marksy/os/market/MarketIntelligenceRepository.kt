package com.marksy.os.market

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONException
import java.io.IOException

/** Every Market screen reads through this repository, never `MarketApiClient` directly, so
 * "not configured" / network / server-error handling lives in one place. Separate from the
 * existing `MarketRepository` (Trading tab's ad-hoc `/dashboard/snapshot` client) by design. */
class MarketIntelligenceRepository(private val client: MarketApiClient?) {
    /** Last good overview, so coming back to Market shows it at once while a fresh one loads. */
    @Volatile var lastOverview: MarketSummaryDto? = null
        private set

    suspend fun overview(): MarketDataState<MarketSummaryDto> = fetch { it.marketSummary() }.also { if (it is MarketDataState.Loaded) lastOverview = it.value }

    suspend fun liveQuotes(symbols: List<String>? = null): MarketDataState<LiveQuotesResponseDto> =
        fetch { it.liveQuotes(symbols) }

    suspend fun instrument(symbol: String, includeCalls: Boolean = false): MarketDataState<InstrumentLifecycleDto> = fetch { it.instrument(symbol, includeCalls) }

    suspend fun sectors(): MarketDataState<List<SectorOptionDto>> = fetch(emptyCheck = { it.isEmpty() }) { it.sectors() }

    suspend fun activePredictions(cursor: String? = null): MarketDataState<ActivePredictionPageDto> =
        fetch(emptyCheck = { it.items.isEmpty() }) { c -> c.activePredictions(cursor).also { p -> p.latestScan?.let { _latestScan.value = it } } }

    suspend fun closedPredictions(cursor: String? = null): MarketDataState<ClosedPredictionPageDto> =
        fetch(emptyCheck = { it.items.isEmpty() }) { it.closedPredictions(cursor) }

    suspend fun performanceSummary(range: String = "30d"): MarketDataState<PerformanceSummaryDto> =
        fetch { it.performanceSummary(range) ?: throw java.io.IOException("Track record unavailable") }

    suspend fun performanceBreakdown(dimension: String): MarketDataState<PerformanceBreakdownDto> =
        fetch(emptyCheck = { it.items.isEmpty() }) { it.performanceBreakdown(dimension) ?: throw java.io.IOException("Breakdown unavailable") }

    suspend fun ipos(stage: String? = null, query: String? = null): MarketDataState<List<IpoListItemDto>> =
        fetch(emptyCheck = { it.isEmpty() }) { it.ipos(stage, query) }

    suspend fun ipoStageCounts(): MarketDataState<IpoStageCountsDto> = fetch { it.ipoStageCounts() }

    suspend fun ipoDetail(id: String): MarketDataState<IpoDetailDto> = fetch { it.ipoDetail(id) }

    suspend fun recommendation(id: Int): MarketDataState<org.json.JSONObject> = fetch(emptyCheck = { it.length() == 0 }) { it.recommendation(id) }

    suspend fun trackedIpos(): MarketDataState<List<IpoTrackedItemDto>> = fetch(emptyCheck = { it.isEmpty() }) { it.trackedIpos() }

    suspend fun setIpoTracking(id: String, tracking: Boolean): MarketDataState<IpoTrackingStateDto> = fetch { it.setIpoTracking(id, tracking) }

    suspend fun ipoHistory(id: String): MarketDataState<List<IpoHistoryEntryDto>> = fetch { it.ipoHistory(id) }

    suspend fun myTips(status: String?, cursor: String? = null): MarketDataState<MyTipPageDto> =
        fetch(emptyCheck = { it.items.isEmpty() }, ledger = true) { it.myTips(status, cursor) }

    suspend fun myScorecard(query: ScorecardQuery): MarketDataState<ScorecardDto> =
        query.filterParams()?.let { f -> fetch(ledger = true) { it.myScorecard(f) } } ?: incompleteRange

    suspend fun scorecardSummary(query: ScorecardQuery): MarketDataState<ScorecardSummaryDto> =
        query.filterParams()?.let { f -> fetch(ledger = true) { it.scorecardSummary(f) } } ?: incompleteRange

    suspend fun scorecards(entity: ScorecardEntity, query: ScorecardQuery): MarketDataState<EntityScorecardListDto> =
        query.filterParams()?.let { f -> fetch(emptyCheck = { it.items.isEmpty() }, ledger = true) { it.scorecards(entity.param, f) } } ?: incompleteRange

    suspend fun scorecard(entity: ScorecardEntity, id: Int, query: ScorecardQuery): MarketDataState<ScorecardDto> =
        query.filterParams()?.let { f -> fetch(ledger = true) { it.scorecard(entity.param, id, f) } } ?: incompleteRange

    suspend fun tipDetail(tipId: String): MarketDataState<TipDetailDto> = fetch(ledger = true) { it.tipDetail(tipId) }

    /** Re-reads the customer's follows from the server and publishes them as [followed]. */
    suspend fun follows(): MarketDataState<FollowListDto> =
        fetch(emptyCheck = { it.items.isEmpty() }, ledger = true) { c -> c.follows().also { _followed.value = Follows.keys(it) } }

    /** Optimistic in [followed] while saving, then the server's answer; a failure undoes only this key. */
    suspend fun setFollowing(key: FollowKey, follow: Boolean): MarketDataState<Set<FollowKey>> {
        // M4: a toggle settles on the server's whole set, so read it first when it was never read.
        if (_followed.value == null) follows()
        _followed.value = Follows.toggled(_followed.value, key, follow)
        val result = fetch(ledger = true) { c ->
            try {
                if (follow) Follows.followed(_followed.value, key, c.follow(key)) else Follows.keys(c.unfollow(key))
            } catch (limit: MarketApiException) {
                throw if (limit.status == 422) MarketApiException(Follows.LIMIT_TEXT, 422) else limit
            }
        }
        _followed.value = if (result is MarketDataState.Loaded) result.value else Follows.toggled(_followed.value, key, !follow)
        return result
    }

    suspend fun tipAlerts(): MarketDataState<List<TipAlertDto>> = fetch(emptyCheck = { it.isEmpty() }, ledger = true) { it.tipAlerts() }

    suspend fun markAlertRead(id: Long): MarketDataState<Unit> = fetch(ledger = true) { it.markAlertRead(id) }

    private val incompleteRange = MarketDataState.Error("Pick a start and an end date")

    // 4b review M1: the ledger screens word a session or access failure; signed out or 401 is the existing sign-in state.
    private fun ledgerCopy(error: IOException): MarketDataState<Nothing>? = when ((error as? MarketApiException)?.status) {
        401 -> MarketDataState.Unavailable
        403 -> MarketDataState.Error("Your account doesn't have access to tip records yet")
        404 -> MarketDataState.Error("Not available yet")
        else -> null
    }

    /** The Overview freshness footer's source. `Stale` here means the feed's own reported
     * `feedState`/`fallbackActive` say it is degraded — never a client-invented age threshold. */
    suspend fun liveFeedHealth(): MarketDataState<LiveFeedHealthDto> {
        val activeClient = client ?: return MarketDataState.Unavailable
        return withContext(Dispatchers.IO) {
            try {
                val value = activeClient.liveFeedHealth()
                if (value.feedState != "STREAMING" || value.fallbackActive) MarketDataState.Stale(value, ageSeconds = null)
                else MarketDataState.Loaded(value)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: IOException) {
                MarketDataState.Error(error.message ?: "Market feed health unavailable")
            } catch (error: JSONException) {
                MarketDataState.Error(error.message ?: "Market feed health unavailable")
            }
        }
    }

    private suspend fun <T> fetch(
        emptyCheck: (T) -> Boolean = { false },
        ledger: Boolean = false,
        call: suspend (MarketApiClient) -> T
    ): MarketDataState<T> {
        val activeClient = client ?: return MarketDataState.Unavailable
        return withContext(Dispatchers.IO) {
            try {
                val value = call(activeClient)
                if (emptyCheck(value)) MarketDataState.Empty else MarketDataState.Loaded(value)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: IOException) {
                (if (ledger) ledgerCopy(error) else null) ?: MarketDataState.Error(error.message ?: "Market data unavailable")
            } catch (error: JSONException) {
                MarketDataState.Error(error.message ?: "Market data unavailable")
            }
        }
    }

    companion object {
        // Shared across instances (screens each build their own) and kept even when the page is Empty.
        private val _latestScan = MutableStateFlow<LatestScanDto?>(null)
        /** The newest discovery scan seen behind Marksy's picks, for the Trading title note. */
        val latestScan: StateFlow<LatestScanDto?> = _latestScan
        private val _followed = MutableStateFlow<Set<FollowKey>?>(null)
        /** The last follow set the server returned (null until read); memory only, re-read on every follow screen. */
        val followed: StateFlow<Set<FollowKey>?> = _followed
        internal fun forgetFollows() { _followed.value = null }  // tests only: the state before any server read
    }
}
