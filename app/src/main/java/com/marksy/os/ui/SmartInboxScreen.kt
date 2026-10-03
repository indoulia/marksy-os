package com.marksy.os.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.marksy.os.EmptyState
import com.marksy.os.data.local.NotificationEventEntity
import com.marksy.os.intelligence.PersonalLearning
import com.marksy.os.intelligence.SmartInboxModel
import com.marksy.os.intelligence.SmartInboxModel.InboxThread

private const val NEEDS_VISIBLE = 3
private const val STACK_VISIBLE = 3
// Group rows start at the card's own inset: no rail, no wide indent.
private const val NEEDS_KEY = "needs"
private const val EARLIER_PREFIX = "e:"
private const val HOUR_MS = 60 * 60 * 1000L

@Composable
fun SmartInboxScreen(
    events: List<NotificationEventEntity>,
    padding: PaddingValues,
    onEventSelected: (NotificationEventEntity) -> Unit,
    selectedFilterName: String,
    onFilterSelected: (String) -> Unit,
    // Swipe actions act on one thread (every row incl. folded duplicates).
    onArchive: (List<NotificationEventEntity>) -> Unit = {},
    onHide: (List<NotificationEventEntity>) -> Unit = {},
    actions: InboxActions = InboxActions(),
    learningProfile: PersonalLearning.Profile = PersonalLearning.Profile.EMPTY,
    onOpenHistory: () -> Unit = {}
) {
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var actionThreadKey by rememberSaveable { mutableStateOf<String?>(null) }
    var expanded by rememberSaveable { mutableStateOf(listOf<String>()) }
    var earlierOpen by rememberSaveable { mutableStateOf(false) }
    // Opened this visit keeps its place until the user leaves the tab, so the list never jumps under the thumb.
    var seenThisVisit by remember { mutableStateOf(setOf<String>()) }

    val filter = SmartInboxModel.Filter.entries.firstOrNull { it.name == selectedFilterName }
        ?: SmartInboxModel.Filter.ALL

    // Re-evaluated every minute so snoozes and the 24 h window move without new data.
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            kotlinx.coroutines.delay(60_000)
            value = System.currentTimeMillis()
        }
    }
    val lanes = remember(events, filter, searchQuery, now, learningProfile, seenThisVisit) {
        SmartInboxModel.lanes(events, filter, searchQuery, now, learningProfile, seenThisVisit)
    }
    val byId = remember(events) { events.associateBy { it.id } }
    fun rowsOf(thread: InboxThread) = thread.allIds.mapNotNull { byId[it] }
    fun holdPlace(thread: InboxThread) { if (lanes.holdsOnOpen(thread.key)) seenThisVisit = seenThisVisit + thread.key }
    fun openThread(thread: InboxThread) {
        holdPlace(thread)
        actions.markSeen(thread.allIds)
        onEventSelected(thread.latest)
    }
    fun toggle(key: String) { expanded = if (key in expanded) expanded - key else expanded + key }
    val swipe = ThreadSwipe(
        archive = { onArchive(rowsOf(it)) },
        hide = { onHide(rowsOf(it)) }
    )
    val group = GroupSwipe(
        // Marking a group read holds its place for this visit, like opening it does.
        markRead = { s -> seenThisVisit = seenThisVisit + s.threads.map { it.key }; actions.markSeen(s.threads.flatMap { it.allIds }) },
        markUnread = { s -> actions.markUnread(s.threads.flatMap { it.allIds }) },
        archive = { s -> onArchive(s.allRows) }
    )

    Box(
        Modifier
            .fillMaxSize()
            .background(MarksyTheme.Background)
            .padding(padding)
            .consumeWindowInsets(padding)
    ) {
        MarksyList {
            if (lanes.isEmpty) {
                item(key = "empty") {
                    EmptyState("Nothing here yet", "New notifications matching this filter will appear here.")
                }
            }
            if (lanes.needsYou.isNotEmpty()) {
                val needsOpen = NEEDS_KEY in expanded
                item(key = "lane-needs") { SectionLabel("Needs you", lanes.needsYou.size, MarksyTheme.Negative) }
                items(if (needsOpen) lanes.needsYou else lanes.needsYou.take(NEEDS_VISIBLE), key = { "n-" + it.thread.key }) { need ->
                    Swipeable(need.thread, swipe) {
                        NeedCard(
                            need,
                            onClick = { openThread(need.thread) },
                            onLongClick = { actionThreadKey = need.thread.key },
                            onDone = { actions.resolve(need.thread.allIds) },
                            onSnooze = { actions.snooze(need.thread.allIds, System.currentTimeMillis() + HOUR_MS) }
                        )
                    }
                }
                if (lanes.needsYou.size > NEEDS_VISIBLE) item(key = "needs-more") {
                    MoreRow(
                        open = needsOpen,
                        hidden = lanes.needsYou.size - NEEDS_VISIBLE,
                        names = lanes.needsYou.drop(NEEDS_VISIBLE).map { it.thread.latest.title.ifBlank { it.thread.latest.sourceName } },
                        suffix = " need you",
                        standalone = true
                    ) { toggle(NEEDS_KEY) }
                }
            }
            if (lanes.fresh.isNotEmpty()) {
                item(key = "lane-new") { SectionLabel("New", lanes.fresh.sumOf { it.threads.size }, MarksyTheme.Positive) }
                items(lanes.fresh, key = { "s-" + it.key }) { stack ->
                    SourceStackCard(
                        stack,
                        expanded = stack.key in expanded,
                        swipe = swipe,
                        group = group,
                        onToggle = { toggle(stack.key) },
                        onOpen = { openThread(it) },
                        onLongClick = { actionThreadKey = it.key }
                    )
                }
            }
            if (lanes.earlier.isNotEmpty()) {
                item(key = "earlier-fold") { EarlierFold(lanes.earlier, earlierOpen) { earlierOpen = !earlierOpen } }
                if (earlierOpen) item(key = "earlier-list") {
                    EarlierList(
                        lanes.earlier,
                        expanded,
                        onToggle = { toggle(it) },
                        onOpen = { openThread(it) },
                        onLongClick = { actionThreadKey = it.key },
                        group = group,
                        onClearAll = {
                            onArchive(lanes.earlier.flatMap { s -> s.threads.flatMap { rowsOf(it) } })
                            earlierOpen = false
                        }
                    )
                }
            }
            item(key = "footer") { InboxFooter(lanes.snoozedCount, onOpenHistory) }
        }
        OneHandControls(
            filters = SmartInboxModel.Filter.entries.map { it.name to it.label },
            selectedFilter = filter.name,
            onFilterSelected = onFilterSelected,
            searchQuery = searchQuery,
            onSearchChange = { searchQuery = it },
            searchPlaceholder = "Search notifications…"
        )
    }

    val actionThread = actionThreadKey?.let { key ->
        (lanes.needsYou.map { it.thread } + (lanes.fresh + lanes.earlier).flatMap { it.threads }).firstOrNull { it.key == key }
    }
    if (actionThread != null) {
        // "Mark read" here must hold the thread's lane just like opening it does.
        val dialogActions = actions.copy(markSeen = { ids -> holdPlace(actionThread); actions.markSeen(ids) })
        ThreadActionsDialog(actionThread, dialogActions, onDismiss = { actionThreadKey = null })
    }
}

