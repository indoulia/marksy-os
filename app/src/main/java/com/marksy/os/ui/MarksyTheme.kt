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
import androidx.compose.ui.unit.Dp
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
    val EmailBlue = Color(0xFF82B1FF)
    val BadgeEmailBg = Color(0xFF0F1B2E)

    // Roles: screens name what a colour means, not its hue.
    val OnAccent = Color.Black
    val Positive = PrimaryEmerald
    val Negative = RedUrgent
    val Warning = YellowImportant
    val Info = BlueFinance
    val Divider = BorderGlow
    /** Unfilled part of a gauge, bar or progress line. */
    val Track = BorderGlow

    val DialogTitle = TextStyle(color = TextPrimary, fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.Bold)
    val DialogBody = TextStyle(color = TextSecondary, fontSize = 13.sp, lineHeight = 18.sp)
}

/** One colour pair per message source (avatar, tile, chip): the accent drawn on its dark badge background. */
enum class MarksySource(val accent: Color, val background: Color) {
    TRADING(MarksyTheme.PrimaryEmerald, MarksyTheme.BadgeTradingBg),
    EMAIL(MarksyTheme.EmailBlue, MarksyTheme.BadgeEmailBg),
    WHATSAPP(MarksyTheme.AccentGreen, MarksyTheme.BadgeTradingBg),
    BANK(MarksyTheme.BlueFinance, MarksyTheme.BadgeFinanceBg),
    DELIVERY(MarksyTheme.OrangeDelivery, MarksyTheme.BadgeDeliveryBg),
    IMPORTANT(MarksyTheme.YellowImportant, MarksyTheme.BadgeImportantBg),
    URGENT(MarksyTheme.RedUrgent, MarksyTheme.BadgeUrgentBg),
    WORK(MarksyTheme.PurpleWork, MarksyTheme.BadgeWorkBg),
    OTHER(MarksyTheme.TextSecondary, MarksyTheme.SurfaceRaised)
}

/** The type scale; every Text picks one of these and sets only its colour (and weight, for emphasis). */
object MarksyType {
    val Display = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.Bold)
    val Title = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Bold)
    val Heading = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Bold)
    /** Card titles, prices. */
    val Lead = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    val Subhead = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    val Body = TextStyle(fontSize = 13.sp)
    val Small = TextStyle(fontSize = 12.sp)
    val Meta = TextStyle(fontSize = 11.sp)
    /** Section and lane labels; callers uppercase the text. */
    val Label = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp)
    val Caption = TextStyle(fontSize = 10.sp)
    /** The MARKSY OS name on Home. */
    val Wordmark = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
    /** Initials inside a round avatar of [size]. */
    fun initials(size: Dp) = TextStyle(fontSize = (size.value * 0.38f).sp, fontWeight = FontWeight.Bold)
}

object MarksyShape {
    val Badge = RoundedCornerShape(4.dp)
    val Chip = RoundedCornerShape(8.dp)
    val Pill = RoundedCornerShape(12.dp)
    val Field = RoundedCornerShape(12.dp)
    val Card = RoundedCornerShape(14.dp)
    val Panel = RoundedCornerShape(16.dp)
    val Dialog = RoundedCornerShape(20.dp)
    /** Top of a bar in a bar chart. */
    val BarTop = RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp)
}

/** The spacing scale; every padding and gap is one of these. */
object MarksySpace {
    val Hair = 2.dp
    val Tight = 4.dp
    /** Between lines inside a card. */
    val Inner = 6.dp
    val Gap = 8.dp
    /** Between cards and rows in a list. */
    val ListGap = 10.dp
    val CardPadding = 12.dp
    val Section = 14.dp
    val Wide = 16.dp
    /** Page side margin. */
    val Gutter = 18.dp
    val Border = 1.dp
}

object MarksySize {
    val IconSmall = 14.dp
    val Icon = 18.dp
    val IconLarge = 24.dp
    val Dot = 6.dp
    val Avatar = 32.dp
    val Button = 32.dp
    /** Round icon buttons in the top bar. */
    val HeaderButton = 36.dp
    /** Round floating buttons and the search field. */
    val Touch = 44.dp
}

/** Marksy colours for every Material component (chips, checkboxes, pickers, snackbars) so none fall back to the stock light palette. */
private val MarksyColorScheme = darkColorScheme(
    primary = MarksyTheme.PrimaryEmerald, onPrimary = MarksyTheme.OnAccent,
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
        Surface(shape = MarksyShape.Dialog, color = MarksyTheme.Surface, border = BorderStroke(MarksySpace.Border, MarksyTheme.BorderGlow)) {
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
