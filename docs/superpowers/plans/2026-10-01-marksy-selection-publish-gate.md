# Marksy Selection Publish Gate (SPG-001) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Marksy publishes a call only when its model has proven, out of sample, net excess return over the eligible all-stocks universe. Everything else is stored as `SHADOW` and never reaches a public surface.

**Architecture:**
- The new package `app/selection_gate/` holds pure statistics and reduction code, a point-in-time universe/dataset builder, walk-forward and held-out validation, an append-only decision store, a fail-closed `authorize_publication`, and a publication-capability constant.
- `record_recommendation`, the only place a Prediction is constructed, asks `decide_publication`, and only `PUBLISHED` rows reach the ledger or any public reader.
- A monthly Kubernetes CronJob writes decisions. A weekday CronJob writes SEL-001 shadow scores.

**Tech Stack:** Python 3.12, FastAPI, SQLAlchemy 2 (`Mapped`), Alembic, pandas, numpy, xgboost 3.2 (pickle artefacts via `app/challenger_artefact.py`), pytest on SQLite, k3s CronJobs.

**Spec:** `docs/superpowers/specs/2026-10-01-marksy-selection-publish-gate-design.md` in marksy-os (approved 2026-10-01 with the §20 decisions). All code changes are in `C:\AIAgent\marksy-api` on branch `feat/spg-001-selection-gate`, cut from `main` at `dbe7596`.

## Global Constraints

- Official bars only for the dataset: `app/market_data/bar_finality.py::final_bars_only()`. The provisional source `upstox-v3-quote-ohlc` is never read offline. Live (§14.1) accepts the bar the scan used.
- Thresholds are defaults in `app/settings.py`, all hashed into each decision (spec §17):
  - gate pairs `BASELINE-001 × {1, 3, 5, 7}`, `SEL-001 × {3, 5}`; top-K `10`, cost `0.0030`, min net excess `0.0050`
  - WF trades `2000`, held-out trades `100`, WF folds `24`
  - bootstrap `10000` draws, seed `42`
  - unresolved share `0.02`, session stocks `100`, history bars `60`
  - liquidity floor `10000000` (₹1 crore median 20-session traded value), applied before the benchmark universe is formed
  - benchmark stocks `100`, plausible return `[-0.60, 1.50]`
  - first WF test month `"2018-01"`, min fit rows `100000`, validity `45` days, embargo `DEFAULT_EMBARGO_DAYS` (2)
- SEL-001 hyperparameters are fixed (spec §9.1): `n_estimators=400, max_depth=4, learning_rate=0.05, subsample=0.8, colsample_bytree=0.8, min_child_weight=100, reg_lambda=1.0, objective="reg:squarederror", tree_method="hist", random_state=42, n_jobs=2`. No tuning.
- Fail closed: any missing or invalid decision, and any exception, yields `NO_EDGE` / `SHADOW`.
- A gate decision is not publication. `PUBLICATION_CAPABILITY` is a code constant; SEL-001 is `SHADOW_ONLY`.
- New tables are append-only. No historical rewrite, no target/stop learning, no app UI change, no new dependencies (no pyarrow, no joblib).
- One-line comments citing SPG-001, matching the surrounding style. TDD for every task: failing test, confirm red, implement, confirm green, commit.
- Tests use per-file fixtures `create_engine("sqlite:///:memory:")` + `Base.metadata.create_all` (repo pattern). Run from `C:\AIAgent\marksy-api` with `python -m pytest <path> -q`.
- Do not push, merge or deploy. Task 16 ends with the review report and a local draft-PR description.
- Publication is earned per (model, horizon) pair listed in `selection_gate_pairs`: BASELINE-001 at 1, 3, 5 and 7 sessions, and SEL-001 at 3 and 5. No horizon is inherently publishable. Each pair publishes only with its own valid `PUBLISH` decision and every authorisation check passing; until then its calls are `SHADOW`. SEL-001 and unknown models are always `SHADOW_ONLY`. A pair outside the list is always `HORIZON_NOT_GATED`. Zero public calls is an acceptable outcome.

## Review Focus

Each item below is covered by a test in the task that owns the code.

1. **A ratio action whose ex-date is the entry session `D+1`.** The entry open is already post-action, so the exit close gets no extra factor (Task 10, `test_split_on_entry_session_is_not_double_adjusted`).
2. **A session with fewer than `selection_min_benchmark_stocks` resolved stocks.** Its candidates become `UNRESOLVED` and are counted, never silently dropped or benchmarked (Task 12, `test_candidates_in_unbenchmarkable_sessions_are_unresolved_not_dropped`).
3. **`valid_until` read back naive from SQLite and aware from Postgres, with `at == valid_until`.** Still valid; one microsecond later it is `DECISION_STALE` (Task 5, `test_valid_until_boundary_is_inclusive_across_naive_and_aware`).
4. **A decision whose stored `config_snapshot` lacks a key that current code snapshots.** It returns `DECISION_MISMATCH`, not an exception (Task 5, `test_snapshot_from_older_code_is_a_mismatch`).
5. **A (model, horizon) outside `selection_gate_pairs`**, such as SEL-001 at 7 sessions or an unknown model. It is always `SHADOW / HORIZON_NOT_GATED`, even with a `PUBLISH` row (Task 5 `test_a_pair_outside_the_gate_is_never_authorised`, Task 7 `test_a_pair_outside_the_gate_stays_shadow_even_with_a_publish_row`).

## File Structure

New, in `app/selection_gate/`:
- `__init__.py`: package marker.
- `constants.py`: versions, states, reasons, dispositions. Imports nothing from `app`.
- `config.py`: `SEL001_PARAMS`, `config_snapshot()`, `config_sha256()`, `current_config_sha256()`.
- `capability.py`: `PUBLICATION_CAPABILITY`, `publication_capability()`.
- `statistics.py`: pure §11 (block bootstrap, `evaluate_stage`, `failed_stage`, `decide`).
- `reduction.py`: pure §8 (`Candidate`, `top_k_candidates`, `reduce_candidates`).
- `features.py`: `bars_frame()` and `per_stock_features()` (FV-002 point-in-time, split into corporate-action epochs).
- `universe.py`: SEU-001 `exclusion_reasons()` and `stock_session_table()`.
- `dataset.py`: SEL-DS-001 `build_selection_dataset()` (sessions, labels, benchmarks, cross-sectional features, hash).
- `validation.py`: `plan_windows()`, `fit_row_mask()`, `protected_rows()`.
- `sel001.py`: `fit_sel001()`, `score_sel001()`.
- `holdout.py`: `holdout_label()`, `register_holdout()`, `consume_holdout()`, `previously_observed()`.
- `records.py`: append-only listeners, `write_benchmarks()`, `write_decision()`, `write_trades()`.
- `evaluation.py`: `run_stage()` and `StageRun`.
- `runner.py`: `run_selection_gate()`.
- `report.py`: `render_report()`.
- `authorization.py`: `latest_decision()`, `authorize_publication()`.
- `publication.py`: `PublicationDecision`, `stock_in_gate_universe()`, `decide_publication()`.
- `visibility.py`: `published_only()`.
- `shadow.py`: `run_selection_shadow()`.

New elsewhere:
- scripts: `scripts/run_selection_gate.py`, `scripts/run_selection_shadow.py`, `scripts/load_selection_snapshot.py`
- migration: `migrations/versions/0189_selection_publish_gate.py`
- manifests: `deploy/k8s/base/selection-gate-cronjob.yaml`, `deploy/k8s/base/selection-shadow-cronjob.yaml`
- test helpers: `tests/_selection_gate_factories.py`
- tests: `tests/test_selection_gate_*.py`

Modified:
- `app/settings.py`: SPG-001 settings.
- `app/models.py`: five tables and three `Prediction` columns.
- `app/recommendations.py`: the gate, and `IMMUTABLE_FIELDS`.
- `app/marksy_tips.py`: refuses SHADOW.
- `app/tip_backfill.py`, `app/opportunity_ranking.py`, `app/cross_sectional_ranking.py`, `app/continuous_discovery.py`, `app/lifecycle.py`, `app/rating_job.py`, `app/trade_decision_log.py`, `app/recommendation_tracking_view.py`: published only.
- `app/challenger_training.py`, `app/purged_embargo_validation.py`: ignore `SPG-HOLDOUT-001` rows.
- `app/schedule_orchestration.py`: two operations.
- `api/services/*`: `published_only`.
- `api/routers/recommendation_detail.py`: 404 for SHADOW.
- `api/routers/predictions_active.py` and `api/schemas/predictions_active.py`: `meta.publishGate`.
- `deploy/k8s/base/kustomization.yaml`.
- `tests/conftest.py`: `publish_gate_open`.
- Every test that constructs `Prediction(` directly gets `publication_state="PUBLISHED"`.

## Reader audit (spec §14.2)

Dispositions for every reader of `Prediction` found in `app/` and `api/`.

**Gate (published only):**
- API services, all via `published_only`:
  - `api/services/predictions_active.py` (list and by-id), `recommendations.py` (list and search), `opportunities.py`
  - `market.py::_marksy_market_view`
  - `instruments.py` (through `app/recommendation_tracking_view.py`)
  - `tracking.py`, `discovery.py`, `discovery_intelligence.py`, `recommendations_snooze.py`, `recommendation_detail.py`, `reproducibility.py`, `attribution.py`, `feedback.py`
- Detail routes: every `/recommendations/{recommendationId}/…` route in `api/routers/recommendation_detail.py`, including provenance, lineage, explanation, manifest and changes, returns 404 for a SHADOW call via one router dependency.
- `app/lifecycle.py::published_occurrences` covers alerts, delivery and `portfolio_awareness`.
- `app/opportunity_ranking.py::rank_positive_opportunities`: a SHADOW prediction is never `included`.
- `app/cross_sectional_ranking.py::_qualified_prediction_ids_for_scan`.
- `app/continuous_discovery.py`: skips `publish_recommendation` for SHADOW.
- `app/marksy_tips.py::register_prediction` raises for SHADOW; `app/tip_backfill.py` selects only published rows.
- `app/rating_job.py`: the stock-id collection at line 135 and `_open_call` at line 161.
- `app/trade_decision_log.py:170`.

**Internal (no change):** these read predictions for learning, calibration or monitoring and are not public.
- Learning and training: `adaptive_calibration`, `calibration*`, `candidate_model_evaluation`, `challenger_comparison`, `champion_challenger_shadow`, `clean_population`, `feedback_learning_signals`, `historical_learning_dataset`, `learning_cycle_*`, `out_of_sample_validation`, `purged_embargo_validation`, `walk_forward_dataset`, `rating_job.calibration_samples`.
- Outcomes and monitoring: `outcome*`, `performance`, `prediction_*` monitors, `self_*`, `trust_dashboard`.
- Other analysis: `regime_*`, `segment_*`, `score_*`, `stock_behavior_learning`, `user_preference*`, and the remaining files in the survey list.
- Lifecycle-transition writers: `recommendation_revalidation`, `prediction_reassessment`, `event_*`, `recommendation_retirement`. They change state only; user-facing effects flow through the gated readers above.
- `api/services/learning_readiness.py` and `api/services/ops_retrigger.py` are operator-scoped; the source guard in Task 9 allowlists them by name.

---

### Task 1: Constants, configuration snapshot, capability, settings

**Files:**
- Create: `app/selection_gate/__init__.py`, `app/selection_gate/constants.py`, `app/selection_gate/config.py`, `app/selection_gate/capability.py`
- Modify: `app/settings.py` (add fields inside `class Settings`, before the closing of the class)
- Test: `tests/test_selection_gate_config.py`

**Interfaces:**
- Produces: all constants below; `SEL001_PARAMS: dict`; `config_snapshot(source=settings) -> dict`; `config_sha256(snapshot: dict) -> str`; `current_config_sha256() -> str`; `gate_pairs() -> tuple[tuple[str, int], ...]`; `gate_horizons(model_version=None) -> tuple[int, ...]`; `PUBLICATION_CAPABILITY: dict[str, str]`; `publication_capability(model_version: str) -> str`.

- [ ] **Step 1: Branch**

```bash
cd /c/AIAgent/marksy-api && git checkout main && git pull --ff-only && git checkout -b feat/spg-001-selection-gate
```

- [ ] **Step 2: Write the failing test** `tests/test_selection_gate_config.py`

```python
from app.baseline_signal import MODEL_VERSION
from app.selection_gate.capability import publication_capability
from app.selection_gate.config import config_sha256, config_snapshot, current_config_sha256, gate_horizons, gate_pairs
from app.selection_gate.constants import (
    BASELINE_MODEL_VERSION,
    CAPABILITY_PUBLISHABLE,
    CAPABILITY_SHADOW_ONLY,
    SEL001_MODEL_VERSION,
)
from app.settings import settings


def test_snapshot_holds_every_threshold_and_model_parameter():
    snapshot = config_snapshot()
    assert snapshot["selection_gate_pairs"] == [
        ["BASELINE-001", 1], ["BASELINE-001", 3], ["BASELINE-001", 5], ["BASELINE-001", 7], ["SEL-001", 3], ["SEL-001", 5],
    ]
    assert snapshot["selection_top_k"] == 10
    assert snapshot["selection_round_trip_cost"] == 0.0030
    assert snapshot["selection_min_net_excess"] == 0.0050
    assert snapshot["selection_min_wf_trades"] == 2000
    assert snapshot["selection_min_holdout_trades"] == 100
    assert snapshot["selection_min_wf_folds"] == 24
    assert snapshot["selection_bootstrap_draws"] == 10000
    assert snapshot["selection_bootstrap_seed"] == 42
    assert snapshot["selection_min_median_traded_value_20d"] == 10000000
    assert snapshot["selection_decision_validity_days"] == 45
    assert snapshot["embargo_sessions"] == 2
    assert snapshot["sel001_params"]["n_estimators"] == 400
    assert snapshot["gate_rule_version"] == "SPG-001"
    assert snapshot["universe_rule_version"] == "SEU-001"
    assert snapshot["dataset_version"] == "SEL-DS-001"


def test_hash_is_stable_and_moves_with_any_threshold(monkeypatch):
    first = config_sha256(config_snapshot())
    assert first == config_sha256(config_snapshot())
    monkeypatch.setattr(settings, "selection_min_net_excess", 0.0040)
    assert config_sha256(config_snapshot()) != first


def test_gate_pairs_are_hashed_and_expose_horizons_per_model(monkeypatch):
    first = current_config_sha256()
    assert gate_horizons("BASELINE-001") == (1, 3, 5, 7) and gate_horizons("SEL-001") == (3, 5)
    monkeypatch.setattr(settings, "selection_gate_pairs", (("BASELINE-001", 3),))
    assert current_config_sha256() != first
    assert gate_pairs() == (("BASELINE-001", 3),)


def test_capability_is_separate_from_any_gate_decision():
    assert BASELINE_MODEL_VERSION == MODEL_VERSION
    assert publication_capability(BASELINE_MODEL_VERSION) == CAPABILITY_PUBLISHABLE
    assert publication_capability(SEL001_MODEL_VERSION) == CAPABILITY_SHADOW_ONLY
    assert publication_capability("SEL-002") == CAPABILITY_SHADOW_ONLY
```

- [ ] **Step 3: Run it; expect FAIL** (`ModuleNotFoundError: app.selection_gate`)

Run: `python -m pytest tests/test_selection_gate_config.py -q`

- [ ] **Step 4: Implement**

`app/selection_gate/__init__.py`:

```python
"""SPG-001 selection publish gate (marksy-os docs/superpowers/specs/2026-10-01-marksy-selection-publish-gate-design.md)."""
```

`app/selection_gate/constants.py`:

```python
"""SPG-001 vocabulary. Dependency-free so any module (including the tip ledger) can import it without cycles."""
from __future__ import annotations

GATE_RULE_VERSION = "SPG-001"
UNIVERSE_RULE_VERSION = "SEU-001"
DATASET_VERSION = "SEL-DS-001"
HOLDOUT_REGISTRY_VERSION = "SPG-HOLDOUT-001"
BASELINE_MODEL_VERSION = "BASELINE-001"  # == app.baseline_signal.MODEL_VERSION (asserted in tests)
BASELINE_FEATURE_VERSION = "FV-001"
SEL001_MODEL_VERSION = "SEL-001"
SEL001_MODEL_NAME = "selection-excess-return"
SEL001_FEATURE_VERSION = "FV-002"
GATED_MODEL_VERSIONS = (BASELINE_MODEL_VERSION, SEL001_MODEL_VERSION)

DECISION_PUBLISH = "PUBLISH"
DECISION_NO_EDGE = "NO_EDGE"
STAGE_WALK_FORWARD = "WALK_FORWARD"
STAGE_HELD_OUT = "HELD_OUT"
RESULT_PASS = "PASS"
RESULT_FAIL = "FAIL"

PUBLICATION_PUBLISHED = "PUBLISHED"
PUBLICATION_SHADOW = "SHADOW"
CAPABILITY_PUBLISHABLE = "PUBLISHABLE"
CAPABILITY_SHADOW_ONLY = "SHADOW_ONLY"

REASON_UNRESOLVED_TRADES = "DATA_INTEGRITY_UNRESOLVED_TRADES"
REASON_INSUFFICIENT_EVIDENCE = "INSUFFICIENT_OUT_OF_SAMPLE_EVIDENCE"
REASON_EDGE_BELOW_THRESHOLD = "EDGE_BELOW_THRESHOLD"
REASON_CONFIDENCE_NOT_MET = "CONFIDENCE_THRESHOLD_NOT_MET"
REASON_HOLDOUT_ALREADY_CONSUMED = "HOLDOUT_ALREADY_CONSUMED"
REASON_HOLDOUT_PREVIOUSLY_OBSERVED = "HOLDOUT_PREVIOUSLY_OBSERVED"
REASON_EVALUATION_FAILED = "EVALUATION_FAILED"
REASON_NO_DECISION = "NO_DECISION_ON_RECORD"
REASON_DECISION_MISMATCH = "DECISION_MISMATCH"
REASON_DECISION_STALE = "DECISION_STALE"
REASON_DECISION_INVALID = "DECISION_INVALID"
REASON_AUTHORIZATION_ERROR = "AUTHORIZATION_ERROR"
REASON_HORIZON_NOT_GATED = "HORIZON_NOT_GATED"
REASON_SHADOW_ONLY_CAPABILITY = "SHADOW_ONLY_CAPABILITY"
REASON_OUTSIDE_GATE_UNIVERSE = "OUTSIDE_GATE_UNIVERSE"
RANKING_EXCLUSION_SHADOW = "PUBLICATION_SHADOW"

DISPOSITION_ACCEPTED = "ACCEPTED"
DISPOSITION_DUPLICATE = "DUPLICATE"
DISPOSITION_OVERLAP_SUPPRESSED = "OVERLAP_SUPPRESSED"
DISPOSITION_UNRESOLVED = "UNRESOLVED"

LABEL_RESOLVED = "RESOLVED"
LABEL_NOT_YET_RESOLVABLE = "NOT_YET_RESOLVABLE"
LABEL_ENTRY_BAR_MISSING = "ENTRY_BAR_MISSING"
LABEL_EXIT_BAR_MISSING = "EXIT_BAR_MISSING"
LABEL_INVALID_PRICE = "INVALID_PRICE"
LABEL_SUSPECT_RETURN = "SUSPECT_RETURN"

EXCLUDED_NOT_EQUITY = "NOT_EQUITY"
EXCLUDED_RIGHTS_ENTITLEMENT = "RIGHTS_ENTITLEMENT"
EXCLUDED_TOO_FEW_BARS = "TOO_FEW_BARS"
EXCLUDED_INVALID_MARKET_DATA = "INVALID_MARKET_DATA"
EXCLUDED_DATA_CONTRACT_VIOLATION = "DATA_CONTRACT_VIOLATION"
EXCLUDED_BELOW_LIQUIDITY_FLOOR = "BELOW_LIQUIDITY_FLOOR"

FOLD_TESTED = "TESTED"
FOLD_TOO_SMALL = "FOLD_TOO_SMALL"


def display_reason(reason: str | None) -> str | None:
    return None if reason is None else f"{DECISION_NO_EDGE} — {reason}"
```

`app/selection_gate/config.py`:

```python
"""SPG-001 configuration snapshot (spec §17): every threshold, hashed onto each decision."""
from __future__ import annotations

import hashlib
import json

from app.features.technical import EXTENDED_FEATURE_COLUMNS
from app.purged_embargo_validation import DEFAULT_EMBARGO_DAYS
from app.settings import settings

from .constants import DATASET_VERSION, GATE_RULE_VERSION, UNIVERSE_RULE_VERSION

SEL001_PARAMS = {
    "n_estimators": 400,
    "max_depth": 4,
    "learning_rate": 0.05,
    "subsample": 0.8,
    "colsample_bytree": 0.8,
    "min_child_weight": 100,
    "reg_lambda": 1.0,
    "objective": "reg:squarederror",
    "tree_method": "hist",
    "random_state": 42,
    "n_jobs": 2,
}

SNAPSHOT_SETTINGS = (
    "selection_gate_pairs",
    "selection_top_k",
    "selection_round_trip_cost",
    "selection_min_net_excess",
    "selection_min_wf_trades",
    "selection_min_holdout_trades",
    "selection_min_wf_folds",
    "selection_bootstrap_draws",
    "selection_bootstrap_seed",
    "selection_max_unresolved_share",
    "selection_min_session_stocks",
    "selection_min_history_bars",
    "selection_min_median_traded_value_20d",
    "selection_min_benchmark_stocks",
    "selection_min_plausible_return",
    "selection_max_plausible_return",
    "selection_wf_first_test_month",
    "selection_min_fit_rows",
    "selection_decision_validity_days",
)


def _plain(value):
    if isinstance(value, (tuple, list)):
        return [_plain(item) for item in value]
    return value


def config_snapshot(source=settings) -> dict:
    snapshot = {name: _plain(getattr(source, name)) for name in SNAPSHOT_SETTINGS}
    snapshot.update(
        gate_rule_version=GATE_RULE_VERSION,
        universe_rule_version=UNIVERSE_RULE_VERSION,
        dataset_version=DATASET_VERSION,
        embargo_sessions=DEFAULT_EMBARGO_DAYS,
        sel001_params=dict(SEL001_PARAMS),
        sel001_feature_columns=list(EXTENDED_FEATURE_COLUMNS),
    )
    return snapshot


def config_sha256(snapshot: dict) -> str:
    canonical = json.dumps(snapshot, sort_keys=True, separators=(",", ":"))
    return hashlib.sha256(canonical.encode("utf-8")).hexdigest()


def current_config_sha256() -> str:
    return config_sha256(config_snapshot())


def gate_pairs(source=settings) -> tuple[tuple[str, int], ...]:
    return tuple((str(model), int(horizon)) for model, horizon in source.selection_gate_pairs)


def gate_horizons(model_version: str | None = None) -> tuple[int, ...]:
    return tuple(sorted({h for m, h in gate_pairs() if model_version is None or m == model_version}))
```

`app/selection_gate/capability.py`:

```python
"""SPG-001 §13.1: whether a model may publish at all, independent of any gate decision."""
from __future__ import annotations

from .constants import BASELINE_MODEL_VERSION, CAPABILITY_PUBLISHABLE, CAPABILITY_SHADOW_ONLY, SEL001_MODEL_VERSION

# A reviewed code change, never a setting: SEL-001 stays shadow until trade geometry is validated and approved.
PUBLICATION_CAPABILITY = {
    BASELINE_MODEL_VERSION: CAPABILITY_PUBLISHABLE,
    SEL001_MODEL_VERSION: CAPABILITY_SHADOW_ONLY,
}


def publication_capability(model_version: str) -> str:
    return PUBLICATION_CAPABILITY.get(model_version, CAPABILITY_SHADOW_ONLY)
```

In `app/settings.py`, inside `class Settings`, add:

```python
    # SPG-001 selection publish gate (spec §17); all are hashed into every decision.
    # Publication is earned per listed (model, horizon); anything unlisted is HORIZON_NOT_GATED.
    selection_gate_pairs: tuple[tuple[str, int], ...] = (
        ("BASELINE-001", 1), ("BASELINE-001", 3), ("BASELINE-001", 5), ("BASELINE-001", 7),
        ("SEL-001", 3), ("SEL-001", 5),
    )
    selection_top_k: int = 10
    selection_round_trip_cost: float = 0.0030
    selection_min_net_excess: float = 0.0050
    selection_min_wf_trades: int = 2000
    selection_min_holdout_trades: int = 100
    selection_min_wf_folds: int = 24
    selection_bootstrap_draws: int = 10000
    selection_bootstrap_seed: int = 42
    selection_max_unresolved_share: float = 0.02
    selection_min_session_stocks: int = 100
    selection_min_history_bars: int = 60
    selection_min_median_traded_value_20d: float = 10000000.0  # ₹1 crore
    selection_min_benchmark_stocks: int = 100
    selection_min_plausible_return: float = -0.60
    selection_max_plausible_return: float = 1.50
    selection_wf_first_test_month: str = "2018-01"
    selection_min_fit_rows: int = 100000
    selection_decision_validity_days: int = 45
```

- [ ] **Step 5: Run; expect PASS.** Run: `python -m pytest tests/test_selection_gate_config.py -q`

- [ ] **Step 6: Commit**

```bash
git add app/selection_gate app/settings.py tests/test_selection_gate_config.py
git commit -m "SPG-001: gate constants, config snapshot and publication capability"
```

---

### Task 2: Schema, migration 0189, append-only records, Prediction publication state

**Files:**
- Modify: `app/models.py`. Add five classes after `HoldoutUsageRecord` (about line 2095), and three columns to `Prediction` after `status` (about line 234). Add any missing names (`Date`, `JSON`, `Boolean`, `Index`, `date`) to the existing imports.
- Create: `migrations/versions/0189_selection_publish_gate.py`, `app/selection_gate/records.py` (listeners only in this task)
- Modify: `app/recommendations.py:22` (`IMMUTABLE_FIELDS`)
- Modify: every test file constructing `Prediction(` (mechanical, Step 6)
- Test: `tests/test_selection_gate_schema.py`, `tests/test_selection_gate_migration.py`

**Interfaces:**
- Produces:
  - ORM classes `SelectionGateDecision`, `SelectionGateTrade`, `SelectionBenchmarkSession`, `SelectionShadowScore`, `SelectionHoldoutUsage`
  - `Prediction.publication_state: str` (NOT NULL, no default), `Prediction.publish_gate_decision_id: int | None`, `Prediction.publish_gate_reason: str | None`
  - `records.SelectionGateImmutableError`

- [ ] **Step 1: Write the failing tests**

`tests/test_selection_gate_schema.py`:

```python
from datetime import date, datetime, timezone
from decimal import Decimal

import pytest
from sqlalchemy import create_engine
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import sessionmaker

from app.db import Base
from app.models import Prediction, SelectionBenchmarkSession, Stock
from app.recommendations import IMMUTABLE_FIELDS, RecommendationImmutableError
from app.selection_gate.records import SelectionGateImmutableError
from tests._selection_gate_factories import seed_decision


@pytest.fixture
def session():
    engine = create_engine("sqlite:///:memory:")
    Base.metadata.create_all(engine)
    with sessionmaker(bind=engine)() as db:
        yield db


def _prediction(session, **overrides) -> Prediction:
    stock = Stock(symbol="AAA")
    session.add(stock)
    session.flush()
    values = dict(
        stock_id=stock.id, as_of_timestamp=datetime(2026, 9, 29, 18, 30, tzinfo=timezone.utc),
        entry_price=Decimal("100"), horizon_days=5, target_return=Decimal("0.05"), stop_return=Decimal("-0.03"),
        predicted_probability=Decimal("0.7"), confidence=Decimal("0.8"), model_version="BASELINE-001",
        feature_version="FV-001", consensus_contract_version="C", horizon_selection_version="H",
        scoring_contract_version="S", opportunity_score=Decimal("70"),
    )
    values.update(overrides)
    return Prediction(**values)


def test_insert_without_publication_state_fails(session):
    session.add(_prediction(session))
    with pytest.raises(IntegrityError):
        session.flush()


def test_publication_fields_are_immutable(session):
    assert {"publication_state", "publish_gate_decision_id", "publish_gate_reason"} <= set(IMMUTABLE_FIELDS)
    prediction = _prediction(session, publication_state="SHADOW", publish_gate_reason="NO_DECISION_ON_RECORD")
    session.add(prediction)
    session.flush()
    prediction.publication_state = "PUBLISHED"
    with pytest.raises(RecommendationImmutableError):
        session.flush()


def test_decisions_are_append_only(session):
    decision = seed_decision(session)
    decision.primary_reason = "EDGE_BELOW_THRESHOLD"
    with pytest.raises(SelectionGateImmutableError):
        session.flush()
    session.rollback()


def test_benchmark_rows_are_append_only(session):
    row = SelectionBenchmarkSession(
        dataset_version="SEL-DS-001", dataset_sha256="d" * 64, session_date=date(2026, 9, 1), horizon_sessions=5,
        eligible_count=10, labelled_count=10, unresolved={}, excluded={}, benchmark_return=Decimal("0.01"),
        benchmarkable=True,
    )
    session.add(row)
    session.flush()
    session.delete(row)
    with pytest.raises(SelectionGateImmutableError):
        session.flush()
```

`tests/test_selection_gate_migration.py` (follow `tests/test_user_follows_migration.py` for engine setup; `run_revision` from `tests/_migration_helpers.py`):

```python
import sqlalchemy as sa
import pytest

from tests._migration_helpers import run_revision

REVISION = "0189_selection_publish_gate"


def _predecessor_schema(connection):
    metadata = sa.MetaData()
    sa.Table("stocks", metadata, sa.Column("id", sa.Integer, primary_key=True))
    sa.Table("model_artefacts", metadata, sa.Column("id", sa.Integer, primary_key=True))
    sa.Table("holdout_window_registry", metadata, sa.Column("id", sa.Integer, primary_key=True))
    sa.Table(
        "predictions", metadata,
        sa.Column("id", sa.Integer, primary_key=True),
        sa.Column("stock_id", sa.Integer, sa.ForeignKey("stocks.id")),
        sa.Column("status", sa.String(32)),
    )
    metadata.create_all(connection)
    connection.execute(sa.text("INSERT INTO stocks (id) VALUES (1)"))
    connection.execute(sa.text("INSERT INTO predictions (id, stock_id, status) VALUES (7, 1, 'OPEN')"))


def test_upgrade_marks_existing_calls_published_and_removes_the_default():
    engine = sa.create_engine("sqlite:///:memory:")
    with engine.begin() as connection:
        _predecessor_schema(connection)
        run_revision(connection, REVISION)
        assert connection.execute(sa.text("SELECT publication_state FROM predictions WHERE id = 7")).scalar() == "PUBLISHED"
        tables = set(sa.inspect(connection).get_table_names())
        assert {"selection_gate_decisions", "selection_gate_trades", "selection_benchmark_sessions",
                "selection_shadow_scores", "selection_holdout_usages"} <= tables
        with pytest.raises(sa.exc.IntegrityError):
            connection.execute(sa.text("INSERT INTO predictions (id, stock_id, status) VALUES (8, 1, 'OPEN')"))


def test_downgrade_removes_everything_it_added():
    engine = sa.create_engine("sqlite:///:memory:")
    with engine.begin() as connection:
        _predecessor_schema(connection)
        run_revision(connection, REVISION)
        run_revision(connection, REVISION, step="downgrade")
        inspector = sa.inspect(connection)
        assert "selection_gate_decisions" not in inspector.get_table_names()
        assert "publication_state" not in {c["name"] for c in inspector.get_columns("predictions")}
```

Create `tests/_selection_gate_factories.py`. It is shared by later tasks.

