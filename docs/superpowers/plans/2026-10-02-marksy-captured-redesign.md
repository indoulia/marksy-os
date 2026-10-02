# Captured redesign implementation plan

Spec: docs/superpowers/specs/2026-10-02-marksy-captured-redesign-design.md. TDD for logic; no layout tests. Never connectedAndroidTest.

## Task 1: Data layer (Room 7 to 8, DAO, repository)
- Test (`CapturedRetryTest`, Robolectric in-memory): `requeueFailed` FAILED to PENDING, attempts 0, note cleared; other states untouched; row appears in `findPendingCapture`; `observeCaptured` returns NEW/ACTIVE/RESOLVED trading rows, not archived or non-trading.
- Add `deliveryNote` to NotificationEventEntity, MIGRATION_7_8 (addColumn), version 8, `.addMigrations`.
- DAO (additive): `observeCaptured(limit)`, `requeueFailed(id)`, `setDeliveryNote` folded into `updateInFlightDeliveryState` callers via new `markFailedWithNote`/`markKept` queries.
- Repository: `observeCaptured()`, `retryDelivery(id)`.
- Worker: terminal failure writes fixed code (policy `failureCode(error)`), gate keep writes keep reason. Test `failureCode`.

## Task 2: CapturedModel (pure)
- Tests (`CapturedModelTest`) first: lanes, boundary, folding, levels and headline, stack by symbol, health counts.
- `ui/CapturedModel.kt`: `lanes`, `todayBoundary`, row building from `TradingInsight`/response, fold, stacks, `CaptureHealth`, `capturedNote`.

## Task 3: UI
- `ui/CapturedScreen.kt`: lanes in LazyColumn, stack cards (top 2 + expander), needs-you cards with Retry / Send now / Allow background, dashed folds (Earlier, Rejected orders), row detail dialog, health dialog, Pill FlowRow in dialogs.
- `OneHandControls`: optional `extraSections` slot in the filter panel; X only for an active Show filter (default behaviour for other pages unchanged).
- `TradingIntelligenceScreen`: use CapturedScreen for the Captured view, filter state, stack-by state.
- `MainActivity`/`MarksyViewModel`: `capturedEvents`, header note with tap to health dialog, retry callbacks (scheduler, battery settings intent).

## Task 4: Verify
- Scratch Robolectric render at w400dp and w360dp (not committed) into scratchpad captured-render/.
- `testDebugUnitTest`, `assembleDebug`.
- Whole-branch review, fix, one re-review; rebase on origin/main; PR; merge when green; remove worktree.
