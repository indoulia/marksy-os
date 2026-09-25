package com.marksy.os.intelligence

import com.marksy.os.data.local.ContextEntity
import com.marksy.os.data.local.NotificationEventEntity
import com.marksy.os.data.local.PlanItemEntity
import com.marksy.os.plan.PlanText
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import java.util.Locale

/**
 * EPIC-016 grounded local query layer. Interpretation may come from any [QueryInterpreter]
 * (deterministic by default, a local model later via EPIC-019), but every fact in an [Answer]
 * is computed here from retrieved Marksy rows and carries the ids it was derived from.
 */
object AskMarksy {
    enum class Intent { PAYMENTS, DELIVERIES, FROM_PERSON, BILLS_DUE, IMPORTANT, MISSED, TRADING, SOURCE, SEARCH, PLAN, STOCK, NAVIGATE, HELP }

    enum class Page(val title: String) { HOME("Home"), INBOX("Inbox"), PLAN("Plan"), TRADING("Trading"), MARKET("Market"), STOCK("Market"), SETTINGS("Settings") }

    /** A page to open for the answer; [arg] is the tab, filter or symbol that page takes. [auto] opens it without a tap. */
    data class Action(val label: String, val page: Page, val arg: String? = null, val auto: Boolean = false)

    data class TimeRange(val from: Long, val to: Long, val label: String)

    data class Query(
        val intent: Intent,
        val range: TimeRange,
        val subject: String? = null,
        val direction: EventExtractor.Direction? = null,
        val rawText: String = "",
        /** Channel key ("email", "teams", "whatsapp", …) the answer is limited to. */
        val channel: String? = null,
        /** Sitemap word for NAVIGATE, typed stock name for STOCK. */
        val target: String? = null
    )

    data class Item(val eventId: Long, val title: String, val source: String, val postedAt: Long, val detail: String?)

    data class Answer(
        val query: Query,
        val headline: String,
        val items: List<Item>,
        /** Every event id the headline and items were derived from (provenance). */
        val derivedFromEventIds: List<Long>,
        val followUps: List<String>,
        val noResult: Boolean,
        val interpretedBy: String,
        /** True when Marksy asks which of several real candidates was meant instead of guessing. */
        val needsClarification: Boolean = false,
        val action: Action? = null
    )

    data class Interpretation(val query: Query, val interpretedBy: String)

    /** Read-only access to Marksy data. Implementations must never invent rows. */
    interface Retriever {
        suspend fun events(from: Long, to: Long, limit: Int): List<NotificationEventEntity>
        suspend fun entities(name: String): List<ContextEntity>
        suspend fun eventIdsFor(entityId: Long): List<Long>
        suspend fun planItems(): List<PlanItemEntity> = emptyList()
        /** The listed stock symbol for a typed name or symbol, or null when there is none. */
        suspend fun resolveSymbol(text: String): String? = null
    }

    /** Pluggable interpretation (e.g. an on-device model). Returning null defers to the next interpreter. */
    interface QueryInterpreter {
        val name: String
        suspend fun interpret(text: String, previous: Query?, nowMillis: Long, zone: ZoneId): Query?
    }

    object DeterministicInterpreter : QueryInterpreter {
        override val name = "deterministic"
        override suspend fun interpret(text: String, previous: Query?, nowMillis: Long, zone: ZoneId): Query? =
            parse(text, previous, nowMillis, zone)
    }

    // ---------------------------------------------------------------- parsing

