package com.marksy.os.gateway

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MarketSnapshotTest {
    // Shape mirrors /dashboard/snapshot as consumed by the Marksy web app.
    private val data = JSONObject(
        """
        {
          "marketStatus": "OPEN",
          "indices": [
            {"name": "NIFTY 50", "value": 25143.2, "changePct": 0.42, "change": 105.1},
            {"name": "SENSEX", "value": 82391.5, "changePct": -0.12}
          ],
          "topOpportunities": [
            {"id": 7, "symbol": "RELIANCE", "name": "Reliance Industries", "price": 1452.3, "changePct": 1.2,
             "entryPrice": 1450, "targetPrice": 1488, "stopLoss": 1435, "horizon": 5, "upsidePercent": 2.6,
             "score": 81, "confidence": 0.89, "status": "ACTIVE", "updatedAt": "2026-09-23T09:15:00Z"},
            {"id": 8, "symbol": "BAD"}
          ],
          "topGainers": [{"symbol": "HAL", "name": "HAL", "price": 4100, "changePercent": 3.1}],
          "topLosers": [],
          "dataFreshness": {"marketAsOf": "2026-09-23T10:00:00Z", "marketDataStatus": "LIVE"}
        }
        """
    )

    @Test
    fun parsesIndicesOpportunitiesAndMovers() {
        val snapshot = MarketSnapshot.parse(data)

        assertEquals("OPEN", snapshot.marketStatus)
        assertEquals(2, snapshot.indices.size)
        assertEquals("NIFTY 50", snapshot.indices[0].name)
        assertEquals(0.42, snapshot.indices[0].changePct, 1e-9)
        assertNull(snapshot.indices[1].change)

        val opportunity = snapshot.opportunities.single()
        assertEquals("RELIANCE", opportunity.symbol)
        assertEquals(1488.0, opportunity.targetPrice, 1e-9)
        assertEquals(0.89, opportunity.confidence, 1e-9)

        assertEquals("HAL", snapshot.gainers.single().symbol)
        assertTrue(snapshot.losers.isEmpty())
        assertEquals("2026-09-23T10:00:00Z", snapshot.asOf)
    }

    @Test
    fun missingSectionsYieldEmptyLists() {
        val snapshot = MarketSnapshot.parse(JSONObject("{}"))

        assertTrue(snapshot.indices.isEmpty())
        assertTrue(snapshot.opportunities.isEmpty())
        assertNull(snapshot.marketStatus)
    }
}
