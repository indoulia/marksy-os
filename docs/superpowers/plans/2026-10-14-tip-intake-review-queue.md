# Tip Intake Review Queue Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `tips` holds only calls that passed intake. Calls that fail, or still wait for a price, live in `tip_intake_reviews`. The tracker promotes verified pending calls. Admins resubmit rejected calls (same gate, no overrides, anti-hindsight) or dismiss them. Migration 0193 moves today's failed rows out verbatim, and no scorecard number moves.

**Architecture:**
- **Part A (marksy-api, one PR, kept as a draft until gates G3–G5 pass).**
  - `app/tip_claimed_time.py` (new, pure) decides when scoring starts (spec §4) and when an admission keeps its original start (§6 AH1/AH2).
  - `app/tip_intake_review.py` (new) builds queue items from a candidate tip, writes the append-only events, and promotes an item into `tips` (§8).
  - `app/tip_ledger.py` gains the gate (`evaluate` + `admit_or_queue`). Every tip-creating path calls it: intake, revision, re-parse, Marksy predictions, rating tips and admin resubmit.
  - The model-level `CheckConstraint` plus the ORM guard enforce the validated-only invariant. Migration 0193 adds it to production after moving the old rows.
  - `app/tip_scorecard_query.py` reads tips and non-promoted queue items through one union, so §8 counts are unchanged.
  - Read-only `scripts/sql/tip_intake_review_survey.sql` and `scripts/tip_intake_review_verify.py` (`snapshot` / `check`) prove the migration on synthetic fixtures (G5) and on production (G6).
- **Part B (admin-app, one PR, merged only after Part A is deployed).** An Intake Review page: list by state, resubmit with corrected terms, dismiss with a reason, history.
- **Part C (gates runbook, no code).** G3 survey → G4 backup + restore test on the VPS → G5 rehearsal on synthetic fixtures in a throwaway local Postgres → G6 user-approved merge and deploy. The user runs every production command, and each gate needs its own approval.

**Tech Stack:**
- Part A: Python 3.12, FastAPI 0.139, SQLAlchemy 2.0.52, Alembic 1.20.0, psycopg 3.3.5, pytest 8.4.2 + pytest-xdist 3.6.1. The suite runs on SQLite; migration and script tests run on PostgreSQL in a throwaway docker container.
- Part B: React 18.3, TypeScript 5.6, Vite 5.4, @tanstack/react-query 5.59, vitest 2.1, Testing Library 16, MSW 2.6.

**Spec:** `C:\AIAgent\marksy-os\docs\superpowers\specs\2026-10-14-tip-intake-review-queue-design.md` (branch `docs/tip-intake-review-queue`; D1–D11 below are reflected in it). The amendments to `2026-09-29-marksy-central-tip-ledger-design.md` are on the same branch. Executors read both.

## Global Constraints

- **Agent and production.** No production read, write, merge or deployment by the agent. Each production step in Part C is a command the user runs with `!` after saying go.
- **Merge gate.** The marksy-api PR is opened as a **draft** titled `[DO NOT MERGE before G3–G5] …` and stays unmerged until G3, G4 and G5 pass: `vps-deploy.sh` builds `main`, so any deploy after a merge applies 0193. The admin-app PR merges only after Part A is deployed.
- **Scorecard semantics.** §8 definitions, the trust score and the success, failure and hit-rate percentages are unchanged. `app/tip_scorecard.py` (`compute_metrics`, `_trust`) is not edited.
- **Trust Score ≠ Prediction Score.** The channel Trust Score and the Prediction Score / Prediction Trust Score (`predictions`, `prediction_outcomes`, `app/prediction_trust_score.py`) stay separate. No queue transition changes an existing tip's outcome, status, close or returns, or writes a prediction score or outcome. The only prediction write is today's: a Marksy call that fails intake closes its prediction INVALIDATED.
- **No production data on the laptop (D7).** No production database, dump or secret is copied to the laptop. Migration rehearsal uses synthetic fixtures in a throwaway local Postgres that is deleted afterwards.
- **Scoring start.** It defaults to server receipt R. A claimed time is used only under spec §4 (CT1–CT8); a refused claim never comes back. A claim within 120 s before R is treated as no claim (T = R, no bar read; D11). A missing timezone is never inferred.
- **No admin overrides.** A resubmit runs the full gate. No request field sets a scoring start or claimed time (`extra="forbid"`). Only REJECTED items can be resubmitted or dismissed.
- **Anti-hindsight.** An admission (resubmit or re-parse) keeps its original start only if no NSE market minute lies in [T0, A) (AH1). Otherwise scoring starts at A (AH2). An unknown calendar counts as "a market minute passed".
- **Validated-only invariant.** `ck_tips_validated_only` = `status <> 'PENDING_CHECK' AND status <> 'UNSCORABLE' AND COALESCE(reason, '') NOT IN ('NO_DIRECTION', 'INCONSISTENT_LEVELS', 'UNRESOLVED_SYMBOL', 'ENTRY_OFF_MARKET', 'CLAIMED_TIME_OLDER', 'CLAIMED_TIME_INVALID', 'CLAIMED_TIME_UNVERIFIED')`. It is declared on the model and in 0193, and the ORM guard enforces it too.
- **Immutability.** Tips that passed intake, receipts, queue items and review events are never deleted. Events are never updated. Migration 0193 is the one place rows leave `tips`, verbatim.
- **Tests first.** Every task starts with a failing test, confirmed red. Targeted runs use `python -m pytest <files> -q -p no:cacheprovider`; full runs use `python -m pytest -n 6 --dist loadfile -q -p no:cacheprovider` with the run's own `DATABASE_URL=sqlite:///…`.
- **Comments.** One line at most, only for a non-obvious WHY. No ticket-less essays; docstrings stay short, in the house style.
- **Commits.** End every message with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`. Never stage `.artifacts/`.
- **Environment.** Windows + Git Bash; marksy-api at `/c/AIAgent/marksy-api`, admin-app at `/c/AIAgent/admin-app`. The agent cannot run node/npm here, so admin-app checks are handed to the user as `!` commands.

## Review Focus

Each line below names an input the spec implies but whose behaviour a person would reasonably question. Each one gets its own test in the owning task.

1. **The everyday phone receipt.** `devicePostedAt` arrives a few seconds before R during market hours. Expected (D11): T = R (`SERVER_RECEIPT`), C kept for the record, no bar read for the claim (only `first_seen_price`'s one Upstox call), never a queue, also during an Upstox outage. Test in Task 3.
2. **A claim on a call whose symbol does not resolve.** Expected: queued as `UNRESOLVED_SYMBOL` with T = R, not `CLAIMED_TIME_UNVERIFIED`. CT8 needs a stock and a direction; without them T = R and §6.2 rejects. Test in Task 3.
3. **A copy of a call an admin already dismissed.** Expected: the receipt attaches to the dismissed item inside its horizon, no new item is created, and the unscorable count does not grow (decision D1). Test in Task 3.
4. **An admin fix whose terms equal an ACTIVE tip's key.** Expected: the item's receipts join that tip and the item is PROMOTED to it. There is never a second ACTIVE tip, and `uq_tips_active_match_key` is never hit. Test in Task 6.
5. **A migrated PENDING_CHECK row promoted after 0193.** Its payload is PostgreSQL `to_jsonb`, so numbers arrive as JSON numbers. Expected: the promoted tip's prices equal the original `Decimal`s exactly. Test in Task 8.

## Decisions this plan makes (D1–D11 folded into the spec; D7 and D11 decided by the user 2026-10-14)

- **D1 — Dismissed items stay matchable inside their horizon (§3 matching).** The spec says "PENDING_CHECK, or REJECTED within its horizon". Without DISMISSED, every later copy of a dismissed call would create a new REJECTED item and grow the unscorable count. Today a copy of a rejected call attaches instead. *Spec line to amend: §3 "Matching".*
- **D2 — Queue items carry `closed_at`.** For INTAKE items it is the scoring start; for migrated items it is the original `tips.closed_at`. §7 says `closed_at` = scoring start, but `app/tip_backfill.py:371` closed legacy rejections at a `seen` that can differ from `first_seen_at`. That would move terminal-basis windows at migration. *Spec lines: §5 column list, §7 mapping.*
- **D3 — Admin routes are `/api/v1/admin/intake-reviews…`**, not `/admin/tip-ledger/intake-reviews`. The existing router's prefix is `/admin` and its siblings are `/admin/receipts` and `/admin/tips`. *Spec §6 and central §9 name the other path.*
- **D4 — `tips.intake_review_id` has no FK.** `tip_intake_reviews.promoted_tip_id → tips.id` already links the pair, and a two-way FK cycle cannot be created on the suite's SQLite schema.
- **D5 — 0193 moves the rows before it adds the new `tips` columns.** Each payload is then the 0192-shaped row and the downgrade checksum compares like with like. The spec's step 1 lists all DDL first.
- **D6 — The G3 survey is a pure-SQL file** (`scripts/sql/tip_intake_review_survey.sql`). It has to run before the PR merges, through `psql` in the postgres pod. A small `.py` wrapper runs the same file on local databases.
- **D7 — No production data on the laptop (user, 2026-10-14).** G4's backup is restore-tested on the VPS in a throwaway Postgres pod. G5 rehearses 0193 in a throwaway local Postgres built by `alembic upgrade 0192_tip_entry_check`, with synthetic minimum fixtures covering every status, reason and reference category G3 reports: success, abort paths, exact rollback, receipt links and scorecard parity. Temporary data is deleted after verification. The survey no longer sizes tables for a snapshot; it counts claimed-time formats instead.
- **D8 — CT8 touch checks include the minute bar covering C itself,** not only bars "starting in W". A touch inside that minute cannot be placed before or after C, so ambiguous evidence keeps T = R.
- **D9 — A re-parse of a receipt whose claim is refused (CT2/3/5/6)** scores from the re-parse time when a market minute has passed since R (AH2 makes the claim moot). Otherwise it is queued with that reason.
- **D10 — A Marksy prediction identical to a queued Marksy call follows the queued item.** Its prediction is closed INVALIDATED and no second item is created. Today it becomes a MERGED_DUPLICATE of the rejected tip.
- **D11 — Option (b) (user, 2026-10-14).** A claim less than 120 s before R is treated as no claim: T = R (`SERVER_RECEIPT`), C is stored, no bar read. An older claim that cannot be verified (bars unavailable or unpriced) is still queued `CLAIMED_TIME_UNVERIFIED`, never admitted silently at a new start. Implemented in `scoring_start`'s CT4 test (`claimed > received - CLAIM_SKEW`).
- **Timezone-less claims (user, 2026-10-14).** CT3 (naive → `CLAIMED_TIME_INVALID`) stays as the interim rule. G3 collects format counts; the final acceptance policy is a later user decision. No code infers a timezone.

## Amendment A1 — migrated duplicate followers (user decision 2026-10-14; spec §5, §6, §7, §9, §10, §12 G3)

Today `register_prediction` (`app/marksy_tips.py:174`) and the legacy backfill (`app/tip_backfill.py:373`) store an identical later Marksy call as a MERGED_DUPLICATE tip whose `merged_into_tip_id` points at a rejected tip. Those followers move with their parents as terminal MERGED_DUPLICATE items. They are never scored, promoted, resubmitted or dismissed. The scorecard loader already excludes MERGED_DUPLICATE tips (`app/tip_scorecard_query.py:502`, `app/tip_scorecard.py:150`), so counts stay equal. Each item below binds the named task, tests first. Integrity checks are extended, never relaxed.

- **Task 6 (admin).**
  - Model `TipIntakeReview.merged_into_review_id: Mapped[int | None] = mapped_column(ForeignKey("tip_intake_reviews.id"))`; vocabulary `REVIEW_MERGED_DUPLICATE = "MERGED_DUPLICATE"`.
  - Resubmit and dismiss refuse a MERGED_DUPLICATE item, by the same path as a PENDING item (409 over HTTP).
  - Admitting or dismissing a parent leaves its followers MERGED_DUPLICATE, with no tip and no event of their own.
  - The list accepts `state=MERGED_DUPLICATE`. The detail adds `mergedIntoReviewId` (the parent item's public id) and `followerIds`.
  - Tests: `test_a_merged_duplicate_item_cannot_be_resubmitted_or_dismissed`, `test_admitting_or_dismissing_a_parent_leaves_its_followers_inert`, and the relationship fields in the HTTP test.
- **Task 7 (scorecards).** The union skips state MERGED_DUPLICATE. Test `test_a_merged_duplicate_item_is_counted_nowhere`: the scorecard is the same with and without a follower item.
- **Task 8 (migration).**
  - DDL `merged_into_review_id INTEGER REFERENCES tip_intake_reviews (id)`, matching the model.
  - F comes from a recursive CTE over `merged_into_tip_id` from M, restricted to status MERGED_DUPLICATE. The rows moved are M ∪ F.
  - State: status MERGED_DUPLICATE → MERGED_DUPLICATE, whatever the reason. After the insert, an UPDATE sets each follower's `merged_into_review_id` to its direct parent's item. Abort if any MERGED_DUPLICATE item is left without one.
  - New aborts: a row outside M ∪ F whose `merged_into_tip_id` or `revises_tip_id` points into M ∪ F; a moved MERGED_DUPLICATE row whose target is not moved.
  - Receipt relink count, checksum and delete all cover M ∪ F.
  - Downgrade re-inserts parents before followers (chain-depth order) and restores `merged_into_tip_id` verbatim from the payload.
  - Fixtures in `tests/_tip_0193_pg.py`:
    - a rejected Marksy parent;
    - a direct follower carrying one receipt and one `external_tip_comparisons` row;
    - a second follower pointing at the first (the two-level chain).
  - Tests:
    - `test_followers_move_with_their_parents_and_keep_each_level`: states, `merged_into_review_id` per level, `public_id`, times, verbatim payload, receipt and legacy rows.
    - `test_a_non_duplicate_pointing_into_the_move_set_aborts`, parametrized over `merged_into_tip_id` and `revises_tip_id`.
    - `test_a_moved_duplicate_whose_parent_stays_aborts`.
    - Followers added to the downgrade test: restored after parents, `merged_into_tip_id` intact, checksums equal, then upgrade again and get the same items.
    - Followers added to the re-run no-op test.
    - `test_no_reference_dangles_after_upgrade_or_downgrade`: LEFT JOIN checks over `tips.merged_into_tip_id`, `tips.revises_tip_id`, `tip_receipts.tip_id` / `intake_review_id` and `tip_intake_reviews.merged_into_review_id`.
    - Followers added to the scorecard-parity test.
- **Task 9 (survey and verify).**
  - New survey sections, all counts and ids with no tip content:
    - `followers` (depth, count);
    - `unexpected_merged_or_revised` (ref, status, count);
    - `stranded_duplicates` (count).
  - `move_set`, its checksum and both reference sections cover M ∪ F.
  - Verify `check` adds: each MERGED_DUPLICATE item's parent item has `origin_tip_id` equal to its payload's `merged_into_tip_id`; the moved count equals |M ∪ F|; no reference dangles.
  - Test: the fixture's depths 1 and 2 are counted once each, and both abort sections are empty.
- **Task 11 (admin-app).** The detail shows "Follows …" and "Duplicates: …". There is no new tab. Add one assertion to the existing detail test.
- **Part C G3.** `unexpected_merged_or_revised` and `stranded_duplicates` must be empty. If `followers` shows a depth beyond 2, add a fixture at that depth before G5. Any other unexpected relationship stops the rollout and is reported with its counts.

## Amendment A2 — Marksy calls are dismiss-only (user decision 2026-10-14; spec §6)

- **Task 6 (admin).** `resubmit_review` refuses any item whose call Marksy generated (prediction-backed or rating-engine; use the codebase's existing Marksy-source test) with `LedgerAdminConflict`, which is 409 over HTTP, before any gate run or write. Dismiss stays allowed, with a reason and a note. No path promotes a Marksy item. Remove the `evaluate` scan-bar hook if resubmit was its only caller. The detail view exposes `resubmittable` (false for Marksy, MERGED_DUPLICATE and non-REJECTED items).
  - Tests:
    - `test_a_marksy_call_can_be_dismissed_but_never_resubmitted`: at the service layer and over HTTP, a resubmit with and without corrected terms gets 409. No tip is created. The item is unchanged (terms, times, reason, payload). No event is written for the refused attempt, unless refusals are audited as events by design (say which in the report).
    - `test_dismissing_a_marksy_call_moves_no_prediction_or_scorecard_figure`: the prediction stays INVALIDATED with its outcome/score rows unchanged. The channel `scorecard()` is equal before and after the dismissal.
    - A non-Marksy resubmit still works (the existing tests stay green).
- **Task 11 (admin-app).** The Resubmit control shows only when `resubmittable` is true. The server enforces regardless.

## Amendment A3 — promotion lock proven on PostgreSQL; CI runs on PostgreSQL (user, 2026-10-14)

CI (`.github/workflows/test.yml`) runs the whole suite on a `postgres:16-alpine` service with `DATABASE_URL` pointing at it. Local runs so far used SQLite, which ignores `SELECT … FOR UPDATE`.

- **Task 8b (new, after Task 8). Concurrent promotion on PostgreSQL.**
  - Test file `tests/test_tip_intake_review_promotion_lock_pg.py`, using Task 8's `scratch_db()`. It skips only when `DATABASE_URL` is not PostgreSQL, so it runs in CI.
  - Seed one PENDING_CHECK item whose check passes, with at least one receipt.
  - Run A and B in two threads, each with its own engine connection and transaction, each calling the tracker's real promotion entry point (`check_pending_item` / the resolve pass).
  - Coordinate them so both contend for the same row lock:
    - A acquires the item lock. It is then held inside the transaction by a test-only patch of a function called after the lock, which blocks on a `threading.Event`.
    - B starts. A third connection polls `pg_stat_activity` / `pg_locks` until B's backend shows `wait_event_type = 'Lock'` on that relation, which proves B is waiting on the row lock. Then the test releases A.
  - Assert:
    - A promotes.
    - B returns without raising, in particular no IntegrityError or duplicate key, and skips the already-PROMOTED item.
    - Exactly one `tips` row has the item's `public_id`.
    - The item is PROMOTED with that `promoted_tip_id`.
    - The receipts point at the tip exactly once.
    - There is exactly one promotion event.
  - Mutation check, recorded in the report and not committed: with the lock removed, the test fails.
  - If B hits a duplicate key or promotes from a stale read, fix production, e.g. re-read the locked row with `populate_existing()`. Never weaken the lock. Keep `tips.public_id` unique as a separate safeguard.
  - Add the file to G5's command and to Task 10's PostgreSQL runs.
- **Task 10.**
  - Also run the full suite with `DATABASE_URL` on a scratch database on the local PostgreSQL 16 test server, serially as CI does, and once on `main` (027eda3) the same way for a PostgreSQL baseline.
  - A failure that is not in that baseline is a regression.
  - Record CI's known `test_openapi_contract_freshness` pydantic difference separately.

## File Structure

**marksy-api**
- `app/tip_claimed_time.py` (new): `Claim`, `CallLevels`, `ScoringStart`, `session_of`, `market_minutes_between`, `scoring_start`, `admission_start`. No DB access and no clock.
- `app/tip_intake_review.py` (new): `lock_match_key` (moved from `tip_ledger`), `tip_payload`, `tip_from_payload`, `review_from_tip`, `item_state`, `record_event`, `promote`.
- `app/tip_vocabulary.py`: claim, start-basis, review-state, event and dismissal vocabulary; `INTAKE_QUEUE_REASONS`; `VALIDATED_ONLY_SQL`.
- `app/tip_first_seen_price.py`: `covering_price` (extracted from `_intraday_price`, no behaviour change).
- `app/models.py`: `TipIntakeReview`, `TipIntakeReviewEvent`, the new `Tip`/`TipReceipt` columns, and `ck_tips_validated_only`.
- `app/tip_ledger_guard.py`: `TipNotValidatedError`; tips refuse unvalidated inserts/updates; items and events are never deleted, and events are never updated.
- `app/tip_ledger.py`: `GateDecision`, `evaluate`, `admit_or_queue`, queue-aware matching and attachment, `IntakeResult.review`, and re-parse through the gate with AH.
- `app/marksy_tips.py`: `register_prediction` and `open_rating_tip` go through `admit_or_queue`.
- `app/prediction_outcome_monitor.py`: `invalidate_queued_prediction`.
- `app/recommendations.py`: handles a queued registration.
- `app/tip_tracking_job.py`: `resolve_pending_entry_checks` and `check_pending_item` over queue items.
- `app/tip_scorecard_query.py`: `_fact_source` union; conditions take the source.
- `app/tip_ledger_admin.py`: `resubmit_review`, `dismiss_review`.
- `api/schemas/admin_tip_ledger.py`, `api/services/admin_tip_ledger.py`, `api/routers/admin_tip_ledger.py`: the intake-review routes.
- `api/schemas/tips.py`: `TIP_STATUS_IN_REVIEW`.
- `api/services/tips.py`: claim plumbing and `IN_REVIEW` responses.
- `api/routers/tips.py`: webhook `in_review` status.
- `migrations/versions/0193_tip_intake_reviews.py`.
- `scripts/sql/tip_intake_review_survey.sql`, `scripts/tip_intake_review_survey.py`, `scripts/tip_intake_review_verify.py`.
- `docs/api/openapi.json` (regenerated).
- Tests (new):
  - `tests/test_tip_claimed_time.py`
  - `tests/test_tip_intake_review_guard.py`
  - `tests/test_tip_intake_review_gate.py`
  - `tests/test_tip_intake_review_promotion.py`
  - `tests/test_tip_intake_review_admin.py`
  - `tests/test_api_admin_intake_review.py`
  - `tests/test_tip_intake_review_scorecards.py`
  - `tests/test_tip_intake_review_migration.py`
  - `tests/test_tip_intake_review_scripts.py`
  - `tests/_intake_review_helpers.py`
  - `tests/_tip_0193_pg.py`
- Tests (converted): `tests/test_tip_alerts.py`, `tests/test_tip_ledger.py`, `tests/test_tip_first_seen_price_intake.py`, `tests/test_marksy_prediction_tips.py`, `tests/test_run_tip_tracker.py`.

**admin-app**
- `src/api/types.ts`, `src/api/tipLedger.ts`
- `src/pages/IntakeReviewPage.tsx` (new) and `src/pages/IntakeReviewPage.test.tsx` (new)
- `src/pages/ReceiptReviewPage.tsx` (the `QUEUED` outcome text)
- `src/App.tsx`, `src/components/Layout.tsx`

---

## Part A — marksy-api

### Task 0: Branch and baseline

**Files:** none committed. The baseline lives in `/c/AIAgent/marksy-api/.artifacts/tip-intake-review/` (untracked; never staged).

- [ ] **Step 1: Branch from main**

```bash
cd /c/AIAgent/marksy-api
git status --short            # expect only "?? .artifacts/"
git switch main && git log --oneline -1   # expect 027eda3 or a later main
git switch -c feat/tip-intake-review-queue
```

- [ ] **Step 2: Record the current failing set on main**

```bash
cd /c/AIAgent/marksy-api
python -m pip install -q pytest-xdist==3.6.1
mkdir -p .artifacts/tip-intake-review
DATABASE_URL="sqlite:///$(cygpath -m "$PWD")/.artifacts/tip-intake-review/baseline.db" \
  python -m pytest -n 6 --dist loadfile -q -p no:cacheprovider -rfE 2>&1 | tee .artifacts/tip-intake-review/baseline.log | tail -3
grep -E "^(FAILED|ERROR) " .artifacts/tip-intake-review/baseline.log | sed 's/ - .*//' | sort -u > .artifacts/tip-intake-review/baseline-failures.txt
wc -l .artifacts/tip-intake-review/baseline-failures.txt
```

Expected: the summary line, and a short list of pre-existing failures (memory recorded 5 on 2026-10-01). Later full runs must show no failure that is not in this file.

---

### Task 1: The claimed-time policy (spec §4, §6 AH1/AH2)

**Files:**
- Create: `app/tip_claimed_time.py`
- Modify: `app/tip_vocabulary.py` (append), `app/tip_first_seen_price.py:41-55` (`_intraday_price` → `covering_price`)
- Test: `tests/test_tip_claimed_time.py`

**Interfaces:**
- Consumes: `PriceBar(at, open, high, low, close)` from `app/tip_tracker.py`; `MinuteBarsUnavailable` from `app/tip_minute_bars.py`; `MARKET_OPEN`/`MARKET_CLOSE` from `app/schedule_orchestration.py`; `NSE_TIMEZONE`.
- Produces:
  - `Claim(at: datetime, source: str, date_only: bool = False)`
  - `CallLevels(direction, entry_low, entry_high, target, stop_loss)`
  - `ScoringStart(at: datetime, basis: str, queue_reason: str | None = None, evidence: dict | None = None)`
  - `TradingDayOnOrBefore = Callable[[date], date]` and `BarsForDay = Callable[[date], list[PriceBar]]`
  - `session_of(at, on_or_before) -> date`, which raises `CalendarUnavailable`
  - `market_minutes_between(start, end, on_or_before) -> bool`
  - `scoring_start(*, received_at, claim, levels, on_or_before, bars_for_day: BarsForDay | None) -> ScoringStart`
  - `admission_start(original: ScoringStart, admitted_at, on_or_before) -> ScoringStart`
  - `covering_price(bars: list[PriceBar], at: datetime) -> Decimal | None` (in `app/tip_first_seen_price.py`)
  - New vocabulary:
    - `REASON_CLAIMED_TIME_OLDER|INVALID|UNVERIFIED`, `INTAKE_QUEUE_REASONS`
    - `CLAIM_DEVICE_POSTED_AT|TIP_AS_OF|REPORT_DATE`
    - `START_SERVER_RECEIPT|CLAIMED_NO_TRADING|CLAIMED_MARKET_VERIFIED|SERVER_RECEIPT_CLAIM_REJECTED|ADMIN_ADMISSION|LEGACY`
    - `REVIEW_REJECTED|PROMOTED|DISMISSED` (pending reuses `STATUS_PENDING_CHECK`), `REVIEW_ORIGIN_INTAKE|MIGRATED`
    - `EVENT_*`, `DISMISS_REASONS`, `ACTOR_SYSTEM|TRACKER`, `REPARSE_QUEUED`, `VALIDATED_ONLY_SQL`

- [ ] **Step 1: Write the failing tests**

```python
"""Intake review spec §4 and §6 AH1/AH2: when a call's scoring starts (pure policy, fixed calendar)."""

from __future__ import annotations

from datetime import date, datetime, time, timedelta, timezone
from decimal import Decimal

import pytest

from app.market_data.quality import NSE_TIMEZONE
from app.tip_claimed_time import CallLevels, Claim, ScoringStart, admission_start, scoring_start
from app.tip_minute_bars import MinuteBarsUnavailable
from app.tip_tracker import PriceBar
from app.tip_vocabulary import (
    CLAIM_DEVICE_POSTED_AT,
    CLAIM_REPORT_DATE,
    REASON_CLAIMED_TIME_INVALID,
    REASON_CLAIMED_TIME_OLDER,
    REASON_CLAIMED_TIME_UNVERIFIED,
    START_ADMIN_ADMISSION,
    START_CLAIMED_MARKET_VERIFIED,
    START_CLAIMED_NO_TRADING,
    START_SERVER_RECEIPT,
    START_SERVER_RECEIPT_CLAIM_REJECTED,
)

FRI, SAT, MON, TUE, WED, THU = (date(2026, 9, 25), date(2026, 9, 26), date(2026, 9, 28), date(2026, 9, 29),
                                date(2026, 9, 30), date(2026, 10, 1))
HOLIDAYS = {WED}
D = Decimal
BUY_STATED = CallLevels("BUY", D("100"), D("100"), D("110"), D("95"))
BUY_PLAIN = CallLevels("BUY", None, None, D("110"), D("95"))


def on_or_before(day):
    while day.weekday() >= 5 or day in HOLIDAYS:
        day -= timedelta(days=1)
    return day


def broken_calendar(day):
    raise RuntimeError("calendar down")


def ist(day, hour, minute=0, second=0):
    return datetime.combine(day, time(hour, minute, second), NSE_TIMEZONE)


def flat_bars(day, price="102", start=(9, 15), end=(15, 29)):
    bars, at, last = [], ist(day, *start), ist(day, *end)
    while at <= last:
        bars.append(PriceBar(at, D(price), D(price), D(price), D(price)))
        at += timedelta(minutes=1)
    return bars


def with_bar(bars, at, *, high=None, low=None):
    return [PriceBar(b.at, b.open, D(high) if high and b.at == at else b.high, D(low) if low and b.at == at else b.low,
                     b.close) for b in bars]


def start_for(received, claim_at=None, *, source=CLAIM_DEVICE_POSTED_AT, date_only=False, levels=BUY_STATED,
              bars=None, calendar=on_or_before):
    def bars_for_day(day):
        if bars is None:
            raise MinuteBarsUnavailable("upstox down")
        return bars
    claim = Claim(claim_at, source, date_only) if claim_at is not None else None
    return scoring_start(received_at=received, claim=claim, levels=levels, on_or_before=calendar,
                         bars_for_day=bars_for_day)


def test_without_a_claim_scoring_starts_at_server_receipt():
    start = start_for(ist(MON, 10))
    assert (start.at, start.basis, start.queue_reason) == (ist(MON, 10), START_SERVER_RECEIPT, None)


@pytest.mark.parametrize("report_day, reason", [(MON, None), (FRI, REASON_CLAIMED_TIME_OLDER)])
def test_a_report_date_is_not_a_time(report_day, reason):
    claim_at = datetime.combine(report_day, time(0), timezone.utc)
    start = start_for(ist(MON, 10), claim_at, source=CLAIM_REPORT_DATE, date_only=True)
    assert (start.at, start.basis, start.queue_reason) == (ist(MON, 10), START_SERVER_RECEIPT, reason)


@pytest.mark.parametrize("claim_at, reason", [
    (datetime(2026, 9, 28, 9, 0), REASON_CLAIMED_TIME_INVALID),  # no timezone
    (ist(MON, 10, 2, 1), REASON_CLAIMED_TIME_INVALID),  # 121 s after receipt
    (ist(MON, 10, 1), None),  # inside the skew: a claim at or after receipt cannot move the start
    (ist(MON, 9, 58, 1), None),  # D11: 119 s before receipt is no claim, and no bars are read
    (ist(MON, 9, 58), REASON_CLAIMED_TIME_UNVERIFIED),  # 120 s before needs bars, and Upstox is down
])
def test_a_claim_without_a_zone_or_near_receipt_never_moves_the_start(claim_at, reason):
    start = start_for(ist(MON, 10), claim_at)
    assert (start.at, start.basis, start.queue_reason) == (ist(MON, 10), START_SERVER_RECEIPT, reason)


