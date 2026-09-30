# Tip Ledger Phase 2c (Rating-Engine Port) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Port marksy-os `RatingEngine` and `RatingCalibration` to marksy-api. Rate the universe once per official session and keep every verdict in rating history. A BUY or SELL verdict becomes a direction-only tip of the Marksy channel's `Rating engine` caller: SHORT = 20 sessions, LONG = 250. The unchanged Phase 2a tracker tracks and scores these tips. A changed verdict is a source exit; an unchanged one runs to DIRECTION_HORIZON.

**Architecture:**
- `app/rating_engine.py` (new, pure): the engine and the calibration, float for float the Kotlin.
- `app/rating_inputs.py` (new, pure): the price-history inputs from official daily bars. These are ports of the app helpers `StockRatingInputs` reads: `Technicals`, `UpstoxCandles.returns/range` and `Seasonality`. Monthly bars are derived from the daily bars and never stored.
- `app/marksy_tips.py` (the 2b module) gains the Rating engine's ledger side:
  - `apply_rating_verdict` opens, keeps or withdraws a direction-only tip per stock and horizon;
  - a withdrawal is a `TipSourceExit` that the tracker prices.
- `app/rating_job.py` (new) is the DB runner:
  - it picks the universe and reads bars, NIFTY 50, the OPEN Marksy call and stored fundamentals;
  - it writes one `stock_ratings` row per stock per session and applies each horizon's verdict;
  - once a week it recalibrates the short-term weights into `rating_calibrations`.
- A new `RATING_ENGINE` operation runs it at 09:00 IST Tue–Sat: after the 08:45 tracker, on the previous session's official bars, before the open.
- Migration `0185_rating_history` creates the two tables. `GET /tips` stops listing rating tips.

**Tech Stack:** Python 3.12, FastAPI 0.139, SQLAlchemy 2.0.52 (`Mapped`/`mapped_column`), Alembic 1.19, pytest 8.4 on SQLite 3.49 (tests) and PostgreSQL (prod), Kubernetes CronJobs.

**Spec:** `C:\AIAgent\marksy-os\docs\superpowers\specs\2026-09-29-marksy-central-tip-ledger-design.md`. This plan uses:
- §2 invariants 9 and 12;
- §4 `tips`, and §5.2 match_key v1 and "matchable";
- §6, unchanged;
- §7, the rating-engine bullet;
- §13 phase 2, and §15 defaults (rating SHORT = 20 / LONG = 250 sessions).

For context:
- the Phase 2a plan, `C:\AIAgent\marksy-os\docs\superpowers\plans\2026-09-29-tip-ledger-phase-2a-tracker.md`;
- the Phase 2b plan, `C:\AIAgent\marksy-os\docs\superpowers\plans\2026-09-30-tip-ledger-phase-2b-marksy-predictions.md`;
- the Kotlin sources in `C:\AIAgent\marksy-os\app\src\main\java\com\marksy\os\`:
  - `rating/RatingEngine.kt`, `rating/RatingCalibration.kt` and `rating/RatingSource.kt`;
  - `ui/StockRatingInputs.kt` and `ui/RatingCalibrator.kt`;
  - `upstox/TechnicalRating.kt`, `upstox/Seasonality.kt` and `upstox/UpstoxMarketData.kt`;
- the Kotlin tests `app/src/test/java/com/marksy/os/rating/RatingEngineTest.kt` and `RatingCalibrationTest.kt`.

## Global Constraints

- **Repo and worktree.** Repo: `C:\AIAgent\marksy-api`. Work in a git worktree at `C:\AIAgent\marksy-api-phase2c` on branch `feat/tip-ledger-rating-engine`. Create it from `origin/main` **after Phase 2b has merged**, so that `app/marksy_tips.py` exists with `marksy_channel`.
- **Alembic head.** Run `python -m alembic heads` before writing the migration, and chain `0185_rating_history` onto the head it prints (Task 1 Step 4). If it prints more than one head, stop and report: 2b's `0183` and 4a's `0184` have not been reconciled on main, and merging heads is not this phase's job.
- **Commits, PR, merge and deploy.**
  - Commit once per task. At the end, open a PR with `gh pr create`.
  - **Merging and deploying are authorized:**
    - Once the Task 9 regression set is green, merge with `gh pr merge --merge --delete-branch`.
    - Then deploy with `ssh marksy@103.178.160.233 'bash ~/deployment/server/vps-deploy.sh'`.
  - GitHub Actions don't run (account billing), so GitHub showing UNSTABLE is expected and is not a blocker.
- **Trailers.** Commit messages end with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`. PR bodies end with `🤖 Generated with [Claude Code](https://claude.com/claude-code)`.
- **Tests.**
  - Run only the `python -m pytest tests/<file>.py -v` commands given in each step.
  - Never run the full suite: it takes about 2 hours, and 4 tests already fail on main locally.
  - If a listed test fails, run the same file on `main` in `C:\AIAgent\marksy-api`. If it also fails there, it is pre-existing: note it in the PR and don't fix it.
  - Three `tests/test_api_tips.py` tests already failed on the 2b branch on 2026-09-30, before any 2c change:
    - `test_a_consensus_suppressed_tip_shows_marksys_numbers_not_a_blank_no_view`
    - `test_the_marksy_view_of_an_advisory_tip_carries_its_numbers`
    - `test_an_advisory_tip_card_carries_marksys_numbers_on_the_dashboard`

    Check them against `main` before treating them as 2c's.
- **Pytest file DB.** `tests/conftest.py` gives the app engine a file DB at `%TEMP%\marksy-pytest-default.db`, and `create_all` never alters an existing table. Delete that file whenever the ORM schema changes (Task 1 says when).
- **Code style.** Follow the repo:
  - `Mapped`/`mapped_column`, and `BigInteger().with_variant(Integer, "sqlite")` primary keys;
  - no ORM relationships;
  - camelCase keys in `result_summary`;
  - comments are one line and explain a non-obvious WHY only;
  - no unrelated refactors.
- **Migrations.** Alembic revision ids must be ≤ 32 characters. Migrations must run on both SQLite and PostgreSQL.
- **SQLite and time zones.** SQLite drops tzinfo on read. Pass every stored datetime through `_aware(...)` (assume UTC) before comparing it or converting it to IST.
- **Spec §7, verbatim:** "Rating engine (ported `RatingEngine` + `RatingCalibration`): SHORT = 20 sessions, LONG = 250. A BUY or SELL verdict creates a direction-only tip (entry = price at rating time, `entry_basis = FIRST_SEEN_PRICE`); a verdict change closes it as SOURCE_EXIT; otherwise it ends as DIRECTION_HORIZON. HOLD and NOT_ENOUGH_DATA create no tip; all verdicts stay in rating history. Inputs the backend does not already store are fetched from Upstox at compute time and not kept."
- **Spec §7, first bullet:** "Every rule in §6 … applies to Marksy's calls unchanged. There is no Marksy-specific tracking or scoring rule." `app/tip_tracker.py` and `app/tip_tracking_job.py` are not modified.
- **Spec §2.12** and the user's storage rule: store nothing that isn't reusable for learning. This phase fetches nothing from Upstox (decision 5) and stores only the derived facts in decision 2.
- **Kotlin parity is the port's contract.** The engine and input tests pin figures that the compiled marksy-os classes printed on 2026-09-29. They came from `app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes`, run through a throwaway Java harness on the same inputs. They cannot be re-derived in marksy-api, so if one fails, fix the port, not the number. Decision 7 lists the parity rules.
- **Out of scope:**
  - any API (Phase 3b);
  - app changes, including deleting the on-device engine (Phase 4b);
  - unblocking `UPSTOX_FUNDAMENTALS` (EPIC-177);
  - backfilling rating history.

## Review Focus

These are the inputs the spec implies but its happy paths never exercise. Each one has a test in the owning task:

1. **A coverage sum on a floating-point boundary.** Python 3.12's `sum()` compensates and Kotlin's `sumOf` does not. So a port using `sum()` gives full LONG coverage as 1.0 instead of 0.9999999999999999. Worse, it can move trend + growth + seasonality off the exact 0.5 floor, where the verdict flips to NOT_ENOUGH_DATA. Test: Task 2, `test_coverage_sums_left_to_right_like_kotlin`.
2. **"Entry = price at rating time" for a rating made before the open, on yesterday's close.** The entry must be the first takeable price after the call, the session-1 open. It must never be the close the rating read, because nobody could have bought at that close when the call was made. Test: Task 5, `test_the_entry_is_the_next_open_not_the_close_the_rating_used`.
3. **A BUY that becomes NOT_ENOUGH_DATA** (for example, the Marksy call it leaned on closed). It must close as SOURCE_EXIT at the next open with a scored outcome. It must not linger as a call Marksy no longer makes, and it must not vanish. Test: Task 5, `test_a_verdict_change_closes_the_tip_as_a_source_exit_at_the_next_open`.
4. **A stock missing its bar for the rating session, while the market had one.** A data gap is not a verdict. Rating it on a stale bar could read NOT_ENOUGH_DATA and close a good call. Test: Task 6, `test_a_stock_missing_the_session_bar_is_not_rated_and_keeps_its_tip`.
5. **Calibration leakage.** A calibration sample must be rated only on bars up to its call's scan date, or the tuned weights learn from the future. Test: Task 7, `test_calibration_samples_see_no_bar_after_the_call_date`.

## Resolved ambiguities (decisions this plan makes)

1. **Which stocks get rated, and when.**
   - **Universe.** Three sets, with benchmark indices removed (`app/universe_integrity.py:85` `BENCHMARK_INDEX_SYMBOLS`):
     - the latest discovery scan's eligible candidates (`ScanCandidate.eligible`);
     - every stock with an OPEN `Prediction`;
     - every stock with an ACTIVE Rating-engine tip.

     Why:
     - The universe is a fixed rule decided before any verdict. §1 says "no call goes in hiding", so the engine can't pick where it calls.
     - The scan's eligible set is the coverage Marksy already stands behind.
     - Discovery can skip a stock that already has an OPEN prediction (`continuous_opportunity.py:134-147`, per the 2b plan). Yet only those stocks have the MARKSY_CALL factor.
     - A stock with an open rating tip must stay rated, so that a changed verdict can still end the tip.

     Rejected:
     - The watchlist: `watchlist_entries` (`app/models.py:525-533`) is an add/remove event log, not a coverage set.
     - Stocks with a prediction only: the app rates every stock page today, and after Phase 4b most pages would have no backend rating.
   - **Schedule.** A new operation, `RATING_ENGINE`, with a CronJob at 09:00 IST Tue–Sat (`30 3 * * 2-6`). Why:
     - It needs the previous session's official candle. That candle lands around 07:05 IST the next day and is confirmed at 08:30 by PROVISIONAL_CONFIRMATION.
     - It follows TIP_TRACKING (08:45, 15-minute deadline). So yesterday's exits are already closed when an unchanged key is checked again.
     - Before 09:15, every new call is first seen pre-open. §6.1 then makes that day session 1, with the open as its entry.
     - Tue–Sat matches the official-candle cadence. Saturday rates Friday's session, and its tips enter at Monday's open; a Monday run would have nothing new.
     - The 16:15 discovery run was rejected: it runs on provisional bars. A verdict on a provisional bar that the official candle later contradicts would still be an immutable tip.
   - **Idempotence.** One `stock_ratings` row per (stock, session) makes a rerun a no-op, and so is a holiday, which brings no new session. The dedup key is `rate:<IST date>`.
   - **Registration** follows TIP_TRACKING (2a Task 7):
     - a trigger policy and a runnable job;
     - a recovery policy that depends on TIP_TRACKING;
     - a verifier;
     - the census moves from 22/30 to 23/31.
2. **Rating history: `stock_ratings`, one row per stock per official session.** It holds both horizons, because the factor scores are the same for both; one row halves the rows. Each field, and why it is reusable for learning:
   - `session_date` and `rated_at`: the point-in-time anchors. They join the row to the candle store for forward returns, and to the tip's `first_seen_at`.
   - `price`: the official close the rating read. It is the reference for every verdict's forward return, HOLD and NOT_ENOUGH_DATA included, which have no tip. So a recalibration can learn from all verdicts, not only the tipped ones.
   - `factors`: JSON `{factor: score}`, every factor present, RISK included. Factor scores don't depend on the weights, so any candidate config's score can be recomputed for every past rating without the raw inputs.
   - Per horizon (`short_*` and `long_*`):
     - `verdict`, `score` and `confidence`: what was called, and how strongly;
     - `coverage`: separates "no data" from "no view";
     - `tip_id`: the tip the verdict stands behind, which links the verdict to its ledger outcome.
   - `config_version` and `inputs_version` (`RIN-001`): which weights and which input mapping produced the row, so learning never mixes regimes.

   Not stored:
   - the raw inputs, which can be re-derived from the stored bars, predictions and fundamentals;
   - the reasons and the support/against lists, which are presentation and can be recomputed.

   Calibration runs get their own table (decision 4).
3. **Tip lifecycle per (stock, horizon).**
   - **Terms.** A direction-only BUY or SELL on the Marksy channel (2b `marksy_channel`), with caller `Rating engine` (seeded by 0181, resolved by `resolve_caller`). Its fields:
     - `parser_version = RTG-001`;
     - `horizon_sessions` = 20 or 250, `horizon_days` the same, and `horizon_basis = ENGINE`;
     - `entry_basis = FIRST_SEEN_PRICE`, `entry_status = ENTERED`, and NULL entry, target and stop;
     - `first_seen_at` = the rating time, and `tip_as_of` = the session anchor;
     - no receipts (2b decision 2) and no `prediction_id`.

     This is exactly the shape intake gives a first-seen tip (`app/tip_ledger.py` `_new_tip`: `ENTRY_BASIS_FIRST_SEEN_PRICE`, `ENTRY_ENTERED`).
   - **SHORT and LONG** are told apart by `horizon_sessions`, which is part of match_key v1. So one stock can hold a SHORT and a LONG call at the same time.
   - **Unchanged verdict.** `find_matchable` on the key returns the ACTIVE tip, and nothing new is opened. This is the intake and 2b pattern, under the same `_lock_match_key`.
   - **Entry.** The tracker's own §6.3 first-seen rule applies: "the price at `first_seen_at` (intraday) or else the session 1 open".
     - For the 09:00 run that is the day's open (`app/tip_tracker.py:316-320` fixes `bar.open`). For an in-session rerun it is the first minute bar after the rating.
     - The close the rating read is kept on the rating row (`price`), not as the entry. It wasn't takeable when the call was made. Fixing it at insert would also be a Marksy-specific entry rule, which §7 forbids.
     - The guard already allows this. `app/tip_ledger_guard.py` `_may_fill` lets `entry_low`, `entry_high` and `entry_price` fill from NULL on a FIRST_SEEN_PRICE tip, and `app/tip_tracking_job.py:147` treats `entry_low IS NULL` as "not fixed yet". No guard change is needed.
   - **Verdict change.** Any verdict other than the open tip's direction withdraws the tip: HOLD, the other side, or NOT_ENOUGH_DATA.
     - The withdrawal writes `TipSourceExit(receipt_id=None, stated_exit_price=None, exit_seen_at=rating time)`.
     - The tracker prices it (§6.5, 2a decision 7). A pre-open exit takes that session's open.
     - NOT_ENOUGH_DATA counts as a change because the engine no longer stands behind the call. Keeping the tip would score a call Marksy no longer makes.
     - BUY→SELL writes the exit and opens the SELL tip, which has a different key.
   - **A data gap is not a verdict.** A stock without an official bar on the rating session is not rated: it gets no row and no exit.
   - **Pending exit.** An unchanged verdict can find its ACTIVE tip already carrying an exit (the tracker hasn't closed it yet). It then opens nothing that session and its rating row names no tip; the next session opens a new tip. This only happens after a failed tracker run, and the run summary counts it (`pendingExit`).
   - **Otherwise** the tracker closes the tip at its horizon as DIRECTION_HORIZON, unchanged.
   - **`GET /tips`** is the EPIC-803 list of external tips. 2b's `_external_tips_only()` excludes `prediction_id` tips; it also has to exclude rating tips, which have no prediction. `parser_version IS DISTINCT FROM 'RTG-001'` keeps the 43 legacy tips, whose `parser_version` is NULL.
4. **Calibration.**
   - **Samples.** `PredictionOutcome.actual_return` of closed predictions in the clean population (`app/clean_population.py:72` `clean_intelligence_only`, "every future calibration … composes this"). This is exactly what the app's calibrator reads:
     - `/tracking` closed items carry `realizedReturn = outcome.actual_return` (`api/services/tracking.py:632`);
     - `excludedReason` comes from `historical_exclusion_fields`.

     It takes the newest 400, needs at least 60, and orders them by scan date.

     Rejected sources:
     - Tracker outcomes: 2b registers only the predictions made from now on, so there would be no samples for months. `PredictionOutcome` is the learning-label table (2b decision 10).
     - Rating-tip outcomes: they exist only for BUY/SELL, so they are a selected sample, and they depend on the weights being tuned.
   - **Point-in-time inputs.** Each sample is `price_inputs(bars, nifty, on=scan date)`. That means bars up to the scan date and completed months before its month (`RatingCalibrator.pointInTime`). It has no fundamentals and no call probability, as in the Kotlin (`StockFundamentals()`, no probability), so those factors are absent and keep their weights.
   - **Cadence: weekly.** The calibration runs inside the RATING_ENGINE run, after the ratings (which it never delays), whenever the latest calibration is 7 days old or more. The next run uses its result. Why weekly: `calibrate` always starts from V1 and can flip adoption from one run to the next. Daily flips would close rating tips because the weights changed, not because the market did.
   - **Storage.** A `rating_calibrations` table with one row per run, adopted or not. The version is `marksy-rating-1-cal-<last call date>`, the app's own naming.
     - `active_config` returns the latest row's weights if that run was adopted, else V1 (`RatingCalibrator.configFor`).
     - Every rating row records `config_version`.
     - Settings were rejected: an env value can't be written by a job and keeps no history.
5. **Nothing is fetched from Upstox at compute time.**
   - Every input the engine can use is already stored:
     - official daily bars, including the `NIFTY50` row's (`api/services/market.py:20`);
     - OPEN predictions;
     - Yahoo fundamentals (`app/models.py:1316` `FundamentalDataRecord`).
   - Everything else comes only from Upstox `/v2/fundamentals/{isin}/…`: key ratios with sector values, yearly statements, the balance sheet and share-holdings. That is `UPSTOX_FUNDAMENTALS`, which is `COST_UNKNOWN` (`app/market_data/upstox_capabilities.py:93`). `ensure_capability_allowed` blocks it "with no override flag" (lines 109-121).
   - So the rate and cost limits are moot, and the failure behaviour is structural: VALUATION, QUALITY and OWNERSHIP are absent, and the engine's coverage rule handles it.
   - **Consequence:** LONG coverage can reach at most TREND .10 + CALL .10 + GROWTH .20 = .40. So LONG is NOT_ENOUGH_DATA for every stock, and no LONG tips open, until the capability is verified FREE. A follow-up then adds that fetch. The rating rows keep `long_coverage`, so the change will show.
   - SHORT rates with trend + call (.65), or with trend + growth + seasonality (exactly .5).
   - Monthly candles are derived from daily bars (`monthly_bars`), neither fetched nor stored.
   - Every `RatingInputs` field and its source:
     - `price`: the rating session's official close in `market_prices`.
     - `technical_bull`, `technical_bear` and `technical_total`: `market_prices`, over a 365-day window, through the ported `Technicals.rate`.
     - `return_3m`: `market_prices` (`UpstoxCandles.returns` "3M"). `nifty_3m`: the `NIFTY50` stock's `market_prices`.
     - `year_low` and `year_high`: the window's low and high.
     - `volatility` and `beta`: `market_prices`, the stock against NIFTY50.
     - `month_average` and `month_negative_share`: monthly bars derived from `market_prices`, using completed months only and needing at least 13 of them.
     - `call_upside_pct`: `target_return × 100` of the newest OPEN `Prediction`. `call_probability`: its `predicted_probability`, which is what `/instruments` serves as `probabilityAtPublication` (`app/recommendation_tracking_view.py:112`).
     - `revenue_growth_yoy` and `profit_growth_yoy`: `FundamentalDataRecord` `revenue` and `net_income`. These are Yahoo's trailing-twelve-month figures at `mostRecentQuarter` (`app/fundamental_data/yahoo.py:44-51,80-91`). The latest point-in-time record is compared with the one whose period ended 350–380 days earlier.
     - `pe` and `pb` are stored (`pe_ratio`, `price_to_book`), but `sector_pe` and `sector_pb` are not, so VALUATION is absent.
     - Absent: `ev_ebitda` and `sector_ev_ebitda`; `roe`, `sector_roe`, `roce` and `sector_roce` (QUALITY); `leverage` (it only adjusts QUALITY); `promoter_change`, `fii_change` and `mutual_fund_change` (OWNERSHIP).
6. **Exposure.**
   - This phase adds no API. Phase 3b serves the engine scorecards and `calls.marksy.engines[]` (§9) from the ledger and `stock_ratings`.
   - The app's on-device `RatingEngine` keeps running until Phase 4b deletes it (§10, and §13 phase 4 comes "after 3").
   - The two can disagree, because the app fetches Upstox fundamentals with the user's own token. The ledger's calls are the backend's.
7. **Parity rules** (why the tests pin exact figures):
   - Floats, not Decimal, throughout the engine.
   - `sum_of` and `average_of` add left to right. Python 3.12's `sum()` uses compensated summation, and so does `statistics.mean`.
   - Reasons use Java's `String.format`, which rounds the shortest decimal form half-up: `_fixed` quantizes `Decimal(repr(x))` with ROUND_HALF_UP. `Math.round` is `floor(x + .5)`.
   - Sorts are stable, like Kotlin's `sortedBy`.
   - Kotlin's `1.0 / sqrt(0.0)` is Infinity, where Python raises, so `calibrate` guards an empty test set.
   - The calibration tests reimplement `kotlin.random.Random` (XorWow), so their 300 samples are the Kotlin test's own, draw for draw.
   - libm's `tanh` can differ in the last bit, so the pinned figures use `abs=1e-12`.
8. **Input windows.**
   - The window is 365 days: the app loads `ChartRange.Y1` (`StockLiveData.kt:58-61`).
   - `on` is the rating's IST date in live runs (the app's "today"), and the scan date for samples (`pointInTime`).
   - Seasonality uses completed months only in both. This is the calibrator's rule, applied to live ratings too, so that ratings and samples come from one function. The app's live card also counts the current partial month; that difference is deliberate.
   - With under 30 bars in the window, there are no price-derived factors (`pointInTime`'s floor).
9. **Failure isolation.** Each stock commits on its own. One stock's bad data rolls back only that stock, and the run counts it (the tracker's F1 rule).
10. **Reasons are ported but not stored.** `FactorScore.reason` is part of the engine and is pinned by the parity tests. Phase 3b can recompute reasons from the same stored inputs if it shows them.

