package com.marksy.os.gateway

/** The symbol shown on a trading card until Phase 4b reads the server's parsed terms instead. */
object MarksyTipPayloadBuilder {
    fun symbolOf(title: String, body: String): String? = extractSymbol(title, body, "$title $body")

    private fun extractSymbol(title: String, body: String, text: String): String? {
        // Case-sensitive capture: "Stock Alert" must not yield ALERT.
        val labelled = Regex("(?i:symbol|scrip|ticker|stock)\\s*[:=-]?\\s*([A-Z][A-Z0-9.-]{2,14})\\b")
            .find(text)?.groupValues?.getOrNull(1)
        if (!labelled.isNullOrBlank()) return labelled.uppercase()
        // A parsed call names the instrument after the side; the first caps word is often the SMS sender.
        com.marksy.os.notification.TradeCallParser.parse(title, body)?.let { return it.symbol }

        // Do not guess a ticker from arbitrary ALL-CAPS notification prose.
        // An unlabelled symbol is accepted only when strong trade context exists.
        val hasTradeContext = Regex(
            "(?i)\\b(?:BUY|SELL|ORDER|EXECUTED|FILLED|TRADE|POSITION|QTY|QUANTITY|ENTRY|TARGET|STOP\\s*LOSS|AVG(?:ERAGE)?\\s*PRICE)\\b"
        ).containsMatchIn(text)
        if (!hasTradeContext) return null

        val excluded = setOf(
            "BUY", "SELL", "ORDER", "EXECUTED", "FILLED", "TRADE", "POSITION", "OPENED", "CLOSED",
            "PRICE", "ENTRY", "TARGET", "STOP", "LOSS", "MARKET", "LIMIT", "QTY", "QUANTITY",
            "PERCENT", "CONFIDENCE", "PROBABILITY", "UNUSUAL", "VOLUME", "ALERT", "DETECTED",
            "NSE", "BSE", "INR", "UPI", "P&L", "PNL"
        )
        return Regex("\\b[A-Z][A-Z0-9.-]{2,14}\\b").findAll(text)
            .map { it.value.uppercase() }
            .firstOrNull { it !in excluded }
    }
}
