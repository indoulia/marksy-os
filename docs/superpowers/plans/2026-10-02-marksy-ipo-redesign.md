# Marksy IPO Redesign Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the IPO stage-pill list and key/value detail page with stage lanes and a decision-first detail page, as in the approved mockup.

**Architecture:** The new API blocks are parsed in `market/IpoModels.kt`. All stage, calculator, GMP and reminder rules live in a pure `market/IpoLifecycle.kt` that takes an IST `now`. The Compose screens only lay the results out. Reminders reuse Plan items (`FOLLOW_UP` fires at its due time) through `PlanRepository.setIpoReminder`. The IPO tab renders its own `OneHandControls`, and MarketScreen skips its own on that tab. The header note reaches MainActivity through a callback.

**Tech Stack:** Kotlin 2.3, Jetpack Compose (BOM 2026.08), org.json, JUnit4 + Robolectric 4.17, Room.

**Spec:** `docs/superpowers/specs/2026-10-02-marksy-ipo-redesign-design.md`

## Global Constraints

- No backend changes. Parse only what the design needs.
- No broker hand-off of any kind: no deep links, no "Copy bid details", no "I applied".
- GMP shows on home cards and detail, always with "unofficial" and its as-of time. Multiple sources show as a range plus a source count.
- The UPI mandate cutoff is 5 pm IST on the closing day, worded as the usual cutoff.
- No heading, label or button rows at the top of a page. Page actions go on the floating stack; destructive ones never do. The first 100dp under the header is content.
- Use `MarksyDialog` for popups, `Pill` (`WatchlistScreen.kt:361`) in a wrapping `FlowRow`, and `MarksyTheme` colours only.
- Comments are one line max and only for a non-obvious WHY.
- Tests cover logic only, with no layout tests. Never run `connectedAndroidTest`.
- Gradle in Git Bash: `export PATH="$(echo "$PATH" | tr ':' '\n' | grep -vE '"|%' | paste -sd:)"` then `./gradlew --no-daemon`.
- Commits are concise and end with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

## Review Focus

- An issue with no price band or lot size (UPCOMING before the RHP): cards and calculator say "not out yet" and never divide by zero. `cardFacts` test.
- GMP sources that disagree or are negative: a range with correct signs, and the calculator uses the lowest. `gmpSummary` test.
- A subscription series spanning a market holiday or with a day missing a category: days come from the readings themselves. `subscriptionDays` test.
- Reminder times already past (opened after 3 pm on the closing day): those events are not offered. `reminderEvents` test.
- Back from detail: the list keeps its data and scroll. List data and `LazyListState` are hoisted above the detail branch (checked in the render pass).

---

### Task 1: Parse GMP, subscription, retail odds and the detail blocks

**Files:**
- Modify: `app/src/main/java/com/marksy/os/market/IpoModels.kt`
- Test: `app/src/test/java/com/marksy/os/market/IpoModelsTest.kt`

**Interfaces:**
- Produces: `Any?.asDouble()`, `IpoValueDto?.number(): Double?`, `IpoValueDto?.localDate(): LocalDate?`, `IpoValueDto?.bounds(): Pair<Double, Double>?` and `IpoValueDto?.stateNote(): String?`.
- Produces DTOs:
  - `IpoGmpReading(source, premium, premiumPercent, observedAt)` and `IpoGmpDto(state, readings)`.
  - `IpoSubscriptionReading(times, observedAt)` and `IpoSubscriptionDto(state, asOf, series, latest)`.
  - `IpoAllocationDto(probability, oversubscription)`.
  - `IpoKeyDate(label, date)`, `IpoDecisionContext(context, question, verdict, confidence, reason, answeredBy)` and `IpoOutcomeDto(issuePrice, listingPrice, listingReturnPercent, expectedReturnPercent)`.
- New fields:
  - `IpoListItemDto`: `gmp`, `subscription`, `retailAllocation`, all defaulting to null.
  - `IpoDetailDto`: `keyDates`, `decisionContexts`, `outcome`, `anchorCrore` and `overview`, with defaults.

- [ ] **Step 1: Write the failing tests** (append to `IpoModelsTest`)

