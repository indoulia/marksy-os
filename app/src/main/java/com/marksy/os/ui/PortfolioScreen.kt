package com.marksy.os.ui

import android.app.Activity
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.TrendingDown
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.PieChart
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.marksy.os.EmptyState
import com.marksy.os.MarksyFormat
import com.marksy.os.alerts.PriceAlertStore
import com.marksy.os.portfolio.AllocationBy
import com.marksy.os.portfolio.AllocationSlice
import com.marksy.os.portfolio.FlagKind
import com.marksy.os.portfolio.FlagSeverity
import com.marksy.os.portfolio.HoldingFlag
import com.marksy.os.portfolio.HoldingRow
import com.marksy.os.portfolio.HoldingSort
import com.marksy.os.portfolio.HoldingType
import com.marksy.os.portfolio.HoldingsResult
import com.marksy.os.portfolio.LivePrice
import com.marksy.os.portfolio.NeedsLook
import com.marksy.os.portfolio.PortfolioConnection
import com.marksy.os.portfolio.PortfolioFlags
import com.marksy.os.portfolio.PortfolioHistory
import com.marksy.os.portfolio.PortfolioMath
import com.marksy.os.portfolio.PortfolioMetric
import com.marksy.os.portfolio.PortfolioPeriod
import com.marksy.os.portfolio.PortfolioProvider
import com.marksy.os.portfolio.PortfolioRepository
import com.marksy.os.portfolio.PortfolioTotals
import com.marksy.os.portfolio.PortfolioView
import com.marksy.os.upstox.Candle
import com.marksy.os.upstox.FeedFreshness
import com.marksy.os.upstox.UpstoxCandles
import com.marksy.os.upstox.UpstoxFeed
import com.marksy.os.upstox.UpstoxIndices
import com.marksy.os.upstox.UpstoxLtp
import kotlinx.coroutines.flow.MutableStateFlow
import java.time.LocalDate
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt

/** What Market's header note and floating stack need from the Portfolio page. */
object PortfolioChrome {
    val note = MutableStateFlow<String?>(null)
    val hasHoldings = MutableStateFlow(false)

    // The page leaves composition whenever a stock opens; its choices live here for as long as the app runs.
    internal val metric = mutableStateOf(PortfolioMetric.PERIOD)
    internal val period = mutableStateOf(PortfolioPeriod.D1)
    internal val sort = mutableStateOf(HoldingSort.MOVE)
    internal val show = mutableStateOf<HoldingType?>(null)
    internal val open = mutableStateOf<String?>(null)
    internal val allocationOpen = mutableStateOf(false)
    internal val allocationBy = mutableStateOf(AllocationBy.SECTOR)
    internal val needsOpen = mutableStateOf(false)
    internal val signedOutReason = mutableStateOf<String?>(null)
}

