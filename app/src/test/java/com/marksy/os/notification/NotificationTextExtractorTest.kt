package com.marksy.os.notification

import android.os.Bundle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationTextExtractorTest {
    @Test
    fun combinesTextBigTextAndLinesWithoutDuplicates() {
        val extras = Bundle().apply {
            putCharSequence("android.text", "Hello")
            putCharSequence("android.bigText", "Hello")
            putCharSequenceArray("android.textLines", arrayOf("Hello", "World"))
        }

        assertEquals("Hello\nWorld", NotificationTextExtractor.extract(extras))
    }

    @Test
    fun ignoresBlankValues() {
        val extras = Bundle().apply {
            putCharSequence("android.text", "  ")
            putCharSequence("android.bigText", "Important")
            putCharSequenceArray("android.textLines", arrayOf("", "  "))
        }

        assertEquals("Important", NotificationTextExtractor.extract(extras))
    }

    @Test
    fun truncatesLongNotificationBody() {
        val extras = Bundle().apply {
            putCharSequence("android.text", "x".repeat(NotificationTextExtractor.MAX_BODY_LENGTH + 500))
        }

        val result = NotificationTextExtractor.extract(extras)
        assertEquals(NotificationTextExtractor.MAX_BODY_LENGTH, result.length)
        assertTrue(result.all { it == 'x' })
    }
}
