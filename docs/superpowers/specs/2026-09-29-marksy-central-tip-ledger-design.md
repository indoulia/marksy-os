# Marksy central tip ledger — design

Status: draft for review · 2026-09-29 · Repos: marksy-api (owner), marksy-os, admin-app

## 1. Goal

marksy-api and its DB are the single source of truth for every market call — external tips and Marksy's own
predictions and ratings: intake, parsing, dedupe, daily tracking, outcomes and scorecards. marksy-os captures
and renders only. Trading (broker session, orders, positions, Upstox live data) stays in marksy-os until it
moves to Trady.

Every channel (broker research, news portal, WhatsApp/Telegram group, later YouTube, influencers, agents) and
every caller inside it — Marksy included — gets one universal, auditable scorecard, so no call goes in hiding.

## 2. Invariants

> A **Tip** is the canonical market call. A **Receipt** is one customer's record of having received it.
> Tracking, state, snapshots, outcomes and scores belong to the Tip, never to the Receipt.

1. At most one matchable tip per `match_key` (§5.2); an identical call after the earlier one closed is a new tip.
2. A tip has one or more receipts; a receipt belongs to exactly one customer and at most one tip.
3. Tracking runs once per tip; at most one progress row per tip per session.
4. Scorecards aggregate canonical tips, never receipts. Many receipts for one tip never raise any count.
5. Call terms (channel, symbol, direction, stated entry, target, stop-loss, stated horizon) and `first_seen_at`
   are immutable. Tips and receipts are never deleted.
6. A revised tip is a new tip. The original is tracked on its own terms to its own end.
7. Re-parsing applies only to unparsed receipts. It may create a tip (dated from the receipt's original server
   time) but never alters or duplicates an existing tip.
8. State transitions are idempotent; tracking is deterministic and replayable from market data.
9. Marksy predictions and ratings go through the same ledger, tracker and scorecards as external tips.
10. Customer APIs never expose another customer's identity; admin APIs may.
11. The only customer data leaving the phone is the customer id (from the signed-in session). Private data
    (inbox, timeline, contacts, allow-lists) stays on the phone.
12. Market data that is not reusable for training or learning is not stored. The app shows it straight from
    Upstox; the tracker stores derived facts, never raw intraday bars.

## 3. Current state (audit 2026-09-29)

marksy-os computes on device what the backend should own: `TradeCallParser`, `CallHorizon`, the re-parse in
`MarksyTipPayloadBuilder`, `DailySetups`, the trade-call rule in `NotificationClassifier`, the brand map in
`SourceRegistry`, `RatingEngine` and `RatingCalibration`. It sends raw `title`/`body` to `POST /tips`
(`gateway/MarksyTipPayload.kt:48-49`); for WhatsApp the title is the contact name.

marksy-api already has `POST /tips`, `POST /tips/ingest-text`, `ExternalTip` (`app/models.py:5119`), a per-source
hit-rate scorecard, Marksy predictions with a six-state lifecycle and `DailyPredictionSnapshot`, a PII-free
`User` table, and admin users/clients/sessions. Gaps:

- Dedupe keys on `(source, source_reference)` and the app sends its per-device event key
  (`MarksyTipPayload.kt:72`), so one tip reaching N customers is stored N times.
- No customer↔tip link, no channel or caller entities (`source` is free text).
- Tips get one final row after the horizon: no daily progress, no ACTIVE/INVALIDATED/EXPIRED states.
- `/instruments/{symbol}` has no external tips; the scorecard is hit rate only.
- `prediction_outcome_monitor.record_invalidation` never updates `Prediction.status`, so 12+ modules keep
  treating invalidated predictions as OPEN.

## 4. Data model (marksy-api)

`external_tips` is renamed `tips` (ORM `ExternalTip` → `Tip`) because it now also holds Marksy's calls.

