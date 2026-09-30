# Tip Ledger Phase 2a (Tracker) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Track every ACTIVE ledger tip session by session against the candle store until it hits its target, stop-loss, horizon or a source exit. Write one progress row per session, and close the tip with the §6.6 outcome and returns.

**Architecture:**
- Task 1 makes the ledger tell intake rejections apart from the tracker's own DATA_UNRESOLVED.
- A pure engine (`app/tip_tracker.py`) evaluates one session from a daily bar, or from 1-minute bars when order matters.
- A DB runner (`app/tip_tracking_job.py`) resumes each tip after its last FINAL progress row. It loads official bars from `market_prices`, fetches Upstox 1-minute bars only when needed (they are never stored), writes `tip_daily_progress` and closes tips.
- A new `TIP_TRACKING` operation runs it twice a day: 08:45 IST on official bars, and 16:10 IST with provisional rows that never close a tip.

**Tech Stack:** Python 3.12, FastAPI 0.139, SQLAlchemy 2.0.52 (`Mapped`/`mapped_column`), Alembic 1.19, httpx, pytest on SQLite 3.49 (tests) and PostgreSQL (prod), Kubernetes CronJobs.

**Spec:** `C:\AIAgent\marksy-os\docs\superpowers\specs\2026-09-29-marksy-central-tip-ledger-design.md` (§2 invariants 3, 8 and 12, §4 `tip_daily_progress`, §5.2 "matchable", §6.1–§6.7, §13 phase 2). Phase 1 plan for context: `C:\AIAgent\marksy-os\docs\superpowers\plans\2026-09-29-tip-ledger-phase-0-1.md`.

## Global Constraints

