package com.marksy.os.upstox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/** Builds protobuf bytes shaped like Upstox's MarketDataFeedV3 `FeedResponse`. */
private class Proto {
    private val out = ByteArrayOutputStream()
    private fun varint(value: Long) { var v = value; while (v and 0x7FL.inv() != 0L) { out.write(((v and 0x7F) or 0x80).toInt()); v = v ushr 7 }; out.write(v.toInt()) }
    fun tag(field: Int, wire: Int) = varint(((field shl 3) or wire).toLong())
    fun int(field: Int, value: Long) = apply { tag(field, 0); varint(value) }
    fun double(field: Int, value: Double) = apply { tag(field, 1); val bits = java.lang.Double.doubleToLongBits(value); for (i in 0 until 8) out.write(((bits ushr (8 * i)) and 0xFF).toInt()) }
    fun string(field: Int, value: String) = bytes(field, value.toByteArray())
    fun message(field: Int, nested: Proto) = bytes(field, nested.toByteArray())
    private fun bytes(field: Int, value: ByteArray) = apply { tag(field, 2); varint(value.size.toLong()); out.write(value) }
    fun toByteArray(): ByteArray = out.toByteArray()
}

private fun ltpc(ltp: Double, cp: Double) = Proto().double(1, ltp).int(2, 1758791400000).int(3, 0).double(4, cp)

class UpstoxFeedDecoderTest {
    @Test
    fun decodesLtpcModeFeedsKeyedByInstrument() {
        val bytes = Proto()
            .int(1, 1) // live_feed
            .message(2, Proto().string(1, "NSE_INDEX|Nifty 50").message(2, Proto().message(1, ltpc(23133.4, 23063.1)).int(4, 0)))
            .message(2, Proto().string(1, "BSE_INDEX|SENSEX").message(2, Proto().message(1, ltpc(73908.2, 73585.0))))
            .int(3, 1758791400123)
            .toByteArray()

        val quotes = UpstoxFeedDecoder.decode(bytes)

        assertEquals(23133.4, quotes.getValue(UpstoxIndices.NIFTY_50).lastPrice, 0.001)
        assertEquals(0.30, quotes.getValue(UpstoxIndices.NIFTY_50).changePct!!, 0.01)
        assertEquals(73908.2, quotes.getValue(UpstoxIndices.SENSEX).lastPrice, 0.001)
    }

    @Test
    fun decodesIndexFullFeedLtpc() {
        val fullFeed = Proto().message(2, Proto().message(1, ltpc(55687.0, 55437.5))) // FullFeed.indexFF.ltpc
        val bytes = Proto().int(1, 0).message(2, Proto().string(1, UpstoxIndices.BANK_NIFTY).message(2, Proto().message(2, fullFeed))).toByteArray()

        assertEquals(55687.0, UpstoxFeedDecoder.decode(bytes).getValue(UpstoxIndices.BANK_NIFTY).lastPrice, 0.001)
    }

    // Regression: after 15:30 every screen still said LIVE because only the socket state was checked.
    @Test
    fun decodesSegmentMarketStatusFromMarketInfo() {
        val marketInfo = Proto()
            .message(1, Proto().string(1, "NSE_EQ").int(2, 3))      // NORMAL_CLOSE
            .message(1, Proto().string(1, "NSE_INDEX").int(2, 3))
        val bytes = Proto().int(1, 2).message(4, marketInfo).toByteArray()

        val frame = UpstoxFeedDecoder.decodeFrame(bytes)

        assertEquals(false, frame.nseOpen)
        val open = Proto().int(1, 2).message(4, Proto().message(1, Proto().string(1, "NSE_EQ").int(2, 2))).toByteArray() // NORMAL_OPEN
        assertEquals(true, UpstoxFeedDecoder.decodeFrame(open).nseOpen)
    }

    @Test
    fun marketInfoOnlyMessageYieldsNoQuotesAndSkipsUnknownFields() {
        val bytes = Proto().int(1, 2).int(3, 1).message(4, Proto().string(1, "NSE_EQ")).double(9, 1.0).toByteArray()

        assertTrue(UpstoxFeedDecoder.decode(bytes).isEmpty())
    }
}
