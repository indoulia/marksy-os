package com.marksy.os.gateway

import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TradingDeliverySchedulerTest {
    // Send now and every other immediate trigger enqueue the worker whose doWork drains TradingDeliveryRun.
    @Test
    fun immediateDeliveryEnqueuesTheTradingDeliveryWorker() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        WorkManager.initialize(app, Configuration.Builder().setExecutor { it.run() }.build())

        TradingDeliveryScheduler.requestImmediateDelivery(app)

        val work = WorkManager.getInstance(app).getWorkInfosForUniqueWork("marksy-trading-delivery-immediate").get()
        assertEquals(1, work.size)
        assertTrue(TradingDeliveryWorker::class.java.name in work.single().tags)
    }
}
