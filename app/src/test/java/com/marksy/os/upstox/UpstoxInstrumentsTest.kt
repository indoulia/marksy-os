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

    // Regression: CROPSTER AGRO (BSE-only, called by name) had no live price.
    @Test
    fun bseOnlyEquityResolvesBySymbolOrCompanyNameAndNseWins() {
        val nse = """[{"segment":"NSE_EQ","name":"RELIANCE INDUSTRIES LTD","instrument_key":"NSE_EQ|INE002A01018","trading_symbol":"RELIANCE"}]"""
        val bse = """
            [
              {"segment":"BSE_EQ","name":"CROPSTER AGRO LIMITED","instrument_key":"BSE_EQ|INE293E01031","trading_symbol":"CROPSTER"},
              {"segment":"BSE_EQ","name":"RELIANCE INDUSTRIES LTD","instrument_key":"BSE_EQ|INE002A01018","trading_symbol":"RELIANCE"},
              {"segment":"BSE_FO","name":"SENSEX","instrument_key":"BSE_FO|1","trading_symbol":"SENSEX 30 SEP FUT"}
            ]
        """.trimIndent()
        val map = UpstoxInstruments.parse(bse.byteInputStream(), UpstoxInstruments.parse(nse.byteInputStream()))
        assertEquals("BSE_EQ|INE293E01031", UpstoxInstruments.resolve(map, "CROPSTER"))
        assertEquals("BSE_EQ|INE293E01031", UpstoxInstruments.resolve(map, "CROPSTER AGRO"))
        assertEquals("NSE_EQ|INE002A01018", UpstoxInstruments.resolve(map, "RELIANCE"))
        assertNull(map["SENSEX 30 SEP FUT"])
        assertEquals(listOf("CROPSTER"), UpstoxInstruments.suggest(map.keys, "CROP", 8))
    }

    @Test
    fun suggestsPrefixMatchesFirstThenContainsIgnoringSpacesAndCase() {
        val symbols = listOf("TATAMOTORS", "TATASTEEL", "TATA", "RELIANCE", "MOTHERSON", "AUTOTATA")
        assertEquals(listOf("TATA", "TATASTEEL", "TATAMOTORS", "AUTOTATA"), UpstoxInstruments.suggest(symbols, "tat", 8))
        assertEquals(listOf("TATAMOTORS"), UpstoxInstruments.suggest(symbols, "tata mot", 8))
        assertEquals(listOf("TATA", "TATASTEEL"), UpstoxInstruments.suggest(symbols, "TATA", 2))
    }
}
