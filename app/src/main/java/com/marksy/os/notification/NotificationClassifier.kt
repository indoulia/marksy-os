package com.marksy.os.notification

object NotificationClassifier {
    /** Bump when rules change so stored events are reclassified once on next launch. */
    const val VERSION = 9

    enum class Category {
        TRADING, BANKING, BILLS, PAYMENTS, OTP, REMINDERS, MESSAGES,
        WORK, EMAIL, DELIVERY, PROMOTIONS, SYSTEM, MARKET, OTHER
    }

    data class Result(val category: Category, val priority: Int, val confidence: Float)

    private data class Rule(
        val category: Category,
        val priority: Int,
        val confidence: Float,
        val terms: List<String>
    )

    private val tradingPackages = setOf(
        "com.upstox.pro",
        "com.icicidirect",
        "com.etmoney",
        "com.zerodha.kite3",
        "com.zerodha.kite",
        "com.nextbillion.groww",
        "com.angelbroking.smartmoney",
        "com.angelbroking.lite",
        "com.fivepaisa.trade",
        // Package ids as actually installed (Play Store builds), verified on-device 2026-09-25.
        "in.upstox.app",
        "com.icicidirect.idirectsuper",
        "com.zerodha.coin",
        "com.assetgro.stockgro.prod"
    )

    // Package identity is a strong fallback signal for apps whose notification text
    // carries no explicit keyword. Consulted only AFTER term rules, so it never
    // overrides a stronger textual signal (OTP, trading, payment, etc.).
    private val packageHints: List<Pair<String, Category>> = listOf(
        "com.google.android.gm" to Category.EMAIL,
        "outlook" to Category.EMAIL,
        "yahoo" to Category.EMAIL,
        "protonmail" to Category.EMAIL,
        "whatsapp" to Category.MESSAGES,
        "telegram" to Category.MESSAGES,
        "securesms" to Category.MESSAGES,
        "com.facebook.orca" to Category.MESSAGES,
        "phonepe" to Category.PAYMENTS,
        "paytm" to Category.PAYMENTS,
        "nbu.paisa" to Category.PAYMENTS,
        "bhim" to Category.PAYMENTS,
        "myntra" to Category.PROMOTIONS,
        "flipkart" to Category.PROMOTIONS,
        "amazon" to Category.PROMOTIONS,
        "ajio" to Category.PROMOTIONS,
        "nykaa" to Category.PROMOTIONS,
        "meesho" to Category.PROMOTIONS,
        "swiggy" to Category.PROMOTIONS,
        "zomato" to Category.PROMOTIONS,
        "delhivery" to Category.DELIVERY,
        "bluedart" to Category.DELIVERY,
        "ekart" to Category.DELIVERY,
        "shadowfax" to Category.DELIVERY,
        "hdfc" to Category.BANKING,
        "kotak" to Category.BANKING,
        "sbi" to Category.BANKING,
        "axisbank" to Category.BANKING
    )

