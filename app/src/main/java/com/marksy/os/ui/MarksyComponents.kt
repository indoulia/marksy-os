package com.marksy.os.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.ui.semantics.Role
import androidx.compose.material3.Switch
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenu
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.animation.animateColorAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import kotlinx.coroutines.launch

/*
 * Marksy's shared building blocks. Screens compose these with MarksyTheme colours, MarksyType, MarksyShape and
 * MarksySpace instead of styling their own. Also shared: EmptyState (MainActivity.kt),
 * MarksyDialog (MarksyTheme.kt), MarksySearchField and the floating stack (OneHandControls.kt), CompactTextField and SelectField,
 * MarksyLoader, and MarksyFormat for numbers and dates.
 */

/** The card surface: Surface fill (or a tinted one for a flagged row), 1dp border, Card corners. */
fun Modifier.marksyCard(border: Color = MarksyTheme.BorderGlow, fill: Color = MarksyTheme.Surface): Modifier =
    clip(MarksyShape.Card).background(fill).border(MarksySpace.Border, border, MarksyShape.Card)

/** A content card. Every card has the same padding and line gap; screens never pass their own. */
@Composable
fun MarksyCard(
    modifier: Modifier = Modifier,
    border: Color = MarksyTheme.BorderGlow,
    fill: Color = MarksyTheme.Surface,
    onClick: (() -> Unit)? = null,
    onClickLabel: String? = null,
    onLongClick: (() -> Unit)? = null,
    onLongClickLabel: String? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier.fillMaxWidth().marksyCard(border, fill).tappable(onClick, onClickLabel, onLongClick, onLongClickLabel).padding(MarksySpace.CardPadding),
        verticalArrangement = Arrangement.spacedBy(MarksySpace.Inner),
        content = content
    )
}

/** One list row drawn as a card (a tip, a holding, a call): tighter top and bottom than a content card. */
@Composable
fun MarksyRowCard(
    modifier: Modifier = Modifier,
    border: Color = MarksyTheme.BorderGlow,
    fill: Color = MarksyTheme.Surface,
    onClick: (() -> Unit)? = null,
    onClickLabel: String? = null,
    onLongClick: (() -> Unit)? = null,
    onLongClickLabel: String? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier.fillMaxWidth().marksyCard(border, fill).tappable(onClick, onClickLabel, onLongClick, onLongClickLabel)
            .padding(horizontal = MarksySpace.CardPadding, vertical = MarksySpace.ListGap),
        content = content
    )
}

/** Several rows in one card (settings, health checks), separated by MarksyDivider; rows carry no padding of their own. */
@Composable
fun MarksyGroupCard(modifier: Modifier = Modifier, border: Color = MarksyTheme.BorderGlow, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.fillMaxWidth().marksyCard(border).padding(horizontal = MarksySpace.CardPadding, vertical = MarksySpace.Tight),
        content = content
    )
}

