package com.marksy.os.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.marksy.os.EmptyState
import com.marksy.os.market.ActivePredictionDto
import com.marksy.os.market.MarketDataState
import com.marksy.os.market.MarketIntelligenceRepository

@Composable
fun PredictionsScreen(repository: MarketIntelligenceRepository, padding: PaddingValues, onOpenSymbol: (String) -> Unit) {
    val state by produceState(MarketDataState.Loading as MarketDataState<com.marksy.os.market.ActivePredictionPageDto>) {
        value = repository.activePredictions()
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(MarksyTheme.Background).padding(horizontal = 18.dp),
        contentPadding = PaddingValues(bottom = padding.calculateBottomPadding() + 20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        when (val s = state) {
            is MarketDataState.Loading -> item { Text("Checking predictions...", color = MarksyTheme.TextMuted, fontSize = 13.sp) }
            is MarketDataState.Unavailable -> item { EmptyState("Market Intelligence is not configured", "Add a Market API key in More → Configure Gateway.") }
            is MarketDataState.Error -> item { EmptyState("Predictions unavailable", s.message) }
            is MarketDataState.Empty -> item { EmptyState("No active predictions", "Marksy has no open predictions right now.") }
            is MarketDataState.Loaded -> items(s.value.items) { prediction -> PredictionRow(prediction, onOpenSymbol) }
            is MarketDataState.Stale -> items(s.value.items) { prediction -> PredictionRow(prediction, onOpenSymbol) }
        }
    }
}

@Composable
private fun PredictionRow(prediction: ActivePredictionDto, onOpenSymbol: (String) -> Unit) {
    Column(
        Modifier.fillMaxWidth().border(1.dp, MarksyTheme.BorderGlow, RoundedCornerShape(12.dp))
            .clickable { onOpenSymbol(prediction.symbol) }.padding(10.dp)
    ) {
        Text(prediction.symbol, color = MarksyTheme.TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Text(prediction.lifecycleState, color = MarksyTheme.PrimaryEmerald, fontSize = 12.sp)
        Text("Target ${prediction.targetPrice} · Stop ${prediction.stopLoss} · Confidence ${prediction.confidence}", color = MarksyTheme.TextSecondary, fontSize = 11.sp)
    }
}
