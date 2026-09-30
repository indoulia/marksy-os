package com.marksy.os.notification

import android.content.Context

/** Source names for captured rows: the installed app's own label. Channel names are the server's (spec §4). */
object SourceRegistry {
    /** True for WhatsApp variants supported explicitly by V1. */
    fun isWhatsApp(packageName: String): Boolean =
        packageName.trim().lowercase() in setOf("com.whatsapp", "com.whatsapp.w4b")

    fun displayName(context: Context, packageName: String): String {
        val normalized = packageName.trim().lowercase()
        return runCatching {
            val applicationInfo = context.packageManager.getApplicationInfo(normalized, 0)
            context.packageManager.getApplicationLabel(applicationInfo).toString().trim()
        }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: normalized.substringAfterLast('.').ifBlank { normalized }
    }
}
