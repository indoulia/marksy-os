package com.marksy.os.market

/** Stock tickers named in free text (notifications, briefing lines), so they can link to the stock page. */
object StockMentions {
    // Upper-case only: "Infosys results" is prose, "INFY" is a ticker. Allows M&M, BAJAJ-AUTO.
    private val TOKEN = Regex("(?<![A-Za-z0-9&-])[A-Z][A-Z0-9&-]{1,19}(?![A-Za-z0-9&-])")

    // Upper-case words brokers, banks and apps use that also happen to be (or look like) listed symbols.
    private val NOT_TICKERS = setOf(
        "NSE", "BSE", "IPO", "BUY", "SELL", "HOLD", "TARGET", "TGT", "SL", "STOP", "LOSS", "CMP", "LTP", "OTP", "UPI", "INR", "RS",
        "EMI", "ATM", "NEFT", "IMPS", "RTGS", "KYC", "PAN", "SMS", "AM", "PM", "IST", "USD", "GST", "TDS", "NAV", "SIP", "ETF",
        "FNO", "NFO", "MCX", "OFS", "FPO", "QIP", "AGM", "EGM", "EPS", "PE", "PB", "ROE", "ROCE", "YOY", "QOQ", "ALERT", "NEW",
        "OK", "YES", "NO", "ID", "IT", "IN", "ON", "TO", "AT", "BY", "IS", "OR", "AN", "AS", "BE", "DO", "GO", "IF", "ME", "MY",
        "SO", "UP", "US", "WE", "THE", "AND", "FOR", "NOT", "ALL", "ARE", "YOU", "NOW", "GET", "OFF", "OUT", "FREE", "SALE",
        "NIFTY", "SENSEX", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY", "VIX"
    )

    fun find(text: String, isSymbol: (String) -> Boolean, limit: Int = 3): List<String> =
        TOKEN.findAll(text).map { it.value.trimEnd('-', '&') }
            .filter { it.length >= 2 && it !in NOT_TICKERS && isSymbol(it) }
            .distinct().take(limit).toList()
}
