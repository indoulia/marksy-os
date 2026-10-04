package com.marksy.os.capture

import com.marksy.os.notification.NotificationClassifier
import java.util.Locale

sealed interface ExtractionResult {
    data class Candidate(val candidate: TipCandidate) : ExtractionResult
    data object NotATip : ExtractionResult
    data object OneTimeCode : ExtractionResult
}

/** Reads a tip from recognized or notification text. Never invents a field: anything unclear is left empty and flagged. */
object TipExtractor {
    const val EXTRACTED_MIN_CONFIDENCE = 0.8
    const val LOW_OCR_CONFIDENCE = 0.6f
    private const val MAX_TEXT = 4_000
    private const val SYMBOL_WINDOW = 5

    // Side and level vocabulary follows CaptureGate's call rule; long/short count only beside a priced level ("short video").
    private val buyWord = Regex("""\b(?:buy|accumulate|kharido)\b""", RegexOption.IGNORE_CASE)
    private val sellWord = Regex("""\b(?:sell|becho)\b""", RegexOption.IGNORE_CASE)
    private val longWord = Regex("""\blong(?![\s-]*term)\b""", RegexOption.IGNORE_CASE)
    private val shortWord = Regex("""\bshort(?![\s-]*term)\b""", RegexOption.IGNORE_CASE)
    private val level = Regex(
        """(?:\b(targets?|tgt|tp\d?|t[12])\b|\b(stop[\s-]*loss|stoploss|s\s*/\s*l|sl|stop)\b|\b(entry|cmp|ltp)\b|(@)|\b(at|above|below|around|near|between)\b)""" +
            """\s*(?:price\s*)?(?:(?:of|at|is|:|=|-|–|~)\s*)*(?:rs\.?|₹|inr)?\s*([0-9][0-9OoIl|,.]*)""",
        RegexOption.IGNORE_CASE
    )
    private val cleanNumber = Regex("""\d{1,3}(?:,\d{2,3})+(?:\.\d+)?|\d+(?:\.\d+)?""")
    // A time, percentage or unit right after the number means it is no price ("at 10:45", "SL 2%").
    private val notAPriceAfter = Regex("""^(?::\d|\s*(?:[ap]\.?m\b|hrs?\b|mins?\b|%)|[A-Za-z])""", RegexOption.IGNORE_CASE)
    private val horizon = Regex("""\b(intraday|btst|short[\s-]*term|positional|swing|long[\s-]*term)\b|\b(\d{1,3})\s*(days?|weeks?|months?)\b""", RegexOption.IGNORE_CASE)
    private const val TIME = """\b\d{1,2}(?::\d{2})?\s*[ap]\.?m\b\.?|\b\d{1,2}:\d{2}\b"""
    private const val DATE = """\b\d{1,2}[/-]\d{1,2}[/-]\d{2,4}\b|\b\d{1,2}\s+(?:jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*\.?(?:,?\s+\d{4})?\b"""
    private val timestamp = Regex("""(?:$DATE)(?:,?\s+(?:$TIME))?|$TIME""", RegexOption.IGNORE_CASE)
    private val exchangePrefix = Regex("""\b(?:nse|bse)\s*:\s*""", RegexOption.IGNORE_CASE)
    private val tickerShape = Regex("""[A-Za-z][A-Za-z0-9&.-]{1,24}""")
    private val levelWords = setOf("target", "targets", "tgt", "tp", "sl", "stop", "stoploss", "entry", "cmp", "ltp", "at", "above", "below", "around", "near", "between", "rs", "inr")
    private val fillerWords = setOf(
        "stock", "stocks", "share", "shares", "scrip", "equity", "eq", "cash", "call", "calls", "idea", "tip", "now", "today", "tomorrow",
        "fresh", "more", "some", "add", "again", "only", "the", "a", "an", "of", "in", "on", "for", "and", "or", "it", "this", "that", "our",
        "my", "your", "with", "from", "into", "intraday", "btst", "positional", "swing", "short", "long", "term", "nse", "bse",
        "recommendation", "signal", "alert", "strong", "buy", "sell", "qty", "quantity", "lot", "lots", "fut", "futures", "zone", "range",
        "level", "levels", "dip", "dips", "rise", "if", "when", "upto", "up", "to"
    )

    private enum class Kind { TARGET, STOP, ENTRY, LOOSE_ENTRY }

    private class Level(val kind: Kind, val value: Double?)