/** Header note for the Inbox tab: what needs the user and how much is new, or the active filter. */
fun inboxTitleNote(summary: SmartInboxModel.InboxSummary, filterName: String): String? {
    val filter = SmartInboxModel.Filter.entries.firstOrNull { it.name == filterName } ?: SmartInboxModel.Filter.ALL
    if (filter != SmartInboxModel.Filter.ALL) return filter.label
    return when {
        summary.needsYou > 0 -> "${summary.needsYou} need you · ${summary.newUnread} new"
        summary.newUnread > 0 -> "${summary.newUnread} new"
        else -> null
    }
}

/** Thread-level inbox actions; each receives every row id the thread represents. */
data class InboxActions(
    val markSeen: (List<Long>) -> Unit = {},
    val markUnread: (List<Long>) -> Unit = {},
    val resolve: (List<Long>) -> Unit = {},
    val reopen: (List<Long>) -> Unit = {},
    val snooze: (List<Long>, Long) -> Unit = { _, _ -> },
    val archive: (List<Long>) -> Unit = {},
    val prefer: (PersonalLearning.Subject, PersonalLearning.Preference?) -> Unit = { _, _ -> }
)

/** A short coloured line under a group row, e.g. Home's attention level and reason. */
internal data class RowNote(val text: String, val tint: Color)

