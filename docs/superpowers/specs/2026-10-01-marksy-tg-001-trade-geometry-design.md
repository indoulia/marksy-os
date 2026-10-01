# Marksy TG-001 trade geometry validation — design

Status: draft for review 2026-10-01 · Repo: marksy-api (owner), new package `app/trade_geometry/`. SPG-001
(`app/selection_gate/**`) is frozen and only imported. marksy-os and admin-app: no change this phase.

## 1. Goal and separation

TG-001 answers one question: given SEL-001's selections, can a predeclared rule of holding horizon `H`, target `T`
and stop `S` turn the signal into a statistically defensible trade, measured as net excess return over the same
eligible universe traded under the same rule?

The questions stay apart:

- **Selection (SPG-001)**: does the model pick stocks with genuine forward excess return? SPG never reads a target,
  stop, hit rate or any TG output.
- **Trade geometry (TG-001)**: does a fixed `(H, T, S)` rule convert those picks into net excess? TG never changes
  a selection, a model, an SPG threshold or an SPG decision.
- **Publication**: a SEL-001 call is eventually published only with SPG `PUBLISH` **and** TG `GEOMETRY_PASS` **and**
  a reviewed capability change (§18). SPG `PUBLISH` and TG `GEOMETRY_PASS` are separate decisions, and neither
  implies the other.

This phase activates no publication. SEL-001 stays `SHADOW_ONLY`.

## 2. Background

- SPG-001 is live (marksy-api `main`, `e5812e5`). Its evaluation (2026-10-01) gave SEL-001 a walk-forward mean net
  excess of +0.50% at h=3 and +0.87% at h=5. The held-out stage failed on its 21-session month, so both SEL-001
  pairs are `NO_EDGE`, and SEL-001's capability is `SHADOW_ONLY` anyway (SPG-001 §13.1).
- The live BASELINE-001 geometry is a fixed +5% target and −3% stop. With zero edge, a stop is about twice as
  likely as a target (SPG-001 §2). TG-001 does not assume that geometry. It is a reported reference, never a
  candidate (§10.3).
- SPG persists every walk-forward candidate of every evaluated decision, all dispositions (`app/selection_gate/runner.py:203`
  → `records.py::write_trades`). SEL-001 also scores the full eligible universe daily in shadow
  (`shadow.py::run_selection_shadow`). TG-001 consumes those two outputs and nothing else from SPG's evaluation.

## 3. Invariants

1. **SPG frozen.** TG never modifies `app/selection_gate/**`, any `selection_*` setting, any SPG table, or any SPG
   threshold or semantics. TG only imports SPG functions (§6.4).
2. **One-way dependency.** TG reads from SPG. SPG never imports `app/trade_geometry` and never reads a `tg_*`
   table. Tests enforce both directions (§21.7).
3. **No SPG held-out reads.** TG never reads a `selection_gate_trades` row with `stage = 'HELD_OUT'`, nor any
   `ho_*` or `holdout_*` column of `selection_gate_decisions`.
4. **No SPG writes.** A TG run writes only the `tg_*` tables and its own execution row. SPG rows are byte-identical
   before and after a TG run. TG never writes `holdout_window_registry`, `selection_holdout_usages` or
   `holdout_usage_records`.
5. **No publication change.** No TG row changes any `decide_publication` outcome. `PUBLICATION_CAPABILITY` and
   `app/selection_gate/publication.py` are untouched.
6. **Predeclared grid.** 36 geometries per selector, frozen in configuration and hashed (`grid_sha256`). There is no
   adaptive refinement.
7. **Geometry chosen on walk-forward only.** The choice is frozen and durably committed before any held-out path
   is read. Each held-out month is single-use per selector and tests exactly one geometry.
8. **Objective is net excess.** The objective is mean `r − c − b_g(D)` against the same universe under the same
   geometry, with its CI. It is never the hit rate.
9. **Conservative fills.** Daily bars only. A same-bar touch of both barriers is a stop. A gap through the stop
   fills at the open. A gap above the target fills at the target.
10. **Label independence.** Candidates, path resolution, protected-span exclusion and the walk-forward session set
    are fixed without looking at outcomes.
11. **Reduction before statistics.** Every count, mean and CI uses the reduced trade set (§12).
12. **Fail closed, append-only, auditable.** Any error gives `NO_GEOMETRY`. All `tg_*` tables are append-only and
    hold every input, hash and result needed to reproduce a decision.
13. **No artificial pass.** No threshold, grid or rule changes because production has no public calls.

## 4. Scope

In:
- the `app/trade_geometry/` package: path dataset TG-DS-001, fill rules, path statistics, grid, universe
  benchmarks, reduction, walk-forward geometry selection, the reality check, the freeze, the TG holdout registry and
  exam, decisions and records
- migration `0190_trade_geometry`
- `scripts/run_trade_geometry.py`, its CronJob and orchestration entries
- the report, the tests, and the publication-integration design (§18, design only)

Out:
- any change to SPG-001 code, settings, tables or thresholds
- SEL-001 publication, its capability, a SEL-001 live publish path, or a TG check in `decide_publication`
- BASELINE-001's live geometry (untouched)
- refitting, retuning or re-ranking SEL-001
- intraday bars, slippage beyond `c`, position sizing, portfolio limits
- any UI, and marksy-os and admin-app changes

## 5. Definitions

- **Official bar, session list `S`, `i(D)`, `D+k`, `session_date(D)`, cutoff, `U(D)`**: exactly as SPG-001 §5–§6.
  They come from `build_selection_dataset` (SEL-DS-001, SEU-001), unchanged.
- **Selector** `σ = (SEL-001, h_sel)`, `h_sel ∈ tg_selector_horizons = (3, 5)`: the SEL-001 model SPG gates at
  `h_sel`. `h_sel` fixes which selections are used. It does not fix the holding horizon.
- **Geometry** `g = (H, kT, kS)`: `H ∈ tg_holding_horizons`, `kT ∈ tg_target_atr_multiples`,
  `kS ∈ tg_stop_atr_multiples`. `None` means no barrier. `barriers(g)` = number of non-`None` multiples (0, 1 or 2).
- **atr**: the row's `atr_percent` at `D` in SEL-DS-001. It is `atr_14 / close` (`app/features/technical.py:29-30`,
  a 14-bar simple mean of true range), a fraction, not a percentage, on bars back-adjusted to `D`, and available at
  `cutoff(D)`. It is stored as `float32`.
- **Entry** `o`: `rows.entry_open` of SEL-DS-001, the raw official open at `D+1` (`float64`).
- **Path**: adjusted official bars `(O_t, H_t, L_t, C_t)` for `t = 1..7`, where bar `t` is the stock's bar at
  `S[i(D)+t]` (§7.2).
- **Levels**: `target_px = o × (1 + kT × atr)` and `stop_px = o × (1 − kS × atr)`, in `float64`.
- **Exit offset** `t_x ∈ 1..H`; **exit index** `X = i(D) + t_x`; **exit session** `S[X]`.
- **Cost** `c` = `selection_round_trip_cost` (0.0030) by value. **K** = `selection_top_k` (10) by value.
- **Stage**: `WALK_FORWARD` or `HELD_OUT` (SPG vocabulary).
- **TG exam month** `M_TG`, **TG protected span** `P_TG`, and **walk-forward session set** `T_σ`: defined in §13.1.

## 6. Selection input and SPG independence

### 6.1 Selectors and horizons

Each selector `σ = (SEL-001, h_sel)` is evaluated at every `H ∈ {1, 3, 5, 7}`, independently of `h_sel`. That
gives 36 geometries per selector and 72 in total. The two selectors are never pooled. Each gets its own decision.

### 6.2 Walk-forward selections

- **Bound decision**: the latest `selection_gate_decisions` row (highest `id`) with `model_version = 'SEL-001'` and
  `horizon_sessions = h_sel`, whatever its `decision`. TG records its `id` as `bound_spg_decision_id`.
- **Candidates**: read with exactly this predicate, through the read session:

  ```
  SELECT id, session_date, stock_id, rank, score, label_status, disposition
  FROM selection_gate_trades
  WHERE decision_id = :bound_spg_decision_id AND stage = 'WALK_FORWARD' AND rank <= :K
  ```

  TG keeps every disposition. It uses `session_date`, `stock_id`, `rank` and `score`. It reads `label_status` and
  `disposition` only for the parity block (§10.2) and never uses SPG's disposition to reduce.
- **Columns read from the bound decision**: `id`, `model_version`, `horizon_sessions`, `decided_at`,
  `primary_reason`, `config_snapshot`, `config_sha256`, `dataset_sha256`, `wf_trades`, `wf_mean_excess`. It never
  reads `ho_*` or `holdout_*`. Only the separate provenance query of §13.5 reads artefact hashes.