```python
"""SPG-001 test factories: decisions, stocks with official bars, sessions."""
from __future__ import annotations

from datetime import date, datetime, timedelta, timezone
from decimal import Decimal
from uuid import uuid4

from app.models import MarketPrice, SelectionGateDecision, Stock
from app.selection_gate.config import config_sha256, config_snapshot
from app.selection_gate.constants import (
    BASELINE_MODEL_VERSION, DATASET_VERSION, DECISION_NO_EDGE, DECISION_PUBLISH, GATE_RULE_VERSION,
    REASON_CONFIDENCE_NOT_MET, RESULT_FAIL, RESULT_PASS, SEL001_MODEL_VERSION, STAGE_WALK_FORWARD,
    UNIVERSE_RULE_VERSION,
)
from app.walk_forward_dataset import session_cutoff

AT = datetime(2026, 10, 1, 6, 0, tzinfo=timezone.utc)
OFFICIAL_SOURCE = "test-official"


def weekday_sessions(end: date, count: int) -> list[date]:
    days: list[date] = []
    day = end
    while len(days) < count:
        if day.weekday() < 5:
            days.append(day)
        day -= timedelta(days=1)
    return sorted(days)


def seed_stock_with_bars(session, *, symbol, sessions, close=100.0, volume=1_000_000, instrument_key=None,
                         sector="TECH", drift=0.001) -> Stock:
    """Official bars stamped at each session's cutoff anchor. Copy any other NOT NULL MarketPrice
    columns from tests/test_walk_forward_dataset.py's bar helper if the flush complains."""
    stock = Stock(symbol=symbol, instrument_key=instrument_key, sector=sector)
    session.add(stock)
    session.flush()
    for index, day in enumerate(sessions):
        price = Decimal(str(round(close * (1 + drift * (index % 7)), 4)))
        session.add(MarketPrice(
            stock_id=stock.id, timestamp=session_cutoff(day), open=price, high=price * Decimal("1.01"),
            low=price * Decimal("0.99"), close=price, volume=volume, source=OFFICIAL_SOURCE,
        ))
    session.flush()
    return stock


def seed_decision(session, *, model_version=BASELINE_MODEL_VERSION, horizon=5, decision=DECISION_PUBLISH,
                  decided_at=AT, **overrides) -> SelectionGateDecision:
    snapshot = config_snapshot()
    values = dict(
        decision_uuid=str(uuid4()), model_version=model_version, horizon_sessions=horizon,
        gate_rule_version=GATE_RULE_VERSION, universe_rule_version=UNIVERSE_RULE_VERSION,
        dataset_version=DATASET_VERSION, dataset_sha256="d" * 64,
        feature_version="FV-002" if model_version == SEL001_MODEL_VERSION else "FV-001",
        artefact_sha256="a" * 64 if model_version == SEL001_MODEL_VERSION else None,
        config_snapshot=snapshot, config_sha256=config_sha256(snapshot), code_version="test",
        holdout_month="2026-08", holdout_label=f"SPG-001:{model_version}:h{horizon}:2026-08",
        wf_folds_tested=30, wf_folds_skipped=0, wf_trades=2400, wf_min_trades=2000,
        wf_mean_excess=Decimal("0.01"), wf_ci_low=Decimal("0.004"), wf_ci_high=Decimal("0.016"),
        wf_result=RESULT_PASS, wf_reasons=[],
        ho_trades=120, ho_min_trades=100, ho_mean_excess=Decimal("0.012"), ho_ci_low=Decimal("0.002"),
        ho_ci_high=Decimal("0.02"), ho_result=RESULT_PASS, ho_reasons=[],
        decision=decision, primary_stage=None, primary_reason=None, reasons=[],
        decided_at=decided_at, valid_until=decided_at + timedelta(days=45),
    )
    if decision == DECISION_NO_EDGE:
        values.update(
            wf_result=RESULT_FAIL, wf_reasons=[REASON_CONFIDENCE_NOT_MET], primary_stage=STAGE_WALK_FORWARD,
            primary_reason=REASON_CONFIDENCE_NOT_MET,
            reasons=[{"stage": STAGE_WALK_FORWARD, "reason": REASON_CONFIDENCE_NOT_MET}],
        )
    values.update(overrides)
    row = SelectionGateDecision(**values)
    session.add(row)
    session.flush()
    return row
```

- [ ] **Step 2: Run; expect FAIL** (ImportError on the new models)

Run: `python -m pytest tests/test_selection_gate_schema.py tests/test_selection_gate_migration.py -q`

- [ ] **Step 3: Add the ORM models to `app/models.py`**

Add after `HoldoutUsageRecord`:

```python
class SelectionGateDecision(Base):
    """SPG-001 §12.1: one append-only gate decision per (model version, horizon) run."""

    __tablename__ = "selection_gate_decisions"
    __table_args__ = (
        UniqueConstraint("decision_uuid", name="uq_selection_gate_decision_uuid"),
        Index("ix_selection_gate_decisions_pair", "model_version", "horizon_sessions", "id"),
    )

    id: Mapped[int] = mapped_column(BigInteger().with_variant(Integer, "sqlite"), primary_key=True)
    decision_uuid: Mapped[str] = mapped_column(String(36))
    model_version: Mapped[str] = mapped_column(String(64))
    horizon_sessions: Mapped[int] = mapped_column(Integer)
    gate_rule_version: Mapped[str] = mapped_column(String(32))
    universe_rule_version: Mapped[str] = mapped_column(String(32))
    dataset_version: Mapped[str] = mapped_column(String(32))
    dataset_sha256: Mapped[str | None] = mapped_column(String(64))
    feature_version: Mapped[str] = mapped_column(String(64))
    artefact_id: Mapped[int | None] = mapped_column(ForeignKey("model_artefacts.id"))
    artefact_sha256: Mapped[str | None] = mapped_column(String(64))
    config_snapshot: Mapped[dict] = mapped_column(JSON)
    config_sha256: Mapped[str] = mapped_column(String(64))
    code_version: Mapped[str] = mapped_column(String(64))
    dataset_first_session: Mapped[date | None] = mapped_column(Date)
    dataset_last_session: Mapped[date | None] = mapped_column(Date)
    wf_first_test_session: Mapped[date | None] = mapped_column(Date)
    wf_last_test_session: Mapped[date | None] = mapped_column(Date)
    wf_folds_tested: Mapped[int | None] = mapped_column(Integer)
    wf_folds_skipped: Mapped[int | None] = mapped_column(Integer)
    wf_folds: Mapped[list | None] = mapped_column(JSON)
    holdout_month: Mapped[str | None] = mapped_column(String(7))
    holdout_first_session: Mapped[date | None] = mapped_column(Date)
    holdout_last_session: Mapped[date | None] = mapped_column(Date)
    holdout_label: Mapped[str | None] = mapped_column(String(128))
    holdout_registry_id: Mapped[int | None] = mapped_column(ForeignKey("holdout_window_registry.id"))

    wf_sessions: Mapped[int | None] = mapped_column(Integer)
    wf_eligible_stock_sessions: Mapped[int | None] = mapped_column(Integer)
    wf_mean_eligible_per_session: Mapped[Decimal | None] = mapped_column(Numeric(12, 4))
    wf_labelled_stock_sessions: Mapped[int | None] = mapped_column(Integer)
    wf_unresolved_stock_sessions: Mapped[int | None] = mapped_column(Integer)
    wf_excluded_by_reason: Mapped[dict | None] = mapped_column(JSON)
    wf_candidates: Mapped[int | None] = mapped_column(Integer)
    wf_duplicates_removed: Mapped[int | None] = mapped_column(Integer)
    wf_overlap_suppressed: Mapped[int | None] = mapped_column(Integer)
    wf_unresolved_trades: Mapped[int | None] = mapped_column(Integer)
    wf_trades: Mapped[int | None] = mapped_column(Integer)
    wf_min_trades: Mapped[int | None] = mapped_column(Integer)
    wf_mean_benchmark: Mapped[Decimal | None] = mapped_column(Numeric(12, 8))
    wf_mean_gross: Mapped[Decimal | None] = mapped_column(Numeric(12, 8))
    wf_cost: Mapped[Decimal | None] = mapped_column(Numeric(12, 8))
    wf_mean_net: Mapped[Decimal | None] = mapped_column(Numeric(12, 8))
    wf_mean_excess: Mapped[Decimal | None] = mapped_column(Numeric(12, 8))
    wf_ci_low: Mapped[Decimal | None] = mapped_column(Numeric(12, 8))
    wf_ci_high: Mapped[Decimal | None] = mapped_column(Numeric(12, 8))
    wf_bootstrap_sessions: Mapped[int | None] = mapped_column(Integer)
    wf_result: Mapped[str | None] = mapped_column(String(8))
    wf_reasons: Mapped[list | None] = mapped_column(JSON)

    ho_sessions: Mapped[int | None] = mapped_column(Integer)
    ho_eligible_stock_sessions: Mapped[int | None] = mapped_column(Integer)
    ho_mean_eligible_per_session: Mapped[Decimal | None] = mapped_column(Numeric(12, 4))
    ho_labelled_stock_sessions: Mapped[int | None] = mapped_column(Integer)
    ho_unresolved_stock_sessions: Mapped[int | None] = mapped_column(Integer)
    ho_excluded_by_reason: Mapped[dict | None] = mapped_column(JSON)
    ho_candidates: Mapped[int | None] = mapped_column(Integer)
    ho_duplicates_removed: Mapped[int | None] = mapped_column(Integer)
    ho_overlap_suppressed: Mapped[int | None] = mapped_column(Integer)
    ho_unresolved_trades: Mapped[int | None] = mapped_column(Integer)
    ho_trades: Mapped[int | None] = mapped_column(Integer)
    ho_min_trades: Mapped[int | None] = mapped_column(Integer)
    ho_mean_benchmark: Mapped[Decimal | None] = mapped_column(Numeric(12, 8))
    ho_mean_gross: Mapped[Decimal | None] = mapped_column(Numeric(12, 8))
    ho_cost: Mapped[Decimal | None] = mapped_column(Numeric(12, 8))
    ho_mean_net: Mapped[Decimal | None] = mapped_column(Numeric(12, 8))
    ho_mean_excess: Mapped[Decimal | None] = mapped_column(Numeric(12, 8))
    ho_ci_low: Mapped[Decimal | None] = mapped_column(Numeric(12, 8))
    ho_ci_high: Mapped[Decimal | None] = mapped_column(Numeric(12, 8))
    ho_bootstrap_sessions: Mapped[int | None] = mapped_column(Integer)
    ho_result: Mapped[str | None] = mapped_column(String(8))
    ho_reasons: Mapped[list | None] = mapped_column(JSON)

    decision: Mapped[str] = mapped_column(String(16))
    primary_stage: Mapped[str | None] = mapped_column(String(16))
    primary_reason: Mapped[str | None] = mapped_column(String(64))
    reasons: Mapped[list] = mapped_column(JSON)
    decided_at: Mapped[datetime] = mapped_column(DateTime(timezone=True))
    valid_until: Mapped[datetime] = mapped_column(DateTime(timezone=True))
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=func.now())


class SelectionGateTrade(Base):
    """SPG-001 §12.2: every candidate of a decision, with its disposition."""

    __tablename__ = "selection_gate_trades"
    __table_args__ = (
        UniqueConstraint("decision_id", "stage", "session_date", "stock_id", name="uq_selection_gate_trade"),
    )

    id: Mapped[int] = mapped_column(BigInteger().with_variant(Integer, "sqlite"), primary_key=True)
    decision_id: Mapped[int] = mapped_column(ForeignKey("selection_gate_decisions.id"))
    stage: Mapped[str] = mapped_column(String(16))
    session_date: Mapped[date] = mapped_column(Date)
    stock_id: Mapped[int] = mapped_column(ForeignKey("stocks.id"))
    horizon_sessions: Mapped[int] = mapped_column(Integer)
    rank: Mapped[int] = mapped_column(Integer)
    score: Mapped[Decimal] = mapped_column(Numeric(18, 10))
    entry_session: Mapped[date | None] = mapped_column(Date)
    exit_session: Mapped[date | None] = mapped_column(Date)
    entry_open: Mapped[Decimal | None] = mapped_column(Numeric(18, 6))
    exit_close_adjusted: Mapped[Decimal | None] = mapped_column(Numeric(18, 6))
    label_status: Mapped[str] = mapped_column(String(32))
    gross_return: Mapped[Decimal | None] = mapped_column(Numeric(12, 8))
    benchmark_return: Mapped[Decimal | None] = mapped_column(Numeric(12, 8))
    cost: Mapped[Decimal] = mapped_column(Numeric(12, 8))
    net_return: Mapped[Decimal | None] = mapped_column(Numeric(12, 8))
    excess_return: Mapped[Decimal | None] = mapped_column(Numeric(12, 8))
    disposition: Mapped[str] = mapped_column(String(24))
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=func.now())


class SelectionBenchmarkSession(Base):
    """SPG-001 §12.3: the equal-weighted universe benchmark and its counts, per dataset hash."""

    __tablename__ = "selection_benchmark_sessions"
    __table_args__ = (
        UniqueConstraint("dataset_sha256", "session_date", "horizon_sessions", name="uq_selection_benchmark_session"),
    )

    id: Mapped[int] = mapped_column(BigInteger().with_variant(Integer, "sqlite"), primary_key=True)
    dataset_version: Mapped[str] = mapped_column(String(32))
    dataset_sha256: Mapped[str] = mapped_column(String(64))
    session_date: Mapped[date] = mapped_column(Date)
    horizon_sessions: Mapped[int] = mapped_column(Integer)
    eligible_count: Mapped[int] = mapped_column(Integer)
    labelled_count: Mapped[int] = mapped_column(Integer)
    unresolved: Mapped[dict] = mapped_column(JSON)
    excluded: Mapped[dict] = mapped_column(JSON)
    benchmark_return: Mapped[Decimal | None] = mapped_column(Numeric(12, 8))
    benchmarkable: Mapped[bool] = mapped_column(Boolean)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=func.now())


class SelectionShadowScore(Base):
    """SPG-001 §12.4: SEL-001's daily score for every eligible stock; never read by public routes."""

    __tablename__ = "selection_shadow_scores"
    __table_args__ = (
        UniqueConstraint("model_version", "horizon_sessions", "session_date", "stock_id", name="uq_selection_shadow_score"),
    )

    id: Mapped[int] = mapped_column(BigInteger().with_variant(Integer, "sqlite"), primary_key=True)
    model_version: Mapped[str] = mapped_column(String(64))
    artefact_sha256: Mapped[str] = mapped_column(String(64))
    horizon_sessions: Mapped[int] = mapped_column(Integer)
    session_date: Mapped[date] = mapped_column(Date)
    stock_id: Mapped[int] = mapped_column(ForeignKey("stocks.id"))
    score: Mapped[Decimal] = mapped_column(Numeric(18, 10))
    rank: Mapped[int] = mapped_column(Integer)
    selected: Mapped[bool] = mapped_column(Boolean)
    suppression_reason: Mapped[str | None] = mapped_column(String(24))
    scored_at: Mapped[datetime] = mapped_column(DateTime(timezone=True))


class SelectionHoldoutUsage(Base):
    """SPG-001 §10.2: single use of a gate exam month. `HoldoutUsageRecord` needs an experiment arm, so the gate keeps its own."""

    __tablename__ = "selection_holdout_usages"
    __table_args__ = (UniqueConstraint("holdout_label", name="uq_selection_holdout_usage_label"),)

    id: Mapped[int] = mapped_column(BigInteger().with_variant(Integer, "sqlite"), primary_key=True)
    holdout_label: Mapped[str] = mapped_column(String(128))
    registry_id: Mapped[int] = mapped_column(ForeignKey("holdout_window_registry.id"))
    model_version: Mapped[str] = mapped_column(String(64))
    horizon_sessions: Mapped[int] = mapped_column(Integer)
    holdout_month: Mapped[str] = mapped_column(String(7))
    used_at: Mapped[datetime] = mapped_column(DateTime(timezone=True))
```

In `Prediction`, after `status`:

```python
    # SPG-001: no default, so an insert that bypasses the gate fails instead of publishing.
    publication_state: Mapped[str] = mapped_column(String(16), nullable=False)
    publish_gate_decision_id: Mapped[int | None] = mapped_column(ForeignKey("selection_gate_decisions.id"))
    publish_gate_reason: Mapped[str | None] = mapped_column(String(64))
```

In `app/recommendations.py`, append `"publication_state", "publish_gate_decision_id", "publish_gate_reason",` to `IMMUTABLE_FIELDS`.

- [ ] **Step 4: Append-only listeners** in `app/selection_gate/records.py`:

```python
"""SPG-001 §12: the gate's append-only store and its writers."""
from __future__ import annotations

from sqlalchemy import event

from app.models import (
    SelectionBenchmarkSession,
    SelectionGateDecision,
    SelectionGateTrade,
    SelectionHoldoutUsage,
    SelectionShadowScore,
)

APPEND_ONLY_MODELS = (
    SelectionGateDecision, SelectionGateTrade, SelectionBenchmarkSession, SelectionShadowScore, SelectionHoldoutUsage,
)


class SelectionGateImmutableError(RuntimeError):
    pass


def _reject_change(mapper, connection, target):
    raise SelectionGateImmutableError(f"{target.__tablename__} rows are append-only (SPG-001)")


for _model in APPEND_ONLY_MODELS:
    event.listen(_model, "before_update", _reject_change)
    event.listen(_model, "before_delete", _reject_change)
```

Import it from `app/selection_gate/__init__.py` so any gate import arms the listeners. Add the line `from . import records  # noqa: F401  SPG-001: arm append-only listeners` below the docstring.

- [ ] **Step 5: Migration** `migrations/versions/0189_selection_publish_gate.py`:

```python
"""SPG-001: selection publish gate tables and the prediction publication state.

Revision ID: 0189_selection_publish_gate
Revises: 0188_evidence_decay_deadline
"""
from __future__ import annotations

import sqlalchemy as sa
from alembic import op

revision = "0189_selection_publish_gate"
down_revision = "0188_evidence_decay_deadline"
branch_labels = None
depends_on = None

_ID = sa.BigInteger().with_variant(sa.Integer(), "sqlite")
_RETURN = sa.Numeric(12, 8)
_STAGE_COUNTS = (
    "sessions", "eligible_stock_sessions", "labelled_stock_sessions", "unresolved_stock_sessions", "candidates",
    "duplicates_removed", "overlap_suppressed", "unresolved_trades", "trades", "min_trades", "bootstrap_sessions",
)
_STAGE_RETURNS = ("mean_benchmark", "mean_gross", "cost", "mean_net", "mean_excess", "ci_low", "ci_high")


def _stage(prefix: str) -> list[sa.Column]:
    return [
        *(sa.Column(f"{prefix}_{name}", sa.Integer()) for name in _STAGE_COUNTS),
        sa.Column(f"{prefix}_mean_eligible_per_session", sa.Numeric(12, 4)),
        sa.Column(f"{prefix}_excluded_by_reason", sa.JSON()),
        *(sa.Column(f"{prefix}_{name}", _RETURN) for name in _STAGE_RETURNS),
        sa.Column(f"{prefix}_result", sa.String(8)),
        sa.Column(f"{prefix}_reasons", sa.JSON()),
    ]


def _created() -> sa.Column:
    return sa.Column("created_at", sa.DateTime(timezone=True), server_default=sa.func.now(), nullable=False)


def upgrade() -> None:
    op.create_table(
        "selection_gate_decisions",
        sa.Column("id", _ID, primary_key=True),
        sa.Column("decision_uuid", sa.String(36), nullable=False),
        sa.Column("model_version", sa.String(64), nullable=False),
        sa.Column("horizon_sessions", sa.Integer(), nullable=False),
        sa.Column("gate_rule_version", sa.String(32), nullable=False),
        sa.Column("universe_rule_version", sa.String(32), nullable=False),
        sa.Column("dataset_version", sa.String(32), nullable=False),
        sa.Column("dataset_sha256", sa.String(64)),
        sa.Column("feature_version", sa.String(64), nullable=False),
        sa.Column("artefact_id", _ID, sa.ForeignKey("model_artefacts.id")),
        sa.Column("artefact_sha256", sa.String(64)),
        sa.Column("config_snapshot", sa.JSON(), nullable=False),
        sa.Column("config_sha256", sa.String(64), nullable=False),
        sa.Column("code_version", sa.String(64), nullable=False),
        sa.Column("dataset_first_session", sa.Date()),
        sa.Column("dataset_last_session", sa.Date()),
        sa.Column("wf_first_test_session", sa.Date()),
        sa.Column("wf_last_test_session", sa.Date()),
        sa.Column("wf_folds_tested", sa.Integer()),
        sa.Column("wf_folds_skipped", sa.Integer()),
        sa.Column("wf_folds", sa.JSON()),
        sa.Column("holdout_month", sa.String(7)),
        sa.Column("holdout_first_session", sa.Date()),
        sa.Column("holdout_last_session", sa.Date()),
        sa.Column("holdout_label", sa.String(128)),
        sa.Column("holdout_registry_id", _ID, sa.ForeignKey("holdout_window_registry.id")),
        *_stage("wf"),
        *_stage("ho"),
        sa.Column("decision", sa.String(16), nullable=False),
        sa.Column("primary_stage", sa.String(16)),
        sa.Column("primary_reason", sa.String(64)),
        sa.Column("reasons", sa.JSON(), nullable=False),
        sa.Column("decided_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("valid_until", sa.DateTime(timezone=True), nullable=False),
        _created(),
        sa.UniqueConstraint("decision_uuid", name="uq_selection_gate_decision_uuid"),
    )
    op.create_index(
        "ix_selection_gate_decisions_pair", "selection_gate_decisions", ["model_version", "horizon_sessions", "id"]
    )
    op.create_table(
        "selection_gate_trades",
        sa.Column("id", _ID, primary_key=True),
        sa.Column("decision_id", _ID, sa.ForeignKey("selection_gate_decisions.id"), nullable=False),
        sa.Column("stage", sa.String(16), nullable=False),
        sa.Column("session_date", sa.Date(), nullable=False),
        sa.Column("stock_id", _ID, sa.ForeignKey("stocks.id"), nullable=False),
        sa.Column("horizon_sessions", sa.Integer(), nullable=False),
        sa.Column("rank", sa.Integer(), nullable=False),
        sa.Column("score", sa.Numeric(18, 10), nullable=False),
        sa.Column("entry_session", sa.Date()),
        sa.Column("exit_session", sa.Date()),
        sa.Column("entry_open", sa.Numeric(18, 6)),
        sa.Column("exit_close_adjusted", sa.Numeric(18, 6)),
        sa.Column("label_status", sa.String(32), nullable=False),
        sa.Column("gross_return", _RETURN),
        sa.Column("benchmark_return", _RETURN),
        sa.Column("cost", _RETURN, nullable=False),
        sa.Column("net_return", _RETURN),
        sa.Column("excess_return", _RETURN),
        sa.Column("disposition", sa.String(24), nullable=False),
        _created(),
        sa.UniqueConstraint("decision_id", "stage", "session_date", "stock_id", name="uq_selection_gate_trade"),
    )
    op.create_table(
        "selection_benchmark_sessions",
        sa.Column("id", _ID, primary_key=True),
        sa.Column("dataset_version", sa.String(32), nullable=False),
        sa.Column("dataset_sha256", sa.String(64), nullable=False),
        sa.Column("session_date", sa.Date(), nullable=False),
        sa.Column("horizon_sessions", sa.Integer(), nullable=False),
        sa.Column("eligible_count", sa.Integer(), nullable=False),
        sa.Column("labelled_count", sa.Integer(), nullable=False),
        sa.Column("unresolved", sa.JSON(), nullable=False),
        sa.Column("excluded", sa.JSON(), nullable=False),
        sa.Column("benchmark_return", _RETURN),
        sa.Column("benchmarkable", sa.Boolean(), nullable=False),
        _created(),
        sa.UniqueConstraint("dataset_sha256", "session_date", "horizon_sessions", name="uq_selection_benchmark_session"),
    )
    op.create_table(
        "selection_shadow_scores",
        sa.Column("id", _ID, primary_key=True),
        sa.Column("model_version", sa.String(64), nullable=False),
        sa.Column("artefact_sha256", sa.String(64), nullable=False),
        sa.Column("horizon_sessions", sa.Integer(), nullable=False),
        sa.Column("session_date", sa.Date(), nullable=False),
        sa.Column("stock_id", _ID, sa.ForeignKey("stocks.id"), nullable=False),
        sa.Column("score", sa.Numeric(18, 10), nullable=False),
        sa.Column("rank", sa.Integer(), nullable=False),
        sa.Column("selected", sa.Boolean(), nullable=False),
        sa.Column("suppression_reason", sa.String(24)),
        sa.Column("scored_at", sa.DateTime(timezone=True), nullable=False),
        sa.UniqueConstraint(
            "model_version", "horizon_sessions", "session_date", "stock_id", name="uq_selection_shadow_score"
        ),
    )
    op.create_table(
        "selection_holdout_usages",
        sa.Column("id", _ID, primary_key=True),
        sa.Column("holdout_label", sa.String(128), nullable=False),
        sa.Column("registry_id", _ID, sa.ForeignKey("holdout_window_registry.id"), nullable=False),
        sa.Column("model_version", sa.String(64), nullable=False),
        sa.Column("horizon_sessions", sa.Integer(), nullable=False),
        sa.Column("holdout_month", sa.String(7), nullable=False),
        sa.Column("used_at", sa.DateTime(timezone=True), nullable=False),
        sa.UniqueConstraint("holdout_label", name="uq_selection_holdout_usage_label"),
    )
    # Existing calls were public; the default exists only to backfill them, then goes (fail closed).
    with op.batch_alter_table("predictions") as batch:
        batch.add_column(sa.Column("publication_state", sa.String(16), nullable=False, server_default="PUBLISHED"))
        batch.add_column(sa.Column(
            "publish_gate_decision_id", _ID,
            sa.ForeignKey("selection_gate_decisions.id", name="fk_predictions_publish_gate_decision"),
        ))
        batch.add_column(sa.Column("publish_gate_reason", sa.String(64)))
    with op.batch_alter_table("predictions") as batch:
        batch.alter_column(
            "publication_state", server_default=None, existing_type=sa.String(16), existing_nullable=False
        )


def downgrade() -> None:
    with op.batch_alter_table("predictions") as batch:
        batch.drop_column("publish_gate_reason")
        batch.drop_column("publish_gate_decision_id")
        batch.drop_column("publication_state")
    op.drop_table("selection_holdout_usages")
    op.drop_table("selection_shadow_scores")
    op.drop_table("selection_benchmark_sessions")
    op.drop_table("selection_gate_trades")
    op.drop_index("ix_selection_gate_decisions_pair", table_name="selection_gate_decisions")
    op.drop_table("selection_gate_decisions")
```

- [ ] **Step 6: Existing tests that build `Prediction(` directly (98 files) must set the state they always implicitly had**

```bash
grep -rlE "\bPrediction\(" tests --include="*.py" | xargs sed -i -E 's/\bPrediction\(/Prediction(publication_state="PUBLISHED", /g'
grep -rlE 'publication_state="PUBLISHED", $' tests --include="*.py" | xargs -r sed -i -E 's/publication_state="PUBLISHED", $/publication_state="PUBLISHED",/'
git diff --stat tests | tail -1
```

Then revert the substitution in `tests/test_selection_gate_schema.py`, where `_prediction` must omit the state: `git checkout -p tests/test_selection_gate_schema.py`, or edit the line back by hand.

- [ ] **Step 7: Run; expect PASS**

Run: `python -m pytest tests/test_selection_gate_schema.py tests/test_selection_gate_migration.py tests/test_alembic_single_head.py -q`

- [ ] **Step 8: Commit**

```bash
git add app/models.py app/recommendations.py app/selection_gate migrations/versions/0189_selection_publish_gate.py tests
git commit -m "SPG-001: gate tables, migration 0189 and fail-closed prediction publication state"
```

---

### Task 3: Statistics and decision rule (pure)

**Files:**
- Create: `app/selection_gate/statistics.py`
- Test: `tests/test_selection_gate_statistics.py`

**Interfaces:**
- Produces:
  - `AcceptedTrade(session_index: int, gross_return: float, benchmark_return: float)`
  - `StageThresholds(min_trades, min_net_excess, max_unresolved_share, min_folds: int | None, cost, bootstrap_draws, bootstrap_seed)`
  - `StageStatistics(trades, unresolved_trades, min_trades, mean_gross, mean_benchmark, cost, mean_net, mean_excess, ci_low, ci_high, bootstrap_sessions, result, reasons: tuple[str, ...])`
  - `GateOutcome(decision, primary_stage, primary_reason, reasons: tuple[dict, ...])`
  - `block_bootstrap_ci(session_sums, session_counts, *, block_length, draws, seed) -> tuple[float, float] | None`
  - `evaluate_stage(trades, *, unresolved_trades, folds_tested, horizon, thresholds) -> StageStatistics`
  - `failed_stage(reason, *, min_trades, cost) -> StageStatistics`
  - `decide(walk_forward, held_out) -> GateOutcome`

- [ ] **Step 1: Write the failing test** `tests/test_selection_gate_statistics.py`

```python
import math

import numpy as np
import pytest

from app.selection_gate.constants import (
    DECISION_NO_EDGE, DECISION_PUBLISH, REASON_CONFIDENCE_NOT_MET, REASON_EDGE_BELOW_THRESHOLD,
    REASON_INSUFFICIENT_EVIDENCE, REASON_UNRESOLVED_TRADES, RESULT_FAIL, RESULT_PASS, STAGE_HELD_OUT,
    STAGE_WALK_FORWARD,
)
from app.selection_gate.statistics import (
    AcceptedTrade, StageThresholds, block_bootstrap_ci, decide, evaluate_stage,
)

COST = 0.003
WF = StageThresholds(min_trades=2000, min_net_excess=0.005, max_unresolved_share=0.02, min_folds=24, cost=COST,
                     bootstrap_draws=2000, bootstrap_seed=42)
HO = StageThresholds(min_trades=100, min_net_excess=0.005, max_unresolved_share=0.02, min_folds=None, cost=COST,
                     bootstrap_draws=2000, bootstrap_seed=42)


def _trades(sessions: int, per_session: int, excess_of) -> list[AcceptedTrade]:
    """gross = excess + cost + benchmark, so e_j is exactly excess_of(session, k)."""
    trades = []
    for s in range(sessions):
        benchmark = 0.001 * (s % 3)
        for k in range(per_session):
            trades.append(AcceptedTrade(s, excess_of(s, k) + COST + benchmark, benchmark))
    return trades


def _steady(s, k):
    return 0.010 + 0.001 * (((s * 7 + k) % 5) - 2)


def test_positive_edge_with_ci_above_zero_publishes():
    wf = evaluate_stage(_trades(240, 10, _steady), unresolved_trades=0, folds_tested=30, horizon=5, thresholds=WF)
    ho = evaluate_stage(_trades(12, 10, _steady), unresolved_trades=0, folds_tested=None, horizon=5, thresholds=HO)
    assert wf.result == RESULT_PASS and ho.result == RESULT_PASS
    assert wf.ci_low > 0 and wf.mean_excess == pytest.approx(0.010, abs=1e-3)
    assert decide(wf, ho).decision == DECISION_PUBLISH


def test_positive_observed_edge_with_ci_crossing_zero_is_confidence_not_met():
    swing = lambda s, k: 0.006 + (0.3 if s % 2 == 0 else -0.3)
    wf = evaluate_stage(_trades(240, 10, swing), unresolved_trades=0, folds_tested=30, horizon=5, thresholds=WF)
    assert wf.mean_excess == pytest.approx(0.006, abs=1e-9)
    assert wf.ci_low < 0 < wf.ci_high
    assert wf.reasons == (REASON_CONFIDENCE_NOT_MET,)


def test_edge_below_threshold():
    wf = evaluate_stage(_trades(240, 10, lambda s, k: 0.003), unresolved_trades=0, folds_tested=30, horizon=5,
                        thresholds=WF)
    assert wf.ci_low == pytest.approx(0.003) and wf.reasons == (REASON_EDGE_BELOW_THRESHOLD,)


def test_too_few_trades_is_insufficient_evidence():
    wf = evaluate_stage(_trades(50, 1, _steady), unresolved_trades=0, folds_tested=30, horizon=5, thresholds=WF)
    assert wf.reasons[0] == REASON_INSUFFICIENT_EVIDENCE


def test_undefined_ci_is_insufficient_evidence():
    wf = evaluate_stage(_trades(5, 500, _steady), unresolved_trades=0, folds_tested=30, horizon=3, thresholds=WF)
    assert wf.ci_low is None and wf.reasons[0] == REASON_INSUFFICIENT_EVIDENCE


def test_too_few_folds_is_insufficient_evidence():
    wf = evaluate_stage(_trades(240, 10, _steady), unresolved_trades=0, folds_tested=23, horizon=5, thresholds=WF)
    assert wf.reasons == (REASON_INSUFFICIENT_EVIDENCE,)


def test_unresolved_share_over_limit_is_a_data_integrity_failure():
    wf = evaluate_stage(_trades(240, 10, _steady), unresolved_trades=100, folds_tested=30, horizon=5, thresholds=WF)
    assert wf.reasons[0] == REASON_UNRESOLVED_TRADES


def test_walk_forward_failure_blocks_even_when_held_out_passes():
    wf = evaluate_stage(_trades(50, 1, _steady), unresolved_trades=0, folds_tested=30, horizon=5, thresholds=WF)
    ho = evaluate_stage(_trades(12, 10, _steady), unresolved_trades=0, folds_tested=None, horizon=5, thresholds=HO)
    outcome = decide(wf, ho)
    assert outcome.decision == DECISION_NO_EDGE and outcome.primary_stage == STAGE_WALK_FORWARD
    assert outcome.primary_reason == REASON_INSUFFICIENT_EVIDENCE


def test_held_out_failure_blocks_even_when_walk_forward_passes():
    wf = evaluate_stage(_trades(240, 10, _steady), unresolved_trades=0, folds_tested=30, horizon=5, thresholds=WF)
    ho = evaluate_stage(_trades(12, 10, lambda s, k: 0.001), unresolved_trades=0, folds_tested=None, horizon=5,
                        thresholds=HO)
    outcome = decide(wf, ho)
    assert outcome.decision == DECISION_NO_EDGE and outcome.primary_stage == STAGE_HELD_OUT
    assert outcome.primary_reason == REASON_EDGE_BELOW_THRESHOLD
    assert {"stage": STAGE_HELD_OUT, "reason": REASON_EDGE_BELOW_THRESHOLD} in outcome.reasons


def test_bootstrap_is_deterministic_for_a_seed():
    sums, counts = [0.1, -0.2, 0.3, 0.05, -0.1, 0.2, 0.0, 0.4], [1, 2, 1, 1, 3, 1, 2, 1]
    first = block_bootstrap_ci(sums, counts, block_length=2, draws=500, seed=42)
    assert first == block_bootstrap_ci(sums, counts, block_length=2, draws=500, seed=42)
    assert first != block_bootstrap_ci(sums, counts, block_length=2, draws=500, seed=43)


def test_bootstrap_matches_a_hand_computed_draw():
    sums, counts = [1.0, 2.0, 3.0, 4.0], [1, 1, 2, 1]
    starts = np.random.default_rng(7).integers(0, 4, size=(1, 2))[0]
    sessions = [(start + offset) % 4 for start in starts for offset in range(2)][:4]
    expected = sum(sums[s] for s in sessions) / sum(counts[s] for s in sessions)
    low, high = block_bootstrap_ci(sums, counts, block_length=2, draws=1, seed=7)
    assert low == pytest.approx(expected) and high == pytest.approx(expected)


def test_constant_excess_has_a_degenerate_interval_and_short_series_none():
    assert block_bootstrap_ci([0.02] * 6, [2] * 6, block_length=3, draws=100, seed=1) == pytest.approx((0.01, 0.01))
    assert block_bootstrap_ci([0.02] * 5, [1] * 5, block_length=3, draws=100, seed=1) is None
    assert math.ceil(5 / 3) == 2
```

