package com.marksy.os.intelligence

import com.marksy.os.data.local.EventActionEntity
import com.marksy.os.data.local.LearningOverrideEntity
import com.marksy.os.data.local.NotificationEventEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneOffset

class DailyBriefingTest {
    private val zone = ZoneOffset.UTC
    private fun at(day: Int, h: Int, m: Int = 0) = LocalDateTime.of(2026, 9, day, h, m).toInstant(zone).toEpochMilli()
    private val morning = at(24, 8)
    private val evening = at(24, 20)
    private var seq = 0L

    private fun event(category: String, title: String, body: String, postedAt: Long, pkg: String = "com.app", priority: Int = 50, state: String = "ACTIVE", dup: Long? = null): NotificationEventEntity {
        val e = NotificationEventEntity(id = ++seq, sourcePackage = pkg, sourceName = pkg.substringAfterLast('.'), sourceKey = "k$seq", eventFingerprint = "f$seq",
            title = title, body = body, postedAt = postedAt, category = category, priority = priority, confidence = .9f, isTrading = category == "TRADING",
            lifecycleState = state, duplicateOfId = dup)
        return e.copy(intelligenceJson = EventNormalizer.toJson(EventNormalizer.normalize(e, zone), dup))
    }

    private val fixture by lazy {
        val otp = event("OTP", "OTP", "123456 is your code", at(24, 7), "com.bank", priority = 90)
        val bill = event("BILLS", "Electricity bill due", "Rs 1,200 due today", at(23, 19), "com.power", priority = 65)
        val debit = event("BANKING", "Debited", "Rs 500 debited. UPI Ref 426712345678", at(24, 6), "com.bank", priority = 80)
        val dup = event("PAYMENTS", "Paid", "Paid Rs 500. UPI Ref 426712345678", at(24, 6, 1), "com.upi", priority = 75, dup = debit.id)
        val credit = event("BANKING", "Credited", "Rs 2,000 credited", at(24, 7, 30), "com.bank", priority = 80)
        val parcel = event("DELIVERY", "Shipped", "Order ID 403-1234567-7654321 arriving tomorrow", at(24, 12), "com.shop")
        val friend = event("MESSAGES", "Rahul", "call me", at(24, 7, 45), "com.whatsapp", priority = 40)
        val old = event("PROMOTIONS", "Sale", "50% off", at(20, 10), "com.shop", priority = 20)
        listOf(otp, bill, debit, dup, credit, parcel, friend, old)
    }

    private fun profileRahulImportant() = PersonalLearning.buildProfile(
        emptyList(), listOf(LearningOverrideEntity("SENDER", "com.whatsapp|rahul", PersonalLearning.Preference.ALWAYS_IMPORTANT.name, "Rahul", 0))
    )

    @Test
    fun morningBriefingSectionsAreGroundedAndExplained() {
        val b = DailyBriefing.build(DailyBriefing.Kind.MORNING, fixture, emptyList(), profileRahulImportant(), morning, zone)
        val s = b.sections.associateBy { it.title }

        assertTrue(s.getValue("Important").lines.first().text.startsWith("bank: OTP"))
        assertEquals("Bill that may need payment", s.getValue("Needs your action").lines.single().why)
        assertEquals(listOf("Spent ₹500", "Received ₹2000"), s.getValue("Money").lines.map { it.text })
        assertEquals("1 transaction with an amount", s.getValue("Money").lines.first().why)
        assertEquals("whatsapp: Rahul", s.getValue("For you").lines.single().text)
        assertTrue(b.derivedFromEventIds.none { id -> fixture.first { it.id == id }.duplicateOfId != null })
        // The debit's UPI duplicate is not counted twice and the old promo is outside the window.
        assertTrue(b.headline.startsWith("5 notifications since yesterday evening"))
    }

    @Test
    fun eveningBriefingLooksAtTomorrowIncludingExpectedItems() {
        val parcel = fixture.first { it.category == "DELIVERY" }
        val expected = listOf(EventActionEntity(id = 1, eventId = parcel.id, type = "MARK_EXPECTED", state = "SUCCEEDED", createdAt = 0, updatedAt = 0, scheduledFor = at(25, 10)))
        val b = DailyBriefing.build(DailyBriefing.Kind.EVENING, fixture, expected, PersonalLearning.Profile.EMPTY, evening, zone)
        val tomorrow = b.sections.single { it.title == "Tomorrow" }.lines
        assertEquals(1, tomorrow.size)
        assertEquals("You marked this as expected", tomorrow.single().why)
    }

    @Test
    fun sameInputGivesIdenticalBriefingRegardlessOfInputOrder() {
        val a = DailyBriefing.build(DailyBriefing.Kind.MORNING, fixture, emptyList(), profileRahulImportant(), morning, zone)
        val b = DailyBriefing.build(DailyBriefing.Kind.MORNING, fixture.reversed(), emptyList(), profileRahulImportant(), morning, zone)
        assertEquals(a, b)
        assertEquals(DailyBriefing.Kind.MORNING, DailyBriefing.defaultKind(morning, zone))
        assertEquals(DailyBriefing.Kind.EVENING, DailyBriefing.defaultKind(evening, zone))
    }

    @Test
    fun emptyDataProducesAnHonestEmptyBriefing() {
        val b = DailyBriefing.build(DailyBriefing.Kind.OVERNIGHT, emptyList(), emptyList(), PersonalLearning.Profile.EMPTY, at(24, 6), zone)
        assertTrue(b.sections.isEmpty())
        assertEquals("0 notifications overnight.", b.headline)
    }
}
