package com.marksy.os.connector

import androidx.room.Room
import com.marksy.os.data.MetricsRecorder
import com.marksy.os.data.local.ConnectorEventEntity
import com.marksy.os.data.local.MarksyDatabase
import com.marksy.os.intelligence.EventIntelligencePipeline
import com.marksy.os.intelligence.LocationMemory
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
import java.io.IOException
import java.time.ZoneOffset

/** EPIC-021: provider boundaries are faked; everything from ingestion down is the real pipeline and Room. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ConnectorSyncTest {
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
        var fail: Exception? = null
        override fun hasPermission() = permission
        override fun instances(from: Long, to: Long, limit: Int): List<CalendarConnector.Instance> {
            fail?.let { throw it }
            return instances.filter { it.end >= from && it.begin <= to }.take(limit)
        }
    }

    private fun inst(id: Long, begin: Long, title: String = "Design review", location: String? = "WeWork Baner", canceled: Boolean = false) =
        CalendarConnector.Instance(id, begin, begin + hour, title, location, "lead@corp.com", "Work", allDay = false, canceled = canceled, declined = false)

    private fun calendar(source: FakeCalendar) = CalendarConnector(source, { now }, { ZoneOffset.UTC })
    private fun rows() = runBlocking { db.notificationEventDao().findInRange(0, Long.MAX_VALUE, 1000) }
    private fun sync(c: SyncConnector) = runBlocking { syncer.sync(c) }

    @Test
    fun disabledConnectorDoesNothingAndPermissionDeniedIsReported() {
        val source = FakeCalendar(listOf(inst(1, t0 + 2 * hour)), permission = false)
        assertEquals(ConnectorSyncer.Outcome.Skipped(ConnectorState.DISCONNECTED), sync(calendar(source)))
        syncer.setEnabled(CalendarConnector.ID, true)
        assertEquals(ConnectorSyncer.Outcome.Skipped(ConnectorState.NEEDS_PERMISSION), sync(calendar(source)))
        assertEquals("NEEDS_PERMISSION", store.load(CalendarConnector.ID).lastError)
        assertTrue(rows().isEmpty())
        // Permission revoked mid-sync surfaces as a non-retryable permission failure.
        source.permission = true
        source.fail = SecurityException("revoked")
        val failed = sync(calendar(source)) as ConnectorSyncer.Outcome.Failed
        assertEquals(ConnectorException.Kind.PERMISSION, failed.kind)
        assertFalse(failed.retryable)
    }

    @Test
    fun calendarIncrementalSyncHandlesNewUpdatedDeletedAndPersistsCursorAcrossRestart() {
        syncer.setEnabled(CalendarConnector.ID, true)
        val source = FakeCalendar(listOf(inst(1, t0 + 2 * hour), inst(2, t0 + 26 * hour, "Dentist", null)))
        assertEquals(ConnectorSyncer.Outcome.Synced(2, 0, 0, 0, 0), sync(calendar(source)))
        val review = rows().first { it.title == "Design review" }
        assertEquals("com.android.providers.calendar", review.sourcePackage)
        assertEquals("${CalendarConnector.ID}:1@${t0 + 2 * hour}", review.sourceKey)
        assertTrue(review.body.contains("Location: WeWork Baner"))
        assertEquals("WeWork Baner", LocationMemory.extract(review)?.label)
        assertEquals(t0, review.postedAt)

        // Nothing changed: no re-import.
        now += hour
        assertEquals(ConnectorSyncer.Outcome.Synced(0, 0, 0, 0, 0), sync(calendar(source)))

        // "Process restart": fresh syncer + store instances read the persisted cursor.
        syncer = ConnectorSyncer(pipeline, PrefsSyncStore(RuntimeEnvironment.getApplication())) { now }
        source.instances = listOf(inst(1, t0 + 2 * hour, location = "Room 4, Tower B, Baner, Pune"), inst(2, t0 + 26 * hour, "Dentist", null, canceled = true))
        assertEquals(ConnectorSyncer.Outcome.Synced(0, 1, 1, 0, 0), sync(calendar(source)))
        val updated = rows().first { it.title == "Design review" }
        assertEquals(review.id, updated.id)
        assertTrue("update replaces rather than appends", updated.body.contains("Location: Room 4") && !updated.body.contains("WeWork"))
        assertEquals("RESOLVED", rows().first { it.title == "Dentist" }.lifecycleState)
        assertEquals(2, rows().size)
    }

    @Test
    fun recurringInstancesAreDistinctAndOldOnesAgeOutWithoutBeingDeleted() {
        syncer.setEnabled(CalendarConnector.ID, true)
        val weekly = (0..1).map { inst(7, t0 + 2 * hour + it * 7 * 24 * hour, "Weekly sync") }
        val source = FakeCalendar(weekly)
        sync(calendar(source))
        assertEquals(2, rows().count { it.title == "Weekly sync" })
        // Two days later the first instance left the 1-day look-back window: not a deletion.
        now = t0 + 2 * 24 * hour + 3 * hour
        assertEquals(0, (sync(calendar(source)) as ConnectorSyncer.Outcome.Synced).removed)
        assertTrue(rows().none { it.lifecycleState == "RESOLVED" })
    }

    @Test
    fun truncatedProviderResultDoesNotLookLikeDeletions() {
        syncer.setEnabled(CalendarConnector.ID, true)
        val many = (0 until CalendarConnector.MAX_INSTANCES + 5).map { inst(100L + it, t0 + hour + it * 60_000L, "E$it", null) }
        val source = FakeCalendar(many.drop(5))
        sync(calendar(source))
        // Five earlier events appear, so the capped result no longer reaches the last five known instances.
        source.instances = many
        assertEquals(0, (sync(calendar(source)) as ConnectorSyncer.Outcome.Synced).removed)
        assertTrue(rows().none { it.lifecycleState == "RESOLVED" })
        // An instance inside the returned range that really vanished is still detected.
        source.instances = many.drop(1)
        assertEquals(1, (sync(calendar(source)) as ConnectorSyncer.Outcome.Synced).removed)
        assertEquals("E0", rows().single { it.lifecycleState == "RESOLVED" }.title)
    }

    @Test
    fun upcomingEventPrunedByRetentionIsRestoredOnRefresh() {
        syncer.setEnabled(CalendarConnector.ID, true)
        val source = FakeCalendar(listOf(inst(1, t0 + 12 * 24 * hour, "Offsite")))
        sync(calendar(source))
        runBlocking { db.notificationEventDao().deleteAll() }
        now = t0 + CalendarConnector.REFRESH_MS
        assertEquals(1, (sync(calendar(source)) as ConnectorSyncer.Outcome.Synced).added)
        assertEquals("Offsite", rows().single().title)
    }

    @Test
    fun corruptCursorFallsBackToSnapshotAndDuplicatesAreNotReimported() {
        syncer.setEnabled(CalendarConnector.ID, true)
        val source = FakeCalendar(listOf(inst(1, t0 + 2 * hour)))
        sync(calendar(source))
        store.save(store.load(CalendarConnector.ID).copy(cursor = "{not json"))
        assertEquals(ConnectorSyncer.Outcome.Synced(0, 0, 0, 1, 0), sync(calendar(source)))
        assertEquals(1, rows().size)
    }

    private class FakeGmail : GmailApi {
        var messages = mutableMapOf<String, GmailApi.Message>()
        var history: (String) -> GmailApi.HistoryPage = { throw GmailApi.HttpError(404) }
        var failWith: Exception? = null
        var snapshots = 0
        override suspend fun profileHistoryId(token: String): String { failWith?.let { throw it }; return "100" }
        override suspend fun recentMessageIds(token: String, query: String, max: Int): List<String> { snapshots++; return messages.keys.toList() }
        override suspend fun history(token: String, startHistoryId: String): GmailApi.HistoryPage { failWith?.let { throw it }; return history(startHistoryId) }
        override suspend fun message(token: String, id: String) = messages[id] ?: throw GmailApi.HttpError(404)
    }

    private class Tokens(var token: String? = "t1") : GmailTokenProvider {
        val invalidated = mutableListOf<String>()
        override suspend fun accessToken() = token
        override suspend fun invalidate(token: String) { invalidated += token; this.token = null }
    }

    private fun msg(id: String, from: String, subject: String, labels: List<String> = listOf("INBOX")) =
        GmailApi.Message(id, from, subject, "snippet $id", t0 - hour, labels)

    @Test
    fun gmailIsHonestlyNotConfiguredWithoutOAuth() {
        val gmail = GmailConnector(FakeGmail(), tokens = null)
        assertEquals(ConnectorState.NOT_CONFIGURED, gmail.state())
        syncer.setEnabled(GmailConnector.ID, true)
        assertEquals(ConnectorSyncer.Outcome.Skipped(ConnectorState.NOT_CONFIGURED), sync(gmail))
        assertTrue(SyncConnectors.all(RuntimeEnvironment.getApplication()).first { it.descriptor.id == GmailConnector.ID }.state() == ConnectorState.NOT_CONFIGURED)
    }

    @Test
    fun gmailSnapshotThenHistoryIncrementalWithDeletesSpamAndExpiredHistory() {
        syncer.setEnabled(GmailConnector.ID, true)
        val api = FakeGmail().apply {
            messages["a"] = msg("a", "\"Rahul Sharma\" <rahul@x.com>", "Project draft")
            messages["s"] = msg("s", "spam@x.com", "Win", listOf("SPAM"))
        }
        val gmail = GmailConnector(api, Tokens())
        assertEquals(ConnectorSyncer.Outcome.Synced(1, 0, 0, 0, 0), sync(gmail))
        val row = rows().single()
        assertEquals("Rahul Sharma", row.title)
        assertEquals("Project draft\nsnippet a", row.body)
        assertEquals("100", store.load(GmailConnector.ID).cursor)

        api.messages["b"] = msg("b", "bank@x.com", "Statement")
        api.history = { start -> assertEquals("100", start); GmailApi.HistoryPage(listOf("b", "b"), listOf("a"), "120") }
        assertEquals(ConnectorSyncer.Outcome.Synced(1, 0, 1, 0, 0), sync(gmail))
        assertEquals("RESOLVED", rows().first { it.title == "Rahul Sharma" }.lifecycleState)
        assertEquals("120", store.load(GmailConnector.ID).cursor)

        // History id expired (404): bounded re-snapshot; already-stored messages dedup by source key.
        api.history = { throw GmailApi.HttpError(404) }
        val before = api.snapshots
        val r = sync(gmail) as ConnectorSyncer.Outcome.Synced
        assertEquals(before + 1, api.snapshots)
        assertEquals(0, r.added)
        assertEquals(2, rows().size)
    }

    @Test
    fun gmailTokenExpiryNetworkFailureAndRetryKeepTheCursor() {
        syncer.setEnabled(GmailConnector.ID, true)
        val api = FakeGmail().apply { messages["a"] = msg("a", "x@y.com", "Hi") }
        val tokens = Tokens()
        val gmail = GmailConnector(api, tokens)
        sync(gmail)
        val cursor = store.load(GmailConnector.ID).cursor

        api.failWith = GmailApi.HttpError(401)
        val auth = sync(gmail) as ConnectorSyncer.Outcome.Failed
        assertEquals(ConnectorException.Kind.AUTH, auth.kind)
        assertEquals(listOf("t1"), tokens.invalidated)
        assertEquals(cursor, store.load(GmailConnector.ID).cursor)
        // Revoked/expired and not re-authorized: AUTH again, without calling the API.
        assertEquals(ConnectorException.Kind.AUTH, (sync(gmail) as ConnectorSyncer.Outcome.Failed).kind)

        tokens.token = "t2"
        api.failWith = IOException("offline")
        val net = sync(gmail) as ConnectorSyncer.Outcome.Failed
        assertEquals(ConnectorException.Kind.TRANSIENT, net.kind)
        assertTrue(net.retryable)
        assertEquals(3, store.load(GmailConnector.ID).consecutiveFailures)
        assertTrue(runBlocking { db.connectorDao().failuresSince(0, 10) }.any { it.connectorId == GmailConnector.ID && it.type == ConnectorEventEntity.FAILED })

        api.failWith = null
        api.history = { GmailApi.HistoryPage(emptyList(), emptyList(), "130") }
        assertTrue(sync(gmail) is ConnectorSyncer.Outcome.Synced)
        assertEquals(0, store.load(GmailConnector.ID).consecutiveFailures)
        assertEquals("130", store.load(GmailConnector.ID).cursor)
    }

    @Test
    fun malformedProviderPayloadsAreRejectedSafely() {
        val page = HttpGmailApi.parseHistory("""{"history":[{"messagesAdded":[{"message":{"id":"m1"}},{"message":{}}]},{"messagesDeleted":[{"message":{"id":"m0"}}]}],"historyId":"9","nextPageToken":"p"}""")
        assertEquals(listOf("m1"), page.first.addedIds)
        assertEquals(listOf("m0"), page.first.deletedIds)
        assertEquals("p", page.second)
        val m = HttpGmailApi.parseMessage("""{"id":"m1","snippet":"s","internalDate":"1700000000000","labelIds":["INBOX"],"payload":{"headers":[{"name":"From","value":"A <a@b.c>"},{"name":"Subject","value":"Hello"}]}}""")
        assertEquals("Hello", m.subject)
        assertEquals(1_700_000_000_000L, m.internalDate)
        assertEquals(emptyList<String>(), HttpGmailApi.parseIds("{}"))

        syncer.setEnabled(GmailConnector.ID, true)
        val broken = object : SyncConnector {
            override val descriptor = ConnectorDescriptor("broken", "Broken", "test")
            override val sourcePackage = "x.broken"
            override val sourceName = "Broken"
            override fun state() = ConnectorState.ACTIVE
            override suspend fun sync(cursor: String?) = SyncBatch(listOf(SourceRecord("", "t", "b", t0), SourceRecord("k", "", "", t0)), cursor = "c")
        }
        syncer.setEnabled("broken", true)
        assertEquals(ConnectorSyncer.Outcome.Synced(0, 0, 0, 0, 2), sync(broken))
        assertTrue(rows().isEmpty())
        assertNull(store.load("unknown").cursor)
    }

    @Test
    fun crossSourceDedupStillAppliesToConnectorEvents() {
        // The same bank debit from a notification and a pull connector goes through one dedup path.
        val notification = runBlocking { pipeline.ingest(RawCapture(ConnectorRegistry.NOTIFICATIONS, "com.snapwork.hdfc", "hdfc", "n1", "Debited", "Rs 500 debited. UPI Ref 426712345678", t0)) }
        assertTrue(notification is IngestionPipeline.Result.Stored)
        val pulled = object : SyncConnector {
            override val descriptor = ConnectorDescriptor("mail-test", "Mail", "test")
            override val sourcePackage = "com.google.android.gm"
            override val sourceName = "Gmail"
            override fun state() = ConnectorState.ACTIVE
            override suspend fun sync(cursor: String?) = SyncBatch(listOf(SourceRecord("x", "HDFC Bank", "Rs 500 debited. UPI Ref 426712345678", t0 + 1000)), cursor = "1")
        }
        syncer.setEnabled("mail-test", true)
        sync(pulled)
        val mail = rows().first { it.sourcePackage == "com.google.android.gm" }
        assertEquals((notification as IngestionPipeline.Result.Stored).eventId, mail.duplicateOfId)
    }
}
