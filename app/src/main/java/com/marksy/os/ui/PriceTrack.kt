package com.marksy.os.ui

import android.provider.Settings
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

internal fun trackColor(tone: TrackTone): Color = when (tone) {
    TrackTone.POSITIVE -> MarksyTheme.Positive
    TrackTone.NEGATIVE -> MarksyTheme.Negative
    TrackTone.WARNING -> MarksyTheme.Warning
    TrackTone.NEUTRAL -> MarksyTheme.TextSecondary
    TrackTone.MUTED -> MarksyTheme.TextMuted
}

/**
 * The one price track for every tip row: stop-loss left, target right (mirrored for a SELL), recommended ring, entry tick or
 * zone, current dot (pulsing while the tip is live), exit diamond; then SL/T and the 2×2 block. [backdrop] is the fill behind it.
 */
@Composable
internal fun PriceTrackView(track: PriceTrack, modifier: Modifier = Modifier, backdrop: Color = MarksyTheme.Surface, large: Boolean = false, block: Boolean = true) {
    Column(modifier.fillMaxWidth().alpha(if (track.muted) 0.6f else 1f), verticalArrangement = Arrangement.spacedBy(MarksySpace.Tight)) {
        if (track.hasBar) {
            TrackBar(track, backdrop, large, Modifier.fillMaxWidth().height(if (large) 22.dp else 16.dp))
            Row(Modifier.fillMaxWidth()) {
                Text(stopText(track, large), color = MarksyTheme.Negative, style = if (large) MarksyType.Meta else MarksyType.Caption, maxLines = 1, modifier = Modifier.weight(1f))
                Text(targetText(track, large), color = MarksyTheme.Positive, style = if (large) MarksyType.Meta else MarksyType.Caption, maxLines = 1)
            }
        }
        if (block) PriceBlock(track)
    }
}

private fun distance(track: PriceTrack, level: Double): String? =
    track.entryRef?.let { TipTrack.fromEntry(it, level, track.sell) }?.let { " · " + com.marksy.os.MarksyFormat.percent(it * 100, 1) }

private fun stopText(track: PriceTrack, large: Boolean): String {
    val s = track.stop ?: return ""
    return if (large) "Stop ${TipTrack.price(s)}" + distance(track, s).orEmpty() else "SL ${TipTrack.price(s)}"
}

private fun targetText(track: PriceTrack, large: Boolean): String {
    val t = track.target ?: return ""
    return if (large) "Target ${TipTrack.price(t)}" + distance(track, t).orEmpty() else "T ${TipTrack.price(t)}"
}

/** RECOMMENDED | ENTRY over CURRENT | EXIT; a missing price leaves its cell empty so every card lines up the same way. */
@Composable
internal fun PriceBlock(track: PriceTrack, modifier: Modifier = Modifier) {
    val toneColor = trackColor(track.tone)
    val cells = listOf(
        track.recommendedText?.let { Triple("RECOMMENDED", it, MarksyTheme.TextPrimary) },
        track.entryText?.let { Triple("ENTRY", it, MarksyTheme.Warning) },
        track.currentText?.let { Triple("CURRENT", it, if (track.live && track.returnFraction != null) toneColor else MarksyTheme.TextPrimary) },
        track.slot?.let { Triple(it.caption, it.text, if (it.exited) toneColor else MarksyTheme.TextSecondary) }
    )
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(MarksySpace.Hair)) {
        cells.chunked(2).filter { row -> row.any { it != null } }.forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(MarksySpace.CardPadding)) {
                row.forEach { cell ->
                    Box(Modifier.weight(1f)) { cell?.let { (caption, value, color) -> PriceCell(caption, value, color) } }
                }
            }
        }
    }
}

// The value is measured first (the caption takes what is left), so a six-digit price never wraps or clips.
@Composable
private fun PriceCell(caption: String, value: String, color: Color) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(MarksySpace.Tight), verticalAlignment = Alignment.CenterVertically) {
        Text(caption, color = MarksyTheme.TextMuted, style = MarksyType.PriceLabel, maxLines = 1, softWrap = false, overflow = TextOverflow.Clip, modifier = Modifier.weight(1f))
        Text(value, color = color, style = MarksyType.Small, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
    }
}

/** Android's "Remove animations" setting: the pulse then holds still as a faint halo. */
@Composable
internal fun reduceMotion(): Boolean {
    val context = LocalContext.current
    return remember(context) { runCatching { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }.getOrDefault(false) }
}

