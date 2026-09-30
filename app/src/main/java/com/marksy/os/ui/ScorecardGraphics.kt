package com.marksy.os.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.marksy.os.market.LedgerCalls
import com.marksy.os.market.LedgerTipDto
import com.marksy.os.market.SeriesPointDto
import com.marksy.os.market.TrustBandDto
import java.time.Duration
import java.time.OffsetDateTime

enum class ReturnRange(val label: String, val days: Long?) { W1("1W", 7), M1("1M", 30), M3("3M", 90), ALL("All", null) }

/** Scorecard drawing math, kept apart from Compose so the numbers are testable. */
object ScorecardGraphics {
    /** A trust ring's filled share; a real score shows at least a sliver, null shows none. */
    fun ringFraction(score: Double?): Float = score?.let { (maxOf(it, 3.0) / 100).coerceIn(0.0, 1.0).toFloat() } ?: 0f

    /** Red below 30, amber from 30 to 59, green at 60 or above; grey when unknown. */
    fun tone(score: Double?): Color = when {
        score == null -> MarksyTheme.TextMuted
        score >= 60 -> MarksyTheme.PrimaryEmerald
        score >= 30 -> MarksyTheme.YellowImportant
        else -> MarksyTheme.RedUrgent
    }

    /** The hero bar as 0-1 shares: the Wilson hit-rate interval, and the observed hit rate that always falls inside it. */
    data class HitRateBar(val low: Float, val high: Float, val marker: Float?)

    fun hitRateBand(band: TrustBandDto?, hitRatePct: Double?): HitRateBar? = band?.let {
        HitRateBar(it.low.toFloat().coerceIn(0f, 1f), it.high.toFloat().coerceIn(0f, 1f), hitRatePct?.let { h -> (h / 100).toFloat().coerceIn(0f, 1f) })
    }

    fun hitRateRangeText(band: TrustBandDto): String = "Hit-rate range ${Math.round(band.low * 100)}–${Math.round(band.high * 100)}%"

    fun returnTone(fraction: Double?): Color = when {
        fraction == null -> MarksyTheme.TextMuted
        fraction >= 0 -> MarksyTheme.PrimaryEmerald
        else -> MarksyTheme.RedUrgent
    }

    /** Each count's share of the whole; all zero when nothing is counted. */
    fun shares(counts: List<Int>): List<Float> {
        val total = counts.sumOf { maxOf(it, 0) }
        return counts.map { if (total == 0) 0f else maxOf(it, 0).toFloat() / total }
    }

    /** Donut segments as (start, sweep) fractions of the full turn, in the order given. */
    fun donut(counts: List<Int>): List<Pair<Float, Float>> {
        var start = 0f
        return shares(counts).map { sweep -> (start to sweep).also { start += sweep } }
    }

    /** Points 0–1 across and 0 (top) – 1 (bottom) down; the bounds always include zero so the baseline shows. */
    fun chartPoints(values: List<Double>, lo: Double, hi: Double): List<Pair<Float, Float>> {
        val span = (hi - lo).takeIf { it > 0 } ?: 1.0
        val last = (values.size - 1).coerceAtLeast(1)
        return values.mapIndexed { i, v -> (if (values.size == 1) 1f else i.toFloat() / last) to (1 - (v - lo) / span).toFloat().coerceIn(0f, 1f) }
    }

    fun bounds(vararg series: List<Double>): Pair<Double, Double> {
        val all = series.flatMap { it }
        val lo = minOf(0.0, all.minOrNull() ?: 0.0)
        val hi = maxOf(0.0, all.maxOrNull() ?: 0.0)
        return if (hi - lo < 1e-9) (lo - 1) to (hi + 1) else lo to hi
    }