def test_a_claim_seconds_before_the_open_is_no_claim_not_an_older_session():
    start = start_for(ist(MON, 9, 15, 20), ist(MON, 9, 14, 50))
    assert (start.at, start.basis, start.queue_reason) == (ist(MON, 9, 15, 20), START_SERVER_RECEIPT, None)


@pytest.mark.parametrize("claim_at, received, basis, reason", [
    (ist(FRI, 16), ist(SAT, 10), START_CLAIMED_NO_TRADING, None),
    (ist(FRI, 16), ist(MON, 9, 14), START_CLAIMED_NO_TRADING, None),
    (ist(FRI, 16), ist(MON, 9, 15), START_SERVER_RECEIPT, REASON_CLAIMED_TIME_OLDER),
    (ist(MON, 9), ist(MON, 10), START_SERVER_RECEIPT, REASON_CLAIMED_TIME_OLDER),  # pre-open belongs to Friday
    (ist(TUE, 16), ist(WED, 12), START_CLAIMED_NO_TRADING, None),  # Wednesday is a holiday
    (ist(WED, 11), ist(THU, 10), START_SERVER_RECEIPT, REASON_CLAIMED_TIME_OLDER),
    (ist(MON, 9, 14, 59), ist(MON, 10), START_SERVER_RECEIPT, REASON_CLAIMED_TIME_OLDER),
    (ist(MON, 15, 30), ist(MON, 16), START_CLAIMED_NO_TRADING, None),
])
def test_sessions_weekends_holidays_and_boundaries(claim_at, received, basis, reason):
    start = start_for(received, claim_at, bars=flat_bars(MON))
    assert (start.basis, start.queue_reason) == (basis, reason)
    assert start.at == (claim_at if basis == START_CLAIMED_NO_TRADING else received)


@pytest.mark.parametrize("claim_at, received", [
    (ist(MON, 9, 15), ist(MON, 10)),
    (ist(MON, 15, 29, 59), ist(MON, 15, 32)),  # one market minute in between, claim 121 s before receipt
])
def test_trading_between_claim_and_receipt_needs_the_minute_bars(claim_at, received):
    assert start_for(received, claim_at, bars=flat_bars(MON)).basis == START_CLAIMED_MARKET_VERIFIED
    assert start_for(received, claim_at, bars=None).queue_reason == REASON_CLAIMED_TIME_UNVERIFIED


def test_a_calendar_failure_makes_a_claim_unverifiable():
    start = start_for(ist(MON, 10), ist(MON, 9, 30), calendar=broken_calendar)
    assert start.queue_reason == REASON_CLAIMED_TIME_UNVERIFIED


@pytest.mark.parametrize("levels, touch, failed", [
    (BUY_PLAIN, {"high": "110"}, "TARGET_TOUCHED"),
    (BUY_PLAIN, {"low": "95"}, "STOP_TOUCHED"),
    (BUY_STATED, {"low": "100"}, "ENTRY_TOUCHED"),
])
def test_a_touch_between_claim_and_receipt_keeps_the_server_start(levels, touch, failed):
    bars = with_bar(flat_bars(MON), ist(MON, 9, 40), **touch)
    start = start_for(ist(MON, 10), ist(MON, 9, 30), levels=levels, bars=bars)
    assert (start.at, start.basis, start.queue_reason) == (ist(MON, 10), START_SERVER_RECEIPT_CLAIM_REJECTED, None)
    assert start.evidence["failed"] == failed


def test_the_minute_covering_the_claim_counts_as_inside_the_window():
    bars = with_bar(flat_bars(MON), ist(MON, 9, 30), high="110")  # touched in the very minute the claim names
    start = start_for(ist(MON, 10), ist(MON, 9, 30, 40), levels=BUY_PLAIN, bars=bars)
    assert start.basis == START_SERVER_RECEIPT_CLAIM_REJECTED


@pytest.mark.parametrize("at_claim, at_receipt, basis", [
    ("100", "104", START_SERVER_RECEIPT_CLAIM_REJECTED),  # backdating would buy cheaper
    ("104", "100", START_CLAIMED_MARKET_VERIFIED),  # an honest claim that costs the caller
])
def test_without_a_stated_entry_a_claim_counts_only_if_it_cannot_buy_cheaper(at_claim, at_receipt, basis):
    bars = flat_bars(MON, at_claim, end=(9, 44)) + flat_bars(MON, at_receipt, start=(9, 45))
    assert start_for(ist(MON, 10), ist(MON, 9, 30), levels=BUY_PLAIN, bars=bars).basis == basis


def test_bars_that_cannot_price_the_claim_leave_it_unverified():
    late_open = flat_bars(MON, start=(9, 45))
    assert start_for(ist(MON, 10), ist(MON, 9, 30), bars=late_open).queue_reason == REASON_CLAIMED_TIME_UNVERIFIED


def test_a_forged_earlier_time_never_beats_the_server_start():
    rally = flat_bars(MON, "100", end=(9, 59)) + flat_bars(MON, "109", start=(10, 0))
    same_day = start_for(ist(MON, 14), ist(MON, 9, 20), levels=BUY_PLAIN, bars=rally)
    day_before = start_for(ist(MON, 14), ist(FRI, 11), levels=BUY_PLAIN, bars=rally)
    assert (same_day.at, same_day.basis) == (ist(MON, 14), START_SERVER_RECEIPT_CLAIM_REJECTED)
    assert (day_before.at, day_before.queue_reason) == (ist(MON, 14), REASON_CLAIMED_TIME_OLDER)


def test_without_a_bar_source_ct8_keeps_the_server_start():
    start = scoring_start(received_at=ist(MON, 10), claim=Claim(ist(MON, 9, 30), CLAIM_DEVICE_POSTED_AT),
                          levels=CallLevels(None, None, None, None, None), on_or_before=on_or_before,
                          bars_for_day=None)
    assert (start.at, start.basis, start.queue_reason) == (ist(MON, 10), START_SERVER_RECEIPT, None)


@pytest.mark.parametrize("original, admitted, basis", [
    (ist(MON, 18), ist(MON, 21), START_SERVER_RECEIPT),  # nothing traded since receipt
    (ist(FRI, 18), ist(MON, 9), START_SERVER_RECEIPT),
    (ist(MON, 15, 30), ist(TUE, 9, 15), START_SERVER_RECEIPT),
    (ist(MON, 10), ist(MON, 14), START_ADMIN_ADMISSION),  # same session, after trading
    (ist(MON, 18), ist(TUE, 10), START_ADMIN_ADMISSION),  # after the next session opened
])
def test_an_admission_keeps_its_original_start_only_if_no_market_minute_has_passed(original, admitted, basis):
    start = admission_start(ScoringStart(original, START_SERVER_RECEIPT), admitted, on_or_before)
    assert start.basis == basis
    assert start.at == (original if basis == START_SERVER_RECEIPT else admitted)


def test_an_unknown_calendar_scores_an_admission_from_the_admission():
    start = admission_start(ScoringStart(ist(MON, 18), START_SERVER_RECEIPT), ist(MON, 21), broken_calendar)
    assert (start.at, start.basis) == (ist(MON, 21), START_ADMIN_ADMISSION)
```

- [ ] **Step 2: Run it red**

Run: `python -m pytest tests/test_tip_claimed_time.py -q -p no:cacheprovider`
Expected: collection error `ModuleNotFoundError: No module named 'app.tip_claimed_time'`.

- [ ] **Step 3: Vocabulary (append to `app/tip_vocabulary.py`)**

```python
# Intake review spec §4: a claimed creation time that cannot be trusted keeps the call in review.
REASON_CLAIMED_TIME_OLDER = "CLAIMED_TIME_OLDER"
REASON_CLAIMED_TIME_INVALID = "CLAIMED_TIME_INVALID"
REASON_CLAIMED_TIME_UNVERIFIED = "CLAIMED_TIME_UNVERIFIED"
INTAKE_QUEUE_REASONS = (*INTAKE_REJECTION_REASONS, REASON_CLAIMED_TIME_OLDER, REASON_CLAIMED_TIME_INVALID,
                        REASON_CLAIMED_TIME_UNVERIFIED)
# Mirrors migration 0193's ck_tips_validated_only; the migration keeps its own literal copy.
VALIDATED_ONLY_SQL = ("status <> 'PENDING_CHECK' AND status <> 'UNSCORABLE' AND COALESCE(reason, '') NOT IN ("
                      + ", ".join(f"'{reason}'" for reason in INTAKE_QUEUE_REASONS) + ")")

CLAIM_DEVICE_POSTED_AT = "DEVICE_POSTED_AT"
CLAIM_TIP_AS_OF = "TIP_AS_OF"
CLAIM_REPORT_DATE = "REPORT_DATE"

START_SERVER_RECEIPT = "SERVER_RECEIPT"
START_CLAIMED_NO_TRADING = "CLAIMED_NO_TRADING"
START_CLAIMED_MARKET_VERIFIED = "CLAIMED_MARKET_VERIFIED"
START_SERVER_RECEIPT_CLAIM_REJECTED = "SERVER_RECEIPT_CLAIM_REJECTED"
START_ADMIN_ADMISSION = "ADMIN_ADMISSION"
START_LEGACY = "LEGACY"

# Queue states; a pending item uses STATUS_PENDING_CHECK's spelling.
REVIEW_REJECTED = "REJECTED"
REVIEW_PROMOTED = "PROMOTED"
REVIEW_DISMISSED = "DISMISSED"
REVIEW_ORIGIN_INTAKE = "INTAKE"
REVIEW_ORIGIN_MIGRATED = "MIGRATED_0193"

EVENT_QUEUED = "QUEUED"
EVENT_RECEIPT_ATTACHED = "RECEIPT_ATTACHED"
EVENT_RECHECKED = "RECHECKED"
EVENT_PROMOTED = "PROMOTED"
EVENT_RESUBMITTED = "RESUBMITTED"
EVENT_DISMISSED = "DISMISSED"
EVENT_MIGRATED = "MIGRATED"
ACTOR_SYSTEM = "system"
ACTOR_TRACKER = "tracker"
DISMISS_REASONS = ("NOT_A_TIP", "UNFIXABLE_TERMS", "DUPLICATE", "OFF_MARKET_CONFIRMED", "OTHER")
REPARSE_QUEUED = "QUEUED"
```

- [ ] **Step 4: Extract `covering_price` in `app/tip_first_seen_price.py`**

Add `from .tip_tracker import PriceBar` to the imports. Replace the body of `_intraday_price` after the `try/except` with `return covering_price(bars, at)`, and add:

```python
def covering_price(bars: list[PriceBar], at: datetime) -> Decimal | None:
    """The traded price at `at` from the 1-minute bar covering it: its open in the first half-minute, else its close."""
    covering = [bar for bar in bars if bar.at <= at]
    if not covering:
        return None
    bar = covering[-1]
    return bar.open if at < bar.at + timedelta(seconds=30) else bar.close
```

- [ ] **Step 5: Write `app/tip_claimed_time.py`**

```python
"""Intake review spec §4 and §6: when a call's scoring starts. Server receipt by default; a claimed creation time only
when the calendar and that session's minute bars prove it cannot improve the result. No database, no clock."""

from __future__ import annotations

from dataclasses import dataclass
from datetime import date, datetime, timedelta, timezone
from decimal import Decimal
from typing import Callable

from .market_data.quality import NSE_TIMEZONE
from .schedule_orchestration import MARKET_CLOSE, MARKET_OPEN
from .tip_first_seen_price import covering_price
from .tip_minute_bars import MinuteBarsUnavailable
from .tip_tracker import PriceBar
from .tip_vocabulary import (
    DIRECTION_BUY,
    REASON_CLAIMED_TIME_INVALID,
    REASON_CLAIMED_TIME_OLDER,
    REASON_CLAIMED_TIME_UNVERIFIED,
    START_ADMIN_ADMISSION,
    START_CLAIMED_MARKET_VERIFIED,
    START_CLAIMED_NO_TRADING,
    START_SERVER_RECEIPT,
    START_SERVER_RECEIPT_CLAIM_REJECTED,
)

CLAIM_SKEW = timedelta(seconds=120)
_MINUTE = timedelta(minutes=1)

TradingDayOnOrBefore = Callable[[date], date]
BarsForDay = Callable[[date], list[PriceBar]]


@dataclass(frozen=True)
class Claim:
    at: datetime
    source: str
    date_only: bool = False


@dataclass(frozen=True)
class CallLevels:
    direction: str | None
    entry_low: Decimal | None
    entry_high: Decimal | None
    target: Decimal | None
    stop_loss: Decimal | None


@dataclass(frozen=True)
class ScoringStart:
    at: datetime
    basis: str
    queue_reason: str | None = None
    evidence: dict | None = None


class CalendarUnavailable(RuntimeError):
    pass


def _open(day: date) -> datetime:
    return datetime.combine(day, MARKET_OPEN, NSE_TIMEZONE)


def _close(day: date) -> datetime:
    return datetime.combine(day, MARKET_CLOSE, NSE_TIMEZONE)


def _trading_day(day: date, on_or_before: TradingDayOnOrBefore) -> date:
    try:
        return on_or_before(day)
    except Exception as exc:  # noqa: BLE001 -- any calendar failure makes the session ambiguous (CT5)
        raise CalendarUnavailable(str(exc)) from exc


def session_of(at: datetime, on_or_before: TradingDayOnOrBefore) -> date:
    """S(t): the latest NSE trading day whose 09:15 IST open is at or before `at`."""
    local = at.astimezone(NSE_TIMEZONE)
    candidate = local.date() if local.time() >= MARKET_OPEN else local.date() - timedelta(days=1)
    return _trading_day(candidate, on_or_before)


def market_minutes_between(start: datetime, end: datetime, on_or_before: TradingDayOnOrBefore) -> bool:
    """Whether any market minute lies in [start, end); an unknown calendar answers yes, so AH1 fails closed."""
    if end <= start:
        return False
    day, last = start.astimezone(NSE_TIMEZONE).date(), end.astimezone(NSE_TIMEZONE).date()
    try:
        while day <= last:
            if _trading_day(day, on_or_before) == day and _open(day) < end and _close(day) > start:
                return True
            day += timedelta(days=1)
    except CalendarUnavailable:
        return True
    return False


def _queued(received: datetime, reason: str, evidence: dict | None = None) -> ScoringStart:
    return ScoringStart(received, START_SERVER_RECEIPT, reason, evidence)


def scoring_start(*, received_at: datetime, claim: Claim | None, levels: CallLevels,
                  on_or_before: TradingDayOnOrBefore, bars_for_day: BarsForDay | None) -> ScoringStart:
    """CT1-CT8 in order; the first rule that matches decides."""
    received = received_at.astimezone(timezone.utc)
    server = ScoringStart(received, START_SERVER_RECEIPT)
    if claim is None:
        return server
    try:
        if claim.date_only:
            return _queued(received, REASON_CLAIMED_TIME_OLDER) if claim.at.date() < session_of(
                received, on_or_before) else server
        if claim.at.tzinfo is None or claim.at > received + CLAIM_SKEW:
            return _queued(received, REASON_CLAIMED_TIME_INVALID)
        claimed = claim.at.astimezone(timezone.utc)
        if claimed > received - CLAIM_SKEW:  # D11: a claim this close to receipt is no claim
            return server
        day = session_of(received, on_or_before)
        if session_of(claimed, on_or_before) != day:
            return _queued(received, REASON_CLAIMED_TIME_OLDER)
    except CalendarUnavailable:
        return _queued(received, REASON_CLAIMED_TIME_UNVERIFIED)
    if claimed >= _close(day):
        return ScoringStart(claimed, START_CLAIMED_NO_TRADING)
    return _market_verified(claimed, received, day, levels, bars_for_day)


def _market_verified(claimed: datetime, received: datetime, day: date, levels: CallLevels,
                     bars_for_day: BarsForDay | None) -> ScoringStart:
    """CT8: the claim stands only if the window's bars show it cannot have helped the call."""
    if bars_for_day is None or levels.direction is None:
        return ScoringStart(received, START_SERVER_RECEIPT)  # no stock or direction: §6.2 rejects the call anyway
    end = min(received, _close(day).astimezone(timezone.utc))
    evidence = {"windowStart": claimed.isoformat(), "windowEnd": end.isoformat()}
    try:
        bars = bars_for_day(day)
    except MinuteBarsUnavailable:
        return _queued(received, REASON_CLAIMED_TIME_UNVERIFIED, {**evidence, "failed": "BARS_UNAVAILABLE"})
    at_start, at_end = covering_price(bars, claimed), covering_price(bars, end)
    evidence.update(priceAtStart=_text(at_start), priceAtEnd=_text(at_end))
    if at_start is None or at_end is None:
        return _queued(received, REASON_CLAIMED_TIME_UNVERIFIED, {**evidence, "failed": "UNPRICED"})
    failed = _touched(bars, claimed, end, levels)
    if failed is None and levels.entry_low is None and _better(levels.direction, at_start, at_end):
        failed = "BETTER_PRICE"
    if failed is not None:
        return ScoringStart(received, START_SERVER_RECEIPT_CLAIM_REJECTED, evidence={**evidence, "failed": failed})
    return ScoringStart(claimed, START_CLAIMED_MARKET_VERIFIED, evidence=evidence)


def _touched(bars: list[PriceBar], start: datetime, end: datetime, levels: CallLevels) -> str | None:
    buy = levels.direction == DIRECTION_BUY
    for bar in bars:
        # Plan decision D8: the minute covering the claim counts, since a touch inside it cannot be ordered against C.
        if not (bar.at < end and bar.at + _MINUTE > start):
            continue
        if levels.entry_low is not None and (bar.low <= levels.entry_high if buy else bar.high >= levels.entry_low):
            return "ENTRY_TOUCHED"
        if levels.target is not None and (bar.high >= levels.target if buy else bar.low <= levels.target):
            return "TARGET_TOUCHED"
        if levels.stop_loss is not None and (bar.low <= levels.stop_loss if buy else bar.high >= levels.stop_loss):
            return "STOP_TOUCHED"
    return None


def _better(direction: str, at_start: Decimal, at_end: Decimal) -> bool:
    return at_start < at_end if direction == DIRECTION_BUY else at_start > at_end


def _text(value: Decimal | None) -> str | None:
    return None if value is None else str(value)


def admission_start(original: ScoringStart, admitted_at: datetime,
                    on_or_before: TradingDayOnOrBefore) -> ScoringStart:
    """AH1/AH2: the original start only if no market minute has passed since it, else the admission time."""
    if not market_minutes_between(original.at, admitted_at, on_or_before):
        return original
    return ScoringStart(admitted_at.astimezone(timezone.utc), START_ADMIN_ADMISSION,
                        evidence={"originalStart": original.at.isoformat(), "originalBasis": original.basis})
```

- [ ] **Step 6: Run green, plus the first-seen-price regression**

Run: `python -m pytest tests/test_tip_claimed_time.py tests/test_tip_first_seen_price.py tests/test_tip_first_seen_price_intake.py -q -p no:cacheprovider`
Expected: all pass. `covering_price` is a pure extraction, so the first-seen tests are unchanged.

- [ ] **Step 7: Commit**

```bash
git add app/tip_claimed_time.py app/tip_vocabulary.py app/tip_first_seen_price.py tests/test_tip_claimed_time.py
git commit -m "Claimed-time policy: when a call's scoring starts, and when an admission keeps it

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 2: Queue models, the validated-only CHECK, the guard

**Files:**
- Modify: `app/models.py` (after `class Tip`, and `class TipReceipt`), `app/tip_ledger_guard.py`
- Create: `tests/_intake_review_helpers.py`, `tests/test_tip_intake_review_guard.py`
- Convert: `tests/test_tip_alerts.py` seeds at lines 146-148, 356-357, 394-395, 452-453, 549

**Interfaces:**
- Consumes: Task 1 vocabulary.
- Produces:
  - `TipIntakeReview` columns: `id, public_id, state, reason, rejected_status, origin, origin_tip_id, source_channel_id, caller_id, prediction_id, stock_id, symbol, direction, entry_low, entry_high, target, stop_loss, horizon_sessions, horizon_basis, match_key, match_key_version, parser_version, source, client_name, source_reference, received_at, claimed_created_at, claimed_time_source, scoring_start_at, scoring_start_basis, first_seen_price, entry_check_price, entry_check_basis, check_evidence, closed_at, payload, promoted_tip_id, decided_at, decided_by, created_at, updated_at`
  - `TipIntakeReviewEvent(id, review_id, at, actor, action, before, after, note)`
  - `Tip.claimed_created_at, claimed_time_source, scoring_start_basis, intake_review_id`; `TipReceipt.intake_review_id`
  - `TipNotValidatedError`, `unvalidated(status, reason) -> bool`
  - Test helper `queue_item(session, channel, *, state=REVIEW_REJECTED, reason=REASON_NO_DIRECTION, rejected_status=STATUS_UNSCORABLE, symbol="RENUKA", direction=None, caller_row=None, holders=(), seen=NOW, horizon=20, match_key=None) -> TipIntakeReview`

- [ ] **Step 1: Write the failing tests**

`tests/_intake_review_helpers.py`:

```python
"""Seeds intake-review items directly, for tests that need a queued call without running intake."""

from __future__ import annotations

import uuid
from datetime import datetime, timezone

from app.models import TipIntakeReview, TipReceipt
from app.tip_vocabulary import (
    KIND_TIP,
    MEDIUM_APP_NOTIFICATION,
    REASON_NO_DIRECTION,
    REVIEW_ORIGIN_INTAKE,
    REVIEW_REJECTED,
    START_SERVER_RECEIPT,
    STATUS_PENDING_CHECK,
    STATUS_UNSCORABLE,
)

NOW = datetime(2026, 9, 28, 5, 0, tzinfo=timezone.utc)


def queue_item(session, channel, *, state=REVIEW_REJECTED, reason=REASON_NO_DIRECTION,
               rejected_status=STATUS_UNSCORABLE, symbol="RENUKA", direction=None, caller_row=None, holders=(),
               seen=NOW, horizon=20, match_key=None) -> TipIntakeReview:
    pending = state == STATUS_PENDING_CHECK
    item = TipIntakeReview(
        public_id=str(uuid.uuid4()), state=state, reason=None if pending else reason,
        rejected_status=None if pending else rejected_status, origin=REVIEW_ORIGIN_INTAKE,
        source_channel_id=channel.id, caller_id=caller_row.id if caller_row is not None else None, symbol=symbol,
        direction=direction, horizon_sessions=horizon, match_key=match_key or f"key-{uuid.uuid4().hex}",
        source=channel.name[:64], received_at=seen, scoring_start_at=seen, scoring_start_basis=START_SERVER_RECEIPT,
        closed_at=None if pending else seen, payload={},
    )
    session.add(item)
    session.flush()
    for user_id in holders:
        session.add(TipReceipt(
            public_id=str(uuid.uuid4()), intake_review_id=item.id, user_id=user_id,
            device_event_key=f"ev-{uuid.uuid4().hex}", kind=KIND_TIP, medium=MEDIUM_APP_NOTIFICATION, text="",
            recorded_at=seen, parser_version="TCP-001",
        ))
    session.flush()
    return item
```

`tests/test_tip_intake_review_guard.py`:

```python
"""Intake review spec §5: `tips` refuses a call that has not passed intake, in the ORM and in the database."""

from __future__ import annotations

import uuid
from datetime import datetime, timezone

import pytest
from sqlalchemy import create_engine, text
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import sessionmaker

from app.db import Base
from app.models import Channel, Tip, TipIntakeReviewEvent
from app.tip_ledger_guard import TipLedgerImmutableError, TipNotValidatedError
from app.tip_vocabulary import (
    CHANNEL_BROKER_APP,
    REASON_CLAIMED_TIME_OLDER,
    REASON_MISSING_BARS,
    REASON_NO_DIRECTION,
    REASON_UNRESOLVED_SYMBOL,
    STATUS_ACTIVE,
    STATUS_DATA_UNRESOLVED,
    STATUS_PENDING_CHECK,
    STATUS_UNSCORABLE,
)
from tests._intake_review_helpers import queue_item

NOW = datetime(2026, 9, 28, 5, 0, tzinfo=timezone.utc)


@pytest.fixture
def session():
    engine = create_engine("sqlite:///:memory:")
    Base.metadata.create_all(engine)
    db = sessionmaker(bind=engine)()
    try:
        yield db
    finally:
        db.close()


def _tip(**overrides):
    values = dict(public_id=str(uuid.uuid4()), source="Upstox", symbol="RENUKA", received_at=NOW,
                  comparison_status="RECEIVED", match_key="k-1", status=STATUS_ACTIVE)
    values.update(overrides)
    return Tip(**values)


@pytest.mark.parametrize("status, reason", [
    (STATUS_PENDING_CHECK, None),
    (STATUS_UNSCORABLE, REASON_NO_DIRECTION),
    (STATUS_DATA_UNRESOLVED, REASON_UNRESOLVED_SYMBOL),
    (STATUS_UNSCORABLE, REASON_CLAIMED_TIME_OLDER),
])
def test_the_orm_refuses_a_tip_that_has_not_passed_intake(session, status, reason):
    session.add(_tip(status=status, reason=reason))
    with pytest.raises(TipNotValidatedError):
        session.flush()


def test_the_database_refuses_it_without_the_orm(session):
    with pytest.raises(IntegrityError):
        session.execute(text(
            "INSERT INTO tips (public_id, source, symbol, received_at, comparison_status, status) "
            "VALUES ('p-1', 'Upstox', 'RENUKA', '2026-09-28 05:00:00', 'RECEIVED', 'PENDING_CHECK')"
        ))


def test_the_trackers_own_data_unresolved_is_allowed(session):
    session.add(_tip(status=STATUS_DATA_UNRESOLVED, reason=REASON_MISSING_BARS))
    session.flush()


def test_an_active_tip_cannot_be_moved_back_into_review(session):
    tip = _tip()
    session.add(tip)
    session.commit()
    tip.status = STATUS_PENDING_CHECK
    with pytest.raises(TipNotValidatedError):
        session.flush()


def test_review_items_and_events_are_evidence(session):
    channel = Channel(name="Upstox", type=CHANNEL_BROKER_APP, default_horizon_sessions=20, capture_enabled=True)
    session.add(channel)
    session.flush()
    item = queue_item(session, channel)
    event = TipIntakeReviewEvent(review_id=item.id, at=NOW, actor="system", action="QUEUED")
    session.add(event)
    session.commit()
    event.note = "rewritten"
    with pytest.raises(TipLedgerImmutableError):
        session.flush()
    session.rollback()
    session.delete(item)
    with pytest.raises(TipLedgerImmutableError):
        session.flush()
```

- [ ] **Step 2: Run it red**

Run: `python -m pytest tests/test_tip_intake_review_guard.py -q -p no:cacheprovider`
Expected: `ImportError: cannot import name 'TipIntakeReviewEvent' from 'app.models'`.

- [ ] **Step 3: Models (`app/models.py`)**

Add `CheckConstraint` to the `sqlalchemy` import and `VALIDATED_ONLY_SQL` from `.tip_vocabulary`. Append to `Tip.__table_args__`:

```python
        # Intake review spec §5: a call that has not passed intake belongs in tip_intake_reviews, never here.
        CheckConstraint(VALIDATED_ONLY_SQL, name="ck_tips_validated_only"),
```

Append to `Tip`'s columns:

```python
    # Intake review spec §4: what the sender claimed, and why scoring starts at `first_seen_at`.
    claimed_created_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    claimed_time_source: Mapped[str | None] = mapped_column(String(24))
    scoring_start_basis: Mapped[str | None] = mapped_column(String(32))
    # Plan decision D4: no FK, since tip_intake_reviews.promoted_tip_id already points back here.
    intake_review_id: Mapped[int | None] = mapped_column(BigInteger().with_variant(Integer, "sqlite"), index=True)
```

Insert after `ExternalTip = Tip`:

```python
class TipIntakeReview(Base):
    """A call that has not passed intake (intake review spec §5); it becomes a tip only when promoted."""

    __tablename__ = "tip_intake_reviews"
    __table_args__ = (
        UniqueConstraint("source", "source_reference", name="uq_tip_intake_review_source_reference"),
        Index("uq_tip_intake_reviews_prediction_id", "prediction_id", unique=True),
        Index("ix_tip_intake_reviews_state", "state"),
    )
    id: Mapped[int] = mapped_column(BigInteger().with_variant(Integer, "sqlite"), primary_key=True)
    public_id: Mapped[str] = mapped_column(String(36), unique=True, index=True)
    state: Mapped[str] = mapped_column(String(16))
    reason: Mapped[str | None] = mapped_column(String(32))
    rejected_status: Mapped[str | None] = mapped_column(String(24))
    origin: Mapped[str] = mapped_column(String(16))
    # No FK: a migrated item's tips row no longer exists (migration 0193).
    origin_tip_id: Mapped[int | None] = mapped_column(BigInteger().with_variant(Integer, "sqlite"), unique=True)
    source_channel_id: Mapped[int | None] = mapped_column(ForeignKey("channels.id"))
    caller_id: Mapped[int | None] = mapped_column(ForeignKey("callers.id"))
    prediction_id: Mapped[int | None] = mapped_column(ForeignKey("predictions.id"))
    stock_id: Mapped[int | None] = mapped_column(ForeignKey("stocks.id"))
    symbol: Mapped[str] = mapped_column(String(32))
    direction: Mapped[str | None] = mapped_column(String(8))
    entry_low: Mapped[Decimal | None] = mapped_column(Numeric(18, 6))
    entry_high: Mapped[Decimal | None] = mapped_column(Numeric(18, 6))
    target: Mapped[Decimal | None] = mapped_column(Numeric(18, 6))
    stop_loss: Mapped[Decimal | None] = mapped_column(Numeric(18, 6))
    horizon_sessions: Mapped[int | None] = mapped_column(Integer)
    horizon_basis: Mapped[str | None] = mapped_column(String(16))
    match_key: Mapped[str | None] = mapped_column(String(64), index=True)
    match_key_version: Mapped[int | None] = mapped_column(Integer)
    parser_version: Mapped[str | None] = mapped_column(String(32))
    source: Mapped[str] = mapped_column(String(64))
    client_name: Mapped[str | None] = mapped_column(String(64))
    source_reference: Mapped[str | None] = mapped_column(String(128))
    received_at: Mapped[datetime] = mapped_column(DateTime(timezone=True))
    claimed_created_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    claimed_time_source: Mapped[str | None] = mapped_column(String(24))
    scoring_start_at: Mapped[datetime] = mapped_column(DateTime(timezone=True))
    scoring_start_basis: Mapped[str] = mapped_column(String(32))
    first_seen_price: Mapped[Decimal | None] = mapped_column(Numeric(18, 6))
    entry_check_price: Mapped[Decimal | None] = mapped_column(Numeric(18, 6))
    entry_check_basis: Mapped[str | None] = mapped_column(String(24))
    check_evidence: Mapped[dict | None] = mapped_column(JSON)
    # Plan decision D2: when §8 counts a rejected item as closed; a migrated item keeps its tip's closed_at.
    closed_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    payload: Mapped[dict] = mapped_column(JSON)
    promoted_tip_id: Mapped[int | None] = mapped_column(ForeignKey("tips.id"), unique=True)
    decided_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    decided_by: Mapped[str | None] = mapped_column(String(160))
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=func.now())
    updated_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=func.now(),
                                                 onupdate=func.now())


class TipIntakeReviewEvent(Base):
    """Append-only history of one intake-review item (intake review spec §5)."""

    __tablename__ = "tip_intake_review_events"
    id: Mapped[int] = mapped_column(BigInteger().with_variant(Integer, "sqlite"), primary_key=True)
    review_id: Mapped[int] = mapped_column(ForeignKey("tip_intake_reviews.id"), index=True)
    at: Mapped[datetime] = mapped_column(DateTime(timezone=True))
    actor: Mapped[str] = mapped_column(String(160))
    action: Mapped[str] = mapped_column(String(24))
    before: Mapped[dict | None] = mapped_column(JSON)
    after: Mapped[dict | None] = mapped_column(JSON)
    note: Mapped[str | None] = mapped_column(Text)
```

