package com.marksy.os.capture

import com.marksy.os.data.local.CaptureDao
import com.marksy.os.data.local.CaptureEvidenceEntity
import com.marksy.os.data.local.CaptureWorkflowEntity
import com.marksy.os.data.local.DeliveryState
import com.marksy.os.data.local.NotificationEventEntity
import com.marksy.os.data.local.TipCandidateEntity
import java.util.Locale

sealed interface CaptureOutcome {
    data class Candidate(val id: Long, val state: CaptureState) : CaptureOutcome
    data class Duplicate(val existingCandidateId: Long) : CaptureOutcome
    data object NotATip : CaptureOutcome
    data class Failed(val code: String) : CaptureOutcome
}

enum class ReviewDecision { SEND, KEEP, REJECT }

data class SweepResult(val staleSessions: Int, val expiredWorkflows: Int, val expiredCandidates: Int)

object CaptureFailure {
    const val CAPTURE_DENIED = "capture-denied"
    const val PROJECTION_REVOKED = "projection-revoked"
    const val PROTECTED_SCREEN = "protected-screen"
    const val SOURCE_UNAVAILABLE = "source-unavailable"
    const val CAPTURE_FAILED = "capture-failed"
    const val UNSUPPORTED_ANDROID = "unsupported-android"
    const val IMAGE_UNREADABLE = "image-unreadable"
    const val SESSION_TIMEOUT = "session-timeout"
    const val CONTENT_HIDDEN = "content-hidden"
    const val ONE_TIME_CODE = "one-time-code"
    const val NOT_A_TIP = "not-a-tip"
    const val STALE_SESSION = "stale-session"
    const val UNSUPPORTED_SOURCE = "unsupported-source"

    /** Codes a caller may report; anything else is stored as [CAPTURE_FAILED] so no free text reaches storage or logs. */
    val REPORTABLE = setOf(
        CAPTURE_DENIED, PROJECTION_REVOKED, PROTECTED_SCREEN, SOURCE_UNAVAILABLE, CAPTURE_FAILED, UNSUPPORTED_ANDROID, IMAGE_UNREADABLE,
        SESSION_TIMEOUT, CONTENT_HIDDEN
    )
}

/**
 * The capture boundary (EPIC-036): every capture method enters here, and nothing leaves the phone from here.
 * Logs carry only ids, enum names and fixed codes.
 */
