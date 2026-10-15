package com.marksy.os.ui

import com.marksy.os.MarksyFormat
import com.marksy.os.upstox.FeedFreshness
import com.marksy.os.upstox.UpstoxLtp
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId

/** The price on a call's stop-to-target bar and what it is: the Upstox quote when there is one, else Marksy's latest stored close. */
internal data class CallPrice(val price: Double?, val label: String?, val live: Boolean) {
    companion object {
        private val IST: ZoneId = ZoneId.of("Asia/Kolkata")

        fun of(quote: UpstoxLtp?, feed: FeedFreshness, storedClose: Double?, storedAt: String?, today: LocalDate): CallPrice {
            if (quote == null) return CallPrice(storedClose, storedClose?.let { "Close · " + (storedAt?.let(::sessionDay) ?: "date unknown") }, live = false)
            val traded = quote.tradeTime?.let { Instant.ofEpochMilli(it).atZone(IST) }
            val at = traded?.let { if (it.toLocalDate() == today) MarksyFormat.time(it) else MarksyFormat.dayTime(it) }
            // A streaming feed still carries an illiquid stock's trade from an earlier day; that price is not live.
            val live = feed == FeedFreshness.LIVE && (traded == null || traded.toLocalDate() == today)
            val label = when {
                live -> "Live" + at?.let { " · $it" }.orEmpty()
                feed == FeedFreshness.LIVE || feed == FeedFreshness.CLOSED -> "Last trade" + at?.let { " · $it" }.orEmpty()
                else -> "Not live" + at?.let { " · last trade $it" }.orEmpty()
            }
            return CallPrice(quote.lastPrice, label, live)
        }

        // `lastPriceAt` stamps the stored bar's session, not when the price was seen, so only its date is shown.
        private fun sessionDay(iso: String): String? =
            runCatching { MarksyFormat.day(OffsetDateTime.parse(iso).atZoneSameInstant(IST).toLocalDate()) }.getOrNull()
    }
}
