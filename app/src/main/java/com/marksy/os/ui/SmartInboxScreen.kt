package com.marksy.os.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
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
import androidx.compose.ui.unit.sp
import com.marksy.os.EmptyState
import com.marksy.os.data.local.NotificationEventEntity
import com.marksy.os.intelligence.PersonalLearning
import com.marksy.os.intelligence.SmartInboxModel
import com.marksy.os.intelligence.SmartInboxModel.InboxThread

private const val NEEDS_VISIBLE = 3
private const val STACK_VISIBLE = 3
// Rows sit just right of a rail under the group icon's centre (12dp inset + half its 28dp).
private val RAIL_X = 25.dp
private val ROW_INDENT = 36.dp
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
    onDelete: (List<NotificationEventEntity>) -> Unit = {},
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
        delete = { onDelete(rowsOf(it)) },
        hide = { onHide(rowsOf(it)) }
    )
    val group = GroupSwipe(
        // Marking a group read holds its place for this visit, like opening it does.
        markRead = { s -> seenThisVisit = seenThisVisit + s.threads.map { it.key }; actions.markSeen(s.threads.flatMap { it.allIds }) },
        markUnread = { s -> actions.markUnread(s.threads.flatMap { it.allIds }) },
        archive = { s -> onArchive(s.allRows) },
        delete = { s -> onDelete(s.allRows) }
    )

    Box(
        Modifier
            .fillMaxSize()
            .background(MarksyTheme.Background)
            .padding(padding)
            .consumeWindowInsets(padding)
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(bottom = OneHandListBottomPadding)
        ) {
            if (lanes.isEmpty) {
                item(key = "empty") {
                    EmptyState("Nothing here yet.", "New notifications matching this filter will appear here.")
                }
            }
            if (lanes.needsYou.isNotEmpty()) {
                val needsOpen = NEEDS_KEY in expanded
                item(key = "lane-needs") { LaneLabel("Needs you", lanes.needsYou.size, MarksyTheme.RedUrgent) }
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
                item(key = "lane-new") { LaneLabel("New", lanes.fresh.sumOf { it.threads.size }, MarksyTheme.PrimaryEmerald) }
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
            searchPlaceholder = "Search notifications..."
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

internal data class ThreadSwipe(
    val archive: (InboxThread) -> Unit,
    val delete: (InboxThread) -> Unit,
    val hide: (InboxThread) -> Unit
)

/** What a group header's swipe does to every item in the group. */
internal data class GroupSwipe(
    val markRead: (SmartInboxModel.SourceStack) -> Unit,
    val markUnread: (SmartInboxModel.SourceStack) -> Unit,
    val archive: (SmartInboxModel.SourceStack) -> Unit,
    val delete: (SmartInboxModel.SourceStack) -> Unit
)

/** Header swipe for a whole group: read/unread, archive, then delete behind a confirmation (delete has no undo). */
@Composable
private fun SwipeableGroup(stack: SmartInboxModel.SourceStack, group: GroupSwipe, stacked: Boolean = true, content: @Composable (headerDrag: Modifier) -> Unit) {
    var confirming by remember { mutableStateOf(false) }
    val actions = listOf(
        if (stack.unread > 0) SwipeTrayAction(Icons.Default.DoneAll, "Mark all from ${stack.label} read", MarksyTheme.SecondaryCyan) { group.markRead(stack) }
        else SwipeTrayAction(Icons.Default.MarkEmailUnread, "Mark all from ${stack.label} unread", MarksyTheme.SecondaryCyan) { group.markUnread(stack) },
        SwipeTrayAction(Icons.Default.Archive, "Archive all from ${stack.label}", MarksyTheme.PrimaryEmerald) { group.archive(stack) },
        SwipeTrayAction(Icons.Default.Delete, "Delete all from ${stack.label}", MarksyTheme.RedUrgent) { confirming = true }
    )
    SwipeGroupCard(actions, stacked = stacked, content = content)
    if (confirming) MarksyDialog(
        onDismissRequest = { confirming = false },
        title = { Text("Delete all ${stack.threads.size} from ${stack.label}?") },
        text = { Text("This can't be undone. Archive keeps them instead.", color = MarksyTheme.TextSecondary, fontSize = 13.sp) },
        dismissButton = { TextButton(onClick = { confirming = false }) { Text("Cancel", color = MarksyTheme.TextSecondary) } },
        confirmButton = {
            TextButton(onClick = { confirming = false; group.delete(stack) }) { Text("Delete", color = MarksyTheme.RedUrgent, fontWeight = FontWeight.Bold) }
        }
    )
}

@Composable
private fun Swipeable(thread: InboxThread, swipe: ThreadSwipe, content: @Composable () -> Unit) {
    SwipeActionsRow(
        onDelete = { swipe.delete(thread) },
        onArchive = { swipe.archive(thread) },
        onHide = { swipe.hide(thread) },
        content = content
    )
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
    val shape = RoundedCornerShape(16.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MarksyTheme.Surface)
            .border(1.dp, lerp(MarksyTheme.BorderGlow, tint, 0.38f), shape)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick, onLongClickLabel = "Thread actions")
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier.clip(RoundedCornerShape(50)).background(tintBg).padding(start = 7.dp, end = 9.dp, top = 3.dp, bottom = 3.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(urgencyIcon(need.reason.urgency), contentDescription = null, tint = tint, modifier = Modifier.size(13.dp))
                Spacer(Modifier.width(5.dp))
                Text(need.reason.chip, color = tint, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.weight(1f))
            Text(compactTime(event.postedAt).orEmpty(), color = MarksyTheme.TextMuted, fontSize = 11.sp)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            SourceIcon(resolveSourceStyle(event), 18.dp, 11.dp)
            Spacer(Modifier.width(7.dp))
            Text(
                if (SmartInboxModel.isSms(event)) "SMS" else event.sourceName.ifBlank { "System" },
                color = MarksyTheme.TextMuted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
        Text(
            event.title.ifBlank { event.sourceName.ifBlank { "Notification event" } },
            color = MarksyTheme.TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold,
            maxLines = 2, overflow = TextOverflow.Ellipsis
        )
        val body = EventText.body(event.title, event.body)
        if (body.isNotBlank()) {
            Text(body, color = MarksyTheme.TextSecondary, fontSize = 12.sp, lineHeight = 17.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        FlowRow(
            Modifier.padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Pill("Done", onClick = onDone)
            Pill("Snooze 1h", onClick = onSnooze)
        }
    }
}

@Composable
private fun SourceIcon(style: SourceStyle, size: Dp, iconSize: Dp) {
    Box(Modifier.size(size).clip(CircleShape).background(style.iconBg), contentAlignment = Alignment.Center) {
        Icon(style.icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(iconSize))
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
    note: (InboxThread) -> String? = { null }
) = SwipeableGroup(stack, group) { headerDrag ->
    val hidden = stack.threads.size - STACK_VISIBLE
    val peek = hidden > 0 && !expanded
    val shape = RoundedCornerShape(16.dp)
    Box(Modifier.fillMaxWidth()) {
        // A second card edge peeking out underneath says "there is more in here".
        if (peek) Box(
            Modifier.matchParentSize().padding(start = 12.dp, end = 12.dp, top = 6.dp)
                .clip(shape).background(MarksyTheme.Surface).border(1.dp, MarksyTheme.BorderGlow, shape)
        )
        Column(
            Modifier
                .fillMaxWidth()
                .padding(bottom = if (peek) 6.dp else 0.dp)
                .clip(shape)
                .background(MarksyTheme.Surface)
                .border(1.dp, MarksyTheme.BorderGlow, shape)
                .animateContentSize()
        ) {
            Row(headerDrag.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                SourceIcon(resolveSourceStyle(stack.threads.first().latest), 28.dp, 15.dp)
                Spacer(Modifier.width(10.dp))
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stack.label, color = MarksyTheme.TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false)
                    )
                    if (stack.isSms) {
                        Spacer(Modifier.width(6.dp))
                        Text("SMS", color = MarksyTheme.TextMuted, fontSize = 11.sp)
                    }
                }
                if (stack.unread > 0) {
                    Text("${stack.unread} new", color = MarksyTheme.PrimaryEmerald, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(8.dp))
                }
                Text(compactTime(stack.latestAt).orEmpty(), color = MarksyTheme.TextMuted, fontSize = 11.sp)
                if (stack.unread > 0) {
                    IconButton(onClick = { group.markRead(stack) }, modifier = Modifier.size(40.dp)) {
                        Icon(Icons.Default.DoneAll, contentDescription = "Mark all from ${stack.label} read", tint = MarksyTheme.TextSecondary, modifier = Modifier.size(18.dp))
                    }
                } else {
                    Spacer(Modifier.width(12.dp))
                }
            }
            val rail = resolveSourceStyle(stack.threads.first().latest).iconBg
            (if (expanded) stack.threads else stack.threads.take(STACK_VISIBLE)).forEachIndexed { index, thread ->
                if (index > 0) HorizontalDivider(Modifier.padding(start = ROW_INDENT), color = MarksyTheme.BorderGlow.copy(alpha = 0.5f))
                // Swipe offset lives in the row's composition; keyed so a re-sort never leaves an open tray over another thread.
                key(thread.key) {
                    Swipeable(thread, swipe) {
                        StackRow(thread, stack.isSms, first = index == 0, note = note(thread), rail = rail, onClick = { onOpen(thread) }, onLongClick = { onLongClick(thread) })
                    }
                }
            }
            if (hidden > 0) {
                HorizontalDivider(color = MarksyTheme.BorderGlow.copy(alpha = 0.5f))
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
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun StackRow(thread: InboxThread, smsStack: Boolean, first: Boolean, note: String?, rail: Color, onClick: () -> Unit, onLongClick: () -> Unit) {
    val text = rowText(thread.latest, smsStack)
    val unread = thread.unread
    Box(
        Modifier
            .fillMaxWidth()
            .background(MarksyTheme.Surface)
            // The rail runs down from the group's icon, tying these rows to it without a wide indent.
            .drawBehind { drawRect(rail.copy(alpha = 0.6f), topLeft = Offset(RAIL_X.toPx(), 0f), size = Size(2.dp.toPx(), size.height)) }
            .combinedClickable(onClick = onClick, onLongClick = onLongClick, onLongClickLabel = "Thread actions")
            .padding(start = ROW_INDENT, end = 14.dp, top = 7.dp, bottom = 9.dp)
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text.headline,
                    color = MarksyTheme.TextPrimary,
                    fontSize = if (text.bodyLed) 13.sp else 13.5.sp,
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
                    Spacer(Modifier.width(6.dp))
                    Box(Modifier.size(7.dp).clip(CircleShape).background(if (thread.critical) MarksyTheme.RedUrgent else MarksyTheme.PrimaryEmerald))
                }
                Spacer(Modifier.weight(1f))
                if (thread.count > 1) {
                    Spacer(Modifier.width(6.dp))
                    Box(
                        Modifier.heightIn(min = 18.dp).widthIn(min = 18.dp).clip(RoundedCornerShape(50))
                            .background(MarksyTheme.BadgeTradingBg).padding(horizontal = 5.dp),
                        contentAlignment = Alignment.Center
                    ) { Text("${thread.count}", color = MarksyTheme.PrimaryEmerald, fontSize = 10.sp, fontWeight = FontWeight.Bold) }
                }
                Spacer(Modifier.width(8.dp))
                Text(compactTime(thread.latest.postedAt).orEmpty(), color = MarksyTheme.TextMuted, fontSize = 11.sp)
            }
            if (text.detail.isNotBlank()) {
                Text(
                    text.detail, color = MarksyTheme.TextSecondary, fontSize = 12.sp, lineHeight = 17.sp,
                    maxLines = if (first) 2 else 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp)
                )
            }
            note?.let { Text(it, color = MarksyTheme.YellowImportant, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp)) }
        }
    }
}

@Composable
private fun MoreRow(open: Boolean, hidden: Int, names: List<String>, suffix: String, standalone: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    val rotation by animateFloatAsState(if (open) 180f else 0f, label = "more-chevron")
    val base = if (standalone) Modifier.fillMaxWidth().clip(shape).background(MarksyTheme.Surface).border(1.dp, MarksyTheme.BorderGlow, shape)
    else Modifier.fillMaxWidth()
    Row(
        base.clickable(onClickLabel = if (open) "Show less" else "Show $hidden more", onClick = onClick)
            .padding(start = if (standalone) 14.dp else 50.dp, end = 14.dp, top = 9.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Default.ExpandMore, contentDescription = null, tint = MarksyTheme.PrimaryEmerald, modifier = Modifier.size(18.dp).rotate(rotation))
        Spacer(Modifier.width(6.dp))
        Text(if (open) "Show less" else "$hidden more$suffix", color = MarksyTheme.PrimaryEmerald, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        if (!open) {
            Spacer(Modifier.width(8.dp))
            Text(names.joinToString(", "), color = MarksyTheme.TextMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun EarlierFold(stacks: List<SmartInboxModel.SourceStack>, open: Boolean, onToggle: () -> Unit) {
    val count = stacks.sumOf { it.threads.size }
    val rotation by animateFloatAsState(if (open) 180f else 0f, label = "earlier-chevron")
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 6.dp)
            .clip(RoundedCornerShape(16.dp))
            .drawBehind {
                drawRoundRect(
                    color = MarksyTheme.BorderGlow,
                    cornerRadius = CornerRadius(16.dp.toPx()),
                    style = Stroke(width = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx())))
                )
            }
            .clickable(onClickLabel = if (open) "Fold earlier" else "Show earlier", onClick = onToggle)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Default.History, contentDescription = null, tint = MarksyTheme.TextSecondary, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(color = MarksyTheme.TextPrimary, fontWeight = FontWeight.Bold)) { append("Earlier") }
                    append(" · $count item${if (count == 1) "" else "s"} from ${stacks.size} source${if (stacks.size == 1) "" else "s"}")
                },
                color = MarksyTheme.TextSecondary, fontSize = 13.sp
            )
            Text("Seen, or older than a day", color = MarksyTheme.TextMuted, fontSize = 11.sp)
        }
        Icon(Icons.Default.ExpandMore, contentDescription = null, tint = MarksyTheme.TextSecondary, modifier = Modifier.size(20.dp).rotate(rotation))
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
    val shape = RoundedCornerShape(16.dp)
    Column(
        Modifier.fillMaxWidth().clip(shape).background(MarksyTheme.Surface)
            .border(1.dp, MarksyTheme.BorderGlow.copy(alpha = 0.6f), shape)
            .animateContentSize()
            .padding(vertical = 4.dp)
    ) {
        stacks.forEach { stack ->
            val key = EARLIER_PREFIX + stack.key
            val open = key in expanded
            val style = resolveSourceStyle(stack.threads.first().latest)
            val shown = if (open) stack.threads else stack.threads.take(STACK_VISIBLE)
            // A one-row group is too short for a column of icons, so its tray lies flat.
            androidx.compose.runtime.key(stack.key) { SwipeableGroup(stack, group, stacked = shown.size > 1) { headerDrag -> Column(Modifier.background(MarksyTheme.Surface)) {
            Row(
                headerDrag.fillMaxWidth().clickable(enabled = stack.threads.size > STACK_VISIBLE) { onToggle(key) }.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.alpha(0.8f)) { SourceIcon(style, 24.dp, 13.dp) }
                Spacer(Modifier.width(10.dp))
                Text(
                    "${stack.label} · ${stack.threads.size}", color = MarksyTheme.TextSecondary, fontSize = 12.5.sp, fontWeight = FontWeight.Medium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                Text(compactTime(stack.latestAt).orEmpty(), color = MarksyTheme.TextMuted, fontSize = 11.sp)
                if (stack.threads.size > STACK_VISIBLE) Icon(
                    Icons.Default.ExpandMore, contentDescription = if (open) "Fold ${stack.label}" else "Show all ${stack.threads.size} from ${stack.label}",
                    tint = MarksyTheme.TextMuted, modifier = Modifier.size(18.dp).rotate(if (open) 180f else 0f)
                )
            }
            shown.forEach { thread ->
                val t = rowText(thread.latest, stack.isSms)
                Row(
                    Modifier.fillMaxWidth()
                        .drawBehind { drawRect(style.iconBg.copy(alpha = 0.6f), topLeft = Offset(23.dp.toPx(), 0f), size = Size(2.dp.toPx(), size.height)) }
                        .combinedClickable(onClick = { onOpen(thread) }, onLongClick = { onLongClick(thread) }, onLongClickLabel = "Thread actions")
                        .padding(start = 34.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        if (t.bodyLed || t.detail.isBlank()) t.headline else "${t.headline} · ${t.detail}",
                        color = MarksyTheme.TextSecondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(compactTime(thread.latest.postedAt).orEmpty(), color = MarksyTheme.TextMuted, fontSize = 11.sp)
                }
            }
            } } }
        }
        Row(Modifier.padding(start = 12.dp, top = 6.dp, bottom = 4.dp)) {
            Pill("Clear all ${stacks.sumOf { it.threads.size }}", onClick = onClearAll)
        }
    }
}

