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

    fun displayName(context: Context, packageName: String): String {
        names[packageName]?.let { return it }
        return runCatching {
            val applicationInfo = context.packageManager.getApplicationInfo(packageName, 0)
            context.packageManager.getApplicationLabel(applicationInfo).toString().trim()
        }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: packageName.substringAfterLast('.').ifBlank { packageName }
    }

    fun displayName(packageName: String): String =
        names[packageName] ?: packageName.substringAfterLast('.').ifBlank { packageName }
}