internal data class ThreadSwipe(
    val archive: (InboxThread) -> Unit,
    val hide: (InboxThread) -> Unit
)

/** What a group header's swipe does to every item in the group. */
internal data class GroupSwipe(
    val markRead: (SmartInboxModel.SourceStack) -> Unit,
    val markUnread: (SmartInboxModel.SourceStack) -> Unit,
    val archive: (SmartInboxModel.SourceStack) -> Unit
)

/** Header swipe for a whole group: read/unread and archive (with undo). No group delete: it's permanent and a group can be large. */
@Composable
private fun SwipeableGroup(stack: SmartInboxModel.SourceStack, group: GroupSwipe, stacked: Boolean = true, content: @Composable (headerDrag: Modifier) -> Unit) {
    val actions = listOf(
        if (stack.unread > 0) SwipeTrayAction(Icons.Default.DoneAll, "Mark all from ${stack.label} read", MarksyTheme.SecondaryCyan) { group.markRead(stack) }
        else SwipeTrayAction(Icons.Default.MarkEmailUnread, "Mark all from ${stack.label} unread", MarksyTheme.SecondaryCyan) { group.markUnread(stack) },
        SwipeTrayAction(Icons.Default.Archive, "Archive all from ${stack.label}", MarksyTheme.PrimaryEmerald) { group.archive(stack) }
    )
    SwipeGroupCard(actions, stacked = stacked, content = content)
}

@Composable
private fun Swipeable(thread: InboxThread, swipe: ThreadSwipe, content: @Composable () -> Unit) {
    SwipeActionsRow(
        onArchive = { swipe.archive(thread) },
        onHide = { swipe.hide(thread) },
        content = content
    )
}

private fun urgencyColors(urgency: SmartInboxModel.Urgency): Pair<Color, Color> = when (urgency) {
    SmartInboxModel.Urgency.FAILURE -> MarksyTheme.RedUrgent to MarksyTheme.BadgeUrgentBg
    SmartInboxModel.Urgency.DUE -> MarksyTheme.YellowImportant to MarksyTheme.BadgeImportantBg
    SmartInboxModel.Urgency.FLAGGED -> MarksyTheme.PrimaryEmerald to MarksyTheme.BadgeTradingBg
}

