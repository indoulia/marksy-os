package com.marksy.os.gateway

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
 * Session-token storage for the real Marksy login. "Remember me" persists the
 * token Keystore-encrypted (mirrors `SecureCredentialStore`'s exact pattern),
 * surviving app restarts. Without it, the token lives only in this object's
 * in-memory holder -- gone on process death, which on Android is a real,
 * stated tradeoff (a background delivery job can lose access mid-flight),
 * not a bug to route around.
 */
class AuthSessionStore(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun saveSession(token: String, userId: String, expiresAtEpochMs: Long, remember: Boolean) {
        InMemorySession.token = token
        InMemorySession.userId = userId
        InMemorySession.expiresAtEpochMs = expiresAtEpochMs
        InMemorySession.remembered = remember

        if (!remember) {
            clearPersisted()
            return
        }

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        preferences.edit()
            .putString(KEY_TOKEN_CIPHERTEXT, encode(cipher.doFinal(token.toByteArray(StandardCharsets.UTF_8))))
            .putString(KEY_TOKEN_IV, encode(cipher.iv))
            .putString(KEY_USER_ID, userId)
            .putLong(KEY_EXPIRES_AT, expiresAtEpochMs)
            .putBoolean(KEY_REMEMBERED, true)
            .apply()
    }

    fun getToken(): String? {
        InMemorySession.token?.let { return it }
        val ciphertext = preferences.getString(KEY_TOKEN_CIPHERTEXT, null) ?: return null
        val iv = preferences.getString(KEY_TOKEN_IV, null) ?: return null
        val token = try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_BITS, decode(iv)))
            String(cipher.doFinal(decode(ciphertext)), StandardCharsets.UTF_8).trim().takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
        // Warm the in-memory holder from persisted storage so subsequent reads
        // in this process skip the decrypt.
        if (token != null) InMemorySession.token = token
        return token
    }

    fun getUserId(): String? = InMemorySession.userId ?: preferences.getString(KEY_USER_ID, null)

    fun getExpiresAtEpochMs(): Long? {
        InMemorySession.expiresAtEpochMs?.let { return it }
        val value = preferences.getLong(KEY_EXPIRES_AT, -1L)
        return if (value == -1L) null else value
    }

    fun isRemembered(): Boolean = InMemorySession.remembered ?: preferences.getBoolean(KEY_REMEMBERED, false)

    fun clearSession() {
        InMemorySession.token = null
        InMemorySession.userId = null
        InMemorySession.expiresAtEpochMs = null
        InMemorySession.remembered = null
        clearPersisted()
    }

    private fun clearPersisted() {
        preferences.edit()
            .remove(KEY_TOKEN_CIPHERTEXT).remove(KEY_TOKEN_IV)
            .remove(KEY_USER_ID).remove(KEY_EXPIRES_AT).remove(KEY_REMEMBERED)
            .apply()
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

    /** Process-lifetime holder. A fresh process (e.g. a cold-started WorkManager
     * job after the app was killed) starts with everything null here and falls
     * back to persisted storage -- which is itself empty when "remember me" was
     * off, correctly reproducing "signed out" in that case. */
    private object InMemorySession {
        var token: String? = null
        var userId: String? = null
        var expiresAtEpochMs: Long? = null
        var remembered: Boolean? = null
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "marksy_os_auth_session"
        const val PREFERENCES = "auth_session"
        const val KEY_TOKEN_CIPHERTEXT = "session_token"
        const val KEY_TOKEN_IV = "session_token_iv"
        const val KEY_USER_ID = "session_user_id"
        const val KEY_EXPIRES_AT = "session_expires_at"
        const val KEY_REMEMBERED = "session_remembered"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_BITS = 128
    }
}
