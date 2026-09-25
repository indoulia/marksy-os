package com.marksy.os.upstox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UpstoxModelsTest {
    @Test
    fun parsesLtpResponseKeyedByInstrumentToken() {
        val quotes = UpstoxLtp.parseResponse(
            """
            {"status":"success","data":{
              "NSE_INDEX:Nifty 50":{"last_price":23063.1,"instrument_token":"NSE_INDEX|Nifty 50","ltq":0,"volume":0,"cp":23447.6},
              "NSE_INDEX:Nifty Bank":{"last_price":55438.5,"instrument_token":"NSE_INDEX|Nifty Bank","ltq":0,"volume":0,"cp":56545.0}
            }}
            """.trimIndent()
        )

        val nifty = quotes.getValue(UpstoxIndices.NIFTY_50)
        assertEquals(23063.1, nifty.lastPrice, 0.001)
        assertEquals(-1.64, nifty.changePct!!, 0.01)
        assertEquals(55438.5, quotes.getValue(UpstoxIndices.BANK_NIFTY).lastPrice, 0.001)
    }

    @Test
    fun missingPreviousCloseLeavesChangeUnknownRatherThanZero() {
        val quotes = UpstoxLtp.parseResponse("""{"status":"success","data":{"NSE_INDEX:Nifty 50":{"last_price":23063.1,"instrument_token":"NSE_INDEX|Nifty 50"}}}""")

        assertNull(quotes.getValue(UpstoxIndices.NIFTY_50).changePct)
    }

    @Test
    fun fallsBackToResponseKeyWhenInstrumentTokenIsAbsent() {
        val quotes = UpstoxLtp.parseResponse("""{"status":"success","data":{"NSE_INDEX:Nifty 50":{"last_price":1.0}}}""")

        assertEquals(1.0, quotes.getValue(UpstoxIndices.NIFTY_50).lastPrice, 0.0)
    }

    @Test
    fun errorEnvelopeSurfacesUpstoxMessage() {
        val error = assertThrows(IOException::class.java) {
            UpstoxLtp.parseResponse("""{"status":"error","errors":[{"errorCode":"UDAPI100050","message":"Invalid token used to access API"}]}""")
        }
        assertEquals("Upstox: Invalid token used to access API", error.message)
    }

    @Test
    fun malformedBodyIsAnIoErrorNotACrash() {
        assertThrows(IOException::class.java) { UpstoxLtp.parseResponse("<html>gateway timeout</html>") }
    }
}
