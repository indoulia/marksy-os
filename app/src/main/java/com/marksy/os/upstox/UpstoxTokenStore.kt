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
 * The user's own Upstox Analytics Token (read-only market data). Keystore-encrypted with its own
 * alias, same pattern as `AuthSessionStore`; it is only ever sent to api.upstox.com.
 */
class UpstoxTokenStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun save(token: String) {
        val value = token.trim()
        if (value.isBlank()) { clear(); return }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        preferences.edit()
            .putString(KEY_CIPHERTEXT, encode(cipher.doFinal(value.toByteArray(StandardCharsets.UTF_8))))
            .putString(KEY_IV, encode(cipher.iv))
            .apply()
        Cached.token = value
    }

    fun getToken(): String? {
        Cached.token?.let { return it }
        val ciphertext = preferences.getString(KEY_CIPHERTEXT, null) ?: return null
        val iv = preferences.getString(KEY_IV, null) ?: return null
        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_BITS, decode(iv)))
            String(cipher.doFinal(decode(ciphertext)), StandardCharsets.UTF_8).takeIf { it.isNotBlank() }
        }.getOrNull()?.also { Cached.token = it }
    }

    fun hasToken(): Boolean = getToken() != null

    fun clear() {
        Cached.token = null
        preferences.edit().remove(KEY_CIPHERTEXT).remove(KEY_IV).apply()
    }

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

    // Home polls every few seconds; skip the Keystore decrypt on every read.
    private object Cached { @Volatile var token: String? = null }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "marksy_os_upstox_token"
        const val PREFERENCES = "upstox_token"
        const val KEY_CIPHERTEXT = "analytics_token"
        const val KEY_IV = "analytics_token_iv"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_BITS = 128
    }
}
