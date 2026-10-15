# Trust Score and Prediction Score — implementation plan

> Execution mode: autonomous after the user starts Claude. Inspect both repositories first; make only the changes supported by the current implementation and this spec.

**Goal:** Close only the gaps between the existing canonical tip scorecards/Trust Score and the per-open-tip Prediction Score contract, then wire the results into existing Marksy OS screens.

**Spec:** `docs/superpowers/specs/2026-10-15-trust-and-prediction-score-engine-design.md`

## Repository ownership

- `indoulia/marksy-api`: canonical formulas, data access, lifecycle, response contracts, persistence if proven necessary, backend tests.
- `indoulia/marksy-os`: typed API DTOs/repository integration, existing Trust and Predictions screens, Android tests and UI states.
- Do not implement the same backend story twice. `docs/epics/EPIC-854-open-tip-prediction-score-api.md` is the authoritative backend checklist for EPIC-037. `docs/epics/EPIC-855-source-reliability-shadow-evaluation.md` is the authoritative backend checklist for EPIC-039.

## Ordered execution

### EPIC-036 — Audit and freeze score semantics
1. Read the spec and current implementation in both repositories, including routes, DTOs, models, tests, migrations, and the existing central tip-ledger design/plan.
2. Produce a concise gap matrix: already correct / missing / defective, with exact file paths and existing tests. Confirm Trust Score uses the existing `SCR-001` Wilson lower-bound implementation and identify all current meanings of "prediction score", "rating", "confidence", and "prediction trust".
3. Freeze the response contract and scoring semantics. If a required change conflicts with an existing contract, record the conflict and select the least-invasive compatible behavior; do not stop for routine design choices.
4. Add/adjust golden tests for metric separation, as-of semantics, duplicate exclusion and insufficient sample behavior before production code changes.

**Exit gate:** no duplicate scoring engine proposed; API contract and acceptance cases are documented.

### EPIC-037 — Per-tip Prediction Score in marksy-api
1. Execute the implementation checklist in API EPIC-854; reuse the canonical tip ledger and tracker.
2. Implement the progress score only when valid geometry and fresh current price exist; return explicit unavailability reasons otherwise.
3. Preserve existing fields/routes and add response fields only where needed.
4. Test BUY/SELL geometry, entry/target/stop boundaries, stale/missing prices, invalid terms, duplicate/invalidated/closed tips, and idempotent repeated reads/evaluations.
5. Run the backend regression suite and PostgreSQL integration tests for any changed transaction/locking guarantees; SQLite tests alone do not prove row-lock behavior.

**Exit gate:** API response is deterministic, backward compatible and tested against the actual PostgreSQL deployment engine where required.

### EPIC-038 — Marksy OS integration
1. Extend existing scorecard/prediction DTOs and repository parsing; preserve old payload compatibility.
2. Update the existing Trust screen only if the audit finds a missing field/state. Do not redesign the screen.
3. Update the existing Predictions screen to show the per-tip Prediction Score and a short explanation that it is progress between stop and target, not probability. Keep live return separate.
4. Represent insufficient history, unavailable score, stale price, loading, API failure and closed tip without placeholder numbers.
5. Run unit tests and assemble the Android debug build; run device verification if a device is available and report if not.

**Exit gate:** existing screens show the correct score for the correct subject, with no client-side formula.

### EPIC-039 — Historical source reliability, shadow-only
1. Execute API EPIC-855; export point-in-time Trust Score/sample-size features from the existing canonical ledger only.
2. Build chronological walk-forward evaluation that computes each feature using only information available at that historical timestamp.
3. Compare baseline versus baseline-plus-source-features by horizon and regime; report sample coverage, uncertainty, failures and leakage checks.
4. Keep all new features out of production prediction ranking, publication gating and user-facing recommendations. Do not change live behavior based on a positive-looking result alone.
5. Publish a reproducible report and a separate explicit decision gate for any later production use.

**Exit gate:** reproducible shadow evaluation exists; production behavior is unchanged.

## Global rules

- Maximum five implementation stories per EPIC.
- Inspect before modifying. Do not recreate working features or duplicate the existing Trust Score.
- Keep API and OS changes backward compatible; no breaking endpoint or schema changes.
- No production data on laptop; use the established safe environment and existing fixtures.
- No secrets in commits, logs, examples or reports.
- No merge/deploy/production migration unless separately requested.
- After each EPIC, record exact commits, tests run, pass/fail counts, skipped tests, PostgreSQL coverage and any device-verification limitation.
- Continue through ordinary implementation choices autonomously. Stop only for a genuine blocker, data-integrity risk, incompatible contract, or required destructive/production action.
