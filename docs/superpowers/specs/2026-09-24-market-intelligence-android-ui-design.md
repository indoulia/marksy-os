# Market Intelligence Android UI — Design

## Context

`marksy-api` already has a mature, production market-intelligence surface (Upstox
integration, market summary/live/indices/regime, instruments, active predictions,
IPOs, freshness, health) serving the Flutter client today. `marksy-os` (this repo,
the Android companion app) has no UI for any of it. This is sub-project 1 of a
larger initiative; a follow-on sub-project will add real-time WebSocket push on
top of this one.

## Goals

- Add a new "Market" section to MarksyOS: Overview, Stock detail, Predictions,
  IPOs — reading real data from the existing `marksy-api` endpoints.
- Reuse existing MarksyOS architecture exactly: manual `MarksyContainer` service
  locator, raw `HttpURLConnection` client convention (as in
  `MarksyTipsApiClient`), `SecureCredentialStore` (Android Keystore), hand-rolled
  bottom-tab + boolean-flag sub-screen navigation, `MarksyTheme`.
- Never show fabricated market values; always reflect the data's own freshness/
  availability signals.

## Non-goals (deferred)

- Real-time WebSocket streaming (sub-project 2 — needs new `marksy-api`
  broadcast/subscription work on top of the existing pull-only
  `live_market_cache`, plus an Android streaming client). This design leaves a
  seam for it (see Data Flow) but does not build it.
- Any change to the existing "Trading" tab (`TradingIntelligenceScreen`,
  `MarketRepository`, `MarketState`) — it keeps doing ad-hoc tip analysis,
  untouched.
- Any `marksy-api` code changes. The only operational step on that side is an
  admin minting a `scopes=["marksy"]` API key via the existing
  `POST /admin/clients` (EPIC-815) — no new server code.
- Tradsy / trading execution surfaces — not requested, not part of this work.

## Architecture

### Auth

`SecureCredentialStore` gains a second stored credential, `marketApiKey`,
independent of the existing `integrationKey` (the EPIC-803 shared secret used
only for `/tips`). It is a scoped `X-API-Key` minted via `POST /admin/clients`
with `scopes=["marksy"]`. The existing Gateway Settings screen gets a second
masked field for it, following the exact pattern of the integration-key field
(show/hide toggle, HTTPS-only base URL validation, "Save Securely" /
"Remove Credential"). QR provisioning is not extended in this pass — manual
entry only, matching the smallest change that satisfies the goal.

### API client

New `MarketApiClient` in the `gateway` package, structurally identical to
`MarksyTipsApiClient`: `HttpURLConnection`, HTTPS-enforced base URL, `{data,
meta}` envelope parsing, the same bounded-text/list truncation constants,
sending `X-API-Key` instead of `X-Marksy-Integration-Key`. Suspend functions,
one per endpoint actually used by the screens below:

- `marketSummary()` → `GET /market/summary`
- `marketLive(symbols?)` → `GET /market/live`
- `indexHistory(name, range)` → `GET /market/indices/{name}/history`
- `sectors()` → `GET /market/sectors`
- `regimeHistory()` / `regimePerformance()` → `GET /market/regime/*`
- `instrument(symbol)` → `GET /instruments/{symbol}`
- `predictionsActive(cursor?)` / `prediction(id)` → `GET /predictions/active[/{id}]`
- `ipos(stage?, query?)`, `ipoAttention()`, `ipoTracked()`, `ipo(id)`,
  `ipoHistory(id)`, `trackIpo(id)` / `untrackIpo(id)` → `/ipos*`
- `freshness()` → `GET /freshness`
- `health()` → `GET /health`

No endpoint is called speculatively; only what a screen in this design
actually renders gets a method. Anything the real response shape doesn't
support (e.g. a filter with no backing field) is not built.

### Repository

New `MarketIntelligenceRepository`, separate from the existing `MarketRepository`.
Exposes a shared sealed state:

