# Marksy OS Next-Generation Roadmap

## Product Evolution
Marksy evolves through:
V1 — Capture
V2 — Organize
V3 — Understand
V4 — Learn
V5 — Act
V6 — Personal Intelligence

The objective is to strengthen the existing Marksy architecture and intelligence pipeline rather than continuously creating unrelated UI surfaces.

## EPIC-010 — Unified Event Intelligence
Build a normalized intelligence layer over captured notifications/events.
Requirements: normalize captured notifications/events; extract entities (person, company, merchant, bank, delivery, stock, app); extract amount, currency, date/time, reference/order/transaction IDs; calculate confidence score and importance score; cross-source deduplication; event threading; event lifecycle NEW → ACTIVE → RESOLVED → ARCHIVED.
Acceptance: stable normalized event contract; deduplication works; event threading works; confidence and importance are persisted; derived decisions are explainable; existing V2 behavior remains compatible; unit tests cover important cases.

## EPIC-011 — Smart Inbox
Build a useful intelligence-oriented inbox using real event data.
Requirements: priority inbox; needs action; important; informational; resolved; group related events; collapse duplicates; explain "Why am I seeing this?"; support read/resolved/snooze/archive/search.
Acceptance: uses real persisted events; threads displayed correctly; state survives app restart; no hardcoded production data.

## EPIC-012 — Personal Learning Engine
Create a learning layer based on actual user interaction.
Learn signals: apps, senders/contacts, recurring events, ignored notifications, opened notifications, actions resulting from notifications, personal importance, category confidence.
Requirements: learning history; user feedback; observable learning signals; deterministic rules remain authoritative where appropriate; user corrections override learned behavior; ability to disable/reset learning.
Acceptance: learning is observable and inspectable; corrections work; disable/reset works; no opaque uncontrolled behavior.

## EPIC-013 — Adaptive Notification Intelligence
Make notification importance/ranking adaptive. Consider: dynamic priority, context, time of day, repeated events, notification fatigue, important-vs-noisy prediction, confidence, personal history.
Requirements: explainable ranking signals; deterministic protection for explicitly high-priority events; deterministic test fixtures. Do not allow an AI model to silently override safety-critical deterministic rules.

## EPIC-014 — Action Engine
Create a common action framework. Support: open source, track, order, payment, remind, mark expected, mark resolved, report, ignore. Only expose actions genuinely supported by the source/event.
Requirements: action model; event-specific action discovery; action state; action history; failure/recovery; audit trail; permission/user interaction handling. Do not fake external actions.

## EPIC-015 — Cross-Source Context Graph
Build relationships between people, organizations, merchants, events, applications, dates/times, locations, transactions, deliveries, other relevant entities.
Requirements: stable entity references; cross-source correlation; duplicate entity merging; relationship confidence; context timeline; corrections.
Acceptance: cross-source relationships are real; confidence is persisted; corrections work; performance remains reasonable.

## EPIC-016 — Ask Marksy
Build a grounded local intelligence/query layer. Examples: "What payments did I make this week?", "What deliveries are coming tomorrow?", "What did Rahul send me?", "Which bills are due?", "What are the important things today?"
Requirements: intent detection; local retrieval; event retrieval; graph retrieval; structured answers; source references; drill-down; follow-up questions; no-result handling.
CRITICAL: the model must NOT invent database facts — answers must be grounded in retrieved Marksy data. AI enhances interpretation, not the source of truth. Support a local-model path through an abstraction/interface; do not hard-code one specific model vendor into the domain layer.

## EPIC-017 — Daily Briefing
Build deterministic daily intelligence. Support morning briefing, evening briefing, overnight summary. Include where applicable: important events, pending actions, upcoming events, financial summary, personalized highlights, "why this matters", drill-down. Same input state should produce deterministic core results. AI may improve wording but must not fabricate facts.

## EPIC-018 — Rules 2.0
Build a proper rules engine. Support: conditions, AND/OR, nested conditions, time, sender, app, category, entity, confidence, actions, priority, history, enable/disable, simulation/testing.
Requirements: versioned rules; idempotent execution; deterministic conflict resolution; audit trail; historical simulation. Do not create a rules UI with no functioning engine behind it.

## EPIC-019 — On-Device Intelligence
Create a provider-independent local AI abstraction. Requirements: local model interface; capability detection; model lifecycle; install/update handling where appropriate; model versioning; prompt/template management; structured JSON output; schema validation; confidence; deterministic fallback; latency metrics; privacy visibility. Architecture should allow models to be swapped without rewriting the application. Never send sensitive user data externally unless explicitly configured.

