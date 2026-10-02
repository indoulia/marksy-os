# Marksy Portfolio Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace Market › Portfolio's placeholder with a triage-first holdings page fed by a daily, read-only Upstox OAuth sign-in.

**Architecture:**
- Pure Kotlin logic lives in `upstox/` and `portfolio/`, unit-tested on the JVM:
  - `UpstoxOAuth` (authorize URL, redirect, token, expiry);
  - `UpstoxHoldings` (parser);
  - `UpstoxHoldingsSource` (provider mapping);
  - `PortfolioMath` (valuation, ranking, allocation);
  - `PortfolioFlags` (Needs a look).
- Android pieces:
  - `UpstoxOAuthStore`, the encrypted store;
  - `PortfolioRepository`, the cache, refresh and candles;
  - `UpstoxSignInActivity`, a WebView that catches the redirect;
  - `PortfolioScreen`, the Compose page.
- `MarketScreen` and `MainActivity` change by only a few lines.

**Tech Stack:** Kotlin 2.3, Jetpack Compose (BOM 2026.08), Android Keystore AES-GCM, `HttpURLConnection`,
`org.json`, Robolectric 4.17 and JUnit 4.

**Spec:** `docs/superpowers/specs/2026-10-02-marksy-portfolio-design.md`

## Global Constraints

- **Read-only.** No order, modify or GTT endpoint anywhere. Holdings come only from `GET https://api.upstox.com/v2/portfolio/long-term-holdings`.
- **Nothing to the Marksy backend.** No holdings data reaches it, and nothing in `portfolio/` references `MarksyContainer`, `MarketApiClient`, `MarketIntelligenceRepository` or `gateway`.
- **Credential handling.** The API key, secret, access token, holdings cache and hidden list are Keystore-encrypted (AES/GCM, alias `marksy_os_upstox_oauth`, prefs `upstox_oauth`). They are never logged, never put in an Intent, and only sent to api.upstox.com.
- **Thresholds.** 15% below average, within 4% of an alert, 20% weight, 4% day move. These are fixed constants.
- **Expiry.** A token expires at the first 03:30 Asia/Kolkata after it was issued. A 401 clears the token and keeps the keys.
- **UI rules:**
  - no heading, label or button rows at the top, and the first 100dp is content;
  - the section goes in the header note;
  - filters, search and sort go on OneHandControls, and destructive actions only in Settings behind a confirmation;
  - `MarksyDialog`, `Pill` in a `FlowRow` and `MarksyTheme` colours;
  - gains `PrimaryEmerald`, losses `RedUrgent`.
- **Comments:** one line max, non-obvious WHY only.
- **Gradle (Git Bash):** first `export PATH="$(echo "$PATH" | tr ':' '\n' | grep -vE '"|%' | paste -sd:)"`, then `./gradlew --no-daemon …`. Never `connectedAndroidTest`.
- **Commits** end with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`. Never stage `local.properties`.

## Review Focus

- **The redirect arrives through a server 302 rather than a page load.** `shouldOverrideUrlLoading` and `onPageStarted` must both catch it, exactly once. Pinned by `isRedirect` tests (query, trailing slash, default port) in Task 1.
- **A holding has no live tick and no previous close** (suspended, or a new listing). The rows show "—" for today, and totals leave it out of the period %. Pinned in Task 4 (`periodPnl` null excluded).
- **Token issued between midnight and 03:30.** It expires the same morning, not the next day. Pinned in Task 1 (`02:10 → same-day 03:30`).
- **Signed out at 3:30 with cached holdings.** The page still shows the holdings, marked stale, with the sign-in card. Pinned in Task 2: clearing the token keeps the keys, and the holdings cache survives.
- **Floating-point boundaries on the thresholds** (exactly −15.0%, 20%). Pinned in Task 5 with inside and outside cases.

---

### Task 1: Upstox OAuth logic

**Files:**
- Create: `app/src/main/java/com/marksy/os/upstox/UpstoxOAuth.kt`
- Test: `app/src/test/java/com/marksy/os/upstox/UpstoxOAuthTest.kt`

**Interfaces:**
- Produces:
  - `UpstoxOAuth.Credentials(apiKey, apiSecret, redirectUri)`
  - `authorizeUrl(Credentials, state): String`
  - `isRedirect(url, redirectUri): Boolean`
  - `isUpstoxPage(url): Boolean`
  - `parseRedirect(url, expectedState): Redirect` (`Redirect.Code(code)` or `Redirect.Failed(message)`)
  - `tokenRequestBody(code, Credentials): String`
  - `parseToken(body): String`
  - `expiresAt(issuedAtMs): Long`
  - `newState(): String`
  - `suspend exchange(code, Credentials): String`
  - `DEFAULT_REDIRECT`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.marksy.os.upstox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.time.LocalDateTime
import java.time.ZoneId

class UpstoxOAuthTest {
    private val creds = UpstoxOAuth.Credentials("my-key", "s3c&ret=1", "http://127.0.0.1/marksy-upstox")
    private fun ist(at: String) = LocalDateTime.parse(at).atZone(ZoneId.of("Asia/Kolkata")).toInstant().toEpochMilli()

    @Test fun authorizeUrlCarriesKeyRedirectAndStateButCredentialsNeverPrint() {
        assertEquals(
            "https://api.upstox.com/v2/login/authorization/dialog?response_type=code&client_id=my-key&redirect_uri=http%3A%2F%2F127.0.0.1%2Fmarksy-upstox&state=abc123",
            UpstoxOAuth.authorizeUrl(creds, "abc123")
        )
        assertFalse(creds.toString().contains("s3c"))
        assertFalse(creds.toString().contains("my-key"))
    }

    @Test fun redirectMatchesOnlyTheRegisteredAddress() {
        val r = creds.redirectUri
        assertTrue(UpstoxOAuth.isRedirect("http://127.0.0.1/marksy-upstox?code=mk404x&state=XX56849", r))
        assertTrue(UpstoxOAuth.isRedirect("http://127.0.0.1:80/marksy-upstox/?code=x", r))
        assertFalse(UpstoxOAuth.isRedirect("https://127.0.0.1/marksy-upstox?code=x", r))
        assertFalse(UpstoxOAuth.isRedirect("http://127.0.0.1:8080/marksy-upstox?code=x", r))
        assertFalse(UpstoxOAuth.isRedirect("http://evil.example/marksy-upstox?code=x", r))
        assertFalse(UpstoxOAuth.isRedirect("http://127.0.0.1/other?code=x", r))
        assertFalse(UpstoxOAuth.isRedirect("not a url", r))
    }

    @Test fun onlyHttpsUpstoxPagesMayOpen() {
        assertTrue(UpstoxOAuth.isUpstoxPage("https://login.upstox.com/login/v2/oauth/authorize?x=1"))
        assertTrue(UpstoxOAuth.isUpstoxPage("https://api.upstox.com/v2/login/authorization/dialog"))
        assertFalse(UpstoxOAuth.isUpstoxPage("http://login.upstox.com/"))
        assertFalse(UpstoxOAuth.isUpstoxPage("https://upstox.com.evil.example/"))
        assertFalse(UpstoxOAuth.isUpstoxPage("https://notupstox.com/"))
    }

    @Test fun redirectYieldsTheCodeOnlyWhenStateMatches() {
        val url = "http://127.0.0.1/marksy-upstox?code=mk404x&state=XX56849"
        assertEquals(UpstoxOAuth.Redirect.Code("mk404x"), UpstoxOAuth.parseRedirect(url, "XX56849"))
        assertTrue(UpstoxOAuth.parseRedirect(url, "other") is UpstoxOAuth.Redirect.Failed)
        assertTrue(UpstoxOAuth.parseRedirect("http://127.0.0.1/marksy-upstox?state=XX56849", "XX56849") is UpstoxOAuth.Redirect.Failed)
        assertTrue(UpstoxOAuth.parseRedirect("http://127.0.0.1/marksy-upstox?code=%zz&state=XX56849", "XX56849") is UpstoxOAuth.Redirect.Failed)
        val denied = UpstoxOAuth.parseRedirect("http://127.0.0.1/marksy-upstox?error=access_denied&state=XX56849", "XX56849")
        assertEquals("Upstox: access_denied", (denied as UpstoxOAuth.Redirect.Failed).message)
    }

    @Test fun tokenRequestIsFormEncoded() {
        assertEquals(
            "code=mk404x&client_id=my-key&client_secret=s3c%26ret%3D1&redirect_uri=http%3A%2F%2F127.0.0.1%2Fmarksy-upstox&grant_type=authorization_code",
            UpstoxOAuth.tokenRequestBody("mk404x", creds)
        )
    }

    @Test fun documentedTokenResponseYieldsTheAccessToken() {
        val body = """{"email":"******","exchanges":["NSE","NFO","BSE","CDS","BFO","BCD"],"products":["D","CO","I"],"broker":"UPSTOX","user_id":"******","user_name":"******","order_types":["MARKET","LIMIT","SL","SL-M"],"user_type":"individual","poa":false,"is_active":true,"access_token":"eyJ0eXAi.token","extended_token":"ext"}"""
        assertEquals("eyJ0eXAi.token", UpstoxOAuth.parseToken(body))
    }

    @Test fun tokenErrorCarriesUpstoxsMessage() {
        val body = """{"status":"error","errors":[{"errorCode":"UDAPI100057","message":"Invalid Auth code","propertyPath":null,"invalidValue":null,"error_code":"UDAPI100057","property_path":null,"invalid_value":null}]}"""
        assertEquals("Upstox: Invalid Auth code", assertThrows(IOException::class.java) { UpstoxOAuth.parseToken(body) }.message)
        assertThrows(IOException::class.java) { UpstoxOAuth.parseToken("<html>") }
    }

    @Test fun tokenLastsUntilTheNext0330Ist() {
        assertEquals(ist("2026-10-03T03:30"), UpstoxOAuth.expiresAt(ist("2026-10-02T09:05")))
        assertEquals(ist("2026-10-02T03:30"), UpstoxOAuth.expiresAt(ist("2026-10-02T02:10")))
        assertEquals(ist("2026-10-03T03:30"), UpstoxOAuth.expiresAt(ist("2026-10-02T03:30")))
    }

    @Test fun stateIs32RandomHexCharacters() {
        val s = UpstoxOAuth.newState()
        assertTrue(s.matches(Regex("[0-9a-f]{32}")))
        assertNotEquals(s, UpstoxOAuth.newState())
    }
}
```

