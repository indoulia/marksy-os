# ML Admin Control Plane — repository audit and contract map

**Date:** 2026-10-15
**Epics:** EPIC-040 S1/S2 (feeds EPIC-041..044)
**Design:** [ML Admin Control Plane](2026-10-15-marksy-ml-admin-control-plane-design.md)

## 1. Repository reality (S1)

| Repo | Role | Stack | Baseline |
|---|---|---|---|
| `indoulia/admin-app` | **The Admin App** | React 18 + Vite + TanStack Query + react-router 6; vitest + Testing Library + MSW | `main` 2c51c61; `npm test / typecheck / lint / build` |
| `indoulia/marksy-api` | **The ML service boundary** (FastAPI, `/api/v1`) | Python, SQLAlchemy, Alembic | `main` cbe0c59; `python -m pytest -n 6 --dist loadfile` |
| `marksy-ml` (local) | Separate research work | — | **Out of scope by user instruction (2026-10-15).** Not a data source. |
| `marksy-os` | Android app + coordination docs | — | Not the Admin App |

- Admin auth: `POST /api/v1/auth/login` (password grant) → opaque bearer; every ML route needs scope `admin` (`require_scope(SCOPE_ADMIN)`) plus `require_active_session` (bearer only, API keys refused). Read-only sessions cannot POST/PUT/PATCH/DELETE (`forbid_read_only_mutation`). The UI never sends an actor; the server takes it from the session.
- `marksy_ml` (registry store, `ml_lab`, selection rounds, winner lock) was removed from marksy-api main by PR #94 and never deployed. The ML Postgres was never deployed.
- What *is* in production: core tables written by existing jobs (SPG-001 gate, shadow scorer, learning cycle, regression/drift/trust monitors, market-data validation, DQ contracts) and the orchestration ledger.

## 2. Capability status before this work

| Area | Status | Evidence |
|---|---|---|
| Dashboard | PARTIAL | `/ops/overview`, `/ops/health`, `/learning/status`; SPG state only on public `/predictions/active` meta |
| Models | PARTIAL | `model_artefacts` (sha256) inside `/learning/status`; `model_versions` has no writer (`scripts/train_challenger.py` missing) |
| Training Runs | MISSING (no training-run table) | ML jobs exist only as `orchestration_executions` rows |
| Model Lab | PARTIAL | `/learning/experiments`; shadow scores and champion/challenger detail unexposed |
| Selection | MISSING | No selection-round / winner-lock tables on main; only SPG monthly decisions |
| Evaluation | MISSING | `horizon_regime_trusts`, `horizon_usefulness_reports`, `prediction_calibration_drifts` unexposed |
| Leaderboard | MISSING | No leaderboard table |
| Datasets | MISSING (no registry) | Identity only as `dataset_version`/`dataset_sha256` on SPG rows and `selection_benchmark_sessions` |
| Data Quality | PARTIAL | `/system/data-freshness`; `dataset_validation_runs`, `data_quality_contract_evaluations` unexposed |
| Promotion & SPG | PARTIAL | Gate logic complete and fail-closed; API exposes only the summary |
| Audit Trail | PARTIAL | `orchestration_executions.requested_by` + `job_execution_events`; no general admin audit log; request ids not persisted |

## 3. Contract map

All new routes are **additive, GET-only**, under `/api/v1/admin/ml`, with `require_scope(SCOPE_ADMIN)` on the router and `require_active_session` on each route, over existing tables. No new tables or migrations. No writes. Envelope `{data, meta}`.

