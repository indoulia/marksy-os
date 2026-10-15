# EPIC-042 — Learning and research workspace

**Status:** BLOCKED ON EPIC-040 CONTRACT AUDIT COMPLETION  
**Priority:** P1  
**Owner:** Admin App with ML service support  
**Spec:** [ML Admin Control Plane design](../superpowers/specs/2026-10-15-marksy-ml-admin-control-plane-design.md)

## Goal

Expose existing learning/research workflows—experiments, shadow runs, selection rounds, evaluation and comparative leaderboard—in one auditable workspace. This epic is UI and integration only; it does not introduce a new model algorithm or change selection behavior.

## Stories (5)

### S1 — Model Lab: experiments and candidates
Show registered candidates, experiment/run IDs, version, horizon, status, evidence availability and provenance.

**Acceptance:** candidate data comes from the service; incomplete or provisional evidence is visibly distinguished from validated evidence.

### S2 — Shadow-run inspection
List and inspect supported shadow runs, input/model version, as-of window, coverage, exclusions and result artifact.

**Acceptance:** shadow output is labelled research-only; no shadow result changes production ranking or published calls.

### S3 — Selection-round workflow
Display candidate set, criteria/version, selection result, winner lock and handoff state if the current service supports them. State clearly that selection is not production approval.

**Acceptance:** every decision is tied to an immutable candidate and round; no UI action edits or bypasses selection policy.

### S4 — Evaluation and leaderboard
Display evaluation metrics by horizon and relevant regime, sample size, uncertainty, coverage, data exclusions and evidence grade. Separate skill, economic outcome, risk/stability and operational health when those fields exist.

**Acceptance:** comparisons are apples-to-apples and as-of scoped; insufficient samples are not shown as confident wins; leaderboard ranking cannot promote a model.

### S5 — Research integrity and tests
Test filters, missing evidence, stale results, incompatible horizons/datasets, permission failures and service errors. Verify protected/held-out outcome windows remain excluded until their authorized release.

**Acceptance:** no look-ahead data is exposed early; no fabricated values; research views are read-only unless a documented service operation explicitly permits a mutation.

## Guardrails

No production selection, publish-gate, ranking or prediction behavior changes. No new experiment runs unless explicitly supported by an approved backend contract and user authorization.