- [ ] **Step 2: Run the test and confirm it fails**

Run: `./gradlew --no-daemon :app:testDebugUnitTest --tests com.marksy.os.upstox.UpstoxOAuthTest`
Expected: FAIL, compilation error "Unresolved reference: UpstoxOAuth".

- [ ] **Step 3: Implement**

```kotlin
package com.marksy.os.upstox

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.SecureRandom
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/** Upstox OAuth (authorization code) with the user's own Upstox app: it reads holdings, never places orders. */
object UpstoxOAuth {
    const val AUTHORIZE_URL = "https://api.upstox.com/v2/login/authorization/dialog"
    const val TOKEN_URL = "https://api.upstox.com/v2/login/authorization/token"
    // Loopback: even a redirect the WebView failed to catch would never leave the phone.
    const val DEFAULT_REDIRECT = "http://127.0.0.1/marksy-upstox"
    private val IST: ZoneId = ZoneId.of("Asia/Kolkata")
    private val CUTOFF: LocalTime = LocalTime.of(3, 30)
    private const val MAX_RESPONSE_CHARS = 64_000

    data class Credentials(val apiKey: String, val apiSecret: String, val redirectUri: String) {
        override fun toString() = "Credentials(apiKey=***, apiSecret=***, redirectUri=$redirectUri)"
    }

    sealed interface Redirect {
        data class Code(val code: String) : Redirect { override fun toString() = "Code(***)" }
        data class Failed(val message: String) : Redirect
    }

    fun authorizeUrl(credentials: Credentials, state: String): String =
        "$AUTHORIZE_URL?response_type=code&client_id=${enc(credentials.apiKey)}&redirect_uri=${enc(credentials.redirectUri)}&state=${enc(state)}"

    /** Same scheme, host, port and path as the registered redirect, whatever the query. */
    fun isRedirect(url: String, redirectUri: String): Boolean {
        val a = uri(url) ?: return false
        val b = uri(redirectUri) ?: return false
        if (a.scheme == null || a.host == null) return false
        return a.scheme.equals(b.scheme, ignoreCase = true) && a.host.equals(b.host, ignoreCase = true) &&
            port(a) == port(b) && a.path.orEmpty().trimEnd('/') == b.path.orEmpty().trimEnd('/')
    }

    fun isUpstoxPage(url: String): Boolean {
        val u = uri(url) ?: return false
        val host = u.host?.lowercase() ?: return false
        return u.scheme.equals("https", ignoreCase = true) && (host == "upstox.com" || host.endsWith(".upstox.com"))
    }

    fun parseRedirect(url: String, expectedState: String): Redirect {
        val q = query(url) ?: return Redirect.Failed("Upstox sent an unreadable sign-in reply. Try again.")
        q["error"]?.let { return Redirect.Failed("Upstox: ${q["error_description"] ?: it}") }
        if (q["state"] != expectedState) return Redirect.Failed("That sign-in didn't come from this request. Try again.")
        val code = q["code"]?.takeIf { it.isNotBlank() } ?: return Redirect.Failed("Upstox sent no sign-in code. Try again.")
        return Redirect.Code(code)
    }

    fun tokenRequestBody(code: String, credentials: Credentials): String = listOf(
        "code" to code,
        "client_id" to credentials.apiKey,
        "client_secret" to credentials.apiSecret,
        "redirect_uri" to credentials.redirectUri,
        "grant_type" to "authorization_code"
    ).joinToString("&") { (k, v) -> "$k=${enc(v)}" }

    fun parseToken(body: String): String = try {
        val root = JSONObject(body)
        root.optString("access_token").takeIf { it.isNotBlank() }
            ?: throw IOException(
                root.optJSONArray("errors")?.optJSONObject(0)?.optString("message")?.takeIf { it.isNotBlank() }?.let { "Upstox: $it" }
                    ?: "Upstox returned no access token"
            )
    } catch (e: JSONException) {
        throw IOException("Upstox returned an unreadable sign-in response", e)
    }

    fun expiresAt(issuedAtMs: Long): Long {
        val issued = Instant.ofEpochMilli(issuedAtMs).atZone(IST)
        val cutoff = issued.toLocalDate().atTime(CUTOFF).atZone(IST)
        return (if (cutoff.isAfter(issued)) cutoff else cutoff.plusDays(1)).toInstant().toEpochMilli()
    }

    fun newState(random: SecureRandom = SecureRandom()): String = ByteArray(16).also(random::nextBytes).joinToString("") { "%02x".format(it) }

    /** Trades the one-time code for today's access token; the secret travels only in this https POST body. */
    suspend fun exchange(code: String, credentials: Credentials): String = withContext(Dispatchers.IO) {
        val connection = (URL(TOKEN_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 10_000
            readTimeout = 10_000
            instanceFollowRedirects = false
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        }
        val started = System.currentTimeMillis()
        var failure: String? = "no response"
        try {
            connection.outputStream.use { it.write(tokenRequestBody(code, credentials).toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            val body = (if (status in 200..299) connection.inputStream else connection.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (body.length > MAX_RESPONSE_CHARS) throw IOException("Upstox sign-in response exceeded the safety limit")
            parseToken(body).also { failure = null }
        } catch (e: IOException) {
            failure = e.message ?: e.javaClass.simpleName
            throw e
        } finally {
            UpstoxRestStats.record(TOKEN_URL, ok = failure == null, ms = System.currentTimeMillis() - started, error = failure)
            connection.disconnect()
        }
    }

    private fun uri(url: String): URI? = runCatching { URI(url) }.getOrNull()
    private fun port(u: URI): Int = if (u.port != -1) u.port else if (u.scheme.equals("https", ignoreCase = true)) 443 else 80
    private fun enc(v: String) = URLEncoder.encode(v, "UTF-8")
    private fun query(url: String): Map<String, String>? = runCatching {
        uri(url)?.rawQuery.orEmpty().split('&').filter { '=' in it }
            .associate { URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('='), "UTF-8") }
    }.getOrNull()
}
```

- [ ] **Step 4: Run the test and confirm it passes**

Run: `./gradlew --no-daemon :app:testDebugUnitTest --tests com.marksy.os.upstox.UpstoxOAuthTest`
Expected: PASS (9 tests).

- [ ] **Step 5: Commit**

`git add app/src/main/java/com/marksy/os/upstox/UpstoxOAuth.kt app/src/test/java/com/marksy/os/upstox/UpstoxOAuthTest.kt && git commit -m "feat(portfolio): Upstox OAuth URL, redirect, token and expiry logic"`

### Task 2: Encrypted OAuth store

**Files:**
- Create: `app/src/main/java/com/marksy/os/upstox/UpstoxOAuthStore.kt`
- Test: `app/src/test/java/com/marksy/os/upstox/UpstoxOAuthStoreTest.kt`

**Interfaces:**
- Consumes: `UpstoxOAuth.Credentials`, `UpstoxOAuth.expiresAt`, `UpstoxOAuth.DEFAULT_REDIRECT`.
- Produces: `UpstoxOAuthStore(context)` with:
  - `credentials(): Credentials?`, `hasCredentials()` and `saveCredentials(key, secret, redirect)`;
  - `saveToken(token, issuedAt)`, `accessToken(now = System.currentTimeMillis()): String?`, `signedInAt(): Long?` and `clearToken()`;
  - `saveHoldings(json)` / `holdings()` and `saveHidden(json)` / `hidden()`;
  - `disconnect()`.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.marksy.os.upstox

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.marksy.os.gateway.TestAndroidKeyStoreProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.security.Security

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UpstoxOAuthStoreTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val issued = 1_790_000_000_000L

    companion object {
        @BeforeClass
        @JvmStatic
        fun setupAndroidKeyStore() {
            if (Security.getProvider("AndroidKeyStore") == null) Security.addProvider(TestAndroidKeyStoreProvider())
        }
    }

    @Before
    fun reset() = UpstoxOAuthStore(context).disconnect()

    @Test
    fun keysTokenAndHoldingsAreEncryptedAtRest() {
        val store = UpstoxOAuthStore(context)
        store.saveCredentials(" key-123 ", "secret-xyz", "http://127.0.0.1/marksy-upstox")
        store.saveToken("token-abc", issued)
        store.saveHoldings("""[{"symbol":"SUZLON"}]""")

        val again = UpstoxOAuthStore(context)
        assertEquals(UpstoxOAuth.Credentials("key-123", "secret-xyz", "http://127.0.0.1/marksy-upstox"), again.credentials())
        assertEquals("token-abc", again.accessToken(issued + 60_000))
        assertEquals("""[{"symbol":"SUZLON"}]""", again.holdings())
        val raw = context.getSharedPreferences("upstox_oauth", Context.MODE_PRIVATE).all.values.joinToString()
        listOf("key-123", "secret-xyz", "token-abc", "SUZLON").forEach { assertFalse(it, raw.contains(it)) }
    }

    @Test
    fun tokenStopsAtTheCutoff() {
        val store = UpstoxOAuthStore(context)
        store.saveToken("token-abc", issued)
        val cutoff = UpstoxOAuth.expiresAt(issued)
        assertEquals("token-abc", store.accessToken(cutoff - 1))
        assertNull(store.accessToken(cutoff))
    }

    @Test
    fun signingOutKeepsTheKeysAndNewKeysDropTheToken() {
        val store = UpstoxOAuthStore(context)
        store.saveCredentials("key", "secret", "")
        store.saveToken("token", issued)
        store.clearToken()
        assertNull(store.accessToken(issued + 1))
        assertNull(store.signedInAt())
        assertEquals(UpstoxOAuth.DEFAULT_REDIRECT, store.credentials()!!.redirectUri)

        store.saveToken("token", issued)
        store.saveCredentials("key2", "secret2", UpstoxOAuth.DEFAULT_REDIRECT)
        assertNull(store.accessToken(issued + 1))
        assertNotNull(store.credentials())
    }

    @Test
    fun disconnectForgetsEverythingButTheAnalyticsToken() {
        UpstoxTokenStore(context).save("analytics")
        val store = UpstoxOAuthStore(context)
        store.saveCredentials("key", "secret", UpstoxOAuth.DEFAULT_REDIRECT)
        store.saveToken("token", issued)
        store.saveHoldings("[]")
        store.saveHidden("{}")

        store.disconnect()

        assertNull(store.credentials())
        assertNull(store.accessToken(issued + 1))
        assertNull(store.holdings())
        assertNull(store.hidden())
        assertEquals("analytics", UpstoxTokenStore(context).getToken())
        UpstoxTokenStore(context).clear()
    }
}
```

- [ ] **Step 2: Run the test and confirm it fails**

Run: `./gradlew --no-daemon :app:testDebugUnitTest --tests com.marksy.os.upstox.UpstoxOAuthStoreTest`
Expected: FAIL, "Unresolved reference: UpstoxOAuthStore".

- [ ] **Step 3: Implement**

```kotlin
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
 * The user's Upstox app keys, today's OAuth token and the last holdings, each Keystore-encrypted with its own
 * alias (same pattern as [UpstoxTokenStore]). Keys and token are only ever sent to api.upstox.com.
 */
class UpstoxOAuthStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun credentials(): UpstoxOAuth.Credentials? {
        val key = read(API_KEY) ?: return null
        val secret = read(API_SECRET) ?: return null
        return UpstoxOAuth.Credentials(key, secret, read(REDIRECT) ?: UpstoxOAuth.DEFAULT_REDIRECT)
    }

    fun hasCredentials(): Boolean = preferences.contains(API_KEY) && preferences.contains(API_SECRET)

    /** A different app's keys can't use the old app's token. */
    fun saveCredentials(apiKey: String, apiSecret: String, redirectUri: String) {
        write(API_KEY, apiKey.trim())
        write(API_SECRET, apiSecret.trim())
        write(REDIRECT, redirectUri.trim().ifBlank { UpstoxOAuth.DEFAULT_REDIRECT })
        clearToken()
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
```

- [ ] **Step 4: Run the test and confirm it passes.** Same command. Expected: PASS (4 tests).
- [ ] **Step 5: Commit**

`git add app/src/main/java/com/marksy/os/upstox/UpstoxOAuthStore.kt app/src/test/java/com/marksy/os/upstox/UpstoxOAuthStoreTest.kt && git commit -m "feat(portfolio): Keystore-encrypted Upstox OAuth store"`

### Task 3: Holdings parser, provider interface and Upstox mapping

**Files:**
- Create: `app/src/main/java/com/marksy/os/upstox/UpstoxHoldings.kt`, `app/src/main/java/com/marksy/os/portfolio/Portfolio.kt` and `app/src/main/java/com/marksy/os/portfolio/UpstoxHoldingsSource.kt`
- Modify: `app/src/main/java/com/marksy/os/upstox/UpstoxApiClient.kt`: the class doc and `longTermHoldings()`.
- Modify: `app/src/main/java/com/marksy/os/upstox/UpstoxRestStats.kt`: the `"portfolio/"` family.
- Modify: `app/src/test/java/com/marksy/os/PrivacyBoundaryTest.kt`: the portfolio guard.
- Test: `app/src/test/java/com/marksy/os/upstox/UpstoxHoldingsTest.kt` and `app/src/test/java/com/marksy/os/portfolio/UpstoxHoldingsSourceTest.kt`

**Interfaces:**
- Produces: `UpstoxHolding(isin, companyName, tradingSymbol, instrumentToken, exchange, quantity: Long, averagePrice, lastPrice, closePrice: Double?)`; `UpstoxHoldings.parse(body)`; `UpstoxApiClient.longTermHoldings(): List<UpstoxHolding>`.
- Produces in `com.marksy.os.portfolio`:
  - `HoldingType { STOCK("Stocks"), ETF("ETFs") }` with `label`;
  - `Holding(symbol, name, isin, instrumentKey, type, quantity: Long, averagePrice, lastPrice, previousClose: Double?, sector: String? = null)`;
  - `HoldingsSnapshot(providerId, holdings, fetchedAt)` with `toJson()` and `fromJson(json): HoldingsSnapshot?`;
  - `HoldingsResult` (`Ok(snapshot)`, `SignedOut(reason)` or `Failed(message)`);
  - `fun interface HoldingsSource { suspend fun fetch(now: Long): HoldingsResult }`;
  - `PortfolioProvider(id, name, note, source)` with `available`;
  - `PortfolioProviders.UPSTOX` and `catalog(upstox)`;
  - `UpstoxHoldingsSource(token: () -> String?, load: suspend (String) -> List<UpstoxHolding>)` with `toHolding()` and `isEtf()`.

- [ ] **Step 1: Write the failing tests**

`UpstoxHoldingsTest.kt`:

```kotlin
package com.marksy.os.upstox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException

class UpstoxHoldingsTest {
    // Verbatim from https://upstox.com/developer/api-documentation/get-holdings/
    private val sample = """{"status":"success","data":[{"isin":"INE528G01035","cnc_used_quantity":0,"collateral_type":"WC","company_name":"YES BANK LTD.","haircut":0.2,"product":"D","quantity":36,"trading_symbol":"YESBANK","tradingsymbol":"YESBANK","last_price":17.05,"close_price":17.05,"pnl":-61.2,"day_change":0,"day_change_percentage":0,"instrument_token":"NSE_EQ|INE528G01035","average_price":18.75,"collateral_quantity":0,"collateral_update_quantity":0,"t1_quantity":0,"exchange":"NSE"}]}"""

    @Test fun documentedSampleParses() {
        assertEquals(
            listOf(UpstoxHolding("INE528G01035", "YES BANK LTD.", "YESBANK", "NSE_EQ|INE528G01035", "NSE", 36, 18.75, 17.05, 17.05)),
            UpstoxHoldings.parse(sample)
        )
    }

    @Test fun emptyAccountIsAnEmptyList() {
        assertEquals(emptyList<UpstoxHolding>(), UpstoxHoldings.parse("""{"status":"success","data":[]}"""))
    }

    @Test fun upstoxErrorsAndGarbageThrowIOException() {
        val e = assertThrows(IOException::class.java) {
            UpstoxHoldings.parse("""{"status":"error","errors":[{"errorCode":"UDAPI100050","message":"Invalid token used to access API"}]}""")
        }
        assertEquals("Upstox: Invalid token used to access API", e.message)
        assertThrows(IOException::class.java) { UpstoxHoldings.parse("not json") }
    }
}
```

`UpstoxHoldingsSourceTest.kt`:

```kotlin
package com.marksy.os.portfolio

import com.marksy.os.upstox.UpstoxAuthException
import com.marksy.os.upstox.UpstoxHolding
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class UpstoxHoldingsSourceTest {
    private fun u(symbol: String, qty: Long = 10, name: String = "$symbol LTD", ltp: Double = 100.0, close: Double? = 98.0) =
        UpstoxHolding("INE000000001", name, symbol, "NSE_EQ|INE000000001", "NSE", qty, 90.0, ltp, close)

    @Test fun mapsUpstoxHoldingsWithTodaysTokenAndDropsEmptyOnes(): Unit = runBlocking {
        var sent: String? = null
        val source = UpstoxHoldingsSource({ "token" }) { t -> sent = t; listOf(u("suzlon"), u("BEL", qty = 0)) }
        val ok = source.fetch(1_000L) as HoldingsResult.Ok
        assertEquals("token", sent)
        assertEquals(
            HoldingsSnapshot("upstox", listOf(Holding("SUZLON", "suzlon LTD", "INE000000001", "NSE_EQ|INE000000001", HoldingType.STOCK, 10, 90.0, 100.0, 98.0)), 1_000L),
            ok.snapshot
        )
    }

    @Test fun etfsAreToldApartBySymbolOrName() {
        assertEquals(HoldingType.ETF, UpstoxHoldingsSource.toHolding(u("NIFTYBEES"))!!.type)
        assertEquals(HoldingType.ETF, UpstoxHoldingsSource.toHolding(u("MON100", name = "MOTILAL OSWAL NASDAQ 100 ETF"))!!.type)
        assertEquals(HoldingType.ETF, UpstoxHoldingsSource.toHolding(u("SETFNIF50", name = "SBI-ETF NIFTY 50"))!!.type)
        assertEquals(HoldingType.STOCK, UpstoxHoldingsSource.toHolding(u("HDFCBANK", name = "HDFC BANK LTD"))!!.type)
    }

    @Test fun missingLastPriceFallsBackToCloseThenAverage() {
        assertEquals(98.0, UpstoxHoldingsSource.toHolding(u("A", ltp = 0.0))!!.lastPrice, 0.0)
        assertEquals(90.0, UpstoxHoldingsSource.toHolding(u("A", ltp = 0.0, close = null))!!.lastPrice, 0.0)
    }

    @Test fun signInStateDecidesTheResult(): Unit = runBlocking {
        assertTrue(UpstoxHoldingsSource({ null }) { error("must not call Upstox") }.fetch(0) is HoldingsResult.SignedOut)
        assertTrue(UpstoxHoldingsSource({ "t" }) { throw UpstoxAuthException("401") }.fetch(0) is HoldingsResult.SignedOut)
        assertEquals(HoldingsResult.Failed("timeout"), UpstoxHoldingsSource({ "t" }) { throw IOException("timeout") }.fetch(0))
    }

    @Test fun upstoxIsTheOnlyLiveProvider() {
        val catalog = PortfolioProviders.catalog(UpstoxHoldingsSource({ null }) { emptyList() })
        assertEquals(listOf("upstox", "kite", "groww", "cdsl_nsdl"), catalog.map { it.id })
        assertEquals(listOf(true, false, false, false), catalog.map { it.available })
    }

    @Test fun snapshotSurvivesItsJson() {
        val s = HoldingsSnapshot(
            "upstox",
            listOf(
                Holding("SUZLON", "Suzlon Energy", "INE040H01021", "NSE_EQ|INE040H01021", HoldingType.STOCK, 1000, 62.4, 54.1, 56.95, "Power"),
                Holding("NIFTYBEES", "Nippon Nifty BeES", "INF204KB14I2", "NSE_EQ|INF204KB14I2", HoldingType.ETF, 400, 248.6, 284.35, null)
            ),
            5L
        )
        assertEquals(s, HoldingsSnapshot.fromJson(s.toJson()))
        assertNull(HoldingsSnapshot.fromJson("garbage"))
    }
}
```

Append to `PrivacyBoundaryTest`:

```kotlin
    // Holdings go from Upstox straight to the phone; nothing on the portfolio path may reach the Marksy backend.
    @Test
    fun portfolioNeverTalksToTheMarksyBackend() {
        val forbidden = Regex("MarksyContainer|MarketApiClient|MarketIntelligenceRepository|com\\.marksy\\.os\\.gateway|MarksyTipsApiClient")
        val java = File(main, "java/com/marksy/os")
        assertTrue(File(java, "portfolio").isDirectory)
        val files = File(java, "portfolio").walkTopDown().filter { it.extension == "kt" }.toList() +
            listOf("upstox/UpstoxOAuth.kt", "upstox/UpstoxOAuthStore.kt", "upstox/UpstoxHoldings.kt", "ui/PortfolioScreen.kt", "ui/UpstoxSignInActivity.kt").map { File(java, it) }.filter { it.exists() }
        assertEquals(emptyList<String>(), files.filter { forbidden.containsMatchIn(it.readText()) }.map { it.name })
    }
```

- [ ] **Step 2: Run the tests and confirm they fail**

Run: `./gradlew --no-daemon :app:testDebugUnitTest --tests "com.marksy.os.upstox.UpstoxHoldingsTest" --tests "com.marksy.os.portfolio.*" --tests com.marksy.os.PrivacyBoundaryTest`
Expected: compilation FAIL (`UpstoxHolding`, `HoldingsSnapshot` and the rest are unresolved).

- [ ] **Step 3: Implement**

`UpstoxHoldings.kt`:

```kotlin
package com.marksy.os.upstox

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException

/** One `GET /v2/portfolio/long-term-holdings` entry as Upstox sends it. */
data class UpstoxHolding(
    val isin: String,
    val companyName: String,
    val tradingSymbol: String,
    val instrumentToken: String,
    val exchange: String,
    val quantity: Long,
    val averagePrice: Double,
    val lastPrice: Double,
    val closePrice: Double?
)

object UpstoxHoldings {
    fun parse(body: String): List<UpstoxHolding> = try {
        val root = JSONObject(body)
        if (root.optString("status") != "success") {
            val message = root.optJSONArray("errors")?.optJSONObject(0)?.optString("message")?.takeIf { it.isNotBlank() }
            throw IOException("Upstox: ${message ?: "holdings request failed"}")
        }
        val data = root.optJSONArray("data") ?: JSONArray()
        (0 until data.length()).mapNotNull { i -> data.optJSONObject(i)?.let(::entry) }
    } catch (e: JSONException) {
        throw IOException("Upstox returned unreadable holdings", e)
    }

    private fun entry(o: JSONObject): UpstoxHolding? {
        val symbol = o.text("trading_symbol") ?: o.text("tradingsymbol") ?: return null
        val key = o.text("instrument_token") ?: return null
        return UpstoxHolding(
            isin = o.text("isin").orEmpty(),
            companyName = o.text("company_name") ?: symbol,
            tradingSymbol = symbol,
            instrumentToken = key,
            exchange = o.text("exchange") ?: key.substringBefore('_'),
            quantity = o.optLong("quantity"),
            averagePrice = o.optDouble("average_price", 0.0),
            lastPrice = o.optDouble("last_price", 0.0),
            closePrice = o.num("close_price")?.takeIf { it > 0 }
        )
    }

    private fun JSONObject.text(name: String): String? = if (isNull(name)) null else optString(name).takeIf { it.isNotBlank() }
    private fun JSONObject.num(name: String): Double? = if (has(name) && !isNull(name)) optDouble(name).takeIf { !it.isNaN() } else null
}
```

`UpstoxApiClient.kt`: change the class doc and add the read-only call:

```kotlin
/** Read-only Upstox client: market data, plus holdings when given an OAuth token. Never add order, modify or GTT endpoints. */
class UpstoxApiClient(private val token: () -> String?) {
    // …existing members…

    /** Delivery holdings; needs the user's OAuth access token (an Analytics Token would need a static IP). */
    suspend fun longTermHoldings(): List<UpstoxHolding> = withContext(Dispatchers.IO) {
        UpstoxHoldings.parse(get("$V2_URL/portfolio/long-term-holdings"))
    }
```

`UpstoxRestStats.family`: add `"portfolio/" in path -> "holdings"` before `else`.

`Portfolio.kt`:

```kotlin
package com.marksy.os.portfolio

import org.json.JSONArray
import org.json.JSONObject

enum class HoldingType(val label: String) { STOCK("Stocks"), ETF("ETFs") }

data class Holding(
    val symbol: String,
    val name: String,
    val isin: String,
    val instrumentKey: String,
    val type: HoldingType,
    val quantity: Long,
    val averagePrice: Double,
    val lastPrice: Double,
    val previousClose: Double?,
    val sector: String? = null
)

data class HoldingsSnapshot(val providerId: String, val holdings: List<Holding>, val fetchedAt: Long) {
    fun toJson(): String = JSONObject()
        .put("provider", providerId)
        .put("fetchedAt", fetchedAt)
        .put("holdings", JSONArray().apply {
            holdings.forEach { h ->
                put(
                    JSONObject().put("symbol", h.symbol).put("name", h.name).put("isin", h.isin).put("key", h.instrumentKey)
                        .put("type", h.type.name).put("qty", h.quantity).put("avg", h.averagePrice).put("ltp", h.lastPrice)
                        .put("prev", h.previousClose ?: JSONObject.NULL).put("sector", h.sector ?: JSONObject.NULL)
                )
            }
        })
        .toString()

    companion object {
        fun fromJson(json: String): HoldingsSnapshot? = runCatching {
            val o = JSONObject(json)
            val a = o.getJSONArray("holdings")
            HoldingsSnapshot(
                o.getString("provider"),
                (0 until a.length()).map { i ->
                    val h = a.getJSONObject(i)
                    Holding(
                        h.getString("symbol"), h.getString("name"), h.optString("isin"), h.getString("key"),
                        HoldingType.valueOf(h.getString("type")), h.getLong("qty"), h.getDouble("avg"), h.getDouble("ltp"),
                        if (h.isNull("prev")) null else h.getDouble("prev"), if (h.isNull("sector")) null else h.getString("sector")
                    )
                },
                o.getLong("fetchedAt")
            )
        }.getOrNull()
    }
}

sealed interface HoldingsResult {
    data class Ok(val snapshot: HoldingsSnapshot) : HoldingsResult
    data class SignedOut(val reason: String) : HoldingsResult
    data class Failed(val message: String) : HoldingsResult
}

/** Where holdings come from; one per broker or depository. */
fun interface HoldingsSource {
    suspend fun fetch(now: Long): HoldingsResult
}

/** A connectable holdings provider; no [source] means it is listed as coming soon. */
data class PortfolioProvider(val id: String, val name: String, val note: String, val source: HoldingsSource? = null) {
    val available: Boolean get() = source != null
}

object PortfolioProviders {
    const val UPSTOX = "upstox"

    fun catalog(upstox: HoldingsSource): List<PortfolioProvider> = listOf(
        PortfolioProvider(UPSTOX, "Upstox", "Daily sign-in, read-only", upstox),
        PortfolioProvider("kite", "Kite", "Coming soon"),
        PortfolioProvider("groww", "Groww", "Coming soon"),
        PortfolioProvider("cdsl_nsdl", "CDSL / NSDL", "Coming soon")
    )
}
```

`UpstoxHoldingsSource.kt`:

```kotlin
package com.marksy.os.portfolio

import com.marksy.os.upstox.UpstoxAuthException
import com.marksy.os.upstox.UpstoxHolding
import java.io.IOException

/** Upstox holdings fetched with today's OAuth token; [load] is the api.upstox.com call. */
class UpstoxHoldingsSource(
    private val token: () -> String?,
    private val load: suspend (token: String) -> List<UpstoxHolding>
) : HoldingsSource {
    override suspend fun fetch(now: Long): HoldingsResult {
        val bearer = token() ?: return HoldingsResult.SignedOut("Upstox ends every sign-in at 3:30 am")
        return try {
            HoldingsResult.Ok(HoldingsSnapshot(PortfolioProviders.UPSTOX, load(bearer).mapNotNull(::toHolding), now))
        } catch (e: UpstoxAuthException) {
            HoldingsResult.SignedOut("Upstox ended the sign-in")
        } catch (e: IOException) {
            HoldingsResult.Failed(e.message ?: "Couldn't reach Upstox")
        }
    }

    companion object {
        private val ETF_WORD = Regex("\\bETF\\b", RegexOption.IGNORE_CASE)

        fun toHolding(u: UpstoxHolding): Holding? {
            if (u.quantity <= 0) return null
            val price = u.lastPrice.takeIf { it > 0 } ?: u.closePrice ?: u.averagePrice
            val type = if (isEtf(u.tradingSymbol, u.companyName)) HoldingType.ETF else HoldingType.STOCK
            return Holding(u.tradingSymbol.uppercase(), u.companyName, u.isin, u.instrumentToken, type, u.quantity, u.averagePrice, price, u.closePrice)
        }

        fun isEtf(symbol: String, name: String): Boolean =
            symbol.uppercase().let { it.endsWith("BEES") || it.endsWith("ETF") } || ETF_WORD.containsMatchIn(name)
    }
}
```

- [ ] **Step 4: Run the tests and confirm they pass**

Run the same command. Expected: PASS. `PrivacyBoundaryTest` is green now that `portfolio/` exists.

- [ ] **Step 5: Commit**

`git add …the files above… && git commit -m "feat(portfolio): holdings parser, provider interface and Upstox mapping"`

### Task 4: Portfolio maths

**Files:**
- Create: `app/src/main/java/com/marksy/os/portfolio/PortfolioMath.kt`
- Modify: `app/src/main/java/com/marksy/os/upstox/UpstoxMarketData.kt`: extract `UpstoxCandles.bases`; `returns` calls it.
- Test: `app/src/test/java/com/marksy/os/portfolio/PortfolioMathTest.kt`

**Interfaces:**
- Consumes: `Holding`, `HoldingType`, and `Candle(time, open, high, low, close, volume)`.
- Produces:
  - `PortfolioPeriod(label, title, days)`: `D1`, `W1`, `M1`, `M3` and `Y1`;
  - `PortfolioMetric`: `PERIOD` and `TOTAL`;
  - `HoldingSort(label)`: `MOVE`, `BEST`, `WORST`, `VALUE` and `NAME`;
  - `AllocationBy(label)`: `SECTOR`, `HOLDING` and `TYPE`;
  - `LivePrice(last, previousClose)`;
  - `HoldingRow(holding, price, value, invested, weightPct, dayPnl?, dayPct?, periodPnl?, periodPct?, totalPnl, totalPct?)` with `pnl(metric)` and `pct(metric)`;
  - `PortfolioTotals(value, invested, periodPnl, periodPct?, totalPnl, totalPct?)`;
  - `AllocationSlice(name, pct)`;
  - `PortfolioMath`: `rows`, `totals`, `order`, `arrange`, `allocation`, `valueSeries` and `window`;
  - `UpstoxCandles.bases(daily): Map<String, Double>`.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.marksy.os.portfolio

import com.marksy.os.upstox.Candle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PortfolioMathTest {
    private fun h(symbol: String, qty: Long, avg: Double, ltp: Double, prev: Double?, sector: String? = null, type: HoldingType = HoldingType.STOCK) =
        Holding(symbol, symbol, "", "NSE_EQ|$symbol", type, qty, avg, ltp, prev, sector)

    private val suzlon = h("SUZLON", 1000, 62.40, 54.10, 56.95, "Power")
    private val infy = h("INFY", 40, 1512.40, 1836.00, 1858.20, "IT")

    @Test fun livePriceOverridesTheSnapshotAndTodayUsesPreviousClose() {
        val r = PortfolioMath.rows(listOf(suzlon), mapOf("NSE_EQ|SUZLON" to LivePrice(55.0, 57.0)), PortfolioPeriod.D1, emptyMap()).single()
        assertEquals(55.0, r.price, 1e-9)
        assertEquals(55_000.0, r.value, 1e-6)
        assertEquals(62_400.0, r.invested, 1e-6)
        assertEquals(-2_000.0, r.dayPnl!!, 1e-6)
        assertEquals(-3.5088, r.dayPct!!, 1e-3)
        assertEquals(-2_000.0, r.periodPnl!!, 1e-6)
        assertEquals(-7_400.0, r.totalPnl, 1e-6)
        assertEquals(-11.859, r.totalPct!!, 1e-3)
    }

    @Test fun periodPnlUsesTheBaseAndLeavesOutHoldingsWithoutOne() {
        val rows = PortfolioMath.rows(listOf(suzlon, infy), emptyMap(), PortfolioPeriod.M1, mapOf("SUZLON" to 61.13))
        val s = rows.first { it.holding.symbol == "SUZLON" }
        assertEquals(1000 * (54.10 - 61.13), s.periodPnl!!, 1e-6)
        assertNull(rows.first { it.holding.symbol == "INFY" }.periodPnl)
        val t = PortfolioMath.totals(rows)
        assertEquals(s.periodPnl!!, t.periodPnl, 1e-6)
        assertEquals(s.periodPnl!! / (s.value - s.periodPnl!!) * 100, t.periodPct!!, 1e-9)
        assertEquals(54_100.0 + 73_440.0, t.value, 1e-6)
        assertEquals(62_400.0 + 60_496.0, t.invested, 1e-6)
    }

    @Test fun noPreviousCloseMeansNoMoveToday() {
        val r = PortfolioMath.rows(listOf(h("NEW", 5, 100.0, 110.0, null)), emptyMap(), PortfolioPeriod.D1, emptyMap()).single()
        assertNull(r.dayPnl)
        assertNull(r.periodPnl)
        assertNull(PortfolioMath.totals(listOf(r)).periodPct)
    }

    @Test fun weightsShareThePortfolio() {
        val rows = PortfolioMath.rows(listOf(suzlon, infy), emptyMap(), PortfolioPeriod.D1, emptyMap())
        assertEquals(100.0, rows.sumOf { it.weightPct }, 1e-9)
        assertEquals(54_100.0 / 127_540.0 * 100, rows.first().weightPct, 1e-9)
    }

    @Test fun rowsRankByRupeeMoveNotPercent() {
        // SUZLON's 5.0% fall costs ₹2,850; INFY's 1.2% fall costs ₹888.
        val rows = PortfolioMath.rows(listOf(infy, suzlon), emptyMap(), PortfolioPeriod.D1, emptyMap())
        assertEquals(listOf("SUZLON", "INFY"), PortfolioMath.order(rows, PortfolioMetric.PERIOD, HoldingSort.MOVE))
        assertEquals(listOf("INFY", "SUZLON"), PortfolioMath.order(rows, PortfolioMetric.TOTAL, HoldingSort.BEST))
        assertEquals(listOf("SUZLON", "INFY"), PortfolioMath.order(rows, PortfolioMetric.TOTAL, HoldingSort.WORST))
        assertEquals(listOf("INFY", "SUZLON"), PortfolioMath.order(rows, PortfolioMetric.PERIOD, HoldingSort.NAME))
    }

    @Test fun settledOrderIgnoresLaterTicksAndAppendsNewHoldings() {
        val ticked = PortfolioMath.rows(
            listOf(suzlon, infy, h("BEL", 250, 248.3, 402.15, 395.4)),
            mapOf("NSE_EQ|INFY" to LivePrice(1700.0, 1858.2)), PortfolioPeriod.D1, emptyMap()
        )
        assertEquals(listOf("SUZLON", "INFY", "BEL"), PortfolioMath.arrange(ticked, listOf("SUZLON", "INFY")).map { it.holding.symbol })
    }

    @Test fun allocationGroupsBySectorHoldingAndType() {
        val etf = h("NIFTYBEES", 400, 248.6, 284.35, 283.1, type = HoldingType.ETF)
        val rows = PortfolioMath.rows(listOf(suzlon, infy, etf), emptyMap(), PortfolioPeriod.D1, emptyMap())
        val bySector = PortfolioMath.allocation(rows, AllocationBy.SECTOR)
        assertEquals(listOf("Other", "IT", "Power"), bySector.map { it.name })
        assertEquals(100.0, bySector.sumOf { it.pct }, 1e-9)
        assertEquals(listOf("Stocks", "ETFs"), PortfolioMath.allocation(rows, AllocationBy.TYPE).map { it.name })
        assertEquals(listOf("NIFTYBEES", "INFY", "SUZLON"), PortfolioMath.allocation(rows, AllocationBy.HOLDING).map { it.name })
    }

    @Test fun valueSeriesCarriesEachHoldingsLastCloseForward() {
        fun c(t: Long, close: Double) = Candle(t, close, close, close, close, 0)
        val a = h("A", 10, 1.0, 5.0, null)
        val b = h("B", 2, 1.0, 9.0, null)
        val none = h("C", 1, 1.0, 7.0, null)
        val series = PortfolioMath.valueSeries(listOf(a, b, none), mapOf("A" to listOf(c(1, 4.0), c(3, 6.0)), "B" to listOf(c(2, 10.0))))
        assertEquals(listOf(1L to 67.0, 2L to 67.0, 3L to 87.0), series)
    }

    @Test fun windowKeepsThePeriodsDailyCandles() {
        val day = 86_400_000L
        val daily = (0..400).map { Candle(it * day, 1.0, 1.0, 1.0, 1.0, 0) }
        assertEquals(8, PortfolioMath.window(daily, PortfolioPeriod.W1).size)
        assertEquals(366, PortfolioMath.window(daily, PortfolioPeriod.Y1).size)
    }
}
```

- [ ] **Step 2: Run the test and confirm it fails.** Run `--tests com.marksy.os.portfolio.PortfolioMathTest`. Expected: compilation FAIL.
- [ ] **Step 3: Implement**

In `UpstoxMarketData.kt`, replace `returns` with:

```kotlin
    /** Close on or before each period's start; periods the data doesn't reach are left out. */
    fun bases(daily: List<Candle>): Map<String, Double> {
        val end = daily.lastOrNull()?.time ?: return emptyMap()
        return PERIODS.mapNotNull { (label, days) ->
            val start = end - days * DAY_MS
            // A fetched year can begin a few days after the exact mark (weekends, holidays); the first candle stands in.
            val base = (daily.lastOrNull { it.time <= start } ?: daily.first().takeIf { it.time - start <= PERIOD_SLACK_MS })
                ?.close?.takeIf { it > 0 } ?: return@mapNotNull null
            label to base
        }.toMap()
    }

    /** % change to [lastPrice] from each period's base. */
    fun returns(daily: List<Candle>, lastPrice: Double): Map<String, Double> =
        bases(daily).mapValues { (_, base) -> (lastPrice - base) / base * 100 }
```

`PortfolioMath.kt`:

```kotlin
package com.marksy.os.portfolio

import com.marksy.os.upstox.Candle
import kotlin.math.abs

enum class PortfolioPeriod(val label: String, val title: String, val days: Long) {
    D1("1D", "Today", 1), W1("1W", "1 week", 7), M1("1M", "1 month", 30), M3("3M", "3 months", 91), Y1("1Y", "1 year", 365)
}

enum class PortfolioMetric { PERIOD, TOTAL }

enum class HoldingSort(val label: String) {
    MOVE("Biggest ₹ move"), BEST("Best %"), WORST("Worst %"), VALUE("Largest holding"), NAME("Name A–Z")
}

enum class AllocationBy(val label: String) { SECTOR("Sector"), HOLDING("Holding"), TYPE("Type") }

data class LivePrice(val last: Double, val previousClose: Double?)

data class HoldingRow(
    val holding: Holding,
    val price: Double,
    val value: Double,
    val invested: Double,
    val weightPct: Double,
    val dayPnl: Double?,
    val dayPct: Double?,
    val periodPnl: Double?,
    val periodPct: Double?,
    val totalPnl: Double,
    val totalPct: Double?
) {
    fun pnl(metric: PortfolioMetric): Double? = if (metric == PortfolioMetric.TOTAL) totalPnl else periodPnl
    fun pct(metric: PortfolioMetric): Double? = if (metric == PortfolioMetric.TOTAL) totalPct else periodPct
}

data class PortfolioTotals(val value: Double, val invested: Double, val periodPnl: Double, val periodPct: Double?, val totalPnl: Double, val totalPct: Double?)

data class AllocationSlice(val name: String, val pct: Double)

object PortfolioMath {
    private const val DAY_MS = 86_400_000L

    /** [live] is keyed by instrument key; [bases] (period start price by symbol) is ignored for 1D, which uses the previous close. */
    fun rows(holdings: List<Holding>, live: Map<String, LivePrice>, period: PortfolioPeriod, bases: Map<String, Double>): List<HoldingRow> {
        val priced = holdings.map { h ->
            val l = live[h.instrumentKey]
            Triple(h, l?.last?.takeIf { it > 0 } ?: h.lastPrice, (l?.previousClose ?: h.previousClose)?.takeIf { it > 0 })
        }
        val total = priced.sumOf { (h, price, _) -> h.quantity * price }
        return priced.map { (h, price, prev) ->
            val qty = h.quantity.toDouble()
            val value = qty * price
            val invested = qty * h.averagePrice
            val base = (if (period == PortfolioPeriod.D1) prev else bases[h.symbol])?.takeIf { it > 0 }
            HoldingRow(
                holding = h, price = price, value = value, invested = invested,
                weightPct = if (total > 0) value / total * 100 else 0.0,
                dayPnl = prev?.let { qty * (price - it) }, dayPct = prev?.let { (price / it - 1) * 100 },
                periodPnl = base?.let { qty * (price - it) }, periodPct = base?.let { (price / it - 1) * 100 },
                totalPnl = value - invested, totalPct = if (h.averagePrice > 0) (price / h.averagePrice - 1) * 100 else null
            )
        }
    }

    fun totals(rows: List<HoldingRow>): PortfolioTotals {
        val value = rows.sumOf { it.value }
        val invested = rows.sumOf { it.invested }
        val based = rows.filter { it.periodPnl != null }
        val pnl = based.sumOf { it.periodPnl ?: 0.0 }
        val start = based.sumOf { it.value } - pnl
        return PortfolioTotals(
            value, invested, pnl, if (based.isNotEmpty() && start > 0) pnl / start * 100 else null,
            value - invested, if (invested > 0) (value - invested) / invested * 100 else null
        )
    }

    /** Symbols in display order; the page re-ranks only on load, refresh, or a metric, period or sort change. */
    fun order(rows: List<HoldingRow>, metric: PortfolioMetric, sort: HoldingSort): List<String> = when (sort) {
        HoldingSort.MOVE -> rows.sortedByDescending { abs(it.pnl(metric) ?: 0.0) }
        HoldingSort.BEST -> rows.sortedByDescending { it.pct(metric) ?: Double.NEGATIVE_INFINITY }
        HoldingSort.WORST -> rows.sortedBy { it.pct(metric) ?: Double.POSITIVE_INFINITY }
        HoldingSort.VALUE -> rows.sortedByDescending { it.value }
        HoldingSort.NAME -> rows.sortedBy { it.holding.symbol }
    }.map { it.holding.symbol }

    /** [rows] in a settled [order]; holdings that arrived since go last, largest first. */
    fun arrange(rows: List<HoldingRow>, order: List<String>): List<HoldingRow> {
        val index = order.withIndex().associate { (i, s) -> s to i }
        return rows.sortedWith(compareBy<HoldingRow> { index[it.holding.symbol] ?: Int.MAX_VALUE }.thenByDescending { it.value })
    }

    fun allocation(rows: List<HoldingRow>, by: AllocationBy): List<AllocationSlice> = rows.groupBy {
        when (by) {
            AllocationBy.SECTOR -> it.holding.sector ?: "Other"
            AllocationBy.HOLDING -> it.holding.symbol
            AllocationBy.TYPE -> it.holding.type.label
        }
    }.map { (name, group) -> AllocationSlice(name, group.sumOf { it.weightPct }) }.sortedByDescending { it.pct }

    /** Value at each candle time: Σ qty × close, each holding carrying its last close forward (its first close before it starts). */
    fun valueSeries(holdings: List<Holding>, candles: Map<String, List<Candle>>): List<Pair<Long, Double>> {
        val times = candles.values.flatMap { c -> c.map { it.time } }.distinct().sorted()
        return times.map { t ->
            t to holdings.sumOf { h ->
                val c = candles[h.symbol].orEmpty()
                h.quantity * (c.lastOrNull { it.time <= t }?.close ?: c.firstOrNull()?.close ?: h.lastPrice)
            }
        }
    }

    /** The daily candles inside [period], ending at the latest one. */
    fun window(daily: List<Candle>, period: PortfolioPeriod): List<Candle> {
        val end = daily.lastOrNull()?.time ?: return emptyList()
        return daily.filter { it.time >= end - period.days * DAY_MS }
    }
}
```

- [ ] **Step 4: Run** `--tests com.marksy.os.portfolio.PortfolioMathTest --tests com.marksy.os.upstox.UpstoxMarketDataTest`. Expected: PASS.
- [ ] **Step 5: Commit**

`git commit -m "feat(portfolio): valuation, period P&L, ranking, allocation and value series"`

### Task 5: Needs-a-look flags

**Files:**
- Create: `app/src/main/java/com/marksy/os/portfolio/PortfolioFlags.kt`
- Test: `app/src/test/java/com/marksy/os/portfolio/PortfolioFlagsTest.kt`

**Interfaces:**
- Consumes: `HoldingRow`, and `com.marksy.os.alerts.PriceAlert(id, symbol, price, above, createdAt)`.
- Produces:
  - `FlagKind`: `DAY_MOVE`, `BELOW_AVERAGE`, `NEAR_ALERT` and `CONCENTRATION`;
  - `FlagSeverity`: `CRITICAL`, `WARNING` and `POSITIVE`, in that rank order;
  - `HoldingFlag(kind, severity, reason)`;
  - `NeedsLook(row, flags)` with `primary`;
  - `PortfolioFlags`: `flags(row, alerts)`, `nearestAlert(row, alerts)`, `needsALook(rows, alerts, hidden)`, `hiddenUntil(now)` and the threshold constants.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.marksy.os.portfolio

import com.marksy.os.alerts.PriceAlert
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class PortfolioFlagsTest {
    private fun row(symbol: String = "SUZLON", price: Double = 100.0, avg: Double = 100.0, prev: Double? = 100.0, weight: Double = 5.0, dayPnl: Double? = null) = HoldingRow(
        Holding(symbol, symbol, "", "NSE_EQ|$symbol", HoldingType.STOCK, 10, avg, price, prev), price, price * 10, avg * 10, weight,
        dayPnl ?: prev?.let { 10 * (price - it) }, prev?.let { (price / it - 1) * 100 }, null, null, (price - avg) * 10, (price / avg - 1) * 100
    )
    private fun alert(price: Double, symbol: String = "SUZLON") = PriceAlert(price.toLong(), symbol, price, above = price > 100, createdAt = 0)
    private fun kinds(r: HoldingRow, alerts: List<PriceAlert> = emptyList()) = PortfolioFlags.flags(r, alerts).map { it.kind }

    @Test fun quietHoldingHasNoFlags() {
        assertEquals(emptyList<FlagKind>(), kinds(row(price = 103.9, prev = 100.0, avg = 115.0)))
    }

    @Test fun aDayMoveOfFourPercentEitherWayIsFlagged() {
        val fell = PortfolioFlags.flags(row(price = 95.9, prev = 100.0, avg = 95.9), emptyList()).single()
        assertEquals(FlagKind.DAY_MOVE to FlagSeverity.CRITICAL, fell.kind to fell.severity)
        assertEquals("Fell 4.1% today", fell.reason)
        val rose = PortfolioFlags.flags(row(price = 104.1, prev = 100.0, avg = 104.1), emptyList()).single()
        assertEquals(FlagSeverity.POSITIVE, rose.severity)
        assertEquals("Rose 4.1% today", rose.reason)
        assertEquals(emptyList<FlagKind>(), kinds(row(price = 96.1, prev = 100.0, avg = 96.1)))
    }

    @Test fun fifteenPercentBelowAverageIsFlagged() {
        assertEquals("15.1% below your average", PortfolioFlags.flags(row(price = 84.9, prev = 84.9, avg = 100.0), emptyList()).single().reason)
        assertEquals(listOf(FlagKind.BELOW_AVERAGE), kinds(row(price = 85.0, prev = 85.0, avg = 100.0)))
        assertEquals(emptyList<FlagKind>(), kinds(row(price = 85.1, prev = 85.1, avg = 100.0)))
    }

    @Test fun withinFourPercentOfAnAlertEitherSideIsFlagged() {
        assertEquals(listOf(FlagKind.NEAR_ALERT), kinds(row(), listOf(alert(96.1))))
        assertEquals(listOf(FlagKind.NEAR_ALERT), kinds(row(), listOf(alert(103.9))))
        assertEquals(emptyList<FlagKind>(), kinds(row(), listOf(alert(95.9), alert(103.9, symbol = "INFY"))))
        assertEquals("2.0% from your ₹98.00 alert", PortfolioFlags.flags(row(), listOf(alert(96.5), alert(98.0))).single().reason)
    }

    @Test fun twentyPercentOfThePortfolioIsFlagged() {
        assertEquals("20% of your portfolio", PortfolioFlags.flags(row(weight = 20.0), emptyList()).single().reason)
        assertEquals(emptyList<FlagKind>(), kinds(row(weight = 19.9)))
    }

    @Test fun lanePutsCriticalFirstThenTheBiggestRupeeMoveAndSkipsHidden() {
        val conc = row("HDFCBANK", weight = 22.0, dayPnl = 2_196.0)
        val fell = row("SUZLON", price = 95.0, prev = 100.0, avg = 95.0, dayPnl = -2_850.0)
        val below = row("TATAMOTORS", price = 84.0, prev = 85.0, avg = 100.0, dayPnl = -2_070.0)
        val quiet = row("INFY")
        assertEquals(listOf("SUZLON", "TATAMOTORS", "HDFCBANK"), PortfolioFlags.needsALook(listOf(conc, quiet, below, fell), emptyList(), emptySet()).map { it.row.holding.symbol })
        assertEquals(listOf("TATAMOTORS", "HDFCBANK"), PortfolioFlags.needsALook(listOf(conc, below, fell), emptyList(), setOf("SUZLON")).map { it.row.holding.symbol })
    }

    @Test fun primaryReasonIsTheMostSevere() {
        val r = row(price = 104.5, prev = 100.0, avg = 104.5)
        assertEquals(FlagKind.NEAR_ALERT, PortfolioFlags.needsALook(listOf(r), listOf(alert(106.0)), emptySet()).single().primary.kind)
    }

    @Test fun hideTodayLastsUntilTheNextOpen() {
        fun ist(at: String) = LocalDateTime.parse(at).atZone(ZoneId.of("Asia/Kolkata")).toInstant().toEpochMilli()
        assertEquals(ist("2026-10-03T09:15"), PortfolioFlags.hiddenUntil(ist("2026-10-02T11:00")))
        assertEquals(ist("2026-10-02T09:15"), PortfolioFlags.hiddenUntil(ist("2026-10-02T08:00")))
    }
}
```

- [ ] **Step 2: Run** `--tests com.marksy.os.portfolio.PortfolioFlagsTest`. Expected: compilation FAIL.
- [ ] **Step 3: Implement**

```kotlin
package com.marksy.os.portfolio

import com.marksy.os.alerts.PriceAlert
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

enum class FlagKind { DAY_MOVE, BELOW_AVERAGE, NEAR_ALERT, CONCENTRATION }

/** Declared most severe first; the order ranks reasons and cards. */
enum class FlagSeverity { CRITICAL, WARNING, POSITIVE }

data class HoldingFlag(val kind: FlagKind, val severity: FlagSeverity, val reason: String)

data class NeedsLook(val row: HoldingRow, val flags: List<HoldingFlag>) {
    val primary: HoldingFlag get() = flags.minBy { it.severity.ordinal }
}

/** "Needs a look" rules, fixed in v1 by the user's 2026-10-02 decision. */
object PortfolioFlags {
    const val BELOW_AVERAGE_PCT = 15.0
    const val ALERT_DISTANCE_PCT = 4.0
    const val CONCENTRATION_PCT = 20.0
    const val DAY_MOVE_PCT = 4.0
    private val IST: ZoneId = ZoneId.of("Asia/Kolkata")
    private val OPEN: LocalTime = LocalTime.of(9, 15)

    fun flags(row: HoldingRow, alerts: List<PriceAlert>): List<HoldingFlag> = buildList {
        row.dayPct?.takeIf { abs(it) >= DAY_MOVE_PCT }?.let { d ->
            add(HoldingFlag(FlagKind.DAY_MOVE, if (d < 0) FlagSeverity.CRITICAL else FlagSeverity.POSITIVE, "${if (d < 0) "Fell" else "Rose"} ${one(abs(d))}% today"))
        }
        row.totalPct?.takeIf { it <= -BELOW_AVERAGE_PCT }?.let { add(HoldingFlag(FlagKind.BELOW_AVERAGE, FlagSeverity.CRITICAL, "${one(-it)}% below your average")) }
        nearestAlert(row, alerts)?.let { (alert, gap) ->
            add(HoldingFlag(FlagKind.NEAR_ALERT, FlagSeverity.WARNING, "${one(gap)}% from your ₹${com.marksy.os.ui.money(alert.price)} alert"))
        }
        if (row.weightPct >= CONCENTRATION_PCT) add(HoldingFlag(FlagKind.CONCENTRATION, FlagSeverity.WARNING, "${row.weightPct.roundToInt()}% of your portfolio"))
    }

    /** The user's closest alert on this holding, when within [ALERT_DISTANCE_PCT] of the price either way. */
    fun nearestAlert(row: HoldingRow, alerts: List<PriceAlert>): Pair<PriceAlert, Double>? {
        if (row.price <= 0) return null
        return alerts.filter { it.symbol.equals(row.holding.symbol, ignoreCase = true) }
            .map { it to abs(row.price - it.price) / row.price * 100 }
            .filter { it.second <= ALERT_DISTANCE_PCT }
            .minByOrNull { it.second }
    }

    /** Flagged holdings not hidden today: most severe first, then by today's rupee move. */
    fun needsALook(rows: List<HoldingRow>, alerts: List<PriceAlert>, hidden: Set<String>): List<NeedsLook> =
        rows.filter { it.holding.symbol !in hidden }
            .mapNotNull { r -> flags(r, alerts).takeIf { it.isNotEmpty() }?.let { NeedsLook(r, it) } }
            .sortedWith(compareBy<NeedsLook> { it.primary.severity.ordinal }.thenByDescending { abs(it.row.dayPnl ?: 0.0) })

    /** "Hide today" lasts until the next 09:15 IST open. */
    fun hiddenUntil(now: Long): Long {
        val t = Instant.ofEpochMilli(now).atZone(IST)
        val open = t.toLocalDate().atTime(OPEN).atZone(IST)
        return (if (open.isAfter(t)) open else open.plusDays(1)).toInstant().toEpochMilli()
    }

    private fun one(v: Double) = String.format(Locale.US, "%.1f", v)
}
```

- [ ] **Step 4: Run** the same command. Expected: PASS (8 tests).
- [ ] **Step 5: Commit**

`git commit -m "feat(portfolio): Needs-a-look rules with fixed v1 thresholds"`

### Task 6: Repository and WebView sign-in

There are no unit tests here: this is Android glue. The guard is the privacy test from Task 3, plus a compile check.

**Files:**
- Create: `app/src/main/java/com/marksy/os/portfolio/PortfolioRepository.kt` and `app/src/main/java/com/marksy/os/ui/UpstoxSignInActivity.kt`
- Modify: `app/src/main/AndroidManifest.xml`

**Interfaces:**
- Produces:
  - `PortfolioConnection`: `NOT_CONNECTED`, `SIGNED_IN` and `SIGNED_OUT`;
  - `PortfolioHistory(daily: Map<String, List<Candle>>, nifty: List<Candle>)`;
  - `PortfolioRepository(context)`:
    - `store` and `providers`;
    - `cached()`, `connection(now)` and `suspend refresh(now)`;
    - `suspend history(holdings, today)` and `suspend intraday(holdings, today)`;
    - `hidden(now)`, `hide(symbol, now)` and `unhideAll()`;
    - `disconnect()`.
  - `UpstoxSignInActivity`, which returns `RESULT_OK` once a token is saved.

- [ ] **Step 1: Write `PortfolioRepository.kt`**

```kotlin
package com.marksy.os.portfolio

import android.content.Context
import com.marksy.os.upstox.Candle
import com.marksy.os.upstox.ChartRange
import com.marksy.os.upstox.UpstoxApiClient
import com.marksy.os.upstox.UpstoxCandles
import com.marksy.os.upstox.UpstoxFundamentals
import com.marksy.os.upstox.UpstoxIndices
import com.marksy.os.upstox.UpstoxOAuthStore
import com.marksy.os.upstox.UpstoxTokenStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONObject
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap

enum class PortfolioConnection { NOT_CONNECTED, SIGNED_IN, SIGNED_OUT }

/** Daily candles per holding symbol plus NIFTY 50, for period bases and the chart. */
data class PortfolioHistory(val daily: Map<String, List<Candle>>, val nifty: List<Candle>)

/** Holdings from Upstox straight to this phone (never via Marksy), cached encrypted for the signed-out morning. */
class PortfolioRepository(context: Context) {
    private val app = context.applicationContext
    val store = UpstoxOAuthStore(app)
    private val analytics = UpstoxTokenStore(app)
    private val upstox = UpstoxHoldingsSource({ store.accessToken() }) { token -> UpstoxApiClient { token }.longTermHoldings() }
    val providers: List<PortfolioProvider> = PortfolioProviders.catalog(upstox)

    fun cached(): HoldingsSnapshot? = store.holdings()?.let(HoldingsSnapshot::fromJson)

    fun connection(now: Long = System.currentTimeMillis()): PortfolioConnection = when {
        store.accessToken(now) != null -> PortfolioConnection.SIGNED_IN
        store.holdings() != null -> PortfolioConnection.SIGNED_OUT
        else -> PortfolioConnection.NOT_CONNECTED
    }

    /** A rejected or expired token signs out but keeps the app keys and the cached holdings. */
    suspend fun refresh(now: Long = System.currentTimeMillis()): HoldingsResult = when (val r = upstox.fetch(now)) {
        is HoldingsResult.Ok -> HoldingsResult.Ok(withSectors(r.snapshot)).also { store.saveHoldings(it.snapshot.toJson()) }
        is HoldingsResult.SignedOut -> r.also { store.clearToken() }
        is HoldingsResult.Failed -> r
    }

    // Candles and sector profiles are market data: the Analytics Token when saved, else today's sign-in.
    private fun market() = UpstoxApiClient { analytics.getToken() ?: store.accessToken() }

    private suspend fun withSectors(snapshot: HoldingsSnapshot): HoldingsSnapshot {
        val known = cached()?.holdings.orEmpty().mapNotNull { h -> h.sector?.let { h.isin to it } }.toMap()
        val gate = Semaphore(4)
        val holdings = coroutineScope {
            snapshot.holdings.map { h ->
                async {
                    val sector = when {
                        h.type == HoldingType.ETF -> "ETFs"
                        known[h.isin] != null -> known[h.isin]
                        h.isin.isBlank() -> null
                        else -> gate.withPermit { quietly { UpstoxFundamentals.profile(market().fundamentals(h.isin, "profile")).sector } }
                    }
                    h.copy(sector = sector)
                }
            }.awaitAll()
        }
        return snapshot.copy(holdings = holdings)
    }

    suspend fun history(holdings: List<Holding>, today: LocalDate): PortfolioHistory = coroutineScope {
        val gate = Semaphore(4)
        val from = today.minusDays(372)
        val nifty = async { gate.withPermit { daily(UpstoxIndices.NIFTY_50, from, today) } }
        val daily = holdings.map { h -> async { h.symbol to gate.withPermit { daily(h.instrumentKey, from, today) } } }.awaitAll().toMap()
        PortfolioHistory(daily, nifty.await())
    }

    suspend fun intraday(holdings: List<Holding>, today: LocalDate): Map<String, List<Candle>> = coroutineScope {
        val gate = Semaphore(4)
        holdings.map { h -> async { h.symbol to gate.withPermit { quietly { market().candles(h.instrumentKey, ChartRange.D1, today, IST) }.orEmpty() } } }
            .awaitAll().toMap()
    }

    private suspend fun daily(key: String, from: LocalDate, today: LocalDate): List<Candle> {
        val cacheKey = "$today|$key"
        DAILY[cacheKey]?.let { return it }
        return quietly { market().dailyCandles(key, from, today) }?.also { if (it.isNotEmpty()) DAILY[cacheKey] = it }.orEmpty()
    }

    fun hidden(now: Long): Set<String> = hiddenMap().filterValues { it > now }.keys

    fun hide(symbol: String, now: Long) {
        val map = hiddenMap().filterValues { it > now } + (symbol to PortfolioFlags.hiddenUntil(now))
        store.saveHidden(JSONObject(map as Map<*, *>).toString())
    }

    fun unhideAll() = store.saveHidden("")

    /** Forgets the keys, token, holdings and hidden cards, plus Upstox's login cookies in the sign-in WebView. */
    fun disconnect() {
        store.disconnect()
        DAILY.clear()
        runCatching {
            android.webkit.CookieManager.getInstance().removeAllCookies(null)
            android.webkit.WebStorage.getInstance().deleteAllData()
        }
    }

    private fun hiddenMap(): Map<String, Long> = runCatching {
        val o = JSONObject(store.hidden() ?: return emptyMap())
        o.keys().asSequence().associateWith { o.getLong(it) }
    }.getOrDefault(emptyMap())

    private suspend fun <T> quietly(block: suspend () -> T?): T? = try { block() } catch (e: CancellationException) { throw e } catch (_: Exception) { null }

    companion object {
        val IST: ZoneId = ZoneId.of("Asia/Kolkata")
        // One fetch of a year's daily candles per instrument per day, shared by every Portfolio visit.
        private val DAILY = ConcurrentHashMap<String, List<Candle>>()

        fun bases(history: PortfolioHistory?, period: PortfolioPeriod): Map<String, Double> =
            history?.daily.orEmpty().mapNotNull { (symbol, candles) -> UpstoxCandles.bases(candles)[period.label]?.let { symbol to it } }.toMap()
    }
}
```

- [ ] **Step 2: Write `UpstoxSignInActivity.kt`**

```kotlin
package com.marksy.os.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import com.marksy.os.upstox.UpstoxOAuth
import com.marksy.os.upstox.UpstoxOAuthStore
import kotlinx.coroutines.launch
import java.io.IOException

/** Upstox's own login page in a WebView; the redirect is caught here, so no server is involved. Read-only. */
class UpstoxSignInActivity : ComponentActivity() {
    private lateinit var store: UpstoxOAuthStore
    private lateinit var credentials: UpstoxOAuth.Credentials
    private lateinit var state: String
    private var web: WebView? = null
    private var handled = false
    private var busy by mutableStateOf(false)
    private var problem by mutableStateOf<String?>(null)

    @SuppressLint("SetJavaScriptEnabled")
    @OptIn(ExperimentalLayoutApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = UpstoxOAuthStore(this)
        credentials = store.credentials() ?: run { finish(); return }
        state = savedInstanceState?.getString(KEY_STATE) ?: UpstoxOAuth.newState()
        val view = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.setGeolocationEnabled(false)
            settings.setSupportMultipleWindows(false)
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = route(request.url.toString(), request.isForMainFrame)
                override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                    if (url != null && UpstoxOAuth.isRedirect(url, credentials.redirectUri)) { view.stopLoading(); route(url, mainFrame = true) }
                }
            }
        }
        web = view
        view.loadUrl(UpstoxOAuth.authorizeUrl(credentials, state))
        onBackPressedDispatcher.addCallback(this) { if (problem == null && view.canGoBack()) view.goBack() else finish() }
        setContent {
            MarksyMaterialTheme {
                Box(Modifier.fillMaxSize().background(MarksyTheme.Background).systemBarsPadding()) {
                    AndroidView(factory = { view }, modifier = Modifier.fillMaxSize())
                    if (busy) Box(Modifier.fillMaxSize().background(MarksyTheme.Background.copy(alpha = .9f)), contentAlignment = Alignment.Center) {
                        MarksyLoader("Finishing the Upstox sign-in…")
                    }
                    problem?.let { message ->
                        val shape = RoundedCornerShape(16.dp)
                        Column(
                            Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(16.dp)
                                .background(MarksyTheme.Surface, shape).border(1.dp, MarksyTheme.YellowImportant, shape).padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text(message, color = MarksyTheme.TextPrimary, fontSize = 13.sp)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Pill("Try again") { retry() }
                                Pill("Close") { finish() }
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_STATE, state)
    }

    override fun onDestroy() {
        web?.destroy()
        web = null
        super.onDestroy()
    }

    /** True when the WebView must not load [url] itself. */
    private fun route(url: String, mainFrame: Boolean): Boolean {
        if (UpstoxOAuth.isRedirect(url, credentials.redirectUri)) {
            if (!handled) { handled = true; complete(url) }
            return true
        }
        if (mainFrame && !UpstoxOAuth.isUpstoxPage(url)) {
            problem = "Upstox sent the sign-in to ${Uri.parse(url).host ?: "another site"}, which Marksy doesn't open."
            return true
        }
        return false
    }

    private fun complete(url: String) {
        when (val r = UpstoxOAuth.parseRedirect(url, state)) {
            is UpstoxOAuth.Redirect.Failed -> problem = r.message
            is UpstoxOAuth.Redirect.Code -> {
                busy = true
                lifecycleScope.launch {
                    try {
                        store.saveToken(UpstoxOAuth.exchange(r.code, credentials), System.currentTimeMillis())
                        setResult(RESULT_OK)
                        finish()
                    } catch (e: IOException) {
                        problem = e.message ?: "Couldn't finish the Upstox sign-in"
                    } finally {
                        busy = false
                    }
                }
            }
        }
    }

    private fun retry() {
        problem = null
        handled = false
        state = UpstoxOAuth.newState()
        web?.loadUrl(UpstoxOAuth.authorizeUrl(credentials, state))
    }

    private companion object { const val KEY_STATE = "upstox_state" }
}
```

- [ ] **Step 3: Register the activity.** Add it to `AndroidManifest.xml` after `WhatsAppSettingsActivity`:

```xml
        <!-- Upstox holdings sign-in: OAuth in a WebView that catches the redirect itself. -->
        <activity
            android:name=".ui.UpstoxSignInActivity"
            android:exported="false"
            android:configChanges="orientation|screenSize|screenLayout|smallestScreenSize|keyboardHidden"
            android:windowSoftInputMode="adjustResize" />
```

- [ ] **Step 4: Compile and run the guard.** Run `./gradlew --no-daemon :app:compileDebugKotlin` and `--tests com.marksy.os.PrivacyBoundaryTest`. Expected: BUILD SUCCESSFUL and PASS.
- [ ] **Step 5: Commit**

`git commit -m "feat(portfolio): repository and in-app Upstox sign-in"`

### Task 7: Portfolio page, Market wiring and Settings

**Files:**
- Create: `app/src/main/java/com/marksy/os/ui/PortfolioScreen.kt`
- Modify: `app/src/main/java/com/marksy/os/ui/MarketScreen.kt`: the PORTFOLIO branch and the OneHandControls search and sort.
- Modify: `app/src/main/java/com/marksy/os/MainActivity.kt`: `titleNote` for PORTFOLIO.
- Modify: `app/src/main/java/com/marksy/os/ui/UpstoxScreen.kt`: the "Holdings sign-in" section and `UpstoxAppKeysDialog`.

**Interfaces:**
- Consumes everything from Tasks 3–6, plus:
  - `Pill(text, selected, enabled, compact, onClick)`, `MarksyDialog`, `Sparkline(values, color, modifier)` and `MarksyRefreshBox` / `rememberRefreshState`;
  - `money(Double)`, `count(Long)` and `compactTime(Long)`;
  - `rememberFeedFreshness()`, `PriceAlertDialog(symbol, lastPrice, onDismiss)` and `PriceAlertStore.alerts(context)`;
  - `UpstoxFeed.acquire/release/quotes`.
- Produces:
  - `PortfolioScreen(padding, query, sortOpen, onSortDismiss, onOpenStock)`;
  - `object PortfolioChrome { note: MutableStateFlow<String?>; hasHoldings: MutableStateFlow<Boolean> }`;
  - `UpstoxAppKeysDialog(store, onDismiss, onSaved)`.

- [ ] **Step 1: Write `PortfolioScreen.kt`.** Build it to §8 of the spec, using these pieces:
  - `PortfolioHero`: value, invested, two P&L boxes, Sparkline, the NIFTY line, period Pills, the caveat and the allocation bar;
  - `NeedsCard`, `WhyDialog`, `HoldingsStack` (rows with expand), `Lane` and the mutual funds placeholder;
  - `ConnectCard` (coming-soon providers as disabled Pills), `SignedOutCard`, `PortfolioSortDialog` and the hidden footer.

  Ranking settles in `LaunchedEffect(settleKey, metric, period, sort, bases)` from `rememberUpdatedState(rows)`. `settleKey` bumps after each load. The full source is in the commit; it uses only the interfaces listed above.
- [ ] **Step 2: Wire Market.**
  - The `PORTFOLIO` branch calls `PortfolioScreen(inner, portfolioQuery, portfolioSort, { portfolioSort = false }) { onOpenStock?.invoke(it) }`.
  - `OneHandControls` gets `searchQuery` and `onSearchChange` and a `FloatingAction(Icons.AutoMirrored.Filled.Sort, "Sort and show")`, but only while `tab == PORTFOLIO && PortfolioChrome.hasHoldings`.
  - In MainActivity, `titleNote` adds `selectedTab == 2 && marketTabName == MarketTab.PORTFOLIO.name -> portfolioNote`, from `PortfolioChrome.note.collectAsStateWithLifecycle()`.
- [ ] **Step 3: Add Settings › Upstox › Holdings sign-in.** It shows the status and has Change app keys (`UpstoxAppKeysDialog`) and Disconnect. Disconnect sits behind a `MarksyDialog` confirmation and calls `PortfolioRepository.disconnect()`.
- [ ] **Step 4: Compile and run the full unit suite**

`./gradlew --no-daemon :app:compileDebugKotlin :app:testDebugUnitTest`. Expected: every test passes (659 before this branch, plus the new ones).
- [ ] **Step 5: Commit**

`git commit -m "feat(portfolio): triage-first Portfolio page, Market wiring and Upstox holdings settings"`

### Task 8: Visual check, roadmap status, review and PR

- [ ] **Step 1: Render offscreen.** Write a scratch Robolectric test, kept out of git:
  - `@GraphicsMode(NATIVE)`, with `@Config(qualifiers = "w400dp-h2400dp-xxhdpi")` and `w360dp`;
  - render `PortfolioScreen` content states from a fixture snapshot (connected with Needs a look, signed out, not connected);
  - capture with `onRoot().captureToImage()` and save the PNGs to `<scratchpad>/portfolio-render/`.

  Delete the scratch test afterwards.
- [ ] **Step 2: Check the render against the mockup.** Compare with `portfolio-design.html`: the first 100dp is the hero, there are no heading rows, and the Pills are Marksy Pills.
- [ ] **Step 3: Add the roadmap status.** Add one "Done" line to `docs/ROADMAP-NEXT.md` for Portfolio v1 (Upstox OAuth, read-only).
- [ ] **Step 4: Review.** Run one whole-branch review with superpowers:requesting-code-review, with a security focus on credential storage. Fix the findings and re-review once.
- [ ] **Step 5: Rebase and merge.**
  - Rebase on origin/main and resolve the MainActivity conflicts.
  - Rerun the suite, push and open the PR.
  - Merge once the checks are green, then remove the worktree.
