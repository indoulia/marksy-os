# Tip Ledger Phase 3b (Scorecards, Customer APIs, State-Change Alerts) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Serve the tip ledger to customers and admins. Build the single §8 scorecard function (metrics, trust score, filters), the §9 customer APIs (`/me/tips`, `/me/scorecard`, `/tips/{id}` with progress, `/instruments/{symbol}` calls, `/scorecards/*`) and `/admin/scorecards`, and alert every receipt holder when a tip they received is entered or closes.

**Architecture:**
- `app/tip_scorecard.py` (new) is pure:
  - `compute_metrics(facts, as_of)` covers §8.1 and §8.2 over `TipFact`s;
  - `resolve_window(filters, as_of, holidays)` covers the §8.3 IST windows.
- `app/tip_scorecard_query.py` (new) is the only DB reader for scorecards:
  - a `Directory` of channels and callers with their canonical roots;
  - one narrow `SELECT` on `tips` per call, with the scope, window, horizon and narrowing pushed into SQL;
  - `scorecard(...)` (the §8 signature), `scorecards_by_entity`, `scorecard_summary` and `headlines`.
- API layer (new): `api/schemas/scorecards.py`, `api/schemas/ledger.py`, `api/services/scorecards.py`, `api/services/ledger_tips.py`, and the routers `scorecards`, `admin_scorecards` and `me`. `/tips/{id}` and `/instruments/{symbol}` gain fields; none of their existing fields change.
- `app/tip_alerts.py` (new) holds a stateless sweep over committed tip state. The official `TIP_TRACKING` pass runs it after `track_tips`. It writes through the existing `RecommendationAlert` + `AlertDelivery` mechanism (`create_alert_from_tip_state` in `app/recommendation_alerts.py`, `record_delivered` in `app/alert_delivery.py`). `GET /alerts` items gain `tipId`.
- No migration and no ORM change.

**Tech Stack:** Python 3.12, FastAPI 0.139, SQLAlchemy 2.0.52 (`Mapped`/`mapped_column`), Alembic 1.19, Pydantic v2, pytest on SQLite 3.49 (tests) and PostgreSQL (prod).

**Spec:** `C:\AIAgent\marksy-os\docs\superpowers\specs\2026-09-29-marksy-central-tip-ledger-design.md`. This plan uses:
- §2 invariants 4, 10 and 11;
- §4 `tips`, `tip_receipts` and `tip_daily_progress`;
- §5.2 merging;
- §6.6 outcomes;
- §7;
- §8 in full;
- §9 customer APIs, `/admin/scorecards` and state-change alerts;
- §13 phase 3.

Plans for context:
- Phase 2a: `C:\AIAgent\marksy-os\docs\superpowers\plans\2026-09-29-tip-ledger-phase-2a-tracker.md` (`data_basis`; its decision 10 leaves orphan PROVISIONAL rows to Phase 3 readers).
- Phase 2b: `C:\AIAgent\marksy-os\docs\superpowers\plans\2026-09-30-tip-ledger-phase-2b-marksy-predictions.md` (Marksy tips: MARKSY channel, caller "Prediction engine", no receipts, MERGED_DUPLICATE followers).

## Global Constraints

- **Repo and worktree.**
  - Repo: `C:\AIAgent\marksy-api`.
  - Work in a git worktree at `C:\AIAgent\marksy-api-phase3b` on branch `feat/tip-ledger-scorecards`.
  - Create it from `origin/main` **after Phase 2b has merged**. Task 1 checks this.
  - Phase 2c (rating engine) may or may not have merged. Nothing here depends on it: an engine is any canonical caller of a MARKSY channel, so "Rating engine" works the day its tips exist.
- **No migration.**
  - This plan adds no table, column or index (decision 1 gives the cost reasoning).
  - Task 1 and Task 8 run `python -m alembic heads` and must see exactly one head, the one `origin/main` already has (`0183_tips_prediction_unique`, or a later 2c revision).
  - If you think an index is needed, stop and report. Do not add a migration in this phase.
- **Commits, PR, merge and deploy.**
  - Commit once per task. At the end, open a PR with `gh pr create`.
  - **Merging and deploying are authorized:**
    - Once the Task 8 regression set is green, merge with `gh pr merge --merge --delete-branch`.
    - Then deploy with `ssh marksy@103.178.160.233 'bash ~/deployment/server/vps-deploy.sh'`.
  - GitHub Actions don't run (account billing), so GitHub showing UNSTABLE is expected and is not a blocker.
- **Trailers.** Commit messages end with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`. PR bodies end with `🤖 Generated with [Claude Code](https://claude.com/claude-code)`.
- **Tests.**
  - Run only the `python -m pytest tests/<file>.py -v` commands given in each step.
  - Never run the full suite: it takes about 2 hours, and 4 tests already fail on main locally.
  - If a listed test fails, run the same file on `main` in `C:\AIAgent\marksy-api`. If it also fails there, it is pre-existing: note it in the PR and don't fix it.
  - Known pre-existing failures in this plan's files (seen on the 2b branch, 2026-09-30, with and without this plan's code):
    - `tests/test_openapi_contract_freshness.py::test_the_ipo_capability_is_still_false`
    - `tests/test_api_tips.py::test_a_consensus_suppressed_tip_shows_marksys_numbers_not_a_blank_no_view`
    - `tests/test_api_tips.py::test_the_marksy_view_of_an_advisory_tip_carries_its_numbers`
    - `tests/test_api_tips.py::test_an_advisory_tip_card_carries_marksys_numbers_on_the_dashboard`
  - This plan's code was dry-run on a scratch copy of the 2b branch. Every new test passed, and the rest of the Task 8 regression set passed apart from the four failures above.
- **Pytest file DB.** `tests/conftest.py` gives the app engine a file DB at `%TEMP%\marksy-pytest-default.db`, and `create_all` never alters an existing table. 3b changes no ORM schema, but 2b (and maybe 2c) did, so Task 1 deletes that file once.
- **OpenAPI contract.** `tests/test_openapi_contract_freshness.py` fails whenever `api/*` changes without regeneration. Every task that touches `api/` runs `python scripts/export_openapi.py` and commits `docs/api/openapi.json`.
- **Code style.** Follow the repo:
  - no ORM relationships;
  - camelCase response fields;
  - Decimals serialize as JSON strings, as every existing endpoint does;
  - the `success(...)` / `cursor_paginated(...)` envelopes;
  - comments are one line and explain a non-obvious WHY only;
  - no unrelated refactors.
- **SQLite and time zones.** SQLite drops tzinfo on read and stores the wall clock. Every datetime compared in SQL is UTC, and every value read is passed through `aware_utc(...)`.
- **Spec §2, invariants 4, 10 and 11, verbatim:**
  - "Scorecards aggregate canonical tips, never receipts. Many receipts for one tip never raise any count."
  - "Customer APIs never expose another customer's identity; admin APIs may."
  - "The only customer data leaving the phone is the customer id (from the signed-in session)."
  - So no customer response carries any user id, not even the caller's own, nor another customer's receipt or label. Other holders of a tip are only a count (`alsoReceivedBy`). The customer is always the bearer session's user (`require_bearer_subject`), never a parameter.
- **Spec §8, verbatim:**
  - "No persisted aggregate table. Every customer, admin and dashboard scorecard calls one shared function"
  - "It reads only canonical tips and their progress/outcome data. Receipts never affect counts or percentages. MERGED_DUPLICATE tips are excluded entirely. The result is deterministic and reproducible."
- **Marksy calls are read from the tip (Phase 2b review).**
  - When Marksy withdraws an entered losing prediction, the tip ends SOURCE_EXIT / FAILURE with a negative `actual_return`.
  - The prediction's `PredictionOutcomeEvent` stays INVALIDATED with no price, because Phase 0 writes it first and the first terminal event stands.
  - So every Marksy-engine surface in this plan reads the tip's `status`, `outcome` and returns. That covers `calls.marksy.engines[]`, engine scorecards, the summary's `marksy` part and every tips list. None of them reads `get_terminal_event`, `present()` or any monitor event.
  - The existing `predictions[]` block of `/instruments` is left exactly as it is, for the current app.
- **Out of scope:**
  - app screens (Phase 4b) and admin-app screens (Phase 5);
  - the §12 backfill and retiring `/tips`, `/tips/scorecard` and `/tips/{id}/marksy-view` (Phase 6);
  - any admin endpoint other than `/admin/scorecards`;
  - push delivery channels: `AlertDelivery` DELIVERED means readable through `/alerts`, as today.

## Review Focus

These are the inputs the spec implies but its happy paths never exercise. Each one has a test in the owning task:

1. **A tip that closes after `as_of`.** It must count as open, with no outcome and no return, and must not move a scorecard computed for an earlier `as_of`. Test: Task 1, `test_a_tip_closed_after_as_of_counts_as_open`.
2. **Tips first seen a minute either side of IST midnight, and one seen after `as_of`.**
   - A 23:59 IST tip is "yesterday" and a 00:01 IST tip is "today", although both are the same UTC date.
   - A tip first seen after `as_of` is in no period at all.
   - This runs through SQL on SQLite, where tzinfo is dropped.
   - Test: Task 3, `test_the_ist_day_boundary_decides_the_period`.
3. **One tip received many times.** Three customers hold it, one of them over two media.
   - It counts once in every scope.
   - The signed-in customer sees both of their own receipts and `alsoReceivedBy: 2`.
   - Nothing of the other customers appears in the response.
   - Test: Task 5, `test_my_tips_count_a_tip_once_and_never_name_another_customer`.
4. **A withdrawn losing Marksy call.** It must show as SOURCE_EXIT / FAILURE with its negative return on `/instruments`, and count as a failure (not an invalidation) in the Marksy engine's scorecard. Test: Task 6, `test_a_withdrawn_losing_marksy_call_shows_as_a_failed_source_exit`.
5. **A second sweep over the same closes.** This covers a tracker rerun, a recovery or the next morning's pass.
   - No holder is alerted twice.
   - `legacy-unknown` is never alerted.
   - A Marksy tip, which has no receipts, alerts nobody.
   - Test: Task 7, `test_each_holder_is_alerted_once_and_a_marksy_tip_alerts_nobody`.

## Resolved ambiguities (decisions this plan makes)

1. **One pure metrics function over tip facts, plus one query layer. No persisted aggregate, and no index.**
   - **Pure part.** `compute_metrics(facts, *, as_of)` takes `TipFact`s: status, outcome, `closed_at`, `closed_session`, `promised_return`, `actual_return`, and the canonical channel and caller.
     - It skips MERGED_DUPLICATE.
     - A tip is terminal only when `closed_at <= as_of`.
     - Successful / Failed / Expired come from the §6.6 `outcome` (SUCCESS / FAILURE / EXPIRED), so Completed = Successful + Failed + Expired exactly, and Exited = SOURCE_EXIT among Completed.
     - "Average days to completion" is the mean `closed_session` over Completed. The tracker's session index counts sessions from `first_seen_at`, with session 0 = 0 (2a).
   - **Wilson lower bound (§8.2):** `W = (p + z²/2n − z·√(p(1−p)/n + z²/4n²)) / (1 + z²/n)`, with `z = 1.96`, clipped at 0 because float error gives −2e-17 at p = 0. The golden fixture (Task 1) has n = 12 Completed, 7 of them successful:
     - p = 7/12 = 0.583333;
     - z² = 3.8416, so z²/2n = 0.160067 and z²/4n² = 0.006669;
     - p(1−p)/n = 0.020255;
     - √(0.026924) = 0.164086, so z·√ = 0.321608;
     - numerator = 0.583333 + 0.160067 − 0.321608 = 0.421792;
     - denominator = 1 + 3.8416/12 = 1.320133;
     - **W = 0.319507**.
   - **Return quality G:** Σ max(r, 0) ÷ Σ |r| over Completed returns.
     - The fixture's positives are 0.10 + 0.08 + 0.06 + 0.12 + 0.04 + 0.03 + 0.02 + 0.01 = 0.46.
     - Its absolute negatives are 0.05 + 0.02 + 0.04 + 0.03 = 0.14.
     - So **G = 0.46 / 0.60 = 0.766667**.
     - G is 0.5 when the denominator is 0.
   - **Trust score:** round(100 × 0.319507 × (0.5 + 0.5 × 0.766667)) = round(28.2231) = **28**.
     - The second golden value is 10 of 10 successes with G = 1: W = 1 / 1.38416 = 0.722460, so the trust score is 72.
     - With n < 10 it is null.
   - **Rounding.** All ROUND_HALF_UP:
     - percentages to 2 places;
     - returns, which are fractions, to 6 places (the column scale);
     - W and G to 6 places;
     - the trust score to an integer.
   - **`as_of` is an event-time cut.**
     - Tips first seen after `as_of` are excluded.
     - A tip closed after `as_of` is open.
     - The API accepts an optional `asOf` (default now), so any card can be recomputed.
     - Caveat: the tracker can record a close with an earlier `closed_at` (a source exit priced days later, or MISSING_BARS). A card for a past `as_of` is therefore reproducible from the ledger as it stands, not as it stood.
   - **Query cost on PostgreSQL.**
     - A scorecard, a list or a summary is two small directory reads (`channels`, `callers`) plus one `SELECT` of 9 narrow `tips` columns. Lists and summaries group those facts in Python, so there is no N+1.
     - `/me/tips` issues 7 queries per page, and `/instruments` 5, all bounded by the page or by one stock.
     - With no index on `first_seen_at` / `closed_at` / `caller_id`, PostgreSQL seq-scans `tips`. That is 43 legacy rows plus the Marksy predictions since 2b plus phone captures, well under 50k rows in year one, so about 10 ms.
     - A channel scope uses `ix_tips_channel_symbol`'s leading column. The customer subquery uses `uq_tip_receipt_user_event` (leading `user_id`).
     - An index (`first_seen_at`, `stock_id`) is worth a migration only past about 200k tips. This phase adds none.
2. **Canonical roll-up and the customer scope.**
   - `load_directory` reads every channel and caller and resolves each id's root by following `canonical_channel_id` / `canonical_caller_id` chains, with cycle protection.
   - A scope or narrowing id is replaced by its root, and SQL selects every member. So merged channels and callers roll up, and asking for a merged-away id returns its canonical card (`scopeId` echoes the root).
   - MERGED_DUPLICATE is excluded in `compute_metrics`, the one place, so the golden test covers it.
   - **Customer scope** = `tips.id IN (SELECT tip_id FROM tip_receipts WHERE user_id = :u)`:
     - `IN` counts each tip once, whatever the number of receipts, media or kinds;
     - an EXIT receipt attached to a tip counts as holding it;
     - other customers' receipts never enter.
   - **Legacy rows** (`match_key IS NULL`) are excluded everywhere until the §12 backfill.
3. **Periods.**
   - Every preset resolves to inclusive IST (Asia/Kolkata) dates relative to `as_of`'s IST date, then to a half-open UTC window from IST midnight to the IST midnight after the end date:
     - weeks start Monday;
     - "Yesterday" is the calendar day before;
     - "Last 7/30/90 days" include today;
     - "Last month" is the whole previous calendar month.
   - **"Last 7 trading days"** walks back over weekdays that are not in `market_calendar.get_holiday_dates_in_range(session, "NSE", …)` (holidays plus unexpected closures), the same rule as `count_trading_days`.
     - A date filter needs a calendar answer before a day's bars exist, and for future dates. So it cannot use the 2a tracker's candle-store sessions.
     - The two can differ only on a day the whole ingest missed.
   - **Custom** needs both `startDate` and `endDate`, with start ≤ end. It cannot be combined with a preset.
   - **Lifetime** (the default) echoes `startAt`/`endAt` as null.
   - **`basis=terminal`** filters on `closed_at` inside the window and `closed_at <= as_of`, and drops tips that are not terminal. So Open is always 0 under it. `first_seen` is the default everywhere, and no endpoint switches it implicitly.
   - **Horizon buckets** are inclusive `horizon_sessions` bounds: INTRADAY 0, UP_TO_1_WEEK 1–5, UP_TO_1_MONTH 6–20, LONGER_THAN_1_MONTH ≥ 21.
   - **Echo.** Every response echoes `period`, `startDate`, `endDate`, `basis`, `horizon`, `channelId`, `callerId`, `startAt`, `endAt` and `asOf`.
4. **`/instruments/{symbol}` gains `calls`, and nothing else changes.**
   - The app's `InstrumentLifecycleDto` (marksy-os `market/InstrumentModels.kt:67-91`) parses `symbol, companyName, exchange, sector, isActive, market, predictionCount, openPredictionCount, predictions` with org.json and ignores unknown keys. So one new top-level object is backward compatible.
   - Shape: `calls = {asOf, closedWithinDays: 90, marksy: {engines: [...]}, external: {channels: [...]}}`.
     - `engines[]` lists every canonical caller of a MARKSY channel, including an engine with no call on this stock (`tips: []`). This gives the §10 "a Marksy group per engine".
     - `channels[]` lists every canonical external channel with a call on this stock, best trust first.
     - Each group carries its entity's **Lifetime, all-horizon headline** (§8.2 "The headline trust score is Lifetime, all horizons"), not a per-stock card. A per-stock n is almost always below 10, so its trust score would always be null.
     - Each group's tips are ACTIVE, or closed within the last 90 days, excluding MERGED_DUPLICATE, newest first, as `LedgerTipView`. That view carries the canonical `caller`, and a Marksy tip's `predictionId`.
5. **State-change alerts.**
   - **Where they are made.** Not inside `_TipTracking._close` or `_apply`:
     - `_create_alert` commits, which would break 2a's per-tip rollback (F1);
     - a tip's alert must also survive a crash between the tracker's commit and the alert.
     - So `run_tip_state_alert_sweep(session, *, at)` runs in `scripts/run_tip_tracker.py` right after `track_tips` on the **official** pass only (the provisional pass never changes a tip, §6.7). It derives events from committed tip state and puts its counts in `result_summary.tipAlerts`.
   - **Events.**
     - `TIP_ENTERED`: an ACTIVE tip with `entry_basis = STATED` and `entry_status = ENTERED`, which only a WAITING → ENTERED transition produces (§6.3). A first-seen-price tip starts ENTERED, so it never alerts entry.
     - `TIP_CLOSED`: a tracker close (TARGET_HIT, STOP_LOSS_HIT, SOURCE_EXIT, HORIZON_EXPIRED, DIRECTION_HORIZON, INVALIDATED, DATA_UNRESOLVED) with `closed_at` in the last 14 days and a reason that is not an intake rejection.
     - Not alerted: intake rejections (born terminal, and the ingest response already says so) and MERGED_DUPLICATE. An admin merge moves receipts to a tip whose state did not change.
   - **Audience.** The distinct `user_id`s of the tip's receipts, excluding `legacy-unknown`.
   - **Dedupe.** It is structural: the existing unique key `(user_id, alert_type, source_table="tips", source_id=tip.id)`, checked in one batch query before creating anything. So any rerun or recovery re-sends nothing. Terminal states never reopen, so one TIP_CLOSED per tip is enough.
   - **Delivery.** `create_alert_from_tip_state` (new, in `app/recommendation_alerts.py`) goes through `_create_alert`, which honours muted alert types. Then `record_delivered` writes the DELIVERED event. Both are the existing EPIC-201 mechanism. `GET /alerts` items gain `tipId`, so the app can open the tip.
   - **Marksy tips have no receipts, so they alert nobody** (2b decision 2). Their users keep hearing through the existing recommendation alerts. The sweep counts them as `tipsWithoutHolders`.
   - **Privacy.** The message is built only from the call (symbol, direction), the canonical channel and caller names, the status and the return. For example: `RENUKA BUY call from Upstox (Rahul): target hit, +10.00%`. It never uses a receipt, a customer's channel label or any user id. Every holder gets the same text.
6. **PROVISIONAL progress rows are shown with a basis flag, on ACTIVE tips only, after the last FINAL row.**
   - The progress series is every FINAL row, then (only while the tip is ACTIVE) the PROVISIONAL rows dated after its last FINAL row. Each point carries `dataBasis`.
   - `latestProgress` is that series' last point. So a live tip shows its evening number labelled PROVISIONAL, which is what the 16:10 pass exists for.
   - PROVISIONAL rows on a terminal tip, or dated at or before a FINAL row, are orphans. The 2a coverage threshold or a close can strand them. They are dropped. This is the reader-side cleanup 2a deferred; no row is deleted.
7. **Engines are callers.**
   - Spec §7 makes "Prediction engine" and "Rating engine" callers of the Marksy channel.
   - So the `engine` scope is the caller scope, restricted to canonical callers of a MARKSY-type channel; any other id is a 404.
   - `/scorecards?entity=caller` lists engines among callers, and the Marksy channel appears among channels.
   - Narrowing "by Marksy engine" is `callerId`.
8. **`/scorecards/summary`** returns the `all` scorecard and the same facts split into `marksy` (MARKSY-type channels) and `external` (everything else). It is one read, for the dashboard card. The spec names the route but not its content.
9. **The `/me/tips` headline** is each tip's canonical channel headline, and its caller's when it has one: Lifetime, all horizons, first-seen basis, as of now. It is the same `ScorecardHeadline` the instrument groups use.
10. **`/me/tips` filter and paging.**
    - `status` is `CLOSED` (any non-ACTIVE status) or one ledger status; anything else is a 422.
    - MERGED_DUPLICATE and legacy rows never appear.
    - Newest `first_seen_at` first, with the existing offset cursor (`pageSize` ≤ 100).
11. **`/tips/{id}` is extended, not replaced.**
    - `TipView` gains `ledger` (a `LedgerTipView`, or null for a legacy row) and `progress` (the decision 6 series).
    - Every existing field, including the EPIC-803 `status` (comparison state), stays as it is. §9 lists `/tips/{id}` as a customer route, and §9 keeps `/tips/{id}/marksy-view` "until their readers move".
    - `sourceReference` also stays. For a legacy app post it is the first submitter's device idempotency key: not a customer identity, and it retires with the EPIC-803 fields in Phase 6.
    - `ledger` and `progress` carry no receipt and no user id.
