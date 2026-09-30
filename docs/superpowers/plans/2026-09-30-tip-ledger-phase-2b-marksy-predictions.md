# Tip Ledger Phase 2b (Marksy Predictions into the Ledger) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Every prediction Marksy records becomes a tip of the Marksy channel. The Phase 2a tracker tracks and scores it under spec §6, unchanged. Marksy withdrawing a call becomes a source exit. `Prediction.status` and the monitor's terminal event follow the tip. The monitor keeps its price checks only for predictions recorded before this phase.

**Architecture:**
- `app/marksy_tips.py` (new) holds the ledger side:
  - registration (`register_prediction`) and lookup (`tip_for_prediction`);
  - withdrawal (`withdraw_prediction`) and the revision link (`link_revision`);
  - the status mapping (`prediction_status_for`, `canonical_tip`, `predictions_following`).
  - It writes only `tips` and `tip_source_exits`.
- `app/recommendations.py` `record_recommendation`, the only constructor of `Prediction`, registers the tip in the same transaction.
- `app/prediction_outcome_monitor.py` gains `sync_prediction_from_tip`. It moves `Prediction.status` off OPEN and appends the monitor's terminal `PredictionOutcomeEvent`, which `/predictions/active` and the `/instruments` lifecycle read.
  - The 2a tracker calls it from `_TipTracking._close`.
  - `record_recommendation` calls it at birth for a call rejected at intake.
- `record_invalidation` and the decay branch of `evaluate_prediction_realtime` also write a `TipSourceExit`. `create_recommendation_revision` sets the new tip's `revises_tip_id`.
- `evaluate_prediction_realtime` skips its price checks for a prediction that has a tip.
- Migration `0183_tips_prediction_unique` makes a prediction at most one tip.

**Tech Stack:** Python 3.12, FastAPI 0.139, SQLAlchemy 2.0.52 (`Mapped`/`mapped_column`), Alembic 1.19, pytest on SQLite 3.49 (tests) and PostgreSQL (prod).

**Spec:** `C:\AIAgent\marksy-os\docs\superpowers\specs\2026-09-29-marksy-central-tip-ledger-design.md`. This plan uses:
- §2 invariants 2, 4, 5, 6 and 9;
- §4 `tips`;
- §5.2 match_key v1 and "matchable";
- §6, unchanged;
- §7, the first four bullets;
- §12 and §13 phase 2.

Phase 2a plan for context: `C:\AIAgent\marksy-os\docs\superpowers\plans\2026-09-29-tip-ledger-phase-2a-tracker.md`.

## Global Constraints

- **Repo and worktree.** Repo: `C:\AIAgent\marksy-api`. Work in a git worktree at `C:\AIAgent\marksy-api-phase2b` on branch `feat/tip-ledger-marksy-predictions`. Create it from `origin/main` after Phase 2a has merged (alembic head `0182_tip_daily_progress`).
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
- **Pytest file DB.** `tests/conftest.py` gives the app engine a file DB at `%TEMP%\marksy-pytest-default.db`, and `create_all` never alters an existing table. Delete that file whenever the ORM schema changes (Task 1 says when).
- **Code style.** Follow the repo:
  - `Mapped`/`mapped_column`, and `BigInteger().with_variant(Integer, "sqlite")` primary keys;
  - no ORM relationships;
  - snake_case keys in `PredictionOutcomeEvent.evidence`, as the monitor already writes them;
  - comments are one line and explain a non-obvious WHY only;
  - no unrelated refactors.
- **Migrations.** Alembic revision ids must be ≤ 32 characters. Migrations must run on both SQLite and PostgreSQL.
- **SQLite and time zones.** SQLite drops tzinfo on read. Pass every stored datetime through `_aware(...)` (assume UTC) before comparing it or converting it to IST.
- **Spec §7, verbatim:**
  - "Every rule in §6 (entry, exits, horizon, statuses, outcomes, returns) and §8 (metrics, trust score, filters) applies to Marksy's calls unchanged. There is no Marksy-specific tracking or scoring rule."
  - "A published prediction becomes a tip with `prediction_id`, its stated terms, `first_seen_at` = publish time and `horizon_basis = ENGINE`."
  - "Marksy withdrawing its own prediction (today's decay/invalidation in `prediction_outcome_monitor`) is a source exit: SOURCE_EXIT at the price then. Marksy cannot drop a losing call by invalidating it."
  - "`Prediction.status` is synced from the tip's state. The monitor's price checks retire in favour of the tracker; its model-driven invalidation emits exit events."
- **Spec §2, invariants 5 and 6, verbatim:**
  - "Call terms (channel, symbol, direction, stated entry, target, stop-loss, stated horizon) and `first_seen_at` are immutable. Tips and receipts are never deleted."
  - "A revised tip is a new tip. The original is tracked on its own terms to its own end."
- **The tracker is unchanged.** `app/tip_tracker.py` is not modified. `app/tip_tracking_job.py` gains only the two-line sync hook in `_close`.
- **Out of scope:**
  - the rating-engine port (Phase 2c);
  - scorecards and APIs (Phase 3);
  - registering predictions recorded before this phase (Phase 6; see decision 8).

## Review Focus

These are the inputs the spec implies but its happy paths never exercise. Each one has a test in the owning task:

1. **A losing call that Marksy withdraws.** It must close as SOURCE_EXIT with a FAILURE outcome at the next open. It must not vanish as an unscored INVALIDATED. Test: Task 5, `test_withdrawing_an_entered_losing_call_scores_a_failed_source_exit`.
2. **The 08:30 official-candle confirmation invalidating a call before its first session.** Nobody could have entered it, so it closes INVALIDATED with no outcome, never a zero-risk source exit at the open. Test: Task 5, `test_an_invalidation_before_the_first_open_voids_the_unentered_call`.
3. **An identical call while the first is still ACTIVE.** The partial unique index `uq_tips_active_match_key` would reject a second ACTIVE tip. Registration, which runs inside `record_recommendation`, must not raise, and the second tip must point at the first. Test: Task 2, `test_an_identical_call_while_the_first_is_active_becomes_a_merged_duplicate`.
4. **A revised call.** The original tip must keep running on its own terms to its own end, with no source exit. Its prediction stays SUPERSEDED but still gets its terminal event. Test: Task 6, `test_a_superseded_call_is_still_tracked_to_its_own_end`.
5. **The monitor's price checks retiring.** A prediction recorded before this phase has no tip, and the monitor must still resolve it. A registered prediction must be left to the tracker. Test: Task 7, `test_the_monitor_checks_prices_only_for_predictions_without_a_tip`.

## Resolved ambiguities (decisions this plan makes)

