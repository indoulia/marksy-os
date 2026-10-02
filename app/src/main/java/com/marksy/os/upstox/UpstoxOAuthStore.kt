package com.marksy.os.upstox

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The user's Upstox app keys, today's OAuth token and the last holdings, each slot Keystore-encrypted; all slots
 * share this store's one alias (same pattern as [UpstoxTokenStore]). Keys and token are only ever sent to api.upstox.com.
 */
class UpstoxOAuthStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun credentials(): UpstoxOAuth.Credentials? {
        val key = read(API_KEY) ?: return null
        val secret = read(API_SECRET) ?: return null
        return UpstoxOAuth.Credentials(key, secret, read(REDIRECT) ?: UpstoxOAuth.DEFAULT_REDIRECT)
    }

    // Decrypts rather than checks presence: keys the Keystore can no longer open count as missing.
    fun hasCredentials(): Boolean = credentials() != null

    /** Only a different app (key or redirect) invalidates today's token. */
    fun saveCredentials(apiKey: String, apiSecret: String, redirectUri: String) {
        val redirect = redirectUri.trim().ifBlank { UpstoxOAuth.DEFAULT_REDIRECT }
        val before = credentials()
        write(API_KEY, apiKey.trim())
        write(API_SECRET, apiSecret.trim())
        write(REDIRECT, redirect)
        if (before?.apiKey != apiKey.trim() || before.redirectUri != redirect) clearToken()
    }

    fun saveToken(token: String, issuedAt: Long) {
        write(ACCESS_TOKEN, token.trim())
        preferences.edit().putLong(ISSUED_AT, issuedAt).apply()
    }

    /** Today's token; null once Upstox's 03:30 IST cut-off has passed. */
    fun accessToken(now: Long = System.currentTimeMillis()): String? {
        val issued = signedInAt() ?: return null
        if (now >= UpstoxOAuth.expiresAt(issued)) return null
        return read(ACCESS_TOKEN)
    }

    fun signedInAt(): Long? = preferences.getLong(ISSUED_AT, 0L).takeIf { it > 0 && preferences.contains(ACCESS_TOKEN) }

    fun clearToken() {
        preferences.edit().remove(ACCESS_TOKEN).remove(ivName(ACCESS_TOKEN)).remove(ISSUED_AT).apply()
    }

    fun saveHoldings(json: String) = write(HOLDINGS, json)
    fun holdings(): String? = read(HOLDINGS)
    fun saveHidden(json: String) = write(HIDDEN, json)
    fun hidden(): String? = read(HIDDEN)

    fun disconnect() {
        preferences.edit().clear().apply()
    }

    private fun write(name: String, value: String) {
        if (value.isBlank()) {
            preferences.edit().remove(name).remove(ivName(name)).apply()
            return
        }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        preferences.edit()
            .putString(name, encode(cipher.doFinal(value.toByteArray(StandardCharsets.UTF_8))))
            .putString(ivName(name), encode(cipher.iv))
            .apply()
    }

    private fun read(name: String): String? {
        val ciphertext = preferences.getString(name, null) ?: return null
        val iv = preferences.getString(ivName(name), null) ?: return null
        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_BITS, decode(iv)))
            String(cipher.doFinal(decode(ciphertext)), StandardCharsets.UTF_8).takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    private fun ivName(name: String) = "${name}_iv"

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setUserAuthenticationRequired(false)
                .build()
        )
        return generator.generateKey()
    }

    private fun encode(value: ByteArray): String = android.util.Base64.encodeToString(value, android.util.Base64.NO_WRAP)
    private fun decode(value: String): ByteArray = android.util.Base64.decode(value, android.util.Base64.NO_WRAP)

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "marksy_os_upstox_oauth"
        const val PREFERENCES = "upstox_oauth"
        const val API_KEY = "api_key"
        const val API_SECRET = "api_secret"
        const val REDIRECT = "redirect_uri"
        const val ACCESS_TOKEN = "access_token"
        const val ISSUED_AT = "issued_at"
        const val HOLDINGS = "holdings"
        const val HIDDEN = "hidden"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_BITS = 128
    }
}
