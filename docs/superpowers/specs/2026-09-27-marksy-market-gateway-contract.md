# Marksy Market Gateway, Stream and Health Contracts (EPIC-024 / 025 / 030) — Draft

## Context

MarksyOS reads market data from two places today:

- **marksy-api**: `/market/summary`, `/market/live`, `/market/live/health`, `/market/indices/{name}/history`,
  `/market/sectors`, `/instruments/{symbol}`, predictions, performance and IPO routes.
- **Upstox directly**, with the user's own Analytics Token (`UpstoxApiClient`, `UpstoxFeed`): LTP, full quotes with
  depth, candles, option chain, fundamentals, news, the instrument master, and the V3 WebSocket feed.

This draft lists what marksy-api needs to expose so the app can drop every direct Upstox market-data call. Existing
routes are extended where they already fit. Every field below is one the app reads today; nothing is speculative.

## Conventions (all routes)

- Base `https://marksy.indoulia.com/api/v1`, envelope `{data, meta}` / `{error, meta}`, `Authorization: Bearer <marketApiKey>`
  (what `MarketApiClient` already sends).
- **Identity**: requests accept `symbols` (Marksy symbols, index names such as `NIFTY 50`) or `instrumentKeys`
  (Upstox keys such as `NSE_EQ|INE002A01018`, `NSE_INDEX|Nifty 50`). Every response item carries both `symbol` and
  `instrumentKey`. The app keys everything by instrument key, so accepting keys keeps migration mechanical.
- **Freshness on every item**, using the names `/market/live` already returns:
  - `provider`: `UPSTOX_STREAM` | `UPSTOX_REST` | `MARKSY_HISTORY` | `MARKSY_CACHE`
  - `receivedAt`: when Marksy got the value (ISO 8601)
  - `ageSeconds`
  - `state`: `LIVE` | `DELAYED` | `STALE` | `CLOSED` | `UNAVAILABLE`

  The server owns the thresholds. The app never invents an age cutoff (`MarketIntelligenceRepository` already relies on this).
- **Market status** uses the vocabulary `/market/summary` already returns: `PRE_MARKET`, `MARKET_HOURS`, `POST_MARKET`, `CLOSED`, `UNKNOWN`.
- **Batches never drop items silently**: an instrument Marksy can't price comes back with `state: UNAVAILABLE` and a
  null price.
- **Errors**: `{error: {code, message, provider?, retryAfterSeconds?}, meta}`
  - 400 `INVALID_REQUEST`: too many instruments, bad range
  - 404 `INSTRUMENT_NOT_FOUND`
  - 429 `RATE_LIMITED`, with `Retry-After`, whether the limit is Marksy's own or Upstox's
  - 502 `PROVIDER_ERROR`: Upstox answered with an error
  - 503 `PROVIDER_UNAVAILABLE`: Upstox unreachable
  - 503 `PROVIDER_AUTH`: Marksy's own Upstox token is missing or expired, so Health can tell "Upstox down" apart from "our token expired"

## EPIC-024 — Market data gateway (REST)

### 1. LTP — extend `GET /market/live?symbols=|instrumentKeys=` (max 500)
Replaces `UpstoxApiClient.ltp`. Add `instrumentKey`, `change` and `lastTradeTime` to each quote.
```json
{"asOf": "2026-09-27T10:15:02+05:30", "marketSession": "MARKET_HOURS",
 "quotes": [{"symbol": "NIFTY 50", "instrumentKey": "NSE_INDEX|Nifty 50", "name": "NIFTY 50",
             "price": 25912.4, "prevClose": 25840.1, "change": 72.3, "changePercent": 0.28,
             "lastTradeTime": 1790313902000, "state": "LIVE", "provider": "UPSTOX_STREAM",
             "receivedAt": "2026-09-27T10:15:02+05:30", "ageSeconds": 0}]}
```

### 2. Full quotes — new `GET /market/quotes?instrumentKeys=&depth=false` (max 500; `depth=true` only with one instrument)
Replaces `quote` (stock page, with depth) and `quotes` (batched, used for most-active).
```json
{"asOf": "…", "marketSession": "MARKET_HOURS",
 "quotes": [{"symbol": "RELIANCE", "instrumentKey": "NSE_EQ|INE002A01018",
             "lastPrice": 2941.5, "prevClose": 2920.0, "change": 21.5, "changePercent": 0.74,
             "open": 2925.0, "high": 2950.0, "low": 2918.2, "volume": 5120331, "averagePrice": 2936.8,
             "tradedValue": 15037100000, "upperCircuit": 3212.0, "lowerCircuit": 2628.0,
             "totalBuyQty": 412000, "totalSellQty": 398500, "lastTradeTime": 1790313900000,
             "depth": {"bids": [{"price": 2941.4, "quantity": 120, "orders": 3}], "asks": [{"price": 2941.6, "quantity": 85, "orders": 2}]},
             "state": "LIVE", "provider": "UPSTOX_REST", "receivedAt": "…", "ageSeconds": 1}]}
```
Gotcha the app already handles: take `prevClose` as `last_price - net_change`, because Upstox's `ohlc.close` is only a fallback.

