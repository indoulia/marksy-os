package com.marksy.os.intelligence

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import com.marksy.os.data.NotificationRepository
import com.marksy.os.data.local.MarksyDatabase
import com.marksy.os.data.local.NotificationEventDao
import com.marksy.os.data.local.NotificationEventEntity
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
import java.time.ZoneOffset

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EventIntelligencePipelineTest {
    private lateinit var db: MarksyDatabase
    private lateinit var dao: NotificationEventDao
    private lateinit var pipeline: EventIntelligencePipeline
    private val t0 = 1_790_000_000_000L

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), MarksyDatabase::class.java)
            .allowMainThreadQueries().build()
        dao = db.notificationEventDao()
        pipeline = EventIntelligencePipeline(dao, ZoneOffset.UTC, { t0 + 999 })
    }

    @After
    fun tearDown() = db.close()

    private var seq = 0
    private fun insert(
        pkg: String, title: String, body: String, category: String,
        postedAt: Long = t0, trading: Boolean = false
    ): Long = runBlocking {
        seq++
        dao.insert(
            NotificationEventEntity(
                sourcePackage = pkg, sourceName = pkg.substringAfterLast('.'), sourceKey = "k$seq",
                eventFingerprint = "f$seq", title = title, body = body, postedAt = postedAt,
                category = category, priority = 70, confidence = .9f, isTrading = trading
            )
        )
    }

    private fun get(id: Long) = runBlocking { dao.getById(id)!! }

    @Test
    fun sameTransactionFromBankAndUpiAppCollapsesToEarliest() = runBlocking {
        val bank = insert("com.snapwork.hdfc", "Debited", "Rs 500 debited. UPI Ref 426712345678", "BANKING")
        val upi = insert("com.phonepe.app", "Paid", "₹500 paid to Rahul. UPI ref no 426712345678", "PAYMENTS", postedAt = t0 + 30_000)
        pipeline.process(bank)
        val outcome = pipeline.process(upi)!!

        assertEquals(bank, outcome.duplicateOfId)
        assertNull(get(bank).duplicateOfId)
        assertEquals(bank, get(upi).duplicateOfId)
        assertEquals(get(bank).threadKey, get(upi).threadKey)
        assertTrue(EventNormalizer.reasonsFromJson(get(upi).intelligenceJson).any { it.contains("Duplicate of event #$bank") })
    }

    @Test
    fun amountOnlyMatchRequiresADifferentSourceAndTheWindow() = runBlocking {
        val a = insert("com.phonepe.app", "Paid", "You paid ₹500 to Rahul", "PAYMENTS")
        val sameSource = insert("com.phonepe.app", "Paid", "You paid ₹500 to Amit", "PAYMENTS", postedAt = t0 + 60_000)
        val otherSource = insert("com.snapwork.hdfc", "Debited", "Rs 500 debited from a/c", "BANKING", postedAt = t0 + 90_000)
        val late = insert("com.snapwork.hdfc", "Debited", "Rs 500 debited from a/c", "BANKING", postedAt = t0 + 3_600_000)
        listOf(a, sameSource, otherSource, late).forEach { pipeline.process(it) }

        assertNull(get(sameSource).duplicateOfId)
        assertEquals(a, get(otherSource).duplicateOfId)
        assertNull(get(late).duplicateOfId)
    }

    @Test
    fun sameAmountToDifferentNamedCounterpartiesIsNotFoldedAsDuplicate() = runBlocking {
        val a = insert("com.phonepe.app", "Paid", "You paid ₹500 to Rahul Sharma", "PAYMENTS")
        val b = insert("com.snapwork.hdfc", "Debited", "Rs 500 debited at AMAZON RETAIL.", "BANKING", postedAt = t0 + 60_000)
        val c = insert("com.snapwork.hdfc", "Debited", "Rs 500 debited from a/c", "BANKING", postedAt = t0 + 90_000)
        listOf(a, b, c).forEach { pipeline.process(it) }
        assertNull(get(b).duplicateOfId)
        assertEquals(a, get(c).duplicateOfId) // no counterparty on the bank side: still compatible
    }

    @Test
    fun tradingObservationsFromDifferentBrokersAreNeverDeduplicated() = runBlocking {
        val z = insert("com.zerodha.kite3", "Order executed", "BUY INFY at ₹1500 Txn ID AB12345678", "TRADING", trading = true)
        val u = insert("com.upstox.pro", "Order executed", "BUY INFY at ₹1500 Txn ID AB12345678", "TRADING", trading = true)
        pipeline.process(z); pipeline.process(u)
        assertNull(get(u).duplicateOfId)
        assertNull(get(u).correlationKey)
    }

    @Test
    fun deliveredResolvesEarlierEventsInTheOrderThreadOnlyOnce() = runBlocking {
        val shipped = insert("in.amazon", "Shipped", "Order ID 403-1234567-7654321 shipped", "DELIVERY")
        val unrelated = insert("in.amazon", "Shipped", "Order ID 999-0000000-1111111 shipped", "DELIVERY", postedAt = t0 + 1)
        val delivered = insert("com.delhivery", "Delivered", "Order no 403-1234567-7654321 delivered", "DELIVERY", postedAt = t0 + 86_400_000)
        pipeline.process(shipped); pipeline.process(unrelated)
        val outcome = pipeline.process(delivered)!!

        assertEquals(1, outcome.resolvedCount)
        assertEquals("RESOLVED", get(shipped).lifecycleState)
        assertEquals("Resolved by later event #$delivered", get(shipped).lifecycleReason)
        assertEquals("NEW", get(unrelated).lifecycleState)
        assertEquals("NEW", get(delivered).lifecycleState)

        // Idempotent: reprocessing never re-resolves (user may have reopened it).
        dao.transitionLifecycle(shipped, "RESOLVED", "ACTIVE", null, t0)
        assertEquals(0, pipeline.process(delivered)!!.resolvedCount)
        assertEquals("ACTIVE", get(shipped).lifecycleState)
    }

    @Test
    fun backfillProcessesUnversionedRowsAndPersistsScores() = runBlocking {
        val ids = (1..3).map { insert("com.snapwork.hdfc", "Debited", "Rs ${it}00 debited", "BANKING", postedAt = t0 + it) }
        assertEquals(3, pipeline.processPending())
        assertEquals(0, pipeline.processPending())
        ids.forEach {
            val e = get(it)
            assertEquals(EventIntelligencePipeline.VERSION, e.intelligenceVersion)
            assertTrue(e.importanceScore > 0)
            assertEquals(.95f, e.intelligenceConfidence, .0001f)
            assertTrue(e.threadKey!!.isNotBlank())
        }
    }

    @Test
    fun lifecycleSeenArchiveAndUnarchiveStayConsistentWithArchivedFlag() = runBlocking {
        val repo = NotificationRepository(dao)
        val id = insert("com.whatsapp", "Rahul", "hi", "MESSAGES")
        assertTrue(repo.markSeen(id))
        assertEquals("ACTIVE", get(id).lifecycleState)
        assertTrue(!repo.markSeen(id))

        repo.archive(id)
        assertEquals("ARCHIVED", get(id).lifecycleState)
        assertTrue(get(id).archived)
        repo.unarchive(id)
        assertEquals("ACTIVE", get(id).lifecycleState)
    }

    @Test
    fun threadActionsPersistResolveReopenSnoozeAndArchive() = runBlocking {
        val repo = NotificationRepository(dao)
        val ids = listOf(insert("com.a", "t", "b", "OTHER"), insert("com.b", "t", "b", "OTHER"))
        assertEquals(2, repo.resolveThread(ids, t0))
        assertTrue(ids.all { get(it).lifecycleState == "RESOLVED" && get(it).lifecycleReason == "Resolved by you" })
        assertEquals(2, repo.reopenThread(ids, t0))
        repo.snoozeThread(ids, t0 + 5_000)
        assertTrue(ids.all { get(it).snoozedUntil == t0 + 5_000 })
        repo.archiveThread(ids, t0)
        assertTrue(ids.all { get(it).archived && get(it).lifecycleState == "ARCHIVED" })
        assertEquals(0, repo.resolveThread(ids, t0))
    }

    @Test
    fun migrationFromV2PreservesRowsAndMapsArchivedToLifecycle() {
        val context = RuntimeEnvironment.getApplication()
        val name = "migration-test.db"
        context.deleteDatabase(name)
        SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name).apply { parentFile?.mkdirs() }, null).use { raw ->
            raw.execSQL(V2_SCHEMA)
            V2_INDICES.forEach(raw::execSQL)
            raw.execSQL("INSERT INTO notification_events (sourcePackage, sourceName, sourceKey, eventFingerprint, title, body, postedAt, category, priority, confidence, isTrading, deliveryState, deliveryAttempts, archived, createdAt) VALUES ('com.a','A','k1','f1','t','b',1,'OTHER',10,0.5,0,'NOT_APPLICABLE',0,0,1)")
            raw.execSQL("INSERT INTO notification_events (sourcePackage, sourceName, sourceKey, eventFingerprint, title, body, postedAt, category, priority, confidence, isTrading, deliveryState, deliveryAttempts, archived, createdAt) VALUES ('com.a','A','k2','f2','t','b',2,'OTHER',10,0.5,0,'NOT_APPLICABLE',0,1,1)")
            raw.version = 2
        }

        val migrated = Room.databaseBuilder(context, MarksyDatabase::class.java, name)
            .addMigrations(MarksyDatabase.MIGRATION_1_2, MarksyDatabase.MIGRATION_2_3, MarksyDatabase.MIGRATION_3_4, MarksyDatabase.MIGRATION_4_5)
            .allowMainThreadQueries().build()
        try {
            val rows = runBlocking { migrated.notificationEventDao().findNeedingIntelligence(EventIntelligencePipeline.VERSION, 10) }
            assertEquals(listOf("ACTIVE", "ARCHIVED"), rows.sortedBy { it.id }.map { it.lifecycleState })
            assertTrue(rows.all { it.intelligenceVersion == 0 && it.threadKey == null })
            // 3->4: existing rows start read, nothing kept or reminded.
            assertTrue(rows.all { it.isRead && !it.kept && it.remindAt == null })
        } finally {
            migrated.close()
            context.deleteDatabase(name)
        }
    }

    /**
     * A phone that ran the pre-merge gateway branch is at "v3" with isRead/kept/remindAt but none of the
     * EPIC-010..023 schema. 3->4 must repair it, keep its read state, and pass Room's schema validation.
     */
    @Test
    fun migrationFromGatewayBranchV3RepairsIntelligenceSchemaAndKeepsReadState() {
        val context = RuntimeEnvironment.getApplication()
        val name = "migration-gateway-v3.db"
        context.deleteDatabase(name)
        SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name).apply { parentFile?.mkdirs() }, null).use { raw ->
            raw.execSQL(V2_SCHEMA)
            V2_INDICES.forEach(raw::execSQL)
            raw.execSQL("ALTER TABLE notification_events ADD COLUMN isRead INTEGER NOT NULL DEFAULT 0")
            raw.execSQL("ALTER TABLE notification_events ADD COLUMN kept INTEGER NOT NULL DEFAULT 0")
            raw.execSQL("ALTER TABLE notification_events ADD COLUMN remindAt INTEGER")
            raw.execSQL("INSERT INTO notification_events (sourcePackage, sourceName, sourceKey, eventFingerprint, title, body, postedAt, category, priority, confidence, isTrading, deliveryState, deliveryAttempts, archived, createdAt, isRead, kept, remindAt) VALUES ('com.a','A','k1','f1','t','b',1,'OTHER',10,0.5,0,'NOT_APPLICABLE',0,0,1,0,1,99)")
            raw.version = 3
        }
        val migrated = Room.databaseBuilder(context, MarksyDatabase::class.java, name)
            .addMigrations(MarksyDatabase.MIGRATION_1_2, MarksyDatabase.MIGRATION_2_3, MarksyDatabase.MIGRATION_3_4, MarksyDatabase.MIGRATION_4_5)
            .allowMainThreadQueries().build()
        try {
            val row = runBlocking { migrated.notificationEventDao().findNeedingIntelligence(EventIntelligencePipeline.VERSION, 10) }.single()
            assertTrue(!row.isRead && row.kept && row.remindAt == 99L)
            assertEquals("ACTIVE", row.lifecycleState)
            // 4->5: the existing "Remind me" carries over as a follow-up.
            val followUp = runBlocking { migrated.planItemDao().all() }.single()
            assertEquals("FOLLOW_UP", followUp.kind)
            assertEquals(99L, followUp.dueAt)
            assertEquals(row.id, followUp.sourceEventId)
            assertEquals(0, runBlocking { migrated.learningDao().counts(0) }.size)
        } finally {
            migrated.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun readStateAndLifecycleStayInStep() = runBlocking {
        val id = insert("com.chat", "Rahul", "hi", "MESSAGES")
        dao.setRead(id, true)
        assertEquals("ACTIVE", get(id).lifecycleState)
        dao.setRead(id, false)
        assertEquals("NEW", get(id).lifecycleState)
        assertTrue(!get(id).isRead)
        dao.markSeen(listOf(id), t0)
        assertTrue(get(id).isRead)
        assertEquals("ACTIVE", get(id).lifecycleState)
    }

    private companion object {
        const val V2_SCHEMA = "CREATE TABLE IF NOT EXISTS `notification_events` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `sourcePackage` TEXT NOT NULL, `sourceName` TEXT NOT NULL, `sourceKey` TEXT NOT NULL, `eventFingerprint` TEXT NOT NULL, `title` TEXT NOT NULL, `body` TEXT NOT NULL, `postedAt` INTEGER NOT NULL, `category` TEXT NOT NULL, `priority` INTEGER NOT NULL, `confidence` REAL NOT NULL, `isTrading` INTEGER NOT NULL, `deliveryState` TEXT NOT NULL, `deliveryAttempts` INTEGER NOT NULL, `lastDeliveryAttemptAt` INTEGER, `insightSummary` TEXT, `insightAction` TEXT, `insightConfidence` REAL, `insightReceivedAt` INTEGER, `marksyTipId` TEXT, `marksyResponseJson` TEXT, `archived` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL)"
        val V2_INDICES = listOf(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_notification_events_sourcePackage_sourceKey` ON `notification_events` (`sourcePackage`, `sourceKey`)",
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_notification_events_sourcePackage_eventFingerprint` ON `notification_events` (`sourcePackage`, `eventFingerprint`)",
            "CREATE INDEX IF NOT EXISTS `index_notification_events_category` ON `notification_events` (`category`)",
            "CREATE INDEX IF NOT EXISTS `index_notification_events_postedAt` ON `notification_events` (`postedAt`)",
            "CREATE INDEX IF NOT EXISTS `index_notification_events_deliveryState` ON `notification_events` (`deliveryState`)",
            "CREATE INDEX IF NOT EXISTS `index_notification_events_archived` ON `notification_events` (`archived`)"
        )
    }
}
