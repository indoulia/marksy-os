package com.marksy.os.notification

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WhatsAppSenderWatchlistTest {
    @Test
    fun matchingIsCaseInsensitiveAndWhitespaceNormalized() {
        assertTrue(WhatsAppSenderWatchlist.matches(setOf("  Alice   Sharma "), "alice sharma"))
    }

    @Test
    fun nonMatchingSenderIsRejected() {
        assertFalse(WhatsAppSenderWatchlist.matches(setOf("Alice Sharma"), "Bob Sharma"))
    }

    @Test
    fun blankCandidateIsRejected() {
        assertFalse(WhatsAppSenderWatchlist.matches(setOf("Alice Sharma"), "   "))
    }

    @Test
    fun prefixOrMessageTextDoesNotBecomeAnExactMatch() {
        assertFalse(WhatsAppSenderWatchlist.matches(setOf("Alice Sharma"), "Alice Sharma: hello"))
    }
}