## File Structure

- Modify `app/tip_vocabulary.py`: add `CALLER_RATING_ENGINE` and `PARSER_RATING_ENGINE`.
- Modify `app/models.py`: add `StockRating` and `RatingCalibrationRun` directly after `TipDailyProgress`.
- Create `migrations/versions/0185_rating_history.py`.
- Create `app/rating_engine.py`: the pure engine (Task 2) and the calibration (Task 3).
- Create `app/rating_inputs.py`: the pure price-history inputs.
- Modify `app/marksy_tips.py`: append the Rating engine's tips (open, keep, withdraw).
- Modify `api/services/tips.py`: `_external_tips_only()` also excludes rating tips.
- Create `app/rating_job.py`: the runner (Task 6) and the calibration runner (Task 7).
- Create `scripts/run_rating_engine.py`: the CronJob entrypoint.
- Modify `app/schedule_orchestration.py`, `app/operation_entrypoints.py`, `app/operation_recovery.py` and `app/operation_verification.py`: register `RATING_ENGINE`.
- Create `deploy/k8s/base/rating-engine-cronjob.yaml`; modify `deploy/k8s/base/kustomization.yaml`.
- Tests:
  - Create `tests/test_rating_history_migration.py`, `tests/test_rating_engine.py`, `tests/test_rating_inputs.py`, `tests/test_marksy_rating_tips.py`, `tests/test_rating_job.py` and `tests/test_run_rating_engine.py`.
  - Modify `tests/test_operation_verification_s7.py`.

---

### Task 1: Worktree, vocabulary, and the rating tables (ORM + migration 0185)

**Files:**
- Modify: `app/tip_vocabulary.py`
- Modify: `app/models.py` (after `class TipDailyProgress`)
- Create: `migrations/versions/0185_rating_history.py`
- Test: `tests/test_rating_history_migration.py`

**Interfaces:**
- Produces, in `app/tip_vocabulary.py`: `CALLER_RATING_ENGINE = "Rating engine"` and `PARSER_RATING_ENGINE = "RTG-001"`.
- Produces ORM `StockRating`, with the unique constraint `uq_stock_ratings_stock_session` on `(stock_id, session_date)`. Its columns are `id, stock_id, session_date, rated_at, price, config_version, inputs_version, factors, short_verdict, short_score, short_confidence, short_coverage, short_tip_id, long_verdict, long_score, long_confidence, long_coverage, long_tip_id, created_at`.
- Produces ORM `RatingCalibrationRun` (table `rating_calibrations`), with columns `id, ran_at, version, adopted, trend_weight, seasonality_weight, samples, test_samples, train_spearman, test_spearman, base_test_spearman, first_call_date, last_call_date, created_at`.

- [ ] **Step 1: Create the worktree and read the head**

```bash
cd /c/AIAgent/marksy-api && git fetch origin && git worktree add ../marksy-api-phase2c -b feat/tip-ledger-rating-engine origin/main
cd /c/AIAgent/marksy-api-phase2c && git log --oneline -1 && git log --oneline origin/main | grep -m1 "marksy-predictions"
grep -n "def marksy_channel\|def withdraw_prediction" app/marksy_tips.py && grep -n "def _external_tips_only" api/services/tips.py
python -m alembic heads
```

Expected:
- The `grep -m1` prints the Phase 2b merge.
- The two `grep -n` commands find `marksy_channel`, `withdraw_prediction` and `_external_tips_only`.
- `alembic heads` prints exactly one head. Keep it for Step 4.

All later commands run in `C:\AIAgent\marksy-api-phase2c`. If `_external_tips_only` is missing, because 2b merged its filter inline in `list_tips`, Task 5 Step 5 says what to do.

- [ ] **Step 2: Write the failing migration test**

Create `tests/test_rating_history_migration.py`:

```python
"""0185_rating_history on SQLite: one rating per stock per session; downgrade drops both tables."""
from __future__ import annotations

from datetime import date, datetime, timezone

import pytest
import sqlalchemy as sa
from sqlalchemy.exc import IntegrityError

from tests._migration_helpers import run_revision

RATED_AT = datetime(2026, 9, 29, 3, 30, tzinfo=timezone.utc)


@pytest.fixture
def engine():
    engine = sa.create_engine("sqlite:///:memory:")
    with engine.begin() as conn:
        conn.execute(sa.text("CREATE TABLE stocks (id INTEGER PRIMARY KEY)"))
        conn.execute(sa.text("CREATE TABLE tips (id INTEGER PRIMARY KEY)"))
        conn.execute(sa.text("INSERT INTO stocks (id) VALUES (1)"))
        run_revision(conn, "0185_rating_history")
    return engine


def _rating(conn, session_date):
    conn.execute(sa.text(
        "INSERT INTO stock_ratings (stock_id, session_date, rated_at, price, config_version, inputs_version, factors,"
        " short_verdict, short_score, short_confidence, short_coverage, long_verdict, long_score, long_confidence,"
        " long_coverage) VALUES (1, :day, :at, 100, 'marksy-rating-1', 'RIN-001', '{}', 'HOLD', 0.1, 0.2, 0.65,"
        " 'NOT_ENOUGH_DATA', 0, 0, 0.2)"
    ), {"day": session_date, "at": RATED_AT})


def test_a_stock_has_at_most_one_rating_per_session(engine):
    with engine.begin() as conn:
        _rating(conn, date(2026, 9, 28))
        _rating(conn, date(2026, 9, 29))
    with pytest.raises(IntegrityError), engine.begin() as conn:
        _rating(conn, date(2026, 9, 28))


def test_downgrade_drops_both_tables(engine):
    with engine.begin() as conn:
        run_revision(conn, "0185_rating_history", "downgrade")
        tables = sa.inspect(conn).get_table_names()
    assert "stock_ratings" not in tables
    assert "rating_calibrations" not in tables
```

- [ ] **Step 3: Run it to verify it fails**

Run: `python -m pytest tests/test_rating_history_migration.py -v`
Expected: FAIL with `FileNotFoundError` for `migrations/versions/0185_rating_history.py`.

- [ ] **Step 4: Write the migration**

Create `migrations/versions/0185_rating_history.py`. The revision id stays `0185_rating_history`. `down_revision` and the `Revises:` line are the head that Step 1 printed. The file below assumes `0184_channel_alias_scope` (2b's 0183 plus 4a's 0184, reconciled); if Step 1 printed another single head, put that head in both places.

```python
"""Tip ledger Phase 2c: rating history and calibration runs (tip-ledger spec §7).

Revision ID: 0185_rating_history
Revises: 0184_channel_alias_scope
"""
from __future__ import annotations

import sqlalchemy as sa
from alembic import op

revision = "0185_rating_history"
down_revision = "0184_channel_alias_scope"
branch_labels = None
depends_on = None

_ID = sa.BigInteger().with_variant(sa.Integer(), "sqlite")
_FRACTION = sa.Numeric(12, 6)


def upgrade() -> None:
    op.create_table(
        "stock_ratings",
        sa.Column("id", _ID, primary_key=True),
        sa.Column("stock_id", sa.Integer(), sa.ForeignKey("stocks.id"), nullable=False),
        sa.Column("session_date", sa.Date(), nullable=False),
        sa.Column("rated_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("price", sa.Numeric(18, 6), nullable=False),
        sa.Column("config_version", sa.String(64), nullable=False),
        sa.Column("inputs_version", sa.String(16), nullable=False),
        sa.Column("factors", sa.JSON(), nullable=False),
        sa.Column("short_verdict", sa.String(16), nullable=False),
        sa.Column("short_score", _FRACTION, nullable=False),
        sa.Column("short_confidence", _FRACTION, nullable=False),
        sa.Column("short_coverage", _FRACTION, nullable=False),
        sa.Column("short_tip_id", _ID, sa.ForeignKey("tips.id")),
        sa.Column("long_verdict", sa.String(16), nullable=False),
        sa.Column("long_score", _FRACTION, nullable=False),
        sa.Column("long_confidence", _FRACTION, nullable=False),
        sa.Column("long_coverage", _FRACTION, nullable=False),
        sa.Column("long_tip_id", _ID, sa.ForeignKey("tips.id")),
        sa.Column("created_at", sa.DateTime(timezone=True), server_default=sa.func.now(), nullable=False),
        sa.UniqueConstraint("stock_id", "session_date", name="uq_stock_ratings_stock_session"),
    )
    op.create_table(
        "rating_calibrations",
        sa.Column("id", _ID, primary_key=True),
        sa.Column("ran_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("version", sa.String(64), nullable=False),
        sa.Column("adopted", sa.Boolean(), nullable=False),
        sa.Column("trend_weight", _FRACTION, nullable=False),
        sa.Column("seasonality_weight", _FRACTION, nullable=False),
        sa.Column("samples", sa.Integer(), nullable=False),
        sa.Column("test_samples", sa.Integer(), nullable=False),
        sa.Column("train_spearman", _FRACTION, nullable=False),
        sa.Column("test_spearman", _FRACTION, nullable=False),
        sa.Column("base_test_spearman", _FRACTION, nullable=False),
        sa.Column("first_call_date", sa.Date(), nullable=False),
        sa.Column("last_call_date", sa.Date(), nullable=False),
        sa.Column("created_at", sa.DateTime(timezone=True), server_default=sa.func.now(), nullable=False),
    )


def downgrade() -> None:
    op.drop_table("rating_calibrations")
    op.drop_table("stock_ratings")
```

- [ ] **Step 5: Add the ORM classes**

In `app/models.py`, directly after `class TipDailyProgress` (it ends with its `recorded_at` column), add the two classes below. Every name they use is already imported by `app/models.py` (line 3 imports `Boolean`, `Date`, `JSON`, `Numeric`, `UniqueConstraint` and `func`).

```python
class StockRating(Base):
    """One rating of a stock for one official session (tip-ledger spec §7): both horizons' verdicts, the
    weight-free factor scores they came from and the close they used. Every verdict is kept; none is rewritten."""

    __tablename__ = "stock_ratings"
    __table_args__ = (UniqueConstraint("stock_id", "session_date", name="uq_stock_ratings_stock_session"),)
    id: Mapped[int] = mapped_column(BigInteger().with_variant(Integer, "sqlite"), primary_key=True)
    stock_id: Mapped[int] = mapped_column(ForeignKey("stocks.id"))
    session_date: Mapped[date] = mapped_column(Date)
    rated_at: Mapped[datetime] = mapped_column(DateTime(timezone=True))
    price: Mapped[Decimal] = mapped_column(Numeric(18, 6))
    config_version: Mapped[str] = mapped_column(String(64))
    inputs_version: Mapped[str] = mapped_column(String(16))
    factors: Mapped[dict] = mapped_column(JSON)
    short_verdict: Mapped[str] = mapped_column(String(16))
    short_score: Mapped[Decimal] = mapped_column(Numeric(12, 6))
    short_confidence: Mapped[Decimal] = mapped_column(Numeric(12, 6))
    short_coverage: Mapped[Decimal] = mapped_column(Numeric(12, 6))
    short_tip_id: Mapped[int | None] = mapped_column(ForeignKey("tips.id"))
    long_verdict: Mapped[str] = mapped_column(String(16))
    long_score: Mapped[Decimal] = mapped_column(Numeric(12, 6))
    long_confidence: Mapped[Decimal] = mapped_column(Numeric(12, 6))
    long_coverage: Mapped[Decimal] = mapped_column(Numeric(12, 6))
    long_tip_id: Mapped[int | None] = mapped_column(ForeignKey("tips.id"))
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=func.now())


class RatingCalibrationRun(Base):
    """One calibration of the short-term rating weights on Marksy's closed calls (tip-ledger spec §7). The latest
    run decides the weights ratings use: its tuned ones if adopted, else V1."""

    __tablename__ = "rating_calibrations"
    id: Mapped[int] = mapped_column(BigInteger().with_variant(Integer, "sqlite"), primary_key=True)
    ran_at: Mapped[datetime] = mapped_column(DateTime(timezone=True))
    version: Mapped[str] = mapped_column(String(64))
    adopted: Mapped[bool] = mapped_column(Boolean)
    trend_weight: Mapped[Decimal] = mapped_column(Numeric(12, 6))
    seasonality_weight: Mapped[Decimal] = mapped_column(Numeric(12, 6))
    samples: Mapped[int] = mapped_column(Integer)
    test_samples: Mapped[int] = mapped_column(Integer)
    train_spearman: Mapped[Decimal] = mapped_column(Numeric(12, 6))
    test_spearman: Mapped[Decimal] = mapped_column(Numeric(12, 6))
    base_test_spearman: Mapped[Decimal] = mapped_column(Numeric(12, 6))
    first_call_date: Mapped[date] = mapped_column(Date)
    last_call_date: Mapped[date] = mapped_column(Date)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=func.now())
```

- [ ] **Step 6: Add the vocabulary**

In `app/tip_vocabulary.py`:
- directly after `CALLER_PREDICTION_ENGINE = "Prediction engine"`, add `CALLER_RATING_ENGINE = "Rating engine"`;
- directly after `PARSER_PREDICTION_ENGINE = "ENG-001"`, add `PARSER_RATING_ENGINE = "RTG-001"`.

- [ ] **Step 7: Reset the pytest file DB and run the tests**

```bash
rm -f "$TEMP/marksy-pytest-default.db"
python -m pytest tests/test_rating_history_migration.py tests/test_tip_ledger_migration.py tests/test_tip_ledger.py -v
python -m alembic heads
```

Expected: all PASS, and one head: `0185_rating_history`.

- [ ] **Step 8: Commit**

```bash
git add app/tip_vocabulary.py app/models.py migrations/versions/0185_rating_history.py tests/test_rating_history_migration.py
git commit -m "Rating engine: rating history and calibration tables (migration 0185); Rating engine vocabulary

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 2: Port `RatingEngine` (`app/rating_engine.py`)

**Files:**
- Create: `app/rating_engine.py`
- Test: `tests/test_rating_engine.py`

**Interfaces:**
- Produces:
  - Constants:
    - `HORIZON_SHORT = "SHORT"`, `HORIZON_LONG = "LONG"`, `HORIZONS` and `HORIZON_SESSIONS = {"SHORT": 20, "LONG": 250}`;
    - `VERDICT_BUY`, `VERDICT_HOLD`, `VERDICT_SELL` and `VERDICT_NOT_ENOUGH_DATA`;
    - `FACTOR_TREND`, `FACTOR_MARKSY_CALL`, `FACTOR_VALUATION`, `FACTOR_QUALITY`, `FACTOR_GROWTH`, `FACTOR_OWNERSHIP`, `FACTOR_RISK` and `FACTOR_SEASONALITY`.
  - `@dataclass(frozen=True) RatingInputs`: the 30 Kotlin fields in snake_case, every one `float | None` or `int | None`, default `None`.
  - `@dataclass(frozen=True) FactorScore(factor: str, score: float, reason: str)`
  - `@dataclass(frozen=True) Contribution(factor: str, weighted: float, reason: str)`
  - `@dataclass(frozen=True) HorizonRating(horizon: str, verdict: str, score: float, confidence: float, coverage: float, support: tuple[Contribution, ...], against: tuple[Contribution, ...])`
  - `@dataclass(frozen=True) RatingResult(version: str, factors: tuple[FactorScore, ...], ratings: tuple[HorizonRating, ...])`, with `.horizon(horizon: str) -> HorizonRating`
  - `@dataclass(frozen=True) RatingConfig(version: str, weights: dict[str, dict[str, float]], buy_at: float = .3, sell_at: float = -.3, min_coverage: float = .5, risk_shrink: float = .3)`, and `V1`
  - `sum_of(values) -> float` and `average_of(values) -> float`, Kotlin's left-to-right `sumOf` and `average()`
  - `rate(inputs: RatingInputs, config: RatingConfig = V1) -> RatingResult`

- [ ] **Step 1: Write the failing tests**

Create `tests/test_rating_engine.py`:

```python
"""Rating engine port (tip-ledger spec §7): marksy-os RatingEngineTest, plus the compiled Kotlin engine's own figures."""
from __future__ import annotations

from dataclasses import replace

import pytest

from app.rating_engine import (
    FACTOR_GROWTH,
    FACTOR_OWNERSHIP,
    FACTOR_QUALITY,
    FACTOR_RISK,
    FACTOR_SEASONALITY,
    FACTOR_TREND,
    FACTOR_VALUATION,
    HORIZON_LONG,
    HORIZON_SHORT,
    VERDICT_BUY,
    VERDICT_HOLD,
    VERDICT_NOT_ENOUGH_DATA,
    VERDICT_SELL,
    RatingInputs,
    rate,
)

BULLISH = RatingInputs(
    price=120.0, technical_bull=12, technical_bear=1, technical_total=14, return_3m=15.0, nifty_3m=2.0, year_low=80.0,
    year_high=125.0, call_upside_pct=8.0, call_probability=.75, pe=12.0, sector_pe=20.0, pb=1.5, sector_pb=2.5,
    roe=22.0, sector_roe=14.0, roce=24.0, sector_roce=15.0, revenue_growth_yoy=18.0, profit_growth_yoy=25.0,
    promoter_change=.5, fii_change=1.0, volatility=22.0, month_average=2.0, month_negative_share=.3,
)
BEARISH = RatingInputs(
    price=80.0, technical_bull=0, technical_bear=13, technical_total=14, return_3m=-20.0, nifty_3m=1.0, year_low=78.0,
    year_high=150.0, call_upside_pct=-6.0, call_probability=.7, pe=45.0, sector_pe=18.0, pb=6.0, sector_pb=2.0,
    roe=4.0, sector_roe=15.0, roce=5.0, sector_roce=16.0, revenue_growth_yoy=-12.0, profit_growth_yoy=-40.0,
    promoter_change=-2.0, fii_change=-1.5, volatility=24.0, month_average=-3.0, month_negative_share=.8,
)
KOTLIN = 1e-12  # the compiled engine's figures; libm's tanh may differ in the last bit