/** 0→1 every 1.4 s while [on]; a still 0.5 (scale 1.8, faint) when motion is reduced. */
@Composable
internal fun rememberPulse(on: Boolean): State<Float> {
    if (!on) return remember { mutableFloatStateOf(-1f) }
    if (reduceMotion()) return remember { mutableFloatStateOf(STILL_PULSE) }
    return rememberInfiniteTransition(label = "pulse").animateFloat(0f, 1f, infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Restart), label = "pulse")
}

private const val STILL_PULSE = 0.5f

/** A pulse ring around a dot of [radius] at [center]; [phase] < 0 draws nothing. */
internal fun DrawScope.pulseRing(phase: Float, center: Offset, radius: Float, color: Color) {
    if (phase < 0f) return
    val scale = if (phase == STILL_PULSE) 1.8f else 1f + 1.6f * phase
    val alpha = if (phase == STILL_PULSE) 0.35f else 0.9f * (1f - phase)
    drawCircle(color.copy(alpha = alpha), radius = radius * scale, center = center, style = Stroke(2.dp.toPx()))
}

@Composable
private fun TrackBar(track: PriceTrack, backdrop: Color, large: Boolean, modifier: Modifier) {
    val pulse = rememberPulse(track.live && track.current != null)
    val toneColor = trackColor(track.tone)
    val fill = if (track.fillPositive) MarksyTheme.Positive else MarksyTheme.Negative
    val nowColor = if (track.live) (if (track.returnFraction == null) MarksyTheme.TextPrimary else toneColor) else MarksyTheme.TextMuted
    val described = listOfNotNull(
        track.stop?.let { "Stop-loss ${TipTrack.price(it)}" }, track.target?.let { "target ${TipTrack.price(it)}" },
        track.recommendedText?.let { "recommended $it" }, track.entryText?.let { "entry $it" }, track.currentText?.let { "current $it" },
        track.exit?.let { "exit ${TipTrack.price(it)}" }
    ).joinToString(", ")
    Canvas(modifier.semantics { contentDescription = described }) {
        val w = size.width
        val mid = size.height / 2
        val k = if (large) 1.25f else 1f
        val marker = 12.dp.toPx() * k
        fun x(v: Double) = (track.at(v) ?: 0f) * w
        val bar = 4.dp.toPx() * k
        drawRoundRect(MarksyTheme.Track, Offset(0f, mid - bar / 2), Size(w, bar), CornerRadius(bar / 2))
        val entry = track.entryRef
        if (entry == null && track.zoneLow != null && track.zoneHigh != null) {
            val a = x(track.zoneLow); val b = x(track.zoneHigh)
            val left = minOf(a, b); val width = maxOf(kotlin.math.abs(b - a), w * 0.02f)
            val h = 10.dp.toPx() * k
            drawRoundRect(MarksyTheme.BadgeImportantBg, Offset(left, mid - h / 2), Size(width, h), CornerRadius(3.dp.toPx()))
            drawRoundRect(MarksyTheme.Warning, Offset(left, mid - h / 2), Size(width, h), CornerRadius(3.dp.toPx()), style = Stroke(1.dp.toPx()))
        }
        if (entry != null) {
            (track.exit ?: track.current)?.let { end ->
                val e = x(entry); val p = x(end)
                drawRoundRect(fill, Offset(minOf(e, p), mid - bar / 2), Size(kotlin.math.abs(p - e).coerceAtLeast(2f), bar), CornerRadius(bar / 2))
            }
            val tickW = 3.dp.toPx(); val tickH = 14.dp.toPx() * k
            drawRoundRect(MarksyTheme.Warning, Offset(x(entry) - tickW / 2, mid - tickH / 2), Size(tickW, tickH), CornerRadius(tickW / 2))
        }
        track.recommended?.let { r ->
            val c = Offset(x(r), mid)
            drawCircle(backdrop, radius = marker / 2, center = c)
            drawCircle(MarksyTheme.TextPrimary, radius = marker / 2 - 1.dp.toPx(), center = c, style = Stroke(2.dp.toPx()))
        }
        track.exit?.let { v ->
            val side = 11.dp.toPx() * k
            val c = Offset(x(v), mid)
            rotate(45f, c) { drawRoundRect(toneColor, Offset(c.x - side / 2, c.y - side / 2), Size(side, side), CornerRadius(2.dp.toPx())) }
        }
        track.current?.let { v ->
            val c = Offset(x(v), mid)
            pulseRing(pulse.value, c, marker / 2, nowColor)
            drawCircle(backdrop, radius = marker / 2, center = c)
            drawCircle(nowColor, radius = marker / 2 - 2.dp.toPx(), center = c)
        }
    }
}
