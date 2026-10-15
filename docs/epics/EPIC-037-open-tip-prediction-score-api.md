# EPIC-037 — Per-tip Target Progress API

> Path keeps its original `prediction-score` slug so links do not break; the metric is Target Progress (terminology decision 2026-10-15).

**Status:** DONE 2026-10-15 in marksy-api `feat/trust-target-progress` (not merged or deployed)  
**Priority:** P1  
**Owner:** Backend implementation in marksy-api; coordinated by this Marksy OS epic  
**Authoritative backend checklist:** [marksy-api EPIC-854](https://github.com/indoulia/marksy-api/blob/main/docs/epics/EPIC-854-open-tip-prediction-score-api.md)  
**Spec:** [Trust Score and Target Progress design](../superpowers/specs/2026-10-15-trust-and-prediction-score-engine-design.md)

## Goal

Expose a deterministic, honest Target Progress value (0–100 position between stop-loss and target; not a probability, confidence or likelihood of winning) for one eligible open canonical tip without duplicating the existing tracker, scorecard or model-level trust logic.

## Stories (5)

### S1 — Reuse canonical tip facts
Use the canonical ledger's immutable direction, entry, target, stop-loss, status, current-price observation and source timestamp. Do not calculate from receipts or a separate client-side copy.

**Acceptance:** duplicate receipts do not change Target Progress; merged duplicates and terminal tips do not receive a live value.

### S2 — Calculate orientation-aware progress
For valid BUY geometry (`stop < entry < target`) or SELL geometry (`target < entry < stop`), calculate the 0–100 Target Progress between stop-loss and target, as specified in the design. This is not probability or confidence, and it does not account for risk–reward; a fresh tip starts at its entry's position.

**Acceptance:** deterministic golden tests cover BUY and SELL, entry position, target/stop boundaries, decimal precision, invalid geometry and terminal-boundary reconciliation.

### S3 — Return explicit freshness and unavailable reasons
Return the Target Progress version, evaluation/as-of timestamp, current-price source/timestamp, current return where available, status and stable unavailability reason.

**Acceptance:** missing/stale prices and missing/invalid terms produce no numeric value; no fallback or fabricated market data is allowed.

### S4 — Preserve API compatibility and lifecycle
Prefer additive fields on existing tip/prediction responses. Add a route only if the audit proves the existing contract cannot represent the data. Reuse the existing tracker to resolve terminal conditions.

**Acceptance:** current OS and other clients continue to parse old/new payloads; repeated requests are idempotent and do not mutate immutable tip terms.

### S5 — Verify database behavior and regressions
Add focused unit/API tests plus PostgreSQL integration coverage for any changed transaction, lock or concurrency contract. Run existing tip ledger, scorecard, tracker and active/closed prediction regression tests.

**Acceptance:** reports actual commands/results; SQLite is not claimed to prove PostgreSQL row locking.

## Out of scope

No new Trust Score formula; no probability-based Prediction Score; no changes to Marksy selection/publish gate; no production rollout or migration without separate authorization.