/** Market › Portfolio, triage first: totals, Needs a look, then holdings ranked by rupee move. Read-only. */
@Composable
fun PortfolioScreen(padding: PaddingValues, query: String, sortOpen: Boolean, onSortDismiss: () -> Unit, onOpenStock: (String) -> Unit) {
    val context = LocalContext.current
    val repo = remember { PortfolioRepository(context) }
    val refresh = rememberRefreshState()
    var snapshot by remember { mutableStateOf(repo.cached()) }
    var connection by remember { mutableStateOf(repo.connection()) }
    var loading by remember { mutableStateOf(snapshot == null && connection == PortfolioConnection.SIGNED_IN) }
    var error by remember { mutableStateOf<String?>(null) }
    var hidden by remember { mutableStateOf(repo.hidden(System.currentTimeMillis())) }
    var settled by remember { mutableIntStateOf(0) }
    var order by remember { mutableStateOf(emptyList<String>()) }
    var signedOutReason by PortfolioChrome.signedOutReason
    var metric by PortfolioChrome.metric
    var period by PortfolioChrome.period
    var sort by PortfolioChrome.sort
    var show by PortfolioChrome.show
    var open by PortfolioChrome.open
    var allocationOpen by PortfolioChrome.allocationOpen
    var allocationBy by PortfolioChrome.allocationBy
    var needsOpen by PortfolioChrome.needsOpen
    var keysOpen by remember { mutableStateOf(false) }
    var why by remember { mutableStateOf<NeedsLook?>(null) }
    var alerting by remember { mutableStateOf<HoldingRow?>(null) }

    val signIn = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { if (it.resultCode == Activity.RESULT_OK) refresh.refresh() }
    val launchSignIn = { signIn.launch(Intent(context, UpstoxSignInActivity::class.java)) }
    val startSignIn: () -> Unit = { if (repo.store.hasCredentials()) { launchSignIn() } else { keysOpen = true } }

    LaunchedEffect(refresh.key) {
        val now = System.currentTimeMillis()
        connection = repo.connection(now)
        if (connection == PortfolioConnection.SIGNED_IN) {
            loading = snapshot == null
            when (val r = repo.refresh(now, force = refresh.key > 0)) {
                is HoldingsResult.Ok -> { snapshot = r.snapshot; error = null; signedOutReason = null }
                is HoldingsResult.SignedOut -> { connection = repo.connection(now); error = null; signedOutReason = r.reason }
                is HoldingsResult.Failed -> error = r.message
            }
            loading = false
        }
        hidden = repo.hidden(now)
        settled++
        refresh.done()
    }

    val holdings = snapshot?.holdings.orEmpty()
    val keys = remember(holdings) { holdings.map { it.instrumentKey } }
    val owner = remember { Any() }
    LaunchedEffect(keys) { UpstoxFeed.acquire(owner, keys + UpstoxIndices.NIFTY_50) }
    DisposableEffect(owner) { onDispose { UpstoxFeed.release(owner) } }
    val feed = UpstoxFeed.quotes.collectAsStateWithLifecycle()
    // Only this page's instruments: ticks for other screens' keys don't recompose it.
    val live by remember(keys) { derivedStateOf { keys.mapNotNull { k -> feed.value[k]?.let { k to LivePrice(it.lastPrice, it.previousClose) } }.toMap() } }
    val niftyLive by remember { derivedStateOf { feed.value[UpstoxIndices.NIFTY_50] } }
    val freshness = rememberFeedFreshness()
    val today = remember { LocalDate.now(PortfolioRepository.IST) }
    val history by produceState<PortfolioHistory?>(null, keys) { value = if (holdings.isEmpty()) null else repo.history(holdings, today) }
    val intraday by produceState<Map<String, List<Candle>>?>(null, keys, refresh.key) {
        value = if (holdings.isEmpty()) null else repo.intraday(holdings, today, force = refresh.key > 0)
    }

    val bases = remember(history, period) { PortfolioRepository.bases(history, period) }
    val rows = PortfolioMath.rows(holdings, live, period, bases)
    val totals = PortfolioMath.totals(rows)
    val alerts by remember { PriceAlertStore.alerts(context.applicationContext) }.collectAsStateWithLifecycle()
    val needs = PortfolioFlags.needsALook(rows, alerts.orEmpty(), hidden)
    val flagged = rows.associate { r -> r.holding.symbol to PortfolioFlags.flags(r, alerts.orEmpty()).minByOrNull { it.severity.ordinal }?.severity }
    // Ticks change numbers, never order: rows re-rank only on load, refresh, or a metric, period or sort change.
    val latestRows by rememberUpdatedState(rows)
    LaunchedEffect(settled, metric, period, sort, bases) { order = PortfolioMath.order(latestRows, metric, sort) }

    val series = remember(history, intraday, period, holdings) {
        val candles = if (period == PortfolioPeriod.D1) intraday.orEmpty() else history?.daily.orEmpty().mapValues { PortfolioMath.window(it.value, period) }
        PortfolioMath.valueSeries(holdings, candles).map { it.second }
    }.let { if (it.isEmpty()) it else it + totals.value }
    val nifty = niftyChange(history, niftyLive, period)
    val badge = when {
        freshness == FeedFreshness.LIVE && live.isNotEmpty() -> null
        freshness == FeedFreshness.CLOSED && live.isNotEmpty() -> "Closed"
        else -> snapshot?.fetchedAt?.let { compactTime(it) }?.let { "as of $it" }
    }

    val q = query.trim()
    val visible = PortfolioMath.arrange(rows, order).filter { r ->
        (show == null || r.holding.type == show) && (q.isEmpty() || r.holding.symbol.contains(q, ignoreCase = true) || r.holding.name.contains(q, ignoreCase = true))
    }
    val sectorPct = PortfolioMath.allocation(rows, AllocationBy.SECTOR).associate { it.name to it.pct }

    val view = PortfolioView.of(snapshot, connection, loading, error)
    val note = when (view) {
        PortfolioView.LOADING -> "Portfolio"
        PortfolioView.CONNECT -> "Portfolio · not connected"
        PortfolioView.LOAD_FAILED -> "Portfolio · couldn't load"
        PortfolioView.EMPTY -> "Portfolio · no holdings yet"
        PortfolioView.HOLDINGS -> when {
            q.isNotEmpty() -> "Portfolio · ${visible.size} match \"$q\""
            // One fact after the section so the note fits beside the header icons on a 360dp phone.
            connection == PortfolioConnection.SIGNED_OUT -> "Portfolio · signed out"
            needs.isNotEmpty() -> "Portfolio · ${needs.size} need${if (needs.size == 1) "s" else ""} a look"
            else -> "Portfolio · ${plural(holdings.size, "holding")}"
        }
    }
    SideEffect {
        PortfolioChrome.note.value = note
        PortfolioChrome.hasHoldings.value = holdings.isNotEmpty()
    }

    MarksyRefreshBox(refresh) {
        LazyColumn(
            Modifier.fillMaxSize().padding(horizontal = MarksySpace.Gutter),
            verticalArrangement = Arrangement.spacedBy(MarksySpace.ListGap),
            contentPadding = PaddingValues(top = MarksySpace.Tight, bottom = padding.calculateBottomPadding())
        ) {
            val snap = snapshot
            when (view) {
                PortfolioView.LOADING -> item(key = "loading") { MarksyLoader("Loading your Upstox holdings…") }
                PortfolioView.CONNECT -> item(key = "connect") { ConnectCard(repo.providers, startSignIn) }
                PortfolioView.LOAD_FAILED -> item(key = "load-failed") { LoadFailedCard(error.orEmpty(), refresh::refresh) }
                PortfolioView.EMPTY -> item(key = "empty") {
                    EmptyState("No holdings in your Upstox account", "Stocks and ETFs you own show here. A buy shows up the next morning, once it settles.")
                }
                PortfolioView.HOLDINGS -> if (snap != null) {
                    if (connection == PortfolioConnection.SIGNED_OUT) item(key = "signed-out") { SignedOutCard(snap.fetchedAt, live.isNotEmpty(), signedOutReason, startSignIn) }
                    error?.let { e -> item(key = "error") { Caveat("Couldn't refresh from Upstox ($e). Showing holdings from ${compactTime(snap.fetchedAt) ?: "earlier"}.") } }
                    item(key = "hero") {
                        PortfolioHero(
                            totals, metric, { metric = it }, period, { period = it }, badge, series, nifty,
                            rows, allocationOpen, { allocationOpen = !allocationOpen }, allocationBy, { allocationBy = it }
                        )
                    }
                    if (q.isEmpty() && needs.isNotEmpty()) {
                        item(key = "needs-lane") { SectionLabel("Needs a look", needs.size, MarksyTheme.Negative) }
                        items(if (needsOpen) needs else needs.take(3), key = { "need-${it.row.holding.symbol}" }) { n ->
                            NeedsCard(
                                n, sectorPct, onWhy = { why = n }, onOpen = { onOpenStock(n.row.holding.symbol) }, onAlert = { alerting = n.row },
                                onHide = {
                                    val now = System.currentTimeMillis()
                                    repo.hide(n.row.holding.symbol, now)
                                    hidden = repo.hidden(now)
                                }
                            )
                        }
                        if (needs.size > 3) item(key = "needs-more") {
                            MoreRow(if (needsOpen) "Show less" else "${needs.size - 3} more need a look", if (needsOpen) "" else needs.drop(3).joinToString(", ") { it.row.holding.symbol }) { needsOpen = !needsOpen }
                        }
                    }
                    HoldingType.entries.forEach { type ->
                        val list = visible.filter { it.holding.type == type }
                        if (list.isNotEmpty()) {
                            item(key = "lane-$type") {
                                val sum = list.sumOf { it.pnl(metric) ?: 0.0 }
                                SectionLabel(type.label, list.size, MarksyTheme.BlueFinance) {
                                    Text(MarksyFormat.signedRupees(sum, 0), color = pnlColor(sum), style = MarksyType.Meta, fontWeight = FontWeight.Bold)
                                }
                            }
                            item(key = "stack-$type") {
                                HoldingsStack(list, metric, period, flagged, open, onToggle = { s -> open = if (open == s) null else s }, onOpen = onOpenStock, onAlert = { alerting = it })
                            }
                        }
                    }
                    if (q.isNotEmpty() && visible.isEmpty()) item(key = "no-match") { EmptyState("No holding matches \"$q\"", "Search looks at symbols and company names.") }
                    if (q.isEmpty() && show == null) {
                        item(key = "mf-lane") { SectionLabel("Mutual funds", dot = MarksyTheme.BlueFinance) }
                        item(key = "mf") { MutualFundsPlaceholder() }
                    }
                    if (hidden.isNotEmpty()) item(key = "hidden") { HiddenFooter(hidden.size) { repo.unhideAll(); hidden = emptySet() } }
                }
            }
        }
    }

    if (sortOpen) PortfolioSortDialog(sort, show, { sort = it }, { show = it }, onSortDismiss)
    why?.let { n -> WhyDialog(n, onOpen = { why = null; onOpenStock(n.row.holding.symbol) }, onDismiss = { why = null }) }
    alerting?.let { r -> PriceAlertDialog(r.holding.symbol, r.price) { alerting = null } }
    if (keysOpen) UpstoxAppKeysDialog(repo.store, onDismiss = { keysOpen = false }) { keysOpen = false; launchSignIn() }
}

