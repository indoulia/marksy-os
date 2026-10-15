# Marksy ML Admin Control Plane — design

**Date:** 2026-10-15  
**Status:** PLANNED — documentation only; implementation not started  
**Coordination repo:** `indoulia/marksy-os`  
**Reference artifact:** `marksy_ml_admin_control_plane.html` (static prototype in the user's Library; illustrative data only)

## 1. Goal

Make model learning and research operations manageable through an Admin UI rather than direct database access, ad-hoc API calls, or command-line-only workflows. The UI must expose the existing ML lifecycle through supported ML-service contracts and make evidence, lineage, status, and governance visible.

## 2. Product boundary

The intended request/data path is:

`Admin App UI → supported ML service/API boundary → marksy_ml`

- Admin UI must not connect directly to the ML database.
- Do not add UI-side SQL, one-off scripts, or ad-hoc production API calls as a substitute for supported service operations.
- First inspect the actual admin-app repository and current ML service/API contracts; do not assume the static prototype's controls map to existing endpoints.
- Reuse existing model registry, dataset registry, training, evaluation, selection, shadow-run, and audit capabilities wherever they exist.
- If a required service capability is missing, record it as a backend dependency; do not fake success in the UI.
- Keep research and learning operations separate from production publication.
- Production eligibility/publication remains controlled by the existing SPG-001 gate. No UI control may bypass it.

## 3. Reference UI areas

The existing static prototype sketches these areas:

1. Dashboard / operational overview
2. Models / immutable model versions and provenance
3. Training Runs / start, monitor, inspect and recover supported runs
4. Model Lab / candidates, shadow runs and experiments
5. Selection / candidate rounds, winner lock and handoff state
6. Evaluation / quality, sufficiency, uncertainty and validation results
7. Leaderboard / research comparisons, explicitly not promotion
8. Datasets / version, lineage, coverage and provenance
9. Data Quality / freshness, missingness, integrity and validation failures
10. Promotion & SPG / controlled eligibility state and gate evidence
11. Audit Trail / actor, action, object, timestamp and result

The prototype is a visual reference only. Its sample names, counts, percentages, timestamps, buttons and statuses are not authoritative data and must never be copied as live values.

## 4. Interaction and safety requirements

- Show real server-backed state, with loading, empty, stale, failed and permission-denied states.
- Any action that starts, cancels, retries, locks, promotes, or changes lifecycle state must use a documented service contract, show the exact object and consequences, require confirmation where consequential, and report the server's actual result.
- Make long-running jobs observable after navigation, refresh, and session restart; do not make progress depend on the page staying open.
- Distinguish model family, immutable model version/artifact, training run, dataset version, evaluation, selection round and promotion decision.
- Every displayed result must expose provenance and an as-of/time context when applicable.
- Never use a leaderboard rank or training completion as implicit production approval.
- Show evidence sufficiency and limitations; do not collapse missing/weak evidence into a green success badge.
- Keep audit records for privileged and state-changing operations, using server identity rather than client-asserted actors.
- Avoid exposing secrets, raw production datasets, or sensitive configuration in the UI/logs.

## 5. Scope exclusions

- No new model algorithm, feature engineering, training protocol or selection policy in this UI workstream.
- No direct production database access from the Admin App.
- No bypass, rewrite or automatic override of SPG-001.
- No change to Marksy's production prediction selection, publication behavior, or published calls.
- No fabricated demo data in a connected/production build.
- No wholesale redesign of existing ML services before the actual gaps are established.

## 6. Delivery sequence

EPIC-040 establishes repository/service-contract reality and the Admin shell/dashboard. EPIC-041 adds model registry and training-run workflows. EPIC-042 adds learning/research workspace, selection, evaluation and leaderboard. EPIC-043 adds dataset and data-quality views. EPIC-044 completes controlled promotion/SPG visibility and audit.

The implementation agent must inspect the real Admin App location and relevant ML service repository before coding. This repo's epics coordinate scope; they do not authorize backend schema changes, production data mutation, model promotion, or deployment.

## 7. Definition of done

- The Admin UI uses only supported service/API contracts and has no direct DB access.
- Every screen is backed by real state or an explicit unavailable/not-yet-supported state.
- Mutating actions are authorized, confirmed where consequential, auditable, and recoverable.
- Research and production boundaries are visible and enforced server-side.
- Focused tests cover contracts, state transitions, loading/error/empty states, permissions, and action failures.
- No production promotion or deployment occurs without separate approval.
