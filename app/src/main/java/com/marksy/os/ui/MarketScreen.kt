package com.marksy.os.ui

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

    Column(modifier = Modifier.fillMaxSize().background(MarksyTheme.Background)) {
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
            // STOCKS and PREDICTIONS are wired to their real screens in Task 8; IPOS in Task 9.
            // Placeholders here keep this task's build and tests green without a forward
            // reference to composables those tasks haven't created yet.
            MarketTab.STOCKS -> Text("Coming soon", color = MarksyTheme.TextMuted, modifier = Modifier.padding(18.dp))
            MarketTab.PREDICTIONS -> Text("Coming soon", color = MarksyTheme.TextMuted, modifier = Modifier.padding(18.dp))
            MarketTab.IPOS -> Text("Coming soon", color = MarksyTheme.TextMuted, modifier = Modifier.padding(18.dp))
        }
    }
}