- **`NO_BOUND_SELECTION_DECISION`** when any of these holds: no such decision exists; it has no `WALK_FORWARD` rows
  (for example `EVALUATION_FAILED`); or `config_snapshot["selection_top_k"] ≠ K`.
- **Session mapping**: each `session_date` maps to `i(D)` in TG's rebuilt `S`. A candidate whose date is not in
  `S`, or whose `(D, s)` has no SEL-DS-001 row, gets path status `NOT_IN_DATASET` (§7.3).

SPG's WF candidates are out of sample by construction. Each quarter's model is fitted on rows whose exit is before
the test start minus the embargo (`fit_row_mask`, SPG-001 §10.1), and its sessions already exclude SPG's protected
span at `h_sel`. TG's test paths start after `D`, so they never overlap the selector's training labels, at any `H`.

### 6.3 Held-out selections

The live SEL-001 shadow scores for `M_TG`, read through the read session:

```
SELECT id, session_date, stock_id, rank, score, artefact_sha256, scored_at
FROM selection_shadow_scores
WHERE model_version = 'SEL-001' AND horizon_sessions = :h_sel
  AND session_date BETWEEN :first_session(M_TG) AND :last_session(M_TG) AND rank <= :K
```

- `rank` is the shadow job's rank by score over its `U(D)`, before overlap (`shadow.py:57-70`). TG ignores
  `selected` and `suppression_reason` and reduces on its own (§12).
- Each row was scored at about 08:35 IST on `D+1`, with features up to `D`, by the artefact of the latest SEL-001
  decision for `h_sel` that existed then. Different sessions of one month may use different artefacts (a new SPG
  decision arrives on the 11th). TG records `artefact_sha256` per trade.
- Shadow coverage and provenance are checked in §13.5.

### 6.4 Reads, imports and writes

TG imports from SPG, unchanged:
- `dataset.build_selection_dataset`, `dataset.session_list`, `dataset.FEATURE_COLUMNS`, `SelectionDataset`
- `features.bars_frame`
- `statistics.block_bootstrap_ci`, `evaluate_stage`, `AcceptedTrade`, `StageThresholds`
- `validation.month_of`, `month_text`, `parse_month`, `protected_rows`
- `authorization.naive_utc`
- `config.config_sha256`, `config.current_config_sha256`
- `code_version.selection_code_version`, `metrics.measured`
- the label, disposition, stage and reason constants of `constants.py`

TG also imports `app.corporate_action_adjustment.ratio_actions_for` and `cumulative_ratio`, and
`app.market_data.bar_finality.final_bars_only`.

TG reads: `market_prices` (official bars), `stocks` and corporate actions (through the builder), the two SPG
queries above, `selection_gate_decisions.artefact_sha256` and `decided_at` (for shadow provenance, §13.5),
`holdout_window_registry` rows with `registry_version = 'SPG-HOLDOUT-001'` (`label`, `registered_at`; §13.5), and
its own `tg_*` tables. It reads nothing else.

TG writes only: `tg_decisions`, `tg_geometry_results`, `tg_trades`, `tg_holdout_windows`, `tg_holdout_usages`,
and the `OPERATION_TRADE_GEOMETRY` execution row.

### 6.5 Why TG cannot contaminate SPG's held-out evidence

- **SPG never sees TG.** SPG never imports TG and never reads a `tg_*` table, so no SPG fit, selection, threshold or
  decision can depend on TG.
- **TG never sees SPG's exam.** TG never reads SPG's `HELD_OUT` rows or `ho_*` and `holdout_*` fields. SPG's
  held-out evidence for a month is the `F_H` artefact's scores and outcomes on that month. TG's held-out
  selections are the shadow scores of earlier artefacts, made live, so even on the same month they are a
  different, earlier model's picks.
- **Binding does not depend on outcomes.** TG binds the latest SPG decision whatever its `decision`. SPG computes the
  walk-forward stage independently of its held-out stage (`runner.py:136-141`), so TG's input never depends on
  SPG's held-out outcome.
- **The exam month is already SPG-protected.** TG examines `M_TG` for `σ` only after SPG has registered an exam month
  `≥ M_TG` at `h_sel` (the guard in §13.5). SPG's no-peeking rule (`holdout.py::previously_observed`) already blocks
  every SPG model created after that registration from being examined at `h_sel` on `M_TG` or any earlier month. So TG never
  exposes a month SPG does not already treat as observed. The schedule (SPG on the 11th, TG on the 12th) makes the
  guard pass in normal operation. The guard makes the property hold by construction rather than by schedule.
- **Separate registry.** TG's exams live only in `tg_holdout_windows` and `tg_holdout_usages`. CHT
  (`challenger_training.registered_holdouts`, `purged_embargo_validation`) and SPG's registry semantics are
  untouched.
- **Market bars are not evidence.** TG may read official bars of any month, including SPG's exam month. Bars are
  market data, already examined by SPG the day before, and nothing TG derives from them flows back to SPG.
- **Frozen choices are independent.** SPG's configuration is frozen. TG's geometry is chosen from walk-forward data
  only and frozen before TG's held-out paths are read. Neither depends on the other's held-out outcome.

## 7. Dataset and label contract (TG-DS-001)

### 7.1 Base

`build_selection_dataset(read_session, horizons=tg_selector_horizons, skip_failed_stocks=False)`, called once per
run. It fails closed on any stock error, as SPG does. TG uses the following from it:
- `anchors`, `session_dates` and `S`
- `rows` (`session_index`, `stock_id`, `entry_open`, `atr_percent`): exactly `U(D)` under SEU-001, including the
  ₹1 crore liquidity floor and official bars only
- `benchmarks[h_sel]` and `rows.label_status_{h_sel}` / `gross_return_{h_sel}`, for the parity block only

### 7.2 Paths

- For each stock, TG reads its official bars (`MarketPrice` with `final_bars_only()`, ascending `timestamp`, framed
  with `bars_frame`) and its `ratio_actions_for(read_session, stock_id)`. It keeps only bars whose `timestamp` is an
  anchor of `S`, as SPG's `_stock_part` does.
- For row `(D, s)` and `t = 1..7`, bar `t` is the stock's bar at `S[i(D)+t]`, if one exists.
- **Adjustment**: each price of bar `t` is multiplied by
  `float(cumulative_ratio(actions, after=session_date(D+1), through=session_date(D+t)))`. That is the same half-open
  interval `(session(D+1), session(D+t)]` SPG uses for its exit close (`dataset.py:100`,
  `adjust_forward_close`). Bar 1 has factor 1. An action effective on `session(D+1)` itself is already in both `o`
  and bar 1.
- **Storage**: a `float32` array `[rows, 7, 4]` (O, H, L, C), with `NaN` where a bar is absent. `o` stays `float64`.
  All levels, returns and statistics are computed in `float64`.
- **Two passes** (§13.4): pass 1 builds every `U(D)` row with `i(D) ∈ T_3 ∪ T_5`, before any choice. Pass 2 builds rows for
  the sessions of `M_TG`, only after every freeze record of the run is committed. Pass 2 queries bars with
  `timestamp` from the anchor of `M_TG`'s first session through `S[i(last session of M_TG) + 7]`.

### 7.3 Path status

Per `(row, H)`, first match wins:
1. `NOT_YET_RESOLVABLE`: `i(D) + H` is beyond the last index of `S`.
2. `ENTRY_BAR_MISSING`: no bar at `D+1`.
3. `PATH_BAR_MISSING`: no bar at some `D+t`, `2 ≤ t ≤ H`.
4. `INVALID_PRICE`: `o ≤ 0`, or `atr ≤ 0`, or some bar `t ≤ H` fails SEU-001's record arithmetic
   (`min(O,H,L,C) ≤ 0`, `H < max(O,L,C)` or `L > min(O,H,C)`, `universe.py:31`).
5. `SUSPECT_RETURN`: for some `t ≤ H`, `H_t/o − 1`, `L_t/o − 1` or `C_t/o − 1` is below
   `selection_min_plausible_return` (−0.60) or above `selection_max_plausible_return` (1.50).
6. `RESOLVED`.

`NOT_IN_DATASET` is the candidate-level status of §6.2 and §6.3. It has no path.

Statuses 2–5 are monotone: unresolved at `H` means unresolved at every larger `H`. Status depends only on
`(row, H)`, never on the barriers or the outcome. So all nine geometries sharing an `H` share one resolved row set.
This is stricter than SPG's label, which needs only the `D+1` and `D+h` bars. §10.2 and §23 cover the consequence.

### 7.4 Per (row, geometry) outputs

`outcome ∈ {TARGET, STOP, EXPIRED}`, `ambiguous` (bool), `exit_price`, `t_x`, `X`, gross `r = exit_price/o − 1`,
`net = r − c`, `time_to_target = t_x` if `TARGET` else null, `time_to_stop = t_x` if `STOP` else null, and the
trade `mfe` and `mae` (§9.2). Only `RESOLVED` rows have outputs.