    // High-signal rules come first. Generic words such as "paid" must never
    // override a stronger trading or OTP signal.
    private val rules = listOf(
        Rule(Category.OTP, 90, .98f, listOf("otp", "one time password", "verification code", "verification otp")),
        Rule(Category.TRADING, 100, .96f, listOf(
            "order executed", "order filled", "buy order", "sell order", "trade executed",
            "trade confirmation", "position opened", "position closed", "stop loss", "target hit",
            "market alert", "order rejected", "order cancelled", "order canceled", "executed at",
            "filled at", "quantity executed", "average price"
        )),
        // Dues and birthdays you must act on; ahead of banking so "ensure funds are credited" stays a reminder.
        Rule(Category.REMINDERS, 88, .90f, listOf(
            "due on", "due by", "due date", "bill due", "emi due", "payment due", "amount due", "overdue", "birthday"
        )),
        Rule(Category.BANKING, 80, .92f, listOf(
            "credited", "debited", "account balance", "bank alert", "withdrawn", "deposit",
            "cash withdrawal", "account statement"
        )),
        Rule(Category.PAYMENTS, 75, .90f, listOf(
            "upi", "payment successful", "payment failed", "transaction successful", "transaction failed",
            "paid successfully", "payment received"
        )),
        Rule(Category.BILLS, 65, .88f, listOf(
            "bill due", "bill payment", "electricity bill", "recharge due", "invoice", "utility bill"
        )),
        Rule(Category.DELIVERY, 55, .90f, listOf(
            "out for delivery", "delivered", "shipment", "delivery", "courier", "tracking"
        )),
        Rule(Category.WORK, 50, .82f, listOf(
            "meeting", "calendar", "slack", "teams", "deadline", "assigned you", "task due"
        )),
        Rule(Category.EMAIL, 48, .82f, listOf(
            "new email", "unread email", "unread emails", "mailbox", "sent you an email"
        )),
        Rule(Category.REMINDERS, 45, .80f, listOf("reminder", "remind me", "alarm")),
        Rule(Category.PROMOTIONS, 20, .90f, listOf(
            "sale", "offer", "discount", "deal", "coupon", "cashback", "limited time"
        )),
        Rule(Category.MESSAGES, 40, .75f, listOf("new message", "message from", "whatsapp", "new chat")),
        Rule(Category.SYSTEM, 30, .90f, listOf(
            "system update", "battery", "storage", "security update", "software update"
        ))
    )

    // Market-news apps: not brokers, but their alerts belong with the market, not in OTHER.
    private val marketPackages = setOf("com.divum.moneycontrol")
    private const val EMAIL_PRIORITY_CEILING = 50
    private val BROKER_UTILITY = setOf(Category.DELIVERY, Category.BANKING, Category.PAYMENTS, Category.BILLS)
    private val paymentAppTransferTerms = listOf("received ₹", "received rs", "sent ₹", "paid ₹", "paid to", "requested", "refund", "cashback received")
    private val callChannels = listOf("messaging", "mms", "sms", "whatsapp", "telegram")
    private val brokerPromoTerms = listOf(
        "apply now", "click to apply", "pre apply", "pre-apply", "discover", "new on", "offer", "discount", "cashback",
        "refer", "invite", "open account", "open an account", "limited time", "sale", "coupon", "zero brokerage", "download",
        "join free", "join now", "webinar", "masterclass", "enroll", "enrol", "register now", "ask the expert",
        "check your portfolio", "beating nifty", "beating the nifty"
    )

    // Broker tip/call shorthand: "BUY RENUKA CMP : 23.62 SL : 22.25 TGT : 26", "SELL X @ 120 target 110 stoploss 125".
    private val callSide = Regex("""\b(buy|sell|short(?![\s-]*term)|accumulate)\b""")
    private val callLevels = Regex("""\b(cmp|ltp|sl|tgt|target|targets|stoploss|stop-loss|entry)\b""")
    private fun isTradeCall(text: String): Boolean = callSide.containsMatchIn(text) && callLevels.findAll(text).count() >= 2

