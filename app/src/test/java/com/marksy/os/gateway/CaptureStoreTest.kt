package com.marksy.os.gateway

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
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

    // Finding M2: eviction over the cap (5000) drops the OLDEST name, not an arbitrary one.
    @Test
    fun rememberedSendersAreEvictedOldestFirst() {
        val store = CaptureStore(context)

        store.rememberChatSenders((1..5001).map { "sender$it" })

        val remembered = store.chatSenders()
        assertFalse(remembered.contains("sender1"))
        assertTrue(remembered.contains("sender2"))
        assertTrue(remembered.contains("sender5001"))
    }

    // Finding M2: touching (re-seeing) a name moves it to the end, so it survives an eviction that would
    // otherwise have dropped it as the oldest.
    @Test
    fun reSeeingAnOldNameMovesItToTheEndAndSavesItFromEviction() {
        val store = CaptureStore(context)
        store.rememberChatSenders(listOf("Old"))
        store.rememberChatSenders((1..4999).map { "filler$it" }) // total 5000, at the cap, no eviction yet

        store.rememberChatSenders(listOf("Old")) // re-seen: moves from the front to the end
        store.rememberChatSenders(listOf("NewOne")) // total 5001: evicts the new front-most, "filler1"

        val remembered = store.chatSenders()
        assertTrue(remembered.contains("Old"))
        assertTrue(remembered.contains("NewOne"))
        assertFalse(remembered.contains("filler1"))
    }

    // Finding M2: a pre-fix StringSet is migrated to the ordered-list format on first read.
    @Test
    fun legacyStringSetIsMigratedOnFirstRead() {
        val prefs = context.getSharedPreferences("tip_capture", Context.MODE_PRIVATE)
        prefs.edit().putStringSet("chat_senders", setOf("Rahul", "Amit")).commit()

        val remembered = CaptureStore(context).chatSenders()

        assertEquals(setOf("Rahul", "Amit"), remembered)
        assertNotNull(prefs.getString("chat_senders", null))
    }
}
