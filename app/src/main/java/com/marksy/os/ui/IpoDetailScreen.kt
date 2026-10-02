package com.marksy.os.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.marksy.os.EmptyState
import com.marksy.os.market.IpoDetailDto
import com.marksy.os.market.IpoDetailFormatter
import com.marksy.os.market.IpoHistoryEntryDto
import com.marksy.os.market.IpoLifecycle
import com.marksy.os.market.IpoLifecycle.Lane
import com.marksy.os.market.IpoListItemDto
import com.marksy.os.market.IpoValueDto
import com.marksy.os.market.MarketDataState
import com.marksy.os.market.MarketIntelligenceRepository
import com.marksy.os.market.bounds
import com.marksy.os.market.display
import com.marksy.os.market.localDate
import com.marksy.os.market.number
import com.marksy.os.market.stateNote
import org.json.JSONArray
import org.json.JSONObject
import java.time.Duration
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor

private data class Sec(val key: String, val title: String, val sub: String, val body: @Composable () -> Unit)

/** One IPO, decision first: a top card shaped by stage, then evidence sections that fold. */
@Composable
fun IpoDetailScreen(
    repository: MarketIntelligenceRepository, ipo: IpoListItemDto, padding: PaddingValues, now: ZonedDateTime,
    reminders: Map<String, Long>, onReminder: (IpoLifecycle.ReminderEvent, Boolean) -> Unit,
    remindersOpen: Boolean, onRemindersClose: () -> Unit
) {
    val reminderKeys = reminders.keys
    val detail by produceState(MarketDataState.Loading as MarketDataState<IpoDetailDto>, ipo.id) { value = repository.ipoDetail(ipo.id) }
    val history by produceState(MarketDataState.Loading as MarketDataState<List<IpoHistoryEntryDto>>, ipo.id) { value = repository.ipoHistory(ipo.id) }
    val loaded = (detail as? MarketDataState.Loaded)?.value
    val summary = loaded?.summary?.takeIf { it.companyName.isNotBlank() } ?: ipo
    val lane = IpoLifecycle.laneOf(summary, now)
    val stage = if (lane == Lane.TODAY) Lane.OPEN else lane
    val dates = IpoLifecycle.keyDates(loaded, summary)
    val events = IpoLifecycle.reminderEvents(dates, now)
    val keyOf = { e: IpoLifecycle.ReminderEvent -> IpoLifecycle.reminderKey(ipo.id, e.key) }
    var allotOpen by rememberSaveable { mutableStateOf(false) }
    // A reminder set before a date moved re-arms for the new time.
    LaunchedEffect(events, reminders) {
        events.forEach { e -> reminders[keyOf(e)]?.takeIf { it != e.at.toInstant().toEpochMilli() }?.let { onReminder(e, true) } }
    }

    val order = when (stage) {
        Lane.OPEN -> listOf("subs", "view", "calc", "dates")
        Lane.UPCOMING -> listOf("calc", "view", "dates", "subs")
        Lane.ALLOTMENT -> listOf("subs", "dates", "view")
        Lane.LISTED -> listOf("gain", "subs", "view", "dates")
        else -> listOf("subs", "view", "dates")
    }
    val defaultOpen = when (stage) {
        Lane.UPCOMING -> listOf("calc", "view", "dates")
        Lane.LISTED -> listOf("gain")
        null -> emptyList()
        else -> order
    }
    val sections = buildSections(summary, loaded, (history as? MarketDataState.Loaded)?.value.orEmpty(), stage, now, dates, events, reminderKeys, keyOf, onReminder)

    LazyColumn(
        Modifier.fillMaxSize().background(MarksyTheme.Background).padding(horizontal = 18.dp),
        contentPadding = PaddingValues(top = 8.dp, bottom = maxOf(padding.calculateBottomPadding(), oneHandStackBottomPadding(3))),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item(key = "hero") { HeroCard(summary, loaded, lane, dates, now) { allotOpen = true } }
        when (val d = detail) {
            is MarketDataState.Loading -> item { MarksyLoader("Loading IPO details...") }
            is MarketDataState.Error -> item { EmptyState("Details unavailable", d.message) }
            is MarketDataState.Unavailable -> item { EmptyState("Market Intelligence is not configured", "Sign in to your Marksy account in More.") }
            else -> Unit
        }
        (order + listOf("about", "issue", "fin", "risks", "hist", "more")).forEach { key ->
            sections[key]?.let { s -> item(key = "sec-$key") { Section(ipo.id, s, key in defaultOpen) } }
        }
    }
    if (remindersOpen) RemindersDialog(summary, events, reminderKeys, keyOf, onReminder, onRemindersClose)
    if (allotOpen) AllotmentDialog(summary, dates, now, events.firstOrNull { it.key == "allot" }, reminderKeys, keyOf, onReminder) { allotOpen = false }
}

private fun poolLabel(ipo: IpoListItemDto) = if (ipo.isSme) "Individual" else "Retail"