In `TipReceipt.__table_args__` add:

```python
        Index(
            "uq_tip_receipt_review_user_medium",
            "intake_review_id",
            "user_id",
            "medium",
            unique=True,
            postgresql_where=text("kind = 'TIP'"),
            sqlite_where=text("kind = 'TIP'"),
        ),
```

and the column `intake_review_id: Mapped[int | None] = mapped_column(ForeignKey("tip_intake_reviews.id"), index=True)`.

- [ ] **Step 4: Guard (`app/tip_ledger_guard.py`)**

Import `TipIntakeReview, TipIntakeReviewEvent` and `INTAKE_QUEUE_REASONS, STATUS_PENDING_CHECK, STATUS_UNSCORABLE`, then add:

```python
class TipNotValidatedError(RuntimeError):
    """A tips row the database's ck_tips_validated_only would refuse (intake review spec §5)."""


def unvalidated(status: str | None, reason: str | None) -> bool:
    return status in (STATUS_PENDING_CHECK, STATUS_UNSCORABLE) or reason in INTAKE_QUEUE_REASONS


@event.listens_for(Tip, "before_insert")
@event.listens_for(Tip, "before_update")
def _reject_unvalidated(mapper, connection, target):
    if unvalidated(target.status, target.reason):
        raise TipNotValidatedError(
            f"tip {target.public_id} ({target.status}, {target.reason}) has not passed intake; it belongs in review")


@event.listens_for(TipIntakeReviewEvent, "before_update")
def _reject_event_change(mapper, connection, target):
    raise TipLedgerImmutableError(f"intake review event {target.id} is append-only")


@event.listens_for(TipIntakeReviewEvent, "before_delete")
@event.listens_for(TipIntakeReview, "before_delete")
def _reject_review_delete(mapper, connection, target):
    raise TipLedgerImmutableError(f"{type(target).__name__} {target.id} is evidence and is never deleted")
```

- [ ] **Step 5: Convert the alert tests that seed forbidden rows**

In `tests/test_tip_alerts.py`, import `queue_item` from `tests._intake_review_helpers` and `REVIEW_REJECTED` and `STATUS_PENDING_CHECK` from `app.tip_vocabulary`. Replace each seed and keep every assertion:
- line 146 `tip(session, upstox, status=STATUS_UNSCORABLE, reason=REASON_NO_DIRECTION, closed_at=JUST_CLOSED, entry_status=None)` → `queue_item(session, upstox, reason=REASON_NO_DIRECTION, seen=JUST_CLOSED)`
- line 147 (`STATUS_DATA_UNRESOLVED`, `REASON_UNRESOLVED_SYMBOL`) → `queue_item(session, upstox, reason=REASON_UNRESOLVED_SYMBOL, rejected_status=STATUS_DATA_UNRESOLVED, seen=JUST_CLOSED)`
- lines 356-357 → `queue_item(session, family, caller_row=uncle, holders=("fan-with-receipt",), reason=REASON_NO_DIRECTION)`
- lines 394-395 → `queue_item(session, upstox, reason=REASON_NO_DIRECTION, seen=JUST_CLOSED)`
- lines 452-453 → `queue_item(session, family, caller_row=aunt, holders=("user-b",), reason=REASON_NO_DIRECTION)`
- line 549 → `queue_item(session, upstox, state=STATUS_PENDING_CHECK, seen=RUN - timedelta(minutes=10))`

The test at 352-357 checks private-channel masking for a follower whose only receipt there is now on a queued call. If its masked/unmasked expectation changes, **stop and report**: that is a privacy behaviour change, not a test fix.

- [ ] **Step 6: Run green**

Run: `python -m pytest tests/test_tip_intake_review_guard.py tests/test_tip_alerts.py tests/test_tip_ledger_guard.py -q -p no:cacheprovider`
Expected: all pass. Later tasks repair the intake/tracker tests that now raise `TipNotValidatedError`; `tests/test_tip_ledger.py`, `tests/test_tip_first_seen_price_intake.py`, `tests/test_marksy_prediction_tips.py` and `tests/test_run_tip_tracker.py` are expected red until Tasks 3–5.

- [ ] **Step 7: Commit**

```bash
git add app/models.py app/tip_ledger_guard.py tests/_intake_review_helpers.py tests/test_tip_intake_review_guard.py tests/test_tip_alerts.py
git commit -m "Intake review queue tables; tips refuse calls that have not passed intake

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 3: The gate, queue matching and attachment

**Files:**
- Create: `app/tip_intake_review.py`
- Modify: `app/tip_ledger.py` — `Intake` (+`claim`), `IntakeResult` (+`review`), `_lock_match_key`, `find_matchable` (226-248), `_record` (261-287), `_record_tip` (290-309), `_record_revision` (312-325), `_attach` (339-354), `_new_tip` (371-426), `_new_receipt` (429-446), `_fill_caller` (449-451)
- Test: `tests/test_tip_intake_review_gate.py`
- Convert: `tests/test_tip_ledger.py:158-176`, `tests/test_tip_first_seen_price_intake.py:129-209`

**Interfaces:**
- Consumes: Task 1 `Claim`, `CallLevels`, `ScoringStart`, `scoring_start`, `admission_start`; Task 2 models.
- Produces:
  - In `app/tip_intake_review.py`:
    - `lock_match_key(session, key)`, `tip_payload(tip) -> dict`, `tip_from_payload(item) -> Tip`
    - `review_from_tip(tip, *, state, reason, rejected_status, evidence) -> TipIntakeReview`
    - `item_state(item) -> dict`
    - `record_event(session, item, action, *, actor, at, before=None, after=None, note=None)`
    - `promote(session, item, tip, *, actor, at) -> Tip`
  - In `app/tip_ledger.py`:
    - `GateDecision(tip, rejection, pending, evidence=None)` with `.admitted`
    - `evaluate(session, tip, terms, stock, start, *, minute_bars) -> GateDecision`
    - `admit_or_queue(session, decision, *, actor=ACTOR_SYSTEM, at=None) -> Tip | TipIntakeReview`
    - `trading_days(session) -> TradingDayOnOrBefore`, `bars_for(stock, minute_bars) -> BarsForDay`
    - `find_matchable(...) -> Tip | TipIntakeReview | None`
    - `Intake.claim: Claim | None = None`, `IntakeResult.review: TipIntakeReview | None = None`

- [ ] **Step 1: Write the failing tests** (`tests/test_tip_intake_review_gate.py`)

```python
"""Intake review spec §3: the one gate. A call that fails, or waits for a price, is queued and never a tip."""

from __future__ import annotations

from datetime import datetime, timedelta, timezone
from decimal import Decimal

import pytest
from sqlalchemy import create_engine, func, select
from sqlalchemy.orm import sessionmaker

from app.db import Base
from app.market_data.quality import NSE_TIMEZONE
from app.models import Channel, ChannelAlias, MarketPrice, Stock, Tip, TipIntakeReview, TipIntakeReviewEvent, TipReceipt
from app.tip_claimed_time import Claim
from app.tip_ledger import Intake, ReceiptInput, TipExtras, record_intake
from app.tip_matching import stated_terms
from app.tip_minute_bars import MinuteBarsUnavailable, UpstoxMinuteBars
from app.tip_tracker import PriceBar
from app.tip_vocabulary import (
    ALIAS_PACKAGE,
    CHANNEL_BROKER_APP,
    CLAIM_DEVICE_POSTED_AT,
    KIND_TIP,
    MEDIUM_APP_NOTIFICATION,
    REASON_INCONSISTENT_LEVELS,
    REASON_NO_DIRECTION,
    REASON_UNRESOLVED_SYMBOL,
    REVIEW_DISMISSED,
    REVIEW_REJECTED,
    START_CLAIMED_MARKET_VERIFIED,
    START_SERVER_RECEIPT,
    STATUS_ACTIVE,
    STATUS_DATA_UNRESOLVED,
    STATUS_PENDING_CHECK,
    STATUS_UNSCORABLE,
)

NOW = datetime(2026, 9, 28, 5, 0, tzinfo=timezone.utc)  # Monday 10:30 IST


class CountingMinutes:
    def __init__(self, price="23.62"):
        self.calls, self.price = 0, Decimal(price)

    def minute_bars(self, instrument_key, session_date):
        self.calls += 1
        start = datetime(2026, 9, 28, 3, 45, tzinfo=timezone.utc)  # 09:15 IST
        return [PriceBar(start + timedelta(minutes=i), self.price, self.price, self.price, self.price)
                for i in range(0, 120)]


class DownMinutes:
    def minute_bars(self, instrument_key, session_date):
        raise MinuteBarsUnavailable("upstox down")


@pytest.fixture
def session():
    engine = create_engine("sqlite:///:memory:")
    Base.metadata.create_all(engine)
    db = sessionmaker(bind=engine)()
    db.add(Stock(symbol="RENUKA", instrument_key="NSE_EQ|RENUKA", exchange="NSE", is_active=True))
    upstox = Channel(name="Upstox", type=CHANNEL_BROKER_APP, default_horizon_sessions=20, capture_enabled=True)
    db.add(upstox)
    db.flush()
    upstox.canonical_channel_id = upstox.id
    db.add(ChannelAlias(channel_id=upstox.id, alias="com.upstox.pro", kind=ALIAS_PACKAGE))
    db.commit()
    try:
        yield db
    finally:
        db.close()


def _receipt(user="user-1", key="e-1", posted=None):
    return ReceiptInput(user_id=user, device_event_key=key, medium=MEDIUM_APP_NOTIFICATION,
                        app_package="com.upstox.pro", channel_label="Upstox", text="BUY RENUKA",
                        device_posted_at=posted, parser_version="TCP-001")


def _intake(claim=None, **overrides):
    values = dict(symbol="RENUKA", direction="BUY", entry_low=Decimal("23.62"), entry_high=Decimal("23.62"),
                  target=Decimal("26"), stop_loss=Decimal("22.25"), horizon_sessions=None)
    values.update(overrides)
    return Intake(kind=KIND_TIP, terms=stated_terms(**values), claim=claim)


def _count(session, model):
    return session.scalar(select(func.count()).select_from(model))


@pytest.mark.parametrize("overrides, rejected_status, reason", [
    ({"direction": None}, STATUS_UNSCORABLE, REASON_NO_DIRECTION),
    ({"target": Decimal("20")}, STATUS_UNSCORABLE, REASON_INCONSISTENT_LEVELS),
    ({"symbol": "NOSUCHCO"}, STATUS_DATA_UNRESOLVED, REASON_UNRESOLVED_SYMBOL),
])
def test_a_failed_call_is_queued_and_never_a_tip(session, overrides, rejected_status, reason):
    result = record_intake(session, _receipt(), _intake(**overrides), now=NOW, minute_bars=CountingMinutes())
    item = result.review
    assert (result.tip, item.state, item.rejected_status, item.reason) == (None, REVIEW_REJECTED, rejected_status, reason)
    assert _count(session, Tip) == 0
    assert result.receipt.intake_review_id == item.id and result.receipt.tip_id is None
    assert session.scalars(select(TipIntakeReviewEvent.action)).all() == ["QUEUED"]


def test_a_call_waiting_for_a_price_is_queued_pending(session):
    # Friday's close only: no same-session price, and Upstox is down.
    session.add(MarketPrice(stock_id=1, timestamp=datetime(2026, 9, 24, 18, 30, tzinfo=timezone.utc), open=23,
                            high=23, low=23, close=23, volume=1, source="upstox-v3"))
    session.commit()
    result = record_intake(session, _receipt(), _intake(), now=NOW, minute_bars=DownMinutes())
    assert (result.tip, result.review.state, result.review.reason) == (None, STATUS_PENDING_CHECK, None)


def test_a_passing_call_is_an_active_tip(session):
    result = record_intake(session, _receipt(), _intake(), now=NOW, minute_bars=CountingMinutes())
    assert (result.review, result.tip.status, result.tip.scoring_start_basis) == (None, STATUS_ACTIVE, START_SERVER_RECEIPT)


class FakeUpstox:
    def __init__(self):
        self.calls = 0

    def fetch_minute_candles(self, instrument_key, session_date):
        self.calls += 1
        start = datetime(2026, 9, 28, 9, 15, tzinfo=NSE_TIMEZONE)
        return [[(start + timedelta(minutes=i)).isoformat(), 23.62, 23.62, 23.62, 23.62, 1, 0] for i in range(120)]


def _aware(value):
    return value if value.tzinfo is not None else value.replace(tzinfo=timezone.utc)


def test_the_everyday_phone_claim_costs_no_second_upstox_call(session):
    upstox = FakeUpstox()
    minutes = UpstoxMinuteBars(lambda: upstox)  # the production source: one fetch per (instrument, session)
    claim = Claim(NOW - timedelta(seconds=4), CLAIM_DEVICE_POSTED_AT)
    result = record_intake(session, _receipt(posted=claim.at), _intake(claim=claim), now=NOW, minute_bars=minutes)
    assert result.tip.scoring_start_basis == START_SERVER_RECEIPT  # D11: seconds before R is no claim
    assert result.tip.claimed_created_at is not None and upstox.calls == 1


def test_a_claim_on_an_unresolved_symbol_is_rejected_for_the_symbol(session):
    claim = Claim(NOW - timedelta(minutes=30), CLAIM_DEVICE_POSTED_AT)
    result = record_intake(session, _receipt(), _intake(claim=claim, symbol="NOSUCHCO"), now=NOW,
                           minute_bars=CountingMinutes())
    assert (result.review.reason, _aware(result.review.scoring_start_at)) == (REASON_UNRESOLVED_SYMBOL, NOW)


def test_later_copies_attach_to_the_queued_item_even_after_dismissal(session):
    first = record_intake(session, _receipt(), _intake(direction=None), now=NOW, minute_bars=CountingMinutes())
    first.review.state = REVIEW_DISMISSED
    session.commit()
    copy = record_intake(session, _receipt(user="user-2", key="e-2"), _intake(direction=None),
                         now=NOW + timedelta(days=1), minute_bars=CountingMinutes())
    assert copy.review.id == first.review.id and copy.matched
    assert _count(session, TipIntakeReview) == 1 and _count(session, TipReceipt) == 2


def test_a_source_reference_names_its_queued_item_across_re_pastes(session):
    extras = TipExtras(source="Upstox", source_reference="ref-9")
    intake = Intake(kind=KIND_TIP, terms=_intake(direction=None).terms, extras=extras)
    first = record_intake(session, _receipt(), intake, now=NOW, minute_bars=CountingMinutes())
    again = record_intake(session, _receipt(key="e-2"), intake, now=NOW, minute_bars=CountingMinutes())
    assert again.review.id == first.review.id and _count(session, TipIntakeReview) == 1
```

The `MarketPrice` constructor fields must match the model. If a required column is missing, copy the `_close(...)` helper from `tests/test_tip_first_seen_price_intake.py:107`.

- [ ] **Step 2: Run it red**

Run: `python -m pytest tests/test_tip_intake_review_gate.py -q -p no:cacheprovider`
Expected: `TypeError: Intake.__init__() got an unexpected keyword argument 'claim'`.

- [ ] **Step 3: `app/tip_intake_review.py`**

```python
"""Intake review queue (intake review spec §3, §5, §8): a call that has not passed intake waits here, never in `tips`.
Intake, the tracker's promotion pass and the admin review routes all go through these helpers."""

from __future__ import annotations

from datetime import datetime
from decimal import Decimal

from sqlalchemy import DateTime, Numeric, select, text
from sqlalchemy.orm import Session

from .models import Tip, TipIntakeReview, TipIntakeReviewEvent, TipReceipt
from .tip_vocabulary import EVENT_PROMOTED, KIND_TIP, REVIEW_ORIGIN_INTAKE, REVIEW_PROMOTED, REVIEW_REJECTED, STATUS_ACTIVE

_COPIED = ("public_id", "source_channel_id", "caller_id", "prediction_id", "stock_id", "symbol", "direction",
           "entry_low", "entry_high", "stop_loss", "horizon_sessions", "horizon_basis", "match_key",
           "match_key_version", "parser_version", "source", "client_name", "source_reference", "received_at",
           "claimed_created_at", "claimed_time_source", "first_seen_price", "entry_check_price", "entry_check_basis")
_LIFECYCLE = frozenset({"id", "created_at", "status", "entry_status", "outcome", "reason", "entered_session",
                        "closed_session", "closed_at", "exit_price", "promised_return", "actual_return",
                        "intake_review_id"})


def lock_match_key(session: Session, match_key: str) -> None:
    """Serializes concurrent first sightings of one key on PostgreSQL; released with the transaction."""
    if session.get_bind().dialect.name == "postgresql":
        session.execute(text("SELECT pg_advisory_xact_lock(hashtextextended(:key, 0))"), {"key": match_key})


def _json_safe(value):
    if isinstance(value, Decimal):
        return str(value)
    if isinstance(value, datetime):
        return value.isoformat()
    return value


def tip_payload(tip: Tip) -> dict:
    return {column.key: _json_safe(getattr(tip, column.key)) for column in Tip.__table__.columns if column.key != "id"}


def _from_json(column, value):
    if value is None:
        return None
    if isinstance(column.type, Numeric):
        return Decimal(str(value))  # a migrated payload is to_jsonb: numbers arrive as JSON numbers
    if isinstance(column.type, DateTime):
        return datetime.fromisoformat(value)
    return value


def tip_from_payload(item: TipIntakeReview) -> Tip:
    """The item's call as a transient tip; for a migrated item, its original tips row minus lifecycle."""
    columns = Tip.__table__.columns
    return Tip(**{key: _from_json(columns[key], value) for key, value in item.payload.items()
                  if key in columns and key not in _LIFECYCLE})


def review_from_tip(tip: Tip, *, state: str, reason: str | None, rejected_status: str | None,
                    evidence: dict | None) -> TipIntakeReview:
    return TipIntakeReview(
        **{name: getattr(tip, name) for name in _COPIED},
        target=tip.target_price, scoring_start_at=tip.first_seen_at, scoring_start_basis=tip.scoring_start_basis,
        state=state, reason=reason, rejected_status=rejected_status, origin=REVIEW_ORIGIN_INTAKE,
        check_evidence=evidence, payload=tip_payload(tip),
        closed_at=tip.first_seen_at if state == REVIEW_REJECTED else None,
    )


def item_state(item: TipIntakeReview) -> dict:
    return {"state": item.state, "reason": item.reason, "symbol": item.symbol, "direction": item.direction,
            "entryLow": _json_safe(item.entry_low), "entryHigh": _json_safe(item.entry_high),
            "target": _json_safe(item.target), "stopLoss": _json_safe(item.stop_loss),
            "horizonSessions": item.horizon_sessions, "scoringStartAt": _json_safe(item.scoring_start_at),
            "scoringStartBasis": item.scoring_start_basis}


def record_event(session: Session, item: TipIntakeReview, action: str, *, actor: str, at: datetime,
                 before: dict | None = None, after: dict | None = None, note: str | None = None) -> None:
    session.add(TipIntakeReviewEvent(review_id=item.id, at=at, actor=actor, action=action, before=before,
                                     after=after, note=note))
    session.flush()


def _holds(session: Session, tip: Tip, receipt: TipReceipt) -> bool:
    return session.scalar(select(TipReceipt.id).where(
        TipReceipt.tip_id == tip.id, TipReceipt.user_id == receipt.user_id, TipReceipt.medium == receipt.medium,
        TipReceipt.kind == KIND_TIP)) is not None


def promote(session: Session, item: TipIntakeReview, tip: Tip, *, actor: str, at: datetime) -> Tip:
    """§8 step 3: the item's call becomes its tip, or joins the ACTIVE tip already holding its key -- never two."""
    target = None
    if tip.match_key is not None:
        lock_match_key(session, tip.match_key)
        target = session.scalar(select(Tip).where(Tip.match_key == tip.match_key, Tip.status == STATUS_ACTIVE))
    if target is None:
        tip.status, tip.intake_review_id = STATUS_ACTIVE, item.id
        tip.scoring_start_basis = tip.scoring_start_basis or item.scoring_start_basis
        session.add(tip)
        session.flush()
        target = tip
    receipts = session.scalars(select(TipReceipt).where(
        TipReceipt.intake_review_id == item.id, TipReceipt.tip_id.is_(None)).order_by(TipReceipt.id)).all()
    for receipt in receipts:
        if receipt.kind == KIND_TIP and _holds(session, target, receipt):
            continue
        receipt.tip_id = target.id
    item.state, item.promoted_tip_id, item.decided_at, item.decided_by = REVIEW_PROMOTED, target.id, at, actor
    record_event(session, item, EVENT_PROMOTED, actor=actor, at=at, after={"tipId": target.public_id})
    return target
```

- [ ] **Step 4: The gate in `app/tip_ledger.py`**

1. Imports:
   - Add `TipIntakeReview` to the `.models` import.
   - From `.tip_claimed_time` import `CallLevels, Claim, ScoringStart, admission_start, scoring_start`.
   - From `.tip_intake_review` import `item_state, lock_match_key, record_event, review_from_tip`.
   - From `.tip_minute_bars` add `MinuteBarsUnavailable`.
   - From `.market_calendar` add `last_trading_day_on_or_before`.
   - New vocabulary: `ACTOR_SYSTEM, EVENT_QUEUED, EVENT_RECEIPT_ATTACHED, REVIEW_DISMISSED, REVIEW_PROMOTED, REVIEW_REJECTED, START_ADMIN_ADMISSION, START_SERVER_RECEIPT, STATUS_UNSCORABLE`.
2. Replace the body of `_lock_match_key` with `lock_match_key(session, match_key)` and keep the name, because `marksy_tips`, `tip_ledger_admin` and `prediction_outcome_monitor` import it.
3. `Intake` gains `claim: Claim | None = None` after `extras`. `IntakeResult` gains `review: TipIntakeReview | None = None` as its last field.
4. Replace `_LIVE`/`find_matchable` (226-248) with:

```python
_REVIEW_OPEN = (STATUS_PENDING_CHECK, REVIEW_REJECTED, REVIEW_DISMISSED)


def find_matchable(session: Session, match_key: str, *, now: datetime) -> Tip | TipIntakeReview | None:
    """§5.2: the ACTIVE tip with this key, else the open review item: pending, or rejected/dismissed in its horizon."""
    tip = session.scalar(select(Tip).where(Tip.match_key == match_key, Tip.status == STATUS_ACTIVE)
                         .order_by(Tip.first_seen_at.desc(), Tip.id.desc()))
    if tip is not None:
        return tip
    items = session.scalars(
        select(TipIntakeReview).where(TipIntakeReview.match_key == match_key, TipIntakeReview.state.in_(_REVIEW_OPEN))
        .order_by(TipIntakeReview.scoring_start_at.desc(), TipIntakeReview.id.desc())
    ).all()
    for item in items:
        if item.state == STATUS_PENDING_CHECK:
            return item
    for item in items:
        if _sessions_since(session, item.scoring_start_at, now) <= (item.horizon_sessions or 0):
            return item
    return None
```

5. Add the gate after `_source`:

```python
@dataclass(frozen=True)
class GateDecision:
    tip: Tip
    rejection: tuple[str, str] | None
    pending: bool
    evidence: dict | None = None

    @property
    def admitted(self) -> bool:
        return self.rejection is None and not self.pending


def trading_days(session: Session):
    return lambda day: last_trading_day_on_or_before(session, "NSE", day)


def bars_for(stock: Stock, minute_bars: MinuteBarSource | None):
    def bars(day):
        if minute_bars is None or not stock.instrument_key:
            raise MinuteBarsUnavailable("no minute-bar source for this call")
        return minute_bars.minute_bars(stock.instrument_key, day)
    return bars


def _start(session: Session, terms: StatedTerms, stock: Stock | None, claim: Claim | None, *, received_at: datetime,
           minute_bars: MinuteBarSource | None, admitted_at: datetime | None) -> ScoringStart:
    days = trading_days(session)
    bars = bars_for(stock, minute_bars) if stock is not None and terms.direction is not None else None
    levels = CallLevels(terms.direction, terms.entry_low, terms.entry_high, terms.target, terms.stop_loss)
    start = scoring_start(received_at=received_at, claim=claim, levels=levels, on_or_before=days, bars_for_day=bars)
    if admitted_at is None:
        return start
    # Plan decision D9: once a market minute has passed, an admission scores from itself and the claim is moot.
    base = start if start.queue_reason is None else ScoringStart(start.at, START_SERVER_RECEIPT)
    admitted = admission_start(base, admitted_at, days)
    return admitted if admitted.basis == START_ADMIN_ADMISSION else start


def evaluate(session: Session, tip: Tip, terms: StatedTerms, stock: Stock | None, start: ScoringStart, *,
             minute_bars: MinuteBarSource | None) -> GateDecision:
    """§3 steps 1-2 for a transient candidate: its scoring start, then the §6.2 checks at that start."""
    tip.first_seen_at, tip.scoring_start_basis = start.at, start.basis
    entry_stated = terms.entry_low is not None
    check = EntryCheck(None, None)
    if stock is not None:
        tip.first_seen_price = first_seen_price(session, stock, start.at, minute_bars=minute_bars)
        if entry_stated:
            check = check_entry(session, stock, start.at, tip.first_seen_price)
    tip.entry_check_price, tip.entry_check_basis = check.price, check.basis
    if start.queue_reason is not None:
        return GateDecision(tip, (STATUS_UNSCORABLE, start.queue_reason), False, start.evidence)
    rejection = intake_rejection(terms, symbol_resolved=stock is not None, reference_price=check.price)
    if rejection is None and not check.pending:
        tip.entry_status = ENTRY_WAITING if entry_stated else ENTRY_ENTERED
    return GateDecision(tip, rejection, rejection is None and check.pending, start.evidence)


def admit_or_queue(session: Session, decision: GateDecision, *, actor: str = ACTOR_SYSTEM,
                   at: datetime | None = None) -> Tip | TipIntakeReview:
    """Intake review spec §3: the one place a call becomes a tip; anything unverified becomes a review item."""
    tip = decision.tip
    if decision.admitted:
        tip.status = STATUS_ACTIVE
        session.add(tip)
        session.flush()
        return tip
    rejected_status, reason = decision.rejection or (None, None)
    item = review_from_tip(tip, state=REVIEW_REJECTED if decision.rejection else STATUS_PENDING_CHECK,
                           reason=reason, rejected_status=rejected_status, evidence=decision.evidence)
    session.add(item)
    session.flush()
    record_event(session, item, EVENT_QUEUED, actor=actor, at=at or tip.received_at, after=item_state(item))
    return item
```

6. Replace `_new_tip` (371-426):

```python
def _new_tip(session, channel: Channel, receipt_in: ReceiptInput, intake: Intake, terms: StatedTerms, *, now,
             revises_tip_id: int | None = None, minute_bars: MinuteBarSource | None = None,
             admitted_at: datetime | None = None) -> Tip | TipIntakeReview:
    stock = session.scalar(select(Stock).where(Stock.symbol == terms.symbol))
    extras, claim = intake.extras, intake.claim
    horizon_stated = terms.horizon_sessions is not None
    tip = Tip(
        public_id=str(uuid.uuid4()), source=_source(extras, channel), client_name=extras.client_name,
        source_reference=extras.source_reference, symbol=terms.symbol,
        stock_id=stock.id if stock is not None else None, direction=terms.direction, entry_price=entry_mid(terms),
        target_price=terms.target, stop_loss=terms.stop_loss, horizon_days=terms.horizon_sessions,
        confidence=extras.confidence, rationale=extras.rationale, raw_payload=extras.raw_payload, received_at=now,
        tip_as_of=extras.tip_as_of, comparison_status=COMPARISON_STATUS_RECEIVED, source_channel_id=channel.id,
        entry_low=terms.entry_low, entry_high=terms.entry_high,
        entry_basis=ENTRY_BASIS_STATED if terms.entry_low is not None else ENTRY_BASIS_FIRST_SEEN_PRICE,
        horizon_sessions=terms.horizon_sessions if horizon_stated else channel.default_horizon_sessions,
        horizon_basis=HORIZON_BASIS_STATED if horizon_stated else HORIZON_BASIS_CHANNEL_DEFAULT,
        revises_tip_id=revises_tip_id, match_key=match_key_v1(channel.canonical_channel_id, terms),
        match_key_version=MATCH_KEY_VERSION, parser_version=receipt_in.parser_version,
        claimed_created_at=claim.at if claim is not None else None,
        claimed_time_source=claim.source if claim is not None else None,
    )
    start = _start(session, terms, stock, claim, received_at=now, minute_bars=minute_bars, admitted_at=admitted_at)
    return admit_or_queue(session, evaluate(session, tip, terms, stock, start, minute_bars=minute_bars), at=now)
```

7. `_new_receipt` gains `item: TipIntakeReview | None = None` and sets `intake_review_id=item.id if item is not None else None`.
8. Add `_attach_item` and `_fill_item_caller`:

```python
def _attach_item(session, item: TipIntakeReview, receipt_in: ReceiptInput, intake: Intake, *, now,
                 kind: str) -> IntakeResult:
    receipt = session.scalar(
        select(TipReceipt).where(
            TipReceipt.intake_review_id == item.id, TipReceipt.user_id == receipt_in.user_id,
            TipReceipt.medium == receipt_in.medium, TipReceipt.kind.in_((KIND_TIP, KIND_REVISION)),
        ).order_by(TipReceipt.id)
    )
    if receipt is None:
        receipt = _new_receipt(session, receipt_in, tip=None, item=item, kind=kind, now=now)
        record_event(session, item, EVENT_RECEIPT_ATTACHED, actor=ACTOR_SYSTEM, at=now,
                     after={"receiptId": receipt.public_id})
    _fill_item_caller(session, item, intake.caller_name)
    session.commit()
    return IntakeResult(receipt, None, kind, matched=True, created=False, review=item)


