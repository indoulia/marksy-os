package com.marksy.os.upstox

/** What market cards show from the user's own Upstox feed. */
sealed interface UpstoxLiveState {
    data object NotConfigured : UpstoxLiveState
    data object Loading : UpstoxLiveState
    /** [streaming]: socket connected. [marketOpen]: NSE in session; when false these are the last traded prices. */
    data class Live(val quotes: Map<String, UpstoxLtp>, val lastTickAt: Long, val streaming: Boolean, val marketOpen: Boolean = true) : UpstoxLiveState
    data class Failed(val message: String, val tokenRejected: Boolean) : UpstoxLiveState
}
