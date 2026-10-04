# Play-Safe Capture (EPIC-036 to EPIC-040) Implementation Plan

> For agentic workers: execute your phase's tasks in order with TDD (failing test, confirm red, implement, green), one focused commit per task. No per-task review; one whole-branch review runs after Phase D.

**Goal:** capture legitimate trading tips from (1) Android notifications, (2) user-shared or user-picked screenshots, (3) user-authorized MediaProjection capture when a notification is only a teaser. Everything stays on the phone unless an explicit rule makes an accepted tip eligible for the existing `POST /tips/ingest-text` delivery.

**Numbering.** The source prompt called these EPIC-010 to EPIC-014, but those numbers already name finished EPICs in `docs/ROADMAP-NEXT.md`. Mapping: EPIC-036 Capture Gateway & Source Policy (prompt 010), EPIC-037 Screenshot/Share/OCR (011), EPIC-038 MediaProjection (012), EPIC-039 Notification-to-Screen Workflow (013), EPIC-040 Privacy/Permissions/Validation (014).

**Branch / worktree:** `feat/play-safe-capture` in `C:\AIAgent\marksy-os-capture`. Never merge, push to main, deploy or publish.

## Hard boundaries (all phases)

- Never add: `READ_SMS`, `RECEIVE_SMS`, `WRITE_SMS`, `RECEIVE_MMS`, a new AccessibilityService, `SYSTEM_ALERT_WINDOW`, `READ_MEDIA_IMAGES`/`READ_EXTERNAL_STORAGE`, `QUERY_ALL_PACKAGES`, root, network interception, any FLAG_SECURE/auth/paywall/DRM bypass, continuous or invisible recording, automated interaction with another app (no clicks, scrolls, typing, navigation), brokerage execution. The pre-existing `MarksyWhatsAppAccessibilityService` is NOT touched; it is documented as a Play review risk only.
- No OTP/PIN is ever stored, shown or copied (user rule 2026-10-02). Any recognized text containing a one-time code drops the whole capture: the evidence row is kept as `FAILED` with code `one-time-code` and no text. Reuse `NotificationClassifier.isOneTimeCode` / `carriesOneTimeCode`.
- No image or frame is ever written to storage. Images are decoded in memory, hashed (SHA-256 of the bytes or frame pixels) for duplicate detection, recognized, then released. The evidence reference is `sha256:<hex>` (plus `event:<id>` for notification evidence).
- Logs carry only ids, enum names and fixed codes. Never a notification body, OCR text, symbol, price, URI, file name, token or exception message (exception class simple name is fine). Gateway classes take a `log: (String) -> Unit` so tests can assert this.
- Backend contract unchanged. Accepted candidates go out through the existing `CapturedMessage` / `MarksyGatewayClient.capture` path only.
- Do not touch SEL-001/SEL-002, selection/publish logic, `NotificationClassifier` rules, or `IngestionPipeline` internals beyond wiring the existing `onStored` hook.
- UI: only theme tokens/components (`MarksyDialog`, `MarksyCard`, `MarksyRowCard`, Marksy pills, `MarksyFormat`, `OneHandControls` floating stack). `ThemeGuardTest` is strict and must pass; never add an exception to it. No in-page heading/label/button rows.
- Tests: pure JVM where possible, extend existing files where natural, no UI-layout tests.

## Build / test commands (Git Bash, from the worktree root)