    fun parse(text: String, previous: Query?, nowMillis: Long, zone: ZoneId): Query {
        val normalized = normalize(text)
        val span = DATE_SPAN.find(normalized)
        // The date span is removed so "from 1 sep to 15 sep" is never read as a counterparty or person.
        val t = span?.let { normalized.removeRange(it.range).replace(Regex("\\s+"), " ").trim() } ?: normalized
        val explicitRange = span?.let { dateSpan(it, nowMillis, zone) } ?: rangeFor(t, nowMillis, zone)
        val bare = t.replace(RANGE_PHRASE, " ").replace(Regex("\\s+"), " ").trim()
        val channel = CHANNEL_WORDS.entries.firstOrNull { (w, _) -> t.containsWord(w) }?.value
        // "from rahul on whatsapp": the channel phrase is removed so the name ends the sentence.
        val person = personFor(bare.replace(CHANNEL_PHRASE, " ").replace(Regex("\\s+"), " ").trim())
        val page = NAV_VERB.find(t)?.groupValues?.get(1)?.removeSuffix(" page")?.removeSuffix(" tab")?.takeIf { pageAction(it) != null }
            ?: "ipos".takeIf { has(t, "ipo", "ipos") }
        val stock = STOCK_PATTERNS.firstNotNullOfOrNull { it.find(bare)?.groupValues?.get(1) }?.let(::stockName)
        val opened = Regex("^open (.+)$").find(bare)?.groupValues?.get(1)?.let(::stockName)?.takeIf { it !in CHANNEL_WORDS }
        val planKind = when {
            has(t, "birthday", "birthdays") -> "birthdays"
            has(t, "todo", "to do", "to-do", "task", "tasks") -> "tasks"
            has(t, "reminder", "reminders", "upcoming", "coming up") -> "all"
            else -> null
        }

        val intent = when {
            page != null -> Intent.NAVIGATE
            has(t, "what can you do", "what can i ask", "how do i use", "what do you do") || t == "help" -> Intent.HELP
            has(t, "bill", "bills", "due", "invoice", "recharge") -> Intent.BILLS_DUE
            has(t, "payment", "payments", "paid", "spent", "spend", "debited", "credited", "received money", "transactions", "transaction") -> Intent.PAYMENTS
            has(t, "delivery", "deliveries", "package", "packages", "parcel", "shipment", "order", "orders", "arriving") -> Intent.DELIVERIES
            planKind != null -> Intent.PLAN
            stock != null -> Intent.STOCK
            has(t, "trade", "trades", "trading", "stock", "stocks", "opportunities") -> Intent.TRADING
            has(t, "miss", "missed", "unread") -> Intent.MISSED
            has(t, "important", "urgent", "priority", "happened", "highlights") -> Intent.IMPORTANT
            opened != null -> Intent.STOCK
            person != null -> Intent.FROM_PERSON
            channel != null || has(t, "how many", "count", "number of") -> Intent.SOURCE
            // Short follow-ups ("and yesterday?", "what about Amit?") keep the previous intent.
            previous != null && (t.startsWith("and ") || t.startsWith("what about") || t.startsWith("how about") || t.split(' ').size <= 3) -> previous.intent
            else -> Intent.SEARCH
        }

        val defaultRange = when (intent) {
            Intent.BILLS_DUE -> TimeRange(nowMillis - 30 * DAY, nowMillis + DAY, "the last 30 days")
            Intent.DELIVERIES -> TimeRange(nowMillis - 14 * DAY, nowMillis + DAY, "the last 14 days")
            Intent.IMPORTANT, Intent.MISSED, Intent.SOURCE -> today(nowMillis, zone)
            Intent.PLAN -> TimeRange(nowMillis, nowMillis + 30 * DAY, "the next 30 days")
            else -> TimeRange(nowMillis - 7 * DAY, nowMillis + 1, "the last 7 days")
        }
        val inherit = previous != null && intent == previous.intent
        val direction = when {
            has(t, "received", "credited", "got paid", "refund", "refunds") -> EventExtractor.Direction.CREDIT
            has(t, "paid", "spent", "spend", "debited", "sent money", "i make", "i made", "did i pay") -> EventExtractor.Direction.DEBIT
            inherit -> previous!!.direction
            else -> null
        }
        val subject = when (intent) {
            Intent.PAYMENTS -> counterpartyFor(t) ?: residualSubject(t) ?: if (inherit) previous!!.subject else null
            Intent.FROM_PERSON -> person ?: if (inherit) previous!!.subject else null
            Intent.SOURCE -> bare.split(' ').filterNot { it in STOP_WORDS || it in SOURCE_FILLER || it in CHANNEL_WORDS || it.length < 3 }.joinToString(" ").ifBlank { null }
            Intent.SEARCH -> searchTerm(t)
            Intent.PLAN -> planKind
            else -> null
        }
        val target = when (intent) {
            Intent.NAVIGATE -> page
            Intent.STOCK -> stock ?: opened
            else -> null
        }
        return Query(intent, explicitRange ?: if (inherit) previous!!.range else defaultRange, subject, direction, text.trim(),
            channel = channel ?: if (inherit) previous!!.channel else null, target = target)
    }

    /** What a sitemap word opens, e.g. "ipos" -> Market · IPOs. */
    fun pageAction(word: String): Action? = SITEMAP.firstOrNull { it.first == word.trim() }?.second

    private fun stockName(raw: String): String? {
        val words = raw.split(' ').dropWhile { it in STOCK_LEAD }
        return words.joinToString(" ").trim().takeIf { name ->
            name.isNotEmpty() && words.size <= 4 && words.none { it in MARKET_WORDS || it in STOP_WORDS } && pageAction(name) == null
        }
    }

    /** A time window the user stated explicitly; an interpreter must not override it. */
    fun explicitRange(text: String, nowMillis: Long, zone: ZoneId): TimeRange? {
        val t = normalize(text)
        return DATE_SPAN.find(t)?.let { dateSpan(it, nowMillis, zone) } ?: rangeFor(t, nowMillis, zone)
    }

    private fun normalize(text: String) = text.lowercase(Locale.ROOT).replace(Regex("[?!.,]"), " ").replace(Regex("\\s+"), " ").trim()

