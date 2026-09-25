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

## EPIC-024 — Marksy Market Data Gateway

**Goal:** Make Marksy the normalized market-data gateway for MarksyOS, using Upstox directly wherever the provider is authoritative and available.

### Scope
1. Upstox authentication/configuration boundary — credentials remain server-side and are never shipped in MarksyOS.
2. Current market status.
3. Quotes/LTP/OHLC/volume for supported instruments.
4. NIFTY, BANK NIFTY and relevant index data.
5. Gainers, losers and most-active views.
6. Instrument identity/lookup and stable symbol-to-instrument mapping.
7. Data-source attribution and freshness metadata.
8. REST fallback/reconciliation for live data.
9. Marksy API contracts for MarksyOS.
10. Reuse existing Marksy market/history data rather than creating a parallel data store.

### Acceptance criteria
- MarksyOS consumes market data through Marksy APIs; Upstox credentials are not present in the app.
- Upstox is used as the primary market source wherever supported.
- Responses identify source, timestamp/as-of time, market status and freshness.
- Stale/unavailable data is represented explicitly rather than fabricated.
- Existing Marksy historical data remains compatible.

## EPIC-025 — Real-Time Market Stream

**Goal:** Provide near-real-time market updates through Marksy while keeping Upstox WebSocket details behind the API boundary.

### Scope
1. Upstox WebSocket market-data adapter.
2. Subscription management for configured instruments/watchlists.
3. Normalized Marksy market events.
4. Reconnect/backoff and subscription recovery.
5. REST reconciliation after reconnect or detected gaps.
6. Marksy WebSocket/SSE delivery to MarksyOS where appropriate.
7. Stream health and last-update metrics.
8. Bounded buffering and duplicate-event protection.

### Acceptance criteria
- Live updates reach MarksyOS without exposing Upstox credentials.
- Disconnects recover without creating duplicate market events.
- Gaps are reconciled from REST data where possible.
- Stream freshness and health are observable.
- The app remains functional with live streaming unavailable by using the latest valid REST snapshot.

## EPIC-026 — Stock Intelligence & Prediction Pipeline

**Goal:** Turn Marksy's existing market data, historical observations and learning infrastructure into a traceable stock-prediction pipeline.

### Scope
1. Reuse existing Marksy market/history datasets.
2. Feature generation for trend, momentum, volume, volatility and market regime.
3. Prediction contract with symbol/instrument identity, horizon, direction, entry range, stop loss, targets and confidence.
4. Prediction lifecycle: CREATED → ACTIVE → EXPIRED → OUTCOME_RECORDED.
5. Prediction outcome tracking without look-ahead bias.
6. Evidence/provenance for prediction inputs.
7. Prediction history and accuracy metrics.
8. Deterministic fallback when a model is unavailable.

### Acceptance criteria
- Predictions are generated from timestamped data available at prediction time.
- Future information cannot leak into feature generation or scoring.
- Every prediction records its horizon, inputs/evidence and creation time.
- Outcomes are calculated after the prediction window closes.
- Prediction performance can be inspected by symbol, horizon and date.

## EPIC-027 — IPO Intelligence

**Goal:** Bring IPO lifecycle data into Marksy and add a separate Marksy intelligence layer.

### Scope
1. Upcoming IPOs.
2. Open IPOs.
3. Closed IPOs.
4. Listed/recent IPOs.
5. IPO details including price band, lot size, dates and available subscription information.
6. Stable IPO identity and deduplication.
7. IPO observations/history in Marksy.
8. Marksy analysis/evidence separate from provider facts.
9. IPO detail and watchlist APIs for MarksyOS.

### Acceptance criteria
- Provider facts are stored with source and as-of timestamps.
- IPO lifecycle transitions are tracked correctly.
- Duplicate provider records do not create duplicate IPO entities.
- Marksy analysis never overwrites or masquerades as provider facts.
- Missing provider data is represented as unavailable rather than guessed.

