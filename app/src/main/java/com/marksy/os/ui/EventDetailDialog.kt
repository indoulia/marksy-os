package com.marksy.os.ui

import java.time.ZoneId
import java.time.Instant
import com.marksy.os.MarksyFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.marksy.os.notification.ReminderTimes
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.marksy.os.data.ActionRepository
import com.marksy.os.data.local.ContextEntity
import com.marksy.os.data.local.NotificationEventEntity
import com.marksy.os.intelligence.ActionEngine
import com.marksy.os.notification.OriginalAppLauncher

/** Thin right-edge scrollbar, drawn only when the content actually overflows. */
private fun Modifier.scrollbar(state: ScrollState): Modifier = drawWithContent {
    drawContent()
    val max = state.maxValue
    if (max <= 0 || max == Int.MAX_VALUE) return@drawWithContent
    val viewport = size.height
    val thumb = (viewport * viewport / (viewport + max)).coerceAtLeast(24.dp.toPx())
    val top = (viewport - thumb) * state.value / max
    drawRoundRect(
        color = MarksyTheme.PrimaryEmerald.copy(alpha = 0.7f),
        topLeft = Offset(size.width - 3.dp.toPx(), top),
        size = Size(3.dp.toPx(), thumb),
        cornerRadius = CornerRadius(1.5.dp.toPx())
    )
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun EventDetailDialog(
    event: NotificationEventEntity,
    onArchive: () -> Unit,
    onUnarchive: () -> Unit,
    onDismiss: () -> Unit,
    onToggleKeep: () -> Unit = {},
    onSetReminder: (Long?) -> Unit = {},
    onMarkUnread: () -> Unit = {},
    engineActions: List<ActionEngine.Available> = emptyList(),
    actionMessage: String? = null,
    onAction: (ActionEngine.Type, Long?, String?) -> Unit = { _, _, _ -> },
    related: List<ContextEntity> = emptyList(),
    onUnlinkEntity: (Long) -> Unit = {},
    onOpenStock: ((String) -> Unit)? = null,
) {
    var showReminderOptions by remember { mutableStateOf(false) }
    var reporting by remember(event.id) { mutableStateOf(false) }
    val scrollState = rememberScrollState()
    val context = LocalContext.current
    val notice = rememberNotice()
    val actions = OriginalAppLauncher.actionsFor(event.sourcePackage, event.sourceKey)
    val isSymbol = rememberSymbolCheck()
    val stocks = remember(event.id, isSymbol) { if (onOpenStock == null) emptyList() else stocksIn(event, isSymbol) }
    val details = buildList {
        add("Category" to event.category.lowercase().replaceFirstChar { it.uppercase() })
        add("Priority" to event.priority.toString())
        add("Classifier" to "${(event.confidence * 100).toInt()}%")
        if (event.isTrading) {
            add("Delivery" to event.deliveryState.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() })
            event.insightConfidence?.let { add("Marksy conf." to "${(it * 100).toInt()}%") }
        }
    }
    MarksyDialog(onDismissRequest = onDismiss, confirmButton = {}, text = {
        Column {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f).padding(top = 4.dp)) {
                    Text(
                        event.title.ifBlank { "Notification event" },
                        color = MarksyTheme.TextPrimary,
                        style = MarksyType.Lead,
                        fontWeight = FontWeight.Bold,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        "${event.sourceName.ifBlank { "System" }} · ${formatTimestamp(event.postedAt)}",
                        color = MarksyTheme.TextMuted,
                        style = MarksyType.Meta,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(Modifier.width(6.dp))
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(MarksyTheme.SurfaceRaised)
                        .border(MarksySpace.Border, MarksyTheme.BorderGlow, CircleShape)
                ) {
                    Icon(Icons.Default.Close, contentDescription = "Close", tint = MarksyTheme.TextPrimary, modifier = Modifier.size(20.dp))
                }
            }
            SectionDivider()
            // Only the body scrolls; title above and details/actions below stay in view.
            Column(Modifier.weight(1f, fill = false).scrollbar(scrollState).padding(end = 10.dp).verticalScroll(scrollState)) {
                Text(linkified(event.body.ifBlank { "No notification body was captured." }), color = MarksyTheme.TextSecondary, style = MarksyType.Body)
                if (actions.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        actions.forEach { action ->
                            MarksyButton(
                                action.title,
                                style = MarksyButtonStyle.Outlined,
                                onClick = {
                                    if (!OriginalAppLauncher.send(context, action.intent)) {
                                        notice("\"${action.title}\" is no longer available")
                                    }
                                }
                            )
                        }
                    }
                }
            }
            SectionDivider()
            Column(Modifier.padding(end = 6.dp)) {
                details.chunked(3).forEach { row ->
                    Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        row.forEach { (label, value) -> DetailCell(label, value, Modifier.weight(1f)) }
                        repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
                if (event.isTrading) {
                    // Rows stored before the tips client stopped writing JSON nulls as "null".
                    event.insightSummary?.removeSuffix(" | null")?.takeIf { it.isNotBlank() }?.let { DetailCell("Marksy", it, Modifier.fillMaxWidth().padding(bottom = 6.dp), singleLine = false) }
                    event.insightAction?.takeIf { it.isNotBlank() && it != "null" }?.let { DetailCell("Action", it, Modifier.fillMaxWidth().padding(bottom = 6.dp), singleLine = false) }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(bottom = 6.dp)) {
                    Pill(
                        if (event.kept) "Kept" else "Keep",
                        selected = event.kept,
                        onClick = onToggleKeep
                    )
                    Pill(
                        event.remindAt?.let { "Remind " + formatTimestamp(it) } ?: "Remind me",
                        selected = event.remindAt != null,
                        onClick = { showReminderOptions = !showReminderOptions }
                    )
                    Pill("Mark unread", selected = false) { onMarkUnread(); onDismiss() }
                    stocks.forEach { symbol -> Pill(symbol, selected = false) { onOpenStock?.invoke(symbol) } }
                }
                if (showReminderOptions) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(bottom = 6.dp)) {
                        ReminderTimes.options().forEach { option ->
                            Pill(option.label, selected = false) {
                                onSetReminder(option.atMillis)
                                showReminderOptions = false
                            }
                        }
                        if (event.remindAt != null) {
                            Pill("Cancel reminder", selected = false) {
                                onSetReminder(null)
                                showReminderOptions = false
                            }
                        }
                    }
                }
                Text(
                    when {
                        event.kept -> "Kept forever on this device"
                        event.archived -> "Archived locally on this device"
                        else -> "Stored locally on this device"
                    },
                    color = MarksyTheme.TextMuted,
                    style = MarksyType.Meta
                )
            }
            if (related.isNotEmpty()) {
                // EPIC-015 context graph: entities this event is linked to, with a correction.
                Text("Related", color = MarksyTheme.TextMuted, style = MarksyType.Meta)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(bottom = 6.dp)) {
                    related.forEach { r ->
                        val label = r.displayName + if (r.sourceCount > 1) " · ${r.sourceCount} apps" else ""
                        Pill(label, selected = false) { onUnlinkEntity(r.id) }
                    }
                }
            }
            // EPIC-014 actions not already covered by the Open / Remind / Keep controls above.
            val extra = engineActions.filter { it.enabled && it.type !in COVERED_BY_DIALOG }
            if (extra.isNotEmpty()) {
                actionMessage?.let { Text(it, color = MarksyTheme.PrimaryEmerald, style = MarksyType.Meta) }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(bottom = 6.dp)) {
                    extra.forEach { a ->
                        if (a.type == ActionEngine.Type.REPORT) {
                            Pill(a.type.label, selected = reporting) { reporting = !reporting }
                        } else {
                            Pill(a.type.label, selected = false) { onAction(a.type, null, null) }
                        }
                    }
                }
                if (reporting) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(bottom = 6.dp)) {
                    ActionRepository.CATEGORIES.filter { it != event.category && it != "TRADING" }.forEach { c ->
                        Pill(c.lowercase(), selected = false) { reporting = false; onAction(ActionEngine.Type.REPORT, null, c) }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth().padding(end = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MarksyButton(
                    if (event.archived) "Restore" else "Archive",
                    onClick = if (event.archived) onUnarchive else onArchive,
                    style = MarksyButtonStyle.Outlined,
                    modifier = Modifier.weight(1f)
                )
                MarksyButton(
                    "Open ${event.sourceName.ifBlank { "app" }}",
                    onClick = {
                        if (OriginalAppLauncher.open(context, event.sourcePackage, event.sourceKey)) onDismiss()
                        else notice("${event.sourceName} can't be opened")
                    },
                    modifier = Modifier.weight(1.4f)
                )
            }
        }
    })
}

