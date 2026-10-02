package com.marksy.os.portfolio

import org.json.JSONArray
import org.json.JSONObject

enum class HoldingType(val label: String) { STOCK("Stocks"), ETF("ETFs") }

data class Holding(
    val symbol: String,
    val name: String,
    val isin: String,
    val instrumentKey: String,
    val type: HoldingType,
    val quantity: Long,
    val averagePrice: Double,
    val lastPrice: Double,
    val previousClose: Double?,
    val sector: String? = null
)

data class HoldingsSnapshot(val providerId: String, val holdings: List<Holding>, val fetchedAt: Long) {
    fun toJson(): String = JSONObject()
        .put("provider", providerId)
        .put("fetchedAt", fetchedAt)
        .put("holdings", JSONArray().apply {
            holdings.forEach { h ->
                put(
                    JSONObject().put("symbol", h.symbol).put("name", h.name).put("isin", h.isin).put("key", h.instrumentKey)
                        .put("type", h.type.name).put("qty", h.quantity).put("avg", h.averagePrice).put("ltp", h.lastPrice)
                        .put("prev", h.previousClose ?: JSONObject.NULL).put("sector", h.sector ?: JSONObject.NULL)
                )
            }
        })
        .toString()

    companion object {
        fun fromJson(json: String): HoldingsSnapshot? = runCatching {
            val o = JSONObject(json)
            val a = o.getJSONArray("holdings")
            HoldingsSnapshot(
                o.getString("provider"),
                (0 until a.length()).map { i ->
                    val h = a.getJSONObject(i)
                    Holding(
                        h.getString("symbol"), h.getString("name"), h.optString("isin"), h.getString("key"),
                        HoldingType.valueOf(h.getString("type")), h.getLong("qty"), h.getDouble("avg"), h.getDouble("ltp"),
                        if (h.isNull("prev")) null else h.getDouble("prev"), if (h.isNull("sector")) null else h.getString("sector")
                    )
                },
                o.getLong("fetchedAt")
            )
        }.getOrNull()
    }
}

sealed interface HoldingsResult {
    data class Ok(val snapshot: HoldingsSnapshot) : HoldingsResult
    data class SignedOut(val reason: String) : HoldingsResult
    data class Failed(val message: String) : HoldingsResult
}

/** Where holdings come from; one per broker or depository. */
fun interface HoldingsSource {
    suspend fun fetch(now: Long): HoldingsResult
}

/** A connectable holdings provider; no [source] means it is listed as coming soon. */
data class PortfolioProvider(val id: String, val name: String, val note: String, val source: HoldingsSource? = null) {
    val available: Boolean get() = source != null
}

object PortfolioProviders {
    const val UPSTOX = "upstox"

    fun catalog(upstox: HoldingsSource): List<PortfolioProvider> = listOf(
        PortfolioProvider(UPSTOX, "Upstox", "Daily sign-in, read-only", upstox),
        PortfolioProvider("kite", "Kite", "Coming soon"),
        PortfolioProvider("groww", "Groww", "Coming soon"),
        PortfolioProvider("cdsl_nsdl", "CDSL / NSDL", "Coming soon")
    )
}
