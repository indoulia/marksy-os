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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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

/** IPO home: every live issue in lifecycle lanes, filters and search on the floating stack. */
@Composable
fun IpoScreen(repository: MarketIntelligenceRepository, padding: PaddingValues, onSectionSelected: (String) -> Unit = {}, onTitleNote: (String?) -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val now by produceState(ZonedDateTime.now(IpoLifecycle.IST)) {
        while (true) { delay(30_000); value = ZonedDateTime.now(IpoLifecycle.IST) }
    }
    var stageName by rememberSaveable { mutableStateOf(StageFilter.ALL.name) }
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
    LaunchedEffect(refresh.key) {
        val previous = when (val s = state) { is MarketDataState.Loaded -> s.value; is MarketDataState.Stale -> s.value; else -> emptyList() }
        state = liveIpos(repository, previous).also {
            if (it is MarketDataState.Stale) android.widget.Toast.makeText(context, "Couldn't refresh every IPO stage; some rows may be out of date", android.widget.Toast.LENGTH_SHORT).show()
        }
        refresh.done()
    }
    val listState = rememberLazyListState()

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
                    android.widget.Toast.makeText(context, "Couldn't ${if (want) "watch" else "unwatch"} ${ipo.companyName}", android.widget.Toast.LENGTH_SHORT).show()
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

    val items = when (val s = state) { is MarketDataState.Loaded -> s.value; is MarketDataState.Stale -> s.value; else -> emptyList() }
    // Allotment days and listing results live on the detail; fetched only for the few cards that show them.
    var details by remember { mutableStateOf<Map<String, IpoDetailDto>>(emptyMap()) }
    LaunchedEffect(state) {
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
    val note = opened?.let { IpoLifecycle.detailNote(it, now) } ?: IpoLifecycle.homeNote(items, stage, board, query, now, details)
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
                    modifier = Modifier.fillMaxSize().padding(horizontal = 18.dp),
                    contentPadding = PaddingValues(top = 8.dp, bottom = maxOf(padding.calculateBottomPadding(), oneHandStackBottomPadding(3))),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    when (val s = state) {
                        is MarketDataState.Loading -> item { MarksyLoader("Checking IPOs...") }
                        is MarketDataState.Unavailable -> item { EmptyState("Market Intelligence is not configured", "Add a Market API key in More → Configure Gateway.") }
                        is MarketDataState.Error -> item { EmptyState("IPO data unavailable", s.message) }
                        is MarketDataState.Empty -> item { EmptyState("No IPOs", "No issues are open, upcoming or recently listed.") }
                        is MarketDataState.Loaded, is MarketDataState.Stale -> ipoLanes(
                            items, stage, board, query, watched.orEmpty(), now, listedOpen, { listedOpen = !listedOpen }, reminderKeys, details,
                            onOpen = { openedId = it.id }, onRemind = setReminder
                        )
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
            searchPlaceholder = "Search IPOs...",
            // Stage and board sit inside the section filter's panel, as Captured's options do on Trading.
            extrasActive = opened == null && (stage != StageFilter.ALL || board != Board.ALL),
            filterExtras = if (opened == null) ({
                IpoFilterSections(items, stage, board, watched.orEmpty(), now, onStage = { stageName = it.name }, onBoard = { boardName = it.name })
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

/** Every issue in a live stage; stages with no issues are not fetched, since the unfiltered list is hundreds long. */
private suspend fun liveIpos(repository: MarketIntelligenceRepository, previous: List<IpoListItemDto>): MarketDataState<List<IpoListItemDto>> = coroutineScope {
    val counts = (repository.ipoStageCounts() as? MarketDataState.Loaded)?.value?.byStage
    val stages = IpoLifecycle.LIVE_STAGES.filter { counts == null || (counts[it] ?: 0) > 0 }
    if (stages.isEmpty()) return@coroutineScope MarketDataState.Empty
    IpoLifecycle.mergeStages(stages.map { s -> async { s to repository.ipos(stage = s) } }.awaitAll(), previous)
}

private fun LazyListScope.ipoLanes(
    items: List<IpoListItemDto>, stage: StageFilter, board: Board, query: String, watched: Set<String>, now: ZonedDateTime,
    listedOpen: Boolean, onToggleListed: () -> Unit, reminderKeys: Set<String>, details: Map<String, IpoDetailDto>,
    onOpen: (IpoListItemDto) -> Unit, onRemind: (IpoListItemDto, IpoLifecycle.ReminderEvent, Boolean) -> Unit
) {
    val lanes = IpoLifecycle.lanes(items, stage, board, query, watched, now)
    if (lanes.isEmpty()) {
        item { EmptyState("No IPOs match", if (stage == StageFilter.WATCHING) "Tap the bookmark on an IPO to watch it." else "Try another stage or board, or clear the search.") }
        return
    }
    lanes.forEach { (lane, xs) ->
        val folded = lane == Lane.LISTED && stage == StageFilter.ALL && query.isBlank()
        if (folded) item(key = "fold-listed") { ListedFold(xs.size, IpoLifecycle.listedSummary(xs, details), listedOpen, onToggleListed) }
        else item(key = "lane-${lane.name}") { LaneLabel(lane.label, xs.size, laneColor(lane)) }
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
    Lane.TODAY -> MarksyTheme.RedUrgent
    Lane.OPEN -> MarksyTheme.PrimaryEmerald
    Lane.ALLOTMENT -> MarksyTheme.YellowImportant
    Lane.UPCOMING -> MarksyTheme.BlueFinance
    Lane.LISTED, null -> MarksyTheme.TextMuted
}

@Composable
private fun LaneLabel(text: String, count: Int, dot: Color) {
    Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(dot))
        Spacer(Modifier.width(8.dp))
        Text(text.uppercase(), color = MarksyTheme.TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp)
        Spacer(Modifier.width(6.dp))
        Text("$count", color = MarksyTheme.TextMuted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(8.dp))
        Box(Modifier.weight(1f).height(1.dp).background(MarksyTheme.BorderGlow))
    }
}

@Composable
private fun ListedFold(count: Int, summary: String?, open: Boolean, onToggle: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier.fillMaxWidth().clip(shape).background(MarksyTheme.Surface).border(1.dp, MarksyTheme.BorderGlow, shape).clickable(onClick = onToggle).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(MarksyTheme.TextMuted))
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Row {
                Text("LISTED", color = MarksyTheme.TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp)
                Text(" · $count recent", color = MarksyTheme.TextMuted, fontSize = 11.sp)
            }
            summary?.let { Text(it, color = MarksyTheme.TextSecondary, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        Icon(Icons.Default.ExpandMore, if (open) "Hide listed issues" else "Show listed issues", tint = MarksyTheme.TextSecondary, modifier = Modifier.size(20.dp).rotate(if (open) 180f else 0f))
    }
}

@Composable
internal fun IpoAvatar(name: String, size: Int = 32) {
    val initials = name.split(' ').filter { it.firstOrNull()?.isUpperCase() == true }.take(2).joinToString("") { it.take(1) }.ifEmpty { name.take(1) }
    Box(Modifier.size(size.dp).clip(CircleShape).background(MarksyTheme.SurfaceRaised).border(1.dp, MarksyTheme.BorderGlow, CircleShape), contentAlignment = Alignment.Center) {
        Text(initials, color = MarksyTheme.PrimaryEmerald, fontSize = (size * 0.38).sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
internal fun SmeTag() = Text(
    "SME", color = MarksyTheme.YellowImportant, fontSize = 9.sp, fontWeight = FontWeight.Bold,
    modifier = Modifier.padding(start = 6.dp).clip(RoundedCornerShape(4.dp)).background(MarksyTheme.BadgeImportantBg).padding(horizontal = 4.dp, vertical = 1.dp)
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun IpoCard(ipo: IpoListItemDto, lane: Lane, now: ZonedDateTime, detail: IpoDetailDto?, watched: Boolean, reminderSet: Boolean, onRemind: (() -> Unit)?, onClick: () -> Unit) {
    val facts = IpoLifecycle.cardFacts(ipo, now, detail)
    val gmp = IpoLifecycle.gmpSummary(ipo.gmp, IpoLifecycle.upper(ipo), now)
    val shape = RoundedCornerShape(14.dp)
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = MarksyTheme.Surface),
        shape = shape,
        modifier = Modifier.fillMaxWidth().border(1.dp, if (lane == Lane.TODAY) MarksyTheme.RedUrgent else MarksyTheme.BorderGlow, shape)
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            facts.chip?.let {
                Text(it, color = MarksyTheme.RedUrgent, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(MarksyTheme.BadgeUrgentBg).padding(horizontal = 8.dp, vertical = 3.dp))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                IpoAvatar(ipo.companyName)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(ipo.companyName, color = MarksyTheme.TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                        if (ipo.isSme) SmeTag()
                        if (watched) Icon(Icons.Filled.BookmarkAdded, "Watching", tint = MarksyTheme.PrimaryEmerald, modifier = Modifier.padding(start = 4.dp).size(14.dp))
                    }
                    val board = if (ipo.isSme) ipo.terms?.exchanges.display() else "Mainboard"
                    Text(listOfNotNull(ipo.sector, board).joinToString(" · "), color = MarksyTheme.TextMuted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.width(8.dp))
                Column(horizontalAlignment = Alignment.End) {
                    val statColor = when { facts.statUp == true -> MarksyTheme.PrimaryEmerald; facts.statUp == false && lane == Lane.LISTED -> MarksyTheme.RedUrgent; else -> MarksyTheme.TextPrimary }
                    Text(facts.stat, color = statColor, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Text(facts.statLabel, color = MarksyTheme.TextMuted, fontSize = 10.sp)
                }
            }
            Text(facts.line, color = MarksyTheme.TextSecondary, fontSize = 12.sp, lineHeight = 16.sp)
            gmp?.let { Text(it.text, color = MarksyTheme.TextMuted, fontSize = 11.sp, lineHeight = 14.sp) }
            onRemind?.let { remind -> Pill(if (reminderSet) "Reminder at 3 pm" else "Remind me at 3 pm", selected = reminderSet, onClick = remind) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun IpoFilterSections(
    items: List<IpoListItemDto>, stage: StageFilter, board: Board, watched: Set<String>, now: ZonedDateTime,
    onStage: (StageFilter) -> Unit, onBoard: (Board) -> Unit
) {
    Column(Modifier.widthIn(max = 260.dp).padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("STAGE", color = MarksyTheme.TextMuted, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            StageFilter.entries.forEach { f ->
                val n = IpoLifecycle.lanes(items, f, board, "", watched, now).sumOf { it.second.size }
                Pill("${f.label} $n", selected = f == stage) { onStage(f) }
            }
        }
        Text("BOARD", color = MarksyTheme.TextMuted, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Board.entries.forEach { b -> Pill(b.label, selected = b == board) { onBoard(b) } }
        }
    }
}
