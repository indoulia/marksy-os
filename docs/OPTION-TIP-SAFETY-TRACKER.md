# Option-tip safety tracker (Proposals A and B, CALL/PUT)

Per-route status only. Marksy tracks no options; an option or maybe-option call must never leave the phone, and the server must never score one as its underlying.
App: marksy-os `fix/option-stage0-app`. Server backstop: marksy-api `fix/option-callput`. Merge and deploy are not approved.

Shared pieces:
- `OptionGate` (`capture/OptionGate.kt`): one rule set, OPTION / AMBIGUOUS (bare "18 PE") / NONE. Reads CE/PE and CALL/PUT, spaced, fused ("NIFTY24500CALL") or with an expiry, any case. P/E valuations ("trades at 18 PE", "18x PE", "18 PE ratio") are blanked first.
- `CaptureGate.decide`: keeps any matching message (`option-unsupported`) before the label check and before any payload is built; re-run on every attempt. `requeuesOnUpdate`: a follow-up line to an option thread never splits off on its own.
- `MarksyTipsApiClient.capture`: refuses a matching message with no HTTP request (`OptionTipBlocked`); delivery records it as kept, never failed or retried.
- Status shown: "Option call: option tracking unsupported, not sent to Marksy."
- Server backstop: the parser keeps a CALL/PUT contract whole (`TCP-003`), `is_option_contract` reads CALL/PUT symbols (`DATA_UNRESOLVED` / `OPTION_UNSUPPORTED`), and a BUY entry under half the market is `ENTRY_OFF_MARKET`, so a premium without option words is never scored as its underlying.

Column key: Det = detection implemented, Gate = gate enforced, NoReq = no-outbound-request test passed, Cov = retry/offline/connector coverage, Ret = retention reviewed, Local = locally tested (full suite).

| Route | Det | Gate | NoReq | Cov | Ret | Local | Pushed | Merged | Deployed | Phone |
|---|---|---|---|---|---|---|---|---|---|---|
| Broker app notifications (listener) | Yes | Yes | Yes | Yes | Unchanged | Yes | Yes | BLOCKED | BLOCKED | NOT VERIFIED |
| SMS (messaging apps) | Yes | Yes | Yes | Yes | Unchanged | Yes | Yes | BLOCKED | BLOCKED | NOT VERIFIED |
| WhatsApp notifications | Yes | Yes | Yes | Yes | Unchanged | Yes | Yes | BLOCKED | BLOCKED | NOT VERIFIED |
| WhatsApp accessibility service | Yes | Yes | Yes | Yes | Unchanged | Yes | Yes | BLOCKED | BLOCKED | NOT VERIFIED |
| Telegram notifications | Yes | Yes | Yes | Yes | Unchanged | Yes | Yes | BLOCKED | BLOCKED | NOT VERIFIED |
| Gmail connector | Yes | Yes | Yes | Yes | Unchanged | Yes | Yes | BLOCKED | BLOCKED | NOT VERIFIED |
| Calendar connector | Yes | Yes (same worker gate) | Shared worker test | Shared | Unchanged | Yes | Yes | BLOCKED | BLOCKED | NOT VERIFIED |
| Added lines on an existing row | Yes | Yes | Yes | Yes | Unchanged | Yes | Yes | BLOCKED | BLOCKED | NOT VERIFIED |
| Classifier-version re-queue | Yes | Yes | Yes | Yes | Unchanged | Yes | Yes | BLOCKED | BLOCKED | NOT VERIFIED |
| Retry, Send now, stale IN_FLIGHT reset, offline | Yes | Yes | Yes | Yes | Unchanged | Yes | Yes | BLOCKED | BLOCKED | NOT VERIFIED |
| Screenshot review (shared, picked, screen capture) | Yes | Yes (review + client) | Yes | Yes | Reviewed | Yes | Yes | BLOCKED | BLOCKED | NOT VERIFIED |
| Client-level hard block (every route) | Yes | Yes | Yes | n/a | n/a | Yes | Yes | BLOCKED | BLOCKED | NOT VERIFIED |
| Server backstop (`/tips/ingest-text`) | Yes | Yes | n/a | n/a | Unchanged | Yes | Yes | BLOCKED | BLOCKED | n/a |

Evidence (tests):
- Every automatic channel × CE option, maybe-option, later hedge, CALL wording, P/E ratio; option while the capture list is unknown: `CaptureGateTest.optionAndMaybeOptionCallsStayOnThePhoneOnEveryRoute`.
- Option-thread follow-ups: `CaptureGateTest.aFollowUpToAnOptionThreadNeverQueuesOnItsOwn`.
- Zero client calls across the first run, offline retry, user Retry, Send now, stale reset and a Gmail row: `TradingDeliveryRunTest.optionAndMaybeOptionCallsNeverReachTheClientOnAnyAttempt`.
- Zero client calls from appended lines, classifier re-queue and the accessibility connector (group unknown and group): `IngestionPipelineTest.optionCallsFromEveryIngestionPathNeverReachTheClient`.
- Both run-level tests fail with the `decide` check switched off (checked 2026-10-17).
- Client refusal kept, not failed (calls and screenshots): `TradingDeliveryRunTest.aCallTheClientRefusesAsAnOptionIsKeptNotFailed`, `TradingDeliveryPolicyTest.anOptionTheClientRefusesIsKeptNotFailed`.
- No HTTP request: `MarksyTipsApiClientRecordedReceiptTest.anOptionOrMaybeOptionCallIsNeverRequested`.
- Review correction (A): `CandidateDeliveryPolicyTest.aConfirmedStockTipTakesTheOrdinaryStockPath`, `CaptureGatewayTest.confirmingAStockTipKeepsTheOptionFlag`, `CaptureDaoTest` kept list and retention.
- Rules, forms and false positives: `OptionGateTest` (CALL/PUT, spacing, case, fused, expiry, mixed, "Call me…", "call 98765…", "put SL"), `TipExtractorTest`.
- Server: `test_trade_call_parser` (CALL/PUT forms, ordinary "call"/"put"), `test_tip_matching` (CALL/PUT symbols, premium floor), `test_api_tips_ingest` (CALL/PUT kept out of index tracking).

Retention: automatic messages unchanged (7 days, 30 trading). Screenshot option calls kept until Remove, text cleared at 30 days; one confirmed as a stock tip (`stock-confirmed`) follows the ordinary 30-day rule.

Known over-blocks (stay on the phone, by design): a message mixing a stock call with any option words; "<3+ digit number> calls/puts" in prose ("1000 calls hit target"); "@ 1450 put SL 1420" without punctuation. On the server a BUY limit more than 50% under the market is rejected as off-market.

Open: release build and physical-phone verification NOT VERIFIED; server deploy BLOCKED (approval).
