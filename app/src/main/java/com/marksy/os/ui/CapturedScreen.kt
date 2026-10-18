package com.marksy.os.ui

import java.time.ZoneId
import java.time.Instant
import com.marksy.os.MarksyFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.marksy.os.EmptyState
import com.marksy.os.data.local.DeliveryState
import com.marksy.os.data.local.NotificationEventEntity
import com.marksy.os.market.LedgerCalls
import com.marksy.os.market.MarketDataState
import com.marksy.os.market.StockMentions
import com.marksy.os.market.TipDetailDto
import com.marksy.os.notification.CaptureMedium
import java.math.BigDecimal

private const val SHOWN_PER_STACK = 2

/** Inbox › Captured tips: the capture log under the shared Inbox filter, with its Show options and Add screenshot on the stack. */
@Composable
fun CapturedInboxView(
    events: List<NotificationEventEntity>, padding: PaddingValues, onFilterSelected: (String) -> Unit, onOpenStock: (String) -> Unit,
    onRetry: (Long) -> Unit, onSendNow: () -> Unit, onAllowBackground: () -> Unit, showHealth: Boolean, onHealthDismiss: () -> Unit, inbox: CaptureInbox,
    fetchTip: (suspend (String) -> MarketDataState<TipDetailDto>)? = null
) {
    var show by rememberSaveable { mutableStateOf(ShowOnly.ALL) }
    var stackBy by rememberSaveable { mutableStateOf(StackBy.SOURCE) }
    var query by rememberSaveable { mutableStateOf("") }
    Box(Modifier.fillMaxSize().background(MarksyTheme.Background).padding(padding).consumeWindowInsets(padding)) {
        CapturedList(events, stackBy, show, query, onOpenStock, onRetry, onSendNow, onAllowBackground, showHealth, onHealthDismiss, inbox, fetchTip)
        OneHandControls(
            filters = inboxFilters,
            selectedFilter = InboxCapturedFilter,
            onFilterSelected = onFilterSelected,
            searchQuery = query,
            onSearchChange = { query = it },
            searchPlaceholder = "Search captured…",
            filterIsView = false,
            actions = listOf(FloatingAction(Icons.Default.AddPhotoAlternate, "Add screenshot", onClick = inbox.onAddScreenshot)),
            extrasActive = show != ShowOnly.ALL,
            filterExtras = { CapturedFilterSections(show, { show = it }, stackBy, { stackBy = it }) }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CapturedFilterSections(show: ShowOnly, onShow: (ShowOnly) -> Unit, stackBy: StackBy, onStackBy: (StackBy) -> Unit) {
    Column(Modifier.widthIn(max = MarksySize.MenuWidth).padding(start = MarksySpace.CardPadding, end = MarksySpace.CardPadding, top = MarksySpace.Gap, bottom = MarksySpace.Inner), verticalArrangement = Arrangement.spacedBy(MarksySpace.Inner)) {
        Text("SHOW", color = MarksyTheme.TextMuted, style = MarksyType.Label)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(MarksySpace.Inner), verticalArrangement = Arrangement.spacedBy(MarksySpace.Inner)) {
            listOf(ShowOnly.ALL to "All", ShowOnly.TIPS to "Tips", ShowOnly.NEEDS_YOU to "Needs you").forEach { (v, l) -> Pill(l, selected = show == v) { onShow(v) } }
        }
        Text("STACK BY", color = MarksyTheme.TextMuted, style = MarksyType.Label, modifier = Modifier.padding(top = MarksySpace.Tight))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(MarksySpace.Inner), verticalArrangement = Arrangement.spacedBy(MarksySpace.Inner)) {
            listOf(StackBy.SOURCE to "Source", StackBy.SYMBOL to "Symbol").forEach { (v, l) -> Pill(l, selected = stackBy == v) { onStackBy(v) } }
        }
    }
}

/** Capture log lanes; a ticker named in a call opens its stock page. */
@Composable
private fun CapturedList(
    events: List<NotificationEventEntity>, stackBy: StackBy, show: ShowOnly, query: String, onOpenStock: (String) -> Unit,
    onRetry: (Long) -> Unit, onSendNow: () -> Unit, onAllowBackground: () -> Unit, showHealth: Boolean, onHealthDismiss: () -> Unit, inbox: CaptureInbox,
    fetchTip: (suspend (String) -> MarketDataState<TipDetailDto>)?
) {
    val isSymbol = rememberSymbolCheck()
    val now by produceState(System.currentTimeMillis()) { while (true) { kotlinx.coroutines.delay(60_000); value = System.currentTimeMillis() } }
    val lanes = remember(events, stackBy, show, query, isSymbol, now) {
        CapturedModel.lanes(events, now, stackBy, show, query) { StockMentions.find(it, isSymbol, limit = 1).firstOrNull() }
    }
    CapturedScreen(lanes, now, onOpenStock, onRetry, onSendNow, onAllowBackground, showHealth, onHealthDismiss, inbox, filtered = show != ShowOnly.ALL || query.isNotBlank(), fetchTip = fetchTip)
}

/** Capture log: what the phone caught and whether Marksy got it, in lanes (Needs you, Today, Earlier, Rejected orders). */
@Composable
fun CapturedScreen(
    lanes: CapturedLanes,
    now: Long,
    onOpenStock: (String) -> Unit,
    onRetry: (Long) -> Unit,
    onSendNow: () -> Unit,
    onAllowBackground: () -> Unit,
    showHealth: Boolean,
    onHealthDismiss: () -> Unit,
    inbox: CaptureInbox = CaptureInbox(),
    filtered: Boolean = false,
    // Read-only GET /tips/{id}, so a sent tip's dialog can show its ledger status now.
    fetchTip: (suspend (String) -> MarketDataState<TipDetailDto>)? = null
) {
    var toReviewOpen by rememberSaveable { mutableStateOf(false) }
    var expanded by rememberSaveable { mutableStateOf(setOf<String>()) }
    var keptOpen by rememberSaveable { mutableStateOf(false) }
    var earlierOpen by rememberSaveable { mutableStateOf(false) }
    var rejectedOpen by rememberSaveable { mutableStateOf(false) }
    var selected by remember { mutableStateOf<CapturedRow?>(null) }
    val toggle: (String) -> Unit = { key -> expanded = if (key in expanded) expanded - key else expanded + key }
    val symbols = remember(lanes) {
        (lanes.needsYou.map { it.row } + (lanes.today + lanes.earlier).flatMap { it.rows } + lanes.rejected).mapNotNull { it.levels?.takeIf(TipLevels::hasLevels)?.symbol }.distinct()
    }
    val quotes = rememberUpstoxQuotes(symbols)

    MarksyList {
        captureToReview(inbox, now, toReviewOpen) { toReviewOpen = !toReviewOpen }
        captureKeptOptions(inbox, now, keptOpen) { keptOpen = !keptOpen }
        if (lanes.isEmpty && filtered) item { EmptyState("Nothing matches", "Try another Show option, or clear the search.") }
        else if (lanes.isEmpty && inbox.isEmpty && inbox.keptOptions.isEmpty()) item { EmptyState("Nothing captured yet", "Tips from groups, SMS and research apps, and broker orders, appear here once your phone catches them.") }
        if (lanes.needsYou.isNotEmpty()) {
            item { SectionLabel("Needs you", lanes.needsYou.size, MarksyTheme.Negative) }
            items(lanes.needsYou, key = { "n${it.row.event.id}" }) { NeedCard(it, now, { selected = it.row }, onRetry, onSendNow, onAllowBackground) }
        }
        if (lanes.today.isNotEmpty()) {
            item { SectionLabel("Today", lanes.today.sumOf { it.rows.size }, MarksyTheme.Positive) }
            items(lanes.today, key = { "t${it.key}" }) { StackCard("t${it.key}", it, now, quotes, "t${it.key}" in expanded, toggle) { selected = it } }
        }
        if (lanes.earlier.isNotEmpty()) {
            val count = lanes.earlier.sumOf { it.rows.size }
            item {
                FoldRow("Earlier", "$count ${if (count == 1) "item" else "items"} from ${lanes.earlier.size} ${if (lanes.earlier.size == 1) "source" else "sources"} · Older than this session, or retired", earlierOpen) { earlierOpen = !earlierOpen }
            }
            if (earlierOpen) items(lanes.earlier, key = { "e${it.key}" }) { StackCard("e${it.key}", it, now, quotes, "e${it.key}" in expanded, toggle) { selected = it } }
        }
        if (lanes.rejected.isNotEmpty()) {
            item { FoldRow("Rejected orders", "${lanes.rejected.size} · Your broker turned these down", rejectedOpen) { rejectedOpen = !rejectedOpen } }
            if (rejectedOpen) items(lanes.rejected, key = { "r${it.event.id}" }) { r ->
                MarksyRowCard(onClick = { selected = r }) { TipRow(r, now, firstRow = true, quotes) }
            }
        }
    }
    selected?.let { RowDialog(it, onOpenStock, fetchTip) { selected = null } }
    if (showHealth) HealthDialog(lanes.health, onHealthDismiss)
}

@Composable
internal fun FoldRow(title: String, sub: String, open: Boolean, onClick: () -> Unit) {
    MarksyRowCard(onClick = onClick) {
        MarksyCardHeader(title, trailing = { ExpandChevron(open) })
        Text(sub, color = MarksyTheme.TextMuted, style = MarksyType.Meta)
    }
}

@Composable
private fun ExpandChevron(open: Boolean) =
    Icon(Icons.Default.ExpandMore, contentDescription = null, tint = MarksyTheme.TextMuted, modifier = Modifier.size(MarksySize.Icon).rotate(if (open) 180f else 0f))

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NeedCard(need: NeedItem, now: Long, onOpen: () -> Unit, onRetry: (Long) -> Unit, onSendNow: () -> Unit, onAllowBackground: () -> Unit) {
    val r = need.row
    val failed = need.kind == NeedKind.RETRY
    MarksyCard(border = MarksyTheme.flagBorder(if (failed) MarksyTheme.RedUrgent else MarksyTheme.YellowImportant), onClick = onOpen) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (failed) MarksyBadge("Not sent", MarksyTheme.RedUrgent, MarksyTheme.BadgeUrgentBg) else MarksyBadge("Waiting ${need.waitingMinutes} min", MarksyTheme.YellowImportant, MarksyTheme.BadgeImportantBg)
            Spacer(Modifier.width(MarksySpace.Gap))
            Text("${sourceOf(r)} · ${compactTime(r.event.postedAt, now) ?: ""}", color = MarksyTheme.TextMuted, style = MarksyType.Meta, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(r.headline, color = MarksyTheme.TextPrimary, style = MarksyType.Subhead, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            if (failed) noteLabel(r.event.deliveryNote) ?: "Marksy did not receive this tip." else "Android is holding the send back; it goes out when the phone allows.",
            color = MarksyTheme.TextSecondary, style = MarksyType.Small
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(MarksySpace.Inner), verticalArrangement = Arrangement.spacedBy(MarksySpace.Inner)) {
            if (failed) Pill("Retry", selected = true) { onRetry(r.event.id) }
            else {
                Pill("Send now", selected = true) { onSendNow() }
                Pill("Allow background") { onAllowBackground() }
            }
        }
    }
}

@Composable
private fun StackCard(id: String, stack: CapturedStack, now: Long, quotes: Map<String, com.marksy.os.upstox.UpstoxLtp>, expanded: Boolean, onToggle: (String) -> Unit, onOpen: (CapturedRow) -> Unit) {
    val shown = if (expanded) stack.rows else stack.rows.take(SHOWN_PER_STACK)
    val hidden = stack.rows.size - SHOWN_PER_STACK
    MarksyGroupCard {
        MarksyCardHeader(
            stack.label, Modifier.padding(vertical = MarksySpace.Gap), titleColor = MarksyTheme.PrimaryEmerald,
            trailing = { Text("${stack.rows.size}", color = MarksyTheme.TextMuted, style = MarksyType.Meta) }
        )
        shown.forEachIndexed { i, r ->
            MarksyDivider()
            Column(Modifier.marksyRow(onClick = { onOpen(r) })) { TipRow(r, now, firstRow = i == 0, quotes) }
        }
        if (hidden > 0) {
            val names = stack.rows.drop(SHOWN_PER_STACK).mapNotNull { it.levels?.symbol }.distinct().take(3).joinToString(", ")
            MarksyDivider()
            Row(Modifier.marksyRow(onClick = { onToggle(id) }), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (expanded) "Show less" else "$hidden more" + if (names.isNotEmpty()) " · $names" else "",
                    color = MarksyTheme.TextSecondary, style = MarksyType.Small, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f)
                )
                ExpandChevron(expanded)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TipRow(r: CapturedRow, now: Long, firstRow: Boolean, quotes: Map<String, com.marksy.os.upstox.UpstoxLtp> = emptyMap()) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(r.headline, color = MarksyTheme.TextPrimary, style = MarksyType.Subhead, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(MarksySpace.Inner))
        Text(compactTime(r.event.postedAt, now) ?: "", color = MarksyTheme.TextMuted, style = MarksyType.Meta)
    }
    FlowRow(Modifier.padding(top = MarksySpace.Tight), horizontalArrangement = Arrangement.spacedBy(MarksySpace.Inner), verticalArrangement = Arrangement.spacedBy(MarksySpace.Tight), itemVerticalAlignment = Alignment.CenterVertically) {
        val (text, fg, bg) = deliveryChip(r)
        MarksyBadge(text, fg, bg)
        r.levels?.status?.let { s ->
            val tone = r.levels.tone
            MarksyBadge(s, if (tone == StatusTone.NEGATIVE) MarksyTheme.RedUrgent else if (tone == StatusTone.POSITIVE) MarksyTheme.PrimaryEmerald else MarksyTheme.TextSecondary, if (tone == StatusTone.NEGATIVE) MarksyTheme.BadgeUrgentBg else if (tone == StatusTone.POSITIVE) MarksyTheme.BadgeTradingBg else MarksyTheme.SurfaceRaised)
        }
        if (r.folded > 0) Text("+${r.folded} forwarded", color = MarksyTheme.TextMuted, style = MarksyType.Meta)
        CapturedModel.retiredReason(r.event)?.let { Text(it, color = MarksyTheme.TextMuted, style = MarksyType.Meta, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }
    val lv = r.levels
    if (lv != null && lv.hasLevels) {
        // The shared price track; its block shows the entry, so the levels line is not repeated.
        val open = r.event.lifecycleState != "RESOLVED"
        val live = lv.symbol?.let { quotes[it]?.lastPrice }
        val track = r.ledger?.let { t -> TipTrack.of(t, live).let { if (open) it else it.copy(live = false) } } ?: TipTrack.of(lv, live, open)
        PriceTrackView(track, Modifier.padding(top = MarksySpace.Inner))
    } else if (firstRow || r.keptLocal) {
        Text(r.event.body, color = MarksyTheme.TextSecondary, style = MarksyType.Small, maxLines = if (firstRow) 2 else 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = MarksySpace.Tight))
    }
}

private fun deliveryChip(r: CapturedRow): Triple<String, Color, Color> = when (r.event.deliveryState) {
    DeliveryState.DELIVERED.name -> Triple("Sent", MarksyTheme.PrimaryEmerald, MarksyTheme.BadgeTradingBg)
    DeliveryState.PENDING.name -> Triple("Waiting", MarksyTheme.YellowImportant, MarksyTheme.BadgeImportantBg)
    DeliveryState.IN_FLIGHT.name -> Triple("Sending", MarksyTheme.YellowImportant, MarksyTheme.BadgeImportantBg)
    DeliveryState.FAILED.name -> Triple("Not sent", MarksyTheme.RedUrgent, MarksyTheme.BadgeUrgentBg)
    else -> Triple("On this phone", MarksyTheme.TextSecondary, MarksyTheme.SurfaceRaised)
}

private fun sourceOf(r: CapturedRow) = CapturedModel.sourceLabel(r.event)

private fun price(v: Double): String = BigDecimal(v).setScale(2, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()

internal fun optionPricesLine(f: com.marksy.os.capture.TipFields): String = listOfNotNull(f.entry?.let { "Entry ${price(it)}" }, f.target?.let { "Target ${price(it)}" }, f.stopLoss?.let { "SL ${price(it)}" }).joinToString(" · ")

internal fun levelsLine(lv: TipLevels): String = listOfNotNull(
    lv.entry?.let { e -> "Entry ${price(e)}" + (lv.entryHigh?.takeIf { it != e }?.let { "–${price(it)}" } ?: "") },
    lv.target?.let { "Target ${price(it)}" }, lv.stopLoss?.let { "SL ${price(it)}" }
).joinToString(" · ")

/** What CaptureGate's CapturedMessage carries for this row's medium. */
internal fun sentPayload(e: NotificationEventEntity): String {
    val parts = when (CaptureMedium.of(e.sourcePackage)) {
        CaptureMedium.APP_NOTIFICATION -> "the notification's title and text, the app's name and package"
        CaptureMedium.SMS -> "the message text, the sender id, the SMS app's package"
        CaptureMedium.WHATSAPP, CaptureMedium.TELEGRAM -> "the message text without sender names, the group name, the app's package"
    }
    return "What left the phone: $parts, when it was posted and a one-way key for it. Emails, phone numbers, PAN, long numbers and your user id are masked first."
}

internal fun noteLabel(note: String?): String? = when (note) {
    null -> null
    "rejected" -> "Marksy refused this message."
    "invalid" -> "The message could not be prepared for Marksy."
    "own-order", "own-account" -> "Your own orders and holdings never leave this phone."
    "one-to-one-chat" -> "Only group chats are sent; this was a one-to-one chat."
    "outside-capture-set", "sms-not-sender-id" -> "This source is not on your capture list."
    "not-a-candidate" -> "Not a tip, so nothing was sent."
    "option-unsupported" -> "Option call: option tracking unsupported, not sent to Marksy."
    else -> "Kept on this phone ($note)."
}

private fun fullTime(ms: Long): String = MarksyFormat.dayTime(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))

@Composable
private fun RowDialog(r: CapturedRow, onOpenStock: (String) -> Unit, fetchTip: (suspend (String) -> MarketDataState<TipDetailDto>)?, onDismiss: () -> Unit) {
    val e = r.event
    val tipId = e.marksyTipId?.takeIf { fetchTip != null && e.deliveryState == DeliveryState.DELIVERED.name }
    val ledgerNow by produceState<CapturedModel.LedgerNow?>(tipId?.let { CapturedModel.ledgerNow(MarketDataState.Loading) }, tipId) {
        if (tipId != null && fetchTip != null) value = CapturedModel.ledgerNow(fetchTip(tipId))
    }
    MarksyDialog(
        onDismissRequest = onDismiss,
        title = { Text(r.headline) },
        confirmButton = { MarksyButton("Close", onClick = onDismiss, style = MarksyButtonStyle.Text) },
        dismissButton = r.levels?.symbol?.let { s -> { MarksyButton("Open $s", onClick = { onDismiss(); onOpenStock(s) }, style = MarksyButtonStyle.Text) } },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(MarksySpace.Gap)) {
                val lv = r.levels
                if (lv != null && lv.hasLevels) Text(levelsLine(lv), color = MarksyTheme.TextPrimary, style = MarksyType.Subhead)
                val live = ledgerNow
                val liveTone = live?.tone
                if (liveTone != null) Text(live.text, color = toneColor(liveTone), style = MarksyTheme.DialogBody)
                else {
                    lv?.status?.let { Text("Marksy status: $it", style = MarksyTheme.DialogBody) }
                    live?.let { Text(it.text, color = MarksyTheme.TextMuted, style = MarksyType.Meta) }
                }
                val horizon = if (r.ledger != null) LedgerCalls.horizonText(r.ledger.horizonSessions) else r.insight.marksyHorizonDays?.let { "$it days" }
                horizon?.let { Text("Horizon $it", style = MarksyTheme.DialogBody) }
                Text(e.body, color = MarksyTheme.TextPrimary, style = MarksyType.Body, maxLines = 12, overflow = TextOverflow.Ellipsis)
                Text(
                    when (e.deliveryState) {
                        DeliveryState.DELIVERED.name -> "Sent to Marksy${e.insightReceivedAt?.let { " at ${fullTime(it)}" } ?: ""}."
                        DeliveryState.PENDING.name -> "Waiting to go to Marksy."
                        DeliveryState.IN_FLIGHT.name -> "Sending to Marksy now."
                        DeliveryState.FAILED.name -> "Not sent. ${noteLabel(e.deliveryNote) ?: ""}"
                        else -> noteLabel(e.deliveryNote) ?: "Stays on this phone."
                    },
                    style = MarksyTheme.DialogBody
                )
                if (e.deliveryState == DeliveryState.DELIVERED.name) Text(sentPayload(e), color = MarksyTheme.TextSecondary, style = MarksyType.Small)
                if (r.alsoIn.isNotEmpty()) Text("Also posted in ${r.alsoIn.joinToString(", ")}. Each group's copy is its own tip in Marksy's ledger.", style = MarksyTheme.DialogBody)
                CapturedModel.retiredReason(e)?.let { Text("$it.", style = MarksyTheme.DialogBody) }
                Text("Captured ${fullTime(e.postedAt)} from ${sourceOf(r)}.", color = MarksyTheme.TextMuted, style = MarksyType.Meta)
            }
        }
    )
}

@Composable
private fun HealthDialog(h: CaptureHealth, onDismiss: () -> Unit) {
    MarksyDialog(
        onDismissRequest = onDismiss,
        title = { Text("Capture health") },
        confirmButton = { MarksyButton("Close", onClick = onDismiss, style = MarksyButtonStyle.Text) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(MarksySpace.Inner)) {
                listOf("Caught this session" to h.today, "Sent to Marksy" to h.sent, "Waiting" to h.waiting, "Not sent" to h.failed, "Kept on this phone" to h.kept).forEach { (k, v) ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(k, style = MarksyTheme.DialogBody)
                        Text("$v", color = if (k == "Not sent" && v > 0) MarksyTheme.RedUrgent else MarksyTheme.TextPrimary, style = MarksyType.Body, fontWeight = FontWeight.SemiBold)
                    }
                }
                Text(h.lastDeliveredAt?.let { "Last reply from Marksy ${fullTime(it)}." } ?: "No reply from Marksy yet.", color = MarksyTheme.TextMuted, style = MarksyType.Meta)
            }
        }
    )
}