def _verdicts(result):
    return result.horizon(HORIZON_SHORT).verdict, result.horizon(HORIZON_LONG).verdict


def test_strong_inputs_rate_buy_and_weak_ones_sell():
    assert _verdicts(rate(BULLISH)) == (VERDICT_BUY, VERDICT_BUY)
    assert _verdicts(rate(BEARISH)) == (VERDICT_SELL, VERDICT_SELL)


def test_conflicting_factors_rate_hold():
    mixed = replace(BULLISH, technical_bull=2, technical_bear=10, return_3m=-8.0, call_upside_pct=None,
                    call_probability=None, pe=30.0, sector_pe=20.0, revenue_growth_yoy=2.0, profit_growth_yoy=-3.0)
    assert rate(mixed).horizon(HORIZON_LONG).verdict == VERDICT_HOLD


def test_too_little_data_says_so():
    result = rate(RatingInputs(month_average=3.0, month_negative_share=.2))
    assert _verdicts(result) == (VERDICT_NOT_ENOUGH_DATA, VERDICT_NOT_ENOUGH_DATA)


def test_golden_short_term_score():
    """RatingEngineTest.goldenShortTermScore: "a backend port must produce exactly this"."""
    r = rate(RatingInputs(technical_bull=10, technical_bear=2, technical_total=14, call_upside_pct=5.0,
                          call_probability=.8)).horizon(HORIZON_SHORT)
    assert r.score == pytest.approx(.6769, abs=1e-4)
    assert r.verdict == VERDICT_BUY
    assert r.coverage == pytest.approx(.65, abs=1e-9)
    assert r.confidence == pytest.approx(.65, abs=1e-9)
    assert (r.score, r.coverage, r.confidence) == (0.676923076923077, 0.6499999999999999, 0.6499999999999999)


def test_high_volatility_pulls_towards_hold_and_lowers_confidence():
    calm = rate(BULLISH).horizon(HORIZON_SHORT)
    wild = rate(replace(BULLISH, volatility=70.0)).horizon(HORIZON_SHORT)
    assert wild.score < calm.score
    assert wild.confidence < calm.confidence


def test_drivers_rank_the_biggest_contributions_with_reasons():
    r = rate(BEARISH).horizon(HORIZON_SHORT)
    assert r.against[0].factor == FACTOR_TREND
    assert r.against[0].reason.strip()
    assert r.support == ()


def test_factor_scores_and_reasons_match_the_kotlin_engine():
    factors = {factor.factor: factor for factor in rate(BULLISH).factors}
    expected = {
        FACTOR_TREND: (0.8077232970003411,
                       "Technicals 12 bullish vs 1 bearish, 3M +15.0% vs NIFTY +2.0%, 89% up its 52-week range"),
        FACTOR_VALUATION: (0.7369655941662062, "P/E 12.0 vs sector 20.0, P/B 1.50 vs 2.50"),
        FACTOR_QUALITY: (0.6901673202334369, "ROE 22.0% vs sector 14.0%, ROCE 24.0% vs 15.0%"),
        FACTOR_GROWTH: (0.7954893320541174, "Revenue +18.0%, profit +25.0% year on year"),
        FACTOR_OWNERSHIP: (0.5370495669980353, "Promoters +0.5pp, FII +1.0pp last quarter"),
        FACTOR_SEASONALITY: (0.38997448112761246, "This month averages +2.0%, down in 30% of years"),
        FACTOR_RISK: (-0.05, "Volatility 22.0% (moderate)"),
    }
    for factor, (score, reason) in expected.items():
        assert (factors[factor].score, factors[factor].reason) == (pytest.approx(score, abs=KOTLIN), reason)
    up, down = rate(BULLISH), rate(BEARISH)
    short, long_ = up.horizon(HORIZON_SHORT), up.horizon(HORIZON_LONG)
    assert (short.score, short.confidence) == pytest.approx((0.7008636210190916, 0.975), abs=KOTLIN)
    assert (long_.score, long_.confidence) == pytest.approx((0.7144780056894133, 0.9749999999999999), abs=KOTLIN)
    assert (down.horizon(HORIZON_SHORT).score, down.horizon(HORIZON_LONG).score) == pytest.approx(
        (-0.7929252995119749, -0.8380807992504874), abs=KOTLIN
    )


def test_coverage_sums_left_to_right_like_kotlin():
    """Python 3.12's sum() compensates and Kotlin's sumOf does not: all six LONG weights come to 0.9999999999999999
    in Kotlin, and trend + growth + seasonality to exactly the 0.5 floor, which must still rate."""
    assert rate(BULLISH).horizon(HORIZON_LONG).coverage == 0.9999999999999999
    edge = rate(RatingInputs(technical_bull=10, technical_bear=2, technical_total=14, revenue_growth_yoy=10.0,
                             profit_growth_yoy=10.0, month_average=2.0, month_negative_share=.3)).horizon(HORIZON_SHORT)
    assert (edge.coverage, edge.verdict) == (0.5, VERDICT_BUY)
    assert edge.score == pytest.approx(0.5242066119515234, abs=KOTLIN)


def test_reasons_format_numbers_the_way_java_does():
    """String.format rounds the shortest decimal half-up and Math.round rounds halves up; Python's defaults do neither."""
    assert rate(RatingInputs(pb=1.005, sector_pb=2.0)).factors[0].reason == "P/B 1.01 vs 2.00"
    assert rate(RatingInputs(promoter_change=.25)).factors[0].reason == "Promoters +0.3pp last quarter"
    assert rate(RatingInputs(call_upside_pct=5.0, call_probability=.125)).factors[0].reason == (
        "Target +5.0% at 13% probability"
    )
```

- [ ] **Step 2: Run them to verify they fail**

Run: `python -m pytest tests/test_rating_engine.py -v`
Expected: FAIL with `ModuleNotFoundError: No module named 'app.rating_engine'`.

- [ ] **Step 3: Write the engine**

Create `app/rating_engine.py`:

```python
"""The Marksy rating engine (tip-ledger spec §7), ported from marksy-os `RatingEngine` and `RatingCalibration`.

Pure and float-only, like the Kotlin: it must produce the app's numbers exactly, so sums run left to right
(`sum_of`) rather than through `sum()`, which compensates since Python 3.12."""

from __future__ import annotations

import math
from dataclasses import dataclass, replace
from decimal import ROUND_HALF_UP, Decimal

HORIZON_SHORT = "SHORT"
HORIZON_LONG = "LONG"
HORIZONS = (HORIZON_SHORT, HORIZON_LONG)
HORIZON_SESSIONS = {HORIZON_SHORT: 20, HORIZON_LONG: 250}

VERDICT_BUY = "BUY"
VERDICT_HOLD = "HOLD"
VERDICT_SELL = "SELL"
VERDICT_NOT_ENOUGH_DATA = "NOT_ENOUGH_DATA"

FACTOR_TREND = "TREND"
FACTOR_MARKSY_CALL = "MARKSY_CALL"
FACTOR_VALUATION = "VALUATION"
FACTOR_QUALITY = "QUALITY"
FACTOR_GROWTH = "GROWTH"
FACTOR_OWNERSHIP = "OWNERSHIP"
FACTOR_RISK = "RISK"
FACTOR_SEASONALITY = "SEASONALITY"


@dataclass(frozen=True)
class RatingInputs:
    """Plain numbers the rating needs; any may be missing. Percentages are in percent, `call_probability` is 0..1."""

    price: float | None = None
    technical_bull: int | None = None
    technical_bear: int | None = None
    technical_total: int | None = None
    return_3m: float | None = None
    nifty_3m: float | None = None
    year_low: float | None = None
    year_high: float | None = None
    call_upside_pct: float | None = None
    call_probability: float | None = None
    pe: float | None = None
    sector_pe: float | None = None
    pb: float | None = None
    sector_pb: float | None = None
    ev_ebitda: float | None = None
    sector_ev_ebitda: float | None = None
    roe: float | None = None
    sector_roe: float | None = None
    roce: float | None = None
    sector_roce: float | None = None
    leverage: float | None = None
    revenue_growth_yoy: float | None = None
    profit_growth_yoy: float | None = None
    promoter_change: float | None = None
    fii_change: float | None = None
    mutual_fund_change: float | None = None
    volatility: float | None = None
    beta: float | None = None
    month_average: float | None = None
    month_negative_share: float | None = None


@dataclass(frozen=True)
class FactorScore:
    factor: str
    score: float
    reason: str


@dataclass(frozen=True)
class Contribution:
    factor: str
    weighted: float
    reason: str


@dataclass(frozen=True)
class HorizonRating:
    horizon: str
    verdict: str
    score: float
    confidence: float
    coverage: float
    support: tuple[Contribution, ...]
    against: tuple[Contribution, ...]


@dataclass(frozen=True)
class RatingResult:
    version: str
    factors: tuple[FactorScore, ...]
    ratings: tuple[HorizonRating, ...]

    def horizon(self, horizon: str) -> HorizonRating:
        return next(rating for rating in self.ratings if rating.horizon == horizon)


@dataclass(frozen=True)
class RatingConfig:
    """Weights per horizon (risk is not weighted: it shrinks the score and the confidence instead)."""

    version: str
    weights: dict[str, dict[str, float]]
    buy_at: float = .3
    sell_at: float = -.3
    min_coverage: float = .5
    risk_shrink: float = .3


V1 = RatingConfig(
    version="marksy-rating-1",
    weights={
        HORIZON_SHORT: {
            FACTOR_TREND: .35, FACTOR_MARKSY_CALL: .30, FACTOR_VALUATION: .05, FACTOR_QUALITY: .05,
            FACTOR_GROWTH: .05, FACTOR_OWNERSHIP: .10, FACTOR_SEASONALITY: .10,
        },
        HORIZON_LONG: {
            FACTOR_TREND: .10, FACTOR_MARKSY_CALL: .10, FACTOR_VALUATION: .25, FACTOR_QUALITY: .25,
            FACTOR_GROWTH: .20, FACTOR_OWNERSHIP: .10, FACTOR_SEASONALITY: .0,
        },
    },
)


def sum_of(values) -> float:
    """Kotlin's sumOf: plain left-to-right addition, so boundary sums (a 0.5 coverage) land on the same double."""
    total = 0.0
    for value in values:
        total += value
    return total


def average_of(values) -> float:
    """Kotlin's average(): the left-to-right sum over the count."""
    values = list(values)
    return sum_of(values) / len(values)


def _clamp(value: float, low: float = -1.0, high: float = 1.0) -> float:
    return min(max(value, low), high)


def _fixed(value: float, digits: int = 1) -> str:
    # Java's %.nf rounds the shortest decimal form half-up (2.675 -> 2.68); Python's format would give 2.67.
    return str(Decimal(repr(value)).quantize(Decimal(1).scaleb(-digits), rounding=ROUND_HALF_UP))


def _signed(value: float) -> str:
    text = _fixed(value)
    return text if text.startswith("-") else "+" + text


def _round(value: float) -> int:
    return math.floor(value + .5)


def _capitalized(text: str) -> str:
    return text[:1].upper() + text[1:]


def _sign(value: float) -> float:
    return 0.0 if value == 0 else math.copysign(1.0, value)


def _blend(*parts: tuple[float | None, float]) -> float | None:
    """Weighted mean of the parts that exist, re-scaled to the weights actually available."""
    present = [(value, weight) for value, weight in parts if value is not None]
    total = sum_of(weight for _, weight in present)
    if not present or total == 0.0:
        return None
    return sum_of(value * weight for value, weight in present) / total


def _trend(i: RatingInputs) -> FactorScore | None:
    technical = relative = None
    counts = (i.technical_bull, i.technical_bear, i.technical_total)
    if None not in counts and i.technical_total > 0:
        technical = (i.technical_bull - i.technical_bear) / i.technical_total
    if i.return_3m is not None and i.nifty_3m is not None:
        relative = math.tanh((i.return_3m - i.nifty_3m) / 10)
    position = None
    if i.price is not None and i.year_low is not None and i.year_high is not None and i.year_high > i.year_low:
        position = 2 * _clamp((i.price - i.year_low) / (i.year_high - i.year_low), 0.0, 1.0) - 1
    score = _blend((technical, .6), (relative, .3), (position, .1))
    if score is None:
        return None
    reasons = []
    if technical is not None:
        reasons.append(f"technicals {i.technical_bull} bullish vs {i.technical_bear} bearish")
    if relative is not None:
        reasons.append(f"3M {_signed(i.return_3m)}% vs NIFTY {_signed(i.nifty_3m)}%")
    if position is not None:
        reasons.append(f"{_round((position + 1) / 2 * 100)}% up its 52-week range")
    return FactorScore(FACTOR_TREND, _clamp(score), _capitalized(", ".join(reasons)))


def _call(i: RatingInputs) -> FactorScore | None:
    if i.call_upside_pct is None or i.call_probability is None:
        return None
    probability = _clamp(i.call_probability, 0.0, 1.0)
    return FactorScore(
        FACTOR_MARKSY_CALL, _sign(i.call_upside_pct) * probability,
        f"Target {_signed(i.call_upside_pct)}% at {_round(probability * 100)}% probability",
    )


def _relative_multiple(company: float | None, sector: float | None, *, allow_loss: bool) -> float | None:
    # Half the sector multiple scores +1, double scores -1; a loss-making P/E is -1.
    if company is None or sector is None or sector <= 0:
        return None
    if company <= 0:
        return -1.0 if allow_loss else None
    return _clamp(-math.log(company / sector) / math.log(2.0))


def _valuation(i: RatingInputs) -> FactorScore | None:
    pe = _relative_multiple(i.pe, i.sector_pe, allow_loss=True)
    pb = _relative_multiple(i.pb, i.sector_pb, allow_loss=False)
    ev = _relative_multiple(i.ev_ebitda, i.sector_ev_ebitda, allow_loss=False)
    score = _blend((pe, 1.0), (pb, 1.0), (ev, 1.0))
    if score is None:
        return None
    reasons = []
    if pe is not None:
        reasons.append(f"P/E {_fixed(i.pe)} vs sector {_fixed(i.sector_pe)}")
    if pb is not None:
        reasons.append(f"P/B {_fixed(i.pb, 2)} vs {_fixed(i.sector_pb, 2)}")
    if ev is not None:
        reasons.append(f"EV/EBITDA {_fixed(i.ev_ebitda)} vs {_fixed(i.sector_ev_ebitda)}")
    return FactorScore(FACTOR_VALUATION, score, ", ".join(reasons))


def _quality(i: RatingInputs) -> FactorScore | None:
    roe = math.tanh((i.roe - i.sector_roe) / 10) if i.roe is not None and i.sector_roe is not None else None
    roce = math.tanh((i.roce - i.sector_roce) / 10) if i.roce is not None and i.sector_roce is not None else None
    score = _blend((roe, 1.0), (roce, 1.0))
    if score is None:
        return None
    leveraged = i.leverage is not None and i.leverage > 3
    if leveraged:
        score -= .3
    reasons = []
    if roe is not None:
        reasons.append(f"ROE {_fixed(i.roe)}% vs sector {_fixed(i.sector_roe)}%")
    if roce is not None:
        reasons.append(f"ROCE {_fixed(i.roce)}% vs {_fixed(i.sector_roce)}%")
    if leveraged:
        reasons.append(f"high leverage {_fixed(i.leverage, 2)}×")
    return FactorScore(FACTOR_QUALITY, _clamp(score), ", ".join(reasons))


def _growth(i: RatingInputs) -> FactorScore | None:
    revenue = math.tanh(i.revenue_growth_yoy / 20) if i.revenue_growth_yoy is not None else None
    profit = math.tanh(i.profit_growth_yoy / 20) if i.profit_growth_yoy is not None else None
    score = _blend((revenue, .4), (profit, .6))
    if score is None:
        return None
    reasons = []
    if revenue is not None:
        reasons.append(f"revenue {_signed(i.revenue_growth_yoy)}%")
    if profit is not None:
        reasons.append(f"profit {_signed(i.profit_growth_yoy)}%")
    return FactorScore(FACTOR_GROWTH, score, _capitalized(", ".join(reasons)) + " year on year")


def _ownership(i: RatingInputs) -> FactorScore | None:
    parts = [value * weight for value, weight in
             ((i.promoter_change, 1.0), (i.fii_change, .7), (i.mutual_fund_change, .7)) if value is not None]
    if not parts:
        return None
    reasons = []
    if i.promoter_change is not None:
        reasons.append(f"promoters {_signed(i.promoter_change)}pp")
    if i.fii_change is not None:
        reasons.append(f"FII {_signed(i.fii_change)}pp")
    if i.mutual_fund_change is not None:
        reasons.append(f"mutual funds {_signed(i.mutual_fund_change)}pp")
    reason = _capitalized(", ".join(reasons)) + " last quarter"
    return FactorScore(FACTOR_OWNERSHIP, math.tanh(sum_of(parts) / 2), reason)


def _seasonality(i: RatingInputs) -> FactorScore | None:
    if i.month_average is None or i.month_negative_share is None:
        return None
    score = _clamp(math.tanh(i.month_average / 5) * .5 + (.5 - i.month_negative_share))
    return FactorScore(
        FACTOR_SEASONALITY, score,
        f"This month averages {_signed(i.month_average)}%, down in {_round(i.month_negative_share * 100)}% of years",
    )


def _risk(i: RatingInputs) -> FactorScore | None:
    """Score is minus the risk penalty (0 calm .. 1 very volatile); it never adds direction."""
    if i.volatility is None:
        return None
    penalty = _clamp((i.volatility - 20) / 40, 0.0, 1.0)
    band = "low" if penalty == 0.0 else "moderate" if penalty < .5 else "high"
    beta = f", beta {_fixed(i.beta, 2)}" if i.beta is not None else ""
    return FactorScore(FACTOR_RISK, -penalty, f"Volatility {_fixed(i.volatility)}% ({band})" + beta)


def _horizon(horizon: str, factors: list[FactorScore], penalty: float, config: RatingConfig) -> HorizonRating:
    weights = config.weights[horizon]
    used = [(factor, weights[factor.factor]) for factor in factors if weights.get(factor.factor, 0) > 0]
    coverage = sum_of(weight for _, weight in used)
    if coverage < config.min_coverage or coverage == 0.0:
        return HorizonRating(horizon, VERDICT_NOT_ENOUGH_DATA, 0.0, 0.0, coverage, (), ())
    weighted = [Contribution(factor.factor, weight * factor.score / coverage, factor.reason) for factor, weight in used]
    raw = sum_of(c.weighted for c in weighted)
    score = raw * (1 - config.risk_shrink * penalty)
    spread = sum_of(abs(c.weighted) for c in weighted)
    agreement = 0.0 if spread == 0.0 else abs(raw) / spread
    verdict = VERDICT_BUY if score >= config.buy_at else VERDICT_SELL if score <= config.sell_at else VERDICT_HOLD
    support = sorted((c for c in weighted if c.weighted > 0), key=lambda c: -c.weighted)[:3]
    against = sorted((c for c in weighted if c.weighted < 0), key=lambda c: c.weighted)[:3]
    return HorizonRating(horizon, verdict, score, coverage * agreement * (1 - .5 * penalty), coverage,
                         tuple(support), tuple(against))


def rate(inputs: RatingInputs, config: RatingConfig = V1) -> RatingResult:
    factors = [f for f in (_trend(inputs), _call(inputs), _valuation(inputs), _quality(inputs), _growth(inputs),
                           _ownership(inputs), _seasonality(inputs)) if f is not None]
    risk = _risk(inputs)
    penalty = -risk.score if risk is not None else 0.0
    ratings = tuple(_horizon(horizon, factors, penalty, config) for horizon in HORIZONS)
    return RatingResult(config.version, tuple(factors) + ((risk,) if risk is not None else ()), ratings)