### 7.5 Hashes

- `dataset_sha256`: SHA-256 over UTF-8 lines for the pass-1 rows,
  `session_date,stock_id,o,atr,O_1,H_1,L_1,C_1,…,O_7,H_7,L_7,C_7,path_status_1,path_status_3,path_status_5,path_status_7`.
  Lines are sorted by `(session_date, stock_id)`. Numbers are formatted `%.8f`, missing values are the empty
  string, and lines are hashed in chunks as `dataset.py::_sha256` does.
- `ho_dataset_sha256`: the same, over the pass-2 rows.
- `wf_selection_sha256` and `ho_selection_sha256`: SHA-256 over the lines `session_date,stock_id,rank,score`
  (`score` `%.10f`) of the stage's candidates, sorted by `(session_date, rank, stock_id)`.

Row-level paths are rebuilt every run and not stored. Candidate trades of the chosen and reference geometries are
stored (§16.3).

## 8. Fill rules

For geometry `g` and `t = 1..H`, the first rule that matches ends the trade. A rule whose barrier is `None` is
skipped, and rule 3 needs both barriers.

1. `t > 1` and `O_t ≤ stop_px`: `STOP` at `O_t` (gap-through).
2. `t > 1` and `O_t ≥ target_px`: `TARGET` at `target_px` (no gap gain).
3. `L_t ≤ stop_px` and `H_t ≥ target_px`: `ambiguous = true`, `STOP` at `stop_px`.
4. `L_t ≤ stop_px`: `STOP` at `stop_px`.
5. `H_t ≥ target_px`: `TARGET` at `target_px`.

If no rule fires by `t = H`, the outcome is `EXPIRED` at `C_H`, with `t_x = H`. Bar `D+1` lies entirely after
entry, because entry is its open, so rules 1 and 2 do not apply at `t = 1`. The pure time exit `(H, None, None)` is
always `EXPIRED` at `C_H`, which is SPG's hold-to-horizon label.

Worked examples. Unless stated otherwise: `o = 100.00`, `atr = 0.0200`, `g = (5, 1.5, 1.0)`, so
`target_px = 100 × 1.03 = 103.00` and `stop_px = 100 × 0.98 = 98.00`; `c = 0.0030`. Bars are written `(O, H, L, C)`.

- **Target.** t1 `(100.0, 101.5, 99.0, 101.0)`: no rule. t2 `(101.2, 103.4, 100.8, 103.1)`: rule 5, `TARGET` at
  103.00. `r = 0.0300`, `net = 0.0270`, `t_x = 2`, `time_to_target = 2`, `X = i(D)+2`.
- **Stop.** t1 `(100.0, 100.5, 97.6, 98.2)`: rule 4, `STOP` at 98.00. `r = −0.0200`, `net = −0.0230`, `t_x = 1`.
- **Gap-through stop.** t1 `(100.0, 101.0, 99.2, 99.5)`: no rule. t2 opens at 96.50: rule 1, `STOP` at 96.50.
  `r = −0.0350`, `net = −0.0380`, `t_x = 2`.
- **Gap above target.** t1 `(100.0, 102.0, 99.5, 101.8)`: no rule. t2 opens at 104.20: rule 2, `TARGET` at 103.00,
  not 104.20. `r = 0.0300`, `t_x = 2`.
- **Ambiguous.** t1 `(100.0, 103.5, 97.8, 101.0)`: both barriers are touched, so rule 3 gives `STOP` at 98.00 with
  `ambiguous = true`. `r = −0.0200`, `t_x = 1`.
- **Expiry.** t1 `(100.0, 101.2, 99.1, 100.8)`, t2 `(100.9, 102.6, 100.2, 102.0)`, t3 `(101.8, 102.4, 99.4, 99.9)`,
  t4 `(99.9, 101.0, 98.6, 100.6)`, t5 `(100.7, 101.9, 100.1, 101.4)`. No bar reaches 103.00 or 98.00, so the trade is
  `EXPIRED` at `C_5 = 101.40`. `r = 0.0140`, `net = 0.0110`, `t_x = 5`, and both time-to values are null.
- **Split inside the path.** `o = 200.00`, so `target_px = 206.00` and `stop_px = 196.00`. A 2-for-1 split
  (`ratio = 2`) is effective on `session(D+3)`. Raw bars: t1 `(200.0, 202.0, 198.5, 201.0)`,
  t2 `(201.2, 203.0, 199.8, 202.4)`, t3 `(101.0, 102.2, 100.1, 101.6)`, t4 `(101.5, 103.4, 101.0, 103.2)`. The factor is 1
  for t1–t2 and 2 for t ≥ 3. Adjusted: t3 `(202.0, 204.4, 200.2, 203.2)`, t4 `(203.0, 206.8, 202.0, 206.4)`. At t4,
  rule 5 gives `TARGET` at 206.00, `r = 0.0300`. Unadjusted, t3's open of 101.0 would fire rule 1, a false
  `r = −0.4950`.
- **Missing bar.** Bars exist at `D+1`, `D+2`, `D+4` and `D+5`, but not at `D+3`. Then `path_status_1 = RESOLVED`
  and `path_status_3 = path_status_5 = path_status_7 = PATH_BAR_MISSING`. A candidate under any `H ≥ 3` geometry is
  `UNRESOLVED`, even if a barrier fired at `t = 1`. Resolution never depends on the outcome.
- **Reference geometry** (§10.3), `o = 100.00`, so the target is 105.00 and the stop 97.00. t1
  `(100.0, 101.0, 96.8, 97.5)`: rule 4, `STOP` at 97.00, `r = −0.0300`.

## 9. Path statistics

### 9.1 Per (row, H), independent of the barriers

- forward return `fwd_H = C_H / o − 1`
- `MFE_H = max(H_1..H_H) / o − 1`
- `MAE_H = min(L_1..L_H) / o − 1`
- `MDD_H = max_{t ≤ H} (1 − C_t / max(o, C_1..C_t))`

Using the expiry example's bars: `fwd_5 = 0.0140`, `MFE_5 = 0.0260`, `MAE_5 = −0.0140`. The running peak
`max(o, C_1..C_t)` for t = 1..5 is 100.8, 102.0, 102.0, 102.0, 102.0, so `MDD_5 = 1 − 99.9/102.0 = 0.020588`. At `H = 3`: `fwd_3 = −0.0010`,
`MFE_3 = 0.0260`, `MAE_3 = −0.0090`, `MDD_3 = 0.020588`.

### 9.2 Per trade, up to the exit

Bars `1..t_x − 1` count in full. The exit bar counts in full for intrabar exits (rules 3–5 and expiry). For a gap
exit (rules 1–2) it contributes only `O_{t_x}`. Then `mfe = max(...) / o − 1` and `mae = min(...) / o − 1` over
those values.
- In the target example: `mfe = 103.4/100 − 1 = 0.0340` and `mae = −0.0100`.
- In the gap-through example: `mfe = 0.0100` and `mae = 96.5/100 − 1 = −0.0350`.

These are reported only and never decide anything.

## 10. Candidate geometry grid

### 10.1 Grid

`tg_holding_horizons = (1, 3, 5, 7)`, `tg_target_atr_multiples = (None, 1.5, 3.0)` and
`tg_stop_atr_multiples = (None, 1.0, 2.0)` give 4 × 3 × 3 = 36 geometries per selector.

- **Grid order** (`grid_index` 0–35): `H` ascending, then `kT` in tuple order, then `kS` in tuple order, so
  `(1,None,None)=0`, `(1,None,1.0)=1`, …, `(7,3.0,2.0)=35`.
- **`geometry_key`**: `H<H>:T<kT>:S<kS>`, with `none` for `None` and multiples written with one decimal, for example
  `H5:T1.5:S1.0` or `H3:Tnone:Snone`.
- **`grid_sha256`**: SHA-256 of the canonical JSON
  `{"grid": [[H, kT, kS], …in grid order], "reference": {"holding_sessions": 3, "target_pct": 0.05, "stop_pct": 0.03}}`,
  with `null` for `None`, `sort_keys=True` and `separators=(",", ":")`.

The grid is part of the configuration snapshot (§20). Changing it is a new configuration hash, and it can never be
re-examined on a consumed month (§13.5).

### 10.2 Time-exit parity

`(h_sel, None, None)` is SPG's hold-to-horizon label. Parity is asserted in two places.

- **Tests** (fixture with complete, valid paths, including a split): exact equality with
  `dataset.benchmarks[h_sel].benchmark_return` per session, with the bound decision's accepted WF set (same
  `(session_date, stock_id)`), and with `wf_mean_excess`, within `TG_PARITY_TOLERANCE = 1e-6` absolute. The
  tolerance covers `float32` path storage and SPG's `Numeric(12, 8)` rounding.
