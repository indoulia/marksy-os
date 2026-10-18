package com.marksy.os.upstox

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.marksy.os.gateway.TestAndroidKeyStoreProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.security.Security

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UpstoxSettingsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val now = 1_790_000_000_000L
    private val oauth get() = UpstoxOAuthStore(context)
    private val analytics get() = UpstoxTokenStore(context)

    companion object {
        @BeforeClass
        @JvmStatic
        fun setupAndroidKeyStore() {
            if (Security.getProvider("AndroidKeyStore") == null) Security.addProvider(TestAndroidKeyStoreProvider())
        }
    }

    @Before
    fun reset() {
        oauth.disconnect()
        analytics.clear()
    }

    private fun draft() = UpstoxDraft(UpstoxSaved.load(oauth, analytics))
    private fun qr(vararg fields: Pair<UpstoxField, String>) = UpstoxQrImport.Ok(fields.toMap())
    private fun saver(check: TokenCheck = TokenCheck.OK) = UpstoxSettingsSaver(oauth, analytics) { check }

    @Test
    fun aFreshFormIsCleanAndNeverLoadsTheSecret() {
        oauth.saveCredentials("key-1", "secret-1", UpstoxOAuth.DEFAULT_REDIRECT)
        val d = draft()
        assertFalse(d.dirty)
        assertEquals("key-1", d.text(UpstoxField.API_KEY))
        assertEquals("", d.text(UpstoxField.API_SECRET))
        assertTrue(d.saved.hasSecret)
    }

    @Test
    fun anImportFillsOnlyItsFieldsAndATypedEditDropsTheQrMark() {
        val d = draft().edit(UpstoxField.ANALYTICS_TOKEN, "typed.a.b")
            .apply(qr(UpstoxField.API_KEY to "key-2", UpstoxField.SANDBOX_TOKEN to "s.s.s"))
        assertEquals(setOf(UpstoxField.API_KEY, UpstoxField.SANDBOX_TOKEN), d.fromQr)
        assertEquals("typed.a.b", d.text(UpstoxField.ANALYTICS_TOKEN))
        assertEquals(setOf(UpstoxField.SANDBOX_TOKEN), d.edit(UpstoxField.API_KEY, "key-3").fromQr)
    }

    @Test
    fun aSecondImportReplacesOnlyTheFieldsItCarries() {
        val d = draft().apply(qr(UpstoxField.API_KEY to "key-2", UpstoxField.SANDBOX_TOKEN to "s.s.s"))
            .edit(UpstoxField.SANDBOX_TOKEN, "typed.s.s")
            .apply(qr(UpstoxField.API_KEY to "key-9"))
        assertEquals("key-9", d.text(UpstoxField.API_KEY))
        assertEquals("typed.s.s", d.text(UpstoxField.SANDBOX_TOKEN))
    }

    @Test
    fun discardReturnsToWhatIsSaved() {
        oauth.saveCredentials("key-1", "secret-1", UpstoxOAuth.DEFAULT_REDIRECT)
        val d = draft().apply(qr(UpstoxField.API_KEY to "key-2")).discard()
        assertFalse(d.dirty)
        assertEquals("key-1", d.text(UpstoxField.API_KEY))
        assertTrue(d.fromQr.isEmpty())
    }

    @Test
    fun problemsBlockSave() {
        assertEquals("Add the API key too.", draft().edit(UpstoxField.API_SECRET, "secret-1").problem())
        assertEquals("Add the API secret too.", draft().edit(UpstoxField.API_KEY, "key-1").problem())
        assertEquals(
            "The redirect URL must be https, or http to 127.0.0.1 on this phone.",
            draft().edit(UpstoxField.API_KEY, "k").edit(UpstoxField.API_SECRET, "s").edit(UpstoxField.REDIRECT, "http://example.com/cb").problem()
        )
        assertEquals("API key has spaces or characters Marksy can't use.", draft().edit(UpstoxField.API_KEY, "a b").problem())
        assertEquals(
            "The same token is in both token fields.",
            draft().edit(UpstoxField.ANALYTICS_TOKEN, "t.t.t").edit(UpstoxField.SANDBOX_TOKEN, "t.t.t").problem()
        )
        assertNull(draft().edit(UpstoxField.API_KEY, "k").edit(UpstoxField.API_SECRET, "s").problem())
    }

    @Test
    fun saveWritesOnlyWhatChanged() {
        runBlocking {
            oauth.saveCredentials("key-1", "secret-1", UpstoxOAuth.DEFAULT_REDIRECT)
            oauth.saveToken("daily", now)
            val result = saver().save(draft().edit(UpstoxField.SANDBOX_TOKEN, "opaque-sandbox"), now)
            assertEquals(setOf(UpstoxField.SANDBOX_TOKEN), result.saved)
            assertEquals("daily", oauth.accessToken(now))
            assertEquals(now + UpstoxJwt.SANDBOX_VALIDITY_MS, oauth.sandboxExpiresAt())
            assertNull(result.analytics)
            assertFalse(result.signInDropped)
        }
    }

    @Test
    fun aRejectedAnalyticsTokenIsNotSavedButTheRestIs() {
        runBlocking {
            val d = draft().apply(qr(UpstoxField.API_KEY to "key-2", UpstoxField.API_SECRET to "secret-2", UpstoxField.ANALYTICS_TOKEN to "bad.a.b"))
            val result = saver(TokenCheck.REJECTED).save(d, now)
            assertNull(analytics.getToken())
            assertEquals("key-2", oauth.credentials()?.apiKey)
            assertEquals(TokenCheck.REJECTED, result.analytics)
            assertFalse(UpstoxField.ANALYTICS_TOKEN in result.saved)
        }
    }

    @Test
    fun anUnreachableCheckStillSavesTheAnalyticsToken() {
        runBlocking {
            val result = saver(TokenCheck.UNREACHABLE).save(draft().edit(UpstoxField.ANALYTICS_TOKEN, "good.a.b"), now)
            assertEquals("good.a.b", analytics.getToken())
            assertEquals(TokenCheck.UNREACHABLE, result.analytics)
        }
    }

    @Test
    fun aBlankSecretKeepsTheSavedOne() {
        runBlocking {
            oauth.saveCredentials("key-1", "secret-1", UpstoxOAuth.DEFAULT_REDIRECT)
            saver().save(draft().edit(UpstoxField.REDIRECT, "https://example.com/cb"), now)
            assertEquals(UpstoxOAuth.Credentials("key-1", "secret-1", "https://example.com/cb"), oauth.credentials())
        }
    }

    @Test
    fun aNewKeyDropsTodaysSignInAndSaysSo() {
        runBlocking {
            oauth.saveCredentials("key-1", "secret-1", UpstoxOAuth.DEFAULT_REDIRECT)
            oauth.saveToken("daily", now)
            val result = saver().save(draft().edit(UpstoxField.API_KEY, "key-2"), now)
            assertTrue(result.signInDropped)
            assertNull(oauth.accessToken(now))
        }
    }
}
