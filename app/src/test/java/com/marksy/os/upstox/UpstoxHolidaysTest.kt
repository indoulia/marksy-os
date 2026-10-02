package com.marksy.os.upstox

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class UpstoxHolidaysTest {
    @Test fun keepsOnlyNseTradingHolidays() {
        val body = """{"status":"success","data":[
            {"date":"2026-10-02","description":"Mahatma Gandhi Jayanti","holiday_type":"TRADING_HOLIDAY","closed_exchanges":["NSE","BSE"]},
            {"date":"2026-10-03","description":"Settlement only","holiday_type":"SETTLEMENT_HOLIDAY","closed_exchanges":["NSE"]},
            {"date":"2026-11-01","description":"MCX only","holiday_type":"TRADING_HOLIDAY","closed_exchanges":["MCX"]}]}"""
        assertEquals(mapOf(LocalDate.of(2026, 10, 2) to "Mahatma Gandhi Jayanti"), UpstoxHolidays.parse(body))
    }
}