### 3. Candles — new `GET /market/candles`
Replaces `candles`, `dailyCandles` and `monthlyCandles`.
- **By range**: `?instrumentKey=&range=1D|1W|1M|3M|6M|1Y|5Y|MAX&interval=<minutes>`. The interval applies only to
  1D (1, 3, 5, 15 or 30; default 5) and 1W (15, 30 or 60; default 30).
- **By dates**: `?instrumentKey=&from=YYYY-MM-DD&to=YYYY-MM-DD&interval=day|week|month`.
```json
{"symbol": "RELIANCE", "instrumentKey": "NSE_EQ|INE002A01018", "interval": "5m",
 "sessionDate": "2026-09-26", "fallback": "LAST_SESSION", "provider": "UPSTOX_REST", "asOf": "…",
 "candles": [["2026-09-26T09:15:00+05:30", 2925.0, 2931.0, 2922.5, 2929.8, 184220]]}
```
- Rows are oldest first: `[time, open, high, low, close, volume]`.
- 1D before the open or on a holiday returns the last session, with `fallback: "LAST_SESSION"`. The app does this itself today.
- Serve day, week and month candles from Marksy's history store where it covers the range, and intraday from Upstox.
  `/market/indices/{name}/history` stays as it is.

### 4. Option chain — new `GET /market/options/expiries?instrumentKey=` and `GET /market/options/chain?instrumentKey=&expiry=`
Replaces `optionExpiries` and `optionChain`. The EPIC-024 scope list doesn't include this, but the stock page uses it.
```json
{"instrumentKey": "NSE_EQ|INE002A01018", "expiries": ["2026-10-28", "2026-11-25"]}
{"instrumentKey": "…", "expiry": "2026-10-28", "spot": 2941.5, "state": "LIVE", "provider": "UPSTOX_REST", "asOf": "…",
 "strikes": [{"strike": 2940.0, "call": {"ltp": 38.2, "oi": 812000, "volume": 1204000, "iv": 18.4, "change": 4.1},
                                 "put": {"ltp": 35.9, "oi": 640000, "volume": 980000, "iv": 19.0, "change": -3.2}}]}
```
- `expiries` is empty when the instrument has no F&O.
- `call` or `put` can be null.

### 5. Fundamentals — new `GET /instruments/{symbol}/fundamentals?parts=…`
Replaces the nine `fundamentals(isin, part)` calls on the stock page. The parts are `profile`, `key-ratios`,
`income-quarterly`, `income-yearly`, `balance-sheet`, `cash-flow`, `share-holdings`, `corporate-actions` and `competitors`.
```json
{"symbol": "RELIANCE", "instrumentKey": "…", "isin": "INE002A01018",
 "parts": {"profile": {"state": "AVAILABLE", "asOf": "…", "provider": "UPSTOX_REST", "value": {}},
           "share-holdings": {"state": "UNAVAILABLE", "reason": "PROVIDER_ERROR", "value": null}}}
```
- Parts use the same `{state, value, asOf}` envelope as `/ipos/{id}`.
- Phase 1: `value` is Upstox's `data` object unchanged, so the app's `UpstoxFundamentals` parsers keep working. Normalize later if the web app needs it.
- A failed part comes back `UNAVAILABLE` and the other parts still return.
- Cache daily.

### 6. News — extend `GET /news?instrumentKey=|symbol=&pageSize=15`
Replaces `UpstoxApiClient.news`. Each item needs at least `headline`, `summary`, `url`, `source` and `publishedAt`.

### 7. Instrument lookup — new `GET /instruments/search?q=&limit=20`
Replaces the on-device NSE/BSE instrument-master download (`UpstoxInstruments`).
```json
[{"symbol": "RELIANCE", "name": "Reliance Industries", "exchange": "NSE", "type": "EQ",
  "instrumentKey": "NSE_EQ|INE002A01018", "isin": "INE002A01018"}]
```
- `/instruments/{symbol}` already returns `instrumentKey`.
- The symbol-to-key mapping must be stable. Never re-key an instrument silently.

