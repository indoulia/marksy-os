# Marksy TG-001 Trade Geometry Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** For each SEL-001 selector (`h_sel` 3 and 5), decide whether one predeclared holding/target/stop rule turns SPG-001's selections into statistically defensible net excess over the same universe traded under the same rule, without changing anything SPG decides or publishes.

**Architecture:**
- A new package `app/trade_geometry/` holds pure fill, path-statistics, reduction, reality-check and choice code; database readers for SPG's walk-forward selections, shadow scores and exam registry; adjusted official-bar paths; TG's own exam registry; an append-only decision store; and a runner.
- One monthly CronJob (02:00 IST on the 12th, the day after SPG) writes one `tg_decisions` row per selector. Nothing reads it for publication in this phase.
- SPG-001 (`app/selection_gate/**`) is frozen and only imported. The dependency is one-way and tested in both directions.

**Tech Stack:** Python 3.12, SQLAlchemy 2 (`Mapped`), Alembic, pandas, numpy, pytest on SQLite, k3s CronJob. No new dependency. TG imports no xgboost.

**Spec:** `docs/superpowers/specs/2026-10-01-marksy-tg-001-trade-geometry-design.md` in marksy-os (commit `6eb0640`, approved for planning). All code goes into `C:\AIAgent\marksy-api` on branch `feat/tg-001-trade-geometry`, cut from `main` at `e5812e5` or later. Read the spec alongside this plan.

**Provenance:** every new file below was run in a scratch prototype against a read-only marksy-api `e5812e5`: 116 of the plan's 118 TG tests went green in about 1 min 45 s on SQLite.
- The two not run there need the real orchestration registry (Task 12 `test_the_lease_trigger_and_recovery_policies_are_registered`) or a real package path (Task 11 `test_trade_geometry_never_loads_xgboost_or_the_sel001_model`). Its probe was run by hand and printed `False False`.
- The plan's copies differ from the prototype only in harness wiring: the migration helper, and `python -m` invocation.
- Task 12's edits to existing guard tests are exact text replacements against `e5812e5`.

## Global Constraints

- SPG is frozen: `git diff main -- app/selection_gate scripts/run_selection_gate.py scripts/load_selection_snapshot.py` stays empty. TG imports only the spec §6.4 surface. The exceptions are `StageStatistics` and `Month`, imported as type names.
- Settings, verbatim from spec §20: `tg_selector_horizons = (3, 5)`, `tg_holding_horizons = (1, 3, 5, 7)`, `tg_target_atr_multiples = (None, 1.5, 3.0)`, `tg_stop_atr_multiples = (None, 1.0, 2.0)`, `tg_min_net_excess = 0.0050`, `tg_min_wf_trades = 2000`, `tg_min_holdout_trades = 100`, `tg_min_wf_folds = 24`, `tg_min_fold_positive_share = 0.55`, `tg_reality_check_alpha = 0.05`, `tg_simplicity_tolerance = 0.0010`, `tg_max_unresolved_share = 0.02`, `tg_max_shadow_missing_share = 0.10`, `tg_holdout_not_before = "2026-11"`, `tg_bootstrap_draws = 10000`, `tg_bootstrap_seed = 42`, `tg_decision_validity_days = 45`.
- SPG values reused by value and copied into the TG snapshot: cost `selection_round_trip_cost` (0.0030), K `selection_top_k` (10), `selection_min_benchmark_stocks` (100), plausible returns `[-0.60, 1.50]`.
- Constants: `TG_RULE_VERSION = "TG-001"`, `TG_DATASET_VERSION = "TG-DS-001"`, `TG_HOLDOUT_REGISTRY_VERSION = "TG-HOLDOUT-001"`, `REFERENCE_GEOMETRY = {"holding_sessions": 3, "target_pct": 0.05, "stop_pct": 0.03}`, `TG_PARITY_TOLERANCE = 1e-6`.
- Grid: 36 geometries in `(H, kT, kS)` order, keys `H<H>:T<kT>:S<kS>` (`none`, one decimal), reference key `REF:H3:T0.05:S0.03`. `grid_sha256 = 2918338e591afe85bc2e3d5f497966b04a83c9d191f1ba44885053733bcd6597` (pinned in Task 1).
- Fail closed. Any exception after start writes `NO_GEOMETRY / EVALUATION_FAILED` with `valid_until = decided_at`. A shared-phase failure also re-raises, so the execution row is `FAILED`.
- TG never writes an SPG table, `holdout_window_registry`, `selection_holdout_usages`, `holdout_usage_records` or `model_versions`.
- TG never reads a `selection_gate_trades` row with `stage = 'HELD_OUT'`, nor any `ho_*`/`holdout_*` column of `selection_gate_decisions`. Every SPG read lists its columns explicitly.
- No publication change: `decide_publication`, `PUBLICATION_CAPABILITY` and `app/selection_gate/publication.py` are untouched, and SEL-001 stays `SHADOW_ONLY`.
- Official daily bars only (`final_bars_only()`). Paths are stored as `float32 [rows, 7, 4]`; `o` and all arithmetic are `float64`.
- Migration `migrations/versions/0190_trade_geometry.py`: `revision = "0190_trade_geometry"`, `down_revision = "0189_selection_publish_gate"` (the head at `e5812e5`, confirmed).
- Operation `OPERATION_TRADE_GEOMETRY = "TRADE_GEOMETRY"`: a `TriggerPolicy` (SCHEDULED, 28 days), `LOCK_LEASE_OVERRIDES` 21600, which is at least the CronJob's `activeDeadlineSeconds` 21600, and a `NOT_REPEATABLE` `RecoveryPolicy` depending on `SELECTION_GATE`. CronJob `ttlSecondsAfterFinished: 172800`.
- Tests: per-file SQLite engines plus `Base.metadata.create_all`, the repo pattern. Run from `C:\AIAgent\marksy-api`. Give every run its own database file, because the root `conftest.py` fallback (`marksy-pytest-default.db` in the temp dir) is one shared path: `DATABASE_URL="sqlite:///$SCRATCH/tg-<task>.db" python -m pytest <paths> -q -p no:cacheprovider`. `$SCRATCH` is the session scratchpad. The full suite takes about 25 minutes.
- TDD for every task: failing test, confirm red, implement, confirm green, commit. Comments are one line, cite TG-001 and only say why.
- Do not push, merge, deploy or migrate. Task 14 ends with the review report and a local draft-PR text.
- Open questions (spec §23) are planned on the spec's recommendations: OQ1 ATR-scaled grid, OQ2 absolute net expectancy reported only, OQ3 `tg_holdout_not_before = "2026-11"`. Each affected step is marked **(depends on open question N)** and says what changes.

## Review Focus

Each line names an input the spec implies but no spec test exercises, and the test that pins it.

1. **A bound walk-forward candidate whose `session_date` is not in TG's rebuilt `S`, or whose stock has no SEL-DS-001 row.** It is `NOT_IN_DATASET`, counted `UNRESOLVED` in every geometry, ordered by date, and stored, never dropped (Task 4 `test_a_session_outside_s_is_unresolved_and_still_respects_a_busy_stock`, Task 10 `test_a_candidate_outside_the_dataset_is_counted_unresolved_and_stored`).
2. **A candidate whose path resolves but whose session has fewer than `selection_min_benchmark_stocks` resolved rows at `H`.** It is `UNRESOLVED` under `NOT_BENCHMARKABLE`, never benchmarked and never dropped (Task 9 `test_candidates_in_an_unbenchmarkable_session_are_unresolved_not_dropped`).
3. **`decided_at`, `scored_at` and `registered_at` read back naive from SQLite and aware from Postgres, at exactly equal instants.** The comparison is inclusive; one microsecond later it fails (Task 8 `test_provenance_is_inclusive_at_the_instant_across_naive_and_aware_times`, `test_the_spg_guard_needs_a_registration_at_or_after_the_month_and_not_in_the_future`).
4. **A corporate action dated on a weekend inside a path, or on the entry session `D+1`.** It applies from the next session on, and never twice (Task 7 `test_an_action_dated_on_a_non_session_day_applies_from_the_next_session`, `test_an_action_on_the_entry_session_is_already_in_o_and_bar_one`).
5. **The job is killed after the freeze but before the exam is scored.** The month stays spent and the decision is `EVALUATION_FAILED`. A re-run records `HOLDOUT_ALREADY_CONSUMED` instead of re-examining (Task 10 `test_a_pass_two_failure_keeps_the_month_spent`).

## File Structure

New, in `app/trade_geometry/`:
- `__init__.py`: package marker.
- `constants.py`: versions, decisions, outcomes, statuses, roles, sources and TG-only reasons. Codes SPG already defines are imported, never redefined.
- `config.py`: `Geometry`, `grid()`, `reference_geometry()`, `hold_geometry()`, `path_length()`, `grid_sha256()`, `config_snapshot()`, `current_tg_config_sha256()`.
- `fills.py`: pure §7.3 `path_status`, §8 `simulate` (returns `Fills`) and §9 `path_statistics`.
- `reduction.py`: pure §12 `GeometryCandidate` and `reduce_geometry_candidates`.
- `windows.py`: pure §13.1 `plan_tg_windows` and `walk_forward_sessions`.
- `reality_check.py`: pure §15.5 `reality_check` (returns `RealityCheck`).
- `choice.py`: pure §15.4 folds, §15.8 geometry reasons, §15.6 robust choice, and §15.7 `decide_geometry`.
- `inputs.py`: §6 SPG reads, explicit columns only: `bound_decision`, `walk_forward_candidates`, `shadow_candidates`, `decision_artefacts`, `spg_registrations`.
- `paths.py`: §7.2 `build_paths` (pass 1 and pass 2, returns `PathSet`), and the §7.5 `dataset_sha256` and `selection_sha256`.
- `holdout.py`: §13.4/§13.5 `register_window`, `spg_exam_available`, `already_consumed`, `shadow_coverage` and `freeze`.
- `evaluation.py`: §11 `universe_benchmarks`, §13.2 `evaluate_geometry` (returns `GeometryRun`), `fold_months` and `walk_forward_matrix`.
- `parity.py`: §10.2 `parity_block`.
- `records.py`: append-only listeners and the `write_decision` / `write_results` / `write_trades` writers.
- `runner.py`: `run_trade_geometry`.
- `report.py`: `render_report`.

New elsewhere:
- scripts: `scripts/run_trade_geometry.py`, `scripts/load_trade_geometry_snapshot.py`
- migration: `migrations/versions/0190_trade_geometry.py`
- manifest: `deploy/k8s/base/trade-geometry-cronjob.yaml`
- test helpers: `tests/_trade_geometry_factories.py`
- tests: `tests/test_trade_geometry_*.py`

Modified:
- `app/settings.py`: the TG settings.
- `app/models.py`: five tables.
- `app/schedule_orchestration.py`: the operation, its lease and its trigger.
- `app/operation_recovery.py`: the recovery policy.
- `deploy/k8s/base/kustomization.yaml`: the CronJob.
- Guard tests, counts only: `tests/test_cronjob_manifests.py`, `tests/test_epic317_drills.py`, `tests/test_api_operations_health.py`, `tests/test_epic317_regressions.py`, `tests/test_operation_verification_s7.py`.

Not modified: anything under `app/selection_gate/`, `scripts/run_selection_gate.py`, `scripts/load_selection_snapshot.py`, `app/recommendations.py`, and every marksy-os and admin-app file.

---

### Task 1: Constants, grid, configuration snapshot, settings

**Files:**
- Create: `app/trade_geometry/__init__.py`, `app/trade_geometry/constants.py`, `app/trade_geometry/config.py`
- Modify: `app/settings.py`, inside `class Settings`, directly after `selection_decision_validity_days: int = 45` (line 279 at `e5812e5`)
- Test: `tests/test_trade_geometry_config.py`

**Interfaces:**
- Consumes: `app.selection_gate.config.config_sha256(snapshot: dict) -> str`, `current_config_sha256() -> str`. `app.selection_gate.constants.DATASET_VERSION`, `UNIVERSE_RULE_VERSION`.
- Produces:
  - every constant in `constants.py`, and `display_reason(reason) -> str | None`
  - `Geometry(holding_sessions: int, target_atr_multiple: float | None = None, stop_atr_multiple: float | None = None, grid_index: int | None = None, target_pct: float | None = None, stop_pct: float | None = None, is_reference: bool = False)` with `.barriers -> int` and `.key -> str`
  - `grid(source=settings) -> tuple[Geometry, ...]`, `reference_geometry() -> Geometry`, `hold_geometry(horizon: int) -> Geometry`, `path_length(source=settings) -> int`
  - `grid_sha256(source=settings) -> str`, `config_snapshot(source=settings) -> dict`, `current_tg_config_sha256() -> str`

- [ ] **Step 1: Branch and confirm the migration head**

```bash
cd /c/AIAgent/marksy-api && git checkout main && git pull --ff-only && git checkout -b feat/tg-001-trade-geometry
ls migrations/versions | sort | grep -v __pycache__ | tail -1
grep -rl '"0189_selection_publish_gate"' migrations/versions
```

Expected: the last file is `0189_selection_publish_gate.py`, and only that file names the revision. If a later revision exists, set `down_revision` in Task 2 to it and stop to report it.

- [ ] **Step 2: Write the failing test** `tests/test_trade_geometry_config.py` **(depends on open questions 1 and 3)**: under OQ1 = fixed percentages, the multiple assertions become percentage tuples and `GRID_SHA256` is recomputed. Under OQ3 = faster, `"2026-11"` becomes `"2026-09"`.

```python
from app.selection_gate.config import config_sha256, current_config_sha256
from app.settings import settings
from app.trade_geometry.config import (
    config_snapshot, current_tg_config_sha256, grid, grid_sha256, hold_geometry, path_length, reference_geometry,
)

GRID_SHA256 = "2918338e591afe85bc2e3d5f497966b04a83c9d191f1ba44885053733bcd6597"


def test_the_grid_is_36_geometries_in_predeclared_order():
    geometries = grid()
    assert len(geometries) == 36 and [g.grid_index for g in geometries] == list(range(36))
    assert [g.key for g in geometries[:3]] == ["H1:Tnone:Snone", "H1:Tnone:S1.0", "H1:Tnone:S2.0"]
    assert geometries[35].key == "H7:T3.0:S2.0" and geometries[27].key == "H7:Tnone:Snone"
    assert [g.barriers for g in geometries[:9]] == [0, 1, 1, 1, 2, 2, 1, 2, 2]
    assert hold_geometry(5).key == "H5:Tnone:Snone" and path_length() == 7


def test_the_reference_is_fixed_percentages_and_never_in_the_grid():
    reference = reference_geometry()
    assert (reference.key, reference.is_reference, reference.grid_index) == ("REF:H3:T0.05:S0.03", True, None)
    assert reference.key not in {g.key for g in grid()}


def test_the_grid_hash_is_pinned_and_moves_with_the_grid(monkeypatch):
    assert grid_sha256() == GRID_SHA256
    monkeypatch.setattr(settings, "tg_stop_atr_multiples", (None, 1.0, 2.5))
    assert grid_sha256() != GRID_SHA256


def test_snapshot_holds_every_threshold_and_the_spg_values_it_uses():
    snapshot = config_snapshot()
    assert snapshot["tg_selector_horizons"] == [3, 5] and snapshot["tg_holding_horizons"] == [1, 3, 5, 7]
    assert snapshot["tg_target_atr_multiples"] == [None, 1.5, 3.0]
    assert snapshot["tg_stop_atr_multiples"] == [None, 1.0, 2.0]
    assert (snapshot["tg_min_net_excess"], snapshot["tg_min_wf_trades"], snapshot["tg_min_holdout_trades"]) == (
        0.0050, 2000, 100)
    assert (snapshot["tg_min_wf_folds"], snapshot["tg_min_fold_positive_share"]) == (24, 0.55)
    assert (snapshot["tg_reality_check_alpha"], snapshot["tg_simplicity_tolerance"]) == (0.05, 0.0010)
    assert (snapshot["tg_max_unresolved_share"], snapshot["tg_max_shadow_missing_share"]) == (0.02, 0.10)
    assert snapshot["tg_holdout_not_before"] == "2026-11"  # open question 3
    assert (snapshot["tg_bootstrap_draws"], snapshot["tg_bootstrap_seed"], snapshot["tg_decision_validity_days"]) == (
        10000, 42, 45)
    assert (snapshot["round_trip_cost"], snapshot["top_k"], snapshot["min_benchmark_stocks"]) == (0.0030, 10, 100)
    assert (snapshot["min_plausible_return"], snapshot["max_plausible_return"]) == (-0.60, 1.50)
    assert (snapshot["tg_rule_version"], snapshot["dataset_version"], snapshot["base_dataset_version"]) == (
        "TG-001", "TG-DS-001", "SEL-DS-001")
    assert snapshot["reference_geometry"] == {"holding_sessions": 3, "target_pct": 0.05, "stop_pct": 0.03}
    assert (snapshot["reality_check_block_length"], snapshot["parity_tolerance"]) == (7, 1e-6)
    assert snapshot["grid_sha256"] == GRID_SHA256 and snapshot["spg_config_sha256"] == current_config_sha256()
    assert current_tg_config_sha256() == config_sha256(snapshot)


def test_the_tg_hash_moves_with_a_tg_or_an_spg_setting_and_spgs_hash_ignores_tg(monkeypatch):
    first, spg = current_tg_config_sha256(), current_config_sha256()
    monkeypatch.setattr(settings, "tg_min_net_excess", 0.0040)
    assert current_tg_config_sha256() != first and current_config_sha256() == spg
    monkeypatch.setattr(settings, "tg_min_net_excess", 0.0050)
    monkeypatch.setattr(settings, "selection_min_history_bars", 61)
    assert current_tg_config_sha256() != first and current_config_sha256() != spg
```

- [ ] **Step 3: Run it; expect FAIL**

Run: `DATABASE_URL="sqlite:///$SCRATCH/tg-1.db" python -m pytest tests/test_trade_geometry_config.py -q -p no:cacheprovider`
Expected: collection error `ModuleNotFoundError: No module named 'app.trade_geometry'`.

- [ ] **Step 4: Implement**

`app/trade_geometry/__init__.py`:

```python
"""TG-001 trade geometry validation (marksy-os docs/superpowers/specs/2026-10-01-marksy-tg-001-trade-geometry-design.md)."""
```

`app/trade_geometry/constants.py`:

```python
"""TG-001 vocabulary. Codes SPG already defines are imported from app.selection_gate.constants, never redefined."""
from __future__ import annotations

TG_RULE_VERSION = "TG-001"
TG_DATASET_VERSION = "TG-DS-001"
TG_HOLDOUT_REGISTRY_VERSION = "TG-HOLDOUT-001"
REFERENCE_GEOMETRY = {"holding_sessions": 3, "target_pct": 0.05, "stop_pct": 0.03}
TG_PARITY_TOLERANCE = 1e-6

DECISION_GEOMETRY_PASS = "GEOMETRY_PASS"
DECISION_NO_GEOMETRY = "NO_GEOMETRY"

OUTCOME_TARGET = "TARGET"
OUTCOME_STOP = "STOP"
OUTCOME_EXPIRED = "EXPIRED"

PATH_BAR_MISSING = "PATH_BAR_MISSING"
NOT_IN_DATASET = "NOT_IN_DATASET"
NOT_BENCHMARKABLE = "NOT_BENCHMARKABLE"  # unresolved_by_status key: path RESOLVED, (D, H) below the benchmark floor

ROLE_CHOSEN = "CHOSEN"
ROLE_REFERENCE = "REFERENCE"
SOURCE_SPG_WALK_FORWARD = "SPG_WALK_FORWARD"
SOURCE_SHADOW = "SHADOW"

REASON_UNSTABLE_ACROSS_FOLDS = "UNSTABLE_ACROSS_FOLDS"
REASON_NO_BOUND_SELECTION_DECISION = "NO_BOUND_SELECTION_DECISION"
REASON_NO_ELIGIBLE_GEOMETRY = "NO_ELIGIBLE_GEOMETRY"
REASON_FAMILY_WISE_NOT_SIGNIFICANT = "FAMILY_WISE_NOT_SIGNIFICANT"
REASON_HOLDOUT_NOT_YET_AVAILABLE = "HOLDOUT_NOT_YET_AVAILABLE"
REASON_SHADOW_COVERAGE = "DATA_INTEGRITY_SHADOW_COVERAGE"

# §18: reserved for a future authorize_geometry; nothing in TG-001 returns them.
REASON_NO_GEOMETRY_ON_RECORD = "NO_GEOMETRY_ON_RECORD"
REASON_GEOMETRY_DECISION_MISMATCH = "GEOMETRY_DECISION_MISMATCH"
REASON_GEOMETRY_LINEAGE_MISMATCH = "GEOMETRY_LINEAGE_MISMATCH"
REASON_GEOMETRY_DECISION_STALE = "GEOMETRY_DECISION_STALE"
REASON_GEOMETRY_DECISION_INVALID = "GEOMETRY_DECISION_INVALID"
REASON_GEOMETRY_AUTHORIZATION_ERROR = "GEOMETRY_AUTHORIZATION_ERROR"


def display_reason(reason: str | None) -> str | None:
    return None if reason is None else f"{DECISION_NO_GEOMETRY} — {reason}"
```

`app/trade_geometry/config.py` **(depends on open question 1)**: under fixed percentages, `grid()` builds `Geometry(h, target_pct=t, stop_pct=s, grid_index=k)` from `tg_target_pcts`/`tg_stop_pcts`, and `Geometry.key` prints the percentages:

```python
"""TG-001 §10.1/§20: the predeclared grid, its hash, and the configuration snapshot hashed onto each decision."""
from __future__ import annotations

import hashlib
import json
from dataclasses import dataclass

from app.selection_gate.config import config_sha256, current_config_sha256
from app.selection_gate.constants import DATASET_VERSION, UNIVERSE_RULE_VERSION
from app.settings import settings

from .constants import (
    REFERENCE_GEOMETRY, TG_DATASET_VERSION, TG_HOLDOUT_REGISTRY_VERSION, TG_PARITY_TOLERANCE, TG_RULE_VERSION,
)

TG_SETTINGS = (
    "tg_selector_horizons",
    "tg_holding_horizons",
    "tg_target_atr_multiples",
    "tg_stop_atr_multiples",
    "tg_min_net_excess",
    "tg_min_wf_trades",
    "tg_min_holdout_trades",
    "tg_min_wf_folds",
    "tg_min_fold_positive_share",
    "tg_reality_check_alpha",
    "tg_simplicity_tolerance",
    "tg_max_unresolved_share",
    "tg_max_shadow_missing_share",
    "tg_holdout_not_before",
    "tg_bootstrap_draws",
    "tg_bootstrap_seed",
    "tg_decision_validity_days",
)
# §20: SPG values TG uses, copied by value so a TG decision is reproducible from its own snapshot.
SPG_VALUES = {
    "round_trip_cost": "selection_round_trip_cost",
    "top_k": "selection_top_k",
    "min_benchmark_stocks": "selection_min_benchmark_stocks",
    "min_plausible_return": "selection_min_plausible_return",
    "max_plausible_return": "selection_max_plausible_return",
}


@dataclass(frozen=True)
class Geometry:
    holding_sessions: int
    target_atr_multiple: float | None = None
    stop_atr_multiple: float | None = None
    grid_index: int | None = None
    target_pct: float | None = None
    stop_pct: float | None = None
    is_reference: bool = False

    @property
    def barriers(self) -> int:
        target = self.target_atr_multiple if self.target_pct is None else self.target_pct
        stop = self.stop_atr_multiple if self.stop_pct is None else self.stop_pct
        return int(target is not None) + int(stop is not None)

    @property
    def key(self) -> str:
        if self.is_reference:
            return f"REF:H{self.holding_sessions}:T{self.target_pct}:S{self.stop_pct}"
        return f"H{self.holding_sessions}:T{_multiple(self.target_atr_multiple)}:S{_multiple(self.stop_atr_multiple)}"


def _multiple(value: float | None) -> str:
    return "none" if value is None else f"{value:.1f}"


def _plain(value):
    if isinstance(value, (tuple, list)):
        return [_plain(item) for item in value]
    return value


def grid(source=settings) -> tuple[Geometry, ...]:
    """§10.1 grid order: H ascending, then target and stop multiples in tuple order."""
    geometries: list[Geometry] = []
    for horizon in sorted(int(h) for h in source.tg_holding_horizons):
        for target in source.tg_target_atr_multiples:
            for stop in source.tg_stop_atr_multiples:
                geometries.append(Geometry(horizon, target, stop, grid_index=len(geometries)))
    return tuple(geometries)


def reference_geometry() -> Geometry:
    return Geometry(REFERENCE_GEOMETRY["holding_sessions"], target_pct=REFERENCE_GEOMETRY["target_pct"],
                    stop_pct=REFERENCE_GEOMETRY["stop_pct"], is_reference=True)


def hold_geometry(horizon: int) -> Geometry:
    """`(H, None, None)`: the universe held to H, the `b_(H,None,None)` of §11."""
    return Geometry(int(horizon))


def path_length(source=settings) -> int:
    return max(int(h) for h in source.tg_holding_horizons)


def grid_sha256(source=settings) -> str:
    canonical = json.dumps(
        {"grid": [[g.holding_sessions, g.target_atr_multiple, g.stop_atr_multiple] for g in grid(source)],
         "reference": dict(REFERENCE_GEOMETRY)},
        sort_keys=True, separators=(",", ":"),
    )
    return hashlib.sha256(canonical.encode("utf-8")).hexdigest()


def config_snapshot(source=settings) -> dict:
    snapshot = {name: _plain(getattr(source, name)) for name in TG_SETTINGS}
    snapshot.update({alias: getattr(source, name) for alias, name in SPG_VALUES.items()})
    snapshot.update(
        tg_rule_version=TG_RULE_VERSION,
        dataset_version=TG_DATASET_VERSION,
        base_dataset_version=DATASET_VERSION,
        universe_rule_version=UNIVERSE_RULE_VERSION,
        holdout_registry_version=TG_HOLDOUT_REGISTRY_VERSION,
        reference_geometry=dict(REFERENCE_GEOMETRY),
        reality_check_block_length=path_length(source),
        parity_tolerance=TG_PARITY_TOLERANCE,
        grid_sha256=grid_sha256(source),
        spg_config_sha256=current_config_sha256(),
    )
    return snapshot


def current_tg_config_sha256() -> str:
    return config_sha256(config_snapshot())
```

In `app/settings.py`, directly after `selection_decision_validity_days: int = 45`, add **(depends on open questions 1 and 3)**:

```python

    # TG-001 trade geometry validation (spec §20); all are hashed into every TG decision.
    tg_selector_horizons: tuple[int, ...] = (3, 5)
    tg_holding_horizons: tuple[int, ...] = (1, 3, 5, 7)
    tg_target_atr_multiples: tuple[float | None, ...] = (None, 1.5, 3.0)  # open question 1: ATR-scaled
    tg_stop_atr_multiples: tuple[float | None, ...] = (None, 1.0, 2.0)
    tg_min_net_excess: float = 0.0050
    tg_min_wf_trades: int = 2000
    tg_min_holdout_trades: int = 100
    tg_min_wf_folds: int = 24
    tg_min_fold_positive_share: float = 0.55
    tg_reality_check_alpha: float = 0.05
    tg_simplicity_tolerance: float = 0.0010
    tg_max_unresolved_share: float = 0.02
    tg_max_shadow_missing_share: float = 0.10
    tg_holdout_not_before: str = "2026-11"  # open question 3: the first full month after the TG freeze
    tg_bootstrap_draws: int = 10000
    tg_bootstrap_seed: int = 42
    tg_decision_validity_days: int = 45
```

- [ ] **Step 5: Run; expect PASS (5 passed)**

Run: `DATABASE_URL="sqlite:///$SCRATCH/tg-1.db" python -m pytest tests/test_trade_geometry_config.py tests/test_selection_gate_config.py -q -p no:cacheprovider`
Expected: all pass. The SPG config test proves SPG's snapshot did not move.

- [ ] **Step 6: Commit**

```bash
git add app/trade_geometry app/settings.py tests/test_trade_geometry_config.py
git commit -m "TG-001: constants, predeclared grid and configuration snapshot" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 2: Schema, migration 0190, append-only listeners

**Files:**
- Modify: `app/models.py`. Insert the five classes after `class SelectionHoldoutUsage` (it ends at line 2277 at `e5812e5`), before `class MultiplicityGuardDecision`. Every name they use is already imported at the top of the file.
- Create: `migrations/versions/0190_trade_geometry.py`, and `app/trade_geometry/records.py`, which holds only the listeners in this task. Task 10 replaces it.
- Test: `tests/test_trade_geometry_schema.py`, `tests/test_trade_geometry_migration.py`

**Interfaces:**
- Produces: ORM classes `TradeGeometryHoldoutWindow` (`tg_holdout_windows`), `TradeGeometryHoldoutUsage` (`tg_holdout_usages`), `TradeGeometryDecision` (`tg_decisions`), `TradeGeometryResult` (`tg_geometry_results`) and `TradeGeometryTrade` (`tg_trades`), with the column names of spec §16. Also `records.TradeGeometryImmutableError` and `records.APPEND_ONLY_MODELS`.

- [ ] **Step 1: Write the failing tests**

`tests/test_trade_geometry_schema.py`:

```python
from datetime import datetime, timezone

import pytest
import sqlalchemy as sa
from sqlalchemy import create_engine
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import sessionmaker

from app.db import Base
from app.models import TradeGeometryHoldoutWindow
from app.trade_geometry.records import TradeGeometryImmutableError

AT = datetime(2026, 10, 11, 20, 30, tzinfo=timezone.utc)
TABLES = {"tg_decisions", "tg_geometry_results", "tg_trades", "tg_holdout_windows", "tg_holdout_usages"}


@pytest.fixture
def session():
    engine = create_engine("sqlite:///:memory:")
    Base.metadata.create_all(engine)
    with sessionmaker(bind=engine)() as db:
        yield db


def test_the_five_tables_exist_with_their_keys(session):
    inspector = sa.inspect(session.get_bind())
    assert TABLES <= set(inspector.get_table_names())
    assert {"uq_tg_decision_uuid"} <= {u["name"] for u in inspector.get_unique_constraints("tg_decisions")}
    assert "ix_tg_decisions_selector" in {i["name"] for i in inspector.get_indexes("tg_decisions")}
    assert {"uq_tg_trade"} <= {u["name"] for u in inspector.get_unique_constraints("tg_trades")}
    assert {"uq_tg_geometry_result"} <= {u["name"] for u in inspector.get_unique_constraints("tg_geometry_results")}
    assert {"uq_tg_holdout_usage_label"} <= {u["name"] for u in inspector.get_unique_constraints("tg_holdout_usages")}
    columns = {c["name"] for c in inspector.get_columns("tg_decisions")}
    assert {"bound_spg_decision_id", "wf_tolerance_set", "reality_check_p", "ho_shadow_artefacts", "parity",
            "wf_mean_excess_vs_hold", "ho_mean_time_to_stop", "wf_folds"} <= columns
    assert not {c for c in columns if c.startswith("ho_") and c.endswith("_folds")}


def test_a_window_label_is_unique(session):
    for _ in range(2):
        session.add(TradeGeometryHoldoutWindow(label="TG-001:SEL-001:hsel3:2026-11", model_version="SEL-001",
                                               selector_horizon=3, holdout_month="2026-11", window_start=AT,
                                               window_end=AT, registered_at=AT, registry_version="TG-HOLDOUT-001"))
    with pytest.raises(IntegrityError):
        session.flush()


def test_tg_rows_cannot_change(session):
    row = TradeGeometryHoldoutWindow(label="TG-001:SEL-001:hsel5:2026-11", model_version="SEL-001",
                                     selector_horizon=5, holdout_month="2026-11", window_start=AT, window_end=AT,
                                     registered_at=AT, registry_version="TG-HOLDOUT-001")
    session.add(row)
    session.flush()
    row.registered_at = AT.replace(day=12)
    with pytest.raises(TradeGeometryImmutableError):
        session.flush()
```

`tests/test_trade_geometry_migration.py`. The first test also fails on any drift between the migration and the models, comparing names, SQLite DDL types and nullability:

```python
import sqlalchemy as sa
from sqlalchemy.dialects import sqlite

import app.models  # noqa: F401 -- registers the tables on Base.metadata
from app.db import Base
from tests._migration_helpers import run_revision

REVISION = "0190_trade_geometry"
TABLES = ("tg_holdout_windows", "tg_holdout_usages", "tg_decisions", "tg_geometry_results", "tg_trades")


def _predecessor_schema(connection):
    metadata = sa.MetaData()
    for name in ("stocks", "selection_gate_decisions"):
        sa.Table(name, metadata, sa.Column("id", sa.Integer, primary_key=True))
    metadata.create_all(connection)


def test_upgrade_creates_the_five_tables_exactly_as_the_models_declare():
    engine = sa.create_engine("sqlite:///:memory:")
    with engine.begin() as connection:
        _predecessor_schema(connection)
        run_revision(connection, REVISION)
        inspector = sa.inspect(connection)
        assert set(TABLES) <= set(inspector.get_table_names())
        assert "ix_tg_decisions_selector" in {i["name"] for i in inspector.get_indexes("tg_decisions")}
        dialect = sqlite.dialect()
        for table in TABLES:
            migrated = {c["name"]: (str(c["type"]), c["nullable"]) for c in inspector.get_columns(table)}
            declared = {c.name: (c.type.compile(dialect=dialect), c.nullable)
                        for c in Base.metadata.tables[table].columns}
            assert migrated == declared, table


def test_downgrade_removes_everything_it_added():
    engine = sa.create_engine("sqlite:///:memory:")
    with engine.begin() as connection:
        _predecessor_schema(connection)
        run_revision(connection, REVISION)
        run_revision(connection, REVISION, step="downgrade")
        assert not set(TABLES) & set(sa.inspect(connection).get_table_names())
```

- [ ] **Step 2: Run; expect FAIL**

Run: `DATABASE_URL="sqlite:///$SCRATCH/tg-2.db" python -m pytest tests/test_trade_geometry_schema.py tests/test_trade_geometry_migration.py -q -p no:cacheprovider`
Expected: `ImportError: cannot import name 'TradeGeometryHoldoutWindow' from 'app.models'`.

- [ ] **Step 3: Implement**

In `app/models.py`, after `class SelectionHoldoutUsage`:

```python
class TradeGeometryHoldoutWindow(Base):
    """TG-001 §16.4: a TG exam month, registered once per selector."""

    __tablename__ = "tg_holdout_windows"
    __table_args__ = (UniqueConstraint("label", name="uq_tg_holdout_window_label"),)

    id: Mapped[int] = mapped_column(BigInteger().with_variant(Integer, "sqlite"), primary_key=True)
    label: Mapped[str] = mapped_column(String(128))
    model_version: Mapped[str] = mapped_column(String(64))
    selector_horizon: Mapped[int] = mapped_column(Integer)
    holdout_month: Mapped[str] = mapped_column(String(7))
    window_start: Mapped[datetime] = mapped_column(DateTime(timezone=True))
    window_end: Mapped[datetime] = mapped_column(DateTime(timezone=True))
    registered_at: Mapped[datetime] = mapped_column(DateTime(timezone=True))
    registry_version: Mapped[str] = mapped_column(String(32))
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=func.now())


class TradeGeometryHoldoutUsage(Base):
    """TG-001 §13.4/§16.5: the freeze. Committed before any held-out path is read; spends the month."""

    __tablename__ = "tg_holdout_usages"
    __table_args__ = (UniqueConstraint("holdout_label", name="uq_tg_holdout_usage_label"),)

    id: Mapped[int] = mapped_column(BigInteger().with_variant(Integer, "sqlite"), primary_key=True)
    holdout_label: Mapped[str] = mapped_column(String(128))
    window_id: Mapped[int] = mapped_column(ForeignKey("tg_holdout_windows.id"))
    model_version: Mapped[str] = mapped_column(String(64))
    selector_horizon: Mapped[int] = mapped_column(Integer)
    holdout_month: Mapped[str] = mapped_column(String(7))
    bound_spg_decision_id: Mapped[int] = mapped_column(ForeignKey("selection_gate_decisions.id"))
    geometry_key: Mapped[str] = mapped_column(String(32))
    holding_sessions: Mapped[int] = mapped_column(Integer)
    target_atr_multiple: Mapped[Decimal | None] = mapped_column(Numeric(6, 3))
    stop_atr_multiple: Mapped[Decimal | None] = mapped_column(Numeric(6, 3))
    grid_sha256: Mapped[str] = mapped_column(String(64))
    config_sha256: Mapped[str] = mapped_column(String(64))
    dataset_sha256: Mapped[str] = mapped_column(String(64))
    wf_selection_sha256: Mapped[str] = mapped_column(String(64))
    used_at: Mapped[datetime] = mapped_column(DateTime(timezone=True))


