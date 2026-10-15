package com.marksy.os.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import com.marksy.os.MarksyFormat
import com.marksy.os.gateway.MarketState
import com.marksy.os.upstox.UpstoxIndices
import com.marksy.os.upstox.UpstoxLiveState
import java.time.Instant
import java.time.ZoneId

/**
 * Home's and Market's Market Pulse: the index grid and NIFTY 50 breadth. On Home a tap opens Market; [collapsible] (Market)
 * starts folded to the breadth bar alone and a tap opens or folds it.
 */
@Composable
internal fun MarketPulseCard(market: MarketState, live: UpstoxLiveState = UpstoxLiveState.NotConfigured, onOpen: (() -> Unit)? = null, collapsible: Boolean = false) {
    val snapshot = (market as? MarketState.Loaded)?.snapshot
    // Whole-source preference: the user's own Upstox feed when it's answering, else Marksy's snapshot.
    val liveQuotes = (live as? UpstoxLiveState.Live)?.let { l -> UpstoxIndices.HOME.mapNotNull { (key, name) -> l.quotes[key]?.let { name to it } } }?.takeIf { it.isNotEmpty() }
    var showSource by remember { mutableStateOf(false) }
    var open by rememberSaveable { mutableStateOf(!collapsible) }
    // A streaming socket needs no explanation; the icon appears only when something needs one.
    val streaming = (live as? UpstoxLiveState.Live)?.streaming == true && liveQuotes != null
    val closed = (live as? UpstoxLiveState.Live)?.marketOpen == false && liveQuotes != null
    val sourceLine = when {
        streaming || closed -> null
        liveQuotes != null -> "Upstox reconnecting · last tick ${MarksyFormat.time(Instant.ofEpochMilli((live as UpstoxLiveState.Live).lastTickAt).atZone(ZoneId.systemDefault()))}"
        live is UpstoxLiveState.Failed && live.tokenRejected -> "Upstox token rejected — update it in More → Upstox. Showing Marksy data."
        live is UpstoxLiveState.Failed -> "Upstox unavailable (${live.message}). Showing Marksy data."
        snapshot != null -> "Marksy market snapshot" + (snapshot.asOf?.let { " · as of $it" } ?: "")
        else -> null
    }
    MarksyCard(onClick = if (collapsible) ({ open = !open }) else onOpen, onClickLabel = if (collapsible) (if (open) "Fold Market Pulse" else "Open Market Pulse") else null) {
            MarksyCardHeader("Market Pulse", icon = Icons.Default.TrendingUp, trailing = {
                when {
                    closed -> MarketStatusBadge("CLOSED")
                    liveQuotes != null && streaming -> MarketStatusBadge("LIVE")
                    liveQuotes == null -> snapshot?.marketStatus?.let { MarketStatusBadge(it) }
                }
                if (sourceLine != null && open) {
                    Icon(
                        Icons.Default.Info,
                        contentDescription = "Data source",
                        tint = if (live is UpstoxLiveState.Failed) MarksyTheme.Warning else MarksyTheme.TextMuted,
                        modifier = Modifier.size(MarksySize.Icon).clip(CircleShape).clickable { showSource = !showSource }
                    )
                }
                if (collapsible) Icon(Icons.Default.ExpandMore, contentDescription = null, tint = MarksyTheme.TextMuted, modifier = Modifier.size(MarksySize.Icon).rotate(if (open) 180f else 0f))
            })
            // Source/freshness is one tap away instead of a permanent line.
            if (open && showSource && sourceLine != null) {
                Text(sourceLine, color = MarksyTheme.TextMuted, style = MarksyType.Caption)
            }
            val nifty = rememberUpstoxQuotes(if (liveQuotes != null) Nifty50.SYMBOLS else emptyList())
            val breadth = remember(nifty) { com.marksy.os.market.MarketBreadth.of(nifty.values.map { it.changePct }) }

            when {
                // Folded: the NIFTY 50 breadth bar alone, when the live feed gives one.
                !open -> if (liveQuotes != null && breadth.total >= 12) BreadthBar(breadth, "NIFTY 50 breadth", barOnly = true)
                liveQuotes != null -> {
                    IndexGrid(liveQuotes.map { (name, q) -> Triple(name, q.lastPrice, q.changePct) })
                    if (breadth.total >= 12) BreadthBar(breadth, "NIFTY 50 breadth")
                }
                snapshot != null && snapshot.indices.isNotEmpty() -> IndexGrid(snapshot.indices.take(6).map { Triple(it.name, it.value, it.changePct) })
                market is MarketState.Loading -> MarksyInlineLoader("Loading market data…")
                else -> Text(
                    when (market) {
                        MarketState.Loading -> ""
                        MarketState.NotConfigured -> "Marksy is not connected. Sign in to your Marksy account in More to see live indices."
                        is MarketState.Unavailable -> "Market data unavailable right now."
                        is MarketState.Loaded -> "No index data in the latest Marksy snapshot."
                    },
                    color = MarksyTheme.TextSecondary,
                    style = MarksyType.Small
                )
            }
    }
}

/** Same layout as Market Overview's indices, so the same figures read the same way. */
@Composable
private fun IndexGrid(items: List<Triple<String, Double, Double?>>) {
    QuoteGrid(items.map { (name, value, change) -> Triple(name, formatIndex(value), change) })
}

// Same two decimals as Market Overview.
private fun formatIndex(value: Double): String = MarksyFormat.number(value, 2)
