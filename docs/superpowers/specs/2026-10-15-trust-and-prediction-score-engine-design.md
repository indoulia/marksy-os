# Trust Score and Prediction Score — design

**Date:** 2026-10-15  
**Status:** READY FOR AUTONOMOUS IMPLEMENTATION AFTER USER STARTS CLAUDE  
**Repos:** `indoulia/marksy-api` (scoring authority), `indoulia/marksy-os` (client and presentation)

## 1. Goal

Make the existing distinction explicit and reliable:

- **Trust Score** belongs to an entity: a channel, source/caller, or FinFluencer. It summarizes eligible historical outcomes that were resolved by the score's `as_of` time.
- **Prediction Score** belongs to one canonical tip. It describes the current state/performance of that tip while it is open. It must never be confused with historical source reliability, model confidence, Marksy's model-level prediction-trust score, or a final outcome.

The backend remains authoritative. Marksy OS fetches and renders results; it does not independently calculate scoring formulas.

## 2. Audit findings and reuse-first constraints

The repositories already contain substantial relevant work. Do not rebuild it:

- `marksy-api/app/tip_scorecard.py` implements canonical tip scorecard metrics and a Wilson 95% lower-bound-based Trust Score (`SCORECARD_VERSION = SCR-001`, `MIN_COMPLETED_FOR_TRUST = 10`).
- `marksy-api/app/tip_scorecard_query.py` reads canonical tips for scorecards.
- The central tip-ledger spec (`2026-09-29-marksy-central-tip-ledger-design.md`) defines canonical-tip counting, immutable call terms, duplicate exclusion, `as_of` semantics, source exits, invalidation, and the trust-score formula.
- `marksy-api/app/prediction_trust_score.py` is a separate Marksy/model-level evidence score. It is not the per-tip Prediction Score and must not be renamed or overloaded to mean it.
- Marksy OS already has `TrustScreen.kt`, `ScorecardsView.kt`, `PredictionsView.kt`, `PredictionModels.kt`, and the scorecard repository/models. Extend these in place.
- Existing APIs include scorecard and active/closed prediction surfaces. Inspect their actual current contracts and tests before proposing any new endpoint.

This is a gap-closure project, not a rewrite of the central tip ledger, scorecards, prediction lifecycle, selection model, publish gate, or app navigation.

## 3. Metric definitions

### 3.1 Trust Score — entity-level, historical

The existing `SCR-001` calculation is the baseline and must be preserved unless implementation review finds a concrete defect and documents it:

- Aggregate canonical tips, never receipts.
- Exclude merged duplicates, invalidated tips, unscorable tips, data-unresolved tips and open tips from scored percentages/returns as defined in the existing contract.
- Require at least 10 completed eligible tips before displaying a numeric Trust Score; otherwise return the existing explicit insufficient-history/null state.
- Use the existing Wilson 95% lower bound and return-quality component. Do not replace this with raw accuracy, an arbitrary volume bonus, or an unvalidated Bayesian formula.
- Keep source/channel/caller attribution canonical and deduplicated. A FinFluencer maps to the existing canonical caller/source identity when the data model supports that mapping. Do not invent identity relationships from display names.
- Preserve filter semantics, IST boundaries, calculation version, counts, and invalidated counts.

If an existing route or UI fails to show a supported entity type, identify the smallest compatible extension. Do not create a second Trust Score algorithm.

### 3.2 Prediction Score — individual open tip

The Prediction Score is per canonical tip, not per source. Its inputs must be the tip's immutable terms and a valid market observation available at the evaluation time.

Initial proposed meaning: **price-position/progress score from 0 to 100 between the tip's stop-loss and target**, not a probability of success.

For a valid BUY geometry (`stop < target`), use the orientation-aware corridor position `(price - stop) / (target - stop) * 100`. For a valid SELL geometry (`target < stop`), use `(stop - price) / (stop - target) * 100`. Apply bounded output only while the tip is genuinely open and its lifecycle is current. If the price has crossed a terminal boundary, invoke/rely on the existing tracker and lifecycle; do not leave the tip open merely to emit a clamped score.

This formula is a **display progress measure only**. It is not probability, source trust, expected return, or model confidence. UI copy must make that clear. Preserve the existing current-return, target/stop distance, outcome and lifecycle fields separately.

Return no numeric Prediction Score when geometry is invalid/incomplete, entry terms are unavailable where required, the market price is missing/stale, or the tip is terminal. Return a stable reason/status instead (for example `INSUFFICIENT_TERMS`, `PRICE_UNAVAILABLE`, `PRICE_STALE`, `NOT_OPEN`, `INVALID_GEOMETRY`). Do not fabricate a value. The implementing agent must first audit existing tip geometry and lifecycle semantics and adjust this proposal only where the existing canonical contract requires it; document any deviation before coding.

The score response must include tip ID, score/version, status, entry/target/stop, current price, current return if available, market-data source and as-of timestamp, freshness, and unavailability reason. Do not mutate immutable call terms.

## 4. Historical outcomes and future prediction generation

Using past source outcomes as features for future stock selection is a separate experiment, not an implicit side effect of score calculation.

- Only features knowable at the prediction's creation timestamp may be used.
- Open tips are not historical outcomes.
- Backtests must use chronological walk-forward splits and point-in-time source identity/score snapshots.
- First run in shadow/offline mode. Do not feed source Trust Score into production ranking, publish gating, or user-facing recommendations until a separate evidence-based decision and validation gate approves it.
- Compare against the current baseline and report coverage, sample size, leakage checks, and performance by horizon/regime.
- A source score must not be used as proof that an individual tip will succeed.

## 5. API and OS contract

Reuse existing endpoints if they can carry the required fields without breaking callers. Add a new endpoint only if the audit proves the existing contract cannot represent per-tip score/status/freshness.

All new response fields must be additive and backward compatible. API names should follow existing project conventions. Every result must be point-in-time and auditable.

Marksy OS:
- Trust tab: entity-level Trust Score, resolved sample count, sufficient/insufficient-history state, and as-of time.
- Predictions: per-tip Prediction Score only for eligible open tips, alongside live return and target/stop geometry. Closed tips show outcome/history, not a live score.
- Clearly distinguish Trust Score, Prediction Score, current return, final outcome, and Marksy model-level confidence/trust.
- Use loading, stale, unavailable and error states; never use mock/placeholder production values.
- No client-side reimplementation of either score.

## 6. Non-goals and guardrails

- No redesign of existing screens or navigation.
- No replacement of `SCR-001` merely to introduce a new algorithm.
- No new source/channel identity inferred from names alone.
- No training, production rank changes, publication-gate changes, or automatic trading.
- No schema migration unless the audit proves it necessary; prefer additive fields and existing ledger facts.
- No changes to unrelated ingestion, notification capture, accessibility services, or broker execution.
- No production data copied to a laptop.
- Never claim PostgreSQL concurrency guarantees based solely on SQLite tests; use real PostgreSQL integration tests when row-lock/transaction behavior is part of a changed contract.

## 7. Acceptance criteria

1. Trust Score and per-tip Prediction Score have distinct names, subjects, semantics and tests.
2. Existing Trust Score golden tests and scorecard APIs remain compatible.
3. Prediction Score is deterministic for identical tip facts, price observation and `as_of`.
4. Open/closed/invalidated/duplicate/unscorable/stale-price cases are explicit and cannot silently produce a misleading numeric score.
5. OS consumes backend values and shows their freshness/status.
6. Tests prove no open tip contributes to historical Trust Score and no future outcome leaks into point-in-time calculations.
7. Historical source reliability remains shadow-only until separately validated and approved.