```

`replace` is imported now so that Task 3 only appends code.

- [ ] **Step 4: Run the tests**

Run: `python -m pytest tests/test_rating_engine.py -v`
Expected: all 9 PASS. If a pinned figure misses, the port differs from the Kotlin. Diff the function against `RatingEngine.kt` (sum order, rounding, clamping); don't edit the figure.

- [ ] **Step 5: Commit**

```bash
git add app/rating_engine.py tests/test_rating_engine.py
git commit -m "Rating engine: port RatingEngine with the Kotlin engine's own figures (spec 7)

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 3: Port `RatingCalibration`

**Files:**
- Modify: `app/rating_engine.py` (append)
- Test: `tests/test_rating_engine.py` (append)

**Interfaces:**
- Consumes (Task 2): `rate`, `RatingInputs`, `RatingConfig`, `V1`, `sum_of`, `average_of` and `_round`.
- Produces:
  - `MIN_GAIN = .02` and `MIN_TEST = 30`
  - `@dataclass(frozen=True) Sample(inputs: RatingInputs, realized_return: float)`
  - `@dataclass(frozen=True) CalibrationResult(config: RatingConfig, adopted: bool, train: float, test: float, base_test: float, samples: int, test_samples: int)`
  - `spearman(a: list[float], b: list[float]) -> float`
  - `calibration_score(sample: Sample, config: RatingConfig) -> float`
  - `calibrate(samples: list[Sample], base: RatingConfig = V1, version: str | None = None, step: float = .05) -> CalibrationResult`. `version` defaults to `base.version + "-cal"`.

- [ ] **Step 1: Write the failing tests**

In `tests/test_rating_engine.py`:
- add `import math` above `from dataclasses import replace`;
- add `V1`, `Sample`, `calibrate` and `spearman` to the `from app.rating_engine import (...)` block.

Then append:

```python
_MASK = 0xFFFFFFFF


class KotlinRandom:
    """kotlin.random.Random(seed): XorWow, so these samples are RatingCalibrationTest's own, draw for draw."""

    def __init__(self, seed: int):
        low, high = seed & _MASK, (seed >> 31) & _MASK
        self.x, self.y, self.z, self.w = low, high, 0, 0
        self.v = ~low & _MASK
        self.addend = ((low << 10) ^ (high >> 4)) & _MASK
        for _ in range(64):
            self._next_int()

    def _next_int(self) -> int:
        t = self.x
        t ^= t >> 2
        self.x, self.y, self.z = self.y, self.z, self.w
        v0 = self.v
        self.w = v0
        t = (t ^ (t << 1) ^ v0 ^ (v0 << 4)) & _MASK
        self.v = t
        self.addend = (self.addend + 362437) & _MASK
        return (t + self.addend) & _MASK

    def _bits(self, count: int) -> int:
        return self._next_int() >> (32 - count)

    def next_double(self, start: float, until: float) -> float:
        fraction = ((self._bits(26) << 27) + self._bits(27)) / float(1 << 53)
        value = start + fraction * (until - start)
        return math.nextafter(until, -math.inf) if value >= until else value


def _sample(trend, season, realized):
    """RatingCalibrationTest.sample: trend rides the technical counts, seasonality the month average."""
    inputs = RatingInputs(price=100.0, technical_bull=int(5 + 5 * trend), technical_bear=int(5 - 5 * trend),
                          technical_total=10, month_average=season * 4, month_negative_share=.5 - season / 2,
                          call_upside_pct=5.0)
    return Sample(inputs, realized)


def test_kotlin_random_draws_are_reproduced():
    r = KotlinRandom(7)
    assert [r.next_double(-1.0, 1.0), r.next_double(-1.0, 1.0), r.next_double(-.01, .01)] == [
        0.9151042998662875, 0.09594066061255191, -0.00273159393543752,
    ]


def test_weight_moves_to_the_factor_that_predicts_and_is_adopted_only_with_a_held_out_gain():
    r = KotlinRandom(7)
    samples = []
    for _ in range(300):
        t, s = r.next_double(-1.0, 1.0), r.next_double(-1.0, 1.0)
        samples.append(_sample(t, s, s * .03 + r.next_double(-.01, .01)))

    result = calibrate(samples)

    assert result.adopted
    weights = result.config.weights[HORIZON_SHORT]
    assert weights[FACTOR_SEASONALITY] > V1.weights[HORIZON_SHORT][FACTOR_SEASONALITY]
    assert result.test > result.base_test
    assert result.config.weights[HORIZON_LONG] == V1.weights[HORIZON_LONG]
    # The compiled Kotlin calibration's own result on these samples.
    assert (weights[FACTOR_TREND], weights[FACTOR_SEASONALITY], result.config.version) == (0.0, 0.45, "marksy-rating-1-cal")
    assert (result.samples, result.test_samples) == (300, 90)
    assert (result.train, result.test, result.base_test) == pytest.approx(
        (0.9467004434826835, 0.9399432028645511, 0.3162777297608758), abs=1e-12
    )


def test_noise_keeps_the_current_weights():
    r = KotlinRandom(11)
    samples = [_sample(r.next_double(-1.0, 1.0), r.next_double(-1.0, 1.0), r.next_double(-.03, .03)) for _ in range(300)]

    result = calibrate(samples)

    assert result.adopted is False
    assert result.config == V1
    assert (result.test, result.base_test) == pytest.approx((0.058405875532650135, 0.07106227106227106), abs=1e-12)


def test_spearman_of_perfect_order_is_one_and_ties_share_a_rank():
    assert spearman([1.0, 2.0, 3.0], [10.0, 20.0, 30.0]) == pytest.approx(1.0, abs=1e-9)
    assert spearman([1.0, 2.0, 3.0], [3.0, 2.0, 1.0]) == pytest.approx(-1.0, abs=1e-9)
    assert spearman([1.0, 2.0, 2.0, 4.0], [1.0, 3.0, 2.0, 4.0]) == pytest.approx(0.9486832980505138, abs=1e-12)
```

- [ ] **Step 2: Run them to verify they fail**

Run: `python -m pytest tests/test_rating_engine.py -v`
Expected: FAIL with `ImportError: cannot import name 'Sample' from 'app.rating_engine'`.

- [ ] **Step 3: Append the calibration**

Append to `app/rating_engine.py`:

```python
# Point-in-time data exists only for trend and seasonality, so calibration tunes only those two.
_TUNED = (FACTOR_TREND, FACTOR_SEASONALITY)
MIN_GAIN = .02
MIN_TEST = 30


@dataclass(frozen=True)
class Sample:
    """Inputs as they stood on the call's date (no later data) and the return the call went on to make."""

    inputs: RatingInputs
    realized_return: float


@dataclass(frozen=True)
class CalibrationResult:
    config: RatingConfig
    adopted: bool
    train: float
    test: float
    base_test: float
    samples: int
    test_samples: int


def _ranks(values: list[float]) -> list[float]:
    """Average ranks, so ties share their rank."""
    order = sorted(range(len(values)), key=lambda index: values[index])
    ranks = [0.0] * len(values)
    i = 0
    while i < len(order):
        j = i
        while j + 1 < len(order) and values[order[j + 1]] == values[order[i]]:
            j += 1
        for k in range(i, j + 1):
            ranks[order[k]] = (i + j) / 2.0
        i = j + 1
    return ranks


def spearman(a: list[float], b: list[float]) -> float:
    if len(a) != len(b) or len(a) < 3:
        return 0.0
    ra, rb = _ranks(a), _ranks(b)
    ma, mb = average_of(ra), average_of(rb)
    cov = sum_of((ra[i] - ma) * (rb[i] - mb) for i in range(len(ra)))
    va = sum_of((r - ma) * (r - ma) for r in ra)
    vb = sum_of((r - mb) * (r - mb) for r in rb)
    return 0.0 if va == 0.0 or vb == 0.0 else cov / math.sqrt(va * vb)


def calibration_score(sample: Sample, config: RatingConfig) -> float:
    """The short-term score without the coverage floor: past calls lack fundamentals, so V1 would say NOT_ENOUGH_DATA."""
    return rate(sample.inputs, replace(config, min_coverage=0.0)).horizon(HORIZON_SHORT).score


def calibrate(samples: list[Sample], base: RatingConfig = V1, version: str | None = None,
              step: float = .05) -> CalibrationResult:
    """`samples` oldest first: the newest 30% are held out, so the check runs on calls after those it was tuned on."""
    version = version if version is not None else base.version + "-cal"
    split = int(len(samples) * .7)
    train, test = samples[:split], samples[split:]

    def metric(subset: list[Sample], config: RatingConfig) -> float:
        return spearman([calibration_score(s, config) for s in subset], [s.realized_return for s in subset])

    base_short = base.weights[HORIZON_SHORT]
    steps = _round(sum_of(base_short.get(factor, 0.0) for factor in _TUNED) / step)
    best, best_train = base, metric(train, base)
    for t in range(steps + 1):
        weights = {**base_short, FACTOR_TREND: t * step, FACTOR_SEASONALITY: (steps - t) * step}
        config = replace(base, version=version, weights={**base.weights, HORIZON_SHORT: weights})
        m = metric(train, config)
        if m > best_train + 1e-9:
            best, best_train = config, m
    base_test = metric(test, base)
    best_test = metric(test, best)
    # A gain inside the noise of the held-out sample (about 1/sqrt(n) for a rank correlation) is not evidence.
    needed = max(MIN_GAIN, 1.0 / math.sqrt(len(test))) if test else math.inf
    adopted = best != base and len(test) >= MIN_TEST and best_test > 0 and best_test > base_test + needed
    return CalibrationResult(best if adopted else base, adopted, best_train, best_test, base_test, len(samples),
                             len(test))
```

- [ ] **Step 4: Run the tests**

Run: `python -m pytest tests/test_rating_engine.py -v`
Expected: all 13 PASS.

- [ ] **Step 5: Commit**

```bash
git add app/rating_engine.py tests/test_rating_engine.py
git commit -m "Rating engine: port RatingCalibration; Kotlin's Random reproduces its test samples

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 4: Price-history inputs (`app/rating_inputs.py`)

**Files:**
- Create: `app/rating_inputs.py`
- Test: `tests/test_rating_inputs.py`

**Interfaces:**
- Consumes (Task 2): `RatingInputs`, `sum_of` and `average_of`.
- Produces:
  - `WINDOW_DAYS = 365`, `MIN_WINDOW_BARS = 30` and `MIN_MONTHS = 13`
  - `@dataclass(frozen=True) Bar(day: date, open: float, high: float, low: float, close: float)`
  - `technical_counts(bars: list[Bar]) -> tuple[int, int, int] | None` (bullish, bearish, total)
  - `volatility(bars: list[Bar]) -> float | None` and `beta(stock: list[Bar], index: list[Bar]) -> float | None`
  - `three_month_return(bars: list[Bar], last_price: float) -> float | None`
  - `monthly_bars(daily: list[Bar]) -> list[Bar]`
  - `month_summary(monthly: list[Bar], month: int) -> tuple[float, float] | None` (average, share of years down)
  - `price_inputs(daily: list[Bar], index: list[Bar], *, on: date) -> RatingInputs | None`. Bars after `on` are ignored. It returns None when there are fewer than 30 bars in the window.

**Rules this task implements:** the Kotlin helpers named in each function's docstring, ported line for line (`TechnicalRating.kt:37-221`, `UpstoxMarketData.kt:85-100`, `Seasonality.kt:24-44`), plus decision 8's windows.

- [ ] **Step 1: Write the failing tests**

Create `tests/test_rating_inputs.py`:

```python
"""Rating inputs from official daily bars (tip-ledger spec §7), pinned to the marksy-os helpers' own figures."""
from __future__ import annotations

from datetime import date, timedelta

import pytest

from app.rating_inputs import Bar, price_inputs

KOTLIN = 1e-9


def _walk(n, start, first, mul, mod, drift):
    """Weekday bars from a deterministic walk; the same formula fed the compiled Kotlin helpers."""
    bars, day, prev = [], start, first
    for i in range(n):
        while day.weekday() >= 5:
            day += timedelta(days=1)
        step = (((i * mul) % mod) - (mod - 1) / 2.0 + drift) / 1000.0
        close = prev * (1 + step)
        bars.append(Bar(day, prev, max(prev, close) * 1.004, min(prev, close) * 0.995, close))
        prev = close
        day += timedelta(days=1)
    return bars


DAILY = _walk(800, date(2023, 6, 5), 100.0, 7919, 41, 0.6)
NIFTY = _walk(800, date(2023, 6, 5), 20000.0, 104729, 31, 0.3)
ON = date(2026, 6, 29)  # the Monday after the last bar (Friday 2026-06-26)


def test_price_inputs_match_the_app_helpers_on_the_same_bars():
    """Technicals.rate/volatility/beta, UpstoxCandles.returns/range and Seasonality.summary, run by the compiled
    marksy-os classes on these bars (2026-09-29)."""
    inputs = price_inputs(DAILY, NIFTY, on=ON)

    assert (inputs.technical_bull, inputs.technical_bear, inputs.technical_total) == (11, 3, 17)
    assert (inputs.price, inputs.return_3m, inputs.nifty_3m) == pytest.approx(
        (149.6106807977666, 4.1259389736431356, 1.8079124447516415), abs=KOTLIN
    )
    assert (inputs.year_low, inputs.year_high) == pytest.approx((125.76434624010288, 151.70693040280898), abs=KOTLIN)
    assert (inputs.volatility, inputs.beta) == pytest.approx((18.76803431819198, 0.06037498133805317), abs=KOTLIN)
    assert (inputs.month_average, inputs.month_negative_share) == pytest.approx((-0.8036620916364413, 2 / 3), abs=KOTLIN)


def test_bars_after_the_rating_date_are_never_read():
    mid = date(2025, 1, 15)
    before = [bar for bar in DAILY if bar.day <= mid], [bar for bar in NIFTY if bar.day <= mid]
    assert price_inputs(DAILY, NIFTY, on=mid) == price_inputs(*before, on=mid)


def test_seasonality_needs_thirteen_completed_months_and_the_rest_needs_thirty_bars():
    assert price_inputs(DAILY[-300:], NIFTY[-300:], on=ON).month_average is not None
    assert price_inputs(DAILY[-260:], NIFTY[-260:], on=ON).month_average is None
    assert price_inputs(DAILY[:29], NIFTY[:29], on=DAILY[28].day) is None
```

- [ ] **Step 2: Run them to verify they fail**

Run: `python -m pytest tests/test_rating_inputs.py -v`
Expected: FAIL with `ModuleNotFoundError: No module named 'app.rating_inputs'`.

- [ ] **Step 3: Write the inputs**

Create `app/rating_inputs.py`:

```python
"""The rating engine's price-history inputs (tip-ledger spec §7), from official daily bars.

Pure ports of the marksy-os helpers `StockRatingInputs` reads: `Technicals.rate/volatility/beta`,
`UpstoxCandles.returns/range` and `Seasonality.table/summary`. Monthly bars are derived from the daily ones and
never stored. Float arithmetic in Kotlin's order, so the counts and figures equal the app's."""

from __future__ import annotations

import math
from dataclasses import dataclass
from datetime import date, timedelta

from .rating_engine import RatingInputs, average_of, sum_of

WINDOW_DAYS = 365
MIN_WINDOW_BARS = 30
MIN_MONTHS = 13
_MA_PERIODS = (5, 10, 20, 50, 100, 200)
_CROSSES = ((5, 20), (20, 50), (50, 200))
_MIN_CANDLES = 20
_BULLISH, _NEUTRAL, _BEARISH = 1, 0, -1


@dataclass(frozen=True)
class Bar:
    day: date
    open: float
    high: float
    low: float
    close: float


def _sma(values: list[float], n: int) -> float | None:
    return None if len(values) < n or n <= 0 else average_of(values[-n:])


def _ema_series(values: list[float], n: int) -> list[float]:
    if len(values) < n:
        return []
    k = 2.0 / (n + 1)
    out = [average_of(values[:n])]
    for value in values[n:]:
        out.append(value * k + out[-1] * (1 - k))
    return out


def _rsi_series(closes: list[float], n: int = 14) -> list[float]:
    if len(closes) <= n:
        return []
    diffs = [b - a for a, b in zip(closes, closes[1:])]
    gain = sum_of(max(d, 0.0) for d in diffs[:n]) / n
    loss = sum_of(max(-d, 0.0) for d in diffs[:n]) / n
    out = [100.0 if loss == 0.0 else 100 - 100 / (1 + gain / loss)]
    for d in diffs[n:]:
        gain = (gain * (n - 1) + max(d, 0.0)) / n
        loss = (loss * (n - 1) + max(-d, 0.0)) / n
        out.append(100.0 if loss == 0.0 else 100 - 100 / (1 + gain / loss))
    return out


def _macd(closes: list[float]) -> tuple[float, float] | None:
    fast, slow = _ema_series(closes, 12), _ema_series(closes, 26)
    if not slow:
        return None
    line = [fast[i + 14] - slow[i] for i in range(len(slow))]
    signal = _ema_series(line, 9)
    return (line[-1], signal[-1]) if signal else None


def _fast_k(bars: list[Bar], end: int, n: int) -> float | None:
    if end + 1 < n:
        return None
    window = bars[end + 1 - n:end + 1]
    high, low = max(b.high for b in window), min(b.low for b in window)
    return (bars[end].close - low) / (high - low) * 100 if high > low else None


def _stochastic(bars: list[Bar], n: int = 14, smooth: int = 3) -> float | None:
    ks = [None if index < 0 else _fast_k(bars, index, n) for index in range(len(bars) - smooth, len(bars))]
    return None if any(k is None for k in ks) else average_of(ks)


def _stoch_rsi(closes: list[float], n: int = 14) -> float | None:
    r = _rsi_series(closes, n)[-n:]
    if len(r) != n:
        return None
    high, low = max(r), min(r)
    return (r[-1] - low) / (high - low) * 100 if high > low else None


def _williams_r(bars: list[Bar], n: int = 14) -> float | None:
    k = _fast_k(bars, len(bars) - 1, n)
    return None if k is None else k - 100


def _roc(closes: list[float], n: int = 12) -> float | None:
    if len(closes) <= n:
        return None
    base = closes[-1 - n]
    return (closes[-1] - base) / base * 100 if base > 0 else None


def _true_range(bars: list[Bar], i: int) -> float:
    return max(bars[i].high, bars[i - 1].close) - min(bars[i].low, bars[i - 1].close)


def _adx(bars: list[Bar], n: int = 14) -> tuple[float, float, float] | None:
    if len(bars) < 2 * n + 1:
        return None
    tr = plus = minus = adx = pdi = mdi = 0.0
    dxs: list[float] = []
    for i in range(1, len(bars)):
        up = bars[i].high - bars[i - 1].high
        down = bars[i - 1].low - bars[i].low
        pdm = up if up > down and up > 0 else 0.0
        mdm = down if down > up and down > 0 else 0.0
        if i <= n:
            tr += _true_range(bars, i)
            plus += pdm
            minus += mdm
        else:
            tr = tr - tr / n + _true_range(bars, i)
            plus = plus - plus / n + pdm
            minus = minus - minus / n + mdm
        if i < n:
            continue
        pdi = 100 * plus / tr if tr > 0 else 0.0
        mdi = 100 * minus / tr if tr > 0 else 0.0
        dx = 100 * abs(pdi - mdi) / (pdi + mdi) if pdi + mdi > 0 else 0.0
        if len(dxs) < n:
            dxs.append(dx)
            if len(dxs) == n:
                adx = average_of(dxs)
        else:
            adx = (adx * (n - 1) + dx) / n
    return None if len(dxs) < n else (adx, pdi, mdi)


def _ultimate(bars: list[Bar]) -> float | None:
    if len(bars) < 29:
        return None

    def average(n: int) -> float | None:
        indices = range(len(bars) - n, len(bars))
        bp = sum_of(bars[i].close - min(bars[i].low, bars[i - 1].close) for i in indices)
        tr = sum_of(_true_range(bars, i) for i in indices)
        return bp / tr if tr > 0 else None

    a, b, d = average(7), average(14), average(28)
    if a is None or b is None or d is None:
        return None
    return 100 * (4 * a + 2 * b + d) / 7


