package com.marksy.os.ui

import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performScrollToIndex
import com.marksy.os.data.local.NotificationEventEntity
import com.marksy.os.intelligence.DashboardSnapshot
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DashboardScreenTest {
    @get:Rule val compose = createComposeRule()

    private fun event(id: Long, now: Long) = NotificationEventEntity(
        id = id,
        sourcePackage = "com.zerodha.kite",
        sourceName = "Kite",
        sourceKey = "k$id",
        eventFingerprint = "f$id",
        title = "NIFTY breakout alert $id",
        body = "Target hit, stop loss triggered",
        postedAt = now - id * 1_000,
        category = "TRADING",
        priority = 3,
        confidence = 0.95f,
        isTrading = true
    )

    // Crash seen on device: Key "15" was already used — same event in Attention and Latest Activity.
    @Test
    fun scrollingToBottomWithEventInBothSectionsDoesNotCrash() {
        val now = System.currentTimeMillis()
        // One event: its Attention and Latest cards sit adjacent, so both are in the viewport together.
        val events = listOf(event(15, now))
        val snapshot = DashboardSnapshot.from(events, now)
        assertTrue("precondition: attention list overlaps latest", snapshot.topAttention.isNotEmpty())

        compose.setContent { DashboardScreen(snapshot = snapshot, events = events, onEventSelected = {}) }
        // header + 2 section titles + attention items + latest items
        compose.onNode(hasScrollAction()).performScrollToIndex(2 + snapshot.topAttention.size + events.size)
        compose.waitForIdle()
    }
}
