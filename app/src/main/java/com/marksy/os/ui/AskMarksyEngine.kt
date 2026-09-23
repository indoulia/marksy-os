package com.marksy.os.ui

import com.marksy.os.data.local.NotificationEventEntity
import com.marksy.os.gateway.MarketSnapshot
import com.marksy.os.intelligence.EventIntelligence
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

/** Answers Ask Marksy questions from captured notifications and the Marksy market snapshot only. */
object AskMarksyEngine {
    data class Answer(val text: String, val events: List<NotificationEventEntity> = emptyList())

    private const val MAX_EVENTS = 5
    private const val ATTENTION_SCORE = 70
    private const val DAY_MS = 24 * 60 * 60 * 1000L
    private val stopWords = setOf("the", "and", "any", "show", "what", "did", "my", "me", "for", "about", "from", "with", "today", "are", "is", "there")

    fun answer(
        question: String,
        events: List<NotificationEventEntity>,
        market: MarketSnapshot?,
        nowMillis: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault()
    ): Answer {
        val q = question.lowercase(Locale.ROOT)
        val active = events.filterNot { it.archived }.sortedByDescending { it.postedAt }
        val today = active.filter { Instant.ofEpochMilli(it.postedAt).atZone(zone).toLocalDate() == Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate() }
        return when {
            "whatsapp" in q -> messages(today.filter { it.sourcePackage.contains("whatsapp", ignoreCase = true) }, "WhatsApp messages")
            listOf("message", "chat").any { it in q } -> messages(today.filter { it.category.equals("MESSAGES", ignoreCase = true) }, "messages")
            listOf("trad", "stock", "market", "opportunit", "nifty", "sensex").any { it in q } -> trading(today, market)
            listOf("email", "mail").any { it in q } -> emails(today, nowMillis)
            listOf("miss", "attention", "important", "urgent").any { it in q } -> missed(active, nowMillis)
            listOf("happen", "summary", "summar", "overview").any { it in q } ->
                Answer(HomeSummary.text(DailyDigestModel.build(active, nowMillis, zone), market), attention(today, nowMillis))
            else -> search(active, q)
        }
    }

    private fun messages(matching: List<NotificationEventEntity>, noun: String): Answer {
        if (matching.isEmpty()) return Answer("No $noun captured today.")
        val senders = matching.groupingBy { it.title.ifBlank { it.sourceName } }.eachCount()
            .toList().sortedByDescending { it.second }.take(3)
        return Answer(
            "${matching.size} $noun today. Most from " + senders.joinToString { (name, count) -> "$name ($count)" } + ".",
            matching.take(MAX_EVENTS)
        )
    }

    private fun trading(today: List<NotificationEventEntity>, market: MarketSnapshot?): Answer {
        val captured = today.filter { it.isTrading }
        val parts = mutableListOf<String>()
        val picks = market?.opportunities.orEmpty().take(3)
        if (picks.isNotEmpty()) {
            parts += "Marksy picks: " + picks.joinToString("; ") { pick ->
                "${pick.symbol} target ₹${whole(pick.targetPrice)}, stop ₹${whole(pick.stopLoss)}" +
                    (pick.upsidePct?.let { ", upside ${String.format(Locale.US, "%.1f%%", it)}" } ?: "")
            } + "."
        } else if (market == null) {
            parts += "Marksy market data isn't available, so there are no live picks."
        } else {
            parts += "Marksy has no open opportunities right now."
        }
        market?.indices?.firstOrNull()?.let { parts += "${it.name} ${String.format(Locale.US, "%+.2f%%", it.changePct)}." }
        parts += if (captured.isEmpty()) "No trading notifications captured today." else "${captured.size} trading notifications captured today."
        return Answer(parts.joinToString(" "), captured.take(MAX_EVENTS))
    }

    private fun emails(today: List<NotificationEventEntity>, nowMillis: Long): Answer {
        val emails = today.filter { it.category.equals("EMAIL", ignoreCase = true) }
        if (emails.isEmpty()) return Answer("No emails captured today.")
        val important = attention(emails, nowMillis)
        val text = if (important.isEmpty()) "${emails.size} emails today, none flagged important."
        else "${emails.size} emails today, ${important.size} flagged important."
        return Answer(text, important.ifEmpty { emails }.take(MAX_EVENTS))
    }

    private fun missed(active: List<NotificationEventEntity>, nowMillis: Long): Answer {
        val recent = attention(active.filter { nowMillis - it.postedAt in 0..DAY_MS }, nowMillis)
        return if (recent.isEmpty()) Answer("Nothing important in the last 24 hours.")
        else Answer("${recent.size} important notification${if (recent.size == 1) "" else "s"} in the last 24 hours.", recent.take(MAX_EVENTS))
    }

    private fun search(active: List<NotificationEventEntity>, q: String): Answer {
        val terms = q.split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length >= 3 && it !in stopWords }
        if (terms.isEmpty()) return Answer("Ask about today, WhatsApp, messages, emails, trading, or what you missed.")
        val hits = active.filter { event ->
            val haystack = "${event.title} ${event.body} ${event.sourceName}".lowercase(Locale.ROOT)
            terms.all { it in haystack }
        }
        return if (hits.isEmpty()) Answer("No notifications mention \"${terms.joinToString(" ")}\".")
        else Answer("Found ${hits.size} notification${if (hits.size == 1) "" else "s"} mentioning \"${terms.joinToString(" ")}\".", hits.take(MAX_EVENTS))
    }

    private fun attention(events: List<NotificationEventEntity>, nowMillis: Long): List<NotificationEventEntity> =
        events.map { it to EventIntelligence.analyze(it, nowMillis).attentionScore }
            .filter { it.second >= ATTENTION_SCORE }
            .sortedByDescending { it.second }
            .map { it.first }

    private fun whole(value: Double) = String.format(Locale.getDefault(), "%,.0f", value)
}