private fun buildSections(
    ipo: IpoListItemDto, detail: IpoDetailDto?, history: List<IpoHistoryEntryDto>, stage: Lane?, now: ZonedDateTime,
    dates: Map<String, LocalDate>, events: List<IpoLifecycle.ReminderEvent>, reminderKeys: Set<String>,
    keyOf: (IpoLifecycle.ReminderEvent) -> String, onReminder: (IpoLifecycle.ReminderEvent, Boolean) -> Unit
): Map<String, Sec> {
    val out = LinkedHashMap<String, Sec>()
    fun add(s: Sec?) { s?.let { out[it.key] = it } }

    val days = IpoLifecycle.subscriptionDays(ipo.subscription)
    val overall = ipo.subscription?.latest?.get("OVERALL")?.times
    val live = stage == Lane.OPEN && days.lastOrNull()?.date == now.toLocalDate()
    val final = stage == Lane.ALLOTMENT || stage == Lane.LISTED
    add(
        if (days.isEmpty()) Sec("subs", "Subscription", if (stage == Lane.UPCOMING) dates["open"]?.let { "Starts ${IpoLifecycle.day(it)}" } ?: "Not started" else "No figures yet") {
            MutedText(if (stage == Lane.UPCOMING) "Bidding has not opened. Live numbers by category show here from day 1." else "Marksy has no subscription figures for this issue yet.")
        } else Sec("subs", "Subscription", listOfNotNull(overall?.let { "${IpoLifecycle.times(it)} overall" }, if (live) "live" else if (final) "final" else "so far").joinToString(" · ")) {
            SubscriptionBody(days, ipo.isSme, live, final, now)
        }
    )

    detail?.let { d ->
        val contexts = d.decisionContexts
        add(
            if (contexts.isEmpty()) Sec("view", "Marksy view", "No call yet") { MutedText("Marksy has not made a call on this issue yet.") }
            else Sec("view", "Marksy view", "${contextLabel(contexts[0].context)}: ${verdictLabel(contexts[0].verdict).first.lowercase()}") { VerdictBody(d) }
        )
    }

    val lotCost = IpoLifecycle.lotCost(ipo)
    val lotSize = IpoLifecycle.lotSize(ipo)
    val upper = IpoLifecycle.upper(ipo)
    add(
        if (lotCost != null && lotSize != null && upper != null) Sec("calc", "Lot calculator", IpoLifecycle.minBid(ipo)?.let { "From ${IpoLifecycle.inr(it)}" } ?: "") {
            CalculatorBody(ipo, stage, lotCost, lotSize, upper, now, events.firstOrNull { it.key == "open" }, reminderKeys, keyOf, onReminder)
        } else Sec("calc", "Lot calculator", "Needs the price band") { MutedText("The price band and lot size are not out yet.") }
    )

    detail?.outcome?.let { o ->
        val issue = o.issuePrice ?: upper
        if (issue != null && lotSize != null && o.listingPrice != null)
            add(Sec("gain", "If you were allotted", o.listingReturnPercent?.let { "${IpoLifecycle.pct(it)} on listing" } ?: "Listed") { GainBody(ipo, lotSize, issue, o.listingPrice) })
    }

    add(
        Sec(
            "dates", "Key dates",
            if (stage == Lane.LISTED) dates["list"]?.let { "Listed ${IpoLifecycle.dm(it)}" } ?: "Listed"
            else events.firstOrNull()?.let { "Next: ${it.title.lowercase()} ${IpoLifecycle.dm(it.at.toLocalDate())}" } ?: dates["list"]?.let { "Lists ${IpoLifecycle.dm(it)}" } ?: "Dates not out yet"
        ) { DatesBody(ipo, dates, events, now, reminderKeys, keyOf, onReminder) }
    )

    detail?.overview?.value?.let { it as? String }?.takeIf { it.isNotBlank() }?.let { text ->
        add(Sec("about", "About the company", ipo.sector ?: "Overview") { Text(text, color = MarksyTheme.TextSecondary, fontSize = 12.sp, lineHeight = 17.sp) })
    }

    val t = ipo.terms
    val size = t?.issueSizeCrore.number()
    val fresh = t?.freshIssueCrore.number()
    add(
        Sec("issue", "Issue and anchor book", listOfNotNull(size?.let { "₹${IpoDetailFormatter.number(it)} Cr" }, if (size != null && fresh != null && size > 0) "${(fresh / size * 100).toInt()}% fresh" else null).joinToString(" · ").ifEmpty { "Terms" }) {
            KvRow("Issue size", fact(t?.issueSizeCrore, "₹", " Cr"))
            KvRow("Fresh issue", fact(t?.freshIssueCrore, "₹", " Cr"))
            KvRow("Offer for sale", fact(t?.offerForSaleCrore, "₹", " Cr"))
            KvRow("Price band", fact(t?.priceBand, "₹"))
            KvRow("Lot size", fact(t?.lotSize, suffix = " shares"))
            KvRow("Exchanges", fact(t?.exchanges))
            detail?.let { d ->
                // "No anchor round" and "a report Marksy cannot read" are different answers.
                val unread = d.anchorState?.takeIf { it !in setOf("MISSING", "EMPTY", "AVAILABLE") }?.lowercase()?.replace('_', ' ')?.replaceFirstChar { it.titlecase() }
                KvRow("Anchor book", d.anchorCrore?.let { "₹${IpoDetailFormatter.number(it)} Cr" } ?: unread ?: "Not out yet")
            }
        }
    )

    val raw = detail?.raw
    if (detail != null && raw != null) {
        val fin = JSONObject().apply { listOf("fundamentals", "valuation", "peers").forEach { k -> raw.opt(k)?.takeIf { it != JSONObject.NULL }?.let { put(k, it) } } }
        val finRows = IpoDetailFormatter.rows(fin)
        if (finRows.isNotEmpty()) add(Sec("fin", "Financials and valuation", "From the prospectus") { finRows.forEach { DetailRow(it) } })

        val risks = raw.optJSONArray("risks") ?: JSONArray()
        val riskRows = IpoDetailFormatter.rows(JSONObject().put("risks", risks))
        val run = detail.riskRun
        add(
            Sec("risks", "Risks", if (!detail.riskEngineRan) "Risk engine has not run" else "${risks.length()} finding${if (risks.length() == 1) "" else "s"}") {
                run?.let { r -> Text("Risk engine: ${r.status.lowercase().replaceFirstChar { it.titlecase() }}${r.ranAt?.let { " · ${it.take(10)}" }.orEmpty()}", color = MarksyTheme.TextMuted, fontSize = 11.sp) }
                if (riskRows.isEmpty()) MutedText(if (detail.riskEngineRan) "No risk findings." else "Marksy has not checked this issue for risks yet.")
                riskRows.forEach { DetailRow(it) }
            }
        )

        val shown = setOf(
            "summary", "riskEngineRan", "riskRun", "keyDates", "decisionContexts", "outcome", "anchorBook", "companyOverview",
            "fundamentals", "valuation", "peers", "risks", "currentSnapshotId", "asOfCutoff", "riskRunHistory", "stageHistory"
        )
        val summaryShown = setOf("id", "companyName", "issueName", "isSme", "sector", "stage", "stageRecord", "opensOn", "closesOn", "listsOn", "terms", "name", "gmp", "subscription", "retailAllocationEstimate")
        val rest = detail.summary.raw?.let { IpoDetailFormatter.rows(it, skip = summaryShown) }.orEmpty() + IpoDetailFormatter.rows(raw, skip = shown)
        if (rest.isNotEmpty()) add(Sec("more", "Other details", "Documents, news and more") { rest.forEach { DetailRow(it) } })
    }

    if (history.isNotEmpty()) {
        val newest = history.sortedByDescending { it.predictedAt }
        add(Sec("hist", "Marksy prediction history", "${history.size} snapshot${if (history.size == 1) "" else "s"}") {
            PredictionCard(newest.first(), full = true)
            newest.drop(1).take(4).forEach { PredictionCard(it, full = false) }
        })
    }
    return out
}

