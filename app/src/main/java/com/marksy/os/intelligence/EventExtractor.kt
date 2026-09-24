package com.marksy.os.intelligence

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale

/**
 * Deterministic structured-fact extraction from one notification (EPIC-010).
 * Pure: no Android, no network, no model. Every fact carries its own confidence
 * and a `signal` naming the rule that produced it, so decisions stay explainable.
 */
object EventExtractor {
    enum class EntityType { PERSON, COMPANY, MERCHANT, BANK, DELIVERY, STOCK, APP }
    enum class ReferenceType { TRANSACTION, ORDER, TRACKING, REFERENCE }
    enum class Direction { DEBIT, CREDIT, UNKNOWN }

    data class Entity(val type: EntityType, val value: String, val confidence: Float, val signal: String)

    /** Money is kept in minor units so equality/correlation never depends on float rounding. */
    data class Money(val amountMinor: Long, val currency: String, val direction: Direction, val raw: String)

    data class Reference(val type: ReferenceType, val value: String)

    /** A date/time mentioned in the text, resolved against the event's own postedAt. */
    data class TimeMention(val epochMillis: Long, val raw: String, val hasTime: Boolean)

    data class Facts(
        val entities: List<Entity>,
        val amounts: List<Money>,
        val references: List<Reference>,
        val times: List<TimeMention>,
        /** True when the text says the underlying real-world item is finished (delivered, paid...). */
        val terminal: Boolean
    ) {
        val primaryAmount: Money? get() = amounts.firstOrNull()
        val transactionReference: Reference? get() = references.firstOrNull { it.type == ReferenceType.TRANSACTION }
        val threadReference: Reference?
            get() = references.firstOrNull { it.type == ReferenceType.ORDER }
                ?: references.firstOrNull { it.type == ReferenceType.TRACKING }

        companion object {
            val EMPTY = Facts(emptyList(), emptyList(), emptyList(), emptyList(), terminal = false)
        }
    }

    fun extract(
        sourcePackage: String,
        sourceName: String,
        category: String,
        title: String,
        body: String,
        postedAt: Long,
        zone: ZoneId = ZoneId.systemDefault()
    ): Facts {
        val text = "$title\n$body"
        val lower = text.lowercase(Locale.ROOT)
        return Facts(
            entities = entities(sourcePackage, sourceName, category, title, text, lower),
            amounts = amounts(text, lower),
            references = references(text),
            times = times(text, postedAt, zone),
            terminal = TERMINAL_TERMS.any { lower.containsWord(it) }
        )
    }

    // ---- entities ---------------------------------------------------------

    private fun entities(
        sourcePackage: String,
        sourceName: String,
        category: String,
        title: String,
        text: String,
        lower: String
    ): List<Entity> {
        val out = mutableListOf<Entity>()
        if (sourceName.isNotBlank()) out += Entity(EntityType.APP, sourceName.trim(), 1f, "source app")

        val segments = sourcePackage.lowercase(Locale.ROOT).split('.', '_')
        // Short tokens ("sbi", "bob", "pnb") only count as a whole package segment or the full bank name,
        // so "com.bobble.keyboard" or a friend called Bob never become a bank.
        fun inPackage(token: String) = segments.any { it == token || (token.length >= 4 && it.startsWith(token)) }
        BANKS.forEach { (token, name) ->
            val byPackage = inPackage(token)
            if (byPackage || lower.containsWord(name.lowercase(Locale.ROOT)) || (token.length >= 4 && lower.containsWord(token))) {
                out += Entity(EntityType.BANK, name, if (byPackage) .95f else .8f, "known bank name")
            }
        }
        COURIERS.forEach { (token, name) ->
            if (inPackage(token) || lower.containsWord(token)) {
                out += Entity(EntityType.DELIVERY, name, .9f, "known courier name")
            }
        }

        // Messaging notifications put the sender (person or group) in the title.
        if (category == "MESSAGES" && title.isNotBlank() && PERSONISH.matches(title.trim())) {
            out += Entity(EntityType.PERSON, title.trim().take(MAX_NAME), .8f, "message sender title")
        }

        COUNTERPARTY.findAll(text).forEach { match ->
            val name = match.groupValues[2].trim().trimEnd('.', ',')
            if (name.length < 2 || name.lowercase(Locale.ROOT) in COUNTERPARTY_STOP) return@forEach
            val verb = match.groupValues[1].lowercase(Locale.ROOT)
            val type = when {
                verb.startsWith("at") || verb.startsWith("merchant") -> EntityType.MERCHANT
                name.any(Char::isDigit) || name.contains('@') -> EntityType.MERCHANT
                category == "PAYMENTS" || category == "BANKING" || category == "BILLS" ->
                    if (looksLikeCompany(name)) EntityType.COMPANY else EntityType.PERSON
                else -> if (looksLikeCompany(name)) EntityType.COMPANY else EntityType.PERSON
            }
            out += Entity(type, name.take(MAX_NAME), .7f, "\"$verb\" counterparty")
        }

        if (category == "TRADING") {
            SYMBOL.findAll(text.uppercase(Locale.ROOT)).map { it.value }
                .firstOrNull { it !in NOISE_SYMBOLS }
                ?.let { out += Entity(EntityType.STOCK, it, .75f, "ticker-like token") }
        }
        return out.distinctBy { it.type to it.value.lowercase(Locale.ROOT) }
    }

