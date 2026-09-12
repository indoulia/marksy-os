package com.marksy.os.gateway

import com.marksy.os.data.local.DeliveryState
import com.marksy.os.data.local.NotificationEventEntity
import com.marksy.os.ui.toTradingInsight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MarksyResponsePersistenceMappingTest {
    @Test
    fun persistedMarksyResponseIsMappedToTradingInsight() {
        val event = event(
            marksyTipId = "tip-123",
            marksyResponseJson = """
                {
                  "comparison": {
                    "verdict": "PARTIAL",
                    "verdictReasons": ["Target differs", "Direction agrees"],
                    "marksySource": "PUBLISHED_RECOMMENDATION",
                    "marksyView": "BUY with moderate confidence"
                  },
                  "marksyView": {
                    "recommendation": "BUY",
                    "probability": 0.72,
                    "opportunityScore": 81,
                    "trustScore": 0.84,
                    "trustQuality": "HIGH",
                    "uncertaintyLevel": "MEDIUM",
                    "entryPrice": 368.0,
                    "targetPrice": 390.0,
                    "stopLoss": 355.0,
                    "upsidePct": 5.98,
                    "horizonDays": 5,
                    "levelState": "VALID",
                    "modelVersion": "v1",
                    "asOf": "2026-09-12T09:30:00+05:30",
                    "failedCriteria": ["Volume confirmation"],
                    "decisionOutcome": "PUBLISH",
                    "evidence": ["breakout", "volume"]
                  }
                }
            """.trimIndent()
        )

        val insight = requireNotNull(event.toTradingInsight())

        assertEquals("tip-123", insight.marksyTipId)
        assertEquals("PARTIAL", insight.marksyVerdict)
        assertEquals(listOf("Target differs", "Direction agrees"), insight.marksyVerdictReasons)
        assertEquals("BUY", insight.marksyRecommendation)
        assertEquals(0.72, insight.marksyProbability!!, 0.0001)
        assertEquals(81.0, insight.marksyOpportunityScore!!, 0.0001)
        assertEquals(0.84, insight.marksyTrustScore!!, 0.0001)
        assertEquals(368.0, insight.marksyEntryPrice!!, 0.0001)
        assertEquals(390.0, insight.marksyTargetPrice!!, 0.0001)
        assertEquals(355.0, insight.marksyStopLoss!!, 0.0001)
        assertEquals(5, insight.marksyHorizonDays)
        assertEquals("PUBLISH", insight.marksyDecisionOutcome)
        assertEquals(listOf("breakout", "volume"), insight.marksyEvidence)
    }

    @Test
    fun malformedPersistedResponseDoesNotBreakTradingInsight() {
        val event = event(marksyTipId = "tip-456", marksyResponseJson = "not-json")
        val insight = requireNotNull(event.toTradingInsight())

        assertEquals("tip-456", insight.marksyTipId)
        assertNull(insight.marksyVerdict)
        assertEquals("Marksy response received", insight.status)
    }

    private fun event(
        marksyTipId: String? = null,
        marksyResponseJson: String? = null
    ) = NotificationEventEntity(
        id = 1,
        sourcePackage = "com.upstox.pro",
        sourceName = "Upstox",
        sourceKey = "notification-1",
        title = "Buy HLEGLAS at 367.95",
        body = "Order executed",
        postedAt = 1_757_650_000_000,
        category = "TRADING",
        priority = 3,
        confidence = 0.98f,
        isTrading = true,
        deliveryState = DeliveryState.DELIVERED.name,
        insightSummary = "PARTIAL | BUY",
        insightAction = "BUY",
        insightConfidence = 0.72f,
        marksyTipId = marksyTipId,
        marksyResponseJson = marksyResponseJson
    )
}
