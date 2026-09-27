package com.marksy.os.upstox

/** Which keys each on-screen owner shows; the socket subscribes their union and drops keys no one holds. */
class FeedSubscriptions {
    private val byOwner = HashMap<Any, Set<String>>()

    @Synchronized fun all(): Set<String> = byOwner.values.flatten().toSet()

    /** Replaces [owner]'s keys; returns (keys to subscribe, keys to unsubscribe) at the socket. */
    @Synchronized fun set(owner: Any, keys: Set<String>): Pair<Set<String>, Set<String>> {
        val before = all()
        if (keys.isEmpty()) byOwner.remove(owner) else byOwner[owner] = keys
        val after = all()
        return (after - before) to (before - after)
    }

    @Synchronized fun release(owner: Any): Set<String> = set(owner, emptySet()).second
}

object FeedMerge {
    /** Frames can arrive out of order after a reconnect; keep a key's newer trade. */
    fun merge(current: Map<String, UpstoxLtp>, incoming: Map<String, UpstoxLtp>): Map<String, UpstoxLtp> {
        val newer = incoming.filter { (k, q) ->
            val old = current[k]?.tradeTime
            old == null || q.tradeTime == null || q.tradeTime >= old
        }
        return if (newer.isEmpty()) current else current + newer
    }
}

/** What every live badge shows; LIVE only while ticks keep arriving in session. */
enum class FeedFreshness {
    LIVE, STALE, CLOSED, CONNECTING, RECONNECTING, OFF, TOKEN_REJECTED;

    companion object {
        /** Indices tick every second in session; this long without one means the stream has stalled. */
        const val STALE_AFTER_MS = 20_000L

        fun of(status: UpstoxFeed.Status, marketOpen: Boolean, lastTickAt: Long, now: Long): FeedFreshness = when (status) {
            UpstoxFeed.Status.Idle -> OFF
            UpstoxFeed.Status.Connecting -> CONNECTING
            is UpstoxFeed.Status.Reconnecting -> RECONNECTING
            is UpstoxFeed.Status.TokenRejected -> TOKEN_REJECTED
            UpstoxFeed.Status.Live -> when {
                !marketOpen -> CLOSED
                lastTickAt > 0 && now - lastTickAt <= STALE_AFTER_MS -> LIVE
                else -> STALE
            }
        }
    }
}

/** Stream diagnostics for Marksy Health: is a problem the socket, the ticks, or decoding? */
data class FeedStats(
    val connects: Int = 0,
    val drops: Int = 0,
    val ticks: Long = 0,
    val undecodable: Int = 0,
    val outOfOrder: Long = 0,
    val subscribed: Int = 0,
    val connectedAt: Long = 0,
    val lastError: String? = null
)