### 8. Session, breadth, movers — extend `GET /market/summary`
Replaces the app's own NIFTY 50 breadth (50 LTPs), most-active (batched full quotes) and NSE open or closed status
(read from the Upstox feed's `market_info` frame).
```json
{"session": {"status": "MARKET_HOURS", "opensAt": "…09:15+05:30", "closesAt": "…15:30+05:30", "nextOpenAt": null, "holiday": null},
 "breadth": {"index": "NIFTY 50", "advances": 31, "declines": 17, "unchanged": 2, "asOf": "…"},
 "mostActiveByValue": [{"symbol": "…", "instrumentKey": "…", "name": "…", "price": 0, "changePercent": 0, "tradedValue": 0}],
 "moversUniverse": "NIFTY 500"}
```
Also add `instrumentKey` to the existing `topGainers`, `topLosers` and `indexes` items.

## EPIC-025 — Real-time stream

**Transport**: WebSocket `wss://marksy.indoulia.com/api/v1/market/stream`.
- Authenticate with `Authorization: Bearer <marketApiKey>` on the upgrade request.
- JSON text frames. Marksy decodes Upstox's protobuf on the server.
- The app already runs OkHttp WebSockets (`UpstoxFeed`), so this fits the existing pattern. SSE can't change a subscription without reconnecting.

**Client → server**
```json
{"op": "subscribe", "instrumentKeys": ["NSE_INDEX|Nifty 50"], "mode": "ltp"}
{"op": "unsubscribe", "instrumentKeys": ["…"]}
{"op": "resume", "afterSeq": 18233}
```
- `mode` is `ltp` or `quote`.
- Send `resume` as the first message after a reconnect. It is optional.

**Server → client**
```json
{"type": "hello", "connectionId": "…", "serverTime": 1790313902000, "maxInstruments": 200, "heartbeatSeconds": 15}
{"type": "snapshot", "seq": 18234, "reason": "subscribe", "quotes": [/* /market/live quote items */]}
{"type": "ticks", "seq": 18235, "items": [{"instrumentKey": "…", "price": 25913.1, "prevClose": 25840.1, "volume": null, "lastTradeTime": 1790313903000, "receivedAt": 1790313903042}]}
{"type": "status", "feedState": "STREAMING", "fallbackActive": false, "marketStatus": "MARKET_HOURS"}
{"type": "heartbeat", "seq": 18235, "serverTime": 1790313917000}
{"type": "error", "code": "TOO_MANY_INSTRUMENTS", "instrumentKeys": ["…"]}
```
- `snapshot.reason` is `subscribe`, `resync` or `upstream_reconnect`.
- `feedState` is `STREAMING`, `RECONNECTING`, `DEGRADED` or `DOWN`.

**Semantics**
- **Sequencing**: `seq` increases monotonically over Marksy's normalized event log. The server keeps a bounded
  buffer of about 2 minutes.
  - `resume` inside the buffer replays the ticks for this connection's subscriptions.
  - `resume` outside the buffer gets `snapshot` with `reason: "resync"`.
- **Ticks**: sent in batches, at most one batch every 250 ms.
  - Within an instrument, ticks go out in `lastTradeTime` order.
  - The server drops upstream duplicates, meaning the same key, `lastTradeTime`, price and volume.
  - The app still keeps the newest trade per key (`FeedMerge`).
- **Upstream reconnect**: Marksy resubscribes the union of all keys and REST-fetches LTP for them. It then sends
  `status` (`RECONNECTING`, then `STREAMING`) and a `snapshot` with `reason: "upstream_reconnect"`. The app never has
  to reconcile gaps itself.
- **Market closed**: the socket stays open with `status.marketStatus: CLOSED`, no ticks, and heartbeats continuing.
- **Server-side subscriptions**:
  - The upstream subscription is a reference-counted union of all clients' keys, plus the Home indices, which are always subscribed.
  - A key is dropped 60 s after its last client lets go. The app keeps a 3 s grace of its own before it unsubscribes.
- **Close codes**:
  - 4001: unauthorized. The client does not retry.
  - 4008: too many connections.
  - 1012: server restart. The client reconnects with a 1 s to 30 s backoff and jitter.
- **Fallback**: while the stream is down, the app shows the last `/market/live` snapshot labeled `STALE` (acceptance criterion).

## EPIC-030 — Health and validation metrics

### Extend `GET /market/live/health`
The current fields `upstoxEnabled`, `liveFeedEnabled`, `feedState`, `fallbackActive` and `cachedInstruments` stay. Add:
```json
{"token": {"state": "VALID", "expiresAt": "…"},
 "stream": {"connectedSince": "…", "lastTickAt": "…", "reconnects24h": 1, "ticksLastMinute": 5400,
            "subscribedInstruments": 212, "clients": 3, "decodeErrors24h": 0, "duplicatesDropped24h": 118,
            "gapReconciliations24h": 1,
            "neverTicked": ["NSE_EQ|INE…"], "staleInstruments": [{"instrumentKey": "…", "lastTickAt": "…"}]},
 "rest": {"window": "24h", "endpoints": [{"name": "market-quote/quotes", "calls": 4120, "failures": 3, "rateLimited": 0,
                                          "p50Ms": 180, "p95Ms": 610, "lastError": "…", "lastErrorAt": "…"}]},
 "ingestion": {"lastSuccessAt": "…", "failureRate24h": 0.002, "latencyP95Ms": 900}}
```
- `token.state` is `VALID`, `EXPIRING`, `EXPIRED` or `MISSING`.
- `neverTicked` lists instruments subscribed for at least 5 minutes in session that never got a tick.

These replace the app's per-session `UpstoxRestStats` and `FeedStats`, which only measure one device's session. The
acceptance criteria require metrics collected continuously from real runtime operation.

### New `GET /health/market-intelligence`
```json
{"asOf": "…",
 "predictions": {"lastRunAt": "…", "lastRunStatus": "OK", "runs24h": 24, "failures24h": 0, "failureRate7d": 0.01,
                 "latencyP50Ms": 42000, "latencyP95Ms": 95000, "generated24h": 38, "skippedForData24h": 4, "lastError": null},
 "ipos": {"state": "FRESH", "staleAfterHours": 24,
          "sources": [{"name": "…", "lastAttemptAt": "…", "lastSuccessAt": "…", "lastError": null, "records": 57}]},
 "integrity": {"checkedAt": "…", "window": "30d", "predictionsChecked": 383, "lookAheadViolations": 0, "earlyScoring": 0,
               "violations": [{"predictionId": 0, "symbol": "…", "createdAt": "…", "inputAsOf": "…", "rule": "INPUT_AFTER_CREATION"}]}}
```
- `lastRunStatus` is `OK`, `PARTIAL` or `FAILED`.
- `ipos.state` is `FRESH`, `STALE` or `UNAVAILABLE`.
- `skippedForData24h` counts predictions skipped because their data was missing. It keeps data-source failures apart
  from prediction failures, as the acceptance criterion requires.
- `violations` lists at most 20 items.

**No-look-ahead rules**
- Every prediction stores `inputAsOf`: the newest timestamp among its inputs, such as candles, quotes, news and fundamentals.
  - It is a violation when `inputAsOf` is later than `createdAt` (rule `INPUT_AFTER_CREATION`).
  - Expose `inputAsOf` on `/predictions/active` and `/tracking/predictions` items.
- Outcomes are scored only from prices after `createdAt`. `HORIZON_EXPIRED` is set only after `createdAt + horizon`.
  Scoring earlier breaks rule `EARLY_SCORING`.

**30-day report**: `/performance/summary` and `/performance/breakdown?dimension=horizon` already cover accuracy. Add
`GET /validation/market-intelligence?days=30` only if a daily series is wanted. It would return
`[{date, dataIncidents, predictionsGenerated, predictionsScored, hitRate, lookAheadViolations}]`.

## App migration (after each backend piece ships)

1. **024**: add `MarketApiClient` methods and DTOs for the new routes. Marksy becomes the default source.
   - Keep the user's Upstox token as a fallback until the Marksy routes return everything the direct calls do.
   - Then remove Upstox from the market-data paths. `UpstoxTokenStore` stays for future trading (EPIC-035).
2. **025**: a `MarksyStream` replaces `UpstoxFeed`. It keeps `FeedSubscriptions`, `FeedMerge` and `FeedFreshness`, and
   drops `UpstoxFeedDecoder`.
3. **030**: the Health Markets section reads the new fields and retires the app-side REST and feed stats.

## Open questions

1. **Upstox terms**: can Marksy serve market data from one server-held token to many app users? This may count as
   redistribution. Check Upstox's API terms before building 024. The 2026-09-24 design kept the token on the device
   partly for this reason.
2. **Fallback**: keep the user's own-token path permanently, or remove it once the Marksy routes return everything the direct calls do?
3. **Movers universe**: NIFTY 50, NIFTY 500, or all NSE equities?
4. **Identity**: accept both symbol and instrument key, as proposed, or make one canonical?
