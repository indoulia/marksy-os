# Upstox Settings and QR Import Implementation Plan (PR 1 of 3)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** One Settings › Upstox form for the live account (read-only) and the sandbox token, filled by typing or by importing a QR code (camera or photo picker), plus an offline QR generator and setup doc.

**Architecture:** Pure logic lives in `com.marksy.os.upstox`: a closed-schema QR parser, a JWT expiry reader, a sandbox status, a form model (`UpstoxDraft`) and a saver that writes only changed fields to the existing Keystore-encrypted stores. The Compose screen in `ui/UpstoxScreen.kt` only wires these together. QR images are decoded with zxing, which is already a dependency.

**Tech Stack:** Kotlin 2.3.21, Jetpack Compose (BOM 2026.08.00), zxing-android-embedded 4.3.0 (+ zxing core), `android.util.JsonReader`, OkHttp `HttpUrl`, JUnit 4 + Robolectric 4.17, Python 3.9+ with `segno` for the generator.

**Spec:** `docs/superpowers/specs/2026-10-18-upstox-qr-orders-design.md` (§3 is this PR; §4 and §5 are PRs 2 and 3, planned after their mockups).

## Global Constraints

- minSdk 26, compileSdk/targetSdk 37. No new app dependencies.
- `ThemeGuardTest` is strict: outside the theme files, no `Color(0x…)`, `.sp`, dp literals, `TextStyle(`, `fontFamily =`/`FontFamily.`, stock Material controls (`Button(`, `Card(`, `AlertDialog(`, `ModalBottomSheet(`…), `Toast`, date patterns or number formats. Use `MarksyTheme`, `MarksyType`, `MarksySpace`, `MarksyCard`, `MarksyButton`, `MarksyDialog`, `MarksyBottomSheet`, `CompactTextField`, `Pill`, `OneHandControls`/`FloatingAction`, `MarksyFormat`.
- Code comments: one line at most, only for a non-obvious why.
- Never log, toast, copy or put in an exception message: API secret, Analytics Token, sandbox token, API key, access token. Data classes that hold them mask `toString`.
- The daily access token is never accepted from a QR.
- The QR only fills the form. Nothing is stored until Save & test.
- Fields a QR leaves out keep their saved values.
- QR v1 is a closed schema: `type` = `"marksy-upstox"`, `v` = `1`, optional `api_key`, `api_secret`, `redirect_uri`, `analytics_token`, `sandbox_token`. Max 4096 bytes.
- Redirect URL: `https://` any host, or `http://` only to `127.0.0.1`, `localhost`, `[::1]`; no user info, no fragment.
- Sandbox token expiry: its JWT `exp` when readable and no more than 400 days ahead, else saved-at + 30 days. Expired = no sandbox token (practice off, never live).
- Disconnect wipes app keys, today's sign-in, saved holdings, the sandbox token and Upstox cookies; the Analytics Token stays.
- Test runs on this machine need the PATH fix first (malformed user PATH breaks forked test JVMs):
  `export PATH="$(echo "$PATH" | tr ':' '\n' | grep -vE '"|%' | paste -sd:)"` then `./gradlew --no-daemon …`. Never run `gradlew --stop` while another agent builds.
- Commits end with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

## Review Focus

