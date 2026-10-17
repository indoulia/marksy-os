package com.marksy.os.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape


import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import com.marksy.os.EmptyState
import com.marksy.os.MarksyFormat
import com.marksy.os.market.MarketDataState
import com.marksy.os.market.MarketMoverDto
import com.marksy.os.market.MarketSummaryDto
import com.marksy.os.upstox.UpstoxLtp
import com.marksy.os.upstox.UpstoxIndices
import com.marksy.os.upstox.UpstoxInstruments
import androidx.compose.runtime.remember
import java.util.Locale

@Composable
internal fun MarketOverviewScreen(
    state: MarketDataState<MarketSummaryDto>,
    padding: PaddingValues,
    health: MarketDataState<com.marksy.os.market.LiveFeedHealthDto> = MarketDataState.Unavailable,
    onOpenStock: ((String) -> Unit)? = null,
    market: com.marksy.os.gateway.MarketState = com.marksy.os.gateway.MarketState.Loading,
    live: com.marksy.os.upstox.UpstoxLiveState = com.marksy.os.upstox.UpstoxLiveState.NotConfigured,
    repository: com.marksy.os.market.MarketIntelligenceRepository? = null,
    onTrade: (TradeIntent) -> Unit = {},
    onOpenSetups: () -> Unit = {},
    onOpenTip: (TipCardModel) -> Unit = {}
) {
    // The footer's source/freshness wording comes from the feed's own reported state
    // (`/market/live/health`), never a client-invented threshold on `summary.asOf`.
    val summary = (state as? MarketDataState.Loaded)?.value ?: (state as? MarketDataState.Stale)?.value
    val symbols = remember(summary) {
        (summary?.let { s -> s.topGainers.map { it.symbol } + s.topLosers.map { it.symbol } }.orEmpty() + Nifty50.SYMBOLS).distinct()
    }
    val quotes = rememberUpstoxQuotes(symbols)
    val active = rememberMostActive(Nifty50.SYMBOLS)
    val streaming = upstoxStreaming() && quotes.isNotEmpty()
    val freshnessLabel = when {
        streaming -> "Prices: your Upstox live feed"
        quotes.isNotEmpty() -> "Prices: Upstox · last traded (market closed)"
        health is MarketDataState.Loaded -> "Data: Marksy (Upstox feed · live)"
        health is MarketDataState.Stale -> "Data: Marksy (Upstox feed · ${health.value.feedState.lowercase()}${if (health.value.fallbackActive) ", fallback" else ""})"
        else -> "Data: Marksy · feed status unknown"
    }

    MarksyList(Modifier.background(MarksyTheme.Background), bottom = padding.calculateBottomPadding()) {
        item(key = "pulse") { MarketPulseCard(market, live, collapsible = true) }
        repository?.let { r -> item(key = "picks") { MarksyPicksCard(r, onTrade, onOpenSetups, onOpenTip) } }
        when (state) {
            is MarketDataState.Loading -> item { MarksyLoader("Checking market…") }
            is MarketDataState.Unavailable -> item { EmptyState("Market Intelligence is not configured", "Add a Market API key in More → Configure Gateway.") }
            is MarketDataState.Error -> item { EmptyState("Market data unavailable", state.message) }
            is MarketDataState.Empty -> item { EmptyState("No market data", "Nothing to show right now.") }
            is MarketDataState.Loaded -> overviewContent(state.value, freshnessLabel, quotes, active, onOpenStock)
            is MarketDataState.Stale -> overviewContent(state.value, freshnessLabel, quotes, active, onOpenStock)
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.overviewContent(
    summary: MarketSummaryDto, freshnessLabel: String, live: Map<String, UpstoxLtp>,
    active: List<Pair<String, com.marksy.os.upstox.UpstoxQuote>>?, onOpenStock: ((String) -> Unit)?
) {
    // Open/live state is shown on the page title, not repeated here.
    // Upstox has no movers endpoint, so rank NIFTY 50 constituents by their live change instead.
    val ranked = Nifty50.SYMBOLS.mapNotNull { s -> live[s]?.takeIf { it.changePct != null }?.let { s to it } }.sortedByDescending { it.second.changePct }
    fun movers(list: List<MarketMoverDto>) = list.map { m -> val q = live[m.symbol]; Mover(m.symbol, q?.lastPrice ?: m.price, q?.changePct ?: m.changePercent) }
    val sets = if (ranked.size >= 12) linkedMapOf(
        "Gainers" to ranked.take(MOVERS).map { (s, q) -> Mover(s, q.lastPrice, q.changePct) },
        "Losers" to ranked.takeLast(MOVERS).reversed().map { (s, q) -> Mover(s, q.lastPrice, q.changePct) }
    ) else linkedMapOf("Gainers" to movers(summary.topGainers).take(MOVERS), "Losers" to movers(summary.topLosers).take(MOVERS))
    active?.takeIf { it.isNotEmpty() }?.let { list -> sets["Active"] = list.take(MOVERS).map { (s, q) -> val l = live[s]; Mover(s, l?.lastPrice ?: q.lastPrice, l?.changePct ?: q.changePct) } }
    val shown = sets.filterValues { it.isNotEmpty() }
    if (shown.isNotEmpty()) item(key = "movers") { MoversCard(if (ranked.size >= 12) "Movers · NIFTY 50" else "Movers · Marksy", shown, onOpenStock) }
    item { Text(freshnessLabel, color = MarksyTheme.TextMuted, style = MarksyType.Meta) }
}

private const val MOVERS = 5

private data class Mover(val symbol: String, val price: Double?, val change: Double?)

// Percent change that fills a mover's bar.
private const val MOVE_FOR_FULL_BAR = 40.0 / 11

/** Gainers, losers or most active as one list with a toggle; the bar's length follows the day's change. */
@Composable
private fun MoversCard(title: String, sets: Map<String, List<Mover>>, onOpen: ((String) -> Unit)?) {
    var pick by rememberSaveable { mutableStateOf(sets.keys.first()) }
    val shown = pick.takeIf { it in sets } ?: sets.keys.first()
    MarksyCard {
        MarksyCardHeader(title, trailing = {
            Row(horizontalArrangement = Arrangement.spacedBy(MarksySpace.Tight)) { sets.keys.forEach { k -> Pill(k, selected = k == shown, compact = true) { pick = k } } }
        })
        Column {
            sets.getValue(shown).forEach { m ->
                MarksyDivider()
                val tint = when { m.change == null || m.change == 0.0 -> MarksyTheme.TextSecondary; m.change > 0 -> MarksyTheme.Positive; else -> MarksyTheme.Negative }
                Row(
                    Modifier.fillMaxWidth().then(if (onOpen != null) Modifier.clickable { onOpen(m.symbol) } else Modifier).padding(vertical = MarksySpace.Inner),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(MarksySpace.Gap)
                ) {
                    Text(m.symbol, color = MarksyTheme.TextPrimary, style = MarksyType.Body, fontWeight = FontWeight.SemiBold, maxLines = 1, modifier = Modifier.weight(1f))
                    m.price?.let { Text(MarksyFormat.rupees(it), color = MarksyTheme.TextPrimary, style = MarksyType.Body, maxLines = 1) }
                    m.change?.let { c ->
                        Box(Modifier.width(MarksySize.MoverBar * (kotlin.math.abs(c) / MOVE_FOR_FULL_BAR).coerceAtMost(1.0).toFloat()).height(MarksySize.Bar).clip(MarksyShape.Badge).background(tint))
                        Text(MarksyFormat.percent(c), color = tint, style = MarksyType.Small, fontWeight = FontWeight.SemiBold, maxLines = 1, textAlign = TextAlign.End, modifier = Modifier.widthIn(min = MarksySize.ColumnValue))
                    }
                }
            }
        }
    }
}

/** Marksy's setups still waiting to be bought, each on the shared price track; "All setups" opens Trading's Setups. */
@Composable
private fun MarksyPicksCard(repository: com.marksy.os.market.MarketIntelligenceRepository, onTrade: (TradeIntent) -> Unit, onOpenSetups: () -> Unit, onOpenTip: (TipCardModel) -> Unit) {
    val calls by produceState<List<com.marksy.os.market.ActivePredictionDto>?>(null, repository) {
        value = (repository.activePredictions() as? MarketDataState.Loaded)?.value?.items.orEmpty()
    }
    val setups = remember(calls) {
        calls.orEmpty().filter { p -> com.marksy.os.market.LedgerCalls.isLive(p) && (p.ledger?.let(TipTrack::waiting) ?: true) }
            .sortedWith(compareByDescending<com.marksy.os.market.ActivePredictionDto> { it.isActionableNow }.thenByDescending { it.compositeOpportunityScore ?: -1.0 })
            .take(PICKS)
    }
    if (setups.isEmpty()) return
    val quotes = rememberUpstoxQuotes(remember(setups) { setups.map { it.symbol } })
    val today = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Kolkata"))
    MarksyCard {
        MarksyCardHeader("Marksy picks today", trailing = {
            Text("All setups", color = MarksyTheme.PrimaryEmerald, style = MarksyType.Small, fontWeight = FontWeight.SemiBold, modifier = Modifier.clip(MarksyShape.Chip).clickable(onClick = onOpenSetups))
        })
        setups.forEach { p ->
            val m = TipCards.marksy(p, quotes[p.symbol]?.lastPrice, today, null)
            MarksyDivider()
            Column(Modifier.fillMaxWidth().clickable(onClickLabel = "Open ${p.symbol} tip") { onOpenTip(m) }, verticalArrangement = Arrangement.spacedBy(MarksySpace.Inner)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(MarksySpace.Inner)) {
                    Text(p.symbol, color = MarksyTheme.TextPrimary, style = MarksyType.Subhead, fontWeight = FontWeight.Bold, maxLines = 1)
                    Text(
                        "${p.horizon}-day · ${MarksyFormat.percent(if (p.confidence <= 1) p.confidence * 100 else p.confidence, 0, signed = false)}",
                        color = MarksyTheme.TextMuted, style = MarksyType.Meta, maxLines = 1, modifier = Modifier.weight(1f)
                    )
                    m.trade?.let { t -> MarksyButton(if (t.side == TradeSide.SELL) "Sell" else "Buy", { onTrade(t) }) }
                }
                m.track?.let { PriceTrackView(it) }
            }
        }
    }
}

private const val PICKS = 3


/** Formats a raw `marketStatus`/`marketSession` value from the API (e.g. `MARKET_HOURS`) for display. */
internal fun formatMarketStatus(status: String): String = when (status.uppercase(Locale.ROOT)) {
    "MARKET_HOURS", "OPEN", "LIVE" -> "Market Open"
    "PRE_MARKET" -> "Pre-Market"
    "POST_MARKET" -> "Post-Market"
    "CLOSED" -> "Market Closed"
    "UNKNOWN" -> "Unknown"
    else -> status.lowercase(Locale.ROOT).replace('_', ' ').replaceFirstChar { it.titlecase(Locale.ROOT) }
}
