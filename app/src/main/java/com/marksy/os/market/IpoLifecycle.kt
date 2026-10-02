package com.marksy.os.market

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** Stage-aware facts for the IPO pages, pure so the lane, calculator and reminder rules are testable. */
object IpoLifecycle {
    val IST: ZoneId = ZoneId.of("Asia/Kolkata")
    /** Stages Home fetches; HANDED_OVER and WITHDRAWN are history. */
    val LIVE_STAGES = listOf("OPEN", "CLOSING_SOON", "CLOSED", "ALLOTMENT", "UPCOMING", "RECENTLY_LISTED")
    private val CUTOFF = LocalTime.of(17, 0)
    private val DAY = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)
    private val DM = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)
    private val HM = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH)

    enum class Lane(val label: String) { TODAY("Closes today"), OPEN("Open"), ALLOTMENT("Allotment"), UPCOMING("Opens soon"), LISTED("Listed") }
    enum class StageFilter(val label: String) { ALL("All stages"), OPEN("Open"), ALLOTMENT("Allotment"), UPCOMING("Opens soon"), LISTED("Listed"), WATCHING("Watching") }
    enum class Board(val label: String) { ALL("All boards"), MAIN("Mainboard"), SME("SME") }

    fun day(d: LocalDate): String = d.format(DAY)
    fun dm(d: LocalDate): String = d.format(DM)
    fun timeOf(iso: String?): ZonedDateTime? = iso?.let { runCatching { OffsetDateTime.parse(it).atZoneSameInstant(IST) }.getOrNull() }
    fun dateOf(iso: String?): LocalDate? = timeOf(iso)?.toLocalDate() ?: iso?.let { runCatching { LocalDate.parse(it.take(10)) }.getOrNull() }

    /** "10:15" today, "29 Sep" before. */
    fun asOfLabel(iso: String?, now: ZonedDateTime): String? {
        val t = timeOf(iso) ?: return dateOf(iso)?.let(::dm)
        return if (t.toLocalDate() == now.toLocalDate()) t.format(HM) else dm(t.toLocalDate())
    }

    fun laneOf(ipo: IpoListItemDto, now: ZonedDateTime): Lane? = when (ipo.stage?.uppercase(Locale.ROOT)) {
        "OPEN", "CLOSING_SOON" -> if (ipo.closesOn.localDate() == now.toLocalDate()) Lane.TODAY else Lane.OPEN
        "CLOSED", "ALLOTMENT" -> Lane.ALLOTMENT
        "UPCOMING" -> Lane.UPCOMING
        "RECENTLY_LISTED" -> Lane.LISTED
        else -> null
    }

    private fun inStage(lane: Lane, stage: StageFilter) = when (stage) {
        StageFilter.ALL, StageFilter.WATCHING -> true
        StageFilter.OPEN -> lane == Lane.TODAY || lane == Lane.OPEN
        StageFilter.ALLOTMENT -> lane == Lane.ALLOTMENT
        StageFilter.UPCOMING -> lane == Lane.UPCOMING
        StageFilter.LISTED -> lane == Lane.LISTED
    }

    fun lanes(items: List<IpoListItemDto>, stage: StageFilter, board: Board, query: String, watched: Set<String>, now: ZonedDateTime): List<Pair<Lane, List<IpoListItemDto>>> {
        val q = query.trim().lowercase(Locale.ROOT)
        val kept = items.distinctBy { it.id }.mapNotNull { ipo -> laneOf(ipo, now)?.let { ipo to it } }.filter { (ipo, lane) ->
            (board == Board.ALL || ipo.isSme == (board == Board.SME)) && inStage(lane, stage) &&
                (stage != StageFilter.WATCHING || ipo.id in watched) &&
                (q.isEmpty() || "${ipo.companyName} ${ipo.sector.orEmpty()}".lowercase(Locale.ROOT).contains(q))
        }
        return Lane.entries.mapNotNull { lane ->
            val xs = kept.filter { it.second == lane }.map { it.first }
            val sorted = when (lane) {
                Lane.UPCOMING -> xs.sortedBy { it.opensOn.localDate() ?: LocalDate.MAX }
                Lane.ALLOTMENT -> xs.sortedBy { it.listsOn.localDate() ?: LocalDate.MAX }
                Lane.LISTED -> xs.sortedByDescending { it.listsOn.localDate() ?: LocalDate.MIN }
                else -> xs.sortedBy { it.closesOn.localDate() ?: LocalDate.MAX }
            }
            if (sorted.isEmpty()) null else lane to sorted
        }
    }

    fun homeNote(items: List<IpoListItemDto>, stage: StageFilter, board: Board, query: String, now: ZonedDateTime): String {
        if (stage != StageFilter.ALL || board != Board.ALL || query.isNotBlank())
            return listOfNotNull("IPOs", board.takeIf { it != Board.ALL }?.label, stage.takeIf { it != StageFilter.ALL }?.label, query.trim().takeIf(String::isNotEmpty)?.let { "“$it”" }).joinToString(" · ")
        val lanes = items.distinctBy { it.id }.mapNotNull { laneOf(it, now) }
        val open = lanes.count { it == Lane.TODAY || it == Lane.OPEN }
        val today = lanes.count { it == Lane.TODAY }
        val soon = lanes.count { it == Lane.UPCOMING }
        return when {
            open > 0 -> "IPOs · $open open" + if (today > 0) " · $today closing today" else ""
            soon > 0 -> "IPOs · $soon opening soon"
            else -> "IPOs"
        }
    }

    fun upper(ipo: IpoListItemDto): Double? = ipo.terms?.priceBand.bounds()?.second?.takeIf { it > 0 }
    fun lotSize(ipo: IpoListItemDto): Int? = ipo.terms?.lotSize.number()?.roundToInt()?.takeIf { it > 0 }
    fun lotCost(ipo: IpoListItemDto): Double? = upper(ipo)?.let { u -> lotSize(ipo)?.let { it * u } }
    fun minLots(ipo: IpoListItemDto) = if (ipo.isSme) 2 else 1
    fun minBid(ipo: IpoListItemDto): Double? = lotCost(ipo)?.let { it * minLots(ipo) } ?: ipo.terms?.minInvestment.number()
    fun closeAt(ipo: IpoListItemDto): ZonedDateTime? = ipo.closesOn.localDate()?.atTime(CUTOFF)?.atZone(IST)

    fun leftText(d: Duration): String {
        val m = d.toMinutes().coerceAtLeast(1)
        if (m < 60) return "$m min"
        val h = m / 60
        if (h < 24) return "$h h ${m % 60} m"
        val days = ((h + 12) / 24).toInt()
        return "$days day${if (days > 1) "s" else ""}"
    }

    fun times(x: Double): String = (if (x >= 100) "%.0f" else if (x >= 10) "%.1f" else "%.2f").format(Locale.ENGLISH, x) + "x"
    fun inr(n: Double): String = "₹" + IpoDetailFormatter.number(n.roundToLong())
    fun pct(p: Double, decimals: Int = 1): String = (if (p >= 0) "+" else "−") + "%.${decimals}f".format(Locale.ENGLISH, abs(p)) + "%"
    fun rupees(p: Double): String = (if (p >= 0) "+" else "−") + "₹" + IpoDetailFormatter.number(abs(p))
    fun lotsText(n: Int) = "$n lot${if (n == 1) "" else "s"}"

    fun odds(probability: Double): String? = when {
        probability <= 0 -> null
        probability >= 0.995 -> "Full allotment"
        else -> "About 1 in ${(1 / probability).roundToInt().coerceAtLeast(2)}"
    }

    /** Marksy's estimate, else 1 ÷ the retail subscription, marked as an estimate. */
    fun retailOdds(ipo: IpoListItemDto): String? = ipo.retailAllocation?.probability?.let(::odds)
        ?: ipo.subscription?.latest?.get("RETAIL")?.times?.let { x -> if (x <= 1) "Full allotment" else "About 1 in ${x.roundToInt()} (est.)" }

    data class CardFacts(val stat: String, val statLabel: String, val statUp: Boolean?, val line: String, val chip: String? = null)

    fun cardFacts(ipo: IpoListItemDto, now: ZonedDateTime): CardFacts {
        val overall = ipo.subscription?.latest?.get("OVERALL")?.times
        val subscribed = overall?.let(::times) ?: "–"
        val min = minBid(ipo)?.let { " · min ${inr(it)}" }.orEmpty()
        val lists = ipo.listsOn.localDate()
        return when (laneOf(ipo, now)) {
            Lane.TODAY -> CardFacts(
                subscribed, "subscribed", overall?.let { it >= 1 }, "Bid and approve the UPI mandate by 5 pm$min",
                closeAt(ipo)?.let { Duration.between(now, it) }?.takeIf { !it.isNegative }?.let { "Closes today · ${leftText(it)} left" } ?: "Closes today"
            )
            Lane.OPEN -> CardFacts(subscribed, "subscribed", overall?.let { it >= 1 }, (ipo.closesOn.localDate()?.let { "Closes ${day(it)}" } ?: "Open") + min)
            Lane.ALLOTMENT -> CardFacts(
                subscribed, "final", overall?.let { it >= 1 },
                listOfNotNull(retailOdds(ipo)?.let { "${if (ipo.isSme) "Individual" else "Retail"} ${it.replaceFirstChar(Char::lowercase)}" }, lists?.let { "lists ${day(it)}" })
                    .joinToString(" · ").replaceFirstChar(Char::uppercase).ifEmpty { "Bidding closed" }
            )
            Lane.UPCOMING -> {
                val opens = ipo.opensOn.localDate()
                val band = ipo.terms?.priceBand.bounds()
                CardFacts(
                    opens?.let(::dm) ?: "Soon", "opens", null,
                    band?.let { (lo, hi) -> "₹${IpoDetailFormatter.number(lo)}–${IpoDetailFormatter.number(hi)}$min" }
                        ?: ("Price band not out yet" + (opens?.let { o -> " · bids ${dm(o)}" + (ipo.closesOn.localDate()?.let { " – ${dm(it)}" } ?: "") } ?: ""))
                )
            }
            Lane.LISTED, null -> CardFacts(lists?.let(::dm) ?: "–", "listed", null, lists?.let { "Listed ${day(it)}" } ?: "Listed")
        }
    }

    fun stageWord(ipo: IpoListItemDto, now: ZonedDateTime): String = when (laneOf(ipo, now)) {
        Lane.TODAY -> closeAt(ipo)?.let { Duration.between(now, it) }?.takeIf { !it.isNegative && it.toMinutes() < 60 }?.let { "closes in ${leftText(it)}" } ?: "closes 5 pm"
        Lane.OPEN -> ipo.closesOn.localDate()?.let { "open till ${dm(it)}" } ?: "open"
        Lane.UPCOMING -> ipo.opensOn.localDate()?.let { "opens ${dm(it)}" } ?: "opens soon"
        Lane.ALLOTMENT -> ipo.listsOn.localDate()?.let { "allotment · lists ${dm(it)}" } ?: "allotment"
        Lane.LISTED -> ipo.listsOn.localDate()?.let { "listed ${dm(it)}" } ?: "listed"
        null -> "stage not established"
    }

    fun detailNote(ipo: IpoListItemDto, now: ZonedDateTime) = "${ipo.companyName} · ${stageWord(ipo, now)}"

    data class GmpSummary(val text: String, val lowestPremium: Double)

    fun gmpPercent(r: IpoGmpReading, upper: Double?): Double? = r.premiumPercent ?: upper?.let { r.premium / it * 100 }

    /** "+9–11%" when both ends share a sign, "−1% to +11%" across zero. */
    private fun range(lo: Double, hi: Double, unit: (Double) -> String): String {
        val a = lo.roundToInt()
        val b = hi.roundToInt()
        return when {
            a == b -> unit(lo)
            a >= 0 -> unit(lo).removeSuffix("%") + "–" + unit(hi).drop(1).removePrefix("₹")
            else -> "${unit(lo)} to ${unit(hi)}"
        }
    }

    fun gmpSummary(gmp: IpoGmpDto?, upper: Double?, now: ZonedDateTime): GmpSummary? {
        val rs = gmp?.readings.orEmpty()
        if (rs.isEmpty()) return null
        val newest = rs.maxByOrNull { timeOf(it.observedAt)?.toInstant() ?: Instant.MIN }
        val pcts = rs.map { gmpPercent(it, upper) }
        val value = when {
            rs.size == 1 -> rupees(rs[0].premium) + (pcts[0]?.let { " (${pct(it, 0)})" } ?: "")
            pcts.all { it != null } -> range(pcts.minOf { it!! }, pcts.maxOf { it!! }) { pct(it, 0) }
            else -> range(rs.minOf { it.premium }, rs.maxOf { it.premium }) { rupees(it.roundToInt().toDouble()) }
        }
        val parts = listOfNotNull("GMP $value", if (rs.size > 1) "${rs.size} sources" else null, "unofficial", asOfLabel(newest?.observedAt, now))
        return GmpSummary(parts.joinToString(" · "), rs.minOf { it.premium })
    }

    data class SubscriptionDay(val date: LocalDate, val values: Map<String, Double>, val observedAt: String?)

    /** One entry per IST day with readings, holding each category's last reading that day; Day N = index + 1. */
    fun subscriptionDays(sub: IpoSubscriptionDto?): List<SubscriptionDay> {
        val byDay = sortedMapOf<LocalDate, MutableMap<String, IpoSubscriptionReading>>()
        fun at(r: IpoSubscriptionReading) = timeOf(r.observedAt)?.toInstant() ?: Instant.MIN
        val series = sub?.series.orEmpty().ifEmpty { sub?.latest.orEmpty().mapValues { listOf(it.value) } }
        series.forEach { (category, readings) ->
            readings.forEach { r ->
                val d = dateOf(r.observedAt) ?: return@forEach
                val slot = byDay.getOrPut(d) { mutableMapOf() }
                if (slot[category]?.let { at(it) <= at(r) } != false) slot[category] = r
            }
        }
        return byDay.map { (d, m) -> SubscriptionDay(d, m.mapValues { it.value.times }, m.values.maxByOrNull(::at)?.observedAt) }
    }

    data class LotCategory(val key: String, val label: String, val minLots: Int, val maxLots: Int, val pool: String, val cutOff: Boolean)

    fun lotCategories(lotCost: Double, isSme: Boolean): List<LotCategory> {
        if (isSme) return listOf(LotCategory("IND", "Individual · 2 lots", 2, 2, "RETAIL", true), LotCategory("HNI", "HNI · 3 lots and up", 3, 50, "NII", false))
        val retailMax = floor(200_000 / lotCost).toInt().coerceAtLeast(1)
        val smallMax = floor(1_000_000 / lotCost).toInt().coerceAtLeast(retailMax + 1)
        return listOf(
            LotCategory("RET", "Retail · up to ₹2 lakh", 1, retailMax, "RETAIL", true),
            LotCategory("SHNI", "Small HNI · ₹2–10 lakh", retailMax + 1, smallMax, "NII", false),
            LotCategory("BHNI", "Big HNI · over ₹10 lakh", smallMax + 1, smallMax * 5, "NII", false)
        )
    }

    data class LotQuote(val amount: Double, val chance: String, val gain: Double?, val gainLots: Int, val callout: String?, val next: LotCategory?)

    fun lotQuote(categories: List<LotCategory>, cat: LotCategory, lots: Int, lotSize: Int, upper: Double, poolTimes: Double?, lowestGmp: Double?, poolName: String): LotQuote {
        val amount = lots * lotSize * upper
        val lottery = poolTimes != null && poolTimes > 1
        val chance = when {
            poolTimes == null -> "Shows once bidding opens"
            !lottery -> "Full, if it stays under 1x"
            else -> "About 1 in ${poolTimes.roundToInt()}"
        }
        val got = if (lottery) cat.minLots else lots
        val callout = if (lottery && lots > cat.minLots) "$poolName is ${times(poolTimes!!)} subscribed, so allotment is a lottery for ${lotsText(cat.minLots)}. " +
            "Bidding ${lotsText(lots)} blocks ${inr(amount)} for the same chance as ${lotsText(cat.minLots)} (${inr(cat.minLots * lotSize * upper)})." else null
        val next = categories.getOrNull(categories.indexOf(cat) + 1)?.takeIf { lots >= cat.maxLots }
        return LotQuote(amount, chance, lowestGmp?.let { got * lotSize * it }, got, callout, next)
    }

    data class ReminderEvent(val key: String, val title: String, val whenText: String, val at: ZonedDateTime)

    private val KEY_DATE_LABELS = mapOf("Opens" to "open", "Closes" to "close", "Allotment" to "allot", "Refunds" to "refund", "Shares in demat" to "demat", "Lists" to "list")

    /** The detail's key dates by event key; the summary's own dates fill any gap. */
    fun keyDates(detail: IpoDetailDto?, ipo: IpoListItemDto): Map<String, LocalDate> =
        listOfNotNull(ipo.opensOn.localDate()?.let { "open" to it }, ipo.closesOn.localDate()?.let { "close" to it }, ipo.listsOn.localDate()?.let { "list" to it }).toMap() +
            detail?.keyDates.orEmpty().mapNotNull { k -> KEY_DATE_LABELS[k.label]?.let { key -> k.date.localDate()?.let { key to it } } }

    fun reminderEvents(dates: Map<String, LocalDate>, now: ZonedDateTime): List<ReminderEvent> = listOfNotNull(
        dates["open"]?.let { ReminderEvent("open", "Bidding opens", "${day(it)}, 10:00 am", it.atTime(10, 0).atZone(IST)) },
        dates["close"]?.let { ReminderEvent("close", "Last day to bid", "${day(it)}, 3:00 pm · 2 h before the 5 pm cutoff", it.atTime(15, 0).atZone(IST)) },
        dates["allot"]?.let { ReminderEvent("allot", "Allotment results", "${day(it)}, 6:00 pm", it.atTime(18, 0).atZone(IST)) },
        dates["list"]?.let { ReminderEvent("list", "Listing day", "${day(it)}, 9:55 am · trading starts at 10:00", it.atTime(9, 55).atZone(IST)) }
    ).filter { it.at.isAfter(now) }

    fun reminderKey(ipoId: String, event: String) = "ipo|$ipoId|$event"
}
