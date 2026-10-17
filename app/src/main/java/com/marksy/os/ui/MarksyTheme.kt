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
    /** A cell on a raised surface (the tip sheet), one step lighter than it. */
    val SurfaceHigh = Color(0xFF1C2925)
    /** The dim behind a bottom sheet: dark enough that the sheet stands off the list. */
    val Scrim = Color(0xDB000000)
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

    /** A card border tinted to flag the card (a warning, a lane, a source); one strength everywhere. */
    fun flagBorder(color: Color) = color.copy(alpha = .5f)
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
    /** Caps beside a price (RECOMMENDED, ENTRY): small enough that a six-digit price fits beside it on a 360dp phone. */
    val PriceLabel = TextStyle(fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
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
    /** Ask Marksy's chat bubbles: the corner nearest the speaker is tight. */
    val BubbleOut = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 16.dp, bottomEnd = 4.dp)
    val BubbleIn = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 4.dp, bottomEnd = 16.dp)
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
    /** Inside every control (pill, button, field, select) from its edge to its text. */
    val Control = 12.dp
    /** The same for a compact pill. */
    val ControlCompact = 8.dp
    val Border = 1.dp
    /** The emerald ring on floating controls and today's date. */
    val BorderAccent = 1.5.dp
    /** A ring that separates a mark from what it sits on (the filter dot, the Ask orb). */
    val BorderRing = 2.dp
}

object MarksySize {
    val IconTiny = 11.dp
    val IconSmall = 14.dp
    val Icon = 18.dp
    /** Icons on the floating buttons. */
    val IconFloating = 22.dp
    val IconLarge = 24.dp
    val Dot = 6.dp
    /** The dot on a step in a timeline (IPO dates). */
    val StepDot = 10.dp
    /** The yellow dot on a floating button with a filter or search applied. */
    val ActiveDot = 16.dp
    val Avatar = 32.dp
    /** A round icon tile beside a card title (profile, holdings, reminders). */
    val AvatarLarge = 40.dp
    /** Every button, non-compact pill, text and search field, select, segmented control and stepper. */
    val Control = 32.dp
    /** A compact pill. */
    val ControlCompact = 18.dp
    /** An icon button inside a line of text (a card header, a quote). */
    val ControlInline = 24.dp
    /** Round icon buttons in the top bar. */
    val HeaderButton = 36.dp
    /** Round floating buttons. */
    val Touch = 44.dp
    /** Widest a floating filter panel's extra sections grow. */
    val MenuWidth = 260.dp
    /** Widest a chat bubble grows. */
    val BubbleWidth = 280.dp
    /** A Plan board column. */
    val BoardColumn = 272.dp
    /** Tallest a scrolling picker list in a popup grows. */
    val PickerList = 360.dp
    /** Label and value columns in a bar row, so bars line up down a card. */
    val ColumnNarrow = 44.dp
    val ColumnValue = 56.dp
    val ColumnLabel = 84.dp
    /** Thickness of a progress or split bar. */
    val Bar = 6.dp
    val BarThin = 4.dp
    val BarThick = 10.dp
    /** Height of a chart, a sparkline and a small range strip. */
    val Chart = 200.dp
    val ChartTall = 224.dp
    val ChartSmall = 120.dp
    val Sparkline = 52.dp
    val Strip = 14.dp
    /** The bottom sheet's drag handle. */
    val Handle = 36.dp
}

/** Shadow depth: floating buttons and the search field, and the panels that open over a page. */
object MarksyElevation {
    val Floating = 6.dp
    val Menu = 10.dp
}

/** Canvas geometry shared by every chart: line weights, marker radii and dash lengths. */
object MarksyDraw {
    val Hairline = 1.dp
    val Fine = 1.5.dp
    val Line = 2.dp
    val Track = 4.dp
    /** Radius of a highlighted point (the crosshair, the price on a range). */
    val Marker = 5.dp
    /** Radius of the last point on a line. */
    val Point = 3.dp
    val Dash = 4.dp
    val DashGap = 3.dp
}

/** How far a finger must travel before a swipe counts. */
object MarksyGesture {
    val Nudge = 8.dp
    val SwipeOpen = 64.dp
    /** Per second. */
    val Fling = 1200.dp
    /** A sideways swipe that turns the calendar month. */
    val PageSwipe = 56.dp
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
            Column(Modifier.padding(start = MarksySpace.Gutter, end = MarksySpace.Gutter, top = MarksySpace.Wide, bottom = MarksySpace.Inner)) {
                title?.let {
                    ProvideTextStyle(MarksyTheme.DialogTitle) { it() }
                    Spacer(Modifier.height(MarksySpace.ListGap))
                }
                // Bounded so long content scrolls (or shrinks) inside and the buttons stay on screen.
                text?.let { Box(Modifier.weight(1f, fill = false)) { ProvideTextStyle(MarksyTheme.DialogBody) { it() } } }
                Row(Modifier.fillMaxWidth().padding(top = MarksySpace.Inner), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                    dismissButton?.invoke()
                    confirmButton()
                }
            }
        }
    }
}