## EPIC-020 — Personal Memory
Build controlled personal memory: frequent contacts, merchants, recurring payments, recurring deliveries, locations, organizations, recurring events, preferences. Every memory entry supports: provenance, confidence, last observed, expiry where appropriate, correction, deletion/forget, source learning controls. Explicit user settings must override learned behavior. Privacy controls are mandatory.

## EPIC-021 — Connector Framework
Create a clean connector architecture. Target sources: WhatsApp, Gmail, SMS, Calendar, banking/payment notifications, delivery/shopping apps, other Android notification sources. Architecture target: Connector → Source Adapter → Normalizer → Classifier → Event Intelligence.
Requirements: common event contract; connector lifecycle/status; connector isolation; deduplication at ingestion; adding a connector should not require modifying core intelligence logic. Do not pretend a connector exists if it cannot actually operate.

## EPIC-022 — Marksy Health
Build runtime health/diagnostics tracking real runtime state: notifications captured/day, processing latency, duplicate count, classification failures, important-event detection, connector uptime, queue state, AI latency, AI failures, database/storage usage, battery/resource indicators where available, last successful event, data freshness.
Requirements: stale detection; actionable diagnostics; real runtime data; avoid exposing notification content unnecessarily.

## EPIC-023 — 30-Day Marksy Validation
Prepare Marksy for a real 30-day validation period. Track: captured events, classified events, classification accuracy, false classifications, duplicates, important-event detection, user interactions, corrections, rules, learning, AI latency, connector uptime, freshness, storage, battery/resource usage. Generate a "Marksy Intelligence Validation Report".
IMPORTANT: do NOT fabricate historical data — collect real metrics from actual runtime operation.
Acceptance: metrics are automatically collected; available by day and by source; failures are traceable; validation can operate for 30 days without manual reconstruction.

## Implementation Order
EPIC-010, 011, 012, 013, 014, 015, 016, 017, 018, 019, 020, 021, 022, 023 (in that order, but subject to repository reality — if part of an epic already exists, do not rebuild it, complete the missing portions).

## Per-Epic Constraint
Divide each epic's work into a maximum of 5 implementation stories (e.g. Story 1 domain model, Story 2 persistence, Story 3 processing/service layer, Story 4 UI/integration, Story 5 tests/hardening). Do not create many artificial tiny tasks just to claim progress.

## Required Workflow Per Epic
Step A Understand (inspect existing code: what exists, incomplete, reusable, needs refactor, test coverage) → Step B Plan (≤5 stories) → Step C Implement (domain/data layer → persistence → services → processing → ViewModels → UI; do not start with decorative UI) → Step D Test (focused tests, then `./gradlew :app:testDebugUnitTest`; fix failures rather than weakening tests) → Step E Review (backwards compatibility, lifecycle handling, persistence, concurrency, null/error cases, process death, configuration changes, duplicate processing, migration safety, security/privacy, performance) → Step F Commit (focused commit, descriptive message) → Step G Continue (move to next incomplete epic without waiting for confirmation).

## Architectural Principles
- Local-first: user data stays local unless explicitly configured otherwise.
- AI is not the source of truth: AI classifies/summarizes/extracts/explains/ranks/interprets; deterministic application data remains authoritative.
- Explainability: retain enough information to explain why an event was important/categorized/ranked, why an action was suggested, which signals contributed.
- Provenance: derived information should identify its source where practical (e.g. `derivedFromEventIds`).
- Determinism: core processing deterministic wherever practical; AI behind interfaces.
- Backward compatibility: existing stored data and app behavior must remain compatible; use migrations where necessary.
- No fake data: never hard-code fake notifications, transactions, AI results, health metrics, or validation results to make a screen look complete.
- No secrets: never commit API keys, tokens, passwords, private credentials, personal notification content, production secrets.

## Database/Migrations
If schema changes are required: create proper migrations, preserve existing data, test migrations, handle old installations, avoid destructive migration unless absolutely necessary. Never delete user data simply to make a migration pass.

## Android Reliability
Pay attention to: process death, background execution, notification listener lifecycle, permissions, accessibility lifecycle if used, database concurrency, duplicate events, WorkManager/job lifecycle, battery impact, configuration changes, app restarts. Marksy is intended to run continuously — a feature that only works while a screen is open is not sufficient when background operation is required.

## Testing Standard
Every meaningful feature requires tests. Prioritize: unit, repository, parser, normalization, deduplication, ranking, rules-engine, learning, retrieval, migration, error-handling tests. Run `./gradlew :app:testDebugUnitTest` frequently. Never delete failing tests, weaken assertions merely to pass, skip tests without documenting why, or replace real implementation with mocks solely to avoid work.

