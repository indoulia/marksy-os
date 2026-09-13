package com.marksy.os.gateway

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SecureCredentialStoreTest {
    private lateinit var store: SecureCredentialStore

    @Before
    fun setUp() {
        store = SecureCredentialStore(InstrumentationRegistry.getInstrumentation().targetContext)
        store.clearIntegrationKey()
    }

    @After
    fun tearDown() {
        store.clearIntegrationKey()
    }

    @Test
    fun missingCredentialIsUnconfigured() {
        assertNull(store.getIntegrationKey())
    }

    @Test
    fun credentialRoundTripsThroughKeystore() {
        store.setIntegrationKey("test-integration-key")
        assertEquals("test-integration-key", store.getIntegrationKey())
    }

    @Test
    fun clearCredentialRemovesIt() {
        store.setIntegrationKey("test-integration-key")
        store.clearIntegrationKey()
        assertNull(store.getIntegrationKey())
    }

    @Test
    fun whitespaceIsTrimmedOnWrite() {
        store.setIntegrationKey("  test-integration-key  ")
        assertEquals("test-integration-key", store.getIntegrationKey())
    }
}
