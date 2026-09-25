package com.marksy.os.intelligence

import com.marksy.os.data.local.ContextEntity
import com.marksy.os.data.local.NotificationEventEntity
import com.marksy.os.data.local.PlanItemEntity
import com.marksy.os.intelligence.AskMarksy.Intent
import com.marksy.os.intelligence.AskMarksy.Page
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * Ask Marksy evaluation set: a realistic phone's notifications and the questions people actually ask.
 * Every case states the intent and what the answer must say; failures are reported together.
 */
class AskMarksyEvalTest {
    private val zone = ZoneOffset.UTC
    // Friday 25 Sep 2026, 12:00.
    private val now = LocalDateTime.of(2026, 9, 25, 12, 0).toInstant(zone).toEpochMilli()
    private val hour = 3_600_000L
    private val day = 24 * hour
    private var seq = 0L

    private fun ev(category: String, pkg: String, source: String, title: String, body: String, ago: Long): NotificationEventEntity {
        val base = NotificationEventEntity(id = ++seq, sourcePackage = pkg, sourceName = source, sourceKey = "k$seq", eventFingerprint = "f$seq",
            title = title, body = body, postedAt = now - ago, category = category, priority = 60, confidence = .9f, isTrading = category == "TRADING")
        return base.copy(intelligenceJson = EventNormalizer.toJson(EventNormalizer.normalize(base, zone), null))
    }

    private val outlook = "com.microsoft.office.outlook"
    private val gmail = "com.google.android.gm"
    private val teams = "com.microsoft.teams"
    private val whatsapp = "com.whatsapp"
    private val sms = "com.google.android.apps.messaging"

    private val hrMail = ev("EMAIL", outlook, "Outlook", "HR Team", "Diwali celebrations: RSVP by Friday", hour)
    private val joshMail = ev("EMAIL", outlook, "Outlook", "Josh Mau", "Re: CFI-105 specs", 2 * hour)
    private val timesheet = ev("EMAIL", outlook, "Outlook", "MySpace", "Timesheet update reminder for September 23,2026", 3 * hour)
    private val hidden = ev("EMAIL", outlook, "Outlook", "Outlook", "Sensitive notification content hidden", 4 * hour)
    private val rahulMail = ev("EMAIL", gmail, "Gmail", "Rahul Sharma", "Trip photos", 5 * hour)
    private val oldMail = ev("EMAIL", gmail, "Gmail", "Amazon.in", "Your order has shipped", day + hour)
    private val david1 = ev("WORK", teams, "Teams", "NPM795 SU: David Shpil", "Pratap, please review the findings", hour)
    private val david2 = ev("WORK", teams, "Teams", "Design sync: David Shpil", "joining in 5", 2 * hour)
    private val prashant = ev("WORK", teams, "Teams", "Prashant Verma", "ok, will do", 6 * hour)
    private val mom = ev("MESSAGES", whatsapp, "WhatsApp", "Mom", "Did you eat?", hour)
    private val rahulChat = ev("MESSAGES", whatsapp, "WhatsApp", "Rahul", "Lunch at 1?", 3 * hour)
    private val family = ev("MESSAGES", whatsapp, "WhatsApp", "Family Group: Aisha", "See you Sunday!", day + 2 * hour)
    private val debit = ev("BANKING", sms, "Messages", "AD-HDFCBK", "Rs 450.00 debited from a/c XX12 to SWIGGY on 25-09-26. UPI Ref 426712345678", 2 * hour)
    private val salary = ev("BANKING", sms, "Messages", "AD-HDFCBK", "Rs 12,000.00 credited to a/c XX12 by NEFT from ACME CORP", 7 * hour)
    private val otp = ev("OTP", sms, "Messages", "VM-ICICIT", "123456 is your OTP for login", hour / 2)
    private val iciciSms = ev("REMINDERS", "com.truecaller", "Truecaller", "₹14,917", "•  ICICI Bank  •  Bill due on 6th Oct SMS from ICICI Bank", 2 * day)
    private val amazonPay = ev("PAYMENTS", "com.phonepe.app", "PhonePe", "Payment successful", "Paid ₹1,250 to Amazon", day + 2 * hour)
    private val amazonParcel = ev("DELIVERY", "in.amazon.mShop.android.shopping", "Amazon", "Out for delivery", "Your package with order ID 403-1234567-7654321 will arrive today", 2 * hour)
    private val flipkartParcel = ev("DELIVERY", "com.flipkart.android", "Flipkart", "Shipped", "Order ID OD1234567890 arriving tomorrow", 5 * hour)
    private val call = ev("TRADING", sms, "Messages", "KISHAN ENTERPRISE", "BUY CROPSTER AGRO CMP 23 SL 21 TGT 27", 3 * hour)
    private val market = ev("MARKET", "com.divum.moneycontrol", "Moneycontrol", "Sensex ends 300 pts higher", "Banks lead the rally", hour)
    private val promo = ev("PROMOTIONS", "in.swiggy.android", "Swiggy", "50% off tonight", "Order now", 2 * hour)
    private val events = listOf(hrMail, joshMail, timesheet, hidden, rahulMail, oldMail, david1, david2, prashant, mom, rahulChat, family,
        debit, salary, otp, iciciSms, amazonPay, amazonParcel, flipkartParcel, call, market, promo)

