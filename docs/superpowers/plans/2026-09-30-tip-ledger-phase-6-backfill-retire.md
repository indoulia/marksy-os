# Tip Ledger Phase 6 (Backfill and Retirement) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Bring every call recorded before the ledger into it (spec §12), then retire what no reader needs any more. After this phase no `tips` row has a NULL status, every Marksy prediction has a tip, the tracker is the only price checker, and the Predictions tab, the Setups list and a captured call all read the tip, so a withdrawn losing Marksy call shows as a failed exit everywhere.

**Architecture:**
- **Part A (marksy-api, one PR, no migration).**
  - `app/tip_backfill.py` (new) holds the §12 rules:
    - legacy rows get a channel, their stated terms, a `match_key` and a receipt; open copies of one call collapse;
    - pre-2b predictions are registered through 2b's `register_prediction`, and a monitor withdrawal becomes their source exit;
    - every new ledger tip is replayed through the 2a tracker in batches.
  - `scripts/backfill_tip_ledger.py` (new) is the operator entrypoint: a read-only survey by default, `--apply` under the `TIP_TRACKING` lock.
  - `app/tip_ledger_guard.py` lets a pre-ledger row's direction be respelled once (LONG → BUY). `track_tips` takes `tip_ids`. `register_prediction` takes a `canonical_for` matcher.
  - `app/prediction_outcome_monitor.py` loses its price-check path.
  - `/predictions/active[/{id}]` and `/tracking/predictions` gain `ledger` (3b's `LedgerTipView`).
  - Retired: `GET /tips`, `GET /tips/scorecard`, `GET /tips/{id}/marksy-view`, `TipView.outcome` and `headToHead`, and the EPIC-845 outcome job (operation, CronJob, script, resolver).
- **Part B (marksy-os, one PR).** The Predictions tab, the Setups list and the captured-call insight read the tip. The app stops reading Marksy's EPIC-803 comparison.
- **admin-app:** no change. It reads no retired route (decision 13).

**Tech Stack:**
- marksy-api: Python 3.12, FastAPI 0.139, SQLAlchemy 2.0.52 (`Mapped`/`mapped_column`), Alembic 1.19, pytest on SQLite 3.49 (tests) and PostgreSQL (prod), Kubernetes.
- marksy-os: Kotlin 2.3.21, AGP 9.4.0, Gradle wrapper 9.7.1, Jetpack Compose (BOM 2026.08.00), JUnit 4 + Robolectric 4.17, `org.json:json:20240303` in unit tests.

**Spec:** `C:\AIAgent\marksy-os\docs\superpowers\specs\2026-09-29-marksy-central-tip-ledger-design.md`. This plan uses:
- §2 invariants 1, 2, 4, 5, 6 and 11;
- §4 "Superseded once readers move";
- §5.2 match_key v1 and merging;
- §6.7 replay;
- §7;
- §9, last line;
- §12 in full;
- §13 phase 6.

Plans this builds on (all merged before Task 1):
- 0–1: `...\plans\2026-09-29-tip-ledger-phase-0-1.md`, decisions 8, 9, 13 and 14.
- 2a: `...\plans\2026-09-29-tip-ledger-phase-2a-tracker.md`, decisions 3, 7 and 12.
- 2b: `...\plans\2026-09-30-tip-ledger-phase-2b-marksy-predictions.md`, decisions 1, 2, 5, 7 and 8.
- 4a: `...\plans\2026-09-30-tip-ledger-phase-4a-capture.md`, decisions 1, 11 and 12.
- 3a/5: `...\plans\2026-09-30-tip-ledger-phase-3a-5-admin.md`, decision 3 (`_reparse_match`) and Task A3.
- 3b: `...\plans\2026-09-30-tip-ledger-phase-3b-scorecards.md`, Task 5.
- 2c: `...\plans\2026-09-30-tip-ledger-phase-2c-rating-engine.md`.
- 4b: `...\plans\2026-09-30-tip-ledger-phase-4b-app.md`, decision 9 and Task B3.

## Global Constraints

**Both parts**
- **Merge and deploy are authorized** (`feedback_merge_authorized` memory).
  - Merge with `gh pr merge --merge --delete-branch` once the part's final test set is green.
  - Deploy marksy-api (and admin-app, which the same script ships) with `ssh marksy@103.178.160.233 'bash ~/deployment/server/vps-deploy.sh'`.
  - GitHub Actions don't run (account billing), so an UNSTABLE status is expected and doesn't block a merge.
  - Never leave a PR open. A step that needs the user's approval is parked, listed in "Parked" below, and the plan moves on.
- **Trailers.** Commit messages end with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`. PR bodies end with `🤖 Generated with [Claude Code](https://claude.com/claude-code)`.
- Commit once per task. Comments are one line at most and explain a non-obvious WHY only. No unrelated refactors.
- **Spec, verbatim:**
  - §12: "Each `external_tips` row gets a channel via the alias of its `source`, stated terms from its columns, and a `match_key`. Rows sharing a key while open collapse into one tip; the others become receipts. The receipt customer is the API key's `bound_user_id`, else the system user `legacy-unknown`. Existing Marksy predictions are registered as tips. Tracking replays from `first_seen_at` (= `received_at` for legacy rows) under §6; no history → DATA_UNRESOLVED."
  - §2.5: "Call terms (channel, symbol, direction, stated entry, target, stop-loss, stated horizon) and `first_seen_at` are immutable. Tips and receipts are never deleted."
  - §6.7: "Replay and backfill never start tracking late — no history means DATA_UNRESOLVED."
  - §7: "Marksy withdrawing its own prediction … is a source exit: SOURCE_EXIT at the price then. Marksy cannot drop a losing call by invalidating it."
  - §9: "`/tips`, `/tips/scorecard` and `/tips/{id}/marksy-view` remain until their readers move, then are removed."
- **Privacy (§2.11).** No new customer data is stored. A backfilled receipt stores only what the row already holds after migration 0181's one-way scrub, cleaned again. That is the text 0–1 decision 14 reserved for this backfill.

**Part A (marksy-api)**
- **Repo and worktree.** Repo: `C:\AIAgent\marksy-api`. Work in the worktree `C:\AIAgent\marksy-api-phase6` on branch `feat/tip-ledger-backfill-retire`, created from `origin/main` once Task 1 Step 1's prerequisites all hold.
- **No migration.** Part A adds no table, column or index. `python -m alembic heads` must print one head, `0185_rating_history` (the chain is 0182 → 0184 → 0183 → 0185). If it prints anything else, stop and report.
- **Tests.**
  - Run only the `python -m pytest ...` commands given in each step. Never run the full suite (about 2 hours).
  - If a listed test fails, run the same file on `main` in `C:\AIAgent\marksy-api`.
    - If it also fails there and the cause is local to the test (a date, a fixture), fix it in its own commit.
    - Otherwise note it in the PR.
  - The 3b plan lists four known pre-existing failures: `test_openapi_contract_freshness.py::test_the_ipo_capability_is_still_false` and three advisory tests in `test_api_tips.py`.
- **Pytest file DB.** `tests/conftest.py` gives the app engine a file DB at `%TEMP%\marksy-pytest-default.db`, and `create_all` never alters an existing table. Part A changes no ORM schema, but 2c and 3a/3b landed since the last reset, so Task 1 deletes it once.
- **OpenAPI.** `docs/api/openapi.json` is checked byte for byte by `tests/test_openapi_contract_freshness.py`. Every task that touches `api/` runs `python scripts/export_openapi.py` and commits the file.
- **Code style.** Follow the repo:
  - `Mapped`/`mapped_column`, and no ORM relationships;
  - camelCase response fields, Decimals as JSON strings, and the `success(...)` envelope;
  - private helpers shared across ledger modules are imported by name, as `marksy_tips` already imports `_lock_match_key`.
- **SQLite and time zones.** SQLite drops tzinfo on read. Pass every stored datetime through `_aware(...)` (assume UTC) before comparing it or converting it to IST.

**Part B (marksy-os)**
- **Repo and worktree.** Repo: `C:\AIAgent\marksy-os`. Work in `C:\AIAgent\marksy-os-phase6` on branch `feat/tip-ledger-retire-reads`, created from `origin/main` after Part A is deployed. Leave `C:\AIAgent\marksy-os` on its current branch.
- **Unit tests** (the `marksy-os-local-run-env` memory). The Windows `Path` has a corrupted entry that kills forked test JVMs, so run:

  ```bash
  cd /c/AIAgent/marksy-os-phase6
  export PATH="$(echo "$PATH" | tr ':' '\n' | grep -vE '"|%' | paste -sd:)"
  ./gradlew --stop
  ./gradlew --no-daemon :app:testDebugUnitTest --tests 'com.marksy.os.market.LedgerCallsTest'
  ```
- **No device step.**
  - Never run `connectedAndroidTest`: it wipes the user's data.
  - No `adb`, no install and no `am start`. The controller installs on the phone in the morning, under the window-focus safety gate.
- **Tests guard real logic only** (the `test-budget-feedback` memory). No UI-layout test. Extend existing test files.
- **UI conventions.** Existing Marksy components only: `Pill`, `EmptyState`, the Surface + BorderGlow card, and emerald only for an open Marksy call. No new heading row.

## Review Focus

These are the inputs the spec implies but its happy paths never exercise. Each one has a test in the owning task:

1. **Copies of one open call, including the same customer twice on one medium.**
   - Two legacy phone posts (both `legacy-unknown`, APP_NOTIFICATION) and one bridge post (bound user `user-9`) of the same Upstox call, while it is open.
   - They must collapse into the first row. The copies become MERGED_DUPLICATE, pointing at it.
   - Each row still leaves a receipt.
   - The second `legacy-unknown` receipt must stay on its copy, or the `(tip_id, user_id, medium)` index raises IntegrityError.
   - Test: Task 3, `test_legacy_copies_of_one_open_call_collapse_into_the_first_and_each_leaves_a_receipt`.
2. **An identical call received after the first had already closed.**
   - Before replay, every backfilled tip is ACTIVE, so a naive match would fold the later call into a call that was over.
   - The later call must be its own tip.
   - Test: Task 3, `test_a_later_identical_call_after_the_first_closed_is_a_new_tip`.
3. **A pre-ledger prediction Marksy withdrew while it was losing.**
   - The monitor wrote INVALIDATED and no exit, because no tip existed then.
   - After the backfill it must score SOURCE_EXIT / FAILURE at the next open. It must not become an unscored invalidation, or a call tracked on to its target.
   - Its first terminal event must stay the monitor's.
   - Test: Task 4, `test_a_pre_ledger_withdrawal_of_a_losing_call_scores_a_failed_source_exit`.
4. **A backfilled tip first seen before the candle store has any session.**
   - It must be DATA_UNRESOLVED (`NO_HISTORY`), never tracked from whenever the store begins.
   - A tip seen on the weekend before the store's first session is not "late", and must be tracked.
   - Test: Task 2, `test_a_tip_older_than_the_candle_store_is_data_unresolved_not_tracked_late`.
5. **A withdrawn losing Marksy call on the Predictions tab and the Setups list.**
   - `/predictions/active` says `INVALIDATED` (the monitor), while its tip says SOURCE_EXIT / FAILURE at −3%.
   - The open list must file it under "ended" calls, shown by default, reading "Exited · Failed · -3.00%" and never "Invalidated".
   - Setups must not offer it as live, and the closed list must read the ledger's state and return.
   - Tests: Task 7, `test_a_withdrawn_losing_call_carries_its_failed_exit_beside_the_monitor_state` (API), and Task 10, `LedgerCallsTest.aWithdrawnLosingPredictionReadsAsAFailedExitNotAnInvalidation` (app).

## Resolved ambiguities (decisions this plan makes)

1. **The backfill is an operator-run, idempotent one-off script, not a data migration.**
   - Migrations must not import app code (0181, 2b decision 8). The backfill needs:
     - `resolve_channel`, `match_key_v1`, `intake_rejection` and `register_prediction`;
     - the tracker, which needs Upstox minute bars.
   - A migration runs inside the deploy window: CronJobs are suspended and the migrate Job has a 300 s wait (`vps-deploy.sh`).
   - A script can be surveyed first, re-run, and resumed after an interruption.
   - It runs in the API pod (`kubectl exec deploy/market-agent-api -- python -m scripts.backfill_tip_ledger`), under the `TIP_TRACKING` execution lock, so the scheduled tracker never tracks a tip the backfill is still completing.
2. **Channel resolution: the `source` alias, in the scope of the path the row came in on.** The resolver is `resolve_channel`, exactly as live intake uses it today.
   - `POST /tips` rows are APP_NOTIFICATION, resolved by package, then by `source` as a label (Phase 1 `submit_tip`; 4a decision 1).
     - A row carries `raw_payload.sourcePackage` (the app) or `client_name` (an API client).
   - Admin-paste rows are MANUAL, resolved by `source` as a label (`_submit_report_tip`).
     - They carry neither marker.
   - So a legacy row keys exactly as its live re-sending would.
   - Legacy chat notifications land on the chat app's channel (for example WhatsApp), never on a group channel. Their titles came from any chat, and none was allow-listed (0–1 decision 14; §5.1 amended 2026-09-30).
   - An unknown source creates its channel, as intake does. The survey lists these first (`legacyNewChannels`).
3. **Stated terms come from the columns, and the direction is respelled once.**
   - The terms:
     - `entry_low = entry_high = entry_price`;
     - `target_price` and `stop_loss`;
     - `horizon_days` as `horizon_sessions`, as live `POST /tips` reads `horizonDays`.
   - A missing entry is FIRST_SEEN_PRICE, and a missing horizon takes the channel default (CHANNEL_DEFAULT).
   - The paste's `raw_payload` entry range is not used. `entry_price` is immutable and already holds the paste's midpoint, and the tracker scores from it (`_TipTracking` reads `tip.entry_price`). A stated range around another midpoint would contradict it.
   - Pre-ledger code only uppercased `direction`, so LONG, SHORT or ACCUMULATE can be stored, and the tracker's sign reads anything but BUY as SELL. So the guard gains exactly one exemption: a pre-ledger row's direction may change to `normalize_direction(previous)`, the meaning it already had.
   - HOLD and other unknowns stay as stored and close UNSCORABLE (`NO_DIRECTION`).
4. **Collapse, and which row is the tip.**
   - Legacy rows go oldest first (`received_at`, then id).
   - Each row joins the tip its call already was, by 3a's re-parse rule `_reparse_match`, because a backfilled row is a late read of a call, exactly as a re-parsed receipt is. In order:
     - the tip open when the row arrived;
     - else a tip rejected at intake and still inside its horizon then;
     - else the ACTIVE tip;
     - else a later tip inside the row's horizon.
   - First the key's ACTIVE tip is replayed ("settle"). "Open when it arrived" is then a tracked fact, not an artefact of rows not yet replayed (Review Focus 2).
   - So the earliest copy of an open call is the tip. A copy becomes MERGED_DUPLICATE with `merged_into_tip_id` and `closed_at = first_seen_at`, and is kept (§2.5), as 2b records a duplicate prediction.
   - A legacy row whose call is a live ledger tip first seen later joins that tip, because that tip's `first_seen_at` is immutable (the re-parse precedent).
   - The partial unique index allows one ACTIVE copy per key, so an existing ACTIVE tip always absorbs a same-key row.
5. **Receipts.** Every legacy row adds one TIP receipt for its own sighting:
   - `user_id`: the bound user of the row's API client. That is `client_name` → every `api_keys` row of that client naming one `bound_user_id`.
     - Else `legacy-unknown`. Bearer-session app posts recorded no user (0–1 decision 13).
   - `medium` is decision 2's, `channel_label = source`, `app_package = raw_payload.sourcePackage`, `recorded_at = received_at`, `device_posted_at = tip_as_of`, and `parser_version` is the path's (`APP-001` or `MTR-001`).
   - `text`: the 0181-cleaned body or rationale (APP) or the paste JSON (MANUAL), cleaned again, as live intake stores it.
   - `device_event_key = backfill:<tip public_id>`. It is deterministic, so a re-run finds it. The legacy `app:<sha>` key cannot be used, because a row without a source reference got a random one.
   - **One holding per customer, tip and medium (§4).** A customer who already holds the canonical tip on that medium keeps that receipt, and the copy's receipt stays on the copy, which points at the call. This is 3a's merge rule.
   - A receipt recorded after Phase 1 on a legacy row (a re-paste matched by `source_reference`) moves to the canonical tip, with the same exception.
6. **Idempotency and resumption.**
   - Every step selects by state: legacy rows by `match_key IS NULL`, predictions by having no tip.
   - Each row or prediction commits on its own, receipts are keyed `(user_id, backfill:<public_id>)`, and replay is the tracker's own resume-after-last-FINAL row.
   - A second run reports zero everywhere.
   - An interrupted run leaves only completed rows. The next run skips them, and the scheduled tracker resumes their tracking, since they are ordinary ACTIVE ledger tips.
7. **Registering pre-2b predictions** (spec §12, "Existing Marksy predictions are registered as tips").
   - **Terms.** `register_prediction` unchanged: `first_seen_at = created_at`, BUY, entry and levels from the returns, ENGINE horizon, and no receipts (2b decisions 1–2).
   - **Matching.** The same settle-and-re-parse matching, injected as `canonical_for`, so an identical call made after the first closed is its own tip.
   - **Replay, not copy.**
     - Tracking replays under §6, and the monitor's events are never copied into the tip. §7 allows no Marksy-specific rule, and §6.7 requires replay.
     - The monitor's events are append-only, and the first terminal event stands. The 2b sync never rewrites an existing terminal event, and moves `Prediction.status` only from OPEN.
     - Where replay disagrees with a monitor TARGET_HIT (the monitor has no §6.3 entry rule), the tip holds its §6 result. Every Marksy surface reads the tip: 3b, 4b and Task 7.
   - **Withdrawals.**
     - A monitor INVALIDATED event (rule `RTM-001`, from decay or the 08:30 confirmation) is Marksy withdrawing the call.
     - It becomes a `TipSourceExit` at the event's `detected_at` (`withdraw_prediction`), written before any replay. So a losing withdrawn call scores a failed SOURCE_EXIT (Review Focus 3).
   - **The rest.**
     - A tip closed at birth (rejected, or a follower of a closed call) is synced at once, as `record_recommendation` does.
     - Revisions recorded before 2b are linked from `recommendation_revisions` (`link_revision`).
     - Rating-engine history is not backfilled: 2c computes it server-side from its own deploy, so nothing before it exists.
8. **Replay tracking.**
   - After registration, on official bars only, through `track_tips(tip_ids=...)` in batches of 200 (`--batch-size`).
   - Minute bars are fetched from Upstox exactly as the scheduled tracker does (never stored). Without them, session 0 and same-session order would be lost for good, because FINAL rows are never rewritten.
   - **No history.** A tip first seen before the candle store's first official bar, with at least one NSE trading day in between (`count_trading_days`), closes DATA_UNRESOLVED (`NO_HISTORY`), with `closed_at = first_seen_at`.
     - Otherwise `tip_sessions` would start it on whatever session the store holds first. That is exactly "tracking late".
     - Stock-level gaps keep the tracker's own MISSING_BARS rule.
   - **Performance.**
     - Registration is one indexed `match_key` lookup per row, and settle replays only on a key collision (rare).
     - About 43 + ≤ 2001 tips means about 11 replay batches. Each batch recomputes the session calendar once, from its oldest tip.
     - Marksy horizons are ≤ 7 sessions (`VALID_HORIZON_DAYS`), so most pre-2b tips close within their batch.
     - Run the backfill away from the 08:45 and 16:10 IST tracker slots. The execution lock makes a clash skip, never double-track.
9. **The survey is the gate before any write.** It is read-only and reports:
   - legacy rows, by medium, direction spelling and receipt customer;
   - the sources that will create channels;
   - predictions without a tip, their highest id, and how many carry a withdrawal;
   - the candle store's first session.
   
   Task 9 stops unless production shows 43 legacy rows and no untipped prediction id above 2001.
10. **Retirement order and gates, route by route** (readers were checked in code on 2026-09-30, and are re-checked by Task 1 and Task 9).
    - **`GET /tips` (list), `GET /tips/scorecard`, `GET /tips/{id}/marksy-view`: removed in Part A.**
      - No reader in code:
        - marksy-os `main`, and the 4a/4b builds, read only `POST /tips`, `POST /tips/ingest-text` and `GET /tips/{id}`;
        - admin-app reads only `/tips/ingest-text` and `/admin/*`;
        - the ai-trading-agent reads only `POST /v1/tips` and `GET /v1/tips/{id}`;
        - the Flutter web client is retired (EPIC-852) and out of scope (§14).
      - Gate: that census, plus zero matching lines in the API pod's access log since its last restart, checked at Task 1 and again before merge.
    - **`TipView.outcome` and `headToHead`: removed in Part A.** No reader: the app parses only `comparison` and `marksyView`, and the agent only `comparison` and the top-level terms.
    - **`POST /tips`, `TipView.comparison`, `TipView.marksyView`, and `status`/`sourceReference` as comparison fields: kept.**
      - The ai-trading-agent (`app/marksy.py`) posts `/v1/tips` with an API key and reads `comparison.verdict` back. It is outside this phase's three repos, so these retire when it moves to a ledger intake. That is parked, because it needs the user's approval.
      - Part B still moves the phone off `comparison` and `marksyView`, so the agent is then their only reader.
    - **Tables. None is dropped. Tips are never deleted.**
      - `external_tip_outcomes`: its only writer, the EPIC-845 job (operation, CronJob, script and resolver module), is retired in Part A. The rows stay read-only, because the dashboard's `externalTips` block (§14 out of scope, and no live client) still reads them.
      - `external_tip_comparisons` stays live for the agent (see above).
      - `daily_prediction_snapshots` has no scoring reader to retire. No code in `app/`, `api/` or `scripts/` calls `capture_daily_prediction_snapshot` or reads snapshots for scoring. Only `end_to_end_validation_gate_v2` checks that one exists. So it is unchanged.
      - Dropping any of these would be irreversible while readers outside the three repos remain.
    - **Modules.**
      - `app/external_tip_outcome.py` and `scripts/run_external_tip_outcomes.py` are deleted with their operation.
      - `app/external_tip_scoring.py` loses `source_scorecard`, and keeps `head_to_head_for_tip` for the dashboard.
      - The `ExternalTip` alias stays, for the comparison code.
    - The phone install (the morning) gates nothing in the backend. Part B only removes the phone as a reader of what stays.
11. **The monitor's legacy price-check path retires in Part A, with the backfill.**
    - For a prediction without a tip, `evaluate_prediction_realtime` now returns before any price check; the old ones are deleted:
      - `_evaluate_bars`, `_valid_ohlc` and `_Evaluation`;
      - the stale-data event and `STALE_BAR_GAP_DAYS`.
    - Decay withdrawals and the tip sync are unchanged.
    - Between the deploy and the backfill's `--apply` (minutes, in Task 9), a pre-2b OPEN prediction is simply not price-checked. Nothing is lost: once registered, the tracker replays it from `first_seen_at`, deterministically from the bars. Its first terminal event is then the tracker's, consistent with the ledger.
    - A decay withdrawal in that window writes its event without an exit (no tip yet). The backfill turns that event into the exit (decision 7).
    - Gate: Task 9 verifies in production that no prediction lacks a tip. After that, `record_recommendation` registers every new one at birth (2b).
    - Tests that relied on the monitor resolving a tipless prediction (EPIC-366 reassessment, sweep progress and monitor tests) now close the call's tip as the tracker would, then sync. This is 2b's own pattern (`test_an_unlinked_revision_still_resolves_through_the_outcome_monitor`).
12. **The Predictions tab and the Setups list read the tip** (4b decision 9's follow-up).
    - **Backend.** `ActivePrediction` and `TrackedPrediction` gain `ledger: LedgerTipView | None`, 3b's shape. A MERGED_DUPLICATE follower shows the tip it follows (2b decision 7).
    - **Open list.** A call is live only if its tip is ACTIVE and the monitor has not ended it.
      - Ended calls stay listed, shown by default, each with its ledger line ("Exited · Failed · -3.00%").
      - A withdrawal the tracker has not priced yet (monitor INVALIDATED, tip still ACTIVE) reads "Withdrawn · result pending", never "Invalidated".
    - **Closed list.** It shows the ledger's state and actual return when `ledger` is present.
    - **Setups.** Setups shows live calls only.
    - **Track-record strip.** The strip stays M1.5's `/performance/summary`. It is a full-horizon learning record that counts withdrawn calls too, so it hides no loss. Moving it to the Prediction-engine scorecard is a presentation change.
    - **Older backend.** Without `ledger`, the old monitor rule applies.
13. **admin-app needs no change.** `origin/main` and the Phase 5 plan's `src/api/tipLedger.ts` call only `/tips/ingest-text` (kept) and `/admin/*`. After the backfill, 3a's Tip Ledger page simply lists the 43 former NULL-status rows.
14. **Vocabulary.** One new reason, `NO_HISTORY`, which fits `reason` String(32). The app's `LedgerCalls.state` words any reason generically ("No market data · No history").

## File Structure

**Part A (`C:\AIAgent\marksy-api-phase6`)**
- Modify:
  - `app/tip_vocabulary.py`: `REASON_NO_HISTORY`.
  - `app/tip_ledger_guard.py`: the pre-ledger direction respelling.
  - `app/tip_tracking_job.py`: `track_tips(..., tip_ids=None)`.
  - `app/marksy_tips.py`: `register_prediction(..., canonical_for=None)`.
- Create `app/tip_backfill.py`: replay and no-history (Task 2), legacy rows (Task 3), predictions (Task 4), and `backfill_ledger` and `survey` (Task 5).
- Create `scripts/backfill_tip_ledger.py`.
- Modify `app/prediction_outcome_monitor.py`: the price path goes.
- Modify, for the ledger on predictions (`prediction_ledger_views` and the `ledger` fields):
  - `api/services/ledger_tips.py`;
  - `api/schemas/predictions_active.py` and `api/schemas/tracking.py`;
  - `api/services/predictions_active.py` and `api/services/tracking.py`.
- Modify, for the retired routes and `source_scorecard`: `api/routers/tips.py`, `api/services/tips.py`, `api/schemas/tips.py` and `app/external_tip_scoring.py`.
- Modify, to unregister `EXTERNAL_TIP_OUTCOME`: `app/schedule_orchestration.py`, `app/operation_entrypoints.py`, `app/operation_recovery.py` and `deploy/k8s/base/kustomization.yaml`.
- Delete `app/external_tip_outcome.py`, `scripts/run_external_tip_outcomes.py` and `deploy/k8s/base/external-tip-outcomes-cronjob.yaml`.
- Regenerate `docs/api/openapi.json`.
- Tests:
  - Create `tests/test_tip_backfill.py` and `tests/test_backfill_tip_ledger_script.py`.
  - Modify:
    - `tests/test_tip_ledger_guard.py` and `tests/test_prediction_outcome_monitor.py`;
    - `tests/test_marksy_prediction_tips.py`, `tests/test_marksy_rating_tips.py`, `tests/test_epic366_prediction_lifecycle.py` and `tests/test_reassessment_sweep_progress.py`;
    - `tests/test_api_predictions_active.py`, `tests/test_api_tips.py` and `tests/test_external_tip_scoring.py`;
    - `tests/test_operation_verification_s7.py` and `tests/test_api_operations_health.py`.
  - Delete `tests/test_external_tip_outcome.py`.

**Part B (`C:\AIAgent\marksy-os-phase6`, paths under `app/src/`)**
- Modify:
  - `main/java/com/marksy/os/market/InstrumentModels.kt` (`ActivePredictionDto.ledger`);
  - `main/java/com/marksy/os/market/PredictionModels.kt` (`ClosedPredictionDto.ledger`);
  - `main/java/com/marksy/os/market/LedgerCalls.kt`: `isLive`, `endedLine`, `lifecycleWord`, `closedLabel` and `closedReturn`;
  - `main/java/com/marksy/os/ui/PredictionsView.kt` and `main/java/com/marksy/os/ui/SetupsView.kt`;
  - `main/java/com/marksy/os/gateway/MarksyTipsApiClient.kt` (`ledgerInsight`).
- Tests: modify `test/java/com/marksy/os/market/LedgerCallsTest.kt` and `test/java/com/marksy/os/gateway/MarksyTipsApiContractTest.kt`.

---

## Part A — marksy-api: the backfill and the retirements

### Task 1: Prerequisites, reader census, worktree, `NO_HISTORY`, and the direction respelling

**Files:**
- Modify: `app/tip_vocabulary.py` (after `REASON_NO_INTRADAY_BARS`), `app/tip_ledger_guard.py`
- Test: `tests/test_tip_ledger_guard.py`

**Interfaces:**
- Produces `REASON_NO_HISTORY = "NO_HISTORY"`.
- Guard rule: on a row whose committed `match_key` is NULL, `direction` may change from a non-null value to `normalize_direction(value)`. Every other term change on a non-null value is still rejected.

- [ ] **Step 1: Check that every phase this builds on has merged and deployed**

```bash
cd /c/AIAgent/marksy-api && git fetch origin -q
for f in app/tip_ledger_admin.py api/services/ledger_tips.py app/tip_scorecard_query.py migrations/versions/0185_rating_history.py; do
  git cat-file -e "origin/main:$f" 2>/dev/null && echo "present $f" || echo "MISSING $f"; done
git show origin/main:app/tip_ledger.py | grep -c "def _reparse_match"
git show origin/main:api/services/ledger_tips.py | grep -cE "def ledger_view|def _progress_rows"
cd /c/AIAgent/marksy-os && git fetch origin -q && git cat-file -e origin/main:app/src/main/java/com/marksy/os/market/LedgerCalls.kt && echo "4b merged"
cd /c/AIAgent/admin-app && git fetch origin -q && git cat-file -e origin/main:src/api/tipLedger.ts && echo "phase 5 merged"
for route in me/tips scorecards/summary admin/channels; do curl -s -o /dev/null -w "$route %{http_code}\n" "https://marksy.indoulia.com/api/v1/$route"; done
```

Expected:
- four `present` lines, then `1`, then `2`;
- `4b merged` and `phase 5 merged`;
- `401` for each route (they exist, so 3a, 3b and 2c are deployed).

If anything is missing or a route prints 404, **stop and report**: a prerequisite phase has not landed.

- [ ] **Step 2: Census the readers of what Part A retires**

```bash
cd /c/AIAgent/marksy-os && git grep -nE 'tips/scorecard|marksy-view|"\$apiBaseUrl/tips"|/tips\?' origin/main -- app/src/main || echo "marksy-os: no retired read"
cd /c/AIAgent/admin-app && git grep -nE 'http\.[a-zA-Z]+(<[^>]*>)?\([`"]/tips' origin/main -- src
grep -n '/v1/tips' /c/AIAgent/ai-trading-agent/app/marksy.py
ssh marksy@103.178.160.233 'KUBECONFIG=~/.kube/config kubectl -n market-agent logs deploy/market-agent-api --since=720h' \
  | grep -cE '"GET /api/v1/tips(\?[^ ]*)? HTTP|"GET /api/v1/tips/scorecard|"GET /api/v1/tips/[^/ ]+/marksy-view'
```

Expected:
- `marksy-os: no retired read`.
- admin-app prints only its `/tips/ingest-text` call.
- The agent prints its `POST .../v1/tips` and `GET .../v1/tips/{tip_id}` lines. Both routes are kept (decision 10).
- The log count is `0`.

The log only covers the API pod's life since its last restart (one replica), so the code census is the primary gate. If the count is not 0, **stop**: rerun the pipeline without `-c` to print the lines (time and path), and report the reader. Nothing may be removed while it reads.

- [ ] **Step 3: Create the worktree and reset the pytest file DB**

```bash
cd /c/AIAgent/marksy-api && git worktree add ../marksy-api-phase6 -b feat/tip-ledger-backfill-retire origin/main
cd /c/AIAgent/marksy-api-phase6 && git log --oneline -1 && python -m alembic heads
rm -f "$TEMP/marksy-pytest-default.db"
```

Expected: one head, `0185_rating_history (head)`. All later Part A commands run in `C:\AIAgent\marksy-api-phase6`.

- [ ] **Step 4: Write the failing guard test**

In `tests/test_tip_ledger_guard.py`, add `("direction", "SELL"),` as the first entry of the `@pytest.mark.parametrize("field, value", [...])` list of `test_a_stated_term_never_changes`, and append:

```python
def test_a_pre_ledger_direction_may_only_be_respelled_to_what_it_already_meant(session):
    tip = _tip(session, public_id="t-2", match_key=None, status=None, direction="LONG")
    tip.direction = "SELL"
    _assert_rejected(session)
    tip.direction = "BUY"
    session.commit()
    tip.direction = "SELL"
    _assert_rejected(session)
    assert session.get(Tip, tip.id).direction == "BUY"
```

- [ ] **Step 5: Run it to verify it fails**

Run: `python -m pytest tests/test_tip_ledger_guard.py -v`
Expected: the new test FAILs with `TipLedgerImmutableError: tip t-2 terms are immutable; ['direction'] cannot change` at `session.commit()`. Every other test passes, including the new `direction` case, which is already rejected.

- [ ] **Step 6: Add the vocabulary and the respelling rule**

In `app/tip_vocabulary.py`, directly after `REASON_NO_INTRADAY_BARS = "NO_INTRADAY_BARS"`, add:

```python
REASON_NO_HISTORY = "NO_HISTORY"
```

In `app/tip_ledger_guard.py`:
- add `from .tip_matching import normalize_direction` below `from .models import Tip, TipReceipt`;
- directly above `@event.listens_for(Tip, "before_update")`, add:

```python
def _respelled(key: str, previous, new, ledger_tip: bool) -> bool:
    # §12: a pre-ledger row's raw spelling ("LONG") may become the canonical direction it already meant ("BUY").
    return not ledger_tip and key == "direction" and previous is not None and normalize_direction(previous) == new
```

- in `_reject_term_changes`, replace:

```python
        previous = history.deleted[0] if history.deleted else None
        if previous is not None or not _may_fill(target, key, ledger_tip):
```

with:

```python
        previous = history.deleted[0] if history.deleted else None
        if _respelled(key, previous, history.added[0] if history.added else None, ledger_tip):
            continue
        if previous is not None or not _may_fill(target, key, ledger_tip):
```

- [ ] **Step 7: Run the tests**

Run: `python -m pytest tests/test_tip_ledger_guard.py tests/test_tip_ledger.py tests/test_tip_matching.py -v`
Expected: all PASS.

- [ ] **Step 8: Commit**

```bash
git add app/tip_vocabulary.py app/tip_ledger_guard.py tests/test_tip_ledger_guard.py
git commit -m "Tip ledger backfill: a pre-ledger direction may be respelled to what it meant; NO_HISTORY reason

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 2: Replay in batches, and "no history" is DATA_UNRESOLVED

**Files:**
- Modify: `app/tip_tracking_job.py` (`track_tips`)
- Create: `app/tip_backfill.py`
- Test: create `tests/test_tip_backfill.py`

**Interfaces:**
- Produces `track_tips(session, *, now, minute_bars, provisional=False, tip_ids: Sequence[int] | None = None) -> TrackingRun`. With `tip_ids`, only those ACTIVE ledger tips are tracked.
- Produces, in `app/tip_backfill.py`:
  - `DEFAULT_BATCH_SIZE = 200`
  - `@dataclass BackfillReport`. Counters: `legacy_completed`, `legacy_collapsed`, `legacy_rejected`, `receipts_created`, `receipts_moved`, `predictions_registered`, `predictions_following`, `predictions_rejected`, `withdrawals`, `revisions_linked` and `no_history`. Also `failures: list[str]` and `replay: TrackingRun`.
    - `summary()` returns `{**replay.summary(), "backfill": {camelCase counters, "failed", "failures"}}`.
  - `first_store_session(session) -> date | None`
  - `close_without_history(session, tip_ids, *, now) -> int`
  - `replay(session, tip_ids, *, now, minute_bars, batch_size=DEFAULT_BATCH_SIZE, report=None) -> BackfillReport`

- [ ] **Step 1: Write the failing tests**

Create `tests/test_tip_backfill.py`:

```python
"""Tip-ledger spec §12 backfill on SQLite: legacy rows, pre-ledger Marksy predictions, and their replay under §6."""
from __future__ import annotations

import itertools
import uuid
from datetime import date, datetime, time, timezone
from decimal import Decimal

import pytest
from sqlalchemy import create_engine, select
from sqlalchemy.orm import sessionmaker

from app.db import Base
from app.market_data.quality import NSE_TIMEZONE
from app.models import Channel, ChannelAlias, MarketPrice, Stock, Tip, TipDailyProgress
from app.tip_backfill import replay
from app.tip_matching import MATCH_KEY_VERSION, match_key_v1, stated_terms
from app.tip_vocabulary import (
    ALIAS_LABEL,
    ALIAS_PACKAGE,
    CHANNEL_BROKER_APP,
    ENTRY_BASIS_STATED,
    ENTRY_WAITING,
    HORIZON_BASIS_STATED,
    MEDIUM_APP_NOTIFICATION,
    REASON_NO_HISTORY,
    STATUS_ACTIVE,
    STATUS_DATA_UNRESOLVED,
)

MON, TUE, WED, THU, FRI = (date(2026, 9, 21), date(2026, 9, 22), date(2026, 9, 23), date(2026, 9, 24),
                           date(2026, 9, 25))
NEXT_MON = date(2026, 9, 28)
NOW = datetime(2026, 10, 1, 5, 0, tzinfo=timezone.utc)


def ist(day, hour, minute=0):
    """`day` at hour:minute IST, as UTC."""
    return datetime(day.year, day.month, day.day, hour, minute, tzinfo=NSE_TIMEZONE).astimezone(timezone.utc)


def aware(value):
    return value if value.tzinfo is not None else value.replace(tzinfo=timezone.utc)


@pytest.fixture
def session():
    engine = create_engine("sqlite:///:memory:")
    Base.metadata.create_all(engine)
    db = sessionmaker(bind=engine)()
    db.add_all([
        Stock(symbol="RENUKA", exchange="NSE", is_active=True, instrument_key="NSE_EQ|INE087H01022"),
        Stock(symbol="RELIANCE", exchange="NSE", is_active=True, instrument_key="NSE_EQ|INE002A01018"),
    ])
    upstox = Channel(name="Upstox", type=CHANNEL_BROKER_APP, default_horizon_sessions=20, capture_enabled=True)
    db.add(upstox)
    db.flush()
    upstox.canonical_channel_id = upstox.id
    db.add_all([
        ChannelAlias(channel_id=upstox.id, alias="com.upstox.pro", kind=ALIAS_PACKAGE, scope=MEDIUM_APP_NOTIFICATION),
        ChannelAlias(channel_id=upstox.id, alias="upstox", kind=ALIAS_LABEL, scope=MEDIUM_APP_NOTIFICATION),
    ])
    db.commit()
    try:
        yield db
    finally:
        db.close()


def _stock(session, symbol):
    return session.scalar(select(Stock).where(Stock.symbol == symbol))


def _upstox(session):
    return session.scalar(select(Channel).where(Channel.name == "Upstox"))


def _bar(session, day, o, h, l, c, *, symbol="RENUKA"):
    session.add(MarketPrice(
        stock_id=_stock(session, symbol).id,
        timestamp=datetime.combine(day, time(0), NSE_TIMEZONE).astimezone(timezone.utc), session_date=day,
        open=Decimal(str(o)), high=Decimal(str(h)), low=Decimal(str(l)), close=Decimal(str(c)), volume=1000,
        source="upstox-v3",
    ))
    session.commit()


def _rows(session, tip):
    return session.scalars(
        select(TipDailyProgress).where(TipDailyProgress.tip_id == tip.id).order_by(TipDailyProgress.session_index)
    ).all()


def _ledger_tip(session, *, seen, symbol="RENUKA", entry="23.62", target="26", stop="22.25"):
    """A tip exactly as Phase 1 intake writes one with stated terms."""
    channel = _upstox(session)
    terms = stated_terms(symbol=symbol, direction="BUY", entry_low=Decimal(entry), entry_high=Decimal(entry),
                         target=Decimal(target), stop_loss=Decimal(stop), horizon_sessions=20)
    tip = Tip(
        public_id=str(uuid.uuid4()), source="Upstox", symbol=symbol, stock_id=_stock(session, symbol).id,
        direction="BUY", entry_price=Decimal(entry), target_price=Decimal(target), stop_loss=Decimal(stop),
        horizon_days=20, received_at=seen, comparison_status="RECEIVED", source_channel_id=channel.id,
        entry_low=Decimal(entry), entry_high=Decimal(entry), entry_basis=ENTRY_BASIS_STATED, horizon_sessions=20,
        horizon_basis=HORIZON_BASIS_STATED, first_seen_at=seen,
        match_key=match_key_v1(channel.canonical_channel_id, terms), match_key_version=MATCH_KEY_VERSION,
        parser_version="TCP-001", status=STATUS_ACTIVE, entry_status=ENTRY_WAITING,
    )
    session.add(tip)
    session.commit()
    return tip


def test_a_replay_tracks_only_the_tips_it_is_given(session):
    given = _ledger_tip(session, seen=ist(date(2026, 9, 20), 12))
    other = _ledger_tip(session, seen=ist(date(2026, 9, 20), 12), symbol="RELIANCE", entry="1300", target="1400",
                        stop="1200")
    _bar(session, MON, 23.7, 23.9, 23.5, 23.8)
    _bar(session, MON, 1305, 1310, 1295, 1302, symbol="RELIANCE")

    report = replay(session, [given.id], now=NOW, minute_bars=None)

    assert [(r.session_index, r.session_date) for r in _rows(session, given)] == [(1, MON)]
    assert (_rows(session, other), report.replay.examined) == ([], 1)


def test_a_tip_older_than_the_candle_store_is_data_unresolved_not_tracked_late(session):
    old = _ledger_tip(session, seen=ist(date(2026, 9, 10), 16))
    weekend = _ledger_tip(session, seen=ist(date(2026, 9, 19), 12), symbol="RELIANCE", entry="1300",
                          target="1400", stop="1200")
    for day in (MON, TUE):
        _bar(session, day, 23.7, 23.9, 23.5, 23.8)
        _bar(session, day, 1305, 1310, 1295, 1302, symbol="RELIANCE")

    report = replay(session, [old.id, weekend.id], now=NOW, minute_bars=None)

    assert (old.status, old.reason, old.outcome, aware(old.closed_at)) == (
        STATUS_DATA_UNRESOLVED, REASON_NO_HISTORY, None, ist(date(2026, 9, 10), 16),
    )
    assert _rows(session, old) == []
    assert (weekend.status, [r.session_date for r in _rows(session, weekend)]) == (STATUS_ACTIVE, [MON, TUE])
    assert report.no_history == 1
```

`itertools` is imported now for Task 3's `_legacy`.

- [ ] **Step 2: Run them to verify they fail**

Run: `python -m pytest tests/test_tip_backfill.py -v`
Expected: ERROR at collection with `ModuleNotFoundError: No module named 'app.tip_backfill'`.

- [ ] **Step 3: Let `track_tips` take a subset**

In `app/tip_tracking_job.py`:
- add `from collections.abc import Sequence` directly below `import statistics`;
- replace:

```python
def track_tips(session: Session, *, now: datetime, minute_bars: MinuteBarSource | None,
               provisional: bool = False) -> TrackingRun:
    """One pass over every ACTIVE ledger tip; legacy rows (no `match_key`) wait for the §12 backfill."""
    run = TrackingRun(basis=DATA_BASIS_PROVISIONAL if provisional else DATA_BASIS_FINAL)
    tips = session.scalars(
        select(Tip).where(Tip.status == STATUS_ACTIVE, Tip.match_key.is_not(None)).order_by(Tip.id)
    ).all()
```

with:

```python
def track_tips(session: Session, *, now: datetime, minute_bars: MinuteBarSource | None,
               provisional: bool = False, tip_ids: Sequence[int] | None = None) -> TrackingRun:
    """One pass over every ACTIVE ledger tip, or only `tip_ids` (the §12 backfill replays in batches)."""
    run = TrackingRun(basis=DATA_BASIS_PROVISIONAL if provisional else DATA_BASIS_FINAL)
    statement = select(Tip).where(Tip.status == STATUS_ACTIVE, Tip.match_key.is_not(None))
    if tip_ids is not None:
        statement = statement.where(Tip.id.in_(list(tip_ids)))
    tips = session.scalars(statement.order_by(Tip.id)).all()
```

The rest of the function (3a's locked re-read and 2a's per-tip loop) is unchanged.

- [ ] **Step 4: Create `app/tip_backfill.py` with the replay**

The import block is complete for Tasks 3–5, so they only append code.

```python
"""Tip-ledger spec §12: every call recorded before the ledger, brought into it once.

Legacy `tips` rows get a channel, their stated terms and a match_key, and each copy leaves its customer's receipt;
predictions recorded before Phase 2b become Marksy tips, a withdrawal the monitor recorded becoming their source exit.
Every tip this creates is replayed under §6 from `first_seen_at`. Idempotent: a second run finds nothing to do."""

from __future__ import annotations

import json
import uuid
from collections import Counter
from dataclasses import dataclass, field
from datetime import date, datetime, timedelta, timezone
from typing import Sequence

from sqlalchemy import func, select
from sqlalchemy.orm import Session

from .market_calendar import count_trading_days
from .market_data.bar_finality import final_bars_only
from .market_data.freshness import session_date_of
from .market_data.quality import NSE_TIMEZONE
from .marksy_tips import link_revision, register_prediction, tip_for_prediction, withdraw_prediction
from .models import (
    ApiKey,
    ChannelAlias,
    MarketPrice,
    Prediction,
    PredictionOutcomeEvent,
    RecommendationRevision,
    Stock,
    Tip,
    TipReceipt,
)
from .prediction_outcome_monitor import (
    MONITOR_RULE_VERSION,
    STATE_INVALIDATED,
    get_terminal_event,
    sync_prediction_from_tip,
)
from .tip_ledger import _lock_match_key, _reparse_match, normalize_alias, resolve_channel
from .tip_matching import MATCH_KEY_VERSION, intake_rejection, match_key_v1, stated_terms
from .tip_minute_bars import MinuteBarSource
from .tip_text_cleaning import clean_tip_text
from .tip_tracking_job import MAX_FAILED_TIP_IDS, TrackingRun, track_tips
from .tip_vocabulary import (
    DATA_BASIS_FINAL,
    ENTRY_BASIS_FIRST_SEEN_PRICE,
    ENTRY_BASIS_STATED,
    ENTRY_ENTERED,
    ENTRY_WAITING,
    HORIZON_BASIS_CHANNEL_DEFAULT,
    HORIZON_BASIS_STATED,
    KIND_REVISION,
    KIND_TIP,
    LEGACY_UNKNOWN_USER_ID,
    MEDIUM_APP_NOTIFICATION,
    MEDIUM_MANUAL,
    PARSER_APP_PAYLOAD,
    PARSER_MARKSY_TIPS_REPORT,
    REASON_NO_HISTORY,
    STATUS_ACTIVE,
    STATUS_DATA_UNRESOLVED,
    STATUS_MERGED_DUPLICATE,
)

DEFAULT_BATCH_SIZE = 200
BACKFILL_EVENT_PREFIX = "backfill:"
MAX_REPORTED_FAILURES = 5
_TEXT_MAX = 128


def _aware(value: datetime) -> datetime:
    # SQLite drops tzinfo on a DateTime(timezone=True) round-trip.
    return value if value.tzinfo is not None else value.replace(tzinfo=timezone.utc)


@dataclass
class BackfillReport:
    legacy_completed: int = 0
    legacy_collapsed: int = 0
    legacy_rejected: int = 0
    receipts_created: int = 0
    receipts_moved: int = 0
    predictions_registered: int = 0
    predictions_following: int = 0
    predictions_rejected: int = 0
    withdrawals: int = 0
    revisions_linked: int = 0
    no_history: int = 0
    failures: list[str] = field(default_factory=list)
    replay: TrackingRun = field(default_factory=lambda: TrackingRun(basis=DATA_BASIS_FINAL))

    def summary(self) -> dict:
        # The replay's keys stay at the top: the TIP_TRACKING verifier reads them off this run as off any other.
        return {
            **self.replay.summary(),
            "backfill": {
                "legacyCompleted": self.legacy_completed,
                "legacyCollapsed": self.legacy_collapsed,
                "legacyRejected": self.legacy_rejected,
                "receiptsCreated": self.receipts_created,
                "receiptsMoved": self.receipts_moved,
                "predictionsRegistered": self.predictions_registered,
                "predictionsFollowing": self.predictions_following,
                "predictionsRejected": self.predictions_rejected,
                "withdrawals": self.withdrawals,
                "revisionsLinked": self.revisions_linked,
                "noHistory": self.no_history,
                "failed": len(self.failures),
                "failures": self.failures[:MAX_REPORTED_FAILURES],
            },
        }


def _add_run(total: TrackingRun, run: TrackingRun) -> None:
    total.examined += run.examined
    total.progress_rows += run.progress_rows
    total.waiting += run.waiting
    total.failed += run.failed
    total.minute_bars_unavailable += run.minute_bars_unavailable
    for status, count in run.closed.items():
        total.closed[status] = total.closed.get(status, 0) + count
    room = max(MAX_FAILED_TIP_IDS - len(total.failed_tip_ids), 0)
    total.failed_tip_ids.extend(run.failed_tip_ids[:room])


def first_store_session(session: Session) -> date | None:
    first = session.scalar(select(func.min(MarketPrice.timestamp)).where(final_bars_only()))
    return None if first is None else session_date_of(_aware(first))


def close_without_history(session: Session, tip_ids: Sequence[int], *, now: datetime) -> int:
    """§6.7 "Replay and backfill never start tracking late": a tip first seen a trading day or more before the candle
    store's first official bar has no history to replay, so it is DATA_UNRESOLVED, never tracked from later on."""
    first = first_store_session(session)
    if first is None or not tip_ids:
        return 0
    closed = 0
    for tip in session.scalars(select(Tip).where(Tip.id.in_(list(tip_ids)), Tip.status == STATUS_ACTIVE)).all():
        seen = _aware(tip.first_seen_at)
        seen_on = seen.astimezone(NSE_TIMEZONE).date()
        if seen_on >= first or count_trading_days(session, "NSE", seen_on + timedelta(days=1), first) == 0:
            continue
        tip.status, tip.reason, tip.closed_at = STATUS_DATA_UNRESOLVED, REASON_NO_HISTORY, seen
        if tip.prediction_id is not None:
            sync_prediction_from_tip(session, tip, now=now)
        closed += 1
    session.commit()
    return closed


def replay(session: Session, tip_ids: Sequence[int], *, now: datetime, minute_bars: MinuteBarSource | None,
           batch_size: int = DEFAULT_BATCH_SIZE, report: BackfillReport | None = None) -> BackfillReport:
    """The tracker over these tips only, in batches, on official bars (§6.7); it resumes, so a rerun changes nothing."""
    report = report if report is not None else BackfillReport()
    ids = sorted(set(tip_ids))
    report.no_history += close_without_history(session, ids, now=now)
    for start in range(0, len(ids), batch_size):
        run = track_tips(session, now=now, minute_bars=minute_bars, tip_ids=ids[start:start + batch_size])
        _add_run(report.replay, run)
    return report
```

- [ ] **Step 5: Run the tests**

Run: `python -m pytest tests/test_tip_backfill.py tests/test_tip_tracking_job.py tests/test_run_tip_tracker.py -v`
Expected: all PASS. The existing callers of `track_tips` pass no `tip_ids`, and their behaviour is unchanged.

- [ ] **Step 6: Commit**

```bash
git add app/tip_tracking_job.py app/tip_backfill.py tests/test_tip_backfill.py
git commit -m "Tip ledger backfill: replay chosen tips in batches; a tip older than the candle store is DATA_UNRESOLVED

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 3: Legacy rows — channel, stated terms, key, collapse and receipts

**Files:**
- Modify: `app/tip_backfill.py` (append)
- Test: `tests/test_tip_backfill.py` (append)

**Interfaces:**
- Consumes:
  - `resolve_channel`, `_lock_match_key` and `_reparse_match` (3a, `app/tip_ledger.py`);
  - `stated_terms`, `match_key_v1` and `intake_rejection`;
  - `clean_tip_text`;
  - Task 2's `replay`.
- Produces:
  - `legacy_medium(row: Tip) -> str`
  - `receipt_customer(session, client_name: str | None) -> str`
  - `backfill_legacy_row(session, row, *, now, minute_bars, report) -> None`, which commits the row.
  - `backfill_legacy_tips(session, *, now, minute_bars, report) -> list[int]`: the ids of the rows that became ACTIVE, for the replay.

**Rules this task implements:** decisions 2–6.

- [ ] **Step 1: Write the failing tests**

Append to `tests/test_tip_backfill.py`:
- add `ApiKey` and `TipReceipt` to its `from app.models import (...)` line;
- change `from app.tip_backfill import replay` to `from app.tip_backfill import BackfillReport, backfill_legacy_tips, replay`;
- add these to its `from app.tip_vocabulary import (...)` block: `CHANNEL_NEWS_PORTAL`, `HORIZON_BASIS_CHANNEL_DEFAULT`, `LEGACY_UNKNOWN_USER_ID`, `MEDIUM_MANUAL`, `PARSER_MARKSY_TIPS_REPORT`, `STATUS_MERGED_DUPLICATE` and `STATUS_TARGET_HIT`.

```python
_legacy_numbers = itertools.count(1)


def _legacy(session, *, received, direction="BUY", source="Upstox", client_name=None, payload=None, symbol="RENUKA"):
    """A row as EPIC-803 intake stored it before the ledger, already scrubbed by migration 0181."""
    number = next(_legacy_numbers)
    row = Tip(
        public_id=f"legacy-{number}", source=source, client_name=client_name, source_reference=f"ref-{number}",
        symbol=symbol, stock_id=_stock(session, symbol).id, direction=direction, entry_price=Decimal("23.62"),
        target_price=Decimal("26"), stop_loss=Decimal("22.25"), horizon_days=None,
        raw_payload=payload if payload is not None else {"sourcePackage": "com.upstox.pro", "body": "BUY RENUKA 23.62"},
        received_at=received, comparison_status="COMPARED",
    )
    session.add(row)
    session.commit()
    return row


def _backfill_legacy(session):
    report = BackfillReport()
    backfill_legacy_tips(session, now=NOW, minute_bars=None, report=report)
    return report


def _holdings(session):
    return session.execute(
        select(TipReceipt.tip_id, TipReceipt.user_id, TipReceipt.medium).order_by(TipReceipt.id)
    ).tuples().all()


def test_legacy_copies_of_one_open_call_collapse_into_the_first_and_each_leaves_a_receipt(session):
    session.add(ApiKey(key_hash="h" * 64, client_name="upstox-bridge", scopes=["marksy"], bound_user_id="user-9"))
    first = _legacy(session, received=ist(MON, 10))
    again = _legacy(session, received=ist(TUE, 11))
    bridged = _legacy(session, received=ist(WED, 12), client_name="upstox-bridge")
    for day in (MON, TUE, WED):
        _bar(session, day, 23.7, 23.9, 23.5, 23.8)

    report = _backfill_legacy(session)

    assert (first.status, aware(first.first_seen_at), first.source_channel_id) == (
        STATUS_ACTIVE, ist(MON, 10), _upstox(session).id,
    )
    assert [(row.status, row.merged_into_tip_id, row.match_key) for row in (again, bridged)] == [
        (STATUS_MERGED_DUPLICATE, first.id, first.match_key),
    ] * 2
    assert _holdings(session) == [
        (first.id, LEGACY_UNKNOWN_USER_ID, MEDIUM_APP_NOTIFICATION),
        (again.id, LEGACY_UNKNOWN_USER_ID, MEDIUM_APP_NOTIFICATION),
        (first.id, "user-9", MEDIUM_APP_NOTIFICATION),
    ]
    assert (report.legacy_completed, report.legacy_collapsed, report.receipts_created) == (3, 2, 3)


def test_a_later_identical_call_after_the_first_closed_is_a_new_tip(session):
    first = _legacy(session, received=ist(MON, 16))
    later = _legacy(session, received=ist(FRI, 10))
    _bar(session, TUE, 23.7, 24, 23.5, 23.9)
    _bar(session, WED, 24, 26.2, 23.9, 26.1)
    _bar(session, FRI, 23.7, 23.9, 23.5, 23.8)

    _backfill_legacy(session)

    assert (first.status, later.status, later.merged_into_tip_id, later.match_key) == (
        STATUS_TARGET_HIT, STATUS_ACTIVE, None, first.match_key,
    )
    assert _holdings(session) == [
        (first.id, LEGACY_UNKNOWN_USER_ID, MEDIUM_APP_NOTIFICATION),
        (later.id, LEGACY_UNKNOWN_USER_ID, MEDIUM_APP_NOTIFICATION),
    ]


def test_a_pasted_long_call_is_a_manual_receipt_on_its_own_channel_and_reads_as_a_buy(session):
    pasted = _legacy(session, received=ist(MON, 9), direction="LONG", source="AiTradingAgent",
                     payload={"symbol": "RENUKA", "source": "AiTradingAgent", "entryLow": "23.50", "target2": "27"})

    _backfill_legacy(session)

    channel = session.get(Channel, pasted.source_channel_id)
    alias = session.scalar(select(ChannelAlias).where(ChannelAlias.channel_id == channel.id))
    receipt = session.scalar(select(TipReceipt))
    assert (pasted.direction, pasted.parser_version, pasted.status) == ("BUY", PARSER_MARKSY_TIPS_REPORT, STATUS_ACTIVE)
    assert (pasted.horizon_sessions, pasted.horizon_basis) == (20, HORIZON_BASIS_CHANNEL_DEFAULT)
    assert (channel.name, channel.type, alias.alias, alias.scope) == (
        "AiTradingAgent", CHANNEL_NEWS_PORTAL, "aitradingagent", MEDIUM_MANUAL,
    )
    assert pasted.match_key == match_key_v1(channel.canonical_channel_id, stated_terms(
        symbol="RENUKA", direction="BUY", entry_low=Decimal("23.62"), entry_high=Decimal("23.62"),
        target=Decimal("26"), stop_loss=Decimal("22.25"), horizon_sessions=None,
    ))
    assert (receipt.medium, receipt.user_id, receipt.channel_label, receipt.parser_version) == (
        MEDIUM_MANUAL, LEGACY_UNKNOWN_USER_ID, "AiTradingAgent", PARSER_MARKSY_TIPS_REPORT,
    )
```

How the first two tests replay:
- In the first test, `first` is seen during market hours, so session 0 (MON) is skipped without minute bars. It enters on TUE (low 23.5 ≤ 23.62).
  - Settling it before matching `again` and `bridged` leaves it ACTIVE, so both copies join it.
- In the second test, `first` enters on TUE and hits 26 on WED.
  - Only settle, replaying `first` before `later` is matched, can know that it closed before FRI.
  - Nothing else in `backfill_legacy_tips` replays.

- [ ] **Step 2: Run them to verify they fail**

Run: `python -m pytest tests/test_tip_backfill.py -v`
Expected: ERROR at collection with `ImportError: cannot import name 'backfill_legacy_tips' from 'app.tip_backfill'`.

- [ ] **Step 3: Append the legacy-row rules**

Append to `app/tip_backfill.py`:

```python
def legacy_medium(row: Tip) -> str:
    """The path the row came in on, as live intake files it today: `POST /tips` from the app (its package) or from an
    API client (`client_name`) is APP_NOTIFICATION; the admin paste carries neither and is MANUAL."""
    payload = row.raw_payload if isinstance(row.raw_payload, dict) else {}
    return MEDIUM_APP_NOTIFICATION if row.client_name or payload.get("sourcePackage") else MEDIUM_MANUAL


def _package(row: Tip) -> str | None:
    payload = row.raw_payload if isinstance(row.raw_payload, dict) else {}
    package = payload.get("sourcePackage")
    return package if isinstance(package, str) and package.strip() else None


def receipt_customer(session: Session, client_name: str | None) -> str:
    """§12: the API key's bound user when every key of the row's client names the same one, else legacy-unknown."""
    if not client_name:
        return LEGACY_UNKNOWN_USER_ID
    bound = set(session.scalars(
        select(ApiKey.bound_user_id).where(ApiKey.client_name == client_name, ApiKey.bound_user_id.is_not(None))
    ))
    return bound.pop() if len(bound) == 1 else LEGACY_UNKNOWN_USER_ID


def _receipt_text(row: Tip, medium: str) -> str:
    # 0181 already scrubbed these; cleaning again is idempotent and matches what live intake stores.
    payload = row.raw_payload if isinstance(row.raw_payload, dict) else {}
    if medium == MEDIUM_MANUAL:
        return clean_tip_text(json.dumps(payload, sort_keys=True), username=None)
    body = payload.get("body") if isinstance(payload.get("body"), str) else None
    return clean_tip_text(body or row.rationale or "", username=None)


def _holds(session: Session, tip_id: int, user_id: str, medium: str) -> bool:
    return session.scalar(
        select(TipReceipt.id).where(
            TipReceipt.tip_id == tip_id, TipReceipt.user_id == user_id, TipReceipt.medium == medium,
            TipReceipt.kind.in_((KIND_TIP, KIND_REVISION)),
        )
    ) is not None


def _settle(session: Session, key: str, *, now: datetime, minute_bars: MinuteBarSource | None,
            report: BackfillReport) -> None:
    """Replays the key's open tip first, so "open when a later copy arrived" is its tracked state (§12)."""
    open_id = session.scalar(select(Tip.id).where(Tip.match_key == key, Tip.status == STATUS_ACTIVE))
    if open_id is not None:
        replay(session, [open_id], now=now, minute_bars=minute_bars, report=report)


def _canonical_at(session: Session, key: str, *, seen: datetime, horizon: int, now: datetime,
                  minute_bars: MinuteBarSource | None, report: BackfillReport) -> Tip | None:
    """The tip this call already was when first seen: 3a's re-parse rule, since a backfilled row is a late read too."""
    _settle(session, key, now=now, minute_bars=minute_bars, report=report)
    _lock_match_key(session, key)
    return _reparse_match(session, key, seen=seen, now=now, horizon=horizon)


def _legacy_receipt(session: Session, row: Tip, holder: Tip, *, medium: str, report: BackfillReport) -> None:
    """The row's own sighting as its customer's receipt. §4 allows one holding per customer, tip and medium, so a
    customer who already holds the call keeps it and this receipt stays on the copy, as a 3a merge leaves it."""
    user_id = receipt_customer(session, row.client_name)
    event_key = BACKFILL_EVENT_PREFIX + row.public_id
    recorded = session.scalar(
        select(TipReceipt.id).where(TipReceipt.user_id == user_id, TipReceipt.device_event_key == event_key)
    )
    if recorded is not None:
        return
    target = row if _holds(session, holder.id, user_id, medium) else holder
    if target.id == row.id and _holds(session, row.id, user_id, medium):
        return
    package = _package(row)
    session.add(TipReceipt(
        public_id=str(uuid.uuid4()), tip_id=target.id, user_id=user_id, device_event_key=event_key, kind=KIND_TIP,
        medium=medium, app_package=package[:_TEXT_MAX] if package else None, channel_label=row.source[:_TEXT_MAX],
        text=_receipt_text(row, medium), device_posted_at=row.tip_as_of, recorded_at=_aware(row.received_at),
        parser_version=row.parser_version,
    ))
    session.flush()
    report.receipts_created += 1


def _move_receipts(session: Session, row: Tip, holder: Tip, *, report: BackfillReport) -> None:
    """Receipts a copy gained after Phase 1 (a re-paste matched by source reference) follow it to the call it is."""
    if holder.id == row.id:
        return
    for receipt in session.scalars(select(TipReceipt).where(TipReceipt.tip_id == row.id)).all():
        if receipt.kind in (KIND_TIP, KIND_REVISION) and _holds(session, holder.id, receipt.user_id, receipt.medium):
            continue
        receipt.tip_id = holder.id
        session.flush()
        report.receipts_moved += 1


def backfill_legacy_row(session: Session, row: Tip, *, now: datetime, minute_bars: MinuteBarSource | None,
                        report: BackfillReport) -> None:
    """Completes one pre-ledger row as live intake would have recorded it on arrival (§12)."""
    medium = legacy_medium(row)
    channel = resolve_channel(session, medium=medium, app_package=_package(row), channel_label=row.source)
    terms = stated_terms(
        symbol=row.symbol, direction=row.direction, entry_low=row.entry_price, entry_high=row.entry_price,
        target=row.target_price, stop_loss=row.stop_loss, horizon_sessions=row.horizon_days,
    )
    key = match_key_v1(channel.canonical_channel_id, terms)
    seen = _aware(row.received_at)
    stated_horizon = terms.horizon_sessions is not None
    horizon = terms.horizon_sessions if stated_horizon else channel.default_horizon_sessions
    canonical = _canonical_at(session, key, seen=seen, horizon=horizon, now=now, minute_bars=minute_bars, report=report)
    stock_id = row.stock_id or session.scalar(select(Stock.id).where(Stock.symbol == terms.symbol))
    entry_stated = terms.entry_low is not None
    row.source_channel_id, row.stock_id = channel.id, stock_id
    if terms.direction is not None and row.direction != terms.direction:
        row.direction = terms.direction  # the guard allows exactly this respelling of a pre-ledger row
    row.entry_low, row.entry_high = terms.entry_low, terms.entry_high
    row.entry_basis = ENTRY_BASIS_STATED if entry_stated else ENTRY_BASIS_FIRST_SEEN_PRICE
    row.horizon_sessions = horizon
    row.horizon_basis = HORIZON_BASIS_STATED if stated_horizon else HORIZON_BASIS_CHANNEL_DEFAULT
    row.first_seen_at = seen
    row.match_key, row.match_key_version = key, MATCH_KEY_VERSION
    row.parser_version = PARSER_APP_PAYLOAD if medium == MEDIUM_APP_NOTIFICATION else PARSER_MARKSY_TIPS_REPORT
    holder = row
    if canonical is not None:
        row.status, row.merged_into_tip_id, row.closed_at = STATUS_MERGED_DUPLICATE, canonical.id, seen
        holder = canonical
        report.legacy_collapsed += 1
    else:
        rejection = intake_rejection(terms, symbol_resolved=stock_id is not None)
        if rejection is not None:
            row.status, row.reason = rejection
            row.closed_at = seen
            report.legacy_rejected += 1
        else:
            row.status = STATUS_ACTIVE
            row.entry_status = ENTRY_WAITING if entry_stated else ENTRY_ENTERED
    session.flush()
    _legacy_receipt(session, row, holder, medium=medium, report=report)
    _move_receipts(session, row, holder, report=report)
    session.commit()
    report.legacy_completed += 1


def backfill_legacy_tips(session: Session, *, now: datetime, minute_bars: MinuteBarSource | None,
                         report: BackfillReport) -> list[int]:
    """Every pre-ledger row (no match_key), oldest first; returns the rows that became ACTIVE tips, for the replay."""
    rows = session.scalars(
        select(Tip).where(Tip.match_key.is_(None), Tip.prediction_id.is_(None)).order_by(Tip.received_at, Tip.id)
    ).all()
    active: list[int] = []
    for row in rows:
        row_id = row.id
        try:
            backfill_legacy_row(session, row, now=now, minute_bars=minute_bars, report=report)
        except Exception as exc:  # noqa: BLE001 -- one bad row must not strand the rest; the report names it
            session.rollback()
            report.failures.append(f"tip {row_id}: {type(exc).__name__}: {exc}")
            continue
        if row.status == STATUS_ACTIVE:
            active.append(row_id)
    return active
```

- [ ] **Step 4: Run the tests**

Run: `python -m pytest tests/test_tip_backfill.py tests/test_tip_ledger_guard.py tests/test_tip_reparse.py -v`
Expected: all PASS.
- If the paste test raises `TipLedgerImmutableError` on `direction`, Task 1's respelling is missing. Fix the guard, never the backfill.
- If an `IntegrityError` names `uq_tip_receipt_tip_user_medium`, `_legacy_receipt` created a second holding. Re-read decision 5.

- [ ] **Step 5: Commit**

```bash
git add app/tip_backfill.py tests/test_tip_backfill.py
git commit -m "Tip ledger backfill: legacy rows get a channel, stated terms and a key; open copies collapse, each a receipt

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 4: Pre-ledger Marksy predictions — registration, withdrawals as exits, revision links

**Files:**
- Modify: `app/marksy_tips.py` (`register_prediction`)
- Modify: `app/tip_backfill.py` (append)
- Test: `tests/test_tip_backfill.py` (append)

**Interfaces:**
- Produces `register_prediction(session, prediction, *, canonical_for: Callable[[str, datetime, int], Tip | None] | None = None) -> Tip`.
  - Without `canonical_for`, behaviour is unchanged (2b's live match).
  - With it, `canonical_for(key, first_seen, horizon_sessions)` supplies the matchable tip and takes its own lock.
- Produces, in `app/tip_backfill.py`:
  - `backfill_prediction(session, prediction, *, now, minute_bars, report) -> Tip`, which commits.
  - `backfill_predictions(session, *, now, minute_bars, report) -> list[int]`: the ACTIVE tip ids, for the replay.
- Consumes: `withdraw_prediction`, `link_revision`, `tip_for_prediction`, `sync_prediction_from_tip`, `get_terminal_event` and `MONITOR_RULE_VERSION`.

**Rules this task implements:** decision 7.

- [ ] **Step 1: Write the failing tests**

Append to `tests/test_tip_backfill.py`:
- add `Prediction` and `PredictionOutcomeEvent` to its `from app.models import (...)` line;
- add `backfill_predictions` to its `from app.tip_backfill import (...)` line;
- add these imports:

```python
from app.marksy_tips import tip_for_prediction
from app.prediction_outcome_monitor import (
    MONITOR_RULE_VERSION,
    STATE_INVALIDATED,
    STATE_TARGET_HIT,
    TIP_SYNC_RULE_VERSION,
    get_event_history,
    get_terminal_event,
    record_invalidation,
)
```

- add `OUTCOME_FAILURE`, `REASON_TARGET_BEFORE_ENTRY`, `STATUS_INVALIDATED` and `STATUS_SOURCE_EXIT` to its `from app.tip_vocabulary import (...)` block.

```python
SCAN = ist(MON, 0)
PUBLISHED = ist(MON, 16, 30)


def _prediction(session, *, created_at=PUBLISHED, status="OPEN"):
    """A prediction recorded before Phase 2b: entry 100, target +5% (105), stop -3% (97), 5 sessions, no tip."""
    prediction = Prediction(
        stock_id=_stock(session, "RELIANCE").id, created_at=created_at, as_of_timestamp=SCAN,
        entry_price=Decimal("100"), horizon_days=5, target_return=Decimal("0.05"), stop_return=Decimal("-0.03"),
        predicted_probability=Decimal("0.7"), confidence=Decimal("0.8"), model_version="m1-baseline-1",
        feature_version="f1", consensus_contract_version="c1", horizon_selection_version="h1",
        scoring_contract_version="s1", opportunity_score=Decimal("60.00"), status=status,
    )
    session.add(prediction)
    session.commit()
    return prediction


def _backfill_predictions(session):
    report = BackfillReport()
    active = backfill_predictions(session, now=NOW, minute_bars=None, report=report)
    return replay(session, active, now=NOW, minute_bars=None, report=report)


def test_a_pre_ledger_withdrawal_of_a_losing_call_scores_a_failed_source_exit(session):
    prediction = _prediction(session)
    record_invalidation(session, prediction, at=ist(WED, 11), evidence={"trigger": "official_bar_confirmation"})
    _bar(session, TUE, 100.5, 101, 99.5, 100, symbol="RELIANCE")
    _bar(session, WED, 98, 99, 97.5, 98, symbol="RELIANCE")
    _bar(session, THU, 97.8, 98.5, 97.2, 98, symbol="RELIANCE")

    report = _backfill_predictions(session)

    tip = tip_for_prediction(session, prediction.id)
    assert (tip.status, tip.outcome, tip.exit_price, tip.actual_return, tip.closed_session) == (
        STATUS_SOURCE_EXIT, OUTCOME_FAILURE, Decimal("97.8"), Decimal("-0.022"), 3,
    )
    assert aware(tip.closed_at) == ist(WED, 11)
    event = get_terminal_event(session, prediction.id)
    assert (prediction.status, event.state, event.monitor_rule_version) == (
        "INVALIDATED", STATE_INVALIDATED, MONITOR_RULE_VERSION,
    )
    assert (report.withdrawals, len(get_event_history(session, prediction.id))) == (1, 1)


def test_an_already_closed_prediction_keeps_its_first_event_while_its_tip_is_scored_under_section_6(session):
    prediction = _prediction(session, status="EVALUATED")
    session.add(PredictionOutcomeEvent(
        prediction_id=prediction.id, state=STATE_TARGET_HIT, detected_at=ist(TUE, 18), observed_at=ist(TUE, 0),
        observed_price=Decimal("105"), provider="test", prediction_version="m1-baseline-1:TSL-001-unpublished",
        evidence={"trigger": "target_price"}, monitor_rule_version=MONITOR_RULE_VERSION,
    ))
    session.commit()
    _bar(session, TUE, 104, 106, 103, 105, symbol="RELIANCE")

    _backfill_predictions(session)

    tip = tip_for_prediction(session, prediction.id)
    assert (tip.status, tip.reason, tip.outcome) == (STATUS_INVALIDATED, REASON_TARGET_BEFORE_ENTRY, None)
    assert prediction.status == "EVALUATED"
    assert [event.state for event in get_event_history(session, prediction.id)] == [STATE_TARGET_HIT]


def test_an_open_pre_ledger_prediction_is_tracked_from_its_publish_time_and_closed_by_its_tip(session):
    prediction = _prediction(session)
    _bar(session, TUE, 100.5, 101, 99.5, 100, symbol="RELIANCE")
    _bar(session, WED, 101, 105.5, 100.5, 105.2, symbol="RELIANCE")

    report = _backfill_predictions(session)

    tip = tip_for_prediction(session, prediction.id)
    assert (tip.status, tip.exit_price, aware(tip.first_seen_at)) == (STATUS_TARGET_HIT, Decimal("105"), PUBLISHED)
    event = get_terminal_event(session, prediction.id)
    assert (prediction.status, event.state, event.monitor_rule_version) == (
        "EVALUATED", STATE_TARGET_HIT, TIP_SYNC_RULE_VERSION,
    )
    assert report.predictions_registered == 1
```

How the first test replays (2a decision 7):
- The prediction is seen MON 16:30 IST, so session 1 is TUE and it enters there.
- The WED 11:00 withdrawal is in-session. On daily bars the stop (97) is not touched and no price is stated, so WED stays ACTIVE.
- On THU the exit takes that session's open: 97.8 / 100 − 1 = −0.022, closed as session 3.

- [ ] **Step 2: Run them to verify they fail**

Run: `python -m pytest tests/test_tip_backfill.py -v`
Expected: ERROR at collection with `ImportError: cannot import name 'backfill_predictions'`.

- [ ] **Step 3: Let `register_prediction` take the backfill's matcher**

In `app/marksy_tips.py`:
- add `from typing import Callable` below `from decimal import ROUND_HALF_UP, Decimal`;
- change `def register_prediction(session: Session, prediction: Prediction) -> Tip:` to:

```python
def register_prediction(
    session: Session,
    prediction: Prediction,
    *,
    canonical_for: Callable[[str, datetime, int], Tip | None] | None = None,
) -> Tip:
```

- append one line to its docstring: `` `canonical_for(key, first_seen, horizon)` replaces the live match; the §12 backfill matches as of the call's time. ``
- replace:

```python
    key = match_key_v1(channel.canonical_channel_id, terms)
    _lock_match_key(session, key)
    canonical = find_matchable(session, key, now=first_seen)
```

with:

```python
    key = match_key_v1(channel.canonical_channel_id, terms)
    if canonical_for is None:
        _lock_match_key(session, key)
        canonical = find_matchable(session, key, now=first_seen)
    else:
        canonical = canonical_for(key, first_seen, terms.horizon_sessions)
```

If 2c edited these lines, apply the same change to the equivalent lines. Do not re-derive the rule.

- [ ] **Step 4: Append the prediction backfill**

Append to `app/tip_backfill.py`:

```python
def _withdrawal(session: Session, prediction_id: int) -> PredictionOutcomeEvent | None:
    """The monitor's own INVALIDATED event: Marksy withdrawing the call on decay or at the 08:30 confirmation (§7)."""
    event = get_terminal_event(session, prediction_id)
    if event is None or event.state != STATE_INVALIDATED or event.monitor_rule_version != MONITOR_RULE_VERSION:
        return None
    return event


def backfill_prediction(session: Session, prediction: Prediction, *, now: datetime,
                        minute_bars: MinuteBarSource | None, report: BackfillReport) -> Tip:
    """A pre-ledger prediction as its Marksy tip (2b's terms), matched as of its own time; a withdrawal the monitor
    recorded becomes the tip's source exit before any replay, so a losing withdrawn call still scores (§7)."""

    def canonical_for(key: str, seen: datetime, horizon: int) -> Tip | None:
        return _canonical_at(session, key, seen=seen, horizon=horizon, now=now, minute_bars=minute_bars, report=report)

    tip = register_prediction(session, prediction, canonical_for=canonical_for)
    report.predictions_registered += 1
    if tip.status == STATUS_MERGED_DUPLICATE:
        report.predictions_following += 1
    elif tip.status != STATUS_ACTIVE:
        report.predictions_rejected += 1
    withdrawn = _withdrawal(session, prediction.id)
    if withdrawn is not None and withdraw_prediction(session, prediction, at=_aware(withdrawn.detected_at)) is not None:
        report.withdrawals += 1
    if tip.status != STATUS_ACTIVE:
        sync_prediction_from_tip(session, tip, now=now)
    session.commit()
    return tip


def _link_revisions(session: Session) -> int:
    """Invariant 6 for calls revised before Phase 2b: the revised tip names the call it revises."""
    linked = 0
    for revision in session.scalars(select(RecommendationRevision).order_by(RecommendationRevision.id)).all():
        revised = tip_for_prediction(session, revision.revised_prediction_id)
        if revised is None or revised.revises_tip_id is not None:
            continue
        link_revision(
            session,
            session.get(Prediction, revision.previous_prediction_id),
            session.get(Prediction, revision.revised_prediction_id),
        )
        if revised.revises_tip_id is not None:
            linked += 1
    session.commit()
    return linked


def backfill_predictions(session: Session, *, now: datetime, minute_bars: MinuteBarSource | None,
                         report: BackfillReport) -> list[int]:
    """Every prediction without a tip (all recorded before Phase 2b), oldest first; returns the ACTIVE tips."""
    registered = select(Tip.prediction_id).where(Tip.prediction_id.is_not(None))
    predictions = session.scalars(
        select(Prediction).where(Prediction.id.not_in(registered)).order_by(Prediction.created_at, Prediction.id)
    ).all()
    active: list[int] = []
    for prediction in predictions:
        prediction_id = prediction.id
        try:
            tip = backfill_prediction(session, prediction, now=now, minute_bars=minute_bars, report=report)
        except Exception as exc:  # noqa: BLE001 -- one bad prediction must not strand the rest; the report names it
            session.rollback()
            report.failures.append(f"prediction {prediction_id}: {type(exc).__name__}: {exc}")
            continue
        if tip.status == STATUS_ACTIVE:
            active.append(tip.id)
    report.revisions_linked += _link_revisions(session)
    return active
```

- [ ] **Step 5: Run the tests**

Run: `python -m pytest tests/test_tip_backfill.py tests/test_marksy_prediction_tips.py tests/test_marksy_rating_tips.py -v`
Expected: all PASS. The 2b and 2c files show that `register_prediction`'s live path is unchanged.

- [ ] **Step 6: Commit**

```bash
git add app/marksy_tips.py app/tip_backfill.py tests/test_tip_backfill.py
git commit -m "Tip ledger backfill: pre-ledger predictions become Marksy tips; a recorded withdrawal is their source exit

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 5: `backfill_ledger`, the survey, and `scripts/backfill_tip_ledger.py`

**Files:**
- Modify: `app/tip_backfill.py` (append)
- Create: `scripts/backfill_tip_ledger.py`
- Test: create `tests/test_backfill_tip_ledger_script.py`

**Interfaces:**
- Produces `backfill_ledger(session, *, now, minute_bars, batch_size=DEFAULT_BATCH_SIZE) -> BackfillReport`: legacy rows, then predictions, then one batched replay.
- Produces `survey(session) -> dict`, read-only. Keys:
  - `legacyRows`, `legacyByMedium`, `legacyDirections`, `legacyCustomers` and `legacyNewChannels`;
  - `predictionsWithoutTip`, `maxPredictionIdWithoutTip` and `withdrawnWithoutTip`;
  - `firstStoreSession`.
- Produces `scripts.backfill_tip_ledger.main(argv=None, *, now=None, minute_bars=None) -> dict`.
  - Without `--apply` it returns `{"status": "survey", **survey}`.
  - With it, it returns `{"status": "ok", **report.summary()}`, run under a `TIP_TRACKING` claim with `trigger_type=MANUAL` and `trigger_source="backfill:<ISO now>"`.

- [ ] **Step 1: Write the failing test**

Create `tests/test_backfill_tip_ledger_script.py`:

```python
"""The §12 backfill entrypoint: a read-only survey by default, and an idempotent apply under the tracker's lock."""
from __future__ import annotations

from datetime import datetime, timezone
from decimal import Decimal

import pytest
from sqlalchemy import create_engine, select
from sqlalchemy.orm import sessionmaker

import scripts.backfill_tip_ledger as backfill_module
from app.db import Base
from app.models import Channel, ChannelAlias, OrchestrationExecution, Prediction, Stock, Tip, TipReceipt
from app.schedule_orchestration import OPERATION_TIP_TRACKING
from app.tip_vocabulary import ALIAS_PACKAGE, CHANNEL_BROKER_APP, STATUS_ACTIVE

NOW = datetime(2026, 10, 1, 5, 0, tzinfo=timezone.utc)
RECEIVED = datetime(2026, 9, 21, 5, 0, tzinfo=timezone.utc)


class NoMinutes:
    def minute_bars(self, instrument_key, session_date):
        return []


@pytest.fixture
def session_local(monkeypatch):
    engine = create_engine("sqlite:///:memory:")
    Base.metadata.create_all(engine)
    factory = sessionmaker(bind=engine, autoflush=False, expire_on_commit=False)
    monkeypatch.setattr(backfill_module, "SessionLocal", factory)
    with factory() as db:
        stock = Stock(symbol="RENUKA", exchange="NSE", is_active=True)
        upstox = Channel(name="Upstox", type=CHANNEL_BROKER_APP, default_horizon_sessions=20, capture_enabled=True)
        db.add_all([stock, upstox])
        db.flush()
        upstox.canonical_channel_id = upstox.id
        db.add(ChannelAlias(channel_id=upstox.id, alias="com.upstox.pro", kind=ALIAS_PACKAGE))
        db.add(Tip(
            public_id="legacy-1", source="Upstox", source_reference="ref-1", symbol="RENUKA", stock_id=stock.id,
            direction="BUY", entry_price=Decimal("23.62"), target_price=Decimal("26"), stop_loss=Decimal("22.25"),
            raw_payload={"sourcePackage": "com.upstox.pro", "body": "BUY RENUKA"}, received_at=RECEIVED,
            comparison_status="COMPARED",
        ))
        db.add(Prediction(
            stock_id=stock.id, created_at=RECEIVED, as_of_timestamp=RECEIVED, entry_price=Decimal("23.62"),
            horizon_days=5, target_return=Decimal("0.05"), stop_return=Decimal("-0.03"),
            predicted_probability=Decimal("0.7"), confidence=Decimal("0.8"), model_version="m1-baseline-1",
            feature_version="f1", consensus_contract_version="c1", horizon_selection_version="h1",
            scoring_contract_version="s1", opportunity_score=Decimal("60.00"), status="OPEN",
        ))
        db.commit()
    return factory


def test_the_survey_writes_nothing_and_an_apply_runs_once(session_local):
    survey = backfill_module.main([], now=NOW, minute_bars=NoMinutes())
    with session_local() as db:
        assert (db.scalar(select(Tip.status).where(Tip.public_id == "legacy-1")), db.scalar(select(TipReceipt.id))) == (
            None, None,
        )

    applied = backfill_module.main(["--apply"], now=NOW, minute_bars=NoMinutes())
    again = backfill_module.main(["--apply"], now=NOW.replace(minute=5), minute_bars=NoMinutes())

    assert (survey["status"], survey["legacyRows"], survey["legacyByMedium"], survey["predictionsWithoutTip"]) == (
        "survey", 1, {"APP_NOTIFICATION": 1}, 1,
    )
    assert (applied["status"], applied["tipsExamined"], applied["backfill"]["legacyCompleted"],
            applied["backfill"]["predictionsRegistered"], applied["backfill"]["receiptsCreated"]) == ("ok", 2, 1, 1, 1)
    assert (again["tipsExamined"], again["backfill"]["legacyCompleted"], again["backfill"]["predictionsRegistered"],
            again["backfill"]["receiptsCreated"]) == (0, 0, 0, 0)
    with session_local() as db:
        assert db.scalar(select(Tip.status).where(Tip.public_id == "legacy-1")) == STATUS_ACTIVE
        sources = db.scalars(
            select(OrchestrationExecution.trigger_source)
            .where(OrchestrationExecution.operation_name == OPERATION_TIP_TRACKING,
                   OrchestrationExecution.status == "COMPLETED")
            .order_by(OrchestrationExecution.id)
        ).all()
    assert sources == [f"backfill:{NOW.isoformat()}", f"backfill:{NOW.replace(minute=5).isoformat()}"]
```

- [ ] **Step 2: Run it to verify it fails**

Run: `python -m pytest tests/test_backfill_tip_ledger_script.py -v`
Expected: ERROR at collection with `ModuleNotFoundError: No module named 'scripts.backfill_tip_ledger'`.

- [ ] **Step 3: Append the orchestration and the survey**

Append to `app/tip_backfill.py`:

```python
def backfill_ledger(session: Session, *, now: datetime, minute_bars: MinuteBarSource | None,
                    batch_size: int = DEFAULT_BATCH_SIZE) -> BackfillReport:
    """The whole §12 backfill. Every step selects by state, so a rerun finds nothing; an interrupted run leaves only
    completed rows, whose tracking the scheduled pass resumes like any ACTIVE tip."""
    report = BackfillReport()
    active = backfill_legacy_tips(session, now=now, minute_bars=minute_bars, report=report)
    active += backfill_predictions(session, now=now, minute_bars=minute_bars, report=report)
    replay(session, active, now=now, minute_bars=minute_bars, batch_size=batch_size, report=report)
    return report


def _alias_known(session: Session, row: Tip) -> bool:
    names = [normalize_alias(value) for value in (_package(row), row.source) if value and value.strip()]
    return session.scalar(
        select(ChannelAlias.id).where(ChannelAlias.scope == legacy_medium(row), ChannelAlias.alias.in_(names)).limit(1)
    ) is not None


def survey(session: Session) -> dict:
    """What `backfill_ledger` would do, read-only; the operator checks it against production's known state first."""
    rows = session.scalars(select(Tip).where(Tip.match_key.is_(None), Tip.prediction_id.is_(None))).all()
    registered = select(Tip.prediction_id).where(Tip.prediction_id.is_not(None))
    untipped = session.scalars(select(Prediction.id).where(Prediction.id.not_in(registered))).all()
    first = first_store_session(session)
    return {
        "legacyRows": len(rows),
        "legacyByMedium": dict(sorted(Counter(legacy_medium(row) for row in rows).items())),
        "legacyDirections": dict(sorted(Counter(row.direction or "-" for row in rows).items())),
        "legacyCustomers": dict(sorted(Counter(receipt_customer(session, row.client_name) for row in rows).items())),
        "legacyNewChannels": sorted({row.source for row in rows if not _alias_known(session, row)}),
        "predictionsWithoutTip": len(untipped),
        "maxPredictionIdWithoutTip": max(untipped, default=None),
        "withdrawnWithoutTip": sum(1 for prediction_id in untipped if _withdrawal(session, prediction_id) is not None),
        "firstStoreSession": first.isoformat() if first is not None else None,
    }
```

- [ ] **Step 4: Write the script**

Create `scripts/backfill_tip_ledger.py`:

```python
"""Tip-ledger spec §12: the one-off backfill. Without --apply it only surveys; with it, it completes every pre-ledger
row, registers pre-ledger predictions and replays their tracking. Idempotent: rerun until it reports nothing to do."""
from __future__ import annotations

import argparse
import json
from datetime import datetime, timezone

from app.db import SessionLocal
from app.schedule_orchestration import (
    OPERATION_TIP_TRACKING,
    TRIGGER_MANUAL,
    acquire_execution,
    complete_execution,
    fail_execution,
)
from app.tip_backfill import DEFAULT_BATCH_SIZE, backfill_ledger, survey
from app.tip_minute_bars import UpstoxMinuteBars
from scripts.run_tip_tracker import _upstox_client


def main(argv: list[str] | None = None, *, now: datetime | None = None, minute_bars=None) -> dict:
    parser = argparse.ArgumentParser(description="Backfill the tip ledger (tip-ledger spec §12).")
    parser.add_argument("--apply", action="store_true", help="write; without it the run only surveys")
    parser.add_argument("--batch-size", type=int, default=DEFAULT_BATCH_SIZE)
    args = parser.parse_args(argv)
    now = now or datetime.now(timezone.utc)

    with SessionLocal() as session:
        if not args.apply:
            result = {"status": "survey", **survey(session)}
            print(json.dumps(result, indent=2, default=str))
            return result
        # The tracker's own lock: the scheduled pass never tracks a tip this run is still completing.
        claim = acquire_execution(
            session, operation_name=OPERATION_TIP_TRACKING, scope_key="GLOBAL", trigger_type=TRIGGER_MANUAL,
            trigger_source=f"backfill:{now.isoformat()}", triggered_at=now, requested_by="scripts.backfill_tip_ledger",
        )
        source = minute_bars if minute_bars is not None else UpstoxMinuteBars(lambda: _upstox_client(session, at=now))
        try:
            report = backfill_ledger(session, now=now, minute_bars=source, batch_size=args.batch_size)
        except BaseException as exc:  # noqa: BLE001 -- the only lock release on failure
            fail_execution(
                session, claim, started_at=now, failed_at=datetime.now(timezone.utc),
                failure_reason=f"{type(exc).__name__}: {exc}",
            )
            raise
        finally:
            close = getattr(source, "close", None)
            if close is not None:
                close()
        summary = report.summary()
        complete_execution(session, claim, started_at=now, completed_at=datetime.now(timezone.utc),
                           result_summary={"status": "ok", **summary})
    result = {"status": "ok", **summary}
    print(json.dumps(result, indent=2, default=str))
    return result


if __name__ == "__main__":
    main()
```

- [ ] **Step 5: Run the tests**

Run: `python -m pytest tests/test_backfill_tip_ledger_script.py tests/test_tip_backfill.py tests/test_run_tip_tracker.py tests/test_operation_verification_s7.py -v`
Expected: all PASS. The TIP_TRACKING verifier reads `tipsExamined` at the top of the backfill's summary exactly as it does for a scheduled run.

- [ ] **Step 6: Commit**

```bash
git add app/tip_backfill.py scripts/backfill_tip_ledger.py tests/test_backfill_tip_ledger_script.py
git commit -m "Tip ledger backfill: operator script, a read-only survey by default, apply under the tracker's lock

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 6: Retire the monitor's legacy price-check path

**Files:**
- Modify: `app/prediction_outcome_monitor.py`
- Test: modify `tests/test_prediction_outcome_monitor.py`, `tests/test_marksy_prediction_tips.py`, `tests/test_epic366_prediction_lifecycle.py` and `tests/test_reassessment_sweep_progress.py`

**Interfaces:**
- `evaluate_prediction_realtime(session, prediction, *, as_of, holiday_dates=frozenset())`: the signature is unchanged. It writes no price-based event any more.
  - A prediction without a tip returns None after the decay check.
  - A registered prediction follows its tip, as in 2b.
- Removed: `_evaluate_bars`, `_valid_ohlc`, `_Evaluation`, `STALE_BAR_GAP_DAYS` and the stale-data event.

**Rules this task implements:** decision 11.

- [ ] **Step 1: Write the failing test, and rewrite the tests whose subject this removes**

In `tests/test_prediction_outcome_monitor.py`:
- delete, because their subject is the removed price path:
  - `test_target_hit_detected_before_full_horizon_elapses`, `test_stop_loss_checked_before_target_on_same_bar` and `test_horizon_expires_when_no_hit_after_horizon_days_of_bars`;
  - `test_remains_active_mid_horizon_with_no_hit_and_fresh_data` and `test_stale_price_data_during_trading_session_is_flagged_not_silently_closed`;
  - `test_uses_published_absolute_prices_when_available`.
- replace `test_closure_is_idempotent_and_never_re_evaluates` and `test_event_rows_are_fully_immutable` with:

```python
def test_closure_is_idempotent_and_never_re_evaluates(session):
    stock = make_stock(session)
    prediction = make_prediction(session, stock)
    session.add(RecommendationEvidenceItem(
        prediction_id=prediction.id, evidence_category=EVIDENCE_CATEGORY_NEWS, status=STATUS_AVAILABLE, source="test",
        reference=None, evidence_timestamp=AS_OF - timedelta(hours=12), is_stale=False, snapshot_rule_version="EVS-001",
        captured_at=AS_OF,
    ))
    session.commit()

    first = evaluate_prediction_realtime(session, prediction, as_of=AS_OF + timedelta(days=3))
    second = evaluate_prediction_realtime(session, prediction, as_of=AS_OF + timedelta(days=4))

    assert second.id == first.id
    assert len(get_event_history(session, prediction.id)) == 1


def test_event_rows_are_fully_immutable(session):
    stock = make_stock(session)
    prediction = make_prediction(session, stock)
    event = record_invalidation(
        session, prediction, at=AS_OF + timedelta(days=1), evidence={"trigger": "official_bar_confirmation"}
    )

    event.observed_price = Decimal("999")
    with pytest.raises(PredictionOutcomeEventImmutableError):
        session.commit()
    session.rollback()


def test_a_prediction_without_a_tip_is_never_price_checked(session):
    """Phase 6: every prediction gets its tip at birth or from the §12 backfill, and the tracker owns price checks."""
    stock = make_stock(session)
    prediction = make_prediction(session, stock)
    add_bar(session, stock.id, day_offset=1, close=104, high=106)

    assert evaluate_prediction_realtime(session, prediction, as_of=AS_OF + timedelta(days=1)) is None
    assert get_event_history(session, prediction.id) == ()
```

- remove `STATE_DATA_UNRESOLVED`, `STATE_HORIZON_EXPIRED`, `STATE_STOP_LOSS_HIT` and `STATE_TARGET_HIT` from its `from app.prediction_outcome_monitor import (...)` block, and delete `from app.target_stop_loss import publish_recommendation`;
- change `make_prediction`'s docstring to `"""A prediction without a tip; the monitor never price-checks one (tip-ledger Phase 6)."""`.

In `tests/test_marksy_prediction_tips.py`, replace `test_the_monitor_checks_prices_only_for_predictions_without_a_tip` with:

```python
def test_the_monitor_never_checks_prices(session):
    """Phase 6: every prediction is a tip (at birth or by the §12 backfill), and the tracker owns its price checks."""
    legacy = _prediction(session, symbol="TCS")
    registered, _tip = _registered(session)
    _bar(session, TUE, 100.5, 106, 99.5, 105.5, symbol="TCS")
    _bar(session, TUE, 100.5, 106, 99.5, 105.5)

    assert evaluate_prediction_realtime(session, legacy, as_of=ist(TUE, 18)) is None
    assert evaluate_prediction_realtime(session, registered, as_of=ist(TUE, 18)) is None
    assert get_event_history(session, legacy.id) == get_event_history(session, registered.id) == ()
```

In `tests/test_epic366_prediction_lifecycle.py`:
- add `register_prediction` to its `from app.marksy_tips import (...)` block;
- change `from app.tip_vocabulary import STATUS_TARGET_HIT` to `from app.tip_vocabulary import STATUS_STOP_LOSS_HIT, STATUS_TARGET_HIT`;
- add this helper directly after `_observe`:

```python
def _closed_by_tracker(session, prediction, tip_status, price):
    """Tip-ledger Phase 6: the tracker owns price checks; close the call's tip on the next day's bar, as it would."""
    tip = register_prediction(session, prediction)
    at = datetime.combine(ISSUED_ON + timedelta(days=1), time(10), timezone.utc)
    tip.status, tip.closed_at, tip.exit_price = tip_status, at, price
    sync_prediction_from_tip(session, tip, now=at)
    session.commit()
```

- then add one line in each of these tests, directly after the `session.commit()` that follows its `ISSUED_ON + timedelta(days=1)` bar:

| test | line to add |
| --- | --- |
| `test_the_outcome_monitor_now_has_a_caller` | `_closed_by_tracker(session, prediction, STATUS_TARGET_HIT, Decimal("1050"))` |
| `test_a_stop_hit_resolves_through_the_canonical_monitor` | `_closed_by_tracker(session, prediction, STATUS_STOP_LOSS_HIT, Decimal("970"))` |
| `test_a_resolved_prediction_is_never_reassessed_again` | `_closed_by_tracker(session, prediction, STATUS_TARGET_HIT, Decimal("1050"))` |
| `test_the_reassessment_log_reconstructs_the_life_of_the_thesis` | `_closed_by_tracker(session, prediction, STATUS_TARGET_HIT, Decimal("1050"))` |
| `test_a_resolved_prediction_presents_as_its_terminal_state` | `_closed_by_tracker(session, prediction, STATUS_TARGET_HIT, Decimal("1050"))` |
| `test_a_stopped_prediction_presents_as_stop_hit` | `_closed_by_tracker(session, prediction, STATUS_STOP_LOSS_HIT, Decimal("970"))` |

In `tests/test_reassessment_sweep_progress.py`:
- add these imports: `from app.marksy_tips import register_prediction`, `from app.prediction_outcome_monitor import sync_prediction_from_tip` and `from app.tip_vocabulary import STATUS_TARGET_HIT`;
- add this helper after `_population`:

```python
def _closed_by_tracker(session, prediction, tip_status, price):
    """Tip-ledger Phase 6: the tracker owns price checks; close the call's tip as it would, then sync."""
    tip = register_prediction(session, prediction)
    tip.status, tip.closed_at, tip.exit_price = tip_status, AT, price
    sync_prediction_from_tip(session, tip, now=AT)
    session.commit()
```

- in `test_a_resolved_row_leaves_the_queue_entirely`, directly after the `session.commit()` that follows its `_bar(...)`, add `_closed_by_tracker(session, target_hit, STATUS_TARGET_HIT, ENTRY * (1 + target_hit.target_return))`.

These tests keep their subject (reassessment, presentation, the sweep). Only the call's resolution moves to the tip, as 2b's `test_an_unlinked_revision_still_resolves_through_the_outcome_monitor` already does.

- [ ] **Step 2: Run them to verify they fail**

Run: `python -m pytest tests/test_prediction_outcome_monitor.py tests/test_marksy_prediction_tips.py -v -k "never_price_checked or never_checks_prices"`
Expected: 2 FAIL. `evaluate_prediction_realtime` still returns a TARGET_HIT event for the tipless prediction.

- [ ] **Step 3: Remove the price path**

In `app/prediction_outcome_monitor.py`:
- insert as the docstring's first line, followed by a blank line: `Tip-ledger Phase 6: price checks belong to the tip tracker; this module keeps the event log, withdrawals and the tip sync.`
- delete the `STALE_BAR_GAP_DAYS = 3` constant with its four comment lines above it;
- delete `_valid_ohlc`, the `@dataclass class _Evaluation` and `_evaluate_bars`;
- delete the imports that only they used: `from dataclasses import dataclass`, `from .schedule_orchestration import is_trading_session` and `from .market_data.bar_finality import final_bars_only`. Change `from .models import MarketPrice, Prediction, PredictionOutcomeEvent, Tip` to `from .models import Prediction, PredictionOutcomeEvent, Tip`;
- replace the whole `evaluate_prediction_realtime` function with:

```python
def evaluate_prediction_realtime(
    session: Session, prediction: Prediction, *, as_of: datetime, holiday_dates: frozenset[date] = frozenset()
) -> PredictionOutcomeEvent | None:
    """Idempotent: the existing terminal event once the prediction is closed; an INVALIDATED event when Marksy
    withdraws it on material decay; otherwise whatever its tip's sync recorded. Prices are the tracker's (spec §7)."""
    existing_terminal = get_terminal_event(session, prediction.id)
    if existing_terminal is not None:
        return existing_terminal

    decay = assess_assumption_decay(session, prediction, evaluated_at=as_of)
    if decay.verdict == VERDICT_MATERIAL_DECAY and decay.invalidation_recommended:
        event_row = PredictionOutcomeEvent(
            prediction_id=prediction.id,
            state=STATE_INVALIDATED,
            detected_at=as_of,
            observed_at=decay.evaluated_at,
            observed_price=None,
            provider=None,
            prediction_version=f"{prediction.model_version}:{decay.decay_rule_version}",
            evidence={"trigger": "assumption_decay", "assumption_decay_assessment_id": decay.id, "decay_ratio": str(decay.decay_ratio)},
            monitor_rule_version=MONITOR_RULE_VERSION,
        )
        session.add(event_row)
        _close_invalidated(prediction)
        # tip-ledger spec §7: a withdrawal is a source exit, so the call is still scored.
        withdraw_prediction(session, prediction, at=as_of)
        session.commit()
        session.refresh(event_row)
        return event_row

    tip = tip_for_prediction(session, prediction.id)
    if tip is None:
        # Phase 6: a prediction gets its tip at birth or from the §12 backfill; nothing is price-checked here.
        return None
    # F1: self-heal a prediction whose canonical tip closed without syncing it (a registration raced the tracker).
    status_before = prediction.status
    sync_prediction_from_tip(session, tip, now=as_of)
    healed_event = get_terminal_event(session, prediction.id)
    if prediction.status != status_before or healed_event is not None:
        session.commit()
    return healed_event
```

`holiday_dates` stays in the signature because `prediction_reassessment.reassess_prediction` passes it.

- [ ] **Step 4: Run the affected tests**

Run:

```bash
python -m pytest tests/test_prediction_outcome_monitor.py tests/test_marksy_prediction_tips.py \
  tests/test_epic366_prediction_lifecycle.py tests/test_reassessment_sweep_progress.py tests/test_epic853_confirmation.py \
  tests/test_epic853_invalidated_on_next_run.py tests/test_epic795_revision_chain_closure.py \
  tests/test_prediction_observation_flow.py tests/test_tip_tracking_job.py tests/test_tip_backfill.py -v
```

Expected: all PASS.
- If a test outside the four edited files now fails because a tipless prediction no longer resolves, apply the same `_closed_by_tracker` pattern there. Never restore a price check.
- Compare each failure with `main` first (Global Constraints).

- [ ] **Step 5: Commit**

```bash
git add app/prediction_outcome_monitor.py tests/test_prediction_outcome_monitor.py tests/test_marksy_prediction_tips.py \
  tests/test_epic366_prediction_lifecycle.py tests/test_reassessment_sweep_progress.py
git commit -m "Tip ledger: the monitor's price checks retire; every prediction is tracked through its tip (spec 7)

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 7: `/predictions/active` and `/tracking/predictions` carry the tip

**Files:**
- Modify:
  - `api/services/ledger_tips.py` (append `prediction_ledger_views`)
  - `api/schemas/predictions_active.py` (`ActivePrediction.ledger`) and `api/schemas/tracking.py` (`TrackedPrediction.ledger`)
  - `api/services/predictions_active.py` (`list_active_predictions`, `get_active_prediction`) and `api/services/tracking.py` (`list_tracked_predictions`)
  - `docs/api/openapi.json`
- Test: `tests/test_api_predictions_active.py` (append)

**Interfaces:**
- Produces `prediction_ledger_views(session, prediction_ids: list[int]) -> dict[int, LedgerTipView]`. A MERGED_DUPLICATE follower maps to the tip it follows.
- Produces `ledger: LedgerTipView | None = None` on `ActivePrediction` (list and detail) and on `TrackedPrediction`. It is additive, so every existing field is unchanged.

- [ ] **Step 1: Write the failing test**

Append to `tests/test_api_predictions_active.py`. Add `STATE_INVALIDATED` to its `from app.prediction_outcome_monitor import (...)` line, and add:

```python
from app.marksy_tips import tip_for_prediction
from app.tip_vocabulary import OUTCOME_FAILURE, STATUS_SOURCE_EXIT
```

```python
def test_a_withdrawn_losing_call_carries_its_failed_exit_beside_the_monitor_state(client, session):
    """Tip-ledger Phase 6: the monitor records a withdrawal as INVALIDATED; the tip says what it cost (spec §7)."""
    prediction, _generation, _stock = _make_active_prediction(session, symbol="AAA")
    tip = tip_for_prediction(session, prediction.id)
    tip.status, tip.outcome, tip.actual_return, tip.closed_at = (
        STATUS_SOURCE_EXIT, OUTCOME_FAILURE, Decimal("-0.03"), AS_OF + timedelta(days=2),
    )
    _add_outcome_event(session, prediction, STATE_INVALIDATED)

    listed = client.get("/api/v1/predictions/active").json()["data"]
    detail = client.get(f"/api/v1/predictions/active/{prediction.id}").json()["data"]

    for body in (listed[0], detail):
        assert (body["status"], body["ledger"]["status"], body["ledger"]["outcome"]) == (
            STATE_INVALIDATED, STATUS_SOURCE_EXIT, OUTCOME_FAILURE,
        )
        assert Decimal(body["ledger"]["actualReturn"]) == Decimal("-0.03")
```

`_make_active_prediction` goes through `record_recommendation`, so the prediction already has its Marksy tip (2b).

- [ ] **Step 2: Run it to verify it fails**

Run: `python -m pytest tests/test_api_predictions_active.py -v -k withdrawn_losing`
Expected: FAIL with `KeyError: 'ledger'`.

- [ ] **Step 3: Add the view, the fields and the wiring**

Append to `api/services/ledger_tips.py`:

```python
def prediction_ledger_views(session: Session, prediction_ids: list[int]) -> dict[int, LedgerTipView]:
    """Each prediction's call as the ledger tracks it; a MERGED_DUPLICATE follower reads the tip it follows (§7)."""
    if not prediction_ids:
        return {}
    own = {tip.prediction_id: tip for tip in session.scalars(select(Tip).where(Tip.prediction_id.in_(prediction_ids)))}
    followed = {
        tip.merged_into_tip_id for tip in own.values()
        if tip.status == STATUS_MERGED_DUPLICATE and tip.merged_into_tip_id is not None
    }
    canonical = (
        {tip.id: tip for tip in session.scalars(select(Tip).where(Tip.id.in_(followed)))} if followed else {}
    )
    tips = {
        prediction_id: canonical.get(tip.merged_into_tip_id, tip) if tip.status == STATUS_MERGED_DUPLICATE else tip
        for prediction_id, tip in own.items()
    }
    rows = _progress_rows(session, sorted({tip.id for tip in tips.values()}))
    directory = load_directory(session)
    return {prediction_id: ledger_view(directory, tip, rows[tip.id]) for prediction_id, tip in tips.items()}
```

In `api/schemas/predictions_active.py`, add `from .ledger import LedgerTipView` below `from .common import CursorMeta`, and append to `class ActivePrediction`, after `dataBasis`:

```python
    # Tip-ledger Phase 6: the call as the ledger tracks it (spec §7), so a withdrawn loss reads as a failed exit.
    ledger: LedgerTipView | None = None
```

In `api/schemas/tracking.py`, add `from .ledger import LedgerTipView` below `from .population import PopulationView`, and append to `class TrackedPrediction`, after `historicalExclusionReason`:

```python
    # Tip-ledger Phase 6: the call as the ledger tracks it (spec §7).
    ledger: LedgerTipView | None = None
```

In `api/services/predictions_active.py`:
- add `from .ledger_tips import prediction_ledger_views` below `from .keyset import decode_cursor, encode_cursor, keyset_predicate`;
- in `list_active_predictions`, directly after the `provenance = _provenance(...)` line, add `ledgers = prediction_ledger_views(session, prediction_ids)`, and pass `ledger=ledgers.get(m["prediction_id"]),` as the last `ActivePrediction(...)` argument, after `dataBasis=...`;
- in `get_active_prediction`, pass `ledger=prediction_ledger_views(session, [prediction.id]).get(prediction.id),` as the last `ActivePrediction(...)` argument.

In `api/services/tracking.py`:
- add `from .ledger_tips import prediction_ledger_views` to its relative imports;
- in `list_tracked_predictions`, directly after `rows = rows[:page_size]`, add `ledgers = prediction_ledger_views(session, [prediction.id for prediction, _symbol, _generation_id, _outcome in rows])`, and pass `ledger=ledgers.get(prediction.id),` to `TrackedPrediction(...)` after `**historical_exclusion_fields(session, prediction.id),`.

- [ ] **Step 4: Run the tests and regenerate the contract**

```bash
python -m pytest tests/test_api_predictions_active.py tests/test_api_tracking*.py tests/test_epic853_api_contract.py \
  tests/test_api_my_tips.py -v
python scripts/export_openapi.py
python -m pytest tests/test_openapi_contract_freshness.py -v
```

Expected: all PASS, apart from the known pre-existing `test_the_ipo_capability_is_still_false`, if it still fails on `main`.

- [ ] **Step 5: Commit**

```bash
git add api/services/ledger_tips.py api/schemas/predictions_active.py api/schemas/tracking.py \
  api/services/predictions_active.py api/services/tracking.py docs/api/openapi.json tests/test_api_predictions_active.py
git commit -m "Tip ledger: active and tracked predictions carry their tip, so a withdrawn loss reads as a failed exit

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 8: Retire the unread routes and the EPIC-845 outcome job

**Files:**
- Modify: `api/routers/tips.py`, `api/services/tips.py`, `api/schemas/tips.py` and `app/external_tip_scoring.py`
- Modify: `app/schedule_orchestration.py`, `app/operation_entrypoints.py`, `app/operation_recovery.py` and `deploy/k8s/base/kustomization.yaml`
- Delete: `app/external_tip_outcome.py`, `scripts/run_external_tip_outcomes.py`, `deploy/k8s/base/external-tip-outcomes-cronjob.yaml` and `tests/test_external_tip_outcome.py`
- Regenerate: `docs/api/openapi.json`
- Test: modify `tests/test_api_tips.py`, `tests/test_marksy_prediction_tips.py`, `tests/test_marksy_rating_tips.py`, `tests/test_external_tip_scoring.py`, `tests/test_operation_verification_s7.py` and `tests/test_api_operations_health.py`

**Interfaces:**
- **Removed routes:**
  - `GET /api/v1/tips`, which now answers 405 because `POST /tips` holds the path;
  - `GET /api/v1/tips/scorecard`, which now resolves as a tip id;
  - `GET /api/v1/tips/{tipId}/marksy-view`, which now answers 404.
- **Removed code:**
  - `TipView.outcome` and `TipView.headToHead`;
  - `TipQuery`, `TipPage`, `list_tips`, `tip_scorecard`, `SourceScorecardCard`, `TipListItem` and `source_scorecard`;
  - `OPERATION_EXTERNAL_TIP_OUTCOME`.
- **Kept (decision 10):**
  - `POST /tips`, `POST /tips/ingest-text` and `GET /tips/{id}`, with `comparison`, `marksyView`, `ledger` and `progress`;
  - the dashboard's `externalTips` block and `head_to_head_for_tip`.

- [ ] **Step 1: Point the tests at what stays, and delete the ones whose subject goes**

In `tests/test_api_tips.py`:
- **Delete these tests:**
  - the list tests: `test_the_list_filters_by_source_symbol_and_verdict`, `test_the_list_since_filter_excludes_older_tips`, `test_the_list_pages_with_a_cursor`, `test_a_tip_with_no_comparison_still_appears_in_the_list` and `test_an_exactly_full_last_page_reports_no_next_cursor`;
  - the retired route and field tests: `test_the_embedded_marksy_view_and_the_dedicated_endpoint_are_the_same_object`, `test_tip_scorecard_route_is_not_captured_as_a_tip_id` and `test_get_tip_includes_outcome_and_head_to_head`;
  - the helper `_resolve_outcome_row`, with its `# EPIC-845: outcome + head-to-head + scorecard surface.` comment. Only the deleted tests used it.
- **Point the remaining reads at the embedded view.** Replace every remaining `client.get(f"/api/v1/tips/{tip_id}/marksy-view", headers=api_key_headers).json()["data"]` (four of them) with `client.get(f"/api/v1/tips/{tip_id}", headers=api_key_headers).json()["data"]["marksyView"]`.
- In `test_the_marksy_view_of_an_advisory_tip_carries_its_numbers`, change `The dedicated marksy-view endpoint reads the numbers off the comparison` to `The embedded marksyView reads the numbers off the comparison`.

In `tests/test_marksy_prediction_tips.py`, delete `test_marksy_tips_are_not_listed_as_external_tips` and the `from api.services.tips import TipQuery, list_tips` line.

In `tests/test_marksy_rating_tips.py`, delete `test_rating_tips_are_not_listed_as_external_tips` and the `from api.services.tips import TipQuery, list_tips` line.

In `tests/test_external_tip_scoring.py`:
- delete `source_scorecard,` from the `from app.external_tip_scoring import (...)` block, and delete `test_source_scorecard_aggregates_multiple_rows_per_source`;
- replace `from app.external_tip_outcome import OUTCOME_DIRECTIONLESS` with `OUTCOME_DIRECTIONLESS = "DIRECTIONLESS"  # the retired EPIC-845 resolver's label; its rows keep it`, placed after the imports;
- delete `from decimal import Decimal` if nothing else in the file uses it.

In `tests/test_operation_verification_s7.py`, delete the `"EXTERNAL_TIP_OUTCOME",` entry and its three comment lines (`# EPIC-845: external-tip outcome measurement -- deliberately unowned for now, …`).

In `tests/test_api_operations_health.py`, change `assert len(without) == 8  # EPIC-845: EXTERNAL_TIP_OUTCOME joins the unowned set` to `assert len(without) == 7  # tip-ledger Phase 6: EXTERNAL_TIP_OUTCOME retired`.

```bash
git rm tests/test_external_tip_outcome.py
```

- [ ] **Step 2: Run them to verify they fail**

Run: `python -m pytest tests/test_api_operations_health.py tests/test_operation_verification_s7.py -v`
Expected: FAIL. The operation is still registered, so `without` has 8 entries, and the census names an operation that is not in its list.

- [ ] **Step 3: Remove the routes, the fields and the scorecard**

Rewrite the head of `api/routers/tips.py`:
- **Docstring.** Replace its first two lines with:

```python
"""POST /api/v1/tips, POST /api/v1/tips/ingest-text and GET /api/v1/tips/{tipId} (EPIC-803, tip-ledger spec §9) --
intake of stock tips from other sources, and one tip with Marksy's comparison and its ledger state.
```

  Keep the rest of the docstring.
- **Imports.**
  - Change `from fastapi import APIRouter, Depends, Query, Response, status` to `from fastapi import APIRouter, Depends, Response, status`.
  - Change `from ..envelope import cursor_paginated, success` to `from ..envelope import success`.
  - Delete `from ..pagination import DEFAULT_PAGE_SIZE, MAX_PAGE_SIZE`.
  - Change `from ..schemas.common import CursorEnvelope, SuccessEnvelope` to `from ..schemas.common import SuccessEnvelope`.
  - Replace the `from ..schemas.tips import (...)` block with `from ..schemas.tips import TipAccepted, TipIngestRequest, TipIngestResult, TipRequest, TipView`.
  - Replace the `from ..services.tips import ...` line with `from ..services.tips import get_tip, ingest_message, ingest_text, submit_tip`.
- **Routes.** Delete the functions `get_tips`, `get_tip_scorecard` and `get_tip_marksy_view`, with their decorators.

In `api/services/tips.py`:
- delete the `TipQuery` and `TipPage` dataclasses, `tip_scorecard` and `list_tips`;
- replace `get_tip` with:

```python
def get_tip(session: Session, tip_public_id: str) -> TipView:
    tip = _resolve_tip(session, tip_public_id)
    comparison = latest_comparison(session, tip.id)
    ledger, progress = tip_ledger_detail(session, tip)
    return TipView(
        tipId=tip.public_id,
        source=tip.source,
        sourceReference=tip.source_reference,
        symbol=tip.symbol,
        stockId=tip.stock_id,
        direction=tip.direction,
        entryPrice=tip.entry_price,
        targetPrice=tip.target_price,
        stopLoss=tip.stop_loss,
        horizonDays=tip.horizon_days,
        confidence=tip.confidence,
        rationale=tip.rationale,
        receivedAt=_as_aware_utc(tip.received_at),
        tipAsOf=_as_aware_utc(tip.tip_as_of),
        status=tip.comparison_status,
        comparison=_comparison_view(comparison),
        marksyView=get_marksy_view(session, tip.public_id),
        ledger=ledger,
        progress=progress,
    )
```

- remove `TipListItem,` from the `from ..schemas.tips import (...)` block, and delete `from ..pagination import DEFAULT_PAGE_SIZE, decode_offset_cursor, encode_offset_cursor`;
- delete `from dataclasses import dataclass` once `grep -n "@dataclass" api/services/tips.py` prints nothing.
- `_outcome_view`, `_head_to_head_view`, `_compared_tips`, `_external_tips_only` and `dashboard_tips` stay. The dashboard reads them.

In `api/schemas/tips.py`, delete `class SourceScorecardCard` and `class TipListItem`, and delete these two lines of `class TipView`:

```python
    outcome: TipOutcomeView | None = None
    headToHead: HeadToHeadView | None = None
```

`TipOutcomeView` and `HeadToHeadView` stay, because `api/schemas/dashboard.py` imports them.

In `app/external_tip_scoring.py`:
- delete `source_scorecard`, and the `defaultdict` and `Decimal` imports it alone used. Afterwards, `grep -nE "defaultdict|Decimal" app/external_tip_scoring.py` prints nothing;
- change the docstring's first line to `"""EPIC-845 B: score an external tip's realized result against Marksy's own.`, and delete the words `and aggregate each source's record.` that end its first sentence.

- [ ] **Step 4: Unregister the EPIC-845 outcome job**

- In `app/schedule_orchestration.py`, delete the line `OPERATION_EXTERNAL_TIP_OUTCOME = "EXTERNAL_TIP_OUTCOME"  # app.external_tip_outcome -- EPIC-845`. Also delete the whole `OPERATION_EXTERNAL_TIP_OUTCOME: TriggerPolicy(...)` entry of `TRIGGER_POLICIES`, through its closing `),`.
- In `app/operation_entrypoints.py`, delete `OPERATION_EXTERNAL_TIP_OUTCOME,` from the import and the `RunnableJob(operation_name=OPERATION_EXTERNAL_TIP_OUTCOME, …)` block.
- In `app/operation_recovery.py`, delete `OPERATION_EXTERNAL_TIP_OUTCOME,` from the import and the `OPERATION_EXTERNAL_TIP_OUTCOME: RecoveryPolicy(...)` entry.
- In `deploy/k8s/base/kustomization.yaml`, delete the lines `  # EPIC-845: external-tip outcome measurement, after ingest like ipo-outcomes.` and `  - external-tip-outcomes-cronjob.yaml`.

```bash
git rm app/external_tip_outcome.py scripts/run_external_tip_outcomes.py deploy/k8s/base/external-tip-outcomes-cronjob.yaml
grep -rnE 'OPERATION_EXTERNAL_TIP_OUTCOME|"EXTERNAL_TIP_OUTCOME"|external_tip_outcome import|run_external_tip_outcomes|external-tip-outcomes-cronjob|\blist_tips\b|\bTipQuery\b|get_tip_scorecard|def tip_scorecard|source_scorecard|SourceScorecardCard|TipListItem' \
  app api scripts deploy tests --include=*.py --include=*.yaml || echo "nothing references the retired code"
```

Expected: `nothing references the retired code`.
- The word boundaries keep 3a's `AdminTipQuery` and 3b's `list_my_tips` and `app/tip_scorecard*.py` out of the match.
- The model `ExternalTipOutcome` and its table name stay, because the dashboard still reads them, and the pattern does not match either.

- [ ] **Step 5: Run the tip, dashboard and operations tests, and regenerate the contract**

```bash
python scripts/export_openapi.py
python -m pytest tests/test_api_tips.py tests/test_api_tips_ingest.py tests/test_api_tips_ledger.py tests/test_api_my_tips.py \
  tests/test_external_tip_scoring.py tests/test_marksy_prediction_tips.py tests/test_marksy_rating_tips.py \
  tests/test_openapi_contract_freshness.py tests/test_security_authorization.py tests/test_epic331_authentication_coverage.py \
  tests/test_operation_verification_s7.py tests/test_operation_verification.py tests/test_operation_recovery.py \
  tests/test_operation_entrypoints.py tests/test_operation_dependencies.py tests/test_recovery_cadence_contract.py \
  tests/test_recovery_sweep.py tests/test_schedule_orchestration.py tests/test_schedule_registry.py \
  tests/test_scheduler_producer_inventory.py tests/test_cronjob_manifests.py tests/test_api_operations.py \
  tests/test_api_operations_health.py tests/test_operations_health.py tests/test_operations_metrics.py \
  tests/test_job_eligibility.py tests/test_epic317_regressions.py tests/test_market_day_audit.py \
  tests/test_operational_checkpoint.py -v
```

Expected: all PASS, apart from failures that also occur on `main` (Global Constraints).

These files pin the operation, CronJob and producer inventories. When one fails, read its assertion:
- If it pins a count or a named set that held EXTERNAL_TIP_OUTCOME (for example `test_coverage_is_eighteen_of_twentyfive_and_stated`), remove the entry or decrement the count in that file's style, with the comment `# tip-ledger Phase 6: EXTERNAL_TIP_OUTCOME retired`.
- If it pins a route list that held a removed path, remove that path.
- Never re-add the operation.

- [ ] **Step 6: Commit**

```bash
git add api/routers/tips.py api/services/tips.py api/schemas/tips.py app/external_tip_scoring.py \
  app/schedule_orchestration.py app/operation_entrypoints.py app/operation_recovery.py deploy/k8s/base/kustomization.yaml \
  docs/api/openapi.json tests/test_api_tips.py tests/test_marksy_prediction_tips.py tests/test_marksy_rating_tips.py \
  tests/test_external_tip_scoring.py tests/test_operation_verification_s7.py tests/test_api_operations_health.py
git add -u app scripts deploy tests
git commit -m "Tip ledger: retire GET /tips, /tips/scorecard, /tips/{id}/marksy-view and the EPIC-845 outcome job (spec 9)

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

Run `git status` first. Check that `git add -u` staged only this task's edits and the four `git rm` deletions.

---

### Task 9: Regression set, PR, merge, deploy, run the backfill, verify production

**Files:** none new.

- [ ] **Step 1: Run the regression set**

```bash
rm -f "$TEMP/marksy-pytest-default.db"
python -m pytest tests/test_tip_backfill.py tests/test_backfill_tip_ledger_script.py tests/test_tip_ledger_guard.py \
  tests/test_tip_ledger.py tests/test_tip_ledger_migration.py tests/test_tip_matching.py tests/test_tip_text_cleaning.py \
  tests/test_tip_tracker.py tests/test_tip_tracking_job.py tests/test_run_tip_tracker.py tests/test_tip_reparse.py \
  tests/test_tip_ledger_admin.py tests/test_api_admin_tip_ledger.py tests/test_api_my_tips.py \
  tests/test_marksy_prediction_tips.py tests/test_marksy_rating_tips.py tests/test_prediction_outcome_monitor.py \
  tests/test_epic366_prediction_lifecycle.py tests/test_reassessment_sweep_progress.py tests/test_epic853_confirmation.py \
  tests/test_epic853_invalidated_on_next_run.py tests/test_api_predictions_active.py tests/test_api_tips*.py \
  tests/test_external_tip_ingest.py tests/test_external_tip_scoring.py tests/test_openapi_contract_freshness.py \
  tests/test_alembic_single_head.py -v
python -m alembic heads
```

Expected: all PASS, apart from failures that also occur on `main` (Global Constraints). There is one head, `0185_rating_history`.

- [ ] **Step 2: Re-check the log gate, push and open the PR**

Rerun Task 1 Step 2's log command. Expected: `0`. Otherwise **stop**, and do not merge.

```bash
git push -u origin feat/tip-ledger-backfill-retire
gh pr create --title "Tip ledger Phase 6: section 12 backfill; retire unread tip routes, the EPIC-845 job and the monitor's price checks" --body "$(cat <<'EOF'
Brings every pre-ledger call into the ledger (tip-ledger spec section 12) and retires what no reader needs.

- `scripts/backfill_tip_ledger.py`: a read-only survey by default; `--apply` completes the 43 legacy rows (channel by
  source alias in the scope of their path, stated terms, match_key, one receipt each, open copies collapsed),
  registers pre-2b Marksy predictions (a recorded withdrawal becomes their source exit), and replays their tracking
  in batches under the TIP_TRACKING lock. Idempotent.
- Guard: a pre-ledger direction may be respelled to what it meant (LONG -> BUY), nothing else
- The monitor no longer checks prices; every prediction is tracked through its tip
- `/predictions/active[/{id}]` and `/tracking/predictions` carry `ledger`, so a withdrawn loss reads as a failed exit
- Removed: GET /tips, GET /tips/scorecard, GET /tips/{id}/marksy-view, TipView.outcome/headToHead, the EPIC-845
  outcome operation, CronJob, script and resolver. Kept for the ai-trading-agent: POST /tips and the comparison.
- No migration (head stays 0185_rating_history); no table dropped

Tests: backfill, script, guard, tracker, monitor, reassessment, predictions API, tips API, operations consistency set.

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```

- [ ] **Step 3: Merge and deploy, then delete the retired CronJob**

```bash
gh pr merge --merge --delete-branch
ssh marksy@103.178.160.233 'bash ~/deployment/server/vps-deploy.sh'
ssh marksy@103.178.160.233 'KUBECONFIG=~/.kube/config kubectl -n market-agent delete cronjob market-agent-external-tip-outcomes --ignore-not-found'
```

Expected: the deploy script finishes (it has no migration to apply). The delete prints `cronjob.batch "market-agent-external-tip-outcomes" deleted`, because `kubectl apply -k` never prunes a CronJob removed from the kustomization.

- [ ] **Step 4: Survey production — the gate before any write**

Run this between 10:00 and 15:30 IST, or after 17:00 IST, away from the 08:45 and 16:10 tracker slots.

```bash
ssh marksy@103.178.160.233 'KUBECONFIG=~/.kube/config kubectl -n market-agent exec deploy/market-agent-api -- python -m scripts.backfill_tip_ledger'
```

Expected:
- `"legacyRows": 43`, with `legacyByMedium` summing to 43;
- `maxPredictionIdWithoutTip` ≤ 2001, or null;
- `firstStoreSession` earlier than every legacy `received_at`;
- `legacyNewChannels` listing only sources the operator recognizes.

If `legacyRows` is not 43, or an untipped prediction id is above 2001, **stop and report**: production is not in the state this plan was written for.

- [ ] **Step 5: Apply, then apply again**

```bash
ssh marksy@103.178.160.233 'KUBECONFIG=~/.kube/config timeout 3600 kubectl -n market-agent exec deploy/market-agent-api -- python -m scripts.backfill_tip_ledger --apply'
ssh marksy@103.178.160.233 'KUBECONFIG=~/.kube/config kubectl -n market-agent exec deploy/market-agent-api -- python -m scripts.backfill_tip_ledger --apply'
```

Expected:
- The first run prints `"status": "ok"`, `"legacyCompleted": 43`, `"failed": 0` and `"tipsFailed": 0`.
- The second run prints `0` for `legacyCompleted`, `predictionsRegistered`, `receiptsCreated` and `tipsExamined`.
- If the first run is cut off (SSH drop or timeout), rerun it: completed rows are skipped (decision 6).
- If `failed` > 0, the report's `failures` name the rows. Investigate them one by one, and rerun once each is fixed. Never delete a row.

- [ ] **Step 6: Verify the ledger in production**

```bash
cat <<'SQL' | ssh marksy@103.178.160.233 'KUBECONFIG=~/.kube/config kubectl -n market-agent exec -i deploy/postgres -- sh -c '"'"'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB"'"'"''
select version_num from alembic_version;
select count(*) as legacy_left from tips where status is null or match_key is null;
select count(*) as untipped from predictions p where not exists (select 1 from tips t where t.prediction_id = p.id);
select count(*) as open_on_a_closed_tip from predictions p join tips t on t.prediction_id = p.id
  left join tips c on c.id = t.merged_into_tip_id
  where p.status = 'OPEN' and coalesce(c.status, t.status) <> 'ACTIVE';
select status, count(*) from tips where prediction_id is null and parser_version in ('APP-001', 'MTR-001')
  and first_seen_at < '2026-09-29' group by status order by status;
select count(*) as backfill_receipts from tip_receipts where device_event_key like 'backfill:%';
SQL
curl -s -o /dev/null -w "tips %{http_code}\n" https://marksy.indoulia.com/api/v1/tips
curl -s -o /dev/null -w "marksy-view %{http_code}\n" https://marksy.indoulia.com/api/v1/tips/abc/marksy-view
ssh marksy@103.178.160.233 'KUBECONFIG=~/.kube/config kubectl -n market-agent exec deploy/market-agent-api -- python -c "from app.db import SessionLocal; from api.services.predictions_active import list_active_predictions; s = SessionLocal(); print([(i.symbol, i.status, i.ledger.status if i.ledger else None) for i in list_active_predictions(s, page_size=10).items])"'
ssh marksy@103.178.160.233 'KUBECONFIG=~/.kube/config kubectl -n market-agent create job --from=cronjob/market-agent-tip-tracker tip-tracker-after-backfill && KUBECONFIG=~/.kube/config kubectl -n market-agent wait --for=condition=complete job/tip-tracker-after-backfill --timeout=900s; KUBECONFIG=~/.kube/config kubectl -n market-agent logs job/tip-tracker-after-backfill'
```

Expected:
- `alembic_version` is `0185_rating_history`.
- `legacy_left`, `untipped` and `open_on_a_closed_tip` are `0`. That is decision 11's gate: every OPEN prediction has a tip, so the monitor needs no price path.
- The status breakdown sums to at most 43; rows re-completed after Phase 1 by `source_reference` also count.
- `backfill_receipts` ≤ 43.
- `tips 405` and `marksy-view 404` (both were 401 before).
- Every active prediction prints a ledger status, never `None`.
- The tracker log prints `'tipsFailed': 0` and no traceback.

- [ ] **Step 7: Clean up the worktree**

```bash
cd /c/AIAgent/marksy-api && git worktree remove ../marksy-api-phase6 && git checkout main && git pull
```

---

## Part B — marksy-os: the Predictions tab, the Setups list and captured calls read the tip

### Task 10: DTOs, `LedgerCalls` wording, both views and the captured insight

**Files:**
- Modify (paths under `app/src/main/java/com/marksy/os/`):
  - `market/InstrumentModels.kt` and `market/PredictionModels.kt`
  - `market/LedgerCalls.kt`
  - `ui/PredictionsView.kt` and `ui/SetupsView.kt`
  - `gateway/MarksyTipsApiClient.kt`
- Test (under `app/src/test/java/com/marksy/os/`): `market/LedgerCallsTest.kt`, `gateway/MarksyTipsApiContractTest.kt`

**Interfaces:**
- Consumes 4b's `LedgerTipDto.parse` and `LedgerCalls.state`, `tone`, `returnText`, `progressText` and `isActive`, and Task 7's `ledger` JSON.
- Produces:
  - `ActivePredictionDto.ledger: LedgerTipDto? = null` and `ClosedPredictionDto.ledger: LedgerTipDto? = null`
  - `LedgerCalls.isLive(p: ActivePredictionDto): Boolean`
  - `LedgerCalls.endedLine(p: ActivePredictionDto): String?`
  - `LedgerCalls.lifecycleWord(p: ActivePredictionDto): String?`
  - `LedgerCalls.closedLabel(c: ClosedPredictionDto): String?` and `LedgerCalls.closedReturn(c: ClosedPredictionDto): Double?`
  - `internal fun ledgerInsight(eventId: Long, kind: String, tipId: String, data: JSONObject): MarksyInsight`, in `MarksyTipsApiClient.kt`

- [ ] **Step 1: Confirm Part A is live and create the worktree**

```bash
cd /c/AIAgent/marksy-os && git fetch origin
git worktree add ../marksy-os-phase6 -b feat/tip-ledger-retire-reads origin/main
cp /c/AIAgent/marksy-os/local.properties /c/AIAgent/marksy-os-phase6/local.properties
cd /c/AIAgent/marksy-os-phase6 && git log --oneline -1 && grep -n "fun isActive\|fun state\|fun progressText" app/src/main/java/com/marksy/os/market/LedgerCalls.kt
```

Expected:
- Task 9 Step 6 already printed a ledger status for every active prediction, so Part A is live.
- HEAD is the 4b merge or later, and `LedgerCalls.kt` shows 4b's three functions.
- All later Part B commands run in `C:\AIAgent\marksy-os-phase6`.

- [ ] **Step 2: Write the failing tests**

Append to `app/src/test/java/com/marksy/os/market/LedgerCallsTest.kt`, inside `class LedgerCallsTest`. Add `import org.junit.Assert.assertFalse` and `import org.junit.Assert.assertTrue` to its imports.

```kotlin
    // Spec §7 and Phase 6: the Predictions tab and Setups read the tip, so a withdrawn loss never reads "Invalidated".
    @Test
    fun aWithdrawnLosingPredictionReadsAsAFailedExitNotAnInvalidation() {
        val withdrawn = prediction("INVALIDATED", ledgerJson("SOURCE_EXIT", "FAILURE", "-0.030000"))
        val pending = prediction("INVALIDATED", ledgerJson("ACTIVE"))
        val closed = ClosedPredictionDto.parse(JSONObject(
            """{"id": 7, "symbol": "RENUKA", "outcome": "HORIZON_EXPIRED", "realizedReturn": "0.01",
               "ledger": ${ledgerJson("SOURCE_EXIT", "FAILURE", "-0.030000")}}"""
        ))

        assertFalse(LedgerCalls.isLive(withdrawn))
        assertEquals("Exited · Failed · -3.00%", LedgerCalls.endedLine(withdrawn))
        assertNull(LedgerCalls.lifecycleWord(withdrawn))
        assertEquals("Withdrawn · result pending", LedgerCalls.endedLine(pending))
        assertTrue(LedgerCalls.isLive(prediction("ACTIONABLE_NOW", ledgerJson("ACTIVE"))))
        assertFalse(LedgerCalls.isLive(prediction("ACTIONABLE_NOW", ledgerJson("TARGET_HIT", "SUCCESS", "0.050000"))))
        assertEquals("Exited · Failed", LedgerCalls.closedLabel(closed))
        assertEquals(-0.03, LedgerCalls.closedReturn(closed)!!, 1e-9)
        assertFalse(LedgerCalls.isLive(prediction("INVALIDATED", null)))
        assertEquals("INVALIDATED", LedgerCalls.lifecycleWord(prediction("INVALIDATED", null)))
    }

    private fun prediction(lifecycleState: String, ledger: String?) = ActivePredictionDto.parse(JSONObject(
        """{"predictionId": 7, "symbol": "RENUKA", "lifecycleState": "$lifecycleState", "ledger": ${ledger ?: "null"}}"""
    ))

    private fun ledgerJson(status: String, outcome: String? = null, actual: String? = null) =
        """{"tipId": "t-7", "symbol": "RENUKA", "direction": "BUY", "firstSeenAt": "2026-09-21T11:00:00Z",
           "status": "$status", "outcome": ${outcome?.let { "\"$it\"" } ?: "null"},
           "actualReturn": ${actual?.let { "\"$it\"" } ?: "null"}}"""
```

Append to `app/src/test/java/com/marksy/os/gateway/MarksyTipsApiContractTest.kt`, inside the class. Add `import org.junit.Assert.assertNull`.

```kotlin
    @Test
    fun aCapturedTipIsDescribedByItsLedgerStateNotByMarksysComparison() {
        val data = JSONObject(
            """{"comparison": {"verdict": "AGREE"}, "marksyView": {"recommendation": "POSITIVE_OPPORTUNITY"},
               "ledger": {"tipId": "t-1", "symbol": "RENUKA", "direction": "BUY", "firstSeenAt": "2026-09-29T04:00:00Z",
                          "status": "SOURCE_EXIT", "outcome": "FAILURE", "actualReturn": "-0.030000"}}"""
        )

        val insight = ledgerInsight(7L, "TIP", "t-1", data)

        assertEquals("Exited · Failed · -3.00%", insight.summary)
        assertEquals("TIP", insight.action)
        assertNull(insight.verdict)
        assertFalse(insight.rawResponseJson!!.contains("AGREE"))
    }
```

- [ ] **Step 3: Run them to verify they fail**

Run the Global Constraints command with `--tests 'com.marksy.os.market.LedgerCallsTest' --tests 'com.marksy.os.gateway.MarksyTipsApiContractTest'`.
Expected: FAIL at compile with `Unresolved reference 'isLive'`. `ledgerInsight` is unresolved too.

- [ ] **Step 4: Parse `ledger` on both prediction DTOs**

In `market/InstrumentModels.kt`, `ActivePredictionDto`:
- add `val ledger: LedgerTipDto? = null` as the last constructor parameter, after `val dataBasis: String? = null`;
- in `parse`, add `ledger = json.optJSONObject("ledger")?.let(LedgerTipDto::parse)` after `dataBasis = json.textOrNull("dataBasis")`. Put a comma on the line before it.

In `market/PredictionModels.kt`, `ClosedPredictionDto`:
- add `val ledger: LedgerTipDto? = null` as the last constructor parameter, after `val excludedReason: String?`;
- in `parse`, add `ledger = json.optJSONObject("ledger")?.let(LedgerTipDto::parse)` after the `excludedReason = …` line. Put a comma on the line before it.

- [ ] **Step 5: Word a prediction from its tip**

In `market/LedgerCalls.kt`, directly after `fun isActive(t: LedgerTipDto): Boolean = t.status == STATUS_ACTIVE`, add:

```kotlin
    private val MONITOR_ENDED = setOf("INVALIDATED", "DATA_UNRESOLVED")

    /** Live on both sides: the tip is open and Marksy has not withdrawn it. Without `ledger` (an older backend) the monitor decides. */
    fun isLive(p: ActivePredictionDto): Boolean = p.lifecycleState !in MONITOR_ENDED && p.ledger?.let(::isActive) != false

    /** An ended call reads as its ledger result (spec §7); a withdrawal the tracker has not priced yet says so, never "Invalidated". */
    fun endedLine(p: ActivePredictionDto): String? {
        val t = p.ledger ?: return null
        return if (isActive(t)) "Withdrawn · result pending" else listOfNotNull(state(t), returnText(t.actualReturn)).joinToString(" · ")
    }

    /** The monitor's lifecycle word, dropped once the tip speaks for an ended call. */
    fun lifecycleWord(p: ActivePredictionDto): String? =
        p.lifecycleState.takeIf { it != "UNAVAILABLE" && (p.ledger == null || it !in MONITOR_ENDED) }

    fun closedLabel(c: ClosedPredictionDto): String? = c.ledger?.let(::state)

    fun closedReturn(c: ClosedPredictionDto): Double? = if (c.ledger != null) c.ledger.actualReturn else c.realizedReturn
```

- [ ] **Step 6: The Predictions tab and the Setups list read it**

In `ui/PredictionsView.kt`:
- add `import com.marksy.os.market.LedgerCalls` to its imports;
- replace:

```kotlin
    // Invalidated calls stay listed for honesty but never count or rank as open.
    val (live, invalidated) = remember(open.items) { open.items.partition { it.lifecycleState !in INVALIDATED } }
    var showInvalidated by rememberSaveable { mutableStateOf(false) }
```

with:

```kotlin
    // Ended calls stay listed, shown by default, with their ledger result; they never count or rank as open.
    val (live, ended) = remember(open.items) { open.items.partition(LedgerCalls::isLive) }
    var showEnded by rememberSaveable { mutableStateOf(true) }
```

- in the `EmptyState("No live Marksy calls", …)` line, change `Every open call has been invalidated;` to `Every open call has ended;`;
- replace the `if (invalidated.isNotEmpty()) item(key = "inv-toggle") { … }` block and the `if (showInvalidated) items(invalidated, …) { … }` block with:

```kotlin
                    if (ended.isNotEmpty()) item(key = "ended-toggle") {
                        Text(
                            "${if (showEnded) "Hide" else "Show"} ${ended.size} ended call${if (ended.size == 1) "" else "s"}",
                            color = MarksyTheme.TextSecondary, fontSize = 12.sp,
                            modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable { showEnded = !showEnded }.padding(horizontal = 4.dp, vertical = 6.dp)
                        )
                    }
                    if (showEnded) items(ended, key = { "e-${it.predictionId}" }) { p ->
                        Box(Modifier.alpha(0.55f)) { OpenCallRow(p, quotes[p.symbol]?.lastPrice, note = LedgerCalls.endedLine(p)) { onOpenStock(p.symbol) } }
                    }
```

- in `OpenCallRow`, replace `p.lifecycleState.takeIf { it != "UNAVAILABLE" }?.let(::words),` with `LedgerCalls.lifecycleWord(p)?.let(::words),`;
- in `ClosedCallRow`, replace:

```kotlin
    val o = c.outcome.orEmpty().uppercase()
    val tint = when { "TARGET" in o || "SUCCESS" in o -> MarksyTheme.PrimaryEmerald; "STOP" in o || "FAIL" in o -> MarksyTheme.RedUrgent; else -> MarksyTheme.TextSecondary }
```

with:

```kotlin
    val o = c.outcome.orEmpty().uppercase()
    val tone = c.ledger?.let(LedgerCalls::tone)
    val tint = when {
        tone == LedgerCalls.Tone.POSITIVE -> MarksyTheme.PrimaryEmerald
        tone == LedgerCalls.Tone.NEGATIVE -> MarksyTheme.RedUrgent
        tone != null -> MarksyTheme.TextSecondary
        "TARGET" in o || "SUCCESS" in o -> MarksyTheme.PrimaryEmerald
        "STOP" in o || "FAIL" in o -> MarksyTheme.RedUrgent
        else -> MarksyTheme.TextSecondary
    }
```

- in `ClosedCallRow`, change `Text(c.outcome?.let(::words) ?: "Closed", …)` to `Text(LedgerCalls.closedLabel(c) ?: c.outcome?.let(::words) ?: "Closed", …)`, and change `c.realizedReturn?.let { Text(returnPct(it), …) }` to `LedgerCalls.closedReturn(c)?.let { Text(returnPct(it), …) }`. The arguments behind the ellipses are unchanged;
- delete `internal val INVALIDATED = setOf("INVALIDATED", "DATA_UNRESOLVED")`.

In `ui/SetupsView.kt`:
- add `import com.marksy.os.market.LedgerCalls`;
- replace `all.filter { it.lifecycleState !in INVALIDATED }` with `all.filter(LedgerCalls::isLive)`;
- replace `"All ${all.size} open calls are invalidated. See Predictions for their history."` with `"All ${all.size} open calls have ended. See Predictions for their results."`.

```bash
grep -rn "in INVALIDATED\|val INVALIDATED" app/src/main/java/com/marksy/os/ui/ || echo "no ui reads the monitor's set"
```

Expected: `no ui reads the monitor's set`.

- [ ] **Step 7: A captured tip reads its ledger state**

In `gateway/MarksyTipsApiClient.kt`:
- add `import com.marksy.os.market.LedgerCalls` and `import com.marksy.os.market.LedgerTipDto`;
- replace the whole `private suspend fun fetchTip(...)` function with:

```kotlin
    private suspend fun fetchTip(tipId: String, eventId: Long, kind: String): MarksyInsight =
        ledgerInsight(eventId, kind, tipId, execute("GET", "$apiBaseUrl/tips/$tipId").getJSONObject("data"))
```

- add this directly after the class's closing brace:

```kotlin
/** The tip as the ledger tracks it (spec §9 `GET /tips/{id}`); Marksy's EPIC-803 comparison is no longer read. */
internal fun ledgerInsight(eventId: Long, kind: String, tipId: String, data: JSONObject): MarksyInsight {
    val ledger = data.optJSONObject("ledger")
    val summary = ledger?.let(LedgerTipDto::parse)?.let { tip ->
        listOfNotNull(LedgerCalls.state(tip), LedgerCalls.progressText(tip)).joinToString(" · ")
    } ?: "Recorded by Marksy; tracking starts with the next session"
    return MarksyInsight(
        eventId = eventId,
        summary = summary.boundedText(MAX_SUMMARY_CHARS),
        action = kind,
        tipId = tipId.boundedText(MAX_SHORT_TEXT_CHARS),
        rawResponseJson = ledger?.toString()?.take(MAX_RESPONSE_CHARS)
    )
}
```

- delete what only the old `fetchTip` used: `MAX_LIST_ITEMS`, `MAX_LONG_TEXT_CHARS`, `private fun JSONObject.finiteDouble` and `private fun JSONObject.stringList`. Delete each one only once `grep -n` shows no other use of it in the file.

Only the ledger part is stored, so `TradingInsight.parseMarksyResponse` finds no `comparison` or `marksyView` on new rows. Rows stored before this change keep their local JSON.

- [ ] **Step 8: Run the tests**

Run the Global Constraints command with `--tests 'com.marksy.os.market.*' --tests 'com.marksy.os.gateway.*' --tests 'com.marksy.os.ui.TradingIntelligenceScreenTest'`.
Expected: BUILD SUCCESSFUL, including both new tests.

- [ ] **Step 9: Commit**

```bash
git add app/src/main/java/com/marksy/os/market/InstrumentModels.kt app/src/main/java/com/marksy/os/market/PredictionModels.kt \
  app/src/main/java/com/marksy/os/market/LedgerCalls.kt app/src/main/java/com/marksy/os/ui/PredictionsView.kt \
  app/src/main/java/com/marksy/os/ui/SetupsView.kt app/src/main/java/com/marksy/os/gateway/MarksyTipsApiClient.kt \
  app/src/test/java/com/marksy/os/market/LedgerCallsTest.kt app/src/test/java/com/marksy/os/gateway/MarksyTipsApiContractTest.kt
git commit -m "Predictions, Setups and captured calls read the ledger tip: a withdrawn loss shows as a failed exit

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 11: Unit suite, build, PR, merge; hand the phone install to the morning

**Files:** none new.

- [ ] **Step 1: Run the whole unit-test suite and build**

The change touches the shared prediction DTOs and the delivery client, so run the full JVM suite. It is still never `connectedAndroidTest`.

```bash
export PATH="$(echo "$PATH" | tr ':' '\n' | grep -vE '"|%' | paste -sd:)"
./gradlew --stop
./gradlew --no-daemon :app:testDebugUnitTest
./gradlew --no-daemon :app:assembleDebug
```

Expected: BUILD SUCCESSFUL twice, and `app/build/outputs/apk/debug/app-debug.apk`. **Do not install it.**

If a test outside this plan's files fails, run it on `origin/main` in a scratch checkout. If it fails there too, fix it if the cause is local, else note it in the PR.

- [ ] **Step 2: Push, open the PR, merge**

```bash
git push -u origin feat/tip-ledger-retire-reads
gh pr create --title "Tip ledger Phase 6: Predictions, Setups and captured calls read the tip" --body "$(cat <<'EOF'
The last app surfaces that read Marksy's monitor or the EPIC-803 comparison now read the ledger tip (marksy-api
Phase 6 serves `ledger` on /predictions/active and /tracking/predictions).

- Predictions: a call is live only while its tip is ACTIVE; ended calls stay listed, shown by default, with their
  ledger line ("Exited · Failed · -3.00%", or "Withdrawn · result pending"), never "Invalidated"; the closed list
  shows the ledger state and actual return
- Setups: live calls only, by the same rule
- A captured tip's insight is its ledger state; the app no longer reads `comparison` or `marksyView`
- An older backend without `ledger` keeps the previous behaviour

Tests: full JVM unit suite, assembleDebug. Not installed; the phone install is in the morning.

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
gh pr merge --merge --delete-branch
test -z "$(git -C /c/AIAgent/marksy-os-phase6 status --porcelain)" && cd /c/AIAgent/marksy-os && git worktree remove --force ../marksy-os-phase6 && git fetch origin
```

`--force` is only needed for the ignored `local.properties` and the build outputs. The `status --porcelain` check makes sure nothing uncommitted is lost.

- [ ] **Step 3: Hand the morning install to the controller**

Report that `origin/main` contains 4a Part B, 4b and this Phase 6 app change, ready for the morning install.
- Install with `assembleDebug`, then `adb install -r`, under the `marksy-os-local-run-env` safety gate: window focus, no taps during a call.
- Visual check on the phone:
  - the Trading → Predictions filter: the "Show/Hide N ended calls" toggle, and a withdrawn call's ledger line;
  - Setups, which lists only live calls;
  - the Marksy line of a captured call's detail, which shows its ledger state.

No backend step waits on this install (decision 10).

## Summary

After this phase:
- Every call is in the ledger: the 43 legacy rows (each with its customer's receipt, copies collapsed) and every Marksy prediction ever recorded.
- Every call is tracked once under §6. Its first monitor event is kept for audit, never rewritten.
- The tracker is the only price checker.
- Every Marksy surface reads the tip: the stock page, My tips, Scorecards, the Predictions tab and Setups. A withdrawn losing call is a failed exit wherever it appears.
- Retired:
  - `GET /tips`, `/tips/scorecard` and `/tips/{id}/marksy-view`;
  - `TipView.outcome` and `headToHead`;
  - the EPIC-845 outcome job.
- Nothing was deleted or dropped.

## Parked (needs the user)

- **Moving the ai-trading-agent to a ledger intake.** It posts `POST /v1/tips` with an API key and reads `GET /v1/tips/{id}`'s `comparison.verdict` (`C:\AIAgent\ai-trading-agent\app\marksy.py`). It is another product, outside this phase's repos. Once it moves, one follow-up retires:
  - `POST /tips`;
  - `TipView.comparison` and `marksyView`, and the comparison-state `status`/`sourceReference`;
  - `compare_tip` and its on-demand evaluations, and `ExternalTipComparison`.
- **Dropping `external_tip_outcomes` and `external_tip_comparisons`.** The dashboard's `externalTips` block (spec §14, out of scope) still reads them, as does the agent. Tips themselves are never deleted.

## Validation

- Task 1: the prerequisite and census gates in Steps 1–2; `tests/test_tip_ledger_guard.py`, `tests/test_tip_ledger.py` and `tests/test_tip_matching.py`
- Task 2: `tests/test_tip_backfill.py`, `tests/test_tip_tracking_job.py` and `tests/test_run_tip_tracker.py`
- Task 3: `tests/test_tip_backfill.py`, `tests/test_tip_ledger_guard.py` and `tests/test_tip_reparse.py`
- Task 4: `tests/test_tip_backfill.py`, `tests/test_marksy_prediction_tips.py` and `tests/test_marksy_rating_tips.py`
- Task 5: `tests/test_backfill_tip_ledger_script.py` and `tests/test_operation_verification_s7.py`
- Task 6: the monitor, 2b, EPIC-366, sweep, EPIC-853, EPIC-795 and observation-flow files in Step 4
- Task 7: `tests/test_api_predictions_active.py`, the tracking and EPIC-853 API contracts, and the OpenAPI freshness test
- Task 8: the tips API, scoring and authentication files, and the operations consistency set in Step 5
- Task 9: the regression set, `alembic heads`, the log gate, the production survey, two applies, and the SQL and route checks
- Task 10: `LedgerCallsTest`, `MarksyTipsApiContractTest`, `com.marksy.os.market.*`, `com.marksy.os.gateway.*` and `TradingIntelligenceScreenTest`
- Task 11: the full `:app:testDebugUnitTest` and `:app:assembleDebug`
