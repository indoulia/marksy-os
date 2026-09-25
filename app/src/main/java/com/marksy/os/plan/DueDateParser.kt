package com.marksy.os.plan

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Reads a payment due from a bill / EMI / card SMS or its Truecaller summary:
 * "•  ICICI Bank  •  Bill due on 6th Oct", "Total amount due Rs 14,917 … due date 06-Oct-26", "EMI … due on 05/10/2026".
 * Returns null unless the text says something is due and names a date, so spends and debits never become dues.
 */
object DueDateParser {
    private val months = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")
    private const val MONTH = """(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*\.?"""

    private val dueWord = Regex("""\b(?:over)?due\b(?!\s+(?:in|to)\b)""", RegexOption.IGNORE_CASE)
    private val paidWords = Regex("""\b(debited|paid|received|successful|spent)\b""", RegexOption.IGNORE_CASE)
    private val dayMonthYear = Regex("""\b(\d{1,2})(?:st|nd|rd|th)?[\s\-/]+$MONTH(?:[\s\-/,]+(\d{2,4}))?\b""", RegexOption.IGNORE_CASE)
    private val monthDayYear = Regex("""\b$MONTH\s+(\d{1,2})(?:st|nd|rd|th)?(?:,?\s+(\d{4}))?\b""", RegexOption.IGNORE_CASE)
    private val numeric = Regex("""\b(\d{1,2})[/\-.](\d{1,2})[/\-.](\d{2,4})\b""")
    private const val MONEY = """(?:₹|\brs\.?|\binr)\s*-?\s*([0-9][0-9,]*(?:\.[0-9]{1,2})?)"""
    private val totalDue = Regex("""total\s+(?:amount\s+)?due\s*(?:of|is|:)?\s*$MONEY""", RegexOption.IGNORE_CASE)
    private val money = Regex(MONEY, RegexOption.IGNORE_CASE)
    private val minimum = Regex("""min(?:imum)?\.?\s+(?:amount\s+)?due\s*(?:of|is|:)?\s*$""", RegexOption.IGNORE_CASE)
    private val bullet = Regex("""•\s*([^•]+?)\s*•""")
    private val smsFrom = Regex("""\bSMS from (.+)$""", RegexOption.IGNORE_CASE)
    private val moneyWords = Regex("""\b(bill|emi|loan|card|payment|recharge|premium|rent|fee|fees|invoice|dues)\b""", RegexOption.IGNORE_CASE)

    fun parse(title: String, body: String, postedAt: Long, zone: ZoneId = ZoneId.systemDefault()): DueNotice? {
        val text = "$title\n$body"
        val due = dueWord.find(text) ?: return null
        if (paidWords.containsMatchIn(text) && !Regex("""\b(amount|bill|emi|payment)\s+(?:is\s+)?due\b""", RegexOption.IGNORE_CASE).containsMatchIn(text)) return null
        val posted = Instant.ofEpochMilli(postedAt).atZone(zone).toLocalDate()
        // Prefer the date after "due"; fall back to any date in the message.
        val date = findDate(text.substring(due.range.first), posted) ?: findDate(text, posted) ?: return null
        val lower = text.lowercase()
        // A due with no money in it (a work deadline, a challenge) is not a bill.
        val amount = amount(text)
        if (amount == null && !moneyWords.containsMatchIn(text)) return null
        val counterparty = counterparty(title, body)
        val kind = when {
            "emi" in lower.split(Regex("\\W+")) || "loan" in lower -> PlanKind.EMI
            "card" in lower || (counterparty?.contains("bank", ignoreCase = true) == true && "bill" in lower) -> PlanKind.CARD_DUE
            else -> PlanKind.BILL
        }
        return DueNotice(kind, PlanRules.atAlertHour(date, zone), amount, counterparty)
    }

    private fun findDate(text: String, posted: LocalDate): LocalDate? {
        dayMonthYear.find(text)?.let { m -> return date(m.groupValues[1].toInt(), monthIndex(m.groupValues[2]), m.groupValues[3], posted) }
        monthDayYear.find(text)?.let { m -> return date(m.groupValues[2].toInt(), monthIndex(m.groupValues[1]), m.groupValues[3], posted) }
        numeric.find(text)?.let { m -> return date(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3], posted) }
        return null
    }

    private fun monthIndex(name: String): Int = months.indexOf(name.take(3).lowercase()) + 1

    // Indian SMS dates are day-first. Without a year, pick the one nearest the message (a Dec SMS "due 3 Jan" is next year).
    private fun date(day: Int, month: Int, year: String, posted: LocalDate): LocalDate? = runCatching {
        if (year.isNotEmpty()) {
            val y = year.toInt().let { if (it < 100) 2000 + it else it }
            return@runCatching LocalDate.of(y, month, day)
        }
        listOf(posted.year - 1, posted.year, posted.year + 1)
            .map { LocalDate.of(it, month, day) }
            .minBy { kotlin.math.abs(it.toEpochDay() - posted.toEpochDay()) }
    }.getOrNull()

    private fun amount(text: String): Long? {
        val raw = totalDue.find(text)?.groupValues?.get(1)
            ?: money.findAll(text).firstOrNull { m -> !minimum.containsMatchIn(text.substring(0, m.range.first).takeLast(40)) }?.groupValues?.get(1)
        return raw?.replace(",", "")?.toBigDecimalOrNull()?.movePointRight(2)?.toLong()
    }

    private fun counterparty(title: String, body: String): String? {
        bullet.find(body)?.let { return it.groupValues[1].trim() }
        smsFrom.find(body)?.let { return it.groupValues[1].trim().trimEnd('.') }
        val t = title.trim()
        // "your bill is due on Oct 05, 2026" is a sentence, not a name.
        val sentence = dueWord.containsMatchIn(t) || dayMonthYear.containsMatchIn(t) || monthDayYear.containsMatchIn(t) || numeric.containsMatchIn(t)
        return t.takeIf { it.isNotEmpty() && it.any(Char::isLetter) && !money.containsMatchIn(it) && it.length <= 40 && !sentence }
    }
}
