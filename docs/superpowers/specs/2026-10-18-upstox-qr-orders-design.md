# Upstox setup by QR, practice Buy/Sell and today's portfolio — design

Date: 2026-10-18 · Status: approved in chat (Parts 1–4), spec under owner review
Mockup: "Marksy App" canvas, board `UpstoxConnection.dc.html` (v58). Ticket and Portfolio boards come before their PRs.

## 1. Decisions

- Orders go to the **Upstox sandbox only**. Live order placement is not built.
- Portfolio adds **funds & margin, today's positions and today's orders**, read-only, with the daily sign-in.
- Upstox settings can be **typed or imported from a QR code** (camera or photo picker). Import fills the form; only Save & test stores anything.
- The sandbox token is used **only** by the sandbox order client. Nothing in a QR can configure a live order endpoint or change the trading mode.
- Sandbox order acknowledgements are **not** portfolio activity and never appear on Portfolio. A local practice log is out of scope.

## 2. Upstox facts this rests on (checked 2026-10-18)

- **Static IP for orders.** Since 1 Apr 2026, place/modify/cancel, multi-order, GTT and exit-all must come from a registered static IP; otherwise HTTP 403 `UDAPI1154`. Reports from April and September 2026 show enforcement. Holdings, positions, funds and other reads are not restricted. A phone has no static IP, so live orders from the app would fail. Sources: [algo-trading circular](https://upstox.com/developer/api-documentation/announcements/algo-trading-circular/), [UDAPI1154 thread](https://community.upstox.com/t/i-am-getting-error-udapi1154-while-placing-orders-via-api/14900).
- **One active API app per user** since 1 Apr 2026: the newest is kept, others are deleted ([27 Mar 2026 notice](https://community.upstox.com/t/important-new-sebi-exchange-mandates-for-api-trading-effective-1st-april-2026/14822)). One live app (one API key and secret) serves both the daily login and orders, so the existing app is the only live app Marksy uses; a second live app is never created. Whether the separate sandbox app counts is **not documented** (see §8).
- **Analytics Token**: 1 year, read-only, cannot trade; portfolio reads with it need a whitelisted static IP, so in the app it stays market-data only ([docs](https://upstox.com/developer/api-documentation/analytics-token/)).
- **Daily OAuth token**: expires 03:30 IST; reads holdings, positions, funds, order book, charges and margin from the phone.
- **Sandbox**: separate sandbox app; token valid **30 days**; supports place/modify/cancel only; no order book, holdings or positions. Order host `https://api-sandbox.upstox.com` (official Python SDK `configuration.py`) ([docs](https://upstox.com/developer/api-documentation/sandbox/)).
- **Place Order V3** body: `quantity, product (D|I|MTF), validity (DAY|IOC), price, tag (≤40), instrument_token, order_type (MARKET|LIMIT|SL|SL-M), transaction_type, disclosed_quantity, trigger_price, is_amo, slice, market_protection` (MARKET: `-1` auto; `0` is rejected). Accepted 05:30–24:00 IST ([docs](https://upstox.com/developer/api-documentation/v3/place-order/)).
- Read endpoints: `GET /v2/user/get-funds-and-margin` (down 00:00–05:30 IST, `UDAPI100072`; combined funds under `equity` since Jul 2025), `GET /v2/portfolio/short-term-positions`, `GET /v2/order/retrieve-all`, `GET /v2/charges/brokerage`, `POST /v2/charges/margin` (`instruments[]` → `required_margin`, `final_margin`).

## 3. Part 1 — Settings › Upstox and QR import

### Screen
One form (replaces today's two blocks in `UpstoxScreen`), compact-header rules apply:
- **Live account · read-only** card: daily sign-in status (or Sign in pill), Analytics Token, API key, API secret, Redirect URL. All four credential fields are masked with Show/Hide; the secret keeps "leave blank to keep it".
- **Sandbox · practice orders** card: sandbox token (masked) and its expiry line in four states: valid (emerald), ≤3 days (warning, "make a new token and import it"), expired ("practice orders are off; live orders stay off"), none.
- Save & test (enabled only with changes) and Discard; Disconnect behind the existing confirmation, now also wiping the sandbox token. Analytics Token stays, as today.
- Floating stack: Import from QR (sheet: Scan with camera / Choose an image / How to make the QR) and a help button.
- After import: banner "Filled from QR: …", imported fields tagged QR. A rejected code shows "Not imported. <reason>. Nothing was changed."
- `FLAG_SECURE` while this screen is shown (set and cleared with the screen).

### QR format v1 (closed schema)
UTF-8 JSON object:
```json
{"type":"marksy-upstox","v":1,"api_key":"…","api_secret":"…","redirect_uri":"http://127.0.0.1/marksy-upstox","analytics_token":"…","sandbox_token":"…"}
```
Rules, each with its own message:
1. Payload over 4096 bytes → refused.
2. Not a single JSON object (malformed, array, trailing content) → refused. Not starting with `{` → "This isn't a Marksy Upstox code."
3. Parsed with `android.util.JsonReader` (strict). **Repeated keys are refused** (the phone's `org.json` silently keeps the last one; the JVM test `org.json` throws, so neither is relied on).
4. `type` must be the string `marksy-upstox`; `v` must be the integer `1` (a higher integer → "made for a newer Marksy").
5. Allowed keys are exactly the seven above. `access_token` → "Marksy only takes that from the Upstox sign-in." Any other key → refused, naming it.
6. Every credential value is a string, non-empty after trimming, printable ASCII with no whitespace.
7. `api_key`, `api_secret` ≤ 128 chars; tokens ≤ 2048 chars. JWT shape is not required (Upstox doesn't document the token format); `exp` is read only when a token is a JWT. `analytics_token` equal to `sandbox_token` → refused.
8. `redirect_uri`: `https://` any host, or `http://` only to `127.0.0.1`, `localhost` or `[::1]`; no user info, no fragment.
9. At least one credential field present.

Omitted fields keep their saved values. Result type `UpstoxQrImport.Ok(fields)` | `Rejected(message)`, both with masked `toString`.

### Saving
- Validate everything first, then write. Analytics Token: tested with the existing NIFTY LTP check (a definite rejection blocks saving it; a network error saves it). App keys: existing `saveCredentials` (a changed key or redirect clears today's sign-in). Sandbox token: no network test (the sandbox has only order endpoints).
- `UpstoxOAuthStore` gains an encrypted `sandbox_token` slot and a plain `sandbox_expires_at`. Expiry = the token's JWT `exp` when readable and within 400 days, else saved-at + 30 days. `sandboxToken(now)` returns null once expired. `disconnect()` already clears the whole file.
- JWT `exp` is read for display and expiry only; the signature is not checked.

### Secret handling
- Never logged: no credential reaches `DiagLog`, `UpstoxRestStats` (URL and error code only) or exception messages; data classes holding them mask `toString`.
- No copy buttons; fields use password keyboards with autocorrect off.
- Scanner: zxing `ScanContract` (dependency already present), QR format only, no beep. Photo picker image decoded in memory with zxing core; nothing is copied or kept.

### Generator and docs
- `tools/make_upstox_qr.py` (Python 3.9+, `segno`): asks for each secret with `getpass` (never command-line arguments, so nothing lands in shell history), applies the same rules, writes the PNG locally, refuses to overwrite without `--force`, prints only the path and which fields it holds. No network use.
- `docs/UPSTOX-SETUP.md`: the one live app and its redirect URL, Analytics Token, sandbox app (after §8 is cleared), the format, the script, and that the QR is unencrypted: never use an online QR site, keep it out of cloud photos, delete it after import.
- In-app help dialog mirrors the format and the warning.

## 4. Part 2 — Buy/Sell ticket: Preview and Practice

Modes are derived, never configured: **Practice** when `sandboxToken(now)` is non-null, else **Preview**. There is no live mode.

- **Shared builder** `UpstoxOrderRequest(instrumentKey, side, product, type, quantity, price, tag)`: validates (quantity ≥ 1; LIMIT price > 0) and produces the V3 body. MARKET: price 0, `market_protection` −1. `validity` DAY, `disclosed_quantity` 0, `trigger_price` 0, `slice` false. Tag `marksy-` + 8 random hex.
- **Instrument**: `UpstoxInstruments.keyFor(symbol)`; unknown → "Can't find <symbol> on Upstox" and buttons off.
- **Preview** (always available): with today's sign-in, calls brokerage, margin and funds read endpoints and shows "Charges ₹Y · margin needed ₹Z · available ₹W", warning when margin exceeds available. Not signed in → "Sign in to check charges and funds". Final button: "Preview only · nothing is sent".
- **Practice**: confirm dialog with side, quantity, symbol, product, type, price, ≈ value and "Sent to Upstox's sandbox. Not a real order: nothing is bought and it won't appear in your portfolio." Then `UpstoxSandboxOrderClient.place(request)`; button disabled while sending; no automatic retry. Shows "Practice order accepted · <id>" or the mapped error. Token ≤ 3 days from expiry → warning on the ticket.
- **Errors**: 401 → "Upstox rejected the sandbox token. Import a new one."; `UDAPI100074` → "Orders open 5:30 am to midnight"; `UDAPI1158`/`1159`/`1160` market-protection messages; validation codes → Upstox's message; timeout → "No reply from Upstox's sandbox. Check before trying again."
- **Separation**:
  - `UpstoxSandboxOrderClient` is the only placing code. Host constant `https://api-sandbox.upstox.com`, path `/v3/order/place`, token only from `UpstoxOAuthStore.sandboxToken`.
  - `UpstoxApiClient` keeps "never add order endpoints" and gains only reads (funds, positions, order book, brokerage, margin).
  - `OrderGuardTest` (source scan, like `ThemeGuardTest`) fails if `api-hft.upstox.com` appears anywhere in `app/src/main`, if `order/place`, `order/modify`, `order/cancel`, `order/multi`, `positions/exit` or `/gtt` appear outside the sandbox client, or if `sandboxToken(` is called outside the sandbox client, the ticket's mode check and Settings.
- The four existing call sites (`MarketScreen`, `ScorecardDetailScreen`, `TradingIntelligenceScreen`, `WatchlistScreen`) keep passing `TradeIntent`.

## 5. Part 3 — Portfolio: funds, positions, orders

- New parsers in `upstox/UpstoxAccount.kt`: `UpstoxFunds`, `UpstoxPosition`, `UpstoxOrder`.
- `PortfolioRepository.today(force)`: when signed in, fetches the three concurrently with the daily token; each section fails on its own; **memory only**, refreshed with holdings on pull or sign-in, same one-minute reuse; no polling.
- UI in a new `ui/PortfolioToday.kt` (keeps `PortfolioScreen.kt` from growing):
  - Funds line under the hero: "Available ₹X · used ₹Y"; 00:00–05:30 IST → "Funds update after 5:30 am".
  - Today's positions, only when any: symbol, Intraday/Delivery, net quantity, average, current price, P&L; closed (net 0) rows dimmed with realised P&L; tap opens the stock.
  - Today's orders, only when any: symbol, Buy/Sell, filled/total, type, status (Filled / Open / Rejected · reason / Cancelled; unknown statuses shown as sent), "Filled 10 @ ₹123.40" from `filled_quantity`/`average_price`.
- Signed out: the existing signed-out card covers these sections. Sandbox orders never appear here.

## 6. Testing

TDD for logic only (no layout tests):
- QR parser: every rule in §3 plus valid full and partial payloads (Robolectric, for `android.util.JsonReader`).
- JWT `exp` reader; sandbox expiry fallback; store slot encrypted, expiry cut-off, Disconnect wipe (extend `UpstoxOAuthStoreTest`).
- `UpstoxOrderRequest` body and validation; sandbox client request (URL, headers, body built by a pure function); error mapping.
- Parsers for funds, positions, order book, brokerage and margin from Upstox's documented samples.
- `OrderGuardTest`; `ThemeGuardTest` stays green.
- Targeted runs per change; full suite once per product-code head before each PR.

Device (phone, `install -r`, owner OK each time):
- QR import with a code of **fake values** made by the script: fill-only, masking, each rejection message.
- Preview and Portfolio after the owner signs in (PIN/OTP are the owner's).
- Practice only after §8 is cleared and the owner creates the sandbox token.

## 7. Delivery

Three sequential PRs off `main`, none stacked, each with a whole-phase code review before merge:
1. `feat/upstox-settings-qr`: Part 1, generator, setup doc, this spec.
2. Ticket: Part 2 (ticket mockup board first).
3. Portfolio: Part 3 (Portfolio board update first).
Roadmap entry updated with each. Canvas mirrors anything changed during build.

## 8. Blocker: sandbox app creation

Upstox does not say whether a sandbox app counts toward the one-active-app rule. Until Upstox confirms in writing that creating one cannot delete or invalidate the live app, its keys or tokens: no sandbox app is created, the live app and its keys are untouched, and Portfolio sign-in is unchanged. The owner sends the support request (drafted in chat). Preview mode does not depend on the answer. If the answer stays unclear, Practice stays unused and Preview is the delivered ticket.

## 9. Out of scope

Live orders, static-IP relay or VPN, Access Token Request webhook, GTT, modify/cancel, multi-order, F&O and MCX orders, practice log, passphrase-encrypted QR.
