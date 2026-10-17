# Option-tip safety tracker (Proposals A and B, CALL/PUT)

Per-route status only. Marksy tracks no options; an option or maybe-option call must never leave the phone, and the server must never score one as its underlying.
App: marksy-os `fix/option-stage0-app`. Server backstop: marksy-api `fix/option-callput`. Merged 2026-10-17 (os #163, api #124); deploy is not approved.
Follow-ups (2026-10-17, not merged): option-index correction block, route-gap tests and the screenshot text re-read on marksy-os `fix/screenshot-ocr-recheck`.

Shared pieces:
- `OptionGate` (`capture/OptionGate.kt`): one rule set, OPTION / AMBIGUOUS (bare "18 PE") / NONE. Reads CE/PE and CALL/PUT, spaced, fused ("NIFTY24500CALL") or with an expiry, any case. P/E valuations ("trades at 18 PE", "18x PE", "18 PE ratio") are blanked first.
- `CaptureGate.decide`: keeps any matching message (`option-unsupported`) before the label check and before any payload is built; re-run on every attempt. `requeuesOnUpdate`: a follow-up line to an option thread never splits off on its own.
- `MarksyTipsApiClient.capture`: refuses a matching message with no HTTP request (`OptionTipBlocked`); delivery records it as kept, never failed or retried. A screenshot's sent line is rebuilt from its fields and never carries option words, so this block can't see a screenshot's option.
- `CandidateDeliveryPolicy.recognizedTextKeep` (screenshots): the recognized text is re-read with `OptionGate` at review, at queueing, on every delivery decision and again right before the request, whatever the stored flags say (an older app's row may have none). Written option wording (`writtenOption`: OPTION from `OptionGate.classify` or the extractor's `captureOption`, which also reads a short strike right after the symbol, "BUY YESBANK 20 PE"; or a flagged text cut at the 4,000-character storage limit) always stays on the phone; AMBIGUOUS passes only with the reviewer's stock correction, which must meet every stock rule; no text fails closed (`recognized-text-missing`) until the screenshot is captured again. The text sits on the candidate row with its fields, so "stale" evidence reduces to missing or blank text.
- Status shown: "Option call: option tracking unsupported, not sent to Marksy."
- Server backstop: the parser keeps a CALL/PUT contract whole (`TCP-003`), `is_option_contract` reads CALL/PUT symbols (`DATA_UNRESOLVED` / `OPTION_UNSUPPORTED`). #124 also added a BUY entry floor (under half the market is `ENTRY_OFF_MARKET`); that is a trading-policy change awaiting its own decision, and option safety doesn't rely on it: a premium read as its underlying is voided unscored by the tracker (marksy-api `test_a_premium_read_as_its_underlying_is_voided_unscored`, local `d906722`).

Column key: Det = detection implemented, Gate = gate enforced, NoReq = no-outbound-request test passed, Cov = retry/offline/connector coverage, Ret = retention reviewed, Local = locally tested (full suite).

| Route | Det | Gate | NoReq | Cov | Ret | Local | Pushed | Merged | Deployed | Phone |
|---|---|---|---|---|---|---|---|---|---|---|
| Broker app notifications (listener) | Yes | Yes | Yes | Yes | Unchanged | Yes | Yes | Yes | BLOCKED | NOT VERIFIED |
| SMS (messaging apps) | Yes | Yes | Yes | Yes | Unchanged | Yes | Yes | Yes | BLOCKED | NOT VERIFIED |
| WhatsApp notifications | Yes | Yes | Yes | Yes | Unchanged | Yes | Yes | Yes | BLOCKED | NOT VERIFIED |
| WhatsApp accessibility service | Yes | Yes | Yes | Yes | Unchanged | Yes | Yes | Yes | BLOCKED | NOT VERIFIED |
| Telegram notifications | Yes | Yes | Yes | Yes | Unchanged | Yes | Yes | Yes | BLOCKED | NOT VERIFIED |
| Gmail connector | Yes | Yes | Yes | Yes | Unchanged | Yes | Yes | Yes | BLOCKED | NOT VERIFIED |
| Calendar connector | Yes | Yes | Yes | Yes | Unchanged | Yes | Yes | Yes | BLOCKED | NOT VERIFIED |
| Added lines on an existing row | Yes | Yes | Yes | Yes | Unchanged | Yes | Yes | Yes | BLOCKED | NOT VERIFIED |
| Classifier-version re-queue | Yes | Yes | Yes | Yes | Unchanged | Yes | Yes | Yes | BLOCKED | NOT VERIFIED |
| Retry, Send now, stale IN_FLIGHT reset, offline | Yes | Yes | Yes | Yes | Unchanged | Yes | Yes | Yes | BLOCKED | NOT VERIFIED |
| Screenshot review (shared, picked, screen capture) | Yes | Yes (review + run) | Yes | Yes | Reviewed | Yes | Yes | Yes | BLOCKED | PARTIAL (shared image only) |
| Screenshot text re-read, any flags (follow-up) | Yes | Yes (review, queue, decide, pre-request) | Yes | Yes | Unchanged | Yes | PR | No | BLOCKED | PARTIAL (shared image only) |
| Option index or written option wording: no stock correction (follow-up) | Yes | Yes | Yes | n/a | Unchanged | Yes | PR | No | BLOCKED | PASS (shared image) |
| Client-level hard block (every route) | Yes | Yes | Yes | n/a | n/a | Yes | Yes | Yes | BLOCKED | NOT VERIFIED |
| Server backstop (`/tips/ingest-text`) | Yes | Yes | n/a | n/a | Unchanged | Yes | Yes | Yes | BLOCKED | n/a |

Evidence (tests):
- Every automatic channel × CE option, maybe-option, later hedge, CALL wording, P/E ratio; option while the capture list is unknown: `CaptureGateTest.optionAndMaybeOptionCallsStayOnThePhoneOnEveryRoute`.
- Option-thread follow-ups: `CaptureGateTest.aFollowUpToAnOptionThreadNeverQueuesOnItsOwn`.
- Zero client calls through the real delivery run (option and maybe-option rows from broker app, SMS, WhatsApp, Telegram, Gmail and Calendar packages) across the first run, offline retry, a requeued failure and stale reset: `TradingDeliveryRunTest.optionAndMaybeOptionCallsNeverReachTheClientOnAnyAttempt`. User Retry through `NotificationRepository.retryDelivery`: `TradingDeliveryRunTest.retryingAFailedOptionCallNeverSendsIt`. Send now enqueues the same worker (`TradingDeliveryWorker`, which runs both delivery runs): `TradingDeliverySchedulerTest.immediateDeliveryEnqueuesTheTradingDeliveryWorker`.
- Zero client calls from appended lines, classifier re-queue and the accessibility connector (a group row asserted PENDING, then kept; rows without a group flag never queue): `IngestionPipelineTest.optionCallsFromEveryIngestionPathNeverReachTheClient`; maybe-option appended lines and re-queue: `IngestionPipelineTest.maybeOptionLinesFromAppendAndRequeueAreNeverSent`.
- Zero client calls from rows synced by the real `GmailConnector` and `CalendarConnector`, with a stock control that is sent: `ConnectorSyncTest.connectorOptionCallsNeverReachTheClient`, `ConnectorSyncTest.calendarOptionAndMaybeOptionTitlesAreQueuedThenNeverSent`.
- Zero client calls for queued flagged or contract-shaped screenshots (screen capture, shared, picked): `TradingDeliveryRunTest.aQueuedOptionScreenshotNeverReachesTheClient`; OCR through review for picked and screen capture: `CaptureGatewayTest.optionScreenshotsFromEveryMethodAreReviewedAndNeverSent`.
- Zero HTTP requests from the real `MarksyTipsApiClient` (only `execute()` recorded) behind the real delivery runs: `OptionRouteCompositionTest`:
  - `theRealClientGetsNoRequestForOptionOrMaybeOptionNotifications` and `theRealClientGetsNoRequestForOptionScreenshotCandidates`;
  - `theRealClientGetsNoRequestForAScreenshotWhoseTextReadsAsAnOptionWhateverItsFlags`: CE/PUT/CALL index text with plausible stock fields, a hedge note, "18 PE", a non-option flag only, a queued-before row after failures, a stale IN_FLIGHT row recovered, missing and blank text, a stock correction over "TCS 3500 CE/PE", "RELIANCE 1400 PE", "INFY 1800 CALL/PUT", "BANKNIFTY 52000 PUT" and "YESBANK 20 PE", and over a flagged text cut at 4,000 characters; a plain stock screenshot, P/E wording and a corrected "18 PE" are each sent once;
  - `aDecisionThatSkipsThePolicyStillSendsNoOptionScreenshot`: the re-read right before the request holds even when the delivery decision is swapped.
- A reviewed screenshot whose text reads as an option is not queued whatever its flags: `CaptureGatewayTest.aReviewedScreenshotWhoseTextReadsAsAnOptionIsNotQueuedWhateverItsFlags`. A stock correction never queues written option wording, and the original flag stays: `CaptureGatewayTest.aStockCorrectionNeverQueuesAScreenshotWithWrittenOptionWording`.
- `TradingDeliveryRunTest.optionAndMaybeOptionCallsNeverReachTheClientOnAnyAttempt` and `IngestionPipelineTest.optionCallsFromEveryIngestionPathNeverReachTheClient` fail with the `CaptureGate.decide` option check switched off (checked 2026-10-17). The screenshot re-read tests failed before the fix (real POSTs of "BUY RENUKA @ 180 Target 220 SL 150", 2026-10-17).
- Client refusal kept, not failed (calls and screenshots): `TradingDeliveryRunTest.aCallTheClientRefusesAsAnOptionIsKeptNotFailed`, `TradingDeliveryPolicyTest.anOptionTheClientRefusesIsKeptNotFailed`.
- No HTTP request: `MarksyTipsApiClientRecordedReceiptTest.anOptionOrMaybeOptionCallIsNeverRequested`.
- An option index (NIFTY, BANKNIFTY, FINNIFTY, MIDCPNIFTY, NIFTYNXT50, SENSEX, BANKEX) read as an option can't be corrected to a stock tip (user decision 2026-10-17): `CandidateDeliveryPolicyTest.aFlaggedIndexCannotBeConfirmedAsAStockTip`.
- Review correction (A), only for a maybe-option and stored only when every stock rule passes (Send or Keep): `CandidateDeliveryPolicyTest.aConfirmedStockTipTakesTheOrdinaryStockPath`, `CaptureGatewayTest.confirmingAStockTipKeepsTheOptionFlag`, `CaptureDaoTest` kept list and retention.
- Screenshot option words outside the first call ("BUY NIFTY @ 180 TGT 220 SL 150 (24500 CE)") flag a maybe-option at extraction, symbol kept: `TipExtractorTest.aLaterCallOrAHedgeNoteKeepsTheStockSymbolButStaysLocalUntilReviewed`; the re-read of the whole text reads OPTION, so review offers no correction (mockup state 1d).
- Rules, forms and false positives: `OptionGateTest` (CALL/PUT, spacing, case, fused, expiry, mixed, "Call me…", "call 98765…", "put SL"), `TipExtractorTest`; sent once through the real run: `TradingDeliveryRunTest.stockTipsSayingPutSlOrIntradayCallsAreSentOnce`.
- Phone (OnePlus CPH2585, debug builds `e73fade` installed 12:49 IST and `413a526` installed 13:08 IST, 2026-10-17; Send never tapped): shared screenshots of "BUY TCS @ 45 TGT 60 SL 35 (3500 CE)" and "BUY YESBANK 20 PE @ 1.5 TGT 3 SL 1" opened review with no stock pill, Send disabled and "The screenshot names an option contract (…), so it stays on this phone."; "BUY NIFTY @ 180 TGT 220 SL 150 (24500 CE)" showed the index message; "BUY TCS (18 PE) …" kept the pill. Each Keep logged `queued=false`; the kept cards read "Kept on this phone. Not sent to Marksy." Not exercised on the phone: picked and screen-capture screenshots, notification and connector routes, offline and retry.
- Server: `test_trade_call_parser` (CALL/PUT forms, ordinary "call"/"put"), `test_tip_matching` (CALL/PUT symbols, premium floor), `test_api_tips_ingest` (CALL/PUT kept out of index tracking).

Retention: automatic messages unchanged (7 days, 30 trading). Screenshot option calls kept until Remove, text cleared at 30 days; one confirmed as a stock tip (`stock-confirmed`) follows the ordinary 30-day rule. The re-read stores nothing new.

Known over-blocks (stay on the phone, by design): a message mixing a stock call with any option words; a screenshot with written option wording anywhere, even a hedge note, which no correction can send; a screenshot from an older app with no recognized text; "<3+ digit number> calls/puts" in prose ("1000 calls hit target"); "@ 1450 put SL 1420" without punctuation. A screenshot from an older app with option text and no flag is kept and never sent, but gets no kept card or Remove; it goes with accepted rows at 30 days. Not covered: a 1–2 digit PE strike neither rule set ties to a contract (away from the symbol, or after a word the extractor doesn't take as one: "BUY IDEA 15 PE") reads AMBIGUOUS like a P/E ratio, so a reviewer's correction that meets every stock rule could still send it.

Open: release build and full phone verification (notification, connector, offline and retry routes on the phone) NOT VERIFIED; server deploy BLOCKED (approval).
