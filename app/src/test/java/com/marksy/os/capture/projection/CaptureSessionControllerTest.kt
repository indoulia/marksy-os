package com.marksy.os.capture.projection

import com.marksy.os.capture.CaptureFailure
import com.marksy.os.capture.CaptureOutcome
import com.marksy.os.capture.CaptureState
import com.marksy.os.capture.projection.CaptureSessionState.Active
import com.marksy.os.capture.projection.CaptureSessionState.AwaitingConsent
import com.marksy.os.capture.projection.CaptureSessionState.Capturing
import com.marksy.os.capture.projection.CaptureSessionState.Finished
import com.marksy.os.capture.projection.CaptureSessionState.Idle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureSessionControllerTest {
    private var now = 1_000_000L
    private val controller = CaptureSessionController(clock = { now })
    private val outcome = CaptureOutcome.Candidate(3, CaptureState.REVIEW_REQUIRED)

    @Test
    fun oneConsentOneSessionOneFrame() {
        assertTrue(controller.requestConsent(7))
        assertEquals(AwaitingConsent(7, now), controller.state.value)
        assertTrue(controller.activate(7))
        assertTrue(controller.isRunning)
        assertEquals(7L, controller.beginCapture())
        assertEquals(Capturing(7), controller.state.value)
        assertNull(controller.beginCapture())
        assertTrue(controller.finish(7, outcome))
        assertEquals(Finished(7, outcome), controller.state.value)
        assertFalse(controller.finish(7, CaptureOutcome.Failed(CaptureFailure.PROJECTION_REVOKED)))
        controller.acknowledge()
        assertEquals(Idle, controller.state.value)
    }

    @Test
    fun aRunningSessionRefusesASecondConsentAndForeignMoves() {
        controller.requestConsent(7)
        controller.activate(7)
        assertFalse(controller.requestConsent(8))
        assertFalse(controller.activate(8))
        assertFalse(controller.finish(8, outcome))
        assertEquals(Active(7), controller.state.value)
    }

    @Test
    fun nothingCapturesWithoutAnActiveSession() {
        assertNull(controller.beginCapture())
        assertFalse(controller.activate(7))
        assertFalse(controller.finish(7, outcome))
        assertEquals(Idle, controller.state.value)
    }

    @Test
    fun aLostConsentAnswerStopsBlockingAfterItsTimeout() {
        controller.requestConsent(7)
        now += 60_000
        assertFalse(controller.requestConsent(8))
        now += CaptureSessionController.CONSENT_TIMEOUT_MS
        assertTrue(controller.requestConsent(8))
        assertFalse(controller.activate(7))
    }

    @Test
    fun aFinishedSessionAllowsTheNextConsentAndAClosedWorkflowIsAbandoned() {
        controller.requestConsent(7)
        controller.finish(7, CaptureOutcome.Failed(CaptureFailure.CAPTURE_DENIED))
        assertTrue(controller.requestConsent(8))
        assertFalse(controller.abandon(7))
        assertTrue(controller.abandon(8))
        assertEquals(Idle, controller.state.value)
    }
}
