# EPIC-038 — Marksy OS Trust Score and Target Progress integration

**Status:** DONE 2026-10-15 on `feat/trust-target-progress` (not merged); no device check (no device connected)  
**Priority:** P1  
**Owner:** marksy-os  
**Depends on:** EPIC-036 contract freeze and EPIC-037 API contract  
**Spec:** [Trust Score and Target Progress design](../superpowers/specs/2026-10-15-trust-and-prediction-score-engine-design.md)

## Goal

Show the backend's entity Trust Score and individual open-tip Target Progress in existing screens, without duplicating formulas or redesigning the app.

## Stories (5)

### S1 — Extend typed response models
Update existing scorecard and prediction DTOs/repository parsing only for fields missing from the API contract. Keep old payloads parseable and nullable/unavailable fields explicit.

**Acceptance:** recorded contract tests cover existing and new payload shapes; no breaking constructor/serialization changes.

### S2 — Preserve the Trust screen
Reuse `TrustScreen.kt` and `ScorecardsView.kt`. Ensure supported channel/source/caller/FinFluencer identities display the historical Trust Score, completed sample count, sufficient-history state and as-of time. If a FinFluencer identity is not represented canonically by the backend, show the actual supported source/caller identity rather than guessing a mapping.

**Acceptance:** trust score is never computed from currently open tips; sample counts and unavailable states match API results.

### S3 — Add per-open-tip Target Progress to Predictions
Reuse `PredictionsView.kt` and existing open-tip models. Show Target Progress, labelled "Target progress", only for eligible open tips, with a compact explanation: current price position between stop-loss and target, not probability of success; a new tip starts at its entry's position. Never label anything "Prediction Score".

**Acceptance:** current return and risk–reward remain separate; closed/invalidated/duplicate tips do not show a live value.

### S4 — Handle real-world states
Show explicit loading, stale-price, insufficient-terms, unavailable-price, API-error and closed-tip states. Do not show 0 as a fallback for missing Target Progress and do not use sample/demo values in production paths.

**Acceptance:** UI tests cover each state and verify stale data is labelled with its timestamp.

### S5 — Regression and build validation
Run relevant JVM/unit tests and the full test suite, assemble debug APK, and perform device validation if a device is available.

**Acceptance:** report exact test/build results and any device-validation limitation; existing Trust/Predictions navigation and behavior remain intact.

## Non-goals

No new tab, navigation redesign, trading execution, client-side scoring algorithm, or Prediction Score display.
