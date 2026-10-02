package com.marksy.os.alerts

import com.marksy.os.market.IpoListItemDto
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IpoBandAlertRulesTest {
    private fun row(id: String, band: Boolean) = IpoListItemDto.parse(JSONObject("""{"id":"$id","companyName":"Co $id","stage":"UPCOMING","terms":{""" +
        (if (band) """"priceBand":{"state":"AVAILABLE","value":{"lower":412,"upper":434}},"lotSize":{"state":"AVAILABLE","value":34}""" else "") + "}}"))

    @Test fun speaksOnlyOnceTheBandIsOut() {
        assertEquals("₹412–434 a share · lot of 34", IpoBandAlertRules.message(row("a", band = true)))
        assertNull(IpoBandAlertRules.message(row("b", band = false)))
    }

    @Test fun oneListFetchDecidesWhoToTellAndWhoToStopWatching() {
        val check = IpoBandAlertRules.resolve(mapOf("a" to "Co a", "b" to "Co b", "c" to "Co c"), listOf(row("a", band = true), row("b", band = false)))
        assertEquals(mapOf("a" to "₹412–434 a share · lot of 34"), check.notify)
        assertEquals(setOf("c"), check.drop)
    }
}