    // Strong evidence of the customer's own order returns own-order before the call veto even runs (spec §5.1).
    private val hardCustomerMarkers = listOf(
        Regex("""\bqty\b""", RegexOption.IGNORE_CASE),
        Regex("""\bquantity\b""", RegexOption.IGNORE_CASE),
        Regex("""\border\s*(?:no|id|number)\b""", RegexOption.IGNORE_CASE),
        Regex("""\border\s*#"""),
        Regex("""#\d"""),
        Regex("""\bavg\.?\s*price\b""", RegexOption.IGNORE_CASE),
        Regex("""\baverage\s+price\b""", RegexOption.IGNORE_CASE),
        Regex("""\byou\s+have\b""", RegexOption.IGNORE_CASE),
        Regex("""\bposition\s+(?:opened|closed)\b""", RegexOption.IGNORE_CASE)
    )
    private val weakCustomerMarker = Regex("""\byour\b""", RegexOption.IGNORE_CASE)
    // A possessive order phrase alone marks the customer's own order; research calls don't say "your order".
    private val possessiveOrderPhrase = Regex("""\b(?:your|aapka|apka|aapki|apki|aapke|apke)\b.{0,40}?\b(?:orders?|gtt|positions?|trades?|sip)\b""", RegexOption.IGNORE_CASE)
    // No \b: JVM and ICU disagree on word boundaries around Devanagari combining marks.
    private val devanagariPossessivePhrase = Regex("""आपक[ाीे].{0,40}?(?:ऑर्डर|आर्डर|अॉर्डर|order)""")
    // Leading \b only: inflections ("opened", "successfully") count, while "oversold" can't match "sold".
    private val orderStatus = Regex(
        """\b(?:executed|filled|traded|placed|rejected|cancell?ed|modified|triggered|completed?|confirmed|successful|accepted|open|pending|processed|created|bought|sold|hit|submitted)""",
        RegexOption.IGNORE_CASE
    )
    private val strongExecution = listOf(
        Regex("""\b(?:orders?|trades?|gtt)\b.{0,80}?\b(?:executed|filled|traded|rejected|cancell?ed|placed|triggered|modified|pending|submitted)\b""", RegexOption.IGNORE_CASE),
        Regex("""(?:executed|filled)\s+(?:at|@)""", RegexOption.IGNORE_CASE),
        Regex("""\bbought\s+\d+\s+shares?\b""", RegexOption.IGNORE_CASE),
        Regex("""\bsip\b.{0,60}?\bprocessed\b""", RegexOption.IGNORE_CASE),
        Regex("""\btrades?\s+executed\b""", RegexOption.IGNORE_CASE)
    )
    // Execution/status vocabulary, never a tipster's call vocabulary ("buy order", "stop loss", "target hit" stay out on purpose).
    private val classifierExecutionTerms = listOf(
        "trade confirmation", "order executed", "order filled", "trade executed",
        "executed at", "filled at", "quantity executed", "position opened", "position closed",
        "order rejected", "order cancelled", "order canceled",
        "sent to exchange", "is active", "execute ho gaya", "ho gaya", "insufficient margin"
    )

    /** A trade call reads like a call (side + symbol + levels); an own-order notification never does. */
    private fun looksLikeCall(title: String, body: String, text: String): Boolean =
        isTradeCall(text) || TradeCallParser.parse(title, body) != null

    fun isOwnOrderEvent(title: String, body: String): Boolean {
        val text = "$title $body".lowercase()
        val strongEvidence = strongExecution.any { it.containsMatchIn(text) } ||
            (hardCustomerMarkers.any { it.containsMatchIn(text) } && orderStatus.containsMatchIn(text)) ||
            possessiveOrderPhrase.containsMatchIn(text) ||
            devanagariPossessivePhrase.containsMatchIn(text) ||
            classifierExecutionTerms.any { text.containsRuleTerm(it) }
        if (strongEvidence) return true
        if (looksLikeCall(title, body, text)) return false
        return weakCustomerMarker.containsMatchIn(text) && orderStatus.containsMatchIn(text)
    }

    private val otpWarning = Regex("""\b(?:never|do\s+not|don'?t)\s+share\s+(?:your\s+|the\s+|any\s+)?otp\b""")

