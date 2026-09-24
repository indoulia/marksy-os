# Market Intelligence Android UI Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a "Market" section to MarksyOS (Overview, Stock detail, Predictions, IPOs) that reads real data from the already-mature `marksy-api` market-intelligence endpoints, via a new scoped API key credential.

**Architecture:** New `com.marksy.os.market` package holds pure DTO/parse code, an `HttpURLConnection`-based `MarketApiClient` (interface + `RealMarketApiClient`, mirroring the existing `MarksyGatewayClient`/`MarksyTipsApiClient` split), and a `MarketIntelligenceRepository` exposing a shared `MarketDataState<T>` sealed type. UI is four new Compose screens under one new bottom-bar "Market" tab, following existing `MainActivity` conventions exactly (no Jetpack Navigation, no DI framework, `MarksyContainer` service locator).

**Tech Stack:** Kotlin, Jetpack Compose (Material3), `org.json` (raw parsing, no serialization library), `HttpURLConnection` (no Retrofit/OkHttp), JUnit4 + Robolectric (sdk 34) for Compose tests, Android Keystore via existing `SecureCredentialStore`.

**Spec:** `docs/superpowers/specs/2026-09-24-market-intelligence-android-ui-design.md`

## Global Constraints

- Never render `0.00`/`0%`/blank as if it were a real value on `Error` or `Unavailable` — always render an explicit state (spec, "Error / empty / stale handling").
- No `marksy-api` code changes in this plan; only an operational API-key mint (`POST /admin/clients`, `scopes=["marksy"]`) is required server-side.
- `X-Marksy-Integration-Key` (EPIC-803, used only by the existing `/tips` gateway) and the new `marketApiKey` (`X-API-Key`, scope `marksy`) are separate credentials — never conflate their storage or their client classes.
- Follow the existing raw-HTTP/`org.json` convention exactly; do not introduce Retrofit, OkHttp, kotlinx.serialization, or a DI framework.
- `/ipos/tracked` and `POST`/`DELETE /ipos/{ipoId}/tracking` require a real user bearer session (`require_bearer_subject`), which MarksyOS does not have with an API-key-only credential — these are **out of scope** for this plan; the IPO screen is read-only (list/attention/counts/detail/history).
- IPO stage filter values must come from the server (`/ipos/counts`'s `byStage` keys), never a hardcoded list — an unrecognised stage is a 422 the server treats as a real error, not "empty result" (`api/routers/ipo.py`).

## Deliberate field-level scope cuts (v1)

Each DTO below models only the fields the four screens actually render. Everything else in the real schema is a documented, additive cut — parsing more fields later never requires a contract change:

- `MarketSummary`: skips `availability`, `regimeSource`, `participation`, `regimeInterpretation`, `marksyView` (asOf/marketStatus from this + `market/live` cover v1 freshness needs).
- `LiveFeedHealth`: skips `subscriptions`, `capabilities`, `metrics` (only used for a simple footer badge).
- `InstrumentLifecycle`/`InstrumentPredictionEntry`: skips `recommendationId`, `observedDays`, `revisionVersionCount`, `isSupersededByRevision`, `evaluationState`, `evaluationExclusionReason`.
- `ActivePrediction`: skips `initialScore`, `trustComponentsAvailable/Total`, `nextEvaluationAt`, `lastPriceAt`, `lastRevisionAt`, `scoreMetric`, legacy `score` (reads `compositeOpportunityScore` instead, per the schema's own "new clients should read" guidance).
- `IpoListItemOut`/`IpoDetailOut`: skips the untyped dict blobs (`gmp`, `subscription`, `prediction`, `trust`, `recommendation`, `headlineRisk`, `retailAllocationEstimate`, `decisionContexts`, `anchorBook`, `documents`, `allocationEstimates`, `allotmentBasis`, `stageHistory`, `riskRunHistory`, `keyDates`, `fundamentals`, `valuation`, `peers`, `materialEvents`, `analystOpinions`, `risks`, `outcome`) — these are real but heterogeneous, undocumented-shape blocks with no v1 screen to render them.
- `IpoHistoryEntryOut`: skips `trustContributions`, `supporting`/`opposing`/`uncertainties`, `gmp`, `subscriptionByCategory`.

## Review Focus

- No `marketApiKey` configured → every screen must show `Unavailable`, never a crash or a fake render (Task 6).
- Stale/degraded live data (`state` = `STALE`/`DELAYED`/`FALLBACK`, or an old `asOf`) → UI shows a stale badge, never presents it as `LIVE` (Task 6, Task 7).
- Empty results (no gainers/losers, no active predictions, no IPOs for a stage) → explicit `Empty` state, not a blank screen or a crash (Task 6, Task 7, Task 8, Task 9).
- An invalid/unrecognised IPO stage filter (server returns 422 per `api/routers/ipo.py`) → surfaced as a real `Error`, never silently mapped to an empty list (Task 4, Task 9).
- Malformed or non-2xx envelope response (mirroring the exceptions `MarksyTipsApiClient.execute` already throws) → `Error` state with a message, never a partial/garbled render (Task 5, Task 6).

---

## File Structure

**New package `app/src/main/java/com/marksy/os/market/`:**
- `MarketModels.kt` — DTOs + `parse()` functions for market summary/live/health/index-history/sectors
- `InstrumentModels.kt` — DTOs + `parse()` for instrument lifecycle + active predictions
- `IpoModels.kt` — DTOs + `parse()` for IPO list/detail/history/attention/counts
- `MarketApiClient.kt` — `MarketApiClient` interface + `RealMarketApiClient` (HTTP transport)
- `MarketDataState.kt` — shared sealed state type
- `MarketIntelligenceRepository.kt` — wraps the client, derives freshness, exposes `Flow<MarketDataState<T>>`

**Modified:**
- `app/src/main/java/com/marksy/os/gateway/SecureCredentialStore.kt` — add `marketApiKey` storage
- `app/src/main/java/com/marksy/os/gateway/MarksyGatewayProvider.kt` — add `marketIntelligenceClient()`
- `app/src/main/java/com/marksy/os/data/MarksyContainer.kt` — add `marketIntelligence(context)` accessor
- `app/src/main/java/com/marksy/os/MainActivity.kt` — add "Market" bottom tab; add Market API Key field to `GatewaySettingsHost`

**New UI, `app/src/main/java/com/marksy/os/ui/`:**
- `MarketScreen.kt` — top composable, internal sub-tab state (Overview/Stocks/Predictions/IPOs)
- `MarketOverviewScreen.kt`
- `StockDetailScreen.kt`
- `PredictionsScreen.kt`
- `IpoScreen.kt`

**New tests, mirroring `MarketSnapshotTest.kt` / `DashboardScreenTest.kt` conventions:**
- `app/src/test/java/com/marksy/os/gateway/SecureCredentialStoreTest.kt`
- `app/src/test/java/com/marksy/os/market/MarketModelsTest.kt`
- `app/src/test/java/com/marksy/os/market/InstrumentModelsTest.kt`
- `app/src/test/java/com/marksy/os/market/IpoModelsTest.kt`
- `app/src/test/java/com/marksy/os/market/RealMarketApiClientTest.kt`
- `app/src/test/java/com/marksy/os/market/MarketIntelligenceRepositoryTest.kt`
- `app/src/test/java/com/marksy/os/ui/MarketOverviewScreenTest.kt`
- `app/src/test/java/com/marksy/os/ui/StockDetailScreenTest.kt`
- `app/src/test/java/com/marksy/os/ui/PredictionsScreenTest.kt`
- `app/src/test/java/com/marksy/os/ui/IpoScreenTest.kt`

---

### Task 1: Market API key credential storage + Gateway Settings field

**Files:**
- Modify: `app/src/main/java/com/marksy/os/gateway/SecureCredentialStore.kt`
- Modify: `app/src/main/java/com/marksy/os/MainActivity.kt:444-537` (`GatewaySettingsHost`)
- Test: `app/src/test/java/com/marksy/os/gateway/SecureCredentialStoreTest.kt`

**Interfaces:**
- Produces: `SecureCredentialStore.getMarketApiKey(): String?`, `setMarketApiKey(value: String)`, `clearMarketApiKey()` — used by `MarksyGatewayProvider` (Task 5) and `GatewaySettingsHost`.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.marksy.os.gateway

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SecureCredentialStoreTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun marketApiKeyRoundTripsThroughEncryptedStorage() {
        val store = SecureCredentialStore(context)
        assertNull(store.getMarketApiKey())

        store.setMarketApiKey("  scoped-key-123  ")

        assertEquals("scoped-key-123", store.getMarketApiKey())
    }

    @Test
    fun clearingMarketApiKeyRemovesIt() {
        val store = SecureCredentialStore(context)
        store.setMarketApiKey("scoped-key-123")

        store.clearMarketApiKey()

        assertNull(store.getMarketApiKey())
    }

    @Test
    fun marketApiKeyIsIndependentOfIntegrationKey() {
        val store = SecureCredentialStore(context)
        store.setIntegrationKey("tips-integration-key")
        store.setMarketApiKey("scoped-market-key")

        assertEquals("tips-integration-key", store.getIntegrationKey())
        assertEquals("scoped-market-key", store.getMarketApiKey())

        store.clearMarketApiKey()

        assertEquals("tips-integration-key", store.getIntegrationKey())
        assertNull(store.getMarketApiKey())
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.marksy.os.gateway.SecureCredentialStoreTest"`
Expected: FAIL — `getMarketApiKey`/`setMarketApiKey`/`clearMarketApiKey` unresolved references.

- [ ] **Step 3: Implement the storage methods**

Add to `SecureCredentialStore`, mirroring the existing `getIntegrationKey`/`setIntegrationKey`/`clearIntegrationKey` methods exactly (same cipher, same key alias — a second AES key is unnecessary since encryption is per-value with its own IV):

```kotlin
    fun getMarketApiKey(): String? {
        val ciphertext = preferences.getString(KEY_MARKET_API_KEY_CIPHERTEXT, null) ?: return null
        val iv = preferences.getString(KEY_MARKET_API_KEY_IV, null) ?: return null
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

    fun setMarketApiKey(value: String) {
        val key = value.trim()
        require(key.isNotBlank()) { "Market API key must not be blank" }

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        preferences.edit()
            .putString(KEY_MARKET_API_KEY_CIPHERTEXT, encode(cipher.doFinal(key.toByteArray(StandardCharsets.UTF_8))))
            .putString(KEY_MARKET_API_KEY_IV, encode(cipher.iv))
            .apply()
    }

    fun clearMarketApiKey() {
        preferences.edit().remove(KEY_MARKET_API_KEY_CIPHERTEXT).remove(KEY_MARKET_API_KEY_IV).apply()
    }
```

Add the two new preference keys to the `private companion object`:

```kotlin
        const val KEY_MARKET_API_KEY_CIPHERTEXT = "marksy_market_api_key"
        const val KEY_MARKET_API_KEY_IV = "marksy_market_api_key_iv"
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.marksy.os.gateway.SecureCredentialStoreTest"`
Expected: PASS (3 tests).

- [ ] **Step 5: Add the Market API Key field to Gateway Settings**

In `MainActivity.kt`'s `GatewaySettingsHost` (around line 444), add a second masked field beneath the existing integration-key field, following the identical show/hide + save/remove pattern:

```kotlin
    var marketKey by rememberSaveable { mutableStateOf("") }
    var marketKeyConfigured by remember { mutableStateOf(store.getMarketApiKey() != null) }
    var revealMarketKey by rememberSaveable { mutableStateOf(false) }
```

(declared alongside the existing `key`/`configured`/`revealKey` vars), and inside the settings `Column`, after the existing integration-key `CompactTextField`:

```kotlin
            Text("Market intelligence key", color = MarksyTheme.TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            Text(
                "A separate, scoped API key for market data, predictions and IPOs — minted by a Marksy admin, independent of the integration key above.",
                color = MarksyTheme.TextSecondary,
                fontSize = 12.sp
            )
            CompactTextField(
                value = marketKey,
                onValueChange = { marketKey = it },
                modifier = Modifier.fillMaxWidth(),
                label = "Market API key",
                visualTransformation = if (revealMarketKey) VisualTransformation.None else PasswordVisualTransformation(),
                trailing = {
                    TextButton(onClick = { revealMarketKey = !revealMarketKey }, contentPadding = PaddingValues(horizontal = 8.dp)) {
                        Text(if (revealMarketKey) "Hide" else "Show", color = MarksyTheme.PrimaryEmerald, fontSize = 12.sp)
                    }
                }
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        runCatching { store.setMarketApiKey(marketKey.trim()) }
                            .onSuccess { marketKey = ""; revealMarketKey = false; marketKeyConfigured = true; message = "Market key saved securely on this device." }
                            .onFailure { message = "Could not save the market key. Try again." }
                    },
                    enabled = marketKey.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(containerColor = MarksyTheme.PrimaryEmerald)
                ) { Text("Save Market Key", color = Color.Black) }
                OutlinedButton(onClick = {
                    store.clearMarketApiKey()
                    marketKey = ""
                    revealMarketKey = false
                    marketKeyConfigured = false
                    message = "Market key removed. Market Intelligence is unavailable until reconfigured."
                }) { Text("Remove", color = MarksyTheme.RedUrgent) }
            }
            Text(if (marketKeyConfigured) "Market key configured" else "Market key not configured", color = if (marketKeyConfigured) MarksyTheme.PrimaryEmerald else MarksyTheme.TextSecondary, fontSize = 12.sp)
```

- [ ] **Step 6: Manually verify the settings screen**

Run the debug build, open More → Configure Gateway, confirm the new "Market intelligence key" field saves/reveals/removes independently of the existing integration-key field (this composable has no existing test coverage in this codebase — `GatewaySettingsHost` and its sibling fields are verified by running the app, consistent with how the existing integration-key field itself is validated).

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/marksy/os/gateway/SecureCredentialStore.kt app/src/main/java/com/marksy/os/MainActivity.kt app/src/test/java/com/marksy/os/gateway/SecureCredentialStoreTest.kt
git commit -m "feat(market): add scoped market API key storage and settings field"
```

---

### Task 2: Market domain models (summary, live quotes, feed health, index history, sectors)

**Files:**
- Create: `app/src/main/java/com/marksy/os/market/MarketModels.kt`
- Test: `app/src/test/java/com/marksy/os/market/MarketModelsTest.kt`

**Interfaces:**
- Produces: `MarketSummaryDto`, `IndexQuoteDto`, `MarketMoverDto`, `SectorMoveDto`, `LiveQuoteDto`, `LiveQuotesResponseDto`, `LiveFeedHealthDto`, `IndexHistoryDto`, `IndexHistoryPointDto`, `SectorOptionDto` — each with a `companion object { fun parse(json: JSONObject): X }` (or `parseList` for the top-level `/market/sectors` array). Consumed by `RealMarketApiClient` (Task 5) and `MarketIntelligenceRepository` (Task 6).

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.marksy.os.market

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MarketModelsTest {
    @Test
    fun parsesMarketSummary() {
        val json = JSONObject(
            """
            {
              "asOf": "2026-09-24T09:43:21+05:30",
              "marketStatus": "MARKET_HOURS",
              "regime": "BULLISH_LOW_VOL",
              "advanceDecline": 0.62,
              "volume": 145000000,
              "volatility": 12.4,
              "indexes": [
                {"name": "NIFTY 50", "value": 25143.2, "changePct": 0.42, "change": 105.1}
              ],
              "sectorLeaders": [{"sector": "IT", "averageChangePct": 1.8}],
              "sectorLaggards": [{"sector": "PSU Bank", "averageChangePct": -0.9}],
              "topGainers": [{"symbol": "HAL", "name": "Hindustan Aeronautics", "price": 4100.0, "changePercent": 3.1, "change": 123.2, "volume": 500000}],
              "topLosers": []
            }
            """
        )

        val summary = MarketSummaryDto.parse(json)

        assertEquals("MARKET_HOURS", summary.marketStatus)
        assertEquals("2026-09-24T09:43:21+05:30", summary.asOf)
        assertEquals(1, summary.indexes.size)
        assertEquals("NIFTY 50", summary.indexes[0].name)
        assertEquals(105.1, summary.indexes[0].change!!, 1e-9)
        assertEquals("HAL", summary.topGainers.single().symbol)
        assertTrue(summary.topLosers.isEmpty())
        assertEquals("IT", summary.sectorLeaders.single().sector)
    }

    @Test
    fun missingOptionalMarketSummaryFieldsYieldNulls() {
        val summary = MarketSummaryDto.parse(JSONObject("""{"asOf": "2026-09-24T00:00:00Z", "marketStatus": "CLOSED", "indexes": [], "topGainers": [], "topLosers": [], "sectorLeaders": [], "sectorLaggards": []}"""))

        assertNull(summary.regime)
        assertNull(summary.advanceDecline)
        assertTrue(summary.indexes.isEmpty())
    }

    @Test
    fun parsesLiveQuotesResponse() {
        val json = JSONObject(
            """
            {
              "asOf": "2026-09-24T09:43:21+05:30",
              "marketSession": "MARKET_HOURS",
              "quotes": [
                {"symbol": "RELIANCE", "name": "Reliance Industries", "price": 1452.3, "prevClose": 1440.1, "changePercent": 0.85, "state": "LIVE", "provider": "upstox-v3-ws", "receivedAt": "2026-09-24T09:43:20Z", "ageSeconds": 1}
              ]
            }
            """
        )

        val response = LiveQuotesResponseDto.parse(json)

        assertEquals("MARKET_HOURS", response.marketSession)
        val quote = response.quotes.single()
        assertEquals("RELIANCE", quote.symbol)
        assertEquals("LIVE", quote.state)
        assertEquals(1, quote.ageSeconds)
    }

    @Test
    fun parsesLiveFeedHealth() {
        val json = JSONObject(
            """{"upstoxEnabled": true, "liveFeedEnabled": true, "feedState": "STREAMING", "fallbackActive": false, "cachedInstruments": 2888}"""
        )

        val health = LiveFeedHealthDto.parse(json)

        assertTrue(health.upstoxEnabled)
        assertEquals("STREAMING", health.feedState)
        assertEquals(2888, health.cachedInstruments)
    }

    @Test
    fun parsesIndexHistory() {
        val json = JSONObject(
            """
            {"name": "NIFTY50", "granularity": "DAILY", "points": [
              {"date": "2026-09-23T00:00:00Z", "close": 25100.0, "high": 25200.0, "low": 25000.0, "volume": 123456}
            ]}
            """
        )

        val history = IndexHistoryDto.parse(json)

        assertEquals("NIFTY50", history.name)
        assertEquals(1, history.points.size)
        assertEquals(25100.0, history.points[0].close, 1e-9)
    }

    @Test
    fun parsesSectorOptionList() {
        val json = org.json.JSONArray("""[{"name": "IT", "stockCount": 42}]""")

        val sectors = SectorOptionDto.parseList(json)

        assertEquals(1, sectors.size)
        assertEquals(42, sectors[0].stockCount)
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "com.marksy.os.market.MarketModelsTest"`
Expected: FAIL — `MarketModels.kt` does not exist.

- [ ] **Step 3: Implement the models**

```kotlin
package com.marksy.os.market

import org.json.JSONArray
import org.json.JSONObject

private const val MAX_ITEMS = 50

internal fun JSONObject.textOrNull(name: String): String? =
    if (isNull(name)) null else optString(name).trim().take(500).ifBlank { null }

internal fun JSONObject.doubleOrNull(name: String): Double? =
    if (isNull(name)) null else optDouble(name).takeIf { it.isFinite() }

internal fun JSONObject.longOrNull(name: String): Long? =
    if (isNull(name)) null else optLong(name)

internal fun JSONObject.intOrNull(name: String): Int? =
    if (isNull(name)) null else optInt(name)

internal fun JSONObject.boolOrFalse(name: String): Boolean = optBoolean(name, false)

internal fun JSONArray?.objects(): List<JSONObject> =
    if (this == null) emptyList() else (0 until minOf(length(), MAX_ITEMS)).mapNotNull { optJSONObject(it) }

data class IndexQuoteDto(val name: String, val value: Double, val changePct: Double, val change: Double?) {
    companion object {
        fun parse(json: JSONObject) = IndexQuoteDto(
            name = json.textOrNull("name") ?: "",
            value = json.doubleOrNull("value") ?: 0.0,
            changePct = json.doubleOrNull("changePct") ?: 0.0,
            change = json.doubleOrNull("change")
        )
    }
}

data class MarketMoverDto(val symbol: String, val name: String, val price: Double?, val changePercent: Double, val change: Double?, val volume: Long?) {
    companion object {
        fun parse(json: JSONObject) = MarketMoverDto(
            symbol = json.textOrNull("symbol") ?: "",
            name = json.textOrNull("name") ?: "",
            price = json.doubleOrNull("price"),
            changePercent = json.doubleOrNull("changePercent") ?: 0.0,
            change = json.doubleOrNull("change"),
            volume = json.longOrNull("volume")
        )
    }
}

data class SectorMoveDto(val sector: String, val averageChangePct: Double) {
    companion object {
        fun parse(json: JSONObject) = SectorMoveDto(
            sector = json.textOrNull("sector") ?: "",
            averageChangePct = json.doubleOrNull("averageChangePct") ?: 0.0
        )
    }
}

data class MarketSummaryDto(
    val asOf: String,
    val marketStatus: String,
    val regime: String?,
    val advanceDecline: Double?,
    val volume: Long?,
    val volatility: Double?,
    val indexes: List<IndexQuoteDto>,
    val sectorLeaders: List<SectorMoveDto>,
    val sectorLaggards: List<SectorMoveDto>,
    val topGainers: List<MarketMoverDto>,
    val topLosers: List<MarketMoverDto>
) {
    companion object {
        fun parse(json: JSONObject) = MarketSummaryDto(
            asOf = json.textOrNull("asOf") ?: "",
            marketStatus = json.textOrNull("marketStatus") ?: "UNKNOWN",
            regime = json.textOrNull("regime"),
            advanceDecline = json.doubleOrNull("advanceDecline"),
            volume = json.longOrNull("volume"),
            volatility = json.doubleOrNull("volatility"),
            indexes = json.optJSONArray("indexes").objects().map(IndexQuoteDto::parse),
            sectorLeaders = json.optJSONArray("sectorLeaders").objects().map(SectorMoveDto::parse),
            sectorLaggards = json.optJSONArray("sectorLaggards").objects().map(SectorMoveDto::parse),
            topGainers = json.optJSONArray("topGainers").objects().map(MarketMoverDto::parse),
            topLosers = json.optJSONArray("topLosers").objects().map(MarketMoverDto::parse)
        )
    }
}

data class LiveQuoteDto(
    val symbol: String,
    val name: String?,
    val price: Double?,
    val prevClose: Double?,
    val changePercent: Double?,
    val state: String,
    val provider: String?,
    val receivedAt: String?,
    val ageSeconds: Int?
) {
    companion object {
        fun parse(json: JSONObject) = LiveQuoteDto(
            symbol = json.textOrNull("symbol") ?: "",
            name = json.textOrNull("name"),
            price = json.doubleOrNull("price"),
            prevClose = json.doubleOrNull("prevClose"),
            changePercent = json.doubleOrNull("changePercent"),
            state = json.textOrNull("state") ?: "UNAVAILABLE",
            provider = json.textOrNull("provider"),
            receivedAt = json.textOrNull("receivedAt"),
            ageSeconds = json.intOrNull("ageSeconds")
        )
    }
}

data class LiveQuotesResponseDto(val asOf: String, val marketSession: String, val quotes: List<LiveQuoteDto>) {
    companion object {
        fun parse(json: JSONObject) = LiveQuotesResponseDto(
            asOf = json.textOrNull("asOf") ?: "",
            marketSession = json.textOrNull("marketSession") ?: "UNKNOWN",
            quotes = json.optJSONArray("quotes").objects().map(LiveQuoteDto::parse)
        )
    }
}

data class LiveFeedHealthDto(
    val upstoxEnabled: Boolean,
    val liveFeedEnabled: Boolean,
    val feedState: String,
    val fallbackActive: Boolean,
    val cachedInstruments: Int
) {
    companion object {
        fun parse(json: JSONObject) = LiveFeedHealthDto(
            upstoxEnabled = json.boolOrFalse("upstoxEnabled"),
            liveFeedEnabled = json.boolOrFalse("liveFeedEnabled"),
            feedState = json.textOrNull("feedState") ?: "UNKNOWN",
            fallbackActive = json.boolOrFalse("fallbackActive"),
            cachedInstruments = json.intOrNull("cachedInstruments") ?: 0
        )
    }
}

data class IndexHistoryPointDto(val date: String, val close: Double, val high: Double, val low: Double, val volume: Long) {
    companion object {
        fun parse(json: JSONObject) = IndexHistoryPointDto(
            date = json.textOrNull("date") ?: "",
            close = json.doubleOrNull("close") ?: 0.0,
            high = json.doubleOrNull("high") ?: 0.0,
            low = json.doubleOrNull("low") ?: 0.0,
            volume = json.longOrNull("volume") ?: 0L
        )
    }
}

data class IndexHistoryDto(val name: String, val granularity: String, val points: List<IndexHistoryPointDto>) {
    companion object {
        fun parse(json: JSONObject) = IndexHistoryDto(
            name = json.textOrNull("name") ?: "",
            granularity = json.textOrNull("granularity") ?: "DAILY",
            points = json.optJSONArray("points").objects().map(IndexHistoryPointDto::parse)
        )
    }
}

data class SectorOptionDto(val name: String, val stockCount: Int) {
    companion object {
        fun parse(json: JSONObject) = SectorOptionDto(name = json.textOrNull("name") ?: "", stockCount = json.intOrNull("stockCount") ?: 0)
        fun parseList(array: JSONArray) = array.objects().map(::parse)
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "com.marksy.os.market.MarketModelsTest"`
Expected: PASS (6 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/marksy/os/market/MarketModels.kt app/src/test/java/com/marksy/os/market/MarketModelsTest.kt
git commit -m "feat(market): add market summary/live/health/index-history DTOs and parsers"
```

---

### Task 3: Instrument and active-prediction domain models

**Files:**
- Create: `app/src/main/java/com/marksy/os/market/InstrumentModels.kt`
- Test: `app/src/test/java/com/marksy/os/market/InstrumentModelsTest.kt`

**Interfaces:**
- Consumes: `JSONObject.textOrNull/doubleOrNull/longOrNull/intOrNull/boolOrFalse`, `JSONArray?.objects()` (Task 2, same package, `internal`).
- Produces: `InstrumentLifecycleDto`, `InstrumentMarketDto`, `InstrumentPredictionEntryDto`, `ActivePredictionDto`, `ActivePredictionPageDto` (list + `nextCursor`) — consumed by `RealMarketApiClient` (Task 5) and `MarketIntelligenceRepository` (Task 6).

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.marksy.os.market

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InstrumentModelsTest {
    @Test
    fun parsesInstrumentLifecycle() {
        val json = JSONObject(
            """
            {
              "symbol": "RELIANCE", "companyName": "Reliance Industries", "exchange": "NSE", "sector": "Energy", "isActive": true,
              "market": {"lastClosePrice": 1452.3, "asOfSessionDate": "2026-09-23T18:30:00Z", "freshnessState": "FRESH"},
              "predictionCount": 3, "openPredictionCount": 1,
              "predictions": [
                {
                  "predictionId": 501, "asOf": "2026-09-20T09:15:00Z", "horizonDays": 5,
                  "entryPrice": 1420.0, "targetPrice": 1470.0, "stopLoss": 1390.0,
                  "probabilityAtPublication": 0.71, "confidenceAtPublication": 0.8,
                  "lifecycleState": "ACTIVE", "lifecycleDetail": "Tracking toward target", "isTerminal": false,
                  "currentPrice": 1452.3, "currentReturn": 2.27, "targetProgress": 0.64, "stopProgress": 0.0,
                  "outcomeStatus": "PENDING", "realizedReturnPct": null, "hasResolvedOutcome": false,
                  "evidenceItemCount": 4
                }
              ]
            }
            """
        )

        val instrument = InstrumentLifecycleDto.parse(json)

        assertEquals("RELIANCE", instrument.symbol)
        assertEquals(1452.3, instrument.market.lastClosePrice!!, 1e-9)
        assertEquals("FRESH", instrument.market.freshnessState)
        val prediction = instrument.predictions.single()
        assertEquals(501, prediction.predictionId)
        assertEquals("ACTIVE", prediction.lifecycleState)
        assertFalse(prediction.hasResolvedOutcome)
        assertEquals(4, prediction.evidenceItemCount)
    }

    @Test
    fun instrumentWithNoPredictionsYieldsEmptyList() {
        val json = JSONObject(
            """{"symbol": "NEWCO", "companyName": null, "exchange": "NSE", "sector": null, "isActive": true, "market": {"lastClosePrice": null, "asOfSessionDate": null, "freshnessState": null}, "predictionCount": 0, "openPredictionCount": 0, "predictions": []}"""
        )

        val instrument = InstrumentLifecycleDto.parse(json)

        assertTrue(instrument.predictions.isEmpty())
        assertNull(instrument.market.lastClosePrice)
    }

    @Test
    fun parsesActivePredictionWithCompositeScore() {
        val json = JSONObject(
            """
            {
              "predictionId": 501, "symbol": "RELIANCE", "companyName": "Reliance Industries", "exchange": "NSE",
              "price": 1452.3, "targetPrice": 1470.0, "stopLoss": 1390.0, "horizon": 5, "remainingTradingDays": 2,
              "distanceToTargetPercent": 1.2, "distanceToStopLossPercent": -4.3,
              "confidence": 0.8, "trustScore": 0.77, "trustQuality": "HIGH",
              "status": "OPEN", "lifecycleState": "ACTIONABLE_NOW", "isActionableNow": true, "lifecycleDetail": "Within entry band",
              "entryPrice": 1420.0, "compositeOpportunityScore": 0.641613
            }
            """
        )

        val prediction = ActivePredictionDto.parse(json)

        assertEquals(501, prediction.predictionId)
        assertEquals("ACTIONABLE_NOW", prediction.lifecycleState)
        assertTrue(prediction.isActionableNow)
        assertEquals(0.641613, prediction.compositeOpportunityScore!!, 1e-9)
    }

    @Test
    fun parsesActivePredictionPageWithCursor() {
        val envelope = JSONObject(
            """
            {"data": [{"predictionId": 1, "symbol": "A", "companyName": null, "exchange": "NSE", "price": null, "targetPrice": 10.0, "stopLoss": 8.0, "horizon": 1, "remainingTradingDays": null, "distanceToTargetPercent": null, "distanceToStopLossPercent": null, "confidence": 0.5, "trustScore": null, "trustQuality": null, "status": "OPEN", "lifecycleState": "WATCHING", "isActionableNow": false, "lifecycleDetail": null, "entryPrice": 9.0, "compositeOpportunityScore": 0.5}],
             "meta": {"requestId": "r1", "timestamp": "2026-09-24T00:00:00Z", "pageSize": 25, "nextCursor": "abc123"}}
            """
        )

        val page = ActivePredictionPageDto.parse(envelope)

        assertEquals(1, page.items.size)
        assertEquals("abc123", page.nextCursor)
    }

    @Test
    fun missingNextCursorMeansLastPage() {
        val envelope = JSONObject("""{"data": [], "meta": {"requestId": "r1", "timestamp": "2026-09-24T00:00:00Z", "pageSize": 25}}""")

        val page = ActivePredictionPageDto.parse(envelope)

        assertTrue(page.items.isEmpty())
        assertNull(page.nextCursor)
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "com.marksy.os.market.InstrumentModelsTest"`
Expected: FAIL — `InstrumentModels.kt` does not exist.

- [ ] **Step 3: Implement the models**

```kotlin
package com.marksy.os.market

import org.json.JSONObject

data class InstrumentMarketDto(val lastClosePrice: Double?, val asOfSessionDate: String?, val freshnessState: String?) {
    companion object {
        fun parse(json: JSONObject) = InstrumentMarketDto(
            lastClosePrice = json.doubleOrNull("lastClosePrice"),
            asOfSessionDate = json.textOrNull("asOfSessionDate"),
            freshnessState = json.textOrNull("freshnessState")
        )
    }
}

data class InstrumentPredictionEntryDto(
    val predictionId: Int,
    val asOf: String,
    val horizonDays: Int,
    val entryPrice: Double,
    val targetPrice: Double?,
    val stopLoss: Double?,
    val probabilityAtPublication: Double,
    val confidenceAtPublication: Double,
    val lifecycleState: String,
    val lifecycleDetail: String,
    val isTerminal: Boolean,
    val currentPrice: Double?,
    val currentReturn: Double?,
    val targetProgress: Double?,
    val stopProgress: Double?,
    val outcomeStatus: String,
    val realizedReturnPct: Double?,
    val hasResolvedOutcome: Boolean,
    val evidenceItemCount: Int
) {
    companion object {
        fun parse(json: JSONObject) = InstrumentPredictionEntryDto(
            predictionId = json.intOrNull("predictionId") ?: 0,
            asOf = json.textOrNull("asOf") ?: "",
            horizonDays = json.intOrNull("horizonDays") ?: 0,
            entryPrice = json.doubleOrNull("entryPrice") ?: 0.0,
            targetPrice = json.doubleOrNull("targetPrice"),
            stopLoss = json.doubleOrNull("stopLoss"),
            probabilityAtPublication = json.doubleOrNull("probabilityAtPublication") ?: 0.0,
            confidenceAtPublication = json.doubleOrNull("confidenceAtPublication") ?: 0.0,
            lifecycleState = json.textOrNull("lifecycleState") ?: "UNAVAILABLE",
            lifecycleDetail = json.textOrNull("lifecycleDetail") ?: "",
            isTerminal = json.boolOrFalse("isTerminal"),
            currentPrice = json.doubleOrNull("currentPrice"),
            currentReturn = json.doubleOrNull("currentReturn"),
            targetProgress = json.doubleOrNull("targetProgress"),
            stopProgress = json.doubleOrNull("stopProgress"),
            outcomeStatus = json.textOrNull("outcomeStatus") ?: "PENDING",
            realizedReturnPct = json.doubleOrNull("realizedReturnPct"),
            hasResolvedOutcome = json.boolOrFalse("hasResolvedOutcome"),
            evidenceItemCount = json.intOrNull("evidenceItemCount") ?: 0
        )
    }
}

data class InstrumentLifecycleDto(
    val symbol: String,
    val companyName: String?,
    val exchange: String,
    val sector: String?,
    val isActive: Boolean,
    val market: InstrumentMarketDto,
    val predictionCount: Int,
    val openPredictionCount: Int,
    val predictions: List<InstrumentPredictionEntryDto>
) {
    companion object {
        fun parse(json: JSONObject) = InstrumentLifecycleDto(
            symbol = json.textOrNull("symbol") ?: "",
            companyName = json.textOrNull("companyName"),
            exchange = json.textOrNull("exchange") ?: "",
            sector = json.textOrNull("sector"),
            isActive = json.boolOrFalse("isActive"),
            market = InstrumentMarketDto.parse(json.optJSONObject("market") ?: JSONObject()),
            predictionCount = json.intOrNull("predictionCount") ?: 0,
            openPredictionCount = json.intOrNull("openPredictionCount") ?: 0,
            predictions = json.optJSONArray("predictions").objects().map(InstrumentPredictionEntryDto::parse)
        )
    }
}

data class ActivePredictionDto(
    val predictionId: Int,
    val symbol: String,
    val companyName: String?,
    val exchange: String,
    val price: Double?,
    val targetPrice: Double,
    val stopLoss: Double,
    val horizon: Int,
    val remainingTradingDays: Int?,
    val distanceToTargetPercent: Double?,
    val distanceToStopLossPercent: Double?,
    val confidence: Double,
    val trustScore: Double?,
    val trustQuality: String?,
    val status: String,
    val lifecycleState: String,
    val isActionableNow: Boolean,
    val lifecycleDetail: String?,
    val entryPrice: Double,
    val compositeOpportunityScore: Double?
) {
    companion object {
        fun parse(json: JSONObject) = ActivePredictionDto(
            predictionId = json.intOrNull("predictionId") ?: 0,
            symbol = json.textOrNull("symbol") ?: "",
            companyName = json.textOrNull("companyName"),
            exchange = json.textOrNull("exchange") ?: "",
            price = json.doubleOrNull("price"),
            targetPrice = json.doubleOrNull("targetPrice") ?: 0.0,
            stopLoss = json.doubleOrNull("stopLoss") ?: 0.0,
            horizon = json.intOrNull("horizon") ?: 0,
            remainingTradingDays = json.intOrNull("remainingTradingDays"),
            distanceToTargetPercent = json.doubleOrNull("distanceToTargetPercent"),
            distanceToStopLossPercent = json.doubleOrNull("distanceToStopLossPercent"),
            confidence = json.doubleOrNull("confidence") ?: 0.0,
            trustScore = json.doubleOrNull("trustScore"),
            trustQuality = json.textOrNull("trustQuality"),
            status = json.textOrNull("status") ?: "UNKNOWN",
            lifecycleState = json.textOrNull("lifecycleState") ?: "UNAVAILABLE",
            isActionableNow = json.boolOrFalse("isActionableNow"),
            lifecycleDetail = json.textOrNull("lifecycleDetail"),
            entryPrice = json.doubleOrNull("entryPrice") ?: 0.0,
            compositeOpportunityScore = json.doubleOrNull("compositeOpportunityScore")
        )
    }
}

data class ActivePredictionPageDto(val items: List<ActivePredictionDto>, val nextCursor: String?) {
    companion object {
        fun parse(envelope: JSONObject): ActivePredictionPageDto {
            val items = envelope.optJSONArray("data").objects().map(ActivePredictionDto::parse)
            val nextCursor = envelope.optJSONObject("meta")?.textOrNull("nextCursor")
            return ActivePredictionPageDto(items, nextCursor)
        }
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "com.marksy.os.market.InstrumentModelsTest"`
Expected: PASS (5 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/marksy/os/market/InstrumentModels.kt app/src/test/java/com/marksy/os/market/InstrumentModelsTest.kt
git commit -m "feat(market): add instrument lifecycle and active-prediction DTOs and parsers"
```

---

### Task 4: IPO domain models

**Files:**
- Create: `app/src/main/java/com/marksy/os/market/IpoModels.kt`
- Test: `app/src/test/java/com/marksy/os/market/IpoModelsTest.kt`

**Interfaces:**
- Consumes: Task 2's `JSONObject`/`JSONArray` extension helpers (same package).
- Produces: `IpoValueDto`, `IpoListItemDto`, `IpoDetailDto`, `IpoHistoryEntryDto`, `IpoAttentionItemDto`, `IpoStageCountsDto` — consumed by `RealMarketApiClient` (Task 5) and `MarketIntelligenceRepository` (Task 6).

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.marksy.os.market

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IpoModelsTest {
    @Test
    fun parsesIpoListItemWithHeterogeneousValueEnvelope() {
        val json = JSONObject(
            """
            {
              "id": "ipo-42", "companyName": "Acme Robotics", "issueName": "Acme Robotics IPO", "isSme": false, "sector": "Industrials", "stage": "OPEN",
              "opensOn": {"state": "AVAILABLE", "value": "2026-10-01", "asOf": "2026-09-20T00:00:00Z"},
              "closesOn": {"state": "AVAILABLE", "value": "2026-10-03", "asOf": "2026-09-20T00:00:00Z"},
              "listsOn": {"state": "MISSING", "value": null, "asOf": null},
              "terms": {
                "priceBand": {"state": "AVAILABLE", "value": {"low": 210, "high": 225}, "asOf": "2026-09-20T00:00:00Z"},
                "lotSize": {"state": "AVAILABLE", "value": 65, "asOf": "2026-09-20T00:00:00Z"},
                "issueSizeCrore": {"state": "AVAILABLE", "value": 1200.5, "asOf": "2026-09-20T00:00:00Z"}
              }
            }
            """
        )

        val ipo = IpoListItemDto.parse(json)

        assertEquals("ipo-42", ipo.id)
        assertEquals("OPEN", ipo.stage)
        assertEquals("AVAILABLE", ipo.opensOn?.state)
        assertEquals("MISSING", ipo.listsOn?.state)
        assertNull(ipo.listsOn?.value)
        assertEquals(65, (ipo.terms?.lotSize?.value as? Number)?.toInt())
    }

    @Test
    fun stageIsNullWhenNeverEvaluated() {
        val ipo = IpoListItemDto.parse(JSONObject("""{"id": "ipo-9", "companyName": "Unknown Co", "isSme": true}"""))

        assertNull(ipo.stage)
    }

    @Test
    fun parsesIpoDetail() {
        val json = JSONObject(
            """
            {
              "summary": {"id": "ipo-42", "companyName": "Acme Robotics", "isSme": false},
              "riskEngineRan": true,
              "riskRun": {"status": "COMPLETE", "ranAt": "2026-09-20T00:00:00Z", "findingsCount": 2}
            }
            """
        )

        val detail = IpoDetailDto.parse(json)

        assertEquals("ipo-42", detail.summary.id)
        assertTrue(detail.riskEngineRan)
        assertEquals(2, detail.riskRun?.findingsCount)
    }

    @Test
    fun parsesIpoHistoryEntryList() {
        val array = org.json.JSONArray(
            """[{"predictedAt": "2026-09-15T00:00:00Z", "decision": "POSITIVE", "expectedReturnPercent": {"state": "AVAILABLE", "value": 12.5}}]"""
        )

        val history = IpoHistoryEntryDto.parseList(array)

        assertEquals(1, history.size)
        assertEquals("POSITIVE", history[0].decision)
        assertEquals(12.5, (history[0].expectedReturnPercent?.value as? Number)?.toDouble()!!, 1e-9)
    }

    @Test
    fun parsesAttentionItems() {
        val array = org.json.JSONArray("""[{"ipoId": "ipo-42", "companyName": "Acme Robotics", "kind": "CLOSING_SOON", "detail": "Closes in 1 day"}]""")

        val items = IpoAttentionItemDto.parseList(array)

        assertEquals("CLOSING_SOON", items.single().kind)
    }

    @Test
    fun parsesStageCountsAndExposesAllStageKeys() {
        val json = JSONObject("""{"byStage": {"UPCOMING": 3, "OPEN": 1, "CLOSED": 5, "LISTED": 12}, "unevaluated": 2, "total": 23}""")

        val counts = IpoStageCountsDto.parse(json)

        assertEquals(setOf("UPCOMING", "OPEN", "CLOSED", "LISTED"), counts.byStage.keys)
        assertEquals(23, counts.total)
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "com.marksy.os.market.IpoModelsTest"`
Expected: FAIL — `IpoModels.kt` does not exist.

- [ ] **Step 3: Implement the models**

```kotlin
package com.marksy.os.market

import org.json.JSONArray
import org.json.JSONObject

/** The one envelope every IPO fact travels in (`IpoValueOut` server-side): `value`'s shape
 * varies by field (number, string, object) by the server contract's own design. */
data class IpoValueDto(val state: String, val value: Any?, val asOf: String?) {
    companion object {
        fun parse(json: JSONObject?): IpoValueDto? {
            if (json == null) return null
            val value = if (json.isNull("value")) null else json.opt("value")
            return IpoValueDto(state = json.textOrNull("state") ?: "UNAVAILABLE", value = value, asOf = json.textOrNull("asOf"))
        }
    }
}

data class IpoTermsDto(val priceBand: IpoValueDto?, val lotSize: IpoValueDto?, val issueSizeCrore: IpoValueDto?) {
    companion object {
        fun parse(json: JSONObject?): IpoTermsDto? {
            if (json == null) return null
            return IpoTermsDto(
                priceBand = IpoValueDto.parse(json.optJSONObject("priceBand")),
                lotSize = IpoValueDto.parse(json.optJSONObject("lotSize")),
                issueSizeCrore = IpoValueDto.parse(json.optJSONObject("issueSizeCrore"))
            )
        }
    }
}

data class IpoListItemDto(
    val id: String,
    val companyName: String,
    val issueName: String?,
    val isSme: Boolean,
    val sector: String?,
    val stage: String?,
    val opensOn: IpoValueDto?,
    val closesOn: IpoValueDto?,
    val listsOn: IpoValueDto?,
    val terms: IpoTermsDto?
) {
    companion object {
        fun parse(json: JSONObject) = IpoListItemDto(
            id = json.textOrNull("id") ?: "",
            companyName = json.textOrNull("companyName") ?: "",
            issueName = json.textOrNull("issueName"),
            isSme = json.boolOrFalse("isSme"),
            sector = json.textOrNull("sector"),
            stage = json.textOrNull("stage"),
            opensOn = IpoValueDto.parse(json.optJSONObject("opensOn")),
            closesOn = IpoValueDto.parse(json.optJSONObject("closesOn")),
            listsOn = IpoValueDto.parse(json.optJSONObject("listsOn")),
            terms = IpoTermsDto.parse(json.optJSONObject("terms"))
        )
        fun parseList(array: JSONArray?) = array.objects().map(::parse)
    }
}

data class IpoRiskRunDto(val status: String, val ranAt: String?, val findingsCount: Int) {
    companion object {
        fun parse(json: JSONObject?): IpoRiskRunDto? {
            if (json == null) return null
            return IpoRiskRunDto(status = json.textOrNull("status") ?: "UNKNOWN", ranAt = json.textOrNull("ranAt"), findingsCount = json.intOrNull("findingsCount") ?: 0)
        }
    }
}

data class IpoDetailDto(val summary: IpoListItemDto, val riskEngineRan: Boolean, val riskRun: IpoRiskRunDto?) {
    companion object {
        fun parse(json: JSONObject) = IpoDetailDto(
            summary = IpoListItemDto.parse(json.optJSONObject("summary") ?: JSONObject()),
            riskEngineRan = json.boolOrFalse("riskEngineRan"),
            riskRun = IpoRiskRunDto.parse(json.optJSONObject("riskRun"))
        )
    }
}

data class IpoHistoryEntryDto(val predictedAt: String, val decision: String?, val expectedReturnPercent: IpoValueDto?) {
    companion object {
        fun parse(json: JSONObject) = IpoHistoryEntryDto(
            predictedAt = json.textOrNull("predictedAt") ?: "",
            decision = json.textOrNull("decision"),
            expectedReturnPercent = IpoValueDto.parse(json.optJSONObject("expectedReturnPercent"))
        )
        fun parseList(array: JSONArray?) = array.objects().map(::parse)
    }
}

data class IpoAttentionItemDto(val ipoId: String, val companyName: String, val kind: String, val detail: String) {
    companion object {
        fun parse(json: JSONObject) = IpoAttentionItemDto(
            ipoId = json.textOrNull("ipoId") ?: "",
            companyName = json.textOrNull("companyName") ?: "",
            kind = json.textOrNull("kind") ?: "",
            detail = json.textOrNull("detail") ?: ""
        )
        fun parseList(array: JSONArray?) = array.objects().map(::parse)
    }
}

data class IpoStageCountsDto(val byStage: Map<String, Int>, val total: Int) {
    companion object {
        fun parse(json: JSONObject): IpoStageCountsDto {
            val byStage = json.optJSONObject("byStage")
            val map = byStage?.keys()?.asSequence()?.associateWith { byStage.optInt(it) } ?: emptyMap()
            return IpoStageCountsDto(byStage = map, total = json.intOrNull("total") ?: 0)
        }
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "com.marksy.os.market.IpoModelsTest"`
Expected: PASS (6 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/marksy/os/market/IpoModels.kt app/src/test/java/com/marksy/os/market/IpoModelsTest.kt
git commit -m "feat(market): add IPO list/detail/history/attention/stage-count DTOs and parsers"
```

---

### Task 5: MarketApiClient (interface + HTTP implementation)

**Files:**
- Create: `app/src/main/java/com/marksy/os/market/MarketApiClient.kt`
- Modify: `app/src/main/java/com/marksy/os/gateway/MarksyGatewayProvider.kt`
- Test: `app/src/test/java/com/marksy/os/market/RealMarketApiClientTest.kt`

**Interfaces:**
- Consumes: all DTOs from Tasks 2-4; `SecureCredentialStore.getMarketApiKey()`/`getBaseUrl()` (Task 1); `BuildConfig.MARKSY_API_BASE_URL`.
- Produces: `interface MarketApiClient` with one suspend function per endpoint used by the repository (Task 6): `marketSummary()`, `liveQuotes(symbols: List<String>?)`, `liveFeedHealth()`, `indexHistory(name: String, range: String)`, `sectors()`, `instrument(symbol: String)`, `activePredictions(cursor: String?)`, `activePrediction(id: Int)`, `ipos(stage: String?, query: String?)`, `ipoAttention(limit: Int)`, `ipoStageCounts()`, `ipoDetail(id: String)`, `ipoHistory(id: String)`. `RealMarketApiClient(apiKey: String, baseUrl: String) : MarketApiClient`. `MarksyGatewayProvider.marketIntelligenceClient(): MarketApiClient?` (null when unconfigured).

- [ ] **Step 1: Write the failing test**

`RealMarketApiClient`'s HTTP transport itself is not unit-tested at the socket level, matching this codebase's existing convention (`MarksyTipsApiClient`'s `execute()` has no dedicated test either — only its pure helpers and callers are tested). The one pure, synchronous, network-free piece of behavior worth pinning here is HTTPS enforcement on construction, mirroring `MarksyTipsApiClient`'s `normalizeBaseUrl`:

```kotlin
package com.marksy.os.market

import org.junit.Assert.assertThrows
import org.junit.Test

class RealMarketApiClientTest {
    @Test
    fun rejectsNonHttpsBaseUrl() {
        assertThrows(IllegalArgumentException::class.java) {
            RealMarketApiClient(apiKey = "k", baseUrl = "http://insecure.example.com/api/v1")
        }
    }

    @Test
    fun rejectsBlankApiKeyAtConstruction() {
        assertThrows(IllegalArgumentException::class.java) {
            RealMarketApiClient(apiKey = "  ", baseUrl = "https://marksy.indoulia.com/api/v1")
        }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.marksy.os.market.RealMarketApiClientTest"`
Expected: FAIL — `MarketApiClient.kt` does not exist.

- [ ] **Step 3: Implement the client**

```kotlin
package com.marksy.os.market

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder

interface MarketApiClient {
    suspend fun marketSummary(): MarketSummaryDto
    suspend fun liveQuotes(symbols: List<String>? = null): LiveQuotesResponseDto
    suspend fun liveFeedHealth(): LiveFeedHealthDto
    suspend fun indexHistory(name: String, range: String): IndexHistoryDto
    suspend fun sectors(): List<SectorOptionDto>
    suspend fun instrument(symbol: String): InstrumentLifecycleDto
    suspend fun activePredictions(cursor: String? = null): ActivePredictionPageDto
    suspend fun activePrediction(id: Int): ActivePredictionDto
    suspend fun ipos(stage: String? = null, query: String? = null): List<IpoListItemDto>
    suspend fun ipoAttention(limit: Int = 4): List<IpoAttentionItemDto>
    suspend fun ipoStageCounts(): IpoStageCountsDto
    suspend fun ipoDetail(id: String): IpoDetailDto
    suspend fun ipoHistory(id: String): List<IpoHistoryEntryDto>
}

class MarketApiException(message: String) : IOException(message)

/** HTTPS-enforced, `X-API-Key`-authenticated client for the `marksy-api` market-intelligence
 * surface, structurally mirroring `MarksyTipsApiClient` (raw `HttpURLConnection`, `{data, meta}`
 * envelope). Uses a separate scoped credential — never the EPIC-803 tips integration key. */
class RealMarketApiClient(apiKey: String, baseUrl: String) : MarketApiClient {
    private val apiKey: String = apiKey.trim().also { require(it.isNotBlank()) { "Market API key must not be blank" } }
    private val base: String = normalizeBaseUrl(baseUrl)

    override suspend fun marketSummary(): MarketSummaryDto =
        MarketSummaryDto.parse(getData("$base/market/summary"))

    override suspend fun liveQuotes(symbols: List<String>?): LiveQuotesResponseDto {
        val query = symbols?.takeIf { it.isNotEmpty() }?.joinToString(",")?.let { "?symbols=${encode(it)}" } ?: ""
        return LiveQuotesResponseDto.parse(getData("$base/market/live$query"))
    }

    override suspend fun liveFeedHealth(): LiveFeedHealthDto =
        LiveFeedHealthDto.parse(getData("$base/market/live/health"))

    override suspend fun indexHistory(name: String, range: String): IndexHistoryDto =
        IndexHistoryDto.parse(getData("$base/market/indices/${encode(name)}/history?range=${encode(range)}"))

    override suspend fun sectors(): List<SectorOptionDto> =
        SectorOptionDto.parseList(getDataArray("$base/market/sectors"))

    override suspend fun instrument(symbol: String): InstrumentLifecycleDto =
        InstrumentLifecycleDto.parse(getData("$base/instruments/${encode(symbol)}"))

    override suspend fun activePredictions(cursor: String?): ActivePredictionPageDto {
        val query = cursor?.let { "?cursor=${encode(it)}" } ?: ""
        return ActivePredictionPageDto.parse(getEnvelope("$base/predictions/active$query"))
    }

    override suspend fun activePrediction(id: Int): ActivePredictionDto =
        ActivePredictionDto.parse(getData("$base/predictions/active/$id"))

    override suspend fun ipos(stage: String?, query: String?): List<IpoListItemDto> {
        val params = listOfNotNull(stage?.let { "stage=${encode(it)}" }, query?.let { "q=${encode(it)}" })
        val suffix = if (params.isEmpty()) "" else "?" + params.joinToString("&")
        return IpoListItemDto.parseList(getDataArray("$base/ipos$suffix"))
    }

    override suspend fun ipoAttention(limit: Int): List<IpoAttentionItemDto> =
        IpoAttentionItemDto.parseList(getDataArray("$base/ipos/attention?limit=$limit"))

    override suspend fun ipoStageCounts(): IpoStageCountsDto =
        IpoStageCountsDto.parse(getData("$base/ipos/counts"))

    override suspend fun ipoDetail(id: String): IpoDetailDto =
        IpoDetailDto.parse(getData("$base/ipos/${encode(id)}"))

    override suspend fun ipoHistory(id: String): List<IpoHistoryEntryDto> =
        IpoHistoryEntryDto.parseList(getDataArray("$base/ipos/${encode(id)}/history"))

    private suspend fun getEnvelope(url: String): JSONObject = execute(url)
    private suspend fun getData(url: String): JSONObject = execute(url).getJSONObject("data")
    private suspend fun getDataArray(url: String): org.json.JSONArray = execute(url).getJSONArray("data")

    private suspend fun execute(url: String): JSONObject = withContext(Dispatchers.IO) {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = false
            setRequestProperty("Accept", "application/json")
            setRequestProperty("X-API-Key", apiKey)
            doInput = true
        }
        try {
            val code = connection.responseCode
            if (code in REDIRECT_CODES) throw MarketApiException("Marksy Market API redirect refused")
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.bufferedReader()?.use { reader ->
                val buffer = CharArray(4096)
                val builder = StringBuilder()
                while (true) {
                    val read = reader.read(buffer)
                    if (read < 0) break
                    builder.append(buffer, 0, read)
                    if (builder.length > MAX_RESPONSE_CHARS) throw IOException("Marksy Market API response exceeded the safety limit")
                }
                builder.toString()
            }.orEmpty()
            if (code !in 200..299) {
                val detail = errorDetail(response)
                if (code in 400..499) throw MarketApiException("Marksy Market API returned HTTP $code$detail")
                throw IOException("Marksy Market API returned HTTP $code$detail")
            }
            return@withContext JSONObject(response).also { envelope ->
                if (!envelope.has("data") || !envelope.has("meta")) throw IOException("Marksy Market API returned an invalid response envelope")
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } finally {
            connection.disconnect()
        }
    }

    private fun errorDetail(response: String): String = try {
        val envelope = JSONObject(response)
        val error = envelope.optJSONObject("error")
        val message = error?.optString("message")?.takeIf { it.isNotBlank() } ?: envelope.optString("message").takeIf { it.isNotBlank() }
        message?.let { ": ${it.take(300)}" } ?: ""
    } catch (_: Exception) { "" }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    private companion object {
        const val CONNECT_TIMEOUT_MS = 10_000
        const val READ_TIMEOUT_MS = 20_000
        const val MAX_RESPONSE_CHARS = 200_000
        val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)

        fun normalizeBaseUrl(value: String): String {
            val trimmed = value.trim().trimEnd('/')
            require(trimmed.isNotBlank()) { "MARKET_API_BASE_URL must not be blank" }
            val uri = runCatching { URI(trimmed) }.getOrNull() ?: throw IllegalArgumentException("MARKET_API_BASE_URL is not a valid URL")
            require(uri.scheme.equals("https", ignoreCase = true)) { "MARKET_API_BASE_URL must use HTTPS" }
            require(uri.host?.isNotBlank() == true) { "MARKET_API_BASE_URL must include a host" }
            return trimmed
        }
    }
}
```

Add the construction point to `MarksyGatewayProvider`:

```kotlin
    /** Null until the market API key is provisioned; every Market screen must degrade to
     * its own Unavailable state rather than crash when this is null. */
    fun marketIntelligenceClient(): com.marksy.os.market.MarketApiClient? {
        val store = SecureCredentialStore(AppContext.get())
        val key = store.getMarketApiKey() ?: return null
        val baseUrl = (store.getBaseUrl() ?: BuildConfig.MARKSY_API_BASE_URL).trimEnd('/')
        return runCatching { com.marksy.os.market.RealMarketApiClient(key, baseUrl) as com.marksy.os.market.MarketApiClient }.getOrNull()
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.marksy.os.market.RealMarketApiClientTest"`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/marksy/os/market/MarketApiClient.kt app/src/main/java/com/marksy/os/gateway/MarksyGatewayProvider.kt app/src/test/java/com/marksy/os/market/RealMarketApiClientTest.kt
git commit -m "feat(market): add MarketApiClient HTTP transport and provider wiring"
```

---

### Task 6: MarketDataState + MarketIntelligenceRepository

**Files:**
- Create: `app/src/main/java/com/marksy/os/market/MarketDataState.kt`
- Create: `app/src/main/java/com/marksy/os/market/MarketIntelligenceRepository.kt`
- Modify: `app/src/main/java/com/marksy/os/data/MarksyContainer.kt`
- Test: `app/src/test/java/com/marksy/os/market/MarketIntelligenceRepositoryTest.kt`

**Interfaces:**
- Consumes: `MarketApiClient` (Task 5, injected — a fake implements it in tests).
- Produces: `sealed class MarketDataState<out T>` (`Loading`, `Loaded<T>(value)`, `Stale<T>(value, ageSeconds)`, `Unavailable`, `Error(message)`, `Empty`); `class MarketIntelligenceRepository(private val client: MarketApiClient?)` with `suspend fun overview(): MarketDataState<MarketSummaryDto>`, `suspend fun liveQuotes(symbols: List<String>? = null): MarketDataState<LiveQuotesResponseDto>`, `suspend fun instrument(symbol: String): MarketDataState<InstrumentLifecycleDto>`, `suspend fun activePredictions(cursor: String? = null): MarketDataState<ActivePredictionPageDto>`, `suspend fun ipos(stage: String? = null, query: String? = null): MarketDataState<List<IpoListItemDto>>`, `suspend fun ipoStageCounts(): MarketDataState<IpoStageCountsDto>`, `suspend fun ipoDetail(id: String): MarketDataState<IpoDetailDto>`, `suspend fun liveFeedHealth(): MarketDataState<LiveFeedHealthDto>`. Consumed by all four screens (Tasks 7-9) and `MarksyContainer.marketIntelligence(context)`.

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.marksy.os.market

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeMarketApiClient(
    private val summary: MarketSummaryDto? = null,
    private val summaryError: Throwable? = null,
    private val liveQuotesResponse: LiveQuotesResponseDto? = null,
    private val predictionPage: ActivePredictionPageDto? = null,
    private val ipoList: List<IpoListItemDto> = emptyList(),
    private val health: LiveFeedHealthDto? = null
) : MarketApiClient {
    override suspend fun marketSummary(): MarketSummaryDto = summaryError?.let { throw it } ?: summary!!
    override suspend fun liveQuotes(symbols: List<String>?): LiveQuotesResponseDto = liveQuotesResponse!!
    override suspend fun liveFeedHealth(): LiveFeedHealthDto = health!!
    override suspend fun indexHistory(name: String, range: String): IndexHistoryDto = throw NotImplementedError()
    override suspend fun sectors(): List<SectorOptionDto> = emptyList()
    override suspend fun instrument(symbol: String): InstrumentLifecycleDto = throw NotImplementedError()
    override suspend fun activePredictions(cursor: String?): ActivePredictionPageDto = predictionPage!!
    override suspend fun activePrediction(id: Int): ActivePredictionDto = throw NotImplementedError()
    override suspend fun ipos(stage: String?, query: String?): List<IpoListItemDto> = ipoList
    override suspend fun ipoAttention(limit: Int): List<IpoAttentionItemDto> = emptyList()
    override suspend fun ipoStageCounts(): IpoStageCountsDto = throw NotImplementedError()
    override suspend fun ipoDetail(id: String): IpoDetailDto = throw NotImplementedError()
    override suspend fun ipoHistory(id: String): List<IpoHistoryEntryDto> = emptyList()
}

private fun summary(marketStatus: String = "MARKET_HOURS") = MarketSummaryDto(
    asOf = "2026-09-24T09:43:21+05:30", marketStatus = marketStatus, regime = null, advanceDecline = null,
    volume = null, volatility = null, indexes = emptyList(), sectorLeaders = emptyList(), sectorLaggards = emptyList(),
    topGainers = emptyList(), topLosers = emptyList()
)

class MarketIntelligenceRepositoryTest {
    @Test
    fun unconfiguredClientYieldsUnavailable() = runBlocking {
        val repository = MarketIntelligenceRepository(client = null)

        val state = repository.overview()

        assertTrue(state is MarketDataState.Unavailable)
    }

    @Test
    fun successfulFetchYieldsLoaded() = runBlocking {
        val repository = MarketIntelligenceRepository(FakeMarketApiClient(summary = summary()))

        val state = repository.overview()

        assertTrue(state is MarketDataState.Loaded)
        assertEquals("MARKET_HOURS", (state as MarketDataState.Loaded).value.marketStatus)
    }

    @Test
    fun thrownIOExceptionYieldsError() = runBlocking {
        val repository = MarketIntelligenceRepository(FakeMarketApiClient(summaryError = java.io.IOException("boom")))

        val state = repository.overview()

        assertTrue(state is MarketDataState.Error)
    }

    @Test
    fun marketApiExceptionYieldsError() = runBlocking {
        val repository = MarketIntelligenceRepository(FakeMarketApiClient(summaryError = MarketApiException("HTTP 422")))

        val state = repository.overview()

        assertTrue(state is MarketDataState.Error)
        assertTrue((state as MarketDataState.Error).message.contains("422"))
    }

    @Test
    fun emptyPredictionPageYieldsEmpty() = runBlocking {
        val repository = MarketIntelligenceRepository(FakeMarketApiClient(predictionPage = ActivePredictionPageDto(items = emptyList(), nextCursor = null)))

        val state = repository.activePredictions()

        assertTrue(state is MarketDataState.Empty)
    }

    @Test
    fun nonEmptyPredictionPageYieldsLoaded() = runBlocking {
        val page = ActivePredictionPageDto(
            items = listOf(
                ActivePredictionDto(
                    predictionId = 1, symbol = "A", companyName = null, exchange = "NSE", price = null,
                    targetPrice = 10.0, stopLoss = 8.0, horizon = 1, remainingTradingDays = null,
                    distanceToTargetPercent = null, distanceToStopLossPercent = null, confidence = 0.5,
                    trustScore = null, trustQuality = null, status = "OPEN", lifecycleState = "WATCHING",
                    isActionableNow = false, lifecycleDetail = null, entryPrice = 9.0, compositeOpportunityScore = 0.5
                )
            ),
            nextCursor = "next"
        )
        val repository = MarketIntelligenceRepository(FakeMarketApiClient(predictionPage = page))

        val state = repository.activePredictions()

        assertTrue(state is MarketDataState.Loaded)
        assertEquals(1, (state as MarketDataState.Loaded).value.items.size)
    }

    @Test
    fun emptyIpoListYieldsEmpty() = runBlocking {
        val repository = MarketIntelligenceRepository(FakeMarketApiClient(ipoList = emptyList()))

        val state = repository.ipos(stage = "OPEN")

        assertTrue(state is MarketDataState.Empty)
    }

    @Test
    fun liveFeedHealthIsLoadedWhenStreamingAndNotFallenBack() = runBlocking {
        val health = LiveFeedHealthDto(upstoxEnabled = true, liveFeedEnabled = true, feedState = "STREAMING", fallbackActive = false, cachedInstruments = 2888)
        val repository = MarketIntelligenceRepository(FakeMarketApiClient(health = health))

        val state = repository.liveFeedHealth()

        assertTrue(state is MarketDataState.Loaded)
        assertEquals("STREAMING", (state as MarketDataState.Loaded).value.feedState)
    }

    @Test
    fun liveFeedHealthIsStaleWhenFeedNotStreaming() = runBlocking {
        val health = LiveFeedHealthDto(upstoxEnabled = true, liveFeedEnabled = true, feedState = "DEGRADED", fallbackActive = false, cachedInstruments = 2888)
        val repository = MarketIntelligenceRepository(FakeMarketApiClient(health = health))

        val state = repository.liveFeedHealth()

        assertTrue(state is MarketDataState.Stale)
    }

    @Test
    fun liveFeedHealthIsStaleWhenFallbackIsActiveEvenIfFeedStateSaysStreaming() = runBlocking {
        val health = LiveFeedHealthDto(upstoxEnabled = true, liveFeedEnabled = true, feedState = "STREAMING", fallbackActive = true, cachedInstruments = 2888)
        val repository = MarketIntelligenceRepository(FakeMarketApiClient(health = health))

        val state = repository.liveFeedHealth()

        assertTrue(state is MarketDataState.Stale)
    }

    @Test
    fun liveFeedHealthIsUnavailableWhenNotConfigured() = runBlocking {
        val repository = MarketIntelligenceRepository(client = null)

        val state = repository.liveFeedHealth()

        assertTrue(state is MarketDataState.Unavailable)
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "com.marksy.os.market.MarketIntelligenceRepositoryTest"`
Expected: FAIL — `MarketDataState`/`MarketIntelligenceRepository` do not exist.

- [ ] **Step 3: Implement the state type and repository**

```kotlin
package com.marksy.os.market

sealed class MarketDataState<out T> {
    data object Loading : MarketDataState<Nothing>()
    data class Loaded<T>(val value: T) : MarketDataState<T>()
    data class Stale<T>(val value: T, val ageSeconds: Int?) : MarketDataState<T>()
    data object Unavailable : MarketDataState<Nothing>()
    data class Error(val message: String) : MarketDataState<Nothing>()
    data object Empty : MarketDataState<Nothing>()
}
```

```kotlin
package com.marksy.os.market

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/** Every Market screen reads through this repository, never `MarketApiClient` directly, so
 * "not configured" / network / server-error handling lives in one place. Separate from the
 * existing `MarketRepository` (Trading tab's ad-hoc `/dashboard/snapshot` client) by design. */
class MarketIntelligenceRepository(private val client: MarketApiClient?) {
    suspend fun overview(): MarketDataState<MarketSummaryDto> = fetch { it.marketSummary() }

    suspend fun liveQuotes(symbols: List<String>? = null): MarketDataState<LiveQuotesResponseDto> =
        fetch { it.liveQuotes(symbols) }

    suspend fun instrument(symbol: String): MarketDataState<InstrumentLifecycleDto> = fetch { it.instrument(symbol) }

    suspend fun activePredictions(cursor: String? = null): MarketDataState<ActivePredictionPageDto> =
        fetch(emptyCheck = { it.items.isEmpty() }) { it.activePredictions(cursor) }

    suspend fun ipos(stage: String? = null, query: String? = null): MarketDataState<List<IpoListItemDto>> =
        fetch(emptyCheck = { it.isEmpty() }) { it.ipos(stage, query) }

    suspend fun ipoStageCounts(): MarketDataState<IpoStageCountsDto> = fetch { it.ipoStageCounts() }

    suspend fun ipoDetail(id: String): MarketDataState<IpoDetailDto> = fetch { it.ipoDetail(id) }

    /** The Overview freshness footer's source. `Stale` here means the feed's own reported
     * `feedState`/`fallbackActive` say it is degraded — never a client-invented age threshold. */
    suspend fun liveFeedHealth(): MarketDataState<LiveFeedHealthDto> {
        val activeClient = client ?: return MarketDataState.Unavailable
        return withContext(Dispatchers.IO) {
            try {
                val value = activeClient.liveFeedHealth()
                if (value.feedState != "STREAMING" || value.fallbackActive) MarketDataState.Stale(value, ageSeconds = null)
                else MarketDataState.Loaded(value)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: IOException) {
                MarketDataState.Error(error.message ?: "Market feed health unavailable")
            }
        }
    }

    private suspend fun <T> fetch(
        emptyCheck: (T) -> Boolean = { false },
        call: suspend (MarketApiClient) -> T
    ): MarketDataState<T> {
        val activeClient = client ?: return MarketDataState.Unavailable
        return withContext(Dispatchers.IO) {
            try {
                val value = call(activeClient)
                if (emptyCheck(value)) MarketDataState.Empty else MarketDataState.Loaded(value)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: IOException) {
                MarketDataState.Error(error.message ?: "Market data unavailable")
            }
        }
    }
}
```

Add the accessor to `MarksyContainer` (mirroring the existing `fun ask(context): AskRepository`-style accessors):

```kotlin
    fun marketIntelligence(context: Context): com.marksy.os.market.MarketIntelligenceRepository =
        com.marksy.os.market.MarketIntelligenceRepository(com.marksy.os.gateway.MarksyGatewayProvider.marketIntelligenceClient())
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "com.marksy.os.market.MarketIntelligenceRepositoryTest"`
Expected: PASS (11 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/marksy/os/market/MarketDataState.kt app/src/main/java/com/marksy/os/market/MarketIntelligenceRepository.kt app/src/main/java/com/marksy/os/data/MarksyContainer.kt app/src/test/java/com/marksy/os/market/MarketIntelligenceRepositoryTest.kt
git commit -m "feat(market): add MarketDataState and MarketIntelligenceRepository"
```

---

### Task 7: Market tab shell + Overview screen

**Files:**
- Create: `app/src/main/java/com/marksy/os/ui/MarketScreen.kt`
- Create: `app/src/main/java/com/marksy/os/ui/MarketOverviewScreen.kt`
- Modify: `app/src/main/java/com/marksy/os/MainActivity.kt` (`tabs` list around line 246, `Scaffold` `when` block around line 285)
- Test: `app/src/test/java/com/marksy/os/ui/MarketOverviewScreenTest.kt`

**Interfaces:**
- Consumes: `MarketDataState<MarketSummaryDto>` (Task 6), `MarksyTheme` tokens (existing).
- Produces: `MarketOverviewScreen(state: MarketDataState<MarketSummaryDto>, padding: PaddingValues)` composable; `MarketScreen(repository: MarketIntelligenceRepository, padding: PaddingValues, onOpenSymbol: (String) -> Unit)` composable with internal sub-tab state, consumed by `MainActivity`.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.marksy.os.ui

import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.marksy.os.market.MarketDataState
import com.marksy.os.market.MarketMoverDto
import com.marksy.os.market.MarketSummaryDto
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MarketOverviewScreenTest {
    @get:Rule val compose = createComposeRule()

    private fun summary(gainers: List<MarketMoverDto> = emptyList()) = MarketSummaryDto(
        asOf = "2026-09-24T09:43:21+05:30", marketStatus = "MARKET_HOURS", regime = "BULLISH_LOW_VOL",
        advanceDecline = null, volume = null, volatility = null,
        indexes = listOf(com.marksy.os.market.IndexQuoteDto("NIFTY 50", 25143.2, 0.42, 105.1)),
        sectorLeaders = emptyList(), sectorLaggards = emptyList(), topGainers = gainers, topLosers = emptyList()
    )

    @Test
    fun loadedStateShowsIndexAndMarketStatus() {
        compose.setContent { MarketOverviewScreen(state = MarketDataState.Loaded(summary()), padding = androidx.compose.foundation.layout.PaddingValues()) }

        compose.onNodeWithText("MARKET_HOURS").assertExists()
        compose.onNodeWithText("NIFTY 50").assertExists()
    }

    @Test
    fun unavailableStateShowsExplicitMessageNotZero() {
        compose.setContent { MarketOverviewScreen(state = MarketDataState.Unavailable, padding = androidx.compose.foundation.layout.PaddingValues()) }

        compose.onNodeWithText("Market Intelligence is not configured", substring = true).assertExists()
    }

    @Test
    fun errorStateShowsMessage() {
        compose.setContent { MarketOverviewScreen(state = MarketDataState.Error("HTTP 500"), padding = androidx.compose.foundation.layout.PaddingValues()) }

        compose.onNodeWithText("HTTP 500", substring = true).assertExists()
    }

    @Test
    fun loadingStateShowsChecking() {
        compose.setContent { MarketOverviewScreen(state = MarketDataState.Loading, padding = androidx.compose.foundation.layout.PaddingValues()) }

        compose.onNodeWithText("Checking market", substring = true).assertExists()
    }

    @Test
    fun degradedFeedHealthShowsStaleFooterNotLive() {
        val health = MarketDataState.Stale(
            com.marksy.os.market.LiveFeedHealthDto(upstoxEnabled = true, liveFeedEnabled = true, feedState = "DEGRADED", fallbackActive = true, cachedInstruments = 2888),
            ageSeconds = null
        )

        compose.setContent { MarketOverviewScreen(state = MarketDataState.Loaded(summary()), padding = androidx.compose.foundation.layout.PaddingValues(), health = health) }

        compose.onNodeWithText("degraded", substring = true).assertExists()
        compose.onNodeWithText("fallback", substring = true).assertExists()
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.marksy.os.ui.MarketOverviewScreenTest"`
Expected: FAIL — `MarketOverviewScreen` does not exist.

- [ ] **Step 3: Implement `MarketOverviewScreen`**

```kotlin
package com.marksy.os.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.marksy.os.EmptyState
import com.marksy.os.market.MarketDataState
import com.marksy.os.market.MarketMoverDto
import com.marksy.os.market.MarketSummaryDto

@Composable
fun MarketOverviewScreen(
    state: MarketDataState<MarketSummaryDto>,
    padding: PaddingValues,
    health: MarketDataState<com.marksy.os.market.LiveFeedHealthDto> = MarketDataState.Unavailable
) {
    // The footer's source/freshness wording comes from the feed's own reported state
    // (`/market/live/health`), never a client-invented threshold on `summary.asOf`.
    val freshnessLabel = when (health) {
        is MarketDataState.Loaded -> "Data: Upstox · live"
        is MarketDataState.Stale -> "Data: Upstox · ${health.value.feedState.lowercase()}${if (health.value.fallbackActive) " (fallback)" else ""}"
        else -> "Data: Upstox · feed status unknown"
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(MarksyTheme.Background).padding(horizontal = 18.dp),
        contentPadding = PaddingValues(bottom = padding.calculateBottomPadding() + 20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        when (state) {
            is MarketDataState.Loading -> item { Text("Checking market...", color = MarksyTheme.TextMuted, fontSize = 13.sp) }
            is MarketDataState.Unavailable -> item { EmptyState("Market Intelligence is not configured", "Add a Market API key in More → Configure Gateway.") }
            is MarketDataState.Error -> item { EmptyState("Market data unavailable", state.message) }
            is MarketDataState.Empty -> item { EmptyState("No market data", "Nothing to show right now.") }
            is MarketDataState.Loaded -> overviewContent(state.value, freshnessLabel)
            is MarketDataState.Stale -> overviewContent(state.value, freshnessLabel)
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.overviewContent(summary: MarketSummaryDto, freshnessLabel: String) {
    item { Text(summary.marketStatus, color = MarksyTheme.PrimaryEmerald, fontSize = 16.sp, fontWeight = FontWeight.Bold) }
    items(summary.indexes) { index ->
        Column(Modifier.fillMaxWidth().border(1.dp, MarksyTheme.BorderGlow, RoundedCornerShape(12.dp)).padding(10.dp)) {
            Text(index.name, color = MarksyTheme.TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text("${index.value} (${index.changePct}%)", color = MarksyTheme.TextSecondary, fontSize = 12.sp)
        }
    }
    if (summary.topGainers.isNotEmpty()) {
        item { Text("Gainers", color = MarksyTheme.TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
        items(summary.topGainers) { mover -> MoverRow(mover) }
    }
    if (summary.topLosers.isNotEmpty()) {
        item { Text("Losers", color = MarksyTheme.TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
        items(summary.topLosers) { mover -> MoverRow(mover) }
    }
    item { Text(freshnessLabel, color = MarksyTheme.TextMuted, fontSize = 11.sp) }
}

@Composable
private fun MoverRow(mover: MarketMoverDto) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(mover.symbol, color = MarksyTheme.TextPrimary, fontSize = 13.sp)
        Text("${mover.changePercent}%", color = MarksyTheme.TextSecondary, fontSize = 12.sp)
    }
}
```

Implement `MarketScreen` (the tab shell with internal sub-navigation — no `MainActivity` booleans added):

```kotlin
package com.marksy.os.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import com.marksy.os.market.MarketIntelligenceRepository

private enum class MarketTab(val label: String) { OVERVIEW("Overview"), STOCKS("Stocks"), PREDICTIONS("Predictions"), IPOS("IPOs") }

@Composable
fun MarketScreen(repository: MarketIntelligenceRepository, padding: PaddingValues) {
    var tab by rememberSaveable { mutableStateOf(MarketTab.OVERVIEW) }
    var selectedSymbol by rememberSaveable { mutableStateOf<String?>(null) }

    Column(modifier = Modifier.fillMaxSize().background(MarksyTheme.Background)) {
        TabRow(selectedTabIndex = tab.ordinal, containerColor = MarksyTheme.Surface) {
            MarketTab.entries.forEach { candidate ->
                Tab(selected = tab == candidate, onClick = { tab = candidate; if (candidate != MarketTab.STOCKS) selectedSymbol = null }, text = { Text(candidate.label) })
            }
        }
        when (tab) {
            MarketTab.OVERVIEW -> {
                val state by produceState(com.marksy.os.market.MarketDataState.Loading as com.marksy.os.market.MarketDataState<com.marksy.os.market.MarketSummaryDto>) {
                    while (true) { value = repository.overview(); kotlinx.coroutines.delay(60_000) }
                }
                val health by produceState(com.marksy.os.market.MarketDataState.Loading as com.marksy.os.market.MarketDataState<com.marksy.os.market.LiveFeedHealthDto>) {
                    while (true) { value = repository.liveFeedHealth(); kotlinx.coroutines.delay(60_000) }
                }
                MarketOverviewScreen(state = state, padding = padding, health = health)
            }
            MarketTab.STOCKS -> {
                val symbol = selectedSymbol
                if (symbol == null) {
                    StockSearchPlaceholder(padding = padding, onSymbolChosen = { selectedSymbol = it })
                } else {
                    val state by produceState(com.marksy.os.market.MarketDataState.Loading as com.marksy.os.market.MarketDataState<com.marksy.os.market.InstrumentLifecycleDto>, symbol) {
                        value = repository.instrument(symbol)
                    }
                    StockDetailScreen(state = state, padding = padding, onBack = { selectedSymbol = null })
                }
            }
            MarketTab.PREDICTIONS -> PredictionsScreen(repository = repository, padding = padding, onOpenSymbol = { selectedSymbol = it; tab = MarketTab.STOCKS })
            MarketTab.IPOS -> IpoScreen(repository = repository, padding = padding)
        }
    }
}
```

(`StockSearchPlaceholder`, `StockDetailScreen`, `PredictionsScreen`, `IpoScreen` are implemented in Tasks 8-9.)

Wire the tab into `MainActivity`: add `"Market" to Icons.Default.ShowChart` (or `Icons.Default.QueryStats` to avoid clashing visually with the existing Trading tab's `ShowChart`) to the `tabs` list, add a `selectedTab == 5 ->` branch in the `Scaffold` content calling `MarketScreen(repository = remember { MarksyContainer.marketIntelligence(applicationContext) }, padding = padding)`, and extend the `BackHandler`'s tab-reset condition set (`selectedTab != 0`) — no change needed there since it already covers any non-zero tab.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.marksy.os.ui.MarketOverviewScreenTest"`
Expected: PASS (5 tests).

- [ ] **Step 5: Manually verify the tab**

Run the debug build, confirm a 6th "Market" bottom tab appears, opens to Overview, and the internal Overview/Stocks/Predictions/IPOs `TabRow` switches without affecting the bottom bar or other tabs' state.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/marksy/os/ui/MarketScreen.kt app/src/main/java/com/marksy/os/ui/MarketOverviewScreen.kt app/src/main/java/com/marksy/os/MainActivity.kt app/src/test/java/com/marksy/os/ui/MarketOverviewScreenTest.kt
git commit -m "feat(market): add Market tab shell and Overview screen"
```

---

### Task 8: Stock detail + Predictions screens

**Files:**
- Create: `app/src/main/java/com/marksy/os/ui/StockDetailScreen.kt`
- Create: `app/src/main/java/com/marksy/os/ui/PredictionsScreen.kt`
- Test: `app/src/test/java/com/marksy/os/ui/StockDetailScreenTest.kt`
- Test: `app/src/test/java/com/marksy/os/ui/PredictionsScreenTest.kt`

**Interfaces:**
- Consumes: `MarketDataState<InstrumentLifecycleDto>`, `MarketIntelligenceRepository.activePredictions(cursor)` (Task 6).
- Produces: `StockDetailScreen(state: MarketDataState<InstrumentLifecycleDto>, padding: PaddingValues, onBack: () -> Unit)`; `StockSearchPlaceholder(padding: PaddingValues, onSymbolChosen: (String) -> Unit)`; `PredictionsScreen(repository: MarketIntelligenceRepository, padding: PaddingValues, onOpenSymbol: (String) -> Unit)`. Consumed by `MarketScreen` (Task 7).

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.marksy.os.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.marksy.os.market.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StockDetailScreenTest {
    @get:Rule val compose = createComposeRule()

    private fun instrument() = InstrumentLifecycleDto(
        symbol = "RELIANCE", companyName = "Reliance Industries", exchange = "NSE", sector = "Energy", isActive = true,
        market = InstrumentMarketDto(lastClosePrice = 1452.3, asOfSessionDate = "2026-09-23T18:30:00Z", freshnessState = "FRESH"),
        predictionCount = 1, openPredictionCount = 1,
        predictions = listOf(
            InstrumentPredictionEntryDto(
                predictionId = 501, asOf = "2026-09-20T09:15:00Z", horizonDays = 5, entryPrice = 1420.0,
                targetPrice = 1470.0, stopLoss = 1390.0, probabilityAtPublication = 0.71, confidenceAtPublication = 0.8,
                lifecycleState = "ACTIVE", lifecycleDetail = "Tracking toward target", isTerminal = false,
                currentPrice = 1452.3, currentReturn = 2.27, targetProgress = 0.64, stopProgress = 0.0,
                outcomeStatus = "PENDING", realizedReturnPct = null, hasResolvedOutcome = false, evidenceItemCount = 4
            )
        )
    )

    @Test
    fun loadedStateShowsCompanyAndPrediction() {
        compose.setContent { StockDetailScreen(state = MarketDataState.Loaded(instrument()), padding = PaddingValues(), onBack = {}) }

        compose.onNodeWithText("Reliance Industries").assertExists()
        compose.onNodeWithText("ACTIVE", substring = true).assertExists()
    }

    @Test
    fun instrumentWithNoPredictionsShowsEmptyPredictionState() {
        val noPredictions = instrument().copy(predictions = emptyList(), predictionCount = 0, openPredictionCount = 0)
        compose.setContent { StockDetailScreen(state = MarketDataState.Loaded(noPredictions), padding = PaddingValues(), onBack = {}) }

        compose.onNodeWithText("No predictions yet", substring = true).assertExists()
    }

    @Test
    fun unavailableStateShowsExplicitMessage() {
        compose.setContent { StockDetailScreen(state = MarketDataState.Unavailable, padding = PaddingValues(), onBack = {}) }

        compose.onNodeWithText("not configured", substring = true).assertExists()
    }
}
```

```kotlin
package com.marksy.os.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.marksy.os.market.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private class FixturePredictionsClient(private val page: ActivePredictionPageDto) : MarketApiClient {
    override suspend fun marketSummary() = throw NotImplementedError()
    override suspend fun liveQuotes(symbols: List<String>?) = throw NotImplementedError()
    override suspend fun liveFeedHealth() = throw NotImplementedError()
    override suspend fun indexHistory(name: String, range: String) = throw NotImplementedError()
    override suspend fun sectors() = emptyList<SectorOptionDto>()
    override suspend fun instrument(symbol: String) = throw NotImplementedError()
    override suspend fun activePredictions(cursor: String?) = page
    override suspend fun activePrediction(id: Int) = throw NotImplementedError()
    override suspend fun ipos(stage: String?, query: String?) = emptyList<IpoListItemDto>()
    override suspend fun ipoAttention(limit: Int) = emptyList<IpoAttentionItemDto>()
    override suspend fun ipoStageCounts() = throw NotImplementedError()
    override suspend fun ipoDetail(id: String) = throw NotImplementedError()
    override suspend fun ipoHistory(id: String) = emptyList<IpoHistoryEntryDto>()
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PredictionsScreenTest {
    @get:Rule val compose = createComposeRule()

    private fun prediction(symbol: String) = ActivePredictionDto(
        predictionId = 1, symbol = symbol, companyName = "$symbol Ltd", exchange = "NSE", price = 100.0,
        targetPrice = 110.0, stopLoss = 90.0, horizon = 5, remainingTradingDays = 3,
        distanceToTargetPercent = 10.0, distanceToStopLossPercent = -10.0, confidence = 0.7,
        trustScore = 0.6, trustQuality = "MEDIUM", status = "OPEN", lifecycleState = "ACTIVE",
        isActionableNow = true, lifecycleDetail = null, entryPrice = 95.0, compositeOpportunityScore = 0.6
    )

    @Test
    fun listedPredictionsAreTappableToOpenSymbol() {
        val repository = MarketIntelligenceRepository(FixturePredictionsClient(ActivePredictionPageDto(listOf(prediction("RELIANCE")), null)))
        var opened: String? = null

        compose.setContent { PredictionsScreen(repository = repository, padding = PaddingValues(), onOpenSymbol = { opened = it }) }
        compose.waitForIdle()
        compose.onNodeWithText("RELIANCE").performClick()

        assert(opened == "RELIANCE")
    }

    @Test
    fun emptyPredictionsShowsEmptyState() {
        val repository = MarketIntelligenceRepository(FixturePredictionsClient(ActivePredictionPageDto(emptyList(), null)))

        compose.setContent { PredictionsScreen(repository = repository, padding = PaddingValues(), onOpenSymbol = {}) }
        compose.waitForIdle()

        compose.onNodeWithText("No active predictions", substring = true).assertExists()
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "com.marksy.os.ui.StockDetailScreenTest" --tests "com.marksy.os.ui.PredictionsScreenTest"`
Expected: FAIL — `StockDetailScreen`/`PredictionsScreen` do not exist.

- [ ] **Step 3: Implement the screens**

```kotlin
package com.marksy.os.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.marksy.os.EmptyState
import com.marksy.os.market.InstrumentLifecycleDto
import com.marksy.os.market.InstrumentPredictionEntryDto
import com.marksy.os.market.MarketDataState

@Composable
fun StockSearchPlaceholder(padding: PaddingValues, onSymbolChosen: (String) -> Unit) {
    var symbol by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().background(MarksyTheme.Background).padding(18.dp)) {
        Text("Look up a stock", color = MarksyTheme.TextPrimary, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Spacer(Modifier.height(8.dp))
        CompactTextField(value = symbol, onValueChange = { symbol = it.uppercase() }, modifier = Modifier.fillMaxWidth(), label = "Symbol, e.g. RELIANCE")
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = { if (symbol.isNotBlank()) onSymbolChosen(symbol.trim()) }) { Text("Open") }
    }
}

@Composable
fun StockDetailScreen(state: MarketDataState<InstrumentLifecycleDto>, padding: PaddingValues, onBack: () -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().background(MarksyTheme.Background).padding(horizontal = 18.dp),
        contentPadding = PaddingValues(bottom = padding.calculateBottomPadding() + 20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        when (state) {
            is MarketDataState.Loading -> item { Text("Checking instrument...", color = MarksyTheme.TextMuted, fontSize = 13.sp) }
            is MarketDataState.Unavailable -> item { EmptyState("Market Intelligence is not configured", "Add a Market API key in More → Configure Gateway.") }
            is MarketDataState.Error -> item { EmptyState("Instrument unavailable", state.message) }
            is MarketDataState.Empty -> item { EmptyState("Not found", "No instrument matches that symbol.") }
            is MarketDataState.Loaded -> instrumentContent(state.value)
            is MarketDataState.Stale -> instrumentContent(state.value)
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.instrumentContent(instrument: InstrumentLifecycleDto) {
    item {
        Text(instrument.companyName ?: instrument.symbol, color = MarksyTheme.TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Text("${instrument.symbol} · ${instrument.exchange}", color = MarksyTheme.TextSecondary, fontSize = 12.sp)
    }
    item {
        val price = instrument.market.lastClosePrice
        Text(if (price != null) "Last close: $price" else "Last close: unavailable", color = MarksyTheme.TextSecondary, fontSize = 13.sp)
    }
    if (instrument.predictions.isEmpty()) {
        item { EmptyState("No predictions yet", "Marksy has not published a prediction for this instrument.") }
    } else {
        item { Text("Prediction history", color = MarksyTheme.TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }
        items(instrument.predictions) { prediction -> PredictionHistoryRow(prediction) }
    }
}

@Composable
private fun PredictionHistoryRow(prediction: InstrumentPredictionEntryDto) {
    Column(Modifier.fillMaxWidth().border(1.dp, MarksyTheme.BorderGlow, RoundedCornerShape(12.dp)).padding(10.dp)) {
        Text(prediction.lifecycleState, color = MarksyTheme.PrimaryEmerald, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Text(prediction.lifecycleDetail, color = MarksyTheme.TextSecondary, fontSize = 12.sp)
        Text("Entry ${prediction.entryPrice} · Target ${prediction.targetPrice ?: "-"} · Stop ${prediction.stopLoss ?: "-"}", color = MarksyTheme.TextMuted, fontSize = 11.sp)
        Text("${prediction.evidenceItemCount} evidence item(s) recorded", color = MarksyTheme.TextMuted, fontSize = 11.sp)
    }
}
```

```kotlin
package com.marksy.os.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.marksy.os.EmptyState
import com.marksy.os.market.ActivePredictionDto
import com.marksy.os.market.MarketDataState
import com.marksy.os.market.MarketIntelligenceRepository

@Composable
fun PredictionsScreen(repository: MarketIntelligenceRepository, padding: PaddingValues, onOpenSymbol: (String) -> Unit) {
    val state by produceState(MarketDataState.Loading as MarketDataState<com.marksy.os.market.ActivePredictionPageDto>) {
        value = repository.activePredictions()
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(MarksyTheme.Background).padding(horizontal = 18.dp),
        contentPadding = PaddingValues(bottom = padding.calculateBottomPadding() + 20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        when (val s = state) {
            is MarketDataState.Loading -> item { Text("Checking predictions...", color = MarksyTheme.TextMuted, fontSize = 13.sp) }
            is MarketDataState.Unavailable -> item { EmptyState("Market Intelligence is not configured", "Add a Market API key in More → Configure Gateway.") }
            is MarketDataState.Error -> item { EmptyState("Predictions unavailable", s.message) }
            is MarketDataState.Empty -> item { EmptyState("No active predictions", "Marksy has no open predictions right now.") }
            is MarketDataState.Loaded -> items(s.value.items) { prediction -> PredictionRow(prediction, onOpenSymbol) }
            is MarketDataState.Stale -> items(s.value.items) { prediction -> PredictionRow(prediction, onOpenSymbol) }
        }
    }
}

@Composable
private fun PredictionRow(prediction: ActivePredictionDto, onOpenSymbol: (String) -> Unit) {
    Column(
        Modifier.fillMaxWidth().border(1.dp, MarksyTheme.BorderGlow, RoundedCornerShape(12.dp))
            .clickable { onOpenSymbol(prediction.symbol) }.padding(10.dp)
    ) {
        Text(prediction.symbol, color = MarksyTheme.TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Text(prediction.lifecycleState, color = MarksyTheme.PrimaryEmerald, fontSize = 12.sp)
        Text("Target ${prediction.targetPrice} · Stop ${prediction.stopLoss} · Confidence ${prediction.confidence}", color = MarksyTheme.TextSecondary, fontSize = 11.sp)
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "com.marksy.os.ui.StockDetailScreenTest" --tests "com.marksy.os.ui.PredictionsScreenTest"`
Expected: PASS (5 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/marksy/os/ui/StockDetailScreen.kt app/src/main/java/com/marksy/os/ui/PredictionsScreen.kt app/src/test/java/com/marksy/os/ui/StockDetailScreenTest.kt app/src/test/java/com/marksy/os/ui/PredictionsScreenTest.kt
git commit -m "feat(market): add Stock detail and Predictions screens"
```

---

### Task 9: IPO screen

**Files:**
- Create: `app/src/main/java/com/marksy/os/ui/IpoScreen.kt`
- Test: `app/src/test/java/com/marksy/os/ui/IpoScreenTest.kt`

**Interfaces:**
- Consumes: `MarketIntelligenceRepository.ipos(stage, query)`, `.ipoStageCounts()` (Task 6).
- Produces: `IpoScreen(repository: MarketIntelligenceRepository, padding: PaddingValues)`, consumed by `MarketScreen` (Task 7).

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.marksy.os.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.marksy.os.market.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private class FixtureIpoClient(
    private val list: List<IpoListItemDto>,
    private val counts: IpoStageCountsDto
) : MarketApiClient {
    override suspend fun marketSummary() = throw NotImplementedError()
    override suspend fun liveQuotes(symbols: List<String>?) = throw NotImplementedError()
    override suspend fun liveFeedHealth() = throw NotImplementedError()
    override suspend fun indexHistory(name: String, range: String) = throw NotImplementedError()
    override suspend fun sectors() = emptyList<SectorOptionDto>()
    override suspend fun instrument(symbol: String) = throw NotImplementedError()
    override suspend fun activePredictions(cursor: String?) = throw NotImplementedError()
    override suspend fun activePrediction(id: Int) = throw NotImplementedError()
    override suspend fun ipos(stage: String?, query: String?) = list
    override suspend fun ipoAttention(limit: Int) = emptyList<IpoAttentionItemDto>()
    override suspend fun ipoStageCounts() = counts
    override suspend fun ipoDetail(id: String) = throw NotImplementedError()
    override suspend fun ipoHistory(id: String) = emptyList<IpoHistoryEntryDto>()
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class IpoScreenTest {
    @get:Rule val compose = createComposeRule()

    private fun ipo(id: String, company: String, stage: String) = IpoListItemDto(
        id = id, companyName = company, issueName = null, isSme = false, sector = null, stage = stage,
        opensOn = null, closesOn = null, listsOn = null, terms = null
    )

    @Test
    fun listedIposShowCompanyNames() {
        val repository = MarketIntelligenceRepository(
            FixtureIpoClient(
                list = listOf(ipo("ipo-1", "Acme Robotics", "OPEN")),
                counts = IpoStageCountsDto(byStage = mapOf("OPEN" to 1), total = 1)
            )
        )

        compose.setContent { IpoScreen(repository = repository, padding = PaddingValues()) }
        compose.waitForIdle()

        compose.onNodeWithText("Acme Robotics").assertExists()
    }

    @Test
    fun emptyIpoListShowsEmptyState() {
        val repository = MarketIntelligenceRepository(FixtureIpoClient(list = emptyList(), counts = IpoStageCountsDto(byStage = emptyMap(), total = 0)))

        compose.setContent { IpoScreen(repository = repository, padding = PaddingValues()) }
        compose.waitForIdle()

        compose.onNodeWithText("No IPOs", substring = true).assertExists()
    }

    @Test
    fun stageFilterChipsComeFromServerCounts() {
        val repository = MarketIntelligenceRepository(
            FixtureIpoClient(
                list = listOf(ipo("ipo-1", "Acme Robotics", "OPEN")),
                counts = IpoStageCountsDto(byStage = mapOf("OPEN" to 1, "UPCOMING" to 2), total = 3)
            )
        )

        compose.setContent { IpoScreen(repository = repository, padding = PaddingValues()) }
        compose.waitForIdle()

        compose.onNodeWithText("UPCOMING", substring = true).assertExists()
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "com.marksy.os.ui.IpoScreenTest"`
Expected: FAIL — `IpoScreen` does not exist.

- [ ] **Step 3: Implement the screen**

```kotlin
package com.marksy.os.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.marksy.os.EmptyState
import com.marksy.os.market.IpoListItemDto
import com.marksy.os.market.MarketDataState
import com.marksy.os.market.MarketIntelligenceRepository

@Composable
fun IpoScreen(repository: MarketIntelligenceRepository, padding: PaddingValues) {
    var selectedStage by rememberSaveable { mutableStateOf<String?>(null) }

    val countsState by produceState(MarketDataState.Loading as MarketDataState<com.marksy.os.market.IpoStageCountsDto>) {
        value = repository.ipoStageCounts()
    }
    val listState by produceState(MarketDataState.Loading as MarketDataState<List<IpoListItemDto>>, selectedStage) {
        value = repository.ipos(stage = selectedStage)
    }

    Column(Modifier.fillMaxSize().background(MarksyTheme.Background)) {
        val counts = (countsState as? MarketDataState.Loaded)?.value
        if (counts != null && counts.byStage.isNotEmpty()) {
            LazyRow(contentPadding = PaddingValues(horizontal = 18.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    FilterChip(selected = selectedStage == null, onClick = { selectedStage = null }, label = { Text("All (${counts.total})") })
                }
                items(counts.byStage.entries.toList()) { (stage, count) ->
                    FilterChip(selected = selectedStage == stage, onClick = { selectedStage = stage }, label = { Text("$stage ($count)") })
                }
            }
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(horizontal = 18.dp),
            contentPadding = PaddingValues(bottom = padding.calculateBottomPadding() + 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            when (val s = listState) {
                is MarketDataState.Loading -> item { Text("Checking IPOs...", color = MarksyTheme.TextMuted, fontSize = 13.sp) }
                is MarketDataState.Unavailable -> item { EmptyState("Market Intelligence is not configured", "Add a Market API key in More → Configure Gateway.") }
                is MarketDataState.Error -> item { EmptyState("IPO data unavailable", s.message) }
                is MarketDataState.Empty -> item { EmptyState("No IPOs", "No issues match this filter right now.") }
                is MarketDataState.Loaded -> items(s.value) { ipo -> IpoRow(ipo) }
                is MarketDataState.Stale -> items(s.value) { ipo -> IpoRow(ipo) }
            }
        }
    }
}

@Composable
private fun IpoRow(ipo: IpoListItemDto) {
    Column(Modifier.fillMaxWidth().border(1.dp, MarksyTheme.BorderGlow, RoundedCornerShape(12.dp)).padding(10.dp)) {
        Text(ipo.companyName, color = MarksyTheme.TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Text(ipo.stage ?: "Stage not established", color = MarksyTheme.TextSecondary, fontSize = 12.sp)
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "com.marksy.os.ui.IpoScreenTest"`
Expected: PASS (3 tests).

- [ ] **Step 5: Full unit test suite + manual end-to-end verification**

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS (all tests, including Tasks 1-9's new tests and every pre-existing test unchanged).

Manually: with a real `scopes=["marksy"]` API key minted via `POST /admin/clients` and entered in Gateway Settings, run the debug build against a live `marksy-api` deployment; confirm Overview/Stocks/Predictions/IPOs each render real data, and that removing the Market API key immediately drops every Market screen to its `Unavailable` state (not a crash, not stale data presented as live).

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/marksy/os/ui/IpoScreen.kt app/src/test/java/com/marksy/os/ui/IpoScreenTest.kt
git commit -m "feat(market): add IPO screen with server-sourced stage filters"
```