## EPIC-028 — Market Predictions & Daily Setups

**Goal:** Expose Marksy's stock and IPO intelligence through a consistent daily/intraday setup experience.

### Scope
1. Morning Daily Setups.
2. Intraday prediction updates when meaningful new information arrives.
3. Stock prediction cards and detail views.
4. IPO opportunity/observation cards.
5. Evidence and "why" explanations.
6. Prediction outcome/history view.
7. Existing `marksy-tips/v1` DAILY_SETUPS JSON contract compatibility.
8. Source/freshness display.
9. No fabricated predictions when required data is unavailable.

### Acceptance criteria
- Human-readable Daily Setups remain compatible with the existing JSON schema contract.
- Every prediction links to its underlying Marksy prediction record.
- Current market facts and Marksy predictions are visually and semantically separated.
- Empty/stale datasets produce explicit empty/stale states.
- Prediction history is available for later validation and learning.

## EPIC-029 — MarksyOS Market & IPO Surfaces

**Goal:** Add native MarksyOS surfaces for current market information, stocks, predictions and IPOs without moving market-provider logic into Android.

### Scope
1. Market overview.
2. Current indices/quotes.
3. Stock list/search/watchlist.
4. Stock detail with current data and Marksy intelligence.
5. Predictions list/detail/history.
6. IPO list/detail/watchlist.
7. Market/data freshness indicators.
8. Deep links from notifications/Ask Marksy/Daily Briefing into market details.
9. Tradsy placeholder navigation only.

### Acceptance criteria
- All production market data is loaded from Marksy APIs.
- Existing MarksyOS navigation and intelligence surfaces remain intact.
- No Upstox token or provider secret is bundled in the application.
- UI clearly distinguishes provider facts from Marksy-derived intelligence.
- Tradsy contains only non-functional placeholder buttons/links/details in this epic.

## EPIC-030 — Market Intelligence Health & 30-Day Validation

**Goal:** Extend Marksy Health and validation so market ingestion, prediction quality and data freshness can be measured continuously.

### Scope
1. Upstox REST/API availability.
2. WebSocket connection/subscription health.
3. Market-data freshness.
4. Missing/stale instrument detection.
5. Market ingestion latency and failure rate.
6. Prediction generation latency/failure rate.
7. Prediction outcome accuracy by horizon.
8. IPO data freshness.
9. Source/provider attribution.
10. Daily and 30-day Market Intelligence Validation Report.
11. No-look-ahead and data-integrity validation checks.

### Acceptance criteria
- Market and prediction metrics are collected automatically from real runtime operation.
- Stale or missing data is visible and traceable.
- Prediction outcomes are measured only after their defined horizons.
- Validation distinguishes data-source failures from prediction failures.
- No fabricated market, prediction or validation results are introduced.


## EPIC-031 — MarksyOS Live Market Home

**Goal:** Turn the MarksyOS home page into a live market-intelligence dashboard driven by the Marksy market stream.

### Scope
1. Live Market Pulse with NIFTY 50, SENSEX, BANK NIFTY, NIFTY IT, NIFTY FIN SERVICE and India VIX where supported.
2. Live market status: PRE_OPEN / OPEN / CLOSED / UNKNOWN.
3. Market breadth: advances, declines and unchanged.
4. Live Top Gainers, Top Losers and Most Active sections.
5. Quote cards showing LTP, absolute change, percentage change and available OHLC/volume/VWAP data.
6. Data freshness/source indicators: LIVE, delayed, stale or unavailable.
7. Tap-through from every instrument/card into the instrument detail experience.
8. Graceful REST-snapshot fallback when the live stream is unavailable.
9. Preserve existing Marksy home/inbox/intelligence surfaces; market content must be additive and composable.

### Acceptance criteria
- Home market values update from Marksy's live API stream without manual refresh.
- No Upstox credentials or direct Upstox API calls exist in the Android client.
- Every live market component exposes a truthful freshness state.
- Gainers/losers and index values are derived from real provider data, never hardcoded.
- Stream loss does not make the home page unusable; latest valid snapshot and stale state remain visible.
- Tapping a market item opens the correct instrument detail route.

