# EPIC-041 — Model registry and training runs in Admin App

**Status:** PARTIAL 2026-10-15 — model registry/lineage read and ML job runs (start/retry via existing `/ops`) built; no training-run records or cancel contract exist ([contract map](../superpowers/specs/2026-10-15-ml-admin-contract-map.md) §6)  
**Priority:** P1  
**Owner:** Admin App with ML service support  
**Spec:** [ML Admin Control Plane design](../superpowers/specs/2026-10-15-marksy-ml-admin-control-plane-design.md)

## Goal

Make model versions and supported training-run lifecycle observable and operable from the Admin App, preserving immutable provenance and existing training policy.

## Stories (5)

### S1 — Model registry list and detail
Show model family, immutable version, artifact identity/hash, horizon/target scope, training run, dataset lineage, created time and state from authoritative services.

**Acceptance:** identities and status match the service; missing metadata is explicit; no client-created model status or mutable artifact identity.

### S2 — Model version provenance
Expose links among model family/version, training run, dataset version, code/config version and evaluation evidence where available.

**Acceptance:** lineage is traceable and immutable identifiers are used; unsupported lineage fields are labelled unavailable rather than inferred.

### S3 — Training-run list and detail
Show queued/running/succeeded/failed/cancelled states, progress where genuinely reported, start/end times, logs or structured failure summaries permitted by policy.

**Acceptance:** state refreshes after navigation/reload; terminal states are stable; secrets and sensitive row-level data are not leaked.

### S4 — Start and manage supported training jobs
Build forms only for parameters supported by the existing service contract and approved retrain/research policies. Add cancel/retry only if the service defines those operations.

**Acceptance:** validate inputs before submission, require confirmation for consequential operations, prevent duplicate submissions, and display the server-confirmed run ID/result. Missing backend operations become explicit dependencies.

### S5 — Training lifecycle tests and recovery states
Cover duplicate submit, timeout-after-submit, authorization failure, worker/service unavailable, failure, retry, cancel and refresh/reconnect behavior.

**Acceptance:** UI does not claim a job started unless the server confirms it; users can recover the current state after a lost response; full relevant test suite passes.

## Guardrails

Training completion is not model approval. No production model is changed or published by this epic. Do not modify training windows, datasets, algorithms or model artifacts as part of UI work.