class TradeGeometryDecision(Base):
    """TG-001 §16.1: one append-only geometry decision per selector run."""

    __tablename__ = "tg_decisions"
    __table_args__ = (
        UniqueConstraint("decision_uuid", name="uq_tg_decision_uuid"),
        Index("ix_tg_decisions_selector", "model_version", "selector_horizon", "id"),
    )

    id: Mapped[int] = mapped_column(BigInteger().with_variant(Integer, "sqlite"), primary_key=True)
    decision_uuid: Mapped[str] = mapped_column(String(36))
    model_version: Mapped[str] = mapped_column(String(64))
    selector_horizon: Mapped[int] = mapped_column(Integer)
    tg_rule_version: Mapped[str] = mapped_column(String(32))
    dataset_version: Mapped[str] = mapped_column(String(32))
    base_dataset_version: Mapped[str] = mapped_column(String(32))
    universe_rule_version: Mapped[str] = mapped_column(String(32))
    bound_spg_decision_id: Mapped[int | None] = mapped_column(ForeignKey("selection_gate_decisions.id"))
    grid_sha256: Mapped[str] = mapped_column(String(64))
    config_snapshot: Mapped[dict] = mapped_column(JSON)
    config_sha256: Mapped[str] = mapped_column(String(64))
    code_version: Mapped[str] = mapped_column(String(64))
    dataset_sha256: Mapped[str | None] = mapped_column(String(64))
    ho_dataset_sha256: Mapped[str | None] = mapped_column(String(64))
    wf_selection_sha256: Mapped[str | None] = mapped_column(String(64))
    ho_selection_sha256: Mapped[str | None] = mapped_column(String(64))

    dataset_first_session: Mapped[date | None] = mapped_column(Date)
    dataset_last_session: Mapped[date | None] = mapped_column(Date)
    wf_first_session: Mapped[date | None] = mapped_column(Date)
    wf_last_session: Mapped[date | None] = mapped_column(Date)
    wf_sessions: Mapped[int | None] = mapped_column(Integer)
    wf_protected_excluded: Mapped[int | None] = mapped_column(Integer)
    holdout_month: Mapped[str | None] = mapped_column(String(7))
    holdout_first_session: Mapped[date | None] = mapped_column(Date)
    holdout_last_session: Mapped[date | None] = mapped_column(Date)
    holdout_label: Mapped[str | None] = mapped_column(String(128))
    holdout_window_id: Mapped[int | None] = mapped_column(ForeignKey("tg_holdout_windows.id"))
    holdout_usage_id: Mapped[int | None] = mapped_column(ForeignKey("tg_holdout_usages.id"))

    holding_sessions: Mapped[int | None] = mapped_column(Integer)
    target_atr_multiple: Mapped[Decimal | None] = mapped_column(Numeric(6, 3))
    stop_atr_multiple: Mapped[Decimal | None] = mapped_column(Numeric(6, 3))
    geometry_key: Mapped[str | None] = mapped_column(String(32))
    grid_index: Mapped[int | None] = mapped_column(Integer)

    wf_eligible_geometries: Mapped[int | None] = mapped_column(Integer)
    wf_tolerance_set: Mapped[list | None] = mapped_column(JSON)
    reality_check_p: Mapped[Decimal | None] = mapped_column(Numeric(8, 6))
    reality_check_statistic: Mapped[Decimal | None] = mapped_column(Numeric(12, 8))
    reality_check_geometries: Mapped[int | None] = mapped_column(Integer)

    wf_candidates: Mapped[int | None] = mapped_column(Integer)
    wf_duplicates_removed: Mapped[int | None] = mapped_column(Integer)
    wf_overlap_suppressed: Mapped[int | None] = mapped_column(Integer)
    wf_unresolved_trades: Mapped[int | None] = mapped_column(Integer)
    wf_unresolved_by_status: Mapped[dict | None] = mapped_column(JSON)
    wf_trades: Mapped[int | None] = mapped_column(Integer)
    wf_min_trades: Mapped[int | None] = mapped_column(Integer)
    wf_mean_gross: Mapped[Decimal | None] = mapped_column(Numeric(12, 8))
    wf_mean_benchmark: Mapped[Decimal | None] = mapped_column(Numeric(12, 8))
    wf_cost: Mapped[Decimal | None] = mapped_column(Numeric(12, 8))
    wf_mean_net: Mapped[Decimal | None] = mapped_column(Numeric(12, 8))
    wf_mean_excess: Mapped[Decimal | None] = mapped_column(Numeric(12, 8))
    wf_ci_low: Mapped[Decimal | None] = mapped_column(Numeric(12, 8))
    wf_ci_high: Mapped[Decimal | None] = mapped_column(Numeric(12, 8))
    wf_bootstrap_sessions: Mapped[int | None] = mapped_column(Integer)
    wf_mean_excess_vs_hold: Mapped[Decimal | None] = mapped_column(Numeric(12, 8))
    wf_target_rate: Mapped[Decimal | None] = mapped_column(Numeric(12, 8))
    wf_stop_rate: Mapped[Decimal | None] = mapped_column(Numeric(12, 8))
    wf_expired_rate: Mapped[Decimal | None] = mapped_column(Numeric(12, 8))
    wf_ambiguous_rate: Mapped[Decimal | None] = mapped_column(Numeric(12, 8))
    wf_mean_mfe: Mapped[Decimal | None] = mapped_column(Numeric(12, 8))
    wf_mean_mae: Mapped[Decimal | None] = mapped_column(Numeric(12, 8))
    wf_mean_mdd: Mapped[Decimal | None] = mapped_column(Numeric(12, 8))
    wf_mean_time_to_target: Mapped[Decimal | None] = mapped_column(Numeric(8, 4))
    wf_mean_time_to_stop: Mapped[Decimal | None] = mapped_column(Numeric(8, 4))
    wf_turnover: Mapped[Decimal | None] = mapped_column(Numeric(12, 8))
    wf_cost_drag: Mapped[Decimal | None] = mapped_column(Numeric(12, 8))
    wf_result: Mapped[str | None] = mapped_column(String(8))
    wf_reasons: Mapped[list | None] = mapped_column(JSON)
    wf_folds_tested: Mapped[int | None] = mapped_column(Integer)
    wf_fold_positive_share: Mapped[Decimal | None] = mapped_column(Numeric(12, 8))
    wf_median_fold_excess: Mapped[Decimal | None] = mapped_column(Numeric(12, 8))
    wf_folds: Mapped[list | None] = mapped_column(JSON)

    ho_candidates: Mapped[int | None] = mapped_column(Integer)
    ho_duplicates_removed: Mapped[int | None] = mapped_column(Integer)
    ho_overlap_suppressed: Mapped[int | None] = mapped_column(Integer)
    ho_unresolved_trades: Mapped[int | None] = mapped_column(Integer)
    ho_unresolved_by_status: Mapped[dict | None] = mapped_column(JSON)
    ho_trades: Mapped[int | None] = mapped_column(Integer)
    ho_min_trades: Mapped[int | None] = mapped_column(Integer)
    ho_mean_gross: Mapped[Decimal | None] = mapped_column(Numeric(12, 8))
    ho_mean_benchmark: Mapped[Decimal | None] = mapped_column(Numeric(12, 8))
    ho_cost: Mapped[Decimal | None] = mapped_column(Numeric(12, 8))
    ho_mean_net: Mapped[Decimal | None] = mapped_column(Numeric(12, 8))
    ho_mean_excess: Mapped[Decimal | None] = mapped_column(Numeric(12, 8))
    ho_ci_low: Mapped[Decimal | None] = mapped_column(Numeric(12, 8))
    ho_ci_high: Mapped[Decimal | None] = mapped_column(Numeric(12, 8))
    ho_bootstrap_sessions: Mapped[int | None] = mapped_column(Integer)
    ho_mean_excess_vs_hold: Mapped[Decimal | None] = mapped_column(Numeric(12, 8))
    ho_target_rate: Mapped[Decimal | None] = mapped_column(Numeric(12, 8))
    ho_stop_rate: Mapped[Decimal | None] = mapped_column(Numeric(12, 8))
    ho_expired_rate: Mapped[Decimal | None] = mapped_column(Numeric(12, 8))
    ho_ambiguous_rate: Mapped[Decimal | None] = mapped_column(Numeric(12, 8))
    ho_mean_mfe: Mapped[Decimal | None] = mapped_column(Numeric(12, 8))
    ho_mean_mae: Mapped[Decimal | None] = mapped_column(Numeric(12, 8))
    ho_mean_mdd: Mapped[Decimal | None] = mapped_column(Numeric(12, 8))
    ho_mean_time_to_target: Mapped[Decimal | None] = mapped_column(Numeric(8, 4))
    ho_mean_time_to_stop: Mapped[Decimal | None] = mapped_column(Numeric(8, 4))
    ho_turnover: Mapped[Decimal | None] = mapped_column(Numeric(12, 8))
    ho_cost_drag: Mapped[Decimal | None] = mapped_column(Numeric(12, 8))
    ho_result: Mapped[str | None] = mapped_column(String(8))
    ho_reasons: Mapped[list | None] = mapped_column(JSON)
    ho_sessions: Mapped[int | None] = mapped_column(Integer)
    ho_shadow_sessions_covered: Mapped[int | None] = mapped_column(Integer)
    ho_shadow_missing_share: Mapped[Decimal | None] = mapped_column(Numeric(12, 8))
    ho_shadow_artefacts: Mapped[dict | None] = mapped_column(JSON)

    decision: Mapped[str] = mapped_column(String(16))
    primary_stage: Mapped[str | None] = mapped_column(String(16))
    primary_reason: Mapped[str | None] = mapped_column(String(64))
    reasons: Mapped[list] = mapped_column(JSON)
    parity: Mapped[dict | None] = mapped_column(JSON)
    decided_at: Mapped[datetime] = mapped_column(DateTime(timezone=True))
    valid_until: Mapped[datetime] = mapped_column(DateTime(timezone=True))
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=func.now())


class TradeGeometryResult(Base):
    """TG-001 §16.2: every walk-forward geometry of a decision, 36 grid rows plus the reference."""

    __tablename__ = "tg_geometry_results"
    __table_args__ = (UniqueConstraint("decision_id", "geometry_key", name="uq_tg_geometry_result"),)

    id: Mapped[int] = mapped_column(BigInteger().with_variant(Integer, "sqlite"), primary_key=True)
    decision_id: Mapped[int] = mapped_column(ForeignKey("tg_decisions.id"))
    geometry_key: Mapped[str] = mapped_column(String(32))
    grid_index: Mapped[int | None] = mapped_column(Integer)
    is_reference: Mapped[bool] = mapped_column(Boolean)
    holding_sessions: Mapped[int] = mapped_column(Integer)
    target_atr_multiple: Mapped[Decimal | None] = mapped_column(Numeric(6, 3))
    stop_atr_multiple: Mapped[Decimal | None] = mapped_column(Numeric(6, 3))
    target_pct: Mapped[Decimal | None] = mapped_column(Numeric(12, 8))
    stop_pct: Mapped[Decimal | None] = mapped_column(Numeric(12, 8))
    candidates: Mapped[int] = mapped_column(Integer)
    duplicates_removed: Mapped[int] = mapped_column(Integer)
    overlap_suppressed: Mapped[int] = mapped_column(Integer)
    unresolved_trades: Mapped[int] = mapped_column(Integer)
    unresolved_by_status: Mapped[dict] = mapped_column(JSON)
    trades: Mapped[int] = mapped_column(Integer)
    min_trades: Mapped[int] = mapped_column(Integer)
    unresolved_share: Mapped[Decimal | None] = mapped_column(Numeric(12, 8))
    mean_gross: Mapped[Decimal | None] = mapped_column(Numeric(12, 8))
    mean_benchmark: Mapped[Decimal | None] = mapped_column(Numeric(12, 8))
    cost: Mapped[Decimal] = mapped_column(Numeric(12, 8))
    mean_net: Mapped[Decimal | None] = mapped_column(Numeric(12, 8))
    mean_excess: Mapped[Decimal | None] = mapped_column(Numeric(12, 8))
    ci_low: Mapped[Decimal | None] = mapped_column(Numeric(12, 8))
    ci_high: Mapped[Decimal | None] = mapped_column(Numeric(12, 8))
    bootstrap_sessions: Mapped[int] = mapped_column(Integer)
    mean_excess_vs_hold: Mapped[Decimal | None] = mapped_column(Numeric(12, 8))
    target_rate: Mapped[Decimal | None] = mapped_column(Numeric(12, 8))
    stop_rate: Mapped[Decimal | None] = mapped_column(Numeric(12, 8))
    expired_rate: Mapped[Decimal | None] = mapped_column(Numeric(12, 8))
    ambiguous_rate: Mapped[Decimal | None] = mapped_column(Numeric(12, 8))
    mean_mfe: Mapped[Decimal | None] = mapped_column(Numeric(12, 8))
    mean_mae: Mapped[Decimal | None] = mapped_column(Numeric(12, 8))
    mean_mdd: Mapped[Decimal | None] = mapped_column(Numeric(12, 8))
    mean_time_to_target: Mapped[Decimal | None] = mapped_column(Numeric(8, 4))
    mean_time_to_stop: Mapped[Decimal | None] = mapped_column(Numeric(8, 4))
    turnover: Mapped[Decimal | None] = mapped_column(Numeric(12, 8))
    cost_drag: Mapped[Decimal | None] = mapped_column(Numeric(12, 8))
    folds_tested: Mapped[int] = mapped_column(Integer)
    fold_positive_share: Mapped[Decimal | None] = mapped_column(Numeric(12, 8))
    median_fold_excess: Mapped[Decimal | None] = mapped_column(Numeric(12, 8))
    folds: Mapped[list] = mapped_column(JSON)
    benchmarkable_sessions: Mapped[int] = mapped_column(Integer)
    result: Mapped[str] = mapped_column(String(8))
    reasons: Mapped[list] = mapped_column(JSON)
    eligible: Mapped[bool] = mapped_column(Boolean)
    in_tolerance_set: Mapped[bool] = mapped_column(Boolean)
    chosen: Mapped[bool] = mapped_column(Boolean)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=func.now())


class TradeGeometryTrade(Base):
    """TG-001 §16.3: candidates of the chosen (WF and HO) and reference (WF) geometries, every disposition."""

    __tablename__ = "tg_trades"
    __table_args__ = (
        UniqueConstraint("decision_id", "geometry_role", "stage", "session_date", "stock_id", name="uq_tg_trade"),
    )

    id: Mapped[int] = mapped_column(BigInteger().with_variant(Integer, "sqlite"), primary_key=True)
    decision_id: Mapped[int] = mapped_column(ForeignKey("tg_decisions.id"))
    geometry_key: Mapped[str] = mapped_column(String(32))
    geometry_role: Mapped[str] = mapped_column(String(16))
    stage: Mapped[str] = mapped_column(String(16))
    source: Mapped[str] = mapped_column(String(24))
    source_row_id: Mapped[int] = mapped_column(BigInteger().with_variant(Integer, "sqlite"))
    artefact_sha256: Mapped[str | None] = mapped_column(String(64))
    session_date: Mapped[date] = mapped_column(Date)
    stock_id: Mapped[int] = mapped_column(ForeignKey("stocks.id"))
    selector_horizon: Mapped[int] = mapped_column(Integer)
    holding_sessions: Mapped[int] = mapped_column(Integer)
    rank: Mapped[int] = mapped_column(Integer)
    score: Mapped[Decimal] = mapped_column(Numeric(18, 10))
    entry_session: Mapped[date | None] = mapped_column(Date)
    exit_session: Mapped[date | None] = mapped_column(Date)
    entry_open: Mapped[Decimal | None] = mapped_column(Numeric(18, 6))
    atr_percent: Mapped[Decimal | None] = mapped_column(Numeric(12, 8))
    target_price: Mapped[Decimal | None] = mapped_column(Numeric(18, 6))
    stop_price: Mapped[Decimal | None] = mapped_column(Numeric(18, 6))
    exit_price_adjusted: Mapped[Decimal | None] = mapped_column(Numeric(18, 6))
    path_status: Mapped[str] = mapped_column(String(32))
    outcome: Mapped[str | None] = mapped_column(String(16))
    ambiguous: Mapped[bool | None] = mapped_column(Boolean)
    time_to_target: Mapped[int | None] = mapped_column(Integer)
    time_to_stop: Mapped[int | None] = mapped_column(Integer)
    mfe: Mapped[Decimal | None] = mapped_column(Numeric(12, 8))
    mae: Mapped[Decimal | None] = mapped_column(Numeric(12, 8))
    gross_return: Mapped[Decimal | None] = mapped_column(Numeric(12, 8))
    benchmark_return: Mapped[Decimal | None] = mapped_column(Numeric(12, 8))
    hold_benchmark_return: Mapped[Decimal | None] = mapped_column(Numeric(12, 8))
    cost: Mapped[Decimal] = mapped_column(Numeric(12, 8))
    net_return: Mapped[Decimal | None] = mapped_column(Numeric(12, 8))
    excess_return: Mapped[Decimal | None] = mapped_column(Numeric(12, 8))
    disposition: Mapped[str] = mapped_column(String(24))
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=func.now())
```

`app/trade_geometry/records.py` (listeners only; Task 10 replaces the whole file):

```python
"""TG-001 §16: the append-only TG store and its writers."""
from __future__ import annotations

from sqlalchemy import event

from app.models import (
    TradeGeometryDecision, TradeGeometryHoldoutUsage, TradeGeometryHoldoutWindow, TradeGeometryResult,
    TradeGeometryTrade,
)

APPEND_ONLY_MODELS = (
    TradeGeometryDecision, TradeGeometryResult, TradeGeometryTrade, TradeGeometryHoldoutWindow,
    TradeGeometryHoldoutUsage,
)


class TradeGeometryImmutableError(RuntimeError):
    pass


def _reject_change(mapper, connection, target):
    raise TradeGeometryImmutableError(f"{target.__tablename__} rows are append-only (TG-001)")


for _model in APPEND_ONLY_MODELS:
    event.listen(_model, "before_update", _reject_change)
    event.listen(_model, "before_delete", _reject_change)
```

`migrations/versions/0190_trade_geometry.py`:

```python
"""TG-001: trade geometry decisions, geometry results, trades, and TG's own exam registry.

Revision ID: 0190_trade_geometry
Revises: 0189_selection_publish_gate
"""
from __future__ import annotations

import sqlalchemy as sa
from alembic import op

revision = "0190_trade_geometry"
down_revision = "0189_selection_publish_gate"
branch_labels = None
depends_on = None

_ID = sa.BigInteger().with_variant(sa.Integer(), "sqlite")
_RETURN = sa.Numeric(12, 8)
_MULTIPLE = sa.Numeric(6, 3)
_COUNTS = ("candidates", "duplicates_removed", "overlap_suppressed", "unresolved_trades", "trades", "min_trades",
           "bootstrap_sessions")
_RETURNS = ("mean_gross", "mean_benchmark", "cost", "mean_net", "mean_excess", "ci_low", "ci_high",
            "mean_excess_vs_hold", "target_rate", "stop_rate", "expired_rate", "ambiguous_rate", "mean_mfe", "mean_mae",
            "mean_mdd", "turnover", "cost_drag")
_SESSION_MEANS = ("mean_time_to_target", "mean_time_to_stop")


def _stage(prefix: str, *, required: bool = False) -> list[sa.Column]:
    """§16.1 per-stage fields; `prefix` "" gives §16.2's unprefixed columns."""
    name = (lambda n: f"{prefix}_{n}") if prefix else (lambda n: n)
    return [
        *(sa.Column(name(n), sa.Integer(), nullable=not required) for n in _COUNTS),
        sa.Column(name("unresolved_by_status"), sa.JSON(), nullable=not required),
        *(sa.Column(name(n), _RETURN, nullable=not (required and n == "cost")) for n in _RETURNS),
        *(sa.Column(name(n), sa.Numeric(8, 4)) for n in _SESSION_MEANS),
        sa.Column(name("result"), sa.String(8), nullable=not required),
        sa.Column(name("reasons"), sa.JSON(), nullable=not required),
    ]


def _folds(prefix: str, *, required: bool = False) -> list[sa.Column]:
    name = (lambda n: f"{prefix}_{n}") if prefix else (lambda n: n)
    return [sa.Column(name("folds_tested"), sa.Integer(), nullable=not required),
            sa.Column(name("fold_positive_share"), _RETURN), sa.Column(name("median_fold_excess"), _RETURN),
            sa.Column(name("folds"), sa.JSON(), nullable=not required)]


def _created() -> sa.Column:
    return sa.Column("created_at", sa.DateTime(timezone=True), server_default=sa.func.now(), nullable=False)


