package com.marksy.os.market

import com.marksy.os.MarksyFormat
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId

/** Words for the session and moment behind a Marksy pick, so a call never reads fresher than it is. */
object PicksBasis {
    /** "Mon 28 Sep close · 16:17 · provisional"; null when the backend names no session. */
    fun label(sessionDate: String?, publishedAt: String?, dataBasis: String?, zone: ZoneId = ZoneId.systemDefault()): String? {
        val session = sessionDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return null
        val published = publishedAt?.let { runCatching { OffsetDateTime.parse(it).atZoneSameInstant(zone) }.getOrNull() }
        return listOfNotNull(
            MarksyFormat.weekdayDay(session) + " close",
            published?.let { if (it.toLocalDate() == session) MarksyFormat.time(it) else MarksyFormat.dayTime(it) },
            "provisional".takeIf { dataBasis == "PROVISIONAL" }
        ).joinToString(" · ")
    }

    fun label(p: ActivePredictionDto): String? = label(p.scanSessionDate, p.publishedAt, p.dataBasis)

    fun label(scan: LatestScanDto): String? = label(scan.scanSessionDate, scan.publishedAt, scan.dataBasis)

    /** "28 Sep", for the Trading title note. */
    fun day(sessionDate: String?): String? =
        sessionDate?.let { runCatching { MarksyFormat.day(LocalDate.parse(it)) }.getOrNull() }
}
