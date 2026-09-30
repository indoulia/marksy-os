# Tip Ledger Phase 4b (marksy-os Screens and Deletions) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make marksy-os a read-only client of the tip ledger. The stock page gets a calls box: a group per Marksy engine and per external channel, each with its record. The Trading tab gets My tips and Scorecards with the §8.3 filters. Every on-device parser and scorer that spec §10 lists is deleted, and the capture pre-filter they fed moves into the capture gate.

**Architecture:**
- **There is no Part A.** Phase 3b already serves every read this plan needs. The rating card is removed instead of served (decision 1).
- **Reads.**
  - `market/LedgerModels.kt` (new) parses 3b's JSON shapes exactly.
  - `MarketApiClient` and `MarketIntelligenceRepository` gain six bearer-session reads: `/me/tips`, `/me/scorecard`, `/scorecards`, `/scorecards/summary`, `/scorecards/{entity}/{id}` and `/tips/{id}`. `/instruments/{symbol}` is already read; its DTO gains `calls`.
  - `market/ScorecardQuery.kt` (new) holds the §8.3 query vocabulary. The server resolves every window; the phone only sends presets or dates.
- **Presentation.** `market/LedgerCalls.kt` (new, pure) words a tip from its own `status`, `outcome` and returns. No surface in this plan reads a prediction's monitor event.
- **Screens.**
  - Stock page: `ui/CallsBox.kt` replaces `MarksyCallCard` and `MarksyRatingCard`. A tap opens `ui/TipDetailDialog.kt` (progress series).
  - Trading tab: `My tips` (`ui/MyTipsView.kt`) replaces the on-device `Calls` tab, and `Scorecards` (`ui/ScorecardsView.kt`) is added. Filters live on the floating action stack and in the title superscript.
- **Capture pre-filter.**
  - The classifier's trade-call rule is deleted, so TRADING now means executions only.
  - What may leave the phone becomes a capture-gate rule: `CaptureGate.queues` at capture and `CaptureGate.decide` at delivery. It is category-based, bounded by the server capture list and the customer's allow-list, and it keeps own-order and own-account notifications local.
  - A chat that gains a message after its row was sent gets a new capture row. A local-only chat keeps folding into one row.
- **Deletions.** `TradeCallParser`, `CallHorizon`, `DailySetups`, `MarksyTipPayloadBuilder` (its whole file), `RatingEngine`, `RatingCalibration`, `RatingSource`, `RatingCalibrator`, `StockRatingInputs`, `MarksyRatingCard`, `MarksyCalls`, the classifier's trade-call rule and `SourceRegistry`'s brand map. Their tests go too.

**Tech Stack:** Kotlin 2.3.21, AGP 9.4.0, Gradle wrapper 9.7.1, Jetpack Compose (BOM 2026.08.00), Room, WorkManager, JUnit 4 + Robolectric 4.17, `org.json:json:20240303` in unit tests.

**Spec:** `C:\AIAgent\marksy-os\docs\superpowers\specs\2026-09-29-marksy-central-tip-ledger-design.md`. This plan uses:
- §2 invariants 10–12;
- §5.1 as amended on 2026-09-30 (lines 105–108) and §15 line 370;
- §6.6 outcomes, and §7 (a withdrawn Marksy call is a SOURCE_EXIT);
- §8.1–§8.3;
- §9 customer APIs;
- §10 in full;
- §13 phase 4.

Plans for context:
- **3b (the API contract this plan builds against):** `C:\AIAgent\marksy-os\docs\superpowers\plans\2026-09-30-tip-ledger-phase-3b-scorecards.md`. Task 4 has the scorecard shapes, Task 5 `/me/tips`, `/me/scorecard` and `/tips/{id}`, and Task 6 `/instruments/{symbol}` `calls`.
- **4a (capture; Part B is merged before this plan starts):** `C:\AIAgent\marksy-os\docs\superpowers\plans\2026-09-30-tip-ledger-phase-4a-capture.md`. See decisions 5, 12, 13 and 17, and Task B3's `CaptureGate`.
- **2c (rating engine):** `C:\AIAgent\marksy-os\docs\superpowers\plans\2026-09-30-tip-ledger-phase-2c-rating-engine.md`. See decisions 1, 5, 6 and 10.
- **3a/5 (admin):** `C:\AIAgent\marksy-os\docs\superpowers\plans\2026-09-30-tip-ledger-phase-3a-5-admin.md`, for context only.

## Global Constraints

- **Repo and worktree.**
  - Repo: `C:\AIAgent\marksy-os`. Work in the worktree `C:\AIAgent\marksy-os-phase4b` on branch `feat/tip-ledger-screens`, created from `origin/main`. Leave `C:\AIAgent\marksy-os` on its current branch.
  - **Start only after 4a Part B has merged and 3b is deployed.** Task B1 Step 1 checks both and stops otherwise.
  - Task B8 edits code that 4a wrote. Its anchors quote 4a's plan (Task B3 Step 5, Task B2 Step 4). If an anchor is not found verbatim, read the file and apply the same change to the equivalent line. Do not re-derive the rule.
