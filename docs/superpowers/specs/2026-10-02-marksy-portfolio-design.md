# Marksy Portfolio — design

Status: approved direction 2026-10-02 (mockup "Portfolio, triage first",
https://claude.ai/artifact/AriQ8EKFyYHvJ7GU9nB5wr) · Repo: marksy-os only. marksy-api: no change.

## 1. Goal

Replace the "Portfolio — coming soon" placeholder (Market › Portfolio) with a triage-first view of what the user
owns. It answers two questions first: how am I doing today and overall? Which holdings need a look?

## 2. User decisions (2026-10-02)

1. **Providers.** Upstox only, behind a small provider interface. Kite, Groww and CDSL/NSDL appear in the connect
   UI as "coming soon" providers and have no implementation.
2. **Mutual funds.** A placeholder section only, with no NAV source and no implementation.
3. **Holdings access is a daily Upstox sign-in (OAuth authorization-code flow).** Holdings go from api.upstox.com
   straight to the phone. Nothing passes through the Marksy backend, so the rule that only the customer id leaves
   the phone still holds. The user enters their own Upstox app's API key and secret once. These and the access
   token are Keystore-encrypted and only ever sent to api.upstox.com. The Analytics Token needs a static IP for
   account APIs, so it is not used for holdings. The integration is read-only: there are no order, modify or GTT
   calls anywhere.
4. **"Needs a look" thresholds** are fixed constants in v1 and not user-editable:
   - a holding 15% or more below its average cost;
   - a price within 4% of one of the user's price alerts;
   - a single holding at 20% or more of portfolio value;
   - a move of 4% or more either way today.

## 3. Upstox OAuth facts (read 2026-10-02)

- Authorize: `GET https://api.upstox.com/v2/login/authorization/dialog?response_type=code&client_id=…&redirect_uri=…&state=…`.
  After login Upstox redirects to `<redirect_uri>?code=…&state=…`.
- Token: `POST https://api.upstox.com/v2/login/authorization/token`, `application/x-www-form-urlencoded`, with
  `code`, `client_id`, `client_secret`, `redirect_uri` and `grant_type=authorization_code`. The success body
  carries `access_token` (plus profile fields we don't keep). Errors include UDAPI100057 (invalid code),
  UDAPI100069 (invalid credentials) and UDAPI100070 (invalid redirect_uri).
- Expiry: an access token is valid until 3:30 AM IST the next day, whenever it was issued.
- Redirect URI: it must exactly match the one registered on the Upstox app. Localhost URLs are accepted, and the
  docs say to open the login page "in a Webview (or similar)". The docs don't say whether a custom scheme
  (`marksy://…`) is accepted.
- Holdings: `GET https://api.upstox.com/v2/portfolio/long-term-holdings` with `Authorization: Bearer <access_token>`.
  Each entry has `isin`, `company_name`, `trading_symbol`, `instrument_token` (`NSE_EQ|INE…`), `exchange`,
  `quantity`, `average_price`, `last_price`, `close_price`, `pnl`, `day_change` and `day_change_percentage`.

## 4. Redirect approach (decision)

**Use an in-app WebView sign-in screen that intercepts the redirect. No server is needed.**

- Constraint: a Custom Tab can only hand the code back through a deep link, which needs one of two things:
  - a verified https App Link, which needs a hosted `assetlinks.json` (a server);
  - a custom scheme, which Upstox doesn't document as accepted and which we can't check without the user's
    developer console.

  A WebView sidesteps both. Upstox documents the WebView route, and the redirect never has to resolve.
- The user registers a loopback redirect on their Upstox app. The default is `http://127.0.0.1/marksy-upstox`,
  and the field is editable if they already registered another one. The WebView catches any navigation whose
  scheme, host, port and path match the redirect, so it never loads. Even if a load slipped through, it would go
  to the phone's own loopback, not a third party, and cleartext is blocked anyway.
- `state` is 32 random hex characters from SecureRandom and is checked on return. A mismatch, a missing code or
  an `error` parameter fails the sign-in.
- The WebView has JavaScript on (Upstox's login needs it) and DOM storage on. File and content access are off,
  and there is no JavaScript interface. Main-frame navigation is allowed only to https `upstox.com` hosts and the
  redirect; anything else is blocked with a message.
- Credentials never travel in Intent extras: the sign-in activity reads them from the encrypted store. Upstox's
  login cookies stay in the app's private WebView storage, so tomorrow's sign-in can be shorter. Disconnect clears
  them.

## 5. Architecture

```
upstox/UpstoxOAuth.kt          authorize URL, redirect parsing, token form body, token response, expiry (pure)
upstox/UpstoxOAuthStore.kt     Keystore-encrypted slots: api key, secret, redirect, access token (+issuedAt), holdings cache, hidden
upstox/UpstoxHoldings.kt       holdings JSON parser (pure)
upstox/UpstoxSignInActivity.kt WebView sign-in; exchanges the code and saves the token
portfolio/Portfolio.kt         Holding, HoldingsSnapshot (+JSON), HoldingsResult, HoldingsSource, PortfolioProvider, catalog
portfolio/UpstoxHoldingsSource.kt  maps Upstox holdings → Holding; 401/expired → SignedOut
portfolio/PortfolioMath.kt     valuation, totals, period P&L, ranking, allocation, value series (pure)
portfolio/PortfolioFlags.kt    Needs-a-look rules and hide-till-09:15 (pure)
portfolio/PortfolioRepository.kt   cache, refresh, sectors, candles; Upstox only
ui/PortfolioScreen.kt          the page
```

- `HoldingsSource` is the provider interface (`suspend fun fetch(now): HoldingsResult`). A `PortfolioProvider`
  is an id, a name, a note and a nullable source; a null source means "coming soon".
- **Market data** (candles, sector profile) uses the Analytics Token if one is saved, and the OAuth token
  otherwise. Both go only to api.upstox.com. **Live prices** come from the existing `UpstoxFeed` via
  `UpstoxFeed.acquire(owner, instrumentKeys)`, keyed by each holding's `instrument_token`. Without an Analytics
  Token the feed is idle, and prices are the holdings call's `last_price`, refreshed on pull.
- **Privacy guard:** a static test checks that nothing in `portfolio/` references the Marksy backend
  (`MarksyContainer`, `MarketApiClient`, `MarketIntelligenceRepository` or `gateway`).

## 6. Calculations

- value = qty × price · invested = qty × average · total P&L = value − invested · total % = total / invested.
- Today (1D) P&L = qty × (price − previous close). The previous close is the live `cp`, falling back to the
  holdings `close_price`.
- Period P&L (1W, 1M, 3M, 1Y) = qty × (price − base). The base is the daily close on or before the period start,
  with the same 5-day slack `UpstoxCandles.returns` uses. **It assumes today's holdings for the whole period**,
  and the card says so. The period % is P&L ÷ (value − P&L) over the holdings that have a base.
- Weight = value ÷ portfolio value. The weight bar under a row is full at 25%.
- Chart: the portfolio value series is Σ qty × close at each candle time, carrying a holding's last close
  forward. 1D uses 5-minute intraday candles, falling back to the last session; periods use daily closes. NIFTY
  50 shows the same period's change beside it.
- Allocation: by sector (from the Upstox fundamentals profile; "Other" when unknown), by holding, or by type
  (Stocks, ETFs). The bar shows the top 4 sectors plus "N more".
- ETF: `trading_symbol` ends in `BEES`, `ETF` or `IETF`, or the company name contains "ETF". Everything else from
  the equity holdings call is a stock.

## 7. Needs a look

- Each flag has a kind, a severity and a reason:
  - **Day move**, |today %| ≥ 4: "Fell 5.0% today" is critical; "Rose 4.2% today" is positive.
  - **Below average**, total % ≤ −15: "15.3% below your average" is critical.
  - **Near alert**, closest alert with |price − alert| ÷ price ≤ 4%: "3.9% from your ₹52.00 alert" is a warning.
  - **Concentration**, weight ≥ 20%: "22% of your portfolio" is a warning.
- The card shows the most severe reason, the rupee move today, "type · sector · n more reasons", "Name · ₹price",
  a body explaining the reasons, and Open stock, Alert and Hide today. Tapping the card lists every reason in a
  MarksyDialog.
- Cards are ordered critical first, then by |today ₹|. The top 3 show, and "N more need a look" expands the rest.
- "Hide today" hides a holding's card until the next 09:15 IST (stored encrypted). The row keeps its dot. A
  footer reads "N hidden till tomorrow · Show".

## 8. Page layout (matches the mockup)

- **Header note** (no in-page heading): one fact after the section, so it fits beside the header icons at 360dp.
  It reads "Portfolio · 3 need a look", "Portfolio · 13 holdings", "Portfolio · signed out", "Portfolio · not
  connected" or "Portfolio · no holdings yet". The header's own LIVE mark covers live prices, so the card's badge
  appears only for "Closed" or "as of 10:42".
- **Top card:** Current value with a LIVE, Closed or "as of 10:42" badge, and Invested.
  - Two boxes, Today (or the period's name) and Total, each with ₹ and %. Tapping one sets what every row shows.
  - A sparkline for the period, with "start · NIFTY 50 ±x.xx% · now" under it.
  - Period Pills (1D 1W 1M 3M 1Y) in a FlowRow.
  - A caveat when the period isn't 1D: "Assumes today's holdings for the whole period."
  - The allocation bar. Tap it to expand Sector, Holding and Type pills with bars; the Holding view draws the 20%
    line.
- **Needs a look** lane (red lamp, count), then the cards.
- **Holdings:** lanes "STOCKS n … ±₹sum" and "ETFS n … ±₹sum". Each row shows the symbol, a flag dot, the ₹ move
  and % for the chosen metric, "qty sh · avg ₹x", "LTP ₹y" and the weight bar.
  - Tapping a row expands Quantity, Avg price, LTP, Invested, Current, Weight, period P&L, Total P&L and Total
    return, plus "Open SYMBOL", "Price alert" and "Less".
- **Mutual funds** lane: one placeholder row, "Mutual funds · coming soon — Marksy has no NAV source yet."
- **Ranking:** rows rank by |₹ move| for the chosen metric. Live ticks change numbers, never order. The order
  settles again on load, pull-to-refresh, and a change of metric, period or sort.
- **Floating stack** (OneHandControls):
  - Market section filter, which already exists; its X goes back to Watchlist.
  - Search holdings (symbol or name).
  - "Sort and show": a MarksyDialog with Pills.
    - Sort: Biggest ₹ move, Best %, Worst %, Largest holding, Name A–Z.
    - Show: All, Stocks, ETFs.
- **States:**
  - Not connected: the connect card ("Sign in to Upstox", then Kite, Groww and CDSL/NSDL as coming-soon Pills,
    then "Read-only. Holdings stay on this phone.").
  - Loading.
  - Signed out: a warning card ("Upstox ends every sign-in at 3:30 am", "Holdings as of <time>; prices are
    live.", "Sign in to Upstox") above the stale holdings.
  - Empty.
  - Network failure: cached holdings plus a one-line caveat.
- **Connect dialog** (MarksyDialog): API key, API secret and redirect URL (prefilled), with a short "how to create
  an Upstox app" note. Continue saves the credentials and opens the sign-in.
- **Settings › Upstox** gains a "Holdings sign-in" section: its status, Change app keys, and Disconnect. Disconnect
  sits behind a confirmation and clears the keys, the token, the holdings cache, the hidden list and the WebView
  cookies. Destructive actions stay off the floating stack.
- Colours: gains are emerald and losses RedUrgent. Warnings are YellowImportant and weights BlueFinance. Pills
  are the existing `Pill`. Popups are `MarksyDialog`.

## 9. Token lifecycle

- `expiresAt(issuedAt)` is the first 03:30 Asia/Kolkata strictly after issuedAt. `accessToken(now)` returns null
  once that time passes.
- A 401 from holdings clears the access token and keeps the keys, and the page shows the signed-out state with
  the cached holdings.
- The secret, the token and the key are never logged, never put in an Intent, and never sent anywhere except
  api.upstox.com over https. Token exchange error messages carry Upstox's error text only, never the request.

## 10. Out of scope for v1 (recorded)

- Kite, Groww and CDSL/NSDL providers, CSV import, manual entry and mutual fund holdings or NAV.
- Today's buys tag ("+5 today"), which needs the positions API.
- Stock detail's "Your holding" card.
- Per-flag mute and undo toasts.
- Marksy-call stop distance as a flag: SPG-001 publishes no calls today.
- User-editable thresholds.
- Upstox `extended_token`, which Upstox issues only to multi-client apps.

## 11. Tests (logic only)

- Holdings parsing against Upstox's documented sample.
- The Upstox → Holding mapping (ETF detection, zero quantity dropped).
- OAuth: authorize URL, redirect matching and state, token body and response, and error parsing.
- Expiry before and after 03:30.
- The encrypted store round trip with no plaintext, and that clearing the token keeps the keys.
- Valuation and totals, period P&L, ranking, allocation and value series.
- Each flag at its boundary, the hide-until time, and the privacy guard.
