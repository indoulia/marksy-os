package com.marksy.os.ui

import androidx.compose.runtime.Composable
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
    LaunchedEffect(symbols, feedActive) {
        if (!feedActive || symbols.isEmpty()) return@LaunchedEffect
        runCatching { UpstoxInstruments.load(context) }
        keys = symbols.mapNotNull { s -> UpstoxInstruments.keyFor(s)?.let { s to it } }.toMap()
        UpstoxFeed.watch(keys.values)
    }
    return keys.mapNotNull { (symbol, key) -> quotes[key]?.let { symbol to it } }.toMap()
}

/** True only while the socket is connected AND NSE is open (drives every LIVE badge). */
@Composable
fun upstoxStreaming(): Boolean {
    val status by UpstoxFeed.status.collectAsStateWithLifecycle()
    val nseOpen by UpstoxFeed.nseOpen.collectAsStateWithLifecycle()
    return status is UpstoxFeed.Status.Live && (nseOpen ?: UpstoxFeed.isMarketOpen())
}
