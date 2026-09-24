# MarksyOS Real Login (Session-Based Auth) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the Integration Key and Market API Key credential mechanisms (the first never actually authenticated against the real server; the second is now redundant) with a real Marksy account login, session-token-based, matching how `marksy-api`'s own Flutter/admin clients already authenticate.

**Architecture:** A new `AuthSessionStore` (Keystore-encrypted when "remember me" is on, in-memory-only otherwise) holds the session token, userId, and expiry. A new `AuthApiClient` calls `POST /auth/login`/`/auth/refresh`/`/auth/logout`. A new `AuthRepository` is the single place that decides "is there a usable token right now" — checking expiry and refreshing proactively — that every other client reads through. `MarksyTipsApiClient` and `RealMarketApiClient` stop taking a raw credential string at construction and instead pull the current token from `AuthRepository` inside each call, sending `Authorization: Bearer <token>`. `MarksyGatewayProvider`'s construction functions stay synchronous (matching existing Compose `remember {}` call sites) — the async refresh-check happens inside each client's already-suspend `execute()`, not at construction.

**Tech Stack:** Kotlin, Jetpack Compose, `org.json`, `HttpURLConnection`, JUnit4 + Robolectric, Android Keystore.

**Spec:** `docs/superpowers/specs/2026-09-24-marksyos-login-auth-design.md`

## Global Constraints

- `marksy-api` requires **no changes** — `/auth/login`, `/auth/refresh`, `/auth/logout` already exist and already work for the Flutter client; this plan only changes what MarksyOS sends.
- `POST /auth/refresh` only succeeds on a **still-valid** token — an already-expired token gets a hard `MRA_SESSION_EXPIRED` requiring full re-login. `AuthRepository` must refresh proactively (before expiry), never rely on reactive recovery after a 401.
- "Remember me" **checked**: session token is Keystore-encrypted and persisted (survives app restart), exactly mirroring `SecureCredentialStore`'s existing AES/GCM pattern. **Unchecked**: token lives only in an in-process in-memory holder — lost on process death. This is a real, stated tradeoff (background tip delivery can lapse if the process is killed before a queued notification is forwarded) — not a bug to silently work around.
- The old `getIntegrationKey`/`setIntegrationKey`/`clearIntegrationKey` and `getMarketApiKey`/`setMarketApiKey`/`clearMarketApiKey` methods and their UI are **removed**, not deprecated-alongside. `getBaseUrl`/`setBaseUrl`/`clearBaseUrl` on `SecureCredentialStore` are **kept** — base URL is endpoint configuration, not a credential, and the login screen still needs it.
- A lapsed/never-established session degrades every existing caller through the exact same "unconfigured" paths that already exist (`UnconfiguredMarksyGatewayClient`, `MarksyGatewayProvider.marketIntelligenceClient(): MarketApiClient?` returning null) — never a crash, never a silently-fake response.
- Follow the existing raw-HTTP/`org.json` convention exactly; no Retrofit/OkHttp/kotlinx.serialization/DI framework.
- This plan does not touch the Upstox integration (separate credential, separate package, unaffected).
- **Cross-plan ordering:** `docs/superpowers/plans/2026-09-24-upstox-direct-market-data.md` was written before this plan but had not been executed as of this writing. Its Task 5 adds an "Upstox Analytics Token" UI block *inside* `GatewaySettingsHost`. This plan's Task 8 deletes `GatewaySettingsHost` entirely. **Execute this plan first, before the Upstox plan**, so the Upstox plan's Task 5 lands its block into the already-restructured (login-based) settings surface instead. If the Upstox plan is executed first regardless, Task 8's implementer must additionally re-add that Upstox token field/Save/Remove block into the new post-login settings surface — check for its presence in `GatewaySettingsHost` before deleting the function, and if found, carry it forward rather than silently dropping it.

## Review Focus

- Never-logged-in state → every screen that reads through `MarksyGatewayProvider` shows its existing "not configured"/unavailable UI, never a crash (Task 6, Task 7).
- Session expires while the app is mid-use → the next call proactively refreshes rather than failing; if refresh itself fails (truly expired), the caller degrades to the unconfigured path, not a raw exception surfacing in UI (Task 4).
- "Remember me" unchecked, then the app process restarts → the user is signed out (in-memory token is gone) and sees the login screen again, not a crash or a stale-looking signed-in state (Task 2, Task 7).
- The background `TradingDeliveryWorker` must keep working unattended for a "remember me"-checked session, and must cleanly skip (not crash or spin-retry forever) when there's no usable session (Task 6).
- Login failure (wrong password → 401; server auth not configured → 503; blank userId → 422) each show a distinct, honest message — never a generic swallow into "something went wrong" (Task 8).

---

## File Structure

**New, `com.marksy.os.gateway`:**
- `AuthModels.kt` — `SessionResponseDto` + parser
- `AuthApiClient.kt` — `AuthApiClient` interface + `RealAuthApiClient`
- `AuthSessionStore.kt` — session token/userId/expiry storage (remember vs. in-memory)
- `AuthRepository.kt` — `currentToken()`, `login()`, `logout()`

**Modified:**
- `app/src/main/java/com/marksy/os/gateway/SecureCredentialStore.kt` — remove integration-key and market-key methods, keep base URL
- `app/src/main/java/com/marksy/os/gateway/MarksyGatewayClient.kt` — `MarksyTipsApiClient` constructor change
- `app/src/main/java/com/marksy/os/gateway/MarksyTipsApiClient.kt` — auth header change
- `app/src/main/java/com/marksy/os/market/MarketApiClient.kt` — `RealMarketApiClient` constructor change
- `app/src/main/java/com/marksy/os/gateway/MarksyGatewayProvider.kt` — construction rewired through `AuthRepository`
- `app/src/main/java/com/marksy/os/data/MarksyContainer.kt` — `authRepository(context)` accessor
- `app/src/main/java/com/marksy/os/MainActivity.kt` — `LoginScreen` replaces `GatewaySettingsHost`; More-screen card updated

**New UI, `com.marksy.os.ui`:**
- `LoginScreen.kt`

**Tests, new/modified:**
- `app/src/test/java/com/marksy/os/gateway/AuthModelsTest.kt` — new
- `app/src/test/java/com/marksy/os/gateway/RealAuthApiClientTest.kt` — new
- `app/src/test/java/com/marksy/os/gateway/AuthSessionStoreTest.kt` — new
- `app/src/test/java/com/marksy/os/gateway/AuthRepositoryTest.kt` — new
- `app/src/test/java/com/marksy/os/gateway/SecureCredentialStoreTest.kt` — modified (old-credential tests removed)
- `app/src/test/java/com/marksy/os/gateway/MarksyGatewayClientTest.kt` — modified
- `app/src/test/java/com/marksy/os/market/RealMarketApiClientTest.kt` — modified
- `app/src/test/java/com/marksy/os/ui/LoginScreenTest.kt` — new

