package com.marksy.os.ui

/** Card text for a captured notification: enough of the message to act on or discard it. */
object EventText {
    // Scoring internals; they say nothing about the message itself.
    private val genericReasons = setOf(
        "Recent", "Standard notification", "High classification confidence", "High base priority", "Trading event"
    )

    /** Body without a leading "SENDER:" that repeats the title (SMS apps prefix every message with it). */
    fun body(title: String, body: String): String {
        val t = title.trim()
        val b = body.trim()
        if (t.isEmpty() || !b.startsWith("$t:", ignoreCase = true)) return b
        return b.substring(t.length + 1).trim()
    }

    fun usefulReasons(reasons: List<String>): List<String> = reasons.filterNot { it in genericReasons }
}
