# EPIC-043 — Datasets and data quality in Admin App

**Status:** BLOCKED ON EPIC-040 CONTRACT AUDIT COMPLETION  
**Priority:** P2  
**Owner:** Admin App with ML service support  
**Spec:** [ML Admin Control Plane design](../superpowers/specs/2026-10-15-marksy-ml-admin-control-plane-design.md)

## Goal

Make dataset versions, lineage and data-quality evidence inspectable without direct database access or local copies of production data.

## Stories (5)

### S1 — Dataset registry
Show dataset ID/version, declared purpose, date range, universe, feature/schema version, source/provenance and lifecycle state from the authoritative service.

**Acceptance:** immutable versions are distinguishable; missing metadata is explicit; UI does not invent a dataset or infer a lineage.

### S2 — Dataset lineage and use
Show which training runs, model versions and evaluations consumed each dataset version, where supported.

**Acceptance:** lineage follows authoritative identifiers; no data is duplicated merely to render the page.

### S3 — Data quality summary
Show existing checks for freshness, coverage, missingness, duplicates, integrity and validation outcomes, including as-of timestamps and failed-check details.

**Acceptance:** only actual recorded checks/results are shown; stale/unknown is not rendered as healthy.

### S4 — Validation detail and failure triage
Allow admins to inspect structured validation failures and safe diagnostic context. Link to the owning dataset/run and supported remediation workflow.

**Acceptance:** no raw production dataset export or sensitive row content is required; remediation is offered only if a supported service operation exists.

### S5 — Quality and access tests
Test empty registry, stale data, failed validation, partial coverage, service timeout and authorization restrictions.

**Acceptance:** error and unknown states are clear; no fabricated data; the relevant test suite passes.

## Guardrails

Do not create a parallel dataset registry, copy production datasets to laptops, change training slices/features, or run training as part of this UI epic.
