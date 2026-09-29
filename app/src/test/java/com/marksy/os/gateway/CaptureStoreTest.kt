package com.marksy.os.gateway

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CaptureStoreTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    // Fix round 1, defence in depth: deviceSalt() must persist with commit(), not apply(), so a retry after
    // process death still finds the same salt on disk. A JVM/Robolectric test can't observe process death,
    // but it can confirm the salt round-trips through the real SharedPreferences file, not just in memory.
    @Test
    fun deviceSaltIsStableAcrossInstancesAndPersistedToSharedPreferences() {
        val salt = CaptureStore(context).deviceSalt()

        assertEquals(salt, CaptureStore(context).deviceSalt())
        val prefs = context.getSharedPreferences("tip_capture", Context.MODE_PRIVATE)
        assertEquals(salt, prefs.getString("device_salt", null))
    }
}