    /** A row's trend from its outcome counts: hits step up, stops step down, expiries hold, spread evenly. */
    fun sparkline(hits: Int, stops: Int, expired: Int, points: Int = 16): List<Double> {
        val counts = listOf(maxOf(hits, 0), maxOf(stops, 0), maxOf(expired, 0))
        val n = counts.sum()
        if (n == 0) return listOf(0.0, 0.0)
        val step = listOf(1.0, -1.0, 0.0)
        val placed = IntArray(3)
        val walk = ArrayList<Double>(n + 1).apply { add(0.0) }
        for (i in 1..n) {
            val pick = (0..2).maxByOrNull { k -> counts[k].toDouble() * i / n - placed[k] }!!
            placed[pick]++
            walk.add(walk.last() + step[pick])
        }
        if (walk.size <= points) return walk
        return (0 until points).map { walk[Math.round(it.toDouble() * (walk.size - 1) / (points - 1)).toInt()] }
    }

    /** The series in percentage points within [range], rebased so the window starts at zero. */
    fun window(series: List<SeriesPointDto>, range: ReturnRange): List<Pair<Double, Double>> {
        val end = series.lastOrNull()?.let { instant(it.at) }
        val start = range.days?.let { d -> end?.minus(Duration.ofDays(d)) }
        val inside = if (start == null) series else series.filter { p -> instant(p.at)?.let { !it.isBefore(start) } ?: true }
        val before = if (start == null) null else series.lastOrNull { p -> instant(p.at)?.isBefore(start) == true }
        val baseR = before?.realisedCum ?: 0.0
        val baseP = before?.promisedCum ?: 0.0
        if (inside.isEmpty()) return emptyList()
        return listOf(0.0 to 0.0) + inside.map { (it.realisedCum - baseR) * 100 to (it.promisedCum - baseP) * 100 }
    }

    private fun instant(iso: String) = runCatching { OffsetDateTime.parse(iso).toInstant() }.getOrNull()

    /** Where a tip's price stands between stop (0) and target (1): its exit once closed, else its last return from entry. */
    fun levelPosition(t: LedgerTipDto): Float? {
        val stop = t.stopLoss ?: return null
        val target = t.target ?: return null
        if (target == stop) return null
        val entry = LedgerCalls.entryMid(t)
        val sign = if (t.direction == "SELL") -1 else 1
        val move = if (LedgerCalls.isActive(t)) t.latestProgress?.returnToDate else t.actualReturn
        val now = t.exitPrice ?: entry?.let { e -> move?.let { e * (1 + sign * it) } ?: e } ?: return null
        return ((now - stop) / (target - stop)).coerceIn(0.0, 1.0).toFloat()
    }

    fun entryPosition(t: LedgerTipDto): Float? {
        val stop = t.stopLoss ?: return null
        val target = t.target ?: return null
        val entry = LedgerCalls.entryMid(t) ?: return null
        return if (target == stop) null else ((entry - stop) / (target - stop)).coerceIn(0.0, 1.0).toFloat()
    }
}

internal val ScoreCardShape = RoundedCornerShape(14.dp)

/** The stock page's card: surface, glow border, 12dp in. */
internal fun Modifier.scoreCard(): Modifier = fillMaxWidth().clip(ScoreCardShape).background(MarksyTheme.Surface).border(1.dp, MarksyTheme.BorderGlow, ScoreCardShape).padding(12.dp)

/** A 0–1 ring; [dashed] draws the grey outline used while a value is unknown. */
@Composable
internal fun ValueRing(fraction: Float, color: Color, size: Dp, stroke: Dp, dashed: Boolean = false, content: @Composable BoxScope.() -> Unit = {}) {
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val w = stroke.toPx()
            val inset = w / 2
            val arcSize = Size(this.size.width - w, this.size.height - w)
            if (dashed) {
                drawCircle(MarksyTheme.TextMuted, radius = (this.size.minDimension - w) / 2, style = Stroke(1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx()))))
            } else {
                drawArc(MarksyTheme.SurfaceRaised, 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(w))
                if (fraction > 0f) drawArc(color, -90f, 360f * fraction, false, Offset(inset, inset), arcSize, style = Stroke(w, cap = StrokeCap.Round))
            }
        }
        content()
    }
}