- Repo: `C:\AIAgent\marksy-api`. Work in a git worktree at `C:\AIAgent\marksy-api-phase2a` on branch `feat/tip-tracker`, created from `origin/main` (which has Phase 1 merged, alembic head `0181_tip_ledger`).
- Commit once per task. At the end, open a PR with `gh pr create`. **Merging and deploying are authorized:** merge with `gh pr merge --merge --delete-branch` once the Task 8 regression set is green, then deploy with `ssh marksy@103.178.160.233 'bash ~/deployment/server/vps-deploy.sh'`. GitHub Actions don't run (account billing), so GitHub showing UNSTABLE is expected and not a blocker.
- Commit messages end with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`. PR bodies end with `🤖 Generated with [Claude Code](https://claude.com/claude-code)`.
- Tests: run only the `python -m pytest tests/<file>.py -v` commands given in each step. Never run the full suite: it takes about 2 hours, and 4 tests already fail on main locally. If a listed test fails, run the same file on `main` in `C:\AIAgent\marksy-api`. If it also fails there, it is pre-existing: note it in the PR and don't fix it.
- `tests/conftest.py` gives the app engine a file DB at `%TEMP%\marksy-pytest-default.db`, and `create_all` never alters an existing table. Delete that file whenever the ORM schema changes (Task 2 says when).
- Code style follows the repo: `Mapped`/`mapped_column`, `BigInteger().with_variant(Integer, "sqlite")` primary keys, no ORM relationships, camelCase keys in `result_summary`. Comments are one line and explain a non-obvious WHY only. No unrelated refactors.
- Alembic revision ids must be ≤ 32 characters. Migrations must run on both SQLite and PostgreSQL.
- SQLite drops tzinfo on read. Pass every stored datetime through `_aware(...)` (assume UTC) before comparing it or converting it to IST.
- Spec §2.3, verbatim: "Tracking runs once per tip; at most one progress row per tip per session."
- Spec §2.5, verbatim: "Call terms (channel, symbol, direction, stated entry, target, stop-loss, stated horizon) and `first_seen_at` are immutable. Tips and receipts are never deleted." The only term write this plan makes is the one-time fill of a first-seen entry (`entry_low`, `entry_high` and `entry_price` from NULL, with `entry_basis = FIRST_SEEN_PRICE`). `app/tip_ledger_guard.py` already allows that fill.
- Spec §2.12, verbatim: "the tracker stores derived facts, never raw intraday bars."
- Spec §6.7, verbatim: "Provisional capture (15:55 IST) updates progress rows only. Terminal transitions use official data only."
- Spec §6.6 returns: `s = +1` for BUY and `-1` for SELL. `promised_return = s × (target / entry_mid − 1)`. `actual_return = s × (exit_price / entry_mid − 1)`. Returns are fractions, quantized to 6 decimal places (`Numeric(12, 6)`).
- Defaults (§15): a 5-session entry window, and DATA_UNRESOLVED after data stays missing for 5 sessions.
- Scope: only tips with `status = 'ACTIVE'` and a `match_key` (ledger tips). The 43 legacy rows (`status` NULL) wait for the §12 backfill. Marksy predictions (2b) and the rating engine (2c) are out of scope (see decision 14).

## Review Focus

These are the inputs the spec implies but its happy paths never exercise. Each one has a test in the owning task:

1. **A tip first seen during market hours, with no minute bars.** Session 0 must be skipped silently, not counted as a missing bar, and tracking starts at session 1. Test: Task 6, `test_session_zero_without_minute_bars_is_skipped_and_tracking_starts_at_session_one`.
2. **A day the whole ingest missed** (no stock has a bar). It must not count as a session for anyone, so it never pushes tips towards DATA_UNRESOLVED. Test: Task 6, `test_a_day_the_whole_ingest_missed_is_not_a_session`.
3. **A first-seen entry that opens beyond the target or the stop** (an overnight gap). It must not score a zero-return success, a negative promised return or a loss. Test: Task 4, `test_a_first_seen_entry_already_past_a_level_is_void`.
4. **An Upstox outage or expired token on the morning run.** It must not permanently skip session 0 or resolve an intraday tip as DATA_UNRESOLVED; the tip waits. Test: Task 6, `test_unavailable_minute_bars_leave_the_tip_waiting`.
5. **A provisional bar that touches the target.** It may show in the evening progress row but must never close the tip; the official bar decides next morning. Test: Task 6, `test_a_provisional_bar_writes_progress_only_and_the_official_bar_closes_the_tip`.

## Resolved ambiguities (decisions this plan makes)

1. **Intake rejections are told apart by `reason`.** A tip counts as rejected at intake only if its status is UNSCORABLE or DATA_UNRESOLVED *and* its reason is NO_DIRECTION, INCONSISTENT_LEVELS or UNRESOLVED_SYMBOL. The tracker's DATA_UNRESOLVED uses MISSING_BARS or NO_INTRADAY_BARS, so it is never matchable, and a later copy of that call becomes a new tip. `closed_at == first_seen_at` was rejected as the test: it is an accident of timing, not a meaning.
2. **Columns added to `tip_daily_progress` beyond §4:**
   - `data_basis` (PROVISIONAL | FINAL): a provisional row is replaced by the FINAL row for the same session, and a FINAL row is never rewritten.
   - `intraday_bar_at`: the minute bar that decided an intraday fact that session. This is §6.7's "the bar timestamp used".
   - `recorded_at`.
3. **Sessions come from the candle store, not the holiday calendar.** A date is a market session once `market_prices` holds an official bar for it for any stock. So holidays and special sessions need no calendar lookup, and a day the whole ingest missed simply isn't a session (the horizon stretches by a day) instead of pushing every tip towards DATA_UNRESOLVED. "Still missing 5 sessions later" (§6.7) is checked per stock against those sessions. `closed_at` for MISSING_BARS is the close of the 5th later session.
4. **An intraday tip (N = 0) seen outside market hours** takes the next whole session as session 0. Broker intraday calls usually arrive before the open. Like any session 0, it needs minute bars; without them it closes as DATA_UNRESOLVED (`NO_INTRADAY_BARS`), with `closed_at` at that session's close.
5. **A first-seen entry at or beyond the target or the stop is void.** For BUY that means entry ≥ target or entry ≤ stop; for SELL, the mirror. The tip closes INVALIDATED (`ENTRY_BEYOND_LEVELS`) with no outcome, just as §6.3 voids a target touched before entry. Otherwise an overnight gap would score a zero-return success or a loss the tipster's levels never allowed.
6. **A source exit before entry** (still WAITING, or a first-seen entry not yet fixed) closes the tip INVALIDATED (`SOURCE_EXIT_BEFORE_ENTRY`) with no outcome.
7. **Source-exit timing (§6.5):**
   - An exit during market hours applies inside that session. With daily bars only, a stop touched that session still wins (the §6.4.1 "stop-loss wins" stance), and a target touched that session loses to the exit, because we can't tell the order. The result is flagged ambiguous, so the runner tries minute bars.
   - An exit outside market hours takes effect at the next session's open, before any touch in that session. Its stated price is used if it lies within that session's low–high; otherwise the exit price is that session's open.
   - An in-session exit that neither its stated price nor a minute bar can price takes the next session's open, and the stated price is dropped. At the horizon session it takes that session's close.
   - `closed_at` is the exit message time. `closed_session` is the session whose price closed the tip.
8. **Progress fields:**
   - `return_to_date` is the session-close return while ACTIVE and the actual return on the terminal row; it is null while WAITING.
   - `to_target_pct` / `to_stop_pct` are percentages from the session close to the level, positive while there is room left. They are null without a level and null on the terminal row.
   - `best_return` / `worst_return` are the cumulative excursions since entry. They are clipped at the target and stop returns (a daily conflict bar can't show a best past the target), and always include the exit return.
9. **Minute bars.** Fetched from Upstox V3 1-minute history for one instrument and one session, cached for the run, and never stored.
   - No instrument key, no provider, or an empty response means "no bars", and the daily fallback applies at once.
   - A provider failure (auth or network) means "not now": the tip waits. Once 5 later sessions exist, a failure counts as "no bars".
10. **The provisional pass** writes at most one PROVISIONAL row per tip: the session right after the tip's last FINAL row, and only if the tip didn't wait. It never touches session 0, minute bars or any tip column.
11. **Lifecycle columns are written as they become known,** not only at close: the first-seen entry fill, `entry_status`, `entered_session` and `promised_return` (which is set once the entry midpoint exists).
12. **Replay is incremental.** Each FINAL run resumes after the tip's last FINAL row and uses the position stored there (entry status, best and worst returns), so a rerun changes nothing. Session 0 skipped for lack of minute bars leaves no row, so a later run may still fill it if bars appear before session 1 is processed.
13. **Scheduling.** One operation, `TIP_TRACKING`, with two CronJobs:
    - 08:45 IST Tue–Sat on official bars, after the 08:30 `PROVISIONAL_CONFIRMATION`.
    - 16:10 IST Mon–Fri with `--provisional`, after the 15:55 capture.

    Both passes also process any official sessions that are due. The dedup key is `track:<basis>:<IST date>`.
14. **Phase 2 is split:** 2a (this plan), 2b (Marksy predictions into the ledger), 2c (the rating-engine port).
    - 2b rewires `Prediction.status`, which three modules write today (`app/outcomes.py` EVALUATED, `app/recommendation_revision.py` SUPERSEDED, `app/prediction_outcome_monitor.py` INVALIDATED). It also changes the `/instruments` lifecycle, which reads `PredictionOutcomeEvent`.
    - 2c needs inputs the backend doesn't store (fundamentals, ownership, monthly candles).
    - Both reuse this tracker unchanged and get their own plans after 2a merges.
15. **Tracker reason strings:** NEVER_ENTERED, TARGET_BEFORE_ENTRY, ENTRY_BEYOND_LEVELS, SOURCE_EXIT_BEFORE_ENTRY, MISSING_BARS and NO_INTRADAY_BARS. All fit the existing `reason` String(32) column.

## File Structure

- Modify `app/tip_vocabulary.py`: intake-rejection reasons, tracker reasons, outcome, bar-basis and data-basis vocabulary.
- Modify `app/tip_ledger.py:194` (`find_matchable`): matchable rejections are intake rejections only.
- Modify `app/models.py`: add the `TipDailyProgress` ORM class after `TipSourceExit` (line 5325).
- Create `migrations/versions/0182_tip_daily_progress.py`.
- Create `app/tip_tracker.py`: the pure engine (bars, terms, position, sessions, exit timing, per-session evaluation, returns, outcomes).
- Modify `app/market_data/upstox.py`: `UpstoxClient.fetch_minute_candles`.
- Create `app/tip_minute_bars.py`: the `MinuteBarSource` protocol, `MinuteBarsUnavailable`, and the per-run Upstox adapter.
- Create `app/tip_tracking_job.py`: the DB runner (`track_tips`).
- Create `scripts/run_tip_tracker.py`: the CronJob entrypoint.
- Modify `app/schedule_orchestration.py`, `app/operation_entrypoints.py`, `app/operation_recovery.py` and `app/operation_verification.py`: register `TIP_TRACKING`.
- Create `deploy/k8s/base/tip-tracker-cronjob.yaml` and `deploy/k8s/base/tip-tracker-provisional-cronjob.yaml`; modify `deploy/k8s/base/kustomization.yaml`.
- Tests: modify `tests/test_tip_ledger.py` and `tests/test_operation_verification_s7.py`. Create `tests/test_tip_daily_progress_migration.py`, `tests/test_tip_tracker.py`, `tests/test_tip_minute_bars.py`, `tests/test_tip_tracking_job.py` and `tests/test_run_tip_tracker.py`.

---

### Task 1: Worktree, tracker vocabulary, and intake-only matchable rejections

**Files:**
- Modify: `app/tip_vocabulary.py` (after `REASON_UNRESOLVED_SYMBOL`)
- Modify: `app/tip_ledger.py:194-207` (`find_matchable`) and its imports
- Test: `tests/test_tip_ledger.py`

**Interfaces:**
- Produces, in `app/tip_vocabulary.py`:
  - `INTAKE_REJECTION_REASONS: tuple[str, ...]`
  - Reasons: `REASON_NEVER_ENTERED`, `REASON_TARGET_BEFORE_ENTRY`, `REASON_ENTRY_BEYOND_LEVELS`, `REASON_SOURCE_EXIT_BEFORE_ENTRY`, `REASON_MISSING_BARS`, `REASON_NO_INTRADAY_BARS`
  - Outcomes: `OUTCOME_SUCCESS`, `OUTCOME_FAILURE`, `OUTCOME_EXPIRED`
  - Bases: `BAR_BASIS_DAILY`, `BAR_BASIS_INTRADAY`, `DATA_BASIS_PROVISIONAL`, `DATA_BASIS_FINAL`
- Produces: `find_matchable(session, match_key, *, now) -> Tip | None`. Its signature is unchanged; it no longer returns tracker-closed tips.

- [ ] **Step 1: Create the worktree**

```bash
cd /c/AIAgent/marksy-api && git fetch origin && git worktree add ../marksy-api-phase2a -b feat/tip-tracker origin/main
cd /c/AIAgent/marksy-api-phase2a && git log --oneline -1 && ls migrations/versions | tail -1
```

Expected: HEAD is the Phase 1 merge (`Merge pull request #40`), and the last migration is `0181_tip_ledger.py`. All later commands run in `C:\AIAgent\marksy-api-phase2a`.

- [ ] **Step 2: Write the failing test**

Append to `tests/test_tip_ledger.py`. Add `find_matchable` to its `from app.tip_ledger import (...)` block, `REASON_MISSING_BARS`, `STATUS_ACTIVE` and `STATUS_DATA_UNRESOLVED` to its `from app.tip_vocabulary import (...)` block, and `Tip` to its `from app.models import (...)` block. Also add `from sqlalchemy import select` if it isn't already imported.

```python
def test_a_tip_the_tracker_closed_as_data_unresolved_is_not_matchable(session):
    _tip_intake(session, _receipt(), _terms())
    tip = session.scalars(select(Tip)).one()
    tip.status, tip.reason, tip.closed_at = STATUS_DATA_UNRESOLVED, REASON_MISSING_BARS, NOW
    session.commit()

    assert find_matchable(session, tip.match_key, now=NOW) is None
    _tip_intake(session, _receipt(user="user-2", key="e-2"), _terms())
    tips = session.scalars(select(Tip).order_by(Tip.id)).all()
    assert len(tips) == 2 and tips[1].status == STATUS_ACTIVE
```

- [ ] **Step 3: Run it to verify it fails**

Run: `python -m pytest tests/test_tip_ledger.py -v -k tracker_closed`
Expected: FAIL with `ImportError: cannot import name 'REASON_MISSING_BARS'`.

- [ ] **Step 4: Add the vocabulary**

In `app/tip_vocabulary.py`, directly after `REASON_UNRESOLVED_SYMBOL = "UNRESOLVED_SYMBOL"`, add:

```python
# The tracker also closes tips as DATA_UNRESOLVED; only these reasons mean "rejected at intake" (§6.2).
INTAKE_REJECTION_REASONS = (REASON_NO_DIRECTION, REASON_INCONSISTENT_LEVELS, REASON_UNRESOLVED_SYMBOL)
REASON_NEVER_ENTERED = "NEVER_ENTERED"
REASON_TARGET_BEFORE_ENTRY = "TARGET_BEFORE_ENTRY"
REASON_ENTRY_BEYOND_LEVELS = "ENTRY_BEYOND_LEVELS"
REASON_SOURCE_EXIT_BEFORE_ENTRY = "SOURCE_EXIT_BEFORE_ENTRY"
REASON_MISSING_BARS = "MISSING_BARS"
REASON_NO_INTRADAY_BARS = "NO_INTRADAY_BARS"

OUTCOME_SUCCESS = "SUCCESS"
OUTCOME_FAILURE = "FAILURE"
OUTCOME_EXPIRED = "EXPIRED"

BAR_BASIS_DAILY = "DAILY"
BAR_BASIS_INTRADAY = "INTRADAY"
DATA_BASIS_PROVISIONAL = "PROVISIONAL"
DATA_BASIS_FINAL = "FINAL"
```

- [ ] **Step 5: Restrict matchable rejections to intake rejections**

In `app/tip_ledger.py`:
- Change `from sqlalchemy import func, select, text` to `from sqlalchemy import and_, func, or_, select, text`.
- Add `INTAKE_REJECTION_REASONS` to the `from .tip_vocabulary import (...)` block.
- Replace the `.where(...)` line of `find_matchable` so the function reads:

```python
def find_matchable(session: Session, match_key: str, *, now: datetime) -> Tip | None:
    """The ACTIVE tip with this key, else a tip rejected at intake still inside its horizon (§5.2)."""
    candidates = session.scalars(
        select(Tip)
        .where(
            Tip.match_key == match_key,
            or_(
                Tip.status == STATUS_ACTIVE,
                and_(Tip.status.in_(REJECTED_AT_INTAKE), Tip.reason.in_(INTAKE_REJECTION_REASONS)),
            ),
        )
        .order_by(Tip.first_seen_at.desc(), Tip.id.desc())
    ).all()
    for tip in candidates:
        if tip.status == STATUS_ACTIVE:
            return tip
    for tip in candidates:
        if _sessions_since(session, tip.first_seen_at, now) <= tip.horizon_sessions:
            return tip
    return None
```

- [ ] **Step 6: Run the ledger tests**

Run: `python -m pytest tests/test_tip_ledger.py tests/test_tip_matching.py -v`
Expected: all PASS, including the Phase 1 tests where an intake-rejected tip is still matchable.

- [ ] **Step 7: Commit**

```bash
git add app/tip_vocabulary.py app/tip_ledger.py tests/test_tip_ledger.py
git commit -m "Tip ledger: only intake rejections stay matchable; tracker vocabulary

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 2: `tip_daily_progress` table (ORM + migration 0182)

**Files:**
- Modify: `app/models.py` (add `TipDailyProgress` directly after `class TipSourceExit`)
- Create: `migrations/versions/0182_tip_daily_progress.py`
- Test: `tests/test_tip_daily_progress_migration.py`

**Interfaces:**
- Produces: ORM `TipDailyProgress` with these columns: `id, tip_id, session_date, session_index, entry_status, status_after, return_to_date, best_return, worst_return, to_target_pct, to_stop_pct, bar_basis, data_basis, intraday_bar_at, recorded_at`, and the unique constraint `uq_tip_daily_progress_tip_session` on `(tip_id, session_date)`.

- [ ] **Step 1: Write the failing migration test**

Create `tests/test_tip_daily_progress_migration.py`:

```python
"""0182_tip_daily_progress on SQLite: one row per tip per session, and downgrade drops the table."""
from __future__ import annotations

from datetime import date, datetime, timezone

import pytest
import sqlalchemy as sa
from sqlalchemy.exc import IntegrityError

from tests._migration_helpers import run_revision


@pytest.fixture
def engine():
    engine = sa.create_engine("sqlite:///:memory:")
    with engine.begin() as conn:
        conn.execute(sa.text("CREATE TABLE tips (id INTEGER PRIMARY KEY)"))
        conn.execute(sa.text("INSERT INTO tips (id) VALUES (1)"))
        run_revision(conn, "0182_tip_daily_progress")
    return engine


def _row(conn, session_date):
    conn.execute(sa.text(
        "INSERT INTO tip_daily_progress (tip_id, session_date, session_index, entry_status, status_after,"
        " bar_basis, data_basis, recorded_at) VALUES (1, :day, 1, 'ENTERED', 'ACTIVE', 'DAILY', 'FINAL', :at)"
    ), {"day": session_date, "at": datetime(2026, 9, 29, 3, 15, tzinfo=timezone.utc)})


def test_a_tip_has_at_most_one_row_per_session(engine):
    with engine.begin() as conn:
        _row(conn, date(2026, 9, 29))
        _row(conn, date(2026, 9, 30))
    with pytest.raises(IntegrityError), engine.begin() as conn:
        _row(conn, date(2026, 9, 29))


def test_downgrade_drops_the_table(engine):
    with engine.begin() as conn:
        run_revision(conn, "0182_tip_daily_progress", "downgrade")
        assert "tip_daily_progress" not in sa.inspect(conn).get_table_names()
```

- [ ] **Step 2: Run it to verify it fails**

Run: `python -m pytest tests/test_tip_daily_progress_migration.py -v`
Expected: FAIL, because Alembic can't find revision `0182_tip_daily_progress`.

- [ ] **Step 3: Write the migration**

Create `migrations/versions/0182_tip_daily_progress.py`:

```python
"""Tip ledger Phase 2: one progress row per tracked session of a tip (tip-ledger spec §4, §6.7).

Revision ID: 0182_tip_daily_progress
Revises: 0181_tip_ledger
"""
from __future__ import annotations

import sqlalchemy as sa
from alembic import op

revision = "0182_tip_daily_progress"
down_revision = "0181_tip_ledger"
branch_labels = None
depends_on = None

_ID = sa.BigInteger().with_variant(sa.Integer(), "sqlite")


def upgrade() -> None:
    op.create_table(
        "tip_daily_progress",
        sa.Column("id", _ID, primary_key=True),
        sa.Column("tip_id", _ID, sa.ForeignKey("tips.id"), nullable=False),
        sa.Column("session_date", sa.Date(), nullable=False),
        sa.Column("session_index", sa.Integer(), nullable=False),
        sa.Column("entry_status", sa.String(16), nullable=False),
        sa.Column("status_after", sa.String(24), nullable=False),
        sa.Column("return_to_date", sa.Numeric(12, 6)),
        sa.Column("best_return", sa.Numeric(12, 6)),
        sa.Column("worst_return", sa.Numeric(12, 6)),
        sa.Column("to_target_pct", sa.Numeric(12, 6)),
        sa.Column("to_stop_pct", sa.Numeric(12, 6)),
        sa.Column("bar_basis", sa.String(16), nullable=False),
        sa.Column("data_basis", sa.String(16), nullable=False),
        sa.Column("intraday_bar_at", sa.DateTime(timezone=True)),
        sa.Column("recorded_at", sa.DateTime(timezone=True), nullable=False),
        sa.UniqueConstraint("tip_id", "session_date", name="uq_tip_daily_progress_tip_session"),
    )


def downgrade() -> None:
    op.drop_table("tip_daily_progress")
```

Before writing it, open `migrations/versions/0181_tip_ledger.py` and match its header. If 0181 declares `branch_labels`/`depends_on` differently or has no docstring header, follow 0181.

- [ ] **Step 4: Add the ORM class**

In `app/models.py`, directly after `class TipSourceExit` (it ends with its `created_at` column), add:

```python
class TipDailyProgress(Base):
    """One tracked session of a tip (tip-ledger spec §4, §6.7). A FINAL row is never rewritten; a
    PROVISIONAL one is replaced by the FINAL row for the same session. No OHLC is copied here."""

    __tablename__ = "tip_daily_progress"
    __table_args__ = (UniqueConstraint("tip_id", "session_date", name="uq_tip_daily_progress_tip_session"),)
    id: Mapped[int] = mapped_column(BigInteger().with_variant(Integer, "sqlite"), primary_key=True)
    tip_id: Mapped[int] = mapped_column(ForeignKey("tips.id"))
    session_date: Mapped[date] = mapped_column(Date)
    session_index: Mapped[int] = mapped_column(Integer)
    entry_status: Mapped[str] = mapped_column(String(16))
    status_after: Mapped[str] = mapped_column(String(24))
    return_to_date: Mapped[Decimal | None] = mapped_column(Numeric(12, 6))
    best_return: Mapped[Decimal | None] = mapped_column(Numeric(12, 6))
    worst_return: Mapped[Decimal | None] = mapped_column(Numeric(12, 6))
    to_target_pct: Mapped[Decimal | None] = mapped_column(Numeric(12, 6))
    to_stop_pct: Mapped[Decimal | None] = mapped_column(Numeric(12, 6))
    bar_basis: Mapped[str] = mapped_column(String(16))
    data_basis: Mapped[str] = mapped_column(String(16))
    intraday_bar_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    recorded_at: Mapped[datetime] = mapped_column(DateTime(timezone=True))
```

`Date`, `date`, `Decimal`, `Numeric`, `UniqueConstraint` and `ForeignKey` are already imported by `app/models.py` (`MarketPrice.session_date` uses `Date`). Check the top-of-file imports and add any that are missing.

- [ ] **Step 5: Reset the pytest file DB and run the tests**

```bash
rm -f "$TEMP/marksy-pytest-default.db"
python -m pytest tests/test_tip_daily_progress_migration.py tests/test_tip_ledger_migration.py tests/test_tip_ledger.py -v
```

Expected: all PASS.

- [ ] **Step 6: Commit**

```bash
git add app/models.py migrations/versions/0182_tip_daily_progress.py tests/test_tip_daily_progress_migration.py
git commit -m "Tip ledger: tip_daily_progress table (migration 0182)

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 3: Tracker engine — values, sessions, exit timing, returns, outcomes

**Files:**
- Create: `app/tip_tracker.py`
- Test: `tests/test_tip_tracker.py`

**Interfaces:**
- Produces (all pure):
  - `MARKET_OPEN = time(9, 15)`, `MARKET_CLOSE = time(15, 30)`, `ENTRY_WINDOW_SESSIONS = 5`, `MISSING_DATA_SESSIONS = 5`, `EXIT_IN_SESSION = "IN_SESSION"`, `EXIT_BEFORE_OPEN = "BEFORE_OPEN"`
  - `@dataclass(frozen=True) PriceBar(at: datetime, open: Decimal, high: Decimal, low: Decimal, close: Decimal)`
  - `@dataclass(frozen=True) Terms(direction: str, entry_low: Decimal | None, entry_high: Decimal | None, target: Decimal | None, stop_loss: Decimal | None, horizon_sessions: int)`
  - `@dataclass(frozen=True) Position(entry_status: str, entry_price: Decimal | None, entered_session: int | None, best_return: Decimal | None = None, worst_return: Decimal | None = None)`
  - `@dataclass(frozen=True) ExitInstruction(mode: str, at: datetime, stated_price: Decimal | None)`
  - `@dataclass(frozen=True) TipSession(index: int, date: date)`
  - `@dataclass(frozen=True) SessionResult(position: Position, status: str, reason: str | None = None, exit_price: Decimal | None = None, actual_return: Decimal | None = None, return_to_date: Decimal | None = None, to_target_pct: Decimal | None = None, to_stop_pct: Decimal | None = None, ambiguous: bool = False, decided_at: datetime | None = None)`
  - `signed_return(direction: str, price: Decimal, entry: Decimal) -> Decimal`
  - `promised_return(terms: Terms, entry: Decimal) -> Decimal | None`
  - `outcome_for(status: str, actual_return: Decimal | None) -> str | None`
  - `session_close(day: date) -> datetime` (UTC)
  - `sessions_after(day: date, market_sessions: Sequence[date]) -> list[date]`
  - `tip_sessions(first_seen_at: datetime, horizon_sessions: int, market_sessions: Sequence[date]) -> list[TipSession]`
  - `exit_instruction_for(exit_at: datetime, stated_price: Decimal | None, session_date: date, market_sessions: Sequence[date]) -> ExitInstruction | None`

- [ ] **Step 1: Write the failing tests**

Create `tests/test_tip_tracker.py`:

```python
"""Tip tracker (tip-ledger spec §6), pure: sessions, exit timing, returns, outcomes and session evaluation."""
from __future__ import annotations

from dataclasses import replace
from datetime import date, datetime, timezone
from decimal import Decimal

import pytest

from app.market_data.quality import NSE_TIMEZONE
from app.tip_tracker import (
    EXIT_BEFORE_OPEN,
    EXIT_IN_SESSION,
    ExitInstruction,
    Terms,
    exit_instruction_for,
    outcome_for,
    promised_return,
    session_close,
    signed_return,
    tip_sessions,
)
from app.tip_vocabulary import (
    DIRECTION_BUY,
    DIRECTION_SELL,
    OUTCOME_EXPIRED,
    OUTCOME_FAILURE,
    OUTCOME_SUCCESS,
    STATUS_DATA_UNRESOLVED,
    STATUS_DIRECTION_HORIZON,
    STATUS_HORIZON_EXPIRED,
    STATUS_INVALIDATED,
    STATUS_SOURCE_EXIT,
    STATUS_STOP_LOSS_HIT,
    STATUS_TARGET_HIT,
)

MON, TUE, WED, THU = date(2026, 9, 28), date(2026, 9, 29), date(2026, 9, 30), date(2026, 10, 1)


def ist(day, hour, minute=0):
    """`day` at hour:minute IST, as UTC."""
    return datetime(day.year, day.month, day.day, hour, minute, tzinfo=NSE_TIMEZONE).astimezone(timezone.utc)


@pytest.mark.parametrize(
    ("seen", "horizon", "market", "expected"),
    [
        (ist(MON, 10, 30), 2, [MON, TUE, WED, THU], [(0, MON), (1, TUE), (2, WED)]),
        (ist(MON, 8), 2, [MON, TUE, WED], [(1, MON), (2, TUE)]),
        (ist(MON, 16), 2, [MON, TUE, WED], [(1, TUE), (2, WED)]),
        (ist(TUE, 10, 30), 1, [MON, WED], [(1, WED)]),
        (ist(MON, 10, 30), 0, [MON, TUE], [(0, MON)]),
        (ist(MON, 16), 0, [MON, TUE], [(0, TUE)]),
        (ist(TUE, 10, 30), 3, [MON], []),
    ],
    ids=["in-hours", "pre-open", "after-close", "holiday", "intraday-in-hours", "intraday-after-close", "not-yet"],
)
def test_sessions_follow_the_time_the_tip_was_first_seen(seen, horizon, market, expected):
    assert [(s.index, s.date) for s in tip_sessions(seen, horizon, market)] == expected


def test_an_exit_during_market_hours_applies_inside_that_session():
    assert exit_instruction_for(ist(TUE, 11), Decimal("104"), TUE, [MON, TUE]) == ExitInstruction(
        EXIT_IN_SESSION, ist(TUE, 11), Decimal("104")
    )


def test_an_exit_after_the_close_applies_at_the_next_open_with_its_stated_price():
    assert exit_instruction_for(ist(TUE, 18), Decimal("104"), TUE, [MON, TUE, WED]) is None
    assert exit_instruction_for(ist(TUE, 18), Decimal("104"), WED, [MON, TUE, WED]) == ExitInstruction(
        EXIT_BEFORE_OPEN, ist(TUE, 18), Decimal("104")
    )


def test_an_exit_an_earlier_session_could_not_price_takes_the_next_open_without_its_stated_price():
    assert exit_instruction_for(ist(TUE, 11), Decimal("104"), WED, [MON, TUE, WED]) == ExitInstruction(
        EXIT_BEFORE_OPEN, ist(TUE, 11), None
    )


def test_returns_are_signed_by_direction():
    assert signed_return(DIRECTION_BUY, Decimal("110"), Decimal("100")) == Decimal("0.1")
    assert signed_return(DIRECTION_SELL, Decimal("190"), Decimal("200")) == Decimal("0.05")
    assert signed_return(DIRECTION_BUY, Decimal("103"), Decimal("101")) == Decimal("0.019802")
    sell = Terms(DIRECTION_SELL, Decimal("200"), Decimal("200"), Decimal("190"), Decimal("206"), 20)
    assert promised_return(sell, Decimal("200")) == Decimal("0.05")
    assert promised_return(replace(sell, target=None), Decimal("200")) is None


@pytest.mark.parametrize(
    ("status", "actual", "outcome"),
    [
        (STATUS_TARGET_HIT, Decimal("0.1"), OUTCOME_SUCCESS),
        (STATUS_STOP_LOSS_HIT, Decimal("-0.05"), OUTCOME_FAILURE),
        (STATUS_SOURCE_EXIT, Decimal("0.01"), OUTCOME_SUCCESS),
        (STATUS_SOURCE_EXIT, Decimal("0"), OUTCOME_FAILURE),
        (STATUS_HORIZON_EXPIRED, Decimal("0.02"), OUTCOME_EXPIRED),
        (STATUS_DIRECTION_HORIZON, Decimal("0.02"), OUTCOME_SUCCESS),
        (STATUS_DIRECTION_HORIZON, Decimal("-0.02"), OUTCOME_FAILURE),
        (STATUS_INVALIDATED, None, None),
        (STATUS_DATA_UNRESOLVED, None, None),
    ],
)
def test_outcomes_follow_spec_6_6(status, actual, outcome):
    assert outcome_for(status, actual) == outcome


def test_a_session_closes_at_1530_ist():
    assert session_close(MON) == datetime(2026, 9, 28, 10, 0, tzinfo=timezone.utc)
```

- [ ] **Step 2: Run them to verify they fail**

Run: `python -m pytest tests/test_tip_tracker.py -v`
Expected: FAIL with `ModuleNotFoundError: No module named 'app.tip_tracker'`.

- [ ] **Step 3: Write the engine's values and session rules**

Create `app/tip_tracker.py`:

```python
"""Tip tracker (tip-ledger spec §6): one tip, one session at a time, against price bars.

Pure -- no DB, no clock. `app.tip_tracking_job` loads the bars and persists what this returns."""

from __future__ import annotations

from dataclasses import dataclass, replace
from datetime import date, datetime, time, timezone
from decimal import Decimal
from typing import Sequence

from .market_data.quality import NSE_TIMEZONE
from .tip_vocabulary import (
    DIRECTION_BUY,
    ENTRY_ENTERED,
    ENTRY_WAITING,
    OUTCOME_EXPIRED,
    OUTCOME_FAILURE,
    OUTCOME_SUCCESS,
    REASON_ENTRY_BEYOND_LEVELS,
    REASON_NEVER_ENTERED,
    REASON_SOURCE_EXIT_BEFORE_ENTRY,
    REASON_TARGET_BEFORE_ENTRY,
    STATUS_ACTIVE,
    STATUS_DIRECTION_HORIZON,
    STATUS_HORIZON_EXPIRED,
    STATUS_INVALIDATED,
    STATUS_SOURCE_EXIT,
    STATUS_STOP_LOSS_HIT,
    STATUS_TARGET_HIT,
)

MARKET_OPEN = time(9, 15)
MARKET_CLOSE = time(15, 30)
ENTRY_WINDOW_SESSIONS = 5
MISSING_DATA_SESSIONS = 5
EXIT_IN_SESSION = "IN_SESSION"
EXIT_BEFORE_OPEN = "BEFORE_OPEN"
_PLACES = Decimal("0.000001")
_ONE = Decimal(1)
_HUNDRED = Decimal(100)


@dataclass(frozen=True)
class PriceBar:
    at: datetime
    open: Decimal
    high: Decimal
    low: Decimal
    close: Decimal


@dataclass(frozen=True)
class Terms:
    direction: str
    entry_low: Decimal | None
    entry_high: Decimal | None
    target: Decimal | None
    stop_loss: Decimal | None
    horizon_sessions: int


@dataclass(frozen=True)
class Position:
    """What one session hands the next; `entry_price` is the entry midpoint, None until a first-seen entry is fixed."""

    entry_status: str
    entry_price: Decimal | None
    entered_session: int | None
    best_return: Decimal | None = None
    worst_return: Decimal | None = None


@dataclass(frozen=True)
class ExitInstruction:
    mode: str
    at: datetime
    stated_price: Decimal | None


@dataclass(frozen=True)
class TipSession:
    index: int
    date: date


@dataclass(frozen=True)
class SessionResult:
    position: Position
    status: str
    reason: str | None = None
    exit_price: Decimal | None = None
    actual_return: Decimal | None = None
    return_to_date: Decimal | None = None
    to_target_pct: Decimal | None = None
    to_stop_pct: Decimal | None = None
    ambiguous: bool = False
    decided_at: datetime | None = None


def _q(value: Decimal) -> Decimal:
    return value.quantize(_PLACES)


def _sign(direction: str) -> Decimal:
    return _ONE if direction == DIRECTION_BUY else -_ONE


def signed_return(direction: str, price: Decimal, entry: Decimal) -> Decimal:
    """§6.6: `s × (price / entry_mid − 1)`."""
    return _q(_sign(direction) * (price / entry - _ONE))


def promised_return(terms: Terms, entry: Decimal) -> Decimal | None:
    return None if terms.target is None else signed_return(terms.direction, terms.target, entry)


def outcome_for(status: str, actual_return: Decimal | None) -> str | None:
    """§6.6: the outcome of a terminal status; statuses without one return None."""
    if status == STATUS_TARGET_HIT:
        return OUTCOME_SUCCESS
    if status == STATUS_STOP_LOSS_HIT:
        return OUTCOME_FAILURE
    if status == STATUS_HORIZON_EXPIRED:
        return OUTCOME_EXPIRED
    if status in (STATUS_SOURCE_EXIT, STATUS_DIRECTION_HORIZON):
        return OUTCOME_SUCCESS if actual_return is not None and actual_return > 0 else OUTCOME_FAILURE
    return None


def session_close(day: date) -> datetime:
    return datetime.combine(day, MARKET_CLOSE, NSE_TIMEZONE).astimezone(timezone.utc)


def _in_market_hours(moment: time) -> bool:
    return MARKET_OPEN <= moment < MARKET_CLOSE


def sessions_after(day: date, market_sessions: Sequence[date]) -> list[date]:
    return [other for other in market_sessions if other > day]


def tip_sessions(first_seen_at: datetime, horizon_sessions: int, market_sessions: Sequence[date]) -> list[TipSession]:
    """§6.1 over the sessions the candle store holds (oldest first): session 0 is the one a tip arrived in during
    market hours, 1..N the full sessions after it. An intraday tip (N = 0) seen outside hours takes the next one."""
    local = first_seen_at.astimezone(NSE_TIMEZONE)
    seen_on, seen_at = local.date(), local.time()
    if not market_sessions or market_sessions[-1] < seen_on:
        return []
    partial = _in_market_hours(seen_at) and seen_on in market_sessions
    after = [day for day in market_sessions if day > seen_on or (day == seen_on and seen_at < MARKET_OPEN)]
    if horizon_sessions == 0:
        if partial:
            return [TipSession(0, seen_on)]
        return [TipSession(0, after[0])] if after else []
    sessions = [TipSession(0, seen_on)] if partial else []
    return sessions + [TipSession(index, day) for index, day in enumerate(after[:horizon_sessions], start=1)]


def exit_instruction_for(
    exit_at: datetime, stated_price: Decimal | None, session_date: date, market_sessions: Sequence[date]
) -> ExitInstruction | None:
    """§6.5: how a source exit applies to `session_date`, or None when it came after that session."""
    local = exit_at.astimezone(NSE_TIMEZONE)
    said_on, said_at = local.date(), local.time()
    during_hours = _in_market_hours(said_at)
    if said_on == session_date and during_hours:
        return ExitInstruction(EXIT_IN_SESSION, exit_at, stated_price)
    if said_on > session_date or (said_on == session_date and said_at >= MARKET_CLOSE):
        return None
    # An in-hours exit an earlier session could not price takes this open; its stated price belonged to that session.
    priced_earlier = during_hours and said_on in market_sessions
    return ExitInstruction(EXIT_BEFORE_OPEN, exit_at, None if priced_earlier else stated_price)
```

`replace`, `ENTRY_*`, `REASON_*` and `STATUS_ACTIVE`/`STATUS_INVALIDATED` are imported now so that Task 4 only appends code.

- [ ] **Step 4: Run the tests**

Run: `python -m pytest tests/test_tip_tracker.py -v`
Expected: all PASS.

- [ ] **Step 5: Commit**

```bash
git add app/tip_tracker.py tests/test_tip_tracker.py
git commit -m "Tip tracker: sessions, source-exit timing, returns and outcomes (spec 6.1, 6.5, 6.6)

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---
### Task 4: Tracker engine — one session's evaluation (entry, exits, horizon, source exits)

**Files:**
- Modify: `app/tip_tracker.py` (append)
- Test: `tests/test_tip_tracker.py` (append)

**Interfaces:**
- Consumes (Task 3): `PriceBar`, `Terms`, `Position`, `ExitInstruction`, `SessionResult`, `signed_return`, `ENTRY_WINDOW_SESSIONS`, `EXIT_BEFORE_OPEN`.
- Produces: `evaluate_session(terms: Terms, position: Position, index: int, day: PriceBar, *, minutes: Sequence[PriceBar] | None = None, exit_instruction: ExitInstruction | None = None) -> SessionResult`
  - `day` is the session's daily bar. `minutes`, when given, are that session's 1-minute bars; for session 0 the caller has already dropped the bars before `first_seen_at`.
  - `result.ambiguous` is True when a daily-only answer rested on an event order that minute bars would settle: an entry and an exit in one bar, a target and a stop in one bar, or an in-session source exit. The runner then refetches with minute bars.
  - `result.decided_at` is the minute bar that decided the session's last intraday fact (None for daily bars).

**Rules this task implements** (quoting the spec):
- §6.3: "A BUY is entered when a bar's low ≤ `entry_high`; a SELL when a bar's high ≥ `entry_low`."
- §6.3: "Not entered by the close of session min(5, N) → INVALIDATED (`NEVER_ENTERED`)."
- §6.3: "Target touched while still WAITING → INVALIDATED (`TARGET_BEFORE_ENTRY`)."
- §6.3: "Entry and an exit in the same session: with intraday bars only bars at or after the entry count; with daily bars only, entry is taken first and §6.4 applies."
- §6.4, verbatim: "Target and stop-loss both touched in one session: with intraday bars the first touched wins; without them the stop-loss wins. Exit price is the level, unless the session opened beyond it (gap), then the session open."
- §6.4 horizon: at the close of session N the exit is that session's close. It is HORIZON_EXPIRED with a target or stop, and DIRECTION_HORIZON with neither.
- Decisions 5, 6, 7 and 8 of this plan.

- [ ] **Step 1: Write the failing tests**

Append to `tests/test_tip_tracker.py`. Extend its imports:
- from `app.tip_tracker`: `Position`, `PriceBar`, `evaluate_session`
- from `app.tip_vocabulary`: `ENTRY_ENTERED`, `ENTRY_WAITING`, `REASON_ENTRY_BEYOND_LEVELS`, `REASON_NEVER_ENTERED`, `REASON_SOURCE_EXIT_BEFORE_ENTRY`, `REASON_TARGET_BEFORE_ENTRY`, `STATUS_ACTIVE`

```python
def day(o, h, l, c, on=TUE):
    return PriceBar(ist(on, 0), *(Decimal(str(value)) for value in (o, h, l, c)))


def minute(hour, minute_, o, h, l, c, on=TUE):
    return PriceBar(ist(on, hour, minute_), *(Decimal(str(value)) for value in (o, h, l, c)))


BUY_RANGE = Terms(DIRECTION_BUY, Decimal("100"), Decimal("102"), Decimal("110"), Decimal("95"), 20)
BUY = Terms(DIRECTION_BUY, Decimal("100"), Decimal("100"), Decimal("110"), Decimal("95"), 20)
FIRST_SEEN = Terms(DIRECTION_BUY, None, None, Decimal("110"), Decimal("95"), 20)
SELL = Terms(DIRECTION_SELL, Decimal("200"), Decimal("200"), Decimal("190"), Decimal("206"), 20)
WAITING = Position(ENTRY_WAITING, Decimal("101"), None)
ENTERED = Position(ENTRY_ENTERED, Decimal("100"), 1)
SOLD = Position(ENTRY_ENTERED, Decimal("200"), 1)
UNFIXED = Position(ENTRY_ENTERED, None, None)


def test_a_buy_is_entered_once_a_low_reaches_the_top_of_its_range():
    result = evaluate_session(BUY_RANGE, WAITING, 1, day(104, 105, 101.5, 103))
    assert (result.status, result.position.entry_status, result.position.entered_session) == (
        STATUS_ACTIVE, ENTRY_ENTERED, 1,
    )
    assert (result.return_to_date, result.to_target_pct, result.to_stop_pct) == (
        Decimal("0.019802"), Decimal("6.796117"), Decimal("7.766990"),
    )
    assert result.ambiguous is False


def test_a_sell_is_entered_once_a_high_reaches_the_bottom_of_its_range():
    result = evaluate_session(SELL, Position(ENTRY_WAITING, Decimal("200"), None), 1, day(198, 200.5, 197, 199))
    assert (result.position.entry_status, result.return_to_date) == (ENTRY_ENTERED, Decimal("0.005"))


def test_a_target_touched_before_entry_invalidates():
    result = evaluate_session(BUY_RANGE, WAITING, 1, day(108, 111, 105, 109))
    assert (result.status, result.reason) == (STATUS_INVALIDATED, REASON_TARGET_BEFORE_ENTRY)


def test_a_call_not_entered_by_session_min_5_n_is_never_entered():
    untouched = day(104, 106, 103, 105)
    waiting = evaluate_session(BUY_RANGE, WAITING, 4, untouched)
    assert (waiting.status, waiting.return_to_date) == (STATUS_ACTIVE, None)
    assert evaluate_session(BUY_RANGE, WAITING, 5, untouched).reason == REASON_NEVER_ENTERED
    assert evaluate_session(replace(BUY_RANGE, horizon_sessions=3), WAITING, 3, untouched).reason == REASON_NEVER_ENTERED


def test_a_first_seen_entry_is_the_first_price_after_the_tip():
    daily = evaluate_session(FIRST_SEEN, UNFIXED, 1, day(100, 104, 99, 103))
    assert (daily.position.entry_price, daily.position.entered_session) == (Decimal("100"), 1)
    assert (daily.return_to_date, daily.position.best_return, daily.position.worst_return) == (
        Decimal("0.03"), Decimal("0.04"), Decimal("-0.01"),
    )
    minutes = [minute(10, 31, 101, 101.5, 100.5, 101), minute(10, 32, 101, 102, 100.8, 101.8)]
    intraday = evaluate_session(FIRST_SEEN, UNFIXED, 0, day(100, 104, 99, 103), minutes=minutes)
    assert (intraday.position.entry_price, intraday.position.entered_session, intraday.decided_at) == (
        Decimal("101"), 0, minutes[0].at,
    )


def test_a_first_seen_entry_already_past_a_level_is_void():
    for opening in (111, 94):
        result = evaluate_session(FIRST_SEEN, UNFIXED, 1, day(opening, opening + 1, opening - 1, opening))
        assert (result.status, result.reason, result.actual_return) == (
            STATUS_INVALIDATED, REASON_ENTRY_BEYOND_LEVELS, None,
        )


def test_the_target_exits_at_the_level_or_at_a_gapped_open():
    hit = evaluate_session(BUY, ENTERED, 2, day(105, 112, 104, 111))
    assert (hit.status, hit.exit_price, hit.actual_return, hit.position.best_return) == (
        STATUS_TARGET_HIT, Decimal("110"), Decimal("0.1"), Decimal("0.1"),
    )
    gap = evaluate_session(BUY, ENTERED, 2, day(113, 115, 112, 114))
    assert (gap.exit_price, gap.actual_return) == (Decimal("113"), Decimal("0.13"))


def test_the_stop_exits_at_the_level_or_at_a_gapped_open():
    gap = evaluate_session(BUY, ENTERED, 2, day(92, 96, 91, 95))
    assert (gap.status, gap.exit_price, gap.actual_return) == (STATUS_STOP_LOSS_HIT, Decimal("92"), Decimal("-0.08"))
    sell = evaluate_session(SELL, SOLD, 2, day(201, 207, 200, 205))
    assert (sell.status, sell.exit_price, sell.actual_return) == (STATUS_STOP_LOSS_HIT, Decimal("206"), Decimal("-0.03"))


def test_a_sell_target_is_a_low_touch():
    result = evaluate_session(SELL, SOLD, 2, day(195, 196, 189, 191))
    assert (result.status, result.exit_price, result.actual_return) == (STATUS_TARGET_HIT, Decimal("190"), Decimal("0.05"))


def test_target_and_stop_in_one_daily_bar_lose_to_the_stop_and_ask_for_minutes():
    result = evaluate_session(BUY, ENTERED, 2, day(100, 111, 94, 105))
    assert (result.status, result.exit_price, result.ambiguous) == (STATUS_STOP_LOSS_HIT, Decimal("95"), True)
    assert (result.position.best_return, result.position.worst_return) == (Decimal("0.1"), Decimal("-0.05"))


def test_minute_bars_settle_which_level_came_first():
    minutes = [minute(9, 15, 100, 111, 99, 110), minute(9, 16, 109, 109, 94, 95)]
    result = evaluate_session(BUY, ENTERED, 2, day(100, 111, 94, 105), minutes=minutes)
    assert (result.status, result.exit_price, result.ambiguous, result.decided_at) == (
        STATUS_TARGET_HIT, Decimal("110"), False, minutes[0].at,
    )


def test_entry_and_exit_in_one_daily_bar_take_the_entry_first_and_ask_for_minutes():
    daily = evaluate_session(BUY_RANGE, WAITING, 1, day(104, 111, 101, 109))
    assert (daily.status, daily.exit_price, daily.ambiguous) == (STATUS_TARGET_HIT, Decimal("110"), True)
    minutes = [minute(9, 15, 104, 111, 104, 110), minute(9, 16, 109, 109, 101, 102)]
    settled = evaluate_session(BUY_RANGE, WAITING, 1, day(104, 111, 101, 109), minutes=minutes)
    assert (settled.status, settled.reason) == (STATUS_INVALIDATED, REASON_TARGET_BEFORE_ENTRY)


def test_the_horizon_close_expires_a_call_with_levels_and_scores_a_direction_only_call():
    expired = evaluate_session(BUY, ENTERED, 20, day(103, 104, 102, 103.5))
    assert (expired.status, expired.exit_price, expired.actual_return) == (
        STATUS_HORIZON_EXPIRED, Decimal("103.5"), Decimal("0.035"),
    )
    direction_only = Terms(DIRECTION_BUY, None, None, None, None, 5)
    closed = evaluate_session(direction_only, ENTERED, 5, day(99, 100, 97, 98))
    assert (closed.status, closed.actual_return) == (STATUS_DIRECTION_HORIZON, Decimal("-0.02"))
    assert evaluate_session(direction_only, ENTERED, 4, day(99, 100, 97, 98)).to_target_pct is None


def test_a_source_exit_before_the_open_takes_its_stated_price_in_range_else_the_open():
    session = day(102, 105, 101, 104)
    stated = evaluate_session(
        BUY, ENTERED, 2, session, exit_instruction=ExitInstruction(EXIT_BEFORE_OPEN, ist(MON, 18), Decimal("104"))
    )
    assert (stated.status, stated.exit_price, stated.actual_return) == (STATUS_SOURCE_EXIT, Decimal("104"), Decimal("0.04"))
    outside = evaluate_session(
        BUY, ENTERED, 2, session, exit_instruction=ExitInstruction(EXIT_BEFORE_OPEN, ist(MON, 18), Decimal("107"))
    )
    assert outside.exit_price == Decimal("102")
    waiting = evaluate_session(
        BUY_RANGE, WAITING, 1, session, exit_instruction=ExitInstruction(EXIT_BEFORE_OPEN, ist(MON, 18), None)
    )
    assert (waiting.status, waiting.reason) == (STATUS_INVALIDATED, REASON_SOURCE_EXIT_BEFORE_ENTRY)


def test_an_in_session_exit_on_daily_bars_loses_to_a_stop_beats_a_target_and_asks_for_minutes():
    exit_at = ExitInstruction(EXIT_IN_SESSION, ist(TUE, 11), Decimal("108"))
    stopped = evaluate_session(BUY, ENTERED, 2, day(100, 111, 94, 105), exit_instruction=exit_at)
    assert (stopped.status, stopped.ambiguous) == (STATUS_STOP_LOSS_HIT, True)
    exited = evaluate_session(BUY, ENTERED, 2, day(100, 111, 99, 105), exit_instruction=exit_at)
    assert (exited.status, exited.exit_price, exited.ambiguous) == (STATUS_SOURCE_EXIT, Decimal("108"), True)
    unpriced = evaluate_session(
        BUY, ENTERED, 2, day(100, 105, 99, 104), exit_instruction=replace(exit_at, stated_price=None)
    )
    assert (unpriced.status, unpriced.ambiguous) == (STATUS_ACTIVE, True)


def test_minute_bars_price_an_in_session_exit_at_the_first_bar_after_it():
    minutes = [minute(10, 59, 101, 102, 100.5, 101.5), minute(11, 0, 101.2, 101.4, 100.9, 101.0)]
    result = evaluate_session(
        BUY, ENTERED, 2, day(100, 105, 99, 104), minutes=minutes,
        exit_instruction=ExitInstruction(EXIT_IN_SESSION, ist(TUE, 11), None),
    )
    assert (result.status, result.exit_price, result.decided_at) == (STATUS_SOURCE_EXIT, Decimal("101.2"), minutes[1].at)
```

- [ ] **Step 2: Run them to verify they fail**

Run: `python -m pytest tests/test_tip_tracker.py -v`
Expected: the new tests FAIL with `ImportError: cannot import name 'evaluate_session'`.

- [ ] **Step 3: Implement the session evaluation**

Append to `app/tip_tracker.py`:

```python
def _touched_target(terms: Terms, bar: PriceBar) -> bool:
    if terms.target is None:
        return False
    return bar.high >= terms.target if terms.direction == DIRECTION_BUY else bar.low <= terms.target


def _touched_stop(terms: Terms, bar: PriceBar) -> bool:
    if terms.stop_loss is None:
        return False
    return bar.low <= terms.stop_loss if terms.direction == DIRECTION_BUY else bar.high >= terms.stop_loss


def _entry_touched(terms: Terms, bar: PriceBar) -> bool:
    return bar.low <= terms.entry_high if terms.direction == DIRECTION_BUY else bar.high >= terms.entry_low


def _beyond_levels(terms: Terms, entry: Decimal) -> bool:
    """A first-seen entry at or past the target or the stop: the call was void when it could first be taken."""
    s = _sign(terms.direction)
    past_target = terms.target is not None and s * (entry - terms.target) >= 0
    past_stop = terms.stop_loss is not None and s * (terms.stop_loss - entry) >= 0
    return past_target or past_stop


def _fill(level: Decimal, bar: PriceBar, s: Decimal, *, favourable: bool) -> Decimal:
    """§6.4: the level, unless the bar opened beyond it (a gap), then the open."""
    gapped = s * (bar.open - level) >= 0 if favourable else s * (level - bar.open) >= 0
    return bar.open if gapped else level


def _excursion(terms: Terms, position: Position, bar: PriceBar) -> Position:
    """Best/worst return since entry, clipped at the target and stop so a conflict bar never reads past a level."""
    buy = terms.direction == DIRECTION_BUY
    best = signed_return(terms.direction, bar.high if buy else bar.low, position.entry_price)
    worst = signed_return(terms.direction, bar.low if buy else bar.high, position.entry_price)
    if terms.target is not None:
        best = min(best, signed_return(terms.direction, terms.target, position.entry_price))
    if terms.stop_loss is not None:
        worst = max(worst, signed_return(terms.direction, terms.stop_loss, position.entry_price))
    return replace(
        position,
        best_return=best if position.best_return is None else max(position.best_return, best),
        worst_return=worst if position.worst_return is None else min(position.worst_return, worst),
    )


def _exit(terms: Terms, position: Position, status: str, price: Decimal, *, ambiguous: bool = False,
          decided_at: datetime | None = None) -> SessionResult:
    actual = signed_return(terms.direction, price, position.entry_price)
    position = replace(
        position,
        best_return=actual if position.best_return is None else max(position.best_return, actual),
        worst_return=actual if position.worst_return is None else min(position.worst_return, actual),
    )
    return SessionResult(position, status, exit_price=price, actual_return=actual, return_to_date=actual,
                         ambiguous=ambiguous, decided_at=decided_at)


def _void(position: Position, reason: str, *, ambiguous: bool = False, decided_at: datetime | None = None) -> SessionResult:
    return SessionResult(position, STATUS_INVALIDATED, reason=reason, ambiguous=ambiguous, decided_at=decided_at)


def _still_open(terms: Terms, position: Position, day: PriceBar, *, ambiguous: bool,
                decided_at: datetime | None) -> SessionResult:
    s = _sign(terms.direction)
    entered = position.entry_status == ENTRY_ENTERED and position.entry_price is not None
    return SessionResult(
        position,
        STATUS_ACTIVE,
        return_to_date=signed_return(terms.direction, day.close, position.entry_price) if entered else None,
        to_target_pct=None if terms.target is None else _q(s * (terms.target - day.close) / day.close * _HUNDRED),
        to_stop_pct=None if terms.stop_loss is None else _q(s * (day.close - terms.stop_loss) / day.close * _HUNDRED),
        ambiguous=ambiguous,
        decided_at=decided_at,
    )


def _exit_before_open(terms: Terms, position: Position, day: PriceBar, instruction: ExitInstruction) -> SessionResult:
    if position.entry_status == ENTRY_WAITING or position.entry_price is None:
        return _void(position, REASON_SOURCE_EXIT_BEFORE_ENTRY)
    stated = instruction.stated_price
    price = stated if stated is not None and day.low <= stated <= day.high else day.open
    return _exit(terms, position, STATUS_SOURCE_EXIT, price)


def _exit_in_session(terms: Terms, position: Position, index: int, day: PriceBar,
                     minutes: Sequence[PriceBar] | None, instruction: ExitInstruction, *, ambiguous: bool,
                     decided_at: datetime | None) -> SessionResult:
    if position.entry_status == ENTRY_WAITING or position.entry_price is None:
        return _void(position, REASON_SOURCE_EXIT_BEFORE_ENTRY, ambiguous=ambiguous)
    stated = instruction.stated_price
    if stated is not None and day.low <= stated <= day.high:
        return _exit(terms, position, STATUS_SOURCE_EXIT, stated, ambiguous=ambiguous, decided_at=decided_at)
    after = [bar for bar in minutes or () if bar.at >= instruction.at]
    if after:
        return _exit(terms, position, STATUS_SOURCE_EXIT, after[0].open, decided_at=after[0].at)
    if index >= terms.horizon_sessions:
        return _exit(terms, position, STATUS_SOURCE_EXIT, day.close, ambiguous=ambiguous, decided_at=decided_at)
    # Nothing prices it in this session, so the next session's open will (§6.5).
    return _still_open(terms, position, day, ambiguous=True, decided_at=decided_at)


def _session_end(terms: Terms, position: Position, index: int, day: PriceBar, *, ambiguous: bool,
                 decided_at: datetime | None) -> SessionResult:
    if position.entry_status == ENTRY_WAITING:
        if index >= min(ENTRY_WINDOW_SESSIONS, terms.horizon_sessions):
            return _void(position, REASON_NEVER_ENTERED, ambiguous=ambiguous)
        return _still_open(terms, position, day, ambiguous=ambiguous, decided_at=decided_at)
    if index >= terms.horizon_sessions:
        has_levels = terms.target is not None or terms.stop_loss is not None
        status = STATUS_HORIZON_EXPIRED if has_levels else STATUS_DIRECTION_HORIZON
        return _exit(terms, position, status, day.close, ambiguous=ambiguous, decided_at=decided_at)
    return _still_open(terms, position, day, ambiguous=ambiguous, decided_at=decided_at)


def evaluate_session(
    terms: Terms,
    position: Position,
    index: int,
    day: PriceBar,
    *,
    minutes: Sequence[PriceBar] | None = None,
    exit_instruction: ExitInstruction | None = None,
) -> SessionResult:
    """§6.3–§6.5 for one session. `day` is its daily bar; `minutes`, when given, are its 1-minute bars (for
    session 0 only those after `first_seen_at`) and order the events inside it. `ambiguous` on the result says
    a daily-only answer rested on an order that minute bars would settle."""
    if exit_instruction is not None and exit_instruction.mode == EXIT_BEFORE_OPEN:
        return _exit_before_open(terms, position, day, exit_instruction)
    s = _sign(terms.direction)
    intraday = minutes is not None
    exit_in_session = exit_instruction is not None
    if intraday:
        bars = [bar for bar in minutes if not exit_in_session or bar.at < exit_instruction.at]
    else:
        bars = [day]
    events = 0
    decided_at = None
    for bar in bars:
        stamp = bar.at if intraday else None
        if position.entry_price is None:
            position = replace(position, entry_status=ENTRY_ENTERED, entry_price=bar.open, entered_session=index)
            decided_at = stamp
            if _beyond_levels(terms, bar.open):
                return _void(position, REASON_ENTRY_BEYOND_LEVELS, decided_at=decided_at)
        if position.entry_status == ENTRY_WAITING:
            if not _entry_touched(terms, bar):
                if _touched_target(terms, bar):
                    return _void(position, REASON_TARGET_BEFORE_ENTRY, decided_at=stamp)
                continue
            position = replace(position, entry_status=ENTRY_ENTERED, entered_session=index)
            events, decided_at = events + 1, stamp
        position = _excursion(terms, position, bar)
        stop, target = _touched_stop(terms, bar), _touched_target(terms, bar)
        events += int(stop) + int(target)
        ambiguous = not intraday and (events > 1 or exit_in_session)
        if stop:
            price = _fill(terms.stop_loss, bar, s, favourable=False)
            return _exit(terms, position, STATUS_STOP_LOSS_HIT, price, ambiguous=ambiguous, decided_at=stamp)
        # With daily bars an in-session source exit beats a target it may have preceded (decision 7).
        if target and (intraday or not exit_in_session):
            price = _fill(terms.target, bar, s, favourable=True)
            return _exit(terms, position, STATUS_TARGET_HIT, price, ambiguous=ambiguous, decided_at=stamp)
    ambiguous = not intraday and (events > 1 or exit_in_session)
    if exit_in_session:
        return _exit_in_session(terms, position, index, day, minutes, exit_instruction,
                                ambiguous=ambiguous, decided_at=decided_at)
    return _session_end(terms, position, index, day, ambiguous=ambiguous, decided_at=decided_at)
```

- [ ] **Step 4: Run the tests**

Run: `python -m pytest tests/test_tip_tracker.py -v`
Expected: all PASS. If a Decimal assertion fails by one unit in the 6th place, check that the implementation matches the code above exactly: quantize once in `signed_return` and once per percentage, never round intermediate values.

- [ ] **Step 5: Commit**

```bash
git add app/tip_tracker.py tests/test_tip_tracker.py
git commit -m "Tip tracker: per-session entry, exits, horizon and source exits (spec 6.3-6.5)

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 5: Upstox 1-minute bars on demand

**Files:**
- Modify: `app/market_data/upstox.py` (add `fetch_minute_candles` after `fetch_daily_candles`, around line 137)
- Create: `app/tip_minute_bars.py`
- Test: `tests/test_tip_minute_bars.py`

**Interfaces:**
- Consumes (Task 3): `PriceBar`.
- Produces:
  - `UpstoxClient.fetch_minute_candles(instrument_key: str, session_date: date) -> list[list[Any]]`
  - `class MinuteBarsUnavailable(RuntimeError)`
  - `class MinuteBarSource(Protocol)` with `minute_bars(instrument_key: str, session_date: date) -> list[PriceBar]`: bars oldest first, and `[]` when the provider has none.
  - `parse_candles(candles: list[list[Any]]) -> list[PriceBar]`
  - `class UpstoxMinuteBars(open_client: Callable[[], Any])`, implementing `minute_bars(...)` and `close()`

- [ ] **Step 1: Write the failing tests**

Create `tests/test_tip_minute_bars.py`:

```python
"""Upstox 1-minute bars for the tip tracker: one session per request, cached per run, never stored."""
from __future__ import annotations

from datetime import date
from decimal import Decimal

import httpx
import pytest

from app.market_data.upstox import UpstoxClient
from app.tip_minute_bars import MinuteBarsUnavailable, UpstoxMinuteBars, parse_candles

SESSION = date(2026, 9, 28)
CANDLES = [
    ["2026-09-28T09:16:00+05:30", 101, 102, 100.5, 101.5, 10, 0],
    ["2026-09-28T09:15:00+05:30", 100, 101, 99.5, 101, 20, 0],
]


def test_minute_candles_request_exactly_one_session(monkeypatch):
    client = UpstoxClient("token", "https://assets.upstox.com/market-quote/instruments/exchange/complete.json.gz")
    seen = []

    def fake_get(url):
        seen.append(url)
        body = {"status": "success", "data": {"candles": CANDLES}}
        return httpx.Response(200, json=body, request=httpx.Request("GET", url))

    monkeypatch.setattr(client, "_get", fake_get)
    assert client.fetch_minute_candles("NSE_EQ|INE002A01018", SESSION) == CANDLES
    assert seen == ["https://api.upstox.com/v3/historical-candle/NSE_EQ%7CINE002A01018/minutes/1/2026-09-28/2026-09-28"]


def test_candles_become_bars_oldest_first():
    bars = parse_candles(CANDLES)
    assert [(bar.at.hour, bar.at.minute, bar.open) for bar in bars] == [(9, 15, Decimal("100")), (9, 16, Decimal("101"))]
    assert bars[0].at.utcoffset().total_seconds() == 19800


class _Client:
    def __init__(self, fail_times=0):
        self.calls, self.fail_times, self.closed = 0, fail_times, False

    def fetch_minute_candles(self, instrument_key, session_date):
        self.calls += 1
        if self.calls <= self.fail_times:
            raise httpx.ConnectError("reset")
        return CANDLES

    def close(self):
        self.closed = True


def test_each_session_is_fetched_once_per_run_and_the_client_closes():
    client = _Client()
    opened = []
    source = UpstoxMinuteBars(lambda: opened.append(1) or client)
    assert len(source.minute_bars("NSE_EQ|X", SESSION)) == 2
    assert len(source.minute_bars("NSE_EQ|X", SESSION)) == 2
    source.close()
    assert (len(opened), client.calls, client.closed) == (1, 1, True)


def test_a_failed_request_is_unavailable_and_retried_next_time():
    source = UpstoxMinuteBars(lambda: _Client(fail_times=1))
    with pytest.raises(MinuteBarsUnavailable):
        source.minute_bars("NSE_EQ|X", SESSION)
    assert len(source.minute_bars("NSE_EQ|X", SESSION)) == 2


def test_a_client_that_cannot_open_makes_every_request_unavailable_without_reopening():
    attempts = []

    def open_client():
        attempts.append(1)
        raise RuntimeError("reauth required")

    source = UpstoxMinuteBars(open_client)
    for _ in range(2):
        with pytest.raises(MinuteBarsUnavailable):
            source.minute_bars("NSE_EQ|X", SESSION)
    assert len(attempts) == 1
```

- [ ] **Step 2: Run them to verify they fail**

Run: `python -m pytest tests/test_tip_minute_bars.py -v`
Expected: FAIL with `ModuleNotFoundError: No module named 'app.tip_minute_bars'`.

- [ ] **Step 3: Add the Upstox request**

In `app/market_data/upstox.py`, add `UPSTOX_HISTORICAL_CANDLES` to the `from .upstox_capabilities import (...)` block if it isn't there. Then add this method directly after `fetch_daily_candles`:

```python
    def fetch_minute_candles(self, instrument_key: str, session_date: date) -> list[list[Any]]:
        """One session's 1-minute candles (V3 historical), fetched on demand for the tip tracker and never stored."""
        ensure_capability_allowed(UPSTOX_HISTORICAL_CANDLES)
        encoded_key = quote(instrument_key, safe="")
        day = session_date.isoformat()
        response = self._get(f"https://api.upstox.com/v3/historical-candle/{encoded_key}/minutes/1/{day}/{day}")
        if response.status_code == 401:
            raise UpstoxError("Upstox access token is expired or unauthorized")
        response.raise_for_status()
        body = response.json()
        if body.get("status") != "success":
            raise UpstoxError(f"Upstox minute candle request failed: {body}")
        return body.get("data", {}).get("candles", [])
```

- [ ] **Step 4: Write the adapter**

Create `app/tip_minute_bars.py`:

```python
"""1-minute bars for the tip tracker (tip-ledger spec §6.7): fetched from Upstox on demand, never stored."""

from __future__ import annotations

from datetime import date, datetime
from decimal import Decimal
from typing import Any, Callable, Protocol

from .tip_tracker import PriceBar


class MinuteBarsUnavailable(RuntimeError):
    """The provider could not answer now (auth, network): the tip waits instead of falling back to daily rules."""


class MinuteBarSource(Protocol):
    def minute_bars(self, instrument_key: str, session_date: date) -> list[PriceBar]: ...


def parse_candles(candles: list[list[Any]]) -> list[PriceBar]:
    """Upstox V3 candles `[ts, open, high, low, close, volume, oi]` as bars, oldest first."""
    bars = [
        PriceBar(datetime.fromisoformat(candle[0]), *(Decimal(str(value)) for value in candle[1:5]))
        for candle in candles
    ]
    return sorted(bars, key=lambda bar: bar.at)


class UpstoxMinuteBars:
    """Opens the Upstox client on first use and keeps each (instrument, session) for the rest of the run."""

    def __init__(self, open_client: Callable[[], Any]) -> None:
        self._open_client = open_client
        self._client = None
        self._open_failure: str | None = None
        self._cache: dict[tuple[str, date], list[PriceBar]] = {}

    def minute_bars(self, instrument_key: str, session_date: date) -> list[PriceBar]:
        key = (instrument_key, session_date)
        if key in self._cache:
            return self._cache[key]
        if self._open_failure is not None:
            raise MinuteBarsUnavailable(self._open_failure)
        if self._client is None:
            try:
                self._client = self._open_client()
            except Exception as exc:  # noqa: BLE001 -- an unopenable client means "not now" for the whole run
                self._open_failure = f"{type(exc).__name__}: {exc}"
                raise MinuteBarsUnavailable(self._open_failure) from exc
        try:
            candles = self._client.fetch_minute_candles(instrument_key, session_date)
        except Exception as exc:  # noqa: BLE001 -- any provider failure is "not now", never "no bars"
            raise MinuteBarsUnavailable(f"{type(exc).__name__}: {exc}") from exc
        self._cache[key] = parse_candles(candles)
        return self._cache[key]

    def close(self) -> None:
        if self._client is not None:
            self._client.close()
```

- [ ] **Step 5: Run the tests**

Run: `python -m pytest tests/test_tip_minute_bars.py tests/test_upstox_cost_safety.py tests/test_provider_governance.py -v`
Expected: all PASS. The two existing files guard Upstox capability use. If one of them pins the set of capabilities or URLs that `UpstoxClient` uses, add the minute-candle path the same way that file lists `days/1` and say so in the commit message.

- [ ] **Step 6: Commit**

```bash
git add app/market_data/upstox.py app/tip_minute_bars.py tests/test_tip_minute_bars.py
git commit -m "Tip tracker: Upstox 1-minute bars on demand, cached per run, never stored

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---
### Task 6: The tracking runner (`track_tips`)

**Files:**
- Create: `app/tip_tracking_job.py`
- Test: `tests/test_tip_tracking_job.py`

**Interfaces:**
- Consumes:
  - Task 2: `TipDailyProgress`
  - Tasks 3–4: `PriceBar`, `Terms`, `Position`, `TipSession`, `SessionResult`, `evaluate_session`, `exit_instruction_for`, `tip_sessions`, `sessions_after`, `session_close`, `outcome_for`, `promised_return`, `MISSING_DATA_SESSIONS`
  - Task 5: `MinuteBarSource`, `MinuteBarsUnavailable`
  - Existing:
    - `app.market_data.bar_finality`: `final_bars_only()`, `PROVISIONAL_SOURCES`
    - `app.market_data.freshness.session_date_of(anchor) -> date`
    - `app.market_data.quality.NSE_TIMEZONE`
    - `app.models`: `MarketPrice` (daily bars keyed by `stock_id`, with `timestamp` = midnight IST of the session), `Stock.instrument_key`, and `TipSourceExit(tip_id, receipt_id, stated_exit_price, exit_seen_at)`
- Produces:
  - `@dataclass TrackingRun(basis: str, examined: int = 0, progress_rows: int = 0, waiting: int = 0, closed: dict[str, int])`, with `summary() -> {"basis", "tipsExamined", "progressRows", "tipsWaiting", "closed"}`
  - `track_tips(session: Session, *, now: datetime, minute_bars: MinuteBarSource | None, provisional: bool = False) -> TrackingRun`, which commits once per tip

**Behaviour:**
- For each ACTIVE ledger tip, resume after its last FINAL row.
- For each due session:
  - A missing daily bar at session 0 skips session 0, except for an intraday tip.
  - Otherwise, a missing daily bar makes the tip wait. Once 5 later market sessions exist, the tip closes DATA_UNRESOLVED (`MISSING_BARS`).
  - Session 0 needs minute bars at or after `first_seen_at`. Without them the session is skipped, and an intraday tip closes DATA_UNRESOLVED (`NO_INTRADAY_BARS`).
  - An ambiguous daily result is re-evaluated on that session's minute bars when some are available.
  - A `MinuteBarsUnavailable` makes the tip wait until 5 later sessions exist.
- Write the row, apply the lifecycle columns (decision 11), and stop at a terminal status.
- The provisional pass then writes the next session's PROVISIONAL row (decision 10).

- [ ] **Step 1: Write the failing tests**

Create `tests/test_tip_tracking_job.py`:

```python
"""Tip tracking job (tip-ledger spec §6.7) on SQLite: bars in `market_prices` become progress rows and closed tips."""
from __future__ import annotations

from datetime import date, datetime, time, timezone
from decimal import Decimal

import pytest
from sqlalchemy import create_engine, select, update
from sqlalchemy.orm import sessionmaker

from app.db import Base
from app.market_data.bar_finality import PROVISIONAL_SOURCE
from app.market_data.quality import NSE_TIMEZONE
from app.models import Channel, ChannelAlias, MarketPrice, Stock, Tip, TipDailyProgress, TipSourceExit
from app.tip_ledger import Intake, ReceiptInput, TipExtras, record_intake
from app.tip_matching import stated_terms
from app.tip_minute_bars import MinuteBarsUnavailable
from app.tip_tracker import PriceBar, session_close
from app.tip_tracking_job import track_tips
from app.tip_vocabulary import (
    ALIAS_PACKAGE,
    BAR_BASIS_DAILY,
    BAR_BASIS_INTRADAY,
    CHANNEL_BROKER_APP,
    DATA_BASIS_FINAL,
    DATA_BASIS_PROVISIONAL,
    KIND_TIP,
    MEDIUM_APP_NOTIFICATION,
    OUTCOME_SUCCESS,
    REASON_MISSING_BARS,
    REASON_NO_INTRADAY_BARS,
    STATUS_ACTIVE,
    STATUS_DATA_UNRESOLVED,
    STATUS_HORIZON_EXPIRED,
    STATUS_SOURCE_EXIT,
    STATUS_TARGET_HIT,
)

MON, TUE, WED, THU, FRI = (date(2026, 9, 28), date(2026, 9, 29), date(2026, 9, 30), date(2026, 10, 1),
                           date(2026, 10, 2))
NEXT_MON, NEXT_TUE = date(2026, 10, 5), date(2026, 10, 6)
RUN_AT = datetime(2026, 10, 7, 3, 15, tzinfo=timezone.utc)
RENUKA_KEY = "NSE_EQ|INE087H01022"


def ist(day, hour, minute=0):
    return datetime(day.year, day.month, day.day, hour, minute, tzinfo=NSE_TIMEZONE).astimezone(timezone.utc)


def aware(value):
    return value if value.tzinfo is not None else value.replace(tzinfo=timezone.utc)


def _minute(day, hour, minute_, o, h, l, c):
    return PriceBar(ist(day, hour, minute_), *(Decimal(str(value)) for value in (o, h, l, c)))


class StubMinutes:
    def __init__(self, bars=None, *, down=False):
        self.bars, self.down, self.calls = bars or {}, down, []

    def minute_bars(self, instrument_key, session_date):
        self.calls.append((instrument_key, session_date))
        if self.down:
            raise MinuteBarsUnavailable("upstox down")
        return self.bars.get(session_date, [])


@pytest.fixture
def session():
    engine = create_engine("sqlite:///:memory:")
    Base.metadata.create_all(engine)
    db = sessionmaker(bind=engine)()
    db.add_all([
        Stock(symbol="RENUKA", exchange="NSE", is_active=True, instrument_key=RENUKA_KEY),
        Stock(symbol="RELIANCE", exchange="NSE", is_active=True, instrument_key="NSE_EQ|INE002A01018"),
    ])
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


def _tip(session, *, seen, **overrides):
    terms = dict(symbol="RENUKA", direction="BUY", entry_low=Decimal("23.62"), entry_high=Decimal("23.62"),
                 target=Decimal("26"), stop_loss=Decimal("22.25"), horizon_sessions=None)
    terms.update(overrides)
    receipt = ReceiptInput(
        user_id="user-1", device_event_key="e-1", medium=MEDIUM_APP_NOTIFICATION, app_package="com.upstox.pro",
        channel_label="Upstox", text="BUY RENUKA", device_posted_at=None, parser_version="TCP-001",
    )
    intake = Intake(kind=KIND_TIP, terms=stated_terms(**terms), caller_name=None, extras=TipExtras())
    record_intake(session, receipt, intake, now=seen)
    return session.scalars(select(Tip)).one()


def _bar(session, day, o, h, l, c, *, symbol="RENUKA", source="upstox-v3"):
    stock = session.scalar(select(Stock).where(Stock.symbol == symbol))
    session.add(MarketPrice(
        stock_id=stock.id, timestamp=datetime.combine(day, time(0), NSE_TIMEZONE).astimezone(timezone.utc),
        session_date=day, open=Decimal(str(o)), high=Decimal(str(h)), low=Decimal(str(l)), close=Decimal(str(c)),
        volume=1000, source=source,
    ))
    session.commit()


def _rows(session, tip):
    return session.scalars(
        select(TipDailyProgress).where(TipDailyProgress.tip_id == tip.id).order_by(TipDailyProgress.session_index)
    ).all()


def test_session_zero_without_minute_bars_is_skipped_and_tracking_starts_at_session_one(session):
    tip = _tip(session, seen=ist(MON, 10, 30))
    _bar(session, MON, 23.6, 23.8, 23.4, 23.7)
    _bar(session, TUE, 23.7, 24, 23.5, 23.9)
    _bar(session, WED, 24, 26.2, 23.9, 26.1)

    run = track_tips(session, now=RUN_AT, minute_bars=None)

    assert [(r.session_index, r.session_date, r.status_after, r.data_basis, r.bar_basis) for r in _rows(session, tip)] == [
        (1, TUE, STATUS_ACTIVE, DATA_BASIS_FINAL, BAR_BASIS_DAILY),
        (2, WED, STATUS_TARGET_HIT, DATA_BASIS_FINAL, BAR_BASIS_DAILY),
    ]
    assert (tip.status, tip.outcome, tip.exit_price, tip.entered_session, tip.closed_session) == (
        STATUS_TARGET_HIT, OUTCOME_SUCCESS, Decimal("26"), 1, 2,
    )
    assert (tip.promised_return, tip.actual_return) == (Decimal("0.100762"), Decimal("0.100762"))
    assert aware(tip.closed_at) == session_close(WED)
    assert run.summary() == {
        "basis": DATA_BASIS_FINAL, "tipsExamined": 1, "progressRows": 2, "tipsWaiting": 0,
        "closed": {STATUS_TARGET_HIT: 1},
    }


def test_a_rerun_changes_nothing(session):
    tip = _tip(session, seen=ist(MON, 16))
    _bar(session, TUE, 23.7, 24, 23.5, 23.9)
    track_tips(session, now=RUN_AT, minute_bars=None)
    again = track_tips(session, now=RUN_AT, minute_bars=None)
    assert (len(_rows(session, tip)), again.progress_rows, tip.status) == (1, 0, STATUS_ACTIVE)


def test_a_day_the_whole_ingest_missed_is_not_a_session(session):
    tip = _tip(session, seen=ist(MON, 16), horizon_sessions=1)
    _bar(session, MON, 23.6, 23.8, 23.4, 23.7)
    _bar(session, WED, 23.7, 24, 23.5, 23.9)
    track_tips(session, now=RUN_AT, minute_bars=None)
    assert [(r.session_index, r.session_date) for r in _rows(session, tip)] == [(1, WED)]
    assert (tip.status, tip.exit_price, tip.closed_session) == (STATUS_HORIZON_EXPIRED, Decimal("23.9"), 1)


def test_a_missing_bar_waits_then_resolves_after_five_later_sessions(session):
    tip = _tip(session, seen=ist(MON, 16))
    for day in (TUE, WED, THU, FRI, NEXT_MON):
        _bar(session, day, 1300, 1310, 1290, 1305, symbol="RELIANCE")
    first = track_tips(session, now=RUN_AT, minute_bars=None)
    assert (first.waiting, tip.status, _rows(session, tip)) == (1, STATUS_ACTIVE, [])

    _bar(session, NEXT_TUE, 1300, 1310, 1290, 1305, symbol="RELIANCE")
    track_tips(session, now=RUN_AT, minute_bars=None)
    assert (tip.status, tip.reason, tip.closed_session, tip.outcome) == (STATUS_DATA_UNRESOLVED, REASON_MISSING_BARS, 1, None)
    assert aware(tip.closed_at) == session_close(NEXT_TUE)


def test_minute_bars_fix_a_first_seen_entry_in_session_zero(session):
    tip = _tip(session, seen=ist(MON, 10, 30), entry_low=None, entry_high=None)
    _bar(session, MON, 23.4, 24, 23.3, 23.9)
    minutes = StubMinutes({MON: [_minute(MON, 10, 29, 23.5, 23.6, 23.4, 23.5), _minute(MON, 10, 31, 23.8, 23.9, 23.7, 23.85)]})

    track_tips(session, now=RUN_AT, minute_bars=minutes)

    [row] = _rows(session, tip)
    assert (row.session_index, row.bar_basis, aware(row.intraday_bar_at), row.return_to_date) == (
        0, BAR_BASIS_INTRADAY, ist(MON, 10, 31), Decimal("0.004202"),
    )
    assert (tip.entry_low, tip.entry_high, tip.entry_price) == (Decimal("23.8"),) * 3
    assert (tip.entered_session, tip.promised_return, minutes.calls) == (0, Decimal("0.092437"), [(RENUKA_KEY, MON)])


def test_an_intraday_tip_without_minute_bars_is_data_unresolved(session):
    tip = _tip(session, seen=ist(MON, 10, 30), horizon_sessions=0)
    _bar(session, MON, 23.6, 23.8, 23.4, 23.7)
    track_tips(session, now=RUN_AT, minute_bars=None)
    assert (tip.status, tip.reason, tip.closed_session) == (STATUS_DATA_UNRESOLVED, REASON_NO_INTRADAY_BARS, 0)
    assert aware(tip.closed_at) == session_close(MON)


def test_unavailable_minute_bars_leave_the_tip_waiting(session):
    tip = _tip(session, seen=ist(MON, 10, 30), entry_low=None, entry_high=None)
    _bar(session, MON, 23.4, 24, 23.3, 23.9)
    run = track_tips(session, now=RUN_AT, minute_bars=StubMinutes(down=True))
    assert (run.waiting, tip.status, tip.entry_low, _rows(session, tip)) == (1, STATUS_ACTIVE, None, [])


def test_a_daily_conflict_is_settled_by_minute_bars(session):
    tip = _tip(session, seen=ist(MON, 16))
    _bar(session, TUE, 23.7, 26.5, 22.0, 24)
    minutes = StubMinutes({TUE: [_minute(TUE, 9, 15, 23.6, 26.1, 23.5, 26), _minute(TUE, 9, 16, 25.9, 25.9, 22.0, 22.1)]})
    track_tips(session, now=RUN_AT, minute_bars=minutes)
    [row] = _rows(session, tip)
    assert (tip.status, tip.exit_price, row.bar_basis) == (STATUS_TARGET_HIT, Decimal("26"), BAR_BASIS_INTRADAY)


def test_a_source_exit_after_the_close_takes_the_next_open(session):
    tip = _tip(session, seen=ist(MON, 16))
    session.add(TipSourceExit(tip_id=tip.id, receipt_id=None, stated_exit_price=None, exit_seen_at=ist(TUE, 18)))
    session.commit()
    _bar(session, TUE, 23.7, 24, 23.5, 23.9)
    _bar(session, WED, 24.5, 25, 24.2, 24.8)
    track_tips(session, now=RUN_AT, minute_bars=None)
    assert (tip.status, tip.exit_price, tip.actual_return, tip.outcome, tip.closed_session) == (
        STATUS_SOURCE_EXIT, Decimal("24.5"), Decimal("0.037257"), OUTCOME_SUCCESS, 2,
    )
    assert aware(tip.closed_at) == ist(TUE, 18)


def test_a_provisional_bar_writes_progress_only_and_the_official_bar_closes_the_tip(session):
    tip = _tip(session, seen=ist(MON, 16))
    _bar(session, TUE, 23.7, 24, 23.5, 23.9)
    _bar(session, WED, 24, 26.2, 23.9, 26.1, source=PROVISIONAL_SOURCE)

    evening = track_tips(session, now=RUN_AT, minute_bars=None, provisional=True)

    assert [(r.session_date, r.data_basis, r.status_after) for r in _rows(session, tip)] == [
        (TUE, DATA_BASIS_FINAL, STATUS_ACTIVE), (WED, DATA_BASIS_PROVISIONAL, STATUS_TARGET_HIT),
    ]
    assert (evening.basis, tip.status, tip.exit_price) == (DATA_BASIS_PROVISIONAL, STATUS_ACTIVE, None)

    session.execute(update(MarketPrice).where(MarketPrice.source == PROVISIONAL_SOURCE).values(source="upstox-v3"))
    session.commit()
    track_tips(session, now=RUN_AT, minute_bars=None)
    assert [(r.session_date, r.data_basis) for r in _rows(session, tip)] == [(TUE, DATA_BASIS_FINAL), (WED, DATA_BASIS_FINAL)]
    assert tip.status == STATUS_TARGET_HIT
```

- [ ] **Step 2: Run them to verify they fail**

Run: `python -m pytest tests/test_tip_tracking_job.py -v`
Expected: FAIL with `ModuleNotFoundError: No module named 'app.tip_tracking_job'`.

- [ ] **Step 3: Write the runner**

Create `app/tip_tracking_job.py`:

```python
"""Runs the tip tracker (tip-ledger spec §6.7) over every ACTIVE ledger tip.

Each tip resumes after its last FINAL progress row and replays its due sessions on official bars; only that
pass changes a tip. The provisional pass then writes the next session's row from the same-evening bar."""

from __future__ import annotations

from dataclasses import dataclass, field
from datetime import date, datetime, time, timedelta, timezone

from sqlalchemy import select
from sqlalchemy.orm import Session

from . import tip_ledger_guard  # noqa: F401 -- the tracker may fill a first-seen entry, never change terms
from .market_data.bar_finality import PROVISIONAL_SOURCES, final_bars_only
from .market_data.freshness import session_date_of
from .market_data.quality import NSE_TIMEZONE
from .models import MarketPrice, Stock, Tip, TipDailyProgress, TipSourceExit
from .tip_minute_bars import MinuteBarSource, MinuteBarsUnavailable
from .tip_tracker import (
    MISSING_DATA_SESSIONS,
    Position,
    PriceBar,
    SessionResult,
    Terms,
    TipSession,
    evaluate_session,
    exit_instruction_for,
    outcome_for,
    promised_return,
    session_close,
    sessions_after,
    tip_sessions,
)
from .tip_vocabulary import (
    BAR_BASIS_DAILY,
    BAR_BASIS_INTRADAY,
    DATA_BASIS_FINAL,
    DATA_BASIS_PROVISIONAL,
    REASON_MISSING_BARS,
    REASON_NO_INTRADAY_BARS,
    STATUS_ACTIVE,
    STATUS_DATA_UNRESOLVED,
    STATUS_SOURCE_EXIT,
)


@dataclass
class TrackingRun:
    basis: str
    examined: int = 0
    progress_rows: int = 0
    waiting: int = 0
    closed: dict[str, int] = field(default_factory=dict)

    def summary(self) -> dict:
        return {
            "basis": self.basis,
            "tipsExamined": self.examined,
            "progressRows": self.progress_rows,
            "tipsWaiting": self.waiting,
            "closed": dict(sorted(self.closed.items())),
        }


class _Waiting(Exception):
    """The tip's next session cannot be decided yet; a later run resumes it."""


def _aware(value: datetime) -> datetime:
    return value if value.tzinfo is not None else value.replace(tzinfo=timezone.utc)


def _anchor(day: date) -> datetime:
    """A session's `market_prices.timestamp`: midnight IST."""
    return datetime.combine(day, time(0), NSE_TIMEZONE).astimezone(timezone.utc)


def _seen_on(tip: Tip) -> date:
    return _aware(tip.first_seen_at).astimezone(NSE_TIMEZONE).date()


def _session_dates(session: Session, since: date) -> list[date]:
    """Sessions the candle store holds an official bar for, any stock (decision 3), oldest first."""
    stamps = session.scalars(
        select(MarketPrice.timestamp).where(MarketPrice.timestamp >= _anchor(since), final_bars_only()).distinct()
    ).all()
    return sorted({session_date_of(_aware(stamp)) for stamp in stamps})


def _provisional_date(session: Session, after: date) -> date | None:
    stamps = session.scalars(
        select(MarketPrice.timestamp)
        .where(MarketPrice.timestamp > _anchor(after), MarketPrice.source.in_(PROVISIONAL_SOURCES))
        .distinct()
    ).all()
    dates = {session_date_of(_aware(stamp)) for stamp in stamps}
    return min(dates) if dates else None


def _daily_bars(session: Session, stock_id: int, since: date, *, provisional: bool) -> dict[date, PriceBar]:
    basis = MarketPrice.source.in_(PROVISIONAL_SOURCES) if provisional else final_bars_only()
    rows = session.scalars(
        select(MarketPrice).where(MarketPrice.stock_id == stock_id, MarketPrice.timestamp >= _anchor(since), basis)
    ).all()
    return {
        session_date_of(_aware(row.timestamp)): PriceBar(_aware(row.timestamp), row.open, row.high, row.low, row.close)
        for row in rows
    }


class _TipTracking:
    def __init__(self, session: Session, tip: Tip, market: list[date], source_exit: TipSourceExit | None,
                 minute_bars: MinuteBarSource | None, now: datetime, run: TrackingRun) -> None:
        self.session, self.tip, self.market, self.source_exit = session, tip, market, source_exit
        self.minute_bars, self.now, self.run = minute_bars, now, run
        self.first_seen = _aware(tip.first_seen_at)
        self.terms = Terms(tip.direction, tip.entry_low, tip.entry_high, tip.target_price, tip.stop_loss,
                           tip.horizon_sessions)
        rows = session.scalars(select(TipDailyProgress).where(TipDailyProgress.tip_id == tip.id)).all()
        self.rows = {row.session_date: row for row in rows}
        final = [row for row in rows if row.data_basis == DATA_BASIS_FINAL]
        last = max(final, key=lambda row: row.session_index) if final else None
        self.done = last.session_index if last is not None else -1
        self.position = Position(
            entry_status=tip.entry_status,
            entry_price=tip.entry_price if tip.entry_low is not None else None,
            entered_session=tip.entered_session,
            best_return=last.best_return if last is not None else None,
            worst_return=last.worst_return if last is not None else None,
        )
        self.stock = session.get(Stock, tip.stock_id)

    def final(self) -> bool:
        """Replays the due sessions on official bars; False when the tip has to wait for data."""
        bars = _daily_bars(self.session, self.tip.stock_id, _seen_on(self.tip), provisional=False)
        for due in self._due(self.market):
            later = sessions_after(due.date, self.market)
            day = bars.get(due.date)
            try:
                if day is None and due.index == 0 and self.tip.horizon_sessions > 0:
                    continue  # session 0 is optional (§6.1); without its bars it is skipped
                if day is None:
                    if len(later) < MISSING_DATA_SESSIONS:
                        raise _Waiting
                    self._close(STATUS_DATA_UNRESOLVED, REASON_MISSING_BARS,
                                session_close(later[MISSING_DATA_SESSIONS - 1]), due.index)
                    return True
                minutes = None
                if due.index == 0:
                    minutes = [bar for bar in self._minutes(due.date, later) if bar.at >= self.first_seen]
                    if not minutes:
                        if self.tip.horizon_sessions == 0:
                            self._close(STATUS_DATA_UNRESOLVED, REASON_NO_INTRADAY_BARS, session_close(due.date), 0)
                            return True
                        continue
                result = self._evaluate(due, day, minutes, self.market)
                if result.ambiguous and minutes is None:
                    refined = self._minutes(due.date, later)
                    if refined:
                        minutes, result = refined, self._evaluate(due, day, refined, self.market)
            except _Waiting:
                self.run.waiting += 1
                return False
            self._record(due, result, DATA_BASIS_FINAL, intraday=minutes is not None)
            self._apply(due, result)
            if result.status != STATUS_ACTIVE:
                return True
        return True

    def provisional(self, evening: date) -> None:
        """Decision 10: the next session's row from the same-evening bar; never session 0, never the tip."""
        market = [*self.market, evening]
        due = [s for s in self._due(market) if s.date == evening and s.index > 0]
        if not due:
            return
        day = _daily_bars(self.session, self.tip.stock_id, evening, provisional=True).get(evening)
        if day is None:
            return
        self._record(due[0], self._evaluate(due[0], day, None, market), DATA_BASIS_PROVISIONAL, intraday=False)

    def _due(self, market: list[date]) -> list[TipSession]:
        return [s for s in tip_sessions(self.first_seen, self.tip.horizon_sessions, market) if s.index > self.done]

    def _minutes(self, day: date, later: list[date]) -> list[PriceBar]:
        """A session's minute bars. An outage waits, until 5 later sessions exist; then it counts as none."""
        if self.minute_bars is None or self.stock is None or not self.stock.instrument_key:
            return []
        try:
            return self.minute_bars.minute_bars(self.stock.instrument_key, day)
        except MinuteBarsUnavailable:
            if len(later) < MISSING_DATA_SESSIONS:
                raise _Waiting from None
            return []

    def _evaluate(self, due: TipSession, day: PriceBar, minutes: list[PriceBar] | None,
                  market: list[date]) -> SessionResult:
        instruction = None
        if self.source_exit is not None:
            instruction = exit_instruction_for(
                _aware(self.source_exit.exit_seen_at), self.source_exit.stated_exit_price, due.date, market
            )
        return evaluate_session(self.terms, self.position, due.index, day, minutes=minutes,
                                exit_instruction=instruction)

    def _record(self, due: TipSession, result: SessionResult, data_basis: str, *, intraday: bool) -> None:
        row = self.rows.get(due.date)
        if row is None:
            row = TipDailyProgress(tip_id=self.tip.id, session_date=due.date)
            self.session.add(row)
            self.rows[due.date] = row
        position = result.position
        row.session_index = due.index
        row.entry_status = position.entry_status
        row.status_after = result.status
        row.return_to_date = result.return_to_date
        row.best_return = position.best_return
        row.worst_return = position.worst_return
        row.to_target_pct = result.to_target_pct
        row.to_stop_pct = result.to_stop_pct
        row.bar_basis = BAR_BASIS_INTRADAY if intraday else BAR_BASIS_DAILY
        row.data_basis = data_basis
        row.intraday_bar_at = result.decided_at
        row.recorded_at = self.now
        self.run.progress_rows += 1

    def _apply(self, due: TipSession, result: SessionResult) -> None:
        tip, position = self.tip, result.position
        if tip.entry_low is None and position.entry_price is not None:
            tip.entry_low = tip.entry_high = tip.entry_price = position.entry_price
        tip.entry_status, tip.entered_session = position.entry_status, position.entered_session
        if tip.promised_return is None and position.entry_price is not None:
            tip.promised_return = promised_return(self.terms, position.entry_price)
        self.position, self.done = position, due.index
        if result.status == STATUS_ACTIVE:
            return
        if result.status == STATUS_SOURCE_EXIT:
            closed_at = _aware(self.source_exit.exit_seen_at)
        else:
            closed_at = session_close(due.date)
        self._close(result.status, result.reason, closed_at, due.index,
                    exit_price=result.exit_price, actual_return=result.actual_return)

    def _close(self, status: str, reason: str | None, closed_at: datetime, closed_session: int, *,
               exit_price=None, actual_return=None) -> None:
        tip = self.tip
        tip.status, tip.reason, tip.closed_at, tip.closed_session = status, reason, closed_at, closed_session
        tip.exit_price, tip.actual_return = exit_price, actual_return
        tip.outcome = outcome_for(status, actual_return)
        self.run.closed[status] = self.run.closed.get(status, 0) + 1


def track_tips(session: Session, *, now: datetime, minute_bars: MinuteBarSource | None,
               provisional: bool = False) -> TrackingRun:
    """One pass over every ACTIVE ledger tip; legacy rows (no `match_key`) wait for the §12 backfill."""
    run = TrackingRun(basis=DATA_BASIS_PROVISIONAL if provisional else DATA_BASIS_FINAL)
    tips = session.scalars(
        select(Tip).where(Tip.status == STATUS_ACTIVE, Tip.match_key.is_not(None)).order_by(Tip.id)
    ).all()
    if not tips:
        return run
    since = min(_seen_on(tip) for tip in tips) - timedelta(days=1)
    market = _session_dates(session, since)
    evening = _provisional_date(session, market[-1] if market else since) if provisional else None
    exits = {
        row.tip_id: row
        for row in session.scalars(select(TipSourceExit).where(TipSourceExit.tip_id.in_([tip.id for tip in tips])))
    }
    for tip in tips:
        run.examined += 1
        tracking = _TipTracking(session, tip, market, exits.get(tip.id), minute_bars, now, run)
        if tracking.final() and evening is not None and tip.status == STATUS_ACTIVE:
            tracking.provisional(evening)
        session.commit()
    return run
```

- [ ] **Step 4: Run the tests**

Run: `python -m pytest tests/test_tip_tracking_job.py tests/test_tip_tracker.py -v`
Expected: all PASS.

If a `tip.entry_low` assignment raises `TipLedgerImmutableError`, the fill is not going from NULL to a value on a FIRST_SEEN_PRICE tip. Fix the runner, never the guard.

- [ ] **Step 5: Commit**

```bash
git add app/tip_tracking_job.py tests/test_tip_tracking_job.py
git commit -m "Tip tracker: runner writes progress rows and closes tips on official bars (spec 6.7)

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 7: `TIP_TRACKING` operation — script, registries, CronJobs

**Files:**
- Create: `scripts/run_tip_tracker.py`
- Modify:
  - `app/schedule_orchestration.py`: the operation constant next to `OPERATION_PROVISIONAL_CONFIRMATION`, and a `TRIGGER_POLICIES` entry
  - `app/operation_entrypoints.py`: a `RUNNABLE_JOBS` entry
  - `app/operation_recovery.py`: a `RECOVERY_POLICIES` entry
  - `app/operation_verification.py`: a verifier and a `VERIFIERS` entry
  - `deploy/k8s/base/kustomization.yaml`
- Create: `deploy/k8s/base/tip-tracker-cronjob.yaml`, `deploy/k8s/base/tip-tracker-provisional-cronjob.yaml`
- Test: create `tests/test_run_tip_tracker.py`; modify `tests/test_operation_verification_s7.py`

**Interfaces:**
- Consumes (Task 6): `track_tips`, `TrackingRun.summary()`. Consumes (Task 5): `UpstoxMinuteBars`.
- Produces: `OPERATION_TIP_TRACKING = "TIP_TRACKING"` and `scripts.run_tip_tracker.main(argv=None, *, trigger_type=TRIGGER_SCHEDULED, trigger_source=None, now=None, minute_bars=None) -> dict`. The dedup key is `track:<final|provisional>:<IST date>`.
- The registration pattern is commit `50c23c1` (EPIC-853), which added `PROVISIONAL_CONFIRMATION`. `git show 50c23c1 -- app/operation_*.py app/schedule_orchestration.py` shows each touch point.

- [ ] **Step 1: Write the failing script test**

Create `tests/test_run_tip_tracker.py`:

```python
"""The TIP_TRACKING CronJob entrypoint: one completed execution per basis per IST day."""
from __future__ import annotations

from datetime import datetime, timezone

import pytest
from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker

import scripts.run_tip_tracker as tracker_module
from app.db import Base
from app.models import OrchestrationExecution
from app.schedule_orchestration import OPERATION_TIP_TRACKING

FIRE = datetime(2026, 9, 29, 3, 15, tzinfo=timezone.utc)


class NoMinutes:
    def minute_bars(self, instrument_key, session_date):
        return []


@pytest.fixture
def session_local(monkeypatch):
    engine = create_engine("sqlite:///:memory:")
    Base.metadata.create_all(engine)
    factory = sessionmaker(bind=engine, autoflush=False, expire_on_commit=False)
    monkeypatch.setattr(tracker_module, "SessionLocal", factory)
    return factory


def test_each_basis_runs_once_per_ist_day(session_local):
    first = tracker_module.main([], now=FIRE, minute_bars=NoMinutes())
    again = tracker_module.main([], now=FIRE, minute_bars=NoMinutes())
    evening = tracker_module.main(["--provisional"], now=FIRE, minute_bars=NoMinutes())

    assert (first["status"], first["basis"], first["tipsExamined"]) == ("ok", "FINAL", 0)
    assert again["status"] == "skipped_duplicate_trigger"
    assert (evening["status"], evening["basis"]) == ("ok", "PROVISIONAL")
    with session_local() as db:
        rows = (
            db.query(OrchestrationExecution)
            .filter_by(operation_name=OPERATION_TIP_TRACKING, status="COMPLETED")
            .order_by(OrchestrationExecution.id)
            .all()
        )
    assert [row.trigger_source for row in rows] == ["track:final:2026-09-29", "track:provisional:2026-09-29"]
```

- [ ] **Step 2: Run it to verify it fails**

Run: `python -m pytest tests/test_run_tip_tracker.py -v`
Expected: FAIL with `ImportError: cannot import name 'OPERATION_TIP_TRACKING'`.

- [ ] **Step 3: Declare the operation and its trigger policy**

In `app/schedule_orchestration.py`, directly after the `OPERATION_PROVISIONAL_CONFIRMATION = ...` line, add:

```python
# Tip-ledger spec §6.7: tracks every ACTIVE ledger tip against the candle store.
OPERATION_TIP_TRACKING = "TIP_TRACKING"    # scripts.run_tip_tracker -- tip ledger Phase 2
```

In `TRIGGER_POLICIES`, directly after the `OPERATION_PROVISIONAL_CONFIRMATION: TriggerPolicy(...)` entry, add:

```python
    OPERATION_TIP_TRACKING: TriggerPolicy(
        operation_name=OPERATION_TIP_TRACKING,
        trigger_type=TRIGGER_SCHEDULED,
        cadence=timedelta(days=1),
        requires_market_session=False,
        description=(
            "Tip-ledger spec §6.7: replays every ACTIVE ledger tip session by session on official bars at "
            "08:45 IST Tue-Sat (after PROVISIONAL_CONFIRMATION) and may close it; the 16:10 IST Mon-Fri pass "
            "also writes the evening's provisional progress rows, which never close a tip."
        ),
    ),
```

- [ ] **Step 4: Write the script**

Create `scripts/run_tip_tracker.py`:

```python
"""Tip-ledger spec §6.7: tracks every ACTIVE ledger tip against the candle store.

08:45 IST Tue-Sat on official bars, after the 08:30 confirmation; 16:10 IST Mon-Fri with `--provisional`,
which also writes the evening's progress rows from the provisional capture. Both passes are idempotent."""
from __future__ import annotations

import argparse
from datetime import datetime, timezone

from app.db import SessionLocal
from app.market_data.quality import NSE_TIMEZONE
from app.market_data.upstox import UpstoxClient
from app.provider_auth_manager import provider_auth_manager
from app.schedule_orchestration import (
    ConcurrentExecutionError,
    OPERATION_TIP_TRACKING,
    TRIGGER_SCHEDULED,
    acquire_execution,
    complete_execution,
    fail_execution,
)
from app.settings import settings
from app.tip_minute_bars import UpstoxMinuteBars
from app.tip_tracking_job import track_tips
from app.upstox_market_data_auth import ensure_market_data_credential, register_upstox_auth_providers


def _upstox_client(session, *, at: datetime) -> UpstoxClient:
    """The same credential resolution the ingest uses (EPIC-309)."""
    register_upstox_auth_providers(provider_auth_manager)
    credential = ensure_market_data_credential(session, at=at, manager=provider_auth_manager)
    return UpstoxClient(credential.access_token, settings.upstox_instruments_url)


def main(
    argv: list[str] | None = None,
    *,
    trigger_type: str = TRIGGER_SCHEDULED,
    trigger_source: str | None = None,
    now: datetime | None = None,
    minute_bars=None,
) -> dict:
    parser = argparse.ArgumentParser(description="Track every ACTIVE ledger tip against the candle store.")
    parser.add_argument("--provisional", action="store_true", help="also write the evening's provisional rows")
    args = parser.parse_args(argv)
    now = now or datetime.now(timezone.utc)
    basis = "provisional" if args.provisional else "final"

    with SessionLocal() as session:
        claim = acquire_execution(
            session,
            operation_name=OPERATION_TIP_TRACKING,
            scope_key="GLOBAL",
            trigger_type=trigger_type,
            trigger_source=trigger_source or f"track:{basis}:{now.astimezone(NSE_TIMEZONE).date().isoformat()}",
            triggered_at=now,
        )
        if claim.is_duplicate:
            message = f"tip tracking already completed (execution id={claim.existing.id})"
            print(message)
            return {"status": "skipped_duplicate_trigger", "reason": message}

        source = minute_bars if minute_bars is not None else UpstoxMinuteBars(lambda: _upstox_client(session, at=now))
        try:
            run = track_tips(session, now=now, minute_bars=source, provisional=args.provisional)
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

        summary = run.summary()
        complete_execution(session, claim, started_at=now, completed_at=datetime.now(timezone.utc),
                           result_summary={"status": "ok", **summary})
    print(f"Tip tracking ({basis}): {summary}")
    return {"status": "ok", **summary}


def run() -> None:
    try:
        main()
    except ConcurrentExecutionError as exc:
        print(f"Skipping: {exc}")


if __name__ == "__main__":
    run()
```

- [ ] **Step 5: Run the script test**

Run: `python -m pytest tests/test_run_tip_tracker.py -v`
Expected: PASS.

If the duplicate call returns `"ok"`, then `acquire_execution` doesn't dedupe completed runs by `trigger_source` the way `scripts/run_provisional_confirmation.py` relies on. Read `acquire_execution` in `app/schedule_orchestration.py` and match the confirmation script's use exactly before changing the test.

- [ ] **Step 6: Register the runnable job, recovery policy and verifier**

Run `grep -n "OPERATION_PROVISIONAL_CONFIRMATION" app/*.py`. Add `OPERATION_TIP_TRACKING` to the same import block in every file it lists. Then add these entries:

`app/operation_entrypoints.py`, inside the `RUNNABLE_JOBS` tuple, right after the `PROVISIONAL_CONFIRMATION` job:

```python
        RunnableJob(
            operation_name=OPERATION_TIP_TRACKING,
            label="Track Tips",
            module="scripts.run_tip_tracker",
            kwargs={"argv": []},
        ),
```

`app/operation_recovery.py`, in `RECOVERY_POLICIES`, right after the `OPERATION_PROVISIONAL_CONFIRMATION` entry:

```python
    OPERATION_TIP_TRACKING: RecoveryPolicy(
        operation_name=OPERATION_TIP_TRACKING,
        repeatability=REPEATABLE,
        depends_on=(OPERATION_MARKET_DATA_INGEST,),
        grace=timedelta(hours=4),
        rationale=(
            "Tip ledger Phase 2. Idempotent per tip session -- a FINAL progress row is never rewritten and a "
            "closed tip is never re-tracked. Ordered after the ingest (it needs the official candle). Worth "
            "repairing: a missed pass leaves tips ACTIVE past a target, stop or horizon they already reached."
        ),
    ),
```

`app/operation_verification.py`: add this function directly after `_verify_provisional_confirmation`, and `OPERATION_TIP_TRACKING: _verify_tip_tracking,` to `VERIFIERS` right after the `OPERATION_PROVISIONAL_CONFIRMATION` entry:

```python
def _verify_tip_tracking(session: Session, *, now: datetime) -> OperationVerification:
    """Tip ledger Phase 2. A pass with no ACTIVE tips is ordinary; one that did not report what it examined is not."""
    source = "scripts/run_tip_tracker.py (tip ledger result_summary)"
    summary, refusal = _summary_of(session, OPERATION_TIP_TRACKING)
    if refusal is not None:
        return refusal
    examined = summary.get("tipsExamined")
    if examined is None:
        return OperationVerification(
            operation_name=OPERATION_TIP_TRACKING, verdict=NOT_VERIFIABLE, source=source,
            detail="the run recorded no tipsExamined count, so what it tracked is unknown",
        )
    return OperationVerification(
        operation_name=OPERATION_TIP_TRACKING, verdict=VERIFIED, source=source,
        detail=(
            f"tracked {examined} ACTIVE tip(s) on {summary.get('basis')} bars; closed {summary.get('closed') or {}}, "
            f"{summary.get('tipsWaiting', 0)} waiting for data"
        ),
    )
```

If the grep lists another registry (for example `app/operation_dependencies.py`), add `TIP_TRACKING` there too, following the shape of the PROVISIONAL_CONFIRMATION entry.

- [ ] **Step 7: Bump the verification census**

In `tests/test_operation_verification_s7.py`, in the test whose docstring ends with "EPIC-853 moves it to 21/29 ...":
- Change `assert len(declared) == 29` to `== 30`.
- Change `assert len(set(VERIFIERS) & declared) == 21` to `== 22`.
- Append this paragraph to the docstring:

```
    Tip ledger Phase 2a moves it to 22/30: TIP_TRACKING, with its verifier in
    the same commit; the unowned set is unchanged.
```

- [ ] **Step 8: Add the CronJobs**

Create `deploy/k8s/base/tip-tracker-cronjob.yaml`. The env block is the Upstox set from `provisional-capture-cronjob.yaml`, because the tracker fetches minute bars:

```yaml
# Tip-ledger spec §6.7: tracks every ACTIVE ledger tip on official bars at 08:45 IST Tue-Sat, after the
# 08:30 provisional confirmation, so the official candle has landed. Fetches Upstox 1-minute bars on demand.
apiVersion: batch/v1
kind: CronJob
metadata:
  name: market-agent-tip-tracker
  namespace: market-agent
spec:
  schedule: "15 3 * * 2-6"
  timeZone: "Etc/UTC"
  concurrencyPolicy: Forbid
  startingDeadlineSeconds: 3600
  successfulJobsHistoryLimit: 3
  failedJobsHistoryLimit: 3
  jobTemplate:
    spec:
      backoffLimit: 1
      ttlSecondsAfterFinished: 86400
      activeDeadlineSeconds: 900
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
            - name: tip-tracker
              image: marksy-api:local
              imagePullPolicy: IfNotPresent
              command: ["python", "-m", "scripts.run_tip_tracker"]
              env:
                - name: DATABASE_URL
                  valueFrom:
                    secretKeyRef:
                      name: market-agent-secrets
                      key: DATABASE_URL
                - name: MARKET_DATA_PROVIDER
                  valueFrom:
                    secretKeyRef:
                      name: market-agent-secrets
                      key: MARKET_DATA_PROVIDER
                      optional: true
                - name: UPSTOX_ANALYTICS_TOKEN
                  valueFrom:
                    secretKeyRef:
                      name: market-agent-secrets
                      key: UPSTOX_ANALYTICS_TOKEN
                      optional: true
                - name: UPSTOX_ANALYTICS_TOKEN_EXPIRES_AT
                  valueFrom:
                    secretKeyRef:
                      name: market-agent-secrets
                      key: UPSTOX_ANALYTICS_TOKEN_EXPIRES_AT
                      optional: true
                - name: UPSTOX_ACCESS_TOKEN
                  valueFrom:
                    secretKeyRef:
                      name: market-agent-secrets
                      key: UPSTOX_ACCESS_TOKEN
                      optional: true
                - name: UPSTOX_INSTRUMENTS_URL
                  valueFrom:
                    secretKeyRef:
                      name: market-agent-secrets
                      key: UPSTOX_INSTRUMENTS_URL
                      optional: true
```

Create `deploy/k8s/base/tip-tracker-provisional-cronjob.yaml` as a copy of that file with four changes:
- The first comment line becomes: `# Tip-ledger spec §6.7: the 16:10 IST Mon-Fri pass, after the 15:55 provisional capture; its rows never close a tip.`
- `metadata.name: market-agent-tip-tracker-provisional`
- `spec.schedule: "40 10 * * 1-5"`
- The container command becomes `["python", "-m", "scripts.run_tip_tracker", "--provisional"]`, and the container name becomes `tip-tracker-provisional`.

In `deploy/k8s/base/kustomization.yaml`, add these under `resources:`, right after `- provisional-confirmation-cronjob.yaml`:

```yaml
  - tip-tracker-cronjob.yaml
  - tip-tracker-provisional-cronjob.yaml
```

- [ ] **Step 9: Run the operations consistency tests**

```bash
python -m pytest tests/test_run_tip_tracker.py tests/test_operation_verification_s7.py tests/test_operation_verification.py \
  tests/test_operation_recovery.py tests/test_operation_entrypoints.py tests/test_operation_dependencies.py \
  tests/test_recovery_cadence_contract.py tests/test_recovery_sweep.py tests/test_schedule_orchestration.py \
  tests/test_schedule_registry.py tests/test_scheduler_producer_inventory.py tests/test_cronjob_manifests.py \
  tests/test_api_operations.py tests/test_api_operations_health.py tests/test_operations_health.py \
  tests/test_operations_metrics.py tests/test_job_eligibility.py tests/test_epic317_regressions.py \
  tests/test_market_day_audit.py tests/test_operational_checkpoint.py -v
```

Expected: all PASS. These files pin the operation, CronJob and producer inventories. When one fails, read its assertion:
- If it pins a count or a named set, add TIP_TRACKING (or the two manifests) in that file's existing style, with a one-line docstring or comment naming this phase.
- If it asserts a property the new operation lacks (for example a claim site `scheduler_producers` can't parse), fix the code, not the test.

Compare each failure with `main` (Global Constraints) before editing a test.

- [ ] **Step 10: Commit**

```bash
git add scripts/run_tip_tracker.py app/schedule_orchestration.py app/operation_entrypoints.py app/operation_recovery.py \
  app/operation_verification.py deploy/k8s/base/tip-tracker-cronjob.yaml deploy/k8s/base/tip-tracker-provisional-cronjob.yaml \
  deploy/k8s/base/kustomization.yaml tests/test_run_tip_tracker.py tests/test_operation_verification_s7.py
git add -u tests app
git commit -m "Tip tracker: TIP_TRACKING operation, 08:45 IST official and 16:10 IST provisional CronJobs

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

Run `git status` before committing, and check that `git add -u tests app` staged only the census and registry updates from Step 9.

---

### Task 8: Regression set, PR, merge, deploy, verify

**Files:** none new.

- [ ] **Step 1: Run the regression set**

```bash
rm -f "$TEMP/marksy-pytest-default.db"
python -m pytest tests/test_tip_ledger.py tests/test_tip_ledger_guard.py tests/test_tip_ledger_migration.py \
  tests/test_tip_matching.py tests/test_tip_text_cleaning.py tests/test_tip_daily_progress_migration.py \
  tests/test_tip_tracker.py tests/test_tip_minute_bars.py tests/test_tip_tracking_job.py tests/test_run_tip_tracker.py \
  tests/test_external_tip_ingest.py tests/test_external_tip_outcome.py tests/test_external_tip_scoring.py \
  tests/test_api_tips*.py tests/test_prediction_outcome_monitor.py tests/test_epic853_scripts.py -v
python -m alembic heads
```

Expected: all PASS, and one head, `0182_tip_daily_progress`. Handle any failure as the Global Constraints describe (check `main`, and note pre-existing failures in the PR).

- [ ] **Step 2: Push and open the PR**

```bash
git push -u origin feat/tip-tracker
gh pr create --title "Tip ledger Phase 2a: tracker, progress rows, outcomes" --body "$(cat <<'EOF'
Tracks every ACTIVE ledger tip session by session (tip-ledger spec §6) and closes it on target, stop-loss,
horizon or a source exit, with the §6.6 outcome and returns.

- Only intake rejections stay matchable; tracker-closed DATA_UNRESOLVED tips no longer absorb new copies
- `tip_daily_progress` (migration 0182): one row per tip per session, PROVISIONAL rows replaced by FINAL
- Pure engine `app/tip_tracker.py`; runner `app/tip_tracking_job.py`; Upstox 1-minute bars on demand, never stored
- `TIP_TRACKING` operation: 08:45 IST Tue-Sat official pass, 16:10 IST Mon-Fri provisional pass
- Marksy predictions (2b) and the rating-engine port (2c) follow in their own PRs

Tests: tip ledger, tracker, runner, minute bars, migration, script, operations consistency and tip regression files.

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```

- [ ] **Step 3: Merge and deploy**

```bash
gh pr merge --merge --delete-branch
ssh marksy@103.178.160.233 'bash ~/deployment/server/vps-deploy.sh'
```

Expected: the deploy script finishes, and its migrate Job applies `0182_tip_daily_progress`.

- [ ] **Step 4: Verify production**

```bash
echo "select version_num from alembic_version; select count(*) from tip_daily_progress; select status, count(*) from tips group by status;" \
  | ssh marksy@103.178.160.233 'KUBECONFIG=~/.kube/config kubectl -n market-agent exec -i deploy/postgres -- sh -c '"'"'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB"'"'"''
ssh marksy@103.178.160.233 'KUBECONFIG=~/.kube/config kubectl -n market-agent get cronjob market-agent-tip-tracker market-agent-tip-tracker-provisional'
ssh marksy@103.178.160.233 'KUBECONFIG=~/.kube/config kubectl -n market-agent create job --from=cronjob/market-agent-tip-tracker tip-tracker-first-run && KUBECONFIG=~/.kube/config kubectl -n market-agent wait --for=condition=complete job/tip-tracker-first-run --timeout=600s; KUBECONFIG=~/.kube/config kubectl -n market-agent logs job/tip-tracker-first-run'
```

Expected:
- `alembic_version` is `0182_tip_daily_progress`.
- Both CronJobs are listed.
- The first-run log prints `Tip tracking (final): {'basis': 'FINAL', 'tipsExamined': N, ...}`, with no traceback.
- Re-running the `select status ...` query shows ACTIVE tips either still ACTIVE or closed with a tracker status.

- [ ] **Step 5: Clean up the worktree**

```bash
cd /c/AIAgent/marksy-api && git worktree remove ../marksy-api-phase2a && git checkout main && git pull
```

## Summary

Phase 2a gives the ledger its tracker. Every ACTIVE ledger tip is replayed on official daily bars, with minute bars only where order matters. It gets one progress row per session and ends with a spec §6 status, an outcome, `promised_return` and `actual_return`. Evening provisional rows show progress without ever deciding a tip. The next plans are 2b (Marksy predictions into the ledger: `Prediction.status` sync, withdrawals as source exits, retiring the monitor's price checks) and 2c (the rating-engine port). Phase 3 (scorecards and APIs) reads what this phase writes.

## Validation

- Task 1: `tests/test_tip_ledger.py`, `tests/test_tip_matching.py`
- Task 2: `tests/test_tip_daily_progress_migration.py`, `tests/test_tip_ledger_migration.py`, `tests/test_tip_ledger.py`
- Tasks 3–4: `tests/test_tip_tracker.py`
- Task 5: `tests/test_tip_minute_bars.py`, `tests/test_upstox_cost_safety.py`, `tests/test_provider_governance.py`
- Task 6: `tests/test_tip_tracking_job.py`, `tests/test_tip_tracker.py`
- Task 7: `tests/test_run_tip_tracker.py` plus the operations consistency set in Task 7 Step 9
- Task 8: the regression set in Task 8 Step 1, `alembic heads`, and the production checks in Task 8 Step 4
