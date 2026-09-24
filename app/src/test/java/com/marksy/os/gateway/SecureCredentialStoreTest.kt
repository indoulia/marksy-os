package com.marksy.os.gateway

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
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
}