## EPIC-032 — MarksyOS Instrument Intelligence Workspace

**Goal:** Build a rich Upstox-style instrument page that becomes MarksyOS's primary market research and decision-support surface.

### Scope
1. Instrument header with symbol, company/index name, exchange/segment, LTP, change, percentage change and live/freshness state.
2. Interactive price chart with supported timeframes: intraday intervals plus 1D/1W/1M/3M/6M/1Y/5Y/MAX where data is available.
3. Candlestick/line modes with volume.
4. Technical indicator overlays and panels: EMA/SMA, VWAP, RSI, MACD, Bollinger Bands, ATR, Supertrend, ADX and other indicators supported by the backend.
5. Market-depth section where the provider supplies depth: bid/ask prices, quantities, spread and depth totals.
6. Key quote statistics: OHLC, volume, average/relative volume, VWAP, 52-week high/low and available trading statistics.
7. Watchlist, alert and navigation actions using existing Marksy action/navigation conventions.
8. Separate tabs/sections for Overview, Technical, Fundamentals, News, Events and Derivatives when applicable.
9. Clear separation between provider facts and Marksy-derived intelligence.

### Acceptance criteria
- Opening any supported stock/index from Home, Search, Watchlist, Gainers/Losers or Predictions resolves to the same canonical instrument screen.
- Chart data is timestamped and does not silently mix incompatible intervals or stale snapshots.
- Unsupported data is shown as unavailable/not applicable rather than fabricated.
- Technical indicators use the same backend calculations consumed by prediction/intelligence pipelines where practical.
- The page remains usable on slow networks, stream interruptions, empty data and process recreation.
- Existing navigation and V2 behavior remain backward compatible.

## EPIC-033 — Market Fundamentals, News & Corporate Intelligence

**Goal:** Enrich the instrument workspace with trustworthy non-price information while keeping provider facts, timestamps and Marksy interpretation separate.

### Scope
1. Fundamentals: valuation, market cap, revenue, EBITDA, PAT, EPS, margins, ROE, ROCE, debt, cash and other fields available from configured sources.
2. Period-aware financial history with quarter/fiscal-period labels and as-of timestamps.
3. Shareholding information where available.
4. Corporate events: results, dividends, splits, bonuses, rights, AGM and material announcements where available.
5. Instrument/company news with headline, source, publication time, URL and symbol relevance.
6. News categorization and optional Marksy relevance/sentiment analysis behind an explicit derived-intelligence boundary.
7. Source attribution and freshness for every externally sourced section.
8. Backend aggregation contracts so the Android app does not call multiple external providers directly.
9. Empty/unavailable states for missing provider capabilities.
10. Caching that respects source freshness and does not present stale fundamentals/news as current.

### Acceptance criteria
- Fundamentals, news and events are sourced from real configured providers.
- Every externally sourced dataset has source and as-of metadata.
- Historical financial periods cannot be presented as current values without period context.
- Marksy interpretation never overwrites or masquerades as provider facts.
- News links resolve to the original/source destination when available.
- No fabricated fundamentals, news, corporate events or sentiment are shown.

## EPIC-034 — Market Intelligence Experience & Realtime Hardening

**Goal:** Make the complete Home → Instrument workflow feel reliable under real market conditions and prepare it for continuous use.

### Scope
1. Unified realtime state model for Home and Instrument screens.
2. Subscription lifecycle based on visible instruments, watchlist and currently opened instrument.
3. Stream reconnect/recovery UX with bounded retries and REST reconciliation handled by the backend.
4. Client-side duplicate/out-of-order update protection.
5. Loading, empty, delayed, stale, disconnected and provider-unavailable states.
6. Screen/process recreation without losing the canonical instrument or creating duplicate subscriptions.
7. Performance controls: bounded rendering frequency, batching/debouncing where appropriate and no blocking work on the main thread.
8. Observability for stream latency, update age, reconnect count and client-side rendering failures.
9. Accessibility/readability for rapidly changing financial values.
10. End-to-end tests for Home → Gainers/Losers → Instrument → back navigation and stream interruption/recovery.

