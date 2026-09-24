package com.marksy.os.gateway

import android.content.Context
import androidx.test.core.app.ApplicationProvider
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
class AuthSessionStoreTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    // AuthSessionStore's in-memory holder is a process-wide singleton (by design --
    // production code needs every AuthSessionStore(context) construction to share
    // one live session), so without this it could leak between @Test methods in
    // this class. clearSession() is the store's own public API, not a test-only hook.
    @Before
    fun resetSession() {
        AuthSessionStore(context).clearSession()
    }

    companion object {
        @BeforeClass
        @JvmStatic
        fun setupAndroidKeyStore() {
            if (Security.getProvider("AndroidKeyStore") == null) {
                Security.addProvider(TestAndroidKeyStoreProvider())
            }
        }
    }

    @Test
    fun rememberedSessionRoundTripsThroughEncryptedStorage() {
        val store = AuthSessionStore(context)
        assertNull(store.getToken())

        store.saveSession("sess_abc123", "prsingh", 1_800_000_000_000L, remember = true)

        assertEquals("sess_abc123", store.getToken())
        assertEquals("prsingh", store.getUserId())
        assertEquals(1_800_000_000_000L, store.getExpiresAtEpochMs())
        assertTrue(store.isRemembered())
    }

    // These two tests check the raw SharedPreferences file directly rather than
    // constructing "a new instance" and reading through it: the in-memory holder
    // is a process-wide singleton (a Kotlin `object`), so within one test method
    // a second `AuthSessionStore(context)` instance shares that same singleton
    // state and would see the token either way -- it does not simulate a new
    // process the way a real app restart would. Reading the preference file
    // directly is what actually proves "written to persistent storage" vs. not,
    // which is the real thing "remember me" needs to guarantee.
    @Test
    fun rememberedSessionIsWrittenToSharedPreferences() {
        AuthSessionStore(context).saveSession("sess_abc123", "prsingh", 1_800_000_000_000L, remember = true)

        val prefs = context.getSharedPreferences("auth_session", Context.MODE_PRIVATE)

        assertTrue(prefs.contains("session_token"))
    }

    @Test
    fun unrememberedSessionIsNeverWrittenToSharedPreferences() {
        AuthSessionStore(context).saveSession("sess_abc123", "prsingh", 1_800_000_000_000L, remember = false)

        val prefs = context.getSharedPreferences("auth_session", Context.MODE_PRIVATE)

        assertFalse(prefs.contains("session_token"))
    }

    @Test
    fun unrememberedSessionIsReadableFromTheSameInstance() {
        val store = AuthSessionStore(context)
        store.saveSession("sess_abc123", "prsingh", 1_800_000_000_000L, remember = false)

        assertEquals("sess_abc123", store.getToken())
        assertFalse(store.isRemembered())
    }

    @Test
    fun clearingSessionRemovesItFromBothStorageForms() {
        val store = AuthSessionStore(context)
        store.saveSession("sess_abc123", "prsingh", 1_800_000_000_000L, remember = true)

        store.clearSession()

        assertNull(store.getToken())
        assertNull(store.getUserId())
        assertNull(store.getExpiresAtEpochMs())
    }
}