| UI screen / action | Service operation | Side effects | Status after |
|---|---|---|---|
| Dashboard: gate state | `GET /admin/ml/spg/pairs` (new; wraps authoritative `publish_gate_meta`) | none | COMPLETE |
| Dashboard: models | `GET /admin/ml/models` (new) | none | COMPLETE |
| Dashboard: active ML runs | `GET /ops/jobs/active` (existing), filtered to ML operations | none | COMPLETE |
| Dashboard: learning health | `GET /learning/status` (existing) | none | COMPLETE |
| Dashboard: data quality | `GET /admin/ml/data-quality` (new) | none | COMPLETE |
| Dashboard: service health | `GET /ops/health` (existing) | none | COMPLETE |
| Models list / detail | `GET /admin/ml/models`, `GET /admin/ml/models/{modelVersion}` (new) | none | COMPLETE for recorded fields; training-run link unavailable |
| Training runs list / detail | `GET /ops/operations?operation=X`, `GET /ops/jobs/executions/{id}` (existing) | none | PARTIAL (job executions, not training-run records) |
| Training: start | `POST /ops/jobs/{name}/run` (existing; LEARNING_CYCLE, RATING_ENGINE only) | starts the job; server dedupes live runs, cooldown 429, actor from session, audited in `job_execution_events` | COMPLETE for runnable jobs |
| Training: retry | `POST /ops/jobs/executions/{id}/run-again` (existing) | new execution linked by `parent_execution_id`, `RUN_AGAIN` event | COMPLETE for runnable jobs |
| Training: cancel | none | — | MISSING (no cancel contract) |
| Model Lab: experiments | `GET /learning/experiments` (existing) | none | COMPLETE |
| Model Lab: champion/challenger | `GET /learning/summary` → `championChallenger` (existing) | none | COMPLETE |
| Model Lab: shadow runs | `GET /admin/ml/shadow` (new; per-session aggregates only) | none | COMPLETE |
| Selection: gate rounds | `GET /admin/ml/spg/decisions`, `GET /admin/ml/spg/decisions/{id}` (new) | none | PARTIAL (no selection-round / winner-lock tables) |
| Evaluation by horizon/regime | `GET /admin/ml/evaluation` (new) | none | COMPLETE |
| Leaderboard | client-side, descriptive ordering within one comparability group from `/learning/experiments` and `/admin/ml/spg/decisions` | none | PARTIAL (no server leaderboard) |
| Datasets | `GET /admin/ml/datasets`, `GET /admin/ml/datasets/{datasetSha256}` (new; derived from immutable SPG/benchmark records) | none | PARTIAL (no dataset registry) |
| Data quality | `GET /admin/ml/data-quality`, `GET /admin/ml/data-quality/validation-runs/{id}` (new) | none | COMPLETE for recorded checks |
| Promotion eligibility / SPG | `GET /admin/ml/spg/pairs`, `GET /admin/ml/spg/decisions/{id}`, `GET /admin/ml/promotions` (new) | none | COMPLETE read-only |
| Promotion handoff action | none | — | MISSING; UI stays read-only |
| Audit trail | `GET /admin/ml/audit` (new; union of server-written records) | none | PARTIAL (no admin action log) |

Request id: every response carries `meta.requestId`; the UI shows it on errors. It is not persisted server-side.

## 4. Response contracts (camelCase)

Types below are the wire contract shared by marksy-api schemas (`api/schemas/admin_ml.py`) and admin-app (`src/api/mlTypes.ts`). Timestamps are ISO-8601 UTC; decimals arrive as JSON strings or numbers (Pydantic `Decimal`), so the UI accepts `number | string`.

```
MlArtefactRef      { id, sha256, featureVersion, registeredAt, writtenBy }
MlRegistryEntry    { modelName, version, featureVersion, status, createdAt }
MlModelSummary     { modelVersion, publicationCapability, gatedHorizons: int[], artefactCount,
                     latestArtefact: MlArtefactRef|null, registry: MlRegistryEntry|null }
MlArtefactView     { id, sha256, byteSize, featureVersion, formatVersion, writtenBy, registeredAt,
                     superseded: bool, manifest: object|null }          # never the payload bytes
MlRegressionCheckView { id, verdict, baselineWindowLabel, monitoringWindowLabel, rollbackTriggered, checkedAt }
MlModelDetail      { summary: MlModelSummary, artefacts: MlArtefactView[], gateDecisions: MlGateDecisionSummary[],
                     promotions: MlPromotionEventView[], regressionChecks: MlRegressionCheckView[],
                     lineageUnavailable: string[] }                    # e.g. "TRAINING_RUN", "EVALUATION_RUN"
MlGatePairs        { pairs: PublishGateEntry[], gateRuleVersion: "SPG-001", evaluatedAt }
                     # PublishGateEntry = existing api/schemas/predictions_active.py shape, unchanged
MlStageStats       { trades, meanExcess, ciLow, ciHigh, result, reasons: string[] }
MlGateDecisionSummary { id, decisionUuid, modelVersion, horizonSessions, decision, primaryStage, primaryReason,
                     decidedAt, validUntil, gateVersion, datasetVersion, datasetSha256, artefactSha256,
                     holdoutMonth, walkForward: MlStageStats, heldOut: MlStageStats|null }
MlGateDecisionDetail  MlGateDecisionSummary + { universeVersion, featureVersion, configSha256, codeVersion,
                     holdoutLabel, holdoutConsumedAt, reasons: string[], isLatestForPair: bool,
                     currentAuthorization: { state, reason, decisionId }|null,     # only when isLatestForPair
                     tradeDispositions: { stage, disposition, count }[] }          # aggregates only
MlShadowSessionView { modelVersion, artefactSha256, horizonSessions, sessionDate, scoredCount, selectedCount,
                     suppressedCount, suppressionReasons: {reason: count}, lastScoredAt }
MlEvaluationView   { horizonRegime: MlHorizonRegimeRow[], horizonUsefulness: MlHorizonUsefulnessRow[],
                     calibrationDrift: MlCalibrationDriftRow[] }       # latest computation per model (and horizon)
MlHorizonRegimeRow { modelVersion, segmentType, horizonDays, regime, sampleCount, successRate,
                     successRateStandardError, verdict, isLowTrust, computedAt, ruleVersion }
MlHorizonUsefulnessRow { modelVersion, horizonDays, sampleCount, avgRiskAdjustedRatio, usefulRate, verdict, computedAt, ruleVersion }
MlCalibrationDriftRow { modelVersion, baselineWindowLabel, monitoringWindowLabel, baselineSampleCount,
                     monitoringSampleCount, calibrationDrift, calibrationDriftDetected, distributionDrift,
                     distributionDriftDetected, verdict, checkedAt, ruleVersion }
MlDatasetConsumer  { kind: "SPG_DECISION"|"PROMOTION_DECISION", id, modelVersion, horizonSessions|null, decision, decidedAt }
MlDatasetView      { datasetVersion, datasetSha256, purpose, firstSession, lastSession, sessionCount, horizons: int[],
                     benchmarkableSessions, universeVersions: string[], consumers: MlDatasetConsumer[],
                     metadataUnavailable: string[] }                   # e.g. "SCHEMA", "DECLARED_RANGE"
MlDatasetSessionRow { sessionDate, horizonSessions, eligibleCount, labelledCount, unresolved: object, excluded: object, benchmarkable }
MlDatasetDetail    { dataset: MlDatasetView, sessions: MlDatasetSessionRow[] }
MlValidationRunView { id, fromTimestamp, toTimestamp, status, recordCount, issueCount, createdAt }
MlContractSummary  { contractKey, rulesetVersion, latestAsOf, freshness, verdictCounts: {verdict: count},
                     blockingCount, failingScopes: { scopeType, scopeKey, verdict, reason, severity, enforcement }[] }
MlDataQualityView  { status: "PASSING"|"FAILING"|"STALE"|"UNKNOWN", staleAfterHours, latestValidationAt,
                     validationFreshness: "FRESH"|"STALE"|"UNKNOWN", validationRuns: MlValidationRunView[],
                     contracts: MlContractSummary[] }
MlValidationRunDetail MlValidationRunView + { report: object|null }
MlPromotionEventView { kind: "MODEL_PROMOTION"|"SAFE_PROMOTION", id, candidate, baseline, decision, reason,
                     decidedAt, approver, ruleVersion, datasetVersion }
MlAuditEntry       { id, source, occurredAt, actor, actorKind: "USER"|"SYSTEM"|"SCHEDULER", action,
                     objectType, objectId, result, correlationId }
```

