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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.marksy.os.MarksyFormat
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

    fun price(v: Double, step: Double): String = MarksyFormat.number(v, if (step >= 1) 0 else 2)

    fun timeTicks(size: Int, count: Int = 4): List<Int> = if (size < 2) listOf(0) else (0 until count).map { it * (size - 1) / (count - 1) }.distinct()

    fun index(x: Float, width: Float, size: Int): Int = if (size < 2 || width <= 0) 0 else (x / width * (size - 1)).roundToInt().coerceIn(0, size - 1)

    fun timeLabel(range: ChartRange, time: Long): String = at(time).let {
        when (range) {
            ChartRange.D1 -> MarksyFormat.time(it)
            ChartRange.W1, ChartRange.M1, ChartRange.M3 -> MarksyFormat.day(it.toLocalDate())
            ChartRange.M6, ChartRange.Y1, ChartRange.MAX -> MarksyFormat.monthYear(it)
            ChartRange.Y5 -> it.year.toString()
        }
    }

    fun readout(range: ChartRange, time: Long): String = at(time).let {
        when (range) {
            ChartRange.D1 -> MarksyFormat.dayTime(it)
            ChartRange.W1 -> MarksyFormat.weekdayDay(it) + ", " + MarksyFormat.time(it)
            else -> MarksyFormat.fullDay(it)
        }
    }

    private fun at(time: Long) = Instant.ofEpochMilli(time).atZone(IST)
}

