package com.marksy.os.market

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/** Every Market screen reads through this repository, never `MarketApiClient` directly, so
 * "not configured" / network / server-error handling lives in one place. Separate from the
 * existing `MarketRepository` (Trading tab's ad-hoc `/dashboard/snapshot` client) by design. */
class MarketIntelligenceRepository(private val client: MarketApiClient?) {
    suspend fun overview(): MarketDataState<MarketSummaryDto> = fetch { it.marketSummary() }

    suspend fun liveQuotes(symbols: List<String>? = null): MarketDataState<LiveQuotesResponseDto> =
        fetch { it.liveQuotes(symbols) }

    suspend fun instrument(symbol: String): MarketDataState<InstrumentLifecycleDto> = fetch { it.instrument(symbol) }

    suspend fun activePredictions(cursor: String? = null): MarketDataState<ActivePredictionPageDto> =
        fetch(emptyCheck = { it.items.isEmpty() }) { it.activePredictions(cursor) }

    suspend fun ipos(stage: String? = null, query: String? = null): MarketDataState<List<IpoListItemDto>> =
        fetch(emptyCheck = { it.isEmpty() }) { it.ipos(stage, query) }

    suspend fun ipoStageCounts(): MarketDataState<IpoStageCountsDto> = fetch { it.ipoStageCounts() }

    suspend fun ipoDetail(id: String): MarketDataState<IpoDetailDto> = fetch { it.ipoDetail(id) }

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
            }
        }
    }
}