def upgrade() -> None:
    op.create_table(
        "tg_holdout_windows",
        sa.Column("id", _ID, primary_key=True),
        sa.Column("label", sa.String(128), nullable=False),
        sa.Column("model_version", sa.String(64), nullable=False),
        sa.Column("selector_horizon", sa.Integer(), nullable=False),
        sa.Column("holdout_month", sa.String(7), nullable=False),
        sa.Column("window_start", sa.DateTime(timezone=True), nullable=False),
        sa.Column("window_end", sa.DateTime(timezone=True), nullable=False),
        sa.Column("registered_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("registry_version", sa.String(32), nullable=False),
        _created(),
        sa.UniqueConstraint("label", name="uq_tg_holdout_window_label"),
    )
    op.create_table(
        "tg_holdout_usages",
        sa.Column("id", _ID, primary_key=True),
        sa.Column("holdout_label", sa.String(128), nullable=False),
        sa.Column("window_id", _ID, sa.ForeignKey("tg_holdout_windows.id"), nullable=False),
        sa.Column("model_version", sa.String(64), nullable=False),
        sa.Column("selector_horizon", sa.Integer(), nullable=False),
        sa.Column("holdout_month", sa.String(7), nullable=False),
        sa.Column("bound_spg_decision_id", _ID, sa.ForeignKey("selection_gate_decisions.id"), nullable=False),
        sa.Column("geometry_key", sa.String(32), nullable=False),
        sa.Column("holding_sessions", sa.Integer(), nullable=False),
        sa.Column("target_atr_multiple", _MULTIPLE),
        sa.Column("stop_atr_multiple", _MULTIPLE),
        sa.Column("grid_sha256", sa.String(64), nullable=False),
        sa.Column("config_sha256", sa.String(64), nullable=False),
        sa.Column("dataset_sha256", sa.String(64), nullable=False),
        sa.Column("wf_selection_sha256", sa.String(64), nullable=False),
        sa.Column("used_at", sa.DateTime(timezone=True), nullable=False),
        sa.UniqueConstraint("holdout_label", name="uq_tg_holdout_usage_label"),
    )
    op.create_table(
        "tg_decisions",
        sa.Column("id", _ID, primary_key=True),
        sa.Column("decision_uuid", sa.String(36), nullable=False),
        sa.Column("model_version", sa.String(64), nullable=False),
        sa.Column("selector_horizon", sa.Integer(), nullable=False),
        sa.Column("tg_rule_version", sa.String(32), nullable=False),
        sa.Column("dataset_version", sa.String(32), nullable=False),
        sa.Column("base_dataset_version", sa.String(32), nullable=False),
        sa.Column("universe_rule_version", sa.String(32), nullable=False),
        sa.Column("bound_spg_decision_id", _ID, sa.ForeignKey("selection_gate_decisions.id")),
        sa.Column("grid_sha256", sa.String(64), nullable=False),
        sa.Column("config_snapshot", sa.JSON(), nullable=False),
        sa.Column("config_sha256", sa.String(64), nullable=False),
        sa.Column("code_version", sa.String(64), nullable=False),
        *(sa.Column(n, sa.String(64)) for n in ("dataset_sha256", "ho_dataset_sha256", "wf_selection_sha256",
                                                 "ho_selection_sha256")),
        *(sa.Column(n, sa.Date()) for n in ("dataset_first_session", "dataset_last_session", "wf_first_session",
                                             "wf_last_session")),
        sa.Column("wf_sessions", sa.Integer()),
        sa.Column("wf_protected_excluded", sa.Integer()),
        sa.Column("holdout_month", sa.String(7)),
        sa.Column("holdout_first_session", sa.Date()),
        sa.Column("holdout_last_session", sa.Date()),
        sa.Column("holdout_label", sa.String(128)),
        sa.Column("holdout_window_id", _ID, sa.ForeignKey("tg_holdout_windows.id")),
        sa.Column("holdout_usage_id", _ID, sa.ForeignKey("tg_holdout_usages.id")),
        sa.Column("holding_sessions", sa.Integer()),
        sa.Column("target_atr_multiple", _MULTIPLE),
        sa.Column("stop_atr_multiple", _MULTIPLE),
        sa.Column("geometry_key", sa.String(32)),
        sa.Column("grid_index", sa.Integer()),
        sa.Column("wf_eligible_geometries", sa.Integer()),
        sa.Column("wf_tolerance_set", sa.JSON()),
        sa.Column("reality_check_p", sa.Numeric(8, 6)),
        sa.Column("reality_check_statistic", _RETURN),
        sa.Column("reality_check_geometries", sa.Integer()),
        *_stage("wf"),
        *_folds("wf"),
        *_stage("ho"),
        sa.Column("ho_sessions", sa.Integer()),
        sa.Column("ho_shadow_sessions_covered", sa.Integer()),
        sa.Column("ho_shadow_missing_share", _RETURN),
        sa.Column("ho_shadow_artefacts", sa.JSON()),
        sa.Column("decision", sa.String(16), nullable=False),
        sa.Column("primary_stage", sa.String(16)),
        sa.Column("primary_reason", sa.String(64)),
        sa.Column("reasons", sa.JSON(), nullable=False),
        sa.Column("parity", sa.JSON()),
        sa.Column("decided_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("valid_until", sa.DateTime(timezone=True), nullable=False),
        _created(),
        sa.UniqueConstraint("decision_uuid", name="uq_tg_decision_uuid"),
    )
    op.create_index("ix_tg_decisions_selector", "tg_decisions", ["model_version", "selector_horizon", "id"])
    op.create_table(
        "tg_geometry_results",
        sa.Column("id", _ID, primary_key=True),
        sa.Column("decision_id", _ID, sa.ForeignKey("tg_decisions.id"), nullable=False),
        sa.Column("geometry_key", sa.String(32), nullable=False),
        sa.Column("grid_index", sa.Integer()),
        sa.Column("is_reference", sa.Boolean(), nullable=False),
        sa.Column("holding_sessions", sa.Integer(), nullable=False),
        sa.Column("target_atr_multiple", _MULTIPLE),
        sa.Column("stop_atr_multiple", _MULTIPLE),
        sa.Column("target_pct", _RETURN),
        sa.Column("stop_pct", _RETURN),
        *_stage("", required=True),
        sa.Column("unresolved_share", _RETURN),
        *_folds("", required=True),
        sa.Column("benchmarkable_sessions", sa.Integer(), nullable=False),
        sa.Column("eligible", sa.Boolean(), nullable=False),
        sa.Column("in_tolerance_set", sa.Boolean(), nullable=False),
        sa.Column("chosen", sa.Boolean(), nullable=False),
        _created(),
        sa.UniqueConstraint("decision_id", "geometry_key", name="uq_tg_geometry_result"),
    )
    op.create_table(
        "tg_trades",
        sa.Column("id", _ID, primary_key=True),
        sa.Column("decision_id", _ID, sa.ForeignKey("tg_decisions.id"), nullable=False),
        sa.Column("geometry_key", sa.String(32), nullable=False),
        sa.Column("geometry_role", sa.String(16), nullable=False),
        sa.Column("stage", sa.String(16), nullable=False),
        sa.Column("source", sa.String(24), nullable=False),
        sa.Column("source_row_id", _ID, nullable=False),
        sa.Column("artefact_sha256", sa.String(64)),
        sa.Column("session_date", sa.Date(), nullable=False),
        sa.Column("stock_id", _ID, sa.ForeignKey("stocks.id"), nullable=False),
        sa.Column("selector_horizon", sa.Integer(), nullable=False),
        sa.Column("holding_sessions", sa.Integer(), nullable=False),
        sa.Column("rank", sa.Integer(), nullable=False),
        sa.Column("score", sa.Numeric(18, 10), nullable=False),
        sa.Column("entry_session", sa.Date()),
        sa.Column("exit_session", sa.Date()),
        sa.Column("entry_open", sa.Numeric(18, 6)),
        sa.Column("atr_percent", _RETURN),
        sa.Column("target_price", sa.Numeric(18, 6)),
        sa.Column("stop_price", sa.Numeric(18, 6)),
        sa.Column("exit_price_adjusted", sa.Numeric(18, 6)),
        sa.Column("path_status", sa.String(32), nullable=False),
        sa.Column("outcome", sa.String(16)),
        sa.Column("ambiguous", sa.Boolean()),
        sa.Column("time_to_target", sa.Integer()),
        sa.Column("time_to_stop", sa.Integer()),
        *(sa.Column(n, _RETURN) for n in ("mfe", "mae", "gross_return", "benchmark_return", "hold_benchmark_return")),
        sa.Column("cost", _RETURN, nullable=False),
        sa.Column("net_return", _RETURN),
        sa.Column("excess_return", _RETURN),
        sa.Column("disposition", sa.String(24), nullable=False),
        _created(),
        sa.UniqueConstraint("decision_id", "geometry_role", "stage", "session_date", "stock_id", name="uq_tg_trade"),
    )


def downgrade() -> None:
    op.drop_table("tg_trades")
    op.drop_table("tg_geometry_results")
    op.drop_index("ix_tg_decisions_selector", table_name="tg_decisions")
    op.drop_table("tg_decisions")
    op.drop_table("tg_holdout_usages")
    op.drop_table("tg_holdout_windows")
```

- [ ] **Step 4: Run; expect PASS (5 passed), and a single head**

Run: `DATABASE_URL="sqlite:///$SCRATCH/tg-2.db" python -m pytest tests/test_trade_geometry_schema.py tests/test_trade_geometry_migration.py tests/test_alembic_single_head.py tests/test_selection_gate_schema.py tests/test_selection_gate_migration.py -q -p no:cacheprovider`
Expected: all pass. `test_migration_graph_has_exactly_one_head` sees `0190_trade_geometry`.

- [ ] **Step 5: Commit**

```bash
git add app/models.py app/trade_geometry/records.py migrations/versions/0190_trade_geometry.py tests/test_trade_geometry_schema.py tests/test_trade_geometry_migration.py
git commit -m "TG-001: tg_* tables, migration 0190 and append-only listeners" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 3: Path status, conservative fills, path statistics (pure)

**Files:**
- Create: `app/trade_geometry/fills.py`
- Test: `tests/test_trade_geometry_fills.py`

**Interfaces:**
- Consumes: `config.Geometry`, and `constants.OUTCOME_*` / `PATH_BAR_MISSING`. SPG `LABEL_RESOLVED`, `LABEL_NOT_YET_RESOLVABLE`, `LABEL_ENTRY_BAR_MISSING`, `LABEL_INVALID_PRICE`, `LABEL_SUSPECT_RETURN`.
- Produces:
  - `OPEN, HIGH, LOW, CLOSE = 0, 1, 2, 3`
  - `path_status(paths: np.ndarray[n, L, 4], entry_open, atr, session_index, *, horizon: int, last_index: int, min_return: float, max_return: float) -> np.ndarray[object]`
  - `levels(geometry, entry_open, atr) -> (target, stop)`, with NaN meaning no barrier
  - `simulate(paths, entry_open, atr, geometry) -> Fills`. The `Fills` fields are `outcome`, `ambiguous`, `exit_price`, `exit_offset` (`t_x`), `gross_return`, `mfe`, `mae`, `target_price` and `stop_price`, each an array over rows.
  - `path_statistics(paths, entry_open, *, horizon) -> {"fwd", "mfe", "mae", "mdd"}`

- [ ] **Step 1: Write the failing test** `tests/test_trade_geometry_fills.py`. It covers every spec §8 worked example, the §9.1 numbers, each §7.3 status and monotonicity in `H`. Returns are compared within 1e-6 absolute and prices within 1e-6 relative, because paths are `float32`.

```python
import numpy as np
import pytest

from app.selection_gate.constants import (
    LABEL_ENTRY_BAR_MISSING, LABEL_INVALID_PRICE, LABEL_NOT_YET_RESOLVABLE, LABEL_RESOLVED, LABEL_SUSPECT_RETURN,
)
from app.trade_geometry.config import Geometry, reference_geometry
from app.trade_geometry.constants import OUTCOME_EXPIRED, OUTCOME_STOP, OUTCOME_TARGET, PATH_BAR_MISSING
from app.trade_geometry.fills import path_statistics, path_status, simulate

G = Geometry(5, 1.5, 1.0)  # spec Â§8: o = 100, atr = 0.02, so target 103.00 and stop 98.00
COST = 0.0030
MISSING = (np.nan,) * 4
EXPIRY_BARS = ((100.0, 101.2, 99.1, 100.8), (100.9, 102.6, 100.2, 102.0), (101.8, 102.4, 99.4, 99.9),
               (99.9, 101.0, 98.6, 100.6), (100.7, 101.9, 100.1, 101.4))


def near(value):
    return pytest.approx(value, abs=1e-6)  # paths are stored as float32 (§7.2)


def price(value):
    return pytest.approx(value, rel=1e-6)


def _paths(*bars, length=7):
    out = np.full((1, length, 4), np.nan, dtype=np.float32)
    for t, bar in enumerate(bars):
        out[0, t] = bar
    return out


def _fill(geometry, *bars, o=100.0, atr=0.02):
    fills = simulate(_paths(*bars), np.array([o]), np.array([atr]), geometry)
    return {name: getattr(fills, name)[0] for name in
            ("outcome", "ambiguous", "exit_price", "exit_offset", "gross_return", "mfe", "mae")}


def _status(*bars, horizon, o=100.0, atr=0.02, session_index=10, last_index=100):
    return path_status(_paths(*bars), np.array([o]), np.array([atr]), np.array([session_index]), horizon=horizon,
                       last_index=last_index, min_return=-0.60, max_return=1.50)[0]


def test_target_fills_at_the_target():
    f = _fill(G, (100.0, 101.5, 99.0, 101.0), (101.2, 103.4, 100.8, 103.1))
    assert (f["outcome"], f["exit_offset"], f["ambiguous"]) == (OUTCOME_TARGET, 2, False)
    assert f["exit_price"] == price(103.00) and f["gross_return"] == near(0.0300)
    assert f["gross_return"] - COST == near(0.0270)
    assert (f["mfe"], f["mae"]) == (near(0.0340), near(-0.0100))


def test_stop_fills_at_the_stop():
    f = _fill(G, (100.0, 100.5, 97.6, 98.2))
    assert (f["outcome"], f["exit_offset"]) == (OUTCOME_STOP, 1)
    assert f["gross_return"] == near(-0.0200) and f["gross_return"] - COST == near(-0.0230)


def test_a_gap_through_the_stop_fills_at_the_open():
    f = _fill(G, (100.0, 101.0, 99.2, 99.5), (96.5, 97.0, 96.0, 96.8))
    assert (f["outcome"], f["exit_offset"]) == (OUTCOME_STOP, 2)
    assert f["exit_price"] == price(96.50) and f["gross_return"] == near(-0.0350)
    assert f["gross_return"] - COST == near(-0.0380)
    assert (f["mfe"], f["mae"]) == (near(0.0100), near(-0.0350))


def test_a_gap_above_the_target_fills_at_the_target_not_the_open():
    f = _fill(G, (100.0, 102.0, 99.5, 101.8), (104.2, 105.0, 103.8, 104.5))
    assert (f["outcome"], f["exit_offset"]) == (OUTCOME_TARGET, 2)
    assert f["exit_price"] == price(103.00) and f["gross_return"] == near(0.0300)


def test_both_barriers_in_one_bar_is_an_ambiguous_stop():
    f = _fill(G, (100.0, 103.5, 97.8, 101.0))
    assert (f["outcome"], f["ambiguous"], f["exit_offset"]) == (OUTCOME_STOP, True, 1)
    assert f["gross_return"] == near(-0.0200)


def test_expiry_exits_at_the_horizon_close():
    f = _fill(G, *EXPIRY_BARS)
    assert (f["outcome"], f["exit_offset"]) == (OUTCOME_EXPIRED, 5)
    assert f["exit_price"] == price(101.40) and f["gross_return"] == near(0.0140)
    assert f["gross_return"] - COST == near(0.0110)


def test_the_adjusted_split_path_hits_the_target_and_the_raw_path_would_stop():
    adjusted = ((200.0, 202.0, 198.5, 201.0), (201.2, 203.0, 199.8, 202.4), (202.0, 204.4, 200.2, 203.2),
                (203.0, 206.8, 202.0, 206.4))
    f = _fill(G, *adjusted, o=200.0)
    assert (f["outcome"], f["exit_offset"]) == (OUTCOME_TARGET, 4) and f["gross_return"] == near(0.0300)
    raw = adjusted[:2] + ((101.0, 102.2, 100.1, 101.6), (101.5, 103.4, 101.0, 103.2))
    f = _fill(G, *raw, o=200.0)
    assert (f["outcome"], f["exit_offset"]) == (OUTCOME_STOP, 3) and f["gross_return"] == near(-0.4950)


def test_a_missing_bar_unresolves_every_longer_horizon_even_after_a_barrier_fired():
    bars = ((100.0, 100.5, 97.6, 98.2), (98.0, 99.0, 97.0, 98.5), MISSING, (98.0, 99.0, 97.5, 98.0),
            (98.0, 99.0, 97.5, 98.0))
    assert _status(*bars, horizon=1) == LABEL_RESOLVED
    assert [_status(*bars, horizon=h) for h in (3, 5, 7)] == [PATH_BAR_MISSING] * 3


def test_a_missing_barrier_skips_its_rules_and_the_time_exit_always_expires():
    stop_only = _fill(Geometry(5, None, 1.0), (100.0, 103.5, 99.0, 101.0), *EXPIRY_BARS[1:])
    assert stop_only["outcome"] == OUTCOME_EXPIRED  # 103.5 would have been a target
    target_only = _fill(Geometry(5, 1.5, None), (100.0, 101.0, 97.0, 99.0), *EXPIRY_BARS[1:])
    assert target_only["outcome"] == OUTCOME_EXPIRED  # 97.0 would have been a stop
    closes = (101.0,) + tuple(bar[3] for bar in EXPIRY_BARS[1:])
    for h in (1, 3, 5):
        timed = _fill(Geometry(h), (100.0, 110.0, 90.0, 101.0), *EXPIRY_BARS[1:])
        assert (timed["outcome"], timed["exit_offset"]) == (OUTCOME_EXPIRED, h)
        assert timed["exit_price"] == price(closes[h - 1])


def test_the_reference_uses_fixed_percentages():
    f = _fill(reference_geometry(), (100.0, 101.0, 96.8, 97.5), atr=0.5)
    assert (f["outcome"], f["exit_offset"]) == (OUTCOME_STOP, 1)
    assert f["exit_price"] == price(97.00) and f["gross_return"] == near(-0.0300)


@pytest.mark.parametrize("bars,kwargs,expected", [
    (EXPIRY_BARS, {"session_index": 98}, LABEL_NOT_YET_RESOLVABLE),
    ((MISSING,) + EXPIRY_BARS[1:], {}, LABEL_ENTRY_BAR_MISSING),
    (EXPIRY_BARS[:2] + (MISSING,) + EXPIRY_BARS[3:], {}, PATH_BAR_MISSING),
    (EXPIRY_BARS, {"o": 0.0}, LABEL_INVALID_PRICE),
    (EXPIRY_BARS, {"atr": 0.0}, LABEL_INVALID_PRICE),
    (EXPIRY_BARS[:2] + ((101.8, 99.0, 99.4, 99.9),) + EXPIRY_BARS[3:], {}, LABEL_INVALID_PRICE),  # high below low
    (EXPIRY_BARS[:2] + ((101.8, 260.0, 99.4, 99.9),) + EXPIRY_BARS[3:], {}, LABEL_SUSPECT_RETURN),
    (EXPIRY_BARS, {}, LABEL_RESOLVED),
])
def test_each_path_status_first_failure_wins(bars, kwargs, expected):
    assert _status(*bars, horizon=5, **kwargs) == expected


def test_path_status_is_monotone_in_the_horizon():
    rng = np.random.default_rng(7)
    paths = (100 * (1 + rng.normal(0, 0.05, size=(400, 7, 4)))).astype(np.float32)
    paths[rng.random((400, 7)) < 0.05] = np.nan
    paths[rng.random((400, 7)) < 0.02, 1] = 1.0  # a high below the rest of the bar: invalid arithmetic
    o, atr, index = np.full(400, 100.0), np.full(400, 0.02), rng.integers(0, 100, 400)
    statuses = {h: path_status(paths, o, atr, index, horizon=h, last_index=100, min_return=-0.6, max_return=1.5)
                for h in (1, 3, 5, 7)}
    for short, long in ((1, 3), (3, 5), (5, 7)):
        assert not ((statuses[short] != LABEL_RESOLVED) & (statuses[long] == LABEL_RESOLVED)).any()


def test_path_statistics_match_the_worked_example():
    stats = {h: {k: v[0] for k, v in path_statistics(_paths(*EXPIRY_BARS), np.array([100.0]), horizon=h).items()}
             for h in (3, 5)}
    assert stats[5]["fwd"] == near(0.0140) and stats[5]["mfe"] == near(0.0260)
    assert stats[5]["mae"] == near(-0.0140) and stats[5]["mdd"] == near(0.020588)
    assert stats[3]["fwd"] == near(-0.0010) and stats[3]["mfe"] == near(0.0260)
    assert stats[3]["mae"] == near(-0.0090) and stats[3]["mdd"] == near(0.020588)
```

- [ ] **Step 2: Run; expect FAIL**

Run: `DATABASE_URL="sqlite:///$SCRATCH/tg-3.db" python -m pytest tests/test_trade_geometry_fills.py -q -p no:cacheprovider`
Expected: `ModuleNotFoundError: No module named 'app.trade_geometry.fills'`.

- [ ] **Step 3: Implement** `app/trade_geometry/fills.py` **(depends on open question 1)**: under fixed percentages, `levels` reads `geometry.target_pct`/`stop_pct` for every geometry, not only the reference. The fill rules do not change.

```python
"""TG-001 §7.3, §8, §9: path status, conservative daily-bar fills and path statistics. Pure numpy, float64 maths."""
from __future__ import annotations

from dataclasses import dataclass

import numpy as np

from app.selection_gate.constants import (
    LABEL_ENTRY_BAR_MISSING, LABEL_INVALID_PRICE, LABEL_NOT_YET_RESOLVABLE, LABEL_RESOLVED, LABEL_SUSPECT_RETURN,
)

from .config import Geometry
from .constants import OUTCOME_EXPIRED, OUTCOME_STOP, OUTCOME_TARGET, PATH_BAR_MISSING

OPEN, HIGH, LOW, CLOSE = 0, 1, 2, 3


@dataclass(frozen=True)
class Fills:
    outcome: np.ndarray       # TARGET / STOP / EXPIRED
    ambiguous: np.ndarray     # rule 3: both barriers inside one bar, resolved as a stop
    exit_price: np.ndarray
    exit_offset: np.ndarray   # t_x in 1..H
    gross_return: np.ndarray
    mfe: np.ndarray
    mae: np.ndarray
    target_price: np.ndarray  # NaN where the geometry has no target
    stop_price: np.ndarray    # NaN where the geometry has no stop


def path_status(paths: np.ndarray, entry_open: np.ndarray, atr: np.ndarray, session_index: np.ndarray, *,
                horizon: int, last_index: int, min_return: float, max_return: float) -> np.ndarray:
    """§7.3 per (row, H), first failing status wins. Never reads a barrier or an outcome."""
    bars = paths[:, :horizon, :].astype(np.float64)
    o = np.asarray(entry_open, dtype=np.float64)
    a = np.asarray(atr, dtype=np.float64)
    present = ~np.isnan(bars).any(axis=2)
    op, hi, lo, cl = (bars[..., k] for k in (OPEN, HIGH, LOW, CLOSE))
    with np.errstate(invalid="ignore", divide="ignore"):
        bad = (np.minimum.reduce([op, hi, lo, cl]) <= 0) | (hi < np.maximum.reduce([op, lo, cl])) \
            | (lo > np.minimum.reduce([op, hi, cl]))
        relative = bars[..., HIGH:] / o[:, None, None] - 1.0
        suspect = ((relative < min_return) | (relative > max_return)).any(axis=(1, 2))
    status = np.full(len(o), LABEL_RESOLVED, dtype=object)
    status[suspect] = LABEL_SUSPECT_RETURN
    status[~(o > 0) | ~(a > 0) | bad.any(axis=1)] = LABEL_INVALID_PRICE
    status[~present[:, 1:].all(axis=1)] = PATH_BAR_MISSING
    status[~present[:, 0]] = LABEL_ENTRY_BAR_MISSING
    status[np.asarray(session_index) + horizon > last_index] = LABEL_NOT_YET_RESOLVABLE
    return status


def levels(geometry: Geometry, entry_open: np.ndarray, atr: np.ndarray) -> tuple[np.ndarray, np.ndarray]:
    """§5: `o x (1 + kT x atr)` and `o x (1 - kS x atr)`, or fixed percentages for the reference; NaN = no barrier."""
    o = np.asarray(entry_open, dtype=np.float64)
    a = np.asarray(atr, dtype=np.float64)
    none = np.full(len(o), np.nan)
    if geometry.is_reference:
        return o * (1.0 + geometry.target_pct), o * (1.0 - geometry.stop_pct)
    target = none if geometry.target_atr_multiple is None else o * (1.0 + geometry.target_atr_multiple * a)
    stop = none if geometry.stop_atr_multiple is None else o * (1.0 - geometry.stop_atr_multiple * a)
    return target, stop


def simulate(paths: np.ndarray, entry_open: np.ndarray, atr: np.ndarray, geometry: Geometry) -> Fills:
    """§8 fill rules, first match per bar, bars t = 1..H. Only meaningful on rows RESOLVED at H."""
    horizon = geometry.holding_sessions
    bars = paths[:, :horizon, :].astype(np.float64)
    o = np.asarray(entry_open, dtype=np.float64)
    target, stop = levels(geometry, entry_open, atr)
    n = len(o)
    rows = np.arange(n)
    op, hi, lo, cl = (bars[..., k] for k in (OPEN, HIGH, LOW, CLOSE))
    later = np.arange(horizon) > 0  # entry is the D+1 open, so no gap rule at t = 1
    with np.errstate(invalid="ignore", divide="ignore"):
        gap_stop = (op <= stop[:, None]) & later
        gap_target = (op >= target[:, None]) & later
        touch_stop = lo <= stop[:, None]
        touch_target = hi >= target[:, None]
        fired = gap_stop | gap_target | touch_stop | touch_target
        hit = fired.any(axis=1)
        first = np.where(hit, fired.argmax(axis=1), horizon - 1)
        g_stop = gap_stop[rows, first] & hit
        g_target = gap_target[rows, first] & hit & ~g_stop
        t_stop = touch_stop[rows, first] & hit
        t_target = touch_target[rows, first] & hit
        is_stop = g_stop | (~g_target & t_stop)
        is_target = ~is_stop & (g_target | t_target)
        ambiguous = ~g_stop & ~g_target & t_stop & t_target
        exit_price = cl[rows, first].copy()
        exit_price[is_stop] = stop[is_stop]
        exit_price[g_stop] = op[rows, first][g_stop]
        exit_price[is_target] = target[is_target]
        outcome = np.full(n, OUTCOME_EXPIRED, dtype=object)
        outcome[is_stop] = OUTCOME_STOP
        outcome[is_target] = OUTCOME_TARGET
        # §9.2: the exit bar counts in full for intrabar exits; a gap exit contributes only its open.
        run_high = np.maximum.accumulate(hi, axis=1)
        run_low = np.minimum.accumulate(lo, axis=1)
        gap = g_stop | g_target
        before = np.maximum(first - 1, 0)
        high = np.where(gap, np.maximum(run_high[rows, before], op[rows, first]), run_high[rows, first])
        low = np.where(gap, np.minimum(run_low[rows, before], op[rows, first]), run_low[rows, first])
        return Fills(outcome=outcome, ambiguous=ambiguous, exit_price=exit_price, exit_offset=first + 1,
                     gross_return=exit_price / o - 1.0, mfe=high / o - 1.0, mae=low / o - 1.0,
                     target_price=target, stop_price=stop)


def path_statistics(paths: np.ndarray, entry_open: np.ndarray, *, horizon: int) -> dict[str, np.ndarray]:
    """§9.1 per (row, H): forward return, MFE, MAE and the close-to-close drawdown from max(o, C_1..C_t)."""
    bars = paths[:, :horizon, :].astype(np.float64)
    o = np.asarray(entry_open, dtype=np.float64)
    closes = bars[..., CLOSE]
    peak = np.maximum.accumulate(np.concatenate([o[:, None], closes], axis=1), axis=1)[:, 1:]
    with np.errstate(invalid="ignore", divide="ignore"):
        return {
            "fwd": closes[:, -1] / o - 1.0,
            "mfe": bars[..., HIGH].max(axis=1) / o - 1.0,
            "mae": bars[..., LOW].min(axis=1) / o - 1.0,
            "mdd": (1.0 - closes / peak).max(axis=1),
        }
```

- [ ] **Step 4: Run; expect PASS (20 passed)**

Run: `DATABASE_URL="sqlite:///$SCRATCH/tg-3.db" python -m pytest tests/test_trade_geometry_fills.py -q -p no:cacheprovider`

- [ ] **Step 5: Commit**

```bash
git add app/trade_geometry/fills.py tests/test_trade_geometry_fills.py
git commit -m "TG-001: conservative daily-bar fills, path status and path statistics" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 4: Reduction and TG windows (pure)

**Files:**
- Create: `app/trade_geometry/reduction.py`, `app/trade_geometry/windows.py`
- Test: `tests/test_trade_geometry_reduction.py`, `tests/test_trade_geometry_windows.py`

**Interfaces:**
- Consumes: SPG `DISPOSITION_*`, `validation.month_of`, `parse_month`, `protected_rows`, `Month`.
- Produces:
  - `GeometryCandidate(source_row_id, session_date, session_index: int | None, stock_id, rank, score, resolved: bool, exit_index: int | None)`
  - `reduce_geometry_candidates(candidates, *, dates) -> list[(GeometryCandidate, disposition)]`
  - `TgWindows(holdout_month: Month | None, holdout_indices: tuple[int, ...], protected: tuple[int, int] | None)`
  - `plan_tg_windows(dates, *, today, not_before: str, max_horizon: int) -> TgWindows`
  - `walk_forward_sessions(candidate_indices, *, max_horizon, protected) -> (kept, excluded)`

Reduction orders and compares by `session_date`, which orders exactly as `i(D)` for every `D` in `S`. It also gives a defined order and overlap test for a `D` that is not in `S` (Review Focus 1). For one `(σ, g)` the duplicate key `(σ, g, stock_id, i(D))` is `(stock_id, session_date)`.

- [ ] **Step 1: Write the failing tests**

`tests/test_trade_geometry_reduction.py`:

```python
from datetime import date, timedelta

from app.selection_gate.constants import (
    DISPOSITION_ACCEPTED, DISPOSITION_DUPLICATE, DISPOSITION_OVERLAP_SUPPRESSED, DISPOSITION_UNRESOLVED,
)
from app.trade_geometry.reduction import GeometryCandidate, reduce_geometry_candidates

DATES = tuple(date(2026, 1, 1) + timedelta(days=k) for k in range(200))  # index i is DATES[i]


def _c(index, *, stock=7, rank=1, resolved=True, exit_offset=None, source=None):
    return GeometryCandidate(source_row_id=source or index * 100 + rank, session_date=DATES[index],
                             session_index=index, stock_id=stock, rank=rank, score=1.0 / rank, resolved=resolved,
                             exit_index=None if exit_offset is None else index + exit_offset)


def _dispositions(reduced):
    return [(c.session_index, d) for c, d in reduced]


def test_overlap_uses_the_exit_index_under_a_barrier_geometry():
    """§12 example under (5, 1.5, 1.0): the 100 trade hits its target at t_x = 2, so X = 102."""
    reduced = reduce_geometry_candidates(
        [_c(100, exit_offset=2), _c(101, exit_offset=5), _c(102, exit_offset=5), _c(105, exit_offset=5)], dates=DATES)
    assert _dispositions(reduced) == [(100, DISPOSITION_ACCEPTED), (101, DISPOSITION_OVERLAP_SUPPRESSED),
                                      (102, DISPOSITION_ACCEPTED), (105, DISPOSITION_OVERLAP_SUPPRESSED)]


def test_overlap_under_the_time_exit_is_spgs_rule():
    """§12 example under (5, None, None): X = 105, so 101 and 102 are suppressed and 105 is accepted."""
    reduced = reduce_geometry_candidates([_c(i, exit_offset=5) for i in (100, 101, 102, 105)], dates=DATES)
    assert _dispositions(reduced) == [(100, DISPOSITION_ACCEPTED), (101, DISPOSITION_OVERLAP_SUPPRESSED),
                                      (102, DISPOSITION_OVERLAP_SUPPRESSED), (105, DISPOSITION_ACCEPTED)]


def test_a_suppressed_candidate_is_not_replaced():
    reduced = reduce_geometry_candidates(
        [_c(100, exit_offset=5), _c(101, rank=1, exit_offset=5), _c(101, stock=8, rank=2, exit_offset=5)], dates=DATES)
    assert [(c.session_index, c.stock_id, d) for c, d in reduced] == [
        (100, 7, DISPOSITION_ACCEPTED), (101, 7, DISPOSITION_OVERLAP_SUPPRESSED), (101, 8, DISPOSITION_ACCEPTED)]


def test_a_duplicate_counts_once():
    reduced = reduce_geometry_candidates([_c(100, exit_offset=1), _c(100, exit_offset=1, source=999)], dates=DATES)
    assert [d for _, d in reduced] == [DISPOSITION_ACCEPTED, DISPOSITION_DUPLICATE]


def test_an_unresolved_candidate_does_not_make_the_stock_busy():
    reduced = reduce_geometry_candidates([_c(100, resolved=False), _c(101, exit_offset=5)], dates=DATES)
    assert [d for _, d in reduced] == [DISPOSITION_UNRESOLVED, DISPOSITION_ACCEPTED]


def test_candidates_are_ordered_by_session_then_rank_never_by_outcome():
    """Label independence: the same candidates in any input order give the same dispositions."""
    candidates = [_c(102, rank=2, exit_offset=1), _c(100, stock=9, rank=1, exit_offset=7), _c(100, rank=2,
                  exit_offset=3), _c(101, stock=9, rank=1, exit_offset=1)]
    forward = reduce_geometry_candidates(candidates, dates=DATES)
    backward = reduce_geometry_candidates(list(reversed(candidates)), dates=DATES)
    assert _dispositions(forward) == _dispositions(backward)
    assert [(c.session_index, c.rank) for c, _ in forward] == [(100, 1), (100, 2), (101, 1), (102, 2)]


def test_a_session_outside_s_is_unresolved_and_still_respects_a_busy_stock():
    outside = GeometryCandidate(source_row_id=1, session_date=date(2026, 4, 11), session_index=None, stock_id=7,
                                rank=1, score=1.0, resolved=False, exit_index=None)
    reduced = reduce_geometry_candidates([_c(99, exit_offset=7), outside], dates=DATES)  # busy until DATES[106]
    assert [d for _, d in reduced] == [DISPOSITION_ACCEPTED, DISPOSITION_OVERLAP_SUPPRESSED]
    reduced = reduce_geometry_candidates([outside], dates=DATES)
    assert [d for _, d in reduced] == [DISPOSITION_UNRESOLVED]
```

`tests/test_trade_geometry_windows.py`:

```python
from datetime import date

from app.trade_geometry.windows import plan_tg_windows, walk_forward_sessions
from tests._selection_gate_factories import weekday_sessions

DATES = weekday_sessions(date(2026, 9, 30), 200)  # Aug = indices 157..177, Sep = 178..199


def test_the_exam_month_is_the_latest_complete_month_on_or_after_the_floor():
    windows = plan_tg_windows(DATES, today=date(2026, 10, 12), not_before="2026-08", max_horizon=7)
    assert windows.holdout_month == (2026, 8)  # September's last session has no 7 sessions after it
    assert (windows.holdout_indices[0], windows.holdout_indices[-1]) == (157, 177)
    assert windows.protected == (157, 184)


def test_never_before_the_floor_and_never_the_current_month():
    assert plan_tg_windows(DATES, today=date(2026, 10, 12), not_before="2026-11", max_horizon=7).holdout_month is None
    assert plan_tg_windows(DATES, today=date(2026, 10, 12), not_before="2026-09", max_horizon=7).holdout_month is None
    in_september = plan_tg_windows(DATES[:170], today=date(2026, 8, 20), not_before="2026-01", max_horizon=7)
    assert in_september.holdout_month == (2026, 7)  # August is the current month there


def test_the_month_depends_only_on_the_session_list():
    shorter = plan_tg_windows(DATES[:183], today=date(2026, 10, 12), not_before="2026-01", max_horizon=7)
    assert shorter.holdout_month == (2026, 7)  # 177 + 7 > 182, so August is not yet complete


def test_walk_forward_sessions_drop_every_scheduled_hold_touching_the_span():
    kept, excluded = walk_forward_sessions(range(140, 190), max_horizon=7, protected=(157, 184))
    assert excluded == tuple(range(150, 185))
    assert kept == tuple(range(140, 150)) + tuple(range(185, 190))  # 185 on: [D, D+7] no longer touches it
    assert walk_forward_sessions([5, 3, 3], max_horizon=7, protected=None) == ((3, 5), ())
```

- [ ] **Step 2: Run; expect FAIL**

Run: `DATABASE_URL="sqlite:///$SCRATCH/tg-4.db" python -m pytest tests/test_trade_geometry_reduction.py tests/test_trade_geometry_windows.py -q -p no:cacheprovider`
Expected: `ModuleNotFoundError: No module named 'app.trade_geometry.reduction'` (and `.windows`).

- [ ] **Step 3: Implement**

`app/trade_geometry/reduction.py`:

```python
"""TG-001 §12: duplicate, overlap and unresolved reduction. A stock is busy until its accepted trade exits."""
from __future__ import annotations

from dataclasses import dataclass
from datetime import date
from typing import Iterable, Sequence

from app.selection_gate.constants import (
    DISPOSITION_ACCEPTED, DISPOSITION_DUPLICATE, DISPOSITION_OVERLAP_SUPPRESSED, DISPOSITION_UNRESOLVED,
)


@dataclass(frozen=True)
class GeometryCandidate:
    source_row_id: int
    session_date: date
    session_index: int | None  # i(D) in TG's S; None when D is not a session of S (NOT_IN_DATASET)
    stock_id: int
    rank: int
    score: float
    resolved: bool             # path RESOLVED at H and (D, H) benchmarkable
    exit_index: int | None     # X = i(D) + t_x, set when resolved


def reduce_geometry_candidates(candidates: Iterable[GeometryCandidate], *,
                               dates: Sequence[date]) -> list[tuple[GeometryCandidate, str]]:
    """One selector, geometry and stage. Dates order exactly as i(D) does, and also order a D outside S."""
    ordered = sorted(candidates, key=lambda c: (c.session_date, c.rank, c.stock_id))
    seen: set[tuple[int, date]] = set()
    busy_until: dict[int, date] = {}
    reduced: list[tuple[GeometryCandidate, str]] = []
    for candidate in ordered:
        key = (candidate.stock_id, candidate.session_date)
        if key in seen:
            reduced.append((candidate, DISPOSITION_DUPLICATE))
            continue
        seen.add(key)
        until = busy_until.get(candidate.stock_id)
        if until is not None and candidate.session_date < until:  # i(D) < X_a
            reduced.append((candidate, DISPOSITION_OVERLAP_SUPPRESSED))
            continue
        if not candidate.resolved:
            reduced.append((candidate, DISPOSITION_UNRESOLVED))
            continue
        busy_until[candidate.stock_id] = dates[candidate.exit_index]
        reduced.append((candidate, DISPOSITION_ACCEPTED))
    return reduced
```

`app/trade_geometry/windows.py`:

```python
"""TG-001 §13.1: the TG exam month M_TG, its protected span P_TG, and the walk-forward session set T_sigma."""
from __future__ import annotations

from dataclasses import dataclass
from datetime import date
from typing import Iterable, Sequence

import numpy as np

from app.selection_gate.validation import Month, month_of, parse_month, protected_rows


@dataclass(frozen=True)
class TgWindows:
    holdout_month: Month | None
    holdout_indices: tuple[int, ...]
    protected: tuple[int, int] | None


def plan_tg_windows(dates: Sequence[date], *, today: date, not_before: str, max_horizon: int) -> TgWindows:
    """The latest month before today's IST month, on or after `not_before`, whose every session has L bars after it."""
    by_month: dict[Month, list[int]] = {}
    for index, day in enumerate(dates):
        by_month.setdefault(month_of(day), []).append(index)
    last = len(dates) - 1
    current, floor = month_of(today), parse_month(not_before)
    month = next((m for m in sorted(by_month, reverse=True)
                  if floor <= m < current and by_month[m][-1] + max_horizon <= last), None)
    if month is None:
        return TgWindows(None, (), None)
    indices = tuple(by_month[month])
    return TgWindows(month, indices, (indices[0], indices[-1] + max_horizon))


def walk_forward_sessions(candidate_indices: Iterable[int], *, max_horizon: int,
                          protected: tuple[int, int] | None) -> tuple[tuple[int, ...], tuple[int, ...]]:
    """(T_sigma, excluded): one label-independent set for every geometry, using the scheduled hold [D, D+L]."""
    sessions = np.array(sorted({int(i) for i in candidate_indices}), dtype=np.int64)
    blocked = protected_rows(sessions, max_horizon, protected)
    return tuple(int(i) for i in sessions[~blocked]), tuple(int(i) for i in sessions[blocked])
```

- [ ] **Step 4: Run; expect PASS (11 passed)**

Run: `DATABASE_URL="sqlite:///$SCRATCH/tg-4.db" python -m pytest tests/test_trade_geometry_reduction.py tests/test_trade_geometry_windows.py -q -p no:cacheprovider`

- [ ] **Step 5: Commit**

```bash
git add app/trade_geometry/reduction.py app/trade_geometry/windows.py tests/test_trade_geometry_reduction.py tests/test_trade_geometry_windows.py
git commit -m "TG-001: exit-index overlap reduction and the TG exam month, span and session set" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 5: White's Reality Check (pure)

**Files:**
- Create: `app/trade_geometry/reality_check.py`
- Test: `tests/test_trade_geometry_reality_check.py`

**Interfaces:**
- Produces: `RealityCheck(p_value: float | None, statistic: float | None, geometries: int)`, and `reality_check(session_sums: [G, m], session_counts: [G, m], *, block_length: int, draws: int, seed: int) -> RealityCheck`.

The draw index is built exactly as `block_bootstrap_ci` builds it, from a fresh `default_rng(seed)`. Each chunk of 500 draws becomes a weight matrix, so every geometry's resampled mean is one matrix product. `test_it_matches_direct_block_resampling` proves that form equals resampling the session index directly.

- [ ] **Step 1: Write the failing test** `tests/test_trade_geometry_reality_check.py`

```python
import math

import numpy as np

from app.trade_geometry.reality_check import reality_check

M, G, L = 300, 36, 7


def _grid(edge_at=None, *, demean=False, seed=3):
    rng = np.random.default_rng(seed)
    common = rng.normal(0, 0.01, size=M)  # geometries share sessions, so their excess is correlated
    sums = common + rng.normal(0, 0.005, size=(G, M))
    if demean:
        sums -= sums.mean(axis=1, keepdims=True)
    if edge_at is not None:
        sums[edge_at] += 0.01
    return sums, np.ones((G, M))


def test_it_is_deterministic_for_a_fixed_seed():
    sums, counts = _grid(edge_at=4)
    first = reality_check(sums, counts, block_length=L, draws=2000, seed=42)
    assert first == reality_check(sums, counts, block_length=L, draws=2000, seed=42)
    assert first.geometries == G


def test_a_null_grid_is_not_significant():
    sums, counts = _grid(demean=True)
    assert reality_check(sums, counts, block_length=L, draws=2000, seed=42).p_value > 0.05


def test_a_planted_edge_in_one_geometry_is_significant():
    sums, counts = _grid(edge_at=17)
    result = reality_check(sums, counts, block_length=L, draws=2000, seed=42)
    assert result.p_value < 0.05
    assert result.statistic == max(sums.sum(axis=1) / counts.sum(axis=1))


def test_it_is_undefined_below_two_blocks_or_without_geometries():
    sums, counts = _grid()
    assert reality_check(sums[:, :13], counts[:, :13], block_length=L, draws=100, seed=42).p_value is None
    assert reality_check(np.zeros((0, M)), np.zeros((0, M)), block_length=L, draws=100, seed=42).p_value is None


def test_sessions_without_trades_are_skipped_not_counted_as_zero():
    sums, counts = _grid(edge_at=0)
    sums[:, ::2], counts[:, ::2] = 0.0, 0.0
    result = reality_check(sums, counts, block_length=L, draws=500, seed=42)
    assert result.statistic == max(sums.sum(axis=1) / counts.sum(axis=1)) and result.p_value is not None


def test_it_matches_direct_block_resampling():
    """The weight-matrix form equals resampling the session index directly, draw by draw."""
    sums, counts = _grid(edge_at=2)
    sums, counts = sums[:5, :40], counts[:5, :40]
    m, draws = 40, 50
    means = sums.sum(axis=1) / counts.sum(axis=1)
    starts = np.random.default_rng(9).integers(0, m, size=(draws, math.ceil(m / L)))
    exceed = 0
    for row in starts:
        index = np.concatenate([(start + np.arange(L)) % m for start in row])[:m]
        exceed += (sums[:, index].sum(axis=1) / counts[:, index].sum(axis=1) - means).max() >= means.max()
    assert reality_check(sums, counts, block_length=L, draws=draws, seed=9).p_value == exceed / draws
```

- [ ] **Step 2: Run; expect FAIL**

Run: `DATABASE_URL="sqlite:///$SCRATCH/tg-5.db" python -m pytest tests/test_trade_geometry_reality_check.py -q -p no:cacheprovider`
Expected: `ModuleNotFoundError: No module named 'app.trade_geometry.reality_check'`.

- [ ] **Step 3: Implement** `app/trade_geometry/reality_check.py`

```python
"""TG-001 §15.5: White's Reality Check, a max-statistic circular block bootstrap over the grid's geometries."""
from __future__ import annotations

import math
from dataclasses import dataclass

import numpy as np

_DRAW_CHUNK = 500


@dataclass(frozen=True)
class RealityCheck:
    p_value: float | None
    statistic: float | None  # V = max_g mean_g
    geometries: int          # G


def reality_check(session_sums: np.ndarray, session_counts: np.ndarray, *, block_length: int, draws: int,
                  seed: int) -> RealityCheck:
    """Rows are geometries with n_g >= 1, columns the sessions of T_sigma ascending; undefined if G = 0 or m < 2L."""
    sums = np.asarray(session_sums, dtype=float)
    counts = np.asarray(session_counts, dtype=float)
    geometries, m = sums.shape
    if geometries == 0 or block_length < 1 or m < 2 * block_length:
        return RealityCheck(None, None, geometries)
    means = sums.sum(axis=1) / counts.sum(axis=1)
    statistic = float(means.max())
    blocks = math.ceil(m / block_length)
    # A fresh generator; the draw index is built exactly as block_bootstrap_ci builds its own.
    starts = np.random.default_rng(seed).integers(0, m, size=(draws, blocks))
    offsets = np.arange(block_length)
    exceed = 0
    for first in range(0, draws, _DRAW_CHUNK):
        part = starts[first:first + _DRAW_CHUNK]
        index = ((part[:, :, None] + offsets) % m).reshape(len(part), blocks * block_length)[:, :m]
        flat = (index + m * np.arange(len(part))[:, None]).ravel()
        weights = np.bincount(flat, minlength=len(part) * m).reshape(len(part), m).astype(float)
        numerator, denominator = weights @ sums.T, weights @ counts.T
        resampled = np.divide(numerator, denominator, out=np.full_like(numerator, np.nan), where=denominator > 0)
        centred = resampled - means
        empty = np.isnan(centred).all(axis=1)
        best = np.nanmax(np.where(empty[:, None], 0.0, centred), axis=1)
        exceed += int((np.where(empty, -np.inf, best) >= statistic).sum())
    return RealityCheck(exceed / draws, statistic, geometries)
```

- [ ] **Step 4: Run; expect PASS (6 passed)**

Run: `DATABASE_URL="sqlite:///$SCRATCH/tg-5.db" python -m pytest tests/test_trade_geometry_reality_check.py -q -p no:cacheprovider`

- [ ] **Step 5: Commit**

```bash
git add app/trade_geometry/reality_check.py tests/test_trade_geometry_reality_check.py
git commit -m "TG-001: max-statistic block-bootstrap reality check over the grid" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 6: Fold stability, robust choice and the decision rule (pure)

**Files:**
- Create: `app/trade_geometry/choice.py`
- Test: `tests/test_trade_geometry_choice.py`

**Interfaces:**
- Consumes: SPG `StageStatistics` (type), `STAGE_WALK_FORWARD`, `month_text`, `Month`. Also `RealityCheck` (Task 5) and `Geometry` (Task 1).
- Produces:
  - `FoldStatistics(folds: tuple[dict, ...], folds_tested: int, fold_positive_share: float | None, median_fold_excess: float | None)`, from `fold_statistics(months, accepted: Iterable[(Month, e_j)]) -> FoldStatistics`
  - `stage_reasons(statistics, folds | None, *, min_positive_share) -> tuple[str, ...]`
  - `GeometryScore(geometry, reasons, ci_low, fold_positive_share)` with `.eligible`
  - `Choice(eligible: tuple[str, ...], tolerance_set: tuple[str, ...], chosen: str | None)`, from `choose_geometry(scores, *, tolerance, significant) -> Choice`
  - `family_wise_significant(check, *, alpha) -> bool`, `walk_forward_reasons(scores, *, significant) -> list[dict]`
  - `GeometryOutcome(decision, primary_stage, primary_reason, reasons)`, from `decide_geometry(reasons) -> GeometryOutcome`

- [ ] **Step 1: Write the failing test** `tests/test_trade_geometry_choice.py`

```python
from app.selection_gate.constants import REASON_CONFIDENCE_NOT_MET, REASON_EDGE_BELOW_THRESHOLD, STAGE_HELD_OUT, STAGE_WALK_FORWARD
from app.selection_gate.statistics import StageStatistics
from app.trade_geometry.choice import (
    GeometryScore, choose_geometry, decide_geometry, family_wise_significant, fold_statistics, stage_reasons,
    walk_forward_reasons,
)
from app.trade_geometry.config import grid, reference_geometry
from app.trade_geometry.constants import (
    DECISION_GEOMETRY_PASS, DECISION_NO_GEOMETRY, REASON_FAMILY_WISE_NOT_SIGNIFICANT, REASON_NO_ELIGIBLE_GEOMETRY,
    REASON_UNSTABLE_ACROSS_FOLDS,
)
from app.trade_geometry.reality_check import RealityCheck

GRID = {g.key: g for g in grid()}


def _score(key, ci_low, share=0.8, reasons=()):
    geometry = reference_geometry() if key.startswith("REF") else GRID[key]
    return GeometryScore(geometry, tuple(reasons), ci_low, share)


def _stats(reasons=()):
    return StageStatistics(trades=2400, unresolved_trades=0, min_trades=2000, mean_gross=0.02, mean_benchmark=0.005,
                           cost=0.003, mean_net=0.017, mean_excess=0.012, ci_low=0.004, ci_high=0.02,
                           bootstrap_sessions=900, result="FAIL" if reasons else "PASS", reasons=tuple(reasons))


def test_the_simplest_geometry_within_tolerance_beats_the_best_two_barrier_one():
    """The two-barrier geometry has the highest ci_low (and mean); a time exit within 0.0010 is chosen."""
    scores = [_score("H5:T1.5:S1.0", 0.0060), _score("H5:Tnone:Snone", 0.0052), _score("H3:Tnone:S1.0", 0.0055)]
    choice = choose_geometry(scores, tolerance=0.0010, significant=True)
    assert choice.chosen == "H5:Tnone:Snone"
    assert choice.tolerance_set == ("H3:Tnone:S1.0", "H5:Tnone:Snone", "H5:T1.5:S1.0")  # grid order


def test_outside_the_tolerance_the_simpler_geometry_loses():
    scores = [_score("H5:T1.5:S1.0", 0.0060), _score("H5:Tnone:Snone", 0.0049)]
    assert choose_geometry(scores, tolerance=0.0010, significant=True).chosen == "H5:T1.5:S1.0"


def test_tie_breaks_apply_in_order():
    by_share = [_score("H3:Tnone:Snone", 0.005, share=0.7), _score("H7:Tnone:Snone", 0.005, share=0.6)]
    assert choose_geometry(by_share, tolerance=0.001, significant=True).chosen == "H3:Tnone:Snone"
    by_horizon = [_score("H3:Tnone:Snone", 0.005, share=0.7), _score("H7:Tnone:Snone", 0.005, share=0.7)]
    assert choose_geometry(by_horizon, tolerance=0.001, significant=True).chosen == "H7:Tnone:Snone"
    by_grid = [_score("H5:T3.0:Snone", 0.005), _score("H5:Tnone:S2.0", 0.005), _score("H5:T1.5:Snone", 0.005)]
    assert choose_geometry(by_grid, tolerance=0.001, significant=True).chosen == "H5:Tnone:S2.0"  # grid index 20 < 21 < 24


def test_fold_instability_is_a_geometry_reason():
    months = [(2026, m) for m in range(1, 6)]
    unstable = fold_statistics(months, [((2026, 1), 0.05), ((2026, 2), -0.01), ((2026, 3), -0.01)])
    assert (unstable.folds_tested, unstable.fold_positive_share) == (3, 1 / 3)
    assert [f["n"] for f in unstable.folds] == [1, 1, 1, 0, 0]
    assert stage_reasons(_stats(), unstable, min_positive_share=0.55) == (REASON_UNSTABLE_ACROSS_FOLDS,)
    stable = fold_statistics(months, [((2026, m), 0.01) for m in range(1, 6)])
    assert stage_reasons(_stats(), stable, min_positive_share=0.55) == ()
    assert stage_reasons(_stats(), fold_statistics(months, []), min_positive_share=0.55) == (
        REASON_UNSTABLE_ACROSS_FOLDS,)
    assert stage_reasons(_stats([REASON_EDGE_BELOW_THRESHOLD]), unstable, min_positive_share=0.55) == (
        REASON_EDGE_BELOW_THRESHOLD, REASON_UNSTABLE_ACROSS_FOLDS)


def test_the_reference_is_never_eligible_or_chosen():
    scores = [_score("REF:H3:T0.05:S0.03", 0.02), _score("H3:Tnone:Snone", 0.004)]
    choice = choose_geometry(scores, tolerance=0.001, significant=True)
    assert "REF:H3:T0.05:S0.03" not in choice.eligible and choice.chosen == "H3:Tnone:Snone"
    alone = choose_geometry([_score("REF:H3:T0.05:S0.03", 0.02)], tolerance=0.001, significant=True)
    assert alone == alone.__class__((), (), None)


def test_without_family_wise_significance_nothing_is_chosen_but_the_tolerance_set_is_reported():
    choice = choose_geometry([_score("H5:Tnone:Snone", 0.006)], tolerance=0.001, significant=False)
    assert choice.chosen is None and choice.tolerance_set == ("H5:Tnone:Snone",)


def test_walk_forward_reasons_name_the_first_failure_per_geometry_and_the_family_wise_result():
    scores = [_score("H1:Tnone:Snone", None, reasons=[REASON_EDGE_BELOW_THRESHOLD, REASON_CONFIDENCE_NOT_MET]),
              _score("H3:Tnone:Snone", None, reasons=[REASON_CONFIDENCE_NOT_MET]),
              _score("H5:Tnone:Snone", None, reasons=[REASON_EDGE_BELOW_THRESHOLD]),
              _score("REF:H3:T0.05:S0.03", 0.02)]
    check = RealityCheck(0.2, 0.001, 3)
    reasons = walk_forward_reasons(scores, significant=family_wise_significant(check, alpha=0.05))
    assert reasons == [
        {"stage": STAGE_WALK_FORWARD, "reason": REASON_NO_ELIGIBLE_GEOMETRY,
         "detail": {REASON_CONFIDENCE_NOT_MET: 1, REASON_EDGE_BELOW_THRESHOLD: 2}},
        {"stage": STAGE_WALK_FORWARD, "reason": REASON_FAMILY_WISE_NOT_SIGNIFICANT},
    ]
    assert not family_wise_significant(RealityCheck(None, None, 0), alpha=0.05)
    assert family_wise_significant(RealityCheck(0.05, 0.01, 36), alpha=0.05)


def test_the_decision_passes_only_without_any_reason_and_names_the_first():
    assert decide_geometry([]).decision == DECISION_GEOMETRY_PASS
    outcome = decide_geometry([{"stage": STAGE_HELD_OUT, "reason": REASON_EDGE_BELOW_THRESHOLD}])
    assert (outcome.decision, outcome.primary_stage, outcome.primary_reason) == (
        DECISION_NO_GEOMETRY, STAGE_HELD_OUT, REASON_EDGE_BELOW_THRESHOLD)
```

- [ ] **Step 2: Run; expect FAIL**

Run: `DATABASE_URL="sqlite:///$SCRATCH/tg-6.db" python -m pytest tests/test_trade_geometry_choice.py -q -p no:cacheprovider`
Expected: `ModuleNotFoundError: No module named 'app.trade_geometry.choice'`.

- [ ] **Step 3: Implement** `app/trade_geometry/choice.py` **(depends on open question 2)**. If the user requires positive absolute net expectancy:
  - add `REASON_NEGATIVE_NET_EXPECTANCY = "NEGATIVE_NET_EXPECTANCY"` to `constants.py`
  - in `stage_reasons`, right after copying `statistics.reasons` and before the fold check, add `if statistics.mean_net is None or statistics.mean_net <= 0: reasons.append(REASON_NEGATIVE_NET_EXPECTANCY)`

  The runner already takes held-out reasons from `run.reasons`, so the HO stage inherits it, and one choice test gains that case.

```python
"""TG-001 §13.3, §15.4, §15.6, §15.7: fold stability, eligibility, the robust choice and the decision."""
from __future__ import annotations

from collections import Counter, defaultdict
from dataclasses import dataclass
from typing import Iterable, Sequence

import numpy as np

from app.selection_gate.constants import STAGE_WALK_FORWARD
from app.selection_gate.statistics import StageStatistics
from app.selection_gate.validation import Month, month_text

from .config import Geometry
from .constants import (
    DECISION_GEOMETRY_PASS, DECISION_NO_GEOMETRY, REASON_FAMILY_WISE_NOT_SIGNIFICANT, REASON_NO_ELIGIBLE_GEOMETRY,
    REASON_UNSTABLE_ACROSS_FOLDS,
)
from .reality_check import RealityCheck


@dataclass(frozen=True)
class FoldStatistics:
    folds: tuple[dict, ...]
    folds_tested: int
    fold_positive_share: float | None
    median_fold_excess: float | None


@dataclass(frozen=True)
class GeometryScore:
    geometry: Geometry
    reasons: tuple[str, ...]
    ci_low: float | None
    fold_positive_share: float | None

    @property
    def eligible(self) -> bool:
        return not self.geometry.is_reference and not self.reasons


@dataclass(frozen=True)
class Choice:
    eligible: tuple[str, ...]
    tolerance_set: tuple[str, ...]
    chosen: str | None


@dataclass(frozen=True)
class GeometryOutcome:
    decision: str
    primary_stage: str | None
    primary_reason: str | None
    reasons: tuple[dict, ...]


def fold_statistics(months: Sequence[Month], accepted: Iterable[tuple[Month, float]]) -> FoldStatistics:
    """§15.4 over the calendar months of T_sigma; `accepted` holds (month of D, e_j) per accepted trade."""
    by_month: dict[Month, list[float]] = defaultdict(list)
    for month, excess in accepted:
        by_month[month].append(float(excess))
    folds = tuple({"month": month_text(m), "n": len(by_month[m]),
                   "mean_excess": float(np.mean(by_month[m])) if by_month[m] else None} for m in months)
    tested = [f["mean_excess"] for f in folds if f["n"] >= 1]
    if not tested:
        return FoldStatistics(folds, 0, None, None)
    return FoldStatistics(folds, len(tested), sum(v > 0 for v in tested) / len(tested), float(np.median(tested)))


def stage_reasons(statistics: StageStatistics, folds: FoldStatistics | None, *,
                  min_positive_share: float) -> tuple[str, ...]:
    """§15.8 geometry order: evaluate_stage's four reasons, then fold stability (walk-forward only)."""
    reasons = list(statistics.reasons)
    if folds is not None and (folds.folds_tested == 0 or folds.fold_positive_share < min_positive_share
                              or folds.median_fold_excess <= 0):
        reasons.append(REASON_UNSTABLE_ACROSS_FOLDS)
    return tuple(reasons)


def family_wise_significant(check: RealityCheck, *, alpha: float) -> bool:
    return check.p_value is not None and check.p_value <= alpha


def choose_geometry(scores: Sequence[GeometryScore], *, tolerance: float, significant: bool) -> Choice:
    """§15.6: the simplest eligible geometry whose ci_low is within `tolerance` of the best. No mean in the key."""
    eligible = [s for s in scores if s.eligible]
    if not eligible:
        return Choice((), (), None)
    best = max(s.ci_low for s in eligible)
    tolerance_set = [s for s in eligible if s.ci_low >= best - tolerance]
    ranked = sorted(tolerance_set, key=lambda s: (s.geometry.barriers, -s.fold_positive_share,
                                                  -s.geometry.holding_sessions, s.geometry.grid_index))
    return Choice(
        eligible=tuple(s.geometry.key for s in eligible),
        tolerance_set=tuple(s.geometry.key for s in sorted(tolerance_set, key=lambda s: s.geometry.grid_index)),
        chosen=ranked[0].geometry.key if significant else None,
    )


def walk_forward_reasons(scores: Sequence[GeometryScore], *, significant: bool) -> list[dict]:
    reasons: list[dict] = []
    if not any(s.eligible for s in scores):
        detail = Counter(s.reasons[0] for s in scores if not s.geometry.is_reference and s.reasons)
        reasons.append({"stage": STAGE_WALK_FORWARD, "reason": REASON_NO_ELIGIBLE_GEOMETRY,
                        "detail": dict(sorted(detail.items()))})
    if not significant:
        reasons.append({"stage": STAGE_WALK_FORWARD, "reason": REASON_FAMILY_WISE_NOT_SIGNIFICANT})
    return reasons


def decide_geometry(reasons: Sequence[dict]) -> GeometryOutcome:
    """§15.7: GEOMETRY_PASS only with no reason at any stage; reasons arrive in stage order none, WF, HO."""
    if not reasons:
        return GeometryOutcome(DECISION_GEOMETRY_PASS, None, None, ())
    first = reasons[0]
    return GeometryOutcome(DECISION_NO_GEOMETRY, first["stage"], first["reason"], tuple(reasons))
```

- [ ] **Step 4: Run; expect PASS (8 passed)**

Run: `DATABASE_URL="sqlite:///$SCRATCH/tg-6.db" python -m pytest tests/test_trade_geometry_choice.py -q -p no:cacheprovider`

- [ ] **Step 5: Commit**

```bash
git add app/trade_geometry/choice.py tests/test_trade_geometry_choice.py
git commit -m "TG-001: fold stability, simplest-within-tolerance choice and the TG-001 decision rule" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 7: SPG inputs, adjusted paths and the TG hashes

**Files:**
- Create: `app/trade_geometry/inputs.py`, `app/trade_geometry/paths.py`
- Test: `tests/test_trade_geometry_inputs.py`, `tests/test_trade_geometry_paths.py`

**Interfaces:**
- Consumes: `SelectionDataset` and `build_selection_dataset(session, *, horizons, keep_session_indices=None, skip_failed_stocks=False)`, `features.bars_frame(rows)`, `ratio_actions_for(session, stock_id)`, `cumulative_ratio(actions, *, after, through)`, `final_bars_only()`. Also `fills.path_status` and `config.path_length`.
- Produces:
  - `BoundDecision(id, model_version, horizon_sessions, decided_at, primary_reason, config_snapshot, config_sha256, dataset_sha256, wf_trades, wf_mean_excess: float | None)`
  - `SelectionRow(source_row_id, session_date, stock_id, rank, score: float, label_status=None, disposition=None, excess_return=None, artefact_sha256=None, scored_at=None)`
  - `bound_decision(read_session, *, selector_horizon) -> BoundDecision | None`
  - `walk_forward_candidates(read_session, *, decision_id, top_k) -> list[SelectionRow]`
  - `shadow_candidates(read_session, *, selector_horizon, first, last, top_k) -> list[SelectionRow]`
  - `decision_artefacts(read_session, *, selector_horizon) -> list[(sha, decided_at)]`
  - `spg_registrations(read_session, *, selector_horizon) -> list[(Month, registered_at)]`
  - `PathSet(session_index, stock_id, entry_open, atr, paths, status: dict[H, np.ndarray])` with `__len__` and `locate(pairs) -> list[int | None]`
  - `build_paths(read_session, dataset, *, session_indices, window: (start, end) | None = None) -> PathSet`
  - `dataset_sha256(paths, dates) -> str`, `selection_sha256(rows) -> str`

`walk_forward_candidates` also selects `excess_return`. It is the same `WALK_FORWARD` predicate, used only by the parity block. This resolves spec defect 1: §10.2 needs SPG's `excess_return`, but the §6.2 column list omits it.

- [ ] **Step 1: Write the failing tests**

`tests/test_trade_geometry_inputs.py`:

```python
from datetime import date
from decimal import Decimal

import pytest
from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker

from app.db import Base
from app.models import HoldoutWindowRegistry, SelectionGateTrade, SelectionShadowScore
from app.selection_gate.constants import SEL001_MODEL_VERSION, STAGE_HELD_OUT, STAGE_WALK_FORWARD
from app.trade_geometry.inputs import (
    bound_decision, decision_artefacts, shadow_candidates, spg_registrations, walk_forward_candidates,
)
from tests._selection_gate_factories import AT, seed_decision, seed_stock_with_bars, weekday_sessions

DAYS = weekday_sessions(date(2026, 8, 31), 5)


@pytest.fixture
def session():
    engine = create_engine("sqlite:///:memory:")
    Base.metadata.create_all(engine)
    with sessionmaker(bind=engine)() as db:
        yield db


def _trade(decision, stage, day, stock, rank):
    return SelectionGateTrade(decision_id=decision.id, stage=stage, session_date=day, stock_id=stock.id,
                              horizon_sessions=decision.horizon_sessions, rank=rank, score=Decimal("0.5"),
                              label_status="RESOLVED", cost=Decimal("0.003"), excess_return=Decimal("0.01"),
                              disposition="ACCEPTED")


def test_the_bound_decision_is_the_latest_for_the_selector_whatever_it_decided(session):
    seed_decision(session, model_version=SEL001_MODEL_VERSION, horizon=3)
    latest = seed_decision(session, model_version=SEL001_MODEL_VERSION, horizon=3, decision="NO_EDGE")
    seed_decision(session, model_version=SEL001_MODEL_VERSION, horizon=5)
    seed_decision(session, model_version="BASELINE-001", horizon=3)
    bound = bound_decision(session, selector_horizon=3)
    assert (bound.id, bound.wf_mean_excess, bound.config_snapshot["selection_top_k"]) == (latest.id, 0.01, 10)


def test_walk_forward_candidates_never_include_held_out_rows_or_ranks_beyond_k(session):
    stock = seed_stock_with_bars(session, symbol="AAA", sessions=DAYS)
    other = seed_stock_with_bars(session, symbol="BBB", sessions=DAYS)
    decision = seed_decision(session, model_version=SEL001_MODEL_VERSION, horizon=3)
    session.add_all([_trade(decision, STAGE_WALK_FORWARD, DAYS[0], stock, 1),
                     _trade(decision, STAGE_WALK_FORWARD, DAYS[1], other, 11),
                     _trade(decision, STAGE_HELD_OUT, DAYS[2], stock, 1)])
    session.flush()
    rows = walk_forward_candidates(session, decision_id=decision.id, top_k=10)
    assert [(r.session_date, r.stock_id, r.rank, r.excess_return) for r in rows] == [(DAYS[0], stock.id, 1, 0.01)]


def test_shadow_candidates_are_the_months_rows_within_k(session):
    stock = seed_stock_with_bars(session, symbol="AAA", sessions=DAYS)
    for day, rank in ((DAYS[0], 1), (DAYS[1], 11), (date(2026, 9, 1), 1)):
        session.add(SelectionShadowScore(model_version=SEL001_MODEL_VERSION, artefact_sha256="a" * 64,
                                         horizon_sessions=3, session_date=day, stock_id=stock.id,
                                         score=Decimal("0.4"), rank=rank, selected=True, scored_at=AT))
    session.flush()
    rows = shadow_candidates(session, selector_horizon=3, first=DAYS[0], last=DAYS[-1], top_k=10)
    assert [(r.session_date, r.artefact_sha256) for r in rows] == [(DAYS[0], "a" * 64)]


def test_provenance_and_registration_reads(session):
    seed_decision(session, model_version=SEL001_MODEL_VERSION, horizon=3, artefact_sha256="a" * 64)
    seed_decision(session, model_version=SEL001_MODEL_VERSION, horizon=3, artefact_sha256=None)
    assert [sha for sha, _ in decision_artefacts(session, selector_horizon=3)] == ["a" * 64]
    for label in ("SPG-001:SEL-001:h3:2026-09", "SPG-001:BASELINE-001:h3:2026-10", "SPG-001:SEL-001:h5:2026-09"):
        session.add(HoldoutWindowRegistry(label=label, window_start=AT, window_end=AT, registered_at=AT,
                                          registry_version="SPG-HOLDOUT-001"))
    session.add(HoldoutWindowRegistry(label="CHT-1:SEL-001:h3:2026-12", window_start=AT, window_end=AT,
                                      registered_at=AT, registry_version="CHT-001"))
    session.flush()
    assert sorted(m for m, _ in spg_registrations(session, selector_horizon=3)) == [(2026, 9), (2026, 10)]
```

`tests/test_trade_geometry_paths.py`:

```python
from datetime import date, timedelta
from decimal import Decimal

import numpy as np
import pytest
from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker

from app.db import Base
from app.models import CorporateAction, MarketPrice
from app.selection_gate.constants import LABEL_RESOLVED
from app.selection_gate.dataset import build_selection_dataset
from app.settings import settings
from app.trade_geometry.config import Geometry
from app.trade_geometry.constants import OUTCOME_TARGET, PATH_BAR_MISSING
from app.trade_geometry.fills import simulate
from app.trade_geometry.inputs import SelectionRow
from app.trade_geometry.paths import build_paths, dataset_sha256, selection_sha256
from app.walk_forward_dataset import session_cutoff
from tests._selection_gate_factories import AT, seed_stock_with_bars, weekday_sessions

SESSIONS = weekday_sessions(date(2026, 9, 30), 40)
D = 25
SPLIT_PATH = ((200.0, 202.0, 198.5, 201.0), (201.2, 203.0, 199.8, 202.4), (101.0, 102.2, 100.1, 101.6),
              (101.5, 103.4, 101.0, 103.2))  # spec §8: raw bars t1..t4, a 2-for-1 split effective on D+3


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


def _set(session, stock, index, ohlc):
    bar = _bar(session, stock, index)
    bar.open, bar.high, bar.low, bar.close = (Decimal(str(v)) for v in ohlc)


def _split_stock(session, effective_index):
    """Raw bars 200-ish before the split session and 100-ish from it on, with the spec's path after D."""
    stock = seed_stock_with_bars(session, symbol="SPL", sessions=SESSIONS, close=200.0, drift=0.0)
    for index in range(effective_index, len(SESSIONS)):
        _set(session, stock, index, (100.0, 101.0, 99.0, 100.0))
    session.add(CorporateAction(stock_id=stock.id, action_type="SPLIT", effective_date=SESSIONS[effective_index],
                                ratio=Decimal("2"), source="test", action_version="CPA-001", recorded_at=AT))
    session.flush()
    return stock


def _paths(session, indices=(D,), **kwargs):
    dataset = build_selection_dataset(session, horizons=(3,))
    return dataset, build_paths(session, dataset, session_indices=indices, **kwargs)


def test_paths_are_the_next_seven_official_bars(session):
    stock = seed_stock_with_bars(session, symbol="AAA", sessions=SESSIONS, drift=0.003)
    _, paths = _paths(session)
    for t in range(1, 8):
        bar = _bar(session, stock, D + t)
        assert paths.paths[0, t - 1] == pytest.approx([float(bar.open), float(bar.high), float(bar.low),
                                                       float(bar.close)], rel=1e-6)
    assert paths.entry_open[0] == float(_bar(session, stock, D + 1).open)
    assert paths.paths.dtype == np.float32 and paths.entry_open.dtype == np.float64
    assert all(paths.status[h][0] == LABEL_RESOLVED for h in (1, 3, 5, 7))


def test_a_split_inside_the_path_is_adjusted_onto_the_entry_basis(session):
    stock = _split_stock(session, effective_index=D + 3)
    for t, ohlc in enumerate(SPLIT_PATH, start=1):
        _set(session, stock, D + t, ohlc)
    session.flush()
    _, paths = _paths(session)
    assert paths.paths[0, 2] == pytest.approx([202.0, 204.4, 200.2, 203.2], rel=1e-6)
    assert paths.paths[0, 3] == pytest.approx([203.0, 206.8, 202.0, 206.4], rel=1e-6)
    fills = simulate(paths.paths, paths.entry_open, np.array([0.02]), Geometry(5, 1.5, 1.0))
    assert (fills.outcome[0], fills.exit_offset[0]) == (OUTCOME_TARGET, 4)
    assert fills.gross_return[0] == pytest.approx(0.0300, abs=1e-6)


def test_an_action_on_the_entry_session_is_already_in_o_and_bar_one(session):
    stock = _split_stock(session, effective_index=D + 1)
    _, paths = _paths(session)
    assert paths.entry_open[0] == pytest.approx(100.0)
    assert paths.paths[0, :, 3] == pytest.approx([100.0] * 7, rel=1e-6)  # no factor on any bar


def test_an_action_dated_on_a_non_session_day_applies_from_the_next_session(session):
    stock = seed_stock_with_bars(session, symbol="SPL", sessions=SESSIONS, close=200.0, drift=0.0)
    monday = next(t for t in range(2, 8) if SESSIONS[D + t].weekday() == 0)
    saturday = SESSIONS[D + monday] - timedelta(days=2)  # not a session: the action dates the weekend before D+t
    for index in range(D + monday, len(SESSIONS)):
        _set(session, stock, index, (100.0, 101.0, 99.0, 100.0))
    session.add(CorporateAction(stock_id=stock.id, action_type="SPLIT", effective_date=saturday, ratio=Decimal("2"),
                                source="test", action_version="CPA-001", recorded_at=AT))
    session.flush()
    _, paths = _paths(session)
    assert paths.paths[0, :, 3] == pytest.approx([200.0] * 7, rel=1e-6)  # every bar on the entry basis


def test_a_missing_intermediate_bar_unresolves_three_and_longer(session):
    stock = seed_stock_with_bars(session, symbol="AAA", sessions=SESSIONS)
    seed_stock_with_bars(session, symbol="KEEP", sessions=SESSIONS)  # keeps D+3 a session of S
    session.delete(_bar(session, stock, D + 3))
    session.flush()
    _, paths = _paths(session)
    assert np.isnan(paths.paths[0, 2]).all()
    assert [paths.status[h][0] for h in (1, 3, 5, 7)] == [LABEL_RESOLVED] + [PATH_BAR_MISSING] * 3


def test_provisional_bars_never_enter_a_path(session):
    stock = seed_stock_with_bars(session, symbol="AAA", sessions=SESSIONS)
    seed_stock_with_bars(session, symbol="KEEP", sessions=SESSIONS)
    _bar(session, stock, D + 2).source = "upstox-v3-quote-ohlc"
    session.flush()
    _, paths = _paths(session)
    assert np.isnan(paths.paths[0, 1]).all() and paths.status[3][0] == PATH_BAR_MISSING


def test_rows_are_located_by_session_and_stock(session):
    a = seed_stock_with_bars(session, symbol="AAA", sessions=SESSIONS)
    b = seed_stock_with_bars(session, symbol="BBB", sessions=SESSIONS)
    _, paths = _paths(session, indices=(D, D + 1))
    assert paths.locate([(D + 1, a.id), (D, b.id), (D + 2, a.id), (D, 999)]) == [2, 1, None, None]


def test_pass_two_reads_only_its_window(session):
    seed_stock_with_bars(session, symbol="AAA", sessions=SESSIONS, drift=0.002)
    dataset, full = _paths(session)
    windowed = build_paths(session, dataset, session_indices=(D,),
                           window=(dataset.anchors[D], dataset.anchors[D + 7]))
    np.testing.assert_array_equal(full.paths, windowed.paths)
    short = build_paths(session, dataset, session_indices=(D,), window=(dataset.anchors[D], dataset.anchors[D + 4]))
    assert not np.isnan(short.paths[0, :4]).any() and np.isnan(short.paths[0, 4:]).all()


def test_the_dataset_hash_is_stable_and_moves_with_a_path_bar(session):
    stock = seed_stock_with_bars(session, symbol="AAA", sessions=SESSIONS)
    dataset, paths = _paths(session, indices=range(21, 30))
    first = dataset_sha256(paths, dataset.session_dates)
    assert first == dataset_sha256(_paths(session, indices=range(21, 30))[1], dataset.session_dates)
    _bar(session, stock, D + 5).high = Decimal("150")
    session.flush()
    dataset, paths = _paths(session, indices=range(21, 30))
    assert dataset_sha256(paths, dataset.session_dates) != first


def test_the_selection_hash_ignores_input_order_and_moves_with_a_score():
    day = date(2026, 3, 2)
    rows = [SelectionRow(1, day, 7, 2, 0.5), SelectionRow(2, day, 8, 1, 0.9),
            SelectionRow(3, day + timedelta(days=1), 7, 1, 0.7)]
    assert selection_sha256(rows) == selection_sha256(list(reversed(rows)))
    assert selection_sha256(rows) != selection_sha256(rows[:2] + [SelectionRow(3, day + timedelta(days=1), 7, 1, 0.71)])
```

- [ ] **Step 2: Run; expect FAIL**

Run: `DATABASE_URL="sqlite:///$SCRATCH/tg-7.db" python -m pytest tests/test_trade_geometry_inputs.py tests/test_trade_geometry_paths.py -q -p no:cacheprovider`
Expected: `ModuleNotFoundError: No module named 'app.trade_geometry.inputs'` (and `.paths`).

- [ ] **Step 3: Implement**

`app/trade_geometry/inputs.py`:

```python
"""TG-001 §6: every read TG makes of SPG's tables. Explicit column lists only: never a HELD_OUT row, never ho_/holdout_."""
from __future__ import annotations

from dataclasses import dataclass
from datetime import date, datetime

from sqlalchemy import select
from sqlalchemy.orm import Session

from app.models import HoldoutWindowRegistry, SelectionGateDecision, SelectionGateTrade, SelectionShadowScore
from app.selection_gate.constants import HOLDOUT_REGISTRY_VERSION, SEL001_MODEL_VERSION, STAGE_WALK_FORWARD
from app.selection_gate.validation import Month, parse_month


@dataclass(frozen=True)
class BoundDecision:
    id: int
    model_version: str
    horizon_sessions: int
    decided_at: datetime
    primary_reason: str | None
    config_snapshot: dict
    config_sha256: str
    dataset_sha256: str | None
    wf_trades: int | None
    wf_mean_excess: float | None


@dataclass(frozen=True)
class SelectionRow:
    source_row_id: int
    session_date: date
    stock_id: int
    rank: int
    score: float
    label_status: str | None = None     # walk-forward rows: parity only
    disposition: str | None = None      # walk-forward rows: parity only, never used to reduce
    excess_return: float | None = None  # walk-forward rows: parity only
    artefact_sha256: str | None = None  # shadow rows
    scored_at: datetime | None = None   # shadow rows


def bound_decision(read_session: Session, *, selector_horizon: int) -> BoundDecision | None:
    """§6.2: the latest SEL-001 decision at h_sel, whatever its decision."""
    d = SelectionGateDecision
    row = read_session.execute(
        select(d.id, d.model_version, d.horizon_sessions, d.decided_at, d.primary_reason, d.config_snapshot,
               d.config_sha256, d.dataset_sha256, d.wf_trades, d.wf_mean_excess)
        .where(d.model_version == SEL001_MODEL_VERSION, d.horizon_sessions == selector_horizon)
        .order_by(d.id.desc()).limit(1)
    ).first()
    if row is None:
        return None
    return BoundDecision(row.id, row.model_version, row.horizon_sessions, row.decided_at, row.primary_reason,
                         row.config_snapshot, row.config_sha256, row.dataset_sha256, row.wf_trades,
                         None if row.wf_mean_excess is None else float(row.wf_mean_excess))


def walk_forward_candidates(read_session: Session, *, decision_id: int, top_k: int) -> list[SelectionRow]:
    t = SelectionGateTrade
    rows = read_session.execute(
        select(t.id, t.session_date, t.stock_id, t.rank, t.score, t.label_status, t.disposition, t.excess_return)
        .where(t.decision_id == decision_id, t.stage == STAGE_WALK_FORWARD, t.rank <= top_k)
        .order_by(t.id)
    ).all()
    return [SelectionRow(r.id, r.session_date, r.stock_id, r.rank, float(r.score), r.label_status, r.disposition,
                         None if r.excess_return is None else float(r.excess_return)) for r in rows]


def shadow_candidates(read_session: Session, *, selector_horizon: int, first: date, last: date,
                      top_k: int) -> list[SelectionRow]:
    """§6.3: the live SEL-001 shadow scores of the exam month, rank <= K."""
    s = SelectionShadowScore
    rows = read_session.execute(
        select(s.id, s.session_date, s.stock_id, s.rank, s.score, s.artefact_sha256, s.scored_at)
        .where(s.model_version == SEL001_MODEL_VERSION, s.horizon_sessions == selector_horizon,
               s.session_date >= first, s.session_date <= last, s.rank <= top_k)
        .order_by(s.id)
    ).all()
    return [SelectionRow(r.id, r.session_date, r.stock_id, r.rank, float(r.score), artefact_sha256=r.artefact_sha256,
                         scored_at=r.scored_at) for r in rows]


def decision_artefacts(read_session: Session, *, selector_horizon: int) -> list[tuple[str, datetime]]:
    """§13.5 check 3 provenance: (artefact_sha256, decided_at) of every SEL-001 decision at h_sel."""
    d = SelectionGateDecision
    return [(r.artefact_sha256, r.decided_at) for r in read_session.execute(
        select(d.artefact_sha256, d.decided_at)
        .where(d.model_version == SEL001_MODEL_VERSION, d.horizon_sessions == selector_horizon,
               d.artefact_sha256.is_not(None))
    ).all()]


def spg_registrations(read_session: Session, *, selector_horizon: int) -> list[tuple[Month, datetime]]:
    """§13.5 check 1: SPG's registered exam months at h_sel (any model, as holdout.previously_observed reads them)."""
    r = HoldoutWindowRegistry
    out = []
    for label, registered_at in read_session.execute(
            select(r.label, r.registered_at).where(r.registry_version == HOLDOUT_REGISTRY_VERSION)).all():
        parts = label.split(":")
        if len(parts) == 4 and parts[2] == f"h{selector_horizon}":
            out.append((parse_month(parts[3]), registered_at))
    return out
```

`app/trade_geometry/paths.py`:

```python
"""TG-001 §7.2/§7.5: adjusted official-bar paths for SEL-DS-001 rows, path statuses, and the TG hashes."""
from __future__ import annotations

import hashlib
from dataclasses import dataclass
from datetime import date, datetime
from typing import Iterable, Sequence

import numpy as np
import pandas as pd
from sqlalchemy import select
from sqlalchemy.orm import Session

from app.corporate_action_adjustment import cumulative_ratio, ratio_actions_for
from app.market_data.bar_finality import final_bars_only
from app.models import MarketPrice
from app.selection_gate.dataset import SelectionDataset
from app.selection_gate.features import bars_frame
from app.settings import settings

from .config import path_length
from .fills import path_status

_PRICES = ("open", "high", "low", "close")
_HASH_CHUNK = 200_000
_KEY_STRIDE = 1 << 32  # stock ids stay below 2**32, so (session_index, stock_id) packs into one int64


@dataclass(frozen=True)
class PathSet:
    session_index: np.ndarray  # int64, ascending with stock_id (SEL-DS-001 row order)
    stock_id: np.ndarray
    entry_open: np.ndarray     # float64, raw D+1 open
    atr: np.ndarray            # float32 atr_percent at D
    paths: np.ndarray          # float32 [rows, L, 4]; NaN where a bar is absent
    status: dict[int, np.ndarray]

    def __len__(self) -> int:
        return len(self.session_index)

    def locate(self, pairs: Sequence[tuple[int, int]]) -> list[int | None]:
        """Row of each (session_index, stock_id), or None. Rows ascend by that pair, so one searchsorted serves all."""
        keys = self.session_index * _KEY_STRIDE + self.stock_id
        if not pairs or not len(keys):
            return [None] * len(pairs)
        wanted = np.array([i * _KEY_STRIDE + s for i, s in pairs], dtype=np.int64)
        found = np.minimum(np.searchsorted(keys, wanted), len(keys) - 1)
        return [int(k) if keys[k] == w else None for k, w in zip(found, wanted)]


def _positions(bar_index: np.ndarray, targets: np.ndarray) -> np.ndarray:
    if not len(bar_index):
        return np.full(len(targets), -1, dtype=np.int64)
    found = np.minimum(np.searchsorted(bar_index, targets), len(bar_index) - 1)
    return np.where(bar_index[found] == targets, found, -1).astype(np.int64)


def _stock_bars(read_session: Session, stock_id: int, start: datetime | None, end: datetime | None) -> pd.DataFrame:
    query = (select(MarketPrice.timestamp, MarketPrice.open, MarketPrice.high, MarketPrice.low, MarketPrice.close,
                    MarketPrice.volume)
             .where(MarketPrice.stock_id == stock_id, final_bars_only()))
    if start is not None:
        query = query.where(MarketPrice.timestamp >= start, MarketPrice.timestamp <= end)
    return bars_frame(read_session.execute(query.order_by(MarketPrice.timestamp.asc())).all())


def _stock_paths(bars: pd.DataFrame, actions, session_index: np.ndarray, index_of: dict, dates: Sequence[date],
                 length: int) -> np.ndarray:
    mapped = bars["timestamp"].map(index_of)
    keep = mapped.notna().to_numpy()  # only bars at anchors of S, as SPG's _stock_part
    bar_index = mapped[keep].astype(np.int64).to_numpy()
    prices = bars.loc[keep, list(_PRICES)].to_numpy(dtype=np.float64)
    block = np.full((len(session_index), length, 4), np.nan)
    for t in range(1, length + 1):
        found = _positions(bar_index, session_index + t)
        hit = found >= 0
        block[hit, t - 1, :] = prices[found[hit]]
    if actions:
        last_index = len(dates) - 1
        effective = np.searchsorted(np.array(dates, dtype="datetime64[D]"),
                                    np.array([a.effective_date for a in actions], dtype="datetime64[D]"), side="left")
        # Only rows whose (S[i+1], S[i+L]] holds an effective date need a factor other than 1.
        touched = ((effective[None, :] > session_index[:, None] + 1)
                   & (effective[None, :] <= session_index[:, None] + length)).any(axis=1)
        for k in np.flatnonzero(touched):
            i = int(session_index[k])
            for t in range(2, length + 1):
                if i + t <= last_index:
                    block[k, t - 1, :] *= float(cumulative_ratio(actions, after=dates[i + 1], through=dates[i + t]))
    return block


def build_paths(read_session: Session, dataset: SelectionDataset, *, session_indices: Iterable[int],
                window: tuple[datetime, datetime] | None = None) -> PathSet:
    """Pass 1 (`window=None`) reads each stock's official bars; pass 2 reads only `window`'s timestamps."""
    rows = dataset.rows
    selected = rows.loc[rows.session_index.isin(sorted({int(i) for i in session_indices})),
                        ["session_index", "stock_id", "entry_open", "atr_percent"]]
    length = path_length()
    session_index = selected.session_index.to_numpy(dtype=np.int64)
    stock_id = selected.stock_id.to_numpy(dtype=np.int64)
    paths = np.full((len(selected), length, 4), np.nan, dtype=np.float32)
    index_of = {pd.Timestamp(anchor): i for i, anchor in enumerate(dataset.anchors)}
    order = np.argsort(stock_id, kind="stable")
    stocks, starts = np.unique(stock_id[order], return_index=True)
    bounds = list(starts) + [len(order)]
    start, end = window if window is not None else (None, None)
    for position, stock in enumerate(stocks):
        mine = order[bounds[position]:bounds[position + 1]]
        bars = _stock_bars(read_session, int(stock), start, end)
        actions = ratio_actions_for(read_session, int(stock))
        paths[mine] = _stock_paths(bars, actions, session_index[mine], index_of, dataset.session_dates,
                                   length).astype(np.float32)
    entry_open = selected.entry_open.to_numpy(dtype=np.float64)
    atr = selected.atr_percent.to_numpy(dtype=np.float32)
    last_index = len(dataset.session_dates) - 1
    status = {
        int(h): path_status(paths, entry_open, atr, session_index, horizon=int(h), last_index=last_index,
                            min_return=settings.selection_min_plausible_return,
                            max_return=settings.selection_max_plausible_return)
        for h in sorted(set(settings.tg_holding_horizons))
    }
    return PathSet(session_index, stock_id, entry_open, atr, paths, status)


def dataset_sha256(paths: PathSet, dates: Sequence[date]) -> str:
    """§7.5: `session_date,stock_id,o,atr,O_1,H_1,L_1,C_1,...,O_L..C_L,path_status_<H>...` sorted lines."""
    digest = hashlib.sha256()
    length = paths.paths.shape[1]
    horizons = sorted(paths.status)
    for first in range(0, len(paths), _HASH_CHUNK):
        part = slice(first, first + _HASH_CHUNK)
        columns = {"session_date": [dates[i].isoformat() for i in paths.session_index[part]],
                   "stock_id": paths.stock_id[part], "o": paths.entry_open[part], "atr": paths.atr[part]}
        for t in range(length):
            for k, name in enumerate("OHLC"):
                columns[f"{name}_{t + 1}"] = paths.paths[part, t, k]
        for h in horizons:
            columns[f"path_status_{h}"] = paths.status[h][part]
        chunk = pd.DataFrame(columns).to_csv(header=False, index=False, float_format="%.8f", na_rep="",
                                              lineterminator="\n")
        digest.update(chunk.encode("utf-8"))
    return digest.hexdigest()


def selection_sha256(rows: Iterable) -> str:
    """§7.5: `session_date,stock_id,rank,score` lines (score %.10f), sorted by (session_date, rank, stock_id)."""
    lines = sorted((r.session_date, r.rank, r.stock_id, r.score) for r in rows)
    digest = hashlib.sha256()
    for day, rank, stock, score in lines:
        digest.update(f"{day.isoformat()},{stock},{rank},{score:.10f}\n".encode("utf-8"))
    return digest.hexdigest()
```

- [ ] **Step 4: Run; expect PASS (14 passed)**

Run: `DATABASE_URL="sqlite:///$SCRATCH/tg-7.db" python -m pytest tests/test_trade_geometry_inputs.py tests/test_trade_geometry_paths.py tests/test_selection_gate_dataset.py -q -p no:cacheprovider`
Expected: all pass, including SPG's dataset tests.

- [ ] **Step 5: Commit**

```bash
git add app/trade_geometry/inputs.py app/trade_geometry/paths.py tests/test_trade_geometry_inputs.py tests/test_trade_geometry_paths.py
git commit -m "TG-001: explicit-column SPG reads, adjusted official-bar paths and TG hashes" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 8: TG exam registry, SPG availability guard, shadow coverage and the freeze

**Files:**
- Create: `app/trade_geometry/holdout.py`
- Test: `tests/test_trade_geometry_holdout.py`

**Interfaces:**
- Consumes: `TradeGeometryHoldoutWindow`, `TradeGeometryHoldoutUsage` (Task 2), `SelectionRow` (Task 7), `Geometry` (Task 1), and SPG `naive_utc`.
- Produces:
  - `tg_holdout_label(selector_horizon, month) -> "TG-001:SEL-001:hsel<h>:<YYYY-MM>"`
  - `register_window(session, *, selector_horizon, month, window_start, window_end, at) -> TradeGeometryHoldoutWindow`, idempotent by label
  - `spg_exam_available(registrations, *, month, now) -> bool`, `already_consumed(session, *, label) -> bool`
  - `Coverage(covered: tuple[date, ...], missing_share: float, artefacts: dict[str, int])`, from `shadow_coverage(rows, *, sessions, artefacts) -> Coverage`
  - `freeze(session, *, window, selector_horizon, month, bound_spg_decision_id, geometry, grid_sha256, config_sha256, dataset_sha256, wf_selection_sha256, at) -> TradeGeometryHoldoutUsage | None`. It commits, and returns None when the label was already spent.

- [ ] **Step 1: Write the failing test** `tests/test_trade_geometry_holdout.py`

```python
from datetime import date, datetime, timedelta, timezone

import pytest
from sqlalchemy import create_engine, func, select
from sqlalchemy.orm import sessionmaker

from app.db import Base
from app.models import TradeGeometryHoldoutUsage, TradeGeometryHoldoutWindow
from app.trade_geometry.config import grid
from app.trade_geometry.holdout import (
    already_consumed, freeze, register_window, shadow_coverage, spg_exam_available, tg_holdout_label,
)
from app.trade_geometry.inputs import SelectionRow
from tests._selection_gate_factories import seed_decision

AT = datetime(2026, 10, 11, 20, 30, tzinfo=timezone.utc)
DAYS = [date(2026, 8, d) for d in (3, 4, 5, 6, 7, 10, 11, 12, 13, 14)]  # ten sessions
GOOD, LATE = "a" * 64, "c" * 64
ARTEFACTS = [(GOOD, datetime(2026, 7, 10, 20, 30, tzinfo=timezone.utc)),
             (LATE, datetime(2026, 8, 12, 20, 30, tzinfo=timezone.utc))]


@pytest.fixture
def session():
    engine = create_engine("sqlite:///:memory:")
    Base.metadata.create_all(engine)
    with sessionmaker(bind=engine)() as db:
        yield db


def _rows(days, artefact=GOOD, per_day=2):
    return [SelectionRow(k * 10 + r, day, 100 + r, r + 1, 1.0 - r / 10, artefact_sha256=artefact,
                         scored_at=datetime(day.year, day.month, day.day, 3, 5, tzinfo=timezone.utc) + timedelta(days=1))
            for k, day in enumerate(days) for r in range(per_day)]


def test_the_label_names_rule_model_selector_and_month():
    assert tg_holdout_label(3, (2026, 11)) == "TG-001:SEL-001:hsel3:2026-11"


def test_more_than_ten_percent_missing_fails_and_exactly_ten_passes():
    exactly = shadow_coverage(_rows(DAYS[1:]), sessions=DAYS, artefacts=ARTEFACTS)
    assert exactly.missing_share == pytest.approx(0.10) and not exactly.missing_share > 0.10
    assert exactly.artefacts == {GOOD: 9}
    over = shadow_coverage(_rows(DAYS[2:]), sessions=DAYS, artefacts=ARTEFACTS)
    assert over.missing_share == pytest.approx(0.20)


def test_an_artefact_not_fitted_before_its_scores_uncovers_the_session():
    rows = _rows(DAYS[:5]) + _rows(DAYS[5:6], artefact=LATE) + _rows(DAYS[6:], artefact="f" * 64)
    coverage = shadow_coverage(rows, sessions=DAYS, artefacts=ARTEFACTS)
    assert coverage.covered == tuple(DAYS[:5])  # LATE was decided after 11 Aug's scores; f... was never decided
    mixed = _rows(DAYS[:1]) + [SelectionRow(999, DAYS[0], 300, 3, 0.1, artefact_sha256="f" * 64,
                                            scored_at=datetime(2026, 8, 4, 3, 5, tzinfo=timezone.utc))]
    assert shadow_coverage(mixed, sessions=DAYS[:1], artefacts=ARTEFACTS).covered == ()


def test_provenance_is_inclusive_at_the_instant_across_naive_and_aware_times():
    """SQLite reads DateTime(timezone=True) back naive; Postgres keeps it aware. Equal instants still compare equal."""
    scored = datetime(2026, 8, 4, 3, 5, tzinfo=timezone.utc)
    row = SelectionRow(1, DAYS[0], 100, 1, 0.9, artefact_sha256=GOOD, scored_at=scored)
    assert shadow_coverage([row], sessions=DAYS[:1], artefacts=[(GOOD, scored.replace(tzinfo=None))]).covered == (DAYS[0],)
    later = scored.replace(tzinfo=None) + timedelta(microseconds=1)
    assert shadow_coverage([row], sessions=DAYS[:1], artefacts=[(GOOD, later)]).covered == ()


def test_the_spg_guard_needs_a_registration_at_or_after_the_month_and_not_in_the_future():
    month = (2026, 8)
    assert spg_exam_available([((2026, 9), AT - timedelta(days=1))], month=month, now=AT)
    assert spg_exam_available([((2026, 8), AT)], month=month, now=AT)
    assert not spg_exam_available([((2026, 7), AT - timedelta(days=1))], month=month, now=AT)
    assert not spg_exam_available([((2026, 9), AT + timedelta(seconds=1))], month=month, now=AT)
    assert not spg_exam_available([], month=month, now=AT)


def _window(session):
    return register_window(session, selector_horizon=3, month=(2026, 8), window_start=AT, window_end=AT, at=AT)


def test_a_window_is_registered_once(session):
    first = _window(session)
    assert _window(session).id == first.id
    assert session.scalar(select(func.count()).select_from(TradeGeometryHoldoutWindow)) == 1


def test_the_freeze_spends_the_month_once_even_under_a_concurrent_insert(session):
    window = _window(session)
    bound = seed_decision(session, model_version="SEL-001", horizon=3)
    session.commit()
    kwargs = dict(window=window, selector_horizon=3, month=(2026, 8), bound_spg_decision_id=bound.id,
                  geometry=grid()[27], grid_sha256="g" * 64, config_sha256="c" * 64, dataset_sha256="d" * 64,
                  wf_selection_sha256="w" * 64, at=AT)
    usage = freeze(session, **kwargs)
    assert usage is not None and already_consumed(session, label=window.label)
    assert (usage.geometry_key, usage.holding_sessions, usage.target_atr_multiple) == ("H7:Tnone:Snone", 7, None)
    assert freeze(session, **kwargs) is None  # the unique label is the last line of defence
    assert session.scalar(select(func.count()).select_from(TradeGeometryHoldoutUsage)) == 1
```

- [ ] **Step 2: Run; expect FAIL**

Run: `DATABASE_URL="sqlite:///$SCRATCH/tg-8.db" python -m pytest tests/test_trade_geometry_holdout.py -q -p no:cacheprovider`
Expected: `ModuleNotFoundError: No module named 'app.trade_geometry.holdout'`.

- [ ] **Step 3: Implement** `app/trade_geometry/holdout.py`

```python
"""TG-001 §13.4/§13.5: TG's own exam registry, the SPG availability guard, shadow coverage and the freeze."""
from __future__ import annotations

from collections import Counter
from dataclasses import dataclass
from datetime import date, datetime
from decimal import Decimal
from typing import Sequence

from sqlalchemy import select
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session

from app.models import TradeGeometryHoldoutUsage, TradeGeometryHoldoutWindow
from app.selection_gate.authorization import naive_utc
from app.selection_gate.constants import SEL001_MODEL_VERSION
from app.selection_gate.validation import Month, month_text

from .config import Geometry
from .constants import TG_HOLDOUT_REGISTRY_VERSION, TG_RULE_VERSION
from .inputs import SelectionRow


@dataclass(frozen=True)
class Coverage:
    covered: tuple[date, ...]
    missing_share: float
    artefacts: dict[str, int]  # artefact_sha256 -> covered sessions it scored


def tg_holdout_label(selector_horizon: int, month: Month) -> str:
    return f"{TG_RULE_VERSION}:{SEL001_MODEL_VERSION}:hsel{selector_horizon}:{month_text(month)}"


def register_window(session: Session, *, selector_horizon: int, month: Month, window_start: datetime,
                    window_end: datetime, at: datetime) -> TradeGeometryHoldoutWindow:
    label = tg_holdout_label(selector_horizon, month)
    existing = session.scalar(select(TradeGeometryHoldoutWindow).where(TradeGeometryHoldoutWindow.label == label))
    if existing is not None:
        return existing
    row = TradeGeometryHoldoutWindow(label=label, model_version=SEL001_MODEL_VERSION, selector_horizon=selector_horizon,
                                     holdout_month=month_text(month), window_start=window_start,
                                     window_end=window_end, registered_at=at,
                                     registry_version=TG_HOLDOUT_REGISTRY_VERSION)
    session.add(row)
    session.flush()
    return row


def spg_exam_available(registrations: Sequence[tuple[Month, datetime]], *, month: Month, now: datetime) -> bool:
    """§13.5 check 1: SPG has registered an exam month >= M_TG at h_sel, at or before now."""
    return any(m >= month and naive_utc(at) <= naive_utc(now) for m, at in registrations)


def already_consumed(session: Session, *, label: str) -> bool:
    return session.scalar(
        select(TradeGeometryHoldoutUsage.id).where(TradeGeometryHoldoutUsage.holdout_label == label)) is not None


def shadow_coverage(rows: Sequence[SelectionRow], *, sessions: Sequence[date],
                    artefacts: Sequence[tuple[str, datetime]]) -> Coverage:
    """§13.5 check 3: a session is covered iff it has a row and every row's artefact was decided before scoring."""
    by_day: dict[date, list[SelectionRow]] = {}
    for row in rows:
        by_day.setdefault(row.session_date, []).append(row)

    def fitted_before(row: SelectionRow) -> bool:
        return any(sha == row.artefact_sha256 and naive_utc(decided) <= naive_utc(row.scored_at)
                   for sha, decided in artefacts)

    covered = tuple(day for day in sessions if by_day.get(day) and all(fitted_before(r) for r in by_day[day]))
    used = Counter(by_day[day][0].artefact_sha256 for day in covered)
    missing = (len(sessions) - len(covered)) / len(sessions) if sessions else 1.0
    return Coverage(covered, missing, dict(sorted(used.items())))


def _multiple(value: float | None) -> Decimal | None:
    return None if value is None else Decimal(str(value))


def freeze(session: Session, *, window: TradeGeometryHoldoutWindow, selector_horizon: int, month: Month,
           bound_spg_decision_id: int, geometry: Geometry, grid_sha256: str, config_sha256: str,
           dataset_sha256: str, wf_selection_sha256: str, at: datetime) -> TradeGeometryHoldoutUsage | None:
    """§13.4: commit the choice before any held-out path is read. That commit spends the month; None if spent."""
    usage = TradeGeometryHoldoutUsage(
        holdout_label=window.label, window_id=window.id, model_version=SEL001_MODEL_VERSION,
        selector_horizon=selector_horizon, holdout_month=month_text(month),
        bound_spg_decision_id=bound_spg_decision_id, geometry_key=geometry.key,
        holding_sessions=geometry.holding_sessions, target_atr_multiple=_multiple(geometry.target_atr_multiple),
        stop_atr_multiple=_multiple(geometry.stop_atr_multiple), grid_sha256=grid_sha256,
        config_sha256=config_sha256, dataset_sha256=dataset_sha256, wf_selection_sha256=wf_selection_sha256,
        used_at=at,
    )
    session.add(usage)
    try:
        session.commit()
    except IntegrityError:
        session.rollback()
        return None
    return usage
```

- [ ] **Step 4: Run; expect PASS (7 passed)**

Run: `DATABASE_URL="sqlite:///$SCRATCH/tg-8.db" python -m pytest tests/test_trade_geometry_holdout.py -q -p no:cacheprovider`

- [ ] **Step 5: Commit**

```bash
git add app/trade_geometry/holdout.py tests/test_trade_geometry_holdout.py
git commit -m "TG-001: TG exam registry, SPG availability guard, shadow coverage and freeze" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 9: Universe benchmarks and one geometry's stage

**Files:**
- Create: `app/trade_geometry/evaluation.py`
- Test: `tests/test_trade_geometry_evaluation.py`

**Interfaces:**
- Consumes: SPG `AcceptedTrade`, `StageThresholds`, `evaluate_stage`, `StageStatistics`. Also `fills.simulate` / `path_statistics`, `reduction.reduce_geometry_candidates`, `choice.fold_statistics` / `stage_reasons` / `GeometryScore`, `PathSet.locate` and `SelectionRow`.
- Produces:
  - `Benchmarks(returns: dict[key, ndarray], resolved_counts: dict[H, ndarray], min_stocks)` with `.benchmarkable(H, i)` and `.value(geometry, i)`
  - `universe_benchmarks(paths, geometries, *, sessions, min_stocks) -> Benchmarks`
  - `TradeRow`, and `GeometryRun(geometry, stage, statistics, folds, reasons, metrics, reduction, benchmarkable_sessions, trades, session_excess)` with `.score -> GeometryScore`
  - `evaluate_geometry(*, stage, geometry, candidates, paths, benchmarks, dates, stage_sessions, thresholds, fold_months, min_positive_share) -> GeometryRun`
  - `fold_months(sessions, dates) -> tuple[Month, ...]`, `walk_forward_matrix(runs, sessions) -> (sums [G, m], counts [G, m])`

`metrics` keys: `unresolved_share`, `mean_excess_vs_hold`, `target_rate`, `stop_rate`, `expired_rate`, `ambiguous_rate`, `mean_mfe`, `mean_mae`, `mean_mdd`, `mean_time_to_target`, `mean_time_to_stop`, `turnover`, `cost_drag`. `reduction` keys: `candidates`, `duplicates_removed`, `overlap_suppressed`, `unresolved_trades`, `unresolved_by_status`.

- [ ] **Step 1: Write the failing test** `tests/test_trade_geometry_evaluation.py`

```python
from datetime import date, timedelta

import numpy as np
import pytest

from app.selection_gate.constants import (
    DISPOSITION_ACCEPTED, DISPOSITION_UNRESOLVED, STAGE_HELD_OUT, STAGE_WALK_FORWARD,
)
from app.selection_gate.statistics import StageThresholds
from app.trade_geometry.config import Geometry, hold_geometry
from app.trade_geometry.constants import NOT_BENCHMARKABLE, OUTCOME_TARGET
from app.trade_geometry.evaluation import evaluate_geometry, fold_months, universe_benchmarks, walk_forward_matrix
from app.trade_geometry.fills import path_status
from app.trade_geometry.inputs import SelectionRow
from app.trade_geometry.paths import PathSet

DATES = tuple(date(2026, 3, 2) + timedelta(days=k) for k in range(60))
TARGET_PATH = ((100.0, 101.5, 99.0, 101.0), (101.2, 103.4, 100.8, 103.1)) + ((103.0, 103.5, 102.5, 103.0),) * 5
FLAT_PATH = ((100.0, 100.5, 99.5, 100.0),) * 7
THRESHOLDS = StageThresholds(min_trades=1, min_net_excess=0.0, max_unresolved_share=1.0, min_folds=None, cost=0.003,
                             bootstrap_draws=50, bootstrap_seed=42)
G = Geometry(5, 1.5, 1.0, grid_index=23)


def _pathset(rows):
    """rows: (session_index, stock_id, path bars, atr)."""
    session_index = np.array([r[0] for r in rows], dtype=np.int64)
    stock_id = np.array([r[1] for r in rows], dtype=np.int64)
    paths = np.full((len(rows), 7, 4), np.nan, dtype=np.float32)
    for k, (_, _, bars, _) in enumerate(rows):
        paths[k, :len(bars)] = bars
    entry_open, atr = np.full(len(rows), 100.0), np.array([r[3] for r in rows], dtype=np.float32)
    status = {h: path_status(paths, entry_open, atr, session_index, horizon=h, last_index=len(DATES) - 1,
                             min_return=-0.6, max_return=1.5) for h in (1, 3, 5, 7)}
    return PathSet(session_index, stock_id, entry_open, atr, paths, status)


def _evaluate(paths, candidates, *, min_stocks, stage=STAGE_HELD_OUT, geometry=G):
    benchmarks = universe_benchmarks(paths, [geometry, hold_geometry(geometry.holding_sessions)], sessions=len(DATES),
                                     min_stocks=min_stocks)
    sessions = sorted({int(i) for i in paths.session_index})
    return evaluate_geometry(stage=stage, geometry=geometry, candidates=candidates, paths=paths,
                             benchmarks=benchmarks, dates=DATES, stage_sessions=sessions,
                             thresholds=THRESHOLDS, fold_months=fold_months(sessions, DATES) if stage == STAGE_WALK_FORWARD else None,
                             min_positive_share=0.55), benchmarks


def test_the_benchmark_is_the_mean_over_resolved_rows_each_on_its_own_levels():
    paths = _pathset([(10, 1, TARGET_PATH, 0.02), (10, 2, TARGET_PATH, 0.012), (10, 3, TARGET_PATH[:2], 0.02)])
    _, benchmarks = _evaluate(paths, [], min_stocks=2)
    # Row 1 targets at 103.00 (r = 0.030), row 2 at its own 101.80 (r = 0.018); row 3's path is unresolved at H = 5.
    assert benchmarks.value(G, 10) == pytest.approx((0.030 + 0.018) / 2, abs=1e-6)
    assert benchmarks.resolved_counts[5][10] == 2 and benchmarks.benchmarkable(5, 10)
    assert not universe_benchmarks(paths, [G], sessions=len(DATES), min_stocks=3).benchmarkable(5, 10)


def test_candidates_in_an_unbenchmarkable_session_are_unresolved_not_dropped():
    paths = _pathset([(10, 1, TARGET_PATH, 0.02), (11, 1, FLAT_PATH, 0.02), (11, 2, FLAT_PATH, 0.02)])
    candidates = [SelectionRow(1, DATES[10], 1, 1, 0.9), SelectionRow(2, DATES[11], 2, 1, 0.9)]
    run, _ = _evaluate(paths, candidates, min_stocks=2)
    assert [t.disposition for t in run.trades] == [DISPOSITION_UNRESOLVED, DISPOSITION_ACCEPTED]
    assert run.reduction["unresolved_by_status"] == {NOT_BENCHMARKABLE: 1}
    assert run.statistics.unresolved_trades == 1 and run.reduction["candidates"] == 2


def test_an_accepted_trade_carries_its_outcome_exit_and_benchmarks():
    paths = _pathset([(10, 1, TARGET_PATH, 0.02), (10, 2, FLAT_PATH, 0.02)])
    run, benchmarks = _evaluate(paths, [SelectionRow(7, DATES[10], 1, 1, 0.9)], min_stocks=2)
    (trade,) = run.trades
    assert (trade.outcome, trade.exit_offset, trade.exit_index, trade.disposition) == (OUTCOME_TARGET, 2, 12,
                                                                                       DISPOSITION_ACCEPTED)
    assert trade.benchmark_return == pytest.approx(benchmarks.value(G, 10))
    assert trade.hold_benchmark_return == pytest.approx(benchmarks.value(hold_geometry(5), 10))
    assert run.metrics["mean_time_to_target"] == 2.0 and run.metrics["mean_time_to_stop"] is None
    assert run.metrics["target_rate"] == 1.0 and run.metrics["cost_drag"] == pytest.approx(0.003)
    assert run.session_excess == {10: (pytest.approx(0.03 - 0.003 - benchmarks.value(G, 10), abs=1e-6), 1)}


def test_walk_forward_folds_and_the_reality_check_matrix_use_t_sigma():
    paths = _pathset([(i, s, TARGET_PATH, 0.02) for i in (10, 40) for s in (1, 2)])
    candidates = [SelectionRow(i, DATES[i], 1, 1, 0.9) for i in (10, 40)]
    run, _ = _evaluate(paths, candidates, min_stocks=2, stage=STAGE_WALK_FORWARD)
    assert [f["month"] for f in run.folds.folds] == ["2026-03", "2026-04"] and run.folds.folds_tested == 2
    sums, counts = walk_forward_matrix([run], [10, 25, 40])
    assert counts.tolist() == [[1.0, 0.0, 1.0]] and sums[0, 1] == 0.0
```

- [ ] **Step 2: Run; expect FAIL**

Run: `DATABASE_URL="sqlite:///$SCRATCH/tg-9.db" python -m pytest tests/test_trade_geometry_evaluation.py -q -p no:cacheprovider`
Expected: `ModuleNotFoundError: No module named 'app.trade_geometry.evaluation'`.

- [ ] **Step 3: Implement** `app/trade_geometry/evaluation.py`

```python
"""TG-001 §11-§13.2: universe benchmarks per (D, g), and one geometry's stage from candidates to statistics."""
from __future__ import annotations

from collections import Counter
from dataclasses import dataclass
from datetime import date
from typing import Iterable, Sequence

import numpy as np

from app.selection_gate.constants import (
    DISPOSITION_ACCEPTED, DISPOSITION_DUPLICATE, DISPOSITION_OVERLAP_SUPPRESSED, DISPOSITION_UNRESOLVED, LABEL_RESOLVED,
)
from app.selection_gate.statistics import AcceptedTrade, StageStatistics, StageThresholds, evaluate_stage
from app.selection_gate.validation import Month, month_of

from .choice import FoldStatistics, GeometryScore, fold_statistics, stage_reasons
from .config import Geometry, hold_geometry
from .constants import NOT_BENCHMARKABLE, NOT_IN_DATASET, OUTCOME_EXPIRED, OUTCOME_STOP, OUTCOME_TARGET
from .fills import path_statistics, simulate
from .inputs import SelectionRow
from .paths import PathSet
from .reduction import GeometryCandidate, reduce_geometry_candidates

_CHUNK = 250_000


@dataclass(frozen=True)
class Benchmarks:
    returns: dict[str, np.ndarray]        # geometry key -> b_g(D) per session index (NaN without resolved rows)
    resolved_counts: dict[int, np.ndarray]  # H -> resolved U(D) rows per session index
    min_stocks: int

    def benchmarkable(self, horizon: int, index: int) -> bool:
        return bool(self.resolved_counts[horizon][index] >= self.min_stocks)

    def value(self, geometry: Geometry, index: int) -> float:
        return float(self.returns[geometry.key][index])


@dataclass(frozen=True)
class TradeRow:
    candidate: SelectionRow
    session_index: int | None
    path_status: str
    entry_open: float | None
    atr: float | None
    target_price: float | None
    stop_price: float | None
    exit_price: float | None
    exit_index: int | None
    outcome: str | None
    ambiguous: bool | None
    exit_offset: int | None
    mfe: float | None
    mae: float | None
    gross_return: float | None
    benchmark_return: float | None
    hold_benchmark_return: float | None
    disposition: str


@dataclass(frozen=True)
class GeometryRun:
    geometry: Geometry
    stage: str
    statistics: StageStatistics
    folds: FoldStatistics | None
    reasons: tuple[str, ...]
    metrics: dict
    reduction: dict
    benchmarkable_sessions: int
    trades: tuple[TradeRow, ...]
    session_excess: dict[int, tuple[float, int]]  # session index -> (sum of e_j, accepted trades)

    @property
    def score(self) -> GeometryScore:
        share = None if self.folds is None else self.folds.fold_positive_share
        return GeometryScore(self.geometry, self.reasons, self.statistics.ci_low, share)


def universe_benchmarks(paths: PathSet, geometries: Iterable[Geometry], *, sessions: int,
                        min_stocks: int) -> Benchmarks:
    """§11: b_g(D) = mean r under g over every U(D) row RESOLVED at H, each row on its own atr levels."""
    geometries = {g.key: g for g in geometries}.values()
    counts, returns = {}, {}
    for horizon in sorted({g.holding_sessions for g in geometries}):
        resolved = paths.status[horizon] == LABEL_RESOLVED
        counts[horizon] = np.bincount(paths.session_index[resolved], minlength=sessions)
    for geometry in geometries:
        rows = np.flatnonzero(paths.status[geometry.holding_sessions] == LABEL_RESOLVED)
        sums = np.zeros(sessions)
        for first in range(0, len(rows), _CHUNK):
            part = rows[first:first + _CHUNK]
            fills = simulate(paths.paths[part], paths.entry_open[part], paths.atr[part], geometry)
            sums += np.bincount(paths.session_index[part], weights=fills.gross_return, minlength=sessions)
        count = counts[geometry.holding_sessions]
        returns[geometry.key] = np.divide(sums, count, out=np.full(sessions, np.nan), where=count > 0)
    return Benchmarks(returns, counts, min_stocks)


def _mean(values) -> float | None:
    return float(np.mean(values)) if len(values) else None


def evaluate_geometry(*, stage: str, geometry: Geometry, candidates: Sequence[SelectionRow], paths: PathSet,
                      benchmarks: Benchmarks, dates: Sequence[date],
                      stage_sessions: Sequence[int], thresholds: StageThresholds, fold_months: Sequence[Month] | None,
                      min_positive_share: float) -> GeometryRun:
    horizon, cost = geometry.holding_sessions, thresholds.cost
    hold = hold_geometry(horizon)
    index_of = {day: i for i, day in enumerate(dates)}
    indices = [index_of.get(row.session_date) for row in candidates]
    mapped = [(i, row.stock_id) for i, row in zip(indices, candidates) if i is not None]
    found = iter(paths.locate(mapped))
    located = [(row, i, None if i is None else next(found)) for row, i in zip(candidates, indices)]
    known = np.array(sorted({p for _, _, p in located if p is not None}), dtype=np.int64)
    slot = {int(p): k for k, p in enumerate(known)}
    fills = simulate(paths.paths[known], paths.entry_open[known], paths.atr[known], geometry)
    drawdown = path_statistics(paths.paths[known], paths.entry_open[known], horizon=horizon)["mdd"]

    by_source, reduction_input = {}, []
    for row, index, position in located:
        status = NOT_IN_DATASET if position is None else paths.status[horizon][position]
        benchmarkable = index is not None and benchmarks.benchmarkable(horizon, index)
        resolved = status == LABEL_RESOLVED and benchmarkable
        k = None if position is None else slot[position]
        exit_index = index + int(fills.exit_offset[k]) if resolved else None
        reduction_input.append(GeometryCandidate(row.source_row_id, row.session_date, index, row.stock_id, row.rank,
                                                 row.score, resolved, exit_index))
        by_source[row.source_row_id] = (row, index, k, status, benchmarkable)
    reduced = reduce_geometry_candidates(reduction_input, dates=dates)

    trades, accepted, excess, hold_excess, chosen = [], [], [], [], []
    unresolved = Counter()
    session_excess: dict[int, list] = {}
    for candidate, disposition in reduced:
        row, index, k, status, benchmarkable = by_source[candidate.source_row_id]
        resolved_path = k is not None and status == LABEL_RESOLVED
        bench = benchmarks.value(geometry, index) if benchmarkable else None
        hold_bench = benchmarks.value(hold, index) if benchmarkable else None
        gross = float(fills.gross_return[k]) if resolved_path else None
        if disposition == DISPOSITION_UNRESOLVED:
            unresolved[NOT_BENCHMARKABLE if status == LABEL_RESOLVED else status] += 1
        if disposition == DISPOSITION_ACCEPTED:
            accepted.append(AcceptedTrade(index, gross, bench))
            e = gross - cost - bench
            excess.append(e)
            hold_excess.append(gross - cost - hold_bench)
            chosen.append(k)
            total = session_excess.setdefault(index, [0.0, 0])
            total[0] += e
            total[1] += 1
        trades.append(TradeRow(
            candidate=row, session_index=index, path_status=status,
            entry_open=None if k is None else float(paths.entry_open[known[k]]),
            atr=None if k is None else float(paths.atr[known[k]]),
            target_price=None if k is None or np.isnan(fills.target_price[k]) else float(fills.target_price[k]),
            stop_price=None if k is None or np.isnan(fills.stop_price[k]) else float(fills.stop_price[k]),
            exit_price=float(fills.exit_price[k]) if resolved_path else None,
            exit_index=index + int(fills.exit_offset[k]) if resolved_path else None,
            outcome=fills.outcome[k] if resolved_path else None,
            ambiguous=bool(fills.ambiguous[k]) if resolved_path else None,
            exit_offset=int(fills.exit_offset[k]) if resolved_path else None,
            mfe=float(fills.mfe[k]) if resolved_path else None, mae=float(fills.mae[k]) if resolved_path else None,
            gross_return=gross, benchmark_return=bench, hold_benchmark_return=hold_bench, disposition=disposition,
        ))

    folds = None
    if fold_months is not None:
        folds = fold_statistics(fold_months, [(month_of(dates[t.session_index]), e) for t, e in zip(accepted, excess)])
    statistics = evaluate_stage(accepted, unresolved_trades=sum(unresolved.values()),
                                folds_tested=None if folds is None else folds.folds_tested, horizon=horizon,
                                thresholds=thresholds)
    reasons = stage_reasons(statistics, folds, min_positive_share=min_positive_share)
    outcomes = fills.outcome[chosen] if chosen else np.array([], dtype=object)
    n, u = len(accepted), sum(unresolved.values())
    metrics = {
        "unresolved_share": u / (n + u) if n + u else None,
        "mean_excess_vs_hold": _mean(hold_excess),
        "target_rate": _mean(outcomes == OUTCOME_TARGET), "stop_rate": _mean(outcomes == OUTCOME_STOP),
        "expired_rate": _mean(outcomes == OUTCOME_EXPIRED),
        "ambiguous_rate": _mean(fills.ambiguous[chosen]) if chosen else None,
        "mean_mfe": _mean(fills.mfe[chosen]) if chosen else None,
        "mean_mae": _mean(fills.mae[chosen]) if chosen else None,
        "mean_mdd": _mean(drawdown[chosen]) if chosen else None,
        "mean_time_to_target": _mean(fills.exit_offset[chosen][outcomes == OUTCOME_TARGET]) if chosen else None,
        "mean_time_to_stop": _mean(fills.exit_offset[chosen][outcomes == OUTCOME_STOP]) if chosen else None,
        "turnover": n / len(stage_sessions) if stage_sessions else None,
        "cost_drag": cost * n,
    }
    counts = Counter(d for _, d in reduced)
    reduction = {"candidates": len(reduced), "duplicates_removed": counts[DISPOSITION_DUPLICATE],
                 "overlap_suppressed": counts[DISPOSITION_OVERLAP_SUPPRESSED],
                 "unresolved_trades": counts[DISPOSITION_UNRESOLVED], "unresolved_by_status": dict(sorted(unresolved.items()))}
    benchmarkable_sessions = sum(benchmarks.benchmarkable(horizon, i) for i in stage_sessions)
    return GeometryRun(geometry, stage, statistics, folds, reasons, metrics, reduction, benchmarkable_sessions,
                       tuple(trades), {i: (s, c) for i, (s, c) in session_excess.items()})


def fold_months(sessions: Sequence[int], dates: Sequence[date]) -> tuple[Month, ...]:
    """§13.1: the folds are the calendar months of T_sigma."""
    return tuple(sorted({month_of(dates[i]) for i in sessions}))


def walk_forward_matrix(runs: Sequence[GeometryRun], sessions: Sequence[int]) -> tuple[np.ndarray, np.ndarray]:
    """§15.5 inputs: per grid geometry with n_g >= 1, its excess sum and count on each session of T_sigma."""
    used = [r for r in runs if not r.geometry.is_reference and r.statistics.trades >= 1]
    column = {i: c for c, i in enumerate(sorted(sessions))}
    sums, counts = np.zeros((len(used), len(column))), np.zeros((len(used), len(column)))
    for row, run in enumerate(used):
        for index, (total, count) in run.session_excess.items():
            sums[row, column[index]], counts[row, column[index]] = total, count
    return sums, counts
```

- [ ] **Step 4: Run; expect PASS (4 passed)**

Run: `DATABASE_URL="sqlite:///$SCRATCH/tg-9.db" python -m pytest tests/test_trade_geometry_evaluation.py -q -p no:cacheprovider`

- [ ] **Step 5: Commit**

```bash
git add app/trade_geometry/evaluation.py tests/test_trade_geometry_evaluation.py
git commit -m "TG-001: per-(D, g) universe benchmarks and the per-geometry stage evaluation" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 10: Records writers, parity block, runner and report

**Files:**
- Modify (replace the whole file): `app/trade_geometry/records.py`
- Create: `app/trade_geometry/parity.py`, `app/trade_geometry/runner.py`, `app/trade_geometry/report.py`, `tests/_trade_geometry_factories.py`
- Test: `tests/test_trade_geometry_runner.py`, `tests/test_trade_geometry_parity.py`

**Interfaces:**
- Consumes: everything from Tasks 1–9. From SPG: `build_selection_dataset`, `selection_code_version()`, `config_sha256`, `NSE_TIMEZONE`, and the constants `REASON_EVALUATION_FAILED`, `REASON_HOLDOUT_ALREADY_CONSUMED`, `RESULT_PASS`/`RESULT_FAIL`, `STAGE_*`.
- Produces:
  - `run_trade_geometry(session, *, read_session=None, walk_forward_only=False, now=None, code_version=None) -> dict` with `{"dataset": {...}, "selectors": [report, ...]}`. Each selector report holds `selector_horizon`, `bound_spg_decision_id`, `stage_less_reason`, `wf_sessions`, `wf_protected_excluded`, `reality_check_*`, `tolerance_set`, `chosen`, `wf_reasons`, `parity`, `geometries` (37 rows), and `decision` (column dict) when one was written.
  - `parity_block(*, dataset, selector_horizon, time_exit, benchmarks, bound, candidates, sessions) -> dict`
  - `records.decimal`, `multiple`, `run_fields`, `fold_fields`, `write_decision(session, values, *, usage)`, `write_results`, `write_trades`, `FreezeMismatchError`
  - `render_report(result) -> (text, json)`

Run order, per spec §13 and §17:
1. Build SEL-DS-001 once. It fails closed.
2. Compute `M_TG`, `P_TG`. Outside walk-forward-only mode, register both TG windows and commit.
3. Bind each selector and build its `T_σ`.
4. Pass 1 builds paths for every `U(D)` row of `T_3 ∪ T_5`. Compute universe benchmarks for 36 + 1 geometries and `dataset_sha256`.
5. Per selector: 37 walk-forward runs, the reality check, the robust choice and parity. With a chosen geometry: checks 1–3, then the freeze (commit).
6. Pass 2 runs once, after every freeze, over the exam month's window, for the frozen geometries only.
7. Per selector, one transaction holds the decision, its 37 results and its trades.

A failure in steps 1–4 writes `EVALUATION_FAILED` for both selectors and re-raises. A failure in step 5 or 7 is confined to its selector. A failure in pass 2 marks every frozen selector `EVALUATION_FAILED`, and their months stay spent.

The test world (`tests/_trade_geometry_factories.py`) is built as follows:
- sessions: 200 weekday sessions ending 2026-09-30
- stocks: two edge stocks gaining 0.6% a session, and ten flat zig-zag stocks
- bound decisions: SEL-001 decisions at h=3 and h=5, whose March–August walk-forward rows come from SPG's own `run_stage`/`write_decision`/`write_trades`, with the edge stocks ranked first
- provenance: an older SEL-001 decision whose artefact scored August in shadow
- registration: SPG's September registration
- clock: `NOW = 2026-10-11 20:30 UTC`

The prototype measured these outcomes:
- `M_TG = 2026-08` and `P_TG = (157, 184)`
- `T_σ` has 103 sessions, and 56 candidates are excluded as protected
- `g* = H7:Tnone:Snone`, chosen over its two equal-`ci_low` stop variants, with `p = 0`
- the held-out stage has 6 trades on 3 sessions, so the CI is undefined (`m < 2H`), giving `NO_GEOMETRY / HELD_OUT / INSUFFICIENT_OUT_OF_SAMPLE_EVIDENCE`
- with `tg_holding_horizons = (1, 3)`, the same world reaches `GEOMETRY_PASS` end to end

- [ ] **Step 1: Write the factories** `tests/_trade_geometry_factories.py`

```python
"""TG-001 test factories: a small universe with a planted edge, a bound SPG decision written by SPG's own code,
SEL-001 shadow scores for the exam month, and SPG's exam registration."""
from __future__ import annotations

from dataclasses import dataclass
from datetime import date, datetime, time, timedelta, timezone
from decimal import Decimal

import pandas as pd
from sqlalchemy import select

from app.models import HoldoutWindowRegistry, MarketPrice, SelectionShadowScore, Stock
from app.selection_gate.config import config_sha256, config_snapshot
from app.selection_gate.constants import (
    HOLDOUT_REGISTRY_VERSION, REASON_INSUFFICIENT_EVIDENCE, SEL001_MODEL_VERSION, STAGE_WALK_FORWARD,
)
from app.selection_gate.dataset import build_selection_dataset
from app.selection_gate.evaluation import run_stage
from app.selection_gate.records import write_decision, write_trades
from app.selection_gate.statistics import StageThresholds, decide, failed_stage
from app.selection_gate.validation import month_of
from app.settings import settings
from app.walk_forward_dataset import session_cutoff
from tests._selection_gate_factories import OFFICIAL_SOURCE, seed_decision, weekday_sessions

SESSIONS = weekday_sessions(date(2026, 9, 30), 200)  # 2025-12-25 .. 2026-09-30; index 47 = 2026-03-02
NOW = datetime(2026, 10, 11, 20, 30, tzinfo=timezone.utc)  # 02:00 IST on 12 Oct, the CronJob's slot
SPG_DECIDED = datetime(2026, 10, 10, 20, 30, tzinfo=timezone.utc)
OLD_ARTEFACT, BOUND_ARTEFACT = "a" * 64, "b" * 64
FIRST_TESTED = 47
SMALL = {
    "selection_min_session_stocks": 1, "selection_min_benchmark_stocks": 3, "selection_min_history_bars": 25,
    "selection_min_median_traded_value_20d": 0.0, "selection_top_k": 2,
    "tg_min_wf_trades": 10, "tg_min_holdout_trades": 3, "tg_min_wf_folds": 3, "tg_bootstrap_draws": 200,
    "tg_holdout_not_before": "2026-08",
}


@dataclass(frozen=True)
class World:
    edge: tuple[Stock, ...]
    flat: tuple[Stock, ...]
    bound: dict[int, object]


def apply_small_settings(monkeypatch, **overrides) -> None:
    for name, value in {**SMALL, **overrides}.items():
        monkeypatch.setattr(settings, name, value)


def seed_path_stock(session, *, symbol, closes, sessions=SESSIONS, sector="TECH") -> Stock:
    """Official bars: open = previous close, high/low 0.3% outside the body, so atr is about the body size."""
    stock = Stock(symbol=symbol, sector=sector)
    session.add(stock)
    session.flush()
    previous = closes[0]
    for day, close in zip(sessions, closes):
        o, c = previous, close
        session.add(MarketPrice(
            stock_id=stock.id, timestamp=session_cutoff(day), open=Decimal(str(round(o, 4))),
            high=Decimal(str(round(max(o, c) * 1.003, 4))), low=Decimal(str(round(min(o, c) * 0.997, 4))),
            close=Decimal(str(round(c, 4))), volume=1_000_000, source=OFFICIAL_SOURCE,
        ))
        previous = close
    session.flush()
    return stock


def edge_closes(n=len(SESSIONS), drift=0.006):
    return [100.0 * (1 + drift) ** k for k in range(n)]


def flat_closes(phase, n=len(SESSIONS)):
    return [100.0 + 0.2 * ((k + phase) % 2) for k in range(n)]


def seed_universe(session) -> tuple[tuple[Stock, ...], tuple[Stock, ...]]:
    edge = tuple(seed_path_stock(session, symbol=f"EDGE{k}", closes=edge_closes()) for k in range(2))
    flat = tuple(seed_path_stock(session, symbol=f"FLAT{k}", closes=flat_closes(k)) for k in range(10))
    return edge, flat


def tested_sessions(dates, last_month=(2026, 8)):
    return [i for i, day in enumerate(dates) if i >= FIRST_TESTED and month_of(day) <= last_month]


def seed_bound_decision(session, *, horizon, edge_ids, decided_at=SPG_DECIDED, artefact_sha256=BOUND_ARTEFACT,
                        snapshot=None):
    """A SEL-001 decision whose walk-forward trades SPG's own run_stage/write_trades produced (edge stocks rank first)."""
    dataset = build_selection_dataset(session, horizons=(horizon,))
    rows, dates = dataset.rows, dataset.session_dates
    ranked = {sid: 2.0 - 0.1 * k for k, sid in enumerate(edge_ids)}
    scores = pd.Series([ranked.get(int(sid), 1.0 - 0.001 * int(sid)) for sid in rows.stock_id], index=rows.index)
    thresholds = StageThresholds(min_trades=5, min_net_excess=0.005, max_unresolved_share=0.02, min_folds=None,
                                 cost=settings.selection_round_trip_cost, bootstrap_draws=50, bootstrap_seed=42)
    tested = tested_sessions(dates)
    wf_run = run_stage(stage=STAGE_WALK_FORWARD, model_version=SEL001_MODEL_VERSION, horizon=horizon, rows=rows,
                       scores=scores, session_indices=tested, benchmark=dataset.benchmarks[horizon],
                       thresholds=thresholds, folds_tested=None, top_k=settings.selection_top_k)
    ho_stats = failed_stage(REASON_INSUFFICIENT_EVIDENCE, min_trades=3, cost=settings.selection_round_trip_cost)
    snapshot = snapshot or config_snapshot()
    months = sorted({month_of(dates[i]) for i in tested})
    decision = write_decision(
        session, model_version=SEL001_MODEL_VERSION, horizon=horizon, feature_version="FV-002",
        outcome=decide(wf_run.statistics, ho_stats), snapshot=snapshot, snapshot_sha=config_sha256(snapshot),
        code_version="test", decided_at=decided_at, validity_days=45,
        dataset_sha256=dataset.sha256_by_horizon[horizon], dataset_bounds=(dates[0], dates[-1]), dates=dates,
        wf=(wf_run, wf_run.statistics), ho=(None, ho_stats),
        folds=[{"month": f"{y:04d}-{m:02d}", "status": "TESTED"} for y, m in months], holdout={},
        artefact_id=None, artefact_sha256=artefact_sha256,
    )
    write_trades(session, decision, [wf_run], dates, horizon, settings.selection_round_trip_cost)
    session.flush()
    return decision


def seed_shadow_month(session, *, horizon, ranked_ids, month=(2026, 8), artefact_sha256=OLD_ARTEFACT,
                      skip=()) -> int:
    """Shadow rows as the 08:35 IST job writes them on D+1: ranks over the universe; only the top few are needed."""
    written = 0
    for day in SESSIONS:
        if month_of(day) != month or day in skip:
            continue
        scored_at = datetime.combine(day + timedelta(days=1), time(3, 5), tzinfo=timezone.utc)
        for rank, stock_id in enumerate(ranked_ids, start=1):
            session.add(SelectionShadowScore(
                model_version=SEL001_MODEL_VERSION, artefact_sha256=artefact_sha256, horizon_sessions=horizon,
                session_date=day, stock_id=stock_id, score=Decimal(str(2.0 - 0.1 * rank)), rank=rank,
                selected=True, suppression_reason=None, scored_at=scored_at))
            written += 1
    session.flush()
    return written


def seed_spg_registration(session, *, horizon, month="2026-09", registered_at=SPG_DECIDED,
                          model_version=SEL001_MODEL_VERSION) -> HoldoutWindowRegistry:
    row = HoldoutWindowRegistry(label=f"SPG-001:{model_version}:h{horizon}:{month}", window_start=registered_at,
                                window_end=registered_at, registered_at=registered_at,
                                registry_version=HOLDOUT_REGISTRY_VERSION)
    session.add(row)
    session.flush()
    return row


def seed_world(session, *, horizons=(3, 5), shadow=True, registration=True) -> World:
    """Both selectors bound, an older SEL-001 artefact that scored August, and SPG's September registration."""
    edge, flat = seed_universe(session)
    edge_ids = [s.id for s in edge]
    bound = {}
    for h in horizons:
        seed_decision(session, model_version=SEL001_MODEL_VERSION, horizon=h,
                      decided_at=datetime(2026, 7, 10, 20, 30, tzinfo=timezone.utc), artefact_sha256=OLD_ARTEFACT)
        bound[h] = seed_bound_decision(session, horizon=h, edge_ids=edge_ids)
        if shadow:
            seed_shadow_month(session, horizon=h, ranked_ids=edge_ids + [flat[0].id, flat[1].id])
        if registration:
            seed_spg_registration(session, horizon=h)
    session.commit()
    return World(edge, flat, bound)


def bar(session, stock, day) -> MarketPrice:
    return session.scalar(select(MarketPrice).where(MarketPrice.stock_id == stock.id,
                                                    MarketPrice.timestamp == session_cutoff(day)))


def table_rows(session, model) -> list[tuple]:
    """Every column of every row, ordered by id: the byte-identity witness for SPG tables."""
    columns = [c for c in model.__table__.columns]
    return [tuple(row) for row in session.execute(select(*columns).order_by(model.id)).all()]
```

- [ ] **Step 2: Write the failing tests**

`tests/test_trade_geometry_runner.py` (spec §21.10, and the held-out lifecycle cases of §21.7 and §21.9):

```python
from datetime import date, timedelta
from decimal import Decimal

import pytest
from sqlalchemy import create_engine, func, select
from sqlalchemy.orm import sessionmaker

from app.db import Base
from app.models import (
    ModelVersion, SelectionGateDecision, SelectionGateTrade, Stock, TradeGeometryDecision,
    TradeGeometryHoldoutUsage, TradeGeometryHoldoutWindow, TradeGeometryResult, TradeGeometryTrade,
)
from app.selection_gate.config import config_snapshot
from app.selection_gate.constants import (
    DISPOSITION_ACCEPTED, REASON_EVALUATION_FAILED, REASON_HOLDOUT_ALREADY_CONSUMED, REASON_INSUFFICIENT_EVIDENCE,
    SEL001_MODEL_VERSION, STAGE_HELD_OUT, STAGE_WALK_FORWARD,
)
from app.settings import settings
from app.trade_geometry import runner
from app.trade_geometry.config import current_tg_config_sha256, grid_sha256
from app.trade_geometry.constants import (
    DECISION_GEOMETRY_PASS, DECISION_NO_GEOMETRY, NOT_IN_DATASET, REASON_HOLDOUT_NOT_YET_AVAILABLE,
    REASON_NO_BOUND_SELECTION_DECISION, REASON_SHADOW_COVERAGE, ROLE_REFERENCE,
)
from app.trade_geometry.records import TradeGeometryImmutableError
from tests._selection_gate_factories import seed_decision
from tests._trade_geometry_factories import (
    NOW, SESSIONS, apply_small_settings, seed_bound_decision, seed_shadow_month, seed_spg_registration,
    seed_universe, seed_world,
)

TG_TABLES = (TradeGeometryDecision, TradeGeometryResult, TradeGeometryTrade, TradeGeometryHoldoutWindow,
             TradeGeometryHoldoutUsage)


@pytest.fixture
def session(monkeypatch):
    apply_small_settings(monkeypatch)
    engine = create_engine("sqlite:///:memory:")
    Base.metadata.create_all(engine)
    with sessionmaker(bind=engine)() as db:
        yield db


def _count(session, model, *where):
    return session.scalar(select(func.count()).select_from(model).where(*where))


def _decisions(session):
    return {d.selector_horizon: d for d in session.scalars(select(TradeGeometryDecision).order_by(TradeGeometryDecision.id))}


def test_a_full_run_writes_one_auditable_decision_per_selector(session):
    world = seed_world(session)
    result = runner.run_trade_geometry(session, now=NOW, code_version="abc123")
    decisions = _decisions(session)
    assert sorted(decisions) == [3, 5]
    for h, d in decisions.items():
        assert (d.model_version, d.tg_rule_version, d.dataset_version, d.base_dataset_version) == (
            SEL001_MODEL_VERSION, "TG-001", "TG-DS-001", "SEL-DS-001")
        assert d.bound_spg_decision_id == world.bound[h].id and d.code_version == "abc123"
        assert d.config_sha256 == current_tg_config_sha256() and d.grid_sha256 == grid_sha256()
        assert d.dataset_sha256 == result["dataset"]["dataset_sha256"]
        assert (d.holdout_month, d.holdout_label) == ("2026-08", f"TG-001:SEL-001:hsel{h}:2026-08")
        assert (d.geometry_key, d.holding_sessions, d.grid_index) == ("H7:Tnone:Snone", 7, 27)
        assert (d.wf_result, d.wf_reasons, d.wf_trades) == ("PASS", [], 30)
        assert d.reality_check_p == Decimal("0") and d.reality_check_geometries == 36
        assert d.wf_tolerance_set == ["H7:Tnone:Snone", "H7:Tnone:S1.0", "H7:Tnone:S2.0"]
        assert (d.decision, d.primary_stage, d.primary_reason) == (
            DECISION_NO_GEOMETRY, STAGE_HELD_OUT, REASON_INSUFFICIENT_EVIDENCE)  # 3 exam sessions < 2H
        assert d.ho_trades == 6 and d.ho_shadow_missing_share == Decimal("0") and d.ho_sessions == 21
        assert d.valid_until.replace(tzinfo=None) == (d.decided_at + timedelta(days=45)).replace(tzinfo=None)
        results = session.scalars(select(TradeGeometryResult).where(TradeGeometryResult.decision_id == d.id)).all()
        assert len(results) == 37 and sum(r.is_reference for r in results) == 1
        assert [r.geometry_key for r in results if r.chosen] == ["H7:Tnone:Snone"]
        assert not any(r.eligible for r in results if r.is_reference)
        trades = _count(session, TradeGeometryTrade, TradeGeometryTrade.decision_id == d.id)
        reference = next(r for r in results if r.is_reference)
        assert trades == d.wf_candidates + d.ho_candidates + reference.candidates
        assert _count(session, TradeGeometryTrade, TradeGeometryTrade.decision_id == d.id,
                      TradeGeometryTrade.geometry_role == ROLE_REFERENCE) == reference.candidates
    assert _count(session, TradeGeometryHoldoutWindow) == 2 and _count(session, TradeGeometryHoldoutUsage) == 2


def test_a_full_run_can_pass_end_to_end(session, monkeypatch):
    """A three-session geometry leaves enough exam sessions for a defined CI: both selectors pass."""
    monkeypatch.setattr(settings, "tg_holding_horizons", (1, 3))
    seed_world(session)
    runner.run_trade_geometry(session, now=NOW)
    for d in _decisions(session).values():
        assert (d.decision, d.geometry_key, d.ho_result) == (DECISION_GEOMETRY_PASS, "H3:Tnone:Snone", "PASS")
        assert d.ho_trades >= settings.tg_min_holdout_trades and d.ho_ci_low > 0 and d.primary_reason is None


def test_the_decision_carries_its_freeze_records_geometry_and_hashes(session):
    seed_world(session)
    runner.run_trade_geometry(session, now=NOW)
    for d in _decisions(session).values():
        usage = session.get(TradeGeometryHoldoutUsage, d.holdout_usage_id)
        assert usage.holdout_label == d.holdout_label and usage.window_id == d.holdout_window_id
        for name in ("bound_spg_decision_id", "geometry_key", "holding_sessions", "target_atr_multiple",
                     "stop_atr_multiple", "grid_sha256", "config_sha256", "dataset_sha256", "wf_selection_sha256"):
            assert getattr(usage, name) == getattr(d, name), name


def test_a_mismatched_freeze_record_is_refused_and_recorded_as_a_failure(session, monkeypatch):
    seed_world(session)
    real = runner._decision_values

    def tampered(*args, **kwargs):
        values = real(*args, **kwargs)
        if values.get("holdout_usage_id"):
            values["geometry_key"] = "H5:Tnone:Snone"
        return values

    monkeypatch.setattr(runner, "_decision_values", tampered)
    runner.run_trade_geometry(session, now=NOW)
    assert {d.primary_reason for d in _decisions(session).values()} == {REASON_EVALUATION_FAILED}


@pytest.mark.parametrize("case", ["none", "evaluation_failed", "top_k_mismatch"])
def test_no_bound_selection_decision(session, case):
    edge, _ = seed_universe(session)
    if case == "evaluation_failed":
        seed_decision(session, model_version=SEL001_MODEL_VERSION, horizon=3, decision="NO_EDGE",
                      primary_reason=REASON_EVALUATION_FAILED)  # an SPG failure row has no trades
    if case == "top_k_mismatch":
        snapshot = {**config_snapshot(), "selection_top_k": 10}
        seed_bound_decision(session, horizon=3, edge_ids=[s.id for s in edge], snapshot=snapshot)
    session.commit()
    runner.run_trade_geometry(session, now=NOW)
    d = _decisions(session)[3]
    assert (d.decision, d.primary_stage, d.primary_reason) == (DECISION_NO_GEOMETRY, None,
                                                               REASON_NO_BOUND_SELECTION_DECISION)
    assert _count(session, TradeGeometryResult, TradeGeometryResult.decision_id == d.id) == 0
    assert _count(session, TradeGeometryHoldoutUsage) == 0


def test_walk_forward_only_writes_no_tg_row_and_reads_no_registry_or_shadow(session):
    from sqlalchemy import event

    seed_world(session)
    statements = []
    event.listen(session.get_bind(), "before_cursor_execute",
                 lambda conn, cursor, statement, *args: statements.append(statement))
    result = runner.run_trade_geometry(session, walk_forward_only=True, now=NOW)
    ran = list(statements)
    assert not [s for s in ran if "selection_shadow_scores" in s or "holdout_window_registry" in s or "tg_" in s]
    assert all(_count(session, model) == 0 for model in TG_TABLES)
    assert [s["chosen"] for s in result["selectors"]] == ["H7:Tnone:Snone", "H7:Tnone:Snone"]
    assert all(len(s["geometries"]) == 37 for s in result["selectors"])


def test_walk_forward_numbers_equal_a_full_runs(session):
    seed_world(session)
    report = runner.run_trade_geometry(session, walk_forward_only=True, now=NOW)
    full = runner.run_trade_geometry(session, now=NOW)
    for wf_only, decided in zip(report["selectors"], full["selectors"]):
        assert wf_only["geometries"] == decided["geometries"]
        assert wf_only["reality_check_p"] == decided["reality_check_p"]


def test_every_tg_row_is_append_only(session):
    seed_world(session)
    runner.run_trade_geometry(session, now=NOW)
    edits = {TradeGeometryDecision: "primary_reason", TradeGeometryResult: "result", TradeGeometryTrade: "disposition",
             TradeGeometryHoldoutWindow: "registry_version", TradeGeometryHoldoutUsage: "geometry_key"}
    for model, column in edits.items():
        row = session.scalars(select(model).limit(1)).one()
        setattr(row, column, "EDITED")
        with pytest.raises(TradeGeometryImmutableError):
            session.flush()
        session.rollback()
        with pytest.raises(TradeGeometryImmutableError):
            session.delete(session.scalars(select(model).limit(1)).one())
            session.flush()
        session.rollback()


def test_a_second_run_on_the_same_month_finds_the_exam_consumed_and_never_falls_back(session):
    seed_world(session)
    runner.run_trade_geometry(session, now=NOW)
    runner.run_trade_geometry(session, now=NOW + timedelta(hours=1))
    latest = session.scalars(select(TradeGeometryDecision).order_by(TradeGeometryDecision.id.desc()).limit(2)).all()
    for d in latest:
        assert (d.holdout_month, d.primary_reason, d.ho_reasons) == (
            "2026-08", REASON_HOLDOUT_ALREADY_CONSUMED, [REASON_HOLDOUT_ALREADY_CONSUMED])
        assert d.holdout_usage_id is None and d.ho_trades is None
    assert _count(session, TradeGeometryHoldoutUsage) == 2 and _count(session, TradeGeometryHoldoutWindow) == 2


def test_a_concurrent_freeze_records_already_consumed(session, monkeypatch):
    seed_world(session)
    monkeypatch.setattr(runner, "already_consumed", lambda *a, **k: False)
    runner.run_trade_geometry(session, now=NOW)
    runner.run_trade_geometry(session, now=NOW + timedelta(hours=1))  # passes check 2, loses the unique insert
    latest = session.scalars(select(TradeGeometryDecision).order_by(TradeGeometryDecision.id.desc()).limit(2)).all()
    assert {d.primary_reason for d in latest} == {REASON_HOLDOUT_ALREADY_CONSUMED}
    assert _count(session, TradeGeometryHoldoutUsage) == 2


@pytest.mark.parametrize("registration", [None, {"month": "2026-07"}, {"registered_at": NOW + timedelta(hours=1)}])
def test_without_spgs_registration_the_exam_is_not_yet_available_and_not_spent(session, registration):
    seed_world(session, registration=False)
    if registration is not None:
        for h in (3, 5):
            seed_spg_registration(session, horizon=h, **registration)
        session.commit()
    runner.run_trade_geometry(session, now=NOW)
    for d in _decisions(session).values():
        assert (d.primary_stage, d.primary_reason) == (STAGE_HELD_OUT, REASON_HOLDOUT_NOT_YET_AVAILABLE)
        assert d.geometry_key == "H7:Tnone:Snone" and d.holdout_usage_id is None
    assert _count(session, TradeGeometryHoldoutUsage) == 0


def test_another_spg_models_registration_at_the_selector_horizon_counts(session):
    seed_world(session, registration=False)
    for h in (3, 5):
        seed_spg_registration(session, horizon=h, model_version="BASELINE-001")
    session.commit()
    runner.run_trade_geometry(session, now=NOW)
    assert _count(session, TradeGeometryHoldoutUsage) == 2


def test_missing_shadow_coverage_fails_closed_without_spending_the_month(session):
    seed_world(session, shadow=False)
    edge = session.scalars(select(Stock).where(Stock.symbol.like("EDGE%")).order_by(Stock.id)).all()
    august = [d for d in SESSIONS if (d.year, d.month) == (2026, 8)]
    for h in (3, 5):
        seed_shadow_month(session, horizon=h, ranked_ids=[s.id for s in edge], skip=set(august[:3]))  # 3/21 > 10%
    session.commit()
    runner.run_trade_geometry(session, now=NOW)
    for d in _decisions(session).values():
        assert d.primary_reason == REASON_SHADOW_COVERAGE and d.holdout_usage_id is None
        assert d.ho_shadow_sessions_covered == 18 and d.ho_shadow_missing_share == Decimal(str(round(3 / 21, 8)))
    assert _count(session, TradeGeometryHoldoutUsage) == 0


def test_walk_forward_rows_inside_the_protected_span_are_excluded_for_every_horizon(session):
    seed_world(session)
    runner.run_trade_geometry(session, now=NOW)
    last_kept = SESSIONS[149]  # P_TG = (157, 184); any D >= 150 has [D, D+7] touching it
    for d in _decisions(session).values():
        assert d.wf_protected_excluded == 56 and d.wf_sessions == 103 and d.wf_last_session == last_kept
        latest_wf = session.scalar(select(func.max(TradeGeometryTrade.session_date)).where(
            TradeGeometryTrade.decision_id == d.id, TradeGeometryTrade.stage == STAGE_WALK_FORWARD))
        assert latest_wf == last_kept


def test_a_candidate_outside_the_dataset_is_counted_unresolved_and_stored(session):
    world = seed_world(session)
    ghost = Stock(symbol="GHOST", sector="TECH")
    session.add(ghost)
    session.flush()
    for day in (SESSIONS[60], date(2026, 3, 28)):  # a stock with no bars, and a Saturday that is not in S
        session.add(SelectionGateTrade(
            decision_id=world.bound[3].id, stage=STAGE_WALK_FORWARD, session_date=day, stock_id=ghost.id,
            horizon_sessions=3, rank=1, score=Decimal("3"), label_status="RESOLVED", cost=Decimal("0.003"),
            disposition=DISPOSITION_ACCEPTED))
    session.commit()
    runner.run_trade_geometry(session, now=NOW)
    d = _decisions(session)[3]
    results = session.scalars(select(TradeGeometryResult).where(TradeGeometryResult.decision_id == d.id)).all()
    assert all(r.unresolved_by_status.get(NOT_IN_DATASET) == 2 for r in results)
    stored = session.scalars(select(TradeGeometryTrade).where(TradeGeometryTrade.stock_id == ghost.id)).all()
    assert stored and {t.path_status for t in stored} == {NOT_IN_DATASET}
    assert {t.entry_session for t in stored if t.session_date.weekday() == 5} == {None}


def test_a_dataset_failure_records_evaluation_failed_for_both_selectors_and_raises(session, monkeypatch):
    seed_world(session)
    monkeypatch.setattr(runner, "build_selection_dataset", lambda *a, **k: (_ for _ in ()).throw(RuntimeError("boom")))
    with pytest.raises(RuntimeError):
        runner.run_trade_geometry(session, now=NOW)
    for d in _decisions(session).values():
        assert (d.decision, d.primary_reason, d.primary_stage) == (DECISION_NO_GEOMETRY, REASON_EVALUATION_FAILED, None)
        assert d.valid_until.replace(tzinfo=None) == d.decided_at.replace(tzinfo=None)


def test_one_selectors_failure_is_isolated(session, monkeypatch):
    seed_world(session)
    real = runner.evaluate_geometry

    def flaky(**kwargs):
        if kwargs["candidates"] and kwargs["candidates"][0].source_row_id in failing:
            raise RuntimeError("selector 5 broke")
        return real(**kwargs)

    failing = {r.id for r in session.scalars(select(SelectionGateTrade).join(
        SelectionGateDecision, SelectionGateDecision.id == SelectionGateTrade.decision_id).where(
        SelectionGateDecision.horizon_sessions == 5))}
    monkeypatch.setattr(runner, "evaluate_geometry", flaky)
    runner.run_trade_geometry(session, now=NOW)
    decisions = _decisions(session)
    assert decisions[5].primary_reason == REASON_EVALUATION_FAILED and decisions[5].holdout_usage_id is None
    assert decisions[3].geometry_key == "H7:Tnone:Snone" and decisions[3].holdout_usage_id is not None


def test_a_pass_two_failure_keeps_the_month_spent(session, monkeypatch):
    seed_world(session)
    real = runner.build_paths

    def no_second_pass(*args, window=None, **kwargs):
        if window is not None:
            raise RuntimeError("killed in pass 2")
        return real(*args, window=window, **kwargs)

    monkeypatch.setattr(runner, "build_paths", no_second_pass)
    runner.run_trade_geometry(session, now=NOW)
    assert {d.primary_reason for d in _decisions(session).values()} == {REASON_EVALUATION_FAILED}
    assert _count(session, TradeGeometryHoldoutUsage) == 2
    monkeypatch.setattr(runner, "build_paths", real)
    runner.run_trade_geometry(session, now=NOW + timedelta(hours=1))
    assert {d.primary_reason for d in _decisions(session).values()} == {REASON_HOLDOUT_ALREADY_CONSUMED}


def test_trade_geometry_writes_no_model_version(session):
    seed_world(session)
    runner.run_trade_geometry(session, now=NOW)
    assert _count(session, ModelVersion) == 0


def test_render_report_prints_every_geometry_row_with_reasons_tolerance_and_p(session):
    from app.trade_geometry.report import render_report

    seed_world(session)
    text, document = render_report(runner.run_trade_geometry(session, now=NOW))
    assert text.count("REF:H3:T0.05:S0.03") == 2 and text.count("H1:Tnone:Snone |") == 2
    assert "tolerance set: H7:Tnone:Snone, H7:Tnone:S1.0, H7:Tnone:S2.0" in text
    assert "reality_check p=0.000000" in text and "H7:Tnone:Snone*~" in text
    assert "NO_GEOMETRY — INSUFFICIENT_OUT_OF_SAMPLE_EVIDENCE" in text and '"selectors"' in document
```

`tests/test_trade_geometry_parity.py` (spec §21.3). It sets no exam month, so `T_σ` equals SPG's walk-forward sessions, and puts a 2-for-1 split inside the walk-forward period:

```python
from decimal import Decimal

import pytest
from sqlalchemy import create_engine, select
from sqlalchemy.orm import sessionmaker

from app.db import Base
from app.models import CorporateAction, MarketPrice
from app.trade_geometry import runner
from app.trade_geometry.constants import PATH_BAR_MISSING, TG_PARITY_TOLERANCE
from app.walk_forward_dataset import session_cutoff
from tests._selection_gate_factories import AT
from tests._trade_geometry_factories import NOW, SESSIONS, apply_small_settings, bar, seed_bound_decision, seed_universe

SPLIT = 100


@pytest.fixture
def session(monkeypatch):
    apply_small_settings(monkeypatch, tg_holdout_not_before="2099-01")  # no exam month: T_sigma is every bound session
    engine = create_engine("sqlite:///:memory:")
    Base.metadata.create_all(engine)
    with sessionmaker(bind=engine)() as db:
        edge, _ = seed_universe(db)
        split_from = session_cutoff(SESSIONS[SPLIT]).replace(tzinfo=None)
        for row in db.scalars(select(MarketPrice).where(MarketPrice.stock_id == edge[1].id)):
            if row.timestamp.replace(tzinfo=None) >= split_from:  # 2-for-1 on SESSIONS[100]: raw prices halve
                row.open, row.high, row.low, row.close = (v / 2 for v in (row.open, row.high, row.low, row.close))
                row.volume *= 2
        db.add(CorporateAction(stock_id=edge[1].id, action_type="SPLIT", effective_date=SESSIONS[SPLIT],
                               ratio=Decimal("2"), source="test", action_version="CPA-001", recorded_at=AT))
        db.flush()
        for h in (3, 5):
            seed_bound_decision(db, horizon=h, edge_ids=[s.id for s in edge])
        db.commit()
        db.info["edge"] = edge
        yield db


def test_the_time_exit_reproduces_spgs_label_benchmark_and_mean_excess(session):
    result = runner.run_trade_geometry(session, walk_forward_only=True, now=NOW)
    for selector in result["selectors"]:
        parity = selector["parity"]
        assert parity["benchmark_sessions_compared"] == 131 and parity["benchmark_sessions_differing"] == 0
        assert parity["tg_only_trades"] == parity["spg_only_trades"] == 0 and parity["common_trades"] > 0
        assert parity["tg_mean_excess"] == pytest.approx(parity["spg_accepted_mean_excess"], abs=TG_PARITY_TOLERANCE)
        assert parity["tg_mean_excess"] == pytest.approx(parity["spg_wf_mean_excess"], abs=TG_PARITY_TOLERANCE)
        assert parity["status_mismatches"] == {}


def test_a_missing_intermediate_bar_appears_as_a_status_mismatch(session):
    edge = session.info["edge"]
    session.delete(bar(session, edge[0], SESSIONS[80]))  # SPG's stored labels were resolved before this
    session.commit()
    result = runner.run_trade_geometry(session, walk_forward_only=True, now=NOW)
    for selector in result["selectors"]:
        assert selector["parity"]["status_mismatches"].get(PATH_BAR_MISSING, 0) >= 1
```

- [ ] **Step 3: Run; expect FAIL**

Run: `DATABASE_URL="sqlite:///$SCRATCH/tg-10.db" python -m pytest tests/test_trade_geometry_runner.py tests/test_trade_geometry_parity.py -q -p no:cacheprovider`
Expected: `ModuleNotFoundError: No module named 'app.trade_geometry.runner'`.

- [ ] **Step 4: Implement**

`app/trade_geometry/records.py` (replaces the Task 2 file; the listeners are unchanged):

```python
"""TG-001 §16: the append-only TG store and its writers."""
from __future__ import annotations

from datetime import date
from decimal import Decimal
from typing import Sequence

from sqlalchemy import event

from app.models import (
    TradeGeometryDecision, TradeGeometryHoldoutUsage, TradeGeometryHoldoutWindow, TradeGeometryResult,
    TradeGeometryTrade,
)
from app.selection_gate.constants import RESULT_FAIL, RESULT_PASS

from .choice import Choice
from .constants import OUTCOME_STOP, OUTCOME_TARGET
from .evaluation import GeometryRun

APPEND_ONLY_MODELS = (
    TradeGeometryDecision, TradeGeometryResult, TradeGeometryTrade, TradeGeometryHoldoutWindow,
    TradeGeometryHoldoutUsage,
)
FROZEN_FIELDS = ("bound_spg_decision_id", "geometry_key", "holding_sessions", "target_atr_multiple",
                 "stop_atr_multiple", "grid_sha256", "config_sha256", "dataset_sha256", "wf_selection_sha256")


class TradeGeometryImmutableError(RuntimeError):
    pass


class FreezeMismatchError(RuntimeError):
    pass


def _reject_change(mapper, connection, target):
    raise TradeGeometryImmutableError(f"{target.__tablename__} rows are append-only (TG-001)")


for _model in APPEND_ONLY_MODELS:
    event.listen(_model, "before_update", _reject_change)
    event.listen(_model, "before_delete", _reject_change)


def decimal(value, places: int = 8) -> Decimal | None:
    return None if value is None else Decimal(str(round(float(value), places)))


def multiple(value: float | None) -> Decimal | None:
    return None if value is None else Decimal(str(value))


def run_fields(run: GeometryRun) -> dict:
    """The §16.1/§16.2 per-stage reduction, returns and geometry fields, without prefix."""
    s, m = run.statistics, run.metrics
    return {
        **run.reduction, "trades": s.trades, "min_trades": s.min_trades,
        "mean_gross": decimal(s.mean_gross), "mean_benchmark": decimal(s.mean_benchmark), "cost": decimal(s.cost),
        "mean_net": decimal(s.mean_net), "mean_excess": decimal(s.mean_excess), "ci_low": decimal(s.ci_low),
        "ci_high": decimal(s.ci_high), "bootstrap_sessions": s.bootstrap_sessions,
        "mean_excess_vs_hold": decimal(m["mean_excess_vs_hold"]), "target_rate": decimal(m["target_rate"]),
        "stop_rate": decimal(m["stop_rate"]), "expired_rate": decimal(m["expired_rate"]),
        "ambiguous_rate": decimal(m["ambiguous_rate"]), "mean_mfe": decimal(m["mean_mfe"]),
        "mean_mae": decimal(m["mean_mae"]), "mean_mdd": decimal(m["mean_mdd"]),
        "mean_time_to_target": decimal(m["mean_time_to_target"], 4),
        "mean_time_to_stop": decimal(m["mean_time_to_stop"], 4), "turnover": decimal(m["turnover"]),
        "cost_drag": decimal(m["cost_drag"]),
    }


def fold_fields(run: GeometryRun) -> dict:
    folds = run.folds
    return {"folds_tested": folds.folds_tested, "fold_positive_share": decimal(folds.fold_positive_share),
            "median_fold_excess": decimal(folds.median_fold_excess), "folds": list(folds.folds)}


def write_decision(session, values: dict, *, usage: TradeGeometryHoldoutUsage | None) -> TradeGeometryDecision:
    """§13.4: a decision that examined a month must carry exactly its freeze record's geometry and hashes."""
    if usage is not None:
        differing = [name for name in FROZEN_FIELDS if values.get(name) != getattr(usage, name)]
        if differing or values.get("holdout_usage_id") != usage.id:
            raise FreezeMismatchError(f"TG-001 decision differs from its freeze record: {differing}")
    row = TradeGeometryDecision(**values)
    session.add(row)
    session.flush()
    return row


def write_results(session, decision: TradeGeometryDecision, runs: Sequence[GeometryRun], choice: Choice) -> int:
    rows = []
    for run in runs:
        g = run.geometry
        rows.append(TradeGeometryResult(
            decision_id=decision.id, geometry_key=g.key, grid_index=g.grid_index, is_reference=g.is_reference,
            holding_sessions=g.holding_sessions, target_atr_multiple=multiple(g.target_atr_multiple),
            stop_atr_multiple=multiple(g.stop_atr_multiple), target_pct=decimal(g.target_pct),
            stop_pct=decimal(g.stop_pct), unresolved_share=decimal(run.metrics["unresolved_share"]),
            benchmarkable_sessions=run.benchmarkable_sessions, result=RESULT_FAIL if run.reasons else RESULT_PASS,
            reasons=list(run.reasons), eligible=g.key in choice.eligible,
            in_tolerance_set=g.key in choice.tolerance_set, chosen=g.key == choice.chosen,
            **run_fields(run), **fold_fields(run),
        ))
    session.add_all(rows)
    session.flush()
    return len(rows)


def write_trades(session, decision: TradeGeometryDecision, groups: Sequence[tuple[str, GeometryRun, str]], *,
                 selector_horizon: int, cost: float, dates: Sequence[date]) -> int:
    """`groups` holds (geometry_role, run, source) for the chosen WF and HO runs and the reference's WF run."""
    rows = []
    for role, run, source in groups:
        for t in run.trades:
            c, i = t.candidate, t.session_index
            net = None if t.gross_return is None else t.gross_return - cost
            excess = None if net is None or t.benchmark_return is None else net - t.benchmark_return
            rows.append(TradeGeometryTrade(
                decision_id=decision.id, geometry_key=run.geometry.key, geometry_role=role, stage=run.stage,
                source=source, source_row_id=c.source_row_id, artefact_sha256=c.artefact_sha256,
                session_date=c.session_date, stock_id=c.stock_id, selector_horizon=selector_horizon,
                holding_sessions=run.geometry.holding_sessions, rank=c.rank, score=decimal(c.score, 10),
                entry_session=dates[i + 1] if i is not None and i + 1 < len(dates) else None,
                exit_session=None if t.exit_index is None else dates[t.exit_index],
                entry_open=decimal(t.entry_open, 6), atr_percent=decimal(t.atr), target_price=decimal(t.target_price, 6),
                stop_price=decimal(t.stop_price, 6), exit_price_adjusted=decimal(t.exit_price, 6),
                path_status=t.path_status, outcome=t.outcome, ambiguous=t.ambiguous,
                time_to_target=t.exit_offset if t.outcome == OUTCOME_TARGET else None,
                time_to_stop=t.exit_offset if t.outcome == OUTCOME_STOP else None,
                mfe=decimal(t.mfe), mae=decimal(t.mae), gross_return=decimal(t.gross_return),
                benchmark_return=decimal(t.benchmark_return), hold_benchmark_return=decimal(t.hold_benchmark_return),
                cost=decimal(cost), net_return=decimal(net), excess_return=decimal(excess), disposition=t.disposition,
            ))
    session.add_all(rows)
    session.flush()
    return len(rows)
```

`app/trade_geometry/parity.py`:

```python
"""TG-001 §10.2: the report-only time-exit parity block. It gates nothing."""
from __future__ import annotations

import math
from collections import Counter
from typing import Sequence

import numpy as np

from app.selection_gate.constants import DISPOSITION_ACCEPTED, LABEL_EXIT_BAR_MISSING
from app.selection_gate.dataset import SelectionDataset

from .constants import PATH_BAR_MISSING, TG_PARITY_TOLERANCE
from .evaluation import Benchmarks, GeometryRun
from .inputs import BoundDecision, SelectionRow

_SAME_STATUS = {LABEL_EXIT_BAR_MISSING: PATH_BAR_MISSING}  # SPG's exit-bar status is TG's missing path bar


def parity_block(*, dataset: SelectionDataset, selector_horizon: int, time_exit: GeometryRun,
                 benchmarks: Benchmarks, bound: BoundDecision, candidates: Sequence[SelectionRow],
                 sessions: Sequence[int]) -> dict:
    """(h_sel, None, None) over T_sigma against SPG's benchmark, accepted set and walk-forward mean excess."""
    in_sessions = set(sessions)
    dates = dataset.session_dates
    spg = dataset.benchmarks[selector_horizon].set_index("session_index")
    compared = differing = 0
    for index in sorted(in_sessions):
        if index not in spg.index or not benchmarks.benchmarkable(selector_horizon, index):
            continue
        theirs = float(spg.at[index, "benchmark_return"])
        if math.isnan(theirs):
            continue
        compared += 1
        differing += abs(benchmarks.value(time_exit.geometry, index) - theirs) > TG_PARITY_TOLERANCE

    session_dates = {dates[i] for i in in_sessions}
    tg = {(t.candidate.session_date, t.candidate.stock_id) for t in time_exit.trades
          if t.disposition == DISPOSITION_ACCEPTED}
    theirs = {(r.session_date, r.stock_id) for r in candidates
              if r.disposition == DISPOSITION_ACCEPTED and r.session_date in session_dates}
    statuses = {(t.candidate.session_date, t.candidate.stock_id): t.path_status for t in time_exit.trades}
    mismatches = Counter(
        statuses[(r.session_date, r.stock_id)] for r in candidates
        if (r.session_date, r.stock_id) in statuses
        and _SAME_STATUS.get(r.label_status, r.label_status) != statuses[(r.session_date, r.stock_id)]
    )
    return {
        "selector_horizon": selector_horizon,
        "benchmark_sessions_compared": compared,
        "benchmark_sessions_differing": int(differing),
        "tg_only_trades": len(tg - theirs),
        "spg_only_trades": len(theirs - tg),
        "common_trades": len(tg & theirs),
        "tg_mean_excess": time_exit.statistics.mean_excess,
        "spg_accepted_mean_excess": _mean([r.excess_return for r in candidates if (r.session_date, r.stock_id) in theirs
                                            and r.excess_return is not None]),
        "spg_wf_mean_excess": bound.wf_mean_excess,
        "status_mismatches": dict(sorted(mismatches.items())),
    }


def _mean(values: list[float]) -> float | None:
    return float(np.mean(values)) if values else None
```

`app/trade_geometry/runner.py`:

```python
"""TG-001: one run -- bind, walk-forward grid, reality check, robust choice, freeze, TG exam, one decision per selector."""
from __future__ import annotations

import logging
from dataclasses import dataclass, field
from datetime import datetime, timedelta, timezone
from uuid import uuid4

from sqlalchemy.orm import Session

from app.market_data.quality import NSE_TIMEZONE
from app.selection_gate.code_version import selection_code_version
from app.selection_gate.config import config_sha256
from app.selection_gate.constants import (
    DATASET_VERSION, REASON_EVALUATION_FAILED, REASON_HOLDOUT_ALREADY_CONSUMED, RESULT_FAIL, RESULT_PASS,
    SEL001_MODEL_VERSION, STAGE_HELD_OUT, STAGE_WALK_FORWARD, UNIVERSE_RULE_VERSION,
)
from app.selection_gate.dataset import SelectionDataset, build_selection_dataset
from app.selection_gate.statistics import StageThresholds
from app.selection_gate.validation import month_text
from app.settings import settings

from .choice import (
    Choice, choose_geometry, decide_geometry, family_wise_significant, walk_forward_reasons,
)
from .config import config_snapshot, grid, hold_geometry, path_length, reference_geometry
from .constants import (
    REASON_HOLDOUT_NOT_YET_AVAILABLE, REASON_NO_BOUND_SELECTION_DECISION, REASON_SHADOW_COVERAGE, ROLE_CHOSEN,
    ROLE_REFERENCE, SOURCE_SHADOW, SOURCE_SPG_WALK_FORWARD, TG_DATASET_VERSION, TG_RULE_VERSION,
)
from .evaluation import GeometryRun, evaluate_geometry, fold_months, universe_benchmarks, walk_forward_matrix
from .holdout import (
    Coverage, already_consumed, freeze, register_window, shadow_coverage, spg_exam_available, tg_holdout_label,
)
from .inputs import (
    BoundDecision, SelectionRow, bound_decision, decision_artefacts, shadow_candidates, spg_registrations,
    walk_forward_candidates,
)
from .parity import parity_block
from .paths import PathSet, build_paths, dataset_sha256, selection_sha256
from .reality_check import RealityCheck, reality_check
from .records import decimal, fold_fields, multiple, run_fields, write_decision, write_results, write_trades
from .windows import TgWindows, plan_tg_windows, walk_forward_sessions

logger = logging.getLogger(__name__)


@dataclass
class _Selector:
    horizon: int
    bound: BoundDecision | None = None
    stage_less: str | None = None  # NO_BOUND_SELECTION_DECISION or EVALUATION_FAILED
    wf_candidates: list[SelectionRow] = field(default_factory=list)
    wf_sessions: tuple[int, ...] = ()
    protected_excluded: int = 0
    wf_selection_sha256: str | None = None
    runs: list[GeometryRun] = field(default_factory=list)
    check: RealityCheck | None = None
    choice: Choice | None = None
    chosen: GeometryRun | None = None
    wf_reasons: list[dict] = field(default_factory=list)
    parity: dict | None = None
    window: object | None = None
    ho_reason: str | None = None
    coverage: Coverage | None = None
    ho_candidates: list[SelectionRow] = field(default_factory=list)
    usage: object | None = None
    ho_run: GeometryRun | None = None
    ho_selection_sha256: str | None = None


def _thresholds(stage: str) -> StageThresholds:
    walk_forward = stage == STAGE_WALK_FORWARD
    return StageThresholds(
        min_trades=settings.tg_min_wf_trades if walk_forward else settings.tg_min_holdout_trades,
        min_net_excess=settings.tg_min_net_excess, max_unresolved_share=settings.tg_max_unresolved_share,
        min_folds=settings.tg_min_wf_folds if walk_forward else None, cost=settings.selection_round_trip_cost,
        bootstrap_draws=settings.tg_bootstrap_draws, bootstrap_seed=settings.tg_bootstrap_seed,
    )


def _bind(read_session: Session, s: _Selector, dataset: SelectionDataset, windows: TgWindows) -> None:
    """§6.2 and §13.1: the bound decision, its walk-forward candidates, and T_sigma without P_TG."""
    s.bound = bound_decision(read_session, selector_horizon=s.horizon)
    top_k = settings.selection_top_k
    if s.bound is None or int((s.bound.config_snapshot or {}).get("selection_top_k", -1)) != top_k:
        s.stage_less = REASON_NO_BOUND_SELECTION_DECISION
        return
    rows = walk_forward_candidates(read_session, decision_id=s.bound.id, top_k=top_k)
    if not rows:
        s.stage_less = REASON_NO_BOUND_SELECTION_DECISION
        return
    index_of = {day: i for i, day in enumerate(dataset.session_dates)}
    s.wf_sessions, excluded = walk_forward_sessions(
        [index_of[r.session_date] for r in rows if r.session_date in index_of], max_horizon=path_length(),
        protected=windows.protected)
    blocked = set(excluded)
    s.wf_candidates = [r for r in rows if index_of.get(r.session_date) not in blocked]
    s.protected_excluded = len(rows) - len(s.wf_candidates)
    s.wf_selection_sha256 = selection_sha256(s.wf_candidates)


def _walk_forward(s: _Selector, dataset: SelectionDataset, paths: PathSet, benchmarks) -> None:
    """§13.2-§13.3: 37 geometries on one session set, the reality check over the 36, then the robust choice."""
    dates = dataset.session_dates
    months = fold_months(s.wf_sessions, dates)
    thresholds = _thresholds(STAGE_WALK_FORWARD)
    s.runs = [evaluate_geometry(stage=STAGE_WALK_FORWARD, geometry=g, candidates=s.wf_candidates, paths=paths,
                                benchmarks=benchmarks, dates=dates, stage_sessions=s.wf_sessions,
                                thresholds=thresholds, fold_months=months,
                                min_positive_share=settings.tg_min_fold_positive_share)
              for g in (*grid(), reference_geometry())]
    sums, counts = walk_forward_matrix(s.runs, s.wf_sessions)
    s.check = reality_check(sums, counts, block_length=path_length(), draws=settings.tg_bootstrap_draws,
                            seed=settings.tg_bootstrap_seed)
    significant = family_wise_significant(s.check, alpha=settings.tg_reality_check_alpha)
    scores = [run.score for run in s.runs]
    s.choice = choose_geometry(scores, tolerance=settings.tg_simplicity_tolerance, significant=significant)
    s.wf_reasons = walk_forward_reasons(scores, significant=significant)
    s.chosen = next((run for run in s.runs if run.geometry.key == s.choice.chosen), None)
    time_exit = next((run for run in s.runs if run.geometry.key == hold_geometry(s.horizon).key), None)
    if time_exit is not None:
        s.parity = parity_block(dataset=dataset, selector_horizon=s.horizon, time_exit=time_exit,
                                benchmarks=benchmarks, bound=s.bound, candidates=s.wf_candidates,
                                sessions=s.wf_sessions)


def _check_and_freeze(session: Session, read_session: Session, s: _Selector, dataset: SelectionDataset,
                      windows: TgWindows, *, now: datetime, snapshot: dict, snapshot_sha: str, data_sha: str) -> None:
    """§13.5 checks 1-3 (never consume), then the freeze (§13.4) that spends the month."""
    month = windows.holdout_month
    if month is None or not spg_exam_available(spg_registrations(read_session, selector_horizon=s.horizon),
                                               month=month, now=now):
        s.ho_reason = REASON_HOLDOUT_NOT_YET_AVAILABLE
        return
    if already_consumed(session, label=tg_holdout_label(s.horizon, month)):
        s.ho_reason = REASON_HOLDOUT_ALREADY_CONSUMED
        return
    days = [dataset.session_dates[i] for i in windows.holdout_indices]
    rows = shadow_candidates(read_session, selector_horizon=s.horizon, first=days[0], last=days[-1],
                             top_k=settings.selection_top_k)
    s.coverage = shadow_coverage(rows, sessions=days,
                                 artefacts=decision_artefacts(read_session, selector_horizon=s.horizon))
    if s.coverage.missing_share > settings.tg_max_shadow_missing_share:
        s.ho_reason = REASON_SHADOW_COVERAGE
        return
    covered = set(s.coverage.covered)
    s.ho_candidates = [r for r in rows if r.session_date in covered]
    s.usage = freeze(session, window=s.window, selector_horizon=s.horizon, month=month,
                     bound_spg_decision_id=s.bound.id, geometry=s.chosen.geometry,
                     grid_sha256=snapshot["grid_sha256"], config_sha256=snapshot_sha, dataset_sha256=data_sha,
                     wf_selection_sha256=s.wf_selection_sha256, at=now)
    if s.usage is None:
        s.ho_reason = REASON_HOLDOUT_ALREADY_CONSUMED


def _held_out(read_session: Session, frozen: list[_Selector], dataset: SelectionDataset,
              windows: TgWindows) -> str:
    """Pass 2, once, after every freeze of the run: the exam month's paths under each frozen geometry only."""
    first, last = windows.holdout_indices[0], windows.holdout_indices[-1]
    window = (dataset.anchors[first], dataset.anchors[last + path_length()])
    paths = build_paths(read_session, dataset, session_indices=windows.holdout_indices, window=window)
    geometries = [g for s in frozen for g in (s.chosen.geometry, hold_geometry(s.chosen.geometry.holding_sessions))]
    benchmarks = universe_benchmarks(paths, geometries, sessions=len(dataset.session_dates),
                                     min_stocks=settings.selection_min_benchmark_stocks)
    for s in frozen:
        try:
            s.ho_run = evaluate_geometry(
                stage=STAGE_HELD_OUT, geometry=s.chosen.geometry, candidates=s.ho_candidates, paths=paths,
                benchmarks=benchmarks, dates=dataset.session_dates,
                stage_sessions=windows.holdout_indices, thresholds=_thresholds(STAGE_HELD_OUT), fold_months=None,
                min_positive_share=settings.tg_min_fold_positive_share)
            s.ho_selection_sha256 = selection_sha256(s.ho_candidates)
        except Exception:
            logger.exception("TG-001 held-out scoring failed for SEL-001 hsel=%s", s.horizon)
            s.stage_less = REASON_EVALUATION_FAILED
    return dataset_sha256(paths, dataset.session_dates)


def _reasons(s: _Selector) -> list[dict]:
    if s.stage_less:
        return [{"stage": None, "reason": s.stage_less}]
    reasons = list(s.wf_reasons)
    if s.chosen is None:
        return reasons
    if s.ho_reason:
        return reasons + [{"stage": STAGE_HELD_OUT, "reason": s.ho_reason}]
    return reasons + [{"stage": STAGE_HELD_OUT, "reason": r} for r in s.ho_run.reasons]


def _prefixed(prefix: str, fields: dict) -> dict:
    return {f"{prefix}_{name}": value for name, value in fields.items()}


def _decision_values(s: _Selector, dataset: SelectionDataset, windows: TgWindows, *, now: datetime, snapshot: dict,
                     snapshot_sha: str, code_version: str, data_sha: str | None, ho_sha: str | None) -> dict:
    dates, month = dataset.session_dates, windows.holdout_month
    outcome = decide_geometry(_reasons(s))
    chosen = s.chosen.geometry if s.chosen is not None and not s.stage_less else None
    failed = s.stage_less == REASON_EVALUATION_FAILED
    values = dict(
        decision_uuid=str(uuid4()), model_version=SEL001_MODEL_VERSION, selector_horizon=s.horizon,
        tg_rule_version=TG_RULE_VERSION, dataset_version=TG_DATASET_VERSION, base_dataset_version=DATASET_VERSION,
        universe_rule_version=UNIVERSE_RULE_VERSION, bound_spg_decision_id=s.bound.id if s.bound else None,
        grid_sha256=snapshot["grid_sha256"], config_snapshot=snapshot, config_sha256=snapshot_sha,
        code_version=code_version, dataset_sha256=data_sha, ho_dataset_sha256=ho_sha if s.ho_run else None,
        wf_selection_sha256=s.wf_selection_sha256, ho_selection_sha256=s.ho_selection_sha256,
        dataset_first_session=dates[0] if dates else None, dataset_last_session=dates[-1] if dates else None,
        wf_first_session=dates[s.wf_sessions[0]] if s.wf_sessions else None,
        wf_last_session=dates[s.wf_sessions[-1]] if s.wf_sessions else None,
        wf_sessions=len(s.wf_sessions) if s.bound else None, wf_protected_excluded=s.protected_excluded if s.bound else None,
        holdout_month=month_text(month) if month else None,
        holdout_first_session=dates[windows.holdout_indices[0]] if month else None,
        holdout_last_session=dates[windows.holdout_indices[-1]] if month else None,
        holdout_label=s.window.label if s.window else None, holdout_window_id=s.window.id if s.window else None,
        holdout_usage_id=s.usage.id if s.usage else None,
        holding_sessions=chosen.holding_sessions if chosen else None,
        target_atr_multiple=multiple(chosen.target_atr_multiple) if chosen else None,
        stop_atr_multiple=multiple(chosen.stop_atr_multiple) if chosen else None,
        geometry_key=chosen.key if chosen else None, grid_index=chosen.grid_index if chosen else None,
        decision=outcome.decision, primary_stage=outcome.primary_stage, primary_reason=outcome.primary_reason,
        reasons=list(outcome.reasons), parity=s.parity, decided_at=now,
        valid_until=now if failed else now + timedelta(days=settings.tg_decision_validity_days),
    )
    if s.runs and not failed:
        values.update(
            wf_eligible_geometries=len(s.choice.eligible), wf_tolerance_set=list(s.choice.tolerance_set),
            reality_check_p=decimal(s.check.p_value, 6), reality_check_statistic=decimal(s.check.statistic),
            reality_check_geometries=s.check.geometries,
            wf_result=RESULT_PASS if chosen else RESULT_FAIL, wf_reasons=[r["reason"] for r in s.wf_reasons],
        )
        if chosen:
            values.update(_prefixed("wf", {**run_fields(s.chosen), **fold_fields(s.chosen)}))
    if chosen and (s.ho_run or s.ho_reason):
        values.update(ho_result=(RESULT_FAIL if s.ho_run.reasons else RESULT_PASS) if s.ho_run else RESULT_FAIL,
                      ho_reasons=list(s.ho_run.reasons) if s.ho_run else [s.ho_reason],
                      ho_sessions=len(windows.holdout_indices) if month else None)
        if s.coverage is not None:
            values.update(ho_shadow_sessions_covered=len(s.coverage.covered),
                          ho_shadow_missing_share=decimal(s.coverage.missing_share),
                          ho_shadow_artefacts=s.coverage.artefacts)
        if s.ho_run:
            values.update(_prefixed("ho", run_fields(s.ho_run)))
    return values


def _write(session: Session, s: _Selector, dataset: SelectionDataset, windows: TgWindows, **context) -> dict:
    """One transaction: the decision, its 37 geometry rows and its trades (§17 DECIDED)."""
    values = _decision_values(s, dataset, windows, **context)
    usage = s.usage if not s.stage_less else None
    decision = write_decision(session, values, usage=usage)
    if s.runs and not s.stage_less:
        write_results(session, decision, s.runs, s.choice)
        groups = [(ROLE_CHOSEN, s.chosen, SOURCE_SPG_WALK_FORWARD)] if s.chosen else []
        if s.ho_run is not None:
            groups.append((ROLE_CHOSEN, s.ho_run, SOURCE_SHADOW))
        groups.append((ROLE_REFERENCE, next(r for r in s.runs if r.geometry.is_reference), SOURCE_SPG_WALK_FORWARD))
        write_trades(session, decision, groups, selector_horizon=s.horizon, cost=settings.selection_round_trip_cost,
                     dates=dataset.session_dates)
    session.commit()
    return _selector_report(s, decision=decision)


def _record_failure(session: Session, s: _Selector, dataset, windows, **context) -> dict:
    s.stage_less = REASON_EVALUATION_FAILED
    if dataset is None:
        values = dict(
            decision_uuid=str(uuid4()), model_version=SEL001_MODEL_VERSION, selector_horizon=s.horizon,
            tg_rule_version=TG_RULE_VERSION, dataset_version=TG_DATASET_VERSION, base_dataset_version=DATASET_VERSION,
            universe_rule_version=UNIVERSE_RULE_VERSION, bound_spg_decision_id=s.bound.id if s.bound else None,
            grid_sha256=context["snapshot"]["grid_sha256"], config_snapshot=context["snapshot"],
            config_sha256=context["snapshot_sha"], code_version=context["code_version"],
            decision=decide_geometry(_reasons(s)).decision, primary_stage=None, primary_reason=REASON_EVALUATION_FAILED,
            reasons=[{"stage": None, "reason": REASON_EVALUATION_FAILED}], decided_at=context["now"],
            valid_until=context["now"],
        )
    else:
        values = _decision_values(s, dataset, windows, **context)
    decision = write_decision(session, values, usage=None)
    session.commit()
    return _selector_report(s, decision=decision)


def _geometry_report(run: GeometryRun, choice: Choice | None) -> dict:
    s = run.statistics
    return {
        "geometry_key": run.geometry.key, "is_reference": run.geometry.is_reference, "reasons": list(run.reasons),
        "eligible": bool(choice and run.geometry.key in choice.eligible),
        "in_tolerance_set": bool(choice and run.geometry.key in choice.tolerance_set),
        "chosen": bool(choice and run.geometry.key == choice.chosen),
        "trades": s.trades, "unresolved_trades": s.unresolved_trades, "mean_excess": s.mean_excess,
        "ci_low": s.ci_low, "ci_high": s.ci_high, "mean_net": s.mean_net, **run.metrics,
        "folds_tested": run.folds.folds_tested if run.folds else None,
        "fold_positive_share": run.folds.fold_positive_share if run.folds else None,
        "median_fold_excess": run.folds.median_fold_excess if run.folds else None,
        "benchmarkable_sessions": run.benchmarkable_sessions, **run.reduction,
    }


def _selector_report(s: _Selector, *, decision=None) -> dict:
    report = {
        "selector_horizon": s.horizon, "bound_spg_decision_id": s.bound.id if s.bound else None,
        "bound_spg_primary_reason": s.bound.primary_reason if s.bound else None, "stage_less_reason": s.stage_less,
        "wf_sessions": len(s.wf_sessions), "wf_protected_excluded": s.protected_excluded,
        "reality_check_p": s.check.p_value if s.check else None,
        "reality_check_statistic": s.check.statistic if s.check else None,
        "reality_check_geometries": s.check.geometries if s.check else None,
        "tolerance_set": list(s.choice.tolerance_set) if s.choice else [],
        "chosen": s.choice.chosen if s.choice else None, "wf_reasons": s.wf_reasons, "parity": s.parity,
        "geometries": [_geometry_report(run, s.choice) for run in s.runs],
    }
    if decision is not None:
        report["decision"] = {c.name: getattr(decision, c.name) for c in decision.__table__.columns
                              if c.name not in ("config_snapshot", "parity")}
    return report


def _dataset_summary(dataset: SelectionDataset, windows: TgWindows, paths: PathSet | None, data_sha: str | None,
                     ho_sha: str | None) -> dict:
    dates = dataset.session_dates
    return {
        "first_session": dates[0].isoformat(), "last_session": dates[-1].isoformat(), "sessions": len(dates),
        "stocks_by_year": dataset.stocks_by_year, "universe_rows": int(len(dataset.rows)),
        "path_rows": 0 if paths is None else len(paths), "dataset_sha256": data_sha, "ho_dataset_sha256": ho_sha,
        "holdout_month": month_text(windows.holdout_month) if windows.holdout_month else None,
    }


def run_trade_geometry(session: Session, *, read_session: Session | None = None, walk_forward_only: bool = False,
                       now: datetime | None = None, code_version: str | None = None) -> dict:
    read_session = read_session or session
    now = now or datetime.now(timezone.utc)
    snapshot = config_snapshot()
    context = dict(now=now, snapshot=snapshot, snapshot_sha=config_sha256(snapshot),
                   code_version=code_version or selection_code_version())
    selectors = [_Selector(int(h)) for h in settings.tg_selector_horizons]
    dataset = windows = None
    try:
        dataset = build_selection_dataset(read_session, horizons=[s.horizon for s in selectors],
                                          skip_failed_stocks=False)  # fails closed, as SPG does
        windows = plan_tg_windows(dataset.session_dates, today=now.astimezone(NSE_TIMEZONE).date(),
                                  not_before=settings.tg_holdout_not_before, max_horizon=path_length())
        if not walk_forward_only and windows.holdout_month is not None:
            first, last = windows.holdout_indices[0], windows.holdout_indices[-1]
            for s in selectors:
                s.window = register_window(session, selector_horizon=s.horizon, month=windows.holdout_month,
                                           window_start=dataset.anchors[first],
                                           window_end=dataset.anchors[last + path_length()], at=now)
            session.commit()
        for s in selectors:
            _bind(read_session, s, dataset, windows)
        # Pass 1, before any choice: every U(D) row of every walk-forward session of either selector.
        paths = build_paths(read_session, dataset, session_indices={i for s in selectors for i in s.wf_sessions})
        geometries = [*grid(), reference_geometry(), *(hold_geometry(h) for h in settings.tg_holding_horizons)]
        benchmarks = universe_benchmarks(paths, geometries, sessions=len(dataset.session_dates),
                                         min_stocks=settings.selection_min_benchmark_stocks)
        data_sha = dataset_sha256(paths, dataset.session_dates)
    except Exception:
        session.rollback()
        logger.exception("TG-001 run failed before any selector was evaluated")
        if not walk_forward_only:
            for s in selectors:
                _record_failure(session, s, None, None, **context)
        raise
    for s in selectors:
        if s.stage_less:
            continue
        try:
            _walk_forward(s, dataset, paths, benchmarks)
            if not walk_forward_only and s.chosen is not None:
                _check_and_freeze(session, read_session, s, dataset, windows, now=now, snapshot=snapshot,
                                  snapshot_sha=context["snapshot_sha"], data_sha=data_sha)
        except Exception:
            session.rollback()
            logger.exception("TG-001 evaluation failed for SEL-001 hsel=%s", s.horizon)
            s.stage_less = REASON_EVALUATION_FAILED
    if walk_forward_only:
        return {"dataset": _dataset_summary(dataset, windows, paths, data_sha, None),
                "selectors": [_selector_report(s) for s in selectors]}

    frozen = [s for s in selectors if s.usage is not None and not s.stage_less]
    ho_sha = None
    if frozen:
        try:
            ho_sha = _held_out(read_session, frozen, dataset, windows)
        except Exception:
            logger.exception("TG-001 pass 2 failed; every frozen selector's month stays spent")
            for s in frozen:
                s.stage_less = REASON_EVALUATION_FAILED
    reports = []
    for s in selectors:
        try:
            reports.append(_write(session, s, dataset, windows, data_sha=data_sha, ho_sha=ho_sha, **context))
        except Exception:
            session.rollback()
            logger.exception("TG-001 decision write failed for SEL-001 hsel=%s", s.horizon)
            reports.append(_record_failure(session, s, dataset, windows, data_sha=data_sha, ho_sha=ho_sha, **context))
    return {"dataset": _dataset_summary(dataset, windows, paths, data_sha, ho_sha), "selectors": reports}
```

`app/trade_geometry/report.py`:

```python
"""TG-001 §14/§22: every geometry row of every selector, never just the chosen one -- text for a reader, JSON for the record."""
from __future__ import annotations

import json

from .constants import display_reason

_COLUMNS = ("trades", "unresolved_trades", "mean_excess", "ci_low", "ci_high", "mean_net", "mean_excess_vs_hold",
            "target_rate", "stop_rate", "expired_rate", "ambiguous_rate", "folds_tested", "fold_positive_share",
            "median_fold_excess", "turnover")


def _value(value) -> str:
    return "-" if value is None else (f"{value:.6f}" if isinstance(value, float) else str(value))


def render_report(result: dict) -> tuple[str, str]:
    data = result["dataset"]
    lines = [
        "TG-001 trade geometry",
        f"sessions {data['first_session']} .. {data['last_session']} ({data['sessions']}), universe rows "
        f"{data['universe_rows']}, path rows {data['path_rows']}, dataset_sha256={data['dataset_sha256']}",
        "stocks per year: " + ", ".join(f"{year}={count}" for year, count in data["stocks_by_year"].items()),
        f"TG exam month: {data['holdout_month'] or 'none'}",
    ]
    if result.get("metrics"):
        lines.append("run: " + ", ".join(f"{k}={v}" for k, v in result["metrics"].items()))
    for selector in result["selectors"]:
        decision = selector.get("decision") or {}
        outcome = display_reason(decision.get("primary_reason")) or decision.get("decision") or "WALK_FORWARD_ONLY"
        lines.append(f"SEL-001 hsel={selector['selector_horizon']}: {outcome} "
                     f"(bound SPG decision {selector['bound_spg_decision_id']}, SPG primary reason "
                     f"{selector['bound_spg_primary_reason']})")
        if selector["stage_less_reason"]:
            continue
        lines.append(f"  T_sigma sessions={selector['wf_sessions']} protected_excluded={selector['wf_protected_excluded']} "
                     f"reality_check p={_value(selector['reality_check_p'])} V={_value(selector['reality_check_statistic'])} "
                     f"G={selector['reality_check_geometries']}")
        lines.append(f"  tolerance set: {', '.join(selector['tolerance_set']) or '-'}; chosen: {selector['chosen'] or '-'}; "
                     f"walk-forward reasons: {[r['reason'] for r in selector['wf_reasons']]}")
        lines.append("  geometry | " + " | ".join(_COLUMNS) + " | eligible | reasons")
        for row in selector["geometries"]:
            marks = ("*" if row["chosen"] else "") + ("~" if row["in_tolerance_set"] else "")
            lines.append(f"  {row['geometry_key']}{marks} | " + " | ".join(_value(row[c]) for c in _COLUMNS)
                         + f" | {row['eligible']} | {','.join(row['reasons']) or '-'}")
        if selector["parity"]:
            lines.append("  parity: " + ", ".join(f"{k}={v}" for k, v in selector["parity"].items()))
        held = {k[3:]: v for k, v in decision.items() if k.startswith("ho_") and v is not None}
        if held:
            lines.append("  held-out: " + ", ".join(f"{k}={v}" for k, v in sorted(held.items())))
    return "\n".join(lines), json.dumps(result, default=str, indent=2, sort_keys=True)
```

- [ ] **Step 5: Run; expect PASS (26 passed, about 70 s)**, then re-run the Task 2 tests against the replaced `records.py`

Run: `DATABASE_URL="sqlite:///$SCRATCH/tg-10.db" python -m pytest tests/test_trade_geometry_runner.py tests/test_trade_geometry_parity.py tests/test_trade_geometry_schema.py -q -p no:cacheprovider`
Expected: 29 passed.

- [ ] **Step 6: Commit**

```bash
git add app/trade_geometry/records.py app/trade_geometry/parity.py app/trade_geometry/runner.py app/trade_geometry/report.py tests/_trade_geometry_factories.py tests/test_trade_geometry_runner.py tests/test_trade_geometry_parity.py
git commit -m "TG-001: trade geometry runner, freeze-before-read exam, parity block and report" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 11: Independence and publication guards

**Files:**
- Test: `tests/test_trade_geometry_independence.py`, `tests/test_trade_geometry_publication.py`
- No production code. These tests pin spec §21.7 and §21.8 properties that Tasks 7–10 already hold. Step 3 makes each guard fail on purpose to prove it bites.

**Interfaces:**
- Consumes: `run_trade_geometry`, the factories, SPG `run_selection_gate` (walk-forward only, BASELINE-001 h=3, so nothing is fitted), `decide_publication`, `publication_capability` and `gate_pairs`.

- [ ] **Step 1: Write the tests**

`tests/test_trade_geometry_independence.py`:
- Freeze before read: poisoning `P_TG` leaves every walk-forward output and the usage row identical. The poison is an additive distortion; a ×50 scale would be invisible to returns. A second connection sees the usage commit before the pass-2 query.
- No `HELD_OUT` read: seeded `HELD_OUT` rows change nothing. The captured SQL against SPG tables holds no `HELD_OUT` literal or parameter and no `selection_gate_decisions.ho_*`/`holdout_*` column. The capture is scoped to SPG-table statements, because TG legitimately writes `'HELD_OUT'` into `tg_trades.stage`.
- SPG tables are byte-identical after a run.
- No SPG module imports TG or names a `tg_` table.
- SPG decides the same with `tg_*` rows present.
- A fresh interpreter importing TG loads neither xgboost nor `sel001`.

```python
import ast
import os
import re
import subprocess
import sys
from decimal import Decimal
from pathlib import Path

import pytest
from sqlalchemy import create_engine, event, func, select, text
from sqlalchemy.orm import sessionmaker

from app.db import Base
from app.models import (
    HoldoutUsageRecord, HoldoutWindowRegistry, MarketPrice, SelectionBenchmarkSession, SelectionGateDecision,
    SelectionGateTrade, SelectionHoldoutUsage, SelectionShadowScore, TradeGeometryDecision, TradeGeometryHoldoutUsage,
    TradeGeometryResult,
)
from app.selection_gate import runner as spg_runner
from app.selection_gate.constants import STAGE_HELD_OUT
from app.trade_geometry import runner
from app.walk_forward_dataset import session_cutoff
from tests._trade_geometry_factories import NOW, SESSIONS, apply_small_settings, seed_world, table_rows

SPG_PACKAGE = Path(spg_runner.__file__).resolve().parent
REPO_ROOT = SPG_PACKAGE.parents[1]
SPG_TABLES = (SelectionGateDecision, SelectionGateTrade, SelectionBenchmarkSession, SelectionShadowScore,
              SelectionHoldoutUsage, HoldoutWindowRegistry, HoldoutUsageRecord)
TG_TABLE_NAMES = ("tg_decisions", "tg_geometry_results", "tg_trades", "tg_holdout_windows", "tg_holdout_usages")


@pytest.fixture
def make_session(monkeypatch, tmp_path):
    apply_small_settings(monkeypatch)
    opened = []

    def make(name="tg"):
        engine = create_engine(f"sqlite:///{(tmp_path / f'{name}.db').as_posix()}")
        Base.metadata.create_all(engine)
        db = sessionmaker(bind=engine)()
        opened.append(db)
        return db

    yield make
    for db in opened:
        db.close()


def _results(session):
    skip = {"id", "created_at"}
    return [{c: getattr(r, c) for c in r.__table__.columns.keys() if c not in skip}
            for r in session.scalars(select(TradeGeometryResult).order_by(TradeGeometryResult.id))]


def _usages(session):
    return [{c: getattr(u, c) for c in u.__table__.columns.keys()}
            for u in session.scalars(select(TradeGeometryHoldoutUsage).order_by(TradeGeometryHoldoutUsage.id))]


def test_poisoning_the_protected_span_changes_no_walk_forward_output(make_session):
    clean, poisoned = make_session("clean"), make_session("poisoned")
    for db in (clean, poisoned):
        seed_world(db)
    protected = {session_cutoff(day).replace(tzinfo=None) for day in SESSIONS[157:185]}  # P_TG = (157, 184)
    for bar in poisoned.scalars(select(MarketPrice)):
        if bar.timestamp.replace(tzinfo=None) in protected:  # extreme but arithmetically valid bars
            bar.high, bar.close = bar.open * 2, bar.open * Decimal("1.8")
    poisoned.commit()
    runner.run_trade_geometry(clean, now=NOW)
    runner.run_trade_geometry(poisoned, now=NOW)
    assert _results(clean) == _results(poisoned) and _usages(clean) == _usages(poisoned)
    exam = select(TradeGeometryDecision.ho_mean_gross).order_by(TradeGeometryDecision.id)
    assert clean.scalars(exam).all() != poisoned.scalars(exam).all()  # the poison did reach the exam


def test_the_freeze_is_committed_before_the_pass_two_query(make_session, monkeypatch):
    db = make_session()
    seed_world(db)
    observer = sessionmaker(bind=create_engine(db.get_bind().url))()  # another connection sees only commits
    seen = []
    real = runner.build_paths

    def watched(*args, window=None, **kwargs):
        if window is not None:
            seen.append(observer.scalar(select(func.count()).select_from(TradeGeometryHoldoutUsage)))
        return real(*args, window=window, **kwargs)

    monkeypatch.setattr(runner, "build_paths", watched)
    runner.run_trade_geometry(db, now=NOW)
    observer.close()
    assert seen == [2]  # pass 2 ran once, after both selectors' freezes were durable


def _held_out_rows(session):
    """HELD_OUT rows that would change every result if TG read them: flat stocks ranked first on WF sessions."""
    flat = session.execute(text("SELECT id FROM stocks WHERE symbol LIKE 'FLAT%' ORDER BY id")).scalars().all()
    for decision in session.scalars(select(SelectionGateDecision).where(SelectionGateDecision.wf_trades.is_not(None))):
        for day in SESSIONS[60:140]:
            for rank, stock_id in enumerate(flat[:2], start=1):
                session.add(SelectionGateTrade(
                    decision_id=decision.id, stage=STAGE_HELD_OUT, session_date=day, stock_id=stock_id,
                    horizon_sessions=decision.horizon_sessions, rank=rank, score=Decimal("9"), label_status="RESOLVED",
                    cost=Decimal("0.003"), disposition="ACCEPTED"))
    session.commit()


def test_spg_held_out_rows_are_never_read(make_session):
    plain, seeded = make_session("plain"), make_session("seeded")
    for db in (plain, seeded):
        seed_world(db)
    _held_out_rows(seeded)
    captured = []
    event.listen(seeded.get_bind(), "before_cursor_execute",
                 lambda conn, cursor, statement, parameters, context, many: captured.append((statement, parameters)))
    runner.run_trade_geometry(plain, now=NOW)
    runner.run_trade_geometry(seeded, now=NOW)
    assert _results(plain) == _results(seeded)
    spg_reads = [(s, p) for s, p in captured if "selection_gate_" in s]
    assert spg_reads
    for statement, parameters in spg_reads:
        assert "HELD_OUT" not in statement and "HELD_OUT" not in repr(parameters)
        assert not re.search(r"selection_gate_decisions\.(ho_|holdout_)", statement), statement


def test_every_spg_table_is_byte_identical_after_a_full_run(make_session):
    db = make_session()
    seed_world(db)
    before = {model.__tablename__: table_rows(db, model) for model in SPG_TABLES}
    runner.run_trade_geometry(db, now=NOW)
    db.expire_all()
    assert {model.__tablename__: table_rows(db, model) for model in SPG_TABLES} == before


def _spg_sources():
    return sorted(SPG_PACKAGE.rglob("*.py"))


def test_no_spg_module_imports_trade_geometry_or_names_a_tg_table():
    for path in _spg_sources():
        source = path.read_text(encoding="utf-8")
        for node in ast.walk(ast.parse(source)):
            if isinstance(node, ast.ImportFrom):
                assert "trade_geometry" not in (node.module or ""), path
                assert all("trade_geometry" not in alias.name for alias in node.names), path
            if isinstance(node, ast.Import):
                assert all("trade_geometry" not in alias.name for alias in node.names), path
        assert not any(name in source for name in TG_TABLE_NAMES + ("TradeGeometry",)), path


def test_spg_decides_the_same_with_tg_rows_present(make_session):
    db = make_session()
    seed_world(db)
    kwargs = dict(models=("BASELINE-001",), horizons=(3,), walk_forward_only=True, now=NOW)
    before = spg_runner.run_selection_gate(db, **kwargs)
    runner.run_trade_geometry(db, now=NOW)
    assert spg_runner.run_selection_gate(db, **kwargs) == before


def test_trade_geometry_never_loads_xgboost_or_the_sel001_model():
    """TG fits nothing: a fresh interpreter importing the whole package loads neither (lesson from SPG Ruling 18)."""
    probe = ("import sys; import app.trade_geometry.runner, app.trade_geometry.report; "
             "print('xgboost' in sys.modules, 'app.selection_gate.sel001' in sys.modules)")
    result = subprocess.run([sys.executable, "-c", probe], cwd=REPO_ROOT, capture_output=True, text=True,
                            env={**os.environ, "PYTHONDONTWRITEBYTECODE": "1"}, check=True)
    assert result.stdout.strip() == "False False", result.stdout + result.stderr
```

`tests/test_trade_geometry_publication.py`:

```python
from datetime import date, timedelta
from uuid import uuid4

import pytest
from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker

from app.db import Base
from app.models import TradeGeometryDecision
from app.selection_gate import publication
from app.selection_gate.capability import publication_capability
from app.selection_gate.config import gate_pairs
from app.selection_gate.constants import (
    CAPABILITY_SHADOW_ONLY, PUBLICATION_SHADOW, REASON_SHADOW_ONLY_CAPABILITY, SEL001_MODEL_VERSION,
)
from app.trade_geometry.config import config_snapshot, current_tg_config_sha256, grid_sha256
from app.trade_geometry.constants import DECISION_GEOMETRY_PASS
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


def _seed_geometry_pass(session, horizon):
    session.add(TradeGeometryDecision(
        decision_uuid=str(uuid4()), model_version=SEL001_MODEL_VERSION, selector_horizon=horizon,
        tg_rule_version="TG-001", dataset_version="TG-DS-001", base_dataset_version="SEL-DS-001",
        universe_rule_version="SEU-001", grid_sha256=grid_sha256(), config_snapshot=config_snapshot(),
        config_sha256=current_tg_config_sha256(), code_version="test", holding_sessions=5,
        geometry_key="H5:Tnone:Snone", grid_index=18, wf_result="PASS", ho_result="PASS",
        decision=DECISION_GEOMETRY_PASS, reasons=[], decided_at=AT, valid_until=AT + timedelta(days=45)))
    session.flush()


def _outcomes(session, stock):
    return {(model, horizon): publication.decide_publication(
                session, model_version=model, horizon_days=horizon, stock_id=stock.id, as_of_timestamp=AS_OF, at=AT,
                artefact_sha256="a" * 64)
            for model, horizon in gate_pairs()}


def test_a_geometry_pass_changes_no_publication_outcome(session):
    stock = seed_stock_with_bars(session, symbol="AAA", sessions=SESSIONS)
    for model, horizon in gate_pairs():
        seed_decision(session, model_version=model, horizon=horizon)  # a valid SPG PUBLISH for every pair
    before = _outcomes(session, stock)
    for horizon in (3, 5):
        _seed_geometry_pass(session, horizon)
    after = _outcomes(session, stock)
    assert after == before
    for horizon in (3, 5):
        assert (after[(SEL001_MODEL_VERSION, horizon)].state, after[(SEL001_MODEL_VERSION, horizon)].reason) == (
            PUBLICATION_SHADOW, REASON_SHADOW_ONLY_CAPABILITY)
    assert publication_capability(SEL001_MODEL_VERSION) == CAPABILITY_SHADOW_ONLY
```

- [ ] **Step 2: Run; expect PASS (8 passed, about 40 s)**

Run: `DATABASE_URL="sqlite:///$SCRATCH/tg-11.db" python -m pytest tests/test_trade_geometry_independence.py tests/test_trade_geometry_publication.py -q -p no:cacheprovider`

- [ ] **Step 3: Prove two guards bite, reverting each edit immediately**
  - In `app/trade_geometry/inputs.py::walk_forward_candidates`, delete `t.stage == STAGE_WALK_FORWARD, `. Run `-k held_out_rows`; expect FAIL. Then `git checkout app/trade_geometry/inputs.py`.
  - In `app/trade_geometry/holdout.py::freeze`, replace `session.commit()` with `session.flush()`. Run `-k committed_before`; expect FAIL (`seen == [0]`). Then `git checkout app/trade_geometry/holdout.py`.
  - Confirm `git status --short app/` is clean.

- [ ] **Step 4: Commit**

```bash
git add tests/test_trade_geometry_independence.py tests/test_trade_geometry_publication.py
git commit -m "TG-001: one-way dependency, no held-out reads, freeze-before-read and publication guards" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 12: Operations: script, orchestration, CronJob and guard counts

**Files:**
- Create: `scripts/run_trade_geometry.py`, `deploy/k8s/base/trade-geometry-cronjob.yaml`, `tests/test_trade_geometry_operations.py`
- Modify: `app/schedule_orchestration.py` in three places:
  - the constant after line 166
  - `LOCK_LEASE_OVERRIDES` after line 345
  - `TRIGGER_POLICIES`, after the `OPERATION_SELECTION_SHADOW` entry, which ends at line 678
- Modify: `app/operation_recovery.py`, with the import after line 133 and the policy after the `OPERATION_SELECTION_SHADOW` policy, which ends at line 531
- Modify: `deploy/k8s/base/kustomization.yaml`
- Modify (counts only): `tests/test_cronjob_manifests.py`, `tests/test_epic317_drills.py`, `tests/test_api_operations_health.py`, `tests/test_epic317_regressions.py`, `tests/test_operation_verification_s7.py`

**Interfaces:**
- Consumes: `run_trade_geometry`, `render_report`, `measured()`, `selection_code_version()`, and `scripts.run_selection_gate.read_only_sessionmaker(url)`. That last import, required by the spec, also loads SPG's runner and xgboost into this batch process; `app.trade_geometry` itself never does (Task 11).
- Produces: `OPERATION_TRADE_GEOMETRY`, its lease, trigger and recovery policy, the CLI `python -m scripts.run_trade_geometry [--walk-forward-only] [--source-url URL] [--ephemeral-schema] [--code-version SHA] [--report-json PATH]`, and the CronJob `market-agent-trade-geometry`.

Lessons from the SPG-001 run are built in here:
- a TriggerPolicy, a lease at or above `activeDeadlineSeconds`, `NOT_REPEATABLE` recovery, and a TTL of at most 172800, all landing together so every guard stays green inside this one task
- the drill and operation counts bumped
- no xgboost allow-list change, because `app/trade_geometry` imports none
- no `ModelVersion` write (Task 10 test)

- [ ] **Step 1: Write the failing tests**

`tests/test_trade_geometry_operations.py`. The script test runs a subprocess with its own `DATABASE_URL`, and the small settings passed as environment variables, which pydantic-settings reads:

```python
import json
import os
import subprocess
import sys
from datetime import timedelta
from pathlib import Path

from sqlalchemy import create_engine, func, select, text
from sqlalchemy.orm import sessionmaker

from app.db import Base
from app.models import TradeGeometryDecision
from tests._trade_geometry_factories import SMALL, apply_small_settings, seed_world

REPO_ROOT = Path(__file__).resolve().parent.parent


def test_the_script_runs_walk_forward_only_from_a_read_only_source(tmp_path, monkeypatch):
    apply_small_settings(monkeypatch)
    source_url = f"sqlite:///{(tmp_path / 'source.db').as_posix()}"
    engine = create_engine(source_url)
    Base.metadata.create_all(engine)
    with sessionmaker(bind=engine)() as db:
        seed_world(db)
        before = {t: db.execute(text(f"SELECT count(*) FROM {t}")).scalar() for t in ("selection_gate_trades",
                                                                                     "market_prices")}
    eval_url = f"sqlite:///{(tmp_path / 'eval.db').as_posix()}"
    report = tmp_path / "report.json"
    env = {**os.environ, "DATABASE_URL": eval_url, **{name.upper(): str(value) for name, value in SMALL.items()}}
    completed = subprocess.run(
        [sys.executable, "-m", "scripts.run_trade_geometry", "--walk-forward-only", "--ephemeral-schema", "--source-url", source_url, "--code-version", "abc",
         "--report-json", str(report)], cwd=REPO_ROOT, env=env, capture_output=True, text=True, timeout=600)
    assert completed.returncode == 0, completed.stderr[-3000:]
    assert "TG-001 trade geometry" in completed.stdout and "REF:H3:T0.05:S0.03" in completed.stdout
    document = json.loads(report.read_text(encoding="utf-8"))
    assert [s["chosen"] for s in document["selectors"]] == ["H7:Tnone:Snone", "H7:Tnone:Snone"]
    assert {"elapsed_seconds", "cpu_seconds", "peak_memory_mb", "universe_rows", "path_rows", "sessions"} <= set(
        document["metrics"])
    with create_engine(eval_url).connect() as connection:
        execution = connection.execute(text(
            "SELECT operation_name, status FROM orchestration_executions")).one()
        assert tuple(execution) == ("TRADE_GEOMETRY", "COMPLETED")
        assert connection.execute(select(func.count()).select_from(TradeGeometryDecision.__table__)).scalar() == 0
    with create_engine(source_url).connect() as connection:
        assert {t: connection.execute(text(f"SELECT count(*) FROM {t}")).scalar() for t in before} == before


def test_the_lease_trigger_and_recovery_policies_are_registered():
    from app.operation_recovery import NOT_REPEATABLE, RECOVERY_POLICIES
    from app.schedule_orchestration import (
        OPERATION_SELECTION_GATE, OPERATION_TRADE_GEOMETRY, TRIGGER_POLICIES, TRIGGER_SCHEDULED, lock_lease_seconds,
    )

    assert OPERATION_TRADE_GEOMETRY == "TRADE_GEOMETRY" and lock_lease_seconds(OPERATION_TRADE_GEOMETRY) == 21600
    trigger = TRIGGER_POLICIES[OPERATION_TRADE_GEOMETRY]
    assert (trigger.trigger_type, trigger.cadence, trigger.requires_market_session) == (
        TRIGGER_SCHEDULED, timedelta(days=28), False)
    recovery = RECOVERY_POLICIES[OPERATION_TRADE_GEOMETRY]
    assert (recovery.repeatability, recovery.depends_on, recovery.grace) == (
        NOT_REPEATABLE, (OPERATION_SELECTION_GATE,), None)
```

In `tests/test_cronjob_manifests.py::test_no_cronjob_may_outlive_the_lock_its_run_holds`, replace

```python
    from app.schedule_orchestration import (
        LOCK_LEASE_SECONDS, LOCK_LEASE_OVERRIDES, OPERATION_DATABASE_BACKUP,
        OPERATION_SELECTION_GATE, OPERATION_SELECTION_SHADOW,
    )
```

with

```python
    from app.schedule_orchestration import (
        LOCK_LEASE_SECONDS, LOCK_LEASE_OVERRIDES, OPERATION_DATABASE_BACKUP,
        OPERATION_SELECTION_GATE, OPERATION_SELECTION_SHADOW, OPERATION_TRADE_GEOMETRY,
    )
```

and replace

```python
        "selection-shadow-cronjob.yaml": LOCK_LEASE_OVERRIDES[OPERATION_SELECTION_SHADOW],
    }
```

with

```python
        "selection-shadow-cronjob.yaml": LOCK_LEASE_OVERRIDES[OPERATION_SELECTION_SHADOW],
        # TG-001: the monthly trade-geometry run holds its own lease, equal to its deadline.
        "trade-geometry-cronjob.yaml": LOCK_LEASE_OVERRIDES[OPERATION_TRADE_GEOMETRY],
    }