/** A small figure with its label (hit rate, average return, P&L); sits in a row of equal stats. */
@Composable
fun MarksyStat(label: String, value: String, modifier: Modifier = Modifier, valueColor: Color = MarksyTheme.TextPrimary, note: String? = null) {
    Column(
        modifier.marksyCard().padding(horizontal = MarksySpace.CardPadding, vertical = MarksySpace.Gap),
        verticalArrangement = Arrangement.spacedBy(MarksySpace.Hair)
    ) {
        Text(label, color = MarksyTheme.TextMuted, style = MarksyType.Meta, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(value, color = valueColor, style = MarksyType.Subhead, maxLines = 1, overflow = TextOverflow.Ellipsis)
        note?.let { Text(it, color = MarksyTheme.TextMuted, style = MarksyType.Caption, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }
}

/** Equal-width stats side by side. */
@Composable
fun MarksyStatRow(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(MarksySpace.Gap), content = content)
}

/** A page's scrolling list: page margin, list gap and top inset are fixed; [bottom] clears the floating buttons. */
@Composable
fun MarksyList(
    modifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState(),
    bottom: Dp = OneHandListBottomPadding,
    content: LazyListScope.() -> Unit
) {
    LazyColumn(
        modifier.fillMaxSize(),
        state = state,
        contentPadding = PaddingValues(start = MarksySpace.Gutter, end = MarksySpace.Gutter, top = MarksySpace.ListGap, bottom = bottom),
        verticalArrangement = Arrangement.spacedBy(MarksySpace.ListGap),
        content = content
    )
}

/** One row inside a MarksyGroupCard: full width, the standard row padding, optional tap and long-press. */
fun Modifier.marksyRow(
    onClick: (() -> Unit)? = null, onClickLabel: String? = null, onLongClick: (() -> Unit)? = null, onLongClickLabel: String? = null
): Modifier = fillMaxWidth().tappable(onClick, onClickLabel, onLongClick, onLongClickLabel).padding(vertical = MarksySpace.Gap)

@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.tappable(onClick: (() -> Unit)?, label: String?, onLongClick: (() -> Unit)? = null, longLabel: String? = null): Modifier = when {
    onLongClick != null -> combinedClickable(onClickLabel = label, onLongClickLabel = longLabel, onLongClick = onLongClick, onClick = onClick ?: {})
    onClick != null -> clickable(onClickLabel = label, onClick = onClick)
    else -> this
}

/** The one card title: optional icon, Subhead title, optional trailing value or action at the end. */
@Composable
fun MarksyCardHeader(
    title: String, modifier: Modifier = Modifier, icon: ImageVector? = null, iconTint: Color = MarksyTheme.PrimaryEmerald,
    titleColor: Color = MarksyTheme.TextPrimary, trailing: (@Composable () -> Unit)? = null
) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(MarksySpace.Gap)) {
        icon?.let { Icon(it, contentDescription = null, tint = iconTint, modifier = Modifier.size(MarksySize.Icon)) }
        Text(title, color = titleColor, style = MarksyType.Subhead, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        trailing?.invoke()
    }
}

/** Section or lane heading: optional dot, caps label, optional count, a rule to the edge, and an optional trailing value. */
@Composable
fun SectionLabel(
    text: String, count: Int? = null, dot: Color? = null, rule: Boolean = true, modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null
) {
    Row(modifier.fillMaxWidth().padding(top = MarksySpace.Inner), verticalAlignment = Alignment.CenterVertically) {
        dot?.let {
            Box(Modifier.size(MarksySize.Dot).clip(CircleShape).background(it))
            Spacer(Modifier.width(MarksySpace.Gap))
        }
        Text(text.uppercase(), color = MarksyTheme.TextSecondary, style = MarksyType.Label)
        count?.let {
            Spacer(Modifier.width(MarksySpace.Inner))
            Text("$it", color = MarksyTheme.TextMuted, style = MarksyType.Meta, fontWeight = FontWeight.Bold)
        }
        if (rule) {
            Spacer(Modifier.width(MarksySpace.Gap))
            MarksyDivider(Modifier.weight(1f))
        } else if (trailing != null) Spacer(Modifier.weight(1f))
        trailing?.let {
            Spacer(Modifier.width(MarksySpace.Gap))
            it()
        }
    }
}

/** Small tag on a row (SME, LIVE, a severity): accent text, and optionally an icon, on its tinted background. */
@Composable
fun MarksyBadge(text: String, color: Color, background: Color, modifier: Modifier = Modifier, icon: ImageVector? = null) {
    Row(modifier.clip(MarksyShape.Badge).background(background).padding(horizontal = MarksySpace.Tight, vertical = MarksySpace.Border), verticalAlignment = Alignment.CenterVertically) {
        icon?.let {
            Icon(it, contentDescription = null, tint = color, modifier = Modifier.size(MarksySize.IconTiny))
            Spacer(Modifier.width(MarksySpace.Hair))
        }
        Text(text, color = color, style = MarksyType.Caption, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

/**
 * A status capsule on a row (a return, a score, a fact): [content] on its tinted [background]; tappable when [onClick]
 * is set. Bigger than [MarksyBadge], smaller than a [Pill].
 */
@Composable
fun MarksyChip(
    background: Color, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, onClickLabel: String? = null,
    content: @Composable RowScope.() -> Unit
) {
    Row(
        modifier.clip(MarksyShape.Chip).background(background)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClickLabel = onClickLabel, onClick = onClick) else Modifier)
            .padding(horizontal = MarksySpace.Gap, vertical = MarksySpace.Hair),
        verticalAlignment = Alignment.CenterVertically,
        content = content
    )
}

/** An icon on a round tile in its source colours (a category, a message source, the profile). */
@Composable
fun MarksyIconDisc(
    icon: ImageVector, tint: Color, background: Color, modifier: Modifier = Modifier, size: Dp = MarksySize.IconLarge,
    iconSize: Dp = MarksySize.IconSmall, contentDescription: String? = null
) {
    Box(modifier.size(size).clip(CircleShape).background(background), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = contentDescription, tint = tint, modifier = Modifier.size(iconSize))
    }
}

/**
 * The one icon button: a [MarksySize.Control] circle, or [MarksySize.ControlInline] when [inline] beside text. [raised]
 * draws it on the raised surface with a border (filled emerald while [active]).
 */
@Composable
fun MarksyIconButton(
    icon: ImageVector, contentDescription: String?, onClick: () -> Unit, modifier: Modifier = Modifier,
    tint: Color = MarksyTheme.PrimaryEmerald, raised: Boolean = false, active: Boolean = false, enabled: Boolean = true, inline: Boolean = false
) {
    Box(
        modifier.size(if (inline) MarksySize.ControlInline else MarksySize.Control).clip(CircleShape)
            .then(
                if (raised) Modifier.background(if (active) MarksyTheme.PrimaryEmerald else MarksyTheme.SurfaceRaised).border(MarksySpace.Border, MarksyTheme.BorderGlow, CircleShape)
                else Modifier
            )
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            icon, contentDescription = contentDescription, modifier = Modifier.size(MarksySize.Icon),
            tint = when { !enabled -> MarksyTheme.TextMuted; active -> MarksyTheme.OnAccent; else -> tint }
        )
    }
}

