package com.marksy.os.upstox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UpstoxQrImportTest {
    private val full = """{"type":"marksy-upstox","v":1,"api_key":"key-123","api_secret":"secret-xyz","redirect_uri":"http://127.0.0.1/marksy-upstox","analytics_token":"aaa.bbb.ccc","sandbox_token":"ddd.eee.fff"}"""

    private fun ok(text: String) = UpstoxQrImport.parse(text) as UpstoxQrImport.Ok
    private fun refused(text: String) = (UpstoxQrImport.parse(text) as UpstoxQrImport.Rejected).message
    private fun with(body: String) = """{"type":"marksy-upstox","v":1,$body}"""

    @Test
    fun aFullCodeFillsEveryField() {
        assertEquals(
            mapOf(
                UpstoxField.API_KEY to "key-123", UpstoxField.API_SECRET to "secret-xyz",
                UpstoxField.REDIRECT to "http://127.0.0.1/marksy-upstox",
                UpstoxField.ANALYTICS_TOKEN to "aaa.bbb.ccc", UpstoxField.SANDBOX_TOKEN to "ddd.eee.fff"
            ),
            ok(full).fields
        )
    }

    @Test
    fun aPartialCodeFillsOnlyWhatItCarriesTrimmed() {
        assertEquals(mapOf(UpstoxField.SANDBOX_TOKEN to "ddd.eee.fff"), ok(with(""""sandbox_token":" ddd.eee.fff """")).fields)
    }

    @Test
    fun surroundingWhitespaceAndAByteOrderMarkAreFine() {
        assertEquals(5, ok("\uFEFF\n$full\n").fields.size)
    }

    @Test
    fun otherCodesAreNotOurs() {
        listOf(
            """{"integrationKey":"abc","baseUrl":"https://example.com"}""",
            "https://example.com/login",
            "[1,2]",
            """{"type":"something-else","v":1,"api_key":"k"}""",
            """{"v":1,"api_key":"k"}"""
        ).forEach { assertEquals(it, "This isn't a Marksy Upstox code.", refused(it)) }
    }

    @Test
    fun brokenJsonIsRefused() {
        listOf("""{"type":"marksy-upstox",""", """{"type":"marksy-upstox","v":1,"api_key":"k"} {}""", """{'type':'marksy-upstox'}""")
            .forEach { assertEquals(it, "This QR isn't valid JSON.", refused(it)) }
    }

    @Test
    fun aRepeatedFieldIsRefused() {
        assertEquals("\"api_key\" appears twice.", refused(with(""""api_key":"a","api_key":"b"""")))
    }

    @Test
    fun onlyVersionOneIsAccepted() {
        assertEquals("This code was made for a newer Marksy (version 2). Update the app.", refused("""{"type":"marksy-upstox","v":2,"api_key":"k"}"""))
        assertEquals("This code's version isn't supported.", refused("""{"type":"marksy-upstox","v":0,"api_key":"k"}"""))
        listOf("\"1\"", "1.0", "-1", "true").forEach { v ->
            assertEquals(v, "This code has no valid version.", refused("""{"type":"marksy-upstox","v":$v,"api_key":"k"}"""))
        }
        assertEquals("This code has no valid version.", refused("""{"type":"marksy-upstox","api_key":"k"}"""))
    }

    @Test
    fun unknownFieldsAndAccessTokensAreRefused() {
        assertEquals("It has a field Marksy doesn't accept: \"order_url\".", refused(with(""""order_url":"https://api-hft.upstox.com"""")))
        assertEquals("It contains an access token. Marksy only takes that from the Upstox sign-in.", refused(with(""""access_token":"t"""")))
    }

    @Test
    fun valuesMustBeNonEmptyPrintableText() {
        assertEquals("\"sandbox_token\" must be text.", refused(with(""""sandbox_token":123""")))
        assertEquals("\"api_secret\" is empty. Leave a field out to keep the saved value.", refused(with(""""api_secret":"  """")))
        assertEquals("\"api_key\" has spaces or characters Marksy can't use.", refused(with(""""api_key":"a b"""")))
        assertEquals("\"api_key\" is too long.", refused(with(""""api_key":"${"k".repeat(129)}"""")))
    }

    @Test
    fun redirectsMustBeHttpsOrThisPhone() {
        listOf("http://127.0.0.1/marksy-upstox", "http://localhost:8080/cb", "http://[::1]/cb", "https://example.com/cb")
            .forEach { assertTrue(it, UpstoxQrImport.parse(with(""""redirect_uri":"$it"""")) is UpstoxQrImport.Ok) }
        listOf("http://example.com/cb", "ftp://127.0.0.1/cb", "marksy://cb", "javascript:alert(1)")
            .forEach { assertEquals(it, "The redirect URL must be https, or http to 127.0.0.1 on this phone.", refused(with(""""redirect_uri":"$it""""))) }
        listOf("https://user:pw@example.com/cb", "https://example.com/cb#x")
            .forEach { assertEquals(it, "The redirect URL can't have a user name or a #fragment.", refused(with(""""redirect_uri":"$it""""))) }
    }

    @Test
    fun theSameTokenTwiceAndEmptyCodesAreRefused() {
        assertEquals("The same token is in both token fields.", refused(with(""""analytics_token":"t.t.t","sandbox_token":"t.t.t"""")))
        assertEquals("This code has nothing to import.", refused("""{"type":"marksy-upstox","v":1}"""))
    }

    @Test
    fun oversizedCodesAreRefused() {
        assertEquals("The code is over 4 KB.", refused(with(""""api_key":"${"k".repeat(4100)}"""")))
    }

    @Test
    fun noResultRevealsAValue() {
        assertFalse(ok(full).toString().contains("secret-xyz"))
        assertFalse(refused(with(""""api_secret":"hunter 2"""")).contains("hunter"))
    }
}
