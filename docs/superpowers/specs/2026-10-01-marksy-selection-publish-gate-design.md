# Marksy selection publish gate — design

Status: approved 2026-10-01 with the review decisions in §20 · Repo: marksy-api (owner). marksy-os and admin-app:
no change this phase.

## 1. Goal and separation

Marksy publishes a call only when the model that made it has proven, out of sample, that it picks stocks with
genuine forward excess return over the eligible all-stocks universe. Two questions are kept apart:

- **Selection learning** answers: *does this model identify stocks with genuine excess forward return?*
- **Publication gating** answers: *has that model demonstrated enough evidence to be allowed to publish?*

Target, stop and horizon optimisation (trade geometry) is a later phase. It must not feed, alter or be
evaluated inside selection-gate validation; the gate never reads a target, stop or hit rate.

## 2. Background (production, 30 days to 2026-09-30)

- BASELINE-001 picks (817 distinct, entry at the first open after publication, 3 sessions) returned −0.07%
  against −0.42% for the whole universe on the same sessions (37,326 stock-sessions); touch +5% 22.2% vs 22.5%,
  touch −3% 49.4% vs 49.2%. No selection edge.
- The fixed +5%/−3% geometry makes a stop about twice as likely as a target even with zero edge.
- ADT-003 (marksy-api PR #63) fixed the decay rule that withdrew nearly every call; historical rows untouched.
- `market_prices` holds official daily bars from 2015 (≈1.2k–2.9k stocks a year, ≈4.5M rows).

## 3. Invariants

1. **Gate before publication.** The gate runs inside `app/recommendations.py::record_recommendation` before the
   `Prediction` row is built. A prediction is not a public call by default. Only a valid `PUBLISH` decision for
   the exact model version and horizon, for a model whose publication capability is `PUBLISHABLE` (§13.1), can
   yield `publication_state = PUBLISHED`, and only a `PUBLISHED` prediction may reach `register_prediction` (the
   ledger tip, today line 144).
2. **Fail closed.** A missing, stale, invalid or mismatched decision, or any error while authorising, yields
   `NO_EDGE` and `SHADOW`. Never publication.
3. **Per model and horizon.** Decisions are independent per `(model_version, horizon_sessions)`. A pass at 5
   sessions authorises nothing at 3.
4. **Benchmark integrity.** The benchmark is the equal-weighted mean over the same eligible universe, session and
   horizon, with eligible, labelled, unresolved and excluded counts persisted per session.
5. **Deduplication.** Every count, mean and confidence interval uses the reduced trade set (§8). Repeated scans
   never add evidence.
6. **Held-out isolation.** The held-out month is never used for feature selection, hyperparameter tuning,
   threshold selection, calibration, model selection or gate tuning; the holdout registry enforces it (§10.2).
7. **Configurable, persisted thresholds.** Every threshold is a setting; the full snapshot and its hash are stored
   on each decision (§12.1).
8. **Shadow behaviour.** A blocked prediction is stored as `SHADOW`, stays available for learning, and never
   appears in the ledger, `/predictions/active`, `/dashboard/snapshot`, `/recommendations` or any other public
   surface (§14). SEL-001 scores the full eligible universe daily in shadow (§15).
9. **No artificial publication.** If no model passes, Marksy publishes zero calls. The gate is never weakened to
   keep the UI populated.
10. **Auditability.** `selection_gate_decisions` and its child tables are append-only and hold every input,
    formula version, result, configuration, dataset/model version, validation period and failing reason needed
    to reproduce the decision.
11. **No history rewrite.** Existing predictions, tips, ADT-001/002 assessments and ledger outcomes are untouched.
12. **Gate decision is not publication.** A `PUBLISH` decision means the model earned authorisation under the
    selection criteria. It never grants a model permission to publish on its own: publication also requires the
    model's publication capability to be `PUBLISHABLE`. SEL-001 is `SHADOW_ONLY` until trade-geometry validation
    is implemented and separately approved.

## 4. Scope

In: universe rule, dataset and labels, SEL-001 model, BASELINE-001 evaluation, trade-set reduction, statistics,
walk-forward and held-out validation, decision state machine, schema, publish-path enforcement, public-surface
filtering, `/predictions/active` gate meta, SEL-001 daily shadow scoring, Kubernetes jobs, tests, report.

Out: target/stop/horizon learning; publishing SEL-001 calls (needs trade geometry, §15.3); app UI for
`NO_EDGE`; any rewrite of historical rows; changes to the live scan's own eligibility.

## 5. Definitions

- **Official bar**: a `market_prices` row whose `source` is not `upstox-v3-quote-ohlc` (provisional rows are
  never used anywhere in this design).
- **Session list `S`**: ascending distinct official-bar `timestamp` anchors (midnight IST, stored as 18:30 UTC of
  the previous day) that carry official bars for at least `selection_min_session_stocks` (default 100) distinct
  stocks. `i(D)` is the index of session `D` in `S`; `D+k` means `S[i(D)+k]`. `session_date(D)` is the IST date.
- **Cutoff**: `app/walk_forward_dataset.py::session_cutoff(session_date)`, the anchor D's own bar carries.
- **Horizon** `h ∈ {3, 5}` sessions (setting `selection_gate_horizons`).
- **Model versions under gate**: `BASELINE-001` (existing live model) and `SEL-001` (new).
- **Stage**: `WALK_FORWARD` or `HELD_OUT`.
- **Cost** `c`: round-trip transaction cost as a return, default `0.0030`.

## 6. Universe (rule `SEU-001`)

`U(D)` is the set of stocks `s` meeting all of, evaluated only from data at or before `cutoff(D)`:

1. The same instrument predicates as `app/scan.py::evaluate_stock`: equity instrument (not `EXCLUSION_NOT_EQUITY`)
   and not a rights entitlement (`EXCLUSION_RIGHTS_ENTITLEMENT`).
2. An official bar for `s` at `D`.
3. At least `selection_min_history_bars` (default 60) official bars for `s` at or before `D`.
4. FV-001's `REQUIRED_FEATURE_COLUMNS` (`sma20_distance`, `volume_ratio_20d`, `atr_percent`) non-NaN at `D`.
5. The scan's record contracts pass (`evaluate_record_contracts`, as `evaluate_stock` applies them).
6. Median of `close × volume` over the 20 official bars ending at `D` ≥ `selection_min_median_traded_value_20d`
   (default ₹100,000, i.e. ₹1 lakh, per review decision 3), on corporate-action-adjusted bars.

A stock with an official bar at `D` that fails a rule is **excluded** with the first failing reason:
`NOT_EQUITY`, `RIGHTS_ENTITLEMENT`, `TOO_FEW_BARS`, `INVALID_MARKET_DATA`, `DATA_CONTRACT_VIOLATION`,
`BELOW_LIQUIDITY_FLOOR`. A stock with no official bar at `D` is absent, not excluded.

Known limitation, reported with every run: equity/rights status uses the current `Stock` row, and stocks
delisted before their bars were backfilled may be missing (survivorship). The report prints distinct stocks per
year so the reader can judge it.

## 7. Dataset contract (`SEL-DS-001`)

### 7.1 Rows

One row per `(session D, stock s ∈ U(D), horizon h)`. All prices are corporate-action adjusted with
`app/corporate_action_adjustment.py` (`back_adjust_frame` for features, `adjust_forward_close` for the exit).

### 7.2 Features

- SEL-001 uses FV-002 (`app/features/technical.py::EXTENDED_FEATURE_COLUMNS`): the per-stock columns from
  `add_extended_features` over official bars with `timestamp <= cutoff(D)`, plus the cross-sectional and regime
  columns computed **across `U(D)` only** (reusing `walk_forward_dataset.cross_sectional_features` and
  `_regime_for` with `U(D)` as the population).
- BASELINE-001 uses FV-001 as the live scan does.
- News, fundamentals, market cap and sector are not features (not reconstructable as of `D`).
- Every bar passed to feature code goes through the existing `feature_bar_observer`; a test asserts no observed
  bar has `timestamp > cutoff(D)`.

### 7.3 Label

- Entry: official open of `s` at session `D+1` (`o`). Exit: official close of `s` at session `D+h`, brought onto
  the entry basis with `adjust_forward_close` for ratio actions between `D+1` and `D+h` (`x`).
- Gross return `r = x / o − 1`.
- `label_status`, first match wins: `NOT_YET_RESOLVABLE` (`i(D)+h` beyond the last index of `S`),
  `ENTRY_BAR_MISSING`, `EXIT_BAR_MISSING`, `INVALID_PRICE` (`o ≤ 0` or `x ≤ 0`), `SUSPECT_RETURN`
  (`r < selection_min_plausible_return` default −0.60, or `r > selection_max_plausible_return` default 1.50),
  else `RESOLVED`.
- Label bars are strictly after `cutoff(D)`. No bar is both feature and label.

### 7.4 Benchmark

- `b(D,h)` = arithmetic mean of `r` over rows of `U(D)` with `label_status = RESOLVED`.
- Session `(D,h)` is `benchmarkable` iff its resolved count ≥ `selection_min_benchmark_stocks` (default 100).
- Persisted in `selection_benchmark_sessions` (§12.3) with `eligible_count = |U(D)|`, `labelled_count`,
  `unresolved` counts by status, `excluded` counts by reason, `benchmark_return`, `benchmarkable`.

### 7.5 Storage and hash

The row-level dataset is rebuilt deterministically on every run and not stored (≈9M rows is too heavy for the
VPS Postgres). Its identity is `dataset_sha256`: SHA-256 over UTF-8 lines
`session_date,stock_id,horizon,label_status,o,x,r,<features…>`, sorted by `(session_date, stock_id, horizon)`,
with every number formatted `%.8f` and missing values as the empty string. Benchmarks and every candidate trade
are persisted (§12), so any decision can be audited without rebuilding.

Build is per horizon and cutoff-sharded (existing `--shard-cutoffs` pattern) with `float32` feature arrays.

## 8. Trade selection and reduction

For model `m`, horizon `h`, stage window `W` (a set of sessions), processed in ascending session order:

1. **Score** every `s ∈ U(D)`: SEL-001 → predicted excess return (§9.1); BASELINE-001 →
   `BaselineSignalProvider.predict(stock_id, features).probability` (§9.2).
2. **Candidates**: the top `selection_top_k` (default 10) by score, ties broken by `stock_id` ascending. Chosen
   among all of `U(D)` before label status is known.
3. **Duplicates**: key `(m, h, s, D)`; the first by rank is kept, others get disposition `DUPLICATE`. Unique by
   construction offline; the same key guards live repeated scans.
4. **Overlap suppression**: per stock, with `D_a` the last `ACCEPTED` trade in the stage, a candidate on `D` is
   `OVERLAP_SUPPRESSED` if `i(D) < i(D_a) + h` (its hold `[D+1, D+h]` overlaps `[D_a+1, D_a+h]`). Suppressed
   candidates are not replaced. Each stage starts with no open trades.
5. **Unresolved**: an accepted candidate whose row is not `RESOLVED`, or whose session is not benchmarkable, is
   `UNRESOLVED`, excluded from statistics and counted.
6. Remaining candidates are `ACCEPTED`. **`n` = number of `ACCEPTED` trades.** All statistics use exactly these.

## 9. Models

### 9.1 SEL-001

- One model per horizon; target `y = r − b(D,h)` on `RESOLVED` rows of benchmarkable sessions, winsorised to the
  training set's own 1st and 99th percentiles.
- `xgboost.XGBRegressor(n_estimators=400, max_depth=4, learning_rate=0.05, subsample=0.8,
  colsample_bytree=0.8, min_child_weight=100, reg_lambda=1.0, objective="reg:squarederror", tree_method="hist",
  random_state=42, n_jobs=2)`. Rows sorted by `(session_date, stock_id)` before fitting. NaN left as missing.
- No tuning of any kind; parameters are part of the config snapshot. Changing any of them is a new model version.
- Fitted artefacts are saved with `app/challenger_artefact.py::save_artefact(root="db://")` into `ModelArtefact`;
  `ModelVersion(version="SEL-001", feature_version="FV-002", status="SHADOW")` is registered on first run.

### 9.2 BASELINE-001

No fitting. Scores come from the existing `BaselineSignalProvider`; the model is evaluated exactly as it runs
live, restricted to `U(D)`.

## 10. Validation protocol

### 10.1 Windows

- **Held-out month `H(h)`**: the latest calendar month `M` (by IST session date), not the current month, all of
  whose sessions have `i(D)+h ≤` the last index of `S`. A month is never used until every one of its sessions
  has its exit session `D+h` in `S`; `H(3)` and `H(5)` may therefore differ. Window rows: sessions in `M`;
  protected span: from the first session of `M` through the exit session of its last session.
- **Walk-forward test months**: every calendar month from `selection_wf_first_test_month` (default 2018-01)
  through the month before `H(h)`.
- **Refit cadence**: quarterly. The model for calendar quarter `q` is fitted on rows whose exit session index is
  `< i(first test session of q) − E`, with `E = DEFAULT_EMBARGO_DAYS` (2) sessions, and scores that quarter's test
  months. A fit with fewer than `selection_min_fit_rows` (default 100,000) rows skips its months as
  `FOLD_TOO_SMALL` (recorded). BASELINE-001 needs no fit; it is scored on the same test months.
- **Held-out model `F_H`**: fitted on rows with exit index `< i(first session of H) − E`, outside the protected
  span. `F_H` is the artefact a `PUBLISH` decision authorises and the one live shadow scoring uses.
- Every row whose `[D, D+h]` intersects the protected span is excluded from all fits and all walk-forward tests.

### 10.2 Holdout registry enforcement

- At run start, per `(m, h)`, insert `HoldoutWindowRegistry(label="SPG-001:<model_version>:h<h>:<YYYY-MM>",
  window_start, window_end, registry_version="SPG-HOLDOUT-001")`. It protects the span within the run.
- Immediately before held-out scoring (after `F_H` is fitted), insert `HoldoutUsageRecord(holdout_label=label)`.
  Its uniqueness makes each exam single-use. If it already exists, the pair gets `NO_EDGE /
  HOLDOUT_ALREADY_CONSUMED` and no held-out statistics. Failures before this point do not burn the month.
- **No peeking**: a model version may be examined on month `M` only if its `ModelVersion.created_at` precedes the
  earliest `registered_at` of any `SPG-HOLDOUT-001` row for month `M` or later at the same horizon; otherwise
  `NO_EDGE / HOLDOUT_PREVIOUSLY_OBSERVED`.
- Config is frozen before the run (setting defaults and code constants). A config change can never be judged on
  an already-consumed month because the usage record is per model, horizon and month.
- `SPG-HOLDOUT-001` months are rolling exams: once consumed they may be training history in later runs.
  `challenger_training.registered_holdouts` and `purged_embargo_validation.compute_purged_training_set` ignore
  rows with this `registry_version`, so the existing "consumed once, ever" semantics are unchanged for their own
  rows.
- `--walk-forward-only` runs (§16) never read, register or consume the held-out month.

## 11. Statistics and gate rule (rule `SPG-001`)

For each accepted trade `j` in a stage: `r_j` gross, `b_j = b(D_j,h)`, `net_j = r_j − c`,
`e_j = r_j − c − b_j`. Stage aggregates: `mean_gross`, `mean_benchmark`, `cost = c`, `mean_net`,
`mean_excess = mean(e_j)`, all over the `n` accepted trades.

**Confidence interval**: circular moving-block bootstrap over sessions. Let `T_1 … T_m` be the stage's sessions
with at least one accepted trade, in order; block length `L = h`. Each draw picks `ceil(m / L)` block starts
uniformly from `0 … m−1` using one `numpy.random.default_rng(selection_bootstrap_seed)` generator per
`(model, horizon, stage)`, takes `L` consecutive sessions circularly per block, truncates to `m` sessions, and
computes the trade-weighted mean `e` over trades in the drawn sessions. `B = selection_bootstrap_draws` (default
10,000). `ci_low, ci_high = numpy.percentile(draws, [2.5, 97.5], method="linear")`. If `m < 2L` the CI is
undefined.

**Stage result.** Reasons are evaluated in this order, and all failing reasons are recorded:

1. `unresolved / (accepted + unresolved) ≤ selection_max_unresolved_share` (default 0.02), else
   `DATA_INTEGRITY_UNRESOLVED_TRADES`.
2. `n ≥ min_trades(stage)` (walk-forward 2,000, held-out 100), CI defined, and for walk-forward
   `folds_tested ≥ selection_min_wf_folds` (default 24); else `INSUFFICIENT_OUT_OF_SAMPLE_EVIDENCE`.
3. `mean_excess ≥ selection_min_net_excess` (default 0.0050), else `EDGE_BELOW_THRESHOLD`.
4. `ci_low > 0`, else `CONFIDENCE_THRESHOLD_NOT_MET`.

`PASS` iff no reason. **Decision `PUBLISH` iff both stages `PASS`**, else `NO_EDGE`. Primary reason: the first
failing reason of `WALK_FORWARD`, else of `HELD_OUT`; the display string is `NO_EDGE — <PRIMARY_REASON>`.
A run that fails after start writes `NO_EDGE / EVALUATION_FAILED` for the affected pairs (best effort).

## 12. Schema (migration `0189_selection_publish_gate`, after `0188_evidence_decay_deadline`)

All new tables are append-only: a `before_update` listener in the owning module rejects every field change (the
`app/recommendations.py:48` pattern), and there is no delete path.

### 12.1 `selection_gate_decisions`

Identity and versions:
- `id`, `decision_uuid` (unique), `model_version`, `horizon_sessions`
- `gate_rule_version` (`SPG-001`), `universe_rule_version` (`SEU-001`), `dataset_version` (`SEL-DS-001`),
  `dataset_sha256`, `feature_version`
- `artefact_id` (FK `model_artefacts`, null for BASELINE-001), `artefact_sha256`
- `config_snapshot` (JSON of every §17 setting plus model hyperparameters), `config_sha256` (canonical sorted-key
  JSON), `code_version` (image git SHA)

Periods:
- `dataset_first_session`, `dataset_last_session`
- walk-forward: `wf_first_test_session`, `wf_last_test_session`, `wf_folds_tested`, `wf_folds_skipped`,
  `wf_folds` (JSON per test month: fit quarter, train rows, train first/last session, `n`, `mean_excess`)
- held-out: `holdout_month`, `holdout_first_session`, `holdout_last_session`, `holdout_label`,
  `holdout_registry_id`

Per stage, prefixed `wf_` and `ho_`:
- universe: `sessions`, `eligible_stock_sessions`, `mean_eligible_per_session`, `labelled_stock_sessions`,
  `unresolved_stock_sessions`, `excluded_by_reason` (JSON)
- reduction: `candidates`, `duplicates_removed`, `overlap_suppressed`, `unresolved_trades`, `trades`, `min_trades`
- returns: `mean_benchmark`, `mean_gross`, `cost`, `mean_net`, `mean_excess`, `ci_low`, `ci_high`,
  `bootstrap_sessions`
- outcome: `result` (`PASS`/`FAIL`), `reasons` (JSON)

Outcome:
- `decision` (`PUBLISH`/`NO_EDGE`), `primary_stage`, `primary_reason`, `reasons` (JSON, all)
- `decided_at`, `valid_until` (`decided_at + selection_decision_validity_days`, default 45), `created_at`

Returns are `Numeric(12, 8)`.

### 12.2 `selection_gate_trades`

One row per candidate, all dispositions:
- `decision_id` (FK), `stage`, `session_date`, `stock_id`, `horizon_sessions`, `rank`, `score`
- `entry_session`, `exit_session`, `entry_open`, `exit_close_adjusted`, `label_status`
- `gross_return`, `benchmark_return`, `cost`, `net_return`, `excess_return`
- `disposition` (`ACCEPTED`, `DUPLICATE`, `OVERLAP_SUPPRESSED`, `UNRESOLVED`)

Unique `(decision_id, stage, session_date, stock_id)`.

### 12.3 `selection_benchmark_sessions`

- `dataset_version`, `dataset_sha256`, `session_date`, `horizon_sessions`
- `eligible_count`, `labelled_count`, `unresolved` (JSON by status), `excluded` (JSON by reason)
- `benchmark_return`, `benchmarkable`, `created_at`

Unique `(dataset_sha256, session_date, horizon_sessions)`; a rebuild with the same hash writes nothing.

### 12.4 `selection_shadow_scores`

- `model_version`, `artefact_sha256`, `horizon_sessions`, `session_date`, `stock_id`
- `score`, `rank`, `selected` (bool), `suppression_reason` (null, `DUPLICATE`, `OVERLAP_SUPPRESSED`), `scored_at`

Unique `(model_version, horizon_sessions, session_date, stock_id)`.

### 12.5 `predictions` additions

- `publication_state` `String(16)`, NOT NULL, values `PUBLISHED` or `SHADOW`. The migration sets existing rows to
  `PUBLISHED` (they were public) and then drops the server default, so an insert that omits it fails (fail
  closed).
- `publish_gate_decision_id` (FK `selection_gate_decisions`, nullable) and `publish_gate_reason` (`String(64)`,
  nullable).
- All three join the `Prediction` immutable-field list.

## 13. Decision state machine

Per `(model_version, horizon)` the *latest decision* is the row with the highest `id`. A later `NO_EDGE`
supersedes an earlier `PUBLISH`.

`authorize_publication(session, *, model_version, horizon_sessions, at, artefact_sha256=None) ->
PublishAuthorization(state, reason, decision_id)` returns `PUBLISH` only if every check passes, in order. The
first failure returns `NO_EDGE` with its reason:

1. A latest decision exists, else `NO_DECISION_ON_RECORD`.
2. Its `decision == PUBLISH`, else its own `primary_reason`.
3. `gate_rule_version`, `universe_rule_version`, `dataset_version` and `config_sha256` equal the running code's,
   else `DECISION_MISMATCH`.
4. `at ≤ valid_until`, else `DECISION_STALE`.
5. For SEL-001, the caller's `artefact_sha256` equals the decision's, else `DECISION_MISMATCH`.
6. The stored numbers re-satisfy §11: both results `PASS`, trades ≥ min, `mean_excess ≥ min`, `ci_low > 0`,
   required fields non-null. Else `DECISION_INVALID`.

Any exception → `NO_EDGE / AUTHORIZATION_ERROR` (logged). BASELINE-001 horizons 1 and 7, which the gate never
evaluates, always resolve to `NO_DECISION_ON_RECORD`.

### 13.1 Publication capability

`authorize_publication` answers only the selection question. Whether a model may publish at all is a separate,
code-reviewed constant, `PUBLICATION_CAPABILITY` in `app/selection_gate/capability.py`:

- `BASELINE-001` → `PUBLISHABLE` (it already carries the live trade geometry)
- `SEL-001` → `SHADOW_ONLY` (no trade geometry yet; changing this needs the geometry phase and its own approval)
- any other model version → `SHADOW_ONLY` (fail closed)

`publication_capability(model_version) -> str` reads it. It is not a setting and not in the config snapshot:
changing it means a reviewed code change and a deploy. It never alters a gate decision row. A decision for
SEL-001 can be `PUBLISH` while SEL-001 still publishes nothing.

Effective publication for a prediction = gate decision `PUBLISH` **and** capability `PUBLISHABLE` **and** the
stock is in `U(D)` (§14.1).

## 14. Publish-path enforcement and public surfaces

### 14.1 `record_recommendation`

Before the `Prediction(...)` constructor (today `app/recommendations.py:116`):

1. `auth = authorize_publication(model_version=…, horizon_sessions=horizon_days, at=created_at)`.
2. If `auth.state == PUBLISH`, check `publication_capability(model_version) == PUBLISHABLE`, else
   `SHADOW_ONLY_CAPABILITY`.
3. If both pass, check that the stock is in `U(D)` for the prediction's session (same `SEU-001`
   code); not in it, or not computable, gives `OUTSIDE_GATE_UNIVERSE`. Live, rule 2 of §6 accepts the
   bar the scan used for `D` (the 16:15 IST scan's provisional bar); this is the only live deviation from §6, and
   the existing 08:30 IST official-candle confirmation (EPIC-853) still invalidates a call the official bar
   contradicts.
4. `publication_state = PUBLISHED` iff all three pass, else `SHADOW`. `publish_gate_decision_id` is recorded
   whenever a decision was found. `publish_gate_reason` is the first failing reason (the gate's `NO_EDGE` reason,
   `SHADOW_ONLY_CAPABILITY` or `OUTSIDE_GATE_UNIVERSE`), and null when published.
5. `register_prediction` / `sync_prediction_from_tip` (line 144) run **only** when `PUBLISHED`.

### 14.2 Downstream side effects

Every path that produces user-visible output from a `Prediction` requires `publication_state = 'PUBLISHED'`.
The known sites:
- ranking: `PositiveOpportunityRanking` inclusion; shadow rows are never `included`
- publication: `continuous_discovery` → `publish_recommendation` (M1.47) is skipped for `SHADOW`
- alerts and notifications (`alert_generation`, `alert_delivery`)
- API readers: `api/services/active_ranking.py::current_ranking_ids()`, which covers `/recommendations`,
  `/opportunities`, `/predictions/active` and the dashboard; plus `instruments`, `recommendation_detail`,
  `tracking`, `discovery`, `discovery_intelligence`, `market` and `recommendations_snooze` in `api/services/`

The implementation plan audits every reader of `Prediction` in `app/` and `api/` and lists each with its
disposition. A single predicate `published_only(query)` is used everywhere.

### 14.3 `/predictions/active` meta

`meta.publishGate` is a list, one entry per pair in `selection_gate_reported_pairs` (default BASELINE-001 × {1,3,5,7},
SEL-001 × {3,5}):
- `modelVersion`, `horizonSessions`
- `gateDecision` (`PUBLISH`/`NO_EDGE`), `reason` (e.g. `NO_EDGE — CONFIDENCE_THRESHOLD_NOT_MET`, null on
  `PUBLISH`), `stage`
- `publicationCapability` (`PUBLISHABLE`/`SHADOW_ONLY`)
- `publishing` (bool: `gateDecision == PUBLISH` and `publicationCapability == PUBLISHABLE`)
- `decisionId`, `decidedAt`, `validUntil`

With no pair `publishing`, `data` is empty.

## 15. Shadow behaviour

### 15.1 Shadow predictions

A `SHADOW` prediction keeps the rows `record_recommendation` writes today, except the ledger tip, the publication
and any ranking inclusion. Its outcome for learning comes from `SEL-DS-001` labels joined on
`(session, stock, horizon)`, not from the tip tracker.

### 15.2 SEL-001 daily shadow scoring

- CronJob `market-agent-selection-shadow`, 08:35 IST Mon–Fri, after the 08:30 IST official-candle confirmation,
  so features use `D`'s official bar and the call would still be tradeable at the `D+1` open.
- For each `h`, it loads the artefact of the latest SEL-001 decision for `h` (any state); none gives a logged
  skip. It scores all of `U(D)` and writes `selection_shadow_scores` for every stock, marking the top K after
  duplicate and overlap reduction against earlier shadow selections (§8 rules).
- Shadow scores are never read by public routes.

### 15.3 SEL-001 publication

Not built in this phase. SEL-001's capability is `SHADOW_ONLY` (§13.1). A SEL-001 `PUBLISH` decision is recorded
and reported, but no public SEL-001 call is created until the trade-geometry phase adds a publish path, and its
capability is changed with separate approval. That path must call `authorize_publication` with the live artefact
hash.

## 16. Execution (Kubernetes)

CronJob `market-agent-selection-gate`:
- schedule `30 20 10 * *` UTC (02:00 IST on the 11th, when the previous month's 5-session labels have resolved)
- `timeZone: Etc/UTC`, `concurrencyPolicy: Forbid`, `startingDeadlineSeconds: 3600`, `backoffLimit: 0`,
  `activeDeadlineSeconds: 21600`, `ttlSecondsAfterFinished: 604800`
- the `wait-for-db` initContainer, and `DATABASE_URL` from `market-agent-secrets`, as `learning-cycle-cronjob.yaml` does
- command `python -m scripts.run_selection_gate`
- resources: requests `cpu: 1, memory: 3Gi`; limits `cpu: 2, memory: 6Gi` (approved). They live only in the
  manifest, so an environment changes them with a kustomize patch and no code change; `--shard-cutoffs` trades
  memory for time

CronJob `market-agent-selection-shadow`: same shape; schedule `5 3 * * 1-5`, `activeDeadlineSeconds: 1800`,
limit `memory: 2Gi`.

Both are added to the base kustomization, so `vps-deploy.sh`'s suspend/resume covers them.

Manual run: `kubectl -n market-agent create job --from=cronjob/market-agent-selection-gate selection-gate-manual-<YYYYMMDD>`.

`scripts/run_selection_gate.py` flags:
- `--models` (default `BASELINE-001 SEL-001`), `--horizons` (default `3 5`)
- `--walk-forward-only`: no holdout read, registration or consumption; no decision written; report only
- `--shard-cutoffs N`

It prints the report (§19) to stdout as text and a JSON document.

## 17. Configuration (`app/settings.py`, persisted in every snapshot)

- `selection_gate_horizons = (3, 5)`
- `selection_top_k = 10`
- `selection_round_trip_cost = 0.0030`
- `selection_min_net_excess = 0.0050`
- `selection_min_wf_trades = 2000`
- `selection_min_holdout_trades = 100`
- `selection_min_wf_folds = 24`
- `selection_bootstrap_draws = 10000`
- `selection_bootstrap_seed = 42`
- `selection_max_unresolved_share = 0.02`
- `selection_min_session_stocks = 100`
- `selection_min_history_bars = 60`
- `selection_min_median_traded_value_20d = 100000`
- `selection_min_benchmark_stocks = 100`
- `selection_min_plausible_return = -0.60`
- `selection_max_plausible_return = 1.50`
- `selection_wf_first_test_month = "2018-01"`
- `selection_min_fit_rows = 100000`
- `selection_decision_validity_days = 45`
- `selection_gate_reported_pairs` (display only; excluded from the snapshot and its hash)
- SEL-001 hyperparameters (§9.1), `DEFAULT_EMBARGO_DAYS`, and the rule and dataset versions

## 18. Tests (written first; SQLite in-memory, `Base.metadata.create_all`)

Gate rule (pure functions over synthetic trades and benchmarks):
- sufficient positive edge with CI above zero → `PUBLISH`
- positive observed edge but CI crossing zero → `NO_EDGE / CONFIDENCE_THRESHOLD_NOT_MET`
- edge below threshold → `NO_EDGE / EDGE_BELOW_THRESHOLD`
- too few trades, or undefined CI → `NO_EDGE / INSUFFICIENT_OUT_OF_SAMPLE_EVIDENCE`
- walk-forward fails and held-out passes → `NO_EDGE`, primary stage `WALK_FORWARD`
- held-out fails and walk-forward passes → `NO_EDGE`, primary stage `HELD_OUT`
- unresolved share over the limit → `DATA_INTEGRITY_UNRESOLVED_TRADES`
- bootstrap is deterministic for a fixed seed, and matches a hand-computed example on a tiny set

Reduction:
- duplicate predictions cannot inflate the sample: the same `(m,h,s,D)` twice counts once; ten repeated scans
  leave `n`, `mean_excess` and the CI unchanged
- overlapping holds of the same stock are suppressed and not replaced; a non-overlapping re-pick is accepted
- candidates are chosen before label status (a suspended stock stays a candidate and becomes `UNRESOLVED`)

Dataset:
- features never see a bar after `cutoff(D)` (observer)
- entry is the `D+1` open and exit the `D+h` close, adjusted across a split
- each `label_status` case
- benchmark is the mean over resolved `U(D)` rows only, with counts persisted
- universe exclusion reasons and liquidity floor
- dataset hash is stable across two builds

Holdout:
- held-out rows and label-overlapping rows are absent from every fit
- a second exam on the same month → `HOLDOUT_ALREADY_CONSUMED`
- a model version created after a month was examined → `HOLDOUT_PREVIOUSLY_OBSERVED`
- `--walk-forward-only` writes no registry, usage or decision rows
- existing CHT readers ignore `SPG-HOLDOUT-001` rows

Authorisation (fail closed): no decision; latest `NO_EDGE` after an earlier `PUBLISH`; stale; each mismatch
field; artefact hash mismatch; tampered numbers (`DECISION_INVALID`); exception inside → `NO_EDGE` every time.
Also: a `PUBLISH` at h=5 does not authorise h=3.

Capability:
- `BASELINE-001` is `PUBLISHABLE`; `SEL-001` and an unknown model version are `SHADOW_ONLY`

Publish path:
- no decision → prediction `SHADOW`, no `Tip` row, no publication, not ranked
- valid `PUBLISH`, `PUBLISHABLE` and in-universe → `PUBLISHED` with a tip
- valid `PUBLISH` for a `SHADOW_ONLY` model → `SHADOW / SHADOW_ONLY_CAPABILITY`, the decision id recorded, and
  the decision row unchanged
- `PUBLISH` but out of universe → `SHADOW / OUTSIDE_GATE_UNIVERSE`
- insert without `publication_state` fails

Public surfaces: a seeded `SHADOW` prediction, fully ranked, lifecycle open, with a generation, is absent from
every public route (parametrised over `/predictions/active`, `/predictions/active/{id}`, `/recommendations`,
`/opportunities`, `/dashboard/snapshot`, `/instruments/{symbol}` and the other audited readers). `meta.publishGate`
reports each pair's gate decision, reason, capability and `publishing`, and a SEL-001 `PUBLISH` decision shows
`publishing: false`.

Shadow:
- shadow predictions continue when publication is blocked (the scan still writes `SHADOW` rows)
- SEL-001 shadow scoring writes one row per `U(D)` stock and marks the top K

Append-only: updating any column of the new tables or of the new `Prediction` columns raises.

Migration: `0189` upgrades and downgrades on SQLite; existing predictions become `PUBLISHED`; single alembic head.

## 19. Acceptance criteria and review report

Acceptance criteria:
1. All §18 tests pass, and the existing suites for recommendations, discovery, predictions_active,
   recommendations, dashboard, instruments, walk-forward, challenger and holdout pass locally.
2. A pre-merge evaluation run on a read-only production snapshot writes nothing to production. It follows the
   EPIC-843 isolation contract: production is read through a separate read-only engine, and all writes go to an
   ephemeral store. It must complete for BASELINE-001 and SEL-001 at h=3 and h=5 within the job limits.
3. The config used in that run is frozen at a commit SHA. Any change afterwards burns that held-out month for the
   production run: the next month is used instead.
4. After approval and deploy, the first production run writes decisions whose numbers match the pre-merge run for
   the same dataset hash; any difference is reported.
5. With every pair `NO_EDGE`, the next scan creates only `SHADOW` predictions, no new Marksy tips appear, and the
   public routes list no new calls.

Review report (before merge or deploy):
- **Formulas:** §7–§11 as implemented, citing file and line.
- **Data sources:** tables read, session range, per-year stock counts, `dataset_sha256`, per-horizon universe
  totals and exclusion counts.
- **Tests:** names and results.
- **Gate results:** for each of the four pairs, every §12.1 stage field plus the decision and reasons.

## 20. Review decisions (approved 2026-10-01)

1. **SEL-001 publishing.** SHADOW-only this phase even if it passes the gate; its capability is `SHADOW_ONLY`
   (§13.1) until trade-geometry validation is implemented and separately approved.
2. **VPS capacity.** 6Gi / 2 CPU limits approved for the monthly gate job; resources stay configurable per
   environment (§16).
3. **Liquidity floor.** ₹1 lakh median 20-session traded value, applied identically offline and live. A
   BASELINE-001 candidate below the floor is `SHADOW`; nothing bypasses `SEU-001`.
4. **Decision validity.** 45 days, fail closed: an expired or missing decision means no publication until a fresh
   valid decision exists.
5. **Held-out lag.** The latest fully resolved month for the horizon; a month is unused until all its `D+h` exit
   sessions exist (§10.1).
6. **Existing calls.** Pre-rollout predictions and tips are untouched and finish their existing lifecycle.
7. **Gate decision vs publication.** `PUBLISH` is selection authorisation only; actual publication also needs a
   `PUBLISHABLE` capability (invariant 12, §13.1).
