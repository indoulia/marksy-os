package com.marksy.os.capture

import androidx.room.Room
import com.marksy.os.data.local.CaptureDao
import com.marksy.os.data.local.MarksyDatabase
import com.marksy.os.data.local.NotificationEventEntity
import com.marksy.os.notification.OriginalAppLauncher.Opened
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
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
class WorkflowSourceOpenerTest {
    private lateinit var db: MarksyDatabase
    private lateinit var dao: CaptureDao
    private lateinit var gateway: CaptureGateway
    private var result = Opened.CONTENT_INTENT
    private val opened = mutableListOf<Pair<String, String>>()
    private lateinit var opener: WorkflowSourceOpener

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), MarksyDatabase::class.java).allowMainThreadQueries().build()
        dao = db.captureDao()
        gateway = CaptureGateway(dao, CaptureSourceRegistry(capturePackages = { setOf("com.research.app") }, displayName = { "App $it" }))
        opener = WorkflowSourceOpener(gateway, dao) { pkg, key -> opened += pkg to key; result }
    }

    @After
    fun tearDown() = db.close()

    private suspend fun workflow(): Long = gateway.onNotificationStored(
        NotificationEventEntity(
            id = 7, sourcePackage = "com.research.app", sourceName = "Research", sourceKey = "k1", eventFingerprint = "f", title = "ABC Research",
            body = "New recommendation is live. Tap to view", postedAt = System.currentTimeMillis(), category = "MARKET", priority = 1, confidence = 1f, isTrading = false
        )
    )!!

    private suspend fun state(id: Long) = dao.workflow(id)!!.state

    @Test
    fun viewTipOpensTheNotificationsOwnScreen() = runBlocking {
        val id = workflow()
        assertEquals(Opened.CONTENT_INTENT, opener.view(id))
        assertEquals(listOf("com.research.app" to "k1"), opened)
        assertEquals(WorkflowState.USER_OPENED_SOURCE.name, state(id))
        assertEquals(true, dao.workflow(id)!!.openedViaDeepLink)
    }

    @Test
    fun aMissingLinkFallsBackToTheLauncherAndIsRecorded() = runBlocking {
        val id = workflow()
        result = Opened.LAUNCHER
        assertEquals(Opened.LAUNCHER, opener.view(id))
        assertEquals(WorkflowState.USER_OPENED_SOURCE.name, state(id))
        assertEquals(false, dao.workflow(id)!!.openedViaDeepLink)
    }

    @Test
    fun anUnavailableSourceIsRecordedAndCanBeRetried() = runBlocking {
        val id = workflow()
        result = Opened.UNAVAILABLE
        assertEquals(Opened.UNAVAILABLE, opener.view(id))
        assertEquals(WorkflowState.SOURCE_UNAVAILABLE.name, state(id))
        assertEquals(CaptureFailure.SOURCE_UNAVAILABLE, dao.workflow(id)!!.failureCode)
        assertTrue(gateway.captureRequested(id))
    }

    @Test
    fun duringAnAuthorizedCaptureTheOpenIsRecordedWithoutMovingTheWorkflow() = runBlocking {
        val id = workflow()
        gateway.captureRequested(id)
        gateway.captureAuthorized(id)
        result = Opened.LAUNCHER
        assertEquals(Opened.LAUNCHER, opener.openForCapture(id))
        assertEquals(WorkflowState.CAPTURE_AUTHORIZED.name, state(id))
        assertEquals(false, dao.workflow(id)!!.openedViaDeepLink)
        result = Opened.UNAVAILABLE
        assertEquals(Opened.UNAVAILABLE, opener.openForCapture(id))
        assertEquals(WorkflowState.CAPTURE_AUTHORIZED.name, state(id))
        assertNull(dao.lastFailureCode())
    }

    @Test
    fun aClosedOrUnknownWorkflowOpensNothing() = runBlocking {
        val id = workflow()
        gateway.sweep(System.currentTimeMillis() + CaptureLifecycle.WORKFLOW_TTL_MS + 1)
        assertEquals(Opened.UNAVAILABLE, opener.view(id))
        assertEquals(Opened.UNAVAILABLE, opener.view(999))
        assertTrue(opened.isEmpty())
    }
}