    private fun looksLikeCompany(name: String): Boolean {
        val l = name.lowercase(Locale.ROOT)
        return COMPANY_SUFFIXES.any { l.endsWith(it) || l.contains(" $it") } || name == name.uppercase(Locale.ROOT) && name.length > 3
    }

    // ---- money ------------------------------------------------------------

    private fun amounts(text: String, lower: String): List<Money> {
        fun directionOf(s: String) = when {
            DEBIT_TERMS.any { s.containsWord(it) } -> Direction.DEBIT
            CREDIT_TERMS.any { s.containsWord(it) } -> Direction.CREDIT
            else -> Direction.UNKNOWN
        }
        val whole = directionOf(lower)
        return MONEY.findAll(text).mapNotNull { m ->
            // The verb nearest an amount decides its direction ("500 debited ... balance 12,300 credited").
            val window = lower.substring(maxOf(0, m.range.first - DIRECTION_WINDOW), minOf(lower.length, m.range.last + 1 + DIRECTION_WINDOW))
            val direction = directionOf(window).takeIf { it != Direction.UNKNOWN } ?: whole
            val symbol = (m.groups[1]?.value ?: m.groups[4]?.value)?.trim() ?: return@mapNotNull null
            val number = (m.groups[2]?.value ?: m.groups[3]?.value)?.replace(",", "") ?: return@mapNotNull null
            val currency = CURRENCIES[symbol.lowercase(Locale.ROOT).trimEnd('.')] ?: return@mapNotNull null
            val minor = number.toBigDecimalOrNull()?.movePointRight(2)?.toLong() ?: return@mapNotNull null
            if (minor <= 0) null else Money(minor, currency, direction, m.value.trim())
        }.distinctBy { it.amountMinor to it.currency }.toList()
    }

    // ---- references -------------------------------------------------------

    private fun references(text: String): List<Reference> {
        val out = mutableListOf<Reference>()
        REFERENCE_PATTERNS.forEach { (type, pattern) ->
            pattern.findAll(text).forEach { m ->
                val value = m.groupValues.last().trim().uppercase(Locale.ROOT)
                // Pure-letter tokens are words, not identifiers.
                if (value.length >= 6 && value.any(Char::isDigit)) out += Reference(type, value)
            }
        }
        return out.distinctBy { it.type to it.value }
    }

    // ---- dates/times ------------------------------------------------------

