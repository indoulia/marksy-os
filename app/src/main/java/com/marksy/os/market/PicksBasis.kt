package com.marksy.os.market

import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Words for the session and moment behind a Marksy pick, so a call never reads fresher than it is. */
object PicksBasis {
    /** "Mon 28 Sep close · 16:17 · provisional"; null when the backend names no session. */
    fun label(sessionDate: String?, publishedAt: String?, dataBasis: String?, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String? {
        val session = sessionDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return null
        val published = publishedAt?.let { runCatching { OffsetDateTime.parse(it).atZoneSameInstant(zone) }.getOrNull() }
        return listOfNotNull(
            session.format(DateTimeFormatter.ofPattern("EEE d MMM", locale)) + " close",
            published?.format(DateTimeFormatter.ofPattern(if (published.toLocalDate() == session) "HH:mm" else "d MMM, HH:mm", locale)),
            "provisional".takeIf { dataBasis == "PROVISIONAL" }
        ).joinToString(" · ")
    }

    fun label(p: ActivePredictionDto): String? = label(p.scanSessionDate, p.publishedAt, p.dataBasis)

    fun label(scan: LatestScanDto): String? = label(scan.scanSessionDate, scan.publishedAt, scan.dataBasis)

    /** "28 Sep", for the Trading title note. */
    fun day(sessionDate: String?, locale: Locale = Locale.getDefault()): String? =
        sessionDate?.let { runCatching { LocalDate.parse(it).format(DateTimeFormatter.ofPattern("d MMM", locale)) }.getOrNull() }
}
