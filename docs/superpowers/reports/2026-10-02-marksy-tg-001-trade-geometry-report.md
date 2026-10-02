# Marksy TG-001 trade geometry — pre-merge walk-forward report

Date: 2026-10-02 · Rule TG-001 · Spec `docs/superpowers/specs/2026-10-01-marksy-tg-001-trade-geometry-design.md` (amended A1, §24) · Code marksy-api `feat/tg-001-trade-geometry` at **73f6d6a** (frozen, not pushed) · Mode: walk-forward-only, `--data-through 2026-07-31`.

## 1. Outcome

**Both selectors pass the walk-forward stage. Each chooses a pure time exit. The official TG-001 decision is not yet `GEOMETRY_PASS`, because TG's held-out exam has not run.**

The h3 selector:
- chose g* = `H5:Tnone:Snone` (hold 5 sessions, no target, no stop)
- walk-forward mean net excess was **+0.75%**, CI [+0.55%, +0.94%], over n = 11,577 trades
- 9 of 36 geometries were eligible; the reality check gives p < 0.0001

The h5 selector:
- chose g* = `H7:Tnone:Snone` (hold 7 sessions, no target, no stop)
- walk-forward mean net excess was **+1.00%**, CI [+0.73%, +1.27%], over n = 9,165 trades
- 10 of 36 geometries were eligible; the reality check gives p < 0.0001

Walk-forward-only mode registers, consumes and scores no exam month, and writes no `tg_*` row (spec §13.6).
- Under `tg_holdout_not_before = "2026-11"`, the first TG exam is November 2026, scored on 12 Dec 2026.
- `GEOMETRY_PASS` needs that exam to pass (§15.7).
- SEL-001 stays `SHADOW_ONLY`, no publication outcome changes, and SPG's decisions are untouched.

The chosen geometries are the simplest rows within 0.10% of the best `ci_low` (§15.6). They are not the highest-mean rows:
- **h3:** the tolerance set holds five rows. Two have zero barriers, H5 and H7. H5 wins on fold stability, 0.825 against H7's 0.767.
- **h5:** H5 and H7 tie on zero barriers. H7 wins on fold stability, 0.816 against H5's 0.806.

Read with §14's caveat: TG's walk-forward reuses the selections SPG already evaluated, so it only ranks geometries. The held-out exam is the authoritative test.

## 2. What the walk-forward shows

1. **Barriers never help.** For every holding period, adding an ATR target or stop lowers mean excess relative to the pure time exit at the same H. For example, at h3 with H5: none/none +0.75%, stop 1.0 ATR +0.47%, target 1.5 ATR +0.39%, both +0.22%.
   - Targets cap the right tail, which is where the edge lives (§5).
   - Stops are hit on noise, in a set where about 70% of the trades are high-ATR stocks.
   - 27 of 36 h3 geometries and 26 of 36 h5 geometries fail first on `EDGE_BELOW_THRESHOLD`.
2. **Longer holds are better, and one session is worse than nothing.** Every H=1 row is negative, from −0.06% to −0.24%. H3 sits near the threshold, at +0.52% for h3 and +0.49% for h5. H5 and H7 clear it.
3. **The incumbent +5%/−3% reference loses.**
   - Mean excess is −0.13% (h3) and −0.20% (h5), with CI wholly below 0.
   - About 53% of trades stop and about 30% hit target.
   - The hit rate is 38–39%.
   - This matches the 2026-09-30 production diagnosis.
4. **Time-exit parity with SPG and SEL-002.**
   - TG's `H3:Tnone:Snone` row at h3 is +0.52% [+0.38%, +0.66%].
   - SEL-002's K=10 hold-to-3 was +0.505% [+0.368%, +0.643%].
   - The parity block (§7) accounts for the remaining difference.

## 3. Diagnostic views (report-only, A1 §24.3)

These views never feed eligibility, the reality check, the choice or the decision.
- **Full detail.** Every one of the 74 geometry rows carries its full diagnostics, in `tg-eval-report.json` and in the text report.
- **Headline figures.** The figures below are for the two chosen geometries. Appendix B adds each selector's time exits and the reference.

### 3.1 Slippage sensitivity

The decision uses c = 0.30%. Each other cost shifts mean and CI exactly by −(c′ − c).

h3 · H5 (chosen):

| cost | mean excess | CI |
|---|---|---|
| 0.30% | +0.75% | [+0.55%, +0.94%] |
| 0.50% | +0.55% | [+0.35%, +0.74%] |
| 0.75% | +0.30% | [+0.10%, +0.49%] |
| 1.00% | +0.05% | [−0.15%, +0.24%] |

Break-even costs:
- mean excess reaches 0 at 1.05%
- mean excess falls to 0.50% at 0.55%
- CI low reaches 0 at 0.85%

h5 · H7 (chosen):

| cost | mean excess | CI |
|---|---|---|
| 0.30% | +1.00% | [+0.73%, +1.27%] |
| 0.50% | +0.80% | [+0.53%, +1.07%] |
| 0.75% | +0.55% | [+0.28%, +0.82%] |
| 1.00% | +0.30% | [+0.03%, +0.57%] |

Break-even costs:
- mean excess reaches 0 at 1.30%
- mean excess falls to 0.50% at 0.80%
- CI low reaches 0 at 1.03%

For contrast, holding the h3 selector only 3 sessions (+0.52%) drops below the 0.50% threshold at any cost above 0.32%. Most of the slippage headroom comes from the longer hold.

### 3.2 Up/flat/down markets

Each session is assigned to a walk-forward tercile of the universe's hold-to-H return.
- h3 · H5 cut points: −1.00% and +1.43%
- h5 · H7 cut points: −1.14% and +1.89%

h3 · H5:

| market | mean excess | CI | share of total |
|---|---|---|---|
| DOWN | +0.02% | [−0.27%, +0.30%] | 1% |
| FLAT | +0.67% | [+0.39%, +0.95%] | 30% |
| UP | +1.54% | [+1.16%, +1.93%] | 70% |

h5 · H7:

| market | mean excess | CI | share of total |
|---|---|---|---|
| DOWN | −0.26% | [−0.62%, +0.11%] | −8% |
| FLAT | +0.94% | [+0.53%, +1.34%] | 31% |
| UP | +2.26% | [+1.77%, +2.77%] | 78% |

As SEL-002 found, there is no edge in down markets. FLAT is clearly positive, so the edge is not purely an up-market effect.

### 3.3 ATR attribution

Stocks are split into within-session ATR terciles over U(D).

h3 · H5:

| ATR tercile | trades | mean excess | CI | share of total |
|---|---|---|---|---|
| HIGH | 7,901 | +0.80% | [+0.55%, +1.04%] | 73% |
| MID | 1,935 | +0.85% | [+0.47%, +1.25%] | 19% |
| LOW | 1,741 | +0.41% | [+0.09%, +0.72%] | 8% |

h5 · H7:

| ATR tercile | trades | mean excess | CI | share of total |
|---|---|---|---|---|
| HIGH | 5,971 | +1.06% | [+0.70%, +1.40%] | 69% |
| MID | 1,795 | +1.11% | [+0.59%, +1.63%] | 22% |
| LOW | 1,399 | +0.62% | [+0.17%, +1.07%] | 10% |

SEL-001 mostly picks high-ATR stocks, which is why HIGH dominates the total. Per trade, the edge is similar in MID and positive in LOW. It is not a pure high-volatility effect.

### 3.4 Return distribution

| | h3 · H5 | h5 · H7 |
|---|---|---|
| hit rate (e > 0) | 0.494 | 0.491 |
| trades losing after cost | 50.0% | 49.8% |
| median trade e | −0.07% | −0.16% |
| p1 / p99 | −19.1% / +25.3% | −21.5% / +32.8% |
| p10 / p90 | −8.1% / +10.7% | −9.4% / +12.9% |