    private fun plan(kind: String, title: String, due: Long, amount: Long? = null, counterparty: String? = null) =
        PlanItemEntity(id = ++seq, kind = kind, title = title, counterparty = counterparty, amountMinor = amount, dueAt = due, recurrence = "NONE", status = "TODO", origin = "MANUAL", createdAt = 0, updatedAt = 0)

    private val plans = listOf(
        plan("CARD_DUE", "ICICI Bank card bill", now + 11 * day, 1_491_700, "ICICI Bank"),
        plan("BILL", "Provilac Milk bill", now - 31 * day, 8_000, "Provilac Milk"),
        plan("BIRTHDAY", "Aisha's birthday", now + 2 * day),
        plan("TASK", "Renew passport", now + 10 * day)
    )

    private val store = object : AskMarksy.Retriever {
        override suspend fun events(from: Long, to: Long, limit: Int) = events.filter { it.postedAt in from until to }.sortedByDescending { it.postedAt }.take(limit)
        override suspend fun entities(name: String) = emptyList<ContextEntity>()
        override suspend fun eventIdsFor(entityId: Long) = emptyList<Long>()
        override suspend fun planItems() = plans
        override suspend fun resolveSymbol(text: String) = mapOf("tcs" to "TCS", "reliance" to "RELIANCE", "infosys" to "INFY", "tata motors" to "TATAMOTORS")[text.lowercase()]
    }

    private class Case(val q: String, val intent: Intent, val expect: (AskMarksy.Answer) -> String? = { null })

    private fun ids(vararg e: NotificationEventEntity): (AskMarksy.Answer) -> String? = { a ->
        val want = e.map { it.id }.toSet()
        if (a.derivedFromEventIds.toSet() == want) null else "ids ${a.derivedFromEventIds} != $want"
    }
    private fun says(text: String): (AskMarksy.Answer) -> String? = { a -> if (a.headline.contains(text)) null else "headline \"${a.headline}\" lacks \"$text\"" }
    private fun opens(page: Page, arg: String?): (AskMarksy.Answer) -> String? = { a -> if (a.action?.page == page && a.action?.arg == arg) null else "action ${a.action} != $page/$arg" }
    private fun all(vararg checks: (AskMarksy.Answer) -> String?): (AskMarksy.Answer) -> String? = { a -> checks.firstNotNullOfOrNull { it(a) } }