private fun fact(v: IpoValueDto?, prefix: String = "", suffix: String = ""): String =
    v.display(prefix)?.let { s -> s + suffix + (v.stateNote()?.let { " ($it)" } ?: "") } ?: "Not out yet"

private fun contextLabel(context: String) = when (context) {
    "LISTING_OPPORTUNITY" -> "Listing day"
    "PARTICIPATION" -> "Should you bid"
    "POST_LISTING" -> "After listing"
    "LONG_TERM" -> "Long term"
    else -> context.lowercase().replace('_', ' ').replaceFirstChar { it.titlecase() }
}

private fun verdictLabel(verdict: String): Triple<String, Color, Color> = when (verdict) {
    "APPLY" -> Triple("Apply", MarksyTheme.PrimaryEmerald, MarksyTheme.BadgeTradingBg)
    "WATCH" -> Triple("Watch", MarksyTheme.YellowImportant, MarksyTheme.BadgeImportantBg)
    "AVOID" -> Triple("Avoid", MarksyTheme.RedUrgent, MarksyTheme.BadgeUrgentBg)
    else -> Triple("No call yet", MarksyTheme.TextSecondary, MarksyTheme.SurfaceRaised)
}

private fun chipBg(lane: Lane?) = when (lane) {
    Lane.TODAY -> MarksyTheme.BadgeUrgentBg
    Lane.OPEN -> MarksyTheme.BadgeTradingBg
    Lane.ALLOTMENT -> MarksyTheme.BadgeImportantBg
    Lane.UPCOMING -> MarksyTheme.BadgeFinanceBg
    Lane.LISTED, null -> MarksyTheme.SurfaceRaised
}

