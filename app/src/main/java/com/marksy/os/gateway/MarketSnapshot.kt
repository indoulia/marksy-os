package com.marksy.os.gateway

import org.json.JSONArray
import org.json.JSONObject

/** Market context from Marksy `GET /dashboard/snapshot`; replaces the hard-coded index and signal cards. */
data class MarketSnapshot(
    val marketStatus: String?,
    val indices: List<MarketIndex>,
    val opportunities: List<MarketOpportunity>,
    val gainers: List<MarketMover>,
    val losers: List<MarketMover>,
    val asOf: String?
) {
    data class MarketIndex(val name: String, val value: Double, val changePct: Double, val change: Double?)

    data class MarketOpportunity(
        val symbol: String,
        val name: String,
        val price: Double?,
        val changePct: Double?,
        val entryPrice: Double?,
        val targetPrice: Double,
        val stopLoss: Double,
        val upsidePct: Double?,
        val horizonDays: Int?,
        val confidence: Double,
        val score: Double?,
        val status: String?
    )

    data class MarketMover(val symbol: String, val name: String, val price: Double?, val changePct: Double)

    companion object {
        private const val MAX_ITEMS = 20

        fun parse(data: JSONObject): MarketSnapshot = MarketSnapshot(
            marketStatus = data.text("marketStatus"),
            indices = data.optJSONArray("indices").objects().mapNotNull { item ->
                val name = item.text("name") ?: return@mapNotNull null
                MarketIndex(name, item.number("value") ?: return@mapNotNull null, item.number("changePct") ?: 0.0, item.number("change"))
            },
            opportunities = data.optJSONArray("topOpportunities").objects().mapNotNull { item ->
                val symbol = item.text("symbol") ?: return@mapNotNull null
                MarketOpportunity(
                    symbol = symbol,
                    name = item.text("name") ?: symbol,
                    price = item.number("price"),
                    changePct = item.number("changePct"),
                    entryPrice = item.number("entryPrice"),
                    targetPrice = item.number("targetPrice") ?: return@mapNotNull null,
                    stopLoss = item.number("stopLoss") ?: return@mapNotNull null,
                    upsidePct = item.number("upsidePercent"),
                    horizonDays = item.number("horizon")?.toInt(),
                    confidence = item.number("confidence") ?: 0.0,
                    score = item.number("score"),
                    status = item.text("status")
                )
            },
            gainers = data.optJSONArray("topGainers").movers(),
            losers = data.optJSONArray("topLosers").movers(),
            asOf = data.optJSONObject("dataFreshness")?.text("marketAsOf")
        )

        private fun JSONArray?.movers(): List<MarketMover> = objects().mapNotNull { item ->
            val symbol = item.text("symbol") ?: return@mapNotNull null
            MarketMover(symbol, item.text("name") ?: symbol, item.number("price"), item.number("changePercent") ?: return@mapNotNull null)
        }

        private fun JSONArray?.objects(): List<JSONObject> =
            if (this == null) emptyList() else (0 until minOf(length(), MAX_ITEMS)).mapNotNull { optJSONObject(it) }

        private fun JSONObject.text(name: String): String? =
            if (isNull(name)) null else optString(name).trim().take(200).ifBlank { null }

        private fun JSONObject.number(name: String): Double? =
            if (isNull(name)) null else optDouble(name).takeIf { it.isFinite() }
    }
}
