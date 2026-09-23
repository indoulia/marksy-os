package com.marksy.os.ui

import com.marksy.os.data.local.NotificationEventEntity
import com.marksy.os.gateway.MarketSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneOffset

class AskMarksyEngineTest {
    private val zone = ZoneOffset.UTC
    private val now = LocalDateTime.of(2026, 9, 23, 18, 0).toInstant(zone).toEpochMilli()
    private var seq = 0
    private fun event(category: String, title: String, source: String = "App", pkg: String = "p", priority: Int = 1, hour: Int = 10) =
        NotificationEventEntity(
            sourcePackage = pkg, sourceName = source, sourceKey = "k${seq++}", eventFingerprint = "f$seq",
            title = title, body = "body $title", postedAt = LocalDateTime.of(2026, 9, 23, hour, 0).toInstant(zone).toEpochMilli(),
            category = category, priority = priority, confidence = 1f, isTrading = category == "TRADING"
        )

    // Regression: every Ask Marksy prompt replied "Your question is queued" and never answered.
    @Test
    fun whatsappQuestionSummarisesRealWhatsAppMessages() {
        val events = listOf(
            event("MESSAGES", "Mom", "WhatsApp", "com.whatsapp", hour = 9),
            event("MESSAGES", "Mom", "WhatsApp", "com.whatsapp", hour = 11),
            event("MESSAGES", "Team", "WhatsApp", "com.whatsapp", hour = 12),
            event("EMAIL", "Invoice", "Gmail")
        )

        val answer = AskMarksyEngine.answer("Summarize my WhatsApp", events, null, now, zone)

        assertTrue(answer.text, answer.text.startsWith("3 WhatsApp messages today"))
        assertTrue(answer.text, answer.text.contains("Mom (2)"))
        assertEquals("Team", answer.events.first().title)
    }

    @Test
    fun tradingQuestionUsesMarksyPicks() {
        val market = MarketSnapshot(
            "OPEN", emptyList(),
            listOf(MarketSnapshot.MarketOpportunity("HAL", "HAL", 4100.0, 1.0, 4090.0, 4300.0, 3990.0, 5.1, 5, 0.8, 70.0, "ACTIVE")),
            emptyList(), emptyList(), null
        )

        val answer = AskMarksyEngine.answer("Show trading opportunities", emptyList(), market, now, zone)

        assertTrue(answer.text, answer.text.contains("HAL"))
        assertTrue(answer.text, answer.text.contains("target ₹4,300"))
    }

    private fun teams(title: String, body: String, hour: Int = 10) =
        event("WORK", title, "Teams", "com.microsoft.teams", hour = hour).copy(body = body)

    // Regression (device, 2026-09-23): speech gave "mate" for "Matt"; search demanded every word incl. "was"/"saying" and found nothing.
    @Test
    fun personQuestionOnNamedAppToleratesSpeechSlipsAndFillers() {
        val events = listOf(
            teams("Product: Matt Carter", "Product\nMatt Carter: Alex - quick update", hour = 12),
            teams("Release: Jo Lee", "Release\nJo Lee: thanks for finding it", hour = 11),
            event("MESSAGES", "Carter", "Telegram")
        )

        val answer = AskMarksyEngine.answer("what mate Carter was saying on teams", events, null, now, zone)

        assertEquals("Product: Matt Carter", answer.events.first().title)
        assertTrue(answer.events.all { it.sourceName == "Teams" })
    }

    // Regression (device, 2026-09-23): "messages … from Teams" ignored Teams and listed Telegram/Outlook.
    @Test
    fun howManyFromNamedAppCountsThatAppOnly() {
        val events = listOf(teams("A", "a"), teams("B", "b"), event("MESSAGES", "Mom", "Telegram"))

        val answer = AskMarksyEngine.answer("how many messages I have received today from own teams", events, null, now, zone)

        assertTrue(answer.text, answer.text.startsWith("2 Teams notifications today"))
        assertTrue(answer.events.all { it.sourceName == "Teams" })
    }

    @Test
    fun unknownQuestionSearchesNotifications() {
        val events = listOf(event("DELIVERY", "Your Swiggy order is on the way"), event("EMAIL", "Weekly report"))

        val answer = AskMarksyEngine.answer("swiggy order", events, null, now, zone)

        assertEquals(1, answer.events.size)
        assertTrue(answer.text, answer.text.startsWith("Found 1 notification"))
    }
}
