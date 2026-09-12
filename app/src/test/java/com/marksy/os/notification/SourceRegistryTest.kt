package com.marksy.os.notification

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    @Test fun knownSourcesAreStableUniqueAndSorted() {
        val sources = SourceRegistry.knownSources()
        assertEquals(sources.sorted(), sources)
        assertEquals(sources.size, sources.distinct().size)
        assertTrue(sources.contains("Upstox"))
        assertTrue(sources.contains("WhatsApp"))
        assertTrue(sources.contains("WhatsApp Business"))
    }

    @Test fun whatsappVariantsAreExplicitlyRecognized() {
        assertTrue(SourceRegistry.isWhatsApp("com.whatsapp"))
        assertTrue(SourceRegistry.isWhatsApp(" COM.WHATSAPP.W4B "))
        assertFalse(SourceRegistry.isWhatsApp("com.example.whatsapp"))
    }

    @Test fun tradingSourcesAreExplicitlyRecognized() {
        assertTrue(SourceRegistry.isTradingSource("com.upstox.pro"))
        assertTrue(SourceRegistry.isTradingSource(" COM.ZERODHA.KITE3 "))
        assertFalse(SourceRegistry.isTradingSource("com.example"))
    }

    @Test fun knownSourceLookupNormalizesPackageName() {
        assertTrue(SourceRegistry.isKnownSource(" COM.UPSTOX.PRO "))
        assertEquals("Upstox", SourceRegistry.displayName(" COM.UPSTOX.PRO "))
    }
}