- **Every run**: a report-only `parity` block, persisted on `tg_decisions`, gates nothing. It holds:
  - benchmark sessions compared, and those differing by more than the tolerance
  - TG-only, SPG-only and common accepted trades over `T_σ`
  - TG `mean_excess` against the mean of the SPG accepted trades' `excess_return` over `T_σ`, and against
    `wf_mean_excess`
  - candidates whose SPG `label_status` and TG `path_status_{h_sel}` disagree, counted by TG status

  Differences are expected only from §7.3's stricter resolution, data changes since the bound run, and the `T_σ`
  exclusion. The review report must explain each one.

### 10.3 Reference geometry

`REFERENCE_GEOMETRY = (H = 3, target = o × 1.05, stop = o × 0.97)`: fixed percentages, not ATR. Its key is
`REF:H3:T0.05:S0.03`. It uses the same fill rules, reduction and statistics, and it has its own universe benchmark
`b_ref(D)`. It is reported in `tg_geometry_results` with `is_reference = true`, `eligible = false`. Its WF trades
are stored. It is never in the reality check or the choice.

## 11. Benchmark and objective

- `b_g(D)` = arithmetic mean of `r` under `g` over every row of `U(D)` with `path_status_H = RESOLVED`. Each row
  uses its own `atr` levels. This includes the candidate's own row, as in SPG.
- `(D, g)` is benchmarkable iff that resolved count ≥ `selection_min_benchmark_stocks` (100). This depends only on
  `(D, H)`.
- Per-session benchmarks depend only on `(D, g)`, so they are computed once per run and shared by both selectors.
- **Trade excess**: `e_j = r_j − c − b_g(D_j)`. The objective is `mean_excess = mean(e_j)` with its CI. The hit rate
  is never an objective.

Also reported, per geometry and stage:
- excess versus the universe held to `H`: `mean_excess_vs_hold = mean(r_j − c − b_(H,None,None)(D_j))`
- absolute net expectancy: `mean_net = mean(r_j − c)`
- `target_rate`, `stop_rate`, `expired_rate` (summing to 1) and `ambiguous_rate` (included in `stop_rate`). These
  are target-before-stop, stop-before-target and neither.
- `mean_mfe` and `mean_mae` (trade-level), `mean_mdd` (mean `MDD_H` of the accepted rows)
- `mean_time_to_target` (over `TARGET` trades) and `mean_time_to_stop` (over `STOP` trades), null when there are none
- independent trades `n`, and `bootstrap_sessions`
- `turnover = n / stage sessions`
- `cost_drag = c × n`

Whether positive absolute expectancy should also be required is open (§23).

## 12. Reduction

Per selector, geometry and stage, candidates are processed in `(i(D), rank, stock_id)` order. They are the stored
top-K (WF) or shadow `rank ≤ K` (HO), chosen before labels. Each stage starts with no open trades.

1. **Duplicates**: key `(σ, g, stock_id, i(D))`. The first by rank is kept and the rest are `DUPLICATE`. The
   sources are unique by construction (`uq_selection_gate_trade`, `uq_selection_shadow_score`), so this guards
   against repeats.
2. **Overlap**: per stock, with `X_a` the exit index of the last `ACCEPTED` trade, a candidate on `D` is
   `OVERLAP_SUPPRESSED` iff `i(D) < X_a`. The stock is busy until it exits. Suppressed candidates are not replaced.
   For `(H, None, None)`, `X_a = i(D_a) + H`, which is SPG's rule exactly.
3. **Unresolved**: a candidate whose `path_status_H ≠ RESOLVED` (including `NOT_IN_DATASET`), or whose `(D, g)` is
   not benchmarkable, is `UNRESOLVED`. It is counted, excluded from statistics, and does not make the stock busy.
4. The rest are `ACCEPTED`, and `n` = their count.

The overlap is causal: `X_a ≤ i(D)` means the earlier trade had exited by `D`'s close, when the signal is known.

Example for one stock, with candidates at `i(D)` = 100, 101, 102 and 105:
- Under `(5, 1.5, 1.0)`, the 100 trade hits the target at `t_x = 2`, so `X = 102`. 101 is suppressed (101 < 102),
  and 102 is accepted (entry at 103).
- Under `(5, None, None)`, `X = 105`. 101 and 102 are suppressed, and 105 is accepted.

## 13. Validation protocol

### 13.1 Windows

- **`M_TG`**: the latest calendar month `M` (IST session date) for which all of the following hold. If none
  exists, there is no exam this run.
  - `M` is earlier than the current IST month
  - `M ≥ tg_holdout_not_before`
  - every session `D` of `M` has `i(D) + max(tg_holding_horizons) ≤` the last index of `S`

  `M_TG` depends only on the session list, and is the same for both selectors. "Not yet consumed" in the brief is a
  check, not a filter. TG never falls back to an earlier month, just as SPG never does (§13.5).
- **`P_TG`**: `(i(first session of M_TG), i(last session of M_TG) + max(tg_holding_horizons))`, or none when there
  is no `M_TG`.
- **`T_σ`**: the distinct sessions of the bound WF candidates, minus every session `D` with
  `protected_rows([i(D)], max(tg_holding_horizons), P_TG)` true. One label-independent session set is shared by all
  37 geometries of `σ`.
  - This excludes every WF trade whose scheduled hold `[D, D+H]` reaches into the exam month, for every `H`.
    Using the scheduled hold rather than the realised exit keeps the exclusion label-independent.
  - Using `max H` for all geometries is stricter than the brief's per-trade rule, and gives the reality check one
    common session set.
  - Candidates excluded this way are counted (`wf_protected_excluded`) and not stored.
  - The SPG embargo and protected rules are already applied in the bound WF rows, and TG adds its own span.
- **Folds**: the calendar months of `T_σ`. Every one is a bound fold with status `TESTED`.

### 13.2 Walk-forward stage (per geometry, including the reference)

1. Build the stage's `AcceptedTrade(i(D), r_j, b_g(D_j))` list from the §12 reduction over `T_σ`.
2. Call `evaluate_stage(trades, unresolved_trades=u, folds_tested=f_g, horizon=H,
   thresholds=StageThresholds(min_trades=tg_min_wf_trades, min_net_excess=tg_min_net_excess,
   max_unresolved_share=tg_max_unresolved_share, min_folds=tg_min_wf_folds, cost=c,
   bootstrap_draws=tg_bootstrap_draws, bootstrap_seed=tg_bootstrap_seed))`. This gives `n`, the means, and the CI
   via `block_bootstrap_ci` (circular, `L = H`, `B = 10000`, seed 42), plus the first four reasons of §15.8 in
   SPG's order.
3. Fold statistics (§15.4). Add `UNSTABLE_ACROSS_FOLDS` if they fail.
4. Compute the §11 reported metrics.

### 13.3 Geometry selection (WF only)

1. **Eligible** iff not the reference and no reason, meaning all of:
   - `n ≥ 2000`
   - `folds_tested ≥ 24`
   - `unresolved_share ≤ 0.02`
   - `mean_excess ≥ 0.0050`
   - CI defined and `ci_low > 0`
   - `fold_positive_share ≥ 0.55`
   - `median_fold_excess > 0`
2. **Reality check** over the 36 grid geometries (§15.5). The selector proceeds only if `p ≤ 0.05`.
3. **Robust choice** among eligible geometries (§15.6). It is never the maximum mean.

The WF result is `PASS` iff a geometry is chosen. Otherwise the decision is `NO_GEOMETRY` with
`NO_ELIGIBLE_GEOMETRY` and/or `FAMILY_WISE_NOT_SIGNIFICANT`, the held-out stage does not run, and the month is not
consumed.

### 13.4 Freeze

For each selector with a chosen geometry, the `tg_holdout_usages` row is inserted and committed before pass 2 of
§7.2 starts. The row holds the label, geometry, `grid_sha256`, `config_sha256`, `dataset_sha256`,
`wf_selection_sha256` and `bound_spg_decision_id`. That commit is the freeze, and it also spends the exam. Pass 2
runs once, after every selector's freeze in the run. The final `tg_decisions` row must carry the same geometry and
hashes as its usage row (asserted in tests and on write).

### 13.5 Held-out exam (TG's own)

Checks run in this order. Each failure is a single-reason held-out result, and checks 1–3 never consume the month.

1. **Availability**: `M_TG` exists, and `holdout_window_registry` holds a row with
   `registry_version = 'SPG-HOLDOUT-001'`, label horizon `h<h_sel>`, label month `≥ M_TG`, and
   `naive_utc(registered_at) ≤ naive_utc(now)`. Otherwise `HOLDOUT_NOT_YET_AVAILABLE`.
2. **Single use**: the label `TG-001:SEL-001:hsel<h_sel>:<YYYY-MM>` already has a `tg_holdout_usages` row, so the
   result is `HOLDOUT_ALREADY_CONSUMED`.
