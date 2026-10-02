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

    @Test
    fun parsesTrackedIposAndTrackingState() {
        val tracked = IpoTrackedItemDto.parseList(org.json.JSONArray(
            """[{"ipoId": "ipo-42", "companyName": "Acme Robotics", "stage": "WATCHING", "issueStage": "OPEN"}, {"ipoId": "", "companyName": "No id"}]"""
        ))
        assertEquals(listOf("ipo-42"), tracked.map { it.ipoId })
        assertEquals("OPEN", tracked.single().issueStage)

        val state = IpoTrackingStateDto.parse(JSONObject("""{"ipoId": "ipo-42", "tracking": false, "stage": null}"""))
        assertEquals(IpoTrackingStateDto("ipo-42", false), state)
    }

    @Test
    fun parsesGmpSubscriptionAndRetailEstimate() {
        val ipo = IpoListItemDto.parse(JSONObject("""
            {"id":"kaveri","companyName":"Kaveri Hospitals","isSme":false,"stage":"OPEN",
             "gmp":{"state":"AVAILABLE","readings":[{"source":"ipoji.com","premium":48,"premiumPercent":11.06,"observedAt":"2026-10-01T04:45:00Z"},{"source":"other.in","premium":"40.5","premiumPercent":null,"observedAt":null}]},
             "subscription":{"state":"AVAILABLE","asOf":"2026-10-01T05:10:00Z",
               "series":{"RETAIL":[{"category":"RETAIL","timesSubscribed":1.18,"observedAt":"2026-09-29T11:30:00Z"},{"category":"RETAIL","timesSubscribed":2.41,"observedAt":"2026-09-30T11:30:00Z"}]},
               "latest":{"OVERALL":{"category":"OVERALL","timesSubscribed":2.31,"observedAt":"2026-10-01T05:10:00Z"}}},
             "retailAllocationEstimate":{"category":"RETAIL","probability":{"state":"AVAILABLE","value":0.25},"oversubscription":{"state":"AVAILABLE","value":3.92}}}
        """))
        assertEquals(listOf("ipoji.com", "other.in"), ipo.gmp!!.readings.map { it.source })
        assertEquals(40.5, ipo.gmp!!.readings[1].premium, 1e-9)
        assertNull(ipo.gmp!!.readings[1].premiumPercent)
        assertEquals(2, ipo.subscription!!.series.getValue("RETAIL").size)
        assertEquals(2.31, ipo.subscription!!.latest.getValue("OVERALL").times, 1e-9)
        assertEquals(0.25, ipo.retailAllocation!!.probability!!, 1e-9)
    }

    @Test
    fun parsesDetailDatesVerdictsAndOutcome() {
        val d = IpoDetailDto.parse(JSONObject("""
            {"summary":{"id":"kaveri","companyName":"Kaveri Hospitals"},
             "keyDates":[{"label":"Allotment","date":{"state":"AVAILABLE","value":"2026-10-05"}},{"label":"Refunds","date":{"state":"MISSING","value":null}}],
             "decisionContexts":[{"context":"PARTICIPATION","question":"Is it worth applying?","verdict":"APPLY","confidence":{"state":"AVAILABLE","value":0.8},
               "supporting":[{"supportive":true,"description":"Retail is a lottery past 1x."}],"opposing":[],"uncertainties":["Thin data"]}],
             "outcome":{"issuePrice":{"state":"AVAILABLE","value":434},"listingPrice":{"state":"AVAILABLE","value":479},"listingReturnPercent":{"state":"AVAILABLE","value":10.37},"expectedReturnPercent":{"state":"AVAILABLE","value":9.5}},
             "anchorBook":{"state":"AVAILABLE","totalAmountCrore":552.0},
             "companyOverview":{"state":"AVAILABLE","value":"Runs 14 hospitals."}}
        """))
        assertEquals(java.time.LocalDate.of(2026, 10, 5), d.keyDates.first { it.label == "Allotment" }.date.localDate())
        assertNull(d.keyDates.first { it.label == "Refunds" }.date.localDate())
        val c = d.decisionContexts.single()
        assertEquals("APPLY", c.verdict)
        assertEquals(0.8, c.confidence!!, 1e-9)
        assertEquals("Retail is a lottery past 1x.", c.reason)
        assertEquals(10.37, d.outcome!!.listingReturnPercent!!, 1e-9)
        assertEquals(552.0, d.anchorCrore!!, 1e-9)
        assertEquals("Runs 14 hospitals.", d.overview?.value)
    }

    @Test
    fun stateNoteFlagsStaleMissingAndConflicting() {
        assertNull(IpoValueDto("AVAILABLE", 434, "2026-09-20T00:00:00Z").stateNote())
        assertEquals("as of 20 Sep", IpoValueDto("STALE", 434, "2026-09-20T00:00:00Z").stateNote())
        assertEquals("not out yet", IpoValueDto("MISSING", null, null).stateNote())
        assertEquals("not out yet", (null as IpoValueDto?).stateNote())
        assertEquals("sources disagree", IpoValueDto("CONFLICTING", 434, null).stateNote())
        assertEquals(412.0 to 434.0, IpoValueDto("AVAILABLE", JSONObject("""{"lower":"412","upper":434}"""), null).bounds())
    }

    @Test
    fun parsesEverySubscriptionReadingAndListRowPastFifty() {
        val readings = org.json.JSONArray((0 until 60).map { JSONObject().put("timesSubscribed", it / 10.0) })
        assertEquals(60, IpoSubscriptionDto.parse(JSONObject().put("series", JSONObject().put("RETAIL", readings)))!!.series.getValue("RETAIL").size)
        val rows = org.json.JSONArray((0 until 60).map { JSONObject().put("id", "ipo-$it").put("companyName", "Co $it") })
        assertEquals(60, IpoListItemDto.parseList(rows).size)
    }

    @Test
    fun parsesTheListedSymbol() {
        assertEquals("KAVERI", IpoDetailDto.parse(JSONObject("""{"summary":{"id":"kaveri","companyName":"Kaveri"},"listedSymbol":"KAVERI"}""")).listedSymbol)
        assertNull(IpoDetailDto.parse(JSONObject("""{"summary":{"id":"kaveri","companyName":"Kaveri"},"listedSymbol":null}""")).listedSymbol)
    }
}