Lists: `/admin/ml/spg/decisions` and `/admin/ml/audit` are cursor-paginated (`meta.nextCursor`, `limit`); filters `modelVersion`, `horizonSessions` (decisions) and `source` (audit).

## 5. Safety rules the contract encodes

- **SPG-001 stays authoritative.** `spg/pairs` returns `publish_gate_meta` output verbatim; `currentAuthorization` calls `authorize_publication`. The UI never computes eligibility and has no promote/handoff control.
- **Held-out integrity.** Only recorded gate decisions (holdout already consumed) are read. No per-trade or per-stock rows: trade dispositions and shadow scores are aggregates. TG-001 tables and `TRADE_GEOMETRY` are excluded until its exam is decided.
- **TG-001 embargo.** SPG decisions for holdout month 2026-11 are withheld until 2026-12-12 00:00 UTC (after the November exam): excluded from lists, lineage and audit; their detail and `/spg/pairs` return 409 `MRA_EMBARGOED`. The gate outcome itself reveals the held-out result, so it is embargoed too.
- **No fabricated values.** Missing lineage is listed in `lineageUnavailable` / `metadataUnavailable`; the UI renders "not recorded", never a guess.
- **Stale is never healthy; a failure is never hidden.** Data-quality `status`: no checks recorded → UNKNOWN; latest validation run FAILED, any REQUIRED violation or any blocking failure → FAILING (outranks STALE); a contract check that missed an expected trading session → STALE; else PASSING. Freshness is trading-calendar aware. Validation-run freshness is reported separately (the script has no CronJob).
- **Mutations** are only the existing `/ops` job start/run-again (server dedupe, cooldown, session actor, event audit). The UI confirms first, disables while pending, never auto-retries, and after a timeout re-reads active runs instead of resubmitting.

## 6. Remaining backend dependencies (not built here)

1. Training-run records with dataset/code/config lineage (needs a training orchestrator; `scripts/train_challenger.py` is referenced but missing).
2. Training-run cancel operation.
3. Selection rounds, winner lock and handoff (EPIC-4 §7A) — not on main.
4. Dataset registry with declared purpose, range, universe and schema.
5. Server-side research leaderboard with comparability rules.
6. Promotion/handoff operation that routes through SPG-001.
7. General admin action audit log with persisted request ids (today only ops triggers and client revocation record an actor).