3. **Shadow coverage**: a session `D` of `M_TG` is covered iff it has at least one §6.3 row, and every such row's
   `artefact_sha256` equals the `artefact_sha256` of a `selection_gate_decisions` row for `(SEL-001, h_sel)` with
   `decided_at ≤ scored_at`. That artefact was fitted before use.
   `missing_share = (sessions(M_TG) − covered) / sessions(M_TG)`. If `missing_share > tg_max_shadow_missing_share`
   (0.10), the result is `DATA_INTEGRITY_SHADOW_COVERAGE`.
4. **Consume and freeze** (§13.4). A uniqueness violation on insert gives `HOLDOUT_ALREADY_CONSUMED`.
5. **Pass 2**: paths and benchmarks for `M_TG` under the frozen geometry `g*` and `(H*, None, None)`.
6. **Score**: candidates are the covered sessions' shadow rows. Reduce (§12), then call `evaluate_stage` with
   `min_trades=tg_min_holdout_trades`, `min_folds=None`, `horizon=H*` and the other thresholds as in §13.2. `PASS`
   needs `n ≥ 100`, a defined CI, `mean_excess ≥ 0.0050`, `ci_low > 0` and `unresolved_share ≤ 0.02`.

Only `g*` is evaluated, which is one test per selector. `tg_holdout_windows` gets the month's row (idempotent by
label) at run start whenever `M_TG` exists, outside walk-forward-only mode.
- `window_start` = anchor of `M_TG`'s first session.
- `window_end` = `S[i(last session) + 7]`'s anchor.

### 13.6 Walk-forward-only mode

TG computes `M_TG`, `P_TG` and `T_σ` exactly as in §13.1, so its WF numbers equal a full run's. It reads no TG or
SPG registry rows and no shadow scores. It builds no pass 2 and writes no `tg_*` row. It reports only.

## 14. Multiple-testing and data-mining controls

- **Predeclared, small and hashed.** 36 geometries per selector, fixed before any TG run, with `grid_sha256` on
  every decision. There is no grid refinement, no data-dependent level, no per-stock or per-regime geometry, and no
  `K` or score-threshold search. SEL-001 is used exactly as SPG gates it.
- **Family-wise control on walk-forward.** White's Reality Check is a max-statistic block bootstrap over all 36
  geometries of the selector. It uses common session blocks, so the strong correlation between geometries is kept.
  A selector whose best geometry is not significant after the search stops at `FAMILY_WISE_NOT_SIGNIFICANT`.
- **The same thresholds as SPG.** The trade, fold, edge, CI and unresolved thresholds equal SPG's values, and none
  is tuned. Fold stability (`fold_positive_share ≥ 0.55`, median fold `> 0`) rejects edges carried by a few months.
- **No winner's-curse pick.** The choice maximises nothing. It takes the simplest geometry whose `ci_low` is within
  0.0010 of the best `ci_low` and never the maximum mean. Ties are broken by predeclared simplicity rules.
- **One held-out test per selector.** Only the frozen geometry is examined, so there is no multiplicity at held-out.
  The month is single-use per selector, whatever the configuration.
- **Selectors are separate products.** h=3 and h=5 each authorise only themselves, as SPG pairs do. Neither
  borrows the other's evidence, and both are reported.
- **The reference is never eligible.** The incumbent +5%/−3% cannot be rescued by the search. It is reported for
  comparison only.
- **WF reuse is acknowledged.** TG's walk-forward uses the same selections SPG already evaluated, and SEL-001
  reached TG partly because its SPG walk-forward was positive. TG's walk-forward therefore only ranks geometries.
  TG's held-out exam is the authoritative test.
- **No forking paths in the report.** Every run prints all 37 geometry rows per selector, with reasons, the tolerance
  set and the reality-check `p`, not just the chosen row.
- **No re-runs to fish.** `OPERATION_TRADE_GEOMETRY` is `NOT_REPEATABLE`. A re-run on the same month records
  `HOLDOUT_ALREADY_CONSUMED`. A code or configuration change after the freeze moves `tg_holdout_not_before` to the
  first full month after the new freeze (§22).

## 15. Statistics and decision rule (rule `TG-001`)

### 15.1 Per trade

For accepted trade `j` of geometry `g`:
- `r_j` gross exit return (§7.4)
- `b_j = b_g(D_j)`
- `net_j = r_j − c`
- `e_j = r_j − c − b_j`
- `e^hold_j = r_j − c − b_(H,None,None)(D_j)`

### 15.2 Stage aggregates

Over the `n` accepted trades:
- `mean_gross`, `mean_benchmark`, `cost = c`, `mean_net`, `mean_excess = (1/n) Σ e_j`, `mean_excess_vs_hold`
- the §11 rates and means
- `unresolved_share = u / (n + u)`, where `u` = `UNRESOLVED` count
- `turnover = n / |stage sessions|`, where stage sessions are `|T_σ|` for WF and `|sessions(M_TG)|` for HO
- `cost_drag = c × n`

### 15.3 Confidence interval

`block_bootstrap_ci(session_sums, session_counts, block_length=H, draws=tg_bootstrap_draws,
seed=tg_bootstrap_seed)`, through `evaluate_stage` (SPG-001 §11). The sessions are those with at least one accepted
trade, in order. It is undefined if `m < 2H`.

### 15.4 Folds

- For fold `f` (a calendar month of `T_σ`): `n_f` = accepted trades with `D` in `f`, and
  `mean_f = (1/n_f) Σ_{j∈f} e_j`.
- `folds_tested = #{f : n_f ≥ 1}`.
- `fold_positive_share = #{f : n_f ≥ 1, mean_f > 0} / folds_tested`.
- `median_fold_excess = numpy.median([mean_f : n_f ≥ 1])`.
- If `folds_tested = 0`, both are undefined and the fold gate fails.

### 15.5 Reality check

- Sessions: `T_σ` ascending, `m = |T_σ|`, `L = max(tg_holding_horizons) = 7`.
- Geometries: the `G` grid geometries with `n_g ≥ 1`. The reference is excluded.
- For each `g`: `sums_g[s] = Σ e_j` and `counts_g[s] = #j` over its accepted trades on session `s` (0 when there are
  none). Then `mean_g = Σ sums_g / Σ counts_g`, and `V = max_g mean_g`.
- **Draws**: `rng = numpy.random.default_rng(tg_bootstrap_seed)` (a fresh generator, not shared with any CI), then
  `starts = rng.integers(0, m, size=(B, ceil(m / L)))`. Draw `b` takes `L` consecutive sessions circularly from
  each start, truncated to `m`, exactly as `block_bootstrap_ci` builds its index. Processing is in chunks of 500
  draws.
- `mean*_{g,b} = Σ sums_g[idx_b] / Σ counts_g[idx_b]`, or `NaN` when the denominator is 0.
- `V*_b = nanmax_g (mean*_{g,b} − mean_g)`, or `−∞` if every value is `NaN`.
- `reality_check_p = #{b : V*_b ≥ V} / B`.
- It is undefined if `G = 0` or `m < 2L`.
- `FAMILY_WISE_NOT_SIGNIFICANT` iff `p` is undefined or `p > tg_reality_check_alpha` (0.05). It is computed whether
  or not any geometry is eligible.

### 15.6 Robust choice

- `best = max ci_low` over eligible geometries.
- The tolerance set is `C = {g eligible : ci_low_g ≥ best − tg_simplicity_tolerance}`.
- `g*` is the first element of `C` sorted by `(barriers(g), −fold_positive_share_g, −H_g, grid_index_g)`. That
  means fewest barriers, then higher fold stability, then the longer `H` (lower turnover), then grid order.
- No mean is part of the key.

### 15.7 Decision

- `GEOMETRY_PASS` iff the WF result is `PASS` (`g*` exists) and the HO result is `PASS`. Otherwise `NO_GEOMETRY`.
- The primary reason is the first failing reason in §15.8's stage order. Every failing reason is recorded as
  `{"stage", "reason"}`, and `NO_ELIGIBLE_GEOMETRY` adds `"detail"`, the count of geometries per first failing
  reason.
- The display string is `NO_GEOMETRY — <PRIMARY_REASON>`.
- A run that fails after start writes `NO_GEOMETRY / EVALUATION_FAILED` (best effort, `valid_until = decided_at`).

### 15.8 Reason codes and order

Geometry level, on `tg_geometry_results.reasons`, in order:
1. `DATA_INTEGRITY_UNRESOLVED_TRADES`: `unresolved_share > tg_max_unresolved_share`.
2. `INSUFFICIENT_OUT_OF_SAMPLE_EVIDENCE`: `n < tg_min_wf_trades`, CI undefined, or `folds_tested < tg_min_wf_folds`.
3. `EDGE_BELOW_THRESHOLD`: `mean_excess` undefined or `< tg_min_net_excess`.
4. `CONFIDENCE_THRESHOLD_NOT_MET`: CI undefined or `ci_low ≤ 0`.
5. `UNSTABLE_ACROSS_FOLDS`: `folds_tested = 0`, `fold_positive_share < tg_min_fold_positive_share`, or
   `median_fold_excess ≤ 0`.

