package com.marksy.os.gateway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GatewayQrParserTest {

    @Test fun parsesJsonWithBothFields() {
        val r = GatewayQrParser.parse("""{"integrationKey":"mk_live_abc","baseUrl":"https://marksy.indoulia.com/api/v1"}""")
        assertEquals("mk_live_abc", r.integrationKey)
        assertEquals("https://marksy.indoulia.com/api/v1", r.baseUrl)
    }

    @Test fun trimsWhitespaceAroundValuesAndPayload() {
        val r = GatewayQrParser.parse("""  {"integrationKey":"  mk_live_abc  ","baseUrl":" https://x.example/api "}  """)
        assertEquals("mk_live_abc", r.integrationKey)
        assertEquals("https://x.example/api", r.baseUrl)
    }

    @Test fun jsonWithOnlyKeyLeavesBaseUrlNull() {
        val r = GatewayQrParser.parse("""{"integrationKey":"mk_live_abc"}""")
        assertEquals("mk_live_abc", r.integrationKey)
        assertNull(r.baseUrl)
    }

    @Test fun acceptsCommonFieldAliases() {
        val r = GatewayQrParser.parse("""{"key":"mk_live_abc","url":"https://x.example/api"}""")
        assertEquals("mk_live_abc", r.integrationKey)
        assertEquals("https://x.example/api", r.baseUrl)
    }

    @Test fun blankJsonValuesBecomeNull() {
        val r = GatewayQrParser.parse("""{"integrationKey":"   ","baseUrl":""}""")
        assertNull(r.integrationKey)
        assertNull(r.baseUrl)
    }

    @Test fun nonJsonStringIsTreatedAsIntegrationKey() {
        val r = GatewayQrParser.parse("mk_live_rawkey")
        assertEquals("mk_live_rawkey", r.integrationKey)
        assertNull(r.baseUrl)
    }

    @Test fun malformedJsonFallsBackToRawAsKey() {
        val r = GatewayQrParser.parse("""{"integrationKey":""")
        assertEquals("""{"integrationKey":""", r.integrationKey)
        assertNull(r.baseUrl)
    }

    @Test fun blankOrNullInputYieldsEmptyPayload() {
        val r = GatewayQrParser.parse("   ")
        assertNull(r.integrationKey)
        assertNull(r.baseUrl)
        assertEquals(false, r.hasAny)
        val n = GatewayQrParser.parse(null)
        assertNull(n.integrationKey)
        assertNull(n.baseUrl)
    }

    @Test fun hasAnyReflectsPresence() {
        assertEquals(true, GatewayQrParser.parse("mk_live_abc").hasAny)
        assertEquals(true, GatewayQrParser.parse("""{"baseUrl":"https://x.example/api"}""").hasAny)
    }
}