def _fill_item_caller(session, item: TipIntakeReview, caller_name: str | None) -> None:
    if caller_name and item.caller_id is None and item.source_channel_id is not None:
        item.caller_id = resolve_caller(session, item.source_channel_id, caller_name).id
```

9. `_record` (existing-receipt branch): load `review = session.get(TipIntakeReview, existing.intake_review_id)` when `existing.tip_id is None and existing.intake_review_id is not None`. Return `IntakeResult(existing, tip, existing.kind, matched=matched, created=False, review=review)`, where `matched = review is not None and _aware(review.received_at) < _aware(existing.recorded_at)` for a review.
10. `_record_tip`:
    - The source-reference lookup falls back to `select(TipIntakeReview).where(TipIntakeReview.source == source, TipIntakeReview.source_reference == extras.source_reference, TipIntakeReview.state != REVIEW_PROMOTED)`.
    - A match that is a `TipIntakeReview` goes to `_attach_item(..., kind=KIND_TIP)`; a `Tip` goes to `_attach`.
    - After `made = _new_tip(...)`: when `made` is a `TipIntakeReview`, create the receipt with `item=made`, call `_fill_item_caller`, commit, and return `IntakeResult(receipt, None, KIND_TIP, matched=False, created=True, review=made)`.
11. `_record_revision`: the same three dispatches with `KIND_REVISION`.

- [ ] **Step 5: Convert the intake tests**

- `tests/test_tip_ledger.py:158-168`: rename the parametrized test to `test_a_call_rejected_at_intake_is_queued_never_a_tip`. Assert `result.tip is None`, `(result.review.state, result.review.rejected_status, result.review.reason) == (REVIEW_REJECTED, status, reason)`, and `session.scalar(select(func.count()).select_from(Tip)) == 0`.
- `tests/test_tip_ledger.py:170-176`: the later-copy test asserts `copy.review.id == first.review.id` inside the horizon, and a new `review` (different id) after it.
- `tests/test_tip_first_seen_price_intake.py`:
  - Read `item = result.review` where the test read a rejected or pending tip.
  - `(tip.status, tip.reason) == ("UNSCORABLE", "ENTRY_OFF_MARKET")` → `(item.state, item.rejected_status, item.reason) == ("REJECTED", "UNSCORABLE", "ENTRY_OFF_MARKET")`.
  - The pending asserts at 166/209 → `(item.state, item.reason, item.entry_check_price) == ("PENDING_CHECK", None, None)`.
  - `entry_check_price` and `entry_check_basis` are read from the item.
  - The resent-pending test at 170 asserts the copy's `review.id` equals the first.

- [ ] **Step 6: Run green**

Run: `python -m pytest tests/test_tip_intake_review_gate.py tests/test_tip_ledger.py tests/test_tip_first_seen_price_intake.py tests/test_tip_intake_review_guard.py -q -p no:cacheprovider`
Expected: all pass.

- [ ] **Step 7: Commit**

```bash
git add app/tip_intake_review.py app/tip_ledger.py tests/test_tip_intake_review_gate.py tests/test_tip_ledger.py tests/test_tip_first_seen_price_intake.py
git commit -m "Intake gate: a call that fails or waits is queued for review, never a tip

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 4: Every intake path through the gate

**Files:**
- Modify:
  - `api/schemas/tips.py` (`TIP_STATUS_IN_REVIEW`)
  - `api/services/tips.py:120-186` (`_record_and_compare`, `submit_tip`), `:244-288` (`_submit_report_tip`), `:373-402` (`ingest_message`)
  - `api/routers/tips.py:127-133` (webhook result)
  - `app/tip_ledger.py` (`_reparse`, `_reparse_match`, `ReparseResult`)
  - `app/marksy_tips.py` (`register_prediction`, `open_rating_tip`)
  - `app/prediction_outcome_monitor.py`, `app/recommendations.py:157-159`
- Test: append to `tests/test_tip_intake_review_gate.py`
- Convert: `tests/test_marksy_prediction_tips.py:180-200, 296-305`

**Interfaces:**
- Consumes: Task 3 `admit_or_queue`, `GateDecision`, `find_matchable`, `Intake.claim`, `IntakeResult.review`.
- Produces:
  - `TIP_STATUS_IN_REVIEW = "IN_REVIEW"`
  - `register_prediction(...) -> Tip | TipIntakeReview`
  - `invalidate_queued_prediction(session, prediction, item, *, now) -> None`
  - `ReparseResult.review: TipIntakeReview | None = None`; re-parse outcome `REPARSE_QUEUED`
  - Webhook statuses `"recorded" | "in_review"`

- [ ] **Step 1: Write the failing tests (append)**

```python
from fastapi.testclient import TestClient
from sqlalchemy.pool import StaticPool

from api.deps import get_db
from api.services.tips import ingest_message, submit_tip
from api.schemas.tips import TIP_STATUS_IN_REVIEW, TipIngestRequest, TipRequest
from app.main import app
from app.tip_ledger import reparse_receipt
from app.tip_vocabulary import KIND_UNPARSED, REPARSE_QUEUED, START_ADMIN_ADMISSION


def test_post_tips_accepts_a_queued_call_as_in_review(session, monkeypatch):
    monkeypatch.setattr("api.services.tips._live_minute_bars", lambda at: CountingMinutes())
    request = TipRequest(symbol="RENUKA", source="Upstox", direction="BUY", entryPrice=Decimal("23.62"),
                         targetPrice=Decimal("20"), stopLoss=Decimal("22.25"))
    accepted, created = submit_tip(session, request, received_at=NOW)
    item = session.scalar(select(TipIntakeReview))
    assert (accepted.status, accepted.tipId, created) == (TIP_STATUS_IN_REVIEW, item.public_id, True)


def test_ingest_text_returns_no_tip_id_for_a_queued_call_and_records_the_device_time(session, monkeypatch):
    monkeypatch.setattr("api.services.tips._live_minute_bars", lambda at: CountingMinutes())
    request = TipIngestRequest(deviceEventKey="e-9", medium="APP_NOTIFICATION", appPackage="com.upstox.pro",
                               channelLabel="Upstox", text="RENUKA looks strong", devicePostedAt=NOW - timedelta(seconds=3))
    result = ingest_message(session, request, received_at=NOW, customer_id="user-1")
    assert result.tipId is None


def test_a_queued_public_id_is_not_a_tip(session):
    item = record_intake(session, _receipt(), _intake(direction=None), now=NOW, minute_bars=CountingMinutes()).review
    app.dependency_overrides[get_db] = lambda: (yield session)
    try:
        response = TestClient(app).get(f"/api/v1/tips/{item.public_id}")
    finally:
        app.dependency_overrides.clear()
    assert response.status_code == 404


def test_a_reparse_after_trading_is_dated_at_the_reparse(session):
    receipt = record_intake(session, _receipt(), Intake(kind=KIND_UNPARSED), now=NOW, minute_bars=None).receipt
    parsed = _intake(entry_low=None, entry_high=None)
    result = reparse_receipt(session, receipt, parsed, parser_version="TCP-002", now=NOW + timedelta(days=1))
    made = result.tip or result.review
    assert made.scoring_start_basis == START_ADMIN_ADMISSION
    assert _aware(made.first_seen_at if result.tip else made.scoring_start_at) == NOW + timedelta(days=1)
```

In `tests/test_api_tips_supabase_webhook.py`, add one test using that file's own fixtures. Post a row whose `target_price` is below its `entry_price`, then assert:
- status 200;
- `data.status == "in_review"`;
- `data.tipId` equals the public id of the only `TipIntakeReview` row.

Then post the same row again as an `UPDATE` and assert there is still exactly one item (source-reference idempotency).

The ingest test's text may parse as UNPARSED under the real parser; that is fine, since a null `tipId` is the contract either way. Its purpose is to fail if the service crashes on the claim plumbing. In `tests/test_marksy_prediction_tips.py`, the rejected-prediction tests become:
- `item = register_prediction(...)`
- `assert isinstance(item, TipIntakeReview)`
- `(item.state, item.rejected_status, item.reason) == (REVIEW_REJECTED, STATUS_UNSCORABLE, <reason>)`
- `tip_for_prediction(session, prediction.id) is None`

Line 303's status assert becomes the item assert. Line 305 (`get_terminal_event(...).evidence["tip_reason"]`) is unchanged and must still pass. Add one new test there: a second identical prediction while the first is queued returns the same item and its own prediction is INVALIDATED (D10).

- [ ] **Step 2: Run red**

Run: `python -m pytest tests/test_tip_intake_review_gate.py tests/test_marksy_prediction_tips.py -q -p no:cacheprovider`
Expected: `ImportError: cannot import name 'TIP_STATUS_IN_REVIEW'`.

- [ ] **Step 3: Services and routes**

In `api/schemas/tips.py`, next to `TIP_STATUS_RECEIVED`, add `TIP_STATUS_IN_REVIEW = "IN_REVIEW"`.

`api/services/tips.py`:

```python
def _claim(at: datetime | None, source: str) -> Claim | None:
    return Claim(at, source) if at is not None else None


def _report_claim(parsed: ParsedTip) -> Claim | None:
    """Spec §2: a report's own tipAsOf is a claimed time; a report date alone is date-only."""
    if parsed.tip_as_of:
        try:
            return Claim(datetime.fromisoformat(str(parsed.tip_as_of)), CLAIM_TIP_AS_OF)
        except ValueError:
            pass
    day = _report_date_as_of(parsed.report_date)
    return Claim(day, CLAIM_REPORT_DATE, date_only=True) if day is not None else None


def _in_review(item: TipIntakeReview) -> TipAccepted:
    return TipAccepted(tipId=item.public_id, status=TIP_STATUS_IN_REVIEW, receivedAt=_as_aware_utc(item.received_at))
```

- `submit_tip`: `Intake(..., claim=_claim(request.tipAsOf, CLAIM_TIP_AS_OF))`; then `if result.review is not None: return _in_review(result.review), result.created` before the `tip is None` check.
- `_submit_report_tip`: `claim=_report_claim(parsed)`; `if result.review is not None: return _in_review(result.review)`.
- `ingest_message`: `intake = replace(message_intake(parsed), claim=_claim(request.devicePostedAt, CLAIM_DEVICE_POSTED_AT))` (import `dataclasses.replace`).

`api/routers/tips.py` webhook return:

```python
    status = "in_review" if accepted[0].status == TIP_STATUS_IN_REVIEW else "recorded"
    return success(SupabaseWebhookResult(status=status, tipId=accepted[0].tipId))
```

- [ ] **Step 4: Re-parse through the gate (`app/tip_ledger.py`)**

- `ReparseResult` gains `review: TipIntakeReview | None = None`.
- In `_reparse`, before `_new_tip`, set `intake = replace(intake, claim=Claim(_aware(receipt.device_posted_at), CLAIM_DEVICE_POSTED_AT) if receipt.device_posted_at else None)`.
- Call `made = _new_tip(session, channel, receipt_in, intake, terms, now=seen, revises_tip_id=..., admitted_at=now)`. When `made` is a `TipIntakeReview`, set `receipt.kind, receipt.intake_review_id = kind, made.id`, commit, and return `ReparseResult(receipt, None, kind, REPARSE_QUEUED, review=made)`.
- In `_reparse_match`, delete the `REJECTED_AT_INTAKE` loop, since rejected calls are no longer tips. `find_matchable` may now return an item.
- When the match is a `TipIntakeReview`:
  - if this customer already holds it on this medium (same rule as `_held`, on `intake_review_id`), roll back, record the attempt as today, and return `REPARSE_ALREADY_HELD`;
  - otherwise set `receipt.kind, receipt.intake_review_id = kind, item.id`, write a `RECEIPT_ATTACHED` event, commit, and return `ReparseResult(receipt, None, kind, REPARSE_ATTACHED, review=item)`.
- Drop the now-unused `REJECTED_AT_INTAKE` and `INTAKE_REJECTION_REASONS` imports.

`api/services/admin_tip_ledger.py:reparse`: `ReparseView` gains `reviewId: str | None` (`result.review.public_id if result.review else None`). Add `reviewId: str | None = None` to `ReparseView` in `api/schemas/admin_tip_ledger.py`.

- [ ] **Step 5: Marksy calls (`app/marksy_tips.py`, `app/prediction_outcome_monitor.py`, `app/recommendations.py`)**

`register_prediction` (import `GateDecision, admit_or_queue` from `.tip_ledger`, `TipIntakeReview` from `.models`, `START_SERVER_RECEIPT` from `.tip_vocabulary`):

```python
    existing = tip_for_prediction(session, prediction.id) or session.scalar(
        select(TipIntakeReview).where(TipIntakeReview.prediction_id == prediction.id))
    if existing is not None:
        return existing
    ...
    if isinstance(canonical, TipIntakeReview):
        return canonical  # plan decision D10: the same rejected call again follows its queued item
    ...  # the Tip(...) construction is unchanged, plus scoring_start_basis=START_SERVER_RECEIPT
    rejection = intake_rejection(terms, symbol_resolved=True, reference_price=check.price)
    if canonical is not None:
        tip.status, tip.merged_into_tip_id, tip.closed_at = STATUS_MERGED_DUPLICATE, canonical.id, first_seen
        session.add(tip)
        session.flush()
        return tip
    if rejection is None:
        tip.entry_status = ENTRY_WAITING
    return admit_or_queue(session, GateDecision(tip, rejection, False), at=first_seen)
```

`open_rating_tip`: `existing = find_matchable(...)`, then `if isinstance(existing, Tip): return ...` (an item is ignored). Construct the `Tip` with `scoring_start_basis=START_SERVER_RECEIPT`, then `tip = admit_or_queue(session, GateDecision(tip, None, False), at=at)`. A direction-only rating call always passes.

`app/prediction_outcome_monitor.py`:

```python
def invalidate_queued_prediction(session: Session, prediction: Prediction, item: TipIntakeReview, *,
                                 now: datetime) -> None:
    """Intake review spec §3: a Marksy call queued at intake closes as a rejected tip did -- INVALIDATED."""
    if prediction.status == PREDICTION_STATUS_OPEN:
        prediction.status = PREDICTION_INVALIDATED
    if get_terminal_event(session, prediction.id) is None:
        _, _, prediction_version = get_target_stop_prices(session, prediction)
        session.add(PredictionOutcomeEvent(
            prediction_id=prediction.id, state=STATE_INVALIDATED, detected_at=now, observed_at=item.closed_at,
            observed_price=None, provider=None, prediction_version=prediction_version,
            evidence={"trigger": "tip_intake_review", "review_id": item.id, "tip_status": item.rejected_status,
                      "tip_reason": item.reason},
            monitor_rule_version=TIP_SYNC_RULE_VERSION,
        ))
    session.flush()
```

Import `PREDICTION_INVALIDATED` from where `prediction_status_for`'s values live (`app/marksy_tips.py`), or use the literal already used by `prediction_status_for`.

`app/recommendations.py:157-159`:

```python
        registered = register_prediction(session, recommendation)
        if isinstance(registered, TipIntakeReview):
            invalidate_queued_prediction(session, recommendation, registered, now=recommendation.created_at)
        else:
            sync_prediction_from_tip(session, registered, now=recommendation.created_at)
```

- [ ] **Step 6: Run green, plus the API suites**

Run: `python -m pytest tests/test_tip_intake_review_gate.py tests/test_marksy_prediction_tips.py tests/test_api_tips.py tests/test_api_tips_ingest.py tests/test_api_tips_ledger.py tests/test_api_tips_supabase_webhook.py tests/test_api_admin_tip_ledger.py tests/test_tip_ledger_admin.py tests/test_backfill_tip_ledger_script.py -q -p no:cacheprovider`
Expected: all pass. If a test in these files asserts a *tips* row for a rejected call, convert it as in Task 3 Step 5 and name it in the commit message.

- [ ] **Step 7: Commit**

```bash
git add api/schemas/tips.py api/schemas/admin_tip_ledger.py api/services/tips.py api/services/admin_tip_ledger.py api/routers/tips.py app/tip_ledger.py app/marksy_tips.py app/prediction_outcome_monitor.py app/recommendations.py tests/
git commit -m "Every intake path through the gate: claims recorded, queued calls IN_REVIEW, re-parse under AH

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 5: Promotion by the tracker's official pass

**Files:**
- Modify: `app/tip_tracking_job.py:309-330`
- Test: `tests/test_tip_intake_review_promotion.py`
- Convert: `tests/test_run_tip_tracker.py:146-200` (`_pending_in_hours_tip`, `_checked`)

**Interfaces:**
- Consumes: `tip_from_payload`, `promote`, `record_event`, `item_state` (Task 3); `check_entry`, `session_range_check`.
- Produces: `resolve_pending_entry_checks(session) -> {"passed", "rejected", "waiting"}` (unchanged contract); `check_pending_item(session, item_id) -> "passed" | "rejected" | "waiting" | "skipped"`.

- [ ] **Step 1: Write the failing tests**

```python
"""Intake review spec §8: the official pass promotes a verified pending call once, in one transaction."""

from __future__ import annotations

from datetime import datetime, timezone
from decimal import Decimal

import pytest
from sqlalchemy import create_engine, func, select
from sqlalchemy.orm import sessionmaker
from sqlalchemy.pool import StaticPool

from app.db import Base
from app.models import Channel, ChannelAlias, MarketPrice, Stock, Tip, TipIntakeReview, TipIntakeReviewEvent, TipReceipt
from app.tip_ledger import Intake, ReceiptInput, record_intake
from app.tip_matching import stated_terms
from app.tip_minute_bars import MinuteBarsUnavailable
from app.tip_tracking_job import check_pending_item, resolve_pending_entry_checks
from app.tip_vocabulary import ALIAS_PACKAGE, CHANNEL_BROKER_APP, KIND_TIP, MEDIUM_APP_NOTIFICATION

NOW = datetime(2026, 9, 28, 5, 0, tzinfo=timezone.utc)  # Monday 10:30 IST


class DownMinutes:
    def minute_bars(self, instrument_key, session_date):
        raise MinuteBarsUnavailable("upstox down")


@pytest.fixture
def factory():
    engine = create_engine("sqlite:///:memory:", connect_args={"check_same_thread": False}, poolclass=StaticPool)
    Base.metadata.create_all(engine)
    make = sessionmaker(bind=engine)
    with make() as db:
        db.add(Stock(symbol="RENUKA", instrument_key="NSE_EQ|RENUKA", exchange="NSE", is_active=True))
        upstox = Channel(name="Upstox", type=CHANNEL_BROKER_APP, default_horizon_sessions=20, capture_enabled=True)
        db.add(upstox)
        db.flush()
        upstox.canonical_channel_id = upstox.id
        db.add(ChannelAlias(channel_id=upstox.id, alias="com.upstox.pro", kind=ALIAS_PACKAGE))
        db.add(MarketPrice(stock_id=1, timestamp=datetime(2026, 9, 24, 18, 30, tzinfo=timezone.utc), open=30,
                           high=30, low=30, close=30, volume=1, source="upstox-v3"))  # Friday only: Monday waits
        db.commit()
    return make


def _pending(make, user="user-1", key="e-1"):
    with make() as db:
        receipt = ReceiptInput(user_id=user, device_event_key=key, medium=MEDIUM_APP_NOTIFICATION,
                               app_package="com.upstox.pro", channel_label="Upstox", text="BUY RENUKA",
                               device_posted_at=None, parser_version="TCP-001")
        terms = stated_terms(symbol="RENUKA", direction="BUY", entry_low=Decimal("23.62"), entry_high=Decimal("23.62"),
                             target=Decimal("26"), stop_loss=Decimal("22.25"), horizon_sessions=None)
        item = record_intake(db, receipt, Intake(kind=KIND_TIP, terms=terms), now=NOW, minute_bars=DownMinutes()).review
        return item.id


def _monday_bar(make, low, high):
    with make() as db:
        db.add(MarketPrice(stock_id=1, timestamp=datetime(2026, 9, 27, 18, 30, tzinfo=timezone.utc), open=low,
                           high=high, low=low, close=high, volume=1, source="upstox-v3"))
        db.commit()


def test_a_pending_call_that_passes_becomes_one_tip_with_its_receipts(factory):
    item_id = _pending(factory)
    _monday_bar(factory, 23, 24)
    with factory() as db:
        assert resolve_pending_entry_checks(db) == {"passed": 1, "rejected": 0, "waiting": 0}
        item, tip = db.get(TipIntakeReview, item_id), db.scalar(select(Tip))
        assert (item.state, item.promoted_tip_id, tip.public_id, tip.first_seen_at) == ("PROMOTED", tip.id, item.public_id, item.scoring_start_at)
        assert db.scalar(select(TipReceipt.tip_id)) == tip.id


def test_a_pending_call_off_the_market_is_rejected_and_stays_queued(factory):
    item_id = _pending(factory)
    _monday_bar(factory, 19, 21)  # a BUY at 23.62 is >10% above Monday's whole range
    with factory() as db:
        assert resolve_pending_entry_checks(db)["rejected"] == 1
        item = db.get(TipIntakeReview, item_id)
        assert (item.state, item.rejected_status, item.reason) == ("REJECTED", "UNSCORABLE", "ENTRY_OFF_MARKET")
        assert db.scalar(select(func.count()).select_from(Tip)) == 0


def test_without_a_price_it_keeps_waiting(factory):
    _pending(factory)
    with factory() as db:
        assert resolve_pending_entry_checks(db)["waiting"] == 1


def test_two_passes_and_a_stale_second_worker_make_exactly_one_tip(factory):
    item_id = _pending(factory)
    _monday_bar(factory, 23, 24)
    first, second = factory(), factory()
    stale = second.get(TipIntakeReview, item_id)  # read before the first pass commits
    assert check_pending_item(first, item_id) == "passed"
    assert check_pending_item(second, stale.id) == "skipped"
    assert resolve_pending_entry_checks(first) == {"passed": 0, "rejected": 0, "waiting": 0}
    assert first.scalar(select(func.count()).select_from(Tip)) == 1
    assert first.scalar(select(func.count()).select_from(TipIntakeReviewEvent).where(
        TipIntakeReviewEvent.action == "PROMOTED")) == 1


def test_a_failure_before_commit_leaves_no_partial_state(factory, monkeypatch):
    item_id = _pending(factory)
    _monday_bar(factory, 23, 24)
    monkeypatch.setattr("app.tip_tracking_job.promote", lambda *a, **k: (_ for _ in ()).throw(RuntimeError("boom")))
    with factory() as db, pytest.raises(RuntimeError):
        check_pending_item(db, item_id)
    with factory() as db:
        assert (db.get(TipIntakeReview, item_id).state, db.scalar(select(func.count()).select_from(Tip))) == ("PENDING_CHECK", 0)
```

- [ ] **Step 2: Run red**

Run: `python -m pytest tests/test_tip_intake_review_promotion.py -q -p no:cacheprovider`
Expected: `ImportError: cannot import name 'check_pending_item'`.

- [ ] **Step 3: Implement (replace `resolve_pending_entry_checks` in `app/tip_tracking_job.py`)**

```python
def resolve_pending_entry_checks(session: Session) -> dict:
    """§6.2/§8: each pending intake-review item is checked once a same-session price is stored, then promoted into
    `tips` or rejected; one transaction per item, so a rerun or a second worker never makes a second tip."""
    counts = {"passed": 0, "rejected": 0, "waiting": 0}
    ids = session.scalars(select(TipIntakeReview.id).where(TipIntakeReview.state == STATUS_PENDING_CHECK)
                          .order_by(TipIntakeReview.id)).all()
    session.rollback()
    for item_id in ids:
        outcome = check_pending_item(session, item_id)
        if outcome in counts:
            counts[outcome] += 1
    return counts


def check_pending_item(session: Session, item_id: int) -> str:
    try:
        item = session.get(TipIntakeReview, item_id, with_for_update={"key_share": True}, populate_existing=True)
        if item is None or item.state != STATUS_PENDING_CHECK:
            session.rollback()
            return "skipped"
        stock = session.get(Stock, item.stock_id)
        tip, at = tip_from_payload(item), _aware(item.scoring_start_at)
        check = check_entry(session, stock, at, tip.first_seen_price)
        if check.price is None and classify_session(at) == SESSION_MARKET_HOURS:
            check = session_range_check(session, stock, at, tip.entry_price) or check
        if check.price is None:
            session.rollback()
            return "waiting"
        item.entry_check_price, item.entry_check_basis = check.price, check.basis
        tip.entry_check_price, tip.entry_check_basis = check.price, check.basis
        now = datetime.now(timezone.utc)
        if entry_off_market(tip.direction, tip.entry_price, check.price):
            before = item_state(item)
            item.state, item.reason, item.rejected_status, item.closed_at = (
                REVIEW_REJECTED, REASON_ENTRY_OFF_MARKET, STATUS_UNSCORABLE, item.scoring_start_at)
            record_event(session, item, EVENT_RECHECKED, actor=ACTOR_TRACKER, at=now, before=before,
                         after=item_state(item))
            session.commit()
            return "rejected"
        tip.entry_status = ENTRY_WAITING
        promote(session, item, tip, actor=ACTOR_TRACKER, at=now)
        session.commit()
        return "passed"
    except Exception:
        session.rollback()
        raise
```

Import `TipIntakeReview`, `tip_from_payload, promote, record_event, item_state` and `ACTOR_TRACKER, EVENT_RECHECKED, REVIEW_REJECTED`. Drop `STATUS_UNSCORABLE` from the import if it becomes unused (it is still used here).

- [ ] **Step 4: Convert `tests/test_run_tip_tracker.py`**

- `_pending_in_hours_tip`: assert `_tip(...)` produced a pending item. Change `_tip` to return `result.review or result.tip`, and assert `.state == "PENDING_CHECK"`.
- `_checked` reads the item and, if promoted, its tip:

```python
def _checked(session_local):
    with session_local() as db:
        item = db.scalar(select(TipIntakeReview))
        if item.state == "PROMOTED":
            tip = db.get(Tip, item.promoted_tip_id)
            return tip.status, tip.reason, tip.entry_check_price, tip.entry_check_basis, False
        return item.rejected_status, item.reason, item.entry_check_price, item.entry_check_basis, item.closed_at == item.scoring_start_at
```

The expected tuples in the parametrize at 188-193 are unchanged.

- [ ] **Step 5: Run green**

Run: `python -m pytest tests/test_tip_intake_review_promotion.py tests/test_run_tip_tracker.py tests/test_tip_tracking_job.py -q -p no:cacheprovider`
Expected: all pass.

- [ ] **Step 6: Commit**

```bash
git add app/tip_tracking_job.py tests/test_tip_intake_review_promotion.py tests/test_run_tip_tracker.py
git commit -m "Official pass promotes verified pending calls into tips, once and transactionally

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 6: Admin review — resubmit, dismiss, history, anti-hindsight

**Files:**
- Modify:
  - `app/tip_ledger_admin.py` (append)
  - `api/schemas/admin_tip_ledger.py` (append)
  - `api/services/admin_tip_ledger.py` (append)
  - `api/routers/admin_tip_ledger.py` (append routes; add `Principal` to the deps import)
- Test: `tests/test_tip_intake_review_admin.py`, `tests/test_api_admin_intake_review.py`

**Interfaces:**
- Consumes: Task 1 `admission_start`, `ScoringStart`; Task 3 `evaluate`, `trading_days`, `promote`, `tip_from_payload`, `tip_payload`, `item_state`, `record_event`, `lock_match_key`; scorecard `scorecard()`.
- Produces:
  - `resubmit_review(session, review_id: str, edits: dict, *, actor: str, note: str, now: datetime, minute_bars=None) -> tuple[TipIntakeReview, Tip | None]`
  - `dismiss_review(session, review_id: str, *, reason: str, note: str, actor: str, now: datetime) -> TipIntakeReview`
  - Routes:
    - `GET /api/v1/admin/intake-reviews?state=&reason=&channelId=&pageSize=&cursor=`
    - `GET /api/v1/admin/intake-reviews/{reviewId}`
    - `POST /api/v1/admin/intake-reviews/{reviewId}/resubmit`
    - `POST /api/v1/admin/intake-reviews/{reviewId}/dismiss`
  - Schemas: `IntakeReviewView`, `IntakeReviewEventView`, `IntakeReviewDetailView`, `ResubmitIntakeReviewRequest`, `DismissIntakeReviewRequest`, `IntakeReviewDecisionView`, `IntakeReviewState`, `DismissReason`

- [ ] **Step 1: Write the failing domain tests (`tests/test_tip_intake_review_admin.py`)**

