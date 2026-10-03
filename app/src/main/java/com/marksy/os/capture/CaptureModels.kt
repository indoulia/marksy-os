package com.marksy.os.capture

enum class CaptureMethod { NOTIFICATION_LISTENER, USER_SHARED_IMAGE, USER_SELECTED_IMAGE, MEDIA_PROJECTION }

enum class CaptureState {
    CAPTURED, PENDING_EXTRACTION, EXTRACTED, REVIEW_REQUIRED, ACCEPTED, REJECTED, EXPIRED, FAILED;

    val isTerminal: Boolean get() = this == ACCEPTED || this == REJECTED || this == EXPIRED || this == FAILED
}

enum class WorkflowState {
    NOTIFICATION_RECEIVED, NEEDS_SOURCE_VIEW, USER_OPENED_SOURCE, CAPTURE_REQUESTED, CAPTURE_AUTHORIZED, EXTRACTION_PENDING,
    REVIEW_REQUIRED, ACCEPTED, REJECTED, CAPTURE_DENIED, CAPTURE_FAILED, PROTECTED_SCREEN, SOURCE_UNAVAILABLE, EXPIRED;

    val isTerminal: Boolean get() = this == ACCEPTED || this == REJECTED || this == EXPIRED
}

enum class TipSide { BUY, SELL }

/** `sourceVerified` only when the package is Android package identity (the originating notification), never a user pick. */
data class CaptureProvenance(
    val method: CaptureMethod,
    val sourcePackage: String?,
    val sourceName: String?,
    val sourceVerified: Boolean,
    val capturedAt: Long,
    val evidenceRef: String,
    val notificationEventId: Long? = null,
    val workflowId: Long? = null
)

data class TipFields(
    val symbol: String? = null,
    val side: TipSide? = null,
    val entry: Double? = null,
    val target: Double? = null,
    val stopLoss: Double? = null,
    val horizon: String? = null,
    val visibleTimestamp: String? = null
) {
    // Same shape as CaptureGate's call: a side and two or more priced levels.
    val forwardable: Boolean get() = !symbol.isNullOrBlank() && side != null && listOfNotNull(entry, target, stopLoss).size >= 2
}

data class TipCandidate(
    val provenance: CaptureProvenance,
    val extractedText: String,
    val confidence: Double,
    val fields: TipFields,
    val state: CaptureState,
    val ambiguities: Set<String> = emptySet()
)

object Ambiguity {
    const val AMBIGUOUS_SYMBOL = "ambiguous-symbol"
    const val MALFORMED_PRICE = "malformed-price"
    const val MISSING_TARGET = "missing-target"
    const val MISSING_STOP_LOSS = "missing-stop-loss"
    const val CONFLICTING_SIDE = "conflicting-side"
    const val LOW_OCR_CONFIDENCE = "low-ocr-confidence"
}

object CaptureLifecycle {
    const val WORKFLOW_TTL_MS = 12 * 60 * 60 * 1000L

    private val captureMoves = mapOf(
        CaptureState.CAPTURED to setOf(CaptureState.PENDING_EXTRACTION, CaptureState.FAILED, CaptureState.EXPIRED),
        CaptureState.PENDING_EXTRACTION to setOf(CaptureState.EXTRACTED, CaptureState.REVIEW_REQUIRED, CaptureState.FAILED, CaptureState.EXPIRED),
        CaptureState.EXTRACTED to setOf(CaptureState.ACCEPTED, CaptureState.REJECTED, CaptureState.EXPIRED),
        CaptureState.REVIEW_REQUIRED to setOf(CaptureState.ACCEPTED, CaptureState.REJECTED, CaptureState.EXPIRED)
    )

    private val retry = setOf(WorkflowState.CAPTURE_REQUESTED)
    private val workflowMoves = mapOf(
        WorkflowState.NOTIFICATION_RECEIVED to setOf(WorkflowState.NEEDS_SOURCE_VIEW),
        WorkflowState.NEEDS_SOURCE_VIEW to setOf(WorkflowState.USER_OPENED_SOURCE, WorkflowState.CAPTURE_REQUESTED, WorkflowState.SOURCE_UNAVAILABLE),
        WorkflowState.USER_OPENED_SOURCE to setOf(WorkflowState.CAPTURE_REQUESTED),
        WorkflowState.CAPTURE_REQUESTED to setOf(
            WorkflowState.CAPTURE_AUTHORIZED, WorkflowState.CAPTURE_DENIED, WorkflowState.CAPTURE_FAILED, WorkflowState.SOURCE_UNAVAILABLE
        ),
        WorkflowState.CAPTURE_AUTHORIZED to setOf(
            WorkflowState.EXTRACTION_PENDING, WorkflowState.CAPTURE_FAILED, WorkflowState.PROTECTED_SCREEN, WorkflowState.SOURCE_UNAVAILABLE
        ),
        WorkflowState.EXTRACTION_PENDING to setOf(WorkflowState.REVIEW_REQUIRED, WorkflowState.CAPTURE_FAILED, WorkflowState.PROTECTED_SCREEN),
        WorkflowState.REVIEW_REQUIRED to setOf(WorkflowState.ACCEPTED, WorkflowState.REJECTED),
        WorkflowState.CAPTURE_DENIED to retry,
        WorkflowState.CAPTURE_FAILED to retry,
        WorkflowState.PROTECTED_SCREEN to retry,
        WorkflowState.SOURCE_UNAVAILABLE to retry
    )

    fun canMove(from: CaptureState, to: CaptureState): Boolean = to in captureMoves[from].orEmpty()

    fun move(from: CaptureState, to: CaptureState): CaptureState =
        if (canMove(from, to)) to else throw IllegalStateException("Illegal capture move $from -> $to")

    // Any open workflow may expire (12 h TTL).
    fun canMove(from: WorkflowState, to: WorkflowState): Boolean =
        !from.isTerminal && (to == WorkflowState.EXPIRED || to in workflowMoves[from].orEmpty())

    fun move(from: WorkflowState, to: WorkflowState): WorkflowState =
        if (canMove(from, to)) to else throw IllegalStateException("Illegal workflow move $from -> $to")

    fun isWorkflowExpired(createdAt: Long, now: Long): Boolean = now - createdAt >= WORKFLOW_TTL_MS
}