class CaptureGateway(
    private val dao: CaptureDao,
    private val registry: CaptureSourceRegistry,
    private val clock: () -> Long = System::currentTimeMillis,
    private val log: (String) -> Unit = {},
    /** A reviewed candidate was queued for delivery (wakes the delivery worker). */
    private val onDeliveryQueued: () -> Unit = {}
) {
    private val planner = NotificationCapturePlanner(registry)

    /** Called from IngestionPipeline.onStored; returns the new workflow id for a teaser or redacted notification. */
    suspend fun onNotificationStored(event: NotificationEventEntity): Long? {
        val plan = planner.plan(event)
        if (plan !is NotificationCapturePlan.NeedsSourceView) {
            log("capture event=${event.id} no workflow (${planCode(plan)})")
            return null
        }
        val now = clock()
        val id = dao.insertWorkflow(
            CaptureWorkflowEntity(
                sourcePackage = event.sourcePackage.trim().lowercase(Locale.ROOT), sourceKey = event.sourceKey, notificationEventId = event.id,
                reason = plan.reason.name, state = CaptureLifecycle.move(WorkflowState.NOTIFICATION_RECEIVED, WorkflowState.NEEDS_SOURCE_VIEW).name,
                createdAt = now, updatedAt = now
            )
        )
        if (id == -1L) {
            log("capture event=${event.id} no workflow (duplicate)")
            return null
        }
        log("capture workflow=$id event=${event.id} ${plan.reason}")
        return id
    }

    suspend fun submitRecognized(
        method: CaptureMethod, sourceHint: String?, sourceVerified: Boolean, contentHash: String, text: String?,
        ocrConfidence: Float?, workflowId: Long?
    ): CaptureOutcome {
        val now = clock()
        workflowId?.let { openWorkflow(it) }
        val workflow = workflowId?.let { dao.workflow(it) }
        if (workflow?.candidateId != null && workflow.state in REVIEWED_WORKFLOW) return duplicate(workflow.candidateId)
        dao.candidateIdByHash(contentHash, now - DUPLICATE_WINDOW_MS)?.let { return duplicate(it) }

        // A workflow's package is the originating notification's identity; otherwise only an allow-listed hint counts.
        val hinted = if (workflow == null) registry.resolve(sourceHint) else null
        if (workflow == null && !sourceHint.isNullOrBlank() && hinted == null) log("capture source ${CaptureFailure.UNSUPPORTED_SOURCE}")
        val sourcePackage = workflow?.sourcePackage ?: hinted?.packageName
        val provenance = CaptureProvenance(
            method, sourcePackage, sourcePackage?.let { registry.resolve(it)?.displayName }, workflow != null || (hinted != null && sourceVerified),
            now, "sha256:$contentHash", workflow?.notificationEventId, workflow?.id
        )
        val evidenceId = dao.insertEvidence(
            CaptureEvidenceEntity(
                method = method.name, sourcePackage = provenance.sourcePackage, sourceName = provenance.sourceName, sourceVerified = provenance.sourceVerified,
                capturedAt = now, evidenceRef = provenance.evidenceRef, contentHash = contentHash, notificationEventId = provenance.notificationEventId,
                workflowId = provenance.workflowId, state = CaptureState.CAPTURED.name, updatedAt = now
            )
        )
        moveEvidence(evidenceId, CaptureState.CAPTURED, CaptureState.PENDING_EXTRACTION)
        workflow?.let { moveWorkflow(it.id, WorkflowState.EXTRACTION_PENDING) }

        val result = if (text.isNullOrBlank()) ExtractionResult.NotATip else TipExtractor.extract(text, provenance, ocrConfidence)
        return when (result) {
            ExtractionResult.OneTimeCode -> failExtraction(evidenceId, workflow, CaptureFailure.ONE_TIME_CODE, method)
            ExtractionResult.NotATip -> {
                failExtraction(evidenceId, workflow, CaptureFailure.NOT_A_TIP, method)
                CaptureOutcome.NotATip
            }
            is ExtractionResult.Candidate -> {
                val candidate = result.candidate
                val id = dao.insertCandidate(candidate.toEntity(evidenceId, now))
                moveEvidence(evidenceId, CaptureState.PENDING_EXTRACTION, candidate.state)
                workflow?.let {
                    dao.linkCandidate(it.id, id)
                    moveWorkflow(it.id, WorkflowState.REVIEW_REQUIRED)
                }
                log("capture candidate=$id evidence=$evidenceId method=$method ${candidate.state}")
                CaptureOutcome.Candidate(id, candidate.state)
            }
        }
    }

    suspend fun submitFailure(method: CaptureMethod, workflowId: Long?, code: String): CaptureOutcome.Failed {
        val fixed = code.takeIf { it in CaptureFailure.REPORTABLE } ?: CaptureFailure.CAPTURE_FAILED
        val now = clock()
        val workflow = workflowId?.let { dao.workflow(it) }
        val evidenceId = dao.insertEvidence(
            CaptureEvidenceEntity(
                method = method.name, sourcePackage = workflow?.sourcePackage, sourceName = null, sourceVerified = workflow != null,
                capturedAt = now, evidenceRef = workflow?.let { "workflow:${it.id}" } ?: "none", contentHash = null,
                notificationEventId = workflow?.notificationEventId, workflowId = workflow?.id,
                state = CaptureLifecycle.move(CaptureState.CAPTURED, CaptureState.FAILED).name, failureCode = fixed, updatedAt = now
            )
        )
        workflow?.let { moveWorkflow(it.id, failureState(fixed), fixed) }
        log("capture evidence=$evidenceId method=$method workflow=${workflow?.id} $fixed")
        return CaptureOutcome.Failed(fixed)
    }

    /** The user opened the source app; [viaDeepLink] false when only its launcher could open it. */
    suspend fun sourceOpened(id: Long, viaDeepLink: Boolean): Boolean {
        val workflow = openWorkflow(id) ?: return false
        dao.setOpenedVia(id, viaDeepLink, clock())
        // During an authorized capture session the open is recorded without moving the workflow.
        return workflow.state != WorkflowState.NEEDS_SOURCE_VIEW.name || moveWorkflow(id, WorkflowState.USER_OPENED_SOURCE)
    }

    suspend fun captureRequested(id: Long): Boolean = openWorkflow(id) != null && moveWorkflow(id, WorkflowState.CAPTURE_REQUESTED)

    suspend fun captureAuthorized(id: Long): Boolean = openWorkflow(id) != null && moveWorkflow(id, WorkflowState.CAPTURE_AUTHORIZED)

    /** The user's review; returns the new state, or null when the candidate is gone or already final. */
    suspend fun review(candidateId: Long, fields: TipFields, sourcePackage: String?, decision: ReviewDecision): CaptureState? {
        val now = clock()
        val candidate = dao.candidate(candidateId) ?: return null
        val from = CaptureState.valueOf(candidate.state)
        val to = if (decision == ReviewDecision.REJECT) CaptureState.REJECTED else CaptureState.ACCEPTED
        if (!CaptureLifecycle.canMove(from, to)) {
            log("capture candidate=$candidateId review ignored ($from)")
            return null
        }
        val reviewed = if (decision == ReviewDecision.REJECT) {
            candidate.copy(state = to.name, extractedText = null, reviewedAt = now, updatedAt = now)
        } else {
            // A verified source is package identity and stays; otherwise the user's pick from the allow-list (null for "Other").
            val picked = if (candidate.sourceVerified) null else registry.resolve(sourcePackage)
            candidate.copy(
                state = to.name, reviewedAt = now, updatedAt = now, userChoseSend = decision == ReviewDecision.SEND,
                symbol = fields.symbol?.trim()?.uppercase(Locale.ROOT)?.ifEmpty { null }, side = fields.side?.name,
                entry = fields.entry, target = fields.target, stopLoss = fields.stopLoss, horizon = fields.horizon?.trim()?.ifEmpty { null },
                sourcePackage = if (candidate.sourceVerified) candidate.sourcePackage else picked?.packageName,
                sourceName = if (candidate.sourceVerified) candidate.sourceName else picked?.displayName
            )
        }
        val queued = decision == ReviewDecision.SEND && CandidateDeliveryPolicy.mayQueue(reviewed, registry)
        dao.updateCandidate(reviewed.copy(deliveryState = (if (queued) DeliveryState.PENDING else DeliveryState.NOT_APPLICABLE).name))
        moveEvidence(candidate.evidenceId, from, to)
        candidate.workflowId?.let { moveWorkflow(it, if (to == CaptureState.ACCEPTED) WorkflowState.ACCEPTED else WorkflowState.REJECTED) }
        log("capture candidate=$candidateId $decision -> $to queued=$queued")
        if (queued) runCatching(onDeliveryQueued)
        return to
    }

    /** Expires workflows past their TTL, fails sessions lost to process death, and expires unreviewed candidates. */
    suspend fun sweep(now: Long = clock()): SweepResult {
        val result = SweepResult(
            staleSessions = dao.failStaleSessions(now - STALE_SESSION_MS, now),
            expiredWorkflows = dao.expireWorkflows(now - CaptureLifecycle.WORKFLOW_TTL_MS, now),
            expiredCandidates = dao.expireUnreviewed(now - UNREVIEWED_TTL_MS, now)
        )
        log("capture sweep stale=${result.staleSessions} workflows=${result.expiredWorkflows} candidates=${result.expiredCandidates}")
        return result
    }

    private fun duplicate(candidateId: Long): CaptureOutcome {
        log("capture duplicate of candidate=$candidateId")
        return CaptureOutcome.Duplicate(candidateId)
    }

    private suspend fun failExtraction(evidenceId: Long, workflow: CaptureWorkflowEntity?, code: String, method: CaptureMethod): CaptureOutcome.Failed {
        moveEvidence(evidenceId, CaptureState.PENDING_EXTRACTION, CaptureState.FAILED, code)
        workflow?.let { moveWorkflow(it.id, WorkflowState.CAPTURE_FAILED, code) }
        log("capture evidence=$evidenceId method=$method $code")
        return CaptureOutcome.Failed(code)
    }

    private suspend fun moveEvidence(id: Long, from: CaptureState, to: CaptureState, code: String? = null) {
        dao.moveEvidence(id, from.name, CaptureLifecycle.move(from, to).name, code, clock())
    }

    /** The workflow if it is still open; past its TTL it is expired here instead. */
    private suspend fun openWorkflow(id: Long): CaptureWorkflowEntity? {
        val workflow = dao.workflow(id) ?: return null
        if (WorkflowState.valueOf(workflow.state).isTerminal) return null
        if (CaptureLifecycle.isWorkflowExpired(workflow.createdAt, clock())) {
            moveWorkflow(id, WorkflowState.EXPIRED)
            return null
        }
        return workflow
    }

    // Illegal moves (double taps, a late callback) are ignored rather than thrown at the UI.
    private suspend fun moveWorkflow(id: Long, to: WorkflowState, code: String? = null): Boolean {
        val from = dao.workflow(id)?.state?.let(WorkflowState::valueOf) ?: return false
        if (!CaptureLifecycle.canMove(from, to)) {
            log("capture workflow=$id ignored $from->$to")
            return false
        }
        val moved = dao.moveWorkflow(id, from.name, to.name, code, clock()) == 1
        if (moved) log("capture workflow=$id $from->$to")
        return moved
    }

    private fun failureState(code: String): WorkflowState = when (code) {
        CaptureFailure.CAPTURE_DENIED -> WorkflowState.CAPTURE_DENIED
        CaptureFailure.PROTECTED_SCREEN -> WorkflowState.PROTECTED_SCREEN
        CaptureFailure.SOURCE_UNAVAILABLE -> WorkflowState.SOURCE_UNAVAILABLE
        else -> WorkflowState.CAPTURE_FAILED
    }

    private fun planCode(plan: NotificationCapturePlan): String = when (plan) {
        is NotificationCapturePlan.CompleteTip -> "complete-tip"
        is NotificationCapturePlan.NotApplicable -> plan.code
        is NotificationCapturePlan.NeedsSourceView -> plan.reason.name
    }

    companion object {
        const val DUPLICATE_WINDOW_MS = 24 * 60 * 60 * 1000L
        const val STALE_SESSION_MS = 10 * 60 * 1000L
        const val UNREVIEWED_TTL_MS = 3 * 24 * 60 * 60 * 1000L
        private val REVIEWED_WORKFLOW = setOf(WorkflowState.REVIEW_REQUIRED.name, WorkflowState.ACCEPTED.name)
    }
}

