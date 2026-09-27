package com.marksy.os.market

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
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

    suspend fun instrument(symbol: String): MarketDataState<InstrumentLifecycleDto> = fetch { it.instrument(symbol) }

    suspend fun sectors(): MarketDataState<List<SectorOptionDto>> = fetch(emptyCheck = { it.isEmpty() }) { it.sectors() }

    suspend fun activePredictions(cursor: String? = null): MarketDataState<ActivePredictionPageDto> =
        fetch(emptyCheck = { it.items.isEmpty() }) { it.activePredictions(cursor) }

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
                MarketDataState.Error(error.message ?: "Market data unavailable")
            } catch (error: JSONException) {
                MarketDataState.Error(error.message ?: "Market data unavailable")
            }
        }
    }
}
