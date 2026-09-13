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
 * Small Android Keystore-backed store for the Marksy integration credential.
 * The secret is never persisted in SharedPreferences/DataStore or BuildConfig.
 */
class SecureCredentialStore(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun getIntegrationKey(): String? {
        val ciphertext = preferences.getString(KEY_CIPHERTEXT, null) ?: return null
        val iv = preferences.getString(KEY_IV, null) ?: return null
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_BITS, decode(iv)))
            String(cipher.doFinal(decode(ciphertext)), StandardCharsets.UTF_8)
                .trim()
                .takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            // A missing/invalid keystore entry should degrade to an unconfigured gateway,
            // never crash the notification listener or delivery worker.
            null
        }
    }

    fun setIntegrationKey(value: String) {
        val key = value.trim()
        require(key.isNotBlank()) { "Integration key must not be blank" }

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        preferences.edit()
            .putString(KEY_CIPHERTEXT, encode(cipher.doFinal(key.toByteArray(StandardCharsets.UTF_8))))
            .putString(KEY_IV, encode(cipher.iv))
            .apply()
    }

    fun clearIntegrationKey() {
        preferences.edit().remove(KEY_CIPHERTEXT).remove(KEY_IV).apply()
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
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
        const val KEY_ALIAS = "marksy_os_integration_key"
        const val PREFERENCES = "secure_credentials"
        const val KEY_CIPHERTEXT = "marksy_integration_key"
        const val KEY_IV = "marksy_integration_key_iv"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_BITS = 128
    }
}
