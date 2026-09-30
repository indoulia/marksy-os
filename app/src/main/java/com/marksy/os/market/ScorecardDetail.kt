package com.marksy.os.market

import org.json.JSONArray
import org.json.JSONObject

/** The source-detail trust range bar: the Wilson interval as fractions 0–1. */
data class TrustBandDto(val low: Double, val high: Double)

data class HorizonHitDto(val horizon: String, val hitRatePct: Double?, val completed: Int)

/** One return point: sums and running means since the series start, all fractions; the means are null from an older backend. */
data class SeriesPointDto(
    val at: String, val realisedCum: Double, val promisedCum: Double, val n: Int, val realisedAvg: Double? = null, val promisedAvg: Double? = null
)

data class TopSymbolDto(val symbol: String, val calls: Int, val avgActualReturn: Double?)

data class CallerScorecardDto(val callerId: Int, val name: String, val scorecard: ScorecardHeadlineDto)

/** `GET /scorecards/{entity}/{id}/detail`: the whole source-detail page in one round trip. */
data class ScorecardDetailDto(
    val entity: String,
    val card: ScorecardDto,
    val trustBand: TrustBandDto?,
    val byHorizon: List<HorizonHitDto>,
    val series: List<SeriesPointDto>,
    val recent: List<LedgerTipDto>,
    val topSymbols: List<TopSymbolDto>,
    val callers: List<CallerScorecardDto>?
) {
    companion object {
        private fun JSONArray?.all(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

        fun parse(json: JSONObject) = ScorecardDetailDto(
            entity = json.textOrNull("entity") ?: "",
            card = ScorecardDto.parse(json.optJSONObject("card") ?: JSONObject()),
            trustBand = json.optJSONObject("trustBand")?.let { b ->
                val low = b.doubleOrNull("low")
                val high = b.doubleOrNull("high")
                if (low != null && high != null) TrustBandDto(low, high) else null
            },
            byHorizon = json.optJSONArray("byHorizon").all().map { HorizonHitDto(it.textOrNull("horizon") ?: "", it.doubleOrNull("hitRatePct"), it.intOrNull("completed") ?: 0) },
            series = json.optJSONArray("series").all().map {
                SeriesPointDto(
                    it.textOrNull("at") ?: "", it.doubleOrNull("realisedCum") ?: 0.0, it.doubleOrNull("promisedCum") ?: 0.0, it.intOrNull("n") ?: 0,
                    it.doubleOrNull("realisedAvg"), it.doubleOrNull("promisedAvg")
                )
            },
            recent = json.optJSONArray("recent").all().map(LedgerTipDto::parse),
            topSymbols = json.optJSONArray("topSymbols").all().map { TopSymbolDto(it.textOrNull("symbol") ?: "", it.intOrNull("calls") ?: 0, it.doubleOrNull("avgActualReturn")) },
            callers = if (json.isNull("callers")) null else json.optJSONArray("callers").all().map {
                CallerScorecardDto(it.intOrNull("callerId") ?: 0, it.textOrNull("name") ?: "", ScorecardHeadlineDto.parse(it.optJSONObject("scorecard") ?: JSONObject()))
            }
        )
    }
}

/** A source the Scorecards tab can open: `entity` is the server's "channel" or "caller". */
data class ScorecardSource(val entity: String, val id: Int, val name: String) {
    val followKey: FollowKey get() = if (entity == ScorecardEntity.CHANNEL.param) FollowKey.channel(id) else FollowKey.caller(id)

    companion object {
        fun of(card: EntityScorecardDto) = ScorecardSource(card.entity, card.id, card.name)
        fun caller(c: CallerScorecardDto) = ScorecardSource(ScorecardEntity.CALLER.param, c.callerId, c.name)
    }
}

/** Scorecards list → source detail → a channel's caller: back pops one page. */
object ScorecardNav {
    fun open(trail: List<ScorecardSource>, source: ScorecardSource): List<ScorecardSource> =
        trail.indexOfFirst { it.entity == source.entity && it.id == source.id }.let { at -> if (at >= 0) trail.take(at + 1) else trail + source }

    fun back(trail: List<ScorecardSource>): List<ScorecardSource> = trail.dropLast(1)

    fun encode(trail: List<ScorecardSource>): String =
        JSONArray(trail.map { JSONObject().put("e", it.entity).put("i", it.id).put("n", it.name) }).toString()

    fun decode(text: String): List<ScorecardSource> = runCatching {
        JSONArray(text).let { a -> (0 until a.length()).map { a.getJSONObject(it).let { o -> ScorecardSource(o.getString("e"), o.getInt("i"), o.getString("n")) } } }
    }.getOrDefault(emptyList())
}

/** What a scorecard row says about its source beyond the name: the server's `channelType`/`engine`, else a name match. */
object ScorecardSources {
    // Fallback for a backend without `engine`: marksy-api reserves the channel name "Marksy" for its engines' channel.
    private const val MARKSY_CHANNEL = "Marksy"
    private const val MARKSY_TYPE = "MARKSY"
    private val TYPE_LABEL = mapOf(
        MARKSY_TYPE to "Engine", "BROKER_APP" to "Broker", "NEWS_PORTAL" to "News", "SMS_SENDER" to "SMS",
        "WHATSAPP_GROUP" to "WhatsApp group", "TELEGRAM_CHANNEL" to "Telegram", "YOUTUBE" to "YouTube"
    )

    fun typeLabel(type: String?): String? = type?.let(TYPE_LABEL::get)

    fun isEngine(card: EntityScorecardDto): Boolean =
        card.entity == ScorecardEntity.CALLER.param && (card.engine ?: card.channelName.equals(MARKSY_CHANNEL, ignoreCase = true))

    fun chip(card: EntityScorecardDto): String = typeLabel(card.channelType) ?: when {
        card.entity == ScorecardEntity.CHANNEL.param -> if (card.name.equals(MARKSY_CHANNEL, ignoreCase = true)) "Engine" else "External"
        isEngine(card) -> "Engine"
        else -> card.channelName ?: "Caller"
    }

    /** The detail hero's type line: the card's type, else the latest call's channel type. */
    fun kind(d: ScorecardDetailDto): String {
        val type = d.card.channelType ?: d.recent.firstNotNullOfOrNull { it.channel }?.type
        val engine = d.card.engine ?: (type == MARKSY_TYPE || d.card.channelName.equals(MARKSY_CHANNEL, ignoreCase = true))
        return when {
            d.entity == ScorecardEntity.CHANNEL.param -> if (engine) "Engines" else typeLabel(type) ?: "Channel"
            engine -> "Marksy · Engine"
            else -> listOfNotNull(d.card.channelName, typeLabel(type)).joinToString(" · ").ifEmpty { "Caller" }
        }
    }
}