- **Commits, PR and merge.**
  - Commit once per task. Commit messages end with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`. PR bodies end with `🤖 Generated with [Claude Code](https://claude.com/claude-code)`.
  - **Merging is authorized:** run `gh pr merge --merge --delete-branch` once Task B10's suite is green. GitHub Actions don't run (account billing), so an UNSTABLE status is expected and does not block the merge.
  - **No device install.** This plan ends at PR, merge and `assembleDebug`. The controller installs on the phone in the morning and does the on-device visual check then.
- **Unit tests, run from `C:\AIAgent\marksy-os-phase4b`.**
  - The Windows user `Path` has a corrupted entry that kills forked test JVMs. Strip it first.
  - Stop Gradle once per shell session, then run with `--no-daemon`.
  - Each step names its own `--tests` filter. Task B1's filter is shown here:

  ```bash
  cd /c/AIAgent/marksy-os-phase4b
  export PATH="$(echo "$PATH" | tr ':' '\n' | grep -vE '"|%' | paste -sd:)"
  ./gradlew --stop
  ./gradlew --no-daemon :app:testDebugUnitTest --tests 'com.marksy.os.market.LedgerModelsTest'
  ```
- **Never run `connectedAndroidTest`.** It uninstalls the app and wipes the user's data.
  - This plan has no on-device step: no `adb`, no install, no `am start`.
  - The OnePlus is the user's personal phone. Any later on-device check needs the window-focus safety gate from the `marksy-os-local-run-env` memory.
- **Regexes run on ICU on Android, but on the JVM in unit tests.** Escape every literal brace or bracket. The one new regex (`ownAccountEvents`, Task B8) has none.
- **Tests guard real logic only** (the user's test budget).
  - No UI-layout tests.
  - Prefer extending an existing test file.
  - Every test this plan adds is named in its task. The one Compose test (Task B5) guards which data source the stock page believes, not its layout.
- **UI conventions** (the `feedback_ui_consistency` and `feedback_compact_headers` memories).
  - Popups use `MarksyDialog`. Choices use the Marksy `Pill` in a wrapping `FlowRow`. The one date picker is the `DatePickerDialog` that `PlanScreen` already uses.
  - No heading, label or button row at the top of a page. The Trading sub-filter goes in the title superscript (`tradingTitleNote`), and page actions go on the floating stack (`OneHandControls(actions = …)`).
  - Cards reuse the existing Surface + BorderGlow shape, and emerald only for an open Marksy call.
- **Comments** are one line at most and explain only a non-obvious WHY. Make no unrelated refactors.
- **Spec §2, invariants 10–12, verbatim:**
  - "Customer APIs never expose another customer's identity; admin APIs may."
  - "The only customer data leaving the phone is the customer id (from the signed-in session). Private data (inbox, timeline, contacts, allow-lists) stays on the phone."
  - "Market data that is not reusable for training or learning is not stored. The app shows it straight from Upstox; the tracker stores derived facts, never raw intraday bars."
  - So: the new DTOs carry no user id (3b sends none). Nothing new is persisted on the phone; ledger reads live in memory only. Live prices on My tips and the chart come straight from Upstox.
- **Spec §5.1, amended, verbatim:**
  - "1:1 chats never leave the phone (decided 2026-09-30)."
  - "The customer's own order/execution notifications from broker apps never leave the phone (decided 2026-09-30)."
- **Marksy calls are read from the tip (3b constraint, spec §7).**
  - A withdrawn losing Marksy call is SOURCE_EXIT / FAILURE with a negative `actualReturn`, while its prediction's monitor event stays INVALIDATED.
  - Every Marksy-engine surface this plan builds (the calls box, My tips and Scorecards) reads the tip's `status`, `outcome` and returns.
  - The stock page stops rendering `predictions[]`. It reads `predictions[]` only to map a tip's `predictionId` to the `recommendationId` behind Marksy's analysis.
  - PROVISIONAL progress is always shown with its basis (`dataBasis`).
- **Out of scope:**
  - any marksy-api change;
  - the Predictions tab and the Setups list data sources (decision 9);
  - `/alerts` `tipId`;
  - the chat allow-list UI (decision 10);
  - Phase 6 route retirement.

## Review Focus

These are the inputs the spec implies but its happy paths never exercise. Each one has a test in the owning task:

1. **A withdrawn losing Marksy call on the stock page.**
   - `predictions[]` still says INVALIDATED (the monitor), while the ledger tip says SOURCE_EXIT / FAILURE with −3%.
   - The page must show "Exited · Failed" and "−3.00%", and never "Invalidated".
   - Test: Task B5, `StockDetailScreenTest.aWithdrawnLosingMarksyCallShowsAsAFailedExitNotAnInvalidation`.
2. **A PROVISIONAL evening number on an open tip.** It must read "+2.00% so far · provisional", while a FINAL one carries no flag. A progress point in the series says "provisional" too. A WAITING tip, whose return is still null, shows no number. Test: Task B3, `LedgerCallsTest.aProvisionalReturnIsLabelledAndAFinalOneIsNot`.
3. **A broker research call after the trade-call rule is gone.**
   - It classifies as MARKET, not TRADING, and must still leave the phone.
   - These stay local: a holdings alert from the same app, a P&L statement, and an OTP or a bank SMS from an allow-listed sender.
   - A group call that classifies as MESSAGES still goes.
   - Test: Task B8, `CaptureGateTest.aBrokerResearchCallStillLeavesButHoldingsAlertsAndOtpsStayLocal`.
4. **A group chat that gains a message after its row was delivered.**
   - Server re-sends of the same row return the stored receipt, so the new message must become its own capture row.
   - A 1:1 chat that gains a message must still fold into one local row, so the inbox is unchanged.
   - Test: Task B8, `IngestionPipelineTest.aNewMessageInADeliveredChatIsItsOwnCaptureAndALocalChatStillFolds`.
5. **A custom scorecard range.**
   - With one date missing, or an end before the start, it must never be sent (the server would 422).
   - A preset must never send dates left over from an earlier custom pick.
   - The saved filter must survive a process restore.
   - Test: Task B2, `ScorecardQueryTest.aCustomRangeNeedsBothDatesAndReplacesThePreset`.

## Resolved ambiguities (decisions this plan makes)

1. **The rating card is removed, not served. There is no Part A.**
   - **Spec.** §10's screens are the calls box, My tips and Scorecards; no rating card is listed. §7 makes a Rating-engine BUY or SELL verdict a ledger tip of the caller "Rating engine", and 3b's `calls.marksy.engines[]` always lists that engine, with its record and tips. So the calls box already shows the rating engine's current call and its scored history, read from the tip. A changed verdict shows as a SOURCE_EXIT with its outcome.
   - **One source per engine.** A separate verdict card would be a second Marksy-engine surface reading a non-ledger source (`stock_ratings`). It can disagree with the tip: 2c decision 3's "pending exit" leaves a HOLD verdict beside a still-ACTIVE BUY tip.
   - **What the card would lose.** `stock_ratings` stores no reasons (2c decision 10), so the card's "Why" section could not be served without more backend work.
   - **What most pages would show.** LONG is NOT_ENOUGH_DATA for every stock until EPIC-177 (2c decision 5), and stocks outside the rating universe have no row. The card would be hidden on most pages anyway.
   - **UI conventions.** Screen real estate comes first; one card fewer on the stock page, and no duplicate verdict.
   - **What is lost.** A HOLD verdict now reads "No call on this stock". If the user wants HOLD shown, the smallest follow-up is an additive `rating` object on `/instruments/{symbol}` from the latest `stock_ratings` row (listed under ambiguities in the report).
   - **Deleted.**
     - `rating/RatingEngine.kt`, `rating/RatingCalibration.kt` and `rating/RatingSource.kt`;
     - `ui/RatingCalibrator.kt`, `ui/StockRatingInputs.kt` and `ui/MarksyRatingCard.kt`;
     - `RatingCalibrationCard` on the Health screen, and its `load` in `MainActivity`.
2. **The classifier pre-filter is replaced by capture-gate rules, bounded by the server capture list.**
   - **The classifier.** The trade-call rule (`callSide`, `isTradeCall`, the `callChannels` block and every `TradeCallParser.parse` call) is deleted.
     - TRADING now means a broker execution (the unchanged execution vocabulary). It keeps its local meaning: the Captured tab, retention and delivery history.
     - Broker research calls become MARKET. SMS and chat calls keep their medium's category (OTHER or MESSAGES).
     - `VERSION` goes to 10. Reclassification only touches non-TRADING rows and only queues rows that become TRADING, so no old row is newly sent.
   - **Candidacy moves from `isTrading` to `CaptureGate.queues(sourcePackage, category, chatGroup)`, decided at capture:**
     - app notifications: TRADING or MARKET. These categories only arise from the local broker and market package sets, which the server capture list then narrows at delivery.
     - SMS: TRADING, MARKET, MESSAGES, OTHER or PROMOTIONS. The allow-listed sender decides at delivery.
     - WhatsApp and Telegram: the same categories, and only when `chatGroup == true`. 1:1 chats never queue.
   - **Never candidates:** OTP, BANKING, PAYMENTS, BILLS, REMINDERS, DELIVERY, EMAIL, WORK and SYSTEM. These are the private categories.
   - **At delivery.** `CaptureGate.decide` applies the same category check, then 4a's rules unchanged: own orders, the capture list, the allow-list and 1:1 chats.
   - **The new own-account rule.** A new `OWN_ACCOUNT` rule keeps holdings, portfolio, positions, funds, margin, account, P&L, SIP, mandate, pledge, ledger and contract-note alerts local for apps and SMS (`NotificationClassifier.isOwnAccountEvent`). It is needed because MARKET rows now go, and a "Your stock NATSEC touched its 52-week low" alert reveals holdings (invariant 11). It is not applied to groups, for 4a decision 17's reason.
   - **The OTP footer.** A "never share your OTP" footer is ignored only when the text names two or more price levels (cmp, ltp, sl, tgt, target, stoploss, entry). This keeps the regression fix for tip SMS without parsing a call, and a real OTP still wins.
   - **What now leaves the phone.** Within the capture set, the phone no longer judges what is a call. The server files non-calls as UNPARSED receipts (§5.2 step 4). The capture list stays the server's off switch for apps.
3. **Chat threads.**
   - Lines added to a row that is IN_FLIGHT or DELIVERED become a new capture row (the old split path, now call-agnostic).
   - A row that is PENDING, NOT_APPLICABLE or FAILED folds as before, so a PENDING row sends the merged thread once.
   - This replaces `existing.isTrading && TradeCallParser.parse(...)`. It keeps the old regression fixed: "a tip posted after 'Good morning' in the same chat notification".
4. **The delivery queue reads `deliveryState`, not `isTrading`.**
   - `NotificationEventDao.findPendingTrading` becomes `findPendingCapture`, with the `isTrading = 1` term dropped. No stored row is PENDING without `isTrading` today, so the upgrade queues nothing old.
   - `onTradingCaptured` keeps its name and now fires when a row is queued.
5. **Navigation.**
   - **Where.** The Trading tab's filters become Setups, Predictions, **My tips**, **Scorecards**, Captured. My tips replaces the on-device Calls tab, which `TradeCallParser` fed.
   - **Why Trading.** It already holds calls and Marksy's record, the bottom bar keeps its tabs, and a Trading filter needs no new page chrome.
   - **Filters.** My tips cycles Open → Closed → All from a floating action. Scorecards opens a `MarksyDialog` filter from a floating action. Both states show in the title superscript ("My tips · Open", "Scorecards · Channels · Last 30 days"), and both are hoisted in `MainActivity` so they survive a restore.
   - **Ask Marksy.** "calls", "trade calls" and "trading calls" now open My tips. "my tips", "scorecards", "scorecard" and "track record" are added.
6. **`DailySetups`.**
   - The Setups tab keeps Marksy's live calls (`/predictions/active`) and drops the DAILY_SETUPS report rows.
   - The server parses DAILY_SETUPS at ingest (§5.2 step 3, `app/external_tip_ingest.py`). A report becomes ledger tips, and so My tips rows, when its app is in the capture set or an admin pastes it (MANUAL).
   - A report from an app outside the capture set stays only as its raw notification in the Inbox (§5.1: nothing outside the set leaves the phone).
7. **The stock page.**
   - The calls box shows when any group has a tip. Channels are listed only when they have one (3b), so an empty box is hidden, as `MarksyCallView.None` was.
   - Inside the box, every engine is listed. One without a call on this stock says "No call on this stock" beside its record (§10: "a Marksy group per engine … each with its record").
   - Open tips are shown in full: levels, the stop–entry–target bar with today's Upstox price, and progress. Closed tips (the last 90 days) sit in a collapsed "Past calls (n)".
   - The chart lines and the trade ticket come from the newest open Marksy tip that has levels (`LedgerCalls.leadingMarksyCall`).
   - Marksy's analysis uses the Prediction engine's current tip, else its newest, mapped through `predictions[].recommendationId`.
   - A tap on any tip opens `TipDetailDialog`: terms, state, returns and the progress series.
8. **Scorecards.**
   - Items: the customer's own record (`/me/scorecard`), a Marksy-versus-external split (`/scorecards/summary`), then channels or callers ranked by trust (`/scorecards?entity=`). Engines are callers (3b decision 7), so they rank among callers.
   - The filter dialog: Show (Channels, Callers), Period (the 11 presets plus Custom, with two date pills), and Horizon (All or the 4 buckets).
   - **Basis stays `first_seen`.** The UI never sends `basis=terminal`: §8.3 says terminal is "only used when an endpoint asks for it explicitly".
   - **Narrowing.** A channel's detail dialog lists its callers with `channelId` narrowing (§8.3 "Optional narrowing by channel, caller or Marksy engine").
   - **Echo.** The echoed IST range (`filter.startDate`/`endDate`) is shown as sent; the phone computes no window. "Not enough history" replaces a null trust score (n < 10), and the invalidated count is always shown beside it (§8.2).
9. **The Predictions tab and the Setups list are unchanged.**
   - They read `/predictions/active` and `/tracking`, not the ledger. §10 does not list them.
   - Consequence: the Predictions tab's closed list can still show a withdrawn losing call as "Invalidated", while the calls box and the Prediction-engine scorecard show it as a failed exit. Listed under ambiguities; moving that tab onto `/scorecards/caller/{engine}` and the ledger is a follow-up.
10. **The chat allow-list is unchanged.** 4a decision 5 said "Phase 4b presents [it] as the chat allow-list", but §10 lists allow-lists as unchanged, so its screen and wording stay. Listed under ambiguities.
11. **My tips.**
    - The list is `/me/tips` with 50 per page, newest first, using the existing `Paged` helper (made `internal`).
    - Each row: the symbol and direction, the ledger state, the levels, progress, and the live Upstox price for open tips. Then the canonical channel and caller with the channel's headline record. Then "Received via SMS, app · 2 others got it" (`receivedVia[]` media and `alsoReceivedBy`, never anyone's identity).
    - Marksy's own calls have no receipts (3b decision 5), so they appear in Scorecards and on the stock page, not in My tips.
12. **On-device expiry and stock links.**
    - `EventExpiry` loses the call-horizon expiry and the "newer call supersedes" rule; the server now tracks horizons and revisions. TRADING rows keep the 7-day rule.
    - A broker call, now MARKET, leaves the active inbox at the next session close, and My tips follows it from there.
    - `stocksIn` uses ticker mentions only (`StockMentions`), and a row Marksy recorded as a tip (`marksyTipId`) is linkable even if it is not MARKET or TRADING. So a chat call keeps its ticker pills.
    - The Captured card's stock link uses `StockMentions` too, replacing `MarksyTipPayloadBuilder.symbolOf`.
13. **The `SourceRegistry` brand map goes.**
    - Removed: `names`, `knownSources`, `isKnownSource`, `isTradingSource`, `TRADING_PACKAGES` and the context-free `displayName`.
    - `displayName(context, package)` returns the installed app's label.
    - Channel names are the server's (§4, seeded from this map in Phase 1). An app notification resolves by package before label (`app/tip_ledger.py` `resolve_channel`), so a label change never makes a new channel.
14. **The DTOs.**
    - Decimal strings parse to `Double` through the existing `doubleOrNull`, which parses strings under both Android's and the unit tests' org.json. Returns stay fractions, and percentages stay 0–100.
    - `scopeId` (int, string or null) is kept as `String?`.
    - Ledger arrays keep up to 300 items, not the market lists' 50, because a 250-session tip has 250 progress rows.
    - New `MarketApiClient` members have default bodies, so every existing test fake still compiles.

## File Structure

**Part B only (marksy-os, paths under `app/src/`).** There is no Part A (decision 1).
- Create:
  - `main/java/com/marksy/os/market/LedgerModels.kt` (B1)
  - `main/java/com/marksy/os/market/ScorecardQuery.kt`: `ScorecardPeriod`, `HorizonBucket`, `ScorecardEntity`, `ScorecardQuery`, `ScorecardText` (B2)
  - `main/java/com/marksy/os/market/LedgerCalls.kt` (B3)
  - `main/java/com/marksy/os/ui/CallsBox.kt`, via `git mv` from `ui/MarksyCallCard.kt` (B5)
  - `main/java/com/marksy/os/ui/TipDetailDialog.kt` (B5)
  - `main/java/com/marksy/os/ui/MyTipsView.kt` (B6)
  - `main/java/com/marksy/os/ui/ScorecardsView.kt` (B7)
- Modify:
  - `market/InstrumentModels.kt` (B1); `market/MarketApiClient.kt` and `market/MarketIntelligenceRepository.kt` (B2)
  - `ui/StockDetailScreen.kt` (B4, B5); `ui/MarketScreen.kt` (B5); `ui/MarketHealthCards.kt` and `ui/HealthScreen.kt` (B4)
  - `MainActivity.kt` (B4, B6, B7, B9); `ui/TradingIntelligenceScreen.kt` (B6, B7, B9); `ui/PredictionsView.kt` (B6); `intelligence/AskMarksy.kt` (B6, B7)
  - `gateway/CaptureGate.kt`, `notification/NotificationClassifier.kt`, `connector/ConnectorFramework.kt`, `data/local/NotificationEventDao.kt` and `gateway/TradingDeliveryWorker.kt` (B8)
  - `intelligence/EventExpiry.kt`, `data/NotificationRepository.kt`, `ui/StockLinks.kt`, `ui/SetupsView.kt` and `notification/SourceRegistry.kt` (B9)
- Delete:
  - `rating/RatingEngine.kt`, `rating/RatingCalibration.kt`, `rating/RatingSource.kt`, `ui/RatingCalibrator.kt`, `ui/StockRatingInputs.kt` and `ui/MarksyRatingCard.kt` (B4)
  - `market/MarksyCalls.kt` (B5)
  - `notification/TradeCallParser.kt`, `notification/CallHorizon.kt`, `market/DailySetups.kt` and `gateway/MarksyTipPayload.kt` (B9)
- Tests (under `test/java/com/marksy/os/`):
  - Create `market/LedgerModelsTest.kt`, `market/ScorecardQueryTest.kt` and `market/LedgerCallsTest.kt`.
  - Modify `ui/StockDetailScreenTest.kt`, `ui/TradingIntelligenceScreenTest.kt`, `intelligence/AskMarksyEvalTest.kt`, `intelligence/AskMarksySearchTest.kt`, `gateway/CaptureGateTest.kt`, `gateway/TradingDeliveryRunTest.kt`, `connector/IngestionPipelineTest.kt`, `notification/NotificationClassifierTest.kt`, `intelligence/EventExpiryTest.kt` and `notification/SourceRegistryTest.kt`.
  - Delete `rating/RatingEngineTest.kt`, `rating/RatingCalibrationTest.kt`, `market/MarksyCallsTest.kt`, `notification/TradeCallParserTest.kt`, `notification/CallHorizonTest.kt` and `market/DailySetupsTest.kt`.

---

## Part B — marksy-os: ledger screens and deletions

### Task B1: Gates, worktree, and the ledger DTOs

**Files:**
- Create: `app/src/main/java/com/marksy/os/market/LedgerModels.kt`
- Modify: `app/src/main/java/com/marksy/os/market/InstrumentModels.kt` (`InstrumentLifecycleDto`, lines 67–91)
- Test: `app/src/test/java/com/marksy/os/market/LedgerModelsTest.kt`

**Interfaces:**
- Produces, in `market/LedgerModels.kt`. Each class has `companion fun parse(json: JSONObject)`, except the page, which parses the whole envelope:
  - `data class ChannelRefDto(channelId: Int, name: String, type: String)`
  - `data class CallerRefDto(callerId: Int, name: String)`
  - `data class ProgressPointDto(sessionDate: String, sessionIndex: Int, entryStatus: String, statusAfter: String, returnToDate: Double?, bestReturn: Double?, worstReturn: Double?, toTargetPct: Double?, toStopPct: Double?, barBasis: String, dataBasis: String)`
  - `data class LedgerTipDto(tipId: String, symbol: String, direction: String?, entryLow: Double?, entryHigh: Double?, entryBasis: String?, target: Double?, stopLoss: Double?, horizonSessions: Int?, horizonBasis: String?, firstSeenAt: String, status: String, entryStatus: String?, outcome: String?, reason: String?, enteredSession: Int?, closedSession: Int?, closedAt: String?, exitPrice: Double?, promisedReturn: Double?, actualReturn: Double?, predictionId: Int?, channel: ChannelRefDto?, caller: CallerRefDto?, latestProgress: ProgressPointDto?)`
  - `data class ReceiptRefDto(receiptId: String, kind: String, medium: String, channelLabel: String?, appPackage: String?, devicePostedAt: String?, recordedAt: String)`
  - `data class ScorecardHeadlineDto(total, open, completed, successful, failed, expired, invalidated: Int, successPct: Double?, hitRatePct: Double?, avgActualReturn: Double?, trustScore: Int?)`
  - `data class MyTipDto(tip: LedgerTipDto, channelHeadline: ScorecardHeadlineDto?, callerHeadline: ScorecardHeadlineDto?, receivedVia: List<ReceiptRefDto>, alsoReceivedBy: Int)`
  - `data class MyTipPageDto(items: List<MyTipDto>, nextCursor: String?)`, with `parse(envelope: JSONObject)`
  - `data class EngineCallsDto(callerId: Int, name: String, channelId: Int, scorecard: ScorecardHeadlineDto, tips: List<LedgerTipDto>)`
  - `data class ChannelCallsDto(channelId: Int, name: String, type: String, scorecard: ScorecardHeadlineDto, tips: List<LedgerTipDto>)`
  - `data class InstrumentCallsDto(asOf: String, closedWithinDays: Int, engines: List<EngineCallsDto>, channels: List<ChannelCallsDto>)`
  - `data class ScorecardFilterEchoDto(period: String, startDate: String?, endDate: String?, basis: String, horizon: String?, channelId: Int?, callerId: Int?, startAt: String?, endAt: String?, asOf: String)`
  - `data class ScorecardCountsDto(total, open, successful, failed, expired, completed, exited, invalidated, unscorable, dataUnresolved: Int)`
  - `data class ScorecardPerformanceDto(successPct, failurePct, hitRatePct, avgActualReturn, totalActualReturn, avgPromisedReturn, totalPromisedReturn, returnRealizationPct, avgDaysToCompletion: Double?)`
  - `data class ScorecardTrustDto(trustScore: Int?, completed: Int, minimumCompleted: Int, wilsonLowerBound: Double?, returnQuality: Double?, invalidated: Int)`
  - `data class ScorecardBodyDto(counts: ScorecardCountsDto, performance: ScorecardPerformanceDto, trust: ScorecardTrustDto)`
  - `data class ScorecardDto(version: String, scope: String, scopeId: String?, name: String?, channelId: Int?, channelName: String?, filter: ScorecardFilterEchoDto, body: ScorecardBodyDto)` (3b `ScorecardView`)
  - `data class EntityScorecardDto(entity: String, id: Int, name: String, channelId: Int?, channelName: String?, body: ScorecardBodyDto)`
  - `data class EntityScorecardListDto(version: String, entity: String, filter: ScorecardFilterEchoDto, items: List<EntityScorecardDto>)`
  - `data class ScorecardSummaryDto(version: String, filter: ScorecardFilterEchoDto, all: ScorecardBodyDto, marksy: ScorecardBodyDto, external: ScorecardBodyDto)`
  - `data class TipDetailDto(ledger: LedgerTipDto?, progress: List<ProgressPointDto>)` (3b `TipView.ledger` and `TipView.progress`)
- Produces `InstrumentLifecycleDto.calls: InstrumentCallsDto? = null`. It is null for an older backend.
- JSON consumed, as 3b Tasks 4–6 serve it:
  - `/me/tips`: `{"data": [MyTipItem], "meta": {"pageSize", "nextCursor"}}`, where MyTipItem is the flat `LedgerTipView` plus `channelHeadline`, `callerHeadline`, `receivedVia` and `alsoReceivedBy`.
  - `/instruments/{symbol}`: `data.calls = {asOf, closedWithinDays, marksy: {engines}, external: {channels}}`.
  - `/tips/{id}`: `data.ledger` and `data.progress`.
  - The scorecard envelopes put their body under `data`.

- [ ] **Step 1: Confirm the two gates**

```bash
cd /c/AIAgent/marksy-os && git fetch origin
git cat-file -e origin/main:app/src/main/java/com/marksy/os/gateway/CaptureGate.kt && echo "4a Part B merged"
for route in me/tips scorecards/summary; do curl -s -o /dev/null -w "$route %{http_code}\n" "https://marksy.indoulia.com/api/v1/$route"; done
```

Expected: `4a Part B merged`, `me/tips 401` and `scorecards/summary 401`.
- An existing route answers an unauthenticated call with 401, and an unknown one with 404 (checked 2026-09-30: `/instruments/RELIANCE` gave 401, a made-up route 404).
- If `cat-file` fails, **stop and report**: 4a Part B is not merged.
- If either route prints 404, **stop and report**: 3b is not deployed.

- [ ] **Step 2: Create the worktree**

```bash
cd /c/AIAgent/marksy-os && git worktree add ../marksy-os-phase4b -b feat/tip-ledger-screens origin/main
cp /c/AIAgent/marksy-os/local.properties /c/AIAgent/marksy-os-phase4b/local.properties
cd /c/AIAgent/marksy-os-phase4b && git log --oneline -1 && grep -n "NOT_TRADING\|fun decide" app/src/main/java/com/marksy/os/gateway/CaptureGate.kt
```

Expected: HEAD is the 4a Part B merge, and `CaptureGate.kt` shows `NOT_TRADING` and `fun decide`, as 4a Task B3 wrote them. `local.properties` holds `sdk.dir`, is untracked, and git ignores it. All later commands run in `C:\AIAgent\marksy-os-phase4b`.

- [ ] **Step 3: Write the failing test**

Create `app/src/test/java/com/marksy/os/market/LedgerModelsTest.kt`:

```kotlin
package com.marksy.os.market

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// The shapes marksy-api Phase 3b serves (plan Tasks 4-6), field for field.
class LedgerModelsTest {
    private val progress = """{"sessionDate": "2026-09-22", "sessionIndex": 2, "entryStatus": "ENTERED", "statusAfter": "ACTIVE",
        "returnToDate": "0.020000", "bestReturn": "0.020000", "worstReturn": "0.000000", "toTargetPct": "5.000000",
        "toStopPct": "4.000000", "barBasis": "DAILY", "dataBasis": "PROVISIONAL"}"""
    private val headline = """{"total": 11, "open": 1, "completed": 10, "successful": 10, "failed": 0, "expired": 0,
        "invalidated": 0, "successPct": "100.00", "hitRatePct": "100.00", "avgActualReturn": "0.100000", "trustScore": 72}"""
    private val filter = """{"period": "LAST_7_DAYS", "startDate": "2026-09-23", "endDate": "2026-09-29", "basis": "first_seen",
        "horizon": null, "channelId": null, "callerId": null, "startAt": "2026-09-22T18:30:00Z", "endAt": "2026-09-29T18:30:00Z",
        "asOf": "2026-09-29T10:00:00Z"}"""
    private val body = """"counts": {"total": 17, "open": 2, "successful": 7, "failed": 3, "expired": 2, "completed": 12,
        "exited": 2, "invalidated": 1, "unscorable": 1, "dataUnresolved": 1},
        "performance": {"successPct": "70.00", "failurePct": "30.00", "hitRatePct": "58.33", "avgActualReturn": "0.026667",
        "totalActualReturn": "0.320000", "avgPromisedReturn": "0.087778", "totalPromisedReturn": "0.790000",
        "returnRealizationPct": "39.24", "avgDaysToCompletion": "9.67"},
        "trust": {"trustScore": 28, "completed": 12, "minimumCompleted": 10, "wilsonLowerBound": "0.319507",
        "returnQuality": "0.766667", "invalidated": 1}"""

    private fun ledgerTip(status: String = "TARGET_HIT", latest: String = "null") = """"tipId": "uuid-1", "symbol": "RENUKA",
        "direction": "BUY", "entryLow": "100.000000", "entryHigh": "100.000000", "entryBasis": "STATED", "target": "110.000000",
        "stopLoss": "95.000000", "horizonSessions": 5, "horizonBasis": "STATED", "firstSeenAt": "2026-09-21T04:00:00Z",
        "status": "$status", "entryStatus": "ENTERED", "outcome": "SUCCESS", "reason": null, "enteredSession": 1,
        "closedSession": 2, "closedAt": "2026-09-25T10:00:00Z", "exitPrice": null, "promisedReturn": "0.100000",
        "actualReturn": "0.100000", "predictionId": null, "channel": {"channelId": 3, "name": "Upstox", "type": "BROKER_APP"},
        "caller": {"callerId": 9, "name": "Rahul"}, "latestProgress": $latest"""

    @Test
    fun parsesMyTipsWithTheirReceiptsAndProvisionalProgress() {
        val page = MyTipPageDto.parse(JSONObject("""{"data": [{${ledgerTip("ACTIVE", progress)},
            "channelHeadline": $headline, "callerHeadline": null,
            "receivedVia": [{"receiptId": "r-1", "kind": "TIP", "medium": "SMS", "channelLabel": "UPSTOX",
              "appPackage": "com.upstox.pro", "devicePostedAt": null, "recordedAt": "2026-09-21T04:00:00Z"}],
            "alsoReceivedBy": 2}], "meta": {"pageSize": 50, "nextCursor": "c-2"}}"""))

        val item = page.items.single()
        assertEquals("c-2", page.nextCursor)
        assertEquals(listOf("RENUKA", "ACTIVE", "Upstox", "Rahul"), listOf(item.tip.symbol, item.tip.status, item.tip.channel?.name, item.tip.caller?.name))
        assertEquals(100.0, item.tip.entryLow!!, 1e-9)
        assertNull(item.tip.exitPrice)
        assertEquals(0.02, item.tip.latestProgress!!.returnToDate!!, 1e-9)
        assertEquals("PROVISIONAL", item.tip.latestProgress!!.dataBasis)
        assertEquals(72, item.channelHeadline!!.trustScore)
        assertNull(item.callerHeadline)
        assertEquals(listOf("SMS" to "UPSTOX"), item.receivedVia.map { it.medium to it.channelLabel })
        assertEquals(2, item.alsoReceivedBy)
    }

    @Test
    fun parsesAnInstrumentsCallsByEngineAndChannel() {
        val json = JSONObject("""{"symbol": "RENUKA", "exchange": "NSE", "market": {}, "predictions": [],
          "calls": {"asOf": "2026-09-30T10:00:00Z", "closedWithinDays": 90,
            "marksy": {"engines": [
              {"callerId": 1, "name": "Prediction engine", "channelId": 7, "scorecard": $headline, "tips": [{${ledgerTip("SOURCE_EXIT")}}]},
              {"callerId": 2, "name": "Rating engine", "channelId": 7, "scorecard": $headline, "tips": []}]},
            "external": {"channels": [
              {"channelId": 3, "name": "Upstox", "type": "BROKER_APP", "scorecard": $headline, "tips": [{${ledgerTip()}}]}]}}}""")

        val calls = InstrumentLifecycleDto.parse(json).calls!!

        assertEquals(listOf("Prediction engine", "Rating engine"), calls.engines.map { it.name })
        assertEquals("SOURCE_EXIT", calls.engines[0].tips.single().status)
        assertEquals(emptyList<LedgerTipDto>(), calls.engines[1].tips)
        assertEquals(listOf("Upstox" to "BROKER_APP"), calls.channels.map { it.name to it.type })
        assertEquals(90, calls.closedWithinDays)
        assertNull(InstrumentLifecycleDto.parse(JSONObject("""{"symbol": "NEWCO", "predictions": []}""")).calls)
    }

    @Test
    fun parsesScorecardsAndATipDetailWithDecimalStrings() {
        val card = ScorecardDto.parse(JSONObject("""{"version": "SCR-001", "scope": "channel", "scopeId": 3, "name": "Upstox",
            "channelId": 3, "channelName": "Upstox", "filter": $filter, $body}"""))
        val mine = ScorecardDto.parse(JSONObject("""{"version": "SCR-001", "scope": "customer", "scopeId": null, "name": null,
            "channelId": null, "channelName": null, "filter": $filter, $body}"""))
        val list = EntityScorecardListDto.parse(JSONObject("""{"version": "SCR-001", "entity": "caller", "filter": $filter,
            "items": [{"entity": "caller", "id": 9, "name": "Rahul", "channelId": 3, "channelName": "Upstox", $body}]}"""))
        val summary = ScorecardSummaryDto.parse(JSONObject("""{"version": "SCR-001", "filter": $filter,
            "all": {$body}, "marksy": {$body}, "external": {$body}}"""))
        val detail = TipDetailDto.parse(JSONObject("""{"tipId": "uuid-1", "ledger": {${ledgerTip()}}, "progress": [$progress]}"""))

        assertEquals("3", card.scopeId)
        assertNull(mine.scopeId)
        assertEquals("2026-09-23" to "2026-09-29", card.filter.startDate to card.filter.endDate)
        assertEquals(12, card.body.counts.completed)
        assertEquals(58.33, card.body.performance.hitRatePct!!, 1e-9)
        assertEquals(0.319507, card.body.trust.wilsonLowerBound!!, 1e-9)
        assertEquals(28, card.body.trust.trustScore)
        assertEquals(listOf(9 to "Upstox"), list.items.map { it.id to it.channelName })
        assertEquals(17, summary.marksy.counts.total)
        assertEquals(listOf("2026-09-22" to "PROVISIONAL"), detail.progress.map { it.sessionDate to it.dataBasis })
        assertEquals("TARGET_HIT", detail.ledger!!.status)
        assertNull(TipDetailDto.parse(JSONObject("""{"tipId": "legacy", "ledger": null, "progress": []}""")).ledger)
    }
}
```

- [ ] **Step 4: Run it to verify it fails**

Run the unit-test command with `--tests 'com.marksy.os.market.LedgerModelsTest'`.
Expected: FAIL at compile with `Unresolved reference 'MyTipPageDto'`.

- [ ] **Step 5: Write the DTOs**

Create `app/src/main/java/com/marksy/os/market/LedgerModels.kt`:

```kotlin
package com.marksy.os.market

import org.json.JSONArray
import org.json.JSONObject

// Tip-ledger reads from marksy-api Phase 3b: decimals arrive as strings, returns are fractions, percentages are 0-100.
private const val MAX_LEDGER_ITEMS = 300

// A 250-session tip has 250 progress rows, beyond the 50 the market lists keep.
private fun JSONArray?.ledgerObjects(): List<JSONObject> =
    if (this == null) emptyList() else (0 until minOf(length(), MAX_LEDGER_ITEMS)).mapNotNull { optJSONObject(it) }

private fun JSONObject.count(name: String): Int = intOrNull(name) ?: 0

data class ChannelRefDto(val channelId: Int, val name: String, val type: String) {
    companion object {
        fun parse(json: JSONObject) = ChannelRefDto(json.count("channelId"), json.textOrNull("name") ?: "", json.textOrNull("type") ?: "")
    }
}

data class CallerRefDto(val callerId: Int, val name: String) {
    companion object {
        fun parse(json: JSONObject) = CallerRefDto(json.count("callerId"), json.textOrNull("name") ?: "")
    }
}

data class ProgressPointDto(
    val sessionDate: String,
    val sessionIndex: Int,
    val entryStatus: String,
    val statusAfter: String,
    val returnToDate: Double?,
    val bestReturn: Double?,
    val worstReturn: Double?,
    val toTargetPct: Double?,
    val toStopPct: Double?,
    val barBasis: String,
    val dataBasis: String
) {
    companion object {
        fun parse(json: JSONObject) = ProgressPointDto(
            sessionDate = json.textOrNull("sessionDate") ?: "",
            sessionIndex = json.count("sessionIndex"),
            entryStatus = json.textOrNull("entryStatus") ?: "",
            statusAfter = json.textOrNull("statusAfter") ?: "",
            returnToDate = json.doubleOrNull("returnToDate"),
            bestReturn = json.doubleOrNull("bestReturn"),
            worstReturn = json.doubleOrNull("worstReturn"),
            toTargetPct = json.doubleOrNull("toTargetPct"),
            toStopPct = json.doubleOrNull("toStopPct"),
            barBasis = json.textOrNull("barBasis") ?: "",
            dataBasis = json.textOrNull("dataBasis") ?: ""
        )
    }
}

/** The canonical call (spec §4): terms, lifecycle and latest progress; `channel` and `caller` are canonical. */
data class LedgerTipDto(
    val tipId: String,
    val symbol: String,
    val direction: String?,
    val entryLow: Double?,
    val entryHigh: Double?,
    val entryBasis: String?,
    val target: Double?,
    val stopLoss: Double?,
    val horizonSessions: Int?,
    val horizonBasis: String?,
    val firstSeenAt: String,
    val status: String,
    val entryStatus: String?,
    val outcome: String?,
    val reason: String?,
    val enteredSession: Int?,
    val closedSession: Int?,
    val closedAt: String?,
    val exitPrice: Double?,
    val promisedReturn: Double?,
    val actualReturn: Double?,
    val predictionId: Int?,
    val channel: ChannelRefDto?,
    val caller: CallerRefDto?,
    val latestProgress: ProgressPointDto?
) {
    companion object {
        fun parse(json: JSONObject) = LedgerTipDto(
            tipId = json.textOrNull("tipId") ?: "",
            symbol = json.textOrNull("symbol") ?: "",
            direction = json.textOrNull("direction"),
            entryLow = json.doubleOrNull("entryLow"),
            entryHigh = json.doubleOrNull("entryHigh"),
            entryBasis = json.textOrNull("entryBasis"),
            target = json.doubleOrNull("target"),
            stopLoss = json.doubleOrNull("stopLoss"),
            horizonSessions = json.intOrNull("horizonSessions"),
            horizonBasis = json.textOrNull("horizonBasis"),
            firstSeenAt = json.textOrNull("firstSeenAt") ?: "",
            status = json.textOrNull("status") ?: "",
            entryStatus = json.textOrNull("entryStatus"),
            outcome = json.textOrNull("outcome"),
            reason = json.textOrNull("reason"),
            enteredSession = json.intOrNull("enteredSession"),
            closedSession = json.intOrNull("closedSession"),
            closedAt = json.textOrNull("closedAt"),
            exitPrice = json.doubleOrNull("exitPrice"),
            promisedReturn = json.doubleOrNull("promisedReturn"),
            actualReturn = json.doubleOrNull("actualReturn"),
            predictionId = json.intOrNull("predictionId"),
            channel = json.optJSONObject("channel")?.let(ChannelRefDto::parse),
            caller = json.optJSONObject("caller")?.let(CallerRefDto::parse),
            latestProgress = json.optJSONObject("latestProgress")?.let(ProgressPointDto::parse)
        )
    }
}

/** One of the signed-in customer's own receipts; nobody else's is ever served (invariant 10). */
data class ReceiptRefDto(
    val receiptId: String,
    val kind: String,
    val medium: String,
    val channelLabel: String?,
    val appPackage: String?,
    val devicePostedAt: String?,
    val recordedAt: String
) {
    companion object {
        fun parse(json: JSONObject) = ReceiptRefDto(
            receiptId = json.textOrNull("receiptId") ?: "",
            kind = json.textOrNull("kind") ?: "",
            medium = json.textOrNull("medium") ?: "",
            channelLabel = json.textOrNull("channelLabel"),
            appPackage = json.textOrNull("appPackage"),
            devicePostedAt = json.textOrNull("devicePostedAt"),
            recordedAt = json.textOrNull("recordedAt") ?: ""
        )
    }
}

/** §8.2's headline: Lifetime, every horizon, first-seen basis. */
data class ScorecardHeadlineDto(
    val total: Int,
    val open: Int,
    val completed: Int,
    val successful: Int,
    val failed: Int,
    val expired: Int,
    val invalidated: Int,
    val successPct: Double?,
    val hitRatePct: Double?,
    val avgActualReturn: Double?,
    val trustScore: Int?
) {
    companion object {
        fun parse(json: JSONObject) = ScorecardHeadlineDto(
            total = json.count("total"),
            open = json.count("open"),
            completed = json.count("completed"),
            successful = json.count("successful"),
            failed = json.count("failed"),
            expired = json.count("expired"),
            invalidated = json.count("invalidated"),
            successPct = json.doubleOrNull("successPct"),
            hitRatePct = json.doubleOrNull("hitRatePct"),
            avgActualReturn = json.doubleOrNull("avgActualReturn"),
            trustScore = json.intOrNull("trustScore")
        )
    }
}

data class MyTipDto(
    val tip: LedgerTipDto,
    val channelHeadline: ScorecardHeadlineDto?,
    val callerHeadline: ScorecardHeadlineDto?,
    val receivedVia: List<ReceiptRefDto>,
    val alsoReceivedBy: Int
) {
    companion object {
        fun parse(json: JSONObject) = MyTipDto(
            tip = LedgerTipDto.parse(json),
            channelHeadline = json.optJSONObject("channelHeadline")?.let(ScorecardHeadlineDto::parse),
            callerHeadline = json.optJSONObject("callerHeadline")?.let(ScorecardHeadlineDto::parse),
            receivedVia = json.optJSONArray("receivedVia").ledgerObjects().map(ReceiptRefDto::parse),
            alsoReceivedBy = json.count("alsoReceivedBy")
        )
    }
}

data class MyTipPageDto(val items: List<MyTipDto>, val nextCursor: String?) {
    companion object {
        fun parse(envelope: JSONObject) = MyTipPageDto(
            envelope.optJSONArray("data").ledgerObjects().map(MyTipDto::parse),
            envelope.optJSONObject("meta")?.textOrNull("nextCursor")
        )
    }
}

data class EngineCallsDto(val callerId: Int, val name: String, val channelId: Int, val scorecard: ScorecardHeadlineDto, val tips: List<LedgerTipDto>) {
    companion object {
        fun parse(json: JSONObject) = EngineCallsDto(
            json.count("callerId"), json.textOrNull("name") ?: "", json.count("channelId"),
            ScorecardHeadlineDto.parse(json.optJSONObject("scorecard") ?: JSONObject()),
            json.optJSONArray("tips").ledgerObjects().map(LedgerTipDto::parse)
        )
    }
}

data class ChannelCallsDto(val channelId: Int, val name: String, val type: String, val scorecard: ScorecardHeadlineDto, val tips: List<LedgerTipDto>) {
    companion object {
        fun parse(json: JSONObject) = ChannelCallsDto(
            json.count("channelId"), json.textOrNull("name") ?: "", json.textOrNull("type") ?: "",
            ScorecardHeadlineDto.parse(json.optJSONObject("scorecard") ?: JSONObject()),
            json.optJSONArray("tips").ledgerObjects().map(LedgerTipDto::parse)
        )
    }
}

/** `/instruments/{symbol}` `calls` (spec §9): every Marksy engine, and every external channel with a call here. */
data class InstrumentCallsDto(val asOf: String, val closedWithinDays: Int, val engines: List<EngineCallsDto>, val channels: List<ChannelCallsDto>) {
    companion object {
        fun parse(json: JSONObject) = InstrumentCallsDto(
            asOf = json.textOrNull("asOf") ?: "",
            closedWithinDays = json.count("closedWithinDays"),
            engines = json.optJSONObject("marksy")?.optJSONArray("engines").ledgerObjects().map(EngineCallsDto::parse),
            channels = json.optJSONObject("external")?.optJSONArray("channels").ledgerObjects().map(ChannelCallsDto::parse)
        )
    }
}

/** The filter the server applied (§8.3 "Every response echoes the applied filter and the resolved start/end"). */
data class ScorecardFilterEchoDto(
    val period: String,
    val startDate: String?,
    val endDate: String?,
    val basis: String,
    val horizon: String?,
    val channelId: Int?,
    val callerId: Int?,
    val startAt: String?,
    val endAt: String?,
    val asOf: String
) {
    companion object {
        fun parse(json: JSONObject) = ScorecardFilterEchoDto(
            period = json.textOrNull("period") ?: "LIFETIME",
            startDate = json.textOrNull("startDate"),
            endDate = json.textOrNull("endDate"),
            basis = json.textOrNull("basis") ?: "first_seen",
            horizon = json.textOrNull("horizon"),
            channelId = json.intOrNull("channelId"),
            callerId = json.intOrNull("callerId"),
            startAt = json.textOrNull("startAt"),
            endAt = json.textOrNull("endAt"),
            asOf = json.textOrNull("asOf") ?: ""
        )
    }
}

data class ScorecardCountsDto(
    val total: Int,
    val open: Int,
    val successful: Int,
    val failed: Int,
    val expired: Int,
    val completed: Int,
    val exited: Int,
    val invalidated: Int,
    val unscorable: Int,
    val dataUnresolved: Int
) {
    companion object {
        fun parse(json: JSONObject) = ScorecardCountsDto(
            json.count("total"), json.count("open"), json.count("successful"), json.count("failed"), json.count("expired"),
            json.count("completed"), json.count("exited"), json.count("invalidated"), json.count("unscorable"), json.count("dataUnresolved")
        )
    }
}

data class ScorecardPerformanceDto(
    val successPct: Double?,
    val failurePct: Double?,
    val hitRatePct: Double?,
    val avgActualReturn: Double?,
    val totalActualReturn: Double?,
    val avgPromisedReturn: Double?,
    val totalPromisedReturn: Double?,
    val returnRealizationPct: Double?,
    val avgDaysToCompletion: Double?
) {
    companion object {
        fun parse(json: JSONObject) = ScorecardPerformanceDto(
            json.doubleOrNull("successPct"), json.doubleOrNull("failurePct"), json.doubleOrNull("hitRatePct"),
            json.doubleOrNull("avgActualReturn"), json.doubleOrNull("totalActualReturn"), json.doubleOrNull("avgPromisedReturn"),
            json.doubleOrNull("totalPromisedReturn"), json.doubleOrNull("returnRealizationPct"), json.doubleOrNull("avgDaysToCompletion")
        )
    }
}

data class ScorecardTrustDto(
    val trustScore: Int?,
    val completed: Int,
    val minimumCompleted: Int,
    val wilsonLowerBound: Double?,
    val returnQuality: Double?,
    val invalidated: Int
) {
    companion object {
        fun parse(json: JSONObject) = ScorecardTrustDto(
            json.intOrNull("trustScore"), json.count("completed"), json.count("minimumCompleted"),
            json.doubleOrNull("wilsonLowerBound"), json.doubleOrNull("returnQuality"), json.count("invalidated")
        )
    }
}

data class ScorecardBodyDto(val counts: ScorecardCountsDto, val performance: ScorecardPerformanceDto, val trust: ScorecardTrustDto) {
    companion object {
        fun parse(json: JSONObject) = ScorecardBodyDto(
            ScorecardCountsDto.parse(json.optJSONObject("counts") ?: JSONObject()),
            ScorecardPerformanceDto.parse(json.optJSONObject("performance") ?: JSONObject()),
            ScorecardTrustDto.parse(json.optJSONObject("trust") ?: JSONObject())
        )
    }
}

data class ScorecardDto(
    val version: String,
    val scope: String,
    val scopeId: String?,
    val name: String?,
    val channelId: Int?,
    val channelName: String?,
    val filter: ScorecardFilterEchoDto,
    val body: ScorecardBodyDto
) {
    companion object {
        fun parse(json: JSONObject) = ScorecardDto(
            version = json.textOrNull("version") ?: "",
            scope = json.textOrNull("scope") ?: "",
            // An int for channel/caller/engine scopes, null on the customer's own card.
            scopeId = if (json.isNull("scopeId")) null else json.opt("scopeId")?.toString(),
            name = json.textOrNull("name"),
            channelId = json.intOrNull("channelId"),
            channelName = json.textOrNull("channelName"),
            filter = ScorecardFilterEchoDto.parse(json.optJSONObject("filter") ?: JSONObject()),
            body = ScorecardBodyDto.parse(json)
        )
    }
}

data class EntityScorecardDto(val entity: String, val id: Int, val name: String, val channelId: Int?, val channelName: String?, val body: ScorecardBodyDto) {
    companion object {
        fun parse(json: JSONObject) = EntityScorecardDto(
            json.textOrNull("entity") ?: "", json.count("id"), json.textOrNull("name") ?: "",
            json.intOrNull("channelId"), json.textOrNull("channelName"), ScorecardBodyDto.parse(json)
        )
    }
}

data class EntityScorecardListDto(val version: String, val entity: String, val filter: ScorecardFilterEchoDto, val items: List<EntityScorecardDto>) {
    companion object {
        fun parse(json: JSONObject) = EntityScorecardListDto(
            json.textOrNull("version") ?: "", json.textOrNull("entity") ?: "",
            ScorecardFilterEchoDto.parse(json.optJSONObject("filter") ?: JSONObject()),
            json.optJSONArray("items").ledgerObjects().map(EntityScorecardDto::parse)
        )
    }
}

data class ScorecardSummaryDto(val version: String, val filter: ScorecardFilterEchoDto, val all: ScorecardBodyDto, val marksy: ScorecardBodyDto, val external: ScorecardBodyDto) {
    companion object {
        fun parse(json: JSONObject) = ScorecardSummaryDto(
            json.textOrNull("version") ?: "",
            ScorecardFilterEchoDto.parse(json.optJSONObject("filter") ?: JSONObject()),
            ScorecardBodyDto.parse(json.optJSONObject("all") ?: JSONObject()),
            ScorecardBodyDto.parse(json.optJSONObject("marksy") ?: JSONObject()),
            ScorecardBodyDto.parse(json.optJSONObject("external") ?: JSONObject())
        )
    }
}

/** `GET /tips/{id}`'s ledger part: null `ledger` and no progress for a legacy row. */
data class TipDetailDto(val ledger: LedgerTipDto?, val progress: List<ProgressPointDto>) {
    companion object {
        fun parse(json: JSONObject) = TipDetailDto(
            json.optJSONObject("ledger")?.let(LedgerTipDto::parse),
            json.optJSONArray("progress").ledgerObjects().map(ProgressPointDto::parse)
        )
    }
}
```

In `market/InstrumentModels.kt`, `data class InstrumentLifecycleDto`:
- Change `val predictions: List<InstrumentPredictionEntryDto>` to `val predictions: List<InstrumentPredictionEntryDto>,`, then add on the next line:

```kotlin
    val calls: InstrumentCallsDto? = null
```

- In its `parse`, change `predictions = json.optJSONArray("predictions").objects().map(InstrumentPredictionEntryDto::parse)` to:

```kotlin
            predictions = json.optJSONArray("predictions").objects().map(InstrumentPredictionEntryDto::parse),
            calls = json.optJSONObject("calls")?.let(InstrumentCallsDto::parse)
```

- [ ] **Step 6: Run it to verify it passes**

Run the unit-test command with `--tests 'com.marksy.os.market.LedgerModelsTest' --tests 'com.marksy.os.market.InstrumentModelsTest'`.
Expected: all PASS. That is 3 in `LedgerModelsTest`, and `InstrumentModelsTest` unchanged.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/marksy/os/market/LedgerModels.kt app/src/main/java/com/marksy/os/market/InstrumentModels.kt \
  app/src/test/java/com/marksy/os/market/LedgerModelsTest.kt
git commit -m "Tip ledger: parse My tips, instrument calls, scorecards and tip progress as Phase 3b serves them

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task B2: §8.3 query vocabulary, the client calls and the repository

**Files:**
- Create: `app/src/main/java/com/marksy/os/market/ScorecardQuery.kt`
- Modify: `app/src/main/java/com/marksy/os/market/MarketApiClient.kt` (the interface, `RealMarketApiClient` and its companion)
- Modify: `app/src/main/java/com/marksy/os/market/MarketIntelligenceRepository.kt`
- Test: `app/src/test/java/com/marksy/os/market/ScorecardQueryTest.kt`

**Interfaces:**
- Produces, in `market/ScorecardQuery.kt`:
  - `enum class ScorecardPeriod(val label: String)`, whose names equal 3b's period values: `LIFETIME, TODAY, YESTERDAY, THIS_WEEK, LAST_WEEK, THIS_MONTH, LAST_MONTH, LAST_7_DAYS, LAST_7_TRADING_DAYS, LAST_30_DAYS, LAST_90_DAYS, CUSTOM`
  - `enum class HorizonBucket(val label: String)`, whose names equal 3b's values: `INTRADAY, UP_TO_1_WEEK, UP_TO_1_MONTH, LONGER_THAN_1_MONTH`
  - `enum class ScorecardEntity(val label: String, val param: String) { CHANNEL, CALLER }`, with `companion fun fromParam(param: String): ScorecardEntity`
  - `data class ScorecardQuery(entity = CHANNEL, period = LIFETIME, startDate: LocalDate? = null, endDate: LocalDate? = null, horizon: HorizonBucket? = null, channelId: Int? = null, callerId: Int? = null)`, with:
    - `fun filterParams(): String?`
    - `fun label(): String`
    - `fun encode(): String`
    - `companion fun decode(text: String): ScorecardQuery`
  - `object ScorecardText`, with `date(LocalDate)`, `trust(ScorecardTrustDto)`, `range(ScorecardFilterEchoDto)`, `summary(ScorecardBodyDto)` and `details(ScorecardBodyDto): List<Pair<String, String>>`
- Produces on `MarketApiClient`. Each has a default body, so existing fakes compile:
  - `suspend fun myTips(status: String?, cursor: String?): MyTipPageDto`
  - `suspend fun myScorecard(filter: String): ScorecardDto`
  - `suspend fun scorecards(entity: String, filter: String): EntityScorecardListDto`
  - `suspend fun scorecardSummary(filter: String): ScorecardSummaryDto`
  - `suspend fun scorecard(entity: String, id: Int, filter: String): ScorecardDto`
  - `suspend fun tipDetail(tipId: String): TipDetailDto`
- Produces on `MarketIntelligenceRepository`:
  - `suspend fun myTips(status: String?, cursor: String? = null): MarketDataState<MyTipPageDto>`, which is Empty for an empty page
  - `suspend fun myScorecard(query: ScorecardQuery): MarketDataState<ScorecardDto>`
  - `suspend fun scorecardSummary(query: ScorecardQuery): MarketDataState<ScorecardSummaryDto>`
  - `suspend fun scorecards(entity: ScorecardEntity, query: ScorecardQuery): MarketDataState<EntityScorecardListDto>`, which is Empty with no items
  - `suspend fun scorecard(entity: ScorecardEntity, id: Int, query: ScorecardQuery): MarketDataState<ScorecardDto>`
  - `suspend fun tipDetail(tipId: String): MarketDataState<TipDetailDto>`
- HTTP, all under `MARKET_API_BASE_URL` with the existing bearer session:
  - `GET /me/tips?pageSize=50[&status=][&cursor=]`
  - `GET /me/scorecard[?filter]`
  - `GET /scorecards?entity=channel|caller[&filter]`
  - `GET /scorecards/summary[?filter]`
  - `GET /scorecards/{entity}/{id}[?filter]`
  - `GET /tips/{tipId}`
  - Here `filter` is `period`, `startDate`, `endDate`, `horizon`, `channelId` and `callerId`, as 3b's `scorecard_filter` names them.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/marksy/os/market/ScorecardQueryTest.kt`:

```kotlin
package com.marksy.os.market

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class ScorecardQueryTest {
    private val sep1 = LocalDate.of(2026, 9, 1)
    private val sep30 = LocalDate.of(2026, 9, 30)

    // marksy-api 422s a custom range missing a date, running backwards, or mixed with a preset (§8.3).
    @Test
    fun aCustomRangeNeedsBothDatesAndReplacesThePreset() {
        val custom = ScorecardQuery(period = ScorecardPeriod.CUSTOM, startDate = sep1)

        assertNull(custom.filterParams())
        assertNull(custom.copy(endDate = sep1.minusDays(1)).filterParams())
        assertEquals("period=CUSTOM&startDate=2026-09-01&endDate=2026-09-30", custom.copy(endDate = sep30).filterParams())
        assertEquals("1 Sep – 30 Sep", custom.copy(endDate = sep30).label())

        val preset = custom.copy(period = ScorecardPeriod.LAST_7_TRADING_DAYS, endDate = sep30, horizon = HorizonBucket.UP_TO_1_WEEK, callerId = 9)
        assertEquals("period=LAST_7_TRADING_DAYS&horizon=UP_TO_1_WEEK&callerId=9", preset.filterParams())
        assertEquals("Last 7 trading days · Up to 1 week", preset.label())
        assertEquals("", ScorecardQuery().filterParams())
        assertEquals(preset, ScorecardQuery.decode(preset.encode()))
        assertEquals(ScorecardQuery(), ScorecardQuery.decode("garbage"))
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run the unit-test command with `--tests 'com.marksy.os.market.ScorecardQueryTest'`.
Expected: FAIL at compile with `Unresolved reference 'ScorecardQuery'`.

- [ ] **Step 3: Write the query vocabulary**

Create `app/src/main/java/com/marksy/os/market/ScorecardQuery.kt`:

```kotlin
package com.marksy.os.market

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** §8.3 presets; the server resolves each to IST dates, so the phone never computes a window. */
enum class ScorecardPeriod(val label: String) {
    LIFETIME("Lifetime"), TODAY("Today"), YESTERDAY("Yesterday"), THIS_WEEK("This week"), LAST_WEEK("Last week"),
    THIS_MONTH("This month"), LAST_MONTH("Last month"), LAST_7_DAYS("Last 7 days"), LAST_7_TRADING_DAYS("Last 7 trading days"),
    LAST_30_DAYS("Last 30 days"), LAST_90_DAYS("Last 90 days"), CUSTOM("Custom")
}

enum class HorizonBucket(val label: String) {
    INTRADAY("Intraday"), UP_TO_1_WEEK("Up to 1 week"), UP_TO_1_MONTH("Up to 1 month"), LONGER_THAN_1_MONTH("Longer than 1 month")
}

enum class ScorecardEntity(val label: String, val param: String) {
    CHANNEL("Channels", "channel"), CALLER("Callers", "caller");

    companion object {
        fun fromParam(param: String): ScorecardEntity = entries.firstOrNull { it.param == param } ?: CHANNEL
    }
}

/** What the Scorecards tab shows: which list, and the §8.3 filter every card on it shares. */
data class ScorecardQuery(
    val entity: ScorecardEntity = ScorecardEntity.CHANNEL,
    val period: ScorecardPeriod = ScorecardPeriod.LIFETIME,
    val startDate: LocalDate? = null,
    val endDate: LocalDate? = null,
    val horizon: HorizonBucket? = null,
    val channelId: Int? = null,
    val callerId: Int? = null
) {
    /** The §8.3 query parameters; null while a custom range lacks a date or ends before it starts. */
    fun filterParams(): String? {
        val range = if (period == ScorecardPeriod.CUSTOM) {
            val start = startDate ?: return null
            val end = endDate?.takeUnless { it.isBefore(start) } ?: return null
            listOf("period=CUSTOM", "startDate=$start", "endDate=$end")
        } else {
            listOfNotNull(period.takeUnless { it == ScorecardPeriod.LIFETIME }?.let { "period=${it.name}" })
        }
        val narrowing = listOfNotNull(horizon?.let { "horizon=${it.name}" }, channelId?.let { "channelId=$it" }, callerId?.let { "callerId=$it" })
        return (range + narrowing).joinToString("&")
    }

    fun label(): String {
        val start = startDate
        val end = endDate
        val range = if (period == ScorecardPeriod.CUSTOM && start != null && end != null) "${DAY.format(start)} – ${DAY.format(end)}" else period.label
        return range + (horizon?.let { " · ${it.label}" } ?: "")
    }

    fun encode(): String = listOf(
        entity.name, period.name, startDate?.toString().orEmpty(), endDate?.toString().orEmpty(),
        horizon?.name.orEmpty(), channelId?.toString().orEmpty(), callerId?.toString().orEmpty()
    ).joinToString("|")

    companion object {
        private val DAY = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)

        fun decode(text: String): ScorecardQuery = runCatching {
            val p = text.split("|")
            ScorecardQuery(
                ScorecardEntity.valueOf(p[0]), ScorecardPeriod.valueOf(p[1]),
                p[2].ifEmpty { null }?.let(LocalDate::parse), p[3].ifEmpty { null }?.let(LocalDate::parse),
                p[4].ifEmpty { null }?.let(HorizonBucket::valueOf), p[5].toIntOrNull(), p[6].toIntOrNull()
            )
        }.getOrDefault(ScorecardQuery())
    }
}