@Composable
private fun InboxFooter(snoozed: Int, onOpenHistory: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        if (snoozed > 0) Text("$snoozed snoozed  ·  ", color = MarksyTheme.TextMuted, fontSize = 11.sp)
        Text(
            "History", color = MarksyTheme.TextSecondary, fontSize = 11.sp, textDecoration = TextDecoration.Underline,
            modifier = Modifier.clickable(onClickLabel = "Open history", onClick = onOpenHistory).padding(4.dp)
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
        title = { Text(thread.latest.title.ifBlank { thread.latest.sourceName }, color = MarksyTheme.TextPrimary, maxLines = 2) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Why am I seeing this?", color = MarksyTheme.PrimaryEmerald, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                thread.why.take(8).forEach { Text("• $it", color = MarksyTheme.TextSecondary, fontSize = 12.sp) }
                Spacer(Modifier.height(8.dp))
                if (thread.unread) TextButton(onClick = { run { actions.markSeen(ids) } }) { Text("Mark read") }
                if (thread.bucket == SmartInboxModel.Bucket.RESOLVED) {
                    TextButton(onClick = { run { actions.reopen(ids) } }) { Text("Reopen") }
                } else {
                    TextButton(onClick = { run { actions.resolve(ids) } }) { Text("Mark resolved") }
                }
                TextButton(onClick = { run { actions.snooze(ids, System.currentTimeMillis() + 60 * 60 * 1000L) } }) { Text("Snooze 1 hour") }
                TextButton(onClick = { run { actions.snooze(ids, nextMorningMillis()) } }) { Text("Snooze until tomorrow 9:00") }
                TextButton(onClick = { run { actions.archive(ids) } }) { Text("Archive") }
                // Explicit corrections (EPIC-012) outrank anything learned.
                PersonalLearning.subjectsOf(thread.latest)
                    .filter { it.type != PersonalLearning.SubjectType.CATEGORY }
                    .forEach { subject ->
                        TextButton(onClick = { run { actions.prefer(subject, PersonalLearning.Preference.ALWAYS_IMPORTANT) } }) { Text("Always important: ${subject.label}") }
                        TextButton(onClick = { run { actions.prefer(subject, PersonalLearning.Preference.LESS_IMPORTANT) } }) { Text("Less from ${subject.label}") }
                    }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}

private fun nextMorningMillis(): Long {
    val zone = java.time.ZoneId.systemDefault()
    return java.time.LocalDate.now(zone).plusDays(1).atTime(9, 0).atZone(zone).toInstant().toEpochMilli()
}

private data class SourceStyle(val icon: ImageVector, val iconBg: Color)

/** Source identity only; importance is shown by lane and reason chip, never by app. */
private fun resolveSourceStyle(event: NotificationEventEntity): SourceStyle {
    // SMS rows carry the sender in the title, so a bank SMS still gets the bank icon.
    val src = (if (SmartInboxModel.isSms(event)) "${event.sourceName} ${event.title}" else event.sourceName).lowercase()
    val category = event.category.uppercase()
    return when {
        event.isTrading || src.contains("zerodha") || src.contains("groww") || src.contains("upstox") || src.contains("kite") ->
            SourceStyle(Icons.Default.ShowChart, Color(0xFFC62828))
        src.contains("whatsapp") -> SourceStyle(Icons.Default.Chat, Color(0xFF2E7D32))
        src.contains("gmail") || src.contains("mail") || category == "WORK" -> SourceStyle(Icons.Default.Email, Color(0xFF1565C0))
        src.contains("icici") || src.contains("bank") || src.contains("upi") || category == "PAYMENTS" || category == "BANKING" ->
            SourceStyle(Icons.Default.AccountBalance, Color(0xFF0288D1))
        src.contains("swiggy") || src.contains("zomato") || category == "DELIVERY" -> SourceStyle(Icons.Default.LocalShipping, Color(0xFFE65100))
        category == "OTP" -> SourceStyle(Icons.Default.VpnKey, Color(0xFF0288D1))
        else -> SourceStyle(Icons.Default.Notifications, Color(0xFF37474F))
    }
}
