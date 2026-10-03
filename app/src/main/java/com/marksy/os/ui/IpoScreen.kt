package com.marksy.os.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BookmarkAdded
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.outlined.BookmarkAdd
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.marksy.os.EmptyState
import com.marksy.os.data.MarksyContainer
import com.marksy.os.market.IpoLifecycle
import com.marksy.os.market.IpoLifecycle.Board
import com.marksy.os.market.IpoLifecycle.Lane
import com.marksy.os.market.IpoLifecycle.StageFilter
import com.marksy.os.market.IpoDetailDto
import com.marksy.os.market.IpoListItemDto
import com.marksy.os.market.localDate
import com.marksy.os.market.MarketDataState
import com.marksy.os.market.MarketIntelligenceRepository
import com.marksy.os.market.display
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.ZonedDateTime

/** IPO home: open issues first, other stages from the filter; Listed pages in as it scrolls. */
@Composable
fun IpoScreen(repository: MarketIntelligenceRepository, padding: PaddingValues, onSectionSelected: (String) -> Unit = {}, onTitleNote: (String?) -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val notice = rememberNotice()
    val now by produceState(ZonedDateTime.now(IpoLifecycle.IST)) {
        while (true) { delay(30_000); value = ZonedDateTime.now(IpoLifecycle.IST) }
    }
    var stageName by rememberSaveable { mutableStateOf(StageFilter.OPEN.name) }
    var boardName by rememberSaveable { mutableStateOf(Board.ALL.name) }
    val stage = StageFilter.valueOf(stageName)
    val board = Board.valueOf(boardName)
    var query by rememberSaveable { mutableStateOf("") }
    var openedId by rememberSaveable { mutableStateOf<String?>(null) }
    var listedOpen by rememberSaveable { mutableStateOf(false) }
    var remindersOpen by rememberSaveable { mutableStateOf(false) }

    // Loaded above the detail branch so back returns to the same list and scroll position.
    val refresh = rememberRefreshState()
    var state by remember { mutableStateOf<MarketDataState<List<IpoListItemDto>>>(MarketDataState.Loading) }
    var counts by remember { mutableStateOf<Map<String, Int>?>(null) }
    LaunchedEffect(refresh.key) {
        val previous = when (val s = state) { is MarketDataState.Loaded -> s.value; is MarketDataState.Stale -> s.value; else -> emptyList() }
        val c = (repository.ipoStageCounts() as? MarketDataState.Loaded)?.value?.byStage
        if (c != null) counts = c
        state = liveIpos(repository, c, previous).also {
            if (it is MarketDataState.Stale) notice("Couldn't refresh every IPO stage; some rows may be out of date")
        }
        refresh.done()
    }
    val listState = rememberLazyListState()
    // Hundreds of listed issues, so they arrive a page at a time; All takes page one for the fold's best/worst.
    val listed = remember(refresh.key) { Paged { c -> repository.iposPage(stage = IpoLifecycle.LISTED_STAGE, limit = LISTED_PAGE, cursor = c).map { it.items to it.nextCursor } } }
    LaunchedEffect(listed, stage) { if ((stage == StageFilter.ALL || stage == StageFilter.LISTED) && listed.items.isEmpty()) listed.more() }

    // Server-side watch list; null until it loads so rows don't flash as unwatched.
    var watched by remember { mutableStateOf<Set<String>?>(null) }
    LaunchedEffect(refresh.key) {
        watched = when (val t = repository.trackedIpos()) {
            is MarketDataState.Loaded -> t.value.mapTo(HashSet()) { it.ipoId }
            is MarketDataState.Empty -> emptySet()
            else -> watched ?: emptySet()
        }
    }
    val toggleWatch: (IpoListItemDto) -> Unit = { ipo ->
        val before = watched.orEmpty()
        val want = ipo.id !in before
        watched = if (want) before + ipo.id else before - ipo.id
        scope.launch {
            when (val r = repository.setIpoTracking(ipo.id, want)) {
                is MarketDataState.Loaded -> watched = if (r.value.tracking) watched.orEmpty() + ipo.id else watched.orEmpty() - ipo.id
                else -> {
                    watched = before
                    notice("Couldn't ${if (want) "watch" else "unwatch"} ${ipo.companyName}")
                }
            }
        }
    }

    val plan = remember { MarksyContainer.plan(context) }
    val reminders by remember { plan.observeIpoReminders("ipo|") }.collectAsState(emptyMap())
    val reminderKeys = reminders.keys
    val setReminder: (IpoListItemDto, IpoLifecycle.ReminderEvent, Boolean) -> Unit = { ipo, e, on ->
        // The clock ticks every 30 s, so an event can pass between render and tap.
        if (!on || e.at.isAfter(ZonedDateTime.now(IpoLifecycle.IST)))
            scope.launch { plan.setIpoReminder(IpoLifecycle.reminderKey(ipo.id, e.key), "${ipo.companyName}: ${e.title.lowercase()}", if (on) e.at.toInstant().toEpochMilli() else null) }
    }

    val live = when (val s = state) { is MarketDataState.Loaded -> s.value; is MarketDataState.Stale -> s.value; else -> emptyList() }
    // Watched issues outside the loaded pages, fetched by id.
    var extra by remember(refresh.key) { mutableStateOf<List<IpoListItemDto>>(emptyList()) }
    LaunchedEffect(watched, state, refresh.key) {
        val have = (live + listed.items + extra).mapTo(HashSet()) { it.id }
        val missing = watched.orEmpty().filter { it !in have }.take(MAX_IDS)
        if (missing.isNotEmpty()) (repository.iposPage(ids = missing, limit = MAX_IDS) as? MarketDataState.Loaded)?.let { r -> extra = (extra + r.value.items).distinctBy { it.id } }
    }
    // Every search asks the server, which also matches names a later source corrected; hits page in like Listed.
    val search = remember(query.trim(), refresh.key) {
        query.trim().takeIf { it.isNotEmpty() }?.let { q -> Paged { c -> repository.iposPage(query = q, limit = SEARCH_PAGE, cursor = c).map { it.items to it.nextCursor } } }
    }
    LaunchedEffect(search) { if (search != null) { delay(300); search.more() } }
    // Open is the home, not a chosen filter, so a search from it looks at every stage.
    val shownStage = if (search != null && stage == StageFilter.OPEN) StageFilter.ALL else stage
    val items = remember(live, listed.items, extra, search?.items) { (live + listed.items + extra + search?.items.orEmpty()).distinctBy { it.id } }
    val listedDone = !listed.hasMore && !listed.failed
    val listedTotal = counts?.get(IpoLifecycle.LISTED_STAGE)?.takeIf { board == Board.ALL && !listedDone }
    // Allotment days and listing results live on the detail; fetched only for the few cards that show them.
    var details by remember { mutableStateOf<Map<String, IpoDetailDto>>(emptyMap()) }
    LaunchedEffect(items) {
        val at = ZonedDateTime.now(IpoLifecycle.IST)
        val wanted = items.filter { IpoLifecycle.needsDetail(it, details[it.id], at) }
            .sortedWith(compareBy({ IpoLifecycle.laneOf(it, at) != Lane.ALLOTMENT }, { -(it.listsOn.localDate()?.toEpochDay() ?: 0L) }))
            .map { it.id }.take(12)
        if (wanted.isNotEmpty()) details = details + coroutineScope {
            wanted.map { id -> async { id to (repository.ipoDetail(id) as? MarketDataState.Loaded)?.value } }.awaitAll()
        }.mapNotNull { (id, d) -> d?.let { id to it } }
    }
    val holidays by produceState(emptyMap<java.time.LocalDate, String>()) { value = com.marksy.os.upstox.UpstoxHolidays.load(context, java.time.LocalDate.now(IpoLifecycle.IST)) }
    val opened = openedId?.let { id -> items.firstOrNull { it.id == id } }
    val note = opened?.let { IpoLifecycle.detailNote(it, now) } ?: IpoLifecycle.homeNote(items, shownStage, board, query, now, details)
    LaunchedEffect(note) { onTitleNote(note) }
    DisposableEffect(Unit) { onDispose { onTitleNote(null) } }

    Box(Modifier.fillMaxSize().background(MarksyTheme.Background)) {
        if (opened != null) {
            BackHandler { openedId = null; remindersOpen = false }
            IpoDetailScreen(
                repository, opened, padding, now, reminders, onReminder = { e, on -> setReminder(opened, e, on) },
                remindersOpen = remindersOpen, onRemindersClose = { remindersOpen = false }, holidays = holidays
            )
        } else {
            MarksyRefreshBox(refresh) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().padding(horizontal = MarksySpace.Gutter),
                    contentPadding = PaddingValues(top = MarksySpace.Gap, bottom = maxOf(padding.calculateBottomPadding(), oneHandStackBottomPadding(3))),
                    verticalArrangement = Arrangement.spacedBy(MarksySpace.ListGap)
                ) {
                    when (val s = state) {
                        is MarketDataState.Loading -> item { MarksyLoader("Checking IPOs…") }
                        is MarketDataState.Unavailable -> item { EmptyState("Marksy is not connected", "Add a Market API key in More → Configure Gateway.") }
                        is MarketDataState.Error -> item { EmptyState("IPO data unavailable", s.message) }
                        is MarketDataState.Empty -> item { EmptyState("No IPOs", "No issues are open, upcoming or recently listed.") }
                        is MarketDataState.Loaded, is MarketDataState.Stale -> if (search != null) {
                            val failure = search.state as? MarketDataState.Error
                            when {
                                search.items.isEmpty() && search.hasMore -> item(key = "searching") { MarksyLoader("Searching…") }
                                search.items.isEmpty() && failure != null -> item(key = "search-error") { EmptyState("Search unavailable", failure.message) }
                                else -> {
                                    ipoLanes(
                                        search.items, shownStage, board, query, watched.orEmpty(), now, listedOpen, { listedOpen = !listedOpen }, reminderKeys, details,
                                        listedTotal = null, pending = false, onOpen = { openedId = it.id }, onRemind = setReminder
                                    )
                                    pagedFooter(search, "search", "search results", end = null)
                                }
                            }
                        } else {
                            val listedShown = query.isBlank() && (stage == StageFilter.LISTED || (stage == StageFilter.ALL && listedOpen))
                            ipoLanes(
                                items, stage, board, query, watched.orEmpty(), now, listedOpen, { listedOpen = !listedOpen }, reminderKeys, details,
                                listedTotal = listedTotal, pending = stage == StageFilter.LISTED && listed.items.isEmpty() && listed.hasMore,
                                onOpen = { openedId = it.id }, onRemind = setReminder
                            )
                            if (listedShown) pagedFooter(listed, "listed", "listed IPOs", "That's all ${IpoLifecycle.stageCount(items, StageFilter.LISTED, board, emptySet(), now, null, listedDone = true)} listed")
                        }
                    }
                }
            }
        }
        OneHandControls(
            filters = MarketSections,
            selectedFilter = MarketTab.IPOS.name,
            filterIsView = false,
            onFilterSelected = onSectionSelected,
            searchQuery = if (opened == null) query else null,
            onSearchChange = if (opened == null) ({ query = it }) else null,
            searchPlaceholder = "Search IPOs…",
            // Stage and board sit inside the section filter's panel, as Captured's options do on Trading.
            extrasActive = opened == null && (stage != StageFilter.OPEN || board != Board.ALL),
            filterExtras = if (opened == null) ({
                IpoFilterSections(items, stage, board, watched.orEmpty(), now, counts?.get(IpoLifecycle.LISTED_STAGE), listedDone, onStage = { stageName = it.name }, onBoard = { boardName = it.name })
            }) else null,
            actions = if (opened == null) emptyList()
            else {
                val on = opened.id in watched.orEmpty()
                listOf(
                    FloatingAction(Icons.Default.NotificationsActive, "Reminders for ${opened.companyName}") { remindersOpen = true },
                    FloatingAction(if (on) Icons.Filled.BookmarkAdded else Icons.Outlined.BookmarkAdd, if (on) "Stop watching ${opened.companyName}" else "Watch ${opened.companyName}") { toggleWatch(opened) }
                )
            }
        )
    }
}

