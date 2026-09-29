package com.marksy.os.notification

/**
 * Pulls side, symbol and price levels out of broker / SMS / chat trade calls such as
 * "BUY RENUKA CMP : 23.62 SL : 22.25 TGT : 26" or "BUY | CROPSTER AGRO | Entry ₹2.82 | Target ₹10 | SL ₹2".
 * Returns null unless there is a side and at least two levels, so ordinary text is never shown as a call.
 */
object TradeCallParser {
    enum class Side { BUY, SELL }

    data class TradeCall(val side: Side, val symbol: String, val entry: Double?, val stopLoss: Double?, val target: Double?, val horizon: String?, val horizonSessions: Int? = null)

    // "short" is a SELL only on its own: "Short term Call" is a horizon, not a side.
    private val side = Regex("""\b(buy|sell|short(?![\s-]*term)|accumulate)\b""", RegexOption.IGNORE_CASE)
    // Where the instrument name ends: a separator, a level label, or a standalone price. Digits inside a
    // name (360ONE) or an option strike ("NIFTY 24500 PE") are part of the instrument.
    private val symbolEnd = Regex("""[|\n]|\b(cmp|ltp|entry|sl|tgt|target|targets|stop|stoploss|with|at|above|below|near|around|in|range|between)\b|@|₹|\brs\b|(?<!\w)\d[\d,]*(?:\.\d+)?(?!\w)(?!\s*(?:ce|pe)\b)""", RegexOption.IGNORE_CASE)
    private const val PRICE = """\s*[:\-=]?\s*(?:rs\.?|inr|₹)?\s*([0-9][0-9,]*(?:\.[0-9]+)?)"""
    // "around"/"target price of" are ICICI Direct research calls: "Buy JSL around Rs 750 … target price of Rs 915".
    private val entry = Regex("""(?:\b(?:cmp|ltp|entry|buy\s+at|sell\s+at|around|range\s+of|between)|@)$PRICE""", RegexOption.IGNORE_CASE)
    private val stop = Regex("""\b(?:sl|stop\s*loss|stoploss|stop-loss)$PRICE""", RegexOption.IGNORE_CASE)
    // "Target 1: 150": the 1 numbers the target, it is not the price.
    private val target = Regex("""\b(?:tgt|targets?)(?:\s*\d\s*[:\-=)])?(?:\s+price)?(?:\s+of)?$PRICE""", RegexOption.IGNORE_CASE)

    fun parse(title: String, body: String): TradeCall? {
        val text = "$title\n$body"
        val sideMatch = side.find(text) ?: return null
        val after = text.substring(sideMatch.range.last + 1).trimStart(' ', '|', ':', ',', '-', '\t', '\n')
        val symbol = after.substring(0, symbolEnd.find(after)?.range?.first ?: after.length)
            .trim(' ', '|', ':', ',', '-', '.').replace(Regex("\\s+"), " ").uppercase()
        if (symbol.isEmpty() || symbol.length > 30 || symbol.none { it.isLetter() }) return null
        val levels = listOf(entry, stop, target).map { re -> re.find(text)?.groupValues?.get(1)?.replace(",", "")?.toDoubleOrNull() }
        if (levels.count { it != null } < 2) return null
        val horizon = CallHorizon.parse(text)
        val s = if (sideMatch.value.lowercase() in setOf("sell", "short")) Side.SELL else Side.BUY
        return TradeCall(s, symbol, levels[0], levels[1], levels[2], horizon?.label, horizon?.sessions)
    }
}