12. **`/admin/scorecards`** takes `scope` = channel|caller|engine|customer|all, `scopeId` (a user id for customer, an integer otherwise) and the §8.3 filters, and returns the same `ScorecardView`.
    - The 3a/5 admin plan did not exist when this plan was written, and the endpoint needs this phase's function. So 3b builds it.
    - Task 1 greps for an existing `/admin/scorecards`. If the admin phase got there first, skip that part of Task 4.
13. **`alsoReceivedBy`** counts distinct other user ids holding any receipt for the tip, excluding the caller and `legacy-unknown`, which is a system placeholder, not a customer.

## File Structure

- Create `app/tip_scorecard.py`: `TipFact`, `ScorecardMetrics`, `compute_metrics`, `wilson_lower_bound`, `return_quality`, `aware_utc` (Task 1); the §8.3 vocabulary, `ScorecardFilter`, `Window`, `validate_filter`, `resolve_window` (Task 2).
- Create `app/tip_scorecard_query.py`: `Directory`, `load_directory`, `ScorecardQuery`, `Scorecard`, `resolve_query`, `scorecard`, `scorecards_by_entity`, `scorecard_summary`, `headlines`.
- Create `api/schemas/scorecards.py`, `api/services/scorecards.py`, `api/routers/scorecards.py`, `api/routers/admin_scorecards.py`.
- Create `api/schemas/ledger.py`, `api/services/ledger_tips.py`, `api/routers/me.py`.
- Modify `api/app.py` (register the three routers), `api/schemas/tips.py` + `api/services/tips.py` (`TipView.ledger`, `TipView.progress`), `api/schemas/instruments.py` + `api/services/instruments.py` (`calls`), `api/schemas/alerts.py` + `api/services/alerts.py` (`tipId`).
- Modify `app/recommendation_alerts.py`: the two tip alert types, their severities, `ALERT_SOURCE_TABLE_TIPS`, `create_alert_from_tip_state`.
- Create `app/tip_alerts.py`: `run_tip_state_alert_sweep`.
- Modify `scripts/run_tip_tracker.py`: run the sweep after the official pass.
- Regenerate `docs/api/openapi.json` (Tasks 4–7).
- Tests: create `tests/_ledger_factories.py`, `tests/test_tip_scorecard.py`, `tests/test_tip_scorecard_query.py`, `tests/test_api_scorecards.py`, `tests/test_api_my_tips.py`, `tests/test_api_instrument_calls.py` and `tests/test_tip_alerts.py`. Modify `tests/test_run_tip_tracker.py`.

---

### Task 1: Worktree, and the §8.1/§8.2 metrics on tip facts

**Files:**
- Create: `app/tip_scorecard.py`
- Test: `tests/test_tip_scorecard.py`

**Interfaces:**
- Produces, in `app/tip_scorecard.py`:
  - `SCORECARD_VERSION = "SCR-001"`, `WILSON_Z = 1.96`, `MIN_COMPLETED_FOR_TRUST = 10`
  - `@dataclass(frozen=True) TipFact(tip_id: int, status: str, outcome: str | None, closed_at: datetime | None, closed_session: int | None, promised_return: Decimal | None, actual_return: Decimal | None, channel_id: int | None = None, caller_id: int | None = None)`
  - `@dataclass(frozen=True) ScorecardMetrics(total, open, successful, failed, expired, completed, exited, invalidated, unscorable, data_unresolved: int; success_pct, failure_pct, hit_rate_pct, avg_actual_return, total_actual_return, avg_promised_return, total_promised_return, return_realization_pct, avg_days_to_completion: Decimal | None; trust_score: int | None; wilson_lower_bound, return_quality: Decimal | None)`
  - `aware_utc(value: datetime) -> datetime`
  - `wilson_lower_bound(successes: int, n: int, *, z: float = WILSON_Z) -> float`
  - `return_quality(returns: list[Decimal]) -> Decimal`
  - `compute_metrics(facts: Iterable[TipFact], *, as_of: datetime) -> ScorecardMetrics`

- [ ] **Step 1: Create the worktree and check what it builds on**

```bash
cd /c/AIAgent/marksy-api && git fetch origin && git log origin/main --oneline -6
git worktree add ../marksy-api-phase3b -b feat/tip-ledger-scorecards origin/main
cd /c/AIAgent/marksy-api-phase3b && python -m alembic heads && test -f app/marksy_tips.py && echo "2b present"
grep -rn "admin/scorecards" api/ || echo "no admin scorecards yet"
rm -f "$TEMP/marksy-pytest-default.db"
```

Expected:
- `origin/main` shows the Phase 2b merge (`feat/tip-ledger-marksy-predictions`).
- One alembic head (`0183_tips_prediction_unique`, or a later 2c revision). Write it down: Task 8 checks it is unchanged.
- `2b present`. **If `app/marksy_tips.py` is missing, stop: 2b has not merged.**
- `no admin scorecards yet`. If the grep prints a route instead, skip the admin parts of Task 4 (they are marked) and say so in the PR.

All later commands run in `C:\AIAgent\marksy-api-phase3b`.

- [ ] **Step 2: Write the failing test**

Create `tests/test_tip_scorecard.py`:

```python
"""Tip-ledger spec §8 on plain facts: the §8.1 metrics, the §8.2 trust score and the §8.3 windows, as golden values."""
from __future__ import annotations

from datetime import datetime, timedelta, timezone
from decimal import Decimal
from itertools import count

from app.tip_scorecard import TipFact, compute_metrics, return_quality, wilson_lower_bound
from app.tip_vocabulary import (
    OUTCOME_EXPIRED,
    OUTCOME_FAILURE,
    OUTCOME_SUCCESS,
    STATUS_ACTIVE,
    STATUS_DATA_UNRESOLVED,
    STATUS_DIRECTION_HORIZON,
    STATUS_HORIZON_EXPIRED,
    STATUS_INVALIDATED,
    STATUS_MERGED_DUPLICATE,
    STATUS_SOURCE_EXIT,
    STATUS_STOP_LOSS_HIT,
    STATUS_TARGET_HIT,
    STATUS_UNSCORABLE,
)

AS_OF = datetime(2026, 9, 29, 10, 0, tzinfo=timezone.utc)
BEFORE = AS_OF - timedelta(days=1)
AFTER = AS_OF + timedelta(hours=1)
_ids = count(1)


def fact(status, outcome=None, promised=None, actual=None, closed_session=None, *, closed_at=BEFORE):
    return TipFact(
        tip_id=next(_ids), status=status, outcome=outcome,
        closed_at=None if status == STATUS_ACTIVE else closed_at, closed_session=closed_session,
        promised_return=None if promised is None else Decimal(promised),
        actual_return=None if actual is None else Decimal(actual),
    )


# 12 completed (7 successful, 3 failed, 2 expired), 2 open (one closes after AS_OF), one each invalidated,
# unscorable and data-unresolved, and a merged duplicate that must not count at all.
GOLDEN = [
    fact(STATUS_TARGET_HIT, OUTCOME_SUCCESS, "0.10", "0.10", 3),
    fact(STATUS_TARGET_HIT, OUTCOME_SUCCESS, "0.08", "0.08", 5),
    fact(STATUS_TARGET_HIT, OUTCOME_SUCCESS, "0.05", "0.06", 1),
    fact(STATUS_TARGET_HIT, OUTCOME_SUCCESS, "0.12", "0.12", 10),
    fact(STATUS_SOURCE_EXIT, OUTCOME_SUCCESS, "0.10", "0.04", 2),
    fact(STATUS_DIRECTION_HORIZON, OUTCOME_SUCCESS, None, "0.03", 20),
    fact(STATUS_DIRECTION_HORIZON, OUTCOME_SUCCESS, None, "0.02", 20),
    fact(STATUS_STOP_LOSS_HIT, OUTCOME_FAILURE, "0.10", "-0.05", 4),
    fact(STATUS_SOURCE_EXIT, OUTCOME_FAILURE, "0.06", "-0.02", 6),
    fact(STATUS_DIRECTION_HORIZON, OUTCOME_FAILURE, None, "-0.04", 20),
    fact(STATUS_HORIZON_EXPIRED, OUTCOME_EXPIRED, "0.10", "0.01", 20),
    fact(STATUS_HORIZON_EXPIRED, OUTCOME_EXPIRED, "0.08", "-0.03", 5),
    fact(STATUS_ACTIVE),
    fact(STATUS_TARGET_HIT, OUTCOME_SUCCESS, "0.10", "0.10", 2, closed_at=AFTER),
    fact(STATUS_INVALIDATED),
    fact(STATUS_UNSCORABLE),
    fact(STATUS_DATA_UNRESOLVED),
    fact(STATUS_MERGED_DUPLICATE),
]


def test_the_golden_fixture_gives_every_section_8_1_count():
    m = compute_metrics(GOLDEN, as_of=AS_OF)

    assert (m.total, m.open, m.successful, m.failed, m.expired, m.completed, m.exited) == (17, 2, 7, 3, 2, 12, 2)
    assert (m.invalidated, m.unscorable, m.data_unresolved) == (1, 1, 1)


def test_the_golden_fixture_gives_every_section_8_1_performance_value():
    m = compute_metrics(GOLDEN, as_of=AS_OF)

    assert (m.success_pct, m.failure_pct, m.hit_rate_pct) == (Decimal("70.00"), Decimal("30.00"), Decimal("58.33"))
    assert (m.total_actual_return, m.avg_actual_return) == (Decimal("0.320000"), Decimal("0.026667"))
    assert (m.total_promised_return, m.avg_promised_return) == (Decimal("0.790000"), Decimal("0.087778"))
    # §15: like with like -- 0.31 realised over 0.79 promised on the nine tips with a target.
    assert m.return_realization_pct == Decimal("39.24")
    assert m.avg_days_to_completion == Decimal("9.67")


def test_the_golden_fixture_gives_the_section_8_2_trust_score():
    m = compute_metrics(GOLDEN, as_of=AS_OF)

    assert (m.wilson_lower_bound, m.return_quality, m.trust_score) == (Decimal("0.319507"), Decimal("0.766667"), 28)


def test_a_tip_closed_after_as_of_counts_as_open():
    later = fact(STATUS_TARGET_HIT, OUTCOME_SUCCESS, "0.10", "0.10", 2, closed_at=AFTER)

    before = compute_metrics([later], as_of=AS_OF)
    after = compute_metrics([later], as_of=AFTER)

    assert (before.open, before.successful, before.completed, before.total_actual_return) == (1, 0, 0, None)
    assert (after.open, after.successful, after.completed, after.total_actual_return) == (0, 1, 1, Decimal("0.100000"))


def test_fewer_than_ten_completed_tips_have_no_trust_score():
    m = compute_metrics(GOLDEN[:9], as_of=AS_OF)

    assert (m.completed, m.trust_score, m.wilson_lower_bound, m.return_quality) == (9, None, None, None)


def test_the_wilson_bound_and_return_quality_at_their_edges():
    ten_wins = [fact(STATUS_TARGET_HIT, OUTCOME_SUCCESS, "0.05", "0.05", 1) for _ in range(10)]

    assert round(wilson_lower_bound(10, 10), 6) == 0.72246
    assert wilson_lower_bound(0, 10) == 0.0
    assert return_quality([Decimal("0"), Decimal("0")]) == Decimal("0.5")
    assert compute_metrics(ten_wins, as_of=AS_OF).trust_score == 72


def test_an_empty_scope_has_counts_and_no_percentages():
    m = compute_metrics([], as_of=AS_OF)

    assert (m.total, m.success_pct, m.hit_rate_pct, m.avg_actual_return, m.return_realization_pct) == (0, None, None, None, None)
    assert (m.avg_days_to_completion, m.trust_score) == (None, None)
```

- [ ] **Step 3: Run it to verify it fails**

Run: `python -m pytest tests/test_tip_scorecard.py -v`
Expected: FAIL with `ModuleNotFoundError: No module named 'app.tip_scorecard'`.

- [ ] **Step 4: Write the metrics module**

Create `app/tip_scorecard.py`:

```python
"""Tip-ledger spec §8: the one scorecard every customer, admin and dashboard view reads.

Pure functions over tip facts: the §8.1 metrics, the §8.2 trust score and the §8.3 period windows. Nothing here is
persisted; `app.tip_scorecard_query` reads the facts."""

from __future__ import annotations

import math
from collections import Counter
from collections.abc import Iterable
from dataclasses import dataclass
from datetime import datetime, timezone
from decimal import ROUND_HALF_UP, Decimal

from .tip_vocabulary import (
    OUTCOME_EXPIRED,
    OUTCOME_FAILURE,
    OUTCOME_SUCCESS,
    STATUS_ACTIVE,
    STATUS_DATA_UNRESOLVED,
    STATUS_INVALIDATED,
    STATUS_MERGED_DUPLICATE,
    STATUS_SOURCE_EXIT,
    STATUS_UNSCORABLE,
)

SCORECARD_VERSION = "SCR-001"
WILSON_Z = 1.96
MIN_COMPLETED_FOR_TRUST = 10

_SCORED_OUTCOMES = (OUTCOME_SUCCESS, OUTCOME_FAILURE, OUTCOME_EXPIRED)
_CENT = Decimal("0.01")
_MICRO = Decimal("0.000001")


@dataclass(frozen=True)
class TipFact:
    """What §8 reads of one tip; `channel_id` and `caller_id` are canonical roots."""

    tip_id: int
    status: str
    outcome: str | None
    closed_at: datetime | None
    closed_session: int | None
    promised_return: Decimal | None
    actual_return: Decimal | None
    channel_id: int | None = None
    caller_id: int | None = None


@dataclass(frozen=True)
class ScorecardMetrics:
    total: int
    open: int
    successful: int
    failed: int
    expired: int
    completed: int
    exited: int
    invalidated: int
    unscorable: int
    data_unresolved: int
    success_pct: Decimal | None
    failure_pct: Decimal | None
    hit_rate_pct: Decimal | None
    avg_actual_return: Decimal | None
    total_actual_return: Decimal | None
    avg_promised_return: Decimal | None
    total_promised_return: Decimal | None
    return_realization_pct: Decimal | None
    avg_days_to_completion: Decimal | None
    trust_score: int | None
    wilson_lower_bound: Decimal | None
    return_quality: Decimal | None


def aware_utc(value: datetime) -> datetime:
    """SQLite drops tzinfo on read; every stored instant is UTC."""
    return (value if value.tzinfo is not None else value.replace(tzinfo=timezone.utc)).astimezone(timezone.utc)


def _terminal(fact: TipFact, as_of: datetime) -> bool:
    return fact.status != STATUS_ACTIVE and fact.closed_at is not None and aware_utc(fact.closed_at) <= as_of


def _pct(part: int, whole: int) -> Decimal | None:
    return None if whole == 0 else (Decimal(part) * 100 / whole).quantize(_CENT, ROUND_HALF_UP)


def _total(values: list[Decimal]) -> Decimal | None:
    return sum(values, Decimal(0)).quantize(_MICRO, ROUND_HALF_UP) if values else None


def _mean(values: list[Decimal]) -> Decimal | None:
    return (sum(values, Decimal(0)) / len(values)).quantize(_MICRO, ROUND_HALF_UP) if values else None


def wilson_lower_bound(successes: int, n: int, *, z: float = WILSON_Z) -> float:
    """§8.2: (p + z²/2n − z·√(p(1−p)/n + z²/4n²)) / (1 + z²/n), floored at 0 against float error at p = 0."""
    p = successes / n
    z2 = z * z
    margin = z * math.sqrt(p * (1 - p) / n + z2 / (4 * n * n))
    return max(0.0, (p + z2 / (2 * n) - margin) / (1 + z2 / n))


def return_quality(returns: list[Decimal]) -> Decimal:
    """§8.2's G: Σ max(r, 0) ÷ Σ |r|, and 0.5 when every return is zero."""
    gross = sum((abs(value) for value in returns), Decimal(0))
    if gross == 0:
        return Decimal("0.5")
    return sum((max(value, Decimal(0)) for value in returns), Decimal(0)) / gross


def _trust(successful: int, completed: int, returns: list[Decimal]):
    if completed < MIN_COMPLETED_FOR_TRUST:
        return None, None, None
    wilson = wilson_lower_bound(successful, completed)
    quality = return_quality(returns)
    score = Decimal(repr(100 * wilson * (0.5 + 0.5 * float(quality)))).quantize(Decimal(1), ROUND_HALF_UP)
    return int(score), Decimal(repr(wilson)).quantize(_MICRO, ROUND_HALF_UP), quality.quantize(_MICRO, ROUND_HALF_UP)


def compute_metrics(facts: Iterable[TipFact], *, as_of: datetime) -> ScorecardMetrics:
    """§8.1 and §8.2. A tip is terminal only once `closed_at <= as_of`; unscorable, invalidated and data-unresolved
    tips are counted but never enter a percentage or a return."""
    as_of = aware_utc(as_of)
    counted = [fact for fact in facts if fact.status != STATUS_MERGED_DUPLICATE]
    terminal = [fact for fact in counted if _terminal(fact, as_of)]
    statuses = Counter(fact.status for fact in terminal)
    completed = [fact for fact in terminal if fact.outcome in _SCORED_OUTCOMES]
    outcomes = Counter(fact.outcome for fact in completed)
    successful, failed = outcomes[OUTCOME_SUCCESS], outcomes[OUTCOME_FAILURE]
    actual = [fact.actual_return for fact in completed if fact.actual_return is not None]
    with_target = [fact for fact in completed if fact.promised_return is not None]
    promised = [fact.promised_return for fact in with_target]
    promised_sum = sum(promised, Decimal(0))
    realized_sum = sum((fact.actual_return for fact in with_target if fact.actual_return is not None), Decimal(0))
    sessions = [fact.closed_session for fact in completed if fact.closed_session is not None]
    trust_score, wilson, quality = _trust(successful, len(completed), actual)
    return ScorecardMetrics(
        total=len(counted),
        open=len(counted) - len(terminal),
        successful=successful,
        failed=failed,
        expired=outcomes[OUTCOME_EXPIRED],
        completed=len(completed),
        exited=sum(1 for fact in completed if fact.status == STATUS_SOURCE_EXIT),
        invalidated=statuses[STATUS_INVALIDATED],
        unscorable=statuses[STATUS_UNSCORABLE],
        data_unresolved=statuses[STATUS_DATA_UNRESOLVED],
        success_pct=_pct(successful, successful + failed),
        failure_pct=_pct(failed, successful + failed),
        hit_rate_pct=_pct(successful, len(completed)),
        avg_actual_return=_mean(actual),
        total_actual_return=_total(actual),
        avg_promised_return=_mean(promised),
        total_promised_return=_total(promised),
        return_realization_pct=(
            (realized_sum * 100 / promised_sum).quantize(_CENT, ROUND_HALF_UP) if promised_sum else None
        ),
        avg_days_to_completion=(
            (Decimal(sum(sessions)) / len(sessions)).quantize(_CENT, ROUND_HALF_UP) if sessions else None
        ),
        trust_score=trust_score,
        wilson_lower_bound=wilson,
        return_quality=quality,
    )
```

- [ ] **Step 5: Run it to verify it passes**

Run: `python -m pytest tests/test_tip_scorecard.py -v`
Expected: 7 PASS.

- [ ] **Step 6: Commit**

```bash
git add app/tip_scorecard.py tests/test_tip_scorecard.py
git commit -m "Tip scorecard: section 8.1 metrics and 8.2 trust score over tip facts, golden values

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 2: §8.3 filters — IST windows, trading days, validation

**Files:**
- Modify: `app/tip_scorecard.py`
- Test: `tests/test_tip_scorecard.py`

**Interfaces:**
- Produces, in `app/tip_scorecard.py`:
  - `PERIOD_TODAY, PERIOD_YESTERDAY, PERIOD_THIS_WEEK, PERIOD_LAST_WEEK, PERIOD_THIS_MONTH, PERIOD_LAST_MONTH, PERIOD_LAST_7_DAYS, PERIOD_LAST_7_TRADING_DAYS, PERIOD_LAST_30_DAYS, PERIOD_LAST_90_DAYS, PERIOD_LIFETIME` (values equal their names), `PRESET_PERIODS`, `PERIOD_CUSTOM = "CUSTOM"`
  - `BASIS_FIRST_SEEN = "first_seen"`, `BASIS_TERMINAL = "terminal"`, `BASES`
  - `HORIZON_INTRADAY, HORIZON_UP_TO_1_WEEK, HORIZON_UP_TO_1_MONTH, HORIZON_LONGER_THAN_1_MONTH` (values equal their names), `HORIZON_BUCKETS: dict[str, tuple[int, int | None]]`
  - `class InvalidScorecardFilter(ValueError)`
  - `@dataclass(frozen=True) ScorecardFilter(period: str | None = None, start_date: date | None = None, end_date: date | None = None, basis: str = BASIS_FIRST_SEEN, horizon: str | None = None, channel_id: int | None = None, caller_id: int | None = None)`
  - `@dataclass(frozen=True) Window(period: str, start_date: date | None, end_date: date | None, start_at: datetime | None, end_at: datetime | None)`, where `start_at` is inclusive and `end_at` exclusive, both UTC
  - `validate_filter(filters: ScorecardFilter) -> None`, which raises `InvalidScorecardFilter`
  - `ist_date(at: datetime) -> date`
  - `trading_days_start(today: date, sessions: int, holidays: frozenset[date]) -> date`
  - `resolve_window(filters: ScorecardFilter, *, as_of: datetime, holidays: frozenset[date] = frozenset()) -> Window`

- [ ] **Step 1: Write the failing tests**

In `tests/test_tip_scorecard.py`:
- Add `import pytest`.
- Change `from datetime import datetime, timedelta, timezone` to `from datetime import date, datetime, timedelta, timezone`.
- Replace the `from app.tip_scorecard import (...)` line with:

```python
from app.tip_scorecard import (
    PERIOD_CUSTOM,
    PERIOD_LAST_30_DAYS,
    PERIOD_LAST_7_DAYS,
    PERIOD_LAST_7_TRADING_DAYS,
    PERIOD_LAST_90_DAYS,
    PERIOD_LAST_MONTH,
    PERIOD_LAST_WEEK,
    PERIOD_LIFETIME,
    PERIOD_THIS_MONTH,
    PERIOD_THIS_WEEK,
    PERIOD_TODAY,
    PERIOD_YESTERDAY,
    InvalidScorecardFilter,
    ScorecardFilter,
    TipFact,
    compute_metrics,
    resolve_window,
    return_quality,
    wilson_lower_bound,
)
```

Append:

```python
TUE_1530_IST = datetime(2026, 9, 29, 10, 0, tzinfo=timezone.utc)


