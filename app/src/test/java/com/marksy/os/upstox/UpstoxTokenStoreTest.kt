package com.marksy.os.upstox

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.marksy.os.gateway.AuthSessionStore
import com.marksy.os.gateway.TestAndroidKeyStoreProvider
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
class UpstoxTokenStoreTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    companion object {
        @BeforeClass
        @JvmStatic
        fun setupAndroidKeyStore() {
            if (Security.getProvider("AndroidKeyStore") == null) Security.addProvider(TestAndroidKeyStoreProvider())
        }
    }

    @Before
    fun reset() {
        UpstoxTokenStore(context).clear()
        AuthSessionStore(context).clearSession()
    }

    @Test
    fun tokenRoundTripsThroughEncryptedStorageAndIsNotStoredInPlaintext() {
        UpstoxTokenStore(context).save("  eyJ.analytics.token  ")

        assertEquals("eyJ.analytics.token", UpstoxTokenStore(context).getToken())
        assertTrue(UpstoxTokenStore(context).hasToken())
        val raw = context.getSharedPreferences("upstox_token", Context.MODE_PRIVATE).all.values.joinToString()
        assertFalse(raw.contains("eyJ.analytics.token"))
    }

    @Test
    fun clearRemovesToken() {
        val store = UpstoxTokenStore(context)
        store.save("token")
        store.clear()

        assertNull(UpstoxTokenStore(context).getToken())
        assertFalse(store.hasToken())
    }

    @Test
    fun isIndependentOfTheMarksySession() {
        UpstoxTokenStore(context).save("upstox")
        AuthSessionStore(context).clearSession()

        assertEquals("upstox", UpstoxTokenStore(context).getToken())
    }
}
