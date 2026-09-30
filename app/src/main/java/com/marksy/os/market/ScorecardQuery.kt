package com.marksy.os.market

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** §8.3 presets; the server resolves each to IST dates, so the phone never computes a window. */
enum class ScorecardPeriod(val label: String) {
    LIFETIME("Lifetime"), TODAY("Today"), YESTERDAY("Yesterday"), THIS_WEEK("This week"), LAST_WEEK("Last week"),
    THIS_MONTH("This month"), LAST_MONTH("Last month"), LAST_7_DAYS("Last 7 days"), LAST_7_TRADING_DAYS("Last 7 trading days"),
    LAST_30_DAYS("Last 30 days"), LAST_90_DAYS("Last 90 days"), CUSTOM("Custom")
}

enum class HorizonBucket(val label: String) {
    INTRADAY("Intraday"), UP_TO_1_WEEK("Up to 1 week"), UP_TO_1_MONTH("Up to 1 month"), LONGER_THAN_1_MONTH("Longer than 1 month")
}

enum class ScorecardEntity(val label: String, val param: String) {
    CHANNEL("Channels", "channel"), CALLER("Callers", "caller");

    companion object {
        fun fromParam(param: String): ScorecardEntity = entries.firstOrNull { it.param == param } ?: CHANNEL
    }
}

/** What the Scorecards tab shows: which list, and the §8.3 filter every card on it shares. */
data class ScorecardQuery(
    val entity: ScorecardEntity = ScorecardEntity.CHANNEL,
    val period: ScorecardPeriod = ScorecardPeriod.LIFETIME,
    val startDate: LocalDate? = null,
    val endDate: LocalDate? = null,
    val horizon: HorizonBucket? = null,
    val channelId: Int? = null,
    val callerId: Int? = null
) {
    /** The §8.3 query parameters; null while a custom range lacks a date or ends before it starts. */
    fun filterParams(): String? {
        val range = if (period == ScorecardPeriod.CUSTOM) {
            val start = startDate ?: return null
            val end = endDate?.takeUnless { it.isBefore(start) } ?: return null
            listOf("period=CUSTOM", "startDate=$start", "endDate=$end")
        } else {
            listOfNotNull(period.takeUnless { it == ScorecardPeriod.LIFETIME }?.let { "period=${it.name}" })
        }
        val narrowing = listOfNotNull(horizon?.let { "horizon=${it.name}" }, channelId?.let { "channelId=$it" }, callerId?.let { "callerId=$it" })
        return (range + narrowing).joinToString("&")
    }

    fun label(): String {
        val start = startDate
        val end = endDate
        val range = if (period == ScorecardPeriod.CUSTOM && start != null && end != null) "${DAY.format(start)} – ${DAY.format(end)}" else period.label
        return range + (horizon?.let { " · ${it.label}" } ?: "")
    }

    fun encode(): String = listOf(
        entity.name, period.name, startDate?.toString().orEmpty(), endDate?.toString().orEmpty(),
        horizon?.name.orEmpty(), channelId?.toString().orEmpty(), callerId?.toString().orEmpty()
    ).joinToString("|")

    companion object {
        private val DAY = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)

        fun decode(text: String): ScorecardQuery = runCatching {
            val p = text.split("|")
            ScorecardQuery(
                ScorecardEntity.valueOf(p[0]), ScorecardPeriod.valueOf(p[1]),
                p[2].ifEmpty { null }?.let(LocalDate::parse), p[3].ifEmpty { null }?.let(LocalDate::parse),
                p[4].ifEmpty { null }?.let(HorizonBucket::valueOf), p[5].toIntOrNull(), p[6].toIntOrNull()
            )
        }.getOrDefault(ScorecardQuery())
    }
}

/** Words for §8 numbers exactly as the server sent them; nothing here recomputes a metric. */
object ScorecardText {
    private val DAY = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)
    private val FULL_DAY = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)

    fun date(d: LocalDate): String = DAY.format(d)

    fun trust(t: ScorecardTrustDto): String = t.trustScore?.let { "Trust $it" } ?: "Trust —"

    /** The window the server resolved, in IST dates as echoed; "All time" for Lifetime. */
    fun range(f: ScorecardFilterEchoDto): String {
        fun day(iso: String?) = iso?.let { runCatching { FULL_DAY.format(LocalDate.parse(it)) }.getOrDefault(it) }
        val start = day(f.startDate)
        val end = day(f.endDate)
        return if (start == null || end == null) "All time" else "$start – $end (IST)"
    }

    fun summary(b: ScorecardBodyDto): String = listOfNotNull(
        "${b.counts.completed} completed of ${b.counts.total}",
        b.performance.successPct?.let { "success ${pct(it)}" },
        b.performance.hitRatePct?.let { "hit ${pct(it)}" },
        LedgerCalls.returnText(b.performance.avgActualReturn)?.let { "avg $it" },
        b.counts.open.takeIf { it > 0 }?.let { "$it open" },
        b.trust.invalidated.takeIf { it > 0 }?.let { "$it invalidated" }
    ).joinToString(" · ")

    fun details(b: ScorecardBodyDto): List<Pair<String, String>> = listOf(
        "Total tips" to "${b.counts.total}", "Open" to "${b.counts.open}", "Successful" to "${b.counts.successful}",
        "Failed" to "${b.counts.failed}", "Expired" to "${b.counts.expired}", "Completed" to "${b.counts.completed}",
        "Exited by source" to "${b.counts.exited}", "Invalidated" to "${b.counts.invalidated}",
        "Unscorable" to "${b.counts.unscorable}", "Data unresolved" to "${b.counts.dataUnresolved}",
        "Success" to opt(b.performance.successPct, ::pct), "Failure" to opt(b.performance.failurePct, ::pct),
        "Hit rate" to opt(b.performance.hitRatePct, ::pct),
        "Avg actual return" to opt(b.performance.avgActualReturn, ::ret), "Total actual return" to opt(b.performance.totalActualReturn, ::ret),
        "Avg promised return" to opt(b.performance.avgPromisedReturn, ::ret), "Total promised return" to opt(b.performance.totalPromisedReturn, ::ret),
        "Return realization" to opt(b.performance.returnRealizationPct, ::pct),
        "Avg days to completion" to opt(b.performance.avgDaysToCompletion) { String.format(Locale.US, "%.2f", it) },
        "Trust score" to (b.trust.trustScore?.toString() ?: "not enough history (${b.trust.completed}/${b.trust.minimumCompleted})"),
        "Wilson lower bound" to opt(b.trust.wilsonLowerBound) { String.format(Locale.US, "%.3f", it) },
        "Return quality" to opt(b.trust.returnQuality) { String.format(Locale.US, "%.3f", it) }
    )

    private fun pct(v: Double) = String.format(Locale.US, "%.2f%%", v)
    private fun ret(v: Double) = LedgerCalls.returnText(v) ?: "—"
    private fun opt(v: Double?, format: (Double) -> String) = v?.let(format) ?: "—"
}