/** Words for §8 numbers exactly as the server sent them; nothing here recomputes a metric. */
object ScorecardText {
    private val DAY = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)
    private val FULL_DAY = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)

    fun date(d: LocalDate): String = DAY.format(d)

    fun trust(t: ScorecardTrustDto): String = t.trustScore?.let { "Trust $it" } ?: "Trust —"

    /** The window the server resolved, in IST dates as echoed; "All time" for Lifetime. */
    fun range(f: ScorecardFilterEchoDto): String {
        fun day(iso: String?) = iso?.let { runCatching { FULL_DAY.format(LocalDate.parse(it)) }.getOrDefault(it) }
        val start = day(f.startDate)
        val end = day(f.endDate)
        return if (start == null || end == null) "All time" else "$start – $end (IST)"
    }

    fun summary(b: ScorecardBodyDto): String = listOfNotNull(
        "${b.counts.completed} completed of ${b.counts.total}",
        b.performance.successPct?.let { "success ${pct(it)}" },
        b.performance.hitRatePct?.let { "hit ${pct(it)}" },
        LedgerCalls.returnText(b.performance.avgActualReturn)?.let { "avg $it" },
        b.counts.open.takeIf { it > 0 }?.let { "$it open" },
        b.trust.invalidated.takeIf { it > 0 }?.let { "$it invalidated" }
    ).joinToString(" · ")

    fun details(b: ScorecardBodyDto): List<Pair<String, String>> = listOf(
        "Total tips" to "${b.counts.total}", "Open" to "${b.counts.open}", "Successful" to "${b.counts.successful}",
        "Failed" to "${b.counts.failed}", "Expired" to "${b.counts.expired}", "Completed" to "${b.counts.completed}",
        "Exited by source" to "${b.counts.exited}", "Invalidated" to "${b.counts.invalidated}",
        "Unscorable" to "${b.counts.unscorable}", "Data unresolved" to "${b.counts.dataUnresolved}",
        "Success" to opt(b.performance.successPct, ::pct), "Failure" to opt(b.performance.failurePct, ::pct),
        "Hit rate" to opt(b.performance.hitRatePct, ::pct),
        "Avg actual return" to opt(b.performance.avgActualReturn, ::ret), "Total actual return" to opt(b.performance.totalActualReturn, ::ret),
        "Avg promised return" to opt(b.performance.avgPromisedReturn, ::ret), "Total promised return" to opt(b.performance.totalPromisedReturn, ::ret),
        "Return realization" to opt(b.performance.returnRealizationPct, ::pct),
        "Avg days to completion" to opt(b.performance.avgDaysToCompletion) { String.format(Locale.US, "%.2f", it) },
        "Trust score" to (b.trust.trustScore?.toString() ?: "not enough history (${b.trust.completed}/${b.trust.minimumCompleted})"),
        "Wilson lower bound" to opt(b.trust.wilsonLowerBound) { String.format(Locale.US, "%.3f", it) },
        "Return quality" to opt(b.trust.returnQuality) { String.format(Locale.US, "%.3f", it) }
    )

    private fun pct(v: Double) = String.format(Locale.US, "%.2f%%", v)
    private fun ret(v: Double) = LedgerCalls.returnText(v) ?: "—"
    private fun opt(v: Double?, format: (Double) -> String) = v?.let(format) ?: "—"
}
```

`LedgerCalls.returnText` comes in Task B3. So, to compile this task alone, create `app/src/main/java/com/marksy/os/market/LedgerCalls.kt` now with just:

```kotlin
package com.marksy.os.market

import java.util.Locale

/** How a ledger tip reads on the phone. Every state, outcome and return is the server's (spec §6.6, §7); this only
 * words it, so a withdrawn losing Marksy call reads as a failed exit, never as a neutral invalidation. */
