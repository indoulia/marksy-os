package com.marksy.os.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.marksy.os.EmptyState
import com.marksy.os.data.local.NotificationEventEntity
import com.marksy.os.market.MarketIntelligenceRepository

// Predictions live on Trading (Marksy picks), so Market covers the market itself.
enum class MarketTab(val label: String) { OVERVIEW("Overview"), STOCKS("Stocks"), IPOS("IPOs"), UPDATES("Updates") }

/** Section and symbol are hoisted so the Stocks search can sit in the app header. */
@Composable
fun MarketScreen(
    repository: MarketIntelligenceRepository,
    padding: PaddingValues,
    tabName: String,
    onTabSelected: (String) -> Unit,
    selectedSymbol: String?,
    onSymbolSelected: (String?) -> Unit,
    stockQuery: String = "",
    marketEvents: List<NotificationEventEntity> = emptyList(),
    stockEvents: List<NotificationEventEntity> = emptyList(),
    onEventSelected: (NotificationEventEntity) -> Unit = {}
) {
    val tab = MarketTab.entries.firstOrNull { it.name == tabName } ?: MarketTab.OVERVIEW

    BackHandler(enabled = selectedSymbol != null) { onSymbolSelected(null) }

    // Section switching uses the same bottom-right floating filter as Inbox and Trading.
    Box(Modifier.fillMaxSize().background(MarksyTheme.Background).padding(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding())) {
        val inner = PaddingValues(bottom = OneHandListBottomPadding)
        when (tab) {
            MarketTab.OVERVIEW -> {
                val refresh = rememberRefreshState()
                val state by produceState(com.marksy.os.market.MarketDataState.Loading as com.marksy.os.market.MarketDataState<com.marksy.os.market.MarketSummaryDto>, refresh.key) {
                    while (true) { value = repository.overview(); refresh.done(); kotlinx.coroutines.delay(60_000) }
                }
                val health by produceState(com.marksy.os.market.MarketDataState.Loading as com.marksy.os.market.MarketDataState<com.marksy.os.market.LiveFeedHealthDto>, refresh.key) {
                    while (true) { value = repository.liveFeedHealth(); kotlinx.coroutines.delay(60_000) }
                }
                MarksyRefreshBox(refresh) { MarketOverviewScreen(state = state, padding = inner, health = health) }
            }
            MarketTab.STOCKS -> {
                val symbol = selectedSymbol
                val query = stockQuery.trim()
                if (query.length >= 3 && !query.equals(symbol, ignoreCase = true)) {
                    StockSuggestions(query, inner, onSymbolSelected)
                } else if (symbol == null) {
                    Box(Modifier.padding(18.dp)) { EmptyState("Look up a stock", "Search a symbol in the bar above, e.g. RELIANCE.") }
                } else {
                    val refresh = rememberRefreshState()
                    val state by produceState(com.marksy.os.market.MarketDataState.Loading as com.marksy.os.market.MarketDataState<com.marksy.os.market.InstrumentLifecycleDto>, symbol, refresh.key) {
                        value = repository.instrument(symbol)
                        refresh.done()
                    }
                    var range by rememberSaveable(symbol) { mutableStateOf(com.marksy.os.upstox.ChartRange.D1) }
                    val live = rememberStockLive(symbol, range, refresh.key)
                    val fundamentals = rememberStockFundamentals(live.key, refresh.key)
                    val words = remember(symbol, state) {
                        listOfNotNull(symbol, (state as? com.marksy.os.market.MarketDataState.Loaded)?.value?.companyName?.substringBefore(" Limited")?.substringBefore(" Ltd"))
                            .map { Regex("\\b${Regex.escape(it)}\\b", RegexOption.IGNORE_CASE) }
                    }
                    val mentions = remember(stockEvents, words) { stockEvents.filter { e -> words.any { it.containsMatchIn("${e.title} ${e.body}") } } }
                    MarksyRefreshBox(refresh) {
                        StockDetailScreen(state = state, padding = inner, symbol = symbol, live = live, range = range, onRangeSelected = { range = it },
                            mentions = mentions, onEventSelected = onEventSelected, fundamentals = fundamentals, onOpenSymbol = { onSymbolSelected(it) })
                    }
                }
            }
            MarketTab.IPOS -> IpoScreen(repository = repository, padding = inner)
            MarketTab.UPDATES -> LazyColumn(
                Modifier.fillMaxSize().padding(horizontal = 18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(top = 10.dp, bottom = OneHandListBottomPadding)
            ) {
                if (marketEvents.isEmpty()) item { EmptyState("No market updates yet.", "Holdings alerts, research views, IPO notices and market moves from your broker and market apps appear here.") }
                items(marketEvents, key = { "mkt-${it.id}" }) { event -> MarketUpdateCard(event) { onEventSelected(event) } }
            }
        }
        OneHandControls(
            filters = MarketTab.entries.map { it.name to it.label },
            selectedFilter = tab.name,
            onFilterSelected = { onTabSelected(it); if (it != MarketTab.STOCKS.name) onSymbolSelected(null) }
        )
    }
}

@Composable
private fun StockSuggestions(query: String, padding: PaddingValues, onSymbolSelected: (String) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    // null = still loading; the instrument master is public and cached for a day.
    val matches by produceState<List<String>?>(null, query) {
        value = runCatching { com.marksy.os.upstox.UpstoxInstruments.suggest(context, query) }.getOrDefault(emptyList())
    }
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 18.dp),
        contentPadding = PaddingValues(top = 8.dp, bottom = padding.calculateBottomPadding())
    ) {
        val list = matches
        when {
            list == null -> item { MarksyLoader("Searching…") }
            list.isEmpty() -> item { EmptyState("No NSE symbol matches \"$query\"", "Press search on the keyboard to look it up anyway.") }
            else -> items(list, key = { "sym-$it" }) { symbol ->
                Text(
                    symbol,
                    color = MarksyTheme.TextPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.fillMaxWidth().clickable { onSymbolSelected(symbol) }.padding(vertical = 12.dp)
                )
            }
        }
    }
}

@Composable
private fun MarketUpdateCard(event: NotificationEventEntity, onClick: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MarksyTheme.Surface),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth().border(1.dp, MarksyTheme.BorderGlow, RoundedCornerShape(14.dp)).clickable(onClick = onClick)
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(event.sourceName, color = MarksyTheme.PrimaryEmerald, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                relativeTime(event.postedAt)?.let { Text(it, color = MarksyTheme.TextMuted, fontSize = 10.sp) }
            }
            Text(event.title, color = MarksyTheme.TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
            if (event.body.isNotBlank()) Text(event.body, color = MarksyTheme.TextSecondary, fontSize = 12.sp, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
        }
    }
}
