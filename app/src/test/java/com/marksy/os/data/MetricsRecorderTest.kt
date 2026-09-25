package com.marksy.os.data

import androidx.room.Room
import com.marksy.os.data.local.MarksyDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneOffset

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MetricsRecorderTest {
    private val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), MarksyDatabase::class.java).allowMainThreadQueries().build()
    private val today = LocalDate.of(2026, 9, 26)
    private val metrics = MetricsRecorder(db.metricsDao(), clock = { today.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() + 1 }, zone = { ZoneOffset.UTC })

    @After fun tearDown() = db.close()

    private fun counts() = runBlocking { metrics.range(today, today) }.associate { "${it.metric}/${it.scope}" to it.value }

    // ~60 single-row writes per captured notification: a batch holds them and writes merged totals once.
    @Test fun batchedCountsAreHeldUntilTheBlockEndsThenWrittenMerged() = runBlocking {
        metrics.batch {
            metrics.count("captured", "source:a")
            metrics.count("captured", "source:a")
            metrics.count("stored")
            assertTrue(counts().isEmpty())
        }
        assertEquals(mapOf("captured/all" to 2L, "captured/source:a" to 2L, "stored/all" to 1L), counts())
    }

    @Test fun aFailedBlockStillRecordsItsCountsAndUnbatchedCountsWriteAtOnce() = runBlocking {
        runCatching { metrics.batch { metrics.count("failed"); error("capture crashed") } }
        metrics.count("direct", "scope:x")
        assertEquals(mapOf("failed/all" to 1L, "direct/all" to 1L, "direct/scope:x" to 1L), counts())
    }
}
