package com.marksy.os.market

/** Advances / declines / unchanged across a basket, from each stock's day change in percent. */
data class MarketBreadth(val advances: Int, val declines: Int, val unchanged: Int) {
    val total get() = advances + declines + unchanged

    data class Traded(val volume: Long?, val price: Double?) {
        val value: Double? get() = if (volume != null && price != null && volume > 0) volume * price else null
    }

    companion object {
        // Below a hundredth of a percent a move is noise from rounding, so it counts as unchanged.
        private const val FLAT_PCT = 0.01

        fun of(changesPct: List<Double?>): MarketBreadth {
            val known = changesPct.filterNotNull()
            return MarketBreadth(known.count { it >= FLAT_PCT }, known.count { it <= -FLAT_PCT }, known.count { kotlin.math.abs(it) < FLAT_PCT })
        }

        /** Most active by traded value (volume × average price), the way exchanges rank "most active by value". */
        fun mostActive(traded: Map<String, Traded>, limit: Int = 6): List<Pair<String, Double>> =
            traded.mapNotNull { (s, t) -> t.value?.let { s to it } }.sortedByDescending { it.second }.take(limit)
    }
}
