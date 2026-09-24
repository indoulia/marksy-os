package com.marksy.os.gateway

import android.content.Context
import java.security.KeyStore

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

    companion object {
        private const val PREFERENCES = "secure_credentials"
        private const val KEY_BASE_URL = "marksy_base_url"
        private const val LEGACY_KEY_CIPHERTEXT = "marksy_integration_key"
        private const val LEGACY_KEY_IV = "marksy_integration_key_iv"
        private const val LEGACY_MARKET_KEY_CIPHERTEXT = "marksy_market_api_key"
        private const val LEGACY_MARKET_KEY_IV = "marksy_market_api_key_iv"
        private const val LEGACY_KEYSTORE_ALIAS = "marksy_os_integration_key"

        fun purgeLegacyCredentials(context: Context) {
            context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit()
                .remove(LEGACY_KEY_CIPHERTEXT).remove(LEGACY_KEY_IV)
                .remove(LEGACY_MARKET_KEY_CIPHERTEXT).remove(LEGACY_MARKET_KEY_IV)
                .apply()
            runCatching {
                val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
                if (keyStore.containsAlias(LEGACY_KEYSTORE_ALIAS)) keyStore.deleteEntry(LEGACY_KEYSTORE_ALIAS)
            }
        }
    }
}
