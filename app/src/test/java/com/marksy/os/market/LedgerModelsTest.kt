package com.marksy.os.market

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// The shapes marksy-api Phase 3b serves (plan Tasks 4-6), field for field.
class LedgerModelsTest {
    private val progress = """{"sessionDate": "2026-09-22", "sessionIndex": 2, "entryStatus": "ENTERED", "statusAfter": "ACTIVE",
        "returnToDate": "0.020000", "bestReturn": "0.020000", "worstReturn": "0.000000", "toTargetPct": "5.000000",
        "toStopPct": "4.000000", "barBasis": "DAILY", "dataBasis": "PROVISIONAL"}"""
    private val headline = """{"total": 11, "open": 1, "completed": 10, "successful": 10, "failed": 0, "expired": 0,
        "invalidated": 0, "successPct": "100.00", "hitRatePct": "100.00", "avgActualReturn": "0.100000", "trustScore": 72}"""
    private val filter = """{"period": "LAST_7_DAYS", "startDate": "2026-09-23", "endDate": "2026-09-29", "basis": "first_seen",
        "horizon": null, "channelId": null, "callerId": null, "startAt": "2026-09-22T18:30:00Z", "endAt": "2026-09-29T18:30:00Z",
        "asOf": "2026-09-29T10:00:00Z"}"""
    private val body = """"counts": {"total": 17, "open": 2, "successful": 7, "failed": 3, "expired": 2, "completed": 12,
        "exited": 2, "invalidated": 1, "unscorable": 1, "dataUnresolved": 1},
        "performance": {"successPct": "70.00", "failurePct": "30.00", "hitRatePct": "58.33", "avgActualReturn": "0.026667",
        "totalActualReturn": "0.320000", "avgPromisedReturn": "0.087778", "totalPromisedReturn": "0.790000",
        "returnRealizationPct": "39.24", "avgDaysToCompletion": "9.67"},
        "trust": {"trustScore": 28, "completed": 12, "minimumCompleted": 10, "wilsonLowerBound": "0.319507",
        "returnQuality": "0.766667", "invalidated": 1}"""

    private fun ledgerTip(status: String = "TARGET_HIT", latest: String = "null") = """"tipId": "uuid-1", "symbol": "RENUKA",
        "direction": "BUY", "entryLow": "100.000000", "entryHigh": "100.000000", "entryBasis": "STATED", "target": "110.000000",
        "stopLoss": "95.000000", "horizonSessions": 5, "horizonBasis": "STATED", "firstSeenAt": "2026-09-21T04:00:00Z",
        "status": "$status", "entryStatus": "ENTERED", "outcome": "SUCCESS", "reason": null, "enteredSession": 1,
        "closedSession": 2, "closedAt": "2026-09-25T10:00:00Z", "exitPrice": null, "promisedReturn": "0.100000",
        "actualReturn": "0.100000", "predictionId": null, "channel": {"channelId": 3, "name": "Upstox", "type": "BROKER_APP"},
        "caller": {"callerId": 9, "name": "Rahul"}, "latestProgress": $latest"""

    @Test
    fun parsesMyTipsWithTheirReceiptsAndProvisionalProgress() {
        val page = MyTipPageDto.parse(JSONObject("""{"data": [{${ledgerTip("ACTIVE", progress)},
            "channelHeadline": $headline, "callerHeadline": null,
            "receivedVia": [{"receiptId": "r-1", "kind": "TIP", "medium": "SMS", "channelLabel": "UPSTOX",
              "appPackage": "com.upstox.pro", "devicePostedAt": null, "recordedAt": "2026-09-21T04:00:00Z"}],
            "alsoReceivedBy": 2}], "meta": {"pageSize": 50, "nextCursor": "c-2"}}"""))

        val item = page.items.single()
        assertEquals("c-2", page.nextCursor)
        assertEquals(listOf("RENUKA", "ACTIVE", "Upstox", "Rahul"), listOf(item.tip.symbol, item.tip.status, item.tip.channel?.name, item.tip.caller?.name))
        assertEquals(100.0, item.tip.entryLow!!, 1e-9)
        assertNull(item.tip.exitPrice)
        assertEquals(0.02, item.tip.latestProgress!!.returnToDate!!, 1e-9)
        assertEquals("PROVISIONAL", item.tip.latestProgress!!.dataBasis)
        assertEquals(72, item.channelHeadline!!.trustScore)
        assertNull(item.callerHeadline)
        assertEquals(listOf("SMS" to "UPSTOX"), item.receivedVia.map { it.medium to it.channelLabel })
        assertEquals(2, item.alsoReceivedBy)
    }

