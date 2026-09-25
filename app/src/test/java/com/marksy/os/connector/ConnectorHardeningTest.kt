package com.marksy.os.connector

import androidx.room.Room
import com.marksy.os.data.MetricsRecorder
import com.marksy.os.data.local.MarksyDatabase
import com.marksy.os.intelligence.EventIntelligencePipeline
import com.marksy.os.intelligence.LocationMemory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.json.JSONException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.ZoneOffset

/** EPIC-021 pre-device hardening: provider edges, worker isolation/retry policy, status display, cross-source limits. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ConnectorHardeningTest {
    private lateinit var db: MarksyDatabase
    private val t0 = 1_790_000_000_000L
    private val hour = 3_600_000L
    private var now = t0
    private lateinit var pipeline: IngestionPipeline
    private lateinit var store: PrefsSyncStore
    private lateinit var syncer: ConnectorSyncer

    @Before
    fun setUp() {
        val app = RuntimeEnvironment.getApplication()
        app.getSharedPreferences("marksy_connector_sync", 0).edit().clear().commit()
        db = Room.inMemoryDatabaseBuilder(app, MarksyDatabase::class.java).allowMainThreadQueries().build()
        pipeline = IngestionPipeline(
            db.notificationEventDao(), db.connectorDao(), MetricsRecorder(db.metricsDao(), { now }, { ZoneOffset.UTC }), { emptyList() },
            null, EventIntelligencePipeline(db.notificationEventDao(), ZoneOffset.UTC), clock = { now }
        )
        store = PrefsSyncStore(app)
        syncer = ConnectorSyncer(pipeline, store) { now }
    }

    @After
    fun tearDown() = db.close()

    private class FakeCalendar(var instances: List<CalendarConnector.Instance>, var permission: Boolean = true) : CalendarConnector.CalendarSource {
        override fun hasPermission() = permission
        override fun instances(from: Long, to: Long, limit: Int) = instances.filter { it.end >= from && it.begin <= to }.take(limit)
    }

    private fun inst(
        id: Long, begin: Long, title: String? = "Design review", location: String? = null, organizer: String? = "lead@corp.com",
        end: Long = begin + hour, allDay: Boolean = false, declined: Boolean = false
    ) = CalendarConnector.Instance(id, begin, end, title, location, organizer, "Work", allDay = allDay, canceled = false, declined = declined)

    private fun calendar(source: FakeCalendar) = CalendarConnector(source, { now }, { ZoneOffset.UTC })
    private fun rows() = runBlocking { db.notificationEventDao().findInRange(0, Long.MAX_VALUE, 1000) }
    private fun sync(c: SyncConnector) = runBlocking { syncer.sync(c) }

    private class FakeGmail : GmailApi {
        var failWith: Exception? = null
        var calls = 0
        override suspend fun profileHistoryId(token: String): String { calls++; failWith?.let { throw it }; return "100" }
        override suspend fun recentMessageIds(token: String, query: String, max: Int) = listOf("m1")
        override suspend fun history(token: String, startHistoryId: String): GmailApi.HistoryPage { calls++; failWith?.let { throw it }; throw GmailApi.HttpError(404) }
        override suspend fun message(token: String, id: String) = GmailApi.Message(id, "Team <t@corp.com>", "Invite: Design review", "Wed 10:00 WeWork Baner", 1_790_000_000_000L, listOf("INBOX"))
    }

    private class Tokens(var token: String? = "secret-token-123") : GmailTokenProvider {
        val invalidated = mutableListOf<String>()
        override suspend fun accessToken() = token
        override suspend fun invalidate(token: String) { invalidated += token; this.token = null }
    }

    private fun connector(id: String, pkg: String = "x.$id", onSync: suspend (String?) -> SyncBatch) = object : SyncConnector {
        override val descriptor = ConnectorDescriptor(id, id, "test")
        override val sourcePackage = pkg
        override val sourceName = id
        override fun state() = ConnectorState.ACTIVE
        override suspend fun sync(cursor: String?) = onSync(cursor)
    }

    // ------------------------------------------------------------ calendar

    @Test
    fun calendarNoEventsSucceedsAndPersistsAnEmptyCursor() {
        syncer.setEnabled(CalendarConnector.ID, true)
        assertEquals(ConnectorSyncer.Outcome.Synced(0, 0, 0, 0, 0), sync(calendar(FakeCalendar(emptyList()))))
        val s = store.load(CalendarConnector.ID)
        assertEquals("{}", s.cursor)
        assertEquals(t0, s.lastSuccessAt)
        assertEquals(ConnectorDisplay.ACTIVE, connectorDisplay(ConnectorState.ACTIVE, s))
    }

    @Test
    fun calendarMalformedDeclinedDuplicateAndAllDayInstances() {
        syncer.setEnabled(CalendarConnector.ID, true)
        val source = FakeCalendar(listOf(
            inst(1, t0 + hour, title = null),
            inst(2, t0 + 2 * hour, title = "   "),
            inst(3, t0 + 3 * hour, declined = true),
            inst(4, t0 + 4 * hour, end = t0 + 3 * hour),
            inst(5, t0 + 5 * hour, "Standup"), inst(5, t0 + 5 * hour, "Standup"),
            inst(6, t0 + 24 * hour, "Holiday", allDay = true, end = t0 + 48 * hour)
        ))
        val r = sync(calendar(source)) as ConnectorSyncer.Outcome.Synced
        assertEquals(4, r.added)
        assertEquals(listOf("(No title)", "(No title)", "Holiday", "Standup"), rows().map { it.title }.sorted())
        assertTrue(rows().first { it.title == "Holiday" }.body.contains("(all day)"))
    }

    @Test
    fun calendarCapTieAtTheHorizonIsNotReportedAsADeletion() {
        syncer.setEnabled(CalendarConnector.ID, true)
        val max = CalendarConnector.MAX_INSTANCES
        val many = (0 until max).map { inst(1_000L + it, t0 + hour + it * 60_000L, "E$it") }
        val source = FakeCalendar(many)
        sync(calendar(source))
        // A new instance sharing the last begin time pushes the known one past the cap.
        val last = many.last()
        source.instances = many.dropLast(1) + inst(9_999, last.begin, "Tie") + last
        val r = sync(calendar(source)) as ConnectorSyncer.Outcome.Synced
        assertEquals(0, r.removed)
        assertTrue(rows().none { it.lifecycleState == "RESOLVED" })
    }

    @Test
    fun calendarAttendeeOrganizerAndTitleNamesNeverBecomeLocations() {
        syncer.setEnabled(CalendarConnector.ID, true)
        sync(calendar(FakeCalendar(listOf(
            inst(1, t0 + hour, "Lunch with Priya Sharma", organizer = "Priya Sharma"),
            inst(2, t0 + 2 * hour, "1:1", location = "Sharma, Priya", organizer = "Rahul Verma"),
            inst(3, t0 + 3 * hour, "Trip to Goa, India")
        ))))
        assertEquals(3, rows().size)
        rows().forEach { assertNull(it.title, LocationMemory.extract(it)) }
    }

    @Test
    fun calendarPermissionGrantedAfterDenialRecoversAndDisplayFollows() {
        syncer.setEnabled(CalendarConnector.ID, true)
        val source = FakeCalendar(listOf(inst(1, t0 + hour)), permission = false)
        assertEquals(ConnectorSyncer.Outcome.Skipped(ConnectorState.NEEDS_PERMISSION), sync(calendar(source)))
        assertEquals(ConnectorDisplay.PERMISSION_REQUIRED, connectorDisplay(calendar(source).state(), store.load(CalendarConnector.ID)))
        source.permission = true
        assertEquals(1, (sync(calendar(source)) as ConnectorSyncer.Outcome.Synced).added)
        val s = store.load(CalendarConnector.ID)
        assertNull(s.lastError)
        assertEquals(ConnectorDisplay.ACTIVE, connectorDisplay(calendar(source).state(), s))
    }

    // ------------------------------------------------------------ gmail

    @Test
    fun gmailAuthFailuresNeverCallTheApiWithoutATokenAndMapToAuthRequired() {
        syncer.setEnabled(GmailConnector.ID, true)
        val api = FakeGmail()
        val none = Tokens(token = null)
        assertEquals(ConnectorException.Kind.AUTH, (sync(GmailConnector(api, none)) as ConnectorSyncer.Outcome.Failed).kind)
        assertEquals(0, api.calls)
        assertEquals(ConnectorDisplay.AUTH_REQUIRED, connectorDisplay(ConnectorState.ACTIVE, store.load(GmailConnector.ID)))

        val revoked = Tokens()
        api.failWith = GmailApi.HttpError(403)
        val f = sync(GmailConnector(api, revoked)) as ConnectorSyncer.Outcome.Failed
        assertEquals(ConnectorException.Kind.AUTH, f.kind)
        assertFalse(f.retryable)
        assertEquals(listOf("secret-token-123"), revoked.invalidated)
        // The token never reaches persisted status or stored rows.
        val raw = RuntimeEnvironment.getApplication().getSharedPreferences("marksy_connector_sync", 0).all.toString()
        assertFalse(raw.contains("secret-token"))
        assertTrue(rows().none { it.body.contains("secret-token") || it.title.contains("secret-token") })
    }

    @Test
    fun gmailProviderErrorsMapToRetryableOrNot() {
        syncer.setEnabled(GmailConnector.ID, true)
        val cases = mapOf(
            GmailApi.HttpError(429) to ConnectorException.Kind.TRANSIENT,
            GmailApi.HttpError(503) to ConnectorException.Kind.TRANSIENT,
            GmailApi.HttpError(400) to ConnectorException.Kind.UNAVAILABLE,
            JSONException("bad") to ConnectorException.Kind.MALFORMED
        )
        cases.forEach { (error, kind) ->
            val api = FakeGmail().apply { failWith = error }
            val f = sync(GmailConnector(api, Tokens())) as ConnectorSyncer.Outcome.Failed
            assertEquals(error.toString(), kind, f.kind)
            assertTrue(f.retryable)
        }
        assertEquals(4, store.load(GmailConnector.ID).consecutiveFailures)
        assertEquals(ConnectorDisplay.ERROR, connectorDisplay(ConnectorState.ACTIVE, store.load(GmailConnector.ID)))
        assertNull(store.load(GmailConnector.ID).cursor)
    }

    @Test
    fun registeredGmailStaysNotConfiguredAndProducesNoData() {
        val gmail = SyncConnectors.all(RuntimeEnvironment.getApplication()).first { it.descriptor.id == GmailConnector.ID }
        syncer.setEnabled(GmailConnector.ID, true)
        val outcome = runBlocking { ConnectorSyncWorker.syncAll(syncer, listOf(gmail)) }
        assertEquals(listOf(ConnectorSyncer.Outcome.Skipped(ConnectorState.NOT_CONFIGURED)), outcome)
        assertTrue(rows().isEmpty())
        assertEquals(ConnectorDisplay.NOT_CONFIGURED, connectorDisplay(gmail.state(), store.load(GmailConnector.ID)))
    }

    // ------------------------------------------------------------ worker

    @Test
    fun oneCrashingConnectorIsIsolatedFromTheOthers() {
        val crashing = object : SyncConnector {
            override val descriptor: ConnectorDescriptor get() = throw IllegalStateException("broken registry entry")
            override val sourcePackage = "x.crash"
            override val sourceName = "crash"
            override fun state() = ConnectorState.ACTIVE
            override suspend fun sync(cursor: String?) = SyncBatch(emptyList(), cursor = null)
        }
        val throwing = connector("throwing") { throw RuntimeException("provider exploded") }
        val healthy = connector("healthy") { SyncBatch(listOf(SourceRecord("r1", "Hello", "World body", t0)), cursor = "c1") }
        listOf("throwing", "healthy").forEach { syncer.setEnabled(it, true) }
        val outcomes = runBlocking { ConnectorSyncWorker.syncAll(syncer, listOf(crashing, throwing, healthy)) }
        assertEquals(ConnectorSyncer.Outcome.Failed(ConnectorException.Kind.TRANSIENT), outcomes[0])
        assertEquals(ConnectorSyncer.Outcome.Failed(ConnectorException.Kind.TRANSIENT), outcomes[1])
        assertTrue(outcomes[2] is ConnectorSyncer.Outcome.Synced)
        assertEquals("c1", store.load("healthy").cursor)
        assertEquals(1, store.load("throwing").consecutiveFailures)
        assertTrue(ConnectorSyncWorker.shouldRetry(outcomes, attempt = 0))
    }

    @Test
    fun retryPolicyIsBoundedAndSkipsNonRetryableFailures() {
        val transient = ConnectorSyncer.Outcome.Failed(ConnectorException.Kind.TRANSIENT)
        val auth = ConnectorSyncer.Outcome.Failed(ConnectorException.Kind.AUTH)
        val perm = ConnectorSyncer.Outcome.Failed(ConnectorException.Kind.PERMISSION)
        val ok = ConnectorSyncer.Outcome.Synced(0, 0, 0, 0, 0)
        assertTrue(ConnectorSyncWorker.shouldRetry(listOf(ok, transient), 2))
        assertFalse(ConnectorSyncWorker.shouldRetry(listOf(ok, transient), 3))
        assertFalse(ConnectorSyncWorker.shouldRetry(listOf(auth, perm, ok), 0))
        assertFalse(ConnectorSyncWorker.shouldRetry(listOf(ConnectorSyncer.Outcome.Skipped(ConnectorState.DISCONNECTED)), 0))
        assertFalse(ConnectorSyncWorker.shouldRetry(emptyList(), 0))
    }

    @Test
    fun cancellationPropagatesAndLeavesPersistedStateUntouched() {
        syncer.setEnabled("slow", true)
        val before = store.load("slow")
        try {
            runBlocking { ConnectorSyncWorker.syncAll(syncer, listOf(connector("slow") { throw CancellationException("worker stopped") })) }
            fail("cancellation must propagate")
        } catch (_: CancellationException) { }
        assertEquals(before, store.load("slow"))
    }

    @Test
    fun disabledConnectorIsNeverCalledAndRepeatedRunsAreIdempotent() {
        var calls = 0
        val c = connector("rep") { calls++; SyncBatch(listOf(SourceRecord("r1", "Hello", "World body", t0)), cursor = "c$calls") }
        assertEquals(listOf(ConnectorSyncer.Outcome.Skipped(ConnectorState.DISCONNECTED)), runBlocking { ConnectorSyncWorker.syncAll(syncer, listOf(c)) })
        assertEquals(0, calls)
        syncer.setEnabled("rep", true)
        repeat(3) { runBlocking { ConnectorSyncWorker.syncAll(syncer, listOf(c)) } }
        assertEquals(3, calls)
        assertEquals(1, rows().size)
        // "Process death": a new store instance sees the durable cursor from the last run.
        assertEquals("c3", PrefsSyncStore(RuntimeEnvironment.getApplication()).load("rep").cursor)
    }

    // ------------------------------------------------------------ cross-source

    /**
     * Documented limitation: cross-source dedup exists only for money events (txn ref / amount+direction).
     * A calendar reminder notification, the Calendar provider record and a Gmail invite for the same meeting
     * stay three separate rows, each attributable to its own source.
     */
    @Test
    fun nonMoneyCrossSourceEventsAreNotMergedDocumentedLimitation() {
        runBlocking {
            pipeline.ingest(RawCapture(ConnectorRegistry.NOTIFICATIONS, "com.google.android.calendar", "Calendar", "n1", "Design review", "10:00–11:00 · WeWork Baner", t0))
        }
        syncer.setEnabled(CalendarConnector.ID, true)
        sync(calendar(FakeCalendar(listOf(inst(1, t0 + hour, "Design review", location = "WeWork Baner")))))
        syncer.setEnabled(GmailConnector.ID, true)
        sync(GmailConnector(FakeGmail(), Tokens()))
        val r = rows()
        assertEquals(setOf("com.google.android.calendar", "com.android.providers.calendar", "com.google.android.gm"), r.map { it.sourcePackage }.toSet())
        assertEquals(3, r.size)
        assertTrue(r.all { it.duplicateOfId == null })
    }
}
