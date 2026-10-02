package com.marksy.os.upstox

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException

/** One `GET /v2/portfolio/long-term-holdings` entry as Upstox sends it. */
data class UpstoxHolding(
    val isin: String,
    val companyName: String,
    val tradingSymbol: String,
    val instrumentToken: String,
    val exchange: String,
    val quantity: Long,
    val averagePrice: Double,
    val lastPrice: Double,
    val closePrice: Double?
)

object UpstoxHoldings {
    fun parse(body: String): List<UpstoxHolding> = try {
        val root = JSONObject(body)
        if (root.optString("status") != "success") {
            val message = root.optJSONArray("errors")?.optJSONObject(0)?.optString("message")?.takeIf { it.isNotBlank() }
            throw IOException("Upstox: ${message ?: "holdings request failed"}")
        }
        val data = root.optJSONArray("data") ?: JSONArray()
        (0 until data.length()).mapNotNull { i -> data.optJSONObject(i)?.let(::entry) }
    } catch (e: JSONException) {
        throw IOException("Upstox returned unreadable holdings", e)
    }

    private fun entry(o: JSONObject): UpstoxHolding? {
        val symbol = o.text("trading_symbol") ?: o.text("tradingsymbol") ?: return null
        val key = o.text("instrument_token") ?: return null
        return UpstoxHolding(
            isin = o.text("isin").orEmpty(),
            companyName = o.text("company_name") ?: symbol,
            tradingSymbol = symbol,
            instrumentToken = key,
            exchange = o.text("exchange") ?: key.substringBefore('_'),
            quantity = o.optLong("quantity"),
            averagePrice = o.optDouble("average_price", 0.0),
            lastPrice = o.optDouble("last_price", 0.0),
            closePrice = o.num("close_price")?.takeIf { it > 0 }
        )
    }

    private fun JSONObject.text(name: String): String? = if (isNull(name)) null else optString(name).takeIf { it.isNotBlank() }
    private fun JSONObject.num(name: String): Double? = if (has(name) && !isNull(name)) optDouble(name).takeIf { !it.isNaN() } else null
}