/** NIFTY 50 over the same period: live day change when streaming, else from its daily candles. */
private fun niftyChange(history: PortfolioHistory?, live: UpstoxLtp?, period: PortfolioPeriod): Double? {
    val candles = history?.nifty.orEmpty()
    if (period == PortfolioPeriod.D1) {
        return live?.changePct ?: candles.takeLast(2).takeIf { it.size == 2 }?.let { (a, b) -> (b.close / a.close - 1) * 100 }
    }
    val last = live?.lastPrice ?: candles.lastOrNull()?.close ?: return null
    return UpstoxCandles.bases(candles)[period.label]?.let { (last / it - 1) * 100 }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PortfolioHero(
    totals: PortfolioTotals,
    metric: PortfolioMetric,
    onMetric: (PortfolioMetric) -> Unit,
    period: PortfolioPeriod,
    onPeriod: (PortfolioPeriod) -> Unit,
    badge: String?,
    series: List<Double>,
    nifty: Double?,
    rows: List<HoldingRow>,
    allocationOpen: Boolean,
    onAllocationToggle: () -> Unit,
    allocationBy: AllocationBy,
    onAllocationBy: (AllocationBy) -> Unit
) {
    MarksyCard {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Current value", color = MarksyTheme.TextMuted, style = MarksyType.Meta)
                    badge?.let {
                        Spacer(Modifier.width(MarksySpace.Inner))
                        MarksyBadge(it, MarksyTheme.TextSecondary, MarksyTheme.SurfaceRaised)
                    }
                }
                Text(MarksyFormat.rupees(totals.value, 0), color = MarksyTheme.TextPrimary, style = MarksyType.Display, fontWeight = FontWeight.Bold, maxLines = 1)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("Invested", color = MarksyTheme.TextMuted, style = MarksyType.Meta)
                Text(MarksyFormat.rupees(totals.invested, 0), color = MarksyTheme.TextSecondary, style = MarksyType.Body, fontWeight = FontWeight.Medium)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(MarksySpace.Gap)) {
            PnlBox(period.title, totals.periodPnl.takeIf { totals.periodPct != null }, totals.periodPct, metric == PortfolioMetric.PERIOD, Modifier.weight(1f)) { onMetric(PortfolioMetric.PERIOD) }
            PnlBox("Total", totals.totalPnl, totals.totalPct, metric == PortfolioMetric.TOTAL, Modifier.weight(1f)) { onMetric(PortfolioMetric.TOTAL) }
        }
        if (series.size >= 2) {
            // Relative to the period start: Sparkline's scale always includes zero.
            Sparkline(series.map { it - series.first() }, if (series.last() >= series.first()) MarksyTheme.Positive else MarksyTheme.Negative, Modifier.fillMaxWidth().height(52.dp))
        }
        if (series.size >= 2 || nifty != null) Row(verticalAlignment = Alignment.CenterVertically) {
            if (series.size >= 2) Text(if (period == PortfolioPeriod.D1) "9:15 am" else "${period.title} ago", color = MarksyTheme.TextMuted, style = MarksyType.Caption)
            Spacer(Modifier.weight(1f))
            nifty?.let {
                Text("NIFTY 50 ", color = MarksyTheme.TextMuted, style = MarksyType.Caption)
                Text(MarksyFormat.percent(it), color = pnlColor(it), style = MarksyType.Caption, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.weight(1f))
            if (series.size >= 2) Text("now", color = MarksyTheme.TextMuted, style = MarksyType.Caption)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(MarksySpace.Inner), verticalArrangement = Arrangement.spacedBy(MarksySpace.Inner)) {
            PortfolioPeriod.entries.forEach { p -> Pill(p.label, selected = p == period) { onPeriod(p) } }
        }
        if (period != PortfolioPeriod.D1) Text("Assumes today's holdings for the whole period.", color = MarksyTheme.TextMuted, style = MarksyType.Meta)
        AllocationBar(rows, allocationOpen, onAllocationToggle, allocationBy, onAllocationBy)
    }
}

