# MarksyOS Real Login (Session-Based Auth) — Design

## Context

Investigation during the Upstox-integration work found two things that change this
app's credential architecture:

1. The `X-Marksy-Integration-Key` mechanism `MarksyTipsApiClient` was built against
   is dead code server-side — no middleware, no handler, nothing in `marksy-api`
   checks for that header anywhere. It exists only as a stale comment in
   `app/settings.py`. Whatever has appeared to work about the existing "Gateway"
   credential has not been authenticating via that path.
2. `marksy-api`'s real, designed-for-this auth system — `POST /api/v1/auth/login`,
   session bearer tokens, `require_scope(SCOPE_MARKSY)` — already backs `/tips`,
   `/market`, `/instruments`, `/predictions`, and `/ipos` identically. The existing
   config-only user gets `DEFAULT_BOOTSTRAP_SCOPES = {marksy, admin}` on login,
   with zero extra provisioning. This is the same mechanism the Flutter/admin
   client already uses.

This replaces the Integration Key and Market API Key credentials/UI built in the
two prior sub-projects with one real login, reusing the existing Marksy account
(no new service user).

## Goals

- A login screen (username/password) calling `POST /auth/login`, replacing the
  "Configure Gateway" manual-key-entry screen.
- A "Remember me" checkbox, mirroring `admin-app`'s exact behavior
  (`src/auth/AuthContext.tsx`/`src/api/client.ts`): checked persists the session
  across app restarts; unchecked keeps it in memory only for the current process.
- `RealMarketApiClient` and `MarksyTipsApiClient` both switch to
  `Authorization: Bearer <session token>`, dropping their current custom headers
  (`X-API-Key` and the non-functional `X-Marksy-Integration-Key`).
- Proactive refresh: every authenticated call checks the stored session's
  `expiresAt` first and calls `POST /auth/refresh` ahead of expiry if needed —
  refresh only works on a still-valid token (verified from `api/services/auth.py`:
  an already-expired token gets a hard `MRA_SESSION_EXPIRED`, not a silent renew),
  so reactive-only refresh would strand a background caller mid-expiry.
- The existing background tip-delivery worker (`TradingDeliveryWorker`) keeps
  working unattended by reading the same stored, refreshed session token — no new
  scheduling logic, matching how it already reads a stored credential today.

## Non-goals

- No new Marksy user/service account — logs in as the existing account.
- No change to Upstox integration (separate sub-project, separate credential,
  unaffected by this).
- No UI for `/auth/logout` beyond a "Sign out" action in the existing settings
  surface (replacing "Remove Credential") — no broader account-management screens.
- No marksy-api changes. Everything needed (`/auth/login`, `/auth/refresh`,
  `DEFAULT_BOOTSTRAP_SCOPES`) already exists and already works for the Flutter
  client.

## Architecture

### `AuthSessionStore` (new, replaces the credential fields in `SecureCredentialStore`)

Holds `sessionToken`, `userId`, `expiresAt` (epoch millis), and the `remember`
choice. When `remember` is true, values are Keystore-encrypted and persisted
(mirroring `SecureCredentialStore`'s existing AES/GCM pattern) so they survive app
restarts. When false, they live only in an in-process in-memory holder — lost on
process death, which is a real, honest platform difference from a browser tab
(Android kills backgrounded processes far more readily): unchecked "remember me"
means background tip delivery can lapse if the process is killed before a queued
notification is forwarded. This is stated as a known tradeoff, not silently
glossed over — "remember me" trades that reliability for not persisting the
session on a shared/borrowed device.

The existing `getIntegrationKey`/`setIntegrationKey`/`clearIntegrationKey` and
`getMarketApiKey`/`setMarketApiKey`/`clearMarketApiKey` methods, and the
"Integration key"/"Market intelligence key" UI blocks, are removed — not
deprecated alongside the new mechanism, removed, since neither ever
worked against the real server for the first one, and the second is now
redundant with login.

### `AuthApiClient` (new)

`HttpURLConnection`-based, matching every other client in this codebase:
- `login(userId, password): SessionResponse` → `POST /auth/login`
- `refresh(currentToken): SessionResponse` → `POST /auth/refresh`,
  `Authorization: Bearer <currentToken>`
- `logout(currentToken): Boolean` → `POST /auth/logout`

`SessionResponse` fields used: `sessionToken`, `userId`, `expiresAt`. (`issuedAt`,
`readOnly` parsed too since they're on the wire, not necessarily surfaced in UI
yet.)

### `AuthRepository` (new)

Owns the "is there a usable session, refresh if needed" logic every other client
calls through: `suspend fun currentToken(): String?` — returns null if never
logged in; if the stored session expires within a configurable buffer, refreshes
first (surfacing a `SessionExpiredApiError` by falling back to null, which every
caller already treats as "not configured/unavailable" via the existing
`MarketDataState.Unavailable` pattern — a lapsed session degrades a screen
exactly like a missing credential does today, never crashes).

### `MarksyTipsApiClient` and `RealMarketApiClient` changes

Both drop their constructor's credential parameter in favor of reading through
`AuthRepository.currentToken()` per call, sending
`Authorization: Bearer <token>` instead of their current headers. Both already
have an "unconfigured" fallback path (`UnconfiguredMarksyGatewayClient`,
`MarksyGatewayProvider.marketIntelligenceClient(): MarketApiClient?` returning
null) — a null/expired session routes through those exact same existing paths,
so no screen-level behavior changes beyond the credential source.

### UI

- `LoginScreen` — userId + password fields, "Remember me" checkbox (default
  checked, matching `admin-app`'s `useState(true)`), Sign In button, error text
  on 401/503.
- Replaces "Configure Gateway" in the More screen's settings list; "Marksy
  Gateway" card becomes something like "Marksy Account" showing signed-in
  userId or "Not signed in."
- "Sign out" calls `/auth/logout` and clears the stored session.

## Testing

Same conventions throughout: pure DTO/parse tests for `SessionResponse`;
`AuthSessionStore` tested like `SecureCredentialStore` (round-trip, remember vs.
not, independent of anything else in `SharedPreferences`); `AuthRepository`
tested with a fake `AuthApiClient` covering never-logged-in, valid, needs-refresh,
refresh-fails-because-truly-expired; `LoginScreen` Compose tests for the
remember-me checkbox's effect and error states.

## Risks / open items carried into planning

- Exact refresh-buffer duration (how far ahead of `expiresAt` to proactively
  refresh) is a planning-time choice, not guessed here — needs to be generous
  enough that a backgrounded WorkManager job's occasional multi-hour delay
  doesn't miss the window, without refreshing needlessly often.
- Whether `TradingDeliveryWorker`/`TradingDeliveryScheduler` need any changes
  beyond reading through the new `AuthRepository` instead of the old credential
  store — to be confirmed by reading those files during implementation, not
  assumed here.
- `readOnly` on `SessionResponse` (a session can be marked read-only per EPIC-814)
  is parsed but not yet acted on anywhere in this design — if a read-only session
  should block the tip-submission POST specifically, that's a planning-time
  decision, not silently assumed.
