package com.marksy.os.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.BookmarkAdded
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.BookmarkAdd
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.marksy.os.EmptyState
import com.marksy.os.data.MarksyContainer
import com.marksy.os.data.local.WatchAdd
import com.marksy.os.data.local.WatchlistEntity
import com.marksy.os.data.local.WatchlistItemEntity
import com.marksy.os.market.MarketDataState
import com.marksy.os.upstox.UpstoxApiClient
import com.marksy.os.upstox.UpstoxFundamentals
import com.marksy.os.upstox.UpstoxInstruments
import com.marksy.os.upstox.UpstoxTokenStore
import com.marksy.os.watchlist.WatchlistNames
import com.marksy.os.watchlist.WatchlistPicker
import com.marksy.os.watchlist.WatchlistRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Lists by stock count, fullest first; ties keep creation order. */
fun rankedWatchlists(lists: List<WatchlistEntity>, items: List<WatchlistItemEntity>): List<WatchlistEntity> {
    val counts = items.groupingBy { it.watchlistId }.eachCount()
    return lists.sortedByDescending { counts[it.id] ?: 0 }
}

// "" (or a deleted list's id) opens the fullest list.
private fun currentList(view: String, lists: List<WatchlistEntity>, items: List<WatchlistItemEntity>): WatchlistEntity? =
    lists.firstOrNull { it.id.toString() == view } ?: rankedWatchlists(lists, items).firstOrNull()

fun watchlistCurrentId(view: String, lists: List<WatchlistEntity>, items: List<WatchlistItemEntity>): Long? = currentList(view, lists, items)?.id

/** What stock rows need to show and open the add popup; a CompositionLocal so rows deep in any screen reach it. */
class WatchlistAdder(val watched: Set<String>, val open: (String) -> Unit)

val LocalWatchlistAdder = compositionLocalOf<WatchlistAdder?> { null }

/** Small bookmark on any stock row: filled once the stock is in a watchlist; opens the add popup. */
@Composable
fun WatchlistButton(symbol: String, modifier: Modifier = Modifier) {
    val adder = LocalWatchlistAdder.current ?: return
    val s = symbol.trim().uppercase()
    val watched = s in adder.watched
    Icon(
        if (watched) Icons.Filled.BookmarkAdded else Icons.Outlined.BookmarkAdd,
        contentDescription = if (watched) "$s is in a watchlist" else "Add $s to a watchlist",
        tint = if (watched) MarksyTheme.PrimaryEmerald else MarksyTheme.TextSecondary,
        modifier = modifier.size(30.dp).clip(CircleShape).clickable { adder.open(s) }.padding(MarksySpace.Inner)
    )
}

/** The app's one add-to-watchlist popup; [onAdded] gets the list id and a confirmation line. */
@Composable
fun WatchlistAddHost(
    repository: WatchlistRepository,
    lists: List<WatchlistEntity>,
    items: List<WatchlistItemEntity>,
    currentListId: Long?,
    onAdded: (Long, String) -> Unit,
    content: @Composable () -> Unit
) {
    var adding by rememberSaveable { mutableStateOf<String?>(null) }
    val watched = remember(items) { items.mapTo(HashSet()) { it.symbol } }
    val adder = remember(watched) { WatchlistAdder(watched) { adding = it.trim().uppercase() } }
    CompositionLocalProvider(LocalWatchlistAdder provides adder, content = content)
    adding?.let { symbol ->
        AddToWatchlistDialog(
            symbol = symbol,
            lists = lists,
            counts = items.groupingBy { it.watchlistId }.eachCount(),
            holding = items.filter { it.symbol == symbol }.mapTo(HashSet()) { it.watchlistId },
            current = currentListId,
            onDismiss = { adding = null },
            onAdd = { listId, newName, name ->
                val id = listId ?: newName?.let { repository.createList(it) } ?: return@AddToWatchlistDialog "A list with that name already exists"
                when (repository.add(id, symbol, name)) {
                    WatchAdd.ADDED -> { adding = null; onAdded(id, "Added $symbol to ${lists.firstOrNull { it.id == id }?.name ?: newName?.trim()}"); null }
                    WatchAdd.ALREADY_THERE -> "$symbol is already in that list"
                    WatchAdd.FULL -> "That list already has ${WatchlistRepository.MAX_STOCKS} stocks"
                }
            }
        )
    }
}

