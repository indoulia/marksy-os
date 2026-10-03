package com.marksy.os.capture.projection

import com.marksy.os.capture.CaptureOutcome
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface CaptureSessionState {
    data object Idle : CaptureSessionState
    data class AwaitingConsent(val workflowId: Long, val since: Long) : CaptureSessionState
    data class Active(val workflowId: Long) : CaptureSessionState
    data class Capturing(val workflowId: Long) : CaptureSessionState
    data class Finished(val workflowId: Long, val outcome: CaptureOutcome) : CaptureSessionState
}

/**
 * The screen-capture session (EPIC-038): one consent, one session, one frame. Process memory only;
 * a session lost to process death is turned into `stale-session` by [com.marksy.os.capture.CaptureGateway.sweep].
 */
class CaptureSessionController(private val clock: () -> Long = System::currentTimeMillis) {
    private val _state = MutableStateFlow<CaptureSessionState>(CaptureSessionState.Idle)
    val state: StateFlow<CaptureSessionState> = _state.asStateFlow()

    val isRunning: Boolean get() = state.value.let { it is CaptureSessionState.Active || it is CaptureSessionState.Capturing }

    /** A consent answer that never came back (its screen was recreated elsewhere) stops blocking after [CONSENT_TIMEOUT_MS]. */
    @Synchronized
    fun requestConsent(workflowId: Long): Boolean {
        val current = _state.value
        val free = current is CaptureSessionState.Idle || current is CaptureSessionState.Finished ||
            (current is CaptureSessionState.AwaitingConsent && clock() - current.since >= CONSENT_TIMEOUT_MS)
        if (free) _state.value = CaptureSessionState.AwaitingConsent(workflowId, clock())
        return free
    }

    @Synchronized
    fun activate(workflowId: Long): Boolean = move<CaptureSessionState.AwaitingConsent>(workflowId) { CaptureSessionState.Active(workflowId) }

    /** Active to Capturing; the workflow to capture for, or null when no session is waiting for a frame. */
    @Synchronized
    fun beginCapture(): Long? {
        val active = _state.value as? CaptureSessionState.Active ?: return null
        _state.value = CaptureSessionState.Capturing(active.workflowId)
        return active.workflowId
    }

    /** Ends the session once; a late second end (e.g. the system's onStop after the frame) is ignored. */
    @Synchronized
    fun finish(workflowId: Long, outcome: CaptureOutcome): Boolean {
        val current = _state.value
        if (current is CaptureSessionState.Idle || current is CaptureSessionState.Finished || current.workflowId != workflowId) return false
        _state.value = CaptureSessionState.Finished(workflowId, outcome)
        return true
    }

    /** The workflow closed before consent was asked. */
    @Synchronized
    fun abandon(workflowId: Long): Boolean = move<CaptureSessionState.AwaitingConsent>(workflowId) { CaptureSessionState.Idle }

    /** The UI has shown the finished outcome. */
    @Synchronized
    fun acknowledge() {
        if (_state.value is CaptureSessionState.Finished) _state.value = CaptureSessionState.Idle
    }

    private inline fun <reified T : CaptureSessionState> move(workflowId: Long, next: () -> CaptureSessionState): Boolean {
        val current = _state.value
        if (current !is T || current.workflowId != workflowId) return false
        _state.value = next()
        return true
    }

    private val CaptureSessionState.workflowId: Long?
        get() = when (this) {
            CaptureSessionState.Idle -> null
            is CaptureSessionState.AwaitingConsent -> workflowId
            is CaptureSessionState.Active -> workflowId
            is CaptureSessionState.Capturing -> workflowId
            is CaptureSessionState.Finished -> workflowId
        }

    companion object {
        const val CONSENT_TIMEOUT_MS = 2 * 60 * 1000L
        val shared = CaptureSessionController()
    }
}
