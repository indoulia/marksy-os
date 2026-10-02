package com.marksy.os.ui

import com.marksy.os.data.local.DeliveryState
import com.marksy.os.data.local.NotificationEventEntity
import com.marksy.os.notification.CaptureMedium
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale

enum class StackBy { SOURCE, SYMBOL }
enum class ShowOnly { ALL, TIPS, NEEDS_YOU }
enum class NeedKind { RETRY, WAITING }
enum class StatusTone { POSITIVE, NEGATIVE, NEUTRAL }

data class TipLevels(
    val side: String?, val symbol: String?, val entry: Double?, val target: Double?, val stopLoss: Double?,
    val status: String?, val tone: StatusTone = StatusTone.NEUTRAL
) { val hasLevels: Boolean get() = entry != null || target != null || stopLoss != null }

/** [folded] counts forwarded copies merged into this row; [alsoIn] names where they appeared. */
data class CapturedRow(
    val event: NotificationEventEntity, val insight: TradingInsight, val levels: TipLevels?, val headline: String,
    val folded: Int = 0, val alsoIn: List<String> = emptyList()
) {
    /** Kept on the phone by design (own orders, holdings): never sent to Marksy. */
    val keptLocal: Boolean get() = event.deliveryState == DeliveryState.NOT_APPLICABLE.name
}

data class NeedItem(val row: CapturedRow, val kind: NeedKind, val waitingMinutes: Int = 0)
data class CapturedStack(val key: String, val label: String, val rows: List<CapturedRow>)
data class CaptureHealth(val today: Int, val sent: Int, val waiting: Int, val failed: Int, val kept: Int, val lastDeliveredAt: Long?)

data class CapturedLanes(
    val needsYou: List<NeedItem>, val today: List<CapturedStack>, val earlier: List<CapturedStack>,
    val rejected: List<CapturedRow>, val health: CaptureHealth
) { val isEmpty: Boolean get() = needsYou.isEmpty() && today.isEmpty() && earlier.isEmpty() && rejected.isEmpty() }

fun capturedNote(l: CapturedLanes): String = listOfNotNull(
    "Captured",
    l.health.today.takeIf { it > 0 }?.let { "$it today" },
    l.needsYou.size.takeIf { it > 0 }?.let { "$it ${if (it == 1) "needs" else "need"} you" }
).joinToString(" · ")

object CapturedModel {
    const val WAIT_MINUTES = 15
    private const val DAY = 24 * 60 * 60_000L
    private val MARKET_ZONE: ZoneId = ZoneId.of("Asia/Kolkata")
    private val SESSION_CLOSE: LocalTime = LocalTime.of(15, 30)
    private val SIDE = Regex("""\b(buy|sell|accumulate|exit|short|long)\b""", RegexOption.IGNORE_CASE)

    /** Start of "Today": the latest weekday 15:30 IST close at or before [now]. */
    fun todayBoundary(now: Long): Long {
        val at = Instant.ofEpochMilli(now).atZone(MARKET_ZONE)
        var close: ZonedDateTime = at.toLocalDate().atTime(SESSION_CLOSE).atZone(MARKET_ZONE)
        if (close.isAfter(at)) close = close.minusDays(1)
        while (close.dayOfWeek == DayOfWeek.SATURDAY || close.dayOfWeek == DayOfWeek.SUNDAY) close = close.minusDays(1)
        return close.toInstant().toEpochMilli()
    }

    fun lanes(
        events: List<NotificationEventEntity>,
        now: Long,
        stackBy: StackBy = StackBy.SOURCE,
        show: ShowOnly = ShowOnly.ALL,
        symbolOf: (String) -> String? = { null }
    ): CapturedLanes {
        val boundary = todayBoundary(now)
        val rows = events.mapNotNull { e -> e.toTradingInsight()?.let { buildRow(e, it, symbolOf) } }
        val health = health(rows, boundary)
        val active = rows.filter { it.event.lifecycleState != "RESOLVED" }

        val needs = active.mapNotNull { r ->
            when {
                r.event.deliveryState == DeliveryState.FAILED.name -> NeedItem(r, NeedKind.RETRY)
                r.event.deliveryState == DeliveryState.PENDING.name && now - r.event.postedAt > WAIT_MINUTES * 60_000L ->
                    NeedItem(r, NeedKind.WAITING, ((now - r.event.postedAt) / 60_000L).toInt())
                else -> null
            }
        }.sortedByDescending { it.row.event.postedAt }
        val needIds = needs.map { it.row.event.id }.toSet()

        val rejected = rows.filter { it.event.id !in needIds && isRejected(it) }.sortedByDescending { it.event.postedAt }
        val rest = rows.filter { it.event.id !in needIds && !isRejected(it) }
            .let { fold(it) }
            .filter { show != ShowOnly.TIPS || !it.keptLocal }

        val (today, earlier) = rest.partition { it.event.lifecycleState != "RESOLVED" && it.event.postedAt >= boundary }
        if (show == ShowOnly.NEEDS_YOU) return CapturedLanes(needs, emptyList(), emptyList(), emptyList(), health)
        return CapturedLanes(
            needs, stacks(today, stackBy), stacks(earlier, stackBy),
            if (show == ShowOnly.TIPS) emptyList() else rejected, health
        )
    }