- [ ] **Step 2: Run; expect FAIL** (module missing). Run: `python -m pytest tests/test_selection_gate_statistics.py -q`

- [ ] **Step 3: Implement** `app/selection_gate/statistics.py`

```python
"""SPG-001 §11: stage statistics and the decision rule. Pure: no database, no clock."""
from __future__ import annotations

import math
from dataclasses import dataclass
from typing import Sequence

import numpy as np

from .constants import (
    DECISION_NO_EDGE, DECISION_PUBLISH, REASON_CONFIDENCE_NOT_MET, REASON_EDGE_BELOW_THRESHOLD,
    REASON_INSUFFICIENT_EVIDENCE, REASON_UNRESOLVED_TRADES, RESULT_FAIL, RESULT_PASS, STAGE_HELD_OUT,
    STAGE_WALK_FORWARD,
)

_DRAW_CHUNK = 500


@dataclass(frozen=True)
class AcceptedTrade:
    session_index: int
    gross_return: float
    benchmark_return: float


@dataclass(frozen=True)
class StageThresholds:
    min_trades: int
    min_net_excess: float
    max_unresolved_share: float
    min_folds: int | None
    cost: float
    bootstrap_draws: int
    bootstrap_seed: int


@dataclass(frozen=True)
class StageStatistics:
    trades: int
    unresolved_trades: int
    min_trades: int
    mean_gross: float | None
    mean_benchmark: float | None
    cost: float
    mean_net: float | None
    mean_excess: float | None
    ci_low: float | None
    ci_high: float | None
    bootstrap_sessions: int
    result: str
    reasons: tuple[str, ...]


@dataclass(frozen=True)
class GateOutcome:
    decision: str
    primary_stage: str | None
    primary_reason: str | None
    reasons: tuple[dict, ...]


def block_bootstrap_ci(session_sums: Sequence[float], session_counts: Sequence[int], *, block_length: int,
                       draws: int, seed: int) -> tuple[float, float] | None:
    """Circular moving-block bootstrap of the trade-weighted mean excess; None when m < 2L."""
    sums = np.asarray(session_sums, dtype=float)
    counts = np.asarray(session_counts, dtype=float)
    m = len(sums)
    if block_length < 1 or m < 2 * block_length:
        return None
    blocks = math.ceil(m / block_length)
    # One generator call for every start keeps the draw sequence independent of chunking.
    starts = np.random.default_rng(seed).integers(0, m, size=(draws, blocks))
    offsets = np.arange(block_length)
    means = np.empty(draws)
    for first in range(0, draws, _DRAW_CHUNK):
        part = starts[first:first + _DRAW_CHUNK]
        index = ((part[:, :, None] + offsets) % m).reshape(len(part), blocks * block_length)[:, :m]
        means[first:first + len(part)] = sums[index].sum(axis=1) / counts[index].sum(axis=1)
    low, high = np.percentile(means, [2.5, 97.5], method="linear")
    return float(low), float(high)


def evaluate_stage(trades: Sequence[AcceptedTrade], *, unresolved_trades: int, folds_tested: int | None,
                   horizon: int, thresholds: StageThresholds) -> StageStatistics:
    n = len(trades)
    cost = thresholds.cost
    reasons: list[str] = []
    attempted = n + unresolved_trades
    if attempted and unresolved_trades / attempted > thresholds.max_unresolved_share:
        reasons.append(REASON_UNRESOLVED_TRADES)

    mean_gross = mean_benchmark = mean_net = mean_excess = None
    interval = None
    sessions = 0
    if n:
        gross = np.array([t.gross_return for t in trades], dtype=float)
        benchmark = np.array([t.benchmark_return for t in trades], dtype=float)
        excess = gross - cost - benchmark
        mean_gross, mean_benchmark = float(gross.mean()), float(benchmark.mean())
        mean_net, mean_excess = float((gross - cost).mean()), float(excess.mean())
        by_session: dict[int, list[float]] = {}
        for trade, value in zip(trades, excess):
            by_session.setdefault(trade.session_index, []).append(float(value))
        ordered = sorted(by_session)
        sessions = len(ordered)
        interval = block_bootstrap_ci(
            [sum(by_session[s]) for s in ordered], [len(by_session[s]) for s in ordered],
            block_length=horizon, draws=thresholds.bootstrap_draws, seed=thresholds.bootstrap_seed,
        )

    too_few_folds = thresholds.min_folds is not None and (folds_tested or 0) < thresholds.min_folds
    if n < thresholds.min_trades or interval is None or too_few_folds:
        reasons.append(REASON_INSUFFICIENT_EVIDENCE)
    if mean_excess is None or mean_excess < thresholds.min_net_excess:
        reasons.append(REASON_EDGE_BELOW_THRESHOLD)
    if interval is None or interval[0] <= 0:
        reasons.append(REASON_CONFIDENCE_NOT_MET)

    return StageStatistics(
        trades=n, unresolved_trades=unresolved_trades, min_trades=thresholds.min_trades, mean_gross=mean_gross,
        mean_benchmark=mean_benchmark, cost=cost, mean_net=mean_net, mean_excess=mean_excess,
        ci_low=None if interval is None else interval[0], ci_high=None if interval is None else interval[1],
        bootstrap_sessions=sessions, result=RESULT_FAIL if reasons else RESULT_PASS, reasons=tuple(reasons),
    )


def failed_stage(reason: str, *, min_trades: int, cost: float) -> StageStatistics:
    """A stage that could not be evaluated (§10.2): no statistics, one reason."""
    return StageStatistics(
        trades=0, unresolved_trades=0, min_trades=min_trades, mean_gross=None, mean_benchmark=None, cost=cost,
        mean_net=None, mean_excess=None, ci_low=None, ci_high=None, bootstrap_sessions=0, result=RESULT_FAIL,
        reasons=(reason,),
    )


def decide(walk_forward: StageStatistics, held_out: StageStatistics) -> GateOutcome:
    reasons = tuple({"stage": STAGE_WALK_FORWARD, "reason": r} for r in walk_forward.reasons) + tuple(
        {"stage": STAGE_HELD_OUT, "reason": r} for r in held_out.reasons
    )
    if walk_forward.result == RESULT_PASS and held_out.result == RESULT_PASS:
        return GateOutcome(DECISION_PUBLISH, None, None, ())
    first = reasons[0]
    return GateOutcome(DECISION_NO_EDGE, first["stage"], first["reason"], reasons)
```

- [ ] **Step 4: Run; expect PASS.** Run: `python -m pytest tests/test_selection_gate_statistics.py -q`

- [ ] **Step 5: Commit**

```bash
git add app/selection_gate/statistics.py tests/test_selection_gate_statistics.py
git commit -m "SPG-001: block-bootstrap stage statistics and decision rule"
```

---

### Task 4: Trade selection and reduction (pure)

**Files:**
- Create: `app/selection_gate/reduction.py`
- Test: `tests/test_selection_gate_reduction.py`

**Interfaces:**
- Consumes: `AcceptedTrade`, `evaluate_stage`, `StageThresholds` (Task 3)
- Produces:
  - `Candidate(model_version, horizon, stock_id, session_index, rank, score, resolved: bool)`
  - `top_k_candidates(frame: pd.DataFrame, *, score_column: str, top_k: int) -> pd.DataFrame`. It adds a `rank` column; `frame` needs `session_index` and `stock_id`.
  - `reduce_candidates(candidates) -> list[tuple[Candidate, str]]`

- [ ] **Step 1: Write the failing test** `tests/test_selection_gate_reduction.py`

```python
import pandas as pd

from app.selection_gate.constants import (
    DISPOSITION_ACCEPTED, DISPOSITION_DUPLICATE, DISPOSITION_OVERLAP_SUPPRESSED, DISPOSITION_UNRESOLVED,
)
from app.selection_gate.reduction import Candidate, reduce_candidates, top_k_candidates
from app.selection_gate.statistics import AcceptedTrade, StageThresholds, evaluate_stage

THRESHOLDS = StageThresholds(min_trades=1, min_net_excess=0.0, max_unresolved_share=1.0, min_folds=None,
                             cost=0.003, bootstrap_draws=200, bootstrap_seed=42)


def _c(stock, session, rank=1, resolved=True, horizon=3):
    return Candidate("BASELINE-001", horizon, stock, session, rank, 1.0 / rank, resolved)


def _stats(candidates):
    reduced = reduce_candidates(candidates)
    accepted = [AcceptedTrade(c.session_index, 0.01 * (c.stock_id % 3) + 0.004, 0.0)
                for c, d in reduced if d == DISPOSITION_ACCEPTED]
    return evaluate_stage(accepted, unresolved_trades=0, folds_tested=None, horizon=3, thresholds=THRESHOLDS)


def test_ten_repeated_scans_count_once_and_leave_statistics_unchanged():
    once = [_c(s, d, rank=s) for d in range(0, 30, 3) for s in (1, 2, 3)]
    repeated = once * 10
    dispositions = [d for _, d in reduce_candidates(repeated)]
    assert dispositions.count(DISPOSITION_ACCEPTED) == len(once)
    assert dispositions.count(DISPOSITION_DUPLICATE) == 9 * len(once)
    assert _stats(repeated) == _stats(once)


def test_overlapping_holds_are_suppressed_and_a_clear_repick_is_accepted():
    reduced = reduce_candidates([_c(1, 0), _c(1, 1), _c(1, 2), _c(1, 3)])
    assert [d for _, d in reduced] == [
        DISPOSITION_ACCEPTED, DISPOSITION_OVERLAP_SUPPRESSED, DISPOSITION_OVERLAP_SUPPRESSED, DISPOSITION_ACCEPTED,
    ]


def test_suppressed_candidates_are_not_replaced():
    frame = pd.DataFrame({"session_index": [0, 1, 1, 1], "stock_id": [7, 7, 8, 9], "score": [0.9, 0.9, 0.8, 0.7]})
    top = top_k_candidates(frame, score_column="score", top_k=2)
    assert list(zip(top.session_index, top.stock_id, top["rank"])) == [(0, 7, 1), (1, 7, 1), (1, 8, 2)]
    reduced = reduce_candidates([_c(int(r.stock_id), int(r.session_index), int(r.rank)) for r in top.itertuples()])
    assert [(c.stock_id, d) for c, d in reduced if c.session_index == 1] == [
        (7, DISPOSITION_OVERLAP_SUPPRESSED), (8, DISPOSITION_ACCEPTED),
    ]


def test_candidates_are_chosen_before_label_status_is_known():
    frame = pd.DataFrame({"session_index": [0, 0], "stock_id": [1, 2], "score": [0.9, 0.1]})
    top = top_k_candidates(frame, score_column="score", top_k=1)
    assert list(top.stock_id) == [1]
    reduced = reduce_candidates([_c(1, 0, resolved=False)])
    assert reduced[0][1] == DISPOSITION_UNRESOLVED


def test_ties_break_by_stock_id():
    frame = pd.DataFrame({"session_index": [0, 0, 0], "stock_id": [5, 3, 4], "score": [0.5, 0.5, 0.5]})
    assert list(top_k_candidates(frame, score_column="score", top_k=2).stock_id) == [3, 4]
```

- [ ] **Step 2: Run; expect FAIL.** Run: `python -m pytest tests/test_selection_gate_reduction.py -q`

- [ ] **Step 3: Implement** `app/selection_gate/reduction.py`

```python
"""SPG-001 §8: top-K selection before labels are known, then duplicate and overlap reduction."""
from __future__ import annotations

from dataclasses import dataclass
from typing import Iterable

import pandas as pd

from .constants import (
    DISPOSITION_ACCEPTED, DISPOSITION_DUPLICATE, DISPOSITION_OVERLAP_SUPPRESSED, DISPOSITION_UNRESOLVED,
)


@dataclass(frozen=True)
class Candidate:
    model_version: str
    horizon: int
    stock_id: int
    session_index: int
    rank: int
    score: float
    resolved: bool


def top_k_candidates(frame: pd.DataFrame, *, score_column: str, top_k: int) -> pd.DataFrame:
    """Per session, the `top_k` highest scores among all eligible stocks; ties by stock_id ascending."""
    ordered = frame.sort_values(
        ["session_index", score_column, "stock_id"], ascending=[True, False, True], kind="mergesort"
    )
    top = ordered.groupby("session_index", sort=True).head(top_k).copy()
    top["rank"] = top.groupby("session_index").cumcount() + 1
    return top


def reduce_candidates(candidates: Iterable[Candidate]) -> list[tuple[Candidate, str]]:
    ordered = sorted(candidates, key=lambda c: (c.session_index, c.rank, c.stock_id))
    seen: set[tuple[str, int, int, int]] = set()
    last_accepted: dict[tuple[str, int, int], int] = {}
    reduced: list[tuple[Candidate, str]] = []
    for candidate in ordered:
        key = (candidate.model_version, candidate.horizon, candidate.stock_id, candidate.session_index)
        if key in seen:
            reduced.append((candidate, DISPOSITION_DUPLICATE))
            continue
        seen.add(key)
        holding = (candidate.model_version, candidate.horizon, candidate.stock_id)
        last = last_accepted.get(holding)
        if last is not None and candidate.session_index < last + candidate.horizon:
            reduced.append((candidate, DISPOSITION_OVERLAP_SUPPRESSED))
            continue
        if not candidate.resolved:
            reduced.append((candidate, DISPOSITION_UNRESOLVED))
            continue
        last_accepted[holding] = candidate.session_index
        reduced.append((candidate, DISPOSITION_ACCEPTED))
    return reduced
```

- [ ] **Step 4: Run; expect PASS.** Run: `python -m pytest tests/test_selection_gate_reduction.py -q`

- [ ] **Step 5: Commit**

```bash
git add app/selection_gate/reduction.py tests/test_selection_gate_reduction.py
git commit -m "SPG-001: top-K candidate selection with duplicate and overlap reduction"
```

---

### Task 5: `authorize_publication` (fail closed)

**Files:**
- Create: `app/selection_gate/authorization.py`
- Test: `tests/test_selection_gate_authorization.py`

**Interfaces:**
- Consumes: `SelectionGateDecision` (Task 2), `seed_decision` factory, `current_config_sha256`, `config_sha256` (Task 1)
- Produces:
  - `PublishAuthorization(state: str, reason: str | None, decision_id: int | None)`
  - `latest_decision(session, *, model_version, horizon_sessions) -> SelectionGateDecision | None`
  - `authorize_publication(session, *, model_version, horizon_sessions, at, artefact_sha256=None) -> PublishAuthorization`
  - `naive_utc(value: datetime) -> datetime`

- [ ] **Step 1: Write the failing test** `tests/test_selection_gate_authorization.py`

```python
from datetime import timedelta
from decimal import Decimal

import pytest
from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker

from app.db import Base
from app.selection_gate import authorization
from app.selection_gate.authorization import authorize_publication
from app.selection_gate.config import config_sha256
from app.selection_gate.constants import REASON_HORIZON_NOT_GATED
from app.selection_gate.constants import (
    DECISION_NO_EDGE, DECISION_PUBLISH, REASON_AUTHORIZATION_ERROR, REASON_CONFIDENCE_NOT_MET,
    REASON_DECISION_INVALID, REASON_DECISION_MISMATCH, REASON_DECISION_STALE, REASON_NO_DECISION, RESULT_FAIL,
    SEL001_MODEL_VERSION,
)
from app.settings import settings
from tests._selection_gate_factories import AT, seed_decision


@pytest.fixture
def session():
    engine = create_engine("sqlite:///:memory:")
    Base.metadata.create_all(engine)
    with sessionmaker(bind=engine)() as db:
        yield db


def _auth(session, model="BASELINE-001", horizon=5, at=AT + timedelta(days=1), artefact=None):
    return authorize_publication(session, model_version=model, horizon_sessions=horizon, at=at,
                                 artefact_sha256=artefact)


def test_no_decision_on_record(session):
    assert _auth(session).state == DECISION_NO_EDGE and _auth(session).reason == REASON_NO_DECISION


def test_valid_publish_decision_authorises_its_pair(session):
    decision = seed_decision(session)
    auth = _auth(session)
    assert (auth.state, auth.reason, auth.decision_id) == (DECISION_PUBLISH, None, decision.id)


def test_a_pair_outside_the_gate_is_never_authorised(session):
    seed_decision(session, horizon=2)
    seed_decision(session, model_version=SEL001_MODEL_VERSION, horizon=7)
    assert _auth(session, horizon=2).reason == REASON_HORIZON_NOT_GATED
    assert _auth(session, model=SEL001_MODEL_VERSION, horizon=7, artefact="a" * 64).reason == REASON_HORIZON_NOT_GATED


def test_every_baseline_horizon_is_authorised_only_by_its_own_decision(session):
    for horizon in (1, 3, 5, 7):
        assert _auth(session, horizon=horizon).reason == REASON_NO_DECISION
        seed_decision(session, horizon=horizon)
        assert _auth(session, horizon=horizon).state == DECISION_PUBLISH


def test_publish_at_h5_does_not_authorise_h3(session):
    seed_decision(session, horizon=5)
    assert _auth(session, horizon=3).reason == REASON_NO_DECISION


def test_latest_no_edge_supersedes_an_earlier_publish(session):
    seed_decision(session)
    later = seed_decision(session, decision=DECISION_NO_EDGE)
    auth = _auth(session)
    assert (auth.state, auth.reason, auth.decision_id) == (DECISION_NO_EDGE, REASON_CONFIDENCE_NOT_MET, later.id)


def test_valid_until_boundary_is_inclusive_across_naive_and_aware(session):
    decision = seed_decision(session)
    session.expire_all()  # SQLite reads back naive
    assert _auth(session, at=decision.valid_until.replace(tzinfo=AT.tzinfo)).state == DECISION_PUBLISH
    late = decision.valid_until.replace(tzinfo=AT.tzinfo) + timedelta(microseconds=1)
    assert _auth(session, at=late).reason == REASON_DECISION_STALE


@pytest.mark.parametrize("field,value", [
    ("gate_rule_version", "SPG-000"), ("universe_rule_version", "SEU-000"), ("dataset_version", "SEL-DS-000"),
    ("config_sha256", "0" * 64),
])
def test_each_version_or_config_mismatch_blocks(session, field, value):
    seed_decision(session, **{field: value})
    assert _auth(session).reason == REASON_DECISION_MISMATCH


def test_a_config_change_after_the_decision_blocks(session, monkeypatch):
    seed_decision(session)
    monkeypatch.setattr(settings, "selection_top_k", 20)
    assert _auth(session).reason == REASON_DECISION_MISMATCH


def test_snapshot_from_older_code_is_a_mismatch(session):
    decision = seed_decision(session)
    older = dict(decision.config_snapshot)
    older.pop("selection_min_fit_rows")
    seed_decision(session, config_snapshot=older, config_sha256=config_sha256(older))
    assert _auth(session).reason == REASON_DECISION_MISMATCH


def test_sel001_requires_the_decided_artefact(session):
    seed_decision(session, model_version=SEL001_MODEL_VERSION)
    assert _auth(session, model=SEL001_MODEL_VERSION, artefact="b" * 64).reason == REASON_DECISION_MISMATCH
    assert _auth(session, model=SEL001_MODEL_VERSION, artefact=None).reason == REASON_DECISION_MISMATCH
    assert _auth(session, model=SEL001_MODEL_VERSION, artefact="a" * 64).state == DECISION_PUBLISH


@pytest.mark.parametrize("overrides", [
    {"wf_ci_low": Decimal("-0.001")},
    {"ho_ci_low": Decimal("0")},
    {"ho_trades": 50},
    {"wf_mean_excess": Decimal("0.004")},
    {"wf_result": RESULT_FAIL},
    {"wf_folds_tested": 10},
    {"dataset_sha256": None},
    {"ho_mean_excess": None},
])
def test_tampered_numbers_are_invalid(session, overrides):
    seed_decision(session, **overrides)
    assert _auth(session).reason == REASON_DECISION_INVALID


def test_any_exception_fails_closed(session, monkeypatch):
    seed_decision(session)
    monkeypatch.setattr(authorization, "latest_decision", lambda *a, **k: (_ for _ in ()).throw(RuntimeError("x")))
    auth = _auth(session)
    assert (auth.state, auth.reason) == (DECISION_NO_EDGE, REASON_AUTHORIZATION_ERROR)
```

- [ ] **Step 2: Run; expect FAIL.** Run: `python -m pytest tests/test_selection_gate_authorization.py -q`

- [ ] **Step 3: Implement** `app/selection_gate/authorization.py`

```python
"""SPG-001 §13: fail-closed authorisation of one (model version, horizon) against its latest decision."""
from __future__ import annotations

import logging
from dataclasses import dataclass
from datetime import datetime, timezone

from sqlalchemy import select
from sqlalchemy.orm import Session

from app.models import SelectionGateDecision

from .config import config_sha256, current_config_sha256, gate_pairs
from .constants import (
    DATASET_VERSION, DECISION_NO_EDGE, DECISION_PUBLISH, GATE_RULE_VERSION, REASON_AUTHORIZATION_ERROR,
    REASON_HORIZON_NOT_GATED, REASON_DECISION_INVALID, REASON_DECISION_MISMATCH, REASON_DECISION_STALE, REASON_NO_DECISION, RESULT_PASS,
    SEL001_MODEL_VERSION, UNIVERSE_RULE_VERSION,
)

logger = logging.getLogger(__name__)


@dataclass(frozen=True)
class PublishAuthorization:
    state: str
    reason: str | None
    decision_id: int | None


def naive_utc(value: datetime) -> datetime:
    # SQLite drops tzinfo on a DateTime(timezone=True) round-trip.
    return value if value.tzinfo is None else value.astimezone(timezone.utc).replace(tzinfo=None)


def latest_decision(session: Session, *, model_version: str, horizon_sessions: int) -> SelectionGateDecision | None:
    return session.scalar(
        select(SelectionGateDecision)
        .where(SelectionGateDecision.model_version == model_version,
               SelectionGateDecision.horizon_sessions == horizon_sessions)
        .order_by(SelectionGateDecision.id.desc())
        .limit(1)
    )


def _stage_holds(decision: SelectionGateDecision, prefix: str, *, min_trades: int, min_excess: float) -> bool:
    result = getattr(decision, f"{prefix}_result")
    trades = getattr(decision, f"{prefix}_trades")
    stored_min = getattr(decision, f"{prefix}_min_trades")
    mean_excess = getattr(decision, f"{prefix}_mean_excess")
    ci_low = getattr(decision, f"{prefix}_ci_low")
    if None in (result, trades, stored_min, mean_excess, ci_low):
        return False
    return (result == RESULT_PASS and stored_min == min_trades and trades >= min_trades
            and float(mean_excess) >= min_excess and float(ci_low) > 0)


def _numbers_satisfy_rule(decision: SelectionGateDecision) -> bool:
    snapshot = decision.config_snapshot
    if config_sha256(snapshot) != decision.config_sha256:
        return False
    if decision.dataset_sha256 is None or decision.holdout_label is None:
        return False
    if (decision.wf_folds_tested or 0) < int(snapshot["selection_min_wf_folds"]):
        return False
    min_excess = float(snapshot["selection_min_net_excess"])
    return (_stage_holds(decision, "wf", min_trades=int(snapshot["selection_min_wf_trades"]), min_excess=min_excess)
            and _stage_holds(decision, "ho", min_trades=int(snapshot["selection_min_holdout_trades"]),
                             min_excess=min_excess))


def authorize_publication(session: Session, *, model_version: str, horizon_sessions: int, at: datetime,
                          artefact_sha256: str | None = None) -> PublishAuthorization:
    try:
        if (model_version, horizon_sessions) not in gate_pairs():
            return PublishAuthorization(DECISION_NO_EDGE, REASON_HORIZON_NOT_GATED, None)
        decision = latest_decision(session, model_version=model_version, horizon_sessions=horizon_sessions)
        if decision is None:
            return PublishAuthorization(DECISION_NO_EDGE, REASON_NO_DECISION, None)
        if decision.decision != DECISION_PUBLISH:
            return PublishAuthorization(DECISION_NO_EDGE, decision.primary_reason or REASON_DECISION_INVALID,
                                        decision.id)
        stored = (decision.gate_rule_version, decision.universe_rule_version, decision.dataset_version,
                  decision.config_sha256)
        if stored != (GATE_RULE_VERSION, UNIVERSE_RULE_VERSION, DATASET_VERSION, current_config_sha256()):
            return PublishAuthorization(DECISION_NO_EDGE, REASON_DECISION_MISMATCH, decision.id)
        if naive_utc(at) > naive_utc(decision.valid_until):
            return PublishAuthorization(DECISION_NO_EDGE, REASON_DECISION_STALE, decision.id)
        if model_version == SEL001_MODEL_VERSION and (
            artefact_sha256 is None or artefact_sha256 != decision.artefact_sha256
        ):
            return PublishAuthorization(DECISION_NO_EDGE, REASON_DECISION_MISMATCH, decision.id)
        if not _numbers_satisfy_rule(decision):
            return PublishAuthorization(DECISION_NO_EDGE, REASON_DECISION_INVALID, decision.id)
        return PublishAuthorization(DECISION_PUBLISH, None, decision.id)
    except Exception:
        logger.exception("SPG-001 authorisation failed for %s h=%s", model_version, horizon_sessions)
        return PublishAuthorization(DECISION_NO_EDGE, REASON_AUTHORIZATION_ERROR, None)
```

- [ ] **Step 4: Run; expect PASS.** Run: `python -m pytest tests/test_selection_gate_authorization.py -q`

- [ ] **Step 5: Commit**

```bash
git add app/selection_gate/authorization.py tests/test_selection_gate_authorization.py
git commit -m "SPG-001: fail-closed publication authorisation"
```

---
### Task 6: Point-in-time per-stock features and universe rule SEU-001

**Files:**
- Create: `app/selection_gate/features.py`, `app/selection_gate/universe.py`
- Test: `tests/test_selection_gate_universe.py`

**Interfaces:**
- Consumes (existing):
  - `back_adjust_frame`, `RatioAction` (`app/corporate_action_adjustment.py`)
  - `add_extended_features`, `EXTENDED_PER_STOCK_FEATURES` (`app/features/technical.py`)
  - `REQUIRED_FEATURE_COLUMNS` (`app/scan.py:34`)
  - `BaselineSignalProvider` (`app/baseline_signal.py`)
  - `session_date_of` (`app/market_data/freshness.py`), `as_utc` (`app/walk_forward_dataset.py:121`)
- Produces:
  - `bars_frame(rows) -> pd.DataFrame` with columns `timestamp` (UTC-aware), `open`, `high`, `low`, `close`, `volume` (floats)
  - `per_stock_features(bars, actions) -> pd.DataFrame` with `timestamp`, `session_date`, `*EXTENDED_PER_STOCK_FEATURES`, `baseline_score` (split-adjusted; the gate uses it) and `baseline_score_live` (raw bars, exactly as the live scan scores; report-only)
  - `exclusion_reasons(bars, features, *, is_equity, is_rights, min_history_bars, min_median_traded_value) -> np.ndarray[object]`
  - `stock_session_table(bars, actions, *, is_equity, is_rights, min_history_bars, min_median_traded_value) -> pd.DataFrame`: `per_stock_features` columns plus raw `open`, `close` and `exclusion_reason` (None = in `U(D)`)

- [ ] **Step 1: Write the failing test** `tests/test_selection_gate_universe.py`