**channels** — `id, name, type, canonical_channel_id, default_horizon_sessions (20), created_at`.
Types: `BROKER_APP, NEWS_PORTAL, SMS_SENDER, WHATSAPP_GROUP, TELEGRAM_CHANNEL, YOUTUBE, MARKSY` (extendable).
`canonical_channel_id` is itself unless merged. **channel_aliases** — `channel_id, alias` (package name or
label), alias unique. Seeded from the app's `SourceRegistry`.

**callers** — `id, channel_id, name, canonical_caller_id`. Optional on tips.

**tips**
- `source_channel_id` (as received, never rewritten), `caller_id` (nullable; may be filled once, never changed)
- `symbol`, `stock_id` (nullable)
- `direction` BUY | SELL | null
- `entry_low`, `entry_high`, `entry_basis` STATED | FIRST_SEEN_PRICE
- `target`, `stop_loss` (nullable)
- `horizon_sessions`, `horizon_basis` STATED | CHANNEL_DEFAULT | ENGINE
- `first_seen_at` (server time of the first receipt)
- `revises_tip_id`, `merged_into_tip_id`, `prediction_id` (nullable)
- `match_key`, `match_key_version`, `parser_version`
- lifecycle (the only mutable fields): `status, entry_status, outcome, reason, entered_session,
  closed_session, closed_at, exit_price, promised_return, actual_return`
  - `status`: ACTIVE | TARGET_HIT | STOP_LOSS_HIT | SOURCE_EXIT | HORIZON_EXPIRED | DIRECTION_HORIZON |
    INVALIDATED | UNSCORABLE | DATA_UNRESOLVED | MERGED_DUPLICATE
  - `entry_status`: WAITING | ENTERED · `outcome`: SUCCESS | FAILURE | EXPIRED | null
- partial unique index on `match_key` where `status = 'ACTIVE'`; an ORM guard rejects term changes and deletes

**tip_receipts** — `id, tip_id (nullable), user_id, device_event_key, kind, medium, app_package,
channel_label, text (cleaned), device_posted_at, recorded_at, parser_version`.
- `kind`: TIP | REVISION | EXIT | UNPARSED
- `medium`: APP_NOTIFICATION | SMS | WHATSAPP | TELEGRAM | EMAIL | MANUAL (admin paste)
- unique `(user_id, device_event_key)` makes retries safe; unique `(tip_id, user_id, medium)` for kind TIP

**tip_daily_progress** — `tip_id, session_date, session_index, entry_status, status_after, return_to_date,
best_return, worst_return, to_target_pct, to_stop_pct, bar_basis (DAILY | INTRADAY)`; unique
`(tip_id, session_date)`. No OHLC is copied — daily prices live in the existing candle store.

Superseded once readers move: `ExternalTipOutcome`, `ExternalTipComparison`, and `DailyPredictionSnapshot`
for scoring.

## 5. Intake

### 5.1 Phone (marksy-os)

- Capture set: packages from `GET /channels/capture-list` plus the customer's own WhatsApp/Telegram
  allow-list. Nothing outside that set leaves the phone.
- `POST /tips/ingest-text` as the signed-in customer (bearer session) with
  `device_event_key, medium, app_package, channel_label, text, device_posted_at`. No title, body or sender.
- `channel_label`: the app name for app notifications; the sender id for SMS; the group name for
  WhatsApp/Telegram, with the individual sender inside a group removed ("Rahul @ StockTips" → "StockTips").
  1:1 chats never leave the phone (decided 2026-09-30).
- The customer's own order/execution notifications from broker apps never leave the phone (decided 2026-09-30).
- Cleaning before send: the customer's username, phone numbers, emails, PAN-like codes
  (`[A-Z]{5}[0-9]{4}[A-Z]`) and digit runs of 8 or more (accounts, folios) are replaced with mask tokens.

### 5.2 Server

1. Re-apply the same cleaning.
2. Resolve the channel by alias (package or label); an unknown alias creates a channel of the medium's type.
3. Parse with the ported `TradeCallParser`, `CallHorizon` and `DailySetups` contract → kind, terms, optional
   caller name.
