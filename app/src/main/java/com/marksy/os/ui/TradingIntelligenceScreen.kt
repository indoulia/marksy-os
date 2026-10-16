package com.marksy.os.ui

import com.marksy.os.gateway.MarketState
import java.util.Locale
import java.time.LocalDate
import com.marksy.os.market.MarketDataState

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.marksy.os.EmptyState
import com.marksy.os.MarksyFormat

@Composable
fun TradingIntelligenceScreen(
    padding: PaddingValues,
    market: MarketState = MarketState.Loading,
    selectedFilter: String = TradingFilters.first(),
    onFilterSelected: (String) -> Unit = {},
    onOpenStock: (String) -> Unit = {},
    marketRepository: com.marksy.os.market.MarketIntelligenceRepository? = null,
    openScope: OpenScope = OpenScope.ALL,
    onOpenScopeChange: (OpenScope) -> Unit = {},
    historyScope: HistoryScope = HistoryScope.ALL,
    onHistoryScopeChange: (HistoryScope) -> Unit = {},
    onOpenScorecard: (com.marksy.os.market.ScorecardSource) -> Unit = {}
) {
    // Open: live tips. Setups: Marksy's tips still waiting to be bought. History: how tips ended. Captured tips are reviewed in Inbox.
    Box(
        Modifier
            .fillMaxSize()
            .background(MarksyTheme.Background)
            .padding(padding)
            .consumeWindowInsets(padding)
    ) {
        if (marketRepository == null) {
            Box(Modifier.padding(MarksySpace.Gutter)) { EmptyState("Marksy is not connected", "Sign in to your Marksy account in More.") }
        } else {
            TradingViews(marketRepository, selectedFilter, onFilterSelected, onOpenStock, openScope, onOpenScopeChange, historyScope, onHistoryScopeChange, onOpenScorecard)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TradingViews(
    repository: com.marksy.os.market.MarketIntelligenceRepository, view: String, onView: (String) -> Unit, onOpenStock: (String) -> Unit,
    openScope: OpenScope, onOpenScope: (OpenScope) -> Unit, historyScope: HistoryScope, onHistoryScope: (HistoryScope) -> Unit,
    onScore: (com.marksy.os.market.ScorecardSource) -> Unit
) {
    val feed = remember(repository) { TradingFeed(repository) }
    LaunchedEffect(feed) { feed.load() }
    var setupsScope by rememberSaveable { mutableStateOf(SetupsScope.ALL) }
    val scan by com.marksy.os.market.MarketIntelligenceRepository.latestScan.collectAsState()
    val symbols = remember(feed.myOpen.items, feed.marksyActive.items, feed.myClosed.items, feed.marksyClosed.items, view) {
        when (view) {
            TAB_HISTORY -> feed.myClosed.items.map { it.tip.symbol } + feed.marksyClosed.items.map { it.symbol }
            else -> feed.myOpen.items.map { it.tip.symbol } + feed.marksyActive.items.map { it.symbol }
        }.distinct()
    }
    val quotes = rememberUpstoxQuotes(symbols)
    val today = LocalDate.now(java.time.ZoneId.of("Asia/Kolkata"))
    val lists = remember(feed.myOpen.items, feed.myClosed.items, feed.marksyActive.items, feed.marksyClosed.items, feed.engine, quotes, today) {
        TradingLists.build(feed.myOpen.items, feed.myClosed.items, feed.marksyActive.items, feed.marksyClosed.items, feed.engine, quotes, today)
    }
    // Counts wait for every up-front page: a first page alone once showed History 19 before it settled at 79.
    val openReady = feed.myOpen.settled && feed.marksyActive.settled
    val historyReady = feed.myClosed.settled && feed.marksyClosed.settled && feed.marksyActive.settled
    val counts = mapOf(
        TAB_OPEN to lists.open.size.takeIf { openReady },
        TAB_PICKS to lists.setups.size.takeIf { feed.marksyActive.settled },
        TAB_HISTORY to lists.history(HistoryScope.ALL).size.takeIf { historyReady }
    )
    var ticket by remember { mutableStateOf<TradeIntent?>(null) }
    ticket?.let { TradeTicketSheet(it) { ticket = null } }
    var sheet by remember { mutableStateOf<TipCardModel?>(null) }
    sheet?.let { m ->
        TipSheet(m, repository, onDismiss = { sheet = null }, onTrade = { sheet = null; ticket = it }, onOpenStock = { sheet = null; onOpenStock(it) }, onScore = { sheet = null; onScore(it) })
    }
    var daily by remember { mutableStateOf<Pair<com.marksy.os.market.LedgerTipDto, com.marksy.os.market.AlsoTippedDto>?>(null) }
    daily?.let { (t, o) -> DayByDayDialog(repository, t, o) { daily = null } }
    var expanded by rememberSaveable { mutableStateOf(setOf<String>()) }
    var voidedOpen by rememberSaveable { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        // Sticky: the view switch and its scopes stay put while the list scrolls under them.
        Column(
            Modifier.fillMaxWidth().background(MarksyTheme.Background).padding(start = MarksySpace.Gutter, end = MarksySpace.Gutter, top = MarksySpace.Tight, bottom = MarksySpace.Gap),
            verticalArrangement = Arrangement.spacedBy(MarksySpace.Gap)
        ) {
            MarksySegmented(TradingFilters, view, onView, Modifier.fillMaxWidth(), count = { counts[it] })
            FlowRow(horizontalArrangement = Arrangement.spacedBy(MarksySpace.Inner), verticalArrangement = Arrangement.spacedBy(MarksySpace.Inner)) {
                when (view) {
                    TAB_PICKS -> SetupsScope.entries.forEach { s ->
                        ScopePill(s.label, lists.setups(s, scan).size.takeIf { feed.marksyActive.settled }, s == setupsScope) { setupsScope = s }
                    }
                    TAB_HISTORY -> HistoryScope.entries.forEach { s ->
                        ScopePill(s.label, lists.history(s).size.takeIf { historyReady }, s == historyScope) { onHistoryScope(s) }
                    }
                    else -> OpenScope.entries.forEach { s ->
                        // Following lists alerts and follows, not tips, so it has no tip count.
                        ScopePill(s.label, lists.open(s).size.takeIf { openReady && s != OpenScope.FOLLOWING }, s == openScope) { onOpenScope(s) }
                    }
                }
            }
        }
        MarksyDivider()
        Box(Modifier.weight(1f)) {
            if (view == TAB_OPEN && openScope == OpenScope.FOLLOWING) {
                FollowingView(repository, MarksySpace.Wide, onOpenStock, onTrade = { ticket = it }, onScore = onScore)
            } else {
                TradingList(view, openScope, setupsScope, historyScope, feed, lists, scan, voidedOpen, { voidedOpen = !voidedOpen }, expanded,
                    onToggle = { k -> expanded = if (k in expanded) expanded - k else expanded + k },
                    onOpen = { sheet = it }, onTrade = { ticket = it }, onScore = onScore, onSourceRow = { t, o -> daily = t to o })
            }
        }
    }
}

@Composable
private fun TradingList(
    view: String, openScope: OpenScope, setupsScope: SetupsScope, historyScope: HistoryScope, feed: TradingFeed, lists: TradingLists,
    scan: com.marksy.os.market.LatestScanDto?, voidedOpen: Boolean, onToggleVoided: () -> Unit, expanded: Set<String>, onToggle: (String) -> Unit,
    onOpen: (TipCardModel) -> Unit, onTrade: (TradeIntent) -> Unit, onScore: (com.marksy.os.market.ScorecardSource) -> Unit,
    onSourceRow: (com.marksy.os.market.LedgerTipDto, com.marksy.os.market.AlsoTippedDto) -> Unit
) {
    val cards = when (view) {
        TAB_PICKS -> lists.setups(setupsScope, scan)
        TAB_HISTORY -> lists.history(historyScope)
        else -> lists.open(openScope)
    }
    val voided = if (view == TAB_HISTORY) lists.voided(historyScope) else emptyList()
    val loading = when (view) {
        TAB_PICKS -> feed.marksyActive.loading
        TAB_HISTORY -> feed.myClosed.loading || feed.marksyClosed.loading || feed.marksyActive.loading
        else -> feed.myOpen.loading || feed.marksyActive.loading
    }
    val summary = when (view) {
        TAB_PICKS -> TradingLists.setupsSummary(cards.size, scan).takeIf { cards.isNotEmpty() }
        TAB_HISTORY -> TradingLists.historySummary(if (historyScope == HistoryScope.MINE) feed.myRecord else feed.engine?.body)
        else -> TradingLists.openSummary(cards).takeIf { cards.isNotEmpty() }
    }
    MarksyList(bottom = MarksySpace.Wide) {
        summary?.let { s -> item(key = "summary") { SummaryStrip(s) } }
        items(cards, key = { it.key }) { m ->
            TipCard(
                m, expanded = m.key in expanded, onToggleSources = { onToggle(m.key) }, onOpen = { onOpen(m) }, onTrade = onTrade, onScore = onScore,
                onSourceRow = { o -> m.ledger?.let { onSourceRow(it, o) } }, impliedStatus = impliedStatus(view)
            )
        }
        if (cards.isEmpty()) item(key = "empty") { if (loading) MarksyLoader("Loading tips…") else TradingEmpty(view, openScope, feed, lists, scan) }
        if (voided.isNotEmpty()) {
            val n = voided.size
            item(key = "voided-fold") { FoldRow(VOIDED, "$n tip${if (n == 1) "" else "s"} · voided before anyone could buy · not scored", voidedOpen, onToggleVoided) }
            if (voidedOpen) items(voided, key = { "v-" + it.key }) { m ->
                TipCard(m, expanded = false, onToggleSources = {}, onOpen = { onOpen(m) }, onTrade = onTrade, onScore = onScore, onSourceRow = {}, impliedStatus = VOIDED)
            }
        }
    }
}

@Composable
private fun ScopePill(label: String, count: Int?, selected: Boolean, onClick: () -> Unit) =
    Pill(label + (count?.let { " $it" } ?: ""), selected = selected, onClick = onClick)

@Composable
private fun TradingEmpty(view: String, scope: OpenScope, feed: TradingFeed, lists: TradingLists, scan: com.marksy.os.market.LatestScanDto?) {
    val sources = listOf(feed.myOpen, feed.marksyActive, feed.myClosed, feed.marksyClosed)
    val error = sources.firstNotNullOfOrNull { it.state as? MarketDataState.Error }
    when {
        sources.all { it.state is MarketDataState.Unavailable } -> EmptyState("Marksy is not connected", "Sign in to your Marksy account in More.")
        view == TAB_PICKS -> EmptyState(
            "No live Marksy setups right now",
            if (lists.marksyOpen.isNotEmpty()) "Marksy's bought tips are in Open · Marksy."
            else "Marksy's new tips appear here after each market close." + scan?.let(com.marksy.os.market.PicksBasis::label)?.let { " Last scan: $it." }.orEmpty()
        )
        view == TAB_HISTORY -> EmptyState("No closed tips yet", error?.message ?: "Tips appear here once their target, stop or horizon is reached.")
        scope == OpenScope.MARKSY -> EmptyState("No open Marksy tips", "Marksy's setups move here once they are bought.")
        else -> EmptyState("No open tips", error?.message ?: "Tips from your broker apps, allow-listed groups and SMS senders appear here once Marksy records them.")
    }
}

private const val VOIDED = "Voided before buy"

/** The card status every row of a view shares, which the view's own name already says. */
private fun impliedStatus(view: String): String? = when (view) {
    TAB_OPEN -> "Active"
    TAB_PICKS -> "Waiting to buy"
    else -> null
}

private const val TAB_OPEN = "Open"
private const val TAB_PICKS = "Setups"
private const val TAB_HISTORY = "History"
val TradingFilters = listOf(TAB_OPEN, TAB_PICKS, TAB_HISTORY)
/** The Open view, whose Following scope a tip-alert notification opens. */
const val TradingTipsFilter = TAB_OPEN
const val TradingSetupsFilter = TAB_PICKS
/** Ask Marksy's argument for Marksy's results: History, on Marksy's closed tips. */
const val TradingMarksyResults = "Marksy results"

/** An Ask Marksy argument → the view to show and, for Marksy's results, the History list; anything else opens Open. */
fun tradingRoute(arg: String?): Pair<String, HistoryScope?> =
    if (arg == TradingMarksyResults) TAB_HISTORY to HistoryScope.MARKSY else (TradingFilters.firstOrNull { it == arg } ?: TAB_OPEN) to null

/** Title superscript: the view, plus Marksy's scan session or the chosen scope. */
fun tradingTitleNote(
    filter: String,
    scan: com.marksy.os.market.LatestScanDto?,
    openScope: OpenScope = OpenScope.ALL,
    historyScope: HistoryScope = HistoryScope.ALL
): String = when (filter) {
    TAB_PICKS -> filter + com.marksy.os.market.PicksBasis.day(scan?.scanSessionDate)?.let { " · $it" }.orEmpty()
    TAB_HISTORY -> filter + (if (historyScope != HistoryScope.ALL) " · ${historyScope.label}" else "")
    else -> TAB_OPEN + (if (openScope != OpenScope.ALL) " · ${openScope.label}" else "")
}


private fun marketMessage(market: MarketState, loaded: String): String = when (market) {
    MarketState.Loading -> "Loading market data…"
    MarketState.NotConfigured -> "Marksy is not connected"
    is MarketState.Unavailable -> "Marksy market data is unavailable right now."
    is MarketState.Loaded -> loaded
}

private fun changeColor(change: String): Color = if (change.startsWith("-") || change.startsWith(MarksyFormat.MINUS)) MarksyTheme.Negative else MarksyTheme.PrimaryEmerald

@Composable
private fun TradingTickerCard(
    symbol: String,
    price: String,
    change: String
) {
    MarksyCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(MarksySize.IconLarge)
                        .clip(CircleShape)
                        .background(MarksyTheme.BadgeFinanceBg),
                    contentAlignment = Alignment.Center
                ) {
                    Text("📊", style = MarksyType.Small)
                }
                Spacer(Modifier.width(MarksySpace.Gap))
                Text(symbol, color = MarksyTheme.TextPrimary, style = MarksyType.Subhead, fontWeight = FontWeight.Bold)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(price, color = MarksyTheme.TextPrimary, style = MarksyType.Subhead, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(MarksySpace.Inner))
                Text(change, color = changeColor(change), style = MarksyType.Small, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun CapturedInsightCard(
    insight: TradingInsight,
    onClick: (() -> Unit)?
) {
    MarksyCard(onClick = onClick) {
        Column {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    insight.source.ifBlank { "Trading Source" },
                    color = MarksyTheme.PrimaryEmerald,
                    fontWeight = FontWeight.Bold,
                    style = MarksyType.Body
                )
                Text(listOfNotNull(compactTime(insight.postedAt), insight.status).joinToString(" · "), color = MarksyTheme.TextMuted, style = MarksyType.Caption)
            }
            Spacer(Modifier.height(MarksySpace.Tight))
            Text(
                insight.headline.ifBlank { "Trading Alert" },
                color = MarksyTheme.TextPrimary,
                style = MarksyType.Subhead,
                fontWeight = FontWeight.SemiBold
            )
            if (insight.body.isNotBlank()) {
                Text(
                    insight.body,
                    color = MarksyTheme.TextSecondary,
                    style = MarksyType.Small,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = MarksySpace.Tight)
                )
            }
        }
    }
}
