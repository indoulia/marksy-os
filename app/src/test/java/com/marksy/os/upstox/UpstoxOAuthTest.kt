package com.marksy.os.upstox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.time.LocalDateTime
import java.time.ZoneId

class UpstoxOAuthTest {
    private val creds = UpstoxOAuth.Credentials("my-key", "s3c&ret=1", "http://127.0.0.1/marksy-upstox")
    private fun ist(at: String) = LocalDateTime.parse(at).atZone(ZoneId.of("Asia/Kolkata")).toInstant().toEpochMilli()

    @Test fun authorizeUrlCarriesKeyRedirectAndStateButCredentialsNeverPrint() {
        assertEquals(
            "https://api.upstox.com/v2/login/authorization/dialog?response_type=code&client_id=my-key&redirect_uri=http%3A%2F%2F127.0.0.1%2Fmarksy-upstox&state=abc123",
            UpstoxOAuth.authorizeUrl(creds, "abc123")
        )
        assertFalse(creds.toString().contains("s3c"))
        assertFalse(creds.toString().contains("my-key"))
    }

    @Test fun redirectMatchesOnlyTheRegisteredAddress() {
        val r = creds.redirectUri
        assertTrue(UpstoxOAuth.isRedirect("http://127.0.0.1/marksy-upstox?code=mk404x&state=XX56849", r))
        assertTrue(UpstoxOAuth.isRedirect("http://127.0.0.1:80/marksy-upstox/?code=x", r))
        assertFalse(UpstoxOAuth.isRedirect("https://127.0.0.1/marksy-upstox?code=x", r))
        assertFalse(UpstoxOAuth.isRedirect("http://127.0.0.1:8080/marksy-upstox?code=x", r))
        assertFalse(UpstoxOAuth.isRedirect("http://evil.example/marksy-upstox?code=x", r))
        assertFalse(UpstoxOAuth.isRedirect("http://127.0.0.1/other?code=x", r))
        assertFalse(UpstoxOAuth.isRedirect("not a url", r))
    }

    @Test fun onlyHttpsUpstoxPagesMayOpen() {
        assertTrue(UpstoxOAuth.isUpstoxPage("https://login.upstox.com/login/v2/oauth/authorize?x=1"))
        assertTrue(UpstoxOAuth.isUpstoxPage("https://api.upstox.com/v2/login/authorization/dialog"))
        assertFalse(UpstoxOAuth.isUpstoxPage("http://login.upstox.com/"))
        assertFalse(UpstoxOAuth.isUpstoxPage("https://upstox.com.evil.example/"))
        assertFalse(UpstoxOAuth.isUpstoxPage("https://notupstox.com/"))
    }

    @Test fun redirectYieldsTheCodeOnlyWhenStateMatches() {
        val url = "http://127.0.0.1/marksy-upstox?code=mk404x&state=XX56849"
        assertEquals(UpstoxOAuth.Redirect.Code("mk404x"), UpstoxOAuth.parseRedirect(url, "XX56849"))
        assertTrue(UpstoxOAuth.parseRedirect(url, "other") is UpstoxOAuth.Redirect.Failed)
        assertTrue(UpstoxOAuth.parseRedirect("http://127.0.0.1/marksy-upstox?state=XX56849", "XX56849") is UpstoxOAuth.Redirect.Failed)
        assertTrue(UpstoxOAuth.parseRedirect("not a url", "XX56849") is UpstoxOAuth.Redirect.Failed)
        val denied = UpstoxOAuth.parseRedirect("http://127.0.0.1/marksy-upstox?error=access_denied&state=XX56849", "XX56849")
        assertEquals("Upstox: access_denied", (denied as UpstoxOAuth.Redirect.Failed).message)
    }

    @Test fun tokenRequestIsFormEncoded() {
        assertEquals(
            "code=mk404x&client_id=my-key&client_secret=s3c%26ret%3D1&redirect_uri=http%3A%2F%2F127.0.0.1%2Fmarksy-upstox&grant_type=authorization_code",
            UpstoxOAuth.tokenRequestBody("mk404x", creds)
        )
    }

    @Test fun documentedTokenResponseYieldsTheAccessToken() {
        val body = """{"email":"******","exchanges":["NSE","NFO","BSE","CDS","BFO","BCD"],"products":["D","CO","I"],"broker":"UPSTOX","user_id":"******","user_name":"******","order_types":["MARKET","LIMIT","SL","SL-M"],"user_type":"individual","poa":false,"is_active":true,"access_token":"eyJ0eXAi.token","extended_token":"ext"}"""
        assertEquals("eyJ0eXAi.token", UpstoxOAuth.parseToken(body))
    }

    @Test fun tokenErrorCarriesUpstoxsMessage() {
        val body = """{"status":"error","errors":[{"errorCode":"UDAPI100057","message":"Invalid Auth code","propertyPath":null,"invalidValue":null,"error_code":"UDAPI100057","property_path":null,"invalid_value":null}]}"""
        assertEquals("Upstox: Invalid Auth code", assertThrows(IOException::class.java) { UpstoxOAuth.parseToken(body) }.message)
        assertThrows(IOException::class.java) { UpstoxOAuth.parseToken("<html>") }
    }

    @Test fun tokenLastsUntilTheNext0330Ist() {
        assertEquals(ist("2026-10-03T03:30"), UpstoxOAuth.expiresAt(ist("2026-10-02T09:05")))
        assertEquals(ist("2026-10-02T03:30"), UpstoxOAuth.expiresAt(ist("2026-10-02T02:10")))
        assertEquals(ist("2026-10-03T03:30"), UpstoxOAuth.expiresAt(ist("2026-10-02T03:30")))
    }

    @Test fun stateIs32RandomHexCharacters() {
        val s = UpstoxOAuth.newState()
        assertTrue(s.matches(Regex("[0-9a-f]{32}")))
        assertNotEquals(s, UpstoxOAuth.newState())
    }

    @Test fun urlsWithUnusualQueryCharactersStillParse() {
        assertTrue(UpstoxOAuth.isUpstoxPage("https://login.upstox.com/login?x=a|b{c}"))
        assertTrue(UpstoxOAuth.isRedirect("http://127.0.0.1/marksy-upstox?code=a|b&state=s", "http://127.0.0.1/marksy-upstox"))
        assertEquals(UpstoxOAuth.Redirect.Code("ab|c"), UpstoxOAuth.parseRedirect("http://127.0.0.1/marksy-upstox?code=ab|c&state=s1", "s1"))
    }
}
