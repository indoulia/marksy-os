package com.marksy.os.gateway

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Live market context for Home and Trading; every screen must render honestly when it is unavailable. */
sealed interface MarketState {
    data object Loading : MarketState
    data object NotConfigured : MarketState
    data class Unavailable(val reason: String) : MarketState
    data class Loaded(val snapshot: MarketSnapshot, val fetchedAt: Long) : MarketState
}

object MarketRepository {
    private const val TAG = "MarksyMarket"

    suspend fun fetch(): MarketState = withContext(Dispatchers.IO) {
        val client = MarksyGatewayProvider.marketClient() ?: return@withContext MarketState.NotConfigured
        try {
            MarketState.Loaded(client.marketSnapshot(), System.currentTimeMillis())
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            Log.w(TAG, "Market snapshot failed: ${error.message}")
            MarketState.Unavailable(error.message ?: "Market data unavailable")
        }
    }
}