```python
import math
from datetime import date
from decimal import Decimal

import numpy as np
import pandas as pd
import pytest

from app.baseline_signal import BaselineSignalProvider
from app.corporate_action_adjustment import RatioAction
from app.features.technical import EXTENDED_PER_STOCK_FEATURES, add_basic_features
from app.market_data.contracts import as_nse_aware, evaluate_record_contracts
from app.market_data.quality import PriceRecord
from app.selection_gate.constants import (
    EXCLUDED_BELOW_LIQUIDITY_FLOOR, EXCLUDED_DATA_CONTRACT_VIOLATION, EXCLUDED_INVALID_MARKET_DATA,
    EXCLUDED_NOT_EQUITY, EXCLUDED_RIGHTS_ENTITLEMENT, EXCLUDED_TOO_FEW_BARS,
)
from app.selection_gate.features import per_stock_features
from app.selection_gate.universe import exclusion_reasons
from app.walk_forward_dataset import Bar, extended_point_in_time_features, session_cutoff
from tests._selection_gate_factories import weekday_sessions

SESSIONS = weekday_sessions(date(2026, 9, 30), 90)
SPLIT_INDEX = 70


def _bars(*, split_at=SPLIT_INDEX, bad_at=None, volume=1_000_000, zero_volume_from=None, constant=False):
    rows = []
    for i, day in enumerate(SESSIONS):
        base = 100.0 if constant else 100.0 * (1 + 0.01 * math.sin(i / 3)) * (1 + 0.002 * i)
        split = split_at is not None and i >= split_at
        price = round(base / 2 if split else base, 4)
        vol = float(int(volume * (2 if split else 1) * (1 if constant else 1 + 0.3 * math.cos(i))))
        if zero_volume_from is not None and i >= zero_volume_from:
            vol = 0.0
        high, low = round(price * 1.02, 4), round(price * 0.98, 4)
        if bad_at == i:
            high = round(price * 0.97, 4)
        rows.append({"timestamp": pd.Timestamp(session_cutoff(day)), "open": round(price * 0.995, 4),
                     "high": high, "low": low, "close": price, "volume": vol})
    return pd.DataFrame(rows)


def _reference_bars(bars):
    return [Bar(timestamp=r.timestamp.to_pydatetime(), open=Decimal(repr(r.open)), high=Decimal(repr(r.high)),
                low=Decimal(repr(r.low)), close=Decimal(repr(r.close)), volume=int(r.volume))
            for r in bars.itertuples()]


def test_vectorised_features_equal_the_point_in_time_reference_across_a_split():
    bars = _bars()
    actions = (RatioAction(effective_date=SESSIONS[SPLIT_INDEX], ratio=Decimal("2")),)
    table = per_stock_features(bars, actions)
    reference_bars = _reference_bars(bars)
    for k in range(20, len(bars)):
        cutoff = reference_bars[k].timestamp
        seen = []
        ref = extended_point_in_time_features(reference_bars, cutoff, stock_id=1, actions=actions,
                                              observer=lambda s, c, t: seen.append(t))
        assert max(seen) <= cutoff and ref is not None
        for name in EXTENDED_PER_STOCK_FEATURES:
            expected, actual = ref.values[name], table.at[k, name]
            assert math.isnan(actual) if expected is None else actual == pytest.approx(expected, abs=6e-7), (k, name)
        assert table.at[k, "baseline_score"] == pytest.approx(float(ref.predicted_probability), abs=1e-12)


def test_changing_future_bars_never_changes_an_earlier_row():
    bars = _bars()
    actions = (RatioAction(effective_date=SESSIONS[SPLIT_INDEX], ratio=Decimal("2")),)
    shocked = bars.copy()
    shocked.loc[60:, ["open", "high", "low", "close"]] *= 3.0
    pd.testing.assert_frame_equal(per_stock_features(bars, actions).iloc[:60],
                                  per_stock_features(shocked, actions).iloc[:60])


def test_a_future_corporate_action_never_changes_an_earlier_row():
    bars = _bars(split_at=None)
    later = (RatioAction(effective_date=SESSIONS[80], ratio=Decimal("2")),)
    pd.testing.assert_frame_equal(per_stock_features(bars, ()).iloc[:80], per_stock_features(bars, later).iloc[:80])


def test_live_raw_score_matches_the_scan_and_differs_from_offline_only_across_a_split():
    """Offline scores split-adjusted bars; the live scan scores raw bars (app/scan.py::evaluate_stock). Not equivalent."""
    bars = _bars()
    actions = (RatioAction(effective_date=SESSIONS[SPLIT_INDEX], ratio=Decimal("2")),)
    table = per_stock_features(bars, actions)
    for k in range(20, len(bars)):
        live_row = add_basic_features(bars.iloc[: k + 1]).iloc[-1]
        expected = float(BaselineSignalProvider().predict(1, live_row).predicted_probability)
        assert table.at[k, "baseline_score_live"] == pytest.approx(expected, abs=1e-12), k
    assert (table.baseline_score_live[20:SPLIT_INDEX] == table.baseline_score[20:SPLIT_INDEX]).all()
    after_split = slice(SPLIT_INDEX, SPLIT_INDEX + 20)
    assert (table.baseline_score_live[after_split] != table.baseline_score[after_split]).any()


def _reasons(bars, **overrides):
    params = dict(is_equity=True, is_rights=False, min_history_bars=60, min_median_traded_value=10_000_000.0)
    params.update(overrides)
    return exclusion_reasons(bars, per_stock_features(bars, ()), **params)


def test_history_rule_then_eligibility():
    reasons = _reasons(_bars(split_at=None))
    assert set(reasons[:59]) == {EXCLUDED_TOO_FEW_BARS} and reasons[59] is None and reasons[89] is None


def test_instrument_rules_exclude_every_session():
    assert set(_reasons(_bars(split_at=None), is_equity=False)) == {EXCLUDED_NOT_EQUITY}
    assert set(_reasons(_bars(split_at=None), is_rights=True)) == {EXCLUDED_RIGHTS_ENTITLEMENT}


def test_contract_violation_matches_the_scan_evaluator_and_persists():
    bars = _bars(split_at=None, bad_at=65)
    reasons = _reasons(bars)
    for k in range(60, 90):
        records = [PriceRecord(1, as_nse_aware(ts.to_pydatetime()), Decimal(repr(o)), Decimal(repr(h)),
                               Decimal(repr(l)), Decimal(repr(c)), int(v))
                   for ts, o, h, l, c, v in bars.iloc[: k + 1].itertuples(index=False, name=None)]
        report = evaluate_record_contracts(records, SESSIONS[0], SESSIONS[k], stock_ids=[1], scope_key="1")
        assert (reasons[k] == EXCLUDED_DATA_CONTRACT_VIOLATION) == report.blocks_downstream, k


def test_liquidity_floor_is_inclusive():
    at_floor = _reasons(_bars(split_at=None, constant=True, volume=100_000))  # 100 x 100,000 = ₹1 crore
    assert at_floor[89] is None
    below = _reasons(_bars(split_at=None, constant=True, volume=99_999))
    assert below[89] == EXCLUDED_BELOW_LIQUIDITY_FLOOR


def test_missing_required_feature_is_invalid_market_data():
    reasons = _reasons(_bars(split_at=None, zero_volume_from=61))
    assert reasons[85] == EXCLUDED_INVALID_MARKET_DATA
    assert reasons[75] == EXCLUDED_BELOW_LIQUIDITY_FLOOR
```

- [ ] **Step 2: Run; expect FAIL.** Run: `python -m pytest tests/test_selection_gate_universe.py -q`

- [ ] **Step 3: Implement** `app/selection_gate/features.py`

```python
"""SPG-001 §7.2: FV-002 per-stock features and the BASELINE-001 score for every bar of one stock at once."""
from __future__ import annotations

from typing import Sequence

import numpy as np
import pandas as pd

from app.baseline_signal import BaselineSignalProvider
from app.corporate_action_adjustment import RatioAction, back_adjust_frame
from app.features.technical import EXTENDED_PER_STOCK_FEATURES, add_basic_features, add_extended_features
from app.market_data.freshness import session_date_of
from app.walk_forward_dataset import as_utc

_BASELINE = BaselineSignalProvider()
_BASELINE_INPUTS = ("sma20_distance", "volume_ratio_20d", "rsi_14", "atr_percent")
_PRICE_COLUMNS = ("open", "high", "low", "close", "volume")


def bars_frame(rows) -> pd.DataFrame:
    """`(timestamp, open, high, low, close, volume)` rows, ascending, as the float frame feature code reads."""
    frame = pd.DataFrame({"timestamp": pd.to_datetime([as_utc(r[0]) for r in rows], utc=True)})
    for position, column in enumerate(_PRICE_COLUMNS, start=1):
        frame[column] = [float(r[position]) for r in rows]
    return frame


def _baseline_scores(frame: pd.DataFrame) -> list[float]:
    return [
        float(_BASELINE.predict(0, dict(zip(_BASELINE_INPUTS, values))).predicted_probability)
        for values in frame[list(_BASELINE_INPUTS)].itertuples(index=False, name=None)
    ]


def per_stock_features(bars: pd.DataFrame, actions: Sequence[RatioAction]) -> pd.DataFrame:
    """Row k equals `extended_point_in_time_features` at bar k's cutoff: each corporate-action epoch is
    back-adjusted onto its own last session, so no row sees an action effective after it."""
    bars = bars.reset_index(drop=True)
    sessions = [session_date_of(ts.to_pydatetime()) for ts in bars["timestamp"]]
    effective = sorted({action.effective_date for action in actions})
    if effective and len(bars):
        epochs = np.searchsorted(np.array(effective, dtype="datetime64[D]"),
                                 np.array(sessions, dtype="datetime64[D]"), side="right")
    else:
        epochs = np.zeros(len(bars), dtype=int)
    parts = []
    for epoch in np.unique(epochs):
        positions = np.flatnonzero(epochs == epoch)
        last = int(positions[-1])
        adjusted = back_adjust_frame(bars.iloc[: last + 1], actions, cutoff_session=sessions[last])
        parts.append(add_extended_features(adjusted).iloc[positions])
    features = pd.concat(parts) if parts else pd.DataFrame(columns=[*EXTENDED_PER_STOCK_FEATURES, "rsi_14"])

    out = pd.DataFrame({"timestamp": bars["timestamp"], "session_date": sessions})
    out["baseline_score"] = _baseline_scores(features)
    # The live scan scores raw, unadjusted bars (app/scan.py::evaluate_stock); kept to report, never to decide.
    out["baseline_score_live"] = _baseline_scores(add_basic_features(bars)) if len(bars) else []
    for name in EXTENDED_PER_STOCK_FEATURES:
        out[name] = features[name].to_numpy(dtype=float)
    out[list(EXTENDED_PER_STOCK_FEATURES)] = out[list(EXTENDED_PER_STOCK_FEATURES)].replace([np.inf, -np.inf], np.nan)
    return out
```

`app/selection_gate/universe.py`:

```python
"""SPG-001 §6: universe rule SEU-001 for one stock, each session judged only on bars at or before it."""
from __future__ import annotations

from typing import Sequence

import numpy as np
import pandas as pd

from app.corporate_action_adjustment import RatioAction
from app.features.technical import EXTENDED_PER_STOCK_FEATURES
from app.scan import REQUIRED_FEATURE_COLUMNS

from .constants import (
    EXCLUDED_BELOW_LIQUIDITY_FLOOR, EXCLUDED_DATA_CONTRACT_VIOLATION, EXCLUDED_INVALID_MARKET_DATA,
    EXCLUDED_NOT_EQUITY, EXCLUDED_RIGHTS_ENTITLEMENT, EXCLUDED_TOO_FEW_BARS,
)
from .features import per_stock_features

_LIQUIDITY_WINDOW = 20


def exclusion_reasons(bars: pd.DataFrame, features: pd.DataFrame, *, is_equity: bool, is_rights: bool,
                      min_history_bars: int, min_median_traded_value: float) -> np.ndarray:
    n = len(bars)
    if not is_equity:
        return np.full(n, EXCLUDED_NOT_EQUITY, dtype=object)
    if is_rights:
        return np.full(n, EXCLUDED_RIGHTS_ENTITLEMENT, dtype=object)
    o, h, l, c, v = (bars[column].to_numpy(dtype=float) for column in ("open", "high", "low", "close", "volume"))
    # The record-arithmetic rows `evaluate_record_contracts` blocks on, carried forward like the scan's full history.
    bad = (np.minimum.reduce([o, h, l, c]) <= 0) | (h < np.maximum.reduce([o, l, c])) | (l > np.minimum.reduce([o, h, c]))
    violated = np.maximum.accumulate(bad) if n else bad
    too_few = np.arange(1, n + 1) < min_history_bars
    invalid = features[list(REQUIRED_FEATURE_COLUMNS)].isna().any(axis=1).to_numpy()
    # close x volume is split-invariant: back-adjustment divides price and multiplies volume by one factor.
    median_value = pd.Series(c * v).rolling(_LIQUIDITY_WINDOW).median().to_numpy()
    illiquid = ~(median_value >= min_median_traded_value)
    reasons = np.full(n, None, dtype=object)
    # Lowest priority first so the first failing rule (§6 order) is the one that remains.
    for mask, reason in ((illiquid, EXCLUDED_BELOW_LIQUIDITY_FLOOR), (violated, EXCLUDED_DATA_CONTRACT_VIOLATION),
                         (invalid, EXCLUDED_INVALID_MARKET_DATA), (too_few, EXCLUDED_TOO_FEW_BARS)):
        reasons[mask] = reason
    return reasons


def stock_session_table(bars: pd.DataFrame, actions: Sequence[RatioAction], *, is_equity: bool, is_rights: bool,
                        min_history_bars: int, min_median_traded_value: float) -> pd.DataFrame:
    bars = bars.reset_index(drop=True)
    if is_equity and not is_rights:
        table = per_stock_features(bars, actions)
    else:
        table = pd.DataFrame({"timestamp": bars["timestamp"], "session_date": [None] * len(bars),
                              "baseline_score": np.nan, "baseline_score_live": np.nan,
                              **{name: np.nan for name in EXTENDED_PER_STOCK_FEATURES}})
    table["open"] = bars["open"].to_numpy(dtype=float)
    table["close"] = bars["close"].to_numpy(dtype=float)
    table["exclusion_reason"] = exclusion_reasons(
        bars, table, is_equity=is_equity, is_rights=is_rights, min_history_bars=min_history_bars,
        min_median_traded_value=min_median_traded_value,
    )
    return table
```

- [ ] **Step 4: Run; expect PASS.** Run: `python -m pytest tests/test_selection_gate_universe.py -q`

  If the parity test fails on a single feature, the likely cause is a non-trailing pandas operation. Diff that column for the first failing `k` before changing anything. Do not loosen the tolerance.

- [ ] **Step 5: Commit**

```bash
git add app/selection_gate/features.py app/selection_gate/universe.py tests/test_selection_gate_universe.py
git commit -m "SPG-001: point-in-time per-stock features and universe rule SEU-001"
```

---

### Task 7: Publish path: `decide_publication` in `record_recommendation`, ledger refusal, legacy opt-in

**Files:**
- Create: `app/selection_gate/publication.py`
- Modify: `app/recommendations.py` (`record_recommendation`: before the `Prediction(` constructor at line 116, the constructor itself, and the tip call at line 144)
- Modify: `app/marksy_tips.py` (`register_prediction`, line 95)
- Modify: `tests/conftest.py` (add fixture `publish_gate_open`)
- Test: `tests/test_selection_gate_publish_path.py`

**Interfaces:**
- Consumes: `authorize_publication` (Task 5), `publication_capability` (Task 1), `stock_session_table`, `bars_frame` (Task 6)
- Produces:
  - `PublicationDecision(state, reason, decision_id)`
  - `stock_in_gate_universe(session, *, stock_id, as_of_timestamp) -> bool`
  - `decide_publication(session, *, model_version, horizon_days, stock_id, as_of_timestamp, at, artefact_sha256=None) -> PublicationDecision`
  - `app.marksy_tips.ShadowPredictionError`
  - pytest fixture `publish_gate_open`

- [ ] **Step 1: Write the failing test** `tests/test_selection_gate_publish_path.py`

`_record` mirrors a direct `record_recommendation` call. If this argument set is rejected, copy the one in `tests/test_marksy_prediction_tips.py`.

```python
from datetime import date, timedelta
from decimal import Decimal

import pytest
from sqlalchemy import create_engine, func, select
from sqlalchemy.orm import sessionmaker

from app.db import Base
from app.marksy_tips import ShadowPredictionError, register_prediction
from app.models import SelectionGateDecision, Tip
from app.recommendations import record_recommendation
from app.selection_gate import publication
from app.selection_gate.constants import (
    BASELINE_MODEL_VERSION, PUBLICATION_PUBLISHED, PUBLICATION_SHADOW, REASON_AUTHORIZATION_ERROR,
    REASON_HORIZON_NOT_GATED, REASON_NO_DECISION, REASON_OUTSIDE_GATE_UNIVERSE, REASON_SHADOW_ONLY_CAPABILITY,
    SEL001_MODEL_VERSION,
)
from app.walk_forward_dataset import session_cutoff
from tests._selection_gate_factories import AT, seed_decision, seed_stock_with_bars, weekday_sessions

SESSIONS = weekday_sessions(date(2026, 9, 30), 80)
AS_OF = session_cutoff(SESSIONS[-1])


@pytest.fixture
def session():
    engine = create_engine("sqlite:///:memory:")
    Base.metadata.create_all(engine)
    with sessionmaker(bind=engine)() as db:
        yield db


def _record(session, stock, *, model_version=BASELINE_MODEL_VERSION, horizon_days=5):
    return record_recommendation(
        session, stock_id=stock.id, as_of_timestamp=AS_OF, entry_price=Decimal("100"), horizon_days=horizon_days,
        target_return=Decimal("0.05"), stop_return=Decimal("-0.03"), predicted_probability=Decimal("0.70"),
        confidence=Decimal("0.80"), model_version=model_version, feature_version="FV-001",
        consensus_contract_version="CONSENSUS-TEST", horizon_selection_version="HSEL-TEST",
        scoring_contract_version="SCORE-TEST", opportunity_score=Decimal("70.00"), activation_now=AT,
    )


def _tips(session):
    return session.scalar(select(func.count()).select_from(Tip))


def test_no_decision_means_shadow_and_no_tip(session):
    stock = seed_stock_with_bars(session, symbol="AAA", sessions=SESSIONS)
    prediction = _record(session, stock)
    assert (prediction.publication_state, prediction.publish_gate_reason) == (PUBLICATION_SHADOW, REASON_NO_DECISION)
    assert _tips(session) == 0


def test_valid_publish_for_a_publishable_model_in_universe_publishes_with_a_tip(session):
    stock = seed_stock_with_bars(session, symbol="AAA", sessions=SESSIONS)
    decision = seed_decision(session, horizon=5)
    prediction = _record(session, stock)
    assert prediction.publication_state == PUBLICATION_PUBLISHED
    assert (prediction.publish_gate_decision_id, prediction.publish_gate_reason) == (decision.id, None)
    assert _tips(session) == 1


def test_every_baseline_horizon_earns_publication_independently(session):
    stock = seed_stock_with_bars(session, symbol="AAA", sessions=SESSIONS)
    seed_decision(session, horizon=1)
    assert _record(session, stock, horizon_days=1).publication_state == PUBLICATION_PUBLISHED
    for horizon in (3, 5, 7):
        prediction = _record(session, stock, horizon_days=horizon)
        assert (prediction.publication_state, prediction.publish_gate_reason) == (PUBLICATION_SHADOW, REASON_NO_DECISION)


def test_a_pair_outside_the_gate_stays_shadow_even_with_a_publish_row(session):
    stock = seed_stock_with_bars(session, symbol="AAA", sessions=SESSIONS)
    seed_decision(session, model_version=SEL001_MODEL_VERSION, horizon=7)
    outcome = publication.decide_publication(
        session, model_version=SEL001_MODEL_VERSION, horizon_days=7, stock_id=stock.id, as_of_timestamp=AS_OF,
        at=AT, artefact_sha256="a" * 64,
    )
    assert (outcome.state, outcome.reason) == (PUBLICATION_SHADOW, REASON_HORIZON_NOT_GATED)


def test_an_unknown_model_with_a_publish_decision_stays_shadow(session):
    stock = seed_stock_with_bars(session, symbol="AAA", sessions=SESSIONS)
    seed_decision(session, model_version="SEL-002", horizon=5)
    prediction = _record(session, stock, model_version="SEL-002")
    # Unlisted in selection_gate_pairs, so never gated; publication_capability() is SHADOW_ONLY as well (Task 1).
    assert (prediction.publication_state, prediction.publish_gate_reason) == (
        PUBLICATION_SHADOW, REASON_HORIZON_NOT_GATED,
    )


def test_publish_for_a_shadow_only_model_is_recorded_but_not_published(session):
    stock = seed_stock_with_bars(session, symbol="AAA", sessions=SESSIONS)
    decision = seed_decision(session, model_version=SEL001_MODEL_VERSION, horizon=5)
    before = {c.name: getattr(decision, c.name) for c in SelectionGateDecision.__table__.columns}
    outcome = publication.decide_publication(
        session, model_version=SEL001_MODEL_VERSION, horizon_days=5, stock_id=stock.id, as_of_timestamp=AS_OF,
        at=AT, artefact_sha256="a" * 64,
    )
    assert (outcome.state, outcome.reason, outcome.decision_id) == (
        PUBLICATION_SHADOW, REASON_SHADOW_ONLY_CAPABILITY, decision.id,
    )
    session.expire_all()
    assert {c.name: getattr(session.get(SelectionGateDecision, decision.id), c.name)
            for c in SelectionGateDecision.__table__.columns} == before


def test_publish_but_outside_the_gate_universe_is_shadow(session):
    stock = seed_stock_with_bars(session, symbol="AAA", sessions=SESSIONS[-30:])
    seed_decision(session, horizon=5)
    prediction = _record(session, stock)
    assert (prediction.publication_state, prediction.publish_gate_reason) == (
        PUBLICATION_SHADOW, REASON_OUTSIDE_GATE_UNIVERSE,
    )


def test_an_error_inside_the_decision_fails_closed(session, monkeypatch):
    stock = seed_stock_with_bars(session, symbol="AAA", sessions=SESSIONS)
    seed_decision(session, horizon=5)

    def boom(*args, **kwargs):
        raise RuntimeError("universe unavailable")

    monkeypatch.setattr(publication, "stock_in_gate_universe", boom)
    prediction = _record(session, stock)
    assert (prediction.publication_state, prediction.publish_gate_reason) == (
        PUBLICATION_SHADOW, REASON_AUTHORIZATION_ERROR,
    )


def test_the_ledger_refuses_a_shadow_prediction(session):
    stock = seed_stock_with_bars(session, symbol="AAA", sessions=SESSIONS)
    prediction = _record(session, stock)
    with pytest.raises(ShadowPredictionError):
        register_prediction(session, prediction)
```

- [ ] **Step 2: Run; expect FAIL.** Run: `python -m pytest tests/test_selection_gate_publish_path.py -q`

- [ ] **Step 3: Implement** `app/selection_gate/publication.py`

```python
"""SPG-001 §13.1/§14.1: whether a new prediction is PUBLISHED or SHADOW. Fails closed."""
from __future__ import annotations

import logging
from dataclasses import dataclass
from datetime import datetime

from sqlalchemy import select
from sqlalchemy.orm import Session

from app.corporate_action_adjustment import ratio_actions_for
from app.instrument_class import is_equity_instrument
from app.market_data.freshness import session_date_of
from app.models import MarketPrice, Stock
from app.settings import settings
from app.universe_integrity import is_rights_entitlement
from app.walk_forward_dataset import as_utc

from .authorization import authorize_publication
from .capability import publication_capability
from .constants import (
    CAPABILITY_PUBLISHABLE, DECISION_PUBLISH, PUBLICATION_PUBLISHED, PUBLICATION_SHADOW, REASON_AUTHORIZATION_ERROR,
    REASON_OUTSIDE_GATE_UNIVERSE, REASON_SHADOW_ONLY_CAPABILITY,
)
from .features import bars_frame
from .universe import stock_session_table

logger = logging.getLogger(__name__)


@dataclass(frozen=True)
class PublicationDecision:
    state: str
    reason: str | None
    decision_id: int | None


def stock_in_gate_universe(session: Session, *, stock_id: int, as_of_timestamp: datetime) -> bool:
    """SEU-001 at the prediction's own session, on the bars the scan used (its provisional D bar included)."""
    stock = session.get(Stock, stock_id)
    if stock is None:
        return False
    rows = session.execute(
        select(MarketPrice.timestamp, MarketPrice.open, MarketPrice.high, MarketPrice.low, MarketPrice.close,
               MarketPrice.volume)
        .where(MarketPrice.stock_id == stock_id, MarketPrice.timestamp <= as_of_timestamp)
        .order_by(MarketPrice.timestamp.asc())
    ).all()
    if not rows or session_date_of(as_utc(rows[-1][0])) != session_date_of(as_utc(as_of_timestamp)):
        return False
    table = stock_session_table(
        bars_frame(rows), ratio_actions_for(session, stock_id),
        is_equity=is_equity_instrument(stock.instrument_key), is_rights=is_rights_entitlement(stock.symbol),
        min_history_bars=settings.selection_min_history_bars,
        min_median_traded_value=settings.selection_min_median_traded_value_20d,
    )
    return table["exclusion_reason"].iloc[-1] is None


def decide_publication(session: Session, *, model_version: str, horizon_days: int, stock_id: int,
                       as_of_timestamp: datetime, at: datetime,
                       artefact_sha256: str | None = None) -> PublicationDecision:
    try:
        auth = authorize_publication(session, model_version=model_version, horizon_sessions=horizon_days, at=at,
                                     artefact_sha256=artefact_sha256)
        if auth.state != DECISION_PUBLISH:
            return PublicationDecision(PUBLICATION_SHADOW, auth.reason, auth.decision_id)
        if publication_capability(model_version) != CAPABILITY_PUBLISHABLE:
            return PublicationDecision(PUBLICATION_SHADOW, REASON_SHADOW_ONLY_CAPABILITY, auth.decision_id)
        if not stock_in_gate_universe(session, stock_id=stock_id, as_of_timestamp=as_of_timestamp):
            return PublicationDecision(PUBLICATION_SHADOW, REASON_OUTSIDE_GATE_UNIVERSE, auth.decision_id)
        return PublicationDecision(PUBLICATION_PUBLISHED, None, auth.decision_id)
    except Exception:
        logger.exception("SPG-001 publication decision failed for %s h=%s stock=%s", model_version, horizon_days,
                         stock_id)
        return PublicationDecision(PUBLICATION_SHADOW, REASON_AUTHORIZATION_ERROR, None)
```

In `app/recommendations.py::record_recommendation`, immediately before `recommendation = Prediction(`:

```python
    from .selection_gate import publication as publish_gate  # deferred: the gate imports modules that import this one

    gate = publish_gate.decide_publication(
        session, model_version=model_version, horizon_days=horizon_days, stock_id=stock_id,
        as_of_timestamp=as_of_timestamp, at=activation_now or datetime.now(timezone.utc),
    )
```

Add these keyword arguments to the `Prediction(...)` constructor:

```python
        publication_state=gate.state,
        publish_gate_decision_id=gate.decision_id,
        publish_gate_reason=gate.reason,
```

Replace line 144 (`sync_prediction_from_tip(session, register_prediction(session, recommendation), now=...)`), keeping its existing `now=` argument, with:

```python
    if recommendation.publication_state == PUBLICATION_PUBLISHED:  # SPG-001: only published calls become tips
        sync_prediction_from_tip(session, register_prediction(session, recommendation), now=<unchanged>)
```

Import `PUBLICATION_PUBLISHED` from `.selection_gate.constants` at the top of the module; the constants module has no dependencies. Add `datetime` and `timezone` to the imports if missing.

In `app/marksy_tips.py`:

```python
class ShadowPredictionError(ValueError):
    """SPG-001: a SHADOW prediction never becomes a ledger tip."""
```

Make this the first statement of `register_prediction`:

```python
    if prediction.publication_state != PUBLICATION_PUBLISHED:
        raise ShadowPredictionError(f"prediction {prediction.id} is {prediction.publication_state}; only PUBLISHED calls become tips")
```

Import `PUBLICATION_PUBLISHED` from `app.selection_gate.constants`.

In `tests/conftest.py`, add:

```python
@pytest.fixture
def publish_gate_open(monkeypatch):
    """SPG-001 is fail closed; pipeline tests written before the gate opt in to publication explicitly."""
    from app.selection_gate import publication
    from app.selection_gate.constants import PUBLICATION_PUBLISHED

    monkeypatch.setattr(
        publication, "decide_publication",
        lambda session, **_: publication.PublicationDecision(PUBLICATION_PUBLISHED, None, None),
    )
```

- [ ] **Step 4: Run; expect PASS.** Run: `python -m pytest tests/test_selection_gate_publish_path.py -q`

- [ ] **Step 5: Opt the pre-gate pipeline tests in** (they assert on published calls, tips, rankings and publications)

```bash
FILES=$(grep -rlE "record_recommendation|route_discovery_through_pipeline|select_horizon|record_qualifying_recommendation|record_ranked_recommendation|continuous_discovery|run_discovery_scan|record_watchlist|register_prediction" tests --include="test_*.py" | grep -v test_selection_gate_)
python -m pytest $FILES -q -p no:cacheprovider -x --co -q | tail -1
python -m pytest $FILES -q -p no:cacheprovider 2>&1 | grep -E "^FAILED|^ERROR" | cut -d: -f1 | sort -u
```

Some failing files fail because a prediction is now `SHADOW`: an empty list, no `Tip`, no ranking, no publication, or `ShadowPredictionError`. In each of those files, add this after the imports (append to an existing `pytestmark` list if there is one):

```python
pytestmark = pytest.mark.usefixtures("publish_gate_open")  # SPG-001: pre-gate pipeline behaviour under an open gate
```

Re-run the same `$FILES` until green. A failure for any other reason is a real regression: stop and investigate it (superpowers:systematic-debugging).

- [ ] **Step 6: Commit**

```bash
git add app/selection_gate/publication.py app/recommendations.py app/marksy_tips.py tests
git commit -m "SPG-001: gate every new prediction before publication; SHADOW never reaches the ledger"
```

---

### Task 8: Downstream side effects are published-only

**Files:**
- Create: `app/selection_gate/visibility.py`
- Modify:
  - `app/opportunity_ranking.py::rank_positive_opportunities` (line 186; guard before the gate-failure branch near line 225)
  - `app/cross_sectional_ranking.py::_qualified_prediction_ids_for_scan` (lines 60–78)
  - `app/continuous_discovery.py` (line 259)
  - `app/lifecycle.py::published_occurrences` (lines 280–314)
  - `app/rating_job.py` (lines 135 and 161)
  - `app/trade_decision_log.py` (line 170)
  - `app/tip_backfill.py`: the query that selects the predictions it passes to `backfill_prediction`
- Test: `tests/test_selection_gate_side_effects.py`

**Interfaces:**
- Produces: `published_only(statement)`, which returns `statement.where(Prediction.publication_state == PUBLICATION_PUBLISHED)` and works on `Select` and `Query`.

- [ ] **Step 1: Write the failing test** `tests/test_selection_gate_side_effects.py`

```python
from datetime import datetime, timezone
from decimal import Decimal

import pytest
from sqlalchemy import create_engine, select
from sqlalchemy.orm import sessionmaker

from app.db import Base
from app.models import Prediction, Stock
from app.opportunity_ranking import rank_positive_opportunities
from app.rating_job import _open_call
from app.selection_gate.constants import PUBLICATION_PUBLISHED, PUBLICATION_SHADOW, RANKING_EXCLUSION_SHADOW
from app.selection_gate.visibility import published_only

AT = datetime(2026, 10, 1, 12, tzinfo=timezone.utc)


@pytest.fixture
def session():
    engine = create_engine("sqlite:///:memory:")
    Base.metadata.create_all(engine)
    with sessionmaker(bind=engine)() as db:
        yield db


def _prediction(session, symbol, state):
    stock = Stock(symbol=symbol)
    session.add(stock)
    session.flush()
    prediction = Prediction(
        publication_state=state, stock_id=stock.id, as_of_timestamp=AT, entry_price=Decimal("100"), horizon_days=5,
        target_return=Decimal("0.05"), stop_return=Decimal("-0.03"), predicted_probability=Decimal("0.7"),
        confidence=Decimal("0.8"), model_version="BASELINE-001", feature_version="FV-001",
        consensus_contract_version="C", horizon_selection_version="H", scoring_contract_version="S",
        opportunity_score=Decimal("70"), status="OPEN",
    )
    session.add(prediction)
    session.flush()
    return prediction


def test_published_only_filters_select_statements(session):
    shadow = _prediction(session, "SHDW", PUBLICATION_SHADOW)
    public = _prediction(session, "PUBL", PUBLICATION_PUBLISHED)
    ids = set(session.scalars(published_only(select(Prediction.id))))
    assert ids == {public.id} and shadow.id not in ids


def test_a_shadow_prediction_is_never_an_included_opportunity(session):
    shadow = _prediction(session, "SHDW", PUBLICATION_SHADOW)
    rows = rank_positive_opportunities(session, [shadow.id], evaluated_at=AT)
    assert [(r.included, r.exclusion_reason) for r in rows] == [(False, RANKING_EXCLUSION_SHADOW)]


def test_the_rating_engine_ignores_shadow_calls(session):
    shadow = _prediction(session, "SHDW", PUBLICATION_SHADOW)
    assert _open_call(session, shadow.stock_id) is None
```

Add one test per remaining site in the same file:

- `app/trade_decision_log.py:170`: call the public function that contains line 170, with `stock_ids=[shadow.stock_id]`. Find it with `grep -n "^def " app/trade_decision_log.py`. Assert its candidate for that stock has no open prediction.
- `app/tip_backfill.py`: call the backfill entry point, the function that loops over predictions calling `backfill_prediction`, with one `SHADOW` prediction. Assert it registers zero tips and raises nothing.
- `app/continuous_discovery.py:259`: copy the setup of the `tests/test_continuous_discovery.py` test that asserts a `RecommendationPublication` is created, and do not use `publish_gate_open`. Assert zero `RecommendationPublication` rows and a `SHADOW` prediction.

- [ ] **Step 2: Run; expect FAIL.** Run: `python -m pytest tests/test_selection_gate_side_effects.py -q`

- [ ] **Step 3: Implement**

`app/selection_gate/visibility.py`:

```python
"""SPG-001 §14.2: the one predicate every user-visible read of Prediction applies."""
from __future__ import annotations

from app.models import Prediction

from .constants import PUBLICATION_PUBLISHED


def published_only(statement):
    return statement.where(Prediction.publication_state == PUBLICATION_PUBLISHED)
```

Site changes. Each gets the one-line comment `# SPG-001: SHADOW calls never surface.`

- `app/opportunity_ranking.py`: in the per-prediction loop of `rank_positive_opportunities`, before the gate-failure branch (about line 225), add a branch for `prediction.publication_state != PUBLICATION_PUBLISHED`. It builds the same excluded `PositiveOpportunityRanking(...)` row that the gate-failure branch builds, with `included=False` and `exclusion_reason=RANKING_EXCLUSION_SHADOW`, then continues.
- `app/cross_sectional_ranking.py::_qualified_prediction_ids_for_scan`: wrap its `select(...)` in `published_only(...)`.
- `app/continuous_discovery.py:259`: change to

  ```python
          if prediction.publication_state == PUBLICATION_PUBLISHED:  # SPG-001: SHADOW calls are never published
              publications.append(publish_recommendation(session, prediction, published_at=evaluated_at))
  ```

- `app/lifecycle.py::published_occurrences`: add `.where(Prediction.publication_state == PUBLICATION_PUBLISHED)` to its `select(...)`, after the existing `.where(...)`.
- `app/rating_job.py:135`: `select(Prediction.stock_id).where(Prediction.status == PREDICTION_OPEN, Prediction.publication_state == PUBLICATION_PUBLISHED)`. Line 161: add the same condition to `_open_call`'s `.where(...)`.
- `app/trade_decision_log.py:170`: add the same condition to its `.where(...)`.
- `app/tip_backfill.py`: wrap the prediction-selection query in `published_only(...)`.

Import `PUBLICATION_PUBLISHED` (and `RANKING_EXCLUSION_SHADOW` in `opportunity_ranking`) from `app.selection_gate.constants`, and `published_only` from `app.selection_gate.visibility`. If `visibility` creates an import cycle in a module, import the constant and inline the condition instead.

- [ ] **Step 4: Run; expect PASS**, then re-run the owning suites:

```bash
python -m pytest tests/test_selection_gate_side_effects.py tests/test_opportunity_ranking.py tests/test_cross_sectional_ranking.py tests/test_continuous_discovery.py tests/test_recommendation_alerts.py tests/test_alert_delivery_and_sweep.py tests/test_marksy_prediction_tips.py -q
```

- [ ] **Step 5: Commit**

```bash
git add app tests/test_selection_gate_side_effects.py
git commit -m "SPG-001: ranking, publication, alerts, rating and backfill ignore SHADOW calls"
```

---

### Task 9: Public surfaces and `meta.publishGate`

**Files:**
- Modify: every `api/services/*.py` in the reader audit (Gate list); `api/routers/recommendation_detail.py`; `api/routers/predictions_active.py`; `api/schemas/predictions_active.py`
- Test: `tests/test_selection_gate_public_surfaces.py`

**Interfaces:**
- Consumes: `published_only` (Task 8), `authorize_publication`, `latest_decision` (Task 5), `publication_capability`, `display_reason`, `gate_pairs` (Task 1)
- Produces: `api.services.predictions_active.publish_gate_meta(session, *, now=None) -> list[dict]` and the `PublishGateEntry` schema

- [ ] **Step 1: Write the failing test** `tests/test_selection_gate_public_surfaces.py`

Copy `_make_prediction`, its imports and its `session`/`client` fixtures verbatim from `tests/test_api_predictions_active.py` (lines 1–120). Also copy whatever ranking step its first listing test performs so that an item appears in `/predictions/active`. Then add:

```python
import json
import pathlib
import re

from sqlalchemy import text

from app.lifecycle import published_occurrences
from app.selection_gate.constants import SEL001_MODEL_VERSION
from tests._selection_gate_factories import seed_decision

DETAIL_SUFFIXES = ("", "/provenance", "/history", "/events", "/timeline", "/evidence", "/observations", "/outcome",
                   "/explanation")


@pytest.fixture
def seeded(session, publish_gate_open):
    control = _make_prediction(session, symbol="CTRL")
    shadow = _make_prediction(session, symbol="SHDW")
    # Fully ranked, open, with a generation -- then SHADOW, so every reader's own filter is what is tested.
    session.execute(text("UPDATE predictions SET publication_state = 'SHADOW' WHERE id = :id"), {"id": shadow[0].id})
    session.commit()
    session.expire_all()
    return control, shadow


def _symbols(items):
    return {item.get("symbol") for item in items}


@pytest.mark.parametrize("route", ["/api/v1/predictions/active", "/api/v1/recommendations", "/api/v1/opportunities"])
def test_shadow_calls_are_absent_from_list_routes(client, seeded, route):
    body = client.get(route).json()
    assert "SHDW" not in _symbols(body["data"])
    if route == "/api/v1/predictions/active":
        assert "CTRL" in _symbols(body["data"])


def test_shadow_calls_are_absent_from_the_dashboard(client, seeded):
    snapshot = client.get("/api/v1/dashboard/snapshot").json()["data"]
    assert "SHDW" not in _symbols(snapshot["topOpportunities"])


def test_shadow_calls_are_absent_from_the_instrument_page(client, seeded):
    assert client.get("/api/v1/instruments/SHDW").json()["data"]["predictions"] == []


def test_shadow_calls_have_no_detail_routes(client, seeded):
    (control, control_generation, *_), (shadow, shadow_generation, *_) = seeded
    assert client.get(f"/api/v1/recommendations/{control_generation.id}").status_code == 200
    for suffix in DETAIL_SUFFIXES:
        assert client.get(f"/api/v1/recommendations/{shadow_generation.id}{suffix}").status_code == 404, suffix


def test_shadow_call_by_id_is_not_found(client, seeded):
    (control, control_generation, *_), (shadow, shadow_generation, *_) = seeded
    listed = client.get("/api/v1/predictions/active").json()["data"]
    control_item = next(i for i in listed if i["symbol"] == "CTRL")
    key = shadow.id if str(control_item["id"]) == str(control.id) else shadow_generation.id
    assert client.get(f"/api/v1/predictions/active/{key}").status_code == 404


def test_shadow_calls_never_reach_alerts(session, seeded):
    _, (shadow, shadow_generation, *_) = seeded
    assert shadow_generation.id not in {o.generation_id for o in published_occurrences(session)}


def test_publish_gate_meta_separates_decision_from_publication(client, session):
    seed_decision(session, model_version=SEL001_MODEL_VERSION, horizon=5)
    session.commit()
    body = client.get("/api/v1/predictions/active").json()
    entries = {(e["modelVersion"], e["horizonSessions"]): e for e in body["meta"]["publishGate"]}
    sel = entries[(SEL001_MODEL_VERSION, 5)]
    assert (sel["gateDecision"], sel["publicationCapability"], sel["publishing"]) == ("PUBLISH", "SHADOW_ONLY", False)
    base = entries[("BASELINE-001", 3)]
    assert (base["gateDecision"], base["reason"], base["publishing"]) == (
        "NO_EDGE", "NO_EDGE — NO_DECISION_ON_RECORD", False,
    )
    assert body["data"] == []


def test_every_public_service_that_reads_predictions_filters_to_published():
    services = pathlib.Path(__file__).resolve().parents[1] / "api" / "services"
    operator_only = {"learning_readiness.py", "ops_retrigger.py"}
    offenders = [p.name for p in sorted(services.glob("*.py"))
                 if re.search(r"\bPrediction\b", p.read_text(encoding="utf-8"))
                 and "published_only" not in p.read_text(encoding="utf-8") and p.name not in operator_only]
    assert offenders == []
```

`published_occurrences` returns `PublishedOccurrence` rows. Use its actual generation-id attribute name, from `app/lifecycle.py:280`. If `CTRL` is absent from `/recommendations` or `/opportunities` for reasons unrelated to SPG-001, extend the fixture the way that route's own test file seeds it.

- [ ] **Step 2: Run; expect FAIL.** Run: `python -m pytest tests/test_selection_gate_public_surfaces.py -q`

- [ ] **Step 3: Implement**

a) In each service on the Gate list, wrap every `select(...)`/`query(...)` that reads `Prediction` rows in `published_only(...)`:
   - `predictions_active.py`: list and `get_active_prediction`
   - `recommendations.py`: the `list_recommendations` and `search_recommendations_by_symbol` queries
   - `opportunities.py`, `market.py::_marksy_market_view`, `tracking.py`, `discovery.py`, `discovery_intelligence.py`, `recommendations_snooze.py`, `recommendation_detail.py`, `reproducibility.py`, `attribution.py`, `feedback.py`
   - `instruments.py` reads through `app/recommendation_tracking_view.py`: wrap that module's queries at lines 88 and 144–150

   A by-id fetch that finds a SHADOW row must take the function's existing not-found path.

b) In `api/routers/recommendation_detail.py`, add:

```python
def _require_published(recommendationId: int, db: Session = Depends(get_db)) -> None:
    """SPG-001: a SHADOW call has no public detail route."""
    generation = db.get(RecommendationGeneration, recommendationId)
    if generation is None or generation.prediction_id is None:
        return  # the route's own resolver reports not-found
    state = db.scalar(select(Prediction.publication_state).where(Prediction.id == generation.prediction_id))
    if state != PUBLICATION_PUBLISHED:
        raise NotFoundError("Recommendation", str(recommendationId))
```

   Add `Depends(_require_published)` to the `APIRouter(... dependencies=[...])` list, but only if every route in the file has `{recommendationId}` in its path (check with `grep -n "@router" api/routers/recommendation_detail.py`). Otherwise add `dependencies=[Depends(_require_published)]` to each `@router.get` that has it. `NotFoundError` comes from `api.errors`, as in `api/services/recommendation_detail.py:80`.

c) In `api/schemas/predictions_active.py`, add:

```python
class PublishGateEntry(BaseModel):
    modelVersion: str
    horizonSessions: int
    gateDecision: str
    reason: str | None = None
    stage: str | None = None
    publicationCapability: str
    publishing: bool
    decisionId: int | None = None
    decidedAt: datetime | None = None
    validUntil: datetime | None = None
```

   Also add `publishGate: list[PublishGateEntry] | None = None` to `ActivePredictionsMeta`.

d) In `api/services/predictions_active.py`, add:

```python
def publish_gate_meta(session: Session, *, now: datetime | None = None) -> list[dict]:
    """SPG-001 §14.3: per reported pair, the gate decision and whether that model may actually publish."""
    now = now or datetime.now(timezone.utc)
    entries = []
    for model_version, horizon in gate_pairs():
        latest = latest_decision(session, model_version=model_version, horizon_sessions=horizon)
        auth = authorize_publication(session, model_version=model_version, horizon_sessions=horizon, at=now,
                                     artefact_sha256=latest.artefact_sha256 if latest is not None else None)
        capability = publication_capability(model_version)
        entries.append({
            "modelVersion": model_version,
            "horizonSessions": horizon,
            "gateDecision": auth.state,
            "reason": display_reason(auth.reason),
            "stage": latest.primary_stage if latest is not None and auth.reason == latest.primary_reason else None,
            "publicationCapability": capability,
            "publishing": auth.state == DECISION_PUBLISH and capability == CAPABILITY_PUBLISHABLE,
            "decisionId": auth.decision_id,
            "decidedAt": latest.decided_at if latest is not None else None,
            "validUntil": latest.valid_until if latest is not None else None,
        })
    return entries
```

   In `api/routers/predictions_active.py::get_active_predictions`, after the `latestScan` line, add `body["meta"]["publishGate"] = publish_gate_meta(db)`.

- [ ] **Step 4: Run; expect PASS**, then the owning suites:

```bash
python -m pytest tests/test_selection_gate_public_surfaces.py tests/test_api_predictions_active.py tests/test_api_recommendations.py tests/test_api_opportunities.py tests/test_api_dashboard.py tests/test_api_instrument_calls.py tests/test_api_recommendation_detail.py tests/test_api_tracking.py tests/test_api_recommendations_snooze.py -q
```

- [ ] **Step 5: Commit**

```bash
git add api app tests/test_selection_gate_public_surfaces.py
git commit -m "SPG-001: public routes show published calls only; meta.publishGate separates decision from publication"
```

---

### Task 10: Dataset SEL-DS-001

**Files:**
- Create: `app/selection_gate/dataset.py`
- Test: `tests/test_selection_gate_dataset.py`

**Interfaces:**
- Consumes:
  - `stock_session_table`, `bars_frame` (Task 6); `final_bars_only`; `ratio_actions_for`, `cumulative_ratio`
  - `cross_sectional_features`, `_regime_for`, `ExtendedStockFeatures`, `_quantize`, `_feature_float`, `_SIX_PLACES`, `as_utc` (`app/walk_forward_dataset.py`)
  - `is_equity_instrument`, `is_rights_entitlement`
- Produces:
  - `FEATURE_COLUMNS = EXTENDED_FEATURE_COLUMNS`
  - `SelectionDataset(anchors, session_dates, horizons, rows, benchmarks: dict[int, pd.DataFrame], sha256_by_horizon: dict[int, str], stocks_by_year: dict[int, int])`
  - `rows` has one row per (D, s ∈ U(D)), sorted by `(session_index, stock_id)`. Columns: `session_index`, `stock_id`, `*FEATURE_COLUMNS` (float32), `baseline_score`, `baseline_score_live`, `entry_open`, and for each h `exit_close_{h}`, `gross_return_{h}`, `label_status_{h}`.
  - `SelectionDataset.failed_stocks: tuple[int, ...]`, stock ids skipped when `skip_failed_stocks=True` (shadow only; the gate passes `False` and fails closed)
  - `benchmarks[h]` columns: `session_index`, `session_date`, `eligible_count`, `labelled_count`, `unresolved` (dict), `excluded` (dict), `benchmark_return` (float or NaN), `benchmarkable` (bool)
  - `session_list(session, *, min_stocks) -> tuple[datetime, ...]`
  - `build_selection_dataset(session, *, horizons, keep_session_indices=None, skip_failed_stocks=False) -> SelectionDataset`

- [ ] **Step 1: Write the failing test** `tests/test_selection_gate_dataset.py`

```python
from datetime import date
from decimal import Decimal

import numpy as np
import pytest
from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker

from app.db import Base
from app.models import CorporateAction, MarketPrice
from app.corporate_action_adjustment import ratio_actions_for
from app.selection_gate.constants import (
    EXCLUDED_BELOW_LIQUIDITY_FLOOR, EXCLUDED_NOT_EQUITY, LABEL_ENTRY_BAR_MISSING, LABEL_EXIT_BAR_MISSING, LABEL_INVALID_PRICE,
    LABEL_NOT_YET_RESOLVABLE, LABEL_RESOLVED, LABEL_SUSPECT_RETURN,
)
import math

from app.selection_gate.dataset import FEATURE_COLUMNS, build_selection_dataset
from app.settings import settings
from app.walk_forward_dataset import (
    _regime_for, cross_sectional_features, extended_point_in_time_features, load_bars, session_cutoff,
)
from tests._selection_gate_factories import seed_stock_with_bars, weekday_sessions

SESSIONS = weekday_sessions(date(2026, 9, 30), 40)


@pytest.fixture
def session(monkeypatch):
    for name, value in {"selection_min_session_stocks": 1, "selection_min_benchmark_stocks": 1,
                        "selection_min_history_bars": 21, "selection_min_median_traded_value_20d": 0.0}.items():
        monkeypatch.setattr(settings, name, value)
    engine = create_engine("sqlite:///:memory:")
    Base.metadata.create_all(engine)
    with sessionmaker(bind=engine)() as db:
        yield db


def _bar(session, stock, index):
    return session.query(MarketPrice).filter_by(stock_id=stock.id, timestamp=session_cutoff(SESSIONS[index])).one()


def _row(dataset, stock, index):
    rows = dataset.rows
    return rows[(rows.stock_id == stock.id) & (rows.session_index == index)].iloc[0]


def test_entry_is_the_next_open_and_exit_the_horizon_close(session):
    a = seed_stock_with_bars(session, symbol="AAA", sessions=SESSIONS)
    dataset = build_selection_dataset(session, horizons=(3,))
    row = _row(dataset, a, 25)
    expected = float(_bar(session, a, 28).close) / float(_bar(session, a, 26).open) - 1
    assert row.entry_open == pytest.approx(float(_bar(session, a, 26).open))
    assert row.gross_return_3 == pytest.approx(expected) and row.label_status_3 == LABEL_RESOLVED


def test_exit_is_brought_onto_the_entry_basis_across_a_split(session):
    b = seed_stock_with_bars(session, symbol="BBB", sessions=SESSIONS)
    for index in range(30, 40):
        bar = _bar(session, b, index)
        bar.open, bar.high, bar.low, bar.close = (bar.open / 2, bar.high / 2, bar.low / 2, bar.close / 2)
        bar.volume = bar.volume * 2
    session.add(CorporateAction(stock_id=b.id, action_type="SPLIT", effective_date=SESSIONS[30], ratio=Decimal("2")))
    session.flush()
    dataset = build_selection_dataset(session, horizons=(3,))
    row = _row(dataset, b, 28)  # entry 29 pre-split, exit 31 post-split
    raw = float(_bar(session, b, 31).close) * 2 / float(_bar(session, b, 29).open) - 1
    assert row.gross_return_3 == pytest.approx(raw)
    assert abs(row.gross_return_3) < 0.05


def test_split_on_entry_session_is_not_double_adjusted(session):
    b = seed_stock_with_bars(session, symbol="BBB", sessions=SESSIONS)
    for index in range(30, 40):
        bar = _bar(session, b, index)
        bar.open, bar.high, bar.low, bar.close = (bar.open / 2, bar.high / 2, bar.low / 2, bar.close / 2)
    session.add(CorporateAction(stock_id=b.id, action_type="SPLIT", effective_date=SESSIONS[30], ratio=Decimal("2")))
    session.flush()
    row = _row(build_selection_dataset(session, horizons=(3,)), b, 29)  # entry session 30 is the ex-date
    assert row.gross_return_3 == pytest.approx(float(_bar(session, b, 32).close) / float(_bar(session, b, 30).open) - 1)


def test_each_label_status(session):
    a = seed_stock_with_bars(session, symbol="AAA", sessions=SESSIONS)
    b = seed_stock_with_bars(session, symbol="BBB", sessions=SESSIONS)  # keeps every session in S
    session.delete(_bar(session, a, 30))
    _bar(session, a, 26).close = _bar(session, a, 26).close * 3
    _bar(session, a, 26).high = _bar(session, a, 26).close
    _bar(session, b, 34).open = Decimal("0")
    session.flush()
    dataset = build_selection_dataset(session, horizons=(1, 3))
    assert _row(dataset, a, 29).label_status_1 == LABEL_ENTRY_BAR_MISSING
    assert _row(dataset, a, 27).label_status_3 == LABEL_EXIT_BAR_MISSING
    assert _row(dataset, a, 25).label_status_1 == LABEL_SUSPECT_RETURN
    assert _row(dataset, a, 39).label_status_1 == LABEL_NOT_YET_RESOLVABLE
    assert _row(dataset, b, 33).label_status_1 == LABEL_INVALID_PRICE
    assert _row(dataset, b, 20).label_status_1 == LABEL_RESOLVED


def test_benchmark_is_the_mean_over_resolved_eligible_rows_with_counts(session):
    a = seed_stock_with_bars(session, symbol="AAA", sessions=SESSIONS, drift=0.002)
    b = seed_stock_with_bars(session, symbol="BBB", sessions=SESSIONS, drift=-0.001)
    seed_stock_with_bars(session, symbol="IDX", sessions=SESSIONS, instrument_key="NSE_INDEX|Nifty 50")
    dataset = build_selection_dataset(session, horizons=(3,))
    bench = dataset.benchmarks[3].set_index("session_index").loc[25]
    expected = np.mean([_row(dataset, a, 25).gross_return_3, _row(dataset, b, 25).gross_return_3])
    assert bench.benchmark_return == pytest.approx(expected)
    assert (bench.eligible_count, bench.labelled_count, bench.excluded) == (2, 2, {EXCLUDED_NOT_EQUITY: 1})
    assert bool(bench.benchmarkable)


def test_the_liquidity_floor_applies_before_the_benchmark(session, monkeypatch):
    monkeypatch.setattr(settings, "selection_min_median_traded_value_20d", 10_000_000.0)
    liquid = seed_stock_with_bars(session, symbol="AAA", sessions=SESSIONS, volume=1_000_000)
    seed_stock_with_bars(session, symbol="THIN", sessions=SESSIONS, volume=10)
    dataset = build_selection_dataset(session, horizons=(3,))
    bench = dataset.benchmarks[3].set_index("session_index").loc[25]
    assert (bench.eligible_count, bench.excluded) == (1, {EXCLUDED_BELOW_LIQUIDITY_FLOOR: 1})
    assert bench.benchmark_return == pytest.approx(_row(dataset, liquid, 25).gross_return_3)


def test_every_dataset_feature_row_matches_the_point_in_time_reference(session):
    """The per-stock optimisation is approved only while every stock-day row equals the reference functions."""
    stocks = [seed_stock_with_bars(session, symbol=f"S{k}", sessions=SESSIONS, drift=0.001 * (k - 1),
                                   sector="TECH" if k < 2 else "BANK") for k in range(4)]
    split = stocks[3]
    for index in range(30, 40):
        bar = _bar(session, split, index)
        bar.open, bar.high, bar.low, bar.close = (bar.open / 2, bar.high / 2, bar.low / 2, bar.close / 2)
        bar.volume = bar.volume * 2
    session.add(CorporateAction(stock_id=split.id, action_type="SPLIT", effective_date=SESSIONS[30], ratio=Decimal("2")))
    session.flush()
    dataset = build_selection_dataset(session, horizons=(3,))
    assert len(dataset.rows) > 0
    for index, group in dataset.rows.groupby("session_index"):
        cutoff = dataset.anchors[index]
        seen = []
        extended = {}
        for stock in stocks:
            if stock.id in set(group.stock_id):
                extended[stock.id] = extended_point_in_time_features(
                    load_bars(session, stock.id), cutoff, stock_id=stock.id,
                    actions=ratio_actions_for(session, stock.id), observer=lambda s, c, t: seen.append((c, t)))
        assert all(t <= c for c, t in seen)
        regime = _regime_for(e.as_point_in_time() for e in extended.values())
        cross = cross_sectional_features(extended, equity_stock_ids=set(extended),
                                         sector_by_stock={s.id: s.sector for s in stocks}, regime=regime)
        for row in group.itertuples():
            expected_values = {**extended[row.stock_id].values, **cross[row.stock_id]}
            for name in FEATURE_COLUMNS:
                expected, actual = expected_values[name], float(getattr(row, name))
                assert math.isnan(actual) if expected is None else actual == pytest.approx(expected, rel=1e-6, abs=1e-6), (
                    index, row.stock_id, name)


def test_dataset_hash_is_stable_and_moves_with_the_data(session):
    a = seed_stock_with_bars(session, symbol="AAA", sessions=SESSIONS)
    first = build_selection_dataset(session, horizons=(3,)).sha256_by_horizon[3]
    assert first == build_selection_dataset(session, horizons=(3,)).sha256_by_horizon[3]
    _bar(session, a, 35).close = _bar(session, a, 35).close + Decimal("1")
    session.flush()
    assert build_selection_dataset(session, horizons=(3,)).sha256_by_horizon[3] != first


def test_provisional_bars_never_enter_the_dataset(session):
    a = seed_stock_with_bars(session, symbol="AAA", sessions=SESSIONS)
    _bar(session, a, 39).source = "upstox-v3-quote-ohlc"
    session.flush()
    dataset = build_selection_dataset(session, horizons=(1,))
    assert dataset.session_dates[-1] == SESSIONS[38]  # the only bar at session 39 is provisional
```

If `CorporateAction` requires more fields, copy them from `tests/test_corporate_actions.py`, and use the `action_type` value that `RATIO_ACTION_TYPES` accepts (`app/corporate_action_adjustment.py`).

- [ ] **Step 2: Run; expect FAIL.** Run: `python -m pytest tests/test_selection_gate_dataset.py -q`

- [ ] **Step 3: Implement** `app/selection_gate/dataset.py`

```python
"""SPG-001 §7: dataset SEL-DS-001 -- universe rows, next-open labels, equal-weighted benchmarks, and a hash."""
from __future__ import annotations

import hashlib
import logging
from collections import Counter, defaultdict
from dataclasses import dataclass
from datetime import date, datetime
from decimal import Decimal
from typing import Iterable, Sequence

import numpy as np
import pandas as pd
from sqlalchemy import func, select
from sqlalchemy.orm import Session

from app.corporate_action_adjustment import cumulative_ratio, ratio_actions_for
from app.features.technical import EXTENDED_CROSS_SECTIONAL_FEATURES, EXTENDED_FEATURE_COLUMNS
from app.instrument_class import is_equity_instrument
from app.market_data.bar_finality import final_bars_only
from app.market_data.freshness import session_date_of
from app.models import MarketPrice, Stock
from app.settings import settings
from app.universe_integrity import is_rights_entitlement
from app.walk_forward_dataset import (
    _SIX_PLACES, ExtendedStockFeatures, _feature_float, _quantize, _regime_for, as_utc, cross_sectional_features,
)

from .constants import (
    LABEL_ENTRY_BAR_MISSING, LABEL_EXIT_BAR_MISSING, LABEL_INVALID_PRICE, LABEL_NOT_YET_RESOLVABLE, LABEL_RESOLVED,
    LABEL_SUSPECT_RETURN,
)
from .features import bars_frame
from .universe import stock_session_table

FEATURE_COLUMNS = EXTENDED_FEATURE_COLUMNS
_HASH_CHUNK = 200_000
logger = logging.getLogger(__name__)


@dataclass(frozen=True)
class SelectionDataset:
    anchors: tuple[datetime, ...]
    session_dates: tuple[date, ...]
    horizons: tuple[int, ...]
    rows: pd.DataFrame
    benchmarks: dict[int, pd.DataFrame]
    sha256_by_horizon: dict[int, str]
    stocks_by_year: dict[int, int]
    failed_stocks: tuple[int, ...] = ()


def session_list(session: Session, *, min_stocks: int) -> tuple[datetime, ...]:
    """S: official-bar anchors carrying bars for at least `min_stocks` distinct stocks (§5)."""
    rows = session.execute(
        select(MarketPrice.timestamp, func.count(func.distinct(MarketPrice.stock_id)))
        .where(final_bars_only())
        .group_by(MarketPrice.timestamp)
        .order_by(MarketPrice.timestamp.asc())
    ).all()
    return tuple(as_utc(anchor) for anchor, count in rows if count >= min_stocks)


def _official_bars(session: Session, stock_id: int) -> pd.DataFrame:
    return bars_frame(session.execute(
        select(MarketPrice.timestamp, MarketPrice.open, MarketPrice.high, MarketPrice.low, MarketPrice.close,
               MarketPrice.volume)
        .where(MarketPrice.stock_id == stock_id, final_bars_only())
        .order_by(MarketPrice.timestamp.asc())
    ).all())


def _labels(opens: np.ndarray, closes: np.ndarray, session_index: np.ndarray, actions, dates: Sequence[date],
            horizons: Iterable[int], last_index: int) -> dict[str, np.ndarray]:
    """§7.3: entry at the D+1 open, exit at the D+h close on the entry basis; first failing status wins."""
    n = len(session_index)
    position_of = {int(i): p for p, i in enumerate(session_index)}
    entry_pos = np.array([position_of.get(int(i) + 1, -1) for i in session_index], dtype=np.int64)
    entry_open = np.where(entry_pos >= 0, opens[entry_pos], np.nan) if n else np.array([])
    out: dict[str, np.ndarray] = {"entry_open": entry_open}
    for h in horizons:
        exit_pos = np.array([position_of.get(int(i) + h, -1) for i in session_index], dtype=np.int64)
        exit_close = np.where(exit_pos >= 0, closes[exit_pos], np.nan) if n else np.array([])
        if actions:
            for k in np.flatnonzero((entry_pos >= 0) & (exit_pos >= 0)):
                i = int(session_index[k])
                exit_close[k] *= float(cumulative_ratio(actions, after=dates[i + 1], through=dates[i + h]))
        with np.errstate(divide="ignore", invalid="ignore"):
            gross = exit_close / entry_open - 1.0
        status = np.full(n, LABEL_RESOLVED, dtype=object)
        status[(gross < settings.selection_min_plausible_return) | (gross > settings.selection_max_plausible_return)] = LABEL_SUSPECT_RETURN
        status[~(entry_open > 0) | ~(exit_close > 0)] = LABEL_INVALID_PRICE
        status[exit_pos < 0] = LABEL_EXIT_BAR_MISSING
        status[entry_pos < 0] = LABEL_ENTRY_BAR_MISSING
        status[session_index + h > last_index] = LABEL_NOT_YET_RESOLVABLE
        gross = np.where(status == LABEL_RESOLVED, gross, np.nan)
        out[f"exit_close_{h}"], out[f"gross_return_{h}"], out[f"label_status_{h}"] = exit_close, gross, status
    return out


def _add_cross_sectional(rows: pd.DataFrame, sector_by_stock: dict[int, str | None]) -> None:
    """FV-002's cross-sectional and regime columns, computed across U(D) only, via the WF-002 functions."""
    columns = {name: np.full(len(rows), np.nan) for name in EXTENDED_CROSS_SECTIONAL_FEATURES}
    for _, group in rows.groupby("session_index", sort=True):
        extended = {
            int(sid): ExtendedStockFeatures(
                close=Decimal("0"), predicted_probability=Decimal("0"),
                # Quantised from the 6-place float, exactly as extended_point_in_time_features does.
                sma20_distance=_quantize(_feature_float(float(sma)), _SIX_PLACES),
                volume_ratio_20d=_quantize(_feature_float(float(vr)), _SIX_PLACES),
                atr_percent=_quantize(_feature_float(float(atr)), _SIX_PLACES),
                values={"return_20d": _feature_float(float(r20)), "volume_ratio_20d": _feature_float(float(vr))},
            )
            for sid, r20, vr, sma, atr in zip(group.stock_id, group.return_20d, group.volume_ratio_20d,
                                              group.sma20_distance, group.atr_percent)
        }
        regime = _regime_for(ext.as_point_in_time() for ext in extended.values())
        cross = cross_sectional_features(extended, equity_stock_ids=set(extended), sector_by_stock=sector_by_stock,
                                         regime=regime)
        positions = group.index.to_numpy()
        for name in EXTENDED_CROSS_SECTIONAL_FEATURES:
            values = [cross[int(sid)][name] for sid in group.stock_id]
            columns[name][positions] = [np.nan if v is None else v for v in values]
    for name, values in columns.items():
        rows[name] = values


def _benchmarks(rows: pd.DataFrame, excluded: dict[int, Counter], dates: Sequence[date], horizon: int,
                session_indices: Iterable[int]) -> pd.DataFrame:
    status_column, gross_column = f"label_status_{horizon}", f"gross_return_{horizon}"
    grouped = {int(i): g for i, g in rows.groupby("session_index", sort=True)}
    records = []
    for index in sorted(set(session_indices)):
        group = grouped.get(index)
        statuses = Counter() if group is None else Counter(group[status_column])
        labelled = statuses.pop(LABEL_RESOLVED, 0)
        resolved = None if group is None else group.loc[group[status_column] == LABEL_RESOLVED, gross_column]
        records.append({
            "session_index": index, "session_date": dates[index],
            "eligible_count": 0 if group is None else len(group), "labelled_count": labelled,
            "unresolved": dict(sorted(statuses.items())), "excluded": dict(sorted(excluded.get(index, Counter()).items())),
            "benchmark_return": float(resolved.mean()) if labelled else np.nan,
            "benchmarkable": labelled >= settings.selection_min_benchmark_stocks,
        })
    return pd.DataFrame.from_records(records)


def _sha256(rows: pd.DataFrame, dates: Sequence[date], horizon: int) -> str:
    """§7.5: SHA-256 of `session_date,stock_id,horizon,label_status,o,x,r,<features>,baseline_score` lines."""
    frame = pd.DataFrame({
        "session_date": [dates[i].isoformat() for i in rows.session_index],
        "stock_id": rows.stock_id.to_numpy(), "horizon": horizon,
        "label_status": rows[f"label_status_{horizon}"].to_numpy(), "o": rows.entry_open.to_numpy(),
        "x": rows[f"exit_close_{horizon}"].to_numpy(), "r": rows[f"gross_return_{horizon}"].to_numpy(),
        **{name: rows[name].to_numpy() for name in FEATURE_COLUMNS}, "baseline_score": rows.baseline_score.to_numpy(),
    })
    digest = hashlib.sha256()
    for start in range(0, len(frame), _HASH_CHUNK):
        chunk = frame.iloc[start:start + _HASH_CHUNK].to_csv(
            header=False, index=False, float_format="%.8f", na_rep="", lineterminator="\n"
        )
        digest.update(chunk.encode("utf-8"))
    return digest.hexdigest()


def build_selection_dataset(session: Session, *, horizons: Sequence[int],
                            keep_session_indices: Iterable[int] | None = None,
                            skip_failed_stocks: bool = False) -> SelectionDataset:
    horizons = tuple(horizons)
    anchors = session_list(session, min_stocks=settings.selection_min_session_stocks)
    dates = tuple(session_date_of(anchor) for anchor in anchors)
    index_of = {pd.Timestamp(anchor): i for i, anchor in enumerate(anchors)}
    last_index = len(anchors) - 1
    keep = None if keep_session_indices is None else set(keep_session_indices)
    parts: list[pd.DataFrame] = []
    excluded: dict[int, Counter] = defaultdict(Counter)
    years: dict[int, set[int]] = defaultdict(set)
    sector_by_stock: dict[int, str | None] = {}
    failed: list[int] = []

    for stock in session.scalars(select(Stock).order_by(Stock.id)).all():
        try:
            part = _stock_part(session, stock, index_of, dates, horizons, last_index, keep, excluded, years)
        except Exception:
            if not skip_failed_stocks:
                raise  # the gate fails closed: a missing stock would bias the universe
            logger.exception("SPG-001 dataset: stock %s skipped", stock.id)
            failed.append(stock.id)
            continue
        if part is not None:
            parts.append(part)
            sector_by_stock[stock.id] = stock.sector

    if not parts:
        raise ValueError("SPG-001: no stock-session is in the gate universe")
    rows = pd.concat(parts, ignore_index=True)
    rows = rows.sort_values(["session_index", "stock_id"], kind="mergesort").reset_index(drop=True)
    _add_cross_sectional(rows, sector_by_stock)
    rows[list(FEATURE_COLUMNS)] = rows[list(FEATURE_COLUMNS)].astype(np.float32)
    session_indices = set(rows.session_index.astype(int)) | set(excluded)
    return SelectionDataset(
        anchors=anchors, session_dates=dates, horizons=horizons, rows=rows,
        benchmarks={h: _benchmarks(rows, excluded, dates, h, session_indices) for h in horizons},
        sha256_by_horizon={h: _sha256(rows, dates, h) for h in horizons},
        stocks_by_year={year: len(ids) for year, ids in sorted(years.items())},
        failed_stocks=tuple(failed),
    )


def _stock_part(session, stock, index_of, dates, horizons, last_index, keep, excluded, years) -> pd.DataFrame | None:
        bars = _official_bars(session, stock.id)
        mapped = bars["timestamp"].map(index_of)
        bars = bars[mapped.notna()].reset_index(drop=True)
        if bars.empty:
            return None
        session_index = mapped.dropna().astype(np.int64).to_numpy()
        actions = ratio_actions_for(session, stock.id)
        table = stock_session_table(
            bars, actions, is_equity=is_equity_instrument(stock.instrument_key),
            is_rights=is_rights_entitlement(stock.symbol), min_history_bars=settings.selection_min_history_bars,
            min_median_traded_value=settings.selection_min_median_traded_value_20d,
        )
        reasons = table["exclusion_reason"].to_numpy()
        for index, reason in zip(session_index, reasons):
            if reason is not None and (keep is None or int(index) in keep):
                excluded[int(index)][reason] += 1
        for index in session_index:
            years[dates[int(index)].year].add(stock.id)
        labels = _labels(table["open"].to_numpy(), table["close"].to_numpy(), session_index, actions, dates,
                         horizons, last_index)
        mask = np.array([reason is None for reason in reasons])
        if keep is not None:
            mask &= np.isin(session_index, list(keep))
        if not mask.any():
            return None
        part = table.loc[mask, ["baseline_score", "baseline_score_live",
                                *[c for c in table.columns if c in FEATURE_COLUMNS]]].copy()
        part.insert(0, "stock_id", stock.id)
        part.insert(0, "session_index", session_index[mask])
        for name, values in labels.items():
            part[name] = values[mask]
        return part
```

