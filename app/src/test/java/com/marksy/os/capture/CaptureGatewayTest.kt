package com.marksy.os.capture

import androidx.room.Room
import com.marksy.os.data.local.CaptureDao
import com.marksy.os.data.local.DeliveryState
import com.marksy.os.data.local.MarksyDatabase
import com.marksy.os.data.local.NotificationEventEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CaptureGatewayTest {
    private lateinit var db: MarksyDatabase
    private lateinit var dao: CaptureDao
    private lateinit var gateway: CaptureGateway
    private var now = 1_790_000_000_000L
    private val logs = mutableListOf<String>()
    private var queued = 0
    private val tip = "BUY ABC @ 500 Target 650 SL 470"
    private val hour = 60 * 60 * 1000L

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), MarksyDatabase::class.java).allowMainThreadQueries().build()
        dao = db.captureDao()
        val registry = CaptureSourceRegistry(capturePackages = { setOf("com.research.app") }, displayName = { "App $it" })
        gateway = CaptureGateway(dao, registry, clock = { now }, log = { logs += it }, onDeliveryQueued = { queued++ })
    }

    @After
    fun tearDown() = db.close()

    private fun teaser(key: String = "k1", body: String = "New recommendation is live. Tap to view") = NotificationEventEntity(
        id = 7, sourcePackage = "com.research.app", sourceName = "Research", sourceKey = key, eventFingerprint = "f", title = "ABC Research",
        body = body, postedAt = now, category = "MARKET", priority = 1, confidence = 1f, isTrading = false
    )

    private suspend fun submit(
        hash: String = "h1", text: String? = tip, method: CaptureMethod = CaptureMethod.USER_SHARED_IMAGE, hint: String? = null,
        verified: Boolean = false, workflowId: Long? = null
    ) = gateway.submitRecognized(method, hint, verified, hash, text, 0.95f, workflowId)

    private suspend fun candidateId(outcome: CaptureOutcome) = (outcome as CaptureOutcome.Candidate).id

    private suspend fun authorizedWorkflow(): Long {
        val id = gateway.onNotificationStored(teaser())!!
        assertTrue(gateway.captureRequested(id))
        assertTrue(gateway.captureAuthorized(id))
        return id
    }

    private suspend fun workflowState(id: Long) = dao.workflow(id)!!.state

    @Test
    fun provenanceIsKeptPerMethod() = runBlocking {
        CaptureMethod.entries.forEachIndexed { i, method ->
            val c = dao.candidate(candidateId(submit(hash = "h$i", method = method)))!!
            assertEquals(method.name, c.method)
            assertEquals(method.name, dao.evidence(c.evidenceId)!!.method)
        }
        val shared = dao.candidate(candidateId(submit(hash = "s", hint = "com.research.app", verified = true)))!!
        assertEquals(
            CaptureProvenance(CaptureMethod.USER_SHARED_IMAGE, "com.research.app", "App com.research.app", true, now, "sha256:s"),
            shared.provenance
        )
        val picked = dao.candidate(candidateId(submit(hash = "p", method = CaptureMethod.USER_SELECTED_IMAGE)))!!
        assertNull(picked.sourcePackage)
        assertFalse(picked.sourceVerified)

        val workflowId = authorizedWorkflow()
        val frame = dao.candidate(candidateId(submit(hash = "f", method = CaptureMethod.MEDIA_PROJECTION, workflowId = workflowId)))!!
        assertEquals(
            CaptureProvenance(CaptureMethod.MEDIA_PROJECTION, "com.research.app", "App com.research.app", true, now, "sha256:f", 7, workflowId),
            frame.provenance
        )
    }

    @Test
    fun workflowKeepsTheNotificationsPackageCase() = runBlocking {
        // Device 2026-10-04: Moneycontrol is `com.divum.MoneyControl`; a lowercased name can't be launched.
        val id = gateway.onNotificationStored(teaser().copy(sourcePackage = "com.Research.App"))!!
        assertEquals("com.Research.App", dao.workflow(id)!!.sourcePackage)
    }

    // A gallery or screenshot UI is never the source: an unsupported hint leaves the source for the user to pick.
    @Test
    fun unsupportedSourceIsDropped() = runBlocking {
        val c = dao.candidate(candidateId(submit(hint = "com.google.android.apps.photos", verified = true)))!!
        assertNull(c.sourcePackage)
        assertFalse(c.sourceVerified)
        assertTrue(logs.any { "unsupported-source" in it })
    }

    @Test
    fun lifecycleTransitions() = runBlocking {
        val kept = submit(hash = "a") as CaptureOutcome.Candidate
        assertEquals(CaptureState.EXTRACTED, kept.state)
        val keptRow = dao.candidate(kept.id)!!
        assertEquals(CaptureState.EXTRACTED.name, dao.evidence(keptRow.evidenceId)!!.state)

        val edited = TipFields(" abc ", TipSide.BUY, 500.0, 660.0, 470.0)
        assertEquals(CaptureState.ACCEPTED, gateway.review(kept.id, edited, "com.research.app", ReviewDecision.KEEP))
        val accepted = dao.candidate(kept.id)!!
        assertEquals(TipFields("ABC", TipSide.BUY, 500.0, 660.0, 470.0), accepted.fields)
        assertEquals(DeliveryState.NOT_APPLICABLE.name, accepted.deliveryState)
        assertEquals(now, accepted.reviewedAt)
        assertFalse(accepted.sourceVerified)
        assertEquals(CaptureState.ACCEPTED.name, dao.evidence(accepted.evidenceId)!!.state)
        // Terminal: a second review changes nothing.
        assertNull(gateway.review(kept.id, edited, null, ReviewDecision.REJECT))

        val rejected = candidateId(submit(hash = "b"))
        assertEquals(CaptureState.REJECTED, gateway.review(rejected, TipFields(), null, ReviewDecision.REJECT))
        assertNull(dao.candidate(rejected)!!.extractedText)

        val sent = candidateId(submit(hash = "c", hint = "com.research.app", verified = true))
        gateway.review(sent, TipFields("ABC", TipSide.BUY, 500.0, 650.0, 470.0), null, ReviewDecision.SEND)
        assertEquals(DeliveryState.PENDING.name, dao.candidate(sent)!!.deliveryState)
        assertEquals(1, queued)

        // A source the user picked is only a label: it never queues and never becomes verified.
        val picked = candidateId(submit(hash = "p"))
        gateway.review(picked, TipFields("ABC", TipSide.BUY, 500.0, 650.0, 470.0), "com.research.app", ReviewDecision.SEND)
        assertEquals(DeliveryState.NOT_APPLICABLE.name, dao.candidate(picked)!!.deliveryState)
        assertFalse(dao.candidate(picked)!!.sourceVerified)
        assertEquals(1, queued)

        // Send without an allow-listed source is accepted but stays on the phone.
        val local = candidateId(submit(hash = "d"))
        gateway.review(local, TipFields("ABC", TipSide.BUY, 500.0, 650.0, 470.0), null, ReviewDecision.SEND)
        assertEquals(DeliveryState.NOT_APPLICABLE.name, dao.candidate(local)!!.deliveryState)
        assertTrue(dao.candidate(local)!!.userChoseSend)
    }

    @Test
    fun notificationToCapture() = runBlocking {
        assertNull(gateway.onNotificationStored(teaser(key = "complete", body = tip)))
        val id = gateway.onNotificationStored(teaser())!!
        assertEquals(WorkflowState.NEEDS_SOURCE_VIEW.name, workflowState(id))
        assertTrue(gateway.sourceOpened(id, viaDeepLink = true))
        assertEquals(WorkflowState.USER_OPENED_SOURCE.name, workflowState(id))
        assertTrue(dao.workflow(id)!!.openedViaDeepLink!!)
        assertTrue(gateway.captureRequested(id))
        assertTrue(gateway.captureAuthorized(id))

        val outcome = submit(method = CaptureMethod.MEDIA_PROJECTION, workflowId = id) as CaptureOutcome.Candidate
        assertEquals(WorkflowState.REVIEW_REQUIRED.name, workflowState(id))
        assertEquals(outcome.id, dao.workflow(id)!!.candidateId)
        gateway.review(outcome.id, dao.candidate(outcome.id)!!.fields, null, ReviewDecision.SEND)
        assertEquals(WorkflowState.ACCEPTED.name, workflowState(id))
        // The verified notification source wins over anything picked in review.
        assertEquals("com.research.app", dao.candidate(outcome.id)!!.sourcePackage)
        assertEquals(DeliveryState.PENDING.name, dao.candidate(outcome.id)!!.deliveryState)
    }

    @Test
    fun deniedCaptureCanBeRetried() = runBlocking {
        val id = gateway.onNotificationStored(teaser())!!
        assertTrue(gateway.captureRequested(id))
        assertEquals(CaptureOutcome.Failed(CaptureFailure.CAPTURE_DENIED), gateway.submitFailure(CaptureMethod.MEDIA_PROJECTION, id, CaptureFailure.CAPTURE_DENIED))
        assertEquals(WorkflowState.CAPTURE_DENIED.name, workflowState(id))
        assertEquals(CaptureFailure.CAPTURE_DENIED, dao.workflow(id)!!.failureCode)
        assertEquals(CaptureFailure.CAPTURE_DENIED, dao.lastFailureCode())
        assertTrue(gateway.captureRequested(id))
        // An unknown code is stored as the generic fixed code, never as given.
        gateway.submitFailure(CaptureMethod.USER_SELECTED_IMAGE, null, "boom: ABC 500")
        assertEquals(CaptureFailure.CAPTURE_FAILED, dao.lastFailureCode())
    }

    @Test
    fun protectedScreen() = runBlocking {
        val id = authorizedWorkflow()
        gateway.submitFailure(CaptureMethod.MEDIA_PROJECTION, id, CaptureFailure.PROTECTED_SCREEN)
        assertEquals(WorkflowState.PROTECTED_SCREEN.name, workflowState(id))
        assertTrue(gateway.captureRequested(id))
    }

    @Test
    fun missingLinkOpensViaLauncher() = runBlocking {
        val id = gateway.onNotificationStored(teaser())!!
        assertTrue(gateway.sourceOpened(id, viaDeepLink = false))
        assertEquals(WorkflowState.USER_OPENED_SOURCE.name, workflowState(id))
        assertFalse(dao.workflow(id)!!.openedViaDeepLink!!)
        assertTrue(gateway.captureRequested(id))
        // Opening the source again during an authorized session only records how it opened.
        assertTrue(gateway.captureAuthorized(id))
        assertTrue(gateway.sourceOpened(id, viaDeepLink = true))
        assertEquals(WorkflowState.CAPTURE_AUTHORIZED.name, workflowState(id))
    }

    @Test
    fun duplicateNotificationMakesOneWorkflow() = runBlocking {
        val id = gateway.onNotificationStored(teaser())!!
        assertNull(gateway.onNotificationStored(teaser()))
        assertEquals(listOf(id), dao.observeOpenWorkflows().first().map { it.id })
    }

    @Test
    fun duplicateCapture() = runBlocking {
        val first = candidateId(submit(hash = "same"))
        now += 23 * hour
        assertEquals(CaptureOutcome.Duplicate(first), submit(hash = "same"))
        now += 2 * hour
        assertTrue(submit(hash = "same") is CaptureOutcome.Candidate)

        val id = authorizedWorkflow()
        val framed = candidateId(submit(hash = "frame1", method = CaptureMethod.MEDIA_PROJECTION, workflowId = id))
        assertEquals(CaptureOutcome.Duplicate(framed), submit(hash = "frame2", method = CaptureMethod.MEDIA_PROJECTION, workflowId = id))
    }

    // A frame already captured still ends its session: the workflow follows the existing candidate, never stuck authorized.
    @Test
    fun duplicateFrameClosesTheWorkflow() = runBlocking {
        val shared = candidateId(submit(hash = "same"))
        val id = authorizedWorkflow()
        assertEquals(CaptureOutcome.Duplicate(shared), submit(hash = "same", method = CaptureMethod.MEDIA_PROJECTION, workflowId = id))
        assertEquals(WorkflowState.REVIEW_REQUIRED.name, workflowState(id))
        assertEquals(shared, dao.workflow(id)!!.candidateId)

        gateway.review(shared, dao.candidate(shared)!!.fields, null, ReviewDecision.KEEP)
        val later = gateway.onNotificationStored(teaser(key = "k2"))!!
        gateway.captureRequested(later)
        gateway.captureAuthorized(later)
        submit(hash = "same", method = CaptureMethod.MEDIA_PROJECTION, workflowId = later)
        assertEquals(WorkflowState.ACCEPTED.name, workflowState(later))
    }

    // A session lost to process death is failed on the next "Capture tip", not only by the daily sweep.
    @Test
    fun deadSessionIsFailedOnRetry() = runBlocking {
        val id = authorizedWorkflow()
        assertFalse(gateway.captureRequested(id))
        assertTrue(gateway.captureRequested(id, sessionDead = true))
        assertEquals(WorkflowState.CAPTURE_REQUESTED.name, workflowState(id))
        assertTrue(logs.any { "workflow=$id ${CaptureFailure.STALE_SESSION}" in it })
    }

    @Test
    fun expiredWorkflowAndSweep() = runBlocking {
        val expiring = gateway.onNotificationStored(teaser(key = "old"))!!
        val stale = authorizedWorkflow()
        val unreviewed = candidateId(submit(hash = "u"))

        now += 11 * 60 * 1000L
        gateway.sweep(now)
        assertEquals(WorkflowState.CAPTURE_FAILED.name, workflowState(stale))
        assertEquals(CaptureFailure.STALE_SESSION, dao.workflow(stale)!!.failureCode)

        now += 12 * hour
        assertFalse(gateway.captureRequested(expiring))
        assertEquals(WorkflowState.EXPIRED.name, workflowState(expiring))
        gateway.sweep(now)
        assertEquals(WorkflowState.EXPIRED.name, workflowState(stale))

        now += 3 * 24 * hour
        gateway.sweep(now)
        assertEquals(CaptureState.EXPIRED.name, dao.candidate(unreviewed)!!.state)
        assertNull(gateway.review(unreviewed, TipFields(), null, ReviewDecision.KEEP))
    }

    @Test
    fun oneTimeCodeDropsTheCapture() = runBlocking {
        val id = authorizedWorkflow()
        val outcome = submit(text = "Your OTP is 482913. Do not share.", method = CaptureMethod.MEDIA_PROJECTION, workflowId = id)
        assertEquals(CaptureOutcome.Failed(CaptureFailure.ONE_TIME_CODE), outcome)
        assertEquals(CaptureFailure.ONE_TIME_CODE, dao.lastFailureCode())
        assertEquals(WorkflowState.CAPTURE_FAILED.name, workflowState(id))
        assertTrue(dao.observeToReview().first().isEmpty())
    }

    @Test
    fun logsCarryNoBodyOrRecognizedText() = runBlocking {
        val id = gateway.onNotificationStored(teaser(body = "New tip on SECRETCO, tap to view"))!!
        gateway.sourceOpened(id, true)
        gateway.captureRequested(id)
        gateway.captureAuthorized(id)
        val c = candidateId(submit(hash = "cafe01", text = "BUY SECRETCO @ 917 Target 999 SL 888", method = CaptureMethod.MEDIA_PROJECTION, workflowId = id))
        submit(hash = "cafe01", text = "BUY SECRETCO @ 917 Target 999 SL 888")
        submit(hash = "cafe02", text = "Your OTP is 482913", hint = "com.secret.gallery", verified = true)
        gateway.review(c, dao.candidate(c)!!.fields, null, ReviewDecision.SEND)
        gateway.sweep(now)
        assertTrue(logs.isNotEmpty())
        val forbidden = listOf("SECRETCO", "917", "999", "888", "482913", "cafe0", "com.secret.gallery", "tap to view")
        logs.forEach { line -> forbidden.forEach { assertFalse("'$it' leaked in: $line", line.contains(it, ignoreCase = true)) } }
    }
}
