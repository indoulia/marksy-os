package com.marksy.os.capture

import com.marksy.os.capture.NotificationCapturePlan.CompleteTip
import com.marksy.os.capture.NotificationCapturePlan.NeedsSourceView
import com.marksy.os.capture.NotificationCapturePlan.NotApplicable
import com.marksy.os.data.local.NotificationEventEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationCapturePlannerTest {
    private val planner = NotificationCapturePlanner(CaptureSourceRegistry(capturePackages = { setOf("com.research.app") }, displayName = { "App" }))

    private fun event(pkg: String, title: String, body: String, category: String = "MARKET") = NotificationEventEntity(
        id = 7, sourcePackage = pkg, sourceName = "Research", sourceKey = "k", eventFingerprint = "f", title = title, body = body,
        postedAt = 1_000L, category = category, priority = 1, confidence = 1f, isTrading = false
    )

    @Test
    fun completeTipKeepsTheExistingPath() {
        val plan = planner.plan(event("com.zerodha.kite3", "Research", "BUY ABC @ 500 Target 650 SL 470"))
        assertTrue(plan is CompleteTip)
        val candidate = (plan as CompleteTip).candidate
        assertEquals(TipFields("ABC", TipSide.BUY, 500.0, 650.0, 470.0), candidate.fields)
        assertEquals(
            CaptureProvenance(CaptureMethod.NOTIFICATION_LISTENER, "com.zerodha.kite3", "Research", true, 1_000L, "event:7", notificationEventId = 7),
            candidate.provenance
        )
    }

    @Test
    fun incompleteTeaserNeedsTheSourceView() {
        assertEquals(NeedsSourceView(TeaserReason.TEASER), planner.plan(event("com.research.app", "ABC Research", "New recommendation is live. Tap to view")))
        assertEquals(NeedsSourceView(TeaserReason.TEASER), planner.plan(event("com.zerodha.kite3", "Stock idea: BUY ABC", "Check now")))
    }

    @Test
    fun redactedOrMissingText() {
        assertEquals(NeedsSourceView(TeaserReason.REDACTED), planner.plan(event("com.research.app", "Research", "Sensitive notification content hidden")))
        assertEquals(NeedsSourceView(TeaserReason.REDACTED), planner.plan(event("com.research.app", "Research", "Contents hidden")))
        assertEquals(NeedsSourceView(TeaserReason.REDACTED), planner.plan(event("com.research.app", "Research", " ")))
    }

    @Test
    fun unsupportedSource() {
        assertEquals(NotApplicable(NotificationCapturePlanner.UNSUPPORTED_SOURCE), planner.plan(event("com.example.game", "Game", "New tip! Tap to view")))
        assertEquals(NotApplicable(NotificationCapturePlanner.UNSUPPORTED_SOURCE), planner.plan(event("", "Game", "New tip! Tap to view")))
    }

    // Chats show the full message, so a chat teaser is no screen-capture case; an app's own order or promotion never is either.
    @Test
    fun sourceSpecificChatVersusApp() {
        assertEquals(NotApplicable(NotificationCapturePlanner.NO_SCREEN_CAPTURE), planner.plan(event("com.whatsapp", "Tips group", "New tip, tap to view", "MESSAGES")))
        assertTrue(planner.plan(event("com.whatsapp", "Tips group", "BUY ABC @ 500 Target 650 SL 470", "TRADING")) is CompleteTip)
        assertEquals(NotApplicable("own-order"), planner.plan(event("com.zerodha.kite3", "Kite", "Your order for ABC is executed. Tap to view")))
        assertEquals(NotApplicable(NotificationCapturePlanner.NOT_A_TEASER), planner.plan(event("com.zerodha.kite3", "Kite", "Zero brokerage week! Check now", "PROMOTIONS")))
        assertEquals(NotApplicable(NotificationCapturePlanner.NOT_A_TEASER), planner.plan(event("com.research.app", "Research", "Markets closed higher today")))
    }
}
