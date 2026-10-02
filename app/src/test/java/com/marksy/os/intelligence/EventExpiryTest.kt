package com.marksy.os.intelligence

import com.marksy.os.data.local.NotificationEventEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class EventExpiryTest {
    private val ist = ZoneId.of("Asia/Kolkata")
    private val mondayTen = ZonedDateTime.of(2026, 9, 28, 10, 0, 0, 0, ist)
    private val minute = 60_000L
    private val day = 24 * 60 * minute
    private var seq = 0L

    private fun event(category: String, title: String, body: String, at: ZonedDateTime = mondayTen) = NotificationEventEntity(
        id = ++seq, sourcePackage = "p", sourceName = "App", sourceKey = "k$seq", eventFingerprint = "f$seq", title = title, body = body,
        postedAt = at.toInstant().toEpochMilli(), category = category, priority = 1, confidence = 1f, isTrading = category == "TRADING"
    )

    private fun expiry(e: NotificationEventEntity) = EventExpiry.of(e, ist)?.atMillis

    // User rule: an OTP lasts as long as its text says, otherwise 30 minutes.
    @Test
    fun otpUsesItsStatedValidityElseThirtyMinutes() {
        val stated = event("OTP", "HDFC Bank", "123456 is your OTP. Valid for 10 mins. Do not share.")
        val unstated = event("OTP", "Amazon", "123456 is your Amazon OTP.")
        assertEquals(stated.postedAt + 10 * minute, expiry(stated))
        assertEquals(unstated.postedAt + 30 * minute, expiry(unstated))
    }

    // User rule: promotions are meant for a day.
    @Test
    fun promotionLastsADay() {
        val promo = event("PROMOTIONS", "Meesho", "Flat 70% off today only")
        assertEquals(promo.postedAt + day, expiry(promo))
    }

    @Test
    fun marketNewsExpiresAtTheNextSessionClose() {
        val afterClose = event("MARKET", "Moneycontrol", "Sensex ends 300 pts higher", mondayTen.withHour(18))
        assertEquals(mondayTen.plusDays(1).withHour(15).withMinute(30).toInstant().toEpochMilli(), expiry(afterClose))
    }

    @Test
    fun sameDayItemsExpireAtMidnight() {
        val midnight = mondayTen.plusDays(1).toLocalDate().atStartOfDay(ist).toInstant().toEpochMilli()
        assertEquals(midnight, expiry(event("MARKET", "Upstox", "IPO of Acme closes today. Apply now")))
        assertEquals(midnight, expiry(event("DELIVERY", "Amazon", "Your package is out for delivery")))
        assertNull(expiry(event("BANKING", "HDFC", "Rs 500 debited")))
    }

    // Inbox redesign: seen, low-attention items leave the active views two days after posting.
    @Test
    fun seenLowAttentionItemsGoStaleAfterTwoDays() {
        val seen = event("BANKING", "HDFC Bank", "Rs 640 debited").copy(isRead = true, lifecycleState = "ACTIVE")
        assertEquals(seen.postedAt + 2 * day, EventExpiry.staleSeen(seen)?.atMillis)
        assertEquals(EventExpiry.REASON_STALE_SEEN, EventExpiry.staleSeen(seen)?.reason)
    }

    @Test
    fun unreadKeptRemindedDueFailedAndImportantItemsNeverGoStale() {
        val seen = event("BANKING", "HDFC Bank", "Rs 640 debited").copy(isRead = true, lifecycleState = "ACTIVE")
        assertNull(EventExpiry.staleSeen(seen.copy(isRead = false)))
        assertNull(EventExpiry.staleSeen(seen.copy(kept = true)))
        assertNull(EventExpiry.staleSeen(seen.copy(remindAt = seen.postedAt + day)))
        assertNull(EventExpiry.staleSeen(seen.copy(category = "BILLS")))
        assertNull(EventExpiry.staleSeen(seen.copy(category = "REMINDERS")))
        assertNull(EventExpiry.staleSeen(seen.copy(category = "MESSAGES", priority = 60)))
        assertNull(EventExpiry.staleSeen(seen.copy(category = "MESSAGES", importanceScore = 75)))
        assertNull(EventExpiry.staleSeen(seen.copy(category = "TRADING", isTrading = true, deliveryState = "FAILED")))
        assertNull(EventExpiry.staleSeen(seen.copy(lifecycleState = "RESOLVED")))
    }

    // Review I5: History hides OTHER, so a retired OTHER row would vanish entirely; it keeps the normal retention instead.
    @Test
    fun seenOtherItemsNeverGoStale() {
        val seen = event("OTHER", "App", "Something happened").copy(isRead = true, lifecycleState = "ACTIVE")
        assertNull(EventExpiry.staleSeen(seen))
    }

    // Review minor (re-graded): "could not be delivered" is a failure to act on, not a delivery day that ended.
    @Test
    fun failedDeliveryDoesNotExpireAtEndOfDay() {
        assertNull(EventExpiry.of(event("DELIVERY", "Delhivery", "Your parcel could not be delivered. We will retry tomorrow.")))
    }

    // User 2026-10-02: seen bank and payment alerts retire too; their 80/75 score is a category default, so only a failure keeps one.
    @Test
    fun seenBankAndPaymentAlertsGoStaleButFailuresDoNot() {
        val debit = event("BANKING", "HDFC Bank", "Rs 640 debited to SWIGGY").copy(isRead = true, lifecycleState = "ACTIVE", priority = 80, importanceScore = 80)
        assertEquals(debit.postedAt + 2 * day, EventExpiry.staleSeen(debit)?.atMillis)
        assertEquals(debit.postedAt + 2 * day, EventExpiry.staleSeen(debit.copy(category = "PAYMENTS", priority = 75))?.atMillis)
        assertNull(EventExpiry.staleSeen(debit.copy(body = "Payment of Rs 640 to SWIGGY failed")))
    }
}