4. By kind:
   - **TIP** — compute `match_key`. A matchable tip with the same key gets the receipt attached; otherwise
     insert a tip and the receipt. Matchable = ACTIVE, or rejected at intake (§6.2) with `first_seen_at`
     within its horizon, so later copies of a rejected tip attach instead of duplicating it.
   - **REVISION** — new tip with `revises_tip_id` = the latest ACTIVE tip for the same canonical channel,
     symbol and direction. The original is untouched.
   - **EXIT** — attach to the ACTIVE tip(s) for the same canonical channel and symbol (and direction if
     stated) and record a source exit (§6.5). With no ACTIVE tip the receipt stays unlinked as an orphan exit.
   - **UNPARSED** — receipt only, reviewable and re-parseable in admin.
5. If the parse names a caller and the tip has none, set it.

**match_key v1** = sha256 of
`v1|canonical_channel_id|SYMBOL|direction|entry_low|entry_high|target|stop_loss|horizon_sessions`, built
from **stated** terms only (defaults and first-seen prices never enter it). Prices are rounded to the 0.05
tick and printed with 2 decimals; a missing value is `-`. The caller is excluded. Changing the formula bumps
`match_key_version` and re-keys ACTIVE tips in a migration.

**Merging (admin)** sets `canonical_channel_id` (or `canonical_caller_id`). Historical tips keep
`source_channel_id`; scorecards aggregate through the canonical id. ACTIVE tips of both channels are re-keyed;
if two now share a key, the later `first_seen_at` becomes MERGED_DUPLICATE with `merged_into_tip_id` set, its
receipts move to the earlier tip, and it is excluded from scorecards but kept.

## 6. Tracking

### 6.1 Sessions and horizon

- Session 0 is the NSE session containing `first_seen_at` if it arrived during market hours. It is tracked
  only from intraday bars after `first_seen_at`; without them it is skipped.
- Sessions 1..N are the full trading sessions after `first_seen_at`. `horizon_sessions = N`.
- Intraday tips have N = 0 and expire at the session 0 close; without intraday bars they are DATA_UNRESOLVED.
- A missing horizon takes the channel default (20) with `horizon_basis = CHANNEL_DEFAULT`. Word horizons use
  the ported `CallHorizon` rules (1 week = 5 sessions, 1 month = 20).

### 6.2 Rejected at intake (terminal, never tracked)

- No direction → UNSCORABLE (`NO_DIRECTION`).
- Target on the wrong side of the entry midpoint (promised return ≤ 0), or stop-loss on the wrong side →
  UNSCORABLE (`INCONSISTENT_LEVELS`).
- Symbol not resolvable → DATA_UNRESOLVED (`UNRESOLVED_SYMBOL`).

### 6.3 Entry

- A stated entry range starts ACTIVE with `entry_status = WAITING`. A BUY is entered when a bar's low ≤
  `entry_high`; a SELL when a bar's high ≥ `entry_low`.
- No stated entry: the entry is the price at `first_seen_at` (intraday) or else the session 1 open, stored
  once with `entry_basis = FIRST_SEEN_PRICE`; the tip starts ENTERED.
- `entry_mid = (entry_low + entry_high) / 2`. Return fields stay null while WAITING.
- Not entered by the close of session min(5, N) → INVALIDATED (`NEVER_ENTERED`).
- Target touched while still WAITING → INVALIDATED (`TARGET_BEFORE_ENTRY`).
- Entry and an exit in the same session: with intraday bars only bars at or after the entry count; with daily
  bars only, entry is taken first and §6.4 applies.

### 6.4 Exits (entered tips, evaluated session by session)

1. Target and stop-loss both touched in one session: with intraday bars the first touched wins; without them
   the stop-loss wins.
2. Exit price is the level, unless the session opened beyond it (gap), then the session open.
3. Target → TARGET_HIT. Stop-loss → STOP_LOSS_HIT.
4. Horizon: at the close of session N, exit price = that session's official close. A tip with a target or a
   stop-loss → HORIZON_EXPIRED; a direction-only tip (neither) → DIRECTION_HORIZON.

### 6.5 Source exits