/** The one on/off switch: a [MarksySize.Control] tall track. */
@Composable
fun MarksySwitch(checked: Boolean, onCheckedChange: ((Boolean) -> Unit)?, modifier: Modifier = Modifier, enabled: Boolean = true) {
    // Colours come from MarksyMaterialTheme; the height drops Material's 48dp touch box.
    Switch(checked = checked, onCheckedChange = onCheckedChange, modifier = modifier.height(MarksySize.Control), enabled = enabled)
}

/** The one dropdown: a raised panel with the Marksy border and shadow, items in Body type. */
@Composable
fun MarksyDropdownMenu(expanded: Boolean, onDismissRequest: () -> Unit, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    DropdownMenu(
        expanded = expanded, onDismissRequest = onDismissRequest, modifier = modifier, shape = MarksyShape.Panel,
        containerColor = MarksyTheme.SurfaceRaised, shadowElevation = MarksyElevation.Menu,
        border = BorderStroke(MarksySpace.Border, MarksyTheme.BorderGlow), content = content
    )
}

@Composable
fun MarksyMenuItem(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    DropdownMenuItem(
        text = { Text(text, color = MarksyTheme.TextPrimary, style = MarksyType.Body) }, onClick = onClick,
        modifier = modifier.height(MarksySize.Control), contentPadding = PaddingValues(horizontal = MarksySpace.Control)
    )
}

