# EPIC-036 — Trust Score vs Target Progress: audit and contract freeze

**Status:** READY FOR AUTONOMOUS EXECUTION  
**Priority:** P1  
**Owner:** Cross-repository; contract coordinated from marksy-os  
**Repositories:** `indoulia/marksy-api`, `indoulia/marksy-os`  
**Spec:** [Trust Score and Target Progress design](../superpowers/specs/2026-10-15-trust-and-prediction-score-engine-design.md)

## Goal

Confirm and preserve the distinction between entity-level historical Trust Score and per-open-tip Target Progress (the 0–100 position between stop-loss and target; not a probability). "Prediction Score" is reserved for a future evidence-calibrated estimate and is not built by this workstream. Audit before coding because the existing ledger, scorecard and screens are already substantial.

## Stories (4; maximum 5)

### S1 — Audit existing backend score paths
Inspect `app/tip_scorecard.py`, `app/tip_scorecard_query.py`, scorecard routes/schemas, `app/prediction_trust_score.py`, active/closed prediction routes, tip tracker and relevant tests. Record which code calculates each score and its subject, including the existing `POS-001` prediction opportunity score and `OPR-001` composite score (`docs/api/SCORE-METRICS.md`).

**Acceptance:** a path-level gap matrix distinguishes canonical-tip Trust Score, Marksy/model-level prediction trust, prediction/composite opportunity scores, model confidence/rating, current return, risk–reward and the proposed open-tip Target Progress.

### S2 — Audit existing Android integration
Inspect `TrustScreen.kt`, `ScorecardsView.kt`, `PredictionsView.kt`, DTOs, repository methods and existing tests.

**Acceptance:** existing working screens/components are identified for reuse; no duplicate screen or navigation is proposed.

### S3 — Freeze score semantics and response contract
Use the existing `SCR-001` Wilson lower-bound Trust Score as baseline. Specify Target Progress inputs, status, freshness, unavailable reasons and version, under names that say "target progress", never "prediction score". Reuse current routes when possible.

**Acceptance:** contract documents subject, units, as-of semantics, invalid states, compatibility behavior and no-look-ahead rules.

### S4 — Add/adjust contract golden tests
Cover entity vs tip identity, open tips excluded from historical trust, sample size below 10, duplicate exclusion, terminal tips and stale/missing price states.

**Acceptance:** tests fail for a score-subject mix-up and pass without altering existing expected scorecard results.

## Non-goals

No new Trust Score algorithm; no probability-based Prediction Score; no production ranking or publish-gate change; no screen redesign; no schema migration unless proven necessary by the audit.
