package com.marksy.os.intelligence

import com.marksy.os.data.local.NotificationEventEntity
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/** When an item stops being actionable and leaves the active views; history keeps it. */
object EventExpiry {
    data class Expiry(val atMillis: Long, val reason: String)

    const val REASON_PREFIX = "Retired: "
    const val REASON_STALE_SEEN = "${REASON_PREFIX}seen and older than 2 days"
    private const val STALE_ATTENTION = 60
    private val NEVER_STALE = setOf("BILLS", "REMINDERS")
    private const val MINUTE = 60_000L
    private const val DAY = 24 * 60 * MINUTE
    private val MARKET_ZONE: ZoneId = ZoneId.of("Asia/Kolkata")
    private val SESSION_CLOSE: LocalTime = LocalTime.of(15, 30)

    private val validFor = Regex("""\b(?:valid|expires?|expiring)\s+(?:for|in|within)?\s*(?:the\s+)?(?:next\s+)?(\d{1,3})\s*(sec|secs|seconds?|min|mins|minutes?|hrs?|hours?)\b""", RegexOption.IGNORE_CASE)
    private val validTill = Regex("""\b(?:valid|expires?)\s+(?:till|until|upto|up\s+to|by)\s+(\d{1,2}):(\d{2})\s*([ap]\.?m\.?)?""", RegexOption.IGNORE_CASE)
    private val closesToday = Regex("""\b(?:closes|closing|ends|last\s+day)\s+(?:is\s+)?today\b""", RegexOption.IGNORE_CASE)
    private val deliveryToday = Regex("""\b(?:out\s+for\s+delivery|arriving\s+today|delivered)\b""", RegexOption.IGNORE_CASE)

    fun of(event: NotificationEventEntity, zone: ZoneId = ZoneId.systemDefault()): Expiry? {
        val text = "${event.title}\n${event.body}"
        val posted = event.postedAt
        if (event.category in SAME_DAY_CATEGORIES && closesToday.containsMatchIn(text)) return Expiry(endOfDay(posted, zone), "${REASON_PREFIX}closed for the day")
        return when (event.category) {
            "OTP" -> Expiry(otpValidUntil(text, posted, zone), "${REASON_PREFIX}OTP validity passed")
            "PROMOTIONS" -> Expiry(posted + DAY, "${REASON_PREFIX}promotion older than a day")
            "TRADING" -> Expiry(posted + 7 * DAY, "${REASON_PREFIX}trading update older than a week")
            "MARKET" -> Expiry(nextSessionClose(posted), "${REASON_PREFIX}market session closed")
            "DELIVERY" -> if (deliveryToday.containsMatchIn(text)) Expiry(endOfDay(posted, zone), "${REASON_PREFIX}delivery day ended") else null
            else -> null
        }
    }

    /** Seen, low-attention items retire two days after posting; due, kept, reminded and failed items never do. */
    fun staleSeen(event: NotificationEventEntity): Expiry? {
        if (!event.isRead || event.kept || event.remindAt != null || event.archived) return null
        if (event.lifecycleState != EventLifecycle.State.NEW.name && event.lifecycleState != EventLifecycle.State.ACTIVE.name) return null
        if (event.category in NEVER_STALE || (event.isTrading && event.deliveryState == "FAILED")) return null
        if (maxOf(event.priority, event.importanceScore) >= STALE_ATTENTION) return null
        return Expiry(event.postedAt + 2 * DAY, REASON_STALE_SEEN)
    }

    private fun otpValidUntil(text: String, posted: Long, zone: ZoneId): Long {
        validFor.find(text)?.let { m ->
            val n = m.groupValues[1].toLong()
            val unit = m.groupValues[2].lowercase()
            return posted + when {
                unit.startsWith("s") -> n * 1000
                unit.startsWith("h") -> n * 60 * MINUTE
                else -> n * MINUTE
            }
        }
        validTill.find(text)?.let { m ->
            var hour = m.groupValues[1].toInt()
            val meridiem = m.groupValues[3].lowercase()
            if (meridiem.startsWith("p") && hour < 12) hour += 12
            if (meridiem.startsWith("a") && hour == 12) hour = 0
            val postedAt = Instant.ofEpochMilli(posted).atZone(zone)
            var until = postedAt.toLocalDate().atTime(hour.coerceIn(0, 23), m.groupValues[2].toInt().coerceIn(0, 59)).atZone(zone)
            if (!until.isAfter(postedAt)) until = until.plusDays(1)
            return until.toInstant().toEpochMilli()
        }
        return posted + 30 * MINUTE
    }

    private fun nextSessionClose(posted: Long): Long {
        val postedAt = Instant.ofEpochMilli(posted).atZone(MARKET_ZONE)
        var close: ZonedDateTime = postedAt.toLocalDate().atTime(SESSION_CLOSE).atZone(MARKET_ZONE)
        if (!postedAt.isBefore(close)) close = close.plusDays(1)
        while (close.dayOfWeek == DayOfWeek.SATURDAY || close.dayOfWeek == DayOfWeek.SUNDAY) close = close.plusDays(1)
        return close.toInstant().toEpochMilli()
    }

    private fun endOfDay(posted: Long, zone: ZoneId): Long =
        Instant.ofEpochMilli(posted).atZone(zone).toLocalDate().plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

    // IPO windows are announced by brokers, market apps and promo SMS alike.
    private val SAME_DAY_CATEGORIES = setOf("MARKET", "TRADING", "PROMOTIONS")
}