The edge comes from a right-skewed tail, not from a high hit rate. Expect half the trades to lose.

### 3.5 Session concentration

| | h3 · H5 | h5 · H7 |
|---|---|---|
| share of total from top 1% of sessions (22) | 24% | 24% |
| share of total from top 5% of sessions | 76% | 76% |
| largest single session | 1.7% | 1.6% |
| Herfindahl | 0.001 | 0.001 |
| mean excess without the top 1% of sessions | +0.57% [+0.40%, +0.75%] | +0.77% [+0.52%, +1.02%] |

The edge survives removing its best 1% of sessions, still above 0.50% with CI above 0. Its spread across sessions is wide.

## 4. Evidence boundary (2026-07-31): proof

- **Source.**
  - SEL-002's `snapshot.db`, with SHA-256 `5f59b9bc929f5d0c3f8fcdada419d705f0e6e9a3c0e26efb72d79ae2f0579a8b`. This was checked equal to the SEL-002 report before use, and the file was read-only.
  - Cut rule: prices with `timestamp < 2026-07-31 18:30 UTC` (IST date ≤ 2026-07-31), and corporate actions with `effective_date ≤ 2026-07-31`.
  - Cut result: 4,420,268 prices kept and 115,307 dropped; 910 corporate actions kept and 7 dropped; 2,930 stocks.
- **SPG export.** Read-only, from production, using `BEGIN TRANSACTION READ ONLY … ROLLBACK`:
  - 6 `selection_gate_decisions` rows with no `ho_*`/`holdout_*` column. The verdict fields are withheld (Ruling T13): `decision='WITHHELD'`, empty stage and reason, `reasons=[]`.
  - The SEL-001 `WALK_FORWARD` trades: decision 3 (h3) has 21,220 rows, from 2018-01-01 to 2026-07-28; decision 5 (h5) has 21,200 rows, from 2018-01-01 to 2026-07-24.
  - Zero walk-forward rows fall after 2026-07-31, checked with an unfiltered count.
- **Frozen snapshot.** `tg-snapshot.db`, read-only, SHA-256 `697917739ad0b663667dcb9fe1b9c29ecf1bd5a508f441e6f76512dd2839ce1c`. It was loaded by the frozen loader with `--data-through 2026-07-31`.
- **Data manifest** (`tg-data-manifest.json`):

| table | rows | max date |
|---|---|---|
| market_prices | 4,420,268 | 2026-07-31 |
| corporate_actions | 910 | 2026-07-31 |
| selection_gate_trades | 42,420 | 2026-07-28 |
| selection_gate_decisions | 6 | 2026-10-01 (decision time, not data) |
| stocks | 2,930 | — |

  CSV SHA-256s:
  - market_prices `d50c28cf…2f62`
  - corporate_actions `2f3fe15c…93e0`
  - stocks `a6021672…90c5`
  - selection_gate_trades `95fd61c8…7d97`
  - selection_gate_decisions `a9dd39ed…57a3`
- **Run self-check.** The run's report records:
  - `max_price_session` 2026-07-31
  - `max_corporate_action_effective_date` 2026-07-31
  - `max_walk_forward_trade_session` 2026-07-28
  - session range 2015-01-01 to 2026-07-31
  - `holdout_month` none and `ho_dataset_sha256` none, so no exam month and no pass 2
- **Tail rule.** The T_σ sessions whose 7-session hold would pass 2026-07-31 were dropped before any statistic: 40 candidates (h3) and 20 (h5).
- **Automated tests** (all green at 73f6d6a):
  - `data_through` fails closed on a later price, corporate action or walk-forward trade, and writes zero `tg_*` rows. It is refused outside walk-forward-only mode.
  - The loader refuses later-dated rows, a CSV missing its cutoff column, and non-withheld SPG verdicts, all before any write. The boundary instant is tested.
  - The manifest content is tested.
  - The captured SQL holds no `HELD_OUT` literal and no `ho_`/`holdout_` column.
  - The freeze happens before any pass-2 read.

## 5. Data sources

- **Tables read** (snapshot, read-only): `market_prices` (official bars via `final_bars_only()`), `stocks`, `corporate_actions`, `selection_gate_decisions` (explicit non-held-out columns) and `selection_gate_trades` (`WALK_FORWARD`, rank ≤ 10).
- **Dataset.** 2,865 sessions, 2015-01-01 to 2026-07-31. U(D) has 2,344,563 rows and the path set has 1,976,502 rows. `dataset_sha256` is `6be6521f30c514574350372fe00d00861bef91cf6e71b764414cf69ae5bb2930`.
- **Stocks per year.**

| year | stocks |
|---|---|
| 2015 | 1,169 |
| 2016 | 1,269 |
| 2017 | 1,367 |
| 2018 | 1,445 |
| 2019 | 1,485 |
| 2020 | 1,532 |
| 2021 | 1,577 |
| 2022 | 1,782 |
| 2023 | 1,893 |
| 2024 | 2,034 |
| 2025 | 2,202 |
| 2026 | 2,710 |

- **Bound SPG decisions.** 3 (SEL-001 h3) and 5 (SEL-001 h5), both decided 2026-10-01.
- **T_σ.** 2,118 sessions per selector. Excluded: 40 candidates (h3) and 20 (h5), all by the tail rule. No TG protected span applies, because there is no exam month.

## 6. Runtime and memory

| measure | value | limit |
|---|---|---|
| elapsed | 737.9 s | 21,600 s |
| CPU | 736.4 s | — |
| peak memory (`measured()`) | 3,001.6 MB | 6 GiB |
| watchdog peak working set | 2,985 MB | 10 GB kill line, never approached |

The run was a single process with no worker pool, and native threads were capped at 4. Exit code 0.

## 7. Parity (§10.2)

| | h3 | h5 |
|---|---|---|
| TG `(h_sel, none, none)` mean excess | 0.5176% | 0.8608% |
| SPG accepted trades over T_σ | 0.5158% | 0.8525% |
| SPG `wf_mean_excess` | 0.5147% | 0.8524% |
| common accepted trades | 13,583 | 10,256 |
| SPG-only | 6 | 11 |
| TG-only | 0 | 0 |
| path-status mismatches | INVALID_PRICE 12, PATH_BAR_MISSING 2 | INVALID_PRICE 23, PATH_BAR_MISSING 7, SUSPECT_RETURN 1 |
| benchmark sessions differing by more than 1e-6 | 108 of 2,118 | 174 of 2,118 |

Explained differences:
- **Stricter resolution (§7.3, §23.4).**
  - SPG's label needs only the D+1 and D+h bars.
  - TG needs every bar from D+1 to D+H to exist and to pass SEU-001's arithmetic.
  - The mismatches are exactly those rows. Their trades are SPG-only, and those rows also leave TG's universe benchmark. That accounts for the differing benchmark sessions and the 0.002–0.008% gap in mean excess.
  - There is no TG-only trade.
- **T_σ.** The tail rule removes 4 sessions (h3) and 2 sessions (h5) that SPG kept. That explains the gap between SPG's accepted mean over T_σ and its `wf_mean_excess`, 0.0011% at h3 and 0.0000% at h5.
- **Data timing.** The snapshot was exported at 05:35 IST on 2026-10-01, and SPG's production run decided at 09:21 IST. Bars up to 31 Jul are not expected to change in between, and nothing above needs that as an explanation.

## 8. Held-out

None was run, by design:
- walk-forward-only mode
- the purist first month, `tg_holdout_not_before = "2026-11"`
- the 2026-07-31 evidence cutoff

The first possible TG exam is November 2026, scored on 12 Dec 2026, and only after a deploy decision.

## 9. Tests and validation

TG tests at 73f6d6a: 155 in total, all passing.