## Genuine Blockers Only
Stop only for: mandatory external credential, unavailable external service, destructive migration requiring a product decision, security-critical ambiguity, repository corruption, impossible requirement conflict. Difficulty, large refactors, failing tests, unfamiliar code, missing helper/abstraction, UI integration work, DB migration, or a reasonable design choice are NOT blockers.

## Git Discipline
Check `git status` and `git log --oneline --decorate -20` before changing anything. Never force-push, reset someone else's work, discard unrelated working changes, rewrite history, or commit secrets. Preserve pre-existing changes. Use focused commits. Inspect `git diff` and `git status` before every commit.

## Do Not Pretend Completion
An epic is complete only when its acceptance criteria are substantially satisfied — not because a class/interface/screen exists, tests only test mocks, data is hardcoded, a button exists, or a model returns placeholder JSON. If only part is complete, explicitly mark it PARTIAL.

## UI Priority
Do not spend all your effort on UI redesign. Priority order: domain model, persistence, intelligence pipeline, processing, learning, actions, retrieval, AI abstraction, health/metrics, UI integration. Extend existing V2 screens rather than replace them unless there's a strong architectural reason.

## Performance
Avoid unnecessary DB scans, repeated full-table processing, blocking main-thread work, loading large notification histories into memory, expensive AI calls per event, duplicate processing. Prefer incremental processing, indexed queries, batching, caching, background workers, bounded queues, lazy retrieval.

## Security/Privacy
Treat notification content as sensitive. Do not log full notification content unnecessarily, send notification contents to external services by default, persist unnecessary sensitive data, expose secrets in debug output, or place sensitive info into analytics. If external AI is supported, make external transmission explicit and configurable.

## Classification vocabulary
When inspecting existing code for each epic, classify it as COMPLETE / PARTIAL / MISSING / BROKEN and report accordingly.

---

# Appendix A — Original roadmap spec (commit 86204f5, preserved unchanged)

The text below is the version of this file that was committed to `main` in 86204f5. It is kept verbatim so no scope detail is lost; where the two differ, the sections above are the execution spec.

# Marksy OS — Next Roadmap Specs

This document defines the next product roadmap after Marksy OS V2. Each EPIC is intentionally bounded and should be implemented incrementally. Keep each EPIC to a maximum of 5 implementation prompts/stories.

## Product direction

**V1 — Capture → V2 — Organize → V3 — Understand → V4 — Learn → V5 — Act → V6 — Personal Intelligence**

Do not expand the UI unnecessarily. Prefer strengthening the existing V2 surfaces and underlying intelligence pipeline.

---

## EPIC-010 — Unified Event Intelligence

**Goal:** Transform captured notifications/events into normalized, structured intelligence.

### Scope
1. Event normalization pipeline.
2. Entity extraction: person, company, merchant, bank, delivery, stock, app.
3. Amount/currency extraction.
4. Date/time extraction.
5. Reference/order/transaction ID extraction.
6. Event confidence score.
7. Event importance score.
8. Cross-source event deduplication.
9. Event threading.
10. Event lifecycle: NEW → ACTIVE → RESOLVED → ARCHIVED.

### Acceptance criteria
- Raw source events are converted into a stable normalized event contract.
- The same real-world event received from multiple notifications can be deduplicated/threaded.
- Confidence and importance are persisted and explainable.
- Existing notification capture behavior remains backward compatible.
- Unit tests cover normalization, extraction, deduplication and lifecycle transitions.

---

## EPIC-011 — Smart Inbox

**Goal:** Turn captured notifications into a decision-oriented inbox.

### Scope
1. Priority inbox.
2. Needs Action.
3. Important.
4. Informational.
5. Resolved.
6. Related-event grouping.
7. Duplicate collapsing.
8. “Why am I seeing this?” explanation.
9. Mark read/resolved.
10. Snooze.
11. Archive.
12. Search across captured events.

### Acceptance criteria
- Inbox is driven by real event data, not hardcoded data.
- Related notifications appear as a single thread where appropriate.
- Users can resolve, snooze and archive events.
- Existing V2 navigation remains intact.
- State survives app restart.

---

## EPIC-012 — Personal Learning Engine

**Goal:** Learn user-specific importance and interaction patterns from Marksy usage.

### Scope
1. Learn frequently used apps.
2. Learn important senders/contacts.
3. Learn recurring events.
4. Learn ignored notifications.
5. Learn opened notifications.
6. Learn events that result in actions.
7. Personal importance score.
8. Personal category confidence.
9. Learning history.
10. User correction feedback loop.

