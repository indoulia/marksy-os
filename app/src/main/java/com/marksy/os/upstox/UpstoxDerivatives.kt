package com.marksy.os.upstox

import org.json.JSONObject
import java.io.IOException

/** Option chain facts from Upstox `/v2/option/contract` and `/v2/option/chain`. */
object UpstoxDerivatives {
    data class Side(val ltp: Double?, val oi: Long?, val volume: Long?, val iv: Double?, val change: Double?)
    data class Strike(val strike: Double, val call: Side?, val put: Side?)

    data class Chain(val expiry: String?, val spot: Double?, val strikes: List<Strike>) {
        private fun totalOi(pick: (Strike) -> Side?) = strikes.sumOf { pick(it)?.oi ?: 0L }
        /** Put/call ratio of open interest across the whole expiry. */
        val pcr: Double? get() = totalOi { it.call }.takeIf { it > 0 }?.let { totalOi { s -> s.put }.toDouble() / it }
        /** Strike with the most call OI: where option writers expect a ceiling. */
        val resistance: Double? get() = strikes.maxByOrNull { it.call?.oi ?: 0L }?.takeIf { (it.call?.oi ?: 0L) > 0 }?.strike
        /** Strike with the most put OI: the floor writers defend. */
        val support: Double? get() = strikes.maxByOrNull { it.put?.oi ?: 0L }?.takeIf { (it.put?.oi ?: 0L) > 0 }?.strike

        /** [each] strikes either side of the spot. */
        fun aroundSpot(each: Int = 4): List<Strike> {
            val s = spot ?: return strikes.take(each * 2)
            val above = strikes.indexOfFirst { it.strike >= s }.let { if (it < 0) strikes.size else it }
            return strikes.subList((above - each).coerceAtLeast(0), (above + each).coerceAtMost(strikes.size))
        }
    }

    /** Option expiries for an underlying, soonest first; empty when it has no F&O. */
    fun expiries(body: String): List<String> = data(body) { root ->
        val a = root.optJSONArray("data") ?: return@data emptyList()
        (0 until a.length()).mapNotNull { a.optJSONObject(it) }
            .filter { it.optString("instrument_type") in setOf("CE", "PE") }
            .mapNotNull { it.optString("expiry").takeIf { e -> e.length >= 10 }?.take(10) }
            .distinct().sorted()
    }

    fun chain(body: String): Chain = data(body) { root ->
        val a = root.optJSONArray("data") ?: return@data Chain(null, null, emptyList())
        val rows = (0 until a.length()).mapNotNull { a.optJSONObject(it) }
        Chain(
            expiry = rows.firstOrNull()?.optString("expiry")?.take(10),
            spot = rows.firstNotNullOfOrNull { it.num("underlying_spot_price") },
            strikes = rows.mapNotNull { r -> r.num("strike_price")?.let { Strike(it, side(r.optJSONObject("call_options")), side(r.optJSONObject("put_options"))) } }.sortedBy { it.strike }
        )
    }

    private fun side(o: JSONObject?): Side? {
        val m = o?.optJSONObject("market_data") ?: return null
        val g = o.optJSONObject("option_greeks")
        val ltp = m.num("ltp")
        return Side(ltp, m.num("oi")?.toLong(), m.num("volume")?.toLong(), g?.num("iv")?.takeIf { it > 0 }, ltp?.let { l -> m.num("close_price")?.takeIf { it > 0 }?.let { l - it } })
    }

    private fun JSONObject.num(name: String): Double? = if (has(name) && !isNull(name)) optDouble(name).takeIf { !it.isNaN() } else null

    private fun <T> data(body: String, read: (JSONObject) -> T): T {
        val root = runCatching { JSONObject(body) }.getOrElse { throw IOException("Upstox returned an unreadable option response") }
        if (root.optString("status") != "success") throw IOException("Upstox: ${root.optJSONArray("errors")?.optJSONObject(0)?.optString("message") ?: "option data unavailable"}")
        return read(root)
    }
}