Decision level, stage order none → `WALK_FORWARD` → `HELD_OUT`:
- no stage (`primary_stage` null):
  - `NO_BOUND_SELECTION_DECISION` (§6.2)
  - `EVALUATION_FAILED`: an exception after the run started
- `WALK_FORWARD`:
  - `NO_ELIGIBLE_GEOMETRY`: no grid geometry is eligible
  - `FAMILY_WISE_NOT_SIGNIFICANT` (§15.5)
- `HELD_OUT`, run only after a WF `PASS`:
  - `HOLDOUT_NOT_YET_AVAILABLE`, `HOLDOUT_ALREADY_CONSUMED`, `DATA_INTEGRITY_SHADOW_COVERAGE` (§13.5)
  - then, from `evaluate_stage`: `DATA_INTEGRITY_UNRESOLVED_TRADES`, `INSUFFICIENT_OUT_OF_SAMPLE_EVIDENCE`
    (`n < tg_min_holdout_trades` or CI undefined), `EDGE_BELOW_THRESHOLD`, `CONFIDENCE_THRESHOLD_NOT_MET`

The SPG-reused codes keep SPG's strings (`app/selection_gate/constants.py`). The TG-specific codes live in
`app/trade_geometry/constants.py`.

## 16. Schema (migration `0190_trade_geometry`, after `0189_selection_publish_gate`)

- File `migrations/versions/0190_trade_geometry.py`, with `revision = "0190_trade_geometry"` and
  `down_revision = "0189_selection_publish_gate"`.
- Models in `app/models.py`: `TradeGeometryDecision`, `TradeGeometryResult`, `TradeGeometryTrade`,
  `TradeGeometryHoldoutWindow`, `TradeGeometryHoldoutUsage`.
- All five are append-only. `app/trade_geometry/records.py` registers `before_update` and `before_delete` listeners
  that raise `TradeGeometryImmutableError`, the `selection_gate/records.py` pattern.
- Returns, shares and benchmarks are `Numeric(12, 8)`, prices `Numeric(18, 6)`, scores `Numeric(18, 10)`,
  multiples `Numeric(6, 3)`, `p` `Numeric(8, 6)`, and mean session counts `Numeric(8, 4)`.

### 16.1 `tg_decisions`

Identity and versions:
- `id`, `decision_uuid` (unique), `model_version` (`SEL-001`), `selector_horizon`
- `tg_rule_version` (`TG-001`), `dataset_version` (`TG-DS-001`), `base_dataset_version` (`SEL-DS-001`),
  `universe_rule_version` (`SEU-001`)
- `bound_spg_decision_id` (FK `selection_gate_decisions`, nullable)
- `grid_sha256`, `config_snapshot` (JSON), `config_sha256`, `code_version` (`selection_code_version()`)
- `dataset_sha256`, `ho_dataset_sha256`, `wf_selection_sha256`, `ho_selection_sha256`

Periods:
- `dataset_first_session`, `dataset_last_session`
- `wf_first_session`, `wf_last_session`, `wf_sessions`, `wf_protected_excluded`
- `holdout_month`, `holdout_first_session`, `holdout_last_session`, `holdout_label`, `holdout_window_id`
  (FK `tg_holdout_windows`), `holdout_usage_id` (FK `tg_holdout_usages`)

Chosen geometry, null when none:
- `holding_sessions`, `target_atr_multiple`, `stop_atr_multiple`, `geometry_key`, `grid_index`

Walk-forward selection:
- `wf_eligible_geometries`, `wf_tolerance_set` (JSON keys), `reality_check_p`, `reality_check_statistic` (`V`),
  `reality_check_geometries` (`G`)

Per stage, prefixed `wf_` (the chosen geometry) and `ho_`:
- reduction: `candidates`, `duplicates_removed`, `overlap_suppressed`, `unresolved_trades`, `unresolved_by_status`
  (JSON), `trades`, `min_trades`
- returns: `mean_gross`, `mean_benchmark`, `cost`, `mean_net`, `mean_excess`, `ci_low`, `ci_high`,
  `bootstrap_sessions`, `mean_excess_vs_hold`
- geometry: `target_rate`, `stop_rate`, `expired_rate`, `ambiguous_rate`, `mean_mfe`, `mean_mae`, `mean_mdd`,
  `mean_time_to_target`, `mean_time_to_stop`, `turnover`, `cost_drag`
- outcome: `result` (`PASS`/`FAIL`), `reasons` (JSON)
- `wf_` only: `folds_tested`, `fold_positive_share`, `median_fold_excess`, `folds` (JSON per month: `n`,
  `mean_excess`)
- `ho_` only: `sessions`, `shadow_sessions_covered`, `shadow_missing_share`, `shadow_artefacts` (JSON
  `artefact_sha256 → sessions`)

Outcome:
- `decision` (`GEOMETRY_PASS`/`NO_GEOMETRY`), `primary_stage`, `primary_reason`, `reasons` (JSON)
- `parity` (JSON, §10.2)
- `decided_at`, `valid_until` (`decided_at + tg_decision_validity_days`), `created_at`

Index `(model_version, selector_horizon, id)`.

### 16.2 `tg_geometry_results`

One row per decision × geometry: 36 grid rows plus the reference.
- `decision_id` (FK), `geometry_key`, `grid_index` (null for the reference), `is_reference`
- `holding_sessions`, `target_atr_multiple`, `stop_atr_multiple`, `target_pct`, `stop_pct` (reference only)
- the §16.1 per-stage reduction, returns and geometry fields, without prefix, plus `unresolved_share`, `folds_tested`, `fold_positive_share`,
  `median_fold_excess`, `folds` (JSON), `benchmarkable_sessions`
- `eligible`, `in_tolerance_set`, `chosen`, `reasons` (JSON, §15.8 geometry level)

Unique `(decision_id, geometry_key)`.

### 16.3 `tg_trades`

Per decision: WF and HO candidates of the chosen geometry, plus WF candidates of the reference. All dispositions
are stored.
- `decision_id` (FK), `geometry_key`, `geometry_role` (`CHOSEN`/`REFERENCE`), `stage`
- `source` (`SPG_WALK_FORWARD`/`SHADOW`), `source_row_id` (`selection_gate_trades.id` or
  `selection_shadow_scores.id`), `artefact_sha256` (HO only)
- `session_date`, `stock_id`, `selector_horizon`, `holding_sessions`, `rank`, `score`
- `entry_session`, `exit_session`, `entry_open`, `atr_percent`, `target_price`, `stop_price`, `exit_price_adjusted`,
  `path_status`
- `outcome`, `ambiguous`, `time_to_target`, `time_to_stop`, `mfe`, `mae`
- `gross_return`, `benchmark_return`, `hold_benchmark_return`, `cost`, `net_return`, `excess_return`
- `disposition` (`ACCEPTED`, `DUPLICATE`, `OVERLAP_SUPPRESSED`, `UNRESOLVED`)

Unique `(decision_id, geometry_role, stage, session_date, stock_id)`.

### 16.4 `tg_holdout_windows`

- `label` (unique, `TG-001:SEL-001:hsel<h>:<YYYY-MM>`), `model_version`, `selector_horizon`, `holdout_month`
- `window_start`, `window_end`, `registered_at`, `registry_version` (`TG-HOLDOUT-001`), `created_at`

### 16.5 `tg_holdout_usages`

- `holdout_label` (unique), `window_id` (FK), `model_version`, `selector_horizon`, `holdout_month`
- `bound_spg_decision_id`, `geometry_key`, `holding_sessions`, `target_atr_multiple`, `stop_atr_multiple`
- `grid_sha256`, `config_sha256`, `dataset_sha256`, `wf_selection_sha256`, `used_at`

## 17. Decision state machine

- **Per selector**: the latest decision is the `tg_decisions` row with the highest `id`. A later `NO_GEOMETRY`
  supersedes an earlier `GEOMETRY_PASS`. An `EVALUATION_FAILED` decision is valid for 0 days.
- **Per run and selector**:
  - `BOUND` (or `NO_BOUND_SELECTION_DECISION`, which ends the run for that selector)
  - → `WF_EVALUATED` (37 rows)
  - → `CHOSEN` (or `NO_GEOMETRY` with WF reasons, which ends it with the month untouched)
  - → `HO_CHECKED` (or a §13.5 1–3 failure, which ends it with the month untouched)
  - → `FROZEN` (usage committed)
  - → `HO_EVALUATED`
  - → `DECIDED` (one decision row with its results and trades, one transaction)
- **Per selector and month**: `UNREGISTERED` → `REGISTERED` (window row) → `CONSUMED` (usage row). A month never
  goes back. A consumed month is never examined again under any configuration.