def utc(*parts):
    return datetime(*parts, tzinfo=timezone.utc)


@pytest.mark.parametrize("period, start, end", [
    (PERIOD_TODAY, date(2026, 9, 29), date(2026, 9, 29)),
    (PERIOD_YESTERDAY, date(2026, 9, 28), date(2026, 9, 28)),
    (PERIOD_THIS_WEEK, date(2026, 9, 28), date(2026, 9, 29)),
    (PERIOD_LAST_WEEK, date(2026, 9, 21), date(2026, 9, 27)),
    (PERIOD_THIS_MONTH, date(2026, 9, 1), date(2026, 9, 29)),
    (PERIOD_LAST_MONTH, date(2026, 8, 1), date(2026, 8, 31)),
    (PERIOD_LAST_7_DAYS, date(2026, 9, 23), date(2026, 9, 29)),
    (PERIOD_LAST_7_TRADING_DAYS, date(2026, 9, 21), date(2026, 9, 29)),
    (PERIOD_LAST_30_DAYS, date(2026, 8, 31), date(2026, 9, 29)),
    (PERIOD_LAST_90_DAYS, date(2026, 7, 2), date(2026, 9, 29)),
])
def test_every_preset_resolves_to_ist_calendar_dates(period, start, end):
    window = resolve_window(ScorecardFilter(period=period), as_of=TUE_1530_IST)

    assert (window.period, window.start_date, window.end_date) == (period, start, end)


def test_a_window_runs_from_ist_midnight_to_the_next_ist_midnight():
    window = resolve_window(ScorecardFilter(period=PERIOD_TODAY), as_of=TUE_1530_IST)

    assert (window.start_at, window.end_at) == (utc(2026, 9, 28, 18, 30), utc(2026, 9, 29, 18, 30))


def test_just_after_ist_midnight_is_already_the_next_day():
    window = resolve_window(ScorecardFilter(period=PERIOD_TODAY), as_of=utc(2026, 9, 28, 19, 0))

    assert window.start_date == date(2026, 9, 29)


def test_last_seven_trading_days_skip_weekends_and_nse_holidays():
    sunday = utc(2026, 10, 4, 6, 0)

    window = resolve_window(ScorecardFilter(period=PERIOD_LAST_7_TRADING_DAYS), as_of=sunday,
                            holidays=frozenset({date(2026, 10, 2)}))

    assert (window.start_date, window.end_date) == (date(2026, 9, 23), date(2026, 10, 4))


def test_lifetime_is_unbounded_and_a_custom_range_is_inclusive():
    lifetime = resolve_window(ScorecardFilter(), as_of=TUE_1530_IST)
    custom = resolve_window(ScorecardFilter(start_date=date(2026, 9, 1), end_date=date(2026, 9, 30)), as_of=TUE_1530_IST)

    assert (lifetime.period, lifetime.start_at, lifetime.end_at) == (PERIOD_LIFETIME, None, None)
    assert (custom.period, custom.start_at, custom.end_at) == (PERIOD_CUSTOM, utc(2026, 8, 31, 18, 30), utc(2026, 9, 30, 18, 30))


@pytest.mark.parametrize("filters", [
    ScorecardFilter(period="FORTNIGHT"),
    ScorecardFilter(period=PERIOD_TODAY, start_date=date(2026, 9, 1), end_date=date(2026, 9, 2)),
    ScorecardFilter(start_date=date(2026, 9, 1)),
    ScorecardFilter(period=PERIOD_CUSTOM),
    ScorecardFilter(start_date=date(2026, 9, 2), end_date=date(2026, 9, 1)),
    ScorecardFilter(basis="closed"),
    ScorecardFilter(horizon="WEEKLY"),
])
def test_a_filter_outside_the_contract_is_refused(filters):
    with pytest.raises(InvalidScorecardFilter):
        resolve_window(filters, as_of=TUE_1530_IST)