```python
"""Intake review spec §6: admins fix or dismiss rejected calls -- through the same gate, never with hindsight."""

from __future__ import annotations

import uuid
from datetime import date, datetime, timedelta, timezone
from decimal import Decimal

import pytest
from sqlalchemy import create_engine, select
from sqlalchemy.orm import sessionmaker

from app.db import Base
from app.market_data.quality import NSE_TIMEZONE
from app.models import Channel, ChannelAlias, MarketPrice, Stock, Tip, TipIntakeReview, TipIntakeReviewEvent
from app.tip_ledger import Intake, ReceiptInput, record_intake
from app.tip_ledger_admin import LedgerAdminConflict, dismiss_review, resubmit_review
from app.tip_matching import stated_terms
from app.tip_scorecard_query import SCOPE_CHANNEL, scorecard
from app.tip_tracker import PriceBar
from app.tip_tracker import tip_sessions
from app.tip_vocabulary import (
    ALIAS_PACKAGE, CHANNEL_BROKER_APP, KIND_TIP, MEDIUM_APP_NOTIFICATION, OUTCOME_FAILURE, OUTCOME_SUCCESS,
    START_ADMIN_ADMISSION, START_SERVER_RECEIPT, STATUS_ACTIVE, STATUS_STOP_LOSS_HIT, STATUS_TARGET_HIT,
)

MON_10 = datetime(2026, 9, 28, 4, 30, tzinfo=timezone.utc)   # 10:00 IST
MON_18 = datetime(2026, 9, 28, 12, 30, tzinfo=timezone.utc)  # 18:00 IST
MON_21 = datetime(2026, 9, 28, 15, 30, tzinfo=timezone.utc)  # 21:00 IST
MON_14 = datetime(2026, 9, 28, 8, 30, tzinfo=timezone.utc)   # 14:00 IST
TUE_10 = datetime(2026, 9, 29, 4, 30, tzinfo=timezone.utc)
AS_OF = datetime(2026, 9, 30, 12, 0, tzinfo=timezone.utc)


class FlatMinutes:
    def __init__(self, price="23.62"):
        self.price = Decimal(price)

    def minute_bars(self, instrument_key, session_date):
        start = datetime.combine(session_date, datetime.min.time(), NSE_TIMEZONE).replace(hour=9, minute=15)
        return [PriceBar(start + timedelta(minutes=i), self.price, self.price, self.price, self.price)
                for i in range(375)]


@pytest.fixture
def session():
    engine = create_engine("sqlite:///:memory:")
    Base.metadata.create_all(engine)
    db = sessionmaker(bind=engine)()
    db.add(Stock(symbol="RENUKA", instrument_key="NSE_EQ|RENUKA", exchange="NSE", is_active=True))
    upstox = Channel(name="Upstox", type=CHANNEL_BROKER_APP, default_horizon_sessions=20, capture_enabled=True)
    db.add(upstox)
    db.flush()
    upstox.canonical_channel_id = upstox.id
    db.add(ChannelAlias(channel_id=upstox.id, alias="com.upstox.pro", kind=ALIAS_PACKAGE))
    db.add(MarketPrice(stock_id=1, timestamp=datetime(2026, 9, 27, 18, 30, tzinfo=timezone.utc), open=23.5,
                       high=26.5, low=23.4, close=23.6, volume=1, source="upstox-v3"))  # Monday: target 26 touched
    _history(db, upstox)
    db.commit()
    try:
        yield db
    finally:
        db.close()


def _history(db, channel):
    """Ten completed calls, 6 won and 4 lost, so the channel has a trust score to protect."""
    for index in range(10):
        won = index < 6
        db.add(Tip(public_id=str(uuid.uuid4()), source="Upstox", symbol="RENUKA", direction="BUY", received_at=MON_10,
                   comparison_status="RECEIVED", source_channel_id=channel.id, match_key=f"h-{index}",
                   first_seen_at=MON_10 - timedelta(days=30), horizon_sessions=20,
                   status=STATUS_TARGET_HIT if won else STATUS_STOP_LOSS_HIT,
                   outcome=OUTCOME_SUCCESS if won else OUTCOME_FAILURE, closed_at=MON_10 - timedelta(days=10),
                   actual_return=Decimal("0.05") if won else Decimal("-0.03"), promised_return=Decimal("0.08")))


def _rejected(session, received, *, target="20"):
    """A BUY whose target was mis-read below its entry: rejected INCONSISTENT_LEVELS."""
    receipt = ReceiptInput(user_id="user-1", device_event_key=str(uuid.uuid4()), medium=MEDIUM_APP_NOTIFICATION,
                           app_package="com.upstox.pro", channel_label="Upstox", text="BUY RENUKA",
                           device_posted_at=None, parser_version="TCP-001")
    terms = stated_terms(symbol="RENUKA", direction="BUY", entry_low=None, entry_high=None, target=Decimal(target),
                         stop_loss=Decimal("22.25"), horizon_sessions=None)
    return record_intake(session, receipt, Intake(kind=KIND_TIP, terms=terms), now=received,
                         minute_bars=FlatMinutes()).review


def _aware(value):
    return value if value.tzinfo is not None else value.replace(tzinfo=timezone.utc)  # SQLite drops tzinfo


def _card(session):
    channel_id = session.scalar(select(Channel.id).where(Channel.name == "Upstox"))
    metrics = scorecard(session, scope=SCOPE_CHANNEL, scope_id=channel_id, as_of=AS_OF)[1].metrics
    return metrics.trust_score, metrics.success_pct, metrics.completed


def test_an_admission_before_any_trading_keeps_the_original_start(session):
    item = _rejected(session, MON_18)
    _, tip = resubmit_review(session, item.public_id, {"target": Decimal("26")}, actor="admin-1", note="target misread",
                             now=MON_21, minute_bars=FlatMinutes())
    assert (_aware(tip.first_seen_at), tip.scoring_start_basis) == (MON_18, START_SERVER_RECEIPT)


def test_an_admission_after_trading_scores_from_the_admission(session):
    item = _rejected(session, MON_10)
    _, tip = resubmit_review(session, item.public_id, {"target": Decimal("26")}, actor="admin-1", note="fix",
                             now=MON_14, minute_bars=FlatMinutes())
    assert (_aware(tip.first_seen_at), tip.scoring_start_basis) == (MON_14, START_ADMIN_ADMISSION)


def test_admitting_a_winner_after_its_target_was_hit_cannot_raise_the_score(session):
    baseline = _card(session)
    item = _rejected(session, MON_10)
    _, tip = resubmit_review(session, item.public_id, {"target": Decimal("26")}, actor="admin-1",
                             note="target misread", now=TUE_10, minute_bars=FlatMinutes())
    assert (_aware(tip.first_seen_at), tip.status) == (TUE_10, STATUS_ACTIVE)
    monday = date(2026, 9, 28)
    scored = tip_sessions(_aware(tip.first_seen_at), tip.horizon_sessions, [monday, date(2026, 9, 29)])
    assert monday not in [s.date for s in scored]  # Monday's target touch is outside what gets scored
    assert _card(session) == baseline


@pytest.mark.parametrize("decision", ["dismiss", "admit"])
def test_dismissing_or_admitting_a_loser_after_its_stop_leaves_the_score_alone(session, decision):
    baseline = _card(session)
    item = _rejected(session, MON_10)
    if decision == "dismiss":
        dismiss_review(session, item.public_id, reason="UNFIXABLE_TERMS", note="levels unknowable", actor="admin-1",
                       now=TUE_10)
        assert session.get(TipIntakeReview, item.id).state == "DISMISSED"
    else:
        resubmit_review(session, item.public_id, {"target": Decimal("26")}, actor="admin-1", note="fix", now=TUE_10,
                        minute_bars=FlatMinutes())
    assert _card(session) == baseline


def test_a_fix_that_still_fails_stays_rejected_with_its_history(session):
    item = _rejected(session, MON_10)
    reviewed, tip = resubmit_review(session, item.public_id, {"target": Decimal("21")}, actor="admin-1",
                                    note="second try", now=TUE_10, minute_bars=FlatMinutes())
    assert (tip, reviewed.state, reviewed.reason) == (None, "REJECTED", "INCONSISTENT_LEVELS")
    actions = session.scalars(select(TipIntakeReviewEvent.action).where(TipIntakeReviewEvent.review_id == item.id)
                              .order_by(TipIntakeReviewEvent.id)).all()
    assert actions == ["QUEUED", "RESUBMITTED"]


def test_a_pending_item_cannot_be_dismissed_or_resubmitted(session):
    item = _rejected(session, MON_10)
    item.state = "PENDING_CHECK"
    session.commit()
    with pytest.raises(LedgerAdminConflict):
        dismiss_review(session, item.public_id, reason="OTHER", note="x", actor="admin-1", now=TUE_10)
    with pytest.raises(LedgerAdminConflict):
        resubmit_review(session, item.public_id, {}, actor="admin-1", note="x", now=TUE_10)


def test_a_fix_that_matches_an_active_tip_joins_it(session):
    first = _rejected(session, MON_10)
    _, tip = resubmit_review(session, first.public_id, {"target": Decimal("26")}, actor="admin-1", note="fix",
                             now=TUE_10, minute_bars=FlatMinutes())
    second = _rejected(session, TUE_10 + timedelta(minutes=5))
    _, joined = resubmit_review(session, second.public_id, {"target": Decimal("26")}, actor="admin-1", note="fix",
                                now=TUE_10 + timedelta(minutes=10), minute_bars=FlatMinutes())
    assert joined.id == tip.id
    assert session.scalar(select(TipIntakeReview.promoted_tip_id).where(TipIntakeReview.id == second.id)) == tip.id
```

- [ ] **Step 2: Write the failing API tests (`tests/test_api_admin_intake_review.py`)**

Copy the `session`/`client` fixtures from `tests/test_api_admin_tip_ledger.py:31-70` and the `_rejected` helper above, then:

```python
def test_list_detail_resubmit_and_dismiss_over_http(client, session):
    item = _rejected(session, MON_10)
    listed = client.get("/api/v1/admin/intake-reviews", params={"state": "REJECTED"}).json()["data"]
    assert [row["reviewId"] for row in listed] == [item.public_id]
    detail = client.get(f"/api/v1/admin/intake-reviews/{item.public_id}").json()["data"]
    assert [e["action"] for e in detail["events"]] == ["QUEUED"] and len(detail["receipts"]) == 1
    refused = client.post(f"/api/v1/admin/intake-reviews/{item.public_id}/dismiss", json={"reason": "OTHER"})
    assert refused.status_code == 422  # a dismissal needs a note
    done = client.post(f"/api/v1/admin/intake-reviews/{item.public_id}/dismiss",
                       json={"reason": "NOT_A_TIP", "note": "market commentary"})
    assert done.json()["data"]["state"] == "DISMISSED"


def test_no_request_field_can_set_a_scoring_start_or_claimed_time(client, session):
    item = _rejected(session, MON_10)
    response = client.post(f"/api/v1/admin/intake-reviews/{item.public_id}/resubmit",
                           json={"target": "26", "note": "fix", "scoringStartAt": "2026-09-28T04:30:00Z"})
    assert response.status_code == 422


def test_resubmitting_a_pending_item_is_a_conflict(client, session):
    item = _rejected(session, MON_10)
    item.state = "PENDING_CHECK"
    session.commit()
    response = client.post(f"/api/v1/admin/intake-reviews/{item.public_id}/resubmit", json={"note": "x"})
    assert (response.status_code, response.json()["error"]["code"]) == (409, "MRA_REVIEW_NOT_REJECTED")
```

- [ ] **Step 3: Run red**

Run: `python -m pytest tests/test_tip_intake_review_admin.py tests/test_api_admin_intake_review.py -q -p no:cacheprovider`
Expected: `ImportError: cannot import name 'dismiss_review'`.

- [ ] **Step 4: Domain (`app/tip_ledger_admin.py`, append)**

```python
_EDITABLE = {"symbol": "symbol", "direction": "direction", "entryLow": "entry_low", "entryHigh": "entry_high",
             "target": "target", "stopLoss": "stop_loss", "horizonSessions": "horizon_sessions"}


def _rejected_review(session: Session, review_id: str) -> TipIntakeReview:
    item = session.scalar(select(TipIntakeReview).where(TipIntakeReview.public_id == review_id)
                          .with_for_update(key_share=True))
    if item is None:
        raise LedgerAdminNotFound("IntakeReview", review_id)
    if item.state != REVIEW_REJECTED:
        raise LedgerAdminConflict("MRA_REVIEW_NOT_REJECTED",
                                  f"intake review {review_id} is {item.state}; only REJECTED calls are decided by an admin")
    return item


def _note(note: str) -> str:
    clean = note.strip()
    if not clean:
        raise LedgerAdminInvalid("an admin decision needs a note", "note")
    return clean[:2000]


def resubmit_review(session: Session, review_id: str, edits: dict, *, actor: str, note: str, now: datetime,
                    minute_bars=None) -> tuple[TipIntakeReview, Tip | None]:
    """§6: corrected terms through the full gate with no override; AH1/AH2 decide when scoring starts."""
    item = _rejected_review(session, review_id)
    note = _note(note)
    unknown = set(edits) - set(_EDITABLE)
    if unknown:
        raise LedgerAdminInvalid(f"{sorted(unknown)} cannot be edited", sorted(unknown)[0])
    current = {key: getattr(item, attr) for key, attr in _EDITABLE.items()}
    if item.horizon_basis == HORIZON_BASIS_CHANNEL_DEFAULT:
        current["horizonSessions"] = None  # a default never enters the match key
    merged = {**current, **edits}
    terms = stated_terms(symbol=merged["symbol"], direction=merged["direction"], entry_low=merged["entryLow"],
                         entry_high=merged["entryHigh"], target=merged["target"], stop_loss=merged["stopLoss"],
                         horizon_sessions=merged["horizonSessions"])
    channel = session.get(Channel, item.source_channel_id)
    stock = session.scalar(select(Stock).where(Stock.symbol == terms.symbol))
    start = admission_start(ScoringStart(_aware(item.scoring_start_at), item.scoring_start_basis), now,
                            trading_days(session))
    candidate = tip_from_payload(item)
    _apply_terms(candidate, terms, channel, stock, item)
    decision = evaluate(session, candidate, terms, stock, start, minute_bars=minute_bars)
    admin = f"admin:{actor}"
    before = {**item_state(item), "payload": item.payload}
    lock_match_key(session, candidate.match_key)
    _record_terms(item, candidate, decision)
    if decision.admitted:
        record_event(session, item, EVENT_RESUBMITTED, actor=admin, at=now, before=before, after=item_state(item),
                     note=note)
        tip = promote(session, item, candidate, actor=admin, at=now)
        session.commit()
        return item, tip
    if decision.pending:
        item.state, item.reason, item.rejected_status, item.closed_at = STATUS_PENDING_CHECK, None, None, None
    else:
        (item.rejected_status, item.reason), item.closed_at = decision.rejection, candidate.first_seen_at
    record_event(session, item, EVENT_RESUBMITTED, actor=admin, at=now, before=before, after=item_state(item),
                 note=note)
    session.commit()
    return item, None


def _apply_terms(candidate: Tip, terms, channel: Channel, stock: Stock | None, item: TipIntakeReview) -> None:
    candidate.symbol, candidate.stock_id, candidate.direction = terms.symbol, stock.id if stock else None, terms.direction
    candidate.entry_low, candidate.entry_high, candidate.entry_price = terms.entry_low, terms.entry_high, entry_mid(terms)
    candidate.target_price, candidate.stop_loss = terms.target, terms.stop_loss
    candidate.entry_basis = ENTRY_BASIS_STATED if terms.entry_low is not None else ENTRY_BASIS_FIRST_SEEN_PRICE
    stated = terms.horizon_sessions is not None
    candidate.horizon_sessions = terms.horizon_sessions if stated else channel.default_horizon_sessions
    candidate.horizon_days = terms.horizon_sessions
    candidate.horizon_basis = (item.horizon_basis if stated and item.horizon_basis == HORIZON_BASIS_ENGINE
                               else HORIZON_BASIS_STATED if stated else HORIZON_BASIS_CHANNEL_DEFAULT)
    candidate.match_key = match_key_v1(channel.canonical_channel_id, terms)
    candidate.match_key_version = MATCH_KEY_VERSION


def _record_terms(item: TipIntakeReview, candidate: Tip, decision) -> None:
    item.symbol, item.stock_id, item.direction = candidate.symbol, candidate.stock_id, candidate.direction
    item.entry_low, item.entry_high, item.target, item.stop_loss = (
        candidate.entry_low, candidate.entry_high, candidate.target_price, candidate.stop_loss)
    item.horizon_sessions, item.horizon_basis, item.match_key = (
        candidate.horizon_sessions, candidate.horizon_basis, candidate.match_key)
    item.scoring_start_at, item.scoring_start_basis = candidate.first_seen_at, candidate.scoring_start_basis
    item.first_seen_price, item.entry_check_price, item.entry_check_basis = (
        candidate.first_seen_price, candidate.entry_check_price, candidate.entry_check_basis)
    item.check_evidence, item.payload = decision.evidence, tip_payload(candidate)


def dismiss_review(session: Session, review_id: str, *, reason: str, note: str, actor: str,
                   now: datetime) -> TipIntakeReview:
    """§6: a rejected call an admin will not enter; it stays counted as rejected and fully auditable."""
    item = _rejected_review(session, review_id)
    note = _note(note)
    if reason not in DISMISS_REASONS:
        raise LedgerAdminInvalid(f"reason must be one of {', '.join(DISMISS_REASONS)}", "reason")
    admin = f"admin:{actor}"
    before = item_state(item)
    item.state, item.decided_at, item.decided_by = REVIEW_DISMISSED, now, admin
    record_event(session, item, EVENT_DISMISSED, actor=admin, at=now, before=before,
                 after={**item_state(item), "dismissReason": reason}, note=note)
    session.commit()
    return item
```

Imports needed in `app/tip_ledger_admin.py`:
- `Stock, TipIntakeReview` from `.models`
- `ScoringStart, admission_start` from `.tip_claimed_time`
- `evaluate, trading_days` from `.tip_ledger`
- `item_state, lock_match_key, promote, record_event, tip_from_payload, tip_payload` from `.tip_intake_review`
- `MATCH_KEY_VERSION, entry_mid, match_key_v1, stated_terms` from `.tip_matching`
- the vocabulary: `DISMISS_REASONS, ENTRY_BASIS_FIRST_SEEN_PRICE, ENTRY_BASIS_STATED, EVENT_DISMISSED, EVENT_RESUBMITTED, HORIZON_BASIS_CHANNEL_DEFAULT, HORIZON_BASIS_ENGINE, HORIZON_BASIS_STATED, REVIEW_DISMISSED, REVIEW_REJECTED, STATUS_PENDING_CHECK`
- `_aware`, defined locally as in `tip_ledger`

- [ ] **Step 5: Schemas, service, routes**

`api/schemas/admin_tip_ledger.py` (append):

```python
IntakeReviewState = Literal["PENDING_CHECK", "REJECTED", "PROMOTED", "DISMISSED"]
DismissReason = Literal["NOT_A_TIP", "UNFIXABLE_TERMS", "DUPLICATE", "OFF_MARKET_CONFIRMED", "OTHER"]


class IntakeReviewView(BaseModel):
    reviewId: str
    state: str
    reason: str | None
    rejectedStatus: str | None
    origin: str
    channelId: int | None
    channelName: str | None
    callerId: int | None
    symbol: str
    direction: str | None
    entryLow: Decimal | None
    entryHigh: Decimal | None
    target: Decimal | None
    stopLoss: Decimal | None
    horizonSessions: int | None
    receivedAt: datetime
    claimedCreatedAt: datetime | None
    claimedTimeSource: str | None
    scoringStartAt: datetime
    scoringStartBasis: str
    entryCheckPrice: Decimal | None
    entryCheckBasis: str | None
    checkEvidence: dict | None
    promotedTipId: str | None
    decidedAt: datetime | None
    decidedBy: str | None
    receiptCount: int


class IntakeReviewEventView(BaseModel):
    at: datetime
    actor: str
    action: str
    before: dict | None
    after: dict | None
    note: str | None


class IntakeReviewDetailView(IntakeReviewView):
    receipts: list[AdminReceiptView]
    events: list[IntakeReviewEventView]


class ResubmitIntakeReviewRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")
    symbol: str | None = Field(default=None, min_length=1, max_length=32)
    direction: str | None = None
    entryLow: Decimal | None = None
    entryHigh: Decimal | None = None
    target: Decimal | None = None
    stopLoss: Decimal | None = None
    horizonSessions: int | None = Field(default=None, ge=0)
    note: str = Field(min_length=1, max_length=2000)


class DismissIntakeReviewRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")
    reason: DismissReason
    note: str = Field(min_length=1, max_length=2000)


class IntakeReviewDecisionView(BaseModel):
    review: IntakeReviewView
    tipId: str | None
```

`api/services/admin_tip_ledger.py` (append; same keyset pattern as `list_review_receipts`):

```python
def _review_views(db: Session, items: list[TipIntakeReview]) -> list[IntakeReviewView]:
    channel_ids = {item.source_channel_id for item in items if item.source_channel_id is not None}
    names = dict(db.execute(select(Channel.id, Channel.name).where(Channel.id.in_(channel_ids))).all()) if channel_ids else {}
    ids = [item.id for item in items]
    counts = dict(db.execute(select(TipReceipt.intake_review_id, func.count()).where(
        TipReceipt.intake_review_id.in_(ids)).group_by(TipReceipt.intake_review_id)).all()) if ids else {}
    promoted = {item.promoted_tip_id for item in items if item.promoted_tip_id is not None}
    tip_ids = dict(db.execute(select(Tip.id, Tip.public_id).where(Tip.id.in_(promoted))).all()) if promoted else {}
    return [IntakeReviewView(
        reviewId=item.public_id, state=item.state, reason=item.reason, rejectedStatus=item.rejected_status,
        origin=item.origin, channelId=item.source_channel_id, channelName=names.get(item.source_channel_id),
        callerId=item.caller_id, symbol=item.symbol, direction=item.direction, entryLow=item.entry_low,
        entryHigh=item.entry_high, target=item.target, stopLoss=item.stop_loss,
        horizonSessions=item.horizon_sessions, receivedAt=_utc(item.received_at),
        claimedCreatedAt=_utc(item.claimed_created_at), claimedTimeSource=item.claimed_time_source,
        scoringStartAt=_utc(item.scoring_start_at), scoringStartBasis=item.scoring_start_basis,
        entryCheckPrice=item.entry_check_price, entryCheckBasis=item.entry_check_basis,
        checkEvidence=item.check_evidence, promotedTipId=tip_ids.get(item.promoted_tip_id),
        decidedAt=_utc(item.decided_at), decidedBy=item.decided_by, receiptCount=counts.get(item.id, 0),
    ) for item in items]


def list_intake_reviews(db: Session, *, state: str | None, reason: str | None, channel_id: int | None,
                        page_size: int, cursor: str | None) -> AdminPage:
    statement = select(TipIntakeReview)
    if state:
        statement = statement.where(TipIntakeReview.state == state)
    if reason:
        statement = statement.where(TipIntakeReview.reason == reason)
    if channel_id is not None:
        statement = statement.where(TipIntakeReview.source_channel_id == channel_id)
    if cursor:
        cursor_value, cursor_id = decode_cursor(cursor, is_datetime=True)
        statement = statement.where(keyset_predicate(TipIntakeReview.received_at, TipIntakeReview.id, cursor_value,
                                                     cursor_id, descending=True))
    rows = list(db.scalars(statement.order_by(TipIntakeReview.received_at.desc(), TipIntakeReview.id.desc())
                           .limit(page_size + 1)))
    has_more = len(rows) > page_size
    rows = rows[:page_size]
    next_cursor = encode_cursor(rows[-1].received_at, rows[-1].id) if has_more and rows else None
    return AdminPage(items=_review_views(db, rows), next_cursor=next_cursor)


def _review_or_404(db: Session, review_id: str) -> TipIntakeReview:
    item = db.scalar(select(TipIntakeReview).where(TipIntakeReview.public_id == review_id))
    if item is None:
        raise NotFoundError("IntakeReview", review_id)
    return item


def intake_review_detail(db: Session, review_id: str) -> IntakeReviewDetailView:
    item = _review_or_404(db, review_id)
    receipts = db.scalars(select(TipReceipt).where(TipReceipt.intake_review_id == item.id)
                          .order_by(TipReceipt.recorded_at, TipReceipt.id))
    events = db.scalars(select(TipIntakeReviewEvent).where(TipIntakeReviewEvent.review_id == item.id)
                        .order_by(TipIntakeReviewEvent.id))
    view = _review_views(db, [item])[0]
    return IntakeReviewDetailView(
        **view.model_dump(), receipts=[_receipt_view(r, None) for r in receipts],
        events=[IntakeReviewEventView(at=_utc(e.at), actor=e.actor, action=e.action, before=e.before, after=e.after,
                                      note=e.note) for e in events],
    )


def resubmit_intake_review(db: Session, review_id: str, request: ResubmitIntakeReviewRequest, *,
                           actor: str) -> IntakeReviewDecisionView:
    edits = request.model_dump(exclude_unset=True, exclude={"note"})
    now = datetime.now(timezone.utc)
    live = _live_minute_bars(now)
    try:
        with _ledger_errors():
            item, tip = resubmit_review(db, review_id, edits, actor=actor, note=request.note, now=now,
                                        minute_bars=live)
    finally:
        close = getattr(live, "close", None)
        if close is not None:
            close()
    return IntakeReviewDecisionView(review=_review_views(db, [item])[0], tipId=tip.public_id if tip else None)


def dismiss_intake_review(db: Session, review_id: str, request: DismissIntakeReviewRequest, *,
                          actor: str) -> IntakeReviewView:
    with _ledger_errors():
        item = dismiss_review(db, review_id, reason=request.reason, note=request.note, actor=actor,
                              now=datetime.now(timezone.utc))
    return _review_views(db, [item])[0]
```

Import `_live_minute_bars` from `.tips`, `resubmit_review, dismiss_review` from `app.tip_ledger_admin`, and `TipIntakeReview, TipIntakeReviewEvent` from `app.models`.

`api/routers/admin_tip_ledger.py` (append):

```python
@router.get("/intake-reviews", response_model=CursorEnvelope[IntakeReviewView])
def get_intake_reviews(
    state: IntakeReviewState | None = Query(default=None),
    reason: str | None = Query(default=None),
    channelId: int | None = Query(default=None),
    pageSize: int = Query(default=DEFAULT_PAGE_SIZE, ge=1, le=MAX_PAGE_SIZE),
    cursor: str | None = Query(default=None),
    db: Session = Depends(get_db),
):
    """Intake review spec §6: calls kept out of `tips`, newest first."""
    page = admin_tip_ledger.list_intake_reviews(db, state=state, reason=reason, channel_id=channelId,
                                                page_size=pageSize, cursor=cursor)
    return cursor_paginated(page.items, page_size=pageSize, next_cursor=page.next_cursor)


@router.get("/intake-reviews/{review_id}", response_model=SuccessEnvelope[IntakeReviewDetailView])
def get_intake_review(review_id: str, db: Session = Depends(get_db)):
    return success(admin_tip_ledger.intake_review_detail(db, review_id))


@router.post("/intake-reviews/{review_id}/resubmit", response_model=SuccessEnvelope[IntakeReviewDecisionView])
def post_intake_review_resubmit(review_id: str, request: ResubmitIntakeReviewRequest, db: Session = Depends(get_db),
                                principal: Principal = Depends(require_scope(SCOPE_ADMIN))):
    """REJECTED only (409 otherwise); the full gate runs, and AH1/AH2 decide when scoring starts."""
    return success(admin_tip_ledger.resubmit_intake_review(db, review_id, request, actor=principal.subject or "admin"))


@router.post("/intake-reviews/{review_id}/dismiss", response_model=SuccessEnvelope[IntakeReviewView])
def post_intake_review_dismiss(review_id: str, request: DismissIntakeReviewRequest, db: Session = Depends(get_db),
                               principal: Principal = Depends(require_scope(SCOPE_ADMIN))):
    return success(admin_tip_ledger.dismiss_intake_review(db, review_id, request, actor=principal.subject or "admin"))
```

- [ ] **Step 6: Run green**

Run: `python -m pytest tests/test_tip_intake_review_admin.py tests/test_api_admin_intake_review.py tests/test_api_admin_tip_ledger.py tests/test_tip_ledger_admin.py -q -p no:cacheprovider`
Expected: all pass. If `test_admitting_a_winner…` shows a different card, the scorecard is counting the admitted tip as completed: stop and inspect `first_seen_at`. Never edit the baseline.

- [ ] **Step 7: Commit**

```bash
git add app/tip_ledger_admin.py api/schemas/admin_tip_ledger.py api/services/admin_tip_ledger.py api/routers/admin_tip_ledger.py tests/test_tip_intake_review_admin.py tests/test_api_admin_intake_review.py
git commit -m "Admin intake review: resubmit through the gate, dismiss with a reason, never with hindsight

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 7: Scorecards read queued calls as they read rejected tips

**Files:**
- Modify: `app/tip_scorecard_query.py` (`_conditions` 199-221, `_facts` 224-240, `_scope_conditions` 262-277, `scorecard` 280-295, `scorecards_by_entity` 303-324, `scorecard_summary` 326-340, `headlines` 341-367, `scorecard_detail_query` 437-471)
- Test: `tests/test_tip_intake_review_scorecards.py`

**Interfaces:**
- Consumes: `TipIntakeReview` (Task 2); `compute_metrics`, `TipFact` (unchanged).
- Produces: `_fact_source()`, a union subquery with columns `tip_id, review_id, source_channel_id, caller_id, status, outcome, closed_at, closed_session, promised_return, actual_return, horizon_sessions, symbol, match_key, first_seen_at`. `_conditions(directory, query, facts)`, `_scope_conditions(directory, scope, scope_id, facts)` and `_facts(session, directory, facts, conditions)` all take that source.

- [ ] **Step 1: Write the failing test**

```python
"""Intake review spec §7: moving rejected and pending calls into the queue changes no §8 number."""

from __future__ import annotations

import uuid
from datetime import datetime, timedelta, timezone
from decimal import Decimal

import pytest
from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker

from app.db import Base
from app.models import Channel, Tip, TipReceipt
from app.tip_scorecard import TipFact, compute_metrics
from app.tip_scorecard_query import SCOPE_ALL, SCOPE_CHANNEL, SCOPE_CUSTOMER, scorecard
from app.tip_vocabulary import (
    CHANNEL_BROKER_APP, KIND_TIP, MEDIUM_APP_NOTIFICATION, OUTCOME_FAILURE, OUTCOME_SUCCESS, REASON_NO_DIRECTION,
    REASON_UNRESOLVED_SYMBOL, STATUS_DATA_UNRESOLVED, STATUS_PENDING_CHECK, STATUS_STOP_LOSS_HIT, STATUS_TARGET_HIT,
    STATUS_UNSCORABLE,
)
from tests._intake_review_helpers import queue_item

SEEN = datetime(2026, 9, 1, 5, 0, tzinfo=timezone.utc)
AS_OF = datetime(2026, 10, 1, 5, 0, tzinfo=timezone.utc)


@pytest.fixture
def session():
    engine = create_engine("sqlite:///:memory:")
    Base.metadata.create_all(engine)
    db = sessionmaker(bind=engine)()
    try:
        yield db
    finally:
        db.close()


def _completed(db, channel, index, won, holder=None):
    tip = Tip(public_id=str(uuid.uuid4()), source="Upstox", symbol="RENUKA", direction="BUY", received_at=SEEN,
              comparison_status="RECEIVED", source_channel_id=channel.id, match_key=f"c-{index}", first_seen_at=SEEN,
              horizon_sessions=20, status=STATUS_TARGET_HIT if won else STATUS_STOP_LOSS_HIT,
              outcome=OUTCOME_SUCCESS if won else OUTCOME_FAILURE, closed_at=SEEN + timedelta(days=5),
              actual_return=Decimal("0.05") if won else Decimal("-0.03"), promised_return=Decimal("0.08"))
    db.add(tip)
    db.flush()
    if holder:
        db.add(TipReceipt(public_id=str(uuid.uuid4()), tip_id=tip.id, user_id=holder, device_event_key=f"k-{index}",
                          kind=KIND_TIP, medium=MEDIUM_APP_NOTIFICATION, text="", recorded_at=SEEN,
                          parser_version="TCP-001"))
    return tip


