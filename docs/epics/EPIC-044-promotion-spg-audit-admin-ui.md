# EPIC-044 — Promotion/SPG governance and audit trail in Admin App

**Status:** PARTIAL 2026-10-15 — SPG-001 state, decisions and promotions read-only (no handoff operation exists, so none is offered); audit from server-written records only (no admin action log) ([contract map](../superpowers/specs/2026-10-15-ml-admin-contract-map.md) §6)  
**Priority:** P1  
**Owner:** Admin App with ML service support  
**Spec:** [ML Admin Control Plane design](../superpowers/specs/2026-10-15-marksy-ml-admin-control-plane-design.md)

## Goal

Expose model eligibility, promotion evidence and administrative audit history while preserving the existing SPG-001 production boundary. This epic must make governance clearer, not provide a bypass.

## Stories (5)

### S1 — Promotion eligibility overview
Show the exact model family/version/artifact and the authoritative eligibility/gate state with evidence links and as-of time.

**Acceptance:** UI reflects server-authoritative state only; no state is inferred from training completion, leaderboard rank, validation badge or selection winner.

### S2 — SPG-001 decision visibility
Display the existing SPG-001 decision, its inputs/evidence reference, status and reason where the service exposes them. If unavailable, record a backend contract dependency.

**Acceptance:** no client-side implementation of gate logic; no direct writes to gate tables; no way to mark a model production-eligible from the UI.

### S3 — Controlled handoff actions
If a supported and authorized service operation exists, present the documented handoff/decision workflow with object/version confirmation and explicit consequences. Otherwise keep the UI read-only.

**Acceptance:** no action can bypass SPG-001; server authorization and validation are mandatory; result is confirmed by the service; destructive or consequential actions require confirmation.

### S4 — Audit trail
Show server-authored actor, timestamp, operation, object/version, result and correlation/request ID for privileged operations where available.

**Acceptance:** actor is not trusted from client input; audit entries are not editable/deletable through this UI; filters and pagination do not lose provenance.

### S5 — Governance/security tests
Test denied roles, stale eligibility, changed artifact/version, repeated submissions, timeout-after-submit, audit gaps and service failures.

**Acceptance:** fail closed for consequential operations; no false success; missing audit/authorization capabilities are explicit blockers; production deployment remains separately approved.

## Guardrails

No production promotion, deployment, publish-gate changes, model selection changes, or mutation of production state without separate explicit approval.
