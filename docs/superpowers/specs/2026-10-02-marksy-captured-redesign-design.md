# Marksy Trading > Captured redesign

Status: approved direction 2026-10-02. Mockup: https://claude.ai/artifact/TBcR6ixdTMUN95QBDJevrQ. Sibling: the Inbox redesign (same lanes, stacks of 2 plus expander, dashed fold).

## Goal

Captured answers one question: did my phone catch it, and did Marksy get it. Calls and My tips already answer what is being called and how it is doing. The page becomes a capture log in three lanes with a rejected-orders fold, a retry path, and each tip's levels and status visible.

## Problems verified in code

1. Flat list, newest first, cap 50 (TradingIntelligenceScreen.kt CapturedList; NotificationRepository.observeTrading).
2. Every tip headline reads "Trading event detected" (TradingInsight.tradingHeadline), and the source is the app name.
3. Entry, target, stop loss, level state are parsed but never drawn (TradingInsight.kt vs CapturedInsightCard).
4. Delivery state is 10sp grey text; FAILED looks like DELIVERED.
5. No retry: FAILED is final (TradingDeliveryPolicy.retryState) and nothing requeues it. A PENDING row stuck behind background limits shows no sign.
6. A call forwarded to two groups shows twice (the DAO duplicate matching leaves trading rows out).
7. Retired and old rows vanish: observeTrading reads NEW/ACTIVE only, and EventExpiry retires TRADING at 7 days.
8. On Captured the filter button is an X that jumps to the first view (OneHandControls filterActive), and there is no Captured filter.

## Design

### Lanes (one LazyColumn, dividers not header rows)

- **Needs you** (red dot). Active rows with `deliveryState == FAILED` (chip "Not sent", button **Retry**) and rows `PENDING` for more than 15 minutes (chip "Waiting N min", buttons **Send now** and **Allow background**). Sorted newest first. Rejected orders are not here (decision 3).
- **Today** (emerald dot). One stack per source (default) or per symbol. Rows are tips.
- **Earlier** (dashed fold, collapsed). Anything older than the Today boundary, and retired rows (RESOLVED, with the retirement reason shown).
- **Rejected orders** (dashed fold at the very bottom, collapsed). Own broker orders whose title says rejected. The Inbox already surfaces them as needs-you.

A lane with nothing in it is not drawn. Empty page: "Nothing captured yet" with the wording widened to group, SMS and research-app calls.

### Decisions (user, 2026-10-02)

