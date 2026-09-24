package com.marksy.os.gateway

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.security.KeyStore
import java.security.Provider
import java.security.Security

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SecureCredentialStoreTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    companion object {
        @BeforeClass
        @JvmStatic
        fun setupAndroidKeyStore() {
            // Register a test Android Keystore provider if not already registered
            if (Security.getProvider("AndroidKeyStore") == null) {
                Security.addProvider(TestAndroidKeyStoreProvider())
            }
        }
    }

    @Test
    fun marketApiKeyRoundTripsThroughEncryptedStorage() {
        val store = SecureCredentialStore(context)
        assertNull(store.getMarketApiKey())

        store.setMarketApiKey("  scoped-key-123  ")

        assertEquals("scoped-key-123", store.getMarketApiKey())
    }

    @Test
    fun clearingMarketApiKeyRemovesIt() {
        val store = SecureCredentialStore(context)
        store.setMarketApiKey("scoped-key-123")

        store.clearMarketApiKey()

        assertNull(store.getMarketApiKey())
    }

    @Test
    fun marketApiKeyIsIndependentOfIntegrationKey() {
        val store = SecureCredentialStore(context)
        store.setIntegrationKey("tips-integration-key")
        store.setMarketApiKey("scoped-market-key")

        assertEquals("tips-integration-key", store.getIntegrationKey())
        assertEquals("scoped-market-key", store.getMarketApiKey())

        store.clearMarketApiKey()

        assertEquals("tips-integration-key", store.getIntegrationKey())
        assertNull(store.getMarketApiKey())
    }
}
