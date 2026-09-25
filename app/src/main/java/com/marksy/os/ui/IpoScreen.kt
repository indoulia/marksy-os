package com.marksy.os.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.marksy.os.EmptyState
import com.marksy.os.market.IpoListItemDto
import com.marksy.os.market.MarketDataState
import com.marksy.os.market.MarketIntelligenceRepository

private fun List<IpoListItemDto>.openedFirst(): List<IpoListItemDto> =
    sortedByDescending { it.stage?.contains("open", ignoreCase = true) == true }

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
                is MarketDataState.Loaded -> items(s.value.openedFirst()) { ipo -> IpoRow(ipo) }
                is MarketDataState.Stale -> items(s.value.openedFirst()) { ipo -> IpoRow(ipo) }
            }
        }
    }
}

@Composable
private fun IpoRow(ipo: IpoListItemDto) {
    val isOpen = ipo.stage?.contains("open", ignoreCase = true) == true
    val stageTint = if (isOpen) MarksyTheme.PrimaryEmerald else MarksyTheme.TextMuted
    Card(
        colors = CardDefaults.cardColors(containerColor = MarksyTheme.Surface),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth().border(1.dp, if (isOpen) MarksyTheme.PrimaryEmerald else MarksyTheme.BorderGlow, RoundedCornerShape(14.dp))
    ) {
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(ipo.companyName, color = MarksyTheme.TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                ipo.sector?.let { Text(it, color = MarksyTheme.TextMuted, fontSize = 11.sp) }
            }
            Text(
                (ipo.stage ?: "Unknown").uppercase(),
                color = stageTint,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (isOpen) MarksyTheme.BadgeTradingBg else MarksyTheme.SurfaceRaised)
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            )
        }
    }
}