    /** "between 1 september and 15 september": inclusive days; a year-less span is the most recent one not in the future. */
    private fun dateSpan(m: MatchResult, now: Long, zone: ZoneId): TimeRange? {
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val g = m.groupValues
        // DATE_RX groups per date: day-first day, day-first month, month-first month, month-first day, year.
        fun date(i: Int, defaultYear: Int): LocalDate? {
            val mon = MONTHS[(g[i + 1].ifBlank { g[i + 2] }).take(3)] ?: return null
            val d = g[i].ifBlank { g[i + 3] }.toIntOrNull() ?: return null
            return runCatching { LocalDate.of(g[i + 4].toIntOrNull() ?: defaultYear, mon, d) }.getOrNull()
        }
        val fromYear = g[5].toIntOrNull()
        var from = date(1, today.year) ?: return null
        var to = date(6, fromYear ?: today.year) ?: return null
        if (fromYear == null && g[10].isBlank() && from.isAfter(today)) { from = from.minusYears(1); to = to.minusYears(1) }
        if (to.isBefore(from)) to = to.plusYears(1)
        val fmt = java.time.format.DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)
        return TimeRange(start(from, zone), start(to.plusDays(1), zone), "${from.format(fmt)} – ${to.format(fmt)}")
    }

    private fun rangeFor(t: String, now: Long, zone: ZoneId): TimeRange? {
        val date = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        fun day(d: LocalDate, label: String) = TimeRange(start(d, zone), start(d.plusDays(1), zone), label)
        return when {
            t.containsWord("tomorrow") -> day(date.plusDays(1), "tomorrow")
            t.containsWord("yesterday") -> day(date.minusDays(1), "yesterday")
            t.containsWord("today") || t.containsWord("tonight") -> day(date, "today")
            t.contains("last week") -> {
                val monday = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).minusWeeks(1)
                TimeRange(start(monday, zone), start(monday.plusWeeks(1), zone), "last week")
            }
            t.contains("this week") || t.containsWord("week") -> {
                val monday = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                TimeRange(start(monday, zone), now + 1, "this week")
            }
            t.contains("last month") || t.contains("previous month") -> {
                val first = date.withDayOfMonth(1)
                TimeRange(start(first.minusMonths(1), zone), start(first, zone), "last month")
            }
            t.contains("this month") || t.containsWord("month") -> TimeRange(start(date.withDayOfMonth(1), zone), now + 1, "this month")
            else -> null
        }
    }

    private fun today(now: Long, zone: ZoneId): TimeRange {
        val d = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        return TimeRange(start(d, zone), start(d.plusDays(1), zone), "today")
    }

    private fun start(d: LocalDate, zone: ZoneId) = d.atStartOfDay(zone).toInstant().toEpochMilli()

    private fun personFor(t: String): String? {
        val patterns = listOf(
            Regex("\\bwhat did ([a-z][a-z .'-]{1,30}?) (?:send|say|write|message)"),
            Regex("\\b(?:from|by) ([a-z][a-z.'-]{1,20}(?: [a-z][a-z.'-]{1,20})?)$"),
            Regex("\\bwhat about ([a-z][a-z.'-]{1,20}(?: [a-z][a-z.'-]{1,20})?)$"),
            Regex("\\bmessages? (?:from )?([a-z][a-z.'-]{1,20})$"),
            Regex("^(?:did|has|have) ([a-z][a-z.'-]{1,20}(?: [a-z][a-z.'-]{1,20})?) (?:e-?mail(?:ed)?|mail(?:ed)?|message[ds]?|text(?:ed)?|call(?:ed)?|ping(?:ed)?|sen[dt]|write|wrote|repl(?:y|ied))\\b")
        )
        return patterns.firstNotNullOfOrNull { it.find(t)?.groupValues?.get(1)?.trim() }
            ?.takeUnless { it in NOT_PEOPLE || it in CHANNEL_WORDS || rangeWords.any { w -> it.containsWord(w) } }
    }

    /** "payments to amazon last month" -> "amazon": the counterparty is a filter over retrieved rows, never a fact. */
    private fun counterpartyFor(t: String): String? {
        val m = Regex("\\b(?:to|at|from|with)\\s+([a-z0-9][a-z0-9&.'-]*(?:\\s+[a-z0-9][a-z0-9&.'-]*){0,2})").find(t) ?: return null
        val words = m.groupValues[1].split(' ').dropWhile { it in ARTICLES }.takeWhile { it !in COUNTERPARTY_STOP }
        return words.joinToString(" ").trim().ifBlank { null }?.takeUnless { it in setOf("me", "my", "i", "us") }
    }

    /** "show my amazon transactions" -> "amazon": leftover words after removing question and payment vocabulary. */
    private fun residualSubject(t: String): String? =
        t.split(' ').filterNot { it in STOP_WORDS || it in PAYMENT_WORDS || it in COUNTERPARTY_STOP || it.length < 3 || it.any(Char::isDigit) }
            .takeIf { it.size in 1..3 }?.joinToString(" ")

    private fun searchTerm(t: String): String? =
        t.split(' ').filterNot { it in STOP_WORDS || it.length < 3 }.joinToString(" ").ifBlank { null }

    // ---------------------------------------------------------------- answering

    /** Optional interpreters only choose the query; a failure or null falls through to deterministic parsing. */
    suspend fun interpret(text: String, previous: Query?, interpreters: List<QueryInterpreter>, nowMillis: Long, zone: ZoneId): Interpretation {
        for (interpreter in interpreters) {
            val q = try {
                interpreter.interpret(text, previous, nowMillis, zone)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                null
            }
            if (q != null) return Interpretation(q, interpreter.name)
        }
        return Interpretation(parse(text, previous, nowMillis, zone), DeterministicInterpreter.name)
    }

    suspend fun ask(
        text: String, previous: Query?, interpreters: List<QueryInterpreter>, retriever: Retriever, nowMillis: Long, zone: ZoneId
    ): Answer = interpret(text, previous, interpreters, nowMillis, zone).let { answer(it.query, retriever, nowMillis, it.interpretedBy, zone) }

    suspend fun answer(
        query: Query, retriever: Retriever, nowMillis: Long, interpretedBy: String = DeterministicInterpreter.name, zone: ZoneId = ZoneId.systemDefault()
    ): Answer {
        when (query.intent) {
            Intent.NAVIGATE -> return query.target?.let(::pageAction)?.let {
                Answer(query, "Opening ${it.label.removePrefix("Open ")}.", emptyList(), emptyList(), emptyList(), false, interpretedBy, action = it.copy(auto = true))
            } ?: help(query, interpretedBy)
            Intent.HELP -> return help(query, interpretedBy)
            Intent.PLAN -> return plan(query, retriever.planItems(), PLAN_KINDS[query.subject], if (query.subject == "tasks") "task" else "reminder", nowMillis, zone, interpretedBy)
            // Parsed reminders carry real due dates, so they answer "what's due" better than raw bill notifications.
            Intent.BILLS_DUE -> retriever.planItems().takeIf { items -> items.any { it.kind in BILL_KINDS && it.status != "DONE" && it.dueAt != null } }
                ?.let { return plan(query, it, BILL_KINDS, "bill", nowMillis, zone, interpretedBy) }
            else -> Unit
        }
        val all = retriever.events(query.range.from, query.range.to, RETRIEVAL_LIMIT)
        val canonical = all.filter { it.duplicateOfId == null || all.none { o -> o.id == it.duplicateOfId } }
        fun facts(e: NotificationEventEntity) = EventNormalizer.factsFromJson(e.intelligenceJson)
        val r = query.range.label

        return when (query.intent) {
            Intent.PAYMENTS -> {
                val who = query.subject
                val rows = canonical.filter { it.category in MONEY }.mapNotNull { e ->
                    val m = facts(e).primaryAmount ?: return@mapNotNull null
                    if (query.direction != null && m.direction != query.direction) return@mapNotNull null
                    if (who != null && !mentions(e, facts(e), who)) null else e to m
                }
                val totals = rows.groupBy { it.second.currency to it.second.direction }
                    .map { (k, v) -> "${k.second.name.lowercase()} ${formatMoney(v.sumOf { it.second.amountMinor }, k.first)}" }
                val matching = who?.let { " matching \"$it\"" }.orEmpty()
                build(query, rows.map { it.first }, interpretedBy,
                    headline = if (rows.isEmpty()) "I found no payments$matching with an amount $r." else "${rows.size} payment${s(rows.size)}$matching $r: ${totals.joinToString(", ")}.",
                    detail = { e -> rows.first { it.first.id == e.id }.second.let { "${it.direction.name.lowercase()} ${formatMoney(it.amountMinor, it.currency)}" } },
                    followUps = listOf("What did I receive $r?", "Payments yesterday", "Payments last week"))
            }
            Intent.DELIVERIES -> {
                fun isOpen(e: NotificationEventEntity) =
                    e.category == "DELIVERY" && e.lifecycleState != EventLifecycle.State.RESOLVED.name && !e.archived && !facts(e).terminal
                // "Coming tomorrow" is about the expected date the notification mentions, not when it was posted.
                val byExpectedDate = query.range.label == "tomorrow" || query.range.label == "today"
                val rows = if (byExpectedDate) {
                    retriever.events(nowMillis - 14 * DAY, nowMillis + 1, RETRIEVAL_LIMIT)
                        .filter { isOpen(it) && facts(it).times.any { t -> t.epochMillis in query.range.from until query.range.to } }
                } else canonical.filter(::isOpen)
                // Only the latest update of each order is listed.
                val latest = rows.sortedByDescending { it.postedAt }.distinctBy { EventIntelligence.threadKey(it) }
                val phrase = if (byExpectedDate) "expected $r" else "in $r"
                build(query, latest, interpretedBy,
                    headline = if (latest.isEmpty()) "No open deliveries $phrase." else "${latest.size} open deliver${if (latest.size == 1) "y" else "ies"} $phrase.",
                    detail = { e -> facts(e).times.firstOrNull()?.raw?.let { "expected \"$it\"" } },
                    followUps = listOf("Deliveries tomorrow", "Deliveries today"))
            }
            Intent.BILLS_DUE -> {
                val bills = canonical.filter { (it.category == "BILLS" || it.category == "REMINDERS") && it.lifecycleState != EventLifecycle.State.RESOLVED.name && !it.archived }
                build(query, bills.distinctBy { EventIntelligence.threadKey(it) }, interpretedBy,
                    headline = if (bills.isEmpty()) "No open bills in $r." else "${bills.distinctBy { EventIntelligence.threadKey(it) }.size} open bill${s(bills.size)} from $r.",
                    detail = { e -> listOfNotNull(facts(e).primaryAmount?.let { formatMoney(it.amountMinor, it.currency) }, facts(e).times.firstOrNull { it.epochMillis > nowMillis }?.raw?.let { "due \"$it\"" }).joinToString(" · ").ifBlank { null } },
                    followUps = listOf("Payments this week"))
            }
            Intent.FROM_PERSON -> {
                val name = query.subject
                if (name == null) return build(query, emptyList(), interpretedBy, "Who do you mean? Try \"What did Rahul send me?\".", { null }, emptyList())
                val candidates = retriever.entities(name).filter { it.type in PERSONISH }
                clarifyPerson(query, name, candidates, canonical, retriever, interpretedBy)?.let { return it }
                val graphIds = candidates.flatMap { retriever.eventIdsFor(it.mergedIntoId ?: it.id) }.toSet()
                // "message" names no app, so only a specific channel narrows the sender search.
                val channel = query.channel?.takeUnless { it == "messages" }?.let(::channelFor)
                val rows = canonical.filter { e ->
                    (channel == null || channel.matches(e)) && (e.id in graphIds ||
                        ((e.category in PERSON_CATEGORIES || channel != null) && e.title.contains(name, ignoreCase = true)) ||
                        e.body.contains("from ${name}", ignoreCase = true) || e.sourceName.equals(name, ignoreCase = true))
                }
                val noun = channel?.noun ?: "notification"
                val yesNo = YES_NO.containsMatchIn(normalize(query.rawText))
                build(query, rows, interpretedBy,
                    headline = when {
                        rows.isEmpty() && yesNo -> "No ${plural(noun, 0)} from \"$name\" ${over(r)}."
                        rows.isEmpty() -> "Nothing from \"$name\" in $r."
                        else -> (if (yesNo) "Yes, " else "") + "${rows.size} ${plural(noun, rows.size)} from \"$name\" ${over(r)}."
                    },
                    detail = { e -> e.body.take(PREVIEW) },
                    followUps = listOf("What about yesterday?", "What about last week?"))
            }
            Intent.IMPORTANT -> {
                val rows = canonical.filter { !it.archived && (it.importanceScore >= IMPORTANT || EventIntelligence.importance(it).attentionScore >= IMPORTANT) }
                    .sortedByDescending { maxOf(it.importanceScore, EventIntelligence.importance(it).attentionScore) }
                build(query, rows, interpretedBy,
                    headline = if (rows.isEmpty()) "Nothing important $r." else "${rows.size} important item${s(rows.size)} $r.",
                    detail = { e -> e.body.take(PREVIEW) }, followUps = listOf("What did I miss?", "Payments today"))
            }
            Intent.MISSED -> {
                val rows = canonical.filter { !it.archived && it.lifecycleState == EventLifecycle.State.NEW.name && it.category != "OTHER" }
                build(query, rows, interpretedBy,
                    headline = if (rows.isEmpty()) "You haven't missed anything $r." else "${rows.size} unread notification${s(rows.size)} $r.",
                    detail = { e -> e.body.take(PREVIEW) }, followUps = listOf("What's important today?"))
            }
            Intent.TRADING -> {
                val rows = canonical.filter { it.isTrading }
                build(query, rows, interpretedBy,
                    headline = if (rows.isEmpty()) "No trading events in $r." else "${rows.size} trading event${s(rows.size)} in $r.",
                    detail = { e -> e.insightSummary ?: e.body.take(PREVIEW) }, followUps = listOf("Trading today"))
            }
            Intent.SOURCE -> {
                val channel = query.channel?.let(::channelFor)
                val words = query.subject?.split(' ').orEmpty()
                val rows = canonical.filter { e ->
                    (channel == null || channel.matches(e)) && words.all { w -> e.title.contains(w, true) || e.body.contains(w, true) }
                }
                val noun = channel?.noun ?: "notification"
                val about = query.subject?.let { " about \"$it\"" }.orEmpty()
                // Per channel the sender is the title's person ("Chat: Person"); across all apps it's the app.
                // A title that is just the app's name (content hidden by Android) names no sender.
                val senders = rows.mapNotNull { e ->
                    if (channel == null) e.sourceName else e.title.substringAfterLast(": ").trim().takeUnless { it.isBlank() || it.equals(e.sourceName, ignoreCase = true) }
                }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.take(3)
                val from = when (senders.size) {
                    0 -> ""
                    1 -> " From ${senders[0].key}."
                    else -> " Most from " + senders.joinToString { "${it.key} (${it.value})" } + "."
                }
                build(query, rows, interpretedBy,
                    headline = if (rows.isEmpty()) "No ${plural(noun, 0)}$about ${over(r)}." else "${rows.size} ${plural(noun, rows.size)}$about ${over(r)}.$from",
                    detail = { e -> e.body.take(PREVIEW) }, followUps = listOf("Yesterday", "This week", "Last week"))
            }
            Intent.STOCK -> {
                val name = query.target.orEmpty()
                val symbol = retriever.resolveSymbol(name)
                if (symbol == null) {
                    val search = answer(query.copy(intent = Intent.SEARCH, subject = name), retriever, nowMillis, interpretedBy, zone)
                    return search.copy(query = query, headline = "I couldn't find a listed stock called \"$name\". ${search.headline}")
                }
                val words = setOf(symbol.lowercase(), name)
                val rows = canonical.filter { e -> "${e.title} ${e.body}".lowercase().let { text -> words.any { text.containsWord(it) } } }
                build(query, rows, interpretedBy,
                    headline = "Opening $symbol in Market." + if (rows.isEmpty()) "" else " ${rows.size} notification${s(rows.size)} mention${if (rows.size == 1) "s" else ""} it ${over(r)}.",
                    detail = { e -> e.insightSummary ?: e.body.take(PREVIEW) }, followUps = listOf("Trading today", "Open trade calls")
                ).copy(action = Action("Open $symbol", Page.STOCK, symbol, auto = true))
            }
            Intent.PLAN, Intent.NAVIGATE, Intent.HELP -> error("answered before retrieval")
            Intent.SEARCH -> {
                val term = query.subject
                val rows = if (term == null) emptyList() else canonical.filter { e ->
                    val f = facts(e)
                    term.split(' ').all { w ->
                        e.title.contains(w, true) || e.body.contains(w, true) || e.sourceName.contains(w, true) ||
                            f.entities.any { it.value.contains(w, true) } || f.references.any { it.value.contains(w, true) }
                    }
                }
                build(query, rows, interpretedBy,
                    headline = when {
                        term == null -> "Try asking about payments, deliveries, bills, a person, or what's important today."
                        rows.isEmpty() -> "I found nothing matching \"$term\" in $r."
                        else -> "${rows.size} match${if (rows.size == 1) "" else "es"} for \"$term\" in $r."
                    },
                    detail = { e -> e.body.take(PREVIEW) }, followUps = listOf("What's important today?", "Payments this week"))
            }
        }
    }

    /** Several different people match a partial name and each has events in range: ask instead of merging them. */
    private suspend fun clarifyPerson(
        query: Query, name: String, candidates: List<ContextEntity>, canonical: List<NotificationEventEntity>, retriever: Retriever, interpretedBy: String
    ): Answer? {
        val people = candidates.filter { it.type == "PERSON" && it.mergedIntoId == null }.distinctBy { it.displayName.lowercase().trim() }
        if (people.size < 2 || people.any { it.displayName.equals(name, ignoreCase = true) }) return null
        val inRange = canonical.map { it.id }.toSet()
        val active = people.filter { p -> retriever.eventIdsFor(p.id).any { it in inRange } }.take(MAX_CLARIFY)
        if (active.size < 2) return null
        val names = active.map { it.displayName }
        return Answer(
            query = query,
            headline = "Which ${name.replaceFirstChar { it.titlecase(Locale.ROOT) }} do you mean: ${names.dropLast(1).joinToString(", ")} or ${names.last()}?",
            items = emptyList(), derivedFromEventIds = emptyList(),
            followUps = names.map { "What did $it send me?" },
            noResult = false, interpretedBy = interpretedBy, needsClarification = true
        )
    }

    private fun help(query: Query, interpretedBy: String) = Answer(
        query = query,
        headline = "I answer from what's on your phone. Try \"How many emails today?\", \"Did Rahul email me this week?\", " +
            "\"What came on Teams today?\", \"Payments to Amazon last month\", \"Which bills are due?\", \"Any birthdays this week?\" or \"TCS share price\". " +
            "I can also open any page: Home, Inbox, Plan (Reminders, Board), Trading (Marksy picks, Calls, Captured), " +
            "Market (Overview, Stocks, IPOs, Updates) and Settings.",
        items = emptyList(), derivedFromEventIds = emptyList(),
        followUps = listOf("How many emails today?", "What's coming up?", "Open IPOs"), noResult = false, interpretedBy = interpretedBy
    )

    /** Open plan items due in the asked window; overdue ones always count because they still need doing. */
    private fun plan(query: Query, items: List<PlanItemEntity>, kinds: Set<String>?, noun: String, now: Long, zone: ZoneId, interpretedBy: String): Answer {
        val range = query.range
        val from = if (range.label == "tomorrow") range.from else Long.MIN_VALUE
        val to = when (range.label) {
            "today", "tomorrow" -> range.to
            "this week" -> range.from + 7 * DAY
            "this month" -> Instant.ofEpochMilli(range.from).atZone(zone).toLocalDate().plusMonths(1).let { start(it, zone) }
            else -> now + 30 * DAY
        }
        val open = items.filter { p -> p.status != "DONE" && p.dueAt != null && p.dueAt in from until to && (kinds == null || p.kind in kinds) }.sortedBy { it.dueAt }
        fun line(p: PlanItemEntity) = listOfNotNull(p.title, PlanText.amount(p.amountMinor)).joinToString(" ") +
            (PlanText.dueLabel(p.dueAt, now, zone)?.let { " ($it)" } ?: "")
        val more = if (open.size > MAX_PLAN_LINES) " and ${open.size - MAX_PLAN_LINES} more" else ""
        val window = if (range.label.startsWith("the last")) "the next 30 days" else range.label
        return Answer(
            query = query,
            headline = if (open.isEmpty()) "No open ${plural(noun, 0)} ${over(window)}."
                else "${open.size} open ${plural(noun, open.size)}: ${open.take(MAX_PLAN_LINES).joinToString(", ") { line(it) }}$more.",
            items = open.take(MAX_ITEMS).map { Item(it.sourceEventId ?: 0, it.title, "Plan", it.dueAt!!, line(it)) },
            derivedFromEventIds = open.mapNotNull { it.sourceEventId },
            followUps = listOf("Any birthdays this week?", "Which bills are due?", "Open my to do board"),
            noResult = open.isEmpty(), interpretedBy = interpretedBy,
            action = Action("Open Plan · Reminders", Page.PLAN, "Reminders")
        )
    }

    private class Channel(val key: String, val noun: String, val packages: List<String>, val category: String? = null) {
        fun matches(e: NotificationEventEntity) =
            packages.any { e.sourcePackage.contains(it, ignoreCase = true) } || e.sourceName.contains(key, ignoreCase = true) || e.category == category
    }

    // An app the list doesn't know ("truecaller") still filters by its package and name.
    private fun channelFor(key: String) = CHANNELS.firstOrNull { it.key == key } ?: Channel(key, "$key notification", listOf(key))

    private fun plural(noun: String, n: Int) = if (n == 1 || noun.endsWith("SMS")) noun else "${noun}s"

    private fun over(label: String) = if (label.startsWith("the ") || label.first().isDigit()) "in $label" else label

    private fun mentions(e: NotificationEventEntity, f: EventExtractor.Facts, who: String): Boolean =
        f.entities.any { it.value.contains(who, true) } || e.title.contains(who, true) || e.body.contains(who, true)

    private fun build(
        query: Query, rows: List<NotificationEventEntity>, interpretedBy: String, headline: String,
        detail: (NotificationEventEntity) -> String?, followUps: List<String>
    ): Answer {
        val shown = rows.sortedByDescending { it.postedAt }.take(MAX_ITEMS)
        return Answer(
            query = query,
            headline = headline,
            items = shown.map { Item(it.id, it.title.ifBlank { it.sourceName }, it.sourceName, it.postedAt, detail(it)?.ifBlank { null }) },
            derivedFromEventIds = rows.map { it.id },
            followUps = followUps,
            noResult = rows.isEmpty(),
            interpretedBy = interpretedBy
        )
    }

    fun formatMoney(minor: Long, currency: String): String {
        val symbol = when (currency) { "INR" -> "₹"; "USD" -> "$"; "EUR" -> "€"; "GBP" -> "£"; else -> "$currency " }
        val whole = minor / 100
        val frac = minor % 100
        return if (frac == 0L) "$symbol$whole" else "$symbol$whole.${frac.toString().padStart(2, '0')}"
    }

    private fun s(n: Int) = if (n == 1) "" else "s"
    private fun has(t: String, vararg words: String) = words.any { if (' ' in it) t.contains(it) else t.containsWord(it) }

    private fun String.containsWord(term: String): Boolean =
        Regex("(?<![\\p{L}\\p{N}])${Regex.escape(term)}(?![\\p{L}\\p{N}])").containsMatchIn(this)

    private val MONEY = setOf("PAYMENTS", "BANKING", "BILLS")
    private val PERSONISH = setOf("PERSON", "COMPANY", "MERCHANT")
    private val CHANNELS = listOf(
        Channel("gmail", "Gmail email", listOf("com.google.android.gm")),
        Channel("outlook", "Outlook email", listOf("outlook")),
        Channel("email", "email", listOf("com.google.android.gm", "outlook", "mail"), "EMAIL"),
        Channel("teams", "Teams notification", listOf("teams")),
        Channel("whatsapp", "WhatsApp message", listOf("whatsapp")),
        Channel("sms", "SMS", listOf("messaging", "mms", "truecaller")),
        Channel("telegram", "Telegram message", listOf("telegram")),
        Channel("slack", "Slack notification", listOf("slack")),
        Channel("instagram", "Instagram notification", listOf("instagram")),
        Channel("linkedin", "LinkedIn notification", listOf("linkedin")),
        Channel("messages", "message", emptyList(), "MESSAGES")
    )
    // Specific apps first, so "messages on whatsapp" is WhatsApp rather than any message.
    private val CHANNEL_WORDS = linkedMapOf(
        "gmail" to "gmail", "outlook" to "outlook", "teams" to "teams", "whatsapp" to "whatsapp", "telegram" to "telegram",
        "slack" to "slack", "instagram" to "instagram", "linkedin" to "linkedin", "email" to "email", "emails" to "email",
        "mail" to "email", "mails" to "email", "e-mail" to "email", "sms" to "sms", "text" to "sms", "texts" to "sms",
        "message" to "messages", "messages" to "messages", "chats" to "messages"
    )
    private val CHANNEL_PHRASE = Regex("\\b(?:on|in|via|over|through) (?:the )?(?:${CHANNEL_WORDS.keys.joinToString("|") { Regex.escape(it) }})\\b")
    private val RANGE_PHRASE = Regex("\\b(?:(?:this|last|previous|next) (?:week|month)|today|tonight|yesterday|tomorrow|so far)\\b")
    private val NAV_VERB = Regex("^(?:please )?(?:open|go to|goto|take me to|navigate to|switch to|jump to)(?: the| my)? (.+)$")
    private val STOCK_PATTERNS = listOf(
        Regex("\\b(?:price|quote|chart|ltp) (?:of|for) (.+)$"),
        Regex("^(?:how is|how's|hows) (.+?) (?:doing|performing|trading)$"),
        Regex("^(.+?) (?:share|stock)(?: price| quote| chart)?$"),
        Regex("^(.+?) (?:price|quote|ltp)$")
    )
    private val STOCK_LEAD = setOf("what", "what's", "whats", "is", "the", "show", "me", "get", "check", "current", "today's", "latest", "live", "any", "a", "my", "best", "top", "which")
    private val MARKET_WORDS = setOf("market", "markets", "nifty", "sensex", "index", "indices")
    private val SOURCE_FILLER = setOf("receive", "received", "get", "got", "come", "came", "many", "count", "number", "notification", "notifications",
        "new", "any", "summarize", "summarise", "summary", "sent", "there", "been", "anything", "list", "much", "today's")
    private val PLAN_KINDS = mapOf("birthdays" to setOf("BIRTHDAY"), "tasks" to setOf("TASK", "FOLLOW_UP"))
    private val BILL_KINDS = setOf("BILL", "EMI", "CARD_DUE")
    private val PERSON_CATEGORIES = setOf("MESSAGES", "EMAIL", "WORK")
    private val YES_NO = Regex("^(?:did|do|have|has|was|were|is there|are there|any)\\b")

    private fun act(page: Page, arg: String? = null, tab: String? = null) = Action("Open ${page.title}" + (tab?.let { " · $it" } ?: ""), page, arg)
    /** Every page and tab Ask can open, by the words people use for them. */
    internal val SITEMAP: List<Pair<String, Action>> = listOf(
        "home" to act(Page.HOME), "dashboard" to act(Page.HOME),
        "inbox" to act(Page.INBOX), "smart inbox" to act(Page.INBOX), "notifications" to act(Page.INBOX),
        "emails" to act(Page.INBOX, "EMAIL", "Emails"), "messages" to act(Page.INBOX, "MESSAGES", "Messages"),
        "payments" to act(Page.INBOX, "PAYMENTS", "Payments"), "banking" to act(Page.INBOX, "BANKING", "Banking"),
        "work" to act(Page.INBOX, "WORK", "Work"), "teams" to act(Page.INBOX, "TEAMS", "Teams"), "deliveries" to act(Page.INBOX, "DELIVERY", "Delivery"),
        "plan" to act(Page.PLAN), "reminders" to act(Page.PLAN, "Reminders", "Reminders"),
        "board" to act(Page.PLAN, "Board", "Board"), "kanban" to act(Page.PLAN, "Board", "Board"), "todo" to act(Page.PLAN, "Board", "Board"),
        "to do" to act(Page.PLAN, "Board", "Board"), "to do board" to act(Page.PLAN, "Board", "Board"), "todo board" to act(Page.PLAN, "Board", "Board"),
        "to-do board" to act(Page.PLAN, "Board", "Board"), "tasks" to act(Page.PLAN, "Board", "Board"),
        "trading" to act(Page.TRADING), "picks" to act(Page.TRADING, "Marksy picks", "Marksy picks"), "marksy picks" to act(Page.TRADING, "Marksy picks", "Marksy picks"),
        "calls" to act(Page.TRADING, "Calls", "Calls"), "trade calls" to act(Page.TRADING, "Calls", "Calls"), "trading calls" to act(Page.TRADING, "Calls", "Calls"),
        "captured" to act(Page.TRADING, "Captured", "Captured"), "captured trades" to act(Page.TRADING, "Captured", "Captured"),
        "market" to act(Page.MARKET, "OVERVIEW"), "overview" to act(Page.MARKET, "OVERVIEW", "Overview"), "market overview" to act(Page.MARKET, "OVERVIEW", "Overview"),
        "indices" to act(Page.MARKET, "OVERVIEW", "Overview"), "stocks" to act(Page.MARKET, "STOCKS", "Stocks"), "stock search" to act(Page.MARKET, "STOCKS", "Stocks"),
        "ipo" to act(Page.MARKET, "IPOS", "IPOs"), "ipos" to act(Page.MARKET, "IPOS", "IPOs"),
        "updates" to act(Page.MARKET, "UPDATES", "Updates"), "market updates" to act(Page.MARKET, "UPDATES", "Updates"),
        "market news" to act(Page.MARKET, "UPDATES", "Updates"), "news" to act(Page.MARKET, "UPDATES", "Updates"),
        "settings" to act(Page.SETTINGS), "profile" to act(Page.SETTINGS), "more" to act(Page.SETTINGS)
    )
    private const val MAX_PLAN_LINES = 5
    private val NOT_PEOPLE = setOf("me", "you", "them", "work", "bank", "the bank", "amazon", "whatsapp", "email")
    private val COUNTERPARTY_STOP = setOf("last", "this", "today", "yesterday", "tomorrow", "week", "month", "in", "on", "for", "during", "since", "and", "or")
    private val PAYMENT_WORDS = setOf("payment", "payments", "paid", "pay", "spent", "spend", "spending", "debited", "credited", "received", "transaction",
        "transactions", "money", "much", "many", "total", "make", "made", "recent", "list", "see", "give", "amount", "debit", "credit", "upi", "card", "i've")
    private val ARTICLES = setOf("a", "an", "the")
    private val MONTHS = mapOf("jan" to 1, "feb" to 2, "mar" to 3, "apr" to 4, "may" to 5, "jun" to 6, "jul" to 7, "aug" to 8, "sep" to 9, "oct" to 10, "nov" to 11, "dec" to 12)
    private const val MONTH_RX = "(jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|june?|july?|aug(?:ust)?|sep(?:t(?:ember)?)?|oct(?:ober)?|nov(?:ember)?|dec(?:ember)?)"
    private const val DATE_RX = "(?:(\\d{1,2})(?:st|nd|rd|th)?(?: of)? $MONTH_RX|$MONTH_RX (\\d{1,2})(?:st|nd|rd|th)?)(?: (\\d{4}))?"
    private val DATE_SPAN = Regex("\\b(?:between|from) $DATE_RX (?:and|to|until|till|-) $DATE_RX\\b")
    private const val MAX_CLARIFY = 4
    private val rangeWords = listOf("today", "yesterday", "tomorrow", "week", "month")
    private val STOP_WORDS = setOf("what", "whats", "show", "find", "tell", "about", "the", "and", "any", "did", "does", "for", "from", "with", "this", "that", "last", "week", "today", "yesterday", "month", "have", "has", "was", "are", "were", "get", "got", "all", "my", "me", "your", "how", "when", "where", "who", "which")
    private const val DAY = 24 * 60 * 60 * 1000L
    private const val RETRIEVAL_LIMIT = 1000
    private const val MAX_ITEMS = 25
    private const val PREVIEW = 120
    private const val IMPORTANT = 70
}
