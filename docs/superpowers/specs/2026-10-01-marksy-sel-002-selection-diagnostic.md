# Marksy SEL-002 selection diagnostic: design

Status: draft for review, 2026-10-01 · Repo: marksy-api (code on `feat/sel-002-diagnostic`, never merged by this work) ·
marksy-os: this document and its plan only.

Binding inputs: the predeclared SEL-002 protocol (fixed before any SEL-002 number was computed) and the user's addendum,
which overrides the protocol where they differ. This spec restates both precisely. Every rule, bucket, regime, K, seed
and threshold below is frozen here and in `app/selection_diagnostics/config.py` and `evidence.py` before any SEL-002
run. None is changed after results are seen.

## 1. Goal and non-goals

**Question.** Does SEL-001 consistently rank stocks with higher future excess returns than transparent alternatives
and random selection, out of sample? Or is its walk-forward result an artefact of the training setup, feature set,
concentration, regime or chance?

**Answer format.** A diagnostic classification, A, B or C (§7), per the addendum. These classes are not
publication decisions, and SEL-002 adds no pass/fail gate.

- A, strong evidence of a ranking signal: the core signal evidence passes comprehensively at both horizons, with no
  major robustness red flag. Recommend TG-001; resuming it still needs the user's approval.
- B, meaningful core evidence, but the signal is incomplete or fragile. Investigate and revise SEL-001 before trade
  geometry.
- C, the core evidence does not demonstrate a useful ranking signal. Stop investing in the current SEL-001
  architecture and redesign the selection model, features or objective.

The 37 checks per horizon are descriptive. They are frozen before execution and never changed afterwards, and they
are never used to tune SEL-001 after the results are seen.

**Non-goals.** SEL-002 does none of the following:
- edit anything under `app/selection_gate/**` or `scripts/run_selection_gate.py`
- write to any database
- change any decision, threshold, publication state or the fail-closed gate
- deploy anything that changes prediction behaviour
- implement target, stop or horizon geometry
- tune SEL-001
- evaluate on the held-out month
- enter the API request path
- alter the shadow job
- change TG-001, which stays paused until SEL-002 is complete

## 2. Prior exploratory evidence (SEL-DIAG-001)

A read-only spike (`scratchpad/sel_diag/run_diag.py`, 2026-10-01) ran a smaller set:
- 10 rankers against SEL-001
- a 1,000-draw selection permutation
- deciles and IC
- regimes

It reproduced the SPG-001 pre-merge walk-forward numbers for SEL-001:
- h=3: 13,758 trades, mean net excess 0.0050488, CI [0.00368, 0.00643]
- h=5: 10,374 trades, mean net excess 0.0087478, CI [0.00657, 0.01094]

All of its predeclared checks were positive at both horizons. That is prior exploratory evidence only. SEL-002's
classification comes only from SEL-002's own runs. SEL-002 reuses the spike's cached dataset and parity-verified
walk-forward scores, and only after their hashes match the pinned values (§5.1).

## 3. Foundation reused (SPG-001, import-only)

Every name below was verified against marksy-api `main` at `8944bf4`. SEL-002 imports these and never edits them.

Dataset and labels:
- `app/selection_gate/dataset.py`:
  - `build_selection_dataset` (l.179)
  - `SelectionDataset` (l.50)
  - `FEATURE_COLUMNS` (l.41, = FV-002 `EXTENDED_FEATURE_COLUMNS`, 23 columns)
  - `_sha256` (l.161, the §7.5 SEL-DS-001 hash)
- `app/selection_gate/runner.py::_excess_target` (l.50): the target y = r − b(D,h) on RESOLVED rows of benchmarkable
  sessions, NaN elsewhere. SEL-002's realised excess x is this same series.

Walk-forward:
- `app/selection_gate/runner.py::_walk_forward` (l.65): the SEL-001 walk-forward that the parameterised loop (§6.5)
  replicates.
- `app/selection_gate/runner.py::_thresholds(STAGE_WALK_FORWARD)` (l.40): cost, bootstrap draws and seed for
  `run_stage`.
- `app/selection_gate/validation.py`:
  - `plan_windows` (l.40)
  - `fit_row_mask` (l.68)
  - `protected_rows` (l.60)
  - `quarter_of`, `month_text`
