package com.marksy.os.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.marksy.os.market.MarketIntelligenceRepository

private enum class MarketTab(val label: String) { OVERVIEW("Overview"), STOCKS("Stocks"), PREDICTIONS("Predictions"), IPOS("IPOs") }

@Composable
fun MarketScreen(repository: MarketIntelligenceRepository, padding: PaddingValues) {
    var tab by rememberSaveable { mutableStateOf(MarketTab.OVERVIEW) }
    var selectedSymbol by rememberSaveable { mutableStateOf<String?>(null) }

    BackHandler(enabled = selectedSymbol != null) { selectedSymbol = null }

    Column(modifier = Modifier.fillMaxSize().background(MarksyTheme.Background).padding(top = padding.calculateTopPadding())) {
        TabRow(selectedTabIndex = tab.ordinal, containerColor = MarksyTheme.Surface) {
            MarketTab.entries.forEach { candidate ->
                Tab(selected = tab == candidate, onClick = { tab = candidate; if (candidate != MarketTab.STOCKS) selectedSymbol = null }, text = { Text(candidate.label) })
            }
        }
        when (tab) {
            MarketTab.OVERVIEW -> {
                val state by produceState(com.marksy.os.market.MarketDataState.Loading as com.marksy.os.market.MarketDataState<com.marksy.os.market.MarketSummaryDto>) {
                    while (true) { value = repository.overview(); kotlinx.coroutines.delay(60_000) }
                }
                val health by produceState(com.marksy.os.market.MarketDataState.Loading as com.marksy.os.market.MarketDataState<com.marksy.os.market.LiveFeedHealthDto>) {
                    while (true) { value = repository.liveFeedHealth(); kotlinx.coroutines.delay(60_000) }
                }
                MarketOverviewScreen(state = state, padding = padding, health = health)
            }
            MarketTab.STOCKS -> {
                val symbol = selectedSymbol
                if (symbol == null) {
                    StockSearchPlaceholder(padding = padding, onSymbolChosen = { selectedSymbol = it })
                } else {
                    val state by produceState(com.marksy.os.market.MarketDataState.Loading as com.marksy.os.market.MarketDataState<com.marksy.os.market.InstrumentLifecycleDto>, symbol) {
                        value = repository.instrument(symbol)
                    }
                    StockDetailScreen(state = state, padding = padding, onBack = { selectedSymbol = null })
                }
            }
            MarketTab.PREDICTIONS -> PredictionsScreen(repository = repository, padding = padding, onOpenSymbol = { selectedSymbol = it; tab = MarketTab.STOCKS })
            MarketTab.IPOS -> IpoScreen(repository = repository, padding = padding)
        }
    }
}