| file | tests |
|---|---|
| choice | 8 |
| config | 5 |
| diagnostics | 6 |
| evaluation | 4 |
| fills | 20 |
| holdout | 7 |
| independence | 7 |
| inputs | 4 |
| migration | 2 |
| operations | 4 |
| parity | 2 |
| paths | 10 |
| publication | 1 |
| reality_check | 6 |
| reduction | 7 |
| runner | 36 |
| schema | 3 |
| snapshot | 18 |
| windows | 5 |

The A1 additions are in diagnostics, windows, runner, snapshot, config and schema.

- **Full suite** (`-n 6`, 434 s): 8,750 passed, 11 skipped, 5 failed. The 5 failures are main's known set:
  - cold-start tip-new-alerts
  - two unordered single-row reads
  - two order-dependent news-category tests
  - On a clean `main` 0183d09: three fail identically and the two news-category tests pass in isolation.
  - The failure output never mentions `trade_geometry`.
- **Scope.** `git diff main -- app/selection_gate scripts/run_selection_gate.py scripts/load_selection_snapshot.py app/recommendations.py` is empty. No `xgboost` appears in `app/trade_geometry` or in either TG script.
- **Review.**
  - One whole-phase review (Opus): 0 Critical, 1 Important, 7 Minor.
  - One fix wave (73f6d6a) fixed I-1, plus Minors M-1, M-2 and M-3.
  - A scoped re-review found all four addressed and nothing new broken.

## 10. Parked findings

These are for the deploy decision, not this run:
- M-4: `ho_shadow_artefacts` counts only the first row's artefact per covered session.
- M-5: `freeze` treats any `IntegrityError` as already consumed.
- M-6: `_record_failure` can raise inside the `_write` handler.
- M-7: `dataset_sha256` is the slowest TG step after the dataset build.
- A failed walk-forward-only run does not write `--report-json`. Stdout carries the report.

M-4 to M-6 affect only the production held-out path, and should be fixed before any deploy that can reach an exam.

## 11. On deploy (not requested; nothing pushed, merged, deployed or migrated)

- **What deploying adds:** migration `0190_trade_geometry`, with five empty append-only tables plus the `diagnostics` and `ho_diagnostics` columns, and one monthly CronJob (02:00 IST on the 12th). No publication change.
- **Open point for that decision:** production runs bind SPG's latest decision. After SPG's 11 Oct run, that decision's walk-forward window will include August 2026.
- **After the first production run** (§22.4): its walk-forward numbers must equal this report's for the same `dataset_sha256` and `bound_spg_decision_id`. They will differ here by design, because the dataset is cut at 2026-07-31. Any other difference is reported.

## 12. Rulings made during execution (ledger)

