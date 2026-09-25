package com.marksy.os.upstox

import java.io.IOException

/**
 * Minimal protobuf reader for Upstox's MarketDataFeedV3 `FeedResponse`, decoding only what Marksy
 * shows: each feed's LTPC (last price + previous close). Field numbers per MarketDataFeedV3.proto:
 * FeedResponse{1 type, 2 map<string,Feed> feeds, 3 currentTs, 4 marketInfo};
 * Feed{1 ltpc, 2 fullFeed, 3 firstLevelWithGreeks, 4 requestMode}; FullFeed{1 marketFF, 2 indexFF}
 * (both carry ltpc as field 1); LTPC{1 ltp double, 2 ltt, 3 ltq, 4 cp double}.
 */
object UpstoxFeedDecoder {
    /** [nseOpen] is set only by a market_info frame (FeedResponse field 4); null means "not in this frame". */
    data class Frame(val quotes: Map<String, UpstoxLtp>, val nseOpen: Boolean?)

    fun decode(bytes: ByteArray): Map<String, UpstoxLtp> = decodeFrame(bytes).quotes

    fun decodeFrame(bytes: ByteArray): Frame {
        val quotes = mutableMapOf<String, UpstoxLtp>()
        var nseOpen: Boolean? = null
        Reader(bytes, 0, bytes.size).forEachField { field, r ->
            when (field) {
                2 -> feedEntry(r.message())?.let { quotes[it.instrumentKey] = it }
                // A malformed market_info must not cost the frame its quotes.
                4 -> { val info = r.message(); nseOpen = runCatching { nseOpen(info) }.getOrNull() ?: nseOpen }
                else -> r.skip()
            }
        }
        return Frame(quotes, nseOpen)
    }

    /** MarketInfo{1 map<string, MarketStatus> segmentStatus}; MarketStatus NORMAL_OPEN = 2. NSE_EQ decides, NSE_INDEX as fallback. */
    private fun nseOpen(info: Reader): Boolean? {
        val status = mutableMapOf<String, Long>()
        info.forEachField { field, r ->
            if (field == 1) {
                var key: String? = null; var value = 0L
                r.message().forEachField { f, e -> when (f) { 1 -> key = e.string(); 2 -> value = e.varint(); else -> e.skip() } }
                key?.let { status[it] = value }
            } else r.skip()
        }
        return (status["NSE_EQ"] ?: status["NSE_INDEX"])?.let { it == NORMAL_OPEN }
    }

    private const val NORMAL_OPEN = 2L

    private fun feedEntry(entry: Reader): UpstoxLtp? {
        var key: String? = null
        var ltpc: Pair<Double, Double?>? = null
        entry.forEachField { field, r ->
            when (field) {
                1 -> key = r.string()
                2 -> ltpc = feed(r.message())
                else -> r.skip()
            }
        }
        val k = key ?: return null
        val (ltp, cp) = ltpc ?: return null
        return UpstoxLtp(k, ltp, cp)
    }

    private fun feed(feed: Reader): Pair<Double, Double?>? {
        var result: Pair<Double, Double?>? = null
        feed.forEachField { field, r ->
            when (field) {
                1 -> result = ltpc(r.message())
                2, 3 -> result = firstLtpc(r.message(), nested = field == 2) ?: result
                else -> r.skip()
            }
        }
        return result
    }

    /** FullFeed wraps marketFF/indexFF, each with ltpc at field 1; FirstLevelWithGreeks has ltpc at field 1 directly. */
    private fun firstLtpc(msg: Reader, nested: Boolean): Pair<Double, Double?>? {
        var result: Pair<Double, Double?>? = null
        msg.forEachField { field, r ->
            if (field == 1 && !nested) result = ltpc(r.message())
            else if (nested && (field == 1 || field == 2)) result = firstLtpc(r.message(), nested = false) ?: result
            else r.skip()
        }
        return result
    }

    private fun ltpc(msg: Reader): Pair<Double, Double?>? {
        var ltp: Double? = null
        var cp: Double? = null
        msg.forEachField { field, r ->
            when (field) {
                1 -> ltp = r.double()
                4 -> cp = r.double()
                else -> r.skip()
            }
        }
        return ltp?.let { it to cp?.takeIf { c -> c > 0 } }
    }

    private class Reader(private val buf: ByteArray, private var pos: Int, private val end: Int) {
        private var wire = 0

        inline fun forEachField(block: (Int, Reader) -> Unit) {
            while (pos < end) {
                val tag = varint().toInt()
                wire = tag and 7
                block(tag ushr 3, this)
            }
        }

        fun varint(): Long {
            var result = 0L
            var shift = 0
            while (true) {
                if (pos >= end) throw IOException("Truncated Upstox feed")
                val b = buf[pos++].toInt()
                result = result or ((b and 0x7F).toLong() shl shift)
                if (b and 0x80 == 0) return result
                shift += 7
                if (shift > 63) throw IOException("Malformed Upstox feed varint")
            }
        }

        fun double(): Double {
            if (wire != 1 || pos + 8 > end) { skip(); return Double.NaN }
            var bits = 0L
            for (i in 0 until 8) bits = bits or ((buf[pos + i].toLong() and 0xFF) shl (8 * i))
            pos += 8
            return java.lang.Double.longBitsToDouble(bits)
        }

        fun message(): Reader {
            val len = varint().toInt()
            if (len < 0 || pos + len > end) throw IOException("Truncated Upstox feed message")
            return Reader(buf, pos, pos + len).also { pos += len }
        }

        fun string(): String {
            val len = varint().toInt()
            if (len < 0 || pos + len > end) throw IOException("Truncated Upstox feed string")
            return String(buf, pos, len, Charsets.UTF_8).also { pos += len }
        }

        fun skip() {
            when (wire) {
                0 -> varint()
                1 -> pos += 8
                // Read the length first: `pos += varint()` would add to pos from before the length bytes.
                2 -> { val len = varint().toInt(); pos += len }
                5 -> pos += 4
                else -> throw IOException("Unsupported protobuf wire type $wire")
            }
            if (pos > end) throw IOException("Truncated Upstox feed")
        }
    }
}