### Acceptance criteria
- Home and Instrument screens remain stable through WebSocket disconnect/reconnect and API failures.
- The client never displays an old value as LIVE after its freshness threshold has expired.
- Repeated navigation does not leak collectors/subscriptions or multiply updates.
- High-frequency market updates do not cause visible UI jank or unbounded memory growth.
- Tests cover realtime state transitions, stale data, process recreation, navigation and failure recovery.
- Runtime diagnostics can identify whether a problem originated in the API, stream, client repository or UI.

## Market Experience Implementation Order

EPIC-024 → EPIC-025 → EPIC-031 → EPIC-032 → EPIC-033 → EPIC-026 → EPIC-028 → EPIC-034 → EPIC-027 → EPIC-029 → EPIC-030

EPIC-031 through EPIC-034 are the MarksyOS experience layer over the existing Market Data Gateway, Real-Time Market Stream, Stock Intelligence, IPO Intelligence and Market Health architecture. Do not create a second market-data or intelligence pipeline to implement these screens.

## Market Intelligence Implementation Order

EPIC-024 → EPIC-025 → EPIC-026 → EPIC-027 → EPIC-028 → EPIC-029 → EPIC-030

These EPICs extend EPIC-010 through EPIC-023; they must reuse existing Marksy intelligence, health, validation and on-device abstraction capabilities rather than introducing parallel implementations.

## Market Intelligence Architectural Rules

- **Upstox is the primary market-data source** wherever its API supports the required data.
- **Marksy is the intelligence and normalization boundary.** MarksyOS should consume Marksy APIs rather than calling Upstox directly.
- **Upstox credentials remain server-side.** Never commit, log, bundle or expose access tokens.
- **Current market facts and Marksy predictions are different data classes** and must remain distinguishable in storage, APIs and UI.
- **No look-ahead bias.** A prediction may use only data available at its creation timestamp.
- **No fake market data.** Missing, stale or unavailable data must be represented explicitly.
- **Reuse existing Marksy data.** Do not create a second market database if the existing Marksy data model can be extended safely.
- **Tradsy is placeholder-only for now.** Do not implement order execution, portfolio mutation or trading automation as part of these EPICs.
- Keep each EPIC to a maximum of 5 implementation stories/prompts.

## Implementation Order
EPIC-010, 011, 012, 013, 014, 015, 016, 017, 018, 019, 020, 021, 022, 023, 024, 025, 026, 027, 028, 029, 030 (in that order, but subject to repository reality — if part of an epic already exists, do not rebuild it, complete the missing portions).

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

**Status (2026-09-25):** Deterministic Ask is COMPLETE, and model-routed interpretation is wired in. AI interpretation stays PARTIAL until Nano runs on a real device.

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

**Status (2026-09-25):** COMPLETE in tests. The editor builds nested AND/OR/NOT trees in the engine's own model, and they reload without loss. Each rule has one action. Not yet run on a device.

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

**Status (2026-09-25):** PARTIAL. Gemini Nano runs through ML Kit GenAI/AICore, with capability detection, lifecycle, diagnostics and fallback. It is not verified on hardware, because the test phone has no AICore. Devices without AICore still have no local runtime.

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

**Status (2026-09-25):** COMPLETE for places named in notification text: they are learned, coarsened, explainable, correctable and expiring. Learning from device location is deliberately not built, because it needs a product and privacy decision.

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

**Status (2026-09-25):** PARTIAL. The pull-connector framework and the Calendar provider connector are done. The Gmail API connector is built but not configured, because it needs OAuth. SMS stays notification-only because of the Android restriction.

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