```kotlin
@Test
fun parsesGmpSubscriptionAndRetailEstimate() {
    val ipo = IpoListItemDto.parse(JSONObject("""
        {"id":"kaveri","companyName":"Kaveri Hospitals","isSme":false,"stage":"OPEN",
         "gmp":{"state":"AVAILABLE","readings":[{"source":"ipoji.com","premium":48,"premiumPercent":11.06,"observedAt":"2026-10-01T04:45:00Z"},{"source":"other.in","premium":"40.5","premiumPercent":null,"observedAt":null}]},
         "subscription":{"state":"AVAILABLE","asOf":"2026-10-01T05:10:00Z",
           "series":{"RETAIL":[{"category":"RETAIL","timesSubscribed":1.18,"observedAt":"2026-09-29T11:30:00Z"},{"category":"RETAIL","timesSubscribed":2.41,"observedAt":"2026-09-30T11:30:00Z"}]},
           "latest":{"OVERALL":{"category":"OVERALL","timesSubscribed":2.31,"observedAt":"2026-10-01T05:10:00Z"}}},
         "retailAllocationEstimate":{"category":"RETAIL","probability":{"state":"AVAILABLE","value":0.25},"oversubscription":{"state":"AVAILABLE","value":3.92}}}
    """))
    assertEquals(listOf("ipoji.com", "other.in"), ipo.gmp!!.readings.map { it.source })
    assertEquals(40.5, ipo.gmp!!.readings[1].premium, 1e-9)
    assertNull(ipo.gmp!!.readings[1].premiumPercent)
    assertEquals(2, ipo.subscription!!.series.getValue("RETAIL").size)
    assertEquals(2.31, ipo.subscription!!.latest.getValue("OVERALL").times, 1e-9)
    assertEquals(0.25, ipo.retailAllocation!!.probability!!, 1e-9)
}

@Test
fun parsesDetailDatesVerdictsAndOutcome() {
    val d = IpoDetailDto.parse(JSONObject("""
        {"summary":{"id":"kaveri","companyName":"Kaveri Hospitals"},
         "keyDates":[{"label":"Allotment","date":{"state":"AVAILABLE","value":"2026-10-05"}},{"label":"Refunds","date":{"state":"MISSING","value":null}}],
         "decisionContexts":[{"context":"PARTICIPATION","question":"Is it worth applying?","verdict":"APPLY","confidence":{"state":"AVAILABLE","value":0.8},
           "supporting":[{"supportive":true,"description":"Retail is a lottery past 1x."}],"opposing":[],"uncertainties":["Thin data"]}],
         "outcome":{"issuePrice":{"state":"AVAILABLE","value":434},"listingPrice":{"state":"AVAILABLE","value":479},"listingReturnPercent":{"state":"AVAILABLE","value":10.37},"expectedReturnPercent":{"state":"AVAILABLE","value":9.5}},
         "anchorBook":{"state":"AVAILABLE","totalAmountCrore":552.0},
         "companyOverview":{"state":"AVAILABLE","value":"Runs 14 hospitals."}}
    """))
    assertEquals(java.time.LocalDate.of(2026, 10, 5), d.keyDates.first { it.label == "Allotment" }.date.localDate())
    assertNull(d.keyDates.first { it.label == "Refunds" }.date.localDate())
    val c = d.decisionContexts.single()
    assertEquals("APPLY", c.verdict); assertEquals(0.8, c.confidence!!, 1e-9); assertEquals("Retail is a lottery past 1x.", c.reason)
    assertEquals(10.37, d.outcome!!.listingReturnPercent!!, 1e-9)
    assertEquals(552.0, d.anchorCrore!!, 1e-9)
    assertEquals("Runs 14 hospitals.", d.overview?.value)
}

@Test
fun stateNoteFlagsStaleMissingAndConflicting() {
    assertNull(IpoValueDto("AVAILABLE", 434, "2026-09-20T00:00:00Z").stateNote())
    assertEquals("as of 20 Sep", IpoValueDto("STALE", 434, "2026-09-20T00:00:00Z").stateNote())
    assertEquals("not out yet", IpoValueDto("MISSING", null, null).stateNote())
    assertEquals("not out yet", (null as IpoValueDto?).stateNote())
    assertEquals("sources disagree", IpoValueDto("CONFLICTING", 434, null).stateNote())
    assertEquals(412.0 to 434.0, IpoValueDto("AVAILABLE", JSONObject("""{"lower":"412","upper":434}"""), null).bounds())
}
```

- [ ] **Step 2: Run to verify they fail.** `./gradlew --no-daemon :app:testDebugUnitTest --tests "com.marksy.os.market.IpoModelsTest"`. Expected: compile errors (`gmp`, `keyDates`, `stateNote` undefined).

- [ ] **Step 3: Implement** (append to `IpoModels.kt`; add the new fields to `IpoListItemDto` and `IpoDetailDto` with defaults, parsed in their `parse`)

```kotlin
internal fun Any?.asDouble(): Double? = when (this) { is Number -> toDouble(); is String -> toDoubleOrNull(); else -> null }

/** The fact as a number; Marksy sends some decimals as strings. */
fun IpoValueDto?.number(): Double? = this?.value.asDouble()

fun IpoValueDto?.localDate(): java.time.LocalDate? =
    (this?.value as? String)?.let { runCatching { java.time.LocalDate.parse(it.take(10)) }.getOrNull() }

/** (lower, upper) of a band-shaped fact. */
fun IpoValueDto?.bounds(): Pair<Double, Double>? {
    val o = this?.value as? JSONObject ?: return null
    return listOf("lower" to "upper", "low" to "high", "min" to "max", "from" to "to").firstNotNullOfOrNull { (a, b) ->
        val lo = o.opt(a).asDouble(); val hi = o.opt(b).asDouble()
        if (lo != null && hi != null) lo to hi else null
    }
}

/** Why a fact can't be read as current, or null when it can. */
fun IpoValueDto?.stateNote(): String? = when {
    this == null || value == null -> when (this?.state) { "INSUFFICIENT_EVIDENCE" -> "not enough evidence"; "CONFLICTING" -> "sources disagree"; else -> "not out yet" }
    state == "STALE" -> IpoLifecycle.dateOf(asOf)?.let { "as of ${IpoLifecycle.dm(it)}" } ?: "may be out of date"
    state == "CONFLICTING" -> "sources disagree"
    state == "INSUFFICIENT_EVIDENCE" -> "not enough evidence"
    else -> null
}

data class IpoGmpReading(val source: String, val premium: Double, val premiumPercent: Double?, val observedAt: String?)

/** Every source's current quote; there is deliberately no single "the GMP". */
data class IpoGmpDto(val state: String, val readings: List<IpoGmpReading>) {
    companion object {
        fun parse(json: JSONObject?): IpoGmpDto? = json?.let { g ->
            IpoGmpDto(g.textOrNull("state") ?: "UNAVAILABLE", g.optJSONArray("readings").objects().mapNotNull { r ->
                r.opt("premium").asDouble()?.let { IpoGmpReading(r.textOrNull("source") ?: "Unknown", it, r.opt("premiumPercent").asDouble(), r.textOrNull("observedAt")) }
            })
        }
    }
}

data class IpoSubscriptionReading(val times: Double, val observedAt: String?)

data class IpoSubscriptionDto(val state: String, val asOf: String?, val series: Map<String, List<IpoSubscriptionReading>>, val latest: Map<String, IpoSubscriptionReading>) {
    companion object {
        private fun reading(o: JSONObject?) = o?.opt("timesSubscribed").asDouble()?.let { IpoSubscriptionReading(it, o?.textOrNull("observedAt")) }
        fun parse(json: JSONObject?): IpoSubscriptionDto? {
            if (json == null) return null
            val series = json.optJSONObject("series"); val latest = json.optJSONObject("latest")
            return IpoSubscriptionDto(
                state = json.textOrNull("state") ?: "UNAVAILABLE", asOf = json.textOrNull("asOf"),
                series = series?.keys()?.asSequence()?.associateWith { k -> series.optJSONArray(k).objects().mapNotNull(::reading) }.orEmpty(),
                latest = latest?.keys()?.asSequence()?.mapNotNull { k -> reading(latest.optJSONObject(k))?.let { k to it } }?.toMap().orEmpty()
            )
        }
    }
}

data class IpoAllocationDto(val probability: Double?, val oversubscription: Double?) {
    companion object {
        fun parse(json: JSONObject?): IpoAllocationDto? = json?.let {
            IpoAllocationDto(IpoValueDto.parse(it.optJSONObject("probability")).number(), IpoValueDto.parse(it.optJSONObject("oversubscription")).number())
        }
    }
}

data class IpoKeyDate(val label: String, val date: IpoValueDto?)

data class IpoDecisionContext(val context: String, val question: String?, val verdict: String, val confidence: Double?, val reason: String?, val answeredBy: String?) {
    companion object {
        fun parse(json: JSONObject): IpoDecisionContext {
            fun first(key: String): String? = json.optJSONArray(key).let { a ->
                (0 until (a?.length() ?: 0)).firstNotNullOfOrNull { i -> a!!.opt(i).let { (it as? JSONObject)?.textOrNull("description") ?: (it as? String)?.takeIf(String::isNotBlank) } }
            }
            return IpoDecisionContext(
                context = json.textOrNull("context") ?: "", question = json.textOrNull("question"), verdict = json.textOrNull("verdict") ?: "NO_DECISION",
                confidence = IpoValueDto.parse(json.optJSONObject("confidence")).number(),
                reason = first("supporting") ?: first("opposing") ?: first("uncertainties"), answeredBy = json.textOrNull("answeredBy")
            )
        }
    }
}

data class IpoOutcomeDto(val issuePrice: Double?, val listingPrice: Double?, val listingReturnPercent: Double?, val expectedReturnPercent: Double?) {
    companion object {
        fun parse(json: JSONObject?): IpoOutcomeDto? = json?.let { o ->
            fun n(k: String) = IpoValueDto.parse(o.optJSONObject(k)).number()
            IpoOutcomeDto(n("issuePrice"), n("listingPrice"), n("listingReturnPercent"), n("expectedReturnPercent"))
        }
    }
}
```

