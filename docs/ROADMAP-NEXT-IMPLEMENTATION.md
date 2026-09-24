# Roadmap Next: implementation notes

Status and contracts for the work on `feat/roadmap-next` against `docs/ROADMAP-NEXT.md`. Validation so far is unit/Robolectric tests plus `assembleDebug`. Nothing here has been checked on a device yet.

## Pipeline

```
Connector (NotificationListener | WhatsApp accessibility | pull: Calendar provider, Gmail API)
  -> RawCapture                      connector/ConnectorFramework.kt, pull via connector/SyncConnector.kt
  -> SourceAdapter (per source)      WhatsApp, Gmail, SMS, calendar, banking, shopping
  -> IngestionPipeline               classify, dedup (source key + fingerprint), rules, persist, rule audit, metrics
  -> EventIntelligencePipeline       extract facts, normalize, cross-source dedup, reference threads, auto-resolve, graph index
  -> surfaces                        Smart Inbox, Ask, Briefing, Health, Validation
```

The deterministic logic is the source of truth. AI (`ai/`) can only choose how an Ask query is interpreted, and every AI call has a deterministic fallback.

## On-device AI (EPIC-019)

`GeminiNanoModel` (ai/GeminiNanoModel.kt) runs Gemini Nano through the ML Kit GenAI Prompt API (`com.google.mlkit:genai-prompt:1.0.0-beta4`), and Android AICore executes it. The APK contains no model file, because AICore owns both the model and its download.

States:
- UNKNOWN: not probed yet in this process.
- NOT_AVAILABLE: the device has no AICore or Nano.
- NOT_INSTALLED: the model can be downloaded. It then moves through DOWNLOADING to READY, or to FAILED.

Download and warm-up start only when the user taps the button in "What Marksy learned".

Diagnostics record the model name, runtime, warm-up ms, last latency, calls, failures and last error type. They never contain text.

Calls run on `Dispatchers.Default` with a 5 s timeout, then fall back to deterministic interpretation. Prompt content stays on-device. ML Kit itself may send anonymous usage telemetry (no prompt text) to Google. `allowExternal` is still `false`.

Not verified on hardware. The only test device (OnePlus 12R, Android 16) has no AICore package, so Nano reports NOT_AVAILABLE there. Devices without AICore need a second runtime registered in `AiModelRegistry`, for example LiteRT-LM or MediaPipe with a user-supplied Gemma file.

## Ask Marksy (EPIC-016)

`AskMarksy.interpret` is separate from `answer`. It tries the model first and falls back to deterministic parsing. The Ask screen routes on the interpreted intent via `AskMarksyRepository.askIf`.

Model output can only pick the intent, range keyword, subject and direction:
- Ranges are resolved deterministically.
- Subjects that are not in the question are dropped.
- Every item comes from retrieved rows.

New query support:
- Payments can be filtered by counterparty ("to Amazon").
- "last month" is a range.
- A partial name that matches several active people returns a clarification (`needsClarification`) instead of merged results.

## Rules editor (EPIC-018)

`RuleConditionTree` edits the engine's own `Condition` tree: AND/OR groups, NOT, nested groups and per-field comparators. It validates against the store's reload limits (depth 6, 20 children, 120-char values).

A stored rule whose condition fails to parse is now dropped on load. Before, it kept no condition and matched every event.

A rule still has one action. The engine does not support multi-action rules.

## Places (EPIC-020)

`LocationMemory` learns Home, Work and named places only from notification text:
- delivery and ride phrases
- calendar-style `Location:`/`Where:`/pin lines (never chats)

Marksy never reads device location. The coarse-location permission is still used only for weather.

Privacy handling:
- Addresses keep only their last two comma parts.
- Bare street addresses and recipient names are dropped.

Places use the existing memory provenance, confidence, 90-day expiry, correction and forget. Memory entries now also record source apps (`detailJson.sources`). Only events ingested after this change are scanned.

## Pull connectors (EPIC-021)

`SyncConnector` and `ConnectorSyncer` feed provider records through the same `IngestionPipeline`. With `replaceOnUpdate`, an update replaces the stored content instead of appending to it.

- The cursor is saved only after the whole batch is stored.
- Records deleted or cancelled at the source are resolved, not deleted.
- `ConnectorSyncWorker` runs every 30 minutes with backoff.
- Sync state lives in the `marksy_connector_sync` preferences, so there is no schema change.

Calendar:
- Reads `CalendarContract.Instances` from 1 day back to 14 days ahead, with recurrence expanded.
- Skips declined and cancelled instances.
- The cursor is a hash snapshot, which detects new, updated and deleted instances.
- Unchanged instances are re-sent every 3 days, so retention cannot drop upcoming events.
- Results are capped at 300, and a capped result never produces false deletions.
- `READ_CALENDAR` is requested only when the user connects the calendar on the Health screen.

Gmail API:
- Syncs incrementally from history, re-snapshots on a 404, and invalidates the token on 401/403.
- Reports NOT_CONFIGURED. It needs a Google OAuth client for the app's signing key plus the restricted `gmail.readonly` scope, and the app has neither.

SMS: there is no direct connector. `READ_SMS` is restricted to default SMS apps, so SMS stays notification-only.

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

- Gemini Nano has not been tested on real hardware, because no AICore device is available. Every current test device uses the deterministic path.
- Gmail API sync is implemented but stays inactive until an OAuth token provider exists. SMS is notification-only.
- The Calendar connector and place learning have only unit and Robolectric tests. Neither has run on a device yet.
- About 60 small metric writes per captured notification. This works, but they could be batched.
- The old `DailyDigestScreen`/`DailyDigestModel` are no longer routed. The briefing replaces them.
