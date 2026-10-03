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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/*
 * Marksy's shared building blocks. Screens compose these with MarksyTheme colours, MarksyType, MarksyShape and
 * MarksySpace instead of styling their own. Also shared: Pill (WatchlistScreen.kt), EmptyState (MainActivity.kt),
 * MarksyDialog (MarksyTheme.kt), MarksySearchField and the floating stack (OneHandControls.kt), CompactTextField,
 * MarksyLoader, and MarksyFormat for numbers and dates.
 */

/** The card surface: Surface fill, 1dp border, Card corners. */
fun Modifier.marksyCard(border: Color = MarksyTheme.BorderGlow): Modifier =
    clip(MarksyShape.Card).background(MarksyTheme.Surface).border(MarksySpace.Border, border, MarksyShape.Card)

@Composable
fun MarksyCard(
    modifier: Modifier = Modifier,
    border: Color = MarksyTheme.BorderGlow,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier.fillMaxWidth().marksyCard(border).let { if (onClick != null) it.clickable(onClick = onClick) else it }.padding(MarksySpace.CardPadding),
        verticalArrangement = Arrangement.spacedBy(MarksySpace.Inner),
        content = content
    )
}

/** Section or lane heading: optional dot, caps label, optional count, and a rule to the edge. */
@Composable
fun SectionLabel(text: String, count: Int? = null, dot: Color? = null, rule: Boolean = true, modifier: Modifier = Modifier) {
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
        }
    }
}

/** Small tag on a row (SME, LIVE, NEW): accent text on its tinted background. */
@Composable
fun MarksyBadge(text: String, color: Color, background: Color, modifier: Modifier = Modifier) {
    Text(
        text, color = color, style = MarksyType.Caption, fontWeight = FontWeight.Bold, maxLines = 1,
        modifier = modifier.clip(MarksyShape.Badge).background(background).padding(horizontal = 4.dp, vertical = 1.dp)
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
    enabled: Boolean = true
) {
    val padding = PaddingValues(horizontal = 14.dp, vertical = 0.dp)
    when (style) {
        MarksyButtonStyle.Filled -> Button(
            onClick, modifier.height(32.dp), enabled, contentPadding = padding,
            colors = ButtonDefaults.buttonColors(containerColor = color, contentColor = MarksyTheme.OnAccent)
        ) { Text(text, style = MarksyType.Small, fontWeight = FontWeight.SemiBold) }
        MarksyButtonStyle.Outlined -> OutlinedButton(
            onClick, modifier.height(32.dp), enabled, contentPadding = padding,
            border = androidx.compose.foundation.BorderStroke(MarksySpace.Border, color),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = color)
        ) { Text(text, style = MarksyType.Small, fontWeight = FontWeight.SemiBold) }
        MarksyButtonStyle.Text -> TextButton(onClick, modifier, enabled, colors = ButtonDefaults.textButtonColors(contentColor = color)) {
            Text(text, style = MarksyType.Small, fontWeight = FontWeight.SemiBold)
        }
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
