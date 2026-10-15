# EPIC-040 — Admin Control Plane shell and dashboard

**Status:** IMPLEMENTED 2026-10-15 on local branches (admin-app + marksy-api `feat/ml-admin-control-plane`); admin-app typecheck, lint, 73/73 vitest and build passed 2026-10-15 (Node 20.18.0) at f3f9e13. Audit + contract: [contract map](../superpowers/specs/2026-10-15-ml-admin-contract-map.md)  
**Priority:** P1  
**Owner:** Admin App; coordinate backend gaps with the ML services repository  
**Spec:** [ML Admin Control Plane design](../superpowers/specs/2026-10-15-marksy-ml-admin-control-plane-design.md)

## Goal

Establish the actual Admin App entry point and secure service boundary for ML learning/model operations, then build a truthful overview. The static prototype is a reference, not an implementation or source of live values.

## Stories (5)

### S1 — Locate the real Admin App and establish baseline
Identify the authoritative repository, framework, current navigation, auth model, test/build commands and deployment path. Inspect Git status/history before changes.

**Acceptance:** repository map and baseline tests/build are recorded; no unrelated changes are overwritten; any uncertainty about the app's location is resolved from repository evidence.

### S2 — Map ML service contracts
Inspect supported ML service/API operations for status, model registry, training, evaluation, datasets, selection, SPG and audit. Map each dashboard datum to a contract and classify capabilities COMPLETE / PARTIAL / MISSING / BROKEN.

**Acceptance:** a checked-in contract map identifies service operation, permissions, response freshness, audit behavior and missing dependencies; no direct database or ad-hoc API path is proposed.

### S3 — Admin shell and navigation
Implement/reuse the existing Admin App shell and navigation for Dashboard, Models, Training Runs, Model Lab, Selection, Evaluation, Leaderboard, Datasets, Data Quality, Promotion & SPG, and Audit Trail.

**Acceptance:** navigation works with existing app conventions, preserves authentication/session behavior and has responsive/loading/error states; no unrelated app redesign.

### S4 — Service client and operational dashboard
Use the supported ML service client to show real service health, active jobs, model/evaluation state and last-updated times. Omit unsupported metrics or show them explicitly unavailable.

**Acceptance:** no hardcoded illustrative counts/metrics in connected builds; tests cover success, empty, stale, timeout, authorization failure and service failure.

### S5 — Access and audit baseline
Apply existing role/permission policy to read access and define server-side audit expectations for later mutating workflows.

**Acceptance:** unauthorized data/actions are not exposed; actor identity comes from authenticated server context; audit contract gaps are documented as dependencies.

## Non-goals

No model training, selection-policy changes, direct DB access, SPG bypass, production promotion or deployment.