- Ruling T1: subagent-driven, with no per-task reviews; one whole-branch review, then a fix wave and a scoped re-review, as for SPG-001 (user preference). Cost if wrong: defects found late, in one batch.
- Ruling T2: implementers run only their task's tests and owning suites; Task 14 runs the full suite on its own DATABASE_URL. Cost if wrong: a cross-suite break shows up late.
- Ruling T3: SPG-001 is FROZEN. Any edit under app/selection_gate/** is a defect; only imports are allowed. Cost if wrong: none.
- Ruling T4: stop before merge (plan Task 14). Report to the user. Merge/deploy only on the user's word. Cost if wrong: a delay.
- Ruling T5: implementer model is sonnet (the plan has complete, prototyped code); escalate to opus on BLOCKED. Cost if wrong: more turns.
- Ruling T6 (user, SEL-002 message): TG-001 is PAUSED after Tasks 5-6 (already dispatched) until SEL-002 completes. Dispatch no TG Task 7+.
- Ruling T7: order 7+8+9 (one sonnet dispatch, commit per task) -> A1 (opus, new code from prose) -> 10+delta (sonnet) -> 11+12+delta (sonnet) -> 13 Steps 1-4 + delta (sonnet) -> whole-phase Opus review -> one fix wave + scoped re-check -> Task 13 run. Cost if wrong: more turns.
- Ruling T8 (user conditions): cost sensitivity report-only; snapshot = SEL-002 snapshot.db cut at 2026-07-31 + small read-only SPG export; no code/config change after the Task 13 freeze; stop after report + draft PR. Cost if wrong: none.
- Ruling T9: spec §24.2 binds; change an expected count only when the shift equals the tail-excluded candidates, documented in the report. Cost if wrong: a test hides a real shift.
- Ruling T10: a diagnostics failure must never change a decision (report-only, spec §24). Wrap geometry_diagnostics per call; on exception store {"error": "<Type>: <msg>"} and continue; report renders it. Cost if wrong: a diagnostics bug is visible only in the report, not as a failed run.
- Ruling T11: held-out diagnostics get a nullable JSON column tg_decisions.ho_diagnostics (edit unmerged 0190 + model), written with the decision. Cost if wrong: one extra column.
- Ruling T12: diagnostics lines label geometries H7/Tnone/Snone so the verbatim report test's key counts hold. Cost if wrong: cosmetic.
- Ruling T13: SPG verdict fields (decision, primary_stage, primary_reason, reasons) mix SPG's August exam into the verdict, so the TG snapshot withholds them: decision='WITHHELD', reasons='[]', primary_stage/primary_reason empty. TG reads primary_reason for display only. Trades exported only for SEL-001 decisions. Cost if wrong: the report shows no SPG verdict text.
- Ruling T14: fix wave = I-1, M-1, M-2 (review's list) + M-3 (loader enforces Ruling T13 when data_through is set) because the user made the cutoff absolute. Park M-4..M-7 (production held-out paths / runtime note; none touches the pre-merge WF-only run). Cost if wrong: M-5/M-6 fail-closed edge cases remain for the production exam path, revisit before deploy.
- Ruling T15: stdout (tee'd) carries the text+JSON report and the execution row names failing horizons. Cost if wrong: a failed run's JSON file is missing.

## Appendix A. All 37 walk-forward geometry rows per selector

Columns: n = accepted trades; u = unresolved share; ex = mean net excess; ci = 95% block-bootstrap CI; net = mean r − c; vsHold = excess vs the universe held to H; f+ = fold positive share; med = median fold excess; T/S/E = target/stop/expired rates; el = eligible; R = reasons (EDGETHRE = EDGE_BELOW_THRESHOLD, CONFIDMET = CONFIDENCE_THRESHOLD_NOT_MET, UNSTABFOLD = UNSTABLE_ACROSS_FOLDS).

```text

=== h_sel=3 bound=3 stage_less=None wf_sessions=2118 protected_excl=40 tail_excl=40
RC p=0.0 stat=+0.83% G=36 chosen=H5:Tnone:Snone tol=['H5:Tnone:Snone', 'H5:Tnone:S2.0', 'H7:Tnone:Snone', 'H7:Tnone:S2.0', 'H7:T3.0:Snone'] wf_reasons=[]
H1:Tnone:Snone      n=21151 u=0.001 ex=-0.06% ci=[-0.12%,-0.00%] net=-0.28% vsHold=-0.06% f+=0.417 med=-0.06% T/S/E=0.000/0.000/1.000 el=0 R=EDGETHRE,CONFIDMET,UNSTABFOLD
H1:Tnone:S1.0       n=21151 u=0.001 ex=-0.13% ci=[-0.18%,-0.07%] net=-0.38% vsHold=-0.16% f+=0.311 med=-0.13% T/S/E=0.000/0.132/0.868 el=0 R=EDGETHRE,CONFIDMET,UNSTABFOLD
H1:Tnone:S2.0       n=21151 u=0.001 ex=-0.08% ci=[-0.14%,-0.02%] net=-0.30% vsHold=-0.09% f+=0.379 med=-0.09% T/S/E=0.000/0.018/0.982 el=0 R=EDGETHRE,CONFIDMET,UNSTABFOLD
H1:T1.5:Snone       n=21151 u=0.001 ex=-0.06% ci=[-0.12%,-0.00%] net=-0.27% vsHold=-0.05% f+=0.340 med=-0.07% T/S/E=0.049/0.000/0.951 el=0 R=EDGETHRE,CONFIDMET,UNSTABFOLD
H1:T1.5:S1.0        n=21151 u=0.001 ex=-0.13% ci=[-0.18%,-0.07%] net=-0.37% vsHold=-0.16% f+=0.291 med=-0.14% T/S/E=0.047/0.132/0.821 el=0 R=EDGETHRE,CONFIDMET,UNSTABFOLD
H1:T1.5:S2.0        n=21151 u=0.001 ex=-0.08% ci=[-0.13%,-0.02%] net=-0.30% vsHold=-0.08% f+=0.340 med=-0.11% T/S/E=0.048/0.018/0.933 el=0 R=EDGETHRE,CONFIDMET,UNSTABFOLD
H1:T3.0:Snone       n=21151 u=0.001 ex=-0.06% ci=[-0.11%,+0.00%] net=-0.27% vsHold=-0.05% f+=0.427 med=-0.05% T/S/E=0.006/0.000/0.994 el=0 R=EDGETHRE,CONFIDMET,UNSTABFOLD
H1:T3.0:S1.0        n=21151 u=0.001 ex=-0.13% ci=[-0.18%,-0.07%] net=-0.37% vsHold=-0.15% f+=0.301 med=-0.13% T/S/E=0.006/0.132/0.862 el=0 R=EDGETHRE,CONFIDMET,UNSTABFOLD
H1:T3.0:S2.0        n=21151 u=0.001 ex=-0.07% ci=[-0.13%,-0.01%] net=-0.29% vsHold=-0.08% f+=0.388 med=-0.08% T/S/E=0.006/0.018/0.975 el=0 R=EDGETHRE,CONFIDMET,UNSTABFOLD
H3:Tnone:Snone      n=13583 u=0.003 ex=+0.52% ci=[+0.38%,+0.66%] net=+0.45% vsHold=+0.52% f+=0.816 med=+0.43% T/S/E=0.000/0.000/1.000 el=1 R=
H3:Tnone:S1.0       n=14433 u=0.003 ex=+0.36% ci=[+0.24%,+0.49%] net=+0.22% vsHold=+0.28% f+=0.718 med=+0.31% T/S/E=0.000/0.330/0.670 el=0 R=EDGETHRE
H3:Tnone:S2.0       n=13702 u=0.003 ex=+0.50% ci=[+0.36%,+0.63%] net=+0.37% vsHold=+0.43% f+=0.786 med=+0.44% T/S/E=0.000/0.082/0.918 el=0 R=EDGETHRE
H3:T1.5:Snone       n=13795 u=0.003 ex=+0.37% ci=[+0.25%,+0.49%] net=+0.28% vsHold=+0.35% f+=0.738 med=+0.29% T/S/E=0.245/0.000/0.755 el=0 R=EDGETHRE
H3:T1.5:S1.0        n=14691 u=0.003 ex=+0.24% ci=[+0.13%,+0.34%] net=+0.06% vsHold=+0.12% f+=0.670 med=+0.15% T/S/E=0.227/0.326/0.447 el=0 R=EDGETHRE
H3:T1.5:S2.0        n=13922 u=0.003 ex=+0.35% ci=[+0.23%,+0.46%] net=+0.19% vsHold=+0.26% f+=0.748 med=+0.31% T/S/E=0.244/0.081/0.675 el=0 R=EDGETHRE
H3:T3.0:Snone       n=13592 u=0.003 ex=+0.48% ci=[+0.35%,+0.61%] net=+0.41% vsHold=+0.47% f+=0.796 med=+0.43% T/S/E=0.043/0.000/0.957 el=0 R=EDGETHRE
H3:T3.0:S1.0        n=14450 u=0.003 ex=+0.33% ci=[+0.21%,+0.45%] net=+0.18% vsHold=+0.24% f+=0.728 med=+0.27% T/S/E=0.040/0.329/0.631 el=0 R=EDGETHRE
H3:T3.0:S2.0        n=13712 u=0.003 ex=+0.45% ci=[+0.32%,+0.58%] net=+0.32% vsHold=+0.38% f+=0.786 med=+0.42% T/S/E=0.043/0.082/0.875 el=0 R=EDGETHRE
H5:Tnone:Snone      n=11577 u=0.006 ex=+0.75% ci=[+0.55%,+0.94%] net=+0.83% vsHold=+0.75% f+=0.825 med=+0.69% T/S/E=0.000/0.000/1.000 el=1 R=
H5:Tnone:S1.0       n=12980 u=0.005 ex=+0.47% ci=[+0.30%,+0.65%] net=+0.42% vsHold=+0.34% f+=0.718 med=+0.51% T/S/E=0.000/0.431/0.569 el=0 R=EDGETHRE
H5:Tnone:S2.0       n=11831 u=0.005 ex=+0.70% ci=[+0.50%,+0.89%] net=+0.66% vsHold=+0.58% f+=0.796 med=+0.66% T/S/E=0.000/0.149/0.851 el=1 R=
H5:T1.5:Snone       n=12204 u=0.006 ex=+0.39% ci=[+0.24%,+0.53%] net=+0.38% vsHold=+0.30% f+=0.709 med=+0.33% T/S/E=0.357/0.000/0.643 el=0 R=EDGETHRE
H5:T1.5:S1.0        n=13719 u=0.005 ex=+0.22% ci=[+0.09%,+0.34%] net=+0.09% vsHold=+0.01% f+=0.631 med=+0.25% T/S/E=0.313/0.418/0.269 el=0 R=EDGETHRE
H5:T1.5:S2.0        n=12506 u=0.006 ex=+0.35% ci=[+0.20%,+0.49%] net=+0.23% vsHold=+0.14% f+=0.699 med=+0.27% T/S/E=0.352/0.149/0.500 el=0 R=EDGETHRE
H5:T3.0:Snone       n=11680 u=0.006 ex=+0.66% ci=[+0.48%,+0.83%] net=+0.71% vsHold=+0.63% f+=0.806 med=+0.66% T/S/E=0.118/0.000/0.882 el=1 R=
H5:T3.0:S1.0        n=13115 u=0.005 ex=+0.40% ci=[+0.24%,+0.56%] net=+0.33% vsHold=+0.25% f+=0.709 med=+0.48% T/S/E=0.104/0.432/0.464 el=0 R=EDGETHRE
H5:T3.0:S2.0        n=11940 u=0.006 ex=+0.60% ci=[+0.42%,+0.78%] net=+0.54% vsHold=+0.46% f+=0.767 med=+0.63% T/S/E=0.116/0.150/0.734 el=1 R=
H7:Tnone:Snone      n=10587 u=0.008 ex=+0.83% ci=[+0.58%,+1.08%] net=+1.07% vsHold=+0.83% f+=0.767 med=+0.73% T/S/E=0.000/0.000/1.000 el=1 R=
H7:Tnone:S1.0       n=12396 u=0.007 ex=+0.49% ci=[+0.28%,+0.70%] net=+0.52% vsHold=+0.31% f+=0.680 med=+0.53% T/S/E=0.000/0.496/0.504 el=0 R=EDGETHRE
H7:Tnone:S2.0       n=11039 u=0.008 ex=+0.75% ci=[+0.50%,+0.99%] net=+0.80% vsHold=+0.58% f+=0.757 med=+0.69% T/S/E=0.000/0.206/0.794 el=1 R=
H7:T1.5:Snone       n=11428 u=0.008 ex=+0.41% ci=[+0.23%,+0.59%] net=+0.50% vsHold=+0.26% f+=0.680 med=+0.38% T/S/E=0.429/0.000/0.571 el=0 R=EDGETHRE
H7:T1.5:S1.0        n=13433 u=0.007 ex=+0.21% ci=[+0.08%,+0.35%] net=+0.11% vsHold=-0.11% f+=0.612 med=+0.19% T/S/E=0.358/0.467/0.176 el=0 R=EDGETHRE
H7:T1.5:S2.0        n=11982 u=0.008 ex=+0.35% ci=[+0.19%,+0.52%] net=+0.27% vsHold=+0.04% f+=0.709 med=+0.31% T/S/E=0.417/0.199/0.384 el=0 R=EDGETHRE
H7:T3.0:Snone       n=10815 u=0.008 ex=+0.70% ci=[+0.49%,+0.92%] net=+0.88% vsHold=+0.64% f+=0.757 med=+0.74% T/S/E=0.170/0.000/0.830 el=1 R=
H7:T3.0:S1.0        n=12679 u=0.007 ex=+0.39% ci=[+0.21%,+0.56%] net=+0.37% vsHold=+0.16% f+=0.718 med=+0.42% T/S/E=0.143/0.495/0.362 el=0 R=EDGETHRE
H7:T3.0:S2.0        n=11291 u=0.007 ex=+0.62% ci=[+0.41%,+0.83%] net=+0.62% vsHold=+0.40% f+=0.786 med=+0.59% T/S/E=0.165/0.208/0.627 el=1 R=
REF:H3:T0.05:S0.03  n=16775 u=0.003 ex=-0.13% ci=[-0.19%,-0.07%] net=-0.32% vsHold=-0.25% f+=0.340 med=-0.16% T/S/E=0.309/0.528/0.163 el=0 R=EDGETHRE,CONFIDMET,UNSTABFOLD

=== h_sel=5 bound=5 stage_less=None wf_sessions=2118 protected_excl=20 tail_excl=20
RC p=0.0 stat=+1.00% G=36 chosen=H7:Tnone:Snone tol=['H5:Tnone:Snone', 'H7:Tnone:Snone'] wf_reasons=[]
H1:Tnone:Snone      n=21142 u=0.002 ex=-0.17% ci=[-0.23%,-0.11%] net=-0.39% vsHold=-0.17% f+=0.262 med=-0.20% T/S/E=0.000/0.000/1.000 el=0 R=EDGETHRE,CONFIDMET,UNSTABFOLD
H1:Tnone:S1.0       n=21142 u=0.002 ex=-0.23% ci=[-0.28%,-0.17%] net=-0.47% vsHold=-0.26% f+=0.223 med=-0.25% T/S/E=0.000/0.136/0.864 el=0 R=EDGETHRE,CONFIDMET,UNSTABFOLD
H1:Tnone:S2.0       n=21142 u=0.002 ex=-0.19% ci=[-0.24%,-0.13%] net=-0.41% vsHold=-0.20% f+=0.272 med=-0.18% T/S/E=0.000/0.019/0.981 el=0 R=EDGETHRE,CONFIDMET,UNSTABFOLD
H1:T1.5:Snone       n=21142 u=0.002 ex=-0.18% ci=[-0.24%,-0.13%] net=-0.39% vsHold=-0.17% f+=0.233 med=-0.20% T/S/E=0.048/0.000/0.952 el=0 R=EDGETHRE,CONFIDMET,UNSTABFOLD
H1:T1.5:S1.0        n=21142 u=0.002 ex=-0.24% ci=[-0.29%,-0.18%] net=-0.48% vsHold=-0.27% f+=0.204 med=-0.24% T/S/E=0.046/0.136/0.817 el=0 R=EDGETHRE,CONFIDMET,UNSTABFOLD
H1:T1.5:S2.0        n=21142 u=0.002 ex=-0.20% ci=[-0.26%,-0.14%] net=-0.42% vsHold=-0.20% f+=0.214 med=-0.19% T/S/E=0.048/0.019/0.933 el=0 R=EDGETHRE,CONFIDMET,UNSTABFOLD
H1:T3.0:Snone       n=21142 u=0.002 ex=-0.17% ci=[-0.23%,-0.11%] net=-0.38% vsHold=-0.16% f+=0.272 med=-0.18% T/S/E=0.007/0.000/0.993 el=0 R=EDGETHRE,CONFIDMET,UNSTABFOLD
H1:T3.0:S1.0        n=21142 u=0.002 ex=-0.23% ci=[-0.28%,-0.17%] net=-0.47% vsHold=-0.25% f+=0.204 med=-0.23% T/S/E=0.007/0.136/0.857 el=0 R=EDGETHRE,CONFIDMET,UNSTABFOLD
H1:T3.0:S2.0        n=21142 u=0.002 ex=-0.18% ci=[-0.24%,-0.12%] net=-0.40% vsHold=-0.19% f+=0.262 med=-0.18% T/S/E=0.007/0.019/0.974 el=0 R=EDGETHRE,CONFIDMET,UNSTABFOLD
H3:Tnone:Snone      n=12470 u=0.005 ex=+0.49% ci=[+0.34%,+0.64%] net=+0.45% vsHold=+0.49% f+=0.767 med=+0.48% T/S/E=0.000/0.000/1.000 el=0 R=EDGETHRE
H3:Tnone:S1.0       n=13382 u=0.004 ex=+0.31% ci=[+0.17%,+0.44%] net=+0.18% vsHold=+0.21% f+=0.650 med=+0.24% T/S/E=0.000/0.344/0.656 el=0 R=EDGETHRE
H3:Tnone:S2.0       n=12602 u=0.005 ex=+0.46% ci=[+0.31%,+0.60%] net=+0.35% vsHold=+0.39% f+=0.738 med=+0.44% T/S/E=0.000/0.090/0.910 el=0 R=EDGETHRE
H3:T1.5:Snone       n=12691 u=0.004 ex=+0.26% ci=[+0.14%,+0.38%] net=+0.20% vsHold=+0.24% f+=0.680 med=+0.28% T/S/E=0.252/0.000/0.748 el=0 R=EDGETHRE
H3:T1.5:S1.0        n=13664 u=0.004 ex=+0.11% ci=[-0.00%,+0.21%] net=-0.06% vsHold=-0.02% f+=0.524 med=+0.06% T/S/E=0.231/0.342/0.427 el=0 R=EDGETHRE,CONFIDMET,UNSTABFOLD
H3:T1.5:S2.0        n=12823 u=0.004 ex=+0.23% ci=[+0.11%,+0.35%] net=+0.10% vsHold=+0.14% f+=0.689 med=+0.23% T/S/E=0.249/0.088/0.662 el=0 R=EDGETHRE
H3:T3.0:Snone       n=12490 u=0.005 ex=+0.43% ci=[+0.29%,+0.57%] net=+0.39% vsHold=+0.43% f+=0.738 med=+0.42% T/S/E=0.048/0.000/0.952 el=0 R=EDGETHRE
H3:T3.0:S1.0        n=13409 u=0.005 ex=+0.25% ci=[+0.12%,+0.37%] net=+0.11% vsHold=+0.15% f+=0.650 med=+0.26% T/S/E=0.045/0.344/0.611 el=0 R=EDGETHRE
H3:T3.0:S2.0        n=12619 u=0.005 ex=+0.39% ci=[+0.26%,+0.53%] net=+0.29% vsHold=+0.32% f+=0.748 med=+0.44% T/S/E=0.048/0.089/0.863 el=0 R=EDGETHRE
H5:Tnone:Snone      n=10256 u=0.007 ex=+0.86% ci=[+0.65%,+1.07%] net=+0.99% vsHold=+0.86% f+=0.806 med=+0.80% T/S/E=0.000/0.000/1.000 el=1 R=
H5:Tnone:S1.0       n=11668 u=0.007 ex=+0.55% ci=[+0.36%,+0.74%] net=+0.52% vsHold=+0.41% f+=0.718 med=+0.48% T/S/E=0.000/0.445/0.555 el=1 R=
H5:Tnone:S2.0       n=10513 u=0.007 ex=+0.79% ci=[+0.57%,+1.00%] net=+0.80% vsHold=+0.66% f+=0.777 med=+0.76% T/S/E=0.000/0.160/0.840 el=1 R=
H5:T1.5:Snone       n=10896 u=0.008 ex=+0.36% ci=[+0.20%,+0.52%] net=+0.40% vsHold=+0.27% f+=0.699 med=+0.45% T/S/E=0.371/0.000/0.629 el=0 R=EDGETHRE
H5:T1.5:S1.0        n=12487 u=0.007 ex=+0.17% ci=[+0.03%,+0.30%] net=+0.05% vsHold=-0.06% f+=0.602 med=+0.14% T/S/E=0.322/0.434/0.244 el=0 R=EDGETHRE
H5:T1.5:S2.0        n=11209 u=0.008 ex=+0.32% ci=[+0.16%,+0.47%] net=+0.24% vsHold=+0.11% f+=0.650 med=+0.47% T/S/E=0.365/0.160/0.475 el=0 R=EDGETHRE
H5:T3.0:Snone       n=10375 u=0.008 ex=+0.74% ci=[+0.55%,+0.93%] net=+0.85% vsHold=+0.72% f+=0.796 med=+0.73% T/S/E=0.133/0.000/0.867 el=1 R=
H5:T3.0:S1.0        n=11825 u=0.007 ex=+0.44% ci=[+0.27%,+0.61%] net=+0.39% vsHold=+0.27% f+=0.709 med=+0.42% T/S/E=0.114/0.447/0.440 el=0 R=EDGETHRE
H5:T3.0:S2.0        n=10638 u=0.007 ex=+0.66% ci=[+0.47%,+0.86%] net=+0.65% vsHold=+0.52% f+=0.757 med=+0.67% T/S/E=0.130/0.162/0.708 el=1 R=
H7:Tnone:Snone      n= 9165 u=0.011 ex=+1.00% ci=[+0.73%,+1.27%] net=+1.30% vsHold=+1.00% f+=0.816 med=+0.98% T/S/E=0.000/0.000/1.000 el=1 R=
H7:Tnone:S1.0       n=10985 u=0.010 ex=+0.62% ci=[+0.39%,+0.84%] net=+0.68% vsHold=+0.43% f+=0.709 med=+0.56% T/S/E=0.000/0.508/0.492 el=1 R=
H7:Tnone:S2.0       n= 9629 u=0.010 ex=+0.90% ci=[+0.63%,+1.18%] net=+1.02% vsHold=+0.74% f+=0.767 med=+1.00% T/S/E=0.000/0.223/0.777 el=1 R=
H7:T1.5:Snone       n=10033 u=0.012 ex=+0.40% ci=[+0.22%,+0.59%] net=+0.53% vsHold=+0.25% f+=0.641 med=+0.49% T/S/E=0.448/0.000/0.552 el=0 R=EDGETHRE
H7:T1.5:S1.0        n=12123 u=0.010 ex=+0.18% ci=[+0.03%,+0.32%] net=+0.10% vsHold=-0.15% f+=0.612 med=+0.16% T/S/E=0.365/0.480/0.155 el=0 R=EDGETHRE
H7:T1.5:S2.0        n=10592 u=0.011 ex=+0.34% ci=[+0.16%,+0.52%] net=+0.31% vsHold=+0.03% f+=0.699 med=+0.38% T/S/E=0.434/0.216/0.350 el=0 R=EDGETHRE
H7:T3.0:Snone       n= 9414 u=0.011 ex=+0.84% ci=[+0.60%,+1.07%] net=+1.08% vsHold=+0.78% f+=0.796 med=+0.68% T/S/E=0.190/0.000/0.810 el=1 R=
H7:T3.0:S1.0        n=11307 u=0.010 ex=+0.47% ci=[+0.29%,+0.66%] net=+0.49% vsHold=+0.24% f+=0.738 med=+0.41% T/S/E=0.154/0.506/0.340 el=0 R=EDGETHRE
H7:T3.0:S2.0        n= 9912 u=0.010 ex=+0.73% ci=[+0.49%,+0.96%] net=+0.80% vsHold=+0.52% f+=0.786 med=+0.79% T/S/E=0.184/0.225/0.591 el=1 R=
REF:H3:T0.05:S0.03  n=15873 u=0.004 ex=-0.20% ci=[-0.27%,-0.14%] net=-0.39% vsHold=-0.34% f+=0.223 med=-0.19% T/S/E=0.298/0.537/0.164 el=0 R=EDGETHRE,CONFIDMET,UNSTABFOLD
```

## Appendix B. Diagnostics for the time exits, chosen geometry and reference

All 74 rows carry the same blocks in `tg-eval-report.json`.

```text

######## h_sel=3 chosen=H5:Tnone:Snone

--- H3:Tnone:Snone n=13583 ex=+0.52% ci=[+0.38%,+0.66%]
  cost: 0.30%: +0.52% [+0.38%,+0.66%] | 0.50%: +0.32% [+0.18%,+0.46%] | 0.75%: +0.07% [-0.07%,+0.21%] | 1.00%: -0.18% [-0.32%,-0.04%] | BE zero=+0.82% thr=+0.32% ci=+0.68%
  direction: cuts=(-0.77%,+0.86%) hhi=0.584 largest=0.823
    DOWN  n= 4524 ses= 706 ex=-0.10% ci=[-0.31%,+0.11%] share=-0.062 T/S/E=0.000/0.000/1.000
    FLAT  n= 4496 ses= 706 ex=+0.37% ci=[+0.16%,+0.58%] share=0.239 T/S/E=0.000/0.000/1.000
    UP    n= 4563 ses= 706 ex=+1.27% ci=[+1.00%,+1.54%] share=0.823 T/S/E=0.000/0.000/1.000
  atr: hhi=0.659 largest=0.797
    HIGH  n= 9226 ses=2072 ex=+0.61% ci=[+0.42%,+0.79%] share=0.797 T/S/E=0.000/0.000/1.000
    LOW   n= 2065 ses= 993 ex=+0.20% ci=[+0.00%,+0.40%] share=0.060 T/S/E=0.000/0.000/1.000
    MID   n= 2292 ses=1324 ex=+0.44% ci=[+0.18%,+0.71%] share=0.143 T/S/E=0.000/0.000/1.000
  dist q: p1=-14.84% p10=-6.53% p25=-3.13% p5=-9.10% p50=-0.06% p75=+3.69% p90=+8.47% p95=+11.23% p99=+18.64% hit=0.495 negnet=0.513 sessSum med=+2.11% p10=-18.22% p90=+25.89%
  conc: m=2118 top1=0.260 top5=0.780 hhi=0.001 largest=0.018 | ex_top1 removed=22 n=13416 ex=+0.39% ci=[+0.26%,+0.52%]

--- H5:Tnone:Snone (CHOSEN) n=11577 ex=+0.75% ci=[+0.55%,+0.94%]
  cost: 0.30%: +0.75% [+0.55%,+0.94%] | 0.50%: +0.55% [+0.35%,+0.74%] | 0.75%: +0.30% [+0.10%,+0.49%] | 1.00%: +0.05% [-0.15%,+0.24%] | BE zero=+1.05% thr=+0.55% ci=+0.85%
  direction: cuts=(-1.00%,+1.43%) hhi=0.572 largest=0.696
    DOWN  n= 3829 ses= 706 ex=+0.02% ci=[-0.27%,+0.30%] share=0.007 T/S/E=0.000/0.000/1.000
    FLAT  n= 3831 ses= 706 ex=+0.67% ci=[+0.39%,+0.95%] share=0.297 T/S/E=0.000/0.000/1.000
    UP    n= 3917 ses= 705 ex=+1.54% ci=[+1.16%,+1.93%] share=0.696 T/S/E=0.000/0.000/1.000
  atr: hhi=0.572 largest=0.727
    HIGH  n= 7901 ses=2047 ex=+0.80% ci=[+0.55%,+1.04%] share=0.727 T/S/E=0.000/0.000/1.000
    LOW   n= 1741 ses= 908 ex=+0.41% ci=[+0.09%,+0.72%] share=0.082 T/S/E=0.000/0.000/1.000
    MID   n= 1935 ses=1206 ex=+0.85% ci=[+0.47%,+1.25%] share=0.191 T/S/E=0.000/0.000/1.000
  dist q: p1=-19.12% p10=-8.14% p25=-3.90% p5=-11.37% p50=-0.07% p75=+4.52% p90=+10.67% p95=+15.63% p99=+25.34% hit=0.494 negnet=0.500 sessSum med=+2.12% p10=-20.92% p90=+30.98%
  conc: m=2117 top1=0.240 top5=0.757 hhi=0.001 largest=0.017 | ex_top1 removed=22 n=11431 ex=+0.57% ci=[+0.40%,+0.75%]

--- H7:Tnone:Snone n=10587 ex=+0.83% ci=[+0.58%,+1.08%]
  cost: 0.30%: +0.83% [+0.58%,+1.08%] | 0.50%: +0.63% [+0.38%,+0.88%] | 0.75%: +0.38% [+0.13%,+0.63%] | 1.00%: +0.13% [-0.12%,+0.38%] | BE zero=+1.13% thr=+0.63% ci=+0.88%
  direction: cuts=(-1.14%,+1.89%) hhi=0.533 largest=0.756
    DOWN  n= 3501 ses= 705 ex=-0.15% ci=[-0.51%,+0.22%] share=-0.059 T/S/E=0.000/0.000/1.000
    FLAT  n= 3526 ses= 706 ex=+0.75% ci=[+0.41%,+1.10%] share=0.303 T/S/E=0.000/0.000/1.000
    UP    n= 3560 ses= 704 ex=+1.86% ci=[+1.35%,+2.40%] share=0.756 T/S/E=0.000/0.000/1.000
  atr: hhi=0.586 largest=0.739
    HIGH  n= 7226 ses=2025 ex=+0.90% ci=[+0.58%,+1.21%] share=0.739 T/S/E=0.000/0.000/1.000
    LOW   n= 1578 ses= 859 ex=+0.44% ci=[+0.01%,+0.89%] share=0.080 T/S/E=0.000/0.000/1.000
    MID   n= 1783 ses=1141 ex=+0.89% ci=[+0.37%,+1.42%] share=0.181 T/S/E=0.000/0.000/1.000
  dist q: p1=-22.41% p10=-9.37% p25=-4.72% p5=-12.81% p50=-0.19% p75=+5.11% p90=+12.26% p95=+18.62% p99=+31.90% hit=0.489 negnet=0.504 sessSum med=+1.66% p10=-22.99% p90=+33.81%
  conc: m=2115 top1=0.273 top5=0.852 hhi=0.001 largest=0.019 | ex_top1 removed=22 n=10444 ex=+0.61% ci=[+0.38%,+0.84%]

--- REF:H3:T0.05:S0.03 n=16775 ex=-0.13% ci=[-0.19%,-0.07%]
  cost: 0.30%: -0.13% [-0.19%,-0.07%] | 0.50%: -0.33% [-0.39%,-0.27%] | 0.75%: -0.58% [-0.64%,-0.52%] | 1.00%: -0.83% [-0.89%,-0.77%] | BE zero=+0.17% thr=-0.33% ci=+0.11%
  direction: cuts=(-0.77%,+0.86%) hhi=0.461 largest=-
    DOWN  n= 5635 ses= 706 ex=+0.17% ci=[+0.08%,+0.27%] share=- T/S/E=0.206/0.675/0.119
    FLAT  n= 5451 ses= 706 ex=-0.11% ci=[-0.22%,-0.00%] share=- T/S/E=0.298/0.501/0.201
    UP    n= 5689 ses= 706 ex=-0.45% ci=[-0.55%,-0.34%] share=- T/S/E=0.422/0.408/0.171
  atr: hhi=0.519 largest=-
    HIGH  n=12001 ses=2092 ex=-0.17% ci=[-0.25%,-0.10%] share=- T/S/E=0.339/0.582/0.079
    LOW   n= 2147 ses=1015 ex=+0.20% ci=[+0.07%,+0.33%] share=- T/S/E=0.174/0.257/0.569
    MID   n= 2627 ses=1410 ex=-0.20% ci=[-0.34%,-0.06%] share=- T/S/E=0.284/0.502/0.214
  dist q: p1=-6.01% p10=-4.00% p25=-2.88% p5=-4.65% p50=-1.25% p75=+3.35% p90=+5.01% p95=+5.73% p99=+6.81% hit=0.392 negnet=0.608 sessSum med=-1.16% p10=-14.41% p90=+12.51%
  conc: m=2118 top1=- top5=- hhi=0.001 largest=- | ex_top1 removed=22 n=16576 ex=-0.17% ci=[-0.23%,-0.11%]

  parity: {"benchmark_sessions_compared": 2118, "benchmark_sessions_differing": 108, "common_trades": 13583, "selector_horizon": 3, "spg_accepted_mean_excess": 0.005158049144160718, "spg_only_trades": 6, "spg_wf_mean_excess": 0.00514747, "status_mismatches": {"INVALID_PRICE": 12, "PATH_BAR_MISSING": 2}, "tg_mean_excess": 0.005176141570811849, "tg_only_trades": 0}

######## h_sel=5 chosen=H7:Tnone:Snone

--- H3:Tnone:Snone n=12470 ex=+0.49% ci=[+0.34%,+0.64%]
  cost: 0.30%: +0.49% [+0.34%,+0.64%] | 0.50%: +0.29% [+0.14%,+0.44%] | 0.75%: +0.04% [-0.11%,+0.19%] | 1.00%: -0.21% [-0.36%,-0.06%] | BE zero=+0.79% thr=+0.29% ci=+0.64%
  direction: cuts=(-0.77%,+0.86%) hhi=0.504 largest=0.857
    DOWN  n= 4100 ses= 705 ex=-0.21% ci=[-0.43%,+0.01%] share=-0.145 T/S/E=0.000/0.000/1.000
    FLAT  n= 4146 ses= 706 ex=+0.42% ci=[+0.18%,+0.66%] share=0.288 T/S/E=0.000/0.000/1.000
    UP    n= 4224 ses= 706 ex=+1.23% ci=[+0.96%,+1.51%] share=0.857 T/S/E=0.000/0.000/1.000
  atr: hhi=0.594 largest=0.745
    HIGH  n= 8093 ses=2047 ex=+0.56% ci=[+0.36%,+0.76%] share=0.745 T/S/E=0.000/0.000/1.000
    LOW   n= 1925 ses= 991 ex=+0.22% ci=[-0.02%,+0.47%] share=0.070 T/S/E=0.000/0.000/1.000
    MID   n= 2452 ses=1372 ex=+0.46% ci=[+0.20%,+0.72%] share=0.186 T/S/E=0.000/0.000/1.000
  dist q: p1=-14.72% p10=-6.47% p25=-3.17% p5=-8.96% p50=-0.20% p75=+3.55% p90=+8.56% p95=+11.40% p99=+19.02% hit=0.483 negnet=0.523 sessSum med=+1.25% p10=-17.51% p90=+25.01%
  conc: m=2117 top1=0.306 top5=0.893 hhi=0.001 largest=0.020 | ex_top1 removed=22 n=12316 ex=+0.34% ci=[+0.21%,+0.47%]

--- H5:Tnone:Snone n=10256 ex=+0.86% ci=[+0.65%,+1.07%]
  cost: 0.30%: +0.86% [+0.65%,+1.07%] | 0.50%: +0.66% [+0.45%,+0.87%] | 0.75%: +0.41% [+0.20%,+0.62%] | 1.00%: +0.16% [-0.05%,+0.37%] | BE zero=+1.16% thr=+0.66% ci=+0.95%
  direction: cuts=(-1.00%,+1.43%) hhi=0.600 largest=0.729
    DOWN  n= 3357 ses= 705 ex=+0.02% ci=[-0.30%,+0.34%] share=0.009 T/S/E=0.000/0.000/1.000
    FLAT  n= 3374 ses= 704 ex=+0.69% ci=[+0.38%,+1.01%] share=0.263 T/S/E=0.000/0.000/1.000
    UP    n= 3525 ses= 704 ex=+1.82% ci=[+1.44%,+2.23%] share=0.729 T/S/E=0.000/0.000/1.000
  atr: hhi=0.525 largest=0.678
    HIGH  n= 6671 ses=2001 ex=+0.90% ci=[+0.61%,+1.18%] share=0.678 T/S/E=0.000/0.000/1.000
    LOW   n= 1570 ses= 875 ex=+0.45% ci=[+0.13%,+0.79%] share=0.080 T/S/E=0.000/0.000/1.000
    MID   n= 2015 ses=1243 ex=+1.06% ci=[+0.68%,+1.43%] share=0.242 T/S/E=0.000/0.000/1.000
  dist q: p1=-18.89% p10=-8.11% p25=-3.95% p5=-11.39% p50=-0.11% p75=+4.67% p90=+11.11% p95=+16.42% p99=+26.04% hit=0.493 negnet=0.497 sessSum med=+1.84% p10=-19.42% p90=+30.74%
  conc: m=2113 top1=0.231 top5=0.699 hhi=0.001 largest=0.014 | ex_top1 removed=22 n=10102 ex=+0.67% ci=[+0.48%,+0.86%]

--- H7:Tnone:Snone (CHOSEN) n=9165 ex=+1.00% ci=[+0.73%,+1.27%]
  cost: 0.30%: +1.00% [+0.73%,+1.27%] | 0.50%: +0.80% [+0.53%,+1.07%] | 0.75%: +0.55% [+0.28%,+0.82%] | 1.00%: +0.30% [+0.03%,+0.57%] | BE zero=+1.30% thr=+0.80% ci=+1.03%
  direction: cuts=(-1.14%,+1.89%) hhi=0.518 largest=0.779
    DOWN  n= 3015 ses= 702 ex=-0.26% ci=[-0.62%,+0.11%] share=-0.084 T/S/E=0.000/0.000/1.000
    FLAT  n= 2989 ses= 702 ex=+0.94% ci=[+0.53%,+1.34%] share=0.306 T/S/E=0.000/0.000/1.000
    UP    n= 3161 ses= 705 ex=+2.26% ci=[+1.77%,+2.77%] share=0.779 T/S/E=0.000/0.000/1.000
  atr: hhi=0.529 largest=0.687
    HIGH  n= 5971 ses=1957 ex=+1.06% ci=[+0.70%,+1.40%] share=0.687 T/S/E=0.000/0.000/1.000
    LOW   n= 1399 ses= 826 ex=+0.62% ci=[+0.17%,+1.07%] share=0.095 T/S/E=0.000/0.000/1.000
    MID   n= 1795 ses=1160 ex=+1.11% ci=[+0.59%,+1.63%] share=0.217 T/S/E=0.000/0.000/1.000
  dist q: p1=-21.48% p10=-9.40% p25=-4.77% p5=-12.93% p50=-0.16% p75=+5.35% p90=+12.87% p95=+19.40% p99=+32.78% hit=0.491 negnet=0.498 sessSum med=+1.70% p10=-21.27% p90=+32.12%
  conc: m=2109 top1=0.242 top5=0.758 hhi=0.001 largest=0.016 | ex_top1 removed=22 n=9035 ex=+0.77% ci=[+0.52%,+1.02%]

--- REF:H3:T0.05:S0.03 n=15873 ex=-0.20% ci=[-0.27%,-0.14%]
  cost: 0.30%: -0.20% [-0.27%,-0.14%] | 0.50%: -0.40% [-0.47%,-0.34%] | 0.75%: -0.65% [-0.72%,-0.59%] | 1.00%: -0.90% [-0.97%,-0.84%] | BE zero=+0.10% thr=-0.40% ci=+0.03%
  direction: cuts=(-0.77%,+0.86%) hhi=0.433 largest=-
    DOWN  n= 5287 ses= 706 ex=+0.11% ci=[+0.01%,+0.21%] share=- T/S/E=0.196/0.684/0.120
    FLAT  n= 5190 ses= 706 ex=-0.25% ci=[-0.36%,-0.15%] share=- T/S/E=0.279/0.522/0.199
    UP    n= 5396 ses= 706 ex=-0.47% ci=[-0.57%,-0.36%] share=- T/S/E=0.418/0.408/0.174
  atr: hhi=0.650 largest=-
    HIGH  n=10950 ses=2085 ex=-0.25% ci=[-0.33%,-0.17%] share=- T/S/E=0.325/0.592/0.082
    LOW   n= 2023 ses=1023 ex=+0.07% ci=[-0.07%,+0.21%] share=- T/S/E=0.178/0.285/0.537
    MID   n= 2900 ses=1480 ex=-0.21% ci=[-0.33%,-0.08%] share=- T/S/E=0.280/0.507/0.214
  dist q: p1=-5.87% p10=-3.99% p25=-2.90% p5=-4.58% p50=-1.30% p75=+3.13% p90=+4.93% p95=+5.67% p99=+6.75% hit=0.381 negnet=0.620 sessSum med=-1.97% p10=-14.08% p90=+11.70%
  conc: m=2118 top1=- top5=- hhi=0.001 largest=- | ex_top1 removed=22 n=15687 ex=-0.25% ci=[-0.31%,-0.19%]

  parity: {"benchmark_sessions_compared": 2118, "benchmark_sessions_differing": 174, "common_trades": 10256, "selector_horizon": 5, "spg_accepted_mean_excess": 0.00852471954319665, "spg_only_trades": 11, "spg_wf_mean_excess": 0.00852445, "status_mismatches": {"INVALID_PRICE": 23, "PATH_BAR_MISSING": 7, "SUSPECT_RETURN": 1}, "tg_mean_excess": 0.008608312249169204, "tg_only_trades": 0}
```

## Appendix C. Reproduction

Artefacts (session scratchpad, not committed): `tg-snapshot/` (CSVs + `export-summary.json`), `tg-snapshot.db`, `tg-data-manifest.json`, `tg-eval-commit.txt`, `tg-eval-report.json`, `tg-eval-report.txt`, `tg-eval-stderr.txt`, `tg-watchdog.log`, `tg-full-suite.txt`.

```bash
# marksy-api worktree at 73f6d6a
DATABASE_URL="sqlite:///$SCRATCH/tg-load.db" python -m scripts.load_trade_geometry_snapshot $SCRATCH/tg-snapshot "sqlite:///$SCRATCH/tg-snapshot.db" --data-through 2026-07-31 --manifest $SCRATCH/tg-data-manifest.json
OMP_NUM_THREADS=4 OPENBLAS_NUM_THREADS=4 MKL_NUM_THREADS=4 DATABASE_URL="sqlite:///$SCRATCH/tg-eval.db" python -m scripts.run_trade_geometry --walk-forward-only --ephemeral-schema --source-url "sqlite:///$SCRATCH/tg-snapshot.db" --data-through 2026-07-31 --code-version 73f6d6a984e4633eacda808290219f09a66eae13 --report-json $SCRATCH/tg-eval-report.json
```
