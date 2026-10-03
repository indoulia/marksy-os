package com.marksy.os

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAccessor
import java.util.Locale
import kotlin.math.abs

/** The one way Marksy writes money, percentages and dates: Indian grouping, a true minus, "3 Oct, 09:05". */
object MarksyFormat {
    const val MINUS = "−"
    const val ELLIPSIS = "…"

    private val DAY = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)
    private val DAY_TIME = DateTimeFormatter.ofPattern("d MMM, HH:mm", Locale.ENGLISH)
    private val TIME = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH)
    private val WEEKDAY_DAY = DateTimeFormatter.ofPattern("EEE, d MMM", Locale.ENGLISH)
    private val FULL_DAY = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)
    private val MONTH_YEAR = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH)
    private val TIME_12 = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)

    /** 1234567.891 -> "12,34,567.89" (no sign; callers add one). */
    fun number(v: Double, decimals: Int = 2): String {
        val plain = BigDecimal(abs(v)).setScale(decimals, RoundingMode.HALF_UP).toPlainString()
        val whole = plain.substringBefore('.')
        val grouped = if (whole.length <= 3) whole else whole.dropLast(3).reversed().chunked(2).joinToString(",").reversed() + "," + whole.takeLast(3)
        return grouped + plain.substringAfter('.', "").let { if (it.isEmpty()) "" else ".$it" }
    }

    fun rupees(v: Double, decimals: Int = 2): String = (if (isNegative(v, decimals)) MINUS else "") + "₹" + number(v, decimals)

    fun signedRupees(v: Double, decimals: Int = 2): String = sign(v, decimals) + "₹" + number(v, decimals)

    fun percent(v: Double, decimals: Int = 2, signed: Boolean = true): String =
        (if (signed) sign(v, decimals) else if (isNegative(v, decimals)) MINUS else "") + number(v, decimals).replace(",", "") + "%"

    fun day(date: LocalDate): String = DAY.format(date)
    fun dayTime(at: TemporalAccessor): String = DAY_TIME.format(at)
    fun time(at: TemporalAccessor): String = TIME.format(at)
    fun weekdayDay(at: TemporalAccessor): String = WEEKDAY_DAY.format(at)
    fun fullDay(at: TemporalAccessor): String = FULL_DAY.format(at)
    fun monthYear(at: TemporalAccessor): String = MONTH_YEAR.format(at)
    /** World clocks only; Marksy times are 24-hour everywhere else. */
    fun time12(at: TemporalAccessor): String = TIME_12.format(at)

    // A value that rounds to zero is written unsigned, never "−0.00".
    private fun isNegative(v: Double, decimals: Int) = v < 0 && BigDecimal(abs(v)).setScale(decimals, RoundingMode.HALF_UP).signum() != 0
    private fun sign(v: Double, decimals: Int): String = when {
        isNegative(v, decimals) -> MINUS
        BigDecimal(abs(v)).setScale(decimals, RoundingMode.HALF_UP).signum() == 0 -> ""
        else -> "+"
    }
}