def test_queued_calls_count_exactly_as_the_rejected_tips_they_replace(session):
    channel = Channel(name="Upstox", type=CHANNEL_BROKER_APP, default_horizon_sessions=20, capture_enabled=True)
    session.add(channel)
    session.flush()
    channel.canonical_channel_id = channel.id
    tips = [_completed(session, channel, i, i < 7, holder="user-1" if i == 0 else None) for i in range(12)]
    queue_item(session, channel, reason=REASON_NO_DIRECTION, seen=SEEN, holders=("user-1",))
    queue_item(session, channel, reason=REASON_UNRESOLVED_SYMBOL, rejected_status=STATUS_DATA_UNRESOLVED, seen=SEEN)
    queue_item(session, channel, state=STATUS_PENDING_CHECK, seen=SEEN)
    session.commit()

    def fact(status, closed_at, tip_id):
        return TipFact(tip_id=tip_id, status=status, outcome=None, closed_at=closed_at, closed_session=None,
                       promised_return=None, actual_return=None, channel_id=channel.id, horizon_sessions=20,
                       symbol="RENUKA")
    old_world = [TipFact(tip_id=t.id, status=t.status, outcome=t.outcome, closed_at=t.closed_at, closed_session=None,
                         promised_return=t.promised_return, actual_return=t.actual_return, channel_id=channel.id,
                         horizon_sessions=20, symbol="RENUKA") for t in tips]
    old_world += [fact(STATUS_UNSCORABLE, SEEN, -1), fact(STATUS_DATA_UNRESOLVED, SEEN, -2),
                  fact(STATUS_PENDING_CHECK, None, -3)]
    expected = compute_metrics(old_world, as_of=AS_OF)

    actual = scorecard(session, scope=SCOPE_CHANNEL, scope_id=channel.id, as_of=AS_OF)[1].metrics
    assert actual == expected
    assert (actual.unscorable, actual.data_unresolved, actual.open, actual.total) == (1, 1, 1, 15)
    assert scorecard(session, scope=SCOPE_ALL, as_of=AS_OF)[1].metrics == expected
    customer = scorecard(session, scope=SCOPE_CUSTOMER, scope_id="user-1", as_of=AS_OF)[1].metrics
    assert (customer.total, customer.unscorable) == (2, 1)


def test_a_promoted_item_is_counted_once_as_its_tip(session):
    channel = Channel(name="Upstox", type=CHANNEL_BROKER_APP, default_horizon_sessions=20, capture_enabled=True)
    session.add(channel)
    session.flush()
    channel.canonical_channel_id = channel.id
    item = queue_item(session, channel, state="PROMOTED", seen=SEEN)
    item.promoted_tip_id = _completed(session, channel, 99, True).id
    session.commit()
    assert scorecard(session, scope=SCOPE_CHANNEL, scope_id=channel.id, as_of=AS_OF)[1].metrics.total == 1
```

- [ ] **Step 2: Run red**

Run: `python -m pytest tests/test_tip_intake_review_scorecards.py -q -p no:cacheprovider`
Expected: assertion failure `unscorable` 0 != 1, because the loader does not see the queue yet.

- [ ] **Step 3: Implement the union (`app/tip_scorecard_query.py`)**

```python
def _fact_source():
    """Tips plus the intake-review items §8 still counts (intake review spec §7), as one row shape."""
    review = TipIntakeReview
    tips = select(
        Tip.id.label("tip_id"), cast(null(), Integer).label("review_id"), Tip.source_channel_id, Tip.caller_id,
        Tip.status, Tip.outcome, Tip.closed_at, Tip.closed_session, Tip.promised_return, Tip.actual_return,
        Tip.horizon_sessions, Tip.symbol, Tip.match_key, Tip.first_seen_at,
    )
    reviews = select(
        cast(null(), Integer).label("tip_id"), review.id.label("review_id"), review.source_channel_id,
        review.caller_id,
        case((review.state == STATUS_PENDING_CHECK, STATUS_PENDING_CHECK), else_=review.rejected_status).label("status"),
        cast(null(), String(16)).label("outcome"), review.closed_at, cast(null(), Integer).label("closed_session"),
        cast(null(), Numeric(12, 6)).label("promised_return"), cast(null(), Numeric(12, 6)).label("actual_return"),
        review.horizon_sessions, review.symbol, review.match_key, review.scoring_start_at.label("first_seen_at"),
    ).where(review.state.in_((STATUS_PENDING_CHECK, REVIEW_REJECTED, REVIEW_DISMISSED)))
    return union_all(tips, reviews).subquery("scorecard_facts")
```

Then thread `facts = _fact_source()` through every public function. In `_conditions`, `_scope_conditions`, `_facts` and `headlines`, replace each `Tip.<column>` with `facts.c.<column>`. Change `_facts` to `select(facts.c.tip_id, facts.c.review_id, …).where(*conditions)`, and build each `TipFact` with `tip_id=row.tip_id if row.tip_id is not None else -row.review_id`. The customer scope in `_scope_conditions` becomes:

```python
        held_tips = select(TipReceipt.tip_id).where(TipReceipt.user_id == scope_id, TipReceipt.tip_id.is_not(None))
        held_reviews = select(TipReceipt.intake_review_id).where(TipReceipt.user_id == scope_id,
                                                                 TipReceipt.intake_review_id.is_not(None))
        return [or_(facts.c.tip_id.in_(held_tips), facts.c.review_id.in_(held_reviews))], scope_id
```

`return_series` (494+) stays on `Tip`, because queue items have no scored outcome. Import `TipIntakeReview`, `case, cast, null, union_all, Integer, Numeric, String`, and `REVIEW_DISMISSED, REVIEW_REJECTED, STATUS_PENDING_CHECK`.

- [ ] **Step 4: Run green, plus every scorecard suite**

Run: `python -m pytest tests/test_tip_intake_review_scorecards.py tests/test_tip_scorecard.py tests/test_tip_scorecard_query.py tests/test_api_scorecards*.py tests/test_api_ledger_tips*.py -q -p no:cacheprovider`
Expected: all pass. `tests/test_tip_scorecard.py` (the pure golden trust and percentage values) must pass untouched.

- [ ] **Step 5: Commit**

```bash
git add app/tip_scorecard_query.py tests/test_tip_intake_review_scorecards.py
git commit -m "Scorecards read queued calls exactly as the rejected tips they replace

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 8: Migration 0193 (upgrade, abort paths, exact downgrade)

**Files:**
- Create: `migrations/versions/0193_tip_intake_reviews.py`, `tests/_tip_0193_pg.py`, `tests/test_tip_intake_review_migration.py`

**Interfaces:**
- Consumes: the 0192 schema; the Task 2 model shapes, which must match the DDL exactly.
- Produces: revision `0193_tip_intake_reviews` (down `0192_tip_entry_check`). Test helpers: `scratch_db()` (context manager yielding a URL; skips without PostgreSQL), `alembic(target, step)`, `seed_0192(url) -> dict[str, int]` (public_id → tips.id), `row_digests(url) -> dict[int, str]`.

- [ ] **Step 1: Start a throwaway PostgreSQL**

```bash
docker run -d --name marksy-pg-0193-test -e POSTGRES_PASSWORD=postgres -p 55441:5432 postgres:${PG_MAJOR:-16}
for i in $(seq 1 300); do docker exec marksy-pg-0193-test pg_isready -U postgres -q && break; done
export PG_TEST_URL="postgresql+psycopg://postgres:postgres@localhost:55441/postgres"
```

`PG_MAJOR` becomes the production major from the G3 survey before G5. Re-run these tests on that major then.

- [ ] **Step 2: Write the PG helpers (`tests/_tip_0193_pg.py`)**

```python
"""PostgreSQL scratch databases for migration 0193 (its SQL uses to_jsonb, md5 and ON CONFLICT); skipped without one,
as tests/test_fresh_database_migration.py is."""

from __future__ import annotations

import contextlib
import re
import uuid
from datetime import datetime, timezone
from pathlib import Path

import psycopg
import pytest
from alembic import command
from alembic.config import Config
from sqlalchemy import create_engine, text

from app.settings import settings

ALEMBIC_INI = Path(__file__).resolve().parent.parent / "alembic.ini"
SEEN = datetime(2026, 9, 28, 5, 0, tzinfo=timezone.utc)
ROWS = [  # public_id, status, reason, receipts
    ("ok-1", "ACTIVE", None, 1),
    ("miss-1", "DATA_UNRESOLVED", "MISSING_BARS", 0),
    ("rej-1", "UNSCORABLE", "NO_DIRECTION", 2),
    ("rej-2", "DATA_UNRESOLVED", "UNRESOLVED_SYMBOL", 0),
    ("off-1", "UNSCORABLE", "ENTRY_OFF_MARKET", 1),
    ("pend-1", "PENDING_CHECK", None, 2),
]
MOVED = {"rej-1", "rej-2", "off-1", "pend-1"}


@contextlib.contextmanager
def scratch_db():
    admin = settings.database_url.replace("postgresql+psycopg://", "postgresql://")
    if not admin.startswith("postgresql://"):
        pytest.skip("migration 0193 tests need DATABASE_URL on PostgreSQL")
    try:
        psycopg.connect(admin, autocommit=True, connect_timeout=3).close()
    except Exception:
        pytest.skip("PostgreSQL is not reachable")
    name = f"tip_0193_{uuid.uuid4().hex[:8]}"
    with psycopg.connect(admin, autocommit=True) as conn:
        conn.execute(f"CREATE DATABASE {name}")
    original, url = settings.database_url, re.sub(r"/[^/]+$", f"/{name}", settings.database_url)
    settings.database_url = url
    try:
        yield url
    finally:
        settings.database_url = original
        with psycopg.connect(admin, autocommit=True) as conn:
            conn.execute(f"DROP DATABASE IF EXISTS {name} WITH (FORCE)")


def alembic(target: str, step: str = "upgrade") -> None:
    getattr(command, step)(Config(str(ALEMBIC_INI)), target)


def seed_0192(url: str) -> dict[str, int]:
    engine = create_engine(url)
    ids = {}
    with engine.begin() as conn:
        channel = conn.execute(text("INSERT INTO channels (name, type, default_horizon_sessions, capture_enabled) "
                                    "VALUES ('Upstox', 'BROKER_APP', 20, true) RETURNING id")).scalar_one()
        conn.execute(text("UPDATE channels SET canonical_channel_id = id WHERE id = :id"), {"id": channel})
        for public_id, status, reason, receipts in ROWS:
            closed = None if status in ("ACTIVE", "PENDING_CHECK") else SEEN
            ids[public_id] = conn.execute(text(
                "INSERT INTO tips (public_id, source, symbol, direction, entry_low, entry_high, entry_price, "
                "target_price, stop_loss, horizon_sessions, horizon_basis, received_at, comparison_status, "
                "source_channel_id, match_key, match_key_version, first_seen_at, status, reason, closed_at, entry_basis) "
                "VALUES (:p, 'Upstox', 'RENUKA', 'BUY', 23.62, 23.62, 23.62, 26, 22.25, 20, 'CHANNEL_DEFAULT', :seen, "
                "'RECEIVED', :c, :k, 1, :seen, :s, :r, :closed, 'STATED') RETURNING id"),
                {"p": public_id, "seen": SEEN, "c": channel, "k": f"key-{public_id}", "s": status, "r": reason,
                 "closed": closed}).scalar_one()
            for n in range(receipts):
                conn.execute(text(
                    "INSERT INTO tip_receipts (public_id, tip_id, user_id, device_event_key, kind, medium, text, "
                    "recorded_at, parser_version) VALUES (:p, :t, :u, :k, 'TIP', 'APP_NOTIFICATION', '', :seen, 'TCP-001')"),
                    {"p": str(uuid.uuid4()), "t": ids[public_id], "u": f"user-{n}", "k": f"{public_id}-{n}", "seen": SEEN})
        conn.execute(text(
            "INSERT INTO external_tip_comparisons (tip_id, compared_at, marksy_source, verdict, comparison_rule_version) "
            "VALUES (:t, :seen, 'NONE', 'NO_MARKSY_VIEW', 'v1')"), {"t": ids["rej-1"], "seen": SEEN})
    engine.dispose()
    return ids


def row_digests(url: str) -> dict[int, str]:
    engine = create_engine(url)
    with engine.connect() as conn:
        rows = conn.execute(text("SELECT t.id, md5(to_jsonb(t)::text) FROM tips t ORDER BY t.id")).all()
    engine.dispose()
    return dict(rows)
```

- [ ] **Step 3: Write the failing tests (`tests/test_tip_intake_review_migration.py`)**

```python
"""Migration 0193 on PostgreSQL: the move is verbatim, aborts leave nothing behind, downgrade is exact."""

from __future__ import annotations

from decimal import Decimal

import pytest
from sqlalchemy import create_engine, text
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session

from app.models import TipIntakeReview
from app.tip_intake_review import tip_from_payload
from tests._tip_0193_pg import MOVED, ROWS, SEEN, alembic, row_digests, scratch_db, seed_0192

pytestmark = pytest.mark.filterwarnings("ignore::DeprecationWarning")


def _scalar(url, sql, **params):
    engine = create_engine(url)
    with engine.connect() as conn:
        value = conn.execute(text(sql), params).scalar()
    engine.dispose()
    return value


def test_upgrade_moves_exactly_the_unvalidated_rows_verbatim():
    with scratch_db() as url:
        alembic("0192_tip_entry_check")
        ids = seed_0192(url)
        before = row_digests(url)
        alembic("0193_tip_intake_reviews")
        assert _scalar(url, "SELECT string_agg(public_id, ',' ORDER BY public_id) FROM tips") == "miss-1,ok-1"
        for public_id in MOVED:
            digest = _scalar(url, "SELECT md5((payload::jsonb - 'legacy_rows')::text) FROM tip_intake_reviews "
                                  "WHERE origin_tip_id = :id", id=ids[public_id])
            assert digest == before[ids[public_id]]
        assert _scalar(url, "SELECT count(*) FROM tip_receipts WHERE intake_review_id IS NOT NULL AND tip_id IS NULL") == 5
        assert _scalar(url, "SELECT count(*) FROM external_tip_comparisons") == 0
        assert _scalar(url, "SELECT jsonb_array_length(payload::jsonb->'legacy_rows'->'external_tip_comparisons') "
                            "FROM tip_intake_reviews WHERE origin_tip_id = :id", id=ids["rej-1"]) == 1
        assert _scalar(url, "SELECT count(*) FROM tip_intake_review_events WHERE action = 'MIGRATED'") == 4
        with pytest.raises(IntegrityError):
            _scalar(url, "INSERT INTO tips (public_id, source, symbol, received_at, comparison_status, status) "
                         "VALUES ('x', 'Upstox', 'RENUKA', now(), 'RECEIVED', 'PENDING_CHECK') RETURNING id")


@pytest.mark.parametrize("reference", [
    "INSERT INTO tip_daily_progress (tip_id, session_date, session_index, entry_status, status_after, bar_basis, "
    "data_basis, recorded_at) VALUES (:t, '2026-09-28', 1, 'WAITING', 'ACTIVE', 'DAILY', 'FINAL', now())",
    "INSERT INTO tip_source_exits (tip_id, exit_seen_at) VALUES (:t, now())",
])
def test_an_unhandled_reference_aborts_and_leaves_nothing_behind(reference):
    with scratch_db() as url:
        alembic("0192_tip_entry_check")
        ids = seed_0192(url)
        engine = create_engine(url)
        with engine.begin() as conn:
            conn.execute(text(reference), {"t": ids["rej-1"]})
        engine.dispose()
        before = row_digests(url)
        with pytest.raises(Exception, match="0193 aborted"):
            alembic("0193_tip_intake_reviews")
        assert _scalar(url, "SELECT version_num FROM alembic_version") == "0192_tip_entry_check"
        assert row_digests(url) == before
        assert _scalar(url, "SELECT to_regclass('tip_intake_reviews')") is None


def test_downgrade_restores_ids_rows_links_and_legacy_rows():
    with scratch_db() as url:
        alembic("0192_tip_entry_check")
        seed_0192(url)
        before = row_digests(url)
        receipts = _scalar(url, "SELECT md5(string_agg(id || ':' || tip_id, ',' ORDER BY id)) FROM tip_receipts")
        alembic("0193_tip_intake_reviews")
        alembic("0192_tip_entry_check", "downgrade")
        assert row_digests(url) == before
        assert _scalar(url, "SELECT md5(string_agg(id || ':' || tip_id, ',' ORDER BY id)) FROM tip_receipts") == receipts
        assert _scalar(url, "SELECT count(*) FROM external_tip_comparisons") == 1
        alembic("0193_tip_intake_reviews")  # and forward again
        assert _scalar(url, "SELECT count(*) FROM tip_intake_reviews") == len(MOVED)


def test_downgrade_refuses_once_post_0193_data_exists():
    with scratch_db() as url:
        alembic("0192_tip_entry_check")
        seed_0192(url)
        alembic("0193_tip_intake_reviews")
        engine = create_engine(url)
        with engine.begin() as conn:
            conn.execute(text("INSERT INTO tip_intake_review_events (review_id, at, actor, action) "
                              "SELECT id, now(), 'admin:a', 'DISMISSED' FROM tip_intake_reviews LIMIT 1"))
        engine.dispose()
        with pytest.raises(Exception, match="0193 downgrade refused"):
            alembic("0192_tip_entry_check", "downgrade")
        assert _scalar(url, "SELECT version_num FROM alembic_version") == "0193_tip_intake_reviews"


def test_scorecard_counts_are_unchanged_by_the_move():
    from app.tip_scorecard_query import SCOPE_CHANNEL, scorecard
    as_of = SEEN.replace(year=2026, month=10, day=10)
    with scratch_db() as url:
        alembic("0192_tip_entry_check")
        seed_0192(url)
        old = dict(create_engine(url).connect().execute(text(
            "SELECT status, count(*) FROM tips WHERE match_key IS NOT NULL AND status IS NOT NULL GROUP BY status")).all())
        channel_id = _scalar(url, "SELECT id FROM channels WHERE name = 'Upstox'")
        alembic("0193_tip_intake_reviews")
        with Session(create_engine(url)) as session:
            metrics = scorecard(session, scope=SCOPE_CHANNEL, scope_id=channel_id, as_of=as_of)[1].metrics
        assert (metrics.unscorable, metrics.data_unresolved, metrics.total) == (
            old["UNSCORABLE"], old["DATA_UNRESOLVED"], sum(old.values()))


def test_a_migrated_pending_payload_promotes_with_exact_decimals():
    with scratch_db() as url:
        alembic("0192_tip_entry_check")
        seed_0192(url)
        alembic("0193_tip_intake_reviews")
        with Session(create_engine(url)) as session:
            item = session.query(TipIntakeReview).filter_by(public_id="pend-1").one()
            tip = tip_from_payload(item)
        assert (tip.entry_low, tip.target_price, tip.public_id, tip.first_seen_at) == (
            Decimal("23.62"), Decimal("26"), "pend-1", SEEN)
```

- [ ] **Step 4: Run red**

Run: `DATABASE_URL="$PG_TEST_URL" python -m pytest tests/test_tip_intake_review_migration.py -q -p no:cacheprovider`
Expected: `alembic.util.exc.CommandError: Can't locate revision identified by '0193_tip_intake_reviews'`.

- [ ] **Step 5: Write `migrations/versions/0193_tip_intake_reviews.py`**

```python
"""Intake review queue (intake review spec §5, §9): calls that have not passed intake leave `tips`.

Upgrade moves every PENDING_CHECK, UNSCORABLE or intake-rejected tips row, verbatim, into `tip_intake_reviews` (receipts
relinked, retired EPIC-803 rows copied into the payload), aborts on any reference it cannot carry, then adds
ck_tips_validated_only. Downgrade restores the moved rows exactly and refuses once post-0193 data exists.

Revision ID: 0193_tip_intake_reviews
Revises: 0192_tip_entry_check
"""
from __future__ import annotations

import sqlalchemy as sa
from alembic import op

revision = "0193_tip_intake_reviews"
down_revision = "0192_tip_entry_check"
branch_labels = None
depends_on = None

_INTAKE = "('NO_DIRECTION', 'INCONSISTENT_LEVELS', 'UNRESOLVED_SYMBOL', 'ENTRY_OFF_MARKET')"
_MOVE = f"(status IN ('PENDING_CHECK', 'UNSCORABLE') OR reason IN {_INTAKE})"
_MOVED = f"SELECT id FROM tips WHERE {_MOVE}"
_CHECK = ("status <> 'PENDING_CHECK' AND status <> 'UNSCORABLE' AND COALESCE(reason, '') NOT IN ('NO_DIRECTION', "
          "'INCONSISTENT_LEVELS', 'UNRESOLVED_SYMBOL', 'ENTRY_OFF_MARKET', 'CLAIMED_TIME_OLDER', "
          "'CLAIMED_TIME_INVALID', 'CLAIMED_TIME_UNVERIFIED')")
_BIG = sa.BigInteger().with_variant(sa.Integer(), "sqlite")
_PRICE = sa.Numeric(18, 6)
_TZ = sa.DateTime(timezone=True)

_STRAY = {
    "an unexpected status": f"SELECT id FROM tips WHERE {_MOVE} AND (status IS NULL OR status NOT IN "
                            "('PENDING_CHECK', 'UNSCORABLE', 'DATA_UNRESOLVED'))",
    "a match key but no first_seen_at": f"SELECT id FROM tips WHERE {_MOVE} AND match_key IS NOT NULL "
                                        "AND first_seen_at IS NULL",
}
_UNHANDLED = {
    "tip_daily_progress": f"SELECT tip_id FROM tip_daily_progress WHERE tip_id IN ({_MOVED})",
    "tip_source_exits": f"SELECT tip_id FROM tip_source_exits WHERE tip_id IN ({_MOVED})",
    "stock_ratings": f"SELECT id FROM stock_ratings WHERE short_tip_id IN ({_MOVED}) OR long_tip_id IN ({_MOVED})",
    "tips.revises_tip_id": f"SELECT id FROM tips WHERE revises_tip_id IN ({_MOVED}) AND id NOT IN ({_MOVED})",
    "tips.merged_into_tip_id": f"SELECT id FROM tips WHERE merged_into_tip_id IN ({_MOVED}) AND id NOT IN ({_MOVED})",
    "recommendation_alerts": "SELECT id FROM recommendation_alerts WHERE source_table = 'tips' "
                             f"AND source_id IN ({_MOVED})",
}
_LOSSY = {
    "intake-origin items": "SELECT id FROM tip_intake_reviews WHERE origin <> 'MIGRATED_0193'",
    "post-0193 events": "SELECT id FROM tip_intake_review_events WHERE action <> 'MIGRATED'",
    "tips with a claimed time or 0193 basis": "SELECT id FROM tips WHERE claimed_created_at IS NOT NULL "
        "OR intake_review_id IS NOT NULL OR (scoring_start_basis IS NOT NULL AND scoring_start_basis <> 'SERVER_RECEIPT')",
}
_INSERT_ITEMS = f"""
INSERT INTO tip_intake_reviews (
    public_id, state, reason, rejected_status, origin, origin_tip_id, source_channel_id, caller_id, prediction_id,
    stock_id, symbol, direction, entry_low, entry_high, target, stop_loss, horizon_sessions, horizon_basis, match_key,
    match_key_version, parser_version, source, client_name, source_reference, received_at, scoring_start_at,
    scoring_start_basis, first_seen_price, entry_check_price, entry_check_basis, closed_at, payload, created_at,
    updated_at)
SELECT t.public_id,
       CASE WHEN t.status = 'PENDING_CHECK' THEN 'PENDING_CHECK' ELSE 'REJECTED' END,
       t.reason,
       CASE WHEN t.status = 'PENDING_CHECK' THEN NULL ELSE t.status END,
       'MIGRATED_0193', t.id, t.source_channel_id, t.caller_id, t.prediction_id, t.stock_id, t.symbol, t.direction,
       t.entry_low, t.entry_high, t.target_price, t.stop_loss, t.horizon_sessions, t.horizon_basis, t.match_key,
       t.match_key_version, t.parser_version, t.source, t.client_name, t.source_reference, t.received_at,
       COALESCE(t.first_seen_at, t.received_at), 'LEGACY', t.first_seen_price, t.entry_check_price,
       t.entry_check_basis, CASE WHEN t.status = 'PENDING_CHECK' THEN NULL ELSE t.closed_at END,
       (to_jsonb(t) || jsonb_build_object('legacy_rows', jsonb_build_object(
           'external_tip_comparisons', COALESCE((SELECT jsonb_agg(to_jsonb(c) ORDER BY c.id)
                                                 FROM external_tip_comparisons c WHERE c.tip_id = t.id), '[]'::jsonb),
           'external_tip_outcomes', COALESCE((SELECT jsonb_agg(to_jsonb(o) ORDER BY o.id)
                                              FROM external_tip_outcomes o WHERE o.tip_id = t.id), '[]'::jsonb)
       )))::json,
       now(), now()
FROM tips t
WHERE t.status IN ('PENDING_CHECK', 'UNSCORABLE') OR t.reason IN {_INTAKE}
ORDER BY t.id
ON CONFLICT (origin_tip_id) DO NOTHING
"""
_INSERT_EVENTS = """
INSERT INTO tip_intake_review_events (review_id, at, actor, action, before, after, note)
SELECT r.id, now(), 'migration:0193', 'MIGRATED', NULL,
       json_build_object('tipId', r.origin_tip_id, 'status', r.payload->>'status', 'reason', r.reason), NULL
FROM tip_intake_reviews r
WHERE r.origin = 'MIGRATED_0193'
  AND NOT EXISTS (SELECT 1 FROM tip_intake_review_events e WHERE e.review_id = r.id AND e.action = 'MIGRATED')
"""
_PAYLOAD_DIGEST = ("SELECT md5(string_agg((payload::jsonb - 'legacy_rows')::text, '|' ORDER BY origin_tip_id)) "
                   "FROM tip_intake_reviews WHERE origin = 'MIGRATED_0193'")


def _ids(bind, sql: str) -> list:
    return [row[0] for row in bind.execute(sa.text(f"{sql} LIMIT 50"))]


def _scalar(bind, sql: str):
    return bind.execute(sa.text(sql)).scalar()


def _create_queue_tables() -> None:
    op.create_table(
        "tip_intake_reviews",
        sa.Column("id", _BIG, primary_key=True),
        sa.Column("public_id", sa.String(36), nullable=False),
        sa.Column("state", sa.String(16), nullable=False),
        sa.Column("reason", sa.String(32)),
        sa.Column("rejected_status", sa.String(24)),
        sa.Column("origin", sa.String(16), nullable=False),
        sa.Column("origin_tip_id", _BIG, unique=True),
        sa.Column("source_channel_id", sa.Integer(), sa.ForeignKey("channels.id")),
        sa.Column("caller_id", sa.Integer(), sa.ForeignKey("callers.id")),
        sa.Column("prediction_id", sa.Integer(), sa.ForeignKey("predictions.id")),
        sa.Column("stock_id", sa.Integer(), sa.ForeignKey("stocks.id")),
        sa.Column("symbol", sa.String(32), nullable=False),
        sa.Column("direction", sa.String(8)),
        sa.Column("entry_low", _PRICE),
        sa.Column("entry_high", _PRICE),
        sa.Column("target", _PRICE),
        sa.Column("stop_loss", _PRICE),
        sa.Column("horizon_sessions", sa.Integer()),
        sa.Column("horizon_basis", sa.String(16)),
        sa.Column("match_key", sa.String(64)),
        sa.Column("match_key_version", sa.Integer()),
        sa.Column("parser_version", sa.String(32)),
        sa.Column("source", sa.String(64), nullable=False),
        sa.Column("client_name", sa.String(64)),
        sa.Column("source_reference", sa.String(128)),
        sa.Column("received_at", _TZ, nullable=False),
        sa.Column("claimed_created_at", _TZ),
        sa.Column("claimed_time_source", sa.String(24)),
        sa.Column("scoring_start_at", _TZ, nullable=False),
        sa.Column("scoring_start_basis", sa.String(32), nullable=False),
        sa.Column("first_seen_price", _PRICE),
        sa.Column("entry_check_price", _PRICE),
        sa.Column("entry_check_basis", sa.String(24)),
        sa.Column("check_evidence", sa.JSON()),
        sa.Column("closed_at", _TZ),
        sa.Column("payload", sa.JSON(), nullable=False),
        sa.Column("promoted_tip_id", _BIG, sa.ForeignKey("tips.id"), unique=True),
        sa.Column("decided_at", _TZ),
        sa.Column("decided_by", sa.String(160)),
        sa.Column("created_at", _TZ, server_default=sa.func.now(), nullable=False),
        sa.Column("updated_at", _TZ, server_default=sa.func.now(), nullable=False),
        sa.UniqueConstraint("source", "source_reference", name="uq_tip_intake_review_source_reference"),
    )
    op.create_index("ix_tip_intake_reviews_public_id", "tip_intake_reviews", ["public_id"], unique=True)
    op.create_index("ix_tip_intake_reviews_match_key", "tip_intake_reviews", ["match_key"])
    op.create_index("ix_tip_intake_reviews_state", "tip_intake_reviews", ["state"])
    op.create_index("uq_tip_intake_reviews_prediction_id", "tip_intake_reviews", ["prediction_id"], unique=True)
    op.create_table(
        "tip_intake_review_events",
        sa.Column("id", _BIG, primary_key=True),
        sa.Column("review_id", _BIG, sa.ForeignKey("tip_intake_reviews.id"), nullable=False),
        sa.Column("at", _TZ, nullable=False),
        sa.Column("actor", sa.String(160), nullable=False),
        sa.Column("action", sa.String(24), nullable=False),
        sa.Column("before", sa.JSON()),
        sa.Column("after", sa.JSON()),
        sa.Column("note", sa.Text()),
    )
    op.create_index("ix_tip_intake_review_events_review_id", "tip_intake_review_events", ["review_id"])
    op.add_column("tip_receipts", sa.Column("intake_review_id", _BIG, sa.ForeignKey(
        "tip_intake_reviews.id", name="fk_tip_receipts_intake_review_id")))
    op.create_index("ix_tip_receipts_intake_review_id", "tip_receipts", ["intake_review_id"])
    op.create_index("uq_tip_receipt_review_user_medium", "tip_receipts", ["intake_review_id", "user_id", "medium"],
                    unique=True, postgresql_where=sa.text("kind = 'TIP'"), sqlite_where=sa.text("kind = 'TIP'"))


def _move_unvalidated_rows(bind) -> None:
    for name, sql in {**_STRAY, **{f"{k} references moved tips": v for k, v in _UNHANDLED.items()}}.items():
        ids = _ids(bind, sql)
        if ids:
            raise RuntimeError(f"0193 aborted: {name}: {ids}")
    before = _scalar(bind, f"SELECT md5(string_agg(to_jsonb(t)::text, '|' ORDER BY t.id)) FROM tips t WHERE {_MOVE}")
    receipts = _scalar(bind, f"SELECT count(*) FROM tip_receipts WHERE tip_id IN ({_MOVED})")
    bind.execute(sa.text(_INSERT_ITEMS))
    bind.execute(sa.text(_INSERT_EVENTS))
    relinked = bind.execute(sa.text(
        "UPDATE tip_receipts r SET intake_review_id = i.id, tip_id = NULL FROM tip_intake_reviews i "
        f"WHERE i.origin_tip_id = r.tip_id AND r.tip_id IN ({_MOVED})")).rowcount
    if relinked != receipts:
        raise RuntimeError(f"0193 aborted: relinked {relinked} receipts, expected {receipts}")
    after = _scalar(bind, f"{_PAYLOAD_DIGEST} AND origin_tip_id IN ({_MOVED})")
    if before != after:
        raise RuntimeError(f"0193 aborted: payload checksum {after} differs from the rows' {before}")
    for table in ("external_tip_comparisons", "external_tip_outcomes"):
        bind.execute(sa.text(f"DELETE FROM {table} WHERE tip_id IN ({_MOVED})"))
    bind.execute(sa.text(f"DELETE FROM tips WHERE {_MOVE}"))


def _add_tip_columns() -> None:
    op.add_column("tips", sa.Column("claimed_created_at", _TZ))
    op.add_column("tips", sa.Column("claimed_time_source", sa.String(24)))
    op.add_column("tips", sa.Column("scoring_start_basis", sa.String(32)))
    op.add_column("tips", sa.Column("intake_review_id", _BIG))
    op.create_index("ix_tips_intake_review_id", "tips", ["intake_review_id"])


def upgrade() -> None:
    bind = op.get_bind()
    postgres = bind.dialect.name == "postgresql"
    _create_queue_tables()
    if postgres:
        _move_unvalidated_rows(bind)  # plan decision D5: before the new columns, so each payload is the row as it was
    _add_tip_columns()
    if postgres:
        violating = _ids(bind, f"SELECT id FROM tips WHERE NOT ({_CHECK})")
        if violating:
            raise RuntimeError(f"0193 aborted: rows still violate ck_tips_validated_only: {violating}")
    with op.batch_alter_table("tips") as batch:
        batch.create_check_constraint("ck_tips_validated_only", _CHECK)


def downgrade() -> None:
    bind = op.get_bind()
    postgres = bind.dialect.name == "postgresql"
    expected = None
    if postgres:
        for name, sql in _LOSSY.items():
            ids = _ids(bind, sql)
            if ids:
                raise RuntimeError(f"0193 downgrade refused: {name} {ids} have no place in the 0192 schema; "
                                   "roll forward or restore the G4 backup")
        expected = _scalar(bind, _PAYLOAD_DIGEST)
    with op.batch_alter_table("tips") as batch:
        batch.drop_constraint("ck_tips_validated_only", type_="check")
        batch.drop_index("ix_tips_intake_review_id")
        for column in ("intake_review_id", "scoring_start_basis", "claimed_time_source", "claimed_created_at"):
            batch.drop_column(column)
    if postgres:
        bind.execute(sa.text(
            "INSERT INTO tips SELECT (jsonb_populate_record(NULL::tips, r.payload::jsonb - 'legacy_rows')).* "
            "FROM tip_intake_reviews r WHERE r.origin = 'MIGRATED_0193' ORDER BY r.origin_tip_id"))
        bind.execute(sa.text(
            "UPDATE tip_receipts rc SET tip_id = r.origin_tip_id, intake_review_id = NULL FROM tip_intake_reviews r "
            "WHERE rc.intake_review_id = r.id AND r.origin = 'MIGRATED_0193'"))
        for table in ("external_tip_comparisons", "external_tip_outcomes"):
            bind.execute(sa.text(
                f"INSERT INTO {table} SELECT (jsonb_populate_record(NULL::{table}, x.value)).* "
                "FROM tip_intake_reviews r CROSS JOIN LATERAL "
                f"jsonb_array_elements(r.payload::jsonb -> 'legacy_rows' -> '{table}') AS x(value) "
                "WHERE r.origin = 'MIGRATED_0193'"))
        restored = _scalar(bind, "SELECT md5(string_agg(to_jsonb(t)::text, '|' ORDER BY t.id)) FROM tips t "
                                 "WHERE t.id IN (SELECT origin_tip_id FROM tip_intake_reviews WHERE origin = 'MIGRATED_0193')")
        if restored != expected:
            raise RuntimeError(f"0193 downgrade aborted: restored checksum {restored} differs from {expected}")
    with op.batch_alter_table("tip_receipts") as batch:
        batch.drop_index("uq_tip_receipt_review_user_medium")
        batch.drop_index("ix_tip_receipts_intake_review_id")
        batch.drop_column("intake_review_id")
    op.drop_table("tip_intake_review_events")
    op.drop_table("tip_intake_reviews")
```

