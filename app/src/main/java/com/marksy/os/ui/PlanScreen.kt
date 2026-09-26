package com.marksy.os.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.unit.sp
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
            onFilterSelected = onViewSelected,
            actions = listOfNotNull(
                FloatingAction(Icons.Default.Cake, "Import birthdays from contacts", onImportBirthdays).takeIf { !contactsAccess },
                FloatingAction(Icons.Default.Add, "Add reminder", onAdd)
            )
        )
    }
}

@Composable
private fun ReminderList(items: List<PlanItemEntity>, now: Long, onEdit: (PlanItemEntity) -> Unit, onStatus: (PlanItemEntity, PlanStatus) -> Unit) {
    val groups = remember(items, now) { PlanModel.reminders(items, now) }
    var showDone by rememberSaveable { mutableStateOf(false) }
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 18.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(bottom = OneHandListBottomPadding)
    ) {
        if (groups.overdue.isEmpty() && groups.next7.isEmpty() && groups.later.isEmpty()) item {
            EmptyState("No reminders yet.", "Bill, EMI and card due messages become reminders automatically. Tap + to add a bill or birthday.")
        }
        listOf("Overdue" to groups.overdue, "Next 7 days" to groups.next7, "Later" to groups.later).forEach { (label, list) ->
            if (list.isNotEmpty()) {
                item(key = "h-$label") { PlanSectionTitle(label, list.size) }
                items(list, key = { "r-${it.id}" }) { PlanRow(it, now, onEdit, onStatus) }
            }
        }
        if (groups.done.isNotEmpty()) {
            item(key = "h-done") {
                TextButton(onClick = { showDone = !showDone }) {
                    Text((if (showDone) "Hide done" else "Show done") + " · ${groups.done.size}", color = MarksyTheme.TextMuted, fontSize = 12.sp)
                }
            }
            if (showDone) items(groups.done, key = { "d-${it.id}" }) { PlanRow(it, now, onEdit, onStatus) }
        }
    }
}

@Composable
private fun PlanSectionTitle(label: String, count: Int) =
    Text("$label · $count", color = MarksyTheme.TextSecondary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp))