- **Relation to SPG**: the TG state never alters an SPG decision. SPG `NO_EDGE` does not stop TG from evaluating.
  TG `GEOMETRY_PASS` with SPG `NO_EDGE` still publishes nothing.

## 18. Publication integration design (NOT activated)

Eventual contract for a SEL-001 call at selector `h_sel`. It is `PUBLISHED` iff all of these hold; anything else
is `SHADOW`.
1. `authorize_publication(SEL-001, h_sel, at, artefact_sha256)` is `PUBLISH`.
2. `publication_capability(SEL-001) == PUBLISHABLE`. This needs a reviewed code change and separate approval.
3. A future `authorize_geometry(session, *, model_version, selector_horizon, at, spg_decision_id) ->
   GeometryAuthorization(state, reason, decision_id, geometry)` is `GEOMETRY_PASS`.
4. The stock is in `U(D)` (`stock_in_gate_universe`).

The call's target, stop and horizon come from TG's frozen geometry, computed per stock from `atr` at `D` and the
`D+1` entry open `o`: `target = o × (1 + kT × atr)` and `stop = o × (1 − kS × atr)`, with horizon `H`.

The geometry check sits after the capability check. While SEL-001 is `SHADOW_ONLY` it is never reached, so wiring
it changes no outcome.

Future `authorize_geometry` checks, in order (the first failure returns `NO_GEOMETRY` with that reason):
1. A latest TG decision exists for `(SEL-001, h_sel)`, else `NO_GEOMETRY_ON_RECORD`.
2. Its `decision == GEOMETRY_PASS`, else its own `primary_reason`.
3. `tg_rule_version`, `dataset_version`, `grid_sha256` and `config_sha256` equal the running code's, else
   `GEOMETRY_DECISION_MISMATCH`.
4. `bound_spg_decision_id == spg_decision_id` (the decision that authorised step 1), else
   `GEOMETRY_LINEAGE_MISMATCH`. Between SPG's run on the 11th and TG's on the 12th, SEL-001 is therefore `SHADOW`.
   This is fail-closed.
5. `at ≤ valid_until`, else `GEOMETRY_DECISION_STALE`.
6. The stored numbers re-satisfy §15, else `GEOMETRY_DECISION_INVALID`.

Any exception gives `GEOMETRY_AUTHORIZATION_ERROR`.

These reason names are reserved. None of this is built in TG-001: `authorize_geometry`, a SEL-001 live publish path
(`record_recommendation` with `model_version = SEL-001` and the geometry), and the `decide_publication` change (a
future, separately approved SPG change). This phase only adds the tests of §21.8.

## 19. Operations

### 19.1 Script

`scripts/run_trade_geometry.py` (`python -m scripts.run_trade_geometry`). It mirrors `run_selection_gate.py`: an
`acquire_execution` claim on `OPERATION_TRADE_GEOMETRY`, `measured()` metrics, then a text and JSON report on stdout.

Flags:
- `--walk-forward-only`: §13.6, report only.
- `--source-url URL` (default env `SELECTION_SOURCE_DATABASE_URL`): a read-only source for market data and every
  SPG table, enforced by the database (`scripts/run_selection_gate.py::read_only_sessionmaker`, imported). The
  `tg_*` tables are always read and written through `DATABASE_URL`.
- `--ephemeral-schema`: creates the schema in a throwaway SQLite `DATABASE_URL`. The pre-merge recipe needs it for
  the execution claim, as in SPG.
- `--report-json PATH`, `--code-version SHA`.

`run_trade_geometry(session, *, read_session=None, walk_forward_only=False, now=None, code_version=None) -> dict`
lives in `app/trade_geometry/runner.py`.

### 19.2 Orchestration

- `app/schedule_orchestration.py`:
  - `OPERATION_TRADE_GEOMETRY = "TRADE_GEOMETRY"`
  - `LOCK_LEASE_OVERRIDES[OPERATION_TRADE_GEOMETRY] = 21600`
  - a `TriggerPolicy(trigger_type=TRIGGER_SCHEDULED, cadence=timedelta(days=28), requires_market_session=False)`
- `app/operation_recovery.py`: `RecoveryPolicy(repeatability=NOT_REPEATABLE,
  depends_on=(OPERATION_SELECTION_GATE,), grace=None)`. A repeat on the same month records
  `HOLDOUT_ALREADY_CONSUMED`, which as the newest decision would withdraw a `GEOMETRY_PASS`.

### 19.3 CronJob