Check every column type and nullability against `TipIntakeReview` in `app/models.py`. The model's `index=True` / `unique=True` names must match the index names here; adjust the model's `Index(...)` declarations rather than relying on autogenerated names. In particular, `unique=True, index=True` on `public_id` yields `ix_tip_intake_reviews_public_id`.

- [ ] **Step 6: Run green on PostgreSQL, then the fresh-database chain**

```bash
DATABASE_URL="$PG_TEST_URL" python -m pytest tests/test_tip_intake_review_migration.py tests/test_fresh_database_migration.py -q -p no:cacheprovider
```

Expected: all pass. `test_fresh_database_migration` proves 0193 runs on an empty database at head.

- [ ] **Step 7: Commit**

```bash
git add migrations/versions/0193_tip_intake_reviews.py tests/_tip_0193_pg.py tests/test_tip_intake_review_migration.py
git commit -m "Migration 0193: move unvalidated tips into the review queue verbatim; exact downgrade

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 9: Survey and verify scripts

**Files:**
- Create: `scripts/sql/tip_intake_review_survey.sql`, `scripts/tip_intake_review_survey.py`, `scripts/tip_intake_review_verify.py`
- Test: `tests/test_tip_intake_review_scripts.py`

**Interfaces:**
- Consumes: `load_directory`, `scorecard`, `TipFact`, `compute_metrics` (all already on main 027eda3).
- Produces:
  - `snapshot(session, as_of) -> dict` with keys `asOf, scorecards, moved, receipts` plus `maxTipId, maxReceiptId, takenAt` (final review I2)
  - `check(session, pre: dict) -> list[str]`
  - CLI `python scripts/tip_intake_review_verify.py snapshot [--as-of ISO] > pre.json`
  - CLI `python scripts/tip_intake_review_verify.py check --pre pre.json` (exit 1 on any problem; prints later rows under `afterSnapshot`)
  - CLI `python scripts/tip_intake_review_survey.py` (runs the SQL file against `DATABASE_URL` in a read-only transaction)

- [ ] **Step 1: Write the failing tests**

```python
"""Intake review spec §12 G3/G5: the survey is read-only, and the verify script catches a broken move."""

from __future__ import annotations

from datetime import datetime, timezone
from pathlib import Path

import pytest
from sqlalchemy import create_engine, text
from sqlalchemy.orm import Session

from scripts.tip_intake_review_survey import run_survey
from scripts.tip_intake_review_verify import check, snapshot
from tests._tip_0193_pg import alembic, scratch_db, seed_0192

AS_OF = datetime(2026, 10, 10, tzinfo=timezone.utc)


def test_snapshot_then_upgrade_then_check_is_clean_and_tampering_is_caught():
    with scratch_db() as url:
        alembic("0192_tip_entry_check")
        seed_0192(url)
        engine = create_engine(url)
        with Session(engine) as session:
            pre = snapshot(session, AS_OF)
        assert len(pre["moved"]) == 4 and len(pre["receipts"]) == 5
        alembic("0193_tip_intake_reviews")
        with Session(engine) as session:
            assert check(session, pre) == []
        with engine.begin() as conn:
            conn.execute(text("UPDATE tip_intake_reviews SET payload = '{}'::json WHERE origin_tip_id = "
                              "(SELECT min(origin_tip_id) FROM tip_intake_reviews)"))
        with Session(engine) as session:
            problems = check(session, pre)
        assert any("payload checksum" in problem for problem in problems)


def test_the_survey_reads_without_writing():
    with scratch_db() as url:
        alembic("0192_tip_entry_check")
        seed_0192(url)
        sections = run_survey(url)
        assert sections["alembic_version"] == [("0192_tip_entry_check",)]
        assert sections["move_set"] == [("DATA_UNRESOLVED", "UNRESOLVED_SYMBOL", 1), ("PENDING_CHECK", None, 1),
                                        ("UNSCORABLE", "ENTRY_OFF_MARKET", 1), ("UNSCORABLE", "NO_DIRECTION", 1)]
        assert sections["unhandled_references"] == [] and sections["preflight_outside_move_set"] == []
        assert sections["handled_references"][0] == ("tip_receipts", 5)
```

- [ ] **Step 2: Run red**

Run: `DATABASE_URL="$PG_TEST_URL" python -m pytest tests/test_tip_intake_review_scripts.py -q -p no:cacheprovider`
Expected: `ModuleNotFoundError: No module named 'scripts.tip_intake_review_survey'`. If `scripts` is not importable as a package, follow how `tests/test_backfill_tip_ledger_script.py` imports its script.

- [ ] **Step 3: `scripts/sql/tip_intake_review_survey.sql`**

Pure SQL, one statement per section. Each statement's first column is the section name, and the file contains no semicolons except statement ends.

```sql
-- Intake review spec §12 G3: read-only survey before migration 0193. Expectations are in the plan's G3 table.
BEGIN TRANSACTION READ ONLY;
SELECT 'server_version' AS section, current_setting('server_version') AS value;
SELECT 'alembic_version' AS section, version_num FROM alembic_version;
SELECT 'move_set' AS section, status, reason, count(*) FROM tips
 WHERE status IN ('PENDING_CHECK', 'UNSCORABLE') OR reason IN ('NO_DIRECTION', 'INCONSISTENT_LEVELS', 'UNRESOLVED_SYMBOL', 'ENTRY_OFF_MARKET')
 GROUP BY status, reason ORDER BY status, reason;
SELECT 'move_set_stray' AS section, id, status, reason, match_key IS NOT NULL AND first_seen_at IS NULL AS unseen FROM tips
 WHERE (status IN ('PENDING_CHECK', 'UNSCORABLE') OR reason IN ('NO_DIRECTION', 'INCONSISTENT_LEVELS', 'UNRESOLVED_SYMBOL', 'ENTRY_OFF_MARKET'))
   AND (status IS NULL OR status NOT IN ('PENDING_CHECK', 'UNSCORABLE', 'DATA_UNRESOLVED') OR (match_key IS NOT NULL AND first_seen_at IS NULL));
WITH m AS (SELECT id FROM tips WHERE status IN ('PENDING_CHECK', 'UNSCORABLE') OR reason IN ('NO_DIRECTION', 'INCONSISTENT_LEVELS', 'UNRESOLVED_SYMBOL', 'ENTRY_OFF_MARKET'))
SELECT 'unhandled_references' AS section, ref, id FROM (
  SELECT 'tip_daily_progress' AS ref, tip_id AS id FROM tip_daily_progress WHERE tip_id IN (SELECT id FROM m)
  UNION ALL SELECT 'tip_source_exits', tip_id FROM tip_source_exits WHERE tip_id IN (SELECT id FROM m)
  UNION ALL SELECT 'stock_ratings', id FROM stock_ratings WHERE short_tip_id IN (SELECT id FROM m) OR long_tip_id IN (SELECT id FROM m)
  UNION ALL SELECT 'tips.revises_tip_id', id FROM tips WHERE revises_tip_id IN (SELECT id FROM m) AND id NOT IN (SELECT id FROM m)
  UNION ALL SELECT 'tips.merged_into_tip_id', id FROM tips WHERE merged_into_tip_id IN (SELECT id FROM m) AND id NOT IN (SELECT id FROM m)
  UNION ALL SELECT 'recommendation_alerts', id FROM recommendation_alerts WHERE source_table = 'tips' AND source_id IN (SELECT id FROM m)
) refs LIMIT 200;
WITH m AS (SELECT id FROM tips WHERE status IN ('PENDING_CHECK', 'UNSCORABLE') OR reason IN ('NO_DIRECTION', 'INCONSISTENT_LEVELS', 'UNRESOLVED_SYMBOL', 'ENTRY_OFF_MARKET'))
SELECT 'handled_references' AS section, 'tip_receipts' AS ref, count(*) FROM tip_receipts WHERE tip_id IN (SELECT id FROM m)
UNION ALL SELECT 'handled_references', 'external_tip_comparisons', count(*) FROM external_tip_comparisons WHERE tip_id IN (SELECT id FROM m)
UNION ALL SELECT 'handled_references', 'external_tip_outcomes', count(*) FROM external_tip_outcomes WHERE tip_id IN (SELECT id FROM m);
SELECT 'null_status_rows' AS section, count(*) AS rows, count(*) FILTER (WHERE reason IS NOT NULL) AS with_reason FROM tips WHERE status IS NULL;
SELECT 'preflight_outside_move_set' AS section, id, status, reason FROM tips
 WHERE NOT (status IN ('PENDING_CHECK', 'UNSCORABLE') OR reason IN ('NO_DIRECTION', 'INCONSISTENT_LEVELS', 'UNRESOLVED_SYMBOL', 'ENTRY_OFF_MARKET'))
   AND NOT (status <> 'PENDING_CHECK' AND status <> 'UNSCORABLE' AND COALESCE(reason, '') NOT IN ('NO_DIRECTION', 'INCONSISTENT_LEVELS', 'UNRESOLVED_SYMBOL', 'ENTRY_OFF_MARKET', 'CLAIMED_TIME_OLDER', 'CLAIMED_TIME_INVALID', 'CLAIMED_TIME_UNVERIFIED'))
 LIMIT 50;
SELECT 'move_set_checksum' AS section, count(*), md5(string_agg(to_jsonb(t)::text, '|' ORDER BY t.id)) FROM tips t
 WHERE t.status IN ('PENDING_CHECK', 'UNSCORABLE') OR t.reason IN ('NO_DIRECTION', 'INCONSISTENT_LEVELS', 'UNRESOLVED_SYMBOL', 'ENTRY_OFF_MARKET');
SELECT 'receipt_link_checksum' AS section, count(*), md5(string_agg(r.id || ':' || r.tip_id, ',' ORDER BY r.id)) FROM tip_receipts r
 WHERE r.tip_id IN (SELECT id FROM tips WHERE status IN ('PENDING_CHECK', 'UNSCORABLE') OR reason IN ('NO_DIRECTION', 'INCONSISTENT_LEVELS', 'UNRESOLVED_SYMBOL', 'ENTRY_OFF_MARKET'));
SELECT 'tips_checksum' AS section, count(*), md5(string_agg(to_jsonb(t)::text, '|' ORDER BY t.id)) FROM tips t;
SELECT 'closed_not_first_seen' AS section, count(*) FROM tips
 WHERE (status = 'UNSCORABLE' OR reason IN ('NO_DIRECTION', 'INCONSISTENT_LEVELS', 'UNRESOLVED_SYMBOL', 'ENTRY_OFF_MARKET')) AND closed_at IS DISTINCT FROM first_seen_at;
SELECT 'claimed_time_formats' AS section, source, fmt, count(*) FROM (
  SELECT source, CASE
    WHEN v IS NULL THEN 'ABSENT'
    WHEN v ~ '^\d{4}-\d{2}-\d{2}$' THEN 'DATE_ONLY'
    WHEN v ~ '(Z|[+-]\d{2}(:?\d{2})?)$' THEN 'ZONED'
    WHEN v ~ '^\d{4}-\d{2}-\d{2}[T ]\d{2}:\d{2}' THEN 'NAIVE'
    ELSE 'OTHER' END AS fmt
  FROM (SELECT source, raw_payload::jsonb ->> 'tipAsOf' AS v FROM tips WHERE tip_as_of IS NOT NULL) x
) y GROUP BY source, fmt ORDER BY source, fmt;
ROLLBACK;
```

- [ ] **Step 4: `scripts/tip_intake_review_survey.py`**

```python
"""Runs scripts/sql/tip_intake_review_survey.sql against DATABASE_URL in a read-only transaction (local test databases and
rehearsals); production runs the same file through psql in the postgres pod (plan G3)."""

from __future__ import annotations

import json
import re
import sys
from pathlib import Path

from sqlalchemy import create_engine, text

SURVEY = Path(__file__).resolve().parent / "sql" / "tip_intake_review_survey.sql"
_SECTION = re.compile(r"'(\w+)' AS section")


def _statements() -> list[str]:
    body = "\n".join(line for line in SURVEY.read_text(encoding="utf-8").splitlines() if not line.startswith("--"))
    return [s.strip() for s in body.split(";") if s.strip() and s.strip() not in ("BEGIN TRANSACTION READ ONLY", "ROLLBACK")]


def run_survey(url: str) -> dict[str, list[tuple]]:
    engine = create_engine(url)
    sections: dict[str, list[tuple]] = {}
    with engine.connect() as conn:
        conn.execute(text("SET TRANSACTION READ ONLY"))
        for statement in _statements():
            sections.setdefault(_SECTION.search(statement).group(1), [])
            for row in conn.execute(text(statement)):
                sections.setdefault(row[0], []).append(tuple(row[1:]))
        conn.rollback()
    engine.dispose()
    return sections


if __name__ == "__main__":
    from app.settings import settings
    json.dump({k: [list(map(str, row)) for row in v] for k, v in run_survey(settings.database_url).items()},
              sys.stdout, indent=1)
```

- [ ] **Step 5: `scripts/tip_intake_review_verify.py`**

```python
"""Intake review spec §12 G5/G6: what migration 0193 must preserve, captured before it and checked after it.

`snapshot` is read-only and imports only modules main 027eda3 already has, so it also runs inside the pre-0193 API pod
(`python - snapshot < this file`). `check` runs on the 0193 code and exits 1 on any difference."""

from __future__ import annotations

import argparse
import dataclasses
import json
import sys
from collections import defaultdict
from datetime import datetime, timezone
from decimal import Decimal

from sqlalchemy import text

from app.db import SessionLocal
from app.tip_scorecard import TipFact, compute_metrics
from app.tip_scorecard_query import SCOPE_ALL, SCOPE_CALLER, SCOPE_CHANNEL, SCOPE_CUSTOMER, load_directory

MOVE = ("(status IN ('PENDING_CHECK', 'UNSCORABLE') OR reason IN "
        "('NO_DIRECTION', 'INCONSISTENT_LEVELS', 'UNRESOLVED_SYMBOL', 'ENTRY_OFF_MARKET'))")
VALIDATED_ONLY = ("status <> 'PENDING_CHECK' AND status <> 'UNSCORABLE' AND COALESCE(reason, '') NOT IN "
                  "('NO_DIRECTION', 'INCONSISTENT_LEVELS', 'UNRESOLVED_SYMBOL', 'ENTRY_OFF_MARKET', "
                  "'CLAIMED_TIME_OLDER', 'CLAIMED_TIME_INVALID', 'CLAIMED_TIME_UNVERIFIED')")


def _aware(value):
    return value if value is None or value.tzinfo is not None else value.replace(tzinfo=timezone.utc)


def _plain(metrics) -> dict:
    return {k: str(v) if isinstance(v, Decimal) else v for k, v in dataclasses.asdict(metrics).items()}


def _old_facts(session, directory, as_of) -> list[TipFact]:
    """§8's pre-0193 read of `tips`, replayed in SQL so it runs on either schema."""
    rows = session.execute(text(
        "SELECT id, source_channel_id, caller_id, status, outcome, closed_at, closed_session, promised_return, "
        "actual_return, horizon_sessions, symbol FROM tips WHERE match_key IS NOT NULL AND status IS NOT NULL "
        "AND first_seen_at <= :as_of"), {"as_of": as_of}).all()
    return [TipFact(tip_id=r.id, status=r.status, outcome=r.outcome, closed_at=_aware(r.closed_at),
                    closed_session=r.closed_session, promised_return=r.promised_return,
                    actual_return=r.actual_return, channel_id=directory.channel_root.get(r.source_channel_id),
                    caller_id=directory.caller_root.get(r.caller_id), horizon_sessions=r.horizon_sessions,
                    symbol=r.symbol) for r in rows]


def snapshot(session, as_of: datetime) -> dict:
    session.execute(text("SET TRANSACTION READ ONLY"))
    directory = load_directory(session)
    facts = _old_facts(session, directory, as_of)
    groups: dict[str, list[TipFact]] = defaultdict(list, {SCOPE_ALL: list(facts)})
    for fact in facts:
        if fact.channel_id is not None:
            groups[f"{SCOPE_CHANNEL}:{fact.channel_id}"].append(fact)
        if fact.caller_id is not None:
            groups[f"{SCOPE_CALLER}:{fact.caller_id}"].append(fact)
    held = defaultdict(set)
    for user_id, tip_id in session.execute(text(
            "SELECT DISTINCT user_id, tip_id FROM tip_receipts WHERE tip_id IS NOT NULL")):
        held[user_id].add(tip_id)
    for user_id, ids in held.items():
        groups[f"{SCOPE_CUSTOMER}:{user_id}"] = [fact for fact in facts if fact.tip_id in ids]
    moved = session.execute(text(f"SELECT t.id, md5(to_jsonb(t)::text) FROM tips t WHERE {MOVE} ORDER BY t.id"))
    receipts = session.execute(text(
        f"SELECT id, tip_id FROM tip_receipts WHERE tip_id IN (SELECT id FROM tips WHERE {MOVE}) ORDER BY id"))
    return {
        "asOf": as_of.isoformat(),
        "scorecards": {key: _plain(compute_metrics(group, as_of=as_of)) for key, group in groups.items()},
        "moved": {str(tip_id): digest for tip_id, digest in moved},
        "receipts": {str(receipt_id): tip_id for receipt_id, tip_id in receipts},
    }


def check(session, pre: dict) -> list[str]:
    from app.tip_scorecard_query import scorecard  # the 0193 code's real §8 read
    as_of = datetime.fromisoformat(pre["asOf"])
    directory = load_directory(session)
    problems = []
    for key, expected in pre["scorecards"].items():
        scope, _, raw_id = key.partition(":")
        scope_id = None if scope == SCOPE_ALL else raw_id if scope == SCOPE_CUSTOMER else int(raw_id)
        actual = _plain(scorecard(session, scope=scope, scope_id=scope_id, as_of=as_of, directory=directory)[1].metrics)
        if actual != expected:
            problems.append(f"scorecard {key}: " + ", ".join(
                f"{field} {expected[field]} -> {actual[field]}" for field in expected if expected[field] != actual[field]))
    items = dict(session.execute(text(
        "SELECT origin_tip_id, md5((payload::jsonb - 'legacy_rows')::text) FROM tip_intake_reviews "
        "WHERE origin = 'MIGRATED_0193'")).all())
    problems += [f"moved tip {tip_id}: payload checksum differs or item missing"
                 for tip_id, digest in pre["moved"].items() if items.get(int(tip_id)) != digest]
    links = dict(session.execute(text(
        "SELECT rc.id, r.origin_tip_id FROM tip_receipts rc JOIN tip_intake_reviews r ON r.id = rc.intake_review_id")).all())
    problems += [f"receipt {receipt_id}: not relinked to the item of tip {tip_id}"
                 for receipt_id, tip_id in pre["receipts"].items() if links.get(int(receipt_id)) != tip_id]
    if session.execute(text("SELECT count(*) FROM pg_constraint WHERE conname = 'ck_tips_validated_only'")).scalar() != 1:
        problems.append("ck_tips_validated_only is missing")
    violating = session.execute(text(f"SELECT count(*) FROM tips WHERE NOT ({VALIDATED_ONLY})")).scalar()
    if violating:
        problems.append(f"{violating} tips rows violate ck_tips_validated_only")
    return problems


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    modes = parser.add_subparsers(dest="mode", required=True)
    snap = modes.add_parser("snapshot")
    snap.add_argument("--as-of", type=datetime.fromisoformat, default=None)
    verify = modes.add_parser("check")
    verify.add_argument("--pre", required=True)
    args = parser.parse_args(argv)
    with SessionLocal() as session:
        if args.mode == "snapshot":
            json.dump(snapshot(session, args.as_of or datetime.now(timezone.utc)), sys.stdout, indent=1, sort_keys=True)
            session.rollback()
            return 0
        with open(args.pre, encoding="utf-8") as handle:
            problems = check(session, json.load(handle))
        session.rollback()
    print(json.dumps({"ok": not problems, "problems": problems}, indent=1))
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
```

- [ ] **Step 6: Run green**

Run: `DATABASE_URL="$PG_TEST_URL" python -m pytest tests/test_tip_intake_review_scripts.py tests/test_tip_intake_review_migration.py -q -p no:cacheprovider`
Expected: all pass. PostgreSQL sorts NULL reasons last within a status, which is the order the `move_set` assertion encodes.

- [ ] **Step 7: Commit**

```bash
git add scripts/sql/tip_intake_review_survey.sql scripts/tip_intake_review_survey.py scripts/tip_intake_review_verify.py tests/test_tip_intake_review_scripts.py
git commit -m "Read-only 0193 survey and snapshot/check verification

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 10: Regression, contract, whole-phase review, draft PR

**Files:** `docs/api/openapi.json` (regenerated). No other new code unless the review finds defects.

- [ ] **Step 1: Regenerate the OpenAPI contract**

```bash
python scripts/export_openapi.py
python -m pytest tests/test_openapi_contract_freshness.py -q -p no:cacheprovider
```

Expected: pass locally. CI's pydantic 2.14.1 drops the Decimal `pattern` and fails this test on every branch (known, see memory); compare CI's failure set to main's.

- [ ] **Step 2: Full suite against the baseline**

```bash
DATABASE_URL="sqlite:///$(cygpath -m "$PWD")/.artifacts/tip-intake-review/final.db" \
  python -m pytest -n 6 --dist loadfile -q -p no:cacheprovider -rfE 2>&1 | tee .artifacts/tip-intake-review/final.log | tail -3
grep -E "^(FAILED|ERROR) " .artifacts/tip-intake-review/final.log | sed 's/ - .*//' | sort -u > .artifacts/tip-intake-review/final-failures.txt
comm -13 .artifacts/tip-intake-review/baseline-failures.txt .artifacts/tip-intake-review/final-failures.txt
DATABASE_URL="$PG_TEST_URL" python -m pytest tests/test_tip_intake_review_migration.py tests/test_tip_intake_review_scripts.py tests/test_fresh_database_migration.py -q -p no:cacheprovider
```

Expected: `comm` prints nothing (no new failures), and the PostgreSQL run passes.

- [ ] **Step 3: Whole-phase review (one review for Part A, not per task)**

Invoke `superpowers:requesting-code-review` on `main..feat/tip-intake-review-queue`. The reviewer prompt must include "Use `tokensave_context` as primary exploration tool" and the spec path, and must name these checks:
- every Global Constraint;
- that no path inserts a `Tip` without `admit_or_queue` (grep `Tip(` and `session.add(tip)` in `app/`);
- that AH1/AH2 run on both admission paths;
- that `app/tip_scorecard.py` is unchanged (`git diff main -- app/tip_scorecard.py` is empty).

Fix every finding with a test first, then re-review until clean.

- [ ] **Step 4: Push the branch and open the draft PR**

```bash
git push -u origin feat/tip-intake-review-queue
gh pr create --draft --base main --title "[DO NOT MERGE before G3–G5] Tip intake review queue: validated-only tips" --body-file - <<'EOF'
Implements `marksy-os/docs/superpowers/specs/2026-10-14-tip-intake-review-queue-design.md`.

- Calls that fail intake, or wait for a price, go to `tip_intake_reviews`; `tips` holds only validated calls (CHECK `ck_tips_validated_only` + ORM guard).
- Claimed creation times are used only when the policy proves they cannot improve a result; admin admissions follow the anti-hindsight rule.
- Admin routes `/admin/intake-reviews` (list, detail, resubmit through the gate, dismiss).
- Migration 0193 moves today's failed rows verbatim, with an exact downgrade.

**Do not merge** until G3 (prod survey), G4 (backup + restore test) and G5 (rehearsal) pass: any deploy builds `main` and applies 0193.

Validation: full suite = baseline failure set; PostgreSQL migration/script tests green.

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
```

Pushing and opening a draft PR is the executor's step after the user approves this plan; it publishes code and nothing else.

---

## Part B — admin-app

### Task 11: Intake Review page

**Files:**
- Modify: `src/api/types.ts` (append), `src/api/tipLedger.ts` (append `intakeReviewsApi`), `src/App.tsx` (route `/intake-review`), `src/components/Layout.tsx` (nav item after Receipt Review), `src/pages/ReceiptReviewPage.tsx` (`OUTCOMES.QUEUED`)
- Create: `src/pages/IntakeReviewPage.tsx`, `src/pages/IntakeReviewPage.test.tsx`

**Interfaces:**
- Consumes: the Task 6 routes and JSON shapes (Decimals arrive as strings).
- Produces: `intakeReviewsApi.list(state, cursor)`, `.detail(id)`, `.resubmit(id, body)`, `.dismiss(id, body)`.

- [ ] **Step 1: Branch**

```bash
cd /c/AIAgent/admin-app && git status --short && git switch main && git switch -c feat/intake-review-page
```

- [ ] **Step 2: Write the failing test (`src/pages/IntakeReviewPage.test.tsx`)**

