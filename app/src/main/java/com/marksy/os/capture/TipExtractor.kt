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
    // Expiry, strike, CE/PE: "24 OCT", "24TH OCT", "OCT", "24 OCT 2026", "28-OCT-2026", "28/10" or "28/10/2026", then "24500 CE".
    private const val CONTRACT_TAIL = """((?:(?:\d{1,2}(?:st|nd|rd|th)?[\s-]?)?(?:jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*(?:[\s-]?\d{2,4})?|""" +
        """\d{1,2}[/-]\d{1,2}(?:[/-]\d{2,4})?)\s+)?(\d[\d,]*(?:\.\d+)?)\s*(CE|PE)\b"""
    private val optionAfterSymbol = Regex("""^\s+$CONTRACT_TAIL""", RegexOption.IGNORE_CASE)
    // A contract named away from the side word ("NIFTY 24500 CE: BUY above 182"); isContract decides.
    private val optionAnywhere = Regex("""\b([A-Za-z][A-Za-z0-9&.-]*)\s+$CONTRACT_TAIL""", RegexOption.IGNORE_CASE)
    private val optionFused = Regex("""\b[A-Za-z][A-Za-z&-]*\d[\d.]*(?:CE|PE)\b""", RegexOption.IGNORE_CASE)
    // CE after a number is always a call; PE after 4+ digits is a strike, as no P/E ratio is that large.
    private val optionMarker = Regex("""\d\s*CE\b|\d{4,}\s*PE\b|\d{1,3}(?:,\d{3})+\s*PE\b""", RegexOption.IGNORE_CASE)
    private val bareNumberPe = Regex("""\d\s*PE\b""", RegexOption.IGNORE_CASE)
    private val callLevel = Regex("""\b(?:targets?|tgt|tp\d?|t[12]|stop[\s-]*loss|stoploss|s\s*/\s*l|sl|stop)\b""", RegexOption.IGNORE_CASE)
    // P/E valuations ("trades at 18 PE", "18x PE", "18 PE ratio") are blanked before any option is looked for.
    private val peRatio = Regex(
        """\b(?:(?:at|of|trades?|trading|valued|forward|fwd|trailing|ttm)\s+(?:at\s+)?\d{1,3}(?:\.\d+)?\s*(?:x|times)?|\d{1,3}(?:\.\d+)?\s*(?:x|times))\s*PE\b""" +
            """|\b\d{1,3}(?:\.\d+)?\s*PE\s+(?:ratio|multiple|valuation)\b""",
        RegexOption.IGNORE_CASE
    )
    private val levelWords = setOf("target", "targets", "tgt", "tp", "sl", "stop", "stoploss", "entry", "cmp", "ltp", "at", "above", "below", "around", "near", "between", "rs", "inr")
    private val fillerWords = setOf(
        "stock", "stocks", "share", "shares", "scrip", "equity", "eq", "cash", "call", "calls", "idea", "tip", "now", "today", "tomorrow",
        "fresh", "more", "some", "add", "again", "only", "the", "a", "an", "of", "in", "on", "for", "and", "or", "it", "this", "that", "our",
        "my", "your", "with", "from", "into", "intraday", "btst", "positional", "swing", "short", "long", "term", "nse", "bse",
        "recommendation", "signal", "alert", "strong", "buy", "sell", "qty", "quantity", "lot", "lots", "fut", "futures", "zone", "range",
        "level", "levels", "dip", "dips", "rise", "if", "when", "upto", "up", "to"
    )
    private val notUnderlying = levelWords + fillerWords + setOf("accumulate", "kharido", "becho", "ce", "pe", "ratio", "trades", "trading", "valued")

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
        // "SELL NIFTY 24500 CE below 182": the levels are the option's premium, so the whole contract is kept and reviewed.
        // Only the first call names the instrument: up to its target or stop and never past the next BUY/SELL, so a later
        // call or a hedge note ("| BUY NIFTY 24500 CE") never turns a stock call into an option.
        val callStart = sideHits.firstOrNull()?.first?.range?.last?.plus(1) ?: 0
        val callEnd = listOfNotNull(sideHits.getOrNull(1)?.first?.range?.first, callLevel.find(flat, callStart)?.range?.first).minOrNull() ?: flat.length
        val scan = peRatio.replace(flat.substring(0, callEnd)) { " ".repeat(it.value.length) }
        val found = symbols.singleOrNull()
        val contract = found?.let { optionContract(scan, sideHits.first().first, it) }
            ?: optionAnywhere.findAll(scan).firstOrNull(::isContract)?.let { contractName(it.groupValues[1], it, 2) }
        val option = contract != null || optionFused.containsMatchIn(scan) || optionMarker.containsMatchIn(scan)
        if (option) ambiguities += Ambiguity.OPTION_CONTRACT
        // A bare "18 PE" may be a P/E ratio or a put: reviewed with its symbol kept, never flagged as an option.
        val unsure = !option && bareNumberPe.containsMatchIn(scan)
        val symbol = contract ?: found
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
        val extracted = symbol != null && side != null && target != null && stopLoss != null && ambiguities.isEmpty() && !unsure && confidence >= EXTRACTED_MIN_CONFIDENCE
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

    /** "NIFTY 24 OCT 24500 CE" when an optional expiry, a strike and CE/PE follow [symbol] after the side word, else null. */
    private fun optionContract(flat: String, side: MatchResult, symbol: String): String? {
        val at = flat.indexOf(symbol, side.range.last + 1, ignoreCase = true).takeIf { it >= 0 } ?: return null
        val m = optionAfterSymbol.find(flat.substring(at + symbol.length)) ?: return null
        return contractName(symbol, m, 1)
    }

    // Away from the side word a put needs an expiry or a 3+ digit strike: "TCS 18 PE" may be a P/E ratio.
    private fun isContract(m: MatchResult): Boolean {
        if (m.groupValues[1].lowercase(Locale.ROOT) in notUnderlying) return false
        return m.groupValues[4].equals("CE", ignoreCase = true) || m.groupValues[2].isNotBlank() ||
            m.groupValues[3].substringBefore('.').count(Char::isDigit) >= 3
    }

    /** Underlying, expiry, strike and CE/PE from [m], whose expiry is group [first] (strike and type follow it). */
    private fun contractName(underlying: String, m: MatchResult, first: Int): String {
        val expiry = m.groupValues[first].trim().replace(Regex("""\s+"""), " ").ifEmpty { null }?.uppercase(Locale.ROOT)
        return listOfNotNull(underlying.uppercase(Locale.ROOT), expiry, m.groupValues[first + 1].replace(",", ""), m.groupValues[first + 2].uppercase(Locale.ROOT))
            .joinToString(" ")
    }

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