- `app/selection_gate/sel001.py`:
  - `fit_sel001` (l.10; winsorises the target to the training set's own 1st/99th percentiles)
  - `score_sel001` (l.17)
- `app/selection_gate/config.py`: `SEL001_PARAMS` (l.13), `config_sha256` (l.69)
- `app/purged_embargo_validation.py::DEFAULT_EMBARGO_DAYS` (= 2)

Evaluation and statistics:
- `app/selection_gate/evaluation.py::run_stage` (l.48), `StageRun`. Top-K is chosen per session before labels are
  read, followed by duplicate and overlap reduction, unresolved handling, cost and benchmark.
- `app/selection_gate/reduction.py::top_k_candidates` (l.25): ties broken by `stock_id` ascending.
- `app/selection_gate/statistics.py::block_bootstrap_ci` (l.62): the circular moving-block bootstrap.

Universe and run evidence:
- `app/selection_gate/universe.py::_LIQUIDITY_WINDOW` (= 20; the SEU-001 traded-value rule)
- `app/selection_gate/metrics.py::measured` (l.36): elapsed time, CPU and peak memory per stage.
- `app/selection_gate/code_version.py::source_digest`

Read-only access:
- `scripts/run_selection_gate.py::read_only_sessionmaker` (l.23). SQLite connections get `PRAGMA query_only = ON`;
  Postgres connections get `default_transaction_read_only=on`.

Rows and data:
- `app/market_data/bar_finality.py::final_bars_only` (official bars only)
- `app.models.MarketPrice`, `Stock.sector`

SEL-002 never imports any of the following; a source-guard test enforces it:
- the SPG writers: `holdout`, `records`, `shadow`, `publication`, `authorization`, `run_selection_gate`,
  `_evaluate_pair`
- `app.db`'s `SessionLocal`
- `save_artefact`

## 4. Held-out protection

**Exam start.** The held-out month is August 2026, and every later month is held out with it. The exam start is
`2026-08-01`, and the first session on or after it is 2026-08-03.

**No use for any choice.** Nothing from the held-out months is used to choose any of these: features, K, baselines,
model parameters, regimes, geometry or thresholds. SEL-002 does not read the held-out months for any purpose.

Mechanisms, each tested (§9):

1. **Rows dropped first.** `heldout.restrict` is the first operation after loading. It:
   - drops every row with `session_index ≥ i(2026-08-03)`
   - masks every label whose window reaches the exam start: `entry_open` when D+1 ≥ exam, `exit_close_h`,
     `gross_return_h` and status `MASKED_SEL002` when D+h ≥ exam
   - makes every such benchmark session non-benchmarkable

   `restrict` is idempotent. `assert_restricted` re-checks this at the start of every analysis, which refuses an
   unrestricted dataset.
2. **Calendar only.** The session calendar is kept: dates only, no prices, features or labels. `plan_windows` needs
   it to reproduce SPG-001's walk-forward windows exactly. `diagnostic_windows` refuses to run unless:
   - `plan_windows(dates, h, today=2026-10-01, first_test_month="2018-01")` gives held-out month 2026-08
   - the protected span starts at the exam start
   - no test month reaches the exam start
3. **Tested sessions.** Every tested session satisfies `i(D) + h < i(exam start)` (`check_tested`). The cached scores
   hold no value for a dropped row; the run refuses otherwise.
4. **Bounded liquidity read.** The only market-data read (§6.7) has `timestamp < anchor(exam start)`.
5. **No writes.** The diagnostic runs inside `guards.no_database_writes()`, which raises on every INSERT, UPDATE,
   DELETE, CREATE, DROP, ALTER, REPLACE, MERGE, TRUNCATE, VACUUM, REINDEX or ATTACH on any engine in the process.
   It therefore never writes to:
   - `HoldoutWindowRegistry`, `HoldoutUsageRecord` or `SelectionHoldoutUsage`
   - any `tg_holdout_*` table
   - any SPG-001 table
6. **No exam path.** SEL-002 has no code path that registers, consumes or scores an exam month.

The one held-out-touching computation is the SPG build's own identity hash. `build_selection_dataset` hashes the full
build before SEL-002 masks it, and SEL-002 compares that hash for equality only (§5.1); it reveals no outcome. In
the record run that hash is already in the cache.

## 5. Dataset and validation period

### 5.1 Source and pins

Source:
- The production snapshot `scratchpad/snapshot.db`: a read-only CSV export of `stocks`, `corporate_actions` and
  official `market_prices`, loaded by `scripts/load_selection_snapshot.py` (the SPG-001 pre-merge recipe).
- File SHA-256 `5f59b9bc929f5d0c3f8fcdada419d705f0e6e9a3c0e26efb72d79ae2f0579a8b`, checked before and after the run.

Cache (`scratchpad/sel_diag/`), each file pinned by SHA-256:
- `dataset.pkl`: `50035bae382cf525ece4643cfcf4cf876b9c61db3c4573110522ede73a6dc08e`. This is SEL-DS-001 from
  `build_selection_dataset(read_session, horizons=(3, 5))`, with labels reaching the exam masked before caching.
- `wf_h3.npz`: `cda9efc995c1a71b8ee4dfe340357c0194f759f6c6ad822240c255a1bb898615`
- `wf_h5.npz`: `916390230c695ed0af5a896340d6470ed5532b01094ffa37ed6c9a3b5e426204`. Both npz files hold SEL-001
  out-of-sample scores from `runner._walk_forward`.

Dataset identity:
- `dataset.sha256_by_horizon` must equal the SPG-001 pre-merge evaluation (code `87da414`):
  - h=3: `ab07215f9790f19cb2d5efaf3a269db1a83d107290fb2ec895fcdf2cbe887eb0`
  - h=5: `a2381ab89b873aa9e364fb03924b443d73bc68ca2a05542c66aab24db54c9b68`
- Any mismatch stops the run.
- SEL-002 also reports its own `diagnostic_sha256`: `dataset._sha256` over the restricted rows, which is exactly what
  SEL-002 read.

If any cache file fails its pin, the run stops; nothing falls back automatically. The operator may then rebuild
through the same function with `--rebuild`:
- `build_selection_dataset` runs on the read-only source.
- The same mask is applied.
- SEL-001 scores come from `runner._walk_forward`.
- The SPG dataset hashes are still required.

A rebuilt run reports `mode: REBUILD`.

### 5.2 Universe, labels, benchmark, cost

These are SPG-001's own (SPG spec §6–§8):
- **Universe.** SEU-001 `U(D)`: equity, not a rights entitlement, an official bar at D, ≥ 60 official bars, FV-001
  required features at D, record contracts pass, and a median 20-session close×volume ≥ ₹1 crore (10,000,000).
- **Label.** Entry at the official open of D+1; exit at the official close of D+h, corporate-action adjusted.
  `r = gross_return_h`.
- **Benchmark.** `b(D,h)` = the equal-weighted mean `r` over RESOLVED rows of `U(D)`. A session is benchmarkable
  with ≥ 100 of them.
- **Cost.** c = 0.0030 round trip. Horizons h ∈ {3, 5}.

Notation:
- x = r − b (realised excess, no cost)
- e = r − c − b (net excess)
- "Resolved rows" are rows where x is defined (`_excess_target` non-NaN).

### 5.3 Validation period

Walk-forward test months:
- `plan_windows(dates, h, today=2026-10-01, first_test_month="2018-01")` gives 2018-01..2026-07: 103 months, refitted
  quarterly (35 fits).
- Rows whose [D, D+h] touches the protected span (2026-08 through its exit sessions) are excluded.
- `T_h` = the tested sessions: 2,122 at h=3 and 2,120 at h=5, per SEL-DIAG-001.

The report prints the following per horizon:
- first and last tested session
- U(D) rows and resolved rows
- exclusions by reason
- distinct stocks per year, computed from the restricted rows

## 6. Analyses

All analyses use the restricted dataset, the sessions `T_h` and SEL-001's cached scores. Labels marked DECISION feed
§7; everything else is EXPLORATORY.

### 6.1 Strategy harness and K sensitivity (DECISION input: E2, E3, E4, E5)

`evaluate(scores, h, K)` = `run_stage(stage=WALK_FORWARD, …, scores, session_indices=T_h, top_k=K,
thresholds=runner._thresholds(WALK_FORWARD))`.

Per session, it takes the top K among every scored `U(D)` row, before labels are read; ties go by `stock_id`
ascending. Then:
- **Duplicate and overlap suppression.** A stock with an accepted hold `[D_a+1, D_a+h]` cannot be accepted again
  while `i(D) < i(D_a) + h`, and a suppressed candidate is not replaced.
- **Unresolved.** A candidate whose label is unresolved, or whose session is not benchmarkable, is UNRESOLVED and
  counted.
- **Accepted.** The rest are ACCEPTED, and every statistic uses exactly these.

SPG's stage `result`/`reasons` are discarded; SEL-002 reports no PASS/FAIL.

Per strategy and K ∈ {1, 5, 10, 25, 50}, the harness reports:
- accepted trades, unresolved trades, candidates, duplicates removed, overlap suppressed
- sessions with ≥ 1 trade
- mean gross, mean net (r − c), mean benchmark
- mean net excess (mean e) with its CI (`run_stage`'s, §8.1)
- hit rate (the share of trades with e > 0) and the share of trades with r − c < 0
- per-session summed e: median, p10 and p90; the share of the total from the top ⌈1%·m⌉ and ⌈5%·m⌉ sessions
  (undefined when the total ≤ 0)

Strategies are SEL-001 and every §6.4 baseline. K=1 is reported only.

### 6.2 Prediction buckets (EXPLORATORY; monotonicity feeds E1)

Percentile rank, per session over SEL-001's resolved rows:
- Order by score descending, ties by `stock_id` ascending. Position r = 0..n−1; p = 100·r/n.
- Buckets on p:
  - Top 1% [0,1), 1–5% [1,5), 5–10% [5,10)
  - [10,20), [20,30), …, [80,90)
  - Bottom 10% [90,100]

Per bucket:
- **Session means.** v_s = mean x of the bucket's rows in session s. The mean x is the mean of v_s over sessions with
  ≥ 1 row, and its CI is the session-level block bootstrap of v_s.
- **Pooled.** The pooled mean x and median x over rows.
- **Net.** Mean net excess = mean x − c.
- **Counts.** Rows, and sessions with ≥ 1 row.

**Monotonicity** = Spearman(ordinal, bucket mean x) over the 12 buckets, where ordinal is 1 for Bottom 10% up to 12
for Top 1%.

### 6.3 Ranking quality (DECISION input: E1, E4)

Per session, over resolved rows with a defined score:
- **IC.** Spearman correlation: Pearson of average ranks within the session. Undefined with < 3 rows or a constant
  side.
- **Pearson.** Score against x winsorised to that session's 1st/99th percentile (`numpy`/pandas linear). The score is
  not winsorised.
- **Spread.** Mean x of p < 10 minus mean x of p ≥ 90 (the top decile minus the bottom decile, by the §6.2 ranks).

Per strategy (SEL-001 and every baseline):
- the mean IC, with its CI from the session bootstrap of the IC series, and the median IC
- the share of sessions with IC > 0
- the mean Pearson with its CI
- the mean spread with its CI, and the share of sessions with spread > 0

### 6.4 Baselines (DECISION input: E3)

No parameter is fitted, and each score is a point-in-time FV-002 value at D. A higher score is selected, and a
NaN-score row is never selected.
- BASELINE-RANDOM: `default_rng(42).random(n_rows)`, one uniform score per restricted row in row order. Seeds 1..20
  give a distribution at every K, reported only.
- BASELINE-MOM-5: `return_5d`
- BASELINE-MOM-20: `return_20d`
- BASELINE-MEANREV: −`sma20_distance`
- BASELINE-RELATIVE: `rel_strength_market_20d`
- BASELINE-VOLADJ: `return_20d / realized_vol_20d`, NaN where the vol is ≤ 0 or missing
- BASELINE-001: `baseline_score`, the live heuristic on split-adjusted bars

**Paired comparison (K=10).** Let T* be the sessions where either strategy has an accepted trade, ascending. For
each session, take (sum e, count) for SEL-001 and for the baseline, zero where absent. The difference is
mean_e(SEL-001) − mean_e(baseline), trade-weighted. Its CI comes from one joint bootstrap (§8.2) over T*. Also
reported: the share of shared sessions where SEL-001's session mean is higher.

### 6.5 Feature ablation (EXPLORATORY)

Groups, which partition the 23 FV-002 columns:
- RET = return_1d, return_5d, return_20d, return_60d
- TREND = sma20_distance, sma50_distance, macd, macd_signal, macd_hist
- OSC = rsi_14
- VOL = realized_vol_20d, realized_vol_60d, atr_percent
- RANGE = high_60d_distance, low_60d_distance
- VOLUME = volume_ratio_20d, volume_ratio_rank
- XSEC = momentum_rank_20d, rel_strength_market_20d, rel_strength_sector_20d
- REGIME = regime_bullish, regime_bearish, regime_neutral

Variants, at each h, with features in FV-002 column order:
- PRICE-ONLY = RET ∪ TREND ∪ OSC ∪ VOL ∪ RANGE (15)
- VOLUME-ONLY = VOLUME (2)
- PRICE+VOLUME (17)
- LOGO-g = all 23 minus group g, for each of the 8 groups
- FULL = all 23, which is SEL-001 itself (the cached scores)

**The loop.** Every refit uses ONE parameterised walk-forward, `wf_loop.walk_forward(rows, dates, windows, h,
target, features)`. It is a line-for-line copy of `runner._walk_forward`'s SEL-001 path:
- the same calendar quarters, each fitted on `fit_row_mask(…, embargo=2, protected)` ∩ defined target
- `selection_min_fit_rows` (100,000); a smaller fit gives FOLD_TOO_SMALL
- `fit_sel001` with in-fit winsorisation and `SEL001_PARAMS` (`random_state` 42, `n_jobs` 2)
- the same non-protected test sessions

The only parameters are the feature subset and the training target.

Variant reporting:
- each variant: the K=10 harness summary, plus §6.3 ranking quality
- each LOGO-g: the incremental contribution of g
  - net excess = mean_e(FULL) − mean_e(LOGO-g), with the §8.2 joint CI
  - IC = the mean over sessions of IC_FULL − IC_LOGO-g, on sessions where both are defined, with the session CI

**Individual features.** Each of the 23 features is also a model-free ranker, sign_q × feature.
- sign_q is fixed per quarter q from the training sessions of that quarter's fit: the same `fit_row_mask` and
  defined-target rows, never test data.
- sign_q = −1 if the mean per-session Spearman IC(feature, y) over those sessions is < 0, else +1. That includes a
  zero or undefined IC, as for a constant feature.
- Each ranker reports K=10 `evaluate`, ranking quality, and its count of negative-sign folds.

In-sample importance is never used.

### 6.6 Nulls (DECISION input: E4)

**Selection null.**
- Universe: every scored `U(D)` row of `T_h`. Draws are made before labels are read, so unresolved picks stay
  picked and are dropped from the mean.
- Observed statistic: the pooled mean of (x − c) over SEL-001's top 10 per session, by the SPG ordering, with no
  reduction.
- Null: 1,000 draws. Each draws 10 rows per session uniformly without replacement, using `default_rng(42)` keys and
  the 10 smallest keys per session, and takes the same pooled mean.
- Reported: p = (1 + #{null ≥ observed}) / 1,001, the null mean, sd (ddof = 1), max and z. Every session must have ≥
  10 scored rows.

**Label-permutation null (refit).** For seeds 1..8 at each h:
- The training target y is permuted across stocks within each session, among its defined values. Sessions are
  processed in ascending order with one `default_rng(seed)`.
- Fits read whole sessions, so every fold's training rows are permuted within session. Features, sessions, folds,
  test rows, labels and the harness are unchanged.
- The model is refit with the §6.5 loop and the walk-forward months are scored.
- The run is evaluated by K=10 mean net excess and by mean IC (§6.3) against real labels.
- Reported: the 8 values, their mean, sd (ddof = 1) and max, and SEL-001's z.

### 6.7 Concentration (EXPLORATORY; DECISION input: E5)

The trades are SEL-001's K=10 accepted trades. Contribution = summed e per group, for each of seven dimensions:
- session
- stock
- month (YYYY-MM of D)
- year
- sector (current `Stock.sector`; NULL → UNKNOWN)
- liquidity bucket
- volatility bucket

The two bucket dimensions are within-session terciles of the row at D:
- **Liquidity.** The median 20-session close×volume, recomputed read-only from official bars before the exam
  (`liquidity.median_traded_value_20d`). The rule is SEU-001's: a rolling-20 median of raw close×volume, which is
  split-invariant. The run refuses if any U(D) row's recomputed value is below the floor.
- **Volatility.** The row's `atr_percent`.

**Tercile rule.** Ascending rank r = 0..n−1, ties by row order. LOW if 3r < n, MID if 3r < 2n, otherwise HIGH.

Reported per dimension:
- the number of groups
- the Herfindahl index Σ(|C_g| / Σ|C|)²
- the top 10 groups with their contribution, trade count and share (C_g / total, undefined when the total ≤ 0)
- the top-1% and top-5% shares, over ⌈1%·groups⌉ and ⌈5%·groups⌉ groups
- the largest share

Also reported:
- the mean e with its CI after removing the ⌈1%⌉ sessions with the largest contribution (ties by session ascending),
  and the same after removing every trade of the ⌈1%⌉ largest stocks. Each CI is a trade-weighted bootstrap over
  the remaining sessions.
- the largest single-sector share, excluding UNKNOWN (UNKNOWN's share is reported), and the largest single-year
  share

### 6.8 Regimes (EXPLORATORY; DECISION input: E6)

All defined at D or from b(D,h). Session-level, over `T_h`:
- **Market direction.** UP if b(D,h) > 0, DOWN if ≤ 0. NO_BENCHMARK sessions are not predeclared and hold no trades.
- **Volatility.** The tercile, over `T_h`, of the session's mean `atr_percent` across U(D).
- **Dispersion.** The tercile, over `T_h`, of the cross-sectional sd (ddof = 1) of `return_5d` across U(D).
- **FV-002 label.** BULLISH, BEARISH or NEUTRAL from the session's regime one-hot, which must be constant within the
  session. UNLABELLED is not predeclared.

Row-level:
- **Liquidity.** The §6.7 within-session tercile.

The 14 predeclared buckets are UP/DOWN, then LOW/MID/HIGH for each of volatility, dispersion and liquidity, then
BULLISH/BEARISH/NEUTRAL.

Per bucket:
- the K=10 mean e, with a trade-weighted CI over the bucket's sessions
- the mean IC with its CI: the session IC for session-level buckets, or IC over the bucket's rows for liquidity

## 7. Evidence rules and classification (DECISION)

The rules are frozen in `evidence.py` as `RULES_VERSION = "SEL-002-EVIDENCE-002"`, with the protocol's §9
thresholds used as classification evidence. Version 002 replaced 001 on 2026-10-01, at the user's review and
before any SEL-002 code existed or any SEL-002 number was computed. 001 required every one of the 37 checks for A; 002
splits them into core signal evidence and robustness evidence, so that one thin regime or one narrow diagnostic
cannot by itself make an otherwise compelling result B or C.

A check whose value is undefined (None) does not hold, except in E6, where an undefined CI cannot show a
significantly negative regime.

### 7.1 The 37 checks per horizon h ∈ {3, 5} (all reported)

**Core signal evidence (19).**
- **E1 Ranking (4).**
  - mean IC CI_low > 0
  - the share of sessions with IC > 0 is ≥ 0.55
  - bucket monotonicity is ≥ 0.8
  - the top-minus-bottom decile spread has CI_low > 0
- **E2 Top-K breadth (4).** Net-excess CI_low > 0 at K = 5, 10, 25 and 50. K=1 is reported only.
- **E3 Baselines (7).** At K=10, the paired difference of SEL-001 minus each §6.4 baseline has CI_low > 0, for
  RANDOM, MOM-5, MOM-20, MEANREV, RELATIVE, VOLADJ and BASELINE-001.
- **E4 Nulls (4).** All need all 8 permutation runs present:
  - K=10 net excess > the maximum of the 8 permutation runs
  - (K=10 net excess − their mean) ≥ 3 × their sd (ddof = 1)
  - mean IC > the maximum of the 8 permutation ICs
  - the selection-null p is ≤ 0.01

**Robustness evidence (18).**
- **E5 Concentration (4).**
  - net-excess CI_low > 0 without the top 1% of sessions
  - the same without the top 1% of stocks
  - the largest single-sector share is ≤ 0.40 (UNKNOWN excluded; its share is reported)
  - the largest single-year share is ≤ 0.40

  A share is undefined, and so fails, when the total net excess is ≤ 0.
- **E6 Regimes (14).** No predeclared bucket has a mean net excess with CI_high < 0.

### 7.2 Major robustness red flags (per horizon, K = 10)

A failed robustness check is a **minor flag** (reported, does not block A) unless it is one of these **major red
flags**:
- **F1 Session dependence.** Without the top 1% of sessions, the mean net excess is ≤ 0 or undefined: the edge
  disappears, not merely its CI.
- **F2 Stock dependence.** The same without the top 1% of stocks.
- **F3 Period dependence.** The largest single-year share is > 0.40 or undefined.
- **F4 Sector dependence.** The largest single-sector share (UNKNOWN excluded) is > 0.40 or undefined.
- **F5 Well-populated losing regime.** A regime bucket has CI_high < 0 and at least `MIN_REGIME_SESSIONS = 250`
  sessions with ≥ 1 accepted K=10 trade in that bucket. A bucket below 250 sessions with CI_high < 0 is a minor flag.

So a failed E5 CI check whose mean stays > 0, or a significantly negative thin regime, is reported but cannot block
A. F1–F5 are fixed here, before execution, like the checks.

### 7.3 Classification (addendum §2, revised at review)

- **A.** All 19 core checks hold at h=3 and at h=5, and no major red flag (F1–F5) exists at either horizon.
- **B.** Not A, but meaningful core evidence exists. That means, at either horizon, mean IC CI_low > 0 or K=10
  net-excess CI_low > 0.
- **C.** Otherwise.

The report maps each failing rule to what the evidence depends on:
- E1: deciles and ranking
- E2: K
- E3: simple baselines
- E4: null expectations
- E5 and F1–F4: period or a small subset of observations
- E6 and F5: regime

The SPG-001 decision for SEL-001 (`NO_EDGE`) is unaffected by any class.

## 8. Statistics

### 8.1 Session-level block bootstrap

This is SPG-001's `block_bootstrap_ci` unchanged:
- **Method.** A circular moving-block bootstrap over the ordered sessions that carry a value.
- **Parameters.** Block length L = h, B = 10,000 draws.
- **Draws.** One `default_rng(42).integers(0, m, (B, ⌈m/L⌉))` call per statistic.
- **Interval.** Percentile 2.5/97.5 (linear); undefined when m < 2L.

Stock rows of one session are never independent units.

Two forms:
- **Trade-weighted** (net excess): per-session sums and counts.
- **Equal-weight** (IC, Pearson, spread, bucket means, paired IC): one value per session, weight 1.

### 8.2 Paired joint bootstrap

`paired_block_bootstrap_ci(sums_a, counts_a, sums_b, counts_b)` uses SPG-001's draw mechanics exactly: the same rng
call and the same block construction. Each draw takes Σsum_a/Σcount_a − Σsum_b/Σcount_b over the same drawn
sessions, and the CI is undefined if any draw has zero trades on either side. Against a zero strategy it equals
`block_bootstrap_ci` bit for bit, which is tested.

### 8.3 Other conventions

- Quantiles are `numpy.percentile`/pandas linear.
- Spearman uses average ranks; correlation is undefined when n < 3 or a side is constant.
- sd uses ddof = 1. Top fractions are ⌈f·n⌉.
- Ties follow SPG order: score descending, `stock_id` ascending.
- Every seed is fixed in config: bootstrap 42, random 42 and 1..20, permutations 1..8, selection null 42, and
  SEL-001's `random_state` 42.

## 9. Tests (written first)

The tests are in marksy-api `tests/test_sel002_*.py`, with helpers in `tests/_sel002_factories.py` (a synthetic
SEL-DS-001 world: 300 sessions × 40 stocks with a planted signal, held-out month 2026-01) and `tests/_sel002_world.py`.
The test databases are SQLite. That makes 99 tests in 17 files, about 2 minutes.

Addendum §4 requirements:
- **Point-in-time integrity:**
  - `test_traded_value_is_seu_001_rolling_median_and_never_reads_past_the_bound`
  - `test_session_regimes_use_features_at_d_and_the_benchmark_sign`
  - `test_fits_respect_the_embargo_and_the_protected_span`
  - `test_feature_sign_comes_from_training_sessions_only`
  - `test_baselines_never_read_labels`
- **No held-out contamination:**
  - `test_no_held_out_value_can_change_the_restricted_data`
  - `test_held_out_values_cannot_change_any_result` (end to end through every analysis)
  - `test_labels_and_benchmarks_reaching_the_exam_are_masked`
  - `test_windows_must_match_the_frozen_exam_month`
  - `test_a_tested_session_reaching_the_exam_is_refused`
  - `test_an_unrestricted_dataset_is_refused`
- **Deterministic random baseline:** `test_random_baseline_is_deterministic_per_seed`
- **Deterministic permutation seeds:**
  - `test_permutation_is_deterministic_per_seed_and_stays_within_each_session`
  - `test_jobs_are_deterministic_resumable_and_reproduce_spg`
  - `test_parallel_workers_give_the_same_scores_as_one_process`
- **Identical universe across strategies:** `test_every_strategy_sees_the_same_universe`
- **Identical labels and costs:** `test_labels_and_costs_are_identical_across_strategies`
- **K selection before labels:**
  - `test_k_selection_happens_before_labels_are_read`
  - `test_selection_happens_before_labels_so_unresolved_picks_stay_picked`
- **No inflation from duplicates or overlaps:** `test_duplicates_and_overlapping_holds_cannot_inflate_the_sample`
- **Session-level bootstrap:**
  - `test_the_session_is_the_unit_not_the_row`
  - `test_the_session_is_the_bootstrap_unit`
  - `test_paired_ci_against_a_zero_strategy_equals_spg_001_on_the_same_blocks`
- **SPG-001 decisions cannot change:** `test_the_whole_pipeline_leaves_spg_tables_and_the_source_bytes_identical`.
  SPG-001 first writes decisions, trades, benchmarks, registry and usage rows; then the full SEL-002 pipeline runs on
  that database. The digests of every SPG and holdout table, and the file SHA-256, must be identical.
- **The held-out registry cannot be consumed:**
  - `test_registry_and_gate_tables_cannot_be_written_inside_the_guard`
  - `test_sel002_imports_no_spg_writer_and_no_write_session`
  - the end-to-end digest above

Protocol §10 requirements:
- **Buckets and IC/Pearson:** `test_sel002_ranking.py`
- **Concentration:** `test_sel002_concentration.py`
- **Regime assignment:** `test_sel002_regimes.py`
- **Permutation within session:** `test_sel002_strategies.py`
- **WF-loop parity on a synthetic world:** `test_loop_reproduces_spg_walk_forward_exactly`, an exact equality of
  scores, folds and tested sessions with `runner._walk_forward`

Also:
- evidence rules: pinned thresholds, the A/B/C matrix and undefined values (`test_sel002_evidence.py`)
- the parity gate's two modes (`test_sel002_refits.py`)
- the report's sections, exactly the addendum list (`test_sel002_report.py`)
- the frozen CLI refusing drifted settings or an unpinned source (`test_sel002_script.py`)
- SEL-002 being on no API path and in no schedule (`test_nothing_outside_sel002_imports_it_and_nothing_schedules_it`)
- the manual Job manifest (`test_sel002_manifest.py`)

## 10. Operations

### 10.1 Offline evaluation environment (the record run)

The record run uses the local read-only production snapshot. This is the SPG-001 pre-merge recipe: a CSV export
under `BEGIN TRANSACTION READ ONLY`, loaded into SQLite. It runs from the worktree `C:\AIAgent\marksy-api-sel002`, on
`feat/sel-002-diagnostic` at a frozen commit with a clean tree.

- **Throwaway app database.** `DATABASE_URL` points at `scratchpad/sel002/throwaway-app.db`, because importing `app`
  needs a URL. SEL-002 never connects to it.
- **Source.** `--source-url sqlite:///…/scratchpad/snapshot.db`, opened through `read_only_sessionmaker`.
- **Pins.** Every pin in §5.1 is enforced whenever the frozen configuration is used, and the CLI has no flag that
  disables a pin.
- **Outputs.** Written only to `scratchpad/sel002/`:
  - `prepare.json`, `prepared.pkl`, `refit_inputs_h{3,5}.pkl`
  - `refits/*.npz|json`, `refits.json`
  - `parity.json`
  - `results_h{3,5}.json`, `results.json`
  - `report.md`
  - logs
- **Production.** SEL-002 never connects to production.

Stages, each resumable:
1. `prepare`: verify the pins, restrict, build the windows, load the cached SEL-001 scores, run the liquidity read,
   hash the restricted frame.
2. `refit`: 40 jobs in a process pool (`--workers 5`, spawn). Parity jobs go first, and each job writes its scores
   atomically.
3. `parity`: the gate below.
4. `analyze --h 3` and `analyze --h 5`. They can run concurrently, and they refuse unless parity passed.
5. `report`.

**Parity gate (protocol §0).** Per h, the loop's FULL refit must match the cached SEL-001 scores in one of two ways:
- **Scores mode.** The tested sessions are identical and max |Δ| ≤ 1e-6 over the tested rows.
- **Top-K mode.** Failing that, the K=10 candidate list (session, rank, stock, disposition), the trade count and
  mean_excess are identical.

Otherwise the run stops.

### 10.2 Kubernetes Job: dropped

Dropped at the user's review (2026-10-01). It would need about 28 h of the VPS's 2 CPUs, and the local frozen-cache
run is the classification of record.

### 10.3 Deployment boundary

- SEL-002 is never added to the API request path or to `schedule_orchestration`, and the shadow job is untouched.
- Before any deployment that this diagnostic informs, the diagnostic must have run against a read-only production
  snapshot in a throwaway database, as SPG-001 was evaluated.
- This work ends at the report: no merge, push or deploy.

## 11. Report structure

`report.md` is one document with exactly these sections, in this order (the addendum's list):

1. **Executive conclusion.**
   - the opening sentence, verbatim with the values filled: "SEL-002 produced Grade <A|B|C> under the criteria
     frozen before execution (`SEL-002-EVIDENCE-002`, evidence.py SHA-256 <hash>)."
   - the class, label and recommendation
   - the statement that this is a diagnostic classification, not a publication decision
   - the positive-evidence flags per h, and each rule E1–E6 holding or failing per h
   - the dependencies of a B result
   - all 37 checks per h, each with its value and threshold, grouped core then robustness
   - the major red flags F1–F5 and the minor flags per h
2. **Dataset and validation period.**
   - source and hashes
   - sessions used and masking counts
   - universe rows and stocks per year
   - per h: test months, tested sessions with their first and last, quarters, protected start, U(D) and resolved
     rows, exclusions
3. **SEL-001 K sensitivity.** Every §6.1 field for K = 1, 5, 10, 25, 50 at each h.
4. **Prediction-decile analysis.** The 12 buckets with means, CIs, medians and counts, plus monotonicity.
5. **Simple-baseline comparison.**
   - every baseline at every K
   - the K=10 paired differences
   - the random-seed distribution
6. **Feature ablation.**
   - the variants, with LOGO incremental contributions and their CIs
   - the 23 single-feature rankers
7. **Null and permutation results.** The selection null, and the 8 permutation runs with their summary.
8. **Concentration.** All §6.7 outputs.
9. **Regimes.** All predeclared buckets, plus any non-predeclared bucket marked as such.
10. **Ranking metrics.** §6.3 for SEL-001 and every baseline.
11. **Limitations and possible confounders.**
    - current sector mapping
    - survivorship
    - offline vs live BASELINE-001
    - ex-post regime cut points and market direction
    - what the permutation null preserves
    - multiple exploratory numbers
    - the flat cost
    - overlap suppression at K=50
    - walk-forward months only
12. **Whether the evidence supports proceeding to TG-001.** Fixed text per class.
13. **Reproducibility.**
    - code version (HEAD, and -dirty if the tree is dirty) and source digest
    - SPG-001 frozen at `8944bf4`, with unchanged = true/false
    - dataset hashes, cache file hashes and diagnostic hashes
    - the snapshot SHA-256 before and after
    - the configuration SHA-256 and the full snapshot
    - the rules version and the SHA-256 of `evidence.py`
    - the seeds
    - the validation windows
    - the universe definition
    - the cost assumption
    - library versions and the worker count
    - per-stage elapsed time, CPU and peak memory (`measured()`)

`results.json` carries every number in the report.

## 12. Reproducibility

- **Configuration.** `DiagnosticConfig` is frozen. `cfg.sha256()` is `config_sha256` over its snapshot: the fields
  plus `SEL001_PARAMS`, the feature list, groups, variants, regime buckets, the frozen SPG settings and the embargo.
  `check_settings` refuses to run if any SPG-001 setting SEL-002 relies on differs:
  - top_k 10, cost 0.0030, B 10,000, seed 42
  - min session stocks 100, history 60, floor 10,000,000, benchmark stocks 100
  - plausible returns [−0.60, 1.50]
  - first test month 2018-01, min fit rows 100,000
  - embargo 2
- **Pins.** The pins of §5.1, the snapshot SHA-256 before and after, and the dataset and diagnostic hashes.
- **Determinism.** Refit jobs are deterministic for a fixed seed and `n_jobs = 2`. Results do not depend on the
  worker count, which is tested. Resuming skips a job only when its `job_sha256` matches; that hash covers the job,
  the config SHA and the inputs SHA.
- **Code.** HEAD is recorded, a dirty tree is labelled, and `git diff 8944bf4 -- app/selection_gate
  scripts/run_selection_gate.py` must be empty.

## 13. Compute

Refit runs:
- 40 walk-forward refits, 20 per horizon:
  - 1 FULL parity run
  - 3 group variants (PRICE-ONLY, VOLUME-ONLY, PRICE+VOLUME)
  - 8 LOGO runs
  - 8 label permutations
- Each has 35 quarterly XGBoost fits on up to about 1.9M rows, so 1,400 fits in all.

Baseline cost:
- SEL-DIAG-001 measured one FULL refit at 2,322–2,329 s wall and 4,542–4,560 s CPU, with 2 threads and no
  contention, at a peak of 1.6 GB.
- The SEL-002 workers load a slim input (row keys, 23 float32 features and the target; about 280 MB), so each peaks
  at roughly 1 GB.

The record run uses 5 parallel refits (`--workers 5`, the CLI default), since only about 7 GB of RAM was free.
The worker count changes only the wall clock, never a result. For reference, with 7 parallel refits (14 xgboost
threads; i7-11850H, 8 cores/16 threads):
- **Per run.** About 55–65 min each, from hyperthread sharing and all-core clocks. The variants average about 0.87 of
  FULL's cost by feature count.
- **Refit stage.** 40 runs in 6 waves, about 5 h (4.5–6 h). That is about 158,000 CPU-s, or 44 CPU-hours.
- **Other stages:**
  - `prepare`: about 10–15 min (pickle load, hashes, liquidity read of about 4.5M bars, about 2 × 300 MB inputs)
  - `parity`: under 1 min
  - `analyze`: about 25–30 min per horizon, run concurrently
  - `report`: under 1 min
- **End to end:** about 6–6.5 h with 7 workers; about 7 h with the record run's 5.

Memory and fallbacks:
- **RAM.** About 8 GB for 7 workers plus the parent, and about 3 GB per analyze process. At least 10 GB of free RAM
  is needed; otherwise use `--workers 5`, which changes only the wall clock.

## 14. Decisions (user review, 2026-10-01)

1. **UNKNOWN sector.** Excluded from the single-sector check and flag F4; its share is reported, and listed as a
   limitation if material.
2. **Permutation scope.** One within-session permutation per seed, shared by every fold.
3. **Selection-null population.** Every scored U(D) row, the pool SEL-001 actually selects from.
4. **Ex-post regimes.** Fixed whole-period cut points and the realised b(D,h), descriptive and after the fact only.
5. **Held-out rows in the cache.** Dropped on load; a test proves none survive.
6. **Kubernetes Job.** Dropped (§10.2).
7. **Grading.** Core versus robustness evidence with major red flags (§7, `SEL-002-EVIDENCE-002`).
8. **After the run.** TG-001 is not resumed automatically, even on an A; the user decides from the report.
