package com.marksy.os.upstox

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.marksy.os.gateway.TestAndroidKeyStoreProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.security.Security

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UpstoxOAuthStoreTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val issued = 1_790_000_000_000L

    companion object {
        @BeforeClass
        @JvmStatic
        fun setupAndroidKeyStore() {
            if (Security.getProvider("AndroidKeyStore") == null) Security.addProvider(TestAndroidKeyStoreProvider())
        }
    }

    @Before
    fun reset() = UpstoxOAuthStore(context).disconnect()

    @Test
    fun keysTokenAndHoldingsAreEncryptedAtRest() {
        val store = UpstoxOAuthStore(context)
        store.saveCredentials(" key-123 ", "secret-xyz", "http://127.0.0.1/marksy-upstox")
        store.saveToken("token-abc", issued)
        store.saveHoldings("""[{"symbol":"SUZLON"}]""")

        val again = UpstoxOAuthStore(context)
        assertEquals(UpstoxOAuth.Credentials("key-123", "secret-xyz", "http://127.0.0.1/marksy-upstox"), again.credentials())
        assertEquals("token-abc", again.accessToken(issued + 60_000))
        assertEquals("""[{"symbol":"SUZLON"}]""", again.holdings())
        val raw = context.getSharedPreferences("upstox_oauth", Context.MODE_PRIVATE).all.values.joinToString()
        listOf("key-123", "secret-xyz", "token-abc", "SUZLON").forEach { assertFalse(it, raw.contains(it)) }
    }

    @Test
    fun tokenStopsAtTheCutoff() {
        val store = UpstoxOAuthStore(context)
        store.saveToken("token-abc", issued)
        val cutoff = UpstoxOAuth.expiresAt(issued)
        assertEquals("token-abc", store.accessToken(cutoff - 1))
        assertNull(store.accessToken(cutoff))
    }

    @Test
    fun signingOutKeepsTheKeysAndNewKeysDropTheToken() {
        val store = UpstoxOAuthStore(context)
        store.saveCredentials("key", "secret", "")
        store.saveToken("token", issued)
        store.clearToken()
        assertNull(store.accessToken(issued + 1))
        assertNull(store.signedInAt())
        assertEquals(UpstoxOAuth.DEFAULT_REDIRECT, store.credentials()!!.redirectUri)

        store.saveToken("token", issued)
        store.saveCredentials("key2", "secret2", UpstoxOAuth.DEFAULT_REDIRECT)
        assertNull(store.accessToken(issued + 1))
        assertNotNull(store.credentials())
    }

    @Test
    fun disconnectForgetsEverythingButTheAnalyticsToken() {
        UpstoxTokenStore(context).save("analytics")
        val store = UpstoxOAuthStore(context)
        store.saveCredentials("key", "secret", UpstoxOAuth.DEFAULT_REDIRECT)
        store.saveToken("token", issued)
        store.saveHoldings("[]")
        store.saveHidden("{}")

        store.disconnect()

        assertNull(store.credentials())
        assertNull(store.accessToken(issued + 1))
        assertNull(store.holdings())
        assertNull(store.hidden())
        assertEquals("analytics", UpstoxTokenStore(context).getToken())
        UpstoxTokenStore(context).clear()
    }
}
