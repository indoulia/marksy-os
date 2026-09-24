package com.marksy.os.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.marksy.os.EmptyState
import com.marksy.os.market.IpoListItemDto
import com.marksy.os.market.MarketDataState
import com.marksy.os.market.MarketIntelligenceRepository

@Composable
fun IpoScreen(repository: MarketIntelligenceRepository, padding: PaddingValues) {
    var selectedStage by rememberSaveable { mutableStateOf<String?>(null) }

    val countsState by produceState(MarketDataState.Loading as MarketDataState<com.marksy.os.market.IpoStageCountsDto>) {
        value = repository.ipoStageCounts()
    }
    val listState by produceState(MarketDataState.Loading as MarketDataState<List<IpoListItemDto>>, selectedStage) {
        value = repository.ipos(stage = selectedStage)
    }

    Column(Modifier.fillMaxSize().background(MarksyTheme.Background)) {
        val counts = (countsState as? MarketDataState.Loaded)?.value
        if (counts != null && counts.byStage.isNotEmpty()) {
            LazyRow(contentPadding = PaddingValues(horizontal = 18.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    FilterChip(selected = selectedStage == null, onClick = { selectedStage = null }, label = { Text("All (${counts.total})") })
                }
                items(counts.byStage.entries.toList()) { (stage, count) ->
                    FilterChip(selected = selectedStage == stage, onClick = { selectedStage = stage }, label = { Text("$stage ($count)") })
                }
            }
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(horizontal = 18.dp),
            contentPadding = PaddingValues(bottom = padding.calculateBottomPadding() + 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            when (val s = listState) {
                is MarketDataState.Loading -> item { Text("Checking IPOs...", color = MarksyTheme.TextMuted, fontSize = 13.sp) }
                is MarketDataState.Unavailable -> item { EmptyState("Market Intelligence is not configured", "Add a Market API key in More → Configure Gateway.") }
                is MarketDataState.Error -> item { EmptyState("IPO data unavailable", s.message) }
                is MarketDataState.Empty -> item { EmptyState("No IPOs", "No issues match this filter right now.") }
                is MarketDataState.Loaded -> items(s.value) { ipo -> IpoRow(ipo) }
                is MarketDataState.Stale -> items(s.value) { ipo -> IpoRow(ipo) }
            }
        }
    }
}

@Composable
private fun IpoRow(ipo: IpoListItemDto) {
    Column(Modifier.fillMaxWidth().border(1.dp, MarksyTheme.BorderGlow, RoundedCornerShape(12.dp)).padding(10.dp)) {
        Text(ipo.companyName, color = MarksyTheme.TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Text(ipo.stage ?: "Stage not established", color = MarksyTheme.TextSecondary, fontSize = 12.sp)
    }
}
