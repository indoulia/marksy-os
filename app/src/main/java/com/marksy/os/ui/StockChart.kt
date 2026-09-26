package com.marksy.os.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.marksy.os.upstox.Candle
import com.marksy.os.upstox.ChartRange
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt

internal object ChartAxis {
    private val IST = ZoneId.of("Asia/Kolkata")

    /** Round-valued gridlines inside [lo, hi], about [target] of them. */
    fun priceTicks(lo: Double, hi: Double, target: Int = 4): List<Double> {
        if (hi <= lo) return listOf(lo)
        val raw = (hi - lo) / target
        val magnitude = 10.0.pow(floor(log10(raw)))
        val step = listOf(1.0, 2.0, 2.5, 5.0, 10.0).map { it * magnitude }.first { it >= raw }
        return generateSequence(ceil(lo / step) * step) { it + step }.takeWhile { it <= hi + step * 1e-9 }.map { Math.round(it / step) * step }.toList()
    }

    fun price(v: Double, step: Double): String = if (step >= 1) count(Math.round(v)) else String.format(Locale.US, "%.2f", v)

    fun timeTicks(size: Int, count: Int = 4): List<Int> = if (size < 2) listOf(0) else (0 until count).map { it * (size - 1) / (count - 1) }.distinct()

    fun index(x: Float, width: Float, size: Int): Int = if (size < 2 || width <= 0) 0 else (x / width * (size - 1)).roundToInt().coerceIn(0, size - 1)

    fun timeLabel(range: ChartRange, time: Long): String = format(time, when (range) {
        ChartRange.D1 -> "HH:mm"
        ChartRange.W1, ChartRange.M1 -> "d MMM"
        ChartRange.Y1 -> "MMM yy"
        ChartRange.Y5 -> "yyyy"
    })

    fun readout(range: ChartRange, time: Long): String = format(time, when (range) {
        ChartRange.D1 -> "d MMM, HH:mm"
        ChartRange.W1 -> "EEE d MMM, HH:mm"
        else -> "d MMM yyyy"
    })

    private fun format(time: Long, pattern: String) = DateTimeFormatter.ofPattern(pattern, Locale.getDefault()).format(Instant.ofEpochMilli(time).atZone(IST))
}

/** Close-price line with price and time axes; touching reports the nearest candle to [onSelect], null on release. */
@Composable
internal fun PriceChart(candles: List<Candle>, range: ChartRange, color: Color, reference: Double?, selected: Int?, onSelect: (Int?) -> Unit) {
    val measurer = rememberTextMeasurer()
    val select by rememberUpdatedState(onSelect)
    val closes = remember(candles) { candles.map { it.close } }
    val bounds = remember(closes, reference) {
        val all = closes + listOfNotNull(reference)
        val pad = ((all.max() - all.min()).takeIf { it > 0 } ?: (all.max() * .01)) * .08
        (all.min() - pad) to (all.max() + pad)
    }
    val ticks = remember(bounds) { ChartAxis.priceTicks(bounds.first, bounds.second) }
    val step = if (ticks.size > 1) ticks[1] - ticks[0] else 1.0
    val labelStyle = TextStyle(color = MarksyTheme.TextMuted, fontSize = 9.sp)
    val gutter = 40.dp
    val axis = 16.dp
    Canvas(
        Modifier.fillMaxSize().pointerInput(candles) {
            val width = size.width - gutter.toPx()
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                select(ChartAxis.index(down.position.x, width, closes.size))
                var scrubbing = false
                while (true) {
                    val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed) break
                    val dx = change.position.x - down.position.x
                    val dy = change.position.y - down.position.y
                    if (!scrubbing) {
                        // A vertical drag belongs to the page scroll, not the chart.
                        if (abs(dy) > viewConfiguration.touchSlop && abs(dy) > abs(dx)) break
                        if (abs(dx) > viewConfiguration.touchSlop) scrubbing = true
                    }
                    if (scrubbing) {
                        change.consume()
                        select(ChartAxis.index(change.position.x, width, closes.size))
                    }
                }
                select(null)
            }
        }
    ) {
        val w = size.width - gutter.toPx()
        val h = size.height - axis.toPx()
        val (lo, hi) = bounds
        fun x(i: Int) = if (closes.size < 2) 0f else i * w / (closes.size - 1)
        fun y(v: Double) = (h * (1 - (v - lo) / (hi - lo))).toFloat()
        ticks.forEach { t ->
            val ty = y(t)
            drawLine(Color(0x14FFFFFF), Offset(0f, ty), Offset(w, ty), 1f)
            val label = measurer.measure(ChartAxis.price(t, step), labelStyle)
            drawText(label, topLeft = Offset(size.width - label.size.width, (ty - label.size.height / 2f).coerceIn(0f, h - label.size.height)))
        }
        reference?.let { r ->
            drawLine(MarksyTheme.TextMuted, Offset(0f, y(r)), Offset(w, y(r)), 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f)))
        }
        val line = Path().apply { closes.forEachIndexed { i, v -> if (i == 0) moveTo(x(i), y(v)) else lineTo(x(i), y(v)) } }
        val fill = Path().apply { addPath(line); lineTo(w, h); lineTo(0f, h); close() }
        drawPath(fill, Brush.verticalGradient(listOf(color.copy(alpha = .25f), Color.Transparent), endY = h))
        drawPath(line, color, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
        ChartAxis.timeTicks(closes.size).forEach { i ->
            val label = measurer.measure(ChartAxis.timeLabel(range, candles[i].time), labelStyle)
            drawText(label, topLeft = Offset((x(i) - label.size.width / 2f).coerceIn(0f, (w - label.size.width).coerceAtLeast(0f)), h + 3.dp.toPx()))
        }
        val point = selected?.takeIf { it in closes.indices }
        if (point != null) {
            drawLine(MarksyTheme.TextSecondary, Offset(x(point), 0f), Offset(x(point), h), 1.dp.toPx())
            drawCircle(color, 5.dp.toPx(), Offset(x(point), y(closes[point])))
            drawCircle(Color.White, 5.dp.toPx(), Offset(x(point), y(closes[point])), style = Stroke(1.5.dp.toPx()))
        } else {
            drawCircle(color, 3.dp.toPx(), Offset(x(closes.lastIndex), y(closes.last())))
        }
    }
}
