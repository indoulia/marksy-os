package com.marksy.os.data.local

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.marksy.os.data.RetentionPolicy
import kotlinx.coroutines.flow.Flow

/** Candidates in one [state] (Health). */
data class CaptureStateCount(val state: String, val n: Int)

/** One capture attempt (EPIC-036). Never holds an image or text: only a hash reference and fixed codes. */
@Entity(tableName = "capture_evidence", indices = [Index(value = ["contentHash"]), Index(value = ["capturedAt"])])
data class CaptureEvidenceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** CaptureMethod name. */
    val method: String,
    val sourcePackage: String?,
    val sourceName: String?,
    val sourceVerified: Boolean,
    val capturedAt: Long,
    /** `sha256:<hex>` of the image or frame, or `event:<id>`. */
    val evidenceRef: String,
    val contentHash: String?,
    val notificationEventId: Long? = null,
    val workflowId: Long? = null,
    /** CaptureState name. */
    val state: String,
    val failureCode: String? = null,
    val updatedAt: Long
)

/** A tip read from captured text; stays on the phone unless the user chose Send and CandidateDeliveryPolicy allows it. */
@Entity(tableName = "tip_candidates", indices = [Index(value = ["evidenceId"]), Index(value = ["state"]), Index(value = ["deliveryState"])])
data class TipCandidateEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val evidenceId: Long,
    val method: String,
    val sourcePackage: String?,
    val sourceName: String?,
    val sourceVerified: Boolean,
    val capturedAt: Long,
    val evidenceRef: String,
    val notificationEventId: Long? = null,
    val workflowId: Long? = null,
    /** Recognized text for the user's review only; never sent, cleared on reject. */
    val extractedText: String?,
    val confidence: Double,
    val symbol: String?,
    /** TipSide name. */
    val side: String?,
    val entry: Double?,
    val target: Double?,
    val stopLoss: Double?,
    val horizon: String?,
    val visibleTimestamp: String?,
    /** Comma-separated fixed ambiguity codes. */
    val ambiguities: String,
    /** CaptureState name. */
    val state: String,
    val userChoseSend: Boolean = false,
    val reviewedAt: Long? = null,
    val deliveryState: String = DeliveryState.NOT_APPLICABLE.name,
    val deliveryAttempts: Int = 0,
    val lastDeliveryAttemptAt: Long? = null,
    /** Fixed code only. */
    val deliveryNote: String? = null,
    val updatedAt: Long
)

/** A teaser or redacted notification the user may open or capture; one per notification. */
@Entity(
    tableName = "capture_workflows",
    indices = [Index(value = ["sourcePackage", "sourceKey"], unique = true), Index(value = ["state"])]
)
data class CaptureWorkflowEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sourcePackage: String,
    val sourceKey: String,
    val notificationEventId: Long,
    /** TEASER or REDACTED. */
    val reason: String,
    /** WorkflowState name. */
    val state: String,
    val createdAt: Long,
    val updatedAt: Long,
    /** Null until opened; false when only the launcher could open the source. */
    val openedViaDeepLink: Boolean? = null,
    val failureCode: String? = null,
    val candidateId: Long? = null
)

@Dao
interface CaptureDao {
    @Insert
    suspend fun insertEvidence(evidence: CaptureEvidenceEntity): Long

    @Query("SELECT * FROM capture_evidence WHERE id = :id")
    suspend fun evidence(id: Long): CaptureEvidenceEntity?

    // Conditional so a stale caller can never move a row backwards.
    @Query("UPDATE capture_evidence SET state = :to, failureCode = :code, updatedAt = :at WHERE id = :id AND state = :from")
    suspend fun moveEvidence(id: Long, from: String, to: String, code: String?, at: Long): Int

    @Query("SELECT failureCode FROM capture_evidence WHERE state = 'FAILED' ORDER BY capturedAt DESC, id DESC LIMIT 1")
    suspend fun lastFailureCode(): String?

    @Insert
    suspend fun insertCandidate(candidate: TipCandidateEntity): Long

    @Query("SELECT * FROM tip_candidates WHERE id = :id")
    suspend fun candidate(id: Long): TipCandidateEntity?

    @Update
    suspend fun updateCandidate(candidate: TipCandidateEntity): Int

    @Query("SELECT c.id FROM tip_candidates c JOIN capture_evidence e ON e.id = c.evidenceId WHERE e.contentHash = :hash AND e.capturedAt >= :since ORDER BY c.id DESC LIMIT 1")
    suspend fun candidateIdByHash(hash: String, since: Long): Long?

    @Query("SELECT state, COUNT(*) AS n FROM tip_candidates GROUP BY state")
    fun observeCandidateCounts(): Flow<List<CaptureStateCount>>

    @Query("SELECT * FROM tip_candidates WHERE state IN ('EXTRACTED', 'REVIEW_REQUIRED') ORDER BY capturedAt DESC")
    fun observeToReview(): Flow<List<TipCandidateEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertWorkflow(workflow: CaptureWorkflowEntity): Long

    @Query("SELECT * FROM capture_workflows WHERE id = :id")
    suspend fun workflow(id: Long): CaptureWorkflowEntity?

    @Query("SELECT * FROM capture_workflows WHERE sourcePackage = :sourcePackage AND sourceKey = :sourceKey")
    suspend fun workflowBySource(sourcePackage: String, sourceKey: String): CaptureWorkflowEntity?

