package com.marksy.os.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.marksy.os.market.MarketIntelligenceRepository
import com.marksy.os.market.ScorecardPeriod
import com.marksy.os.market.ScorecardQuery
import com.marksy.os.market.ScorecardSource

/** Which parts of the filter a dialog asks for: only those the page has no pills for. */
private data class TrustFilter(val query: ScorecardQuery, val periods: Boolean, val horizon: Boolean)

/** Trust tab: who has been right. Scorecards for channels, finfluencers and Marksy engines; a row opens its source. */
@Composable
fun TrustScreen(
    repository: MarketIntelligenceRepository,
    padding: PaddingValues,
    query: ScorecardQuery,
    onQueryChange: (ScorecardQuery) -> Unit,
    trail: List<ScorecardSource>,
    onTrailChange: (List<ScorecardSource>) -> Unit,
    onOpenStock: (String) -> Unit
) {
    var filtering by remember { mutableStateOf<TrustFilter?>(null) }
    filtering?.let { f -> ScorecardFilterDialog(f.query, onApply = { onQueryChange(it); filtering = null }, periods = f.periods, horizon = f.horizon) { filtering = null } }
    // The page shows the tabs and the four period pills; the filter button holds the custom range and the horizon.
    val action = if (trail.isNotEmpty()) {
        FloatingAction(Icons.Default.FilterList, "Period and horizon", active = query.period != ScorecardPeriod.LIFETIME || query.horizon != null) {
            filtering = TrustFilter(query, periods = true, horizon = true)
        }
    } else {
        FloatingAction(Icons.Default.FilterList, "Period and horizon", active = query.period == ScorecardPeriod.CUSTOM || query.horizon != null) {
            filtering = TrustFilter(query, periods = true, horizon = true)
        }
    }
    Box(Modifier.fillMaxSize().background(MarksyTheme.Background).padding(padding).consumeWindowInsets(padding)) {
        ScorecardsView(
            repository, query, oneHandStackBottomPadding(1), onQueryChange, onEditFilter = { filtering = TrustFilter(it, periods = false, horizon = false) },
            trail = trail, onTrailChange = onTrailChange, onOpenStock = onOpenStock
        )
        OneHandControls(filters = emptyList(), selectedFilter = "", onFilterSelected = {}, actions = listOf(action))
    }
}

/** The page title: Trust, then the scorecard period and horizon (the tabs name the list). */
fun trustTitleNote(query: ScorecardQuery): String = "Trust · " + query.label()