val TipCandidateEntity.fields: TipFields
    get() = TipFields(symbol, TipSide.entries.firstOrNull { it.name == side }, entry, target, stopLoss, horizon, visibleTimestamp)

val TipCandidateEntity.provenance: CaptureProvenance
    get() = CaptureProvenance(CaptureMethod.valueOf(method), sourcePackage, sourceName, sourceVerified, capturedAt, evidenceRef, notificationEventId, workflowId)

val TipCandidateEntity.ambiguityCodes: Set<String> get() = ambiguities.split(',').filter { it.isNotBlank() }.toSet()

private fun TipCandidate.toEntity(evidenceId: Long, now: Long) = TipCandidateEntity(
    evidenceId = evidenceId, method = provenance.method.name, sourcePackage = provenance.sourcePackage, sourceName = provenance.sourceName,
    sourceVerified = provenance.sourceVerified, capturedAt = provenance.capturedAt, evidenceRef = provenance.evidenceRef,
    notificationEventId = provenance.notificationEventId, workflowId = provenance.workflowId, extractedText = extractedText, confidence = confidence,
    symbol = fields.symbol, side = fields.side?.name, entry = fields.entry, target = fields.target, stopLoss = fields.stopLoss, horizon = fields.horizon,
    visibleTimestamp = fields.visibleTimestamp, ambiguities = ambiguities.sorted().joinToString(","), state = state.name, updatedAt = now
)
