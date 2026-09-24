package com.marksy.os.gateway

import android.content.Context

/**
 * Endpoint configuration for the Marksy API. Credentials themselves are no
 * longer stored here -- see `AuthSessionStore` for the real session login.
 */
class SecureCredentialStore(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    // The base URL is endpoint configuration, not a secret, so it is stored as
    // plaintext. A blank value clears it, falling delivery back to the build default.
    fun getBaseUrl(): String? =
        preferences.getString(KEY_BASE_URL, null)?.trim()?.takeIf { it.isNotBlank() }

    fun setBaseUrl(value: String) {
        val url = value.trim()
        if (url.isBlank()) clearBaseUrl()
        else preferences.edit().putString(KEY_BASE_URL, url).apply()
    }

    fun clearBaseUrl() {
        preferences.edit().remove(KEY_BASE_URL).apply()
    }

    private companion object {
        const val PREFERENCES = "secure_credentials"
        const val KEY_BASE_URL = "marksy_base_url"
    }
}
