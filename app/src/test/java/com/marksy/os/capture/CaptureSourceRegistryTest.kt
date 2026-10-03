package com.marksy.os.capture

import com.marksy.os.notification.CaptureMedium
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureSourceRegistryTest {
    private var captureList: Set<String>? = setOf("com.research.app", "com.zerodha.kite3", "com.whatsapp")
    private val registry = CaptureSourceRegistry(capturePackages = { captureList }, displayName = { "App $it" })

    @Test
    fun allowListedSources() {
        assertEquals(
            CaptureSource("com.research.app", "App com.research.app", CaptureMedium.APP_NOTIFICATION, offersScreenCapture = true, deliverable = true),
            registry.resolve(" Com.Research.App ")
        )
        // On-device broker hint only: screen capture is offered, but only the server list makes it deliverable.
        val broker = registry.resolve("com.nextbillion.groww")!!
        assertTrue(broker.offersScreenCapture)
        assertFalse(broker.deliverable)
        assertTrue(registry.allowListed().map { it.packageName }.containsAll(listOf("com.research.app", "com.zerodha.kite3", "com.nextbillion.groww")))
    }

    @Test
    fun unsupportedSources() {
        assertNull(registry.resolve("com.example.game"))
        assertNull(registry.resolve(""))
        assertNull(registry.resolve("  "))
        assertNull(registry.resolve(null))
    }

    // Chats and SMS can never prove a group identity, so their screenshots stay local even when listed.
    @Test
    fun chatPackageIsNeverDeliverable() {
        val chat = registry.resolve("com.whatsapp")!!
        assertEquals(CaptureMedium.WHATSAPP, chat.medium)
        assertFalse(chat.deliverable)
        assertFalse(chat.offersScreenCapture)
        assertFalse(registry.resolve("com.google.android.apps.messaging")!!.deliverable)
        assertTrue(registry.allowListed().none { it.medium != CaptureMedium.APP_NOTIFICATION })
    }

    @Test
    fun captureListNotYetFetched() {
        captureList = null
        assertFalse(registry.captureListKnown())
        assertNull(registry.resolve("com.research.app"))
        val broker = registry.resolve("com.zerodha.kite3")!!
        assertTrue(broker.offersScreenCapture)
        assertFalse(broker.deliverable)
    }
}