```

- [ ] **Step 2: Run them to verify they fail**

Run: `python -m pytest tests/test_tip_scorecard.py -v`
Expected: collection FAILS with `ImportError: cannot import name 'PERIOD_CUSTOM' from 'app.tip_scorecard'`.

- [ ] **Step 3: Add the §8.3 filter section**

In `app/tip_scorecard.py`:
- Change `from datetime import datetime, timezone` to `from datetime import date, datetime, time, timedelta, timezone`.
- Add `from .market_data.quality import NSE_TIMEZONE` directly above `from .tip_vocabulary import (`.
- Append at the end of the file:

```python
PERIOD_TODAY = "TODAY"
PERIOD_YESTERDAY = "YESTERDAY"
PERIOD_THIS_WEEK = "THIS_WEEK"
PERIOD_LAST_WEEK = "LAST_WEEK"
PERIOD_THIS_MONTH = "THIS_MONTH"
PERIOD_LAST_MONTH = "LAST_MONTH"
PERIOD_LAST_7_DAYS = "LAST_7_DAYS"
PERIOD_LAST_7_TRADING_DAYS = "LAST_7_TRADING_DAYS"
PERIOD_LAST_30_DAYS = "LAST_30_DAYS"
PERIOD_LAST_90_DAYS = "LAST_90_DAYS"
PERIOD_LIFETIME = "LIFETIME"
PRESET_PERIODS = (
    PERIOD_TODAY, PERIOD_YESTERDAY, PERIOD_THIS_WEEK, PERIOD_LAST_WEEK, PERIOD_THIS_MONTH, PERIOD_LAST_MONTH,
    PERIOD_LAST_7_DAYS, PERIOD_LAST_7_TRADING_DAYS, PERIOD_LAST_30_DAYS, PERIOD_LAST_90_DAYS, PERIOD_LIFETIME,
)
PERIOD_CUSTOM = "CUSTOM"

BASIS_FIRST_SEEN = "first_seen"
BASIS_TERMINAL = "terminal"
BASES = (BASIS_FIRST_SEEN, BASIS_TERMINAL)

HORIZON_INTRADAY = "INTRADAY"
HORIZON_UP_TO_1_WEEK = "UP_TO_1_WEEK"
HORIZON_UP_TO_1_MONTH = "UP_TO_1_MONTH"
HORIZON_LONGER_THAN_1_MONTH = "LONGER_THAN_1_MONTH"
# Inclusive `horizon_sessions` bounds; None is unbounded.
HORIZON_BUCKETS: dict[str, tuple[int, int | None]] = {
    HORIZON_INTRADAY: (0, 0),
    HORIZON_UP_TO_1_WEEK: (1, 5),
    HORIZON_UP_TO_1_MONTH: (6, 20),
    HORIZON_LONGER_THAN_1_MONTH: (21, None),
}


class InvalidScorecardFilter(ValueError):
    """A filter the §8.3 contract does not define."""


@dataclass(frozen=True)
class ScorecardFilter:
    """§8.3: a preset `period` or an inclusive custom range, the date basis, a horizon bucket, and narrowing."""

    period: str | None = None
    start_date: date | None = None
    end_date: date | None = None
    basis: str = BASIS_FIRST_SEEN
    horizon: str | None = None
    channel_id: int | None = None
    caller_id: int | None = None


@dataclass(frozen=True)
class Window:
    """IST dates and their UTC instants, `start_at` inclusive and `end_at` exclusive; None is unbounded."""

    period: str
    start_date: date | None
    end_date: date | None
    start_at: datetime | None
    end_at: datetime | None


def validate_filter(filters: ScorecardFilter) -> None:
    if filters.basis not in BASES:
        raise InvalidScorecardFilter(f"basis must be one of {', '.join(BASES)}")
    if filters.horizon is not None and filters.horizon not in HORIZON_BUCKETS:
        raise InvalidScorecardFilter(f"horizon must be one of {', '.join(HORIZON_BUCKETS)}")
    if filters.period is not None and filters.period not in (*PRESET_PERIODS, PERIOD_CUSTOM):
        raise InvalidScorecardFilter(f"period must be one of {', '.join((*PRESET_PERIODS, PERIOD_CUSTOM))}")
    dated = filters.start_date is not None or filters.end_date is not None
    if (dated or filters.period == PERIOD_CUSTOM) and (filters.start_date is None or filters.end_date is None):
        raise InvalidScorecardFilter("a custom period needs both startDate and endDate")
    if dated and filters.period not in (None, PERIOD_CUSTOM):
        raise InvalidScorecardFilter("pass a preset period or startDate and endDate, not both")
    if dated and filters.start_date > filters.end_date:
        raise InvalidScorecardFilter("startDate is after endDate")


def ist_date(at: datetime) -> date:
    return aware_utc(at).astimezone(NSE_TIMEZONE).date()


def _midnight(day: date) -> datetime:
    return datetime.combine(day, time(0), NSE_TIMEZONE).astimezone(timezone.utc)


def trading_days_start(today: date, sessions: int, holidays: frozenset[date]) -> date:
    """The earliest of the last `sessions` NSE weekdays on or before `today` that are not holidays."""
    day, seen = today, 0
    while True:
        if day.weekday() < 5 and day not in holidays:
            seen += 1
            if seen == sessions:
                return day
        day -= timedelta(days=1)


def _preset_dates(period: str, today: date, holidays: frozenset[date]) -> tuple[date, date]:
    monday = today - timedelta(days=today.weekday())
    first = today.replace(day=1)
    if period == PERIOD_TODAY:
        return today, today
    if period == PERIOD_YESTERDAY:
        return today - timedelta(days=1), today - timedelta(days=1)
    if period == PERIOD_THIS_WEEK:
        return monday, today
    if period == PERIOD_LAST_WEEK:
        return monday - timedelta(days=7), monday - timedelta(days=1)
    if period == PERIOD_THIS_MONTH:
        return first, today
    if period == PERIOD_LAST_MONTH:
        last_month_end = first - timedelta(days=1)
        return last_month_end.replace(day=1), last_month_end
    if period == PERIOD_LAST_7_TRADING_DAYS:
        return trading_days_start(today, 7, holidays), today
    days = {PERIOD_LAST_7_DAYS: 7, PERIOD_LAST_30_DAYS: 30, PERIOD_LAST_90_DAYS: 90}[period]
    return today - timedelta(days=days - 1), today


def resolve_window(filters: ScorecardFilter, *, as_of: datetime, holidays: frozenset[date] = frozenset()) -> Window:
    """§8.3 boundaries in IST (Asia/Kolkata), weeks from Monday, relative to `as_of`'s IST date."""
    validate_filter(filters)
    if filters.start_date is not None:
        return Window(PERIOD_CUSTOM, filters.start_date, filters.end_date, _midnight(filters.start_date),
                      _midnight(filters.end_date + timedelta(days=1)))
    period = filters.period or PERIOD_LIFETIME
    if period == PERIOD_LIFETIME:
        return Window(PERIOD_LIFETIME, None, None, None, None)
    start, end = _preset_dates(period, ist_date(as_of), holidays)
    return Window(period, start, end, _midnight(start), _midnight(end + timedelta(days=1)))
```

- [ ] **Step 4: Run them to verify they pass**

Run: `python -m pytest tests/test_tip_scorecard.py -v`
Expected: all PASS (7 from Task 1 + 21 here).

- [ ] **Step 5: Commit**

```bash
git add app/tip_scorecard.py tests/test_tip_scorecard.py
git commit -m "Tip scorecard: section 8.3 IST windows, NSE trading days, date basis and horizon buckets

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 3: The query layer — scopes, canonical roll-up, filters in SQL

**Files:**
- Create: `app/tip_scorecard_query.py`
- Create: `tests/_ledger_factories.py`
- Test: `tests/test_tip_scorecard_query.py`

**Interfaces:**
- Consumes: Task 1–2 names; `market_calendar.get_holiday_dates_in_range(session, exchange, start_date, end_date) -> frozenset[date]` (half-open `[start, end)`).
- Produces, in `app/tip_scorecard_query.py`:
  - `SCOPE_CHANNEL = "channel"`, `SCOPE_CALLER = "caller"`, `SCOPE_ENGINE = "engine"`, `SCOPE_CUSTOMER = "customer"`, `SCOPE_ALL = "all"`, `SCOPES`
  - `class UnknownScorecardEntity(LookupError)` with `.entity` and `.entity_id`
  - `@dataclass(frozen=True) Directory(channels: dict[int, Channel], callers: dict[int, Caller], channel_root: dict[int, int], caller_root: dict[int, int])` with `channel_members(root) -> list[int]`, `caller_members(root) -> list[int]`, `caller_channel_root(caller_root) -> int | None`, `is_marksy(channel_root) -> bool` and `engine_roots() -> list[int]`
  - `load_directory(session) -> Directory`
  - `@dataclass(frozen=True) ScorecardQuery(filters: ScorecardFilter, window: Window, as_of: datetime)`
  - `@dataclass(frozen=True) Scorecard(scope: str, scope_id: int | str | None, name: str | None, channel_id: int | None, channel_name: str | None, metrics: ScorecardMetrics)`
  - `resolve_query(session, filters, *, as_of) -> ScorecardQuery`
  - `scorecard(session, *, scope: str, scope_id: int | str | None = None, filters: ScorecardFilter = ScorecardFilter(), as_of: datetime) -> tuple[ScorecardQuery, Scorecard]`
  - `scorecards_by_entity(session, *, entity: str, filters: ScorecardFilter = ScorecardFilter(), as_of: datetime) -> tuple[ScorecardQuery, list[Scorecard]]`
  - `scorecard_summary(session, *, filters: ScorecardFilter = ScorecardFilter(), as_of: datetime) -> tuple[ScorecardQuery, dict[str, ScorecardMetrics]]` with keys `all`, `marksy`, `external`
  - `headlines(session, directory, *, channel_roots: set[int], caller_roots: set[int], as_of: datetime) -> tuple[dict[int, ScorecardMetrics], dict[int, ScorecardMetrics]]`
- Produces, in `tests/_ledger_factories.py`, the helpers Tasks 3–7 share: `AS_OF`, `SEEN`, `CLOSED`, `new_session`, `api_client`, `bearer`, `stock`, `channel`, `caller`, `marksy_channel`, `tip`, `won`, `lost`, `receipt`, `progress`.

- [ ] **Step 1: Write the shared factories**

Create `tests/_ledger_factories.py`:

```python
"""Ledger rows for the scorecard, reader and alert tests: a tip in any lifecycle state, with its receipts."""
from __future__ import annotations

import uuid
from contextlib import contextmanager
from datetime import datetime, timezone
from decimal import Decimal
from itertools import count

from fastapi.testclient import TestClient
from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker
from sqlalchemy.pool import StaticPool

from api.deps import get_db
from app.auth_session import create_session
from app.db import Base
from app.main import app
from app.models import Caller, Channel, Stock, Tip, TipDailyProgress, TipReceipt
from app.tip_vocabulary import (
    BAR_BASIS_DAILY,
    CHANNEL_BROKER_APP,
    CHANNEL_MARKSY,
    COMPARISON_STATUS_RECEIVED,
    DATA_BASIS_FINAL,
    DIRECTION_BUY,
    ENTRY_BASIS_STATED,
    ENTRY_ENTERED,
    HORIZON_BASIS_STATED,
    KIND_TIP,
    MEDIUM_APP_NOTIFICATION,
    OUTCOME_FAILURE,
    OUTCOME_SUCCESS,
    STATUS_ACTIVE,
    STATUS_STOP_LOSS_HIT,
    STATUS_TARGET_HIT,
)

AS_OF = datetime(2026, 9, 29, 10, 0, tzinfo=timezone.utc)  # Tue 15:30 IST
SEEN = datetime(2026, 9, 21, 4, 0, tzinfo=timezone.utc)  # Mon 09:30 IST
CLOSED = datetime(2026, 9, 25, 10, 0, tzinfo=timezone.utc)  # Fri 15:30 IST
_serial = count(1)


def new_session():
    engine = create_engine("sqlite:///:memory:", connect_args={"check_same_thread": False}, poolclass=StaticPool)
    Base.metadata.create_all(engine)
    return sessionmaker(bind=engine)()


@contextmanager
def api_client(session):
    def override_get_db():
        yield session

    app.dependency_overrides[get_db] = override_get_db
    try:
        yield TestClient(app)
    finally:
        app.dependency_overrides.clear()


def bearer(session, user_id: str) -> dict:
    auth = create_session(session, user_id=user_id, issued_at=datetime.now(timezone.utc))
    return {"Authorization": f"Bearer {auth.session_token}"}


def stock(session, symbol: str) -> Stock:
    row = Stock(symbol=symbol, exchange="NSE", company_name=f"{symbol} Ltd", is_active=True)
    session.add(row)
    session.flush()
    return row


def channel(session, name: str, *, type_: str = CHANNEL_BROKER_APP, merged_into: Channel | None = None) -> Channel:
    row = Channel(name=name, type=type_, default_horizon_sessions=20)
    session.add(row)
    session.flush()
    row.canonical_channel_id = merged_into.id if merged_into is not None else row.id
    session.flush()
    return row


def caller(session, channel_row: Channel, name: str, *, merged_into: Caller | None = None) -> Caller:
    row = Caller(channel_id=channel_row.id, name=name)
    session.add(row)
    session.flush()
    row.canonical_caller_id = merged_into.id if merged_into is not None else row.id
    session.flush()
    return row


def marksy_channel(session) -> tuple[Channel, Caller, Caller]:
    marksy = channel(session, "Marksy", type_=CHANNEL_MARKSY)
    return marksy, caller(session, marksy, "Prediction engine"), caller(session, marksy, "Rating engine")


def receipt(session, tip_row: Tip, user_id: str, *, medium: str = MEDIUM_APP_NOTIFICATION, label: str = "Upstox",
            kind: str = KIND_TIP) -> TipReceipt:
    row = TipReceipt(
        public_id=str(uuid.uuid4()), tip_id=tip_row.id, user_id=user_id, device_event_key=f"evt-{next(_serial)}",
        kind=kind, medium=medium, app_package="com.upstox.pro", channel_label=label, text=f"BUY {tip_row.symbol}",
        device_posted_at=None, recorded_at=tip_row.first_seen_at, parser_version="TCP-001",
    )
    session.add(row)
    session.flush()
    return row


def tip(session, channel_row: Channel, *, caller_row: Caller | None = None, stock_row: Stock | None = None,
        seen: datetime = SEEN, status: str = STATUS_ACTIVE, outcome: str | None = None, reason: str | None = None,
        closed_at: datetime | None = None, closed_session: int | None = None, horizon: int = 5,
        promised: str | None = None, actual: str | None = None, entry_basis: str = ENTRY_BASIS_STATED,
        entry_status: str | None = ENTRY_ENTERED, merged_into: Tip | None = None,
        holders: tuple[str, ...] = ("user-1",)) -> Tip:
    serial = next(_serial)
    row = Tip(
        public_id=str(uuid.uuid4()), source=channel_row.name, symbol=stock_row.symbol if stock_row else "RENUKA",
        stock_id=stock_row.id if stock_row else None, direction=DIRECTION_BUY,
        entry_low=Decimal("100"), entry_high=Decimal("100"), entry_price=Decimal("100"), entry_basis=entry_basis,
        target_price=Decimal("110"), stop_loss=Decimal("95"), horizon_sessions=horizon, horizon_days=horizon,
        horizon_basis=HORIZON_BASIS_STATED, first_seen_at=seen, received_at=seen,
        comparison_status=COMPARISON_STATUS_RECEIVED, source_channel_id=channel_row.id,
        caller_id=caller_row.id if caller_row else None, match_key=f"test-{serial:05d}", match_key_version=1,
        parser_version="TCP-001", status=status, entry_status=entry_status, outcome=outcome, reason=reason,
        entered_session=1 if entry_status == ENTRY_ENTERED else None, closed_session=closed_session,
        closed_at=closed_at, promised_return=None if promised is None else Decimal(promised),
        actual_return=None if actual is None else Decimal(actual),
        merged_into_tip_id=merged_into.id if merged_into else None,
    )
    session.add(row)
    session.flush()
    for user_id in holders:
        receipt(session, row, user_id)
    return row


def won(session, channel_row: Channel, **overrides) -> Tip:
    """TARGET_HIT in session 2, +10% promised and realised."""
    values = dict(status=STATUS_TARGET_HIT, outcome=OUTCOME_SUCCESS, closed_at=CLOSED, closed_session=2,
                  promised="0.10", actual="0.10")
    return tip(session, channel_row, **{**values, **overrides})


def lost(session, channel_row: Channel, **overrides) -> Tip:
    """STOP_LOSS_HIT in session 1, −5% against +10% promised."""
    values = dict(status=STATUS_STOP_LOSS_HIT, outcome=OUTCOME_FAILURE, closed_at=CLOSED, closed_session=1,
                  promised="0.10", actual="-0.05")
    return tip(session, channel_row, **{**values, **overrides})


def progress(session, tip_row: Tip, day, index: int, *, data_basis: str = DATA_BASIS_FINAL,
             status_after: str = STATUS_ACTIVE, ret: str = "0.01") -> TipDailyProgress:
    row = TipDailyProgress(
        tip_id=tip_row.id, session_date=day, session_index=index, entry_status=ENTRY_ENTERED,
        status_after=status_after, return_to_date=Decimal(ret), best_return=Decimal(ret), worst_return=Decimal("0"),
        to_target_pct=Decimal("5"), to_stop_pct=Decimal("4"), bar_basis=BAR_BASIS_DAILY, data_basis=data_basis,
        recorded_at=AS_OF,
    )
    session.add(row)
    session.flush()
    return row
```

- [ ] **Step 2: Write the failing tests**

Create `tests/test_tip_scorecard_query.py`:

```python
"""Tip-ledger spec §8 on SQLite: scopes, canonical roll-up, receipts that never count, and the §8.3 filters in SQL."""
from __future__ import annotations

from datetime import datetime, timedelta, timezone

import pytest

from app.tip_scorecard import (
    BASIS_TERMINAL,
    HORIZON_INTRADAY,
    HORIZON_LONGER_THAN_1_MONTH,
    HORIZON_UP_TO_1_MONTH,
    HORIZON_UP_TO_1_WEEK,
    PERIOD_LIFETIME,
    PERIOD_TODAY,
    PERIOD_YESTERDAY,
    ScorecardFilter,
)
from app.tip_scorecard_query import (
    SCOPE_ALL,
    SCOPE_CALLER,
    SCOPE_CHANNEL,
    SCOPE_CUSTOMER,
    SCOPE_ENGINE,
    UnknownScorecardEntity,
    scorecard,
    scorecard_summary,
    scorecards_by_entity,
)
from app.tip_vocabulary import MEDIUM_SMS, STATUS_MERGED_DUPLICATE
from tests._ledger_factories import (
    AS_OF,
    SEEN,
    caller,
    channel,
    lost,
    marksy_channel,
    new_session,
    receipt,
    tip,
    won,
)


@pytest.fixture
def session():
    db = new_session()
    try:
        yield db
    finally:
        db.close()


def test_a_merged_channel_rolls_up_and_its_duplicate_is_excluded(session):
    upstox = channel(session, "Upstox")
    pro = channel(session, "Upstox Pro", merged_into=upstox)
    first = won(session, upstox)
    lost(session, pro)
    tip(session, pro, status=STATUS_MERGED_DUPLICATE, closed_at=SEEN, merged_into=first)
    session.commit()

    query, card = scorecard(session, scope=SCOPE_CHANNEL, scope_id=pro.id, as_of=AS_OF)

    assert (card.scope_id, card.name, query.window.period) == (upstox.id, "Upstox", PERIOD_LIFETIME)
    assert (card.metrics.total, card.metrics.successful, card.metrics.failed) == (2, 1, 1)


def test_merged_callers_roll_up_to_the_canonical_caller(session):
    group = channel(session, "StockTips")
    rahul = caller(session, group, "Rahul")
    rahul_k = caller(session, group, "Rahul K", merged_into=rahul)
    won(session, group, caller_row=rahul)
    lost(session, group, caller_row=rahul_k)
    session.commit()

    _, card = scorecard(session, scope=SCOPE_CALLER, scope_id=rahul_k.id, as_of=AS_OF)

    assert (card.scope_id, card.name, card.channel_name, card.metrics.completed) == (rahul.id, "Rahul", "StockTips", 2)


def test_many_receipts_never_raise_a_count(session):
    upstox = channel(session, "Upstox")
    shared = won(session, upstox, holders=("user-1", "user-2", "user-3"))
    receipt(session, shared, "user-1", medium=MEDIUM_SMS, label="UPSTOX")
    lost(session, upstox, holders=("user-2",))
    session.commit()

    _, mine = scorecard(session, scope=SCOPE_CUSTOMER, scope_id="user-1", as_of=AS_OF)
    _, theirs = scorecard(session, scope=SCOPE_CUSTOMER, scope_id="user-2", as_of=AS_OF)
    _, everyone = scorecard(session, scope=SCOPE_ALL, as_of=AS_OF)

    assert (mine.metrics.total, mine.metrics.successful) == (1, 1)
    assert (theirs.metrics.total, theirs.metrics.failed) == (2, 1)
    assert everyone.metrics.total == 2


def test_the_ist_day_boundary_decides_the_period(session):
    upstox = channel(session, "Upstox")
    tip(session, upstox, seen=datetime(2026, 9, 28, 18, 29, tzinfo=timezone.utc))  # Mon 23:59 IST
    tip(session, upstox, seen=datetime(2026, 9, 28, 18, 31, tzinfo=timezone.utc))  # Tue 00:01 IST
    tip(session, upstox, seen=AS_OF + timedelta(minutes=5))
    session.commit()

    _, today = scorecard(session, scope=SCOPE_ALL, filters=ScorecardFilter(period=PERIOD_TODAY), as_of=AS_OF)
    _, yesterday = scorecard(session, scope=SCOPE_ALL, filters=ScorecardFilter(period=PERIOD_YESTERDAY), as_of=AS_OF)
    _, lifetime = scorecard(session, scope=SCOPE_ALL, as_of=AS_OF)

    assert (today.metrics.total, yesterday.metrics.total, lifetime.metrics.total) == (1, 1, 2)


def test_the_terminal_basis_filters_on_closed_at_and_drops_open_tips(session):
    upstox = channel(session, "Upstox")
    won(session, upstox, seen=datetime(2026, 9, 1, 4, 0, tzinfo=timezone.utc), closed_at=AS_OF - timedelta(hours=1))
    tip(session, upstox, seen=AS_OF - timedelta(hours=2))
    session.commit()

    _, seen_today = scorecard(session, scope=SCOPE_ALL, filters=ScorecardFilter(period=PERIOD_TODAY), as_of=AS_OF)
    _, closed_today = scorecard(session, scope=SCOPE_ALL, as_of=AS_OF,
                                filters=ScorecardFilter(period=PERIOD_TODAY, basis=BASIS_TERMINAL))

    assert (seen_today.metrics.total, seen_today.metrics.open) == (1, 1)
    assert (closed_today.metrics.total, closed_today.metrics.open, closed_today.metrics.successful) == (1, 0, 1)


def test_a_horizon_bucket_narrows_by_horizon_sessions(session):
    upstox = channel(session, "Upstox")
    for horizon in (0, 5, 6, 20, 21):
        tip(session, upstox, horizon=horizon)
    session.commit()

    totals = [
        scorecard(session, scope=SCOPE_ALL, filters=ScorecardFilter(horizon=bucket), as_of=AS_OF)[1].metrics.total
        for bucket in (HORIZON_INTRADAY, HORIZON_UP_TO_1_WEEK, HORIZON_UP_TO_1_MONTH, HORIZON_LONGER_THAN_1_MONTH)
    ]

    assert totals == [1, 1, 2, 1]


def test_an_engine_is_a_caller_of_the_marksy_channel(session):
    marksy, prediction_engine, rating_engine = marksy_channel(session)
    upstox = channel(session, "Upstox")
    rahul = caller(session, upstox, "Rahul")
    won(session, marksy, caller_row=prediction_engine, holders=())
    lost(session, marksy, caller_row=rating_engine, holders=())
    session.commit()

    _, rating = scorecard(session, scope=SCOPE_ENGINE, scope_id=rating_engine.id, as_of=AS_OF)
    _, narrowed = scorecard(session, scope=SCOPE_ALL, filters=ScorecardFilter(caller_id=prediction_engine.id), as_of=AS_OF)

    assert (rating.name, rating.channel_name, rating.metrics.failed, rating.metrics.total) == ("Rating engine", "Marksy", 1, 1)
    assert (narrowed.metrics.successful, narrowed.metrics.total) == (1, 1)
    with pytest.raises(UnknownScorecardEntity):
        scorecard(session, scope=SCOPE_ENGINE, scope_id=rahul.id, as_of=AS_OF)
    with pytest.raises(UnknownScorecardEntity):
        scorecard(session, scope=SCOPE_CHANNEL, scope_id=999, as_of=AS_OF)


def test_the_entity_list_and_the_summary_come_from_one_read_of_the_ledger(session):
    marksy, prediction_engine, _ = marksy_channel(session)
    upstox = channel(session, "Upstox")
    zerodha = channel(session, "Zerodha")
    channel(session, "Silent")
    for _ in range(10):
        won(session, upstox)
    lost(session, zerodha)
    won(session, marksy, caller_row=prediction_engine, holders=())
    session.commit()

    _, cards = scorecards_by_entity(session, entity=SCOPE_CHANNEL, as_of=AS_OF)
    _, parts = scorecard_summary(session, as_of=AS_OF)

    assert [(card.name, card.metrics.total) for card in cards] == [("Upstox", 10), ("Marksy", 1), ("Zerodha", 1)]
    assert cards[0].metrics.trust_score == 72
    assert {key: metrics.total for key, metrics in parts.items()} == {"all": 12, "marksy": 1, "external": 11}
```

- [ ] **Step 3: Run them to verify they fail**

Run: `python -m pytest tests/test_tip_scorecard_query.py -v`
Expected: collection FAILS with `ModuleNotFoundError: No module named 'app.tip_scorecard_query'`.

- [ ] **Step 4: Write the query layer**

Create `app/tip_scorecard_query.py`:

```python
"""Tip-ledger spec §8: reads a scope's canonical tips and hands their facts to `app.tip_scorecard`.

One narrow SELECT per scorecard, list or summary. Channels and callers roll up through their canonical ids (§5.2
merging); receipts only decide which tips a customer scope holds, so they never raise a count (invariant 4)."""

from __future__ import annotations

from collections import defaultdict
from dataclasses import dataclass
from datetime import datetime, timedelta

from sqlalchemy import or_, select
from sqlalchemy.orm import Session

from .market_calendar import get_holiday_dates_in_range
from .models import Caller, Channel, Tip, TipReceipt
from .tip_scorecard import (
    BASIS_TERMINAL,
    HORIZON_BUCKETS,
    PERIOD_LAST_7_TRADING_DAYS,
    InvalidScorecardFilter,
    ScorecardFilter,
    ScorecardMetrics,
    TipFact,
    Window,
    aware_utc,
    compute_metrics,
    ist_date,
    resolve_window,
)
from .tip_vocabulary import CHANNEL_MARKSY, STATUS_ACTIVE

SCOPE_CHANNEL = "channel"
SCOPE_CALLER = "caller"
SCOPE_ENGINE = "engine"
SCOPE_CUSTOMER = "customer"
SCOPE_ALL = "all"
SCOPES = (SCOPE_CHANNEL, SCOPE_CALLER, SCOPE_ENGINE, SCOPE_CUSTOMER, SCOPE_ALL)
# Calendar days that always hold 7 NSE sessions, holiday clusters included.
_TRADING_LOOKBACK_DAYS = 31


class UnknownScorecardEntity(LookupError):
    def __init__(self, entity: str, entity_id) -> None:
        super().__init__(f"{entity} {entity_id} not found")
        self.entity, self.entity_id = entity, entity_id


def _roots(parents: dict[int, int | None]) -> dict[int, int]:
    """Each id's canonical root, following merge chains; a cycle stops at its first repeat."""
    roots: dict[int, int] = {}
    for start in parents:
        node, seen = start, {start}
        while (parent := parents.get(node)) is not None and parent not in seen:
            seen.add(parent)
            node = parent
        roots[start] = node
    return roots


@dataclass(frozen=True)
class Directory:
    """Every channel and caller with its canonical root; small enough to read whole per request."""

    channels: dict[int, Channel]
    callers: dict[int, Caller]
    channel_root: dict[int, int]
    caller_root: dict[int, int]

    def channel_members(self, root: int) -> list[int]:
        return sorted(cid for cid, found in self.channel_root.items() if found == root)

    def caller_members(self, root: int) -> list[int]:
        return sorted(cid for cid, found in self.caller_root.items() if found == root)

    def caller_channel_root(self, caller_root: int) -> int | None:
        caller = self.callers.get(caller_root)
        return None if caller is None else self.channel_root.get(caller.channel_id)

    def is_marksy(self, channel_root: int | None) -> bool:
        channel = self.channels.get(channel_root) if channel_root is not None else None
        return channel is not None and channel.type == CHANNEL_MARKSY

    def engine_roots(self) -> list[int]:
        """§7: Marksy's engines are the canonical callers of a MARKSY channel."""
        return [
            cid for cid in sorted(self.callers)
            if self.caller_root[cid] == cid and self.is_marksy(self.caller_channel_root(cid))
        ]


def load_directory(session: Session) -> Directory:
    channels = {row.id: row for row in session.scalars(select(Channel))}
    callers = {row.id: row for row in session.scalars(select(Caller))}
    return Directory(
        channels=channels,
        callers=callers,
        channel_root=_roots({cid: row.canonical_channel_id for cid, row in channels.items()}),
        caller_root=_roots({cid: row.canonical_caller_id for cid, row in callers.items()}),
    )


@dataclass(frozen=True)
class ScorecardQuery:
    """The applied filter every response echoes (§8.3)."""

    filters: ScorecardFilter
    window: Window
    as_of: datetime


@dataclass(frozen=True)
class Scorecard:
    scope: str
    scope_id: int | str | None
    name: str | None
    channel_id: int | None
    channel_name: str | None
    metrics: ScorecardMetrics


def resolve_query(session: Session, filters: ScorecardFilter, *, as_of: datetime) -> ScorecardQuery:
    as_of = aware_utc(as_of)
    holidays: frozenset = frozenset()
    if filters.period == PERIOD_LAST_7_TRADING_DAYS:
        today = ist_date(as_of)
        holidays = get_holiday_dates_in_range(
            session, "NSE", today - timedelta(days=_TRADING_LOOKBACK_DAYS), today + timedelta(days=1)
        )
    return ScorecardQuery(filters, resolve_window(filters, as_of=as_of, holidays=holidays), as_of)


def _root_of(roots: dict[int, int], entity: str, entity_id) -> int:
    if entity_id not in roots:
        raise UnknownScorecardEntity(entity, entity_id)
    return roots[entity_id]


def _conditions(directory: Directory, query: ScorecardQuery) -> list:
    """Ledger tips existing at `as_of`, in the window on the chosen basis, the horizon bucket and any narrowing.
    Every bound is UTC: SQLite stores the wall clock and drops tzinfo."""
    filters, window = query.filters, query.window
    conditions = [Tip.match_key.is_not(None), Tip.first_seen_at <= query.as_of]
    basis_column = Tip.first_seen_at
    if filters.basis == BASIS_TERMINAL:
        basis_column = Tip.closed_at
        conditions += [Tip.status != STATUS_ACTIVE, Tip.closed_at.is_not(None), Tip.closed_at <= query.as_of]
    if window.start_at is not None:
        conditions += [basis_column >= window.start_at, basis_column < window.end_at]
    if filters.horizon is not None:
        low, high = HORIZON_BUCKETS[filters.horizon]
        conditions.append(Tip.horizon_sessions >= low)
        if high is not None:
            conditions.append(Tip.horizon_sessions <= high)
    if filters.channel_id is not None:
        root = _root_of(directory.channel_root, SCOPE_CHANNEL, filters.channel_id)
        conditions.append(Tip.source_channel_id.in_(directory.channel_members(root)))
    if filters.caller_id is not None:
        root = _root_of(directory.caller_root, SCOPE_CALLER, filters.caller_id)
        conditions.append(Tip.caller_id.in_(directory.caller_members(root)))
    return conditions


def _facts(session: Session, directory: Directory, conditions: list) -> list[TipFact]:
    rows = session.execute(
        select(
            Tip.id, Tip.source_channel_id, Tip.caller_id, Tip.status, Tip.outcome, Tip.closed_at,
            Tip.closed_session, Tip.promised_return, Tip.actual_return,
        ).where(*conditions)
    ).all()
    return [
        TipFact(
            tip_id=row.id, status=row.status, outcome=row.outcome, closed_at=row.closed_at,
            closed_session=row.closed_session, promised_return=row.promised_return, actual_return=row.actual_return,
            channel_id=directory.channel_root.get(row.source_channel_id),
            caller_id=directory.caller_root.get(row.caller_id),
        )
        for row in rows
    ]


def _card(directory: Directory, scope: str, root: int, metrics: ScorecardMetrics) -> Scorecard:
    if scope == SCOPE_CHANNEL:
        name = directory.channels[root].name
        return Scorecard(scope, root, name, root, name, metrics)
    channel_root = directory.caller_channel_root(root)
    channel = directory.channels.get(channel_root) if channel_root is not None else None
    return Scorecard(scope, root, directory.callers[root].name, channel_root, channel.name if channel else None, metrics)


def _scope_conditions(directory: Directory, scope: str, scope_id) -> tuple[list, int | str | None]:
    if scope == SCOPE_ALL:
        return [], None
    if scope == SCOPE_CUSTOMER:
        # IN, not a join: a tip is held once however many receipts, media or kinds the customer has for it.
        held = select(TipReceipt.tip_id).where(TipReceipt.user_id == scope_id, TipReceipt.tip_id.is_not(None))
        return [Tip.id.in_(held)], scope_id
    if scope == SCOPE_CHANNEL:
        root = _root_of(directory.channel_root, scope, scope_id)
        return [Tip.source_channel_id.in_(directory.channel_members(root))], root
    if scope in (SCOPE_CALLER, SCOPE_ENGINE):
        root = _root_of(directory.caller_root, scope, scope_id)
        if scope == SCOPE_ENGINE and root not in directory.engine_roots():
            raise UnknownScorecardEntity(scope, scope_id)
        return [Tip.caller_id.in_(directory.caller_members(root))], root
    raise InvalidScorecardFilter(f"scope must be one of {', '.join(SCOPES)}")


def scorecard(session: Session, *, scope: str, scope_id: int | str | None = None,
              filters: ScorecardFilter = ScorecardFilter(), as_of: datetime) -> tuple[ScorecardQuery, Scorecard]:
    """§8's `scorecard(scope, scope_id?, period | start_date+end_date, date_basis, horizon_bucket?, as_of)`."""
    query = resolve_query(session, filters, as_of=as_of)
    directory = load_directory(session)
    scoped, root = _scope_conditions(directory, scope, scope_id)
    metrics = compute_metrics(_facts(session, directory, [*_conditions(directory, query), *scoped]), as_of=query.as_of)
    if scope in (SCOPE_CHANNEL, SCOPE_CALLER, SCOPE_ENGINE):
        return query, _card(directory, scope, root, metrics)
    return query, Scorecard(scope, root, None, None, None, metrics)


def _rank(card: Scorecard):
    trust = card.metrics.trust_score
    return (trust is None, -(trust or 0), -card.metrics.completed, card.name or "", card.scope_id)


def scorecards_by_entity(session: Session, *, entity: str, filters: ScorecardFilter = ScorecardFilter(),
                         as_of: datetime) -> tuple[ScorecardQuery, list[Scorecard]]:
    """Every canonical channel or caller with a tip in the filter, best trust first, from one read."""
    if entity not in (SCOPE_CHANNEL, SCOPE_CALLER):
        raise InvalidScorecardFilter("entity must be channel or caller")
    query = resolve_query(session, filters, as_of=as_of)
    directory = load_directory(session)
    groups: dict[int, list[TipFact]] = defaultdict(list)
    for fact in _facts(session, directory, _conditions(directory, query)):
        key = fact.channel_id if entity == SCOPE_CHANNEL else fact.caller_id
        if key is not None:
            groups[key].append(fact)
    cards = [_card(directory, entity, root, compute_metrics(facts, as_of=query.as_of)) for root, facts in groups.items()]
    return query, sorted((card for card in cards if card.metrics.total > 0), key=_rank)


def scorecard_summary(session: Session, *, filters: ScorecardFilter = ScorecardFilter(),
                      as_of: datetime) -> tuple[ScorecardQuery, dict[str, ScorecardMetrics]]:
    """The whole ledger, and the same facts split into Marksy's calls and everyone else's."""
    query = resolve_query(session, filters, as_of=as_of)
    directory = load_directory(session)
    facts = _facts(session, directory, _conditions(directory, query))
    marksy = [fact for fact in facts if directory.is_marksy(fact.channel_id)]
    external = [fact for fact in facts if not directory.is_marksy(fact.channel_id)]
    return query, {
        "all": compute_metrics(facts, as_of=query.as_of),
        "marksy": compute_metrics(marksy, as_of=query.as_of),
        "external": compute_metrics(external, as_of=query.as_of),
    }


def headlines(session: Session, directory: Directory, *, channel_roots: set[int], caller_roots: set[int],
              as_of: datetime) -> tuple[dict[int, ScorecardMetrics], dict[int, ScorecardMetrics]]:
    """§8.2's headline -- Lifetime, every horizon -- for several channels and callers from one read."""
    channel_ids = [cid for root in sorted(channel_roots) for cid in directory.channel_members(root)]
    caller_ids = [cid for root in sorted(caller_roots) for cid in directory.caller_members(root)]
    if not channel_ids and not caller_ids:
        return {}, {}
    as_of = aware_utc(as_of)
    facts = _facts(session, directory, [
        Tip.match_key.is_not(None),
        Tip.first_seen_at <= as_of,
        or_(Tip.source_channel_id.in_(channel_ids), Tip.caller_id.in_(caller_ids)),
    ])
    by_channel: dict[int | None, list[TipFact]] = defaultdict(list)
    by_caller: dict[int | None, list[TipFact]] = defaultdict(list)
    for fact in facts:
        by_channel[fact.channel_id].append(fact)
        by_caller[fact.caller_id].append(fact)
    return (
        {root: compute_metrics(by_channel[root], as_of=as_of) for root in channel_roots},
        {root: compute_metrics(by_caller[root], as_of=as_of) for root in caller_roots},
    )
```

- [ ] **Step 5: Run them to verify they pass**

Run: `python -m pytest tests/test_tip_scorecard_query.py tests/test_tip_scorecard.py -v`
Expected: all PASS.

- [ ] **Step 6: Commit**

```bash
git add app/tip_scorecard_query.py tests/_ledger_factories.py tests/test_tip_scorecard_query.py
git commit -m "Tip scorecard: query layer with canonical roll-up, customer scope and section 8.3 filters in SQL

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 4: Scorecard APIs — `/scorecards`, `/scorecards/summary`, `/scorecards/{entity}/{id}`, `/admin/scorecards`

**Files:**
- Create: `api/schemas/scorecards.py`, `api/services/scorecards.py`, `api/routers/scorecards.py`, `api/routers/admin_scorecards.py`
- Modify: `api/app.py`, `docs/api/openapi.json` (regenerated)
- Test: `tests/test_api_scorecards.py`

**Interfaces:**
- Produces the HTTP API. Every route takes the §8.3 query parameters: `period`, `startDate`, `endDate`, `basis` (`first_seen`|`terminal`), `horizon`, `channelId`, `callerId`, plus `asOf` (default now).
  - `GET /api/v1/scorecards?entity=channel|caller` (marksy scope) → `EntityScorecardList`
  - `GET /api/v1/scorecards/summary` → `ScorecardSummaryView`
  - `GET /api/v1/scorecards/{entity}/{entityId}` (entity = channel|caller) → `ScorecardView`
  - `GET /api/v1/admin/scorecards?scope=channel|caller|engine|customer|all&scopeId=` (admin scope) → `ScorecardView`
- JSON shapes (Decimals are strings: percentages have 2 places, returns are fractions with 6):

```json
ScorecardFilterEcho: {"period": "LAST_7_DAYS", "startDate": "2026-09-23", "endDate": "2026-09-29", "basis": "first_seen",
  "horizon": null, "channelId": null, "callerId": null,
  "startAt": "2026-09-22T18:30:00Z", "endAt": "2026-09-29T18:30:00Z", "asOf": "2026-09-29T10:00:00Z"}
ScorecardCounts: {"total": 17, "open": 2, "successful": 7, "failed": 3, "expired": 2, "completed": 12, "exited": 2,
  "invalidated": 1, "unscorable": 1, "dataUnresolved": 1}
ScorecardPerformance: {"successPct": "70.00", "failurePct": "30.00", "hitRatePct": "58.33",
  "avgActualReturn": "0.026667", "totalActualReturn": "0.320000", "avgPromisedReturn": "0.087778",
  "totalPromisedReturn": "0.790000", "returnRealizationPct": "39.24", "avgDaysToCompletion": "9.67"}
ScorecardTrust: {"trustScore": 28, "completed": 12, "minimumCompleted": 10, "wilsonLowerBound": "0.319507",
  "returnQuality": "0.766667", "invalidated": 1}
ScorecardView: {"version": "SCR-001", "scope": "channel", "scopeId": 3, "name": "Upstox", "channelId": 3,
  "channelName": "Upstox", "filter": ScorecardFilterEcho, "counts": ..., "performance": ..., "trust": ...}
EntityScorecardList: {"version": "SCR-001", "entity": "channel", "filter": ScorecardFilterEcho,
  "items": [{"entity": "channel", "id": 3, "name": "Upstox", "channelId": 3, "channelName": "Upstox",
             "counts": ..., "performance": ..., "trust": ...}]}
ScorecardSummaryView: {"version": "SCR-001", "filter": ScorecardFilterEcho,
  "all": {"counts", "performance", "trust"}, "marksy": {...}, "external": {...}}
ScorecardHeadline: {"total", "open", "completed", "successful", "failed", "expired", "invalidated",
  "successPct", "hitRatePct", "avgActualReturn", "trustScore"}
```

- Produces, in `api/services/scorecards.py`:
  - `filter_echo(query) -> ScorecardFilterEcho`, `body(metrics) -> dict`, `headline(metrics) -> ScorecardHeadline`
  - `get_scorecard(session, *, scope, scope_id, filters, as_of, echo_scope_id=True) -> ScorecardView`
  - `list_scorecards(session, *, entity, filters, as_of) -> EntityScorecardList`
  - `get_summary(session, *, filters, as_of) -> ScorecardSummaryView`
  - `admin_scope_id(scope, raw) -> int | str | None`
- Produces, in `api/routers/scorecards.py`, the dependencies `scorecard_filter(...) -> ScorecardFilter` and `as_of_param(asOf) -> datetime`. The `/me` router reuses them in Task 5.

- [ ] **Step 1: Write the failing tests**

Create `tests/test_api_scorecards.py`:

```python
"""GET /api/v1/scorecards[...] and /admin/scorecards (tip-ledger spec §8.3/§9): the applied filter is echoed,
entities are ranked, and only an admin names a customer."""
from __future__ import annotations

from datetime import timedelta

import pytest

from app.models import User
from tests._auth_helpers import admin_bearer_headers
from tests._ledger_factories import AS_OF, api_client, bearer, channel, lost, marksy_channel, new_session, won

AT = {"asOf": AS_OF.isoformat()}


@pytest.fixture
def session():
    db = new_session()
    try:
        yield db
    finally:
        db.close()


@pytest.fixture
def client(session):
    with api_client(session) as test_client:
        yield test_client


def test_a_channel_scorecard_echoes_the_applied_filter(client, session):
    upstox = channel(session, "Upstox")
    won(session, upstox)
    lost(session, upstox, closed_at=AS_OF - timedelta(hours=1))
    session.commit()

    response = client.get(f"/api/v1/scorecards/channel/{upstox.id}",
                          params={"period": "LAST_7_DAYS", "basis": "terminal", **AT})
    body = response.json()["data"]

    assert body["filter"] == {
        "period": "LAST_7_DAYS", "startDate": "2026-09-23", "endDate": "2026-09-29", "basis": "terminal",
        "horizon": None, "channelId": None, "callerId": None,
        "startAt": "2026-09-22T18:30:00Z", "endAt": "2026-09-29T18:30:00Z", "asOf": "2026-09-29T10:00:00Z",
    }
    assert (body["version"], body["scope"], body["scopeId"], body["name"]) == ("SCR-001", "channel", upstox.id, "Upstox")
    assert (body["counts"]["total"], body["counts"]["successful"], body["counts"]["failed"]) == (2, 1, 1)
    assert body["performance"]["successPct"] == "50.00"
    assert body["trust"] == {"trustScore": None, "completed": 2, "minimumCompleted": 10, "wilsonLowerBound": None,
                             "returnQuality": None, "invalidated": 0}


def test_the_channel_list_ranks_by_trust_and_skips_channels_without_tips(client, session):
    upstox, zerodha = channel(session, "Upstox"), channel(session, "Zerodha")
    channel(session, "Silent")
    for _ in range(10):
        won(session, upstox)
    lost(session, zerodha)
    session.commit()

    body = client.get("/api/v1/scorecards", params={"entity": "channel", **AT}).json()["data"]

    assert [(item["name"], item["trust"]["trustScore"]) for item in body["items"]] == [("Upstox", 72), ("Zerodha", None)]
    assert (body["entity"], body["filter"]["period"], body["filter"]["startAt"]) == ("channel", "LIFETIME", None)


def test_the_summary_splits_marksy_calls_from_external_ones(client, session):
    marksy, prediction_engine, _ = marksy_channel(session)
    won(session, marksy, caller_row=prediction_engine, holders=())
    lost(session, channel(session, "Upstox"))
    session.commit()

    body = client.get("/api/v1/scorecards/summary", params=AT).json()["data"]

    assert {part: body[part]["counts"]["total"] for part in ("all", "marksy", "external")} == {
        "all": 2, "marksy": 1, "external": 1}
    assert (body["marksy"]["counts"]["successful"], body["external"]["counts"]["failed"]) == (1, 1)


def test_an_invalid_filter_is_a_422_and_an_unknown_channel_a_404(client, session):
    assert client.get("/api/v1/scorecards/summary", params={"period": "FORTNIGHT"}).status_code == 422
    assert client.get("/api/v1/scorecards/summary",
                      params={"period": "TODAY", "startDate": "2026-09-01", "endDate": "2026-09-02"}).status_code == 422
    assert client.get("/api/v1/scorecards", params={"entity": "customer"}).status_code == 422
    assert client.get("/api/v1/scorecards/channel/999").status_code == 404


def test_only_an_admin_reads_a_customer_scorecard(client, session):
    won(session, channel(session, "Upstox"), holders=("user-2",))
    session.add(User(user_id="customer-9", scopes=["marksy"], disabled=False))
    session.commit()

    admin = client.get("/api/v1/admin/scorecards", params={"scope": "customer", "scopeId": "user-2", **AT},
                       headers=admin_bearer_headers(session))
    customer = client.get("/api/v1/admin/scorecards", params={"scope": "customer", "scopeId": "user-2"},
                          headers=bearer(session, "customer-9"))

    assert admin.status_code == 200
    assert (admin.json()["data"]["scopeId"], admin.json()["data"]["counts"]["total"]) == ("user-2", 1)
    assert customer.status_code == 403
```

> If Task 1 Step 1 found an existing `/admin/scorecards`, delete `test_only_an_admin_reads_a_customer_scorecard` and skip `api/routers/admin_scorecards.py` in Steps 3–4.

- [ ] **Step 2: Run them to verify they fail**

Run: `python -m pytest tests/test_api_scorecards.py -v`
Expected: FAIL. The routes don't exist yet, so each test gets a 404 (`KeyError: 'data'` or a wrong status).

- [ ] **Step 3: Write the schemas, the service and the routers**

Create `api/schemas/scorecards.py`:

```python
"""Tip-ledger spec §8 response shapes: one scorecard body, the echoed filter (§8.3), and the compact headline."""

from __future__ import annotations

from datetime import date, datetime
from decimal import Decimal

from pydantic import BaseModel


class ScorecardFilterEcho(BaseModel):
    period: str
    startDate: date | None
    endDate: date | None
    basis: str
    horizon: str | None
    channelId: int | None
    callerId: int | None
    startAt: datetime | None
    endAt: datetime | None
    asOf: datetime


class ScorecardCounts(BaseModel):
    total: int
    open: int
    successful: int
    failed: int
    expired: int
    completed: int
    exited: int
    invalidated: int
    unscorable: int
    dataUnresolved: int


class ScorecardPerformance(BaseModel):
    successPct: Decimal | None
    failurePct: Decimal | None
    hitRatePct: Decimal | None
    avgActualReturn: Decimal | None
    totalActualReturn: Decimal | None
    avgPromisedReturn: Decimal | None
    totalPromisedReturn: Decimal | None
    returnRealizationPct: Decimal | None
    avgDaysToCompletion: Decimal | None


class ScorecardTrust(BaseModel):
    trustScore: int | None
    completed: int
    minimumCompleted: int
    wilsonLowerBound: Decimal | None
    returnQuality: Decimal | None
    invalidated: int


class ScorecardBody(BaseModel):
    counts: ScorecardCounts
    performance: ScorecardPerformance
    trust: ScorecardTrust


class ScorecardView(BaseModel):
    version: str
    scope: str
    scopeId: int | str | None
    name: str | None
    channelId: int | None
    channelName: str | None
    filter: ScorecardFilterEcho
    counts: ScorecardCounts
    performance: ScorecardPerformance
    trust: ScorecardTrust


class EntityScorecard(BaseModel):
    entity: str
    id: int
    name: str
    channelId: int | None
    channelName: str | None
    counts: ScorecardCounts
    performance: ScorecardPerformance
    trust: ScorecardTrust


class EntityScorecardList(BaseModel):
    version: str
    entity: str
    filter: ScorecardFilterEcho
    items: list[EntityScorecard]


class ScorecardSummaryView(BaseModel):
    version: str
    filter: ScorecardFilterEcho
    all: ScorecardBody
    marksy: ScorecardBody
    external: ScorecardBody


class ScorecardHeadline(BaseModel):
    """§8.2's headline: Lifetime, every horizon, first-seen basis."""

    total: int
    open: int
    completed: int
    successful: int
    failed: int
    expired: int
    invalidated: int
    successPct: Decimal | None
    hitRatePct: Decimal | None
    avgActualReturn: Decimal | None
    trustScore: int | None
```

Create `api/services/scorecards.py`:

```python
"""Tip-ledger spec §8/§9 transport over `app.tip_scorecard_query`: every number is computed there, once."""

from __future__ import annotations

from datetime import datetime

from sqlalchemy.orm import Session

from app.tip_scorecard import (
    MIN_COMPLETED_FOR_TRUST,
    SCORECARD_VERSION,
    InvalidScorecardFilter,
    ScorecardFilter,
    ScorecardMetrics,
)
from app.tip_scorecard_query import (
    SCOPE_ALL,
    SCOPE_CUSTOMER,
    ScorecardQuery,
    UnknownScorecardEntity,
    scorecard,
    scorecard_summary,
    scorecards_by_entity,
)

from ..errors import NotFoundError, ValidationError
from ..schemas.scorecards import (
    EntityScorecard,
    EntityScorecardList,
    ScorecardBody,
    ScorecardCounts,
    ScorecardFilterEcho,
    ScorecardHeadline,
    ScorecardPerformance,
    ScorecardSummaryView,
    ScorecardTrust,
    ScorecardView,
)


def filter_echo(query: ScorecardQuery) -> ScorecardFilterEcho:
    filters, window = query.filters, query.window
    return ScorecardFilterEcho(
        period=window.period, startDate=window.start_date, endDate=window.end_date, basis=filters.basis,
        horizon=filters.horizon, channelId=filters.channel_id, callerId=filters.caller_id,
        startAt=window.start_at, endAt=window.end_at, asOf=query.as_of,
    )


def body(metrics: ScorecardMetrics) -> dict:
    return {
        "counts": ScorecardCounts(
            total=metrics.total, open=metrics.open, successful=metrics.successful, failed=metrics.failed,
            expired=metrics.expired, completed=metrics.completed, exited=metrics.exited,
            invalidated=metrics.invalidated, unscorable=metrics.unscorable, dataUnresolved=metrics.data_unresolved,
        ),
        "performance": ScorecardPerformance(
            successPct=metrics.success_pct, failurePct=metrics.failure_pct, hitRatePct=metrics.hit_rate_pct,
            avgActualReturn=metrics.avg_actual_return, totalActualReturn=metrics.total_actual_return,
            avgPromisedReturn=metrics.avg_promised_return, totalPromisedReturn=metrics.total_promised_return,
            returnRealizationPct=metrics.return_realization_pct, avgDaysToCompletion=metrics.avg_days_to_completion,
        ),
        "trust": ScorecardTrust(
            trustScore=metrics.trust_score, completed=metrics.completed, minimumCompleted=MIN_COMPLETED_FOR_TRUST,
            wilsonLowerBound=metrics.wilson_lower_bound, returnQuality=metrics.return_quality,
            invalidated=metrics.invalidated,
        ),
    }


def headline(metrics: ScorecardMetrics) -> ScorecardHeadline:
    return ScorecardHeadline(
        total=metrics.total, open=metrics.open, completed=metrics.completed, successful=metrics.successful,
        failed=metrics.failed, expired=metrics.expired, invalidated=metrics.invalidated,
        successPct=metrics.success_pct, hitRatePct=metrics.hit_rate_pct, avgActualReturn=metrics.avg_actual_return,
        trustScore=metrics.trust_score,
    )


def _translated(call):
    try:
        return call()
    except InvalidScorecardFilter as exc:
        raise ValidationError(str(exc)) from exc
    except UnknownScorecardEntity as exc:
        raise NotFoundError(exc.entity.capitalize(), str(exc.entity_id)) from exc


def get_scorecard(session: Session, *, scope: str, scope_id, filters: ScorecardFilter, as_of: datetime,
                  echo_scope_id: bool = True) -> ScorecardView:
    query, card = _translated(lambda: scorecard(session, scope=scope, scope_id=scope_id, filters=filters, as_of=as_of))
    return ScorecardView(
        version=SCORECARD_VERSION, scope=card.scope, scopeId=card.scope_id if echo_scope_id else None,
        name=card.name, channelId=card.channel_id, channelName=card.channel_name, filter=filter_echo(query),
        **body(card.metrics),
    )


def list_scorecards(session: Session, *, entity: str, filters: ScorecardFilter, as_of: datetime) -> EntityScorecardList:
    query, cards = _translated(lambda: scorecards_by_entity(session, entity=entity, filters=filters, as_of=as_of))
    return EntityScorecardList(
        version=SCORECARD_VERSION, entity=entity, filter=filter_echo(query),
        items=[
            EntityScorecard(entity=card.scope, id=card.scope_id, name=card.name, channelId=card.channel_id,
                            channelName=card.channel_name, **body(card.metrics))
            for card in cards
        ],
    )


def get_summary(session: Session, *, filters: ScorecardFilter, as_of: datetime) -> ScorecardSummaryView:
    query, parts = _translated(lambda: scorecard_summary(session, filters=filters, as_of=as_of))
    return ScorecardSummaryView(
        version=SCORECARD_VERSION, filter=filter_echo(query),
        **{part: ScorecardBody(**body(metrics)) for part, metrics in parts.items()},
    )


def admin_scope_id(scope: str, raw: str | None) -> int | str | None:
    """A customer is named by user id; every other scope by its integer id."""
    if scope == SCOPE_ALL:
        return None
    if raw is None:
        raise ValidationError(f"scopeId is required for scope {scope}", field_errors={"scopeId": "required"})
    if scope == SCOPE_CUSTOMER:
        return raw
    try:
        return int(raw)
    except ValueError as exc:
        raise ValidationError("scopeId must be an integer for this scope", field_errors={"scopeId": "integer"}) from exc
```

Create `api/routers/scorecards.py`:

```python
"""GET /api/v1/scorecards[/summary|/{entity}/{entityId}] -- tip-ledger spec §8/§9: universal channel and caller
records. No customer is ever named here (invariant 10); a customer's own card is `/me/scorecard`."""

from __future__ import annotations

from datetime import date, datetime, timezone
from typing import Literal

from fastapi import APIRouter, Depends, Query
from sqlalchemy.orm import Session

from app.scopes import SCOPE_MARKSY
from app.tip_scorecard import BASIS_FIRST_SEEN, ScorecardFilter

from ..deps import get_db, require_scope
from ..envelope import success
from ..schemas.common import SuccessEnvelope
from ..schemas.scorecards import EntityScorecardList, ScorecardSummaryView, ScorecardView
from ..services.scorecards import get_scorecard, get_summary, list_scorecards

router = APIRouter(prefix="/scorecards", tags=["scorecards"], dependencies=[Depends(require_scope(SCOPE_MARKSY))])


def scorecard_filter(
    period: str | None = Query(default=None, description="TODAY|YESTERDAY|THIS_WEEK|LAST_WEEK|THIS_MONTH|LAST_MONTH|"
                               "LAST_7_DAYS|LAST_7_TRADING_DAYS|LAST_30_DAYS|LAST_90_DAYS|LIFETIME|CUSTOM"),
    startDate: date | None = Query(default=None, description="Custom range start, inclusive, IST"),
    endDate: date | None = Query(default=None, description="Custom range end, inclusive, IST"),
    basis: str = Query(default=BASIS_FIRST_SEEN, description="first_seen|terminal"),
    horizon: str | None = Query(default=None, description="INTRADAY|UP_TO_1_WEEK|UP_TO_1_MONTH|LONGER_THAN_1_MONTH"),
    channelId: int | None = Query(default=None),
    callerId: int | None = Query(default=None, description="A caller, or a Marksy engine"),
) -> ScorecardFilter:
    return ScorecardFilter(period=period, start_date=startDate, end_date=endDate, basis=basis, horizon=horizon,
                           channel_id=channelId, caller_id=callerId)


def as_of_param(asOf: datetime | None = Query(default=None, description="Calculation time; defaults to now")) -> datetime:
    if asOf is None:
        return datetime.now(timezone.utc)
    return asOf if asOf.tzinfo is not None else asOf.replace(tzinfo=timezone.utc)


@router.get("", response_model=SuccessEnvelope[EntityScorecardList])
def get_scorecards(
    entity: Literal["channel", "caller"],
    filters: ScorecardFilter = Depends(scorecard_filter),
    as_of: datetime = Depends(as_of_param),
    db: Session = Depends(get_db),
):
    return success(list_scorecards(db, entity=entity, filters=filters, as_of=as_of))


@router.get("/summary", response_model=SuccessEnvelope[ScorecardSummaryView])
def get_scorecard_summary(
    filters: ScorecardFilter = Depends(scorecard_filter),
    as_of: datetime = Depends(as_of_param),
    db: Session = Depends(get_db),
):
    """Declared before `/{entity}/{entityId}` so the literal path is never read as an entity."""
    return success(get_summary(db, filters=filters, as_of=as_of))


@router.get("/{entity}/{entityId}", response_model=SuccessEnvelope[ScorecardView])
def get_entity_scorecard(
    entity: Literal["channel", "caller"],
    entityId: int,
    filters: ScorecardFilter = Depends(scorecard_filter),
    as_of: datetime = Depends(as_of_param),
    db: Session = Depends(get_db),
):
    return success(get_scorecard(db, scope=entity, scope_id=entityId, filters=filters, as_of=as_of))
```

Create `api/routers/admin_scorecards.py` (skip if Task 1 found one):

```python
"""GET /api/v1/admin/scorecards -- tip-ledger spec §9: the §8 scorecard for any scope, a customer's included."""

from __future__ import annotations

from datetime import datetime
from typing import Literal

from fastapi import APIRouter, Depends, Query
from sqlalchemy.orm import Session

from app.scopes import SCOPE_ADMIN
from app.tip_scorecard import ScorecardFilter

from ..deps import get_db, require_scope
from ..envelope import success
from ..schemas.common import SuccessEnvelope
from ..schemas.scorecards import ScorecardView
from ..services.scorecards import admin_scope_id, get_scorecard
from .scorecards import as_of_param, scorecard_filter

router = APIRouter(prefix="/admin/scorecards", tags=["admin"], dependencies=[Depends(require_scope(SCOPE_ADMIN))])


@router.get("", response_model=SuccessEnvelope[ScorecardView])
def get_admin_scorecard(
    scope: Literal["channel", "caller", "engine", "customer", "all"] = Query(default="all"),
    scopeId: str | None = Query(default=None, description="A user id for customer, an integer id otherwise"),
    filters: ScorecardFilter = Depends(scorecard_filter),
    as_of: datetime = Depends(as_of_param),
    db: Session = Depends(get_db),
):
    return success(get_scorecard(db, scope=scope, scope_id=admin_scope_id(scope, scopeId), filters=filters, as_of=as_of))
```

In `api/app.py`:
- Directly after `from .routers import channels` (and its comment), add:

```python
# Tip-ledger spec §9: universal scorecards and the admin scorecard.
from .routers import admin_scorecards, scorecards
```

- Directly after `api_router.include_router(channels.router)`, add `api_router.include_router(scorecards.router)`.
- Directly after `api_router.include_router(admin_sessions.router)`, add `api_router.include_router(admin_scorecards.router)`.

- [ ] **Step 4: Run them to verify they pass, then regenerate the contract**

```bash
python -m pytest tests/test_api_scorecards.py -v
python scripts/export_openapi.py
python -m pytest tests/test_openapi_contract_freshness.py tests/test_security_authorization.py tests/test_epic331_authentication_coverage.py -v
```

Expected: all PASS. `docs/api/openapi.json` gains `/api/v1/scorecards`, `/api/v1/scorecards/summary`, `/api/v1/scorecards/{entity}/{entityId}` and `/api/v1/admin/scorecards`.

In the dry-run, no inventory test needed a change. If one does now (for example a count pinned by `test_the_enumeration_actually_found_the_authenticated_surface`), update it in the file's own style, after comparing with `main` (Global Constraints).

- [ ] **Step 5: Commit**

```bash
git add api/schemas/scorecards.py api/services/scorecards.py api/routers/scorecards.py api/routers/admin_scorecards.py \
  api/app.py docs/api/openapi.json tests/test_api_scorecards.py
git add -u tests
git commit -m "Tip scorecard APIs: /scorecards list, summary and entity cards, /admin/scorecards, filter echoed

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

Run `git status` before committing, and check that `git add -u tests` staged only an inventory update from Step 4, if there was one.

---

### Task 5: Customer reads — `/me/tips`, `/me/scorecard`, `/tips/{id}` with its progress series

**Files:**
- Create: `api/schemas/ledger.py`, `api/services/ledger_tips.py`, `api/routers/me.py`
- Modify: `api/schemas/tips.py` (`TipView`), `api/services/tips.py` (`get_tip`), `api/app.py`, `docs/api/openapi.json`
- Test: `tests/test_api_my_tips.py`

**Interfaces:**
- Consumes: `headline` (Task 4), `headlines` and `load_directory` (Task 3), `decode_offset_cursor` / `encode_offset_cursor`, and `require_bearer_subject`.
- Produces the HTTP API:
  - `GET /api/v1/me/tips?status=&pageSize=&cursor=` (bearer session) → `CursorEnvelope[MyTipItem]`, i.e. `{"data": [MyTipItem], "meta": {..., "pageSize", "nextCursor"}}`
  - `GET /api/v1/me/scorecard` (bearer session, §8.3 params + `asOf`) → `ScorecardView` with `scope = "customer"` and `scopeId = null`
  - `GET /api/v1/tips/{tipId}` → the existing `TipView` plus `ledger: LedgerTipView | null` and `progress: [ProgressPoint]`
- JSON shapes:

```json
ProgressPoint: {"sessionDate": "2026-09-22", "sessionIndex": 2, "entryStatus": "ENTERED", "statusAfter": "ACTIVE",
  "returnToDate": "0.020000", "bestReturn": "0.020000", "worstReturn": "0.000000", "toTargetPct": "5.000000",
  "toStopPct": "4.000000", "barBasis": "DAILY", "dataBasis": "PROVISIONAL"}
LedgerTipView: {"tipId": "uuid", "symbol": "RENUKA", "direction": "BUY", "entryLow": "100.000000",
  "entryHigh": "100.000000", "entryBasis": "STATED", "target": "110.000000", "stopLoss": "95.000000",
  "horizonSessions": 5, "horizonBasis": "STATED", "firstSeenAt": "2026-09-21T04:00:00Z", "status": "TARGET_HIT",
  "entryStatus": "ENTERED", "outcome": "SUCCESS", "reason": null, "enteredSession": 1, "closedSession": 2,
  "closedAt": "2026-09-25T10:00:00Z", "exitPrice": null, "promisedReturn": "0.100000", "actualReturn": "0.100000",
  "predictionId": null, "channel": {"channelId": 3, "name": "Upstox", "type": "BROKER_APP"},
  "caller": {"callerId": 9, "name": "Rahul"}, "latestProgress": ProgressPoint | null}
ReceiptRef: {"receiptId": "uuid", "kind": "TIP", "medium": "SMS", "channelLabel": "UPSTOX",
  "appPackage": "com.upstox.pro", "devicePostedAt": null, "recordedAt": "2026-09-21T04:00:00Z"}
MyTipItem: LedgerTipView + {"channelHeadline": ScorecardHeadline | null, "callerHeadline": ScorecardHeadline | null,
  "receivedVia": [ReceiptRef], "alsoReceivedBy": 2}
```

- Produces, in `api/services/ledger_tips.py`:
  - `STATUS_FILTER_CLOSED = "CLOSED"`, `LISTABLE_STATUSES`, `INSTRUMENT_CLOSED_WITHIN_DAYS = 90`
  - `visible_progress(rows, *, status) -> list[TipDailyProgress]`
  - `ledger_view(directory, tip, rows) -> LedgerTipView`
  - `tip_ledger_detail(session, tip) -> tuple[LedgerTipView | None, list[ProgressPoint]]`
  - `@dataclass MyTipPage(items: list[MyTipItem], next_cursor: str | None)`
  - `list_my_tips(session, *, user_id, status, page_size, cursor, now) -> MyTipPage`

- [ ] **Step 1: Write the failing tests**

Create `tests/test_api_my_tips.py`:

```python
"""GET /api/v1/me/tips, /me/scorecard and /tips/{id} (tip-ledger spec §9): a customer's tips, each counted once,
with nobody else's identity, and progress whose provisional rows are labelled or dropped (decision 6)."""
from __future__ import annotations

from datetime import date, timedelta
from decimal import Decimal

import pytest
from sqlalchemy import select

from app.models import TipReceipt
from app.tip_vocabulary import DATA_BASIS_PROVISIONAL, MEDIUM_SMS, STATUS_TARGET_HIT
from tests._ledger_factories import SEEN, api_client, bearer, caller, channel, new_session, progress, receipt, tip, won

MON, TUE = date(2026, 9, 21), date(2026, 9, 22)


@pytest.fixture
def session():
    db = new_session()
    try:
        yield db
    finally:
        db.close()


@pytest.fixture
def client(session):
    with api_client(session) as test_client:
        yield test_client


def test_my_tips_count_a_tip_once_and_never_name_another_customer(client, session):
    upstox = channel(session, "Upstox")
    shared = tip(session, upstox, holders=("user-1", "user-2", "user-3"))
    mine = receipt(session, shared, "user-1", medium=MEDIUM_SMS, label="UPSTOX")
    other = session.scalars(select(TipReceipt).where(TipReceipt.user_id == "user-2")).one()
    session.commit()
    headers = bearer(session, "user-1")

    response = client.get("/api/v1/me/tips", headers=headers)
    items = response.json()["data"]
    card = client.get("/api/v1/me/scorecard", headers=headers).json()["data"]

    assert [(item["tipId"], item["alsoReceivedBy"]) for item in items] == [(shared.public_id, 2)]
    assert sorted(via["medium"] for via in items[0]["receivedVia"]) == ["APP_NOTIFICATION", "SMS"]
    assert mine.public_id in response.text
    assert "user-2" not in response.text and "user-3" not in response.text and other.public_id not in response.text
    assert (card["scope"], card["scopeId"], card["counts"]["total"]) == ("customer", None, 1)


def test_my_tips_filter_by_status_and_page(client, session):
    upstox = channel(session, "Upstox")
    tip(session, upstox)
    won(session, upstox, seen=SEEN + timedelta(hours=1))
    won(session, upstox, seen=SEEN + timedelta(hours=2))
    tip(session, upstox, holders=("user-2",))
    session.commit()
    headers = bearer(session, "user-1")

    closed = client.get("/api/v1/me/tips", params={"status": "CLOSED", "pageSize": 1}, headers=headers).json()
    active = client.get("/api/v1/me/tips", params={"status": "ACTIVE"}, headers=headers).json()["data"]

    assert [item["status"] for item in closed["data"]] == ["TARGET_HIT"] and closed["meta"]["nextCursor"] is not None
    assert [item["status"] for item in active] == ["ACTIVE"]
    assert client.get("/api/v1/me/tips", params={"status": "OPENISH"}, headers=headers).status_code == 422
    assert client.get("/api/v1/me/tips").status_code == 401


def test_a_tip_carries_its_channel_and_caller_headline(client, session):
    upstox = channel(session, "Upstox")
    rahul = caller(session, upstox, "Rahul")
    for _ in range(10):
        won(session, upstox, caller_row=rahul, holders=("user-2",))
    tip(session, upstox, caller_row=rahul)
    session.commit()

    [item] = client.get("/api/v1/me/tips", headers=bearer(session, "user-1")).json()["data"]

    assert (item["channel"]["name"], item["caller"]["name"]) == ("Upstox", "Rahul")
    assert (item["channelHeadline"]["trustScore"], item["channelHeadline"]["total"]) == (72, 11)
    assert (item["callerHeadline"]["completed"], item["alsoReceivedBy"]) == (10, 0)


def test_a_tip_detail_carries_its_ledger_terms_and_state(client, session):
    upstox = channel(session, "Upstox")
    rahul = caller(session, upstox, "Rahul")
    closed = won(session, upstox, caller_row=rahul)
    session.commit()

    ledger = client.get(f"/api/v1/tips/{closed.public_id}").json()["data"]["ledger"]

    assert (ledger["status"], ledger["outcome"], ledger["horizonSessions"], ledger["closedSession"]) == (
        "TARGET_HIT", "SUCCESS", 5, 2)
    assert [Decimal(ledger[key]) for key in ("entryLow", "target", "stopLoss", "promisedReturn", "actualReturn")] == [
        Decimal("100"), Decimal("110"), Decimal("95"), Decimal("0.10"), Decimal("0.10")]
    assert ledger["channel"] == {"channelId": upstox.id, "name": "Upstox", "type": "BROKER_APP"}
    assert ledger["caller"] == {"callerId": rahul.id, "name": "Rahul"}


def test_a_provisional_row_shows_only_on_an_active_tip_after_its_last_final_row(client, session):
    upstox = channel(session, "Upstox")
    live = tip(session, upstox)
    progress(session, live, MON, 1)
    progress(session, live, TUE, 2, data_basis=DATA_BASIS_PROVISIONAL, ret="0.02")
    closed = won(session, upstox, closed_session=1)
    progress(session, closed, MON, 1, status_after=STATUS_TARGET_HIT, ret="0.10")
    progress(session, closed, TUE, 2, data_basis=DATA_BASIS_PROVISIONAL)
    session.commit()

    live_body = client.get(f"/api/v1/tips/{live.public_id}").json()["data"]
    closed_body = client.get(f"/api/v1/tips/{closed.public_id}").json()["data"]

    assert [(p["sessionDate"], p["dataBasis"]) for p in live_body["progress"]] == [
        ("2026-09-21", "FINAL"), ("2026-09-22", "PROVISIONAL")]
    assert Decimal(live_body["ledger"]["latestProgress"]["returnToDate"]) == Decimal("0.02")
    assert [(p["sessionDate"], p["dataBasis"]) for p in closed_body["progress"]] == [("2026-09-21", "FINAL")]
    assert closed_body["ledger"]["latestProgress"]["statusAfter"] == "TARGET_HIT"
```

- [ ] **Step 2: Run them to verify they fail**

Run: `python -m pytest tests/test_api_my_tips.py -v`
Expected: FAIL. `/me/tips` and `/me/scorecard` return 404, and `/tips/{id}` has no `ledger` key (`KeyError: 'ledger'`).

- [ ] **Step 3: Write the schemas**

Create `api/schemas/ledger.py`:

```python
"""Tip-ledger spec §9 read shapes: a ledger tip with its progress, the customer's own receipts, and an instrument's
calls. No shape here carries a user id; other holders of a tip are only a count (invariant 10)."""

from __future__ import annotations

from datetime import date, datetime
from decimal import Decimal

from pydantic import BaseModel

from .scorecards import ScorecardHeadline


class ChannelRef(BaseModel):
    channelId: int
    name: str
    type: str


class CallerRef(BaseModel):
    callerId: int
    name: str


class ProgressPoint(BaseModel):
    sessionDate: date
    sessionIndex: int
    entryStatus: str
    statusAfter: str
    returnToDate: Decimal | None
    bestReturn: Decimal | None
    worstReturn: Decimal | None
    toTargetPct: Decimal | None
    toStopPct: Decimal | None
    barBasis: str
    dataBasis: str


class LedgerTipView(BaseModel):
    """The canonical call (spec §4): terms, lifecycle and latest progress; `channel` and `caller` are canonical."""

    tipId: str
    symbol: str
    direction: str | None
    entryLow: Decimal | None
    entryHigh: Decimal | None
    entryBasis: str | None
    target: Decimal | None
    stopLoss: Decimal | None
    horizonSessions: int | None
    horizonBasis: str | None
    firstSeenAt: datetime
    status: str
    entryStatus: str | None
    outcome: str | None
    reason: str | None
    enteredSession: int | None
    closedSession: int | None
    closedAt: datetime | None
    exitPrice: Decimal | None
    promisedReturn: Decimal | None
    actualReturn: Decimal | None
    predictionId: int | None
    channel: ChannelRef | None
    caller: CallerRef | None
    latestProgress: ProgressPoint | None


class ReceiptRef(BaseModel):
    """One of the signed-in customer's own receipts for the tip."""

    receiptId: str
    kind: str
    medium: str
    channelLabel: str | None
    appPackage: str | None
    devicePostedAt: datetime | None
    recordedAt: datetime


class MyTipItem(LedgerTipView):
    channelHeadline: ScorecardHeadline | None
    callerHeadline: ScorecardHeadline | None
    receivedVia: list[ReceiptRef]
    alsoReceivedBy: int


class EngineCalls(BaseModel):
    callerId: int
    name: str
    channelId: int
    scorecard: ScorecardHeadline
    tips: list[LedgerTipView]


class ChannelCalls(BaseModel):
    channelId: int
    name: str
    type: str
    scorecard: ScorecardHeadline
    tips: list[LedgerTipView]


class MarksyCalls(BaseModel):
    engines: list[EngineCalls]


class ExternalCalls(BaseModel):
    channels: list[ChannelCalls]


class InstrumentCalls(BaseModel):
    asOf: datetime
    closedWithinDays: int
    marksy: MarksyCalls
    external: ExternalCalls
```

In `api/schemas/tips.py`:
- Add `from .ledger import LedgerTipView, ProgressPoint` after `from .score_metrics import METRIC_PREDICTION_OPPORTUNITY`.
- Append two fields to `class TipView`, after `headToHead: HeadToHeadView | None = None`:

```python
    # tip-ledger spec §9: the canonical call and its progress series; null and empty for a legacy row.
    ledger: LedgerTipView | None = None
    progress: list[ProgressPoint] = Field(default_factory=list)
```

- [ ] **Step 4: Write the service, the router, and the `/tips/{id}` wiring**

Create `api/services/ledger_tips.py`:

```python
"""Tip-ledger spec §9 customer reads: My tips, a tip's progress series, and an instrument's calls.

Receipts only decide which tips a customer holds; the only receipt fields served are the caller's own, and other
holders are a count (invariants 4 and 10). Marksy's calls are read from the tip -- status, outcome, returns -- never
from the prediction's monitor event, which stays INVALIDATED for a withdrawn call."""

from __future__ import annotations

from collections import defaultdict
from dataclasses import dataclass
from datetime import datetime, timedelta

from sqlalchemy import func, or_, select
from sqlalchemy.orm import Session

from app.models import Stock, Tip, TipDailyProgress, TipReceipt
from app.tip_scorecard import aware_utc
from app.tip_scorecard_query import Directory, headlines, load_directory
from app.tip_vocabulary import (
    DATA_BASIS_FINAL,
    DATA_BASIS_PROVISIONAL,
    LEGACY_UNKNOWN_USER_ID,
    STATUS_ACTIVE,
    STATUS_DATA_UNRESOLVED,
    STATUS_DIRECTION_HORIZON,
    STATUS_HORIZON_EXPIRED,
    STATUS_INVALIDATED,
    STATUS_MERGED_DUPLICATE,
    STATUS_SOURCE_EXIT,
    STATUS_STOP_LOSS_HIT,
    STATUS_TARGET_HIT,
    STATUS_UNSCORABLE,
)

from ..errors import ValidationError
from ..pagination import decode_offset_cursor, encode_offset_cursor
from ..schemas.ledger import (
    CallerRef,
    ChannelCalls,
    ChannelRef,
    EngineCalls,
    ExternalCalls,
    InstrumentCalls,
    LedgerTipView,
    MarksyCalls,
    MyTipItem,
    ProgressPoint,
    ReceiptRef,
)
from .scorecards import headline

STATUS_FILTER_CLOSED = "CLOSED"
LISTABLE_STATUSES = (
    STATUS_ACTIVE, STATUS_TARGET_HIT, STATUS_STOP_LOSS_HIT, STATUS_SOURCE_EXIT, STATUS_HORIZON_EXPIRED,
    STATUS_DIRECTION_HORIZON, STATUS_INVALIDATED, STATUS_UNSCORABLE, STATUS_DATA_UNRESOLVED,
)
INSTRUMENT_CLOSED_WITHIN_DAYS = 90


@dataclass
class MyTipPage:
    items: list[MyTipItem]
    next_cursor: str | None


def _optional(value: datetime | None) -> datetime | None:
    return None if value is None else aware_utc(value)


def visible_progress(rows: list[TipDailyProgress], *, status: str | None) -> list[TipDailyProgress]:
    """Decision 6: every FINAL row, then an ACTIVE tip's PROVISIONAL rows dated after its last FINAL row."""
    final = sorted((row for row in rows if row.data_basis == DATA_BASIS_FINAL), key=lambda row: row.session_date)
    last = final[-1].session_date if final else None
    evening = sorted(
        (row for row in rows if row.data_basis == DATA_BASIS_PROVISIONAL and status == STATUS_ACTIVE
         and (last is None or row.session_date > last)),
        key=lambda row: row.session_date,
    )
    return final + evening


def _point(row: TipDailyProgress) -> ProgressPoint:
    return ProgressPoint(
        sessionDate=row.session_date, sessionIndex=row.session_index, entryStatus=row.entry_status,
        statusAfter=row.status_after, returnToDate=row.return_to_date, bestReturn=row.best_return,
        worstReturn=row.worst_return, toTargetPct=row.to_target_pct, toStopPct=row.to_stop_pct,
        barBasis=row.bar_basis, dataBasis=row.data_basis,
    )


def _progress_rows(session: Session, tip_ids: list[int]) -> dict[int, list[TipDailyProgress]]:
    grouped: dict[int, list[TipDailyProgress]] = defaultdict(list)
    if tip_ids:
        for row in session.scalars(select(TipDailyProgress).where(TipDailyProgress.tip_id.in_(tip_ids))):
            grouped[row.tip_id].append(row)
    return grouped


def _channel_ref(directory: Directory, channel_id: int | None) -> ChannelRef | None:
    channel = directory.channels.get(directory.channel_root.get(channel_id))
    return None if channel is None else ChannelRef(channelId=channel.id, name=channel.name, type=channel.type)


def _caller_ref(directory: Directory, caller_id: int | None) -> CallerRef | None:
    caller = directory.callers.get(directory.caller_root.get(caller_id))
    return None if caller is None else CallerRef(callerId=caller.id, name=caller.name)


def ledger_view(directory: Directory, tip: Tip, rows: list[TipDailyProgress]) -> LedgerTipView:
    visible = visible_progress(rows, status=tip.status)
    return LedgerTipView(
        tipId=tip.public_id, symbol=tip.symbol, direction=tip.direction, entryLow=tip.entry_low,
        entryHigh=tip.entry_high, entryBasis=tip.entry_basis, target=tip.target_price, stopLoss=tip.stop_loss,
        horizonSessions=tip.horizon_sessions, horizonBasis=tip.horizon_basis,
        firstSeenAt=aware_utc(tip.first_seen_at), status=tip.status, entryStatus=tip.entry_status,
        outcome=tip.outcome, reason=tip.reason, enteredSession=tip.entered_session,
        closedSession=tip.closed_session, closedAt=_optional(tip.closed_at), exitPrice=tip.exit_price,
        promisedReturn=tip.promised_return, actualReturn=tip.actual_return, predictionId=tip.prediction_id,
        channel=_channel_ref(directory, tip.source_channel_id), caller=_caller_ref(directory, tip.caller_id),
        latestProgress=_point(visible[-1]) if visible else None,
    )


def tip_ledger_detail(session: Session, tip: Tip) -> tuple[LedgerTipView | None, list[ProgressPoint]]:
    """`/tips/{id}`'s ledger block and progress series; a legacy row (no `match_key`) has neither."""
    if tip.match_key is None:
        return None, []
    rows = _progress_rows(session, [tip.id])[tip.id]
    visible = visible_progress(rows, status=tip.status)
    return ledger_view(load_directory(session), tip, rows), [_point(row) for row in visible]


def _status_condition(status: str | None):
    if status is None:
        return None
    if status == STATUS_FILTER_CLOSED:
        return Tip.status != STATUS_ACTIVE
    if status in LISTABLE_STATUSES:
        return Tip.status == status
    raise ValidationError(
        f"status must be {STATUS_FILTER_CLOSED} or one of {', '.join(LISTABLE_STATUSES)}",
        field_errors={"status": "unknown"},
    )


def _receipt_ref(receipt: TipReceipt) -> ReceiptRef:
    return ReceiptRef(
        receiptId=receipt.public_id, kind=receipt.kind, medium=receipt.medium, channelLabel=receipt.channel_label,
        appPackage=receipt.app_package, devicePostedAt=_optional(receipt.device_posted_at),
        recordedAt=aware_utc(receipt.recorded_at),
    )


def list_my_tips(session: Session, *, user_id: str, status: str | None, page_size: int, cursor: str | None,
                 now: datetime) -> MyTipPage:
    """Newest first. Each tip once, however many receipts the customer holds for it (invariant 4)."""
    held = select(TipReceipt.tip_id).where(TipReceipt.user_id == user_id, TipReceipt.tip_id.is_not(None))
    statement = select(Tip).where(Tip.id.in_(held), Tip.match_key.is_not(None), Tip.status != STATUS_MERGED_DUPLICATE)
    condition = _status_condition(status)
    if condition is not None:
        statement = statement.where(condition)
    offset = decode_offset_cursor(cursor) if cursor else 0
    tips = session.scalars(
        statement.order_by(Tip.first_seen_at.desc(), Tip.id.desc()).offset(offset).limit(page_size + 1)
    ).all()
    has_more = len(tips) > page_size
    tips = tips[:page_size]
    ids = [tip.id for tip in tips]
    directory = load_directory(session)
    rows = _progress_rows(session, ids)
    own: dict[int, list[TipReceipt]] = defaultdict(list)
    others: dict[int, int] = {}
    if ids:
        for receipt in session.scalars(
            select(TipReceipt).where(TipReceipt.tip_id.in_(ids), TipReceipt.user_id == user_id)
            .order_by(TipReceipt.recorded_at, TipReceipt.id)
        ):
            own[receipt.tip_id].append(receipt)
        others = dict(session.execute(
            select(TipReceipt.tip_id, func.count(func.distinct(TipReceipt.user_id)))
            .where(TipReceipt.tip_id.in_(ids), TipReceipt.user_id.not_in([user_id, LEGACY_UNKNOWN_USER_ID]))
            .group_by(TipReceipt.tip_id)
        ).tuples().all())
    channel_roots = {directory.channel_root[t.source_channel_id] for t in tips if t.source_channel_id in directory.channel_root}
    caller_roots = {directory.caller_root[t.caller_id] for t in tips if t.caller_id in directory.caller_root}
    by_channel, by_caller = headlines(session, directory, channel_roots=channel_roots, caller_roots=caller_roots,
                                      as_of=now)
    items = []
    for tip in tips:
        channel_root = directory.channel_root.get(tip.source_channel_id)
        caller_root = directory.caller_root.get(tip.caller_id)
        items.append(MyTipItem(
            **ledger_view(directory, tip, rows[tip.id]).model_dump(),
            channelHeadline=headline(by_channel[channel_root]) if channel_root in by_channel else None,
            callerHeadline=headline(by_caller[caller_root]) if caller_root in by_caller else None,
            receivedVia=[_receipt_ref(receipt) for receipt in own[tip.id]],
            alsoReceivedBy=others.get(tip.id, 0),
        ))
    return MyTipPage(items=items, next_cursor=encode_offset_cursor(offset + page_size) if has_more else None)
```

> `Stock`, `or_`, `timedelta` and the `*Calls` imports are used by Task 6's `instrument_calls`. If your linter flags them as unused now, add them in Task 6 instead.

Create `api/routers/me.py`:

```python
"""GET /api/v1/me/tips and /me/scorecard -- tip-ledger spec §9: the tips the signed-in customer received.

The customer is the bearer session's user, never a parameter (invariant 11)."""

from __future__ import annotations

from datetime import datetime, timezone

from fastapi import APIRouter, Depends, Query
from sqlalchemy.orm import Session

from app.tip_scorecard import ScorecardFilter
from app.tip_scorecard_query import SCOPE_CUSTOMER

from ..deps import get_db, require_bearer_subject
from ..envelope import cursor_paginated, success
from ..pagination import DEFAULT_PAGE_SIZE, MAX_PAGE_SIZE
from ..schemas.common import CursorEnvelope, SuccessEnvelope
from ..schemas.ledger import MyTipItem
from ..schemas.scorecards import ScorecardView
from ..services.ledger_tips import list_my_tips
from ..services.scorecards import get_scorecard
from .scorecards import as_of_param, scorecard_filter

router = APIRouter(prefix="/me", tags=["me"])


@router.get("/tips", response_model=CursorEnvelope[MyTipItem])
def get_my_tips(
    status: str | None = Query(default=None, description="CLOSED, or one status: ACTIVE|TARGET_HIT|STOP_LOSS_HIT|"
                               "SOURCE_EXIT|HORIZON_EXPIRED|DIRECTION_HORIZON|INVALIDATED|UNSCORABLE|DATA_UNRESOLVED"),
    pageSize: int = Query(default=DEFAULT_PAGE_SIZE, ge=1, le=MAX_PAGE_SIZE),
    cursor: str | None = Query(default=None),
    db: Session = Depends(get_db),
    user_id: str = Depends(require_bearer_subject),
):
    page = list_my_tips(db, user_id=user_id, status=status, page_size=pageSize, cursor=cursor,
                        now=datetime.now(timezone.utc))
    return cursor_paginated(page.items, page_size=pageSize, next_cursor=page.next_cursor)


@router.get("/scorecard", response_model=SuccessEnvelope[ScorecardView])
def get_my_scorecard(
    filters: ScorecardFilter = Depends(scorecard_filter),
    as_of: datetime = Depends(as_of_param),
    db: Session = Depends(get_db),
    user_id: str = Depends(require_bearer_subject),
):
    return success(get_scorecard(db, scope=SCOPE_CUSTOMER, scope_id=user_id, filters=filters, as_of=as_of,
                                 echo_scope_id=False))
```

In `api/services/tips.py`:
- Add `from .ledger_tips import tip_ledger_detail` after `from .recommendation_detail import evidence_from_detail, get_detail`.
- In `get_tip`, add `ledger, progress = tip_ledger_detail(session, tip)` after the `outcome = ...` line, and pass `ledger=ledger, progress=progress,` as the last two `TipView(...)` arguments, after `headToHead=...`.

In `api/app.py`:
- Change the Task 4 import to `from .routers import admin_scorecards, me, scorecards`.
- Add `api_router.include_router(me.router)` directly after `api_router.include_router(scorecards.router)`.

- [ ] **Step 5: Run them to verify they pass, then regenerate the contract**

```bash
python -m pytest tests/test_api_my_tips.py tests/test_api_tips.py tests/test_api_tips_ledger.py -v
python scripts/export_openapi.py
python -m pytest tests/test_openapi_contract_freshness.py tests/test_security_authorization.py tests/test_epic331_authentication_coverage.py -v
```

Expected: all PASS. The existing `/tips` tests still pass because `ledger` and `progress` are additive.

- [ ] **Step 6: Commit**

```bash
git add api/schemas/ledger.py api/services/ledger_tips.py api/routers/me.py api/schemas/tips.py api/services/tips.py \
  api/app.py docs/api/openapi.json tests/test_api_my_tips.py
git add -u tests
git commit -m "Tip ledger reads: /me/tips and /me/scorecard, /tips/{id} ledger and progress series

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

Run `git status` first. `git add -u tests` may stage only a count update from Step 5, if there was one.

---

### Task 6: `/instruments/{symbol}` gains `calls.marksy.engines[]` and `calls.external.channels[]`

**Files:**
- Modify: `api/services/ledger_tips.py` (`instrument_calls`), `api/schemas/instruments.py`, `api/services/instruments.py`, `docs/api/openapi.json`
- Test: `tests/test_api_instrument_calls.py`

**Interfaces:**
- Produces: `instrument_calls(session, stock: Stock, *, now: datetime) -> InstrumentCalls` in `api/services/ledger_tips.py`.
- Produces: `InstrumentLifecycle.calls: InstrumentCalls`. Every existing field is unchanged.
- JSON shape (added at the top level of `GET /api/v1/instruments/{symbol}` data):

```json
"calls": {
  "asOf": "2026-09-30T10:00:00Z", "closedWithinDays": 90,
  "marksy": {"engines": [
    {"callerId": 1, "name": "Prediction engine", "channelId": 7, "scorecard": ScorecardHeadline, "tips": [LedgerTipView]},
    {"callerId": 2, "name": "Rating engine", "channelId": 7, "scorecard": ScorecardHeadline, "tips": []}]},
  "external": {"channels": [
    {"channelId": 3, "name": "Upstox", "type": "BROKER_APP", "scorecard": ScorecardHeadline,
     "tips": [LedgerTipView (caller set when known)]}]}
}
```

- [ ] **Step 1: Write the failing tests**

Create `tests/test_api_instrument_calls.py`:

```python
"""GET /api/v1/instruments/{symbol} `calls` (tip-ledger spec §9): Marksy engines and external channels, each with its
record, read from the tip itself -- a withdrawn losing Marksy call is a failed source exit, never a neutral one."""
from __future__ import annotations

from datetime import datetime, timedelta, timezone
from decimal import Decimal

import pytest

from app.tip_vocabulary import OUTCOME_FAILURE, STATUS_MERGED_DUPLICATE, STATUS_SOURCE_EXIT
from tests._ledger_factories import api_client, caller, channel, marksy_channel, new_session, stock, tip, won


@pytest.fixture
def session():
    db = new_session()
    try:
        yield db
    finally:
        db.close()


@pytest.fixture
def client(session):
    with api_client(session) as test_client:
        yield test_client


def test_an_instrument_groups_calls_by_marksy_engine_and_external_channel(client, session):
    renuka, idea = stock(session, "RENUKA"), stock(session, "IDEA")
    marksy, prediction_engine, _ = marksy_channel(session)
    upstox = channel(session, "Upstox")
    rahul = caller(session, upstox, "Rahul")
    now = datetime.now(timezone.utc)
    active = tip(session, upstox, caller_row=rahul, stock_row=renuka, seen=now - timedelta(days=1))
    recent = won(session, upstox, stock_row=renuka, seen=now - timedelta(days=20), closed_at=now - timedelta(days=10))
    won(session, upstox, stock_row=renuka, seen=now - timedelta(days=120), closed_at=now - timedelta(days=100))
    tip(session, upstox, stock_row=idea, seen=now - timedelta(days=2))
    engine_call = tip(session, marksy, caller_row=prediction_engine, stock_row=renuka, seen=now - timedelta(days=3),
                      holders=())
    tip(session, marksy, caller_row=prediction_engine, stock_row=renuka, seen=now - timedelta(days=3),
        status=STATUS_MERGED_DUPLICATE, closed_at=now - timedelta(days=3), merged_into=engine_call, holders=())
    session.commit()

    body = client.get("/api/v1/instruments/RENUKA").json()["data"]
    calls = body["calls"]

    assert [(e["name"], [t["tipId"] for t in e["tips"]]) for e in calls["marksy"]["engines"]] == [
        ("Prediction engine", [engine_call.public_id]), ("Rating engine", [])]
    [external] = calls["external"]["channels"]
    assert (external["name"], [t["tipId"] for t in external["tips"]]) == ("Upstox", [active.public_id, recent.public_id])
    assert external["tips"][0]["caller"] == {"callerId": rahul.id, "name": "Rahul"}
    assert (external["scorecard"]["total"], external["scorecard"]["completed"]) == (4, 2)
    assert (calls["closedWithinDays"], body["symbol"], body["predictionCount"], body["predictions"]) == (90, "RENUKA", 0, [])


def test_a_withdrawn_losing_marksy_call_shows_as_a_failed_source_exit(client, session):
    renuka = stock(session, "RENUKA")
    marksy, prediction_engine, _ = marksy_channel(session)
    tip(session, marksy, caller_row=prediction_engine, stock_row=renuka, status=STATUS_SOURCE_EXIT,
        outcome=OUTCOME_FAILURE, closed_at=datetime.now(timezone.utc) - timedelta(days=1), closed_session=2,
        promised="0.05", actual="-0.03", holders=())
    session.commit()

    [engine, _] = client.get("/api/v1/instruments/RENUKA").json()["data"]["calls"]["marksy"]["engines"]
    card = client.get(f"/api/v1/scorecards/caller/{prediction_engine.id}").json()["data"]

    [call] = engine["tips"]
    assert (call["status"], call["outcome"], Decimal(call["actualReturn"])) == ("SOURCE_EXIT", "FAILURE", Decimal("-0.03"))
    assert (engine["scorecard"]["failed"], engine["scorecard"]["successful"], engine["scorecard"]["invalidated"]) == (1, 0, 0)
    assert (card["counts"]["failed"], card["counts"]["exited"], card["counts"]["invalidated"]) == (1, 1, 0)
```

- [ ] **Step 2: Run them to verify they fail**

Run: `python -m pytest tests/test_api_instrument_calls.py -v`
Expected: FAIL with `KeyError: 'calls'`.

- [ ] **Step 3: Write `instrument_calls` and wire it in**

Append to `api/services/ledger_tips.py`:

```python
def instrument_calls(session: Session, stock: Stock, *, now: datetime) -> InstrumentCalls:
    """§9: every Marksy engine, and every external channel with a call on this stock -- ACTIVE, or closed within the
    last 90 days -- each with its Lifetime headline. An engine with no call here is listed with no tips."""
    now = aware_utc(now)
    directory = load_directory(session)
    tips = session.scalars(
        select(Tip).where(
            Tip.stock_id == stock.id,
            Tip.match_key.is_not(None),
            Tip.status != STATUS_MERGED_DUPLICATE,
            or_(Tip.status == STATUS_ACTIVE, Tip.closed_at >= now - timedelta(days=INSTRUMENT_CLOSED_WITHIN_DAYS)),
        ).order_by(Tip.first_seen_at.desc(), Tip.id.desc())
    ).all()
    rows = _progress_rows(session, [tip.id for tip in tips])
    by_engine: dict[int | None, list[LedgerTipView]] = defaultdict(list)
    by_channel: dict[int, list[LedgerTipView]] = defaultdict(list)
    for tip in tips:
        channel_root = directory.channel_root.get(tip.source_channel_id)
        view = ledger_view(directory, tip, rows[tip.id])
        if directory.is_marksy(channel_root):
            by_engine[directory.caller_root.get(tip.caller_id)].append(view)
        elif channel_root is not None:
            by_channel[channel_root].append(view)
    engine_roots = directory.engine_roots()
    channel_heads, engine_heads = headlines(session, directory, channel_roots=set(by_channel),
                                            caller_roots=set(engine_roots), as_of=now)
    engines = [
        EngineCalls(callerId=root, name=directory.callers[root].name, channelId=directory.caller_channel_root(root),
                    scorecard=headline(engine_heads[root]), tips=by_engine[root])
        for root in engine_roots
    ]
    channels = sorted(
        (
            ChannelCalls(channelId=root, name=directory.channels[root].name, type=directory.channels[root].type,
                         scorecard=headline(channel_heads[root]), tips=views)
            for root, views in by_channel.items()
        ),
        key=lambda calls: (calls.scorecard.trustScore is None, -(calls.scorecard.trustScore or 0), calls.name),
    )
    return InstrumentCalls(asOf=now, closedWithinDays=INSTRUMENT_CLOSED_WITHIN_DAYS,
                           marksy=MarksyCalls(engines=engines), external=ExternalCalls(channels=channels))
```

In `api/schemas/instruments.py`:
- Add `from .ledger import InstrumentCalls` after `from pydantic import BaseModel, Field`.
- Append to `class InstrumentLifecycle`, after `predictions: list[InstrumentPredictionEntry]`:

```python
    # tip-ledger spec §9; added beside `predictions`, which the current app reads and which is unchanged.
    calls: InstrumentCalls
```

In `api/services/instruments.py`:
- Add `from .ledger_tips import instrument_calls` after the `from ..schemas.instruments import (...)` block.
- Add `calls=instrument_calls(session, stock, now=now),` as the last argument of the returned `InstrumentLifecycle(...)`, after `predictions=entries,`.

- [ ] **Step 4: Run them to verify they pass, then regenerate the contract**

```bash
python -m pytest tests/test_api_instrument_calls.py tests/test_api_prediction_lifecycle.py tests/test_api_global_search.py -v
python scripts/export_openapi.py
python -m pytest tests/test_openapi_contract_freshness.py -v
```

Expected: all PASS. `tests/test_api_prediction_lifecycle.py` (the existing instrument tests) is unchanged and green.

- [ ] **Step 5: Commit**

```bash
git add api/services/ledger_tips.py api/schemas/instruments.py api/services/instruments.py docs/api/openapi.json \
  tests/test_api_instrument_calls.py
git commit -m "Instrument calls: Marksy engines and external channels with their record, read from the tip

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 7: State-change alerts to receipt holders

**Files:**
- Modify: `app/recommendation_alerts.py`, `scripts/run_tip_tracker.py`, `api/schemas/alerts.py`, `api/services/alerts.py`, `docs/api/openapi.json`
- Create: `app/tip_alerts.py`
- Test: `tests/test_tip_alerts.py`, `tests/test_run_tip_tracker.py`

**Interfaces:**
- Produces, in `app/recommendation_alerts.py`:
  - `ALERT_TYPE_TIP_ENTERED = "TIP_ENTERED"`, `ALERT_TYPE_TIP_CLOSED = "TIP_CLOSED"` (severity MEDIUM for both), `TIP_ALERT_TYPES`, `ALERT_SOURCE_TABLE_TIPS = "tips"`
  - `create_alert_from_tip_state(session, *, user_id: str, tip_id: int, alert_type: str, message: str, triggered_at: datetime) -> RecommendationAlert | None`
- Produces, in `app/tip_alerts.py`:
  - `TIP_ALERT_SWEEP_VERSION = "TAS-001"`, `TIP_ALERT_LOOKBACK = timedelta(days=14)`, `ALERTABLE_CLOSES`
  - `@dataclass TipAlertSweep(tips_considered, alerts_created, alerts_already_present, suppressed, deliveries_recorded, tips_without_holders: int)` with `summary() -> dict` (camelCase keys plus `version`)
  - `tip_events(tip) -> list[str]`, `tip_alert_message(directory, tip, alert_type) -> str`
  - `run_tip_state_alert_sweep(session, *, at: datetime) -> TipAlertSweep`
- Produces: `TIP_TRACKING` official-pass `result_summary.tipAlerts = {"version", "tipsConsidered", "alertsCreated", "alertsAlreadyPresent", "suppressed", "deliveriesRecorded", "tipsWithoutHolders"}`. The provisional pass has no `tipAlerts`.
- Produces: `GET /api/v1/alerts` items gain `tipId: str | null` (the tip's public id for a tip alert).

- [ ] **Step 1: Write the failing tests**

Create `tests/test_tip_alerts.py`:

```python
"""Tip-ledger spec §9: receipt holders hear when a tip is entered or closes -- once each, never about anyone else."""
from __future__ import annotations

from datetime import timedelta

import pytest
from sqlalchemy import select

from app.models import AlertDelivery, RecommendationAlert
from app.recommendation_alerts import ALERT_TYPE_TIP_CLOSED, ALERT_TYPE_TIP_ENTERED, set_alert_preference
from app.tip_alerts import run_tip_state_alert_sweep
from app.tip_vocabulary import (
    ENTRY_BASIS_FIRST_SEEN_PRICE,
    ENTRY_WAITING,
    LEGACY_UNKNOWN_USER_ID,
    OUTCOME_FAILURE,
    REASON_NO_DIRECTION,
    REASON_UNRESOLVED_SYMBOL,
    STATUS_DATA_UNRESOLVED,
    STATUS_MERGED_DUPLICATE,
    STATUS_SOURCE_EXIT,
    STATUS_UNSCORABLE,
)
from tests._ledger_factories import AS_OF, api_client, bearer, caller, channel, marksy_channel, new_session, tip, won

RUN = AS_OF
JUST_CLOSED = RUN - timedelta(hours=1)


@pytest.fixture
def session():
    db = new_session()
    try:
        yield db
    finally:
        db.close()


def _alerts(session):
    return session.scalars(select(RecommendationAlert).order_by(RecommendationAlert.id)).all()


def test_each_holder_is_alerted_once_and_a_marksy_tip_alerts_nobody(session):
    upstox = channel(session, "Upstox")
    marksy, prediction_engine, _ = marksy_channel(session)
    closed = won(session, upstox, closed_at=JUST_CLOSED, holders=("user-1", "user-2", LEGACY_UNKNOWN_USER_ID))
    won(session, marksy, caller_row=prediction_engine, closed_at=JUST_CLOSED, holders=())
    session.commit()

    first = run_tip_state_alert_sweep(session, at=RUN)
    again = run_tip_state_alert_sweep(session, at=RUN + timedelta(days=1))

    alerts = _alerts(session)
    assert [(a.user_id, a.alert_type, a.source_table, a.source_id) for a in alerts] == [
        ("user-1", ALERT_TYPE_TIP_CLOSED, "tips", closed.id), ("user-2", ALERT_TYPE_TIP_CLOSED, "tips", closed.id)]
    assert alerts[0].message == alerts[1].message and "user-" not in alerts[0].message
    assert len(session.scalars(select(AlertDelivery)).all()) == 2
    assert first.summary() == {
        "version": "TAS-001", "tipsConsidered": 2, "alertsCreated": 2, "alertsAlreadyPresent": 0, "suppressed": 0,
        "deliveriesRecorded": 2, "tipsWithoutHolders": 1,
    }
    assert (again.alerts_created, again.alerts_already_present) == (0, 2)


def test_the_message_names_the_call_and_its_result(session):
    upstox = channel(session, "Upstox")
    rahul = caller(session, upstox, "Rahul")
    won(session, upstox, caller_row=rahul, closed_at=JUST_CLOSED)
    tip(session, upstox, status=STATUS_SOURCE_EXIT, outcome=OUTCOME_FAILURE, closed_at=JUST_CLOSED, closed_session=1,
        promised="0.10", actual="-0.025")
    session.commit()

    run_tip_state_alert_sweep(session, at=RUN)

    assert [a.message for a in _alerts(session)] == [
        "RENUKA BUY call from Upstox (Rahul): target hit, +10.00%",
        "RENUKA BUY call from Upstox: closed by its source, -2.50%",
    ]


def test_only_a_reached_stated_entry_alerts_entry(session):
    upstox = channel(session, "Upstox")
    entered = tip(session, upstox)
    tip(session, upstox, entry_basis=ENTRY_BASIS_FIRST_SEEN_PRICE)
    tip(session, upstox, entry_status=ENTRY_WAITING)
    session.commit()

    run_tip_state_alert_sweep(session, at=RUN)

    assert [(a.alert_type, a.source_id, a.message) for a in _alerts(session)] == [
        (ALERT_TYPE_TIP_ENTERED, entered.id, "RENUKA BUY call from Upstox: entry reached")]


def test_intake_rejections_merged_duplicates_and_old_closes_alert_nobody(session):
    upstox = channel(session, "Upstox")
    old = won(session, upstox, closed_at=RUN - timedelta(days=20))
    tip(session, upstox, status=STATUS_UNSCORABLE, reason=REASON_NO_DIRECTION, closed_at=JUST_CLOSED, entry_status=None)
    tip(session, upstox, status=STATUS_DATA_UNRESOLVED, reason=REASON_UNRESOLVED_SYMBOL, closed_at=JUST_CLOSED,
        entry_status=None)
    tip(session, upstox, status=STATUS_MERGED_DUPLICATE, closed_at=JUST_CLOSED, merged_into=old)
    session.commit()

    sweep = run_tip_state_alert_sweep(session, at=RUN)

    assert (_alerts(session), sweep.tips_considered) == ([], 0)


def test_a_muted_tip_alert_type_is_suppressed(session):
    won(session, channel(session, "Upstox"), closed_at=JUST_CLOSED)
    session.commit()
    set_alert_preference(session, user_id="user-1", muted_alert_types=[ALERT_TYPE_TIP_CLOSED], effective_at=RUN)

    sweep = run_tip_state_alert_sweep(session, at=RUN)

    assert (_alerts(session), sweep.suppressed) == ([], 1)


def test_the_alert_list_links_a_tip_alert_to_its_tip(session):
    closed = won(session, channel(session, "Upstox"), closed_at=JUST_CLOSED)
    session.commit()
    run_tip_state_alert_sweep(session, at=RUN)

    with api_client(session) as client:
        [alert] = client.get("/api/v1/alerts", headers=bearer(session, "user-1")).json()["data"]["alerts"]

    assert (alert["alertType"], alert["tipId"], alert["recommendationId"]) == (ALERT_TYPE_TIP_CLOSED, closed.public_id, None)
    assert alert["deliveredAt"] is not None
```

In `tests/test_run_tip_tracker.py`, append these lines to the end of `test_each_basis_runs_once_per_ist_day`:

```python
    assert first["tipAlerts"]["alertsCreated"] == 0
    assert "tipAlerts" not in evening
```

- [ ] **Step 2: Run them to verify they fail**

Run: `python -m pytest tests/test_tip_alerts.py tests/test_run_tip_tracker.py -v`
Expected: collection of `test_tip_alerts.py` FAILS with `ImportError: cannot import name 'ALERT_TYPE_TIP_CLOSED'`. The tracker test FAILS with `KeyError: 'tipAlerts'`.

- [ ] **Step 3: Add the tip alert type and its factory**

In `app/recommendation_alerts.py`:
- Directly after `ALERT_TYPE_RECOMMENDATION_CHANGED = "RECOMMENDATION_CHANGED"  # EPIC-236`, add:

```python
ALERT_TYPE_TIP_ENTERED = "TIP_ENTERED"  # tip-ledger spec §9
ALERT_TYPE_TIP_CLOSED = "TIP_CLOSED"
TIP_ALERT_TYPES = (ALERT_TYPE_TIP_ENTERED, ALERT_TYPE_TIP_CLOSED)
ALERT_SOURCE_TABLE_TIPS = "tips"
```

- In `_SEVERITY_BY_ALERT_TYPE`, after the `ALERT_TYPE_RECOMMENDATION_CHANGED: SEVERITY_MEDIUM,` entry, add:

```python
    ALERT_TYPE_TIP_ENTERED: SEVERITY_MEDIUM,
    ALERT_TYPE_TIP_CLOSED: SEVERITY_MEDIUM,
```

- Directly after `def create_alert_from_regime_change(...)` (before the EPIC-196 comment block), add:

```python
def create_alert_from_tip_state(
    session: Session, *, user_id: str, tip_id: int, alert_type: str, message: str, triggered_at: datetime
) -> RecommendationAlert | None:
    """Tip-ledger spec §9: a receipt holder hears when the tip they received changes state; one per user and type."""
    if alert_type not in TIP_ALERT_TYPES:
        raise ValueError(f"{alert_type!r} is not a tip alert type")
    return _create_alert(
        session, user_id=user_id, alert_type=alert_type, prediction_id=None, recommendation_generation_id=None,
        source_table=ALERT_SOURCE_TABLE_TIPS, source_id=tip_id, message=message[:512], triggered_at=triggered_at,
    )
```

- [ ] **Step 4: Write the sweep**

Create `app/tip_alerts.py`:

```python
"""Tip-ledger spec §9: every receipt holder hears when the tip they received is entered or closes.

A sweep over committed tip state, run after each official tracking pass. `_create_alert` commits, so it cannot run
inside the tracker's per-tip transaction; deriving events from the tip means a rerun or a recovery re-sends nothing
(one alert per user, tip and type)."""

from __future__ import annotations

from collections import defaultdict
from dataclasses import dataclass
from datetime import datetime, timedelta

from sqlalchemy import and_, or_, select
from sqlalchemy.orm import Session

from .alert_delivery import record_delivered
from .models import RecommendationAlert, Tip, TipReceipt
from .recommendation_alerts import (
    ALERT_SOURCE_TABLE_TIPS,
    ALERT_TYPE_TIP_CLOSED,
    ALERT_TYPE_TIP_ENTERED,
    create_alert_from_tip_state,
)
from .tip_scorecard import aware_utc
from .tip_scorecard_query import Directory, load_directory
from .tip_vocabulary import (
    ENTRY_BASIS_STATED,
    ENTRY_ENTERED,
    INTAKE_REJECTION_REASONS,
    LEGACY_UNKNOWN_USER_ID,
    STATUS_ACTIVE,
    STATUS_DATA_UNRESOLVED,
    STATUS_DIRECTION_HORIZON,
    STATUS_HORIZON_EXPIRED,
    STATUS_INVALIDATED,
    STATUS_SOURCE_EXIT,
    STATUS_STOP_LOSS_HIT,
    STATUS_TARGET_HIT,
)

TIP_ALERT_SWEEP_VERSION = "TAS-001"
# A source exit can close up to 5 sessions after its message's `closed_at`; 14 days covers that with holidays.
TIP_ALERT_LOOKBACK = timedelta(days=14)
ALERTABLE_CLOSES = (
    STATUS_TARGET_HIT, STATUS_STOP_LOSS_HIT, STATUS_SOURCE_EXIT, STATUS_HORIZON_EXPIRED, STATUS_DIRECTION_HORIZON,
    STATUS_INVALIDATED, STATUS_DATA_UNRESOLVED,
)
_CLOSE_PHRASES = {
    STATUS_TARGET_HIT: "target hit",
    STATUS_STOP_LOSS_HIT: "stop-loss hit",
    STATUS_SOURCE_EXIT: "closed by its source",
    STATUS_HORIZON_EXPIRED: "horizon ended",
    STATUS_DIRECTION_HORIZON: "horizon ended",
    STATUS_INVALIDATED: "invalidated",
    STATUS_DATA_UNRESOLVED: "could not be tracked",
}


@dataclass
class TipAlertSweep:
    tips_considered: int = 0
    alerts_created: int = 0
    alerts_already_present: int = 0
    suppressed: int = 0
    deliveries_recorded: int = 0
    tips_without_holders: int = 0

    def summary(self) -> dict:
        return {
            "version": TIP_ALERT_SWEEP_VERSION,
            "tipsConsidered": self.tips_considered,
            "alertsCreated": self.alerts_created,
            "alertsAlreadyPresent": self.alerts_already_present,
            "suppressed": self.suppressed,
            "deliveriesRecorded": self.deliveries_recorded,
            "tipsWithoutHolders": self.tips_without_holders,
        }


def tip_events(tip: Tip) -> list[str]:
    """A stated entry reached, and a tracker close; never an intake rejection or a merged duplicate."""
    events = []
    if tip.status == STATUS_ACTIVE and tip.entry_basis == ENTRY_BASIS_STATED and tip.entry_status == ENTRY_ENTERED:
        events.append(ALERT_TYPE_TIP_ENTERED)
    if tip.status in ALERTABLE_CLOSES and tip.reason not in INTAKE_REJECTION_REASONS:
        events.append(ALERT_TYPE_TIP_CLOSED)
    return events


def tip_alert_message(directory: Directory, tip: Tip, alert_type: str) -> str:
    """Only the call and its public source -- never a receipt, a customer's label or a user (invariant 10)."""
    channel = directory.channels.get(directory.channel_root.get(tip.source_channel_id))
    caller = directory.callers.get(directory.caller_root.get(tip.caller_id))
    source = channel.name if channel is not None else tip.source
    if caller is not None:
        source = f"{source} ({caller.name})"
    head = f"{tip.symbol} {tip.direction} call from {source}"
    if alert_type == ALERT_TYPE_TIP_ENTERED:
        return f"{head}: entry reached"
    text = f"{head}: {_CLOSE_PHRASES[tip.status]}"
    if tip.actual_return is not None:
        text += f", {tip.actual_return * 100:+.2f}%"
    return text


def run_tip_state_alert_sweep(session: Session, *, at: datetime) -> TipAlertSweep:
    sweep = TipAlertSweep()
    tips = session.scalars(
        select(Tip).where(
            Tip.match_key.is_not(None),
            or_(
                and_(Tip.status == STATUS_ACTIVE, Tip.entry_basis == ENTRY_BASIS_STATED,
                     Tip.entry_status == ENTRY_ENTERED),
                and_(Tip.status.in_(ALERTABLE_CLOSES), Tip.closed_at >= aware_utc(at) - TIP_ALERT_LOOKBACK),
            ),
        ).order_by(Tip.id)
    ).all()
    subjects = [(tip, events) for tip in tips if (events := tip_events(tip))]
    if not subjects:
        return sweep
    ids = [tip.id for tip, _ in subjects]
    holders: dict[int, set[str]] = defaultdict(set)
    for tip_id, user_id in session.execute(
        select(TipReceipt.tip_id, TipReceipt.user_id)
        .where(TipReceipt.tip_id.in_(ids), TipReceipt.user_id != LEGACY_UNKNOWN_USER_ID)
    ).tuples():
        holders[tip_id].add(user_id)
    sent = set(session.execute(
        select(RecommendationAlert.user_id, RecommendationAlert.alert_type, RecommendationAlert.source_id)
        .where(RecommendationAlert.source_table == ALERT_SOURCE_TABLE_TIPS, RecommendationAlert.source_id.in_(ids))
    ).tuples())
    directory = load_directory(session)
    for tip, events in subjects:
        tip_id = tip.id
        sweep.tips_considered += 1
        if not holders[tip_id]:
            sweep.tips_without_holders += 1
            continue
        for alert_type in events:
            message = tip_alert_message(directory, tip, alert_type)
            for user_id in sorted(holders[tip_id]):
                if (user_id, alert_type, tip_id) in sent:
                    sweep.alerts_already_present += 1
                    continue
                alert = create_alert_from_tip_state(session, user_id=user_id, tip_id=tip_id, alert_type=alert_type,
                                                    message=message, triggered_at=at)
                if alert is None:
                    sweep.suppressed += 1
                    continue
                sweep.alerts_created += 1
                record_delivered(session, alert=alert, occurred_at=at)
                sweep.deliveries_recorded += 1
    session.commit()
    return sweep
```

- [ ] **Step 5: Run the sweep after the official pass, and link alerts to tips**

In `scripts/run_tip_tracker.py`:
- Add `from app.tip_alerts import run_tip_state_alert_sweep` after `from app.tip_minute_bars import UpstoxMinuteBars`.
- Inside the `try:` block, directly after `run = track_tips(...)`, add:

```python
            # Only the official pass changes a tip (spec §6.7), so only it can owe an alert.
            alerts = None if args.provisional else run_tip_state_alert_sweep(session, at=now)
```

- Directly after `summary = run.summary()`, add:

```python
        if alerts is not None:
            summary["tipAlerts"] = alerts.summary()
```

In `api/schemas/alerts.py`, append this field to `class AlertView`, after `unread: bool`:

```python
    # tip-ledger spec §9: the tip a TIP_ENTERED/TIP_CLOSED alert is about; null for every other type.
    tipId: str | None = None
```

In `api/services/alerts.py`:
- Add `from sqlalchemy import select` and `from app.models import Tip`.
- Add `ALERT_SOURCE_TABLE_TIPS` to the `from app.recommendation_alerts import (...)` block.
- In `list_alerts`, directly after `states = delivery_state(...)`, add:

```python
    tip_ids = sorted({alert.source_id for alert in alerts if alert.source_table == ALERT_SOURCE_TABLE_TIPS})
    tip_public_ids = (
        dict(session.execute(select(Tip.id, Tip.public_id).where(Tip.id.in_(tip_ids))).tuples().all()) if tip_ids else {}
    )
```

- Pass this as the last `AlertView(...)` argument, after `unread=...`:

```python
                tipId=tip_public_ids.get(alert.source_id) if alert.source_table == ALERT_SOURCE_TABLE_TIPS else None,
```

- [ ] **Step 6: Run the tests, then regenerate the contract**

```bash
python -m pytest tests/test_tip_alerts.py tests/test_run_tip_tracker.py tests/test_alert_delivery_and_sweep.py \
  tests/test_recommendation_alerts.py tests/test_epic329_alert_contract.py tests/test_alert_sweep_observability.py \
  tests/test_materiality_alert_sweep.py -v
python scripts/export_openapi.py
python -m pytest tests/test_openapi_contract_freshness.py -v
```

Expected: all PASS. The existing alert tests are unchanged: `tipId` defaults to null, and no existing alert type or severity moved.

- [ ] **Step 7: Commit**

```bash
git add app/recommendation_alerts.py app/tip_alerts.py scripts/run_tip_tracker.py api/schemas/alerts.py \
  api/services/alerts.py docs/api/openapi.json tests/test_tip_alerts.py tests/test_run_tip_tracker.py
git commit -m "Tip alerts: receipt holders hear entry and close once each, after the official tracking pass

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 8: Regression set, PR, merge, deploy, verify

**Files:** none new.

- [ ] **Step 1: Run the regression set**

```bash
rm -f "$TEMP/marksy-pytest-default.db"
python -m pytest tests/test_tip_scorecard.py tests/test_tip_scorecard_query.py tests/test_api_scorecards.py \
  tests/test_api_my_tips.py tests/test_api_instrument_calls.py tests/test_tip_alerts.py tests/test_run_tip_tracker.py \
  tests/test_tip_ledger.py tests/test_tip_tracker.py tests/test_tip_tracking_job.py tests/test_marksy_prediction_tips.py \
  tests/test_api_tips*.py tests/test_external_tip_*.py tests/test_prediction_outcome_monitor.py \
  tests/test_api_prediction_lifecycle.py tests/test_api_global_search.py tests/test_alert_delivery_and_sweep.py \
  tests/test_recommendation_alerts.py tests/test_epic329_alert_contract.py tests/test_openapi_contract_freshness.py \
  tests/test_security_authorization.py tests/test_epic331_authentication_coverage.py tests/test_read_only_principal.py \
  tests/test_score_metric_identity.py tests/test_operation_verification_s7.py -v
python -m alembic heads
```

Expected:
- All PASS. Handle any failure as the Global Constraints describe (check `main`, and note pre-existing failures in the PR).
- One alembic head, the one Task 1 wrote down.

- [ ] **Step 2: Push and open the PR**

```bash
git push -u origin feat/tip-ledger-scorecards
gh pr create --title "Tip ledger Phase 3b: scorecards, customer APIs, state-change alerts" --body "$(cat <<'EOF'
Serves the tip ledger (spec §8, §9) to customers and admins. No migration, no ORM change.

- `app/tip_scorecard.py`: pure §8.1 metrics, §8.2 trust score (Wilson lower bound x return quality, null under 10 completed), §8.3 IST windows and NSE trading days; golden-value tests
- `app/tip_scorecard_query.py`: the one scorecard reader; canonical channel/caller roll-up, MERGED_DUPLICATE excluded, customer scope counts each held tip once
- `GET /scorecards?entity=`, `/scorecards/summary`, `/scorecards/{entity}/{id}`, `/admin/scorecards`: every response echoes the applied filter and `asOf`
- `GET /me/tips`, `/me/scorecard`: own receipts only, other holders as a count, channel and caller headlines
- `GET /tips/{id}` gains `ledger` and `progress` (PROVISIONAL rows labelled on live tips, orphans dropped); `/instruments/{symbol}` gains `calls.marksy.engines[]` and `calls.external.channels[]`, read from the tip so a withdrawn losing Marksy call shows as a failed source exit; existing fields unchanged
- Receipt holders get TIP_ENTERED / TIP_CLOSED alerts through the existing alert delivery, after the official tracking pass, once per user and tip; `/alerts` items gain `tipId`

Tests: scorecard golden values and filters, query scopes, the new APIs, instrument calls, alerts, and the tip, prediction, alert, auth and OpenAPI regression files.

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```

- [ ] **Step 3: Merge and deploy**

```bash
gh pr merge --merge --delete-branch
ssh marksy@103.178.160.233 'bash ~/deployment/server/vps-deploy.sh'
```

Expected: the deploy script finishes. Its migrate Job has nothing to apply.

- [ ] **Step 4: Smoke-check production**

```bash
for path in "scorecards/summary" "scorecards?entity=channel" "me/tips" "me/scorecard" "admin/scorecards"; do
  curl -s -o /dev/null -w "$path %{http_code}\n" "https://marksy.indoulia.com/api/v1/$path"
done
ssh marksy@103.178.160.233 'KUBECONFIG=~/.kube/config kubectl -n market-agent exec deploy/market-agent-api -- python -c "from datetime import datetime, timezone; from app.db import SessionLocal; from app.tip_scorecard_query import scorecard_summary; s = SessionLocal(); q, parts = scorecard_summary(s, as_of=datetime.now(timezone.utc)); print(q.window.period, {k: (m.total, m.open, m.completed, m.trust_score) for k, m in parts.items()})"'
echo "select version_num from alembic_version;" \
  | ssh marksy@103.178.160.233 'KUBECONFIG=~/.kube/config kubectl -n market-agent exec -i deploy/postgres -- sh -c '"'"'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB"'"'"''
```

Expected:
- Every curl line prints `401`: the route is deployed and gated. A `404` means the routes did not ship.
- The in-pod line prints `LIFETIME {'all': (...), 'marksy': (...), 'external': (...)}` with no traceback. `marksy`'s total is the number of Marksy tips registered since 2b.
- `alembic_version` is the head Task 1 wrote down.

The first `tipAlerts` summary comes with the next 08:45 IST official run. Check it later with `select id, status, result_summary from orchestration_executions where operation_name = 'TIP_TRACKING' order by id desc limit 2;`.

- [ ] **Step 5: Clean up the worktree**

```bash
cd /c/AIAgent/marksy-api && git worktree remove ../marksy-api-phase3b && git checkout main && git pull
```

## Summary

Phase 3b turns the ledger into something people can read.
- **Scorecards.** One pure function computes every §8 number from canonical tips, and one reader feeds it. The same code serves the customer's own card, channel and caller cards, the Marksy/external summary, per-tip and per-instrument headlines, and the admin card. Every response echoes its IST window and `asOf`.
- **Customer reads.** Customers see the tips they received, counted once, with their own receipts and only a count of everyone else.
- **Tip detail.** A tip's page shows its progress series with provisional evenings labelled.
- **Stock page.** It gains Marksy engine and external channel groups read from the tip, so a withdrawn losing Marksy call can't hide as an invalidation.
- **Alerts.** Receipt holders are alerted once when their tip is entered or closes.

The next plans are Phase 4b (the marksy-os My tips, Scorecards and stock-page calls box, written against the Task 4–7 shapes) and Phase 5 (admin-app).

## Validation

- Tasks 1–2: `tests/test_tip_scorecard.py`
- Task 3: `tests/test_tip_scorecard_query.py`, `tests/test_tip_scorecard.py`
- Task 4: `tests/test_api_scorecards.py`, `tests/test_openapi_contract_freshness.py`, `tests/test_security_authorization.py`, `tests/test_epic331_authentication_coverage.py`
- Task 5: `tests/test_api_my_tips.py`, `tests/test_api_tips.py`, `tests/test_api_tips_ledger.py`, plus the Task 4 contract and auth files
- Task 6: `tests/test_api_instrument_calls.py`, `tests/test_api_prediction_lifecycle.py`, `tests/test_api_global_search.py`, `tests/test_openapi_contract_freshness.py`
- Task 7: `tests/test_tip_alerts.py`, `tests/test_run_tip_tracker.py`, plus the existing alert files in Task 7 Step 6
- Task 8: the regression set in Task 8 Step 1, `alembic heads`, and the production checks in Task 8 Step 4