An EXIT receipt, or a Marksy engine withdrawing its own call (§7), closes the tip as SOURCE_EXIT. Exit price: the
stated exit price if within that session's low–high; else the intraday price at the exit message's
`first_seen_at`; else the next session's open.

### 6.6 Returns and outcome

- `s = +1` for BUY, `-1` for SELL.
- `promised_return = s × (target / entry_mid − 1)` (null without a target).
- `actual_return = s × (exit_price / entry_mid − 1)`.
- Outcome:
  - TARGET_HIT → SUCCESS
  - STOP_LOSS_HIT → FAILURE
  - SOURCE_EXIT → SUCCESS if `actual_return > 0`, else FAILURE
  - HORIZON_EXPIRED → EXPIRED (neither success nor failure; `actual_return` recorded)
  - DIRECTION_HORIZON → SUCCESS if `actual_return > 0` (price moved the called way), else FAILURE
  - INVALIDATED, UNSCORABLE, DATA_UNRESOLVED, MERGED_DUPLICATE → no outcome
- Direction-only tips have no `promised_return`; none is ever fabricated.
- `closed_at` is the terminal event time (the session close, the exit message time, or `first_seen_at` for
  tips rejected at intake).

### 6.7 Data, schedule, replay

- Daily bars come from the existing candle store. Intraday 1-minute bars are fetched from Upstox on demand
  only where needed (session 0, same-session conflicts, intraday tips, source exits) and are not stored —
  only the derived fact and the bar timestamp used.
- Provisional capture (15:55 IST) updates progress rows only. Terminal transitions use official data only
  (the 08:30 IST confirmation job, EPIC-853).
- Missing data for a due session: the tip waits; still missing 5 sessions later → DATA_UNRESOLVED. Replay and
  backfill never start tracking late — no history means DATA_UNRESOLVED.
- One progress row per tracked session; transitions derive from the rows, so a rerun gives the same result.

## 7. Marksy calls in the ledger

- Every rule in §6 (entry, exits, horizon, statuses, outcomes, returns) and §8 (metrics, trust score,
  filters) applies to Marksy's calls unchanged. There is no Marksy-specific tracking or scoring rule.
- Channel `Marksy` (type MARKSY) with callers `Prediction engine` and `Rating engine`.
- A published prediction becomes a tip with `prediction_id`, its stated terms, `first_seen_at` = publish time
  and `horizon_basis = ENGINE`.
