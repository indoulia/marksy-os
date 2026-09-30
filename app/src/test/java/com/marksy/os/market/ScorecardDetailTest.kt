package com.marksy.os.market

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScorecardDetailTest {
    private val body = """
        "counts": {"total": 42, "open": 6, "successful": 2, "failed": 26, "expired": 4, "completed": 32, "exited": 0, "invalidated": 3, "unscorable": 1, "dataUnresolved": 0},
        "performance": {"successPct": "6.25", "failurePct": "81.25", "hitRatePct": "6.25", "avgActualReturn": "-0.0191", "totalActualReturn": "-0.61",
                        "avgPromisedReturn": "0.04", "totalPromisedReturn": "1.28", "returnRealizationPct": "-47.66", "avgDaysToCompletion": "3.40"},
        "trust": {"trustScore": 1, "completed": 32, "minimumCompleted": 10, "wilsonLowerBound": "0.017", "returnQuality": "0.1", "invalidated": 3}
    """

    private fun detail(entity: String, callers: String) = JSONObject(
        """{"version": "1", "entity": "$entity",
            "card": {"version": "1", "scope": "$entity", "scopeId": 7, "name": "St*****ps", "channelId": 7, "channelName": null,
                     "filter": {"period": "LIFETIME", "basis": "first_seen", "asOf": "2026-09-30T10:00:00Z"}, $body, "following": true},
            "trustBand": {"low": "0.017302", "high": "0.198211"},
            "byHorizon": [{"horizon": "INTRADAY", "hitRatePct": null, "completed": 0}, {"horizon": "UP_TO_1_WEEK", "hitRatePct": "12.50", "completed": 16}],
            "series": [{"at": "2026-09-02T10:00:00Z", "realisedCum": "-0.02", "promisedCum": "0.04", "n": 1},
                       {"at": "2026-09-09T10:00:00+00:00", "realisedCum": "-0.05", "promisedCum": "0.07", "n": 3}],
            "recent": [{"tipId": "t-9", "symbol": "RSYSTEMS", "direction": "BUY", "firstSeenAt": "2026-09-30T04:00:00Z", "status": "ACTIVE",
                        "target": "450", "stopLoss": "400", "entryLow": "420", "entryHigh": "420",
                        "channel": {"channelId": 7, "name": "St*****ps", "type": "WHATSAPP_GROUP"}, "caller": null}],
            "topSymbols": [{"symbol": "RSYSTEMS", "calls": 2, "avgActualReturn": null}, {"symbol": "TCS", "calls": 1, "avgActualReturn": "0.031"}],
            "callers": $callers}"""
    )

    @Test
    fun theDetailResponseParsesEverySectionWithDecimalStrings() {
        val d = ScorecardDetailDto.parse(detail("channel", """[{"callerId": 11, "name": "Ravi", "scorecard": {"total": 5, "open": 1, "completed": 4, "successful": 1,
            "failed": 3, "expired": 0, "invalidated": 0, "successPct": "25", "hitRatePct": "25", "avgActualReturn": "-0.01", "trustScore": null}}]"""))

        assertEquals("St*****ps", d.card.name)
        assertTrue(d.card.following)
        assertEquals(32, d.card.body.counts.completed)
        assertEquals(-47.66, d.card.body.performance.returnRealizationPct!!, 1e-9)
        assertEquals(TrustBandDto(0.017302, 0.198211), d.trustBand)
        assertNull(d.byHorizon[0].hitRatePct)
        assertEquals(12.5, d.byHorizon[1].hitRatePct!!, 1e-9)
        assertEquals(listOf(-0.02, -0.05), d.series.map { it.realisedCum })
        assertEquals(3, d.series[1].n)
        assertEquals(450.0, d.recent.single().target!!, 1e-9)
        assertEquals("WHATSAPP_GROUP", d.recent.single().channel?.type)
        assertEquals(0.031, d.topSymbols[1].avgActualReturn!!, 1e-9)
        assertEquals(ScorecardSource("caller", 11, "Ravi"), ScorecardSource.caller(d.callers!!.single()))
        assertNull(d.callers.single().scorecard.trustScore)
        assertEquals("WhatsApp group", ScorecardSources.kind(d))
    }

    @Test
    fun aCallerHasNoCallersListAndABandBelowTheMinimumIsNull() {
        val json = detail("caller", "null").put("trustBand", JSONObject.NULL)
        val d = ScorecardDetailDto.parse(json)

        assertNull(d.callers)
        assertNull(d.trustBand)
    }

    @Test
    fun theTrailOpensSourcesInOrderAndBackPopsOnePage() {
        val channel = ScorecardSource("channel", 7, "Marksy | engines")
        val caller = ScorecardSource("caller", 11, "Prediction engine")

        val opened = ScorecardNav.open(ScorecardNav.open(emptyList(), channel), caller)
        assertEquals(listOf(channel, caller), opened)
        // Reopening a page already on the trail returns to it rather than stacking a copy.
        assertEquals(listOf(channel), ScorecardNav.open(opened, channel))
        assertEquals(listOf(channel), ScorecardNav.back(opened))
        assertEquals(emptyList<ScorecardSource>(), ScorecardNav.back(ScorecardNav.back(opened)))
        assertEquals(opened, ScorecardNav.decode(ScorecardNav.encode(opened)))
        assertEquals(emptyList<ScorecardSource>(), ScorecardNav.decode("garbage"))
        assertEquals(FollowKey.channel(7), channel.followKey)
        assertEquals(FollowKey.caller(11), caller.followKey)
    }

    @Test
    fun rowsAndCardsReadTheServersChannelTypeAndEngineFlag() {
        val row = EntityScorecardDto.parse(JSONObject("""{"entity": "caller", "id": 3, "name": "Un*****aj", "channelName": "Fa********up",
            "channelType": "WHATSAPP_GROUP", "engine": false}"""))
        val engine = EntityScorecardDto.parse(JSONObject("""{"entity": "caller", "id": 1, "name": "Prediction engine", "channelName": "Renamed",
            "channelType": "MARKSY", "engine": true}"""))
        val old = EntityScorecardDto.parse(JSONObject("""{"entity": "caller", "id": 1, "name": "Prediction engine", "channelName": "Marksy"}"""))

        assertEquals("WHATSAPP_GROUP" to false, row.channelType to row.engine)
        assertNull(old.channelType)
        assertNull(old.engine)
        assertEquals("WhatsApp group", ScorecardSources.chip(row))
        // The server's flag wins over the name; the name match is only for an older backend without the fields.
        assertTrue(ScorecardSources.isEngine(engine))
        assertFalse(ScorecardSources.isEngine(old.copy(engine = false)))
        assertTrue(ScorecardSources.isEngine(old))
        assertEquals(
            listOf("Engine", "Broker", "News", "SMS", "WhatsApp group", "Telegram", "YouTube"),
            listOf("MARKSY", "BROKER_APP", "NEWS_PORTAL", "SMS_SENDER", "WHATSAPP_GROUP", "TELEGRAM_CHANNEL", "YOUTUBE").map { t -> ScorecardSources.chip(row.copy(channelType = t)) }
        )

        val card = ScorecardDetailDto.parse(detail("channel", "null").apply { getJSONObject("card").put("channelType", "TELEGRAM_CHANNEL").put("engine", false) })
        assertEquals("TELEGRAM_CHANNEL", card.card.channelType)
        assertEquals(false, card.card.engine)
        assertEquals("Telegram", ScorecardSources.kind(card))
    }

    @Test
    fun enginesAreTheCallersOfTheMarksyChannel() {
        fun card(entity: String, name: String, channel: String?) = EntityScorecardDto(entity, 1, name, 7, channel, ScorecardBodyDto.parse(JSONObject()))

        assertTrue(ScorecardSources.isEngine(card("caller", "Prediction engine", "Marksy")))
        assertFalse(ScorecardSources.isEngine(card("caller", "Ravi", "St*****ps")))
        assertFalse(ScorecardSources.isEngine(card("channel", "Marksy", null)))
        assertEquals("Engine", ScorecardSources.chip(card("channel", "Marksy", null)))
        assertEquals("External", ScorecardSources.chip(card("channel", "5paisa", null)))
        assertEquals("St*****ps", ScorecardSources.chip(card("caller", "Ravi", "St*****ps")))
    }
}
