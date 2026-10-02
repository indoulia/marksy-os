# Marksy Inbox redesign

Status: approved 2026-10-02 (user: "use your best recommendation"). Mockup: https://claude.ai/artifact/5vU4pMzwcVsSQvoUu8y1MG

## Goal

The Inbox is the app's main page. Make it obvious at a glance what needs the user, merge items from the same source, keep the top 1–2 per source visible with the rest behind an expander, and stop stale items from crowding new ones.

## Problems being fixed

1. Stale items never leave. `EventExpiry.of` (EventExpiry.kt:27) retires only OTP, PROMOTIONS, TRADING, MARKET and DELIVERY. Messages, email, banking, payments, bills, work and OTHER stay for the full retention period, as loud as new items, even after they are read.
2. Priority is invisible. `SmartInboxScreen` shows buckets only as a 12sp grey label (SmartInboxScreen.kt:100-115) and every card looks the same.
3. Tags follow the app, not the item. `resolveSourceStyle` (SmartInboxScreen.kt:345) gives every Gmail a yellow "Important" star and every WhatsApp a "Grouped" tag, which contradicts the bucket.
4. One source, many cards. Threads split by transaction ref, subject or ticker (EventNormalizer.kt:42, EventIntelligence.legacyThreadKey), so 3 HDFC alerts are 3 cards and 8 tickers are 8 cards.
5. Cards are tall: bodies run to 5 lines (SmartInboxScreen.kt:313).
6. The RESOLVED bucket never shows anything: the inbox reads `observeActive()` (NEW/ACTIVE only).

## Design

### Three lanes

The screen is one `LazyColumn` with three lanes. A lane with nothing in it is not drawn. Each lane is introduced by a small uppercase label with a coloured dot, used as an in-list divider, never as a page header row.

**Needs you** (dot: RedUrgent). A thread is here when any of these is true:
- `bucketFor` puts it in `NEEDS_ACTION` (the existing reasons, plus the new ones below) or `PRIORITY` (attention ≥ 90);
- any `PersonalLearning.subjectsOf(thread.latest)` has `profile.of(type, key)?.override == ALWAYS_IMPORTANT`.

Rules for this lane:
- Threads are sorted by attention, then newest. The top 3 show, and the rest sit behind a "▾ N more need you · names" row.
- Each thread is a full card. It has a reason chip, source line, title, a 2-line body, and inline **Done** (resolve) and **Snooze 1h** buttons.
- Chip colour follows `Urgency`: FAILURE uses RedUrgent on BadgeUrgentBg, DUE uses YellowImportant on BadgeImportantBg, FLAGGED uses PrimaryEmerald on BadgeTradingBg. The card border mixes the urgency colour into BorderGlow, with no side stripe.

