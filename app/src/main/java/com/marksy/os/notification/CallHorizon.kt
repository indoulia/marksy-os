package com.marksy.os.notification

import java.util.Locale
import kotlin.math.roundToInt

/**
 * A trade call's holding horizon from free text ("for 12 Month", "Time: 1-2 Months", "1 year", "12M",
 * "long term") as words to show and a length in trading sessions (5 a week, 21 a month, 252 a year),
 * which is the unit Marksy measures a tip's horizon in. A range counts to its upper end.
 */
object CallHorizon {
    data class Horizon(val label: String, val sessions: Int)

    private const val WEEK = 5
    private const val MONTH = 21
    private const val YEAR = 252
    private const val MAX_SESSIONS = 5 * YEAR

    private val WORD_NUMBERS = mapOf(
        "a" to 1.0, "an" to 1.0, "one" to 1.0, "two" to 2.0, "three" to 3.0, "four" to 4.0, "five" to 5.0, "six" to 6.0,
        "seven" to 7.0, "eight" to 8.0, "nine" to 9.0, "ten" to 10.0, "eleven" to 11.0, "twelve" to 12.0, "eighteen" to 18.0
    )
    private const val NUM = """(\d{1,3}(?:\.\d+)?|an?|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|eighteen)"""
    private const val UNIT = """(trading\s+days?|sessions?|days?|weeks?|wks?|months?|mths?|mnths?|years?|yrs?)"""
    private val duration = Regex("""(?<![\w.])$NUM(?:\s*(?:-|–|to)\s*$NUM)?[\s-]*$UNIT\b""", RegexOption.IGNORE_CASE)
    // "12M", "1Y": research shorthand, only when glued to the number and not a rupee amount.
    private val compact = Regex("""(?<![\w.₹$])(\d{1,2})([dwmy])\b""", RegexOption.IGNORE_CASE)
    private val field = Regex(
        """\b(?:investment\s+horizon|holding\s+period|time\s*frame|horizon|duration|holding|period|tenure|time)\s*[:=\-]\s*([^|\n]+)""",
        RegexOption.IGNORE_CASE
    )
    // Conventional lengths for broker phrases, each the upper end of how the phrase is normally used.
    private val phrases = listOf(
        Regex("""\bintra[\s-]?day\b""", RegexOption.IGNORE_CASE) to ("Intraday" to 1),
        Regex("""\b(?:btst|stbt)\b""", RegexOption.IGNORE_CASE) to ("BTST" to 1),
        Regex("""\bswing\b""", RegexOption.IGNORE_CASE) to ("Swing" to 2 * WEEK),
        Regex("""\bpositional\b""", RegexOption.IGNORE_CASE) to ("Positional" to MONTH),
        Regex("""\bshort[\s-]?term\b""", RegexOption.IGNORE_CASE) to ("Short term" to 3 * MONTH),
        Regex("""\b(?:medium|mid)[\s-]?term\b""", RegexOption.IGNORE_CASE) to ("Medium term" to 6 * MONTH),
        Regex("""\blong[\s-]?term\b""", RegexOption.IGNORE_CASE) to ("Long term" to YEAR)
    )
    // "last 3 years", "52 week high", "200 day moving average", "1 year return": history and statistics, not a horizon.
    private val NOT_AFTER = setOf("last", "past", "previous", "prior", "since", "trailing")
    private val NOT_BEFORE = setOf(
        "high", "highs", "low", "lows", "range", "return", "returns", "avg", "average", "moving", "ma", "sma", "ema", "dma",
        "chart", "change", "volume", "gain", "gains", "performance", "cagr", "ago", "old", "back"
    )

    fun parse(text: String): Horizon? {
        field.findAll(text).forEach { f -> (durationIn(f.groupValues[1]) ?: phraseIn(f.groupValues[1]))?.let { return it } }
        return durationIn(text) ?: phraseIn(text)
    }

    private fun durationIn(text: String): Horizon? {
        val spans = duration.findAll(text).map { m ->
            Triple(m.range, (m.groupValues[2].ifEmpty { m.groupValues[1] }).let(::number), unitSessions(m.groupValues[3]))
        } + compact.findAll(text).map { m -> Triple(m.range, m.groupValues[1].toDouble(), unitSessions(m.groupValues[2])) }
        return spans.sortedBy { it.first.first }
            .filter { (range, _, _) -> wordBefore(text, range.first) !in NOT_AFTER && wordAfter(text, range.last + 1) !in NOT_BEFORE }
            .firstNotNullOfOrNull { (range, value, perUnit) ->
                val sessions = value?.let { (it * perUnit).roundToInt() }?.takeIf { it in 1..MAX_SESSIONS } ?: return@firstNotNullOfOrNull null
                Horizon(label(text.substring(range.first, range.last + 1)), sessions)
            }
    }

    private fun phraseIn(text: String): Horizon? =
        phrases.mapNotNull { (re, v) -> re.find(text)?.let { it to v } }.minByOrNull { it.first.range.first }
            ?.let { (m, v) -> Horizon(if (v.first == "BTST") m.value.uppercase() else v.first, v.second) }

    private fun number(s: String): Double? = s.toDoubleOrNull() ?: WORD_NUMBERS[s.lowercase()]

    private fun unitSessions(unit: String): Int = when (unit.lowercase().first()) {
        'w' -> WEEK
        'm' -> MONTH
        'y' -> YEAR
        else -> 1
    }

    /** "12 Month" → "12 months", "six months" → "6 months", "12M" → "12 months", "1-2 Months" → "1-2 months". */
    private fun label(raw: String): String {
        val c = compact.matchEntire(raw)
        val m = if (c == null) duration.matchEntire(raw) else null
        val low = c?.groupValues?.get(1)?.toDouble() ?: m?.groupValues?.get(1)?.let(::number) ?: return raw.trim()
        val high = m?.groupValues?.get(2)?.takeIf { it.isNotEmpty() }?.let(::number)
        val unit = when (unitSessions(c?.groupValues?.get(2) ?: m!!.groupValues[3])) { WEEK -> "week"; MONTH -> "month"; YEAR -> "year"; else -> "day" }
        val amount = listOfNotNull(low, high).joinToString("-") { fmt(it) }
        return "$amount $unit" + if ((high ?: low) == 1.0) "" else "s"
    }

    private fun fmt(v: Double) = if (v % 1.0 == 0.0) v.toInt().toString() else String.format(Locale.US, "%.1f", v)

    private fun wordBefore(text: String, index: Int): String? =
        Regex("""(\w+)\W*$""").find(text.substring((index - 24).coerceAtLeast(0), index))?.groupValues?.get(1)?.lowercase()

    private fun wordAfter(text: String, end: Int): String? =
        Regex("""^\W*(\w+)""").find(text.substring(end, (end + 24).coerceAtMost(text.length)))?.groupValues?.get(1)?.lowercase()
}
