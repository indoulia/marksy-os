package com.marksy.os.upstox

import org.json.JSONException
import org.json.JSONObject
import java.io.IOException

/** Upstox instrument keys for the indices Home shows; fixed by Upstox, no catalog lookup needed. */
object UpstoxIndices {
    const val NIFTY_50 = "NSE_INDEX|Nifty 50"
    const val BANK_NIFTY = "NSE_INDEX|Nifty Bank"
    const val SENSEX = "BSE_INDEX|SENSEX"
    const val NIFTY_IT = "NSE_INDEX|Nifty IT"
    const val FIN_NIFTY = "NSE_INDEX|Nifty Fin Service"
    // Exact key from Upstox's instrument master (assets.upstox.com/.../NSE.json.gz).
    const val NIFTY_DEFENCE = "NSE_INDEX|Nifty Ind Defence"
    val HOME = listOf(
        NIFTY_50 to "NIFTY 50", SENSEX to "SENSEX",
        BANK_NIFTY to "BANKNIFTY", FIN_NIFTY to "FINNIFTY",
        NIFTY_IT to "NIFTY IT", NIFTY_DEFENCE to "DEFENCE"
    )
}

/** One `GET /v3/market-quote/ltp` entry. `previousClose` is Upstox's `cp`. */
data class UpstoxLtp(val instrumentKey: String, val lastPrice: Double, val previousClose: Double?) {
    val changePct: Double? get() = previousClose?.takeIf { it > 0 }?.let { (lastPrice - it) / it * 100 }

    companion object {
        /** Keyed by instrument key (`NSE_INDEX|Nifty 50`); Upstox keys `data` by `NSE_INDEX:Nifty 50`. */
        fun parseResponse(body: String): Map<String, UpstoxLtp> = try {
            val root = JSONObject(body)
            if (root.optString("status") != "success") {
                val message = root.optJSONArray("errors")?.optJSONObject(0)?.optString("message")?.takeIf { it.isNotBlank() }
                throw IOException("Upstox: ${message ?: "request failed"}")
            }
            val data = root.getJSONObject("data")
            data.keys().asSequence().associate { key ->
                val quote = data.getJSONObject(key)
                val instrumentKey = quote.optString("instrument_token").takeIf { it.isNotBlank() } ?: key.replaceFirst(':', '|')
                instrumentKey to UpstoxLtp(
                    instrumentKey = instrumentKey,
                    lastPrice = quote.getDouble("last_price"),
                    previousClose = if (quote.has("cp") && !quote.isNull("cp")) quote.getDouble("cp") else null
                )
            }
        } catch (e: JSONException) {
            throw IOException("Upstox returned an unreadable response", e)
        }
    }
}
