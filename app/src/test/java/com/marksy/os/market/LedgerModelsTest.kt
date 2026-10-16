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

    // TPR-001 is additive: a pre-TPR payload still parses, and a stale value arrives as an explicit null.
    @Test
    fun parsesTargetProgressAndToleratesItsAbsence() {
        val available = """{"version": "TPR-001", "status": "AVAILABLE", "reason": null, "value": "60.00", "startValue": "33.33",
            "evaluatedAt": "2026-10-15T04:00:00Z", "price": "104.000000", "priceSessionDate": "2026-10-14",
            "priceAt": "2026-10-14T10:00:00Z", "priceSource": "upstox-v3", "dataBasis": "FINAL", "freshness": "CURRENT",
            "currentReturn": "0.040000"}"""
        val stale = """{"version": "TPR-001", "status": "UNAVAILABLE", "reason": "PRICE_STALE", "value": null, "startValue": "33.33",
            "evaluatedAt": "2026-10-19T12:00:00Z", "price": "104.000000", "priceSessionDate": "2026-10-14",
            "priceAt": "2026-10-14T10:00:00Z", "priceSource": "upstox-v3", "dataBasis": "FINAL", "freshness": "OVERDUE",
            "currentReturn": null}"""

        val old = LedgerTipDto.parse(JSONObject("{${ledgerTip("ACTIVE")}}"))
        val now = LedgerTipDto.parse(JSONObject("{${ledgerTip("ACTIVE")}, \"targetProgress\": $available}")).targetProgress!!
        val paused = LedgerTipDto.parse(JSONObject("{${ledgerTip("ACTIVE")}, \"targetProgress\": $stale}")).targetProgress!!

        assertNull(old.targetProgress)
        assertEquals(listOf("TPR-001", "AVAILABLE", "2026-10-14", "FINAL", "CURRENT"), listOf(now.version, now.status, now.priceSessionDate, now.dataBasis, now.freshness))
        assertEquals(listOf(60.0, 33.33, 104.0, 0.04), listOf(now.value, now.startValue, now.price, now.currentReturn))
        assertNull(now.reason)
        assertEquals("PRICE_STALE", paused.reason)
        assertNull(paused.value)
        assertNull(paused.currentReturn)
    }

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

    // Revamp fields are optional: today's payload parses with them absent, and a payload with them keeps every value.
    @Test
    fun parsesRevampFieldsAndToleratesTheirAbsence() {
        val old = LedgerTipDto.parse(JSONObject("{${ledgerTip()}}"))
        assertNull(old.recommendedPrice)
        assertNull(old.marksyView)
        assertNull(old.predictionScore)
        val scored = LedgerTipDto.parse(JSONObject("""{${ledgerTip()}, "predictionScore": {"version": "MSV-001", "status": "VALIDATED",
            "value": 72, "reason": null, "evaluatedAt": "2026-10-13T05:12:00Z", "provenance": "RECONSTRUCTED"}}""")).predictionScore!!
        assertEquals(PredictionScoreDto("MSV-001", "VALIDATED", 72, null, "2026-10-13T05:12:00Z", "RECONSTRUCTED"), scored)
        assertNull(PredictionScoreDto.parse(JSONObject("""{"version": "MSV-001", "status": "NOT_VALIDATED", "value": null}""")).value)
        assertNull(old.repeats)
        assertEquals(emptyList<AlsoTippedDto>(), old.alsoBy)
        assertNull(ProgressPointDto.parse(JSONObject(progress)).close)

        val tip = LedgerTipDto.parse(JSONObject("""{${ledgerTip()}, "recommendedPrice": "130450.000000", "enteredPrice": "130620.000000",
            "enteredOn": "2026-10-13", "buyBy": "2026-10-17", "horizonEndsOn": "2026-10-27", "sessionsElapsed": 2, "repeats": 3,
            "marksyView": {"probability": "0.724", "verdict": "AGREES", "comparedAt": "2026-10-13T05:00:00Z"},
            "alsoBy": [{"tipId": "uuid-2", "channel": {"channelId": 4, "name": "Options Desk", "type": "WHATSAPP_GROUP"}, "caller": null,
                "firstSeenAt": "2026-10-11T04:00:00Z", "recommendedPrice": "4398.00", "status": "ACTIVE", "entryStatus": "ENTERED",
                "returnToDate": "0.030100", "actualReturn": null}]}"""))
        assertEquals(130450.0, tip.recommendedPrice!!, 0.0)
        assertEquals(130620.0, tip.enteredPrice!!, 0.0)
        assertEquals(listOf("2026-10-13", "2026-10-17", "2026-10-27"), listOf(tip.enteredOn, tip.buyBy, tip.horizonEndsOn))
        assertEquals(2, tip.sessionsElapsed)
        assertEquals(3, tip.repeats)
        assertEquals(0.724, tip.marksyView!!.probability!!, 1e-9)
        assertEquals("AGREES", tip.marksyView!!.verdict)
        val also = tip.alsoBy.single()
        assertEquals("uuid-2", also.tipId)
        assertEquals("Options Desk", also.channel!!.name)
        assertNull(also.caller)
        assertEquals(0.0301, also.returnToDate!!, 1e-9)
        assertNull(also.actualReturn)
    }

    // `GET /tips/{id}/progress` may list the sessions under `data` or `data.progress`; each may carry its close.
    @Test
    fun parsesTheProgressListWithCloses() {
        val withClose = progress.dropLast(1) + """, "close": "4530.40"}"""
        assertEquals(listOf(4530.4), ProgressPointDto.parseList(JSONObject("""{"data": [$withClose]}""")).map { it.close })
        assertEquals(listOf("2026-09-22"), ProgressPointDto.parseList(JSONObject("""{"data": {"progress": [$progress]}}""")).map { it.sessionDate })
        assertEquals(emptyList<ProgressPointDto>(), ProgressPointDto.parseList(JSONObject("""{"data": null}""")))
    }
}
