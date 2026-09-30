package com.marksy.os.market

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// The shapes marksy-api's /me/follows, /scorecards `following` and /alerts serve, field for field.
class FollowModelsTest {
    private val headline = """{"total": 11, "open": 1, "completed": 10, "successful": 10, "failed": 0, "expired": 0,
        "invalidated": 0, "successPct": "100.00", "hitRatePct": "100.00", "avgActualReturn": "0.100000", "trustScore": 72}"""

    @Test
    fun parsesFollowsWithTheirHeadlineAndCanonicalKey() {
        val list = FollowListDto.parse(JSONObject("""{"items": [
            {"entityType": "CALLER", "entityId": 2, "name": "Prediction engine", "channelId": 7, "channelName": "Marksy",
             "channelType": "MARKSY", "engine": true, "followedAt": "2026-09-30T04:00:00Z", "headline": $headline},
            {"entityType": "CHANNEL", "entityId": 5, "name": "Fa********up", "channelId": 5, "channelName": "Fa********up",
             "channelType": "WHATSAPP_GROUP", "engine": false, "followedAt": "2026-09-29T04:00:00Z", "headline": $headline}
        ], "limit": 200}"""))

        assertEquals(listOf(FollowKey.caller(2), FollowKey.channel(5)), list.items.map { it.key })
        assertTrue(list.items[0].engine)
        assertEquals(listOf("Prediction engine", "Fa********up"), list.items.map { it.name })
        assertEquals(72, list.items[0].headline.trustScore)
        assertEquals(setOf(FollowKey.caller(2), FollowKey.channel(5)), Follows.keys(list))
    }

    @Test
    fun scorecardsCarryTheRequestersFollowingFlag() {
        val body = """"counts": {}, "performance": {}, "trust": {}"""
        val list = EntityScorecardListDto.parse(JSONObject("""{"version": "SCR-001", "entity": "channel", "filter": {}, "items": [
            {"entity": "channel", "id": 3, "name": "Upstox", "channelId": 3, "channelName": "Upstox", "following": true, $body},
            {"entity": "channel", "id": 4, "name": "Zerodha", "channelId": 4, "channelName": "Zerodha", $body}]}"""))
        val card = ScorecardDto.parse(JSONObject("""{"version": "SCR-001", "scope": "caller", "scopeId": 9, "following": true, $body}"""))

        assertEquals(listOf(true, false), list.items.map { it.following })
        assertEquals(FollowKey.channel(3), Follows.key(list.items[0]))
        assertEquals(FollowKey.caller(9), Follows.key(EntityScorecardDto("caller", 9, "Rahul", 3, "Upstox", list.items[0].body)))
        assertTrue(card.following)
    }

    @Test
    fun keepsOnlyTipAlertsFromTheAlertList() {
        val alerts = TipAlertDto.parseList(JSONObject("""{"alerts": [
            {"id": 3, "alertType": "TIP_NEW", "severity": "MEDIUM", "message": "RENUKA BUY call from Upstox: new call",
             "predictionId": null, "recommendationId": null, "triggeredAt": "2026-09-30T04:00:00Z", "deliveredAt": null,
             "readAt": null, "unread": true, "tipId": "uuid-1"},
            {"id": 2, "alertType": "NEW_OPPORTUNITY", "severity": "MEDIUM", "message": "x", "predictionId": 4,
             "recommendationId": 5, "triggeredAt": "2026-09-30T03:00:00Z", "unread": true, "tipId": null},
            {"id": 1, "alertType": "TIP_CLOSED", "severity": "MEDIUM", "message": "RENUKA BUY call from Upstox: target hit",
             "triggeredAt": "2026-09-29T10:00:00Z", "unread": false, "tipId": "uuid-1"}
        ], "unreadCount": 2}"""))

        assertEquals(listOf(3L to "TIP_NEW", 1L to "TIP_CLOSED"), alerts.map { it.id to it.alertType })
        assertEquals(listOf(true, false), alerts.map { it.unread })
        assertEquals(1_790_740_800_000L, alerts[0].triggeredAt)
        assertEquals("uuid-1", alerts[1].tipId)
    }

    @Test
    fun anOptimisticToggleSettlesOnTheServersCanonicalKey() {
        val start = setOf(FollowKey.channel(3))
        val pending = Follows.toggled(start, FollowKey.channel(7), follow = true)
        val server = FollowDto(FollowKey.channel(1), "Upstox", 1, "Upstox", "BROKER_APP", false, "2026-09-30T04:00:00Z",
            ScorecardHeadlineDto.parse(JSONObject(headline)))

        assertEquals(setOf(FollowKey.channel(3), FollowKey.channel(7)), pending)
        assertEquals(setOf(FollowKey.channel(3), FollowKey.channel(1)), Follows.followed(pending, FollowKey.channel(7), server))
        assertEquals(start, Follows.toggled(pending, FollowKey.channel(7), follow = false))
        assertEquals(setOf(FollowKey.caller(2)), Follows.toggled(null, FollowKey.caller(2), follow = true))
    }

    @Test
    fun theServersSetWinsOverAListsFlagOnceLoaded() {
        assertTrue(Follows.isFollowing(null, FollowKey.channel(3), fallback = true))
        assertFalse(Follows.isFollowing(emptySet(), FollowKey.channel(3), fallback = true))
        assertTrue(Follows.isFollowing(setOf(FollowKey.channel(3)), FollowKey.channel(3), fallback = false))
    }
}
