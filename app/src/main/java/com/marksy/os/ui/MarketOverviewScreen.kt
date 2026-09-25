package com.marksy.os.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.marksy.os.EmptyState
import com.marksy.os.market.MarketDataState
import com.marksy.os.market.MarketMoverDto
import com.marksy.os.market.MarketSummaryDto
import com.marksy.os.upstox.UpstoxLtp
import com.marksy.os.upstox.UpstoxIndices
import com.marksy.os.upstox.UpstoxInstruments
import androidx.compose.runtime.remember
import java.util.Locale

@Composable
fun MarketOverviewScreen(
    state: MarketDataState<MarketSummaryDto>,
    padding: PaddingValues,
    health: MarketDataState<com.marksy.os.market.LiveFeedHealthDto> = MarketDataState.Unavailable
) {
    // The footer's source/freshness wording comes from the feed's own reported state
    // (`/market/live/health`), never a client-invented threshold on `summary.asOf`.
    val summary = (state as? MarketDataState.Loaded)?.value ?: (state as? MarketDataState.Stale)?.value
    val symbols = remember(summary) {
        (summary?.let { s -> s.indexes.map { it.name } + s.topGainers.map { it.symbol } + s.topLosers.map { it.symbol } }.orEmpty() +
            UpstoxIndices.HOME.map { it.second } + Nifty50.SYMBOLS).distinct()
    }
    val live = rememberUpstoxQuotes(symbols)
    val streaming = upstoxStreaming() && live.isNotEmpty()
    val freshnessLabel = when {
        streaming -> "Prices: your Upstox live feed"
        live.isNotEmpty() -> "Prices: Upstox · last traded (market closed)"
        health is MarketDataState.Loaded -> "Data: Marksy (Upstox feed · live)"
        health is MarketDataState.Stale -> "Data: Marksy (Upstox feed · ${health.value.feedState.lowercase()}${if (health.value.fallbackActive) ", fallback" else ""})"
        else -> "Data: Marksy · feed status unknown"
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(MarksyTheme.Background).padding(horizontal = 18.dp),
        contentPadding = PaddingValues(bottom = padding.calculateBottomPadding() + 20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        when (state) {
            is MarketDataState.Loading -> item { MarksyLoader("Checking market...") }
            is MarketDataState.Unavailable -> item { EmptyState("Market Intelligence is not configured", "Add a Market API key in More → Configure Gateway.") }
            is MarketDataState.Error -> item { EmptyState("Market data unavailable", state.message) }
            is MarketDataState.Empty -> item { EmptyState("No market data", "Nothing to show right now.") }
            is MarketDataState.Loaded -> overviewContent(state.value, freshnessLabel, live, streaming)
            is MarketDataState.Stale -> overviewContent(state.value, freshnessLabel, live, streaming)
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.overviewContent(summary: MarketSummaryDto, freshnessLabel: String, live: Map<String, UpstoxLtp>, streaming: Boolean) {
    // Open/live state is shown on the page title, not repeated here.
    // Marksy's indices plus the Home set; each ticks live when the user's Upstox feed has it.
    val names = (summary.indexes.map { it.name } + UpstoxIndices.HOME.map { it.second }).distinctBy { UpstoxInstruments.keyFor(it) ?: it }
    val marksy = summary.indexes.associateBy { it.name }
    val indices = names.mapNotNull { n -> live[n]?.let { Triple(n, formatIndexValue(it.lastPrice), it.changePct) } ?: marksy[n]?.let { Triple(n, formatIndexValue(it.value), it.changePct) } }
    if (indices.isNotEmpty()) item(key = "g-indices") { QuoteGridCard("Indices", indices) }
    // Upstox has no movers endpoint, so rank NIFTY 50 constituents by their live change instead.
    val ranked = Nifty50.SYMBOLS.mapNotNull { s -> live[s]?.takeIf { it.changePct != null }?.let { s to it } }.sortedByDescending { it.second.changePct }
    if (ranked.size >= 12) {
        item(key = "g-gainers") { QuoteGridCard("Top gainers · NIFTY 50", ranked.take(6).map { (s, q) -> Triple(s, "₹" + formatIndexValue(q.lastPrice), q.changePct) }) }
        item(key = "g-losers") { QuoteGridCard("Top losers · NIFTY 50", ranked.takeLast(6).reversed().map { (s, q) -> Triple(s, "₹" + formatIndexValue(q.lastPrice), q.changePct) }) }
    } else {
        fun movers(list: List<MarketMoverDto>) = list.map { m -> val q = live[m.symbol]; Triple(m.symbol, (q?.lastPrice ?: m.price)?.let { "₹" + formatIndexValue(it) } ?: "—", q?.changePct ?: m.changePercent) }
        if (summary.topGainers.isNotEmpty()) item(key = "g-gainers") { QuoteGridCard("Gainers · Marksy", movers(summary.topGainers)) }
        if (summary.topLosers.isNotEmpty()) item(key = "g-losers") { QuoteGridCard("Losers · Marksy", movers(summary.topLosers)) }
    }
    item { Text(freshnessLabel, color = MarksyTheme.TextMuted, fontSize = 11.sp) }
}

private fun formatIndexValue(value: Double): String = String.format(Locale.getDefault(), "%,.2f", value)

/** Formats a raw `marketStatus`/`marketSession` value from the API (e.g. `MARKET_HOURS`) for display. */
internal fun formatMarketStatus(status: String): String = when (status.uppercase(Locale.ROOT)) {
    "MARKET_HOURS", "OPEN", "LIVE" -> "Market Open"
    "PRE_MARKET" -> "Pre-Market"
    "POST_MARKET" -> "Post-Market"
    "CLOSED" -> "Market Closed"
    "UNKNOWN" -> "Unknown"
    else -> status.lowercase(Locale.ROOT).replace('_', ' ').replaceFirstChar { it.titlecase(Locale.ROOT) }
}