/** Title superscript: the open list and how full it is. */
fun watchlistLabel(view: String, lists: List<WatchlistEntity>, items: List<WatchlistItemEntity>): String? {
    val list = currentList(view, lists, items) ?: return null
    return "${list.name} ${items.count { it.watchlistId == list.id }}/${WatchlistRepository.MAX_STOCKS}"
}

/** Market's home: named lists of up to 15 stocks each; a left quick menu switches lists. Search lives in the app header. */
@Composable
fun WatchlistScreen(
    repository: WatchlistRepository,
    lists: List<WatchlistEntity>,
    items: List<WatchlistItemEntity>,
    padding: PaddingValues,
    view: String,
    onViewSelected: (String) -> Unit,
    query: String,
    onQueryChange: (String) -> Unit = {},
    onOpenStock: (String) -> Unit,
    onSectionSelected: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    val list = currentList(view, lists, items)
    val counts = remember(items) { items.groupingBy { it.watchlistId }.eachCount() }
    val adder = LocalWatchlistAdder.current
    var creating by rememberSaveable { mutableStateOf(false) }
    var quickAdding by rememberSaveable { mutableStateOf(false) }
    var moving by remember { mutableStateOf<String?>(null) }

    Box(Modifier.fillMaxSize().background(MarksyTheme.Background).padding(padding).consumeWindowInsets(padding)) {
        val inner = PaddingValues(bottom = OneHandListBottomPadding)
        val q = query.trim()
        when {
            q.length >= 2 -> StockSuggestions(q, inner, onSymbolSelected = { adder?.open(it) }, emptyHint = "Try the company's NSE symbol, e.g. HAL.")
            list == null -> Box(Modifier.padding(MarksySpace.Gutter)) {
                EmptyState("No watchlists yet", "Tap the bottom-left button to create one, e.g. Defence or SmallCap, then tap search above to add stocks.")
            }
            else -> WatchlistRows(
                rows = items.filter { it.watchlistId == list.id },
                emptyName = list.name,
                padding = inner,
                onOpen = onOpenStock,
                onRemove = { symbol -> scope.launch { repository.remove(list.id, symbol) } },
                onMove = { moving = it },
                onAdd = { quickAdding = true }
            )
        }
        OneHandQuickMenu(
            options = rankedWatchlists(lists, items).map { it.id.toString() to "${it.name}  ${counts[it.id] ?: 0}" },
            selected = list?.id?.toString(),
            onSelected = onViewSelected,
            icon = Icons.Default.Bookmarks,
            label = "Watchlists",
            action = FloatingAction(Icons.Default.Add, "New watchlist") { creating = true }
        )
        // Delete lives in Settings (ManageWatchlistsDialog), away from easy taps.
        OneHandControls(
            filters = MarketSections,
            selectedFilter = MarketTab.WATCHLIST.name,
            filterIsView = false,
            onFilterSelected = onSectionSelected,
            searchQuery = query,
            onSearchChange = onQueryChange,
            searchPlaceholder = "Search a stock to add…",
            searchSymbols = true
        )
    }

    val from = list
    moving?.takeIf { from != null }?.let { symbol ->
        AddToWatchlistDialog(
            symbol = symbol,
            lists = lists,
            counts = counts,
            holding = items.filter { it.symbol == symbol }.mapTo(HashSet()) { it.watchlistId },
            current = null,
            title = "Move $symbol",
            confirmLabel = "Move",
            onDismiss = { moving = null },
            onAdd = { listId, newName, _ ->
                val id = listId ?: newName?.let { repository.createList(it) } ?: return@AddToWatchlistDialog "A list with that name already exists"
                when (repository.move(from!!.id, id, symbol)) {
                    WatchAdd.FULL -> "That list already has ${WatchlistRepository.MAX_STOCKS} stocks"
                    else -> { moving = null; null }
                }
            }
        )
    }
    if (creating) NewWatchlistDialog(
        existing = lists.map { it.name },
        onDismiss = { creating = false },
        onCreate = { name -> repository.createList(name)?.also { creating = false; onViewSelected(it.toString()) } }
    )
    list?.takeIf { quickAdding }?.let { l ->
        QuickAddStockDialog(repository, l, items.filter { it.watchlistId == l.id }.mapTo(HashSet()) { it.symbol }) { quickAdding = false }
    }
}

