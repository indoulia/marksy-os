package com.marksy.os.intelligence

import com.marksy.os.data.local.ConnectorEventEntity
import com.marksy.os.intelligence.MarksyHealth.Level
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MarksyHealthTest {
    private val hour = 3_600_000L
    private val now = 100 * hour
    private val from = now - 24 * hour

    private fun ev(type: String, at: Long) = ConnectorEventEntity(connectorId = "c", adapterId = null, type = type, detail = null, at = at)

    private fun connector(granted: Boolean = true, configured: Boolean = true, lifecycle: List<ConnectorEventEntity> = emptyList(), before: ConnectorEventEntity? = ev("CONNECTED", from - hour), failures: Int = 0) =
        MarksyHealth.ConnectorInput("android-notifications", "Android notifications", granted, configured, lifecycle, before, 10, failures)

    private fun inputs(
        lastCapture: Long? = now - hour, connectors: List<MarksyHealth.ConnectorInput> = listOf(connector()),
        exempt: Boolean? = true, backlog: Int = 0, failedDeliveries: Int = 0
    ) = MarksyHealth.Inputs(
        nowMillis = now, windowStart = from, capturedToday = 10, captured7d = 70, duplicates7d = 3, unclassified7d = 5,
        processingFailures7d = 0, captureFailures7d = 0, important7d = 4, processingMsSum7d = 700, processingCount7d = 70,
        deliveryDelayMsSum7d = 7000, lastCaptureAt = lastCapture, intelligenceBacklog = backlog, pendingTradingDeliveries = 0,
        failedTradingDeliveries = failedDeliveries, pendingReminders = 1, aiCalls7d = 0, aiFailures7d = 0, aiAvgLatencyMs = null,
        databaseBytes = 2048, eventRows = 50, batteryPercent = 80, charging = false, batteryOptimizationExempt = exempt, connectors = connectors
    )

    @Test
    fun uptimeFollowsTheLifecycleLog() {
        assertEquals(100.0, MarksyHealth.uptime(emptyList(), ev("CONNECTED", 0), from, now)!!, .001)
        assertEquals(25.0, MarksyHealth.uptime(listOf(ev("DISCONNECTED", from + 6 * hour)), ev("CONNECTED", 0), from, now)!!, .001)
        assertEquals(0.0, MarksyHealth.uptime(listOf(ev("DISCONNECTED", from + 6 * hour)), null, from, now)!!, .001)
        assertEquals(50.0, MarksyHealth.uptime(listOf(ev("DISCONNECTED", from + 6 * hour), ev("CONNECTED", from + 18 * hour)), ev("CONNECTED", 0), from, now)!!, .001)
        assertNull(MarksyHealth.uptime(emptyList(), null, from, now))
    }

    @Test
    fun healthyWhenCapturingAndConnected() {
        val r = MarksyHealth.evaluate(inputs())
        assertEquals(Level.OK, r.level)
        assertFalse(r.stale)
        assertEquals("10 ms", r.metrics.single { it.label == "Processing latency" }.value)
        assertEquals("1 h ago", r.metrics.single { it.label == "Last capture" }.value)
    }

    @Test
    fun staleDisconnectedAndPermissionProblemsAreActionable() {
        val stale = MarksyHealth.evaluate(inputs(lastCapture = now - 13 * hour))
        assertTrue(stale.stale)
        assertTrue(stale.diagnostics.any { it.title == "No notification captured for 13h" })

        val off = MarksyHealth.evaluate(inputs(connectors = listOf(connector(granted = false))))
        assertEquals(Level.CRITICAL, off.level)
        assertEquals("Permission not granted", off.connectors.single().status)

        val dropped = MarksyHealth.evaluate(inputs(connectors = listOf(connector(lifecycle = listOf(ev("DISCONNECTED", now - hour))))))
        assertEquals("Disconnected", dropped.connectors.single().status)
        assertTrue(dropped.diagnostics.any { it.action!!.contains("Toggle") })

        val misc = MarksyHealth.evaluate(inputs(exempt = false, backlog = 60, failedDeliveries = 2))
        assertEquals(Level.WARNING, misc.level)
        assertEquals(3, misc.diagnostics.size)
    }
}
