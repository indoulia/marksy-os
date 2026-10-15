# EPIC-037 — Per-tip Prediction Score API

**Status:** READY FOR AUTONOMOUS EXECUTION  
**Priority:** P1  
**Owner:** Backend implementation in marksy-api; coordinated by this Marksy OS epic  
**Authoritative backend checklist:** [marksy-api EPIC-854](https://github.com/indoulia/marksy-api/blob/main/docs/epics/EPIC-854-open-tip-prediction-score-api.md)  
**Spec:** [Trust and Prediction Score design](../superpowers/specs/2026-10-15-trust-and-prediction-score-engine-design.md)

## Goal

Expose a deterministic, honest current score for one eligible open canonical tip without duplicating the existing tracker, scorecard or model-level trust logic.

## Stories (5)

### S1 — Reuse canonical tip facts
Use the canonical ledger's immutable direction, entry, target, stop-loss, status, current-price observation and source timestamp. Do not calculate from receipts or a separate client-side copy.

**Acceptance:** duplicate receipts do not change the score; merged duplicates and terminal tips do not receive a live score.

### S2 — Calculate orientation-aware progress
For valid BUY/SELL geometry, calculate a 0–100 price-position score between stop-loss and target, as specified in the design. This is not probability or confidence.

**Acceptance:** deterministic golden tests cover BUY and SELL, entry position, target/stop boundaries, decimal precision, invalid geometry and terminal-boundary reconciliation.

### S3 — Return explicit freshness and unavailable reasons
Return the score version, evaluation/as-of timestamp, current-price source/timestamp, current return where available, status and stable unavailability reason.

**Acceptance:** missing/stale prices and missing/invalid terms produce no numeric score; no fallback or fabricated market data is allowed.

### S4 — Preserve API compatibility and lifecycle
Prefer additive fields on existing tip/prediction responses. Add a route only if the audit proves the existing contract cannot represent the data. Reuse the existing tracker to resolve terminal conditions.

**Acceptance:** current OS and other clients continue to parse old/new payloads; repeated requests are idempotent and do not mutate immutable tip terms.

### S5 — Verify database behavior and regressions
Add focused unit/API tests plus PostgreSQL integration coverage for any changed transaction, lock or concurrency contract. Run existing tip ledger, scorecard, tracker and active/closed prediction regression tests.

**Acceptance:** reports actual commands/results; SQLite is not claimed to prove PostgreSQL row locking.

## Out of scope

No new Trust Score formula; no changes to Marksy selection/publish gate; no production rollout or migration without separate authorization.
