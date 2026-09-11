package com.marksy.os.notification

object NotificationClassifier {
    enum class Category {
        TRADING, BANKING, BILLS, PAYMENTS, OTP, REMINDERS, MESSAGES,
        WORK, DELIVERY, PROMOTIONS, SYSTEM, OTHER
    }

    data class Result(val category: Category, val priority: Int, val confidence: Float)

    private data class Rule(val category: Category, val priority: Int, val confidence: Float, val terms: List<String>)

    private val rules = listOf(
        Rule(Category.OTP, 90, .98f, listOf("otp", "one time password", "verification code")),
        Rule(Category.TRADING, 100, .96f, listOf("order executed", "order filled", "buy order", "sell order", "trade executed", "position", "stop loss", "target hit", "market alert", "upstox", "icici direct", "et money")),
        Rule(Category.BANKING, 80, .92f, listOf("credited", "debited", "account balance", "bank alert", "withdrawn", "deposit")),
        Rule(Category.PAYMENTS, 75, .90f, listOf("upi", "payment successful", "payment failed", "transaction successful", "paid")),
        Rule(Category.BILLS, 65, .88f, listOf("bill due", "bill payment", "electricity bill", "recharge due", "invoice")),
        Rule(Category.DELIVERY, 55, .90f, listOf("out for delivery", "delivered", "shipment", "delivery", "courier")),
        Rule(Category.WORK, 50, .82f, listOf("meeting", "calendar", "slack", "teams", "deadline")),
        Rule(Category.REMINDERS, 45, .80f, listOf("reminder", "remind me", "alarm")),
        Rule(Category.PROMOTIONS, 20, .90f, listOf("sale", "offer", "discount", "deal", "coupon", "cashback")),
        Rule(Category.MESSAGES, 40, .75f, listOf("new message", "message from", "whatsapp")),
        Rule(Category.SYSTEM, 30, .90f, listOf("system update", "battery", "storage", "security update"))
    )

    fun classify(packageName: String, title: String, body: String): Result {
        val haystack = "$packageName $title $body".lowercase()
        val rule = rules.firstOrNull { candidate -> candidate.terms.any(haystack::contains) }
        return rule?.let { Result(it.category, it.priority, it.confidence) }
            ?: Result(Category.OTHER, 10, .50f)
    }
}