/** Search-and-add straight into [list]; stays open so several stocks go in one trip. */
@Composable
private fun QuickAddStockDialog(repository: WatchlistRepository, list: WatchlistEntity, inList: Set<String>, onDismiss: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var pending by remember { mutableStateOf<String?>(null) }
    var note by remember { mutableStateOf<String?>(null) }
    val q = query.trim()
    // null = still loading.
    val matches by produceState<List<String>?>(emptyList(), q) {
        if (q.length < 2) { value = emptyList(); return@produceState }
        value = null
        value = runCatching { UpstoxInstruments.suggest(context, q) }.getOrDefault(emptyList())
    }
    MarksyDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add to ${list.name}  ${inList.size}/${WatchlistRepository.MAX_STOCKS}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(MarksySpace.Tight)) {
                MarksySearchField(query, { query = it; note = null }, "Search NSE symbol or name…", Modifier.fillMaxWidth(), symbols = true, lifted = false)
                note?.let { Text(it, color = MarksyTheme.TextSecondary, style = MarksyType.Small) }
                val found = matches
                when {
                    found == null -> MarksyLoader("Searching…")
                    q.length >= 2 && found.isEmpty() -> Text("No NSE symbol matches \"$q\"", color = MarksyTheme.TextMuted, style = MarksyType.Small)
                }
                found.orEmpty().forEach { symbol ->
                    val has = symbol in inList
                    Row(
                        Modifier.fillMaxWidth().clip(MarksyShape.Chip).clickable(enabled = !has && pending == null) {
                            pending = symbol
                            scope.launch {
                                // Company name for the row's subtitle; skipped if the lookup is slow.
                                val name = withTimeoutOrNull(3_000) { loadTraits(context, symbol).name }
                                note = when (repository.add(list.id, symbol, name)) {
                                    WatchAdd.ADDED -> "Added $symbol"
                                    WatchAdd.ALREADY_THERE -> "$symbol is already in ${list.name}"
                                    WatchAdd.FULL -> "${list.name} already has ${WatchlistRepository.MAX_STOCKS} stocks"
                                }
                                pending = null
                            }
                        }.padding(horizontal = MarksySpace.Tight, vertical = MarksySpace.ListGap),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(symbol, color = MarksyTheme.TextPrimary, style = MarksyType.Subhead, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                        if (has) Icon(Icons.Default.Check, contentDescription = "Added", tint = MarksyTheme.TextMuted, modifier = Modifier.size(MarksySize.Icon))
                        else Text(if (pending == symbol) "Adding…" else "Add", color = MarksyTheme.PrimaryEmerald, style = MarksyType.Body, fontWeight = FontWeight.Bold)
                    }
                }
            }
        },
        confirmButton = { MarksyButton("Done", onClick = onDismiss, style = MarksyButtonStyle.Text) }
    )
}

