package com.marksy.os.notification

object NotificationClassifier {
    enum class Category {
        TRADING, BANKING, BILLS, PAYMENTS, OTP, REMINDERS, MESSAGES,
        WORK, DELIVERY, PROMOTIONS, SYSTEM, OTHER
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
        "com.fivepaisa.trade"
    )

    // High-signal rules come first. Generic words such as "paid" must never
    // override a stronger trading or OTP signal.
    private val rules = listOf(
        Rule(Category.OTP, 90, .98f, listOf("otp", "one time password", "verification code", "verification otp")),
        Rule(Category.TRADING, 100, .96f, listOf(
            "order executed", "order filled", "buy order", "sell order", "trade executed",
            "trade confirmation", "position opened", "position closed", "stop loss", "target hit",
            "market alert", "order rejected", "order cancelled", "order canceled", "executed at",
            "filled at", "quantity executed", "average price", "p&l", "profit and loss"
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
        Rule(Category.REMINDERS, 45, .80f, listOf("reminder", "remind me", "alarm")),
        Rule(Category.PROMOTIONS, 20, .90f, listOf(
            "sale", "offer", "discount", "deal", "coupon", "cashback", "limited time"
        )),
        Rule(Category.MESSAGES, 40, .75f, listOf("new message", "message from", "whatsapp", "new chat")),
        Rule(Category.SYSTEM, 30, .90f, listOf(
            "system update", "battery", "storage", "security update", "software update"
        ))
    )

    fun classify(packageName: String, title: String, body: String): Result {
        val normalizedPackage = packageName.trim().lowercase()
        val notificationText = "$title $body".trim().lowercase()

        // OTP is a safety-critical notification type. It must win even when a
        // broker package or other text also contains trading-looking language.
        val otpRule = rules.first { it.category == Category.OTP }
        if (otpRule.terms.any(notificationText::containsRuleTerm)) {
            return Result(otpRule.category, otpRule.priority, otpRule.confidence)
        }

        // A broker package is a source hint, not proof that the notification is
        // a trade. Require an actual trading signal before routing it to Marksy.
        if (normalizedPackage in tradingPackages) {
            val tradingRule = rules.first { it.category == Category.TRADING }
            if (tradingRule.terms.any(notificationText::containsRuleTerm)) {
                return Result(tradingRule.category, tradingRule.priority, tradingRule.confidence)
            }
        }

        val haystack = "$normalizedPackage $notificationText"
        val rule = rules.firstOrNull { candidate ->
            candidate.category != Category.TRADING && candidate.terms.any(haystack::containsRuleTerm)
        }
        return rule?.let { Result(it.category, it.priority, it.confidence) }
            ?: Result(Category.OTHER, 10, .50f)
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
