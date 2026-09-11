package com.marksy.os.notification

import org.junit.Assert.assertEquals
import org.junit.Test

class SourceRegistryTest {
    @Test fun whatsappBusinessHasDistinctDisplayName() {
        assertEquals("WhatsApp Business", SourceRegistry.displayName("com.whatsapp.w4b"))
    }

    @Test fun knownTradingSourceUsesFriendlyName() {
        assertEquals("Upstox", SourceRegistry.displayName("com.upstox.pro"))
    }

    @Test fun unknownSourceRemainsReadable() {
        assertEquals("example", SourceRegistry.displayName("com.example"))
    }
}
