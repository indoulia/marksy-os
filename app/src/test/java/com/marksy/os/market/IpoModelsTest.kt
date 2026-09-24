package com.marksy.os.market

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IpoModelsTest {
    @Test
    fun parsesIpoListItemWithHeterogeneousValueEnvelope() {
        val json = JSONObject(
            """
            {
              "id": "ipo-42", "companyName": "Acme Robotics", "issueName": "Acme Robotics IPO", "isSme": false, "sector": "Industrials", "stage": "OPEN",
              "opensOn": {"state": "AVAILABLE", "value": "2026-10-01", "asOf": "2026-09-20T00:00:00Z"},
              "closesOn": {"state": "AVAILABLE", "value": "2026-10-03", "asOf": "2026-09-20T00:00:00Z"},
              "listsOn": {"state": "MISSING", "value": null, "asOf": null},
              "terms": {
                "priceBand": {"state": "AVAILABLE", "value": {"low": 210, "high": 225}, "asOf": "2026-09-20T00:00:00Z"},
                "lotSize": {"state": "AVAILABLE", "value": 65, "asOf": "2026-09-20T00:00:00Z"},
                "issueSizeCrore": {"state": "AVAILABLE", "value": 1200.5, "asOf": "2026-09-20T00:00:00Z"}
              }
            }
            """
        )

        val ipo = IpoListItemDto.parse(json)

        assertEquals("ipo-42", ipo.id)
        assertEquals("OPEN", ipo.stage)
        assertEquals("AVAILABLE", ipo.opensOn?.state)
        assertEquals("MISSING", ipo.listsOn?.state)
        assertNull(ipo.listsOn?.value)
        assertEquals(65, (ipo.terms?.lotSize?.value as? Number)?.toInt())
    }

    @Test
    fun stageIsNullWhenNeverEvaluated() {
        val ipo = IpoListItemDto.parse(JSONObject("""{"id": "ipo-9", "companyName": "Unknown Co", "isSme": true}"""))

        assertNull(ipo.stage)
    }

    @Test
    fun parsesIpoDetail() {
        val json = JSONObject(
            """
            {
              "summary": {"id": "ipo-42", "companyName": "Acme Robotics", "isSme": false},
              "riskEngineRan": true,
              "riskRun": {"status": "COMPLETE", "ranAt": "2026-09-20T00:00:00Z", "findingsCount": 2}
            }
            """
        )

        val detail = IpoDetailDto.parse(json)

        assertEquals("ipo-42", detail.summary.id)
        assertTrue(detail.riskEngineRan)
        assertEquals(2, detail.riskRun?.findingsCount)
    }

    @Test
    fun parsesIpoHistoryEntryList() {
        val array = org.json.JSONArray(
            """[{"predictedAt": "2026-09-15T00:00:00Z", "decision": "POSITIVE", "expectedReturnPercent": {"state": "AVAILABLE", "value": 12.5}}]"""
        )

        val history = IpoHistoryEntryDto.parseList(array)

        assertEquals(1, history.size)
        assertEquals("POSITIVE", history[0].decision)
        assertEquals(12.5, (history[0].expectedReturnPercent?.value as? Number)?.toDouble()!!, 1e-9)
    }

    @Test
    fun parsesAttentionItems() {
        val array = org.json.JSONArray("""[{"ipoId": "ipo-42", "companyName": "Acme Robotics", "kind": "CLOSING_SOON", "detail": "Closes in 1 day"}]""")

        val items = IpoAttentionItemDto.parseList(array)

        assertEquals("CLOSING_SOON", items.single().kind)
    }

    @Test
    fun parsesStageCountsAndExposesAllStageKeys() {
        val json = JSONObject("""{"byStage": {"UPCOMING": 3, "OPEN": 1, "CLOSED": 5, "LISTED": 12}, "unevaluated": 2, "total": 23}""")

        val counts = IpoStageCountsDto.parse(json)

        assertEquals(setOf("UPCOMING", "OPEN", "CLOSED", "LISTED"), counts.byStage.keys)
        assertEquals(23, counts.total)
    }
}