```

Then add, after `test_selection_gate_jobs_are_registered_bounded_and_resourced`:

```python
def test_trade_geometry_job_is_registered_bounded_and_resourced():
    kustomization = yaml.safe_load((MANIFESTS_DIR / "kustomization.yaml").read_text())
    assert "trade-geometry-cronjob.yaml" in kustomization["resources"]
    cronjob = _kind("trade-geometry-cronjob.yaml", "CronJob")
    assert cronjob["metadata"]["name"] == "market-agent-trade-geometry"
    spec = cronjob["spec"]
    assert (spec["schedule"], spec["timeZone"], spec["concurrencyPolicy"], spec["startingDeadlineSeconds"]) == (
        "30 20 11 * *", "Etc/UTC", "Forbid", 3600)
    assert (spec["successfulJobsHistoryLimit"], spec["failedJobsHistoryLimit"]) == (3, 3)
    job = spec["jobTemplate"]["spec"]
    assert (job["backoffLimit"], job["activeDeadlineSeconds"], job["ttlSecondsAfterFinished"]) == (0, 21600, 172800)
    pod = job["template"]["spec"]
    assert [c["name"] for c in pod["initContainers"]] == ["wait-for-db"]
    container = pod["containers"][0]
    assert container["command"] == ["python", "-m", "scripts.run_trade_geometry"]
    assert container["resources"] == {"requests": {"cpu": "1", "memory": "3Gi"}, "limits": {"cpu": "2", "memory": "6Gi"}}
    assert container["env"] == [{"name": "DATABASE_URL", "valueFrom": {"secretKeyRef": {
        "name": "market-agent-secrets", "key": "DATABASE_URL"}}}]