```tsx
import { describe, expect, it } from "vitest";
import { screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { server } from "../test/server";
import { renderApp, seedAuth } from "../test/render";
import type { IntakeReviewView } from "../api/types";
import { IntakeReviewPage } from "./IntakeReviewPage";

const BASE = "http://localhost/api/v1";

function ok(data: unknown) {
  return HttpResponse.json({ data, meta: { requestId: "t", timestamp: new Date().toISOString(), pageSize: 100, nextCursor: null } });
}

function review(overrides: Partial<IntakeReviewView>): IntakeReviewView {
  return {
    reviewId: "rv-1", state: "REJECTED", reason: "INCONSISTENT_LEVELS", rejectedStatus: "UNSCORABLE", origin: "INTAKE",
    channelId: 1, channelName: "Upstox", callerId: null, symbol: "RENUKA", direction: "BUY", entryLow: null,
    entryHigh: null, target: "20", stopLoss: "22.25", horizonSessions: 20, receivedAt: "2026-09-28T04:30:00Z",
    claimedCreatedAt: null, claimedTimeSource: null, scoringStartAt: "2026-09-28T04:30:00Z",
    scoringStartBasis: "SERVER_RECEIPT", entryCheckPrice: null, entryCheckBasis: null, checkEvidence: null,
    promotedTipId: null, decidedAt: null, decidedBy: null, receiptCount: 1, ...overrides,
  };
}

describe("IntakeReviewPage", () => {
  it("lists rejected calls, shows why a resubmit still fails, and needs a reason and note to dismiss", async () => {
    let dismissed: unknown = null;
    server.use(
      http.get(`${BASE}/admin/intake-reviews`, ({ request }) =>
        ok(new URL(request.url).searchParams.get("state") === "REJECTED" ? [review({})] : [])),
      http.post(`${BASE}/admin/intake-reviews/rv-1/resubmit`, () =>
        ok({ review: review({ target: "21" }), tipId: null })),
      http.post(`${BASE}/admin/intake-reviews/rv-1/dismiss`, async ({ request }) => {
        dismissed = await request.json();
        return ok(review({ state: "DISMISSED" }));
      }),
    );
    seedAuth();
    renderApp(<IntakeReviewPage />);

    const row = (await screen.findByText("RENUKA")).closest("tr")!;
    await userEvent.click(within(row).getByRole("button", { name: "Resubmit" }));
    await userEvent.clear(screen.getByLabelText("Target"));
    await userEvent.type(screen.getByLabelText("Target"), "21");
    await userEvent.type(screen.getByLabelText("Note"), "second read");
    await userEvent.click(screen.getByRole("button", { name: "Run checks" }));
    expect(await screen.findByRole("status")).toHaveTextContent("Still rejected: INCONSISTENT_LEVELS");

    await userEvent.click(within(row).getByRole("button", { name: "Dismiss" }));
    const confirm = screen.getByRole("button", { name: "Dismiss call" });
    expect(confirm).toBeDisabled();
    await userEvent.selectOptions(screen.getByLabelText("Reason"), "NOT_A_TIP");
    await userEvent.type(screen.getByLabelText("Dismissal note"), "market commentary");
    await userEvent.click(confirm);
    expect(dismissed).toEqual({ reason: "NOT_A_TIP", note: "market commentary" });
  });
});
```

- [ ] **Step 3: Run red (user runs; the agent cannot run node here)**

Ask the user to run `! cd /c/AIAgent/admin-app && npx vitest run src/pages/IntakeReviewPage.test.tsx`.
Expected: fails to resolve `./IntakeReviewPage`.

- [ ] **Step 4: Types and client**

Append to `src/api/types.ts`:

```ts
export type IntakeReviewState = "PENDING_CHECK" | "REJECTED" | "PROMOTED" | "DISMISSED";
export type DismissReason = "NOT_A_TIP" | "UNFIXABLE_TERMS" | "DUPLICATE" | "OFF_MARKET_CONFIRMED" | "OTHER";

export interface IntakeReviewView {
  reviewId: string;
  state: IntakeReviewState;
  reason: string | null;
  rejectedStatus: string | null;
  origin: string;
  channelId: number | null;
  channelName: string | null;
  callerId: number | null;
  symbol: string;
  direction: string | null;
  entryLow: string | null;
  entryHigh: string | null;
  target: string | null;
  stopLoss: string | null;
  horizonSessions: number | null;
  receivedAt: string;
  claimedCreatedAt: string | null;
  claimedTimeSource: string | null;
  scoringStartAt: string;
  scoringStartBasis: string;
  entryCheckPrice: string | null;
  entryCheckBasis: string | null;
  checkEvidence: Record<string, unknown> | null;
  promotedTipId: string | null;
  decidedAt: string | null;
  decidedBy: string | null;
  receiptCount: number;
}

export interface IntakeReviewEventView {
  at: string;
  actor: string;
  action: string;
  before: Record<string, unknown> | null;
  after: Record<string, unknown> | null;
  note: string | null;
}

export interface IntakeReviewDetailView extends IntakeReviewView {
  receipts: AdminReceiptView[];
  events: IntakeReviewEventView[];
}

export interface ResubmitIntakeReviewRequest {
  symbol?: string;
  direction?: string | null;
  entryLow?: string | null;
  entryHigh?: string | null;
  target?: string | null;
  stopLoss?: string | null;
  horizonSessions?: number | null;
  note: string;
}

export interface DismissIntakeReviewRequest {
  reason: DismissReason;
  note: string;
}

export interface IntakeReviewDecisionView {
  review: IntakeReviewView;
  tipId: string | null;
}
```

Append to `src/api/tipLedger.ts` (and add the new types to its import):

```ts
export const intakeReviewsApi = {
  list: (state: IntakeReviewState, cursor: string | null) =>
    http.getPage<IntakeReviewView>(`/admin/intake-reviews${qs({ state, pageSize: PAGE_SIZE, cursor })}`),
  detail: (reviewId: string) =>
    http.get<IntakeReviewDetailView>(`/admin/intake-reviews/${encodeURIComponent(reviewId)}`),
  resubmit: (reviewId: string, body: ResubmitIntakeReviewRequest) =>
    http.post<IntakeReviewDecisionView>(`/admin/intake-reviews/${encodeURIComponent(reviewId)}/resubmit`, body),
  dismiss: (reviewId: string, body: DismissIntakeReviewRequest) =>
    http.post<IntakeReviewView>(`/admin/intake-reviews/${encodeURIComponent(reviewId)}/dismiss`, body),
};
```

- [ ] **Step 5: The page (`src/pages/IntakeReviewPage.tsx`)**

```tsx
import { useState } from "react";
import { useInfiniteQuery, useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { intakeReviewsApi } from "../api/tipLedger";
import type { DismissReason, IntakeReviewState, IntakeReviewView, ResubmitIntakeReviewRequest } from "../api/types";
import type { Column } from "../components/DataTable";
import { DataTable } from "../components/DataTable";
import { Modal } from "../components/Modal";
import { Tabs } from "../components/Tabs";
import { formatDateTime } from "../lib/format";

const TABS = [
  { key: "REJECTED", label: "Rejected" },
  { key: "PENDING_CHECK", label: "Waiting for a price" },
  { key: "DISMISSED", label: "Dismissed" },
  { key: "PROMOTED", label: "Entered" },
];
const DISMISS_REASONS: DismissReason[] = ["NOT_A_TIP", "UNFIXABLE_TERMS", "DUPLICATE", "OFF_MARKET_CONFIRMED", "OTHER"];
const FIELDS: { key: keyof ResubmitIntakeReviewRequest; label: string }[] = [
  { key: "symbol", label: "Symbol" },
  { key: "direction", label: "Direction" },
  { key: "entryLow", label: "Entry low" },
  { key: "entryHigh", label: "Entry high" },
  { key: "target", label: "Target" },
  { key: "stopLoss", label: "Stop-loss" },
];

// Intake review spec §6: calls kept out of the ledger. A fix runs every check again; nothing here can backdate a call.
export function IntakeReviewPage() {
  const qc = useQueryClient();
  const [state, setState] = useState<IntakeReviewState>("REJECTED");
  const [editing, setEditing] = useState<IntakeReviewView | null>(null);
  const [dismissing, setDismissing] = useState<IntakeReviewView | null>(null);
  const [history, setHistory] = useState<string | null>(null);
  const [message, setMessage] = useState<string | null>(null);
  const reviews = useInfiniteQuery({
    queryKey: ["intake-reviews", state],
    queryFn: ({ pageParam }) => intakeReviewsApi.list(state, pageParam),
    initialPageParam: null as string | null,
    getNextPageParam: (page) => page.nextCursor,
  });
  const refresh = () => {
    qc.invalidateQueries({ queryKey: ["intake-reviews"] });
    qc.invalidateQueries({ queryKey: ["tip-ledger"] });
  };
  const resubmit = useMutation({
    mutationFn: ({ id, body }: { id: string; body: ResubmitIntakeReviewRequest }) => intakeReviewsApi.resubmit(id, body),
    onSuccess: (result) => {
      setMessage(result.tipId ? `Entered the ledger as tip ${result.tipId}.`
        : result.review.state === "PENDING_CHECK" ? "Passed; waiting for a same-session price before it is entered."
        : `Still rejected: ${result.review.reason}`);
      setEditing(null);
      refresh();
    },
  });
  const dismiss = useMutation({
    mutationFn: ({ id, reason, note }: { id: string; reason: DismissReason; note: string }) =>
      intakeReviewsApi.dismiss(id, { reason, note }),
    onSuccess: () => {
      setMessage("Dismissed. It stays counted as rejected.");
      setDismissing(null);
      refresh();
    },
  });

  const columns: Column<IntakeReviewView>[] = [
    { key: "received", header: "Received", className: "nowrap", sortValue: (r) => new Date(r.receivedAt).getTime(),
      render: (r) => <span className="muted">{formatDateTime(r.receivedAt)}</span> },
    { key: "channel", header: "Channel", render: (r) => r.channelName ?? "—" },
    { key: "symbol", header: "Symbol", sortValue: (r) => r.symbol, render: (r) => <span className="mono">{r.symbol}</span> },
    { key: "terms", header: "Terms", render: (r) => <span className="mono">{[r.direction, r.entryLow && `@${r.entryLow}`, r.target && `T ${r.target}`, r.stopLoss && `SL ${r.stopLoss}`].filter(Boolean).join(" ")}</span> },
    { key: "reason", header: "Reason", render: (r) => r.reason ?? "—" },
    { key: "start", header: "Scoring start", render: (r) => <span className="muted">{formatDateTime(r.scoringStartAt)} · {r.scoringStartBasis}</span> },
    { key: "actions", header: "", align: "right", render: (r) => (
      <>
        <button className="btn btn-sm" onClick={() => setHistory(r.reviewId)}>History</button>
        {r.state === "REJECTED" && (
          <>
            <button className="btn btn-sm" onClick={() => { setMessage(null); setEditing(r); }}>Resubmit</button>
            <button className="btn btn-sm" onClick={() => { setMessage(null); setDismissing(r); }}>Dismiss</button>
          </>
        )}
      </>
    ) },
  ];
  const failure = (resubmit.error ?? dismiss.error) instanceof Error ? (resubmit.error ?? dismiss.error)!.message : null;

  return (
    <>
      <Tabs tabs={TABS} active={state} onChange={(key) => { setState(key as IntakeReviewState); setMessage(null); }} />
      <p className="muted">
        Calls that failed an intake check, or still wait for a price, never enter the ledger. A resubmit runs every check
        again; one admitted after the market has traded is scored from the moment you admit it.
      </p>
      {message && <p role="status" className="card" style={{ padding: "var(--space-4)" }}>{message}</p>}
      {failure && <p className="error-text" role="alert">{failure}</p>}
      <DataTable columns={columns} rows={reviews.data?.pages.flatMap((page) => page.items)} getRowKey={(r) => r.reviewId}
        isLoading={reviews.isLoading} error={reviews.error} emptyLabel="Nothing in this state." pageSize={25} />
      {reviews.hasNextPage && (
        <div style={{ display: "flex", justifyContent: "center", marginTop: "var(--space-4)" }}>
          <button className="btn btn-sm" disabled={reviews.isFetchingNextPage} onClick={() => void reviews.fetchNextPage()}>
            {reviews.isFetchingNextPage ? "Loading…" : "Load more"}
          </button>
        </div>
      )}
      {editing && <ResubmitModal review={editing} busy={resubmit.isPending} onClose={() => setEditing(null)}
        onSubmit={(body) => resubmit.mutate({ id: editing.reviewId, body })} />}
      {dismissing && <DismissModal busy={dismiss.isPending} onClose={() => setDismissing(null)}
        onSubmit={(reason, note) => dismiss.mutate({ id: dismissing.reviewId, reason, note })} />}
      {history && <HistoryModal reviewId={history} onClose={() => setHistory(null)} />}
    </>
  );
}

function ResubmitModal({ review, busy, onClose, onSubmit }: { review: IntakeReviewView; busy: boolean;
  onClose: () => void; onSubmit: (body: ResubmitIntakeReviewRequest) => void }) {
  const [values, setValues] = useState<Record<string, string>>(() =>
    Object.fromEntries(FIELDS.map(({ key }) => [key, String(review[key as keyof IntakeReviewView] ?? "")])));
  const [note, setNote] = useState("");
  const changed = () => Object.fromEntries(FIELDS
    .filter(({ key }) => values[key] !== String(review[key as keyof IntakeReviewView] ?? ""))
    .map(({ key }) => [key, values[key] === "" ? null : values[key]]));
  return (
    <Modal title={`Resubmit ${review.symbol}`} onClose={onClose} footer={
      <button className="btn" disabled={busy || !note.trim()} onClick={() => onSubmit({ ...changed(), note })}>Run checks</button>
    }>
      {FIELDS.map(({ key, label }) => (
        <label key={key} className="field">{label}
          <input aria-label={label} value={values[key]} onChange={(e) => setValues({ ...values, [key]: e.target.value })} />
        </label>
      ))}
      <label className="field">Note
        <textarea aria-label="Note" value={note} onChange={(e) => setNote(e.target.value)} />
      </label>
    </Modal>
  );
}

function DismissModal({ busy, onClose, onSubmit }: { busy: boolean; onClose: () => void;
  onSubmit: (reason: DismissReason, note: string) => void }) {
  const [reason, setReason] = useState<DismissReason | "">("");
  const [note, setNote] = useState("");
  return (
    <Modal title="Dismiss call" onClose={onClose} footer={
      <button className="btn" disabled={busy || !reason || !note.trim()} onClick={() => reason && onSubmit(reason, note)}>Dismiss call</button>
    }>
      <label className="field">Reason
        <select aria-label="Reason" value={reason} onChange={(e) => setReason(e.target.value as DismissReason)}>
          <option value="">Choose…</option>
          {DISMISS_REASONS.map((r) => <option key={r} value={r}>{r}</option>)}
        </select>
      </label>
      <label className="field">Dismissal note
        <textarea aria-label="Dismissal note" value={note} onChange={(e) => setNote(e.target.value)} />
      </label>
    </Modal>
  );
}

function HistoryModal({ reviewId, onClose }: { reviewId: string; onClose: () => void }) {
  const detail = useQuery({ queryKey: ["intake-reviews", "detail", reviewId], queryFn: () => intakeReviewsApi.detail(reviewId) });
  return (
    <Modal title="History" onClose={onClose} wide>
      {detail.data?.events.map((e) => (
        <p key={`${e.at}-${e.action}`}><span className="muted">{formatDateTime(e.at)}</span> {e.action} · {e.actor}{e.note ? ` — ${e.note}` : ""}</p>
      )) ?? <p className="muted">Loading…</p>}
    </Modal>
  );
}
```

Wire it up:
- `src/App.tsx`: `import { IntakeReviewPage } from "./pages/IntakeReviewPage";` and `<Route path="/intake-review" element={<IntakeReviewPage />} />` next to `/receipt-review`.
- `src/components/Layout.tsx`: a nav entry `{ to: "/intake-review", label: "Intake Review", icon: icon(<><path d="M12 3v12" /><path d="M5 10l7 7 7-7" /><path d="M5 21h14" /></>) }` after Receipt Review.
- `src/pages/ReceiptReviewPage.tsx`: `OUTCOMES.QUEUED = "Read as a call that failed a check or waits for a price; it is in Intake Review, not the ledger."`

- [ ] **Step 6: Run green (user runs)**

Ask the user to run `! cd /c/AIAgent/admin-app && npx vitest run src/pages/IntakeReviewPage.test.tsx src/pages/ReceiptReviewPage.test.tsx && npm run typecheck && npm run lint`.
Expected: all pass.

- [ ] **Step 7: Commit, push, draft PR (merged only after Part A is deployed)**

```bash
git add src/api/types.ts src/api/tipLedger.ts src/pages/IntakeReviewPage.tsx src/pages/IntakeReviewPage.test.tsx src/pages/ReceiptReviewPage.tsx src/App.tsx src/components/Layout.tsx
git commit -m "Intake Review page: resubmit through the checks, dismiss with a reason, history

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
git push -u origin feat/intake-review-page
gh pr create --draft --title "Intake Review page (needs marksy-api intake-review routes deployed)" --body "Admin screen for marksy-api's /admin/intake-reviews. Merge after the marksy-api PR is deployed (G6).

🤖 Generated with [Claude Code](https://claude.com/claude-code)"
```

---

## Part C — Gates runbook (no code; every production step is the user's `!` command)

Shared helper: build a one-shot script that runs a SQL file through `psql` in the postgres pod. It uses the memory pattern of `KUBECONFIG`, the pod lookup and a heredoc. The agent writes it to the scratchpad; **the user** runs the `ssh` line.

```bash
SQL=/c/AIAgent/marksy-api/scripts/sql/tip_intake_review_survey.sql
OUT="$SCRATCH/survey-run.sh"
{
  echo 'export KUBECONFIG=$HOME/.kube/config'
  echo 'P=$(kubectl -n market-agent get pods --field-selector=status.phase=Running -o name | grep -m1 "^pod/postgres")'
  echo 'kubectl -n market-agent exec -i $P -- sh -c '"'"'psql -X -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB"'"'"' <<'"'"'SQL'"'"''
  cat "$SQL"
  echo 'SQL'
} > "$OUT"
# user: ! cat "$OUT" | ssh marksy@103.178.160.233 "tr -d '\r' | bash" > survey-$(date +%F).txt
```

### G3 — read-only production survey

**Precondition:** Part A passed Task 10 **and the user separately approved running G3**. The survey file opens `BEGIN TRANSACTION READ ONLY` and ends with `ROLLBACK`.

**Pass criteria (all must hold):**
- `alembic_version` = `0192_tip_entry_check`.
- `move_set_stray` returns no rows (no unexpected status; no match key without `first_seen_at`).
- `unhandled_references` returns no rows.
- `unexpected_merged_or_revised` and `stranded_duplicates` return no rows; `followers` depth ≤ 2 (Amendment A1). Otherwise stop and report the counts by relationship type.
- `preflight_outside_move_set` returns no rows.
- `null_status_rows.with_reason` = 0.
- `move_set` counts are plausible against the 2026-10-13 audit (2,284 tips). Record each count.
- Record `server_version` (→ `PG_MAJOR`), `move_set_checksum`, `receipt_link_checksum`, `tips_checksum`, `closed_not_first_seen` (informational, see D2) and every status/reason/reference category present (G5's fixtures must cover each one).
- Record `claimed_time_formats` (counts only, no raw values). It is the evidence for the final CT3 policy, which the user decides; nothing is inferred.

Any failed criterion → **stop and report options to the user**. Do not adjust the migration by judgement.

### G4 — backup and restore test (on the VPS; separate approval)

Nothing from production is copied to the laptop (D7). The user runs these after approving G4. Run the survey's `session_settings` and three checksum statements together inside one `BEGIN TRANSACTION READ ONLY` … `ROLLBACK` (the TimeZone/DateStyle pin does not carry across separate psql sessions) immediately before and after the dumps; if the two differ (a write landed), repeat. Run the restore pod's checksums the same way. In the postgres pod, written to the pod's `/tmp` (exec streams reset after ~5 min):

```bash
pg_dump -Fc -U "$POSTGRES_USER" -d "$POSTGRES_DB" -t tips -t tip_receipts -t external_tip_comparisons -t external_tip_outcomes -f /tmp/tip-intake-0193-rows.dump
pg_dump -Fc --schema-only -U "$POSTGRES_USER" -d "$POSTGRES_DB" -f /tmp/tip-intake-0193-schema.dump
sha256sum /tmp/tip-intake-0193-*.dump
```

Then `kubectl cp` both files to `~/backups/` on the VPS and compare `sha256sum`.

Off-VPS copy: the existing nightly job `market-agent-backup` (full `pg_dump`, AES-256-GCM encrypted on the VPS, uploaded to Google Drive; verified working 2026-10-14: `20261013T210002Z`, `off-site: STORED via google_drive`). It never touches the laptop. The user has tested the end-to-end backup, Google Drive restore included (confirmed 2026-10-14). The restore-check job below proves only the on-node copy; run it to show that path is current:

```bash
kubectl -n market-agent create job --from=cronjob/market-agent-restore-verification restore-check-0193
kubectl -n market-agent wait --for=condition=complete job/restore-check-0193 --timeout=2700s
kubectl -n market-agent logs job/restore-check-0193 --tail=30
```

The fresh pre-migration off-site copy is taken in G6 step 2a.

Restore test on the VPS, in a throwaway pod with no volume:

```bash
kubectl -n market-agent run pg-0193-restore --image=postgres:$PG_MAJOR --restart=Never --env=POSTGRES_PASSWORD=restore-only
kubectl -n market-agent wait --for=condition=Ready pod/pg-0193-restore --timeout=120s
kubectl -n market-agent cp ~/backups/tip-intake-0193-schema.dump pg-0193-restore:/tmp/s.dump
kubectl -n market-agent cp ~/backups/tip-intake-0193-rows.dump pg-0193-restore:/tmp/r.dump
kubectl -n market-agent exec pg-0193-restore -- sh -c 'createdb -U postgres restore && pg_restore -U postgres --no-owner --no-privileges -d restore /tmp/s.dump && pg_restore -U postgres --data-only --disable-triggers -d restore /tmp/r.dump'
# then the survey's three checksum statements through psql in pg-0193-restore, as in G3
kubectl -n market-agent delete pod pg-0193-restore
```

**Pass criteria:** the restored `move_set_checksum`, `receipt_link_checksum` and `tips_checksum` equal the production values taken around the dump (G3's are a different moment of a live table); the sha256 sums match; the restore pod is deleted; `restore-check-0193` reports PASS, or NOT_VERIFIABLE only for tables written during the dump (e.g. `orchestration_executions`), never FAIL.

### G5 — rehearsal on synthetic fixtures (local, no production data)

1. Before G5, check the T8/T9 fixtures cover every status, reason and reference category G3 recorded. Add any missing one as a test fixture (TDD, focused commit) before rehearsing.
2. Run the migration and script suites against a throwaway local Postgres, `PG_MAJOR` matching G3:

```bash
cd /c/AIAgent/marksy-api
DATABASE_URL="$PG_TEST_URL" python -m pytest tests/test_tip_intake_review_migration.py tests/test_tip_intake_review_scripts.py tests/test_tip_intake_review_promotion_lock_pg.py tests/test_fresh_database_migration.py -q -rs -p no:cacheprovider
```

**Pass criteria:**
- Every test passes and **0 are skipped** (a skip means no PostgreSQL was reached): upgrade moves exactly M verbatim; each unhandled reference aborts with nothing left behind; downgrade restores ids, rows, receipt links and `legacy_rows` with checksums equal to the pre-upgrade values; downgrade refuses after post-0193 data; scorecard counts unchanged; migrated pending Decimals exact; verify `snapshot`/`check` clean and tampering caught.
- Fixture timings are not a production estimate. G3's move-set size bounds the work (a few hundred rows plus one CHECK scan of `tips`), far under the deploy's 300 s migrate-Job wait.

3. **Recovery rehearsal** (G6 route 3, R2–R3), on a throwaway rehearsal database seeded with synthetic data:
   - dump it at 0192 as the "backup";
   - take `snapshot`;
   - upgrade to 0193;
   - create post-deploy records through the app: a queued failing call, a dismiss, and an admitted tip with a verified claimed time;
   - confirm the downgrade refuses;
   - take the preserving dump, and list the records with the R3 rules;
   - restore the "backup" into a fresh database.
   Pass: every listed record is absent from the restore and present in the preserving dump, and no record created after the snapshot is missing from the list.

Clean up only what was created here: `docker rm -f marksy-pg-0193-test`, and confirm no rehearsal database or dump remains.

### G6 — production (user approval required; this plan grants none)

1. Pick a window clear of tracker and capture jobs: not 08:00–09:00 IST and not 15:30–16:45 IST. It must also fall before that UTC day's 21:00 nightly backup (02:30 IST), so step 2a stores a new archive instead of reusing the day's.
2. **Pre-snapshot with the running (pre-0193) code.** The user runs:
   ```bash
   ! cat scripts/tip_intake_review_verify.py | ssh marksy@103.178.160.233 "export KUBECONFIG=\$HOME/.kube/config; A=\$(kubectl -n market-agent get pods --field-selector=status.phase=Running -o name | grep -m1 api); tr -d '\r' | kubectl -n market-agent exec -i \$A -- python - snapshot > ~/tip-intake-0193-pre.json"
   ```
   (Confirm the API pod name prefix with `kubectl -n market-agent get pods -o name` first.)
   The snapshot runs in one REPEATABLE READ, READ ONLY transaction and records high-water marks `maxTipId`, `maxReceiptId` and `takenAt` (final review I2). Check that the three keys are present in `pre.json`.
1a. **Deploy window** (final review M5). Between the migrate Job committing 0193 and the rollout finishing, old pods that queue a failing call hit `ck_tips_validated_only`. `record_intake` retries once, then that request returns 500. Before the window, confirm that the phone outbox retries failed submissions. Keep the scan and recommendation jobs out of the window, because a Marksy registration in an old-image job would roll back its recommendation. Expect transient 500s on failing intakes only.
2a. **Fresh off-site backup** (user): `kubectl -n market-agent create job --from=cronjob/market-agent-backup backup-pre-0193`, wait for completion, and check its log shows `off-site: STORED via google_drive`. `REUSED` or no off-site line → stop.
3. The user marks the PR ready and merges it: `gh pr ready <n> && gh pr merge <n> --merge`.
4. **Deploy** (user): `! ssh marksy@103.178.160.233 'nohup bash ~/deployment/server/vps-deploy.sh > ~/deploy-0193.log 2>&1 &'`. Poll `tail ~/deploy-0193.log` until the migrate Job completes and the rollout finishes. A failed 0193 aborts before rollout, and the old code keeps serving.
5. **Post-check** (user, on the VPS): `kubectl cp ~/tip-intake-0193-pre.json <api pod>:/tmp/pre.json`, then `kubectl exec <api pod> -- python -m scripts.tip_intake_review_verify check --pre /tmp/pre.json`.
   - Pass = `"ok": true`.
   - `check` compares only rows at or below the snapshot's marks. Tips, receipts and intake items created after the snapshot appear under `afterSnapshot` as counts (`newTips`, `newReceipts`, `intakeItems`, `movedAfterSnapshot`). They are informational, not problems.
   - Exception: a row moved after the snapshot that fails 0193's move-set predicate is reported as a problem, because it is real migration damage.
   - Any problem → stop. Two rare causes to investigate while stopped:
     - a transaction in flight at the snapshot instant that committed a lower id later shows as a named scorecard problem;
     - a later copy that fills the caller of a tip or migrated item the snapshot saw changes a `caller:<id>` card.
   - `check` needs a `pre.json` taken with this branch's script; an older one fails with `KeyError: 'maxTipId'`.
   - On a problem, choose a route from **Rollback limits and recovery** below.
6. Merge and deploy the admin-app PR. Then open Intake Review and check that the Rejected tab shows the G3 `move_set` counts.

#### Rollback limits and recovery (read before approving G6)

**Downgrade stops working once new-format data arrives.** `alembic downgrade 0192_tip_entry_check` is an exact inverse only while the database holds nothing that 0192 cannot represent. It refuses as soon as any of these exist; when it refuses, it changes nothing and the database stays at 0193:
- a queue item 0193 did not migrate. Every failing or pending call after deploy creates one, so expect this within the first trading hour.
- a history event other than MIGRATED: a resubmit, dismiss, receipt attach or RECHECKED fill.
- any change to a migrated item: a decision, a promotion, a first-seen fill or a claimed time.
- a tip with a claimed time, a claimed-time source, an `intake_review_id`, or a scoring basis other than SERVER_RECEIPT.
It also aborts if a restored row would differ by one byte from the checksum recorded at upgrade.

**Tested.** These run in this branch's PostgreSQL suite, with 0 skipped:
- With only migrated rows, downgrade restores ids, rows, receipt links, legacy rows and checksums exactly.
- For each of the 8 post-0193 data kinds, downgrade refuses and leaves the database byte-identical at 0193.
- A downgrade that cannot restore exactly aborts.
- On a fresh database, downgrade then upgrade round-trips cleanly.
- Separately, the user tested the Google Drive backup restore end to end on 2026-10-14.

**Not tested.**
- The production steps of route 1.
- Any forward fix of 0193.
- The preserve-and-reconcile steps of route 3, until G5 step 3 rehearses R2–R3 on synthetic data.

**Route 1, downgrade (only before new-format data exists).**
1. Stop every writer on the new image. Roll the API deployment back to the previous image, and suspend the CronJobs that run the new image (`kubectl -n market-agent get cronjobs`). Old pods on the 0193 schema return 500 on failing intakes (step 1a) and write no new-format data.
2. Run `alembic downgrade 0192_tip_entry_check` from a one-off pod on the new image, the way the deploy's migrate Job runs it. Only the new image carries 0193's downgrade.
3. If it refuses, nothing changed; go to route 2. If it succeeds, resume the CronJobs on the previous image.

**Route 2, forward fix (the default once new-format data exists).**
- Keep 0193 running and fix the defect in code. Add a migration (0194) only if stored data needs repair, and audit every repaired row with a history event, as for every queue edit.
- The fix follows this PR's path: TDD, the PostgreSQL suite with 0 skipped, a local rehearsal on synthetic data, then a user-approved merge and deploy with `snapshot` before and `check` after.
- Meanwhile, wrongly queued calls wait in review and nothing is lost. Tips admitted since deploy are the ids above the snapshot's `maxTipId`.

**Route 3, restore the step 2a backup (last resort).** A restore rewinds the whole database to the backup instant. Every later write in every table is lost: tips, receipts, progress, queue items, admin decisions, and every other job's output. Before any restore:
- R1. Freeze writers: scale the API to 0 and suspend every CronJob, so that the preserved copy is final.
- R2. Preserve the current database. Run a full `pg_dump -Fc` in the postgres pod, copy it to `~/backups/post-0193-<UTC timestamp>.dump` on the VPS, and compare `sha256sum`.
  - Do not use `market-agent-backup` for this. Its `backup_id` is the UTC date, so a run on the step 2a day reports `REUSED` and stores nothing.
  - Never copy the dump to the laptop (D7).
- R3. List what must be reconciled, using the snapshot marks:
  - tips with id > `maxTipId`;
  - receipts with id > `maxReceiptId`;
  - every queue item and event not MIGRATED;
  - rows other tables gained after `takenAt`.
  This is a superset, because the snapshot precedes the backup. Keep the ids and counts on the VPS.
- R4. Restore from the on-node copy first, or from Google Drive via `scripts/restore_from_offsite.py` if the node is lost. The disaster-recovery runbook the backup notes cite is not in the repo; write it down before relying on route 3. Then redeploy the previous image, because the restored database is at 0192.
- R5. Reconcile from the preserved dump before reopening intake. No reconciliation tooling exists yet. Build it and test it on synthetic data before any restore that would drop real records. Receipts re-enter through the normal intake, whose source-reference idempotency stops duplicates; never by copying rows. Queue decisions that have no 0192 form are recorded in a reconciliation note.