@Composable
private fun HeroCard(ipo: IpoListItemDto, detail: IpoDetailDto?, lane: Lane?, dates: Map<String, LocalDate>, now: ZonedDateTime, onCheckAllotment: () -> Unit) {
    val L = IpoLifecycle
    val band = ipo.terms?.priceBand.bounds()
    val bandText = band?.let { (lo, hi) -> "₹${IpoDetailFormatter.number(lo)}–${IpoDetailFormatter.number(hi)}" }
    val lotSize = L.lotSize(ipo)
    val minBid = L.minBid(ipo)?.let(L::inr) ?: "–"
    val minNote = lotSize?.let { "${IpoDetailFormatter.number(it * L.minLots(ipo))} shares" } ?: "needs the lot size"
    val overall = ipo.subscription?.latest?.get("OVERALL")?.times
    val close = L.closeAt(ipo)
    val list = dates["list"]
    var bigColor = MarksyTheme.TextPrimary
    val chip: String
    val big: String
    val sub: String
    val stats: List<Triple<String, String, String>>
    when (lane) {
        Lane.TODAY, Lane.OPEN -> {
            val left = close?.let { Duration.between(now, it) }?.takeIf { !it.isNegative }
            val live = L.subscriptionDays(ipo.subscription).lastOrNull()?.date == now.toLocalDate()
            chip = if (lane == Lane.TODAY) "Closes today" else dates["close"]?.let { "Open till ${L.dm(it)}" } ?: "Open"
            big = if (lane == Lane.TODAY) left?.let { "${L.leftText(it)} left" } ?: "Closing" else dates["close"]?.let { "Closes ${L.day(it)}, 5 pm" } ?: "Open now"
            sub = if (lane == Lane.TODAY) "Bid, then approve the UPI mandate in your UPI app, by 5 pm today (the usual cutoff)."
            else left?.let { "${L.leftText(it)} left. Approve the UPI mandate by 5 pm that day (the usual cutoff)." } ?: "Approve the UPI mandate by 5 pm on the closing day."
            stats = listOf(Triple("Price band", bandText ?: "Not out yet", if (band != null) ipo.terms?.priceBand.stateNote() ?: "per share" else ipo.terms?.priceBand.stateNote().orEmpty()), Triple("Min to bid", minBid, minNote), Triple("Subscribed", overall?.let(L::times) ?: "–", if (live) "live" else "so far"))
        }
        Lane.UPCOMING -> {
            val opens = dates["open"]
            val n = opens?.let { ChronoUnit.DAYS.between(now.toLocalDate(), it) }
            chip = opens?.let { "Opens ${L.day(it)}" } ?: "Opens soon"
            big = when { n == null || n < 0 -> "Opening soon"; n == 0L -> "Opens today"; n == 1L -> "Opens tomorrow"; else -> "In $n days" }
            val window = listOfNotNull(opens?.let(L::dm), dates["close"]?.let(L::day)).joinToString(" – ")
            sub = (if (band == null) "Price band not out yet" else "") + (if (window.isNotEmpty()) "${if (band == null) " · bidding" else "Bidding"} $window" else "")
            val size = ipo.terms?.issueSizeCrore.number()
            val fresh = ipo.terms?.freshIssueCrore.number()
            stats = listOf(
                Triple("Price band", bandText ?: "Not out yet", if (band != null) ipo.terms?.priceBand.stateNote() ?: "per share" else "due before opening"), Triple("Min to bid", minBid, minNote),
                Triple("Issue size", size?.let { "₹${IpoDetailFormatter.number(it)} Cr" } ?: "–", if (size != null && fresh != null && size > 0) "${(fresh / size * 100).toInt()}% fresh" else "")
            )
        }
        Lane.ALLOTMENT -> {
            val allot = dates["allot"]
            val out = allot != null && !now.isBefore(allot.atTime(18, 0).atZone(L.IST))
            val tonight = allot == now.toLocalDate()
            chip = when { out -> "Allotment out"; tonight -> "Allotment tonight"; allot != null -> "Allotment ${L.day(allot)}"; else -> "Bidding closed" }
            big = when { out -> "Check by PAN"; tonight -> "Results after 6 pm"; allot != null -> "Results ${L.day(allot)}"; else -> "Allotment due" }
            sub = listOfNotNull(dates["close"]?.let { "Bidding closed ${L.day(it)}" }, list?.let { "lists ${L.day(it)}, 10:00" }).joinToString(" · ").replaceFirstChar { it.uppercase() }
            val retail = ipo.subscription?.latest?.get("RETAIL")?.times
            stats = listOf(
                Triple("Subscribed", overall?.let(L::times) ?: "–", "final"),
                Triple(poolLabel(ipo), retail?.let(L::times) ?: "–", L.retailOdds(ipo)?.replaceFirstChar { it.lowercase() } ?: ""),
                Triple("Lists", list?.let(L::dm) ?: "–", list?.let { "${it.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)}, 10:00" } ?: "")
            )
        }
        Lane.LISTED -> {
            val o = detail?.outcome
            val ret = o?.listingReturnPercent
            val exp = o?.expectedReturnPercent
            chip = list?.let { "Listed ${L.day(it)}" } ?: "Listed"
            big = ret?.let { "${L.pct(it)} on listing" } ?: "Listed"
            ret?.let { bigColor = if (it >= 0) MarksyTheme.PrimaryEmerald else MarksyTheme.RedUrgent }
            sub = when {
                exp != null && ret != null -> "Marksy expected ${L.pct(exp)} before listing, off by ${"%.1f".format(Locale.ENGLISH, abs(ret - exp))} points."
                exp != null -> "Marksy expected ${L.pct(exp)} before listing."
                else -> "Marksy made no call on this issue."
            }
            val issue = o?.issuePrice ?: L.upper(ipo)
            stats = listOf(
                Triple("Issue price", issue?.let { "₹${IpoDetailFormatter.number(it)}" } ?: "–", if (o?.issuePrice != null) "final" else "upper band"),
                Triple("Listed at", o?.listingPrice?.let { "₹${IpoDetailFormatter.number(it)}" } ?: "–", ret?.let { L.pct(it) } ?: ""),
                Triple("Marksy expected", exp?.let { L.pct(it) } ?: "–", "before listing")
            )
        }
        null -> {
            chip = "Stage not established"
            big = ipo.stage?.lowercase()?.replace('_', ' ')?.replaceFirstChar { it.titlecase() } ?: "Not placed yet"
            sub = "Marksy has not placed this issue in its lifecycle yet."
            stats = listOf(Triple("Price band", bandText ?: "Not out yet", ""), Triple("Min to bid", minBid, minNote), Triple("Lists", list?.let(L::dm) ?: "–", ""))
        }
    }
    val color = laneColor(lane)
    val shape = RoundedCornerShape(16.dp)
    Column(
        Modifier.fillMaxWidth().clip(shape).background(MarksyTheme.Surface).border(1.dp, color.copy(alpha = 0.6f), shape).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IpoAvatar(ipo.companyName, size = 24)
            Spacer(Modifier.width(8.dp))
            Text(listOfNotNull(ipo.sector, if (ipo.isSme) "SME" else "Mainboard", ipo.terms?.exchanges.display()).joinToString(" · "), color = MarksyTheme.TextMuted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(chip, color = color, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(chipBg(lane)).padding(horizontal = 8.dp, vertical = 3.dp))
        Text(big, color = bigColor, fontSize = 24.sp, fontWeight = FontWeight.Bold, lineHeight = 28.sp)
        if (sub.isNotBlank()) Text(sub, color = MarksyTheme.TextSecondary, fontSize = 12.sp, lineHeight = 16.sp)
        Row(Modifier.fillMaxWidth().padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            stats.forEach { (label, value, note) ->
                Column(Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(MarksyTheme.SurfaceRaised).padding(horizontal = 8.dp, vertical = 6.dp)) {
                    Text(label, color = MarksyTheme.TextMuted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(value, color = MarksyTheme.TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (note.isNotBlank()) Text(note, color = MarksyTheme.TextMuted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        if (lane != Lane.LISTED) GmpBlock(ipo, now)
        if (lane == Lane.ALLOTMENT) Pill("Check allotment", onClick = onCheckAllotment)
    }
}

@Composable
private fun GmpBlock(ipo: IpoListItemDto, now: ZonedDateTime) {
    val readings = ipo.gmp?.readings.orEmpty()
    if (readings.isEmpty()) {
        Text("No grey-market quotes yet", color = MarksyTheme.TextMuted, fontSize = 11.sp)
        return
    }
    val upper = IpoLifecycle.upper(ipo)
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        readings.sortedByDescending { it.premium }.forEach { r ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(r.source, color = MarksyTheme.TextSecondary, fontSize = 12.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull(IpoLifecycle.rupees(r.premium), IpoLifecycle.gmpPercent(r, upper)?.let { IpoLifecycle.pct(it) }, IpoLifecycle.asOfLabel(r.observedAt, now) ?: "time not stated").joinToString(" · "),
                    color = if (r.premium >= 0) MarksyTheme.TextPrimary else MarksyTheme.RedUrgent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold
                )
            }
        }
        Text("Unofficial grey-market premium${if (ipo.isSme) " · thin SME market" else ""}", color = MarksyTheme.TextMuted, fontSize = 10.sp)
    }
}

@Composable
private fun Section(id: String, s: Sec, initiallyOpen: Boolean) {
    var open by rememberSaveable(id, s.key) { mutableStateOf(initiallyOpen) }
    val shape = RoundedCornerShape(14.dp)
    Column(Modifier.fillMaxWidth().clip(shape).background(MarksyTheme.Surface).border(1.dp, MarksyTheme.BorderGlow, shape)) {
        Row(Modifier.fillMaxWidth().clickable { open = !open }.padding(horizontal = 12.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(s.title, color = MarksyTheme.TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(s.sub, color = MarksyTheme.TextMuted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.End, modifier = Modifier.weight(1f).padding(start = 8.dp))
            Icon(Icons.Default.ExpandMore, if (open) "Fold ${s.title}" else "Show ${s.title}", tint = MarksyTheme.TextSecondary, modifier = Modifier.padding(start = 4.dp).size(18.dp).rotate(if (open) 180f else 0f))
        }
        if (open) Column(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { s.body() }
    }
}

@Composable
private fun MutedText(text: String) = Text(text, color = MarksyTheme.TextSecondary, fontSize = 12.sp, lineHeight = 16.sp)

@Composable
private fun Legend(text: String) = Text(text, color = MarksyTheme.TextMuted, fontSize = 10.sp, lineHeight = 14.sp)

@Composable
private fun KvRow(label: String, value: String, valueColor: Color = MarksyTheme.TextPrimary) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(label, color = MarksyTheme.TextSecondary, fontSize = 12.sp, lineHeight = 16.sp, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(8.dp))
        Text(value, color = valueColor, fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SubscriptionBody(days: List<IpoLifecycle.SubscriptionDay>, sme: Boolean, live: Boolean, final: Boolean, now: ZonedDateTime) {
    var pick by rememberSaveable(days.size) { mutableIntStateOf(days.lastIndex) }
    val i = pick.coerceIn(0, days.lastIndex)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        days.forEachIndexed { n, d ->
            val dow = d.date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)
            Pill("Day ${n + 1} · $dow ${d.date.dayOfMonth}${if (n == days.lastIndex && live) " · live" else ""}", selected = n == i) { pick = n }
        }
    }
    val values = days[i].values
    val rows = listOf(
        "OVERALL" to ("Overall" to "all bids"), "QIB" to ("QIB" to "institutions"), "NII" to ("NII" to "HNIs"),
        "RETAIL" to (if (sme) "Individual" to "2 lots" else "Retail" to "up to ₹2 lakh"), "EMPLOYEE" to ("Employee" to "own quota"), "SHAREHOLDER" to ("Shareholder" to "own quota")
    ).filter { it.first in values }
    val scale = maxOf(1.0, values.values.maxOrNull() ?: 1.0) * 1.04
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        rows.forEach { (key, names) -> SubscriptionBar(names.first, names.second, values.getValue(key), scale, key == "OVERALL") }
    }
    val asOf = when {
        i == days.lastIndex && live -> "live, updated ${IpoLifecycle.asOfLabel(days[i].observedAt, now) ?: "today"}"
        i == days.lastIndex && final -> "final"
        else -> "end of day ${i + 1}"
    }
    Legend("1x = fully subscribed · $asOf${if ("NII" in values) " · small/big HNI split not available" else ""}")
}

@Composable
private fun SubscriptionBar(name: String, sub: String, value: Double, scale: Double, total: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.width(84.dp)) {
            Text(name, color = MarksyTheme.TextPrimary, fontSize = 12.sp, fontWeight = if (total) FontWeight.Bold else FontWeight.Medium)
            Text(sub, color = MarksyTheme.TextMuted, fontSize = 10.sp, maxLines = 1)
        }
        BoxWithConstraints(Modifier.weight(1f).height(10.dp).clip(RoundedCornerShape(5.dp)).background(MarksyTheme.SurfaceRaised)) {
            val fill = (value / scale).coerceIn(0.015, 1.0).toFloat()
            Box(Modifier.fillMaxWidth(fill).fillMaxHeight().clip(RoundedCornerShape(5.dp)).background(if (value < 1) MarksyTheme.YellowImportant else MarksyTheme.PrimaryEmerald))
            Box(Modifier.offset(x = maxWidth * (1 / scale).toFloat()).width(1.5.dp).fillMaxHeight().background(MarksyTheme.TextPrimary))
        }
        Text(IpoLifecycle.times(value), color = MarksyTheme.TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.End, modifier = Modifier.width(56.dp))
    }
}

@Composable
private fun VerdictBody(d: IpoDetailDto) {
    d.decisionContexts.forEach { c ->
        val (label, fg, bg) = verdictLabel(c.verdict)
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(contextLabel(c.context), color = MarksyTheme.TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text(label, color = fg, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(bg).padding(horizontal = 8.dp, vertical = 3.dp))
            }
            c.confidence?.let { Text("${if (it >= 0.7) "High" else if (it >= 0.4) "Medium" else "Low"} confidence", color = MarksyTheme.TextMuted, fontSize = 10.sp) }
            c.reason?.let { MutedText(it) }
            if (c.answeredBy != null) Legend("Answered on the stock page once it lists.")
        }
    }
    Legend("Four separate questions and no overall score.")
}

@Composable
private fun Stepper(value: String, sub: String, canDown: Boolean, canUp: Boolean, onDown: () -> Unit, onUp: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        StepButton(Icons.Default.Remove, "One lot less", canDown, onDown)
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value, color = MarksyTheme.TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text(sub, color = MarksyTheme.TextMuted, fontSize = 11.sp)
        }
        StepButton(Icons.Default.Add, "One lot more", canUp, onUp)
    }
}

@Composable
private fun StepButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(40.dp).clip(CircleShape).background(MarksyTheme.SurfaceRaised).border(1.dp, MarksyTheme.BorderGlow, CircleShape)) {
        Icon(icon, label, tint = if (enabled) MarksyTheme.PrimaryEmerald else MarksyTheme.TextMuted)
    }
}