1. Forwarded copies fold into one row showing "+N".
2. "Today" ends at the 3:30 pm IST close. Boundary = the most recent weekday 15:30 IST at or before now; rows posted before it go to Earlier. At 10:42 Thursday that is Wednesday 15:30 (overnight messages are still today's session). After 15:30 the whole session moves to Earlier. Exchange holidays are not modelled.
3. Rejected orders sit in the bottom fold, not in Needs you.

### Decisions added (best recommendation)

- **Fold key**: the message text lower-cased with everything but letters and digits removed, at least 20 characters, on rows of a different source or group, within 24 h of the first copy. The first (oldest) row stays; "+N" counts the copies and the detail dialog names where else it appeared. Each copy stays its own ledger tip in Marksy (central-ledger rule); folding is display only. Own orders and rejected rows never fold.
- **Stack key (Source)**: chat media (WhatsApp, Telegram): the chat title; SMS and apps: `sourceName`. Stack by Symbol: the symbol, else "Other". Rows newest first, stacks by latest row. Top 2 show, "N more" expands in place.
- **Row headline**: side and symbol ("BUY TATAPOWER") when both are known; otherwise the first line of the message. Side comes from Marksy's `recommendation`, else a buy/sell word in the text. The symbol is found by the existing `StockMentions` check.
- **Levels line**: Entry, Target, SL from Marksy's response, only when present, plus Marksy's `levelState` as a status pill (green for target, red for stop loss, grey otherwise). Own orders show no levels.
- **Status pill text** comes from `levelState` as received, humanised (underscores to spaces). The app never computes it.
- **Delivery state** is a coloured pill beside the time, not grey text: Sent (green), Sending / Waiting (amber), Not sent (red), "On this phone" (muted, for own orders and anything the gate kept; not a fault).
- **Retry** (app side only): `requeueFailed(id)` sets FAILED to PENDING, attempts to 0 and clears the note, then `TradingDeliveryScheduler.requestImmediateDelivery`. Same worker, same gateway call. **Send now** just requests immediate delivery. **Allow background** opens the system battery-optimisation settings (no new permission).
- **Failure reason**: a new nullable column `deliveryNote` (Room 7 to 8, additive `ALTER TABLE`). The worker writes a fixed code on terminal failure (`rejected` for a gateway terminal error, `invalid` for a bad request) and the gate's keep reason on kept rows. Never an exception message (it could carry notification content). Shown in the detail dialog only.
- **Capture health**: the header note on this tab reads "Captured · 14 today · 2 need you". Tapping it opens a MarksyDialog: captured today, sent, waiting, not sent, kept on this phone, last delivery time. No header rows or chrome.
- **Filter button** (bottom-right stack): opens a panel with the Trading views, **Show** (All, Tips, Needs you) and **Stack by** (Source, Symbol). The button stays a filter icon; it shows X only while a Show filter other than All is on, and then clears that Show filter, not the tab. `OneHandControls` gets an optional extra-sections slot; other pages are unchanged.
- **Tap a row**: a MarksyDialog with levels, Marksy status, the message, what left the phone and when, and the failure or keep note.
- **Data window**: a new DAO query `observeCaptured` (isTrading, not archived, NEW/ACTIVE/RESOLVED, newest 300). `observeTrading` stays for other callers.
- **Privacy**: no OTP or PIN is shown, copied or stored by this page. Captured rows are call and order candidates, and the gate already refuses code-bearing text. Test data holds no codes. No change to NotificationClassifier or IngestionPipeline (the Inbox branch owns those). DAO changes are additive.
- **Tab names** stay Setups / Predictions / My tips / Captured.

### Needs data (not available from Marksy today)

- A ledger status per tip with the time it changed (open, entry not hit, target hit, stop loss hit, expired, revised). The app shows `levelState` as received.
- Entry ranges and a second target (the response has one entry and one target).
- Marksy's own view on a stock while the publish gate (SPG-001) holds all calls: the detail says Marksy has no call on the stock.

## Model API (ui/CapturedModel.kt, pure)

`CapturedModel.lanes(events, now, stackBy, show, symbolOf, zone)` returns `CapturedLanes(needsYou, today, earlier, rejected, health)`. Rows are `CapturedRow(event, levels: TipLevels?, headline, folded, alsoIn)`; `todayBoundary(now)` is public for tests.

## Testing (logic only, no layout tests)

- `CapturedModelTest`: lane assignment (failed, waiting over and under 15 min, rejected, retired, old); the 15:30 IST boundary before and after close and on a weekend; forwarded-copy folding (different group folds; same source, over 24 h and own orders do not); parsed levels and headline; Stack by Symbol.
- `CapturedRetryTest` (Robolectric in-memory Room, like RetirementTest): `requeueFailed` moves FAILED to PENDING with attempts 0 and leaves other states alone; the row is then in `findPendingCapture`; `observeCaptured` includes RESOLVED rows.
- Visual: offscreen Robolectric render at w400dp and w360dp, scratch test kept out of git.

## Acceptance

1. First screen under the header is content; lanes as above; no duplicate forwarded rows.
2. Retry on a FAILED row requeues it and requests delivery.
3. Retired and old calls are visible under Earlier.
4. The filter button opens the panel and no longer jumps to the first view.
5. `./gradlew testDebugUnitTest` and `assembleDebug` pass.
