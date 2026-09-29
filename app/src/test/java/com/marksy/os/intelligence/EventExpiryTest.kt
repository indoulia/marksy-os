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
    fun callExpiresWithItsHorizon() {
        val intraday = event("TRADING", "Intraday Call", "BUY RENUKA CMP 23.62 SL 22.25 TGT 26")
        val months = event("TRADING", "KISHAN", "BUY | CROPSTER AGRO | Entry 2.82 | Target 10 | SL 2 | Time: 1-2 Months")
        assertEquals(mondayTen.withHour(15).withMinute(30).toInstant().toEpochMilli(), expiry(intraday))
        // Horizons count trading sessions after the posting session: 1-2 months = 42, 12 months = 252.
        assertEquals(mondayTen.plusWeeks(8).plusDays(2).withHour(15).withMinute(30).toInstant().toEpochMilli(), expiry(months))
        val year = event("TRADING", "ICICI Direct", "Buy TENNIND around Rs 500 for 12 Month with target price of Rs 650, potential upside of 30%.")
        assertEquals(mondayTen.plusWeeks(50).plusDays(2).withHour(15).withMinute(30).toInstant().toEpochMilli(), expiry(year))
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

    @Test
    fun newerCallOnTheSameSymbolRetiresTheOlderOne() {
        val older = event("TRADING", "ICICI", "Buy RENUKA around Rs 22, target price of Rs 27")
        val newer = event("TRADING", "5paisa", "SELL RENUKA CMP 23.62 SL 25 TGT 21", mondayTen.plusDays(1))
        assertEquals(setOf(older.id), EventExpiry.superseded(listOf(older, newer)).keys)
    }
}
