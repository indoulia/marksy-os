package com.marksy.os.upstox

import android.content.Context
import org.json.JSONObject
import java.time.LocalDate

/** NSE trading holidays from Upstox, cached for the day. */
object UpstoxHolidays {
    @Volatile private var cached: Pair<LocalDate, Map<LocalDate, String>>? = null

    fun parse(body: String): Map<LocalDate, String> = runCatching {
        val data = JSONObject(body).optJSONArray("data") ?: return@runCatching emptyMap()
        (0 until data.length()).mapNotNull { data.optJSONObject(it) }.filter { o ->
            val closed = o.optJSONArray("closed_exchanges")
            o.optString("holiday_type") == "TRADING_HOLIDAY" && closed != null && (0 until closed.length()).any { closed.optString(it) == "NSE" }
        }.mapNotNull { o -> runCatching { LocalDate.parse(o.getString("date")) }.getOrNull()?.let { it to o.optString("description") } }.toMap()
    }.getOrDefault(emptyMap())

    suspend fun load(context: Context, today: LocalDate): Map<LocalDate, String> {
        cached?.takeIf { it.first == today }?.let { return it.second }
        val store = UpstoxTokenStore(context.applicationContext)
        return runCatching { parse(UpstoxApiClient { store.getToken() }.holidays()) }.getOrDefault(emptyMap())
            .also { if (it.isNotEmpty()) cached = today to it }
    }
}
