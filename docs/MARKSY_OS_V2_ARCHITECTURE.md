# Marksy OS V2 Architecture Freeze

## Product north star

> Marksy OS collects, understands, organizes and acts. Marksy thinks.

Reference product surfaces:

1. Lock Screen / Marksy Pulse
2. Home Dashboard
3. Smart Inbox
4. Trading Intelligence
5. Ask Marksy
6. AI Insight Center
7. Timeline
8. Rules & Automation
9. Daily Digest
10. Floating AI Assistant

## Architectural boundary

```text
Android device
    |
    +-- Collect
    |     +-- NotificationListenerService
    |     +-- optional WhatsApp connector
    |     +-- source registry
    |
    +-- Understand
    |     +-- classification
    |     +-- priority / attention
    |     +-- grouping / correlation
    |     +-- local context
    |
    +-- Organize
    |     +-- Smart Inbox
    |     +-- Timeline
    |     +-- Insights
    |     +-- Daily Digest
    |
    +-- Act
          +-- rules
          +-- automation
          +-- cross-app actions
          +-- trading presentation/actions

Trading events only when eligible
    |
    v
Marksy Gateway / Marksy API
    |
    v
Marksy intelligence
```

## Non-negotiable boundaries

- PostgreSQL is not a warehouse for every phone notification.
- Ordinary notification data remains local and follows the local retention policy.
- Only explicitly eligible trading events are sent to Marksy.
- Marksy OS must not implement trading intelligence locally when the Marksy service is the source of truth.
- No direct PostgreSQL connection from Android.
- No LLM call per notification.
- No WebView as an intelligence layer.
- Upstox may remain a notification source, but brokerage authentication/order execution is disabled until explicitly approved.
- No Play Store publication is part of this implementation phase.
- No APK/CI work is part of this implementation phase.

## Data flow

```text
raw notification
      |
      v
normalize + lifecycle gate
      |
      v
classify
      |
      v
source-aware deduplication
      |
      v
local event store
      |
      +--> attention / priority stream
      |
      +--> inbox / timeline / digest / insights
      |
      +--> if TRADING --> Marksy delivery state machine
```

## Domain boundaries

The implementation should evolve toward these logical boundaries without forcing premature Gradle modules:

- `notification` — device capture and normalization
- `data` — local persistence and repositories
- `intelligence` — classification, prioritization, grouping and correlation
- `gateway` — transport only
- `ui` — presentation
- `actions` — rules and automation
- `assistant` — Ask Marksy and floating assistant

The existing V1 packages remain valid during migration. New V2 behavior must respect these boundaries.

## V2 implementation sequence

### Phase 0 — V1 hardening

- notification lifecycle and privacy
- source-aware deduplication
- WhatsApp least privilege
- trading delivery race protection
- Marksy API transport hardening
- retention and recovery
- real-device validation

### Phase 1 — Core architecture

- stable event/domain boundaries
- repository-driven streams
- priority/attention streams
- grouping and correlation contracts
- feature-level ViewModel boundaries

### Phase 2 — Event intelligence

- attention scoring
- grouping
- correlation
- event lifecycle/threading
- local context windows

### Phase 3 — Home Dashboard

- notification overview
- important attention feed
- AI summary surface
- daily activity metrics

### Phase 4 — Smart Inbox

- AI sorting
- grouping
- priority filters
- source-aware presentation

### Phase 5 — Trading Intelligence

- Marksy signals
- comparison/verdict
- positions/watchlist/news surfaces
- no brokerage execution

### Phase 6 — Timeline

- chronological event graph
- related event threads
- Marksy analysis attached to trading events

### Phase 7 — Insight Center

- patterns
- trends
- notification behavior
- market intelligence summaries

### Phase 8 — Ask Marksy

- contextual questions
- local context selection
- Marksy conversation transport
- explicit unavailable states

### Phase 9 — Rules & Automation

- IF/THEN rules
- notification routing
- local actions
- scheduled automation

### Phase 10 — Daily Digest

- daily attention summary
- trading opportunities
- pending actions
- next-day reminders

### Phase 11 — Floating Assistant

- persistent assistant surface
- quick contextual questions
- action entry point

### Phase 12 — Lock Screen / Pulse

- glanceable high-value intelligence
- important events
- market/trading pulse

### Phase 13 — Cross-app intelligence

- cross-source correlation
- event graph
- unified context for Marksy

## Current V2 status

The first V2 foundation slice is implemented:

- repository exposes `importantEvents` and `attentionEvents`
- local DAO exposes priority-aware streams
- Home consumes the important-event stream
- Home now has an `AI attention` surface instead of treating every retained event equally

This is intentionally a small first step. The scoring model, grouping engine and remote conversational intelligence will be added as separate capabilities rather than embedded into the UI.