Field additions:
- `IpoListItemDto.parse`: `gmp = IpoGmpDto.parse(json.optJSONObject("gmp"))`, `subscription = IpoSubscriptionDto.parse(json.optJSONObject("subscription"))`, `retailAllocation = IpoAllocationDto.parse(json.optJSONObject("retailAllocationEstimate"))`.
- `IpoDetailDto.parse`: `keyDates = json.optJSONArray("keyDates").objects().map { IpoKeyDate(it.textOrNull("label") ?: "", IpoValueDto.parse(it.optJSONObject("date"))) }`, `decisionContexts = json.optJSONArray("decisionContexts").objects().map(IpoDecisionContext::parse)`, `outcome = IpoOutcomeDto.parse(json.optJSONObject("outcome"))`, `anchorCrore = json.optJSONObject("anchorBook")?.opt("totalAmountCrore").asDouble()`, `overview = IpoValueDto.parse(json.optJSONObject("companyOverview"))`.

- [ ] **Step 4: Run the tests and verify they pass** (same command; `IpoLifecycle.dateOf`/`dm` come from Task 2, so do Tasks 1–2 Step 3 together before the green run).
- [ ] **Step 5: Commit** `feat(ipo): parse GMP readings, subscription series, retail odds and detail blocks`

### Task 2: IpoLifecycle (lanes, notes, card facts, GMP summary, odds)

**Files:**
- Create: `app/src/main/java/com/marksy/os/market/IpoLifecycle.kt`
- Test: `app/src/test/java/com/marksy/os/market/IpoLifecycleTest.kt`

**Interfaces:**
- Produces:
  - `IpoLifecycle.IST`, `LIVE_STAGES`, enums `Lane`, `StageFilter` and `Board`.
  - Date helpers: `day(LocalDate)`, `dm(LocalDate)`, `dateOf(String?)`, `timeOf(String?)` and `asOfLabel(String?, ZonedDateTime)`.
  - Lanes: `laneOf(ipo, now): Lane?` and `lanes(items, stage, board, query, watched, now): List<Pair<Lane, List<IpoListItemDto>>>`.
  - Notes: `homeNote(items, stage, board, query, now)`, `stageWord(ipo, now)` and `detailNote(ipo, now)`.
  - Money and lots: `upper(ipo)`, `lotSize(ipo)`, `lotCost(ipo)`, `minLots(ipo)`, `minBid(ipo)` and `closeAt(ipo)`.
  - Formatting: `leftText(Duration)`, `times(Double)`, `inr(Double)`, `pct(Double, Int)`, `rupees(Double)` and `lotsText(Int)`.
  - Odds: `odds(Double): String?` and `retailOdds(ipo): String?`.
  - `CardFacts(stat, statLabel, statUp, line, chip)` and `cardFacts(ipo, now)`.
  - GMP: `GmpSummary(text, lowestPremium)`, `gmpPercent(reading, upper)` and `gmpSummary(gmp, upper, now)`.

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.marksy.os.market

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.ZonedDateTime

class IpoLifecycleTest {
    private val now = ZonedDateTime.of(2026, 10, 1, 10, 42, 0, 0, IpoLifecycle.IST)
    private fun v(x: Any?) = IpoValueDto(if (x == null) "MISSING" else "AVAILABLE", x, null)
    private fun ipo(id: String, stage: String, opens: String? = null, closes: String? = null, lists: String? = null, sme: Boolean = false,
                    band: Pair<Int, Int>? = 412 to 434, lot: Int? = 34, sector: String = "Healthcare",
                    sub: IpoSubscriptionDto? = null, gmp: IpoGmpDto? = null, odds: Double? = null) = IpoListItemDto(
        id = id, companyName = "Co $id", issueName = null, isSme = sme, sector = sector, stage = stage,
        opensOn = v(opens), closesOn = v(closes), listsOn = v(lists),
        terms = IpoTermsDto(priceBand = band?.let { v(JSONObject().put("lower", it.first).put("upper", it.second)) }, lotSize = v(lot), issueSizeCrore = null),
        gmp = gmp, subscription = sub, retailAllocation = odds?.let { IpoAllocationDto(it, null) })

    private val book = listOf(
        ipo("today", "CLOSING_SOON", closes = "2026-10-01"), ipo("later", "OPEN", closes = "2026-10-05"),
        ipo("allot", "CLOSED", lists = "2026-10-07", odds = 0.25), ipo("up2", "UPCOMING", opens = "2026-10-09"),
        ipo("up1", "UPCOMING", opens = "2026-10-06", band = null, lot = null, sme = true), ipo("listed", "RECENTLY_LISTED", lists = "2026-09-28"),
        ipo("gone", "HANDED_OVER"))

