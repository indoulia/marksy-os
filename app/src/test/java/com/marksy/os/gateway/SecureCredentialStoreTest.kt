package com.marksy.os.gateway

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SecureCredentialStoreTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun baseUrlRoundTripsAsPlaintext() {
        val store = SecureCredentialStore(context)
        assertNull(store.getBaseUrl())

        store.setBaseUrl("https://api.indoulia.com/api/v1")

        assertEquals("https://api.indoulia.com/api/v1", store.getBaseUrl())
    }

    @Test
    fun purgeLegacyCredentialsRemovesOldIntegrationAndMarketKeyEntries() {
        val prefs = context.getSharedPreferences("secure_credentials", Context.MODE_PRIVATE)
        prefs.edit()
            .putString("marksy_integration_key", "old-ciphertext")
            .putString("marksy_integration_key_iv", "old-iv")
            .putString("marksy_market_api_key", "old-market-ciphertext")
            .putString("marksy_market_api_key_iv", "old-market-iv")
            .apply()

        SecureCredentialStore.purgeLegacyCredentials(context)

        assertFalse(prefs.contains("marksy_integration_key"))
        assertFalse(prefs.contains("marksy_integration_key_iv"))
        assertFalse(prefs.contains("marksy_market_api_key"))
        assertFalse(prefs.contains("marksy_market_api_key_iv"))
    }

    @Test
    fun purgeLegacyCredentialsIsSafeWhenNothingToPurge() {
        SecureCredentialStore.purgeLegacyCredentials(context)
    }
}
