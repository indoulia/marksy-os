package com.marksy.os.notification

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceRegistryTest {
    @Test
    fun knownSourcesAreStableAndUnique() {
        val sources = SourceRegistry.knownSources()

        assertEquals(sources.sorted(), sources)
        assertEquals(sources.size, sources.distinct().size)
        assertTrue(sources.isNotEmpty())
    }

    @Test
    fun coreTradingSourcesAreRegistered() {
        val sources = SourceRegistry.knownSources()

        assertTrue(sources.contains("Upstox"))
        assertTrue(sources.contains("Zerodha"))
        assertTrue(sources.contains("Groww"))
        assertTrue(sources.contains("Angel One"))
        assertTrue(sources.contains("5paisa"))
        assertTrue(sources.contains("ICICI Direct"))
        assertTrue(sources.contains("ET Money"))
    }

    @Test
    fun whatsappSourcesAreRegisteredSeparately() {
        val sources = SourceRegistry.knownSources()

        assertTrue(sources.contains("WhatsApp"))
        assertTrue(sources.contains("WhatsApp Business"))
    }
}