private fun urgencyIcon(urgency: SmartInboxModel.Urgency): ImageVector = when (urgency) {
    SmartInboxModel.Urgency.FAILURE -> Icons.Default.Error
    SmartInboxModel.Urgency.DUE -> Icons.Default.Schedule
    SmartInboxModel.Urgency.FLAGGED -> Icons.Default.Star
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
private fun NeedCard(
    need: SmartInboxModel.NeedThread,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onDone: () -> Unit,
    onSnooze: () -> Unit
) {
    val event = need.thread.latest
    val (tint, tintBg) = urgencyColors(need.reason.urgency)
    MarksyCard(onClick = onClick, onLongClick = onLongClick, onLongClickLabel = "Thread actions", border = lerp(MarksyTheme.BorderGlow, tint, 0.38f)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MarksyBadge(need.reason.chip, tint, tintBg, icon = urgencyIcon(need.reason.urgency))
            Spacer(Modifier.weight(1f))
            Text(compactTime(event.postedAt).orEmpty(), color = MarksyTheme.TextMuted, style = MarksyType.Meta)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            SourceIcon(resolveSourceStyle(event), MarksySize.Icon, MarksySize.IconSmall)
            Spacer(Modifier.width(MarksySpace.Gap))
            Text(
                if (SmartInboxModel.isSms(event)) "SMS" else event.sourceName.ifBlank { "System" },
                color = MarksyTheme.TextMuted, style = MarksyType.Meta, maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
        Text(
            event.title.ifBlank { event.sourceName.ifBlank { "Notification event" } },
            color = MarksyTheme.TextPrimary, style = MarksyType.Subhead, fontWeight = FontWeight.Bold,
            maxLines = 2, overflow = TextOverflow.Ellipsis
        )
        val body = EventText.body(event.title, event.body)
        if (body.isNotBlank()) {
            Text(body, color = MarksyTheme.TextSecondary, style = MarksyType.Small, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(MarksySpace.Gap),
            verticalArrangement = Arrangement.spacedBy(MarksySpace.Inner)
        ) {
            Pill("Done", onClick = onDone)
            Pill("Snooze 1h", onClick = onSnooze)
        }
    }
}

@Composable
private fun SourceIcon(style: SourceStyle, size: Dp, iconSize: Dp) {
    Box(Modifier.size(size).clip(CircleShape).background(style.source.background), contentAlignment = Alignment.Center) {
        Icon(style.icon, contentDescription = null, tint = style.source.accent, modifier = Modifier.size(iconSize))
    }
}

private data class RowText(val headline: String, val detail: String, val bodyLed: Boolean)

// In an SMS stack the label already names the sender, so the message itself leads.
private fun rowText(event: NotificationEventEntity, smsStack: Boolean): RowText {
    val body = EventText.body(event.title, event.body)
    return if (smsStack) RowText(body.ifBlank { event.title }, "", true)
    else RowText(event.title.ifBlank { event.sourceName }, body, false)
}

@Composable
internal fun SourceStackCard(
    stack: SmartInboxModel.SourceStack,
    expanded: Boolean,
    swipe: ThreadSwipe,
    group: GroupSwipe,
    onToggle: () -> Unit,
    onOpen: (InboxThread) -> Unit,
    onLongClick: (InboxThread) -> Unit,
    note: (InboxThread) -> RowNote? = { null }
) = SwipeableGroup(stack, group) { headerDrag ->
    val hidden = stack.threads.size - STACK_VISIBLE
    MarksyGroupCard(Modifier.animateContentSize()) {
        Row(headerDrag.fillMaxWidth().padding(vertical = MarksySpace.Gap), verticalAlignment = Alignment.CenterVertically) {
            SourceIcon(resolveSourceStyle(stack.threads.first().latest), MarksySize.Avatar, MarksySize.IconSmall)
            Spacer(Modifier.width(MarksySpace.Gap))
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stack.label, color = MarksyTheme.TextPrimary, style = MarksyType.Body, fontWeight = FontWeight.Bold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false)
                )
                if (stack.isSms) {
                    Spacer(Modifier.width(MarksySpace.Inner))
                    Text("SMS", color = MarksyTheme.TextMuted, style = MarksyType.Meta)
                }
            }
            if (stack.unread > 0) {
                Text("${stack.unread} new", color = MarksyTheme.PrimaryEmerald, style = MarksyType.Meta, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(MarksySpace.Gap))
            }
            Text(compactTime(stack.latestAt).orEmpty(), color = MarksyTheme.TextMuted, style = MarksyType.Meta)
            if (stack.unread > 0) {
                IconButton(onClick = { group.markRead(stack) }, modifier = Modifier.size(MarksySize.Touch)) {
                    Icon(Icons.Default.DoneAll, contentDescription = "Mark all from ${stack.label} read", tint = MarksyTheme.TextSecondary, modifier = Modifier.size(MarksySize.Icon))
                }
            }
        }
        (if (expanded) stack.threads else stack.threads.take(STACK_VISIBLE)).forEach { thread ->
            MarksyDivider()
            // Swipe offset lives in the row's composition; keyed so a re-sort never leaves an open tray over another thread.
            key(thread.key) {
                Swipeable(thread, swipe) {
                    StackRow(thread, stack.isSms, first = thread === stack.threads.first(), note = note(thread), onClick = { onOpen(thread) }, onLongClick = { onLongClick(thread) })
                }
            }
        }
        if (hidden > 0) {
            MarksyDivider()
            MoreRow(
                open = expanded,
                hidden = hidden,
                names = stack.threads.drop(STACK_VISIBLE).map { t ->
                    rowText(t.latest, stack.isSms).let { if (it.bodyLed) compactTime(t.latest.postedAt).orEmpty() else it.headline }
                },
                suffix = "",
                standalone = false,
                onClick = onToggle
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun StackRow(thread: InboxThread, smsStack: Boolean, first: Boolean, note: RowNote?, onClick: () -> Unit, onLongClick: () -> Unit) {
    val text = rowText(thread.latest, smsStack)
    val unread = thread.unread
    Box(
        Modifier
            .background(MarksyTheme.Surface)
            .marksyRow(onClick = onClick, onLongClick = onLongClick, onLongClickLabel = "Thread actions")
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // The title takes the free width and the dot hugs it; a second weighted spacer would halve the title.
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text.headline,
                        color = MarksyTheme.TextPrimary,
                        style = MarksyType.Body,
                        fontWeight = when {
                            text.bodyLed && unread -> FontWeight.Medium
                            text.bodyLed -> FontWeight.Normal
                            unread -> FontWeight.Bold
                            else -> FontWeight.Medium
                        },
                        maxLines = if (text.bodyLed) 2 else 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (unread) {
                        Spacer(Modifier.width(MarksySpace.Inner))
                        Box(Modifier.size(MarksySize.Dot).clip(CircleShape).background(if (thread.critical) MarksyTheme.RedUrgent else MarksyTheme.PrimaryEmerald))
                    }
                }
                if (thread.count > 1) {
                    Spacer(Modifier.width(MarksySpace.Inner))
                    Box(
                        Modifier.heightIn(min = MarksySize.Icon).widthIn(min = MarksySize.Icon).clip(CircleShape)
                            .background(MarksyTheme.BadgeTradingBg).padding(horizontal = MarksySpace.Inner),
                        contentAlignment = Alignment.Center
                    ) { Text("${thread.count}", color = MarksyTheme.PrimaryEmerald, style = MarksyType.Caption, fontWeight = FontWeight.Bold) }
                }
                Spacer(Modifier.width(MarksySpace.Gap))
                Text(compactTime(thread.latest.postedAt).orEmpty(), color = MarksyTheme.TextMuted, style = MarksyType.Meta)
            }
            if (text.detail.isNotBlank()) {
                Text(
                    text.detail, color = MarksyTheme.TextSecondary, style = MarksyType.Small,
                    maxLines = if (first) 2 else 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = MarksySpace.Hair)
                )
            }
            note?.let { Text(it.text, color = it.tint, style = MarksyType.Meta, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = MarksySpace.Hair)) }
        }
    }
}

