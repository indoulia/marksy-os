# Marksy SEL-002 Selection Diagnostic Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Answer, offline and read-only, whether SEL-001 ranks stocks with higher future excess returns than simple baselines and chance, out of sample, and record a predeclared A/B/C evidence class in one report. No change to SPG-001 or to publication.

**Architecture:**
- A new package, `app/selection_diagnostics/`, holds pure, unit-tested functions: held-out restriction, a write guard, the session bootstrap, baselines, ranking metrics, the `run_stage` harness, a parameterised copy of SPG-001's walk-forward, nulls, concentration, regimes, evidence rules, refit jobs and the report.
- `scripts/run_sel002_diagnostic.py` runs the stages prepare → refit (a process pool) → parity → analyze → report. It reads a read-only snapshot and writes only to the scratchpad.
- SPG-001 (`app/selection_gate/`) is imported, never edited.

**Tech Stack:** Python 3.12, pandas 2.3, numpy 2.x, SQLAlchemy 2, xgboost (SPG-001's `fit_sel001`), pytest on SQLite, `concurrent.futures.ProcessPoolExecutor` (spawn). No new dependency.

**Spec:** `docs/superpowers/specs/2026-10-01-marksy-sel-002-selection-diagnostic.md` in marksy-os. The binding inputs are the predeclared protocol (`scratchpad/sel002-protocol.md`) and the addendum (`scratchpad/sel002-addendum.md`). Code goes in the worktree `C:\AIAgent\marksy-api-sel002`, on branch `feat/sel-002-diagnostic`, which already exists at `8944bf4` (marksy-api `main`).

**Pre-validated:** every code block below passed (99 tests, about 2 min) in a scratch copy of marksy-api at `8944bf4` before this plan was first committed, against evidence rules `SEL-002-EVIDENCE-001`. On 2026-10-01 the spec was revised at the user's review to `SEL-002-EVIDENCE-002` (core/robustness grading, major red flags F1-F5, the K8s Job dropped); Tasks 12, 14, 15, 16 and 18 below were updated to match and their changed code blocks re-run on synthetic data before this revision was committed. Implement them verbatim; a red or green result that differs from the one stated is a finding to investigate, not something to patch over.

## Global Constraints

- **SPG-001 is import-only.** Nothing under `app/selection_gate/**` or `scripts/run_selection_gate.py` changes. After every task, `git diff --exit-code 8944bf4 -- app/selection_gate scripts/run_selection_gate.py` must print nothing.
- **No database writes.** The source is read only through `scripts.run_selection_gate.read_only_sessionmaker`. The CLI runs inside `guards.no_database_writes()`. SEL-002 never writes `HoldoutWindowRegistry`, `HoldoutUsageRecord`, `SelectionHoldoutUsage`, `tg_holdout_*` or any SPG-001 table.
- **Held-out:**
  - The exam starts `2026-08-01` (first session 2026-08-03), and the held-out month is `(2026, 8)`.
  - `heldout.restrict` runs first; only the session calendar (dates) is kept whole.
  - Every tested session satisfies `i(D) + h < i(exam start)`.
- **Frozen configuration** (`DiagnosticConfig()` defaults):
  - windows: horizons `(3, 5)`, today `2026-10-01`, first test month `"2018-01"`
  - K: `(1, 5, 10, 25, 50)`, primary `10`; cost `0.0030`
  - bootstrap: `10000` draws, seed `42`, L = h
  - seeds: random baseline `42` plus `1..20`; permutation seeds `1..8`; selection null `1000` draws, seed `42`
  - bucket edges `(0, 1, 5, 10, 20, 30, 40, 50, 60, 70, 80, 90, 100)`; top fractions `0.01` and `0.05`
  - parity tolerance `1e-6`
- **Evidence** (`SEL-002-EVIDENCE-002`):
  - 37 checks per horizon, tagged core (E1-E4, 19) or robustness (E5-E6, 18)
  - IC share ≥ `0.55`, monotonicity ≥ `0.8`
  - breadth at K `5, 10, 25, 50`
  - `8` permutation runs, ≥ `3` sd; selection-null p ≤ `0.01`
  - single sector or year share ≤ `0.40`
  - an undefined value fails, except an undefined E6 CI
  - major red flags F1-F5 (§7.2): F1/F2 session/stock dependence (ex-top-1% K=10 mean net excess ≤ 0 or undefined),
    F3/F4 period/sector dependence (largest share > 0.40 or undefined), F5 a losing regime with CI_high < 0 and
    `MIN_REGIME_SESSIONS = 250` sessions with ≥ 1 accepted K=10 trade; a thinner losing regime or a failed E5 CI
    check whose mean stays positive is a minor flag only
  - A: all 19 core checks hold at both horizons, and no major flag (F1-F5) at either horizon. B: otherwise, mean IC
    CI_low > 0 or K=10 net-excess CI_low > 0 at either horizon. C: otherwise.
- **Pins:**
  - snapshot `5f59b9bc929f5d0c3f8fcdada419d705f0e6e9a3c0e26efb72d79ae2f0579a8b`
  - `dataset.pkl` `50035bae382cf525ece4643cfcf4cf876b9c61db3c4573110522ede73a6dc08e`
  - `wf_h3.npz` `cda9efc995c1a71b8ee4dfe340357c0194f759f6c6ad822240c255a1bb898615`
  - `wf_h5.npz` `916390230c695ed0af5a896340d6470ed5532b01094ffa37ed6c9a3b5e426204`
  - SEL-DS-001 h=3 `ab07215f9790f19cb2d5efaf3a269db1a83d107290fb2ec895fcdf2cbe887eb0`
  - SEL-DS-001 h=5 `a2381ab89b873aa9e364fb03924b443d73bc68ca2a05542c66aab24db54c9b68`
- **No tuning.** `SEL001_PARAMS` is used unchanged (`n_jobs=2`, `random_state=42`).
- **Never deployed.** SEL-002 is never added to the API path, `schedule_orchestration` or `deploy/k8s/base`, and the shadow job is never altered. Do not push, merge or deploy.
- **Comments.** One line, citing SEL-002 where useful, matching the surrounding style.
- **TDD.** Every task goes failing test, confirm red, implement, confirm green, commit.
- **Execution reviews.** Per the user's standing instruction, do not review task by task. Run one whole-branch review in Task 18 before the evaluation run, then one fix wave and one scoped re-review.
- **Tests:**
  - Run from the worktree with `DATABASE_URL` set to a throwaway SQLite file (importing `app` needs one).
  - Only the synthetic world and temporary SQLite files are used; the snapshot is never touched before Task 18.
- **Windows faulthandler noise.** Pytest may print `Windows fatal exception: access violation` while Task 13's parallel-worker test runs. That comes from the spawned xgboost workers and is benign when the test passes; `-p no:faulthandler` silences it.

Every task's shell block assumes this setup (Git Bash):

```bash
SCRATCH="C:/Users/prsingh/AppData/Local/Temp/claude/C--AIAgent-marksy-os/6b834fe4-b26b-4339-9c36-d86bb4d99a4c/scratchpad"
cd /c/AIAgent/marksy-api-sel002
export DATABASE_URL="sqlite:///$SCRATCH/sel002-pytest.db"
```

## Review Focus

Each item below is a failure mode the spec implies but no other test exercises. It is pinned by a test in the task that owns the code.

1. **Rows without a score** (BASELINE-VOLADJ where the vol is ≤ 0, or a feature that is NaN for some stocks). They are never candidates and never enter an IC, and the universe and benchmark stay unchanged. Pinned by Task 7, `test_rows_without_a_score_are_never_selected_or_ranked`.
2. **A resumed refit whose config or inputs changed.** It reruns rather than reusing stale scores. Pinned by Task 13, `test_a_job_reruns_when_its_config_changes`.
3. **The loop scoring a row the cache did not** (or the reverse). Parity fails with `SCORED_ROWS_DIFFER` rather than comparing only the overlap. Pinned by Task 13, `test_parity_fails_when_the_loop_scores_rows_the_cache_did_not`.
4. **A truncated snapshot with no session on or after the exam start.** It is refused, never treated as all walk-forward. Pinned by Task 2, `test_a_calendar_without_the_exam_month_is_refused`.
5. **A dirty worktree or a changed SPG-001.** The report's code version is suffixed `-dirty` and `spg_unchanged` is false. Pinned by Task 16, `test_a_dirty_tree_or_a_changed_spg_is_labelled`.

## File Structure

New, in `app/selection_diagnostics/` (every module imports only from SPG-001 and from earlier tasks):
- `__init__.py`, `config.py` (the frozen `DiagnosticConfig`, groups, variants, regime buckets, pins, `check_settings`), and `files.py` (hashes, atomic JSON and pickle). Task 1.
- `heldout.py`: `restrict`, `assert_restricted`, `diagnostic_windows`, `check_tested`, `first_exam_index`. Task 2.
- `guards.py`: `no_database_writes`, `table_digest`. Task 3.
- `bootstrap.py`: `session_mean_ci`, `trade_weighted_ci`, `paired_block_bootstrap_ci`. Task 4.
- `strategies.py`: `baseline_scores`, `random_scores`, `permute_within_session`. Task 5.
- `ranking.py`: `outcome_frame`, `scored`, `correlation_by_session`, `percentile_rank`, buckets, `session_metrics`, `summarize`. Task 6.
- `harness.py`: `evaluate`, `accepted_trades`, `strategy_summary`, `paired_difference`. Task 7.
- `wf_loop.py`: `walk_forward`, `fold_signed_scores`. Task 8.
- `nulls.py`: `selection_null`, `permutation_summary`. Task 9.
- `concentration.py`: `concentration_report`. Task 10.
- `liquidity.py` and `regimes.py`: the traded-value recompute, terciles, `session_regimes`, `regime_report`. Task 11.
- `evidence.py`: `horizon_checks`, `classify`. Task 12.
- `refits.py`: `RefitJob`, `refit_jobs`, `run_jobs`, `load_scores`, `parity_check`. Task 13.
- `analysis.py`: `analyze_horizon`. Task 14.
- `report.py`: `render_report`, `REPORT_SECTIONS`. Task 15.
- `pipeline.py`: the five stages. Task 16.

New elsewhere:
- `scripts/run_sel002_diagnostic.py` (Task 16)
- `tests/_sel002_factories.py` (Task 2) and `tests/_sel002_world.py` (Task 14)
- `tests/test_sel002_*.py`

Modified: nothing.

---

### Task 1: Frozen configuration and file helpers

**Files:**
- Create: `app/selection_diagnostics/__init__.py`, `app/selection_diagnostics/config.py`, `app/selection_diagnostics/files.py`
- Test: `tests/test_sel002_config.py`

**Interfaces:**
- Consumes (SPG-001): `app.selection_gate.config.SEL001_PARAMS`, `config_sha256(snapshot) -> str`; `app.selection_gate.dataset.FEATURE_COLUMNS`; `app.purged_embargo_validation.DEFAULT_EMBARGO_DAYS`; `app.settings.settings`.
- Produces:
  - `DiagnosticConfig` (frozen dataclass; `.snapshot() -> dict`, `.sha256() -> str`) and `check_settings(cfg, source=settings) -> None` (raises `ValueError`)
  - `ordered_features(names) -> tuple[str, ...]`
  - `GROUPS: dict[str, tuple[str, ...]]`, `VARIANTS: dict[str, tuple[str, ...]]` (11, in FV-002 order), `REGIME_BUCKETS: dict[str, tuple[str, ...]]`
  - `BASELINE_NAMES` (7) and the names `SEL001`, `FULL`, `PERMUTATION`, `BASELINE_*`
  - `PINNED_SNAPSHOT_SHA256`, `PINNED_CACHE_SHA256: dict[str, str]`, `PINNED_DATASET_SHA256: dict[int, str]`, `PINNED_SPG_WALK_FORWARD`, `SPG_CODE_VERSION = "8944bf4"`, `FROZEN_SETTINGS`
  - `files.file_sha256(path) -> str`, `jsonable(value)`, `write_json(path, payload)`, `read_json(path)`, `write_pickle(path, payload)`, `read_pickle(path)`

**Data dependencies:** none (constants only).

**Compute:** < 5 s.

**Acceptance criteria:**
- `7 passed`.
- The groups partition all 23 FV-002 columns, and the variants match spec §6.5.
- `check_settings` passes on the repo defaults and raises on any drift.
- The SPG diff is empty.

- [ ] **Step 1: Confirm the worktree** (it already exists; never `git checkout` elsewhere)

```bash
SCRATCH="C:/Users/prsingh/AppData/Local/Temp/claude/C--AIAgent-marksy-os/6b834fe4-b26b-4339-9c36-d86bb4d99a4c/scratchpad"
cd /c/AIAgent/marksy-api-sel002
export DATABASE_URL="sqlite:///$SCRATCH/sel002-pytest.db"
git status --short --branch   # expect: ## feat/sel-002-diagnostic...origin/main, nothing else
git rev-parse --short HEAD     # expect: 8944bf4
mkdir -p "$SCRATCH/sel002"
```

- [ ] **Step 2: Write the failing tests**

`tests/test_sel002_config.py`:

```python
import dataclasses
import math

import numpy as np
import pytest

from app.selection_diagnostics.config import (
    BASELINE_NAMES, GROUPS, PINNED_DATASET_SHA256, REGIME_BUCKETS, VARIANTS, DiagnosticConfig, check_settings,
    ordered_features,
)
from app.selection_diagnostics.files import file_sha256, jsonable, read_json, write_json
from app.selection_gate.dataset import FEATURE_COLUMNS
from app.settings import settings


def test_groups_partition_the_23_fv002_features():
    names = [name for group in GROUPS.values() for name in group]
    assert len(names) == len(set(names)) == 23
    assert set(names) == set(FEATURE_COLUMNS)


def test_variants_are_the_predeclared_eleven_in_fv002_order():
    assert list(VARIANTS) == ["PRICE-ONLY", "VOLUME-ONLY", "PRICE+VOLUME", "LOGO-RET", "LOGO-TREND", "LOGO-OSC",
                              "LOGO-VOL", "LOGO-RANGE", "LOGO-VOLUME", "LOGO-XSEC", "LOGO-REGIME"]
    assert len(VARIANTS["PRICE-ONLY"]) == 15
    assert VARIANTS["VOLUME-ONLY"] == ("volume_ratio_20d", "volume_ratio_rank")
    assert len(VARIANTS["PRICE+VOLUME"]) == 17
    for group, names in GROUPS.items():
        assert set(VARIANTS[f"LOGO-{group}"]) == set(FEATURE_COLUMNS) - set(names)
    for features in VARIANTS.values():
        assert list(features) == [c for c in FEATURE_COLUMNS if c in features]


def test_unknown_feature_is_refused():
    with pytest.raises(ValueError):
        ordered_features(["return_5d", "close"])


def test_frozen_defaults_match_the_protocol():
    cfg = DiagnosticConfig()
    assert cfg.horizons == (3, 5) and cfg.k_values == (1, 5, 10, 25, 50) and cfg.k_primary == 10
    assert cfg.cost == 0.0030 and cfg.bootstrap_draws == 10_000 and cfg.bootstrap_seed == 42
    assert cfg.random_distribution_seeds == tuple(range(1, 21)) and cfg.permutation_seeds == tuple(range(1, 9))
    assert cfg.selection_null_draws == 1000 and cfg.selection_null_seed == 42
    assert cfg.bucket_edges == (0, 1, 5, 10, 20, 30, 40, 50, 60, 70, 80, 90, 100)
    assert cfg.exam_start.isoformat() == "2026-08-01" and cfg.expected_holdout_month == (2026, 8)
    assert cfg.today.isoformat() == "2026-10-01" and cfg.first_test_month == "2018-01"
    assert len(BASELINE_NAMES) == 7 and sum(len(v) for v in REGIME_BUCKETS.values()) == 14
    assert set(PINNED_DATASET_SHA256) == {3, 5}


def test_config_hash_is_stable_and_moves_with_any_field():
    cfg = DiagnosticConfig()
    assert cfg.sha256() == DiagnosticConfig().sha256()
    assert dataclasses.replace(cfg, selection_null_draws=999).sha256() != cfg.sha256()
    assert dataclasses.replace(cfg, k_values=(1, 5, 10, 25)).sha256() != cfg.sha256()


def test_settings_check_passes_on_defaults_and_refuses_any_drift(monkeypatch):
    check_settings(DiagnosticConfig())
    monkeypatch.setattr(settings, "selection_round_trip_cost", 0.0020)
    with pytest.raises(ValueError, match="selection_round_trip_cost"):
        check_settings(DiagnosticConfig())


def test_json_helpers_round_trip_numpy_and_nan(tmp_path):
    path = tmp_path / "a.json"
    write_json(path, {"a": np.int64(3), "b": np.float32(0.5), "c": float("nan"), 4: [np.bool_(True)]})
    assert read_json(path) == {"a": 3, "b": 0.5, "c": None, "4": [True]}
    assert jsonable(math.inf) is None
    assert len(file_sha256(path)) == 64
```

- [ ] **Step 3: Run them; expect FAIL** (`ModuleNotFoundError: No module named 'app.selection_diagnostics'`)

```bash
python -m pytest tests/test_sel002_config.py -q -p no:cacheprovider
```

- [ ] **Step 4: Implement**

`app/selection_diagnostics/__init__.py`:

```python
"""SEL-002 selection diagnostic (marksy-os docs/superpowers/specs/2026-10-01-marksy-sel-002-selection-diagnostic.md).

Offline and read-only. Imports SPG-001 (app.selection_gate) and never changes it."""
```

`app/selection_diagnostics/config.py`:

```python
"""SEL-002 frozen configuration: every choice is fixed here before any SEL-002 number is computed."""
from __future__ import annotations

import dataclasses
from dataclasses import dataclass
from datetime import date

from app.purged_embargo_validation import DEFAULT_EMBARGO_DAYS
from app.selection_gate.config import SEL001_PARAMS, config_sha256
from app.selection_gate.dataset import FEATURE_COLUMNS
from app.settings import settings

EXPERIMENT_ID = "SEL-002"
CONFIG_VERSION = "SEL-002-CONFIG-001"

SEL001 = "SEL-001"
FULL = "FULL"
PERMUTATION = "PERM"
BASELINE_RANDOM = "BASELINE-RANDOM"
BASELINE_MOM_5 = "BASELINE-MOM-5"
BASELINE_MOM_20 = "BASELINE-MOM-20"
BASELINE_MEANREV = "BASELINE-MEANREV"
BASELINE_RELATIVE = "BASELINE-RELATIVE"
BASELINE_VOLADJ = "BASELINE-VOLADJ"
BASELINE_001 = "BASELINE-001"
BASELINE_NAMES = (BASELINE_RANDOM, BASELINE_MOM_5, BASELINE_MOM_20, BASELINE_MEANREV, BASELINE_RELATIVE,
                  BASELINE_VOLADJ, BASELINE_001)

GROUPS: dict[str, tuple[str, ...]] = {
    "RET": ("return_1d", "return_5d", "return_20d", "return_60d"),
    "TREND": ("sma20_distance", "sma50_distance", "macd", "macd_signal", "macd_hist"),
    "OSC": ("rsi_14",),
    "VOL": ("realized_vol_20d", "realized_vol_60d", "atr_percent"),
    "RANGE": ("high_60d_distance", "low_60d_distance"),
    "VOLUME": ("volume_ratio_20d", "volume_ratio_rank"),
    "XSEC": ("momentum_rank_20d", "rel_strength_market_20d", "rel_strength_sector_20d"),
    "REGIME": ("regime_bullish", "regime_bearish", "regime_neutral"),
}
PRICE_GROUPS = ("RET", "TREND", "OSC", "VOL", "RANGE")


def ordered_features(names) -> tuple[str, ...]:
    """A feature subset in FV-002 column order, so FULL equals SEL-001's own matrix."""
    wanted = set(names)
    unknown = wanted - set(FEATURE_COLUMNS)
    if unknown:
        raise ValueError(f"SEL-002: not FV-002 features: {sorted(unknown)}")
    return tuple(name for name in FEATURE_COLUMNS if name in wanted)


def _variants() -> dict[str, tuple[str, ...]]:
    price = ordered_features(name for group in PRICE_GROUPS for name in GROUPS[group])
    variants = {
        "PRICE-ONLY": price,
        "VOLUME-ONLY": ordered_features(GROUPS["VOLUME"]),
        "PRICE+VOLUME": ordered_features(price + GROUPS["VOLUME"]),
    }
    for group, names in GROUPS.items():
        variants[f"LOGO-{group}"] = ordered_features(c for c in FEATURE_COLUMNS if c not in names)
    return variants


VARIANTS = _variants()  # FULL (all 23) is SEL-001 itself; it is refitted once per horizon only for the parity gate

REGIME_BUCKETS: dict[str, tuple[str, ...]] = {
    "direction": ("UP", "DOWN"),
    "volatility": ("LOW", "MID", "HIGH"),
    "dispersion": ("LOW", "MID", "HIGH"),
    "liquidity": ("LOW", "MID", "HIGH"),
    "fv002": ("BULLISH", "BEARISH", "NEUTRAL"),
}

# The SEL-DIAG-001 cache and the SPG-001 pre-merge evaluation it reproduces (spec §5).
PINNED_SNAPSHOT_SHA256 = "5f59b9bc929f5d0c3f8fcdada419d705f0e6e9a3c0e26efb72d79ae2f0579a8b"
PINNED_CACHE_SHA256 = {
    "dataset.pkl": "50035bae382cf525ece4643cfcf4cf876b9c61db3c4573110522ede73a6dc08e",
    "wf_h3.npz": "cda9efc995c1a71b8ee4dfe340357c0194f759f6c6ad822240c255a1bb898615",
    "wf_h5.npz": "916390230c695ed0af5a896340d6470ed5532b01094ffa37ed6c9a3b5e426204",
}
PINNED_DATASET_SHA256 = {
    3: "ab07215f9790f19cb2d5efaf3a269db1a83d107290fb2ec895fcdf2cbe887eb0",
    5: "a2381ab89b873aa9e364fb03924b443d73bc68ca2a05542c66aab24db54c9b68",
}
PINNED_SPG_WALK_FORWARD = {3: {"trades": 13758, "mean_excess": 0.0050488}, 5: {"trades": 10374, "mean_excess": 0.0087478}}
SPG_CODE_VERSION = "8944bf4"

# SPG-001 settings SEL-002 relies on; a different value means a different experiment.
FROZEN_SETTINGS = {
    "selection_top_k": 10,
    "selection_round_trip_cost": 0.0030,
    "selection_bootstrap_draws": 10000,
    "selection_bootstrap_seed": 42,
    "selection_min_session_stocks": 100,
    "selection_min_history_bars": 60,
    "selection_min_median_traded_value_20d": 10000000.0,
    "selection_min_benchmark_stocks": 100,
    "selection_min_plausible_return": -0.60,
    "selection_max_plausible_return": 1.50,
    "selection_wf_first_test_month": "2018-01",
    "selection_min_fit_rows": 100000,
}
FROZEN_EMBARGO_SESSIONS = 2


@dataclass(frozen=True)
class DiagnosticConfig:
    horizons: tuple[int, ...] = (3, 5)
    today: date = date(2026, 10, 1)
    first_test_month: str = "2018-01"
    exam_start: date = date(2026, 8, 1)
    expected_holdout_month: tuple[int, int] = (2026, 8)
    k_values: tuple[int, ...] = (1, 5, 10, 25, 50)
    k_primary: int = 10
    cost: float = 0.0030
    bootstrap_draws: int = 10_000
    bootstrap_seed: int = 42
    random_seed: int = 42
    random_distribution_seeds: tuple[int, ...] = tuple(range(1, 21))
    permutation_seeds: tuple[int, ...] = tuple(range(1, 9))
    selection_null_draws: int = 1000
    selection_null_seed: int = 42
    bucket_edges: tuple[float, ...] = (0, 1, 5, 10, 20, 30, 40, 50, 60, 70, 80, 90, 100)
    top_fraction: float = 0.01
    wide_fraction: float = 0.05
    parity_tolerance: float = 1e-6
    variants: tuple[str, ...] = tuple(VARIANTS)

    def snapshot(self) -> dict:
        values = dataclasses.asdict(self)
        values.update(
            today=self.today.isoformat(), exam_start=self.exam_start.isoformat(), experiment=EXPERIMENT_ID,
            config_version=CONFIG_VERSION, embargo_sessions=DEFAULT_EMBARGO_DAYS, sel001_params=dict(SEL001_PARAMS),
            feature_columns=list(FEATURE_COLUMNS), groups={k: list(v) for k, v in GROUPS.items()},
            variant_features={name: list(VARIANTS[name]) for name in self.variants},
            regime_buckets={k: list(v) for k, v in REGIME_BUCKETS.items()}, frozen_settings=dict(FROZEN_SETTINGS),
        )
        return values

    def sha256(self) -> str:
        return config_sha256(self.snapshot())


def check_settings(cfg: DiagnosticConfig, source=settings) -> None:
    """Refuse to run when SPG-001's settings or the embargo differ from the frozen values (spec §12)."""
    wrong = {name: getattr(source, name) for name, value in FROZEN_SETTINGS.items() if getattr(source, name) != value}
    if DEFAULT_EMBARGO_DAYS != FROZEN_EMBARGO_SESSIONS:
        wrong["DEFAULT_EMBARGO_DAYS"] = DEFAULT_EMBARGO_DAYS
    if cfg.cost != source.selection_round_trip_cost:
        wrong["cost"] = cfg.cost
    if (cfg.bootstrap_draws, cfg.bootstrap_seed) != (source.selection_bootstrap_draws, source.selection_bootstrap_seed):
        wrong["bootstrap"] = (cfg.bootstrap_draws, cfg.bootstrap_seed)
    if cfg.first_test_month != source.selection_wf_first_test_month:
        wrong["first_test_month"] = cfg.first_test_month
    if wrong:
        raise ValueError(f"SEL-002: settings differ from the frozen configuration: {wrong}")
```

`app/selection_diagnostics/files.py`:

```python
"""SEL-002 file helpers: hashes, atomic writes, and JSON that numpy values survive."""
from __future__ import annotations

import hashlib
import json
import math
import os
import pathlib
import pickle

import numpy as np

_CHUNK = 1 << 20


def file_sha256(path) -> str:
    digest = hashlib.sha256()
    with open(path, "rb") as fh:
        for block in iter(lambda: fh.read(_CHUNK), b""):
            digest.update(block)
    return digest.hexdigest()


def jsonable(value):
    if isinstance(value, dict):
        return {str(k): jsonable(v) for k, v in value.items()}
    if isinstance(value, (list, tuple)):
        return [jsonable(v) for v in value]
    if isinstance(value, np.bool_):
        return bool(value)
    if isinstance(value, np.integer):
        return int(value)
    if isinstance(value, (np.floating, float)):
        return None if math.isnan(value) or math.isinf(value) else float(value)
    return value


def write_json(path, payload) -> None:
    path = pathlib.Path(path)
    tmp = path.with_name(path.name + ".tmp")
    tmp.write_text(json.dumps(jsonable(payload), indent=2, sort_keys=True), encoding="utf-8")
    os.replace(tmp, path)


def read_json(path):
    return json.loads(pathlib.Path(path).read_text(encoding="utf-8"))


def write_pickle(path, payload) -> None:
    path = pathlib.Path(path)
    tmp = path.with_name(path.name + ".tmp")
    with open(tmp, "wb") as fh:
        pickle.dump(payload, fh, protocol=5)
    os.replace(tmp, path)


def read_pickle(path):
    with open(path, "rb") as fh:
        return pickle.load(fh)
```

- [ ] **Step 5: Run them; expect PASS** (`7 passed`)

```bash
python -m pytest tests/test_sel002_config.py -q -p no:cacheprovider
git diff --exit-code 8944bf4 -- app/selection_gate scripts/run_selection_gate.py
```

- [ ] **Step 6: Commit**

```bash
git add app/selection_diagnostics/__init__.py app/selection_diagnostics/config.py app/selection_diagnostics/files.py tests/test_sel002_config.py
git commit -m "SEL-002: frozen diagnostic configuration and file helpers"
```

---

### Task 2: Synthetic world and held-out restriction

**Files:**
- Create: `tests/_sel002_factories.py`, `app/selection_diagnostics/heldout.py`
- Test: `tests/test_sel002_heldout.py`

**Interfaces:**
- Consumes: `DiagnosticConfig` (Task 1); SPG-001 `SelectionDataset`, `plan_windows`, `Windows`, `LABEL_RESOLVED`.
- Produces:
  - `MASKED = "MASKED_SEL002"`, `HeldOutViolation(RuntimeError)`
  - `first_exam_index(dates, exam_start) -> int`
  - `restrict(dataset, exam_start) -> tuple[SelectionDataset, dict]`. The dict keys are `first_exam_session`, `first_exam_index`, `rows_dropped`, `entry_masked`, `h{h}_rows_masked` and `h{h}_benchmark_sessions_masked`.
  - `assert_restricted(dataset, exam_start) -> None`
  - `diagnostic_windows(dataset, horizon, cfg) -> Windows`
  - `check_tested(tested, horizon, first) -> None`
  - Test helpers: `synthetic_dataset(*, sessions=300, stocks=40, horizons=(3, 5), seed=7, signal=0.02) -> SelectionDataset`, `small_config(**overrides) -> DiagnosticConfig`, `weekdays`, `with_rows`, `TODAY`, `EXAM_START`, `FIRST_TEST_MONTH`

**Data dependencies:** the synthetic world only: 300 weekday sessions × 40 stocks, held-out month 2026-01.

**Compute:** < 5 s.

**Acceptance criteria:**
- `8 passed`.
- Held-out values (features, labels and benchmarks at or after the exam) cannot change the restricted data.
- A wrong exam month, a tested session reaching the exam, or a calendar without the exam month each raise `HeldOutViolation`.

- [ ] **Step 1: Write the failing tests**

`tests/test_sel002_heldout.py`:

```python
import dataclasses
from datetime import date

import numpy as np
import pandas as pd
import pytest

from app.selection_diagnostics.heldout import (
    MASKED, HeldOutViolation, assert_restricted, check_tested, diagnostic_windows, first_exam_index, restrict,
)
from app.selection_gate.constants import LABEL_RESOLVED
from app.selection_gate.dataset import FEATURE_COLUMNS
from tests._sel002_factories import EXAM_START, small_config, synthetic_dataset


@pytest.fixture(scope="module")
def world():
    return synthetic_dataset()


def test_rows_on_or_after_the_exam_start_are_dropped(world):
    restricted, counts = restrict(world, EXAM_START)
    first = first_exam_index(world.session_dates, EXAM_START)
    assert world.session_dates[first].isoformat() == "2026-01-01"
    assert restricted.rows.session_index.max() == first - 1
    assert counts["rows_dropped"] == int((world.rows.session_index >= first).sum())
    assert restricted.session_dates == world.session_dates  # the calendar only


def test_labels_and_benchmarks_reaching_the_exam_are_masked(world):
    restricted, _ = restrict(world, EXAM_START)
    first = first_exam_index(world.session_dates, EXAM_START)
    for h in (3, 5):
        reach = restricted.rows.session_index + h >= first
        assert restricted.rows.loc[reach, f"gross_return_{h}"].isna().all()
        assert (restricted.rows.loc[reach, f"label_status_{h}"] == MASKED).all()
        assert (restricted.rows.loc[~reach, f"label_status_{h}"] == LABEL_RESOLVED).all()
        bench = restricted.benchmarks[h]
        late = bench.session_index + h >= first
        assert not bench.loc[late, "benchmarkable"].any() and bench.loc[late, "benchmark_return"].isna().all()
        assert bench.session_index.max() == first - 1
    assert restricted.rows.loc[restricted.rows.session_index + 1 >= first, "entry_open"].isna().all()
    assert_restricted(restricted, EXAM_START)


def test_restrict_is_idempotent(world):
    once, _ = restrict(world, EXAM_START)
    twice, _ = restrict(once, EXAM_START)
    pd.testing.assert_frame_equal(once.rows, twice.rows)
    for h in (3, 5):
        pd.testing.assert_frame_equal(once.benchmarks[h], twice.benchmarks[h])


def test_no_held_out_value_can_change_the_restricted_data(world):
    first = first_exam_index(world.session_dates, EXAM_START)
    rows = world.rows.copy()
    late = rows.session_index >= first
    rows.loc[late, list(FEATURE_COLUMNS)] = 1e6
    for h in (3, 5):
        reach = rows.session_index + h >= first
        rows.loc[reach, f"gross_return_{h}"] = 9.0
        rows.loc[reach, f"label_status_{h}"] = LABEL_RESOLVED
    benchmarks = {h: b.assign(benchmark_return=np.where(b.session_index + h >= first, 5.0, b.benchmark_return),
                              benchmarkable=True) for h, b in world.benchmarks.items()}
    poisoned = dataclasses.replace(world, rows=rows, benchmarks=benchmarks)
    clean, _ = restrict(world, EXAM_START)
    dirty, _ = restrict(poisoned, EXAM_START)
    pd.testing.assert_frame_equal(clean.rows, dirty.rows)
    for h in (3, 5):
        pd.testing.assert_frame_equal(clean.benchmarks[h], dirty.benchmarks[h])


def test_an_unrestricted_dataset_is_refused(world):
    with pytest.raises(HeldOutViolation):
        assert_restricted(world, EXAM_START)


def test_windows_must_match_the_frozen_exam_month(world):
    restricted, _ = restrict(world, EXAM_START)
    windows = diagnostic_windows(restricted, 3, small_config())
    assert windows.holdout_month == (2026, 1)
    assert windows.test_months[0] == (2025, 7) and windows.test_months[-1] == (2025, 12)
    with pytest.raises(HeldOutViolation):
        diagnostic_windows(restricted, 3, small_config(expected_holdout_month=(2025, 12)))


def test_a_tested_session_reaching_the_exam_is_refused(world):
    first = first_exam_index(world.session_dates, EXAM_START)
    check_tested([first - 4], 3, first)
    with pytest.raises(HeldOutViolation):
        check_tested([first - 3], 3, first)


def test_a_calendar_without_the_exam_month_is_refused(world):
    with pytest.raises(HeldOutViolation):
        restrict(world, date(2027, 1, 1))
```

- [ ] **Step 2: Run them; expect FAIL** (`ModuleNotFoundError: No module named 'app.selection_diagnostics.heldout'`)

```bash
python -m pytest tests/test_sel002_heldout.py -q -p no:cacheprovider
```

- [ ] **Step 3: Implement**

`tests/_sel002_factories.py`:

```python
"""SEL-002 test factories: a synthetic SEL-DS-001 world that needs no database."""
from __future__ import annotations

import dataclasses
from datetime import date, timedelta

import numpy as np
import pandas as pd

from app.selection_diagnostics.config import DiagnosticConfig
from app.selection_gate.constants import LABEL_NOT_YET_RESOLVABLE, LABEL_RESOLVED
from app.selection_gate.dataset import FEATURE_COLUMNS, SelectionDataset
from app.walk_forward_dataset import session_cutoff

# 300 weekday sessions 2025-01-01 .. 2026-02-24. With today=2026-03-02 the held-out month is 2026-01.
TODAY = date(2026, 3, 2)
EXAM_START = date(2026, 1, 1)
FIRST_TEST_MONTH = "2025-07"


def weekdays(start: date, count: int) -> list[date]:
    days, day = [], start
    while len(days) < count:
        if day.weekday() < 5:
            days.append(day)
        day += timedelta(days=1)
    return days


def small_config(**overrides) -> DiagnosticConfig:
    values = dict(today=TODAY, exam_start=EXAM_START, expected_holdout_month=(2026, 1), first_test_month=FIRST_TEST_MONTH,
                  k_values=(1, 2, 5), k_primary=2, bootstrap_draws=200, random_distribution_seeds=(1, 2),
                  permutation_seeds=(1, 2), selection_null_draws=50, variants=("PRICE-ONLY", "LOGO-RET"))
    values.update(overrides)
    return DiagnosticConfig(**values)


def synthetic_dataset(*, sessions: int = 300, stocks: int = 40, horizons=(3, 5), seed: int = 7,
                      signal: float = 0.02) -> SelectionDataset:
    """Every stock in U(D) every session; gross return = signal x return_5d + noise; last h sessions unresolvable."""
    dates = tuple(weekdays(date(2025, 1, 1), sessions))
    rng = np.random.default_rng(seed)
    n = sessions * stocks
    session_index = np.repeat(np.arange(sessions), stocks).astype(np.int64)
    rows = pd.DataFrame({"session_index": session_index, "stock_id": np.tile(np.arange(1, stocks + 1), sessions)})
    for name in FEATURE_COLUMNS:
        rows[name] = rng.normal(size=n).astype(np.float32)
    regime = rng.integers(0, 3, size=sessions)[session_index]
    for code, name in enumerate(("regime_bullish", "regime_bearish", "regime_neutral")):
        rows[name] = (regime == code).astype(np.float32)
    rows["realized_vol_20d"] = (np.abs(rows.realized_vol_20d) + 0.01).astype(np.float32)
    rows["atr_percent"] = (np.abs(rows.atr_percent) + 0.5).astype(np.float32)
    rows["baseline_score"] = rng.random(n)
    rows["baseline_score_live"] = rows.baseline_score
    rows["entry_open"] = 100.0
    benchmarks = {}
    for h in horizons:
        gross = signal * rows.return_5d.to_numpy(np.float64) + rng.normal(0.0, 0.02, n)
        resolved = session_index + h <= sessions - 1
        rows[f"exit_close_{h}"] = np.where(resolved, 100.0 * (1.0 + gross), np.nan)
        rows[f"gross_return_{h}"] = np.where(resolved, gross, np.nan)
        rows[f"label_status_{h}"] = np.where(resolved, LABEL_RESOLVED, LABEL_NOT_YET_RESOLVABLE).astype(object)
        records = []
        for i in range(sessions):
            part = rows[f"gross_return_{h}"].to_numpy()[session_index == i]
            labelled = int(np.isfinite(part).sum())
            records.append({"session_index": i, "session_date": dates[i], "eligible_count": stocks,
                            "labelled_count": labelled,
                            "unresolved": {} if labelled else {LABEL_NOT_YET_RESOLVABLE: stocks}, "excluded": {},
                            "benchmark_return": float(np.nanmean(part)) if labelled else np.nan,
                            "benchmarkable": labelled > 0})
        benchmarks[h] = pd.DataFrame.from_records(records)
    return SelectionDataset(
        anchors=tuple(session_cutoff(d) for d in dates), session_dates=dates, horizons=tuple(horizons), rows=rows,
        benchmarks=benchmarks, sha256_by_horizon={h: "s" * 64 for h in horizons}, stocks_by_year={2025: stocks, 2026: stocks},
    )


def with_rows(dataset: SelectionDataset, rows: pd.DataFrame) -> SelectionDataset:
    return dataclasses.replace(dataset, rows=rows)
```

`app/selection_diagnostics/heldout.py`:

```python
"""SEL-002 held-out protection: the exam month and every later month are removed before any computation.

Only the session calendar (dates, no values) is kept whole, so `plan_windows` reproduces SPG-001's windows."""
from __future__ import annotations

import dataclasses
from datetime import date
from typing import Iterable, Sequence

import numpy as np

from app.selection_gate.constants import LABEL_RESOLVED
from app.selection_gate.dataset import SelectionDataset
from app.selection_gate.validation import Windows, plan_windows

MASKED = "MASKED_SEL002"


class HeldOutViolation(RuntimeError):
    pass


def first_exam_index(dates: Sequence[date], exam_start: date) -> int:
    for index, day in enumerate(dates):
        if day >= exam_start:
            return index
    raise HeldOutViolation(f"SEL-002: no session on or after {exam_start}; the exam month cannot be located")


def restrict(dataset: SelectionDataset, exam_start: date) -> tuple[SelectionDataset, dict]:
    """Drop rows at or after the exam start and mask every label or benchmark whose window reaches it. Idempotent."""
    first = first_exam_index(dataset.session_dates, exam_start)
    rows = dataset.rows.loc[dataset.rows.session_index < first].copy()
    counts = {"first_exam_session": dataset.session_dates[first].isoformat(), "first_exam_index": first,
              "rows_dropped": int(len(dataset.rows) - len(rows))}
    entry = (rows.session_index + 1 >= first).to_numpy()
    rows.loc[entry, "entry_open"] = np.nan
    counts["entry_masked"] = int(entry.sum())
    benchmarks = {}
    for h in dataset.horizons:
        reach = (rows.session_index + h >= first).to_numpy()
        rows.loc[reach, [f"exit_close_{h}", f"gross_return_{h}"]] = np.nan
        rows.loc[reach, f"label_status_{h}"] = MASKED
        bench = dataset.benchmarks[h]
        bench = bench.loc[bench.session_index < first].copy()
        masked = (bench.session_index + h >= first).to_numpy()
        bench.loc[masked, "benchmark_return"] = np.nan
        bench.loc[masked, "benchmarkable"] = False
        bench.loc[masked, "labelled_count"] = 0
        bench["unresolved"] = [{} if m else u for m, u in zip(masked, bench.unresolved)]
        benchmarks[h] = bench
        counts[f"h{h}_rows_masked"] = int(reach.sum())
        counts[f"h{h}_benchmark_sessions_masked"] = int(masked.sum())
    return dataclasses.replace(dataset, rows=rows, benchmarks=benchmarks), counts


def assert_restricted(dataset: SelectionDataset, exam_start: date) -> None:
    first = first_exam_index(dataset.session_dates, exam_start)
    si = dataset.rows.session_index
    if (si >= first).any():
        raise HeldOutViolation("SEL-002: a row at or after the exam start is present")
    for h in dataset.horizons:
        reach = (si + h >= first).to_numpy()
        if dataset.rows.loc[reach, f"gross_return_{h}"].notna().any() or \
                (dataset.rows.loc[reach, f"label_status_{h}"] == LABEL_RESOLVED).any():
            raise HeldOutViolation(f"SEL-002: an h={h} label reaches the exam month")
        bench = dataset.benchmarks[h]
        late = (bench.session_index + h >= first).to_numpy()
        if (bench.session_index >= first).any() or bench.loc[late, "benchmarkable"].astype(bool).any():
            raise HeldOutViolation(f"SEL-002: an h={h} benchmark reaches the exam month")


def diagnostic_windows(dataset: SelectionDataset, horizon: int, cfg) -> Windows:
    """SPG-001's walk-forward windows, refused unless the exam month is exactly the frozen one."""
    windows = plan_windows(dataset.session_dates, horizon, today=cfg.today, first_test_month=cfg.first_test_month)
    first = first_exam_index(dataset.session_dates, cfg.exam_start)
    if windows.holdout_month != tuple(cfg.expected_holdout_month):
        raise HeldOutViolation(f"SEL-002: held-out month {windows.holdout_month} != {cfg.expected_holdout_month}")
    if windows.protected is None or windows.protected[0] != first:
        raise HeldOutViolation("SEL-002: the protected span does not start at the exam start")
    if any(windows.month_indices[m][-1] >= first for m in windows.test_months):
        raise HeldOutViolation("SEL-002: a walk-forward test month reaches the exam month")
    return windows


def check_tested(tested: Iterable[int], horizon: int, first: int) -> None:
    late = [int(i) for i in tested if int(i) + horizon >= first]
    if late:
        raise HeldOutViolation(f"SEL-002: {len(late)} tested sessions reach the exam month at h={horizon}")
```

- [ ] **Step 4: Run them; expect PASS** (`8 passed`)

```bash
python -m pytest tests/test_sel002_heldout.py -q -p no:cacheprovider
git diff --exit-code 8944bf4 -- app/selection_gate scripts/run_selection_gate.py
```

- [ ] **Step 5: Commit**

```bash
git add app/selection_diagnostics/heldout.py tests/_sel002_factories.py tests/test_sel002_heldout.py
git commit -m "SEL-002: remove the held-out months before any computation"
```

---

### Task 3: Database write guard and isolation

**Files:**
- Create: `app/selection_diagnostics/guards.py`
- Test: `tests/test_sel002_guards.py`

**Interfaces:**
- Consumes: SQLAlchemy `Engine` events; `app.models` (tests only).
- Produces: `DatabaseWriteRefused(RuntimeError)`, the context manager `no_database_writes()`, `PROTECTED_TABLES`, `PROTECTED_PREFIXES = ("tg_holdout_",)`, `protected_tables(engine) -> list[str]` and `table_digest(engine) -> dict[str, dict]` (`{table: {"rows", "sha256"}}`).

**Data dependencies:** temporary SQLite files created with `Base.metadata.create_all`.

**Compute:** < 10 s.

**Acceptance criteria:**
- `5 passed`.
- Inside the guard, every write (ORM flush, CREATE or DELETE) raises; reads still work.
- No SEL-002 module imports an SPG-001 writer, `app.db`, `SessionLocal` or anything from `app.trade_geometry` (TG-001).
- Nothing under `app/`, `api/`, `scripts/` or `deploy/k8s/base` references SEL-002.

- [ ] **Step 1: Write the failing tests**

`tests/test_sel002_guards.py`:

```python
import ast
import pathlib
from datetime import datetime, timezone

import pytest
from sqlalchemy import create_engine, func, select, text
from sqlalchemy.orm import sessionmaker

import app
import app.selection_diagnostics as package
from app.db import Base
from app.models import HoldoutWindowRegistry, SelectionGateDecision, SelectionHoldoutUsage
from app.selection_diagnostics.guards import DatabaseWriteRefused, no_database_writes, table_digest

REPO = pathlib.Path(app.__file__).resolve().parent.parent
PACKAGE = pathlib.Path(package.__file__).resolve().parent
SCRIPT = PACKAGE.parent.parent / "scripts" / "run_sel002_diagnostic.py"
AT = datetime(2026, 10, 1, tzinfo=timezone.utc)

FORBIDDEN_MODULES = {
    "app.db", "app.challenger_artefact", "app.schedule_orchestration", "app.selection_gate.holdout",
    "app.selection_gate.records", "app.selection_gate.shadow", "app.selection_gate.publication",
    "app.selection_gate.authorization", "app.recommendations", "app.marksy_tips", "app.trade_geometry",
}
FORBIDDEN_NAMES = {"run_selection_gate", "register_holdout", "consume_holdout", "write_decision", "write_trades",
                   "write_benchmarks", "SessionLocal", "save_artefact", "run_selection_shadow", "_evaluate_pair"}


@pytest.fixture
def engine(tmp_path):
    engine = create_engine(f"sqlite:///{tmp_path / 'guard.db'}")
    Base.metadata.create_all(engine)
    return engine


def test_registry_and_gate_tables_cannot_be_written_inside_the_guard(engine):
    maker = sessionmaker(bind=engine)
    with no_database_writes():
        with maker() as session:
            session.add(HoldoutWindowRegistry(label="SPG-001:SEL-001:h3:2026-09", registered_at=AT,
                                              registry_version="SPG-HOLDOUT-001"))
            with pytest.raises(DatabaseWriteRefused):
                session.flush()
            session.rollback()
        with engine.connect() as connection:
            with pytest.raises(DatabaseWriteRefused):
                connection.execute(text("CREATE TABLE tg_holdout_exams (id INTEGER)"))
            with pytest.raises(DatabaseWriteRefused):
                connection.execute(text("DELETE FROM selection_gate_decisions"))
            assert connection.execute(select(func.count()).select_from(SelectionHoldoutUsage)).scalar() == 0
    with maker() as session:
        assert session.scalar(select(func.count()).select_from(HoldoutWindowRegistry)) == 0
        assert session.scalar(select(func.count()).select_from(SelectionGateDecision)) == 0


def test_the_guard_is_removed_on_exit(engine):
    with no_database_writes():
        pass
    with engine.begin() as connection:
        connection.execute(text("CREATE TABLE scratch_after_guard (id INTEGER)"))


def test_table_digest_covers_protected_tables_and_moves_with_a_row(engine):
    before = table_digest(engine)
    assert {"selection_gate_decisions", "holdout_window_registry", "selection_holdout_usages"} <= set(before)
    with engine.begin() as connection:
        connection.execute(HoldoutWindowRegistry.__table__.insert().values(
            label="x", registered_at=AT, registry_version="SPG-HOLDOUT-001"))
    after = table_digest(engine)
    assert after["holdout_window_registry"]["rows"] == 1
    assert after["holdout_window_registry"]["sha256"] != before["holdout_window_registry"]["sha256"]


def _imports(path: pathlib.Path) -> tuple[set[str], set[str]]:
    modules, names = set(), set()
    for node in ast.walk(ast.parse(path.read_text(encoding="utf-8"))):
        if isinstance(node, ast.Import):
            modules |= {a.name for a in node.names}
        elif isinstance(node, ast.ImportFrom):
            base = node.module or ""
            if node.level and path.parent == PACKAGE:
                base = f"app.selection_diagnostics.{base}".rstrip(".")
            modules.add(base)
            names |= {a.name for a in node.names}
            modules |= {f"{base}.{a.name}" for a in node.names}
    return modules, names


def test_sel002_imports_no_spg_writer_and_no_write_session():
    sources = sorted(PACKAGE.glob("*.py")) + ([SCRIPT] if SCRIPT.exists() else [])
    for path in sources:
        modules, names = _imports(path)
        assert not modules & FORBIDDEN_MODULES, (path.name, modules & FORBIDDEN_MODULES)
        assert not names & FORBIDDEN_NAMES, (path.name, names & FORBIDDEN_NAMES)


def test_nothing_outside_sel002_imports_it_and_nothing_schedules_it():
    for directory in ("app", "api", "scripts"):
        for path in (REPO / directory).rglob("*.py"):
            if PACKAGE in path.parents or path.name == "run_sel002_diagnostic.py":
                continue
            assert "selection_diagnostics" not in path.read_text(encoding="utf-8", errors="ignore"), path
    for path in (REPO / "deploy" / "k8s" / "base").glob("*.yaml"):
        content = path.read_text(encoding="utf-8").lower()
        assert "sel002" not in content and "sel-002" not in content, path
```

- [ ] **Step 2: Run them; expect FAIL** (`ModuleNotFoundError: No module named 'app.selection_diagnostics.guards'`)

```bash
python -m pytest tests/test_sel002_guards.py -q -p no:cacheprovider
```

- [ ] **Step 3: Implement**

`app/selection_diagnostics/guards.py`:

```python
"""SEL-002 writes nothing to any database: inside `no_database_writes()` every write statement raises."""
from __future__ import annotations

import hashlib
from contextlib import contextmanager

from sqlalchemy import event, inspect, text
from sqlalchemy.engine import Engine

WRITE_VERBS = frozenset({"INSERT", "UPDATE", "DELETE", "REPLACE", "MERGE", "UPSERT", "CREATE", "DROP", "ALTER",
                         "TRUNCATE", "VACUUM", "REINDEX", "ATTACH"})
# SPG-001 decisions and evidence, every holdout registry, and TG-001's future exam tables (spec §4).
PROTECTED_TABLES = ("selection_gate_decisions", "selection_gate_trades", "selection_benchmark_sessions",
                    "selection_shadow_scores", "holdout_window_registry", "holdout_usage_records",
                    "selection_holdout_usages", "model_artefacts", "predictions")
PROTECTED_PREFIXES = ("tg_holdout_",)


class DatabaseWriteRefused(RuntimeError):
    pass


def _refuse(conn, cursor, statement, parameters, context, executemany):
    words = statement.lstrip().split(None, 1)
    head = words[0].upper() if words else ""
    if head in WRITE_VERBS or (head == "WITH" and any(v in statement.upper() for v in ("INSERT", "UPDATE", "DELETE"))):
        raise DatabaseWriteRefused(f"SEL-002 is read-only; refused: {statement[:120]}")


@contextmanager
def no_database_writes():
    event.listen(Engine, "before_cursor_execute", _refuse)
    try:
        yield
    finally:
        event.remove(Engine, "before_cursor_execute", _refuse)


def protected_tables(engine) -> list[str]:
    names = inspect(engine).get_table_names()
    return sorted(n for n in names if n in PROTECTED_TABLES or n.startswith(PROTECTED_PREFIXES))


def table_digest(engine) -> dict[str, dict]:
    """Row count and SHA-256 of every protected table's rows, ordered by every column."""
    out = {}
    with engine.connect() as connection:
        for name in protected_tables(engine):
            columns = [c["name"] for c in inspect(engine).get_columns(name)]
            order = ", ".join(f'"{c}"' for c in columns)
            rows = connection.execute(text(f'SELECT * FROM "{name}" ORDER BY {order}')).all()
            digest = hashlib.sha256("\n".join(repr(tuple(r)) for r in rows).encode("utf-8")).hexdigest()
            out[name] = {"rows": len(rows), "sha256": digest}
    return out
```

- [ ] **Step 4: Run them; expect PASS** (`5 passed`)

```bash
python -m pytest tests/test_sel002_guards.py -q -p no:cacheprovider
git diff --exit-code 8944bf4 -- app/selection_gate scripts/run_selection_gate.py
```

- [ ] **Step 5: Commit**

```bash
git add app/selection_diagnostics/guards.py tests/test_sel002_guards.py
git commit -m "SEL-002: refuse every database write and keep the diagnostic off every serving path"
```

---

### Task 4: Session-level block bootstrap

**Files:**
- Create: `app/selection_diagnostics/bootstrap.py`
- Test: `tests/test_sel002_bootstrap.py`

**Interfaces:**
- Consumes: SPG-001 `block_bootstrap_ci(session_sums, session_counts, *, block_length, draws, seed) -> tuple[float, float] | None`.
- Produces: `session_mean_ci(values, *, block_length, draws, seed)`, `trade_weighted_ci(session_sums, session_counts, *, block_length, draws, seed)` and `paired_block_bootstrap_ci(sums_a, counts_a, sums_b, counts_b, *, block_length, draws, seed)`. Each returns `(low, high)`, or `None` when m < 2L.

**Data dependencies:** none.

**Compute:** < 5 s.

**Acceptance criteria:**
- `6 passed`.
- The paired CI against a zero strategy equals SPG-001's `block_bootstrap_ci` exactly.
- Doubling a session's trades leaves the CI unchanged: the session is the unit.

- [ ] **Step 1: Write the failing tests**

`tests/test_sel002_bootstrap.py`:

```python
import numpy as np

from app.selection_diagnostics.bootstrap import paired_block_bootstrap_ci, session_mean_ci, trade_weighted_ci
from app.selection_gate.statistics import block_bootstrap_ci

RNG = np.random.default_rng(3)
SUMS = RNG.normal(0.01, 0.05, 60)
COUNTS = RNG.integers(1, 11, 60).astype(float)


def test_trade_weighted_ci_is_spg_001_exactly():
    assert trade_weighted_ci(SUMS, COUNTS, block_length=3, draws=500, seed=42) == \
        block_bootstrap_ci(SUMS, COUNTS, block_length=3, draws=500, seed=42)


def test_session_mean_ci_weights_every_session_equally():
    values = SUMS / COUNTS
    assert session_mean_ci(values, block_length=3, draws=500, seed=42) == \
        block_bootstrap_ci(values, np.ones(60), block_length=3, draws=500, seed=42)


def test_paired_ci_against_a_zero_strategy_equals_spg_001_on_the_same_blocks():
    zero_sums, one_counts = np.zeros(60), np.ones(60)
    assert paired_block_bootstrap_ci(SUMS, COUNTS, zero_sums, one_counts, block_length=3, draws=500, seed=42) == \
        block_bootstrap_ci(SUMS, COUNTS, block_length=3, draws=500, seed=42)


def test_paired_ci_of_a_strategy_against_itself_is_zero():
    assert paired_block_bootstrap_ci(SUMS, COUNTS, SUMS, COUNTS, block_length=5, draws=300, seed=1) == (0.0, 0.0)


def test_bootstrap_is_deterministic_and_undefined_below_two_blocks():
    a = paired_block_bootstrap_ci(SUMS, COUNTS, SUMS * 0.5, COUNTS, block_length=3, draws=300, seed=42)
    assert a == paired_block_bootstrap_ci(SUMS, COUNTS, SUMS * 0.5, COUNTS, block_length=3, draws=300, seed=42)
    assert a != paired_block_bootstrap_ci(SUMS, COUNTS, SUMS * 0.5, COUNTS, block_length=3, draws=300, seed=43)
    assert paired_block_bootstrap_ci(SUMS[:5], COUNTS[:5], SUMS[:5], COUNTS[:5], block_length=3, draws=10, seed=1) is None
    assert session_mean_ci(SUMS[:5], block_length=3, draws=10, seed=1) is None


def test_the_session_is_the_unit_not_the_row():
    """Doubling every session's trades (sums and counts) changes nothing: rows add no independent evidence."""
    assert trade_weighted_ci(SUMS * 2, COUNTS * 2, block_length=3, draws=500, seed=42) == \
        trade_weighted_ci(SUMS, COUNTS, block_length=3, draws=500, seed=42)
```

- [ ] **Step 2: Run them; expect FAIL** (`ModuleNotFoundError: No module named 'app.selection_diagnostics.bootstrap'`)

```bash
python -m pytest tests/test_sel002_bootstrap.py -q -p no:cacheprovider
```

- [ ] **Step 3: Implement**

`app/selection_diagnostics/bootstrap.py`:

```python
"""SEL-002 statistics: SPG-001's session-level circular block bootstrap. Stock rows of one session are never independent."""
from __future__ import annotations

import math
from typing import Sequence

import numpy as np

from app.selection_gate.statistics import block_bootstrap_ci

_DRAW_CHUNK = 500


def session_mean_ci(values: Sequence[float], *, block_length: int, draws: int, seed: int):
    """Equal-weight mean of one value per session (sessions in ascending order); None when m < 2L."""
    v = np.asarray(values, dtype=float)
    return block_bootstrap_ci(v, np.ones(len(v)), block_length=block_length, draws=draws, seed=seed)


def trade_weighted_ci(session_sums: Sequence[float], session_counts: Sequence[int], *, block_length: int, draws: int,
                      seed: int):
    """Trade-weighted mean over sessions (SPG-001 §11 exactly)."""
    return block_bootstrap_ci(session_sums, session_counts, block_length=block_length, draws=draws, seed=seed)


def paired_block_bootstrap_ci(sums_a: Sequence[float], counts_a: Sequence[int], sums_b: Sequence[float],
                              counts_b: Sequence[int], *, block_length: int, draws: int, seed: int):
    """Difference of two trade-weighted means, both resampled on the same session blocks; SPG-001 draw mechanics."""
    sa, na, sb, nb = (np.asarray(x, dtype=float) for x in (sums_a, counts_a, sums_b, counts_b))
    m = len(sa)
    if not len(na) == len(sb) == len(nb) == m:
        raise ValueError("SEL-002: paired series must cover the same sessions")
    if block_length < 1 or m < 2 * block_length:
        return None
    blocks = math.ceil(m / block_length)
    starts = np.random.default_rng(seed).integers(0, m, size=(draws, blocks))
    offsets = np.arange(block_length)
    diffs = np.empty(draws)
    for first in range(0, draws, _DRAW_CHUNK):
        part = starts[first:first + _DRAW_CHUNK]
        index = ((part[:, :, None] + offsets) % m).reshape(len(part), blocks * block_length)[:, :m]
        count_a, count_b = na[index].sum(axis=1), nb[index].sum(axis=1)
        if (count_a == 0).any() or (count_b == 0).any():
            return None
        diffs[first:first + len(part)] = sa[index].sum(axis=1) / count_a - sb[index].sum(axis=1) / count_b
    low, high = np.percentile(diffs, [2.5, 97.5], method="linear")
    return float(low), float(high)
```

- [ ] **Step 4: Run them; expect PASS** (`6 passed`)

```bash
python -m pytest tests/test_sel002_bootstrap.py -q -p no:cacheprovider
git diff --exit-code 8944bf4 -- app/selection_gate scripts/run_selection_gate.py
```

- [ ] **Step 5: Commit**

```bash
git add app/selection_diagnostics/bootstrap.py tests/test_sel002_bootstrap.py
git commit -m "SEL-002: session-level and paired block bootstrap on SPG-001 mechanics"
```

---

### Task 5: Baselines and the permutation target

**Files:**
- Create: `app/selection_diagnostics/strategies.py`
- Test: `tests/test_sel002_strategies.py`

**Interfaces:**
- Consumes: the baseline names (Task 1); SPG-001 `runner._excess_target` (tests).
- Produces: `random_scores(index, seed) -> pd.Series`, `baseline_scores(rows, *, random_seed) -> dict[str, pd.Series]` (keys in `BASELINE_NAMES` order) and `permute_within_session(target, session_index, seed) -> pd.Series`.

**Data dependencies:** the synthetic world (60 × 20).

**Compute:** < 5 s.

**Acceptance criteria:**
- `4 passed`.
- The random baseline is deterministic per seed.
- Baselines are exactly the spec §6.4 columns and never read labels.
- A permutation is deterministic per seed, keeps each session's multiset of values, and keeps NaN positions.

- [ ] **Step 1: Write the failing tests**

`tests/test_sel002_strategies.py`:

```python
import numpy as np
import pandas as pd
import pytest

from app.selection_diagnostics.config import BASELINE_NAMES
from app.selection_diagnostics.strategies import baseline_scores, permute_within_session, random_scores
from app.selection_gate.runner import _excess_target
from tests._sel002_factories import synthetic_dataset


@pytest.fixture(scope="module")
def world():
    return synthetic_dataset(sessions=60, stocks=20)


def test_random_baseline_is_deterministic_per_seed(world):
    a, b = random_scores(world.rows.index, 42), random_scores(world.rows.index, 42)
    pd.testing.assert_series_equal(a, b)
    assert not np.array_equal(a.to_numpy(), random_scores(world.rows.index, 1).to_numpy())
    assert ((a >= 0) & (a < 1)).all()


def test_baselines_are_the_predeclared_point_in_time_columns(world):
    rows = world.rows.copy()
    rows.loc[rows.index[:5], "realized_vol_20d"] = [0.0, -1.0, np.nan, 0.5, 0.25]
    scores = baseline_scores(rows, random_seed=42)
    assert tuple(scores) == BASELINE_NAMES
    np.testing.assert_array_equal(scores["BASELINE-MOM-5"], rows.return_5d.astype(float))
    np.testing.assert_array_equal(scores["BASELINE-MOM-20"], rows.return_20d.astype(float))
    np.testing.assert_array_equal(scores["BASELINE-MEANREV"], -rows.sma20_distance.astype(float))
    np.testing.assert_array_equal(scores["BASELINE-RELATIVE"], rows.rel_strength_market_20d.astype(float))
    np.testing.assert_array_equal(scores["BASELINE-001"], rows.baseline_score.astype(float))
    voladj = scores["BASELINE-VOLADJ"]
    assert voladj.iloc[:3].isna().all()
    assert voladj.iloc[3] == pytest.approx(float(rows.return_20d.iloc[3]) / 0.5)


def test_baselines_never_read_labels(world):
    rows = world.rows.copy()
    before = baseline_scores(rows, random_seed=42)
    for h in (3, 5):
        rows[f"gross_return_{h}"] = rows[f"gross_return_{h}"].sample(frac=1.0, random_state=1).to_numpy()
    after = baseline_scores(rows, random_seed=42)
    for name in before:
        pd.testing.assert_series_equal(before[name], after[name])


def test_permutation_is_deterministic_per_seed_and_stays_within_each_session(world):
    target = _excess_target(world.rows, world.benchmarks[3], 3)
    si = world.rows.session_index.to_numpy()
    first = permute_within_session(target, si, 1)
    pd.testing.assert_series_equal(first, permute_within_session(target, si, 1))
    assert not np.array_equal(first.to_numpy(), permute_within_session(target, si, 2).to_numpy(), equal_nan=True)
    assert np.array_equal(first.isna().to_numpy(), target.isna().to_numpy())
    for session in np.unique(si):
        mine = si == session
        np.testing.assert_array_equal(np.sort(first[mine].dropna()), np.sort(target[mine].dropna()))
    assert (first.dropna() != target.dropna()).mean() > 0.5
```

- [ ] **Step 2: Run them; expect FAIL** (`ModuleNotFoundError: No module named 'app.selection_diagnostics.strategies'`)

```bash
python -m pytest tests/test_sel002_strategies.py -q -p no:cacheprovider
```

- [ ] **Step 3: Implement**

`app/selection_diagnostics/strategies.py`:

```python
"""SEL-002 §6.4 baselines (no fitted parameter, FV-002 features at D) and the §6.6 label-permutation target."""
from __future__ import annotations

import numpy as np
import pandas as pd

from .config import (
    BASELINE_001, BASELINE_MEANREV, BASELINE_MOM_5, BASELINE_MOM_20, BASELINE_RANDOM, BASELINE_RELATIVE,
    BASELINE_VOLADJ,
)


def random_scores(index: pd.Index, seed: int) -> pd.Series:
    """One uniform [0, 1) score per row, in row order; never reads a label."""
    return pd.Series(np.random.default_rng(seed).random(len(index)), index=index, dtype=float)


def baseline_scores(rows: pd.DataFrame, *, random_seed: int) -> dict[str, pd.Series]:
    def column(name: str) -> pd.Series:
        return rows[name].astype(np.float64)

    vol = column("realized_vol_20d")
    return {
        BASELINE_RANDOM: random_scores(rows.index, random_seed),
        BASELINE_MOM_5: column("return_5d"),
        BASELINE_MOM_20: column("return_20d"),
        BASELINE_MEANREV: -column("sma20_distance"),
        BASELINE_RELATIVE: column("rel_strength_market_20d"),
        BASELINE_VOLADJ: (column("return_20d") / vol).where(vol > 0),  # NaN rows are never selected
        BASELINE_001: column("baseline_score"),
    }


def permute_within_session(target: pd.Series, session_index, seed: int) -> pd.Series:
    """Shuffle the defined targets across stocks within each session (ascending), one generator per seed."""
    values = target.to_numpy(dtype=float, copy=True)
    sessions = np.asarray(session_index)
    defined = np.flatnonzero(~np.isnan(values))
    order = defined[np.argsort(sessions[defined], kind="stable")]
    rng = np.random.default_rng(seed)
    if len(order):
        for group in np.split(order, np.flatnonzero(np.diff(sessions[order])) + 1):
            if len(group) > 1:
                values[group] = values[rng.permutation(group)]
    return pd.Series(values, index=target.index, name=target.name)
```

- [ ] **Step 4: Run them; expect PASS** (`4 passed`)

```bash
python -m pytest tests/test_sel002_strategies.py -q -p no:cacheprovider
git diff --exit-code 8944bf4 -- app/selection_gate scripts/run_selection_gate.py
```

- [ ] **Step 5: Commit**

```bash
git add app/selection_diagnostics/strategies.py tests/test_sel002_strategies.py
git commit -m "SEL-002: point-in-time baselines and the within-session label permutation"
```

---

### Task 6: Ranking quality and prediction buckets

**Files:**
- Create: `app/selection_diagnostics/ranking.py`
- Test: `tests/test_sel002_ranking.py`

**Interfaces:**
- Consumes: `session_mean_ci` (Task 4); SPG-001 `runner._excess_target`.
- Produces:
  - `outcome_frame(dataset, horizon, tested) -> DataFrame[session_index, stock_id, x]`, indexed like `dataset.rows`
  - `scored(frame, scores, *, resolved_only=True) -> DataFrame` (adds `score`)
  - `correlation_by_session(session_index, a, b) -> Series` and `percentile_rank(frame) -> Series`
  - `bucket_of(p, edges) -> ndarray`, `bucket_labels(edges) -> tuple[str, ...]`, `spearman(a, b) -> float | None`
  - `summarize(values, *, horizon, draws, seed) -> dict` (`sessions`, `mean`, `median`, `share_positive`, `ci_low`, `ci_high`)
  - `session_metrics(f) -> DataFrame[ic, pearson, spread]`
  - `ranking_summary(metrics, *, horizon, draws, seed) -> {ic, pearson, spread}`
  - `bucket_table(f, *, horizon, cost, edges, draws, seed) -> {buckets: [12 dicts], monotonicity}`

**Data dependencies:** hand-built frames and the synthetic world.

**Compute:** < 5 s.

**Acceptance criteria:**
- `8 passed`.
- The bucket edges and labels are the 12 predeclared ones.
- IC is ±1 for a perfect ranker and its inverse.
- Pearson winsorises only x.
- Duplicating rows leaves the IC and its CI unchanged.

- [ ] **Step 1: Write the failing tests**

`tests/test_sel002_ranking.py`:

```python
import numpy as np
import pandas as pd
import pytest

from app.selection_diagnostics.ranking import (
    bucket_labels, bucket_of, bucket_table, outcome_frame, percentile_rank, ranking_summary, scored, session_metrics,
    spearman,
)
from tests._sel002_factories import synthetic_dataset

EDGES = (0, 1, 5, 10, 20, 30, 40, 50, 60, 70, 80, 90, 100)


def _frame(sessions=30, stocks=200, seed=0, noise=1.0):
    rng = np.random.default_rng(seed)
    n = sessions * stocks
    score = rng.normal(size=n)
    return pd.DataFrame({"session_index": np.repeat(np.arange(sessions), stocks),
                         "stock_id": np.tile(np.arange(stocks), sessions),
                         "score": score, "x": 0.01 * score + noise * 0.01 * rng.normal(size=n)})


def test_bucket_edges_are_the_predeclared_twelve():
    assert bucket_labels(EDGES) == ("Top 1%", "1-5%", "5-10%", "10-20%", "20-30%", "30-40%", "40-50%", "50-60%",
                                    "60-70%", "70-80%", "80-90%", "Bottom 10%")
    p = [0, 0.99, 1, 4.99, 5, 9.99, 10, 89.99, 90, 99.9]
    assert bucket_of(p, EDGES).tolist() == [0, 0, 1, 1, 2, 2, 3, 10, 11, 11]


def test_percentile_rank_puts_the_top_score_at_zero_and_breaks_ties_by_stock_id():
    f = pd.DataFrame({"session_index": [0, 0, 0, 0], "stock_id": [4, 2, 3, 1], "score": [1.0, 5.0, 5.0, 0.0],
                      "x": [0.0] * 4})
    assert percentile_rank(f).tolist() == [50.0, 0.0, 25.0, 75.0]


def test_ic_is_one_for_a_perfect_ranker_and_minus_one_for_its_inverse():
    f = _frame(noise=0.0)
    assert np.allclose(session_metrics(f).ic, 1.0)
    assert np.allclose(session_metrics(f.assign(score=-f.score)).ic, -1.0)


def test_pearson_winsorises_x_but_not_the_score():
    f = _frame(sessions=1, stocks=300)
    f.loc[0, "x"] = 50.0
    lo, hi = np.percentile(f.x, [1, 99])
    expected = np.corrcoef(f.score, f.x.clip(lo, hi))[0, 1]
    assert session_metrics(f).pearson.iloc[0] == pytest.approx(expected, rel=1e-9)


def test_spread_is_top_decile_minus_bottom_decile():
    f = _frame(sessions=1, stocks=100)
    order = f.sort_values("score", ascending=False)
    expected = order.x.iloc[:10].mean() - order.x.iloc[-10:].mean()
    assert session_metrics(f).spread.iloc[0] == pytest.approx(expected)


def test_a_monotone_signal_has_monotonicity_one_and_net_is_mean_minus_cost():
    table = bucket_table(_frame(noise=0.0), horizon=3, cost=0.003, edges=EDGES, draws=200, seed=42)
    assert table["monotonicity"] == pytest.approx(1.0)
    top = table["buckets"][0]
    assert top["bucket"] == "Top 1%" and top["observations"] == 30 * 2 and top["sessions"] == 30
    assert top["mean_net_excess"] == pytest.approx(top["mean_x"] - 0.003)
    assert sum(b["observations"] for b in table["buckets"]) == 30 * 200
    assert spearman([1, 2, 3], [1, 1, 1]) is None


def test_the_session_is_the_bootstrap_unit():
    """Duplicating every row of every session leaves IC values and their CI unchanged."""
    f = _frame()
    doubled = pd.concat([f, f.assign(stock_id=f.stock_id + 10_000)], ignore_index=True)
    a = ranking_summary(session_metrics(f), horizon=3, draws=300, seed=42)["ic"]
    b = ranking_summary(session_metrics(doubled), horizon=3, draws=300, seed=42)["ic"]
    assert a["sessions"] == b["sessions"]
    assert a["mean"] == pytest.approx(b["mean"], abs=1e-12)
    assert a["ci_low"] == pytest.approx(b["ci_low"], abs=1e-12) and a["ci_high"] == pytest.approx(b["ci_high"], abs=1e-12)


def test_outcome_frame_holds_every_tested_row_and_x_only_where_resolved():
    world = synthetic_dataset(sessions=40, stocks=10)
    frame = outcome_frame(world, 3, tested=range(30, 40))
    assert len(frame) == 10 * 10
    assert frame.x.isna().sum() == 3 * 10  # sessions 37..39 cannot resolve at h=3
    assert len(scored(frame, world.rows.return_5d.astype(float))) == 7 * 10
    assert len(scored(frame, world.rows.return_5d.astype(float), resolved_only=False)) == 10 * 10
```

- [ ] **Step 2: Run them; expect FAIL** (`ModuleNotFoundError: No module named 'app.selection_diagnostics.ranking'`)

```bash
python -m pytest tests/test_sel002_ranking.py -q -p no:cacheprovider
```

- [ ] **Step 3: Implement**

`app/selection_diagnostics/ranking.py`:

```python
"""SEL-002 §6.2/§6.3: prediction buckets and per-session ranking quality over resolved U(D) rows."""
from __future__ import annotations

from typing import Iterable, Sequence

import numpy as np
import pandas as pd

from app.selection_gate.runner import _excess_target

from .bootstrap import session_mean_ci

_MIN_SESSION_ROWS = 3
_VARIANCE_FLOOR = 1e-18


def outcome_frame(dataset, horizon: int, tested: Iterable[int]) -> pd.DataFrame:
    """Every U(D) row of a tested session; x = r - b(D,h), NaN unless RESOLVED in a benchmarkable session."""
    rows = dataset.rows
    x = _excess_target(rows, dataset.benchmarks[horizon], horizon)
    keep = rows.session_index.isin(sorted({int(i) for i in tested})).to_numpy()
    return pd.DataFrame({"session_index": rows.session_index.to_numpy()[keep].astype(np.int64),
                         "stock_id": rows.stock_id.to_numpy()[keep].astype(np.int64),
                         "x": x.to_numpy(dtype=float)[keep]}, index=rows.index[keep])


def scored(frame: pd.DataFrame, scores: pd.Series, *, resolved_only: bool = True) -> pd.DataFrame:
    f = frame.assign(score=scores.reindex(frame.index).to_numpy(dtype=float))
    keep = f.score.notna()
    if resolved_only:
        keep &= f.x.notna()
    return f[keep]


def correlation_by_session(session_index, a, b) -> pd.Series:
    """Pearson correlation within each session; NaN with fewer than 3 rows or a constant side."""
    f = pd.DataFrame({"s": np.asarray(session_index), "a": np.asarray(a, dtype=float), "b": np.asarray(b, dtype=float)})
    g = f.groupby("s", sort=True)
    f["a"] = f.a - g.a.transform("mean")
    f["b"] = f.b - g.b.transform("mean")
    f["ab"], f["aa"], f["bb"] = f.a * f.b, f.a * f.a, f.b * f.b
    sums = f.groupby("s", sort=True)[["ab", "aa", "bb"]].sum()
    n = g.size()
    valid = (n >= _MIN_SESSION_ROWS) & (sums.aa > _VARIANCE_FLOOR) & (sums.bb > _VARIANCE_FLOOR)
    corr = sums.ab / np.sqrt(sums.aa * sums.bb)
    return corr.where(valid).astype(float).rename_axis("session_index")


def percentile_rank(frame: pd.DataFrame) -> pd.Series:
    """p = 100 * (rank - 1) / n per session; rank 1 is the highest score, ties by stock_id ascending (SPG order)."""
    ordered = frame.sort_values(["session_index", "score", "stock_id"], ascending=[True, False, True], kind="mergesort")
    g = ordered.groupby("session_index", sort=True)
    position = g.cumcount().to_numpy()
    size = g.score.transform("size").to_numpy()
    return pd.Series(100.0 * position / size, index=ordered.index).reindex(frame.index)


def bucket_of(p, edges: Sequence[float]) -> np.ndarray:
    return np.searchsorted(np.asarray(edges[1:-1], dtype=float), np.asarray(p, dtype=float), side="right")


def bucket_labels(edges: Sequence[float]) -> tuple[str, ...]:
    labels = [f"Top {edges[1]:g}%"]
    labels += [f"{lo:g}-{hi:g}%" for lo, hi in zip(edges[1:-2], edges[2:-1])]
    labels.append(f"Bottom {edges[-1] - edges[-2]:g}%")
    return tuple(labels)


def spearman(a, b) -> float | None:
    s = pd.DataFrame({"a": list(a), "b": list(b)}, dtype=float).dropna()
    if len(s) < _MIN_SESSION_ROWS:
        return None
    ra, rb = s.a.rank(), s.b.rank()
    if ra.nunique() < 2 or rb.nunique() < 2:
        return None
    return float(np.corrcoef(ra, rb)[0, 1])


def summarize(values: pd.Series, *, horizon: int, draws: int, seed: int) -> dict:
    v = values.dropna().sort_index()
    if v.empty:
        return {"sessions": 0, "mean": None, "median": None, "share_positive": None, "ci_low": None, "ci_high": None}
    ci = session_mean_ci(v.to_numpy(), block_length=horizon, draws=draws, seed=seed)
    return {"sessions": int(len(v)), "mean": float(v.mean()), "median": float(v.median()),
            "share_positive": float((v > 0).mean()), "ci_low": None if ci is None else ci[0],
            "ci_high": None if ci is None else ci[1]}


def session_metrics(f: pd.DataFrame) -> pd.DataFrame:
    """Per session: Spearman IC, Pearson with x winsorised at the session's 1st/99th percentiles, top-bottom decile spread."""
    g = f.groupby("session_index", sort=True)
    ic = correlation_by_session(f.session_index, g.score.rank(method="average"), g.x.rank(method="average"))
    low = f.session_index.map(g.x.quantile(0.01))
    high = f.session_index.map(g.x.quantile(0.99))
    pearson = correlation_by_session(f.session_index, f.score, f.x.clip(lower=low, upper=high))
    p = percentile_rank(f)
    top = f.x.where(p < 10).groupby(f.session_index).mean()
    bottom = f.x.where(p >= 90).groupby(f.session_index).mean()
    return pd.DataFrame({"ic": ic, "pearson": pearson, "spread": top - bottom}).sort_index()


def ranking_summary(metrics: pd.DataFrame, *, horizon: int, draws: int, seed: int) -> dict:
    return {name: summarize(metrics[name], horizon=horizon, draws=draws, seed=seed) for name in ("ic", "pearson", "spread")}


def bucket_table(f: pd.DataFrame, *, horizon: int, cost: float, edges: Sequence[float], draws: int, seed: int) -> dict:
    f = f.assign(bucket=bucket_of(percentile_rank(f), edges))
    per_session = f.groupby(["bucket", "session_index"], sort=True).x.mean()
    present = set(per_session.index.get_level_values("bucket"))
    buckets = []
    for b, label in enumerate(bucket_labels(edges)):
        part = f.x[f.bucket == b]
        v = per_session.xs(b, level="bucket").sort_index() if b in present else pd.Series(dtype=float)
        ci = session_mean_ci(v.to_numpy(), block_length=horizon, draws=draws, seed=seed) if len(v) else None
        mean = float(v.mean()) if len(v) else None
        buckets.append({
            "bucket": label, "lower": float(edges[b]), "upper": float(edges[b + 1]), "observations": int(len(part)),
            "sessions": int(len(v)), "mean_x": mean, "ci_low": None if ci is None else ci[0],
            "ci_high": None if ci is None else ci[1], "pooled_mean_x": float(part.mean()) if len(part) else None,
            "median_x": float(part.median()) if len(part) else None,
            "mean_net_excess": None if mean is None else mean - cost,
            "net_ci_low": None if ci is None else ci[0] - cost, "net_ci_high": None if ci is None else ci[1] - cost,
        })
    ordinal = list(range(len(buckets), 0, -1))  # Top bucket = highest ordinal
    return {"buckets": buckets, "monotonicity": spearman(ordinal, [b["mean_x"] for b in buckets])}
```

- [ ] **Step 4: Run them; expect PASS** (`8 passed`)

```bash
python -m pytest tests/test_sel002_ranking.py -q -p no:cacheprovider
git diff --exit-code 8944bf4 -- app/selection_gate scripts/run_selection_gate.py
```

- [ ] **Step 5: Commit**

```bash
git add app/selection_diagnostics/ranking.py tests/test_sel002_ranking.py
git commit -m "SEL-002: per-session IC, Pearson, decile spread and the 12 prediction buckets"
```

---

### Task 7: Strategy harness on SPG-001's run_stage

**Files:**
- Create: `app/selection_diagnostics/harness.py`
- Test: `tests/test_sel002_harness.py`

**Interfaces:**
- Consumes: SPG-001 `run_stage(...) -> StageRun` (`.statistics`, `.trades`, `.universe`, `.reduction`), `runner._thresholds(STAGE_WALK_FORWARD)` and `DISPOSITION_ACCEPTED`; `paired_block_bootstrap_ci` (Task 4).
- Produces:
  - `evaluate(dataset, scores, tested, horizon, k, name) -> StageRun`
  - `accepted_trades(run, cost) -> DataFrame[session_index, stock_id, rank, gross, benchmark, net, excess]`
  - `session_contribution(per_session, *, top_fraction, wide_fraction) -> dict`
  - `strategy_summary(run, trades, *, cfg) -> dict`, with the spec §6.1 fields
  - `session_totals(trades, sessions)`
  - `paired_difference(a, b, *, horizon, cfg) -> {sessions, mean_diff, ci_low, ci_high, share_sessions_a_better}`

**Data dependencies:** the synthetic world; tested sessions 100..249.

**Compute:** < 10 s.

**Acceptance criteria:**
- `8 passed`.
- Every strategy gets an identical `run.universe`.
- Labels and cost are identical across strategies.
- Candidates are unchanged when labels are shuffled.
- Overlapping holds are suppressed and not replaced.
- NaN-score rows are never selected (Review Focus 1).

- [ ] **Step 1: Write the failing tests**

`tests/test_sel002_harness.py`:

```python
import dataclasses

import numpy as np
import pandas as pd
import pytest

from app.selection_diagnostics.harness import (
    accepted_trades, evaluate, paired_difference, session_contribution, strategy_summary,
)
from app.selection_diagnostics.ranking import outcome_frame, scored, session_metrics
from app.selection_diagnostics.strategies import baseline_scores
from app.settings import settings
from tests._sel002_factories import small_config, synthetic_dataset

TESTED = list(range(100, 250))


@pytest.fixture(autouse=True)
def small_bootstrap(monkeypatch):
    monkeypatch.setattr(settings, "selection_bootstrap_draws", 200)


@pytest.fixture(scope="module")
def world():
    return synthetic_dataset()


def test_every_strategy_sees_the_same_universe(world):
    scores = baseline_scores(world.rows, random_seed=42)
    runs = {name: evaluate(world, s, TESTED, 3, 5, name) for name, s in scores.items()}
    universes = [run.universe for run in runs.values()]
    assert all(u == universes[0] for u in universes)
    frame = outcome_frame(world, 3, TESTED)
    allowed = set(zip(frame.session_index, frame.stock_id))
    for run in runs.values():
        assert {(t.session_index, t.stock_id) for t in run.trades} <= allowed


def test_labels_and_costs_are_identical_across_strategies(world):
    scores = baseline_scores(world.rows, random_seed=42)
    a = accepted_trades(evaluate(world, scores["BASELINE-MOM-5"], TESTED, 3, 10, "A"), 0.003)
    b = accepted_trades(evaluate(world, scores["BASELINE-MOM-20"], TESTED, 3, 10, "B"), 0.003)
    common = a.merge(b, on=["session_index", "stock_id"], suffixes=("_a", "_b"))
    assert len(common) > 0
    np.testing.assert_array_equal(common.gross_a, common.gross_b)
    np.testing.assert_array_equal(common.benchmark_a, common.benchmark_b)
    assert evaluate(world, scores["BASELINE-MOM-5"], TESTED, 3, 10, "A").statistics.cost == 0.0030


def test_k_selection_happens_before_labels_are_read(world):
    score = world.rows.return_5d.astype(float)
    rows = world.rows.copy()
    rows["gross_return_3"] = rows.gross_return_3.sample(frac=1.0, random_state=3).to_numpy()
    before = evaluate(world, score, TESTED, 3, 5, "S")
    after = evaluate(dataclasses.replace(world, rows=rows), score, TESTED, 3, 5, "S")
    assert [(t.session_index, t.stock_id, t.rank) for t in before.trades] == \
        [(t.session_index, t.stock_id, t.rank) for t in after.trades]


def test_duplicates_and_overlapping_holds_cannot_inflate_the_sample(world):
    score = pd.Series(0.0, index=world.rows.index).where(world.rows.stock_id != 1, 1.0)
    run = evaluate(world, score, TESTED, 5, 1, "ONE-STOCK")
    trades = accepted_trades(run, 0.003)
    assert set(trades.stock_id) == {1}
    assert np.diff(trades.session_index).min() >= 5  # one open hold per stock
    assert run.reduction["overlap_suppressed"] == len(TESTED) - len(trades)
    again = evaluate(world, score, TESTED, 5, 1, "ONE-STOCK")
    assert again.statistics == run.statistics


def test_summary_reports_hit_rate_negative_share_and_session_contribution(world):
    cfg = small_config()
    run = evaluate(world, world.rows.return_5d.astype(float), TESTED, 3, 5, "MOM")
    trades = accepted_trades(run, 0.003)
    summary = strategy_summary(run, trades, cfg=cfg)
    assert summary["trades"] == len(trades) == run.statistics.trades
    assert summary["mean_net_excess"] == pytest.approx(trades.excess.mean())
    assert summary["hit_rate"] == pytest.approx((trades.excess > 0).mean())
    assert summary["negative_net_share"] == pytest.approx((trades.net < 0).mean())
    assert summary["sessions_with_trade"] == trades.session_index.nunique()


def test_session_contribution_on_a_hand_example():
    out = session_contribution(pd.Series([5.0, 3.0, 1.0, 1.0]), top_fraction=0.01, wide_fraction=0.05)
    assert out == {"median": 2.0, "p10": 1.0, "p90": pytest.approx(4.4), "top_1pct_share": 0.5, "top_5pct_share": 0.5}
    assert session_contribution(pd.Series([-1.0, 0.5]), top_fraction=0.01, wide_fraction=0.05)["top_1pct_share"] is None


def test_paired_difference_against_itself_is_zero(world):
    cfg = small_config()
    trades = accepted_trades(evaluate(world, world.rows.return_5d.astype(float), TESTED, 3, 5, "MOM"), 0.003)
    out = paired_difference(trades, trades, horizon=3, cfg=cfg)
    assert out["mean_diff"] == 0.0 and (out["ci_low"], out["ci_high"]) == (0.0, 0.0)


def test_rows_without_a_score_are_never_selected_or_ranked(world):
    """A NaN score (BASELINE-VOLADJ, a sector-less feature) never becomes a candidate and never enters an IC."""
    score = world.rows.return_5d.astype(float).where(world.rows.stock_id % 2 == 0)
    run = evaluate(world, score, TESTED, 3, 5, "HALF")
    assert {t.stock_id % 2 for t in run.trades} == {0}
    assert run.universe == evaluate(world, world.rows.return_5d.astype(float), TESTED, 3, 5, "ALL").universe
    ranked = scored(outcome_frame(world, 3, TESTED), score)
    assert (ranked.stock_id % 2 == 0).all() and session_metrics(ranked).ic.notna().all()
```

- [ ] **Step 2: Run them; expect FAIL** (`ModuleNotFoundError: No module named 'app.selection_diagnostics.harness'`)

```bash
python -m pytest tests/test_sel002_harness.py -q -p no:cacheprovider
```

- [ ] **Step 3: Implement**

`app/selection_diagnostics/harness.py`:

```python
"""SEL-002 §6.1 strategy harness: SPG-001's run_stage (top-K before labels, duplicate and overlap reduction, cost,
benchmark) for any score, plus the per-K summary and paired comparisons."""
from __future__ import annotations

import math
from typing import Iterable

import numpy as np
import pandas as pd

from app.selection_gate.constants import DISPOSITION_ACCEPTED, STAGE_WALK_FORWARD
from app.selection_gate.evaluation import StageRun, run_stage
from app.selection_gate.runner import _thresholds

from .bootstrap import paired_block_bootstrap_ci

TRADE_COLUMNS = ["session_index", "stock_id", "rank", "gross", "benchmark", "net", "excess"]


def evaluate(dataset, scores: pd.Series, tested: Iterable[int], horizon: int, k: int, name: str) -> StageRun:
    return run_stage(stage=STAGE_WALK_FORWARD, model_version=name, horizon=horizon, rows=dataset.rows, scores=scores,
                     session_indices=tested, benchmark=dataset.benchmarks[horizon],
                     thresholds=_thresholds(STAGE_WALK_FORWARD), folds_tested=None, top_k=k)


def accepted_trades(run: StageRun, cost: float) -> pd.DataFrame:
    records = [(t.session_index, t.stock_id, t.rank, t.gross_return, t.benchmark_return)
               for t in run.trades if t.disposition == DISPOSITION_ACCEPTED]
    trades = pd.DataFrame.from_records(records, columns=TRADE_COLUMNS[:5])
    trades = trades.astype({"session_index": np.int64, "stock_id": np.int64, "rank": np.int64,
                            "gross": float, "benchmark": float})
    trades["net"] = trades.gross - cost
    trades["excess"] = trades.gross - cost - trades.benchmark
    return trades


def session_contribution(per_session: pd.Series, *, top_fraction: float, wide_fraction: float) -> dict:
    """Distribution of per-session summed net excess and the share of the total from the top sessions."""
    if per_session.empty:
        return {"median": None, "p10": None, "p90": None, "top_1pct_share": None, "top_5pct_share": None}
    values = per_session.to_numpy(dtype=float)
    total = float(values.sum())
    ordered = np.sort(values)[::-1]

    def share(fraction: float):
        return None if total <= 0 else float(ordered[:math.ceil(fraction * len(ordered))].sum() / total)

    p10, median, p90 = np.percentile(values, [10, 50, 90], method="linear")
    return {"median": float(median), "p10": float(p10), "p90": float(p90), "top_1pct_share": share(top_fraction),
            "top_5pct_share": share(wide_fraction)}


def strategy_summary(run: StageRun, trades: pd.DataFrame, *, cfg) -> dict:
    st = run.statistics
    per_session = trades.groupby("session_index", sort=True).excess.sum()
    return {
        "trades": st.trades, "unresolved_trades": st.unresolved_trades, "candidates": run.reduction["candidates"],
        "duplicates_removed": run.reduction["duplicates_removed"],
        "overlap_suppressed": run.reduction["overlap_suppressed"], "sessions_with_trade": int(len(per_session)),
        "mean_gross": st.mean_gross, "mean_net": st.mean_net, "mean_benchmark": st.mean_benchmark,
        "mean_net_excess": st.mean_excess, "ci_low": st.ci_low, "ci_high": st.ci_high,
        "hit_rate": float((trades.excess > 0).mean()) if len(trades) else None,
        "negative_net_share": float((trades.net < 0).mean()) if len(trades) else None,
        "session_contribution": session_contribution(per_session, top_fraction=cfg.top_fraction,
                                                     wide_fraction=cfg.wide_fraction),
    }


def session_totals(trades: pd.DataFrame, sessions) -> tuple[np.ndarray, np.ndarray]:
    grouped = trades.groupby("session_index").excess.agg(["sum", "size"]).reindex(sessions, fill_value=0)
    return grouped["sum"].to_numpy(dtype=float), grouped["size"].to_numpy(dtype=float)


def paired_difference(a: pd.DataFrame, b: pd.DataFrame, *, horizon: int, cfg) -> dict:
    """Trade-weighted mean net excess of a minus b, CI from one bootstrap over the sessions either traded."""
    if a.empty or b.empty:
        return {"sessions": 0, "mean_diff": None, "ci_low": None, "ci_high": None, "share_sessions_a_better": None}
    sessions = sorted(set(a.session_index) | set(b.session_index))
    sa, na = session_totals(a, sessions)
    sb, nb = session_totals(b, sessions)
    ci = paired_block_bootstrap_ci(sa, na, sb, nb, block_length=horizon, draws=cfg.bootstrap_draws,
                                   seed=cfg.bootstrap_seed)
    both = (na > 0) & (nb > 0)
    better = (sa[both] / na[both]) > (sb[both] / nb[both])
    return {"sessions": len(sessions), "mean_diff": float(a.excess.mean() - b.excess.mean()),
            "ci_low": None if ci is None else ci[0], "ci_high": None if ci is None else ci[1],
            "share_sessions_a_better": float(better.mean()) if both.any() else None}
```

- [ ] **Step 4: Run them; expect PASS** (`8 passed`)

```bash
python -m pytest tests/test_sel002_harness.py -q -p no:cacheprovider
git diff --exit-code 8944bf4 -- app/selection_gate scripts/run_selection_gate.py
```

- [ ] **Step 5: Commit**

```bash
git add app/selection_diagnostics/harness.py tests/test_sel002_harness.py
git commit -m "SEL-002: strategy harness over SPG-001 run_stage with per-K summaries and paired differences"
```

---

### Task 8: Parameterised walk-forward and single-feature rankers

**Files:**
- Create: `app/selection_diagnostics/wf_loop.py`
- Test: `tests/test_sel002_wf_loop.py`

**Interfaces:**
- Consumes: SPG-001 `fit_row_mask`, `protected_rows`, `quarter_of`, `month_text`, `fit_sel001`, `score_sel001`, `FOLD_TESTED` and `FOLD_TOO_SMALL`, plus `settings.selection_min_fit_rows` and `DEFAULT_EMBARGO_DAYS`; `ordered_features` (Task 1); `correlation_by_session` (Task 6).
- Produces:
  - `quarters(windows)` and `month_test_sessions(windows, month, horizon)`
  - `walk_forward(rows, session_dates, windows, horizon, target, features=FEATURE_COLUMNS) -> (scores: Series, folds: list[dict], tested: list[int])`. This is the same contract as `runner._walk_forward` for SEL-001.
  - `fold_signed_scores(rows, windows, horizon, target, feature) -> (scores, folds)`, where each fold carries `sign` and `train_mean_ic`

**Data dependencies:** the synthetic world, restricted; held-out 2026-01; test months 2025-07..12 (2 quarterly fits).

**Compute:** about 15 s (12 small xgboost fits; `selection_min_fit_rows` is patched to 500 in tests).

**Acceptance criteria:**
- `6 passed`.
- `walk_forward` equals `runner._walk_forward` exactly: scores, folds and tested sessions.
- A subset reads only its features.
- Every fit ends before the quarter start minus the embargo minus h.
- Feature signs come only from training sessions.

- [ ] **Step 1: Write the failing tests**

`tests/test_sel002_wf_loop.py`:

```python
import numpy as np
import pandas as pd
import pytest

from app.purged_embargo_validation import DEFAULT_EMBARGO_DAYS
from app.selection_diagnostics.config import VARIANTS
from app.selection_diagnostics.heldout import diagnostic_windows, restrict
from app.selection_diagnostics.strategies import permute_within_session
from app.selection_diagnostics.wf_loop import fold_signed_scores, walk_forward
from app.selection_gate import runner
from app.selection_gate.constants import FOLD_TESTED, SEL001_MODEL_VERSION
from app.selection_gate.validation import quarter_of
from app.settings import settings
from tests._sel002_factories import EXAM_START, small_config, synthetic_dataset


@pytest.fixture(autouse=True)
def small_fits(monkeypatch):
    monkeypatch.setattr(settings, "selection_min_fit_rows", 500)


@pytest.fixture(scope="module")
def world():
    restricted, _ = restrict(synthetic_dataset(), EXAM_START)
    windows = diagnostic_windows(restricted, 3, small_config())
    target = runner._excess_target(restricted.rows, restricted.benchmarks[3], 3)
    return restricted, windows, target


def test_loop_reproduces_spg_walk_forward_exactly(world):
    dataset, windows, target = world
    spg_scores, spg_folds, spg_tested = runner._walk_forward(SEL001_MODEL_VERSION, dataset, windows, 3, target)
    scores, folds, tested = walk_forward(dataset.rows, dataset.session_dates, windows, 3, target)
    np.testing.assert_array_equal(scores.to_numpy(), spg_scores.to_numpy())
    assert folds == spg_folds and tested == spg_tested
    assert scores.notna().sum() > 0


def test_a_feature_subset_reads_only_its_features(world):
    dataset, windows, target = world
    volume = VARIANTS["VOLUME-ONLY"]
    base, _, _ = walk_forward(dataset.rows, dataset.session_dates, windows, 3, target, volume)
    rows = dataset.rows.copy()
    rows["return_5d"] = np.float32(0.0)
    moved, _, _ = walk_forward(rows, dataset.session_dates, windows, 3, target, volume)
    np.testing.assert_array_equal(base.to_numpy(), moved.to_numpy())
    full, _, _ = walk_forward(dataset.rows, dataset.session_dates, windows, 3, target)
    assert not np.array_equal(base.to_numpy(), full.to_numpy(), equal_nan=True)


def test_fits_respect_the_embargo_and_the_protected_span(world):
    dataset, windows, target = world
    _, folds, tested = walk_forward(dataset.rows, dataset.session_dates, windows, 3, target)
    dates = list(dataset.session_dates)
    for fold in folds:
        assert fold["status"] == FOLD_TESTED
        month = tuple(int(p) for p in fold["month"].split("-"))
        first_month = min(m for m in windows.test_months if quarter_of(m) == quarter_of(month))
        quarter_start = windows.month_indices[first_month][0]
        last_train = dates.index(pd.Timestamp(fold["train_last_session"]).date())
        assert last_train + 3 < quarter_start - DEFAULT_EMBARGO_DAYS
    assert max(tested) + 3 < windows.protected[0]


def test_a_permuted_target_changes_the_fit_but_not_the_tested_sessions(world):
    dataset, windows, target = world
    real, _, tested = walk_forward(dataset.rows, dataset.session_dates, windows, 3, target)
    permuted = permute_within_session(target, dataset.rows.session_index.to_numpy(), 1)
    null, _, null_tested = walk_forward(dataset.rows, dataset.session_dates, windows, 3, permuted)
    assert null_tested == tested
    assert not np.array_equal(real.to_numpy(), null.to_numpy(), equal_nan=True)


def test_feature_sign_comes_from_training_sessions_only(world):
    dataset, windows, target = world
    scores, folds = fold_signed_scores(dataset.rows, windows, 3, target, "return_5d")
    assert [f["sign"] for f in folds] == [1.0, 1.0]
    test_rows = dataset.rows.session_index >= windows.month_indices[windows.test_months[0]][0]
    flipped = target.where(~test_rows, -target)
    again, again_folds = fold_signed_scores(dataset.rows, windows, 3, flipped, "return_5d")
    assert [f["sign"] for f in again_folds] == [f["sign"] for f in folds]
    np.testing.assert_array_equal(scores.to_numpy(), again.to_numpy())


def test_a_negatively_predictive_feature_is_flipped_and_a_constant_one_keeps_plus_one():
    restricted, _ = restrict(synthetic_dataset(signal=-0.02), EXAM_START)
    windows = diagnostic_windows(restricted, 3, small_config())
    target = runner._excess_target(restricted.rows, restricted.benchmarks[3], 3)
    scores, folds = fold_signed_scores(restricted.rows, windows, 3, target, "return_5d")
    assert [f["sign"] for f in folds] == [-1.0, -1.0]
    tested = scores.notna()
    np.testing.assert_allclose(scores[tested], -restricted.rows.return_5d[tested].astype(float))
    rows = restricted.rows.assign(rsi_14=np.float32(1.0))
    _, constant = fold_signed_scores(rows, windows, 3, target, "rsi_14")
    assert all(f["sign"] == 1.0 and f["train_mean_ic"] is None for f in constant)
```

- [ ] **Step 2: Run them; expect FAIL** (`ModuleNotFoundError: No module named 'app.selection_diagnostics.wf_loop'`)

```bash
python -m pytest tests/test_sel002_wf_loop.py -q -p no:cacheprovider
```

- [ ] **Step 3: Implement**

`app/selection_diagnostics/wf_loop.py`:

```python
"""SEL-002 §6.5/§6.6: ONE parameterised walk-forward that replicates SPG-001's `runner._walk_forward` for SEL-001.

The only parameters are the feature subset and the training target; quarters, fit_row_mask, embargo, protected span,
min fit rows, in-fit winsorisation (fit_sel001) and SEL001_PARAMS are SPG-001's own."""
from __future__ import annotations

from collections import defaultdict
from typing import Sequence

import numpy as np
import pandas as pd

from app.purged_embargo_validation import DEFAULT_EMBARGO_DAYS
from app.selection_gate.constants import FOLD_TESTED, FOLD_TOO_SMALL
from app.selection_gate.dataset import FEATURE_COLUMNS
from app.selection_gate.sel001 import fit_sel001, score_sel001
from app.selection_gate.validation import Windows, fit_row_mask, month_text, protected_rows, quarter_of
from app.settings import settings

from .config import ordered_features
from .ranking import correlation_by_session


def quarters(windows: Windows) -> list[tuple[tuple[int, int], list]]:
    by_quarter: dict[tuple[int, int], list] = defaultdict(list)
    for month in windows.test_months:
        by_quarter[quarter_of(month)].append(month)
    return sorted(by_quarter.items())


def month_test_sessions(windows: Windows, month, horizon: int) -> list[int]:
    return [i for i in windows.month_indices[month] if not protected_rows(np.array([i]), horizon, windows.protected)[0]]


def walk_forward(rows: pd.DataFrame, session_dates: Sequence, windows: Windows, horizon: int, target: pd.Series,
                 features: Sequence[str] = FEATURE_COLUMNS):
    columns = list(ordered_features(features))
    index = rows.session_index.to_numpy()
    scores = pd.Series(np.nan, index=rows.index)
    folds, tested = [], []
    for quarter, months in quarters(windows):
        start = windows.month_indices[months[0]][0]
        mask = fit_row_mask(index, horizon, test_start_index=start, embargo=DEFAULT_EMBARGO_DAYS,
                            protected=windows.protected)
        usable = mask & target.notna().to_numpy()
        train_rows = int(usable.sum())
        model = None
        if train_rows >= settings.selection_min_fit_rows:
            model = fit_sel001(rows.loc[usable, columns].to_numpy(), target[usable].to_numpy())
        for month in months:
            sessions = month_test_sessions(windows, month, horizon)
            fold = {"month": month_text(month), "fit_quarter": f"{quarter[0]}Q{quarter[1]}", "train_rows": train_rows,
                    "train_first_session": None, "train_last_session": None, "n": 0, "mean_excess": None}
            if usable.any():
                fold["train_first_session"] = session_dates[int(index[usable].min())].isoformat()
                fold["train_last_session"] = session_dates[int(index[usable].max())].isoformat()
            if model is None:
                folds.append({**fold, "status": FOLD_TOO_SMALL})
                continue
            month_mask = rows.session_index.isin(sessions)
            scores[month_mask] = score_sel001(model, rows.loc[month_mask, columns].to_numpy())
            tested.extend(sessions)
            folds.append({**fold, "status": FOLD_TESTED})
    return scores, folds, tested


def fold_signed_scores(rows: pd.DataFrame, windows: Windows, horizon: int, target: pd.Series, feature: str):
    """A model-free ranker: the feature times a per-fold sign, the sign of its mean Spearman IC over that fold's
    training sessions only (never test data); a zero or undefined training IC gives +1."""
    index = rows.session_index.to_numpy()
    values = rows[feature].astype(np.float64)
    defined_target = target.notna().to_numpy()
    both = defined_target & values.notna().to_numpy()
    f = pd.DataFrame({"session_index": index[both], "a": values.to_numpy()[both],
                      "b": target.to_numpy(dtype=float)[both]})
    g = f.groupby("session_index", sort=True)
    ic = correlation_by_session(f.session_index, g.a.rank(method="average"), g.b.rank(method="average"))
    scores = pd.Series(np.nan, index=rows.index)
    folds = []
    for quarter, months in quarters(windows):
        start = windows.month_indices[months[0]][0]
        mask = fit_row_mask(index, horizon, test_start_index=start, embargo=DEFAULT_EMBARGO_DAYS,
                            protected=windows.protected)
        usable = mask & defined_target
        name = f"{quarter[0]}Q{quarter[1]}"
        if int(usable.sum()) < settings.selection_min_fit_rows:
            folds.append({"fit_quarter": name, "status": FOLD_TOO_SMALL, "train_sessions": 0, "train_mean_ic": None,
                          "sign": None})
            continue
        train_ic = ic.reindex(np.unique(index[usable])).dropna()
        mean_ic = float(train_ic.mean()) if len(train_ic) else None
        sign = -1.0 if mean_ic is not None and mean_ic < 0 else 1.0
        for month in months:
            month_mask = rows.session_index.isin(month_test_sessions(windows, month, horizon))
            scores[month_mask] = sign * values[month_mask]
        folds.append({"fit_quarter": name, "status": FOLD_TESTED, "train_sessions": int(len(train_ic)),
                      "train_mean_ic": mean_ic, "sign": sign})
    return scores, folds
```

- [ ] **Step 4: Run them; expect PASS** (`6 passed`)

```bash
python -m pytest tests/test_sel002_wf_loop.py -q -p no:cacheprovider
git diff --exit-code 8944bf4 -- app/selection_gate scripts/run_selection_gate.py
```

- [ ] **Step 5: Commit**

```bash
git add app/selection_diagnostics/wf_loop.py tests/test_sel002_wf_loop.py
git commit -m "SEL-002: one parameterised walk-forward replicating SPG-001 and sign-fixed feature rankers"
```

---

### Task 9: Selection null and permutation summary

**Files:**
- Create: `app/selection_diagnostics/nulls.py`
- Test: `tests/test_sel002_nulls.py`

**Interfaces:**
- Consumes: SPG-001 `top_k_candidates(frame, *, score_column, top_k)`; frames shaped like `outcome_frame` (Task 6).
- Produces:
  - `selection_null(frame, scores, *, k, draws, seed, cost) -> {k, draws, seed, sessions, observed_unresolved_picks, observed, null_mean, null_sd, null_max, p, z}`
  - `permutation_summary(observed_net_excess, observed_ic, runs: {seed: {net_excess, mean_ic, ...}}) -> {seeds, runs, net_excess: {...}, ic: {...}}`

**Data dependencies:** hand-built frames.

**Compute:** < 5 s.

**Acceptance criteria:**
- `5 passed`.
- Deterministic per seed.
- A perfect ranker gets p = 1/(draws+1); a random one is not significant.
- An unresolved top pick stays picked.
- A missing permutation run marks the summary incomplete.

- [ ] **Step 1: Write the failing tests**

`tests/test_sel002_nulls.py`:

```python
import numpy as np
import pandas as pd
import pytest

from app.selection_diagnostics.nulls import permutation_summary, selection_null


def _frame(sessions=40, stocks=30, seed=0):
    rng = np.random.default_rng(seed)
    n = sessions * stocks
    return pd.DataFrame({"session_index": np.repeat(np.arange(sessions), stocks),
                         "stock_id": np.tile(np.arange(stocks), sessions), "x": rng.normal(0, 0.02, n)})


def test_selection_null_is_deterministic_per_seed():
    f = _frame()
    score = pd.Series(np.random.default_rng(1).random(len(f)), index=f.index)
    a = selection_null(f, score, k=5, draws=100, seed=42, cost=0.003)
    assert a == selection_null(f, score, k=5, draws=100, seed=42, cost=0.003)
    assert a["null_mean"] != selection_null(f, score, k=5, draws=100, seed=7, cost=0.003)["null_mean"]


def test_a_perfect_ranker_beats_every_draw_and_gets_the_smallest_p():
    f = _frame()
    out = selection_null(f, f.x, k=5, draws=200, seed=42, cost=0.003)
    assert out["observed"] > out["null_max"]
    assert out["p"] == pytest.approx(1 / 201)


def test_a_random_ranker_is_not_significant():
    f = _frame()
    score = pd.Series(np.random.default_rng(9).random(len(f)), index=f.index)
    assert selection_null(f, score, k=5, draws=200, seed=42, cost=0.003)["p"] > 0.05


def test_selection_happens_before_labels_so_unresolved_picks_stay_picked():
    f = _frame()
    score = f.x.copy()
    top = f.loc[f.groupby("session_index").x.idxmax()].index
    f.loc[top, "x"] = np.nan  # the best row of every session is unresolved
    out = selection_null(f, score, k=5, draws=50, seed=42, cost=0.003)
    assert out["observed_unresolved_picks"] == 40
    with pytest.raises(ValueError):
        selection_null(f.iloc[:3], score, k=5, draws=5, seed=1, cost=0.0)


def test_permutation_summary_on_a_hand_example():
    runs = {s: {"net_excess": v, "mean_ic": ic} for s, v, ic in
            [(1, 0.001, 0.01), (2, -0.001, 0.00), (3, 0.000, -0.01), (4, 0.002, 0.02)]}
    out = permutation_summary(0.010, 0.05, runs)
    values = np.array([0.001, -0.001, 0.0, 0.002])
    assert out["seeds"] == [1, 2, 3, 4]
    assert out["net_excess"]["null_mean"] == pytest.approx(values.mean())
    assert out["net_excess"]["null_sd"] == pytest.approx(values.std(ddof=1))
    assert out["net_excess"]["z"] == pytest.approx((0.010 - values.mean()) / values.std(ddof=1))
    assert out["net_excess"]["exceeds_all"] and out["ic"]["exceeds_all"]
    runs[2]["net_excess"] = None
    assert permutation_summary(0.010, 0.05, runs)["net_excess"]["complete"] is False
```

- [ ] **Step 2: Run them; expect FAIL** (`ModuleNotFoundError: No module named 'app.selection_diagnostics.nulls'`)

```bash
python -m pytest tests/test_sel002_nulls.py -q -p no:cacheprovider
```

- [ ] **Step 3: Implement**

`app/selection_diagnostics/nulls.py`:

```python
"""SEL-002 §6.6 nulls: random top-K selection per session, and the summary of the label-permutation refits."""
from __future__ import annotations

import numpy as np
import pandas as pd

from app.selection_gate.reduction import top_k_candidates


def selection_null(frame: pd.DataFrame, scores: pd.Series, *, k: int, draws: int, seed: int, cost: float) -> dict:
    """Observed: pooled mean (x - c) over each session's top-k by score. Null: k uniform draws per session from the
    same scored U(D) rows. Both pick before labels are read; unresolved picks are dropped from the mean. No reduction."""
    f = frame.assign(score=scores.reindex(frame.index).to_numpy(dtype=float))
    f = f[f.score.notna()]
    top = top_k_candidates(f, score_column="score", top_k=k)
    observed = float(np.nanmean(top.x.to_numpy()) - cost)
    sessions = np.sort(f.session_index.unique())
    pos = np.searchsorted(sessions, f.session_index.to_numpy())
    counts = np.bincount(pos, minlength=len(sessions))
    if (counts < k).any():
        raise ValueError(f"SEL-002: a session has fewer than {k} scored rows")
    order = np.argsort(pos, kind="stable")
    column = np.arange(len(f)) - np.repeat(np.cumsum(counts) - counts, counts)
    grid = np.full((len(sessions), int(counts.max())), np.nan)
    filled = np.zeros(grid.shape, dtype=bool)
    grid[pos[order], column] = f.x.to_numpy()[order]
    filled[pos[order], column] = True
    rng = np.random.default_rng(seed)
    rows = np.arange(len(sessions))[:, None]
    null = np.empty(draws)
    for d in range(draws):
        keys = rng.random(grid.shape)
        keys[~filled] = 2.0
        pick = np.argpartition(keys, k - 1, axis=1)[:, :k]
        null[d] = np.nanmean(grid[rows, pick]) - cost
    sd = float(null.std(ddof=1)) if draws > 1 else None
    return {"k": k, "draws": draws, "seed": seed, "sessions": int(len(sessions)),
            "observed_unresolved_picks": int(top.x.isna().sum()), "observed": observed,
            "null_mean": float(null.mean()), "null_sd": sd, "null_max": float(null.max()),
            "p": float((1 + (null >= observed).sum()) / (1 + draws)),
            "z": None if not sd else float((observed - null.mean()) / sd)}


def _stats(observed, values: list) -> dict:
    complete = bool(values) and all(v is not None for v in values)
    if not complete or observed is None:
        return {"observed": observed, "null_mean": None, "null_sd": None, "null_max": None, "z": None,
                "exceeds_all": False, "complete": complete}
    v = np.asarray(values, dtype=float)
    sd = float(v.std(ddof=1)) if len(v) > 1 else None
    return {"observed": float(observed), "null_mean": float(v.mean()), "null_sd": sd, "null_max": float(v.max()),
            "z": None if not sd else float((observed - v.mean()) / sd), "exceeds_all": bool(observed > v.max()),
            "complete": True}


def permutation_summary(observed_net_excess, observed_ic, runs: dict[int, dict]) -> dict:
    seeds = sorted(runs)
    return {"seeds": seeds, "runs": {str(s): runs[s] for s in seeds},
            "net_excess": _stats(observed_net_excess, [runs[s]["net_excess"] for s in seeds]),
            "ic": _stats(observed_ic, [runs[s]["mean_ic"] for s in seeds])}
```

- [ ] **Step 4: Run them; expect PASS** (`5 passed`)

```bash
python -m pytest tests/test_sel002_nulls.py -q -p no:cacheprovider
git diff --exit-code 8944bf4 -- app/selection_gate scripts/run_selection_gate.py
```

- [ ] **Step 5: Commit**

```bash
git add app/selection_diagnostics/nulls.py tests/test_sel002_nulls.py
git commit -m "SEL-002: label-blind selection null and the label-permutation summary"
```

---

### Task 10: Concentration

**Files:**
- Create: `app/selection_diagnostics/concentration.py`
- Test: `tests/test_sel002_concentration.py`

**Interfaces:**
- Consumes: `trade_weighted_ci` (Task 4).
- Produces:
  - `DIMENSIONS`, `UNKNOWN_SECTOR = "UNKNOWN"`
  - `decompose(trades, key)`, `herfindahl(contributions)`, `top_share(decomposed, fraction, total)`, `mean_with_ci(trades, *, horizon, cfg)` and `exclude_top(trades, key, fraction, *, horizon, cfg)`
  - `concentration_report(trades, *, horizon, cfg) -> {total_net_excess, trades, dimensions, exclude_top_sessions, exclude_top_stocks, max_sector_share, unknown_sector_share, max_year_share}`

**Data dependencies:** hand-built trades.

**Compute:** < 5 s.

**Acceptance criteria:**
- `4 passed`.
- Shares are undefined when the total is ≤ 0.
- Excluding the top 1% removes ⌈1%⌉ groups.
- UNKNOWN is kept out of the single-sector check, and its share is reported.

- [ ] **Step 1: Write the failing tests**

`tests/test_sel002_concentration.py`:

```python
import numpy as np
import pandas as pd
import pytest

from app.selection_diagnostics.concentration import (
    concentration_report, decompose, exclude_top, herfindahl, top_share,
)
from tests._sel002_factories import small_config


def _trades():
    rng = np.random.default_rng(5)
    n = 400
    session = np.repeat(np.arange(200), 2)
    trades = pd.DataFrame({"session_index": session, "stock_id": rng.integers(1, 60, n),
                           "excess": rng.normal(0.004, 0.02, n)})
    trades["month"] = [f"2025-{1 + s // 20:02d}" for s in session]
    trades["year"] = 2025
    trades["sector"] = np.where(trades.stock_id % 3 == 0, "UNKNOWN", np.where(trades.stock_id % 3 == 1, "BANK", "IT"))
    trades["liquidity"] = np.where(trades.stock_id % 2 == 0, "LOW", "HIGH")
    trades["volatility"] = "MID"
    return trades


def test_decompose_orders_by_contribution_and_shares_sum_to_one():
    trades = pd.DataFrame({"session_index": [0, 0, 1, 2], "stock_id": [1, 2, 1, 3], "excess": [0.03, -0.01, 0.02, 0.01]})
    d = decompose(trades, "stock_id")
    assert d.stock_id.tolist() == [1, 3, 2]
    assert d.contribution.tolist() == pytest.approx([0.05, 0.01, -0.01])
    assert d.share.sum() == pytest.approx(1.0)
    assert top_share(d, 0.01, 0.05) == pytest.approx(1.0)
    assert herfindahl(d.contribution) == pytest.approx((5 / 7) ** 2 + (1 / 7) ** 2 + (1 / 7) ** 2)


def test_a_non_positive_total_has_no_shares():
    trades = pd.DataFrame({"session_index": [0, 1], "stock_id": [1, 2], "excess": [-0.01, 0.005]})
    d = decompose(trades, "stock_id")
    assert d.share.isna().all() and top_share(d, 0.05, float(trades.excess.sum())) is None


def test_excluding_the_top_one_percent_removes_ceil_one_percent_of_groups():
    trades = _trades()
    out = exclude_top(trades, "session_index", 0.01, horizon=3, cfg=small_config())
    assert len(out["removed"]) == 2  # ceil(0.01 * 200)
    best = trades.groupby("session_index").excess.sum().sort_values(ascending=False).index[:2].tolist()
    assert sorted(out["removed"]) == sorted(best)
    assert out["trades"] == 400 - int(trades.session_index.isin(best).sum())
    assert out["ci_low"] is not None


def test_report_covers_every_dimension_and_keeps_unknown_sector_out_of_the_sector_rule():
    trades = _trades()
    out = concentration_report(trades, horizon=3, cfg=small_config())
    assert set(out["dimensions"]) == {"session_index", "stock_id", "month", "year", "sector", "liquidity", "volatility"}
    total = trades.excess.sum()
    known = trades[trades.sector != "UNKNOWN"].groupby("sector").excess.sum()
    assert out["max_sector_share"] == pytest.approx(known.max() / total)
    assert out["unknown_sector_share"] == pytest.approx(trades.excess[trades.sector == "UNKNOWN"].sum() / total)
    assert out["max_year_share"] == pytest.approx(1.0)
    assert len(out["dimensions"]["session_index"]["top_10"]) == 10
```

- [ ] **Step 2: Run them; expect FAIL** (`ModuleNotFoundError: No module named 'app.selection_diagnostics.concentration'`)

```bash
python -m pytest tests/test_sel002_concentration.py -q -p no:cacheprovider
```

- [ ] **Step 3: Implement**

`app/selection_diagnostics/concentration.py`:

```python
"""SEL-002 §6.7 concentration of SEL-001's K=10 net excess by session, stock, month, year, sector, liquidity and volatility."""
from __future__ import annotations

import math

import numpy as np
import pandas as pd

from .bootstrap import trade_weighted_ci

DIMENSIONS = ("session_index", "stock_id", "month", "year", "sector", "liquidity", "volatility")
UNKNOWN_SECTOR = "UNKNOWN"


def _plain(value):
    return value.item() if isinstance(value, np.generic) else value


def decompose(trades: pd.DataFrame, key: str) -> pd.DataFrame:
    """Contribution (summed net excess) per group, largest first, ties by group ascending."""
    total = float(trades.excess.sum())
    g = trades.groupby(key, sort=True).excess.agg(contribution="sum", trades="size").reset_index()
    g = g.sort_values(["contribution", key], ascending=[False, True], kind="mergesort").reset_index(drop=True)
    g["share"] = g.contribution / total if total > 0 else np.nan
    return g


def herfindahl(contributions) -> float | None:
    a = np.abs(np.asarray(contributions, dtype=float))
    s = a.sum()
    return float(((a / s) ** 2).sum()) if s > 0 else None


def top_share(decomposed: pd.DataFrame, fraction: float, total: float) -> float | None:
    if total <= 0 or decomposed.empty:
        return None
    return float(decomposed.contribution.iloc[:math.ceil(fraction * len(decomposed))].sum() / total)


def mean_with_ci(trades: pd.DataFrame, *, horizon: int, cfg) -> dict:
    per = trades.groupby("session_index", sort=True).excess.agg(["sum", "size"])
    ci = trade_weighted_ci(per["sum"].to_numpy(), per["size"].to_numpy(), block_length=horizon,
                           draws=cfg.bootstrap_draws, seed=cfg.bootstrap_seed) if len(per) else None
    return {"trades": int(len(trades)), "sessions": int(len(per)),
            "mean_net_excess": float(trades.excess.mean()) if len(trades) else None,
            "ci_low": None if ci is None else ci[0], "ci_high": None if ci is None else ci[1]}


def exclude_top(trades: pd.DataFrame, key: str, fraction: float, *, horizon: int, cfg) -> dict:
    d = decompose(trades, key)
    removed = d[key].iloc[:math.ceil(fraction * len(d))].tolist()
    rest = trades[~trades[key].isin(removed)]
    return {"removed": [_plain(v) for v in removed], **mean_with_ci(rest, horizon=horizon, cfg=cfg)}


def concentration_report(trades: pd.DataFrame, *, horizon: int, cfg) -> dict:
    """`trades` has one row per accepted trade with `excess` and every column in DIMENSIONS."""
    total = float(trades.excess.sum())
    out = {"total_net_excess": total, "trades": int(len(trades)), "dimensions": {}}
    for key in DIMENSIONS:
        d = decompose(trades, key)
        out["dimensions"][key] = {
            "groups": int(len(d)), "herfindahl": herfindahl(d.contribution),
            "top_1pct_share": top_share(d, cfg.top_fraction, total), "top_5pct_share": top_share(d, cfg.wide_fraction, total),
            "max_share": float(d.share.max()) if total > 0 and len(d) else None,
            "top_10": [{"group": _plain(r[key]), "contribution": float(r.contribution), "trades": int(r.trades),
                        "share": None if total <= 0 else float(r.share)} for _, r in d.head(10).iterrows()],
        }
    out["exclude_top_sessions"] = exclude_top(trades, "session_index", cfg.top_fraction, horizon=horizon, cfg=cfg)
    out["exclude_top_stocks"] = exclude_top(trades, "stock_id", cfg.top_fraction, horizon=horizon, cfg=cfg)
    known = trades[trades.sector != UNKNOWN_SECTOR]
    sectors = known.groupby("sector").excess.sum()
    out["max_sector_share"] = float(sectors.max() / total) if total > 0 and len(sectors) else None
    out["unknown_sector_share"] = float(trades.excess[trades.sector == UNKNOWN_SECTOR].sum() / total) if total > 0 else None
    out["max_year_share"] = out["dimensions"]["year"]["max_share"]
    return out
```

- [ ] **Step 4: Run them; expect PASS** (`4 passed`)

```bash
python -m pytest tests/test_sel002_concentration.py -q -p no:cacheprovider
git diff --exit-code 8944bf4 -- app/selection_gate scripts/run_selection_gate.py
```

- [ ] **Step 5: Commit**

```bash
git add app/selection_diagnostics/concentration.py tests/test_sel002_concentration.py
git commit -m "SEL-002: concentration by session, stock, month, year, sector, liquidity and volatility"
```

---

### Task 11: Liquidity recompute and regimes

**Files:**
- Create: `app/selection_diagnostics/liquidity.py`, `app/selection_diagnostics/regimes.py`
- Test: `tests/test_sel002_regimes.py`

**Interfaces:**
- Consumes: SPG-001 `final_bars_only()`, `MarketPrice`, `universe._LIQUIDITY_WINDOW` and `as_utc`; `REGIME_BUCKETS` (Task 1); `trade_weighted_ci` (Task 4); `summarize` (Task 6). In tests: `tests._selection_gate_factories.seed_stock_with_bars` and `weekday_sessions`.
- Produces:
  - `official_bars_before(read_session, stock_id, before) -> DataFrame[timestamp, close, volume]` and `rolling_median_traded_value(close, volume)`
  - `median_traded_value_20d(read_session, rows, anchors, *, before) -> Series`, indexed like `rows`
  - `within_session_terciles(session_index, values) -> Series[LOW|MID|HIGH|UNKNOWN]` and `tercile_labels(values)`
  - `session_regimes(dataset, horizon, tested) -> DataFrame[direction, volatility, dispersion, fv002]`
  - `regime_report(trades, session_labels, session_ic, liquidity_ic, *, horizon, cfg) -> {dim: {bucket: {...}}}`

**Data dependencies:** the synthetic world, and one temporary SQLite market (60 sessions of official bars).

**Compute:** < 10 s.

**Acceptance criteria:**
- `5 passed`.
- The tercile rule is integer-exact.
- Regimes come from features at D and from b(D,h).
- The traded value equals SEU-001's rolling median, and bars at or after the bound are never read or used.

- [ ] **Step 1: Write the failing tests**

`tests/test_sel002_regimes.py`:

```python
import dataclasses
from datetime import date

import numpy as np
import pandas as pd
import pytest
from sqlalchemy import create_engine, update
from sqlalchemy.orm import sessionmaker

from app.db import Base
from app.models import MarketPrice
from app.selection_diagnostics.config import REGIME_BUCKETS
from app.selection_diagnostics.liquidity import (
    median_traded_value_20d, official_bars_before, rolling_median_traded_value,
)
from app.selection_diagnostics.regimes import regime_report, session_regimes, tercile_labels, within_session_terciles
from app.walk_forward_dataset import session_cutoff
from tests._selection_gate_factories import seed_stock_with_bars, weekday_sessions
from tests._sel002_factories import small_config, synthetic_dataset


def test_tercile_rule_is_integer_exact_with_ties_by_order():
    assert tercile_labels(pd.Series(np.arange(10.0))).tolist() == ["LOW"] * 4 + ["MID"] * 3 + ["HIGH"] * 3
    assert tercile_labels(pd.Series(np.arange(9.0))).tolist() == ["LOW"] * 3 + ["MID"] * 3 + ["HIGH"] * 3
    assert tercile_labels(pd.Series([1.0, 1.0, 1.0])).tolist() == ["LOW", "MID", "HIGH"]
    assert tercile_labels(pd.Series([np.nan, 2.0, 1.0, 3.0])).tolist() == ["UNKNOWN", "MID", "LOW", "HIGH"]


def test_within_session_terciles_rank_each_session_separately():
    values = pd.Series([3.0, 1.0, 2.0, 30.0, 10.0, 20.0])
    labels = within_session_terciles(np.array([0, 0, 0, 1, 1, 1]), values)
    assert labels.tolist() == ["HIGH", "LOW", "MID", "HIGH", "LOW", "MID"]


@pytest.fixture(scope="module")
def world():
    return synthetic_dataset(sessions=120, stocks=30)


def test_session_regimes_use_features_at_d_and_the_benchmark_sign(world):
    tested = list(range(60, 110))
    regimes = session_regimes(world, 3, tested)
    bench = world.benchmarks[3].set_index("session_index").benchmark_return
    assert (regimes.direction == np.where(bench.reindex(tested) > 0, "UP", "DOWN")).all()
    one_hot = world.rows.groupby("session_index")[["regime_bullish", "regime_bearish", "regime_neutral"]].first()
    expected = np.select([one_hot.regime_bullish == 1, one_hot.regime_bearish == 1], ["BULLISH", "BEARISH"], "NEUTRAL")
    assert (regimes.fv002 == pd.Series(expected, index=one_hot.index).reindex(tested)).all()
    assert regimes.volatility.value_counts().to_dict() == {"LOW": 17, "MID": 17, "HIGH": 16}
    rows = world.rows.assign(gross_return_3=0.5)
    again = session_regimes(dataclasses.replace(world, rows=rows), 3, tested)
    pd.testing.assert_series_equal(regimes.volatility, again.volatility)
    pd.testing.assert_series_equal(regimes.dispersion, again.dispersion)


def test_regime_report_lists_every_predeclared_bucket(world):
    tested = list(range(60, 110))
    labels = session_regimes(world, 3, tested)
    rng = np.random.default_rng(2)
    trades = pd.DataFrame({"session_index": np.repeat(tested, 3), "excess": rng.normal(0.003, 0.02, 150),
                           "liquidity": np.tile(["LOW", "MID", "HIGH"], 50)})
    ic = pd.Series(rng.normal(0.05, 0.1, len(tested)), index=tested)
    out = regime_report(trades, labels, ic, {b: ic for b in ("LOW", "MID", "HIGH")}, horizon=3, cfg=small_config())
    for dim, buckets in REGIME_BUCKETS.items():
        assert set(buckets) <= set(out[dim])
        assert all(out[dim][b]["predeclared"] for b in buckets)
    assert out["liquidity"]["LOW"]["trades"] == 50
    assert sum(out["direction"][b]["trades"] for b in out["direction"]) == 150


@pytest.fixture
def market(tmp_path):
    engine = create_engine(f"sqlite:///{tmp_path / 'bars.db'}")
    Base.metadata.create_all(engine)
    sessions = weekday_sessions(date(2026, 6, 30), 60)
    with sessionmaker(bind=engine)() as db:
        stock = seed_stock_with_bars(db, symbol="A", sessions=sessions, volume=1000, drift=0.01)
        db.commit()
        yield db, stock, tuple(session_cutoff(d) for d in sessions)


def test_traded_value_is_seu_001_rolling_median_and_never_reads_past_the_bound(market):
    db, stock, anchors = market
    rows = pd.DataFrame({"session_index": np.arange(25, 50), "stock_id": stock.id})
    values = median_traded_value_20d(db, rows, anchors, before=anchors[50])
    every = official_bars_before(db, stock.id, anchors[-1].replace(year=2027))
    expected = rolling_median_traded_value(every.close, every.volume)[25:50]
    np.testing.assert_allclose(values.to_numpy(), expected)
    assert official_bars_before(db, stock.id, anchors[50]).timestamp.max() < pd.Timestamp(anchors[50])
    db.execute(update(MarketPrice).where(MarketPrice.timestamp >= anchors[50]).values(volume=10**9))
    db.flush()
    np.testing.assert_array_equal(median_traded_value_20d(db, rows, anchors, before=anchors[50]).to_numpy(),
                                  values.to_numpy())
```

- [ ] **Step 2: Run them; expect FAIL** (`ModuleNotFoundError: No module named 'app.selection_diagnostics.liquidity'`)

```bash
python -m pytest tests/test_sel002_regimes.py -q -p no:cacheprovider
```

- [ ] **Step 3: Implement**

`app/selection_diagnostics/liquidity.py`:

```python
"""SEL-002 §6.7: SEU-001's median 20-session traded value at D, recomputed read-only from official bars that end
before the exam month (the same rule as app/selection_gate/universe.py::exclusion_reasons)."""
from __future__ import annotations

from datetime import datetime
from typing import Sequence

import numpy as np
import pandas as pd
from sqlalchemy import select

from app.market_data.bar_finality import final_bars_only
from app.models import MarketPrice
from app.selection_gate.universe import _LIQUIDITY_WINDOW
from app.walk_forward_dataset import as_utc


def official_bars_before(read_session, stock_id: int, before: datetime) -> pd.DataFrame:
    rows = read_session.execute(
        select(MarketPrice.timestamp, MarketPrice.close, MarketPrice.volume)
        .where(MarketPrice.stock_id == stock_id, final_bars_only(), MarketPrice.timestamp < before)
        .order_by(MarketPrice.timestamp.asc())
    ).all()
    return pd.DataFrame({"timestamp": [pd.Timestamp(as_utc(r[0])) for r in rows],
                         "close": [float(r[1]) for r in rows], "volume": [float(r[2]) for r in rows]})


def rolling_median_traded_value(close, volume) -> np.ndarray:
    """close x volume is split-invariant, so raw official bars give SEU-001's adjusted value."""
    return pd.Series(np.asarray(close, dtype=float) * np.asarray(volume, dtype=float)).rolling(
        _LIQUIDITY_WINDOW).median().to_numpy()


def median_traded_value_20d(read_session, rows: pd.DataFrame, anchors: Sequence[datetime], *,
                            before: datetime) -> pd.Series:
    index_of = {pd.Timestamp(as_utc(anchor)): i for i, anchor in enumerate(anchors)}
    out = pd.Series(np.nan, index=rows.index, dtype=float)
    for stock_id, labels in sorted(rows.groupby("stock_id").groups.items()):
        bars = official_bars_before(read_session, int(stock_id), before)
        if bars.empty:
            continue
        value = pd.Series(rolling_median_traded_value(bars.close, bars.volume),
                          index=[index_of.get(t, -1) for t in bars.timestamp])
        value = value[value.index >= 0]
        if value.index.duplicated().any():
            raise ValueError(f"SEL-002: stock {stock_id} has two official bars on one session")
        out.loc[labels] = rows.loc[labels, "session_index"].map(value).to_numpy()
    return out
```

`app/selection_diagnostics/regimes.py`:

```python
"""SEL-002 §6.8 predeclared regimes and the tercile rule shared with §6.7."""
from __future__ import annotations

from typing import Iterable

import numpy as np
import pandas as pd

from .bootstrap import trade_weighted_ci
from .config import REGIME_BUCKETS
from .ranking import summarize

UNKNOWN = "UNKNOWN"
SESSION_DIMENSIONS = ("direction", "volatility", "dispersion", "fv002")


def _terciles(rank: np.ndarray, n: np.ndarray) -> np.ndarray:
    """Integer rule on the ascending rank r = 0..n-1: 3r < n LOW, 3r < 2n MID, else HIGH."""
    return np.where(3 * rank < n, "LOW", np.where(3 * rank < 2 * n, "MID", "HIGH"))


def within_session_terciles(session_index, values) -> pd.Series:
    """Tercile of each row's value among its session's rows, ascending, ties by row order; NaN -> UNKNOWN."""
    f = pd.DataFrame({"s": np.asarray(session_index), "v": np.asarray(values, dtype=float),
                      "i": np.arange(len(values))}, index=getattr(values, "index", None))
    d = f[f.v.notna()].sort_values(["s", "v", "i"], kind="mergesort")
    g = d.groupby("s", sort=True)
    labels = _terciles(g.cumcount().to_numpy(), g.v.transform("size").to_numpy())
    out = pd.Series(UNKNOWN, index=f.index, dtype=object)
    out.loc[d.index] = labels
    return out


def tercile_labels(values: pd.Series) -> pd.Series:
    """Tercile over all values (one group), ascending, ties by index ascending."""
    ordered = values.sort_index()
    return within_session_terciles(np.zeros(len(ordered)), ordered).reindex(values.index)


def session_regimes(dataset, horizon: int, tested: Iterable[int]) -> pd.DataFrame:
    """One row per tested session: market direction from b(D,h), volatility and dispersion terciles over the tested
    sessions from features at D, and the FV-002 regime label."""
    sessions = sorted({int(i) for i in tested})
    rows = dataset.rows[dataset.rows.session_index.isin(sessions)]
    g = rows.groupby("session_index", sort=True)
    flags = ["regime_bullish", "regime_bearish", "regime_neutral"]
    if (g[flags].nunique(dropna=False) > 1).any().any():
        raise ValueError("SEL-002: the FV-002 regime differs within a session")
    first = g[flags].first().reindex(sessions)
    fv002 = np.select([first.regime_bullish == 1, first.regime_bearish == 1, first.regime_neutral == 1],
                      ["BULLISH", "BEARISH", "NEUTRAL"], default="UNLABELLED")
    bench = dataset.benchmarks[horizon].set_index("session_index")
    b = bench.benchmark_return.reindex(sessions).astype(float)
    ok = bench.benchmarkable.reindex(sessions).fillna(False).astype(bool) & b.notna()
    out = pd.DataFrame(index=pd.Index(sessions, name="session_index"))
    out["direction"] = np.where(~ok, "NO_BENCHMARK", np.where(b > 0, "UP", "DOWN"))
    out["volatility"] = tercile_labels(g.atr_percent.mean().astype(float).reindex(sessions))
    out["dispersion"] = tercile_labels(g.return_5d.std(ddof=1).astype(float).reindex(sessions))
    out["fv002"] = fv002
    return out


def _bucket(trades: pd.DataFrame, ic: pd.Series, *, horizon: int, cfg) -> dict:
    per = trades.groupby("session_index", sort=True).excess.agg(["sum", "size"])
    ci = trade_weighted_ci(per["sum"].to_numpy(), per["size"].to_numpy(), block_length=horizon,
                           draws=cfg.bootstrap_draws, seed=cfg.bootstrap_seed) if len(per) else None
    ic_summary = summarize(ic, horizon=horizon, draws=cfg.bootstrap_draws, seed=cfg.bootstrap_seed)
    return {"trades": int(len(trades)), "sessions": int(len(per)),
            "mean_net_excess": float(trades.excess.mean()) if len(trades) else None,
            "ci_low": None if ci is None else ci[0], "ci_high": None if ci is None else ci[1],
            "mean_ic": ic_summary["mean"], "ic_ci_low": ic_summary["ci_low"], "ic_ci_high": ic_summary["ci_high"],
            "ic_sessions": ic_summary["sessions"]}


def regime_report(trades: pd.DataFrame, session_labels: pd.DataFrame, session_ic: pd.Series,
                  liquidity_ic: dict[str, pd.Series], *, horizon: int, cfg) -> dict:
    """`trades` carries `session_index`, `excess` and the row-level `liquidity` tercile."""
    out = {}
    for dim in SESSION_DIMENSIONS:
        labels = session_labels[dim]
        found = sorted(set(labels) - set(REGIME_BUCKETS[dim]))
        out[dim] = {}
        for bucket in [*REGIME_BUCKETS[dim], *found]:
            sessions = labels.index[labels == bucket]
            part = trades[trades.session_index.isin(sessions)]
            out[dim][bucket] = {**_bucket(part, session_ic.reindex(sessions), horizon=horizon, cfg=cfg),
                                "predeclared": bucket in REGIME_BUCKETS[dim]}
    out["liquidity"] = {}
    found = sorted(set(trades.liquidity) - set(REGIME_BUCKETS["liquidity"]))
    for bucket in [*REGIME_BUCKETS["liquidity"], *found]:
        out["liquidity"][bucket] = {**_bucket(trades[trades.liquidity == bucket],
                                              liquidity_ic.get(bucket, pd.Series(dtype=float)), horizon=horizon, cfg=cfg),
                                    "predeclared": bucket in REGIME_BUCKETS["liquidity"]}
    return out
```

- [ ] **Step 4: Run them; expect PASS** (`5 passed`)

```bash
python -m pytest tests/test_sel002_regimes.py -q -p no:cacheprovider
git diff --exit-code 8944bf4 -- app/selection_gate scripts/run_selection_gate.py
```

- [ ] **Step 5: Commit**

```bash
git add app/selection_diagnostics/liquidity.py app/selection_diagnostics/regimes.py tests/test_sel002_regimes.py
git commit -m "SEL-002: point-in-time liquidity terciles and the predeclared regimes"
```

---

### Task 12: Predeclared evidence rules, major red flags and the A/B/C class

**Files:**
- Create: `app/selection_diagnostics/evidence.py`
- Test: `tests/test_sel002_evidence.py`

**Interfaces:**
- Consumes: `BASELINE_NAMES`, `REGIME_BUCKETS` (Task 1).
- Produces:
  - `RULES_VERSION = "SEL-002-EVIDENCE-002"`, the threshold constants (including `MIN_REGIME_SESSIONS = 250`),
    `CLASSES` and `DEPENDENCY`
  - `Check(rule, name, value, threshold, holds, group)` where `group` is `"core"` (E1-E4) or `"robustness"` (E5-E6)
  - `Flag(id, kind, rule, name, value)` where `kind` is `"major"` (id `F1`-`F5`) or `"minor"` (id names the bucket or
    check; `rule` is always `E5` or `E6`, matching `DEPENDENCY`)
  - `horizon_checks(inputs) -> list[Check]` (37: 19 core, 18 robustness), `positive_evidence(inputs) -> dict`
  - `major_flags(inputs) -> list[Flag]`, `minor_flags(inputs, major) -> list[Flag]`
  - `classify({3: inputs, 5: inputs}) -> {rules_version, class, label, recommendation, checks, rules_hold, core_hold, positive_evidence, failing, major_flags, minor_flags, dependencies}`
- The evidence input keys (produced by Task 14): `ic_ci_low`, `ic_share_positive`, `bucket_monotonicity`, `spread_ci_low`, `net_excess_ci_low_by_k` (str K keys), `net_excess_k10`, `net_excess_k10_ci_low`, `paired_ci_low` (by baseline), `permutation_net_excess` (list), `mean_ic`, `permutation_ic` (list), `selection_null_p`, `ex_top_sessions_ci_low`, `ex_top_sessions_mean`, `ex_top_stocks_ci_low`, `ex_top_stocks_mean`, `max_sector_share`, `max_year_share`, `regime_ci_high` (`"dim:bucket"` keys), `regime_sessions` (`"dim:bucket"` -> sessions with >= 1 accepted K=10 trade in that bucket).

**Data dependencies:** hand-built evidence inputs.

**Compute:** < 5 s.

**Acceptance criteria:**
- `15 passed`.
- The thresholds, including `MIN_REGIME_SESSIONS = 250`, are pinned literally.
- Implements spec §7 (`SEL-002-EVIDENCE-002`) literally: never adjust a threshold, rule, flag or classification to make
  a test or a synthetic result come out a particular way. If a test and spec §7 disagree, the test is wrong.
- All 37 checks are still computed and reported at every horizon; only the 19 core checks (E1-E4) and the absence of
  a major flag (F1-F5) gate class A. A failing robustness check (E5-E6) that is not a major flag is a minor flag:
  reported, never blocking.
- A thin losing regime (< 250 sessions) does not block A; a well-populated one (>= 250) is a major flag (F5).
- A failed E5 concentration CI check whose underlying mean stays positive does not block A; a mean <= 0 or
  undefined (F1/F2) does.
- A sector or year share > 0.40, or undefined, is always a major flag (F3/F4) when it occurs.
- Any single failed core check, or any major flag, with positive evidence gives B; no positive evidence gives C.
- An undefined value fails, except E6 (an undefined CI cannot show a significantly negative regime); an undefined
  F1-F4 input is itself a major flag.
- Both horizons are required.

- [ ] **Step 1: Write the failing tests**

`tests/test_sel002_evidence.py`:

```python
import copy
import pathlib

import pytest

from app.selection_diagnostics import evidence
from app.selection_diagnostics.config import BASELINE_NAMES, REGIME_BUCKETS
from app.selection_diagnostics.evidence import classify, horizon_checks


def strong() -> dict:
    return {
        "ic_ci_low": 0.01, "ic_share_positive": 0.60, "bucket_monotonicity": 0.95, "spread_ci_low": 0.002,
        "net_excess_ci_low_by_k": {"1": -0.01, "5": 0.001, "10": 0.002, "25": 0.001, "50": 0.0005},
        "net_excess_k10": 0.006, "net_excess_k10_ci_low": 0.002,
        "paired_ci_low": {name: 0.001 for name in BASELINE_NAMES},
        "permutation_net_excess": [0.0001, -0.0002, 0.0003, 0.0, -0.0001, 0.0002, 0.0001, -0.0003],
        "mean_ic": 0.03, "permutation_ic": [0.001, -0.002, 0.0, 0.002, -0.001, 0.001, 0.0, -0.001],
        "selection_null_p": 1 / 1001,
        "ex_top_sessions_ci_low": 0.001, "ex_top_sessions_mean": 0.002,
        "ex_top_stocks_ci_low": 0.001, "ex_top_stocks_mean": 0.002,
        "max_sector_share": 0.2, "max_year_share": 0.3,
        "regime_ci_high": {f"{d}:{b}": 0.01 for d, bs in REGIME_BUCKETS.items() for b in bs},
        "regime_sessions": {f"{d}:{b}": 40 for d, bs in REGIME_BUCKETS.items() for b in bs},
    }


def test_thresholds_are_the_frozen_protocol_numbers():
    assert evidence.RULES_VERSION == "SEL-002-EVIDENCE-002"
    assert (evidence.IC_SHARE_MIN, evidence.MONOTONICITY_MIN, evidence.BREADTH_K) == (0.55, 0.8, (5, 10, 25, 50))
    assert (evidence.PERMUTATION_RUNS, evidence.PERMUTATION_SD_MULTIPLE, evidence.SELECTION_NULL_P_MAX) == (8, 3.0, 0.01)
    assert evidence.MAX_GROUP_SHARE == 0.40
    assert evidence.MIN_REGIME_SESSIONS == 250
    checks = horizon_checks(strong())
    assert len(checks) == 4 + 4 + 7 + 4 + 4 + 14
    assert sum(1 for c in checks if c.group == "core") == 19
    assert sum(1 for c in checks if c.group == "robustness") == 18


def test_every_check_at_both_horizons_with_no_flags_is_class_a():
    out = classify({3: strong(), 5: strong()})
    assert out["class"] == "A"
    assert out["dependencies"] == []
    assert out["major_flags"]["3"] == [] and out["major_flags"]["5"] == []
    assert out["minor_flags"]["3"] == [] and out["minor_flags"]["5"] == []
    assert out["core_hold"]["3"] and out["core_hold"]["5"]


@pytest.mark.parametrize("key, value, rule", [
    ("ic_share_positive", 0.54, "E1"), ("bucket_monotonicity", 0.79, "E1"),
    ("net_excess_ci_low_by_k", {"1": 0.0, "5": 0.001, "10": 0.002, "25": 0.001, "50": -0.0001}, "E2"),
    ("selection_null_p", 0.011, "E4"),
])
def test_any_single_failed_core_check_with_positive_evidence_is_class_b(key, value, rule):
    weak = strong()
    weak[key] = value
    out = classify({3: strong(), 5: weak})
    assert out["class"] == "B"
    assert not out["core_hold"]["5"] and out["core_hold"]["3"]
    assert not out["rules_hold"]["5"][rule] and all(out["rules_hold"]["3"].values())
    assert evidence.DEPENDENCY[rule] in out["dependencies"]


def test_permutation_rules_need_all_eight_runs_and_three_sd():
    close = strong()
    close["net_excess_k10"] = 0.00035  # above every run, but not 3 sd above their mean
    checks = {c.name: c for c in horizon_checks(close) if c.rule == "E4"}
    assert checks["K=10 net excess minus the best of 8 permutation runs"].holds
    assert not checks["K=10 net excess above the permutation mean, in permutation sd"].holds
    short = strong()
    short["permutation_net_excess"] = short["permutation_net_excess"][:7]
    assert not all(c.holds for c in horizon_checks(short) if c.rule == "E4")


def test_a_thin_significantly_negative_regime_still_gives_a():
    bad = strong()
    bad["regime_ci_high"]["volatility:HIGH"] = -0.0001
    bad["regime_sessions"]["volatility:HIGH"] = 100  # below MIN_REGIME_SESSIONS: minor, not major
    out = classify({3: bad, 5: strong()})
    assert out["class"] == "A"
    assert not out["rules_hold"]["3"]["E6"]
    assert any(f["rule"] == "E6" and f["kind"] == "minor" for f in out["minor_flags"]["3"])
    assert out["major_flags"]["3"] == [] and out["major_flags"]["5"] == []
    undefined = strong()
    undefined["regime_ci_high"]["fv002:BEARISH"] = None
    assert classify({3: undefined, 5: strong()})["class"] == "A"


def test_a_well_populated_losing_regime_is_a_major_flag_and_gives_b():
    bad = strong()
    bad["regime_ci_high"]["volatility:HIGH"] = -0.0001
    bad["regime_sessions"]["volatility:HIGH"] = 250  # >= MIN_REGIME_SESSIONS: major (F5)
    out = classify({3: bad, 5: strong()})
    assert out["class"] == "B"
    assert any(f["id"] == "F5" for f in out["major_flags"]["3"])


def test_a_failed_ex_top_ci_check_blocks_a_only_when_its_mean_is_not_positive():
    ci_dips = strong()
    ci_dips["ex_top_sessions_ci_low"] = -0.0001  # CI dips below 0, mean (0.002) stays positive
    out = classify({3: strong(), 5: ci_dips})
    assert out["class"] == "A"
    assert out["major_flags"]["5"] == []  # F1 does not trigger: the mean is still positive
    assert any(f["rule"] == "E5" and f["kind"] == "minor" for f in out["minor_flags"]["5"])
    mean_fails = strong()
    mean_fails["ex_top_sessions_ci_low"] = -0.0001
    mean_fails["ex_top_sessions_mean"] = 0.0
    bad = classify({3: strong(), 5: mean_fails})
    assert bad["class"] == "B"
    assert any(f["id"] == "F1" for f in bad["major_flags"]["5"])


def test_a_year_share_over_threshold_is_a_major_flag_and_gives_b():
    weak = strong()
    weak["max_year_share"] = 0.41
    out = classify({3: strong(), 5: weak})
    assert out["class"] == "B"
    assert any(f["id"] == "F3" for f in out["major_flags"]["5"])


def test_no_positive_evidence_is_class_c_and_either_kind_is_enough_for_b():
    none = strong()
    none.update(ic_ci_low=-0.001, net_excess_k10_ci_low=-0.001)
    assert classify({3: copy.deepcopy(none), 5: copy.deepcopy(none)})["class"] == "C"
    ic_only = copy.deepcopy(none)
    ic_only["ic_ci_low"] = 0.0001
    assert classify({3: none, 5: ic_only})["class"] == "B"
    k10_only = copy.deepcopy(none)
    k10_only["net_excess_k10_ci_low"] = 0.0001
    assert classify({3: k10_only, 5: none})["class"] == "B"


def test_both_horizons_are_required():
    with pytest.raises(ValueError):
        classify({3: strong()})


def test_undefined_f_input_is_itself_a_major_flag():
    undefined_mean = strong()
    undefined_mean["ex_top_sessions_mean"] = None
    out = classify({3: strong(), 5: undefined_mean})
    assert any(f["id"] == "F1" for f in out["major_flags"]["5"])
    assert out["class"] == "B"


def test_rules_module_has_no_runtime_inputs():
    """The rules read only their frozen constants and the numbers handed to them (no settings, no files)."""
    source = pathlib.Path(evidence.__file__).read_text(encoding="utf-8")
    assert "settings" not in source and "open(" not in source and "environ" not in source
```

- [ ] **Step 2: Run them; expect FAIL** (`ImportError: cannot import name 'evidence' from 'app.selection_diagnostics'`)

```bash
python -m pytest tests/test_sel002_evidence.py -q -p no:cacheprovider
```

- [ ] **Step 3: Implement**

`app/selection_diagnostics/evidence.py`:

```python
"""SEL-002 §7 predeclared evidence rules E1-E6, major red flags F1-F5, and the A/B/C classification. Diagnostic
only: no publication decision.

Frozen before any SEL-002 number is computed; changing anything here is a new rules version and a new run."""
from __future__ import annotations

import math
from dataclasses import asdict, dataclass

from .config import BASELINE_NAMES, REGIME_BUCKETS

RULES_VERSION = "SEL-002-EVIDENCE-002"
IC_SHARE_MIN = 0.55
MONOTONICITY_MIN = 0.8
BREADTH_K = (5, 10, 25, 50)
PERMUTATION_RUNS = 8
PERMUTATION_SD_MULTIPLE = 3.0
SELECTION_NULL_P_MAX = 0.01
MAX_GROUP_SHARE = 0.40
MIN_REGIME_SESSIONS = 250
HORIZONS = (3, 5)

CLASSES = {
    "A": ("STRONG EVIDENCE OF A RANKING SIGNAL", "Recommend TG-001; resuming it still needs the user's approval."),
    "B": ("MEANINGFUL BUT INCOMPLETE OR FRAGILE EVIDENCE", "Investigate and revise SEL-001 before trade geometry."),
    "C": ("NO EVIDENCE OF A USEFUL RANKING SIGNAL", "Stop investing in the current SEL-001 architecture; redesign "
                                                     "the selection model, features or objective."),
}
DEPENDENCY = {"E1": "deciles and ranking", "E2": "K", "E3": "simple baselines", "E4": "null expectations",
              "E5": "period or a small subset of observations", "E6": "regime"}
GROUP = {"E1": "core", "E2": "core", "E3": "core", "E4": "core", "E5": "robustness", "E6": "robustness"}


@dataclass(frozen=True)
class Check:
    rule: str
    name: str
    value: float | None
    threshold: str
    holds: bool
    group: str


@dataclass(frozen=True)
class Flag:
    id: str
    kind: str
    rule: str
    name: str
    value: object


def _num(value) -> float | None:
    return None if value is None or (isinstance(value, float) and math.isnan(value)) else float(value)


def _gt(value, threshold) -> bool:
    v = _num(value)
    return v is not None and v > threshold


def _ge(value, threshold) -> bool:
    v = _num(value)
    return v is not None and v >= threshold


def _le(value, threshold) -> bool:
    v = _num(value)
    return v is not None and v <= threshold


def _le_or_undefined(value, threshold) -> bool:
    v = _num(value)
    return v is None or v <= threshold


def _gt_or_undefined(value, threshold) -> bool:
    v = _num(value)
    return v is None or v > threshold


def horizon_checks(e: dict) -> list[Check]:
    def check(rule, name, value, threshold, holds):
        return Check(rule, name, value, threshold, holds, GROUP[rule])

    checks = [
        check("E1", "mean Spearman IC CI_low", _num(e["ic_ci_low"]), "> 0", _gt(e["ic_ci_low"], 0)),
        check("E1", "share of sessions with IC > 0", _num(e["ic_share_positive"]), f">= {IC_SHARE_MIN}",
              _ge(e["ic_share_positive"], IC_SHARE_MIN)),
        check("E1", "bucket monotonicity Spearman", _num(e["bucket_monotonicity"]), f">= {MONOTONICITY_MIN}",
              _ge(e["bucket_monotonicity"], MONOTONICITY_MIN)),
        check("E1", "top-minus-bottom decile spread CI_low", _num(e["spread_ci_low"]), "> 0",
              _gt(e["spread_ci_low"], 0)),
    ]
    for k in BREADTH_K:
        value = e["net_excess_ci_low_by_k"].get(str(k))
        checks.append(check("E2", f"K={k} net excess CI_low", _num(value), "> 0", _gt(value, 0)))
    for name in BASELINE_NAMES:
        value = e["paired_ci_low"].get(name)
        checks.append(check("E3", f"K=10 paired net excess vs {name} CI_low", _num(value), "> 0", _gt(value, 0)))

    observed = _num(e["net_excess_k10"])
    perm = [_num(v) for v in e["permutation_net_excess"]]
    complete = len(perm) == PERMUTATION_RUNS and all(v is not None for v in perm) and observed is not None
    if complete:
        mean = sum(perm) / len(perm)
        sd = math.sqrt(sum((v - mean) ** 2 for v in perm) / (len(perm) - 1))
        margin, z_ok = observed - max(perm), observed - mean >= PERMUTATION_SD_MULTIPLE * sd
        z = (observed - mean) / sd if sd > 0 else None
    else:
        margin, z_ok, z = None, False, None
    checks.append(check("E4", "K=10 net excess minus the best of 8 permutation runs", margin, "> 0",
                        complete and margin > 0))
    checks.append(check("E4", "K=10 net excess above the permutation mean, in permutation sd", z,
                        f">= {PERMUTATION_SD_MULTIPLE}", complete and z_ok))
    ic, perm_ic = _num(e["mean_ic"]), [_num(v) for v in e["permutation_ic"]]
    ic_complete = len(perm_ic) == PERMUTATION_RUNS and all(v is not None for v in perm_ic) and ic is not None
    ic_margin = ic - max(perm_ic) if ic_complete else None
    checks.append(check("E4", "mean IC minus the best of 8 permutation ICs", ic_margin, "> 0",
                        ic_complete and ic_margin > 0))
    checks.append(check("E4", "selection-null p", _num(e["selection_null_p"]), f"<= {SELECTION_NULL_P_MAX}",
                        _le(e["selection_null_p"], SELECTION_NULL_P_MAX)))

    checks += [
        check("E5", "net excess CI_low without the top 1% of sessions", _num(e["ex_top_sessions_ci_low"]), "> 0",
              _gt(e["ex_top_sessions_ci_low"], 0)),
        check("E5", "net excess CI_low without the top 1% of stocks", _num(e["ex_top_stocks_ci_low"]), "> 0",
              _gt(e["ex_top_stocks_ci_low"], 0)),
        check("E5", "largest single-sector share of total net excess", _num(e["max_sector_share"]),
              f"<= {MAX_GROUP_SHARE}", _le(e["max_sector_share"], MAX_GROUP_SHARE)),
        check("E5", "largest single-year share of total net excess", _num(e["max_year_share"]),
              f"<= {MAX_GROUP_SHARE}", _le(e["max_year_share"], MAX_GROUP_SHARE)),
    ]
    for dim, buckets in REGIME_BUCKETS.items():
        for bucket in buckets:
            value = _num(e["regime_ci_high"].get(f"{dim}:{bucket}"))
            checks.append(check("E6", f"{dim}={bucket} net excess CI_high", value, "not < 0",
                                value is None or value >= 0))
    return checks


def positive_evidence(e: dict) -> dict:
    return {"ic_ci_low_gt_0": _gt(e["ic_ci_low"], 0), "k10_net_excess_ci_low_gt_0": _gt(e["net_excess_k10_ci_low"], 0)}


def major_flags(e: dict) -> list[Flag]:
    """§7.2 F1-F5: robustness failures that, unlike a thin regime or a CI dip with a positive mean, block class A."""
    flags = []
    if _le_or_undefined(e["ex_top_sessions_mean"], 0):
        flags.append(Flag("F1", "major", "E5", "session dependence: ex-top-1%-sessions K=10 mean net excess <= 0 "
                                               "or undefined", _num(e["ex_top_sessions_mean"])))
    if _le_or_undefined(e["ex_top_stocks_mean"], 0):
        flags.append(Flag("F2", "major", "E5", "stock dependence: ex-top-1%-stocks K=10 mean net excess <= 0 or "
                                               "undefined", _num(e["ex_top_stocks_mean"])))
    if _gt_or_undefined(e["max_year_share"], MAX_GROUP_SHARE):
        flags.append(Flag("F3", "major", "E5", "period dependence: largest single-year share > 0.40 or undefined",
                          _num(e["max_year_share"])))
    if _gt_or_undefined(e["max_sector_share"], MAX_GROUP_SHARE):
        flags.append(Flag("F4", "major", "E5", "sector dependence: largest single-sector share (UNKNOWN excluded) "
                                               "> 0.40 or undefined", _num(e["max_sector_share"])))
    losing = []
    for dim, buckets in REGIME_BUCKETS.items():
        for bucket in buckets:
            key = f"{dim}:{bucket}"
            ci_high = _num(e["regime_ci_high"].get(key))
            sessions = e["regime_sessions"].get(key)
            if ci_high is not None and ci_high < 0 and sessions is not None and sessions >= MIN_REGIME_SESSIONS:
                losing.append(key)
    if losing:
        flags.append(Flag("F5", "major", "E6", f"well-populated losing regime (>= {MIN_REGIME_SESSIONS} sessions "
                                               "with >= 1 accepted K=10 trade)", losing))
    return flags


def minor_flags(e: dict, major: list[Flag]) -> list[Flag]:
    """Robustness failures reported for completeness; unlike F1-F5 they never block class A."""
    major_ids = {f.id for f in major}
    flags = []
    if "F1" not in major_ids and not _gt(e["ex_top_sessions_ci_low"], 0):
        flags.append(Flag("ex_top_sessions_ci_low", "minor", "E5", "net excess CI_low without the top 1% of "
                                                                    "sessions <= 0, but the mean is positive",
                          _num(e["ex_top_sessions_ci_low"])))
    if "F2" not in major_ids and not _gt(e["ex_top_stocks_ci_low"], 0):
        flags.append(Flag("ex_top_stocks_ci_low", "minor", "E5", "net excess CI_low without the top 1% of stocks "
                                                                  "<= 0, but the mean is positive",
                          _num(e["ex_top_stocks_ci_low"])))
    major_regimes = set()
    for f in major:
        if f.id == "F5":
            major_regimes.update(f.value)
    for dim, buckets in REGIME_BUCKETS.items():
        for bucket in buckets:
            key = f"{dim}:{bucket}"
            if key in major_regimes:
                continue
            ci_high = _num(e["regime_ci_high"].get(key))
            if ci_high is not None and ci_high < 0:
                flags.append(Flag(key, "minor", "E6", f"thin negative regime {key} (< {MIN_REGIME_SESSIONS} "
                                                       "sessions with an accepted K=10 trade)", ci_high))
    return flags


def classify(inputs_by_horizon: dict) -> dict:
    """A: all 19 core checks (E1-E4) hold at h=3 and h=5, and no major flag (F1-F5) at either horizon.
    B: not A, but meaningful core evidence exists (mean IC CI_low > 0 or K=10 net-excess CI_low > 0 at either
    horizon). C: otherwise."""
    inputs = {int(h): v for h, v in inputs_by_horizon.items()}
    if set(inputs) != set(HORIZONS):
        raise ValueError(f"SEL-002: evidence needs horizons {HORIZONS}, got {sorted(inputs)}")
    checks = {h: horizon_checks(inputs[h]) for h in HORIZONS}
    positive = {h: positive_evidence(inputs[h]) for h in HORIZONS}
    core_hold = {h: all(c.holds for c in checks[h] if c.group == "core") for h in HORIZONS}
    major = {h: major_flags(inputs[h]) for h in HORIZONS}
    minor = {h: minor_flags(inputs[h], major[h]) for h in HORIZONS}
    if all(core_hold[h] for h in HORIZONS) and not any(major[h] for h in HORIZONS):
        label = "A"
    elif any(any(p.values()) for p in positive.values()):
        label = "B"
    else:
        label = "C"
    failing = {h: [asdict(c) for c in checks[h] if not c.holds] for h in HORIZONS}
    rules = {h: {r: all(c.holds for c in checks[h] if c.rule == r) for r in DEPENDENCY} for h in HORIZONS}
    return {
        "rules_version": RULES_VERSION, "class": label, "label": CLASSES[label][0], "recommendation": CLASSES[label][1],
        "checks": {str(h): [asdict(c) for c in checks[h]] for h in HORIZONS},
        "rules_hold": {str(h): rules[h] for h in HORIZONS},
        "core_hold": {str(h): core_hold[h] for h in HORIZONS},
        "positive_evidence": {str(h): positive[h] for h in HORIZONS},
        "failing": {str(h): failing[h] for h in HORIZONS},
        "major_flags": {str(h): [asdict(f) for f in major[h]] for h in HORIZONS},
        "minor_flags": {str(h): [asdict(f) for f in minor[h]] for h in HORIZONS},
        "dependencies": sorted({DEPENDENCY[c["rule"]] for h in HORIZONS for c in failing[h]}),
    }
```

- [ ] **Step 4: Run them; expect PASS** (`15 passed`)

```bash
python -m pytest tests/test_sel002_evidence.py -q -p no:cacheprovider
git diff --exit-code 8944bf4 -- app/selection_gate scripts/run_selection_gate.py
```

- [ ] **Step 5: Commit**

```bash
git add app/selection_diagnostics/evidence.py tests/test_sel002_evidence.py
git commit -m "SEL-002: predeclared evidence rules E1-E6, major red flags F1-F5, and the A/B/C classification"
```

---

### Task 13: Refit jobs, the process pool and the parity gate

**Files:**
- Create: `app/selection_diagnostics/refits.py`
- Test: `tests/test_sel002_refits.py`

**Interfaces:**
- Consumes: `walk_forward` (Task 8), `permute_within_session` (Task 5), `evaluate` (Task 7), the `files` helpers and `VARIANTS` (Task 1); SPG-001 `measured()`.
- Produces:
  - `RefitJob(horizon, variant, seed, features)`, with `.job_id` such as `h3__FULL`, `h5__LOGO-VOL` or `h3__PERM-4`
  - `refit_jobs(cfg) -> list[RefitJob]` (40 with the frozen config, the two FULL jobs first)
  - `inputs_path(out_dir, horizon)` (`refit_inputs_h{h}.pkl`: `rows`, `target`, `windows`, `session_dates`, `horizon`) and `job_sha256(job, *, config_sha, inputs_sha)`
  - `run_job(job, out_dir, config_sha, inputs_sha) -> meta`
  - `run_jobs(jobs, out_dir, *, workers, config_sha) -> list[meta]` (spawn pool when `workers > 1`)
  - `load_scores(out_dir, job_id, index) -> (Series, tested)`
  - `parity_check(*, dataset, horizon, cached, cached_tested, loop, loop_tested, tolerance, top_k) -> {pass, mode: SCORES|TOP_K|None, reason, max_abs_delta, rows_compared, trades, mean_excess}`

**Data dependencies:** the synthetic world (h=3), written to a temporary refit-input pickle.

**Compute:** about 30 s, including one 2-process spawn pool (each child imports `app` and xgboost).

**Acceptance criteria:**
- `6 passed`.
- The job list is the predeclared 40.
- A job reruns only when its config or inputs change (Review Focus 2).
- The FULL job equals `runner._walk_forward`, and parallel workers equal one process.
- Parity passes in SCORES and TOP_K modes and fails on different sessions, different scored rows (Review Focus 3) or a different top K.

- [ ] **Step 1: Write the failing tests**

`tests/test_sel002_refits.py`:

```python
import os
from collections import Counter

import numpy as np
import pytest

from app.selection_diagnostics.config import DiagnosticConfig
from app.selection_diagnostics.files import write_pickle
from app.selection_diagnostics.heldout import diagnostic_windows, restrict
from app.selection_diagnostics.refits import (
    inputs_path, load_scores, parity_check, refit_jobs, run_jobs,
)
from app.selection_gate import runner
from app.selection_gate.constants import SEL001_MODEL_VERSION
from app.selection_gate.dataset import FEATURE_COLUMNS
from app.settings import settings
from tests._sel002_factories import EXAM_START, small_config, synthetic_dataset


@pytest.fixture(autouse=True)
def small(monkeypatch):
    monkeypatch.setattr(settings, "selection_min_fit_rows", 500)
    monkeypatch.setattr(settings, "selection_bootstrap_draws", 200)


def test_the_frozen_job_list_is_forty_with_parity_first():
    jobs = refit_jobs(DiagnosticConfig())
    assert len(jobs) == 40 and len({j.job_id for j in jobs}) == 40
    assert [j.job_id for j in jobs[:2]] == ["h3__FULL", "h5__FULL"]
    assert Counter(j.variant for j in jobs) == {"FULL": 2, "PERM": 16, **{v: 2 for v in DiagnosticConfig().variants}}
    assert sorted(j.seed for j in jobs if j.variant == "PERM" and j.horizon == 3) == list(range(1, 9))
    assert jobs[-1].job_id == "h5__PERM-8"


@pytest.fixture(scope="module")
def prepared(tmp_path_factory):
    restricted, _ = restrict(synthetic_dataset(horizons=(3,)), EXAM_START)
    windows = diagnostic_windows(restricted, 3, small_config())
    target = runner._excess_target(restricted.rows, restricted.benchmarks[3], 3)
    out = tmp_path_factory.mktemp("sel002")
    write_pickle(inputs_path(out, 3), {"rows": restricted.rows[["session_index", "stock_id", *FEATURE_COLUMNS]],
                                       "target": target, "windows": windows,
                                       "session_dates": restricted.session_dates, "horizon": 3})
    return restricted, windows, target, out


def test_jobs_are_deterministic_resumable_and_reproduce_spg(prepared):
    dataset, windows, target, out = prepared
    cfg = small_config(horizons=(3,), variants=("VOLUME-ONLY",), permutation_seeds=(1,))
    jobs = refit_jobs(cfg)
    first = run_jobs(jobs, out, workers=1, config_sha=cfg.sha256())
    again = run_jobs(jobs, out, workers=1, config_sha=cfg.sha256())
    assert [m["metrics"] for m in first] == [m["metrics"] for m in again]  # resumed, not rerun
    full, tested = load_scores(out, "h3__FULL", dataset.rows.index)
    spg, _, spg_tested = runner._walk_forward(SEL001_MODEL_VERSION, dataset, windows, 3, target)
    np.testing.assert_array_equal(full.to_numpy(), spg.to_numpy())
    assert tested == spg_tested
    perm, _ = load_scores(out, "h3__PERM-1", dataset.rows.index)
    assert not np.array_equal(perm.to_numpy(), full.to_numpy(), equal_nan=True)


def test_parallel_workers_give_the_same_scores_as_one_process(prepared, tmp_path, monkeypatch):
    monkeypatch.setenv("SELECTION_MIN_FIT_ROWS", "500")  # spawned workers read settings afresh
    dataset, _, _, out = prepared
    parallel = tmp_path / "parallel"
    parallel.mkdir()
    (parallel / inputs_path(out, 3).name).write_bytes(inputs_path(out, 3).read_bytes())
    cfg = small_config(horizons=(3,), variants=("VOLUME-ONLY",), permutation_seeds=(1,))
    jobs = refit_jobs(cfg)
    run_jobs(jobs, out, workers=1, config_sha=cfg.sha256())
    metas = run_jobs(jobs, parallel, workers=2, config_sha=cfg.sha256())
    assert all(m["pid"] != os.getpid() for m in metas)
    for job in jobs:
        a, _ = load_scores(out, job.job_id, dataset.rows.index)
        b, _ = load_scores(parallel, job.job_id, dataset.rows.index)
        np.testing.assert_array_equal(a.to_numpy(), b.to_numpy())


def test_parity_passes_on_equal_scores_and_falls_back_to_identical_top_k(prepared):
    dataset, windows, target, _ = prepared
    score = dataset.rows.return_5d.astype(float)
    tested = list(range(130, 250))
    exact = parity_check(dataset=dataset, horizon=3, cached=score, cached_tested=tested, loop=score.copy(),
                         loop_tested=tested, tolerance=1e-6, top_k=2)
    assert exact["pass"] and exact["mode"] == "SCORES" and exact["max_abs_delta"] == 0.0
    scaled = parity_check(dataset=dataset, horizon=3, cached=score, cached_tested=tested, loop=score * 2,
                          loop_tested=tested, tolerance=1e-6, top_k=2)
    assert scaled["pass"] and scaled["mode"] == "TOP_K"
    reversed_ = parity_check(dataset=dataset, horizon=3, cached=score, cached_tested=tested, loop=-score,
                             loop_tested=tested, tolerance=1e-6, top_k=2)
    assert not reversed_["pass"] and reversed_["reason"] == "TOP_K_DIFFERS"
    shorter = parity_check(dataset=dataset, horizon=3, cached=score, cached_tested=tested, loop=score,
                           loop_tested=tested[:-1], tolerance=1e-6, top_k=2)
    assert not shorter["pass"] and shorter["reason"] == "TESTED_SESSIONS_DIFFER"


def test_a_job_reruns_when_its_config_changes(prepared):
    _, _, _, out = prepared
    cfg = small_config(horizons=(3,), variants=(), permutation_seeds=())
    jobs = refit_jobs(cfg)
    first = run_jobs(jobs, out, workers=1, config_sha="config-a")
    second = run_jobs(jobs, out, workers=1, config_sha="config-b")
    assert first[0]["job_sha256"] != second[0]["job_sha256"]
    assert second[0]["metrics"] != first[0]["metrics"]


def test_parity_fails_when_the_loop_scores_rows_the_cache_did_not(prepared):
    dataset, _, _, _ = prepared
    tested = list(range(130, 250))
    cached = dataset.rows.return_5d.astype(float).where(dataset.rows.session_index.isin(tested))
    loop = cached.copy()
    loop.iloc[int(np.flatnonzero(cached.notna().to_numpy())[0])] = np.nan
    out = parity_check(dataset=dataset, horizon=3, cached=cached, cached_tested=tested, loop=loop,
                       loop_tested=tested, tolerance=1e-6, top_k=2)
    assert not out["pass"] and out["reason"] == "SCORED_ROWS_DIFFER"
```

- [ ] **Step 2: Run them; expect FAIL** (`ModuleNotFoundError: No module named 'app.selection_diagnostics.refits'`)

```bash
python -m pytest tests/test_sel002_refits.py -q -p no:cacheprovider
```

- [ ] **Step 3: Implement**

`app/selection_diagnostics/refits.py`:

```python
"""SEL-002 refit runs (parity, ablation variants, label permutations) in parallel processes, and the parity gate."""
from __future__ import annotations

import hashlib
import json
import multiprocessing
import os
import pathlib
from concurrent.futures import ProcessPoolExecutor
from dataclasses import asdict, dataclass

import numpy as np
import pandas as pd

from app.selection_gate.dataset import FEATURE_COLUMNS
from app.selection_gate.metrics import measured

from .config import FULL, PERMUTATION, SEL001, VARIANTS
from .files import file_sha256, read_json, read_pickle, write_json
from .harness import evaluate
from .strategies import permute_within_session
from .wf_loop import walk_forward


@dataclass(frozen=True)
class RefitJob:
    horizon: int
    variant: str
    seed: int | None
    features: tuple[str, ...]

    @property
    def job_id(self) -> str:
        name = self.variant if self.seed is None else f"{self.variant}-{self.seed}"
        return f"h{self.horizon}__{name}"


def refit_jobs(cfg) -> list[RefitJob]:
    """Parity (FULL) first, then the ablation variants, then the permutation seeds; 40 with the frozen config."""
    jobs = [RefitJob(h, FULL, None, tuple(FEATURE_COLUMNS)) for h in cfg.horizons]
    jobs += [RefitJob(h, name, None, VARIANTS[name]) for h in cfg.horizons for name in cfg.variants]
    jobs += [RefitJob(h, PERMUTATION, seed, tuple(FEATURE_COLUMNS)) for h in cfg.horizons for seed in cfg.permutation_seeds]
    return jobs


def inputs_path(out_dir, horizon: int) -> pathlib.Path:
    return pathlib.Path(out_dir) / f"refit_inputs_h{horizon}.pkl"


def job_sha256(job: RefitJob, *, config_sha: str, inputs_sha: str) -> str:
    payload = json.dumps({"job": asdict(job), "config_sha256": config_sha, "inputs_sha256": inputs_sha}, sort_keys=True)
    return hashlib.sha256(payload.encode("utf-8")).hexdigest()


def run_job(job: RefitJob, out_dir: str, config_sha: str, inputs_sha: str) -> dict:
    """One walk-forward refit; resumable: a finished job with the same inputs is not run again."""
    folder = pathlib.Path(out_dir) / "refits"
    folder.mkdir(parents=True, exist_ok=True)
    sha = job_sha256(job, config_sha=config_sha, inputs_sha=inputs_sha)
    meta_path = folder / f"{job.job_id}.json"
    if meta_path.exists() and (folder / f"{job.job_id}.npz").exists() and read_json(meta_path)["job_sha256"] == sha:
        return read_json(meta_path)
    with measured() as metrics:
        payload = read_pickle(inputs_path(out_dir, job.horizon))
        rows, target = payload["rows"], payload["target"]
        if job.seed is not None:
            target = permute_within_session(target, rows.session_index.to_numpy(), job.seed)
        scores, folds, tested = walk_forward(rows, payload["session_dates"], payload["windows"], job.horizon, target,
                                             job.features)
        tmp = folder / f"{job.job_id}.tmp.npz"
        np.savez(tmp, scores=scores.to_numpy(), index=scores.index.to_numpy(), tested=np.asarray(tested, dtype=np.int64))
        os.replace(tmp, folder / f"{job.job_id}.npz")
    meta = {"job_id": job.job_id, "job": asdict(job), "job_sha256": sha, "folds": folds,
            "tested_sessions": len(set(tested)), "metrics": metrics, "pid": os.getpid()}
    write_json(meta_path, meta)
    return meta


def run_jobs(jobs: list[RefitJob], out_dir, *, workers: int, config_sha: str) -> list[dict]:
    inputs_sha = {h: file_sha256(inputs_path(out_dir, h)) for h in sorted({j.horizon for j in jobs})}
    if workers <= 1:
        return [run_job(j, str(out_dir), config_sha, inputs_sha[j.horizon]) for j in jobs]
    with ProcessPoolExecutor(max_workers=workers, mp_context=multiprocessing.get_context("spawn")) as pool:
        futures = [pool.submit(run_job, j, str(out_dir), config_sha, inputs_sha[j.horizon]) for j in jobs]
        return [f.result() for f in futures]


def load_scores(out_dir, job_id: str, index: pd.Index) -> tuple[pd.Series, list[int]]:
    data = np.load(pathlib.Path(out_dir) / "refits" / f"{job_id}.npz")
    scores = pd.Series(data["scores"], index=data["index"]).reindex(index)
    return scores, [int(i) for i in data["tested"]]


def parity_check(*, dataset, horizon: int, cached: pd.Series, cached_tested, loop: pd.Series, loop_tested,
                 tolerance: float, top_k: int) -> dict:
    """PASS when the loop's FULL scores equal the cached SEL-001 scores within `tolerance`, or failing that when the
    top-K per session, the accepted trades and mean_excess are identical (spec §3)."""
    result = {"horizon": horizon, "tolerance": tolerance, "pass": False, "mode": None}
    if sorted(set(cached_tested)) != sorted(set(loop_tested)):
        return {**result, "reason": "TESTED_SESSIONS_DIFFER"}
    window = dataset.rows.session_index.isin(set(cached_tested)).to_numpy()
    a = cached.reindex(dataset.rows.index).to_numpy(dtype=float)[window]
    b = loop.reindex(dataset.rows.index).to_numpy(dtype=float)[window]
    if not np.array_equal(np.isnan(a), np.isnan(b)):
        return {**result, "reason": "SCORED_ROWS_DIFFER"}
    defined = ~np.isnan(a)
    delta = float(np.max(np.abs(a[defined] - b[defined]))) if defined.any() else 0.0
    run_a = evaluate(dataset, cached, cached_tested, horizon, top_k, SEL001)
    result.update(max_abs_delta=delta, rows_compared=int(defined.sum()), trades=run_a.statistics.trades,
                  mean_excess=run_a.statistics.mean_excess)
    if delta <= tolerance:
        return {**result, "pass": True, "mode": "SCORES", "reason": None}
    run_b = evaluate(dataset, loop, loop_tested, horizon, top_k, SEL001)
    picks = lambda run: [(t.session_index, t.rank, t.stock_id, t.disposition) for t in run.trades]  # noqa: E731
    same = (picks(run_a) == picks(run_b) and run_a.statistics.trades == run_b.statistics.trades
            and run_a.statistics.mean_excess == run_b.statistics.mean_excess)
    return {**result, "pass": same, "mode": "TOP_K" if same else None, "reason": None if same else "TOP_K_DIFFERS"}
```

- [ ] **Step 4: Run them; expect PASS** (`6 passed`; a `Windows fatal exception: access violation` dump from the spawned workers is benign (see Global Constraints))

```bash
python -m pytest tests/test_sel002_refits.py -q -p no:cacheprovider
git diff --exit-code 8944bf4 -- app/selection_gate scripts/run_selection_gate.py
```

- [ ] **Step 5: Commit**

```bash
git add app/selection_diagnostics/refits.py tests/test_sel002_refits.py
git commit -m "SEL-002: resumable parallel refit jobs and the parity gate"
```

---

### Task 14: Every analysis for one horizon

**Files:**
- Create: `app/selection_diagnostics/analysis.py`, `tests/_sel002_world.py`
- Test: `tests/test_sel002_analysis.py`

**Interfaces:**
- Consumes: Tasks 2 and 4–11.
- Produces: `analyze_horizon(*, dataset, horizon, windows, tested, sel001_scores, variant_scores: {variant: Series}, permutation_scores: {seed: Series}, liquidity: Series, sectors: {stock_id: str|None}, cfg) -> dict`. The keys are `horizon`, `tested_sessions`, `outcome_rows`, `resolved_rows`, `k_sensitivity` ({strategy: {str K: summary}}), `random_distribution`, `baselines_paired_k10`, `ranking`, `buckets`, `ablation`, `features`, `selection_null`, `label_permutation`, `concentration`, `regimes` and `evidence_inputs` (the Task 12 contract, including `ex_top_sessions_mean`, `ex_top_stocks_mean` and `regime_sessions`, all read straight off `concentration` and `regimes`, which already carry them).
- Test helper: `analyze_world(source, cfg=None, horizon=3) -> dict`.

**Data dependencies:** the synthetic world, with real refits through `walk_forward` (SEL-001, 2 variants, 2 permutations).

**Compute:** about 30 s.

**Acceptance criteria:**
- `5 passed`.
- Every predeclared analysis is present.
- The planted signal is found, and random is near zero.
- `evidence_inputs` classify without error (37 checks).
- Poisoning every held-out value leaves the whole result byte-identical.
- An unrestricted dataset is refused.

- [ ] **Step 1: Write the failing tests**

`tests/test_sel002_analysis.py`:

```python
import dataclasses
import json

import numpy as np
import pandas as pd
import pytest

from app.selection_diagnostics.analysis import analyze_horizon
from app.selection_diagnostics.config import BASELINE_NAMES, SEL001
from app.selection_diagnostics.evidence import classify
from app.selection_diagnostics.files import jsonable
from app.selection_diagnostics.heldout import HeldOutViolation, diagnostic_windows, first_exam_index, restrict
from app.selection_gate.dataset import FEATURE_COLUMNS
from app.settings import settings
from tests._sel002_factories import EXAM_START, small_config, synthetic_dataset
from tests._sel002_world import analyze_world

CFG = small_config()
SMALL = {"selection_min_fit_rows": 500, "selection_bootstrap_draws": 200}


@pytest.fixture(autouse=True)
def small(monkeypatch):
    for name, value in SMALL.items():
        monkeypatch.setattr(settings, name, value)


@pytest.fixture(scope="module")
def world():
    return synthetic_dataset()


@pytest.fixture(scope="module")
def result(world):
    with pytest.MonkeyPatch.context() as mp:
        for name, value in SMALL.items():
            mp.setattr(settings, name, value)
        return analyze_world(world, CFG)


def test_every_predeclared_analysis_is_present(result):
    assert set(result["k_sensitivity"]) == {SEL001, *BASELINE_NAMES}
    assert set(result["k_sensitivity"][SEL001]) == {"1", "2", "5"}
    assert set(result["baselines_paired_k10"]) == set(BASELINE_NAMES)
    assert len(result["buckets"]["buckets"]) == 12
    assert set(result["ablation"]) == set(CFG.variants)
    assert "incremental_net_excess" in result["ablation"]["LOGO-RET"]
    assert set(result["features"]) == set(FEATURE_COLUMNS)
    assert result["label_permutation"]["seeds"] == [1, 2]
    assert set(result["regimes"]) == {"direction", "volatility", "dispersion", "liquidity", "fv002"}
    assert result["concentration"]["trades"] == result["k_sensitivity"][SEL001]["2"]["trades"]


def test_the_planted_signal_is_found(result):
    assert result["ranking"][SEL001]["ic"]["mean"] > 0.1
    assert result["ranking"]["BASELINE-MOM-5"]["ic"]["mean"] > 0.1
    assert abs(result["ranking"]["BASELINE-RANDOM"]["ic"]["mean"]) < 0.05
    assert result["buckets"]["monotonicity"] > 0.8


def test_evidence_inputs_feed_the_classifier(result):
    out = classify({3: result["evidence_inputs"], 5: result["evidence_inputs"]})
    assert out["class"] in {"A", "B", "C"}
    assert len(out["checks"]["3"]) == 37
    json.dumps(jsonable(result))


def test_held_out_values_cannot_change_any_result(world, result):
    first = first_exam_index(world.session_dates, EXAM_START)
    rows = world.rows.copy()
    late = rows.session_index >= first
    rows.loc[late, list(FEATURE_COLUMNS)] = np.float32(1e6)
    for h in (3, 5):
        rows.loc[rows.session_index + h >= first, f"gross_return_{h}"] = 9.0
    poisoned = dataclasses.replace(world, rows=rows)
    assert json.dumps(jsonable(analyze_world(poisoned, CFG)), sort_keys=True) == \
        json.dumps(jsonable(result), sort_keys=True)


def test_an_unrestricted_dataset_is_refused(world):
    windows = diagnostic_windows(restrict(world, EXAM_START)[0], 3, CFG)
    with pytest.raises(HeldOutViolation):
        analyze_horizon(dataset=world, horizon=3, windows=windows, tested=[], sel001_scores=pd.Series(dtype=float),
                        variant_scores={}, permutation_scores={}, liquidity=pd.Series(dtype=float), sectors={},
                        cfg=CFG)
```

- [ ] **Step 2: Run them; expect FAIL** (`ModuleNotFoundError: No module named 'app.selection_diagnostics.analysis'`)

```bash
python -m pytest tests/test_sel002_analysis.py -q -p no:cacheprovider
```

- [ ] **Step 3: Implement**

`tests/_sel002_world.py`:

```python
"""SEL-002 test helper: every analysis of one horizon on the synthetic world (real refits, small settings)."""
from __future__ import annotations

import pandas as pd

from app.selection_diagnostics.analysis import analyze_horizon
from app.selection_diagnostics.config import VARIANTS
from app.selection_diagnostics.heldout import diagnostic_windows, restrict
from app.selection_diagnostics.strategies import permute_within_session
from app.selection_diagnostics.wf_loop import walk_forward
from app.selection_gate.runner import _excess_target
from tests._sel002_factories import small_config


def analyze_world(source, cfg=None, horizon: int = 3) -> dict:
    cfg = cfg or small_config()
    dataset, _ = restrict(source, cfg.exam_start)
    windows = diagnostic_windows(dataset, horizon, cfg)
    rows = dataset.rows
    target = _excess_target(rows, dataset.benchmarks[horizon], horizon)
    sel, _, tested = walk_forward(rows, dataset.session_dates, windows, horizon, target)
    variants = {n: walk_forward(rows, dataset.session_dates, windows, horizon, target, VARIANTS[n])[0]
                for n in cfg.variants}
    perms = {s: walk_forward(rows, dataset.session_dates, windows, horizon,
                             permute_within_session(target, rows.session_index.to_numpy(), s))[0]
             for s in cfg.permutation_seeds}
    liquidity = pd.Series(1e7 + 1000.0 * rows.stock_id + rows.session_index, index=rows.index)
    sectors = {i: ("BANK" if i % 2 else None) for i in range(1, 41)}
    return analyze_horizon(dataset=dataset, horizon=horizon, windows=windows, tested=tested, sel001_scores=sel,
                           variant_scores=variants, permutation_scores=perms, liquidity=liquidity, sectors=sectors,
                           cfg=cfg)
```

`app/selection_diagnostics/analysis.py`:

```python
"""SEL-002: every predeclared analysis for one horizon, from the restricted dataset and the refit scores."""
from __future__ import annotations

import numpy as np
import pandas as pd

from app.selection_gate.dataset import FEATURE_COLUMNS
from app.selection_gate.runner import _excess_target

from .bootstrap import session_mean_ci
from .concentration import UNKNOWN_SECTOR, concentration_report
from .config import BASELINE_NAMES, REGIME_BUCKETS, SEL001, VARIANTS
from .harness import accepted_trades, evaluate, paired_difference, strategy_summary
from .heldout import assert_restricted, check_tested, first_exam_index
from .nulls import permutation_summary, selection_null
from .ranking import bucket_table, outcome_frame, ranking_summary, scored, session_metrics
from .regimes import regime_report, session_regimes, within_session_terciles
from .strategies import baseline_scores, random_scores
from .wf_loop import fold_signed_scores


def _distribution(values: list, observed) -> dict:
    v = np.asarray([x for x in values if x is not None], dtype=float)
    if v.size == 0:
        return {"runs": 0}
    return {"runs": int(v.size), "mean": float(v.mean()), "sd": float(v.std(ddof=1)) if v.size > 1 else None,
            "min": float(v.min()), "max": float(v.max()),
            "sel001_above_share": None if observed is None else float((observed > v).mean())}


def _paired_ic(a: pd.DataFrame, b: pd.DataFrame, *, horizon: int, cfg) -> dict:
    d = (a.ic - b.ic).dropna().sort_index()
    ci = session_mean_ci(d.to_numpy(), block_length=horizon, draws=cfg.bootstrap_draws,
                         seed=cfg.bootstrap_seed) if len(d) else None
    return {"sessions": int(len(d)), "mean_diff": float(d.mean()) if len(d) else None,
            "ci_low": None if ci is None else ci[0], "ci_high": None if ci is None else ci[1]}


def analyze_horizon(*, dataset, horizon: int, windows, tested, sel001_scores: pd.Series, variant_scores: dict,
                    permutation_scores: dict, liquidity: pd.Series, sectors: dict, cfg) -> dict:
    assert_restricted(dataset, cfg.exam_start)
    check_tested(tested, horizon, first_exam_index(dataset.session_dates, cfg.exam_start))
    rows, cost, k10 = dataset.rows, cfg.cost, cfg.k_primary
    draws, seed = cfg.bootstrap_draws, cfg.bootstrap_seed
    frame = outcome_frame(dataset, horizon, tested)

    def run(scores: pd.Series, k: int, name: str):
        stage = evaluate(dataset, scores, tested, horizon, k, name)
        return stage, accepted_trades(stage, cost)

    def ranking(scores: pd.Series):
        metrics = session_metrics(scored(frame, scores))
        return metrics, ranking_summary(metrics, horizon=horizon, draws=draws, seed=seed)

    strategies = {SEL001: sel001_scores, **baseline_scores(rows, random_seed=cfg.random_seed)}
    k_sensitivity, trades = {}, {}
    for name, scores in strategies.items():
        k_sensitivity[name] = {}
        for k in cfg.k_values:
            stage, t = run(scores, k, name)
            k_sensitivity[name][str(k)] = strategy_summary(stage, t, cfg=cfg)
            if k == k10:
                trades[name] = t
    random_distribution = {}
    for k in cfg.k_values:
        values = [run(random_scores(rows.index, s), k, f"RANDOM-{s}")[0].statistics.mean_excess
                  for s in cfg.random_distribution_seeds]
        random_distribution[str(k)] = _distribution(values, k_sensitivity[SEL001][str(k)]["mean_net_excess"])
    paired = {name: paired_difference(trades[SEL001], trades[name], horizon=horizon, cfg=cfg) for name in BASELINE_NAMES}

    metrics, ranking_by_strategy = {}, {}
    for name, scores in strategies.items():
        metrics[name], ranking_by_strategy[name] = ranking(scores)
    buckets = bucket_table(scored(frame, sel001_scores), horizon=horizon, cost=cost, edges=cfg.bucket_edges,
                           draws=draws, seed=seed)

    ablation = {}
    for name in cfg.variants:
        stage, t = run(variant_scores[name], k10, name)
        m, summary = ranking(variant_scores[name])
        entry = {"features": list(VARIANTS[name]), "k10": strategy_summary(stage, t, cfg=cfg), "ranking": summary}
        if name.startswith("LOGO-"):
            entry["incremental_net_excess"] = paired_difference(trades[SEL001], t, horizon=horizon, cfg=cfg)
            entry["incremental_ic"] = _paired_ic(metrics[SEL001], m, horizon=horizon, cfg=cfg)
        ablation[name] = entry
    target = _excess_target(rows, dataset.benchmarks[horizon], horizon)
    features = {}
    for feature in FEATURE_COLUMNS:
        scores, folds = fold_signed_scores(rows, windows, horizon, target, feature)
        stage, t = run(scores, k10, f"FEATURE:{feature}")
        features[feature] = {"k10": strategy_summary(stage, t, cfg=cfg), "ranking": ranking(scores)[1],
                             "negative_sign_folds": sum(1 for f in folds if f["sign"] == -1.0), "folds": len(folds)}

    sel_null = selection_null(frame, sel001_scores, k=k10, draws=cfg.selection_null_draws,
                              seed=cfg.selection_null_seed, cost=cost)
    runs = {}
    for perm_seed, scores in sorted(permutation_scores.items()):
        stage, _ = run(scores, k10, f"PERM-{perm_seed}")
        runs[int(perm_seed)] = {"net_excess": stage.statistics.mean_excess, "ci_low": stage.statistics.ci_low,
                                "trades": stage.statistics.trades, "mean_ic": ranking(scores)[1]["ic"]["mean"]}
    sel_k10 = k_sensitivity[SEL001][str(k10)]
    permutation = permutation_summary(sel_k10["mean_net_excess"], ranking_by_strategy[SEL001]["ic"]["mean"], runs)

    attributes = rows[["session_index", "stock_id"]].assign(
        liquidity=within_session_terciles(rows.session_index, liquidity.reindex(rows.index)),
        volatility=within_session_terciles(rows.session_index, rows.atr_percent.astype(float)))
    dates = dataset.session_dates
    t10 = trades[SEL001].merge(attributes, on=["session_index", "stock_id"], how="left", validate="one_to_one")
    t10["month"] = [f"{dates[i].year:04d}-{dates[i].month:02d}" for i in t10.session_index]
    t10["year"] = [dates[i].year for i in t10.session_index]
    t10["sector"] = [sectors.get(int(s)) or UNKNOWN_SECTOR for s in t10.stock_id]
    concentration = concentration_report(t10, horizon=horizon, cfg=cfg)

    labels = session_regimes(dataset, horizon, tested)
    resolved = scored(frame, sel001_scores).join(attributes[["liquidity"]])
    liquidity_ic = {b: session_metrics(resolved[resolved.liquidity == b]).ic for b in REGIME_BUCKETS["liquidity"]}
    regimes = regime_report(t10, labels, metrics[SEL001].ic, liquidity_ic, horizon=horizon, cfg=cfg)

    sel_rank = ranking_by_strategy[SEL001]
    evidence_inputs = {
        "ic_ci_low": sel_rank["ic"]["ci_low"], "ic_share_positive": sel_rank["ic"]["share_positive"],
        "bucket_monotonicity": buckets["monotonicity"], "spread_ci_low": sel_rank["spread"]["ci_low"],
        "net_excess_ci_low_by_k": {k: v["ci_low"] for k, v in k_sensitivity[SEL001].items()},
        "net_excess_k10": sel_k10["mean_net_excess"], "net_excess_k10_ci_low": sel_k10["ci_low"],
        "paired_ci_low": {name: paired[name]["ci_low"] for name in BASELINE_NAMES},
        "permutation_net_excess": [runs[s]["net_excess"] for s in sorted(runs)],
        "mean_ic": sel_rank["ic"]["mean"], "permutation_ic": [runs[s]["mean_ic"] for s in sorted(runs)],
        "selection_null_p": sel_null["p"],
        "ex_top_sessions_ci_low": concentration["exclude_top_sessions"]["ci_low"],
        "ex_top_sessions_mean": concentration["exclude_top_sessions"]["mean_net_excess"],
        "ex_top_stocks_ci_low": concentration["exclude_top_stocks"]["ci_low"],
        "ex_top_stocks_mean": concentration["exclude_top_stocks"]["mean_net_excess"],
        "max_sector_share": concentration["max_sector_share"], "max_year_share": concentration["max_year_share"],
        "regime_ci_high": {f"{d}:{b}": regimes[d][b]["ci_high"] for d, bs in REGIME_BUCKETS.items() for b in bs},
        # F5 needs sessions with >= 1 accepted K=10 trade per bucket; regime_report already counts them.
        "regime_sessions": {f"{d}:{b}": regimes[d][b]["sessions"] for d, bs in REGIME_BUCKETS.items() for b in bs},
    }
    return {
        "horizon": horizon, "tested_sessions": len(set(tested)), "outcome_rows": int(len(frame)),
        "resolved_rows": int(frame.x.notna().sum()), "k_sensitivity": k_sensitivity,
        "random_distribution": random_distribution, "baselines_paired_k10": paired, "ranking": ranking_by_strategy,
        "buckets": buckets, "ablation": ablation, "features": features, "selection_null": sel_null,
        "label_permutation": permutation, "concentration": concentration, "regimes": regimes,
        "evidence_inputs": evidence_inputs,
    }
```

- [ ] **Step 4: Run them; expect PASS** (`5 passed`)

```bash
python -m pytest tests/test_sel002_analysis.py -q -p no:cacheprovider
git diff --exit-code 8944bf4 -- app/selection_gate scripts/run_selection_gate.py
```

- [ ] **Step 5: Commit**

```bash
git add app/selection_diagnostics/analysis.py tests/_sel002_world.py tests/test_sel002_analysis.py
git commit -m "SEL-002: every predeclared analysis for one horizon"
```

---

### Task 15: The single report

**Files:**
- Create: `app/selection_diagnostics/report.py`
- Test: `tests/test_sel002_report.py`

**Interfaces:**
- Consumes: the result dicts from Tasks 12, 14 and 16.
- Produces: `REPORT_SECTIONS` (the addendum's 13 sections, in order), `LIMITATIONS`, `TG001_TEXT` and `render_report(results) -> str`.
- The `results` keys are `classification`, `horizons` ({"3", "5"}), `prepare`, `reproducibility` and `runtime`.

**Data dependencies:** the synthetic world (both horizons) plus fixed prepare and reproducibility blocks.

**Compute:** about 25 s.

**Acceptance criteria:**
- `3 passed`.
- The headings are exactly the addendum list, in order.
- The executive conclusion opens with the verbatim sentence from spec §11: "SEL-002 produced Grade <A|B|C> under
  the criteria frozen before execution (`SEL-002-EVIDENCE-002`, evidence.py SHA-256 <hash>)."
- All 2 × 37 checks lead the report, grouped core then robustness, followed by the major flags (F1-F5) and the
  minor flags per horizon.
- One sentence states the criteria were frozen before execution and are descriptive, never used to tune SEL-001.
- The reproducibility block names the code version, dataset hash, configuration, seeds, windows, universe, cost,
  the evidence rules version and the SPG-001 freeze.

- [ ] **Step 1: Write the failing tests**

`tests/test_sel002_report.py`:

```python
import re

import pytest

from app.selection_diagnostics.evidence import classify
from app.selection_diagnostics.files import jsonable
from app.selection_diagnostics.report import REPORT_SECTIONS, render_report
from app.settings import settings
from tests._sel002_factories import small_config, synthetic_dataset
from tests._sel002_world import analyze_world

ADDENDUM_SECTIONS = (
    "Executive conclusion", "Dataset and validation period", "SEL-001 K sensitivity", "Prediction-decile analysis",
    "Simple-baseline comparison", "Feature ablation", "Null and permutation results", "Concentration", "Regimes",
    "Ranking metrics", "Limitations and possible confounders", "Whether the evidence supports proceeding to TG-001",
    "Reproducibility",
)


@pytest.fixture(scope="module")
def results():
    with pytest.MonkeyPatch.context() as mp:
        mp.setattr(settings, "selection_min_fit_rows", 500)
        mp.setattr(settings, "selection_bootstrap_draws", 200)
        world = synthetic_dataset()
        horizons = {"3": jsonable(analyze_world(world, small_config(), 3)),
                    "5": jsonable(analyze_world(world, small_config(), 5))}
    cfg = small_config()
    window = {"first_test_month": "2025-07", "last_test_month": "2025-12", "test_months": 6, "quarters": 2,
              "tested_sessions": 130, "first_tested_session": "2025-07-01", "last_tested_session": "2025-12-25",
              "protected_start": 261, "excluded_by_reason": {}}
    prepare = {"horizons": [3, 5], "first_session": "2025-01-01", "last_exam_eve_session": "2025-12-31",
               "holdout_month": "2026-01", "masking": {"first_exam_session": "2026-01-01", "rows_dropped": 1560,
                                                       "entry_masked": 40, "h3_rows_masked": 120, "h5_rows_masked": 200},
               "universe_rows": 10440, "stocks_by_year": {"2025": 40}, "windows": {"3": window, "5": window}}
    reproducibility = {"source": "sqlite:///snapshot.db", "snapshot_sha256_before": "f" * 64,
                       "snapshot_sha256_after": "f" * 64, "code_version": "abc1234", "source_digest": "src-sha256:x",
                       "spg_code_version": "8944bf4", "spg_unchanged": True, "dataset_sha256": {"3": "a", "5": "b"},
                       "cache_sha256": {}, "diagnostic_sha256": {"3": "c", "5": "d"}, "config_sha256": cfg.sha256(),
                       "config": jsonable(cfg.snapshot()), "rules_version": "SEL-002-EVIDENCE-002",
                       "evidence_source_sha256": "e" * 64, "universe": "SEU-001", "libraries": {"xgboost": "x"},
                       "workers": 7}
    return {"classification": classify({h: r["evidence_inputs"] for h, r in horizons.items()}), "horizons": horizons,
            "prepare": prepare, "reproducibility": reproducibility,
            "runtime": {"prepare": {"elapsed_seconds": 1.0, "cpu_seconds": 1.0, "peak_memory_mb": 10.0}}}


def test_sections_are_exactly_the_addendum_list_in_order(results):
    assert REPORT_SECTIONS == ADDENDUM_SECTIONS
    headings = re.findall(r"^## \d+\. (.+)$", render_report(results), flags=re.MULTILINE)
    assert tuple(headings) == ADDENDUM_SECTIONS


def test_the_classification_and_every_check_lead_the_report(results):
    text = render_report(results)
    executive = text.split("## 2.")[0]
    c, r = results["classification"], results["reproducibility"]
    assert (f"SEL-002 produced Grade {c['class']} under the criteria frozen before execution "
            f"(`{c['rules_version']}`, evidence.py SHA-256 {r['evidence_source_sha256']}).") in executive
    assert executive.count("HOLDS") + executive.count("FAILS") == 2 * 37
    assert "not a publication decision" in executive
    assert "must not be used to tune SEL-001" in executive
    assert "core" in executive.lower() and "robustness" in executive.lower()


def test_reproducibility_names_every_required_item(results):
    block = render_report(results).split("## 13. Reproducibility")[1]
    for item in ("Code version", "Dataset SHA-256", "Configuration", "Seeds", "Validation windows", "Universe",
                 "Cost assumption", "SPG-001 frozen at 8944bf4"):
        assert item in block, item
```

- [ ] **Step 2: Run them; expect FAIL** (`ModuleNotFoundError: No module named 'app.selection_diagnostics.report'`)

```bash
python -m pytest tests/test_sel002_report.py -q -p no:cacheprovider
```

- [ ] **Step 3: Implement**

`app/selection_diagnostics/report.py`:

```python
"""SEL-002 single report: the addendum's sections, in its order, with numbers and CIs."""
from __future__ import annotations

REPORT_SECTIONS = (
    "Executive conclusion", "Dataset and validation period", "SEL-001 K sensitivity", "Prediction-decile analysis",
    "Simple-baseline comparison", "Feature ablation", "Null and permutation results", "Concentration", "Regimes",
    "Ranking metrics", "Limitations and possible confounders", "Whether the evidence supports proceeding to TG-001",
    "Reproducibility",
)
LIMITATIONS = (
    "Sector is the current `Stock.sector` (also inside `rel_strength_sector_20d`); historical membership is not "
    "reconstructed.",
    "Survivorship: equity and rights status use the current `Stock` row, and stocks delisted before their bars were "
    "backfilled may be missing.",
    "BASELINE-001 is scored on split-adjusted bars; the live scan reads raw bars (SPG-001 §9.2).",
    "Regime cut points (volatility, dispersion terciles) use the whole walk-forward period and market direction uses "
    "the realised b(D,h): regimes describe, they are not tradeable signals.",
    "The label-permutation null keeps each session's target distribution and the fold structure; it does not "
    "preserve any stock-level time-series dependence.",
    "Many exploratory numbers are reported; only the predeclared rules E1-E6 classify the evidence.",
    "Cost is a flat 0.30% round trip with no slippage or market impact; the liquidity floor is Rs 1 crore.",
    "K=1 is reported only; at K=50 overlap suppression removes many candidates.",
    "Walk-forward months only (2018-01..2026-07). The held-out months are not part of SEL-002.",
)
TG001_TEXT = {
    "A": "Yes. The predeclared evidence recommends proceeding to TG-001. TG-001 is not resumed automatically on an "
         "A: the user decides from this report.",
    "B": "Not yet. Investigate and revise SEL-001 before trade geometry; the failing core checks and any major red "
         "flag show what the evidence depends on.",
    "C": "No. Stop investing in the current SEL-001 architecture and redesign the selection model, features or "
         "objective before any trade geometry.",
}


def _f(value, places: int = 5) -> str:
    return "n/a" if value is None else f"{value:.{places}f}"


def _ci(low, high, places: int = 5) -> str:
    return f"[{_f(low, places)}, {_f(high, places)}]"


def _summary_line(label: str, s: dict) -> str:
    c = s["session_contribution"]
    return (f"- {label}: trades {s['trades']} (unresolved {s['unresolved_trades']}, overlap suppressed "
            f"{s['overlap_suppressed']}), sessions {s['sessions_with_trade']}; gross {_f(s['mean_gross'])}, net "
            f"{_f(s['mean_net'])}, benchmark {_f(s['mean_benchmark'])}, net excess {_f(s['mean_net_excess'])} "
            f"{_ci(s['ci_low'], s['ci_high'])}; hit rate {_f(s['hit_rate'], 3)}, negative net {_f(s['negative_net_share'], 3)}; "
            f"per-session sum median {_f(c['median'])} p10 {_f(c['p10'])} p90 {_f(c['p90'])}, top 1% share "
            f"{_f(c['top_1pct_share'], 3)}, top 5% share {_f(c['top_5pct_share'], 3)}")


def _ranking_line(label: str, r: dict) -> str:
    ic, pe, sp = r["ic"], r["pearson"], r["spread"]
    return (f"- {label}: IC mean {_f(ic['mean'], 4)} {_ci(ic['ci_low'], ic['ci_high'], 4)}, median {_f(ic['median'], 4)}, "
            f"share > 0 {_f(ic['share_positive'], 3)}; Pearson {_f(pe['mean'], 4)} {_ci(pe['ci_low'], pe['ci_high'], 4)}; "
            f"top-bottom decile spread {_f(sp['mean'])} {_ci(sp['ci_low'], sp['ci_high'])}, share > 0 "
            f"{_f(sp['share_positive'], 3)} ({ic['sessions']} sessions)")


def _flag_value(value) -> str:
    return ", ".join(str(v) for v in value) if isinstance(value, list) else _f(value)


def _executive(results: dict) -> list[str]:
    c, r = results["classification"], results["reproducibility"]
    lines = [
        f"SEL-002 produced Grade {c['class']} under the criteria frozen before execution (`{c['rules_version']}`, "
        f"evidence.py SHA-256 {r['evidence_source_sha256']}).", "",
        f"**{c['label']}.** {c['recommendation']}", "",
        "Diagnostic classification only. It is not a publication decision; SPG-001 decisions, thresholds and "
        "publication are unchanged.",
        "The checks and flags below (core, robustness, and the major and minor flags) were frozen before any "
        "SEL-002 number was computed and are never changed afterwards; they are descriptive and must not be used "
        "to tune SEL-001.",
    ]
    for h, pos in c["positive_evidence"].items():
        lines.append(f"- h={h}: all core checks hold = {c['core_hold'][h]}; positive evidence: mean IC CI_low > 0 "
                     f"= {pos['ic_ci_low_gt_0']}, K=10 net excess CI_low > 0 = {pos['k10_net_excess_ci_low_gt_0']}; "
                     "rules: " + ", ".join(f"{rl} {'holds' if ok else 'fails'}" for rl, ok in c["rules_hold"][h].items()))
    if c["dependencies"]:
        lines.append("- The evidence fails on: " + "; ".join(c["dependencies"]))
    for h, checks in c["checks"].items():
        core = [k for k in checks if k["group"] == "core"]
        robustness = [k for k in checks if k["group"] == "robustness"]
        lines += ["", f"Evidence checks, h={h}, core (E1-E4, gate class A):"]
        lines += [f"- {k['rule']} {k['name']}: {_f(k['value'])} (needs {k['threshold']}) "
                  f"{'HOLDS' if k['holds'] else 'FAILS'}" for k in core]
        lines += ["", f"Evidence checks, h={h}, robustness (E5-E6, reported; only a major flag below gates A):"]
        lines += [f"- {k['rule']} {k['name']}: {_f(k['value'])} (needs {k['threshold']}) "
                  f"{'HOLDS' if k['holds'] else 'FAILS'}" for k in robustness]
        major = c["major_flags"][h]
        lines += ["", f"Major red flags, h={h}: " + ("none" if not major else "")]
        lines += [f"- {f['id']} {f['name']}: {_flag_value(f['value'])}" for f in major]
        minor = c["minor_flags"][h]
        lines += ["", f"Minor flags, h={h}: " + ("none" if not minor else "")]
        lines += [f"- {f['name']}: {_flag_value(f['value'])}" for f in minor]
    return lines


def _dataset(results: dict) -> list[str]:
    p, r = results["prepare"], results["reproducibility"]
    lines = [f"- Source: {r['source']} (read-only), snapshot SHA-256 {r['snapshot_sha256_before']}; "
             f"sessions {p['first_session']} .. {p['last_exam_eve_session']} used, held-out from "
             f"{p['masking']['first_exam_session']} (month {p['holdout_month']}) and later never read.",
             f"- Masking: rows dropped {p['masking']['rows_dropped']}, entry masked {p['masking']['entry_masked']}, "
             + ", ".join(f"h={h} labels masked {p['masking'][f'h{h}_rows_masked']}" for h in p["horizons"]),
             f"- Universe rows before the exam month: {p['universe_rows']}; stocks per year: "
             + ", ".join(f"{y}={n}" for y, n in p["stocks_by_year"].items())]
    for h in p["horizons"]:
        w, res = p["windows"][str(h)], results["horizons"][str(h)]
        lines.append(f"- h={h}: test months {w['first_test_month']}..{w['last_test_month']} ({w['test_months']}), "
                     f"tested sessions {res['tested_sessions']} ({w['first_tested_session']}..{w['last_tested_session']}), "
                     f"quarterly refits {w['quarters']}, protected span from index {w['protected_start']}; U(D) rows "
                     f"{res['outcome_rows']}, resolved {res['resolved_rows']}; excluded {w['excluded_by_reason']}")
    return lines


def _per_horizon(results: dict, render) -> list[str]:
    lines = []
    for h, res in results["horizons"].items():
        lines += [f"### h = {h}", *render(res), ""]
    return lines


def _k_sensitivity(res: dict) -> list[str]:
    return [_summary_line(f"K={k}", s) for k, s in res["k_sensitivity"]["SEL-001"].items()]


def _deciles(res: dict) -> list[str]:
    lines = [f"- {b['bucket']}: mean x {_f(b['mean_x'])} {_ci(b['ci_low'], b['ci_high'])}, median {_f(b['median_x'])}, "
             f"net excess {_f(b['mean_net_excess'])}, rows {b['observations']}, sessions {b['sessions']}"
             for b in res["buckets"]["buckets"]]
    return lines + [f"- Monotonicity (Spearman, bottom to top): {_f(res['buckets']['monotonicity'], 3)}"]


def _baselines(res: dict) -> list[str]:
    lines = []
    for name, by_k in res["k_sensitivity"].items():
        lines.append(f"- {name}: " + "; ".join(f"K={k} {_f(s['mean_net_excess'])} {_ci(s['ci_low'], s['ci_high'])} "
                                               f"(n={s['trades']})" for k, s in by_k.items()))
    lines.append("- K=10 paired difference, SEL-001 minus baseline:")
    lines += [f"  - {n}: {_f(p['mean_diff'])} {_ci(p['ci_low'], p['ci_high'])} over {p['sessions']} sessions, SEL-001 "
              f"better in {_f(p['share_sessions_a_better'], 3)} of shared sessions" for n, p in res["baselines_paired_k10"].items()]
    lines.append("- Random seeds 1..20 mean net excess: " + "; ".join(
        f"K={k} mean {_f(d.get('mean'))} sd {_f(d.get('sd'))} [{_f(d.get('min'))}, {_f(d.get('max'))}], SEL-001 above "
        f"{_f(d.get('sel001_above_share'), 2)}" for k, d in res["random_distribution"].items()))
    return lines


def _ablation(res: dict) -> list[str]:
    lines = []
    for name, a in res["ablation"].items():
        k10, ic = a["k10"], a["ranking"]["ic"]
        line = (f"- {name} ({len(a['features'])} features): K=10 {_f(k10['mean_net_excess'])} "
                f"{_ci(k10['ci_low'], k10['ci_high'])}, IC {_f(ic['mean'], 4)} {_ci(ic['ci_low'], ic['ci_high'], 4)}")
        if "incremental_net_excess" in a:
            n, i = a["incremental_net_excess"], a["incremental_ic"]
            line += (f"; group contribution FULL minus this: net excess {_f(n['mean_diff'])} {_ci(n['ci_low'], n['ci_high'])}, "
                     f"IC {_f(i['mean_diff'], 4)} {_ci(i['ci_low'], i['ci_high'], 4)}")
        lines.append(line)
    lines.append("- Individual features (sign fixed per fold from training IC):")
    lines += [f"  - {f}: K=10 {_f(v['k10']['mean_net_excess'])} {_ci(v['k10']['ci_low'], v['k10']['ci_high'])}, IC "
              f"{_f(v['ranking']['ic']['mean'], 4)} {_ci(v['ranking']['ic']['ci_low'], v['ranking']['ic']['ci_high'], 4)}, "
              f"negative sign in {v['negative_sign_folds']}/{v['folds']} folds" for f, v in res["features"].items()]
    return lines


def _nulls(res: dict) -> list[str]:
    s, p = res["selection_null"], res["label_permutation"]
    lines = [f"- Selection null ({s['draws']} random top-{s['k']} draws per session, seed {s['seed']}, no reduction): "
             f"observed {_f(s['observed'])}, null mean {_f(s['null_mean'])} sd {_f(s['null_sd'])} max {_f(s['null_max'])}, "
             f"z {_f(s['z'], 2)}, p {_f(s['p'], 4)}"]
    for key in ("net_excess", "ic"):
        v = p[key]
        lines.append(f"- Label permutation ({key}, seeds {p['seeds']}): observed {_f(v['observed'])}, null mean "
                     f"{_f(v['null_mean'])} sd {_f(v['null_sd'])} max {_f(v['null_max'])}, z {_f(v['z'], 2)}, "
                     f"exceeds all {v['exceeds_all']}")
    lines += [f"  - seed {seed}: K=10 net excess {_f(run['net_excess'])} (n={run['trades']}), mean IC {_f(run['mean_ic'], 4)}"
              for seed, run in p["runs"].items()]
    return lines


def _concentration(res: dict) -> list[str]:
    c = res["concentration"]
    lines = [f"- Total K=10 net excess {_f(c['total_net_excess'])} over {c['trades']} trades; largest sector share "
             f"{_f(c['max_sector_share'], 3)} (unknown sector {_f(c['unknown_sector_share'], 3)}), largest year share "
             f"{_f(c['max_year_share'], 3)}"]
    for key in ("exclude_top_sessions", "exclude_top_stocks"):
        e = c[key]
        lines.append(f"- {key.replace('_', ' ')} ({len(e['removed'])} removed): {_f(e['mean_net_excess'])} "
                     f"{_ci(e['ci_low'], e['ci_high'])} over {e['trades']} trades")
    for dim, d in c["dimensions"].items():
        lines.append(f"- by {dim}: {d['groups']} groups, Herfindahl {_f(d['herfindahl'], 4)}, top 1% share "
                     f"{_f(d['top_1pct_share'], 3)}, top 5% share {_f(d['top_5pct_share'], 3)}; top 10: "
                     + ", ".join(f"{t['group']} {_f(t['contribution'], 4)} ({_f(t['share'], 3)})" for t in d["top_10"]))
    return lines


def _regimes(res: dict) -> list[str]:
    lines = []
    for dim, buckets in res["regimes"].items():
        lines.append(f"- {dim}: " + "; ".join(
            f"{b}{'' if v['predeclared'] else ' (not predeclared)'} n={v['trades']} {_f(v['mean_net_excess'])} "
            f"{_ci(v['ci_low'], v['ci_high'])} IC {_f(v['mean_ic'], 4)} {_ci(v['ic_ci_low'], v['ic_ci_high'], 4)}"
            for b, v in buckets.items()))
    return lines


def _ranking(res: dict) -> list[str]:
    return [_ranking_line(name, r) for name, r in res["ranking"].items()]


def _reproducibility(results: dict) -> list[str]:
    r = results["reproducibility"]
    lines = [f"- Code version: {r['code_version']} (source digest {r['source_digest']}); SPG-001 frozen at "
             f"{r['spg_code_version']}, unchanged: {r['spg_unchanged']}",
             f"- Dataset SHA-256 (SEL-DS-001, SPG pre-merge): {r['dataset_sha256']}; cache files {r['cache_sha256']}; "
             f"diagnostic frame {r['diagnostic_sha256']}",
             f"- Snapshot SHA-256 before {r['snapshot_sha256_before']}, after {r['snapshot_sha256_after']}",
             f"- Configuration {r['config_sha256']} ({r['config']['config_version']}); evidence rules "
             f"{r['rules_version']}, source SHA-256 {r['evidence_source_sha256']}",
             f"- Seeds: bootstrap {r['config']['bootstrap_seed']} (B={r['config']['bootstrap_draws']}, L=h), random "
             f"baseline {r['config']['random_seed']} and {r['config']['random_distribution_seeds']}, permutations "
             f"{r['config']['permutation_seeds']}, selection null {r['config']['selection_null_seed']} "
             f"({r['config']['selection_null_draws']} draws), SEL-001 random_state {r['config']['sel001_params']['random_state']}",
             f"- Validation windows: today {r['config']['today']}, first test month {r['config']['first_test_month']}, "
             f"held-out month {r['config']['expected_holdout_month']} from {r['config']['exam_start']}, embargo "
             f"{r['config']['embargo_sessions']} sessions, quarterly refits",
             f"- Universe: {r['universe']}",
             f"- Cost assumption: {r['config']['cost']} round trip; horizons {r['config']['horizons']}; K "
             f"{r['config']['k_values']}",
             f"- Libraries: {r['libraries']}; refit workers {r['workers']}"]
    lines += [f"- Stage {name}: {m.get('elapsed_seconds')} s elapsed, {m.get('cpu_seconds')} s CPU, peak "
              f"{m.get('peak_memory_mb')} MB" for name, m in results["runtime"].items()]
    return lines


def render_report(results: dict) -> str:
    c = results["classification"]
    bodies = (
        _executive(results), _dataset(results), _per_horizon(results, _k_sensitivity),
        _per_horizon(results, _deciles), _per_horizon(results, _baselines), _per_horizon(results, _ablation),
        _per_horizon(results, _nulls), _per_horizon(results, _concentration), _per_horizon(results, _regimes),
        _per_horizon(results, _ranking), [f"- {item}" for item in LIMITATIONS], [TG001_TEXT[c["class"]]],
        _reproducibility(results),
    )
    lines = ["# SEL-002 selection diagnostic", ""]
    for number, (title, body) in enumerate(zip(REPORT_SECTIONS, bodies), start=1):
        lines += [f"## {number}. {title}", "", *body, ""]
    return "\n".join(lines).rstrip() + "\n"
```

- [ ] **Step 4: Run them; expect PASS** (`3 passed`)

```bash
python -m pytest tests/test_sel002_report.py -q -p no:cacheprovider
git diff --exit-code 8944bf4 -- app/selection_gate scripts/run_selection_gate.py
```

- [ ] **Step 5: Commit**

```bash
git add app/selection_diagnostics/report.py tests/test_sel002_report.py
git commit -m "SEL-002: the single report, core/robustness grouping and major/minor flags (EVIDENCE-002)"
```

---

### Task 16: Pipeline stages and the CLI

**Files:**
- Create: `app/selection_diagnostics/pipeline.py`, `scripts/run_sel002_diagnostic.py`
- Test: `tests/test_sel002_script.py`

**Interfaces:**
- Consumes: everything above; SPG-001 `build_selection_dataset`, `_sha256`, `runner._walk_forward`, `runner._excess_target`, `source_digest`, `measured()` and `read_only_sessionmaker`; `Stock.sector`.
- Produces:
  - `pipeline.prepare_stage(cfg, out_dir, *, source_url, cache_dir=None, expected_cache=None, expected_dataset=None, source_sha256=None) -> dict`
  - `refit_stage(cfg, out_dir, *, workers)`
  - `parity_stage(cfg, out_dir)`
  - `analyze_stage(cfg, out_dir, *, horizon)`
  - `report_stage(cfg, out_dir, *, source, code_version, spg_unchanged, source_sha256_after) -> (text, results)`
  - `StageRefused`
  - `scripts.run_sel002_diagnostic.main(argv=None, cfg=None)` and `code_state() -> (version, spg_unchanged)`
- With `cfg=None` (every real CLI use) the frozen config, `check_settings` and every pin are enforced. Exit code 2 on any refusal.

**Data dependencies:** a temporary SQLite source into which SPG-001's own `run_selection_gate` has written decisions, trades, benchmarks, registry and usage rows (8 stocks × 130 sessions).

**Compute:** about 60 s (rebuild, 8 small refits and analysis at both horizons).

**Acceptance criteria:**
- `4 passed`.
- The full pipeline leaves every SPG-001 and holdout table digest, and the source file's SHA-256, identical.
- Analysis is refused without a passing parity gate.
- The frozen CLI refuses drifted settings and an unpinned source.
- A dirty tree or a changed SPG-001 is labelled (Review Focus 5).

- [ ] **Step 1: Write the failing tests**

`tests/test_sel002_script.py`:

```python
import subprocess
from datetime import date, datetime, timezone

import pytest
from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker

from app.db import Base
from app.selection_diagnostics.config import DiagnosticConfig
from app.selection_diagnostics.files import file_sha256, read_json, write_json
from app.selection_diagnostics.guards import table_digest
from app.selection_gate import runner
from app.settings import settings
from scripts import run_sel002_diagnostic
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
CFG = DiagnosticConfig(today=date(2026, 7, 11), exam_start=date(2026, 5, 1), expected_holdout_month=(2026, 5),
                       first_test_month="2026-03", k_values=(1, 2), k_primary=2, bootstrap_draws=200,
                       random_distribution_seeds=(1,), permutation_seeds=(1,), selection_null_draws=20,
                       variants=("PRICE-ONLY", "LOGO-RET"))


@pytest.fixture
def spg_world(tmp_path, monkeypatch):
    """A source database that SPG-001 has already written decisions, trades, benchmarks and holdout rows into."""
    for name, value in SMALL.items():
        monkeypatch.setattr(settings, name, value)
    path = tmp_path / "source.db"
    engine = create_engine(f"sqlite:///{path.as_posix()}")
    Base.metadata.create_all(engine)
    with sessionmaker(bind=engine)() as db:
        for k in range(8):
            seed_stock_with_bars(db, symbol=f"S{k}", sessions=SESSIONS, drift=0.0005 * (k - 3), volume=100_000 + 5_000 * k)
        db.commit()
        runner.run_selection_gate(db, now=NOW, code_version="spg-test")
        db.commit()
    engine.dispose()
    return f"sqlite:///{path.as_posix()}", path


def _digest(url):
    engine = create_engine(url)
    try:
        return table_digest(engine)
    finally:
        engine.dispose()


def test_the_whole_pipeline_leaves_spg_tables_and_the_source_bytes_identical(spg_world, tmp_path):
    url, path = spg_world
    before, before_sha = _digest(url), file_sha256(path)
    assert before["selection_gate_decisions"]["rows"] == 2 and before["selection_holdout_usages"]["rows"] == 2
    out = tmp_path / "out"
    run_sel002_diagnostic.main(["all", "--out", str(out), "--source-url", url, "--rebuild", "--workers", "1",
                                "--code-version", "test"], cfg=CFG)
    assert _digest(url) == before
    assert file_sha256(path) == before_sha
    results = read_json(out / "results.json")
    assert results["classification"]["class"] in {"A", "B", "C"}
    assert results["parity"]["pass"] and results["parity"]["horizons"]["3"]["mode"] == "SCORES"
    assert results["reproducibility"]["snapshot_sha256_before"] == results["reproducibility"]["snapshot_sha256_after"]
    assert "## 13. Reproducibility" in (out / "report.md").read_text(encoding="utf-8")
    assert results["refits"]["jobs"] == 2 * (1 + 2 + 1)


def test_analysis_is_refused_without_a_passing_parity_gate(spg_world, tmp_path):
    url, _ = spg_world
    out = tmp_path / "out"
    run_sel002_diagnostic.main(["prepare", "--out", str(out), "--source-url", url, "--rebuild"], cfg=CFG)
    write_json(out / "parity.json", {"pass": False})
    with pytest.raises(SystemExit) as exit_info:
        run_sel002_diagnostic.main(["analyze", "--h", "3", "--out", str(out)], cfg=CFG)
    assert exit_info.value.code == 2


def test_the_frozen_run_refuses_drifted_settings_and_an_unpinned_source(tmp_path, monkeypatch):
    source = tmp_path / "other.db"
    create_engine(f"sqlite:///{source.as_posix()}").connect().close()
    args = ["prepare", "--out", str(tmp_path / "out"), "--source-url", f"sqlite:///{source.as_posix()}",
            "--cache", str(tmp_path)]
    with pytest.raises(SystemExit) as unpinned:
        run_sel002_diagnostic.main(args)
    assert unpinned.value.code == 2
    monkeypatch.setattr(settings, "selection_round_trip_cost", 0.0020)
    with pytest.raises(SystemExit) as drifted:
        run_sel002_diagnostic.main(args)
    assert drifted.value.code == 2


def test_a_dirty_tree_or_a_changed_spg_is_labelled(monkeypatch):
    replies = {("rev-parse", "HEAD"): (0, "abc123\n"), ("status", "--porcelain"): (0, " M app/x.py\n")}

    def fake_git(*args):
        code, out = replies.get(args[:2], (1, ""))
        return subprocess.CompletedProcess(args, code, out, "")

    monkeypatch.setattr(run_sel002_diagnostic, "_git", fake_git)
    assert run_sel002_diagnostic.code_state() == ("abc123-dirty", False)
    replies[("status", "--porcelain")] = (0, "")
    replies[("diff", "--quiet")] = (0, "")
    assert run_sel002_diagnostic.code_state() == ("abc123", True)
```

- [ ] **Step 2: Run them; expect FAIL** (`ImportError: cannot import name 'run_sel002_diagnostic' from 'scripts'`)

```bash
python -m pytest tests/test_sel002_script.py -q -p no:cacheprovider
```

- [ ] **Step 3: Implement**

`app/selection_diagnostics/pipeline.py`:

```python
"""SEL-002 stages: prepare -> refit -> parity -> analyze (per horizon) -> report. Offline; the source is read-only."""
from __future__ import annotations

import pathlib
import platform
from collections import Counter

import numpy as np
import pandas as pd
import xgboost
from sqlalchemy import select

from app.models import Stock
from app.selection_gate import runner
from app.selection_gate.code_version import source_digest
from app.selection_gate.constants import SEL001_MODEL_VERSION
from app.selection_gate.dataset import FEATURE_COLUMNS, _sha256, build_selection_dataset
from app.selection_gate.metrics import measured
from app.selection_gate.validation import month_text, quarter_of
from app.settings import settings
from scripts.run_selection_gate import read_only_sessionmaker

from . import evidence
from .analysis import analyze_horizon
from .config import PERMUTATION, PINNED_SPG_WALK_FORWARD, SPG_CODE_VERSION
from .files import file_sha256, read_json, read_pickle, write_json, write_pickle
from .heldout import HeldOutViolation, assert_restricted, check_tested, diagnostic_windows, restrict
from .liquidity import median_traded_value_20d
from .refits import inputs_path, load_scores, parity_check, refit_jobs, run_jobs
from .report import render_report

PREPARED = "prepared.pkl"
UNIVERSE = ("SEU-001: equity, not a rights entitlement, an official bar at D, >= 60 official bars, FV-001 required "
            "features at D, record contracts pass, median 20-session close x volume >= Rs 1 crore (10,000,000); "
            "label: D+1 official open to D+h official close, corporate-action adjusted; benchmark b(D,h): equal-weighted "
            "mean gross return of RESOLVED U(D) rows, benchmarkable with >= 100 of them")


class StageRefused(RuntimeError):
    pass


def _cached_scores(path, index: pd.Index) -> tuple[pd.Series, list[int]]:
    data = np.load(path)
    scores = pd.Series(data["scores"], index=data["index"])
    if scores.loc[scores.index.difference(index)].notna().any():
        raise HeldOutViolation("SEL-002: the cache holds a score for a held-out row")
    return scores.reindex(index), [int(i) for i in data["tested"]]


def _window_summary(dataset, windows, tested, horizon: int) -> dict:
    sessions = sorted(set(tested))
    excluded: Counter = Counter()
    for reasons in dataset.benchmarks[horizon].set_index("session_index").excluded.reindex(sessions).dropna():
        excluded.update(reasons)
    return {"first_test_month": month_text(windows.test_months[0]), "last_test_month": month_text(windows.test_months[-1]),
            "test_months": len(windows.test_months), "quarters": len({quarter_of(m) for m in windows.test_months}),
            "tested_sessions": len(sessions), "first_tested_session": dataset.session_dates[sessions[0]].isoformat(),
            "last_tested_session": dataset.session_dates[sessions[-1]].isoformat(),
            "protected_start": int(windows.protected[0]), "excluded_by_reason": dict(sorted(excluded.items()))}


def prepare_stage(cfg, out_dir, *, source_url: str, cache_dir=None, expected_cache=None, expected_dataset=None,
                  source_sha256: str | None = None) -> dict:
    out = pathlib.Path(out_dir)
    out.mkdir(parents=True, exist_ok=True)
    maker = read_only_sessionmaker(source_url)
    with measured() as metrics:
        cache_sha = {}
        if cache_dir is not None:
            cache = pathlib.Path(cache_dir)
            for name in ["dataset.pkl", *[f"wf_h{h}.npz" for h in cfg.horizons]]:
                cache_sha[name] = file_sha256(cache / name)
                if expected_cache is not None and cache_sha[name] != expected_cache[name]:
                    raise StageRefused(f"SEL-002: {name} is not the pinned SEL-DIAG-001 cache")
            source = read_pickle(cache / "dataset.pkl")
        else:
            with maker() as read_session:
                source = build_selection_dataset(read_session, horizons=cfg.horizons, skip_failed_stocks=False)
        dataset_sha = {h: source.sha256_by_horizon[h] for h in cfg.horizons}
        if expected_dataset is not None and any(dataset_sha[h] != expected_dataset[h] for h in cfg.horizons):
            raise StageRefused("SEL-002: the dataset hashes differ from the SPG-001 pre-merge evaluation")
        dataset, masking = restrict(source, cfg.exam_start)
        del source
        assert_restricted(dataset, cfg.exam_start)
        first = masking["first_exam_index"]
        windows, scores, tested = {}, {}, {}
        for h in cfg.horizons:
            windows[h] = diagnostic_windows(dataset, h, cfg)
            if cache_dir is not None:
                scores[h], tested[h] = _cached_scores(pathlib.Path(cache_dir) / f"wf_h{h}.npz", dataset.rows.index)
            else:
                target = runner._excess_target(dataset.rows, dataset.benchmarks[h], h)
                s, _, t = runner._walk_forward(SEL001_MODEL_VERSION, dataset, windows[h], h, target)
                scores[h], tested[h] = s, [int(i) for i in t]
            check_tested(tested[h], h, first)
        with maker() as read_session:
            liquidity = median_traded_value_20d(read_session, dataset.rows, dataset.anchors,
                                                before=dataset.anchors[first])
            sectors = {int(i): s for i, s in read_session.execute(select(Stock.id, Stock.sector)).all()}
        if not (liquidity >= settings.selection_min_median_traded_value_20d).all():
            raise StageRefused("SEL-002: a recomputed traded value disagrees with SEU-001 for a U(D) row")
        diagnostic_sha = {h: _sha256(dataset.rows, dataset.session_dates, h) for h in cfg.horizons}
        write_pickle(out / PREPARED, {"dataset": dataset, "windows": windows, "scores": scores, "tested": tested,
                                      "liquidity": liquidity, "sectors": sectors})
        for h in cfg.horizons:
            write_pickle(inputs_path(out, h), {
                "rows": dataset.rows[["session_index", "stock_id", *FEATURE_COLUMNS]],
                "target": runner._excess_target(dataset.rows, dataset.benchmarks[h], h), "windows": windows[h],
                "session_dates": dataset.session_dates, "horizon": h})
    rows, dates = dataset.rows, dataset.session_dates
    year = np.array([d.year for d in dates])[rows.session_index.to_numpy()]
    stocks_by_year = pd.Series(rows.stock_id.to_numpy()).groupby(year).nunique()
    summary = {
        "mode": "CACHE" if cache_dir is not None else "REBUILD", "horizons": list(cfg.horizons),
        "first_session": dates[0].isoformat(), "last_exam_eve_session": dates[first - 1].isoformat(),
        "holdout_month": month_text(cfg.expected_holdout_month), "masking": masking, "universe_rows": int(len(rows)),
        "stocks_by_year": {int(y): int(n) for y, n in stocks_by_year.items()},
        "dataset_sha256": {str(h): v for h, v in dataset_sha.items()}, "cache_sha256": cache_sha,
        "diagnostic_sha256": {str(h): v for h, v in diagnostic_sha.items()}, "snapshot_sha256_before": source_sha256,
        "windows": {str(h): _window_summary(dataset, windows[h], tested[h], h) for h in cfg.horizons},
        "liquidity_rows": int(liquidity.notna().sum()), "sectors_known": sum(1 for s in sectors.values() if s),
        "config_sha256": cfg.sha256(), "metrics": metrics,
    }
    write_json(out / "prepare.json", summary)
    return summary


def refit_stage(cfg, out_dir, *, workers: int) -> dict:
    jobs = refit_jobs(cfg)
    with measured() as metrics:
        metas = run_jobs(jobs, out_dir, workers=workers, config_sha=cfg.sha256())
    summary = {"jobs": len(jobs), "workers": workers, "metrics": metrics,
               "runs": {m["job_id"]: {"metrics": m["metrics"], "tested_sessions": m["tested_sessions"]} for m in metas}}
    write_json(pathlib.Path(out_dir) / "refits.json", summary)
    return summary


def parity_stage(cfg, out_dir) -> dict:
    prepared = read_pickle(pathlib.Path(out_dir) / PREPARED)
    dataset = prepared["dataset"]
    with measured() as metrics:
        horizons = {}
        for h in cfg.horizons:
            loop, loop_tested = load_scores(out_dir, f"h{h}__FULL", dataset.rows.index)
            horizons[str(h)] = parity_check(dataset=dataset, horizon=h, cached=prepared["scores"][h],
                                            cached_tested=prepared["tested"][h], loop=loop, loop_tested=loop_tested,
                                            tolerance=cfg.parity_tolerance, top_k=cfg.k_primary)
    result = {"pass": all(v["pass"] for v in horizons.values()), "horizons": horizons,
              "spg_pre_merge_reference": {str(h): v for h, v in PINNED_SPG_WALK_FORWARD.items()}, "metrics": metrics}
    write_json(pathlib.Path(out_dir) / "parity.json", result)
    return result


def analyze_stage(cfg, out_dir, *, horizon: int) -> dict:
    out = pathlib.Path(out_dir)
    if not (out / "parity.json").exists() or not read_json(out / "parity.json")["pass"]:
        raise StageRefused("SEL-002: the parity gate has not passed; analysis refused")
    prepared = read_pickle(out / PREPARED)
    dataset = prepared["dataset"]
    index = dataset.rows.index
    with measured() as metrics:
        variants = {name: load_scores(out, f"h{horizon}__{name}", index)[0] for name in cfg.variants}
        perms = {seed: load_scores(out, f"h{horizon}__{PERMUTATION}-{seed}", index)[0] for seed in cfg.permutation_seeds}
        result = analyze_horizon(dataset=dataset, horizon=horizon, windows=prepared["windows"][horizon],
                                 tested=prepared["tested"][horizon], sel001_scores=prepared["scores"][horizon],
                                 variant_scores=variants, permutation_scores=perms, liquidity=prepared["liquidity"],
                                 sectors=prepared["sectors"], cfg=cfg)
    result["metrics"] = metrics
    write_json(out / f"results_h{horizon}.json", result)
    return result


def report_stage(cfg, out_dir, *, source: str, code_version: str | None, spg_unchanged: bool | None,
                 source_sha256_after: str | None) -> tuple[str, dict]:
    out = pathlib.Path(out_dir)
    prepare, parity, refits = read_json(out / "prepare.json"), read_json(out / "parity.json"), read_json(out / "refits.json")
    horizons = {str(h): read_json(out / f"results_h{h}.json") for h in cfg.horizons}
    classification = evidence.classify({h: r["evidence_inputs"] for h, r in horizons.items()})
    reproducibility = {
        "source": source, "snapshot_sha256_before": prepare["snapshot_sha256_before"],
        "snapshot_sha256_after": source_sha256_after, "code_version": code_version, "source_digest": source_digest(),
        "spg_code_version": SPG_CODE_VERSION, "spg_unchanged": spg_unchanged, "dataset_sha256": prepare["dataset_sha256"],
        "cache_sha256": prepare["cache_sha256"], "diagnostic_sha256": prepare["diagnostic_sha256"],
        "config_sha256": cfg.sha256(), "config": cfg.snapshot(), "rules_version": evidence.RULES_VERSION,
        "evidence_source_sha256": file_sha256(evidence.__file__), "universe": UNIVERSE,
        "libraries": {"python": platform.python_version(), "numpy": np.__version__, "pandas": pd.__version__,
                      "xgboost": xgboost.__version__}, "workers": refits["workers"],
    }
    runtime = {"prepare": prepare["metrics"], "refit": refits["metrics"], "parity": parity["metrics"],
               **{f"analyze_h{h}": r["metrics"] for h, r in horizons.items()}}
    results = {"experiment": "SEL-002", "classification": classification, "horizons": horizons, "prepare": prepare,
               "parity": parity, "refits": refits, "reproducibility": reproducibility, "runtime": runtime}
    text = render_report(results)
    write_json(out / "results.json", results)
    (out / "report.md").write_text(text, encoding="utf-8")
    return text, results
```

`scripts/run_sel002_diagnostic.py`:

```python
"""SEL-002 selection diagnostic: offline and read-only; never scheduled, never on the API path.

Run from the marksy-api worktree with DATABASE_URL set to a throwaway SQLite file (importing `app` needs one):
  python -m scripts.run_sel002_diagnostic all --out OUT --source-url sqlite:///SNAPSHOT --cache SEL_DIAG_DIR --workers 5
Stages: prepare | refit | parity | analyze --h H | report | all. With the frozen configuration every pin is enforced.
"""
from __future__ import annotations

import argparse
import os
import pathlib
import subprocess
import sys

from app.selection_diagnostics import pipeline
from app.selection_diagnostics.config import (
    PINNED_CACHE_SHA256, PINNED_DATASET_SHA256, PINNED_SNAPSHOT_SHA256, SPG_CODE_VERSION, DiagnosticConfig,
    check_settings,
)
from app.selection_diagnostics.files import file_sha256
from app.selection_diagnostics.guards import no_database_writes
from app.selection_diagnostics.heldout import HeldOutViolation

REPO = pathlib.Path(__file__).resolve().parent.parent
STAGES = ("prepare", "refit", "parity", "analyze", "report", "all")


def _sqlite_file(url: str) -> pathlib.Path | None:
    return pathlib.Path(url[len("sqlite:///"):]) if url.startswith("sqlite:///") else None


def _git(*args: str) -> subprocess.CompletedProcess | None:
    try:
        return subprocess.run(["git", *args], cwd=REPO, capture_output=True, text=True, check=False)
    except OSError:
        return None


def code_state() -> tuple[str | None, bool | None]:
    """HEAD (suffixed -dirty for a modified tree) and whether SPG-001 is byte-identical to its frozen commit."""
    head = _git("rev-parse", "HEAD")
    if head is None or head.returncode != 0:
        return None, None
    status = _git("status", "--porcelain")
    version = head.stdout.strip() + ("-dirty" if status is not None and status.stdout.strip() else "")
    spg = _git("diff", "--quiet", SPG_CODE_VERSION, "--", "app/selection_gate", "scripts/run_selection_gate.py")
    return version, None if spg is None else spg.returncode == 0


def main(argv: list[str] | None = None, cfg: DiagnosticConfig | None = None) -> None:
    parser = argparse.ArgumentParser(description="SEL-002 selection diagnostic (read-only).")
    parser.add_argument("stage", choices=STAGES)
    parser.add_argument("--out", required=True, help="output folder (scratchpad); nothing else is written")
    parser.add_argument("--source-url", default=os.environ.get("SELECTION_SOURCE_DATABASE_URL"),
                        help="read-only market-data source: the production snapshot")
    parser.add_argument("--cache", default=None, help="SEL-DIAG-001 folder with dataset.pkl and wf_h{3,5}.npz")
    parser.add_argument("--rebuild", action="store_true", help="rebuild through build_selection_dataset (reproduction)")
    parser.add_argument("--h", type=int, default=None, help="horizon for the analyze stage")
    parser.add_argument("--workers", type=int, default=5, help="parallel refit processes (2 xgboost threads each)")
    parser.add_argument("--code-version", default=None)
    args = parser.parse_args(argv)
    frozen = cfg is None
    cfg = cfg or DiagnosticConfig()
    try:
        if frozen:
            check_settings(cfg)
        if args.stage in ("prepare", "report", "all") and not args.source_url:
            raise ValueError("--source-url (or SELECTION_SOURCE_DATABASE_URL) is required")
        if args.stage in ("prepare", "all") and bool(args.cache) == bool(args.rebuild):
            raise ValueError("pass exactly one of --cache or --rebuild")
        if args.stage == "analyze" and args.h not in cfg.horizons:
            raise ValueError(f"--h must be one of {cfg.horizons}")
        source_file = _sqlite_file(args.source_url) if args.source_url else None
        version, spg_unchanged = code_state()
        version = args.code_version or version
        with no_database_writes():
            if args.stage in ("prepare", "all"):
                before = file_sha256(source_file) if source_file else None
                if frozen and source_file and before != PINNED_SNAPSHOT_SHA256:
                    raise pipeline.StageRefused("SEL-002: the source is not the pinned production snapshot")
                pipeline.prepare_stage(cfg, args.out, source_url=args.source_url, cache_dir=args.cache,
                                       expected_cache=PINNED_CACHE_SHA256 if frozen and args.cache else None,
                                       expected_dataset=PINNED_DATASET_SHA256 if frozen else None,
                                       source_sha256=before)
            if args.stage in ("refit", "all"):
                pipeline.refit_stage(cfg, args.out, workers=args.workers)
            if args.stage in ("parity", "all"):
                if not pipeline.parity_stage(cfg, args.out)["pass"]:
                    raise pipeline.StageRefused("SEL-002: PARITY GATE FAILED; stopping")
            for h in ([args.h] if args.stage == "analyze" else cfg.horizons if args.stage == "all" else []):
                pipeline.analyze_stage(cfg, args.out, horizon=h)
            if args.stage in ("report", "all"):
                after = file_sha256(source_file) if source_file else None
                text, _ = pipeline.report_stage(cfg, args.out, source=args.source_url, code_version=version,
                                                spg_unchanged=spg_unchanged, source_sha256_after=after)
                print(text)
    except (ValueError, pipeline.StageRefused, HeldOutViolation) as exc:
        print(f"SEL-002 refused: {exc}", file=sys.stderr)
        raise SystemExit(2) from exc


if __name__ == "__main__":
    main()
```

- [ ] **Step 4: Run them; expect PASS** (`4 passed`)

```bash
python -m pytest tests/test_sel002_script.py -q -p no:cacheprovider
git diff --exit-code 8944bf4 -- app/selection_gate scripts/run_selection_gate.py
```

- [ ] **Step 5: Commit**

```bash
git add app/selection_diagnostics/pipeline.py scripts/run_sel002_diagnostic.py tests/test_sel002_script.py
git commit -m "SEL-002: read-only pipeline stages and the run_sel002_diagnostic CLI"
```

---

### Task 18: Whole-branch validation, the run on the read-only snapshot, the report, then stop

> **Task 17 (optional manual-only Kubernetes Job) is dropped.** Spec §10.2 and §14 decision 6 (2026-10-01, user
> review): the record run uses the local frozen-cache CLI; the Job would need a restored, frozen read-only snapshot
> database, the secret `sel002-snapshot-source`, and about 28 h of the VPS's 2 CPUs. There is no
> `deploy/k8s/manual/sel002-diagnostic-job.yaml` and no `tests/test_sel002_manifest.py`.

**Files:**
- Create (scratchpad only, never in a repo):
  - `$SCRATCH/sel002/commit.txt`
  - `$SCRATCH/sel002/{prepare,refit,parity,analyze_h3,analyze_h5,report}.log`
  - the stage outputs: `prepare.json`, `prepared.pkl`, `refit_inputs_h{3,5}.pkl`, `refits/`, `refits.json`, `parity.json`, `results_h{3,5}.json`, `results.json` and `report.md`

**Interfaces:**
- Consumes: `python -m scripts.run_sel002_diagnostic` (Task 16).

**Data dependencies:**
- `$SCRATCH/snapshot.db`: the read-only production snapshot, SHA-256 `5f59b9bc…79a8b`
- `$SCRATCH/sel_diag/dataset.pkl`, `wf_h3.npz` and `wf_h5.npz` (pinned)
- nothing else: no production connection

**Compute** (the record run uses 5 workers; the worker count changes only the wall clock, never a result):
- prepare: about 10–15 min
- refit: 40 runs, 5 workers × 2 xgboost threads on 16 logical cores, about 5.5–7 h, about 44 CPU-hours
- parity: under 1 min
- analyze: about 25–30 min, with h=3 and h=5 running concurrently
- report: under 1 min
- end to end: about 7 h with 5 workers (about 6–6.5 h for reference with 7, if at least 10 GB RAM is free)
- RAM: about 6 GB for the refits at 5 workers, about 6 GB for the two analyze processes; at least 7 GB free

**Acceptance criteria:**
- All SEL-002 tests pass (99 pre-amendment; Task 17's 2 manifest tests are gone and Task 12 gained 2, so still about
  99 — confirmed exactly by the collected count during implementation), and the SPG diff is empty.
- One whole-branch review is done, with fixes re-reviewed.
- The tree is clean at the frozen commit.
- Parity passes at both horizons.
- `report.md` holds the 13 sections, the class, and every check with its value, grouped core then robustness, plus
  the major and minor flags.
- The snapshot SHA-256 after the run equals the value before.
- No push, merge, PR or deploy.

- [ ] **Step 1: The SEL-002 suite and the SPG freeze** (about 2 min)

```bash
python -m pytest tests/test_sel002_*.py -q -p no:cacheprovider -p no:faulthandler
git diff --exit-code 8944bf4 -- app/selection_gate scripts/run_selection_gate.py && echo SPG-UNCHANGED
```

Expected: no failures (the SEL-002 suite), then `SPG-UNCHANGED`. SPG-001's suites are not rerun, because neither
SPG-001 nor its dependencies changed. (There is no manifest suite: Task 17 is dropped.)

- [ ] **Step 2: One whole-branch review** (the controller dispatches it on the most capable model, over `8944bf4..HEAD`). It must cover:
  - **Held-out protection:** nothing at or after 2026-08-01 is read for any computation; only the calendar is kept; the liquidity read is bounded.
  - **Writes:** no database write path, no SPG-001 writer import, and no registry or `tg_holdout_*` access.
  - **Loop fidelity:** `wf_loop.walk_forward` is line-for-line `runner._walk_forward`, and only the feature subset and the target vary.
  - **Selection before labels** in the harness and the selection null.
  - **Session-level bootstrap** everywhere; rows are never units.
  - **Rules as specified:** the evidence rules and the A/B/C mapping match spec §7 exactly, with nothing tuned.
  - **Report:** the sections are exactly the addendum's.
  - **Reach:** nothing on the API path, in a schedule or in the base kustomization.

  One fix wave, then one scoped re-review. Any fix is TDD'd and committed. All SEL-002 tests are green again.

- [ ] **Step 3: Freeze the commit and pre-flight** (from here on, any code change means a rerun from `prepare`)

```bash
git status --porcelain            # expect: empty
git rev-parse HEAD > "$SCRATCH/sel002/commit.txt" && cat "$SCRATCH/sel002/commit.txt"
sha256sum "$SCRATCH/snapshot.db" "$SCRATCH/sel_diag/dataset.pkl" "$SCRATCH/sel_diag/wf_h3.npz" "$SCRATCH/sel_diag/wf_h5.npz"
powershell -NoProfile -Command "[math]::Round((Get-CimInstance Win32_OperatingSystem).FreePhysicalMemory/1MB,1)"
export DATABASE_URL="sqlite:///$SCRATCH/sel002/throwaway-app.db"
```

Expected: the four hashes are exactly the pins in Global Constraints, and free RAM is at least 7 GB (the CLI's
default is already `--workers 5`; close other applications if it is not). A hash mismatch stops here; nothing else
may be substituted.

- [ ] **Step 4: Prepare** (about 10–15 min)

```bash
python -m scripts.run_sel002_diagnostic prepare --out "$SCRATCH/sel002" \
  --source-url "sqlite:///$SCRATCH/snapshot.db" --cache "$SCRATCH/sel_diag" 2>&1 | tee "$SCRATCH/sel002/prepare.log"
python -c "import json;p=json.load(open(r'$SCRATCH/sel002/prepare.json'));print(p['mode'],p['masking'],p['windows']['3']['tested_sessions'],p['windows']['5']['tested_sessions'],p['metrics'])"
```

Expected:
- `CACHE`
- `first_exam_session` is `2026-08-03`
- tested sessions are `2122` and `2120`
- exit code 0

Exit code 2 means a pin or held-out refusal; stop and report it.

- [ ] **Step 5: Refit, 40 runs in the background** (about 5.5–7 h with the CLI's default of 5 workers). Use
  `run_in_background`; do not poll with sleeps.

```bash
python -m scripts.run_sel002_diagnostic refit --out "$SCRATCH/sel002" --workers 5 > "$SCRATCH/sel002/refit.log" 2>&1
```

- [ ] **Step 6: Parity, as soon as both FULL jobs exist** (they are the first two in the queue, about 1 h in). Use a Monitor until-loop on `ls "$SCRATCH/sel002/refits/h3__FULL.json" "$SCRATCH/sel002/refits/h5__FULL.json"`, then:

```bash
python -m scripts.run_sel002_diagnostic parity --out "$SCRATCH/sel002" 2>&1 | tee "$SCRATCH/sel002/parity.log"
python -c "import json;print(json.dumps(json.load(open(r'$SCRATCH/sel002/parity.json'))['horizons'],indent=1))"
```

Expected: `"pass": true` at h=3 and h=5, in mode `SCORES` (max |Δ| ≤ 1e-6) or `TOP_K`. If either fails, stop the refit task (TaskStop), report the parity block and stop. That is the protocol's PARITY GATE: no analysis runs.

- [ ] **Step 7: Wait for the refit stage to finish** (the background task notifies on exit). Then confirm all 40 jobs:

```bash
tail -5 "$SCRATCH/sel002/refit.log"
ls "$SCRATCH/sel002/refits"/*.npz | wc -l    # expect: 40
python -c "import json;r=json.load(open(r'$SCRATCH/sel002/refits.json'));print(r['jobs'],r['workers'],r['metrics'])"
```

- [ ] **Step 8: Analyze both horizons concurrently** (about 25–30 min). Run both commands with `run_in_background`:

```bash
python -m scripts.run_sel002_diagnostic analyze --h 3 --out "$SCRATCH/sel002" > "$SCRATCH/sel002/analyze_h3.log" 2>&1
python -m scripts.run_sel002_diagnostic analyze --h 5 --out "$SCRATCH/sel002" > "$SCRATCH/sel002/analyze_h5.log" 2>&1
```

- [ ] **Step 9: Write the single report**

```bash
python -m scripts.run_sel002_diagnostic report --out "$SCRATCH/sel002" \
  --source-url "sqlite:///$SCRATCH/snapshot.db" > "$SCRATCH/sel002/report.log" 2>&1
grep -c "^## " "$SCRATCH/sel002/report.md"     # expect: 13
python -c "import json;r=json.load(open(r'$SCRATCH/sel002/results.json'));c=r['classification'];x=r['reproducibility'];print(c['class'],c['label'],c['dependencies']);print(x['code_version'],x['spg_unchanged'],x['snapshot_sha256_before']==x['snapshot_sha256_after'])"
```

Expected:
- 13 sections
- `code_version` equals `commit.txt` with no `-dirty`
- `spg_unchanged` is `True`
- the snapshot hash comparison prints `True`

- [ ] **Step 10: Stop. No merge, push, PR or deploy.** Hand the user:
  - the path `$SCRATCH/sel002/report.md`
  - the class (A, B or C) with its recommendation and the failing rules per horizon
  - the parity block
  - the runtime and peak memory per stage, against spec §13

Resuming TG-001 needs the user's explicit decision (spec §14 decision 8), even on a Grade A. The branch stays local
and unmerged.

---

## Spec coverage and clarifications for review

Spec coverage:
- §4 held-out: Tasks 2, 3, 14 and 16
- §5 pins and dataset: Tasks 1 and 16
- §6.1: Task 7
- §6.2 and §6.3: Task 6
- §6.4: Tasks 5 and 7
- §6.5: Tasks 8 and 13
- §6.6: Tasks 5, 9 and 13
- §6.7: Tasks 10 and 11
- §6.8: Task 11
- §7: Task 12, with Tasks 14 and 15 wiring the `SEL-002-EVIDENCE-002` inputs and the report
- §8: Task 4
- §9: every task
- §10: Task 16 (§10.2, the Kubernetes Job, is dropped; Task 18 runs the CLI directly)
- §11: Task 15
- §12: Tasks 1, 13 and 16
- §13: Task 18

Clarifications, all from the spec:
1. **Calendar kept whole.** Only dates are kept for `plan_windows`; every value at or after 2026-08-01 is dropped first.
2. **Selection null over every scored U(D) row.** This follows the addendum's selection-before-labels rule. SEL-DIAG-001 drew from resolved rows (§14 decision 3).
3. **One label permutation per seed, shared by all folds** (§14 decision 2).
4. **UNKNOWN sector** is outside the 40% sector check and major flag F4, and its share is reported (§14 decision 1).
5. **The cached FULL scores are SEL-001**, and the FULL refit exists only for the parity gate.
6. **Rebuilds are explicit.** `--rebuild` is never automatic. The record run uses the pinned cache and `--workers 5`
   (§14 decision 7 and spec §13); there is no Kubernetes Job (§14 decision 6).
7. **Grading.** Core (E1-E4) versus robustness (E5-E6) evidence, with major red flags F1-F5 blocking class A
   (§7, `SEL-002-EVIDENCE-002`, §14 decision 7).
8. **After the run.** TG-001 is not resumed automatically even on a Grade A; the user decides from the report (§14
   decision 8).