**New** (dot: PrimaryEmerald). A non-needs thread is here when it was posted less than 24 h ago and either it is unread or it was read during this visit to the Inbox. Threads are grouped into **source stacks**:
- Stack key: for SMS packages (`CaptureMedium.of(pkg) == CaptureMedium.SMS`, exposed as `SmartInboxModel.isSms`), package + normalised sender (`latest.title`). Otherwise, the source package. One WhatsApp card holds every chat, one Gmail card holds every email, and SMS splits into "HDFC Bank", "Airtel" and so on.
- Rows are threads, sorted by attention then newest, so a busy group chat cannot push a high-attention chat out of view. Stacks are sorted by their top attention, then newest.
- The stack header shows the source icon (same icon and colour as today's `resolveSourceStyle`, without the pill), the source name ("HDFC Bank" with an "SMS" suffix for SMS), "N new", the latest time and a mark-all-read icon button.
- The top 2 rows show. The first row's body gets 2 lines; other rows get 1. A row's headline is whatever the stack label does not already say: the sender for app stacks, the subject or body for SMS stacks.
- With more than 2 rows, a "▾ N more · names" row expands the stack in place ("▴ Show less" collapses it). A collapsed stack with hidden rows draws a short peek edge under the card.

**Earlier** (dot: TextMuted). Every other non-needs thread (seen, or posted 24 h or more ago) sits behind one dashed fold row: "Earlier · N items from M sources / Seen, or older than a day". It is collapsed by default. Expanded, it shows one compact line per source stack (icon, name · count, latest preview, time). Tapping a line expands its rows. A **Clear all N** button at the end archives every Earlier thread through the existing `archiveThreadWithUndo`.

**Footer**: "N snoozed · History". "History" opens the existing timeline (`showTimeline = true`).

### Read-in-place

Tapping a row marks its thread seen, as today. The thread stays in its lane until the user leaves the Inbox tab. `SmartInboxScreen` keeps a `remember`ed set of thread keys seen during this visit and passes it to the model. Leaving the tab disposes it, so the next visit regroups.

### New action reasons in `bucketFor`

`bucketFor` keeps its signature and verbatim "why" strings. It now also produces a `NeedReason(chip, urgency)`. Existing reasons map as follows, and two new ones are added:

| Condition | Chip | Urgency |
|---|---|---|
| trading + `deliveryState == FAILED` | "Retry needed" | FAILURE |
| PAYMENTS/BANKING + failure term | "Payment failed" | FAILURE |
| **new:** TRADING + "rejected" | "Order rejected" | FAILURE |
| **new:** DELIVERY + delivery-failure term ("failed", "unsuccessful", "undelivered", "could not be delivered") | "Delivery failed" | FAILURE |
| BILLS | "Bill due" | DUE |
| REMINDERS | "Reminder" | DUE |
| attention ≥ 90, no action | "High attention" | FLAGGED |
| Always important override | "Always important" | FLAGGED |

An action reason always wins over a FLAGGED one. The old "Fresh one-time code" reason is removed: one-time codes are no longer stored (below).

### One-time codes are never captured

User rule (2026-10-02): Marksy must not capture or store OTPs, PINs or similar codes, and must keep anything it is not sure about. `NotificationClassifier.isOneTimeCode(title, body)` is true only when the text names a code (OTP, one-time password, verification/security/login code, passcode, password, PIN, TPIN, MPIN) and carries a standalone 4–8 digit number that is no amount, balance, account or card mask (XX1234), date, time, phone number, order or reference id, or call level. `IngestionPipeline` drops such a capture before storage, including a re-post that would update an existing row. `classify` files a message as OTP only when it is such a code, so an alert that merely says "never share your OTP" keeps its real category. Classifier version 13 re-files stored rows once and deletes stored codes (non-trading rows).

### Retirement of seen, low-attention items

There is a new rule next to the expiry rules: a thread leaves the active views once it is
- NEW/ACTIVE, not archived, `isRead`, not `kept`, with no `remindAt`;
- posted 48 h or more ago;
- `max(priority, importanceScore) < 60`;
- not BILLS or REMINDERS, and not a trading row with `deliveryState == FAILED`.

It is resolved with reason `"Retired: seen and older than 2 days"`, using the existing `EventExpiry.REASON_PREFIX` so learning still treats unseen retirements correctly. History (`observeHistory`) keeps these rows. The decision is a pure function `EventExpiry.staleSeen(event): Expiry?` (returns the retire time; the caller compares it with now, like `EventExpiry.of`). `NotificationRepository.retireExpired` applies it to a new DAO candidate query `findSeenOpen()` (NEW/ACTIVE, archived = 0, isRead = 1, kept = 0, remindAt IS NULL). Both existing callers, RetentionWorker and MainActivity start-up, pick it up unchanged.

### Header note and tab badge

- `titleNote` for `selectedTab == 1` becomes "N need you · M new", or just "M new" when nothing needs the user, or the filter label when a filter is on. MainActivity computes `SmartInboxModel.lanes(...).summary` (`InboxSummary(needsYou, newUnread)`) from the same unfiltered events, so the note and badge are right even before the Inbox tab is opened. With a filter on, the note is just the filter label.
- The Inbox `NavigationBarItem` gets a `BadgedBox` showing the needs-you count (RedUrgent) when it is above 0.

### Kept as is

- Bottom-right `OneHandControls` filter and search with the same `Filter` entries.
- Tap opens `onEventSelected(thread.latest)` (the event detail dialog). Long-press opens `ThreadActionsDialog` with the same actions.
- Swipe actions (`SwipeActionsRow`) wrap each needs-you card and each stack row, acting on that one thread.
- Duplicate folding, the snooze filter, query matching, `Filter`, `Bucket`, and `bucketFor`'s `(Bucket, String)` result used by DailyBriefing.

### Removed

- The source-based pill and its label from cards.
- The per-bucket section headers and "N shown" label.
- The five-line body.

## Model API

`SmartInboxModel.inbox(...)` stays for any other caller. A new entry point:

```kotlin
fun lanes(
    events: List<NotificationEventEntity>,
    filter: Filter = Filter.ALL,
    query: String = "",
    nowMillis: Long = System.currentTimeMillis(),
    profile: PersonalLearning.Profile = PersonalLearning.Profile.EMPTY,
    seenThisVisit: Set<String> = emptySet(),   // thread keys
    zone: ZoneId = ZoneId.systemDefault()
): Lanes

data class Lanes(
    val needsYou: List<NeedThread>,          // sorted
    val fresh: List<SourceStack>,            // "New"
    val earlier: List<SourceStack>,
    val snoozedCount: Int
) { val isEmpty: Boolean; val summary: InboxSummary }

data class NeedThread(val thread: InboxThread, val reason: NeedReason)
data class NeedReason(val chip: String, val urgency: Urgency)
enum class Urgency { FAILURE, DUE, FLAGGED }
data class SourceStack(val key: String, val label: String, val isSms: Boolean, val threads: List<InboxThread>) {
    val unread: Int; val latestAt: Long; val topAttention: Int
}
data class InboxSummary(val needsYou: Int, val newUnread: Int)
```

`lanes` reuses the existing filter, snooze partition, duplicate folding, `buildThread` and `bucketFor`. It adds lane assignment and stacking on top.

## Out of scope

- A per-sender grouping toggle (the mockup had one for comparison; app-level stacking ships).
- A "done today" counter, a separate snoozed list, and any backend or API change.
- Portfolio, IPO and Captured redesigns (separate mockups, separate specs).

## Testing

Unit tests only, under the project's test budget: logic, not layout. Never connectedAndroidTest.

- `SmartInboxModelTest`:
  - Lane assignment covers needs via action, needs via ≥ 90 and needs via an Always-important override.
  - Unread under 24 h goes to New; unread over 24 h goes to Earlier.
  - A row read this visit stays in New; a row read earlier goes to Earlier.
  - Stacking puts WhatsApp chats in one stack and splits SMS by sender.
  - Rows inside a stack rank by attention then recency.
  - The new reasons are covered: order rejected and delivery failed.
- `NotificationClassifierTest`, `IngestionPipelineTest`, `ReclassificationTest`: real-style codes (login OTP, TPIN, delivery PIN, card-txn OTP) are dropped and purged; bank, UPI, tip, statement, shipping and password-change alerts that mention OTP are kept and not filed as OTP.
- `EventExpiryTest`: `staleSeen` retires seen, low-attention items that are 48 h or older. It keeps unread, kept, reminder, BILLS/REMINDERS, failed-delivery and high-attention rows, and anything under 48 h.
- Existing suites stay green: `SmartInboxModelTest`, `EventExpiryTest`, DailyBriefing tests and AdaptiveRankerTest.
- Visual check on the phone at a representative size, next to an existing Marksy screen, before the PR (UI consistency rule).

## Acceptance criteria

1. With sample data like the mockup's, the first screen shows the Needs-you cards with reason chips, then New stacks. No card from the same app or SMS sender appears twice in New.
2. A stack with more than 2 threads shows exactly 2, plus an expander naming the hidden ones. Expanding and collapsing works in place.
3. Earlier is collapsed by default and clears with undo.
4. Seen low-attention items 48 h or older disappear from the Inbox after the next retirement pass and are still in History.
5. The header note and tab badge match the lane counts.
6. All unit tests pass (`./gradlew testDebugUnitTest`) and the debug build assembles.
