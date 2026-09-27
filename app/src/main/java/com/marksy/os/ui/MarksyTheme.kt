package com.marksy.os.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object MarksyTheme {
    val Background = Color(0xFF0A0F0D)
    val Surface = Color(0xFF111815)
    val SurfaceRaised = Color(0xFF16221C)
    val BorderGlow = Color(0xFF1E3B2E)

    val PrimaryEmerald = Color(0xFF00E676)
    val PrimaryDark = Color(0xFF00B0FF)
    val AccentGreen = Color(0xFF10B981)
    val SecondaryCyan = Color(0xFF00E5FF)

    val TextPrimary = Color(0xFFF0FDF4)
    val TextSecondary = Color(0xFFA1B2A8)
    val TextMuted = Color(0xFF5C6E64)

    val YellowImportant = Color(0xFFFFB300)
    val RedUrgent = Color(0xFFFF3D00)
    val BlueFinance = Color(0xFF2979FF)
    val OrangeDelivery = Color(0xFFFF9100)
    val PurpleWork = Color(0xFFD500F9)

    val BadgeTradingBg = Color(0xFF0D2E1F)
    val BadgeImportantBg = Color(0xFF2E2400)
    val BadgeUrgentBg = Color(0xFF330E0E)
    val BadgeFinanceBg = Color(0xFF0D1D33)
    val BadgeDeliveryBg = Color(0xFF2E1A00)
    val BadgeWorkBg = Color(0xFF240D33)

    val DialogTitle = TextStyle(color = TextPrimary, fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.Bold)
    val DialogBody = TextStyle(color = TextSecondary, fontSize = 13.sp, lineHeight = 18.sp)
}

/** Marksy colours for every Material component (chips, checkboxes, pickers, snackbars) so none fall back to the stock light palette. */
private val MarksyColorScheme = darkColorScheme(
    primary = MarksyTheme.PrimaryEmerald, onPrimary = Color.Black,
    primaryContainer = MarksyTheme.BadgeTradingBg, onPrimaryContainer = MarksyTheme.PrimaryEmerald, inversePrimary = MarksyTheme.PrimaryEmerald,
    secondary = MarksyTheme.AccentGreen, onSecondary = Color.Black,
    secondaryContainer = MarksyTheme.PrimaryEmerald, onSecondaryContainer = Color.Black,
    tertiary = MarksyTheme.SecondaryCyan, onTertiary = Color.Black,
    background = MarksyTheme.Background, onBackground = MarksyTheme.TextPrimary,
    surface = MarksyTheme.Surface, onSurface = MarksyTheme.TextPrimary,
    surfaceVariant = MarksyTheme.SurfaceRaised, onSurfaceVariant = MarksyTheme.TextSecondary, surfaceTint = Color.Transparent,
    surfaceContainerLowest = MarksyTheme.Background, surfaceContainerLow = MarksyTheme.Surface, surfaceContainer = MarksyTheme.Surface,
    surfaceContainerHigh = MarksyTheme.SurfaceRaised, surfaceContainerHighest = MarksyTheme.SurfaceRaised,
    surfaceBright = MarksyTheme.SurfaceRaised, surfaceDim = MarksyTheme.Background,
    inverseSurface = MarksyTheme.SurfaceRaised, inverseOnSurface = MarksyTheme.TextPrimary,
    error = MarksyTheme.RedUrgent, onError = Color.Black, errorContainer = MarksyTheme.BadgeUrgentBg, onErrorContainer = MarksyTheme.RedUrgent,
    outline = MarksyTheme.BorderGlow, outlineVariant = MarksyTheme.BorderGlow, scrim = Color.Black
)

@Composable
fun MarksyMaterialTheme(content: @Composable () -> Unit) = MaterialTheme(colorScheme = MarksyColorScheme) {
    // MaterialTheme makes bodyLarge (24sp line height, 0.5sp tracking) the default; Marksy text sets its own sizes on plain text.
    CompositionLocalProvider(LocalTextStyle provides TextStyle.Default, content = content)
}

/** The one Marksy popup: same slots as Material's AlertDialog, drawn on the Marksy surface with Marksy type. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MarksyDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null
) {
    BasicAlertDialog(onDismissRequest = onDismissRequest, modifier = modifier) {
        Surface(shape = RoundedCornerShape(20.dp), color = MarksyTheme.Surface, border = BorderStroke(1.dp, MarksyTheme.BorderGlow)) {
            Column(Modifier.padding(start = 18.dp, end = 18.dp, top = 16.dp, bottom = 6.dp)) {
                title?.let {
                    ProvideTextStyle(MarksyTheme.DialogTitle) { it() }
                    Spacer(Modifier.height(10.dp))
                }
                // Bounded so long content scrolls (or shrinks) inside and the buttons stay on screen.
                text?.let { Box(Modifier.weight(1f, fill = false)) { ProvideTextStyle(MarksyTheme.DialogBody) { it() } } }
                Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                    dismissButton?.invoke()
                    confirmButton()
                }
            }
        }
    }
}