    @Test fun lanesFollowStageAndCloseDate() {
        val lanes = IpoLifecycle.lanes(book, IpoLifecycle.StageFilter.ALL, IpoLifecycle.Board.ALL, "", emptySet(), now)
        assertEquals(listOf("TODAY" to listOf("today"), "OPEN" to listOf("later"), "ALLOTMENT" to listOf("allot"), "UPCOMING" to listOf("up1", "up2"), "LISTED" to listOf("listed")),
            lanes.map { (l, xs) -> l.name to xs.map { it.id } })
    }

    @Test fun filtersCombineBoardSearchAndWatching() {
        fun ids(stage: IpoLifecycle.StageFilter, board: IpoLifecycle.Board, q: String = "", w: Set<String> = emptySet()) =
            IpoLifecycle.lanes(book, stage, board, q, w, now).flatMap { it.second }.map { it.id }
        assertEquals(listOf("today", "later"), ids(IpoLifecycle.StageFilter.OPEN, IpoLifecycle.Board.ALL))
        assertEquals(listOf("up1"), ids(IpoLifecycle.StageFilter.ALL, IpoLifecycle.Board.SME))
        assertEquals(listOf("allot"), ids(IpoLifecycle.StageFilter.WATCHING, IpoLifecycle.Board.ALL, w = setOf("allot", "gone")))
        assertEquals(listOf("up2"), ids(IpoLifecycle.StageFilter.ALL, IpoLifecycle.Board.ALL, q = "co up2"))
    }

    @Test fun homeNoteCountsOpenAndClosingTodayOrNamesTheFilter() {
        assertEquals("IPOs · 2 open · 1 closing today", IpoLifecycle.homeNote(book, IpoLifecycle.StageFilter.ALL, IpoLifecycle.Board.ALL, "", now))
        assertEquals("IPOs · SME · Opens soon", IpoLifecycle.homeNote(book, IpoLifecycle.StageFilter.UPCOMING, IpoLifecycle.Board.SME, "", now))
        assertEquals("IPOs · 2 opening soon", IpoLifecycle.homeNote(book.filter { it.stage == "UPCOMING" }, IpoLifecycle.StageFilter.ALL, IpoLifecycle.Board.ALL, "", now))
    }

    @Test fun cardFactsFollowTheStage() {
        val sub = IpoSubscriptionDto("AVAILABLE", null, emptyMap(), mapOf("OVERALL" to IpoSubscriptionReading(2.31, null)))
        val today = IpoLifecycle.cardFacts(book[0].copy(subscription = sub), now)
        assertEquals("2.31x", today.stat)
        assertEquals("Bid and approve the UPI mandate by 5 pm · min ₹14,756", today.line)
        assertEquals("Closes today · 6 h 18 m left", today.chip)
        assertEquals("Price band not out yet · bids 6 Oct", IpoLifecycle.cardFacts(book[4], now).line)
        assertEquals("Retail about 1 in 4 · lists Wed 7 Oct", IpoLifecycle.cardFacts(book[2], now).line)
        assertEquals("closes 5 pm", IpoLifecycle.stageWord(book[0], now))
    }