    private fun isRejected(r: CapturedRow) = r.keptLocal && r.event.title.contains("rejected", ignoreCase = true)

    private fun buildRow(e: NotificationEventEntity, insight: TradingInsight, symbolOf: (String) -> String?): CapturedRow {
        val symbol = symbolOf("${e.title} ${e.body}")
        if (e.deliveryState == DeliveryState.NOT_APPLICABLE.name) {
            val generic = insight.headline == "Trading event detected"
            val first = e.body.lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.take(60) ?: insight.headline
            return CapturedRow(e, insight, null, if (generic) first else insight.headline + (symbol?.let { " · $it" } ?: ""))
        }
        val side = insight.marksyRecommendation?.let { SIDE.find(it)?.value }
            ?: SIDE.find(e.body)?.value ?: SIDE.find(e.title)?.value
        val tone = statusTone(insight.marksyLevelState)
        val levels = TipLevels(
            side?.uppercase(Locale.ROOT), symbol, insight.marksyEntryPrice, insight.marksyTargetPrice, insight.marksyStopLoss,
            insight.marksyLevelState?.let(::humanise), tone
        )
        val headline = when {
            levels.side != null && symbol != null -> "${levels.side} $symbol"
            symbol != null -> symbol
            else -> e.body.lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.take(60) ?: insight.headline
        }
        return CapturedRow(e, insight, levels, headline)
    }

    private fun statusTone(state: String?): StatusTone {
        val s = state?.lowercase(Locale.ROOT) ?: return StatusTone.NEUTRAL
        return when {
            "stop" in s || s.startsWith("sl") -> StatusTone.NEGATIVE
            "target" in s && "not" !in s -> StatusTone.POSITIVE
            else -> StatusTone.NEUTRAL
        }
    }

    private fun humanise(state: String): String =
        state.trim().replace('_', ' ').replace('-', ' ').lowercase(Locale.ROOT).replaceFirstChar { it.titlecase(Locale.ROOT) }

    internal fun sourceLabel(e: NotificationEventEntity): String =
        if (CaptureMedium.of(e.sourcePackage).isChat) e.title.ifBlank { e.sourceName } else e.sourceName

    private fun foldKey(body: String) = body.lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }

    /** A call forwarded to another group within a day folds into the first row; display only, each copy is its own ledger tip. */
    private fun fold(rows: List<CapturedRow>): List<CapturedRow> {
        class Cluster(val primary: CapturedRow, val labels: MutableList<String>, var copies: Int = 0)
        val clusters = mutableListOf<Cluster>()
        val byKey = mutableMapOf<String, MutableList<Cluster>>()
        for (r in rows.sortedBy { it.event.postedAt }) {
            val key = foldKey(r.event.body)
            val label = sourceLabel(r.event)
            if (r.keptLocal || key.length < 20) { clusters += Cluster(r, mutableListOf(label)); continue }
            val target = byKey[key].orEmpty().firstOrNull { c ->
                r.event.postedAt - c.primary.event.postedAt <= DAY && label !in c.labels
            }
            if (target != null) { target.labels += label; target.copies++; continue }
            Cluster(r, mutableListOf(label)).also { clusters += it; byKey.getOrPut(key) { mutableListOf() } += it }
        }
        return clusters.map { c -> if (c.copies == 0) c.primary else c.primary.copy(folded = c.copies, alsoIn = c.labels.drop(1)) }
    }

    private fun stacks(rows: List<CapturedRow>, by: StackBy): List<CapturedStack> =
        rows.groupBy { r -> if (by == StackBy.SYMBOL) r.levels?.symbol ?: "Other" else sourceLabel(r.event) }
            .map { (label, g) -> CapturedStack(label.lowercase(Locale.ROOT), label, g.sortedByDescending { it.event.postedAt }) }
            .sortedByDescending { it.rows.first().event.postedAt }

    private fun health(rows: List<CapturedRow>, boundary: Long): CaptureHealth {
        val today = rows.filter { it.event.postedAt >= boundary }
        fun count(vararg s: DeliveryState) = today.count { it.event.deliveryState in s.map(DeliveryState::name) }
        return CaptureHealth(
            today.size, count(DeliveryState.DELIVERED), count(DeliveryState.PENDING, DeliveryState.IN_FLIGHT),
            count(DeliveryState.FAILED), count(DeliveryState.NOT_APPLICABLE), rows.mapNotNull { it.event.insightReceivedAt }.maxOrNull()
        )
    }
}
