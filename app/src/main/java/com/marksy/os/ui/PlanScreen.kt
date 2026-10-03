package com.marksy.os.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.marksy.os.EmptyState
import com.marksy.os.data.local.PlanItemEntity
import com.marksy.os.plan.PlanKind
import com.marksy.os.plan.PlanModel
import com.marksy.os.plan.PlanRules
import com.marksy.os.plan.PlanStatus
import com.marksy.os.plan.PlanText
import com.marksy.os.plan.Recurrence
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

private const val VIEW_REMINDERS = "Reminders"
private const val VIEW_BOARD = "Board"
val PlanViews = listOf(VIEW_REMINDERS, VIEW_BOARD)

/** Plan tab: reminders (bills, EMIs, card dues, birthdays, follow-ups) and a To do / Doing / Done board over the same items. */
@Composable
fun PlanScreen(
    items: List<PlanItemEntity>,
    padding: PaddingValues,
    view: String,
    onViewSelected: (String) -> Unit,
    onAdd: () -> Unit,
    onEdit: (PlanItemEntity) -> Unit,
    onStatus: (PlanItemEntity, PlanStatus) -> Unit,
    contactsAccess: Boolean,
    onImportBirthdays: () -> Unit
) {
    val now = remember(items) { System.currentTimeMillis() }
    Box(Modifier.fillMaxSize().background(MarksyTheme.Background).padding(padding).consumeWindowInsets(padding)) {
        if (view == VIEW_BOARD) PlanBoard(items, now, onEdit, onStatus)
        else ReminderList(items, now, onEdit, onStatus)
        OneHandControls(
            filters = PlanViews.map { it to it },
            selectedFilter = view,
            filterIsView = false,
            onFilterSelected = onViewSelected,
            actions = listOfNotNull(
                FloatingAction(Icons.Default.Cake, "Import birthdays from contacts", onClick = onImportBirthdays).takeIf { !contactsAccess },
                FloatingAction(Icons.Default.Add, "Add reminder", onClick = onAdd)
            )
        )
    }
}

@Composable
private fun ReminderList(items: List<PlanItemEntity>, now: Long, onEdit: (PlanItemEntity) -> Unit, onStatus: (PlanItemEntity, PlanStatus) -> Unit) {
    val groups = remember(items, now) { PlanModel.reminders(items, now) }
    var showDone by rememberSaveable { mutableStateOf(false) }
    MarksyList {
        if (groups.overdue.isEmpty() && groups.next7.isEmpty() && groups.later.isEmpty()) item {
            EmptyState("No reminders yet", "Bill, EMI and card due messages become reminders automatically. Tap + to add a bill or birthday.")
        }
        listOf("Overdue" to groups.overdue, "Next 7 days" to groups.next7, "Later" to groups.later).forEach { (label, list) ->
            if (list.isNotEmpty()) {
                item(key = "h-$label") { PlanSectionTitle(label, list.size) }
                items(list, key = { "r-${it.id}" }) { PlanRow(it, now, onEdit, onStatus) }
            }
        }
        if (groups.done.isNotEmpty()) {
            item(key = "h-done") {
                MarksyButton((if (showDone) "Hide done" else "Show done") + " · ${groups.done.size}", onClick = { showDone = !showDone }, style = MarksyButtonStyle.Text, color = MarksyTheme.TextMuted)
            }
            if (showDone) items(groups.done, key = { "d-${it.id}" }) { PlanRow(it, now, onEdit, onStatus) }
        }
    }
}

@Composable
private fun PlanSectionTitle(label: String, count: Int) = SectionLabel(label, count)

