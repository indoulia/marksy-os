package com.marksy.os.plan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

class DueDateParserTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private val posted = ZonedDateTime.of(2026, 9, 25, 16, 0, 0, 0, zone).toInstant().toEpochMilli()
    private fun parse(title: String, body: String) = DueDateParser.parse(title, body, posted, zone)
    private fun day(millis: Long) = java.time.Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()

    // Regression: Truecaller's "₹14,917 · ICICI Bank · Bill due on 6th Oct" credit-card bill had no reminder.
    @Test fun truecallerBankBillIsACardDueWithAmountAndDate() {
        val notice = parse("₹14,917", "•  ICICI Bank  •  Bill due on 6th Oct SMS from ICICI Bank")!!
        assertEquals(PlanKind.CARD_DUE, notice.kind)
        assertEquals(LocalDate.of(2026, 10, 6), day(notice.dueAt))
        assertEquals(1_491_700L, notice.amountMinor)
        assertEquals("ICICI Bank", notice.counterparty)
        assertEquals(9, java.time.Instant.ofEpochMilli(notice.dueAt).atZone(zone).hour)
    }

    @Test fun overdueBillKeepsItsPastDateThisYear() {
        val notice = parse("₹-80", "•  Provilac Milk  •  Bill overdue on 25th Aug SMS from Provilac Milk")!!
        assertEquals(PlanKind.BILL, notice.kind)
        assertEquals(LocalDate.of(2026, 8, 25), day(notice.dueAt))
        assertEquals(8_000L, notice.amountMinor)
        assertEquals("Provilac Milk", notice.counterparty)
    }

    @Test fun cardStatementUsesTotalNotMinimumAmountAndDayMonthYear() {
        val notice = parse(
            "ICICI Bank",
            "ICICI Bank Credit Card XX1234: Total amount due Rs 14,917.00, minimum amount due Rs 750.00, due date 06-Oct-26."
        )!!
        assertEquals(PlanKind.CARD_DUE, notice.kind)
        assertEquals(1_491_700L, notice.amountMinor)
        assertEquals(LocalDate.of(2026, 10, 6), day(notice.dueAt))
    }

    @Test fun emiWithNumericDate() {
        val notice = parse("HDFC Bank", "Your EMI of Rs.5,432 for loan a/c XX98 is due on 05/10/2026.")!!
        assertEquals(PlanKind.EMI, notice.kind)
        assertEquals(543_200L, notice.amountMinor)
        assertEquals(LocalDate.of(2026, 10, 5), day(notice.dueAt))
        assertEquals("HDFC Bank", notice.counterparty)
    }

    @Test fun earlyNextYearDueDateRollsTheYear() {
        val december = ZonedDateTime.of(2026, 12, 28, 10, 0, 0, 0, zone).toInstant().toEpochMilli()
        val notice = DueDateParser.parse("Airtel", "Your bill of Rs 599 is due by 3 Jan", december, zone)!!
        assertEquals(LocalDate.of(2027, 1, 3), day(notice.dueAt))
    }

    @Test fun spendsAndCompletedPaymentsAreNotDues() {
        assertNull(parse("Rs.200.00 spent on your SBI Credit Card.", "SMS from SBI Cards and Payment Services Limited"))
        assertNull(parse("HDFC Bank", "Your EMI of Rs 5,432 has been debited on 05/10/2026"))
        assertNull(parse("Swiggy", "Your order is due in 10 minutes"))
    }
}
