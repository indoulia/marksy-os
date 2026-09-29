# Tip Ledger Phase 0 + Phase 1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Take invalidated predictions out of the OPEN population (Phase 0), then make marksy-api's `tips` table the central ledger for external calls — channels, callers, one receipt per customer, server-side cleaning and parsing, match-key dedupe, revisions, source exits, intake rejections and the phone capture list — without breaking the current app or admin-app (Phase 1).

**Architecture:** Phase 0 syncs `Prediction.status` in `app/prediction_outcome_monitor.py` and ships a data migration for rows already stuck at OPEN. Phase 1 renames `external_tips`→`tips` with the old comparison state kept as `comparison_status`, so every EPIC-803/845 reader keeps working. It adds five pure modules (vocabulary, cleaning, horizon, parser, matching), an ORM guard and one DB-facing intake module (`app/tip_ledger.py`), and routes legacy `POST /tips` (APP_NOTIFICATION), the admin paste (MANUAL) and the new captured-message payload of `POST /tips/ingest-text` through that module.

**Tech Stack:** Python 3.12, FastAPI 0.139, SQLAlchemy 2.0.52 (`Mapped`/`mapped_column`), Pydantic 2.13, Alembic 1.19, pytest on SQLite 3.49 (tests) and PostgreSQL (prod).

**Spec:** `C:\AIAgent\marksy-os\docs\superpowers\specs\2026-09-29-marksy-central-tip-ledger-design.md` (sections §2–§6.2, §9, §12, §13 phases 0–1)

## Global Constraints

