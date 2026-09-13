package com.marksy.os.notification

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceRegistryTest {
    @Test
    fun whatsappVariantsAreRecognized() {
        assertTrue(SourceRegistry.isWhatsApp("com.whatsapp"))
        assertTrue(SourceRegistry.isWhatsApp("com.whatsapp.w4b"))
    }

    @Test
    fun whatsappMatchingNormalizesPackageName() {
        assertTrue(SourceRegistry.isWhatsApp("  COM.WHATSAPP  "))
        assertTrue(SourceRegistry.isWhatsApp(" COM.WHATSAPP.W4B "))
    }

    @Test
    fun unrelatedPackagesAreNotWhatsApp() {
        assertFalse(SourceRegistry.isWhatsApp("com.example.app"))
        assertFalse(SourceRegistry.isWhatsApp("com.whatsapp.fake"))
        assertFalse(SourceRegistry.isWhatsApp(""))
    }

    @Test
    fun tradingPackagesAreRecognizedAndUnknownPackagesRejected() {
        assertTrue(SourceRegistry.isTradingSource("com.upstox.pro"))
        assertTrue(SourceRegistry.isTradingSource(" COM.ZERODHA.KITE3 "))
        assertFalse(SourceRegistry.isTradingSource("com.example.trading"))
    }
}
