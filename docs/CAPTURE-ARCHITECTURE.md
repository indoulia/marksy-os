# Play-safe capture architecture (EPIC-036 to EPIC-040)

Status: implemented on branch `feat/play-safe-capture`, not merged, not published. Nothing here is a Google Play approval or a claim that Play review will pass; Play considerations are listed as risks. Items marked "unverified" were not confirmed against official documentation or a device.

Capture means: a tip reaches Marksy only because (1) Android delivered a notification, (2) the user shared or picked a screenshot, or (3) the user authorized a single screen capture. Everything stays on the phone unless the user reviews a candidate and chooses Send, and the source is verified (see Delivery).

## 1. Architecture and data flow

```
 Notification listener      Share / Photo Picker        MediaProjection (user consent each time)
 (existing service)         (ACTION_SEND, PickVisualMedia)   (foreground service, one frame)
        |                            |                              |
        v                            v                              v
 IngestionPipeline            ImageIntake                    ScreenCaptureService
 (existing; onStored hook)    decode in memory, SHA-256,     frame -> FrameInspector
        |                     downscale <= 2048 px           (protected screen?) -> SHA-256
        v                            |                              |
 NotificationCapturePlanner          +--------> TextRecognizer <----+   (bundled ML Kit, on device)
 complete tip -> existing path               text + mean confidence; bitmap released
 teaser/redacted -> workflow                              |
        |                                                 v
        +--------------------------> CaptureGateway.submitRecognized / submitFailure
                                      evidence row (hash, no image) -> TipExtractor
                                      OTP anywhere in text -> FAILED one-time-code, no text kept
                                                          |
                                                          v
                                      TipCandidate (EXTRACTED | REVIEW_REQUIRED)
                                                          |
                                      CaptureReviewDialog: user edits fields, picks source,
                                      chooses Send to Marksy | Keep on phone | Reject
                                                          |
                                       Send + sourceVerified + allow-listed app source
                                                          v
                                      CandidateDeliveryPolicy -> CandidateDeliveryRun
                                      (TradingDeliveryWorker) -> POST /tips/ingest-text
                                      canonical line from reviewed fields only
```

Components (package `com.marksy.os.capture`, `projection/`): `CaptureModels` (methods, states, provenance), `CaptureGateway` (the boundary: nothing leaves the phone from it), `CaptureSourceRegistry` (allow-list by package identity; the only place source-specific behavior lives), `TipExtractor` (pure), `NotificationCapturePlanner`/`TeaserDetector`, `ImageIntake`, `TextRecognition`, `CandidateDeliveryPolicy`, `ScreenCaptureConsent`/`ScreenCaptureService`/`CaptureTriggerActivity`/`CaptureSessionController`/`FrameInspector`. Storage: Room tables `capture_evidence`, `tip_candidates`, `capture_workflows` (migration 8 to 9, validated on a real install, see section 9).

Backend contract is unchanged: accepted candidates go out as the existing `CapturedMessage` (`medium = APP_NOTIFICATION`, `appPackage`, `channelLabel`, `text`, `deviceEventKey`, `devicePostedAt`) through `MarksyGatewayClient.capture`.

## 2. Capture-method matrix

| Method | Trigger | Provenance source | Source verified | Can be sent |
|---|---|---|---|---|
| NOTIFICATION_LISTENER, complete tip | Android posts a notification | Notification package | Yes | Existing `CaptureGate` path, unchanged |
| NOTIFICATION_LISTENER, teaser/redacted | Allow-listed app, "tap to view" text or redacted text | Notification package; creates a workflow "View tip / Capture tip" | Yes (workflow carries it) | Only after user capture and review |
| USER_SHARED_IMAGE | User shares an image to Marksy | `getLaunchedFromPackage()` on API 34+ when the sender shared its identity; else none | Only when that identity is an allow-listed app | Only if verified |
| USER_SELECTED_IMAGE | Photo Picker | None known (picker hides origin) | No | Never; stays on phone |
| MEDIA_PROJECTION | User taps Capture tip, grants consent, taps Capture now | Originating notification's package (workflow) | Yes when started from a notification workflow; no when started otherwise | Only if verified |

Chat and SMS packages (WhatsApp, Telegram, SMS apps) are never deliverable from capture: a screenshot cannot prove a group identity, so they stay local.

