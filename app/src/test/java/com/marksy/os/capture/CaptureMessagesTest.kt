package com.marksy.os.capture

import com.marksy.os.capture.projection.ScreenCaptureStart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CaptureMessagesTest {
    private val codes = listOf(
        CaptureFailure.CAPTURE_DENIED, CaptureFailure.PROJECTION_REVOKED, CaptureFailure.PROTECTED_SCREEN, CaptureFailure.SOURCE_UNAVAILABLE,
        CaptureFailure.UNSUPPORTED_ANDROID, CaptureFailure.IMAGE_UNREADABLE, CaptureFailure.SESSION_TIMEOUT, CaptureFailure.CONTENT_HIDDEN,
        CaptureFailure.ONE_TIME_CODE, CaptureFailure.NOT_A_TIP, CaptureFailure.STALE_SESSION, CaptureFailure.UNSUPPORTED_SOURCE
    )

    @Test
    fun everyFixedCodeHasItsOwnMessageNeverTheCode() {
        val fallback = CaptureMessages.failure(CaptureFailure.CAPTURE_FAILED)
        assertEquals(fallback, CaptureMessages.failure(null))
        assertEquals(codes.size, codes.map(CaptureMessages::failure).toSet().size)
        codes.forEach {
            assertNotEquals(fallback, CaptureMessages.failure(it))
            assertFalse(CaptureMessages.failure(it).contains(it))
        }
    }

    @Test
    fun protectedScreenSaysSo() {
        assertEquals("This app blocks screen capture.", CaptureMessages.failure(CaptureFailure.PROTECTED_SCREEN))
    }

    @Test
    fun everyNonStartedResultExplainsItself() {
        assertNull(CaptureMessages.start(ScreenCaptureStart.STARTED))
        ScreenCaptureStart.entries.filter { it != ScreenCaptureStart.STARTED }.forEach { assertFalse(CaptureMessages.start(it).isNullOrBlank()) }
    }

    @Test
    fun everyAmbiguityCodeHasAMessage() {
        listOf(
            Ambiguity.AMBIGUOUS_SYMBOL, Ambiguity.MALFORMED_PRICE, Ambiguity.MISSING_TARGET, Ambiguity.MISSING_STOP_LOSS,
            Ambiguity.CONFLICTING_SIDE, Ambiguity.LOW_OCR_CONFIDENCE
        ).forEach { assertFalse(CaptureMessages.ambiguity(it).isNullOrBlank()) }
    }
}