```kotlin
sealed class MarketDataState<out T> {
    object Loading : MarketDataState<Nothing>()
    data class Loaded<T>(val value: T, val asOf: Instant, val sourceStatus: String) : MarketDataState<T>()
    data class Stale<T>(val value: T, val asOf: Instant, val ageMs: Long) : MarketDataState<T>()
    object Unavailable : MarketDataState<Nothing>()
    data class Error(val message: String) : MarketDataState<Nothing>()
    object Empty : MarketDataState<Nothing>()
}
```

used uniformly across Overview/Stock/Predictions/IPO screens. Staleness/
unavailability is derived from each response's own `asOf`/`dataAgeMs`/
`sourceStatus`/`marketStatus` fields — never invented client-side thresholds
beyond what the API already exposes via `/freshness` and `/market/live/health`.

### Data flow (poll now, WS-ready seam)

Each repository method that backs a "live-ish" screen (Overview, Stock detail)
exposes a `Flow<MarketDataState<T>>` built from a simple internal poll loop
(matching the existing `produceState { while(true) { ...; delay(...) } }`
pattern already used for `market`/`weather` in `MainActivity`), rather than a
one-shot suspend call wired directly into the UI. This keeps the polling
mechanics inside the repository, not the screen, so sub-project 2 can later
replace the loop's source with a WebSocket listener feeding the same `Flow`
without changing any screen code. No WebSocket client, reconnect logic, or
subscription protocol is built in this pass.

### Navigation

New 6th bottom-bar tab, "Market", added to the existing `tabs` list in
`MainActivity`. Its content is a single new `MarketScreen` composable that
owns its own internal sub-navigation (segmented control or pager) for:
Overview → Stocks (search/list → detail) → Predictions → IPOs. This keeps the
new complexity contained in one composable/file tree instead of adding four
more `showXxx` booleans to `MainActivity`, which is already carrying a lot of
that pattern.

### Screens

1. **Overview** — market status, NIFTY/BANK NIFTY/India VIX (from
   `/market/summary` + `/market/live`), gainers/losers/most-active if present
   in the summary response, freshness footer ("Data: Upstox · updated Xs
   ago" / stale / unavailable wording per `MarketDataState`).
2. **Stock detail** — instrument facts from `/instruments/{symbol}`, the
   symbol's active prediction (if any) from `/predictions/active`, its
   evidence, and a link to prediction history. Only fields the real response
   schema provides are shown; nothing is synthesized to fill a layout.
3. **Predictions** — list from `/predictions/active`, filterable only by
   fields the API actually supports (checked against
   `api/schemas/predictions_active.py` during implementation, not assumed
   here).
4. **IPOs** — list/attention/tracked/detail/history/tracking from `ipo.py`,
   respecting its documented route-registration order
   (`/ipos/attention`, `/ipos/tracked` before `/ipos/{ipoId}`).

### Error / empty / stale handling

Every screen renders all six `MarketDataState` cases explicitly. No screen
ever shows `0.00`/`0%`/blank as if it were a real value on `Error` or
`Unavailable`.

## Testing

- `MarketApiClient`: envelope parsing success/error/malformed-response, HTTPS
  enforcement, header sent correctly — mirroring whatever test coverage
  `MarksyTipsApiClient` already has (checked and matched during
  implementation).
- `MarketIntelligenceRepository`: mapping of raw responses → each
  `MarketDataState` case, including the freshness/staleness derivation.
- Screens: Compose tests for loading/loaded/stale/unavailable/error/empty per
  screen, following this repo's existing screen-test conventions.

## Rollout

1. Admin mints a `scopes=["marksy"]` API key via `POST /admin/clients` for
   this device/app (operational step, no code change).
2. Enter it in the extended Gateway Settings screen.
3. No `marksy-api` deployment or migration required.

## Risks / open items carried into planning

- Exact response field shapes for `predictions_active`, `instruments`, and
  `ipo` schemas need to be read from `api/schemas/*.py` during
  implementation to finalize per-screen field lists — this design
  intentionally does not guess them.
- Whether `MarksyTipsApiClient` has existing unit tests to mirror needs
  confirming during implementation.