@Composable
private fun PnlBox(title: String, pnl: Double?, pct: Double?, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val shape = MarksyShape.Pill
    Column(
        modifier.clip(shape)
            .background(if (selected) MarksyTheme.BadgeTradingBg else MarksyTheme.Background)
            .border(MarksySpace.Border, if (selected) MarksyTheme.PrimaryEmerald else MarksyTheme.BorderGlow, shape)
            .clickable(onClickLabel = "Show $title on every row", onClick = onClick)
            .padding(horizontal = MarksySpace.ListGap, vertical = MarksySpace.Gap)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = MarksyTheme.TextSecondary, style = MarksyType.Meta, modifier = Modifier.weight(1f))
            if (selected) Icon(Icons.Default.Check, contentDescription = "Rows show $title", tint = MarksyTheme.PrimaryEmerald, modifier = Modifier.size(MarksySize.IconSmall))
        }
        Text(pnl?.let { MarksyFormat.signedRupees(it, 0) } ?: "—", color = pnlColor(pnl), style = MarksyType.Lead, fontWeight = FontWeight.Bold, maxLines = 1)
        Text(pct?.let { MarksyFormat.percent(it) } ?: " ", color = pnlColor(pct), style = MarksyType.Meta, fontWeight = FontWeight.SemiBold)
    }
}