```

- [ ] **Step 2: Run; expect FAIL**

Run: `DATABASE_URL="sqlite:///$SCRATCH/tg-12.db" python -m pytest tests/test_trade_geometry_operations.py tests/test_cronjob_manifests.py -q -p no:cacheprovider`
Expected: `ImportError: cannot import name 'OPERATION_TRADE_GEOMETRY'`, and the script subprocess fails with `No module named scripts.run_trade_geometry`.

- [ ] **Step 3: Register the operation**

In `app/schedule_orchestration.py`, after `OPERATION_SELECTION_SHADOW = "SELECTION_SHADOW"  ...`:

```python
OPERATION_TRADE_GEOMETRY = "TRADE_GEOMETRY"                         # scripts.run_trade_geometry
```

In `LOCK_LEASE_OVERRIDES`, after the `OPERATION_SELECTION_SHADOW: 1800,` line:

```python
    OPERATION_TRADE_GEOMETRY: 21600,  # TG-001: SEL-DS-001 plus 74 geometry evaluations; equals its activeDeadlineSeconds.
```

In `TRIGGER_POLICIES`, after the `OPERATION_SELECTION_SHADOW` entry:

```python
    OPERATION_TRADE_GEOMETRY: TriggerPolicy(
        operation_name=OPERATION_TRADE_GEOMETRY,
        trigger_type=TRIGGER_SCHEDULED,
        cadence=timedelta(days=28),
        requires_market_session=False,
        description=(
            "TG-001: monthly on the 12th at 02:00 IST, the day after the selection gate registers its exam month. "
            "Writes trade-geometry decisions only; no publication outcome reads them in this phase."
        ),
    ),