1. A QR whose text has a UTF-8 byte-order mark or a trailing newline (some generators add them) must still import. Test: Task 1 `surroundingWhitespaceAndAByteOrderMarkAreFine`.
2. Scanning the wrong QR (the old gateway provisioning code `{"integrationKey":…}`, a URL, another app's JSON) must say "This isn't a Marksy Upstox code." and change nothing. Test: Task 1 `otherCodesAreNotOurs`.
3. Importing a second QR after typing must replace only the fields that QR carries and keep the typed ones. Test: Task 3 `aSecondImportReplacesOnlyTheFieldsItCarries`.
4. Saving while offline must still save the Analytics Token; a token Upstox rejects must not be saved, but the other fields must be. Tests: Task 3 `anUnreachableCheckStillSavesTheAnalyticsToken`, `aRejectedAnalyticsTokenIsNotSavedButTheRestIs`.
5. Changing the API key while signed in drops today's sign-in, and the user must be told to sign in again. Test: Task 3 `aNewKeyDropsTodaysSignInAndSaysSo`.

---

### Task 1: QR format v1 parser

**Files:**
- Create: `app/src/main/java/com/marksy/os/upstox/UpstoxQrImport.kt`
- Test: `app/src/test/java/com/marksy/os/upstox/UpstoxQrImportTest.kt`

**Interfaces:**
- Produces:
  - `enum class UpstoxField(val key: String, val label: String, val maxChars: Int)` with `API_KEY, API_SECRET, REDIRECT, ANALYTICS_TOKEN, SANDBOX_TOKEN`; `UpstoxField.of(key: String): UpstoxField?`
  - `sealed class UpstoxQrImport` with `data class Ok(val fields: Map<UpstoxField, String>)` and `data class Rejected(val message: String)`
  - `UpstoxQrImport.parse(scanned: String): UpstoxQrImport`
  - `UpstoxQrImport.fieldProblem(field: UpstoxField, value: String, name: String = field.label): String?`
  - `UpstoxQrImport.sameTokenProblem(analytics: String?, sandbox: String?): String?`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.marksy.os.upstox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UpstoxQrImportTest {
    private val full = """{"type":"marksy-upstox","v":1,"api_key":"key-123","api_secret":"secret-xyz","redirect_uri":"http://127.0.0.1/marksy-upstox","analytics_token":"aaa.bbb.ccc","sandbox_token":"ddd.eee.fff"}"""

    private fun ok(text: String) = UpstoxQrImport.parse(text) as UpstoxQrImport.Ok
    private fun refused(text: String) = (UpstoxQrImport.parse(text) as UpstoxQrImport.Rejected).message
    private fun with(body: String) = """{"type":"marksy-upstox","v":1,$body}"""

    @Test
    fun aFullCodeFillsEveryField() {
        assertEquals(
            mapOf(
                UpstoxField.API_KEY to "key-123", UpstoxField.API_SECRET to "secret-xyz",
                UpstoxField.REDIRECT to "http://127.0.0.1/marksy-upstox",
                UpstoxField.ANALYTICS_TOKEN to "aaa.bbb.ccc", UpstoxField.SANDBOX_TOKEN to "ddd.eee.fff"
            ),
            ok(full).fields
        )
    }

    @Test
    fun aPartialCodeFillsOnlyWhatItCarriesTrimmed() {
        assertEquals(mapOf(UpstoxField.SANDBOX_TOKEN to "ddd.eee.fff"), ok(with(""""sandbox_token":" ddd.eee.fff """")).fields)
    }

    @Test
    fun surroundingWhitespaceAndAByteOrderMarkAreFine() {
        assertEquals(5, ok("\uFEFF\n$full\n").fields.size)
    }

    @Test
    fun otherCodesAreNotOurs() {
        listOf(
            """{"integrationKey":"abc","baseUrl":"https://example.com"}""",
            "https://example.com/login",
            "[1,2]",
            """{"type":"something-else","v":1,"api_key":"k"}""",
            """{"v":1,"api_key":"k"}"""
        ).forEach { assertEquals(it, "This isn't a Marksy Upstox code.", refused(it)) }
    }

    @Test
    fun brokenJsonIsRefused() {
        listOf("""{"type":"marksy-upstox",""", """{"type":"marksy-upstox","v":1,"api_key":"k"} {}""", """{'type':'marksy-upstox'}""")
            .forEach { assertEquals(it, "This QR isn't valid JSON.", refused(it)) }
    }

    @Test
    fun aRepeatedFieldIsRefused() {
        assertEquals("\"api_key\" appears twice.", refused(with(""""api_key":"a","api_key":"b"""")))
    }

    @Test
    fun onlyVersionOneIsAccepted() {
        assertEquals("This code was made for a newer Marksy (version 2). Update the app.", refused("""{"type":"marksy-upstox","v":2,"api_key":"k"}"""))
        assertEquals("This code's version isn't supported.", refused("""{"type":"marksy-upstox","v":0,"api_key":"k"}"""))
        listOf("\"1\"", "1.0", "-1", "true").forEach { v ->
            assertEquals(v, "This code has no valid version.", refused("""{"type":"marksy-upstox","v":$v,"api_key":"k"}"""))
        }
        assertEquals("This code has no valid version.", refused("""{"type":"marksy-upstox","api_key":"k"}"""))
    }

    @Test
    fun unknownFieldsAndAccessTokensAreRefused() {
        assertEquals("It has a field Marksy doesn't accept: \"order_url\".", refused(with(""""order_url":"https://api-hft.upstox.com"""")))
        assertEquals("It contains an access token. Marksy only takes that from the Upstox sign-in.", refused(with(""""access_token":"t"""")))
    }

    @Test
    fun valuesMustBeNonEmptyPrintableText() {
        assertEquals("\"sandbox_token\" must be text.", refused(with(""""sandbox_token":123""")))
        assertEquals("\"api_secret\" is empty. Leave a field out to keep the saved value.", refused(with(""""api_secret":"  """")))
        assertEquals("\"api_key\" has spaces or characters Marksy can't use.", refused(with(""""api_key":"a b"""")))
        assertEquals("\"api_key\" is too long.", refused(with(""""api_key":"${"k".repeat(129)}"""")))
    }

    @Test
    fun redirectsMustBeHttpsOrThisPhone() {
        listOf("http://127.0.0.1/marksy-upstox", "http://localhost:8080/cb", "http://[::1]/cb", "https://example.com/cb")
            .forEach { assertTrue(it, UpstoxQrImport.parse(with(""""redirect_uri":"$it"""")) is UpstoxQrImport.Ok) }
        listOf("http://example.com/cb", "ftp://127.0.0.1/cb", "marksy://cb", "javascript:alert(1)")
            .forEach { assertEquals(it, "The redirect URL must be https, or http to 127.0.0.1 on this phone.", refused(with(""""redirect_uri":"$it""""))) }
        listOf("https://user:pw@example.com/cb", "https://example.com/cb#x")
            .forEach { assertEquals(it, "The redirect URL can't have a user name or a #fragment.", refused(with(""""redirect_uri":"$it""""))) }
    }

    @Test
    fun theSameTokenTwiceAndEmptyCodesAreRefused() {
        assertEquals("The same token is in both token fields.", refused(with(""""analytics_token":"t.t.t","sandbox_token":"t.t.t"""")))
        assertEquals("This code has nothing to import.", refused("""{"type":"marksy-upstox","v":1}"""))
    }

    @Test
    fun oversizedCodesAreRefused() {
        assertEquals("The code is over 4 KB.", refused(with(""""api_key":"${"k".repeat(4100)}"""")))
    }

    @Test
    fun noResultRevealsAValue() {
        assertFalse(ok(full).toString().contains("secret-xyz"))
        assertFalse(refused(with(""""api_secret":"hunter 2"""")).contains("hunter"))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew --no-daemon :app:testDebugUnitTest --tests 'com.marksy.os.upstox.UpstoxQrImportTest'` (after the PATH fix)
Expected: compilation FAILS with `Unresolved reference: UpstoxQrImport`.

- [ ] **Step 3: Write the implementation**

```kotlin
package com.marksy.os.upstox

import android.util.JsonReader
import android.util.JsonToken
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.IOException
import java.io.StringReader

/** A setting the Upstox QR code or the Settings form can fill. */
enum class UpstoxField(val key: String, val label: String, val maxChars: Int) {
    API_KEY("api_key", "API key", 128),
    API_SECRET("api_secret", "API secret", 128),
    REDIRECT("redirect_uri", "Redirect URL", 512),
    ANALYTICS_TOKEN("analytics_token", "Analytics Token", 2048),
    SANDBOX_TOKEN("sandbox_token", "Sandbox token", 2048);

    companion object {
        fun of(key: String): UpstoxField? = entries.firstOrNull { it.key == key }
    }
}

/** A scanned Marksy Upstox QR code (format v1, closed schema). It only fills the form; it never saves. */
sealed class UpstoxQrImport {
    data class Ok(val fields: Map<UpstoxField, String>) : UpstoxQrImport() {
        override fun toString() = "Ok(${fields.keys.joinToString { it.key }})"
    }

    data class Rejected(val message: String) : UpstoxQrImport()

    companion object {
        const val TYPE = "marksy-upstox"
        const val VERSION = 1
        const val MAX_BYTES = 4096
        private const val NOT_OURS = "This isn't a Marksy Upstox code."
        private val PRINTABLE = Regex("[\\x21-\\x7E]+")
        private val LOOPBACK = setOf("127.0.0.1", "localhost", "::1")

        fun parse(scanned: String): UpstoxQrImport {
            if (scanned.toByteArray(Charsets.UTF_8).size > MAX_BYTES) return Rejected("The code is over 4 KB.")
            val text = scanned.removePrefix("\uFEFF").trim()
            if (!text.startsWith("{")) return Rejected(NOT_OURS)
            val entries = try {
                read(text)
            } catch (e: Repeated) {
                return Rejected("${quote(e.key)} appears twice.")
            } catch (e: Exception) {
                return Rejected("This QR isn't valid JSON.")
            }
            if (entries["type"] != TYPE) return Rejected(NOT_OURS)
            versionProblem(entries["v"])?.let { return Rejected(it) }
            val fields = LinkedHashMap<UpstoxField, String>()
            for ((key, value) in entries) {
                if (key == "type" || key == "v") continue
                if (key == "access_token") return Rejected("It contains an access token. Marksy only takes that from the Upstox sign-in.")
                val field = UpstoxField.of(key) ?: return Rejected("It has a field Marksy doesn't accept: ${quote(key)}.")
                if (value !is String) return Rejected("${quote(key)} must be text.")
                val trimmed = value.trim()
                if (trimmed.isEmpty()) return Rejected("${quote(key)} is empty. Leave a field out to keep the saved value.")
                fieldProblem(field, trimmed, quote(key))?.let { return Rejected(it) }
                fields[field] = trimmed
            }
            if (fields.isEmpty()) return Rejected("This code has nothing to import.")
            sameTokenProblem(fields[UpstoxField.ANALYTICS_TOKEN], fields[UpstoxField.SANDBOX_TOKEN])?.let { return Rejected(it) }
            return Ok(fields)
        }

        /** Why [value] can't fill [field], or null; [name] is how the message names the field. */
        fun fieldProblem(field: UpstoxField, value: String, name: String = field.label): String? = when {
            value.isEmpty() -> "$name is empty."
            !PRINTABLE.matches(value) -> "$name has spaces or characters Marksy can't use."
            value.length > field.maxChars -> "$name is too long."
            field == UpstoxField.REDIRECT -> redirectProblem(value)
            else -> null
        }

        fun sameTokenProblem(analytics: String?, sandbox: String?): String? =
            if (analytics != null && analytics == sandbox) "The same token is in both token fields." else null

        private fun redirectProblem(value: String): String? {
            val url = value.toHttpUrlOrNull()
            if (url == null || !(url.isHttps || url.host in LOOPBACK)) return "The redirect URL must be https, or http to 127.0.0.1 on this phone."
            if (url.username.isNotEmpty() || url.password.isNotEmpty() || url.fragment != null) return "The redirect URL can't have a user name or a #fragment."
            return null
        }

        private fun versionProblem(v: Any?): String? {
            val n = (v as? Num)?.text?.takeIf { t -> t.isNotEmpty() && t.all(Char::isDigit) }?.toIntOrNull()
                ?: return "This code has no valid version."
            return when {
                n > VERSION -> "This code was made for a newer Marksy (version $n). Update the app."
                n < VERSION -> "This code's version isn't supported."
                else -> null
            }
        }

        private fun quote(key: String) = "\"${key.take(40)}\""

        // Streaming read: the phone's org.json silently keeps the last of two repeated keys.
        private fun read(text: String): Map<String, Any> {
            val reader = JsonReader(StringReader(text))
            val out = LinkedHashMap<String, Any>()
            reader.beginObject()
            while (reader.hasNext()) {
                val name = reader.nextName()
                if (name in out) throw Repeated(name)
                out[name] = when (reader.peek()) {
                    JsonToken.STRING -> reader.nextString()
                    JsonToken.NUMBER -> Num(reader.nextString())
                    else -> Other.also { reader.skipValue() }
                }
            }
            reader.endObject()
            if (reader.peek() != JsonToken.END_DOCUMENT) throw IOException("trailing content")
            return out
        }

        private class Repeated(val key: String) : Exception()
        private class Num(val text: String)
        private object Other
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew --no-daemon :app:testDebugUnitTest --tests 'com.marksy.os.upstox.UpstoxQrImportTest'`
Expected: PASS (13 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/marksy/os/upstox/UpstoxQrImport.kt app/src/test/java/com/marksy/os/upstox/UpstoxQrImportTest.kt
git commit -F - <<'EOF'
feat(upstox): strict QR format v1 parser for Upstox settings

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
```

---

### Task 2: Token expiry and the sandbox token slot

**Files:**
- Create: `app/src/main/java/com/marksy/os/upstox/UpstoxJwt.kt`
- Create: `app/src/main/java/com/marksy/os/upstox/UpstoxSandboxStatus.kt`
- Modify: `app/src/main/java/com/marksy/os/upstox/UpstoxOAuthStore.kt` (class KDoc at lines 13-16; new methods after `clearToken()`; constants in the companion)
- Test: `app/src/test/java/com/marksy/os/upstox/UpstoxJwtTest.kt`
- Test: `app/src/test/java/com/marksy/os/upstox/UpstoxOAuthStoreTest.kt` (append tests)

**Interfaces:**
- Produces:
  - `UpstoxJwt.expiresAt(token: String): Long?` (epoch ms), `UpstoxJwt.sandboxExpiry(token: String, now: Long): Long`, `UpstoxJwt.SANDBOX_VALIDITY_MS`
  - `UpstoxSandboxStatus(state: State, expiresAt: Long?, daysLeft: Int)`, `enum State { NONE, VALID, EXPIRING, EXPIRED }`, `UpstoxSandboxStatus.of(expiresAt: Long?, now: Long)`, `WARN_DAYS = 3` (PR 2's ticket reuses this)
  - `UpstoxOAuthStore.saveSandboxToken(token: String, now: Long)`, `sandboxToken(now: Long = System.currentTimeMillis()): String?`, `sandboxExpiresAt(): Long?`, `clearSandboxToken()`

- [ ] **Step 1: Write the failing tests**

`UpstoxJwtTest.kt`:

```kotlin
package com.marksy.os.upstox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Base64

class UpstoxJwtTest {
    private val now = 1_790_000_000_000L
    private val day = 24L * 60 * 60 * 1000

    private fun jwt(payload: String): String {
        val enc = Base64.getUrlEncoder().withoutPadding()
        return enc.encodeToString("""{"alg":"none"}""".toByteArray()) + "." + enc.encodeToString(payload.toByteArray()) + ".sig"
    }

    @Test
    fun readsExpInMilliseconds() {
        assertEquals(1_800_000_000_000L, UpstoxJwt.expiresAt(jwt("""{"exp":1800000000}""")))
    }

    @Test
    fun nonJwtTokensHaveNoExpiry() {
        listOf("opaque-token", "a.!!!.c", jwt("""{"sub":"x"}"""), jwt("not json")).forEach { assertNull(it, UpstoxJwt.expiresAt(it)) }
    }

    @Test
    fun sandboxExpiryUsesExpOnlyWhenPlausible() {
        assertEquals(now + 10 * day, UpstoxJwt.sandboxExpiry(jwt("""{"exp":${(now + 10 * day) / 1000}}"""), now))
        assertEquals(now - day, UpstoxJwt.sandboxExpiry(jwt("""{"exp":${(now - day) / 1000}}"""), now))
        assertEquals(now + 30 * day, UpstoxJwt.sandboxExpiry(jwt("""{"exp":${(now + 500 * day) / 1000}}"""), now))
        assertEquals(now + 30 * day, UpstoxJwt.sandboxExpiry("opaque-token", now))
    }

    @Test
    fun sandboxStatusWarnsInTheLastThreeDays() {
        assertEquals(UpstoxSandboxStatus.State.NONE, UpstoxSandboxStatus.of(null, now).state)
        assertEquals(UpstoxSandboxStatus(UpstoxSandboxStatus.State.VALID, now + 10 * day, 10), UpstoxSandboxStatus.of(now + 10 * day, now))
        assertEquals(UpstoxSandboxStatus.State.VALID, UpstoxSandboxStatus.of(now + 3 * day + 1, now).state)
        assertEquals(UpstoxSandboxStatus(UpstoxSandboxStatus.State.EXPIRING, now + 3 * day, 3), UpstoxSandboxStatus.of(now + 3 * day, now))
        assertEquals(UpstoxSandboxStatus.State.EXPIRED, UpstoxSandboxStatus.of(now, now).state)
    }
}
```

Append to `UpstoxOAuthStoreTest.kt` (inside the class; `issued` already exists):

```kotlin
    private fun jwt(expSeconds: Long): String {
        val enc = java.util.Base64.getUrlEncoder().withoutPadding()
        return enc.encodeToString("""{"alg":"none"}""".toByteArray()) + "." + enc.encodeToString("""{"exp":$expSeconds}""".toByteArray()) + ".sig"
    }

    @Test
    fun sandboxTokenIsEncryptedAndStopsAtItsExpiry() {
        val store = UpstoxOAuthStore(context)
        val exp = issued + 10 * 24 * 60 * 60 * 1000L
        val token = jwt(exp / 1000)
        store.saveSandboxToken(token, issued)
        assertEquals(exp, store.sandboxExpiresAt())
        assertEquals(token, UpstoxOAuthStore(context).sandboxToken(exp - 1))
        assertNull(store.sandboxToken(exp))
        val raw = context.getSharedPreferences("upstox_oauth", Context.MODE_PRIVATE).all.values.joinToString()
        assertFalse(raw.contains(token))
    }

    @Test
    fun aSandboxTokenWithoutExpiryLastsThirtyDays() {
        val store = UpstoxOAuthStore(context)
        store.saveSandboxToken("opaque-sandbox-token", issued)
        assertEquals(issued + UpstoxJwt.SANDBOX_VALIDITY_MS, store.sandboxExpiresAt())
    }

    @Test
    fun anAlreadyExpiredSandboxTokenIsNeverUsable() {
        val store = UpstoxOAuthStore(context)
        store.saveSandboxToken(jwt(issued / 1000 - 60), issued)
        assertNull(store.sandboxToken(issued))
    }

    @Test
    fun clearAndDisconnectForgetTheSandboxToken() {
        val store = UpstoxOAuthStore(context)
        store.saveSandboxToken("opaque-sandbox-token", issued)
        store.clearSandboxToken()
        assertNull(store.sandboxExpiresAt())
        store.saveSandboxToken("opaque-sandbox-token", issued)
        store.disconnect()
        assertNull(store.sandboxToken(issued))
        assertNull(store.sandboxExpiresAt())
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew --no-daemon :app:testDebugUnitTest --tests 'com.marksy.os.upstox.UpstoxJwtTest' --tests 'com.marksy.os.upstox.UpstoxOAuthStoreTest'`
Expected: compilation FAILS with `Unresolved reference: UpstoxJwt` / `saveSandboxToken`.

- [ ] **Step 3: Write the implementation**

`UpstoxJwt.kt`:

```kotlin
package com.marksy.os.upstox

import org.json.JSONObject
import java.util.Base64

/** Token expiry for display and the sandbox cut-off; reads a JWT's `exp` without checking its signature. */
object UpstoxJwt {
    const val SANDBOX_VALIDITY_MS = 30L * 24 * 60 * 60 * 1000
    private const val MAX_AHEAD_MS = 400L * 24 * 60 * 60 * 1000

    fun expiresAt(token: String): Long? = runCatching {
        val parts = token.split('.')
        if (parts.size != 3) return null
        val exp = JSONObject(String(Base64.getUrlDecoder().decode(parts[1]), Charsets.UTF_8)).optLong("exp", 0L)
        if (exp > 0) exp * 1000 else null
    }.getOrNull()

    /** A readable `exp` up to 400 days ahead (past ones included, so an expired token stays expired), else 30 days from [now]. */
    fun sandboxExpiry(token: String, now: Long): Long =
        expiresAt(token)?.takeIf { it <= now + MAX_AHEAD_MS } ?: (now + SANDBOX_VALIDITY_MS)
}
```

`UpstoxSandboxStatus.kt`:

```kotlin
package com.marksy.os.upstox

/** Where the sandbox token stands; expired or missing turns practice orders off, never live ones on. */
data class UpstoxSandboxStatus(val state: State, val expiresAt: Long?, val daysLeft: Int) {
    enum class State { NONE, VALID, EXPIRING, EXPIRED }

    companion object {
        const val WARN_DAYS = 3
        private const val DAY_MS = 24L * 60 * 60 * 1000

        fun of(expiresAt: Long?, now: Long): UpstoxSandboxStatus {
            if (expiresAt == null) return UpstoxSandboxStatus(State.NONE, null, 0)
            if (now >= expiresAt) return UpstoxSandboxStatus(State.EXPIRED, expiresAt, 0)
            val days = ((expiresAt - now + DAY_MS - 1) / DAY_MS).toInt()
            return UpstoxSandboxStatus(if (days <= WARN_DAYS) State.EXPIRING else State.VALID, expiresAt, days)
        }
    }
}
```

`UpstoxOAuthStore.kt`: replace the class KDoc with:

```kotlin
/**
 * The user's Upstox app keys, today's OAuth token, the last holdings and the sandbox token, each slot Keystore-encrypted;
 * all slots share this store's one alias (same pattern as [UpstoxTokenStore]). Only api.upstox.com and the sandbox host receive them.
 */
```

Add after `clearToken()`:

```kotlin
    /** For practice orders only; null once expired. Only the sandbox order client and Settings may read it. */
    fun sandboxToken(now: Long = System.currentTimeMillis()): String? {
        val expires = sandboxExpiresAt() ?: return null
        return if (now < expires) read(SANDBOX_TOKEN) else null
    }

    fun sandboxExpiresAt(): Long? =
        preferences.getLong(SANDBOX_EXPIRES_AT, 0L).takeIf { it != 0L && preferences.contains(SANDBOX_TOKEN) }

    fun saveSandboxToken(token: String, now: Long) {
        val value = token.trim()
        require(value.isNotEmpty()) { "blank sandbox token" }
        write(SANDBOX_TOKEN, value)
        preferences.edit().putLong(SANDBOX_EXPIRES_AT, UpstoxJwt.sandboxExpiry(value, now)).apply()
    }

    fun clearSandboxToken() {
        preferences.edit().remove(SANDBOX_TOKEN).remove(ivName(SANDBOX_TOKEN)).remove(SANDBOX_EXPIRES_AT).apply()
    }
```

Add to the companion constants:

```kotlin
        const val SANDBOX_TOKEN = "sandbox_token"
        const val SANDBOX_EXPIRES_AT = "sandbox_expires_at"
```

`disconnect()` already calls `preferences.edit().clear()`, so it wipes both new keys.

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew --no-daemon :app:testDebugUnitTest --tests 'com.marksy.os.upstox.UpstoxJwtTest' --tests 'com.marksy.os.upstox.UpstoxOAuthStoreTest'`
Expected: PASS (all old `UpstoxOAuthStoreTest` tests plus 4 new; 4 in `UpstoxJwtTest`).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/marksy/os/upstox/UpstoxJwt.kt app/src/main/java/com/marksy/os/upstox/UpstoxSandboxStatus.kt app/src/main/java/com/marksy/os/upstox/UpstoxOAuthStore.kt app/src/test/java/com/marksy/os/upstox/UpstoxJwtTest.kt app/src/test/java/com/marksy/os/upstox/UpstoxOAuthStoreTest.kt
git commit -F - <<'EOF'
feat(upstox): encrypted sandbox token slot with visible expiry

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
```

---

### Task 3: Settings form model and saver

**Files:**
- Create: `app/src/main/java/com/marksy/os/upstox/UpstoxSettings.kt`
- Test: `app/src/test/java/com/marksy/os/upstox/UpstoxSettingsTest.kt`

**Interfaces:**
- Consumes: `UpstoxField`, `UpstoxQrImport.Ok`, `UpstoxQrImport.fieldProblem`, `UpstoxQrImport.sameTokenProblem` (Task 1); `UpstoxOAuthStore.saveSandboxToken`, `sandboxExpiresAt`, `UpstoxJwt.expiresAt` (Task 2); existing `UpstoxOAuthStore.credentials/saveCredentials/accessToken`, `UpstoxTokenStore.save/getToken/hasToken`.
- Produces:
  - `data class UpstoxSaved(apiKey: String, hasSecret: Boolean, redirect: String, hasAnalytics: Boolean, analyticsExpiresAt: Long?, sandboxExpiresAt: Long?)` with `UpstoxSaved.load(oauth: UpstoxOAuthStore, analytics: UpstoxTokenStore)`
  - `data class UpstoxDraft(saved, values, fromQr)` with `text(field)`, `edit(field, text)`, `apply(import: UpstoxQrImport.Ok)`, `discard()`, `changes(): Map<UpstoxField, String>`, `dirty: Boolean`, `problem(): String?`, `UpstoxDraft.CREDENTIALS`
  - `enum class TokenCheck { OK, REJECTED, UNREACHABLE }`
  - `class UpstoxSettingsSaver(oauth, analytics, check: suspend (String) -> TokenCheck)` with `suspend fun save(draft: UpstoxDraft, now: Long): Result` and `data class Result(saved: Set<UpstoxField>, analytics: TokenCheck?, signInDropped: Boolean)`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.marksy.os.upstox

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.marksy.os.gateway.TestAndroidKeyStoreProvider
import kotlinx.coroutines.runBlocking
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
class UpstoxSettingsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val now = 1_790_000_000_000L
    private val oauth get() = UpstoxOAuthStore(context)
    private val analytics get() = UpstoxTokenStore(context)

    companion object {
        @BeforeClass
        @JvmStatic
        fun setupAndroidKeyStore() {
            if (Security.getProvider("AndroidKeyStore") == null) Security.addProvider(TestAndroidKeyStoreProvider())
        }
    }

    @Before
    fun reset() {
        oauth.disconnect()
        analytics.clear()
    }

    private fun draft() = UpstoxDraft(UpstoxSaved.load(oauth, analytics))
    private fun qr(vararg fields: Pair<UpstoxField, String>) = UpstoxQrImport.Ok(fields.toMap())
    private fun saver(check: TokenCheck = TokenCheck.OK) = UpstoxSettingsSaver(oauth, analytics) { check }

    @Test
    fun aFreshFormIsCleanAndNeverLoadsTheSecret() {
        oauth.saveCredentials("key-1", "secret-1", UpstoxOAuth.DEFAULT_REDIRECT)
        val d = draft()
        assertFalse(d.dirty)
        assertEquals("key-1", d.text(UpstoxField.API_KEY))
        assertEquals("", d.text(UpstoxField.API_SECRET))
        assertTrue(d.saved.hasSecret)
    }

    @Test
    fun anImportFillsOnlyItsFieldsAndATypedEditDropsTheQrMark() {
        val d = draft().edit(UpstoxField.ANALYTICS_TOKEN, "typed.a.b")
            .apply(qr(UpstoxField.API_KEY to "key-2", UpstoxField.SANDBOX_TOKEN to "s.s.s"))
        assertEquals(setOf(UpstoxField.API_KEY, UpstoxField.SANDBOX_TOKEN), d.fromQr)
        assertEquals("typed.a.b", d.text(UpstoxField.ANALYTICS_TOKEN))
        assertEquals(setOf(UpstoxField.SANDBOX_TOKEN), d.edit(UpstoxField.API_KEY, "key-3").fromQr)
    }

    @Test
    fun aSecondImportReplacesOnlyTheFieldsItCarries() {
        val d = draft().apply(qr(UpstoxField.API_KEY to "key-2", UpstoxField.SANDBOX_TOKEN to "s.s.s"))
            .edit(UpstoxField.SANDBOX_TOKEN, "typed.s.s")
            .apply(qr(UpstoxField.API_KEY to "key-9"))
        assertEquals("key-9", d.text(UpstoxField.API_KEY))
        assertEquals("typed.s.s", d.text(UpstoxField.SANDBOX_TOKEN))
    }

    @Test
    fun discardReturnsToWhatIsSaved() {
        oauth.saveCredentials("key-1", "secret-1", UpstoxOAuth.DEFAULT_REDIRECT)
        val d = draft().apply(qr(UpstoxField.API_KEY to "key-2")).discard()
        assertFalse(d.dirty)
        assertEquals("key-1", d.text(UpstoxField.API_KEY))
        assertTrue(d.fromQr.isEmpty())
    }

    @Test
    fun problemsBlockSave() {
        assertEquals("Add the API key too.", draft().edit(UpstoxField.API_SECRET, "secret-1").problem())
        assertEquals("Add the API secret too.", draft().edit(UpstoxField.API_KEY, "key-1").problem())
        assertEquals(
            "The redirect URL must be https, or http to 127.0.0.1 on this phone.",
            draft().edit(UpstoxField.API_KEY, "k").edit(UpstoxField.API_SECRET, "s").edit(UpstoxField.REDIRECT, "http://example.com/cb").problem()
        )
        assertEquals("API key has spaces or characters Marksy can't use.", draft().edit(UpstoxField.API_KEY, "a b").problem())
        assertEquals(
            "The same token is in both token fields.",
            draft().edit(UpstoxField.ANALYTICS_TOKEN, "t.t.t").edit(UpstoxField.SANDBOX_TOKEN, "t.t.t").problem()
        )
        assertNull(draft().edit(UpstoxField.API_KEY, "k").edit(UpstoxField.API_SECRET, "s").problem())
    }

    @Test
    fun saveWritesOnlyWhatChanged() {
        runBlocking {
            oauth.saveCredentials("key-1", "secret-1", UpstoxOAuth.DEFAULT_REDIRECT)
            oauth.saveToken("daily", now)
            val result = saver().save(draft().edit(UpstoxField.SANDBOX_TOKEN, "opaque-sandbox"), now)
            assertEquals(setOf(UpstoxField.SANDBOX_TOKEN), result.saved)
            assertEquals("daily", oauth.accessToken(now))
            assertEquals(now + UpstoxJwt.SANDBOX_VALIDITY_MS, oauth.sandboxExpiresAt())
            assertNull(result.analytics)
            assertFalse(result.signInDropped)
        }
    }

    @Test
    fun aRejectedAnalyticsTokenIsNotSavedButTheRestIs() {
        runBlocking {
            val d = draft().apply(qr(UpstoxField.API_KEY to "key-2", UpstoxField.API_SECRET to "secret-2", UpstoxField.ANALYTICS_TOKEN to "bad.a.b"))
            val result = saver(TokenCheck.REJECTED).save(d, now)
            assertNull(analytics.getToken())
            assertEquals("key-2", oauth.credentials()?.apiKey)
            assertEquals(TokenCheck.REJECTED, result.analytics)
            assertFalse(UpstoxField.ANALYTICS_TOKEN in result.saved)
        }
    }

    @Test
    fun anUnreachableCheckStillSavesTheAnalyticsToken() {
        runBlocking {
            val result = saver(TokenCheck.UNREACHABLE).save(draft().edit(UpstoxField.ANALYTICS_TOKEN, "good.a.b"), now)
            assertEquals("good.a.b", analytics.getToken())
            assertEquals(TokenCheck.UNREACHABLE, result.analytics)
        }
    }

    @Test
    fun aBlankSecretKeepsTheSavedOne() {
        runBlocking {
            oauth.saveCredentials("key-1", "secret-1", UpstoxOAuth.DEFAULT_REDIRECT)
            saver().save(draft().edit(UpstoxField.REDIRECT, "https://example.com/cb"), now)
            assertEquals(UpstoxOAuth.Credentials("key-1", "secret-1", "https://example.com/cb"), oauth.credentials())
        }
    }

    @Test
    fun aNewKeyDropsTodaysSignInAndSaysSo() {
        runBlocking {
            oauth.saveCredentials("key-1", "secret-1", UpstoxOAuth.DEFAULT_REDIRECT)
            oauth.saveToken("daily", now)
            val result = saver().save(draft().edit(UpstoxField.API_KEY, "key-2"), now)
            assertTrue(result.signInDropped)
            assertNull(oauth.accessToken(now))
        }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew --no-daemon :app:testDebugUnitTest --tests 'com.marksy.os.upstox.UpstoxSettingsTest'`
Expected: compilation FAILS with `Unresolved reference: UpstoxDraft`.

- [ ] **Step 3: Write the implementation**

```kotlin
package com.marksy.os.upstox

/** What Settings shows as saved; the secret and tokens are never loaded into the form. */
data class UpstoxSaved(
    val apiKey: String = "",
    val hasSecret: Boolean = false,
    val redirect: String = UpstoxOAuth.DEFAULT_REDIRECT,
    val hasAnalytics: Boolean = false,
    val analyticsExpiresAt: Long? = null,
    val sandboxExpiresAt: Long? = null
) {
    override fun toString() = "UpstoxSaved(apiKey=***, hasSecret=$hasSecret, redirect=$redirect, hasAnalytics=$hasAnalytics)"

    companion object {
        fun load(oauth: UpstoxOAuthStore, analytics: UpstoxTokenStore): UpstoxSaved {
            val credentials = oauth.credentials()
            val token = analytics.getToken()
            return UpstoxSaved(
                apiKey = credentials?.apiKey.orEmpty(),
                hasSecret = credentials != null,
                redirect = credentials?.redirectUri ?: UpstoxOAuth.DEFAULT_REDIRECT,
                hasAnalytics = token != null,
                analyticsExpiresAt = token?.let(UpstoxJwt::expiresAt),
                sandboxExpiresAt = oauth.sandboxExpiresAt()
            )
        }
    }
}

/** The Settings form: typed or QR-filled text over [saved]; a blank secret or token means "keep the saved one". */
data class UpstoxDraft(
    val saved: UpstoxSaved,
    val values: Map<UpstoxField, String> = mapOf(UpstoxField.API_KEY to saved.apiKey, UpstoxField.REDIRECT to saved.redirect),
    val fromQr: Set<UpstoxField> = emptySet()
) {
    val dirty: Boolean get() = changes().isNotEmpty()

    fun text(field: UpstoxField): String = values[field].orEmpty()

    fun edit(field: UpstoxField, text: String) = copy(values = values + (field to text), fromQr = fromQr - field)

    fun apply(import: UpstoxQrImport.Ok) = copy(values = values + import.fields, fromQr = fromQr + import.fields.keys)

    fun discard() = UpstoxDraft(saved)

    /** Trimmed fields Save would write. */
    fun changes(): Map<UpstoxField, String> = UpstoxField.entries.mapNotNull { field ->
        val value = text(field).trim()
        val changed = when (field) {
            UpstoxField.API_KEY -> value != saved.apiKey
            UpstoxField.REDIRECT -> value != saved.redirect
            else -> value.isNotEmpty()
        }
        if (changed) field to value else null
    }.toMap()

    /** Why Save can't run yet, or null. */
    fun problem(): String? {
        val changes = changes()
        changes.forEach { (field, value) -> UpstoxQrImport.fieldProblem(field, value)?.let { return it } }
        if (changes.keys.any { it in CREDENTIALS }) {
            if (text(UpstoxField.API_KEY).isBlank()) return "Add the API key too."
            if (UpstoxField.API_SECRET !in changes && !saved.hasSecret) return "Add the API secret too."
        }
        return UpstoxQrImport.sameTokenProblem(changes[UpstoxField.ANALYTICS_TOKEN], changes[UpstoxField.SANDBOX_TOKEN])
    }

    override fun toString() = "UpstoxDraft(changed=${changes().keys.joinToString { it.key }}, fromQr=${fromQr.joinToString { it.key }})"

    companion object {
        val CREDENTIALS = setOf(UpstoxField.API_KEY, UpstoxField.API_SECRET, UpstoxField.REDIRECT)
    }
}

/** How the Analytics Token check against Upstox went. */
enum class TokenCheck { OK, REJECTED, UNREACHABLE }

/** Writes a valid draft's changed fields; an Analytics Token Upstox rejects is the only thing left unsaved. */
class UpstoxSettingsSaver(
    private val oauth: UpstoxOAuthStore,
    private val analytics: UpstoxTokenStore,
    private val check: suspend (String) -> TokenCheck
) {
    data class Result(val saved: Set<UpstoxField>, val analytics: TokenCheck?, val signInDropped: Boolean)

    suspend fun save(draft: UpstoxDraft, now: Long): Result {
        require(draft.problem() == null) { "draft has a problem" }
        val changes = draft.changes()
        val saved = changes.keys.toMutableSet()
        val outcome = changes[UpstoxField.ANALYTICS_TOKEN]?.let { token ->
            check(token).also { if (it == TokenCheck.REJECTED) saved -= UpstoxField.ANALYTICS_TOKEN else analytics.save(token) }
        }
        val signedIn = oauth.accessToken(now) != null
        if (changes.keys.any { it in UpstoxDraft.CREDENTIALS }) {
            val existing = oauth.credentials()
            oauth.saveCredentials(
                changes[UpstoxField.API_KEY] ?: existing?.apiKey.orEmpty(),
                changes[UpstoxField.API_SECRET] ?: existing?.apiSecret.orEmpty(),
                changes[UpstoxField.REDIRECT] ?: existing?.redirectUri ?: UpstoxOAuth.DEFAULT_REDIRECT
            )
        }
        changes[UpstoxField.SANDBOX_TOKEN]?.let { oauth.saveSandboxToken(it, now) }
        return Result(saved, outcome, signedIn && oauth.accessToken(now) == null)
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew --no-daemon :app:testDebugUnitTest --tests 'com.marksy.os.upstox.UpstoxSettingsTest'`
Expected: PASS (10 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/marksy/os/upstox/UpstoxSettings.kt app/src/test/java/com/marksy/os/upstox/UpstoxSettingsTest.kt
git commit -F - <<'EOF'
feat(upstox): settings form model and saver that writes only changed fields

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
```

---

### Task 4: Reading a QR code from an image

**Files:**
- Create: `app/src/main/java/com/marksy/os/upstox/UpstoxQrImage.kt`
- Test: `app/src/test/java/com/marksy/os/upstox/UpstoxQrImageTest.kt`

**Interfaces:**
- Produces: `UpstoxQrImage.decode(width: Int, height: Int, argb: IntArray): String?`, `suspend UpstoxQrImage.fromUri(context: Context, uri: Uri): String?`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.marksy.os.upstox

import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UpstoxQrImageTest {
    private val black = 0xFF000000.toInt()
    private val white = 0xFFFFFFFF.toInt()

    @Test
    fun readsTheCodeInAnImage() {
        val text = """{"type":"marksy-upstox","v":1,"api_key":"fake-key"}"""
        val size = 300
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
        val pixels = IntArray(size * size) { i -> if (matrix[i % size, i / size]) black else white }
        assertEquals(text, UpstoxQrImage.decode(size, size, pixels))
    }

    @Test
    fun aBlankImageHasNoCode() {
        assertNull(UpstoxQrImage.decode(100, 100, IntArray(100 * 100) { white }))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew --no-daemon :app:testDebugUnitTest --tests 'com.marksy.os.upstox.UpstoxQrImageTest'`
Expected: compilation FAILS with `Unresolved reference: UpstoxQrImage`.

- [ ] **Step 3: Write the implementation**

```kotlin
package com.marksy.os.upstox

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Finds a QR code in a picked image; the image is read in memory and never copied or kept. */
object UpstoxQrImage {
    private const val MAX_SIDE = 2048

    fun decode(width: Int, height: Int, argb: IntArray): String? = runCatching {
        val reader = MultiFormatReader().apply {
            setHints(mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE), DecodeHintType.TRY_HARDER to true))
        }
        reader.decodeWithState(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(width, height, argb)))).text
    }.getOrNull()

    suspend fun fromUri(context: Context, uri: Uri): String? = withContext(Dispatchers.IO) {
        runCatching {
            val resolver = context.contentResolver
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > MAX_SIDE) sample *= 2
            val bitmap = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
                ?: return@runCatching null
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            decode(bitmap.width, bitmap.height, pixels).also { bitmap.recycle() }
        }.getOrNull()
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew --no-daemon :app:testDebugUnitTest --tests 'com.marksy.os.upstox.UpstoxQrImageTest'`
Expected: PASS (2 tests). If `QRCodeWriter` is unresolved in tests, zxing core is not on the test classpath: add `testImplementation("com.google.zxing:core:3.4.1")` to `app/build.gradle.kts` and rerun.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/marksy/os/upstox/UpstoxQrImage.kt app/src/test/java/com/marksy/os/upstox/UpstoxQrImageTest.kt
git commit -F - <<'EOF'
feat(upstox): read the setup QR from a picked image

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
```

---

### Task 5: Settings › Upstox screen

**Files:**
- Modify (rewrite all but `UpstoxAppKeysDialog`): `app/src/main/java/com/marksy/os/ui/UpstoxScreen.kt`
- Modify: `app/src/main/java/com/marksy/os/ui/MarksyTheme.kt` (add `MarksyType.Code` after `Label`, line 107; import `FontFamily`)
- Modify: `app/src/main/AndroidManifest.xml` (tools namespace; zxing scanner orientation)

**Interfaces:**
- Consumes: everything from Tasks 1–4; existing `PortfolioRepository(context).store/disconnect()/` and `.store.holdings()`, `UpstoxSignInActivity`, `UpstoxApiClient.ltp`, `UpstoxIndices.NIFTY_50`, `UpstoxAuthException`.
- Produces: `UpstoxScreen(store: UpstoxTokenStore, padding: PaddingValues, onChanged: () -> Unit)` (signature unchanged, so `MainActivity.kt:712` is untouched); `UpstoxAppKeysDialog` unchanged (used by `PortfolioScreen.kt:293`).

This task is UI only. Per the test budget there are no layout tests; validation is `ThemeGuardTest`, a build, an offscreen render compared with the canvas board, and the device check in Task 7.

- [ ] **Step 1: Add the code type token**

In `MarksyTheme.kt` add `import androidx.compose.ui.text.font.FontFamily` beside the `FontWeight` import, and after `val Label = …` (line 107):

```kotlin
    val Code = TextStyle(fontSize = 11.sp, fontFamily = FontFamily.Monospace)
```

- [ ] **Step 2: Let the scanner rotate**

In `AndroidManifest.xml` add `xmlns:tools="http://schemas.android.com/tools"` to `<manifest>`, and inside `<application>`:

```xml
        <activity
            android:name="com.journeyapps.barcodescanner.CaptureActivity"
            android:screenOrientation="fullSensor"
            tools:replace="screenOrientation" />
```

- [ ] **Step 3: Rewrite `UpstoxScreen.kt`**

Keep the existing `UpstoxAppKeysDialog` function exactly as it is (lines 145-178 today) at the end of the file. Replace everything else (imports, `UpstoxScreen`, `HoldingsSignInSection`, `Check`) with:

```kotlin
package com.marksy.os.ui

import android.content.Intent
import android.view.WindowManager
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.marksy.os.MarksyFormat
import com.marksy.os.portfolio.PortfolioRepository
import com.marksy.os.upstox.TokenCheck
import com.marksy.os.upstox.UpstoxApiClient
import com.marksy.os.upstox.UpstoxAuthException
import com.marksy.os.upstox.UpstoxDraft
import com.marksy.os.upstox.UpstoxField
import com.marksy.os.upstox.UpstoxIndices
import com.marksy.os.upstox.UpstoxOAuth
import com.marksy.os.upstox.UpstoxOAuthStore
import com.marksy.os.upstox.UpstoxQrImage
import com.marksy.os.upstox.UpstoxQrImport
import com.marksy.os.upstox.UpstoxSandboxStatus
import com.marksy.os.upstox.UpstoxSaved
import com.marksy.os.upstox.UpstoxSettingsSaver
import com.marksy.os.upstox.UpstoxTokenStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId

private val IST: ZoneId = ZoneId.of("Asia/Kolkata")

private sealed interface Notice {
    data class Filled(val fields: Set<UpstoxField>) : Notice
    data class Refused(val message: String) : Notice
    data class Done(val message: String, val ok: Boolean) : Notice
}

/** Settings › Upstox: the live account (read-only) and the sandbox token, typed or filled from a QR code. */
@Composable
fun UpstoxScreen(store: UpstoxTokenStore, padding: PaddingValues, onChanged: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repo = remember { PortfolioRepository(context) }
    val saver = remember { UpstoxSettingsSaver(repo.store, store, ::checkAnalyticsToken) }
    var version by remember { mutableIntStateOf(0) }
    val saved = remember(version) { UpstoxSaved.load(repo.store, store) }
    val signedIn = remember(version) { repo.store.accessToken() != null }
    val hasAccountData = remember(version) { saved.hasSecret || saved.sandboxExpiresAt != null || repo.store.holdings() != null }
    var draft by remember(saved) { mutableStateOf(UpstoxDraft(saved)) }
    var notice by remember { mutableStateOf<Notice?>(null) }
    var busy by remember { mutableStateOf(false) }
    var sheet by remember { mutableStateOf(false) }
    var help by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    SecureWindow()

    fun take(scanned: String?) {
        when (val result = scanned?.let(UpstoxQrImport::parse) ?: UpstoxQrImport.Rejected("No QR code found in that image.")) {
            is UpstoxQrImport.Ok -> { draft = draft.apply(result); notice = Notice.Filled(result.fields.keys) }
            is UpstoxQrImport.Rejected -> notice = Notice.Refused(result.message)
        }
    }
    val scan = rememberLauncherForActivityResult(ScanContract()) { result -> result.contents?.let(::take) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch { take(UpstoxQrImage.fromUri(context, uri)) }
    }
    val signIn = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { version++ }
    val edit: (UpstoxField, String) -> Unit = { field, text -> draft = draft.edit(field, text) }
    val problem = draft.problem()

    Box(Modifier.fillMaxSize().background(MarksyTheme.Background).padding(padding).consumeWindowInsets(padding)) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(MarksySpace.Gutter),
            verticalArrangement = Arrangement.spacedBy(MarksySpace.ListGap)
        ) {
            notice?.let { NoticeCard(it) }
            LiveAccountCard(draft, saved, signedIn, edit,
                onSignIn = { signIn.launch(Intent(context, UpstoxSignInActivity::class.java)) },
                onRemoveAnalytics = { store.clear(); onChanged(); version++ })
            SandboxCard(draft, saved, edit, onRemove = { repo.store.clearSandboxToken(); version++ })
            if (draft.dirty && problem != null) Text(problem, color = MarksyTheme.Negative, style = MarksyType.Small)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(MarksySpace.Gap, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                if (draft.dirty) MarksyButton("Discard", { draft = draft.discard(); notice = null }, style = MarksyButtonStyle.Text, color = MarksyTheme.TextSecondary, enabled = !busy)
                MarksyButton(if (busy) "Checking…" else "Save & test", {
                    busy = true
                    scope.launch {
                        val result = saver.save(draft, System.currentTimeMillis())
                        notice = Notice.Done(saveMessage(result), result.analytics != TokenCheck.REJECTED)
                        busy = false
                        version++
                        onChanged()
                    }
                }, enabled = draft.dirty && problem == null && !busy)
            }
            if (busy) MarksyInlineLoader("Contacting Upstox…")
            if (hasAccountData) {
                MarksyButton("Disconnect Upstox", { confirm = true }, style = MarksyButtonStyle.Text, color = MarksyTheme.Negative)
                Text("Forgets the app keys, today's sign-in, saved holdings and the sandbox token. The Analytics Token stays.", color = MarksyTheme.TextMuted, style = MarksyType.Meta)
            }
        }
        OneHandControls(
            filters = emptyList(), selectedFilter = "", onFilterSelected = {},
            actions = listOf(
                FloatingAction(Icons.AutoMirrored.Filled.HelpOutline, "How to make the QR") { help = true },
                FloatingAction(Icons.Default.QrCodeScanner, "Import from QR") { sheet = true }
            )
        )
    }

    if (sheet) MarksyBottomSheet(onDismissRequest = { sheet = false }) {
        Column(Modifier.fillMaxWidth().padding(horizontal = MarksySpace.Gutter).padding(bottom = MarksySpace.Gutter), verticalArrangement = Arrangement.spacedBy(MarksySpace.Gap)) {
            Text("Import from QR", color = MarksyTheme.TextPrimary, style = MarksyType.Heading)
            ImportOption(Icons.Default.PhotoCamera, "Scan with camera", "Point at the code on your computer screen.") {
                sheet = false
                scan.launch(ScanOptions().apply {
                    setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                    setPrompt("Point at the Marksy Upstox QR")
                    setBeepEnabled(false)
                    setOrientationLocked(false)
                })
            }
            ImportOption(Icons.Default.PhotoLibrary, "Choose an image", "Android photo picker. Marksy sees only the image you pick.") {
                sheet = false
                pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }
            Text("The code only fills the form. Nothing is saved until you tap Save & test.", color = MarksyTheme.TextSecondary, style = MarksyType.Small)
            MarksyButton("How to make the QR", { sheet = false; help = true }, style = MarksyButtonStyle.Text)
        }
    }
    if (help) MarksyDialog(
        onDismissRequest = { help = false },
        title = { Text("Make an Upstox QR") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(MarksySpace.Gap)) {
                QR_STEPS.forEachIndexed { i, step -> Text("${i + 1}. $step", color = MarksyTheme.TextSecondary, style = MarksyType.Small) }
                Text(QR_FORMAT, color = MarksyTheme.TextPrimary, style = MarksyType.Code)
                Text(QR_RULES, color = MarksyTheme.TextSecondary, style = MarksyType.Small)
                Text(QR_WARNING, color = MarksyTheme.Warning, style = MarksyType.Small)
            }
        },
        confirmButton = { MarksyButton("Close", { help = false }, style = MarksyButtonStyle.Text) }
    )
    if (confirm) MarksyDialog(
        onDismissRequest = { confirm = false },
        title = { Text("Disconnect Upstox?") },
        text = { Text("Marksy forgets your app keys, today's sign-in, the saved holdings, the sandbox token and Upstox's login cookies. Your Analytics Token stays.") },
        dismissButton = { MarksyButton("Cancel", { confirm = false }, style = MarksyButtonStyle.Text, color = MarksyTheme.TextSecondary) },
        confirmButton = { MarksyButton("Disconnect", { repo.disconnect(); confirm = false; notice = null; version++ }, style = MarksyButtonStyle.Text, color = MarksyTheme.Negative) }
    )
}

/** Keeps the keys out of screenshots, screen recordings and the recent-apps preview. */
@Composable
private fun SecureWindow() {
    val window = LocalActivity.current?.window
    DisposableEffect(window) {
        window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
}

@Composable
private fun LiveAccountCard(
    draft: UpstoxDraft, saved: UpstoxSaved, signedIn: Boolean, onEdit: (UpstoxField, String) -> Unit,
    onSignIn: () -> Unit, onRemoveAnalytics: () -> Unit
) {
    MarksyCard(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("LIVE ACCOUNT · READ-ONLY", color = MarksyTheme.TextMuted, style = MarksyType.Label, modifier = Modifier.weight(1f))
            when {
                signedIn -> Text("Signed in until 3:30 am", color = MarksyTheme.PrimaryEmerald, style = MarksyType.Meta, fontWeight = FontWeight.SemiBold)
                saved.hasSecret -> Pill("Sign in") { onSignIn() }
            }
        }
        Text("Quotes, holdings, positions, funds and today's orders. This side never places orders.", color = MarksyTheme.TextSecondary, style = MarksyType.Small)
        SecretField(draft, UpstoxField.ANALYTICS_TOKEN, if (saved.hasAnalytics) "Saved · paste or import to replace" else "Paste or import your Analytics Token", onEdit)
        if (saved.hasAnalytics) MetaAction(
            saved.analyticsExpiresAt?.let { "Market data · valid until ${fullDay(it)}." } ?: "Market data · saved.",
            MarksyTheme.TextMuted, onRemoveAnalytics
        )
        SecretField(draft, UpstoxField.API_KEY, "Paste or import your API key", onEdit)
        SecretField(draft, UpstoxField.API_SECRET, if (saved.hasSecret) "Saved · leave blank to keep it" else "Paste or import your API secret", onEdit)
        SecretField(draft, UpstoxField.REDIRECT, UpstoxOAuth.DEFAULT_REDIRECT, onEdit, secret = false)
        Text("Must match your Upstox app: https, or http only to 127.0.0.1 on this phone.", color = MarksyTheme.TextMuted, style = MarksyType.Meta)
    }
}

@Composable
private fun SandboxCard(draft: UpstoxDraft, saved: UpstoxSaved, onEdit: (UpstoxField, String) -> Unit, onRemove: () -> Unit) {
    val status = UpstoxSandboxStatus.of(saved.sandboxExpiresAt, System.currentTimeMillis())
    MarksyCard(Modifier.fillMaxWidth()) {
        Text("SANDBOX · PRACTICE ORDERS", color = MarksyTheme.TextMuted, style = MarksyType.Label)
        Text("Used only with Upstox's sandbox. Practice orders are simulated: they never reach your account, holdings or positions.", color = MarksyTheme.TextSecondary, style = MarksyType.Small)
        SecretField(
            draft, UpstoxField.SANDBOX_TOKEN,
            if (status.state == UpstoxSandboxStatus.State.NONE) "Paste or import a sandbox token" else "Saved · paste or import to replace", onEdit
        )
        val (line, color) = sandboxLine(status)
        if (status.state == UpstoxSandboxStatus.State.NONE) Text(line, color = color, style = MarksyType.Meta)
        else MetaAction(line, color, onRemove)
    }
}

@Composable
private fun SecretField(draft: UpstoxDraft, field: UpstoxField, placeholder: String, onEdit: (UpstoxField, String) -> Unit, secret: Boolean = true) {
    var shown by remember { mutableStateOf(false) }
    CompactTextField(
        value = draft.text(field),
        onValueChange = { onEdit(field, it) },
        modifier = Modifier.fillMaxWidth(),
        label = if (field in draft.fromQr) "${field.label} · from QR" else field.label,
        placeholder = placeholder,
        visualTransformation = if (secret && !shown) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = if (secret) KeyboardType.Password else KeyboardType.Uri, autoCorrectEnabled = false),
        trailing = if (secret) {
            { MarksyButton(if (shown) "Hide" else "Show", { shown = !shown }, style = MarksyButtonStyle.Text) }
        } else null
    )
}

@Composable
private fun MetaAction(text: String, color: Color, onRemove: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(text, color = color, style = MarksyType.Meta, modifier = Modifier.weight(1f))
        MarksyButton("Remove", onRemove, style = MarksyButtonStyle.Text, color = MarksyTheme.Negative)
    }
}

@Composable
private fun ImportOption(icon: ImageVector, title: String, detail: String, onClick: () -> Unit) {
    MarksyCard(Modifier.fillMaxWidth(), onClick = onClick, onClickLabel = title) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(MarksySpace.CardPadding)) {
            Icon(icon, contentDescription = null, tint = MarksyTheme.PrimaryEmerald)
            Column {
                Text(title, color = MarksyTheme.TextPrimary, style = MarksyType.Body, fontWeight = FontWeight.SemiBold)
                Text(detail, color = MarksyTheme.TextMuted, style = MarksyType.Meta)
            }
        }
    }
}

@Composable
private fun NoticeCard(notice: Notice) {
    val (border, title, detail) = when (notice) {
        is Notice.Filled -> Triple(MarksyTheme.PrimaryEmerald, "Filled from QR: ${notice.fields.sortedBy { it.ordinal }.joinToString { it.label }}.", "Check the fields, then Save & test. Nothing is saved yet.")
        is Notice.Refused -> Triple(MarksyTheme.Negative, "Not imported. ${notice.message}", "Nothing was changed.")
        is Notice.Done -> Triple(if (notice.ok) MarksyTheme.PrimaryEmerald else MarksyTheme.Warning, notice.message, null)
    }
    MarksyCard(Modifier.fillMaxWidth(), border = border) {
        Text(title, color = MarksyTheme.TextPrimary, style = MarksyType.Body, fontWeight = FontWeight.SemiBold)
        detail?.let { Text(it, color = MarksyTheme.TextSecondary, style = MarksyType.Small) }
    }
}

private fun fullDay(ms: Long): String = MarksyFormat.fullDay(Instant.ofEpochMilli(ms).atZone(IST))

private fun days(n: Int) = if (n == 1) "1 day" else "$n days"

private fun sandboxLine(s: UpstoxSandboxStatus): Pair<String, Color> {
    val day = s.expiresAt?.let(::fullDay).orEmpty()
    return when (s.state) {
        UpstoxSandboxStatus.State.NONE -> "No sandbox token. Practice orders are off." to MarksyTheme.TextMuted
        UpstoxSandboxStatus.State.VALID -> "Expires $day · ${days(s.daysLeft)} left." to MarksyTheme.PrimaryEmerald
        UpstoxSandboxStatus.State.EXPIRING -> "Expires $day · ${days(s.daysLeft)} left. Make a new sandbox token and import it." to MarksyTheme.Warning
        UpstoxSandboxStatus.State.EXPIRED -> "Expired $day. Practice orders are off; live orders stay off too." to MarksyTheme.Negative
    }
}

private fun saveMessage(r: UpstoxSettingsSaver.Result): String = buildList {
    add(
        when (r.analytics) {
            TokenCheck.OK -> "Saved. The Analytics Token works."
            TokenCheck.UNREACHABLE -> "Saved. Couldn't reach Upstox to test the Analytics Token; Home will retry."
            TokenCheck.REJECTED -> if (r.saved.isEmpty()) "Upstox rejected the Analytics Token, so nothing was saved." else "Saved, except the Analytics Token: Upstox rejected it."
            null -> "Saved on this phone."
        }
    )
    if (r.signInDropped) add("The app keys changed, so sign in to Upstox again for today's portfolio.")
}.joinToString(" ")

private suspend fun checkAnalyticsToken(token: String): TokenCheck = try {
    UpstoxApiClient { token }.ltp(listOf(UpstoxIndices.NIFTY_50))
    TokenCheck.OK
} catch (e: UpstoxAuthException) {
    TokenCheck.REJECTED
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    TokenCheck.UNREACHABLE
}

private val QR_STEPS = listOf(
    "Upstox Developer Apps › your one live app. Redirect URL ${UpstoxOAuth.DEFAULT_REDIRECT}. Copy the API key and secret. Don't create a second live app: Upstox keeps only the newest.",
    "Analytics Token › Generate. Read-only, valid for a year.",
    "Sandbox token: only after Upstox confirms a sandbox app can't remove your live app (see docs/UPSTOX-SETUP.md). Valid for 30 days.",
    "On your computer, offline: python tools/make_upstox_qr.py. It asks for each value without showing it and saves upstox-qr.png.",
    "Here: Import from QR, check the fields, Save & test. Then delete the PNG."
)

private val QR_FORMAT = """
{
  "type": "marksy-upstox",
  "v": 1,
  "api_key": "…",
  "api_secret": "…",
  "redirect_uri": "${UpstoxOAuth.DEFAULT_REDIRECT}",
  "analytics_token": "…",
  "sandbox_token": "…"
}
""".trimIndent()

private const val QR_RULES = "type and v are required. Leave out what you don't want to change. Empty values, extra fields, repeated fields and access tokens are refused, and so is a code over 4 KB."

private const val QR_WARNING = "The QR is not encrypted. Anyone with the image can read these keys. Treat it like a password file: never use an online QR site, keep it out of cloud photo backups, and delete it after importing."
```

Then append the unchanged `UpstoxAppKeysDialog` (with its KDoc) after this code. Its imports (`UpstoxOAuthStore`, `CompactTextField`, `KeyboardOptions`, `KeyboardType`, `PasswordVisualTransformation`, `verticalScroll`, `rememberScrollState`) are covered above.

- [ ] **Step 4: Run the theme guard and build**

Run: `./gradlew --no-daemon :app:testDebugUnitTest --tests 'com.marksy.os.ui.ThemeGuardTest' :app:assembleDebug`
Expected: `ThemeGuardTest` PASS, `BUILD SUCCESSFUL`. A guard failure names the file and rule; fix it with a theme token, never with a guard exception.

- [ ] **Step 5: Compare with the mockup (scratch only, not committed)**

Render the screen offscreen with a throwaway Robolectric test in the session scratchpad (pattern from memory: `@GraphicsMode(GraphicsMode.Mode.NATIVE)`, `@Config(qualifiers = "w400dp-h2600dp-xxhdpi")`, `compose.onRoot().captureToImage()` → PNG). Check it against the canvas board `UpstoxConnection.dc.html`: two cards, field order, the floating stack, the sandbox line colours. Two deliberate differences from the mockup: an imported field shows "· from QR" in its label instead of a green chip, and a saved secret or token shows a "Saved · …" placeholder instead of masked dots (they are never loaded back into the form). Delete the scratch test afterwards.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/marksy/os/ui/UpstoxScreen.kt app/src/main/java/com/marksy/os/ui/MarksyTheme.kt app/src/main/AndroidManifest.xml
git commit -F - <<'EOF'
feat(settings): one Upstox form with QR import (camera or photo picker)

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
```

---

### Task 6: Offline QR generator and setup doc

**Files:**
- Create: `tools/make_upstox_qr.py`
- Create: `docs/UPSTOX-SETUP.md`
- Modify: `docs/ROADMAP-NEXT.md:90` (the "Market › Portfolio v1" bullet)

**Interfaces:**
- Consumes: the QR format v1 rules from Task 1 (the script mirrors them so a code it makes always imports).

- [ ] **Step 1: Write the generator**

```python
#!/usr/bin/env python3
"""Make a Marksy Upstox QR code (format v1) on this computer, offline.

The QR is NOT encrypted: anyone with the image can read the keys. Treat the PNG like a
password file, never use an online QR site, and delete it after importing it in Marksy.

    pip install segno
    python tools/make_upstox_qr.py [--out upstox-qr.png] [--force]
    python tools/make_upstox_qr.py --sample --out sample-qr.png   # fake values, to test the import

On Windows run it in PowerShell or Windows Terminal (in Git Bash use: winpty python ...),
so the hidden prompts work.
"""
import argparse
import getpass
import json
import os
import re
import sys
from urllib.parse import urlsplit

TYPE = "marksy-upstox"
VERSION = 1
MAX_BYTES = 4096
DEFAULT_REDIRECT = "http://127.0.0.1/marksy-upstox"
FIELDS = [  # key, prompt, hidden, max length
    ("api_key", "API key", True, 128),
    ("api_secret", "API secret", True, 128),
    ("redirect_uri", f"Redirect URL (Marksy's default is {DEFAULT_REDIRECT})", False, 512),
    ("analytics_token", "Analytics Token", True, 2048),
    ("sandbox_token", "Sandbox token", True, 2048),
]
PRINTABLE = re.compile(r"[\x21-\x7e]+")
LOOPBACK = {"127.0.0.1", "localhost", "::1"}
SAMPLE = {
    "api_key": "sample-api-key",
    "api_secret": "sample-api-secret",
    "redirect_uri": DEFAULT_REDIRECT,
    "analytics_token": "sample.analytics.token",
    "sandbox_token": "sample.sandbox.token",
}


def problem(key, label, value, limit):
    if not PRINTABLE.fullmatch(value):
        return f"{label} has spaces or characters Marksy can't use."
    if len(value) > limit:
        return f"{label} is too long."
    if key == "redirect_uri":
        url = urlsplit(value)
        if not (url.scheme == "https" or (url.scheme == "http" and url.hostname in LOOPBACK)):
            return "The redirect URL must be https, or http to 127.0.0.1 on the phone."
        if url.username or url.password or url.fragment:
            return "The redirect URL can't have a user name or a #fragment."
    return None


def ask():
    fields = {}
    for key, label, hidden, limit in FIELDS:
        while True:
            prompt = f"{label} (Enter to leave out): "
            value = (getpass.getpass(prompt) if hidden else input(prompt)).strip()
            if not value:
                break
            issue = problem(key, label, value, limit)
            if issue is None:
                fields[key] = value
                break
            print(issue, file=sys.stderr)
    return fields


def main():
    parser = argparse.ArgumentParser(description="Make a Marksy Upstox QR code offline.")
    parser.add_argument("--out", default="upstox-qr.png", help="PNG to write (default upstox-qr.png)")
    parser.add_argument("--force", action="store_true", help="overwrite an existing file")
    parser.add_argument("--sample", action="store_true", help="use fake values, to test the import")
    args = parser.parse_args()
    try:
        import segno
    except ImportError:
        sys.exit("Needs segno: pip install segno")
    if os.path.exists(args.out) and not args.force:
        sys.exit(f"{args.out} already exists; pass --force to overwrite it.")
    fields = dict(SAMPLE) if args.sample else ask()
    if not fields:
        sys.exit("Nothing to put in the QR.")
    if fields.get("analytics_token") and fields.get("analytics_token") == fields.get("sandbox_token"):
        sys.exit("The same token is in both token fields.")
    text = json.dumps({"type": TYPE, "v": VERSION, **fields}, separators=(",", ":"))
    if len(text.encode("utf-8")) > MAX_BYTES:
        sys.exit("The code would be over 4 KB.")
    segno.make(text, error="m", micro=False).save(args.out, scale=6, border=4)
    if os.name == "posix":
        os.chmod(args.out, 0o600)
    print(f"Saved {args.out} with: {', '.join(fields)}.")
    print("It is not encrypted. Import it in Marksy, then delete it.")


if __name__ == "__main__":
    main()
```

- [ ] **Step 2: Verify the generator end to end (scratch only)**

```bash
SCRATCH="C:/Users/prsingh/AppData/Local/Temp/claude/C--AIAgent-marksy-os/e3a0bd16-0247-4a68-99b5-5ae64e06fa2d/scratchpad"
python -m pip install --quiet segno
python tools/make_upstox_qr.py --sample --out "$SCRATCH/sample-qr.png"
python tools/make_upstox_qr.py --sample --out "$SCRATCH/sample-qr.png"; echo "exit=$?"
```

Expected: first run prints `Saved …/sample-qr.png with: api_key, api_secret, redirect_uri, analytics_token, sandbox_token.`; second run exits non-zero with `… already exists; pass --force to overwrite it.`

Then confirm the app reads it: a throwaway Robolectric test in the scratchpad loads the PNG with `javax.imageio.ImageIO.read`, passes its pixels to `UpstoxQrImage.decode`, then the text to `UpstoxQrImport.parse`. Expected: `Ok` with 5 fields. Delete the scratch test afterwards.

- [ ] **Step 3: Write `docs/UPSTOX-SETUP.md`**

```markdown
# Upstox setup

Marksy uses three Upstox tokens, each for one job:

- **Analytics Token** (about a year, read-only): market data. It goes in the QR.
- **Daily access token** (until about 3:30 am IST): holdings, positions, funds and today's orders, read-only. It comes only from the in-app Upstox sign-in, never from a QR.
- **Sandbox token** (30 days): practice orders on Upstox's sandbox only. Nothing reaches your account.

Live orders are not available: since 1 April 2026 Upstox accepts order calls only from a registered static IP, and a phone has none.

## 1. The live app

Upstox allows one active API app per person and deletes older ones when you make another. Use the app you already have; it serves both the sign-in and orders.

In Upstox Developer Apps, open your app and set the redirect URL to `http://127.0.0.1/marksy-upstox`. Copy the API key and API secret.

## 2. Analytics Token

Developer Apps › Analytics Token › Generate. It is read-only and lasts a year.

## 3. Sandbox token (wait)

Upstox does not say whether a sandbox app counts toward the one-app rule. Until Upstox confirms in writing that creating one cannot delete or invalidate your live app, its keys or tokens, do not create it. Marksy's Buy/Sell ticket works in Preview without it.

Once confirmed: Developer Apps › Sandbox › New Sandbox App › Generate.

## 4. Make the QR on your computer

    pip install segno
    python tools/make_upstox_qr.py

It asks for each value without showing it (Enter leaves a field out) and saves `upstox-qr.png`. On Windows use PowerShell or Windows Terminal; in Git Bash run `winpty python tools/make_upstox_qr.py`.

The QR is **not encrypted**. Anyone with the image can read the keys. Never use an online QR site, keep the image out of cloud photo backups, and delete it after importing.

## 5. Import it

Marksy › Settings › Upstox › Import from QR (camera or a saved image). The code only fills the form; check the fields, then tap **Save & test**. Delete the PNG.

## Format (version 1)

    {
      "type": "marksy-upstox",
      "v": 1,
      "api_key": "…",
      "api_secret": "…",
      "redirect_uri": "http://127.0.0.1/marksy-upstox",
      "analytics_token": "…",
      "sandbox_token": "…"
    }

- `type` and `v` are required; every other field is optional. A field left out keeps the saved value.
- Refused: invalid JSON, a repeated field, any other field (including `access_token`), a non-text or empty value, values with spaces, a code over 4 KB, `v` other than 1, and a redirect URL that is not `https://`, or `http://` to `127.0.0.1`, `localhost` or `[::1]`, or that has a user name or `#fragment`.
- The same token in both token fields is refused.
```

- [ ] **Step 4: Update the roadmap**

Append to the end of the `docs/ROADMAP-NEXT.md` line 90 bullet ("Market › Portfolio v1: …"):

```markdown
 Settings › Upstox is one form for the live app keys, the Analytics Token and a sandbox token, typed or imported from an offline QR code (camera or photo picker; it fills the form, Save & test stores it). Format and steps: `docs/UPSTOX-SETUP.md`; spec `docs/superpowers/specs/2026-10-18-upstox-qr-orders-design.md`.
```

- [ ] **Step 5: Commit**

```bash
git add tools/make_upstox_qr.py docs/UPSTOX-SETUP.md docs/ROADMAP-NEXT.md
git commit -F - <<'EOF'
docs(upstox): offline QR generator and setup guide

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
```

---

### Task 7: Phase review, PR and device check

**Files:**
- Modify (canvas, not repo): `project/UpstoxConnection.dc.html` on the "Marksy App" canvas

- [ ] **Step 1: Full suite once on this head**

Run: `./gradlew --no-daemon :app:testDebugUnitTest`
Expected: all tests pass (173+ before this PR plus the new ones). Any failure: read the failing test's stack trace only, fix, rerun only the affected tests.

- [ ] **Step 2: Whole-phase review**

Run one code review over `git diff origin/main...HEAD` (superpowers:requesting-code-review), checking: no credential reaches logs, exceptions or `toString`; the QR rules match the spec §3 list; `UpstoxAppKeysDialog` is unchanged; `FLAG_SECURE` is cleared when leaving the screen. Fix findings, rerun the affected tests, re-review once.

- [ ] **Step 3: Mirror the shipped differences in the canvas**

Edit `UpstoxConnection.dc.html` so it matches the app: "· from QR" label suffix instead of the chip, "Saved · …" placeholders for saved secret and tokens, and a Remove action on the token lines. Publish to the canvas (same board).

- [ ] **Step 4: Push and open the PR**

```bash
git push -u origin feat/upstox-settings-qr
gh pr create --title "Settings › Upstox: one form with QR import" --body-file - <<'EOF'
One Settings › Upstox form for the live app keys, Analytics Token and sandbox token, typed or imported from an offline QR (camera or photo picker). The QR only fills the form; Save & test stores it. Strict QR v1 schema, FLAG_SECURE on the screen, offline generator `tools/make_upstox_qr.py`, setup doc.

PR 1 of 3 from `docs/superpowers/specs/2026-10-18-upstox-qr-orders-design.md`. No order code in this PR.

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
```

- [ ] **Step 5: Device check (owner OK required before installing)**

Ask the owner before installing. Then `./gradlew --no-daemon :app:assembleDebug` and `adb install -r` (data kept), following the phone safety gates in memory (focus on `com.marksy.os/.MainActivity`, no call PiP, bounds from `uiautomator dump --windows`). The screen sets `FLAG_SECURE`, so screencap shows black there: verify with `uiautomator dump` text instead. Check:
- Settings › Upstox shows both cards and the floating QR and help buttons.
- With the owner's OK, `adb push` the fake-value `sample-qr.png` to `/sdcard/Pictures/MarksyTest/` (then `am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file:///sdcard/Pictures/MarksyTest/sample-qr.png`), and remove the folder after the check.
- With the owner's help, import `sample-qr.png` (fake values) via Choose an image: the notice lists five fields, labels show "· from QR", Save & test stays possible, and **Discard** returns to the saved values. Do not save the fake values.
- Import a wrong QR (any URL QR): "Not imported. This isn't a Marksy Upstox code."

Record the results in the PR.

- [ ] **Step 6: Merge**

After the review is clean and the device check passes, merge the PR (squash), delete the branch, and update memory `upstox-orders-qr-2026-10-18.md` with the merge commit. CI is checked on the last PR of the three.