@Composable
private fun Callout(text: String) {
    val shape = RoundedCornerShape(10.dp)
    Row(Modifier.fillMaxWidth().clip(shape).background(MarksyTheme.BadgeImportantBg).border(1.dp, MarksyTheme.YellowImportant.copy(alpha = 0.5f), shape).padding(10.dp)) {
        Icon(Icons.Default.Info, null, tint = MarksyTheme.YellowImportant, modifier = Modifier.size(14.dp).padding(top = 1.dp))
        Spacer(Modifier.width(6.dp))
        Text(text, color = MarksyTheme.TextPrimary, fontSize = 12.sp, lineHeight = 16.sp)
    }
}

private fun signedInr(n: Double) = (if (n >= 0) "+" else "−") + IpoLifecycle.inr(abs(n))

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CalculatorBody(
    ipo: IpoListItemDto, stage: Lane?, lotCost: Double, lotSize: Int, upper: Double, now: ZonedDateTime,
    openEvent: IpoLifecycle.ReminderEvent?, reminderKeys: Set<String>, keyOf: (IpoLifecycle.ReminderEvent) -> String, onReminder: (IpoLifecycle.ReminderEvent, Boolean) -> Unit
) {
    val cats = remember(lotCost, ipo.isSme) { IpoLifecycle.lotCategories(lotCost, ipo.isSme) }
    var catKey by rememberSaveable(ipo.id) { mutableStateOf(cats[0].key) }
    val cat = cats.firstOrNull { it.key == catKey } ?: cats[0]
    var lots by rememberSaveable(ipo.id) { mutableIntStateOf(cat.minLots) }
    val n = lots.coerceIn(cat.minLots, cat.maxLots)
    val poolTimes = if (stage == Lane.UPCOMING) null else ipo.subscription?.latest?.get(cat.pool)?.times
    val poolName = if (cat.pool == "RETAIL") poolLabel(ipo) else "NII"
    val gmp = IpoLifecycle.gmpSummary(ipo.gmp, upper, now)
    val lowest = gmp?.lowestPremium
    val q = IpoLifecycle.lotQuote(cats, cat, n, lotSize, upper, poolTimes, lowest, poolName)
    val catName = cat.label.substringBefore(" · ")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        cats.forEach { c -> Pill(c.label, selected = c == cat) { catKey = c.key; lots = c.minLots } }
    }
    Stepper(IpoLifecycle.lotsText(n), "${IpoDetailFormatter.number(n * lotSize)} shares", n > cat.minLots, n < cat.maxLots, { lots = n - 1 }, { lots = n + 1 })
    Column {
        Text(IpoLifecycle.inr(q.amount), color = MarksyTheme.TextPrimary, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Legend((if (cat.cutOff) "blocked in your bank at cut-off, ₹${IpoDetailFormatter.number(upper)} a share" else "blocked in your bank at ₹${IpoDetailFormatter.number(upper)} a share; HNI bids cannot use cut-off") +
            (IpoLifecycle.termsNote(ipo)?.let { " · price band or lot $it" } ?: ""))
    }
    KvRow("$catName range", if (cat.minLots == cat.maxLots) IpoLifecycle.lotsText(cat.minLots) else "${cat.minLots}–${cat.maxLots} lots")
    KvRow("Chance of allotment", q.chance)
    q.gain?.let { KvRow("If allotted ${IpoLifecycle.lotsText(q.gainLots)} and it lists at the lowest grey-market quote${gmp?.asOf?.let { t -> " ($t)" }.orEmpty()}", signedInr(it), if (it >= 0) MarksyTheme.PrimaryEmerald else MarksyTheme.RedUrgent) }
    q.callout?.let { Callout(it) }
    q.next?.let { next ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Above ${IpoLifecycle.lotsText(cat.maxLots)} you bid as ${next.label.substringBefore(" · ")}.", color = MarksyTheme.TextSecondary, fontSize = 12.sp, modifier = Modifier.weight(1f))
            Pill("Switch") { catKey = next.key; lots = next.minLots }
        }
    }
    if (cat.pool == "NII" && poolTimes != null) Legend("NII odds use the combined NII figure; the small and big HNI split is not available.")
    if (stage == Lane.UPCOMING) openEvent?.let { e ->
        val set = keyOf(e) in reminderKeys
        Pill(if (set) "Reminder set for opening" else "Remind me when bidding opens", selected = set) { onReminder(e, !set) }
    }
}

