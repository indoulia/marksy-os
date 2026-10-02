package com.marksy.os.upstox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException

class UpstoxHoldingsTest {
    // Verbatim from https://upstox.com/developer/api-documentation/get-holdings/
    private val sample = """{"status":"success","data":[{"isin":"INE528G01035","cnc_used_quantity":0,"collateral_type":"WC","company_name":"YES BANK LTD.","haircut":0.2,"product":"D","quantity":36,"trading_symbol":"YESBANK","tradingsymbol":"YESBANK","last_price":17.05,"close_price":17.05,"pnl":-61.2,"day_change":0,"day_change_percentage":0,"instrument_token":"NSE_EQ|INE528G01035","average_price":18.75,"collateral_quantity":0,"collateral_update_quantity":0,"t1_quantity":0,"exchange":"NSE"}]}"""

    @Test fun documentedSampleParses() {
        assertEquals(
            listOf(UpstoxHolding("INE528G01035", "YES BANK LTD.", "YESBANK", "NSE_EQ|INE528G01035", "NSE", 36, 18.75, 17.05, 17.05)),
            UpstoxHoldings.parse(sample)
        )
    }

    @Test fun emptyAccountIsAnEmptyList() {
        assertEquals(emptyList<UpstoxHolding>(), UpstoxHoldings.parse("""{"status":"success","data":[]}"""))
    }

    @Test fun upstoxErrorsAndGarbageThrowIOException() {
        val e = assertThrows(IOException::class.java) {
            UpstoxHoldings.parse("""{"status":"error","errors":[{"errorCode":"UDAPI100050","message":"Invalid token used to access API"}]}""")
        }
        assertEquals("Upstox: Invalid token used to access API", e.message)
        assertThrows(IOException::class.java) { UpstoxHoldings.parse("not json") }
    }
}
