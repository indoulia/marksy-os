# Roadmap Next: implementation notes

Status and contracts for the work on `feat/roadmap-next` against `docs/ROADMAP-NEXT.md`. Validation so far is unit/Robolectric tests plus `assembleDebug`. Nothing here has been checked on a device yet.

## Pipeline

```
Connector (NotificationListener | WhatsApp accessibility)
  -> RawCapture                      connector/ConnectorFramework.kt
  -> SourceAdapter (per source)      WhatsApp, Gmail, SMS, calendar, banking, shopping
  -> IngestionPipeline               classify, dedup (source key + fingerprint), rules, persist, rule audit, metrics
  -> EventIntelligencePipeline       extract facts, normalize, cross-source dedup, reference threads, auto-resolve, graph index
  -> surfaces                        Smart Inbox, Ask, Briefing, Health, Validation
```

The deterministic logic is the source of truth. AI (`ai/`) can only choose how an Ask query is interpreted, and every AI call has a deterministic fallback. No model ships with the app, so today every path is deterministic.

## Normalized event contract (EPIC-010)

`NormalizedEvent` (intelligence/EventNormalizer.kt) holds entities, money in minor units with direction, reference ids, time mentions, a terminal flag, importance, confidence, threadKey, correlationKey and reasons. It is persisted on `notification_events` as `importanceScore`, `intelligenceConfidence`, `threadKey`, `correlationKey`, `duplicateOfId`, `intelligenceJson` (bounded) and `intelligenceVersion`. Raising `EventIntelligencePipeline.VERSION` makes the background worker re-derive old rows.

Lifecycle is `NEW -> ACTIVE -> RESOLVED -> ARCHIVED`, and it is kept consistent with the legacy `archived` flag.

Two trading reports of the same event from different brokers are never deduplicated. This keeps the existing EventFingerprint design.

## Database

There is one additive migration, v2 -> v3 (`MarksyDatabase.MIGRATION_2_3`). It adds new columns to `notification_events` and these new tables: `learning_signals`, `learning_overrides`, `event_actions`, `context_entities`, `context_links`, `context_relations`, `rule_executions`, `ai_invocations`, `memory_entries`, `connector_events` and `metric_counters`. No existing data is deleted. The migration is tested in `EventIntelligencePipelineTest`, where Room validates every table.

**Version 4 (gateway merge):** the gateway branch's read/keep/reminder columns (`isRead`, `kept`, `remindAt`) are now `MIGRATION_3_4`. The intelligence schema is applied idempotently: it checks columns with `PRAGMA table_info` and uses `CREATE ... IF NOT EXISTS`. Because of that, 3 -> 4 also repairs a phone that ran the pre-merge gateway build, whose v3 had only the read/keep/reminder columns. Both upgrade paths are covered by tests in `EventIntelligencePipelineTest`. `isRead` and lifecycle `NEW`/`ACTIVE` are kept in sync in the DAO.

## Retention vs. long-lived data

Notifications are kept for 7 days and trading events for 30. Memory entries, graph nodes, rule audit, the AI and connector logs (45 days) and `metric_counters` (120 days) outlive events on purpose, so learning, memory and the 30-day validation never need deleted events. `RetentionWorker` does its work in this order: memory ingest, validation snapshot, learning sweep, prune, then orphan clean-up.

## Privacy

No notification text goes into logs, metrics, `ai_invocations`, `connector_events` or the validation export. External AI is off at the code level (`allowExternal = { false }`). The reminder notification shows only the title and source.

## Known gaps

- Rules UI edits a flat subset (any-of words, hours, amount, confidence). Nested trees are stored and evaluated, and the editor keeps them, but it cannot author them.
- Gmail, SMS and Calendar arrive only through their Android notifications. There is no API connector (Gmail would need OAuth).
- Personal memory learns no locations, because no location data is captured.
- About 60 small metric writes per captured notification. This works, but they could be batched.
- The old `DailyDigestScreen`/`DailyDigestModel` are no longer routed. The briefing replaces them.
