package com.marksy.os.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.marksy.os.EmptyState
import com.marksy.os.market.ActivePredictionDto
import com.marksy.os.market.MarketDataState
import com.marksy.os.market.MarketIntelligenceRepository
import com.marksy.os.market.PicksBasis
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Today's setups: Marksy's live calls, best first. Reports' setups are parsed by the server and land in My tips. */
@Composable
internal fun SetupsView(repository: MarketIntelligenceRepository?, bottomPadding: Dp, onOpenStock: (String) -> Unit) {
    val marksy by produceState<MarketDataState<List<ActivePredictionDto>>>(MarketDataState.Loading, repository) {
        value = repository?.activePredictions()?.map { it.items } ?: MarketDataState.Unavailable
    }
    val all = (marksy as? MarketDataState.Loaded)?.value.orEmpty()
    val live = remember(all) {
        all.filter { it.lifecycleState !in INVALIDATED }
            .sortedWith(compareByDescending<ActivePredictionDto> { it.isActionableNow }.thenByDescending { it.compositeOpportunityScore ?: -1.0 })
    }
    val quotes = rememberUpstoxQuotes(remember(live) { live.map { it.symbol }.distinct() })
    val scan by MarketIntelligenceRepository.latestScan.collectAsState()

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 18.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(top = 10.dp, bottom = bottomPadding)
    ) {
        when (val m = marksy) {
            is MarketDataState.Loading -> item { MarksyLoader("Loading Marksy setups...") }
            is MarketDataState.Error -> item { EmptyState("Marksy setups unavailable", m.message) }
            is MarketDataState.Unavailable -> item { EmptyState("Marksy is not connected", "Sign in to your Marksy account in More.") }
            else -> if (live.isEmpty()) item {
                EmptyState(
                    "No live Marksy setups right now",
                    if (all.isNotEmpty()) "All ${all.size} open calls are invalidated. See Predictions for their history."
                    else "Marksy's new calls appear here after each market close." + scan?.let(PicksBasis::label)?.let { " Last scan: $it." }.orEmpty()
                )
            }
        }
        items(live, key = { "m-${it.predictionId}" }) { p ->
            val basis = PicksBasis.label(p) ?: p.lastPriceAt?.let { "as of ${asOf(it)}" }
            OpenCallRow(p, quotes[p.symbol]?.lastPrice, note = "Marksy${basis?.let { " · $it" }.orEmpty()}") { onOpenStock(p.symbol) }
        }
    }
}

private val TIME = DateTimeFormatter.ofPattern("d MMM, HH:mm", Locale.getDefault())
private fun asOf(iso: String) = runCatching { OffsetDateTime.parse(iso).atZoneSameInstant(ZoneId.systemDefault()).format(TIME) }.getOrDefault(iso.take(16).replace('T', ' '))