    @Test
    fun parsesAnInstrumentsCallsByEngineAndChannel() {
        val json = JSONObject("""{"symbol": "RENUKA", "exchange": "NSE", "market": {}, "predictions": [],
          "calls": {"asOf": "2026-09-30T10:00:00Z", "closedWithinDays": 90,
            "marksy": {"engines": [
              {"callerId": 1, "name": "Prediction engine", "channelId": 7, "scorecard": $headline, "tips": [{${ledgerTip("SOURCE_EXIT")}}]},
              {"callerId": 2, "name": "Rating engine", "channelId": 7, "scorecard": $headline, "tips": []}]},
            "external": {"channels": [
              {"channelId": 3, "name": "Upstox", "type": "BROKER_APP", "scorecard": $headline, "tips": [{${ledgerTip()}}]}]}}}""")

        val calls = InstrumentLifecycleDto.parse(json).calls!!

        assertEquals(listOf("Prediction engine", "Rating engine"), calls.engines.map { it.name })
        assertEquals("SOURCE_EXIT", calls.engines[0].tips.single().status)
        assertEquals(emptyList<LedgerTipDto>(), calls.engines[1].tips)
        assertEquals(listOf("Upstox" to "BROKER_APP"), calls.channels.map { it.name to it.type })
        assertEquals(90, calls.closedWithinDays)
        assertNull(InstrumentLifecycleDto.parse(JSONObject("""{"symbol": "NEWCO", "predictions": []}""")).calls)
    }

    @Test
    fun parsesScorecardsAndATipDetailWithDecimalStrings() {
        val card = ScorecardDto.parse(JSONObject("""{"version": "SCR-001", "scope": "channel", "scopeId": 3, "name": "Upstox",
            "channelId": 3, "channelName": "Upstox", "filter": $filter, $body}"""))
        val mine = ScorecardDto.parse(JSONObject("""{"version": "SCR-001", "scope": "customer", "scopeId": null, "name": null,
            "channelId": null, "channelName": null, "filter": $filter, $body}"""))
        val list = EntityScorecardListDto.parse(JSONObject("""{"version": "SCR-001", "entity": "caller", "filter": $filter,
            "items": [{"entity": "caller", "id": 9, "name": "Rahul", "channelId": 3, "channelName": "Upstox", $body}]}"""))
        val summary = ScorecardSummaryDto.parse(JSONObject("""{"version": "SCR-001", "filter": $filter,
            "all": {$body}, "marksy": {$body}, "external": {$body}}"""))
        val detail = TipDetailDto.parse(JSONObject("""{"tipId": "uuid-1", "ledger": {${ledgerTip()}}, "progress": [$progress]}"""))

        assertEquals("3", card.scopeId)
        assertNull(mine.scopeId)
        assertEquals("2026-09-23" to "2026-09-29", card.filter.startDate to card.filter.endDate)
        assertEquals(12, card.body.counts.completed)
        assertEquals(58.33, card.body.performance.hitRatePct!!, 1e-9)
        assertEquals(0.319507, card.body.trust.wilsonLowerBound!!, 1e-9)
        assertEquals(28, card.body.trust.trustScore)
        assertEquals(listOf(9 to "Upstox"), list.items.map { it.id to it.channelName })
        assertEquals(17, summary.marksy.counts.total)
        assertEquals(listOf("2026-09-22" to "PROVISIONAL"), detail.progress.map { it.sessionDate to it.dataBasis })
        assertEquals("TARGET_HIT", detail.ledger!!.status)
        assertNull(TipDetailDto.parse(JSONObject("""{"tipId": "legacy", "ledger": null, "progress": []}""")).ledger)
    }
}
