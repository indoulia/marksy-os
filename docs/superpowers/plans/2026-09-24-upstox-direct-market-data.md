# Direct-to-Upstox Market Data Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let a MarksyOS user enter their own Upstox Analytics Token (read-only, on-device only) so Stock detail can show a live Upstox-sourced price alongside — never instead of — the existing marksy-api-sourced instrument data.

**Architecture:** Two independent repos, two independent data sources. `marksy-api` gains one additive field (`instrumentKey` on the existing `InstrumentLifecycle` response) so the Android app can resolve a symbol to Upstox's ISIN-based instrument key without hardcoding a mapping. MarksyOS gains a new `com.marksy.os.upstox` package (DTOs, an `HttpURLConnection` REST client, a repository returning the existing `MarketDataState<T>` sealed type) that is never imported by `com.marksy.os.market` or vice versa beyond the shared `MarketDataState` type — the two data sources compose only at the screen layer, which tries Upstox first (when configured) and falls back to Marksy.

**Tech Stack:** Python/FastAPI/SQLAlchemy (marksy-api side), Kotlin/Jetpack Compose, `org.json` raw parsing, `HttpURLConnection` (no Retrofit/OkHttp), JUnit4 + Robolectric, Android Keystore.

**Spec:** `docs/superpowers/specs/2026-09-24-upstox-direct-market-data-design.md`

## Global Constraints