private val SliceColors = listOf(MarksyTheme.BlueFinance, MarksyTheme.EmailBlue, MarksyTheme.BlueFinance.copy(alpha = .6f), MarksyTheme.BlueFinance.copy(alpha = .35f))

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AllocationBar(rows: List<HoldingRow>, open: Boolean, onToggle: () -> Unit, by: AllocationBy, onBy: (AllocationBy) -> Unit) {
    val sectors = PortfolioMath.allocation(rows, AllocationBy.SECTOR)
    val top = sectors.take(4)
    val rest = sectors.drop(4)
    Column(
        Modifier.fillMaxWidth().clip(MarksyShape.Chip).clickable(onClickLabel = if (open) "Hide allocation" else "Show allocation", onClick = onToggle),
        verticalArrangement = Arrangement.spacedBy(MarksySpace.Inner)
    ) {
        Row(Modifier.fillMaxWidth().height(8.dp).clip(MarksyShape.Badge), horizontalArrangement = Arrangement.spacedBy(MarksySpace.Hair)) {
            top.forEachIndexed { i, s -> Box(Modifier.weight(s.pct.toFloat().coerceAtLeast(0.5f)).fillMaxHeight().background(SliceColors[i])) }
            if (rest.isNotEmpty()) Box(Modifier.weight(rest.sumOf { it.pct }.toFloat().coerceAtLeast(0.5f)).fillMaxHeight().background(MarksyTheme.BorderGlow))
        }
        Row(verticalAlignment = Alignment.Top) {
            FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(MarksySpace.ListGap), verticalArrangement = Arrangement.spacedBy(MarksySpace.Hair)) {
                top.forEachIndexed { i, s -> Legend(SliceColors[i], "${s.name} ${s.pct.roundToInt()}%") }
                if (rest.isNotEmpty()) Legend(MarksyTheme.TextMuted, "${rest.size} more ${rest.sumOf { it.pct }.roundToInt()}%")
            }
            Icon(Icons.Default.ExpandMore, contentDescription = null, tint = MarksyTheme.TextMuted, modifier = Modifier.size(MarksySize.Icon).rotate(if (open) 180f else 0f))
        }
    }
    if (open) Column(verticalArrangement = Arrangement.spacedBy(MarksySpace.Inner)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(MarksySpace.Inner), verticalArrangement = Arrangement.spacedBy(MarksySpace.Inner)) {
            AllocationBy.entries.forEach { b -> Pill(b.label, selected = b == by) { onBy(b) } }
        }
        val list = PortfolioMath.allocation(rows, by)
        val first = list.firstOrNull()?.pct ?: 0.0
        val max = if (by == AllocationBy.HOLDING) maxOf(25.0, ceil(first / 5) * 5) else maxOf(30.0, ceil(first / 10) * 10)
        list.forEach { s -> AllocationRow(s, max, line = PortfolioFlags.CONCENTRATION_PCT.takeIf { by == AllocationBy.HOLDING }) }
        if (by == AllocationBy.HOLDING) Text(
            "Amber line at ${PortfolioFlags.CONCENTRATION_PCT.roundToInt()}%: a holding past it shows under Needs a look.",
            color = MarksyTheme.TextMuted, style = MarksyType.Meta
        )
    }
}

@Composable
private fun AllocationRow(slice: AllocationSlice, max: Double, line: Double?) {
    val over = line != null && PortfolioFlags.concentrated(slice.pct)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(slice.name, color = MarksyTheme.TextSecondary, style = MarksyType.Meta, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(92.dp))
        BoxWithConstraints(Modifier.weight(1f).height(8.dp)) {
            Box(Modifier.fillMaxWidth().height(6.dp).align(Alignment.Center).clip(MarksyShape.Badge).background(MarksyTheme.SurfaceRaised))
            Box(
                Modifier.fillMaxWidth((slice.pct / max).coerceIn(0.0, 1.0).toFloat()).height(6.dp).align(Alignment.CenterStart)
                    .clip(MarksyShape.Badge).background(if (over) MarksyTheme.YellowImportant else MarksyTheme.BlueFinance)
            )
            line?.let { Box(Modifier.offset(x = maxWidth * (it / max).toFloat()).width(2.dp).fillMaxHeight().background(MarksyTheme.YellowImportant)) }
        }
        Text(
            "${MarksyFormat.number(slice.pct, 1)}%", color = if (over) MarksyTheme.YellowImportant else MarksyTheme.TextPrimary, style = MarksyType.Meta,
            textAlign = TextAlign.End, modifier = Modifier.width(44.dp)
        )
    }
}

@Composable
private fun Legend(color: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(MarksySize.Dot).clip(MarksyShape.Badge).background(color))
        Spacer(Modifier.width(MarksySpace.Tight))
        Text(text, color = MarksyTheme.TextSecondary, style = MarksyType.Meta)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NeedsCard(item: NeedsLook, sectorPct: Map<String, Double>, onWhy: () -> Unit, onOpen: () -> Unit, onAlert: () -> Unit, onHide: () -> Unit) {
    val r = item.row
    val h = r.holding
    val (fg, bg) = severityColors(item.primary.severity)
    Column(
        Modifier.fillMaxWidth().marksyCard(fg.copy(alpha = .55f))
            .clickable(onClickLabel = "Why ${h.symbol} needs a look", onClick = onWhy).padding(MarksySpace.CardPadding),
        verticalArrangement = Arrangement.spacedBy(MarksySpace.Tight)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MarksyBadge(item.primary.reason, fg, bg, icon = flagIcon(item.primary))
            Spacer(Modifier.weight(1f))
            r.dayPnl?.let { Text("${MarksyFormat.signedRupees(it, 0)} today", color = pnlColor(it), style = MarksyType.Small, fontWeight = FontWeight.Bold) }
        }
        Text(
            listOfNotNull(
                if (h.type == HoldingType.ETF) "ETF" else "Stock", h.sector?.takeIf { h.type == HoldingType.STOCK },
                (item.flags.size - 1).takeIf { it > 0 }?.let { "$it more reason${if (it > 1) "s" else ""}" }
            ).joinToString(" · "),
            color = MarksyTheme.TextMuted, style = MarksyType.Meta
        )
        Text("${displayName(h.name)} · ${MarksyFormat.rupees(r.price)}", color = MarksyTheme.TextPrimary, style = MarksyType.Subhead, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(needBody(item, sectorPct), color = MarksyTheme.TextSecondary, style = MarksyType.Small, maxLines = 3, overflow = TextOverflow.Ellipsis)
        FlowRow(Modifier.padding(top = MarksySpace.Tight), horizontalArrangement = Arrangement.spacedBy(MarksySpace.Inner), verticalArrangement = Arrangement.spacedBy(MarksySpace.Inner)) {
            Pill("Open stock", onClick = onOpen)
            Pill("Alert", onClick = onAlert)
            Pill("Hide today", onClick = onHide)
        }
    }
}