    @Test fun gmpSummaryShowsEverySourceAsARange() {
        val one = IpoGmpDto("AVAILABLE", listOf(IpoGmpReading("ipoji.com", 48.0, null, "2026-10-01T04:45:00Z")))
        assertEquals("GMP +₹48 (+11%) · unofficial · 10:15", IpoLifecycle.gmpSummary(one, 434.0, now)!!.text)
        val many = IpoGmpDto("AVAILABLE", listOf(IpoGmpReading("a", 48.0, 11.1, "2026-10-01T04:45:00Z"), IpoGmpReading("b", 39.0, 9.0, "2026-09-30T12:00:00Z"), IpoGmpReading("c", -5.0, -1.2, null)))
        val s = IpoLifecycle.gmpSummary(many, 434.0, now)!!
        assertEquals("GMP −1% to +11% · 3 sources · unofficial · 10:15", s.text)
        assertEquals(-5.0, s.lowestPremium, 1e-9)
        assertNull(IpoLifecycle.gmpSummary(IpoGmpDto("EMPTY", emptyList()), 434.0, now))
    }
}
```

- [ ] **Step 2: Run to verify they fail.** `./gradlew --no-daemon :app:testDebugUnitTest --tests "com.marksy.os.market.IpoLifecycleTest"`. Expected: `IpoLifecycle` unresolved.

- [ ] **Step 3: Implement** `IpoLifecycle.kt`

```kotlin
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
            Lane.TODAY -> CardFacts(subscribed, "subscribed", overall?.let { it >= 1 }, "Bid and approve the UPI mandate by 5 pm$min",
                closeAt(ipo)?.let { Duration.between(now, it) }?.takeIf { !it.isNegative }?.let { "Closes today · ${leftText(it)} left" } ?: "Closes today")
            Lane.OPEN -> CardFacts(subscribed, "subscribed", overall?.let { it >= 1 }, (ipo.closesOn.localDate()?.let { "Closes ${day(it)}" } ?: "Open") + min)
            Lane.ALLOTMENT -> CardFacts(subscribed, "final", overall?.let { it >= 1 },
                listOfNotNull(retailOdds(ipo)?.let { "${if (ipo.isSme) "Individual" else "Retail"} ${it.replaceFirstChar(Char::lowercase)}" }, lists?.let { "lists ${day(it)}" })
                    .joinToString(" · ").replaceFirstChar(Char::uppercase).ifEmpty { "Bidding closed" })
            Lane.UPCOMING -> {
                val opens = ipo.opensOn.localDate()
                val band = ipo.terms?.priceBand.bounds()
                CardFacts(opens?.let(::dm) ?: "Soon", "opens", null, band?.let { (lo, hi) -> "₹${IpoDetailFormatter.number(lo)}–${IpoDetailFormatter.number(hi)}$min" }
                    ?: ("Price band not out yet" + (opens?.let { o -> " · bids ${dm(o)}" + (ipo.closesOn.localDate()?.let { " – ${dm(it)}" } ?: "") } ?: "")))
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

    private fun range(lo: Double, hi: Double, unit: (Double) -> String): String {
        val a = lo.roundToInt(); val b = hi.roundToInt()
        return when {
            a == b -> unit(lo)
            a >= 0 -> "${unit(lo)}–${unit(hi).drop(1)}".replace("%–", "–")
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
```

> Note on `range` for same-sign values: `pct(9.0, 0)` = `+9%` and `pct(11.0, 0)` = `+11%`. That yields `+9–11%`, and rupees `+₹40–48`. Mixed signs read `−1% to +11%`.

- [ ] **Step 4: Run the tests and verify they pass** (Task 1 and 2 classes together).
- [ ] **Step 5: Commit** `feat(ipo): lifecycle lanes, card facts, notes and multi-source GMP summary`

### Task 3: Subscription days, lot calculator, reminder events

**Files:**
- Modify: `app/src/main/java/com/marksy/os/market/IpoLifecycle.kt`
- Test: `app/src/test/java/com/marksy/os/market/IpoLifecycleTest.kt`

**Interfaces:**
- Produces:
  - `SubscriptionDay(date, values, observedAt)` and `subscriptionDays(sub): List<SubscriptionDay>`.
  - `LotCategory(key, label, minLots, maxLots, pool, cutOff)` and `lotCategories(lotCost, isSme)`.
  - `LotQuote(amount, chance, gain, gainLots, callout, next)` and `lotQuote(categories, cat, lots, lotSize, upper, poolTimes, lowestGmp, poolName)`.
  - `ReminderEvent(key, title, whenText, at)`, `keyDates(detail, ipo): Map<String, LocalDate>` (keys open/close/allot/refund/demat/list), `reminderEvents(dates, now)` and `reminderKey(ipoId, event)`.

- [ ] **Step 1: Write the failing tests** (append)

```kotlin
@Test fun subscriptionDaysKeepEachCategorysLastReadingPerIstDay() {
    val sub = IpoSubscriptionDto("AVAILABLE", null, mapOf(
        "RETAIL" to listOf(IpoSubscriptionReading(0.5, "2026-09-29T06:00:00Z"), IpoSubscriptionReading(1.18, "2026-09-29T11:30:00Z"), IpoSubscriptionReading(2.41, "2026-09-30T11:30:00Z")),
        "QIB" to listOf(IpoSubscriptionReading(0.93, "2026-09-30T11:30:00Z"))), emptyMap())
    val days = IpoLifecycle.subscriptionDays(sub)
    assertEquals(listOf(java.time.LocalDate.of(2026, 9, 29), java.time.LocalDate.of(2026, 9, 30)), days.map { it.date })
    assertEquals(mapOf("RETAIL" to 1.18), days[0].values)
    assertEquals(mapOf("RETAIL" to 2.41, "QIB" to 0.93), days[1].values)
}

@Test fun lotCategoriesFollowRupeeLimits() {
    val main = IpoLifecycle.lotCategories(14_756.0, isSme = false)
    assertEquals(listOf(1 to 13, 14 to 67), main.take(2).map { it.minLots to it.maxLots })
    assertEquals(68, main[2].minLots)
    assertEquals(listOf(2 to 2, 3 to 50), IpoLifecycle.lotCategories(120_000.0, isSme = true).map { it.minLots to it.maxLots })
}

@Test fun calculatorWarnsThatMoreLotsDoNotRaiseOddsPastOneTimes() {
    val cats = IpoLifecycle.lotCategories(14_756.0, isSme = false)
    val q = IpoLifecycle.lotQuote(cats, cats[0], lots = 5, lotSize = 34, upper = 434.0, poolTimes = 3.92, lowestGmp = 40.0, poolName = "Retail")
    assertEquals(73_780.0, q.amount, 1e-6)
    assertEquals("About 1 in 4", q.chance)
    assertEquals(1, q.gainLots); assertEquals(1_360.0, q.gain!!, 1e-6)
    assertEquals("Retail is 3.92x subscribed, so allotment is a lottery for 1 lot. Bidding 5 lots blocks ₹73,780 for the same chance as 1 lot (₹14,756).", q.callout)
    assertNull(IpoLifecycle.lotQuote(cats, cats[0], 5, 34, 434.0, 0.8, null, "Retail").callout)
    assertEquals("SHNI", IpoLifecycle.lotQuote(cats, cats[0], 13, 34, 434.0, null, null, "Retail").next?.key)
}

@Test fun reminderEventsSkipTimesAlreadyPast() {
    val dates = mapOf("open" to java.time.LocalDate.of(2026, 9, 29), "close" to java.time.LocalDate.of(2026, 10, 1), "allot" to java.time.LocalDate.of(2026, 10, 5))
    assertEquals(listOf("close", "allot"), IpoLifecycle.reminderEvents(dates, now).map { it.key })
    assertEquals(listOf("allot"), IpoLifecycle.reminderEvents(dates, now.withHour(15).withMinute(1)).map { it.key })
    assertEquals("ipo|kaveri|close", IpoLifecycle.reminderKey("kaveri", "close"))
}
```

- [ ] **Step 2: Run to verify they fail** (unresolved `subscriptionDays`, `lotCategories`, `lotQuote`, `reminderEvents`).
- [ ] **Step 3: Implement** (append inside `object IpoLifecycle`)

```kotlin
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
        LotCategory("BHNI", "Big HNI · over ₹10 lakh", smallMax + 1, smallMax * 5, "NII", false))
}

data class LotQuote(val amount: Double, val chance: String, val gain: Double?, val gainLots: Int, val callout: String?, val next: LotCategory?)

fun lotQuote(categories: List<LotCategory>, cat: LotCategory, lots: Int, lotSize: Int, upper: Double, poolTimes: Double?, lowestGmp: Double?, poolName: String): LotQuote {
    val amount = lots * lotSize * upper
    val lottery = poolTimes != null && poolTimes > 1
    val chance = when { poolTimes == null -> "Shows once bidding opens"; !lottery -> "Full, if it stays under 1x"; else -> "About 1 in ${poolTimes!!.roundToInt()}" }
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
```

- [ ] **Step 4: Run the `IpoLifecycleTest` tests and verify they pass.**
- [ ] **Step 5: Commit** `feat(ipo): subscription days, lot calculator and reminder events`

### Task 4: IPO reminders as Plan items

**Files:**
- Modify: `app/src/main/java/com/marksy/os/plan/PlanModels.kt:12` (add `IPO` to `PlanOrigin`)
- Modify: `app/src/main/java/com/marksy/os/plan/PlanRepository.kt` (after `mirrorFollowUp`)
- Test: `app/src/test/java/com/marksy/os/plan/PlanRepositoryTest.kt`

**Interfaces:**
- Produces: `suspend fun PlanRepository.setIpoReminder(key: String, title: String, at: Long?)`, where null clears it. Also `fun PlanRepository.observeIpoReminderKeys(prefix: String): Flow<Set<String>>`.

- [ ] **Step 1: Write the failing test**

```kotlin
@Test fun ipoReminderSchedulesOnceAndClears() = runBlocking {
    repo.setIpoReminder("ipo|kaveri|close", "Kaveri Hospitals: last day to bid", now + 3_600_000)
    repo.setIpoReminder("ipo|kaveri|close", "Kaveri Hospitals: last day to bid", now + 3_600_000)
    val item = db.planItemDao().byKey("ipo|kaveri|close")!!
    assertEquals(1, db.planItemDao().all().size)
    assertEquals(PlanKind.FOLLOW_UP.name, item.kind); assertEquals(PlanOrigin.IPO.name, item.origin)
    assertTrue(item.id in scheduled)
    repo.setIpoReminder("ipo|kaveri|close", "", null)
    assertNull(db.planItemDao().byKey("ipo|kaveri|close"))
    assertTrue(item.id in cancelled)
}
```

- [ ] **Step 2: Run to verify it fails.** `--tests "com.marksy.os.plan.PlanRepositoryTest"`. Expected: unresolved `setIpoReminder`.
- [ ] **Step 3: Implement**

```kotlin
/** An IPO date reminder ([key] = `ipo|<id>|<event>`); a null [at] removes it. */
suspend fun setIpoReminder(key: String, title: String, at: Long?) {
    val existing = dao.byKey(key)
    if (at == null) { existing?.let { delete(it.id) }; return }
    val now = clock()
    val item = existing?.copy(title = title, dueAt = at, status = PlanStatus.TODO.name, completedAt = null, updatedAt = now)
        ?: PlanItemEntity(kind = PlanKind.FOLLOW_UP.name, title = title, dueAt = at, recurrence = Recurrence.NONE.name, status = PlanStatus.TODO.name,
            origin = PlanOrigin.IPO.name, dedupeKey = key, createdAt = now, updatedAt = now)
    if (existing != null) dao.update(item) else return alarms.schedule(item.copy(id = dao.insert(item)))
    alarms.schedule(item)
}

fun observeIpoReminderKeys(prefix: String): Flow<Set<String>> = dao.observeAll().map { items ->
    items.filter { it.status != PlanStatus.DONE.name && it.dedupeKey?.startsWith(prefix) == true }.mapNotNullTo(HashSet()) { it.dedupeKey }
}
```

(`delete(id)` already cancels alarms and deletes; add `import kotlinx.coroutines.flow.map` if missing.)

- [ ] **Step 4: Run and verify green.**
- [ ] **Step 5: Commit** `feat(ipo): IPO date reminders as Plan follow-ups`

### Task 5: Home lanes, floating stack, header note, kept scroll

**Files:**
- Modify: `app/src/main/java/com/marksy/os/ui/IpoScreen.kt` (rewrite)
- Modify: `app/src/main/java/com/marksy/os/ui/MarketScreen.kt:136` (pass `onSectionSelected`, `onTitleNote`; skip Market's `OneHandControls` when `tab == MarketTab.IPOS`)
- Modify: `app/src/main/java/com/marksy/os/MainActivity.kt:387-395` (`ipoNote` state; `selectedTab == 2 && marketTabName == MarketTab.IPOS.name -> ipoNote`) and its `MarketScreen(...)` call (`onIpoNote = { ipoNote = it }`)
- Test: `app/src/test/java/com/marksy/os/ui/IpoScreenTest.kt` (update to the new signature; replace the stage-chip test)

**Interfaces:**
- Consumes: Tasks 1–4.
- Produces: `@Composable fun BoxScope.IpoScreen(repository, padding, onSectionSelected: (String) -> Unit = {}, onTitleNote: (String?) -> Unit = {})`. `IpoDetailScreen` gets a new signature in Task 6. For this task, keep calling the old one with `(repository, ipo, padding, watched, onToggleWatch)`.

- [ ] **Step 1: Update `IpoScreenTest`.** Keep `listedIposShowCompanyNames` and `emptyIpoListShowsEmptyState`, wrapping `IpoScreen` in `Box { }`. The empty one expects "No IPOs". Replace `stageFilterChipsComeFromServerCounts` with:

```kotlin
@Test
fun upcomingIssuesShowInTheOpensSoonLane() {
    val repository = MarketIntelligenceRepository(FixtureIpoClient(list = listOf(ipo("ipo-2", "Nilgiri Foods", "UPCOMING")), counts = IpoStageCountsDto(byStage = mapOf("UPCOMING" to 1), total = 1)))
    compose.setContent { Box { IpoScreen(repository = repository, padding = PaddingValues()) } }
    compose.waitForIdle()
    compose.onNodeWithText("OPENS SOON", substring = true).assertExists()
    compose.onNodeWithText("Nilgiri Foods").assertExists()
}
```

- [ ] **Step 2: Run to verify `upcomingIssuesShowInTheOpensSoonLane` fails.** (`--tests "com.marksy.os.ui.IpoScreenTest"`)
- [ ] **Step 3: Implement `IpoScreen`:**
  - **Clock:** `val now by produceState(ZonedDateTime.now(IpoLifecycle.IST)) { while (true) { delay(30_000); value = ZonedDateTime.now(IpoLifecycle.IST) } }`.
  - **Saveable state:** `stage`, `board`, `query`, `openedId`, `listedOpen` and `filtersOpen` through `rememberSaveable`; enums are saved by `name`.
  - **List data, hoisted above the detail branch** so back keeps it: `var items by remember { mutableStateOf<MarketDataState<List<IpoListItemDto>>>(Loading) }`. Load it in `LaunchedEffect(refresh.key)`:
    1. `counts = repository.ipoStageCounts()`, then `stages = IpoLifecycle.LIVE_STAGES` filtered to those whose count is above 0 (all of them when the counts call fails).
    2. Fetch `async { repository.ipos(stage = s) }` for each stage and keep items where `it.stage.equals(s, true)`.
    3. Loaded when any items came back. Otherwise Error from the first Error, else Empty. Then `refresh.done()`.
  - **Watched:** the same watched set and `toggleWatch` as today.
  - **Scroll:** `val listState = rememberLazyListState()` declared before `opened?.let { … }`.
  - **Header note:** `LaunchedEffect(note) { onTitleNote(note) }`, where `note = opened?.let { IpoLifecycle.detailNote(it, now) } ?: IpoLifecycle.homeNote(itemsList, stage, board, query, now)`. Clear it with `DisposableEffect(Unit) { onDispose { onTitleNote(null) } }`.
  - **Detail branch:** `BackHandler { openedId = null }`, then `IpoDetailScreen(...)`.
  - **Home:** `MarksyRefreshBox(refresh) { LazyColumn(state = listState, …) }`:
    - Per lane: `item(key = "lane-${lane.name}") { LaneLabel(lane.label, n, color) }` then `items(xs, key = { it.id }) { IpoCard(…) }`.
    - Lane colours: TODAY `RedUrgent`, OPEN `PrimaryEmerald`, ALLOTMENT `YellowImportant`, UPCOMING `BlueFinance`, LISTED `TextMuted`.
    - Listed under ALL with a blank query: one `ListedFold` row, then the cards when `listedOpen`.
    - Empty after filters: `EmptyState("No IPOs match", if (stage == WATCHING) "Tap the bookmark on an IPO to watch it." else "Try another stage or board, or clear the search.")`.
    - Loading, Unavailable and Error keep today's texts. A plain `Empty` shows `EmptyState("No IPOs", "No issues are open, upcoming or recently listed.")`.
  - **`LaneLabel`:** copy the Inbox plan's composable (a 6dp dot, an uppercase 11sp bold `TextSecondary` name, a `TextMuted` count, and a 1dp `BorderGlow` rule).
  - **`IpoCard`:** a `Card` on `Surface`, 14dp corners, 1dp border (`RedUrgent` for TODAY, else `BorderGlow`):
    - An optional chip (`facts.chip`, `RedUrgent` on `BadgeUrgentBg`).
    - Row: a 32dp initials avatar (`SurfaceRaised`), name + SME tag + watched icon, and the sub line "sector · Mainboard/SME". The stat sits right, 16sp bold: emerald when `statUp == true`, `RedUrgent` when `false`, `TextPrimary` when null. The 10sp `statLabel` sits under it.
    - `facts.line`, 12sp `TextSecondary`.
    - The GMP line `IpoLifecycle.gmpSummary(ipo.gmp, IpoLifecycle.upper(ipo), now)?.text` at 11sp `TextMuted`.
    - For TODAY while `closeAt - 2h` is after `now`: a `Pill(if (set) "Reminder at 3 pm" else "Remind me at 3 pm", selected = set)`. It toggles `plan.setIpoReminder(reminderKey(id, "close"), "${name}: last day to bid", closeAt.minusHours(2).toInstant().toEpochMilli())`.
  - **Reminder keys:** `val plan = remember { MarksyContainer.plan(context) }` and `val reminderKeys by plan.observeIpoReminderKeys("ipo|").collectAsState(emptySet())`.
  - **Floating stack:** `OneHandControls` with:
    - `filters = MarketSections`, `selectedFilter = MarketTab.IPOS.name` and `onFilterSelected = onSectionSelected`.
    - `searchQuery = query` and `onSearchChange = { query = it }` with placeholder "Search IPOs...", on home only.
    - `actions`: home gets `FloatingAction(Icons.Default.Tune, "IPO stage and board") { filtersOpen = true }`. Detail gets `FloatingAction(Icons.Default.NotificationsActive, "Reminders for …") { remindersOpen = true }` and `FloatingAction(if (watched) Icons.Filled.BookmarkAdded else Icons.Outlined.BookmarkAdd, if (watched) "Stop watching …" else "Watch …") { toggleWatch(ipo) }`. Detail passes `remindersOpen` down.
  - **`IpoFiltersDialog`:** a `MarksyDialog` titled "IPOs". The text holds two `FlowRow`s of `Pill`s labelled "Stage" and "Board". The confirm `TextButton` is "Done".
- [ ] **Step 4: Wire MarketScreen and MainActivity.** In MarketScreen, `MarketTab.IPOS -> IpoScreen(repository, inner, onSectionSelected = { onTabSelected(it) }, onTitleNote = onIpoNote)` and `if (tab != MarketTab.IPOS) OneHandControls(…)`. Add the `onIpoNote: (String?) -> Unit = {}` parameter. In MainActivity, `var ipoNote by remember { mutableStateOf<String?>(null) }`, pass `onIpoNote = { ipoNote = it }`, and add the `titleNote` branch.
- [ ] **Step 5: Run `IpoScreenTest` + compile and verify green.** `./gradlew --no-daemon :app:testDebugUnitTest --tests "com.marksy.os.ui.IpoScreenTest"`.
- [ ] **Step 6: Commit** `feat(ipo): lifecycle lanes on IPO home with floating filters and header note`

### Task 6: Decision-first detail page

**Files:**
- Modify: `app/src/main/java/com/marksy/os/ui/IpoDetailScreen.kt` (rewrite; keep `DetailRow`, which other callers may use)
- Modify: `app/src/main/java/com/marksy/os/ui/IpoScreen.kt` (pass the new parameters)

**Interfaces:**
- Consumes: Tasks 1–5.
- Produces: `@Composable fun IpoDetailScreen(repository, ipo, padding, now: ZonedDateTime, reminderKeys: Set<String>, onReminder: (IpoLifecycle.ReminderEvent, Boolean) -> Unit, remindersOpen: Boolean, onRemindersClose: () -> Unit)`.

- [ ] **Step 1: Implement.** Detail and history load as today. Then `summary = loaded?.summary ?: ipo` and `stage = IpoLifecycle.laneOf(summary, now)`, with TODAY treated as OPEN. Build a `LazyColumn` with `contentPadding(top = 8.dp, bottom = oneHandStackBottomPadding(3))`:
  1. **`HeroCard(summary, loaded, stage, now)`:** a card bordered in the stage colour:
     - A source line "sector · Mainboard/SME · exchanges" and a chip from `stageWord`.
     - The big and sub lines and three stats per the spec's stage table. Each stat value carries `value.stateNote()` as its small line when non-null, e.g. "Not out yet / due …" for the band.
     - The GMP block when not listed: one row per reading "source +₹48 · +11.1% · asOf", then "Unofficial grey-market premium" (11sp `TextMuted`). "No grey-market quotes yet" when there are none.
     - Allotment adds a `Pill("Check allotment")` that opens `AllotmentDialog`.
  2. **`Section(title, summary, initiallyOpen)`:** a collapsible card (`rememberSaveable` open state keyed `ipo.id + key`) with the title at 14sp semibold, a `TextMuted` summary and a chevron. Order and defaults follow the spec.
     - **Subscription:** Day `Pill`s in a `FlowRow` from `subscriptionDays` (with " · live" on the last when OPEN and that day is today). Then bars: label column (Overall/QIB/NII/Retail or Individual/Employee/Shareholder), a 10dp track with fill width = `value / max(maxValue, 1) * 0.96`, a 1x tick at `1 / scale`, and a value. Fill is emerald, or `YellowImportant` under 1x. Legend: "1x = fully subscribed · live, updated HH:mm" / "final" / "end of day N".
     - **Marksy view:** per context, a short question label (LISTING_OPPORTUNITY→"Listing day", PARTICIPATION→"Should you bid", POST_LISTING→"After listing", LONG_TERM→"Long term"). Its verdict chip: APPLY "Apply" emerald, WATCH "Watch" yellow, AVOID "Avoid" red, NO_DECISION "No call yet" muted. Confidence: ≥0.7 High, ≥0.4 Medium, else Low. Then `reason`, and "Answered on the stock page once listed" when `answeredBy != null`. Legend: "Four separate questions and no overall score."
     - **Lot calculator:** only when `lotCost` is known, else "The price band and lot size are not out yet." Category `Pill`s, then a stepper (−/+ `IconButton`s around "N lots / shares") constrained to the category range. Then the amount ("blocked in your bank at cut-off, ₹upper a share" or "…; HNI bids cannot use cut-off"), the range row, the chance row, and the gain row when GMP exists: "If allotted N lots and it lists at the lowest grey-market quote ₹X" → `+₹gain`. Then the callout (`YellowImportant` on `BadgeImportantBg`), the next-category hint, and the NII hint. UPCOMING shows a `Pill("Remind me when bidding opens")` for the `open` event.
     - **If you were allotted (listed):** a stepper from minLots to the retail max. Cost at the issue price (`outcome.issuePrice ?: upper`) and the "Sold at listing, ₹price" gain.
     - **Key dates:** a timeline of open/close/allot/refund/demat/list rows that have dates. Each row: a dot (dim when past, emerald for the next), title + detail ("Wed 30 Sep, 10:00", "Thu 1 Oct · bid and approve UPI by 5 pm (usual cutoff)", "… · results after 6 pm", "Money unblocked if not allotted", "Shares in demat", "…, 10:00 · exchanges"). A bell `IconButton` sits on rows that have a future `ReminderEvent`. It is filled when `reminderKey in reminderKeys` and toggles `onReminder`.
     - **Folded:**
       - About the company: `overview.value`.
       - Issue and anchor book: the size/fresh/OFS/lot/exchanges/anchor rows from `terms` and `anchorCrore`, each with `stateNote()`.
       - Financials and valuation: `IpoDetailFormatter.rows` of `fundamentals`, `valuation` and `peers`.
       - Risks: `IpoDetailFormatter.rows` of `risks` and `riskRun`.
       - Marksy prediction history: the existing `PredictionCard`s.
       - Other details: the remaining raw keys through `IpoDetailFormatter.rows`, skipping everything rendered above.
  3. **`RemindersDialog`** (when `remindersOpen`): a `MarksyDialog` titled "Remind me" with "Marksy sends a notification at these times." Rows per future `ReminderEvent` carry title/when and a `Switch` coloured from `MarksyTheme`. "Nothing left to remind you about." when there are none. Confirm is "Done".
  4. **`AllotmentDialog`:** a `MarksyDialog` titled "Allotment results come after 6 pm" or "Allotment results are out" (after 18:00 on the allot day). Body: "Check on the registrar's site or in your broker's IPO orders, using your PAN or application number. Marksy does not see your application." Then rows for retail chance, money unblocked/shares in demat, and listing. The allotment reminder toggles as a `TextButton`, and Close.
- [ ] **Step 2: Wire it from `IpoScreen`.** `onReminder = { e, on -> scope.launch { plan.setIpoReminder(reminderKey(ipo.id, e.key), "${ipo.companyName}: ${e.title.lowercase()}", if (on) e.at.toInstant().toEpochMilli() else null) } }`.
- [ ] **Step 3: Compile and run the IPO and Plan tests and verify green.** `./gradlew --no-daemon :app:testDebugUnitTest --tests "com.marksy.os.market.Ipo*" --tests "com.marksy.os.ui.IpoScreenTest" --tests "com.marksy.os.plan.PlanRepositoryTest"`.
- [ ] **Step 4: Commit** `feat(ipo): decision-first IPO detail with subscription bars, verdicts, lot calculator and key-date reminders`

### Task 7: Visual check, full suite, review, PR

- [ ] **Step 1: Renders.** Write a scratch Robolectric test (`@GraphicsMode(NATIVE)`, `@Config(qualifiers = "w400dp-h2400dp-xxhdpi")` and `w360dp`) **outside git** (copy it in, run it, delete it). It uses a `FixtureIpoClient` with mockup-like data, fixes `now` through sample dates relative to today, captures home and detail (Open, Upcoming, Allotment, Listed) to PNGs in the session scratchpad, and compares them against the mockup. Fix the visual issues found.
- [ ] **Step 2: Full unit suite.** `./gradlew --no-daemon :app:testDebugUnitTest`. Record counts from `app/build/test-results/testDebugUnitTest`.
- [ ] **Step 3: One whole-branch review** (superpowers:requesting-code-review), fix the findings, re-review once.
- [ ] **Step 4: Rebase on the latest `origin/main`** (resolve the MainActivity `titleNote` conflict with the Inbox PR if it merged), re-run the suite, push, open the PR to `main`, and merge when the required checks are green.