private const val LISTED_PAGE = 20
private const val SEARCH_PAGE = 20
private const val MAX_IDS = 100

/** Every pre-listing issue, skipping empty stages; none live is still a list, since Listed pages in separately. */
private suspend fun liveIpos(repository: MarketIntelligenceRepository, counts: Map<String, Int>?, previous: List<IpoListItemDto>): MarketDataState<List<IpoListItemDto>> = coroutineScope {
    val stages = IpoLifecycle.PRE_LISTING_STAGES.filter { counts == null || (counts[it] ?: 0) > 0 }
    if (stages.isEmpty()) return@coroutineScope MarketDataState.Loaded(emptyList())
    IpoLifecycle.mergeStages(stages.map { s -> async { s to repository.ipos(stage = s) } }.awaitAll(), previous)
        .let { if (it is MarketDataState.Empty) MarketDataState.Loaded(emptyList()) else it }
}

private fun LazyListScope.pagedFooter(paged: Paged<IpoListItemDto>, key: String, what: String, end: String?) {
    val note = when {
        paged.hasMore || paged.items.isEmpty() -> null
        paged.failed -> "Couldn't load more $what · pull to refresh"
        else -> end
    }
    if (paged.hasMore) item(key = "more-$key-${paged.items.size}") {
        LaunchedEffect(Unit) { paged.more() }
        MarksyLoader("Loading more…")
    } else if (note != null) item(key = "$key-end") {
        Text(note, color = MarksyTheme.TextMuted, style = MarksyType.Meta, modifier = Modifier.fillMaxWidth().padding(vertical = MarksySpace.Gap), textAlign = TextAlign.Center)
    } else (paged.state as? MarketDataState.Error)?.let { e -> item(key = "$key-error") { EmptyState("${what.replaceFirstChar(Char::titlecase)} unavailable", e.message) } }
}

