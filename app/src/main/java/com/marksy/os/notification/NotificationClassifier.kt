package com.marksy.os.notification

object NotificationClassifier {
    /** Bump when rules change so stored events are reclassified once on next launch. */
    const val VERSION = 13

    enum class Category {
        TRADING, BANKING, BILLS, PAYMENTS, OTP, REMINDERS, MESSAGES,
        WORK, EMAIL, DELIVERY, PROMOTIONS, SYSTEM, MARKET, OTHER
    }

    data class Result(val category: Category, val priority: Int, val confidence: Float, val reason: String? = null)

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
    private val brokerPromoTerms = listOf(
        "apply now", "click to apply", "pre apply", "pre-apply", "discover", "new on", "offer", "discount", "cashback",
        "refer", "invite", "open account", "open an account", "limited time", "sale", "coupon", "zero brokerage", "download",
        "join free", "join now", "webinar", "masterclass", "enroll", "enrol", "register now", "ask the expert",
        "check your portfolio", "beating nifty", "beating the nifty"
    )

    // A tip's "never share your OTP" footer alone doesn't make it an OTP: a real OTP names no price levels.
    private val priceLevels = Regex("""\b(cmp|ltp|sl|tgt|target|targets|stoploss|stop-loss|entry)\b""")

    // Strong evidence of the customer's own order (spec §5.1); since 4b no call veto guards the weak rule either.
    private val hardCustomerMarkers = listOf(
        Regex("""\bqty\b""", RegexOption.IGNORE_CASE),
        Regex("""\bquantity\b""", RegexOption.IGNORE_CASE),
        Regex("""\border\s*(?:no|id|number)\b""", RegexOption.IGNORE_CASE),
        Regex("""\border\s*#"""),
        Regex("""#\d"""),
        Regex("""\bavg\.?\s*price\b""", RegexOption.IGNORE_CASE),
        Regex("""\baverage\s+price\b""", RegexOption.IGNORE_CASE),
        Regex("""\byou\s+have\b""", RegexOption.IGNORE_CASE),
        Regex("""\bposition\s+(?:opened|closed)\b""", RegexOption.IGNORE_CASE),
        // 4b pre-merge: broker order types a tipster's call never names.
        Regex("""\b(?:gtt|oco|amo|forever)\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(?:bracket|cover|super)\s+orders?\b""", RegexOption.IGNORE_CASE)
    )
    private val weakCustomerMarker = Regex("""\byour\b""", RegexOption.IGNORE_CASE)
    // A possessive order phrase alone marks the customer's own order; research calls don't say "your order".
    private val possessiveOrderPhrase = Regex("""\b(?:your|aapka|apka|aapki|apki|aapke|apke)\b.{0,40}?\b(?:orders?|gtt|positions?|trades?|sip)\b""", RegexOption.IGNORE_CASE)
    // No \b: JVM and ICU disagree on word boundaries around Devanagari combining marks.
    private val devanagariPossessivePhrase = Regex("""आपक[ाीे].{0,40}?(?:ऑर्डर|आर्डर|अॉर्डर|order)""")
    // Leading \b only: inflections ("opened", "successfully") count, while "oversold" can't match "sold".
    private val orderStatus = Regex(
        """\b(?:executed|filled|traded|placed|rejected|cancell?ed|modified|triggered|completed?|confirmed|successful|accepted|open|pending|processed|created|bought|sold|hit|submitted|queued|done)""",
        RegexOption.IGNORE_CASE
    )
    private val strongExecution = listOf(
        Regex(
            """\b(?:orders?|trades?|gtt)\b.{0,80}?\b(?:executed|filled|traded|rejected|cancell?ed|placed|triggered|modified|pending|submitted|created|confirmed|accepted|complete|completed|successful|open|hit|done|queued)\b""",
            RegexOption.IGNORE_CASE
        ),
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

    // 4b pre-merge: a notification that opens with its execution status ("Executed: BUY 10 INFY") is an own order.
    private val leadingExecution = Regex("""^(?:executed|order complete|order completed|order executed)\s*[:!-]""")
    // A side, an integer quantity and an upper-case symbol ("SELL 10 RELIANCE") is an order; a call prices a symbol instead. Original case.
    private val sideQuantitySymbol = Regex("""\b(?i:buy|sell|bought|sold)\s+\d+\s+(?:(?i:shares?)\s+(?:(?i:of)\s+)?)?[A-Z][A-Z0-9&-]+""")

    fun isOwnOrderEvent(title: String, body: String): Boolean {
        val text = "$title $body".lowercase()
        val strongEvidence = strongExecution.any { it.containsMatchIn(text) } ||
            listOf(title, body).any { leadingExecution.containsMatchIn(it.trim().lowercase()) } ||
            sideQuantitySymbol.containsMatchIn("$title $body") ||
            (hardCustomerMarkers.any { it.containsMatchIn(text) } && orderStatus.containsMatchIn(text)) ||
            possessiveOrderPhrase.containsMatchIn(text) ||
            devanagariPossessivePhrase.containsMatchIn(text) ||
            classifierExecutionTerms.any { text.containsRuleTerm(it) }
        if (strongEvidence) return true
        return weakCustomerMarker.containsMatchIn(text) && orderStatus.containsMatchIn(text)
    }

    // The customer's own holdings, portfolio and account alerts never leave the phone, with or without "your" (spec §2.11, 4b review C1b).
    private val ownAccountEvents = listOf(
        Regex("""\byour\s+(?:stocks?|holdings?|portfolio|positions?|watchlist|funds?|margin|account|a/c|demat|sips?|mandates?|pledges?|ledger|p&l|pnl|investments?)\b"""),
        Regex("""\bprice\s+alerts?\b|\balerts?\s+triggered\b|\bp\s*&\s*l\b|\bpnl\b|\bportfolios?\b|\bholdings?\b|\bnet\s*worth\b"""),
        Regex("""\bdividends?\b|\bpayouts?\b|\bredemptions?\b|\ballot(?:ted|ments?)\b|\bbids?\s+placed\b|\bmargin\s+shortfall\b|\bfunds?\s+added\b|\bcontract\s+notes?\b"""),
        Regex("""\bsips?\b.{0,60}?\bdue\b"""),
        Regex("""\byou(?:\s+(?:own|hold|earned|have)|'ve|’ve)\b"""),
        // 4b pre-merge: an alert the customer set is theirs, even when it reads like a call.
        Regex("""\bwatchlist\s+alerts?\b|\balerts?\s+set\b|\bsmart\s+alerts?\b|\byour\s+alerts?\b""")
    )

    fun isOwnAccountEvent(title: String, body: String): Boolean = "$title $body".lowercase().let { text -> ownAccountEvents.any { it.containsMatchIn(text) } }

    // 4b review M3: a 4-8 digit number that is no price (no level word, ₹ or @ before it) beside OTP, TPIN or code words.
    private val codeWords = Regex("""\b(?:otp|tpin|m?pin|passcode|password|code)\b""")
    private val standaloneNumber = Regex("""(?<![\d.,])\d{4,8}(?![\d.,]*\d)""")
    private val priceBefore = Regex("""(?:\b(?:cmp|ltp|sl|tgt|targets?|entry|stop[\s-]*loss|above|below|around|near|at|rs\.?|inr)|₹|@)[\s:=\-]*$""")

    // User rule 2026-10-02: only a sure code is dropped, so amounts, masks (XX1234), dates, refs and phone numbers never count.
    private val secretCodeWords = Regex("""\b(?:otp|one[\s-]?time\s+(?:password|passcode|pin|code)|(?:verification|security|login|authentication|auth)\s+code|passcode|password|t?pin|mpin)\b""")
    private val codeCandidate = Regex("""(?<![\w.,#*/])(?<!\d-)\d{4,8}(?![\d.,]*\d)(?![a-z])(?![-/]\d)(?!\s*(?:hrs?|hours?|mins?|minutes?|am|pm|%|/-))""")
    private val notACodeBefore = Regex(
        """(?:\b(?:rs|inr|usd|amt|amount|bal|balance|limit|a/?c|acct|account|card|ending(?:\s+(?:in|with))?|no|number|ref|reference|txn|transaction|order|awb|id|upi|call|sms|dial|""" +
            """debited|credited|spent|paid|sent|received|withdrawn|worth|for|cmp|ltp|sl|tgt|targets?|entry|stop[\s-]*loss|above|below|around|near|at|qty|""" +
            """jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|june?|july?|aug(?:ust)?|sept?(?:ember)?|oct(?:ober)?|nov(?:ember)?|dec(?:ember)?)\.?|₹|\$|@)[\s:=#.\-]*$"""
    )

    fun isOneTimeCode(title: String, body: String): Boolean {
        val text = "$title $body".lowercase()
        return secretCodeWords.containsMatchIn(text) &&
            codeCandidate.findAll(text).any { m -> !notACodeBefore.containsMatchIn(text.substring(maxOf(0, m.range.first - 24), m.range.first)) }
    }

    fun carriesOneTimeCode(title: String, body: String): Boolean = carriesOneTimeCode("$title $body".lowercase())

    private fun carriesOneTimeCode(text: String): Boolean = codeWords.containsMatchIn(text) &&
        standaloneNumber.findAll(text).any { m -> !priceBefore.containsMatchIn(text.substring(maxOf(0, m.range.first - 16), m.range.first)) }

    private val otpWarning = Regex("""\b(?:never|do\s+not|don'?t)\s+share\s+(?:your\s+|the\s+|any\s+)?otp\b""")

    // Android 15+ sensitive-notification protection (not the lock-screen setting) swaps the real body for this
    // placeholder when Marksy lacks the RECEIVE_SENSITIVE_NOTIFICATIONS app-op; it carries no signal at all.
    private const val REDACTED_BODY_PREFIX = "sensitive notification content"
    const val REDACTION_REASON = "Content hidden by Android sensitive-notification protection"

    // fix/trading-call-any-source: a structured call reads as TRADING regardless of source package
    // (OTP and own-order/account precedence, enforced by their own callers, still win as they do today).
    private val callDirectionWord = Regex("""\b(?:buy|sell|long|short)\b""", RegexOption.IGNORE_CASE)
    private val callLevelOrStopWord = Regex("""\b(?:target|tgt|tp|stop\s+loss|stoploss|stop-loss|sl)\b""", RegexOption.IGNORE_CASE)
    private val callExplicitHit = Regex(
        """\b(?:target|tgt)\s+(?:\d+\s+)?hit\b|\b(?:sl|stop\s+loss|stoploss|stop-loss)\s+hit\b""",
        RegexOption.IGNORE_CASE
    )

    // A lone direction or level word is too weak ("hit your savings target"); only the pair is distinctive.
    private fun isStructuredTradingCall(text: String): Boolean =
        callExplicitHit.containsMatchIn(text) || (callDirectionWord.containsMatchIn(text) && callLevelOrStopWord.containsMatchIn(text))

    fun classify(packageName: String, title: String, body: String): Result {
        val normalizedPackage = packageName.trim().lowercase()
        val notificationText = "$title $body".trim().lowercase()

        // Redacted content is not a signal; must never be reported as a confident MARKET/TRADING hit.
        if (body.trim().lowercase().startsWith(REDACTED_BODY_PREFIX)) {
            return Result(Category.OTHER, 10, .15f, REDACTION_REASON)
        }

        // OTP is a safety-critical notification type. It must win even when a
        // broker package or other text also contains trading-looking language.
        val otpRule = rules.first { it.category == Category.OTP }
        val otpText = if (priceLevels.findAll(notificationText).count() >= 2 && !carriesOneTimeCode(notificationText)) notificationText.replace(otpWarning, " ") else notificationText
        if (otpRule.terms.any { term -> otpText.containsRuleTerm(term) } && isOneTimeCode(title, body)) {
            return Result(otpRule.category, otpRule.priority, otpRule.confidence)
        }

        // A broker package is a source hint, not proof that the notification is
        // a trade. Require an actual trading signal before routing it to Marksy.
        val tradingRule = rules.first { it.category == Category.TRADING }
        // Broker and market-news apps: an execution is TRADING, a call-to-action is PROMOTIONS, anything else (calls included) MARKET.
        if (normalizedPackage in tradingPackages || normalizedPackage in marketPackages) {
            val execution = normalizedPackage in tradingPackages && tradingRule.terms.any { term -> notificationText.containsRuleTerm(term) }
            if (execution) return Result(tradingRule.category, tradingRule.priority, tradingRule.confidence)
            // Explicit utility messages (welcome-kit delivery, funds credited, bills) keep their own category.
            rules.firstOrNull { it.category in BROKER_UTILITY && it.terms.any { term -> notificationText.containsRuleTerm(term) } }
                ?.let { return Result(it.category, it.priority, it.confidence) }
            if (brokerPromoTerms.any { notificationText.containsRuleTerm(it) }) return Result(Category.PROMOTIONS, 20, .85f)
            return Result(Category.MARKET, 60, .80f)
        }

        // fix/trading-call-any-source: distinctive call language (direction + level/stop, or an explicit
        // "target hit"/"sl hit") is TRADING no matter which app delivered it.
        if (isStructuredTradingCall(notificationText)) {
            return Result(tradingRule.category, tradingRule.priority, tradingRule.confidence)
        }

        // The OTP rule leads this list too, so it must not see a tip's stripped footer either.
        val haystack = "$normalizedPackage $otpText"
        val rule = rules.firstOrNull { candidate ->
            candidate.category != Category.TRADING && candidate.category != Category.OTP && candidate.terms.any { term -> haystack.containsRuleTerm(term) }
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
