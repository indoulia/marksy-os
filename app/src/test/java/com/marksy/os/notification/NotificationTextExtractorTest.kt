package com.marksy.os.notification

import android.os.Bundle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NotificationTextExtractorTest {
    @Test
    fun extractsAndTrimsNotificationTitle() {
        val extras = Bundle().apply {
            putCharSequence("android.title", "  Order executed  ")
        }

        assertEquals("Order executed", NotificationTextExtractor.extractTitle(extras))
    }

    @Test
    fun truncatesLongNotificationTitle() {
        val extras = Bundle().apply {
            putCharSequence("android.title", "x".repeat(NotificationTextExtractor.MAX_TITLE_LENGTH + 100))
        }

        val result = NotificationTextExtractor.extractTitle(extras)
        assertEquals(NotificationTextExtractor.MAX_TITLE_LENGTH, result.length)
        assertTrue(result.all { it == 'x' })
    }

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
    fun includesSecondaryTextUsedBySomeNotifications() {
        val extras = Bundle().apply {
            putCharSequence("android.text", "Order executed")
            putCharSequence("android.subText", "Upstox")
        }

        assertEquals("Order executed\nUpstox", NotificationTextExtractor.extract(extras))
    }

    @Test
    fun removesDuplicateSecondaryText() {
        val extras = Bundle().apply {
            putCharSequence("android.text", "Upstox")
            putCharSequence("android.subText", "Upstox")
            putCharSequence("android.bigText", "Order executed")
        }

        assertEquals("Upstox\nOrder executed", NotificationTextExtractor.extract(extras))
    }

    @Test
    fun ignoresBlankValues() {
        val extras = Bundle().apply {
            putCharSequence("android.text", "  ")
            putCharSequence("android.subText", "")
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

    @Test
    fun boundsLongNotificationLines() {
        val extras = Bundle().apply {
            putCharSequenceArray(
                "android.textLines",
                arrayOf(
                    "a".repeat(NotificationTextExtractor.MAX_LINE_LENGTH + 100),
                    "second"
                )
            )
        }

        val result = NotificationTextExtractor.extract(extras)
        assertEquals(NotificationTextExtractor.MAX_LINE_LENGTH + 1 + "second".length, result.length)
        assertTrue(result.startsWith("a".repeat(NotificationTextExtractor.MAX_LINE_LENGTH)))
        assertTrue(result.endsWith("second"))
    }

    @Test
    fun limitsNumberOfNotificationLines() {
        val extras = Bundle().apply {
            putCharSequenceArray(
                "android.textLines",
                Array(NotificationTextExtractor.MAX_LINE_COUNT + 10) { index -> "line$index" }
            )
        }

        val result = NotificationTextExtractor.extract(extras)
        assertTrue(result.contains("line${NotificationTextExtractor.MAX_LINE_COUNT - 1}"))
        assertTrue(!result.contains("line${NotificationTextExtractor.MAX_LINE_COUNT}"))
    }
}