private val UrlPattern = Regex("""(https?://|www\.)[^\s<>"]+""", RegexOption.IGNORE_CASE)

/** Makes web links in the captured body tappable. */
private fun linkified(text: String): AnnotatedString = buildAnnotatedString {
    var last = 0
    UrlPattern.findAll(text).forEach { match ->
        append(text.substring(last, match.range.first))
        val url = match.value.trimEnd('.', ',', ')', ';', '!', '?')
        val target = if (url.startsWith("www.", ignoreCase = true)) "https://$url" else url
        withLink(LinkAnnotation.Url(target, TextLinkStyles(SpanStyle(color = MarksyTheme.PrimaryEmerald, textDecoration = TextDecoration.Underline)))) { append(url) }
        last = match.range.first + url.length
    }
    append(text.substring(last))
}

@Composable
private fun SectionDivider() {
    MarksyDivider(Modifier.padding(end = 6.dp, top = 8.dp, bottom = 8.dp))
}

@Composable
private fun DetailCell(label: String, value: String, modifier: Modifier = Modifier, singleLine: Boolean = true) {
    Column(
        modifier
            .clip(MarksyShape.Chip)
            .background(MarksyTheme.SurfaceRaised)
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(label, color = MarksyTheme.TextMuted, style = MarksyType.Caption, maxLines = 1)
        Text(
            value,
            color = MarksyTheme.TextPrimary,
            style = MarksyType.Small,
            fontWeight = FontWeight.Medium,
            maxLines = if (singleLine) 1 else Int.MAX_VALUE,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** Open-in-app and reminders already have dedicated controls in this dialog (keep/remind/open). */
private val COVERED_BY_DIALOG = setOf(ActionEngine.Type.OPEN_SOURCE, ActionEngine.Type.REMIND)

private fun formatTimestamp(timestamp: Long): String =
    MarksyFormat.dayTime(Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()))
