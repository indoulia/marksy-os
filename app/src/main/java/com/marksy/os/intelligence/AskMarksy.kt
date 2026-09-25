package com.marksy.os.intelligence

import com.marksy.os.data.local.ContextEntity
import com.marksy.os.data.local.NotificationEventEntity
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
    enum class Intent { PAYMENTS, DELIVERIES, FROM_PERSON, BILLS_DUE, IMPORTANT, MISSED, TRADING, SOURCE, SEARCH }

    data class TimeRange(val from: Long, val to: Long, val label: String)

    data class Query(
        val intent: Intent,
        val range: TimeRange,
        val subject: String? = null,
        val direction: EventExtractor.Direction? = null,
        val rawText: String = ""
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
        val needsClarification: Boolean = false
    )

    data class Interpretation(val query: Query, val interpretedBy: String)

    /** Read-only access to Marksy data. Implementations must never invent rows. */
    interface Retriever {
        suspend fun events(from: Long, to: Long, limit: Int): List<NotificationEventEntity>
        suspend fun entities(name: String): List<ContextEntity>
        suspend fun eventIdsFor(entityId: Long): List<Long>
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
        val person = personFor(t)
        val source = SOURCES.entries.firstOrNull { (k, _) -> t.containsWord(k) }?.value

        val intent = when {
            has(t, "bill", "bills", "due", "invoice", "recharge") -> Intent.BILLS_DUE
            has(t, "payment", "payments", "paid", "spent", "spend", "debited", "credited", "received money", "transactions", "transaction") -> Intent.PAYMENTS
            has(t, "delivery", "deliveries", "package", "packages", "parcel", "shipment", "order", "orders", "arriving") -> Intent.DELIVERIES
            has(t, "trade", "trades", "trading", "stock", "stocks", "opportunities") -> Intent.TRADING
            has(t, "miss", "missed", "unread") -> Intent.MISSED
            has(t, "important", "urgent", "priority", "happened", "highlights") -> Intent.IMPORTANT
            person != null -> Intent.FROM_PERSON
            source != null -> Intent.SOURCE
            // Short follow-ups ("and yesterday?", "what about Amit?") keep the previous intent.
            previous != null && (t.startsWith("and ") || t.startsWith("what about") || t.startsWith("how about") || t.split(' ').size <= 3) -> previous.intent
            else -> Intent.SEARCH
        }

        val defaultRange = when (intent) {
            Intent.BILLS_DUE -> TimeRange(nowMillis - 30 * DAY, nowMillis + DAY, "the last 30 days")
            Intent.DELIVERIES -> TimeRange(nowMillis - 14 * DAY, nowMillis + DAY, "the last 14 days")
            Intent.IMPORTANT, Intent.MISSED -> today(nowMillis, zone)
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
            Intent.SOURCE -> source
            Intent.SEARCH -> searchTerm(t)
            else -> null
        }
        return Query(intent, explicitRange ?: if (inherit) previous!!.range else defaultRange, subject, direction, text.trim())
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
            Regex("\\bmessages? (?:from )?([a-z][a-z.'-]{1,20})$")
        )
        return patterns.firstNotNullOfOrNull { it.find(t)?.groupValues?.get(1)?.trim() }
            ?.takeUnless { it in NOT_PEOPLE || rangeWords.any { w -> it.containsWord(w) } }
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
    ): Answer = interpret(text, previous, interpreters, nowMillis, zone).let { answer(it.query, retriever, nowMillis, it.interpretedBy) }

    suspend fun answer(query: Query, retriever: Retriever, nowMillis: Long, interpretedBy: String = DeterministicInterpreter.name): Answer {
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
                val bills = canonical.filter { it.category == "BILLS" && it.lifecycleState != EventLifecycle.State.RESOLVED.name && !it.archived }
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
                val rows = canonical.filter { e ->
                    e.id in graphIds || (e.category == "MESSAGES" && e.title.contains(name, ignoreCase = true)) ||
                        e.body.contains("from ${name}", ignoreCase = true)
                }
                build(query, rows, interpretedBy,
                    headline = if (rows.isEmpty()) "Nothing from \"$name\" in $r." else "${rows.size} notification${s(rows.size)} from \"$name\" in $r.",
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
                val src = query.subject.orEmpty()
                val rows = canonical.filter { it.sourcePackage.contains(src) || it.sourceName.contains(src, true) || (src == "mail" && it.category == "EMAIL") }
                build(query, rows, interpretedBy,
                    headline = if (rows.isEmpty()) "Nothing from $src in $r." else "${rows.size} notification${s(rows.size)} from $src in $r.",
                    detail = { e -> e.body.take(PREVIEW) }, followUps = listOf("Only today", "Yesterday"))
            }
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
    private val SOURCES = linkedMapOf("whatsapp" to "whatsapp", "gmail" to "gm", "email" to "mail", "emails" to "mail", "sms" to "messaging", "telegram" to "telegram", "slack" to "slack", "teams" to "teams")
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