/** Price as a line or candles with price and time axes, optional volume and indicator overlays; touching reports the nearest candle. */
@Composable
internal fun PriceChart(
    candles: List<Candle>, range: ChartRange, color: Color, reference: Double?, selected: Int?, onSelect: (Int?) -> Unit,
    levels: List<Pair<String, Double>> = emptyList(), candleMode: Boolean = false, overlays: List<ChartOverlay> = emptyList(), showVolume: Boolean = false
) {
    val measurer = rememberTextMeasurer()
    val select by rememberUpdatedState(onSelect)
    val closes = remember(candles) { candles.map { it.close } }
    // Marksy's levels widen longer ranges so they stay in view; 1D keeps the session's own scale.
    val bounds = remember(candles, reference, levels, range, candleMode, overlays) {
        val prices = if (candleMode) candles.map { it.low } + candles.map { it.high } else closes
        val all = prices + listOfNotNull(reference) + (if (range == ChartRange.D1) emptyList() else levels.map { it.second }) + overlays.flatMap { o -> o.values.filterNotNull() }
        val pad = ((all.max() - all.min()).takeIf { it > 0 } ?: (all.max() * .01)) * .08
        (all.min() - pad) to (all.max() + pad)
    }
    val maxVolume = remember(candles) { candles.maxOfOrNull { it.volume }?.takeIf { it > 0 } ?: 1L }
    val ticks = remember(bounds) { ChartAxis.priceTicks(bounds.first, bounds.second) }
    val step = if (ticks.size > 1) ticks[1] - ticks[0] else 1.0
    val labelStyle = MarksyType.Caption.copy(color = MarksyTheme.TextMuted)
    val gutter = 40.dp
    val axis = 16.dp
    val up = MarksyTheme.Positive
    val down = MarksyTheme.Negative
    Canvas(
        Modifier.fillMaxSize().pointerInput(candles) {
            val width = size.width - gutter.toPx()
            awaitEachGesture {
                val down0 = awaitFirstDown(requireUnconsumed = false)
                select(ChartAxis.index(down0.position.x, width, closes.size))
                var scrubbing = false
                while (true) {
                    val change = awaitPointerEvent().changes.firstOrNull { it.id == down0.id } ?: break
                    if (!change.pressed) break
                    val dx = change.position.x - down0.position.x
                    val dy = change.position.y - down0.position.y
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
        val plot = size.height - axis.toPx()
        // Volume takes the bottom slice of the plot; prices keep the rest.
        val volumeBand = if (showVolume) plot * .18f else 0f
        val h = plot - volumeBand
        val (lo, hi) = bounds
        // Candles need a slot each; the line runs edge to edge.
        val slot = if (candleMode) w / closes.size else 0f
        fun x(i: Int) = if (candleMode) slot * (i + .5f) else if (closes.size < 2) 0f else i * w / (closes.size - 1)
        fun y(v: Double) = (h * (1 - (v - lo) / (hi - lo))).toFloat()
        ticks.forEach { t ->
            val ty = y(t)
            drawLine(MarksyTheme.Divider, Offset(0f, ty), Offset(w, ty), 1f)
            val label = measurer.measure(ChartAxis.price(t, step), labelStyle)
            drawText(label, topLeft = Offset(size.width - label.size.width, (ty - label.size.height / 2f).coerceIn(0f, h - label.size.height)))
        }
        if (showVolume) candles.forEachIndexed { i, c ->
            val bh = volumeBand * (c.volume.toFloat() / maxVolume)
            val bw = if (candleMode) (slot * .7f).coerceAtLeast(1f) else (w / closes.size * .7f).coerceAtLeast(1f)
            drawRect((if (c.close >= c.open) up else down).copy(alpha = .35f), topLeft = Offset(x(i) - bw / 2, plot - bh), size = Size(bw, bh))
        }
        reference?.let { r ->
            drawLine(MarksyTheme.TextMuted, Offset(0f, y(r)), Offset(w, y(r)), 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f)))
        }
        levels.forEach { (name, v) ->
            if (v < lo || v > hi) return@forEach
            val tint = when (name) { "Target" -> MarksyTheme.Positive; "Stop" -> MarksyTheme.Negative; else -> MarksyTheme.TextSecondary }
            drawLine(tint.copy(alpha = .8f), Offset(0f, y(v)), Offset(w, y(v)), 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 6f)))
            val label = measurer.measure("$name ${ChartAxis.price(v, step)}", labelStyle.copy(color = tint))
            drawText(label, topLeft = Offset(4.dp.toPx(), (y(v) - label.size.height - 1.dp.toPx()).coerceIn(0f, h - label.size.height)))
        }
        if (candleMode) {
            val body = (slot * .7f).coerceAtLeast(1f)
            candles.forEachIndexed { i, c ->
                val tint = if (c.close >= c.open) up else down
                drawLine(tint, Offset(x(i), y(c.high)), Offset(x(i), y(c.low)), 1f.coerceAtLeast(body / 6))
                val top = y(maxOf(c.open, c.close)); val bottom = y(minOf(c.open, c.close))
                drawRect(tint, topLeft = Offset(x(i) - body / 2, top), size = Size(body, (bottom - top).coerceAtLeast(1f)))
            }
        } else {
            val line = Path().apply { closes.forEachIndexed { i, v -> if (i == 0) moveTo(x(i), y(v)) else lineTo(x(i), y(v)) } }
            val fill = Path().apply { addPath(line); lineTo(w, h); lineTo(0f, h); close() }
            drawPath(fill, Brush.verticalGradient(listOf(color.copy(alpha = .25f), Color.Transparent), endY = h))
            drawPath(line, color, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
        }
        overlays.forEach { o ->
            val stroke = if (o.thin) 1.dp.toPx() else 1.5.dp.toPx()
            if (o.pointColors == null) {
                val path = Path()
                var open = false
                o.values.forEachIndexed { i, v -> if (v == null) open = false else if (!open) { path.moveTo(x(i), y(v)); open = true } else path.lineTo(x(i), y(v)) }
                drawPath(path, o.color, style = Stroke(width = stroke, pathEffect = if (o.thin) PathEffect.dashPathEffect(floatArrayOf(6f, 4f)) else null))
            } else {
                for (i in 1 until o.values.size) {
                    val a = o.values[i - 1] ?: continue; val b = o.values[i] ?: continue
                    // A trend flip jumps bands; don't draw a vertical connector across it.
                    if (o.pointColors[i] != o.pointColors[i - 1]) continue
                    drawLine(o.pointColors[i], Offset(x(i - 1), y(a)), Offset(x(i), y(b)), stroke)
                }
            }
        }
        ChartAxis.timeTicks(closes.size).forEach { i ->
            val label = measurer.measure(ChartAxis.timeLabel(range, candles[i].time), labelStyle)
            drawText(label, topLeft = Offset((x(i) - label.size.width / 2f).coerceIn(0f, (w - label.size.width).coerceAtLeast(0f)), plot + 3.dp.toPx()))
        }
        val point = selected?.takeIf { it in closes.indices }
        if (point != null) {
            drawLine(MarksyTheme.TextSecondary, Offset(x(point), 0f), Offset(x(point), plot), 1.dp.toPx())
            drawCircle(color, 5.dp.toPx(), Offset(x(point), y(closes[point])))
            drawCircle(MarksyTheme.TextPrimary, 5.dp.toPx(), Offset(x(point), y(closes[point])), style = Stroke(1.5.dp.toPx()))
        } else if (!candleMode) {
            drawCircle(color, 3.dp.toPx(), Offset(x(closes.lastIndex), y(closes.last())))
        }
    }
}