@Composable
private fun MoreRow(open: Boolean, hidden: Int, names: List<String>, suffix: String, standalone: Boolean, onClick: () -> Unit) {
    val rotation by animateFloatAsState(if (open) 180f else 0f, label = "more-chevron")
    val label = if (open) "Show less" else "Show $hidden more"
    val line: @Composable () -> Unit = {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.ExpandMore, contentDescription = null, tint = MarksyTheme.PrimaryEmerald, modifier = Modifier.size(MarksySize.Icon).rotate(rotation))
            Spacer(Modifier.width(MarksySpace.Inner))
            Text(if (open) "Show less" else "$hidden more$suffix", color = MarksyTheme.PrimaryEmerald, style = MarksyType.Small, fontWeight = FontWeight.Bold)
            if (!open) {
                Spacer(Modifier.width(MarksySpace.Gap))
                Text(names.joinToString(", "), color = MarksyTheme.TextMuted, style = MarksyType.Small, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            }
        }
    }
    if (standalone) MarksyRowCard(onClick = onClick, onClickLabel = label) { line() }
    else Box(Modifier.marksyRow(onClick = onClick, onClickLabel = label)) { line() }
}

@Composable
private fun EarlierFold(stacks: List<SmartInboxModel.SourceStack>, open: Boolean, onToggle: () -> Unit) {
    val count = stacks.sumOf { it.threads.size }
    val rotation by animateFloatAsState(if (open) 180f else 0f, label = "earlier-chevron")
    MarksyRowCard(onClick = onToggle, onClickLabel = if (open) "Fold earlier" else "Show earlier") {
    Column {
        MarksyCardHeader(
            "Earlier", icon = Icons.Default.History, iconTint = MarksyTheme.TextSecondary,
            trailing = { Icon(Icons.Default.ExpandMore, contentDescription = null, tint = MarksyTheme.TextMuted, modifier = Modifier.size(MarksySize.Icon).rotate(rotation)) }
        )
        Text("$count item${if (count == 1) "" else "s"} from ${stacks.size} source${if (stacks.size == 1) "" else "s"} · Seen, or older than a day", color = MarksyTheme.TextMuted, style = MarksyType.Meta)
    }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EarlierList(
    stacks: List<SmartInboxModel.SourceStack>,
    expanded: List<String>,
    onToggle: (String) -> Unit,
    onOpen: (InboxThread) -> Unit,
    onLongClick: (InboxThread) -> Unit,
    group: GroupSwipe,
    onClearAll: () -> Unit
) {
    MarksyGroupCard(Modifier.animateContentSize(), border = MarksyTheme.BorderGlow.copy(alpha = 0.6f)) {
        stacks.forEachIndexed { index, stack ->
            if (index > 0) MarksyDivider()
            val key = EARLIER_PREFIX + stack.key
            val open = key in expanded
            val style = resolveSourceStyle(stack.threads.first().latest)
            val shown = if (open) stack.threads else stack.threads.take(STACK_VISIBLE)
            // A one-row group is too short for a column of icons, so its tray lies flat.
            androidx.compose.runtime.key(stack.key) { SwipeableGroup(stack, group, stacked = shown.size > 1) { headerDrag -> Column(Modifier.background(MarksyTheme.Surface)) {
            Row(
                headerDrag.fillMaxWidth().clickable(enabled = stack.threads.size > STACK_VISIBLE) { onToggle(key) }.padding(vertical = MarksySpace.Gap),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.alpha(0.8f)) { SourceIcon(style, MarksySize.IconLarge, MarksySize.IconSmall) }
                Spacer(Modifier.width(MarksySpace.Gap))
                Text(
                    "${stack.label} · ${stack.threads.size}", color = MarksyTheme.TextSecondary, style = MarksyType.Small, fontWeight = FontWeight.Medium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(MarksySpace.Gap))
                Text(compactTime(stack.latestAt).orEmpty(), color = MarksyTheme.TextMuted, style = MarksyType.Meta)
                if (stack.threads.size > STACK_VISIBLE) Icon(
                    Icons.Default.ExpandMore, contentDescription = if (open) "Fold ${stack.label}" else "Show all ${stack.threads.size} from ${stack.label}",
                    tint = MarksyTheme.TextMuted, modifier = Modifier.size(MarksySize.Icon).rotate(if (open) 180f else 0f)
                )
            }
            shown.forEach { thread ->
                val t = rowText(thread.latest, stack.isSms)
                Row(
                    Modifier.marksyRow(onClick = { onOpen(thread) }, onLongClick = { onLongClick(thread) }, onLongClickLabel = "Thread actions"),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        if (t.bodyLed || t.detail.isBlank()) t.headline else "${t.headline} · ${t.detail}",
                        color = MarksyTheme.TextSecondary, style = MarksyType.Small, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(MarksySpace.Gap))
                    Text(compactTime(thread.latest.postedAt).orEmpty(), color = MarksyTheme.TextMuted, style = MarksyType.Meta)
                }
            }
            } } }
        }
        MarksyDivider()
        Row(Modifier.padding(vertical = MarksySpace.Gap)) {
            Pill("Clear all ${stacks.sumOf { it.threads.size }}", onClick = onClearAll)
        }
    }
}