    fun extract(text: String, provenance: CaptureProvenance, ocrConfidence: Float?): ExtractionResult {
        if (text.isBlank()) return ExtractionResult.NotATip
        // User rule 2026-10-02: a capture carrying a one-time code is dropped whole.
        if (NotificationClassifier.isOneTimeCode("", text) || NotificationClassifier.carriesOneTimeCode("", text)) return ExtractionResult.OneTimeCode
        val flat = text.replace(Regex("""[\s​]+"""), " ").trim()
        val levels = levels(flat)
        val firm = levels.any { it.kind != Kind.LOOSE_ENTRY }
        val buys = buyWord.findAll(flat) + (if (levels.isNotEmpty()) longWord.findAll(flat) else emptySequence())
        val sells = sellWord.findAll(flat) + (if (levels.isNotEmpty()) shortWord.findAll(flat) else emptySequence())
        val sideHits = (buys.map { it to TipSide.BUY } + sells.map { it to TipSide.SELL }).sortedBy { it.first.range.first }.toList()
        if (sideHits.isEmpty() && !firm) return ExtractionResult.NotATip

        val ambiguities = mutableSetOf<String>()
        val sides = sideHits.map { it.second }.toSet()
        val side = sides.singleOrNull()
        if (sides.size > 1) ambiguities += Ambiguity.CONFLICTING_SIDE
        val symbols = sideHits.firstOrNull()?.let { symbolsNear(flat, it.first) }.orEmpty()
        val symbol = symbols.singleOrNull()
        if (symbols.size > 1) ambiguities += Ambiguity.AMBIGUOUS_SYMBOL

        fun first(vararg kinds: Kind): Double? {
            val hit = levels.firstOrNull { it.kind in kinds } ?: return null
            if (hit.value == null) ambiguities += Ambiguity.MALFORMED_PRICE
            return hit.value
        }
        val target = first(Kind.TARGET)
        val stopLoss = first(Kind.STOP)
        val entry = if (levels.any { it.kind == Kind.ENTRY }) first(Kind.ENTRY) else first(Kind.LOOSE_ENTRY)
        if (target == null) ambiguities += Ambiguity.MISSING_TARGET
        if (stopLoss == null) ambiguities += Ambiguity.MISSING_STOP_LOSS
        if (ocrConfidence != null && ocrConfidence < LOW_OCR_CONFIDENCE) ambiguities += Ambiguity.LOW_OCR_CONFIDENCE

        val fields = TipFields(symbol, side, entry, target, stopLoss, horizonOf(flat), timestamp.find(flat)?.value?.trim())
        val completeness = (if (symbol != null) .3 else 0.0) + (if (side != null) .25 else 0.0) +
            listOfNotNull(entry, target, stopLoss).size * .15
        val confidence = Math.round((completeness * (ocrConfidence?.toDouble() ?: 1.0)).coerceIn(0.0, 1.0) * 100) / 100.0
        val extracted = symbol != null && side != null && target != null && stopLoss != null && ambiguities.isEmpty() && confidence >= EXTRACTED_MIN_CONFIDENCE
        return ExtractionResult.Candidate(
            TipCandidate(
                provenance, text.trim().take(MAX_TEXT), confidence, fields,
                if (extracted) CaptureState.EXTRACTED else CaptureState.REVIEW_REQUIRED, ambiguities
            )
        )
    }

    /** The canonical horizon named in [text] (intraday, BTST, short term, positional, swing, long term, N days/weeks/months), or null. */
    fun horizonOf(text: String?): String? {
        val m = horizon.find(text ?: return null) ?: return null
        val word = m.groupValues[1].lowercase(Locale.ROOT).replace(Regex("""[\s-]+"""), " ")
        return when {
            word.isEmpty() -> "${m.groupValues[2].toInt()} ${m.groupValues[3].lowercase(Locale.ROOT)}"
            word == "btst" -> "BTST"
            else -> word.replaceFirstChar { it.uppercase() }
        }
    }

    private fun levels(flat: String): List<Level> = level.findAll(flat).mapNotNull { m ->
        val token = m.groupValues[6].trimEnd('.', ',', '|')
        val after = flat.substring(m.range.first + m.value.length - (m.groupValues[6].length - token.length))
        if (notAPriceAfter.containsMatchIn(after)) return@mapNotNull null
        val kind = when {
            m.groupValues[1].isNotEmpty() -> Kind.TARGET
            m.groupValues[2].isNotEmpty() -> Kind.STOP
            m.groupValues[3].isNotEmpty() || m.groupValues[4].isNotEmpty() -> Kind.ENTRY
            else -> Kind.LOOSE_ENTRY
        }
        Level(kind, if (cleanNumber.matches(token)) token.replace(",", "").toDoubleOrNull() else null)
    }.toList()

    // The ticker sits right after the side word; failing that, an all-caps word right before it ("ABC: BUY @ 500").
    private fun symbolsNear(flat: String, side: MatchResult): List<String> {
        val words = exchangePrefix.replace(flat.substring(side.range.last + 1), "").split(' ')
        val found = mutableListOf<String>()
        for (raw in words.take(SYMBOL_WINDOW)) {
            val word = raw.trim { !it.isLetterOrDigit() && it != '&' }
            if (word.isEmpty()) continue
            val lower = word.lowercase(Locale.ROOT)
            if (raw.startsWith("@") || word[0].isDigit() || lower in levelWords) break
            if (lower in fillerWords) continue
            if (!tickerShape.matches(word) || word.count { it.isLetter() } < 2) break
            found += word.uppercase(Locale.ROOT)
        }
        if (found.isEmpty()) {
            val before = flat.substring(0, side.range.first).trimEnd().substringAfterLast(' ').trim { !it.isLetterOrDigit() && it != '&' }
            if (tickerShape.matches(before) && before == before.uppercase(Locale.ROOT) && before.lowercase(Locale.ROOT) !in fillerWords) found += before
        }
        return found.distinct()
    }
}
