# Marksy ML Admin Control Plane — execution plan

**Status:** READY FOR AUTONOMOUS IMPLEMENTATION AFTER THE USER STARTS THE ADMIN-APP WORKFLOW  
**Design:** [ML Admin Control Plane](../specs/2026-10-15-marksy-ml-admin-control-plane-design.md)  
**Coordination epics:** EPIC-040 through EPIC-044 in this repository

## Before implementation

1. Locate the actual Admin App repository, framework, entry points, authentication/authorization model, deployment workflow, and current tests. Do not assume the Android app is the Admin App.
2. Inspect the ML service/API and `marksy_ml` repositories. Map each prototype page to an existing supported contract and classify it COMPLETE / PARTIAL / MISSING / BROKEN.
3. Inspect Git status and recent history in every repository before editing. Preserve unrelated work.
4. Produce a contract map: page/action → service operation → authorization → audit event → tests. Identify missing backend capabilities separately; no UI mock may masquerade as a real operation.
5. Keep each epic to five or fewer implementation stories. Test each increment and run the relevant full suite before marking an epic complete.

## Ordered delivery

1. **EPIC-040 — Admin shell, access boundary and dashboard.** Establish the real app entry point, navigation, service client, authorization/error states, and a truthful operational dashboard.
2. **EPIC-041 — Model registry and training runs.** Inspect immutable model/version lineage and implement supported read/start/monitor/cancel/retry workflows only where contracts exist.
3. **EPIC-042 — Learning and research workspace.** Expose experiments, candidates, shadow runs, selection rounds, evaluation and research leaderboard without altering production behavior.
4. **EPIC-043 — Datasets and data quality.** Show versions, lineage, coverage, freshness, integrity checks and validation failures from authoritative services.
5. **EPIC-044 — Promotion/SPG and audit.** Show the exact artifact and gate evidence, preserve the SPG-001 decision boundary, and expose server-authoritative audit history.

## Execution rules

- Reuse existing services and UI patterns; do not redesign the model lifecycle.
- Admin App → ML services → `marksy_ml`; never UI → database.
- A missing endpoint is a documented dependency, not permission to add an ad-hoc route or SQL access.
- Production publication remains SPG-001-controlled. No UI action may infer or force eligibility.
- Do not change selection algorithms, training windows, dataset semantics, or production predictions as part of UI work.
- Do not use illustrative prototype values in connected screens.
- Require server-side authorization for every privileged operation; do not trust client-provided actor IDs.
- No production data on a laptop. Do not export datasets or outcomes merely to populate a UI.
- Do not deploy, promote a model, or mutate production state without separate approval.
- Commit in focused increments and report repository, commit, tests, acceptance result, and unresolved dependencies.