def _band(value: float, bull_below: float, bear_above: float) -> int:
    return _BULLISH if value < bull_below else _BEARISH if value > bear_above else _NEUTRAL


def technical_counts(bars: list[Bar]) -> tuple[int, int, int] | None:
    """`Technicals.rate(daily).all`: (bullish, bearish, total) readings; None under 20 bars."""
    if len(bars) < _MIN_CANDLES:
        return None
    closes = [b.close for b in bars]
    price = closes[-1]
    signals = []
    for n in _MA_PERIODS:
        s = _sma(closes, n)
        if s is not None:
            signals.append(_BULLISH if price >= s else _BEARISH)
    rsi = _rsi_series(closes)
    if rsi:
        signals.append(_band(rsi[-1], 30.0, 70.0))
    macd = _macd(closes)
    if macd is not None:
        signals.append(_BULLISH if macd[0] >= macd[1] else _BEARISH)
    stochastic = _stochastic(bars)
    if stochastic is not None:
        signals.append(_band(stochastic, 20.0, 80.0))
    stoch_rsi = _stoch_rsi(closes)
    if stoch_rsi is not None:
        signals.append(_band(stoch_rsi, 20.0, 80.0))
    williams = _williams_r(bars)
    if williams is not None:
        signals.append(_band(williams, -80.0, -20.0))
    roc = _roc(closes)
    if roc is not None:
        signals.append(_BULLISH if roc >= 0 else _BEARISH)
    adx = _adx(bars)
    if adx is not None:
        a, p, m = adx
        signals.append(_NEUTRAL if a < 25 else _BULLISH if p >= m else _BEARISH)
    ultimate = _ultimate(bars)
    if ultimate is not None:
        signals.append(_band(ultimate, 30.0, 70.0))
    for fast_n, slow_n in _CROSSES:
        fast, slow = _sma(closes, fast_n), _sma(closes, slow_n)
        if fast is not None and slow is not None:
            signals.append(_BULLISH if fast >= slow else _BEARISH)
    return signals.count(_BULLISH), signals.count(_BEARISH), len(signals)


def volatility(bars: list[Bar]) -> float | None:
    """Annualised % standard deviation of daily log returns over the last 253 bars."""
    tail = bars[-253:]
    r = [math.log(b.close / a.close) for a, b in zip(tail, tail[1:]) if a.close > 0 and b.close > 0]
    if len(r) < 20:
        return None
    mean = average_of(r)
    return math.sqrt(sum_of((x - mean) * (x - mean) for x in r) / (len(r) - 1)) * math.sqrt(252.0) * 100


def beta(stock: list[Bar], index: list[Bar]) -> float | None:
    """Slope of the stock's daily returns on the index's, over the sessions both have."""
    def returns(bars: list[Bar]) -> dict[date, float]:
        return {b.day: (b.close - a.close) / a.close for a, b in zip(bars, bars[1:]) if a.close > 0}

    s, m = returns(stock), returns(index)
    days = [day for day in s if day in m]
    if len(days) < 20:
        return None
    xs, ys = [m[day] for day in days], [s[day] for day in days]
    mx, my = average_of(xs), average_of(ys)
    variance = sum_of((x - mx) * (x - mx) for x in xs)
    return None if variance == 0.0 else sum_of((xs[i] - mx) * (ys[i] - my) for i in range(len(xs))) / variance


def three_month_return(bars: list[Bar], last_price: float) -> float | None:
    """`UpstoxCandles.returns(...)["3M"]`: % change from the close on or before 91 days back."""
    if not bars:
        return None
    start = bars[-1].day - timedelta(days=91)
    on_or_before = [b for b in bars if b.day <= start]
    base = on_or_before[-1] if on_or_before else (bars[0] if (bars[0].day - start).days <= 5 else None)
    if base is None or not base.close > 0:
        return None
    return (last_price - base.close) / base.close * 100


def monthly_bars(daily: list[Bar]) -> list[Bar]:
    """Calendar-month bars from daily ones (first open, last close), oldest first; derived, never stored."""
    months: dict[tuple[int, int], list[Bar]] = {}
    for bar in daily:
        months.setdefault((bar.day.year, bar.day.month), []).append(bar)
    return [
        Bar(bars[0].day, bars[0].open, max(b.high for b in bars), min(b.low for b in bars), bars[-1].close)
        for bars in months.values()
    ]


def _previous_month(year: int, month: int) -> tuple[int, int]:
    return (year, month - 1) if month > 1 else (year - 1, 12)


def month_summary(monthly: list[Bar], month: int) -> tuple[float, float] | None:
    """`Seasonality.summary(table(monthly), month)`: (average % change, share of years down), or None."""
    table: dict[int, list[float | None]] = {}
    for i, bar in enumerate(monthly):
        key = (bar.day.year, bar.day.month)
        previous = monthly[i - 1] if i > 0 else None
        follows = previous is not None and (previous.day.year, previous.day.month) == _previous_month(*key)
        # A gap in the history falls back to the month's own open, as Seasonality.table does.
        base = previous.close if follows else bar.open
        if base > 0:
            table.setdefault(bar.day.year, [None] * 12)[bar.day.month - 1] = (bar.close - base) / base * 100
    values = [table[year][month - 1] for year in sorted(table, reverse=True) if table[year][month - 1] is not None]
    if not values:
        return None
    negative = sum(1 for value in values if value < 0)
    return average_of(values), negative / len(values)


def price_inputs(daily: list[Bar], index: list[Bar], *, on: date) -> RatingInputs | None:
    """The price-derived inputs as they stood on `on`: a year of official bars up to it, NIFTY 50 over the same
    year, and the completed months before it. `daily` and `index` are oldest first; bars after `on` are ignored."""
    since = on - timedelta(days=WINDOW_DAYS)
    window = [b for b in daily if since < b.day <= on]
    if len(window) < MIN_WINDOW_BARS:
        return None
    benchmark = [b for b in index if since < b.day <= on]
    price = window[-1].close
    counts = technical_counts(window)
    low, high = min(b.low for b in window), max(b.high for b in window)
    months = monthly_bars([b for b in daily if b.day < on.replace(day=1)])
    season = month_summary(months, on.month) if len(months) >= MIN_MONTHS else None
    return RatingInputs(
        price=price,
        technical_bull=counts[0] if counts else None,
        technical_bear=counts[1] if counts else None,
        technical_total=counts[2] if counts else None,
        return_3m=three_month_return(window, price),
        nifty_3m=three_month_return(benchmark, benchmark[-1].close) if benchmark else None,
        year_low=low,
        year_high=high,
        volatility=volatility(window),
        beta=beta(window, benchmark),
        month_average=season[0] if season else None,
        month_negative_share=season[1] if season else None,
    )
```

The years are summed newest first in `month_summary` because Kotlin's table is `sortedMapOf(compareByDescending)`. The order changes the float sum.

- [ ] **Step 4: Run the tests**

Run: `python -m pytest tests/test_rating_inputs.py tests/test_rating_engine.py -v`
Expected: all PASS.

- [ ] **Step 5: Commit**

```bash
git add app/rating_inputs.py tests/test_rating_inputs.py
git commit -m "Rating engine: price-history inputs from official daily bars (ported technicals, returns, seasonality)

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 5: The Rating engine's tips in the ledger (`app/marksy_tips.py`) and `GET /tips`

**Files:**
- Modify: `app/marksy_tips.py` (imports; append)
- Modify: `api/services/tips.py` (`_external_tips_only`, imports)
- Test: `tests/test_marksy_rating_tips.py`

**Interfaces:**
- Consumes:
  - from 2b: `marksy_channel(session) -> Channel`;
  - from `app/tip_ledger.py`: `_lock_match_key`, `find_matchable` and `resolve_caller`;
  - from `app/tip_matching.py`: `stated_terms`, `match_key_v1` and `MATCH_KEY_VERSION`;
  - from 2a: `track_tips(session, *, now, minute_bars, provisional=False)`.
- Produces, in `app/marksy_tips.py`:
  - `RATING_TIP_OPENED = "opened"`, `RATING_TIP_KEPT = "kept"`, `RATING_TIP_EXITED = "exited"` and `RATING_TIP_PENDING_EXIT = "pendingExit"`
  - `rating_tips(session: Session, stock_id: int, horizon_sessions: int) -> list[Tip]`
  - `withdraw_rating_tip(session: Session, tip: Tip, *, at: datetime) -> TipSourceExit | None`
  - `open_rating_tip(session: Session, stock: Stock, horizon_sessions: int, direction: str, *, at: datetime, session_anchor: datetime) -> tuple[Tip | None, str]`
  - `apply_rating_verdict(session: Session, stock: Stock, horizon_sessions: int, verdict: str, *, at: datetime, session_anchor: datetime) -> tuple[Tip | None, list[str]]`. It flushes and never commits.
- Produces: `_external_tips_only()` keeps its signature and now also excludes `parser_version = 'RTG-001'`.

**Rules this task implements:** decision 3 and spec §7's lifecycle.

- [ ] **Step 1: Write the failing tests**

Create `tests/test_marksy_rating_tips.py`:

```python
"""The Rating engine's calls in the tip ledger (tip-ledger spec §7): direction-only tips; a changed verdict is an exit."""
from __future__ import annotations

from datetime import date, datetime, time, timezone
from decimal import Decimal

import pytest
from sqlalchemy import create_engine, select
from sqlalchemy.orm import sessionmaker

from api.services.tips import TipQuery, list_tips
from app.db import Base
from app.market_data.quality import NSE_TIMEZONE
from app.marksy_tips import (
    RATING_TIP_EXITED,
    RATING_TIP_KEPT,
    RATING_TIP_OPENED,
    RATING_TIP_PENDING_EXIT,
    apply_rating_verdict,
    marksy_channel,
)
from app.models import Caller, MarketPrice, Stock, Tip, TipReceipt, TipSourceExit
from app.tip_matching import MATCH_KEY_VERSION, match_key_v1, stated_terms
from app.tip_tracking_job import track_tips
from app.tip_vocabulary import (
    CALLER_RATING_ENGINE,
    DIRECTION_BUY,
    DIRECTION_SELL,
    ENTRY_BASIS_FIRST_SEEN_PRICE,
    ENTRY_ENTERED,
    HORIZON_BASIS_ENGINE,
    OUTCOME_SUCCESS,
    PARSER_RATING_ENGINE,
    STATUS_ACTIVE,
    STATUS_DIRECTION_HORIZON,
    STATUS_SOURCE_EXIT,
)

MON, TUE, WED, THU = date(2026, 9, 28), date(2026, 9, 29), date(2026, 9, 30), date(2026, 10, 1)
RUN_AT = datetime(2026, 10, 5, 3, 15, tzinfo=timezone.utc)


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
    db.add(Stock(symbol="RELIANCE", exchange="NSE", is_active=True))
    db.commit()
    try:
        yield db
    finally:
        db.close()


def _stock(session):
    return session.scalar(select(Stock).where(Stock.symbol == "RELIANCE"))


def _rate(session, verdict, day, *, horizon=20):
    """What the 09:00 IST run does with one horizon's verdict on `day`, before the open."""
    result = apply_rating_verdict(session, _stock(session), horizon, verdict, at=ist(day, 9),
                                  session_anchor=ist(day, 0))
    session.commit()
    return result


def _bar(session, day, o, h, l, c):
    session.add(MarketPrice(
        stock_id=_stock(session).id,
        timestamp=datetime.combine(day, time(0), NSE_TIMEZONE).astimezone(timezone.utc), session_date=day,
        open=Decimal(str(o)), high=Decimal(str(h)), low=Decimal(str(l)), close=Decimal(str(c)), volume=1000,
        source="upstox-v3",
    ))
    session.commit()


def test_a_buy_verdict_opens_a_direction_only_tip_of_the_rating_engine(session):
    tip, actions = _rate(session, "BUY", TUE)

    channel = marksy_channel(session)
    assert actions == [RATING_TIP_OPENED]
    assert (tip.source_channel_id, session.get(Caller, tip.caller_id).name, tip.parser_version) == (
        channel.id, CALLER_RATING_ENGINE, PARSER_RATING_ENGINE,
    )
    assert (tip.direction, tip.entry_low, tip.entry_high, tip.entry_price, tip.target_price, tip.stop_loss) == (
        DIRECTION_BUY, None, None, None, None, None,
    )
    assert (tip.horizon_sessions, tip.entry_basis, tip.horizon_basis, tip.status, tip.entry_status) == (
        20, ENTRY_BASIS_FIRST_SEEN_PRICE, HORIZON_BASIS_ENGINE, STATUS_ACTIVE, ENTRY_ENTERED,
    )
    assert aware(tip.first_seen_at) == ist(TUE, 9)
    terms = stated_terms(symbol="RELIANCE", direction=DIRECTION_BUY, entry_low=None, entry_high=None, target=None,
                         stop_loss=None, horizon_sessions=20)
    assert (tip.match_key, tip.match_key_version) == (match_key_v1(channel.canonical_channel_id, terms), MATCH_KEY_VERSION)
    assert session.scalars(select(TipReceipt)).all() == []


def test_short_and_long_are_separate_calls_and_an_unchanged_verdict_keeps_its_tip(session):
    short, _ = _rate(session, "BUY", TUE)
    long_, _ = _rate(session, "BUY", TUE, horizon=250)
    again, actions = _rate(session, "BUY", WED)

    assert short.match_key != long_.match_key
    assert (again.id, actions) == (short.id, [RATING_TIP_KEPT])
    assert len(session.scalars(select(Tip)).all()) == 2


def test_hold_and_not_enough_data_open_nothing(session):
    assert _rate(session, "HOLD", TUE) == (None, [])
    assert _rate(session, "NOT_ENOUGH_DATA", TUE) == (None, [])
    assert session.scalars(select(Tip)).all() == []


def test_the_entry_is_the_next_open_not_the_close_the_rating_used(session):
    _bar(session, MON, 99, 101, 98.5, 100.8)
    tip, _ = _rate(session, "BUY", TUE)
    _bar(session, TUE, 101.5, 103, 101, 102.5)

    track_tips(session, now=RUN_AT, minute_bars=None)

    tip = session.get(Tip, tip.id)
    assert (tip.entry_low, tip.entry_high, tip.entry_price) == (Decimal("101.5"), Decimal("101.5"), Decimal("101.5"))
    assert (tip.entered_session, tip.status) == (1, STATUS_ACTIVE)


def test_a_verdict_change_closes_the_tip_as_a_source_exit_at_the_next_open(session):
    tip, _ = _rate(session, "BUY", TUE)
    _bar(session, TUE, 101.5, 103, 101, 102.5)
    _, actions = _rate(session, "NOT_ENOUGH_DATA", WED)
    _bar(session, WED, 103, 104, 102.5, 103.5)

    track_tips(session, now=RUN_AT, minute_bars=None)

    tip = session.get(Tip, tip.id)
    assert actions == [RATING_TIP_EXITED]
    assert (tip.status, tip.exit_price, tip.actual_return, tip.outcome) == (
        STATUS_SOURCE_EXIT, Decimal("103"), Decimal("0.014778"), OUTCOME_SUCCESS,
    )
    assert aware(tip.closed_at) == ist(WED, 9)


def test_a_flip_exits_the_buy_and_opens_a_sell(session):
    buy, _ = _rate(session, "BUY", TUE)
    sell, actions = _rate(session, "SELL", WED)

    assert actions == [RATING_TIP_EXITED, RATING_TIP_OPENED]
    assert (sell.direction, sell.status, aware(sell.first_seen_at)) == (DIRECTION_SELL, STATUS_ACTIVE, ist(WED, 9))
    assert session.scalar(select(TipSourceExit.tip_id)) == buy.id


def test_a_repeat_verdict_while_the_exit_is_pending_opens_nothing(session):
    tip, _ = _rate(session, "BUY", TUE)
    _rate(session, "HOLD", WED)

    assert _rate(session, "BUY", THU) == (None, [RATING_TIP_PENDING_EXIT])
    assert [row.id for row in session.scalars(select(Tip))] == [tip.id]
    assert aware(session.scalar(select(TipSourceExit.exit_seen_at))) == ist(WED, 9)


def test_an_unchanged_call_ends_at_its_horizon_as_direction_horizon(session):
    tip, _ = _rate(session, "SELL", TUE, horizon=2)
    _bar(session, TUE, 100, 101, 98, 99)
    _bar(session, WED, 99, 99.5, 96, 97)

    track_tips(session, now=RUN_AT, minute_bars=None)

    tip = session.get(Tip, tip.id)
    assert (tip.status, tip.exit_price, tip.actual_return, tip.outcome) == (
        STATUS_DIRECTION_HORIZON, Decimal("97"), Decimal("0.03"), OUTCOME_SUCCESS,
    )


def test_rating_tips_are_not_listed_as_external_tips(session):
    _rate(session, "BUY", TUE)

    assert list_tips(session, TipQuery()).items == []
```

- [ ] **Step 2: Run them to verify they fail**

Run: `python -m pytest tests/test_marksy_rating_tips.py -v`
Expected: FAIL with `ImportError: cannot import name 'RATING_TIP_EXITED' from 'app.marksy_tips'`.

- [ ] **Step 3: Extend the imports**

In `app/marksy_tips.py`, add these names to the `from .tip_vocabulary import (...)` block, keeping its alphabetical order:
- `CALLER_RATING_ENGINE`
- `DIRECTION_SELL`
- `ENTRY_BASIS_FIRST_SEEN_PRICE`
- `ENTRY_ENTERED`
- `PARSER_RATING_ENGINE`

The module already imports:
- `uuid`, `datetime` and `select`;
- `Stock`, `Tip` and `TipSourceExit`;
- `_lock_match_key`, `find_matchable` and `resolve_caller`;
- `MATCH_KEY_VERSION`, `match_key_v1` and `stated_terms`;
- `COMPARISON_STATUS_RECEIVED`, `DIRECTION_BUY`, `HORIZON_BASIS_ENGINE` and `STATUS_ACTIVE`;
- `_SOURCE_MAX`.

Add any of these that the merged 2b module lacks.

- [ ] **Step 4: Append the Rating engine's tips**

Append to `app/marksy_tips.py`:

```python
RATING_TIP_OPENED = "opened"
RATING_TIP_KEPT = "kept"
RATING_TIP_EXITED = "exited"
RATING_TIP_PENDING_EXIT = "pendingExit"
# A rating's BUY and SELL verdicts are spelled like tip directions.
_DIRECTIONAL_VERDICTS = (DIRECTION_BUY, DIRECTION_SELL)


def rating_tips(session: Session, stock_id: int, horizon_sessions: int) -> list[Tip]:
    """The Rating engine's ACTIVE tips on one stock and horizon, newest first."""
    return list(session.scalars(
        select(Tip)
        .where(
            Tip.parser_version == PARSER_RATING_ENGINE,
            Tip.stock_id == stock_id,
            Tip.horizon_sessions == horizon_sessions,
            Tip.status == STATUS_ACTIVE,
        )
        .order_by(Tip.first_seen_at.desc(), Tip.id.desc())
    ))


def _exit_pending(session: Session, tip: Tip) -> bool:
    return session.scalar(select(TipSourceExit.id).where(TipSourceExit.tip_id == tip.id)) is not None


def withdraw_rating_tip(session: Session, tip: Tip, *, at: datetime) -> TipSourceExit | None:
    """tip-ledger spec §7: a changed verdict is a source exit the tracker prices (§6.5); the first exit wins."""
    if tip.status != STATUS_ACTIVE or _exit_pending(session, tip):
        return None
    exit_row = TipSourceExit(tip_id=tip.id, receipt_id=None, stated_exit_price=None, exit_seen_at=at)
    session.add(exit_row)
    session.flush()
    return exit_row


def open_rating_tip(session: Session, stock: Stock, horizon_sessions: int, direction: str, *, at: datetime,
                    session_anchor: datetime) -> tuple[Tip | None, str]:
    """A direction-only tip of the Rating engine first seen `at`; the tracker fixes its entry at the first price
    after that (§6.3). The same call again finds its ACTIVE tip by key; one whose exit is pending opens nothing."""
    channel = marksy_channel(session)
    terms = stated_terms(symbol=stock.symbol, direction=direction, entry_low=None, entry_high=None, target=None,
                         stop_loss=None, horizon_sessions=horizon_sessions)
    key = match_key_v1(channel.canonical_channel_id, terms)
    _lock_match_key(session, key)
    existing = find_matchable(session, key, now=at)
    if existing is not None:
        return (None, RATING_TIP_PENDING_EXIT) if _exit_pending(session, existing) else (existing, RATING_TIP_KEPT)
    caller = resolve_caller(session, channel.id, CALLER_RATING_ENGINE)
    tip = Tip(
        public_id=str(uuid.uuid4()),
        source=channel.name[:_SOURCE_MAX],
        symbol=terms.symbol,
        stock_id=stock.id,
        direction=terms.direction,
        horizon_days=horizon_sessions,
        received_at=at,
        tip_as_of=session_anchor,
        comparison_status=COMPARISON_STATUS_RECEIVED,
        source_channel_id=channel.id,
        caller_id=caller.id,
        entry_basis=ENTRY_BASIS_FIRST_SEEN_PRICE,
        horizon_sessions=horizon_sessions,
        horizon_basis=HORIZON_BASIS_ENGINE,
        first_seen_at=at,
        match_key=key,
        match_key_version=MATCH_KEY_VERSION,
        parser_version=PARSER_RATING_ENGINE,
        status=STATUS_ACTIVE,
        entry_status=ENTRY_ENTERED,
    )
    session.add(tip)
    session.flush()
    return tip, RATING_TIP_OPENED


def apply_rating_verdict(session: Session, stock: Stock, horizon_sessions: int, verdict: str, *, at: datetime,
                         session_anchor: datetime) -> tuple[Tip | None, list[str]]:
    """tip-ledger spec §7 for one stock and horizon: a verdict other than an open tip's direction withdraws that
    tip, and BUY or SELL then stands behind a tip, kept or opened. Returns that tip and what was done."""
    actions = []
    for tip in rating_tips(session, stock.id, horizon_sessions):
        if tip.direction != verdict and withdraw_rating_tip(session, tip, at=at) is not None:
            actions.append(RATING_TIP_EXITED)
    if verdict not in _DIRECTIONAL_VERDICTS:
        return None, actions
    tip, action = open_rating_tip(session, stock, horizon_sessions, verdict, at=at, session_anchor=session_anchor)
    return tip, [*actions, action]
```

- [ ] **Step 5: Keep the EPIC-803 readers external**

In `api/services/tips.py`:
- change `from sqlalchemy import func, select` to `from sqlalchemy import and_, func, select`;
- add `PARSER_RATING_ENGINE` to the `from app.tip_vocabulary import (...)` block;
- make `_external_tips_only` read:

```python
def _external_tips_only():
    # tip-ledger spec §7: Marksy's own calls share the table; every EPIC-803 reader is external tips only.
    return and_(ExternalTip.prediction_id.is_(None), ExternalTip.parser_version.is_distinct_from(PARSER_RATING_ENGINE))
```

`IS DISTINCT FROM` (rendered `IS NOT` on SQLite) keeps the 43 legacy tips, whose `parser_version` is NULL.

If 2b merged its filter inline instead of in `_external_tips_only`, find every `prediction_id.is_(None)` in `api/services/tips.py` and add the same `parser_version.is_distinct_from(PARSER_RATING_ENGINE)` condition beside each one.

- [ ] **Step 6: Run the tests**

```bash
python -m pytest tests/test_marksy_rating_tips.py tests/test_marksy_prediction_tips.py tests/test_tip_ledger.py tests/test_tip_tracking_job.py tests/test_api_tips.py -v
```

Expected: all PASS, apart from the three known `test_api_tips.py` failures (Global Constraints), which must fail identically on `main`.

- [ ] **Step 7: Commit**

```bash
git add app/marksy_tips.py api/services/tips.py tests/test_marksy_rating_tips.py
git commit -m "Tip ledger: the Rating engine's verdicts as direction-only tips; a changed verdict is a source exit

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 6: The rating runner (`rate_stocks`)

**Files:**
- Create: `app/rating_job.py`
- Test: `tests/test_rating_job.py`

**Interfaces:**
- Consumes:
  - `rate`, `V1`, `RatingConfig`, `RatingInputs` and `HORIZON_SESSIONS` (Task 2);
  - `Bar` and `price_inputs` (Task 4);
  - `apply_rating_verdict` (Task 5);
  - `get_latest_fundamental_record(session, stock_id, *, as_of_timestamp)`;
  - `_session_dates(session, since)` and `_anchor(day)` from 2a's `app/tip_tracking_job.py`;
  - `is_benchmark_index(symbol)`.
- Produces:
  - `INPUTS_VERSION = "RIN-001"`, `NIFTY_SYMBOL = "NIFTY50"`, `PREDICTION_OPEN = "OPEN"`, `SESSION_LOOKBACK_DAYS = 10`, `MAX_FAILED_STOCK_IDS = 5` and `YEAR_AGO_WINDOW = (timedelta(days=380), timedelta(days=350))`
  - `@dataclass RatingRun`, with `.summary() -> dict`. Its keys are `sessionDate, configVersion, stocksExamined, stocksRated, alreadyRated, noSessionBar, stocksFailed, failedStockIds, verdicts, tips`.
  - `active_config(session: Session) -> RatingConfig`
  - `rating_session(session: Session, *, now: datetime) -> date | None`
  - `rating_universe(session: Session) -> list[Stock]`
  - `rate_stocks(session: Session, *, now: datetime) -> RatingRun`. It commits per stock.

**Rules this task implements:** decisions 1, 2, 3 (data gap), 5, 8 and 9.

- [ ] **Step 1: Write the failing tests**

Create `tests/test_rating_job.py`:

```python
"""The rating runner (tip-ledger spec §7): universe, point-in-time inputs, rating history and the Rating engine's tips."""
from __future__ import annotations

from datetime import date, datetime, time, timedelta, timezone
from decimal import Decimal

import pytest
from sqlalchemy import create_engine, select
from sqlalchemy.orm import sessionmaker

from app.db import Base
from app.market_data.quality import NSE_TIMEZONE
from app.models import (
    DailyCandidateScan,
    FundamentalDataRecord,
    MarketPrice,
    Prediction,
    RatingCalibrationRun,
    ScanCandidate,
    Stock,
    StockRating,
    Tip,
    TipSourceExit,
)
from app.rating_engine import (
    FACTOR_GROWTH,
    FACTOR_MARKSY_CALL,
    FACTOR_RISK,
    FACTOR_SEASONALITY,
    FACTOR_TREND,
    HORIZON_LONG,
    HORIZON_SHORT,
    V1,
)
from app.rating_job import INPUTS_VERSION, active_config, rate_stocks
from app.tip_vocabulary import DIRECTION_BUY, PARSER_RATING_ENGINE, STATUS_ACTIVE

MON, TUE, WED = date(2026, 9, 28), date(2026, 9, 29), date(2026, 9, 30)


def ist(day, hour, minute=0):
    """`day` at hour:minute IST, as UTC."""
    return datetime(day.year, day.month, day.day, hour, minute, tzinfo=NSE_TIMEZONE).astimezone(timezone.utc)


def aware(value):
    return value if value.tzinfo is not None else value.replace(tzinfo=timezone.utc)


RATED_TUE, RATED_WED = ist(TUE, 9), ist(WED, 9)


def _weekdays(n, last):
    days, day = [], last
    while len(days) < n:
        if day.weekday() < 5:
            days.append(day)
        day -= timedelta(days=1)
    return days[::-1]


SESSIONS = _weekdays(60, MON)


def _walk(first, mul, mod, drift):
    """Deterministic daily bars over SESSIONS, rounded to the paisa: (day, open, high, low, close)."""
    bars, prev = [], first
    for i, day in enumerate(SESSIONS):
        step = (((i * mul) % mod) - (mod - 1) / 2.0 + drift) / 1000.0
        close = round(prev * (1 + step), 2)
        bars.append((day, prev, round(max(prev, close) * 1.004, 2), round(min(prev, close) * 0.995, 2), close))
        prev = close
    return bars


RISING = _walk(100.0, 7919, 41, 3.0)
NIFTY = _walk(20000.0, 104729, 31, 0.3)


def _stock(session, symbol):
    return session.scalar(select(Stock).where(Stock.symbol == symbol))


def _add_bars(session, symbol, bars):
    stock_id = _stock(session, symbol).id
    session.add_all([
        MarketPrice(
            stock_id=stock_id, timestamp=datetime.combine(day, time(0), NSE_TIMEZONE).astimezone(timezone.utc),
            session_date=day, open=Decimal(str(o)), high=Decimal(str(h)), low=Decimal(str(low)),
            close=Decimal(str(c)), volume=1000, source="upstox-v3",
        )
        for day, o, h, low, c in bars
    ])
    session.commit()


def _prediction(session, symbol="RELIANCE", *, day=MON, status="OPEN"):
    prediction = Prediction(
        stock_id=_stock(session, symbol).id, created_at=ist(day, 16, 30), as_of_timestamp=ist(day, 0),
        entry_price=Decimal("113.29"), horizon_days=5, target_return=Decimal("0.05"), stop_return=Decimal("-0.03"),
        predicted_probability=Decimal("0.7"), confidence=Decimal("0.8"), model_version="m1-baseline-1",
        feature_version="f1", consensus_contract_version="c1", horizon_selection_version="h1",
        scoring_contract_version="s1", opportunity_score=Decimal("60.00"), status=status,
    )
    session.add(prediction)
    session.flush()
    return prediction


@pytest.fixture
def session():
    engine = create_engine("sqlite:///:memory:")
    Base.metadata.create_all(engine)
    db = sessionmaker(bind=engine)()
    db.add_all([Stock(symbol=symbol, exchange="NSE", is_active=True) for symbol in ("RELIANCE", "TCS", "NIFTY50")])
    db.commit()
    for symbol, bars in (("RELIANCE", RISING), ("TCS", RISING), ("NIFTY50", NIFTY)):
        _add_bars(db, symbol, bars)
    scan = DailyCandidateScan(scan_date=MON, universe_version="u1", eligible_count=3, excluded_count=0)
    db.add(scan)
    db.flush()
    db.add_all([ScanCandidate(scan_id=scan.id, stock_id=_stock(db, symbol).id, eligible=True)
                for symbol in ("RELIANCE", "TCS", "NIFTY50")])
    _prediction(db)
    db.commit()
    try:
        yield db
    finally:
        db.close()


def _rows(session, day=MON):
    rows = session.scalars(select(StockRating).where(StockRating.session_date == day)).all()
    return {session.get(Stock, row.stock_id).symbol: row for row in rows}


def _next_session(session, *, with_reliance=True):
    """TUE's official bars; without RELIANCE's, the whole-market session exists but that stock has a gap."""
    if with_reliance:
        _add_bars(session, "RELIANCE", [(TUE, 113.29, 114.2, 112.9, 113.8)])
    _add_bars(session, "TCS", [(TUE, 113.29, 114.2, 112.9, 113.8)])
    _add_bars(session, "NIFTY50", [(TUE, 21000.0, 21100.0, 20900.0, 21050.0)])


def test_every_universe_stock_gets_one_rating_row_and_a_buy_gets_a_tip(session):
    run = rate_stocks(session, now=RATED_TUE)

    rows = _rows(session)
    assert set(rows) == {"RELIANCE", "TCS"}
    reliance, tcs = rows["RELIANCE"], rows["TCS"]
    assert (reliance.session_date, aware(reliance.rated_at), reliance.price) == (MON, RATED_TUE, Decimal("113.29"))
    assert (reliance.config_version, reliance.inputs_version) == (V1.version, INPUTS_VERSION)
    assert set(reliance.factors) == {FACTOR_TREND, FACTOR_MARKSY_CALL, FACTOR_RISK}
    assert (reliance.short_verdict, reliance.short_score, reliance.short_coverage) == (
        "BUY", Decimal("0.547244"), Decimal("0.650000"),
    )
    assert (reliance.long_verdict, tcs.short_verdict, tcs.long_verdict) == ("NOT_ENOUGH_DATA",) * 3
    tip = session.get(Tip, reliance.short_tip_id)
    assert (tip.parser_version, tip.symbol, tip.direction, tip.horizon_sessions, tip.status) == (
        PARSER_RATING_ENGINE, "RELIANCE", DIRECTION_BUY, 20, STATUS_ACTIVE,
    )
    assert aware(tip.first_seen_at) == RATED_TUE
    assert (reliance.long_tip_id, tcs.short_tip_id, tcs.long_tip_id) == (None, None, None)
    summary = run.summary()
    assert (summary["sessionDate"], summary["stocksRated"], summary["tips"]) == ("2026-09-28", 2, {"opened": 1})


def test_a_rerun_for_the_same_session_changes_nothing(session):
    rate_stocks(session, now=RATED_TUE)
    again = rate_stocks(session, now=RATED_TUE + timedelta(hours=1))

    assert again.summary()["alreadyRated"] == 2
    assert len(session.scalars(select(StockRating)).all()) == 2
    assert len(session.scalars(select(Tip)).all()) == 1


def test_a_stock_missing_the_session_bar_is_not_rated_and_keeps_its_tip(session):
    rate_stocks(session, now=RATED_TUE)
    tip_id = _rows(session)["RELIANCE"].short_tip_id
    _next_session(session, with_reliance=False)

    run = rate_stocks(session, now=RATED_WED)

    assert (run.summary()["sessionDate"], run.summary()["noSessionBar"]) == ("2026-09-29", 1)
    assert set(_rows(session, TUE)) == {"TCS"}
    assert session.get(Tip, tip_id).status == STATUS_ACTIVE
    assert session.scalars(select(TipSourceExit)).all() == []


def test_a_closed_call_turns_the_buy_into_a_source_exit(session):
    rate_stocks(session, now=RATED_TUE)
    session.scalar(select(Prediction)).status = "EVALUATED"
    _next_session(session)

    run = rate_stocks(session, now=RATED_WED)

    reliance = _rows(session, TUE)["RELIANCE"]
    assert (reliance.short_verdict, reliance.short_tip_id) == ("NOT_ENOUGH_DATA", None)
    assert aware(session.scalar(select(TipSourceExit.exit_seen_at))) == RATED_WED
    assert run.summary()["tips"] == {"exited": 1}


def test_growth_comes_from_the_fundamentals_held_a_year_apart(session):
    reliance = _stock(session, "RELIANCE").id
    for period, revenue, profit, fetched in (
        (date(2025, 6, 30), "100", "10", ist(date(2025, 8, 1), 18)),
        (date(2026, 6, 30), "120", "15", ist(date(2026, 8, 3), 18)),
        (date(2026, 9, 30), "200", "40", RATED_TUE + timedelta(days=1)),  # not held yet at the rating
    ):
        session.add(FundamentalDataRecord(
            stock_id=reliance, source="yahoo-finance", period_end_date=period, revenue=Decimal(revenue),
            net_income=Decimal(profit), published_at=fetched, fetched_at=fetched, ingestion_rule_version="FDI-001",
        ))
    session.commit()

    rate_stocks(session, now=RATED_TUE)

    row = _rows(session)["RELIANCE"]
    assert row.factors[FACTOR_GROWTH] == pytest.approx(0.8966062412731641, abs=1e-12)
    assert row.short_coverage == Decimal("0.700000")


def _calibration(adopted, *, ran_at, version="marksy-rating-1-cal-2026-09-25"):
    return RatingCalibrationRun(
        ran_at=ran_at, version=version if adopted else V1.version, adopted=adopted,
        trend_weight=Decimal("0") if adopted else Decimal("0.35"),
        seasonality_weight=Decimal("0.45") if adopted else Decimal("0.1"), samples=300, test_samples=90,
        train_spearman=Decimal("0.9"), test_spearman=Decimal("0.9"), base_test_spearman=Decimal("0.3"),
        first_call_date=date(2026, 1, 5), last_call_date=date(2026, 9, 25),
    )


def test_an_adopted_calibration_sets_the_weights_ratings_use_and_record(session):
    assert active_config(session) == V1
    session.add(_calibration(True, ran_at=ist(MON, 9)))
    session.commit()

    config = active_config(session)
    rate_stocks(session, now=RATED_TUE)

    short = config.weights[HORIZON_SHORT]
    assert (config.version, short[FACTOR_TREND], short[FACTOR_SEASONALITY]) == ("marksy-rating-1-cal-2026-09-25", 0.0, 0.45)
    assert config.weights[HORIZON_LONG] == V1.weights[HORIZON_LONG]
    assert _rows(session)["RELIANCE"].config_version == "marksy-rating-1-cal-2026-09-25"
    session.add(_calibration(False, ran_at=ist(TUE, 9, 5)))
    session.commit()
    assert active_config(session) == V1
```

The pinned SHORT figures (`0.547244` score, `0.650000` coverage) are the ported engine on these bars with the prediction's call. They are also the coverage arithmetic of decision 5: TREND .35 + MARKSY_CALL .30, rated with no seasonality (60 sessions is under 13 months) and no growth. TCS has the same bars and no call, so its coverage is .35 and it is NOT_ENOUGH_DATA.

- [ ] **Step 2: Run them to verify they fail**

Run: `python -m pytest tests/test_rating_job.py -v`
Expected: FAIL with `ModuleNotFoundError: No module named 'app.rating_job'`.

- [ ] **Step 3: Write the runner**

Create `app/rating_job.py`:

```python
"""Runs the rating engine (tip-ledger spec §7) once per official session over the rating universe.

Each stock rated gets one `stock_ratings` row for the session: both horizons' verdicts, the factor scores and the
close they came from. A BUY or SELL stands behind a direction-only tip of the Rating engine; any other verdict
withdraws it. Every input is read from stored data -- nothing is fetched from Upstox."""

from __future__ import annotations

from dataclasses import dataclass, field, replace
from datetime import date, datetime, timedelta, timezone
from decimal import ROUND_HALF_UP, Decimal

from sqlalchemy import func, select
from sqlalchemy.orm import Session

from .fundamental_data.ingest import get_latest_fundamental_record
from .market_data.bar_finality import final_bars_only
from .market_data.freshness import session_date_of
from .market_data.quality import NSE_TIMEZONE
from .marksy_tips import apply_rating_verdict
from .models import (
    DailyCandidateScan,
    FundamentalDataRecord,
    MarketPrice,
    Prediction,
    RatingCalibrationRun,
    ScanCandidate,
    Stock,
    StockRating,
    Tip,
)
from .rating_engine import (
    FACTOR_SEASONALITY,
    FACTOR_TREND,
    HORIZON_LONG,
    HORIZON_SESSIONS,
    HORIZON_SHORT,
    V1,
    RatingConfig,
    RatingInputs,
    rate,
)
from .rating_inputs import Bar, price_inputs
from .tip_tracking_job import _anchor, _session_dates
from .tip_vocabulary import PARSER_RATING_ENGINE, STATUS_ACTIVE
from .universe_integrity import is_benchmark_index

INPUTS_VERSION = "RIN-001"
NIFTY_SYMBOL = "NIFTY50"
PREDICTION_OPEN = "OPEN"
SESSION_LOOKBACK_DAYS = 10
MAX_FAILED_STOCK_IDS = 5
# Trailing-twelve-month figures a year apart: the quarter-end one year before, give or take a fortnight.
YEAR_AGO_WINDOW = (timedelta(days=380), timedelta(days=350))
_PLACES = Decimal("0.000001")


@dataclass
class RatingRun:
    session_date: date | None = None
    config_version: str | None = None
    examined: int = 0
    rated: int = 0
    already_rated: int = 0
    no_session_bar: int = 0
    failed: int = 0
    verdicts: dict[str, int] = field(default_factory=dict)
    tips: dict[str, int] = field(default_factory=dict)
    failed_stock_ids: list[int] = field(default_factory=list)

    def summary(self) -> dict:
        return {
            "sessionDate": self.session_date.isoformat() if self.session_date is not None else None,
            "configVersion": self.config_version,
            "stocksExamined": self.examined,
            "stocksRated": self.rated,
            "alreadyRated": self.already_rated,
            "noSessionBar": self.no_session_bar,
            "stocksFailed": self.failed,
            "failedStockIds": list(self.failed_stock_ids),
            "verdicts": dict(sorted(self.verdicts.items())),
            "tips": dict(sorted(self.tips.items())),
        }