```

In `app/operation_recovery.py`, add `OPERATION_TRADE_GEOMETRY,` to the `from .schedule_orchestration import (...)` list after `OPERATION_SELECTION_SHADOW,`. Add, after the `OPERATION_SELECTION_SHADOW: RecoveryPolicy(...)` entry:

```python
    OPERATION_TRADE_GEOMETRY: RecoveryPolicy(
        operation_name=OPERATION_TRADE_GEOMETRY,
        repeatability=NOT_REPEATABLE,
        depends_on=(OPERATION_SELECTION_GATE,),
        grace=None,
        rationale=(
            "TG-001. Not the same thing twice: a repeat on the same month finds the TG exam consumed and records "
            "NO_GEOMETRY / HOLDOUT_ALREADY_CONSUMED, which as the newest decision would withdraw a GEOMETRY_PASS. "
            "Ordered after SELECTION_GATE, whose exam registration the held-out guard reads."
        ),
    ),
```

- [ ] **Step 4: Write the script** `scripts/run_trade_geometry.py`

```python
"""TG-001: run trade geometry validation (`OPERATION_TRADE_GEOMETRY`, CronJob market-agent-trade-geometry)."""
from __future__ import annotations

import argparse
import os
import pathlib
from datetime import datetime, timezone

from app.db import Base, SessionLocal, engine
from app.schedule_orchestration import (
    OPERATION_TRADE_GEOMETRY, TRIGGER_SCHEDULED, acquire_execution, complete_execution, fail_execution,
)
from app.selection_gate.code_version import selection_code_version
from app.selection_gate.metrics import measured
from app.trade_geometry.report import render_report
from app.trade_geometry.runner import run_trade_geometry
from scripts.run_selection_gate import read_only_sessionmaker