    private val cases = listOf(
        // Email
        Case("How many emails did I get today?", Intent.SOURCE, all(ids(hrMail, joshMail, timesheet, hidden, rahulMail), says("5 emails today"))),
        Case("how many mails this week", Intent.SOURCE, ids(hrMail, joshMail, timesheet, hidden, rahulMail, oldMail)),
        Case("Did I receive any email from Josh?", Intent.FROM_PERSON, all(ids(joshMail), says("Yes, 1 email from"))),
        Case("any email from HR Team today", Intent.FROM_PERSON, ids(hrMail)),
        Case("show emails about timesheet", Intent.SOURCE, ids(timesheet)),
        Case("Did Rahul email me?", Intent.FROM_PERSON, ids(rahulMail)),
        Case("Did I get any email from Priya?", Intent.FROM_PERSON, says("No emails from")),
        // Teams, WhatsApp, SMS
        Case("What did I receive on Teams today?", Intent.SOURCE, all(ids(david1, david2, prashant), says("Most from David Shpil (2), Prashant Verma (1)"))),
        Case("how many teams messages", Intent.SOURCE, ids(david1, david2, prashant)),
        Case("any teams message from David", Intent.FROM_PERSON, ids(david1, david2)),
        Case("messages from Prashant on teams", Intent.FROM_PERSON, ids(prashant)),
        Case("Summarize my WhatsApp", Intent.SOURCE, ids(mom, rahulChat)),
        Case("What did Mom send me?", Intent.FROM_PERSON, ids(mom)),
        Case("any texts from HDFC", Intent.FROM_PERSON, ids(debit, salary)),
        Case("What did Rahul send me today?", Intent.FROM_PERSON, ids(rahulMail, rahulChat)),
        Case("anything from Priya", Intent.FROM_PERSON, says("Nothing from")),
        Case("how many notifications today", Intent.SOURCE, says("notifications today. Most from")),
        Case("which app sent the most notifications today", Intent.SOURCE, says("Most from")),
        // Money
        Case("How much did I spend this week?", Intent.PAYMENTS, all(ids(debit, amazonPay), says("debit ₹1700"))),
        Case("payments to Swiggy", Intent.PAYMENTS, ids(debit)),
        Case("did I get any money today", Intent.PAYMENTS, all(ids(salary), says("credit ₹12000"))),
        Case("what did I pay Amazon", Intent.PAYMENTS, ids(amazonPay)),
        // Bills, reminders, tasks
        Case("Which bills are due?", Intent.BILLS_DUE, says("2 open bills")),
        Case("do I owe anyone money", Intent.BILLS_DUE, says("2 open bills")),
        Case("what do I have to pay this month", Intent.BILLS_DUE, says("1 open bill: Provilac")),
        Case("when is the ICICI bill due", Intent.BILLS_DUE, says("1 open bill: ICICI Bank card bill")),
        Case("What's coming up?", Intent.PLAN, says("4 open reminders")),
        Case("any birthdays this week", Intent.PLAN, says("1 open reminder: Aisha's birthday")),
        Case("when is Aisha's birthday", Intent.PLAN, says("Aisha's birthday")),
        Case("my to do list", Intent.PLAN, says("1 open task: Renew passport")),
        // Deliveries
        Case("What deliveries are coming tomorrow?", Intent.DELIVERIES, ids(flipkartParcel)),
        Case("where is my amazon order", Intent.DELIVERIES, ids(amazonParcel)),
        Case("any parcels coming", Intent.DELIVERIES, ids(amazonParcel, flipkartParcel)),
        // Stocks and market
        Case("TCS share price", Intent.STOCK, opens(Page.STOCK, "TCS")),
        Case("how is Reliance doing", Intent.STOCK, opens(Page.STOCK, "RELIANCE")),
        Case("price of infosys", Intent.STOCK, opens(Page.STOCK, "INFY")),
        Case("open tata motors", Intent.STOCK, opens(Page.STOCK, "TATAMOTORS")),
        Case("show trading opportunities", Intent.TRADING),
        Case("any trade calls today", Intent.TRADING),
        Case("upcoming IPOs", Intent.NAVIGATE, opens(Page.MARKET, "IPOS")),
        // Pages
        Case("open reminders", Intent.NAVIGATE, opens(Page.PLAN, "Reminders")),
        Case("go to settings", Intent.NAVIGATE, opens(Page.SETTINGS, null)),
        Case("take me to my inbox", Intent.NAVIGATE, opens(Page.INBOX, null)),
        Case("open kanban board", Intent.NAVIGATE, opens(Page.PLAN, "Board")),
        Case("show market news", Intent.NAVIGATE, opens(Page.MARKET, "UPDATES")),
        Case("open trade calls", Intent.NAVIGATE, opens(Page.TRADING, "Calls")),
        // Help, attention
        Case("what can you do", Intent.HELP),
        Case("help", Intent.HELP),
        Case("what did I miss today", Intent.MISSED),
        Case("anything important today", Intent.IMPORTANT),
        // Round 2: other wordings of the same needs
        Case("emails from josh this week", Intent.FROM_PERSON, ids(joshMail)),
        Case("how many outlook emails today", Intent.SOURCE, ids(hrMail, joshMail, timesheet, hidden)),
        Case("gmail today", Intent.SOURCE, ids(rahulMail)),
        Case("any new mail", Intent.SOURCE, ids(hrMail, joshMail, timesheet, hidden, rahulMail)),
        Case("count my whatsapp messages this week", Intent.SOURCE, ids(mom, rahulChat, family)),
        Case("who messaged me on whatsapp today", Intent.SOURCE, all(ids(mom, rahulChat), says("Most from Mom (1), Rahul (1)"))),
        Case("did David ping me on teams", Intent.FROM_PERSON, ids(david1, david2)),
        Case("latest from Josh", Intent.FROM_PERSON, ids(joshMail)),
        Case("how much did I spend on swiggy", Intent.PAYMENTS, ids(debit)),
        Case("money received this week", Intent.PAYMENTS, ids(salary)),
        Case("show my transactions", Intent.PAYMENTS, ids(debit, salary, amazonPay)),
        Case("upcoming birthdays", Intent.PLAN, says("Aisha's birthday")),
        Case("what tasks do I have", Intent.PLAN, says("Renew passport")),
        Case("remind me what's due this week", Intent.BILLS_DUE, says("1 open bill: Provilac")),
        Case("what's pending", Intent.PLAN, says("4 open reminders")),
        Case("is anything arriving today", Intent.DELIVERIES, ids(amazonParcel)),
        Case("track my flipkart order", Intent.DELIVERIES, ids(flipkartParcel)),
        Case("what's the price of TCS", Intent.STOCK, opens(Page.STOCK, "TCS")),
        Case("reliance stock", Intent.STOCK, opens(Page.STOCK, "RELIANCE")),
        Case("how's infosys doing today", Intent.STOCK, opens(Page.STOCK, "INFY")),
        Case("show me the market", Intent.NAVIGATE, opens(Page.MARKET, "OVERVIEW")),
        Case("go to calls", Intent.NAVIGATE, opens(Page.TRADING, "Calls")),
        Case("open plan", Intent.NAVIGATE, opens(Page.PLAN, null)),
        Case("open birthdays", Intent.PLAN, says("Aisha's birthday")),
        // Round 3: phrasings Gemma got wrong on device
        Case("is anything left to settle", Intent.BILLS_DUE),
        Case("any news on reliance shares", Intent.STOCK, opens(Page.STOCK, "RELIANCE")),
        Case("catch me up", Intent.IMPORTANT),
        Case("anything I should know about", Intent.IMPORTANT),
        Case("did anyone reply to my email", Intent.SOURCE),
        Case("should I buy anything today", Intent.TRADING),
        Case("what's new on outlook", Intent.SOURCE, ids(hrMail, joshMail, timesheet, hidden)),
        // Round 4: device eval misses
        Case("who wished me happy birthday", Intent.SEARCH),
        Case("remind me of anything today", Intent.PLAN),
        Case("is the market up today", Intent.TRADING),
        Case("tell me about tata motors", Intent.STOCK, opens(Page.STOCK, "TATAMOTORS"))
    )

    @Test
    fun askMarksyAnswersTheEvaluationSet() {
        val failures = cases.mapNotNull { c ->
            val query = AskMarksy.parse(c.q, null, now, zone)
            if (query.intent != c.intent) return@mapNotNull "\"${c.q}\": intent ${query.intent} != ${c.intent}"
            val answer = runBlocking { AskMarksy.answer(query, store, now, zone = zone) }
            c.expect(answer)?.let { "\"${c.q}\": $it" }
        }
        println("Ask eval: ${cases.size - failures.size}/${cases.size} pass")
        assertTrue(failures.joinToString("\n", prefix = "${failures.size} of ${cases.size} failed:\n"), failures.isEmpty())
    }
}
