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