private fun LazyListScope.ipoLanes(
    items: List<IpoListItemDto>, stage: StageFilter, board: Board, query: String, watched: Set<String>, now: ZonedDateTime,
    listedOpen: Boolean, onToggleListed: () -> Unit, reminderKeys: Set<String>, details: Map<String, IpoDetailDto>,
    listedTotal: Int?, pending: Boolean,
    onOpen: (IpoListItemDto) -> Unit, onRemind: (IpoListItemDto, IpoLifecycle.ReminderEvent, Boolean) -> Unit
) {
    // With a query the items are the server's hits, so no second name filter here.
    val lanes = IpoLifecycle.lanes(items, stage, board, "", watched, now)
    if (lanes.isEmpty()) {
        if (pending) return
        item {
            when {
                stage == StageFilter.WATCHING -> EmptyState("No IPOs match", "Tap the bookmark on an IPO to watch it.")
                stage == StageFilter.OPEN && query.isBlank() -> EmptyState("No IPOs open right now", "Opens soon, Allotment and Listed are in the filter.")
                else -> EmptyState("No IPOs match", "Try another stage or board, or clear the search.")
            }
        }
        return
    }
    lanes.forEach { (lane, xs) ->
        val folded = lane == Lane.LISTED && stage == StageFilter.ALL && query.isBlank()
        if (folded) item(key = "fold-listed") { ListedFold(listedTotal ?: xs.size, IpoLifecycle.listedSummary(xs, details), listedOpen, onToggleListed) }
        else item(key = "lane-${lane.name}") { SectionLabel(lane.label, if (lane == Lane.LISTED) listedTotal ?: xs.size else xs.size, laneColor(lane)) }
        if (!folded || listedOpen) items(xs, key = { it.id }) { ipo ->
            // The 3 pm nudge sits on the card only while it is still ahead.
            val close = if (lane == Lane.TODAY) IpoLifecycle.reminderEvents(mapOf("close" to now.toLocalDate()), now).firstOrNull() else null
            val key = IpoLifecycle.reminderKey(ipo.id, "close")
            IpoCard(ipo, lane, now, details[ipo.id], ipo.id in watched, reminderSet = key in reminderKeys,
                onRemind = close?.let { e -> { onRemind(ipo, e, key !in reminderKeys) } }) { onOpen(ipo) }
        }
    }
}