`_stock_part` is the former loop body. Keep its lines at one indentation level (dedent the block when writing it); it returns the stock's in-universe part or `None`.

Memory note for the executor: production is about 4.5M official rows. Features are float32, and only in-universe rows are kept. Expect about 1.5–2.5 GiB peak inside the 6 GiB job limit.

- [ ] **Step 4: Run; expect PASS.** Run: `python -m pytest tests/test_selection_gate_dataset.py -q`

- [ ] **Step 5: Commit**

```bash
git add app/selection_gate/dataset.py tests/test_selection_gate_dataset.py
git commit -m "SPG-001: dataset SEL-DS-001 with next-open labels, universe benchmarks and a stable hash"
```

---
### Task 11: Validation windows, SEL-001 model, holdout enforcement

**Files:**
- Create: `app/selection_gate/validation.py`, `app/selection_gate/sel001.py`, `app/selection_gate/holdout.py`
- Modify:
  - `app/challenger_training.py::registered_holdouts` (line 355)
  - `app/purged_embargo_validation.py::compute_purged_training_set`, the holdout read at lines 267–270
- Test: `tests/test_selection_gate_validation.py`

**Interfaces:**
- Produces:
  - `Month = tuple[int, int]`; `Windows(horizon, holdout_month, holdout_indices, protected, test_months, month_indices)`
  - `month_of(day) -> Month`; `month_text(month) -> str`; `parse_month(text) -> Month`; `quarter_of(month) -> tuple[int, int]`
  - `plan_windows(dates, horizon, *, today, first_test_month) -> Windows`
  - `protected_rows(session_index, horizon, protected) -> np.ndarray[bool]`
  - `fit_row_mask(session_index, horizon, *, test_start_index, embargo, protected) -> np.ndarray[bool]`
  - `fit_sel001(features, excess) -> XGBRegressor`; `score_sel001(model, features) -> np.ndarray`
  - `holdout_label(model_version, horizon, month) -> str`
  - `register_holdout(session, *, label, window_start, window_end, at) -> HoldoutWindowRegistry`
  - `consume_holdout(session, *, registry, model_version, horizon, month, at) -> bool`
  - `previously_observed(session, *, model_created_at, horizon, month) -> bool`

- [ ] **Step 1: Write the failing test** `tests/test_selection_gate_validation.py`

```python
from datetime import date, datetime, timedelta, timezone

import numpy as np
import pytest
from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker

from app.challenger_training import registered_holdouts
from app.db import Base
from app.models import HoldoutWindowRegistry
from app.selection_gate.config import SEL001_PARAMS
from app.selection_gate.holdout import consume_holdout, holdout_label, previously_observed, register_holdout
from app.selection_gate.sel001 import fit_sel001, score_sel001
from app.selection_gate.validation import fit_row_mask, plan_windows, protected_rows

DATES = [date(2026, 1, 1) + timedelta(days=d) for d in range(0, 101) if (date(2026, 1, 1) + timedelta(days=d)).weekday() < 5]
TODAY = date(2026, 4, 15)
T0 = datetime(2026, 4, 11, 20, 30, tzinfo=timezone.utc)


@pytest.fixture
def session():
    engine = create_engine("sqlite:///:memory:")
    Base.metadata.create_all(engine)
    with sessionmaker(bind=engine)() as db:
        yield db


def test_holdout_is_the_latest_fully_resolved_month_never_the_current_one():
    windows = plan_windows(DATES, 5, today=TODAY, first_test_month="2026-01")
    march = [i for i, d in enumerate(DATES) if (d.year, d.month) == (2026, 3)]
    assert windows.holdout_month == (2026, 3)
    assert windows.protected == (march[0], march[-1] + 5)
    assert windows.test_months == ((2026, 1), (2026, 2))


def test_a_month_is_unused_until_every_exit_session_exists():
    short = [d for d in DATES if d <= date(2026, 4, 3)]  # three April sessions after March
    assert plan_windows(short, 5, today=TODAY, first_test_month="2026-01").holdout_month == (2026, 2)
    assert plan_windows(short, 3, today=TODAY, first_test_month="2026-01").holdout_month == (2026, 3)


def test_no_fit_row_touches_the_protected_span_or_the_embargo():
    windows = plan_windows(DATES, 5, today=TODAY, first_test_month="2026-01")
    index = np.arange(len(DATES))
    starts = [windows.month_indices[m][0] for m in windows.test_months] + [windows.protected[0]]
    for start in starts:
        mask = fit_row_mask(index, 5, test_start_index=start, embargo=2, protected=windows.protected)
        assert not (mask & protected_rows(index, 5, windows.protected)).any()
        assert (index[mask] + 5 < start - 2).all()
        assert not set(index[mask]) & set(windows.holdout_indices)


def test_an_exam_month_is_single_use(session):
    label = holdout_label("SEL-001", 5, (2026, 3))
    registry = register_holdout(session, label=label, window_start=T0, window_end=T0, at=T0)
    assert register_holdout(session, label=label, window_start=T0, window_end=T0, at=T0).id == registry.id
    assert consume_holdout(session, registry=registry, model_version="SEL-001", horizon=5, month=(2026, 3), at=T0)
    assert not consume_holdout(session, registry=registry, model_version="SEL-001", horizon=5, month=(2026, 3), at=T0)


def test_a_model_created_after_a_month_was_examined_has_observed_it(session):
    register_holdout(session, label=holdout_label("BASELINE-001", 5, (2026, 3)), window_start=T0, window_end=T0, at=T0)
    assert previously_observed(session, model_created_at=T0 + timedelta(hours=1), horizon=5, month=(2026, 3))
    assert previously_observed(session, model_created_at=T0 + timedelta(hours=1), horizon=5, month=(2026, 2))
    assert not previously_observed(session, model_created_at=T0 - timedelta(hours=1), horizon=5, month=(2026, 3))
    assert not previously_observed(session, model_created_at=T0 + timedelta(hours=1), horizon=3, month=(2026, 3))


def test_existing_challenger_readers_ignore_gate_exam_rows(session):
    register_holdout(session, label=holdout_label("SEL-001", 5, (2026, 3)), window_start=T0, window_end=T0, at=T0)
    session.add(HoldoutWindowRegistry(label="CHT-HOLDOUT", window_start=T0, window_end=T0, registered_at=T0,
                                      registry_version="CHT-001"))
    session.flush()
    assert [h.label for h in registered_holdouts(session)] == ["CHT-HOLDOUT"]


def test_sel001_fit_is_deterministic_with_fixed_parameters():
    rng = np.random.default_rng(0)
    features, excess = rng.normal(size=(600, 23)), rng.normal(scale=0.02, size=600)
    first = score_sel001(fit_sel001(features, excess), features[:20])
    second = score_sel001(fit_sel001(features, excess), features[:20])
    assert np.array_equal(first, second)
    assert fit_sel001(features, excess).get_params()["min_child_weight"] == SEL001_PARAMS["min_child_weight"]
```

For `compute_purged_training_set`, add one test to `tests/test_purged_embargo_validation.py`. Copy that file's test asserting `HoldoutContaminationError`, set the seeded registry row's `registry_version="SPG-HOLDOUT-001"`, and assert no error is raised.

- [ ] **Step 2: Run; expect FAIL.** Run: `python -m pytest tests/test_selection_gate_validation.py -q`

- [ ] **Step 3: Implement**

`app/selection_gate/validation.py`:

```python
"""SPG-001 §10.1: the held-out month, the walk-forward months, and the rows each fit may use."""
from __future__ import annotations

from dataclasses import dataclass
from datetime import date
from typing import Sequence

import numpy as np

Month = tuple[int, int]


@dataclass(frozen=True)
class Windows:
    horizon: int
    holdout_month: Month | None
    holdout_indices: tuple[int, ...]
    protected: tuple[int, int] | None
    test_months: tuple[Month, ...]
    month_indices: dict[Month, tuple[int, ...]]


def month_of(day: date) -> Month:
    return (day.year, day.month)


def month_text(month: Month) -> str:
    return f"{month[0]:04d}-{month[1]:02d}"


def parse_month(text: str) -> Month:
    year, month = text.split("-")
    return (int(year), int(month))


def quarter_of(month: Month) -> tuple[int, int]:
    return (month[0], (month[1] - 1) // 3 + 1)


def plan_windows(dates: Sequence[date], horizon: int, *, today: date, first_test_month: str) -> Windows:
    by_month: dict[Month, list[int]] = {}
    for index, day in enumerate(dates):
        by_month.setdefault(month_of(day), []).append(index)
    last = len(dates) - 1
    current = month_of(today)
    holdout = next(
        (m for m in sorted(by_month, reverse=True) if m < current and by_month[m][-1] + horizon <= last), None
    )
    holdout_indices = tuple(by_month[holdout]) if holdout else ()
    protected = (holdout_indices[0], holdout_indices[-1] + horizon) if holdout else None
    upper = holdout if holdout else current
    first = parse_month(first_test_month)
    return Windows(
        horizon=horizon, holdout_month=holdout, holdout_indices=holdout_indices, protected=protected,
        test_months=tuple(m for m in sorted(by_month) if first <= m < upper),
        month_indices={m: tuple(v) for m, v in by_month.items()},
    )


def protected_rows(session_index: np.ndarray, horizon: int, protected: tuple[int, int] | None) -> np.ndarray:
    """Rows whose [D, D+h] touches the protected span: out of every fit and every walk-forward test."""
    if protected is None:
        return np.zeros(len(session_index), dtype=bool)
    start, end = protected
    return (session_index <= end) & (session_index + horizon >= start)


def fit_row_mask(session_index: np.ndarray, horizon: int, *, test_start_index: int, embargo: int,
                 protected: tuple[int, int] | None) -> np.ndarray:
    return (session_index + horizon < test_start_index - embargo) & ~protected_rows(session_index, horizon, protected)
```

`app/selection_gate/sel001.py`:

```python
"""SPG-001 §9.1: SEL-001, one fixed-parameter XGBoost regressor per horizon on excess return."""
from __future__ import annotations

import numpy as np
from xgboost import XGBRegressor

from .config import SEL001_PARAMS


def fit_sel001(features: np.ndarray, excess: np.ndarray) -> XGBRegressor:
    low, high = np.percentile(excess, [1, 99])  # winsorised to the training set's own tails
    model = XGBRegressor(**SEL001_PARAMS)
    model.fit(features, np.clip(excess, low, high))
    return model


def score_sel001(model: XGBRegressor, features: np.ndarray) -> np.ndarray:
    return np.asarray(model.predict(features), dtype=float)
```

`app/selection_gate/holdout.py`:

```python
"""SPG-001 §10.2: exam months registered, spent once, and never shown to a model built after them."""
from __future__ import annotations

from datetime import datetime

from sqlalchemy import select
from sqlalchemy.orm import Session

from app.models import HoldoutWindowRegistry, SelectionHoldoutUsage

from .authorization import naive_utc
from .constants import GATE_RULE_VERSION, HOLDOUT_REGISTRY_VERSION
from .validation import Month, month_text, parse_month


def holdout_label(model_version: str, horizon: int, month: Month) -> str:
    return f"{GATE_RULE_VERSION}:{model_version}:h{horizon}:{month_text(month)}"


def register_holdout(session: Session, *, label: str, window_start: datetime, window_end: datetime,
                     at: datetime) -> HoldoutWindowRegistry:
    existing = session.scalar(select(HoldoutWindowRegistry).where(HoldoutWindowRegistry.label == label))
    if existing is not None:
        return existing
    row = HoldoutWindowRegistry(label=label, window_start=window_start, window_end=window_end, registered_at=at,
                                registry_version=HOLDOUT_REGISTRY_VERSION)
    session.add(row)
    session.flush()
    return row


def consume_holdout(session: Session, *, registry: HoldoutWindowRegistry, model_version: str, horizon: int,
                    month: Month, at: datetime) -> bool:
    if session.scalar(select(SelectionHoldoutUsage.id).where(SelectionHoldoutUsage.holdout_label == registry.label)):
        return False
    session.add(SelectionHoldoutUsage(holdout_label=registry.label, registry_id=registry.id,
                                      model_version=model_version, horizon_sessions=horizon,
                                      holdout_month=month_text(month), used_at=at))
    session.flush()
    return True


def previously_observed(session: Session, *, model_created_at: datetime, horizon: int, month: Month) -> bool:
    rows = session.scalars(
        select(HoldoutWindowRegistry).where(HoldoutWindowRegistry.registry_version == HOLDOUT_REGISTRY_VERSION)
    ).all()
    seen = [r.registered_at for r in rows
            if r.label.split(":")[2] == f"h{horizon}" and parse_month(r.label.split(":")[3]) >= month]
    return bool(seen) and naive_utc(model_created_at) > naive_utc(min(seen, key=naive_utc))
```

In `app/challenger_training.py::registered_holdouts`, add a third condition to its `.where(...)`: `HoldoutWindowRegistry.registry_version != HOLDOUT_REGISTRY_VERSION`, with the comment `# SPG-001 exam months are rolling; CHT's consumed-once windows are unchanged`.

In `app/purged_embargo_validation.py` (lines 267–270), make the comprehension condition `h.window_start is not None and h.window_end is not None and h.registry_version != HOLDOUT_REGISTRY_VERSION`.

Both modules import `HOLDOUT_REGISTRY_VERSION` from `app.selection_gate.constants`.

- [ ] **Step 4: Run; expect PASS**

Run: `python -m pytest tests/test_selection_gate_validation.py tests/test_purged_embargo_validation.py tests/test_challenger_training.py tests/test_epic843_history_experiment.py -q`

- [ ] **Step 5: Commit**

```bash
git add app/selection_gate/validation.py app/selection_gate/sel001.py app/selection_gate/holdout.py app/challenger_training.py app/purged_embargo_validation.py tests/test_selection_gate_validation.py tests/test_purged_embargo_validation.py
git commit -m "SPG-001: validation windows, SEL-001 model and single-use holdout exams"
```

---

### Task 12: Stage runner, records, gate runner, report, script and operation

**Files:**
- Create: `app/selection_gate/evaluation.py`, `app/selection_gate/runner.py`, `app/selection_gate/report.py`, `app/selection_gate/metrics.py`, `scripts/run_selection_gate.py`
- Modify:
  - `app/selection_gate/records.py`: add writers
  - `app/schedule_orchestration.py`: two operation constants near line 164, two `TRIGGER_POLICIES` entries after `OPERATION_LEARNING_CYCLE`
- Test: `tests/test_selection_gate_runner.py`

**Interfaces:**
- Consumes: everything from Tasks 1–11; `save_artefact`/`load_artefact` (`app/challenger_artefact.py`), `REGISTRY_ROOT` (`app/model_artefact_registry.py`), `ModelVersion`, `ModelArtefact`, `MODEL_STATUS_SHADOW` (`app/challenger_training.py:82`)
- Produces:
  - `TradeRow`, `StageRun(stage, session_indices, statistics, trades, universe, reduction)`, `run_stage(...) -> StageRun`
  - `write_benchmarks(session, dataset, horizon) -> int`, `write_decision(...) -> SelectionGateDecision`, `write_trades(session, decision, runs, dates, horizon, cost) -> int`
  - `run_selection_gate(session, *, read_session=None, models=GATED_MODEL_VERSIONS, horizons=None, walk_forward_only=False, now=None, code_version=None) -> dict` with keys `"dataset"` and `"pairs"`
  - `render_report(result) -> tuple[str, str]`
  - `scripts.run_selection_gate.read_only_sessionmaker(url)`
  - `OPERATION_SELECTION_GATE = "SELECTION_GATE"`, `OPERATION_SELECTION_SHADOW = "SELECTION_SHADOW"`

- [ ] **Step 1: Write the failing test** `tests/test_selection_gate_runner.py`

```python
from datetime import date, datetime, timedelta, timezone

import numpy as np
import pandas as pd
import pytest
from sqlalchemy import create_engine, func, select, text
from sqlalchemy.exc import OperationalError
from sqlalchemy.orm import sessionmaker

from app.challenger_artefact import load_artefact
from app.db import Base
from app.model_artefact_registry import REGISTRY_ROOT
from app.models import (
    HoldoutWindowRegistry, ModelVersion, SelectionBenchmarkSession, SelectionGateDecision, SelectionGateTrade,
    SelectionHoldoutUsage,
)
from app.selection_gate import runner
from app.selection_gate.config import current_config_sha256
from app.selection_gate.constants import (
    BASELINE_MODEL_VERSION, DECISION_NO_EDGE, DISPOSITION_UNRESOLVED, LABEL_RESOLVED,
    REASON_EVALUATION_FAILED, REASON_HOLDOUT_ALREADY_CONSUMED, REASON_HOLDOUT_PREVIOUSLY_OBSERVED,
    SEL001_FEATURE_VERSION, SEL001_MODEL_VERSION, STAGE_HELD_OUT,
)
from app.selection_gate.evaluation import run_stage
from app.selection_gate.metrics import measured
from app.selection_gate.statistics import StageThresholds
from app.settings import settings
from scripts.run_selection_gate import read_only_sessionmaker
from tests._selection_gate_factories import seed_stock_with_bars, weekday_sessions

SESSIONS = weekday_sessions(date(2026, 6, 30), 130)
NOW = datetime(2026, 7, 11, 20, 30, tzinfo=timezone.utc)
SMALL = {
    "selection_min_session_stocks": 1, "selection_min_benchmark_stocks": 3, "selection_min_history_bars": 25,
    "selection_min_median_traded_value_20d": 0.0, "selection_min_wf_trades": 5, "selection_min_holdout_trades": 3,
    "selection_min_wf_folds": 1, "selection_bootstrap_draws": 200, "selection_top_k": 2,
    "selection_wf_first_test_month": "2026-03", "selection_min_fit_rows": 20,
    "selection_gate_pairs": (("BASELINE-001", 3), ("SEL-001", 3)),
}


@pytest.fixture
def session(monkeypatch):
    for name, value in SMALL.items():
        monkeypatch.setattr(settings, name, value)
    engine = create_engine("sqlite:///:memory:")
    Base.metadata.create_all(engine)
    with sessionmaker(bind=engine)() as db:
        for k in range(8):
            seed_stock_with_bars(db, symbol=f"S{k}", sessions=SESSIONS, drift=0.0005 * (k - 3),
                                 volume=100_000 + 5_000 * k)
        db.commit()
        yield db


def _count(session, model):
    return session.scalar(select(func.count()).select_from(model))


def test_a_full_run_writes_one_auditable_decision_per_pair(session):
    result = runner.run_selection_gate(session, now=NOW, code_version="abc123")
    decisions = session.scalars(select(SelectionGateDecision).order_by(SelectionGateDecision.id)).all()
    assert [(d.model_version, d.horizon_sessions) for d in decisions] == [
        (BASELINE_MODEL_VERSION, 3), (SEL001_MODEL_VERSION, 3),
    ]
    for d in decisions:
        assert d.config_sha256 == current_config_sha256() and d.code_version == "abc123"
        assert d.dataset_sha256 == result["dataset"]["sha256_by_horizon"]["3"]
        assert d.holdout_month == "2026-05"  # June has no 3 sessions after it
        assert d.wf_folds and d.wf_trades is not None and d.ho_result is not None
        trades = session.scalar(select(func.count()).select_from(SelectionGateTrade)
                                .where(SelectionGateTrade.decision_id == d.id))
        assert trades == (d.wf_candidates or 0) + (d.ho_candidates or 0)
    parity = result["pairs"][0]["live_parity"]  # no ratio actions in this fixture: the two paths agree
    assert parity["rows_compared"] > 0 and parity["rows_differing"] == 0 and parity["sessions_top_k_differing"] == 0
    assert result["pairs"][1]["live_parity"] is None
    sel = decisions[1]
    assert sel.artefact_id is not None
    artefact = load_artefact(SEL001_MODEL_VERSION, SEL001_FEATURE_VERSION, root=REGISTRY_ROOT,
                             expected_sha256=sel.artefact_sha256, session=session)
    assert artefact.estimator is not None
    assert _count(session, SelectionHoldoutUsage) == 2
    assert _count(session, SelectionBenchmarkSession) == len(result["dataset"]["benchmark_sessions"]["3"])


def test_a_second_run_on_the_same_month_cannot_reuse_the_exam(session):
    runner.run_selection_gate(session, now=NOW)
    benchmarks = _count(session, SelectionBenchmarkSession)
    runner.run_selection_gate(session, now=NOW + timedelta(hours=1))
    latest = session.scalars(select(SelectionGateDecision).order_by(SelectionGateDecision.id.desc()).limit(2)).all()
    for d in latest:
        assert d.decision == DECISION_NO_EDGE and d.ho_reasons == [REASON_HOLDOUT_ALREADY_CONSUMED]
    assert _count(session, SelectionBenchmarkSession) == benchmarks


def test_a_model_created_after_the_month_was_examined_is_blocked(session):
    runner.run_selection_gate(session, models=(BASELINE_MODEL_VERSION,), now=NOW)
    session.add(ModelVersion(model_name="selection-excess-return", version=SEL001_MODEL_VERSION,
                             feature_version=SEL001_FEATURE_VERSION, status="SHADOW",
                             created_at=NOW + timedelta(hours=1)))
    session.commit()
    runner.run_selection_gate(session, models=(SEL001_MODEL_VERSION,), now=NOW + timedelta(hours=2))
    sel = session.scalar(select(SelectionGateDecision).where(SelectionGateDecision.model_version == SEL001_MODEL_VERSION))
    assert sel.ho_reasons == [REASON_HOLDOUT_PREVIOUSLY_OBSERVED]


def test_walk_forward_only_writes_nothing(session):
    result = runner.run_selection_gate(session, walk_forward_only=True, now=NOW)
    for model in (SelectionGateDecision, SelectionGateTrade, SelectionBenchmarkSession, SelectionHoldoutUsage,
                  HoldoutWindowRegistry):
        assert _count(session, model) == 0
    assert {p["model_version"] for p in result["pairs"]} == {BASELINE_MODEL_VERSION, SEL001_MODEL_VERSION}
    assert all("wf_trades" in p and "ho_trades" not in p for p in result["pairs"])


def test_an_evaluation_failure_is_recorded_and_isolated(session, monkeypatch):
    def boom(*args, **kwargs):
        raise RuntimeError("fit failed")

    monkeypatch.setattr(runner, "fit_sel001", boom)
    runner.run_selection_gate(session, now=NOW)
    by_model = {d.model_version: d for d in session.scalars(select(SelectionGateDecision))}
    assert by_model[SEL001_MODEL_VERSION].primary_reason == REASON_EVALUATION_FAILED
    assert by_model[BASELINE_MODEL_VERSION].primary_reason != REASON_EVALUATION_FAILED


def test_pairs_outside_the_gate_are_refused(session):
    with pytest.raises(ValueError):
        runner.run_selection_gate(session, models=(SEL001_MODEL_VERSION,), horizons=(1,), now=NOW)


def test_measured_records_time_cpu_and_peak_memory():
    with measured() as metrics:
        sum(range(100_000))
    assert metrics["elapsed_seconds"] >= 0 and metrics["cpu_seconds"] >= 0
    assert metrics["peak_memory_mb"] is None or metrics["peak_memory_mb"] > 0


def test_candidates_in_unbenchmarkable_sessions_are_unresolved_not_dropped():
    rows = pd.DataFrame({
        "session_index": [0, 0, 1, 1], "stock_id": [1, 2, 1, 2], "entry_open": [10.0] * 4,
        "exit_close_3": [11.0] * 4, "gross_return_3": [0.1] * 4, "label_status_3": [LABEL_RESOLVED] * 4,
    })
    benchmark = pd.DataFrame({"session_index": [0, 1], "eligible_count": [2, 2], "labelled_count": [2, 2],
                              "excluded": [{}, {}], "benchmark_return": [0.05, 0.05],
                              "benchmarkable": [True, False]})
    thresholds = StageThresholds(min_trades=1, min_net_excess=0.0, max_unresolved_share=1.0, min_folds=None,
                                 cost=0.003, bootstrap_draws=10, bootstrap_seed=42)
    run = run_stage(stage=STAGE_HELD_OUT, model_version=BASELINE_MODEL_VERSION, horizon=3, rows=rows,
                    scores=pd.Series([0.9, 0.1, 0.1, 0.9]), session_indices=[0, 1], benchmark=benchmark,
                    thresholds=thresholds, folds_tested=None, top_k=1)
    assert [(t.session_index, t.disposition) for t in run.trades][-1] == (1, DISPOSITION_UNRESOLVED)
    assert run.statistics.unresolved_trades == 1 and run.reduction["candidates"] == 2


def test_the_read_only_source_refuses_writes(tmp_path):
    url = f"sqlite:///{(tmp_path / 'source.db').as_posix()}"
    engine = create_engine(url)
    with engine.begin() as connection:
        connection.execute(text("CREATE TABLE t (x INTEGER)"))
    with read_only_sessionmaker(url)() as source:
        with pytest.raises(OperationalError):
            source.execute(text("INSERT INTO t VALUES (1)"))
```

- [ ] **Step 2: Run; expect FAIL.** Run: `python -m pytest tests/test_selection_gate_runner.py -q`

- [ ] **Step 3: Implement**

`app/selection_gate/evaluation.py`:

```python
"""SPG-001 §8/§11 for one stage: candidates, reduction, statistics, and the rows to persist."""
from __future__ import annotations

import math
from collections import Counter
from dataclasses import dataclass
from typing import Iterable

import pandas as pd

from .constants import (
    DISPOSITION_ACCEPTED, DISPOSITION_DUPLICATE, DISPOSITION_OVERLAP_SUPPRESSED, DISPOSITION_UNRESOLVED,
    LABEL_RESOLVED,
)
from .reduction import Candidate, reduce_candidates, top_k_candidates
from .statistics import AcceptedTrade, StageStatistics, StageThresholds, evaluate_stage


@dataclass(frozen=True)
class TradeRow:
    stage: str
    session_index: int
    stock_id: int
    rank: int
    score: float
    label_status: str
    entry_open: float | None
    exit_close: float | None
    gross_return: float | None
    benchmark_return: float | None
    disposition: str


@dataclass(frozen=True)
class StageRun:
    stage: str
    session_indices: tuple[int, ...]
    statistics: StageStatistics
    trades: tuple[TradeRow, ...]
    universe: dict
    reduction: dict


def _value(number) -> float | None:
    return None if number is None or (isinstance(number, float) and math.isnan(number)) else float(number)


def run_stage(*, stage: str, model_version: str, horizon: int, rows: pd.DataFrame, scores: pd.Series,
              session_indices: Iterable[int], benchmark: pd.DataFrame, thresholds: StageThresholds,
              folds_tested: int | None, top_k: int) -> StageRun:
    indices = sorted({int(i) for i in session_indices})
    status_column, gross_column, exit_column = f"label_status_{horizon}", f"gross_return_{horizon}", f"exit_close_{horizon}"
    frame = rows.loc[rows.session_index.isin(indices),
                     ["session_index", "stock_id", "entry_open", exit_column, gross_column, status_column]].copy()
    frame["score"] = scores.loc[frame.index].to_numpy(dtype=float)
    top = top_k_candidates(frame[frame.score.notna()], score_column="score", top_k=top_k)
    bench = benchmark.set_index("session_index")

    def benchmarkable(index: int) -> bool:
        return index in bench.index and bool(bench.at[index, "benchmarkable"])

    by_key, candidates = {}, []
    for row in top.itertuples(index=False):
        index, status = int(row.session_index), getattr(row, status_column)
        candidate = Candidate(model_version, horizon, int(row.stock_id), index, int(row.rank), float(row.score),
                              status == LABEL_RESOLVED and benchmarkable(index))
        candidates.append(candidate)
        by_key[(index, candidate.stock_id)] = row
    reduced = reduce_candidates(candidates)

    accepted, trades = [], []
    for candidate, disposition in reduced:
        row = by_key[(candidate.session_index, candidate.stock_id)]
        bench_return = _value(bench.at[candidate.session_index, "benchmark_return"]) if candidate.session_index in bench.index else None
        gross = _value(getattr(row, gross_column))
        if disposition == DISPOSITION_ACCEPTED:
            accepted.append(AcceptedTrade(candidate.session_index, gross, bench_return))
        trades.append(TradeRow(stage, candidate.session_index, candidate.stock_id, candidate.rank, candidate.score,
                               getattr(row, status_column), _value(row.entry_open), _value(getattr(row, exit_column)),
                               gross, bench_return, disposition))
    counts = Counter(disposition for _, disposition in reduced)
    statistics = evaluate_stage(accepted, unresolved_trades=counts[DISPOSITION_UNRESOLVED], folds_tested=folds_tested,
                                horizon=horizon, thresholds=thresholds)

    window = bench.loc[bench.index.intersection(indices)]
    excluded: Counter = Counter()
    for reasons in window.excluded:
        excluded.update(reasons)
    eligible, labelled = int(window.eligible_count.sum()), int(window.labelled_count.sum())
    universe = {
        "sessions": len(indices), "eligible_stock_sessions": eligible,
        "mean_eligible_per_session": eligible / len(indices) if indices else None,
        "labelled_stock_sessions": labelled, "unresolved_stock_sessions": eligible - labelled,
        "excluded_by_reason": dict(sorted(excluded.items())),
    }
    reduction = {
        "candidates": len(reduced), "duplicates_removed": counts[DISPOSITION_DUPLICATE],
        "overlap_suppressed": counts[DISPOSITION_OVERLAP_SUPPRESSED], "unresolved_trades": counts[DISPOSITION_UNRESOLVED],
    }
    return StageRun(stage, tuple(indices), statistics, tuple(trades), universe, reduction)
```

Append to `app/selection_gate/records.py`:

```python
import math
from datetime import datetime, timedelta
from decimal import Decimal
from uuid import uuid4

from sqlalchemy import func, select

from .constants import DATASET_VERSION, GATE_RULE_VERSION, UNIVERSE_RULE_VERSION


def _decimal(value, places: int = 8) -> Decimal | None:
    return None if value is None else Decimal(str(round(float(value), places)))


def write_benchmarks(session, dataset, horizon: int) -> int:
    sha = dataset.sha256_by_horizon[horizon]
    exists = session.scalar(select(func.count()).select_from(SelectionBenchmarkSession).where(
        SelectionBenchmarkSession.dataset_sha256 == sha, SelectionBenchmarkSession.horizon_sessions == horizon))
    if exists:
        return 0
    rows = [
        SelectionBenchmarkSession(
            dataset_version=DATASET_VERSION, dataset_sha256=sha, session_date=r.session_date, horizon_sessions=horizon,
            eligible_count=int(r.eligible_count), labelled_count=int(r.labelled_count), unresolved=r.unresolved,
            excluded=r.excluded, benchmark_return=None if math.isnan(r.benchmark_return) else _decimal(r.benchmark_return),
            benchmarkable=bool(r.benchmarkable),
        )
        for r in dataset.benchmarks[horizon].itertuples(index=False)
    ]
    session.add_all(rows)
    session.flush()
    return len(rows)


def stage_fields(prefix: str, run, statistics) -> dict:
    fields = {
        "min_trades": statistics.min_trades, "trades": statistics.trades,
        "unresolved_trades": statistics.unresolved_trades, "mean_benchmark": _decimal(statistics.mean_benchmark),
        "mean_gross": _decimal(statistics.mean_gross), "cost": _decimal(statistics.cost),
        "mean_net": _decimal(statistics.mean_net), "mean_excess": _decimal(statistics.mean_excess),
        "ci_low": _decimal(statistics.ci_low), "ci_high": _decimal(statistics.ci_high),
        "bootstrap_sessions": statistics.bootstrap_sessions, "result": statistics.result,
        "reasons": list(statistics.reasons),
    }
    if run is not None:
        fields.update({k: v for k, v in run.universe.items() if k != "mean_eligible_per_session"})
        fields["mean_eligible_per_session"] = _decimal(run.universe["mean_eligible_per_session"], 4)
        fields.update(run.reduction)
    return {f"{prefix}_{name}": value for name, value in fields.items()}


def write_decision(session, *, model_version: str, horizon: int, feature_version: str, outcome, snapshot: dict,
                   snapshot_sha: str, code_version: str, decided_at: datetime, validity_days: int,
                   dataset_sha256: str | None, dataset_bounds: tuple, dates, wf: tuple, ho: tuple, folds: list | None,
                   holdout: dict, artefact_id: int | None, artefact_sha256: str | None) -> SelectionGateDecision:
    """`wf`/`ho` are (StageRun | None, StageStatistics | None); `holdout` carries month, sessions, label and registry id."""
    values = dict(
        decision_uuid=str(uuid4()), model_version=model_version, horizon_sessions=horizon,
        gate_rule_version=GATE_RULE_VERSION, universe_rule_version=UNIVERSE_RULE_VERSION,
        dataset_version=DATASET_VERSION, dataset_sha256=dataset_sha256, feature_version=feature_version,
        artefact_id=artefact_id, artefact_sha256=artefact_sha256, config_snapshot=snapshot,
        config_sha256=snapshot_sha, code_version=code_version,
        dataset_first_session=dataset_bounds[0], dataset_last_session=dataset_bounds[1],
        wf_folds=folds, wf_folds_tested=None if folds is None else sum(f["status"] == "TESTED" for f in folds),
        wf_folds_skipped=None if folds is None else sum(f["status"] != "TESTED" for f in folds),
        holdout_month=holdout.get("month"), holdout_first_session=holdout.get("first"),
        holdout_last_session=holdout.get("last"), holdout_label=holdout.get("label"),
        holdout_registry_id=holdout.get("registry_id"),
        decision=outcome.decision, primary_stage=outcome.primary_stage, primary_reason=outcome.primary_reason,
        reasons=list(outcome.reasons), decided_at=decided_at, valid_until=decided_at + timedelta(days=validity_days),
    )
    for prefix, (run, statistics) in (("wf", wf), ("ho", ho)):
        if statistics is not None:
            values.update(stage_fields(prefix, run, statistics))
        if prefix == "wf" and run is not None and run.session_indices:
            values["wf_first_test_session"] = dates[run.session_indices[0]]
            values["wf_last_test_session"] = dates[run.session_indices[-1]]
    row = SelectionGateDecision(**values)
    session.add(row)
    session.flush()
    return row


def write_trades(session, decision: SelectionGateDecision, runs, dates, horizon: int, cost: float) -> int:
    rows = []
    for run in runs:
        for t in run.trades:
            i = t.session_index
            net = None if t.gross_return is None else t.gross_return - cost
            excess = None if net is None or t.benchmark_return is None else net - t.benchmark_return
            rows.append(SelectionGateTrade(
                decision_id=decision.id, stage=t.stage, session_date=dates[i], stock_id=t.stock_id,
                horizon_sessions=horizon, rank=t.rank, score=_decimal(t.score, 10),
                entry_session=dates[i + 1] if i + 1 < len(dates) else None,
                exit_session=dates[i + horizon] if i + horizon < len(dates) else None,
                entry_open=_decimal(t.entry_open, 6), exit_close_adjusted=_decimal(t.exit_close, 6),
                label_status=t.label_status, gross_return=_decimal(t.gross_return),
                benchmark_return=_decimal(t.benchmark_return), cost=_decimal(cost), net_return=_decimal(net),
                excess_return=_decimal(excess), disposition=t.disposition,
            ))
    session.add_all(rows)
    session.flush()
    return len(rows)
```

`app/selection_gate/runner.py`:

```python
"""SPG-001: one gate run -- dataset, walk-forward and held-out stages, decisions -- per (model, horizon)."""
from __future__ import annotations

import logging
from collections import defaultdict
from datetime import datetime, timezone

import numpy as np
import pandas as pd
from sqlalchemy import select
from sqlalchemy.orm import Session

from app.challenger_artefact import save_artefact
from app.challenger_training import MODEL_STATUS_SHADOW
from app.market_data.quality import NSE_TIMEZONE
from app.model_artefact_registry import REGISTRY_ROOT
from app.models import ModelArtefact, ModelVersion
from app.purged_embargo_validation import DEFAULT_EMBARGO_DAYS
from app.settings import settings

from .config import config_sha256, config_snapshot, gate_pairs
from .constants import (
    BASELINE_FEATURE_VERSION, BASELINE_MODEL_VERSION, DECISION_NO_EDGE, DISPOSITION_ACCEPTED, FOLD_TESTED, FOLD_TOO_SMALL,
    GATED_MODEL_VERSIONS, LABEL_RESOLVED, REASON_EVALUATION_FAILED, REASON_HOLDOUT_ALREADY_CONSUMED,
    REASON_HOLDOUT_PREVIOUSLY_OBSERVED, REASON_INSUFFICIENT_EVIDENCE, SEL001_FEATURE_VERSION, SEL001_MODEL_NAME,
    SEL001_MODEL_VERSION, STAGE_HELD_OUT, STAGE_WALK_FORWARD,
)
from .dataset import FEATURE_COLUMNS, SelectionDataset, build_selection_dataset
from .evaluation import StageRun, run_stage
from .holdout import consume_holdout, holdout_label, previously_observed, register_holdout
from .records import write_benchmarks, write_decision, write_trades
from .reduction import top_k_candidates
from .sel001 import fit_sel001, score_sel001
from .statistics import GateOutcome, StageThresholds, decide, failed_stage
from .validation import Windows, fit_row_mask, month_of, month_text, plan_windows, protected_rows, quarter_of

logger = logging.getLogger(__name__)


def _thresholds(stage: str) -> StageThresholds:
    walk_forward = stage == STAGE_WALK_FORWARD
    return StageThresholds(
        min_trades=settings.selection_min_wf_trades if walk_forward else settings.selection_min_holdout_trades,
        min_net_excess=settings.selection_min_net_excess, max_unresolved_share=settings.selection_max_unresolved_share,
        min_folds=settings.selection_min_wf_folds if walk_forward else None, cost=settings.selection_round_trip_cost,
        bootstrap_draws=settings.selection_bootstrap_draws, bootstrap_seed=settings.selection_bootstrap_seed,
    )


def _excess_target(rows: pd.DataFrame, benchmark: pd.DataFrame, horizon: int) -> pd.Series:
    """y = r - b(D,h) on RESOLVED rows of benchmarkable sessions (§9.1); NaN elsewhere."""
    bench = benchmark.set_index("session_index")
    trainable = rows.session_index.map(bench.benchmarkable).fillna(False).astype(bool)
    target = rows[f"gross_return_{horizon}"] - rows.session_index.map(bench.benchmark_return)
    return target.where(trainable & (rows[f"label_status_{horizon}"] == LABEL_RESOLVED))


def _fit(rows: pd.DataFrame, target: pd.Series, mask: np.ndarray):
    usable = mask & target.notna().to_numpy()
    if usable.sum() < settings.selection_min_fit_rows:
        return None, int(usable.sum()), usable
    return fit_sel001(rows.loc[usable, list(FEATURE_COLUMNS)].to_numpy(), target[usable].to_numpy()), int(usable.sum()), usable


def _walk_forward(model_version, dataset: SelectionDataset, windows: Windows, horizon: int, target: pd.Series):
    rows = dataset.rows
    index = rows.session_index.to_numpy()
    scores = pd.Series(np.nan, index=rows.index)
    folds, tested = [], []
    by_quarter: dict[tuple[int, int], list] = defaultdict(list)
    for month in windows.test_months:
        by_quarter[quarter_of(month)].append(month)
    for quarter, months in sorted(by_quarter.items()):
        start = windows.month_indices[months[0]][0]
        model, train_rows, usable = None, 0, np.zeros(len(rows), dtype=bool)
        if model_version == SEL001_MODEL_VERSION:
            mask = fit_row_mask(index, horizon, test_start_index=start, embargo=DEFAULT_EMBARGO_DAYS,
                                protected=windows.protected)
            model, train_rows, usable = _fit(rows, target, mask)
        for month in months:
            sessions = [i for i in windows.month_indices[month]
                        if not protected_rows(np.array([i]), horizon, windows.protected)[0]]
            fold = {"month": month_text(month), "fit_quarter": f"{quarter[0]}Q{quarter[1]}", "train_rows": train_rows,
                    "train_first_session": None, "train_last_session": None, "n": 0, "mean_excess": None}
            if usable.any():
                fold["train_first_session"] = dataset.session_dates[int(index[usable].min())].isoformat()
                fold["train_last_session"] = dataset.session_dates[int(index[usable].max())].isoformat()
            if model_version == SEL001_MODEL_VERSION and model is None:
                folds.append({**fold, "status": FOLD_TOO_SMALL})
                continue
            month_mask = rows.session_index.isin(sessions)
            if model_version == SEL001_MODEL_VERSION:
                scores[month_mask] = score_sel001(model, rows.loc[month_mask, list(FEATURE_COLUMNS)].to_numpy())
            else:
                scores[month_mask] = rows.loc[month_mask, "baseline_score"]
            tested.extend(sessions)
            folds.append({**fold, "status": FOLD_TESTED})
    return scores, folds, tested


def _fold_results(folds: list, run: StageRun, dates, cost: float) -> None:
    by_month = defaultdict(list)
    for t in run.trades:
        if t.disposition == DISPOSITION_ACCEPTED:
            by_month[month_text(month_of(dates[t.session_index]))].append(t.gross_return - cost - t.benchmark_return)
    for fold in folds:
        values = by_month.get(fold["month"], [])
        fold["n"], fold["mean_excess"] = len(values), (float(np.mean(values)) if values else None)


def _model_created_at(session: Session, model_version: str, at: datetime) -> datetime | None:
    row = session.scalar(select(ModelVersion).where(ModelVersion.version == model_version))
    if row is None and model_version == SEL001_MODEL_VERSION:
        row = ModelVersion(model_name=SEL001_MODEL_NAME, version=SEL001_MODEL_VERSION,
                           feature_version=SEL001_FEATURE_VERSION, status=MODEL_STATUS_SHADOW, created_at=at)
        session.add(row)
        session.flush()
    return None if row is None else row.created_at  # BASELINE-001 is fixed code with nothing to fit


def _evaluate_pair(session, dataset: SelectionDataset, windows: Windows, *, model_version, horizon, now,
                   walk_forward_only, snapshot, snapshot_sha, code_version) -> dict:
    rows, benchmark, dates = dataset.rows, dataset.benchmarks[horizon], dataset.session_dates
    cost = settings.selection_round_trip_cost
    target = _excess_target(rows, benchmark, horizon)
    month = windows.holdout_month
    registry, blocked = None, None
    if not walk_forward_only and month is not None:
        created_at = _model_created_at(session, model_version, now)
        if created_at is not None and previously_observed(session, model_created_at=created_at, horizon=horizon,
                                                          month=month):
            blocked = REASON_HOLDOUT_PREVIOUSLY_OBSERVED
        else:
            registry = register_holdout(
                session, label=holdout_label(model_version, horizon, month),
                window_start=dataset.anchors[windows.protected[0]], window_end=dataset.anchors[windows.protected[1]],
                at=now,
            )

    scores, folds, tested = _walk_forward(model_version, dataset, windows, horizon, target)
    wf_run = run_stage(stage=STAGE_WALK_FORWARD, model_version=model_version, horizon=horizon, rows=rows,
                       scores=scores, session_indices=tested, benchmark=benchmark,
                       thresholds=_thresholds(STAGE_WALK_FORWARD),
                       folds_tested=sum(f["status"] == FOLD_TESTED for f in folds), top_k=settings.selection_top_k)
    _fold_results(folds, wf_run, dates, cost)
    parity = _live_parity(dataset, horizon, tested, benchmark) if model_version == BASELINE_MODEL_VERSION else None
    if walk_forward_only:
        from .records import stage_fields
        return {"model_version": model_version, "horizon_sessions": horizon, "wf_folds": folds,
                "live_parity": parity, **stage_fields("wf", wf_run, wf_run.statistics)}

    ho_threshold = _thresholds(STAGE_HELD_OUT)
    ho_run, ho_stats, artefact_id, artefact_sha = None, None, None, None
    if month is None:
        ho_stats = failed_stage(REASON_INSUFFICIENT_EVIDENCE, min_trades=ho_threshold.min_trades, cost=cost)
    elif blocked:
        ho_stats = failed_stage(blocked, min_trades=ho_threshold.min_trades, cost=cost)
    else:
        ho_scores = pd.Series(np.nan, index=rows.index)
        held = rows.session_index.isin(windows.holdout_indices)
        ready = True
        if model_version == SEL001_MODEL_VERSION:
            mask = fit_row_mask(rows.session_index.to_numpy(), horizon, test_start_index=windows.protected[0],
                                embargo=DEFAULT_EMBARGO_DAYS, protected=windows.protected)
            model, train_rows, _ = _fit(rows, target, mask)
            if model is None:
                ready = False
                ho_stats = failed_stage(REASON_INSUFFICIENT_EVIDENCE, min_trades=ho_threshold.min_trades, cost=cost)
            else:
                artefact = save_artefact(model, model_version=SEL001_MODEL_VERSION,
                                         feature_version=SEL001_FEATURE_VERSION,
                                         manifest={"gate_rule_version": "SPG-001", "horizon_sessions": horizon,
                                                   "holdout_month": month_text(month), "train_rows": train_rows,
                                                   "dataset_sha256": dataset.sha256_by_horizon[horizon],
                                                   "config_sha256": snapshot_sha},
                                         root=REGISTRY_ROOT, session=session)
                artefact_sha = artefact.sha256
                artefact_id = session.scalar(select(ModelArtefact.id).where(
                    ModelArtefact.model_version == SEL001_MODEL_VERSION,
                    ModelArtefact.feature_version == SEL001_FEATURE_VERSION, ModelArtefact.sha256 == artefact_sha))
                ho_scores[held] = score_sel001(model, rows.loc[held, list(FEATURE_COLUMNS)].to_numpy())
        else:
            ho_scores[held] = rows.loc[held, "baseline_score"]
        if ready:
            # The exam is spent only now, immediately before it is read (§10.2).
            if not consume_holdout(session, registry=registry, model_version=model_version, horizon=horizon,
                                   month=month, at=now):
                ho_stats = failed_stage(REASON_HOLDOUT_ALREADY_CONSUMED, min_trades=ho_threshold.min_trades, cost=cost)
            else:
                ho_run = run_stage(stage=STAGE_HELD_OUT, model_version=model_version, horizon=horizon, rows=rows,
                                   scores=ho_scores, session_indices=windows.holdout_indices, benchmark=benchmark,
                                   thresholds=ho_threshold, folds_tested=None, top_k=settings.selection_top_k)
                ho_stats = ho_run.statistics

    outcome = decide(wf_run.statistics, ho_stats)
    decision = write_decision(
        session, model_version=model_version, horizon=horizon,
        feature_version=SEL001_FEATURE_VERSION if model_version == SEL001_MODEL_VERSION else BASELINE_FEATURE_VERSION,
        outcome=outcome, snapshot=snapshot, snapshot_sha=snapshot_sha, code_version=code_version, decided_at=now,
        validity_days=settings.selection_decision_validity_days, dataset_sha256=dataset.sha256_by_horizon[horizon],
        dataset_bounds=(dates[0], dates[-1]), dates=dates, wf=(wf_run, wf_run.statistics), ho=(ho_run, ho_stats), folds=folds,
        holdout={"month": month_text(month) if month else None,
                 "first": dates[windows.holdout_indices[0]] if month else None,
                 "last": dates[windows.holdout_indices[-1]] if month else None,
                 "label": registry.label if registry else None, "registry_id": registry.id if registry else None},
        artefact_id=artefact_id, artefact_sha256=artefact_sha,
    )
    write_trades(session, decision, [r for r in (wf_run, ho_run) if r is not None], dates, horizon, cost)
    return {**_decision_report(decision), "live_parity": parity}


def _live_parity(dataset: SelectionDataset, horizon: int, tested: list[int], benchmark: pd.DataFrame) -> dict:
    """§9.2: offline (split-adjusted) vs live-path (raw-bar) BASELINE-001 scores. Walk-forward sessions only:
    the held-out month is never scored with a variant nobody decided on."""
    rows = dataset.rows
    window = rows.session_index.isin(tested)
    top_k = settings.selection_top_k
    live = run_stage(stage=STAGE_WALK_FORWARD, model_version=BASELINE_MODEL_VERSION, horizon=horizon, rows=rows,
                     scores=rows.baseline_score_live, session_indices=tested, benchmark=benchmark,
                     thresholds=_thresholds(STAGE_WALK_FORWARD), folds_tested=None, top_k=top_k).statistics

    def picks(column: str) -> dict:
        top = top_k_candidates(rows[window].assign(score=rows.loc[window, column]), score_column="score", top_k=top_k)
        return top.groupby("session_index").stock_id.apply(frozenset).to_dict()

    adjusted, raw = picks("baseline_score"), picks("baseline_score_live")
    return {
        "rows_compared": int(window.sum()),
        "rows_differing": int((window & (rows.baseline_score != rows.baseline_score_live)).sum()),
        "sessions_compared": len(set(tested)),
        "sessions_top_k_differing": sum(adjusted.get(i) != raw.get(i) for i in set(adjusted) | set(raw)),
        "live_score_wf_trades": live.trades, "live_score_wf_mean_excess": live.mean_excess,
        "live_score_wf_ci_low": live.ci_low, "live_score_wf_ci_high": live.ci_high,
    }


def _decision_report(decision) -> dict:
    return {column.name: getattr(decision, column.name) for column in decision.__table__.columns
            if column.name != "config_snapshot"}


def _record_failure(session, *, model_version, horizon, now, snapshot, snapshot_sha, code_version, dataset) -> dict:
    outcome = GateOutcome(DECISION_NO_EDGE, None, REASON_EVALUATION_FAILED,
                          ({"stage": None, "reason": REASON_EVALUATION_FAILED},))
    decision = write_decision(
        session, model_version=model_version, horizon=horizon,
        feature_version=SEL001_FEATURE_VERSION if model_version == SEL001_MODEL_VERSION else BASELINE_FEATURE_VERSION,
        outcome=outcome, snapshot=snapshot, snapshot_sha=snapshot_sha, code_version=code_version, decided_at=now,
        validity_days=0, dataset_sha256=dataset.sha256_by_horizon.get(horizon) if dataset else None,
        dataset_bounds=(None, None), dates=(), wf=(None, None), ho=(None, None), folds=None, holdout={},
        artefact_id=None, artefact_sha256=None,
    )
    return _decision_report(decision)


def run_selection_gate(session: Session, *, read_session: Session | None = None, models=GATED_MODEL_VERSIONS,
                       horizons=None, walk_forward_only: bool = False, now: datetime | None = None,
                       code_version: str | None = None) -> dict:
    read_session = read_session or session
    now = now or datetime.now(timezone.utc)
    wanted = [(m, h) for m, h in gate_pairs() if m in models and (horizons is None or h in horizons)]
    refused = {(m, h) for m in models for h in (horizons or ())} - set(gate_pairs())
    if refused:
        raise ValueError(f"SPG-001: {sorted(refused)} are not in selection_gate_pairs and are never gated")
    horizons = tuple(sorted({h for _, h in wanted}))
    snapshot = config_snapshot()
    snapshot_sha = config_sha256(snapshot)
    code_version = code_version or settings.build_identifier or "UNKNOWN"
    dataset = build_selection_dataset(read_session, horizons=horizons)
    today = now.astimezone(NSE_TIMEZONE).date()
    pairs = []
    for horizon in horizons:
        if not walk_forward_only:
            write_benchmarks(session, dataset, horizon)
            session.commit()
        windows = plan_windows(dataset.session_dates, horizon, today=today,
                               first_test_month=settings.selection_wf_first_test_month)
        for model_version in [m for m, h in wanted if h == horizon]:
            try:
                pairs.append(_evaluate_pair(session, dataset, windows, model_version=model_version, horizon=horizon,
                                            now=now, walk_forward_only=walk_forward_only, snapshot=snapshot,
                                            snapshot_sha=snapshot_sha, code_version=code_version))
                if not walk_forward_only:
                    session.commit()
            except Exception:
                session.rollback()
                logger.exception("SPG-001 evaluation failed for %s h=%s", model_version, horizon)
                if walk_forward_only:
                    pairs.append({"model_version": model_version, "horizon_sessions": horizon,
                                  "primary_reason": REASON_EVALUATION_FAILED})
                else:
                    pairs.append(_record_failure(session, model_version=model_version, horizon=horizon, now=now,
                                                 snapshot=snapshot, snapshot_sha=snapshot_sha,
                                                 code_version=code_version, dataset=dataset))
                    session.commit()
    return {"dataset": _dataset_summary(dataset), "pairs": pairs}


def _dataset_summary(dataset: SelectionDataset) -> dict:
    return {
        "first_session": dataset.session_dates[0].isoformat(), "last_session": dataset.session_dates[-1].isoformat(),
        "sessions": len(dataset.session_dates), "stocks_by_year": dataset.stocks_by_year,
        "sha256_by_horizon": {str(h): sha for h, sha in dataset.sha256_by_horizon.items()},
        "universe_rows": int(len(dataset.rows)),
        "benchmark_sessions": {str(h): b.session_date.astype(str).tolist() for h, b in dataset.benchmarks.items()},
        "excluded_totals": {str(h): _excluded_totals(b) for h, b in dataset.benchmarks.items()},
    }


def _excluded_totals(benchmark: pd.DataFrame) -> dict:
    totals: dict[str, int] = defaultdict(int)
    for reasons in benchmark.excluded:
        for reason, count in reasons.items():
            totals[reason] += count
    return dict(sorted(totals.items()))
```

`app/selection_gate/metrics.py`:

```python
"""SPG-001 run evidence: elapsed and CPU time and peak memory for the gate and shadow jobs."""
from __future__ import annotations

import ctypes
import sys
import time
from contextlib import contextmanager


def peak_memory_mb() -> float | None:
    """Peak resident memory of this process: ru_maxrss on Linux (KiB), PeakWorkingSetSize on Windows."""
    if sys.platform == "win32":
        from ctypes import wintypes

        class _Counters(ctypes.Structure):
            _fields_ = [("cb", wintypes.DWORD), ("PageFaultCount", wintypes.DWORD),
                        *((name, ctypes.c_size_t) for name in (
                            "PeakWorkingSetSize", "WorkingSetSize", "QuotaPeakPagedPoolUsage", "QuotaPagedPoolUsage",
                            "QuotaPeakNonPagedPoolUsage", "QuotaNonPagedPoolUsage", "PagefileUsage", "PeakPagefileUsage"))]

        counters = _Counters(cb=ctypes.sizeof(_Counters))
        process = ctypes.windll.kernel32.GetCurrentProcess()
        if ctypes.windll.psapi.GetProcessMemoryInfo(process, ctypes.byref(counters), counters.cb):
            return round(counters.PeakWorkingSetSize / 2**20, 1)
        return None
    import resource

    return round(resource.getrusage(resource.RUSAGE_SELF).ru_maxrss / 1024, 1)


@contextmanager
def measured():
    metrics: dict = {}
    started, cpu = time.perf_counter(), time.process_time()
    try:
        yield metrics
    finally:
        metrics.update(elapsed_seconds=round(time.perf_counter() - started, 3),
                       cpu_seconds=round(time.process_time() - cpu, 3), peak_memory_mb=peak_memory_mb())
```

`app/selection_gate/report.py`:

```python
"""SPG-001 §19: the review report -- text for a reader, JSON for the record."""
from __future__ import annotations

import json

from .constants import display_reason


def render_report(result: dict) -> tuple[str, str]:
    data = result["dataset"]
    lines = [
        "SPG-001 selection publish gate",
        f"sessions {data['first_session']} .. {data['last_session']} ({data['sessions']}), universe rows {data['universe_rows']}",
        "stocks per year: " + ", ".join(f"{year}={count}" for year, count in data["stocks_by_year"].items()),
    ]
    lines += [f"h={h} dataset_sha256={sha} excluded={data['excluded_totals'][h]}" for h, sha in data["sha256_by_horizon"].items()]
    if result.get("metrics"):
        lines.append("run: " + ", ".join(f"{k}={v}" for k, v in result["metrics"].items()))
    for pair in result["pairs"]:
        reason = display_reason(pair.get("primary_reason")) or ""
        lines.append(f"{pair['model_version']} h={pair['horizon_sessions']}: {pair.get('decision', 'WALK_FORWARD_ONLY')} {reason}".rstrip())
        if pair.get("live_parity"):
            lines.append("  live-path parity (walk-forward only): " + ", ".join(f"{k}={v}" for k, v in pair["live_parity"].items()))
        for prefix in ("wf", "ho"):
            fields = {k[len(prefix) + 1:]: v for k, v in sorted(pair.items()) if k.startswith(prefix + "_") and k != "wf_folds"}
            if fields:
                lines.append(f"  {prefix}: " + ", ".join(f"{k}={v}" for k, v in fields.items()))
    return "\n".join(lines), json.dumps(result, default=str, indent=2, sort_keys=True)
```

In `app/schedule_orchestration.py`, near line 164, add:

```python
OPERATION_SELECTION_GATE = "SELECTION_GATE"                         # scripts.run_selection_gate
OPERATION_SELECTION_SHADOW = "SELECTION_SHADOW"                     # scripts.run_selection_shadow
```

In `TRIGGER_POLICIES`, add:

```python
    OPERATION_SELECTION_GATE: TriggerPolicy(
        operation_name=OPERATION_SELECTION_GATE,
        trigger_type=TRIGGER_SCHEDULED,
        cadence=timedelta(days=28),
        requires_market_session=False,
        description=(
            "SPG-001: monthly on the 11th at 02:00 IST, once the previous month's 5-session labels have "
            "resolved. Writes the per-(model, horizon) publish decisions that record_recommendation enforces."
        ),
    ),
    OPERATION_SELECTION_SHADOW: TriggerPolicy(
        operation_name=OPERATION_SELECTION_SHADOW,
        trigger_type=TRIGGER_SCHEDULED,
        cadence=timedelta(days=1),
        requires_market_session=False,
        description="SPG-001: 08:35 IST Mon-Fri, after the official-candle confirmation; SEL-001 shadow scores only.",
    ),
```

`scripts/run_selection_gate.py`:

```python
"""SPG-001: run the selection publish gate (`OPERATION_SELECTION_GATE`, CronJob market-agent-selection-gate)."""
from __future__ import annotations

import argparse
import os
import pathlib
from datetime import datetime, timezone

from sqlalchemy import create_engine, event
from sqlalchemy.orm import sessionmaker

from app.db import Base, SessionLocal, engine
from app.schedule_orchestration import (
    OPERATION_SELECTION_GATE, TRIGGER_SCHEDULED, acquire_execution, complete_execution, fail_execution,
)
from app.selection_gate.constants import GATED_MODEL_VERSIONS
from app.selection_gate.metrics import measured
from app.selection_gate.report import render_report
from app.selection_gate.runner import run_selection_gate


def read_only_sessionmaker(url: str) -> sessionmaker:
    """EPIC-843 isolation, enforced by the database rather than by discipline."""
    if url.startswith("postgresql"):
        source = create_engine(url, pool_pre_ping=True, connect_args={"options": "-c default_transaction_read_only=on"})
    else:
        source = create_engine(url)
        event.listen(source, "connect", lambda connection, _record: connection.execute("PRAGMA query_only = ON"))
    return sessionmaker(bind=source, autoflush=False, expire_on_commit=False)


def main(argv: list[str] | None = None) -> None:
    parser = argparse.ArgumentParser(description="Run the SPG-001 selection publish gate.")
    parser.add_argument("--models", nargs="+", default=list(GATED_MODEL_VERSIONS))
    parser.add_argument("--horizons", nargs="+", type=int, default=None)
    parser.add_argument("--walk-forward-only", action="store_true", help="no holdout read, no decision; report only")
    parser.add_argument("--source-url", default=os.environ.get("SELECTION_SOURCE_DATABASE_URL"),
                        help="read-only market-data source (pre-merge evaluation)")
    parser.add_argument("--ephemeral-schema", action="store_true",
                        help="create the schema in a throwaway SQLite DATABASE_URL (evaluation only)")
    parser.add_argument("--code-version", default=None)
    parser.add_argument("--report-json", default=None)
    args = parser.parse_args(argv)
    if args.ephemeral_schema:
        if engine.dialect.name != "sqlite":
            raise SystemExit("--ephemeral-schema is only for a throwaway SQLite DATABASE_URL")
        Base.metadata.create_all(engine)

    requested_at = datetime.now(timezone.utc)
    source = read_only_sessionmaker(args.source_url) if args.source_url else None
    with SessionLocal() as session:
        claim = acquire_execution(session, operation_name=OPERATION_SELECTION_GATE, scope_key="GLOBAL",
                                  trigger_type=TRIGGER_SCHEDULED, trigger_source=requested_at.isoformat(),
                                  triggered_at=requested_at)
        if claim.is_duplicate:
            print(f"Selection gate already ran for this trigger (execution id={claim.existing.id}); skipping.")
            return
        try:
            kwargs = dict(models=tuple(args.models), horizons=args.horizons, walk_forward_only=args.walk_forward_only,
                          now=requested_at, code_version=args.code_version)
            with measured() as metrics:
                if source is not None:
                    with source() as read_session:
                        result = run_selection_gate(session, read_session=read_session, **kwargs)
                else:
                    result = run_selection_gate(session, **kwargs)
            result["metrics"] = {**metrics, "failed_stocks": 0, "retries": 0}  # gate fails closed on any stock; backoffLimit 0
        except BaseException as exc:
            fail_execution(session, claim, started_at=requested_at, failed_at=datetime.now(timezone.utc),
                           failure_reason=f"{type(exc).__name__}: {exc}")
            raise
        complete_execution(session, claim, started_at=requested_at, completed_at=datetime.now(timezone.utc),
                           result_summary={"pairs": [{k: p.get(k) for k in ("model_version", "horizon_sessions",
                                                                           "decision", "primary_reason")}
                                                     for p in result["pairs"]]})
    report_text, document = render_report(result)
    print(report_text)
    print(document)
    if args.report_json:
        pathlib.Path(args.report_json).write_text(document, encoding="utf-8")


if __name__ == "__main__":
    main()
```

- [ ] **Step 4: Run; expect PASS**, then the schedule guards:

```bash
python -m pytest tests/test_selection_gate_runner.py -q
python -m pytest tests/test_schedule_orchestration.py tests/test_scheduler_producer_inventory.py tests/test_validate_scheduler_inventory.py tests/test_schedule_registry.py -q
```

A guard may say an operation has no CronJob yet. That is expected until Task 14: re-run these guards there. Any other guard failure should be fixed as its message directs.

- [ ] **Step 5: Commit**

```bash
git add app/selection_gate app/schedule_orchestration.py scripts/run_selection_gate.py tests/test_selection_gate_runner.py
git commit -m "SPG-001: gate runner, append-only decisions and trades, report and run script"
```

---

### Task 13: SEL-001 daily shadow scoring

**Files:**
- Create: `app/selection_gate/shadow.py`, `scripts/run_selection_shadow.py`
- Test: `tests/test_selection_gate_shadow.py`

**Interfaces:**
- Consumes: `build_selection_dataset(..., keep_session_indices=...)`, `load_artefact`, `score_sel001`, `SelectionShadowScore`
- Produces: `run_selection_shadow(session, *, now, horizons=None) -> dict` with `"results": {horizon: "SKIPPED_NO_DECISION" | "ALREADY_SCORED" | "SCORED <n>"}` and `"metrics"`: `stocks_scored`, `sessions_loaded`, `sessions_scored`, `failed_stocks`, `retries`, `elapsed_seconds`, `cpu_seconds`, `peak_memory_mb`

- [ ] **Step 1: Write the failing test** `tests/test_selection_gate_shadow.py`

```python
from datetime import date, datetime, timezone

import numpy as np
import pytest
from sqlalchemy import create_engine, select
from sqlalchemy.orm import sessionmaker

from app.challenger_artefact import save_artefact
from app.db import Base
from app.model_artefact_registry import REGISTRY_ROOT
from app.models import SelectionShadowScore, Stock
from app.selection_gate import dataset as dataset_module
from app.selection_gate.constants import DISPOSITION_OVERLAP_SUPPRESSED, SEL001_FEATURE_VERSION, SEL001_MODEL_VERSION
from app.selection_gate.dataset import FEATURE_COLUMNS
from app.selection_gate.sel001 import fit_sel001
from app.selection_gate.shadow import run_selection_shadow
from app.settings import settings
from tests._selection_gate_factories import seed_decision, seed_stock_with_bars, weekday_sessions

SESSIONS = weekday_sessions(date(2026, 9, 30), 40)
NOW = datetime(2026, 10, 1, 3, 5, tzinfo=timezone.utc)


@pytest.fixture
def session(monkeypatch):
    for name, value in {"selection_min_session_stocks": 1, "selection_min_history_bars": 25,
                        "selection_min_median_traded_value_20d": 0.0, "selection_top_k": 2,
                        "selection_gate_pairs": (("SEL-001", 3),)}.items():
        monkeypatch.setattr(settings, name, value)
    engine = create_engine("sqlite:///:memory:")
    Base.metadata.create_all(engine)
    with sessionmaker(bind=engine)() as db:
        for k in range(5):
            seed_stock_with_bars(db, symbol=f"S{k}", sessions=SESSIONS, drift=0.001 * k)
        db.commit()
        yield db


def _decision_with_artefact(session):
    rng = np.random.default_rng(1)
    model = fit_sel001(rng.normal(size=(300, len(FEATURE_COLUMNS))), rng.normal(scale=0.02, size=300))
    artefact = save_artefact(model, model_version=SEL001_MODEL_VERSION, feature_version=SEL001_FEATURE_VERSION,
                             manifest={}, root=REGISTRY_ROOT, session=session)
    return seed_decision(session, model_version=SEL001_MODEL_VERSION, horizon=3, artefact_sha256=artefact.sha256)


def test_without_a_decision_shadow_scoring_skips(session):
    assert run_selection_shadow(session, now=NOW)["results"] == {3: "SKIPPED_NO_DECISION"}


def test_scores_every_eligible_stock_and_selects_the_top_k_once(session):
    _decision_with_artefact(session)
    assert run_selection_shadow(session, now=NOW)["results"] == {3: "SCORED 5"}
    rows = session.scalars(select(SelectionShadowScore)).all()
    assert len(rows) == 5 and sum(r.selected for r in rows) == 2
    assert {r.session_date for r in rows} == {SESSIONS[-1]}
    assert run_selection_shadow(session, now=NOW)["results"] == {3: "ALREADY_SCORED"}


def test_metrics_record_scale_time_memory_failures_and_retries(session, monkeypatch):
    _decision_with_artefact(session)
    failing = min(session.scalars(select(Stock.id)))
    real = dataset_module._official_bars

    def flaky(db, stock_id):
        if stock_id == failing:
            raise RuntimeError("corrupt bars")
        return real(db, stock_id)

    monkeypatch.setattr(dataset_module, "_official_bars", flaky)
    metrics = run_selection_shadow(session, now=NOW)["metrics"]
    assert (metrics["stocks_scored"], metrics["failed_stocks"], metrics["retries"]) == (4, 1, 0)
    assert (metrics["sessions_loaded"], metrics["sessions_scored"]) == (40, 1)
    assert metrics["elapsed_seconds"] >= 0 and metrics["cpu_seconds"] >= 0
    assert metrics["peak_memory_mb"] is None or metrics["peak_memory_mb"] > 0


def test_an_open_shadow_hold_suppresses_a_repick(session):
    decision = _decision_with_artefact(session)
    estimator = load_artefact(SEL001_MODEL_VERSION, SEL001_FEATURE_VERSION, root=REGISTRY_ROOT,
                              expected_sha256=decision.artefact_sha256, session=session).estimator
    rows = build_selection_dataset(session, horizons=(3,)).rows
    last = rows[rows.session_index == rows.session_index.max()]
    top = int(last.stock_id.to_numpy()[np.argmax(score_sel001(estimator, last[list(FEATURE_COLUMNS)].to_numpy()))])
    session.add(SelectionShadowScore(model_version=SEL001_MODEL_VERSION, artefact_sha256=decision.artefact_sha256,
                                     horizon_sessions=3, session_date=SESSIONS[-2], stock_id=top, score=1, rank=1,
                                     selected=True, suppression_reason=None, scored_at=NOW))
    session.commit()
    run_selection_shadow(session, now=NOW)
    row = session.scalar(select(SelectionShadowScore).where(SelectionShadowScore.session_date == SESSIONS[-1],
                                                            SelectionShadowScore.stock_id == top))
    assert (row.rank, row.selected, row.suppression_reason) == (1, False, DISPOSITION_OVERLAP_SUPPRESSED)
```

Add `from app.challenger_artefact import load_artefact`, `from app.selection_gate.dataset import build_selection_dataset` and `from app.selection_gate.sel001 import score_sel001` to the imports.

- [ ] **Step 2: Run; expect FAIL.** Run: `python -m pytest tests/test_selection_gate_shadow.py -q`

- [ ] **Step 3: Implement** `app/selection_gate/shadow.py`

```python
"""SPG-001 §15.2: SEL-001 scores the whole eligible universe daily, in shadow; public routes never read it."""
from __future__ import annotations

import logging
from datetime import datetime
from decimal import Decimal

from sqlalchemy import select
from sqlalchemy.orm import Session

from app.challenger_artefact import load_artefact
from app.model_artefact_registry import REGISTRY_ROOT
from app.models import SelectionGateDecision, SelectionShadowScore
from app.settings import settings

from .constants import DISPOSITION_OVERLAP_SUPPRESSED, SEL001_FEATURE_VERSION, SEL001_MODEL_VERSION
from .config import gate_horizons
from .dataset import FEATURE_COLUMNS, build_selection_dataset, session_list
from .metrics import measured
from .sel001 import score_sel001

logger = logging.getLogger(__name__)


def run_selection_shadow(session: Session, *, now: datetime, horizons=None) -> dict:
    horizons = tuple(horizons or gate_horizons(SEL001_MODEL_VERSION))
    last_index = len(session_list(session, min_stocks=settings.selection_min_session_stocks)) - 1
    dataset, results, scored, sessions_scored = None, {}, 0, 0
    with measured() as metrics:
        for horizon in horizons:
            decision = session.scalar(
                select(SelectionGateDecision)
                .where(SelectionGateDecision.model_version == SEL001_MODEL_VERSION,
                       SelectionGateDecision.horizon_sessions == horizon,
                       SelectionGateDecision.artefact_sha256.is_not(None))
                .order_by(SelectionGateDecision.id.desc()).limit(1)
            )
            if decision is None:
                logger.info("SPG-001 shadow: no SEL-001 decision with an artefact for h=%s", horizon)
                results[horizon] = "SKIPPED_NO_DECISION"
                continue
            if dataset is None:
                # Shadow skips and counts a bad stock; only the gate must fail closed on one.
                dataset = build_selection_dataset(session, horizons=horizons, keep_session_indices={last_index},
                                                  skip_failed_stocks=True)
            day = dataset.session_dates[last_index]
            if session.scalar(select(SelectionShadowScore.id).where(
                    SelectionShadowScore.model_version == SEL001_MODEL_VERSION,
                    SelectionShadowScore.horizon_sessions == horizon,
                    SelectionShadowScore.session_date == day).limit(1)):
                results[horizon] = "ALREADY_SCORED"
                continue
            estimator = load_artefact(SEL001_MODEL_VERSION, SEL001_FEATURE_VERSION, root=REGISTRY_ROOT,
                                      expected_sha256=decision.artefact_sha256, session=session).estimator
            rows = dataset.rows[dataset.rows.session_index == last_index].copy()
            rows["score"] = score_sel001(estimator, rows[list(FEATURE_COLUMNS)].to_numpy())
            rows = rows.sort_values(["score", "stock_id"], ascending=[False, True], kind="mergesort")
            held_from = dataset.session_dates[max(last_index - horizon + 1, 0)]
            open_holds = set(session.scalars(select(SelectionShadowScore.stock_id).where(
                SelectionShadowScore.model_version == SEL001_MODEL_VERSION,
                SelectionShadowScore.horizon_sessions == horizon, SelectionShadowScore.selected.is_(True),
                SelectionShadowScore.session_date >= held_from, SelectionShadowScore.session_date < day)))
            for rank, row in enumerate(rows.itertuples(index=False), start=1):
                candidate = rank <= settings.selection_top_k
                suppressed = candidate and int(row.stock_id) in open_holds
                session.add(SelectionShadowScore(
                    model_version=SEL001_MODEL_VERSION, artefact_sha256=decision.artefact_sha256,
                    horizon_sessions=horizon, session_date=day, stock_id=int(row.stock_id),
                    score=Decimal(str(round(float(row.score), 10))), rank=rank,
                    selected=candidate and not suppressed,
                    suppression_reason=DISPOSITION_OVERLAP_SUPPRESSED if suppressed else None, scored_at=now,
                ))
            session.commit()
            scored += len(rows)
            sessions_scored += 1
            results[horizon] = f"SCORED {len(rows)}"
    metrics.update(
        stocks_scored=scored, sessions_loaded=last_index + 1, sessions_scored=sessions_scored,
        failed_stocks=0 if dataset is None else len(dataset.failed_stocks),
        retries=0,  # no retry path; the CronJob's backoffLimit is 0
    )
    return {"results": results, "metrics": metrics}
```

Hold rule, same as §8: a selection on D′ is still open on D when `i(D) < i(D′) + h`. The window `[D−h+1, D−1]` is that set.

`scripts/run_selection_shadow.py` has the same shape as `scripts/run_selection_gate.py` (claim, run, complete/fail). It claims `OPERATION_SELECTION_SHADOW`, calls `run_selection_shadow(session, now=requested_at)`, prints the result dict as JSON (results and metrics), and stores the metrics in `complete_execution(..., result_summary=...)`. It takes no read-only source flag.

- [ ] **Step 4: Run; expect PASS.** Run: `python -m pytest tests/test_selection_gate_shadow.py -q`

- [ ] **Step 5: Commit**

```bash
git add app/selection_gate/shadow.py scripts/run_selection_shadow.py tests/test_selection_gate_shadow.py
git commit -m "SPG-001: SEL-001 daily shadow scoring of the eligible universe"
```

---

### Task 14: Kubernetes CronJobs

**Files:**
- Create: `deploy/k8s/base/selection-gate-cronjob.yaml`, `deploy/k8s/base/selection-shadow-cronjob.yaml`
- Modify: `deploy/k8s/base/kustomization.yaml`, adding both after `learning-cycle-cronjob.yaml`
- Test: `tests/test_cronjob_manifests.py` (append)

- [ ] **Step 1: Write the failing test.** Append to `tests/test_cronjob_manifests.py`, using its existing `MANIFESTS_DIR` and `_kind` helpers:

```python
def test_selection_gate_jobs_are_registered_bounded_and_resourced():
    kustomization = yaml.safe_load((MANIFESTS_DIR / "kustomization.yaml").read_text())
    assert {"selection-gate-cronjob.yaml", "selection-shadow-cronjob.yaml"} <= set(kustomization["resources"])
    gate = _kind("selection-gate-cronjob.yaml", "CronJob")["spec"]
    assert (gate["schedule"], gate["timeZone"], gate["concurrencyPolicy"]) == ("30 20 10 * *", "Etc/UTC", "Forbid")
    job = gate["jobTemplate"]["spec"]
    assert (job["backoffLimit"], job["activeDeadlineSeconds"], job["ttlSecondsAfterFinished"]) == (0, 21600, 604800)
    container = job["template"]["spec"]["containers"][0]
    assert container["command"] == ["python", "-m", "scripts.run_selection_gate"]
    assert container["resources"] == {"requests": {"cpu": "1", "memory": "3Gi"}, "limits": {"cpu": "2", "memory": "6Gi"}}
    shadow = _kind("selection-shadow-cronjob.yaml", "CronJob")["spec"]
    assert shadow["schedule"] == "5 3 * * 1-5"
    assert shadow["jobTemplate"]["spec"]["activeDeadlineSeconds"] == 1800
    assert shadow["jobTemplate"]["spec"]["template"]["spec"]["containers"][0]["command"] == [
        "python", "-m", "scripts.run_selection_shadow"]
```

- [ ] **Step 2: Run; expect FAIL.** Run: `python -m pytest tests/test_cronjob_manifests.py -q`

- [ ] **Step 3: Implement** `deploy/k8s/base/selection-gate-cronjob.yaml`:

```yaml
# SPG-001: the monthly selection publish gate. 20:30 UTC on the 10th = 02:00 IST on the 11th, when the
# previous month's 5-session labels have resolved. Decisions last 45 days; a missed run fails closed.
# Resources are an approved ceiling for a few hours a month; override per environment with a kustomize patch.
apiVersion: batch/v1
kind: CronJob
metadata:
  name: market-agent-selection-gate
  namespace: market-agent
spec:
  schedule: "30 20 10 * *"
  timeZone: "Etc/UTC"
  concurrencyPolicy: Forbid
  startingDeadlineSeconds: 3600
  successfulJobsHistoryLimit: 3
  failedJobsHistoryLimit: 3
  jobTemplate:
    spec:
      backoffLimit: 0
      ttlSecondsAfterFinished: 604800
      activeDeadlineSeconds: 21600
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
            - name: selection-gate
              image: marksy-api:local
              imagePullPolicy: IfNotPresent
              command: ["python", "-m", "scripts.run_selection_gate"]
              resources:
                requests:
                  cpu: "1"
                  memory: 3Gi
                limits:
                  cpu: "2"
                  memory: 6Gi
              env:
                - name: DATABASE_URL
                  valueFrom:
                    secretKeyRef:
                      name: market-agent-secrets
                      key: DATABASE_URL
```

`deploy/k8s/base/selection-shadow-cronjob.yaml` is identical except:
- the header comment reads `# SPG-001: SEL-001 shadow scores at 08:35 IST Mon-Fri, after the 08:30 official-candle confirmation. Never public.`
- `name: market-agent-selection-shadow`, schedule `"5 3 * * 1-5"`
- `backoffLimit: 0`, `ttlSecondsAfterFinished: 86400`, `activeDeadlineSeconds: 1800`
- container name `selection-shadow`, command `["python", "-m", "scripts.run_selection_shadow"]`
- resources requests `cpu: "500m"`, `memory: 1Gi`; limits `cpu: "1"`, `memory: 2Gi`

Add both files to `resources:` in `deploy/k8s/base/kustomization.yaml`.

- [ ] **Step 4: Run; expect PASS**, including the EPIC-198/314 guards:

```bash
python -m pytest tests/test_cronjob_manifests.py tests/test_scheduler_producer_inventory.py tests/test_validate_scheduler_inventory.py tests/test_schedule_orchestration.py tests/test_schedule_registry.py -q
```

- [ ] **Step 5: Commit**

```bash
git add deploy/k8s/base tests/test_cronjob_manifests.py
git commit -m "SPG-001: monthly gate and weekday shadow CronJobs"
```

---

### Task 15: Read-only production snapshot and the pre-merge evaluation run

**Files:**
- Create: `scripts/load_selection_snapshot.py`
- Test: `tests/test_selection_gate_snapshot.py`
- No production writes. Evaluation artefacts go only to the scratchpad; nothing is committed.

- [ ] **Step 1: Write the failing test** `tests/test_selection_gate_snapshot.py`

```python
from decimal import Decimal

from sqlalchemy import create_engine, select
from sqlalchemy.orm import sessionmaker

from app.models import MarketPrice, Stock
from scripts.load_selection_snapshot import load_snapshot


def test_a_csv_export_loads_into_a_local_snapshot(tmp_path):
    (tmp_path / "stocks.csv").write_text("id,symbol,instrument_key,exchange,sector,is_active\n1,AAA,,NSE,TECH,t\n")
    (tmp_path / "corporate_actions.csv").write_text("id,stock_id\n")
    (tmp_path / "market_prices.csv").write_text(
        "id,stock_id,timestamp,open,high,low,close,volume,source\n"
        "1,1,2026-09-29 18:30:00+00,100.5,101,99,100.25,12345,upstox-v3-historical\n"
    )
    url = f"sqlite:///{(tmp_path / 'snapshot.db').as_posix()}"
    assert load_snapshot(tmp_path, url) == {"stocks": 1, "corporate_actions": 0, "market_prices": 1}
    with sessionmaker(bind=create_engine(url))() as db:
        bar = db.scalar(select(MarketPrice))
        assert bar.close == Decimal("100.25") and bar.volume == 12345
        assert db.scalar(select(Stock.symbol)) == "AAA"
```

Extend the CSV headers with any other NOT NULL columns of those three tables. Check them with `python -c "from app.models import MarketPrice; print([c.name for c in MarketPrice.__table__.columns if not c.nullable])"`.

- [ ] **Step 2: Run; expect FAIL.** Run: `python -m pytest tests/test_selection_gate_snapshot.py -q`

- [ ] **Step 3: Implement** `scripts/load_selection_snapshot.py`

```python
"""SPG-001 pre-merge evaluation: load a read-only CSV export of the three source tables into a local SQLite file."""
from __future__ import annotations

import argparse
import json
import pathlib
from decimal import Decimal

import pandas as pd
import sqlalchemy as sa

from app.db import Base

TABLES = ("stocks", "corporate_actions", "market_prices")
_CHUNK = 100_000


def _coerce(column: sa.Column, value):
    if value is None or (isinstance(value, float) and pd.isna(value)) or value == "":
        return None
    kind = column.type
    if isinstance(kind, sa.DateTime):
        return pd.Timestamp(value).to_pydatetime()
    if isinstance(kind, sa.Date):
        return pd.Timestamp(value).date()
    if isinstance(kind, sa.Numeric):
        return Decimal(value)
    if isinstance(kind, sa.Integer):
        return int(value)
    if isinstance(kind, sa.Boolean):
        return value in ("t", "true", "True", "1")
    if isinstance(kind, sa.JSON):
        return json.loads(value)
    return value


def load_snapshot(csv_dir: pathlib.Path, url: str) -> dict[str, int]:
    engine = sa.create_engine(url)
    tables = [Base.metadata.tables[name] for name in TABLES]
    Base.metadata.create_all(engine, tables=tables)
    counts = {}
    with engine.begin() as connection:
        for table in tables:
            total = 0
            for chunk in pd.read_csv(csv_dir / f"{table.name}.csv", chunksize=_CHUNK, dtype=str, keep_default_na=False):
                records = [{name: _coerce(table.c[name], value) for name, value in row.items() if name in table.c}
                           for row in chunk.to_dict("records")]
                if records:
                    connection.execute(table.insert(), records)
                total += len(records)
            counts[table.name] = total
    return counts


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("csv_dir", type=pathlib.Path)
    parser.add_argument("url")
    args = parser.parse_args()
    print(load_snapshot(args.csv_dir, args.url))
```

- [ ] **Step 4: Run; expect PASS**, then commit

```bash
python -m pytest tests/test_selection_gate_snapshot.py -q
git add scripts/load_selection_snapshot.py tests/test_selection_gate_snapshot.py
git commit -m "SPG-001: load a read-only production CSV export for the pre-merge evaluation"
```

- [ ] **Step 5: Freeze the evaluated commit.** All tasks are committed and green: `git rev-parse HEAD > $SCRATCH/spg-eval-commit.txt`. `$SCRATCH` is the session scratchpad. From here on, any config change burns the held-out month for the production run (spec §19.3).

- [ ] **Step 6: Export the three tables, read-only.** This uses the recipe in memory `marksy-api-surface`: the script exports `KUBECONFIG`, finds the postgres pod and runs psql inside `BEGIN TRANSACTION READ ONLY … ROLLBACK`. Run one call per table and redirect stdout locally:

```bash
for T in stocks corporate_actions "market_prices WHERE source <> 'upstox-v3-quote-ohlc'"; do
  NAME=$(echo "$T" | cut -d' ' -f1)
  cat > $SCRATCH/export_$NAME.sh <<EOF
export KUBECONFIG=\$HOME/.kube/config
P=\$(kubectl -n market-agent get pods --field-selector=status.phase=Running -o name | grep -m1 "^pod/postgres")
kubectl -n market-agent exec -i "\$P" -- sh -c 'psql -X -q -U "\$POSTGRES_USER" -d "\$POSTGRES_DB" -c "BEGIN TRANSACTION READ ONLY" -c "\\copy (SELECT * FROM $T) TO STDOUT WITH CSV HEADER" -c "ROLLBACK"'
EOF
  cat $SCRATCH/export_$NAME.sh | ssh -o ConnectTimeout=15 marksy@103.178.160.233 "tr -d '\r' | bash" > $SCRATCH/snapshot/$NAME.csv
done
wc -l $SCRATCH/snapshot/*.csv
```

`psql -q` suppresses the `BEGIN`/`ROLLBACK` tags. If they still appear in a CSV, delete those first and last lines.

- [ ] **Step 7: Load and run the evaluation in an ephemeral store**

```bash
cd /c/AIAgent/marksy-api
python -m scripts.load_selection_snapshot $SCRATCH/snapshot "sqlite:///$SCRATCH/snapshot.db"
DATABASE_URL="sqlite:///$SCRATCH/eval.db" python -m scripts.run_selection_gate --ephemeral-schema \
  --source-url "sqlite:///$SCRATCH/snapshot.db" --code-version "$(cat $SCRATCH/spg-eval-commit.txt)" \
  --report-json $SCRATCH/spg-eval-report.json | tee $SCRATCH/spg-eval-report.txt
```

Record the wall-clock time and peak memory (Task Manager, or `Measure-Command` in PowerShell) for the §16 limits.

- [ ] **Step 8: Measure the shadow job against its 1800 s deadline.** The eval store already holds the SEL-001 decisions and artefacts. Add the market data to it, then run once:

```bash
python -m scripts.load_selection_snapshot $SCRATCH/snapshot "sqlite:///$SCRATCH/eval.db"
DATABASE_URL="sqlite:///$SCRATCH/eval.db" python -m scripts.run_selection_shadow | tee $SCRATCH/spg-shadow-run.json
```

Record, from the printed `metrics` and the gate report's `run:` line:
- stocks scored
- sessions loaded and scored
- elapsed time
- peak memory
- CPU seconds
- failed stock count
- retry count

Record the same for the gate run. If the shadow elapsed time exceeds 1800 s, or either peak exceeds its job limit, the report proposes new limits; it never raises them silently.

---

### Task 16: Full validation and the review report (stop before merge)

- [ ] **Step 1: The whole suite** (about 2 h; run it in the background):

```bash
cd /c/AIAgent/marksy-api && python -m pytest -q -p no:cacheprovider > $SCRATCH/spg-full-suite.txt 2>&1; tail -30 $SCRATCH/spg-full-suite.txt
```

Compare failures against the 4 known pre-existing failures on `main`, using `git stash` or a clean `main` worktree run of just those files. Any new failure blocks the report.

- [ ] **Step 2: Draft-PR state, no push.** Write the PR title and body to `$SCRATCH/spg-001-draft-pr.md`. Title: `SPG-001: selection publish gate`. The body links the spec, lists the commits (`git log --oneline main..HEAD`), carries the evaluation summary, and ends with `🤖 Generated with [Claude Code](https://claude.com/claude-code)`. The branch stays local. Do not push, open a PR, merge or deploy.

- [ ] **Step 2b: One comprehensive whole-branch review** (the controller dispatches it on the most capable model, over `git merge-base main HEAD`..HEAD). It must cover:
  - no publish-path bypass: `record_recommendation` is the only `Prediction` constructor, and `register_prediction` refuses SHADOW
  - no shadow prediction reaching any public surface
  - fail-closed behaviour on every error path
  - leakage and embargo correctness
  - held-out-month single use
  - deduplication and overlap reduction before statistics
  - pairs outside `selection_gate_pairs` staying shadow; every BASELINE-001 horizon earning publication only through its own decision
  - SEL-001 staying shadow-only
  - missing or expired decisions blocking publication
  - pre-rollout calls untouched

  One fix wave, then one scoped re-review.

- [ ] **Step 3: The review report to the user** (spec §19), then stop:
  - **Formulas:** §7–§11 as implemented, each with `file:line`:
    - universe `universe.py::exclusion_reasons`
    - features `features.py::per_stock_features`
    - labels `dataset.py::_labels`
    - benchmark `dataset.py::_benchmarks`
    - hash `dataset.py::_sha256`
    - reduction `reduction.py`
    - statistics `statistics.py`
    - windows `validation.py`
    - decision `statistics.py::decide`
    - authorisation `authorization.py`
  - **Data sources:** tables read, session range, stocks per year, `dataset_sha256` per horizon, universe rows, exclusion totals per horizon, all from `spg-eval-report.json`.
  - **Tests:** new test files with pass counts, and the full-suite result against the `main` baseline.
  - **Gate results:** for each of BASELINE-001 and SEL-001 at h=3 and h=5, every `wf_`/`ho_` field, the decision, the reasons, and the folds table (month, train rows, `n`, `mean_excess`).
  - **Runtime:** for the gate and the shadow job: stocks scored, sessions processed, elapsed time, peak memory, CPU seconds, failed stocks and retries, against the 6 Gi / 21600 s and 1800 s limits.
  - **Offline/live parity:** each BASELINE-001 pair's `live_parity` block, stated as a measured difference, not as equivalence.
  - **Known limitations:** the current-sector feature (historical sector membership is not reconstructed) and survivorship.
  - **What changes for users on deploy:** each BASELINE-001 horizon (1, 3, 5, 7) publishes only if its own pair passed; everything else is `SHADOW`. Zero public calls if none passed.

No merge, deploy or migration until the user approves the report.

---

## Spec deviations and clarifications for review

1. **Holdout consumption uses a new table `selection_holdout_usages`, not `HoldoutUsageRecord`.** `HoldoutUsageRecord.experiment_arm_id` is a NOT NULL foreign key to `experiment_arms`, which the gate has no use for. Windows are still registered in `HoldoutWindowRegistry` as the spec says.
2. **Per-stock feature computation replaces per-cutoff observer calls.** Recomputing `extended_point_in_time_features` for about 4M (stock, session) pairs would take hours. Instead each stock is computed once per corporate-action epoch. Task 6 proves every row equal to the reference and also checks the observer bound. Two leakage tests (future prices, future corporate actions) pin it.
3. **`--shard-cutoffs` is dropped; three flags are added.** The dataset is built stock by stock, so memory is bounded by the final frame. The new flags are `--source-url` (read-only source), `--ephemeral-schema` and `--report-json`, for the pre-merge evaluation.
4. **`rel_strength_sector_20d` uses the current `Stock.sector`,** as FV-002 already does. This is a documented point-in-time limitation: historical sector membership is not reconstructed. It is kept for compatibility and reported with the survivorship limitation.
5. **BASELINE-001 is scored offline on corporate-action-adjusted bars; the live scan reads raw bars.** They are not treated as equivalent. The dataset carries `baseline_score_live`, Task 6 pins where the two differ, and every run reports a walk-forward-only `live_parity` block.
6. **A SEL-001 `ModelVersion` row is created on its first gate run.** BASELINE-001 is fixed code: if it has no `ModelVersion` row, its no-peeking check is skipped. Single use per month still applies to it.
7. **`selection_gate_pairs` replaces `selection_gate_horizons` and `selection_gate_reported_pairs`.** Per the 2026-10-01 instruction, every BASELINE-001 horizon is gated independently. The pair list is hashed, and `meta.publishGate` reports exactly these pairs.
8. **The shadow job's 1800 s deadline is kept.** Task 15 Step 8 measures it; if it is exceeded, the report proposes a new limit rather than silently raising it.