---

### Task 1: `SessionResponseDto` and parser

**Files:**
- Create: `app/src/main/java/com/marksy/os/gateway/AuthModels.kt`
- Test: `app/src/test/java/com/marksy/os/gateway/AuthModelsTest.kt`

**Interfaces:**
- Produces: `data class SessionResponseDto(val sessionToken: String, val userId: String, val issuedAt: String, val expiresAt: String, val readOnly: Boolean)` with `companion object { fun parse(envelope: JSONObject): SessionResponseDto }`. Consumed by `RealAuthApiClient` (Task 2).

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.marksy.os.gateway

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthModelsTest {
    @Test
    fun parsesSessionResponse() {
        val envelope = JSONObject(
            """
            {
              "data": {
                "sessionToken": "sess_abc123",
                "userId": "prsingh",
                "issuedAt": "2026-09-25T09:00:00Z",
                "expiresAt": "2026-09-25T17:00:00Z",
                "readOnly": false
              },
              "meta": {}
            }
            """
        )

        val session = SessionResponseDto.parse(envelope)

        assertEquals("sess_abc123", session.sessionToken)
        assertEquals("prsingh", session.userId)
        assertEquals("2026-09-25T17:00:00Z", session.expiresAt)
        assertFalse(session.readOnly)
    }

    @Test
    fun readOnlyDefaultsToFalseWhenAbsent() {
        val envelope = JSONObject(
            """{"data": {"sessionToken": "sess_abc123", "userId": "prsingh", "issuedAt": "2026-09-25T09:00:00Z", "expiresAt": "2026-09-25T17:00:00Z"}, "meta": {}}"""
        )

        val session = SessionResponseDto.parse(envelope)

        assertFalse(session.readOnly)
    }

    @Test
    fun readOnlyTrueWhenPresent() {
        val envelope = JSONObject(
            """{"data": {"sessionToken": "s", "userId": "u", "issuedAt": "2026-09-25T09:00:00Z", "expiresAt": "2026-09-25T17:00:00Z", "readOnly": true}, "meta": {}}"""
        )

        assertTrue(SessionResponseDto.parse(envelope).readOnly)
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "com.marksy.os.gateway.AuthModelsTest"`
Expected: FAIL — `AuthModels.kt` does not exist.

- [ ] **Step 3: Implement**

```kotlin
package com.marksy.os.gateway

import org.json.JSONObject

data class SessionResponseDto(
    val sessionToken: String,
    val userId: String,
    val issuedAt: String,
    val expiresAt: String,
    val readOnly: Boolean
) {
    companion object {
        fun parse(envelope: JSONObject): SessionResponseDto {
            val data = envelope.getJSONObject("data")
            return SessionResponseDto(
                sessionToken = data.optString("sessionToken", ""),
                userId = data.optString("userId", ""),
                issuedAt = data.optString("issuedAt", ""),
                expiresAt = data.optString("expiresAt", ""),
                readOnly = data.optBoolean("readOnly", false)
            )
        }
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "com.marksy.os.gateway.AuthModelsTest"`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/marksy/os/gateway/AuthModels.kt app/src/test/java/com/marksy/os/gateway/AuthModelsTest.kt
git commit -m "feat(auth): add SessionResponseDto and parser"
```

---

### Task 2: `AuthSessionStore`

**Files:**
- Create: `app/src/main/java/com/marksy/os/gateway/AuthSessionStore.kt`
- Test: `app/src/test/java/com/marksy/os/gateway/AuthSessionStoreTest.kt`

**Interfaces:**
- Produces: `class AuthSessionStore(context: Context)` with `fun saveSession(token: String, userId: String, expiresAtEpochMs: Long, remember: Boolean)`, `fun getToken(): String?`, `fun getUserId(): String?`, `fun getExpiresAtEpochMs(): Long?`, `fun isRemembered(): Boolean`, `fun clearSession()`. Consumed by `AuthRepository` (Task 4).

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.marksy.os.gateway

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.security.Security

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AuthSessionStoreTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    // AuthSessionStore's in-memory holder is a process-wide singleton (by design --
    // production code needs every AuthSessionStore(context) construction to share
    // one live session), so without this it could leak between @Test methods in
    // this class. clearSession() is the store's own public API, not a test-only hook.
    @Before
    fun resetSession() {
        AuthSessionStore(context).clearSession()
    }

    companion object {
        @BeforeClass
        @JvmStatic
        fun setupAndroidKeyStore() {
            if (Security.getProvider("AndroidKeyStore") == null) {
                Security.addProvider(TestAndroidKeyStoreProvider())
            }
        }
    }

    @Test
    fun rememberedSessionRoundTripsThroughEncryptedStorage() {
        val store = AuthSessionStore(context)
        assertNull(store.getToken())

        store.saveSession("sess_abc123", "prsingh", 1_800_000_000_000L, remember = true)

        assertEquals("sess_abc123", store.getToken())
        assertEquals("prsingh", store.getUserId())
        assertEquals(1_800_000_000_000L, store.getExpiresAtEpochMs())
        assertTrue(store.isRemembered())
    }

    // These two tests check the raw SharedPreferences file directly rather than
    // constructing "a new instance" and reading through it: the in-memory holder
    // is a process-wide singleton (a Kotlin `object`), so within one test method
    // a second `AuthSessionStore(context)` instance shares that same singleton
    // state and would see the token either way -- it does not simulate a new
    // process the way a real app restart would. Reading the preference file
    // directly is what actually proves "written to persistent storage" vs. not,
    // which is the real thing "remember me" needs to guarantee.
    @Test
    fun rememberedSessionIsWrittenToSharedPreferences() {
        AuthSessionStore(context).saveSession("sess_abc123", "prsingh", 1_800_000_000_000L, remember = true)

        val prefs = context.getSharedPreferences("auth_session", Context.MODE_PRIVATE)

        assertTrue(prefs.contains("session_token"))
    }

    @Test
    fun unrememberedSessionIsNeverWrittenToSharedPreferences() {
        AuthSessionStore(context).saveSession("sess_abc123", "prsingh", 1_800_000_000_000L, remember = false)

        val prefs = context.getSharedPreferences("auth_session", Context.MODE_PRIVATE)

        assertFalse(prefs.contains("session_token"))
    }

    @Test
    fun unrememberedSessionIsReadableFromTheSameInstance() {
        val store = AuthSessionStore(context)
        store.saveSession("sess_abc123", "prsingh", 1_800_000_000_000L, remember = false)

        assertEquals("sess_abc123", store.getToken())
        assertFalse(store.isRemembered())
    }

    @Test
    fun clearingSessionRemovesItFromBothStorageForms() {
        val store = AuthSessionStore(context)
        store.saveSession("sess_abc123", "prsingh", 1_800_000_000_000L, remember = true)

        store.clearSession()

        assertNull(store.getToken())
        assertNull(store.getUserId())
        assertNull(store.getExpiresAtEpochMs())
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "com.marksy.os.gateway.AuthSessionStoreTest"`
Expected: FAIL — `AuthSessionStore.kt` does not exist.

- [ ] **Step 3: Implement**

```kotlin
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
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "com.marksy.os.gateway.AuthSessionStoreTest"`
Expected: PASS (5 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/marksy/os/gateway/AuthSessionStore.kt app/src/test/java/com/marksy/os/gateway/AuthSessionStoreTest.kt
git commit -m "feat(auth): add AuthSessionStore (remember-me-aware session storage)"
```

---

### Task 3: `AuthApiClient`

**Files:**
- Create: `app/src/main/java/com/marksy/os/gateway/AuthApiClient.kt`
- Test: `app/src/test/java/com/marksy/os/gateway/RealAuthApiClientTest.kt`

**Interfaces:**
- Consumes: `SessionResponseDto` (Task 1).
- Produces: `interface AuthApiClient { suspend fun login(userId: String, password: String): SessionResponseDto; suspend fun refresh(currentToken: String): SessionResponseDto; suspend fun logout(currentToken: String): Boolean }`; `class RealAuthApiClient(baseUrl: String) : AuthApiClient`. Consumed by `AuthRepository` (Task 4).

- [ ] **Step 1: Write the failing test**

```kotlin
package com.marksy.os.gateway

import org.junit.Assert.assertThrows
import org.junit.Test

class RealAuthApiClientTest {
    @Test
    fun rejectsNonHttpsBaseUrl() {
        assertThrows(IllegalArgumentException::class.java) {
            RealAuthApiClient(baseUrl = "http://insecure.example.com/api/v1")
        }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.marksy.os.gateway.RealAuthApiClientTest"`
Expected: FAIL — `AuthApiClient.kt` does not exist.

- [ ] **Step 3: Implement**

```kotlin
package com.marksy.os.gateway

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

interface AuthApiClient {
    suspend fun login(userId: String, password: String): SessionResponseDto
    suspend fun refresh(currentToken: String): SessionResponseDto
    suspend fun logout(currentToken: String): Boolean
}

class AuthApiException(message: String) : IOException(message)

/** `POST /auth/login`, `/auth/refresh`, `/auth/logout` against `marksy-api`'s
 * already-existing session auth (EPIC-M3.12) -- the same mechanism the
 * Flutter/admin client uses. Structurally mirrors every other client in this
 * codebase: raw `HttpURLConnection`, no third-party HTTP library. */
class RealAuthApiClient(baseUrl: String) : AuthApiClient {
    private val base: String = normalizeBaseUrl(baseUrl)

    override suspend fun login(userId: String, password: String): SessionResponseDto {
        val payload = JSONObject().put("userId", userId).put("password", password)
        return SessionResponseDto.parse(execute("POST", "$base/auth/login", payload))
    }

    override suspend fun refresh(currentToken: String): SessionResponseDto =
        SessionResponseDto.parse(execute("POST", "$base/auth/refresh", bearer = currentToken))

    override suspend fun logout(currentToken: String): Boolean {
        val envelope = execute("POST", "$base/auth/logout", bearer = currentToken)
        return envelope.optJSONObject("data")?.optBoolean("revoked", false) ?: false
    }

    private suspend fun execute(method: String, url: String, payload: JSONObject? = null, bearer: String? = null): JSONObject =
        withContext(Dispatchers.IO) {
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = false
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Content-Type", "application/json")
                bearer?.let { setRequestProperty("Authorization", "Bearer $it") }
                doInput = true
                if (payload != null) doOutput = true
            }
            try {
                if (payload != null) {
                    connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
                }
                val code = connection.responseCode
                if (code in REDIRECT_CODES) throw AuthApiException("Marksy auth redirect refused")
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                val response = stream?.bufferedReader()?.use { reader ->
                    val buffer = CharArray(4096)
                    val builder = StringBuilder()
                    while (true) {
                        val read = reader.read(buffer)
                        if (read < 0) break
                        builder.append(buffer, 0, read)
                        if (builder.length > MAX_RESPONSE_CHARS) throw IOException("Marksy auth response exceeded the safety limit")
                    }
                    builder.toString()
                }.orEmpty()
                if (code !in 200..299) {
                    val detail = errorDetail(response)
                    if (code in 400..499) throw AuthApiException("Marksy auth returned HTTP $code$detail")
                    throw IOException("Marksy auth returned HTTP $code$detail")
                }
                JSONObject(response)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } finally {
                connection.disconnect()
            }
        }

    private fun errorDetail(response: String): String = try {
        val envelope = JSONObject(response)
        val error = envelope.optJSONObject("error")
        val message = error?.optString("message")?.takeIf { it.isNotBlank() }
        message?.let { ": ${it.take(300)}" } ?: ""
    } catch (_: Exception) { "" }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 10_000
        const val READ_TIMEOUT_MS = 20_000
        const val MAX_RESPONSE_CHARS = 50_000
        val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)

        fun normalizeBaseUrl(value: String): String {
            val trimmed = value.trim().trimEnd('/')
            require(trimmed.isNotBlank()) { "Base URL must not be blank" }
            val uri = runCatching { URI(trimmed) }.getOrNull() ?: throw IllegalArgumentException("Base URL is not a valid URL")
            require(uri.scheme.equals("https", ignoreCase = true)) { "Base URL must use HTTPS" }
            require(uri.host?.isNotBlank() == true) { "Base URL must include a host" }
            return trimmed
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.marksy.os.gateway.RealAuthApiClientTest"`
Expected: PASS (1 test).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/marksy/os/gateway/AuthApiClient.kt app/src/test/java/com/marksy/os/gateway/RealAuthApiClientTest.kt
git commit -m "feat(auth): add AuthApiClient HTTP transport for login/refresh/logout"
```

---

### Task 4: `AuthRepository`

**Files:**
- Create: `app/src/main/java/com/marksy/os/gateway/AuthRepository.kt`
- Test: `app/src/test/java/com/marksy/os/gateway/AuthRepositoryTest.kt`

**Interfaces:**
- Consumes: `AuthApiClient` (Task 3, injected — a fake implements it in tests), `AuthSessionStore` (Task 2).
- Produces: `class AuthRepository(private val client: AuthApiClient, private val store: AuthSessionStore)` with `suspend fun currentToken(): String?` (refreshes proactively, returns null if never logged in or refresh fails), `suspend fun login(userId: String, password: String, remember: Boolean): Result<Unit>`, `suspend fun logout(): Unit`. Consumed by `MarksyTipsApiClient`/`RealMarketApiClient` (Task 5) and `LoginScreen` (Task 8).

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.marksy.os.gateway

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.security.Security

private class FakeAuthApiClient(
    private val loginResult: SessionResponseDto? = null,
    private val loginError: Throwable? = null,
    private val refreshResult: SessionResponseDto? = null,
    private val refreshError: Throwable? = null
) : AuthApiClient {
    var refreshCallCount = 0
    override suspend fun login(userId: String, password: String): SessionResponseDto = loginError?.let { throw it } ?: loginResult!!
    override suspend fun refresh(currentToken: String): SessionResponseDto {
        refreshCallCount++
        return refreshError?.let { throw it } ?: refreshResult!!
    }
    override suspend fun logout(currentToken: String): Boolean = true
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AuthRepositoryTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    // See AuthSessionStoreTest's identical @Before for why this is needed:
    // AuthSessionStore's in-memory holder is a process-wide singleton.
    @Before
    fun resetSession() {
        AuthSessionStore(context).clearSession()
    }

    companion object {
        @BeforeClass
        @JvmStatic
        fun setupAndroidKeyStore() {
            if (Security.getProvider("AndroidKeyStore") == null) {
                Security.addProvider(TestAndroidKeyStoreProvider())
            }
        }
    }

    private fun session(expiresInMs: Long) = SessionResponseDto(
        sessionToken = "sess_new", userId = "prsingh",
        issuedAt = "2026-09-25T09:00:00Z", expiresAt = "2026-09-25T17:00:00Z", readOnly = false
    ).let { it to (System.currentTimeMillis() + expiresInMs) }

    @Test
    fun neverLoggedInYieldsNullToken() = runBlocking {
        val store = AuthSessionStore(context)
        val repository = AuthRepository(FakeAuthApiClient(), store)

        assertNull(repository.currentToken())
    }

    @Test
    fun freshSessionReturnsStoredTokenWithoutRefreshing() = runBlocking {
        val store = AuthSessionStore(context)
        store.saveSession("sess_fresh", "prsingh", System.currentTimeMillis() + 6 * 60 * 60 * 1000L, remember = true)
        val fake = FakeAuthApiClient()
        val repository = AuthRepository(fake, store)

        val token = repository.currentToken()

        assertEquals("sess_fresh", token)
        assertEquals(0, fake.refreshCallCount)
    }

    @Test
    fun nearExpirySessionRefreshesAndReturnsNewToken() = runBlocking {
        val store = AuthSessionStore(context)
        store.saveSession("sess_old", "prsingh", System.currentTimeMillis() + 60_000L, remember = true)
        val (refreshed, _) = session(8 * 60 * 60 * 1000L)
        val fake = FakeAuthApiClient(refreshResult = refreshed)
        val repository = AuthRepository(fake, store)

        val token = repository.currentToken()

        assertEquals("sess_new", token)
        assertEquals(1, fake.refreshCallCount)
        assertEquals("sess_new", store.getToken())
    }

    @Test
    fun refreshFailureClearsSessionAndReturnsNull() = runBlocking {
        val store = AuthSessionStore(context)
        store.saveSession("sess_old", "prsingh", System.currentTimeMillis() + 60_000L, remember = true)
        val fake = FakeAuthApiClient(refreshError = AuthApiException("HTTP 401"))
        val repository = AuthRepository(fake, store)

        val token = repository.currentToken()

        assertNull(token)
        assertNull(store.getToken())
    }

    @Test
    fun loginSavesSessionWithRememberChoice() = runBlocking {
        val store = AuthSessionStore(context)
        val (loggedIn, _) = session(8 * 60 * 60 * 1000L)
        val repository = AuthRepository(FakeAuthApiClient(loginResult = loggedIn), store)

        val result = repository.login("prsingh", "correct-password", remember = false)

        assertTrue(result.isSuccess)
        assertEquals("sess_new", store.getToken())
        assertTrue(!store.isRemembered())
    }

    @Test
    fun loginFailurePropagatesAsResultFailure() = runBlocking {
        val store = AuthSessionStore(context)
        val repository = AuthRepository(FakeAuthApiClient(loginError = AuthApiException("HTTP 401: Invalid credentials.")), store)

        val result = repository.login("prsingh", "wrong-password", remember = true)

        assertTrue(result.isFailure)
        assertNull(store.getToken())
    }

    @Test
    fun logoutClearsSession() = runBlocking {
        val store = AuthSessionStore(context)
        store.saveSession("sess_abc", "prsingh", System.currentTimeMillis() + 60_000L, remember = true)
        val repository = AuthRepository(FakeAuthApiClient(), store)

        repository.logout()

        assertNull(store.getToken())
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "com.marksy.os.gateway.AuthRepositoryTest"`
Expected: FAIL — `AuthRepository.kt` does not exist.

- [ ] **Step 3: Implement**

```kotlin
package com.marksy.os.gateway

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.time.Instant
import java.time.format.DateTimeParseException

/** The single place that decides "is there a usable session right now" --
 * every API client reads through this, never `AuthSessionStore` directly.
 * Refreshes proactively (within [REFRESH_BUFFER_MS] of expiry) because
 * `POST /auth/refresh` only succeeds on a still-valid token; refreshing too
 * late means a hard sign-out instead of a silent renewal. */
class AuthRepository(private val client: AuthApiClient, private val store: AuthSessionStore) {
    suspend fun currentToken(): String? = withContext(Dispatchers.IO) {
        val token = store.getToken() ?: return@withContext null
        val expiresAt = store.getExpiresAtEpochMs() ?: return@withContext null
        val now = System.currentTimeMillis()
        if (expiresAt - now > REFRESH_BUFFER_MS) return@withContext token

        try {
            val refreshed = client.refresh(token)
            val expiresAtMs = parseEpochMs(refreshed.expiresAt) ?: (now + FALLBACK_TTL_MS)
            store.saveSession(refreshed.sessionToken, refreshed.userId, expiresAtMs, store.isRemembered())
            refreshed.sessionToken
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: IOException) {
            // Includes the deterministic MRA_SESSION_EXPIRED case: refreshing an
            // already-expired token can never succeed, so there is nothing to
            // retry here -- the caller must sign in again via `login`.
            store.clearSession()
            null
        }
    }

    suspend fun login(userId: String, password: String, remember: Boolean): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val session = client.login(userId, password)
            val expiresAtMs = parseEpochMs(session.expiresAt) ?: (System.currentTimeMillis() + FALLBACK_TTL_MS)
            store.saveSession(session.sessionToken, session.userId, expiresAtMs, remember)
            Result.success(Unit)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: IOException) {
            Result.failure(error)
        }
    }

    suspend fun logout() = withContext(Dispatchers.IO) {
        val token = store.getToken()
        if (token != null) {
            runCatching { client.logout(token) }
        }
        store.clearSession()
    }

    private fun parseEpochMs(iso: String): Long? =
        try {
            Instant.parse(iso).toEpochMilli()
        } catch (_: DateTimeParseException) {
            null
        }

    private companion object {
        // Generous enough that a backgrounded WorkManager run, which can be
        // delayed hours by the OS, still lands comfortably inside the window.
        const val REFRESH_BUFFER_MS = 60 * 60 * 1000L // 1 hour
        const val FALLBACK_TTL_MS = 8 * 60 * 60 * 1000L // matches marksy-api's DEFAULT_SESSION_TTL_SECONDS
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "com.marksy.os.gateway.AuthRepositoryTest"`
Expected: PASS (7 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/marksy/os/gateway/AuthRepository.kt app/src/test/java/com/marksy/os/gateway/AuthRepositoryTest.kt
git commit -m "feat(auth): add AuthRepository with proactive session refresh"
```

---

### Task 5: Rewire `MarksyTipsApiClient` and `RealMarketApiClient` to session auth

**Files:**
- Modify: `app/src/main/java/com/marksy/os/gateway/MarksyGatewayClient.kt`
- Modify: `app/src/main/java/com/marksy/os/gateway/MarksyTipsApiClient.kt`
- Modify: `app/src/main/java/com/marksy/os/market/MarketApiClient.kt`
- Test: `app/src/test/java/com/marksy/os/gateway/MarksyGatewayClientTest.kt` (modified)
- Test: `app/src/test/java/com/marksy/os/market/RealMarketApiClientTest.kt` (modified)

**Interfaces:**
- Consumes: `AuthRepository.currentToken()` (Task 4).
- Produces: `MarksyTipsApiClient(authRepository: AuthRepository, baseUrl: String = DEFAULT_MARKSY_API_BASE_URL) : MarksyGatewayClient` (constructor signature changed — no more raw `integrationKey: String` parameter); `RealMarketApiClient(authRepository: AuthRepository, baseUrl: String) : MarketApiClient` (same change). Both throw a clear exception from their `execute()` when `authRepository.currentToken()` returns null, which their existing callers already handle as a failure/unavailable path.

- [ ] **Step 1: Read both files' current full content**

Read `MarksyTipsApiClient.kt` and `MarketApiClient.kt` in full before editing — both already exist from prior work in this session and their exact current line numbers matter for a clean edit.

- [ ] **Step 2: Run the existing tests to confirm the starting point is green**

Run: `./gradlew :app:testDebugUnitTest --tests "com.marksy.os.gateway.MarksyGatewayClientTest" --tests "com.marksy.os.market.RealMarketApiClientTest"`
Expected: PASS (existing tests, before your changes).

- [ ] **Step 3: Update `MarksyTipsApiClient`'s constructor and header**

In `MarksyTipsApiClient.kt`:
1. Change the class declaration from `class MarksyTipsApiClient(private val integrationKey: String, baseUrl: String = DEFAULT_MARKSY_API_BASE_URL) : MarksyGatewayClient` to `class MarksyTipsApiClient(private val authRepository: com.marksy.os.gateway.AuthRepository, baseUrl: String = DEFAULT_MARKSY_API_BASE_URL) : MarksyGatewayClient`.
2. Remove the `if (integrationKey.isBlank()) return Result.failure(...)` check from `analyze()` — a null token from `authRepository.currentToken()` inside `execute()` now covers that case (see next step).
3. In the private `execute(method, url, payload)` function, replace the line `setRequestProperty("X-Marksy-Integration-Key", integrationKey)` with:
   ```kotlin
   val token = authRepository.currentToken() ?: throw MarksyTerminalException("Not signed in to Marksy")
   ```
   placed before `(URL(url).openConnection() as HttpURLConnection).apply { ... }`, then inside that `apply` block replace the removed header line with:
   ```kotlin
   setRequestProperty("Authorization", "Bearer $token")
   ```
   (`execute` is already a `suspend fun`, so calling the suspend `currentToken()` is valid there.)

- [ ] **Step 4: Update `RealMarketApiClient`'s constructor and header**

In `MarketApiClient.kt`:
1. Change `class RealMarketApiClient(apiKey: String, baseUrl: String) : MarketApiClient` to `class RealMarketApiClient(private val authRepository: com.marksy.os.gateway.AuthRepository, baseUrl: String) : MarketApiClient`, removing the `private val apiKey: String = apiKey.trim().also { require(...) }` line entirely (no more upfront blank-key validation — a null token is checked per-call instead, matching the Tips client's new pattern).
2. In the private `execute(url)` function, replace `setRequestProperty("X-API-Key", apiKey)` with:
   ```kotlin
   val token = authRepository.currentToken() ?: throw MarketApiException("Not signed in to Marksy")
   ```
   (before the `HttpURLConnection` block) and inside it:
   ```kotlin
   setRequestProperty("Authorization", "Bearer $token")
   ```

- [ ] **Step 5: Update the existing tests for the new constructors**

In `MarksyGatewayClientTest.kt`, the `unconfiguredClientDoesNotPretendDeliverySucceeded` test already uses `UnconfiguredMarksyGatewayClient()` (unaffected). No other test in that file constructs `MarksyTipsApiClient` directly — if you find one that does (check the full file), update it to pass a fake `AuthRepository`-shaped dependency; if none exists, no further change is needed here.

In `RealMarketApiClientTest.kt`, replace the whole file:
```kotlin
package com.marksy.os.market

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.marksy.os.gateway.AuthApiClient
import com.marksy.os.gateway.AuthRepository
import com.marksy.os.gateway.AuthSessionStore
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Robolectric is required here only because AuthSessionStore's constructor calls
// ApplicationProvider.getApplicationContext() (via the fake repository below),
// which needs a Robolectric-managed application context to resolve at all --
// NOT because this test touches Android Keystore. Construction-time validation
// only calls normalizeBaseUrl(); the fake repository's methods are never invoked,
// so unlike AuthSessionStoreTest this file needs no TestAndroidKeyStoreProvider
// setup.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RealMarketApiClientTest {
    @Test
    fun rejectsNonHttpsBaseUrl() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val fakeAuthRepository = AuthRepository(
            client = object : AuthApiClient {
                override suspend fun login(userId: String, password: String) = throw NotImplementedError()
                override suspend fun refresh(currentToken: String) = throw NotImplementedError()
                override suspend fun logout(currentToken: String) = throw NotImplementedError()
            },
            store = AuthSessionStore(context)
        )

        assertThrows(IllegalArgumentException::class.java) {
            RealMarketApiClient(authRepository = fakeAuthRepository, baseUrl = "http://insecure.example.com/api/v1")
        }
    }
}
```

(The original `rejectsBlankApiKeyAtConstruction` test is removed — there is no longer a constructor-time key to validate; a missing session is now a per-call condition, covered by Task 4's `AuthRepositoryTest.neverLoggedInYieldsNullToken` instead.)

- [ ] **Step 6: Run tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "com.marksy.os.gateway.MarksyGatewayClientTest" --tests "com.marksy.os.market.RealMarketApiClientTest"`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/marksy/os/gateway/MarksyGatewayClient.kt app/src/main/java/com/marksy/os/gateway/MarksyTipsApiClient.kt app/src/main/java/com/marksy/os/market/MarketApiClient.kt app/src/test/java/com/marksy/os/gateway/MarksyGatewayClientTest.kt app/src/test/java/com/marksy/os/market/RealMarketApiClientTest.kt
git commit -m "feat(auth): send session bearer token from MarksyTipsApiClient and RealMarketApiClient"
```

---

### Task 6: Remove old credentials, rewire `MarksyGatewayProvider` and `MarksyContainer`

**Files:**
- Modify: `app/src/main/java/com/marksy/os/gateway/SecureCredentialStore.kt`
- Modify: `app/src/main/java/com/marksy/os/gateway/MarksyGatewayProvider.kt`
- Modify: `app/src/main/java/com/marksy/os/data/MarksyContainer.kt`
- Test: `app/src/test/java/com/marksy/os/gateway/SecureCredentialStoreTest.kt` (modified)

**Interfaces:**
- Produces: `MarksyGatewayProvider.client(): MarksyGatewayClient` and `.marketIntelligenceClient(): com.marksy.os.market.MarketApiClient?` now construct through `AuthRepository` instead of raw stored keys; `.marketClient()` unchanged in shape. `MarksyContainer.authRepository(context): AuthRepository` (new accessor).

- [ ] **Step 1: Remove the old credential methods from `SecureCredentialStore`**

Read the current file in full, then remove: `getIntegrationKey`, `setIntegrationKey`, `clearIntegrationKey`, `getMarketApiKey`, `setMarketApiKey`, `clearMarketApiKey`, and their four companion-object constants (`KEY_ALIAS`'s usage stays if still referenced elsewhere in the file — check; `KEY_CIPHERTEXT`, `KEY_IV`, `KEY_MARKET_API_KEY_CIPHERTEXT`, `KEY_MARKET_API_KEY_IV` are removed). Keep `getBaseUrl`/`setBaseUrl`/`clearBaseUrl` and the shared `secretKey()`/`encode`/`decode` helpers exactly as they are (only remove them if nothing else in the file still calls them — `getBaseUrl`/`setBaseUrl` don't use encryption, so `secretKey()` etc. become unused **unless** something else needs them; if that leaves `secretKey()`/`encode`/`decode`/Keystore imports unused after removal, delete those too rather than leaving dead code).

- [ ] **Step 2: Update `SecureCredentialStoreTest.kt`**

Remove every test referencing the deleted methods (`marketApiKeyRoundTripsThroughEncryptedStorage`, `clearingMarketApiKeyRemovesIt`, `marketApiKeyIsIndependentOfIntegrationKey`, `upstoxAnalyticsTokenIsIndependentOfOtherCredentials` if the Upstox plan's Task 2 has already run and added it — check the file's current content first). If the file ends up with no tests exercising `SecureCredentialStore` at all (only base-URL methods remain, which the original file never had dedicated tests for either), add one minimal test:

```kotlin
    @Test
    fun baseUrlRoundTripsAsPlaintext() {
        val store = SecureCredentialStore(context)
        assertNull(store.getBaseUrl())

        store.setBaseUrl("https://api.indoulia.com/api/v1")

        assertEquals("https://api.indoulia.com/api/v1", store.getBaseUrl())
    }
```

(keep whatever Keystore test setup boilerplate the file already has if any other credential still needs it — otherwise this class may no longer need Robolectric/Keystore setup at all if `getBaseUrl`/`setBaseUrl` truly never touch encryption; verify by reading the post-Step-1 file before deciding whether to keep or remove the `@BeforeClass`/`TestAndroidKeyStoreProvider` scaffolding).

- [ ] **Step 3: Rewire `MarksyGatewayProvider`**

Replace the full file:

```kotlin
package com.marksy.os.gateway

import com.marksy.os.AppContext
import com.marksy.os.BuildConfig

/** Single construction point for the Marksy API clients, both authenticated
 * via the real session login (see `AuthRepository`) rather than a static key. */
object MarksyGatewayProvider {
    private fun authRepository(): AuthRepository {
        val baseUrl = (SecureCredentialStore(AppContext.get()).getBaseUrl() ?: BuildConfig.MARKSY_API_BASE_URL).trimEnd('/')
        return AuthRepository(RealAuthApiClient(baseUrl), AuthSessionStore(AppContext.get()))
    }

    fun client(): MarksyGatewayClient {
        val baseUrl = (SecureCredentialStore(AppContext.get()).getBaseUrl() ?: BuildConfig.MARKSY_API_BASE_URL).trimEnd('/')
        if (AuthSessionStore(AppContext.get()).getToken() == null) return UnconfiguredMarksyGatewayClient()
        return runCatching { MarksyTipsApiClient(authRepository(), baseUrl) as MarksyGatewayClient }
            .getOrElse { UnconfiguredMarksyGatewayClient() }
    }

    /** Null until the user is signed in; market data is read-only and optional. */
    fun marketClient(): MarksyTipsApiClient? = client() as? MarksyTipsApiClient

    /** Null until the user is signed in; every Market screen must degrade to
     * its own Unavailable state rather than crash when this is null. */
    fun marketIntelligenceClient(): com.marksy.os.market.MarketApiClient? {
        if (AuthSessionStore(AppContext.get()).getToken() == null) return null
        val baseUrl = (SecureCredentialStore(AppContext.get()).getBaseUrl() ?: BuildConfig.MARKSY_API_BASE_URL).trimEnd('/')
        return runCatching { com.marksy.os.market.RealMarketApiClient(authRepository(), baseUrl) as com.marksy.os.market.MarketApiClient }.getOrNull()
    }
}
```

(`AuthSessionStore(...).getToken() == null` is checked synchronously here — this is intentionally the cheap "has anyone ever logged in" gate, not a refresh check; the actual expiry/refresh logic lives inside each client's `execute()` via `AuthRepository.currentToken()`, per this plan's Architecture. A session that exists but is near/past expiry still constructs a real client here, and that client's first call performs the proactive refresh.)

- [ ] **Step 4: Add the `MarksyContainer` accessor**

Add to `MarksyContainer.kt`, mirroring its existing accessor style:

```kotlin
    fun authRepository(context: Context): com.marksy.os.gateway.AuthRepository {
        val baseUrl = (com.marksy.os.gateway.SecureCredentialStore(context).getBaseUrl() ?: com.marksy.os.BuildConfig.MARKSY_API_BASE_URL).trimEnd('/')
        return com.marksy.os.gateway.AuthRepository(
            com.marksy.os.gateway.RealAuthApiClient(baseUrl),
            com.marksy.os.gateway.AuthSessionStore(context)
        )
    }
```

- [ ] **Step 5: Run the full unscoped suite**

Run: `./gradlew :app:testDebugUnitTest` (no filter). Paste the actual BUILD output/test count, not a narrative summary.
Expected: PASS. Note: this step will likely surface compile errors in `MainActivity.kt` (the old `GatewaySettingsHost` still references the now-deleted `getIntegrationKey`/`setIntegrationKey`/etc.) — that is expected and is Task 8's job to fix, not this task's. If the module does not compile because of `MainActivity.kt` specifically, note that in your report as an expected, Task-8-owned gap rather than treating it as this task's failure; do not edit `MainActivity.kt` to work around it.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/marksy/os/gateway/SecureCredentialStore.kt app/src/main/java/com/marksy/os/gateway/MarksyGatewayProvider.kt app/src/main/java/com/marksy/os/data/MarksyContainer.kt app/src/test/java/com/marksy/os/gateway/SecureCredentialStoreTest.kt
git commit -m "feat(auth): remove old credential storage, wire providers through AuthRepository"
```

---

### Task 7: `LoginScreen`

**Files:**
- Create: `app/src/main/java/com/marksy/os/ui/LoginScreen.kt`
- Test: `app/src/test/java/com/marksy/os/ui/LoginScreenTest.kt`

**Interfaces:**
- Consumes: `AuthRepository` (Task 4).
- Produces: `LoginScreen(authRepository: AuthRepository, padding: PaddingValues, currentUserId: String?, onSignedIn: () -> Unit)` composable. Consumed by `MainActivity.kt` (Task 8).

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.marksy.os.ui

import android.content.Context
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.marksy.os.gateway.AuthApiClient
import com.marksy.os.gateway.AuthRepository
import com.marksy.os.gateway.AuthSessionStore
import com.marksy.os.gateway.SessionResponseDto
import com.marksy.os.gateway.TestAndroidKeyStoreProvider
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.security.Security

private class FixtureAuthApiClient(
    private val loginResult: SessionResponseDto? = null,
    private val loginError: Throwable? = null
) : AuthApiClient {
    override suspend fun login(userId: String, password: String): SessionResponseDto = loginError?.let { throw it } ?: loginResult!!
    override suspend fun refresh(currentToken: String) = throw NotImplementedError()
    override suspend fun logout(currentToken: String) = throw NotImplementedError()
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LoginScreenTest {
    @get:Rule val compose = createComposeRule()

    // See AuthSessionStoreTest's identical @Before for why this is needed:
    // AuthSessionStore's in-memory holder is a process-wide singleton, and
    // `successfulLoginCallsOnSignedIn` below writes a real session through it.
    @Before
    fun resetSession() {
        com.marksy.os.gateway.AuthSessionStore(ApplicationProvider.getApplicationContext<Context>()).clearSession()
    }

    companion object {
        @BeforeClass
        @JvmStatic
        fun setupAndroidKeyStore() {
            if (Security.getProvider("AndroidKeyStore") == null) {
                Security.addProvider(TestAndroidKeyStoreProvider())
            }
        }
    }

    private fun repository(client: AuthApiClient) =
        AuthRepository(client, AuthSessionStore(ApplicationProvider.getApplicationContext<Context>()))

    @Test
    fun notSignedInShowsLoginForm() {
        compose.setContent {
            LoginScreen(authRepository = repository(FixtureAuthApiClient()), padding = PaddingValues(), currentUserId = null, onSignedIn = {})
        }

        compose.onNodeWithText("Sign In").assertExists()
    }

    @Test
    fun successfulLoginCallsOnSignedIn() {
        var signedIn = false
        val session = SessionResponseDto("sess_abc", "prsingh", "2026-09-25T09:00:00Z", "2026-09-25T17:00:00Z", false)
        compose.setContent {
            LoginScreen(authRepository = repository(FixtureAuthApiClient(loginResult = session)), padding = PaddingValues(), currentUserId = null, onSignedIn = { signedIn = true })
        }

        compose.onNodeWithText("Marksy username").performTextInput("prsingh")
        compose.onNodeWithText("Password").performTextInput("correct-password")
        compose.onNodeWithText("Sign In").performClick()
        compose.waitForIdle()

        assert(signedIn)
    }

    @Test
    fun failedLoginShowsErrorAndDoesNotCallOnSignedIn() {
        var signedIn = false
        compose.setContent {
            LoginScreen(
                authRepository = repository(FixtureAuthApiClient(loginError = com.marksy.os.gateway.AuthApiException("HTTP 401: Invalid credentials."))),
                padding = PaddingValues(), currentUserId = null, onSignedIn = { signedIn = true }
            )
        }

        compose.onNodeWithText("Marksy username").performTextInput("prsingh")
        compose.onNodeWithText("Password").performTextInput("wrong-password")
        compose.onNodeWithText("Sign In").performClick()
        compose.waitForIdle()

        assert(!signedIn)
        compose.onNodeWithText("Invalid credentials", substring = true).assertExists()
    }

    @Test
    fun alreadySignedInShowsUserIdAndSignOut() {
        compose.setContent {
            LoginScreen(authRepository = repository(FixtureAuthApiClient()), padding = PaddingValues(), currentUserId = "prsingh", onSignedIn = {})
        }

        compose.onNodeWithText("prsingh", substring = true).assertExists()
        compose.onNodeWithText("Sign Out").assertExists()
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "com.marksy.os.ui.LoginScreenTest"`
Expected: FAIL — `LoginScreen` does not exist.

- [ ] **Step 3: Implement**

```kotlin
package com.marksy.os.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.marksy.os.gateway.AuthRepository
import kotlinx.coroutines.launch

@Composable
fun LoginScreen(authRepository: AuthRepository, padding: PaddingValues, currentUserId: String?, onSignedIn: () -> Unit) {
    val scope = rememberCoroutineScope()
    var userId by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var remember by rememberSaveable { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var signingIn by remember { mutableStateOf(false) }
    var signedInUserId by remember { mutableStateOf(currentUserId) }

    Column(
        Modifier.fillMaxSize().background(MarksyTheme.Background)
            .padding(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding())
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Marksy Account", color = MarksyTheme.TextPrimary, fontSize = 22.sp, fontWeight = FontWeight.Bold)

        if (signedInUserId != null) {
            Text("Signed in as ${signedInUserId}", color = MarksyTheme.PrimaryEmerald, fontSize = 14.sp)
            OutlinedButton(onClick = {
                scope.launch {
                    authRepository.logout()
                    signedInUserId = null
                }
            }) { Text("Sign Out", color = MarksyTheme.RedUrgent) }
            return@Column
        }

        Text(
            "Sign in with your Marksy account. This replaces any previously configured integration or market keys.",
            color = MarksyTheme.TextSecondary,
            fontSize = 13.sp
        )
        CompactTextField(value = userId, onValueChange = { userId = it }, modifier = Modifier.fillMaxWidth(), label = "Marksy username")
        CompactTextField(
            value = password,
            onValueChange = { password = it },
            modifier = Modifier.fillMaxWidth(),
            label = "Password",
            visualTransformation = PasswordVisualTransformation()
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = remember, onCheckedChange = { remember = it })
            Text("Remember me on this device", color = MarksyTheme.TextSecondary, fontSize = 13.sp)
        }
        Button(
            onClick = {
                if (signingIn) return@Button
                signingIn = true
                errorMessage = null
                scope.launch {
                    val result = authRepository.login(userId.trim(), password, remember)
                    signingIn = false
                    result.onSuccess {
                        password = ""
                        signedInUserId = userId.trim()
                        onSignedIn()
                    }.onFailure { error ->
                        errorMessage = error.message ?: "Sign in failed. Try again."
                    }
                }
            },
            enabled = userId.isNotBlank() && password.isNotBlank() && !signingIn,
            colors = ButtonDefaults.buttonColors(containerColor = MarksyTheme.PrimaryEmerald)
        ) { Text(if (signingIn) "Signing In..." else "Sign In", color = androidx.compose.ui.graphics.Color.Black) }
        errorMessage?.let { Text(it, color = MarksyTheme.RedUrgent, fontSize = 13.sp) }
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "com.marksy.os.ui.LoginScreenTest"`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/marksy/os/ui/LoginScreen.kt app/src/test/java/com/marksy/os/ui/LoginScreenTest.kt
git commit -m "feat(auth): add LoginScreen with remember-me"
```

---

### Task 8: Wire `LoginScreen` into `MainActivity`, remove the old Gateway Settings UI

**Files:**
- Modify: `app/src/main/java/com/marksy/os/MainActivity.kt`

**Interfaces:**
- Consumes: `LoginScreen` (Task 7), `MarksyContainer.authRepository(context)` (Task 6).
- Produces: no new public API — replaces `GatewaySettingsHost`'s call site and the "Marksy Gateway" More-screen card with login-aware equivalents.

- [ ] **Step 1: Read the current `MainActivity.kt` in full**

Its `GatewaySettingsHost` composable, the `showGatewaySettings -> GatewaySettingsHost(padding)` call site, and the "Marksy Gateway" `SettingsCard` in `MoreScreen` all need locating by name — do not assume line numbers, this file has changed repeatedly this session (and, if the Upstox plan's Task 5 already ran, it now also contains an Upstox block inside `GatewaySettingsHost` that this task removes along with everything else in that composable).

- [ ] **Step 2: Replace the `GatewaySettingsHost` call site**

Change:
```kotlin
                showGatewaySettings -> GatewaySettingsHost(padding)
```
to:
```kotlin
                showGatewaySettings -> Column(Modifier.fillMaxSize().background(MarksyTheme.Background)) {
                    LoginScreen(
                        authRepository = remember { MarksyContainer.authRepository(applicationContext) },
                        padding = padding,
                        currentUserId = remember { com.marksy.os.gateway.AuthSessionStore(applicationContext).getUserId() },
                        onSignedIn = { showGatewaySettings = false }
                    )
                }
```

- [ ] **Step 3: Delete the `GatewaySettingsHost` composable and its now-unused imports**

Delete the entire `@Composable private fun GatewaySettingsHost(padding: PaddingValues) { ... }` function. After deleting it, check whether any imports it alone used (`ScanContract`, `ScanOptions`, `GatewayQrParser`, `VisualTransformation`, `PasswordVisualTransformation`, etc.) are still used elsewhere in `MainActivity.kt` — remove any that are now unused. (If the Upstox plan's QR-scan work (Task 3) already landed and nothing else in this file uses `GatewayQrParser`, that import is removed too; if you're implementing this plan before the Upstox plan, `GatewayQrParser`/`ScanContract` usage was entirely inside `GatewaySettingsHost` already, so the same applies.)

- [ ] **Step 4: Update the "Marksy Gateway" card in `MoreScreen`**

Find the `SettingsCard("Marksy Gateway", if (gatewayConfigured) "READY" else "NOT CONFIGURED", "Integration credentials stored securely.") { ... }` block and its `gatewayConfigured` state (currently derived from `store.getIntegrationKey() != null`). Replace with:

```kotlin
        item {
            val signedInUserId = remember { com.marksy.os.gateway.AuthSessionStore(applicationContext).getUserId() }
            SettingsCard(
                "Marksy Account",
                if (signedInUserId != null) "SIGNED IN" else "NOT SIGNED IN",
                if (signedInUserId != null) "Signed in as $signedInUserId." else "Sign in to enable Trading delivery and Market Intelligence."
            ) {
                Button(modifier = Modifier.height(32.dp), contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp), onClick = openGatewaySettings, colors = ButtonDefaults.buttonColors(containerColor = MarksyTheme.PrimaryEmerald)) {
                    Text(if (signedInUserId != null) "Manage Account" else "Sign In", color = Color.Black, fontSize = 12.sp)
                }
            }
        }
```

removing the old `val store = remember { SecureCredentialStore(AppContext.get()) }` / `var gatewayConfigured by remember { mutableStateOf(store.getIntegrationKey() != null) }` lines from `MoreScreen` if nothing else in that composable still uses `store`.

- [ ] **Step 5: Build and run the full unscoped suite**

Run: `./gradlew :app:testDebugUnitTest` (no filter). Paste the actual BUILD output/test count.
Expected: PASS, all tests including every pre-existing one. The module must now compile cleanly (Task 6's expected gap is closed by this task).

- [ ] **Step 6: Manually verify on device**

Sign in with a real Marksy account/password. Confirm: the More screen's "Marksy Account" card shows "SIGNED IN" with your userId; Trading delivery and Market Intelligence screens now work without any separately-entered key; "Sign Out" returns to the login form; force-closing the app after signing in with "Remember me" unchecked shows the login form again on reopen, while checked keeps you signed in.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/marksy/os/MainActivity.kt
git commit -m "feat(auth): replace Gateway Settings key-entry with real Marksy login"
```