private fun needBody(item: NeedsLook, sectorPct: Map<String, Double>): String {
    val r = item.row
    val h = r.holding
    val parts = item.flags.mapNotNull { f ->
        when (f.kind) {
            FlagKind.BELOW_AVERAGE -> "${MarksyFormat.signedRupees(r.totalPnl, 0)} on ${plural(h.quantity.toInt(), "share")}; you paid ${MarksyFormat.rupees(h.averagePrice)} on average."
            FlagKind.CONCENTRATION -> "${MarksyFormat.rupees(r.value, 0)} in one holding" +
                (h.sector?.takeIf { h.type == HoldingType.STOCK }?.let { s -> sectorPct[s]?.let { "; $s make up ${it.roundToInt()}% of the portfolio" } } ?: "") + "."
            FlagKind.NEAR_ALERT, FlagKind.DAY_MOVE -> if (f == item.primary) null else "${f.reason}."
        }
    }.toMutableList()
    val total = r.totalPct
    if (item.flags.none { it.kind == FlagKind.BELOW_AVERAGE } && total != null && total < -5) parts += "${MarksyFormat.number(-total, 1)}% below your ${MarksyFormat.rupees(h.averagePrice)} average."
    if (parts.isEmpty()) parts += "${plural(h.quantity.toInt(), "share")}, ${MarksyFormat.rupees(r.value, 0)} at today's price."
    return parts.joinToString(" ")
}

private fun flagIcon(f: HoldingFlag): ImageVector = when (f.kind) {
    FlagKind.DAY_MOVE -> if (f.severity == FlagSeverity.POSITIVE) Icons.AutoMirrored.Filled.TrendingUp else Icons.AutoMirrored.Filled.TrendingDown
    FlagKind.BELOW_AVERAGE -> Icons.AutoMirrored.Filled.TrendingDown
    FlagKind.NEAR_ALERT -> Icons.Default.NotificationsActive
    FlagKind.CONCENTRATION -> Icons.Default.PieChart
}

private fun severityColors(s: FlagSeverity): Pair<Color, Color> = when (s) {
    FlagSeverity.CRITICAL -> MarksyTheme.Negative to MarksyTheme.BadgeUrgentBg
    FlagSeverity.WARNING -> MarksyTheme.Warning to MarksyTheme.BadgeImportantBg
    FlagSeverity.POSITIVE -> MarksyTheme.Positive to MarksyTheme.BadgeTradingBg
}

private fun ruleText(kind: FlagKind): String = when (kind) {
    FlagKind.DAY_MOVE -> "it moves ${PortfolioFlags.DAY_MOVE_PCT.roundToInt()}% or more either way in a day"
    FlagKind.BELOW_AVERAGE -> "it is ${PortfolioFlags.BELOW_AVERAGE_PCT.roundToInt()}% or more below your average price"
    FlagKind.NEAR_ALERT -> "it is within ${PortfolioFlags.ALERT_DISTANCE_PCT.roundToInt()}% of one of your price alerts"
    FlagKind.CONCENTRATION -> "it is ${PortfolioFlags.CONCENTRATION_PCT.roundToInt()}% or more of your portfolio"
}

@Composable
private fun WhyDialog(item: NeedsLook, onOpen: () -> Unit, onDismiss: () -> Unit) {
    MarksyDialog(
        onDismissRequest = onDismiss,
        title = { Text("Why ${item.row.holding.symbol} needs a look") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(MarksySpace.Gap)) {
                item.flags.forEach { f ->
                    val (fg, bg) = severityColors(f.severity)
                    MarksyBadge(f.reason, fg, bg, icon = flagIcon(f))
                    Text("Marksy flags a holding when ${ruleText(f.kind)}.", color = MarksyTheme.TextSecondary, style = MarksyType.Small)
                }
            }
        },
        dismissButton = { MarksyButton("Close", onDismiss, style = MarksyButtonStyle.Text, color = MarksyTheme.TextSecondary) },
        confirmButton = { MarksyButton("Open stock", onOpen, style = MarksyButtonStyle.Text) }
    )
}

@Composable
private fun HoldingsStack(
    rows: List<HoldingRow>,
    metric: PortfolioMetric,
    period: PortfolioPeriod,
    flagged: Map<String, FlagSeverity?>,
    open: String?,
    onToggle: (String) -> Unit,
    onOpen: (String) -> Unit,
    onAlert: (HoldingRow) -> Unit
) {
    Column(Modifier.marksyCard()) {
        rows.forEachIndexed { i, r ->
            if (i > 0) MarksyDivider()
            HoldingLine(r, metric, period, flagged[r.holding.symbol], open == r.holding.symbol, { onToggle(r.holding.symbol) }, { onOpen(r.holding.symbol) }, { onAlert(r) })
        }
    }
}

