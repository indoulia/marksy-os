package com.marksy.os.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
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
import androidx.compose.ui.unit.sp
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

const val WATCH_VIEW_PORTFOLIO = "portfolio"

private fun currentList(view: String, lists: List<WatchlistEntity>): WatchlistEntity? =
    if (view == WATCH_VIEW_PORTFOLIO) null else lists.firstOrNull { it.id.toString() == view } ?: lists.firstOrNull()

/** Title superscript: the open list and how full it is, or Portfolio. */
fun watchlistLabel(view: String, lists: List<WatchlistEntity>, items: List<WatchlistItemEntity>): String? {
    if (view == WATCH_VIEW_PORTFOLIO) return "Portfolio"
    val list = currentList(view, lists) ?: return null
    return "${list.name} ${items.count { it.watchlistId == list.id }}/${WatchlistRepository.MAX_STOCKS}"
}

/** Watchlist tab: named lists of up to 15 stocks each, plus a Portfolio placeholder. Search lives in the app header. */
@Composable
fun WatchlistScreen(
    repository: WatchlistRepository,
    lists: List<WatchlistEntity>,
    items: List<WatchlistItemEntity>,
    padding: PaddingValues,
    view: String,
    onViewSelected: (String) -> Unit,
    query: String,
    onSearchDone: () -> Unit,
    onOpenStock: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    val list = currentList(view, lists)
    val counts = remember(items) { items.groupingBy { it.watchlistId }.eachCount() }
    var adding by rememberSaveable { mutableStateOf<String?>(null) }
    var creating by rememberSaveable { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<WatchlistEntity?>(null) }

    Box(Modifier.fillMaxSize().background(MarksyTheme.Background).padding(padding).consumeWindowInsets(padding)) {
        val inner = PaddingValues(bottom = OneHandListBottomPadding)
        val q = query.trim()
        when {
            q.length >= 2 -> StockSuggestions(q, inner, onSymbolSelected = { adding = it }, emptyHint = "Try the company's NSE symbol, e.g. HAL.")
            view == WATCH_VIEW_PORTFOLIO -> Box(Modifier.padding(18.dp)) {
                EmptyState("Portfolio — coming soon", "Your holdings and their performance will show here.")
            }
            list == null -> Box(Modifier.padding(18.dp)) {
                EmptyState("No watchlists yet", "Tap + to create one, e.g. Defence or SmallCap, then tap search above to add stocks.")
            }
            else -> WatchlistRows(
                rows = items.filter { it.watchlistId == list.id },
                emptyName = list.name,
                padding = inner,
                onOpen = onOpenStock,
                onRemove = { symbol -> scope.launch { repository.remove(list.id, symbol) } }
            )
        }
        OneHandControls(
            // With no lists yet, "" stands for the empty Watchlists page so the filter button isn't in its reset (X) state.
            filters = (if (lists.isEmpty()) listOf("" to "Watchlists") else lists.map { it.id.toString() to it.name }) + (WATCH_VIEW_PORTFOLIO to "Portfolio"),
            selectedFilter = list?.id?.toString() ?: if (view == WATCH_VIEW_PORTFOLIO) view else "",
            onFilterSelected = onViewSelected,
            actions = listOfNotNull(
                list?.let { l -> FloatingAction(Icons.Default.Delete, "Delete ${l.name}") { deleting = l } },
                FloatingAction(Icons.Default.Add, "New watchlist") { creating = true }
            )
        )
    }

    adding?.let { symbol ->
        AddToWatchlistDialog(
            symbol = symbol,
            lists = lists,
            counts = counts,
            current = list?.id,
            onDismiss = { adding = null },
            onAdd = { listId, newName, name ->
                val id = listId ?: newName?.let { repository.createList(it) } ?: return@AddToWatchlistDialog "A list with that name already exists"
                when (repository.add(id, symbol, name)) {
                    WatchAdd.ADDED -> { adding = null; onViewSelected(id.toString()); onSearchDone(); null }
                    WatchAdd.ALREADY_THERE -> "$symbol is already in that list"
                    WatchAdd.FULL -> "That list already has ${WatchlistRepository.MAX_STOCKS} stocks"
                }
            }
        )
    }
    if (creating) NewWatchlistDialog(
        existing = lists.map { it.name },
        onDismiss = { creating = false },
        onCreate = { name -> repository.createList(name)?.also { creating = false; onViewSelected(it.toString()) } }
    )
    deleting?.let { l ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            containerColor = MarksyTheme.SurfaceRaised,
            title = { Text("Delete ${l.name}?", color = MarksyTheme.TextPrimary) },
            text = {
                val n = counts[l.id] ?: 0
                Text(if (n == 0) "The list is empty." else "Its $n stocks go with it; other lists keep theirs.", color = MarksyTheme.TextSecondary)
            },
            confirmButton = {
                TextButton(onClick = { deleting = null; scope.launch { repository.deleteList(l.id) }; onViewSelected("") }) {
                    Text("Delete", color = MarksyTheme.RedUrgent)
                }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel", color = MarksyTheme.TextSecondary) } }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WatchlistRows(rows: List<WatchlistItemEntity>, emptyName: String, padding: PaddingValues, onOpen: (String) -> Unit, onRemove: (String) -> Unit) {
    val quotes = rememberUpstoxQuotes(remember(rows) { rows.map { it.symbol } })
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 18.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(bottom = padding.calculateBottomPadding())
    ) {
        if (rows.isEmpty()) item { EmptyState("Nothing in $emptyName yet", "Tap search above to add up to ${WatchlistRepository.MAX_STOCKS} stocks.") }
        items(rows, key = { "w-${it.watchlistId}-${it.symbol}" }) { row ->
            val swipe = rememberSwipeToDismissBoxState()
            LaunchedEffect(swipe.currentValue) { if (swipe.currentValue == SwipeToDismissBoxValue.EndToStart) onRemove(row.symbol) }
            SwipeToDismissBox(
                state = swipe,
                enableDismissFromStartToEnd = false,
                backgroundContent = {
                    Box(
                        Modifier.fillMaxSize().background(MarksyTheme.RedUrgent, RoundedCornerShape(14.dp)).padding(end = 16.dp),
                        contentAlignment = Alignment.CenterEnd
                    ) { Icon(Icons.Default.Delete, contentDescription = "Remove ${row.symbol}", tint = MarksyTheme.TextPrimary) }
                }
            ) { WatchRow(row, quotes[row.symbol]) { onOpen(row.symbol) } }
        }
    }
}

@Composable
private fun WatchRow(row: WatchlistItemEntity, quote: com.marksy.os.upstox.UpstoxLtp?, onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier.fillMaxWidth().background(MarksyTheme.Surface, shape).border(1.dp, MarksyTheme.BorderGlow, shape).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(row.symbol, color = MarksyTheme.TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            row.name?.let { Text(it, color = MarksyTheme.TextMuted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        if (quote != null) Column(horizontalAlignment = Alignment.End) {
            Text("₹${money(quote.lastPrice)}", color = MarksyTheme.TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            quote.changePct?.let { pct ->
                Text(String.format(java.util.Locale.US, "%+.2f%%", pct), color = if (pct < 0) MarksyTheme.RedUrgent else MarksyTheme.PrimaryEmerald, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
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

/** [onAdd] returns an error to show, or null once added. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AddToWatchlistDialog(
    symbol: String,
    lists: List<WatchlistEntity>,
    counts: Map<Long, Int>,
    current: Long?,
    onDismiss: () -> Unit,
    onAdd: suspend (listId: Long?, newName: String?, name: String?) -> String?
) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val traits by produceState<StockTraits?>(null, symbol) { value = loadTraits(context, symbol) }
    var chosen by remember(symbol) { mutableStateOf(WatchlistPicker.pick(lists, counts, null, null, current)) }
    var userPicked by remember(symbol) { mutableStateOf(false) }
    var newName by remember(symbol) { mutableStateOf("") }
    var error by remember(symbol) { mutableStateOf<String?>(null) }
    LaunchedEffect(traits) {
        val t = traits ?: return@LaunchedEffect
        if (!userPicked) chosen = WatchlistPicker.pick(lists, counts, t.sector, t.marketCapCr, current)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MarksyTheme.SurfaceRaised,
        title = { Text("Add $symbol", color = MarksyTheme.TextPrimary) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val t = traits
                Text(
                    if (t == null) "Finding the right list…" else listOfNotNull(t.name, t.sector).joinToString(" · ").ifEmpty { "Pick a list" },
                    color = MarksyTheme.TextSecondary, fontSize = 12.sp
                )
                if (lists.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    lists.forEach { l ->
                        val n = counts[l.id] ?: 0
                        FilterChip(
                            selected = chosen == l.id && newName.isBlank(),
                            enabled = n < WatchlistRepository.MAX_STOCKS,
                            onClick = { chosen = l.id; userPicked = true; newName = "" },
                            label = { Text("${l.name} $n/${WatchlistRepository.MAX_STOCKS}", fontSize = 12.sp) }
                        )
                    }
                }
                ListNameField(
                    newName, { newName = it; error = null }, lists.map { it.name }, listOfNotNull(t?.sector),
                    label = if (lists.isEmpty()) "New list" else "Or a new list",
                    placeholder = t?.sector?.let { "e.g. $it" } ?: "e.g. Defence"
                )
                error?.let { Text(it, color = MarksyTheme.RedUrgent, fontSize = 12.sp) }
            }
        },
        confirmButton = {
            val valid = newName.isNotBlank() || chosen != null
            TextButton(enabled = valid, onClick = {
                scope.launch { error = onAdd(chosen.takeIf { newName.isBlank() }, newName.takeIf { it.isNotBlank() }, traits?.name) }
            }) { Text("Add", color = if (valid) MarksyTheme.PrimaryEmerald else MarksyTheme.TextMuted) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = MarksyTheme.TextSecondary) } }
    )
}

/** List-name field with proposal pills under it; from 3 typed chars the pills narrow to matches. */
@Composable
private fun ListNameField(name: String, onNameChange: (String) -> Unit, existing: List<String>, preferred: List<String>, label: String?, placeholder: String) {
    val context = LocalContext.current.applicationContext
    val sectors by produceState(emptyList<String>()) {
        value = quietly { (MarksyContainer.marketIntelligence(context).sectors() as? MarketDataState.Loaded)?.value?.map { it.name } }.orEmpty()
    }
    val pills = remember(name, existing, preferred, sectors) { WatchlistNames.suggest(name, existing, preferred, sectors) }
    CompactTextField(name, onNameChange, Modifier.fillMaxWidth(), label = label, placeholder = placeholder)
    if (pills.isNotEmpty()) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        pills.forEach { pill -> SuggestionChip(onClick = { onNameChange(pill) }, label = { Text(pill, fontSize = 12.sp) }) }
    }
}

@Composable
private fun NewWatchlistDialog(existing: List<String>, onDismiss: () -> Unit, onCreate: suspend (String) -> Long?) {
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var taken by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MarksyTheme.SurfaceRaised,
        title = { Text("New watchlist", color = MarksyTheme.TextPrimary) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ListNameField(name, { name = it; taken = false }, existing, emptyList(), label = null, placeholder = "e.g. Defence, SmallCap")
                if (taken) Text("A list with that name already exists", color = MarksyTheme.RedUrgent, fontSize = 12.sp)
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = { scope.launch { taken = onCreate(name) == null } }) {
                Text("Create", color = if (name.isNotBlank()) MarksyTheme.PrimaryEmerald else MarksyTheme.TextMuted)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = MarksyTheme.TextSecondary) } }
    )
}
