package com.marksy.os.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.minimumInteractiveComponentSize
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/*
 * Marksy's shared building blocks. Screens compose these with MarksyTheme colours, MarksyType, MarksyShape and
 * MarksySpace instead of styling their own. Also shared: Pill (WatchlistScreen.kt), EmptyState (MainActivity.kt),
 * MarksyDialog (MarksyTheme.kt), MarksySearchField and the floating stack (OneHandControls.kt), CompactTextField,
 * MarksyLoader, and MarksyFormat for numbers and dates.
 */

/** The card surface: Surface fill (or a tinted one for a flagged row), 1dp border, Card corners. */
fun Modifier.marksyCard(border: Color = MarksyTheme.BorderGlow, fill: Color = MarksyTheme.Surface): Modifier =
    clip(MarksyShape.Card).background(fill).border(MarksySpace.Border, border, MarksyShape.Card)

@Composable
fun MarksyCard(
    modifier: Modifier = Modifier,
    border: Color = MarksyTheme.BorderGlow,
    fill: Color = MarksyTheme.Surface,
    padding: PaddingValues = PaddingValues(MarksySpace.CardPadding),
    spacing: Dp = MarksySpace.Inner,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier.fillMaxWidth().marksyCard(border, fill).let { if (onClick != null) it.clickable(onClick = onClick) else it }.padding(padding),
        verticalArrangement = Arrangement.spacedBy(spacing),
        content = content
    )
}

/** Section or lane heading: optional dot, caps label, optional count, a rule to the edge, and an optional trailing value. */
@Composable
fun SectionLabel(
    text: String, count: Int? = null, dot: Color? = null, rule: Boolean = true, modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null
) {
    Row(modifier.fillMaxWidth().padding(top = MarksySpace.Inner), verticalAlignment = Alignment.CenterVertically) {
        dot?.let {
            Box(Modifier.size(6.dp).clip(CircleShape).background(it))
            Spacer(Modifier.width(8.dp))
        }
        Text(text.uppercase(), color = MarksyTheme.TextSecondary, style = MarksyType.Label)
        count?.let {
            Spacer(Modifier.width(MarksySpace.Inner))
            Text("$it", color = MarksyTheme.TextMuted, style = MarksyType.Meta, fontWeight = FontWeight.Bold)
        }
        if (rule) {
            Spacer(Modifier.width(8.dp))
            MarksyDivider(Modifier.weight(1f))
        } else if (trailing != null) Spacer(Modifier.weight(1f))
        trailing?.let {
            Spacer(Modifier.width(8.dp))
            it()
        }
    }
}

/** Small tag on a row (SME, LIVE, a severity): accent text, and optionally an icon, on its tinted background. */
@Composable
fun MarksyBadge(text: String, color: Color, background: Color, modifier: Modifier = Modifier, icon: ImageVector? = null) {
    Row(modifier.clip(MarksyShape.Badge).background(background).padding(horizontal = 4.dp, vertical = 1.dp), verticalAlignment = Alignment.CenterVertically) {
        icon?.let {
            Icon(it, contentDescription = null, tint = color, modifier = Modifier.size(11.dp))
            Spacer(Modifier.width(3.dp))
        }
        Text(text, color = color, style = MarksyType.Caption, fontWeight = FontWeight.Bold, maxLines = 1)
    }
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
    color: (String) -> Color = { MarksyTheme.PrimaryEmerald }
) {
    Row(
        modifier.clip(MarksyShape.Pill).background(MarksyTheme.SurfaceRaised).border(MarksySpace.Border, MarksyTheme.BorderGlow, MarksyShape.Pill).padding(MarksySpace.Hair),
        horizontalArrangement = Arrangement.spacedBy(MarksySpace.Hair)
    ) {
        options.forEach { option ->
            val on = option == selected
            Text(
                label(option), color = if (on) MarksyTheme.OnAccent else MarksyTheme.TextSecondary, style = MarksyType.Small,
                fontWeight = if (on) FontWeight.Bold else FontWeight.Medium, textAlign = TextAlign.Center, maxLines = 1,
                modifier = Modifier.weight(1f).clip(MarksyShape.Pill).background(if (on) color(option) else Color.Transparent)
                    .clickable { onSelect(option) }.padding(vertical = MarksySpace.Inner)
            )
        }
    }
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
    val padding = PaddingValues(horizontal = 14.dp, vertical = 0.dp)
    val label: @Composable () -> Unit = {
        icon?.let {
            Icon(it, contentDescription = null, modifier = Modifier.size(MarksySize.IconSmall))
            Spacer(Modifier.width(MarksySpace.Inner))
        }
        Text(text, style = MarksyType.Small, fontWeight = FontWeight.SemiBold)
    }
    when (style) {
        MarksyButtonStyle.Filled -> Button(
            onClick, modifier.height(MarksySize.Button), enabled, contentPadding = padding,
            colors = ButtonDefaults.buttonColors(
                containerColor = color, contentColor = MarksyTheme.OnAccent,
                disabledContainerColor = MarksyTheme.SurfaceRaised, disabledContentColor = MarksyTheme.TextMuted
            )
        ) { label() }
        MarksyButtonStyle.Outlined -> OutlinedButton(
            onClick, modifier.height(MarksySize.Button), enabled, contentPadding = padding,
            border = androidx.compose.foundation.BorderStroke(MarksySpace.Border, if (enabled) color else MarksyTheme.BorderGlow),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = color, disabledContentColor = MarksyTheme.TextMuted)
        ) { label() }
        MarksyButtonStyle.Text -> TextButton(
            onClick, modifier, enabled, colors = ButtonDefaults.textButtonColors(contentColor = color, disabledContentColor = MarksyTheme.TextMuted)
        ) { label() }
    }
}

/** Marksy pill (as on the stock fundamentals cards): emerald when selected, muted when unavailable. */
@Composable
internal fun Pill(text: String, selected: Boolean = false, enabled: Boolean = true, compact: Boolean = false, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val shape = MarksyShape.Pill
    val fill by animateColorAsState(if (selected) MarksyTheme.PrimaryEmerald else MarksyTheme.SurfaceRaised, label = "pill")
    Text(
        text,
        color = when { selected -> MarksyTheme.OnAccent; enabled -> MarksyTheme.TextPrimary; else -> MarksyTheme.TextMuted },
        style = if (compact) MarksyType.Caption else MarksyType.Small,
        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
        maxLines = 1,
        // A compact pill keeps its look but reserves a 48dp touch target around it.
        modifier = (if (compact) modifier.minimumInteractiveComponentSize() else modifier).clip(shape)
            .background(fill)
            .border(MarksySpace.Border, if (selected) MarksyTheme.PrimaryEmerald else MarksyTheme.BorderGlow, shape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = if (compact) 8.dp else 10.dp, vertical = if (compact) 3.dp else 6.dp)
    )
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
