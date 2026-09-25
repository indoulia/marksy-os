package com.marksy.os.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import com.marksy.os.market.MarketIntelligenceRepository

// Predictions live on Trading (Marksy picks), so Market covers the market itself.
private enum class MarketTab(val label: String) { OVERVIEW("Overview"), STOCKS("Stocks"), IPOS("IPOs") }

@Composable
fun MarketScreen(repository: MarketIntelligenceRepository, padding: PaddingValues) {
    var tabName by rememberSaveable { mutableStateOf(MarketTab.OVERVIEW.name) }
    val tab = MarketTab.valueOf(tabName)
    var selectedSymbol by rememberSaveable { mutableStateOf<String?>(null) }

    BackHandler(enabled = selectedSymbol != null) { selectedSymbol = null }

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
                if (symbol == null) {
                    StockSearchPlaceholder(padding = inner, onSymbolChosen = { selectedSymbol = it })
                } else {
                    val refresh = rememberRefreshState()
                    val state by produceState(com.marksy.os.market.MarketDataState.Loading as com.marksy.os.market.MarketDataState<com.marksy.os.market.InstrumentLifecycleDto>, symbol, refresh.key) {
                        value = repository.instrument(symbol)
                        refresh.done()
                    }
                    MarksyRefreshBox(refresh) { StockDetailScreen(state = state, padding = inner, onBack = { selectedSymbol = null }) }
                }
            }
            MarketTab.IPOS -> IpoScreen(repository = repository, padding = inner)
        }
        OneHandControls(
            filters = MarketTab.entries.map { it.name to it.label },
            selectedFilter = tab.name,
            onFilterSelected = { tabName = it; if (it != MarketTab.STOCKS.name) selectedSymbol = null }
        )
    }
}