### Acceptance criteria
- Learning is based on observable user interactions, not hidden assumptions.
- Learned signals can be inspected/debugged.
- Personal scores influence prioritization without replacing deterministic rules.
- User corrections can override learned behavior.
- Learning can be disabled/reset.

---

## EPIC-013 — Adaptive Notification Intelligence

**Goal:** Replace purely static prioritization with context-aware ranking.

### Scope
1. Dynamic priority.
2. Context-aware ranking.
3. Time-of-day awareness.
4. Repeated-event detection.
5. Notification fatigue detection.
6. Important-vs-noisy prediction.
7. Confidence-based classification.
8. Personal-history signals.

### Acceptance criteria
- Ranking combines event data, context and learned signals.
- Deterministic high-priority events cannot be silently downgraded by weak learning signals.
- Every ranking decision has an explainable signal set.
- Model/ranking behavior is testable with deterministic fixtures.

---

## EPIC-014 — Action Engine

**Goal:** Convert important events into actionable recommendations.

### Scope
1. Action model.
2. Action discovery per event type.
3. Open source app/page.
4. Track/order/payment actions where source permits.
5. Remind me.
6. Mark expected/resolved.
7. Report/ignore.
8. Action execution state and history.

### Acceptance criteria
- Events expose only actions appropriate to their source/type.
- Failed actions have clear recovery states.
- Actions are auditable.
- No action executes without the required user interaction/permission.

---

## EPIC-015 — Cross-Source Context Graph

**Goal:** Connect information from different sources into a unified context model.

### Scope
1. Entity graph.
2. Person graph.
3. Organization graph.
4. Event relationships.
5. Time relationships.
6. Location relationships.
7. Cross-source correlation.
8. Duplicate entity merging.
9. Relationship confidence.
10. Context timeline.

### Acceptance criteria
- Entities can be referenced consistently across source adapters.
- Related events from different sources can be correlated.
- Correlation confidence is persisted.
- Incorrect correlations can be corrected.
- Graph queries remain performant on realistic local datasets.

---

## EPIC-016 — Ask Marksy

**Goal:** Make the existing Ask surface query Marksy's local knowledge.

### Example queries
- “What payments did I receive this week?”
- “Show deliveries expected tomorrow.”
- “What did Rahul send me about the project?”
- “Which bills are due this month?”
- “What important things happened today?”

### Scope
1. Intent detection.
2. Local retrieval.
3. Event/graph retrieval.
4. Structured answer generation.
5. Source references/drill-down.
6. Follow-up questions.
7. No-result handling.

### Acceptance criteria
- Answers are grounded in local Marksy data.
- Retrieved source events can be inspected.
- The LLM cannot invent database facts.
- Queries work without a cloud model when the configured local intelligence path supports them.
- Sensitive source data is not sent externally unless explicitly configured.

---

## EPIC-017 — Daily Briefing

**Goal:** Turn the existing Daily Digest foundation into a useful personalized daily briefing.

### Scope
1. Morning briefing.
2. Evening summary.
3. Overnight summary.
4. Important events.
5. Pending actions.
6. Upcoming events.
7. Financial summary.
8. Personalized highlights.
9. “Why this matters.”
10. Drill-down into source events.

### Acceptance criteria
- Briefing is generated from real Marksy data.
- Duplicate events are not repeated across sections.
- Important/pending items link to the underlying event/thread.
- Empty sections are omitted or shown as meaningful empty states.
- Generation is deterministic for the same input dataset.

---

## EPIC-018 — Rules 2.0

**Goal:** Evolve Rules into a robust local automation engine.

### Scope
1. Conditions.
2. AND / OR.
3. Nested conditions.
4. Time conditions.
5. Sender conditions.
6. App conditions.
7. Category conditions.
8. Entity conditions.
9. Confidence conditions.
10. Actions.
11. Rule priority.
12. Rule execution history.
13. Enable/disable.
14. Rule simulation/testing.

### Acceptance criteria
- Rules are versioned and persisted.
- Execution is idempotent.
- Rule conflicts have deterministic resolution.
- Every execution has an audit record.
- Users can simulate a rule against historical events before enabling it.

---

## EPIC-019 — On-Device Intelligence

**Goal:** Make local AI a first-class Marksy OS capability.

### Scope
1. Local model abstraction.
2. Model capability detection.
3. Model lifecycle/install management.
4. Model versioning.
5. Prompt/template management.
6. Structured JSON output.
7. Confidence scoring.
8. Local deterministic fallback.
9. Inference latency metrics.
10. Privacy dashboard.