@Composable
private fun GainBody(ipo: IpoListItemDto, lotSize: Int, issuePrice: Double, listingPrice: Double) {
    val lo = IpoLifecycle.minLots(ipo)
    val hi = if (ipo.isSme) 10 else floor(200_000 / (lotSize * issuePrice)).toInt().coerceAtLeast(lo)
    var lots by rememberSaveable(ipo.id) { mutableIntStateOf(lo) }
    val n = lots.coerceIn(lo, hi)
    val shares = n * lotSize
    val gain = shares * (listingPrice - issuePrice)
    Stepper(IpoLifecycle.lotsText(n), "${IpoDetailFormatter.number(shares)} shares", n > lo, n < hi, { lots = n - 1 }, { lots = n + 1 })
    Column {
        Text(IpoLifecycle.inr(shares * issuePrice), color = MarksyTheme.TextPrimary, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Legend("paid at the issue price, ₹${IpoDetailFormatter.number(issuePrice)} a share")
    }
    KvRow("Sold at listing, ₹${IpoDetailFormatter.number(listingPrice)}", "${signedInr(gain)} · ${IpoLifecycle.pct((listingPrice - issuePrice) / issuePrice * 100)}", if (gain >= 0) MarksyTheme.PrimaryEmerald else MarksyTheme.RedUrgent)
    Legend("Before brokerage and tax. Shares sold within a year are taxed as short-term capital gain.")
}

@Composable
private fun DatesBody(
    ipo: IpoListItemDto, dates: Map<String, LocalDate>, events: List<IpoLifecycle.ReminderEvent>, now: ZonedDateTime,
    reminderKeys: Set<String>, keyOf: (IpoLifecycle.ReminderEvent) -> String, onReminder: (IpoLifecycle.ReminderEvent, Boolean) -> Unit
) {
    val L = IpoLifecycle
    val exchanges = ipo.terms?.exchanges.display()
    data class Row4(val key: String, val title: String, val detail: String, val at: ZonedDateTime)
    val rows = listOfNotNull(
        dates["open"]?.let { Row4("open", "Bidding opens", "${L.day(it)}, 10:00", it.atTime(10, 0).atZone(L.IST)) },
        dates["close"]?.let { Row4("close", "Last day to bid", "${L.day(it)} · bid and approve UPI by 5 pm (usual cutoff)", it.atTime(17, 0).atZone(L.IST)) },
        dates["allot"]?.let { Row4("allot", "Allotment", "${L.day(it)} · results after 6 pm", it.atTime(18, 0).atZone(L.IST)) },
        dates["refund"]?.let { Row4("refund", "Money unblocked if not allotted", L.day(it), it.atTime(12, 0).atZone(L.IST)) },
        dates["demat"]?.let { Row4("demat", "Shares in demat", L.day(it), it.atTime(12, 0).atZone(L.IST)) },
        dates["list"]?.let { Row4("list", "Listing", "${L.day(it)}, 10:00${exchanges?.let { e -> " · $e" }.orEmpty()}", it.atTime(10, 0).atZone(L.IST)) }
    )
    if (rows.isEmpty()) { MutedText("No dates are out yet."); return }
    val next = rows.indexOfFirst { it.at.isAfter(now) }
    rows.forEachIndexed { i, r ->
        val past = !r.at.isAfter(now)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(when { i == next -> MarksyTheme.PrimaryEmerald; past -> MarksyTheme.TextMuted; else -> MarksyTheme.SurfaceRaised }).border(1.dp, if (i == next) MarksyTheme.PrimaryEmerald else MarksyTheme.BorderGlow, CircleShape))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(r.title, color = if (past) MarksyTheme.TextMuted else MarksyTheme.TextPrimary, fontSize = 13.sp, fontWeight = if (i == next) FontWeight.Bold else FontWeight.Medium)
                Text(r.detail, color = MarksyTheme.TextMuted, fontSize = 11.sp, lineHeight = 14.sp)
            }
            events.firstOrNull { it.key == r.key }?.let { e ->
                val on = keyOf(e) in reminderKeys
                IconButton(onClick = { onReminder(e, !on) }, modifier = Modifier.size(40.dp)) {
                    Icon(if (on) Icons.Default.Notifications else Icons.Outlined.NotificationsNone, if (on) "Cancel reminder: ${r.title}" else "Remind me: ${r.title}", tint = if (on) MarksyTheme.PrimaryEmerald else MarksyTheme.TextSecondary, modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}

@Composable
private fun RemindersDialog(
    ipo: IpoListItemDto, events: List<IpoLifecycle.ReminderEvent>, reminderKeys: Set<String>,
    keyOf: (IpoLifecycle.ReminderEvent) -> String, onReminder: (IpoLifecycle.ReminderEvent, Boolean) -> Unit, onClose: () -> Unit
) {
    MarksyDialog(
        onDismissRequest = onClose,
        confirmButton = { TextButton(onClick = onClose) { Text("Done", color = MarksyTheme.PrimaryEmerald) } },
        title = { Text("Remind me") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("${ipo.companyName}: Marksy sends a notification at these times.")
                if (events.isEmpty()) Text("Nothing left to remind you about.", modifier = Modifier.padding(top = 8.dp))
                events.forEach { e ->
                    val on = keyOf(e) in reminderKeys
                    Row(Modifier.fillMaxWidth().clickable { onReminder(e, !on) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(e.title, color = MarksyTheme.TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                            Text(e.whenText, color = MarksyTheme.TextMuted, fontSize = 11.sp)
                        }
                        Switch(
                            checked = on, onCheckedChange = { onReminder(e, it) },
                            colors = SwitchDefaults.colors(checkedThumbColor = Color.Black, checkedTrackColor = MarksyTheme.PrimaryEmerald, uncheckedThumbColor = MarksyTheme.TextMuted, uncheckedTrackColor = MarksyTheme.SurfaceRaised)
                        )
                    }
                }
            }
        }
    )
}

@Composable
private fun AllotmentDialog(
    ipo: IpoListItemDto, dates: Map<String, LocalDate>, now: ZonedDateTime, allotEvent: IpoLifecycle.ReminderEvent?, reminderKeys: Set<String>,
    keyOf: (IpoLifecycle.ReminderEvent) -> String, onReminder: (IpoLifecycle.ReminderEvent, Boolean) -> Unit, onClose: () -> Unit
) {
    val L = IpoLifecycle
    val allot = dates["allot"]
    val out = allot != null && !now.isBefore(allot.atTime(18, 0).atZone(L.IST))
    MarksyDialog(
        onDismissRequest = onClose,
        confirmButton = { TextButton(onClick = onClose) { Text("Close", color = MarksyTheme.TextSecondary) } },
        dismissButton = allotEvent?.let { e ->
            {
                val on = keyOf(e) in reminderKeys
                TextButton(onClick = { onReminder(e, !on) }) { Text(if (on) "Cancel the 6 pm reminder" else "Remind me at 6 pm", color = MarksyTheme.PrimaryEmerald) }
            }
        },
        title = { Text(if (out) "Allotment results are out" else "Allotment results come after 6 pm") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Check on the registrar's site or in your broker's IPO orders, using your PAN or application number. Marksy does not see your application.")
                KvRow("${poolLabel(ipo)} chance", L.retailOdds(ipo) ?: "Not known yet")
                KvRow("Money unblocked, shares in demat", (dates["refund"] ?: dates["demat"])?.let(L::day) ?: "Date not out yet")
                KvRow("Listing", dates["list"]?.let { "${L.day(it)}, 10:00" } ?: "Date not out yet")
            }
        }
    )
}

@Composable
private fun PredictionCard(entry: IpoHistoryEntryDto, full: Boolean) {
    Column(Modifier.fillMaxWidth().border(1.dp, MarksyTheme.BorderGlow, RoundedCornerShape(12.dp)).padding(10.dp)) {
        val call = listOfNotNull(entry.decision?.lowercase()?.replace('_', ' ')?.replaceFirstChar { it.titlecase() }, entry.expectedReturnPercent.display()?.let { "expected listing return $it%" })
        Text(call.joinToString(" · ").ifBlank { "Prediction" }, color = MarksyTheme.PrimaryEmerald, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Text(entry.predictedAt.take(16).replace('T', ' '), color = MarksyTheme.TextMuted, fontSize = 10.sp)
        // Only the newest snapshot is shown in full; earlier ones are the call and when it was made.
        if (full) entry.raw?.let { raw ->
            IpoDetailFormatter.rows(raw, skip = setOf("predictedAt", "decision", "expectedReturnPercent")).forEach { DetailRow(it) }
        }
    }
}

@Composable
internal fun DetailRow(row: IpoDetailFormatter.Row) {
    val indent = (row.depth * 12).dp
    if (row.paragraph) {
        Text("• ${row.label}", color = MarksyTheme.TextSecondary, fontSize = 12.sp, lineHeight = 16.sp, modifier = Modifier.padding(start = indent, top = 2.dp))
    } else if (row.value == null) {
        Text(row.label, color = if (row.depth == 0) MarksyTheme.TextPrimary else MarksyTheme.TextSecondary,
            fontSize = if (row.depth == 0) 14.sp else 12.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = indent, top = if (row.depth == 0) 6.dp else 2.dp))
    } else if (row.value.length > 60) {
        // Long text (overview, thesis) needs the full width, not the right-hand column.
        Column(Modifier.fillMaxWidth().padding(start = indent)) {
            Text(row.label, color = MarksyTheme.TextMuted, fontSize = 12.sp)
            Text(row.value, color = MarksyTheme.TextPrimary, fontSize = 12.sp, lineHeight = 16.sp)
        }
    } else {
        Row(Modifier.fillMaxWidth().padding(start = indent)) {
            Text(row.label, color = MarksyTheme.TextMuted, fontSize = 12.sp, modifier = Modifier.weight(.45f))
            Text(row.value, color = MarksyTheme.TextPrimary, fontSize = 12.sp, modifier = Modifier.weight(.55f))
        }
    }
}