- Marksy withdrawing its own prediction (today's decay/invalidation in `prediction_outcome_monitor`) is a
  source exit: SOURCE_EXIT at the price then. Marksy cannot drop a losing call by invalidating it.
- `Prediction.status` is synced from the tip's state. The monitor's price checks retire in favour of the
  tracker; its model-driven invalidation emits exit events.
- Rating engine (ported `RatingEngine` + `RatingCalibration`): SHORT = 20 sessions, LONG = 250. A BUY or SELL
  verdict creates a direction-only tip (entry = price at rating time, `entry_basis = FIRST_SEEN_PRICE`); a
  verdict change closes it as SOURCE_EXIT; otherwise it ends as DIRECTION_HORIZON. HOLD and NOT_ENOUGH_DATA create no tip; all verdicts stay in rating
  history. Inputs the backend does not already store are fetched from Upstox at compute time and not kept.

## 8. Scorecards

No persisted aggregate table. Every customer, admin and dashboard scorecard calls one shared function:

`scorecard(scope, scope_id?, period | start_date+end_date, date_basis, horizon_bucket?, as_of)`

- `scope`: channel (canonical, so merged channels roll up) | caller | Marksy engine | customer (tips the
  customer holds a receipt for, each counted once) | all.
- `as_of`: the calculation timestamp. A tip counts as terminal only if `closed_at ≤ as_of`; otherwise it is open.
- It reads only canonical tips and their progress/outcome data. Receipts never affect counts or percentages.
  MERGED_DUPLICATE tips are excluded entirely. The result is deterministic and reproducible.

### 8.1 Metrics

Core counts:
- Total tips = unique canonical tips matching the filters (includes unscorable and invalidated)
- Open = tips without a terminal status as of `as_of`
- Successful = TARGET_HIT + successful SOURCE_EXIT + successful DIRECTION_HORIZON
- Failed = STOP_LOSS_HIT + failed SOURCE_EXIT + failed DIRECTION_HORIZON
- Expired = HORIZON_EXPIRED
- Completed = Successful + Failed + Expired
- Exited = SOURCE_EXIT (a subset of Successful + Failed)
- Invalidated · Unscorable · Data unresolved

Performance:
- Success % = Successful ÷ (Successful + Failed) × 100
- Failure % = Failed ÷ (Successful + Failed) × 100
- Hit rate % = Successful ÷ (Successful + Failed + Expired) × 100
- Average / total actual return = mean / sum of `actual_return` over Completed tips
- Average / total predicted return = mean / sum of `promised_return` over Completed tips with a target
- Return realization % = sum of `actual_return` ÷ sum of `promised_return`, both over Completed tips with a
  target, × 100
- Average days to completion = mean trading sessions from `first_seen_at` to the terminal event (session 0 =
  0) over Completed tips

Unscorable, invalidated and data-unresolved tips appear in the counts only — never in percentages or returns.

### 8.2 Trust score

- `n` = Completed. With `n < 10` the trust score is null ("not enough history").
- `p` = hit rate as a fraction. `W` = Wilson 95% lower bound:
  `(p + z²/2n − z·√(p(1−p)/n + z²/4n²)) / (1 + z²/n)`, `z = 1.96`.
- `G` = Σ max(actual_return, 0) ÷ Σ |actual_return| over Completed tips (0.5 if the denominator is 0).
- `trust_score = round(100 × W × (0.5 + 0.5 × G))`, range 0–100.
- Computed for whatever filters are applied. The headline trust score is Lifetime, all horizons.
- Invalidated tips never enter it; their count is always shown beside it.

### 8.3 Filters (API contract, not UI-only)

- Presets: Today, Yesterday, This week, Last week, This month, Last month, Last 7 days, Last 7 trading days,
  Last 30 days, Last 90 days, Lifetime. Custom: `start_date`, `end_date` (inclusive). Boundaries use IST
  (Asia/Kolkata); weeks start Monday; trading days are NSE sessions.
- Date basis: `first_seen_at` by default, so "September" means tips first seen in September. `basis=terminal`
  filters on `closed_at` instead and is only used when an endpoint asks for it explicitly.
- Horizon bucket (optional): Intraday (0), Up to 1 week (1–5), Up to 1 month (6–20), Longer than 1 month (>20).
- Optional narrowing by channel, caller or Marksy engine.
- Every response echoes the applied filter and the resolved start/end timestamps.
- The same semantics serve customer, admin, channel, caller, Marksy engine scorecards and dashboard cards.

## 9. APIs (marksy-api)

Customer (bearer session):
- `POST /tips/ingest-text` (§5.1) → `receiptId, tipId | null, kind, matched`
- `GET /me/tips?status=` → tips the customer holds receipts for: terms, status, entry status, outcome, latest
  progress, channel, caller, headline scorecard, `receivedVia[]`, `alsoReceivedBy` (count only)
- `GET /me/scorecard` (§8.3 filters)
- `GET /tips/{id}` with its progress series
- `GET /instruments/{symbol}` gains `calls.marksy.engines[]` and `calls.external.channels[]`, each with its
  scorecard and tips (ACTIVE plus the last 90 days closed; external tips carry their caller)
- `GET /scorecards?entity=channel|caller`, `GET /scorecards/{entity}/{id}`, `GET /scorecards/summary` (§8.3)
- `GET /channels/capture-list`
- On each tip state change, every receipt holder is alerted through the existing alert delivery
  (`app/alert_delivery.py`).

Admin (admin scope):
- `/admin/channels` — list, rename, aliases, default horizon, merge
- `/admin/callers` — list, rename, merge
- `/admin/tips` (filter by channel, caller, customer, status, period) and `/admin/tips/{id}/receipts`
  (customer ids, medium, times)
- `/admin/users/{id}/tips`
- `/admin/receipts?kind=UNPARSED|EXIT` — review unparsed and orphan-exit receipts, re-parse
- `/admin/scorecards` — same aggregation and filters as §8

`/tips`, `/tips/scorecard` and `/tips/{id}/marksy-view` remain until their readers move, then are removed.

## 10. marksy-os

- Delete: `TradeCallParser`, `CallHorizon`, the parsing in `MarksyTipPayloadBuilder`, `DailySetups`,
  `RatingEngine`, `RatingCalibration`, the trade-call rule in `NotificationClassifier`, the brand map in
  `SourceRegistry`.
- Add: tip text cleaner, the §5.1 payload with bearer auth, a cached capture list.
- Screens: stock-page calls box (a Marksy group per engine and an External group per channel, each with its
  record), My tips, Scorecards with the §8.3 filters. Existing Marksy components and compact-header
  conventions.
- Unchanged: local notification store, allow-lists, inbox/timeline, trading, and direct Upstox data (live
  quotes, charts, option chain, news, fundamentals) shown without going through Marksy.

## 11. admin-app

Screens for Channels (rename, aliases, merge), Callers, Tips with a receipts drawer, Unparsed / orphan-exit
review, customer → tips, and Scorecards with the §8.3 filters. Built on the existing `PageHeader`, `Toolbar`,
`DataTable` and `tokens.css`.

## 12. Backfill

- Each `external_tips` row gets a channel via the alias of its `source`, stated terms from its columns, and a
  `match_key`. Rows sharing a key while open collapse into one tip; the others become receipts.
- The receipt customer is the API key's `bound_user_id`, else the system user `legacy-unknown`.
- Existing Marksy predictions are registered as tips.
- Tracking replays from `first_seen_at` (= `received_at` for legacy rows) under §6; no history →
  DATA_UNRESOLVED.

## 13. Phases

Each phase gets its own implementation plan, TDD, targeted tests and PRs.

0. `Prediction.status` sync in `record_invalidation` and its decay branch (small standalone fix).
1. Ledger and intake: table rename, channels, callers, receipts, parser port, matching, cleaning, revisions,
   exits, capture list.
2. Tracker, outcomes and progress rows; Marksy predictions into the ledger; rating engine port.
3. Scorecards, customer and admin APIs, state-change alerts.
4. marksy-os: cleaner and new payload (can ship after 1), then deletions and new screens (after 3).
5. admin-app screens.
6. Backfill; retire superseded tables and routes.

Tests focus on real logic: matching and dedupe, cleaning, tracker fixtures (bar sequences → expected states,
including gaps, same-session conflicts, never-entered, missing data), and scorecard/trust golden values. The
existing tip and prediction tests (`tests/test_api_tips*.py`, `tests/test_external_tip_*.py`,
`tests/test_prediction_outcome_monitor.py`) are the regression set.

## 14. Out of scope

Trady; YouTube/Telegram/influencer collectors (their channel types exist, collectors come later); changes to
the app's direct Upstox use; existing `/market/*` and `/dashboard/snapshot`; the Flutter web client.

## 15. Decisions to confirm in review

- A tip with a target or stop-loss reaching its horizon is EXPIRED (neither success nor failure); a
  direction-only tip (neither) is scored at horizon end as DIRECTION_HORIZON.
- Return realization compares like with like: its numerator sums `actual_return` only over the tips that have
  a target, so it can differ from the displayed Total actual return, which includes direction-only tips.
- Marksy withdrawing its own call counts as a source exit at that moment's price.
- ~~1:1 WhatsApp/Telegram contact labels are sent as channel labels~~ — reversed 2026-09-30: 1:1 chats stay on the
  phone; only group names are sent.
- Defaults: 5-session entry window, 20-session default horizon, rating SHORT = 20 / LONG = 250 sessions,
  10 completed tips before a trust score shows.