object LedgerCalls {
    fun returnText(fraction: Double?): String? = fraction?.let { String.format(Locale.US, "%+.2f%%", it * 100) }
}
```

Task B3 replaces this file.

- [ ] **Step 4: Add the client calls**

In `market/MarketApiClient.kt`, in `interface MarketApiClient`, directly after the `setIpoTracking` declaration, add:

```kotlin
    /** Tip ledger (spec §9) under the bearer session; `filter` is a §8.3 query string from `ScorecardQuery.filterParams`. */
    suspend fun myTips(status: String?, cursor: String?): MyTipPageDto = MyTipPageDto(emptyList(), null)
    suspend fun myScorecard(filter: String): ScorecardDto = throw MarketApiException("Scorecards are not supported")
    suspend fun scorecards(entity: String, filter: String): EntityScorecardListDto = throw MarketApiException("Scorecards are not supported")
    suspend fun scorecardSummary(filter: String): ScorecardSummaryDto = throw MarketApiException("Scorecards are not supported")
    suspend fun scorecard(entity: String, id: Int, filter: String): ScorecardDto = throw MarketApiException("Scorecards are not supported")
    suspend fun tipDetail(tipId: String): TipDetailDto = throw MarketApiException("Tip detail is not supported")
```

In `class RealMarketApiClient`, directly after `override suspend fun setIpoTracking(...)`, add:

```kotlin
    override suspend fun myTips(status: String?, cursor: String?): MyTipPageDto {
        val params = listOfNotNull("pageSize=$MY_TIPS_PAGE_SIZE", status?.let { "status=${encode(it)}" }, cursor?.let { "cursor=${encode(it)}" })
        return MyTipPageDto.parse(getEnvelope("$base/me/tips?" + params.joinToString("&")))
    }

    override suspend fun myScorecard(filter: String): ScorecardDto = ScorecardDto.parse(getData("$base/me/scorecard" + query(filter)))

    override suspend fun scorecards(entity: String, filter: String): EntityScorecardListDto =
        EntityScorecardListDto.parse(getData("$base/scorecards?entity=${encode(entity)}" + (if (filter.isEmpty()) "" else "&$filter")))

    override suspend fun scorecardSummary(filter: String): ScorecardSummaryDto =
        ScorecardSummaryDto.parse(getData("$base/scorecards/summary" + query(filter)))

    override suspend fun scorecard(entity: String, id: Int, filter: String): ScorecardDto =
        ScorecardDto.parse(getData("$base/scorecards/${encode(entity)}/$id" + query(filter)))

    override suspend fun tipDetail(tipId: String): TipDetailDto = TipDetailDto.parse(getData("$base/tips/${encode(tipId)}"))

    private fun query(filter: String) = if (filter.isEmpty()) "" else "?$filter"
```

In its `private companion object`, directly after `const val IPO_MAX_RESPONSE_CHARS = 16_000_000`, add:

```kotlin
        const val MY_TIPS_PAGE_SIZE = 50
```

- [ ] **Step 5: Add the repository reads**

In `market/MarketIntelligenceRepository.kt`, directly after `suspend fun ipoHistory(...)`, add:

```kotlin
    suspend fun myTips(status: String?, cursor: String? = null): MarketDataState<MyTipPageDto> =
        fetch(emptyCheck = { it.items.isEmpty() }) { it.myTips(status, cursor) }

    suspend fun myScorecard(query: ScorecardQuery): MarketDataState<ScorecardDto> =
        query.filterParams()?.let { f -> fetch { it.myScorecard(f) } } ?: incompleteRange

    suspend fun scorecardSummary(query: ScorecardQuery): MarketDataState<ScorecardSummaryDto> =
        query.filterParams()?.let { f -> fetch { it.scorecardSummary(f) } } ?: incompleteRange

    suspend fun scorecards(entity: ScorecardEntity, query: ScorecardQuery): MarketDataState<EntityScorecardListDto> =
        query.filterParams()?.let { f -> fetch(emptyCheck = { it.items.isEmpty() }) { it.scorecards(entity.param, f) } } ?: incompleteRange

    suspend fun scorecard(entity: ScorecardEntity, id: Int, query: ScorecardQuery): MarketDataState<ScorecardDto> =
        query.filterParams()?.let { f -> fetch { it.scorecard(entity.param, id, f) } } ?: incompleteRange

    suspend fun tipDetail(tipId: String): MarketDataState<TipDetailDto> = fetch { it.tipDetail(tipId) }

    private val incompleteRange = MarketDataState.Error("Pick a start and an end date")
```

- [ ] **Step 6: Run the tests to verify they pass**

Run the unit-test command with `--tests 'com.marksy.os.market.*'`.
Expected: all PASS. That includes `ScorecardQueryTest` (1) and `LedgerModelsTest` (3), and `MarketIntelligenceRepositoryTest` and `RealMarketApiClientTest` unchanged. Their fakes compile because the new members have defaults.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/marksy/os/market/ScorecardQuery.kt app/src/main/java/com/marksy/os/market/LedgerCalls.kt \
  app/src/main/java/com/marksy/os/market/MarketApiClient.kt app/src/main/java/com/marksy/os/market/MarketIntelligenceRepository.kt \
  app/src/test/java/com/marksy/os/market/ScorecardQueryTest.kt
git commit -m "Tip ledger: read My tips, scorecards and tip detail with the section 8.3 filters

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task B3: How a ledger tip reads (`LedgerCalls`)

**Files:**
- Modify: `app/src/main/java/com/marksy/os/market/LedgerCalls.kt` (replace the Task B2 stub)
- Test: `app/src/test/java/com/marksy/os/market/LedgerCallsTest.kt`

**Interfaces:**
- Produces `object LedgerCalls`:
  - `const val STATUS_ACTIVE = "ACTIVE"`; `enum class Tone { POSITIVE, NEGATIVE, NEUTRAL, MUTED }`
  - `fun isActive(t: LedgerTipDto): Boolean`
  - `fun state(t: LedgerTipDto): String`, for example "Active", "Waiting for entry", "Target hit", "Exited · Failed", "Horizon reached · Succeeded" or "Invalidated · never entered"
  - `fun tone(t: LedgerTipDto): Tone` and `fun progressTone(t: LedgerTipDto): Tone`
  - `fun returnText(fraction: Double?): String?`
  - `fun progressText(t: LedgerTipDto): String?` and `fun progressLine(p: ProgressPointDto): String`
  - `fun entryMid(t: LedgerTipDto): Double?`
  - `fun levelsText(t: LedgerTipDto): String`, `fun headline(t: LedgerTipDto, withCaller: Boolean): String`, `fun pastLine(t: LedgerTipDto, withCaller: Boolean): String`, `fun termsText(t: LedgerTipDto): String` and `fun returnsText(t: LedgerTipDto): String?`
  - `fun horizonText(sessions: Int?): String?`, `fun recordText(h: ScorecardHeadlineDto): String`, `fun channelType(type: String): String?` and `fun receivedVia(item: MyTipDto): String`
  - `fun leadingMarksyCall(calls: InstrumentCallsDto?): LedgerTipDto?`
  - `fun analysisRecommendationId(calls: InstrumentCallsDto?, predictions: List<InstrumentPredictionEntryDto>): Int?`
  - `fun chartLevels(t: LedgerTipDto?): List<Pair<String, Double>>`
  - `fun day(iso: String): String`, in IST, as "d MMM"

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/marksy/os/market/LedgerCallsTest.kt`:

```kotlin
package com.marksy.os.market

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LedgerCallsTest {
    // Spec §7: Marksy withdrawing a losing call is a source exit that failed, never a neutral invalidation.
    @Test
    fun aWithdrawnLosingMarksyCallReadsAsAFailedExit() {
        val withdrawn = tip(status = "SOURCE_EXIT", outcome = "FAILURE", actual = -0.03)
        val neverEntered = tip(status = "INVALIDATED", reason = "NEVER_ENTERED")

        assertEquals("Exited · Failed", LedgerCalls.state(withdrawn))
        assertEquals(LedgerCalls.Tone.NEGATIVE, LedgerCalls.tone(withdrawn))
        assertEquals("-3.00%", LedgerCalls.progressText(withdrawn))
        assertEquals("Invalidated · never entered", LedgerCalls.state(neverEntered))
        assertEquals(LedgerCalls.Tone.MUTED, LedgerCalls.tone(neverEntered))
    }

    @Test
    fun aProvisionalReturnIsLabelledAndAFinalOneIsNot() {
        assertEquals("+2.00% so far · provisional", LedgerCalls.progressText(tip(progress = point(0.02, "PROVISIONAL"))))
        assertEquals("+2.00% so far", LedgerCalls.progressText(tip(progress = point(0.02, "FINAL"))))
        assertEquals("22 Sep · S2 · +2.00% · best +2.00% · worst +0.00% · provisional", LedgerCalls.progressLine(point(0.02, "PROVISIONAL")))
        assertNull(LedgerCalls.progressText(tip(entryStatus = "WAITING", progress = point(null, "FINAL"))))
    }

    @Test
    fun theStockPageLeadsWithTheOpenMarksyCallAndItsAnalysis() {
        val closed = tip(status = "TARGET_HIT", outcome = "SUCCESS", predictionId = 7, seen = "2026-09-01T04:00:00Z")
        val open = tip(predictionId = 8, seen = "2026-09-20T04:00:00Z")
        val rating = tip(target = null, stop = null, seen = "2026-09-25T04:00:00Z")
        val calls = InstrumentCallsDto("2026-09-30T10:00:00Z", 90, listOf(engine("Prediction engine", closed, open), engine("Rating engine", rating)), emptyList())
        val predictions = listOf(7 to 70, 8 to 80).map { (p, r) -> InstrumentPredictionEntryDto.parse(JSONObject("""{"predictionId": $p, "recommendationId": $r}""")) }

        assertEquals(open, LedgerCalls.leadingMarksyCall(calls))
        assertEquals(80, LedgerCalls.analysisRecommendationId(calls, predictions))
        assertEquals(70, LedgerCalls.analysisRecommendationId(calls.copy(engines = listOf(engine("Prediction engine", closed))), predictions))
        assertEquals(listOf("Target" to 110.0, "Entry" to 100.0, "Stop" to 95.0), LedgerCalls.chartLevels(open))
    }

    @Test
    fun recordsAndReceiptsReadAsTheServerSentThem() {
        val received = MyTipDto(tip(), null, null, listOf(receipt("SMS"), receipt("APP_NOTIFICATION"), receipt("SMS")), alsoReceivedBy = 2)

        assertEquals("Not enough history · 3 calls · 50% hit · 1 invalidated", LedgerCalls.recordText(headline(null, 3, 50.0, 1)))
        assertEquals("Trust 72 · 11 calls · 91% hit", LedgerCalls.recordText(headline(72, 11, 90.91, 0)))
        assertEquals("Received via SMS, app · 2 others got it", LedgerCalls.receivedVia(received))
        assertEquals("Received via WhatsApp", LedgerCalls.receivedVia(received.copy(receivedVia = listOf(receipt("WHATSAPP")), alsoReceivedBy = 0)))
    }

    private fun point(ret: Double?, basis: String) = ProgressPointDto("2026-09-22", 2, "ENTERED", "ACTIVE", ret, ret, ret?.let { 0.0 }, 5.0, 4.0, "DAILY", basis)

    private fun headline(trust: Int?, total: Int, hit: Double?, invalidated: Int) =
        ScorecardHeadlineDto(total, 0, total, 0, 0, 0, invalidated, null, hit, null, trust)

    private fun engine(name: String, vararg tips: LedgerTipDto) = EngineCallsDto(1, name, 7, headline(null, tips.size, null, 0), tips.toList())

    private fun receipt(medium: String) = ReceiptRefDto("r-$medium", "TIP", medium, "UPSTOX", null, null, "2026-09-21T04:00:00Z")

    private fun tip(
        status: String = "ACTIVE",
        outcome: String? = null,
        reason: String? = null,
        actual: Double? = null,
        entryStatus: String = "ENTERED",
        progress: ProgressPointDto? = null,
        predictionId: Int? = null,
        seen: String = "2026-09-21T04:00:00Z",
        target: Double? = 110.0,
        stop: Double? = 95.0
    ) = LedgerTipDto(
        tipId = "t-$seen-$predictionId", symbol = "RENUKA", direction = "BUY", entryLow = 100.0, entryHigh = 100.0, entryBasis = "STATED",
        target = target, stopLoss = stop, horizonSessions = 5, horizonBasis = "STATED", firstSeenAt = seen, status = status,
        entryStatus = entryStatus, outcome = outcome, reason = reason, enteredSession = 1, closedSession = null, closedAt = null,
        exitPrice = null, promisedReturn = 0.1, actualReturn = actual, predictionId = predictionId, channel = null, caller = null,
        latestProgress = progress
    )
}
```

- [ ] **Step 2: Run it to verify it fails**

Run the unit-test command with `--tests 'com.marksy.os.market.LedgerCallsTest'`.
Expected: FAIL at compile with `Unresolved reference 'state'`.

- [ ] **Step 3: Write `LedgerCalls`**

Replace `app/src/main/java/com/marksy/os/market/LedgerCalls.kt` with:

```kotlin
package com.marksy.os.market

import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** How a ledger tip reads on the phone. Every state, outcome and return is the server's (spec §6.6, §7); this only
 * words it, so a withdrawn losing Marksy call reads as a failed exit, never as a neutral invalidation. */
object LedgerCalls {
    const val STATUS_ACTIVE = "ACTIVE"
    private const val DATA_BASIS_PROVISIONAL = "PROVISIONAL"

    enum class Tone { POSITIVE, NEGATIVE, NEUTRAL, MUTED }

    private val IST: ZoneId = ZoneId.of("Asia/Kolkata")
    private val DAY = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)
    private val TIME = DateTimeFormatter.ofPattern("d MMM, HH:mm", Locale.ENGLISH)
    private val STATUS = mapOf(
        "TARGET_HIT" to "Target hit", "STOP_LOSS_HIT" to "Stop-loss hit", "SOURCE_EXIT" to "Exited",
        "HORIZON_EXPIRED" to "Expired", "DIRECTION_HORIZON" to "Horizon reached", "INVALIDATED" to "Invalidated",
        "UNSCORABLE" to "Unscorable", "DATA_UNRESOLVED" to "No market data"
    )
    private val OUTCOME = mapOf("SUCCESS" to "Succeeded", "FAILURE" to "Failed")
    private val CHANNEL_TYPE = mapOf(
        "BROKER_APP" to "broker app", "NEWS_PORTAL" to "news", "SMS_SENDER" to "SMS", "WHATSAPP_GROUP" to "WhatsApp group",
        "TELEGRAM_CHANNEL" to "Telegram", "YOUTUBE" to "YouTube"
    )
    private val MEDIUM = mapOf(
        "APP_NOTIFICATION" to "app", "SMS" to "SMS", "WHATSAPP" to "WhatsApp", "TELEGRAM" to "Telegram", "EMAIL" to "email", "MANUAL" to "paste"
    )

    fun isActive(t: LedgerTipDto): Boolean = t.status == STATUS_ACTIVE

    fun state(t: LedgerTipDto): String = when (t.status) {
        STATUS_ACTIVE -> if (t.entryStatus == "WAITING") "Waiting for entry" else "Active"
        "SOURCE_EXIT", "DIRECTION_HORIZON" -> listOfNotNull(STATUS[t.status], t.outcome?.let(OUTCOME::get)).joinToString(" · ")
        "INVALIDATED", "UNSCORABLE", "DATA_UNRESOLVED" -> listOfNotNull(STATUS[t.status], t.reason?.let(::words)).joinToString(" · ")
        else -> STATUS[t.status] ?: words(t.status)
    }

    fun tone(t: LedgerTipDto): Tone = when (t.outcome) {
        "SUCCESS" -> Tone.POSITIVE
        "FAILURE" -> Tone.NEGATIVE
        "EXPIRED" -> Tone.NEUTRAL
        else -> if (isActive(t)) Tone.NEUTRAL else Tone.MUTED
    }

    fun returnText(fraction: Double?): String? = fraction?.let { String.format(Locale.US, "%+.2f%%", it * 100) }

    /** The latest return while open, flagged when provisional; the actual return once closed. */
    fun progressText(t: LedgerTipDto): String? {
        if (!isActive(t)) return returnText(t.actualReturn)
        val point = t.latestProgress ?: return null
        val text = returnText(point.returnToDate) ?: return null
        return "$text so far" + if (point.dataBasis == DATA_BASIS_PROVISIONAL) " · provisional" else ""
    }

    fun progressTone(t: LedgerTipDto): Tone {
        val value = (if (isActive(t)) t.latestProgress?.returnToDate else t.actualReturn) ?: return Tone.MUTED
        return when {
            value > 0 -> Tone.POSITIVE
            value < 0 -> Tone.NEGATIVE
            else -> Tone.NEUTRAL
        }
    }

    fun progressLine(p: ProgressPointDto): String = listOfNotNull(
        runCatching { DAY.format(LocalDate.parse(p.sessionDate)) }.getOrDefault(p.sessionDate),
        "S${p.sessionIndex}",
        returnText(p.returnToDate),
        returnText(p.bestReturn)?.let { "best $it" },
        returnText(p.worstReturn)?.let { "worst $it" },
        "provisional".takeIf { p.dataBasis == DATA_BASIS_PROVISIONAL }
    ).joinToString(" · ")

    fun entryMid(t: LedgerTipDto): Double? = if (t.entryLow != null && t.entryHigh != null) (t.entryLow + t.entryHigh) / 2 else t.entryLow ?: t.entryHigh

    fun levelsText(t: LedgerTipDto): String {
        val low = t.entryLow
        val high = t.entryHigh
        val entry = when {
            low == null && high == null -> "first price"
            low != null && high != null && low != high -> "${rupees(low)}–${rupees(high).removePrefix("₹")}"
            else -> rupees(low ?: high!!)
        }
        return listOfNotNull("Entry $entry", t.target?.let { "Target ${rupees(it)}" }, t.stopLoss?.let { "Stop ${rupees(it)}" }).joinToString(" · ")
    }

    fun headline(t: LedgerTipDto, withCaller: Boolean): String = listOfNotNull(
        t.direction ?: "No direction", t.caller?.name?.takeIf { withCaller }, state(t), horizonText(t.horizonSessions), "seen ${day(t.firstSeenAt)}"
    ).joinToString(" · ")

    fun pastLine(t: LedgerTipDto, withCaller: Boolean): String = listOfNotNull(
        day(t.firstSeenAt), t.direction, t.caller?.name?.takeIf { withCaller }, state(t), returnText(t.actualReturn)
    ).joinToString(" · ")

    fun termsText(t: LedgerTipDto): String = listOfNotNull(
        horizonText(t.horizonSessions)?.let { h -> "Horizon $h" + (t.horizonBasis?.let { " (${words(it)})" } ?: "") },
        "first seen ${time(t.firstSeenAt)}",
        t.closedAt?.let { c -> "closed ${time(c)}" + (t.closedSession?.let { " (session $it)" } ?: "") }
    ).joinToString(" · ")

    fun returnsText(t: LedgerTipDto): String? = listOfNotNull(
        returnText(t.promisedReturn)?.let { "Promised $it" }, returnText(t.actualReturn)?.let { "actual $it" }
    ).joinToString(" · ").ifEmpty { null }

    fun horizonText(sessions: Int?): String? = when (sessions) {
        null -> null
        0 -> "intraday"
        1 -> "1 session"
        else -> "$sessions sessions"
    }

    /** §8.2's headline, or "not enough history" below 10 completed calls, always with the invalidated count. */
    fun recordText(h: ScorecardHeadlineDto): String = listOfNotNull(
        h.trustScore?.let { "Trust $it" } ?: "Not enough history",
        "${h.total} call${if (h.total == 1) "" else "s"}",
        h.hitRatePct?.let { String.format(Locale.US, "%.0f%% hit", it) },
        h.invalidated.takeIf { it > 0 }?.let { "$it invalidated" }
    ).joinToString(" · ")

    fun channelType(type: String): String? = CHANNEL_TYPE[type]

    /** Where the customer's own copies came from, and how many others got it; never who (invariant 10). */
    fun receivedVia(item: MyTipDto): String {
        val via = item.receivedVia.map { MEDIUM[it.medium] ?: words(it.medium) }.distinct().joinToString(", ")
        val others = item.alsoReceivedBy.takeIf { it > 0 }?.let { " · $it other${if (it == 1) "" else "s"} got it" } ?: ""
        return "Received via $via$others"
    }

    /** The newest open Marksy call with levels: it draws the chart lines and fills the trade ticket. */
    fun leadingMarksyCall(calls: InstrumentCallsDto?): LedgerTipDto? = calls?.engines.orEmpty().flatMap { it.tips }
        .filter { isActive(it) && (it.target != null || it.stopLoss != null) }
        .maxByOrNull { it.firstSeenAt }

    /** The recommendation behind the Prediction engine's open call, else its newest; `predictions[]` only maps the id. */
    fun analysisRecommendationId(calls: InstrumentCallsDto?, predictions: List<InstrumentPredictionEntryDto>): Int? {
        val made = calls?.engines.orEmpty().flatMap { it.tips }.filter { it.predictionId != null }
        val pick = made.filter(::isActive).maxByOrNull { it.firstSeenAt } ?: made.maxByOrNull { it.firstSeenAt } ?: return null
        return predictions.firstOrNull { it.predictionId == pick.predictionId }?.recommendationId
    }

    fun chartLevels(t: LedgerTipDto?): List<Pair<String, Double>> = if (t == null) emptyList() else listOfNotNull(
        t.target?.let { "Target" to it }, entryMid(t)?.let { "Entry" to it }, t.stopLoss?.let { "Stop" to it }
    )

    fun day(iso: String): String = runCatching { OffsetDateTime.parse(iso).atZoneSameInstant(IST).format(DAY) }.getOrDefault(iso.take(10))

    private fun time(iso: String): String =
        runCatching { OffsetDateTime.parse(iso).atZoneSameInstant(IST).format(TIME) }.getOrDefault(iso.take(16).replace('T', ' '))

    private fun rupees(v: Double) = "₹" + String.format(Locale.US, "%,.2f", v)

    private fun words(s: String) = s.lowercase(Locale.ROOT).replace('_', ' ')
}
```

- [ ] **Step 4: Run it to verify it passes**

Run the unit-test command with `--tests 'com.marksy.os.market.*'`.
Expected: all PASS, 4 of them in `LedgerCallsTest`.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/marksy/os/market/LedgerCalls.kt app/src/test/java/com/marksy/os/market/LedgerCallsTest.kt
git commit -m "Tip ledger: word a tip from its own state, outcome and returns, provisional progress flagged

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task B4: Remove the on-device rating

**Files:**
- Delete: `app/src/main/java/com/marksy/os/rating/RatingEngine.kt`, `rating/RatingCalibration.kt` and `rating/RatingSource.kt`
- Delete: `app/src/main/java/com/marksy/os/ui/RatingCalibrator.kt`, `ui/StockRatingInputs.kt` and `ui/MarksyRatingCard.kt`
- Modify: `app/src/main/java/com/marksy/os/ui/StockDetailScreen.kt` (lines 81, 84–86 and 98)
- Modify: `app/src/main/java/com/marksy/os/ui/MarketHealthCards.kt` (`RatingCalibrationCard`, lines 106–132)
- Modify: `app/src/main/java/com/marksy/os/ui/HealthScreen.kt` (line 66)
- Modify: `app/src/main/java/com/marksy/os/MainActivity.kt` (line 133)
- Delete tests: `app/src/test/java/com/marksy/os/rating/RatingEngineTest.kt` and `rating/RatingCalibrationTest.kt`

**Interfaces:**
- Removes `RatingEngine`, `RatingCalibration`, `RatingSource`, `LocalRatingSource`, `ActiveRating`, `RatingInputs`, `RatingResult`, `RatingConfig`, `RatingCalibrator`, `StockRatingInputs`, `MarksyRatingCard` and `RatingCalibrationCard`.
- `StockDetailScreen` loses its `ratingSource` parameter; its other parameters are unchanged.

- [ ] **Step 1: Write the failing check**