1. **Choke point and terms.**
   - `record_recommendation` (`app/recommendations.py:60-141`) is the only `Prediction(` constructor in `app/`, `api/` and `scripts/`. Registration runs there, after the flush, in the caller's transaction. So a rollback, such as `compare_tip`'s `session.rollback()` in `api/services/tips.py`, removes the tip with the prediction.
   - **Channel and caller.** The channel is the first MARKSY-type channel. Migration 0181 seeds `Marksy` with no alias, so customer intake can never resolve to it. A `create_all` test DB has no seed, so the channel is created on first use. The caller is `Prediction engine` (seeded by 0181), resolved with `resolve_caller`.
   - **Direction is BUY.** Prediction is long-only:
     - `Prediction` has no direction column (`app/models.py:206-235`);
     - `record_qualifying_recommendation`/`record_ranked_recommendation` "persist a positive recommendation";
     - the monitor tests `low <= stop` and `high >= target` (`prediction_outcome_monitor.py:246-262`);
     - M1.47 rejects `target_return <= 0` and `stop_return >= 0` (`app/target_stop_loss.py`).
   - **Entry and levels.**
     - `entry_low = entry_high = entry_price`.
     - `target = entry × (1 + target_return)` and `stop_loss = entry × (1 + stop_return)`. `stop_return` is negative (M1.47's `downside_percentage = -stop_return`; the tests use −0.03).
     - Both are quantized ROUND_HALF_UP to 6 places, the `tips` column scale, so SQLite and PostgreSQL store the same value.
   - **M1.47's published prices do not win, because they cannot exist yet.** `publish_recommendation` takes the persisted Prediction (`continuous_discovery.py:258`), and it uses the same arithmetic (`target_stop_loss.py:142-143`). Terms are immutable, so a later publication cannot change them.
   - **`horizon_sessions = horizon_days`, and both count trading sessions.** `outcomes.py:98-116` counts session bars, and the monitor uses `len(valid) >= horizon_days`.
     - The monitor counts from the scan session (`as_of_timestamp`), while the tracker counts from `first_seen_at` (§6.1).
     - For the evening scan, which writes after the close, the two are the same.
     - For a call written during market hours, the horizon ends one session later. The part of the session before the call cannot be scored.
   - **`first_seen_at = Prediction.created_at`.** It is the wall-clock instant `record_recommendation` writes the row (`recommendations.py:116`). It is stored and immutable (`IMMUTABLE_FIELDS`). The alternatives are all worse:
     - `now` at registration is the same instant, but it is never stored.
     - `activation_now` is a test clock: it is None in production and never stored.
     - `as_of_timestamp` is the scan anchor (midnight IST). It predates the call, so using it would score hindsight.
     - `RecommendationPublication.published_at` is later, optional and supplied by the caller.

     So a historical re-run (entry-validity `HISTORICAL_RECONSTRUCTION`) is tracked from when it was written, never on the bars it was made from (§6.7 "never start tracking late").
   - **Keys and versions.**
     - `match_key` is v1 on the Marksy canonical channel;
     - `match_key_version = MATCH_KEY_VERSION`;
     - `parser_version` is the new `PARSER_PREDICTION_ENGINE = "ENG-001"`;
     - `entry_basis = STATED` and `horizon_basis = ENGINE`;
     - `prediction_id` is set.
   - **Every recorded Prediction registers, WAIT decisions included.**
     - `recommendations.py:136-139` calls WAIT "a real point-in-time thesis".
     - The §6.3 entry rule decides whether the call was ever takeable: one that never was closes NEVER_ENTERED, unscored.
     - "Published" in §7 is read as "recorded". Registering only ranked or lifecycle-visible predictions would let an unranked call hide (§1).
   - **EPIC-803 columns:**
     - `source` is the channel name;
     - `received_at = first_seen_at`;
     - `tip_as_of = as_of_timestamp`;
     - `comparison_status = RECEIVED`, because the column is non-null. Nothing picks RECEIVED rows up for work: `resolve_due_external_tips` selects COMPARED.

2. **No receipts.** A receipt is "one customer's record of having received it" (§2), and Marksy's call has no customer.
   - A system-user receipt, like §12's `legacy-unknown`, would be a fabricated customer record. It would show as a holder in `/admin/tips/{id}/receipts` and count the tip in that user's customer scorecard.
   - Scorecards never read receipts (invariant 4), and `TipSourceExit.receipt_id` is already nullable.
   - What readers assume:
     - A tip with `prediction_id` has no receipts by design, so tip→receipt joins are outer joins (Phase 3).
     - §9's "alert every receipt holder" alerts nobody for a Marksy tip. Its users hear through the existing recommendation alerts.

3. **Duplicates become MERGED_DUPLICATE.** Registration takes the same `pg_advisory_xact_lock` as intake, then asks `find_matchable` (an ACTIVE tip, or one rejected at intake inside its horizon).
   - When a matchable tip exists, the new tip is kept as `MERGED_DUPLICATE`, with `merged_into_tip_id`, its own `prediction_id` and `closed_at = first_seen_at`. So the partial unique index never fires, `record_recommendation` never raises, and the scorecard counts the call once (§5.2 merging).
   - Its prediction follows the canonical tip: `sync_prediction_from_tip` closes it when that tip closes.
   - **How often.** Rarely. Discovery skips stocks that already have an OPEN prediction (`continuous_opportunity.py:134-147`). A collision needs a second call on the same stock with the same entry (to the 0.05 tick), levels and horizon while the first tip is ACTIVE. That means a call re-made from the same session's data:
     - an on-demand tip comparison (`api/services/tips.py:486-531`), which can re-make Marksy's view for every external tip on a stock;
     - a manual reanalysis;
     - the unlinked-revision path (`tests/test_epic366_prediction_lifecycle.py:847-850`, which put 418 rows into production);
     - or a new call in the minutes between a withdrawal and the next tracker run. There the follower ends with the withdrawn call.

4. **Intake rejections.** A call that fails §6.2 is kept as its UNSCORABLE (`INCONSISTENT_LEVELS`) tip, with `closed_at = first_seen_at`, for audit.
   - `record_recommendation` then calls `sync_prediction_from_tip` at once. That moves the prediction to INVALIDATED and appends an INVALIDATED event with `tip_reason`.
   - Otherwise, once the monitor's price checks retire, it would be an OPEN prediction that nothing tracks.
   - It is reachable today: entry validity returns WAIT for a missing observation before it looks at the target's sign (`entry_validity.py:258-272`). For example, `tests/test_target_stop_loss.py:115` records `stop_return=0`.
   - `UNRESOLVED_SYMBOL` cannot occur: `stock_id` is a foreign key.

5. **Withdrawals become source exits.**
   - **What gets written.** `record_invalidation` (the EPIC-853 confirmation, via `provisional_confirmation._invalidate`) and the decay branch of `evaluate_prediction_realtime` each call `withdraw_prediction`. It writes `TipSourceExit(receipt_id=None, stated_exit_price=None, exit_seen_at=at)`: `at` for `record_invalidation`, and `as_of` for decay, the instant the model decides.
     - It writes only for an ACTIVE tip.
     - The first exit per tip wins (`uq_tip_source_exit_tip`).
   - **What stays the same.** Phase 0's immediate `Prediction.status = INVALIDATED` and the INVALIDATED event are still written. The OPEN readers drop the call at withdrawal, and `/predictions/active` shows INVALIDATED at once; the tracker scores it at its next run.
   - **No stated price.** The engine's own price would be its last observation, possibly a stale close. The tracker prices the exit under §6.5 and 2a decision 7: the minute bar at the exit, or else the next open.
   - **A withdrawal before entry** (the 08:30 confirmation, before session 1) closes INVALIDATED (`SOURCE_EXIT_BEFORE_ENTRY`, 2a decision 6) with no outcome. Nobody could have taken the call, so there was no loss to hide.
   - **A MERGED_DUPLICATE prediction's withdrawal writes no exit.** The call it duplicates still stands through its first prediction and is still scored.

6. **Revisions.** Spec invariant 6 is confirmed: "A revised tip is a new tip. The original is tracked on its own terms to its own end."
   - `create_recommendation_revision` is the one place both revision paths meet: REVISED in `provisional_confirmation.py:410`, and reassessment in `prediction_reassessment.py:345`. It calls `link_revision`, which sets the revised tip's `revises_tip_id` to the previous prediction's canonical tip. `revises_tip_id` is not in `TERM_FIELDS`, so the guard allows it.
   - The original tip is not exited. Its prediction stays SUPERSEDED, because the sync moves only OPEN. It still gets the tracker's terminal event.
     - `present()` already puts a terminal event above SUPERSEDED.
     - Reassessment already resolves superseded versions ("Resolution above still runs for it", `prediction_reassessment.py:397-399`).

7. **Status sync and the monitor's event.** When a tip closes, `sync_prediction_from_tip` updates its prediction and every MERGED_DUPLICATE follower.
   - **Status.** It moves `Prediction.status` only from OPEN, the same guard `_close_invalidated` uses:
     - TARGET_HIT, STOP_LOSS_HIT, HORIZON_EXPIRED and DIRECTION_HORIZON → `EVALUATED`;
     - SOURCE_EXIT, INVALIDATED, UNSCORABLE and DATA_UNRESOLVED → `INVALIDATED`.

     No reader tells EVALUATED and INVALIDATED apart on `Prediction.status` (grep: the only other `"EVALUATED"` constants are lifecycle states). Every reader asks OPEN or not, so each terminal status only has to leave OPEN.
     - SOURCE_EXIT maps to the value the withdrawal already set.
     - A TARGET_HIT on session 1 now leaves the OPEN population then, not at horizon end. That is §7's sync, and discovery may cover that stock again sooner.
   - **Event.** It appends the monitor's terminal `PredictionOutcomeEvent` unless one exists; the first terminal event stands.
     - TARGET_HIT, STOP_LOSS_HIT and HORIZON_EXPIRED keep their names. DIRECTION_HORIZON becomes HORIZON_EXPIRED. Every other status becomes INVALIDATED.
     - Fields: `observed_at = closed_at`, `observed_price = exit_price`, `monitor_rule_version = "TIP-SYNC-001"`, and evidence `trigger/tip_id/tip_status/tip_reason`.
     - This keeps `/predictions/active` (`_latest_status`, `api/services/predictions_active.py:168-175`) and the `/instruments` lifecycle (`present()` → `get_terminal_event`, `app/prediction_presentation.py:105`) working once the monitor stops writing price events.
   - **Hook.** `_TipTracking._close` is the single place every tracker close passes through (target, stop, horizon, source exit, void, MISSING_BARS, NO_INTRADAY_BARS). It calls the sync when `tip.prediction_id` is set. The sync never commits; `track_tips` commits per tip.

8. **The monitor's price checks retire for registered predictions only.** `evaluate_prediction_realtime` still runs the decay branch for every prediction. It returns before the price checks and the stale-data event when the prediction has a tip. Predictions without a tip (every one recorded before this phase) keep today's behaviour. No data migration registers the currently OPEN ones:
   - §12 puts "Existing Marksy predictions are registered as tips" in the Phase 6 backfill, with "Replay and backfill never start tracking late".
   - Those predictions already have append-only monitor events. A replay under §6.3's entry rule (which the monitor lacks) can disagree with them, for example NEVER_ENTERED against a recorded TARGET_HIT. That would leave the ledger and the first terminal event contradicting each other.
   - A migration must not import app code (0181: "a migration must replay identically after that module changes"), so it would have to copy registration and match_key.
   - Horizons are at most 7 sessions (`VALID_HORIZON_DAYS`), so the legacy path drains within 7 sessions of deploy.
   - No OPEN prediction is left untracked:
     - a legacy prediction keeps the monitor;
     - an ACTIVE tip has the tracker;
     - a tip closed at birth is synced at birth;
     - a follower follows its canonical tip.

9. **EPIC-803 readers.** `GET /tips` (`list_tips`) lists every `tips` row, so it now filters to `prediction_id IS NULL`. The other readers already cannot see Marksy tips:
   - `resolve_due_external_tips` selects COMPARED;
   - `source_scorecard` joins `ExternalTipOutcome`;
   - the dashboard's `_compared_tips` needs a comparison.

10. **`app/outcomes.py` stays a writer of `Prediction.status`.** It sets EVALUATED at horizon end for the learning labels, and its population comes from lifecycle rows (`lifecycle.py:384`, `counterfactual_analysis.py:76`), not from `Prediction.status`. So an early sync never skips a label. It is left unchanged, because it never re-opens a call, which is all the OPEN readers can see.

11. **Test expectations that change, and why.**
    - `tests/test_prediction_outcome_monitor.py`: `make_prediction` builds a Prediction directly, with no tip. Its price-check tests describe the legacy path, which only pre-2b predictions take now.
    - `tests/test_epic853_confirmation.py::test_a_provisional_high_never_decides_a_target_hit`: the scan's predictions are registered, so the test asserts through `track_tips`. The provisional pass never closes; the official pass closes and writes TARGET_HIT.
    - `tests/test_epic366_prediction_lifecycle.py::test_an_unlinked_revision_still_resolves_through_the_outcome_monitor`: the orphan is registered, so it resolves when a tip closes. The test closes the tip the orphan follows, syncs, and checks that the unlinked-revision guard still lets reassessment resolve it.
    - `tests/test_api_tips.py:426` and `:438`: `_make_published_recommendation` now also writes a Marksy tip, so both counts are scoped to external tips (`prediction_id IS NULL`).

## File Structure

- Modify `app/tip_vocabulary.py`: add `MARKSY_CHANNEL_NAME`, `CALLER_PREDICTION_ENGINE` and `PARSER_PREDICTION_ENGINE`.
- Modify `app/models.py`: add `uq_tips_prediction_id` to `Tip.__table_args__`.
- Create `migrations/versions/0183_tips_prediction_unique.py`.
- Create `app/marksy_tips.py`: registration, lookup, status mapping, withdrawal and the revision link.
- Modify `app/prediction_outcome_monitor.py`:
  - `sync_prediction_from_tip`;
  - withdrawals in `record_invalidation` (lines 184-211) and in the decay branch (lines 290-307);
  - the price-check guard before line 309.
- Modify `app/tip_tracking_job.py`: the sync hook in `_TipTracking._close` (lines 255-261).
- Modify `app/recommendations.py`: register and sync after `record_activation_decision` (line 140).
- Modify `app/recommendation_revision.py`: `link_revision` before the commit in `create_recommendation_revision` (lines 305-308).
- Modify `api/services/tips.py`: `list_tips` (line 888) lists external tips only.
- Tests:
  - Create `tests/test_tip_prediction_link_migration.py` and `tests/test_marksy_prediction_tips.py`.
  - Modify `tests/test_prediction_outcome_monitor.py`, `tests/test_epic853_confirmation.py`, `tests/test_epic366_prediction_lifecycle.py` and `tests/test_api_tips.py`.

---

### Task 1: Worktree, vocabulary, and one tip per prediction (ORM + migration 0183)

**Files:**
- Modify: `app/tip_vocabulary.py`
- Modify: `app/models.py` (`Tip.__table_args__`)
- Create: `migrations/versions/0183_tips_prediction_unique.py`
- Test: `tests/test_tip_prediction_link_migration.py`

**Interfaces:**
- Produces, in `app/tip_vocabulary.py`: `MARKSY_CHANNEL_NAME = "Marksy"`, `CALLER_PREDICTION_ENGINE = "Prediction engine"` and `PARSER_PREDICTION_ENGINE = "ENG-001"`.
- Produces: the unique index `uq_tips_prediction_id` on `tips(prediction_id)`. NULLs are distinct on both dialects.

- [ ] **Step 1: Create the worktree**

```bash
cd /c/AIAgent/marksy-api && git fetch origin && git worktree add ../marksy-api-phase2b -b feat/tip-ledger-marksy-predictions origin/main
cd /c/AIAgent/marksy-api-phase2b && git log --oneline -1 && ls migrations/versions/*.py | tail -1 && ls app/tip_tracking_job.py
```

Expected:
- HEAD is the Phase 2a merge commit (`Merge pull request … feat/tip-tracker`).
- The last migration is `migrations/versions/0182_tip_daily_progress.py`.
- `app/tip_tracking_job.py` exists.

All later commands run in `C:\AIAgent\marksy-api-phase2b`.

- [ ] **Step 2: Write the failing migration test**

Create `tests/test_tip_prediction_link_migration.py`:

```python
"""0183_tips_prediction_unique on SQLite: a prediction is at most one tip; tips without one are unaffected."""
from __future__ import annotations

import pytest
import sqlalchemy as sa
from sqlalchemy.exc import IntegrityError

from tests._migration_helpers import run_revision


@pytest.fixture
def engine():
    engine = sa.create_engine("sqlite:///:memory:")
    with engine.begin() as conn:
        conn.execute(sa.text("CREATE TABLE tips (id INTEGER PRIMARY KEY, prediction_id INTEGER)"))
        run_revision(conn, "0183_tips_prediction_unique")
    return engine


def _tip(conn, prediction_id):
    conn.execute(sa.text("INSERT INTO tips (prediction_id) VALUES (:prediction_id)"), {"prediction_id": prediction_id})


def test_a_prediction_is_at_most_one_tip(engine):
    with engine.begin() as conn:
        _tip(conn, 7)
        _tip(conn, None)
        _tip(conn, None)
    with pytest.raises(IntegrityError), engine.begin() as conn:
        _tip(conn, 7)


def test_downgrade_drops_the_index(engine):
    with engine.begin() as conn:
        run_revision(conn, "0183_tips_prediction_unique", "downgrade")
        assert "uq_tips_prediction_id" not in {index["name"] for index in sa.inspect(conn).get_indexes("tips")}
```

- [ ] **Step 3: Run it to verify it fails**

Run: `python -m pytest tests/test_tip_prediction_link_migration.py -v`
Expected: FAIL with `FileNotFoundError` for `migrations/versions/0183_tips_prediction_unique.py`.

- [ ] **Step 4: Write the migration**

Create `migrations/versions/0183_tips_prediction_unique.py`:

```python
"""Tip ledger Phase 2b: a Marksy prediction is at most one tip (tip-ledger spec §7).

Revision ID: 0183_tips_prediction_unique
Revises: 0182_tip_daily_progress
"""
from __future__ import annotations

from alembic import op

revision = "0183_tips_prediction_unique"
down_revision = "0182_tip_daily_progress"
branch_labels = None
depends_on = None


def upgrade() -> None:
    # NULLs are distinct on SQLite and PostgreSQL, so external tips (no prediction) are unaffected.
    op.create_index("uq_tips_prediction_id", "tips", ["prediction_id"], unique=True)


def downgrade() -> None:
    op.drop_index("uq_tips_prediction_id", table_name="tips")
```

- [ ] **Step 5: Add the ORM index and the vocabulary**

In `app/models.py`, `class Tip`, add the last entry of `__table_args__`, directly after `Index("ix_tips_channel_symbol", "source_channel_id", "symbol"),`:

```python
        # tip-ledger spec §7: a Marksy prediction is at most one tip.
        Index("uq_tips_prediction_id", "prediction_id", unique=True),
```

In `app/tip_vocabulary.py`, directly after `CHANNEL_MARKSY = "MARKSY"`, add:

```python
# Seeded by migration 0181 with the Marksy channel and its callers.
MARKSY_CHANNEL_NAME = "Marksy"
CALLER_PREDICTION_ENGINE = "Prediction engine"
```

Directly after `PARSER_APP_PAYLOAD = "APP-001"`, add:

```python
PARSER_PREDICTION_ENGINE = "ENG-001"
```

- [ ] **Step 6: Reset the pytest file DB and run the tests**

```bash
rm -f "$TEMP/marksy-pytest-default.db"
python -m pytest tests/test_tip_prediction_link_migration.py tests/test_tip_daily_progress_migration.py tests/test_tip_ledger_migration.py tests/test_tip_ledger.py -v
python -m alembic heads
```

Expected: all PASS, and one head: `0183_tips_prediction_unique`.

- [ ] **Step 7: Commit**

```bash
git add app/tip_vocabulary.py app/models.py migrations/versions/0183_tips_prediction_unique.py tests/test_tip_prediction_link_migration.py
git commit -m "Tip ledger: a prediction is at most one tip (migration 0183); Marksy engine vocabulary

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 2: Register a prediction as a Marksy tip (`app/marksy_tips.py`)

**Files:**
- Create: `app/marksy_tips.py`
- Test: `tests/test_marksy_prediction_tips.py`

**Interfaces:**
- Consumes, from `app/tip_ledger.py`:
  - `find_matchable(session, match_key, *, now) -> Tip | None`
  - `_lock_match_key(session, match_key) -> None`
  - `resolve_caller(session, channel_id, name) -> Caller`
- Consumes, from `app/tip_matching.py`:
  - `stated_terms(*, symbol, direction, entry_low, entry_high, target, stop_loss, horizon_sessions) -> StatedTerms`
  - `match_key_v1(canonical_channel_id, terms) -> str`
  - `intake_rejection(terms, *, symbol_resolved) -> tuple[str, str] | None`
  - `MATCH_KEY_VERSION`
- Produces:
  - `marksy_channel(session: Session) -> Channel`
  - `prediction_terms(prediction: Prediction, symbol: str) -> StatedTerms`
  - `tip_for_prediction(session: Session, prediction_id: int) -> Tip | None`
  - `register_prediction(session: Session, prediction: Prediction) -> Tip`. It flushes and never commits. It is idempotent per prediction.

- [ ] **Step 1: Write the failing tests**

Create `tests/test_marksy_prediction_tips.py`:

```python
"""Marksy predictions in the tip ledger (tip-ledger spec §7): registration, status sync, withdrawals, revisions."""
from __future__ import annotations

from datetime import date, datetime, time, timezone
from decimal import Decimal

import pytest
from sqlalchemy import create_engine, select
from sqlalchemy.orm import sessionmaker

from app.db import Base
from app.market_data.quality import NSE_TIMEZONE
from app.marksy_tips import register_prediction, tip_for_prediction
from app.models import Caller, Channel, MarketPrice, Prediction, Stock, Tip, TipReceipt
from app.tip_matching import MATCH_KEY_VERSION, match_key_v1, stated_terms
from app.tip_vocabulary import (
    CALLER_PREDICTION_ENGINE,
    CHANNEL_MARKSY,
    DIRECTION_BUY,
    ENTRY_BASIS_STATED,
    ENTRY_WAITING,
    HORIZON_BASIS_ENGINE,
    PARSER_PREDICTION_ENGINE,
    REASON_INCONSISTENT_LEVELS,
    STATUS_ACTIVE,
    STATUS_MERGED_DUPLICATE,
    STATUS_UNSCORABLE,
)

MON, TUE, WED, THU = date(2026, 9, 28), date(2026, 9, 29), date(2026, 9, 30), date(2026, 10, 1)
RUN_AT = datetime(2026, 10, 5, 3, 15, tzinfo=timezone.utc)


def ist(day, hour, minute=0):
    """`day` at hour:minute IST, as UTC."""
    return datetime(day.year, day.month, day.day, hour, minute, tzinfo=NSE_TIMEZONE).astimezone(timezone.utc)


def aware(value):
    return value if value.tzinfo is not None else value.replace(tzinfo=timezone.utc)


SCAN = ist(MON, 0)
PUBLISHED = ist(MON, 16, 30)


@pytest.fixture
def session():
    engine = create_engine("sqlite:///:memory:")
    Base.metadata.create_all(engine)
    db = sessionmaker(bind=engine)()
    db.add_all([
        Stock(symbol="RELIANCE", exchange="NSE", is_active=True),
        Stock(symbol="TCS", exchange="NSE", is_active=True),
    ])
    db.commit()
    try:
        yield db
    finally:
        db.close()


def _stock(session, symbol="RELIANCE"):
    return session.scalar(select(Stock).where(Stock.symbol == symbol))


def _prediction(session, *, created_at=PUBLISHED, symbol="RELIANCE", entry="100", target_return="0.05",
                stop_return="-0.03", horizon_days=5):
    """A Prediction as `record_recommendation` writes it, published at the test's own time (after the close)."""
    prediction = Prediction(
        stock_id=_stock(session, symbol).id, created_at=created_at, as_of_timestamp=SCAN,
        entry_price=Decimal(entry), horizon_days=horizon_days, target_return=Decimal(target_return),
        stop_return=Decimal(stop_return), predicted_probability=Decimal("0.7"), confidence=Decimal("0.8"),
        model_version="m1-baseline-1", feature_version="f1", consensus_contract_version="c1",
        horizon_selection_version="h1", scoring_contract_version="s1", opportunity_score=Decimal("60.00"),
        status="OPEN",
    )
    session.add(prediction)
    session.flush()
    return prediction


def _registered(session, **overrides):
    prediction = _prediction(session, **overrides)
    tip = register_prediction(session, prediction)
    session.commit()
    return prediction, tip


def _bar(session, day, o, h, l, c, *, symbol="RELIANCE"):
    session.add(MarketPrice(
        stock_id=_stock(session, symbol).id,
        timestamp=datetime.combine(day, time(0), NSE_TIMEZONE).astimezone(timezone.utc), session_date=day,
        open=Decimal(str(o)), high=Decimal(str(h)), low=Decimal(str(l)), close=Decimal(str(c)), volume=1000,
        source="upstox-v3",
    ))
    session.commit()


def test_a_prediction_becomes_a_marksy_tip_on_its_stated_terms(session):
    prediction, tip = _registered(session)

    channel = session.scalar(select(Channel).where(Channel.type == CHANNEL_MARKSY))
    assert (tip.prediction_id, tip.source_channel_id, session.get(Caller, tip.caller_id).name) == (
        prediction.id, channel.id, CALLER_PREDICTION_ENGINE,
    )
    assert (tip.symbol, tip.stock_id, tip.direction) == ("RELIANCE", prediction.stock_id, DIRECTION_BUY)
    assert (tip.entry_low, tip.entry_high, tip.target_price, tip.stop_loss) == (
        Decimal("100"), Decimal("100"), Decimal("105"), Decimal("97"),
    )
    assert (tip.horizon_sessions, tip.entry_basis, tip.horizon_basis) == (5, ENTRY_BASIS_STATED, HORIZON_BASIS_ENGINE)
    assert (tip.status, tip.entry_status, tip.parser_version) == (STATUS_ACTIVE, ENTRY_WAITING, PARSER_PREDICTION_ENGINE)
    assert aware(tip.first_seen_at) == PUBLISHED
    terms = stated_terms(symbol="RELIANCE", direction=DIRECTION_BUY, entry_low=Decimal("100"), entry_high=Decimal("100"),
                         target=Decimal("105"), stop_loss=Decimal("97"), horizon_sessions=5)
    assert (tip.match_key, tip.match_key_version) == (match_key_v1(channel.canonical_channel_id, terms), MATCH_KEY_VERSION)
    assert session.scalars(select(TipReceipt)).all() == []


def test_registration_is_idempotent_and_reuses_one_marksy_channel(session):
    prediction, tip = _registered(session)
    _other, other_tip = _registered(session, symbol="TCS")

    assert register_prediction(session, prediction).id == tip.id
    assert tip_for_prediction(session, prediction.id).id == tip.id
    assert other_tip.source_channel_id == tip.source_channel_id
    assert len(session.scalars(select(Channel).where(Channel.type == CHANNEL_MARKSY)).all()) == 1


def test_an_identical_call_while_the_first_is_active_becomes_a_merged_duplicate(session):
    _first, first_tip = _registered(session)
    second, second_tip = _registered(session, created_at=ist(MON, 17))

    assert (second_tip.status, second_tip.merged_into_tip_id, second_tip.prediction_id) == (
        STATUS_MERGED_DUPLICATE, first_tip.id, second.id,
    )
    assert aware(second_tip.closed_at) == ist(MON, 17)
    assert session.get(Tip, first_tip.id).status == STATUS_ACTIVE


def test_a_call_whose_stop_is_above_its_entry_is_kept_as_unscorable(session):
    _call, tip = _registered(session, stop_return="0.02")

    assert (tip.status, tip.reason, tip.entry_status) == (STATUS_UNSCORABLE, REASON_INCONSISTENT_LEVELS, None)
    assert aware(tip.closed_at) == PUBLISHED
```

- [ ] **Step 2: Run them to verify they fail**

Run: `python -m pytest tests/test_marksy_prediction_tips.py -v`
Expected: FAIL with `ModuleNotFoundError: No module named 'app.marksy_tips'`.

- [ ] **Step 3: Write the registration**

Create `app/marksy_tips.py`:

```python
"""Marksy's own calls in the central tip ledger (tip-ledger spec §7).

Every recorded prediction is a tip of the Marksy channel, tracked and scored by the same §6 rules as any other.
A Marksy tip has no receipts: no customer sent it."""

from __future__ import annotations

import uuid
from datetime import datetime, timezone
from decimal import ROUND_HALF_UP, Decimal

from sqlalchemy import or_, select
from sqlalchemy.orm import Session

from .models import Channel, Prediction, Stock, Tip, TipSourceExit
from .tip_ledger import _lock_match_key, find_matchable, resolve_caller
from .tip_matching import MATCH_KEY_VERSION, StatedTerms, intake_rejection, match_key_v1, stated_terms
from .tip_vocabulary import (
    CALLER_PREDICTION_ENGINE,
    CHANNEL_MARKSY,
    COMPARISON_STATUS_RECEIVED,
    DEFAULT_HORIZON_SESSIONS,
    DIRECTION_BUY,
    ENTRY_BASIS_STATED,
    ENTRY_WAITING,
    HORIZON_BASIS_ENGINE,
    MARKSY_CHANNEL_NAME,
    PARSER_PREDICTION_ENGINE,
    STATUS_ACTIVE,
    STATUS_DIRECTION_HORIZON,
    STATUS_HORIZON_EXPIRED,
    STATUS_MERGED_DUPLICATE,
    STATUS_STOP_LOSS_HIT,
    STATUS_TARGET_HIT,
)

_PRICE_PLACES = Decimal("0.000001")
_SOURCE_MAX = 64


def _aware(value: datetime) -> datetime:
    # SQLite drops tzinfo on a DateTime(timezone=True) round-trip.
    return value if value.tzinfo is not None else value.replace(tzinfo=timezone.utc)


def marksy_channel(session: Session) -> Channel:
    """The MARKSY channel migration 0181 seeds; a `create_all` database gets it on first use."""
    channel = session.scalar(select(Channel).where(Channel.type == CHANNEL_MARKSY).order_by(Channel.id).limit(1))
    if channel is None:
        channel = Channel(
            name=MARKSY_CHANNEL_NAME, type=CHANNEL_MARKSY, default_horizon_sessions=DEFAULT_HORIZON_SESSIONS,
            capture_enabled=False,
        )
        session.add(channel)
        session.flush()
        channel.canonical_channel_id = channel.id
        session.flush()
    return channel


def _level(entry: Decimal, fraction: Decimal) -> Decimal:
    # M1.47's arithmetic (app/target_stop_loss.py), at the tips table's scale so both dialects store one value.
    return (entry * (Decimal(1) + fraction)).quantize(_PRICE_PLACES, rounding=ROUND_HALF_UP)


def prediction_terms(prediction: Prediction, symbol: str) -> StatedTerms:
    """A prediction is a long call: entry at its entry price, levels from its target and stop returns."""
    entry = prediction.entry_price
    return stated_terms(
        symbol=symbol,
        direction=DIRECTION_BUY,
        entry_low=entry,
        entry_high=entry,
        target=_level(entry, prediction.target_return),
        stop_loss=_level(entry, prediction.stop_return),
        horizon_sessions=prediction.horizon_days,
    )


def tip_for_prediction(session: Session, prediction_id: int) -> Tip | None:
    return session.scalar(select(Tip).where(Tip.prediction_id == prediction_id))


def register_prediction(session: Session, prediction: Prediction) -> Tip:
    """The prediction's tip, created in the caller's transaction on first call. An identical call while an earlier
    one is still matchable follows it as MERGED_DUPLICATE; a call rejected at intake is kept for audit (§6.2)."""
    existing = tip_for_prediction(session, prediction.id)
    if existing is not None:
        return existing
    channel = marksy_channel(session)
    caller = resolve_caller(session, channel.id, CALLER_PREDICTION_ENGINE)
    stock = session.get(Stock, prediction.stock_id)
    terms = prediction_terms(prediction, stock.symbol)
    first_seen = _aware(prediction.created_at)
    key = match_key_v1(channel.canonical_channel_id, terms)
    _lock_match_key(session, key)
    canonical = find_matchable(session, key, now=first_seen)
    tip = Tip(
        public_id=str(uuid.uuid4()),
        source=channel.name[:_SOURCE_MAX],
        symbol=terms.symbol,
        stock_id=stock.id,
        direction=terms.direction,
        entry_price=terms.entry_low,
        target_price=terms.target,
        stop_loss=terms.stop_loss,
        horizon_days=prediction.horizon_days,
        received_at=first_seen,
        tip_as_of=prediction.as_of_timestamp,
        comparison_status=COMPARISON_STATUS_RECEIVED,
        source_channel_id=channel.id,
        caller_id=caller.id,
        entry_low=terms.entry_low,
        entry_high=terms.entry_high,
        entry_basis=ENTRY_BASIS_STATED,
        horizon_sessions=terms.horizon_sessions,
        horizon_basis=HORIZON_BASIS_ENGINE,
        first_seen_at=first_seen,
        prediction_id=prediction.id,
        match_key=key,
        match_key_version=MATCH_KEY_VERSION,
        parser_version=PARSER_PREDICTION_ENGINE,
    )
    rejection = intake_rejection(terms, symbol_resolved=True)
    if canonical is not None:
        # tip-ledger spec §2.1: one matchable tip per key, so this call follows the earlier one.
        tip.status, tip.merged_into_tip_id = STATUS_MERGED_DUPLICATE, canonical.id
        tip.closed_at = first_seen
    elif rejection is not None:
        tip.status, tip.reason = rejection
        tip.closed_at = first_seen
    else:
        tip.status, tip.entry_status = STATUS_ACTIVE, ENTRY_WAITING
    session.add(tip)
    session.flush()
    return tip
```

`or_`, `TipSourceExit` and the four scored `STATUS_*` names are imported now so that Tasks 3, 5 and 6 only append code.

- [ ] **Step 4: Run the tests**

Run: `python -m pytest tests/test_marksy_prediction_tips.py tests/test_tip_ledger.py -v`
Expected: all PASS.

- [ ] **Step 5: Commit**

```bash
git add app/marksy_tips.py tests/test_marksy_prediction_tips.py
git commit -m "Tip ledger: register a Marksy prediction as a tip of the Marksy channel (spec 7)

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 3: A closed tip closes its prediction (status sync, monitor event, tracker hook)

**Files:**
- Modify: `app/marksy_tips.py` (append)
- Modify: `app/prediction_outcome_monitor.py` (imports, constants, `sync_prediction_from_tip`)
- Modify: `app/tip_tracking_job.py` (import, `_TipTracking._close`)
- Test: `tests/test_marksy_prediction_tips.py` (append)

**Interfaces:**
- Consumes: `track_tips(session, *, now, minute_bars, provisional=False) -> TrackingRun` (2a). Consumes `get_terminal_event(session, prediction_id)` and `get_target_stop_prices(session, prediction)` (monitor).
- Produces, in `app/marksy_tips.py`:
  - `PREDICTION_EVALUATED = "EVALUATED"` and `PREDICTION_INVALIDATED = "INVALIDATED"`
  - `prediction_status_for(tip_status: str | None) -> str | None`
  - `canonical_tip(session: Session, tip: Tip) -> Tip`
  - `predictions_following(session: Session, tip: Tip) -> list[Prediction]`
- Produces, in `app/prediction_outcome_monitor.py`:
  - `TIP_SYNC_RULE_VERSION = "TIP-SYNC-001"`
  - `sync_prediction_from_tip(session: Session, tip: Tip, *, now: datetime) -> None`. It flushes and never commits.

- [ ] **Step 1: Write the failing tests**

Append to `tests/test_marksy_prediction_tips.py`. Extend its imports:
- from `app.marksy_tips`: `PREDICTION_EVALUATED`, `PREDICTION_INVALIDATED`, `prediction_status_for`
- add `from app.prediction_outcome_monitor import STATE_INVALIDATED, STATE_TARGET_HIT, TIP_SYNC_RULE_VERSION, get_terminal_event`
- add `from app.tip_tracker import session_close`
- add `from app.tip_tracking_job import track_tips`
- from `app.tip_vocabulary`: `OUTCOME_SUCCESS`, `REASON_NEVER_ENTERED`, `STATUS_DATA_UNRESOLVED`, `STATUS_DIRECTION_HORIZON`, `STATUS_HORIZON_EXPIRED`, `STATUS_INVALIDATED`, `STATUS_SOURCE_EXIT`, `STATUS_STOP_LOSS_HIT`, `STATUS_TARGET_HIT`

```python
def test_a_tip_the_tracker_closes_on_its_target_closes_its_prediction_as_evaluated(session):
    prediction, tip = _registered(session)
    _bar(session, TUE, 100.5, 101, 99.5, 100.8)
    _bar(session, WED, 102, 106, 101.5, 105.5)

    track_tips(session, now=RUN_AT, minute_bars=None)

    tip, prediction = session.get(Tip, tip.id), session.get(Prediction, prediction.id)
    assert (tip.status, tip.exit_price, tip.outcome) == (STATUS_TARGET_HIT, Decimal("105"), OUTCOME_SUCCESS)
    assert prediction.status == PREDICTION_EVALUATED
    event = get_terminal_event(session, prediction.id)
    assert (event.state, event.observed_price, event.monitor_rule_version) == (
        STATE_TARGET_HIT, Decimal("105"), TIP_SYNC_RULE_VERSION,
    )
    assert aware(event.observed_at) == session_close(WED)
    assert event.evidence == {
        "trigger": "tip_tracker", "tip_id": tip.id, "tip_status": STATUS_TARGET_HIT, "tip_reason": None,
    }


def test_a_call_never_entered_closes_its_prediction_as_invalidated(session):
    prediction, tip = _registered(session, horizon_days=1)
    _bar(session, TUE, 103, 104, 102, 103.5)

    track_tips(session, now=RUN_AT, minute_bars=None)

    assert (session.get(Tip, tip.id).reason, session.get(Prediction, prediction.id).status) == (
        REASON_NEVER_ENTERED, PREDICTION_INVALIDATED,
    )
    event = get_terminal_event(session, prediction.id)
    assert (event.state, event.observed_price, event.evidence["tip_reason"]) == (
        STATE_INVALIDATED, None, REASON_NEVER_ENTERED,
    )


def test_a_merged_duplicate_prediction_closes_with_the_tip_it_follows(session):
    first, _first_tip = _registered(session)
    second, second_tip = _registered(session, created_at=ist(MON, 17))
    _bar(session, TUE, 100.5, 101, 99.5, 100.8)
    _bar(session, WED, 102, 106, 101.5, 105.5)

    track_tips(session, now=RUN_AT, minute_bars=None)

    assert [session.get(Prediction, p.id).status for p in (first, second)] == [PREDICTION_EVALUATED] * 2
    assert [get_terminal_event(session, p.id).state for p in (first, second)] == [STATE_TARGET_HIT] * 2
    assert session.get(Tip, second_tip.id).status == STATUS_MERGED_DUPLICATE


@pytest.mark.parametrize(
    ("tip_status", "expected"),
    [
        (STATUS_ACTIVE, None),
        (STATUS_MERGED_DUPLICATE, None),
        (STATUS_TARGET_HIT, PREDICTION_EVALUATED),
        (STATUS_STOP_LOSS_HIT, PREDICTION_EVALUATED),
        (STATUS_HORIZON_EXPIRED, PREDICTION_EVALUATED),
        (STATUS_DIRECTION_HORIZON, PREDICTION_EVALUATED),
        (STATUS_SOURCE_EXIT, PREDICTION_INVALIDATED),
        (STATUS_INVALIDATED, PREDICTION_INVALIDATED),
        (STATUS_UNSCORABLE, PREDICTION_INVALIDATED),
        (STATUS_DATA_UNRESOLVED, PREDICTION_INVALIDATED),
    ],
)
def test_every_tip_status_maps_to_a_prediction_status(tip_status, expected):
    assert prediction_status_for(tip_status) == expected
```

- [ ] **Step 2: Run them to verify they fail**

Run: `python -m pytest tests/test_marksy_prediction_tips.py -v`
Expected: FAIL with `ImportError: cannot import name 'PREDICTION_EVALUATED' from 'app.marksy_tips'`.

- [ ] **Step 3: Add the status mapping**

Append to `app/marksy_tips.py`:

```python
PREDICTION_EVALUATED = "EVALUATED"
PREDICTION_INVALIDATED = "INVALIDATED"
_SCORED = frozenset({STATUS_TARGET_HIT, STATUS_STOP_LOSS_HIT, STATUS_HORIZON_EXPIRED, STATUS_DIRECTION_HORIZON})


def prediction_status_for(tip_status: str | None) -> str | None:
    """The `Prediction.status` a terminal tip implies (decision 7); None while the call is still live."""
    if tip_status in (None, STATUS_ACTIVE, STATUS_MERGED_DUPLICATE):
        return None
    return PREDICTION_EVALUATED if tip_status in _SCORED else PREDICTION_INVALIDATED


def canonical_tip(session: Session, tip: Tip) -> Tip:
    if tip.status == STATUS_MERGED_DUPLICATE and tip.merged_into_tip_id is not None:
        return session.get(Tip, tip.merged_into_tip_id)
    return tip


def predictions_following(session: Session, tip: Tip) -> list[Prediction]:
    """The tip's own prediction and those of its MERGED_DUPLICATE followers, oldest first."""
    followers = select(Tip.prediction_id).where(Tip.merged_into_tip_id == tip.id, Tip.prediction_id.is_not(None))
    return list(session.scalars(
        select(Prediction)
        .where(or_(Prediction.id == tip.prediction_id, Prediction.id.in_(followers)))
        .order_by(Prediction.id)
    ))
```

- [ ] **Step 4: Add the sync to the monitor**

In `app/prediction_outcome_monitor.py`:
- Replace `from .models import MarketPrice, Prediction, PredictionOutcomeEvent` with the three imports below.
- Place `marksy_tips` before `models`, and `tip_vocabulary` after `target_stop_loss`:

```python
from .marksy_tips import canonical_tip, prediction_status_for, predictions_following
from .models import MarketPrice, Prediction, PredictionOutcomeEvent, Tip
from .tip_vocabulary import (
    STATUS_DIRECTION_HORIZON as TIP_DIRECTION_HORIZON,
    STATUS_HORIZON_EXPIRED as TIP_HORIZON_EXPIRED,
    STATUS_STOP_LOSS_HIT as TIP_STOP_LOSS_HIT,
    STATUS_TARGET_HIT as TIP_TARGET_HIT,
)
```

Directly after `PREDICTION_STATUS_INVALIDATED = "INVALIDATED"`, add:

```python
TIP_SYNC_RULE_VERSION = "TIP-SYNC-001"
# A closed tip with no monitor twin was tracked but never scored, which the monitor spells INVALIDATED.
_TIP_TO_MONITOR_STATE = {
    TIP_TARGET_HIT: STATE_TARGET_HIT,
    TIP_STOP_LOSS_HIT: STATE_STOP_LOSS_HIT,
    TIP_HORIZON_EXPIRED: STATE_HORIZON_EXPIRED,
    TIP_DIRECTION_HORIZON: STATE_HORIZON_EXPIRED,
}
```

Directly after `record_invalidation` (it ends with `return event_row`), add:

```python
def sync_prediction_from_tip(session: Session, tip: Tip, *, now: datetime) -> None:
    """tip-ledger spec §7: a terminal tip closes every prediction following it. Only OPEN moves, and an existing
    terminal event stands, so a withdrawal, a revision or the end-of-horizon batch is never overwritten."""
    canonical = canonical_tip(session, tip)
    status = prediction_status_for(canonical.status)
    if status is None:
        return
    state = _TIP_TO_MONITOR_STATE.get(canonical.status, STATE_INVALIDATED)
    for prediction in predictions_following(session, canonical):
        if prediction.status == PREDICTION_STATUS_OPEN:
            prediction.status = status
        if get_terminal_event(session, prediction.id) is not None:
            continue
        _, _, prediction_version = get_target_stop_prices(session, prediction)
        session.add(PredictionOutcomeEvent(
            prediction_id=prediction.id,
            state=state,
            detected_at=now,
            observed_at=canonical.closed_at,
            observed_price=canonical.exit_price,
            provider=None,
            prediction_version=prediction_version,
            evidence={
                "trigger": "tip_tracker",
                "tip_id": canonical.id,
                "tip_status": canonical.status,
                "tip_reason": canonical.reason,
            },
            monitor_rule_version=TIP_SYNC_RULE_VERSION,
        ))
    session.flush()
```

- [ ] **Step 5: Hook it into the tracker's close**

In `app/tip_tracking_job.py`, directly after `from .models import MarketPrice, Stock, Tip, TipDailyProgress, TipSourceExit`, add:

```python
from .prediction_outcome_monitor import sync_prediction_from_tip
```

In `_TipTracking._close`, replace:

```python
        tip.outcome = outcome_for(status, actual_return)
        self.run.closed[status] = self.run.closed.get(status, 0) + 1
```

with:

```python
        tip.outcome = outcome_for(status, actual_return)
        if tip.prediction_id is not None:
            sync_prediction_from_tip(self.session, tip, now=self.now)
        self.run.closed[status] = self.run.closed.get(status, 0) + 1
```

- [ ] **Step 6: Run the tests**

Run: `python -m pytest tests/test_marksy_prediction_tips.py tests/test_tip_tracking_job.py tests/test_prediction_outcome_monitor.py -v`
Expected: all PASS. External tips have no `prediction_id`, so `test_tip_tracking_job.py` is unchanged.

- [ ] **Step 7: Commit**

```bash
git add app/marksy_tips.py app/prediction_outcome_monitor.py app/tip_tracking_job.py tests/test_marksy_prediction_tips.py
git commit -m "Tip ledger: a tip the tracker closes closes its prediction and writes the monitor's terminal event

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 4: The choke point: `record_recommendation` registers every prediction; `/tips` lists external tips only

**Files:**
- Modify: `app/recommendations.py` (imports, after `record_activation_decision`)
- Modify: `api/services/tips.py` (`list_tips`)
- Modify: `tests/test_api_tips.py` (two count assertions, decision 11)
- Test: `tests/test_marksy_prediction_tips.py` (append)

**Interfaces:**
- Consumes: `register_prediction(session, prediction) -> Tip` (Task 2) and `sync_prediction_from_tip(session, tip, *, now) -> None` (Task 3).
- Produces: `record_recommendation(...)`, with its signature unchanged. It returns a Prediction that has a tip. The prediction is OPEN unless its tip was closed at birth, in which case it is INVALIDATED.
- Produces: `list_tips(session, query) -> TipPage`, with its signature unchanged. It returns only tips without a `prediction_id`.

- [ ] **Step 1: Write the failing tests**

Append to `tests/test_marksy_prediction_tips.py`. Extend its imports:
- add `from api.services.tips import TipQuery, list_tips`
- add `from app.recommendations import record_recommendation`
- add `from app.tip_ledger import Intake, ReceiptInput, record_intake`
- from `app.models`: `ChannelAlias`
- from `app.tip_vocabulary`: `ALIAS_PACKAGE`, `CHANNEL_BROKER_APP`, `KIND_TIP`, `MEDIUM_APP_NOTIFICATION`

```python
def _record(session, **overrides):
    kwargs = dict(
        stock_id=_stock(session).id, as_of_timestamp=SCAN, entry_price=Decimal("100"), horizon_days=5,
        target_return=Decimal("0.05"), stop_return=Decimal("-0.03"), predicted_probability=Decimal("0.7"),
        confidence=Decimal("0.8"), model_version="m1-baseline-1", feature_version="f1",
        consensus_contract_version="c1", horizon_selection_version="h1", scoring_contract_version="s1",
        opportunity_score=Decimal("60.00"),
    )
    kwargs.update(overrides)
    prediction = record_recommendation(session, **kwargs)
    session.commit()
    return prediction


def test_record_recommendation_registers_the_prediction_as_it_is_written(session):
    prediction = _record(session)

    tip = tip_for_prediction(session, prediction.id)
    assert (tip.status, aware(tip.first_seen_at)) == (STATUS_ACTIVE, aware(prediction.created_at))
    assert prediction.status == "OPEN"


def test_a_prediction_rejected_at_intake_is_closed_at_birth(session):
    prediction = _record(session, stop_return=Decimal("0.02"))

    assert tip_for_prediction(session, prediction.id).status == STATUS_UNSCORABLE
    assert session.get(Prediction, prediction.id).status == PREDICTION_INVALIDATED
    assert get_terminal_event(session, prediction.id).evidence["tip_reason"] == REASON_INCONSISTENT_LEVELS


def test_marksy_tips_are_not_listed_as_external_tips(session):
    upstox = Channel(name="Upstox", type=CHANNEL_BROKER_APP, default_horizon_sessions=20, capture_enabled=True)
    session.add(upstox)
    session.flush()
    upstox.canonical_channel_id = upstox.id
    session.add(ChannelAlias(channel_id=upstox.id, alias="com.upstox.pro", kind=ALIAS_PACKAGE))
    session.commit()
    receipt = ReceiptInput(
        user_id="user-1", device_event_key="e-1", medium=MEDIUM_APP_NOTIFICATION, app_package="com.upstox.pro",
        channel_label="Upstox", text="BUY TCS", device_posted_at=None, parser_version="TCP-001",
    )
    terms = stated_terms(symbol="TCS", direction="BUY", entry_low=Decimal("3000"), entry_high=Decimal("3000"),
                         target=Decimal("3300"), stop_loss=Decimal("2900"), horizon_sessions=None)
    record_intake(session, receipt, Intake(kind=KIND_TIP, terms=terms), now=PUBLISHED)
    _registered(session)

    assert [item.symbol for item in list_tips(session, TipQuery()).items] == ["TCS"]
```

- [ ] **Step 2: Run them to verify they fail**

Run: `python -m pytest tests/test_marksy_prediction_tips.py -v -k "record_recommendation or closed_at_birth or not_listed"`
Expected: FAIL.
- The first two fail with `AttributeError: 'NoneType' object has no attribute 'status'`, because no tip was registered.
- The listing test fails with `['RELIANCE', 'TCS'] != ['TCS']`.

- [ ] **Step 3: Register at the choke point**

In `app/recommendations.py`, replace `from .models import Prediction, Stock` with:

```python
from .marksy_tips import register_prediction
from .models import Prediction, Stock
from .prediction_outcome_monitor import sync_prediction_from_tip
```

At the end of `record_recommendation`, replace:

```python
    record_activation_decision(session, decision, prediction_id=recommendation.id)
    return recommendation
```

with:

```python
    record_activation_decision(session, decision, prediction_id=recommendation.id)
    # tip-ledger spec §7: every recorded call is a ledger tip; one rejected at intake (§6.2) closes at birth.
    sync_prediction_from_tip(session, register_prediction(session, recommendation), now=recommendation.created_at)
    return recommendation
```

- [ ] **Step 4: Keep the EPIC-803 list external**

In `api/services/tips.py`, `list_tips`, replace:

```python
    statement = select(ExternalTip).order_by(ExternalTip.received_at.desc(), ExternalTip.id.desc())
    if query.source:
```

with:

```python
    # tip-ledger spec §7: Marksy's own calls share the table; this EPIC-803 list is external tips only.
    statement = (
        select(ExternalTip)
        .where(ExternalTip.prediction_id.is_(None))
        .order_by(ExternalTip.received_at.desc(), ExternalTip.id.desc())
    )
    if query.source:
```

- [ ] **Step 5: Scope the two tip counts in `tests/test_api_tips.py` to external tips**

Two tests call `_make_published_recommendation`, which now also writes a Marksy tip:
- `test_a_repeat_source_reference_returns_the_same_tip_and_creates_no_second_row`;
- `test_two_identical_reference_less_calls_from_one_caller_are_one_tip`.

In both, replace the line

```python
    assert session.query(ExternalTip).count() == 1
```

with

```python
    assert session.query(ExternalTip).filter(ExternalTip.prediction_id.is_(None)).count() == 1
```

These are the only two occurrences in the file.

- [ ] **Step 6: Check imports, then run the tests**

```bash
python -c "import app.recommendations, app.tip_tracking_job, app.provisional_confirmation, app.prediction_reassessment, api.services.tips"
python -m pytest tests/test_marksy_prediction_tips.py tests/test_api_tips.py tests/test_target_stop_loss.py \
  tests/test_position_risk_assessment.py tests/test_epic365_fresh_entry_activation.py tests/test_recommendation_history.py \
  tests/test_invalidated_status_migration.py tests/test_prediction_outcome_monitor.py -v
```

Expected:
- The import line prints nothing. That means there is no circular import between `recommendations`, `prediction_outcome_monitor`, `marksy_tips` and `tip_ledger`.
- All tests PASS. `test_target_stop_loss.py` records `target_return=0` and `stop_return=0`; those predictions are now closed at birth, and the publication assertions are unaffected.

- [ ] **Step 7: Commit**

```bash
git add app/recommendations.py api/services/tips.py tests/test_api_tips.py tests/test_marksy_prediction_tips.py
git commit -m "Tip ledger: record_recommendation registers every prediction as a Marksy tip; /tips lists external tips only

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 5: Withdrawals are source exits

**Files:**
- Modify: `app/marksy_tips.py` (append `withdraw_prediction`)
- Modify: `app/prediction_outcome_monitor.py` (`record_invalidation`, the decay branch of `evaluate_prediction_realtime`, imports)
- Test: `tests/test_marksy_prediction_tips.py` (append)

**Interfaces:**
- Produces: `withdraw_prediction(session: Session, prediction: Prediction, *, at: datetime) -> TipSourceExit | None`. It flushes and never commits.
  - It writes an exit only for an ACTIVE tip that has no exit yet.
  - It returns None for a prediction without a tip, a MERGED_DUPLICATE follower, or a closed tip.
- Consumes: `record_invalidation(session, prediction, *, at, evidence)` and `evaluate_prediction_realtime(session, prediction, *, as_of, holiday_dates=frozenset())`. Both signatures are unchanged.

**Rules this task implements:**
- §7, verbatim: "Marksy withdrawing its own prediction … is a source exit: SOURCE_EXIT at the price then."
- §6.5, as 2a implemented it: an exit outside market hours takes the next open. An exit before entry is INVALIDATED (`SOURCE_EXIT_BEFORE_ENTRY`).

- [ ] **Step 1: Write the failing tests**

Append to `tests/test_marksy_prediction_tips.py`. Extend its imports:
- change `from datetime import date, datetime, time, timezone` to `from datetime import date, datetime, time, timedelta, timezone`
- add `from app.evidence_snapshot import EVIDENCE_CATEGORY_NEWS, STATUS_AVAILABLE`
- from `app.models`: `RecommendationEvidenceItem`, `TipSourceExit`
- from `app.prediction_outcome_monitor`: `evaluate_prediction_realtime`, `get_event_history`, `record_invalidation`
- from `app.tip_vocabulary`: `OUTCOME_FAILURE`, `REASON_SOURCE_EXIT_BEFORE_ENTRY`

```python
CONFIRMATION = {"trigger": "official_bar_confirmation"}


def test_withdrawing_an_entered_losing_call_scores_a_failed_source_exit(session):
    prediction, tip = _registered(session)
    _bar(session, TUE, 100.5, 101, 99.5, 100.2)
    _bar(session, WED, 100, 100.5, 98, 98.5)
    record_invalidation(session, prediction, at=ist(WED, 18), evidence=CONFIRMATION)
    _bar(session, THU, 97.5, 98, 97.2, 97.8)

    track_tips(session, now=RUN_AT, minute_bars=None)

    tip = session.get(Tip, tip.id)
    assert (tip.status, tip.exit_price, tip.actual_return, tip.outcome) == (
        STATUS_SOURCE_EXIT, Decimal("97.5"), Decimal("-0.025"), OUTCOME_FAILURE,
    )
    assert aware(tip.closed_at) == ist(WED, 18)
    assert session.get(Prediction, prediction.id).status == PREDICTION_INVALIDATED
    assert [event.state for event in get_event_history(session, prediction.id)] == [STATE_INVALIDATED]


def test_an_invalidation_before_the_first_open_voids_the_unentered_call(session):
    prediction, tip = _registered(session)
    record_invalidation(session, prediction, at=ist(TUE, 8, 30), evidence=CONFIRMATION)
    _bar(session, TUE, 100.5, 101, 99.5, 100.2)

    track_tips(session, now=RUN_AT, minute_bars=None)

    tip = session.get(Tip, tip.id)
    assert (tip.status, tip.reason, tip.outcome, tip.actual_return) == (
        STATUS_INVALIDATED, REASON_SOURCE_EXIT_BEFORE_ENTRY, None, None,
    )
    assert session.get(Prediction, prediction.id).status == PREDICTION_INVALIDATED


def test_model_decay_withdraws_the_call_at_the_moment_it_decides(session):
    prediction, tip = _registered(session)
    session.add(RecommendationEvidenceItem(
        prediction_id=prediction.id, evidence_category=EVIDENCE_CATEGORY_NEWS, status=STATUS_AVAILABLE, source="test",
        reference=None, evidence_timestamp=SCAN - timedelta(hours=12), is_stale=False, snapshot_rule_version="EVS-001",
        captured_at=SCAN,
    ))
    session.commit()

    event = evaluate_prediction_realtime(session, prediction, as_of=SCAN + timedelta(days=3))

    exit_row = session.scalar(select(TipSourceExit).where(TipSourceExit.tip_id == tip.id))
    assert event.state == STATE_INVALIDATED
    assert (aware(exit_row.exit_seen_at), exit_row.stated_exit_price, exit_row.receipt_id) == (
        SCAN + timedelta(days=3), None, None,
    )


def test_withdrawing_a_merged_duplicate_leaves_the_call_it_follows_running(session):
    _first, first_tip = _registered(session)
    second, _second_tip = _registered(session, created_at=ist(MON, 17))

    record_invalidation(session, second, at=ist(TUE, 8, 30), evidence=CONFIRMATION)

    assert session.scalars(select(TipSourceExit)).all() == []
    assert session.get(Tip, first_tip.id).status == STATUS_ACTIVE
    assert session.get(Prediction, second.id).status == PREDICTION_INVALIDATED
```

- [ ] **Step 2: Run them to verify they fail**

Run: `python -m pytest tests/test_marksy_prediction_tips.py -v -k "withdraw or invalidation_before or decay"`
Expected: FAIL.
- The two tracker tests fail on their first assertion, because the tip is still ACTIVE.
- The decay test fails with `AttributeError: 'NoneType' object has no attribute 'exit_seen_at'`.
- The merged-duplicate test already passes, because nothing writes exits yet. It guards Step 3.

- [ ] **Step 3: Write the withdrawal**

Append to `app/marksy_tips.py`:

```python
def withdraw_prediction(session: Session, prediction: Prediction, *, at: datetime) -> TipSourceExit | None:
    """tip-ledger spec §7: Marksy withdrawing a call is a source exit the tracker prices (§6.5), so a losing call
    still scores. A follower's call still stands, and a closed tip has nothing to exit; the first exit wins."""
    tip = tip_for_prediction(session, prediction.id)
    if tip is None or tip.status != STATUS_ACTIVE:
        return None
    if session.scalar(select(TipSourceExit.id).where(TipSourceExit.tip_id == tip.id)) is not None:
        return None
    exit_row = TipSourceExit(tip_id=tip.id, receipt_id=None, stated_exit_price=None, exit_seen_at=at)
    session.add(exit_row)
    session.flush()
    return exit_row
```

- [ ] **Step 4: Emit the exit from both withdrawal paths**

In `app/prediction_outcome_monitor.py`, change the `marksy_tips` import to:

```python
from .marksy_tips import canonical_tip, prediction_status_for, predictions_following, withdraw_prediction
```

In `record_invalidation` (4-space indent), replace:

```python
    session.add(event_row)
    _close_invalidated(prediction)
    session.commit()
```

with:

```python
    session.add(event_row)
    _close_invalidated(prediction)
    # tip-ledger spec §7: a withdrawal is a source exit, so the call is still scored.
    withdraw_prediction(session, prediction, at=at)
    session.commit()
```

In the decay branch of `evaluate_prediction_realtime` (8-space indent, inside `if decay.verdict == VERDICT_MATERIAL_DECAY and decay.invalidation_recommended:`), replace:

```python
        session.add(event_row)
        _close_invalidated(prediction)
        session.commit()
```

with:

```python
        session.add(event_row)
        _close_invalidated(prediction)
        # tip-ledger spec §7: a withdrawal is a source exit, so the call is still scored.
        withdraw_prediction(session, prediction, at=as_of)
        session.commit()
```

- [ ] **Step 5: Run the tests**

Run: `python -m pytest tests/test_marksy_prediction_tips.py tests/test_prediction_outcome_monitor.py tests/test_epic853_confirmation.py tests/test_epic853_invalidated_on_next_run.py -v`
Expected: all PASS. Task 4 made the scan's predictions registered, so `test_a_call_that_no_longer_qualifies_on_the_official_candle_is_invalidated_not_edited` now also writes an exit per invalidated call; its assertions are unchanged.

- [ ] **Step 6: Commit**

```bash
git add app/marksy_tips.py app/prediction_outcome_monitor.py tests/test_marksy_prediction_tips.py
git commit -m "Tip ledger: Marksy withdrawing a prediction is a source exit, never a dropped call (spec 7)

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 6: A revision links its new tip; the superseded call runs to its own end

**Files:**
- Modify: `app/marksy_tips.py` (append `link_revision`)
- Modify: `app/recommendation_revision.py` (import, `create_recommendation_revision`)
- Test: `tests/test_marksy_prediction_tips.py` (append)

**Interfaces:**
- Produces: `link_revision(session: Session, previous: Prediction, revised: Prediction) -> None`. It sets the revised tip's `revises_tip_id` once and never exits the original.
- Consumes: `create_recommendation_revision(session, *, original_prediction, previous_prediction, revised_prediction, revision_reason, revised_at, triggering_evidence_revalidation_check_id=None)`. The signature is unchanged.

- [ ] **Step 1: Write the failing test**

Append to `tests/test_marksy_prediction_tips.py`. Extend its imports:
- add `from app.recommendation_revision import REASON_MATERIAL_EVIDENCE_CHANGE, create_recommendation_revision`
- from `app.prediction_outcome_monitor`: `STATE_STOP_LOSS_HIT`

```python
def test_a_superseded_call_is_still_tracked_to_its_own_end(session):
    original, original_tip = _registered(session)
    revised, revised_tip = _registered(session, created_at=ist(MON, 17), entry="102")

    create_recommendation_revision(
        session, original_prediction=original, previous_prediction=original, revised_prediction=revised,
        revision_reason=REASON_MATERIAL_EVIDENCE_CHANGE, revised_at=ist(MON, 17),
    )
    assert session.get(Tip, revised_tip.id).revises_tip_id == original_tip.id
    assert session.scalars(select(TipSourceExit)).all() == []

    _bar(session, TUE, 100.5, 101, 99.5, 100.8)
    _bar(session, WED, 99, 99.5, 96.5, 97)
    track_tips(session, now=RUN_AT, minute_bars=None)

    original_tip, revised_tip = session.get(Tip, original_tip.id), session.get(Tip, revised_tip.id)
    assert (original_tip.status, original_tip.exit_price, original_tip.outcome) == (
        STATUS_STOP_LOSS_HIT, Decimal("97"), OUTCOME_FAILURE,
    )
    assert (revised_tip.status, revised_tip.exit_price) == (STATUS_STOP_LOSS_HIT, Decimal("98.94"))
    assert [session.get(Prediction, p.id).status for p in (original, revised)] == ["SUPERSEDED", PREDICTION_EVALUATED]
    assert get_terminal_event(session, original.id).state == STATE_STOP_LOSS_HIT
```

- [ ] **Step 2: Run it to verify it fails**

Run: `python -m pytest tests/test_marksy_prediction_tips.py -v -k superseded`
Expected: FAIL with `assert None == <original tip id>`: `revises_tip_id` is not set.

- [ ] **Step 3: Write the link**

Append to `app/marksy_tips.py`:

```python
def link_revision(session: Session, previous: Prediction, revised: Prediction) -> None:
    """Invariant 6: the revision is a new tip naming the call it revises; the original is never exited here."""
    previous_tip = tip_for_prediction(session, previous.id)
    revised_tip = tip_for_prediction(session, revised.id)
    if previous_tip is None or revised_tip is None or revised_tip.revises_tip_id is not None:
        return
    revised_tip.revises_tip_id = canonical_tip(session, previous_tip).id
    session.flush()
```

- [ ] **Step 4: Call it where the revision link is made**

In `app/recommendation_revision.py`, directly before `from .models import (`, add:

```python
from .marksy_tips import link_revision
```

In `create_recommendation_revision`, replace:

```python
    if previous_prediction.status == STATUS_OPEN:
        previous_prediction.status = STATUS_SUPERSEDED
    session.commit()
```

with:

```python
    if previous_prediction.status == STATUS_OPEN:
        previous_prediction.status = STATUS_SUPERSEDED
    # tip-ledger invariant 6: the revision is a new tip; the original runs to its own end.
    link_revision(session, previous_prediction, revised_prediction)
    session.commit()
```

- [ ] **Step 5: Run the tests**

Run: `python -m pytest tests/test_marksy_prediction_tips.py tests/test_recommendation_revision.py tests/test_epic795_revision_chain_closure.py tests/test_epic853_confirmation.py -v`
Expected: all PASS.

- [ ] **Step 6: Commit**

```bash
git add app/marksy_tips.py app/recommendation_revision.py tests/test_marksy_prediction_tips.py
git commit -m "Tip ledger: a revised prediction's tip revises the original, which runs to its own end (invariant 6)

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 7: The tracker owns price checks for registered predictions

**Files:**
- Modify: `app/prediction_outcome_monitor.py` (`evaluate_prediction_realtime`, imports)
- Modify: `tests/test_prediction_outcome_monitor.py` (`make_prediction`, imports)
- Modify: `tests/test_epic853_confirmation.py` (`test_a_provisional_high_never_decides_a_target_hit`, imports)
- Modify: `tests/test_epic366_prediction_lifecycle.py` (`test_an_unlinked_revision_still_resolves_through_the_outcome_monitor`, imports)
- Test: `tests/test_marksy_prediction_tips.py` (append)

**Interfaces:**
- Produces: `evaluate_prediction_realtime(...)`, with its signature unchanged.
  - For a prediction with a tip, it still returns an existing terminal event, and still runs the decay withdrawal. Otherwise it returns None, with no price or stale-data event.
  - For a prediction without a tip, its behaviour is unchanged.

- [ ] **Step 1: Write the failing test**

Append to `tests/test_marksy_prediction_tips.py`:

```python
def test_the_monitor_checks_prices_only_for_predictions_without_a_tip(session):
    legacy = _prediction(session, symbol="TCS")
    registered, _tip = _registered(session)
    _bar(session, TUE, 100.5, 106, 99.5, 105.5, symbol="TCS")
    _bar(session, TUE, 100.5, 106, 99.5, 105.5)

    assert evaluate_prediction_realtime(session, legacy, as_of=ist(TUE, 18)).state == STATE_TARGET_HIT
    assert evaluate_prediction_realtime(session, registered, as_of=ist(TUE, 18)) is None
    assert get_event_history(session, registered.id) == ()
```

- [ ] **Step 2: Run it to verify it fails**

Run: `python -m pytest tests/test_marksy_prediction_tips.py -v -k only_for_predictions_without`
Expected: FAIL with `assert <PredictionOutcomeEvent …> is None`: the monitor still records TARGET_HIT for the registered prediction.

- [ ] **Step 3: Move the three tests whose subject no longer takes the monitor's price path (decision 11)**

In `tests/test_prediction_outcome_monitor.py`:
- delete `from app.recommendations import record_recommendation`;
- replace `make_prediction` with:

```python
def make_prediction(session, stock, *, horizon_days=5, entry_price="100", target_return="0.05", stop_return="-0.03"):
    """A pre-ledger prediction (no tip): the monitor's price checks serve only these (tip-ledger spec §7)."""
    prediction = Prediction(
        stock_id=stock.id, created_at=AS_OF, as_of_timestamp=AS_OF, entry_price=Decimal(entry_price),
        horizon_days=horizon_days, target_return=Decimal(target_return), stop_return=Decimal(stop_return),
        predicted_probability=Decimal("0.7"), confidence=Decimal("0.8"), model_version="m1-baseline-1",
        feature_version="f1", consensus_contract_version="c1", horizon_selection_version="h1",
        scoring_contract_version="s1", opportunity_score=Decimal("60.00"), status="OPEN",
    )
    session.add(prediction)
    session.flush()
    return prediction
```

In `tests/test_epic853_confirmation.py`:
- Replace `from app.prediction_outcome_monitor import STATE_INVALIDATED, STATE_TARGET_HIT, evaluate_prediction_realtime` with `from app.prediction_outcome_monitor import STATE_INVALIDATED, STATE_TARGET_HIT, get_terminal_event`.
- Add `from app.tip_tracking_job import track_tips` after the `from app.scan_anchor import scan_cutoff` line.
- Replace the body of `test_a_provisional_high_never_decides_a_target_hit` with:

```python
def test_a_provisional_high_never_decides_a_target_hit(session):
    stocks, _summary = _evening_scan(session)
    prediction = _published_predictions(session)[0]
    tomorrow_anchor = scan_cutoff(TODAY + timedelta(days=1))
    session.add(MarketPrice(
        stock_id=prediction.stock_id, timestamp=tomorrow_anchor, open=prediction.entry_price,
        high=prediction.entry_price * Decimal("1.2"), low=prediction.entry_price, close=prediction.entry_price,
        volume=1000, source=PROVISIONAL_SOURCE,
    ))
    session.commit()

    # tip-ledger spec §7: a registered prediction's target is the tracker's to decide, on official bars only.
    track_tips(session, now=tomorrow_anchor + timedelta(hours=16), minute_bars=None, provisional=True)
    assert get_terminal_event(session, prediction.id) is None

    bar = session.scalar(select(MarketPrice).where(MarketPrice.timestamp == tomorrow_anchor))
    bar.source = "upstox-v3"
    session.commit()
    track_tips(session, now=tomorrow_anchor + timedelta(hours=31), minute_bars=None)
    event = get_terminal_event(session, prediction.id)
    assert event is not None and event.state == STATE_TARGET_HIT
```

Why this holds whatever time of day the test runs:
- `TODAY`'s bars stay provisional, so `TODAY` is not a market session.
- So the tip's session 1 is `TODAY + 1` whenever the prediction was written.
- Its bar opens and lows at the entry, and reaches 1.2 × the entry: entered, then the target is hit. With daily bars only, the entry is taken first.

In `tests/test_epic366_prediction_lifecycle.py`:
- Add `from app.marksy_tips import canonical_tip, tip_for_prediction` before the `from app.models import (` line.
- Add `sync_prediction_from_tip` to its `from app.prediction_outcome_monitor import (...)` block.
- Add `from app.tip_vocabulary import STATUS_TARGET_HIT` after the `from app.scan import CandidateSignals` line.
- Replace `test_an_unlinked_revision_still_resolves_through_the_outcome_monitor` with:

```python
def test_an_unlinked_revision_still_resolves_through_the_outcome_monitor(session):
    """Lineage and resolution are preserved -- the guard skips revision only. The orphan is a registered
    prediction, so it resolves when the tip it follows closes (tip-ledger spec §7)."""
    stock = _stock(session)
    _history(session, stock, last_day=ISSUED_ON)
    original = _prediction(session, stock)
    assert _revise_once(session, stock, original, at=OUT_OF_SESSION, close=Decimal("1002")).result == RESULT_REVISED
    orphan = _orphan_revision(session, stock, original)

    at = OUT_OF_SESSION + timedelta(days=1)
    target = orphan.entry_price * (1 + orphan.target_return)
    _bar(session, stock, at.date(), target)
    tip = canonical_tip(session, tip_for_prediction(session, orphan.id))
    tip.status, tip.closed_at, tip.exit_price = STATUS_TARGET_HIT, at, target
    sync_prediction_from_tip(session, tip, now=at)
    session.commit()

    row = reassess_prediction(
        session, orphan, now=at, signal_provider=StubSignalProvider(), trigger=TRIGGER_NEW_BAR,
    )

    assert row.result == RESULT_RESOLVED, row.detail
    assert get_terminal_event(session, orphan.id).state == STATE_TARGET_HIT
```

`canonical_tip` covers both shapes of the orphan. It may be its own ACTIVE tip, or a follower of the first revision's tip when it re-made that call on identical terms.

Run: `python -m pytest tests/test_prediction_outcome_monitor.py tests/test_epic853_confirmation.py tests/test_epic366_prediction_lifecycle.py -v`
Expected: all PASS on the current code. They no longer depend on the monitor checking a registered prediction's prices.

- [ ] **Step 4: Retire the price checks for registered predictions**

In `app/prediction_outcome_monitor.py`, change the `marksy_tips` import to:

```python
from .marksy_tips import (
    canonical_tip,
    prediction_status_for,
    predictions_following,
    tip_for_prediction,
    withdraw_prediction,
)
```

In `evaluate_prediction_realtime`, directly before `rows = list(` (after the decay branch's `return event_row`), add:

```python
    if tip_for_prediction(session, prediction.id) is not None:
        return None  # tip-ledger spec §7: the tip tracker owns a registered prediction's price checks

```

- [ ] **Step 5: Run the tests**

Run: `python -m pytest tests/test_marksy_prediction_tips.py tests/test_prediction_outcome_monitor.py tests/test_epic853_confirmation.py tests/test_epic853_invalidated_on_next_run.py tests/test_epic366_prediction_lifecycle.py tests/test_reassessment_sweep_progress.py tests/test_api_predictions_active.py -v`
Expected: all PASS.

- [ ] **Step 6: Commit**

```bash
git add app/prediction_outcome_monitor.py tests/test_marksy_prediction_tips.py tests/test_prediction_outcome_monitor.py \
  tests/test_epic853_confirmation.py tests/test_epic366_prediction_lifecycle.py
git commit -m "Tip ledger: the tracker owns price checks for registered predictions; the monitor keeps pre-ledger ones

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 8: Regression set, PR, merge, deploy, verify

**Files:** none new.

- [ ] **Step 1: Run the regression set**

```bash
rm -f "$TEMP/marksy-pytest-default.db"
python -m pytest tests/test_marksy_prediction_tips.py tests/test_tip_prediction_link_migration.py \
  tests/test_tip_ledger.py tests/test_tip_ledger_guard.py tests/test_tip_ledger_migration.py tests/test_tip_matching.py \
  tests/test_tip_daily_progress_migration.py tests/test_tip_tracker.py tests/test_tip_minute_bars.py \
  tests/test_tip_tracking_job.py tests/test_run_tip_tracker.py \
  tests/test_prediction_outcome_monitor.py tests/test_epic853_*.py tests/test_epic366_prediction_lifecycle.py \
  tests/test_epic795_revision_chain_closure.py tests/test_reassessment_sweep_progress.py \
  tests/test_recommendation_revision.py tests/test_recommendation_history.py tests/test_recommendation_lifecycle.py \
  tests/test_recommendation_tracking.py tests/test_epic365_fresh_entry_activation.py tests/test_target_stop_loss.py \
  tests/test_position_risk_assessment.py tests/test_invalidated_status_migration.py tests/test_api_predictions_active.py \
  tests/test_api_tips*.py tests/test_external_tip_*.py -v
python -m alembic heads
```

Expected: all PASS, and one head: `0183_tips_prediction_unique`.

A failure can come only from a new Marksy tip sitting beside an external one in a count or a `.one()`. In that case scope the assertion to `Tip.prediction_id.is_(None)`, as Task 4 Step 5 did, and name it in the PR. Handle any other failure as the Global Constraints describe (check `main`, and note pre-existing failures in the PR).

- [ ] **Step 2: Push and open the PR**

```bash
git push -u origin feat/tip-ledger-marksy-predictions
gh pr create --title "Tip ledger Phase 2b: Marksy predictions into the ledger" --body "$(cat <<'EOF'
Every prediction Marksy records is now a tip of the Marksy channel. The Phase 2a tracker tracks and scores it
under tip-ledger spec §6, unchanged (§7).

- `record_recommendation` registers the tip in the same transaction: BUY, entry = entry price, M1.47's levels,
  horizon = horizon_days (ENGINE), `first_seen_at` = `created_at`, parser `ENG-001`, no receipts
- An identical call while the first is ACTIVE is kept as MERGED_DUPLICATE and follows it; §6.2 rejections are kept as UNSCORABLE and close their prediction at birth
- A tip the tracker closes moves its prediction off OPEN and appends the monitor's terminal event, so
  `/predictions/active` and `/instruments` keep working
- Withdrawals (`record_invalidation`, assumption decay) write a source exit, so a withdrawn call is still scored
- A revision's tip gets `revises_tip_id`; the original is tracked to its own end (invariant 6)
- The monitor's price checks now run only for predictions recorded before this change (Phase 6 backfills them)
- Migration 0183: at most one tip per prediction; `GET /tips` lists external tips only

Tests: new `tests/test_marksy_prediction_tips.py`, the 0183 migration test, and the tip, tracker, monitor, EPIC-853,
EPIC-366, revision and recommendation regression files. Moved expectations: the monitor test fixture (tipless), one
EPIC-853 and one EPIC-366 test (now through the tracker), two `/tips` count assertions.

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```

- [ ] **Step 3: Note the prediction watermark, merge and deploy**

```bash
echo "select coalesce(max(id), 0) from predictions;" \
  | ssh marksy@103.178.160.233 'KUBECONFIG=~/.kube/config kubectl -n market-agent exec -i deploy/postgres -- sh -c '"'"'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB"'"'"''
gh pr merge --merge --delete-branch
ssh marksy@103.178.160.233 'bash ~/deployment/server/vps-deploy.sh'
```

Expected:
- The first command prints the highest prediction id before the deploy. Keep it as `N` for Step 4.
- The deploy script finishes, and its migrate Job applies `0183_tips_prediction_unique`.

- [ ] **Step 4: Verify production**

Replace `N` with the Step 3 value:

```bash
echo "select version_num from alembic_version;
select count(*) from pg_indexes where tablename = 'tips' and indexname = 'uq_tips_prediction_id';
select t.status, p.status, count(*) from tips t join predictions p on p.id = t.prediction_id group by 1, 2 order by 1, 2;
select count(*) from tips t join predictions p on p.id = t.prediction_id where t.status not in ('ACTIVE', 'MERGED_DUPLICATE') and p.status = 'OPEN';
select count(*) from predictions p where p.id > N and not exists (select 1 from tips t where t.prediction_id = p.id);" \
  | ssh marksy@103.178.160.233 'KUBECONFIG=~/.kube/config kubectl -n market-agent exec -i deploy/postgres -- sh -c '"'"'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB"'"'"''
ssh marksy@103.178.160.233 'KUBECONFIG=~/.kube/config kubectl -n market-agent get pods'
ssh marksy@103.178.160.233 'KUBECONFIG=~/.kube/config kubectl -n market-agent create job --from=cronjob/market-agent-tip-tracker tip-tracker-2b-check && KUBECONFIG=~/.kube/config kubectl -n market-agent wait --for=condition=complete job/tip-tracker-2b-check --timeout=600s; KUBECONFIG=~/.kube/config kubectl -n market-agent logs job/tip-tracker-2b-check'
```

Expected:
- `alembic_version` is `0183_tips_prediction_unique`, and the index count is 1.
- The fourth query returns 0: no prediction is OPEN while its tip is closed.
- The fifth query returns 0: every prediction written since the deploy has a tip.
- All pods are Running or Completed.
- The tracker log prints `Tip tracking (final): {'basis': 'FINAL', ...}` with no traceback. The job imports the monitor, `marksy_tips` and the ledger together.

If no prediction has been written since the deploy (the discovery scan runs in the evening), the third and fifth queries show nothing new. Rerun them after the next scan and the next 08:45 IST tracker run. The merge does not wait on them.

- [ ] **Step 5: Clean up the worktree**

```bash
cd /c/AIAgent/marksy-api && git worktree remove ../marksy-api-phase2b && git checkout main && git pull
```

## Summary

Phase 2b puts Marksy's own calls into the central ledger:
- Every recorded prediction becomes a receipt-less tip of the Marksy channel, on its stated long terms, dated from when it was written. The unchanged Phase 2a tracker tracks it.
- A withdrawal is a source exit, so a withdrawn losing call still scores as a failure.
- A revision is a new tip, and the original runs to its end.
- Identical calls follow the first as MERGED_DUPLICATE.
- `Prediction.status` and the monitor's terminal event follow the tip, so the OPEN readers, `/predictions/active` and `/instruments` stay correct as the monitor's price checks retire.
- Only predictions recorded before this phase still use the monitor's price path, until the Phase 6 backfill.

The next plans are 2c (the rating-engine port) and Phase 3 (scorecards and APIs), which read what this phase writes.

## Validation

- Task 1: `tests/test_tip_prediction_link_migration.py`, `tests/test_tip_daily_progress_migration.py`, `tests/test_tip_ledger_migration.py`, `tests/test_tip_ledger.py`, `alembic heads`
- Task 2: `tests/test_marksy_prediction_tips.py`, `tests/test_tip_ledger.py`
- Task 3: `tests/test_marksy_prediction_tips.py`, `tests/test_tip_tracking_job.py`, `tests/test_prediction_outcome_monitor.py`
- Task 4:
  - the import check;
  - `tests/test_marksy_prediction_tips.py` and `tests/test_api_tips.py`;
  - `tests/test_target_stop_loss.py`, `tests/test_position_risk_assessment.py` and `tests/test_epic365_fresh_entry_activation.py`;
  - `tests/test_recommendation_history.py`, `tests/test_invalidated_status_migration.py` and `tests/test_prediction_outcome_monitor.py`.
- Task 5: `tests/test_marksy_prediction_tips.py`, `tests/test_prediction_outcome_monitor.py`, `tests/test_epic853_confirmation.py`, `tests/test_epic853_invalidated_on_next_run.py`
- Task 6: `tests/test_marksy_prediction_tips.py`, `tests/test_recommendation_revision.py`, `tests/test_epic795_revision_chain_closure.py`, `tests/test_epic853_confirmation.py`
- Task 7:
  - `tests/test_marksy_prediction_tips.py` and `tests/test_prediction_outcome_monitor.py`;
  - `tests/test_epic853_confirmation.py` and `tests/test_epic853_invalidated_on_next_run.py`;
  - `tests/test_epic366_prediction_lifecycle.py` and `tests/test_reassessment_sweep_progress.py`;
  - `tests/test_api_predictions_active.py`.
- Task 8: the regression set in Task 8 Step 1, `alembic heads`, and the production checks in Task 8 Step 4
