package com.marksy.os.portfolio

import com.marksy.os.alerts.PriceAlert
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

enum class FlagKind { DAY_MOVE, BELOW_AVERAGE, NEAR_ALERT, CONCENTRATION }

/** Declared most severe first; the order ranks reasons and cards. */
enum class FlagSeverity { CRITICAL, WARNING, POSITIVE }

data class HoldingFlag(val kind: FlagKind, val severity: FlagSeverity, val reason: String)

data class NeedsLook(val row: HoldingRow, val flags: List<HoldingFlag>) {
    val primary: HoldingFlag get() = flags.minBy { it.severity.ordinal }
}

/** "Needs a look" rules, fixed in v1 by the user's 2026-10-02 decision. */
object PortfolioFlags {
    const val BELOW_AVERAGE_PCT = 15.0
    const val ALERT_DISTANCE_PCT = 4.0
    const val CONCENTRATION_PCT = 20.0
    const val DAY_MOVE_PCT = 4.0
    // Rupee-and-paise prices land a hair either side of a threshold in doubles; an exact boundary must still flag.
    private const val EPS = 1e-9
    private val IST: ZoneId = ZoneId.of("Asia/Kolkata")
    private val OPEN: LocalTime = LocalTime.of(9, 15)

    fun flags(row: HoldingRow, alerts: List<PriceAlert>): List<HoldingFlag> = buildList {
        row.dayPct?.takeIf { abs(it) >= DAY_MOVE_PCT - EPS }?.let { d ->
            add(HoldingFlag(FlagKind.DAY_MOVE, if (d < 0) FlagSeverity.CRITICAL else FlagSeverity.POSITIVE, "${if (d < 0) "Fell" else "Rose"} ${one(abs(d))}% today"))
        }
        row.totalPct?.takeIf { it <= -BELOW_AVERAGE_PCT + EPS }?.let { add(HoldingFlag(FlagKind.BELOW_AVERAGE, FlagSeverity.CRITICAL, "${one(-it)}% below your average")) }
        nearestAlert(row, alerts)?.let { (alert, gap) ->
            add(HoldingFlag(FlagKind.NEAR_ALERT, FlagSeverity.WARNING, "${one(gap)}% from your ₹${com.marksy.os.ui.money(alert.price)} alert"))
        }
        if (concentrated(row.weightPct)) add(HoldingFlag(FlagKind.CONCENTRATION, FlagSeverity.WARNING, "${row.weightPct.roundToInt()}% of your portfolio"))
    }

    /** The user's closest alert on this holding, when within [ALERT_DISTANCE_PCT] of the price either way. */
    fun nearestAlert(row: HoldingRow, alerts: List<PriceAlert>): Pair<PriceAlert, Double>? {
        if (row.price <= 0) return null
        return alerts.filter { it.symbol.equals(row.holding.symbol, ignoreCase = true) }
            .map { it to abs(row.price - it.price) / row.price * 100 }
            .filter { it.second <= ALERT_DISTANCE_PCT + EPS }
            .minByOrNull { it.second }
    }

    fun concentrated(weightPct: Double): Boolean = weightPct >= CONCENTRATION_PCT - EPS

    /** Flagged holdings not hidden today: most severe first, then by today's rupee move. */
    fun needsALook(rows: List<HoldingRow>, alerts: List<PriceAlert>, hidden: Set<String>): List<NeedsLook> =
        rows.filter { it.holding.symbol !in hidden }
            .mapNotNull { r -> flags(r, alerts).takeIf { it.isNotEmpty() }?.let { NeedsLook(r, it) } }
            .sortedWith(compareBy<NeedsLook> { it.primary.severity.ordinal }.thenByDescending { abs(it.row.dayPnl ?: 0.0) })

    /** "Hide today" lasts until the next 09:15 IST open. */
    fun hiddenUntil(now: Long): Long {
        val t = Instant.ofEpochMilli(now).atZone(IST)
        val open = t.toLocalDate().atTime(OPEN).atZone(IST)
        return (if (open.isAfter(t)) open else open.plusDays(1)).toInstant().toEpochMilli()
    }

    private fun one(v: Double) = String.format(Locale.US, "%.1f", v)
}