- The Upstox Analytics Token is **read-only** (Upstox's own documented scope — Market Quote, Historical Data, Market Information, V3 WebSocket feed). This plan builds only read-only calls. Never introduce an order-placement/trading endpoint against it.
- The token is stored **only on-device** (Android Keystore, mirroring `SecureCredentialStore`'s existing pattern) and is **never** sent to `marksy-api` or any server other than `api.upstox.com`.
- **Run-alongside, never replace:** the existing `com.marksy.os.market` package, its screens, and its tests are not modified in structure — only additive changes (one new DTO field, one new screen parameter with a safe default, new produceState calls). The existing marksy-api-sourced data path must keep working unchanged when no Upstox token is configured.
- No field-level merging between Upstox and Marksy data. A whole-source preference only, with honest "(Upstox)" / "(Marksy)" labeling — never silently blend two sources' numbers into one line.
- **Scope cut from the spec, decided during planning:** historical candle data is dropped from this plan's scope entirely (not built-but-unused) — there is no chart screen to consume it yet, and building an unused client method repeats a Minor finding already seen once in this project (`com.marksy.os.market`'s several unused endpoints, flagged in sub-project 1's final review). Only the LTP quote endpoint is built here. Historical candles become their own future task once a chart screen is designed.
- Upstox's base URL (`https://api.upstox.com/v3`) is fixed and not user-configurable (unlike `marksy-api`'s base URL, which exists because Marksy self-hosts multiple deployments) — no settings field for it, no `normalizeBaseUrl`-style validation needed for a URL that never varies.
- Follow the existing raw-HTTP/`org.json` convention exactly; no Retrofit/OkHttp/kotlinx.serialization/DI framework.

## Review Focus

- No Upstox token configured → Stock detail must show Marksy's price line exactly as it does today, never a crash or a blank Upstox line (Task 8).
- Upstox call fails (HTTP error, malformed JSON) while Marksy's own data loads fine → Stock detail still shows Marksy's price line; the Upstox failure must not take down the whole screen (Task 7, Task 8) — apply the JSONException-catching lesson from sub-project 1's Task 6 fix from the start, not as an afterthought.
- A symbol with no `instrumentKey` from marksy-api (e.g. a stock marksy-api hasn't resolved yet) → no Upstox call is attempted at all, Marksy's price line shows alone, no error surfaced for the missing Upstox data specifically (Task 8).
- Upstox Analytics Token field must never be logged, and must be independent of both the existing integration key and the Marksy API key in storage (Task 2) — mirrors the credential-separation constraint already enforced for the Marksy key.
- IPO/Predictions screens and the existing `MarketIntelligenceRepository`/`MarketApiClient` are completely untouched by this plan — verify no file under `com.marksy.os.market` (other than `InstrumentModels.kt`'s one new field) or `api/routers/`, `api/schemas/` outside `instruments.py` is modified.

---

## File Structure

**marksy-api (Python):**
- Modify: `api/schemas/instruments.py` — add `instrumentKey` field
- Modify: `api/services/instruments.py` — wire `Stock.instrument_key` through
- Create: `tests/test_instrument_lifecycle_instrument_key.py`

**marksy-os (Kotlin), modified:**
- `app/src/main/java/com/marksy/os/gateway/SecureCredentialStore.kt` — Upstox token storage
- `app/src/main/java/com/marksy/os/gateway/GatewayQrPayload.kt` — Upstox token QR field
- `app/src/main/java/com/marksy/os/market/InstrumentModels.kt` — `instrumentKey` field on `InstrumentLifecycleDto`
- `app/src/main/java/com/marksy/os/MainActivity.kt` — Upstox Gateway Settings block; `MarketScreen` call site
- `app/src/main/java/com/marksy/os/data/MarksyContainer.kt` — `upstoxMarket(context)` accessor
- `app/src/main/java/com/marksy/os/gateway/MarksyGatewayProvider.kt` — `upstoxClient()` construction
- `app/src/main/java/com/marksy/os/ui/MarketScreen.kt` — fetch Upstox quote alongside instrument
- `app/src/main/java/com/marksy/os/ui/StockDetailScreen.kt` — prefer Upstox price line when available

**marksy-os (Kotlin), new package `com.marksy.os.upstox`:**
- `UpstoxModels.kt` — `UpstoxQuoteDto`, `LtpQuoteResponseDto` + parsers
- `UpstoxApiClient.kt` — `UpstoxApiClient` interface + `RealUpstoxApiClient`
- `UpstoxMarketRepository.kt` — wraps the client, returns `MarketDataState<T>`

**marksy-os tests, modified/new:**
- `app/src/test/java/com/marksy/os/gateway/SecureCredentialStoreTest.kt` — modified
- `app/src/test/java/com/marksy/os/gateway/GatewayQrParserTest.kt` — modified
- `app/src/test/java/com/marksy/os/market/InstrumentModelsTest.kt` — modified
- `app/src/test/java/com/marksy/os/upstox/UpstoxModelsTest.kt` — new
- `app/src/test/java/com/marksy/os/upstox/RealUpstoxApiClientTest.kt` — new
- `app/src/test/java/com/marksy/os/upstox/UpstoxMarketRepositoryTest.kt` — new
- `app/src/test/java/com/marksy/os/ui/StockDetailScreenTest.kt` — modified

---

### Task 1: marksy-api — expose `instrumentKey` on `InstrumentLifecycle`

**Repository:** `marksy-api` (work from `C:\AIAgent\marksy-api`, branch off `main`)

**Files:**
- Modify: `api/schemas/instruments.py`
- Modify: `api/services/instruments.py`
- Test: `tests/test_instrument_lifecycle_instrument_key.py`

**Interfaces:**
- Consumes: `Stock.instrument_key` (`app/models.py:12`, already exists, no migration needed).
- Produces: `InstrumentLifecycle.instrumentKey: str | None` on `GET /api/v1/instruments/{symbol}`'s response — consumed by MarksyOS's `InstrumentLifecycleDto` (Task 4).

- [ ] **Step 1: Write the failing test**

```python
"""Batch: InstrumentLifecycle exposes Stock.instrument_key so a client can
resolve the Upstox instrument key for direct on-device market data calls,
without a new endpoint or a hardcoded symbol->key mapping."""

from __future__ import annotations

from datetime import datetime, timezone

from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker
from sqlalchemy.pool import StaticPool

from api.services.instruments import get_instrument_lifecycle
from app.db import Base
from app.models import Stock

UTC = timezone.utc


def _session():
    engine = create_engine(
        "sqlite:///:memory:", connect_args={"check_same_thread": False}, poolclass=StaticPool
    )
    Base.metadata.create_all(engine)
    return sessionmaker(bind=engine)()


def _stock(session, symbol, *, instrument_key=None):
    # Field set matches tests/test_api_global_search.py's own `_stock` helper
    # exactly (is_active passed explicitly rather than relying on an assumed
    # DB-level default) plus the one new field this test exercises.
    stock = Stock(
        symbol=symbol,
        exchange="NSE",
        company_name="Reliance Industries" if symbol == "RELIANCE" else "New Co",
        sector=None,
        is_active=True,
        instrument_key=instrument_key,
    )
    session.add(stock)
    session.commit()
    return stock


def test_instrument_lifecycle_carries_the_stocks_instrument_key():
    session = _session()
    _stock(session, "RELIANCE", instrument_key="NSE_EQ|INE002A01018")

    result = get_instrument_lifecycle(session, "RELIANCE", now=datetime(2026, 9, 24, tzinfo=UTC))

    assert result.instrumentKey == "NSE_EQ|INE002A01018"


def test_instrument_lifecycle_instrument_key_is_none_when_unresolved():
    session = _session()
    _stock(session, "NEWCO", instrument_key=None)

    result = get_instrument_lifecycle(session, "NEWCO", now=datetime(2026, 9, 24, tzinfo=UTC))

    assert result.instrumentKey is None
```

- [ ] **Step 2: Run test to verify it fails**

Run (from `C:\AIAgent\marksy-api`): `python -m pytest tests/test_instrument_lifecycle_instrument_key.py -v`
Expected: FAIL — `AttributeError: 'InstrumentLifecycle' object has no attribute 'instrumentKey'` (or a `TypeError` if Pydantic drops the unknown field silently — either way, the assertion fails).

- [ ] **Step 3: Add the field**

In `api/schemas/instruments.py`, find the `InstrumentLifecycle` class (its fields end with `predictions: list[InstrumentPredictionEntry]`) and add one field:

```python
    instrumentKey: str | None = None
```

Add it directly after `isActive: bool` (grouping it with the instrument-identity fields, not the prediction list).

- [ ] **Step 4: Wire it through the service**

In `api/services/instruments.py`, in `get_instrument_lifecycle`, find the final `return InstrumentLifecycle(...)` call and add one argument, directly after `isActive=bool(stock.is_active),`:

```python
        instrumentKey=stock.instrument_key,
```

- [ ] **Step 5: Run test to verify it passes**

Run: `python -m pytest tests/test_instrument_lifecycle_instrument_key.py -v`
Expected: PASS (2 tests).

- [ ] **Step 6: Run the existing instrument-related test suite to confirm no regression**

Run: `python -m pytest tests/ -k instrument -v`
Expected: PASS, including any pre-existing tests this pattern matches.

- [ ] **Step 7: Commit**

```bash
git add api/schemas/instruments.py api/services/instruments.py tests/test_instrument_lifecycle_instrument_key.py
git commit -m "feat(instruments): expose Stock.instrument_key on InstrumentLifecycle"
```

---

### Task 2: MarksyOS — Upstox Analytics Token credential storage

**Repository:** `marksy-os` (work from `C:\AIAgent\marksy-os`, branch off `main`)

**Files:**
- Modify: `app/src/main/java/com/marksy/os/gateway/SecureCredentialStore.kt`
- Test: `app/src/test/java/com/marksy/os/gateway/SecureCredentialStoreTest.kt`

**Interfaces:**
- Produces: `SecureCredentialStore.getUpstoxAnalyticsToken(): String?`, `setUpstoxAnalyticsToken(value: String)`, `clearUpstoxAnalyticsToken()` — used by `MarksyGatewayProvider.upstoxClient()` (Task 7) and the Gateway Settings UI (Task 5).

- [ ] **Step 1: Write the failing test**

Add to `SecureCredentialStoreTest.kt` (inside the existing `class SecureCredentialStoreTest { ... }` body, after `marketApiKeyIsIndependentOfIntegrationKey`):

```kotlin
    @Test
    fun upstoxAnalyticsTokenRoundTripsThroughEncryptedStorage() {
        val store = SecureCredentialStore(context)
        assertNull(store.getUpstoxAnalyticsToken())

        store.setUpstoxAnalyticsToken("  upstox-analytics-token-abc  ")

        assertEquals("upstox-analytics-token-abc", store.getUpstoxAnalyticsToken())
    }

    @Test
    fun clearingUpstoxAnalyticsTokenRemovesIt() {
        val store = SecureCredentialStore(context)
        store.setUpstoxAnalyticsToken("upstox-analytics-token-abc")

        store.clearUpstoxAnalyticsToken()

        assertNull(store.getUpstoxAnalyticsToken())
    }

    @Test
    fun upstoxAnalyticsTokenIsIndependentOfOtherCredentials() {
        val store = SecureCredentialStore(context)
        store.setIntegrationKey("tips-integration-key")
        store.setMarketApiKey("scoped-market-key")
        store.setUpstoxAnalyticsToken("upstox-analytics-token-abc")

        assertEquals("tips-integration-key", store.getIntegrationKey())
        assertEquals("scoped-market-key", store.getMarketApiKey())
        assertEquals("upstox-analytics-token-abc", store.getUpstoxAnalyticsToken())

        store.clearUpstoxAnalyticsToken()

        assertEquals("tips-integration-key", store.getIntegrationKey())
        assertEquals("scoped-market-key", store.getMarketApiKey())
        assertNull(store.getUpstoxAnalyticsToken())
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.marksy.os.gateway.SecureCredentialStoreTest"`
Expected: FAIL — `getUpstoxAnalyticsToken`/`setUpstoxAnalyticsToken`/`clearUpstoxAnalyticsToken` unresolved references.

- [ ] **Step 3: Implement the storage methods**

Add to `SecureCredentialStore` (after `clearMarketApiKey()`, before `getBaseUrl()`), mirroring the market-key methods exactly:

```kotlin
    fun getUpstoxAnalyticsToken(): String? {
        val ciphertext = preferences.getString(KEY_UPSTOX_TOKEN_CIPHERTEXT, null) ?: return null
        val iv = preferences.getString(KEY_UPSTOX_TOKEN_IV, null) ?: return null
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_BITS, decode(iv)))
            String(cipher.doFinal(decode(ciphertext)), StandardCharsets.UTF_8)
                .trim()
                .takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
    }

    fun setUpstoxAnalyticsToken(value: String) {
        val token = value.trim()
        require(token.isNotBlank()) { "Upstox Analytics Token must not be blank" }

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        preferences.edit()
            .putString(KEY_UPSTOX_TOKEN_CIPHERTEXT, encode(cipher.doFinal(token.toByteArray(StandardCharsets.UTF_8))))
            .putString(KEY_UPSTOX_TOKEN_IV, encode(cipher.iv))
            .apply()
    }

    fun clearUpstoxAnalyticsToken() {
        preferences.edit().remove(KEY_UPSTOX_TOKEN_CIPHERTEXT).remove(KEY_UPSTOX_TOKEN_IV).apply()
    }
```

Add the two new preference keys to the `private companion object` (after `KEY_MARKET_API_KEY_IV`):

```kotlin
        const val KEY_UPSTOX_TOKEN_CIPHERTEXT = "upstox_analytics_token"
        const val KEY_UPSTOX_TOKEN_IV = "upstox_analytics_token_iv"
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.marksy.os.gateway.SecureCredentialStoreTest"`
Expected: PASS (6 tests total).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/marksy/os/gateway/SecureCredentialStore.kt app/src/test/java/com/marksy/os/gateway/SecureCredentialStoreTest.kt
git commit -m "feat(upstox): add on-device Upstox Analytics Token storage"
```

---

### Task 3: MarksyOS — QR scan support for the Upstox token

**Files:**
- Modify: `app/src/main/java/com/marksy/os/gateway/GatewayQrPayload.kt`
- Test: `app/src/test/java/com/marksy/os/gateway/GatewayQrParserTest.kt`

**Interfaces:**
- Produces: `GatewayQrPayload.upstoxAnalyticsToken: String?` (new field, third constructor param), `hasAny` now also true when it is present. Consumed by the Gateway Settings UI (Task 5).

- [ ] **Step 1: Write the failing tests**

Add to `GatewayQrParserTest.kt` (after `hasAnyReflectsPresence`):

```kotlin
    @Test fun parsesJsonWithUpstoxToken() {
        val r = GatewayQrParser.parse("""{"integrationKey":"mk_live_abc","upstoxAnalyticsToken":"ups_live_xyz"}""")
        assertEquals("mk_live_abc", r.integrationKey)
        assertEquals("ups_live_xyz", r.upstoxAnalyticsToken)
    }

    @Test fun upstoxTokenAcceptsAliases() {
        val r1 = GatewayQrParser.parse("""{"upstoxToken":"ups_live_xyz"}""")
        assertEquals("ups_live_xyz", r1.upstoxAnalyticsToken)
        val r2 = GatewayQrParser.parse("""{"analyticsToken":"ups_live_xyz"}""")
        assertEquals("ups_live_xyz", r2.upstoxAnalyticsToken)
    }

    @Test fun blankUpstoxTokenBecomesNull() {
        val r = GatewayQrParser.parse("""{"upstoxAnalyticsToken":"   "}""")
        assertNull(r.upstoxAnalyticsToken)
    }

    @Test fun upstoxTokenAloneMakesHasAnyTrue() {
        assertEquals(true, GatewayQrParser.parse("""{"upstoxAnalyticsToken":"ups_live_xyz"}""").hasAny)
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "com.marksy.os.gateway.GatewayQrParserTest"`
Expected: FAIL — `upstoxAnalyticsToken` unresolved reference on `GatewayQrPayload`.

- [ ] **Step 3: Implement the field and parsing**

Replace the full content of `GatewayQrPayload.kt`:

```kotlin
package com.marksy.os.gateway

import org.json.JSONObject

/** Gateway configuration decoded from a scanned QR code. */
data class GatewayQrPayload(val integrationKey: String?, val baseUrl: String?, val upstoxAnalyticsToken: String?) {
    val hasAny: Boolean get() = !integrationKey.isNullOrBlank() || !baseUrl.isNullOrBlank() || !upstoxAnalyticsToken.isNullOrBlank()
}

/**
 * Decodes the admin-app gateway QR payload, canonical form
 * {"integrationKey":"...","baseUrl":"...","upstoxAnalyticsToken":"..."}. Tolerant of
 * field aliases and, as a fallback, treats a non-JSON scan as the raw integration key.
 */
object GatewayQrParser {
    private val KEY_FIELDS = listOf("integrationKey", "key", "apiKey")
    private val URL_FIELDS = listOf("baseUrl", "url", "base_url")
    private val UPSTOX_TOKEN_FIELDS = listOf("upstoxAnalyticsToken", "upstoxToken", "analyticsToken")

    fun parse(scanned: String?): GatewayQrPayload {
        val raw = scanned?.trim().orEmpty()
        if (raw.isEmpty()) return GatewayQrPayload(null, null, null)

        if (raw.startsWith("{")) {
            runCatching { JSONObject(raw) }.getOrNull()?.let { json ->
                return GatewayQrPayload(
                    integrationKey = json.firstNonBlank(KEY_FIELDS),
                    baseUrl = json.firstNonBlank(URL_FIELDS),
                    upstoxAnalyticsToken = json.firstNonBlank(UPSTOX_TOKEN_FIELDS)
                )
            }
        }

        // Not JSON (or unparseable): the QR carried the bare integration key.
        return GatewayQrPayload(integrationKey = raw, baseUrl = null, upstoxAnalyticsToken = null)
    }

    private fun JSONObject.firstNonBlank(fields: List<String>): String? =
        fields.asSequence()
            .map { optString(it, "").trim() }
            .firstOrNull { it.isNotBlank() }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "com.marksy.os.gateway.GatewayQrParserTest"`
Expected: PASS (12 tests total).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/marksy/os/gateway/GatewayQrPayload.kt app/src/test/java/com/marksy/os/gateway/GatewayQrParserTest.kt
git commit -m "feat(upstox): parse Upstox Analytics Token from the gateway QR payload"
```

---

### Task 4: MarksyOS — `instrumentKey` on `InstrumentLifecycleDto`

**Files:**
- Modify: `app/src/main/java/com/marksy/os/market/InstrumentModels.kt`
- Test: `app/src/test/java/com/marksy/os/market/InstrumentModelsTest.kt`

**Interfaces:**
- Consumes: `instrumentKey` field on the `/instruments/{symbol}` response (Task 1, marksy-api).
- Produces: `InstrumentLifecycleDto.instrumentKey: String?` — consumed by `MarketScreen.kt` (Task 8) to decide whether an Upstox quote can be attempted for the open symbol.

- [ ] **Step 1: Write the failing test**

Add to `InstrumentModelsTest.kt` (after `instrumentWithNoPredictionsYieldsEmptyList`):

```kotlin
    @Test
    fun parsesInstrumentKeyWhenPresent() {
        val json = org.json.JSONObject(
            """{"symbol": "RELIANCE", "companyName": "Reliance Industries", "exchange": "NSE", "sector": null, "isActive": true, "instrumentKey": "NSE_EQ|INE002A01018", "market": {"lastClosePrice": null, "asOfSessionDate": null, "freshnessState": null}, "predictionCount": 0, "openPredictionCount": 0, "predictions": []}"""
        )

        val instrument = InstrumentLifecycleDto.parse(json)

        assertEquals("NSE_EQ|INE002A01018", instrument.instrumentKey)
    }

    @Test
    fun instrumentKeyIsNullWhenAbsent() {
        val json = org.json.JSONObject(
            """{"symbol": "NEWCO", "companyName": null, "exchange": "NSE", "sector": null, "isActive": true, "market": {"lastClosePrice": null, "asOfSessionDate": null, "freshnessState": null}, "predictionCount": 0, "openPredictionCount": 0, "predictions": []}"""
        )

        val instrument = InstrumentLifecycleDto.parse(json)

        assertNull(instrument.instrumentKey)
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "com.marksy.os.market.InstrumentModelsTest"`
Expected: FAIL — `instrumentKey` unresolved reference on `InstrumentLifecycleDto`.

- [ ] **Step 3: Add the field**

In `InstrumentModels.kt`, add `val instrumentKey: String? = null` to `InstrumentLifecycleDto`'s constructor (after `val isActive: Boolean,`) — **with the `= null` default**, not just a nullable type: `StockDetailScreenTest.kt`'s existing `instrument()` fixture (from the previous plan) constructs `InstrumentLifecycleDto` with all-named arguments and no `instrumentKey`, and a nullable-but-required parameter would break that call site's compilation. A default value keeps it compiling unchanged. Add the corresponding parse line (after `isActive = json.boolOrFalse("isActive"),`):

```kotlin
            instrumentKey = json.textOrNull("instrumentKey"),
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "com.marksy.os.market.InstrumentModelsTest"`
Expected: PASS (7 tests total).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/marksy/os/market/InstrumentModels.kt app/src/test/java/com/marksy/os/market/InstrumentModelsTest.kt
git commit -m "feat(upstox): parse instrumentKey on InstrumentLifecycleDto"
```

---

### Task 5: MarksyOS — Upstox Analytics Token settings UI

**Files:**
- Modify: `app/src/main/java/com/marksy/os/MainActivity.kt` (`GatewaySettingsHost`)

**Interfaces:**
- Consumes: `SecureCredentialStore.getUpstoxAnalyticsToken/setUpstoxAnalyticsToken/clearUpstoxAnalyticsToken` (Task 2), `GatewayQrPayload.upstoxAnalyticsToken` (Task 3).
- Produces: no new public API — this is UI wiring only, following the existing Market-key block's established pattern exactly (including its position — this plan's own past mistake was putting a credential's Save/Remove far from its field, so place this block's Save/Remove immediately after its own field, matching the Marksy-key block's already-corrected layout).

- [ ] **Step 1: Read the current `GatewaySettingsHost` function**

Read `app/src/main/java/com/marksy/os/MainActivity.kt`'s `GatewaySettingsHost` composable in full before editing — its exact current line numbers have shifted from earlier work this session (the Market-key block was reordered). Do not assume line numbers; locate the function by name.

- [ ] **Step 2: Add state variables**

In `GatewaySettingsHost`, alongside the existing `marketKey`/`marketKeyConfigured`/`revealMarketKey` state declarations, add:

```kotlin
    var upstoxToken by rememberSaveable { mutableStateOf("") }
    var upstoxTokenConfigured by remember { mutableStateOf(store.getUpstoxAnalyticsToken() != null) }
    var revealUpstoxToken by rememberSaveable { mutableStateOf(false) }
```

- [ ] **Step 3: Extend the QR scan handler**

In the `scanLauncher` callback's non-null-contents branch, after the existing `parsed.baseUrl?.let { baseUrl = it }` line, add:

```kotlin
            parsed.upstoxAnalyticsToken?.let { upstoxToken = it }
```

- [ ] **Step 4: Add the Upstox Analytics Token block**

After the Market intelligence key block's `Text(if (marketKeyConfigured) "Market key configured" else "Market key not configured", ...)` line and before the shared `message?.let { ... }` line, insert:

```kotlin
            Text("Upstox Analytics Token", color = MarksyTheme.TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            Text(
                "Your own read-only Upstox market-data token, generated in Upstox's developer console. Stays on this device only — never sent to Marksy.",
                color = MarksyTheme.TextSecondary,
                fontSize = 12.sp
            )
            CompactTextField(
                value = upstoxToken,
                onValueChange = { upstoxToken = it },
                modifier = Modifier.fillMaxWidth(),
                label = "Upstox Analytics Token",
                visualTransformation = if (revealUpstoxToken) VisualTransformation.None else PasswordVisualTransformation(),
                trailing = {
                    TextButton(onClick = { revealUpstoxToken = !revealUpstoxToken }, contentPadding = PaddingValues(horizontal = 8.dp)) {
                        Text(if (revealUpstoxToken) "Hide" else "Show", color = MarksyTheme.PrimaryEmerald, fontSize = 12.sp)
                    }
                }
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        runCatching { store.setUpstoxAnalyticsToken(upstoxToken.trim()) }
                            .onSuccess { upstoxToken = ""; revealUpstoxToken = false; upstoxTokenConfigured = true; message = "Upstox token saved securely on this device." }
                            .onFailure { message = "Could not save the Upstox token. Try again." }
                    },
                    enabled = upstoxToken.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(containerColor = MarksyTheme.PrimaryEmerald)
                ) { Text("Save Upstox Token", color = Color.Black) }
                OutlinedButton(onClick = {
                    store.clearUpstoxAnalyticsToken()
                    upstoxToken = ""
                    revealUpstoxToken = false
                    upstoxTokenConfigured = false
                    message = "Upstox token removed. Upstox-sourced prices are unavailable until reconfigured."
                }) { Text("Remove", color = MarksyTheme.RedUrgent) }
            }
            Text(if (upstoxTokenConfigured) "Upstox token configured" else "Upstox token not configured", color = if (upstoxTokenConfigured) MarksyTheme.PrimaryEmerald else MarksyTheme.TextSecondary, fontSize = 12.sp)
```

(Note: unlike the Marksy-key/integration-key fields, this block has no Base URL dependency — Upstox's base URL is fixed, so "Save Upstox Token" only ever calls `setUpstoxAnalyticsToken`, nothing else.)

- [ ] **Step 5: Build and manually verify**

Run: `./gradlew :app:installDebug` (or `:app:compileDebugKotlin` if not testing on a device this step). Confirm the module compiles. If a device is available, manually verify the new block appears in More → Configure Gateway, below the Market intelligence key block, with working Save/Remove/Show-Hide and QR scan populating the field — this composable has no existing test coverage in this codebase (the same is true of the Marksy-key and integration-key blocks it mirrors), consistent with prior sessions' convention of verifying `GatewaySettingsHost` manually.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/marksy/os/MainActivity.kt
git commit -m "feat(upstox): add Upstox Analytics Token field to Gateway Settings"
```

---

### Task 6: MarksyOS — Upstox LTP quote models

**Files:**
- Create: `app/src/main/java/com/marksy/os/upstox/UpstoxModels.kt`
- Test: `app/src/test/java/com/marksy/os/upstox/UpstoxModelsTest.kt`

**Interfaces:**
- Produces: `UpstoxQuoteDto(lastPrice: Double, previousClose: Double?, volume: Long?)`, `LtpQuoteResponseDto(quotes: Map<String, UpstoxQuoteDto>)` with `companion object { fun parse(envelope: JSONObject): LtpQuoteResponseDto }`. Consumed by `RealUpstoxApiClient` (Task 7).

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.marksy.os.upstox

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpstoxModelsTest {
    @Test
    fun parsesLtpQuoteResponseWithOneInstrument() {
        val envelope = JSONObject(
            """
            {
              "status": "success",
              "data": {
                "NSE_EQ:RELIANCE": {"last_price": 1452.3, "instrument_token": "NSE_EQ|INE002A01018", "ltq": 1, "volume": 500000, "cp": 1440.1}
              }
            }
            """
        )

        val response = LtpQuoteResponseDto.parse(envelope)

        assertEquals(1, response.quotes.size)
        val quote = response.quotes.values.single()
        assertEquals(1452.3, quote.lastPrice, 1e-9)
        assertEquals(1440.1, quote.previousClose!!, 1e-9)
        assertEquals(500000L, quote.volume)
    }

    @Test
    fun missingOptionalFieldsYieldNulls() {
        val envelope = JSONObject(
            """{"status": "success", "data": {"NSE_EQ:RELIANCE": {"last_price": 1452.3}}}"""
        )

        val quote = LtpQuoteResponseDto.parse(envelope).quotes.values.single()

        assertEquals(1452.3, quote.lastPrice, 1e-9)
        assertNull(quote.previousClose)
        assertNull(quote.volume)
    }

    @Test
    fun emptyDataYieldsEmptyMap() {
        val envelope = JSONObject("""{"status": "success", "data": {}}""")

        val response = LtpQuoteResponseDto.parse(envelope)

        assertTrue(response.quotes.isEmpty())
    }

    @Test
    fun missingDataYieldsEmptyMap() {
        val envelope = JSONObject("""{"status": "success"}""")

        val response = LtpQuoteResponseDto.parse(envelope)

        assertTrue(response.quotes.isEmpty())
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "com.marksy.os.upstox.UpstoxModelsTest"`
Expected: FAIL — `UpstoxModels.kt` does not exist.

- [ ] **Step 3: Implement the models**

```kotlin
package com.marksy.os.upstox

import org.json.JSONObject

/** Upstox's LTP quote entry. Field names (`last_price`, `cp`, `volume`) are Upstox's own
 * wire names, verified against `GET /v3/market-quote/ltp` — not marksy-api's naming
 * convention, which this package never depends on. */
data class UpstoxQuoteDto(val lastPrice: Double, val previousClose: Double?, val volume: Long?) {
    companion object {
        fun parse(json: JSONObject) = UpstoxQuoteDto(
            lastPrice = json.optDouble("last_price", 0.0).takeIf { it.isFinite() } ?: 0.0,
            previousClose = json.optDouble("cp").takeIf { it.isFinite() },
            volume = if (json.has("volume") && !json.isNull("volume")) json.optLong("volume") else null
        )
    }
}

/** Upstox keys the response by whatever instrument identifier it echoes back — not
 * necessarily byte-identical to the key requested — so callers read the map's values,
 * never look a specific key back up by the string they sent. */
data class LtpQuoteResponseDto(val quotes: Map<String, UpstoxQuoteDto>) {
    companion object {
        fun parse(envelope: JSONObject): LtpQuoteResponseDto {
            val data = envelope.optJSONObject("data") ?: JSONObject()
            val map = data.keys().asSequence().associateWith { key -> UpstoxQuoteDto.parse(data.getJSONObject(key)) }
            return LtpQuoteResponseDto(map)
        }
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "com.marksy.os.upstox.UpstoxModelsTest"`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/marksy/os/upstox/UpstoxModels.kt app/src/test/java/com/marksy/os/upstox/UpstoxModelsTest.kt
git commit -m "feat(upstox): add LTP quote DTOs and parser"
```

---

### Task 7: MarksyOS — `UpstoxApiClient`

**Files:**
- Create: `app/src/main/java/com/marksy/os/upstox/UpstoxApiClient.kt`
- Modify: `app/src/main/java/com/marksy/os/gateway/MarksyGatewayProvider.kt`
- Test: `app/src/test/java/com/marksy/os/upstox/RealUpstoxApiClientTest.kt`

**Interfaces:**
- Consumes: `UpstoxQuoteDto`/`LtpQuoteResponseDto` (Task 6); `SecureCredentialStore.getUpstoxAnalyticsToken()` (Task 2).
- Produces: `interface UpstoxApiClient { suspend fun ltpQuote(instrumentKeys: List<String>): LtpQuoteResponseDto }`; `class RealUpstoxApiClient(token: String) : UpstoxApiClient`; `MarksyGatewayProvider.upstoxClient(): UpstoxApiClient?` (null when unconfigured). Consumed by `UpstoxMarketRepository` (Task 8).

- [ ] **Step 1: Write the failing test**

Mirroring `RealMarketApiClientTest`'s scope exactly — the transport itself is not unit-tested (matching this codebase's convention), only construction-time validation:

```kotlin
package com.marksy.os.upstox

import org.junit.Assert.assertThrows
import org.junit.Test

class RealUpstoxApiClientTest {
    @Test
    fun rejectsBlankTokenAtConstruction() {
        assertThrows(IllegalArgumentException::class.java) {
            RealUpstoxApiClient(token = "  ")
        }
    }

    @Test
    fun rejectsEmptyInstrumentKeyList() {
        val client = RealUpstoxApiClient(token = "ups_live_xyz")
        assertThrows(IllegalArgumentException::class.java) {
            kotlinx.coroutines.runBlocking { client.ltpQuote(emptyList()) }
        }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.marksy.os.upstox.RealUpstoxApiClientTest"`
Expected: FAIL — `UpstoxApiClient.kt` does not exist.

- [ ] **Step 3: Implement the client**

```kotlin
package com.marksy.os.upstox

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

interface UpstoxApiClient {
    suspend fun ltpQuote(instrumentKeys: List<String>): LtpQuoteResponseDto
}

class UpstoxApiException(message: String) : IOException(message)

/** Direct, on-device client for Upstox's V3 market-data REST API, using the user's own
 * Analytics Token (read-only, never marksy-api's credentials, never sent to marksy-api).
 * Structurally mirrors `RealMarketApiClient`: raw `HttpURLConnection`, no third-party
 * HTTP library. Upstox's base URL is fixed (not a Marksy self-hosted deployment), so
 * unlike `RealMarketApiClient` there is no configurable/validated base URL here. */
class RealUpstoxApiClient(token: String) : UpstoxApiClient {
    private val token: String = token.trim().also { require(it.isNotBlank()) { "Upstox Analytics Token must not be blank" } }

    override suspend fun ltpQuote(instrumentKeys: List<String>): LtpQuoteResponseDto {
        require(instrumentKeys.isNotEmpty()) { "instrumentKeys must not be empty" }
        val query = instrumentKeys.joinToString(",") { encode(it) }
        return LtpQuoteResponseDto.parse(execute("$BASE_URL/market-quote/ltp?instrument_key=$query"))
    }

    private suspend fun execute(url: String): JSONObject = withContext(Dispatchers.IO) {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = false
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Authorization", "Bearer $token")
            doInput = true
        }
        try {
            val code = connection.responseCode
            if (code in REDIRECT_CODES) throw UpstoxApiException("Upstox API redirect refused")
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.bufferedReader()?.use { reader ->
                val buffer = CharArray(4096)
                val builder = StringBuilder()
                while (true) {
                    val read = reader.read(buffer)
                    if (read < 0) break
                    builder.append(buffer, 0, read)
                    if (builder.length > MAX_RESPONSE_CHARS) throw IOException("Upstox API response exceeded the safety limit")
                }
                builder.toString()
            }.orEmpty()
            if (code !in 200..299) {
                if (code in 400..499) throw UpstoxApiException("Upstox API returned HTTP $code: ${response.take(300)}")
                throw IOException("Upstox API returned HTTP $code: ${response.take(300)}")
            }
            return@withContext JSONObject(response)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } finally {
            connection.disconnect()
        }
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    private companion object {
        const val BASE_URL = "https://api.upstox.com/v3"
        const val CONNECT_TIMEOUT_MS = 10_000
        const val READ_TIMEOUT_MS = 20_000
        const val MAX_RESPONSE_CHARS = 200_000
        val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
    }
}
```

Add the construction point to `MarksyGatewayProvider`:

```kotlin
    /** Null until the user has entered their own Upstox Analytics Token; every screen
     * that uses this must degrade to its own Unavailable state, never crash. */
    fun upstoxClient(): com.marksy.os.upstox.UpstoxApiClient? {
        val store = SecureCredentialStore(AppContext.get())
        val token = store.getUpstoxAnalyticsToken() ?: return null
        return runCatching { com.marksy.os.upstox.RealUpstoxApiClient(token) as com.marksy.os.upstox.UpstoxApiClient }.getOrNull()
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.marksy.os.upstox.RealUpstoxApiClientTest"`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/marksy/os/upstox/UpstoxApiClient.kt app/src/main/java/com/marksy/os/gateway/MarksyGatewayProvider.kt app/src/test/java/com/marksy/os/upstox/RealUpstoxApiClientTest.kt
git commit -m "feat(upstox): add UpstoxApiClient HTTP transport and provider wiring"
```

---

### Task 8: MarksyOS — `UpstoxMarketRepository` and Stock-detail wiring

**Files:**
- Create: `app/src/main/java/com/marksy/os/upstox/UpstoxMarketRepository.kt`
- Modify: `app/src/main/java/com/marksy/os/data/MarksyContainer.kt`
- Modify: `app/src/main/java/com/marksy/os/ui/MarketScreen.kt`
- Modify: `app/src/main/java/com/marksy/os/ui/StockDetailScreen.kt`
- Modify: `app/src/main/java/com/marksy/os/MainActivity.kt` (one-line `MarketScreen(...)` call-site update)
- Test: `app/src/test/java/com/marksy/os/upstox/UpstoxMarketRepositoryTest.kt`
- Test: `app/src/test/java/com/marksy/os/ui/StockDetailScreenTest.kt` (modified)

**Interfaces:**
- Consumes: `UpstoxApiClient` (Task 7, injected — a fake implements it in tests); `MarketDataState<T>` (`com.marksy.os.market`, reused, not duplicated); `InstrumentLifecycleDto.instrumentKey` (Task 4).
- Produces: `class UpstoxMarketRepository(private val client: UpstoxApiClient?) { suspend fun quote(instrumentKey: String): MarketDataState<UpstoxQuoteDto> }`; `MarksyContainer.upstoxMarket(context): UpstoxMarketRepository`; `StockDetailScreen`'s new `upstoxQuote: MarketDataState<UpstoxQuoteDto> = MarketDataState.Unavailable` parameter (default keeps existing call sites and tests compiling unchanged).

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.marksy.os.upstox

import com.marksy.os.market.MarketDataState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeUpstoxApiClient(
    private val response: LtpQuoteResponseDto? = null,
    private val error: Throwable? = null
) : UpstoxApiClient {
    override suspend fun ltpQuote(instrumentKeys: List<String>): LtpQuoteResponseDto =
        error?.let { throw it } ?: response!!
}

class UpstoxMarketRepositoryTest {
    @Test
    fun unconfiguredClientYieldsUnavailable() = runBlocking {
        val repository = UpstoxMarketRepository(client = null)

        val state = repository.quote("NSE_EQ|INE002A01018")

        assertTrue(state is MarketDataState.Unavailable)
    }

    @Test
    fun successfulQuoteYieldsLoaded() = runBlocking {
        val response = LtpQuoteResponseDto(mapOf("NSE_EQ:RELIANCE" to UpstoxQuoteDto(1452.3, 1440.1, 500000L)))
        val repository = UpstoxMarketRepository(FakeUpstoxApiClient(response = response))

        val state = repository.quote("NSE_EQ|INE002A01018")

        assertTrue(state is MarketDataState.Loaded)
        assertEquals(1452.3, (state as MarketDataState.Loaded).value.lastPrice, 1e-9)
    }

    @Test
    fun emptyResponseYieldsEmpty() = runBlocking {
        val repository = UpstoxMarketRepository(FakeUpstoxApiClient(response = LtpQuoteResponseDto(emptyMap())))

        val state = repository.quote("NSE_EQ|INE002A01018")

        assertTrue(state is MarketDataState.Empty)
    }

    @Test
    fun thrownIOExceptionYieldsError() = runBlocking {
        val repository = UpstoxMarketRepository(FakeUpstoxApiClient(error = java.io.IOException("boom")))

        val state = repository.quote("NSE_EQ|INE002A01018")

        assertTrue(state is MarketDataState.Error)
    }

    @Test
    fun malformedJsonYieldsError() = runBlocking {
        val repository = UpstoxMarketRepository(FakeUpstoxApiClient(error = org.json.JSONException("malformed")))

        val state = repository.quote("NSE_EQ|INE002A01018")

        assertTrue(state is MarketDataState.Error)
    }
}
```

Add to `StockDetailScreenTest.kt` (after `unavailableStateShowsExplicitMessage`):

```kotlin
    @Test
    fun upstoxQuoteLoadedShowsUpstoxLabelInsteadOfMarksyPrice() {
        val withKey = instrument().copy(instrumentKey = "NSE_EQ|INE002A01018")
        val upstoxQuote = MarketDataState.Loaded(com.marksy.os.upstox.UpstoxQuoteDto(1500.0, 1490.0, 100000L))

        compose.setContent { StockDetailScreen(state = MarketDataState.Loaded(withKey), padding = PaddingValues(), onBack = {}, upstoxQuote = upstoxQuote) }

        compose.onNodeWithText("1500.0", substring = true).assertExists()
        compose.onNodeWithText("Upstox", substring = true).assertExists()
    }

    @Test
    fun noUpstoxQuoteFallsBackToMarksyPriceUnchanged() {
        compose.setContent { StockDetailScreen(state = MarketDataState.Loaded(instrument()), padding = PaddingValues(), onBack = {}) }

        compose.onNodeWithText("Marksy", substring = true).assertExists()
    }
```

(The `instrument()` fixture helper already exists in this file from Task 8 of the previous plan; `.copy(instrumentKey = ...)` works because it's a data class.)

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "com.marksy.os.upstox.UpstoxMarketRepositoryTest" --tests "com.marksy.os.ui.StockDetailScreenTest"`
Expected: FAIL — `UpstoxMarketRepository`/`upstoxQuote` parameter/`instrumentKey` field don't exist yet where expected.

- [ ] **Step 3: Implement the repository**

```kotlin
package com.marksy.os.upstox

import com.marksy.os.market.MarketDataState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONException
import java.io.IOException

/** Every Upstox-sourced screen reads through this, never `UpstoxApiClient` directly, so
 * "not configured" / network / malformed-response handling lives in one place — mirrors
 * `MarketIntelligenceRepository`'s shape exactly, including catching `JSONException`
 * alongside `IOException` from the start (the lesson from that repository's own
 * post-review fix). */
class UpstoxMarketRepository(private val client: UpstoxApiClient?) {
    suspend fun quote(instrumentKey: String): MarketDataState<UpstoxQuoteDto> {
        val activeClient = client ?: return MarketDataState.Unavailable
        return withContext(Dispatchers.IO) {
            try {
                val response = activeClient.ltpQuote(listOf(instrumentKey))
                val quote = response.quotes.values.firstOrNull()
                if (quote == null) MarketDataState.Empty else MarketDataState.Loaded(quote)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: IOException) {
                MarketDataState.Error(error.message ?: "Upstox quote unavailable")
            } catch (error: JSONException) {
                MarketDataState.Error(error.message ?: "Upstox quote unavailable")
            }
        }
    }
}
```

Add the accessor to `MarksyContainer` (mirroring `marketIntelligence(context)`):

```kotlin
    fun upstoxMarket(context: Context): com.marksy.os.upstox.UpstoxMarketRepository =
        com.marksy.os.upstox.UpstoxMarketRepository(com.marksy.os.gateway.MarksyGatewayProvider.upstoxClient())
```

- [ ] **Step 4: Wire the fetch into `MarketScreen`'s `STOCKS` branch**

In `MarketScreen.kt`, add a new parameter to the function signature:

```kotlin
fun MarketScreen(repository: MarketIntelligenceRepository, upstoxRepository: com.marksy.os.upstox.UpstoxMarketRepository, padding: PaddingValues) {
```

In the `MarketTab.STOCKS` branch's non-null-`symbol` case, replace:

```kotlin
                } else {
                    val state by produceState(com.marksy.os.market.MarketDataState.Loading as com.marksy.os.market.MarketDataState<com.marksy.os.market.InstrumentLifecycleDto>, symbol) {
                        value = repository.instrument(symbol)
                    }
                    StockDetailScreen(state = state, padding = padding, onBack = { selectedSymbol = null })
                }
```

with:

```kotlin
                } else {
                    val state by produceState(com.marksy.os.market.MarketDataState.Loading as com.marksy.os.market.MarketDataState<com.marksy.os.market.InstrumentLifecycleDto>, symbol) {
                        value = repository.instrument(symbol)
                    }
                    val instrumentKey = when (val s = state) {
                        is com.marksy.os.market.MarketDataState.Loaded -> s.value.instrumentKey
                        is com.marksy.os.market.MarketDataState.Stale -> s.value.instrumentKey
                        else -> null
                    }
                    val upstoxQuote by produceState(com.marksy.os.market.MarketDataState.Unavailable as com.marksy.os.market.MarketDataState<com.marksy.os.upstox.UpstoxQuoteDto>, instrumentKey) {
                        value = if (instrumentKey != null) upstoxRepository.quote(instrumentKey) else com.marksy.os.market.MarketDataState.Unavailable
                    }
                    StockDetailScreen(state = state, padding = padding, onBack = { selectedSymbol = null }, upstoxQuote = upstoxQuote)
                }
```

- [ ] **Step 5: Update the `MarketScreen(...)` call site in `MainActivity.kt`**

Find `MarketScreen(repository = remember { MarksyContainer.marketIntelligence(applicationContext) }, padding = padding)` and change it to:

```kotlin
selectedTab == 4 -> MarketScreen(repository = remember { MarksyContainer.marketIntelligence(applicationContext) }, upstoxRepository = remember { MarksyContainer.upstoxMarket(applicationContext) }, padding = padding)
```

- [ ] **Step 6: Update `StockDetailScreen` to prefer the Upstox price**

In `StockDetailScreen.kt`, add the import `import com.marksy.os.upstox.UpstoxQuoteDto`, change the function signature to:

```kotlin
fun StockDetailScreen(
    state: MarketDataState<InstrumentLifecycleDto>,
    padding: PaddingValues,
    onBack: () -> Unit,
    upstoxQuote: MarketDataState<UpstoxQuoteDto> = MarketDataState.Unavailable
) {
```

and thread `upstoxQuote` down to `instrumentContent`, changing its signature and the price line:

```kotlin
private fun androidx.compose.foundation.lazy.LazyListScope.instrumentContent(instrument: InstrumentLifecycleDto, upstoxQuote: MarketDataState<UpstoxQuoteDto>) {
    item {
        Text(instrument.companyName ?: instrument.symbol, color = MarksyTheme.TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Text("${instrument.symbol} · ${instrument.exchange}", color = MarksyTheme.TextSecondary, fontSize = 12.sp)
    }
    item {
        val market = instrument.market
        val marksyLine = if (market.lastClosePrice != null) "Last close: ${market.lastClosePrice} (Marksy)" else "Last close: unavailable"
        val freshnessDetails = listOfNotNull(market.asOfSessionDate, market.freshnessState)
        val marksyLineWithFreshness = if (freshnessDetails.isNotEmpty()) "$marksyLine (${freshnessDetails.joinToString(", ")})" else marksyLine
        val priceLine = when (upstoxQuote) {
            is MarketDataState.Loaded -> "Last: ${upstoxQuote.value.lastPrice} (Upstox)"
            is MarketDataState.Stale -> "Last: ${upstoxQuote.value.lastPrice} (Upstox, stale)"
            else -> marksyLineWithFreshness
        }
        Text(priceLine, color = MarksyTheme.TextSecondary, fontSize = 13.sp)
    }
```

(the rest of `instrumentContent` — predictions list — is unchanged). Update its one call site inside `StockDetailScreen`'s `when (state)` block:

```kotlin
            is MarketDataState.Loaded -> instrumentContent(state.value, upstoxQuote)
            is MarketDataState.Stale -> instrumentContent(state.value, upstoxQuote)
```

- [ ] **Step 7: Run tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "com.marksy.os.upstox.UpstoxMarketRepositoryTest" --tests "com.marksy.os.ui.StockDetailScreenTest"`
Expected: PASS (5 + 5 = 10 tests — 3 pre-existing `StockDetailScreenTest` tests plus 2 new ones).

- [ ] **Step 8: Run the full unscoped suite**

Run: `./gradlew :app:testDebugUnitTest` (no `--tests` filter). Paste the actual BUILD output/test count in your report, not a narrative summary.
Expected: PASS, all tests including every pre-existing one.

- [ ] **Step 9: Manually verify on device**

With a real Upstox Analytics Token entered (Task 5's settings block) and a symbol whose `instrumentKey` marksy-api now resolves (Task 1), open that symbol's Stock detail and confirm the price line reads "(Upstox)". Remove the token and confirm it falls back to "(Marksy)" with no crash. Confirm a symbol with no `instrumentKey` shows the Marksy price line with no Upstox attempt or error.

- [ ] **Step 10: Commit**

```bash
git add app/src/main/java/com/marksy/os/upstox/UpstoxMarketRepository.kt app/src/main/java/com/marksy/os/data/MarksyContainer.kt app/src/main/java/com/marksy/os/ui/MarketScreen.kt app/src/main/java/com/marksy/os/ui/StockDetailScreen.kt app/src/main/java/com/marksy/os/MainActivity.kt app/src/test/java/com/marksy/os/upstox/UpstoxMarketRepositoryTest.kt app/src/test/java/com/marksy/os/ui/StockDetailScreenTest.kt
git commit -m "feat(upstox): prefer Upstox price on Stock detail, falling back to Marksy"
```
