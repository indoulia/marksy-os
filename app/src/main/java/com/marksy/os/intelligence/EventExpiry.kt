package com.marksy.os.intelligence

import com.marksy.os.data.local.NotificationEventEntity
import com.marksy.os.notification.TradeCallParser
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/** When an item stops being actionable and leaves the active views; history keeps it. */
object EventExpiry {
    data class Expiry(val atMillis: Long, val reason: String)

    const val REASON_PREFIX = "Retired: "
    private const val MINUTE = 60_000L
    private const val DAY = 24 * 60 * MINUTE
    private val MARKET_ZONE: ZoneId = ZoneId.of("Asia/Kolkata")
    private val SESSION_CLOSE: LocalTime = LocalTime.of(15, 30)

    private val validFor = Regex("""\b(?:valid|expires?|expiring)\s+(?:for|in|within)?\s*(?:the\s+)?(?:next\s+)?(\d{1,3})\s*(sec|secs|seconds?|min|mins|minutes?|hrs?|hours?)\b""", RegexOption.IGNORE_CASE)
    private val validTill = Regex("""\b(?:valid|expires?)\s+(?:till|until|upto|up\s+to|by)\s+(\d{1,2}):(\d{2})\s*([ap]\.?m\.?)?""", RegexOption.IGNORE_CASE)
    private val closesToday = Regex("""\b(?:closes|closing|ends|last\s+day)\s+(?:is\s+)?today\b""", RegexOption.IGNORE_CASE)
    private val deliveryToday = Regex("""\b(?:out\s+for\s+delivery|arriving\s+today|delivered)\b""", RegexOption.IGNORE_CASE)
    private val span = Regex("""(\d{1,3})(?:\s*(?:-|to)\s*(\d{1,3}))?\s*(day|week|month|year)s?\b""", RegexOption.IGNORE_CASE)

    fun of(event: NotificationEventEntity, zone: ZoneId = ZoneId.systemDefault()): Expiry? {
        val text = "${event.title}\n${event.body}"
        val posted = event.postedAt
        if (event.category in SAME_DAY_CATEGORIES && closesToday.containsMatchIn(text)) return Expiry(endOfDay(posted, zone), "${REASON_PREFIX}closed for the day")
        return when (event.category) {
            "OTP" -> Expiry(otpValidUntil(text, posted, zone), "${REASON_PREFIX}OTP validity passed")
            "PROMOTIONS" -> Expiry(posted + DAY, "${REASON_PREFIX}promotion older than a day")
            "TRADING" -> TradeCallParser.parse(event.title, event.body)
                ?.let { Expiry(callExpiry(it.horizon, posted), "${REASON_PREFIX}call horizon passed") }
                ?: Expiry(posted + 7 * DAY, "${REASON_PREFIX}trading update older than a week")
            "MARKET" -> Expiry(nextSessionClose(posted), "${REASON_PREFIX}market session closed")
            "DELIVERY" -> if (deliveryToday.containsMatchIn(text)) Expiry(endOfDay(posted, zone), "${REASON_PREFIX}delivery day ended") else null
            else -> null
        }
    }

    /** Older open calls on a symbol that has a newer call, whatever the source or side; the newest stays. */
    fun superseded(trading: List<NotificationEventEntity>): Map<Long, String> =
        trading.mapNotNull { e -> TradeCallParser.parse(e.title, e.body)?.let { e to it.symbol } }
            .groupBy({ it.second }, { it.first })
            .flatMap { (symbol, calls) -> calls.sortedByDescending { it.postedAt }.drop(1).map { it.id to "${REASON_PREFIX}newer call for $symbol" } }
            .toMap()

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

    private fun callExpiry(horizon: String?, posted: Long): Long {
        val h = horizon?.lowercase().orEmpty()
        val postedAt = Instant.ofEpochMilli(posted).atZone(MARKET_ZONE)
        return when {
            "intraday" in h -> nextSessionClose(posted)
            "btst" in h || "stbt" in h -> nextSessionClose(nextSessionClose(posted) + 1)
            else -> span.find(h)?.let { m ->
                val n = (m.groupValues[2].ifEmpty { m.groupValues[1] }).toLong()
                when (m.groupValues[3].lowercase()) {
                    "day" -> postedAt.plusDays(n)
                    "week" -> postedAt.plusWeeks(n)
                    "month" -> postedAt.plusMonths(n)
                    else -> postedAt.plusYears(n)
                }.toInstant().toEpochMilli()
            } ?: (posted + (if ("long" in h) 365 else 30) * DAY)
        }
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
