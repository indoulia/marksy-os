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
import java.util.Locale

@Composable
fun MarketOverviewScreen(
    state: MarketDataState<MarketSummaryDto>,
    padding: PaddingValues,
    health: MarketDataState<com.marksy.os.market.LiveFeedHealthDto> = MarketDataState.Unavailable
) {
    // The footer's source/freshness wording comes from the feed's own reported state
    // (`/market/live/health`), never a client-invented threshold on `summary.asOf`.
    val freshnessLabel = when (health) {
        is MarketDataState.Loaded -> "Data: Upstox · live"
        is MarketDataState.Stale -> "Data: Upstox · ${health.value.feedState.lowercase()}${if (health.value.fallbackActive) " (fallback)" else ""}"
        else -> "Data: Upstox · feed status unknown"
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(MarksyTheme.Background).padding(horizontal = 18.dp),
        contentPadding = PaddingValues(bottom = padding.calculateBottomPadding() + 20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        when (state) {
            is MarketDataState.Loading -> item { Text("Checking market...", color = MarksyTheme.TextMuted, fontSize = 13.sp) }
            is MarketDataState.Unavailable -> item { EmptyState("Market Intelligence is not configured", "Add a Market API key in More → Configure Gateway.") }
            is MarketDataState.Error -> item { EmptyState("Market data unavailable", state.message) }
            is MarketDataState.Empty -> item { EmptyState("No market data", "Nothing to show right now.") }
            is MarketDataState.Loaded -> overviewContent(state.value, freshnessLabel)
            is MarketDataState.Stale -> overviewContent(state.value, freshnessLabel)
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.overviewContent(summary: MarketSummaryDto, freshnessLabel: String) {
    item { Text(summary.marketStatus, color = MarksyTheme.PrimaryEmerald, fontSize = 16.sp, fontWeight = FontWeight.Bold) }
    items(summary.indexes) { index -> TickerRow(index.name, formatIndexValue(index.value), signedPct(index.changePct)) }
    if (summary.topGainers.isNotEmpty()) {
        item { Text("Gainers", color = MarksyTheme.TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
        items(summary.topGainers) { mover -> TickerRow(mover.symbol, mover.name, signedPct(mover.changePercent)) }
    }
    if (summary.topLosers.isNotEmpty()) {
        item { Text("Losers", color = MarksyTheme.TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
        items(summary.topLosers) { mover -> TickerRow(mover.symbol, mover.name, signedPct(mover.changePercent)) }
    }
    item { Text(freshnessLabel, color = MarksyTheme.TextMuted, fontSize = 11.sp) }
}

@Composable
private fun TickerRow(name: String, subtitle: String, change: String) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MarksyTheme.Surface),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth().border(1.dp, MarksyTheme.BorderGlow, RoundedCornerShape(14.dp))
    ) {
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(name, color = MarksyTheme.TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text(subtitle, color = MarksyTheme.TextMuted, fontSize = 11.sp)
            }
            Text(change, color = changeColor(change), fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
    }
}

private fun changeColor(change: String): Color = if (change.startsWith("-")) MarksyTheme.RedUrgent else MarksyTheme.PrimaryEmerald
private fun signedPct(value: Double): String = String.format(Locale.US, "%+.2f%%", value)
private fun formatIndexValue(value: Double): String = String.format(Locale.getDefault(), "%,.2f", value)