```bash
grep -rnE "com\.marksy\.os\.rating|RatingCalibrator|StockRatingInputs|MarksyRatingCard|RatingCalibrationCard|LocalRatingSource" app/src || echo "no on-device rating left"
```

Expected now: matches in `MainActivity.kt`, `StockDetailScreen.kt`, `MarketHealthCards.kt`, `HealthScreen.kt`, `RatingCalibrator.kt`, `StockRatingInputs.kt`, `MarksyRatingCard.kt` and the two rating tests.

- [ ] **Step 2: Delete the engine, the calibrator and the card**

```bash
git rm app/src/main/java/com/marksy/os/rating/RatingEngine.kt app/src/main/java/com/marksy/os/rating/RatingCalibration.kt \
  app/src/main/java/com/marksy/os/rating/RatingSource.kt app/src/main/java/com/marksy/os/ui/RatingCalibrator.kt \
  app/src/main/java/com/marksy/os/ui/StockRatingInputs.kt app/src/main/java/com/marksy/os/ui/MarksyRatingCard.kt \
  app/src/test/java/com/marksy/os/rating/RatingEngineTest.kt app/src/test/java/com/marksy/os/rating/RatingCalibrationTest.kt
```

- [ ] **Step 3: Unhook them**

In `ui/StockDetailScreen.kt`:
- In the parameter list, replace

```kotlin
    analysis: org.json.JSONObject? = null,
    ratingSource: com.marksy.os.rating.RatingSource = com.marksy.os.rating.LocalRatingSource
) {
```

  with

```kotlin
    analysis: org.json.JSONObject? = null
) {
```

- Delete these three lines:

```kotlin
    val ist = java.time.ZoneId.of("Asia/Kolkata")
    val ratingInputs = remember(live, fundamentals, instrument) { StockRatingInputs.from(live, fundamentals, instrument?.predictions, java.time.LocalDate.now(ist), ist) }
    val rating by androidx.compose.runtime.produceState<com.marksy.os.rating.RatingResult?>(null, ratingInputs) { value = ratingSource.rating(symbol.orEmpty(), ratingInputs) }
```

- Delete the line `        rating?.let { r -> item { MarksyRatingCard(r) } }`.

In `ui/MarketHealthCards.kt`, delete the KDoc line `/** Tune the Marksy rating on this device against how past calls actually did; adopted only if it wins on held-out calls. */` and the whole `internal fun RatingCalibrationCard(repo: MarketIntelligenceRepository) { … }` below it, through its closing brace (the file's last function).

In `ui/HealthScreen.kt`, delete the line `            item(key = "calibration") { RatingCalibrationCard(m) }`.

In `MainActivity.kt`, delete the line `        com.marksy.os.ui.RatingCalibrator.load(applicationContext)`.

- [ ] **Step 4: Run the check and the affected tests**

Run the Step 1 grep. Expected: it prints `no on-device rating left`.

Then run the unit-test command with `--tests 'com.marksy.os.ui.StockDetailScreenTest'`.
Expected: all PASS, with its 4 existing tests unchanged; they never passed a rating source.

- [ ] **Step 5: Commit**

The Step 2 `git rm` already staged the deletions. A pathspec for the removed `rating/` folders would fail, so add only the edited files:

```bash
git add app/src/main/java/com/marksy/os/ui/StockDetailScreen.kt app/src/main/java/com/marksy/os/ui/MarketHealthCards.kt \
  app/src/main/java/com/marksy/os/ui/HealthScreen.kt app/src/main/java/com/marksy/os/MainActivity.kt
git status
git commit -m "Tip ledger: drop the on-device rating engine and card; the Rating engine's calls come from the ledger

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

Check that `git status` shows only this task's files.

---

### Task B5: The stock page's calls box and the tip detail dialog

**Files:**
- Create: `app/src/main/java/com/marksy/os/ui/CallsBox.kt`, via `git mv ui/MarksyCallCard.kt ui/CallsBox.kt` then a full rewrite.
- Create: `app/src/main/java/com/marksy/os/ui/TipDetailDialog.kt`
- Modify: `app/src/main/java/com/marksy/os/ui/StockDetailScreen.kt` (imports, the parameter list, and lines 96–97 and 99 after Task B4)
- Modify: `app/src/main/java/com/marksy/os/ui/MarketScreen.kt` (the ticket state at lines 57–60, and the STOCKS branch at lines 99–120)
- Delete: `app/src/main/java/com/marksy/os/market/MarksyCalls.kt`
- Test: modify `app/src/test/java/com/marksy/os/ui/StockDetailScreenTest.kt`; delete `app/src/test/java/com/marksy/os/market/MarksyCallsTest.kt`

**Interfaces:**
- Produces, in `ui/CallsBox.kt`:
  - `internal fun toneColor(tone: LedgerCalls.Tone): Color`
  - `@Composable internal fun CallsBox(calls: InstrumentCallsDto, livePrice: Double?, analysis: JSONObject?, onOpenTip: (String) -> Unit)`
  - `@Composable internal fun ProgressBar(stop: Double, entry: Double, target: Double, price: Double?)` and `@Composable internal fun Collapsible(...)`, unchanged except for visibility
- Produces `@Composable internal fun TipDetailDialog(repository: MarketIntelligenceRepository, tipId: String, onOpenStock: ((String) -> Unit)?, onDismiss: () -> Unit)`.
- Produces `StockDetailScreen(..., analysis: org.json.JSONObject? = null, onOpenTip: (String) -> Unit = {})`.
- Removes `MarksyCallView`, `MarksyCalls`, `TrackRecord`, `MarksyCallCard`, and `MarksyCallCard.kt`'s private `Levels`, `HistorySection`, `pct`, `signed`, `humanize` and `day`.

- [ ] **Step 1: Write the failing test**

Replace the body of `class StockDetailScreenTest` in `app/src/test/java/com/marksy/os/ui/StockDetailScreenTest.kt`, keeping its annotations and the `compose` rule. Also add `import org.json.JSONObject`.

```kotlin
    @get:Rule val compose = createComposeRule()

    private val record = ScorecardHeadlineDto(1, 0, 1, 0, 1, 0, 0, 0.0, 0.0, -0.03, null)
    private val noRecord = ScorecardHeadlineDto(0, 0, 0, 0, 0, 0, 0, null, null, null, null)

    private fun withdrawn() = LedgerTipDto(
        tipId = "t-1", symbol = "RELIANCE", direction = "BUY", entryLow = 1420.0, entryHigh = 1420.0, entryBasis = "STATED",
        target = 1470.0, stopLoss = 1390.0, horizonSessions = 5, horizonBasis = "ENGINE", firstSeenAt = "2026-09-20T09:15:00Z",
        status = "SOURCE_EXIT", entryStatus = "ENTERED", outcome = "FAILURE", reason = null, enteredSession = 1, closedSession = 2,
        closedAt = "2026-09-23T10:00:00Z", exitPrice = 1377.4, promisedReturn = 0.035211, actualReturn = -0.03, predictionId = 501,
        channel = ChannelRefDto(7, "Marksy", "MARKSY"), caller = CallerRefDto(1, "Prediction engine"), latestProgress = null
    )

    private fun calls(vararg tips: LedgerTipDto) = InstrumentCallsDto(
        "2026-09-30T10:00:00Z", 90,
        listOf(EngineCallsDto(1, "Prediction engine", 7, record, tips.toList()), EngineCallsDto(2, "Rating engine", 7, noRecord, emptyList())),
        emptyList()
    )

    private fun instrument(calls: InstrumentCallsDto?) = InstrumentLifecycleDto(
        symbol = "RELIANCE", companyName = "Reliance Industries", exchange = "NSE", sector = "Energy", isActive = true,
        market = InstrumentMarketDto(lastClosePrice = 1452.3, asOfSessionDate = "2026-09-23T18:30:00Z", freshnessState = "FRESH"),
        predictionCount = 1, openPredictionCount = 0,
        // The prediction's monitor event still says INVALIDATED; the page must believe the tip.
        predictions = listOf(InstrumentPredictionEntryDto.parse(JSONObject(
            """{"predictionId": 501, "lifecycleState": "INVALIDATED", "lifecycleDetail": "Invalidated by decay", "isTerminal": true, "outcomeStatus": "OPEN"}"""
        ))),
        calls = calls
    )

    @Test
    fun aWithdrawnLosingMarksyCallShowsAsAFailedExitNotAnInvalidation() {
        compose.setContent { StockDetailScreen(state = MarketDataState.Loaded(instrument(calls(withdrawn()))), padding = PaddingValues()) }

        compose.onNodeWithText("Past calls (1)").performClick()
        compose.onNodeWithText("Exited · Failed", substring = true).assertExists()
        compose.onNodeWithText("-3.00%", substring = true).assertExists()
        compose.onNodeWithText("Invalidated", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Rating engine").assertExists()
    }

    @Test
    fun aStockWithoutAnyCallShowsNoCallsBox() {
        compose.setContent { StockDetailScreen(state = MarketDataState.Loaded(instrument(calls())), padding = PaddingValues()) }

        compose.onNodeWithText("Reliance Industries").assertExists()
        compose.onNodeWithText("MARKSY").assertDoesNotExist()
    }

    @Test
    fun unconfiguredMarksyIsHidden() {
        compose.setContent { StockDetailScreen(state = MarketDataState.Unavailable, padding = PaddingValues()) }

        compose.onNodeWithText("not configured", substring = true).assertDoesNotExist()
        compose.onNodeWithText("MARKSY").assertDoesNotExist()
    }
```

The old `openCallLeadsThePageUnderThePrice`, `onlyPastCallsSayThereIsNoActiveCall` and `noMarksyDataShowsNoMarksySection` tested `MarksyCallCard`, which this task deletes.

- [ ] **Step 2: Run it to verify it fails**

Run the unit-test command with `--tests 'com.marksy.os.ui.StockDetailScreenTest'`.
Expected: `aWithdrawnLosingMarksyCallShowsAsAFailedExitNotAnInvalidation` FAILS: no node "Past calls (1)" (today's page renders `MarksyCallCard` from `predictions[]`, and prints "Invalidated").

- [ ] **Step 3: Write the calls box**

```bash
git mv app/src/main/java/com/marksy/os/ui/MarksyCallCard.kt app/src/main/java/com/marksy/os/ui/CallsBox.kt
```

Replace `app/src/main/java/com/marksy/os/ui/CallsBox.kt` with the code below. `ProgressBar`, `Collapsible`, `AnalysisSection`, `Gauge` and `FactPill` are the old file's code; only `ProgressBar` and `Collapsible` become `internal`.

```kotlin
package com.marksy.os.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.marksy.os.market.InstrumentCallsDto
import com.marksy.os.market.LedgerCalls
import com.marksy.os.market.LedgerTipDto
import com.marksy.os.market.MarksyAnalysis
import com.marksy.os.market.ScorecardHeadlineDto
import org.json.JSONObject

internal fun toneColor(tone: LedgerCalls.Tone): Color = when (tone) {
    LedgerCalls.Tone.POSITIVE -> MarksyTheme.PrimaryEmerald
    LedgerCalls.Tone.NEGATIVE -> MarksyTheme.RedUrgent
    LedgerCalls.Tone.NEUTRAL -> MarksyTheme.TextSecondary
    LedgerCalls.Tone.MUTED -> MarksyTheme.TextMuted
}

/** Every call on this stock by who made it (spec §10): a group per Marksy engine, then per external channel, each
 * with its lifetime record. States and returns are the ledger tip's, never a monitor event's. */
@Composable
internal fun CallsBox(calls: InstrumentCallsDto, livePrice: Double?, analysis: JSONObject?, onOpenTip: (String) -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    val leading = remember(calls) { LedgerCalls.leadingMarksyCall(calls) != null }
    Column(
        Modifier.fillMaxWidth().clip(shape).background(MarksyTheme.Surface)
            .border(1.dp, if (leading) MarksyTheme.PrimaryEmerald else MarksyTheme.BorderGlow, shape).padding(12.dp)
    ) {
        Text("MARKSY", color = MarksyTheme.PrimaryEmerald, fontSize = 11.sp, fontWeight = FontWeight.Black)
        calls.engines.forEach { e -> CallGroup(e.name, null, e.scorecard, e.tips, withCaller = false, livePrice, onOpenTip) }
        AnalysisSection(analysis)
        if (calls.channels.isNotEmpty()) {
            Text("EXTERNAL", color = MarksyTheme.TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.Black, modifier = Modifier.padding(top = 10.dp))
        }
        calls.channels.forEach { c -> CallGroup(c.name, LedgerCalls.channelType(c.type), c.scorecard, c.tips, withCaller = true, livePrice, onOpenTip) }
    }
}

@Composable
private fun CallGroup(
    name: String, kind: String?, record: ScorecardHeadlineDto, tips: List<LedgerTipDto>, withCaller: Boolean,
    livePrice: Double?, onOpenTip: (String) -> Unit
) {
    val (open, past) = remember(tips) { tips.partition(LedgerCalls::isActive) }
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                name + (kind?.let { " · $it" } ?: ""), color = MarksyTheme.TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
            )
            Text(LedgerCalls.recordText(record), color = MarksyTheme.TextSecondary, fontSize = 10.sp, maxLines = 1)
        }
        if (tips.isEmpty()) Text("No call on this stock", color = MarksyTheme.TextMuted, fontSize = 11.sp)
        open.forEach { t -> OpenCall(t, withCaller, livePrice) { onOpenTip(t.tipId) } }
        if (past.isNotEmpty()) Collapsible("Past calls (${past.size})", summary = null) {
            past.forEach { t ->
                Text(
                    LedgerCalls.pastLine(t, withCaller), color = toneColor(LedgerCalls.tone(t)), fontSize = 11.sp, maxLines = 2,
                    modifier = Modifier.fillMaxWidth().clickable { onOpenTip(t.tipId) }.padding(vertical = 3.dp)
                )
            }
        }
    }
}

@Composable
private fun OpenCall(t: LedgerTipDto, withCaller: Boolean, livePrice: Double?, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(top = 6.dp)) {
        Text(LedgerCalls.headline(t, withCaller), color = MarksyTheme.TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        Text(LedgerCalls.levelsText(t), color = MarksyTheme.TextSecondary, fontSize = 11.sp)
        val entry = LedgerCalls.entryMid(t)
        if (t.target != null && t.stopLoss != null && entry != null) ProgressBar(t.stopLoss, entry, t.target, livePrice)
        LedgerCalls.progressText(t)?.let { Text(it, color = toneColor(LedgerCalls.progressTone(t)), fontSize = 11.sp, fontWeight = FontWeight.SemiBold) }
    }
}

/** Stop on the left, target on the right, the entry tick and today's price between them. */
@Composable
internal fun ProgressBar(stop: Double, entry: Double, target: Double, price: Double?) {
    if (target == stop) return
    fun at(v: Double) = ((v - stop) / (target - stop)).coerceIn(0.0, 1.0).toFloat()
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Canvas(Modifier.fillMaxWidth().height(16.dp)) {
            val mid = size.height / 2
            drawLine(MarksyTheme.RedUrgent.copy(alpha = .5f), Offset(0f, mid), Offset(size.width * at(entry), mid), 4.dp.toPx(), StrokeCap.Round)
            drawLine(MarksyTheme.PrimaryEmerald.copy(alpha = .5f), Offset(size.width * at(entry), mid), Offset(size.width, mid), 4.dp.toPx(), StrokeCap.Round)
            drawLine(MarksyTheme.TextSecondary, Offset(size.width * at(entry), 0f), Offset(size.width * at(entry), size.height), 1.dp.toPx())
            price?.let { drawCircle(Color.White, 5.dp.toPx(), Offset(size.width * at(it), mid)) }
        }
        Row(Modifier.fillMaxWidth()) {
            Text("Stop", color = MarksyTheme.TextMuted, fontSize = 10.sp, modifier = Modifier.weight(1f))
            price?.let { Text("Now ₹${money(it)}", color = MarksyTheme.TextPrimary, fontSize = 10.sp, fontWeight = FontWeight.SemiBold) }
            Text("Target", color = MarksyTheme.TextMuted, fontSize = 10.sp, modifier = Modifier.weight(1f), textAlign = TextAlign.End)
        }
    }
}

@Composable
internal fun Collapsible(title: String, summary: String?, preview: (@Composable ColumnScope.() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    var open by rememberSaveable(title) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().clickable { open = !open }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, color = MarksyTheme.TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                summary?.let { Text(it, color = MarksyTheme.TextMuted, fontSize = 11.sp) }
            }
            Icon(if (open) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, contentDescription = if (open) "Collapse" else "Expand", tint = MarksyTheme.TextSecondary, modifier = Modifier.size(20.dp))
        }
        preview?.let { Column(content = it) }
        if (open) Column(Modifier.padding(bottom = 8.dp), content = content)
    }
}

/** Gauges stay visible; facts, signals and reasons open on tap. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AnalysisSection(analysis: JSONObject?, title: String = "Marksy analysis") {
    val a = remember(analysis) { analysis?.let(MarksyAnalysis::from) } ?: return
    if (a.gauges.isEmpty() && a.facts.isEmpty() && a.signals.isEmpty()) return
    Collapsible(title, summary = null, preview = { a.gauges.forEach { Gauge(it) } }) {
        if (a.facts.isNotEmpty()) FlowRow(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            a.facts.forEach { (label, value) -> FactPill(label, value) }
        }
        a.signals.forEach { (label, text) ->
            Column(Modifier.padding(top = 8.dp)) {
                Text(label, color = MarksyTheme.TextMuted, fontSize = 10.sp)
                Text(text, color = MarksyTheme.TextPrimary, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        if (a.reasons.isNotEmpty()) Text(a.reasons.joinToString(" · "), color = MarksyTheme.YellowImportant, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
        a.basedOn?.let { Text("Based on $it", color = MarksyTheme.TextMuted, fontSize = 10.sp, modifier = Modifier.padding(top = 4.dp)) }
    }
}

/** A 0–100% reading drawn like the day range: label, bar, value. */
@Composable
private fun Gauge(g: MarksyAnalysis.Gauge) {
    val tint = when {
        g.fraction >= .66f -> MarksyTheme.PrimaryEmerald
        g.fraction >= .4f -> MarksyTheme.YellowImportant
        else -> MarksyTheme.RedUrgent
    }
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(g.label, color = MarksyTheme.TextSecondary, fontSize = 11.sp, modifier = Modifier.width(78.dp))
        Canvas(Modifier.weight(1f).height(10.dp)) {
            val mid = size.height / 2
            drawLine(Color(0x33FFFFFF), Offset(0f, mid), Offset(size.width, mid), 4.dp.toPx(), StrokeCap.Round)
            drawLine(tint, Offset(0f, mid), Offset(size.width * g.fraction.coerceIn(.02f, 1f), mid), 4.dp.toPx(), StrokeCap.Round)
        }
        Text(g.value, color = MarksyTheme.TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.End, modifier = Modifier.width(40.dp))
    }
    g.note?.let { Text(it, color = MarksyTheme.TextMuted, fontSize = 10.sp, modifier = Modifier.padding(start = 78.dp)) }
}

