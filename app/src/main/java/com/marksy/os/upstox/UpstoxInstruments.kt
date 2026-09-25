package com.marksy.os.upstox

import android.content.Context
import android.util.JsonReader
import com.marksy.os.ai.DiagLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

/**
 * NSE symbol → Upstox instrument key (equities are ISIN-keyed, e.g. `NSE_EQ|INE…`). Built from
 * Upstox's public instrument master, cached on-device for a day. Needs no token.
 */
object UpstoxInstruments {
    private const val MASTER_URL = "https://assets.upstox.com/market-quote/instruments/exchange/NSE.json.gz"
    private const val CACHE_FILE = "upstox_nse_instruments.tsv"
    private const val MAX_AGE_MS = 24 * 60 * 60 * 1000L

    // Names Marksy's feeds use for indices, which don't match Upstox's trading symbols.
    private val INDEX_ALIASES = mapOf(
        "NIFTY 50" to UpstoxIndices.NIFTY_50, "NIFTY" to UpstoxIndices.NIFTY_50,
        "BANK NIFTY" to UpstoxIndices.BANK_NIFTY, "NIFTY BANK" to UpstoxIndices.BANK_NIFTY, "BANKNIFTY" to UpstoxIndices.BANK_NIFTY,
        "SENSEX" to UpstoxIndices.SENSEX, "FIN NIFTY" to UpstoxIndices.FIN_NIFTY, "FINNIFTY" to UpstoxIndices.FIN_NIFTY,
        "NIFTY IT" to UpstoxIndices.NIFTY_IT, "DEFENCE" to UpstoxIndices.NIFTY_DEFENCE
    )

    @Volatile private var map: Map<String, String>? = null
    private val mutex = Mutex()

    fun keyFor(symbol: String): String? {
        val s = symbol.trim().uppercase()
        return INDEX_ALIASES[s] ?: map?.get(s)
    }

    suspend fun load(context: Context): Map<String, String> = map ?: mutex.withLock {
        map ?: withContext(Dispatchers.IO) {
            val file = File(context.applicationContext.filesDir, CACHE_FILE)
            val cached = file.takeIf { it.exists() && System.currentTimeMillis() - it.lastModified() < MAX_AGE_MS }?.let(::readCache)
            cached ?: download().also { writeCache(file, it) }
        }.also { map = it }
    }

    /** Keeps NSE equities (by trading symbol) and NSE indices (by name and trading symbol). */
    internal fun parse(stream: InputStream): Map<String, String> {
        val out = HashMap<String, String>(8192)
        JsonReader(InputStreamReader(stream, Charsets.UTF_8)).use { reader ->
            reader.beginArray()
            while (reader.hasNext()) {
                var segment: String? = null; var symbol: String? = null; var key: String? = null; var name: String? = null
                reader.beginObject()
                while (reader.hasNext()) {
                    when (reader.nextName()) {
                        "segment" -> segment = reader.nextStringOrNull()
                        "trading_symbol" -> symbol = reader.nextStringOrNull()
                        "instrument_key" -> key = reader.nextStringOrNull()
                        "name" -> name = reader.nextStringOrNull()
                        else -> reader.skipValue()
                    }
                }
                reader.endObject()
                val k = key ?: continue
                when (segment) {
                    "NSE_EQ" -> symbol?.let { out.putIfAbsent(it.uppercase(), k) }
                    "NSE_INDEX" -> { symbol?.let { out.putIfAbsent(it.uppercase(), k) }; name?.let { out.putIfAbsent(it.uppercase(), k) } }
                }
            }
            reader.endArray()
        }
        return out
    }

    private fun JsonReader.nextStringOrNull(): String? =
        if (peek() == android.util.JsonToken.NULL) { nextNull(); null } else nextString()

    private fun download(): Map<String, String> {
        val connection = (URL(MASTER_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 30_000
        }
        try {
            if (connection.responseCode !in 200..299) throw java.io.IOException("Upstox instrument list HTTP ${connection.responseCode}")
            return GZIPInputStream(connection.inputStream.buffered()).use(::parse).also { DiagLog.i("MarksyUpstox", "instruments: ${it.size} symbols") }
        } finally {
            connection.disconnect()
        }
    }

    private fun readCache(file: File): Map<String, String>? = runCatching {
        file.readLines().mapNotNull { line -> line.split('\t').takeIf { it.size == 2 }?.let { it[0] to it[1] } }.toMap().takeIf { it.isNotEmpty() }
    }.getOrNull()

    private fun writeCache(file: File, map: Map<String, String>) {
        runCatching { file.writeText(map.entries.joinToString("\n") { "${it.key}\t${it.value}" }) }
    }
}