/** A strip pinned above a page's list (section tabs, filters): page background and margins, so the list scrolls under it. */
@Composable
fun MarksyStickyBar(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.fillMaxWidth().background(MarksyTheme.Background)
            .padding(start = MarksySpace.Gutter, end = MarksySpace.Gutter, top = MarksySpace.Tight, bottom = MarksySpace.Gap),
        verticalArrangement = Arrangement.spacedBy(MarksySpace.Inner),
        content = content
    )
}

/** Round initials avatar (IPO issuers, senders). */
@Composable
fun MarksyAvatar(initials: String, size: Dp = MarksySize.Avatar, color: Color = MarksyTheme.PrimaryEmerald, modifier: Modifier = Modifier) {
    Box(
        modifier.size(size).clip(CircleShape).background(MarksyTheme.SurfaceRaised).border(MarksySpace.Border, MarksyTheme.BorderGlow, CircleShape),
        contentAlignment = Alignment.Center
    ) { Text(initials, color = color, style = MarksyType.initials(size)) }
}

/**
 * One choice from a few (Buy/Sell, a chart range, a pivot method): equal segments on a Pill-shaped track; the chosen
 * one fills with [color] for that option.
 */
@Composable
fun MarksySegmented(
    options: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: (String) -> String = { it },
    color: (String) -> Color = { MarksyTheme.PrimaryEmerald },
    count: (String) -> Int? = { null }
) {
    // Wraps its labels (equal segments) unless the caller gives it a width.
    Row(
        modifier.width(IntrinsicSize.Max).height(MarksySize.Control).clip(MarksyShape.Pill).background(MarksyTheme.SurfaceRaised)
            .border(MarksySpace.Border, MarksyTheme.BorderGlow, MarksyShape.Pill).padding(MarksySpace.Hair),
        horizontalArrangement = Arrangement.spacedBy(MarksySpace.Hair)
    ) {
        options.forEach { option ->
            val on = option == selected
            Row(
                Modifier.weight(1f).fillMaxHeight().clip(MarksyShape.Pill).background(if (on) color(option) else Color.Transparent)
                    .clickable { onSelect(option) }.padding(horizontal = MarksySpace.Control),
                horizontalArrangement = Arrangement.spacedBy(MarksySpace.Inner, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    label(option), color = if (on) MarksyTheme.OnAccent else MarksyTheme.TextSecondary, style = MarksyType.Small,
                    fontWeight = if (on) FontWeight.Bold else FontWeight.Medium, textAlign = TextAlign.Center, maxLines = 1
                )
                count(option)?.let { CountBadge(it, on) }
            }
        }
    }
}

/** A count beside a tab or segment label; [on] when it sits on the selected (accent) fill. */
@Composable
fun CountBadge(count: Int, on: Boolean) {
    Text(
        "$count", color = if (on) MarksyTheme.PrimaryEmerald else MarksyTheme.TextSecondary, style = MarksyType.Caption, fontWeight = FontWeight.SemiBold, maxLines = 1,
        modifier = Modifier.clip(MarksyShape.Pill).background(if (on) MarksyTheme.Background else MarksyTheme.Divider).padding(horizontal = MarksySpace.Inner, vertical = MarksySpace.Border)
    )
}

@Composable
fun MarksyDivider(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(MarksySpace.Border).background(MarksyTheme.Divider))
}

enum class MarksyButtonStyle { Filled, Outlined, Text }