@Composable
private fun PlanRow(item: PlanItemEntity, now: Long, onEdit: (PlanItemEntity) -> Unit, onStatus: (PlanItemEntity, PlanStatus) -> Unit) {
    val done = item.status == PlanStatus.DONE.name
    val critical = PlanRules.isCritical(PlanStatus.valueOf(item.status), item.dueAt, now)
    MarksyRowCard(
        border = if (critical) MarksyTheme.Negative else MarksyTheme.BorderGlow, fill = if (critical) MarksyTheme.BadgeUrgentBg else MarksyTheme.Surface,
        onClick = { onEdit(item) }
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            KindBadge(item.kind, critical)
            Spacer(Modifier.width(MarksySpace.Gap))
            Column(Modifier.weight(1f)) {
                Text(item.title, color = if (done) MarksyTheme.TextMuted else MarksyTheme.TextPrimary, style = MarksyType.Subhead, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val detail = listOfNotNull(PlanText.amount(item.amountMinor), item.counterparty?.takeIf { it !in item.title }).joinToString(" · ")
                if (detail.isNotEmpty()) Text(detail, color = MarksyTheme.TextSecondary, style = MarksyType.Small, maxLines = 1, overflow = TextOverflow.Ellipsis)
                PlanText.dueLabel(item.dueAt, now)?.let {
                    Text(it, color = if (critical) MarksyTheme.Negative else MarksyTheme.TextMuted, style = MarksyType.Meta, fontWeight = if (critical) FontWeight.Bold else FontWeight.Normal)
                }
            }
            IconButton(onClick = { onStatus(item, if (done) PlanStatus.TODO else PlanStatus.DONE) }) {
                Icon(
                    if (done) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                    contentDescription = if (done) "Mark not done" else "Mark done",
                    tint = if (done) MarksyTheme.PrimaryEmerald else MarksyTheme.TextSecondary
                )
            }
        }
    }
}

@Composable
private fun KindBadge(kind: String, critical: Boolean) {
    Box(
        Modifier.size(34.dp).clip(CircleShape).background(if (critical) MarksyTheme.Negative else MarksyTheme.SurfaceRaised),
        contentAlignment = Alignment.Center
    ) {
        Icon(kindIcon(kind), contentDescription = kindLabel(kind), tint = if (critical) MarksyTheme.TextPrimary else MarksyTheme.PrimaryEmerald, modifier = Modifier.size(MarksySize.Icon))
    }
}

private fun kindIcon(kind: String): ImageVector = when (kind) {
    PlanKind.CARD_DUE.name -> Icons.Default.CreditCard
    PlanKind.EMI.name -> Icons.Default.AccountBalance
    PlanKind.BIRTHDAY.name -> Icons.Default.Cake
    PlanKind.TASK.name -> Icons.Default.TaskAlt
    PlanKind.FOLLOW_UP.name -> Icons.Default.Alarm
    else -> Icons.Default.Receipt
}

private fun kindLabel(kind: String) = PlanKind.entries.firstOrNull { it.name == kind }?.label ?: kind

@Composable
private fun PlanBoard(items: List<PlanItemEntity>, now: Long, onEdit: (PlanItemEntity) -> Unit, onStatus: (PlanItemEntity, PlanStatus) -> Unit) {
    LazyRow(
        Modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(MarksySpace.CardPadding),
        contentPadding = PaddingValues(start = MarksySpace.Gutter, end = MarksySpace.Gutter, top = MarksySpace.ListGap)
    ) {
        items(PlanStatus.entries, key = { it.name }) { status ->
            val column = remember(items, status) { PlanModel.column(items, status) }
            MarksyCard(Modifier.width(272.dp).fillMaxHeight()) {
                Text("${status.label} · ${column.size}", color = MarksyTheme.PrimaryEmerald, style = MarksyType.Subhead, fontWeight = FontWeight.Bold)
                Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = OneHandListBottomPadding), verticalArrangement = Arrangement.spacedBy(MarksySpace.Gap)) {
                    if (column.isEmpty()) Text("Nothing here", color = MarksyTheme.TextMuted, style = MarksyType.Small)
                    column.forEach { BoardCard(it, now, onEdit, onStatus) }
                }
            }
        }
    }
}

