package com.marksy.os.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.marksy.os.EmptyState
import com.marksy.os.market.InstrumentLifecycleDto
import com.marksy.os.market.InstrumentPredictionEntryDto
import com.marksy.os.market.MarketDataState

@Composable
fun StockDetailScreen(state: MarketDataState<InstrumentLifecycleDto>, padding: PaddingValues, onBack: () -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().background(MarksyTheme.Background).padding(horizontal = 18.dp),
        contentPadding = PaddingValues(bottom = padding.calculateBottomPadding() + 20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            TextButton(onClick = onBack) { Text("Back to search", color = MarksyTheme.PrimaryEmerald, fontSize = 13.sp) }
        }
        when (state) {
            is MarketDataState.Loading -> item { MarksyLoader("Checking instrument...") }
            is MarketDataState.Unavailable -> item { EmptyState("Market Intelligence is not configured", "Add a Market API key in More → Configure Gateway.") }
            is MarketDataState.Error -> item { EmptyState("Instrument unavailable", state.message) }
            is MarketDataState.Empty -> item { EmptyState("Not found", "No instrument matches that symbol.") }
            is MarketDataState.Loaded -> instrumentContent(state.value)
            is MarketDataState.Stale -> instrumentContent(state.value)
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.instrumentContent(instrument: InstrumentLifecycleDto) {
    item {
        Text(instrument.companyName ?: instrument.symbol, color = MarksyTheme.TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Text("${instrument.symbol} · ${instrument.exchange}", color = MarksyTheme.TextSecondary, fontSize = 12.sp)
    }
    item {
        val market = instrument.market
        val priceLine = if (market.lastClosePrice != null) "Last close: ${market.lastClosePrice}" else "Last close: unavailable"
        val freshnessDetails = listOfNotNull(market.asOfSessionDate, market.freshnessState)
        val line = if (freshnessDetails.isNotEmpty()) "$priceLine (${freshnessDetails.joinToString(", ")})" else priceLine
        Text(line, color = MarksyTheme.TextSecondary, fontSize = 13.sp)
    }
    if (instrument.predictions.isEmpty()) {
        item { EmptyState("No predictions yet", "Marksy has not published a prediction for this instrument.") }
    } else {
        item { Text("Prediction history", color = MarksyTheme.TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }
        items(instrument.predictions) { prediction -> PredictionHistoryRow(prediction) }
    }
}

@Composable
private fun PredictionHistoryRow(prediction: InstrumentPredictionEntryDto) {
    Column(Modifier.fillMaxWidth().border(1.dp, MarksyTheme.BorderGlow, RoundedCornerShape(12.dp)).padding(10.dp)) {
        Text(prediction.lifecycleState, color = MarksyTheme.PrimaryEmerald, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Text(prediction.lifecycleDetail, color = MarksyTheme.TextSecondary, fontSize = 12.sp)
        Text("Entry ${prediction.entryPrice} · Target ${prediction.targetPrice ?: "-"} · Stop ${prediction.stopLoss ?: "-"}", color = MarksyTheme.TextMuted, fontSize = 11.sp)
        Text("${prediction.evidenceItemCount} evidence item(s) recorded", color = MarksyTheme.TextMuted, fontSize = 11.sp)
    }
}
