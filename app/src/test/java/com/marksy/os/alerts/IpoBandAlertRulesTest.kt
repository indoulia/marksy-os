package com.marksy.os.alerts

import com.marksy.os.market.IpoDetailDto
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IpoBandAlertRulesTest {
    @Test fun speaksOnlyOnceTheBandIsOut() {
        val out = IpoDetailDto.parse(JSONObject("""{"summary":{"id":"kaveri","companyName":"Kaveri Hospitals","terms":{"priceBand":{"state":"AVAILABLE","value":{"lower":412,"upper":434}},"lotSize":{"state":"AVAILABLE","value":34}}}}"""))
        assertEquals("₹412–434 a share · lot of 34", IpoBandAlertRules.message(out))
        assertNull(IpoBandAlertRules.message(IpoDetailDto.parse(JSONObject("""{"summary":{"id":"x","companyName":"X"}}"""))))
    }
}