internal fun laneColor(lane: Lane?): Color = when (lane) {
    Lane.TODAY -> MarksyTheme.Negative
    Lane.OPEN -> MarksyTheme.PrimaryEmerald
    Lane.ALLOTMENT -> MarksyTheme.Warning
    Lane.UPCOMING -> MarksyTheme.Info
    Lane.LISTED, null -> MarksyTheme.TextMuted
}

@Composable
private fun ListedFold(count: Int, summary: String?, open: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().marksyCard().clickable(onClick = onToggle).padding(horizontal = MarksySpace.CardPadding, vertical = MarksySpace.ListGap),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(MarksySize.Dot).clip(CircleShape).background(MarksyTheme.TextMuted))
        Spacer(Modifier.width(MarksySpace.Gap))
        Column(Modifier.weight(1f)) {
            Row {
                Text("LISTED", color = MarksyTheme.TextSecondary, style = MarksyType.Label)
                Text(" · $count recent", color = MarksyTheme.TextMuted, style = MarksyType.Meta)
            }
            summary?.let { Text(it, color = MarksyTheme.TextSecondary, style = MarksyType.Meta, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        Icon(Icons.Default.ExpandMore, if (open) "Hide listed issues" else "Show listed issues", tint = MarksyTheme.TextMuted, modifier = Modifier.size(MarksySize.Icon).rotate(if (open) 180f else 0f))
    }
}

@Composable
internal fun IpoAvatar(name: String, size: Int = 32) {
    val initials = name.split(' ').filter { it.firstOrNull()?.isUpperCase() == true }.take(2).joinToString("") { it.take(1) }.ifEmpty { name.take(1) }
    MarksyAvatar(initials, size.dp)
}

@Composable
internal fun SmeTag() = MarksyBadge("SME", MarksyTheme.Warning, MarksyTheme.BadgeImportantBg, Modifier.padding(start = MarksySpace.Inner))

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun IpoCard(ipo: IpoListItemDto, lane: Lane, now: ZonedDateTime, detail: IpoDetailDto?, watched: Boolean, reminderSet: Boolean, onRemind: (() -> Unit)?, onClick: () -> Unit) {
    val facts = IpoLifecycle.cardFacts(ipo, now, detail)
    val gmp = IpoLifecycle.gmpSummary(ipo.gmp, IpoLifecycle.upper(ipo), now)
    MarksyCard(border = if (lane == Lane.TODAY) MarksyTheme.Negative else MarksyTheme.BorderGlow, onClick = onClick) {
        facts.chip?.let {
            Text(it, color = MarksyTheme.Negative, style = MarksyType.Meta, fontWeight = FontWeight.Bold,
                modifier = Modifier.clip(MarksyShape.Chip).background(MarksyTheme.BadgeUrgentBg).padding(horizontal = MarksySpace.Gap, vertical = MarksySpace.Tight))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            IpoAvatar(ipo.companyName)
            Spacer(Modifier.width(MarksySpace.ListGap))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(ipo.companyName, color = MarksyTheme.TextPrimary, style = MarksyType.Subhead, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (ipo.isSme) SmeTag()
                    if (watched) Icon(Icons.Filled.BookmarkAdded, "Watching", tint = MarksyTheme.PrimaryEmerald, modifier = Modifier.padding(start = MarksySpace.Tight).size(MarksySize.IconSmall))
                }
                val board = if (ipo.isSme) ipo.terms?.exchanges.display() else "Mainboard"
                Text(listOfNotNull(ipo.sector, board).joinToString(" · "), color = MarksyTheme.TextMuted, style = MarksyType.Meta, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(MarksySpace.Gap))
            Column(horizontalAlignment = Alignment.End) {
                val statColor = when { facts.statUp == true -> MarksyTheme.PrimaryEmerald; facts.statUp == false && lane == Lane.LISTED -> MarksyTheme.Negative; else -> MarksyTheme.TextPrimary }
                Text(facts.stat, color = statColor, style = MarksyType.Lead, fontWeight = FontWeight.Bold)
                Text(facts.statLabel, color = MarksyTheme.TextMuted, style = MarksyType.Caption)
            }
        }
        Text(facts.line, color = MarksyTheme.TextSecondary, style = MarksyType.Small)
        gmp?.let { Text(it.text, color = MarksyTheme.TextMuted, style = MarksyType.Meta) }
        onRemind?.let { remind -> Pill(if (reminderSet) "Reminder at 3 pm" else "Remind me at 3 pm", selected = reminderSet, onClick = remind) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun IpoFilterSections(
    items: List<IpoListItemDto>, stage: StageFilter, board: Board, watched: Set<String>, now: ZonedDateTime,
    listedTotal: Int?, listedDone: Boolean, onStage: (StageFilter) -> Unit, onBoard: (Board) -> Unit
) {
    Column(Modifier.widthIn(max = 260.dp).padding(start = MarksySpace.CardPadding, end = MarksySpace.CardPadding, top = MarksySpace.Gap, bottom = MarksySpace.Inner), verticalArrangement = Arrangement.spacedBy(MarksySpace.Inner)) {
        Text("STAGE", color = MarksyTheme.TextMuted, style = MarksyType.Label)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(MarksySpace.Inner), verticalArrangement = Arrangement.spacedBy(MarksySpace.Inner)) {
            StageFilter.entries.forEach { f ->
                val n = IpoLifecycle.stageCount(items, f, board, watched, now, listedTotal, listedDone)
                Pill(if (n == null) f.label else "${f.label} $n", selected = f == stage) { onStage(f) }
            }
        }
        Text("BOARD", color = MarksyTheme.TextMuted, style = MarksyType.Label)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(MarksySpace.Inner), verticalArrangement = Arrangement.spacedBy(MarksySpace.Inner)) {
            Board.entries.forEach { b -> Pill(b.label, selected = b == board) { onBoard(b) } }
        }
    }
}
