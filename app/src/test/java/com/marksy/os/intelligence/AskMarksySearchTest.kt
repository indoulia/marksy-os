package com.marksy.os.intelligence

import com.marksy.os.data.local.ContextEntity
import com.marksy.os.data.local.NotificationEventEntity
import com.marksy.os.data.local.PlanItemEntity
import com.marksy.os.intelligence.AskMarksy.Intent
import com.marksy.os.intelligence.AskMarksy.Page
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneOffset

class AskMarksySearchTest {
    private val zone = ZoneOffset.UTC
    // Thursday 24 Sep 2026, 18:00 UTC.
    private val now = LocalDateTime.of(2026, 9, 24, 18, 0).toInstant(zone).toEpochMilli()
    private val hour = 3_600_000L
    private val day = 24 * hour
    private var seq = 0L

    private fun event(category: String, title: String, body: String, at: Long, pkg: String): NotificationEventEntity {
        val base = NotificationEventEntity(id = ++seq, sourcePackage = pkg, sourceName = pkg.substringAfterLast('.'), sourceKey = "k$seq", eventFingerprint = "f$seq",
            title = title, body = body, postedAt = at, category = category, priority = 60, confidence = .9f, isTrading = category == "TRADING")
        return base.copy(intelligenceJson = EventNormalizer.toJson(EventNormalizer.normalize(base, zone), null))
    }

    private fun plan(kind: String, title: String, dueAt: Long?, status: String = "TODO", amount: Long? = null) =
        PlanItemEntity(id = ++seq, kind = kind, title = title, amountMinor = amount, dueAt = dueAt, recurrence = "NONE", status = status, origin = "MANUAL", createdAt = 0, updatedAt = 0)

    private class Store(
        val rows: List<NotificationEventEntity> = emptyList(),
        val plans: List<PlanItemEntity> = emptyList(),
        val symbols: Map<String, String> = emptyMap()
    ) : AskMarksy.Retriever {
        override suspend fun events(from: Long, to: Long, limit: Int) = rows.filter { it.postedAt in from until to }.sortedByDescending { it.postedAt }.take(limit)
        override suspend fun entities(name: String) = emptyList<ContextEntity>()
        override suspend fun eventIdsFor(entityId: Long) = emptyList<Long>()
        override suspend fun planItems() = plans
        override suspend fun resolveSymbol(text: String) = symbols[text.lowercase()]
    }

    private fun ask(store: Store, text: String) = runBlocking { AskMarksy.answer(AskMarksy.parse(text, null, now, zone), store, now, zone = zone) }

    @Test
    fun countsEmailsAcrossMailAppsWithTopSendersDefaultingToToday() {
        val store = Store(listOf(
            event("EMAIL", "Rahul Sharma", "Q3 plan", now - hour, "com.google.android.gm"),
            event("EMAIL", "HDFC Bank", "Statement ready", now - 2 * hour, "com.microsoft.office.outlook"),
            event("EMAIL", "Rahul Sharma", "Re: Q3 plan", now - 3 * hour, "com.google.android.gm"),
            event("EMAIL", "Old", "yesterday", now - 30 * hour, "com.google.android.gm"),
            event("MESSAGES", "Rahul", "hi", now - hour, "com.whatsapp")
        ))
        val today = ask(store, "How many emails have I received?")
        assertEquals(Intent.SOURCE, today.query.intent)
        assertEquals("email", today.query.channel)
        assertEquals("today", today.query.range.label)
        assertEquals("3 emails today. Most from Rahul Sharma (2), HDFC Bank (1).", today.headline)
        assertEquals(4, ask(store, "how many emails this week").derivedFromEventIds.size)
    }

    @Test
    fun teamsAndPersonOnAChannelAnswerYesOrNo() {
        val teams = event("WORK", "Amit Kumar", "Can you review the deck?", now - 2 * hour, "com.microsoft.teams")
        val mail = event("EMAIL", "Rahul Sharma", "Invoice attached", now - 2 * day, "com.google.android.gm")
        val chat = event("MESSAGES", "Rahul", "Lunch?", now - hour, "com.whatsapp")
        val store = Store(listOf(teams, mail, chat))

        val onTeams = ask(store, "What did I receive on Teams today?")
        assertEquals(Intent.SOURCE, onTeams.query.intent)
        assertEquals(listOf(teams.id), onTeams.derivedFromEventIds)

        val fromRahul = ask(store, "Did I receive any email from Rahul this week?")
        assertEquals(Intent.FROM_PERSON, fromRahul.query.intent)
        assertEquals("rahul", fromRahul.query.subject)
        assertEquals(listOf(mail.id), fromRahul.derivedFromEventIds)
        assertTrue(fromRahul.headline, fromRahul.headline.startsWith("Yes, 1 email from"))

        val none = ask(store, "Did I get any email from Priya?")
        assertTrue(none.noResult)
        assertTrue(none.headline, none.headline.startsWith("No emails from"))

        assertEquals(listOf(chat.id), ask(store, "messages from Rahul on WhatsApp").derivedFromEventIds)
    }