```bash
export PATH="$(echo "$PATH" | tr ':' '\n' | grep -vE '"|%' | paste -sd:)"   # broken user PATH breaks forked test JVMs
./gradlew --no-daemon :app:testDebugUnitTest --tests 'com.marksy.os.capture.*'
./gradlew --no-daemon :app:assembleDebug
```
Never run `connectedAndroidTest` (wipes the user's phone data). Do not install on or drive the phone; device validation is done by the controller.

## Core model (package `com.marksy.os.capture`)

- `enum CaptureMethod { NOTIFICATION_LISTENER, USER_SHARED_IMAGE, USER_SELECTED_IMAGE, MEDIA_PROJECTION }`
- `enum CaptureState { CAPTURED, PENDING_EXTRACTION, EXTRACTED, REVIEW_REQUIRED, ACCEPTED, REJECTED, EXPIRED, FAILED }`. Legal moves: CAPTURED→{PENDING_EXTRACTION, FAILED, EXPIRED}; PENDING_EXTRACTION→{EXTRACTED, REVIEW_REQUIRED, FAILED, EXPIRED}; EXTRACTED→{ACCEPTED, REJECTED, EXPIRED}; REVIEW_REQUIRED→{ACCEPTED, REJECTED, EXPIRED}. ACCEPTED, REJECTED, EXPIRED, FAILED are terminal. Illegal moves throw `IllegalStateException`.
- `EXTRACTED` = symbol, side, target and stop loss found, no ambiguity, confidence ≥ 0.8. Anything less is `REVIEW_REQUIRED`. Both still need the user's review before anything is forwarded.
- `CaptureProvenance(method, sourcePackage: String?, sourceName: String?, sourceVerified: Boolean, capturedAt: Long, evidenceRef: String, notificationEventId: Long?, workflowId: Long?)`. `sourceVerified` is true only when the package comes from Android package identity (the originating notification); a source the user picks in review is `false`.
- `TipFields(symbol, side: TipSide?, entry, target, stopLoss: Double?, horizon: String?, visibleTimestamp: String?)`; `TipSide { BUY, SELL }`. `forwardable` = symbol + side + at least two of entry/target/stopLoss (same shape as `CaptureGate.isCall`).
- `TipCandidate(provenance, extractedText, confidence: Double, fields, state, ambiguities: Set<String>)`; ambiguity codes are fixed strings (`ambiguous-symbol`, `malformed-price`, `missing-target`, `missing-stop-loss`, `conflicting-side`, `low-ocr-confidence`).
- `WorkflowState { NOTIFICATION_RECEIVED, NEEDS_SOURCE_VIEW, USER_OPENED_SOURCE, CAPTURE_REQUESTED, CAPTURE_AUTHORIZED, EXTRACTION_PENDING, REVIEW_REQUIRED, ACCEPTED, REJECTED, CAPTURE_DENIED, CAPTURE_FAILED, PROTECTED_SCREEN, SOURCE_UNAVAILABLE, EXPIRED }`. Happy path in that order; NEEDS_SOURCE_VIEW may go straight to CAPTURE_REQUESTED (user taps Capture first). CAPTURE_DENIED / CAPTURE_FAILED / PROTECTED_SCREEN / SOURCE_UNAVAILABLE can return to CAPTURE_REQUESTED (retry) until EXPIRED. ACCEPTED, REJECTED, EXPIRED terminal. Workflow TTL 12 h.

## Phase A — gateway, extraction, persistence, delivery, retention (EPIC-036 core, EPIC-039 logic, EPIC-040 safeguards)

A1. `CaptureModels.kt` + `CaptureLifecycle` + `WorkflowState` transitions. Tests: `CaptureLifecycleTest` (every legal move, illegal moves throw, terminal states final, workflow retry/expiry).

A2. `TipExtractor` (pure) — `extract(text: String, provenance, ocrConfidence: Float?): ExtractionResult` = `Candidate(TipCandidate) | NotATip | OneTimeCode`. First search the codebase for existing ticker/level/side parsing (EPIC-028 DAILY_SETUPS parser, `EventExtractor`, `CaptureGate` regexes, `TipTextCleaner`) and reuse it. Rules: never invent a field; a price that does not parse cleanly (e.g. `5O0`, `4,7O`) becomes null plus `malformed-price`; more than one plausible ticker near the side word gives null symbol plus `ambiguous-symbol`; BUY and SELL both present gives `conflicting-side`; horizon from intraday/BTST/short term/positional/swing/long term/"N days|weeks|months"; visible timestamp kept as the matched text only. Non-trading text (no side word and no priced level) is `NotATip`. Tests (`TipExtractorTest`): valid tip, missing target, missing stop loss, malformed price, OCR noise (extra symbols, broken lines, mixed case), ambiguous symbol, non-trading screenshot text, OTP text, notification-complete tip `BUY ABC @ 500 Target 650 SL 470`.

A3. `CaptureSourceRegistry` — allow-list by package identity, the only place source-specific behavior lives. `resolve(pkg)` → `CaptureSource(packageName, displayName, medium: CaptureMedium, offersScreenCapture, deliverable)`. `offersScreenCapture` = package in the cached server capture list (`CaptureStore.capturePackages()`) or in any existing on-device broker/trading package hint list (reuse, do not create a new list if one exists). `deliverable` = medium `APP_NOTIFICATION` and package in the server capture list (chats and SMS screenshots can never prove a group identity, so they stay local). Unknown or blank package → unsupported. Display names via `SourceRegistry.displayName`. Tests (`CaptureSourceRegistryTest`): allow-listed, unsupported, chat package never deliverable, capture list not yet fetched.

A4. Room: `CaptureEvidenceEntity` (`capture_evidence`), `TipCandidateEntity` (`tip_candidates`, incl. `deliveryState` using `DeliveryState` names, `deliveryAttempts`, `deliveryNote`, `userChoseSend: Boolean`, `reviewedAt`), `CaptureWorkflowEntity` (`capture_workflows`, unique index on `(sourcePackage, sourceKey)`), `CaptureDao`, `MIGRATION_8_9` following the existing migration style; register in `MarksyDatabase`. Extend the existing migration/DAO test pattern under `test/.../data/local`.

A5. `TeaserDetector` + `NotificationCapturePlanner` (pure): given a stored notification event and the registry → `CompleteTip(candidate)` | `NeedsSourceView(reason: TEASER | REDACTED)` | `NotApplicable(code)`. Teaser = allow-listed source, trading-ish wording ("new recommendation", "tap to view", "new tip", "trade idea", "stock idea", "research call", "view now", "check now", etc.) and no forwardable call. Redacted = allow-listed source whose text is blank or a system redaction ("Sensitive notification content hidden", "Contents hidden", "New notification"). Complete tips keep the existing ingestion + `CaptureGate` path; the planner never creates a second deliverable record for them. Tests (`NotificationCapturePlannerTest`): complete tip, incomplete teaser, redacted/missing text, unsupported source, source-specific (chat vs app).

A6. `CaptureGateway` — the boundary. Constructor takes `CaptureDao`, registry, `clock`, `log`. API:
- `onNotificationStored(event)` — called from the existing `IngestionPipeline.onStored` hook (wire in `MarksyContainer.ingestion`, keep the existing bill-due behavior). Creates a workflow at `NEEDS_SOURCE_VIEW` for `NeedsSourceView`; a duplicate `(sourcePackage, sourceKey)` never creates a second one.
- `submitRecognized(method, sourceHint: String?, sourceVerified, contentHash, text: String?, ocrConfidence: Float?, workflowId: Long?)` → `CaptureOutcome` (`Candidate(id, state)`, `Duplicate(existingCandidateId)`, `NotATip`, `Failed(code)`). Records evidence CAPTURED → PENDING_EXTRACTION → result. OTP → evidence FAILED `one-time-code`, no text stored. Same `contentHash` within 24 h, or the same workflow already at REVIEW_REQUIRED/ACCEPTED, is a duplicate.
- `submitFailure(method, workflowId, code)` for `capture-denied`, `projection-revoked`, `protected-screen`, `source-unavailable`, `capture-failed`, `unsupported-android`, `image-unreadable`.
- Workflow moves: `sourceOpened(id, viaDeepLink: Boolean)`, `captureRequested(id)`, `captureAuthorized(id)`.
- `review(candidateId, fields, sourcePackage, decision: SEND | KEEP | REJECT)` → ACCEPTED (+ `deliveryState` PENDING only for SEND when eligible, else NOT_APPLICABLE) or REJECTED (extracted text cleared). Sets `reviewedAt`.
- `sweep(now)` — expires workflows past TTL, workflows stuck in CAPTURE_AUTHORIZED/EXTRACTION_PENDING > 10 min become CAPTURE_FAILED `stale-session` (process death), candidates unreviewed > 3 days → EXPIRED.
Tests (`CaptureGatewayTest`, fake in-memory DAO or Robolectric in-memory Room — follow `IngestionPipelineTest`): provenance kept per method, unsupported source, lifecycle transitions, notification → capture, denied, protected screen, missing link (opened via launcher), duplicate notification, duplicate capture, expired workflow, OTP drop, logs contain no body/OCR text.

A7. `CandidateDeliveryPolicy.decide(candidate, CaptureContext): CaptureDecision` reusing `CaptureGate`/`TipTextCleaner` pieces: Send only if ACCEPTED + `userChoseSend` + forwardable + registry `deliverable` (Wait while the capture list is null) + not an own order/holding (`NotificationClassifier.isOwnOrderEvent/isOwnAccountEvent` on the extracted text). Payload: `medium = APP_NOTIFICATION`, `appPackage`, `channelLabel` via `TipTextCleaner.channelLabel`, `text` = a canonical line built ONLY from reviewed fields (e.g. `BUY ABC @ 500 Target 650 SL 470 Intraday`) passed through `TipTextCleaner.clean` — never raw OCR text, `deviceEventKey = CaptureGate.deviceEventKey(salt, pkg, "capture:<candidateId>")`, `devicePostedAt` = capturedAt. Add a `CandidateDeliveryRun` mirroring `TradingDeliveryRun` (claim, decide, send, mark) and call it from `TradingDeliveryWorker` after the notification drain; the worker's early "nothing pending" return must also consider pending candidates. Tests (`CandidateDeliveryPolicyTest` + extend `PrivacyBoundaryTest`): non-trading/kept/rejected/unreviewed candidates never queue, chat-source never sends, payload contains no raw OCR text, unsupported source keeps.

A8. Retention: extend `RetentionPolicy` / `RetentionWorker`: capture rows non-accepted 7 days, accepted 30 days; run `CaptureGateway.sweep`. Extend `RetentionPolicyTest`.

## Phase B — Android platform (EPIC-037 intake/OCR, EPIC-038 MediaProjection, EPIC-039 glue)

B1. OCR: `TextRecognizer` interface (`suspend recognize(bitmap): OcrResult(text, meanConfidence: Float?)`) + `MlKitTextRecognizer` using bundled ML Kit Latin text recognition (`com.google.mlkit:text-recognition`, newest version that resolves). On-device only. Downscale so the long edge ≤ 2048 px before recognition.

B2. Image intake: `ImageIntake` decodes a content `Uri` in memory (ImageDecoder API 28+, BitmapFactory with sampling below), hashes the bytes, recognizes, calls `CaptureGateway.submitRecognized`, releases the bitmap. Unreadable → `submitFailure(image-unreadable)`.
- Path A share: add an `ACTION_SEND` + `image/*` intent filter to `MainActivity`; read `EXTRA_STREAM` (typed getter on API 33+); method `USER_SHARED_IMAGE`; source hint from `Activity.getLaunchedFromPackage()` on API 34+ only when the sender shared its identity, else null (never guessed from the referrer: screenshot UIs and galleries are not the source).
- Path B picker: `ActivityResultContracts.PickVisualMedia(ImageOnly)`; method `USER_SELECTED_IMAGE`; cancellation is a no-op (no row).
- No storage/media permission is added.

B3. MediaProjection (`com.marksy.os.capture.projection`):
- `ScreenCaptureConsent`: activity-result launcher; API 34+ `createScreenCaptureIntent(MediaProjectionConfig.createConfigForUserChoice())` so the user can pick a single app window; below 34 `createScreenCaptureIntent()`. Denial → `submitFailure(capture-denied)`. Consent is never cached or reused: one consent, one session, one frame.
- `ScreenCaptureService`: foreground service `foregroundServiceType="mediaProjection"`; manifest adds `FOREGROUND_SERVICE` and `FOREGROUND_SERVICE_MEDIA_PROJECTION`. Calls `startForeground(..., FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)` BEFORE `getMediaProjection`; registers `MediaProjection.Callback` before `createVirtualDisplay` (required on API 34+); `ImageReader` RGBA_8888 sized to the display, resized on `onCapturedContentResize` (API 34+). `START_NOT_STICKY`. Ongoing notification = the visible active-capture state, with actions "Capture now" and "Stop".
- "Capture now" opens `CaptureTriggerActivity` (translucent, no UI, `excludeFromRecents`, `noHistory`, empty `taskAffinity`) so the shade collapses; after ~500 ms it asks the service for one frame, then waits for the outcome and opens `MainActivity` on the review (via an extra handled like the existing `EXTRA_OPEN`), then finishes.
- One frame only. Immediately after it: release VirtualDisplay, ImageReader, `projection.stop()`, `stopForeground`, `stopSelf`. Session auto-stops after 3 min without a capture (`capture-failed` code `session-timeout`). `onStop` from the system/user → `projection-revoked` unless a frame was already taken.
- `FrameInspector.isProtected(pixels: IntArray)` (pure): ≥ 98 % near-black samples → `protected-screen`; the UI says "This app blocks screen capture" and never retries silently. `onCapturedContentVisibilityChanged(false)` (API 34+) at grab time → `capture-failed` code `content-hidden`.
- `CaptureSessionController`: process singleton `StateFlow` (Idle, AwaitingConsent, Active(workflowId), Capturing, Finished(outcome)); lost on process death, which `sweep` turns into `stale-session`.
- API < 29: no foreground-service type needed; MediaProjection still requires consent. Keep the minSdk 26 path compiling and guarded.
Tests: `FrameInspectorTest`; session state transitions as pure logic if extracted; no instrumentation.

B4. Notification glue: `OriginalAppLauncher.openDetailed(...)` returning `CONTENT_INTENT | LAUNCHER | UNAVAILABLE` (keep `open` delegating). "View tip" → `sourceOpened(id, viaDeepLink)` or `submitFailure(source-unavailable)`. "Capture tip" → `captureRequested` → consent → `captureAuthorized` → service start → source opened the same way (the user's own tap started this) → user taps "Capture now".

## Phase C — UI (EPIC-037/038/039 surfaces)

C1. `CaptureReviewDialog` (`MarksyDialog`): provenance (method, source, time, verified or user-chosen), confidence, ambiguity notes, editable symbol/side/entry/target/SL/horizon, source picker (allow-listed apps + "Other") when unverified, extracted text collapsed. Buttons: "Send to Marksy" (enabled only when `CandidateDeliveryPolicy` would send; caption states exactly what leaves: symbol, side, levels, horizon, source app), "Keep on phone", "Reject".
C2. Captured screen: floating "Add screenshot" action (Photo Picker) in the existing floating stack; a "To review" group (REVIEW_REQUIRED/EXTRACTED candidates) and open teaser workflows ("Tip from X: View · Capture"). Tapping opens review.
C3. `EventDetailDialog`: for an event with an open workflow show "View tip" and "Capture tip".
C4. While a capture session is active: in-app indicator with Stop (the service notification is the system-level one).
C5. `HealthScreen`: Capture card — notification access granted/revoked, screen capture "asked every time", photos "system picker, no permission", counts by state, last failure code.

## Phase D — docs, roadmap, validation (EPIC-040)

D1. `docs/CAPTURE-ARCHITECTURE.md`: architecture + data-flow diagram, capture-method matrix, permission matrix, data-flow/retention matrix, privacy boundaries, Android-version differences (API 26–28, 29–33, 34, 35+, 36/37 unverified unless device-tested), Play-policy considerations with links to official docs (Notification listener, Foreground service types incl. mediaProjection, Photo picker, User Data/Prominent disclosure, Accessibility API policy, SMS/Call Log policy), known limitations, device-test results, why SMS permissions and a capture AccessibilityService are excluded, and READ_CONTACTS / RECORD_AUDIO / CAMERA as open review risks. Never claim Play approval.
- Accessibility audit (user instruction 2026-10-03): the new capture architecture never adds, expands or repurposes an AccessibilityService. `MarksyWhatsAppAccessibilityService` (WhatsApp watch-list connector) stays unchanged. The doc must not say accessibility is excluded from the app; it audits that service against the current Play Accessibility API policy (what it reads, `isAccessibilityTool`, disclosure/consent, data flow) and sets out the retain / disable-for-Play-build / remove options with their consequences. The decision is the user's; the doc and the final report present it as open.
D2. `docs/ROADMAP-NEXT.md`: EPIC-036..040 sections + status lines.
