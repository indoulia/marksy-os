package com.marksy.os.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.marksy.os.upstox.Candle
import com.marksy.os.upstox.ChartOverlays
import com.marksy.os.upstox.ChartRange

/** One indicator line drawn over the price; [pointColors] colours each segment (Supertrend's up/down). */
data class ChartOverlay(val label: String, val color: Color, val values: List<Double?>, val pointColors: List<Color>? = null, val thin: Boolean = false)

enum class Indicator(val label: String, val intradayOnly: Boolean = false) {
    EMA20("EMA 20"), EMA50("EMA 50"), VWAP("VWAP", intradayOnly = true), BOLLINGER("Bollinger 20,2"), SUPERTREND("Supertrend 10,3")
}

/** Chart look the user chose, kept across stocks and restarts like a broker app does. */
@Stable
class ChartPrefs(private val store: android.content.SharedPreferences) {
    private var candlesState by mutableStateOf(store.getBoolean(KEY_CANDLES, false))
    private var volumeState by mutableStateOf(store.getBoolean(KEY_VOLUME, true))
    private var overlaysState by mutableStateOf(store.getStringSet(KEY_OVERLAYS, emptySet()).orEmpty().mapNotNull { runCatching { Indicator.valueOf(it) }.getOrNull() }.toSet())

    var candles: Boolean
        get() = candlesState
        set(v) { candlesState = v; store.edit().putBoolean(KEY_CANDLES, v).apply() }
    var volume: Boolean
        get() = volumeState
        set(v) { volumeState = v; store.edit().putBoolean(KEY_VOLUME, v).apply() }
    val overlays: Set<Indicator> get() = overlaysState

    fun toggle(i: Indicator) {
        overlaysState = if (i in overlaysState) overlaysState - i else overlaysState + i
        store.edit().putStringSet(KEY_OVERLAYS, overlaysState.map { it.name }.toSet()).apply()
    }

    private companion object {
        const val KEY_CANDLES = "candles"
        const val KEY_VOLUME = "volume"
        const val KEY_OVERLAYS = "overlays"
    }
}

@Composable
fun rememberChartPrefs(): ChartPrefs {
    val context = LocalContext.current.applicationContext
    return remember { ChartPrefs(context.getSharedPreferences("marksy_chart", android.content.Context.MODE_PRIVATE)) }
}

/** Series for the chosen indicators; VWAP only on intraday ranges, where a session average means something. */
fun chartOverlays(candles: List<Candle>, chosen: Set<Indicator>, range: ChartRange): List<ChartOverlay> {
    val closes = candles.map { it.close }
    return Indicator.entries.filter { it in chosen && (!it.intradayOnly || range.intervals.isNotEmpty()) }.flatMap { i ->
        when (i) {
            Indicator.EMA20 -> listOf(ChartOverlay("EMA 20", MarksyTheme.SecondaryCyan, ChartOverlays.ema(closes, 20)))
            Indicator.EMA50 -> listOf(ChartOverlay("EMA 50", MarksyTheme.YellowImportant, ChartOverlays.ema(closes, 50)))
            Indicator.VWAP -> listOf(ChartOverlay("VWAP", MarksyTheme.PurpleWork, ChartOverlays.vwap(candles)))
            Indicator.BOLLINGER -> ChartOverlays.bollinger(closes).let { b ->
                listOf(ChartOverlay("BB", MarksyTheme.TextSecondary, b.upper, thin = true), ChartOverlay("BB", MarksyTheme.TextSecondary, b.lower, thin = true))
            }
            Indicator.SUPERTREND -> ChartOverlays.supertrend(candles).let { st ->
                listOf(ChartOverlay("ST", MarksyTheme.PrimaryEmerald, st.map { it?.first }, st.map { if (it?.second == false) MarksyTheme.RedUrgent else MarksyTheme.PrimaryEmerald }))
            }
        }
    }.filter { o -> o.values.any { it != null } }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChartSettingsDialog(prefs: ChartPrefs, range: ChartRange, minutes: Int?, onMinutesSelected: (Int?) -> Unit, onDismiss: () -> Unit) {
    MarksyDialog(
        onDismissRequest = onDismiss,
        title = { Text("Chart", color = MarksyTheme.TextPrimary) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Style", color = MarksyTheme.TextMuted, fontSize = 11.sp)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Pill("Line", selected = !prefs.candles) { prefs.candles = false }
                    Pill("Candles", selected = prefs.candles) { prefs.candles = true }
                    Pill("Volume", selected = prefs.volume) { prefs.volume = !prefs.volume }
                }
                Text("Indicators", color = MarksyTheme.TextMuted, fontSize = 11.sp)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Indicator.entries.forEach { i ->
                        val usable = !i.intradayOnly || range.intervals.isNotEmpty()
                        Pill(i.label + if (usable) "" else " (1D/1W)", selected = usable && i in prefs.overlays, enabled = usable) { prefs.toggle(i) }
                    }
                }
                if (range.intervals.isNotEmpty()) {
                    Text("Candle interval · ${range.label}", color = MarksyTheme.TextMuted, fontSize = 11.sp)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        range.intervals.forEach { m -> Pill(if (m >= 60) "${m / 60}h" else "${m}m", selected = m == minutes) { onMinutesSelected(m) } }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done", color = MarksyTheme.PrimaryEmerald, fontWeight = FontWeight.SemiBold) } }
    )
}