## 3. Permission matrix

Added by this branch: `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PROJECTION` (service type `mediaProjection`), an `ACTION_SEND image/*` filter on `MainActivity`, an internal `CaptureTriggerActivity`, `ScreenCaptureService` (not exported), dependency `com.google.mlkit:text-recognition:16.0.1` (bundled Latin model).

| Permission / capability | Status |
|---|---|
| Notification access (`BIND_NOTIFICATION_LISTENER_SERVICE`) | Pre-existing, user grants in system settings |
| `POST_NOTIFICATIONS`, `INTERNET` | Pre-existing |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PROJECTION` | New, used only during a user-authorized capture |
| MediaProjection consent | Runtime system dialog, every session; never cached |
| Photo access | System Photo Picker and share grants; no permission |
| `CAMERA`, `RECORD_AUDIO`, `READ_CONTACTS`, `ACCESS_COARSE_LOCATION`, `READ_CALENDAR` | Pre-existing, unrelated to capture; CAMERA/RECORD_AUDIO/READ_CONTACTS are open Play review risks (need declared purposes) |
| `BIND_ACCESSIBILITY_SERVICE` | Pre-existing WhatsApp service only; see section 8 |
| NOT requested | `READ_SMS`, `RECEIVE_SMS`, `WRITE_SMS`, `RECEIVE_MMS`, `READ_MEDIA_IMAGES`, `READ_EXTERNAL_STORAGE`, `SYSTEM_ALERT_WINDOW`, `QUERY_ALL_PACKAGES`, `PACKAGE_USAGE_STATS`, root, network interception; no new AccessibilityService |

`PACKAGE_USAGE_STATS` is deliberately not requested, so Marksy cannot verify which app is in the foreground (see section 7).

## 4. Data-flow and retention matrix

| Data | Where | Kept | Can leave the phone |
|---|---|---|---|
| Screenshot / frame image | Memory only, never written | Released right after OCR | No |
| Image SHA-256 (`sha256:<hex>`) | `capture_evidence` | 7 days (30 if accepted) | No |
| OCR text (`extractedText`) | `tip_candidates` | Until review; cleared on Reject; 7 days (30 accepted) | No, never sent raw |
| OCR text containing a one-time code | Not stored; evidence row `FAILED` code `one-time-code` | 7 days (row only) | No |
| Reviewed fields (symbol, side, entry, target, SL, horizon) | `tip_candidates` | 7 days (30 accepted) | Yes, only on user Send + verified allow-listed app source, as one canonical line (e.g. `BUY ABC @ 500 Target 650 SL 470 Intraday`) after `TipTextCleaner.clean`, plus app package, channel label, salted device event key, capture time |
| Workflow row (source package, key, state) | `capture_workflows` | 12 h TTL, row 7 days | No |
| Notification events | `notification_events` (existing) | 7 days non-trading, 30 days trading | Existing `CaptureGate` rules, unchanged |
| Logs | logcat | n/a | Ids, enum names and fixed codes only; no text, symbol, price, URI or file name |
| ML Kit usage metrics | Google (see section 6) | Per Google | Possibly; never image content |

Unreviewed candidates expire after 3 days. Duplicate detection: same hash within 24 h, or workflow already reviewed. Retention runs in `RetentionWorker` with `CaptureGateway.sweep` (expires workflows past TTL, fails sessions stuck over 10 min as `stale-session`).

## 5. Lifecycle and workflow states

Candidate states: `CAPTURED -> PENDING_EXTRACTION -> EXTRACTED | REVIEW_REQUIRED -> ACCEPTED | REJECTED`; `EXPIRED` from any non-terminal state; `FAILED` from CAPTURED/PENDING_EXTRACTION. Terminal: ACCEPTED, REJECTED, EXPIRED, FAILED. EXTRACTED means symbol, side, target and stop loss found, no ambiguity, confidence 0.8 or more; anything less is REVIEW_REQUIRED. Both need user review before anything is forwarded. Illegal moves throw.

Workflow states (notification teaser to screen capture): `NOTIFICATION_RECEIVED -> NEEDS_SOURCE_VIEW -> USER_OPENED_SOURCE -> CAPTURE_REQUESTED -> CAPTURE_AUTHORIZED -> EXTRACTION_PENDING -> REVIEW_REQUIRED -> ACCEPTED | REJECTED`. NEEDS_SOURCE_VIEW may skip to CAPTURE_REQUESTED. `CAPTURE_DENIED`, `CAPTURE_FAILED`, `PROTECTED_SCREEN`, `SOURCE_UNAVAILABLE` can return to CAPTURE_REQUESTED (retry) until EXPIRED (12 h TTL).

MediaProjection session: consent each time, then foreground service with the mediaProjection type, ongoing notification with "Capture now" and "Stop", one frame, then VirtualDisplay/ImageReader/projection released and service stopped. Auto-stops after 3 min without a capture (`session-timeout`). System revocation gives `projection-revoked`. A near-black frame (98 percent or more dark samples) is `protected-screen`; Marksy never retries silently or tries to bypass it. Hidden content (API 34+ visibility callback) gives `content-hidden`. Process death is recovered by `sweep` as `stale-session`.

## 6. Privacy boundaries

- No image or frame is stored; only the hash.
- OCR is on device. ML Kit text recognition is bundled (no model download). Per Google's ML Kit data disclosure (https://developers.google.com/ml-kit/android-data-disclosure), on-device APIs can send usage and diagnostic data such as device and app info, performance metrics, API configuration, input/output sizes and error codes; the page does not list image content. Wording above is a paraphrase of a fetched summary; read the page directly before filling the Data safety form (partly unverified).
- A one-time code anywhere in recognized text drops the whole capture (user rule 2026-10-02).
- Only reviewed fields leave, rebuilt as a canonical line; never raw OCR text.
- Only `sourceVerified` tips may be sent. Verified means Android package identity: the originating notification workflow, or `getLaunchedFromPackage` on API 34+ shares. A source the user picks in review is a local label only (it could forge another channel's record). Trust scores are universal, so the backend contract is unchanged. Remaining work, not done: an optional server `captureMethod`/`sourceVerified` field (contract change in marksy-api).
- Residual risk: a full-screen capture may show an app other than the workflow's source; the verified package comes from the notification, and Marksy cannot check the foreground app without `PACKAGE_USAGE_STATS`.
- Own orders/holdings text (`isOwnOrderEvent`/`isOwnAccountEvent`) never sends.
- No automated interaction with other apps (no clicks, scrolls, typing, navigation); the user opens the source app and taps Capture now.
- Chat/SMS sources never deliver from capture.

## 7. Android-version differences

| API | Behavior |
|---|---|
| 26-28 | minSdk 26 path compiles and is guarded. Foreground service type not applicable; MediaProjection still needs consent. Image decode via BitmapFactory with sampling. Photo Picker available only through the androidx.activity backport (range for API 26-29: unverified). Not device-tested. |
| 29-33 | Foreground service with projection type needed before `getMediaProjection` (Android 10+; not confirmed on the fetched guide, unverified). Android 13 adds `POST_NOTIFICATIONS`; typed `EXTRA_STREAM` getter used on 33+. Not device-tested. |
| 34 | Manifest `mediaProjection` FGS type required; consent token single use; callback must be registered before `createVirtualDisplay` (implemented). Consent uses `MediaProjectionConfig.createConfigForUserChoice()` so the user may pick a single app window (excludes status bar, nav bar, notifications). `getLaunchedFromPackage` available for share provenance. `onCapturedContentResize`/visibility callbacks handled. |
| 35 | Prominent status-bar chip with tap-to-stop (QPR1+); projection stops on screen lock; notification content and activities of OTP-posting apps hidden during screen sharing (per Android 15 behavior-change docs). Untrusted notification listeners get redacted content for notifications carrying a detected OTP. |
| 36 | Device-tested for share/OCR/migration only (OnePlus 12R, Android 16); MediaProjection not yet tested. |
| 37 | Unverified; no documentation reviewed, no device. |

## 8. Play-policy considerations

Sources: Play Console Help pages 10964491 (Accessibility API), 13392821 (foreground service declaration), 14115180 (photo/video permissions), 10208820 (SMS/Call Log), 10144311 (User Data); developer.android.com/media/grow/media-projection and /about/versions/15/behavior-changes-all; ML Kit data disclosure (above).

- Notification access: user-granted in system settings; User Data policy applies (prominent disclosure and consent for data beyond user expectation). A dedicated Play declaration page for notification listeners: unverified. Risk: a reviewer may see "reads all notifications" as broad; needs in-app prominent disclosure before sending the user to settings and a matching privacy policy. Android 15 redaction means tip detection must not depend on notifications that carry an OTP.
- Foreground service mediaProjection (https://support.google.com/googleplay/android-developer/answer/13392821): for targetSdk 34+, the manifest type plus a Play Console declaration per type (functionality, user impact if interrupted, video of the flow, use case). The listed use case is projecting media to non-primary displays or streaming; screenshot-to-OCR is not a listed use case, so expect a custom use case (how it is judged: unverified). The video should show the consent dialog, the Android status indicator and the service stopping. Medium risk.
- MediaProjection consent: https://developer.android.com/media/grow/media-projection. One consent per session, new token per session, callback first, single frame. Partial sharing may yield a frame without the broker app; UX handles "nothing recognized".
- Photo Picker vs `READ_MEDIA_IMAGES` (https://support.google.com/googleplay/android-developer/answer/14115180): media permissions only if system pickers are not sufficient. This build uses the picker and share, no permission. Do not add `READ_MEDIA_IMAGES`.
- User Data, prominent disclosure and consent (https://support.google.com/googleplay/android-developer/answer/10144311): disclosure in normal app usage, affirmative consent, naming data, purpose and that only reviewed fields leave after user action. A dedicated disclosure before notification access, projection and sending is still to be built or confirmed; wording and placement are the usual rejection cause.
- Data safety form: likely declarations are user-generated content (tip fields) and device or other IDs (customer id) collected by the developer on Send; on-device OCR text and images are not collected if they never leave the device (Google's definition of on-device processing: unverified). ML Kit metrics must be reflected (section 6). Risk: mismatch between form and flows.
- SMS/Call Log (https://support.google.com/googleplay/android-developer/answer/10208820): `READ_SMS`/`RECEIVE_SMS` are limited to default handlers or approved exceptions. Excluded here; SMS-origin tips arrive via notification, share or screen capture. Check library manifests for stray SMS permissions.
- Other open review risks: pre-existing `READ_CONTACTS`, `RECORD_AUDIO`, `CAMERA` need declared, defensible purposes.

### Existing AccessibilityService audit (`MarksyWhatsAppAccessibilityService`, unchanged)

What it does:
- Events `typeWindowContentChanged|typeWindowStateChanged`, feedback generic, timeout 250 ms; package filter `com.whatsapp`, `com.whatsapp.w4b` in XML and code.
- Reads `event.text` only. No node traversal in the service (`WhatsAppAccessibilityParser` appears unused by it). No `canRetrieveWindowContent`. `isAccessibilityTool` not set (false).
- Allow-list first: sender watch-list (max 25); an empty list stores nothing. Matches are classified and passed to `IngestionPipeline.ingest` as `RawCapture` with connector `whatsapp-accessibility`, body up to the max length.
- Manifest: exported, `BIND_ACCESSIBILITY_SERVICE`, label "Marksy OS WhatsApp Connector", settings activity `WhatsAppSettingsActivity`.

Consent UI: system service description ("Reads visible WhatsApp accessibility text only for sender names on your Marksy OS allow-list.") and `WhatsAppSettingsActivity` with an explanation card and a button to system Accessibility settings. No separate in-app prominent disclosure with an affirmative accept before the redirect was found.

What can leave the phone (traced in code): the service creates `RawCapture` with `groupConversation = null` (only the notification listener sets it, from MessagingStyle). In `IngestionPipeline.ingestNow`, `CaptureGate.queues` returns false for any chat medium unless `chatGroup == true`, so the row is stored as `NOT_APPLICABLE` and never queued. If it were queued, `CaptureGate.decide` also returns `Keep(group-unknown)` for `chatGroup != true`. The row never enters `CandidateDeliveryPolicy` (not a capture candidate). `onStored` calls `CaptureGateway.onNotificationStored`, whose planner treats chat packages as unsupported (no workflow). Net: WhatsApp messages from the accessibility service are stored on the phone and feed local intelligence (inbox, rules), but no accessibility-sourced message reaches the backend. Separately, WhatsApp group notifications from the notification listener (`chatGroup == true`, allow-listed group) can already be delivered by the pre-existing `CaptureGate` path; that is not changed by this branch. (Traced from code, not exercised on device.)

Play policy comparison (Help 10964491): not an accessibility tool, so it needs in-app prominent disclosure in normal usage, affirmative consent by tap, a Permission Declaration Form naming the data, and cannot rely on the privacy policy alone. The current flow does not clearly meet this. Reading other apps' message content for a non-accessibility purpose is commonly rejected (reviewer experience, not stated on the page: unverified).

Options (open decision for the user; none chosen):
1. Retain: keeps WhatsApp coverage for existing users. Requires a compliant disclosure and consent screen plus a Play declaration; highest review risk and could affect the whole listing.
2. Disable for the Play build: a `play` flavor removes the service and its settings activity (`tools:node="remove"`), another flavor keeps it. Play users lose that connector (listener, share, picker and projection remain). Adds a flavor; HealthRepository and MainActivity must hide the connector in the Play build.
3. Remove: delete service, XML, manifest entry, status helper and watch-list code. Permanently clears the concern; existing users lose the feature and the watch-list data must be migrated or dropped.

Lowest review risk: 3, then 2, then 1.

### Why SMS permissions and a capture AccessibilityService are excluded from the capture design

SMS permissions fall under a restrictive policy limited to default handlers or approved exceptions, and a notification, share or screenshot reaches the same tips without them. The capture feature does not add an AccessibilityService because reading other apps' screens through accessibility for a non-accessibility purpose is a high-rejection pattern and the user-consented, single-frame MediaProjection path covers the need. This is a statement about the new capture architecture only; the pre-existing service is audited above.

## 9. Known limitations

- Notification redaction: apps or users can hide notification content; Android 15 redacts content for untrusted listeners when an OTP is detected, and notifications are hidden during screen sharing. Such cases become teaser/redacted workflows needing the user to open the source.
- `FLAG_SECURE`: many broker and banking apps render black frames. Marksy reports "This app blocks screen capture" and does not retry or bypass. Found on device: Moneycontrol (`com.divum.MoneyControl`) sets FLAG_SECURE on its HomeActivity window (tip screens); `adb screencap` returns an all-black frame apart from the status bar, so screen capture cannot read Moneycontrol tips by design. Play-safe alternatives, in order: (a) the source app's own Share action sending text or links to Marksy (Marksy accepts shared images only; an `ACTION_SEND text/plain` target is remaining work); (b) notification text when it carries the full call (already captured); (c) manual entry in the review form; (d) the source's website in a browser, if not protected. Explicitly excluded: reading protected screens via AccessibilityService, root, overlays, FLAG_SECURE bypass, or fetching paywalled content server-side.
- Package-name case: Android package names are case-sensitive (e.g. `com.divum.MoneyControl`) while capture lists are lowercase. Capture now preserves the notification's package and matches installed launcher apps case-insensitively (found on device, fixed in bc1fae0). The pre-existing `SourceRegistry.displayName` lowercases before lookup and so labels such apps by package tail (e.g. "moneycontrol"); left unchanged deliberately because that label feeds the channel label sent to Marksy, and changing it could split an existing server channel.
- App-window capture is available only on API 34+; below that the user shares the full screen, which can include unrelated content (see residual risk).
- OCR limits: Latin script only, noisy text, broken lines and look-alike characters (`5O0`) become null fields plus ambiguity notes; the user reviews and edits.
- Source-specific limits: only allow-listed app packages (server capture list or on-device broker hints) can send; chats and SMS never do.
- Photo Picker source is unknown, so a picked screenshot cannot be sent.
- Share provenance is available only on API 34+ and only when the sender shares its identity.
- One frame per consent; a session lost to process death ends as `stale-session`.

## 10. Device-test results

Device: OnePlus 12R, Android 16 (API 36), ColorOS, 2026-10-04. Builds (debug, installed over the user's existing install): `754e72b` (Phases A and B), `162a4a5` (full UI), `bc1fae0` (fix round and device fixes).

Migration and listener
- DB migration 8 to 9 on real user data: no crash; capture sweep queried the new tables (`stale=0 workflows=0 candidates=0`).
- Notification listener rebound after each reinstall (backfilled 190, later 188 active notifications). A live notification logged `no workflow (unsupported-source)`.
- Notification Access revoked via `cmd notification disallow_listener`: Health showed "Permission not granted" / "Off, tips can't be caught", no crash; re-allowed, listener reconnected.

Share and OCR (bundled ML Kit, synthetic screenshots)
- Share target for `image/*` registered (`query-activities` lists `MainActivity`). Share without a URI grant: `image-unreadable`, no crash.
- BUY TATAMOTORS Entry 650 Target 700 SL 630 Short term: EXTRACTED. SELL HDFCBANK Entry 990 Target 950 SL 1010 Intraday: EXTRACTED.
- BUY INFY @ 1480 Target 1550 (no SL): REVIEW_REQUIRED. "BUY SBIN or BUY PNB" Target 820 SL 780: REVIEW_REQUIRED.
- Non-trading text: not-a-tip. Tip text plus "Your OTP is 482913": `one-time-code`, evidence only, no candidate or text.
- Identical re-share: duplicate of the earlier candidate; re-sharing an image whose candidate was rejected: duplicate, nothing re-opened.

Privacy and permissions
- Leak check over full logs: Marksy's process logged no tip words, symbols, prices or the OTP (one whole-word match was a system BatteryStats line); MarksyCapture lines carry ids, method, state and fixed codes only.
- Only `FOREGROUND_SERVICE` and `FOREGROUND_SERVICE_MEDIA_PROJECTION` added; no `READ_MEDIA_*`, `READ_EXTERNAL_STORAGE`, `SYSTEM_ALERT_WINDOW` or SMS permissions. Test images were deleted from the phone after each run.

UI (162a4a5, bc1fae0)
- Captured tab "To review" lane listed candidates; OCR misread INFY as "INEY" (why review exists); the ambiguous SBIN/PNB card showed no symbol and "Check details".
- Review dialog: method pill, "Source chosen by you", read %, editable fields, source picker ("kept on this phone only"), Send to Marksy disabled with "Tips with a source you picked yourself stay on this phone.", Recognized text collapsed. Keep on phone gave ACCEPTED `queued=false`; Reject gave REJECTED `queued=false`; both left the lane.
- Photo Picker: "Add screenshot" opened `com.google.android.photopicker` with no permission prompt; Back cancelled with no row; pick and Done gave `USER_SELECTED_IMAGE` EXTRACTED and opened review automatically.
- Health > Capture card: Notification access On, Screen capture "Asked every time", Photos "System picker, no permission", tip counts, Teasers waiting 0, last failure in words.
- Rotation with a review open (portrait, landscape, portrait): review stayed open, no crash.
- Process death (`run-as kill -9`): system restarted the process (listener bound); the next share gave `USER_SHARED_IMAGE` EXTRACTED and review opened.

Defects found on device, fixed
- Source picker listed not-installed and duplicate sources ("icicidirect", "kite", "lite", "pro"): now installed apps only, deduped (picker shows 5paisa, Coin, ICICI Direct, Kite, Moneycontrol, StockGro, Upstox, Other).
- Workflow stored a lowercased package (`com.divum.moneycontrol`), so View/Capture tip could not open Moneycontrol and it was missing from the picker: case now preserved, launcher lookup case-insensitive (bc1fae0; see section 9).

Real-world finding: Moneycontrol's HomeActivity window has FLAG_SECURE (`dumpsys window`); `screencap` of its tip screen is black apart from the status bar (see section 9).

### Still untested on a device

MediaProjection consent and the single-app (app-window) choice; "Capture now" and Stop; projection revocation; protected-screen detection on a real FLAG_SECURE app through Marksy's own capture; View tip with and without a preserved contentIntent; duplicate capture within a workflow; process death mid-session. Reason: these need a teaser workflow from an allow-listed source; none arrived during testing, and seeding a fake notification into the user's real data was not done. API 26-35 behavior is also not device-tested.

Unit-tested only (JVM): lifecycle, extractor, planner, registry, gateway, delivery policy and run, retention, DAO and migration, frame inspector, session controller, messages, source opener, image intake.

## 11. Remaining work

- Text share target (`ACTION_SEND text/plain`) so source apps' Share action can send tip text or links.
- Optional server-side `captureMethod`/`sourceVerified` field (marksy-api contract change).
- The device tests listed above.
- User decision on the WhatsApp AccessibilityService (section 8 options; none chosen).
