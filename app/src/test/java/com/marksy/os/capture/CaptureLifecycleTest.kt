package com.marksy.os.capture

import com.marksy.os.capture.CaptureState.ACCEPTED
import com.marksy.os.capture.CaptureState.CAPTURED
import com.marksy.os.capture.CaptureState.EXPIRED
import com.marksy.os.capture.CaptureState.EXTRACTED
import com.marksy.os.capture.CaptureState.FAILED
import com.marksy.os.capture.CaptureState.PENDING_EXTRACTION
import com.marksy.os.capture.CaptureState.REJECTED
import com.marksy.os.capture.CaptureState.REVIEW_REQUIRED
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureLifecycleTest {
    private val legalCapture = mapOf(
        CAPTURED to setOf(PENDING_EXTRACTION, FAILED, EXPIRED),
        PENDING_EXTRACTION to setOf(EXTRACTED, REVIEW_REQUIRED, FAILED, EXPIRED),
        EXTRACTED to setOf(ACCEPTED, REJECTED, EXPIRED),
        REVIEW_REQUIRED to setOf(ACCEPTED, REJECTED, EXPIRED)
    )

    @Test
    fun everyLegalCaptureMoveIsAllowedAndEveryOtherThrows() {
        for (from in CaptureState.entries) for (to in CaptureState.entries) {
            if (to in legalCapture[from].orEmpty()) {
                assertEquals("$from->$to", to, CaptureLifecycle.move(from, to))
            } else {
                assertThrows("$from->$to", IllegalStateException::class.java) { CaptureLifecycle.move(from, to) }
            }
        }
    }

    @Test
    fun terminalCaptureStatesAreFinal() {
        val terminal = setOf(ACCEPTED, REJECTED, EXPIRED, FAILED)
        assertEquals(terminal, CaptureState.entries.filter { it.isTerminal }.toSet())
        terminal.forEach { from -> CaptureState.entries.forEach { to -> assertFalse("$from->$to", CaptureLifecycle.canMove(from, to)) } }
    }

    @Test
    fun workflowHappyPathRunsInOrder() {
        val path = listOf(
            WorkflowState.NOTIFICATION_RECEIVED, WorkflowState.NEEDS_SOURCE_VIEW, WorkflowState.USER_OPENED_SOURCE,
            WorkflowState.CAPTURE_REQUESTED, WorkflowState.CAPTURE_AUTHORIZED, WorkflowState.EXTRACTION_PENDING,
            WorkflowState.REVIEW_REQUIRED, WorkflowState.ACCEPTED
        )
        path.zipWithNext().forEach { (from, to) -> assertEquals(to, CaptureLifecycle.move(from, to)) }
        assertTrue(CaptureLifecycle.canMove(WorkflowState.REVIEW_REQUIRED, WorkflowState.REJECTED))
        // The user may tap Capture before opening the source.
        assertTrue(CaptureLifecycle.canMove(WorkflowState.NEEDS_SOURCE_VIEW, WorkflowState.CAPTURE_REQUESTED))
    }

    @Test
    fun workflowFailuresRetryUntilExpired() {
        val retryable = listOf(WorkflowState.CAPTURE_DENIED, WorkflowState.CAPTURE_FAILED, WorkflowState.PROTECTED_SCREEN, WorkflowState.SOURCE_UNAVAILABLE)
        retryable.forEach { failed ->
            assertEquals(WorkflowState.CAPTURE_REQUESTED, CaptureLifecycle.move(failed, WorkflowState.CAPTURE_REQUESTED))
            assertEquals(WorkflowState.EXPIRED, CaptureLifecycle.move(failed, WorkflowState.EXPIRED))
            assertThrows(IllegalStateException::class.java) { CaptureLifecycle.move(failed, WorkflowState.REVIEW_REQUIRED) }
        }
        assertTrue(CaptureLifecycle.canMove(WorkflowState.CAPTURE_REQUESTED, WorkflowState.CAPTURE_DENIED))
        assertTrue(CaptureLifecycle.canMove(WorkflowState.CAPTURE_AUTHORIZED, WorkflowState.PROTECTED_SCREEN))
        assertTrue(CaptureLifecycle.canMove(WorkflowState.EXTRACTION_PENDING, WorkflowState.CAPTURE_FAILED))
        assertTrue(CaptureLifecycle.canMove(WorkflowState.NEEDS_SOURCE_VIEW, WorkflowState.SOURCE_UNAVAILABLE))
        // Every open workflow can expire; nothing skips the capture steps.
        WorkflowState.entries.filterNot { it.isTerminal }.forEach { assertTrue("$it", CaptureLifecycle.canMove(it, WorkflowState.EXPIRED)) }
        assertThrows(IllegalStateException::class.java) { CaptureLifecycle.move(WorkflowState.NEEDS_SOURCE_VIEW, WorkflowState.ACCEPTED) }
        assertThrows(IllegalStateException::class.java) { CaptureLifecycle.move(WorkflowState.USER_OPENED_SOURCE, WorkflowState.CAPTURE_AUTHORIZED) }
    }

    @Test
    fun workflowTerminalStatesAreFinalAndTtlIsTwelveHours() {
        val terminal = setOf(WorkflowState.ACCEPTED, WorkflowState.REJECTED, WorkflowState.EXPIRED)
        assertEquals(terminal, WorkflowState.entries.filter { it.isTerminal }.toSet())
        terminal.forEach { from -> WorkflowState.entries.forEach { to -> assertFalse("$from->$to", CaptureLifecycle.canMove(from, to)) } }
        val created = 1_000L
        assertFalse(CaptureLifecycle.isWorkflowExpired(created, created + 12 * 60 * 60 * 1000L - 1))
        assertTrue(CaptureLifecycle.isWorkflowExpired(created, created + 12 * 60 * 60 * 1000L))
    }
}
