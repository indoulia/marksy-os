package com.marksy.os.weather

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.math.roundToInt

data class Weather(val temperatureC: Int, val weatherCode: Int, val isDay: Boolean) {
    val condition: Condition
        get() = when (weatherCode) {
            0 -> if (isDay) Condition.CLEAR else Condition.CLEAR_NIGHT
            1, 2 -> Condition.PARTLY_CLOUDY
            3, 45, 48 -> Condition.CLOUDY
            in 51..67, in 80..82 -> Condition.RAIN
            in 71..77, 85, 86 -> Condition.SNOW
            in 95..99 -> Condition.THUNDER
            else -> Condition.CLOUDY
        }

    enum class Condition { CLEAR, CLEAR_NIGHT, PARTLY_CLOUDY, CLOUDY, RAIN, SNOW, THUNDER }
}

/** Current weather from Open-Meteo (no API key) using coarse device location. */
object WeatherRepository {
    private const val CACHE_MS = 30 * 60 * 1000L
    private var cached: Pair<Long, Weather>? = null

    suspend fun current(context: Context): Weather? {
        cached?.let { (at, weather) -> if (System.currentTimeMillis() - at < CACHE_MS) return weather }
        val location = withTimeoutOrNull(10_000) { location(context) } ?: return cached?.second
        return withContext(Dispatchers.IO) { runCatching { fetch(location.latitude, location.longitude) }.getOrNull() }
            ?.also { cached = System.currentTimeMillis() to it }
            ?: cached?.second
    }

    @SuppressLint("MissingPermission") // Callers only invoke this after ACCESS_COARSE_LOCATION is granted.
    private suspend fun location(context: Context): Location? {
        val manager = context.getSystemService(LocationManager::class.java) ?: return null
        val lastKnown = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
            .mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }
        if (lastKnown != null && System.currentTimeMillis() - lastKnown.time < CACHE_MS * 2) return lastKnown
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || !manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) return lastKnown
        return suspendCancellableCoroutine<Location?> { cont ->
            val signal = CancellationSignal()
            cont.invokeOnCancellation { signal.cancel() }
            manager.getCurrentLocation(LocationManager.NETWORK_PROVIDER, signal, context.mainExecutor) { cont.resume(it) }
        } ?: lastKnown
    }

    private fun fetch(latitude: Double, longitude: Double): Weather {
        // Two decimals (~1 km) is enough for weather and avoids sending a precise location.
        val url = String.format(
            Locale.US,
            "https://api.open-meteo.com/v1/forecast?latitude=%.2f&longitude=%.2f&current=temperature_2m,weather_code,is_day",
            latitude, longitude
        )
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8_000
            readTimeout = 8_000
        }
        try {
            val current = JSONObject(connection.inputStream.bufferedReader().use { it.readText() }).getJSONObject("current")
            return Weather(
                temperatureC = current.getDouble("temperature_2m").roundToInt(),
                weatherCode = current.getInt("weather_code"),
                isDay = current.optInt("is_day", 1) == 1
            )
        } finally {
            connection.disconnect()
        }
    }
}
