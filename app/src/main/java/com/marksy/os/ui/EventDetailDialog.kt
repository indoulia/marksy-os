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
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.AlarmOff
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.MarkEmailUnread
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.ui.graphics.vector.ImageVector
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
    /** A teaser or hidden-content notification whose tip the user can open or capture (EPIC-039). */
    onViewTip: (() -> Unit)? = null,
    onCaptureTip: (() -> Unit)? = null,
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
        add("Classifier" to MarksyFormat.percent(event.confidence * 100.0, 0, signed = false))
        if (event.isTrading) {
            add("Delivery" to event.deliveryState.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() })
            event.insightConfidence?.let { add("Marksy conf." to MarksyFormat.percent(it * 100.0, 0, signed = false)) }
        }
    }
    MarksyDialog(onDismissRequest = onDismiss, confirmButton = {}, text = {
        Column(verticalArrangement = Arrangement.spacedBy(MarksySpace.Gap)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
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
                Spacer(Modifier.width(MarksySpace.Inner))
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .size(MarksySize.Avatar)
                        .clip(CircleShape)
                        .background(MarksyTheme.SurfaceRaised)
                        .border(MarksySpace.Border, MarksyTheme.BorderGlow, CircleShape)
                ) {
                    Icon(Icons.Default.Close, contentDescription = "Close", tint = MarksyTheme.TextPrimary, modifier = Modifier.size(MarksySize.Icon))
                }
            }
            // Only the middle scrolls; the title above and Archive/Open below stay in view.
            Column(Modifier.weight(1f, fill = false).scrollbar(scrollState).verticalScroll(scrollState), verticalArrangement = Arrangement.spacedBy(MarksySpace.ListGap)) {
                MarksyCard {
                    Text(linkified(event.body.ifBlank { "No notification body was captured." }), color = MarksyTheme.TextSecondary, style = MarksyType.Body)
                    if (actions.isNotEmpty()) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(MarksySpace.Inner), verticalArrangement = Arrangement.spacedBy(MarksySpace.Inner)) {
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
                SectionLabel("Details")
                details.chunked(3).forEach { row ->
                    MarksyStatRow {
                        row.forEach { (label, value) -> MarksyStat(label, value, Modifier.weight(1f)) }
                        repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
                if (event.isTrading) {
                    // Rows stored before the tips client stopped writing JSON nulls as "null".
                    val insight = listOfNotNull(
                        event.insightSummary?.removeSuffix(" | null")?.takeIf { it.isNotBlank() }?.let { "Marksy" to it },
                        event.insightAction?.takeIf { it.isNotBlank() && it != "null" }?.let { "Action" to it }
                    )
                    if (insight.isNotEmpty()) MarksyGroupCard {
                        insight.forEachIndexed { i, (label, value) ->
                            if (i > 0) MarksyDivider()
                            Column(Modifier.padding(vertical = MarksySpace.Gap), verticalArrangement = Arrangement.spacedBy(MarksySpace.Hair)) {
                                Text(label, color = MarksyTheme.TextMuted, style = MarksyType.Caption)
                                Text(value, color = MarksyTheme.TextPrimary, style = MarksyType.Small, fontWeight = FontWeight.Medium)
                            }
                        }
                    }
                }
                SectionLabel("Actions")
                MarksyCard {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(MarksySpace.Inner), verticalArrangement = Arrangement.spacedBy(MarksySpace.Inner)) {
                        onViewTip?.let { view -> ChipButton("View tip", Icons.Default.OpenInNew) { view(); onDismiss() } }
                        onCaptureTip?.let { capture -> ChipButton("Capture tip", Icons.Default.CameraAlt, selected = true) { capture(); onDismiss() } }
                        ChipButton(
                            if (event.kept) "Kept" else "Keep",
                            if (event.kept) Icons.Default.Star else Icons.Default.StarBorder,
                            selected = event.kept,
                            onClick = onToggleKeep
                        )
                        ChipButton(
                            event.remindAt?.let { "Remind " + formatTimestamp(it) } ?: "Remind me",
                            Icons.Default.Alarm,
                            selected = event.remindAt != null,
                            onClick = { showReminderOptions = !showReminderOptions }
                        )
                        ChipButton("Mark unread", Icons.Default.MarkEmailUnread) { onMarkUnread(); onDismiss() }
                        stocks.forEach { symbol -> ChipButton(symbol, Icons.Default.ShowChart) { onOpenStock?.invoke(symbol) } }
                    }
                    if (showReminderOptions) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(MarksySpace.Inner), verticalArrangement = Arrangement.spacedBy(MarksySpace.Inner)) {
                            ReminderTimes.options().forEach { option ->
                                ChipButton(option.label, Icons.Default.Schedule) {
                                    onSetReminder(option.atMillis)
                                    showReminderOptions = false
                                }
                            }
                            if (event.remindAt != null) {
                                ChipButton("Cancel reminder", Icons.Default.AlarmOff) {
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
                    SectionLabel("Related")
                    MarksyCard {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(MarksySpace.Inner), verticalArrangement = Arrangement.spacedBy(MarksySpace.Inner)) {
                            related.forEach { r ->
                                val label = r.displayName + if (r.sourceCount > 1) " · ${r.sourceCount} apps" else ""
                                ChipButton(label, Icons.Default.Close) { onUnlinkEntity(r.id) }
                            }
                        }
                    }
                }
                // EPIC-014 actions not already covered by the Open / Remind / Keep controls above.
                val extra = engineActions.filter { it.enabled && it.type !in COVERED_BY_DIALOG }
                if (extra.isNotEmpty()) {
                    MarksyCard {
                        actionMessage?.let { Text(it, color = MarksyTheme.PrimaryEmerald, style = MarksyType.Meta) }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(MarksySpace.Inner), verticalArrangement = Arrangement.spacedBy(MarksySpace.Inner)) {
                            extra.forEach { a ->
                                if (a.type == ActionEngine.Type.REPORT) {
                                    ChipButton(a.type.label, Icons.Default.Schedule, selected = reporting) { reporting = !reporting }
                                } else {
                                    ChipButton(a.type.label, Icons.Default.OpenInNew) { onAction(a.type, null, null) }
                                }
                            }
                        }
                        if (reporting) FlowRow(horizontalArrangement = Arrangement.spacedBy(MarksySpace.Inner), verticalArrangement = Arrangement.spacedBy(MarksySpace.Inner)) {
                            ActionRepository.CATEGORIES.filter { it != event.category && it != "TRADING" }.forEach { c ->
                                ChipButton(c.lowercase(), Icons.Default.Schedule) { reporting = false; onAction(ActionEngine.Type.REPORT, null, c) }
                            }
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(MarksySpace.Gap)) {
                MarksyButton(
                    if (event.archived) "Restore" else "Archive",
                    onClick = if (event.archived) onUnarchive else onArchive,
                    style = MarksyButtonStyle.Outlined,
                    icon = if (event.archived) Icons.Default.Unarchive else Icons.Default.Archive,
                    modifier = Modifier.weight(1f)
                )
                MarksyButton(
                    "Open ${event.sourceName.ifBlank { "app" }}",
                    onClick = {
                        if (OriginalAppLauncher.open(context, event.sourcePackage, event.sourceKey)) onDismiss()
                        else notice("${event.sourceName} can't be opened")
                    },
                    icon = Icons.Default.OpenInNew,
                    modifier = Modifier.weight(1.4f)
                )
            }
        }
    })
}

/** An action chip: icon plus label on the Marksy button; the chosen one fills. */
@Composable
private fun ChipButton(text: String, icon: ImageVector, selected: Boolean = false, onClick: () -> Unit) =
    MarksyButton(text, onClick, style = if (selected) MarksyButtonStyle.Filled else MarksyButtonStyle.Outlined, color = if (selected) MarksyTheme.PrimaryEmerald else MarksyTheme.TextPrimary, icon = icon)

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

/** Open-in-app and reminders already have dedicated controls in this dialog (keep/remind/open). */
private val COVERED_BY_DIALOG = setOf(ActionEngine.Type.OPEN_SOURCE, ActionEngine.Type.REMIND)

private fun formatTimestamp(timestamp: Long): String =
    MarksyFormat.dayTime(Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()))