@Composable
internal fun TrustRing(trust: Int?, size: Dp, stroke: Dp, numberSize: TextUnit, caption: Boolean = true) {
    val score = trust?.toDouble()
    ValueRing(ScorecardGraphics.ringFraction(score), ScorecardGraphics.tone(score), size, stroke, dashed = trust == null) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(trust?.toString() ?: "—", color = if (trust == null) MarksyTheme.TextMuted else MarksyTheme.TextPrimary, fontSize = numberSize, fontWeight = FontWeight.Black, lineHeight = numberSize)
            if (caption) Text("TRUST", color = MarksyTheme.TextSecondary, fontSize = 9.sp, letterSpacing = 1.sp, lineHeight = 10.sp)
        }
    }
}

/** Hit / stop / expired filled, open outlined, each by its share of the calls. */
@Composable
internal fun OutcomeBar(hits: Int, stops: Int, expired: Int, open: Int, modifier: Modifier = Modifier, height: Dp = 6.dp) {
    val shares = ScorecardGraphics.shares(listOf(hits, stops, expired, open))
    val colors = listOf(MarksyTheme.PrimaryEmerald, MarksyTheme.RedUrgent, MarksyTheme.TextMuted, MarksyTheme.SecondaryCyan)
    Canvas(modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(height / 2)).background(MarksyTheme.SurfaceRaised)) {
        var x = 0f
        shares.forEachIndexed { i, s ->
            val w = size.width * s
            if (w > 0f) {
                if (i == 3) {
                    val line = 1.5.dp.toPx()
                    drawRoundRect(colors[i], Offset(x + line / 2, line / 2), Size(w - line, size.height - line), CornerRadius(size.height / 2), style = Stroke(line))
                } else drawRect(colors[i], Offset(x, 0f), Size(w, size.height))
            }
            x += w
        }
    }
}

@Composable
internal fun OutcomeLegend() {
    Row(Modifier.padding(horizontal = 2.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
        listOf("Hit" to MarksyTheme.PrimaryEmerald, "Stop" to MarksyTheme.RedUrgent, "Expired" to MarksyTheme.TextMuted, "Open" to null).forEach { (label, color) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(8.dp).clip(CircleShape).then(
                        if (color != null) Modifier.background(color) else Modifier.border(1.5.dp, MarksyTheme.SecondaryCyan, CircleShape)
                    )
                )
                Spacer(Modifier.width(5.dp))
                Text(label, color = MarksyTheme.TextSecondary, fontSize = 11.sp)
            }
        }
    }
}

@Composable
internal fun Sparkline(values: List<Double>, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val (lo, hi) = ScorecardGraphics.bounds(values)
        val pts = ScorecardGraphics.chartPoints(values, lo, hi)
        val pad = 2.dp.toPx()
        val path = Path()
        pts.forEachIndexed { i, (x, y) ->
            val px = pad + x * (size.width - 2 * pad)
            val py = pad + y * (size.height - 2 * pad)
            if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        drawPath(path, color, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/** A signed return in a tinted capsule; [waiting] marks a call still open with no return yet. */
@Composable
internal fun ReturnBadge(fraction: Double?, fontSize: TextUnit = 13.sp, waiting: Boolean = false) {
    val (fg, bg) = when {
        fraction == null && waiting -> MarksyTheme.SecondaryCyan to MarksyTheme.SecondaryCyan.copy(alpha = .12f)
        fraction == null -> MarksyTheme.TextMuted to MarksyTheme.SurfaceRaised
        fraction >= 0 -> MarksyTheme.PrimaryEmerald to MarksyTheme.BadgeTradingBg
        else -> MarksyTheme.RedUrgent to MarksyTheme.BadgeUrgentBg
    }
    Text(
        LedgerCalls.returnText(fraction) ?: if (waiting) "Waiting" else "—", color = fg, fontSize = fontSize, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace,
        maxLines = 1, modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(bg).padding(horizontal = 8.dp, vertical = 2.dp)
    )
}

@Composable
internal fun TagChip(text: String, fg: Color = MarksyTheme.TextSecondary, bg: Color = MarksyTheme.SurfaceRaised) {
    Text(text, color = fg, fontSize = 10.sp, fontWeight = FontWeight.Medium, maxLines = 1, modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(bg).padding(horizontal = 7.dp, vertical = 2.dp))
}