    fun classify(packageName: String, title: String, body: String): Result {
        val normalizedPackage = packageName.trim().lowercase()
        val notificationText = "$title $body".trim().lowercase()

        // OTP is a safety-critical notification type. It must win even when a
        // broker package or other text also contains trading-looking language.
        val otpRule = rules.first { it.category == Category.OTP }
        // Tip SMS often end with "never share your OTP"; that warning alone does not make a call an OTP.
        val otpText = if (TradeCallParser.parse(title, body) != null) notificationText.replace(otpWarning, " ") else notificationText
        if (otpRule.terms.any { term -> otpText.containsRuleTerm(term) }) {
            return Result(otpRule.category, otpRule.priority, otpRule.confidence)
        }

        // A broker package is a source hint, not proof that the notification is
        // a trade. Require an actual trading signal before routing it to Marksy.
        val tradingRule = rules.first { it.category == Category.TRADING }
        // Broker and market-news apps: a call or execution is TRADING, a call-to-action is the app's own
        // marketing, and everything else (holdings alerts, research views, IPO notices, market moves) is MARKET.
        if (normalizedPackage in tradingPackages || normalizedPackage in marketPackages) {
            val execution = normalizedPackage in tradingPackages && tradingRule.terms.any { term -> notificationText.containsRuleTerm(term) }
            if (execution || isTradeCall(notificationText) || TradeCallParser.parse(title, body) != null) return Result(tradingRule.category, tradingRule.priority, tradingRule.confidence)
            // Explicit utility messages (welcome-kit delivery, funds credited, bills) keep their own category.
            rules.firstOrNull { it.category in BROKER_UTILITY && it.terms.any { term -> notificationText.containsRuleTerm(term) } }
                ?.let { return Result(it.category, it.priority, it.confidence) }
            if (brokerPromoTerms.any { notificationText.containsRuleTerm(it) }) return Result(Category.PROMOTIONS, 20, .85f)
            return Result(Category.MARKET, 60, .80f)
        }
        // Calls also arrive by SMS and chat. Only a fully parsed call (side, symbol, two price levels) counts,
        // so ordinary messages that say "buy" never become trades.
        if (callChannels.any { normalizedPackage.contains(it) } && TradeCallParser.parse(title, body) != null) {
            return Result(tradingRule.category, tradingRule.priority, .85f)
        }

        val haystack = "$normalizedPackage $notificationText"
        val rule = rules.firstOrNull { candidate ->
            candidate.category != Category.TRADING && candidate.terms.any { term -> haystack.containsRuleTerm(term) }
        }
        val hinted = packageHints.firstOrNull { (token, _) -> normalizedPackage.contains(token) }?.second
        // In a mail app, weak words ("reminder", "meeting") describe the email; dues, money and promos still win.
        if (rule != null && hinted == Category.EMAIL && rule.priority <= EMAIL_PRIORITY_CEILING && rule.category != Category.PROMOTIONS) {
            return Result(Category.EMAIL, 48, .82f)
        }
        if (rule != null) return Result(rule.category, rule.priority, rule.confidence)

        // Fallback: infer from the source app when the text alone was inconclusive.
        // Modest priority/confidence marks it as a weaker, package-only inference.
        // Payment apps mostly push marketing (SIPs, loans, insurance); only money movement is a payment.
        if (hinted == Category.PAYMENTS && paymentAppTransferTerms.none { notificationText.containsRuleTerm(it) }) {
            return Result(Category.PROMOTIONS, 20, .70f)
        }
        if (hinted != null) {
            val hintPriority = (rules.firstOrNull { it.category == hinted }?.priority ?: 25).coerceAtMost(50)
            return Result(hinted, hintPriority, .65f)
        }

        return Result(Category.OTHER, 10, .50f)
    }

    /**
     * Matches phrases as substrings but requires standalone boundaries for a
     * single alphanumeric word. This prevents short signals such as "otp",
     * "upi", or "sale" from matching unrelated words such as "stop", while
     * preserving natural phrase matching for signals like "order executed".
     */
    private fun String.containsRuleTerm(term: String): Boolean {
        val normalizedTerm = term.trim().lowercase()
        if (normalizedTerm.isBlank()) return false
        if (normalizedTerm.any(Char::isWhitespace)) return contains(normalizedTerm)

        var start = indexOf(normalizedTerm)
        while (start >= 0) {
            val end = start + normalizedTerm.length
            val before = start == 0 || !this[start - 1].isLetterOrDigit()
            val after = end == length || !this[end].isLetterOrDigit()
            if (before && after) return true
            start = indexOf(normalizedTerm, start + 1)
        }
        return false
    }
}