@Composable
private fun BoardCard(item: PlanItemEntity, now: Long, onEdit: (PlanItemEntity) -> Unit, onStatus: (PlanItemEntity, PlanStatus) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val critical = PlanRules.isCritical(PlanStatus.valueOf(item.status), item.dueAt, now)
    Box {
        MarksyRowCard(
            border = if (critical) MarksyTheme.Negative else MarksyTheme.BorderGlow, fill = if (critical) MarksyTheme.BadgeUrgentBg else MarksyTheme.SurfaceRaised,
            onClick = { menu = true }
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(kindIcon(item.kind), contentDescription = kindLabel(item.kind), tint = if (critical) MarksyTheme.Negative else MarksyTheme.PrimaryEmerald, modifier = Modifier.size(MarksySize.IconSmall))
                Spacer(Modifier.width(MarksySpace.Inner))
                Text(item.title, color = MarksyTheme.TextPrimary, style = MarksyType.Body, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            val detail = listOfNotNull(PlanText.dueLabel(item.dueAt, now), PlanText.amount(item.amountMinor)).joinToString(" · ")
            if (detail.isNotEmpty()) Text(detail, color = if (critical) MarksyTheme.Negative else MarksyTheme.TextMuted, style = MarksyType.Meta)
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            PlanStatus.entries.filter { it.name != item.status }.forEach { target ->
                DropdownMenuItem(text = { Text("Move to ${target.label}") }, onClick = { menu = false; onStatus(item, target) })
            }
            DropdownMenuItem(text = { Text("Edit") }, onClick = { menu = false; onEdit(item) })
        }
    }
}

/** Home's "Upcoming" card, styled like Market Pulse: next reminders, critical ones in red. */
@Composable
fun PlanUpcomingCard(items: List<PlanItemEntity>, onOpenPlan: () -> Unit, onAdd: () -> Unit) {
    val now = remember(items) { System.currentTimeMillis() }
    val upcoming = remember(items) { PlanModel.upcoming(items, 4) }
    MarksyCard(onClick = onOpenPlan) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.EventNote, contentDescription = null, tint = MarksyTheme.PrimaryEmerald, modifier = Modifier.size(MarksySize.Icon))
                Spacer(Modifier.width(MarksySpace.Gap))
                Text("Upcoming", color = MarksyTheme.TextPrimary, style = MarksyType.Lead, fontWeight = FontWeight.Bold)
            }
            Icon(Icons.Default.Add, contentDescription = "Add reminder", tint = MarksyTheme.PrimaryEmerald, modifier = Modifier.size(MarksySize.Icon).clip(CircleShape).clickable(onClick = onAdd))
        }
        if (upcoming.isEmpty()) {
            Text("No bills, EMIs or birthdays coming up", color = MarksyTheme.TextMuted, style = MarksyType.Small)
        }
        upcoming.forEach { item ->
            val critical = PlanRules.isCritical(PlanStatus.valueOf(item.status), item.dueAt, now)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Icon(kindIcon(item.kind), contentDescription = kindLabel(item.kind), tint = if (critical) MarksyTheme.Negative else MarksyTheme.TextSecondary, modifier = Modifier.size(MarksySize.IconSmall))
                Spacer(Modifier.width(MarksySpace.Gap))
                Text(item.title, color = MarksyTheme.TextPrimary, style = MarksyType.Body, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                PlanText.amount(item.amountMinor)?.let { Text(it, color = MarksyTheme.TextSecondary, style = MarksyType.Small, modifier = Modifier.padding(horizontal = MarksySpace.Inner)) }
                Text(
                    PlanText.dueLabel(item.dueAt, now).orEmpty().substringBefore(" · "),
                    color = if (critical) MarksyTheme.Negative else MarksyTheme.TextMuted,
                    style = MarksyType.Small,
                    fontWeight = if (critical) FontWeight.Bold else FontWeight.Normal
                )
            }
        }
    }
}

