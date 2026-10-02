package com.marksy.os.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.NotificationsNone
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.marksy.os.market.MarketIntelligenceRepository
import com.marksy.os.market.ScorecardPeriod
import com.marksy.os.market.ScorecardQuery
import com.marksy.os.market.ScorecardSource

/** Trust tab: who has been right. Scorecards for channels, callers and Marksy engines; a row opens its source. */
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
    var filtering by remember { mutableStateOf<ScorecardQuery?>(null) }
    filtering?.let { initial -> ScorecardFilterDialog(initial, onApply = { onQueryChange(it); filtering = null }) { filtering = null } }
    val followed by MarketIntelligenceRepository.followed.collectAsState()
    val toggleFollow = rememberFollowToggle(repository)
    val openSource = trail.lastOrNull()
    val actions = listOfNotNull(
        openSource?.let { s -> followed?.let { set ->
            val on = s.followKey in set
            FloatingAction(if (on) Icons.Default.NotificationsActive else Icons.Default.NotificationsNone, if (on) "Unfollow ${s.name}" else "Follow ${s.name}") { toggleFollow(s.followKey, s.name, !on) }
        } },
        FloatingAction(
            Icons.Default.FilterList, if (openSource != null) "Period and horizon" else "Filter scorecards",
            active = query.period != ScorecardPeriod.LIFETIME || query.horizon != null
        ) { filtering = query }
    )
    Box(Modifier.fillMaxSize().background(MarksyTheme.Background).padding(padding).consumeWindowInsets(padding)) {
        ScorecardsView(
            repository, query, oneHandStackBottomPadding(actions.size), onQueryChange, onEditFilter = { filtering = it },
            trail = trail, onTrailChange = onTrailChange, onOpenStock = onOpenStock
        )
        OneHandControls(filters = emptyList(), selectedFilter = "", onFilterSelected = {}, actions = actions)
    }
}

/** Title superscript: the scorecard filter; the list name drops while a source is open. */
fun trustTitleNote(query: ScorecardQuery, sourceOpen: Boolean): String =
    if (sourceOpen) query.label() else "${query.entity.label} · ${query.label()}"