@Composable
private fun InboxFooter(snoozed: Int, onOpenHistory: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = MarksySpace.Gap), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        if (snoozed > 0) Text("$snoozed snoozed  ·  ", color = MarksyTheme.TextMuted, style = MarksyType.Meta)
        Text(
            "History", color = MarksyTheme.TextSecondary, style = MarksyType.Meta, textDecoration = TextDecoration.Underline,
            modifier = Modifier.clickable(onClickLabel = "Open history", onClick = onOpenHistory).padding(MarksySpace.Tight)
        )
    }
}

@Composable
private fun ThreadActionsDialog(
    thread: SmartInboxModel.InboxThread,
    actions: InboxActions,
    onDismiss: () -> Unit
) {
    fun run(block: () -> Unit) { block(); onDismiss() }
    val ids = thread.allIds
    MarksyDialog(
        onDismissRequest = onDismiss,
        title = { Text(thread.latest.title.ifBlank { thread.latest.sourceName }, maxLines = 2) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(MarksySpace.Tight)) {
                Text("Why am I seeing this?", color = MarksyTheme.PrimaryEmerald, style = MarksyType.Small, fontWeight = FontWeight.Bold)
                thread.why.take(8).forEach { Text("• $it", color = MarksyTheme.TextSecondary, style = MarksyType.Small) }
                Spacer(Modifier.height(MarksySpace.Gap))
                if (thread.unread) MarksyButton("Mark read", onClick = { run { actions.markSeen(ids) } }, style = MarksyButtonStyle.Text)
                if (thread.bucket == SmartInboxModel.Bucket.RESOLVED) {
                    MarksyButton("Reopen", onClick = { run { actions.reopen(ids) } }, style = MarksyButtonStyle.Text)
                } else {
                    MarksyButton("Mark resolved", onClick = { run { actions.resolve(ids) } }, style = MarksyButtonStyle.Text)
                }
                MarksyButton("Snooze 1 hour", onClick = { run { actions.snooze(ids, System.currentTimeMillis() + 60 * 60 * 1000L) } }, style = MarksyButtonStyle.Text)
                MarksyButton("Snooze until tomorrow 9:00", onClick = { run { actions.snooze(ids, nextMorningMillis()) } }, style = MarksyButtonStyle.Text)
                MarksyButton("Archive", onClick = { run { actions.archive(ids) } }, style = MarksyButtonStyle.Text)
                // Explicit corrections (EPIC-012) outrank anything learned.
                PersonalLearning.subjectsOf(thread.latest)
                    .filter { it.type != PersonalLearning.SubjectType.CATEGORY }
                    .forEach { subject ->
                        MarksyButton("Always important: ${subject.label}", onClick = { run { actions.prefer(subject, PersonalLearning.Preference.ALWAYS_IMPORTANT) } }, style = MarksyButtonStyle.Text)
                        MarksyButton("Less from ${subject.label}", onClick = { run { actions.prefer(subject, PersonalLearning.Preference.LESS_IMPORTANT) } }, style = MarksyButtonStyle.Text)
                    }
            }
        },
        confirmButton = { MarksyButton("Close", onClick = onDismiss, style = MarksyButtonStyle.Text) }
    )
}

