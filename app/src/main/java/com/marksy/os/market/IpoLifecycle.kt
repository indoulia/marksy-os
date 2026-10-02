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
}
