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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BookmarkAdded
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.marksy.os.EmptyState
import com.marksy.os.market.IpoListItemDto
import com.marksy.os.market.MarketDataState
import com.marksy.os.market.MarketIntelligenceRepository
import com.marksy.os.market.display
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/** What a user acts on first: open issues, then ones closing soon, then upcoming, then history. */
private fun stageRank(stage: String): Int {
    val s = stage.uppercase()
    return when {
        s.contains("CLOS") && (s.contains("SOON") || s.contains("TODAY")) -> 1
        s == "OPEN" || s.startsWith("OPEN") -> 0
        s.contains("UPCOMING") || s.contains("ANNOUNC") -> 2
        s.contains("ALLOT") -> 3
        s.contains("LIST") -> 4
        else -> 5
    }
}

private fun stageLabel(stage: String) = stage.lowercase().replace('_', ' ').replaceFirstChar { it.titlecase() }

private const val WATCHING = "WATCHING"

@Composable
fun IpoScreen(repository: MarketIntelligenceRepository, padding: PaddingValues) {
    var selectedStage by rememberSaveable { mutableStateOf<String?>(null) }
    var opened by remember { mutableStateOf<IpoListItemDto?>(null) }
    val refresh = rememberRefreshState()
    // Server-side watch list; null until it loads so rows don't flash as unwatched.
    var watched by remember { mutableStateOf<Set<String>?>(null) }
    LaunchedEffect(refresh.key) {
        watched = when (val t = repository.trackedIpos()) {
            is MarketDataState.Loaded -> t.value.mapTo(HashSet()) { it.ipoId }
            is MarketDataState.Empty -> emptySet()
            else -> watched ?: emptySet()
        }
    }
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    val toggleWatch: (IpoListItemDto) -> Unit = { ipo ->
        val before = watched.orEmpty()
        val want = ipo.id !in before
        watched = if (want) before + ipo.id else before - ipo.id
        scope.launch {
            when (val r = repository.setIpoTracking(ipo.id, want)) {
                is MarketDataState.Loaded -> watched = if (r.value.tracking) watched.orEmpty() + ipo.id else watched.orEmpty() - ipo.id
                else -> {
                    watched = before
                    android.widget.Toast.makeText(context, "Couldn't ${if (want) "watch" else "unwatch"} ${ipo.companyName}", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
    opened?.let { ipo ->
        androidx.activity.compose.BackHandler { opened = null }
        IpoDetailScreen(repository, ipo, padding, watched = ipo.id in watched.orEmpty(), onToggleWatch = { toggleWatch(ipo) })
        return
    }

    val countsState by produceState(MarketDataState.Loading as MarketDataState<com.marksy.os.market.IpoStageCountsDto>, refresh.key) {
        value = repository.ipoStageCounts()
    }
    val watchCount = watched.orEmpty().size
    val stages = (if (watchCount > 0) listOf(WATCHING to watchCount) else emptyList()) +
        (countsState as? MarketDataState.Loaded)?.value?.byStage.orEmpty()
            .filterValues { it > 0 }.entries.sortedWith(compareBy({ stageRank(it.key) }, { it.key })).map { it.key to it.value }
    // No "All": default to the most actionable stage (Open) once counts arrive.
    LaunchedEffect(stages) {
        if (selectedStage == null || stages.none { it.first == selectedStage }) selectedStage = stages.firstOrNull { it.first != WATCHING }?.first ?: stages.firstOrNull()?.first
    }
    val watchKey = if (selectedStage == WATCHING) watched else null
    val listState by produceState(MarketDataState.Loading as MarketDataState<List<IpoListItemDto>>, selectedStage, refresh.key, watchKey) {
        val stage = selectedStage ?: return@produceState
        if (stage == WATCHING) {
            value = watchedIpos(repository, watched.orEmpty())
            refresh.done()
            return@produceState
        }
        value = when (val fetched = repository.ipos(stage = stage)) {
            // Keep only the chosen stage even if the server returns more than asked for.
            is MarketDataState.Loaded -> fetched.value.filter { it.stage.equals(stage, ignoreCase = true) }.let { if (it.isEmpty()) MarketDataState.Empty else MarketDataState.Loaded(it) }
            else -> fetched
        }
        refresh.done()
    }

    val chipsHeader = rememberCollapsingHeaderState()
    Column(Modifier.fillMaxSize().background(MarksyTheme.Background).nestedScroll(chipsHeader.connection)) {
        if (stages.isNotEmpty()) {
            LazyRow(Modifier.collapsingHeader(chipsHeader), contentPadding = PaddingValues(horizontal = 18.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(stages) { (stage, count) ->
                    Pill("${if (stage == WATCHING) "★ Watching" else stageLabel(stage)} ($count)", selected = selectedStage == stage) { selectedStage = stage }
                }
            }
        }
        MarksyRefreshBox(refresh) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(horizontal = 18.dp),
            contentPadding = PaddingValues(bottom = padding.calculateBottomPadding() + 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Counts arrived with no stages at all: nothing to select, so don't wait on a list forever.
            val noStages = countsState is MarketDataState.Loaded && stages.isEmpty()
            when (val s = if (noStages) MarketDataState.Empty else listState) {
                is MarketDataState.Loading -> item { MarksyLoader("Checking IPOs...") }
                is MarketDataState.Unavailable -> item { EmptyState("Market Intelligence is not configured", "Add a Market API key in More → Configure Gateway.") }
                is MarketDataState.Error -> item { EmptyState("IPO data unavailable", s.message) }
                is MarketDataState.Empty -> item { EmptyState("No IPOs", if (selectedStage == WATCHING) "Tap the bookmark on an IPO to watch it." else "No issues match this filter right now.") }
                is MarketDataState.Loaded -> items(s.value, key = { it.id }) { ipo -> IpoRow(ipo, ipo.id in watched.orEmpty()) { opened = ipo } }
                is MarketDataState.Stale -> items(s.value, key = { it.id }) { ipo -> IpoRow(ipo, ipo.id in watched.orEmpty()) { opened = ipo } }
            }
        }
        }
    }
}

/** Watched issues across every stage, most actionable first; ones Marksy no longer returns are dropped. */
private suspend fun watchedIpos(repository: MarketIntelligenceRepository, ids: Set<String>): MarketDataState<List<IpoListItemDto>> = coroutineScope {
    if (ids.isEmpty()) return@coroutineScope MarketDataState.Empty
    val results = ids.map { id -> async { repository.ipoDetail(id) } }.awaitAll()
    val found = results.mapNotNull { (it as? MarketDataState.Loaded)?.value?.summary?.takeIf { s -> s.id.isNotBlank() } }
    when {
        found.isNotEmpty() -> MarketDataState.Loaded(found.sortedWith(compareBy({ stageRank(it.stage.orEmpty()) }, { it.companyName })))
        results.first() is MarketDataState.Error -> MarketDataState.Error((results.first() as MarketDataState.Error).message)
        else -> MarketDataState.Empty
    }
}

@Composable
private fun IpoRow(ipo: IpoListItemDto, watched: Boolean = false, onClick: () -> Unit = {}) {
    val isOpen = ipo.stage?.contains("open", ignoreCase = true) == true
    val stageTint = if (isOpen) MarksyTheme.PrimaryEmerald else MarksyTheme.TextMuted
    Card(
        onClick = onClick,
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(ipo.companyName, color = MarksyTheme.TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f, fill = false))
                    if (watched) Icon(Icons.Filled.BookmarkAdded, "Watching", tint = MarksyTheme.PrimaryEmerald, modifier = Modifier.padding(start = 4.dp).size(14.dp))
                }
                ipo.sector?.let { Text(it, color = MarksyTheme.TextMuted, fontSize = 11.sp) }
                // Enough to decide whether to open it: when, what price, how much per lot.
                val facts = listOfNotNull(
                    ipo.opensOn.display()?.let { o -> ipo.closesOn.display()?.let { "$o – $it" } ?: "Opens $o" },
                    ipo.terms?.priceBand.display("₹"), ipo.terms?.lotSize.display()?.let { "Lot $it" }, if (ipo.isSme) "SME" else null
                )
                if (facts.isNotEmpty()) Text(facts.joinToString(" · "), color = MarksyTheme.TextSecondary, fontSize = 11.sp)
            }
            Text(
                (ipo.stage ?: "Unknown").replace('_', ' ').uppercase(),
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
