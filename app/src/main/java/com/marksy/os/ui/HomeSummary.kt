package com.marksy.os.ui

import com.marksy.os.gateway.MarketSnapshot
import java.util.Locale
import kotlin.math.abs

/** One-paragraph Home summary built only from captured events and the Marksy market snapshot. */
object HomeSummary {
    /** [liveIndex] (name, change %) from the user's Upstox feed wins over the snapshot's, which can be a day old. */
    fun text(digest: DailyDigest, market: MarketSnapshot?, liveIndex: Pair<String, Double>? = null): String {
        val parts = mutableListOf<String>()
        if (digest.totalNotifications == 0) {
            parts += "No notifications captured yet today."
        } else {
            val attention = digest.attentionEvents.size
            val needs = when (attention) {
                0 -> "none need"
                1 -> "1 needs"
                else -> "$attention need"
            }
            parts += "${digest.totalNotifications} notifications today, $needs your attention."
            if (digest.topSources.isNotEmpty()) {
                parts += "Busiest: " + digest.topSources.take(2).joinToString { (name, count) -> "$name ($count)" } + "."
            }
        }
        (liveIndex ?: market?.indices?.firstOrNull()?.let { it.name to it.changePct })?.let { (name, change) ->
            val direction = when {
                change > 0 -> "up"
                change < 0 -> "down"
                else -> "flat"
            }
            parts += if (direction == "flat") "$name is flat." else "$name is $direction ${pct(abs(change))}."
        }
        market?.opportunities?.firstOrNull()?.let { pick ->
            parts += "Top Marksy pick: ${pick.symbol}, target ₹${String.format(Locale.getDefault(), "%,.0f", pick.targetPrice)}."
        }
        return parts.joinToString(" ")
    }

    private fun pct(value: Double) = String.format(Locale.US, "%.2f%%", value)
}
