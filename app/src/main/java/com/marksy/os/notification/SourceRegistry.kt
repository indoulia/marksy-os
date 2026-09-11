package com.marksy.os.notification

/**
 * Central source metadata used by the generic notification collector.
 * Unknown packages intentionally remain supported and fall back to a readable
 * package-derived name.
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

    fun displayName(packageName: String): String =
        names[packageName] ?: packageName.substringAfterLast('.').ifBlank { packageName }
}
