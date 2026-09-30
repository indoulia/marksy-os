package com.marksy.os.market

import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** How a ledger tip reads on the phone. Every state, outcome and return is the server's (spec §6.6, §7); this only
 * words it, so a withdrawn losing Marksy call reads as a failed exit, never as a neutral invalidation. */
object LedgerCalls {
    const val STATUS_ACTIVE = "ACTIVE"
    private const val DATA_BASIS_PROVISIONAL = "PROVISIONAL"

    enum class Tone { POSITIVE, NEGATIVE, NEUTRAL, MUTED }

    private val IST: ZoneId = ZoneId.of("Asia/Kolkata")
    private val DAY = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)
    private val TIME = DateTimeFormatter.ofPattern("d MMM, HH:mm", Locale.ENGLISH)
    private val STATUS = mapOf(
        "TARGET_HIT" to "Target hit", "STOP_LOSS_HIT" to "Stop-loss hit", "SOURCE_EXIT" to "Exited",
        "HORIZON_EXPIRED" to "Expired", "DIRECTION_HORIZON" to "Horizon reached", "INVALIDATED" to "Invalidated",
        "UNSCORABLE" to "Unscorable", "DATA_UNRESOLVED" to "No market data"
    )
    private val OUTCOME = mapOf("SUCCESS" to "Succeeded", "FAILURE" to "Failed")
    private val CHANNEL_TYPE = mapOf(
        "BROKER_APP" to "broker app", "NEWS_PORTAL" to "news", "SMS_SENDER" to "SMS", "WHATSAPP_GROUP" to "WhatsApp group",
        "TELEGRAM_CHANNEL" to "Telegram", "YOUTUBE" to "YouTube"
    )
    private val MEDIUM = mapOf(
        "APP_NOTIFICATION" to "app", "SMS" to "SMS", "WHATSAPP" to "WhatsApp", "TELEGRAM" to "Telegram", "EMAIL" to "email", "MANUAL" to "paste"
    )

    fun isActive(t: LedgerTipDto): Boolean = t.status == STATUS_ACTIVE

    fun state(t: LedgerTipDto): String = when (t.status) {
        STATUS_ACTIVE -> if (t.entryStatus == "WAITING") "Waiting for entry" else "Active"
        "SOURCE_EXIT", "DIRECTION_HORIZON" -> listOfNotNull(STATUS[t.status], t.outcome?.let(OUTCOME::get)).joinToString(" · ")
        "INVALIDATED", "UNSCORABLE", "DATA_UNRESOLVED" -> listOfNotNull(STATUS[t.status], t.reason?.let(::words)).joinToString(" · ")
        else -> STATUS[t.status] ?: words(t.status)
    }

    fun tone(t: LedgerTipDto): Tone = when (t.outcome) {
        "SUCCESS" -> Tone.POSITIVE
        "FAILURE" -> Tone.NEGATIVE
        "EXPIRED" -> Tone.NEUTRAL
        else -> if (isActive(t)) Tone.NEUTRAL else Tone.MUTED
    }

    fun returnText(fraction: Double?): String? = fraction?.let { String.format(Locale.US, "%+.2f%%", it * 100) }

    /** The latest return while open, flagged when provisional; the actual return once closed. */
    fun progressText(t: LedgerTipDto): String? {
        if (!isActive(t)) return returnText(t.actualReturn)
        val point = t.latestProgress ?: return null
        val text = returnText(point.returnToDate) ?: return null
        return "$text so far" + if (point.dataBasis == DATA_BASIS_PROVISIONAL) " · provisional" else ""
    }

    fun progressTone(t: LedgerTipDto): Tone {
        val value = (if (isActive(t)) t.latestProgress?.returnToDate else t.actualReturn) ?: return Tone.MUTED
        return when {
            value > 0 -> Tone.POSITIVE
            value < 0 -> Tone.NEGATIVE
            else -> Tone.NEUTRAL
        }
    }

    fun progressLine(p: ProgressPointDto): String = listOfNotNull(
        runCatching { DAY.format(LocalDate.parse(p.sessionDate)) }.getOrDefault(p.sessionDate),
        "S${p.sessionIndex}",
        returnText(p.returnToDate),
        returnText(p.bestReturn)?.let { "best $it" },
        returnText(p.worstReturn)?.let { "worst $it" },
        "provisional".takeIf { p.dataBasis == DATA_BASIS_PROVISIONAL }
    ).joinToString(" · ")

    fun entryMid(t: LedgerTipDto): Double? = if (t.entryLow != null && t.entryHigh != null) (t.entryLow + t.entryHigh) / 2 else t.entryLow ?: t.entryHigh

    fun levelsText(t: LedgerTipDto): String {
        val low = t.entryLow
        val high = t.entryHigh
        val entry = when {
            low == null && high == null -> "first price"
            low != null && high != null && low != high -> "${rupees(low)}–${rupees(high).removePrefix("₹")}"
            else -> rupees(low ?: high!!)
        }
        return listOfNotNull("Entry $entry", t.target?.let { "Target ${rupees(it)}" }, t.stopLoss?.let { "Stop ${rupees(it)}" }).joinToString(" · ")
    }

    fun headline(t: LedgerTipDto, withCaller: Boolean): String = listOfNotNull(
        t.direction ?: "No direction", t.caller?.name?.takeIf { withCaller }, state(t), horizonText(t.horizonSessions), "seen ${day(t.firstSeenAt)}"
    ).joinToString(" · ")

    fun pastLine(t: LedgerTipDto, withCaller: Boolean): String = listOfNotNull(
        day(t.firstSeenAt), t.direction, t.caller?.name?.takeIf { withCaller }, state(t), returnText(t.actualReturn)
    ).joinToString(" · ")

    fun termsText(t: LedgerTipDto): String = listOfNotNull(
        horizonText(t.horizonSessions)?.let { h -> "Horizon $h" + (t.horizonBasis?.let { " (${words(it)})" } ?: "") },
        "first seen ${time(t.firstSeenAt)}",
        t.closedAt?.let { c -> "closed ${time(c)}" + (t.closedSession?.let { " (session $it)" } ?: "") }
    ).joinToString(" · ")

    fun returnsText(t: LedgerTipDto): String? = listOfNotNull(
        returnText(t.promisedReturn)?.let { "Promised $it" }, returnText(t.actualReturn)?.let { "actual $it" }
    ).joinToString(" · ").ifEmpty { null }

    fun horizonText(sessions: Int?): String? = when (sessions) {
        null -> null
        0 -> "intraday"
        1 -> "1 session"
        else -> "$sessions sessions"
    }

    /** §8.2's headline, or "not enough history" below 10 completed calls, always with the invalidated count. */
    fun recordText(h: ScorecardHeadlineDto): String = listOfNotNull(
        h.trustScore?.let { "Trust $it" } ?: "Not enough history",
        "${h.total} call${if (h.total == 1) "" else "s"}",
        h.hitRatePct?.let { String.format(Locale.US, "%.0f%% hit", it) },
        h.invalidated.takeIf { it > 0 }?.let { "$it invalidated" }
    ).joinToString(" · ")

    fun channelType(type: String): String? = CHANNEL_TYPE[type]

    /** Where the customer's own copies came from, and how many others got it; never who (invariant 10). */
    fun receivedVia(item: MyTipDto): String {
        val via = item.receivedVia.map { MEDIUM[it.medium] ?: words(it.medium) }.distinct().joinToString(", ")
        val others = item.alsoReceivedBy.takeIf { it > 0 }?.let { " · $it other${if (it == 1) "" else "s"} got it" } ?: ""
        return "Received via $via$others"
    }

    /** The newest open Marksy call with levels: it draws the chart lines and fills the trade ticket. */
    fun leadingMarksyCall(calls: InstrumentCallsDto?): LedgerTipDto? = calls?.engines.orEmpty().flatMap { it.tips }
        .filter { isActive(it) && (it.target != null || it.stopLoss != null) }
        .maxByOrNull { it.firstSeenAt }

    /** The recommendation behind the Prediction engine's open call, else its newest; `predictions[]` only maps the id. */
    fun analysisRecommendationId(calls: InstrumentCallsDto?, predictions: List<InstrumentPredictionEntryDto>): Int? {
        val made = calls?.engines.orEmpty().flatMap { it.tips }.filter { it.predictionId != null }
        val pick = made.filter(::isActive).maxByOrNull { it.firstSeenAt } ?: made.maxByOrNull { it.firstSeenAt } ?: return null
        return predictions.firstOrNull { it.predictionId == pick.predictionId }?.recommendationId
    }

    fun chartLevels(t: LedgerTipDto?): List<Pair<String, Double>> = if (t == null) emptyList() else listOfNotNull(
        t.target?.let { "Target" to it }, entryMid(t)?.let { "Entry" to it }, t.stopLoss?.let { "Stop" to it }
    )

    fun day(iso: String): String = runCatching { OffsetDateTime.parse(iso).atZoneSameInstant(IST).format(DAY) }.getOrDefault(iso.take(10))

    private fun time(iso: String): String =
        runCatching { OffsetDateTime.parse(iso).atZoneSameInstant(IST).format(TIME) }.getOrDefault(iso.take(16).replace('T', ' '))

    private fun rupees(v: Double) = "₹" + String.format(Locale.US, "%,.2f", v)

    private fun words(s: String) = s.lowercase(Locale.ROOT).replace('_', ' ')
}