`deploy/k8s/base/trade-geometry-cronjob.yaml`, added to the base kustomization:
- `market-agent-trade-geometry`, schedule `30 20 11 * *` (02:00 IST on the 12th, after SPG's run on the 11th),
  `timeZone: Etc/UTC`
- `concurrencyPolicy: Forbid`, `startingDeadlineSeconds: 3600`, `successfulJobsHistoryLimit: 3`,
  `failedJobsHistoryLimit: 3`
- `backoffLimit: 0`, `ttlSecondsAfterFinished: 172800`, `activeDeadlineSeconds: 21600`
- the `wait-for-db` initContainer and `DATABASE_URL` from `market-agent-secrets`
- command `["python", "-m", "scripts.run_trade_geometry"]`
- requests `cpu: 1, memory: 3Gi`; limits `cpu: 2, memory: 6Gi`

These are the SPG gate manifest's values. The SPG spec text says `ttlSecondsAfterFinished: 604800`, but the manifest
(172800) is authoritative. `tests/test_cronjob_manifests.py` ties `activeDeadlineSeconds` to the lease.

Manual run: `kubectl -n market-agent create job --from=cronjob/market-agent-trade-geometry trade-geometry-manual-<YYYYMMDD>`.

### 19.4 Runtime and memory (estimates)

Runtime:
- SEL-DS-001 build: about 15 min
- pass-1 paths: about 5 min. Pass 2 covers one month, under 1 min.
- universe benchmarks, 37 geometries × about 2.4M rows, vectorised per `H`: about 5 min
- candidates, 74 geometry-selector evaluations × about 21k candidates: about 1 min
- bootstraps, 74 × 10,000 plus two reality checks: about 10 min
- no model fitting
- total about 35–45 min

Memory:
- paths `2.4M × 7 × 4 × 4 B ≈ 270 MB`, beside the SEL-DS-001 frame
- peak about 3.5–4 GB, within the 6Gi limit

The run reports elapsed and CPU time, peak memory, rows and sessions.

## 20. Configuration (`app/settings.py`, all in the snapshot)

TG settings:
- `tg_selector_horizons = (3, 5)`
- `tg_holding_horizons = (1, 3, 5, 7)`
- `tg_target_atr_multiples = (None, 1.5, 3.0)`
- `tg_stop_atr_multiples = (None, 1.0, 2.0)`
- `tg_min_net_excess = 0.0050`
- `tg_min_wf_trades = 2000`
- `tg_min_holdout_trades = 100`
- `tg_min_wf_folds = 24`
- `tg_min_fold_positive_share = 0.55`
- `tg_reality_check_alpha = 0.05`
- `tg_simplicity_tolerance = 0.0010`
- `tg_max_unresolved_share = 0.02`
- `tg_max_shadow_missing_share = 0.10`
- `tg_holdout_not_before = "2026-11"`
- `tg_bootstrap_draws = 10000`
- `tg_bootstrap_seed = 42`
- `tg_decision_validity_days = 45`

Reused from SPG by value, copied into the TG snapshot:
- `round_trip_cost = selection_round_trip_cost` (0.0030)
- `top_k = selection_top_k` (10)
- `min_benchmark_stocks = selection_min_benchmark_stocks` (100)
- `min_plausible_return = selection_min_plausible_return` (−0.60)
- `max_plausible_return = selection_max_plausible_return` (1.50)

Constants (`app/trade_geometry/constants.py`), also in the snapshot:
- `TG_RULE_VERSION = "TG-001"`, `TG_DATASET_VERSION = "TG-DS-001"`, `TG_HOLDOUT_REGISTRY_VERSION = "TG-HOLDOUT-001"`
- `REFERENCE_GEOMETRY = {"holding_sessions": 3, "target_pct": 0.05, "stop_pct": 0.03}`
- `reality_check_block_length = max(tg_holding_horizons)` (7, derived)
- `TG_PARITY_TOLERANCE = 1e-6` (tests and report only)

Hashes:
- `config_snapshot()` also stores `grid_sha256` and `spg_config_sha256 = selection_gate.config.current_config_sha256()`.
  Any SPG setting the builder depends on is therefore part of TG's identity.
- `config_sha256 = selection_gate.config.config_sha256(snapshot)`: canonical sorted-key JSON, with `None` as `null`.
- The `tg_*` settings are not in SPG's `SNAPSHOT_SETTINGS`, so SPG's `config_sha256` is unchanged.

## 21. Tests (written first; SQLite in-memory, `Base.metadata.create_all`, `tests/_selection_gate_factories.py` helpers)

### 21.1 Fills (`test_trade_geometry_fills.py`)

- each §8 example exactly: target, stop, gap-through stop, gap above target, ambiguous → stop (flag set), expiry
- split inside the path (adjusted target hit; the unadjusted path would stop)
- missing bar → `PATH_BAR_MISSING` for `H ≥ 3` while `H = 1` resolves, and `UNRESOLVED` even when a barrier fired
  earlier
- `None` barriers skip their rules, and `(H, None, None)` always expires at `C_H`
- each path status, monotonicity in `H`, and `NOT_IN_DATASET`

### 21.2 Path statistics

`MFE_H`, `MAE_H`, `MDD_H` and `fwd_H` match §9.1. Trade `mfe`/`mae` match §9.2, including a gap exit. Time-to
values hold for each outcome.

### 21.3 Parity

On a fixture, `(h_sel, None, None)` equals `benchmarks[h_sel]`, the bound decision's accepted WF set and
`wf_mean_excess` within `TG_PARITY_TOLERANCE`. An inserted missing intermediate bar appears in
`parity.status_mismatches`.

### 21.4 Reduction

- overlap uses the exit index, and the §12 example holds under both geometries
- suppressed candidates are not replaced
- a duplicate counts once
- `UNRESOLVED` does not block a later candidate
- candidates are fixed before labels

### 21.5 Reality check

- deterministic for a fixed seed
- a null grid (zero-mean `e` for all 36) gives `p > 0.05`
- a planted edge in one geometry gives `p < 0.05`
- undefined when `m < 14` → `FAMILY_WISE_NOT_SIGNIFICANT`

### 21.6 Selection rule

- picks the simplest geometry within tolerance when a two-barrier geometry has the highest mean and the highest
  `ci_low`
- never picks the maximum mean when a simpler one is in tolerance
- each tie-break in order
- the fold-stability gate (`UNSTABLE_ACROSS_FOLDS`)
- the reference is never eligible or chosen

### 21.7 Holdout and independence

- `M_TG` never before `tg_holdout_not_before`, never the current month, and never an older month after consumption
- single use (`HOLDOUT_ALREADY_CONSUMED` on a re-run, and on a concurrent insert)
- the SPG registry guard (`HOLDOUT_NOT_YET_AVAILABLE` without an `SPG-HOLDOUT-001` row `≥ M_TG` at `h_sel`)
- checks 1–3 of §13.5 never write a usage row
- freeze before read: poisoning every bar of `P_TG` (extreme values) leaves all `tg_geometry_results` values, `g*`,
  `reality_check_p` and the usage row byte-identical, and the usage row is committed before the pass-2 query runs
- TG never reads SPG `HELD_OUT` rows: seeded `HELD_OUT` rows that would change results if read leave every output
  unchanged, and the captured SQL contains no `HELD_OUT` literal and no `ho_` / `holdout_` column of
  `selection_gate_decisions`
- every SPG table (`selection_gate_decisions`, `selection_gate_trades`, `selection_benchmark_sessions`,
  `selection_shadow_scores`, `selection_holdout_usages`, `holdout_window_registry`, `holdout_usage_records`) is
  byte-identical before and after a full TG run
- no `app/selection_gate` module imports `app.trade_geometry` and none references a `tg_` table (AST and source
  scan). An SPG run with populated `tg_*` tables gives the same decisions as one with empty tables.
- `WALK_FORWARD` rows within `P_TG` are excluded for every `H`

### 21.8 Publication

With a seeded TG `GEOMETRY_PASS` for both selectors, `decide_publication` results are identical for every model and
horizon pair in `selection_gate_pairs`, and SEL-001 still returns `SHADOW_ONLY_CAPABILITY` with a valid SPG
`PUBLISH`. `publication_capability("SEL-001") == "SHADOW_ONLY"`.

### 21.9 Shadow coverage

- more than 10% of sessions missing → `DATA_INTEGRITY_SHADOW_COVERAGE`
- an artefact not from an earlier SEL-001 decision makes its session uncovered
- exactly 10% passes

### 21.10 Runner and records

- `NO_BOUND_SELECTION_DECISION` cases (none, `EVALUATION_FAILED`, `top_k` mismatch)
- WF-only writes no `tg_*` row
- the decision equals its usage row's geometry and hashes
- updating or deleting any `tg_*` row raises

### 21.11 Migration and manifests

- `0190` upgrades and downgrades on SQLite, with a single alembic head
- the CronJob is in the kustomization with the §19.3 fields
- the lease, trigger and recovery policies are registered

## 22. Acceptance criteria and review report

Acceptance:
1. Every §21 test passes. The existing `test_selection_gate_*`, `test_cronjob_manifests`, walk-forward, challenger
   and holdout suites pass unchanged. `git diff main -- app/selection_gate` is empty.
2. A pre-merge `--walk-forward-only` run on a read-only production snapshot writes nothing to production (EPIC-843
   isolation: a separate read-only engine, an ephemeral store). It must complete for both selectors within 21,600 s
   and 6Gi. No held-out month exists yet under the purist rule.
3. The TG code and configuration are frozen at a commit SHA. `tg_holdout_not_before` is the first full month after
   that freeze. Any later change to TG code or configuration moves it forward to the first full month after the new
   freeze.
4. The first production run's WF numbers match the pre-merge run for the same `dataset_sha256` and
   `bound_spg_decision_id`. Any difference is reported.
5. No publication outcome changes. SEL-001 stays `SHADOW_ONLY`, and no new public SEL-001 call appears.

Review report (before merge and before deploy):
- **Formulas**: §7–§15 as implemented, citing file and line.
- **Data sources**: tables read, session range, per-year stock counts, `dataset_sha256`, the bound decisions, and
  `T_σ` sizes and exclusions.
- **Geometry table**: per selector, all 37 rows with every WF aggregate, reasons, eligibility, the tolerance set,
  `reality_check_p` and `g*`. Results are shown by horizon and by geometry, with the reference side by side.
- **Parity**: the §10.2 block per selector, with every difference explained.
- **Held-out** (when run): the full `ho_*` block and shadow coverage.
- **Tests**: names and results.

## 23. Open questions

For the user, kept open:
1. **ATR-scaled or fixed-percentage grid.** The recommendation is ATR, which is robust across stocks with different
   volatility. Fixed percentages would replace `kT`/`kS` with percentage tuples of the same size.
2. **Require positive absolute net expectancy in WF and HO?** The recommendation is report only (`mean_net`), because
   a down month can make a genuinely good selector negative in absolute terms. The user may prefer to require it,
   as an added reason after `CONFIDENCE_THRESHOLD_NOT_MET`.
3. **First held-out month.**
   - Purist (recommended): `tg_holdout_not_before = "2026-11"`, the first full month after the TG freeze. The first
     possible TG decision is then on 12 Dec 2026.
   - Faster: `"2026-09"`, not examined by anyone at design time. Its risk is that SPG's 11 Oct run examines
     September first.

Brief assumptions checked against SPG code (`e5812e5`), each resolved on the TG side with no SPG change:

4. **Time-exit parity is not exact in general.** SPG's label resolves with only the `D+1` and `D+h` bars
   (`dataset.py::_labels`), while TG requires every bar `D+1..D+H` and valid bar arithmetic. TG also stores paths in
   `float32`. Resolution: parity is exact, within 1e-6, on fixtures, and in production it is a reported block with
   enumerated mismatches (§10.2). TG keeps one resolution rule for all 36 geometries.
5. **"TG runs after SPG registered the month" holds only by schedule.** Nothing in the code enforces it.
   Resolution: the §13.5 availability guard reads `holdout_window_registry` (`SPG-HOLDOUT-001`, read-only) and
   returns `HOLDOUT_NOT_YET_AVAILABLE` until SPG has registered a month `≥ M_TG` at `h_sel`.
6. **Shadow `U(D)` can differ from TG's rebuilt `U(D)`.** The shadow job builds with `skip_failed_stocks=True` at
   08:35 IST. Resolution: such candidates are `NOT_IN_DATASET` and count as unresolved, within the 2% limit.
7. **Shadow scores within one month come from different artefacts.** The latest SEL-001 decision changes on the
   11th. Resolution: provenance is part of the coverage check, and `artefact_sha256` is recorded per trade and per
   decision.
8. **SEL-DS-001 has no high/low or intermediate bars.** Resolution: TG builds its own path arrays from
   `market_prices` with SPG's filters and adjustment interval (§7.2).
9. **SPG does persist every WF candidate, all dispositions** (`write_trades`), so this brief assumption holds. The
   exceptions are `EVALUATION_FAILED` decisions, which have no trade rows, and walk-forward-only runs, which write
   no decision. Both give `NO_BOUND_SELECTION_DECISION`.
