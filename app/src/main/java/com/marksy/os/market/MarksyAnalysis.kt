package com.marksy.os.market

import org.json.JSONObject
import java.util.Locale

/** Marksy's recommendation reduced to what a reader uses: a few gauges, key facts and short plain lines. */
object MarksyAnalysis {
    data class Gauge(val label: String, val fraction: Float, val value: String, val note: String? = null)
    data class View(
        val summary: String?, val gauges: List<Gauge>, val facts: List<Pair<String, String>>,
        val signals: List<Pair<String, String>>, val reasons: List<String>, val basedOn: String?
    )

    private val SIGNALS = listOf("fundamental" to "Fundamental", "technical" to "Technical", "market" to "Market", "news" to "News", "events" to "Events", "benchmarkRelative" to "Vs market")
    private val code = Regex("""\b[A-Z][A-Z0-9]*(?:_[A-Z0-9]+)+\b""")
    private val longDecimal = Regex("""-?\d+\.\d{3,}""")
    private val sma = Regex("""SMA(\d+) distance (-?\d*\.?\d+)""", RegexOption.IGNORE_CASE)
    private val volume = Regex("""volume ratio (\d*\.?\d+)""", RegexOption.IGNORE_CASE)
    private val atr = Regex("""ATR%? (\d*\.?\d+)""", RegexOption.IGNORE_CASE)
    private val alpha = Regex("""\s*\(?alpha (-?\d*\.?\d+)\)?""", RegexOption.IGNORE_CASE)

    fun from(o: JSONObject): View {
        val probability = fraction(o, "probability")
        val confidence = fraction(o, "confidence")
        val trust = fraction(o, "trustScore")
        val quality = o.text("trustQuality")?.let(::words)
        val checks = o.optInt("trustComponentsTotal", 0).takeIf { it > 0 }?.let { total -> o.opt("trustComponentsAvailable").let { (it as? Number)?.toInt() }?.let { "$it/$total checks" } }
        val gauges = listOfNotNull(
            probability?.let { Gauge("Probability", it, percent(it)) },
            confidence?.let { Gauge("Confidence", it, percent(it)) },
            trust?.let { Gauge("Trust", it, percent(it), listOfNotNull(quality, checks).joinToString(" · ").ifBlank { null }) }
        )
        val summary = listOfNotNull(
            probability?.let { "${percent(it)} probability" }, confidence?.let { "${percent(it)} confidence" }, quality?.let { "$it trust" }
        ).joinToString(" · ").ifBlank { null }
        val facts = listOfNotNull(
            number(o, "upsidePct")?.let { "Upside" to String.format(Locale.US, "%+.2f%%", it) },
            o.text("uncertainty")?.let { "Uncertainty" to words(it) },
            o.text("evidenceStrength")?.let { "Evidence" to words(it) },
            o.text("liquidity")?.let { "Liquidity" to words(it) }
        )
        val signals = SIGNALS.mapNotNull { (key, label) -> o.text(key)?.let { label to readable(it) } }
        val reasons = o.optJSONArray("trustReasons")?.let { a -> (0 until a.length()).mapNotNull { a.optString(it).takeIf(String::isNotBlank)?.let(::reason) } }.orEmpty()
        val basedOn = o.optJSONArray("providerEvidence")?.let { a -> (0 until a.length()).map { words(a.optString(it)) }.filter { it.isNotBlank() }.joinToString(" · ") }?.ifBlank { null }
        return View(summary, gauges, facts, signals, reasons, basedOn)
    }

    /** "SMA20 distance 0.029937, volume ratio 2.43, ATR% 0.0347" -> "+2.99% vs 20-day avg · volume 2.44× avg · ATR 3.48%". */
    fun readable(text: String): String {
        var s = text
            .replace(sma) { "${signedPercent(asFraction(it.groupValues[2].toDouble()))} vs ${it.groupValues[1]}-day avg" }
            .replace(volume) { "volume ${two(it.groupValues[1].toDouble())}× avg" }
            .replace(atr) { "ATR ${two(asFraction(it.groupValues[1].toDouble()) * 100)}%" }
            .replace(alpha) { " · alpha ${signedPercent(asFraction(it.groupValues[1].toDouble()))}" }
            .replace(", ", " · ")
            .replace(code) { words(it.value).lowercase(Locale.ROOT) }
            .replace(longDecimal) { two(it.value.toDouble()) }
            .trim()
        if (s.isNotEmpty()) s = s.replaceFirstChar { it.titlecase(Locale.ROOT) }
        return s
    }

    // "recent_performance: RECENT_PERFORMANCE_VERDICT_NOT_CONCLUSIVE: long explanation" -> the verdict in words.
    private fun reason(raw: String): String {
        val parts = raw.split(": ")
        val verdict = parts.getOrNull(1)?.takeIf { code.matches(it) } ?: parts.first()
        return readable(words(verdict))
    }

    private fun fraction(o: JSONObject, key: String): Float? = number(o, key)?.let { asFraction(it).coerceIn(0.0, 1.0).toFloat() }
    private fun asFraction(v: Double) = if (kotlin.math.abs(v) > 1.0) v / 100 else v
    private fun number(o: JSONObject, key: String): Double? = when (val v = o.opt(key)) {
        is Number -> v.toDouble()
        is String -> v.toDoubleOrNull()
        else -> null
    }?.takeIf { it.isFinite() }
    private fun JSONObject.text(key: String): String? = if (isNull(key)) null else optString(key).trim().takeIf { it.isNotEmpty() }
    private fun percent(f: Float) = "${Math.round(f * 100)}%"
    private fun signedPercent(f: Double) = String.format(Locale.US, "%+.2f%%", f * 100)
    private fun two(v: Double) = String.format(Locale.US, "%.2f", v).trimEnd('0').trimEnd('.')
    private fun words(s: String) = s.lowercase(Locale.ROOT).replace('_', ' ').replaceFirstChar { it.titlecase(Locale.ROOT) }
}
