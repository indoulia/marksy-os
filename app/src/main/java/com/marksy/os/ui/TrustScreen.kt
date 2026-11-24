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
import com.marksy.os.MarksyFormat
import com.marksy.os.market.HorizonBucket
import com.marksy.os.market.MarketIntelligenceRepository
import com.marksy.os.market.ScorecardFilterEchoDto
import com.marksy.os.market.ScorecardPeriod
import com.marksy.os.market.ScorecardQuery
import com.marksy.os.market.ScorecardSource
import com.marksy.os.market.ScorecardText
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId

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
    onOpenStock: (String) -> Unit,
    onEcho: (ScorecardFilterEchoDto?) -> Unit = {}
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
            trail = trail, onTrailChange = onTrailChange, onOpenStock = onOpenStock, onEcho = onEcho
        )
        OneHandControls(filters = emptyList(), selectedFilter = "", onFilterSelected = {}, actions = listOf(action))
    }
}

/** The page title: Trust, then the period and horizon the server applied, else the ones asked for (the tabs name the list). */
fun trustTitleNote(query: ScorecardQuery, echo: ScorecardFilterEchoDto? = null): String = "Trust · " + (echo?.let(::applied)?.label() ?: query.label())

/** Under the title: the dates the server counted, then when. */
fun trustTitleSub(echo: ScorecardFilterEchoDto?): String? {
    val e = echo ?: return null
    val range = if (e.startDate != null && e.endDate != null) ScorecardText.range(e) else null
    val at = runCatching { OffsetDateTime.parse(e.asOf).atZoneSameInstant(ZoneId.of("Asia/Kolkata")) }.getOrNull()
    // The range already names the day it ends on.
    val asOf = if (range != null && at != null && at.toLocalDate().toString() == e.endDate) "as of " + MarksyFormat.time(at) else ScorecardText.asOf(e.asOf)
    return listOfNotNull(range, asOf).joinToString(" · ").ifEmpty { null }
}

private fun applied(e: ScorecardFilterEchoDto): ScorecardQuery? = runCatching {
    val custom = e.period == ScorecardPeriod.CUSTOM.name
    ScorecardQuery(
        period = ScorecardPeriod.valueOf(e.period),
        startDate = e.startDate?.takeIf { custom }?.let(LocalDate::parse), endDate = e.endDate?.takeIf { custom }?.let(LocalDate::parse),
        horizon = e.horizon?.let(HorizonBucket::valueOf)
    )
}.getOrNull()