private fun nextMorningMillis(): Long {
    val zone = java.time.ZoneId.systemDefault()
    return java.time.LocalDate.now(zone).plusDays(1).atTime(9, 0).atZone(zone).toInstant().toEpochMilli()
}

private data class SourceStyle(val icon: ImageVector, val source: MarksySource)

/** Source identity only; importance is shown by lane and reason chip, never by app. */
private fun resolveSourceStyle(event: NotificationEventEntity): SourceStyle {
    // SMS rows carry the sender in the title, so a bank SMS still gets the bank icon.
    val src = (if (SmartInboxModel.isSms(event)) "${event.sourceName} ${event.title}" else event.sourceName).lowercase()
    val category = event.category.uppercase()
    return when {
        event.isTrading || src.contains("zerodha") || src.contains("groww") || src.contains("upstox") || src.contains("kite") ->
            SourceStyle(Icons.Default.ShowChart, MarksySource.TRADING)
        src.contains("whatsapp") -> SourceStyle(Icons.Default.Chat, MarksySource.WHATSAPP)
        src.contains("gmail") || src.contains("mail") || category == "WORK" -> SourceStyle(Icons.Default.Email, MarksySource.EMAIL)
        src.contains("icici") || src.contains("bank") || src.contains("upi") || category == "PAYMENTS" || category == "BANKING" ->
            SourceStyle(Icons.Default.AccountBalance, MarksySource.BANK)
        src.contains("swiggy") || src.contains("zomato") || category == "DELIVERY" -> SourceStyle(Icons.Default.LocalShipping, MarksySource.DELIVERY)
        category == "OTP" -> SourceStyle(Icons.Default.VpnKey, MarksySource.BANK)
        else -> SourceStyle(Icons.Default.Notifications, MarksySource.OTHER)
    }
}
