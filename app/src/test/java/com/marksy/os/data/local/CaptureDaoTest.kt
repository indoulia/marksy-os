package com.marksy.os.data.local

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CaptureDaoTest {
    private lateinit var db: MarksyDatabase
    private lateinit var dao: CaptureDao
    private val t0 = 1_790_000_000_000L

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), MarksyDatabase::class.java).allowMainThreadQueries().build()
        dao = db.captureDao()
    }

    @After
    fun tearDown() = db.close()

    private fun evidence(hash: String? = "h1", at: Long = t0) = CaptureEvidenceEntity(
        method = "USER_SHARED_IMAGE", sourcePackage = null, sourceName = null, sourceVerified = false, capturedAt = at,
        evidenceRef = "sha256:$hash", contentHash = hash, state = "REVIEW_REQUIRED", updatedAt = at
    )

    private fun candidate(evidenceId: Long, at: Long = t0) = TipCandidateEntity(
        evidenceId = evidenceId, method = "USER_SHARED_IMAGE", sourcePackage = null, sourceName = null, sourceVerified = false,
        capturedAt = at, evidenceRef = "sha256:h1", extractedText = "x", confidence = .5, symbol = "ABC", side = "BUY", entry = 500.0,
        target = 650.0, stopLoss = 470.0, horizon = null, visibleTimestamp = null, ambiguities = "", state = "REVIEW_REQUIRED", updatedAt = at
    )

    private fun workflow(key: String = "k1") = CaptureWorkflowEntity(
        sourcePackage = "com.research.app", sourceKey = key, notificationEventId = 7, reason = "TEASER", state = "NEEDS_SOURCE_VIEW",
        createdAt = t0, updatedAt = t0
    )

    @Test
    fun workflowIsUniquePerNotificationSourceKey() = runBlocking {
        val id = dao.insertWorkflow(workflow())
        assertEquals(-1L, dao.insertWorkflow(workflow()))
        assertEquals(id, dao.workflowBySource("com.research.app", "k1")!!.id)
        assertEquals(1, dao.moveWorkflow(id, "NEEDS_SOURCE_VIEW", "USER_OPENED_SOURCE", null, t0 + 1))
        // A stale caller can never move a workflow from a state it has already left.
        assertEquals(0, dao.moveWorkflow(id, "NEEDS_SOURCE_VIEW", "CAPTURE_REQUESTED", null, t0 + 2))
    }

    @Test
    fun candidateFoundByContentHashAndDeliveryClaimedOnce() = runBlocking {
        val candidateId = dao.insertCandidate(candidate(dao.insertEvidence(evidence())))
        assertEquals(candidateId, dao.candidateIdByHash("h1", t0 - 1))
        assertNull(dao.candidateIdByHash("h1", t0 + 1))
        assertNull(dao.candidateIdByHash("other", t0 - 1))

        dao.updateCandidate(dao.candidate(candidateId)!!.copy(state = "ACCEPTED", deliveryState = DeliveryState.PENDING.name))
        assertEquals(listOf(candidateId), dao.findPendingDelivery(5).map { it.id })
        assertEquals(1, dao.claimPendingDelivery(candidateId, 1, t0))
        assertEquals(0, dao.claimPendingDelivery(candidateId, 1, t0))
        assertEquals(1, dao.updateInFlightDelivery(candidateId, DeliveryState.DELIVERED.name, 1, t0))
        assertEquals(DeliveryState.DELIVERED.name, dao.candidate(candidateId)!!.deliveryState)
    }

    @Test
    fun migrationFrom8CreatesCaptureTablesThatPassRoomValidation() {
        val context = RuntimeEnvironment.getApplication()
        val name = "capture-migration.db"
        context.deleteDatabase(name)
        Room.databaseBuilder(context, MarksyDatabase::class.java, name).build().apply { openHelper.writableDatabase; close() }
        SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READWRITE).use { raw ->
            listOf("capture_evidence", "tip_candidates", "capture_workflows").forEach { raw.execSQL("DROP TABLE $it") }
            raw.version = 8
        }
        val migrated = Room.databaseBuilder(context, MarksyDatabase::class.java, name)
            .addMigrations(MarksyDatabase.MIGRATION_8_9).allowMainThreadQueries().build()
        try {
            val captureDao = migrated.captureDao()
            val id = runBlocking { captureDao.insertCandidate(candidate(captureDao.insertEvidence(evidence()))) }
            assertEquals("ABC", runBlocking { captureDao.candidate(id) }!!.symbol)
            runBlocking { captureDao.insertWorkflow(workflow()) }
            assertEquals(-1L, runBlocking { captureDao.insertWorkflow(workflow()) })
        } finally {
            migrated.close()
            context.deleteDatabase(name)
        }
    }
}