def main(argv: list[str] | None = None) -> None:
    parser = argparse.ArgumentParser(description="Run TG-001 trade geometry validation.")
    parser.add_argument("--walk-forward-only", action="store_true", help="no exam, no tg_* row; report only")
    parser.add_argument("--source-url", default=os.environ.get("SELECTION_SOURCE_DATABASE_URL"),
                        help="read-only source for market data and every SPG table (pre-merge evaluation)")
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
        claim = acquire_execution(session, operation_name=OPERATION_TRADE_GEOMETRY, scope_key="GLOBAL",
                                  trigger_type=TRIGGER_SCHEDULED, trigger_source=requested_at.isoformat(),
                                  triggered_at=requested_at)
        if claim.is_duplicate:
            print(f"Trade geometry already ran for this trigger (execution id={claim.existing.id}); skipping.")
            return
        try:
            kwargs = dict(walk_forward_only=args.walk_forward_only, now=requested_at,
                          code_version=args.code_version or selection_code_version())
            with measured() as metrics:
                if source is not None:
                    with source() as read_session:
                        result = run_trade_geometry(session, read_session=read_session, **kwargs)
                else:
                    result = run_trade_geometry(session, **kwargs)
            result["metrics"] = {**metrics, "universe_rows": result["dataset"]["universe_rows"],
                                 "path_rows": result["dataset"]["path_rows"], "sessions": result["dataset"]["sessions"]}
        except BaseException as exc:
            fail_execution(session, claim, started_at=requested_at, failed_at=datetime.now(timezone.utc),
                           failure_reason=f"{type(exc).__name__}: {exc}")
            raise
        complete_execution(session, claim, started_at=requested_at, completed_at=datetime.now(timezone.utc),
                           result_summary={"selectors": [
                               {"selector_horizon": s["selector_horizon"],
                                "decision": (s.get("decision") or {}).get("decision"),
                                "primary_reason": (s.get("decision") or {}).get("primary_reason"),
                                "chosen": s["chosen"]} for s in result["selectors"]]})
    report_text, document = render_report(result)
    print(report_text)
    print(document)
    if args.report_json:
        pathlib.Path(args.report_json).write_text(document, encoding="utf-8")