- Repo: `C:\AIAgent\marksy-api`. Each phase runs in its own git worktree and branch. Phase 0 must be merged before Phase 1 branches from `main`. Never stack the two.
- Commit once per task. Open a PR with `gh pr create` at the end of each phase. **The user merges marksy-api PRs and runs the deploy** (`ssh marksy@103.178.160.233 'bash ~/deployment/server/vps-deploy.sh'`). Hand both to the user. Never run either yourself.
- Commit messages end with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`. PR bodies end with `🤖 Generated with [Claude Code](https://claude.com/claude-code)`.
- Tests: run only the `python -m pytest tests/<file>.py -v` commands given in each step. Never run the full suite (it takes about 2 hours, and 4 tests already fail on main locally). If a listed test fails, run the same file on `main` in `C:\AIAgent\marksy-api`. If it also fails there, it is pre-existing: note it in the PR and don't fix it.
- `conftest.py` gives the app engine a file DB at `%TEMP%\marksy-pytest-default.db`. `create_all` never alters an existing table, so delete that file whenever the ORM schema changes (Task 8 says when).
- Code style follows the repo: `Mapped`/`mapped_column`, `BigInteger().with_variant(Integer, "sqlite")` primary keys, no ORM relationships, camelCase API fields, the `success(...)` envelope. Comments are one line and explain a non-obvious WHY only. No unrelated refactors.
- Alembic revision ids must be ≤ 32 characters. Every migration must run on both SQLite and PostgreSQL. Write partial indexes with both `postgresql_where=` and `sqlite_where=`.
- Spec §2.5, verbatim: "Call terms (channel, symbol, direction, stated entry, target, stop-loss, stated horizon) and `first_seen_at` are immutable. Tips and receipts are never deleted."
- Spec §5.2 match key, verbatim: "**match_key v1** = sha256 of `v1|canonical_channel_id|SYMBOL|direction|entry_low|entry_high|target|stop_loss|horizon_sessions`, built from **stated** terms only (defaults and first-seen prices never enter it). Prices are rounded to the 0.05 tick and printed with 2 decimals; a missing value is `-`. The caller is excluded."
- Matchable (§5.2): "ACTIVE, or rejected at intake (§6.2) with `first_seen_at` within its horizon".
- `status` vocabulary (§4): `ACTIVE | TARGET_HIT | STOP_LOSS_HIT | SOURCE_EXIT | HORIZON_EXPIRED | DIRECTION_HORIZON | INVALIDATED | UNSCORABLE | DATA_UNRESOLVED | MERGED_DUPLICATE`. `entry_status`: `WAITING | ENTERED`. `outcome`: `SUCCESS | FAILURE | EXPIRED | null`.
- Receipt `kind`: `TIP | REVISION | EXIT | UNPARSED`. `medium`: `APP_NOTIFICATION | SMS | WHATSAPP | TELEGRAM | EMAIL | MANUAL`. Channel types: `BROKER_APP, NEWS_PORTAL, SMS_SENDER, WHATSAPP_GROUP, TELEGRAM_CHANNEL, YOUTUBE, MARKSY`.
- Receipt uniqueness (§4): "unique `(user_id, device_event_key)` makes retries safe; unique `(tip_id, user_id, medium)` for kind TIP".
- `default_horizon_sessions (20)`. "1 week = 5 sessions, 1 month = 20" (§6.1).
- Intake rejections (§6.2): no direction → `UNSCORABLE` (`NO_DIRECTION`). Target or stop-loss on the wrong side → `UNSCORABLE` (`INCONSISTENT_LEVELS`). Symbol not resolvable → `DATA_UNRESOLVED` (`UNRESOLVED_SYMBOL`). Rejected tips are terminal with `closed_at = first_seen_at` (§6.6).
- Cleaning (§5.1): mask "the customer's username, phone numbers, emails, PAN-like codes (`[A-Z]{5}[0-9]{4}[A-Z]`) and digit runs of 8 or more".
- Privacy (§2.11, hard requirement): legacy `POST /tips` must not persist a raw `title` or `body`. Only cleaned text is stored. Migration 0181 scrubs rows stored before Phase 1 the same way (one-way). Customer data stays within the existing `User` columns.

## Review Focus

These are the inputs the spec implies but its happy paths never exercise. Each one has a test in the owning task:

1. A mobile number next to prices ("TGT 650. Queries: 98765 43210") must become `[PHONE]` while every price stays intact. Test: Task 4, `test_a_mobile_number_beside_prices_is_masked_without_touching_the_prices`.
2. A 1:1 chat whose label is the sender's phone number, or a group label "Rahul @ StockTips", must give a channel label of `[PHONE]` or `StockTips`, never the person. Tests: Task 4, `test_a_one_to_one_chat_titled_with_a_phone_number_is_masked` and `test_a_group_label_drops_the_individual_sender`.
3. The same customer can get the same call twice on one medium under a new device key (a duplicate notification, a reinstall). That must return the existing receipt, not a 500 from the `(tip_id, user_id, medium)` index. Test: Task 11, `test_the_same_customer_twice_on_one_medium_keeps_one_receipt`.
4. Two customers can see the same call for the first time concurrently. The second must attach to the first one's tip, not hit a 500 from the ACTIVE `match_key` index. Test: Task 11, `test_a_concurrent_first_sighting_attaches_instead_of_failing`.
5. A customer-controlled channel label of "Marksy" must never land on Marksy's own channel, because that would forge Marksy's record. Test: Task 11, `test_a_customer_label_never_lands_on_marksys_channel`.

## Resolved ambiguities (decisions this plan makes)

1. **`external_tips.status` conflicts with the lifecycle vocabulary.** It holds the EPIC-803 comparison state (`RECEIVED/COMPARED/UNRESOLVED_SYMBOL/FAILED/RESOLVED`), which `resolve_due_external_tips` filters on and the app reads (`status == "FAILED"` is terminal in `MarksyTipsApiClient.kt`). Migration 0181 renames it to `comparison_status` and adds a new nullable lifecycle `status`. Legacy rows keep their value in `comparison_status`; their `status` stays NULL until the §12 backfill. Readers updated: `api/services/tips.py`, `app/external_tip_outcome.py`, and fixtures in 3 test files. The API field `status` in `TipAccepted`/`TipView`/`TipListItem` still reports the comparison state.
2. The spec's `target` is the existing `target_price` column. The legacy `entry_price` (stated entry midpoint) and `horizon_days` (stated horizon only) stay populated so the EPIC-803/845 comparison and outcome code keep working unchanged.
3. **CallHorizon values follow the spec, not the app.** Month is 20 sessions (spec §6.1; the Kotlin code uses 21), a year is 250 (matches spec §7 LONG = 250), and Intraday is 0 sessions (spec §6.1 N = 0; Kotlin uses 1). The Python test mirrors assert these spec values.
4. The ported parser also reads an entry range ("in the range of 150-152" gives `entry_low` 150 and `entry_high` 152). Kotlin kept 150 only, and spec §4 stores a range.
5. **Kind detection is new code (it does not exist in Kotlin).**
   - An exit keyword (exit / book profit / square off / close position) that appears before any complete call makes the message an EXIT.
   - Revision wording (revised / modified / trail / "new target") plus at least one level makes it a REVISION.
   - Otherwise the TradeCallParser rule applies (side + symbol + 2 levels = TIP), and anything else is UNPARSED.
   - A REVISION with no ACTIVE original is recorded as a TIP. A revision inherits only the direction; other terms are only what it states.
   - Legacy `POST /tips` is always kind TIP, because the phone already parsed its terms.
6. EXIT receipts are recorded in a new `tip_source_exits` table (tip, receipt, stated price, time; the first exit per tip wins). Phase 2's tracker closes the tip as SOURCE_EXIT. Phase 1 never sets a terminal price-based status (§6.7: "Terminal transitions use official data only").
7. Columns added beyond §4:
   - `channels.capture_enabled`: auto-created channels don't silently join every phone's capture list.
   - `channel_aliases.kind` (PACKAGE | LABEL): so the capture list does not have to guess which aliases are package names.
   - `tip_receipts.public_id` (a uuid, as tips use, so ids don't leak row counts).
8. `tip_receipts.user_id` has no foreign key. A config-only user (AUTH_CREDENTIALS) has no `users` row, and `get_user_scopes` treats any `users` row as an override, so creating one would wipe that user's scopes. `legacy-unknown` is created by the migration as a disabled `users` row with no scopes.
9. **Channel resolution.**
   - APP_NOTIFICATION: resolve by package, then by label.
   - WhatsApp, Telegram, SMS, email and pastes: resolve by label only. A WhatsApp group must not collapse into the "WhatsApp" app channel.
   - An unknown alias creates a channel of the medium's type. EMAIL and MANUAL map to NEWS_PORTAL.
   - An alias that resolves to the MARKSY channel is rejected with 422, and the Marksy channel is seeded with no alias.
10. The §5.1 payload uses camelCase (`deviceEventKey, medium, appPackage, channelLabel, text, devicePostedAt`), matching this API's convention. A body without `deviceEventKey` keeps the admin-paste contract (`text`, `dryRun`).
11. **`tip_daily_progress` is deferred to Phase 2.** Its columns (`bar_basis`, `to_target_pct`, `return_to_date`, …) are defined by the tracker's rules, and a table with no writer cannot be tested. Landing it with its writer avoids a Phase-2 migration that amends it.
12. Phase 0 includes a data migration that moves predictions the monitor already invalidated from OPEN to INVALIDATED. Without it, production keeps showing them as open.
13. **The current app does not use an integration key.** It sends `POST /tips` with `Authorization: Bearer <session>` (`gateway/MarksyTipsApiClient.kt:100`), and the backend accepts only Bearer or `X-API-Key`. `X-Marksy-Integration-Key` exists only as a comment in `app/settings.py:148`. So an app receipt's customer is the session user, an API-key receipt's customer is `bound_user_id`, and `legacy-unknown` is the fallback.
14. **Legacy PII is scrubbed now, not in Phase 6.** Migration 0181 masks every existing row, and the scrub is one-way (downgrade cannot restore raw text).
    - `raw_payload.title` keeps only a chat's group ("Rahul @ StockTips" → "StockTips").
    - A WhatsApp/Telegram title without " @ " is a 1:1 contact, i.e. a person, and becomes `[CONTACT]`. §15's 1:1 labels apply only to chats a customer allow-listed in the new payload; legacy titles came from any chat.
    - `body`, the `rationale` column and every other free-text `raw_payload` value are masked for phones, emails, PAN codes and 8+ digit runs. That covers the app payload's title/body/rationale and the admin paste's rationale plus arbitrary extra report fields.
    - Machine keys (`sourceReference`, prices, timestamps, package) are left alone. No username is known for legacy rows, so the username mask is skipped.
    - The regexes are copied into the migration, not imported, so it replays identically after `app/tip_text_cleaning.py` changes.
    - The Phase 6 backfill builds receipts from this cleaned text, which is all a receipt stores anyway.
15. Intake consistency without a stated entry: a BUY target must be above its stop and a SELL target below it. Any stated price ≤ 0 is INCONSISTENT_LEVELS, which also guards the division in `promised_return`.
16. Directions normalize as follows: LONG and ACCUMULATE become BUY, SHORT becomes SELL, and anything else (including HOLD) becomes null, which is UNSCORABLE at intake.

## File Structure

Phase 0 (branch `fix/prediction-invalidated-status`):
- Modify `app/prediction_outcome_monitor.py`: `record_invalidation` (:174-198) and the decay branch of `evaluate_prediction_realtime` (:279-290) also set `Prediction.status`.
- Modify `tests/test_prediction_outcome_monitor.py`: status assertions.
- Create `migrations/versions/0180_invalidated_status.py`: fixes predictions that are already invalidated.
- Create `tests/_migration_helpers.py`: runs one revision's `upgrade()`/`downgrade()` on a connection (reused in Phase 1).
- Create `tests/test_invalidated_status_migration.py`.

Phase 1 (branch `feat/tip-ledger-intake`):
- Create `app/tip_vocabulary.py`: constants only (statuses, kinds, mediums, channel types, bases, parser versions, `legacy-unknown`).
- Create `app/tip_text_cleaning.py`: `clean_tip_text`, `channel_label_for`. Pure.
- Create `app/call_horizon.py`: port of `CallHorizon.kt`. Pure.
- Create `app/trade_call_parser.py`: port of `TradeCallParser.kt` plus TIP/REVISION/EXIT/UNPARSED detection. Pure.
- Create `app/tip_matching.py`: `StatedTerms`, direction normalization, `match_key_v1`, `intake_rejection`. Pure.
- Modify `app/models.py`: `ExternalTip`→`Tip` (table `tips`, `comparison_status`, ledger columns, alias `ExternalTip = Tip`); FKs of `ExternalTipComparison`/`ExternalTipOutcome`; new `Channel`, `ChannelAlias`, `Caller`, `TipReceipt`, `TipSourceExit`.
- Create `app/tip_ledger_guard.py`: ORM listeners that reject term changes and deletes.
- Create `app/tip_ledger.py`: channel/caller resolution, matching, receipts, revisions, exits (the only DB-facing intake module).
- Modify `app/external_tip_outcome.py` (:147, :204): read and write `comparison_status`.
- Create `migrations/versions/0181_tip_ledger.py`: rename, new tables and columns, indexes, seeds, `legacy-unknown`, one-way scrub of legacy notification text.
- Modify `api/services/tips.py`: `submit_tip`, `ingest_text` → ledger; new `ingest_message`; `comparison_status` readers.
- Modify `api/schemas/tips.py`: `TipIngestRequest` message fields, `TipIngestResult` receipt fields, and the `TipRequest` docstring.
- Modify `api/routers/tips.py`: customer id; message vs paste dispatch.
- Create `api/schemas/channels.py`, `api/services/channels.py`, `api/routers/channels.py`: `GET /channels/capture-list`.
- Modify `api/app.py`: register the channels router.
- Tests to create: `tests/test_tip_text_cleaning.py`, `tests/test_call_horizon.py`, `tests/test_trade_call_parser.py`, `tests/test_tip_matching.py`, `tests/test_tip_ledger_guard.py`, `tests/test_tip_ledger_migration.py`, `tests/test_tip_ledger.py`, `tests/test_api_tips_ledger.py`, `tests/test_api_channels.py`.
- Tests to modify: `tests/test_api_tips.py`, `tests/test_api_tips_ingest.py`, `tests/test_external_tip_outcome.py`, `tests/test_external_tip_scoring.py`.

---

# Phase 0 — `Prediction.status` sync

### Task 1: Close invalidated predictions in the outcome monitor

**Files:**
- Modify: `app/prediction_outcome_monitor.py:174-198` (`record_invalidation`), `:279-290` (decay branch)
- Test: `tests/test_prediction_outcome_monitor.py`

**Interfaces:**
- Consumes: `record_invalidation(session, prediction, *, at, evidence) -> PredictionOutcomeEvent`, `evaluate_prediction_realtime(...)` (unchanged signatures).
- Produces: `PREDICTION_STATUS_OPEN = "OPEN"`, `PREDICTION_STATUS_INVALIDATED = "INVALIDATED"` in `app.prediction_outcome_monitor`. An invalidated prediction's `status` becomes `"INVALIDATED"` only when it was `"OPEN"`.

- [ ] **Step 1: Create the Phase 0 worktree**

```bash
git -C /c/AIAgent/marksy-api fetch origin
git -C /c/AIAgent/marksy-api worktree add /c/AIAgent/marksy-api-worktrees/phase0 -b fix/prediction-invalidated-status origin/main
cd /c/AIAgent/marksy-api-worktrees/phase0
```

Every later Phase 0 command runs from `/c/AIAgent/marksy-api-worktrees/phase0`.

- [ ] **Step 2: Write the failing tests**

In `tests/test_prediction_outcome_monitor.py`, add `record_invalidation,` to the `from app.prediction_outcome_monitor import (...)` list (alphabetical, after `get_terminal_event,`), then append:

```python
def test_material_assumption_decay_takes_the_prediction_out_of_the_open_population(session):
    stock = make_stock(session)
    prediction = make_prediction(session, stock, horizon_days=5, entry_price="100", target_return="0.05", stop_return="-0.03")
    session.add(RecommendationEvidenceItem(
        prediction_id=prediction.id, evidence_category=EVIDENCE_CATEGORY_NEWS, status=STATUS_AVAILABLE, source="test",
        reference=None, evidence_timestamp=AS_OF - timedelta(hours=12), is_stale=False, snapshot_rule_version="EVS-001",
        captured_at=AS_OF,
    ))
    session.commit()

    evaluate_prediction_realtime(session, prediction, as_of=AS_OF + timedelta(days=3))

    assert session.get(Prediction, prediction.id).status == "INVALIDATED"


def test_record_invalidation_takes_the_prediction_out_of_the_open_population(session):
    stock = make_stock(session)
    prediction = make_prediction(session, stock)

    record_invalidation(session, prediction, at=AS_OF + timedelta(days=1), evidence={"trigger": "official_bar_confirmation"})

    assert session.get(Prediction, prediction.id).status == "INVALIDATED"


def test_record_invalidation_leaves_a_superseded_prediction_superseded(session):
    stock = make_stock(session)
    prediction = make_prediction(session, stock)
    prediction.status = "SUPERSEDED"
    session.commit()

    record_invalidation(session, prediction, at=AS_OF + timedelta(days=1), evidence={"trigger": "official_bar_confirmation"})

    assert session.get(Prediction, prediction.id).status == "SUPERSEDED"
```

- [ ] **Step 3: Run the tests to verify they fail**

Run: `python -m pytest tests/test_prediction_outcome_monitor.py -v`
Expected: the two `..._takes_the_prediction_out_of_the_open_population` tests FAIL with `AssertionError: assert 'OPEN' == 'INVALIDATED'`. The SUPERSEDED test passes (nothing writes status yet). Every other test passes.

- [ ] **Step 4: Implement**

In `app/prediction_outcome_monitor.py`, directly below `MATERIAL_MOVEMENT_RATIO_THRESHOLD = Decimal("0.6")`, add:

```python
# tip-ledger spec §3: 12+ modules read `Prediction.status == "OPEN"`, so an invalidation must show there.
PREDICTION_STATUS_OPEN = "OPEN"
PREDICTION_STATUS_INVALIDATED = "INVALIDATED"


def _close_invalidated(prediction: Prediction) -> None:
    # SUPERSEDED and EVALUATED already say "not open"; only OPEN moves.
    if prediction.status == PREDICTION_STATUS_OPEN:
        prediction.status = PREDICTION_STATUS_INVALIDATED
```

In `record_invalidation`, replace

```python
    session.add(event_row)
    session.commit()
    session.refresh(event_row)
    return event_row


def detect_material_movement(
```

with

```python
    session.add(event_row)
    _close_invalidated(prediction)
    session.commit()
    session.refresh(event_row)
    return event_row


def detect_material_movement(
```

In `evaluate_prediction_realtime`, inside `if decay.verdict == VERDICT_MATERIAL_DECAY and decay.invalidation_recommended:`, replace

```python
            monitor_rule_version=MONITOR_RULE_VERSION,
        )
        session.add(event_row)
        session.commit()
        session.refresh(event_row)
        return event_row

    rows = list(
```

with

```python
            monitor_rule_version=MONITOR_RULE_VERSION,
        )
        session.add(event_row)
        _close_invalidated(prediction)
        session.commit()
        session.refresh(event_row)
        return event_row

    rows = list(
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `python -m pytest tests/test_prediction_outcome_monitor.py -v`
Expected: all PASS.

- [ ] **Step 6: Commit**

```bash
git add app/prediction_outcome_monitor.py tests/test_prediction_outcome_monitor.py
git commit -m "$(cat <<'EOF'
Close predictions the outcome monitor invalidates

record_invalidation and the assumption-decay branch now move an OPEN
prediction to INVALIDATED, so the 12+ readers of status == "OPEN" stop
treating invalidated calls as live (tip-ledger spec phase 0).

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
)"
```

### Task 2: Heal predictions that were already invalidated

**Files:**
- Create: `migrations/versions/0180_invalidated_status.py`
- Create: `tests/_migration_helpers.py`
- Test: `tests/test_invalidated_status_migration.py`

**Interfaces:**
- Consumes: `app.recommendations.record_recommendation(...)`, `PredictionOutcomeEvent` (existing).
- Produces: `tests._migration_helpers.run_revision(connection, revision: str, step: str = "upgrade") -> None` (Phase 1's Task 10 reuses it), and Alembic revision `0180_invalidated_status` with `down_revision = "0179_scan_data_basis"`.

- [ ] **Step 1: Confirm the head**

Run: `grep -l 'down_revision = "0179_scan_data_basis"' migrations/versions/*.py`
Expected: no output (0179 is still the head). If a file is listed, set this migration's `down_revision` to the real head instead (`python -m pytest tests/test_alembic_single_head.py` will catch a mistake).

- [ ] **Step 2: Write the helper and the failing test**

Create `tests/_migration_helpers.py`:

```python
"""Run one Alembic revision's upgrade or downgrade against a live connection, so a migration's
DDL and data steps are proven on SQLite without replaying the whole chain."""

from __future__ import annotations

import importlib.util
from pathlib import Path

from alembic.migration import MigrationContext
from alembic.operations import Operations

_VERSIONS = Path(__file__).resolve().parent.parent / "migrations" / "versions"


def run_revision(connection, revision: str, step: str = "upgrade") -> None:
    spec = importlib.util.spec_from_file_location(f"_migration_{revision}", _VERSIONS / f"{revision}.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    with Operations.context(MigrationContext.configure(connection)):
        getattr(module, step)()
```

Create `tests/test_invalidated_status_migration.py`:

```python
"""0180_invalidated_status: predictions the monitor already invalidated leave the OPEN population."""

from __future__ import annotations

from datetime import datetime, timezone
from decimal import Decimal

from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker

from app.db import Base
from app.models import Prediction, PredictionOutcomeEvent, Stock
from app.recommendations import record_recommendation
from tests._migration_helpers import run_revision

AS_OF = datetime(2026, 8, 10, tzinfo=timezone.utc)


def _prediction(session, symbol, status="OPEN"):
    stock = Stock(symbol=symbol, exchange="NSE", is_active=True)
    session.add(stock)
    session.flush()
    prediction = record_recommendation(
        session, stock_id=stock.id, as_of_timestamp=AS_OF, entry_price=Decimal("100"), horizon_days=5,
        target_return=Decimal("0.05"), stop_return=Decimal("-0.03"), predicted_probability=Decimal("0.7"),
        confidence=Decimal("0.8"), model_version="m1-baseline-1", feature_version="f1",
        consensus_contract_version="c1", horizon_selection_version="h1", scoring_contract_version="s1",
        opportunity_score=Decimal("60.00"),
    )
    prediction.status = status
    session.commit()
    return prediction


def _event(session, prediction, state):
    session.add(PredictionOutcomeEvent(
        prediction_id=prediction.id, state=state, detected_at=AS_OF, observed_at=AS_OF, observed_price=None,
        provider=None, prediction_version="v", evidence={}, monitor_rule_version="RTM-001",
    ))
    session.commit()


def test_open_predictions_with_an_invalidation_event_are_closed_and_nothing_else_moves():
    engine = create_engine("sqlite:///:memory:")
    Base.metadata.create_all(engine)
    session = sessionmaker(bind=engine)()
    invalidated = _prediction(session, "AAA")
    _event(session, invalidated, "INVALIDATED")
    target_hit = _prediction(session, "BBB")
    _event(session, target_hit, "TARGET_HIT")
    superseded = _prediction(session, "CCC", status="SUPERSEDED")
    _event(session, superseded, "INVALIDATED")
    untouched = _prediction(session, "DDD")
    ids = [p.id for p in (invalidated, target_hit, superseded, untouched)]

    run_revision(session.connection(), "0180_invalidated_status")
    session.commit()
    session.expire_all()

    assert [session.get(Prediction, pid).status for pid in ids] == ["INVALIDATED", "OPEN", "SUPERSEDED", "OPEN"]
    session.close()
```

- [ ] **Step 3: Run the test to verify it fails**

Run: `python -m pytest tests/test_invalidated_status_migration.py -v`
Expected: FAIL with `FileNotFoundError` for `0180_invalidated_status.py`.

- [ ] **Step 4: Write the migration**

Create `migrations/versions/0180_invalidated_status.py`:

```python
"""Close predictions the outcome monitor already invalidated (tip-ledger spec §3, phase 0).

Before phase 0, `record_invalidation` and the assumption-decay branch wrote a terminal
INVALIDATED `prediction_outcome_events` row but left `predictions.status` at OPEN, so every
reader of `status == 'OPEN'` kept those calls live. Only OPEN rows move; SUPERSEDED and
EVALUATED already say "not open".

Not reversible: which rows were OPEN before is not recorded, and reopening them would
re-create the defect, so `downgrade` is a no-op.
"""
from __future__ import annotations

import sqlalchemy as sa
from alembic import op

revision = "0180_invalidated_status"
down_revision = "0179_scan_data_basis"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.execute(
        sa.text(
            "UPDATE predictions SET status = 'INVALIDATED' "
            "WHERE status = 'OPEN' AND id IN "
            "(SELECT prediction_id FROM prediction_outcome_events WHERE state = 'INVALIDATED')"
        )
    )


def downgrade() -> None:
    pass
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `python -m pytest tests/test_invalidated_status_migration.py tests/test_alembic_single_head.py -v`
Expected: all PASS.

- [ ] **Step 6: Commit**

```bash
git add migrations/versions/0180_invalidated_status.py tests/_migration_helpers.py tests/test_invalidated_status_migration.py
git commit -m "$(cat <<'EOF'
Heal predictions already invalidated while still OPEN

Data migration 0180 moves OPEN predictions that carry an INVALIDATED
outcome event to INVALIDATED, so production stops listing them as live.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
)"
```

### Task 3: Phase 0 regression set, PR, hand-off

**Files:** none changed.

**Interfaces:**
- Consumes: Tasks 1–2.
- Produces: an open PR on `fix/prediction-invalidated-status`.

- [ ] **Step 1: Run the prediction regression set**

Run: `python -m pytest tests/test_prediction_outcome_monitor.py tests/test_epic853_confirmation.py tests/test_epic853_invalidated_on_next_run.py tests/test_prediction_lifecycle_capacity.py tests/test_api_predictions_active.py tests/test_invalidated_status_migration.py tests/test_alembic_single_head.py -v`
Expected: all PASS. For any failure, run that file on `main` in `C:\AIAgent\marksy-api`. If it also fails there, note it in the PR body; if not, fix it before continuing.

- [ ] **Step 2: Push and open the PR**

```bash
git push -u origin fix/prediction-invalidated-status
gh pr create --base main --head fix/prediction-invalidated-status --title "Close invalidated predictions (tip ledger phase 0)" --body "$(cat <<'EOF'
## Summary
- `record_invalidation` and the assumption-decay branch now set `Prediction.status = INVALIDATED` (OPEN rows only), so the 12+ `status == "OPEN"` readers stop treating invalidated calls as live.
- Migration `0180_invalidated_status` heals OPEN predictions that already carry an INVALIDATED outcome event. Its downgrade is a no-op by design.

## Validation
`python -m pytest tests/test_prediction_outcome_monitor.py tests/test_epic853_confirmation.py tests/test_epic853_invalidated_on_next_run.py tests/test_prediction_lifecycle_capacity.py tests/test_api_predictions_active.py tests/test_invalidated_status_migration.py tests/test_alembic_single_head.py -v`

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```

- [ ] **Step 3: Hand off to the user and stop**

Report the PR URL. Ask the user to merge it and then deploy with `ssh marksy@103.178.160.233 'bash ~/deployment/server/vps-deploy.sh'`. Do not merge and do not ssh. Phase 1 starts only after the user confirms the merge.

---

# Phase 1 — Ledger and intake

### Task 4: Phase 1 worktree, ledger vocabulary and text cleaning

**Files:**
- Create: `app/tip_vocabulary.py`
- Create: `app/tip_text_cleaning.py`
- Test: `tests/test_tip_text_cleaning.py`

**Interfaces:**
- Consumes: nothing.
- Produces:
  - `app.tip_vocabulary`: every constant below.
  - `app.tip_text_cleaning`:
    - `clean_tip_text(text: str, *, username: str | None) -> str`
    - `channel_label_for(medium: str, label: str | None, *, username: str | None) -> str | None`
    - `MASK_EMAIL, MASK_PHONE, MASK_PAN, MASK_NUMBER, MASK_USER`

- [ ] **Step 1: Create the Phase 1 worktree (only after the Phase 0 merge)**

```bash
git -C /c/AIAgent/marksy-api fetch origin
git -C /c/AIAgent/marksy-api show origin/main:migrations/versions/0180_invalidated_status.py > /dev/null
git -C /c/AIAgent/marksy-api worktree add /c/AIAgent/marksy-api-worktrees/phase1 -b feat/tip-ledger-intake origin/main
cd /c/AIAgent/marksy-api-worktrees/phase1
```

If the `git show` fails, Phase 0 is not merged: stop and tell the user. Every later Phase 1 command runs from `/c/AIAgent/marksy-api-worktrees/phase1`.

- [ ] **Step 2: Write the failing tests**

Create `tests/test_tip_text_cleaning.py`:

```python
"""tip-ledger spec §5.1/§5.2 cleaning: the masks the phone applies, re-applied on the server."""

from __future__ import annotations

from app.tip_text_cleaning import (
    MASK_EMAIL,
    MASK_NUMBER,
    MASK_PAN,
    MASK_PHONE,
    MASK_USER,
    channel_label_for,
    clean_tip_text,
)
from app.tip_vocabulary import MEDIUM_APP_NOTIFICATION, MEDIUM_SMS, MEDIUM_WHATSAPP


def test_contact_details_and_the_username_are_masked():
    text = "Priya, call +91 98765 43210 or 9123456780, mail priya.k@example.com"
    assert clean_tip_text(text, username="priya") == f"{MASK_USER}, call {MASK_PHONE} or {MASK_PHONE}, mail {MASK_EMAIL}"


def test_pan_codes_and_long_digit_runs_are_masked():
    assert clean_tip_text("PAN ABCDE1234F folio 1234567890123", username=None) == f"PAN {MASK_PAN} folio {MASK_NUMBER}"


def test_prices_strikes_and_short_numbers_survive_cleaning():
    text = "BUY RENUKA CMP : 23.62 SL : 22.25 TGT : 26 | SELL NIFTY 24500 PE @ 120 | Qty 1500"
    assert clean_tip_text(text, username="user-1") == text


def test_a_mobile_number_beside_prices_is_masked_without_touching_the_prices():
    text = "BUY TATASTEEL CMP 612.50 SL 598 TGT 650. Queries: 98765 43210"
    assert clean_tip_text(text, username=None) == f"BUY TATASTEEL CMP 612.50 SL 598 TGT 650. Queries: {MASK_PHONE}"


def test_the_username_is_masked_only_as_a_whole_word_and_only_when_long_enough():
    assert clean_tip_text("Ravi: BUY RAVIKUMAR CMP 10 SL 9", username="ravi") == f"{MASK_USER}: BUY RAVIKUMAR CMP 10 SL 9"
    assert clean_tip_text("al buys ALKEM", username="al") == "al buys ALKEM"


def test_a_group_label_drops_the_individual_sender():
    assert channel_label_for(MEDIUM_WHATSAPP, "Rahul @ StockTips", username="user-1") == "StockTips"


def test_a_one_to_one_chat_titled_with_a_phone_number_is_masked():
    assert channel_label_for(MEDIUM_WHATSAPP, "+91 98765 43210", username="user-1") == MASK_PHONE


def test_an_app_label_is_kept_whole_and_a_blank_label_is_none():
    assert channel_label_for(MEDIUM_APP_NOTIFICATION, "Angel @ One", username=None) == "Angel @ One"
    assert channel_label_for(MEDIUM_SMS, "   ", username=None) is None
```

- [ ] **Step 3: Run the tests to verify they fail**

Run: `python -m pytest tests/test_tip_text_cleaning.py -v`
Expected: collection ERROR `ModuleNotFoundError: No module named 'app.tip_text_cleaning'`.

- [ ] **Step 4: Write the vocabulary and the cleaner**

Create `app/tip_vocabulary.py`:

```python
"""Vocabulary of the central tip ledger (tip-ledger spec §4): one spelling of every status, kind,
medium and channel type, shared by the parsers, the matching rules and the ledger."""

from __future__ import annotations

STATUS_ACTIVE = "ACTIVE"
STATUS_TARGET_HIT = "TARGET_HIT"
STATUS_STOP_LOSS_HIT = "STOP_LOSS_HIT"
STATUS_SOURCE_EXIT = "SOURCE_EXIT"
STATUS_HORIZON_EXPIRED = "HORIZON_EXPIRED"
STATUS_DIRECTION_HORIZON = "DIRECTION_HORIZON"
STATUS_INVALIDATED = "INVALIDATED"
STATUS_UNSCORABLE = "UNSCORABLE"
STATUS_DATA_UNRESOLVED = "DATA_UNRESOLVED"
STATUS_MERGED_DUPLICATE = "MERGED_DUPLICATE"
REJECTED_AT_INTAKE = (STATUS_UNSCORABLE, STATUS_DATA_UNRESOLVED)

REASON_NO_DIRECTION = "NO_DIRECTION"
REASON_INCONSISTENT_LEVELS = "INCONSISTENT_LEVELS"
REASON_UNRESOLVED_SYMBOL = "UNRESOLVED_SYMBOL"

ENTRY_WAITING = "WAITING"
ENTRY_ENTERED = "ENTERED"
ENTRY_BASIS_STATED = "STATED"
ENTRY_BASIS_FIRST_SEEN_PRICE = "FIRST_SEEN_PRICE"
HORIZON_BASIS_STATED = "STATED"
HORIZON_BASIS_CHANNEL_DEFAULT = "CHANNEL_DEFAULT"
HORIZON_BASIS_ENGINE = "ENGINE"
DEFAULT_HORIZON_SESSIONS = 20

DIRECTION_BUY = "BUY"
DIRECTION_SELL = "SELL"

KIND_TIP = "TIP"
KIND_REVISION = "REVISION"
KIND_EXIT = "EXIT"
KIND_UNPARSED = "UNPARSED"

MEDIUM_APP_NOTIFICATION = "APP_NOTIFICATION"
MEDIUM_SMS = "SMS"
MEDIUM_WHATSAPP = "WHATSAPP"
MEDIUM_TELEGRAM = "TELEGRAM"
MEDIUM_EMAIL = "EMAIL"
MEDIUM_MANUAL = "MANUAL"

CHANNEL_BROKER_APP = "BROKER_APP"
CHANNEL_NEWS_PORTAL = "NEWS_PORTAL"
CHANNEL_SMS_SENDER = "SMS_SENDER"
CHANNEL_WHATSAPP_GROUP = "WHATSAPP_GROUP"
CHANNEL_TELEGRAM_CHANNEL = "TELEGRAM_CHANNEL"
CHANNEL_YOUTUBE = "YOUTUBE"
CHANNEL_MARKSY = "MARKSY"

ALIAS_PACKAGE = "PACKAGE"
ALIAS_LABEL = "LABEL"

PARSER_TRADE_CALL = "TCP-001"
PARSER_MARKSY_TIPS_REPORT = "MTR-001"
PARSER_APP_PAYLOAD = "APP-001"

LEGACY_UNKNOWN_USER_ID = "legacy-unknown"
# Mirrors api.schemas.tips.TIP_STATUS_RECEIVED; app must not import the web layer.
COMPARISON_STATUS_RECEIVED = "RECEIVED"
```

Create `app/tip_text_cleaning.py`:

```python
"""Tip text cleaning (tip-ledger spec §5.1/§5.2): the masks the phone applies, re-applied here so
no customer detail is stored even when a client skipped them."""

from __future__ import annotations

import re

from .tip_vocabulary import MEDIUM_TELEGRAM, MEDIUM_WHATSAPP

MASK_EMAIL = "[EMAIL]"
MASK_PHONE = "[PHONE]"
MASK_PAN = "[PAN]"
MASK_NUMBER = "[NUMBER]"
MASK_USER = "[USER]"
LABEL_MAX_LENGTH = 128

_EMAIL = re.compile(r"[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}")
# Indian mobiles: 10 digits from 6-9, optional +91/0091/91/0 prefix, optionally split 5+5.
_PHONE = re.compile(r"(?<![\w+])(?:(?:\+91|0091|91|0)[\s-]?)?[6-9]\d{4}[\s-]?\d{5}(?!\w)")
_PAN = re.compile(r"\b[A-Z]{5}[0-9]{4}[A-Z]\b")
_DIGIT_RUN = re.compile(r"\d{8,}")
_GROUP_SENDER = " @ "


def clean_tip_text(text: str, *, username: str | None) -> str:
    cleaned = _EMAIL.sub(MASK_EMAIL, text)
    cleaned = _PHONE.sub(MASK_PHONE, cleaned)
    cleaned = _PAN.sub(MASK_PAN, cleaned)
    cleaned = _DIGIT_RUN.sub(MASK_NUMBER, cleaned)
    name = (username or "").strip()
    if len(name) >= 3:
        cleaned = re.sub(rf"(?<!\w){re.escape(name)}(?!\w)", MASK_USER, cleaned, flags=re.IGNORECASE)
    return cleaned


def channel_label_for(medium: str, label: str | None, *, username: str | None) -> str | None:
    """The channel a message came through, never the person who sent it inside a group."""
    if label is None or not label.strip():
        return None
    value = label.strip()
    if medium in (MEDIUM_WHATSAPP, MEDIUM_TELEGRAM) and _GROUP_SENDER in value:
        value = value.rsplit(_GROUP_SENDER, 1)[1].strip()
    return clean_tip_text(value, username=username)[:LABEL_MAX_LENGTH] or None
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `python -m pytest tests/test_tip_text_cleaning.py -v`
Expected: 8 PASS.

- [ ] **Step 6: Commit**

```bash
git add app/tip_vocabulary.py app/tip_text_cleaning.py tests/test_tip_text_cleaning.py
git commit -m "$(cat <<'EOF'
Tip ledger: vocabulary and server-side text cleaning

Masks username, phones, emails, PAN-like codes and 8+ digit runs, and
reduces a group label to the group (tip-ledger spec §5.1/§5.2).

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
)"
```

### Task 5: Port CallHorizon

**Files:**
- Create: `app/call_horizon.py`
- Test: `tests/test_call_horizon.py` (mirrors `marksy-os/app/src/test/java/com/marksy/os/notification/CallHorizonTest.kt`, with the spec's session lengths)

**Interfaces:**
- Consumes: nothing.
- Produces: `Horizon(label: str, sessions: int)` (frozen dataclass), `parse_horizon(text: str) -> Horizon | None`, and `WEEK = 5`, `MONTH = 20`, `YEAR = 250`.

- [ ] **Step 1: Write the failing tests**

Create `tests/test_call_horizon.py`:

```python
"""Mirror of marksy-os CallHorizonTest.kt. Session counts follow the tip-ledger spec (§6.1:
1 week = 5, 1 month = 20, intraday N = 0; a year is 250), not the app's 21/252/1."""

from __future__ import annotations

from app.call_horizon import parse_horizon


def _sessions(text: str) -> int | None:
    horizon = parse_horizon(text)
    return None if horizon is None else horizon.sessions


def test_durations_in_any_wording_become_trading_sessions():
    assert _sessions("Buy TENNIND around Rs 500 for 12 Month with target price of Rs 650") == 240
    assert _sessions("Accumulate for 24 months") == 480
    assert _sessions("Target in 1 year") == 250
    assert _sessions("holding 1.5 years") == 375
    assert _sessions("2 yrs view") == 500
    assert _sessions("one year target") == 250
    assert _sessions("six months") == 120
    assert _sessions("for 3 weeks") == 15
    assert _sessions("Horizon: 5 days") == 5
    assert _sessions("12M target 650") == 240
    assert _sessions("6-12 months") == 240
    assert _sessions("3 to 6 months") == 120


def test_phrases_use_their_conventional_length():
    assert _sessions("Intraday call") == 0
    assert _sessions("BTST: buy XYZ") == 1
    assert _sessions("Swing trade") == 10
    assert _sessions("Positional call") == 20
    assert _sessions("Short term Call") == 60
    assert _sessions("Mid-term pick") == 120
    assert _sessions("Long term investment") == 250


def test_labelled_field_keeps_its_words_and_wins_over_prose():
    horizon = parse_horizon("BUY | CROPSTER AGRO | Time: 1-2 Months | long term story")
    assert (horizon.label, horizon.sessions) == ("1-2 months", 40)
    assert parse_horizon("for 12 Month").label == "12 months"
    assert parse_horizon("long-term").label == "Long term"


def test_statistics_and_history_are_not_horizons():
    assert _sessions("Trading near its 52 week high") is None
    assert _sessions("above the 200 day moving average") is None
    assert _sessions("up 40% in the last 3 years") is None
    assert _sessions("1 year return of 25%") is None
    assert _sessions("Price ₹12M turnover") is None
    assert _sessions("Time: 10:30 AM") is None
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `python -m pytest tests/test_call_horizon.py -v`
Expected: collection ERROR `ModuleNotFoundError: No module named 'app.call_horizon'`.

- [ ] **Step 3: Write the port**

Create `app/call_horizon.py`:

```python
"""Port of marksy-os `notification/CallHorizon.kt`: a trade call's holding horizon from free text,
as words to show and a length in NSE sessions. A range counts to its upper end."""

from __future__ import annotations

import math
import re
from dataclasses import dataclass

WEEK = 5
# tip-ledger spec §6.1: 1 month = 20 sessions (the app used 21); a year follows at 250 (spec §7 LONG).
MONTH = 20
YEAR = 250
MAX_SESSIONS = 5 * YEAR

_WORD_NUMBERS = {
    "a": 1.0, "an": 1.0, "one": 1.0, "two": 2.0, "three": 3.0, "four": 4.0, "five": 5.0, "six": 6.0,
    "seven": 7.0, "eight": 8.0, "nine": 9.0, "ten": 10.0, "eleven": 11.0, "twelve": 12.0, "eighteen": 18.0,
}
_NUM = r"(\d{1,3}(?:\.\d+)?|an?|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|eighteen)"
_UNIT = r"(trading\s+days?|sessions?|days?|weeks?|wks?|months?|mths?|mnths?|years?|yrs?)"
_DURATION = re.compile(rf"(?<![\w.]){_NUM}(?:\s*(?:-|–|to)\s*{_NUM})?[\s-]*{_UNIT}\b", re.IGNORECASE)
# "12M", "1Y": research shorthand, only when glued to the number and not a rupee amount.
_COMPACT = re.compile(r"(?<![\w.₹$])(\d{1,2})([dwmy])\b", re.IGNORECASE)
_FIELD = re.compile(
    r"\b(?:investment\s+horizon|holding\s+period|time\s*frame|horizon|duration|holding|period|tenure|time)"
    r"\s*[:=\-]\s*([^|\n]+)",
    re.IGNORECASE,
)
# tip-ledger spec §6.1: an intraday call has N = 0 (the app used 1).
_PHRASES = (
    (re.compile(r"\bintra[\s-]?day\b", re.IGNORECASE), "Intraday", 0),
    (re.compile(r"\b(?:btst|stbt)\b", re.IGNORECASE), "BTST", 1),
    (re.compile(r"\bswing\b", re.IGNORECASE), "Swing", 2 * WEEK),
    (re.compile(r"\bpositional\b", re.IGNORECASE), "Positional", MONTH),
    (re.compile(r"\bshort[\s-]?term\b", re.IGNORECASE), "Short term", 3 * MONTH),
    (re.compile(r"\b(?:medium|mid)[\s-]?term\b", re.IGNORECASE), "Medium term", 6 * MONTH),
    (re.compile(r"\blong[\s-]?term\b", re.IGNORECASE), "Long term", YEAR),
)
# "last 3 years", "52 week high", "200 day moving average": history and statistics, not a horizon.
_NOT_AFTER = frozenset({"last", "past", "previous", "prior", "since", "trailing"})
_NOT_BEFORE = frozenset({
    "high", "highs", "low", "lows", "range", "return", "returns", "avg", "average", "moving", "ma", "sma", "ema",
    "dma", "chart", "change", "volume", "gain", "gains", "performance", "cagr", "ago", "old", "back",
})
_WORD_BEFORE = re.compile(r"(\w+)\W*$")
_WORD_AFTER = re.compile(r"^\W*(\w+)")


@dataclass(frozen=True)
class Horizon:
    label: str
    sessions: int


def parse_horizon(text: str) -> Horizon | None:
    """A labelled field ("Time: 1-2 Months") wins over prose; else the first duration, else the first phrase."""
    for field in _FIELD.finditer(text):
        found = _duration_in(field.group(1)) or _phrase_in(field.group(1))
        if found is not None:
            return found
    return _duration_in(text) or _phrase_in(text)


def _duration_in(text: str) -> Horizon | None:
    spans = [
        (m.start(), m.end(), _number(m.group(2) or m.group(1)), _unit_sessions(m.group(3)))
        for m in _DURATION.finditer(text)
    ] + [(m.start(), m.end(), float(m.group(1)), _unit_sessions(m.group(2))) for m in _COMPACT.finditer(text)]
    for start, end, value, per_unit in sorted(spans, key=lambda span: span[0]):
        if value is None or _word_before(text, start) in _NOT_AFTER or _word_after(text, end) in _NOT_BEFORE:
            continue
        sessions = math.floor(value * per_unit + 0.5)  # Kotlin roundToInt rounds half up
        if 1 <= sessions <= MAX_SESSIONS:
            return Horizon(_label(text[start:end]), sessions)
    return None


def _phrase_in(text: str) -> Horizon | None:
    found = [(m, label, sessions) for pattern, label, sessions in _PHRASES if (m := pattern.search(text)) is not None]
    if not found:
        return None
    match, label, sessions = min(found, key=lambda item: item[0].start())
    return Horizon(match.group(0).upper() if label == "BTST" else label, sessions)


def _number(raw: str) -> float | None:
    try:
        return float(raw)
    except ValueError:
        return _WORD_NUMBERS.get(raw.lower())


def _unit_sessions(unit: str) -> int:
    return {"w": WEEK, "m": MONTH, "y": YEAR}.get(unit[0].lower(), 1)


# "12 Month" -> "12 months", "six months" -> "6 months", "12M" -> "12 months", "1-2 Months" -> "1-2 months".
def _label(raw: str) -> str:
    compact = _COMPACT.fullmatch(raw)
    duration = None if compact else _DURATION.fullmatch(raw)
    if compact is not None:
        low, high, unit = float(compact.group(1)), None, compact.group(2)
    elif duration is not None:
        low = _number(duration.group(1))
        high = _number(duration.group(2)) if duration.group(2) else None
        unit = duration.group(3)
    else:
        return raw.strip()
    if low is None:
        return raw.strip()
    name = {WEEK: "week", MONTH: "month", YEAR: "year"}.get(_unit_sessions(unit), "day")
    amount = "-".join(_fmt(value) for value in (low, high) if value is not None)
    return f"{amount} {name}" + ("" if (high if high is not None else low) == 1.0 else "s")


def _fmt(value: float) -> str:
    return str(int(value)) if value % 1 == 0 else f"{value:.1f}"


def _word_before(text: str, index: int) -> str | None:
    match = _WORD_BEFORE.search(text[max(0, index - 24):index])
    return match.group(1).lower() if match else None


def _word_after(text: str, end: int) -> str | None:
    match = _WORD_AFTER.search(text[end:end + 24])
    return match.group(1).lower() if match else None
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `python -m pytest tests/test_call_horizon.py -v`
Expected: 4 PASS.

- [ ] **Step 5: Commit**

```bash
git add app/call_horizon.py tests/test_call_horizon.py
git commit -m "$(cat <<'EOF'
Tip ledger: port CallHorizon to the server

Same wording rules as the app; session lengths follow the tip-ledger
spec (month 20, year 250, intraday 0).

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
)"
```

### Task 6: Port TradeCallParser and detect the receipt kind

**Files:**
- Create: `app/trade_call_parser.py`
- Test: `tests/test_trade_call_parser.py` (mirrors `TradeCallParserTest.kt`, plus the kind detection)

**Interfaces:**
- Consumes: `parse_horizon` (Task 5), `DIRECTION_*`, `KIND_*`, `PARSER_TRADE_CALL` (Task 4).
- Produces:
  - `PARSER_VERSION: str`
  - `TradeCall(side, symbol, entry_low, entry_high, stop_loss, target, horizon_label, horizon_sessions, side_start)`
  - `parse_call(text: str) -> TradeCall | None`
  - `ParsedMessage(kind, side=None, symbol=None, entry_low=None, entry_high=None, stop_loss=None, target=None, horizon_sessions=None, exit_price=None, caller=None)`
  - `parse_message(text: str) -> ParsedMessage`
  - Prices are `Decimal`, sides are `"BUY"`/`"SELL"`.

- [ ] **Step 1: Write the failing tests**

Create `tests/test_trade_call_parser.py`:

```python
"""Mirror of marksy-os TradeCallParserTest.kt (fixtures are real calls captured on-device
2026-09-25), plus the receipt-kind detection the server now owns (tip-ledger spec §5.2 step 3).
The app parsed `title + "\\n" + body`; these fixtures pass that same joined text."""

from __future__ import annotations

from decimal import Decimal

import pytest

from app.tip_vocabulary import DIRECTION_BUY, DIRECTION_SELL, KIND_EXIT, KIND_REVISION, KIND_TIP, KIND_UNPARSED
from app.trade_call_parser import parse_call, parse_message


def _call(title: str, body: str):
    call = parse_call(f"{title}\n{body}")
    assert call is not None
    return call


def test_parses_five_paisa_short_term_call():
    call = _call("Short term Call", "BUY RENUKA CMP : 23.62 SL : 22.25 TGT : 26")
    assert (call.side, call.symbol) == (DIRECTION_BUY, "RENUKA")
    assert (call.entry_low, call.entry_high, call.stop_loss, call.target) == (
        Decimal("23.62"), Decimal("23.62"), Decimal("22.25"), Decimal("26"),
    )
    assert (call.horizon_label, call.horizon_sessions) == ("Short term", 60)


def test_parses_upstox_emoji_labelled_call():
    call = _call(
        "📈BUY LCCPROJECT with 20.0% upside potential",
        "🛠️ Entry : Rs 144.24\n🎯 Target : Rs 173.08\n🛑 Stoploss : Rs 129.81",
    )
    assert (call.symbol, call.entry_low, call.target, call.stop_loss) == (
        "LCCPROJECT", Decimal("144.24"), Decimal("173.08"), Decimal("129.81"),
    )


def test_parses_pipe_separated_sms_call_with_multi_word_name():
    call = _call(
        "KISHAN ENTERPRISE",
        "KISHAN ENTERPRISE: Dear Client \nBUY | CROPSTER AGRO | \nEntry ₹2.82 | Target ₹10 | SL ₹2 | \n"
        "Time: 1-2 Months | \nConviction: High\nThank you \nKISHAN Enterprises",
    )
    assert (call.symbol, call.entry_low, call.target, call.stop_loss) == (
        "CROPSTER AGRO", Decimal("2.82"), Decimal("10"), Decimal("2"),
    )
    assert call.horizon_label == "1-2 months"


def test_parses_icici_research_call_with_around_and_target_price():
    call = _call("ICICI Direct", "Buy JSL around Rs 750 for 12 Month with target price of Rs 915, potential upside of 22%.")
    assert (call.side, call.symbol, call.entry_low, call.target) == (DIRECTION_BUY, "JSL", Decimal("750"), Decimal("915"))
    assert (call.horizon_label, call.horizon_sessions) == ("12 months", 240)


def test_text_without_a_call_is_not_parsed():
    assert parse_call("4 IPOs Just Went Live\nAcevector & Orient Cables IPOs are now open for subscription") is None


def test_parses_options_call_with_strike_and_at_price():
    call = _call("F&O Call", "SELL NIFTY 24500 PE @ 120 SL 140 TGT 90")
    assert (call.side, call.symbol, call.entry_low) == (DIRECTION_SELL, "NIFTY 24500 PE", Decimal("120"))


def test_parses_digit_leading_symbol():
    assert _call("Call", "BUY 360ONE CMP 1150 SL 1090 TGT 1260").symbol == "360ONE"


def test_numbered_target_is_not_taken_as_the_price():
    assert _call("Call", "BUY RENUKA CMP 140 SL 132 Target 1: 150").target == Decimal("150")


def test_range_ends_the_symbol_and_gives_both_entry_bounds():
    call = _call("Call", "Buy TATASTEEL in the range of 150-152, target 170, SL 144")
    assert (call.symbol, call.entry_low, call.entry_high) == ("TATASTEEL", Decimal("150"), Decimal("152"))


def test_a_complete_call_is_a_tip_with_its_stated_terms():
    parsed = parse_message("BUY RENUKA CMP 23.62 SL 22.25 TGT 26 for 3 weeks")
    assert (parsed.kind, parsed.side, parsed.symbol, parsed.horizon_sessions) == (KIND_TIP, DIRECTION_BUY, "RENUKA", 15)


@pytest.mark.parametrize("text, symbol, price", [
    ("Exit RELIANCE at 2510", "RELIANCE", Decimal("2510")),
    ("Book profit in TCS @ 3900", "TCS", Decimal("3900")),
    ("RELIANCE: exit at 2510", "RELIANCE", Decimal("2510")),
    ("Square off HDFCBANK", "HDFCBANK", None),
])
def test_an_exit_instruction_is_an_exit(text, symbol, price):
    parsed = parse_message(text)
    assert (parsed.kind, parsed.symbol, parsed.exit_price, parsed.side) == (KIND_EXIT, symbol, price, None)


def test_an_exit_that_names_the_long_side_carries_it():
    parsed = parse_message("Exit long in SBIN at 810")
    assert (parsed.kind, parsed.symbol, parsed.side) == (KIND_EXIT, "SBIN", DIRECTION_BUY)


def test_exit_wording_after_a_complete_call_is_still_a_tip():
    assert parse_message("BUY INFY CMP 1500 SL 1450 TGT 1600. Exit if it closes below 1450").kind == KIND_TIP


def test_a_revision_with_a_side_reads_its_new_levels():
    parsed = parse_message("BUY RELIANCE revised target 2650 SL 2400")
    assert (parsed.kind, parsed.side, parsed.symbol, parsed.target, parsed.stop_loss) == (
        KIND_REVISION, DIRECTION_BUY, "RELIANCE", Decimal("2650"), Decimal("2400"),
    )


def test_a_revision_without_a_side_names_the_symbol_first():
    parsed = parse_message("RELIANCE: target revised to 2650")
    assert (parsed.kind, parsed.side, parsed.symbol, parsed.target) == (KIND_REVISION, None, "RELIANCE", Decimal("2650"))


def test_revision_wording_without_any_level_is_unparsed():
    assert parse_message("RELIANCE target revised, details soon").kind == KIND_UNPARSED


def test_a_named_analyst_is_read_as_the_caller():
    parsed = parse_message("BUY INFY CMP 1500 SL 1450 TGT 1600\nAnalyst: Rahul Sharma")
    assert (parsed.kind, parsed.caller) == (KIND_TIP, "Rahul Sharma")


def test_ordinary_text_is_unparsed():
    parsed = parse_message("4 IPOs Just Went Live\nAcevector & Orient Cables IPOs are now open for subscription")
    assert (parsed.kind, parsed.symbol) == (KIND_UNPARSED, None)
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `python -m pytest tests/test_trade_call_parser.py -v`
Expected: collection ERROR `ModuleNotFoundError: No module named 'app.trade_call_parser'`.

- [ ] **Step 3: Write the parser**

Create `app/trade_call_parser.py`:

```python
"""Port of marksy-os `notification/TradeCallParser.kt`, plus the receipt-kind detection the server
now owns (tip-ledger spec §5.2 step 3): cleaned text in, a TIP / REVISION / EXIT / UNPARSED reading
out. Pure: no database, no network."""

from __future__ import annotations

import re
from dataclasses import dataclass, replace
from decimal import Decimal, InvalidOperation

from .call_horizon import parse_horizon
from .tip_vocabulary import (
    DIRECTION_BUY,
    DIRECTION_SELL,
    KIND_EXIT,
    KIND_REVISION,
    KIND_TIP,
    KIND_UNPARSED,
    PARSER_TRADE_CALL,
)

PARSER_VERSION = PARSER_TRADE_CALL

# "short" is a SELL only on its own: "Short term Call" is a horizon, not a side.
_SIDE = re.compile(r"\b(buy|sell|short(?![\s-]*term)|accumulate)\b", re.IGNORECASE)
# Where the instrument name ends; digits inside a name (360ONE) or a strike ("NIFTY 24500 PE") stay in it.
_SYMBOL_END = re.compile(
    r"[|\n]|\b(?:cmp|ltp|entry|sl|tgt|target|targets|stop|stoploss|with|at|above|below|near|around|in|range|between)\b"
    r"|@|₹|\brs\b|(?<!\w)\d[\d,]*(?:\.\d+)?(?!\w)(?!\s*(?:ce|pe)\b)",
    re.IGNORECASE,
)
_PRICE = r"\s*[:\-=]?\s*(?:rs\.?|inr|₹)?\s*([0-9][0-9,]*(?:\.[0-9]+)?)"
_ENTRY = re.compile(r"(?:\b(?:cmp|ltp|entry|buy\s+at|sell\s+at|around|range\s+of|between)|@)" + _PRICE, re.IGNORECASE)
_ENTRY_RANGE_END = re.compile(r"\s*(?:-|–|to)\s*(?:rs\.?|inr|₹)?\s*([0-9][0-9,]*(?:\.[0-9]+)?)", re.IGNORECASE)
_STOP = re.compile(r"\b(?:sl|stop\s*loss|stoploss|stop-loss)" + _PRICE, re.IGNORECASE)
# "Target 1: 150": the 1 numbers the target, it is not the price.
_TARGET = re.compile(r"\b(?:tgt|targets?)(?:\s*\d\s*[:\-=)])?(?:\s+price)?(?:\s+of)?" + _PRICE, re.IGNORECASE)
_EXIT = re.compile(
    r"\b(?:exit|book\s+(?:full\s+|partial\s+)?profits?|square[\s-]?off|close\s+(?:the\s+)?(?:position|trade|call))\b",
    re.IGNORECASE,
)
_EXIT_CONNECTOR = re.compile(
    r"^(?:(?:all|your|the)\s+)?(?:(?:positions?|longs?|shorts?|trades?)\s+)?(?:(?:in|from|on|of)\b\s*)?",
    re.IGNORECASE,
)
_EXIT_PRICE = re.compile(r"(?:\b(?:at|cmp|ltp|around|near|price)|@)" + _PRICE, re.IGNORECASE)
_LONG = re.compile(r"\blongs?\b", re.IGNORECASE)
_SHORT = re.compile(r"\bshorts?\b(?![\s-]*term)", re.IGNORECASE)
_REVISION = re.compile(
    r"\b(?:revised|revise|revision|modified|modify|trail(?:ing)?)\b|\bnew\b(?=\s+(?:target|tgt|sl|stop))",
    re.IGNORECASE,
)
# "target revised to 2650" reads "target 2650" once the revision word is gone.
_LEVEL_TO = re.compile(r"\b(tgt|targets?|sl|stop\s*loss|stoploss|stop-loss)\s+(?:is\s+)?(?:now\s+)?to\b", re.IGNORECASE)
_CALLER = re.compile(
    r"^[ \t]*(?:research\s+analyst|analyst|recommended\s+by|call\s+by|by)[ \t]*[:\-][ \t]*"
    r"([A-Za-z][A-Za-z .']{0,58}[A-Za-z.])[ \t]*$",
    re.IGNORECASE | re.MULTILINE,
)
_LEAD_TRIM = " |:,-\t\n"
_SYMBOL_TRIM = " |:,-."


@dataclass(frozen=True)
class TradeCall:
    side: str
    symbol: str
    entry_low: Decimal | None
    entry_high: Decimal | None
    stop_loss: Decimal | None
    target: Decimal | None
    horizon_label: str | None
    horizon_sessions: int | None
    side_start: int


@dataclass(frozen=True)
class ParsedMessage:
    kind: str
    side: str | None = None
    symbol: str | None = None
    entry_low: Decimal | None = None
    entry_high: Decimal | None = None
    stop_loss: Decimal | None = None
    target: Decimal | None = None
    horizon_sessions: int | None = None
    exit_price: Decimal | None = None
    caller: str | None = None


def parse_call(text: str) -> TradeCall | None:
    """TradeCallParser.parse: a side, an instrument and at least two of entry, stop-loss and target."""
    head = _side_head(text)
    if head is None:
        return None
    entry_low, entry_high = _entry(text)
    stop_loss, target = _first_price(_STOP, text), _first_price(_TARGET, text)
    if sum(level is not None for level in (entry_low, stop_loss, target)) < 2:
        return None
    side, symbol, side_start = head
    horizon = parse_horizon(text)
    return TradeCall(
        side=side, symbol=symbol, entry_low=entry_low, entry_high=entry_high, stop_loss=stop_loss, target=target,
        horizon_label=horizon.label if horizon else None, horizon_sessions=horizon.sessions if horizon else None,
        side_start=side_start,
    )


def parse_message(text: str) -> ParsedMessage:
    """An exit instruction ahead of any complete call wins; a revision needs one new level."""
    caller = _caller(text)
    call = parse_call(text)
    exit_match = _EXIT.search(text)
    if exit_match is not None and (call is None or exit_match.start() < call.side_start):
        symbol = _exit_symbol(text, exit_match)
        if symbol is not None:
            return ParsedMessage(
                kind=KIND_EXIT, side=_exit_side(text), symbol=symbol,
                exit_price=_first_price(_EXIT_PRICE, text[exit_match.end():]), caller=caller,
            )
    if _REVISION.search(text):
        revision = _revision(text)
        if revision is not None:
            return replace(revision, caller=caller)
    if call is not None:
        return ParsedMessage(
            kind=KIND_TIP, side=call.side, symbol=call.symbol, entry_low=call.entry_low, entry_high=call.entry_high,
            stop_loss=call.stop_loss, target=call.target, horizon_sessions=call.horizon_sessions, caller=caller,
        )
    return ParsedMessage(kind=KIND_UNPARSED, caller=caller)


def _price(raw: str | None) -> Decimal | None:
    if raw is None:
        return None
    try:
        return Decimal(raw.replace(",", ""))
    except InvalidOperation:
        return None


def _first_price(pattern: re.Pattern, text: str) -> Decimal | None:
    match = pattern.search(text)
    return _price(match.group(1)) if match else None


def _symbol_at_start(text: str) -> str | None:
    end = _SYMBOL_END.search(text)
    raw = text[: end.start() if end else len(text)].strip(_SYMBOL_TRIM)
    symbol = re.sub(r"\s+", " ", raw).upper()
    if not symbol or len(symbol) > 30 or not any(ch.isalpha() for ch in symbol):
        return None
    return symbol


def _side_head(text: str) -> tuple[str, str, int] | None:
    side = _SIDE.search(text)
    if side is None:
        return None
    symbol = _symbol_at_start(text[side.end():].lstrip(_LEAD_TRIM))
    if symbol is None:
        return None
    direction = DIRECTION_SELL if side.group(1).lower() in ("sell", "short") else DIRECTION_BUY
    return direction, symbol, side.start()


def _entry(text: str) -> tuple[Decimal | None, Decimal | None]:
    match = _ENTRY.search(text)
    if match is None:
        return None, None
    low = _price(match.group(1))
    tail = _ENTRY_RANGE_END.match(text, match.end())
    high = _price(tail.group(1)) if tail else None
    return low, (high if low is not None and high is not None and high > low else low)


def _exit_symbol(text: str, match: re.Match) -> str | None:
    after = _EXIT_CONNECTOR.sub("", text[match.end():].lstrip(_LEAD_TRIM), count=1)
    symbol = _symbol_at_start(after)
    if symbol is not None:
        return symbol
    before = text[: match.start()].rstrip().rsplit("\n", 1)[-1]
    return _symbol_at_start(before.lstrip(_LEAD_TRIM))


def _exit_side(text: str) -> str | None:
    if _LONG.search(text):
        return DIRECTION_BUY
    if _SHORT.search(text):
        return DIRECTION_SELL
    return None


def _revision(text: str) -> ParsedMessage | None:
    stripped = _LEVEL_TO.sub(r"\1", _REVISION.sub(" ", text))
    head = _side_head(stripped)
    side, symbol = (head[0], head[1]) if head else (None, _symbol_at_start(stripped.lstrip(_LEAD_TRIM)))
    entry_low, entry_high = _entry(stripped)
    stop_loss, target = _first_price(_STOP, stripped), _first_price(_TARGET, stripped)
    if symbol is None or (entry_low is None and stop_loss is None and target is None):
        return None
    horizon = parse_horizon(stripped)
    return ParsedMessage(
        kind=KIND_REVISION, side=side, symbol=symbol, entry_low=entry_low, entry_high=entry_high,
        stop_loss=stop_loss, target=target, horizon_sessions=horizon.sessions if horizon else None,
    )


def _caller(text: str) -> str | None:
    match = _CALLER.search(text)
    return re.sub(r"\s+", " ", match.group(1)).strip() if match else None
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `python -m pytest tests/test_trade_call_parser.py -v`
Expected: 21 PASS (the parametrized exit test counts 4).

- [ ] **Step 5: Commit**

```bash
git add app/trade_call_parser.py tests/test_trade_call_parser.py
git commit -m "$(cat <<'EOF'
Tip ledger: port TradeCallParser and detect TIP/REVISION/EXIT

Faithful port of the app's call parser (plus entry ranges), with the
server-owned kind detection and optional caller (tip-ledger spec §5.2).

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
)"
```

### Task 7: Stated terms, match_key v1 and intake rejections

**Files:**
- Create: `app/tip_matching.py`
- Test: `tests/test_tip_matching.py`

**Interfaces:**
- Consumes: `DIRECTION_*`, `STATUS_UNSCORABLE`, `STATUS_DATA_UNRESOLVED`, `REASON_*` (Task 4).
- Produces:
  - `MATCH_KEY_VERSION = 1`
  - `StatedTerms(symbol: str, direction: str | None, entry_low: Decimal | None, entry_high: Decimal | None, target: Decimal | None, stop_loss: Decimal | None, horizon_sessions: int | None)` (frozen)
  - `normalize_direction(raw: str | None) -> str | None`
  - `stated_terms(*, symbol, direction, entry_low, entry_high, target, stop_loss, horizon_sessions) -> StatedTerms`
  - `entry_mid(terms: StatedTerms) -> Decimal | None`
  - `match_key_v1(canonical_channel_id: int, terms: StatedTerms) -> str` (64-char hex)
  - `intake_rejection(terms: StatedTerms, *, symbol_resolved: bool) -> tuple[str, str] | None` (`(status, reason)`)

- [ ] **Step 1: Write the failing tests**

Create `tests/test_tip_matching.py`:

```python
"""tip-ledger spec §5.2 match_key v1 and §6.2 intake rejections."""

from __future__ import annotations

import hashlib
from decimal import Decimal

import pytest

from app.tip_matching import intake_rejection, match_key_v1, normalize_direction, stated_terms


def _terms(**overrides):
    values = dict(
        symbol="RENUKA", direction="BUY", entry_low=Decimal("23.62"), entry_high=Decimal("23.62"),
        target=Decimal("26"), stop_loss=Decimal("22.25"), horizon_sessions=None,
    )
    values.update(overrides)
    return stated_terms(**values)


def test_match_key_v1_hashes_the_documented_string():
    assert match_key_v1(7, _terms()) == hashlib.sha256(b"v1|7|RENUKA|BUY|23.60|23.60|26.00|22.25|-").hexdigest()


def test_prices_within_one_tick_share_a_key_and_a_tick_apart_do_not():
    assert match_key_v1(7, _terms(target=Decimal("26.01"))) == match_key_v1(7, _terms(target=Decimal("25.99")))
    assert match_key_v1(7, _terms(target=Decimal("26.05"))) != match_key_v1(7, _terms())


def test_the_key_separates_channel_direction_and_stated_horizon_but_not_symbol_case():
    base = match_key_v1(7, _terms())
    assert match_key_v1(7, _terms(symbol=" renuka")) == base
    keys = {base, match_key_v1(8, _terms()), match_key_v1(7, _terms(direction="SELL")), match_key_v1(7, _terms(horizon_sessions=5))}
    assert len(keys) == 4


def test_one_entry_bound_stands_for_both_reversed_bounds_are_ordered_and_a_negative_horizon_is_unstated():
    assert (_terms(entry_low=None, entry_high=Decimal("100")).entry_low, _terms(entry_low=Decimal("100"), entry_high=None).entry_high) == (Decimal("100"), Decimal("100"))
    reversed_range = _terms(entry_low=Decimal("105"), entry_high=Decimal("100"))
    assert (reversed_range.entry_low, reversed_range.entry_high) == (Decimal("100"), Decimal("105"))
    assert _terms(horizon_sessions=-3).horizon_sessions is None


@pytest.mark.parametrize("raw, expected", [
    ("long", "BUY"), ("Accumulate", "BUY"), (" buy ", "BUY"), ("SHORT", "SELL"), ("sell", "SELL"), ("HOLD", None), (None, None),
])
def test_directions_normalize_to_buy_sell_or_none(raw, expected):
    assert normalize_direction(raw) == expected


@pytest.mark.parametrize("overrides, resolved, expected", [
    ({"direction": "HOLD"}, True, ("UNSCORABLE", "NO_DIRECTION")),
    ({"target": Decimal("23")}, True, ("UNSCORABLE", "INCONSISTENT_LEVELS")),
    ({"stop_loss": Decimal("24")}, True, ("UNSCORABLE", "INCONSISTENT_LEVELS")),
    ({"direction": "SELL"}, True, ("UNSCORABLE", "INCONSISTENT_LEVELS")),
    ({"entry_low": None, "entry_high": None, "target": Decimal("20")}, True, ("UNSCORABLE", "INCONSISTENT_LEVELS")),
    ({"entry_low": Decimal("0"), "entry_high": Decimal("0")}, True, ("UNSCORABLE", "INCONSISTENT_LEVELS")),
    ({}, False, ("DATA_UNRESOLVED", "UNRESOLVED_SYMBOL")),
    ({"direction": "HOLD"}, False, ("UNSCORABLE", "NO_DIRECTION")),
    ({}, True, None),
    ({"entry_low": None, "entry_high": None, "target": None, "stop_loss": None}, True, None),
])
def test_intake_rejections(overrides, resolved, expected):
    assert intake_rejection(_terms(**overrides), symbol_resolved=resolved) == expected
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `python -m pytest tests/test_tip_matching.py -v`
Expected: collection ERROR `ModuleNotFoundError: No module named 'app.tip_matching'`.

- [ ] **Step 3: Write the module**

Create `app/tip_matching.py`:

```python
"""match_key v1 and the intake rejections (tip-ledger spec §5.2, §6.2). Pure: stated terms in,
a key or a verdict out. Defaults and first-seen prices never enter a key."""

from __future__ import annotations

import hashlib
from dataclasses import dataclass
from decimal import ROUND_HALF_UP, Decimal

from .tip_vocabulary import (
    DIRECTION_BUY,
    DIRECTION_SELL,
    REASON_INCONSISTENT_LEVELS,
    REASON_NO_DIRECTION,
    REASON_UNRESOLVED_SYMBOL,
    STATUS_DATA_UNRESOLVED,
    STATUS_UNSCORABLE,
)

MATCH_KEY_VERSION = 1
_TICK = Decimal("0.05")
_DIRECTIONS = {
    "BUY": DIRECTION_BUY, "LONG": DIRECTION_BUY, "ACCUMULATE": DIRECTION_BUY,
    "SELL": DIRECTION_SELL, "SHORT": DIRECTION_SELL,
}


@dataclass(frozen=True)
class StatedTerms:
    symbol: str
    direction: str | None
    entry_low: Decimal | None
    entry_high: Decimal | None
    target: Decimal | None
    stop_loss: Decimal | None
    horizon_sessions: int | None


def normalize_direction(raw: str | None) -> str | None:
    return _DIRECTIONS.get(raw.strip().upper()) if raw and raw.strip() else None


def stated_terms(
    *,
    symbol: str,
    direction: str | None,
    entry_low: Decimal | None,
    entry_high: Decimal | None,
    target: Decimal | None,
    stop_loss: Decimal | None,
    horizon_sessions: int | None,
) -> StatedTerms:
    """One entry bound stands for both; reversed bounds are put in order."""
    low = entry_low if entry_low is not None else entry_high
    high = entry_high if entry_high is not None else entry_low
    if low is not None and high is not None and low > high:
        low, high = high, low
    return StatedTerms(
        symbol=symbol.strip().upper(),
        direction=normalize_direction(direction),
        entry_low=low,
        entry_high=high,
        target=target,
        stop_loss=stop_loss,
        horizon_sessions=horizon_sessions if horizon_sessions is not None and horizon_sessions >= 0 else None,
    )


def entry_mid(terms: StatedTerms) -> Decimal | None:
    if terms.entry_low is None or terms.entry_high is None:
        return None
    return (terms.entry_low + terms.entry_high) / 2


def _price_token(value: Decimal | None) -> str:
    if value is None:
        return "-"
    ticks = (value / _TICK).quantize(Decimal("1"), rounding=ROUND_HALF_UP)
    return f"{ticks * _TICK:.2f}"


def match_key_v1(canonical_channel_id: int, terms: StatedTerms) -> str:
    parts = (
        "v1",
        str(canonical_channel_id),
        terms.symbol,
        terms.direction or "-",
        _price_token(terms.entry_low),
        _price_token(terms.entry_high),
        _price_token(terms.target),
        _price_token(terms.stop_loss),
        "-" if terms.horizon_sessions is None else str(terms.horizon_sessions),
    )
    return hashlib.sha256("|".join(parts).encode("utf-8")).hexdigest()


def _inconsistent(terms: StatedTerms) -> bool:
    prices = [p for p in (terms.entry_low, terms.entry_high, terms.target, terms.stop_loss) if p is not None]
    if any(price <= 0 for price in prices):
        return True
    sign = 1 if terms.direction == DIRECTION_BUY else -1
    mid = entry_mid(terms)
    if mid is not None:
        if terms.target is not None and sign * (terms.target / mid - 1) <= 0:
            return True
        return terms.stop_loss is not None and sign * (terms.stop_loss / mid - 1) >= 0
    # No stated entry: no entry could put the target and the stop on their right sides.
    return terms.target is not None and terms.stop_loss is not None and sign * (terms.target - terms.stop_loss) <= 0


def intake_rejection(terms: StatedTerms, *, symbol_resolved: bool) -> tuple[str, str] | None:
    """`(status, reason)` for a tip rejected at intake, in §6.2 order, else None."""
    if terms.direction is None:
        return STATUS_UNSCORABLE, REASON_NO_DIRECTION
    if _inconsistent(terms):
        return STATUS_UNSCORABLE, REASON_INCONSISTENT_LEVELS
    if not symbol_resolved:
        return STATUS_DATA_UNRESOLVED, REASON_UNRESOLVED_SYMBOL
    return None
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `python -m pytest tests/test_tip_matching.py -v`
Expected: all PASS.

- [ ] **Step 5: Commit**

```bash
git add app/tip_matching.py tests/test_tip_matching.py
git commit -m "$(cat <<'EOF'
Tip ledger: match_key v1 and intake rejections

Stated terms only, 0.05 tick, 2 decimals, '-' for missing; NO_DIRECTION,
INCONSISTENT_LEVELS and UNRESOLVED_SYMBOL per tip-ledger spec §6.2.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
)"
```

### Task 8: Ledger schema in the ORM and the `comparison_status` readers

**Files:**
- Modify: `app/models.py:5119-5164` (`ExternalTip` → `Tip`), the `tip_id` FK in `ExternalTipComparison` (~:5182) and `ExternalTipOutcome` (~:5207); append the new classes after `ExternalTipOutcome`
- Modify: `app/external_tip_outcome.py:147,204`
- Modify: `api/services/tips.py` (7 `status` reads/writes)
- Test (update existing fixtures): `tests/test_external_tip_outcome.py`, `tests/test_external_tip_scoring.py`, `tests/test_api_tips.py`

**Interfaces:**
- Consumes: Task 4 constants (docstrings only).
- Produces: in `app.models`:
  - `Tip` (table `tips`, with `comparison_status: str` and the §4 ledger columns listed below), plus the alias `ExternalTip = Tip`.
  - `Channel(id, name, type, canonical_channel_id, default_horizon_sessions, capture_enabled, created_at)`
  - `ChannelAlias(id, channel_id, alias, kind, created_at)`
  - `Caller(id, channel_id, name, canonical_caller_id, created_at)`
  - `TipReceipt(id, public_id, tip_id, user_id, device_event_key, kind, medium, app_package, channel_label, text, device_posted_at, recorded_at, parser_version)`
  - `TipSourceExit(id, tip_id, receipt_id, stated_exit_price, exit_seen_at, created_at)`
  - Index and constraint names: `uq_tips_active_match_key`, `ix_tips_match_key`, `ix_tips_channel_symbol`, `uq_channel_alias_alias`, `uq_caller_channel_name`, `uq_tip_receipt_user_event`, `uq_tip_receipt_tip_user_medium`, `uq_tip_source_exit_tip`.

- [ ] **Step 1: Point the existing fixtures at `comparison_status` (the failing change)**

In `tests/test_external_tip_outcome.py`:
- In `_tip`, replace `stop_loss=Decimal("95"), horizon_days=3, received_at=ANCHOR, tip_as_of=ANCHOR,\n                    status="COMPARED")` with `stop_loss=Decimal("95"), horizon_days=3, received_at=ANCHOR, tip_as_of=ANCHOR,\n                    comparison_status="COMPARED")`.
- Replace `    assert tip.status == TIP_STATUS_RESOLVED` with `    assert tip.comparison_status == TIP_STATUS_RESOLVED`.
- Replace `    assert tip.status == "COMPARED"` with `    assert tip.comparison_status == "COMPARED"`.
- Replace `    assert ready.status == TIP_STATUS_RESOLVED and not_ready.status == "COMPARED"` with `    assert ready.comparison_status == TIP_STATUS_RESOLVED and not_ready.comparison_status == "COMPARED"`.

In `tests/test_external_tip_scoring.py`:
- In `_tip`, replace `horizon_days=3, received_at=NOW, tip_as_of=NOW, status="RESOLVED")` with `horizon_days=3, received_at=NOW, tip_as_of=NOW, comparison_status="RESOLVED")`.
- In `test_head_to_head_none_until_resolved`, replace `horizon_days=3, received_at=NOW, tip_as_of=NOW, status="COMPARED")` with `horizon_days=3, received_at=NOW, tip_as_of=NOW, comparison_status="COMPARED")`.

In `tests/test_api_tips.py`:
- In `test_a_tip_with_no_comparison_still_appears_in_the_list`, replace `raw_payload=None, received_at=datetime.now(timezone.utc), tip_as_of=None, status="FAILED",` with `raw_payload=None, received_at=datetime.now(timezone.utc), tip_as_of=None, comparison_status="FAILED",`.
- In `_resolve_outcome_row`, replace `    tip.status = "RESOLVED"` with `    tip.comparison_status = "RESOLVED"`.

- [ ] **Step 2: Run to verify they fail**

Run: `python -m pytest tests/test_external_tip_outcome.py tests/test_external_tip_scoring.py -v`
Expected: FAIL with `TypeError: 'comparison_status' is an invalid keyword argument for ExternalTip`.

- [ ] **Step 3: Replace `ExternalTip` with `Tip` in `app/models.py`**

Replace the whole `class ExternalTip(Base): ...` block (from `class ExternalTip(Base):` through its `created_at` line) with:

```python
class Tip(Base):
    """The canonical market call (tip-ledger spec §4), formerly EPIC-803's `external_tips`.

    Call terms and `first_seen_at` never change once set and a tip is never deleted
    (`app.tip_ledger_guard`); lifecycle columns are the only mutable ones. `comparison_status`
    is the EPIC-803 comparison state, apart from the lifecycle `status`, which stays NULL on
    rows that predate the ledger until the §12 backfill. `target_price` is the spec's
    `target`; `entry_price` and `horizon_days` mirror the stated entry midpoint and horizon
    for the EPIC-803/845 readers.
    """

    __tablename__ = "tips"
    # NULLs are distinct in PostgreSQL, so a source that sends no reference can
    # send many tips while one that does send a reference cannot duplicate it.
    __table_args__ = (
        UniqueConstraint("source", "source_reference", name="uq_external_tip_source_reference"),
        # tip-ledger spec §2.1: at most one ACTIVE tip per match_key.
        Index(
            "uq_tips_active_match_key",
            "match_key",
            unique=True,
            postgresql_where=text("status = 'ACTIVE'"),
            sqlite_where=text("status = 'ACTIVE'"),
        ),
        Index("ix_tips_channel_symbol", "source_channel_id", "symbol"),
    )
    id: Mapped[int] = mapped_column(BigInteger().with_variant(Integer, "sqlite"), primary_key=True)
    public_id: Mapped[str] = mapped_column(String(36), unique=True, index=True)
    source: Mapped[str] = mapped_column(String(64), index=True)
    # EPIC-819: the registered API client that submitted this tip, resolved from
    # the key it presented. Kept beside `source` rather than replacing it: one is
    # attribution the platform established, the other is the caller's own claim,
    # and a column holding either would answer neither question.
    client_name: Mapped[str | None] = mapped_column(String(64))
    source_reference: Mapped[str | None] = mapped_column(String(128))
    symbol: Mapped[str] = mapped_column(String(32), index=True, active_history=True)
    stock_id: Mapped[int | None] = mapped_column(ForeignKey("stocks.id"))
    direction: Mapped[str | None] = mapped_column(String(8), active_history=True)
    entry_price: Mapped[Decimal | None] = mapped_column(Numeric(18, 6), active_history=True)
    target_price: Mapped[Decimal | None] = mapped_column(Numeric(18, 6), active_history=True)
    stop_loss: Mapped[Decimal | None] = mapped_column(Numeric(18, 6), active_history=True)
    horizon_days: Mapped[int | None] = mapped_column(Integer, active_history=True)
    confidence: Mapped[Decimal | None] = mapped_column(Numeric(10, 6))
    rationale: Mapped[str | None] = mapped_column(Text)
    raw_payload: Mapped[dict | None] = mapped_column(JSON)
    received_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), index=True)
    tip_as_of: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    comparison_status: Mapped[str] = mapped_column(String(24))
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=func.now())
    source_channel_id: Mapped[int | None] = mapped_column(ForeignKey("channels.id"), active_history=True)
    caller_id: Mapped[int | None] = mapped_column(ForeignKey("callers.id"), active_history=True)
    entry_low: Mapped[Decimal | None] = mapped_column(Numeric(18, 6), active_history=True)
    entry_high: Mapped[Decimal | None] = mapped_column(Numeric(18, 6), active_history=True)
    entry_basis: Mapped[str | None] = mapped_column(String(16))
    horizon_sessions: Mapped[int | None] = mapped_column(Integer, active_history=True)
    horizon_basis: Mapped[str | None] = mapped_column(String(16))
    first_seen_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), active_history=True)
    revises_tip_id: Mapped[int | None] = mapped_column(ForeignKey("tips.id"))
    merged_into_tip_id: Mapped[int | None] = mapped_column(ForeignKey("tips.id"))
    prediction_id: Mapped[int | None] = mapped_column(ForeignKey("predictions.id"))
    match_key: Mapped[str | None] = mapped_column(String(64), index=True, active_history=True)
    match_key_version: Mapped[int | None] = mapped_column(Integer)
    parser_version: Mapped[str | None] = mapped_column(String(32))
    status: Mapped[str | None] = mapped_column(String(24))
    entry_status: Mapped[str | None] = mapped_column(String(16))
    outcome: Mapped[str | None] = mapped_column(String(16))
    reason: Mapped[str | None] = mapped_column(String(32))
    entered_session: Mapped[int | None] = mapped_column(Integer)
    closed_session: Mapped[int | None] = mapped_column(Integer)
    closed_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    exit_price: Mapped[Decimal | None] = mapped_column(Numeric(18, 6))
    promised_return: Mapped[Decimal | None] = mapped_column(Numeric(12, 6))
    actual_return: Mapped[Decimal | None] = mapped_column(Numeric(12, 6))


# EPIC-803/845 readers keep the old name until they move (tip-ledger spec §13 phase 6).
ExternalTip = Tip
```

In `ExternalTipComparison`, replace `    tip_id: Mapped[int] = mapped_column(ForeignKey("external_tips.id"), index=True)` with `    tip_id: Mapped[int] = mapped_column(ForeignKey("tips.id"), index=True)`.

In `ExternalTipOutcome`, replace `    tip_id: Mapped[int] = mapped_column(ForeignKey("external_tips.id"), unique=True, index=True)` with `    tip_id: Mapped[int] = mapped_column(ForeignKey("tips.id"), unique=True, index=True)`.

Directly after the `ExternalTipOutcome` class (before `class OutcomeCohortQuarantine`), add:

```python
class Channel(Base):
    """Where calls are published (tip-ledger spec §4). `canonical_channel_id` is itself unless merged."""

    __tablename__ = "channels"
    id: Mapped[int] = mapped_column(BigInteger().with_variant(Integer, "sqlite"), primary_key=True)
    name: Mapped[str] = mapped_column(String(128))
    type: Mapped[str] = mapped_column(String(24))
    canonical_channel_id: Mapped[int | None] = mapped_column(ForeignKey("channels.id"))
    default_horizon_sessions: Mapped[int] = mapped_column(Integer, default=20, server_default="20")
    # Only admin-approved app packages reach phones through GET /channels/capture-list.
    capture_enabled: Mapped[bool] = mapped_column(Boolean, default=False, server_default=false())
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=func.now())


class ChannelAlias(Base):
    """A package name or label that resolves to a channel (tip-ledger spec §4); globally unique."""

    __tablename__ = "channel_aliases"
    __table_args__ = (UniqueConstraint("alias", name="uq_channel_alias_alias"),)
    id: Mapped[int] = mapped_column(BigInteger().with_variant(Integer, "sqlite"), primary_key=True)
    channel_id: Mapped[int] = mapped_column(ForeignKey("channels.id"), index=True)
    alias: Mapped[str] = mapped_column(String(128))
    kind: Mapped[str] = mapped_column(String(16))
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=func.now())


class Caller(Base):
    """A named caller inside a channel (tip-ledger spec §4); `canonical_caller_id` is itself unless merged."""

    __tablename__ = "callers"
    __table_args__ = (UniqueConstraint("channel_id", "name", name="uq_caller_channel_name"),)
    id: Mapped[int] = mapped_column(BigInteger().with_variant(Integer, "sqlite"), primary_key=True)
    channel_id: Mapped[int] = mapped_column(ForeignKey("channels.id"), index=True)
    name: Mapped[str] = mapped_column(String(128))
    canonical_caller_id: Mapped[int | None] = mapped_column(ForeignKey("callers.id"))
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=func.now())


class TipReceipt(Base):
    """One customer's record of having received a tip (tip-ledger spec §4); never deleted."""

    __tablename__ = "tip_receipts"
    __table_args__ = (
        UniqueConstraint("user_id", "device_event_key", name="uq_tip_receipt_user_event"),
        Index(
            "uq_tip_receipt_tip_user_medium",
            "tip_id",
            "user_id",
            "medium",
            unique=True,
            postgresql_where=text("kind = 'TIP'"),
            sqlite_where=text("kind = 'TIP'"),
        ),
    )
    id: Mapped[int] = mapped_column(BigInteger().with_variant(Integer, "sqlite"), primary_key=True)
    public_id: Mapped[str] = mapped_column(String(36), unique=True, index=True)
    tip_id: Mapped[int | None] = mapped_column(ForeignKey("tips.id"), index=True)
    # No FK: a config-only user has no `users` row, and creating one would override its scopes.
    user_id: Mapped[str] = mapped_column(String(128))
    device_event_key: Mapped[str] = mapped_column(String(128))
    kind: Mapped[str] = mapped_column(String(16))
    medium: Mapped[str] = mapped_column(String(24))
    app_package: Mapped[str | None] = mapped_column(String(128))
    channel_label: Mapped[str | None] = mapped_column(String(128))
    text: Mapped[str] = mapped_column(Text)
    device_posted_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    recorded_at: Mapped[datetime] = mapped_column(DateTime(timezone=True))
    parser_version: Mapped[str] = mapped_column(String(32))


class TipSourceExit(Base):
    """A source's exit instruction awaiting the tracker (tip-ledger spec §6.5); the first per tip wins."""

    __tablename__ = "tip_source_exits"
    __table_args__ = (UniqueConstraint("tip_id", name="uq_tip_source_exit_tip"),)
    id: Mapped[int] = mapped_column(BigInteger().with_variant(Integer, "sqlite"), primary_key=True)
    tip_id: Mapped[int] = mapped_column(ForeignKey("tips.id"))
    receipt_id: Mapped[int | None] = mapped_column(ForeignKey("tip_receipts.id"))
    stated_exit_price: Mapped[Decimal | None] = mapped_column(Numeric(18, 6))
    exit_seen_at: Mapped[datetime] = mapped_column(DateTime(timezone=True))
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=func.now())
```

(`text`, `false`, `Index`, `Boolean`, `UniqueConstraint` are already imported on line 3 of `app/models.py`.)

- [ ] **Step 4: Move the comparison-state readers to `comparison_status`**

In `app/external_tip_outcome.py`:
- Replace `    tip.status = TIP_STATUS_RESOLVED` with `    tip.comparison_status = TIP_STATUS_RESOLVED`.
- Replace `select(ExternalTip).where(ExternalTip.status == "COMPARED")` with `select(ExternalTip).where(ExternalTip.comparison_status == "COMPARED")`.

In `api/services/tips.py` (these seven lines only):
- `                status=existing.status,` → `                status=existing.comparison_status,`
- `        status=TIP_STATUS_RECEIVED,` → `        comparison_status=TIP_STATUS_RECEIVED,`
- `        tip.status = TIP_STATUS_FAILED` → `        tip.comparison_status = TIP_STATUS_FAILED`
- `        TipAccepted(tipId=tip.public_id, status=tip.status, receivedAt=_as_aware_utc(tip.received_at)),` → `        TipAccepted(tipId=tip.public_id, status=tip.comparison_status, receivedAt=_as_aware_utc(tip.received_at)),`
- In `_record`: `    tip.status = status` → `    tip.comparison_status = status`
- In `get_tip`: `        status=tip.status,` → `        status=tip.comparison_status,`
- In `list_tips`: `            status=tip.status,` → `            status=tip.comparison_status,`

- [ ] **Step 5: Drop the stale test DB and run the tip regression files**

```bash
python -c "import pathlib, tempfile; pathlib.Path(tempfile.gettempdir(), 'marksy-pytest-default.db').unlink(missing_ok=True)"
python -m pytest tests/test_external_tip_outcome.py tests/test_external_tip_scoring.py tests/test_api_tips.py tests/test_api_tips_ingest.py tests/test_postgres_identifier_limits.py -v
```

Expected: all PASS.

- [ ] **Step 6: Commit**

```bash
git add app/models.py app/external_tip_outcome.py api/services/tips.py tests/test_external_tip_outcome.py tests/test_external_tip_scoring.py tests/test_api_tips.py
git commit -m "$(cat <<'EOF'
Tip ledger: Tip model, channels, callers, receipts, source exits

external_tips becomes tips (ExternalTip kept as an alias). The EPIC-803
comparison state moves to comparison_status so the lifecycle status can
carry the tip-ledger vocabulary (spec §4).

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
)"
```

### Task 9: ORM guard — immutable terms, no deletes

**Files:**
- Create: `app/tip_ledger_guard.py`
- Test: `tests/test_tip_ledger_guard.py`

**Interfaces:**
- Consumes: `Tip`, `TipReceipt` (Task 8), `ENTRY_BASIS_FIRST_SEEN_PRICE` (Task 4).
- Produces: `TERM_FIELDS: tuple[str, ...]`, `TipLedgerImmutableError(RuntimeError)`, and listeners registered on import (`app.tip_ledger` imports this module in Task 11).
- Rules:
  - A term changes only from None, and only on a pre-ledger row (`match_key` never set).
  - On a ledger tip, only `caller_id` may be filled once. The entry fields may be filled once when `entry_basis == FIRST_SEEN_PRICE`.
  - `session.delete()` of a tip or a receipt raises.

- [ ] **Step 1: Write the failing tests**

Create `tests/test_tip_ledger_guard.py`:

```python
"""tip-ledger spec §2.5: call terms and `first_seen_at` are immutable; tips and receipts are never deleted."""

from __future__ import annotations

from datetime import datetime, timezone
from decimal import Decimal

import pytest
from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker

from app.db import Base
from app.models import Tip, TipReceipt
from app.tip_ledger_guard import TipLedgerImmutableError
from app.tip_vocabulary import ENTRY_BASIS_FIRST_SEEN_PRICE, ENTRY_BASIS_STATED, STATUS_ACTIVE, STATUS_TARGET_HIT

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


def _tip(session, **overrides):
    values = dict(
        public_id="t-1", source="Upstox", symbol="RENUKA", direction="BUY", entry_low=Decimal("23.6"),
        entry_high=Decimal("23.6"), entry_price=Decimal("23.6"), entry_basis=ENTRY_BASIS_STATED,
        target_price=Decimal("26"), stop_loss=Decimal("22.25"), horizon_sessions=20, received_at=NOW,
        first_seen_at=NOW, comparison_status="RECEIVED", match_key="k" * 64, status=STATUS_ACTIVE,
    )
    values.update(overrides)
    tip = Tip(**values)
    session.add(tip)
    session.commit()
    return tip


def _assert_rejected(session):
    with pytest.raises(TipLedgerImmutableError):
        session.commit()
    session.rollback()


@pytest.mark.parametrize("field, value", [
    ("target_price", Decimal("27")),
    ("target_price", None),
    ("symbol", "RENUKAX"),
    ("entry_low", Decimal("23")),
    ("first_seen_at", datetime(2026, 9, 30, tzinfo=timezone.utc)),
])
def test_a_stated_term_never_changes(session, field, value):
    tip = _tip(session)
    setattr(tip, field, value)
    _assert_rejected(session)


def test_a_ledger_tip_cannot_gain_a_target_it_never_stated(session):
    tip = _tip(session, target_price=None)
    tip.target_price = Decimal("30")
    _assert_rejected(session)


def test_lifecycle_fields_stay_mutable(session):
    tip = _tip(session)
    tip.status = STATUS_TARGET_HIT
    tip.exit_price = Decimal("26")
    tip.comparison_status = "COMPARED"
    session.commit()
    assert tip.status == STATUS_TARGET_HIT


def test_the_caller_is_filled_once_and_never_replaced(session):
    tip = _tip(session)
    tip.caller_id = 1
    session.commit()
    tip.caller_id = 2
    _assert_rejected(session)


def test_a_first_seen_price_entry_is_stored_once(session):
    tip = _tip(session, entry_low=None, entry_high=None, entry_price=None, entry_basis=ENTRY_BASIS_FIRST_SEEN_PRICE)
    tip.entry_low = tip.entry_high = tip.entry_price = Decimal("24")
    session.commit()
    tip.entry_low = Decimal("25")
    _assert_rejected(session)


def test_a_pre_ledger_row_can_be_backfilled(session):
    tip = _tip(session, match_key=None, source_channel_id=None, horizon_sessions=None, status=None)
    tip.source_channel_id = 3
    tip.horizon_sessions = 5
    session.commit()
    assert (tip.source_channel_id, tip.horizon_sessions) == (3, 5)


def test_tips_and_receipts_are_never_deleted(session):
    tip = _tip(session)
    receipt = TipReceipt(
        public_id="r-1", tip_id=tip.id, user_id="user-1", device_event_key="e-1", kind="TIP", medium="SMS",
        text="", recorded_at=NOW, parser_version="TCP-001",
    )
    session.add(receipt)
    session.commit()
    for row in (tip, receipt):
        session.delete(row)
        with pytest.raises(TipLedgerImmutableError):
            session.flush()
        session.rollback()
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `python -m pytest tests/test_tip_ledger_guard.py -v`
Expected: collection ERROR `ModuleNotFoundError: No module named 'app.tip_ledger_guard'`.

- [ ] **Step 3: Write the guard**

Create `app/tip_ledger_guard.py`:

```python
"""ORM guard for the tip ledger (tip-ledger spec §2.5): call terms and `first_seen_at` never change,
tips and receipts are never deleted. `app.tip_ledger` imports this, so every ledger use registers it."""

from __future__ import annotations

from sqlalchemy import event, inspect

from .models import Tip, TipReceipt
from .tip_vocabulary import ENTRY_BASIS_FIRST_SEEN_PRICE

TERM_FIELDS = (
    "source_channel_id",
    "caller_id",
    "symbol",
    "direction",
    "entry_low",
    "entry_high",
    "entry_price",
    "target_price",
    "stop_loss",
    "horizon_sessions",
    "horizon_days",
    "first_seen_at",
)
_ENTRY_FIELDS = frozenset({"entry_low", "entry_high", "entry_price"})


class TipLedgerImmutableError(RuntimeError):
    pass


def _committed(state, key: str):
    history = state.attrs[key].history
    if history.deleted:
        return history.deleted[0]
    return history.unchanged[0] if history.unchanged else None


def _may_fill(target: Tip, key: str, ledger_tip: bool) -> bool:
    # A pre-ledger row (no match_key yet) is completed by the §12 backfill.
    if not ledger_tip:
        return True
    return key == "caller_id" or (key in _ENTRY_FIELDS and target.entry_basis == ENTRY_BASIS_FIRST_SEEN_PRICE)


@event.listens_for(Tip, "before_update")
def _reject_term_changes(mapper, connection, target):
    state = inspect(target)
    ledger_tip = _committed(state, "match_key") is not None
    changed = []
    for key in TERM_FIELDS:
        history = state.attrs[key].history
        if not history.has_changes():
            continue
        previous = history.deleted[0] if history.deleted else None
        if previous is not None or not _may_fill(target, key, ledger_tip):
            changed.append(key)
    if changed:
        raise TipLedgerImmutableError(f"tip {target.public_id} terms are immutable; {changed} cannot change")


@event.listens_for(Tip, "before_delete")
def _reject_tip_delete(mapper, connection, target):
    raise TipLedgerImmutableError(f"tip {target.public_id} is never deleted")


@event.listens_for(TipReceipt, "before_delete")
def _reject_receipt_delete(mapper, connection, target):
    raise TipLedgerImmutableError(f"receipt {target.public_id} is never deleted")
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `python -m pytest tests/test_tip_ledger_guard.py tests/test_external_tip_outcome.py -v`
Expected: all PASS. (The outcome file's fixture uses bulk `query(...).delete()`, which bypasses mapper events by design.)

- [ ] **Step 5: Commit**

```bash
git add app/tip_ledger_guard.py tests/test_tip_ledger_guard.py
git commit -m "$(cat <<'EOF'
Tip ledger: ORM guard against term changes and deletes

Stated terms and first_seen_at never change; the caller and a
first-seen-price entry fill once; tips and receipts are never deleted.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
)"
```

### Task 10: Alembic migration 0181 (rename, ledger tables, seeds, legacy text scrub)

**Files:**
- Create: `migrations/versions/0181_tip_ledger.py`
- Test: `tests/test_tip_ledger_migration.py`

**Interfaces:**
- Consumes: `tests._migration_helpers.run_revision` (Task 2), the Task 8 table and index names.
- Produces: revision `0181_tip_ledger` (`down_revision = "0180_invalidated_status"`). Its upgrade:
  - renames the table and indexes, and renames `status` to `comparison_status`;
  - adds the ledger columns, `channels`, `channel_aliases`, `callers`, `tip_receipts` and `tip_source_exits`;
  - seeds the SourceRegistry channels and aliases, the Marksy channel with the "Prediction engine" and "Rating engine" callers, and the disabled `legacy-unknown` user;
  - scrubs every existing row's notification text in `raw_payload` and `rationale`. The scrub is one-way.

- [ ] **Step 1: Write the failing test**

Create `tests/test_tip_ledger_migration.py`:

```python
"""0181_tip_ledger on SQLite: legacy rows keep their comparison state and lose their notification
PII, the seeds land, the partial unique index holds, and downgrade restores `external_tips`."""

from __future__ import annotations

import json
from datetime import datetime, timezone

import pytest
import sqlalchemy as sa
from sqlalchemy.exc import IntegrityError

from app.models import User
from tests._migration_helpers import run_revision

_LEDGER_TABLES = {"tips", "channels", "channel_aliases", "callers", "tip_receipts", "tip_source_exits"}


def _legacy_schema(conn):
    """`external_tips` exactly as migrations 0164 + 0173 left it, plus `users` from 0171."""
    metadata = sa.MetaData()
    table = sa.Table(
        "external_tips", metadata,
        sa.Column("id", sa.Integer, primary_key=True),
        sa.Column("public_id", sa.String(36), nullable=False),
        sa.Column("source", sa.String(64), nullable=False),
        sa.Column("source_reference", sa.String(128)),
        sa.Column("symbol", sa.String(32), nullable=False),
        sa.Column("stock_id", sa.Integer()),
        sa.Column("direction", sa.String(8)),
        sa.Column("entry_price", sa.Numeric(18, 6)),
        sa.Column("target_price", sa.Numeric(18, 6)),
        sa.Column("stop_loss", sa.Numeric(18, 6)),
        sa.Column("horizon_days", sa.Integer()),
        sa.Column("confidence", sa.Numeric(10, 6)),
        sa.Column("rationale", sa.Text()),
        sa.Column("raw_payload", sa.JSON()),
        sa.Column("received_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("tip_as_of", sa.DateTime(timezone=True)),
        sa.Column("status", sa.String(24), nullable=False),
        sa.Column("created_at", sa.DateTime(timezone=True), server_default=sa.func.now()),
        sa.Column("client_name", sa.String(64)),
        sa.UniqueConstraint("source", "source_reference", name="uq_external_tip_source_reference"),
    )
    sa.Index("ix_external_tips_public_id", table.c.public_id, unique=True)
    sa.Index("ix_external_tips_source", table.c.source)
    sa.Index("ix_external_tips_symbol", table.c.symbol)
    sa.Index("ix_external_tips_received_at", table.c.received_at)
    metadata.create_all(conn)
    User.__table__.create(conn)
    received = datetime(2026, 9, 1, 10, 0, tzinfo=timezone.utc)
    conn.execute(table.insert().values(
        public_id="legacy-1", source="ICICIDirect", symbol="AAA", status="COMPARED", received_at=received,
    ))
    # What the current app's POST /tips stored verbatim before phase 1.
    app_rows = (
        ("legacy-pii", "com.whatsapp", "Rahul @ StockTips",
         "BUY RENUKA CMP 23.62 SL 22.25 TGT 26. Call 9876543210, PAN ABCDE1234F, a/c 123456789012"),
        ("legacy-dm", "com.whatsapp", "Rahul Sharma", "BUY INFY CMP 1500 SL 1450 TGT 1600"),
        ("legacy-app", "com.upstox.pro", "📈BUY LCCPROJECT with 20.0% upside potential", "Entry : Rs 144.24"),
    )
    for public_id, package, title, body in app_rows:
        conn.execute(table.insert().values(
            public_id=public_id, source="WhatsApp", symbol="RENUKA", status="COMPARED", received_at=received,
            rationale="Callback 9876543210",
            raw_payload={
                "symbol": "RENUKA", "source": "WhatsApp", "sourceReference": "wa:1727499900123",
                "entryPrice": "23.62", "rationale": "Callback 9876543210", "eventId": 41,
                "sourcePackage": package, "title": title, "body": body,
            },
        ))


@pytest.fixture
def engine(tmp_path):
    engine = sa.create_engine(f"sqlite:///{tmp_path / 'ledger.db'}")
    with engine.begin() as conn:
        _legacy_schema(conn)
        run_revision(conn, "0181_tip_ledger")
    yield engine
    engine.dispose()


def test_legacy_rows_keep_their_comparison_state_and_gain_no_lifecycle(engine):
    with engine.connect() as conn:
        tables = set(sa.inspect(conn).get_table_names())
        row = conn.execute(sa.text(
            "SELECT comparison_status, status, first_seen_at, received_at FROM tips WHERE public_id = 'legacy-1'"
        )).one()
    assert _LEDGER_TABLES <= tables and "external_tips" not in tables
    assert (row.comparison_status, row.status, row.first_seen_at) == ("COMPARED", None, row.received_at)


def test_legacy_notification_text_is_scrubbed_and_prices_survive(engine):
    with engine.connect() as conn:
        rows = {
            row.public_id: (row.rationale, json.loads(row.raw_payload))
            for row in conn.execute(sa.text("SELECT public_id, rationale, raw_payload FROM tips WHERE raw_payload IS NOT NULL"))
        }
    rationale, group = rows["legacy-pii"]
    assert group["title"] == "StockTips"
    assert group["body"] == "BUY RENUKA CMP 23.62 SL 22.25 TGT 26. Call [PHONE], PAN [PAN], a/c [NUMBER]"
    assert (rationale, group["rationale"]) == ("Callback [PHONE]", "Callback [PHONE]")
    assert (group["sourceReference"], group["entryPrice"], group["eventId"], group["sourcePackage"]) == (
        "wa:1727499900123", "23.62", 41, "com.whatsapp",
    )
    assert rows["legacy-dm"][1]["title"] == "[CONTACT]"
    assert rows["legacy-app"][1]["title"] == "📈BUY LCCPROJECT with 20.0% upside potential"
    assert rows["legacy-app"][1]["body"] == "Entry : Rs 144.24"


def test_source_registry_marksy_and_the_legacy_user_are_seeded(engine):
    with engine.connect() as conn:
        aliases = dict(conn.execute(sa.text(
            "SELECT a.alias, c.name FROM channel_aliases a JOIN channels c ON c.id = a.channel_id"
        )).all())
        marksy = conn.execute(sa.text(
            "SELECT id, type, capture_enabled, canonical_channel_id FROM channels WHERE name = 'Marksy'"
        )).one()
        callers = conn.execute(
            sa.text("SELECT name FROM callers WHERE channel_id = :id ORDER BY name"), {"id": marksy.id}
        ).scalars().all()
        capture = dict(conn.execute(sa.text("SELECT name, capture_enabled FROM channels")).all())
        legacy = conn.execute(sa.text("SELECT disabled, scopes FROM users WHERE user_id = 'legacy-unknown'")).one()
    assert aliases["com.upstox.pro"] == aliases["in.upstox.app"] == aliases["upstox"] == "Upstox"
    assert (aliases["com.divum.moneycontrol"], aliases["com.whatsapp.w4b"]) == ("Moneycontrol", "WhatsApp Business")
    assert "marksy" not in aliases
    assert (marksy.type, bool(marksy.capture_enabled), marksy.canonical_channel_id) == ("MARKSY", False, marksy.id)
    assert callers == ["Prediction engine", "Rating engine"]
    assert (bool(capture["Upstox"]), bool(capture["WhatsApp"])) == (True, False)
    assert bool(legacy.disabled) and legacy.scopes == "[]"


def test_only_one_active_tip_may_hold_a_match_key(engine):
    insert = sa.text(
        "INSERT INTO tips (public_id, source, symbol, received_at, comparison_status, match_key, status) "
        "VALUES (:p, 'S', 'AAA', '2026-09-29', 'RECEIVED', 'k', :s)"
    )
    with engine.begin() as conn:
        conn.execute(insert, {"p": "a", "s": "ACTIVE"})
        conn.execute(insert, {"p": "b", "s": "UNSCORABLE"})
    with pytest.raises(IntegrityError), engine.begin() as conn:
        conn.execute(insert, {"p": "c", "s": "ACTIVE"})


def test_downgrade_restores_external_tips_but_not_the_scrubbed_text(engine):
    with engine.begin() as conn:
        run_revision(conn, "0181_tip_ledger", "downgrade")
    with engine.connect() as conn:
        tables = set(sa.inspect(conn).get_table_names())
        status = conn.execute(sa.text("SELECT status FROM external_tips WHERE public_id = 'legacy-1'")).scalar_one()
        legacy_users = conn.execute(sa.text("SELECT count(*) FROM users WHERE user_id = 'legacy-unknown'")).scalar_one()
        payload = conn.execute(sa.text("SELECT raw_payload FROM external_tips WHERE public_id = 'legacy-pii'")).scalar_one()
    assert "external_tips" in tables and not tables & _LEDGER_TABLES
    assert (status, legacy_users) == ("COMPARED", 0)
    assert json.loads(payload)["title"] == "StockTips"
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `python -m pytest tests/test_tip_ledger_migration.py -v`
Expected: ERROR `FileNotFoundError` for `0181_tip_ledger.py`.

- [ ] **Step 3: Write the migration**

Create `migrations/versions/0181_tip_ledger.py`:

```python
"""Central tip ledger, phase 1 (tip-ledger spec §4): `external_tips` becomes `tips`, plus channels,
channel aliases, callers, tip receipts and pending source exits.

`external_tips.status` held the EPIC-803 comparison state (RECEIVED, COMPARED, UNRESOLVED_SYMBOL,
FAILED, RESOLVED), which the lifecycle `status` (ACTIVE, TARGET_HIT, ...) cannot share, so it is
renamed `comparison_status` and every existing row keeps its value there. The new `status` stays
NULL on those rows until the §12 backfill; their `first_seen_at` is `received_at`, which §12
defines as the same instant.

Seeds marksy-os `SourceRegistry` channels and aliases, the Marksy channel with its two engine
callers, and the disabled system user `legacy-unknown` that owns receipts no customer can be
named for. `tip_daily_progress` lands with its writer in phase 2.

Every existing row's notification text is scrubbed (§2.11). In `raw_payload`, a chat title keeps only
its group ("Rahul @ StockTips" -> "StockTips") and a 1:1 chat title, which is a person, becomes
"[CONTACT]". `body`, `rationale` and every other free-text value are masked for phones, emails, PAN
codes and 8+ digit runs. The §12 backfill builds receipts from this cleaned text, which is all a
receipt stores anyway.

Downgrade drops everything the ledger added, receipts included; tips created since stay as rows.
The scrub is ONE-WAY: downgrade cannot restore raw notification text.
"""
from __future__ import annotations

import re
from datetime import datetime, timezone

import sqlalchemy as sa
from alembic import op

revision = "0181_tip_ledger"
down_revision = "0180_invalidated_status"
branch_labels = None
depends_on = None

LEGACY_UNKNOWN_USER_ID = "legacy-unknown"
_ID = sa.BigInteger().with_variant(sa.Integer(), "sqlite")
_RENAMED_INDEXES = {
    "ix_external_tips_public_id": ("ix_tips_public_id", "public_id", True),
    "ix_external_tips_source": ("ix_tips_source", "source", False),
    "ix_external_tips_symbol": ("ix_tips_symbol", "symbol", False),
    "ix_external_tips_received_at": ("ix_tips_received_at", "received_at", False),
}
# (name, type, capture_enabled, package aliases) from marksy-os notification/SourceRegistry.kt.
_SEED_CHANNELS = (
    ("WhatsApp", "WHATSAPP_GROUP", False, ("com.whatsapp",)),
    ("WhatsApp Business", "WHATSAPP_GROUP", False, ("com.whatsapp.w4b",)),
    ("Upstox", "BROKER_APP", True, ("com.upstox.pro", "in.upstox.app")),
    ("ICICI Direct", "BROKER_APP", True, ("com.icicidirect", "com.icicidirect.idirectsuper")),
    ("ET Money", "BROKER_APP", True, ("com.etmoney",)),
    ("Zerodha", "BROKER_APP", True, ("com.zerodha.kite3", "com.zerodha.kite")),
    ("Groww", "BROKER_APP", True, ("com.nextbillion.groww",)),
    ("Angel One", "BROKER_APP", True, ("com.angelbroking.smartmoney", "com.angelbroking.lite")),
    ("5paisa", "BROKER_APP", True, ("com.fivepaisa.trade",)),
    ("Zerodha Coin", "BROKER_APP", True, ("com.zerodha.coin",)),
    ("StockGro", "BROKER_APP", True, ("com.assetgro.stockgro.prod",)),
    ("Moneycontrol", "NEWS_PORTAL", True, ("com.divum.moneycontrol",)),
    ("Marksy", "MARKSY", False, ()),
)
_MARKSY_CALLERS = ("Prediction engine", "Rating engine")
# Copied from app/tip_text_cleaning.py, not imported: a migration must replay identically after that module changes.
_EMAIL = re.compile(r"[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}")
_PHONE = re.compile(r"(?<![\w+])(?:(?:\+91|0091|91|0)[\s-]?)?[6-9]\d{4}[\s-]?\d{5}(?!\w)")
_PAN = re.compile(r"\b[A-Z]{5}[0-9]{4}[A-Z]\b")
_DIGIT_RUN = re.compile(r"\d{8,}")
_MASKS = ((_EMAIL, "[EMAIL]"), (_PHONE, "[PHONE]"), (_PAN, "[PAN]"), (_DIGIT_RUN, "[NUMBER]"))
_MESSAGING_PACKAGES = ("com.whatsapp", "com.whatsapp.w4b")
# Machine values in the legacy app and paste payloads; masking them would corrupt keys, prices and timestamps.
_MACHINE_KEYS = frozenset({
    "symbol", "source", "sourceReference", "sourcePackage", "direction", "category", "tipAsOf", "occurredAt",
    "entryPrice", "targetPrice", "stopLoss", "horizonDays", "confidence", "notificationConfidence",
})


def _fk(target: str) -> tuple:
    # SQLite cannot ALTER a constraint onto an existing table; tests build it from the ORM instead.
    return () if op.get_bind().dialect.name == "sqlite" else (sa.ForeignKey(target),)


def _tip_columns() -> list[sa.Column]:
    return [
        sa.Column("source_channel_id", sa.BigInteger(), *_fk("channels.id"), nullable=True),
        sa.Column("caller_id", sa.BigInteger(), *_fk("callers.id"), nullable=True),
        sa.Column("entry_low", sa.Numeric(18, 6), nullable=True),
        sa.Column("entry_high", sa.Numeric(18, 6), nullable=True),
        sa.Column("entry_basis", sa.String(16), nullable=True),
        sa.Column("horizon_sessions", sa.Integer(), nullable=True),
        sa.Column("horizon_basis", sa.String(16), nullable=True),
        sa.Column("first_seen_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("revises_tip_id", sa.BigInteger(), *_fk("tips.id"), nullable=True),
        sa.Column("merged_into_tip_id", sa.BigInteger(), *_fk("tips.id"), nullable=True),
        sa.Column("prediction_id", sa.BigInteger(), *_fk("predictions.id"), nullable=True),
        sa.Column("match_key", sa.String(64), nullable=True),
        sa.Column("match_key_version", sa.Integer(), nullable=True),
        sa.Column("parser_version", sa.String(32), nullable=True),
        sa.Column("status", sa.String(24), nullable=True),
        sa.Column("entry_status", sa.String(16), nullable=True),
        sa.Column("outcome", sa.String(16), nullable=True),
        sa.Column("reason", sa.String(32), nullable=True),
        sa.Column("entered_session", sa.Integer(), nullable=True),
        sa.Column("closed_session", sa.Integer(), nullable=True),
        sa.Column("closed_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("exit_price", sa.Numeric(18, 6), nullable=True),
        sa.Column("promised_return", sa.Numeric(12, 6), nullable=True),
        sa.Column("actual_return", sa.Numeric(12, 6), nullable=True),
    ]


def upgrade() -> None:
    op.rename_table("external_tips", "tips")
    for old, (new, column, unique) in _RENAMED_INDEXES.items():
        op.drop_index(old, table_name="tips")
        op.create_index(new, "tips", [column], unique=unique)
    op.execute("ALTER TABLE tips RENAME COLUMN status TO comparison_status")
    _scrub_legacy_text()

    op.create_table(
        "channels",
        sa.Column("id", _ID, primary_key=True),
        sa.Column("name", sa.String(128), nullable=False),
        sa.Column("type", sa.String(24), nullable=False),
        sa.Column("canonical_channel_id", sa.BigInteger(), sa.ForeignKey("channels.id"), nullable=True),
        sa.Column("default_horizon_sessions", sa.Integer(), nullable=False, server_default="20"),
        sa.Column("capture_enabled", sa.Boolean(), nullable=False, server_default=sa.false()),
        sa.Column("created_at", sa.DateTime(timezone=True), server_default=sa.func.now(), nullable=False),
    )
    op.create_table(
        "channel_aliases",
        sa.Column("id", _ID, primary_key=True),
        sa.Column("channel_id", sa.BigInteger(), sa.ForeignKey("channels.id"), nullable=False),
        sa.Column("alias", sa.String(128), nullable=False),
        sa.Column("kind", sa.String(16), nullable=False),
        sa.Column("created_at", sa.DateTime(timezone=True), server_default=sa.func.now(), nullable=False),
        sa.UniqueConstraint("alias", name="uq_channel_alias_alias"),
    )
    op.create_index("ix_channel_aliases_channel_id", "channel_aliases", ["channel_id"])
    op.create_table(
        "callers",
        sa.Column("id", _ID, primary_key=True),
        sa.Column("channel_id", sa.BigInteger(), sa.ForeignKey("channels.id"), nullable=False),
        sa.Column("name", sa.String(128), nullable=False),
        sa.Column("canonical_caller_id", sa.BigInteger(), sa.ForeignKey("callers.id"), nullable=True),
        sa.Column("created_at", sa.DateTime(timezone=True), server_default=sa.func.now(), nullable=False),
        sa.UniqueConstraint("channel_id", "name", name="uq_caller_channel_name"),
    )
    op.create_index("ix_callers_channel_id", "callers", ["channel_id"])

    for column in _tip_columns():
        op.add_column("tips", column)
    op.create_index("ix_tips_match_key", "tips", ["match_key"])
    op.create_index("ix_tips_channel_symbol", "tips", ["source_channel_id", "symbol"])
    op.create_index(
        "uq_tips_active_match_key",
        "tips",
        ["match_key"],
        unique=True,
        postgresql_where=sa.text("status = 'ACTIVE'"),
        sqlite_where=sa.text("status = 'ACTIVE'"),
    )
    op.execute("UPDATE tips SET first_seen_at = received_at WHERE first_seen_at IS NULL")

    op.create_table(
        "tip_receipts",
        sa.Column("id", _ID, primary_key=True),
        sa.Column("public_id", sa.String(36), nullable=False),
        sa.Column("tip_id", sa.BigInteger(), sa.ForeignKey("tips.id"), nullable=True),
        sa.Column("user_id", sa.String(128), nullable=False),
        sa.Column("device_event_key", sa.String(128), nullable=False),
        sa.Column("kind", sa.String(16), nullable=False),
        sa.Column("medium", sa.String(24), nullable=False),
        sa.Column("app_package", sa.String(128), nullable=True),
        sa.Column("channel_label", sa.String(128), nullable=True),
        sa.Column("text", sa.Text(), nullable=False),
        sa.Column("device_posted_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("recorded_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("parser_version", sa.String(32), nullable=False),
        sa.UniqueConstraint("user_id", "device_event_key", name="uq_tip_receipt_user_event"),
    )
    op.create_index("ix_tip_receipts_public_id", "tip_receipts", ["public_id"], unique=True)
    op.create_index("ix_tip_receipts_tip_id", "tip_receipts", ["tip_id"])
    op.create_index(
        "uq_tip_receipt_tip_user_medium",
        "tip_receipts",
        ["tip_id", "user_id", "medium"],
        unique=True,
        postgresql_where=sa.text("kind = 'TIP'"),
        sqlite_where=sa.text("kind = 'TIP'"),
    )
    op.create_table(
        "tip_source_exits",
        sa.Column("id", _ID, primary_key=True),
        sa.Column("tip_id", sa.BigInteger(), sa.ForeignKey("tips.id"), nullable=False),
        sa.Column("receipt_id", sa.BigInteger(), sa.ForeignKey("tip_receipts.id"), nullable=True),
        sa.Column("stated_exit_price", sa.Numeric(18, 6), nullable=True),
        sa.Column("exit_seen_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("created_at", sa.DateTime(timezone=True), server_default=sa.func.now(), nullable=False),
        sa.UniqueConstraint("tip_id", name="uq_tip_source_exit_tip"),
    )
    _seed()


def _clean(text: str) -> str:
    for pattern, mask in _MASKS:
        text = pattern.sub(mask, text)
    return text


def _is_messaging(package) -> bool:
    name = package.strip().lower() if isinstance(package, str) else ""
    return name in _MESSAGING_PACKAGES or name.startswith("org.telegram")


def _scrubbed_title(title: str, package) -> str:
    value = title.strip()
    if _is_messaging(package):
        # A chat notification's title is a person unless it reads "sender @ group"; only the group survives.
        if " @ " not in value:
            return "[CONTACT]"
        value = value.rsplit(" @ ", 1)[1].strip()
    return _clean(value)[:128]


def _scrubbed(value):
    if isinstance(value, str):
        return _clean(value)
    if isinstance(value, list):
        return [_scrubbed(item) for item in value]
    if isinstance(value, dict):
        return {key: item if key in _MACHINE_KEYS else _scrubbed(item) for key, item in value.items()}
    return value


def _scrub_legacy_text() -> None:
    """One-way: no username is known for legacy rows, so every mask except the username applies."""
    bind = op.get_bind()
    tips = sa.table(
        "tips", sa.column("id", sa.BigInteger), sa.column("rationale", sa.Text), sa.column("raw_payload", sa.JSON),
    )
    updates = []
    for row in bind.execute(sa.select(tips.c.id, tips.c.rationale, tips.c.raw_payload)).all():
        payload = _scrubbed(row.raw_payload)
        if isinstance(row.raw_payload, dict) and isinstance(row.raw_payload.get("title"), str):
            payload["title"] = _scrubbed_title(row.raw_payload["title"], row.raw_payload.get("sourcePackage"))
        rationale = _clean(row.rationale) if row.rationale else row.rationale
        if payload != row.raw_payload or rationale != row.rationale:
            updates.append({"row_id": row.id, "new_rationale": rationale, "new_payload": payload})
    if updates:
        bind.execute(
            tips.update()
            .where(tips.c.id == sa.bindparam("row_id"))
            .values(rationale=sa.bindparam("new_rationale"), raw_payload=sa.bindparam("new_payload", type_=sa.JSON())),
            updates,
        )


def _seed() -> None:
    bind = op.get_bind()
    channels = sa.table(
        "channels", sa.column("id", sa.BigInteger), sa.column("name", sa.String), sa.column("type", sa.String),
        sa.column("default_horizon_sessions", sa.Integer), sa.column("capture_enabled", sa.Boolean),
    )
    aliases = sa.table(
        "channel_aliases", sa.column("channel_id", sa.BigInteger), sa.column("alias", sa.String),
        sa.column("kind", sa.String),
    )
    callers = sa.table("callers", sa.column("channel_id", sa.BigInteger), sa.column("name", sa.String))
    users = sa.table(
        "users", sa.column("user_id", sa.String), sa.column("scopes", sa.JSON), sa.column("disabled", sa.Boolean),
        sa.column("created_at", sa.DateTime(timezone=True)), sa.column("updated_at", sa.DateTime(timezone=True)),
    )
    for name, channel_type, capture, packages in _SEED_CHANNELS:
        channel_id = bind.execute(
            channels.insert()
            .values(name=name, type=channel_type, default_horizon_sessions=20, capture_enabled=capture)
            .returning(channels.c.id)
        ).scalar_one()
        if channel_type == "MARKSY":
            # No alias: customer intake must never resolve to Marksy's own record.
            bind.execute(callers.insert(), [{"channel_id": channel_id, "name": caller} for caller in _MARKSY_CALLERS])
            continue
        rows = [{"channel_id": channel_id, "alias": package, "kind": "PACKAGE"} for package in packages]
        rows.append({"channel_id": channel_id, "alias": name.lower(), "kind": "LABEL"})
        bind.execute(aliases.insert(), rows)
    op.execute("UPDATE channels SET canonical_channel_id = id WHERE canonical_channel_id IS NULL")
    op.execute("UPDATE callers SET canonical_caller_id = id WHERE canonical_caller_id IS NULL")
    if bind.execute(sa.select(users.c.user_id).where(users.c.user_id == LEGACY_UNKNOWN_USER_ID)).first() is None:
        now = datetime.now(timezone.utc)
        bind.execute(users.insert().values(
            user_id=LEGACY_UNKNOWN_USER_ID, scopes=[], disabled=True, created_at=now, updated_at=now,
        ))


def downgrade() -> None:
    # The §2.11 text scrub is one-way: raw notification text is gone and is not restored here.
    op.execute(f"DELETE FROM users WHERE user_id = '{LEGACY_UNKNOWN_USER_ID}'")
    op.drop_table("tip_source_exits")
    op.drop_index("uq_tip_receipt_tip_user_medium", table_name="tip_receipts")
    op.drop_index("ix_tip_receipts_tip_id", table_name="tip_receipts")
    op.drop_index("ix_tip_receipts_public_id", table_name="tip_receipts")
    op.drop_table("tip_receipts")
    op.drop_index("uq_tips_active_match_key", table_name="tips")
    op.drop_index("ix_tips_channel_symbol", table_name="tips")
    op.drop_index("ix_tips_match_key", table_name="tips")
    for column in reversed(_tip_columns()):
        op.drop_column("tips", column.name)
    op.drop_index("ix_callers_channel_id", table_name="callers")
    op.drop_table("callers")
    op.drop_index("ix_channel_aliases_channel_id", table_name="channel_aliases")
    op.drop_table("channel_aliases")
    op.drop_table("channels")
    op.execute("ALTER TABLE tips RENAME COLUMN comparison_status TO status")
    for old, (new, column, unique) in _RENAMED_INDEXES.items():
        op.drop_index(new, table_name="tips")
        op.create_index(old, "tips", [column], unique=unique)
    op.rename_table("tips", "external_tips")
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `python -m pytest tests/test_tip_ledger_migration.py tests/test_alembic_single_head.py -v`
Expected: all PASS.

- [ ] **Step 5: Commit**

```bash
git add migrations/versions/0181_tip_ledger.py tests/test_tip_ledger_migration.py
git commit -m "$(cat <<'EOF'
Tip ledger: migration 0181 (rename, ledger tables, seeds, PII scrub)

external_tips -> tips with status -> comparison_status; channels,
aliases, callers, receipts, source exits; ACTIVE match_key partial index
on SQLite and PostgreSQL; SourceRegistry, Marksy and legacy-unknown seeds;
one-way scrub of legacy notification text (spec §2.11).

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
)"
```

### Task 11: Ledger intake core

**Files:**
- Create: `app/tip_ledger.py`
- Test: `tests/test_tip_ledger.py`

**Interfaces:**
- Consumes: `StatedTerms`, `entry_mid`, `intake_rejection`, `match_key_v1`, `MATCH_KEY_VERSION` (Task 7); models (Task 8); the guard (Task 9); `app.market_calendar.count_trading_days(session, exchange, start_date, end_date) -> int`; `app.market_data.quality.NSE_TIMEZONE`.
- Produces (used by Tasks 12–13):
  - `ReservedChannelError(ValueError)`
  - `ReceiptInput(user_id: str, device_event_key: str, medium: str, app_package: str | None, channel_label: str | None, text: str, device_posted_at: datetime | None, parser_version: str)`
  - `TipExtras(source=None, client_name=None, source_reference=None, confidence=None, rationale=None, raw_payload=None, tip_as_of=None)`
  - `Intake(kind: str, terms: StatedTerms | None = None, exit_symbol: str | None = None, exit_direction: str | None = None, exit_price: Decimal | None = None, caller_name: str | None = None, extras: TipExtras = TipExtras())`
  - `IntakeResult(receipt: TipReceipt, tip: Tip | None, kind: str, matched: bool, created: bool)`
  - `normalize_alias(value: str) -> str`
  - `resolve_channel(session, *, medium, app_package, channel_label) -> Channel`
  - `resolve_caller(session, channel_id: int, name: str) -> Caller`
  - `find_matchable(session, match_key: str, *, now: datetime) -> Tip | None`
  - `record_intake(session, receipt_in: ReceiptInput, intake: Intake, *, now: datetime) -> IntakeResult` (commits; `created` means the caller should run the EPIC-803 comparison)

- [ ] **Step 1: Write the failing tests**

Create `tests/test_tip_ledger.py`:

```python
"""tip-ledger spec §5.2 intake: one canonical tip per call, one receipt per customer."""

from __future__ import annotations

from datetime import datetime, timedelta, timezone
from decimal import Decimal

import pytest
from sqlalchemy import create_engine, select
from sqlalchemy.orm import sessionmaker

from api.schemas.tips import TIP_STATUS_RECEIVED
from app import tip_ledger
from app.db import Base
from app.models import Caller, Channel, ChannelAlias, Stock, Tip, TipReceipt, TipSourceExit
from app.tip_ledger import Intake, ReceiptInput, ReservedChannelError, TipExtras, record_intake
from app.tip_matching import stated_terms
from app.tip_vocabulary import (
    ALIAS_LABEL,
    ALIAS_PACKAGE,
    CHANNEL_BROKER_APP,
    CHANNEL_MARKSY,
    CHANNEL_TELEGRAM_CHANNEL,
    CHANNEL_WHATSAPP_GROUP,
    COMPARISON_STATUS_RECEIVED,
    DIRECTION_BUY,
    ENTRY_BASIS_FIRST_SEEN_PRICE,
    ENTRY_BASIS_STATED,
    ENTRY_ENTERED,
    ENTRY_WAITING,
    HORIZON_BASIS_CHANNEL_DEFAULT,
    HORIZON_BASIS_STATED,
    KIND_EXIT,
    KIND_REVISION,
    KIND_TIP,
    KIND_UNPARSED,
    MEDIUM_APP_NOTIFICATION,
    MEDIUM_MANUAL,
    MEDIUM_TELEGRAM,
    MEDIUM_WHATSAPP,
    REASON_INCONSISTENT_LEVELS,
    REASON_NO_DIRECTION,
    REASON_UNRESOLVED_SYMBOL,
    STATUS_ACTIVE,
    STATUS_DATA_UNRESOLVED,
    STATUS_TARGET_HIT,
    STATUS_UNSCORABLE,
)

NOW = datetime(2026, 9, 28, 5, 0, tzinfo=timezone.utc)  # Monday, 10:30 IST


@pytest.fixture
def session():
    engine = create_engine("sqlite:///:memory:")
    Base.metadata.create_all(engine)
    db = sessionmaker(bind=engine)()
    db.add_all([Stock(symbol="RENUKA", exchange="NSE", is_active=True), Stock(symbol="RELIANCE", exchange="NSE", is_active=True)])
    upstox = Channel(name="Upstox", type=CHANNEL_BROKER_APP, default_horizon_sessions=20, capture_enabled=True)
    db.add(upstox)
    db.flush()
    upstox.canonical_channel_id = upstox.id
    db.add_all([
        ChannelAlias(channel_id=upstox.id, alias="com.upstox.pro", kind=ALIAS_PACKAGE),
        ChannelAlias(channel_id=upstox.id, alias="upstox", kind=ALIAS_LABEL),
    ])
    db.commit()
    try:
        yield db
    finally:
        db.close()


def _aware(value):
    return value if value.tzinfo is not None else value.replace(tzinfo=timezone.utc)


def _receipt(user="user-1", key="e-1", medium=MEDIUM_APP_NOTIFICATION, package="com.upstox.pro", label="Upstox",
             text="BUY RENUKA CMP 23.62 SL 22.25 TGT 26"):
    return ReceiptInput(
        user_id=user, device_event_key=key, medium=medium, app_package=package, channel_label=label, text=text,
        device_posted_at=None, parser_version="TCP-001",
    )


def _terms(**overrides):
    values = dict(
        symbol="RENUKA", direction="BUY", entry_low=Decimal("23.62"), entry_high=Decimal("23.62"),
        target=Decimal("26"), stop_loss=Decimal("22.25"), horizon_sessions=None,
    )
    values.update(overrides)
    return stated_terms(**values)


def _tip_intake(session, receipt, terms, *, now=NOW, caller=None, extras=None):
    return record_intake(session, receipt, Intake(kind=KIND_TIP, terms=terms, caller_name=caller, extras=extras or TipExtras()), now=now)


def _reliance(**overrides):
    values = dict(symbol="RELIANCE", entry_low=Decimal("2500"), entry_high=Decimal("2500"), target=Decimal("2600"), stop_loss=Decimal("2400"))
    values.update(overrides)
    return _terms(**values)


def test_the_same_call_from_two_customers_is_one_tip_with_two_receipts(session):
    first = _tip_intake(session, _receipt(user="user-1", key="e-1"), _terms())
    second = _tip_intake(session, _receipt(user="user-2", key="e-9"), _terms())
    assert (first.created, first.matched, second.created, second.matched) == (True, False, False, True)
    assert second.tip.id == first.tip.id and session.query(Tip).count() == 1
    assert sorted(r.user_id for r in session.scalars(select(TipReceipt))) == ["user-1", "user-2"]


def test_a_retried_event_returns_the_same_receipt(session):
    first = _tip_intake(session, _receipt(), _terms())
    again = _tip_intake(session, _receipt(), _terms())
    assert (again.receipt.id, again.created, again.matched) == (first.receipt.id, False, False)
    assert session.query(TipReceipt).count() == 1


def test_the_same_customer_twice_on_one_medium_keeps_one_receipt(session):
    first = _tip_intake(session, _receipt(key="e-1"), _terms())
    duplicate = _tip_intake(session, _receipt(key="e-2"), _terms())
    assert (duplicate.receipt.id, duplicate.tip.id, duplicate.matched) == (first.receipt.id, first.tip.id, True)
    assert session.query(TipReceipt).count() == 1


def test_different_stated_terms_are_different_tips(session):
    first = _tip_intake(session, _receipt(key="e-1"), _terms())
    other = _tip_intake(session, _receipt(key="e-2"), _terms(target=Decimal("27")))
    assert other.tip.id != first.tip.id and other.created


def test_an_identical_call_after_the_first_closed_is_a_new_tip(session):
    first = _tip_intake(session, _receipt(user="user-1", key="e-1"), _terms())
    first.tip.status = STATUS_TARGET_HIT
    session.commit()
    later = _tip_intake(session, _receipt(user="user-2", key="e-2"), _terms(), now=NOW + timedelta(days=3))
    assert later.tip.id != first.tip.id and later.created


def test_a_new_tip_records_its_stated_terms_and_bases(session):
    tip = _tip_intake(session, _receipt(), _terms()).tip
    assert (tip.status, tip.entry_status, tip.entry_basis, tip.horizon_sessions, tip.horizon_basis) == (
        STATUS_ACTIVE, ENTRY_WAITING, ENTRY_BASIS_STATED, 20, HORIZON_BASIS_CHANNEL_DEFAULT,
    )
    assert (tip.match_key_version, tip.horizon_days, tip.entry_price, tip.comparison_status) == (
        1, None, Decimal("23.62"), COMPARISON_STATUS_RECEIVED,
    )
    assert COMPARISON_STATUS_RECEIVED == TIP_STATUS_RECEIVED
    no_entry = _tip_intake(session, _receipt(user="user-2", key="e-2"), _terms(entry_low=None, entry_high=None, horizon_sessions=5)).tip
    assert (no_entry.entry_status, no_entry.entry_basis, no_entry.horizon_basis, no_entry.horizon_days) == (
        ENTRY_ENTERED, ENTRY_BASIS_FIRST_SEEN_PRICE, HORIZON_BASIS_STATED, 5,
    )


@pytest.mark.parametrize("overrides, status, reason", [
    ({"direction": None}, STATUS_UNSCORABLE, REASON_NO_DIRECTION),
    ({"target": Decimal("20")}, STATUS_UNSCORABLE, REASON_INCONSISTENT_LEVELS),
    ({"symbol": "NOSUCHCO"}, STATUS_DATA_UNRESOLVED, REASON_UNRESOLVED_SYMBOL),
])
def test_a_tip_rejected_at_intake_is_closed_when_first_seen(session, overrides, status, reason):
    tip = _tip_intake(session, _receipt(), _terms(**overrides)).tip
    assert (tip.status, tip.reason, tip.outcome, tip.entry_status) == (status, reason, None, None)
    assert _aware(tip.closed_at) == _aware(tip.first_seen_at) == NOW


def test_a_later_copy_of_a_rejected_tip_attaches_only_within_its_horizon(session):
    first = _tip_intake(session, _receipt(user="user-1", key="e-1"), _terms(direction=None))
    within = _tip_intake(session, _receipt(user="user-2", key="e-2"), _terms(direction=None), now=NOW + timedelta(days=2))
    after = _tip_intake(session, _receipt(user="user-3", key="e-3"), _terms(direction=None), now=NOW + timedelta(days=35))
    assert (within.tip.id, within.matched) == (first.tip.id, True)
    assert after.tip.id != first.tip.id and after.created


def test_a_source_reference_names_its_tip_across_re_pastes(session):
    extras = TipExtras(source="AiTradingAgent", source_reference="P-1")
    paste = _receipt(medium=MEDIUM_MANUAL, package=None, label="AiTradingAgent")
    first = _tip_intake(session, paste, _terms(), extras=extras)
    again = _tip_intake(session, _receipt(user="admin-2", key="e-2", medium=MEDIUM_MANUAL, package=None, label="AiTradingAgent"),
                        _terms(target=Decimal("27")), extras=extras)
    assert again.tip.id == first.tip.id and session.query(Tip).count() == 1


def test_an_unknown_app_package_creates_a_broker_channel_that_is_not_captured(session):
    tip = _tip_intake(session, _receipt(package="com.new.broker", label="New Broker"), _terms()).tip
    channel = session.get(Channel, tip.source_channel_id)
    assert (channel.name, channel.type, channel.canonical_channel_id, channel.capture_enabled) == (
        "New Broker", CHANNEL_BROKER_APP, channel.id, False,
    )
    aliases = set(session.execute(select(ChannelAlias.alias, ChannelAlias.kind).where(ChannelAlias.channel_id == channel.id)).all())
    assert aliases == {("com.new.broker", ALIAS_PACKAGE), ("new broker", ALIAS_LABEL)}


def test_a_whatsapp_group_is_its_own_channel_not_the_whatsapp_app(session):
    whatsapp = Channel(name="WhatsApp", type=CHANNEL_WHATSAPP_GROUP, default_horizon_sessions=20, capture_enabled=False)
    session.add(whatsapp)
    session.flush()
    session.add(ChannelAlias(channel_id=whatsapp.id, alias="com.whatsapp", kind=ALIAS_PACKAGE))
    session.commit()
    tip = _tip_intake(session, _receipt(medium=MEDIUM_WHATSAPP, package="com.whatsapp", label="StockTips"), _terms()).tip
    channel = session.get(Channel, tip.source_channel_id)
    assert (channel.name, channel.type) == ("StockTips", CHANNEL_WHATSAPP_GROUP)


def test_a_customer_label_never_lands_on_marksys_channel(session):
    marksy = Channel(name="Marksy", type=CHANNEL_MARKSY, default_horizon_sessions=20, capture_enabled=False)
    session.add(marksy)
    session.commit()
    spoof = _tip_intake(session, _receipt(medium=MEDIUM_TELEGRAM, package=None, label="Marksy"), _terms()).tip
    assert session.get(Channel, spoof.source_channel_id).type == CHANNEL_TELEGRAM_CHANNEL
    session.add(ChannelAlias(channel_id=marksy.id, alias="marksy official", kind=ALIAS_LABEL))
    session.commit()
    with pytest.raises(ReservedChannelError):
        _tip_intake(session, _receipt(key="e-9", medium=MEDIUM_TELEGRAM, package=None, label="Marksy Official"), _terms())


def test_a_revision_is_a_new_tip_that_leaves_the_original_untouched(session):
    original = _tip_intake(session, _receipt(key="e-1"), _reliance())
    revised = record_intake(
        session, _receipt(key="e-2", text="RELIANCE: target revised to 2650"),
        Intake(kind=KIND_REVISION, terms=_reliance(direction=None, entry_low=None, entry_high=None, target=Decimal("2650"), stop_loss=None)),
        now=NOW + timedelta(hours=1),
    )
    assert (revised.kind, revised.created, revised.receipt.kind) == (KIND_REVISION, True, KIND_REVISION)
    assert (revised.tip.revises_tip_id, revised.tip.direction) == (original.tip.id, DIRECTION_BUY)
    session.refresh(original.tip)
    assert (original.tip.status, original.tip.target_price) == (STATUS_ACTIVE, Decimal("2600"))


def test_a_revision_with_nothing_to_revise_is_recorded_as_a_tip(session):
    result = record_intake(session, _receipt(), Intake(kind=KIND_REVISION, terms=_terms()), now=NOW)
    assert (result.kind, result.tip.revises_tip_id, result.receipt.kind) == (KIND_TIP, None, KIND_TIP)


def test_an_exit_attaches_to_the_active_tips_and_records_a_source_exit(session):
    older = _tip_intake(session, _receipt(key="e-1"), _reliance())
    newer = _tip_intake(session, _receipt(key="e-2"), _reliance(target=Decimal("2700")), now=NOW + timedelta(minutes=5))
    exit_result = record_intake(
        session, _receipt(key="e-3", text="Exit RELIANCE at 2550"),
        Intake(kind=KIND_EXIT, exit_symbol="RELIANCE", exit_price=Decimal("2550")), now=NOW + timedelta(hours=2),
    )
    assert (exit_result.kind, exit_result.tip.id, exit_result.matched) == (KIND_EXIT, newer.tip.id, True)
    exits = session.scalars(select(TipSourceExit).order_by(TipSourceExit.tip_id)).all()
    assert [(e.tip_id, e.stated_exit_price) for e in exits] == [(older.tip.id, Decimal("2550")), (newer.tip.id, Decimal("2550"))]
    assert {tip.status for tip in session.scalars(select(Tip))} == {STATUS_ACTIVE}


def test_an_exit_with_no_active_tip_stays_an_orphan_receipt(session):
    result = record_intake(
        session, _receipt(text="Exit TCS at 3900"), Intake(kind=KIND_EXIT, exit_symbol="TCS", exit_price=Decimal("3900")), now=NOW,
    )
    assert (result.kind, result.tip, result.receipt.tip_id, result.matched) == (KIND_EXIT, None, None, False)
    assert session.scalars(select(TipSourceExit)).all() == []


def test_an_unparsed_message_is_kept_as_a_receipt_only(session):
    result = record_intake(session, _receipt(text="Markets closed higher today"), Intake(kind=KIND_UNPARSED), now=NOW)
    assert (result.kind, result.tip, result.receipt.kind) == (KIND_UNPARSED, None, KIND_UNPARSED)
    assert session.query(Tip).count() == 0


def test_a_named_caller_fills_an_empty_caller_once(session):
    first = _tip_intake(session, _receipt(user="user-1", key="e-1"), _terms())
    assert first.tip.caller_id is None
    _tip_intake(session, _receipt(user="user-2", key="e-2"), _terms(), caller="Rahul Sharma")
    third = _tip_intake(session, _receipt(user="user-3", key="e-3"), _terms(), caller="Someone Else")
    session.refresh(first.tip)
    caller = session.get(Caller, first.tip.caller_id)
    assert (caller.name, caller.channel_id, caller.canonical_caller_id) == ("Rahul Sharma", first.tip.source_channel_id, caller.id)
    assert third.tip.caller_id == caller.id


def test_a_concurrent_first_sighting_attaches_instead_of_failing(session, monkeypatch):
    first = _tip_intake(session, _receipt(user="user-1", key="e-1"), _terms())
    real = tip_ledger.find_matchable
    calls = []

    def blind_once(*args, **kwargs):
        calls.append(1)
        return None if len(calls) == 1 else real(*args, **kwargs)

    monkeypatch.setattr(tip_ledger, "find_matchable", blind_once)
    second = _tip_intake(session, _receipt(user="user-2", key="e-2"), _terms())
    assert (second.tip.id, second.matched) == (first.tip.id, True)
    assert session.query(Tip).count() == 1
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `python -m pytest tests/test_tip_ledger.py -v`
Expected: collection ERROR `ModuleNotFoundError: No module named 'app.tip_ledger'`.

- [ ] **Step 3: Write the intake module**

Create `app/tip_ledger.py`:

```python
"""Central tip ledger intake (tip-ledger spec §5.2): resolve the channel, match or create the
canonical tip, record one customer's receipt. Tracking, outcomes and scorecards are later phases."""

from __future__ import annotations

import re
import uuid
from dataclasses import dataclass, field, replace
from datetime import datetime, timedelta, timezone
from decimal import Decimal

from sqlalchemy import func, select
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session

from . import tip_ledger_guard  # noqa: F401 -- registers the immutability guard with every ledger use
from .market_calendar import count_trading_days
from .market_data.quality import NSE_TIMEZONE
from .models import Caller, Channel, ChannelAlias, Stock, Tip, TipReceipt, TipSourceExit
from .tip_matching import MATCH_KEY_VERSION, StatedTerms, entry_mid, intake_rejection, match_key_v1
from .tip_vocabulary import (
    ALIAS_LABEL,
    ALIAS_PACKAGE,
    CHANNEL_BROKER_APP,
    CHANNEL_MARKSY,
    CHANNEL_NEWS_PORTAL,
    CHANNEL_SMS_SENDER,
    CHANNEL_TELEGRAM_CHANNEL,
    CHANNEL_WHATSAPP_GROUP,
    COMPARISON_STATUS_RECEIVED,
    DEFAULT_HORIZON_SESSIONS,
    ENTRY_BASIS_FIRST_SEEN_PRICE,
    ENTRY_BASIS_STATED,
    ENTRY_ENTERED,
    ENTRY_WAITING,
    HORIZON_BASIS_CHANNEL_DEFAULT,
    HORIZON_BASIS_STATED,
    KIND_EXIT,
    KIND_REVISION,
    KIND_TIP,
    KIND_UNPARSED,
    MEDIUM_APP_NOTIFICATION,
    MEDIUM_EMAIL,
    MEDIUM_MANUAL,
    MEDIUM_SMS,
    MEDIUM_TELEGRAM,
    MEDIUM_WHATSAPP,
    REJECTED_AT_INTAKE,
    STATUS_ACTIVE,
)

_CHANNEL_TYPE_FOR_MEDIUM = {
    MEDIUM_APP_NOTIFICATION: CHANNEL_BROKER_APP,
    MEDIUM_SMS: CHANNEL_SMS_SENDER,
    MEDIUM_WHATSAPP: CHANNEL_WHATSAPP_GROUP,
    MEDIUM_TELEGRAM: CHANNEL_TELEGRAM_CHANNEL,
    MEDIUM_EMAIL: CHANNEL_NEWS_PORTAL,
    MEDIUM_MANUAL: CHANNEL_NEWS_PORTAL,
}
_ALIAS_MAX = 128
_SOURCE_MAX = 64


class ReservedChannelError(ValueError):
    """A customer-supplied alias that resolves to Marksy's own channel."""


@dataclass(frozen=True)
class ReceiptInput:
    user_id: str
    device_event_key: str
    medium: str
    app_package: str | None
    channel_label: str | None
    text: str
    device_posted_at: datetime | None
    parser_version: str


@dataclass(frozen=True)
class TipExtras:
    """EPIC-803 columns the comparison, dashboard and outcome readers still use."""

    source: str | None = None
    client_name: str | None = None
    source_reference: str | None = None
    confidence: Decimal | None = None
    rationale: str | None = None
    raw_payload: dict | None = None
    tip_as_of: datetime | None = None


@dataclass(frozen=True)
class Intake:
    kind: str
    terms: StatedTerms | None = None
    exit_symbol: str | None = None
    exit_direction: str | None = None
    exit_price: Decimal | None = None
    caller_name: str | None = None
    extras: TipExtras = field(default_factory=TipExtras)


@dataclass(frozen=True)
class IntakeResult:
    receipt: TipReceipt
    tip: Tip | None
    kind: str
    matched: bool
    created: bool


def _aware(value: datetime) -> datetime:
    # SQLite drops tzinfo on a DateTime(timezone=True) round-trip.
    return value if value.tzinfo is not None else value.replace(tzinfo=timezone.utc)


def _bounded(value: str | None) -> str | None:
    return value.strip()[:_ALIAS_MAX] if value and value.strip() else None


def normalize_alias(value: str) -> str:
    return re.sub(r"\s+", " ", value.strip()).lower()[:_ALIAS_MAX]


def resolve_channel(session: Session, *, medium: str, app_package: str | None, channel_label: str | None) -> Channel:
    """App notifications by package then label; chats, SMS, email and pastes by label only."""
    package = normalize_alias(app_package) if app_package and app_package.strip() else None
    label = normalize_alias(channel_label) if channel_label and channel_label.strip() else None
    if medium == MEDIUM_APP_NOTIFICATION:
        keys = [(package, ALIAS_PACKAGE), (label, ALIAS_LABEL)]
    else:
        keys = [(label, ALIAS_LABEL) if label else (package, ALIAS_PACKAGE)]
    keys = [(alias, kind) for alias, kind in keys if alias]
    if not keys:
        raise ValueError("a receipt needs an app package or a channel label")
    for alias, _kind in keys:
        channel = session.scalar(
            select(Channel).join(ChannelAlias, ChannelAlias.channel_id == Channel.id).where(ChannelAlias.alias == alias)
        )
        if channel is not None:
            if channel.type == CHANNEL_MARKSY:
                raise ReservedChannelError(f"'{alias}' names Marksy's own channel")
            return channel
    name = ((channel_label or "").strip() or (app_package or "").strip())[:_ALIAS_MAX]
    channel = Channel(
        name=name,
        type=_CHANNEL_TYPE_FOR_MEDIUM[medium],
        default_horizon_sessions=DEFAULT_HORIZON_SESSIONS,
        capture_enabled=False,
    )
    session.add(channel)
    session.flush()
    channel.canonical_channel_id = channel.id
    session.add_all([ChannelAlias(channel_id=channel.id, alias=alias, kind=kind) for alias, kind in keys])
    session.flush()
    return channel


def resolve_caller(session: Session, channel_id: int, name: str) -> Caller:
    clean = re.sub(r"\s+", " ", name.strip())[:_ALIAS_MAX]
    caller = session.scalar(
        select(Caller).where(Caller.channel_id == channel_id, func.lower(Caller.name) == clean.lower())
    )
    if caller is None:
        caller = Caller(channel_id=channel_id, name=clean)
        session.add(caller)
        session.flush()
        caller.canonical_caller_id = caller.id
        session.flush()
    return caller


def _sessions_since(session: Session, first_seen_at: datetime, now: datetime) -> int:
    first = _aware(first_seen_at).astimezone(NSE_TIMEZONE).date()
    today = _aware(now).astimezone(NSE_TIMEZONE).date()
    if today <= first:
        return 0
    return count_trading_days(session, "NSE", first + timedelta(days=1), today + timedelta(days=1))


def find_matchable(session: Session, match_key: str, *, now: datetime) -> Tip | None:
    """The ACTIVE tip with this key, else a tip rejected at intake still inside its horizon (§5.2)."""
    candidates = session.scalars(
        select(Tip)
        .where(Tip.match_key == match_key, Tip.status.in_((STATUS_ACTIVE, *REJECTED_AT_INTAKE)))
        .order_by(Tip.first_seen_at.desc(), Tip.id.desc())
    ).all()
    for tip in candidates:
        if tip.status == STATUS_ACTIVE:
            return tip
    for tip in candidates:
        if _sessions_since(session, tip.first_seen_at, now) <= tip.horizon_sessions:
            return tip
    return None


def record_intake(session: Session, receipt_in: ReceiptInput, intake: Intake, *, now: datetime) -> IntakeResult:
    """Idempotent per (customer, device event); one retry covers a concurrent first sighting."""
    try:
        return _record(session, receipt_in, intake, now=now)
    except IntegrityError:
        session.rollback()
        return _record(session, receipt_in, intake, now=now)


def _record(session: Session, receipt_in: ReceiptInput, intake: Intake, *, now: datetime) -> IntakeResult:
    existing = session.scalar(
        select(TipReceipt).where(
            TipReceipt.user_id == receipt_in.user_id, TipReceipt.device_event_key == receipt_in.device_event_key
        )
    )
    if existing is not None:
        tip = session.get(Tip, existing.tip_id) if existing.tip_id is not None else None
        matched = tip is not None and (
            tip.first_seen_at is None or _aware(tip.first_seen_at) < _aware(existing.recorded_at)
        )
        return IntakeResult(existing, tip, existing.kind, matched=matched, created=False)
    channel = resolve_channel(
        session, medium=receipt_in.medium, app_package=receipt_in.app_package, channel_label=receipt_in.channel_label
    )
    if intake.kind == KIND_EXIT:
        return _record_exit(session, channel, receipt_in, intake, now=now)
    if intake.kind == KIND_UNPARSED or intake.terms is None:
        receipt = _new_receipt(session, receipt_in, tip=None, kind=KIND_UNPARSED, now=now)
        session.commit()
        return IntakeResult(receipt, None, KIND_UNPARSED, matched=False, created=False)
    if intake.kind == KIND_REVISION:
        originals = _active_tips(session, channel, intake.terms.symbol, intake.terms.direction)
        if originals:
            return _record_revision(session, channel, originals[0], receipt_in, intake, now=now)
    return _record_tip(session, channel, receipt_in, intake, now=now)


def _record_tip(session, channel: Channel, receipt_in: ReceiptInput, intake: Intake, *, now) -> IntakeResult:
    terms, extras = intake.terms, intake.extras
    tip = None
    if extras.source_reference:
        # EPIC-803 idempotency: a source-issued reference names its tip across re-pastes.
        tip = session.scalar(
            select(Tip).where(Tip.source == _source(extras, channel), Tip.source_reference == extras.source_reference)
        )
    if tip is None:
        tip = find_matchable(session, match_key_v1(channel.canonical_channel_id, terms), now=now)
    if tip is not None:
        return _attach(session, tip, receipt_in, intake, now=now)
    tip = _new_tip(session, channel, receipt_in, intake, terms, now=now)
    receipt = _new_receipt(session, receipt_in, tip=tip, kind=KIND_TIP, now=now)
    _fill_caller(session, tip, intake.caller_name)
    session.commit()
    return IntakeResult(receipt, tip, KIND_TIP, matched=False, created=True)


def _record_revision(session, channel: Channel, original: Tip, receipt_in: ReceiptInput, intake: Intake, *, now) -> IntakeResult:
    terms = intake.terms if intake.terms.direction else replace(intake.terms, direction=original.direction)
    existing = find_matchable(session, match_key_v1(channel.canonical_channel_id, terms), now=now)
    if existing is not None:
        return _attach(session, existing, receipt_in, intake, now=now)
    tip = _new_tip(session, channel, receipt_in, intake, terms, now=now, revises_tip_id=original.id)
    receipt = _new_receipt(session, receipt_in, tip=tip, kind=KIND_REVISION, now=now)
    _fill_caller(session, tip, intake.caller_name)
    session.commit()
    return IntakeResult(receipt, tip, KIND_REVISION, matched=False, created=True)


def _record_exit(session, channel: Channel, receipt_in: ReceiptInput, intake: Intake, *, now) -> IntakeResult:
    tips = _active_tips(session, channel, intake.exit_symbol, intake.exit_direction) if intake.exit_symbol else []
    linked = tips[0] if tips else None
    receipt = _new_receipt(session, receipt_in, tip=linked, kind=KIND_EXIT, now=now)
    for tip in tips:
        if session.scalar(select(TipSourceExit.id).where(TipSourceExit.tip_id == tip.id)) is None:
            session.add(TipSourceExit(tip_id=tip.id, receipt_id=receipt.id, stated_exit_price=intake.exit_price, exit_seen_at=now))
    session.commit()
    return IntakeResult(receipt, linked, KIND_EXIT, matched=linked is not None, created=False)


def _attach(session, tip: Tip, receipt_in: ReceiptInput, intake: Intake, *, now) -> IntakeResult:
    receipt = session.scalar(
        select(TipReceipt).where(
            TipReceipt.tip_id == tip.id,
            TipReceipt.user_id == receipt_in.user_id,
            TipReceipt.medium == receipt_in.medium,
            TipReceipt.kind == KIND_TIP,
        )
    )
    if receipt is None:
        receipt = _new_receipt(session, receipt_in, tip=tip, kind=KIND_TIP, now=now)
    _fill_caller(session, tip, intake.caller_name)
    session.commit()
    return IntakeResult(receipt, tip, KIND_TIP, matched=True, created=False)


def _active_tips(session, channel: Channel, symbol: str, direction: str | None) -> list[Tip]:
    same_channel = select(Channel.id).where(Channel.canonical_channel_id == channel.canonical_channel_id)
    statement = select(Tip).where(
        Tip.status == STATUS_ACTIVE, Tip.source_channel_id.in_(same_channel), Tip.symbol == symbol.strip().upper()
    )
    if direction is not None:
        statement = statement.where(Tip.direction == direction)
    return list(session.scalars(statement.order_by(Tip.first_seen_at.desc(), Tip.id.desc())))


def _source(extras: TipExtras, channel: Channel) -> str:
    return (extras.source or channel.name)[:_SOURCE_MAX]


def _new_tip(session, channel: Channel, receipt_in: ReceiptInput, intake: Intake, terms: StatedTerms, *, now,
             revises_tip_id: int | None = None) -> Tip:
    stock = session.scalar(select(Stock).where(Stock.symbol == terms.symbol))
    rejection = intake_rejection(terms, symbol_resolved=stock is not None)
    extras = intake.extras
    entry_stated = terms.entry_low is not None
    horizon_stated = terms.horizon_sessions is not None
    tip = Tip(
        public_id=str(uuid.uuid4()),
        source=_source(extras, channel),
        client_name=extras.client_name,
        source_reference=extras.source_reference,
        symbol=terms.symbol,
        stock_id=stock.id if stock is not None else None,
        direction=terms.direction,
        entry_price=entry_mid(terms),
        target_price=terms.target,
        stop_loss=terms.stop_loss,
        horizon_days=terms.horizon_sessions,
        confidence=extras.confidence,
        rationale=extras.rationale,
        raw_payload=extras.raw_payload,
        received_at=now,
        tip_as_of=extras.tip_as_of,
        comparison_status=COMPARISON_STATUS_RECEIVED,
        source_channel_id=channel.id,
        entry_low=terms.entry_low,
        entry_high=terms.entry_high,
        entry_basis=ENTRY_BASIS_STATED if entry_stated else ENTRY_BASIS_FIRST_SEEN_PRICE,
        horizon_sessions=terms.horizon_sessions if horizon_stated else channel.default_horizon_sessions,
        horizon_basis=HORIZON_BASIS_STATED if horizon_stated else HORIZON_BASIS_CHANNEL_DEFAULT,
        first_seen_at=now,
        revises_tip_id=revises_tip_id,
        match_key=match_key_v1(channel.canonical_channel_id, terms),
        match_key_version=MATCH_KEY_VERSION,
        parser_version=receipt_in.parser_version,
    )
    if rejection is None:
        tip.status = STATUS_ACTIVE
        tip.entry_status = ENTRY_WAITING if entry_stated else ENTRY_ENTERED
    else:
        tip.status, tip.reason = rejection
        tip.closed_at = now
    session.add(tip)
    session.flush()
    return tip


def _new_receipt(session, receipt_in: ReceiptInput, *, tip: Tip | None, kind: str, now) -> TipReceipt:
    receipt = TipReceipt(
        public_id=str(uuid.uuid4()),
        tip_id=tip.id if tip is not None else None,
        user_id=receipt_in.user_id,
        device_event_key=receipt_in.device_event_key,
        kind=kind,
        medium=receipt_in.medium,
        app_package=_bounded(receipt_in.app_package),
        channel_label=_bounded(receipt_in.channel_label),
        text=receipt_in.text,
        device_posted_at=receipt_in.device_posted_at,
        recorded_at=now,
        parser_version=receipt_in.parser_version,
    )
    session.add(receipt)
    session.flush()
    return receipt


def _fill_caller(session, tip: Tip, caller_name: str | None) -> None:
    if caller_name and tip.caller_id is None and tip.source_channel_id is not None:
        tip.caller_id = resolve_caller(session, tip.source_channel_id, caller_name).id
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `python -m pytest tests/test_tip_ledger.py tests/test_tip_ledger_guard.py -v`
Expected: all PASS.

- [ ] **Step 5: Commit**

```bash
git add app/tip_ledger.py tests/test_tip_ledger.py
git commit -m "$(cat <<'EOF'
Tip ledger: intake core (channels, matching, receipts, revisions, exits)

One canonical tip per match_key; one receipt per customer; revisions as
new tips; exits recorded for the tracker; orphan exits and unparsed text
kept as receipts; caller filled once (tip-ledger spec §5.2).

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
)"
```

### Task 12: Legacy intake through the ledger — `POST /tips` and the admin paste

**Files:**
- Modify: `api/services/tips.py`:
  - rewrite `submit_tip` and `ingest_text`;
  - remove `_existing_tip` and `_normalised_direction` (the ledger supersedes both);
  - add helpers.
- Modify: `api/routers/tips.py` (`post_tip`, `post_ingest_text`, add `_customer_id`)
- Modify: `api/schemas/tips.py` (`TipRequest` docstring)
- Modify: `tests/test_api_tips.py` (`test_two_tips_from_one_source_without_a_reference_are_both_kept` becomes the spec's dedupe rule)
- Modify: `tests/test_api_tips_ingest.py` (append one test)
- Test: `tests/test_api_tips_ledger.py`

**Interfaces:**
- Consumes: `record_intake`, `Intake`, `ReceiptInput`, `TipExtras`, `ReservedChannelError`, `IntakeResult` (Task 11); `stated_terms` (Task 7); `clean_tip_text` (Task 4); `LEGACY_UNKNOWN_USER_ID`, `MEDIUM_APP_NOTIFICATION`, `MEDIUM_MANUAL`, `KIND_TIP`, `PARSER_APP_PAYLOAD`, `PARSER_MARKSY_TIPS_REPORT` (Task 4).
- Produces:
  - `submit_tip(session, request, *, received_at, client_name=None, customer_id=LEGACY_UNKNOWN_USER_ID) -> tuple[TipAccepted, bool]`. Its `status` is still the comparison status.
  - `ingest_text(session, text, *, dry_run, received_at, client_name=None, customer_id=LEGACY_UNKNOWN_USER_ID) -> TipIngestResult`, where each prediction becomes a MANUAL receipt.
  - `_record_and_compare(session, receipt, intake, *, received_at) -> IntakeResult` and `_username(customer_id) -> str | None` (Task 13 uses both).
  - Router helper `_customer_id(principal) -> str`.

- [ ] **Step 1: Write the failing tests**

Create `tests/test_api_tips_ledger.py`:

```python
"""Legacy POST /api/v1/tips (the current Android payload) routed through the tip ledger:
an APP_NOTIFICATION receipt per customer, one tip per call, no raw title or body stored."""

from __future__ import annotations

import json
from decimal import Decimal

import pytest
from fastapi.testclient import TestClient
from sqlalchemy import create_engine, select
from sqlalchemy.orm import sessionmaker
from sqlalchemy.pool import StaticPool

from api.deps import get_db
from app.api_keys import mint_api_key
from app.db import Base
from app.main import app
from app.models import Channel, ChannelAlias, Stock, Tip, TipReceipt
from app.scopes import SCOPE_MARKSY
from app.tip_vocabulary import HORIZON_BASIS_STATED, KIND_TIP, LEGACY_UNKNOWN_USER_ID, MEDIUM_APP_NOTIFICATION, STATUS_ACTIVE
from tests._auth_helpers import login_body


@pytest.fixture
def session():
    engine = create_engine("sqlite:///:memory:", connect_args={"check_same_thread": False}, poolclass=StaticPool)
    Base.metadata.create_all(engine)
    db = sessionmaker(bind=engine)()
    db.add(Stock(symbol="RENUKA", exchange="NSE", company_name="Renuka Ltd", is_active=True))
    db.commit()
    try:
        yield db
    finally:
        db.close()


@pytest.fixture
def client(session):
    def override_get_db():
        yield session

    app.dependency_overrides[get_db] = override_get_db
    try:
        yield TestClient(app)
    finally:
        app.dependency_overrides.clear()


def _bearer(client, user_id="user-1"):
    token = client.post("/api/v1/auth/login", json=login_body(user_id)).json()["data"]["sessionToken"]
    return {"Authorization": f"Bearer {token}"}


def _app_payload(**overrides):
    """What gateway/MarksyTipPayload.kt sends today."""
    payload = {
        "symbol": "RENUKA", "source": "Upstox", "sourceReference": "device-a:evt-1", "direction": "BUY",
        "entryPrice": "23.62", "targetPrice": "26", "stopLoss": "22.25", "horizonDays": 5,
        "tipAsOf": "2026-09-28T04:45:00Z", "eventId": 41, "sourcePackage": "com.upstox.pro",
        "title": "Rahul @ StockTips", "body": "BUY RENUKA CMP 23.62 SL 22.25 TGT 26 call 9876543210",
        "category": "TRADING", "priority": 2, "notificationConfidence": 0.9,
        "occurredAt": "2026-09-28T04:45:00Z", "contractVersion": 1,
    }
    payload.update(overrides)
    return payload


def test_a_legacy_app_tip_becomes_a_receipt_without_the_raw_notification(client, session):
    response = client.post("/api/v1/tips", json=_app_payload(), headers=_bearer(client))

    assert response.status_code == 201
    tip = session.scalar(select(Tip).where(Tip.public_id == response.json()["data"]["tipId"]))
    receipt = session.scalar(select(TipReceipt).where(TipReceipt.tip_id == tip.id))
    assert (receipt.user_id, receipt.medium, receipt.device_event_key, receipt.app_package, receipt.channel_label, receipt.kind) == (
        "user-1", MEDIUM_APP_NOTIFICATION, "device-a:evt-1", "com.upstox.pro", "Upstox", KIND_TIP,
    )
    assert receipt.text == "BUY RENUKA CMP 23.62 SL 22.25 TGT 26 call [PHONE]"
    assert "title" not in tip.raw_payload and "body" not in tip.raw_payload and tip.raw_payload["eventId"] == 41
    stored = json.dumps(tip.raw_payload) + (tip.rationale or "") + receipt.text
    assert "Rahul" not in stored and "9876543210" not in stored
    aliases = set(session.scalars(select(ChannelAlias.alias).where(ChannelAlias.channel_id == tip.source_channel_id)))
    assert aliases == {"com.upstox.pro", "upstox"} and session.get(Channel, tip.source_channel_id).name == "Upstox"
    assert (tip.status, tip.entry_low, tip.entry_high, tip.horizon_sessions, tip.horizon_basis, tip.source_reference) == (
        STATUS_ACTIVE, Decimal("23.62"), Decimal("23.62"), 5, HORIZON_BASIS_STATED, None,
    )


def test_the_same_notification_on_two_phones_is_one_tip_with_two_receipts(client, session):
    first = client.post("/api/v1/tips", json=_app_payload(), headers=_bearer(client, "user-1"))
    second = client.post("/api/v1/tips", json=_app_payload(sourceReference="device-b:evt-9"), headers=_bearer(client, "user-2"))

    assert (first.status_code, second.status_code) == (201, 200)
    assert first.json()["data"]["tipId"] == second.json()["data"]["tipId"]
    assert session.query(Tip).count() == 1
    assert sorted(r.user_id for r in session.scalars(select(TipReceipt))) == ["user-1", "user-2"]


def test_an_api_key_receipt_belongs_to_its_bound_user_else_to_legacy_unknown(client, session):
    bound, _ = mint_api_key(session, client_name="Phone", scopes=[SCOPE_MARKSY], bound_user_id="user-3")
    unbound, _ = mint_api_key(session, client_name="AITradingAgents", scopes=[SCOPE_MARKSY])

    client.post("/api/v1/tips", json=_app_payload(), headers={"X-API-Key": bound})
    client.post("/api/v1/tips", json=_app_payload(sourceReference="agent-1"), headers={"X-API-Key": unbound})

    assert sorted(r.user_id for r in session.scalars(select(TipReceipt))) == [LEGACY_UNKNOWN_USER_ID, "user-3"]
```

In `tests/test_api_tips.py`, replace the whole `test_two_tips_from_one_source_without_a_reference_are_both_kept` function with:

```python
def test_two_identical_reference_less_calls_from_one_caller_are_one_tip(client, session, api_key_headers):
    """tip-ledger spec §2.1: an identical call is one tip, however many times it arrives."""
    _make_published_recommendation(session, symbol="AAA")
    first = _post(client, api_key_headers, symbol="AAA", source="ICICIDirect")
    second = _post(client, api_key_headers, symbol="AAA", source="ICICIDirect")

    assert (first.status_code, second.status_code) == (201, 200)
    assert first.json()["data"]["tipId"] == second.json()["data"]["tipId"]
    assert session.query(ExternalTip).count() == 1
```

In `tests/test_api_tips_ingest.py`, change `from app.models import ExternalTip` to `from app.models import ExternalTip, TipReceipt` and append:

```python
def test_a_pasted_report_is_recorded_as_manual_receipts(client, session, api_key_headers):
    client.post("/api/v1/tips/ingest-text", json={"text": _REPORT, "dryRun": False}, headers=api_key_headers)

    receipts = session.scalars(select(TipReceipt)).all()
    assert len(receipts) == 2
    assert {(r.medium, r.user_id, r.channel_label) for r in receipts} == {("MANUAL", "legacy-unknown", "AiTradingAgent")}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `python -m pytest tests/test_api_tips_ledger.py tests/test_api_tips.py::test_two_identical_reference_less_calls_from_one_caller_are_one_tip tests/test_api_tips_ingest.py::test_a_pasted_report_is_recorded_as_manual_receipts -v`
Expected: FAIL, because no `TipReceipt` rows are written yet:
- the ledger file fails with `AttributeError: 'NoneType' object has no attribute 'user_id'`;
- the dedupe test fails with `assert (201, 201) == (201, 200)`;
- the report test fails with `assert 0 == 2`.

- [ ] **Step 3: Route both legacy intake paths through the ledger**

In `api/services/tips.py`:
- Add `import hashlib` and `import json` beside `import uuid`.
- Change `from decimal import Decimal` to `from decimal import Decimal, InvalidOperation`.
- Change `from ..errors import NotFoundError` to `from ..errors import NotFoundError, ValidationError`.
- Add these imports with the other `app.` imports:

```python
from app.tip_ledger import Intake, IntakeResult, ReceiptInput, ReservedChannelError, TipExtras, record_intake
from app.tip_matching import stated_terms
from app.tip_text_cleaning import clean_tip_text
from app.tip_vocabulary import (
    KIND_TIP,
    LEGACY_UNKNOWN_USER_ID,
    MEDIUM_APP_NOTIFICATION,
    MEDIUM_MANUAL,
    PARSER_APP_PAYLOAD,
    PARSER_MARKSY_TIPS_REPORT,
)
```

Delete the functions `_existing_tip` and `_normalised_direction`. Replace the whole `submit_tip` function with:

```python
def _username(customer_id: str) -> str | None:
    return None if customer_id == LEGACY_UNKNOWN_USER_ID else customer_id


def _accepted(tip: ExternalTip) -> TipAccepted:
    return TipAccepted(tipId=tip.public_id, status=tip.comparison_status, receivedAt=_as_aware_utc(tip.received_at))


def _scrubbed_payload(request: TipRequest, username: str | None) -> dict:
    """The caller's body minus a notification's raw title and body (tip-ledger spec §2.11)."""
    payload = request.model_dump(mode="json")
    payload.pop("title", None)
    payload.pop("body", None)
    if payload.get("rationale"):
        payload["rationale"] = clean_tip_text(payload["rationale"], username=username)
    return payload


def _record_and_compare(session: Session, receipt: ReceiptInput, intake: Intake, *, received_at: datetime) -> IntakeResult:
    """Record the receipt; a NEW tip then gets Marksy's comparison, exactly as EPIC-803 intake did."""
    try:
        result = record_intake(session, receipt, intake, now=received_at)
    except ReservedChannelError as exc:
        raise ValidationError(str(exc)) from exc
    if result.created:
        try:
            compare_tip(session, result.tip, at=received_at)
        except Exception:
            # The tip is kept and marked FAILED. Losing somebody else's call because
            # Marksy could not form its own view of it inverts what this table is for.
            session.rollback()
            result.tip.comparison_status = TIP_STATUS_FAILED
            session.commit()
    if result.tip is not None:
        session.refresh(result.tip)
    return result


def submit_tip(
    session: Session,
    request: TipRequest,
    *,
    received_at: datetime,
    client_name: str | None = None,
    customer_id: str = LEGACY_UNKNOWN_USER_ID,
) -> tuple[TipAccepted, bool]:
    """`(response, created)`. The legacy app payload as one ledger receipt: the device key is the
    receipt's, the terms are the ones the phone already parsed. A repeat returns the stored tip
    un-compared -- a second comparison would silently replace the verdict the caller already has."""
    username = _username(customer_id)
    extra = request.model_extra or {}
    body = extra.get("body") if isinstance(extra.get("body"), str) else None
    package = extra.get("sourcePackage") if isinstance(extra.get("sourcePackage"), str) else None
    rationale = clean_tip_text(request.rationale, username=username) if request.rationale else None
    receipt = ReceiptInput(
        user_id=customer_id,
        device_event_key=request.sourceReference or f"legacy:{uuid.uuid4()}",
        medium=MEDIUM_APP_NOTIFICATION,
        app_package=package,
        channel_label=request.source.strip(),
        text=clean_tip_text(body or rationale or "", username=username),
        device_posted_at=request.tipAsOf,
        parser_version=PARSER_APP_PAYLOAD,
    )
    intake = Intake(
        kind=KIND_TIP,
        terms=stated_terms(
            symbol=request.symbol,
            direction=request.direction,
            entry_low=request.entryPrice,
            entry_high=request.entryPrice,
            target=request.targetPrice,
            stop_loss=request.stopLoss,
            horizon_sessions=request.horizonDays,
        ),
        extras=TipExtras(
            source=request.source.strip(),
            client_name=client_name,
            confidence=request.confidence,
            rationale=rationale,
            raw_payload=_scrubbed_payload(request, username),
            tip_as_of=request.tipAsOf,
        ),
    )
    result = _record_and_compare(session, receipt, intake, received_at=received_at)
    return _accepted(result.tip), result.created
```

Replace the whole `ingest_text` function with:

```python
def _decimal_or_none(value) -> Decimal | None:
    if value is None or value == "":
        return None
    try:
        return Decimal(str(value))
    except (InvalidOperation, ValueError):
        return None


def _report_event_key(parsed: ParsedTip, source: str) -> str:
    identity = f"{source}|{parsed.source_reference or ''}|{parsed.symbol}|{parsed.report_date or ''}"
    return "report:" + hashlib.sha256(identity.encode("utf-8")).hexdigest()


def _submit_report_tip(
    session: Session, parsed: ParsedTip, *, received_at: datetime, client_name: str | None, customer_id: str
) -> TipAccepted:
    """One report prediction as a MANUAL receipt; its prediction id stays the tip's source reference."""
    username = _username(customer_id)
    request = _to_tip_request(parsed)
    source = request.source.strip()
    payload = request.model_dump(mode="json")
    low = _decimal_or_none(parsed.extra.get("entryLow"))
    high = _decimal_or_none(parsed.extra.get("entryHigh"))
    receipt = ReceiptInput(
        user_id=customer_id,
        device_event_key=_report_event_key(parsed, source),
        medium=MEDIUM_MANUAL,
        app_package=None,
        channel_label=source,
        text=clean_tip_text(json.dumps(payload, sort_keys=True), username=username),
        device_posted_at=request.tipAsOf,
        parser_version=PARSER_MARKSY_TIPS_REPORT,
    )
    intake = Intake(
        kind=KIND_TIP,
        terms=stated_terms(
            symbol=request.symbol,
            direction=request.direction,
            entry_low=low if low is not None else request.entryPrice,
            entry_high=high if high is not None else request.entryPrice,
            target=request.targetPrice,
            stop_loss=request.stopLoss,
            horizon_sessions=request.horizonDays,
        ),
        extras=TipExtras(
            source=source,
            client_name=client_name,
            source_reference=request.sourceReference,
            confidence=request.confidence,
            rationale=request.rationale,
            raw_payload=payload,
            tip_as_of=request.tipAsOf,
        ),
    )
    return _accepted(_record_and_compare(session, receipt, intake, received_at=received_at).tip)


def ingest_text(
    session: Session,
    text: str,
    *,
    dry_run: bool,
    received_at: datetime,
    client_name: str | None = None,
    customer_id: str = LEGACY_UNKNOWN_USER_ID,
) -> TipIngestResult:
    """Parse a pasted report; on a real run record each prediction as the pasting customer's MANUAL
    receipt, so a pasted tip and a wire tip are indistinguishable downstream (comparison, dashboard,
    outcome tracking)."""
    parsed = parse_ingest_text(text)
    previews = [
        ParsedTipPreview(
            symbol=tip.symbol,
            sourceReference=tip.source_reference,
            direction=tip.direction,
            entryPrice=tip.entry_price,
            targetPrice=tip.target_price,
            stopLoss=tip.stop_loss,
            horizonDays=tip.horizon_days,
            confidence=tip.confidence,
        )
        for tip in parsed.tips
    ]
    imported: list[TipAccepted] = []
    if not dry_run:
        for tip in parsed.tips:
            imported.append(
                _submit_report_tip(session, tip, received_at=received_at, client_name=client_name, customer_id=customer_id)
            )
    return TipIngestResult(
        dryRun=dry_run,
        parsedCount=len(parsed.tips),
        warnings=parsed.warnings,
        tips=previews,
        imported=imported,
    )
```

In `api/schemas/tips.py`, in the `TipRequest` docstring, replace

```
    `extra="allow"` is deliberate. Unmodelled fields are kept and stored in
    `raw_payload` verbatim, so a source that starts sending something new does
    not lose it between the wire and the schema catching up.
```

with

```
    `extra="allow"` is deliberate. Unmodelled fields are kept and stored in
    `raw_payload`, so a source that starts sending something new does not lose
    it; a notification's raw `title`/`body` are dropped (tip-ledger spec §2.11).
```

In `api/routers/tips.py`, add `from app.tip_vocabulary import LEGACY_UNKNOWN_USER_ID` below `from app.scopes import SCOPE_MARKSY`, and add this helper below the `router = APIRouter(...)` block:

```python
def _customer_id(principal: Principal) -> str:
    # tip-ledger spec §5.1: the signed-in user, else the API key's bound user, else the legacy system user.
    return principal.subject or LEGACY_UNKNOWN_USER_ID
```

In `post_tip`, replace the docstring and the `submit_tip(...)` call with:

```python
    """201 for a new tip, 200 when the receipt joined a tip already stored -- a retry of the
    same device event, or the same call from another customer -- with the SAME `tipId`.

    Comparison runs synchronously, so the returned `status` is already the
    tip's post-comparison state.
    """
    accepted, created = submit_tip(
        db,
        request,
        received_at=datetime.now(timezone.utc),
        client_name=principal.client_name,
        customer_id=_customer_id(principal),
    )
```

In `post_ingest_text`, replace the `ingest_text(...)` call with:

```python
    result = ingest_text(
        db,
        request.text,
        dry_run=request.dryRun,
        received_at=datetime.now(timezone.utc),
        client_name=principal.client_name,
        customer_id=_customer_id(principal),
    )
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `python -m pytest tests/test_api_tips_ledger.py tests/test_api_tips.py tests/test_api_tips_ingest.py tests/test_external_tip_ingest.py -v`
Expected: all PASS.

- [ ] **Step 5: Commit**

```bash
git add api/services/tips.py api/routers/tips.py api/schemas/tips.py tests/test_api_tips_ledger.py tests/test_api_tips.py tests/test_api_tips_ingest.py
git commit -m "$(cat <<'EOF'
Route legacy POST /tips and the admin paste through the tip ledger

The current app payload becomes an APP_NOTIFICATION receipt for the
session user (or the key's bound user, else legacy-unknown) on the
channel of its sourcePackage, with no raw title/body stored; each pasted
report prediction becomes a MANUAL receipt.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
)"
```

### Task 13: `POST /tips/ingest-text` accepts a captured message

**Files:**
- Modify: `api/schemas/tips.py` (`TipIngestRequest`, `TipIngestResult`)
- Modify: `api/services/tips.py` (new `ingest_message`)
- Modify: `api/routers/tips.py` (`post_ingest_text` dispatch)
- Test: `tests/test_api_tips_ingest.py` (append)

**Interfaces:**
- Consumes: `_record_and_compare`, `_username` (Task 12); `parse_message`, `PARSER_VERSION` (Task 6); `channel_label_for`, `clean_tip_text` (Task 4); `stated_terms` (Task 7).
- Produces: `ingest_message(session, request: TipIngestRequest, *, received_at, customer_id) -> TipIngestResult`, which sets `receiptId`, `tipId`, `kind` and `matched`. A body without `deviceEventKey` keeps the Task 12 paste path.

- [ ] **Step 1: Write the failing tests**

In `tests/test_api_tips_ingest.py`, add `from decimal import Decimal` below `from __future__ import annotations`, change `from app.models import ExternalTip, TipReceipt` to `from app.models import Channel, ExternalTip, TipReceipt`, add `from tests._auth_helpers import login_body` after the `app.scopes` import, and append:

```python
def _bearer(client, user_id="user-1"):
    token = client.post("/api/v1/auth/login", json=login_body(user_id)).json()["data"]["sessionToken"]
    return {"Authorization": f"Bearer {token}"}


def _message(**overrides):
    """The tip-ledger spec §5.1 payload a phone sends for one captured message."""
    body = {
        "deviceEventKey": "evt-1", "medium": "WHATSAPP", "appPackage": "com.whatsapp",
        "channelLabel": "Rahul @ StockTips", "text": "BUY RENUKA CMP 23.62 SL 22.25 TGT 26. Call 9876543210",
        "devicePostedAt": "2026-09-28T04:45:00Z",
    }
    body.update(overrides)
    return body


def test_a_captured_message_is_cleaned_parsed_and_recorded(client, session):
    res = client.post("/api/v1/tips/ingest-text", json=_message(), headers=_bearer(client))

    data = res.json()["data"]
    assert (res.status_code, data["kind"], data["matched"], data["parsedCount"]) == (200, "TIP", False, 1)
    receipt = session.scalar(select(TipReceipt).where(TipReceipt.public_id == data["receiptId"]))
    assert (receipt.channel_label, receipt.medium, receipt.user_id) == ("StockTips", "WHATSAPP", "user-1")
    assert receipt.text == "BUY RENUKA CMP 23.62 SL 22.25 TGT 26. Call [PHONE]"
    tip = session.scalar(select(ExternalTip).where(ExternalTip.public_id == data["tipId"]))
    assert (tip.symbol, tip.direction, tip.target_price, session.get(Channel, tip.source_channel_id).name) == (
        "RENUKA", "BUY", Decimal("26"), "StockTips",
    )


def test_the_same_message_from_a_second_customer_matches_the_first(client, session):
    first = client.post("/api/v1/tips/ingest-text", json=_message(), headers=_bearer(client, "user-1")).json()["data"]
    second = client.post(
        "/api/v1/tips/ingest-text", json=_message(deviceEventKey="evt-77"), headers=_bearer(client, "user-2")
    ).json()["data"]

    assert (second["tipId"], second["matched"]) == (first["tipId"], True)
    assert len(session.scalars(select(ExternalTip)).all()) == 1


def test_an_exit_with_nothing_open_is_an_orphan_receipt(client):
    data = client.post("/api/v1/tips/ingest-text", json=_message(text="Exit TCS at 3900"), headers=_bearer(client)).json()["data"]

    assert (data["kind"], data["tipId"], data["matched"]) == ("EXIT", None, False)


def test_a_captured_message_without_a_medium_is_rejected(client):
    res = client.post("/api/v1/tips/ingest-text", json=_message(medium=None), headers=_bearer(client))

    assert res.status_code == 422
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `python -m pytest tests/test_api_tips_ingest.py -v`
Expected: the 4 new tests FAIL. The unknown fields are ignored, so the paste path answers with "No marksy-tips block found":
- the message tests fail with `KeyError: 'kind'`;
- the missing-medium test gets 200.

The three earlier tests pass.

- [ ] **Step 3: Extend the schemas**

In `api/schemas/tips.py`, add `from typing import Literal` below `from decimal import Decimal`, and change `from pydantic import BaseModel, ConfigDict, Field` to `from pydantic import BaseModel, ConfigDict, Field, model_validator`. Replace the `TipIngestRequest` class with:

```python
class TipIngestRequest(BaseModel):
    """A pasted scheduled-task report (`text`, `dryRun`), or -- with `deviceEventKey` -- one message
    a phone captured (tip-ledger spec §5.1). A dry run previews a report without writing anything."""

    text: str = Field(min_length=1)
    dryRun: bool = False
    deviceEventKey: str | None = Field(default=None, min_length=1, max_length=128)
    medium: Literal["APP_NOTIFICATION", "SMS", "WHATSAPP", "TELEGRAM", "EMAIL", "MANUAL"] | None = None
    appPackage: str | None = Field(default=None, max_length=128)
    channelLabel: str | None = Field(default=None, max_length=128)
    devicePostedAt: datetime | None = None

    @model_validator(mode="after")
    def _a_message_names_its_medium_and_channel(self) -> TipIngestRequest:
        if self.deviceEventKey is not None and (self.medium is None or not (self.appPackage or self.channelLabel)):
            raise ValueError("a captured message needs medium and appPackage or channelLabel")
        return self
```

In `TipIngestResult`, below `    imported: list[TipAccepted]`, add:

```python
    # tip-ledger spec §9: set for a captured message (`deviceEventKey`), null for a pasted report.
    receiptId: str | None = None
    tipId: str | None = None
    kind: str | None = None
    matched: bool | None = None
```

- [ ] **Step 4: Implement the captured-message path**

In `api/services/tips.py`:
- Add these imports:

```python
from app.tip_text_cleaning import channel_label_for
from app.trade_call_parser import PARSER_VERSION, parse_message
```

- Extend the `from app.tip_vocabulary import (...)` list with `KIND_EXIT, KIND_REVISION, KIND_UNPARSED`.
- Add `TipIngestRequest,` to the `from ..schemas.tips import (...)` list.
- Append after `ingest_text`:

```python
def ingest_message(
    session: Session, request: TipIngestRequest, *, received_at: datetime, customer_id: str
) -> TipIngestResult:
    """tip-ledger spec §5.1/§5.2: one captured message, cleaned again, parsed, recorded as this customer's receipt."""
    username = _username(customer_id)
    text = clean_tip_text(request.text, username=username)
    parsed = parse_message(text)
    terms = None
    if parsed.kind in (KIND_TIP, KIND_REVISION):
        terms = stated_terms(
            symbol=parsed.symbol,
            direction=parsed.side,
            entry_low=parsed.entry_low,
            entry_high=parsed.entry_high,
            target=parsed.target,
            stop_loss=parsed.stop_loss,
            horizon_sessions=parsed.horizon_sessions,
        )
    is_exit = parsed.kind == KIND_EXIT
    receipt = ReceiptInput(
        user_id=customer_id,
        device_event_key=request.deviceEventKey,
        medium=request.medium,
        app_package=request.appPackage,
        channel_label=channel_label_for(request.medium, request.channelLabel, username=username),
        text=text,
        device_posted_at=request.devicePostedAt,
        parser_version=PARSER_VERSION,
    )
    intake = Intake(
        kind=parsed.kind,
        terms=terms,
        exit_symbol=parsed.symbol if is_exit else None,
        exit_direction=parsed.side if is_exit else None,
        exit_price=parsed.exit_price,
        caller_name=parsed.caller,
    )
    result = _record_and_compare(session, receipt, intake, received_at=received_at)
    return TipIngestResult(
        dryRun=False,
        parsedCount=0 if result.kind == KIND_UNPARSED else 1,
        warnings=[],
        tips=[],
        imported=[],
        receiptId=result.receipt.public_id,
        tipId=result.tip.public_id if result.tip is not None else None,
        kind=result.kind,
        matched=result.matched,
    )
```

In `api/routers/tips.py`, add `ingest_message,` to the `from ..services.tips import ...` list, and replace the body of `post_ingest_text` (docstring included) with:

```python
    """A pasted scheduled-task report (MANUAL receipts, idempotent per prediction id), or -- with
    `deviceEventKey` -- one captured message recorded as the caller's receipt (tip-ledger spec §5.1)."""
    now = datetime.now(timezone.utc)
    if request.deviceEventKey is not None:
        return success(ingest_message(db, request, received_at=now, customer_id=_customer_id(principal)))
    result = ingest_text(
        db,
        request.text,
        dry_run=request.dryRun,
        received_at=now,
        client_name=principal.client_name,
        customer_id=_customer_id(principal),
    )
    return success(result)
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `python -m pytest tests/test_api_tips_ingest.py tests/test_external_tip_ingest.py tests/test_api_tips_ledger.py -v`
Expected: all PASS.

- [ ] **Step 6: Commit**

```bash
git add api/schemas/tips.py api/services/tips.py api/routers/tips.py tests/test_api_tips_ingest.py
git commit -m "$(cat <<'EOF'
POST /tips/ingest-text accepts a captured message

A body with deviceEventKey is cleaned, parsed (TIP/REVISION/EXIT/
UNPARSED) and recorded as the caller's receipt, answering receiptId,
tipId, kind and matched (tip-ledger spec §5.1, §9).

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
)"
```

### Task 14: `GET /channels/capture-list`

**Files:**
- Create: `api/schemas/channels.py`, `api/services/channels.py`, `api/routers/channels.py`
- Modify: `api/app.py` (import and `include_router` next to `tips`)
- Test: `tests/test_api_channels.py`

**Interfaces:**
- Consumes: `Channel`, `ChannelAlias` (Task 8), `ALIAS_PACKAGE` (Task 4).
- Produces: `GET /api/v1/channels/capture-list`, gated by the marksy scope. It returns `{"packages": [{"package", "channelId", "channelName", "channelType"}]}` for the PACKAGE aliases of `capture_enabled` channels, sorted by package.

- [ ] **Step 1: Write the failing test**

Create `tests/test_api_channels.py`:

```python
"""GET /api/v1/channels/capture-list (tip-ledger spec §5.1): the app packages a phone may capture.
Messaging apps stay off it -- those are governed by the customer's own allow-list."""

from __future__ import annotations

import pytest
from fastapi.testclient import TestClient
from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker
from sqlalchemy.pool import StaticPool

from api.deps import get_db
from app.db import Base
from app.main import app
from app.models import Channel, ChannelAlias
from app.tip_vocabulary import ALIAS_LABEL, ALIAS_PACKAGE, CHANNEL_BROKER_APP, CHANNEL_WHATSAPP_GROUP


@pytest.fixture
def session():
    engine = create_engine("sqlite:///:memory:", connect_args={"check_same_thread": False}, poolclass=StaticPool)
    Base.metadata.create_all(engine)
    db = sessionmaker(bind=engine)()
    try:
        yield db
    finally:
        db.close()


@pytest.fixture
def client(session):
    def override_get_db():
        yield session

    app.dependency_overrides[get_db] = override_get_db
    try:
        yield TestClient(app)
    finally:
        app.dependency_overrides.clear()


def _channel(session, name, channel_type, capture, *aliases):
    channel = Channel(name=name, type=channel_type, default_horizon_sessions=20, capture_enabled=capture)
    session.add(channel)
    session.flush()
    channel.canonical_channel_id = channel.id
    session.add_all([ChannelAlias(channel_id=channel.id, alias=alias, kind=kind) for alias, kind in aliases])
    session.commit()
    return channel


def test_the_capture_list_names_the_enabled_app_packages_only(client, session):
    upstox = _channel(
        session, "Upstox", CHANNEL_BROKER_APP, True,
        ("in.upstox.app", ALIAS_PACKAGE), ("com.upstox.pro", ALIAS_PACKAGE), ("upstox", ALIAS_LABEL),
    )
    _channel(session, "WhatsApp", CHANNEL_WHATSAPP_GROUP, False, ("com.whatsapp", ALIAS_PACKAGE))
    _channel(session, "New Broker", CHANNEL_BROKER_APP, False, ("com.new.broker", ALIAS_PACKAGE))

    res = client.get("/api/v1/channels/capture-list")

    assert res.status_code == 200
    assert res.json()["data"] == {"packages": [
        {"package": "com.upstox.pro", "channelId": upstox.id, "channelName": "Upstox", "channelType": "BROKER_APP"},
        {"package": "in.upstox.app", "channelId": upstox.id, "channelName": "Upstox", "channelType": "BROKER_APP"},
    ]}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `python -m pytest tests/test_api_channels.py -v`
Expected: FAIL with `assert 404 == 200`.

- [ ] **Step 3: Implement the endpoint**

Create `api/schemas/channels.py`:

```python
"""DTOs for /api/v1/channels (tip-ledger spec §5.1)."""

from __future__ import annotations

from pydantic import BaseModel


class CaptureListEntry(BaseModel):
    package: str
    channelId: int
    channelName: str
    channelType: str


class CaptureList(BaseModel):
    packages: list[CaptureListEntry]
```

Create `api/services/channels.py`:

```python
"""The capture list a phone reads (tip-ledger spec §5.1): package aliases of channels an admin enabled."""

from __future__ import annotations

from sqlalchemy import select
from sqlalchemy.orm import Session

from app.models import Channel, ChannelAlias
from app.tip_vocabulary import ALIAS_PACKAGE

from ..schemas.channels import CaptureList, CaptureListEntry


def get_capture_list(session: Session) -> CaptureList:
    rows = session.execute(
        select(ChannelAlias.alias, Channel)
        .join(Channel, Channel.id == ChannelAlias.channel_id)
        .where(ChannelAlias.kind == ALIAS_PACKAGE, Channel.capture_enabled.is_(True))
        .order_by(ChannelAlias.alias)
    ).all()
    return CaptureList(
        packages=[
            CaptureListEntry(package=alias, channelId=channel.id, channelName=channel.name, channelType=channel.type)
            for alias, channel in rows
        ]
    )
```

Create `api/routers/channels.py`:

```python
"""GET /api/v1/channels/capture-list (tip-ledger spec §5.1): the app packages a phone may capture."""

from __future__ import annotations

from fastapi import APIRouter, Depends
from sqlalchemy.orm import Session

from app.scopes import SCOPE_MARKSY

from ..deps import get_db, require_scope
from ..envelope import success
from ..schemas.channels import CaptureList
from ..schemas.common import SuccessEnvelope
from ..services.channels import get_capture_list

router = APIRouter(prefix="/channels", tags=["channels"], dependencies=[Depends(require_scope(SCOPE_MARKSY))])


@router.get("/capture-list", response_model=SuccessEnvelope[CaptureList])
def get_channel_capture_list(db: Session = Depends(get_db)):
    return success(get_capture_list(db))
```

In `api/app.py`, below the line `from .routers import tips`, add:

```python
# tip-ledger spec §5.1: the phone's capture list. Separate line, per the EPIC-309 note above.
from .routers import channels
```

and below `api_router.include_router(tips.router)` add:

```python
api_router.include_router(channels.router)
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `python -m pytest tests/test_api_channels.py tests/test_security_authorization.py -v`
Expected: all PASS (the route is scope-gated, so the inventory test does not list it as open).

- [ ] **Step 5: Commit**

```bash
git add api/schemas/channels.py api/services/channels.py api/routers/channels.py api/app.py tests/test_api_channels.py
git commit -m "$(cat <<'EOF'
GET /channels/capture-list for the phone

Package aliases of capture-enabled channels only; messaging apps stay
under the customer's allow-list (tip-ledger spec §5.1).

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
)"
```

### Task 15: Phase 1 regression set, PR, hand-off

**Files:** none changed.

**Interfaces:**
- Consumes: Tasks 4–14.
- Produces: an open PR on `feat/tip-ledger-intake`.

- [ ] **Step 1: Run the tip/prediction regression set**

```bash
python -c "import pathlib, tempfile; pathlib.Path(tempfile.gettempdir(), 'marksy-pytest-default.db').unlink(missing_ok=True)"
python -m pytest tests/test_api_tips.py tests/test_api_tips_ingest.py tests/test_api_tips_ledger.py tests/test_api_channels.py tests/test_external_tip_ingest.py tests/test_external_tip_outcome.py tests/test_external_tip_scoring.py tests/test_tip_text_cleaning.py tests/test_call_horizon.py tests/test_trade_call_parser.py tests/test_tip_matching.py tests/test_tip_ledger.py tests/test_tip_ledger_guard.py tests/test_tip_ledger_migration.py tests/test_prediction_outcome_monitor.py tests/test_api_predictions_active.py tests/test_api_dashboard.py tests/test_read_only_principal.py tests/test_security_authorization.py tests/test_postgres_identifier_limits.py tests/test_alembic_single_head.py -v
```

Expected: all PASS. For any failure, run that file on `main` in `C:\AIAgent\marksy-api`. If it also fails there, it is pre-existing: list it in the PR body. Otherwise fix it before continuing.

- [ ] **Step 2: Push and open the PR**

```bash
git push -u origin feat/tip-ledger-intake
gh pr create --base main --head feat/tip-ledger-intake --title "Central tip ledger: intake (phase 1)" --body "$(cat <<'EOF'
## Summary
- `external_tips` → `tips` (ORM `Tip`, alias `ExternalTip`). The EPIC-803 comparison state moves to `comparison_status`, and the new lifecycle `status` stays NULL on legacy rows until the §12 backfill.
- New tables: `channels`, `channel_aliases`, `callers`, `tip_receipts`, `tip_source_exits`. Partial unique index on `match_key` WHERE `status = 'ACTIVE'`. An ORM guard rejects term changes and deletes.
- Seeds: SourceRegistry channels and aliases; the Marksy channel with its "Prediction engine" and "Rating engine" callers; the disabled `legacy-unknown` user.
- **One-way PII scrub of existing rows.** A chat title becomes its group, or `[CONTACT]` for a 1:1 chat. Phones, emails, PAN codes and 8+ digit runs are masked in `body`, `rationale` and the other free-text values. Downgrade cannot restore the raw text.
- Server-side cleaning, ported TradeCallParser/CallHorizon, TIP/REVISION/EXIT/UNPARSED detection, match_key v1, intake rejections (UNSCORABLE/DATA_UNRESOLVED), revisions, orphan exits, caller filled once.
- Legacy `POST /tips` now records an APP_NOTIFICATION receipt and no longer stores raw `title`/`body`. `POST /tips/ingest-text` accepts the §5.1 captured-message payload; admin pastes become MANUAL receipts. New `GET /channels/capture-list`.
- Deferred to phase 2: `tip_daily_progress`, the tracker, and closing tips on source exits.

## Validation
Regression set from the plan's Task 15 (21 files), run locally on SQLite; the migration is also exercised by `tests/test_tip_ledger_migration.py`.

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```

- [ ] **Step 3: Hand off to the user and stop**

Report the PR URL. Ask the user to merge it and then deploy with `ssh marksy@103.178.160.233 'bash ~/deployment/server/vps-deploy.sh'`. After the deploy, the user can spot-check `GET /api/v1/channels/capture-list` and one real app tip (its `POST /tips` still returns `tipId` and `status`). Do not merge and do not ssh.