/** Settings' watchlist manager: deleting a list takes this trip and a confirmation. */
@Composable
fun ManageWatchlistsDialog(repository: WatchlistRepository, lists: List<WatchlistEntity>, items: List<WatchlistItemEntity>, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val counts = remember(items) { items.groupingBy { it.watchlistId }.eachCount() }
    var deleting by remember { mutableStateOf<WatchlistEntity?>(null) }
    var creating by remember { mutableStateOf(false) }
    val target = deleting
    if (creating) NewWatchlistDialog(
        existing = lists.map { it.name },
        onDismiss = { creating = false },
        onCreate = { name -> repository.createList(name)?.also { creating = false } }
    ) else if (target != null) WatchDialog(
        title = "Delete ${target.name}?",
        confirmLabel = "Delete",
        confirmEnabled = true,
        confirmColor = MarksyTheme.Negative,
        onConfirm = { deleting = null; scope.launch { repository.deleteList(target.id) } },
        onDismiss = { deleting = null }
    ) {
        val n = counts[target.id] ?: 0
        Text(if (n == 0) "The list is empty." else "Its $n stocks go with it; other lists keep theirs.", color = MarksyTheme.TextSecondary, style = MarksyType.Body)
    } else MarksyDialog(
        onDismissRequest = onDismiss,
        title = { Text("Watchlists") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(MarksySpace.Tight)) {
                if (lists.isEmpty()) Text("No watchlists yet", color = MarksyTheme.TextSecondary, style = MarksyType.Body)
                rankedWatchlists(lists, items).forEach { w ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(w.name, color = MarksyTheme.TextPrimary, style = MarksyType.Subhead)
                            Text("${counts[w.id] ?: 0}/${WatchlistRepository.MAX_STOCKS} stocks", color = MarksyTheme.TextSecondary, style = MarksyType.Small)
                        }
                        MarksyButton("Delete", onClick = { deleting = w }, style = MarksyButtonStyle.Text, color = MarksyTheme.Negative)
                    }
                }
            }
        },
        dismissButton = { MarksyButton("New watchlist", onClick = { creating = true }, style = MarksyButtonStyle.Text) },
        confirmButton = { MarksyButton("Done", onClick = onDismiss, style = MarksyButtonStyle.Text) }
    )
}

@Composable
private fun WatchlistRows(rows: List<WatchlistItemEntity>, emptyName: String, padding: PaddingValues, onOpen: (String) -> Unit, onRemove: (String) -> Unit, onMove: (String) -> Unit, onAdd: () -> Unit) {
    val quotes = rememberUpstoxQuotes(remember(rows) { rows.map { it.symbol } })
    MarksyList(bottom = padding.calculateBottomPadding()) {
        if (rows.isEmpty()) item { EmptyState("Nothing in $emptyName yet", "Add up to ${WatchlistRepository.MAX_STOCKS} stocks below.") }
        items(rows, key = { "w-${it.watchlistId}-${it.symbol}" }) { row ->
            // Same swipe tray as Home: actions show first and only run when tapped.
            SwipeActionsRow(
                listOf(
                    SwipeTrayAction(Icons.Default.Delete, "Remove ${row.symbol}", MarksyTheme.Negative) { onRemove(row.symbol) },
                    SwipeTrayAction(Icons.Default.SwapHoriz, "Move ${row.symbol} to another list", MarksyTheme.PrimaryEmerald) { onMove(row.symbol) }
                )
            ) { WatchRow(row, quotes[row.symbol]) { onOpen(row.symbol) } }
        }
        // Right under the last stock, where the eye already is; hidden once the list is full.
        if (rows.size < WatchlistRepository.MAX_STOCKS) item(key = "add") { AddStocksButton(rows.size, onAdd) }
    }
}

