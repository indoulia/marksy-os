package com.marksy.os.intelligence

import androidx.room.Room
import com.marksy.os.data.LearningRepository
import com.marksy.os.data.Metric
import com.marksy.os.data.MetricsRecorder
import com.marksy.os.data.NotificationRepository
import com.marksy.os.data.local.ConnectorEventEntity
import com.marksy.os.data.local.MarksyDatabase
import com.marksy.os.data.local.MetricCounterEntity
import com.marksy.os.data.local.NotificationEventEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneOffset

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ValidationReportTest {
    private lateinit var db: MarksyDatabase
    private val start = LocalDate.of(2026, 9, 1)

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), MarksyDatabase::class.java).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = db.close()

    private fun c(day: String, metric: String, value: Long, scope: String = "all") = MetricCounterEntity(day, scope, metric, value)

    @Test
    fun reportAggregatesByDayAndSourceReportsGapsAndNeverFillsThem() {
        val counters = listOf(
            c("2026-09-01", Metric.CAPTURED, 10), c("2026-09-01", Metric.CLASSIFIED, 8), c("2026-09-01", Metric.DUPLICATE, 2),
            c("2026-09-01", Metric.PROCESSING_MS_SUM, 100), c("2026-09-01", Metric.PROCESSING_COUNT, 10),
            c("2026-09-01", ValidationReport.GAUGE_UPTIME_PCT_X100, 9950),
            c("2026-09-03", Metric.CAPTURED, 5), c("2026-09-03", Metric.CLASSIFIED, 4), c("2026-09-03", Metric.CORRECTION, 1),
            c("2026-09-03", Metric.CORRECTION, 1, "correction:category"),
            c("2026-09-01", Metric.CAPTURED, 10, "source:com.bank"), c("2026-09-03", Metric.CAPTURED, 5, "source:com.chat"),
            c("2026-09-03", Metric.CAPTURE_FAILED, 1, "source:com.chat")
        )
        val failure = ConnectorEventEntity(connectorId = "android-notifications", adapterId = "sms", type = "FAILED", detail = "IllegalStateException", at = 1)
        val r = ValidationReport.build(start, LocalDate.of(2026, 9, 3), counters, listOf(failure))

        assertEquals(3, r.daysElapsed)
        assertEquals(2, r.daysWithData)
        assertEquals(listOf(LocalDate.of(2026, 9, 2)), r.gaps)
        assertEquals(0L, r.days[1].captured)
        assertEquals(15L, r.totals.captured)
        assertEquals(12.0 / 15, r.classificationRate!!, 1e-9)
        assertEquals(1 - 1.0 / 12, r.estimatedAccuracy!!, 1e-9)
        assertEquals(10L, r.totals.avgProcessingMs)
        assertEquals(99.5, r.days[0].uptimePct!!, 1e-9)
        assertEquals(listOf("com.bank", "com.chat"), r.sources.map { it.source })
        assertEquals(1L, r.sources[1].failures)

        val md = ValidationReport.toMarkdown(r)
        assertTrue(md.startsWith("# Marksy Intelligence Validation Report"))
        assertTrue(md.contains("Days with no data: 2026-09-02"))
        assertTrue(md.contains("android-notifications/sms: FAILED IllegalStateException"))
    }

    @Test
    fun interactionsCorrectionsAndLearningAreCountedAutomatically() = runBlocking {
        val now = 1_790_000_000_000L
        val metrics = MetricsRecorder(db.metricsDao(), { now }, { ZoneOffset.UTC })
        val learning = LearningRepository(db.learningDao(), db.notificationEventDao(), { true }, metrics) { now }
        val repo = NotificationRepository(db.notificationEventDao(), learning, metrics)
        val id = db.notificationEventDao().insert(NotificationEventEntity(sourcePackage = "com.chat", sourceName = "Chat", sourceKey = "k", eventFingerprint = "f",
            title = "Rahul", body = "hi", postedAt = now, category = "MESSAGES", priority = 40, confidence = .8f, isTrading = false))
        repo.markThreadSeen(listOf(id), now)
        repo.resolveThread(listOf(id), now)
        learning.setPreference(PersonalLearning.Subject(PersonalLearning.SubjectType.APP, "com.chat", "Chat"), PersonalLearning.Preference.ALWAYS_IMPORTANT)

        val rows = db.metricsDao().range("2000-01-01", "2100-01-01").filter { it.scope == "all" }.associate { it.metric to it.value }
        assertEquals(2L, rows[Metric.USER_INTERACTION])
        assertEquals(1L, rows[Metric.CORRECTION])
        assertEquals(6L, rows[Metric.LEARNING_SIGNAL]) // (app, category, sender) x (opened, resolved)

        db.metricsDao().set(MetricCounterEntity("2026-09-01", "all", ValidationReport.GAUGE_DB_BYTES, 10))
        db.metricsDao().set(MetricCounterEntity("2026-09-01", "all", ValidationReport.GAUGE_DB_BYTES, 20))
        assertEquals(20L, db.metricsDao().range("2026-09-01", "2026-09-01").single().value)
    }
}