/** The one button: Filled for a card's main action, Outlined for a secondary one, Text inside dialogs and rows. */
@Composable
fun MarksyButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: MarksyButtonStyle = MarksyButtonStyle.Filled,
    color: Color = MarksyTheme.PrimaryEmerald,
    enabled: Boolean = true,
    icon: ImageVector? = null
) {
    val padding = PaddingValues(horizontal = MarksySpace.Control)
    val label: @Composable () -> Unit = {
        icon?.let {
            Icon(it, contentDescription = null, modifier = Modifier.size(MarksySize.IconSmall))
            Spacer(Modifier.width(MarksySpace.Inner))
        }
        Text(text, style = MarksyType.Small, fontWeight = FontWeight.SemiBold)
    }
    // A fixed height: Material buttons otherwise reserve a 48dp touch box around the drawn one.
    val sized = modifier.height(MarksySize.Control)
    when (style) {
        MarksyButtonStyle.Filled -> Button(
            onClick, sized, enabled, shape = MarksyShape.Pill, contentPadding = padding,
            colors = ButtonDefaults.buttonColors(
                containerColor = color, contentColor = MarksyTheme.OnAccent,
                disabledContainerColor = MarksyTheme.SurfaceRaised, disabledContentColor = MarksyTheme.TextMuted
            )
        ) { label() }
        MarksyButtonStyle.Outlined -> OutlinedButton(
            onClick, sized, enabled, shape = MarksyShape.Pill, contentPadding = padding,
            border = BorderStroke(MarksySpace.Border, if (enabled) color else MarksyTheme.BorderGlow),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = color, disabledContentColor = MarksyTheme.TextMuted)
        ) { label() }
        MarksyButtonStyle.Text -> TextButton(
            onClick, sized, enabled, shape = MarksyShape.Pill, contentPadding = padding,
            colors = ButtonDefaults.textButtonColors(contentColor = color, disabledContentColor = MarksyTheme.TextMuted)
        ) { label() }
    }
}

/**
 * Marksy pill: emerald when selected, muted when unavailable. [leading] sits before the text (an icon, a source disc)
 * and [count] after it; [compact] is the small variant for dense rows.
 */
@Composable
internal fun Pill(
    text: String, selected: Boolean = false, enabled: Boolean = true, compact: Boolean = false, modifier: Modifier = Modifier,
    leading: (@Composable () -> Unit)? = null, count: Int? = null, onClick: () -> Unit
) {
    val shape = MarksyShape.Pill
    val fill by animateColorAsState(if (selected) MarksyTheme.PrimaryEmerald else MarksyTheme.SurfaceRaised, label = "pill")
    Row(
        // Min height, not fixed, so large font scales grow the pill instead of clipping it.
        modifier.heightIn(min = if (compact) MarksySize.ControlCompact else MarksySize.Control)
            .clip(shape)
            .background(fill)
            .border(MarksySpace.Border, if (selected) MarksyTheme.PrimaryEmerald else MarksyTheme.BorderGlow, shape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = if (compact) MarksySpace.ControlCompact else MarksySpace.Control),
        horizontalArrangement = Arrangement.spacedBy(MarksySpace.Inner, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically
    ) {
        leading?.invoke()
        Text(
            text,
            color = when { selected -> MarksyTheme.OnAccent; enabled -> MarksyTheme.TextPrimary; else -> MarksyTheme.TextMuted },
            style = if (compact) MarksyType.Caption else MarksyType.Small,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            maxLines = 1
        )
        count?.let { CountBadge(it, selected) }
    }
}

/** A one-line "nothing here" inside a card or section; a whole empty page uses EmptyState. */
@Composable
fun InlineEmpty(text: String, modifier: Modifier = Modifier) {
    Text(text, color = MarksyTheme.TextMuted, style = MarksyType.Small, textAlign = TextAlign.Center, modifier = modifier.fillMaxWidth().padding(vertical = MarksySpace.CardPadding))
}

/** MainActivity's snackbar host; null outside it (other activities, tests). */
val LocalMarksySnackbar = staticCompositionLocalOf<SnackbarHostState?> { null }

/** The app's one feedback line: a snackbar, or a toast where no snackbar host exists. */
@Composable
fun rememberNotice(): (String) -> Unit {
    val host = LocalMarksySnackbar.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    return remember(host, context, scope) {
        { text -> if (host != null) scope.launch { host.showSnackbar(text, duration = SnackbarDuration.Short) } else Toast.makeText(context, text, Toast.LENGTH_SHORT).show() }
    }
}