@Composable
private fun FactPill(label: String, value: String) {
    Row(Modifier.clip(RoundedCornerShape(8.dp)).background(MarksyTheme.SurfaceRaised).padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("$label ", color = MarksyTheme.TextMuted, fontSize = 10.sp)
        Text(value, color = if (value.startsWith("+")) MarksyTheme.PrimaryEmerald else if (value.startsWith("-")) MarksyTheme.RedUrgent else MarksyTheme.TextPrimary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
    }
}
```

- [ ] **Step 4: Write the tip detail dialog**

Create `app/src/main/java/com/marksy/os/ui/TipDetailDialog.kt`:

```kotlin
package com.marksy.os.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.marksy.os.market.LedgerCalls
import com.marksy.os.market.LedgerTipDto
import com.marksy.os.market.MarketDataState
import com.marksy.os.market.MarketIntelligenceRepository
import com.marksy.os.market.ProgressPointDto
import com.marksy.os.market.TipDetailDto

/** One tip as the ledger tracks it: terms, state, returns and every session's progress (spec §9 `GET /tips/{id}`). */
@Composable
internal fun TipDetailDialog(repository: MarketIntelligenceRepository, tipId: String, onOpenStock: ((String) -> Unit)?, onDismiss: () -> Unit) {
    val detail by produceState<MarketDataState<TipDetailDto>>(MarketDataState.Loading, tipId) { value = repository.tipDetail(tipId) }
    val tip = (detail as? MarketDataState.Loaded)?.value?.ledger
    MarksyDialog(
        onDismissRequest = onDismiss,
        title = { Text(tip?.let { "${it.symbol} · ${it.direction ?: "No direction"}" } ?: "Tip", color = MarksyTheme.TextPrimary) },
        text = {
            when (val d = detail) {
                is MarketDataState.Loaded -> d.value.ledger?.let { TipDetailBody(it, d.value.progress) }
                    ?: Text("This tip is not in the ledger yet.", color = MarksyTheme.TextSecondary)
                is MarketDataState.Error -> Text(d.message, color = MarksyTheme.TextSecondary)
                MarketDataState.Unavailable -> Text("Sign in to your Marksy account in More.", color = MarksyTheme.TextSecondary)
                else -> MarksyLoader("Loading tip...")
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done", color = MarksyTheme.PrimaryEmerald, fontWeight = FontWeight.SemiBold) } },
        dismissButton = if (tip != null && onOpenStock != null) {
            { TextButton(onClick = { onOpenStock(tip.symbol) }) { Text("Stock page", color = MarksyTheme.TextSecondary) } }
        } else null
    )
}

@Composable
private fun TipDetailBody(t: LedgerTipDto, progress: List<ProgressPointDto>) {
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(LedgerCalls.state(t), color = toneColor(LedgerCalls.tone(t)), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Text(listOfNotNull(t.channel?.name, t.caller?.name).joinToString(" · "), color = MarksyTheme.TextSecondary, fontSize = 12.sp)
        Text(LedgerCalls.levelsText(t), color = MarksyTheme.TextPrimary, fontSize = 12.sp)
        Text(LedgerCalls.termsText(t), color = MarksyTheme.TextMuted, fontSize = 11.sp)
        LedgerCalls.returnsText(t)?.let { Text(it, color = toneColor(LedgerCalls.progressTone(t)), fontSize = 12.sp, fontWeight = FontWeight.SemiBold) }
        if (progress.isNotEmpty()) Text("Session by session", color = MarksyTheme.TextMuted, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
        progress.forEach { p ->
            Text(LedgerCalls.progressLine(p), color = if (p.dataBasis == "PROVISIONAL") MarksyTheme.YellowImportant else MarksyTheme.TextSecondary, fontSize = 11.sp)
        }
    }
}
```

- [ ] **Step 5: Put the calls box on the stock page**

In `ui/StockDetailScreen.kt`:
- Replace the imports `import com.marksy.os.market.MarksyCallView` and `import com.marksy.os.market.MarksyCalls` with `import com.marksy.os.market.LedgerCalls`.
- In the parameter list, replace `    analysis: org.json.JSONObject? = null` with:

```kotlin
    analysis: org.json.JSONObject? = null,
    onOpenTip: (String) -> Unit = {}
```

- Replace

```kotlin
        val calls = instrument?.let { MarksyCalls.view(it.predictions) } ?: MarksyCallView.None
        if (calls != MarksyCallView.None) item { MarksyCallCard(calls, live.quote?.lastPrice, analysis) }
```

  with

```kotlin
        // Channels are listed only with a call here, but every engine is, so an engine-only box needs a tip.
        val calls = instrument?.calls?.takeIf { c -> c.engines.any { it.tips.isNotEmpty() } || c.channels.isNotEmpty() }
        calls?.let { c -> item(key = "calls") { CallsBox(c, live.quote?.lastPrice, analysis, onOpenTip) } }
```

- Replace the line `        val levels = (calls as? MarksyCallView.Active)?.primary?.let { p -> listOfNotNull(p.targetPrice?.let { "Target" to it }, "Entry" to p.entryPrice, p.stopLoss?.let { "Stop" to it }) }.orEmpty()` with:

```kotlin
        val levels = LedgerCalls.chartLevels(LedgerCalls.leadingMarksyCall(instrument?.calls))
```

In `ui/MarketScreen.kt`:
- Directly after `    ticket?.let { TradeTicketSheet(it) { ticket = null } }`, add:

```kotlin
    var openTip by remember { mutableStateOf<String?>(null) }
    openTip?.let { TipDetailDialog(repository, it, onOpenStock = null) { openTip = null } }
```

- Replace

```kotlin
                    val predictions = when (val st = state) { is com.marksy.os.market.MarketDataState.Loaded -> st.value.predictions; is com.marksy.os.market.MarketDataState.Stale -> st.value.predictions; else -> null }
                    val analysisId = remember(predictions) { predictions?.let(com.marksy.os.market.MarksyCalls::analysisId) }
```

  with

```kotlin
                    val instrument = when (val st = state) { is com.marksy.os.market.MarketDataState.Loaded -> st.value; is com.marksy.os.market.MarketDataState.Stale -> st.value; else -> null }
                    val analysisId = remember(instrument) { instrument?.let { com.marksy.os.market.LedgerCalls.analysisRecommendationId(it.calls, it.predictions) } }
```

- Replace

```kotlin
                    val call = (predictions?.let(com.marksy.os.market.MarksyCalls::view) as? com.marksy.os.market.MarksyCallView.Active)?.primary
                    SideEffect {
                        stockTrade = TradeIntent(
                            symbol, if (call?.targetPrice != null && call.targetPrice < call.entryPrice) TradeSide.SELL else TradeSide.BUY,
                            live.quote?.lastPrice, call?.targetPrice, call?.stopLoss
                        )
                    }
```

  with

```kotlin
                    val call = remember(instrument) { com.marksy.os.market.LedgerCalls.leadingMarksyCall(instrument?.calls) }
                    SideEffect {
                        stockTrade = TradeIntent(
                            symbol, if (call?.direction == "SELL") TradeSide.SELL else TradeSide.BUY,
                            live.quote?.lastPrice, call?.target, call?.stopLoss
                        )
                    }
```

- In the `StockDetailScreen(...)` call, replace `onOpenSymbol = { onSymbolSelected(it) }, analysis = analysis)` with `onOpenSymbol = { onSymbolSelected(it) }, analysis = analysis, onOpenTip = { openTip = it })`.

- [ ] **Step 6: Delete `MarksyCalls` and its test**

```bash
git rm app/src/main/java/com/marksy/os/market/MarksyCalls.kt app/src/test/java/com/marksy/os/market/MarksyCallsTest.kt
grep -rnE "MarksyCalls|MarksyCallView|MarksyCallCard|TrackRecord\(" app/src || echo "no monitor-driven call card left"
```

Expected: the grep prints `no monitor-driven call card left`. `PredictionsView`'s `TrackRecordStrip` is a different name and doesn't match `TrackRecord\(`.

- [ ] **Step 7: Run the tests to verify they pass**

Run the unit-test command with `--tests 'com.marksy.os.ui.StockDetailScreenTest' --tests 'com.marksy.os.market.*'`.
Expected: all PASS, 3 of them in `StockDetailScreenTest`.

- [ ] **Step 8: Commit**

```bash
git add -A app/src/main/java/com/marksy/os/ui app/src/main/java/com/marksy/os/market app/src/test/java/com/marksy/os/ui/StockDetailScreenTest.kt \
  app/src/test/java/com/marksy/os/market
git status
git commit -m "Stock page: a calls box per Marksy engine and external channel, read from the ledger tip

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task B6: My tips on the Trading tab

**Files:**
- Create: `app/src/main/java/com/marksy/os/ui/MyTipsView.kt`
- Modify: `app/src/main/java/com/marksy/os/ui/TradingIntelligenceScreen.kt`. This covers the function, the constants and `tradingTitleNote` (lines 32–126), and deletes `TradingSignalCard` plus its private helpers `rupees`, `signedPct` and `confidencePct`.
- Modify: `app/src/main/java/com/marksy/os/ui/PredictionsView.kt` (line 39: `Paged` becomes `internal`)
- Modify: `app/src/main/java/com/marksy/os/MainActivity.kt` (lines 239, 386 and 741–752)
- Modify: `app/src/main/java/com/marksy/os/intelligence/AskMarksy.kt` (line 613)
- Test: modify `app/src/test/java/com/marksy/os/intelligence/AskMarksyEvalTest.kt` (lines 144 and 172), `intelligence/AskMarksySearchTest.kt` (line 105) and `ui/TradingIntelligenceScreenTest.kt`

**Interfaces:**
- Produces `enum class MyTipsStatus(val label: String, val param: String?, val empty: String) { OPEN, CLOSED, ALL }`, with `fun next(): MyTipsStatus`.
- Produces `@Composable internal fun MyTipsView(repository: MarketIntelligenceRepository, status: MyTipsStatus, bottomPadding: Dp, onOpenStock: (String) -> Unit)`.
- Produces `TradingIntelligenceScreen(..., setupReports, tipsStatus: MyTipsStatus = MyTipsStatus.OPEN, onTipsStatusChange: (MyTipsStatus) -> Unit = {})`.
- Produces `TradingFilters = listOf("Setups", "Predictions", "My tips", "Captured")`.
- Produces `fun tradingTitleNote(filter: String, scan: LatestScanDto?, tipsStatus: MyTipsStatus = MyTipsStatus.OPEN): String`.
- Removes the `Calls` tab, its `TradeCallParser` use, and the Captured tab's `MarksyTipPayloadBuilder.symbolOf` use.

- [ ] **Step 1: Write the failing tests**

- In `AskMarksyEvalTest.kt`, change `opens(Page.TRADING, "Calls")` to `opens(Page.TRADING, "My tips")` on lines 144 and 172.
- In `AskMarksySearchTest.kt`, replace line 105 with:

```kotlin
        assertEquals("My tips" to Page.TRADING, nav("go to trade calls").let { it.arg to it.page })
        assertEquals("My tips" to Page.TRADING, nav("open my tips").let { it.arg to it.page })
```

- In `TradingIntelligenceScreenTest.kt`, delete `tappingACapturedCallOpensItsStockPage` and its regression comment. Its subject, the on-device parsed Calls tab, is deleted in this task.

- [ ] **Step 2: Run them to verify they fail**

Run the unit-test command with `--tests 'com.marksy.os.intelligence.AskMarksy*'`.
Expected: FAIL. `nav("go to trade calls")` returns `"Calls"`, and "open my tips" is not a sitemap entry.

- [ ] **Step 3: Write My tips**

Create `app/src/main/java/com/marksy/os/ui/MyTipsView.kt`:

```kotlin
package com.marksy.os.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.marksy.os.EmptyState
import com.marksy.os.market.LedgerCalls
import com.marksy.os.market.MarketDataState
import com.marksy.os.market.MarketIntelligenceRepository
import com.marksy.os.market.MyTipDto

enum class MyTipsStatus(val label: String, val param: String?, val empty: String) {
    OPEN("Open", "ACTIVE", "No open tips"), CLOSED("Closed", "CLOSED", "No closed tips yet"), ALL("All", null, "No tips yet");

    fun next(): MyTipsStatus = entries[(ordinal + 1) % entries.size]
}

/** The calls this customer received, as the ledger tracks them (spec §9 `/me/tips`); other holders are only a count. */
@Composable
internal fun MyTipsView(repository: MarketIntelligenceRepository, status: MyTipsStatus, bottomPadding: Dp, onOpenStock: (String) -> Unit) {
    val paged = remember(status) { Paged { c -> repository.myTips(status.param, c).map { it.items to it.nextCursor } } }
    LaunchedEffect(paged) { paged.more() }
    val quotes = rememberUpstoxQuotes(remember(paged.items) { paged.items.filter { LedgerCalls.isActive(it.tip) }.map { it.tip.symbol }.distinct() })
    var open by remember { mutableStateOf<String?>(null) }
    open?.let { id -> TipDetailDialog(repository, id, onOpenStock = { open = null; onOpenStock(it) }) { open = null } }

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 18.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(top = 10.dp, bottom = bottomPadding)
    ) {
        when (val s = paged.state) {
            is MarketDataState.Loading -> item { MarksyLoader("Loading your tips...") }
            is MarketDataState.Unavailable -> item { EmptyState("Marksy is not connected", "Sign in to your Marksy account in More.") }
            is MarketDataState.Error -> item { EmptyState("Your tips are unavailable", s.message) }
            is MarketDataState.Empty -> item { EmptyState(status.empty, "Calls from your broker apps, allow-listed groups and SMS senders appear here once Marksy records them.") }
            else -> {
                items(paged.items, key = { "tip-${it.tip.tipId}" }) { t -> MyTipRow(t, quotes[t.tip.symbol]?.lastPrice) { open = t.tip.tipId } }
                if (paged.hasMore) item(key = "more-${paged.items.size}") {
                    LaunchedEffect(Unit) { paged.more() }
                    MarksyLoader("Loading more...")
                }
            }
        }
    }
}

@Composable
private fun MyTipRow(item: MyTipDto, livePrice: Double?, onClick: () -> Unit) {
    val t = item.tip
    val shape = RoundedCornerShape(12.dp)
    Column(
        Modifier.fillMaxWidth().clip(shape).background(MarksyTheme.Surface).border(1.dp, MarksyTheme.BorderGlow, shape)
            .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 9.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(t.symbol, color = MarksyTheme.TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            t.direction?.let { d ->
                Text(
                    d, color = if (d == "SELL") MarksyTheme.RedUrgent else MarksyTheme.PrimaryEmerald, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 6.dp).clip(RoundedCornerShape(6.dp)).background(MarksyTheme.SurfaceRaised).padding(horizontal = 5.dp, vertical = 1.dp)
                )
            }
            Spacer(Modifier.weight(1f))
            Text(LedgerCalls.state(t), color = toneColor(LedgerCalls.tone(t)), fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
        Text(LedgerCalls.levelsText(t), color = MarksyTheme.TextSecondary, fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp))
        Row(Modifier.padding(top = 2.dp)) {
            LedgerCalls.progressText(t)?.let { Text(it, color = toneColor(LedgerCalls.progressTone(t)), fontSize = 11.sp, fontWeight = FontWeight.SemiBold) }
            Spacer(Modifier.weight(1f))
            livePrice?.let { Text("${callRupees(it)} now", color = MarksyTheme.TextPrimary, fontSize = 11.sp) }
        }
        Text(
            listOfNotNull(t.channel?.name, t.caller?.name, item.channelHeadline?.let(LedgerCalls::recordText)).joinToString(" · "),
            color = MarksyTheme.TextMuted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
        )
        Text(LedgerCalls.receivedVia(item), color = MarksyTheme.TextMuted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
```

In `ui/PredictionsView.kt`, change `private class Paged<T>(` to `internal class Paged<T>(`.

- [ ] **Step 4: Put My tips on the Trading tab**

In `ui/TradingIntelligenceScreen.kt`:
- Add `import com.marksy.os.market.StockMentions` after `import com.marksy.os.EmptyState`.
- Replace everything from the `@Composable` above `fun TradingIntelligenceScreen(` through the end of `fun tradingTitleNote(...)` with:

```kotlin
@Composable
fun TradingIntelligenceScreen(
    insights: List<TradingInsight>,
    padding: PaddingValues,
    market: MarketState = MarketState.Loading,
    selectedFilter: String = TradingFilters.first(),
    onFilterSelected: (String) -> Unit = {},
    onOpenStock: (String) -> Unit = {},
    marketRepository: com.marksy.os.market.MarketIntelligenceRepository? = null,
    setupReports: List<SetupReport> = emptyList(),
    tipsStatus: MyTipsStatus = MyTipsStatus.OPEN,
    onTipsStatusChange: (MyTipsStatus) -> Unit = {}
) {
    // Marksy supplies the calls and their record; prices tick live from the user's Upstox feed.
    Box(
        Modifier
            .fillMaxSize()
            .background(MarksyTheme.Background)
            .padding(padding)
            .consumeWindowInsets(padding)
    ) {
    Column(Modifier.fillMaxSize()) {
        when {
            selectedFilter == TAB_PREDICTIONS && marketRepository != null -> PredictionsView(marketRepository, OneHandListBottomPadding, onOpenStock)
            selectedFilter == TAB_TIPS && marketRepository != null -> MyTipsView(marketRepository, tipsStatus, OneHandListBottomPadding, onOpenStock)
            selectedFilter == TAB_PICKS -> SetupsView(marketRepository, setupReports, OneHandListBottomPadding, onOpenStock)
            else -> CapturedList(insights, onOpenStock)
        }
    }
    OneHandControls(
        filters = TradingFilters.map { it to it },
        selectedFilter = selectedFilter,
        onFilterSelected = onFilterSelected,
        actions = listOfNotNull(
            if (selectedFilter == TAB_TIPS) FloatingAction(Icons.Default.FilterList, "Show ${tipsStatus.next().label.lowercase()} tips") { onTipsStatusChange(tipsStatus.next()) } else null
        )
    )
    }
}

/** Broker executions and confirmations kept on the phone; a ticker named in one opens its stock page. */
@Composable
private fun CapturedList(insights: List<TradingInsight>, onOpenStock: (String) -> Unit) {
    val isSymbol = rememberSymbolCheck()
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(top = 10.dp, bottom = OneHandListBottomPadding)
    ) {
        if (insights.isEmpty()) item { EmptyState("No trading events captured yet.", "Order executions and confirmations from your broker apps appear here.") }
        items(insights, key = { it.eventId }) { insight ->
            val symbol = remember(insight.eventId, isSymbol) { StockMentions.find("${insight.title} ${insight.body}", isSymbol, limit = 1).firstOrNull() }
            CapturedInsightCard(insight, symbol?.let { { onOpenStock(it) } })
        }
    }
}

private const val TAB_PICKS = "Setups"
private const val TAB_PREDICTIONS = "Predictions"
private const val TAB_TIPS = "My tips"
private const val TAB_CAPTURED = "Captured"
val TradingFilters = listOf(TAB_PICKS, TAB_PREDICTIONS, TAB_TIPS, TAB_CAPTURED)

/** Title superscript: the tab, plus Marksy's scan session on its tabs and the status on My tips. */
fun tradingTitleNote(filter: String, scan: com.marksy.os.market.LatestScanDto?, tipsStatus: MyTipsStatus = MyTipsStatus.OPEN): String = when (filter) {
    TAB_TIPS -> "$filter · ${tipsStatus.label}"
    else -> filter + (if (filter == TAB_PICKS || filter == TAB_PREDICTIONS) com.marksy.os.market.PicksBasis.day(scan?.scanSessionDate)?.let { " · $it" } else null).orEmpty()
}
```

- Delete `private fun rupees(...)` and `private fun signedPct(...)`, the comment `// Marksy reports confidence as 0–1 on some routes and 0–100 on others.` and `private fun confidencePct(...)` below it, and the whole `@Composable private fun TradingSignalCard(...)`. The removed Calls tab was their only user.

In `MainActivity.kt`:
- Directly after `        var tradingFilter by rememberSaveable { mutableStateOf(TradingFilters.first()) }`, add:

```kotlin
        var tipsStatus by rememberSaveable { mutableStateOf(com.marksy.os.ui.MyTipsStatus.OPEN) }
```

- Replace `            selectedTab == 3 -> com.marksy.os.ui.tradingTitleNote(tradingFilter, picksScan)` with `            selectedTab == 3 -> com.marksy.os.ui.tradingTitleNote(tradingFilter, picksScan, tipsStatus)`.
- In the `TradingIntelligenceScreen(...)` call, replace

```kotlin
                                .take(3)
                        }
                    )
```

  with

```kotlin
                                .take(3)
                        },
                        tipsStatus = tipsStatus,
                        onTipsStatusChange = { tipsStatus = it }
                    )
```

In `intelligence/AskMarksy.kt`, replace line 613 (`"calls" to act(Page.TRADING, "Calls", "Calls"), …`) with:

```kotlin
        "calls" to act(Page.TRADING, "My tips", "My tips"), "trade calls" to act(Page.TRADING, "My tips", "My tips"), "trading calls" to act(Page.TRADING, "My tips", "My tips"),
        "my tips" to act(Page.TRADING, "My tips", "My tips"),
```

- [ ] **Step 5: Run the tests to verify they pass**

Run the unit-test command with `--tests 'com.marksy.os.intelligence.AskMarksy*' --tests 'com.marksy.os.ui.TradingIntelligenceScreenTest'`.
Expected: all PASS. `twoCallsForTheSameSymbolBothRenderAndInvalidatedOnesAreLeftOut` is unchanged; it uses the default Setups tab.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/marksy/os/ui/MyTipsView.kt app/src/main/java/com/marksy/os/ui/TradingIntelligenceScreen.kt \
  app/src/main/java/com/marksy/os/ui/PredictionsView.kt app/src/main/java/com/marksy/os/MainActivity.kt \
  app/src/main/java/com/marksy/os/intelligence/AskMarksy.kt app/src/test/java/com/marksy/os/intelligence/AskMarksyEvalTest.kt \
  app/src/test/java/com/marksy/os/intelligence/AskMarksySearchTest.kt app/src/test/java/com/marksy/os/ui/TradingIntelligenceScreenTest.kt
git commit -m "Trading: My tips replaces the on-device Calls tab, with progress, record and where each tip came from

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task B7: Scorecards on the Trading tab, with the §8.3 filters

**Files:**
- Create: `app/src/main/java/com/marksy/os/ui/ScorecardsView.kt`
- Modify: `app/src/main/java/com/marksy/os/ui/TradingIntelligenceScreen.kt` (the Task B6 version)
- Modify: `app/src/main/java/com/marksy/os/MainActivity.kt`
- Modify: `app/src/main/java/com/marksy/os/intelligence/AskMarksy.kt`
- Test: modify `app/src/test/java/com/marksy/os/intelligence/AskMarksySearchTest.kt`

**Interfaces:**
- Produces, in `ui/ScorecardsView.kt`:
  - `internal val ScorecardQuerySaver: Saver<ScorecardQuery, String>`
  - `@Composable internal fun ScorecardsView(repository: MarketIntelligenceRepository, query: ScorecardQuery, bottomPadding: Dp)`
  - `@Composable internal fun ScorecardFilterDialog(initial: ScorecardQuery, onApply: (ScorecardQuery) -> Unit, onDismiss: () -> Unit)`
- Produces `TradingIntelligenceScreen(..., scorecardQuery: ScorecardQuery = ScorecardQuery(), onScorecardQueryChange: (ScorecardQuery) -> Unit = {})`.
- Produces `TradingFilters = listOf("Setups", "Predictions", "My tips", "Scorecards", "Captured")`.
- Produces `tradingTitleNote(filter, scan, tipsStatus, scorecards: ScorecardQuery = ScorecardQuery())`.

- [ ] **Step 1: Write the failing test**

In `AskMarksySearchTest.kt`, directly after the two "My tips" lines from Task B6, add:

```kotlin
        assertEquals("Scorecards" to Page.TRADING, nav("open scorecards").let { it.arg to it.page })
```

- [ ] **Step 2: Run it to verify it fails**

Run the unit-test command with `--tests 'com.marksy.os.intelligence.AskMarksySearchTest'`.
Expected: FAIL. "open scorecards" is not a sitemap entry.

- [ ] **Step 3: Write the Scorecards view and its filter**

Create `app/src/main/java/com/marksy/os/ui/ScorecardsView.kt`:

```kotlin
package com.marksy.os.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.marksy.os.EmptyState
import com.marksy.os.market.EntityScorecardDto
import com.marksy.os.market.EntityScorecardListDto
import com.marksy.os.market.HorizonBucket
import com.marksy.os.market.MarketDataState
import com.marksy.os.market.MarketIntelligenceRepository
import com.marksy.os.market.ScorecardBodyDto
import com.marksy.os.market.ScorecardDto
import com.marksy.os.market.ScorecardEntity
import com.marksy.os.market.ScorecardPeriod
import com.marksy.os.market.ScorecardQuery
import com.marksy.os.market.ScorecardSummaryDto
import com.marksy.os.market.ScorecardText
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

internal val ScorecardQuerySaver = Saver<ScorecardQuery, String>(save = { it.encode() }, restore = { ScorecardQuery.decode(it) })

/** Scorecards (spec §8): the customer's own record, Marksy against external calls, then every channel or caller
 * ranked by trust, all under one §8.3 filter. Every number is the server's; the phone never computes a window. */
@Composable
internal fun ScorecardsView(repository: MarketIntelligenceRepository, query: ScorecardQuery, bottomPadding: Dp) {
    val mine by produceState<MarketDataState<ScorecardDto>>(MarketDataState.Loading, query) { value = repository.myScorecard(query) }
    val split by produceState<MarketDataState<ScorecardSummaryDto>>(MarketDataState.Loading, query) { value = repository.scorecardSummary(query) }
    val ranked by produceState<MarketDataState<EntityScorecardListDto>>(MarketDataState.Loading, query) { value = repository.scorecards(query.entity, query) }
    var detail by remember { mutableStateOf<EntityScorecardDto?>(null) }
    detail?.let { e -> ScorecardDetailDialog(repository, query, e) { detail = null } }

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 18.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(top = 10.dp, bottom = bottomPadding)
    ) {
        (mine as? MarketDataState.Loaded)?.value?.let { card -> item(key = "mine") { ScoreTile("Your record", ScorecardText.range(card.filter), card.body) } }
        (split as? MarketDataState.Loaded)?.value?.let { s ->
            item(key = "split") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ScoreTile("Marksy", null, s.marksy, Modifier.weight(1f))
                    ScoreTile("External", null, s.external, Modifier.weight(1f))
                }
            }
        }
        when (val r = ranked) {
            is MarketDataState.Loading -> item { MarksyLoader("Loading scorecards...") }
            is MarketDataState.Unavailable -> item { EmptyState("Marksy is not connected", "Sign in to your Marksy account in More.") }
            is MarketDataState.Error -> item { EmptyState("Scorecards unavailable", r.message) }
            is MarketDataState.Empty -> item { EmptyState("No ${query.entity.label.lowercase()} with calls in this period", "Widen the period or clear the horizon.") }
            is MarketDataState.Loaded -> items(r.value.items, key = { "${it.entity}-${it.id}" }) { e -> EntityRow(e) { detail = e } }
            else -> Unit
        }
    }
}

@Composable
private fun ScoreTile(title: String, subtitle: String?, body: ScorecardBodyDto, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(12.dp)
    Column(modifier.fillMaxWidth().clip(shape).background(MarksyTheme.Surface).border(1.dp, MarksyTheme.BorderGlow, shape).padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = MarksyTheme.TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text(ScorecardText.trust(body.trust), color = if (body.trust.trustScore != null) MarksyTheme.PrimaryEmerald else MarksyTheme.TextMuted, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
        subtitle?.let { Text(it, color = MarksyTheme.TextMuted, fontSize = 10.sp) }
        Text(ScorecardText.summary(body), color = MarksyTheme.TextSecondary, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun EntityRow(e: EntityScorecardDto, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Column(
        Modifier.fillMaxWidth().clip(shape).background(MarksyTheme.Surface).border(1.dp, MarksyTheme.BorderGlow, shape)
            .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 9.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                e.name + (e.channelName?.takeIf { it != e.name }?.let { " · $it" } ?: ""), color = MarksyTheme.TextPrimary, fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
            )
            Text(ScorecardText.trust(e.body.trust), color = if (e.body.trust.trustScore != null) MarksyTheme.PrimaryEmerald else MarksyTheme.TextMuted, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
        Text(ScorecardText.summary(e.body), color = MarksyTheme.TextSecondary, fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp))
    }
}

/** One entity's full §8.1/§8.2 card; a channel also lists its callers, narrowed to it (§8.3). */
@Composable
private fun ScorecardDetailDialog(repository: MarketIntelligenceRepository, query: ScorecardQuery, entity: EntityScorecardDto, onDismiss: () -> Unit) {
    val card by produceState<MarketDataState<ScorecardDto>>(MarketDataState.Loading, entity, query) {
        value = repository.scorecard(ScorecardEntity.fromParam(entity.entity), entity.id, query)
    }
    val callers by produceState<MarketDataState<EntityScorecardListDto>>(MarketDataState.Loading, entity, query) {
        value = if (entity.entity == ScorecardEntity.CHANNEL.param) repository.scorecards(ScorecardEntity.CALLER, query.copy(channelId = entity.id)) else MarketDataState.Empty
    }
    MarksyDialog(
        onDismissRequest = onDismiss,
        title = { Text(entity.name, color = MarksyTheme.TextPrimary) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                when (val c = card) {
                    is MarketDataState.Loaded -> {
                        Text(ScorecardText.range(c.value.filter), color = MarksyTheme.TextMuted, fontSize = 11.sp)
                        ScorecardText.details(c.value.body).forEach { (label, value) ->
                            Row {
                                Text(label, color = MarksyTheme.TextSecondary, fontSize = 12.sp, modifier = Modifier.weight(1f))
                                Text(value, color = MarksyTheme.TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                    is MarketDataState.Error -> Text(c.message, color = MarksyTheme.TextSecondary)
                    else -> MarksyLoader("Loading...")
                }
                (callers as? MarketDataState.Loaded)?.value?.items?.takeIf { it.isNotEmpty() }?.let { list ->
                    Text("Callers in ${entity.name}", color = MarksyTheme.TextMuted, fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp))
                    list.forEach { c -> Text("${c.name} · ${ScorecardText.trust(c.body.trust)} · ${ScorecardText.summary(c.body)}", color = MarksyTheme.TextSecondary, fontSize = 11.sp) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done", color = MarksyTheme.PrimaryEmerald, fontWeight = FontWeight.SemiBold) } }
    )
}

/** Which list, and the §8.3 period and horizon; Apply stays off until a custom range has both ends in order. */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
internal fun ScorecardFilterDialog(initial: ScorecardQuery, onApply: (ScorecardQuery) -> Unit, onDismiss: () -> Unit) {
    var q by remember { mutableStateOf(initial) }
    var pickingStart by remember { mutableStateOf<Boolean?>(null) }
    val applicable = q.filterParams() != null
    MarksyDialog(
        onDismissRequest = onDismiss,
        title = { Text("Scorecards", color = MarksyTheme.TextPrimary) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Show", color = MarksyTheme.TextMuted, fontSize = 11.sp)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    ScorecardEntity.entries.forEach { e -> Pill(e.label, selected = q.entity == e) { q = q.copy(entity = e) } }
                }
                Text("Period (IST)", color = MarksyTheme.TextMuted, fontSize = 11.sp)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    ScorecardPeriod.entries.forEach { p -> Pill(p.label, selected = q.period == p) { q = q.copy(period = p) } }
                }
                if (q.period == ScorecardPeriod.CUSTOM) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Pill("From ${q.startDate?.let(ScorecardText::date) ?: "…"}", selected = q.startDate != null) { pickingStart = true }
                    Pill("To ${q.endDate?.let(ScorecardText::date) ?: "…"}", selected = q.endDate != null) { pickingStart = false }
                }
                Text("Horizon", color = MarksyTheme.TextMuted, fontSize = 11.sp)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Pill("All", selected = q.horizon == null) { q = q.copy(horizon = null) }
                    HorizonBucket.entries.forEach { h -> Pill(h.label, selected = q.horizon == h) { q = q.copy(horizon = h) } }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onApply(q) }, enabled = applicable) {
                Text("Apply", color = if (applicable) MarksyTheme.PrimaryEmerald else MarksyTheme.TextMuted, fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = MarksyTheme.TextSecondary) } }
    )
    pickingStart?.let { start ->
        val state = rememberDatePickerState(
            initialSelectedDateMillis = ((if (start) q.startDate else q.endDate) ?: LocalDate.now()).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        )
        DatePickerDialog(
            onDismissRequest = { pickingStart = null },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { ms ->
                        val day = Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate()
                        q = if (start) q.copy(startDate = day) else q.copy(endDate = day)
                    }
                    pickingStart = null
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { pickingStart = null }) { Text("Cancel") } }
        ) { DatePicker(state) }
    }
}
```

- [ ] **Step 4: Add the tab, its filter action and the title note**

In `ui/TradingIntelligenceScreen.kt` (the Task B6 version):
- Add `import com.marksy.os.market.ScorecardQuery` after `import com.marksy.os.market.StockMentions`.
- Replace

```kotlin
    tipsStatus: MyTipsStatus = MyTipsStatus.OPEN,
    onTipsStatusChange: (MyTipsStatus) -> Unit = {}
) {
```

  with

```kotlin
    tipsStatus: MyTipsStatus = MyTipsStatus.OPEN,
    onTipsStatusChange: (MyTipsStatus) -> Unit = {},
    scorecardQuery: ScorecardQuery = ScorecardQuery(),
    onScorecardQueryChange: (ScorecardQuery) -> Unit = {}
) {
    var filteringScorecards by remember { mutableStateOf(false) }
    if (filteringScorecards) ScorecardFilterDialog(scorecardQuery, onApply = { onScorecardQueryChange(it); filteringScorecards = false }) { filteringScorecards = false }
```

- Directly after the `selectedFilter == TAB_TIPS && marketRepository != null -> MyTipsView(...)` line, add:

```kotlin
            selectedFilter == TAB_SCORECARDS && marketRepository != null -> ScorecardsView(marketRepository, scorecardQuery, OneHandListBottomPadding)
```

- Replace the `actions = listOfNotNull(...)` argument with:

```kotlin
        actions = listOfNotNull(
            if (selectedFilter == TAB_TIPS) FloatingAction(Icons.Default.FilterList, "Show ${tipsStatus.next().label.lowercase()} tips") { onTipsStatusChange(tipsStatus.next()) } else null,
            if (selectedFilter == TAB_SCORECARDS) FloatingAction(Icons.Default.FilterList, "Filter scorecards") { filteringScorecards = true } else null
        )
```

- Replace the constants and `tradingTitleNote` with:

```kotlin
private const val TAB_PICKS = "Setups"
private const val TAB_PREDICTIONS = "Predictions"
private const val TAB_TIPS = "My tips"
private const val TAB_SCORECARDS = "Scorecards"
private const val TAB_CAPTURED = "Captured"
val TradingFilters = listOf(TAB_PICKS, TAB_PREDICTIONS, TAB_TIPS, TAB_SCORECARDS, TAB_CAPTURED)

/** Title superscript: the tab, plus Marksy's scan session, the My tips status, or the scorecard filter. */
fun tradingTitleNote(
    filter: String,
    scan: com.marksy.os.market.LatestScanDto?,
    tipsStatus: MyTipsStatus = MyTipsStatus.OPEN,
    scorecards: ScorecardQuery = ScorecardQuery()
): String = when (filter) {
    TAB_TIPS -> "$filter · ${tipsStatus.label}"
    TAB_SCORECARDS -> "$filter · ${scorecards.entity.label} · ${scorecards.label()}"
    else -> filter + (if (filter == TAB_PICKS || filter == TAB_PREDICTIONS) com.marksy.os.market.PicksBasis.day(scan?.scanSessionDate)?.let { " · $it" } else null).orEmpty()
}
```

In `MainActivity.kt`:
- Directly after the `var tipsStatus by rememberSaveable { … }` line from Task B6, add:

```kotlin
        var scorecardQuery by rememberSaveable(stateSaver = com.marksy.os.ui.ScorecardQuerySaver) { mutableStateOf(com.marksy.os.market.ScorecardQuery()) }
```

- Replace `com.marksy.os.ui.tradingTitleNote(tradingFilter, picksScan, tipsStatus)` with `com.marksy.os.ui.tradingTitleNote(tradingFilter, picksScan, tipsStatus, scorecardQuery)`.
- Replace

```kotlin
                        onTipsStatusChange = { tipsStatus = it }
                    )
```

  with

```kotlin
                        onTipsStatusChange = { tipsStatus = it },
                        scorecardQuery = scorecardQuery,
                        onScorecardQueryChange = { scorecardQuery = it }
                    )
```

In `intelligence/AskMarksy.kt`, directly after the `"my tips" to act(Page.TRADING, "My tips", "My tips"),` line from Task B6, add:

```kotlin
        "scorecards" to act(Page.TRADING, "Scorecards", "Scorecards"), "scorecard" to act(Page.TRADING, "Scorecards", "Scorecards"),
        "track record" to act(Page.TRADING, "Scorecards", "Scorecards"),
```

- [ ] **Step 5: Run the tests to verify they pass**

Run the unit-test command with `--tests 'com.marksy.os.intelligence.AskMarksy*' --tests 'com.marksy.os.ui.TradingIntelligenceScreenTest' --tests 'com.marksy.os.market.*'`.
Expected: all PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/marksy/os/ui/ScorecardsView.kt app/src/main/java/com/marksy/os/ui/TradingIntelligenceScreen.kt \
  app/src/main/java/com/marksy/os/MainActivity.kt app/src/main/java/com/marksy/os/intelligence/AskMarksy.kt \
  app/src/test/java/com/marksy/os/intelligence/AskMarksySearchTest.kt
git commit -m "Trading: Scorecards for channels, callers and Marksy engines, with the section 8.3 period and horizon filters

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task B8: Replace the classifier's trade-call pre-filter with capture-gate rules

**Files:**
- Modify: `app/src/main/java/com/marksy/os/notification/NotificationClassifier.kt` (`VERSION`, lines 121 and 129–132, `classify` lines 142–166, and the 4a `isOwnOrderEvent`)
- Modify: `app/src/main/java/com/marksy/os/gateway/CaptureGate.kt` (4a Task B3 Step 5: the reason codes and the head of `decide`)
- Modify: `app/src/main/java/com/marksy/os/connector/ConnectorFramework.kt` (imports; `ingestNow` lines 146, 172–177, 187–188 and 200, as 4a Task B2 Step 4 left them)
- Modify: `app/src/main/java/com/marksy/os/data/local/NotificationEventDao.kt` (lines 56–57)
- Modify: `app/src/main/java/com/marksy/os/gateway/TradingDeliveryWorker.kt` (every `findPendingTrading(`)
- Test: modify `app/src/test/java/com/marksy/os/gateway/CaptureGateTest.kt`, `connector/IngestionPipelineTest.kt`, `notification/NotificationClassifierTest.kt` and `gateway/TradingDeliveryRunTest.kt`

**Interfaces:**
- Produces:
  - `CaptureGate.queues(sourcePackage: String, category: String, chatGroup: Boolean?): Boolean`
  - `CaptureGate.NOT_A_CANDIDATE = "not-a-candidate"` (replaces `NOT_TRADING`) and `CaptureGate.OWN_ACCOUNT = "own-account"`
  - `NotificationClassifier.isOwnAccountEvent(title: String, body: String): Boolean`, and `NotificationClassifier.VERSION = 10`
  - `NotificationEventDao.findPendingCapture(limit: Int): List<NotificationEventEntity>` (replaces `findPendingTrading`)
- `CaptureGate.decide`'s signature is unchanged. Its first check becomes category candidacy (decision 2), and `OWN_ACCOUNT` follows `OWN_ORDER`.
- Removes `NotificationClassifier`'s `callSide`, `callLevels`, `isTradeCall`, `callChannels` and every `TradeCallParser` use, plus `ConnectorFramework`'s `TradeCallParser` use.

- [ ] **Step 1: Write the failing tests**

In `CaptureGateTest.kt`:
- In `onlyTheCaptureSetLeavesThePhone`, change `CaptureDecision.Keep(CaptureGate.NOT_TRADING)` to `CaptureDecision.Keep(CaptureGate.NOT_A_CANDIDATE)`.
- Add, directly after `onlyTheCaptureSetLeavesThePhone`:

```kotlin
    // Phase 4b: calls no longer classify as TRADING, so the gate, not the classifier, decides what may leave.
    @Test
    fun aBrokerResearchCallStillLeavesButHoldingsAlertsAndOtpsStayLocal() {
        fun decide(category: String, body: String, pkg: String = "com.upstox.pro", title: String = "Upstox") =
            CaptureGate.decide(event(pkg = pkg, title = title, body = body).copy(category = category, isTrading = category == "TRADING"), context)
        val sms = "com.google.android.apps.messaging"
        val ownAccount = CaptureDecision.Keep(CaptureGate.OWN_ACCOUNT)
        val notACandidate = CaptureDecision.Keep(CaptureGate.NOT_A_CANDIDATE)

        assertTrue(decide("MARKET", "BUY RENUKA CMP : 23.62 SL : 22.25 TGT : 26") is CaptureDecision.Send)
        assertEquals(ownAccount, decide("MARKET", "Your stock NATSEC has touched 52 week low of 780.0"))
        assertEquals(ownAccount, decide("MARKET", "RELIANCE in your holdings is up 3% today"))
        assertEquals(ownAccount, decide("MARKET", "Your P&L statement for September is ready"))
        assertEquals(notACandidate, decide("PROMOTIONS", "Zero brokerage for a month"))
        assertEquals(notACandidate, decide("OTP", "Your OTP is 482913. Never share your OTP", pkg = sms, title = "JD-ZERODH-S"))
        assertEquals(notACandidate, decide("BANKING", "Rs 5,000 credited to your account", pkg = sms, title = "JD-ZERODH-S"))
        assertTrue(decide("OTHER", "BUY | CROPSTER AGRO | Entry ₹2.82 | Target ₹10 | SL ₹2", pkg = sms, title = "JD-ZERODH-S") is CaptureDecision.Send)
        val groupCall = event(pkg = "com.whatsapp", source = "WhatsApp", title = "StockTips", group = true).copy(category = "MESSAGES", isTrading = false)
        assertTrue(CaptureGate.decide(groupCall, context) is CaptureDecision.Send)

        assertTrue(CaptureGate.queues("com.upstox.pro", "MARKET", null))
        assertFalse(CaptureGate.queues("com.facebook.orca", "MESSAGES", null))
        assertTrue(CaptureGate.queues("org.telegram.messenger", "MESSAGES", true))
        assertFalse(CaptureGate.queues("org.telegram.messenger", "MESSAGES", false))
        assertFalse(CaptureGate.queues("org.telegram.messenger", "MESSAGES", null))
        assertFalse(CaptureGate.queues(sms, "OTP", null))
    }
```

In `IngestionPipelineTest.kt`, replace `chatUpdateThatAddsATradeCallBecomesTradingAndIsDelivered` and its regression comment with:

```kotlin
    // Regression kept from the trade-call rule: a tip posted after "Good morning" in a sent chat notification must go.
    @Test
    fun aNewMessageInADeliveredChatIsItsOwnCaptureAndALocalChatStillFolds() = runBlocking {
        val dao = db.notificationEventDao()
        val group = raw("org.telegram.messenger", "chat", "Tips Group", "Good morning all").copy(groupConversation = true)
        val sent = (pipeline.ingest(group) as IngestionPipeline.Result.Stored).eventId
        assertEquals("PENDING", dao.getById(sent)!!.deliveryState)
        dao.claimPendingTrading(sent, 1, t0)
        dao.updateInFlightDeliveryState(sent, "DELIVERED", 1, t0)

        val next = pipeline.ingest(group.copy(body = "Good morning all\nBUY RENUKA CMP 23.62 SL 22.25 TGT 26")) as IngestionPipeline.Result.Stored
        val added = dao.getById(next.eventId)!!
        assertEquals("BUY RENUKA CMP 23.62 SL 22.25 TGT 26", added.body)
        assertEquals("PENDING", added.deliveryState)

        val oneToOne = raw("org.telegram.messenger", "dm", "Rahul", "hi").copy(groupConversation = false)
        val local = (pipeline.ingest(oneToOne) as IngestionPipeline.Result.Stored).eventId
        assertTrue(pipeline.ingest(oneToOne.copy(body = "hi\nBUY IDEA CMP 9.5 SL 8.9 TGT 11")) is IngestionPipeline.Result.Updated)
        assertEquals("NOT_APPLICABLE", dao.getById(local)!!.deliveryState)
        assertEquals(2, trading)
    }
```

In `NotificationClassifierTest.kt`:
- Delete `brokerTipCallWithCmpSlTargetIsTrading`, `upstoxPlayStorePackageCallIsTrading` and `iciciResearchCallIsTrading` with their regression comments. Delete `smsAndChatCallsAreTrading` with its comment.
- Add in their place:

```kotlin
    // Phase 4b: the phone no longer spots calls; they land in categories the capture gate sends (CaptureGateTest).
    @Test fun callsLandInCategoriesTheCaptureGateSends() {
        fun cat(pkg: String, t: String, b: String) = NotificationClassifier.classify(pkg, t, b).category
        assertEquals(NotificationClassifier.Category.MARKET, cat("com.fivepaisa.trade", "Short term Call", "BUY RENUKA CMP : 23.62 SL : 22.25 TGT : 26"))
        assertEquals(NotificationClassifier.Category.MARKET, cat("in.upstox.app", "📈BUY LCCPROJECT with 20.0% upside potential", "🛠️ Entry : Rs 144.24 🎯 Target : Rs 173.08 🛑 Stoploss : Rs 129.81"))
        assertEquals(NotificationClassifier.Category.MARKET, cat("com.icicidirect.idirectsuper", "ICICI Direct", "Buy INDGN around Rs 609 for 12 Month with target price of Rs 750, potential upside of 23.15%."))
        assertEquals(NotificationClassifier.Category.OTHER, cat("com.google.android.apps.messaging", "KISHAN ENTERPRISE", "KISHAN ENTERPRISE: Dear Client \nBUY | CROPSTER AGRO | \nEntry ₹2.82 | Target ₹10 | SL ₹2 | \nTime: 1-2 Months"))
        assertEquals(NotificationClassifier.Category.MESSAGES, cat("com.whatsapp", "Tips Group", "BUY TATASTEEL CMP 152 SL 147 TGT 162"))
        assertEquals(NotificationClassifier.Category.MESSAGES, cat("org.telegram.messenger", "Stock Calls", "SELL INFY @ 1500 target 1450 stoploss 1525"))
    }
```

- Replace `tradeSmsWithOtpWarningIsTrading` with the test below. It passes before and after this task, and guards the footer rule through the change.

```kotlin
    // Regression: a tip SMS with a "never share your OTP" footer was filed as an OTP; a real OTP with it still is one.
    @Test fun tradeSmsWithOtpWarningIsNotAnOtp() {
        val tip = NotificationClassifier.classify("com.google.android.apps.messaging", "KISHAN", "BUY RENUKA CMP 23.62 SL 22.25 TGT 26. Never share your OTP with anyone.")
        val otp = NotificationClassifier.classify("com.google.android.apps.messaging", "JD-ZERODH-S", "Use 482913 to log in. Never share your OTP with anyone.")
        assertTrue(tip.category != NotificationClassifier.Category.OTP)
        assertEquals(NotificationClassifier.Category.OTP, otp.category)
    }
```

In `TradingDeliveryRunTest.kt`, replace every `dao.findPendingTrading(` with `dao.findPendingCapture(`:

```bash
sed -i 's/findPendingTrading(/findPendingCapture(/g' app/src/test/java/com/marksy/os/gateway/TradingDeliveryRunTest.kt
```

- [ ] **Step 2: Run them to verify they fail**

Run the unit-test command with `--tests 'com.marksy.os.gateway.*' --tests 'com.marksy.os.connector.IngestionPipelineTest' --tests 'com.marksy.os.notification.NotificationClassifier*'`.
Expected: FAIL at compile with `Unresolved reference 'NOT_A_CANDIDATE'`, `'OWN_ACCOUNT'`, `'queues'` and `'findPendingCapture'`.

- [ ] **Step 3: Delete the classifier's trade-call rule**

In `notification/NotificationClassifier.kt`:
- Change `const val VERSION = 9` to `const val VERSION = 10`.
- Delete the line `    private val callChannels = listOf("messaging", "mms", "sms", "whatsapp", "telegram")`.
- Replace

```kotlin
    // Broker tip/call shorthand: "BUY RENUKA CMP : 23.62 SL : 22.25 TGT : 26", "SELL X @ 120 target 110 stoploss 125".
    private val callSide = Regex("""\b(buy|sell|short(?![\s-]*term)|accumulate)\b""")
    private val callLevels = Regex("""\b(cmp|ltp|sl|tgt|target|targets|stoploss|stop-loss|entry)\b""")
    private fun isTradeCall(text: String): Boolean = callSide.containsMatchIn(text) && callLevels.findAll(text).count() >= 2
```

  with

```kotlin
    // A tip's "never share your OTP" footer alone doesn't make it an OTP: a real OTP names no price levels.
    private val priceLevels = Regex("""\b(cmp|ltp|sl|tgt|target|targets|stoploss|stop-loss|entry)\b""")
```

- Directly after 4a's `fun isOwnOrderEvent(title: String, body: String): Boolean { … }`, add:

```kotlin
    // The customer's own holdings, portfolio and account alerts never leave the phone (spec §2.11).
    private val ownAccountEvents = Regex("""\byour\s+(?:stocks?|holdings?|portfolio|positions?|watchlist|funds?|margin|account|a/c|demat|sips?|mandates?|pledges?|ledger|p&l|pnl|investments?)\b|\bcontract\s+note\b""")

    fun isOwnAccountEvent(title: String, body: String): Boolean = ownAccountEvents.containsMatchIn("$title $body".lowercase())
```

- In `classify`, replace

```kotlin
        // Tip SMS often end with "never share your OTP"; that warning alone does not make a call an OTP.
        val otpText = if (TradeCallParser.parse(title, body) != null) notificationText.replace(otpWarning, " ") else notificationText
```

  with

```kotlin
        val otpText = if (priceLevels.findAll(notificationText).count() >= 2) notificationText.replace(otpWarning, " ") else notificationText
```

- Replace

```kotlin
        // Broker and market-news apps: a call or execution is TRADING, a call-to-action is the app's own
        // marketing, and everything else (holdings alerts, research views, IPO notices, market moves) is MARKET.
```

  with

```kotlin
        // Broker and market-news apps: an execution is TRADING, a call-to-action is the app's own marketing, and
        // everything else (calls, holdings alerts, research views, IPO notices, market moves) is MARKET.
```

- Replace `            if (execution || isTradeCall(notificationText) || TradeCallParser.parse(title, body) != null) return Result(tradingRule.category, tradingRule.priority, tradingRule.confidence)` with:

```kotlin
            if (execution) return Result(tradingRule.category, tradingRule.priority, tradingRule.confidence)
```

- Delete

```kotlin
        // Calls also arrive by SMS and chat. Only a fully parsed call (side, symbol, two price levels) counts,
        // so ordinary messages that say "buy" never become trades.
        if (callChannels.any { normalizedPackage.contains(it) } && TradeCallParser.parse(title, body) != null) {
            return Result(tradingRule.category, tradingRule.priority, .85f)
        }
```

- [ ] **Step 4: Move candidacy into the capture gate**

In `gateway/CaptureGate.kt`:
- Replace `    const val NOT_TRADING = "not-trading"` with:

```kotlin
    const val NOT_A_CANDIDATE = "not-a-candidate"
    const val OWN_ACCOUNT = "own-account"
```

- Replace the head of `decide`:

```kotlin
    fun decide(event: NotificationEventEntity, context: CaptureContext): CaptureDecision {
        if (!event.isTrading || event.category != "TRADING" || event.sourceKey.isBlank()) return CaptureDecision.Keep(NOT_TRADING)
        val appPackage = event.sourcePackage.trim().lowercase(Locale.ROOT)
        val medium = CaptureMedium.of(appPackage)
        // Brokers tell the customer about their own orders in apps and SMS, not in chat groups.
        if (!medium.isChat && NotificationClassifier.isOwnOrderEvent(event.title, event.body)) return CaptureDecision.Keep(OWN_ORDER)
```

  with

```kotlin
    fun decide(event: NotificationEventEntity, context: CaptureContext): CaptureDecision {
        val appPackage = event.sourcePackage.trim().lowercase(Locale.ROOT)
        val medium = CaptureMedium.of(appPackage)
        if (event.sourceKey.isBlank() || event.category !in candidateCategories(medium)) return CaptureDecision.Keep(NOT_A_CANDIDATE)
        // Brokers tell the customer about their own orders and holdings in apps and SMS, not in chat groups.
        if (!medium.isChat && NotificationClassifier.isOwnOrderEvent(event.title, event.body)) return CaptureDecision.Keep(OWN_ORDER)
        if (!medium.isChat && NotificationClassifier.isOwnAccountEvent(event.title, event.body)) return CaptureDecision.Keep(OWN_ACCOUNT)
```

- Directly before `    /** One key per notification row, so a retry is the same receipt; salted so it reveals no notification key. */`, add:

```kotlin
    /** Whether a new row may ever leave the phone, decided at capture; the capture set is checked at delivery. */
    fun queues(sourcePackage: String, category: String, chatGroup: Boolean?): Boolean {
        val medium = CaptureMedium.of(sourcePackage)
        return category in candidateCategories(medium) && (!medium.isChat || chatGroup == true)
    }

    // Apps: executions and broker/market updates, where calls now land; chats and SMS: anything but private categories.
    private val APP_CATEGORIES = setOf("TRADING", "MARKET")
    private val CHAT_CATEGORIES = setOf("TRADING", "MARKET", "MESSAGES", "OTHER", "PROMOTIONS")

    private fun candidateCategories(medium: CaptureMedium) = if (medium == CaptureMedium.APP_NOTIFICATION) APP_CATEGORIES else CHAT_CATEGORIES

```

In `connector/ConnectorFramework.kt`:
- Replace `import com.marksy.os.notification.TradeCallParser` with `import com.marksy.os.gateway.CaptureGate`.
- Directly after `            val isTrading = result.category == NotificationClassifier.Category.TRADING`, add:

```kotlin
            val queued = CaptureGate.queues(raw.sourcePackage, result.category.name, raw.groupConversation)
```

- Replace

```kotlin
                // A new call in an already-trading notification is its own tip, delivered separately.
                val added = if (raw.replaceOnUpdate) raw.body else NotificationTextExtractor.added(existing.body, raw.body)
                if (existing.isTrading && added.isNotBlank() && TradeCallParser.parse(title, added) != null) {
```

  with

```kotlin
                // Lines added after the row left the phone are a new receipt; a row kept local keeps folding.
                val added = if (raw.replaceOnUpdate) raw.body else NotificationTextExtractor.added(existing.body, raw.body)
                if (existing.deliveryState in SENT_STATES && added.isNotBlank()) {
```

- In the new-row entity, replace `deliveryState = if (isTrading) DeliveryState.PENDING.name else DeliveryState.NOT_APPLICABLE.name,` with `deliveryState = if (queued) DeliveryState.PENDING.name else DeliveryState.NOT_APPLICABLE.name,`.
- Replace `            if (isTrading && !applied.archived) runCatching(onTradingCaptured)` with `            if (queued && !applied.archived) runCatching(onTradingCaptured)`.
- At the end of the file, add:

```kotlin

private val SENT_STATES = setOf(DeliveryState.IN_FLIGHT.name, DeliveryState.DELIVERED.name)
```

In `data/local/NotificationEventDao.kt`, replace

```kotlin
    @Query("SELECT * FROM notification_events WHERE isTrading = 1 AND archived = 0 AND deliveryState = 'PENDING' ORDER BY postedAt ASC LIMIT :limit")
    suspend fun findPendingTrading(limit: Int): List<NotificationEventEntity>
```

with

```kotlin
    // Queued by CaptureGate.queues at capture, whatever the category; the gate decides again at delivery.
    @Query("SELECT * FROM notification_events WHERE archived = 0 AND deliveryState = 'PENDING' ORDER BY postedAt ASC LIMIT :limit")
    suspend fun findPendingCapture(limit: Int): List<NotificationEventEntity>
```

Then rename the worker's two calls:

```bash
sed -i 's/findPendingTrading(/findPendingCapture(/g' app/src/main/java/com/marksy/os/gateway/TradingDeliveryWorker.kt
grep -rn "findPendingTrading\|NOT_TRADING\|isTradeCall\|callChannels" app/src || echo "old pre-filter gone"
```

Expected: `old pre-filter gone`.

- [ ] **Step 5: Run the tests to verify they pass**

Run the unit-test command with `--tests 'com.marksy.os.gateway.*' --tests 'com.marksy.os.connector.*' --tests 'com.marksy.os.notification.*' --tests 'com.marksy.os.intelligence.EventIntelligencePipelineTest'`.
Expected: all PASS.
- `CaptureGateTest` has 9 tests: 4a's 8 and this one.
- `IngestionPipelineTest.newCallInAnAlreadyTradingNotificationIsStoredAndDeliveredSeparately` still passes: both 5paisa calls are MARKET, which queues, and the second shares no line, so it is its own row.
- `rulesApplyToEveryConnectorAndTradingTriggersDelivery` still passes: the accessibility row has no group flag, so it doesn't queue, while the Zerodha execution does.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/marksy/os/notification/NotificationClassifier.kt app/src/main/java/com/marksy/os/gateway/CaptureGate.kt \
  app/src/main/java/com/marksy/os/connector/ConnectorFramework.kt app/src/main/java/com/marksy/os/data/local/NotificationEventDao.kt \
  app/src/main/java/com/marksy/os/gateway/TradingDeliveryWorker.kt app/src/test/java/com/marksy/os/gateway/CaptureGateTest.kt \
  app/src/test/java/com/marksy/os/gateway/TradingDeliveryRunTest.kt app/src/test/java/com/marksy/os/connector/IngestionPipelineTest.kt \
  app/src/test/java/com/marksy/os/notification/NotificationClassifierTest.kt
git commit -m "Capture: the gate, not a trade-call rule, decides what may leave; holdings alerts stay on the phone

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task B9: Delete the on-device parsers and the brand map

**Files:**
- Delete: `app/src/main/java/com/marksy/os/notification/TradeCallParser.kt`, `notification/CallHorizon.kt`, `market/DailySetups.kt` and `gateway/MarksyTipPayload.kt`
- Modify: `app/src/main/java/com/marksy/os/intelligence/EventExpiry.kt` (lines 4, 33–35, 42–47 and 72–80)
- Modify: `app/src/main/java/com/marksy/os/data/NotificationRepository.kt` (`retireExpired`, line 77)
- Modify: `app/src/main/java/com/marksy/os/ui/StockLinks.kt` (lines 32 and 45–50)
- Modify: `app/src/main/java/com/marksy/os/ui/SetupsView.kt` (whole file)
- Modify: `app/src/main/java/com/marksy/os/ui/TradingIntelligenceScreen.kt` (`setupReports`)
- Modify: `app/src/main/java/com/marksy/os/MainActivity.kt` (the `setupReports = …` argument)
- Modify: `app/src/main/java/com/marksy/os/notification/SourceRegistry.kt` (whole file)
- Test: delete `app/src/test/java/com/marksy/os/notification/TradeCallParserTest.kt`, `notification/CallHorizonTest.kt` and `market/DailySetupsTest.kt`. Modify `intelligence/EventExpiryTest.kt`, `notification/SourceRegistryTest.kt` and `notification/NotificationClassifierTest.kt`.

**Interfaces:**
- Removes `TradeCallParser`, `CallHorizon`, `DailySetups`, `SetupReport`, `MarksyTipPayloadBuilder` (and its `symbolOf`), `EventExpiry.superseded`, `SourceRegistry.knownSources`, `isKnownSource`, `isTradingSource` and `displayName(packageName)`.
- `SetupsView(repository: MarketIntelligenceRepository?, bottomPadding: Dp, onOpenStock: (String) -> Unit)`.
- `TradingIntelligenceScreen` loses `setupReports`.
- `SourceRegistry` keeps only `isWhatsApp` and `displayName(context, packageName)`.

- [ ] **Step 1: Write the failing check**

```bash
grep -rnE "TradeCallParser|CallHorizon|DailySetups|SetupReport|MarksyTipPayloadBuilder|symbolOf|superseded\(|isTradingSource|knownSources|isKnownSource" app/src || echo "no on-device parsing left"
```

Expected now: matches in `TradeCallParser.kt`, `CallHorizon.kt`, `DailySetups.kt`, `MarksyTipPayload.kt`, `EventExpiry.kt`, `NotificationRepository.kt`, `StockLinks.kt`, `SetupsView.kt`, `TradingIntelligenceScreen.kt`, `MainActivity.kt`, `SourceRegistry.kt` and their tests.

- [ ] **Step 2: Delete the parsers and their tests**

```bash
git rm app/src/main/java/com/marksy/os/notification/TradeCallParser.kt app/src/main/java/com/marksy/os/notification/CallHorizon.kt \
  app/src/main/java/com/marksy/os/market/DailySetups.kt app/src/main/java/com/marksy/os/gateway/MarksyTipPayload.kt \
  app/src/test/java/com/marksy/os/notification/TradeCallParserTest.kt app/src/test/java/com/marksy/os/notification/CallHorizonTest.kt \
  app/src/test/java/com/marksy/os/market/DailySetupsTest.kt
```

- [ ] **Step 3: Rewire what used them**

In `intelligence/EventExpiry.kt`:
- Delete `import com.marksy.os.notification.TradeCallParser`.
- Replace

```kotlin
            "TRADING" -> TradeCallParser.parse(event.title, event.body)
                ?.let { Expiry(callExpiry(it.horizon, it.horizonSessions, posted), "${REASON_PREFIX}call horizon passed") }
                ?: Expiry(posted + 7 * DAY, "${REASON_PREFIX}trading update older than a week")
```

  with

```kotlin
            "TRADING" -> Expiry(posted + 7 * DAY, "${REASON_PREFIX}trading update older than a week")
```

- Delete `fun superseded(...)` with its KDoc (`/** Older open calls on a symbol that has a newer call, … */`), and `private fun callExpiry(...)` with its KDoc (`/** The close of the [sessions]-th session after the posting one … */`).

In `data/NotificationRepository.kt`, in `retireExpired`, replace `        val reasons = com.marksy.os.intelligence.EventExpiry.superseded(open.filter { it.category == "TRADING" }).toMutableMap()` with:

```kotlin
        val reasons = mutableMapOf<Long, String>()
```

In `ui/StockLinks.kt`:
- Delete `import com.marksy.os.notification.TradeCallParser`.
- Replace

```kotlin
/** Stocks a market or trading event is about: the parsed call's symbol first, then tickers in its text. */
fun stocksIn(event: NotificationEventEntity, isSymbol: (String) -> Boolean): List<String> {
    if (event.category !in STOCK_CATEGORIES && !event.isTrading) return emptyList()
    val call = TradeCallParser.parse(event.title, event.body)?.symbol?.takeIf(isSymbol)
    return (listOfNotNull(call) + StockMentions.find("${event.title} ${event.body}", isSymbol)).distinct().take(3)
}
```

  with

```kotlin
/** Stocks a market or trading event, or one Marksy recorded as a tip, is about: the tickers named in its text. */
fun stocksIn(event: NotificationEventEntity, isSymbol: (String) -> Boolean): List<String> {
    if (event.category !in STOCK_CATEGORIES && !event.isTrading && event.marksyTipId == null) return emptyList()
    return StockMentions.find("${event.title} ${event.body}", isSymbol)
}
```

Replace `app/src/main/java/com/marksy/os/ui/SetupsView.kt` with the file below. The `LazyColumn` body is the old code minus the report rows.

```kotlin
package com.marksy.os.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.marksy.os.EmptyState
import com.marksy.os.market.ActivePredictionDto
import com.marksy.os.market.MarketDataState
import com.marksy.os.market.MarketIntelligenceRepository
import com.marksy.os.market.PicksBasis
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Today's setups: Marksy's live calls, best first. Reports' setups are parsed by the server and land in My tips. */
@Composable
internal fun SetupsView(repository: MarketIntelligenceRepository?, bottomPadding: Dp, onOpenStock: (String) -> Unit) {
    val marksy by produceState<MarketDataState<List<ActivePredictionDto>>>(MarketDataState.Loading, repository) {
        value = repository?.activePredictions()?.map { it.items } ?: MarketDataState.Unavailable
    }
    val all = (marksy as? MarketDataState.Loaded)?.value.orEmpty()
    val live = remember(all) {
        all.filter { it.lifecycleState !in INVALIDATED }
            .sortedWith(compareByDescending<ActivePredictionDto> { it.isActionableNow }.thenByDescending { it.compositeOpportunityScore ?: -1.0 })
    }
    val quotes = rememberUpstoxQuotes(remember(live) { live.map { it.symbol }.distinct() })
    val scan by MarketIntelligenceRepository.latestScan.collectAsState()

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 18.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(top = 10.dp, bottom = bottomPadding)
    ) {
        when (val m = marksy) {
            is MarketDataState.Loading -> item { MarksyLoader("Loading Marksy setups...") }
            is MarketDataState.Error -> item { EmptyState("Marksy setups unavailable", m.message) }
            is MarketDataState.Unavailable -> item { EmptyState("Marksy is not connected", "Sign in to your Marksy account in More.") }
            else -> if (live.isEmpty()) item {
                EmptyState(
                    "No live Marksy setups right now",
                    if (all.isNotEmpty()) "All ${all.size} open calls are invalidated. See Predictions for their history."
                    else "Marksy's new calls appear here after each market close." + scan?.let(PicksBasis::label)?.let { " Last scan: $it." }.orEmpty()
                )
            }
        }
        items(live, key = { "m-${it.predictionId}" }) { p ->
            val basis = PicksBasis.label(p) ?: p.lastPriceAt?.let { "as of ${asOf(it)}" }
            OpenCallRow(p, quotes[p.symbol]?.lastPrice, note = "Marksy${basis?.let { " · $it" }.orEmpty()}") { onOpenStock(p.symbol) }
        }
    }
}

private val TIME = DateTimeFormatter.ofPattern("d MMM, HH:mm", Locale.getDefault())
private fun asOf(iso: String) = runCatching { OffsetDateTime.parse(iso).atZoneSameInstant(ZoneId.systemDefault()).format(TIME) }.getOrDefault(iso.take(16).replace('T', ' '))
```

In `ui/TradingIntelligenceScreen.kt`:
- Delete the parameter line `    setupReports: List<SetupReport> = emptyList(),`.
- Replace `SetupsView(marketRepository, setupReports, OneHandListBottomPadding, onOpenStock)` with `SetupsView(marketRepository, OneHandListBottomPadding, onOpenStock)`.

In `MainActivity.kt`, in the `TradingIntelligenceScreen(...)` call, delete:

```kotlin
                        setupReports = remember(inboxEvents) {
                            inboxEvents.filter { "marksy-tips/" in it.body }.sortedByDescending { it.postedAt }
                                .mapNotNull { e -> com.marksy.os.market.DailySetups.parse(e.body)?.takeIf { it.setups.isNotEmpty() }?.let { com.marksy.os.ui.SetupReport(e.id, e.postedAt, it) } }
                                .take(3)
                        },
```

Replace `app/src/main/java/com/marksy/os/notification/SourceRegistry.kt` with:

```kotlin
package com.marksy.os.notification

import android.content.Context

/** Source names for captured rows: the installed app's own label. Channel names are the server's (spec §4). */
object SourceRegistry {
    /** True for WhatsApp variants supported explicitly by V1. */
    fun isWhatsApp(packageName: String): Boolean =
        packageName.trim().lowercase() in setOf("com.whatsapp", "com.whatsapp.w4b")

    fun displayName(context: Context, packageName: String): String {
        val normalized = packageName.trim().lowercase()
        return runCatching {
            val applicationInfo = context.packageManager.getApplicationInfo(normalized, 0)
            context.packageManager.getApplicationLabel(applicationInfo).toString().trim()
        }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: normalized.substringAfterLast('.').ifBlank { normalized }
    }
}
```

- [ ] **Step 4: Remove the dead tests**

- In `intelligence/EventExpiryTest.kt`, delete `callExpiresWithItsHorizon` and `newerCallOnTheSameSymbolRetiresTheOlderOne`; their subjects are deleted.
- In `notification/SourceRegistryTest.kt`, delete `tradingPackagesAreRecognizedAndUnknownPackagesRejected`.
- In `notification/NotificationClassifierTest.kt`, delete `sourceRegistryNamesWhatsappBusinessExplicitly`.

- [ ] **Step 5: Run the check and the tests**

Run the Step 1 grep. Expected: it prints `no on-device parsing left`.

Then run the unit-test command with `--tests 'com.marksy.os.intelligence.*' --tests 'com.marksy.os.notification.*' --tests 'com.marksy.os.ui.TradingIntelligenceScreenTest' --tests 'com.marksy.os.connector.*'`.
Expected: all PASS.

- [ ] **Step 6: Commit**

```bash
git add -A app/src/main/java/com/marksy/os app/src/test/java/com/marksy/os
git status
git commit -m "Tip ledger: delete the on-device call parser, horizon rules, daily setups and brand map; the server parses

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

Check that `git status` shows only this task's files.

---

### Task B10: Unit suite, build, PR, merge

**Files:** none new.

- [ ] **Step 1: Prove every deleted class is gone**

```bash
cd /c/AIAgent/marksy-os-phase4b
grep -rnE "TradeCallParser|CallHorizon|DailySetups|SetupReport|MarksyTipPayloadBuilder|symbolOf|RatingEngine|RatingCalibration|RatingCalibrator|StockRatingInputs|RatingSource|MarksyRatingCard|MarksyCalls|MarksyCallView|MarksyCallCard|isTradeCall|callChannels|isTradingSource|knownSources|isKnownSource|findPendingTrading|NOT_TRADING|com\.marksy\.os\.rating" app/src || echo "no references left"
```

Expected: `no references left`.

- [ ] **Step 2: Run the whole unit-test suite**

The change touches the shared capture path and several screens, so run the full JVM suite. It is still never `connectedAndroidTest`.

```bash
export PATH="$(echo "$PATH" | tr ':' '\n' | grep -vE '"|%' | paste -sd:)"
./gradlew --stop
./gradlew --no-daemon :app:testDebugUnitTest
```

Expected: BUILD SUCCESSFUL. If a test outside this plan's files fails, run the same test on `origin/main` in a scratch checkout. If it fails there too, note it in the PR; otherwise fix it.

- [ ] **Step 3: Build the APK**

```bash
./gradlew --no-daemon :app:assembleDebug
```

Expected: BUILD SUCCESSFUL and `app/build/outputs/apk/debug/app-debug.apk`. **Do not install it.** The controller installs it on the phone in the morning.

- [ ] **Step 4: Push and open the PR**

```bash
git push -u origin feat/tip-ledger-screens
gh pr create --title "Tip ledger Phase 4b: ledger screens; delete on-device parsing and rating" --body "$(cat <<'EOF'
marksy-os becomes a read-only client of the tip ledger (spec §10). Requires marksy-api Phase 3b in production and
marksy-os Phase 4a (B) merged (both checked before this branch was cut).

- Stock page: a calls box with a group per Marksy engine and per external channel, each with its lifetime record;
  states and returns come from the ledger tip, so a withdrawn losing Marksy call shows as a failed exit, not "Invalidated"
- Tip detail: terms, state, returns and the session-by-session progress, provisional rows flagged
- Trading: My tips (replaces the on-device Calls tab) with progress, record and "received via"; Scorecards for
  channels, callers and Marksy engines with the section 8.3 period, custom-range and horizon filters
- Deleted: TradeCallParser, CallHorizon, DailySetups, MarksyTipPayloadBuilder, RatingEngine, RatingCalibration,
  the rating card and calibrator, MarksyCalls, the classifier's trade-call rule and SourceRegistry's brand map
- Capture: the gate decides what may leave by medium and category, bounded by the capture list and allow-list;
  holdings/account alerts, own orders, OTPs, banking and 1:1 chats stay on the phone; a chat message added after its
  row was sent is its own receipt
- The rating card is removed rather than served: the Rating engine's calls show in the calls box from the ledger

Tests: full JVM unit suite (`testDebugUnitTest`), `assembleDebug`. Not installed on a device.

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```

- [ ] **Step 5: Merge and clean up**

```bash
gh pr merge --merge --delete-branch
test -z "$(git -C /c/AIAgent/marksy-os-phase4b status --porcelain)" && cd /c/AIAgent/marksy-os && git worktree remove --force ../marksy-os-phase4b && git fetch origin
```

`--force` is only needed for the ignored `local.properties` and the build outputs. The `status --porcelain` check makes sure nothing uncommitted is lost. Leave `C:\AIAgent\marksy-os` on its current branch.

## Summary

After this phase the phone parses and scores nothing.
- The stock page reads every call from the ledger: Marksy's by engine, and external ones by channel with their caller, each with its universal record.
- The Trading tab shows the customer's own tips as the server tracks them, and scorecards under the same §8.3 filters the admin and dashboard use.
- The deleted parser used to decide what was queued. That job is now a capture-gate rule the server capture list bounds, so what leaves the phone is still only the capture set.
- Own orders, holdings and account alerts, OTPs, banking and 1:1 chats never leave the phone.
- There was no backend change: the rating engine's calls are its ledger tips, shown in the calls box.

## Validation

- Task B1: the gates in Step 1, and `LedgerModelsTest`, `InstrumentModelsTest`
- Task B2: `com.marksy.os.market.*` (`ScorecardQueryTest`, and the unchanged repository and client tests)
- Task B3: `LedgerCallsTest`
- Task B4: the rating grep, and `StockDetailScreenTest`
- Task B5: `StockDetailScreenTest`, `com.marksy.os.market.*`, and the call-card grep
- Task B6: `AskMarksy*`, `TradingIntelligenceScreenTest`
- Task B7: `AskMarksy*`, `TradingIntelligenceScreenTest`, `com.marksy.os.market.*`
- Task B8: `com.marksy.os.gateway.*`, `com.marksy.os.connector.*`, `com.marksy.os.notification.*`, `EventIntelligencePipelineTest`, and the pre-filter grep
- Task B9: the parser grep, and `com.marksy.os.intelligence.*`, `com.marksy.os.notification.*`, `TradingIntelligenceScreenTest`, `com.marksy.os.connector.*`
- Task B10: the zero-reference grep, the full `:app:testDebugUnitTest`, `:app:assembleDebug`