    @Query("SELECT * FROM capture_workflows WHERE state NOT IN ('ACCEPTED', 'REJECTED', 'EXPIRED') ORDER BY createdAt DESC")
    fun observeOpenWorkflows(): Flow<List<CaptureWorkflowEntity>>

    @Query("UPDATE capture_workflows SET state = :to, failureCode = :code, updatedAt = :at WHERE id = :id AND state = :from")
    suspend fun moveWorkflow(id: Long, from: String, to: String, code: String?, at: Long): Int

    @Query("UPDATE capture_workflows SET openedViaDeepLink = :viaDeepLink, updatedAt = :at WHERE id = :id")
    suspend fun setOpenedVia(id: Long, viaDeepLink: Boolean, at: Long): Int

    @Query("UPDATE capture_workflows SET candidateId = :candidateId WHERE id = :id")
    suspend fun linkCandidate(id: Long, candidateId: Long): Int

    @Query("SELECT * FROM tip_candidates WHERE deliveryState = 'PENDING' ORDER BY capturedAt ASC LIMIT :limit")
    suspend fun findPendingDelivery(limit: Int): List<TipCandidateEntity>

    @Query("UPDATE tip_candidates SET deliveryState = 'IN_FLIGHT', deliveryAttempts = :attempts, lastDeliveryAttemptAt = :attemptedAt WHERE id = :id AND deliveryState = 'PENDING'")
    suspend fun claimPendingDelivery(id: Long, attempts: Int, attemptedAt: Long): Int

    @Query("UPDATE tip_candidates SET deliveryState = :state, deliveryAttempts = :attempts, lastDeliveryAttemptAt = :attemptedAt WHERE id = :id AND deliveryState = 'IN_FLIGHT'")
    suspend fun updateInFlightDelivery(id: Long, state: String, attempts: Int, attemptedAt: Long): Int

    @Query("UPDATE tip_candidates SET deliveryNote = :note WHERE id = :id")
    suspend fun setDeliveryNote(id: Long, note: String?): Int

    @Query("UPDATE tip_candidates SET deliveryState = 'PENDING' WHERE deliveryState = 'IN_FLIGHT' AND lastDeliveryAttemptAt < :cutoff")
    suspend fun recoverStaleInFlight(cutoff: Long): Int

    // Process death mid-capture leaves no session to finish it.
    @Query("UPDATE capture_workflows SET state = 'CAPTURE_FAILED', failureCode = 'stale-session', updatedAt = :now WHERE state IN ('CAPTURE_AUTHORIZED', 'EXTRACTION_PENDING') AND updatedAt < :cutoff")
    suspend fun failStaleSessions(cutoff: Long, now: Long): Int

    @Query("UPDATE capture_workflows SET state = 'EXPIRED', updatedAt = :now WHERE state NOT IN ('ACCEPTED', 'REJECTED', 'EXPIRED') AND createdAt <= :createdBefore")
    suspend fun expireWorkflows(createdBefore: Long, now: Long): Int

    @Query("UPDATE capture_evidence SET state = 'EXPIRED', updatedAt = :now WHERE id IN (SELECT evidenceId FROM tip_candidates WHERE state IN ('EXTRACTED', 'REVIEW_REQUIRED') AND capturedAt < :cutoff)")
    suspend fun expireUnreviewedEvidence(cutoff: Long, now: Long): Int

    @Query("UPDATE tip_candidates SET state = 'EXPIRED', updatedAt = :now WHERE state IN ('EXTRACTED', 'REVIEW_REQUIRED') AND capturedAt < :cutoff")
    suspend fun expireUnreviewedCandidates(cutoff: Long, now: Long): Int

    @Transaction
    suspend fun expireUnreviewed(cutoff: Long, now: Long): Int {
        expireUnreviewedEvidence(cutoff, now)
        return expireUnreviewedCandidates(cutoff, now)
    }

    @Query("DELETE FROM tip_candidates WHERE capturedAt < :cutoff AND (state = 'ACCEPTED') = :accepted")
    suspend fun deleteOldCandidates(cutoff: Long, accepted: Boolean): Int

    // Evidence goes with its candidate; evidence without one (failures, not a tip) keeps the short window.
    @Query("DELETE FROM capture_evidence WHERE capturedAt < :cutoff AND id NOT IN (SELECT evidenceId FROM tip_candidates)")
    suspend fun deleteOldOrphanEvidence(cutoff: Long): Int

    @Query("DELETE FROM capture_workflows WHERE createdAt < :cutoff AND (state = 'ACCEPTED') = :accepted")
    suspend fun deleteOldWorkflows(cutoff: Long, accepted: Boolean): Int

    @Transaction
    suspend fun pruneExpired(nowMillis: Long) {
        val short = RetentionPolicy.captureCutoff(accepted = false, nowMillis = nowMillis)
        val long = RetentionPolicy.captureCutoff(accepted = true, nowMillis = nowMillis)
        deleteOldCandidates(short, accepted = false)
        deleteOldCandidates(long, accepted = true)
        deleteOldOrphanEvidence(short)
        deleteOldWorkflows(short, accepted = false)
        deleteOldWorkflows(long, accepted = true)
    }
}