@Composable
private fun HoldingLine(
    r: HoldingRow,
    metric: PortfolioMetric,
    period: PortfolioPeriod,
    flag: FlagSeverity?,
    expanded: Boolean,
    onToggle: () -> Unit,
    onOpen: () -> Unit,
    onAlert: () -> Unit
) {
    val h = r.holding
    val pnl = r.pnl(metric)
    val pct = r.pct(metric)
    Column(
        Modifier.fillMaxWidth().clickable(onClickLabel = if (expanded) "Collapse ${h.symbol}" else "Expand ${h.symbol}", onClick = onToggle)
            .padding(horizontal = MarksySpace.CardPadding, vertical = MarksySpace.ListGap)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                Text(h.symbol, color = MarksyTheme.TextPrimary, style = MarksyType.Subhead, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                flag?.let {
                    Spacer(Modifier.width(MarksySpace.Inner))
                    Box(Modifier.size(MarksySize.Dot).clip(CircleShape).background(severityColors(it).first))
                }
            }
            Text(pnl?.let { MarksyFormat.signedRupees(it, 0) } ?: "—", color = pnlColor(pnl), style = MarksyType.Subhead, fontWeight = FontWeight.Bold)
            Text(
                pct?.let { MarksyFormat.percent(it) }.orEmpty(), color = pnlColor(pct), style = MarksyType.Meta, fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.End, modifier = Modifier.widthIn(min = 58.dp)
            )
        }
        Row {
            Text("${count(h.quantity)} sh · avg ${MarksyFormat.rupees(h.averagePrice)}", color = MarksyTheme.TextMuted, style = MarksyType.Meta, modifier = Modifier.weight(1f))
            Text("LTP ${MarksyFormat.rupees(r.price)}", color = MarksyTheme.TextMuted, style = MarksyType.Meta)
        }
        if (!expanded) {
            // Full width is 25% of the portfolio.
            Box(
                Modifier.padding(top = MarksySpace.Inner).fillMaxWidth((r.weightPct / 25).coerceIn(0.0, 1.0).toFloat()).height(2.dp)
                    .clip(MarksyShape.Badge).background(MarksyTheme.BlueFinance.copy(alpha = .6f))
            )
        } else {
            HoldingDetail(r, period, onOpen, onAlert, onToggle)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HoldingDetail(r: HoldingRow, period: PortfolioPeriod, onOpen: () -> Unit, onAlert: () -> Unit, onLess: () -> Unit) {
    val h = r.holding
    val plain = MarksyTheme.TextPrimary
    val cells = listOf(
        Triple("Quantity", count(h.quantity), plain),
        Triple("Avg price", MarksyFormat.rupees(h.averagePrice), plain),
        Triple("LTP", MarksyFormat.rupees(r.price), plain),
        Triple("Invested", MarksyFormat.rupees(r.invested, 0), plain),
        Triple("Current", MarksyFormat.rupees(r.value, 0), plain),
        Triple("Weight", "${MarksyFormat.number(r.weightPct, 1)}%", plain),
        Triple("${period.title} P&L", r.periodPnl?.let { MarksyFormat.signedRupees(it, 0) } ?: "—", pnlColor(r.periodPnl)),
        Triple("Total P&L", MarksyFormat.signedRupees(r.totalPnl, 0), pnlColor(r.totalPnl)),
        Triple("Total return", r.totalPct?.let { MarksyFormat.percent(it) } ?: "—", pnlColor(r.totalPct))
    )
    Column(Modifier.padding(top = MarksySpace.ListGap), verticalArrangement = Arrangement.spacedBy(MarksySpace.Gap)) {
        cells.chunked(3).forEach { line ->
            Row {
                line.forEach { (label, value, color) ->
                    Column(Modifier.weight(1f)) {
                        Text(label, color = MarksyTheme.TextMuted, style = MarksyType.Caption)
                        Text(value, color = color, style = MarksyType.Body, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    }
                }
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(MarksySpace.Inner), verticalArrangement = Arrangement.spacedBy(MarksySpace.Inner)) {
            Pill("Open ${h.symbol}", onClick = onOpen)
            Pill("Price alert", onClick = onAlert)
            Pill("Less", onClick = onLess)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ConnectCard(providers: List<PortfolioProvider>, onSignIn: () -> Unit) {
    MarksyCard {
        Box(Modifier.size(40.dp).clip(CircleShape).background(MarksyTheme.BadgeTradingBg), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.PieChart, contentDescription = null, tint = MarksyTheme.PrimaryEmerald, modifier = Modifier.size(MarksySize.IconLarge))
        }
        Text("See what you own, next to live prices", color = MarksyTheme.TextPrimary, style = MarksyType.Lead, fontWeight = FontWeight.Bold)
        Text(
            "Holdings need an Upstox sign-in once a day; Upstox's market-data token can't read them. Sign in to see today's and " +
                "total P&L, holdings that need a look, and your sector mix.",
            color = MarksyTheme.TextSecondary, style = MarksyType.Small
        )
        MarksyButton("Sign in to Upstox", onSignIn, Modifier.fillMaxWidth())
        FlowRow(horizontalArrangement = Arrangement.spacedBy(MarksySpace.Inner), verticalArrangement = Arrangement.spacedBy(MarksySpace.Inner)) {
            providers.filterNot { it.available }.forEach { p -> Pill("${p.name} · ${p.note.lowercase(Locale.ROOT)}", enabled = false) {} }
            Pill("Mutual funds · coming soon", enabled = false) {}
        }
        Text("Read-only. Holdings go from Upstox straight to this phone; Marksy's servers never see them.", color = MarksyTheme.TextMuted, style = MarksyType.Meta)
    }
}

@Composable
private fun LoadFailedCard(message: String, onRetry: () -> Unit) {
    MarksyCard {
        MarksyBadge("Couldn't load your holdings", MarksyTheme.Warning, MarksyTheme.BadgeImportantBg, icon = Icons.Default.Schedule)
        Text(message, color = MarksyTheme.TextSecondary, style = MarksyType.Small)
        Text("You're still signed in to Upstox; nothing is wrong with your keys.", color = MarksyTheme.TextMuted, style = MarksyType.Meta)
        Pill("Try again", onClick = onRetry)
    }
}

@Composable
private fun SignedOutCard(fetchedAt: Long, livePrices: Boolean, reason: String?, onSignIn: () -> Unit) {
    MarksyCard(border = MarksyTheme.Warning.copy(alpha = .55f)) {
        MarksyBadge("Signed out of Upstox", MarksyTheme.Warning, MarksyTheme.BadgeImportantBg, icon = Icons.Default.Schedule)
        Text(reason ?: "Upstox ends every sign-in at 3:30 am", color = MarksyTheme.TextPrimary, style = MarksyType.Subhead, fontWeight = FontWeight.Bold)
        Text(
            "Holdings as of ${compactTime(fetchedAt) ?: "your last sign-in"}; prices are ${if (livePrices) "live" else "from then too"}.",
            color = MarksyTheme.TextSecondary, style = MarksyType.Small
        )
        MarksyButton("Sign in to Upstox", onSignIn)
    }
}

@Composable
private fun Caveat(text: String) {
    Text(text, color = MarksyTheme.Warning, style = MarksyType.Meta, modifier = Modifier.padding(horizontal = MarksySpace.Tight))
}

@Composable
private fun MoreRow(label: String, who: String, onClick: () -> Unit) {
    val shape = MarksyShape.Pill
    Row(
        Modifier.fillMaxWidth().clip(shape).border(MarksySpace.Border, MarksyTheme.BorderGlow, shape).clickable(onClick = onClick).padding(horizontal = MarksySpace.CardPadding, vertical = MarksySpace.ListGap),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Default.ExpandMore, contentDescription = null, tint = MarksyTheme.TextMuted, modifier = Modifier.size(MarksySize.Icon).rotate(if (who.isEmpty()) 180f else 0f))
        Spacer(Modifier.width(MarksySpace.Inner))
        Text(label, color = MarksyTheme.TextPrimary, style = MarksyType.Small, fontWeight = FontWeight.Medium)
        if (who.isNotEmpty()) {
            Spacer(Modifier.width(MarksySpace.Gap))
            Text(who, color = MarksyTheme.TextMuted, style = MarksyType.Meta, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun MutualFundsPlaceholder() {
    MarksyRowCard {
        Text("Mutual funds · coming soon", color = MarksyTheme.TextPrimary, style = MarksyType.Body, fontWeight = FontWeight.SemiBold)
        Text("Marksy has no NAV source yet, so funds you hold don't show here.", color = MarksyTheme.TextMuted, style = MarksyType.Meta)
    }
}

@Composable
private fun HiddenFooter(count: Int, onShow: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        Text("$count hidden till the next open · ", color = MarksyTheme.TextMuted, style = MarksyType.Meta)
        Text("Show", color = MarksyTheme.TextSecondary, style = MarksyType.Meta, textDecoration = TextDecoration.Underline, modifier = Modifier.clickable(onClick = onShow).padding(MarksySpace.Tight))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PortfolioSortDialog(sort: HoldingSort, show: HoldingType?, onSort: (HoldingSort) -> Unit, onShow: (HoldingType?) -> Unit, onDismiss: () -> Unit) {
    MarksyDialog(
        onDismissRequest = onDismiss,
        title = { Text("Sort and show") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(MarksySpace.Gap)) {
                Text("Sort", color = MarksyTheme.TextMuted, style = MarksyType.Small)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(MarksySpace.Inner), verticalArrangement = Arrangement.spacedBy(MarksySpace.Inner)) {
                    HoldingSort.entries.forEach { s -> Pill(s.label, selected = s == sort) { onSort(s) } }
                }
                Text("Show", color = MarksyTheme.TextMuted, style = MarksyType.Small)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(MarksySpace.Inner), verticalArrangement = Arrangement.spacedBy(MarksySpace.Inner)) {
                    (listOf<HoldingType?>(null) + HoldingType.entries).forEach { t -> Pill(t?.label ?: "All", selected = t == show) { onShow(t) } }
                }
            }
        },
        confirmButton = { MarksyButton("Done", onDismiss, style = MarksyButtonStyle.Text) }
    )
}

private val LEGAL_SUFFIX = Regex("\\s+(LTD|LIMITED)\\.?$", RegexOption.IGNORE_CASE)
private fun displayName(name: String): String = name.replace(LEGAL_SUFFIX, "")
private fun plural(n: Int, word: String): String = "$n $word" + if (n == 1) "" else "s"
private fun pnlColor(v: Double?): Color = when {
    v == null -> MarksyTheme.TextMuted
    v < 0 -> MarksyTheme.Negative
    else -> MarksyTheme.Positive
}