    private fun times(text: String, postedAt: Long, zone: ZoneId): List<TimeMention> {
        if (postedAt <= 0L) return emptyList()
        val base = Instant.ofEpochMilli(postedAt).atZone(zone).toLocalDate()
        val lower = text.lowercase(Locale.ROOT)
        val time = TIME.find(lower)?.let(::parseTime)
        val out = mutableListOf<TimeMention>()

        fun add(date: LocalDate, raw: String) {
            val t = time?.first ?: LocalTime.MIDNIGHT
            out += TimeMention(date.atTime(t).atZone(zone).toInstant().toEpochMilli(), raw, time != null)
        }

        when {
            lower.containsWord("tomorrow") -> add(base.plusDays(1), "tomorrow")
            lower.containsWord("today") || lower.containsWord("tonight") -> add(base, "today")
        }
        NUMERIC_DATE.findAll(text).forEach { m ->
            val (d, mo, y) = Triple(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3])
            val year = when (y.length) { 2 -> 2000 + y.toInt(); 4 -> y.toInt(); else -> base.year }
            runCatching { LocalDate.of(year, mo, d) }.getOrNull()?.let { add(it, m.value) }
        }
        NAMED_DATE.findAll(text).forEach { m ->
            val day = m.groupValues[1].toInt()
            val month = MONTHS[m.groupValues[2].lowercase(Locale.ROOT).take(3)] ?: return@forEach
            val year = m.groupValues[3].takeIf { it.isNotBlank() }?.let { if (it.length == 2) 2000 + it.toInt() else it.toInt() }
            val date = runCatching { LocalDate.of(year ?: base.year, month, day) }.getOrNull() ?: return@forEach
            add(date, m.value.trim())
        }
        if (out.isEmpty() && time != null) add(base, time.second)
        return out.distinctBy { it.epochMillis }
    }

    private fun parseTime(m: MatchResult): Pair<LocalTime, String>? {
        val raw = m.value.trim()
        return try {
            val normalized = raw.replace(".", ":").replace(Regex("\\s+"), "").uppercase(Locale.ROOT)
            val t = if (normalized.endsWith("AM") || normalized.endsWith("PM")) {
                val withMinutes = if (normalized.contains(':')) normalized else normalized.dropLast(2) + ":00" + normalized.takeLast(2)
                LocalTime.parse(withMinutes.padStart(7, '0'), DateTimeFormatter.ofPattern("hh:mma", Locale.ENGLISH))
            } else {
                LocalTime.parse(normalized.padStart(5, '0'), DateTimeFormatter.ofPattern("HH:mm"))
            }
            t to raw
        } catch (_: DateTimeParseException) {
            null
        }
    }

    private fun String.containsWord(term: String): Boolean {
        var start = indexOf(term)
        while (start >= 0) {
            val end = start + term.length
            val before = start == 0 || !this[start - 1].isLetterOrDigit()
            val after = end == length || !this[end].isLetterOrDigit()
            if (before && after) return true
            start = indexOf(term, start + 1)
        }
        return false
    }

    private const val MAX_NAME = 60
    private const val DIRECTION_WINDOW = 24

    private val CURRENCIES = mapOf(
        "₹" to "INR", "rs" to "INR", "inr" to "INR",
        "$" to "USD", "usd" to "USD", "€" to "EUR", "eur" to "EUR", "£" to "GBP", "gbp" to "GBP"
    )
    private const val NUM = "(\\d{1,3}(?:,\\d{2,3})+(?:\\.\\d{1,2})?|\\d+(?:\\.\\d{1,2})?)"
    // Groups: (1 symbol)(2 number) for "Rs 500", or (3 number)(4 symbol) for "500 INR".
    private val MONEY = Regex("(?i)(?:(₹|\\brs\\.?|\\binr|\\$|\\busd|€|\\beur|£|\\bgbp)\\s?$NUM|(?<![\\w.])$NUM\\s?(inr|rs|usd|eur|gbp)\\b)")

    private val DEBIT_TERMS = listOf("debited", "paid", "sent", "spent", "withdrawn", "purchase", "payment of", "deducted")
    private val CREDIT_TERMS = listOf("credited", "received", "refund", "refunded", "deposited", "cashback")

    private val TERMINAL_TERMS = listOf(
        "delivered", "payment successful", "paid successfully", "transaction successful", "bill paid",
        "refund processed", "order completed", "resolved", "order filled", "order executed"
    )

    private val REFERENCE_PATTERNS = listOf(
        ReferenceType.TRANSACTION to Regex("(?i)\\b(?:upi\\s*ref(?:erence)?(?:\\s*no\\.?)?|utr(?:\\s*no\\.?)?|txn\\s*(?:id|no\\.?|ref)?|transaction\\s*(?:id|no\\.?|ref(?:erence)?))\\s*[:#-]?\\s*([A-Z0-9]{6,24})"),
        ReferenceType.ORDER to Regex("(?i)\\border\\s*(?:id|no\\.?|number|#)\\s*[:#-]?\\s*([A-Z0-9-]{6,32})"),
        ReferenceType.TRACKING to Regex("(?i)\\b(?:awb|tracking\\s*(?:id|no\\.?|number)|shipment\\s*(?:id|no\\.?))\\s*[:#-]?\\s*([A-Z0-9]{6,32})"),
        ReferenceType.REFERENCE to Regex("(?i)\\b(?:ref(?:erence)?\\s*(?:id|no\\.?|number)?)\\s*[:#-]\\s*([A-Z0-9]{6,32})")
    )

    // A bare "12.50" is far more often money than a time, so dotted times need am/pm.
    private val TIME = Regex("\\b(?:[01]?\\d|2[0-3]):[0-5]\\d\\s?(?:am|pm)?\\b|\\b(?:1[0-2]|0?[1-9])(?:[.:][0-5]\\d)?\\s?(?:am|pm)\\b")
    private val NUMERIC_DATE = Regex("\\b(0?[1-9]|[12]\\d|3[01])[/-](0?[1-9]|1[0-2])[/-](\\d{2}|\\d{4})\\b")
    private val NAMED_DATE = Regex("(?i)\\b(0?[1-9]|[12]\\d|3[01])[\\s-](jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*(?:[\\s,-]+(\\d{4}|\\d{2}))?\\b")
    private val MONTHS = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")
        .mapIndexed { i, m -> m to i + 1 }.toMap()

    // Verb is case-insensitive but the name must start with a capital, which keeps prose ("to your account") out.
    private val COUNTERPARTY = Regex("\\b((?i:paid to|sent to|to|from|at|merchant))\\s+([A-Z][A-Za-z0-9&@.' -]{1,40}?)(?=\\s+(?i:on|via|for|using|ref|upi|a/c|ac|is|has|was|of)\\b|[.,;\\n]|$)")
    private val COUNTERPARTY_STOP = setOf(
        "you", "your", "your account", "account", "a/c", "the", "us", "me", "today", "tomorrow", "bank"
    )
    private val COMPANY_SUFFIXES = listOf("ltd", "limited", "pvt", "inc", "llp", "llc", "corp", "technologies", "services", "retail", "bank")
    private val PERSONISH = Regex("^[\\p{L}][\\p{L} .'-]{0,40}$")

    private val BANKS = listOf(
        "hdfc" to "HDFC Bank", "icici" to "ICICI Bank", "sbi" to "SBI", "axis" to "Axis Bank",
        "kotak" to "Kotak Mahindra Bank", "yes bank" to "Yes Bank", "idfc" to "IDFC First Bank",
        "indusind" to "IndusInd Bank", "pnb" to "Punjab National Bank", "bob" to "Bank of Baroda"
    )
    private val COURIERS = listOf(
        "delhivery" to "Delhivery", "bluedart" to "Blue Dart", "ekart" to "Ekart",
        "shadowfax" to "Shadowfax", "dtdc" to "DTDC", "xpressbees" to "XpressBees", "ecom express" to "Ecom Express"
    )

    private val SYMBOL = Regex("\\b[A-Z][A-Z0-9.-]{1,14}\\b")
    private val NOISE_SYMBOLS = setOf(
        "BUY", "SELL", "ORDER", "TRADE", "EXECUTED", "FILLED", "AT", "AVG", "PRICE",
        "TARGET", "STOP", "LOSS", "PROFIT", "P&L", "MARKET", "ALERT", "POSITION",
        "OPENED", "CLOSED", "QTY", "PNL", "INR", "OTP", "RS", "NSE", "BSE",
        "UPDATE", "MOVED", "MOVE", "TO", "NEW", "CONFIRMATION", "REJECTED", "CANCELLED"
    )
}
