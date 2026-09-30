package com.marksy.os.ui

import androidx.compose.material.icons.filled.NotificationsActive
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
import androidx.compose.material.icons.filled.SwapVert
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
    onSymbolBack: () -> Unit = { onSymbolSelected(null) },
    stockQuery: String = "",
    marketEvents: List<NotificationEventEntity> = emptyList(),
    stockEvents: List<NotificationEventEntity> = emptyList(),
    onEventSelected: (NotificationEventEntity) -> Unit = {},
    onOpenStock: ((String) -> Unit)? = null
) {
    val tab = MarketTab.entries.firstOrNull { it.name == tabName } ?: MarketTab.OVERVIEW

    BackHandler(enabled = selectedSymbol != null) { onSymbolBack() }
    // The open stock's trade ticket defaults, refreshed as its price and Marksy call load.
    var stockTrade by remember { mutableStateOf<TradeIntent?>(null) }
    var ticket by remember { mutableStateOf<TradeIntent?>(null) }
    var alerting by remember { mutableStateOf<TradeIntent?>(null) }
    ticket?.let { TradeTicketSheet(it) { ticket = null } }
    var openTip by remember { mutableStateOf<String?>(null) }
    openTip?.let { TipDetailDialog(repository, it, onOpenStock = null) { openTip = null } }

    // Section switching uses the same bottom-right floating filter as Inbox and Trading.
    Box(Modifier.fillMaxSize().background(MarksyTheme.Background).padding(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding())) {
        val inner = PaddingValues(bottom = OneHandListBottomPadding)
        when (tab) {
            MarketTab.OVERVIEW -> {
                val refresh = rememberRefreshState()
                val cached = repository.lastOverview
                val state by produceState(cached?.let { com.marksy.os.market.MarketDataState.Stale(it, ageSeconds = null) } ?: com.marksy.os.market.MarketDataState.Loading as com.marksy.os.market.MarketDataState<com.marksy.os.market.MarketSummaryDto>, refresh.key) {
                    while (true) {
                        val fresh = repository.overview()
                        // A failed refresh keeps the last good overview on screen, marked stale, instead of an error page.
                        value = if (fresh !is com.marksy.os.market.MarketDataState.Loaded && cached != null) com.marksy.os.market.MarketDataState.Stale(repository.lastOverview ?: cached, ageSeconds = null) else fresh
                        refresh.done(); kotlinx.coroutines.delay(60_000)
                    }
                }
                val health by produceState(com.marksy.os.market.MarketDataState.Loading as com.marksy.os.market.MarketDataState<com.marksy.os.market.LiveFeedHealthDto>, refresh.key) {
                    while (true) { value = repository.liveFeedHealth(); kotlinx.coroutines.delay(60_000) }
                }
                MarksyRefreshBox(refresh) { MarketOverviewScreen(state = state, padding = inner, health = health, onOpenStock = onOpenStock) }
            }
            MarketTab.STOCKS -> {
                val symbol = selectedSymbol
                val query = stockQuery.trim()
                if (query.length >= 3 && !query.equals(symbol, ignoreCase = true)) {
                    StockSuggestions(query, inner, onSymbolSelected)
                } else if (symbol == null) {
                    Box(Modifier.padding(18.dp)) { EmptyState("Look up a stock", "Tap search above and type a symbol, e.g. RELIANCE.") }
                } else {
                    val refresh = rememberRefreshState()
                    val state by produceState(com.marksy.os.market.MarketDataState.Loading as com.marksy.os.market.MarketDataState<com.marksy.os.market.InstrumentLifecycleDto>, symbol, refresh.key) {
                        value = repository.instrument(symbol, includeCalls = true)
                        refresh.done()
                    }
                    var range by rememberSaveable(symbol) { mutableStateOf(com.marksy.os.upstox.ChartRange.D1) }
                    var minutes by rememberSaveable(symbol, range) { mutableStateOf<Int?>(null) }
                    val live = rememberStockLive(symbol, range, refresh.key, minutes)
                    val fundamentals = rememberStockFundamentals(live.key, refresh.key)
                    val instrument = when (val st = state) { is com.marksy.os.market.MarketDataState.Loaded -> st.value; is com.marksy.os.market.MarketDataState.Stale -> st.value; else -> null }
                    val analysisId = remember(instrument) { instrument?.let { com.marksy.os.market.LedgerCalls.analysisRecommendationId(it.calls, it.predictions) } }
                    val analysis by produceState<org.json.JSONObject?>(null, analysisId, refresh.key) {
                        value = analysisId?.let { (repository.recommendation(it) as? com.marksy.os.market.MarketDataState.Loaded)?.value }
                    }
                    val words = remember(symbol, state) {
                        listOfNotNull(symbol, (state as? com.marksy.os.market.MarketDataState.Loaded)?.value?.companyName?.substringBefore(" Limited")?.substringBefore(" Ltd"))
                            .map { Regex("\\b${Regex.escape(it)}\\b", RegexOption.IGNORE_CASE) }
                    }
                    val mentions = remember(stockEvents, words) { stockEvents.filter { e -> words.any { it.containsMatchIn("${e.title} ${e.body}") } } }
                    val call = remember(instrument) { com.marksy.os.market.LedgerCalls.leadingMarksyCall(instrument?.calls) }
                    SideEffect {
                        stockTrade = TradeIntent(
                            symbol, if (call?.direction == "SELL") TradeSide.SELL else TradeSide.BUY,
                            live.quote?.lastPrice, call?.target, call?.stopLoss
                        )
                    }
                    MarksyRefreshBox(refresh) {
                        StockDetailScreen(state = state, padding = inner, symbol = symbol, live = live, range = range, onRangeSelected = { range = it },
                            minutes = minutes, onMinutesSelected = { minutes = it },
                            mentions = mentions, onEventSelected = onEventSelected, fundamentals = fundamentals, onOpenSymbol = { onSymbolSelected(it) }, analysis = analysis, onOpenTip = { openTip = it })
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
            onFilterSelected = { onTabSelected(it); if (it != MarketTab.STOCKS.name) onSymbolSelected(null) },
            actions = listOfNotNull(
                stockTrade?.takeIf { tab == MarketTab.STOCKS && it.symbol == selectedSymbol }
                    ?.let { t -> FloatingAction(androidx.compose.material.icons.Icons.Default.NotificationsActive, "Price alert for ${t.symbol}") { alerting = t } },
                stockTrade?.takeIf { tab == MarketTab.STOCKS && it.symbol == selectedSymbol }
                    ?.let { t -> FloatingAction(androidx.compose.material.icons.Icons.Default.SwapVert, "Buy or sell ${t.symbol}") { ticket = t } }
            )
        )
        alerting?.let { t -> PriceAlertDialog(t.symbol, t.price) { alerting = null } }
    }
}

@Composable
internal fun StockSuggestions(
    query: String,
    padding: PaddingValues,
    onSymbolSelected: (String) -> Unit,
    emptyHint: String = "Press search on the keyboard to look it up anyway."
) {
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
            list.isEmpty() -> item { EmptyState("No NSE symbol matches \"$query\"", emptyHint) }
            else -> items(list, key = { "sym-$it" }) { symbol ->
                Row(Modifier.fillMaxWidth().clickable { onSymbolSelected(symbol) }, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text(
                        symbol,
                        color = MarksyTheme.TextPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.weight(1f).padding(vertical = 12.dp)
                    )
                    WatchlistButton(symbol)
                }
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
                compactTime(event.postedAt)?.let { Text(it, color = MarksyTheme.TextMuted, fontSize = 10.sp) }
            }
            Text(event.title, color = MarksyTheme.TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
            if (event.body.isNotBlank()) Text(event.body, color = MarksyTheme.TextSecondary, fontSize = 12.sp, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
        }
    }
}