    @Test
    fun sitemapNavigatesToPagesAndTabs() {
        fun nav(text: String) = ask(Store(), text).also { assertEquals(text, Intent.NAVIGATE, it.query.intent) }.action!!
        assertEquals(AskMarksy.Action("Open Plan · Reminders", Page.PLAN, "Reminders", auto = true), nav("open reminders"))
        assertEquals("Calls" to Page.TRADING, nav("go to trade calls").let { it.arg to it.page })
        assertEquals("IPOS" to Page.MARKET, nav("take me to IPOs").let { it.arg to it.page })
        assertEquals("Board" to Page.PLAN, nav("open my to do board").let { it.arg to it.page })
        assertEquals(Page.SETTINGS, nav("open settings").page)

        val help = ask(Store(), "What can you do?")
        assertEquals(Intent.HELP, help.query.intent)
        assertTrue(help.headline.contains("IPOs") && help.headline.contains("Plan"))
        assertNull(help.action)
    }

    // Sitemap args are handed straight to the tab state, so they must be real tab names.
    @Test
    fun sitemapArgsAreRealTabNames() {
        AskMarksy.SITEMAP.forEach { (word, a) ->
            val valid = when (a.page) {
                Page.PLAN -> a.arg == null || a.arg in com.marksy.os.ui.PlanViews
                Page.TRADING -> a.arg == null || a.arg in com.marksy.os.ui.TradingFilters
                Page.MARKET -> a.arg == null || a.arg in com.marksy.os.ui.MarketTab.entries.map { it.name }
                Page.INBOX -> a.arg == null || a.arg in SmartInboxModel.Filter.entries.map { it.name }
                else -> true
            }
            assertTrue("$word -> $a", valid)
        }
    }

    @Test
    fun stockQuestionsResolveTheSymbolOpenItAndListMentions() {
        val call = event("TRADING", "Kishan", "BUY TATAMOTORS CMP 700 SL 680 TGT 740", now - 2 * hour, "com.google.android.apps.messaging")
        val store = Store(listOf(call), symbols = mapOf("tata motors" to "TATAMOTORS", "reliance" to "RELIANCE"))

        val tata = ask(store, "tata motors share price")
        assertEquals(Intent.STOCK, tata.query.intent)
        assertEquals(AskMarksy.Action("Open TATAMOTORS", Page.STOCK, "TATAMOTORS", auto = true), tata.action)
        assertEquals(listOf(call.id), tata.derivedFromEventIds)
        assertEquals(Intent.STOCK, ask(store, "How is Reliance doing today?").query.intent)
        assertEquals(Intent.TRADING, ask(store, "Show trading opportunities").query.intent)

        val unknown = ask(store, "price of foobarxyz")
        assertNull(unknown.action)
        assertTrue(unknown.headline, unknown.headline.startsWith("I couldn't find a listed stock called \"foobarxyz\""))
    }

    @Test
    fun planQuestionsListOpenRemindersByKindWithinTheWindow() {
        val card = plan("CARD_DUE", "ICICI Bank card bill", now + 11 * day, amount = 1_491_700)
        val milk = plan("BILL", "Provilac Milk bill", now - 31 * day, amount = 8_000)
        val bday = plan("BIRTHDAY", "Aisha's birthday", now + 3 * day)
        val done = plan("TASK", "Call plumber", now + day, status = "DONE")
        val rent = plan("BILL", "Rent", now + 40 * day)
        val store = Store(plans = listOf(card, milk, bday, done, rent))

        val upcoming = ask(store, "What reminders are coming up?")
        assertEquals(Intent.PLAN, upcoming.query.intent)
        assertTrue(upcoming.headline, upcoming.headline.startsWith("3 open reminders: Provilac Milk bill ₹80"))
        assertTrue(upcoming.headline.contains("ICICI Bank card bill ₹14,917") && upcoming.headline.contains("Aisha's birthday"))
        assertFalse(upcoming.headline.contains("Rent") || upcoming.headline.contains("plumber"))
        assertEquals(Page.PLAN, upcoming.action!!.page)
        assertFalse(upcoming.action!!.auto)

        assertTrue(ask(store, "Any birthdays this week?").headline.startsWith("1 open reminder: Aisha's birthday"))
        val bills = ask(store, "Which bills are due?")
        assertEquals(Intent.BILLS_DUE, bills.query.intent)
        assertTrue(bills.headline, bills.headline.startsWith("2 open bills: Provilac Milk bill"))
    }
}