def _aware(value: datetime) -> datetime:
    return value if value.tzinfo is not None else value.replace(tzinfo=timezone.utc)


def _q(value: float) -> Decimal:
    return Decimal(repr(value)).quantize(_PLACES, rounding=ROUND_HALF_UP)


def _count(counts: dict[str, int], key: str) -> None:
    counts[key] = counts.get(key, 0) + 1


def active_config(session: Session) -> RatingConfig:
    """The weights ratings use: the latest calibration's if it was adopted, else V1 (RatingCalibrator.configFor)."""
    latest = session.scalar(
        select(RatingCalibrationRun).order_by(RatingCalibrationRun.ran_at.desc(), RatingCalibrationRun.id.desc()).limit(1)
    )
    if latest is None or not latest.adopted:
        return V1
    short = {
        **V1.weights[HORIZON_SHORT],
        FACTOR_TREND: float(latest.trend_weight),
        FACTOR_SEASONALITY: float(latest.seasonality_weight),
    }
    return replace(V1, version=latest.version, weights={**V1.weights, HORIZON_SHORT: short})


def rating_session(session: Session, *, now: datetime) -> date | None:
    """The latest official session, counted the way the tracker counts sessions (Phase 2a decision 3)."""
    today = now.astimezone(NSE_TIMEZONE).date()
    sessions = [day for day in _session_dates(session, today - timedelta(days=SESSION_LOOKBACK_DAYS)) if day <= today]
    return sessions[-1] if sessions else None


def rating_universe(session: Session) -> list[Stock]:
    """The latest discovery scan's eligible stocks, every stock with an OPEN prediction, and every stock with an
    ACTIVE rating tip (so its verdict can still change it); benchmark indices are never rated."""
    ids: set[int] = set()
    scan_date = session.scalar(select(func.max(DailyCandidateScan.scan_date)))
    if scan_date is not None:
        ids.update(session.scalars(
            select(ScanCandidate.stock_id)
            .join(DailyCandidateScan, DailyCandidateScan.id == ScanCandidate.scan_id)
            .where(DailyCandidateScan.scan_date == scan_date, ScanCandidate.eligible.is_(True))
        ))
    ids.update(session.scalars(select(Prediction.stock_id).where(Prediction.status == PREDICTION_OPEN)))
    ids.update(session.scalars(
        select(Tip.stock_id).where(Tip.parser_version == PARSER_RATING_ENGINE, Tip.status == STATUS_ACTIVE)
    ))
    if not ids:
        return []
    stocks = session.scalars(select(Stock).where(Stock.id.in_(ids)).order_by(Stock.id)).all()
    return [stock for stock in stocks if not is_benchmark_index(stock.symbol)]


def _bars(session: Session, stock_id: int, *, until: date) -> list[Bar]:
    """Official daily bars up to `until`, oldest first."""
    rows = session.execute(
        select(MarketPrice.timestamp, MarketPrice.open, MarketPrice.high, MarketPrice.low, MarketPrice.close)
        .where(MarketPrice.stock_id == stock_id, MarketPrice.timestamp <= _anchor(until), final_bars_only())
        .order_by(MarketPrice.timestamp)
    ).all()
    return [
        Bar(session_date_of(_aware(stamp)), float(o), float(h), float(low), float(c))
        for stamp, o, h, low, c in rows
    ]


def _open_call(session: Session, stock_id: int) -> tuple[float, float] | None:
    """The leading OPEN Marksy call on the stock (MarksyCalls.view's primary): its upside % and probability."""
    prediction = session.scalar(
        select(Prediction)
        .where(Prediction.stock_id == stock_id, Prediction.status == PREDICTION_OPEN)
        .order_by(Prediction.as_of_timestamp.desc(), Prediction.id.desc())
        .limit(1)
    )
    if prediction is None or not prediction.entry_price > 0:
        return None
    return float(prediction.target_return) * 100, float(prediction.predicted_probability)


def _growth(session: Session, stock_id: int, *, at: datetime) -> tuple[float | None, float | None]:
    """Year-on-year revenue and profit growth: the latest fundamentals Marksy held at `at` against the record for
    the period a year earlier, both point-in-time (EPIC-208)."""
    latest = get_latest_fundamental_record(session, stock_id, as_of_timestamp=at)
    if latest is None or latest.period_end_date is None:
        return None, None
    year_ago = session.scalar(
        select(FundamentalDataRecord)
        .where(
            FundamentalDataRecord.stock_id == stock_id,
            FundamentalDataRecord.published_at <= at,
            FundamentalDataRecord.fetched_at <= at,
            FundamentalDataRecord.period_end_date.between(
                latest.period_end_date - YEAR_AGO_WINDOW[0], latest.period_end_date - YEAR_AGO_WINDOW[1]
            ),
        )
        .order_by(FundamentalDataRecord.period_end_date.desc(), FundamentalDataRecord.published_at.desc())
        .limit(1)
    )
    if year_ago is None:
        return None, None

    def change(current: Decimal | None, before: Decimal | None) -> float | None:
        if current is None or before is None or not before > 0:
            return None
        return (float(current) - float(before)) / float(before) * 100

    return change(latest.revenue, year_ago.revenue), change(latest.net_income, year_ago.net_income)


def _rate_stock(session: Session, stock: Stock, *, session_date: date, now: datetime, config: RatingConfig,
                index: list[Bar], run: RatingRun) -> None:
    rated = select(StockRating.id).where(StockRating.stock_id == stock.id, StockRating.session_date == session_date)
    if session.scalar(rated) is not None:
        run.already_rated += 1
        return
    bars = _bars(session, stock.id, until=session_date)
    if not bars or bars[-1].day != session_date:
        # A data gap is not a verdict: rated on a stale bar it could read NOT_ENOUGH_DATA and close the call.
        run.no_session_bar += 1
        return
    close = bars[-1].close
    inputs = price_inputs(bars, index, on=now.astimezone(NSE_TIMEZONE).date()) or RatingInputs(price=close)
    call = _open_call(session, stock.id)
    revenue, profit = _growth(session, stock.id, at=now)
    inputs = replace(
        inputs,
        call_upside_pct=call[0] if call is not None else None,
        call_probability=call[1] if call is not None else None,
        revenue_growth_yoy=revenue,
        profit_growth_yoy=profit,
    )
    result = rate(inputs, config)
    anchor = _anchor(session_date)
    stood: dict[str, int | None] = {}
    for horizon in (HORIZON_SHORT, HORIZON_LONG):
        verdict = result.horizon(horizon).verdict
        tip, actions = apply_rating_verdict(session, stock, HORIZON_SESSIONS[horizon], verdict, at=now,
                                            session_anchor=anchor)
        stood[horizon] = tip.id if tip is not None else None
        _count(run.verdicts, f"{horizon}:{verdict}")
        for action in actions:
            _count(run.tips, action)
    short, long_ = result.horizon(HORIZON_SHORT), result.horizon(HORIZON_LONG)
    session.add(StockRating(
        stock_id=stock.id,
        session_date=session_date,
        rated_at=now,
        price=_q(close),
        config_version=config.version,
        inputs_version=INPUTS_VERSION,
        factors={factor.factor: factor.score for factor in result.factors},
        short_verdict=short.verdict,
        short_score=_q(short.score),
        short_confidence=_q(short.confidence),
        short_coverage=_q(short.coverage),
        short_tip_id=stood[HORIZON_SHORT],
        long_verdict=long_.verdict,
        long_score=_q(long_.score),
        long_confidence=_q(long_.confidence),
        long_coverage=_q(long_.coverage),
        long_tip_id=stood[HORIZON_LONG],
    ))
    run.rated += 1


def rate_stocks(session: Session, *, now: datetime) -> RatingRun:
    """One rating per universe stock for the latest official session; a rerun for that session changes nothing."""
    run = RatingRun()
    session_date = rating_session(session, now=now)
    if session_date is None:
        return run
    config = active_config(session)
    run.session_date, run.config_version = session_date, config.version
    nifty = session.scalar(select(Stock).where(Stock.symbol == NIFTY_SYMBOL))
    index = _bars(session, nifty.id, until=session_date) if nifty is not None else []
    for stock in rating_universe(session):
        run.examined += 1
        stock_id = stock.id
        try:
            _rate_stock(session, stock, session_date=session_date, now=now, config=config, index=index, run=run)
            session.commit()
        except Exception:
            # The tracker's F1 rule: one stock's bad data must not stop the rest of the universe.
            session.rollback()
            run.failed += 1
            if len(run.failed_stock_ids) < MAX_FAILED_STOCK_IDS:
                run.failed_stock_ids.append(stock_id)
    return run
```

`_session_dates` and `_anchor` are private to `app/tip_tracking_job.py`. They are imported rather than copied so that the rating session is by construction the tracker's session. 2b imports `tip_ledger._lock_match_key` the same way.

- [ ] **Step 4: Check imports, then run the tests**

```bash
DATABASE_URL=sqlite:///:memory: python -c "import app.rating_job, app.tip_tracking_job, app.recommendations, api.services.tips"
python -m pytest tests/test_rating_job.py tests/test_marksy_rating_tips.py -v
```

Expected: the import line prints nothing, so there is no import cycle. All tests PASS.

- [ ] **Step 5: Commit**

```bash
git add app/rating_job.py tests/test_rating_job.py
git commit -m "Rating engine: runner rates the universe once per session and keeps every verdict in rating history

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 7: Weekly calibration on Marksy's closed calls

**Files:**
- Modify: `app/rating_job.py` (imports; append)
- Test: `tests/test_rating_job.py` (imports; append)

**Interfaces:**
- Consumes:
  - `Sample` and `calibrate` (Task 3);
  - `price_inputs` (Task 4);
  - `_bars`, `_aware`, `_q` and `NIFTY_SYMBOL` (Task 6);
  - `clean_intelligence_only(prediction_id_column)` from `app/clean_population.py`.
- Produces:
  - `MIN_CALIBRATION_SAMPLES = 60`, `MAX_CALIBRATION_CALLS = 400` and `CALIBRATION_EVERY = timedelta(days=7)`
  - `calibration_samples(session: Session) -> tuple[list[Sample], list[date]]`: oldest first, with every usable call's scan date.
  - `recalibrate_if_due(session: Session, *, now: datetime) -> dict`. It returns one of:
    - `{"status": "notDue"}`;
    - `{"status": "insufficientSamples", "samples": n}`;
    - `{"status": "calibrated", "version", "adopted", "samples", "testSamples"}`.

    It commits the run row it writes.

**Rules this task implements:** decision 4, and `RatingCalibrator.run`/`pointInTime` (`RatingCalibrator.kt:70-128`).

- [ ] **Step 1: Write the failing tests**

In `tests/test_rating_job.py`:
- add `PredictionOutcome` to the `from app.models import (...)` block;
- add `from app.rating_inputs import Bar, price_inputs`;
- change the `app.rating_job` import to `from app.rating_job import INPUTS_VERSION, active_config, calibration_samples, rate_stocks, recalibrate_if_due`.

Then append:

```python
def _closed_call(session, symbol, day, realized):
    prediction = _prediction(session, symbol, day=day, status="EVALUATED")
    session.add(PredictionOutcome(
        prediction_id=prediction.id, evaluation_date=ist(day, 18), highest_price=Decimal("1"),
        lowest_price=Decimal("1"), closing_price=Decimal("1"), maximum_return=Decimal("0"),
        maximum_drawdown=Decimal("0"), actual_return=Decimal(str(realized)), prediction_error=Decimal("0"),
        target_hit=False, stop_hit=False, outcome="EXPIRED",
    ))
    session.commit()


def _as_bars(rows):
    return [Bar(day, o, h, low, c) for day, o, h, low, c in rows]


def test_calibration_samples_see_no_bar_after_the_call_date(session):
    call_day = SESSIONS[45]
    _closed_call(session, "RELIANCE", call_day, 0.02)

    samples, days = calibration_samples(session)

    before = [bar for bar in _as_bars(RISING) if bar.day <= call_day]
    index = [bar for bar in _as_bars(NIFTY) if bar.day <= call_day]
    assert days == [call_day]
    assert (len(samples), samples[0].realized_return, samples[0].inputs.price) == (1, 0.02, before[-1].close)
    assert samples[0].inputs == price_inputs(before, index, on=call_day)


def test_calibration_waits_for_sixty_samples_then_runs_weekly(session):
    assert recalibrate_if_due(session, now=RATED_TUE) == {"status": "insufficientSamples", "samples": 0}
    for i, day in enumerate(SESSIONS[29:]):
        _closed_call(session, "RELIANCE", day, (i % 7 - 3) / 100)
        _closed_call(session, "TCS", day, (3 - i % 5) / 100)

    first = recalibrate_if_due(session, now=RATED_TUE)

    row = session.scalar(select(RatingCalibrationRun))
    assert (first["status"], first["samples"], first["testSamples"], first["adopted"]) == ("calibrated", 62, 19, False)
    assert (row.version, row.adopted, row.first_call_date, row.last_call_date) == (V1.version, False, SESSIONS[29], MON)
    assert recalibrate_if_due(session, now=RATED_TUE + timedelta(days=6)) == {"status": "notDue"}
    assert recalibrate_if_due(session, now=RATED_TUE + timedelta(days=7))["status"] == "calibrated"
```

Both stocks get 31 calls (SESSIONS[29:]), each with at least 30 bars up to its date, which makes 62 samples. 19 are held out. That is under `MIN_TEST` (30), so the run is recorded unadopted with V1's version. This pins the gate, not the weights; Task 3 pins the weights.

- [ ] **Step 2: Run them to verify they fail**

Run: `python -m pytest tests/test_rating_job.py -v`
Expected: FAIL with `ImportError: cannot import name 'calibration_samples' from 'app.rating_job'`.

- [ ] **Step 3: Extend the imports**

In `app/rating_job.py`:
- add `from .clean_population import clean_intelligence_only` directly above `from .fundamental_data.ingest import ...`;
- add `PredictionOutcome` to the `from .models import (...)` block, after `Prediction`;
- add `Sample` and `calibrate` to the `from .rating_engine import (...)` block, after `RatingInputs`.

- [ ] **Step 4: Append the calibration runner**

Append to `app/rating_job.py`:

```python
MIN_CALIBRATION_SAMPLES = 60
MAX_CALIBRATION_CALLS = 400
CALIBRATION_EVERY = timedelta(days=7)


def calibration_samples(session: Session) -> tuple[list[Sample], list[date]]:
    """RatingCalibrator.run on the backend's own labels: the newest 400 closed calls in the clean population
    (`PredictionOutcome.actual_return`), each rated only on bars up to its scan date. Oldest first, with every
    call's scan date. Fundamentals and the call's probability have no history, so those factors are absent."""
    rows = session.execute(
        select(Prediction.stock_id, Prediction.as_of_timestamp, PredictionOutcome.actual_return)
        .join(PredictionOutcome, PredictionOutcome.prediction_id == Prediction.id)
        .where(clean_intelligence_only(Prediction.id))
        .order_by(Prediction.as_of_timestamp, Prediction.id)
    ).all()[-MAX_CALIBRATION_CALLS:]
    if not rows:
        return [], []
    days = [_aware(as_of).astimezone(NSE_TIMEZONE).date() for _, as_of, _ in rows]
    last = max(days)
    nifty = session.scalar(select(Stock).where(Stock.symbol == NIFTY_SYMBOL))
    index = _bars(session, nifty.id, until=last) if nifty is not None else []
    bars: dict[int, list[Bar]] = {}
    samples = []
    for (stock_id, _, realized), day in zip(rows, days):
        if stock_id not in bars:
            bars[stock_id] = _bars(session, stock_id, until=last)
        inputs = price_inputs(bars[stock_id], index, on=day)
        if inputs is not None:
            samples.append(Sample(inputs, float(realized)))
    return samples, days


def recalibrate_if_due(session: Session, *, now: datetime) -> dict:
    """RatingCalibration once a week: tunes the short-term weights on Marksy's closed calls and records the run,
    which the next rating run's `active_config` reads. Under 60 samples nothing is recorded and the next run retries."""
    latest = session.scalar(
        select(RatingCalibrationRun).order_by(RatingCalibrationRun.ran_at.desc(), RatingCalibrationRun.id.desc()).limit(1)
    )
    if latest is not None and now - _aware(latest.ran_at) < CALIBRATION_EVERY:
        return {"status": "notDue"}
    samples, days = calibration_samples(session)
    if len(samples) < MIN_CALIBRATION_SAMPLES:
        return {"status": "insufficientSamples", "samples": len(samples)}
    result = calibrate(samples, version=f"{V1.version}-cal-{days[-1].isoformat()}")
    short = result.config.weights[HORIZON_SHORT]
    session.add(RatingCalibrationRun(
        ran_at=now,
        version=result.config.version,
        adopted=result.adopted,
        trend_weight=_q(short[FACTOR_TREND]),
        seasonality_weight=_q(short[FACTOR_SEASONALITY]),
        samples=result.samples,
        test_samples=result.test_samples,
        train_spearman=_q(result.train),
        test_spearman=_q(result.test),
        base_test_spearman=_q(result.base_test),
        first_call_date=days[0],
        last_call_date=days[-1],
    ))
    session.commit()
    return {
        "status": "calibrated",
        "version": result.config.version,
        "adopted": result.adopted,
        "samples": result.samples,
        "testSamples": result.test_samples,
    }
```

- [ ] **Step 5: Run the tests**

```bash
DATABASE_URL=sqlite:///:memory: python -c "import app.rating_job, app.clean_population"
python -m pytest tests/test_rating_job.py tests/test_rating_engine.py -v
```

Expected: the import line prints nothing, and all tests PASS.

- [ ] **Step 6: Commit**

```bash
git add app/rating_job.py tests/test_rating_job.py
git commit -m "Rating engine: weekly calibration on Marksy's closed calls sets the weights the next run uses

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 8: `RATING_ENGINE` operation — script, registries, CronJob

**Files:**
- Create: `scripts/run_rating_engine.py`
- Modify:
  - `app/schedule_orchestration.py`: the operation constant next to `OPERATION_TIP_TRACKING`, and a `TRIGGER_POLICIES` entry;
  - `app/operation_entrypoints.py`: a `RUNNABLE_JOBS` entry;
  - `app/operation_recovery.py`: a `RECOVERY_POLICIES` entry;
  - `app/operation_verification.py`: a verifier and a `VERIFIERS` entry;
  - `deploy/k8s/base/kustomization.yaml`.
- Create: `deploy/k8s/base/rating-engine-cronjob.yaml`
- Test: create `tests/test_run_rating_engine.py`; modify `tests/test_operation_verification_s7.py`.

**Interfaces:**
- Consumes (Tasks 6–7): `rate_stocks(session, *, now) -> RatingRun` and `recalibrate_if_due(session, *, now) -> dict`.
- Produces:
  - `OPERATION_RATING_ENGINE = "RATING_ENGINE"`;
  - `scripts.run_rating_engine.main(argv=None, *, trigger_type=TRIGGER_SCHEDULED, trigger_source=None, now=None) -> dict`. Its summary is `RatingRun.summary()` plus `calibration`, and the dedup key is `rate:<IST date>`.
- The registration pattern is Phase 2a's TIP_TRACKING (2a plan Task 7). `git log --oneline -1 -- scripts/run_tip_tracker.py` names the commit, and `git show <that commit> -- app/operation_*.py app/schedule_orchestration.py` shows each touch point as merged.

- [ ] **Step 1: Write the failing script test**

Create `tests/test_run_rating_engine.py`:

```python
"""The RATING_ENGINE CronJob entrypoint: one completed execution per IST day."""
from __future__ import annotations

from datetime import datetime, timezone

import pytest
from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker

import scripts.run_rating_engine as rating_module
from app.db import Base
from app.models import OrchestrationExecution
from app.schedule_orchestration import OPERATION_RATING_ENGINE

FIRE = datetime(2026, 9, 29, 3, 30, tzinfo=timezone.utc)