@Composable
private fun AddStocksButton(count: Int, onClick: () -> Unit) {
    MarksyRowCard(onClick = onClick) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Add, contentDescription = null, tint = MarksyTheme.PrimaryEmerald, modifier = Modifier.size(MarksySize.Icon))
            Spacer(Modifier.width(MarksySpace.Inner))
            Text("Add stocks  $count/${WatchlistRepository.MAX_STOCKS}", color = MarksyTheme.PrimaryEmerald, style = MarksyType.Body, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun WatchRow(row: WatchlistItemEntity, quote: com.marksy.os.upstox.UpstoxLtp?, onClick: () -> Unit) {
    MarksyRowCard(onClick = onClick) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(row.symbol, color = MarksyTheme.TextPrimary, style = MarksyType.Subhead, fontWeight = FontWeight.Bold)
                row.name?.let { Text(it, color = MarksyTheme.TextMuted, style = MarksyType.Meta, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
            if (quote != null) Column(horizontalAlignment = Alignment.End) {
                Text(com.marksy.os.MarksyFormat.rupees(quote.lastPrice), color = MarksyTheme.TextPrimary, style = MarksyType.Body, fontWeight = FontWeight.SemiBold)
                quote.changePct?.let { pct ->
                    Text(com.marksy.os.MarksyFormat.percent(pct), color = if (pct < 0) MarksyTheme.Negative else MarksyTheme.Positive, style = MarksyType.Meta, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

private data class StockTraits(val name: String?, val sector: String?, val marketCapCr: Double?)

private suspend fun <T> quietly(block: suspend () -> T?): T? = try { block() } catch (e: CancellationException) { throw e } catch (_: Exception) { null }

/** Company name and sector from Marksy, sector and market cap from Upstox when connected. */
private suspend fun loadTraits(context: Context, symbol: String): StockTraits = coroutineScope {
    val marksy = async {
        quietly {
            when (val s = MarksyContainer.marketIntelligence(context).instrument(symbol)) {
                is MarketDataState.Loaded -> s.value
                is MarketDataState.Stale -> s.value
                else -> null
            }
        }
    }
    val upstox = async {
        quietly {
            val store = UpstoxTokenStore(context)
            if (!store.hasToken()) return@quietly null
            UpstoxInstruments.load(context)
            val isin = UpstoxInstruments.keyFor(symbol)?.substringAfter('|')?.takeIf { it.startsWith("IN") && it.length == 12 } ?: return@quietly null
            UpstoxFundamentals.profile(UpstoxApiClient { store.getToken() }.fundamentals(isin, "profile"))
        }
    }
    val dto = marksy.await()
    val profile = upstox.await()
    StockTraits(dto?.companyName, dto?.sector ?: profile?.sector, profile?.marketCapCr)
}

/** Watchlist popups on the shared [MarksyDialog]; content scrolls so pills and the keyboard fit. */
@Composable
private fun WatchDialog(
    title: String,
    confirmLabel: String,
    confirmEnabled: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    confirmColor: Color = MarksyTheme.PrimaryEmerald,
    content: @Composable ColumnScope.() -> Unit
) {
    MarksyDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(MarksySpace.ListGap), content = content) },
        dismissButton = { MarksyButton("Cancel", onClick = onDismiss, style = MarksyButtonStyle.Text, color = MarksyTheme.TextSecondary) },
        confirmButton = { MarksyButton(confirmLabel, onClick = onConfirm, style = MarksyButtonStyle.Text, color = confirmColor, enabled = confirmEnabled) }
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PillRow(content: @Composable () -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(MarksySpace.Inner), verticalArrangement = Arrangement.spacedBy(MarksySpace.Inner)) { content() }
}

/** [onAdd] returns an error to show, or null once added. */
@Composable
private fun AddToWatchlistDialog(
    symbol: String,
    lists: List<WatchlistEntity>,
    counts: Map<Long, Int>,
    holding: Set<Long>,
    current: Long?,
    onDismiss: () -> Unit,
    title: String = "Add $symbol",
    confirmLabel: String = "Add",
    onAdd: suspend (listId: Long?, newName: String?, name: String?) -> String?
) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val traits by produceState<StockTraits?>(null, symbol) { value = loadTraits(context, symbol) }
    val open = remember(lists, holding) { lists.filterNot { it.id in holding } }
    var chosen by remember(symbol) { mutableStateOf(WatchlistPicker.pick(open, counts, null, null, current)) }
    var userPicked by remember(symbol) { mutableStateOf(false) }
    var newName by remember(symbol) { mutableStateOf("") }
    var error by remember(symbol) { mutableStateOf<String?>(null) }
    LaunchedEffect(traits) {
        val t = traits ?: return@LaunchedEffect
        if (!userPicked) chosen = WatchlistPicker.pick(open, counts, t.sector, t.marketCapCr, current, indices = listOfNotNull("Nifty 50".takeIf { symbol in Nifty50.SYMBOLS }))
    }
    val valid = newName.isNotBlank() || chosen != null
    WatchDialog(
        title = title,
        confirmLabel = confirmLabel,
        confirmEnabled = valid,
        onConfirm = { scope.launch { error = onAdd(chosen.takeIf { newName.isBlank() }, newName.takeIf { it.isNotBlank() }, traits?.name) } },
        onDismiss = onDismiss
    ) {
        val t = traits
        Text(
            if (t == null) "Finding the right list…" else listOfNotNull(t.name, t.sector).joinToString(" · ").ifEmpty { "Pick a list" },
            color = MarksyTheme.TextSecondary, style = MarksyType.Small
        )
        if (lists.isNotEmpty()) PillRow {
            lists.forEach { l ->
                val n = counts[l.id] ?: 0
                val has = l.id in holding
                Pill(if (has) "${l.name} · added" else "${l.name} $n/${WatchlistRepository.MAX_STOCKS}", selected = chosen == l.id && newName.isBlank(), enabled = !has && n < WatchlistRepository.MAX_STOCKS) {
                    chosen = l.id; userPicked = true; newName = ""
                }
            }
        }
        ListNameField(
            newName, { newName = it; error = null }, lists.map { it.name }, listOfNotNull(t?.sector),
            label = if (lists.isEmpty()) "New list" else "Or a new list",
            placeholder = t?.sector?.let { "e.g. $it" } ?: "e.g. Defence"
        )
        error?.let { Text(it, color = MarksyTheme.Negative, style = MarksyType.Small) }
    }
}

/** List-name field: from 3 typed chars, suggested names under it; proposal pills below, unaffected by typing. */
@Composable
private fun ListNameField(name: String, onNameChange: (String) -> Unit, existing: List<String>, preferred: List<String>, label: String?, placeholder: String) {
    val context = LocalContext.current.applicationContext
    val sectors by produceState(emptyList<String>()) {
        value = quietly { (MarksyContainer.marketIntelligence(context).sectors() as? MarketDataState.Loaded)?.value?.map { it.name } }.orEmpty()
    }
    val suggestions = remember(name, existing, sectors) { WatchlistNames.suggest(name, existing, sectors) }
    val pills = remember(existing, preferred) { WatchlistNames.proposals(existing, preferred) }
    CompactTextField(name, onNameChange, Modifier.fillMaxWidth(), label = label, placeholder = placeholder)
    if (suggestions.isNotEmpty()) {
        val shape = MarksyShape.Field
        Column(Modifier.fillMaxWidth().clip(shape).background(MarksyTheme.SurfaceRaised).border(MarksySpace.Border, MarksyTheme.BorderGlow, shape)) {
            suggestions.forEach { s ->
                Text(
                    s, color = MarksyTheme.TextPrimary, style = MarksyType.Body,
                    modifier = Modifier.fillMaxWidth().clickable { onNameChange(s) }.padding(horizontal = MarksySpace.CardPadding, vertical = MarksySpace.ListGap)
                )
            }
        }
    }
    if (pills.isNotEmpty()) PillRow { pills.forEach { p -> Pill(p, selected = p.equals(name.trim(), ignoreCase = true)) { onNameChange(p) } } }
}

@Composable
private fun NewWatchlistDialog(existing: List<String>, onDismiss: () -> Unit, onCreate: suspend (String) -> Long?) {
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var taken by remember { mutableStateOf(false) }
    WatchDialog(
        title = "New watchlist",
        confirmLabel = "Create",
        confirmEnabled = name.isNotBlank(),
        onConfirm = { scope.launch { taken = onCreate(name) == null } },
        onDismiss = onDismiss
    ) {
        ListNameField(name, { name = it; taken = false }, existing, emptyList(), label = null, placeholder = "e.g. Defence, SmallCap")
        if (taken) Text("A list with that name already exists", color = MarksyTheme.Negative, style = MarksyType.Small)
    }
}
