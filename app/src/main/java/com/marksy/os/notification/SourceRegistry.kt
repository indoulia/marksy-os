package com.marksy.os.notification

import android.content.Context

/**
 * Central source metadata used by the generic notification collector.
 * Known trading/messaging packages get stable product names; other installed
 * apps use their Android application label when it can be resolved.
 */
object SourceRegistry {
    private val names = mapOf(
        "com.whatsapp" to "WhatsApp",
        "com.whatsapp.w4b" to "WhatsApp Business",
        "com.upstox.pro" to "Upstox",
        "com.icicidirect" to "ICICI Direct",
        "com.etmoney" to "ET Money",
        "com.zerodha.kite3" to "Zerodha",
        "com.zerodha.kite" to "Zerodha",
        "com.nextbillion.groww" to "Groww",
        "com.angelbroking.smartmoney" to "Angel One",
        "com.angelbroking.lite" to "Angel One",
        "com.fivepaisa.trade" to "5paisa"
    )

    /** Stable list for diagnostics/settings UI. */
    fun knownSources(): List<String> = names.values.distinct().sorted()

    /** True when the package has explicit Marksy source metadata. */
    fun isKnownSource(packageName: String): Boolean =
        names.containsKey(packageName.trim().lowercase())

    /** True for WhatsApp variants supported explicitly by V1. */
    fun isWhatsApp(packageName: String): Boolean =
        packageName.trim().lowercase() in setOf("com.whatsapp", "com.whatsapp.w4b")

    /** True for broker/trading apps that have explicit V1 source metadata. */
    fun isTradingSource(packageName: String): Boolean =
        packageName.trim().lowercase() in TRADING_PACKAGES

    fun displayName(context: Context, packageName: String): String {
        val normalized = packageName.trim().lowercase()
        names[normalized]?.let { return it }
        return runCatching {
            val applicationInfo = context.packageManager.getApplicationInfo(normalized, 0)
            context.packageManager.getApplicationLabel(applicationInfo).toString().trim()
        }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: normalized.substringAfterLast('.').ifBlank { normalized }
    }

    fun displayName(packageName: String): String {
        val normalized = packageName.trim().lowercase()
        return names[normalized] ?: normalized.substringAfterLast('.').ifBlank { normalized }
    }

    private val TRADING_PACKAGES = setOf(
        "com.upstox.pro",
        "com.icicidirect",
        "com.etmoney",
        "com.zerodha.kite3",
        "com.zerodha.kite",
        "com.nextbillion.groww",
        "com.angelbroking.smartmoney",
        "com.angelbroking.lite",
        "com.fivepaisa.trade"
    )
}
