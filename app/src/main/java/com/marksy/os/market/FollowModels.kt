package com.marksy.os.market

import org.json.JSONObject
import java.time.Instant

/** A followed channel or caller; a Marksy engine is a caller. `id` is the server's canonical id. */
data class FollowKey(val type: String, val id: Int) {
    companion object {
        const val CHANNEL = "CHANNEL"
        const val CALLER = "CALLER"
        fun channel(id: Int) = FollowKey(CHANNEL, id)
        fun caller(id: Int) = FollowKey(CALLER, id)
    }
}

/** One `/me/follows` item: a private channel's name arrives already masked unless the customer holds a receipt from it. */
data class FollowDto(
    val key: FollowKey,
    val name: String,
    val channelId: Int?,
    val channelName: String?,
    val channelType: String?,
    val engine: Boolean,
    val followedAt: String,
    val headline: ScorecardHeadlineDto
) {
    companion object {
        fun parse(json: JSONObject) = FollowDto(
            key = FollowKey(json.textOrNull("entityType") ?: FollowKey.CHANNEL, json.intOrNull("entityId") ?: 0),
            name = json.textOrNull("name") ?: "",
            channelId = json.intOrNull("channelId"),
            channelName = json.textOrNull("channelName"),
            channelType = json.textOrNull("channelType"),
            engine = json.boolOrFalse("engine"),
            followedAt = json.textOrNull("followedAt") ?: "",
            headline = ScorecardHeadlineDto.parse(json.optJSONObject("headline") ?: JSONObject())
        )
    }
}

data class FollowListDto(val items: List<FollowDto>, val limit: Int) {
    companion object {
        fun parse(json: JSONObject) = FollowListDto(
            json.optJSONArray("items").let { a -> if (a == null) emptyList() else (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let(FollowDto::parse) } },
            json.intOrNull("limit") ?: 0
        )
    }
}

/** A `/alerts` item about a ledger tip: new from a followed channel or caller, entered, or closed. */
data class TipAlertDto(val id: Long, val alertType: String, val message: String, val tipId: String?, val triggeredAt: Long, val unread: Boolean) {
    companion object {
        val TYPES = setOf("TIP_NEW", "TIP_ENTERED", "TIP_CLOSED")

        fun parseList(json: JSONObject): List<TipAlertDto> = json.optJSONArray("alerts").objects()
            .filter { it.textOrNull("alertType") in TYPES }
            .map {
                TipAlertDto(
                    id = it.longOrNull("id") ?: 0L, alertType = it.textOrNull("alertType").orEmpty(), message = it.textOrNull("message").orEmpty(),
                    tipId = it.textOrNull("tipId"),
                    triggeredAt = it.textOrNull("triggeredAt")?.let { at -> runCatching { Instant.parse(at).toEpochMilli() }.getOrNull() } ?: 0L,
                    unread = it.boolOrFalse("unread")
                )
            }
    }
}

/** The follow set is always the server's; these only apply an optimistic change and settle it on the server's answer. */
object Follows {
    fun keys(list: FollowListDto): Set<FollowKey> = list.items.mapTo(LinkedHashSet()) { it.key }

    fun key(card: EntityScorecardDto): FollowKey = FollowKey(if (card.entity == ScorecardEntity.CHANNEL.param) FollowKey.CHANNEL else FollowKey.CALLER, card.id)

    fun toggled(set: Set<FollowKey>?, key: FollowKey, follow: Boolean): Set<FollowKey> = set.orEmpty().let { if (follow) it + key else it - key }

    /** A follow of a merged id settles on the canonical id the server returned. */
    fun followed(set: Set<FollowKey>?, requested: FollowKey, server: FollowDto): Set<FollowKey> = set.orEmpty() - requested + server.key

    /** The loaded set wins; before it loads, the flag the list itself carried. */
    fun isFollowing(set: Set<FollowKey>?, key: FollowKey, fallback: Boolean): Boolean = set?.contains(key) ?: fallback
}