### Acceptance criteria
- Intelligence providers are abstracted behind a stable interface.
- Local models can be swapped without changing business logic.
- Structured outputs are schema validated.
- Failure/timeout falls back safely.
- The user can see which processing is local vs remote.
- No sensitive data is sent remotely without explicit configuration.

---

## EPIC-020 — Personal Memory

**Goal:** Build controlled long-term memory from useful user data.

### Scope
1. Frequent contacts.
2. Frequent merchants.
3. Recurring payments.
4. Recurring deliveries.
5. Common locations.
6. Important organizations.
7. Regular events.
8. User-created preferences.
9. Memory source.
10. Confidence.
11. Last observed.
12. Expiry.
13. User correction.
14. Delete/forget.
15. Disable learning for a source.

### Acceptance criteria
- Every memory has provenance.
- Memories can expire.
- Users can correct/delete memories.
- Memory does not silently override explicit user settings.
- Sensitive information is handled according to the privacy model.

---

## EPIC-021 — Connector Framework

**Goal:** Create one extensible architecture for source integrations.

### Initial connectors
1. WhatsApp.
2. Gmail.
3. SMS.
4. Calendar.
5. Banking/payment notifications.
6. Delivery/shopping apps.
7. Other Android notification sources.

### Architecture

Connector → Source Adapter → Normalizer → Classifier → Event Intelligence

### Acceptance criteria
- All connectors produce the same normalized event contract.
- Connector lifecycle/status is visible.
- Connector failures are isolated.
- Duplicate events are prevented at the ingestion boundary.
- Adding a connector does not require changing core intelligence logic.

---

## EPIC-022 — Marksy Health

**Goal:** Provide operational visibility into Marksy itself.

### Scope
1. Capture health.
2. Accessibility service health.
3. Notification listener health.
4. Database health.
5. Classification health.
6. Learning status.
7. Connector status.
8. Last event timestamp.
9. Events captured/day.
10. Classification latency.
11. Duplicate rate.
12. Failed processing.
13. Queue/backlog.
14. AI latency/failures.
15. Storage growth.
16. Battery/resource indicators.

### Acceptance criteria
- Health reflects real runtime state.
- Stale components are clearly identified.
- Failures provide actionable diagnostics.
- Health data does not expose notification contents unnecessarily.

---

## EPIC-023 — 30-Day Marksy Validation

**Goal:** Validate Marksy OS as a continuously operating system rather than only a passing test suite.

### Metrics
1. Events captured.
2. Events successfully classified.
3. Classification accuracy.
4. False classifications.
5. Duplicate events.
6. Important events detected.
7. User interactions.
8. User corrections.
9. Rule executions.
10. Learning events.
11. AI inference latency.
12. Connector uptime.
13. Data freshness.
14. Storage growth.
15. Battery/resource impact.

### Deliverable
Generate a Marksy Intelligence Validation Report covering:
- Capture reliability.
- Classification quality.
- Deduplication quality.
- Importance ranking quality.
- Learning behavior.
- Connector reliability.
- AI performance.
- Storage growth.
- Resource impact.

### Acceptance criteria
- Metrics are collected automatically.
- Results can be reviewed by day and by source.
- Failures are traceable to the originating pipeline stage.
- Validation can run for 30 days without manual data reconstruction.

---

# Implementation rules for Claude

1. Before implementing an EPIC, inspect the existing code and tests and identify what is already implemented.
2. Do not rebuild functionality that already exists.
3. Preserve existing V2 screens and navigation unless the EPIC explicitly requires a change.
4. Prefer domain/data-layer changes before UI changes.
5. Keep every EPIC to a maximum of 5 implementation prompts/stories.
6. Every implementation must include tests.
7. Do not use hardcoded production data.
8. Preserve backward compatibility for existing captured events.
9. Prefer deterministic behavior for core event processing.
10. Keep AI behind interfaces so local/cloud providers can be swapped.
11. Store provenance for derived intelligence.
12. Make important decisions explainable.
13. Do not send private notification/event content externally unless explicitly configured.
14. Update documentation when contracts or architecture change.
15. After each EPIC, run the complete unit test suite and report the result.
16. Do not start the next EPIC until the current EPIC has a clear acceptance result.

# Recommended implementation order

EPIC-010 → EPIC-011 → EPIC-012 → EPIC-013 → EPIC-014 → EPIC-015 → EPIC-016 → EPIC-017 → EPIC-018 → EPIC-019 → EPIC-020 → EPIC-021 → EPIC-022 → EPIC-023

The immediate next implementation target is **EPIC-010 — Unified Event Intelligence**.