@pytest.fixture
def session_local(monkeypatch):
    engine = create_engine("sqlite:///:memory:")
    Base.metadata.create_all(engine)
    factory = sessionmaker(bind=engine, autoflush=False, expire_on_commit=False)
    monkeypatch.setattr(rating_module, "SessionLocal", factory)
    return factory


def test_the_rating_runs_once_per_ist_day(session_local):
    first = rating_module.main([], now=FIRE)
    again = rating_module.main([], now=FIRE)

    assert (first["status"], first["sessionDate"], first["stocksExamined"]) == ("ok", None, 0)
    assert first["calibration"] == {"status": "insufficientSamples", "samples": 0}
    assert again["status"] == "skipped_duplicate_trigger"
    with session_local() as db:
        rows = (
            db.query(OrchestrationExecution)
            .filter_by(operation_name=OPERATION_RATING_ENGINE, status="COMPLETED")
            .order_by(OrchestrationExecution.id)
            .all()
        )
    assert [row.trigger_source for row in rows] == ["rate:2026-09-29"]
```

- [ ] **Step 2: Run it to verify it fails**

Run: `python -m pytest tests/test_run_rating_engine.py -v`
Expected: FAIL with `ImportError: cannot import name 'OPERATION_RATING_ENGINE'`.

- [ ] **Step 3: Declare the operation and its trigger policy**

In `app/schedule_orchestration.py`, directly after the `OPERATION_TIP_TRACKING = ...` line, add:

```python
# Tip-ledger spec §7: rates the universe and records the Rating engine's calls.
OPERATION_RATING_ENGINE = "RATING_ENGINE"  # scripts.run_rating_engine -- tip ledger Phase 2c
```

In `TRIGGER_POLICIES`, directly after the `OPERATION_TIP_TRACKING: TriggerPolicy(...)` entry, add:

```python
    OPERATION_RATING_ENGINE: TriggerPolicy(
        operation_name=OPERATION_RATING_ENGINE,
        trigger_type=TRIGGER_SCHEDULED,
        cadence=timedelta(days=1),
        requires_market_session=False,
        description=(
            "Tip-ledger spec §7: rates the universe at 09:00 IST Tue-Sat on the previous session's official bars, "
            "after TIP_TRACKING, and records the Rating engine's calls; recalibrates the short-term weights weekly."
        ),
    ),
```

- [ ] **Step 4: Write the script**

Create `scripts/run_rating_engine.py`:

```python
"""Tip-ledger spec §7: rates the universe once per official session and records the Rating engine's calls.

09:00 IST Tue-Sat, after the 08:45 tracker has closed yesterday's exits, so every call is first seen before the
open. Once a week it also recalibrates the short-term weights for the next run. Idempotent per session."""
from __future__ import annotations

import argparse
from datetime import datetime, timezone

from app.db import SessionLocal
from app.market_data.quality import NSE_TIMEZONE
from app.rating_job import rate_stocks, recalibrate_if_due
from app.schedule_orchestration import (
    ConcurrentExecutionError,
    OPERATION_RATING_ENGINE,
    TRIGGER_SCHEDULED,
    acquire_execution,
    complete_execution,
    fail_execution,
)


def main(
    argv: list[str] | None = None,
    *,
    trigger_type: str = TRIGGER_SCHEDULED,
    trigger_source: str | None = None,
    now: datetime | None = None,
) -> dict:
    argparse.ArgumentParser(description="Rate the universe for the latest official session.").parse_args(argv)
    now = now or datetime.now(timezone.utc)

    with SessionLocal() as session:
        claim = acquire_execution(
            session,
            operation_name=OPERATION_RATING_ENGINE,
            scope_key="GLOBAL",
            trigger_type=trigger_type,
            trigger_source=trigger_source or f"rate:{now.astimezone(NSE_TIMEZONE).date().isoformat()}",
            triggered_at=now,
        )
        if claim.is_duplicate:
            message = f"rating already completed (execution id={claim.existing.id})"
            print(message)
            return {"status": "skipped_duplicate_trigger", "reason": message}

        try:
            summary = rate_stocks(session, now=now).summary()
            summary["calibration"] = recalibrate_if_due(session, now=now)
        except BaseException as exc:  # noqa: BLE001 -- the only lock release on failure
            fail_execution(
                session, claim, started_at=now, failed_at=datetime.now(timezone.utc),
                failure_reason=f"{type(exc).__name__}: {exc}",
            )
            raise

        complete_execution(session, claim, started_at=now, completed_at=datetime.now(timezone.utc),
                           result_summary={"status": "ok", **summary})
    print(f"Rating engine: {summary}")
    return {"status": "ok", **summary}


def run() -> None:
    try:
        main()
    except ConcurrentExecutionError as exc:
        print(f"Skipping: {exc}")


if __name__ == "__main__":
    run()
```

Before writing it, open `scripts/run_tip_tracker.py` as merged. If its `acquire_execution`, `fail_execution` or `complete_execution` calls differ from the ones above, match the merged file.

- [ ] **Step 5: Run the script test**

Run: `python -m pytest tests/test_run_rating_engine.py -v`
Expected: PASS.

- [ ] **Step 6: Register the runnable job, recovery policy and verifier**

Run `grep -n "OPERATION_TIP_TRACKING" app/*.py`. Add `OPERATION_RATING_ENGINE` to the same import block in every file it lists. Then add these entries.

`app/operation_entrypoints.py`, inside the `RUNNABLE_JOBS` tuple, right after the `TIP_TRACKING` job:

```python
        RunnableJob(
            operation_name=OPERATION_RATING_ENGINE,
            label="Rate Stocks",
            module="scripts.run_rating_engine",
            kwargs={"argv": []},
        ),
```

`app/operation_recovery.py`, in `RECOVERY_POLICIES`, right after the `OPERATION_TIP_TRACKING` entry:

```python
    OPERATION_RATING_ENGINE: RecoveryPolicy(
        operation_name=OPERATION_RATING_ENGINE,
        repeatability=REPEATABLE,
        depends_on=(OPERATION_TIP_TRACKING,),
        grace=timedelta(hours=4),
        rationale=(
            "Tip ledger Phase 2c. Idempotent per stock session -- one stock_ratings row per stock and session, "
            "and an unchanged verdict keeps its tip. Ordered after TIP_TRACKING so yesterday's exits are closed "
            "first. Worth repairing: a missed run leaves the session unrated and a changed verdict's call open."
        ),
    ),
```

`app/operation_verification.py`: add this function directly after `_verify_tip_tracking`. Add `OPERATION_RATING_ENGINE: _verify_rating_engine,` to `VERIFIERS`, right after the `OPERATION_TIP_TRACKING` entry.

```python
def _verify_rating_engine(session: Session, *, now: datetime) -> OperationVerification:
    """Tip ledger Phase 2c. A run with no new session is ordinary; one that did not report what it examined is not."""
    source = "scripts/run_rating_engine.py (rating result_summary)"
    summary, refusal = _summary_of(session, OPERATION_RATING_ENGINE)
    if refusal is not None:
        return refusal
    examined = summary.get("stocksExamined")
    if examined is None:
        return OperationVerification(
            operation_name=OPERATION_RATING_ENGINE, verdict=NOT_VERIFIABLE, source=source,
            detail="the run recorded no stocksExamined count, so what it rated is unknown",
        )
    return OperationVerification(
        operation_name=OPERATION_RATING_ENGINE, verdict=VERIFIED, source=source,
        detail=(
            f"rated {summary.get('stocksRated', 0)} of {examined} stock(s) for session {summary.get('sessionDate')}; "
            f"tips {summary.get('tips') or {}}, {summary.get('stocksFailed', 0)} failed; "
            f"calibration {(summary.get('calibration') or {}).get('status')}"
        ),
    )
```

The merged `_verify_tip_tracking` may treat `tipsFailed > 0` as something other than VERIFIED (2a's final review made that verifier "honest"). If so, give `stocksFailed` the same treatment here, in the same shape.

If the grep lists another registry (for example `app/operation_dependencies.py`), add `RATING_ENGINE` there too, following the shape of the TIP_TRACKING entry.

- [ ] **Step 7: Bump the verification census**

In `tests/test_operation_verification_s7.py`, in the test whose docstring ends with "Tip ledger Phase 2a moves it to 22/30 …":
- Change `assert len(declared) == 30` to `== 31`.
- Change `assert len(set(VERIFIERS) & declared) == 22` to `== 23`.
- Append this paragraph to the docstring:

```
    Tip ledger Phase 2c moves it to 23/31: RATING_ENGINE, with its verifier in
    the same commit; the unowned set is unchanged.
```

If main already moved past 22/30, bump whatever numbers are there by one each, and name this phase the same way.

- [ ] **Step 8: Add the CronJob**

Create `deploy/k8s/base/rating-engine-cronjob.yaml`. It needs only `DATABASE_URL`, because the run reads stored data only.

```yaml
# Tip-ledger spec §7: rates the universe at 09:00 IST Tue-Sat on the previous session's official bars, after the
# 08:45 tip tracker, so every Rating-engine call is first seen before the open. Reads stored data only.
apiVersion: batch/v1
kind: CronJob
metadata:
  name: market-agent-rating-engine
  namespace: market-agent
spec:
  schedule: "30 3 * * 2-6"
  timeZone: "Etc/UTC"
  concurrencyPolicy: Forbid
  startingDeadlineSeconds: 3600
  successfulJobsHistoryLimit: 3
  failedJobsHistoryLimit: 3
  jobTemplate:
    spec:
      backoffLimit: 1
      ttlSecondsAfterFinished: 86400
      activeDeadlineSeconds: 1800
      template:
        spec:
          restartPolicy: Never
          initContainers:
            - name: wait-for-db
              image: marksy-api:local
              imagePullPolicy: IfNotPresent
              command: ["python", "-m", "scripts.wait_for_db"]
              env:
                - name: DATABASE_URL
                  valueFrom:
                    secretKeyRef:
                      name: market-agent-secrets
                      key: DATABASE_URL
          containers:
            - name: rating-engine
              image: marksy-api:local
              imagePullPolicy: IfNotPresent
              command: ["python", "-m", "scripts.run_rating_engine"]
              env:
                - name: DATABASE_URL
                  valueFrom:
                    secretKeyRef:
                      name: market-agent-secrets
                      key: DATABASE_URL
```

In `deploy/k8s/base/kustomization.yaml`, under `resources:`, right after `- tip-tracker-provisional-cronjob.yaml`, add:

```yaml
  - rating-engine-cronjob.yaml
```

- [ ] **Step 9: Run the operations consistency tests**

```bash
python -m pytest tests/test_run_rating_engine.py tests/test_run_tip_tracker.py tests/test_operation_verification_s7.py \
  tests/test_operation_verification.py tests/test_operation_recovery.py tests/test_operation_entrypoints.py \
  tests/test_operation_dependencies.py tests/test_recovery_cadence_contract.py tests/test_recovery_sweep.py \
  tests/test_schedule_orchestration.py tests/test_schedule_registry.py tests/test_scheduler_producer_inventory.py \
  tests/test_cronjob_manifests.py tests/test_api_operations.py tests/test_api_operations_health.py \
  tests/test_operations_health.py tests/test_operations_metrics.py tests/test_job_eligibility.py \
  tests/test_epic317_regressions.py tests/test_market_day_audit.py tests/test_operational_checkpoint.py -v
```

Expected: all PASS. These files pin the operation, CronJob and producer inventories. When one fails, read its assertion:
- If it pins a count or a named set, add RATING_ENGINE (or the manifest) in that file's existing style, with a one-line docstring or comment naming this phase. Phase 2a's TIP_TRACKING commit shows where each one went.
- If it asserts a property the new operation lacks (for example a claim site `scheduler_producers` can't parse), fix the code, not the test.

Compare each failure with `main` (Global Constraints) before editing a test.

- [ ] **Step 10: Commit**

```bash
git add scripts/run_rating_engine.py app/schedule_orchestration.py app/operation_entrypoints.py app/operation_recovery.py \
  app/operation_verification.py deploy/k8s/base/rating-engine-cronjob.yaml deploy/k8s/base/kustomization.yaml \
  tests/test_run_rating_engine.py tests/test_operation_verification_s7.py
git add -u tests app
git commit -m "Rating engine: RATING_ENGINE operation, 09:00 IST Tue-Sat CronJob

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

Run `git status` before committing. Check that `git add -u tests app` staged only the census and registry updates from Step 9.

---

### Task 9: Regression set, PR, merge, deploy, verify

**Files:** none new.

- [ ] **Step 1: Run the regression set**

```bash
rm -f "$TEMP/marksy-pytest-default.db"
python -m pytest tests/test_rating_history_migration.py tests/test_rating_engine.py tests/test_rating_inputs.py \
  tests/test_marksy_rating_tips.py tests/test_rating_job.py tests/test_run_rating_engine.py \
  tests/test_marksy_prediction_tips.py tests/test_tip_prediction_link_migration.py \
  tests/test_tip_ledger.py tests/test_tip_ledger_guard.py tests/test_tip_ledger_migration.py tests/test_tip_matching.py \
  tests/test_tip_daily_progress_migration.py tests/test_tip_tracker.py tests/test_tip_minute_bars.py \
  tests/test_tip_tracking_job.py tests/test_run_tip_tracker.py tests/test_prediction_outcome_monitor.py \
  tests/test_api_tips*.py tests/test_external_tip_*.py tests/test_operation_verification_s7.py -v
python -m alembic heads
```

Expected: all PASS, apart from failures that also occur on `main` (Global Constraints). There is one head, `0185_rating_history`.

A new failure here can only come from a rating tip appearing beside the other tips:
- in a count or a `.one()` over `tips`;
- in a reader that selects ACTIVE tips without `prediction_id`.

In that case, scope the reader or the assertion with `_external_tips_only()`, or with `Tip.parser_version.is_distinct_from(PARSER_RATING_ENGINE)`, and name it in the PR.

- [ ] **Step 2: Push and open the PR**

```bash
git push -u origin feat/tip-ledger-rating-engine
gh pr create --title "Tip ledger Phase 2c: rating-engine port" --body "$(cat <<'EOF'
Ports marksy-os RatingEngine and RatingCalibration to the backend (tip-ledger spec §7). Every verdict is kept in
rating history; BUY/SELL verdicts are direction-only tips of the Marksy channel's Rating engine, tracked and scored
by the unchanged Phase 2a tracker.

- `app/rating_engine.py`: engine + calibration, float for float the Kotlin (figures pinned from the compiled app)
- `app/rating_inputs.py`: technicals, 3M vs NIFTY, 52-week range, volatility, beta, seasonality from official daily bars
- `stock_ratings` (migration 0185): one row per stock per session, both horizons, weight-free factor scores, the close used, config version
- SHORT = 20 / LONG = 250 sessions; entry = the first price after the rating (§6.3); a changed verdict (incl. NOT_ENOUGH_DATA) is a source exit; a data gap is not a verdict
- Weekly calibration on clean closed calls (`PredictionOutcome.actual_return`), recorded in `rating_calibrations`; ratings use the latest adopted weights
- `RATING_ENGINE` operation: 09:00 IST Tue-Sat, after TIP_TRACKING; `GET /tips` no longer lists rating tips
- Nothing is fetched from Upstox: `UPSTOX_FUNDAMENTALS` is COST_UNKNOWN and blocked, so valuation, quality and ownership are absent and LONG is NOT_ENOUGH_DATA until it is verified FREE

Tests: engine/calibration parity, inputs parity, rating tips through the tracker, runner, calibration runner, script,
operations consistency and the tip/tracker/prediction regression files.

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```

- [ ] **Step 3: Merge and deploy**

```bash
gh pr merge --merge --delete-branch
ssh marksy@103.178.160.233 'bash ~/deployment/server/vps-deploy.sh'
```

Expected: the deploy script finishes, and its migrate Job applies `0185_rating_history`.

- [ ] **Step 4: Verify production**

```bash
echo "select version_num from alembic_version; select count(*) from stock_ratings; select count(*) from rating_calibrations;" \
  | ssh marksy@103.178.160.233 'KUBECONFIG=~/.kube/config kubectl -n market-agent exec -i deploy/postgres -- sh -c '"'"'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB"'"'"''
ssh marksy@103.178.160.233 'KUBECONFIG=~/.kube/config kubectl -n market-agent get cronjob market-agent-rating-engine'
ssh marksy@103.178.160.233 'KUBECONFIG=~/.kube/config kubectl -n market-agent create job --from=cronjob/market-agent-rating-engine rating-engine-first-run && KUBECONFIG=~/.kube/config kubectl -n market-agent wait --for=condition=complete job/rating-engine-first-run --timeout=1800s; KUBECONFIG=~/.kube/config kubectl -n market-agent logs job/rating-engine-first-run; KUBECONFIG=~/.kube/config kubectl -n market-agent get job rating-engine-first-run -o jsonpath="{.status.startTime} {.status.completionTime}"'
echo "select session_date, short_verdict, long_verdict, count(*) from stock_ratings group by 1, 2, 3 order by 1, 2, 3;
select status, horizon_sessions, direction, count(*) from tips where parser_version = 'RTG-001' group by 1, 2, 3 order by 1, 2, 3;
select version, adopted, samples, test_samples, trend_weight, seasonality_weight from rating_calibrations order by id desc limit 1;" \
  | ssh marksy@103.178.160.233 'KUBECONFIG=~/.kube/config kubectl -n market-agent exec -i deploy/postgres -- sh -c '"'"'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB"'"'"''
```

The first run is a real rating: its BUY/SELL tips are first seen at the run's time, and §6.1 dates their entry from that moment.

Expected:
- `alembic_version` is `0185_rating_history`, and the CronJob is listed.
- The log prints `Rating engine: {'sessionDate': ..., 'stocksExamined': N, ...}` with no traceback, and `stocksFailed` is 0.
- `stock_ratings` holds one row per rated universe stock for the latest session.
- LONG is `NOT_ENOUGH_DATA` on every row (decision 5).
- Every `RTG-001` tip is ACTIVE with `horizon_sessions` 20.
- The last query shows a calibration run, or the log's `calibration` says `insufficientSamples`.
- Start to completion takes under 15 minutes, so the scheduled 09:00 run finishes before the 09:15 open. If it takes longer, say so in the summary: later-rated stocks then take a session-0 intraday entry instead of the open.

- [ ] **Step 5: Clean up the worktree**

```bash
cd /c/AIAgent/marksy-api && git worktree remove ../marksy-api-phase2c && git checkout main && git pull
```

## Summary

Phase 2c puts the rating engine into the central ledger:
- The backend rates its universe once per official session, with the ported engine, and keeps every verdict with its weight-free factor scores and the close it read.
- BUY and SELL become direction-only tips of the Marksy channel's Rating engine: SHORT for 20 sessions and LONG for 250.
- The unchanged tracker enters them at the first price after the call and scores them at their horizon.
- A changed verdict is a source exit, so the engine can't drop a call quietly.
- Weekly calibration on Marksy's own closed calls tunes the short-term weights, and every rating records which weights it used.
- Nothing comes from Upstox. Until `UPSTOX_FUNDAMENTALS` is verified FREE, valuation, quality and ownership are absent, so LONG stays NOT_ENOUGH_DATA; SHORT rates with the Marksy call or with trend, growth and seasonality.

Phase 3b serves the Rating engine's scorecard and calls, and Phase 4b removes the on-device engine.

## Validation

- Task 1: `tests/test_rating_history_migration.py`, `tests/test_tip_ledger_migration.py`, `tests/test_tip_ledger.py`, `alembic heads`
- Tasks 2–3: `tests/test_rating_engine.py`
- Task 4: `tests/test_rating_inputs.py`, `tests/test_rating_engine.py`
- Task 5: `tests/test_marksy_rating_tips.py`, `tests/test_marksy_prediction_tips.py`, `tests/test_tip_ledger.py`, `tests/test_tip_tracking_job.py`, `tests/test_api_tips.py`
- Task 6: the import check, `tests/test_rating_job.py`, `tests/test_marksy_rating_tips.py`
- Task 7: the import check, `tests/test_rating_job.py`, `tests/test_rating_engine.py`
- Task 8: `tests/test_run_rating_engine.py` plus the operations consistency set in Task 8 Step 9
- Task 9: the regression set in Task 9 Step 1, `alembic heads`, and the production checks in Task 9 Step 4
