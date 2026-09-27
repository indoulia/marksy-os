package com.marksy.os.upstox

import org.junit.Assert.assertEquals
import org.junit.Test

class FeedModelTest {
    @Test fun socketSubscribesTheUnionAndUnsubscribesOnlyKeysNoOneElseHolds() {
        val subs = FeedSubscriptions()
        val home = Any(); val stock = Any()

        assertEquals(setOf("A", "B") to emptySet<String>(), subs.set(home, setOf("A", "B")))
        assertEquals(setOf("C") to emptySet<String>(), subs.set(stock, setOf("B", "C")))
        assertEquals(setOf("A"), subs.release(home))
        assertEquals(setOf("B", "C"), subs.all())
        // Same owner, new keys: only the difference goes to the socket.
        assertEquals(setOf("D") to setOf("C"), subs.set(stock, setOf("B", "D")))
    }

    @Test fun olderTicksNeverOverwriteNewerOnes() {
        val current = mapOf("A" to UpstoxLtp("A", 100.0, 99.0, tradeTime = 2_000))
        val merged = FeedMerge.merge(current, mapOf("A" to UpstoxLtp("A", 95.0, 99.0, tradeTime = 1_000), "B" to UpstoxLtp("B", 10.0, null, tradeTime = 5)))

        assertEquals(100.0, merged.getValue("A").lastPrice, 0.0)
        assertEquals(10.0, merged.getValue("B").lastPrice, 0.0)
        assertEquals(101.0, FeedMerge.merge(current, mapOf("A" to UpstoxLtp("A", 101.0, 99.0, tradeTime = 2_000)))["A"]!!.lastPrice, 0.0)
    }

    @Test fun liveOnlyWhileTicksAreFreshDuringTheSession() {
        val live = UpstoxFeed.Status.Live
        assertEquals(FeedFreshness.LIVE, FeedFreshness.of(live, marketOpen = true, lastTickAt = 100_000, now = 110_000))
        assertEquals(FeedFreshness.STALE, FeedFreshness.of(live, marketOpen = true, lastTickAt = 100_000, now = 100_000 + FeedFreshness.STALE_AFTER_MS + 1))
        assertEquals(FeedFreshness.CLOSED, FeedFreshness.of(live, marketOpen = false, lastTickAt = 0, now = 1))
        assertEquals(FeedFreshness.RECONNECTING, FeedFreshness.of(UpstoxFeed.Status.Reconnecting("x"), marketOpen = true, lastTickAt = 100_000, now = 100_500))
        assertEquals(FeedFreshness.OFF, FeedFreshness.of(UpstoxFeed.Status.Idle, marketOpen = true, lastTickAt = 0, now = 1))
    }
}
