package com.marksy.os.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.produceState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.marksy.os.upstox.UpstoxFeed
import com.marksy.os.upstox.UpstoxInstruments
import com.marksy.os.upstox.UpstoxLtp

/**
 * Live Upstox quotes for the given symbols / index names, keyed by the same strings. Empty when no
 * Upstox token is set (the feed never starts), so callers simply keep their Marksy values.
 */
@Composable
fun rememberUpstoxQuotes(symbols: List<String>): Map<String, UpstoxLtp> {
    val context = LocalContext.current.applicationContext
    val quotes by UpstoxFeed.quotes.collectAsStateWithLifecycle()
    val status by UpstoxFeed.status.collectAsStateWithLifecycle()
    val feedActive = status !is UpstoxFeed.Status.Idle || quotes.isNotEmpty()
    var keys by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    // This screen's own subscription: the socket drops these keys once no visible screen shows them.
    val owner = remember { Any() }
    LaunchedEffect(symbols, feedActive) {
        if (!feedActive || symbols.isEmpty()) return@LaunchedEffect
        runCatching { UpstoxInstruments.load(context) }
        keys = symbols.mapNotNull { s -> UpstoxInstruments.keyFor(s)?.let { s to it } }.toMap()
        UpstoxFeed.acquire(owner, keys.values)
    }
    DisposableEffect(owner) { onDispose { UpstoxFeed.release(owner) } }
    return keys.mapNotNull { (symbol, key) -> quotes[key]?.let { symbol to it } }.toMap()
}

/** The feed's freshness, re-evaluated every few seconds so a stalled stream stops reading LIVE on its own. */
@Composable
fun rememberFeedFreshness(): com.marksy.os.upstox.FeedFreshness {
    val status by UpstoxFeed.status.collectAsStateWithLifecycle()
    val nseOpen by UpstoxFeed.nseOpen.collectAsStateWithLifecycle()
    val lastTick by UpstoxFeed.lastTickAt.collectAsStateWithLifecycle()
    val now by produceState(System.currentTimeMillis()) { while (true) { kotlinx.coroutines.delay(5_000); value = System.currentTimeMillis() } }
    return com.marksy.os.upstox.FeedFreshness.of(status, nseOpen ?: UpstoxFeed.isMarketOpen(), lastTick, now)
}

/** True only while NSE is open and ticks are still arriving (drives every LIVE badge). */
@Composable
fun upstoxStreaming(): Boolean = rememberFeedFreshness() == com.marksy.os.upstox.FeedFreshness.LIVE