@Composable
private fun PlanRow(item: PlanItemEntity, now: Long, onEdit: (PlanItemEntity) -> Unit, onStatus: (PlanItemEntity, PlanStatus) -> Unit) {
    val done = item.status == PlanStatus.DONE.name
    val critical = PlanRules.isCritical(PlanStatus.valueOf(item.status), item.dueAt, now)
    Card(
        colors = CardDefaults.cardColors(containerColor = if (critical) MarksyTheme.BadgeUrgentBg else MarksyTheme.Surface),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth().border(1.dp, if (critical) MarksyTheme.RedUrgent else MarksyTheme.BorderGlow, RoundedCornerShape(14.dp)).clickable { onEdit(item) }
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            KindBadge(item.kind, critical)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(item.title, color = if (done) MarksyTheme.TextMuted else MarksyTheme.TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val detail = listOfNotNull(PlanText.amount(item.amountMinor), item.counterparty?.takeIf { it !in item.title }).joinToString(" · ")
                if (detail.isNotEmpty()) Text(detail, color = MarksyTheme.TextSecondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                PlanText.dueLabel(item.dueAt, now)?.let {
                    Text(it, color = if (critical) MarksyTheme.RedUrgent else MarksyTheme.TextMuted, fontSize = 11.sp, fontWeight = if (critical) FontWeight.Bold else FontWeight.Normal)
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
        Modifier.size(34.dp).clip(CircleShape).background(if (critical) MarksyTheme.RedUrgent else MarksyTheme.SurfaceRaised),
        contentAlignment = Alignment.Center
    ) {
        Icon(kindIcon(kind), contentDescription = kindLabel(kind), tint = if (critical) Color.White else MarksyTheme.PrimaryEmerald, modifier = Modifier.size(18.dp))
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
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 10.dp)
    ) {
        items(PlanStatus.entries, key = { it.name }) { status ->
            val column = remember(items, status) { PlanModel.column(items, status) }
            Column(
                Modifier.width(272.dp).fillMaxHeight().clip(RoundedCornerShape(16.dp)).background(MarksyTheme.Surface)
                    .border(1.dp, MarksyTheme.BorderGlow, RoundedCornerShape(16.dp)).padding(10.dp)
            ) {
                Text("${status.label} · ${column.size}", color = MarksyTheme.PrimaryEmerald, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = OneHandListBottomPadding), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (column.isEmpty()) Text("Nothing here", color = MarksyTheme.TextMuted, fontSize = 12.sp)
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
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                .background(if (critical) MarksyTheme.BadgeUrgentBg else MarksyTheme.SurfaceRaised)
                .border(1.dp, if (critical) MarksyTheme.RedUrgent else MarksyTheme.BorderGlow, RoundedCornerShape(12.dp))
                .clickable { menu = true }.padding(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(kindIcon(item.kind), contentDescription = kindLabel(item.kind), tint = if (critical) MarksyTheme.RedUrgent else MarksyTheme.PrimaryEmerald, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(6.dp))
                Text(item.title, color = MarksyTheme.TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            val detail = listOfNotNull(PlanText.dueLabel(item.dueAt, now), PlanText.amount(item.amountMinor)).joinToString(" · ")
            if (detail.isNotEmpty()) Text(detail, color = if (critical) MarksyTheme.RedUrgent else MarksyTheme.TextMuted, fontSize = 11.sp, modifier = Modifier.padding(top = 3.dp))
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
    Card(
        colors = CardDefaults.cardColors(containerColor = MarksyTheme.Surface),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().border(1.dp, MarksyTheme.BorderGlow, RoundedCornerShape(16.dp)).clip(RoundedCornerShape(16.dp)).clickable(onClick = onOpenPlan)
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.EventNote, contentDescription = null, tint = MarksyTheme.PrimaryEmerald, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Upcoming", color = MarksyTheme.TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                }
                Icon(Icons.Default.Add, contentDescription = "Add reminder", tint = MarksyTheme.PrimaryEmerald, modifier = Modifier.size(18.dp).clip(CircleShape).clickable(onClick = onAdd))
            }
            if (upcoming.isEmpty()) {
                Text("No bills, EMIs or birthdays coming up.", color = MarksyTheme.TextMuted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
            }
            upcoming.forEach { item ->
                val critical = PlanRules.isCritical(PlanStatus.valueOf(item.status), item.dueAt, now)
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(kindIcon(item.kind), contentDescription = kindLabel(item.kind), tint = if (critical) MarksyTheme.RedUrgent else MarksyTheme.TextSecondary, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(item.title, color = MarksyTheme.TextPrimary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    PlanText.amount(item.amountMinor)?.let { Text(it, color = MarksyTheme.TextSecondary, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 6.dp)) }
                    Text(
                        PlanText.dueLabel(item.dueAt, now).orEmpty().substringBefore(" · "),
                        color = if (critical) MarksyTheme.RedUrgent else MarksyTheme.TextMuted,
                        fontSize = 12.sp,
                        fontWeight = if (critical) FontWeight.Bold else FontWeight.Normal
                    )
                }
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

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MarksyTheme.SurfaceRaised,
        title = { Text(if (initial == null) "Add reminder" else "Edit", color = MarksyTheme.TextPrimary) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(PlanKind.BILL, PlanKind.EMI, PlanKind.CARD_DUE, PlanKind.BIRTHDAY, PlanKind.TASK).forEach { k ->
                        FilterChip(
                            selected = kind == k,
                            onClick = {
                                kind = k
                                if (initial == null) recurrence = when (k) { PlanKind.BIRTHDAY -> Recurrence.YEARLY; PlanKind.TASK -> Recurrence.NONE; else -> Recurrence.MONTHLY }
                            },
                            label = { Text(k.label, fontSize = 12.sp) }
                        )
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
                OutlinedButton(onClick = { picking = true }) {
                    Icon(Icons.Default.Event, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(date?.let { PlanText.dueLabel(PlanRules.atAlertHour(it, zone), System.currentTimeMillis(), zone) } ?: if (kind == PlanKind.TASK) "Due date (optional)" else "Pick due date")
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Recurrence.entries.forEach { r -> FilterChip(selected = recurrence == r, onClick = { recurrence = r }, label = { Text(r.label, fontSize = 12.sp) }) }
                }
            }
        },
        confirmButton = {
            val valid = title.isNotBlank() && (date != null || kind == PlanKind.TASK)
            TextButton(enabled = valid, onClick = {
                // Keep a follow-up's own time; everything else alerts at the standard hour.
                val due = date?.let { d -> dueTime?.takeIf { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() == d } ?: PlanRules.atAlertHour(d, zone) }
                val saveTitle = if (kind == PlanKind.BIRTHDAY && !title.contains("birthday", ignoreCase = true)) "${title.trim()}'s birthday" else title
                onSave(kind, saveTitle, counterparty.ifBlank { null }, amount.toLongOrNull()?.times(100), due, recurrence)
            }) { Text("Save", color = if (valid) MarksyTheme.PrimaryEmerald else MarksyTheme.TextMuted) }
        },
        dismissButton = {
            Row {
                onDelete?.let { TextButton(onClick = it) { Text("Delete", color = MarksyTheme.RedUrgent) } }
                TextButton(onClick = onDismiss) { Text("Cancel", color = MarksyTheme.TextSecondary) }
            }
        }
    )

    if (picking) {
        val state = rememberDatePickerState(initialSelectedDateMillis = (date ?: LocalDate.now(zone)).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { picking = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { date = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate(); dueTime = null }
                    picking = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { picking = false }) { Text("Cancel") } }
        ) { DatePicker(state) }
    }
}
