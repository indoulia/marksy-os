package com.marksy.os.capture

/** Whether text reads as an option call. One rule set for every intake route: Marksy tracks no options yet. */
object OptionGate {
    /** AMBIGUOUS is a bare "18 PE": a put or a P/E ratio. Both it and OPTION stay on the phone. */
    enum class Signal { OPTION, AMBIGUOUS, NONE }

    /** [evidence] is the words that made the call an option, as written; [contract] the contract name when one was read. */
    data class Reading(val signal: Signal, val evidence: String? = null, val contract: String? = null)

    /** The whole text: every route sends all of it, so a contract after a stock call counts too. */
    fun classify(text: String): Reading = TipExtractor.readOption(TipExtractor.flatten(text))

    fun blocks(text: String): Boolean = classify(text).signal != Signal.NONE

    /** A reviewed symbol such as "NIFTY24500CE" or "NIFTY 24500 CE" is still a contract, whatever the review says. */
    fun looksLikeContract(symbol: String?): Boolean = !symbol.isNullOrBlank() && blocks(symbol)
}
