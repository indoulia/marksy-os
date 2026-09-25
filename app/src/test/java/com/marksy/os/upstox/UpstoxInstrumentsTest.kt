package com.marksy.os.upstox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UpstoxInstrumentsTest {
    @Test
    fun keepsEquitiesBySymbolAndIndicesByNameIgnoringOtherSegments() {
        val json = """
            [
              {"segment":"NSE_EQ","name":"NTPC GREEN ENERGY LIMITED","exchange":"NSE","isin":"INE0ONG01011","instrument_type":"EQ","instrument_key":"NSE_EQ|INE0ONG01011","lot_size":1,"trading_symbol":"NTPCGREEN"},
              {"segment":"NSE_INDEX","name":"Nifty Ind Defence","exchange":"NSE","instrument_type":"INDEX","instrument_key":"NSE_INDEX|Nifty Ind Defence","trading_symbol":"NIFTY IND DEFENCE"},
              {"segment":"NSE_FO","name":"NIFTY","instrument_key":"NSE_FO|12345","trading_symbol":"NIFTY 30 SEP 25000 CE","expiry":null}
            ]
        """.trimIndent()

        val map = UpstoxInstruments.parse(json.byteInputStream())

        assertEquals("NSE_EQ|INE0ONG01011", map["NTPCGREEN"])
        assertEquals("NSE_INDEX|Nifty Ind Defence", map["NIFTY IND DEFENCE"])
        assertNull(map["NIFTY 30 SEP 25000 CE"])
    }

    @Test
    fun suggestsPrefixMatchesFirstThenContainsIgnoringSpacesAndCase() {
        val symbols = listOf("TATAMOTORS", "TATASTEEL", "TATA", "RELIANCE", "MOTHERSON", "AUTOTATA")
        assertEquals(listOf("TATA", "TATASTEEL", "TATAMOTORS", "AUTOTATA"), UpstoxInstruments.suggest(symbols, "tat", 8))
        assertEquals(listOf("TATAMOTORS"), UpstoxInstruments.suggest(symbols, "tata mot", 8))
        assertEquals(listOf("TATA", "TATASTEEL"), UpstoxInstruments.suggest(symbols, "TATA", 2))
    }
}