/** Add or edit a plan item. Bills and EMIs default to monthly, birthdays to yearly. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlanItemDialog(
    initial: PlanItemEntity?,
    onDismiss: () -> Unit,
    onSave: (kind: PlanKind, title: String, counterparty: String?, amountMinor: Long?, dueAt: Long?, recurrence: Recurrence) -> Unit,
    onDelete: (() -> Unit)? = null
) {
    val zone = ZoneId.systemDefault()
    var kind by remember { mutableStateOf(initial?.kind?.let(PlanKind::valueOf) ?: PlanKind.BILL) }
    var title by remember { mutableStateOf(initial?.title.orEmpty()) }
    var counterparty by remember { mutableStateOf(initial?.counterparty.orEmpty()) }
    var amount by remember { mutableStateOf(initial?.amountMinor?.let { (it / 100).toString() }.orEmpty()) }
    var date by remember { mutableStateOf(initial?.dueAt?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }) }
    var dueTime by remember { mutableStateOf(initial?.dueAt) }
    var recurrence by remember { mutableStateOf(initial?.recurrence?.let(Recurrence::valueOf) ?: Recurrence.MONTHLY) }
    var picking by remember { mutableStateOf(false) }

    MarksyDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "Add reminder" else "Edit", color = MarksyTheme.TextPrimary) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(MarksySpace.ListGap)) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(MarksySpace.Inner)) {
                    listOf(PlanKind.BILL, PlanKind.EMI, PlanKind.CARD_DUE, PlanKind.BIRTHDAY, PlanKind.TASK).forEach { k ->
                        Pill(k.label, selected = kind == k) {
                            kind = k
                            if (initial == null) recurrence = when (k) { PlanKind.BIRTHDAY -> Recurrence.YEARLY; PlanKind.TASK -> Recurrence.NONE; else -> Recurrence.MONTHLY }
                        }
                    }
                }
                CompactTextField(title, { title = it }, Modifier.fillMaxWidth(), label = if (kind == PlanKind.BIRTHDAY) "Whose birthday" else "Title")
                if (kind != PlanKind.BIRTHDAY && kind != PlanKind.TASK) {
                    CompactTextField(counterparty, { counterparty = it }, Modifier.fillMaxWidth(), label = "Pay to (optional)")
                    CompactTextField(
                        amount, { v -> amount = v.filter(Char::isDigit) }, Modifier.fillMaxWidth(), label = "Amount ₹ (optional)",
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                }
                MarksyButton(
                    date?.let { PlanText.dueLabel(PlanRules.atAlertHour(it, zone), System.currentTimeMillis(), zone) } ?: if (kind == PlanKind.TASK) "Due date (optional)" else "Pick due date",
                    onClick = { picking = true }, style = MarksyButtonStyle.Outlined, icon = Icons.Default.Event
                )
                Row(horizontalArrangement = Arrangement.spacedBy(MarksySpace.Inner)) {
                    Recurrence.entries.forEach { r -> Pill(r.label, selected = recurrence == r) { recurrence = r } }
                }
            }
        },
        confirmButton = {
            val valid = title.isNotBlank() && (date != null || kind == PlanKind.TASK)
            MarksyButton("Save", enabled = valid, style = MarksyButtonStyle.Text, onClick = {
                // Keep a follow-up's own time; everything else alerts at the standard hour.
                val due = date?.let { d -> dueTime?.takeIf { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() == d } ?: PlanRules.atAlertHour(d, zone) }
                val saveTitle = if (kind == PlanKind.BIRTHDAY && !title.contains("birthday", ignoreCase = true)) "${title.trim()}'s birthday" else title
                onSave(kind, saveTitle, counterparty.ifBlank { null }, amount.toLongOrNull()?.times(100), due, recurrence)
            })
        },
        dismissButton = {
            Row {
                onDelete?.let { MarksyButton("Delete", onClick = it, style = MarksyButtonStyle.Text, color = MarksyTheme.Negative) }
                MarksyButton("Cancel", onClick = onDismiss, style = MarksyButtonStyle.Text, color = MarksyTheme.TextSecondary)
            }
        }
    )

    if (picking) {
        val state = rememberDatePickerState(initialSelectedDateMillis = (date ?: LocalDate.now(zone)).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { picking = false },
            confirmButton = {
                MarksyButton("OK", style = MarksyButtonStyle.Text, onClick = {
                    state.selectedDateMillis?.let { date = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate(); dueTime = null }
                    picking = false
                })
            },
            dismissButton = { MarksyButton("Cancel", onClick = { picking = false }, style = MarksyButtonStyle.Text) }
        ) { DatePicker(state) }
    }
}
