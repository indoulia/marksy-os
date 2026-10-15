# EPIC-039 — Historical source reliability as a shadow prediction feature

**Status:** IMPLEMENTED 2026-10-15 as marksy-api `SRS-001` (shadow-only); real-data run pending owner approval  
**Priority:** P2  
**Owner:** marksy-api (experiment); marksy-os coordinates the execution plan  
**Authoritative backend checklist:** [marksy-api EPIC-855](https://github.com/indoulia/marksy-api/blob/main/docs/epics/EPIC-855-source-reliability-shadow-evaluation.md)  
**Spec:** [Trust Score and Target Progress design](../superpowers/specs/2026-10-15-trust-and-prediction-score-engine-design.md)

## Goal

Test whether historical source/channel/caller reliability adds predictive value for future Marksy stock predictions. This is an experiment, not a production scoring change, and it does not create a Prediction Score or change Target Progress.

## Stories (5)

### S1 — Point-in-time feature extraction
For each historical prediction timestamp, derive source Trust Score and eligible resolved sample size using only outcomes known by that timestamp.

**Acceptance:** deterministic tests prove later outcomes cannot affect earlier feature rows; unknown/unmapped sources remain unavailable.

### S2 — Walk-forward evaluation
Evaluate baseline versus baseline-plus-source-reliability features using chronological splits and existing Marksy validation conventions.

**Acceptance:** report by horizon and market regime with sample coverage, uncertainty and data exclusions.

### S3 — Leakage and attribution audit
Verify canonical-tip deduplication, source identity, copied/syndicated tips, insufficient history and stale/out-of-sample dates.

**Acceptance:** explicit leakage checks and duplicate attribution tests pass; no receipt-level double counting.

### S4 — Shadow-only result publication
Write a reproducible report/artifact. Any persisted shadow feature must be clearly labelled experimental and must not alter live prediction ranking.

**Acceptance:** production output, selection gate and published calls remain unchanged.

### S5 — Separate decision gate
Document evidence and a go/no-go recommendation for any later use in production.

**Acceptance:** no live use is enabled by this epic; promotion requires a separately approved follow-up and held-out evidence.