if __name__ == "__main__":
    main()
```

- [ ] **Step 5: Write the CronJob** `deploy/k8s/base/trade-geometry-cronjob.yaml`. In `deploy/k8s/base/kustomization.yaml`, add `  - trade-geometry-cronjob.yaml` directly after `  - selection-shadow-cronjob.yaml`, with the comment line `  # TG-001: monthly trade geometry, the day after the selection gate.` above it.

```yaml
# TG-001: monthly trade geometry. 20:30 UTC on the 11th = 02:00 IST on the 12th, the day after the selection
# gate (02:00 IST on the 11th) registers its exam month. Writes tg_* decisions only; SEL-001 stays shadow.
# Resources match the selection gate: an approved ceiling for a few hours a month.
apiVersion: batch/v1
kind: CronJob
metadata:
  name: market-agent-trade-geometry
  namespace: market-agent
spec:
  schedule: "30 20 11 * *"
  timeZone: "Etc/UTC"
  concurrencyPolicy: Forbid
  startingDeadlineSeconds: 3600
  successfulJobsHistoryLimit: 3
  failedJobsHistoryLimit: 3
  jobTemplate:
    spec:
      backoffLimit: 0
      ttlSecondsAfterFinished: 172800
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
            - name: trade-geometry
              image: marksy-api:local
              imagePullPolicy: IfNotPresent
              command: ["python", "-m", "scripts.run_trade_geometry"]
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

- [ ] **Step 6: Bump the pinned counts.** One more CronJob and one more unowned operation. Make exact replacements:
  - `tests/test_epic317_drills.py`:
    - Replace `    # SPG-001's monthly selection gate and its daily shadow scorer: 33/32.` and the next line `    assert (health.drift.source_count, health.drift.live_count) == (33, 32)` with `    # SPG-001's monthly selection gate and its daily shadow scorer: 33/32; TG-001's trade geometry: 34/33.` and `    assert (health.drift.source_count, health.drift.live_count) == (34, 33)`.
    - Replace `    # SPG-001's monthly selection gate and its daily shadow scorer: -> 33.` and `    assert health.source_cronjobs == health.live_cronjobs == 33` with `    # SPG-001's monthly selection gate and its daily shadow scorer: -> 33; TG-001's trade geometry -> 34.` and `    assert health.source_cronjobs == health.live_cronjobs == 34`.
  - `tests/test_api_operations_health.py`: replace all three occurrences of `== 9  # SPG-001: SELECTION_GATE, SELECTION_SHADOW` with `== 10  # SPG-001: SELECTION_GATE, SELECTION_SHADOW; TG-001: TRADE_GEOMETRY`.
  - `tests/test_epic317_regressions.py`: replace `# SPG-001 adds SELECTION_GATE and SELECTION_SHADOW: 31 -> 33, both unowned.` and `DECLARED_OPERATIONS = 33` with `# SPG-001 adds SELECTION_GATE and SELECTION_SHADOW: 31 -> 33, both unowned.`, `#`, `# TG-001 adds TRADE_GEOMETRY: 33 -> 34, unowned.` and `DECLARED_OPERATIONS = 34`.
  - `tests/test_operation_verification_s7.py`:
    - In `UNOWNED`, after `    "SELECTION_SHADOW",`, add `    # TG-001: no completion contract is defined for trade geometry yet.` and `    "TRADE_GEOMETRY",`.
    - In the docstring of `test_coverage_is_eighteen_of_twentyfive_and_stated`, after the SPG-001 sentence, add a blank line and `    TG-001 moves it to 24/34: TRADE_GEOMETRY joins the unowned set.`
    - Replace `    assert len(declared) == 33  # SPG-001: SELECTION_GATE, SELECTION_SHADOW` with `    assert len(declared) == 34  # SPG-001: SELECTION_GATE, SELECTION_SHADOW; TG-001: TRADE_GEOMETRY`.

- [ ] **Step 7: Run the new tests and every guard the SPG work had to satisfy; expect PASS**

```bash
DATABASE_URL="sqlite:///$SCRATCH/tg-12.db" python -m pytest tests/test_trade_geometry_operations.py tests/test_cronjob_manifests.py \
  tests/test_scheduler_producer_inventory.py tests/test_cold_start_resilience.py tests/test_operation_recovery.py \
  tests/test_schedule_orchestration.py tests/test_schedule_registry.py tests/test_validate_scheduler_inventory.py \
  tests/test_epic317_drills.py tests/test_epic317_regressions.py tests/test_api_operations_health.py \
  tests/test_operation_verification_s7.py tests/test_operations_health.py tests/test_operations_metrics.py \
  tests/test_learning_safety_bars.py tests/test_version_registry.py tests/test_alembic_single_head.py -q -p no:cacheprovider
```

Expected: all pass. `test_every_scheduled_producer_in_scripts_has_a_registered_cronjob` and `test_every_registered_cronjob_is_attributed_to_the_script_it_runs` see `scripts/run_trade_geometry.py`. `test_every_job_reaps_its_finished_pods` sees 172800. `test_no_cronjob_may_outlive_the_lock_its_run_holds` sees 21600 ≤ 21600, below the monthly interval.

- [ ] **Step 8: Commit**

```bash
git add scripts/run_trade_geometry.py deploy/k8s/base app/schedule_orchestration.py app/operation_recovery.py tests/test_trade_geometry_operations.py tests/test_cronjob_manifests.py tests/test_epic317_drills.py tests/test_api_operations_health.py tests/test_epic317_regressions.py tests/test_operation_verification_s7.py
git commit -m "TG-001: monthly trade-geometry CronJob, operation, lease, trigger and recovery policy" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 13: Read-only snapshot and the pre-merge walk-forward evaluation

**Files:**
- Create: `scripts/load_trade_geometry_snapshot.py`
- Test: `tests/test_trade_geometry_snapshot.py`
- No production writes. Evaluation artefacts go only to `$SCRATCH`; none is committed.

**Interfaces:**
- Consumes: `scripts.load_selection_snapshot.load_snapshot(csv_dir, url)`, `_make_coercer(column)` and `_CHUNK`, all unchanged.
- Produces: `load_tg_snapshot(csv_dir, url) -> dict[str, int]`. It loads the three SPG source tables plus `selection_gate_decisions` and `selection_gate_trades`, and refuses any `ho_*`/`holdout_*` column and any row that is not `WALK_FORWARD`.

- [ ] **Step 1: Write the failing test** `tests/test_trade_geometry_snapshot.py`

```python
import pytest
from sqlalchemy import create_engine, select
from sqlalchemy.orm import sessionmaker

from app.models import SelectionGateDecision, SelectionGateTrade
from scripts.load_trade_geometry_snapshot import load_tg_snapshot

DECISIONS = ("id,decision_uuid,model_version,horizon_sessions,gate_rule_version,universe_rule_version,dataset_version,"
             "dataset_sha256,feature_version,artefact_sha256,config_snapshot,config_sha256,code_version,wf_trades,"
             "wf_mean_excess,decision,primary_stage,primary_reason,reasons,decided_at,valid_until\n"
             '7,u-1,SEL-001,3,SPG-001,SEU-001,SEL-DS-001,d,FV-002,a,"{""selection_top_k"": 10}",c,x,2400,0.005,'
             'NO_EDGE,WALK_FORWARD,CONFIDENCE_THRESHOLD_NOT_MET,[],2026-10-10 20:30:00+00,2026-11-24 20:30:00+00\n')
TRADES = ("id,decision_id,stage,session_date,stock_id,horizon_sessions,rank,score,label_status,cost,excess_return,"
          "disposition\n9,7,{stage},2026-03-02,1,3,1,0.5,RESOLVED,0.003,0.01,ACCEPTED\n")


def _write(tmp_path, *, stage="WALK_FORWARD", decisions=DECISIONS):
    (tmp_path / "stocks.csv").write_text("id,symbol,instrument_key,exchange,sector,is_active\n1,AAA,,NSE,TECH,t\n")
    (tmp_path / "corporate_actions.csv").write_text("id,stock_id\n")
    (tmp_path / "market_prices.csv").write_text(
        "id,stock_id,timestamp,open,high,low,close,volume,source\n"
        "1,1,2026-03-01 18:30:00+00,100.5,101,99,100.25,12345,upstox-v3-historical\n")
    (tmp_path / "selection_gate_decisions.csv").write_text(decisions)
    (tmp_path / "selection_gate_trades.csv").write_text(TRADES.format(stage=stage))
    return f"sqlite:///{(tmp_path / 'snapshot.db').as_posix()}"


def test_the_snapshot_holds_spgs_walk_forward_selections_and_nothing_held_out(tmp_path):
    url = _write(tmp_path)
    assert load_tg_snapshot(tmp_path, url) == {"stocks": 1, "corporate_actions": 0, "market_prices": 1,
                                               "selection_gate_decisions": 1, "selection_gate_trades": 1}
    with sessionmaker(bind=create_engine(url))() as db:
        decision = db.scalar(select(SelectionGateDecision))
        assert decision.config_snapshot == {"selection_top_k": 10} and decision.ho_trades is None
        assert db.scalar(select(SelectionGateTrade.stage)) == "WALK_FORWARD"


def test_a_held_out_row_or_column_is_refused(tmp_path):
    held, leaky = tmp_path / "held", tmp_path / "leaky"
    held.mkdir()
    leaky.mkdir()
    with pytest.raises(ValueError, match="WALK_FORWARD"):
        load_tg_snapshot(held, _write(held, stage="HELD_OUT"))
    decisions = DECISIONS.replace("wf_mean_excess,", "wf_mean_excess,ho_trades,", 1).replace(",0.005,", ",0.005,120,", 1)
    with pytest.raises(ValueError, match="held-out columns"):
        load_tg_snapshot(leaky, _write(leaky, decisions=decisions))
```

- [ ] **Step 2: Run; expect FAIL** (`ModuleNotFoundError: No module named 'scripts.load_trade_geometry_snapshot'`)

Run: `DATABASE_URL="sqlite:///$SCRATCH/tg-13.db" python -m pytest tests/test_trade_geometry_snapshot.py -q -p no:cacheprovider`

- [ ] **Step 3: Implement** `scripts/load_trade_geometry_snapshot.py`

```python
"""TG-001 pre-merge evaluation: SPG's three snapshot tables plus SEL-001's walk-forward selections, read-only export."""
from __future__ import annotations

import argparse
import pathlib

import pandas as pd
import sqlalchemy as sa

from app.db import Base
from app.selection_gate.constants import STAGE_WALK_FORWARD
from scripts.load_selection_snapshot import _CHUNK, _make_coercer, load_snapshot

SPG_TABLES = ("selection_gate_decisions", "selection_gate_trades")


def _refuse_held_out(table: sa.Table, columns, records) -> None:
    """Invariant 3 at the door: no held-out column, no HELD_OUT row, ever reaches the evaluation store."""
    leaked = [c for c in columns if c.startswith(("ho_", "holdout_"))]
    if leaked:
        raise ValueError(f"TG-001 snapshot: {table.name} carries held-out columns {leaked}")
    if table.name == "selection_gate_trades" and any(r.get("stage") != STAGE_WALK_FORWARD for r in records):
        raise ValueError("TG-001 snapshot: selection_gate_trades holds a row that is not WALK_FORWARD")


def load_tg_snapshot(csv_dir: pathlib.Path, url: str) -> dict[str, int]:
    counts = load_snapshot(csv_dir, url)
    engine = sa.create_engine(url)
    tables = [Base.metadata.tables[name] for name in SPG_TABLES]
    Base.metadata.create_all(engine, tables=tables)
    with engine.begin() as connection:
        for table in tables:
            coercers = {name: _make_coercer(column) for name, column in table.c.items()}
            total = 0
            for chunk in pd.read_csv(csv_dir / f"{table.name}.csv", chunksize=_CHUNK, dtype=str, keep_default_na=False):
                columns = [name for name in chunk.columns if name in coercers]
                coerced = {name: [coercers[name](value) for value in chunk[name].tolist()] for name in columns}
                records = [dict(zip(columns, row)) for row in zip(*coerced.values())] if columns else []
                _refuse_held_out(table, chunk.columns, records)
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
    print(load_tg_snapshot(args.csv_dir, args.url))
```

- [ ] **Step 4: Run; expect PASS (2 passed, SPG's snapshot test unchanged), then commit**

```bash
DATABASE_URL="sqlite:///$SCRATCH/tg-13.db" python -m pytest tests/test_trade_geometry_snapshot.py tests/test_selection_gate_snapshot.py -q -p no:cacheprovider
git add scripts/load_trade_geometry_snapshot.py tests/test_trade_geometry_snapshot.py
git commit -m "TG-001: load a read-only export of SPG's walk-forward selections for the pre-merge run" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

- [ ] **Step 5: Freeze the evaluated commit** **(depends on open question 3)**. Everything is committed and green: `git rev-parse HEAD > $SCRATCH/tg-eval-commit.txt`.
  - Spec §22.3: `tg_holdout_not_before` must be the first full month after this freeze. On a freeze in October 2026 that is `"2026-11"`, as configured.
  - If the freeze falls in November or later, set it to the next month, re-run Task 1's tests, commit, and freeze again.
  - Under OQ3 = faster, it stays `"2026-09"` whatever the date.
  - From here, any TG code or config change moves the month forward (spec §14).

- [ ] **Step 6: Export six tables read-only, in id-range chunks.** `kubectl exec` streams reset after about 5 minutes, so the large tables go in chunks with a count check per chunk. This is the SPG Task 15 recipe: every statement runs inside `BEGIN TRANSACTION READ ONLY … ROLLBACK`, through `ssh marksy@103.178.160.233`. `selection_gate_trades` leaves production filtered to `WALK_FORWARD`, and `selection_gate_decisions` without any `ho_*`/`holdout_*` column, so held-out evidence never reaches the snapshot.

```bash
OUT=$SCRATCH/tg-snapshot; mkdir -p $OUT/chunks
tg_copy() {  # $1 = one \copy statement; CSV on stdout
  { printf '%s\n' 'export KUBECONFIG=$HOME/.kube/config' \
      "P=\$(kubectl -n market-agent get pods --field-selector=status.phase=Running -o name | grep -m1 '^pod/postgres')" \
      "kubectl -n market-agent exec -i \"\$P\" -- sh -c 'psql -X -q -v ON_ERROR_STOP=1 -U \"\$POSTGRES_USER\" -d \"\$POSTGRES_DB\"' <<'SQL'" \
      'BEGIN TRANSACTION READ ONLY;' "$1" 'ROLLBACK;' 'SQL'; } > $SCRATCH/tg-copy.sh
  cat $SCRATCH/tg-copy.sh | ssh -o ConnectTimeout=15 marksy@103.178.160.233 "tr -d '\r' | bash"
}
tg_chunks() {  # $1 = table, $2 = row filter, $3 = id step; writes $OUT/$1.csv
  local lo hi n a b want got attempt
  IFS=, read lo hi n <<< "$(tg_copy "\copy (SELECT min(id), max(id), count(*) FROM $1 WHERE $2) TO STDOUT WITH CSV" | tr -d '\r')"
  rm -f $OUT/chunks/$1.*.csv
  for ((a=lo; a<=hi; a+=$3)); do
    b=$((a + $3))
    want=$(tg_copy "\copy (SELECT count(*) FROM $1 WHERE $2 AND id >= $a AND id < $b) TO STDOUT WITH CSV" | tr -d '\r')
    for attempt in 1 2; do
      tg_copy "\copy (SELECT * FROM $1 WHERE $2 AND id >= $a AND id < $b ORDER BY id) TO STDOUT WITH CSV HEADER" > $OUT/chunks/$1.$a.csv
      got=$(( $(wc -l < $OUT/chunks/$1.$a.csv) - 1 ))
      [ "$got" = "$want" ] && break
      echo "$1 [$a, $b): got $got, want $want; retrying" >&2
    done
    [ "$got" = "$want" ] || { echo "$1 [$a, $b) is still short: halve the step for this range" >&2; return 1; }
  done
  (cd $OUT/chunks && files=$(ls $1.*.csv | sort -t. -k2 -n) && head -1 $(echo "$files" | head -1) > ../$1.csv \
     && for f in $files; do tail -n +2 "$f" >> ../$1.csv; done)
  echo "$1: $(( $(wc -l < $OUT/$1.csv) - 1 )) rows exported, $n expected"
}
tg_copy "\copy (SELECT * FROM stocks ORDER BY id) TO STDOUT WITH CSV HEADER" > $OUT/stocks.csv
tg_copy "\copy (SELECT * FROM corporate_actions ORDER BY id) TO STDOUT WITH CSV HEADER" > $OUT/corporate_actions.csv
tg_copy "\copy (SELECT id, decision_uuid, model_version, horizon_sessions, gate_rule_version, universe_rule_version, dataset_version, dataset_sha256, feature_version, artefact_sha256, config_snapshot, config_sha256, code_version, wf_trades, wf_mean_excess, decision, primary_stage, primary_reason, reasons, decided_at, valid_until, created_at FROM selection_gate_decisions ORDER BY id) TO STDOUT WITH CSV HEADER" > $OUT/selection_gate_decisions.csv
tg_chunks market_prices "source <> 'upstox-v3-quote-ohlc'" 1000000
tg_chunks selection_gate_trades "stage = 'WALK_FORWARD'" 1000000
wc -l $OUT/*.csv
```

Expected: each `tg_chunks` line reports exported rows equal to expected rows. `psql -q` suppresses the `BEGIN`/`ROLLBACK` tags; if one appears in a CSV, delete that line. If a chunk is still short after its retry, halve the step and re-run only that table.

- [ ] **Step 7: Load the snapshot and run walk-forward-only in an ephemeral store**

```bash
cd /c/AIAgent/marksy-api
DATABASE_URL="sqlite:///$SCRATCH/tg-load.db" python -m scripts.load_trade_geometry_snapshot $OUT "sqlite:///$SCRATCH/tg-snapshot.db"
DATABASE_URL="sqlite:///$SCRATCH/tg-eval.db" python -m scripts.run_trade_geometry --walk-forward-only --ephemeral-schema \
  --source-url "sqlite:///$SCRATCH/tg-snapshot.db" --code-version "$(cat $SCRATCH/tg-eval-commit.txt)" \
  --report-json $SCRATCH/tg-eval-report.json | tee $SCRATCH/tg-eval-report.txt
```

Expected:
- The loader's counts equal Step 6's.
- The run writes only the execution row in `tg-eval.db` and no `tg_*` row.
- Each selector has `stage_less_reason` null, a bound decision equal to the latest SEL-001 decision at that `h_sel`, 37 geometry rows and a parity block.
- `TG exam month: none`, under the purist OQ3.

Compute and runtime:
- Spec §19.4 estimate: about 35–45 minutes and a peak of about 3.5–4 GB.
- Prototype measurement: TG's own compute on a synthetic 2.4M-row, 2,000-session path set takes about 2 minutes and peaks at 2.3 GB. That covers path status, 37 universe benchmarks, 37 geometry runs with B = 10,000 bootstraps per selector, and the reality check. The rest of the runtime is the SEL-DS-001 build (about 15 minutes) and the pass-1 bar reads (about 5 minutes).

- [ ] **Step 8: Record the evidence for the report.** From `tg-eval-report.json` `metrics`, take `elapsed_seconds`, `cpu_seconds`, `peak_memory_mb`, `universe_rows`, `path_rows` and `sessions`, and compare them with the 21600 s and 6 Gi limits. If either is exceeded, the report proposes new limits; never raise them silently. Also record:
  - per selector: `bound_spg_decision_id`, `wf_sessions`, `wf_protected_excluded`, `reality_check_p`, the tolerance set and `chosen`
  - all 37 rows
  - the parity block, explaining every nonzero `status_mismatches`, `tg_only_trades` or `spg_only_trades` (§7.3's stricter resolution, data changed since the bound run, or `T_σ`)
  - `dataset_sha256`, `stocks_by_year` and the session range

---

### Task 14: Full validation and the review report (stop before merge)

- [ ] **Step 1: The whole suite, in the background, on its own database file** (about 25 minutes)

```bash
cd /c/AIAgent/marksy-api && DATABASE_URL="sqlite:///$SCRATCH/tg-full.db" python -m pytest -q -p no:cacheprovider > $SCRATCH/tg-full-suite.txt 2>&1; tail -30 $SCRATCH/tg-full-suite.txt
```

Compare against the 5 failures known on `main` as of 2026-10-01: cold-start tip-new-alerts, 2× unordered single-row reads, and 2× order-dependent news category. Confirm with a clean `main` worktree run of just those files. Any other failure blocks the report.

- [ ] **Step 2: Scope checks**

```bash
git diff --stat main -- app/selection_gate scripts/run_selection_gate.py scripts/load_selection_snapshot.py app/recommendations.py   # expect empty
git diff --stat main                                                                                                              # only the files in this plan
grep -rn "xgboost" app/trade_geometry scripts/run_trade_geometry.py scripts/load_trade_geometry_snapshot.py                         # expect nothing
```

- [ ] **Step 3: One whole-branch review** on the most capable model, over `git merge-base main HEAD..HEAD`. It must cover:
  - SPG untouched and the dependency one-way
  - no `HELD_OUT`, `ho_*` or `holdout_*` read on any path, including the snapshot export
  - the freeze committed before any pass-2 bar is read, and pass 2 running once
  - single use per selector and month, including the concurrent insert and the kill-after-freeze case
  - label independence of candidates, path status, `T_σ` and the choice
  - reduction before statistics
  - every fail-closed path, and the `valid_until` of failures
  - no publication outcome change, and SEL-001 `SHADOW_ONLY`
  - append-only `tg_*` rows
  - the parity block explained
  - runtime against the job's limits

  Then one fix wave and one scoped re-review.

- [ ] **Step 4: Draft-PR text, no push.** Write the title and body to `$SCRATCH/tg-001-draft-pr.md`.
  - Title: `TG-001: trade geometry validation`.
  - Body: link the spec, list `git log --oneline main..HEAD`, carry the Task 13 evaluation summary, and end with `🤖 Generated with [Claude Code](https://claude.com/claude-code)`.
  - Do not push, open a PR, merge, deploy or migrate.

- [ ] **Step 5: The review report to the user** (spec §22), then stop:
  - **Formulas**, §7–§15 as implemented, each with `file:line`:
    - path status `fills.py::path_status`, fills `fills.py::simulate`, path statistics `fills.py::path_statistics`
    - adjustment `paths.py::_stock_paths`, hashes `paths.py::dataset_sha256` / `selection_sha256`
    - benchmarks `evaluation.py::universe_benchmarks`, reduction `reduction.py::reduce_geometry_candidates`
    - stage `evaluation.py::evaluate_geometry`, folds `choice.py::fold_statistics`, reality check `reality_check.py::reality_check`
    - choice `choice.py::choose_geometry`, decision `choice.py::decide_geometry`
    - windows `windows.py`, exam `holdout.py` and `runner.py::_check_and_freeze`
  - **Data sources**: the tables read, the session range, stocks per year, `dataset_sha256`, the bound decisions, and `T_σ` sizes and exclusions.
  - **Geometry table**: per selector, all 37 rows by horizon and by geometry, with every WF aggregate, reasons, eligibility, the tolerance set, `reality_check_p` and `g*`, and the reference side by side.
  - **Parity**: the §10.2 block per selector, with every difference explained.
  - **Held-out**: none is expected yet (no exam month under the purist rule). The first possible TG exam is on 12 Dec 2026, for November 2026.
  - **Tests**: TG test files with pass counts, and the full-suite result against the `main` baseline.
  - **Runtime**: elapsed time, CPU, peak memory, rows and sessions, against 21600 s and 6 Gi.
  - **What changes on deploy**: one CronJob and five empty tables. No publication outcome changes, and SEL-001 stays `SHADOW_ONLY`.
  - **After the first production run** (spec §22.4): its WF numbers must equal Task 13's for the same `dataset_sha256` and `bound_spg_decision_id`. Report any difference.

No merge, deploy or migration until the user approves the report.

---

## Spec deviations and clarifications for review

1. **Spec defect: parity needs SPG's `excess_return`.** §10.2 compares against "the SPG accepted trades' `excess_return`", but §6.2's column list omits it, and §6.4 says TG reads nothing else. `walk_forward_candidates` adds `excess_return` to the SELECT, under the same `WALK_FORWARD` predicate, and only `parity.py` uses it (Task 7).
2. **Spec defect: a `DUPLICATE` cannot be stored under §16.3's unique key.** The key is `(decision_id, geometry_role, stage, session_date, stock_id)`, and a duplicate shares it with the row it duplicates. The sources are unique (`uq_selection_gate_trade`, `uq_selection_shadow_score`), so it cannot arise. If it ever did, the insert fails and the selector records `EVALUATION_FAILED`. Reduction still classifies duplicates, so counts and statistics are right.
3. **Spec defect: the §21.7 poisoning test holds by schedule, not by construction.** A walk-forward session after `P_TG` legitimately reads `P_TG` bars as features (ATR-14, the 20-session liquidity median), so poisoning would change its numbers. That breaks no isolation, since market bars are not evidence (§6.5). Bound walk-forward sessions end before SPG's exam month, which is at or after `M_TG`, so the case does not arise in operation. The test world keeps every walk-forward session before `P_TG`. Also, a multiplicative poison is invisible to returns, so the test distorts the bars additively and asserts that the exam did change.
4. **Spec defect: the §6.2/§12 handling of a `session_date` outside TG's `S` is undefined.** Such a candidate has no `i(D)`, so neither its §12 order nor its protected-span test is defined. Reduction orders and tests overlap by date, which equals `i(D)` order inside `S`. The candidate always stays in the walk-forward stage as `NOT_IN_DATASET`/`UNRESOLVED`; it carries no path, so it reveals nothing about the exam (Review Focus 1).
5. **Spec ambiguity: status names in parity.** SPG's `EXIT_BAR_MISSING` and TG's `PATH_BAR_MISSING` name the same fact. `parity.status_mismatches` treats them as agreeing. Every other difference counts, under TG's status.
6. `unresolved_by_status` counts a resolved path in an unbenchmarkable session under `NOT_BENCHMARKABLE`; the spec has no name for it. Such a candidate's `tg_trades.path_status` stays `RESOLVED`, and its disposition is `UNRESOLVED`.
7. **When stats are NULL:** stage numbers are NULL, never 0, when a stage did not run. That covers checks 1–3, consumption and walk-forward failure. It avoids SPG's I1 "never-ran counts" confusion. `tg_decisions.wf_*` describe the chosen geometry only; `wf_result`/`wf_reasons` describe the walk-forward stage.
8. **A shared-phase failure** (dataset build, window registration, binding, pass 1) writes `EVALUATION_FAILED` for both selectors and re-raises, so the execution row is `FAILED`. Per-selector and pass-2 failures are recorded and the run continues. A month frozen before a failure stays spent.
9. **Types the spec leaves open:**
   - `tg_holdout_usages.bound_spg_decision_id` is a NOT NULL foreign key to `selection_gate_decisions`.
   - `tg_geometry_results.target_pct`/`stop_pct` are `Numeric(12, 8)`, because they are return levels.
   - `turnover` and `cost_drag` are `Numeric(12, 8)`.
10. **Pass 2's query:** pass 2 reads each stock's bars restricted to `[anchor(first session of M_TG), anchor(S[i(last)+7])]`. That is the spec's window, issued per stock. The freeze-order test watches the call, not one SQL statement.
11. **SPG names TG imports beyond §6.4:**
    - `StageStatistics` and `Month`, as type names
    - `SelectionDataset.anchors`
    - the SPG constants `DATASET_VERSION`, `UNIVERSE_RULE_VERSION`, `HOLDOUT_REGISTRY_VERSION`, `SEL001_MODEL_VERSION`, `RESULT_*` and `LABEL_EXIT_BAR_MISSING`
    - `scripts/load_selection_snapshot.py`'s `_make_coercer`/`_CHUNK`/`load_snapshot` (Task 13), and `read_only_sessionmaker` (§19.1), which also loads SPG's runner and xgboost into the TG batch process

    `app/trade_geometry` itself loads neither, which Task 11 pins.
12. **Pre-merge snapshot hygiene:** `scripts/load_trade_geometry_snapshot.py` refuses any `ho_*`/`holdout_*` column and any non-`WALK_FORWARD` trade row, so invariant 3 also holds for the evaluation copy.

## Self-review

**Spec coverage** (section → task):
- §3 invariants: 1–2 → Tasks 11 and 14; 3 → Tasks 7 and 11; 4 → Task 11; 5 → Task 11; 6 → Task 1; 7 → Tasks 8, 10 and 11; 8 → Tasks 6 and 9; 9 → Task 3; 10 → Tasks 4 and 10; 11 → Task 9; 12 → Tasks 2 and 10; 13 → Global Constraints.
- §6 → Task 7; §7.2–7.5 → Tasks 3 and 7; §8–§9 → Task 3; §10.1 → Task 1; §10.2 → Task 10; §10.3 → Tasks 1, 3 and 10; §11 → Task 9; §12 → Task 4.
- §13.1 → Task 4; §13.2–13.3 → Tasks 6, 9 and 10; §13.4–13.5 → Tasks 8 and 10; §13.6 → Task 10.
- §14 → Tasks 1, 5, 6 and 13; §15 → Tasks 5, 6 and 9; §16 → Task 2; §17 → Task 10; §18 → Task 11 (tests only; reasons reserved in Task 1).
- §19.1 → Task 12; §19.2 → Task 12; §19.3 → Task 12; §19.4 → Task 13; §20 → Task 1; §21.1–21.11 → Tasks 2–12; §22 → Tasks 13–14.

**Placeholder scan:** no "TBD", "TODO", "similar to" or undefined names. Every code step carries its full file, and the only partial-file steps are exact text replacements in existing files.

**Type consistency:** the names below are used identically across tasks, checked by running the prototype end to end:
- `Geometry.key`, `grid_index` and `is_reference`
- `PathSet.locate`
- `SelectionRow` fields
- `GeometryRun.reasons`, `metrics` and `reduction` keys
- `Choice.eligible`, `tolerance_set` and `chosen`
- `RealityCheck.p_value`, `statistic` and `geometries`
- `write_decision(values, *, usage)`
- the selector report keys used by `render_report` and the script's `result_summary`

**Review Focus:** five uncovered input classes were identified, and each has its test in the owning task (Tasks 4, 7, 8, 9 and 10).
