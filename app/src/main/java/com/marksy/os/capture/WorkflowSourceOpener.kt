package com.marksy.os.capture

import com.marksy.os.data.local.CaptureDao
import com.marksy.os.notification.OriginalAppLauncher.Opened

/** Opens a teaser workflow's source app (EPIC-039) only on the user's own tap, and records how it opened. */
class WorkflowSourceOpener(
    private val gateway: CaptureGateway,
    private val dao: CaptureDao,
    private val open: (sourcePackage: String, sourceKey: String) -> Opened
) {
    /** "View tip": a source that cannot be opened is recorded as `source-unavailable`. */
    suspend fun view(workflowId: Long): Opened {
        val opened = openAndRecord(workflowId) ?: return Opened.UNAVAILABLE
        if (opened == Opened.UNAVAILABLE) gateway.submitFailure(CaptureMethod.NOTIFICATION_LISTENER, workflowId, CaptureFailure.SOURCE_UNAVAILABLE)
        return opened
    }

    /** After capture consent; on [Opened.UNAVAILABLE] the caller ends the session, which reports it. */
    suspend fun openForCapture(workflowId: Long): Opened = openAndRecord(workflowId) ?: Opened.UNAVAILABLE

    /** Null when the workflow is gone or closed: nothing is opened for it. */
    private suspend fun openAndRecord(workflowId: Long): Opened? {
        val workflow = dao.workflow(workflowId)?.takeIf { !WorkflowState.valueOf(it.state).isTerminal } ?: return null
        val opened = open(workflow.sourcePackage, workflow.sourceKey)
        if (opened != Opened.UNAVAILABLE) gateway.sourceOpened(workflowId, viaDeepLink = opened == Opened.CONTENT_INTENT)
        return opened
    }
}
