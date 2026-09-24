package com.marksy.os.market

sealed class MarketDataState<out T> {
    data object Loading : MarketDataState<Nothing>()
    data class Loaded<T>(val value: T) : MarketDataState<T>()
    data class Stale<T>(val value: T, val ageSeconds: Int?) : MarketDataState<T>()
    data object Unavailable : MarketDataState<Nothing>()
    data class Error(val message: String) : MarketDataState<Nothing>()
    data object Empty : MarketDataState<Nothing>()
}
