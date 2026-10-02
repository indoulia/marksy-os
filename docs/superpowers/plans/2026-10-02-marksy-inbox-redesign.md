# Marksy Inbox Redesign Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the flat, bucket-labelled Inbox with three lanes: Needs you, New (one stack per source, top 2 shown) and a folded Earlier. Also retire seen low-attention items after 2 days.

**Architecture:** All decisions stay pure and deterministic in `SmartInboxModel` (new `lanes()` built on the existing `inbox()` threads) and `EventExpiry` (new `staleSeen`). `NotificationRepository.retireExpired` applies the new rule through one new DAO candidate query. `SmartInboxScreen` is rewritten to render lanes. MainActivity adds a header note and a tab badge.

**Tech Stack:** Kotlin 2.3, Jetpack Compose (BOM 2026.08.00, Material3, material-icons-extended), Room, JUnit4 + Robolectric unit tests.

**Spec:** `docs/superpowers/specs/2026-10-02-marksy-inbox-redesign-design.md`. The interactive mockup is linked from the spec.

## Global Constraints

- Branch `feat/inbox-redesign` (already holds the spec and this plan). One PR to `main`. No stacking.
- Marksy UI only: `MarksyTheme` colours, the existing `Pill` (WatchlistScreen.kt:361) for inline actions, `MarksyDialog` for popups. No stock Material look, no new design system.
- No heading, label or button row at the top of the page. Lane labels are in-list dividers only. Page actions stay on `OneHandControls`.
- Comments: one line max, explaining a non-obvious WHY. Never restate code.
- Tests are for logic only (project test budget). No layout tests. **Never run `connectedAndroidTest`**: it wipes the user's phone data.
- Before running Gradle tests in Git Bash, repair PATH (a corrupted user PATH breaks forked test JVMs):
  `export PATH="$(echo "$PATH" | tr ':' '\n' | grep -vE '"|%' | paste -sd:)"` then use `./gradlew --no-daemon`.
- Android ICU regex: escape literal braces in any regex; the ones in this plan need none.
- Keep `SmartInboxModel.inbox()`, `Bucket`, `Filter`, `isFromSource` and `bucketFor`'s `(Bucket, String)` result working. DailyBriefing, HomeCategoryStats, DailyDigestScreen, InsightsScreen and MainActivity use them.
- Commit messages are concise and end with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

## Review Focus

1. **Thread read during this visit:** it must not jump from New to Earlier while the user is still on the tab. Pinned by `readThisVisitStaysInNewUntilTheNextVisit` (Task 2).
2. **Expired OTP:** it must leave Needs you the minute its stated validity ends, even before the retirement worker runs. Pinned by `otpNeedsYouUntilItsStatedValidityEnds` (Task 1).
3. **Seen BILLS, REMINDERS, kept, reminder-set, failed-delivery or high-attention rows:** these must never auto-retire. Pinned by `unreadKeptRemindedDueFailedAndImportantItemsNeverGoStale` (Task 3).
4. **Retirement pass:** it must not touch unread rows, and History must keep what it retires. Pinned by `seenLowAttentionItemRetiresAfterTwoDays` (Task 3, Room in-memory).
5. **SMS from different senders in one SMS app:** these must not merge into one stack, while WhatsApp chats do merge. Pinned by `oneStackPerAppButSmsSplitsBySender` (Task 2).

---

### Task 1: Need reasons and new action triggers in `SmartInboxModel`

**Files:**
- Modify: `app/src/main/java/com/marksy/os/intelligence/SmartInboxModel.kt` (`bucketFor` at :170-189, constants at :198-204)
- Test: `app/src/test/java/com/marksy/os/intelligence/SmartInboxModelTest.kt`

**Interfaces:**
- Produces: `enum class SmartInboxModel.Urgency { FAILURE, DUE, FLAGGED }`, `data class SmartInboxModel.NeedReason(val chip: String, val urgency: Urgency)`, `internal fun SmartInboxModel.actionFor(open: List<NotificationEventEntity>, nowMillis: Long): Pair<String, NeedReason>?` (why-string, chip), `internal fun SmartInboxModel.otpCode(body: String): String?`. `bucketFor` keeps its signature.

This task changes only `bucketFor`'s internals, so the tests go through `inbox()` buckets. Task 2's tests cover the chips.

- [ ] **Step 1: Write the failing tests.** Add to `SmartInboxModelTest` (inside the class, next to the existing `event` helper):

```kotlin
    private val hour = 60 * 60 * 1000L

    private fun msg(
        id: Long, pkg: String, title: String, body: String, postedAt: Long,
        category: String = "MESSAGES", priority: Int = 50, read: Boolean = false
    ) = NotificationEventEntity(
        id = id, sourcePackage = pkg, sourceName = SOURCE_NAMES[pkg] ?: pkg,
        sourceKey = "$pkg-$id", eventFingerprint = "fp-$id", title = title, body = body,
        postedAt = postedAt, category = category, priority = priority, confidence = 0.95f,
        isTrading = category == "TRADING", isRead = read, lifecycleState = if (read) "ACTIVE" else "NEW"
    )

    private fun bucketsOf(vararg events: NotificationEventEntity, at: Long = now) =
        SmartInboxModel.inbox(events.toList(), nowMillis = at).sections.flatMap { (b, ts) -> ts.map { it.latest.id to b } }.toMap()

    @Test
    fun rejectedOrdersAndFailedDeliveriesNeedAction() {
        val buckets = bucketsOf(
            msg(1, "com.zerodha.kite3", "Order rejected", "BUY 10 INFY rejected: insufficient margin", now - hour, category = "TRADING"),
            msg(2, "com.delhivery", "Delivery attempt failed", "AWB 1490: you were not available", now - hour, category = "DELIVERY"),
            msg(3, "com.delhivery", "Delivered", "Your parcel was delivered", now - hour, category = "DELIVERY")
        )
        assertEquals(SmartInboxModel.Bucket.NEEDS_ACTION, buckets[1L])
        assertEquals(SmartInboxModel.Bucket.NEEDS_ACTION, buckets[2L])
        assertTrue(buckets[3L] != SmartInboxModel.Bucket.NEEDS_ACTION)
    }

    @Test
    fun otpNeedsActionUntilItsStatedValidityEnds() {
        val otp = msg(1, SMS, "SBI", "482913 is your OTP for login. Valid for 30 minutes.", now - 20 * 60_000, category = "OTP")
        assertEquals(SmartInboxModel.Bucket.NEEDS_ACTION, bucketsOf(otp)[1L])
        assertTrue(bucketsOf(otp, at = now + 11 * 60_000)[1L] != SmartInboxModel.Bucket.NEEDS_ACTION)
    }
```

And add at the bottom of the class:

```kotlin
    companion object {
        const val WA = "com.whatsapp"
        const val SMS = "com.google.android.apps.messaging"
        val SOURCE_NAMES = mapOf(WA to "WhatsApp", SMS to "Messages")
    }
```

- [ ] **Step 2: Run the tests and confirm they fail.**

Run: `./gradlew --no-daemon :app:testDebugUnitTest --tests "com.marksy.os.intelligence.SmartInboxModelTest"`
Expected: FAIL. The rejected order and the failed delivery are not NEEDS_ACTION. The OTP is NEEDS_ACTION only for 10 minutes after posting, so at 20 minutes it is not.

- [ ] **Step 3: Implement.** Replace `bucketFor` and the constants block in `SmartInboxModel.kt` with:

```kotlin
    enum class Urgency { FAILURE, DUE, FLAGGED }
    data class NeedReason(val chip: String, val urgency: Urgency)

    /** Deterministic bucket; the returned string is shown verbatim in "Why am I seeing this?". */
    internal fun bucketFor(events: List<NotificationEventEntity>, attention: Int, nowMillis: Long): Pair<Bucket, String> {
        val open = events.filter { it.lifecycleState != EventLifecycle.State.RESOLVED.name }
        if (open.isEmpty()) return Bucket.RESOLVED to "Resolved"
        val action = actionFor(open, nowMillis)
        return when {
            attention >= PRIORITY_THRESHOLD && action == null -> Bucket.PRIORITY to "Very high attention score ($attention)"
            action != null -> Bucket.NEEDS_ACTION to action.first
            attention >= IMPORTANT_THRESHOLD -> Bucket.IMPORTANT to "Attention score $attention"
            else -> Bucket.INFORMATIONAL to "Informational (attention $attention)"
        }
    }

    /** Why-string plus the short chip for anything the user should act on now; null when nothing is due. */
    internal fun actionFor(open: List<NotificationEventEntity>, nowMillis: Long): Pair<String, NeedReason>? {
        if (open.isEmpty()) return null
        val latest = open.maxBy { it.postedAt }
        val text = "${latest.title} ${latest.body}".lowercase()
        return when {
            open.any { it.isTrading && it.deliveryState == "FAILED" } -> "Marksy delivery failed and needs a retry" to NeedReason("Retry needed", Urgency.FAILURE)
            latest.category == "BILLS" -> "Bill that may need payment" to NeedReason("Bill due", Urgency.DUE)
            latest.category == "REMINDERS" -> "Due or reminder to act on" to NeedReason("Reminder", Urgency.DUE)
            latest.category == "OTP" -> otpMinutesLeft(latest, nowMillis)?.let { "Fresh one-time code" to NeedReason("Code · $it min left", Urgency.DUE) }
            latest.category in setOf("PAYMENTS", "BANKING") && FAILURE_TERMS.any { text.contains(it) } -> "A payment or transaction failed" to NeedReason("Payment failed", Urgency.FAILURE)
            latest.category == "TRADING" && text.contains("rejected") -> "Your order was rejected" to NeedReason("Order rejected", Urgency.FAILURE)
            latest.category == "DELIVERY" && DELIVERY_FAILURE_TERMS.any { text.contains(it) } -> "A delivery attempt failed" to NeedReason("Delivery failed", Urgency.FAILURE)
            else -> null
        }
    }

    // An OTP needs the user for as long as its own text says it is valid (EventExpiry), not a fixed window.
    private fun otpMinutesLeft(event: NotificationEventEntity, nowMillis: Long): Long? {
        val left = (EventExpiry.of(event)?.atMillis ?: return null) - nowMillis
        return if (left > 0) maxOf(1L, left / 60_000L) else null
    }

    internal fun otpCode(body: String): String? = OTP_CODE.find(body)?.value
```

Replace the constants at the bottom of the object with the following. `OTP_ACTION_WINDOW_MS` is removed; `ATTENTION_THRESHOLD` and `RECENT_WINDOW_MS` stay because `section()` still uses them.

```kotlin
    private const val PRIORITY_THRESHOLD = 90
    private const val IMPORTANT_THRESHOLD = 60
    private val FAILURE_TERMS = listOf("failed", "declined", "unsuccessful", "reversed")
    private val DELIVERY_FAILURE_TERMS = listOf("failed", "unsuccessful", "undelivered", "could not be delivered")
    private val OTP_CODE = Regex("\\b\\d{4,8}\\b")

    private const val ATTENTION_THRESHOLD = 70
    private const val RECENT_WINDOW_MS = 2 * 60 * 60 * 1000L
```

- [ ] **Step 4: Run the tests and confirm they pass.**

Run: `./gradlew --no-daemon :app:testDebugUnitTest --tests "com.marksy.os.intelligence.SmartInboxModelTest"`
Expected: PASS, including the existing `bucketsAreDeterministicAndExplained`, whose "Bill that may need payment" string is unchanged.

- [ ] **Step 5: Commit.**

```bash
git add app/src/main/java/com/marksy/os/intelligence/SmartInboxModel.kt app/src/test/java/com/marksy/os/intelligence/SmartInboxModelTest.kt
git commit -m "feat(inbox): need reasons; rejected orders, failed deliveries and valid OTPs need action

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 2: `SmartInboxModel.lanes()`: Needs you / New / Earlier with source stacks

**Files:**
- Modify: `app/src/main/java/com/marksy/os/intelligence/SmartInboxModel.kt` (add after `inbox()`)
- Test: `app/src/test/java/com/marksy/os/intelligence/SmartInboxModelTest.kt`

**Interfaces:**
- Consumes: `actionFor`, `otpCode`, `NeedReason`, `Urgency` (Task 1); existing `inbox()`, `InboxThread`, `PersonalLearning.subjectsOf`, `PersonalLearning.Profile.of`; `com.marksy.os.notification.CaptureMedium.of(packageName)` (TipTextCleaner.kt:16).
- Produces (used by Tasks 4 and 5):

```kotlin
fun SmartInboxModel.lanes(events: List<NotificationEventEntity>, filter: Filter = Filter.ALL, query: String = "", nowMillis: Long = System.currentTimeMillis(), profile: PersonalLearning.Profile = PersonalLearning.Profile.EMPTY, seenThisVisit: Set<String> = emptySet(), zone: ZoneId = ZoneId.systemDefault()): Lanes
data class Lanes(val needsYou: List<NeedThread>, val fresh: List<SourceStack>, val earlier: List<SourceStack>, val snoozedCount: Int) { val isEmpty: Boolean; val summary: InboxSummary }
data class NeedThread(val thread: InboxThread, val reason: NeedReason, val otpCode: String?)
data class SourceStack(val key: String, val label: String, val isSms: Boolean, val threads: List<InboxThread>) { val unread: Int; val latestAt: Long; val topAttention: Int }
data class InboxSummary(val needsYou: Int, val newUnread: Int)
fun SmartInboxModel.isSms(event: NotificationEventEntity): Boolean
```

- [ ] **Step 1: Write the failing tests.** Add to `SmartInboxModelTest`:

```kotlin
    @Test
    fun lanesSeparateNeedsYouNewAndEarlier() {
        val lanes = SmartInboxModel.lanes(
            listOf(
                msg(1, "com.power", "Electricity bill", "Due on 4 Oct", now - hour, category = "BILLS"),
                msg(2, WA, "Mom", "Call me", now - hour),
                msg(3, WA, "College gang", "Plan for Sunday?", now - 30 * hour),
                msg(4, WA, "Papa", "Ok", now - 2 * hour, read = true)
            ),
            nowMillis = now
        )
        assertEquals(listOf(1L), lanes.needsYou.map { it.thread.latest.id })
        assertEquals(SmartInboxModel.NeedReason("Bill due", SmartInboxModel.Urgency.DUE), lanes.needsYou.single().reason)
        assertEquals(listOf(2L), lanes.fresh.flatMap { it.threads }.map { it.latest.id })
        assertEquals(setOf(3L, 4L), lanes.earlier.flatMap { it.threads }.map { it.latest.id }.toSet())
        assertEquals(SmartInboxModel.InboxSummary(needsYou = 1, newUnread = 1), lanes.summary)
    }

    @Test
    fun readThisVisitStaysInNewUntilTheNextVisit() {
        val read = msg(1, WA, "Mom", "Call me", now - hour, read = true)
        val key = EventIntelligence.threadKey(read)
        assertEquals(1, SmartInboxModel.lanes(listOf(read), nowMillis = now, seenThisVisit = setOf(key)).fresh.size)
        assertEquals(1, SmartInboxModel.lanes(listOf(read), nowMillis = now).earlier.size)
    }

    @Test
    fun oneStackPerAppButSmsSplitsBySender() {
        val lanes = SmartInboxModel.lanes(
            listOf(
                msg(1, WA, "Mom", "Call me", now - hour),
                msg(2, WA, "Platform team", "Build is green", now - 2 * hour),
                msg(3, SMS, "HDFC Bank", "Rs 640 debited to SWIGGY", now - hour, category = "PAYMENTS"),
                msg(4, SMS, "Airtel", "Data pack renewed", now - hour, category = "OTHER")
            ),
            nowMillis = now
        )
        assertEquals(mapOf("WhatsApp" to 2, "HDFC Bank" to 1, "Airtel" to 1), lanes.fresh.associate { it.label to it.threads.size })
        assertTrue(lanes.fresh.single { it.label == "HDFC Bank" }.isSms)
    }

    @Test
    fun rowsInAStackRankByAttentionThenRecency() {
        val lanes = SmartInboxModel.lanes(
            listOf(
                msg(1, WA, "Residents", "Water off Saturday", now - 10 * 60_000, priority = 20),
                msg(2, WA, "Mom", "Call me", now - 2 * hour, priority = 70)
            ),
            nowMillis = now
        )
        assertEquals(listOf("Mom", "Residents"), lanes.fresh.single().threads.map { it.latest.title })
    }

    @Test
    fun alwaysImportantSenderIsPinnedToNeedsYou() {
        val e = msg(1, WA, "Mom", "Call me", now - hour, priority = 30)
        val subject = PersonalLearning.subjectsOf(e).first { it.type != PersonalLearning.SubjectType.CATEGORY }
        val profile = PersonalLearning.Profile(
            mapOf(
                (subject.type to subject.key) to PersonalLearning.SubjectProfile(
                    subject, positive = 0, negative = 0, neutral = 0, lastObservedAt = now, adjustment = 25,
                    confidence = 1f, override = PersonalLearning.Preference.ALWAYS_IMPORTANT, reason = "You marked this important"
                )
            )
        )
        val need = SmartInboxModel.lanes(listOf(e), nowMillis = now, profile = profile).needsYou.single()
        assertEquals(SmartInboxModel.NeedReason("Always important", SmartInboxModel.Urgency.FLAGGED), need.reason)
    }

    @Test
    fun otpNeedsYouUntilItsStatedValidityEnds() {
        val otp = msg(1, SMS, "SBI", "482913 is your OTP for login. Valid for 30 minutes.", now - 20 * 60_000, category = "OTP")
        val need = SmartInboxModel.lanes(listOf(otp), nowMillis = now).needsYou.single()
        assertEquals("Code · 10 min left", need.reason.chip)
        assertEquals("482913", need.otpCode)
        assertTrue(SmartInboxModel.lanes(listOf(otp), nowMillis = now + 11 * 60_000).needsYou.isEmpty())
    }

    @Test
    fun failedOrdersAndDeliveriesShowFailureChips() {
        val lanes = SmartInboxModel.lanes(
            listOf(
                msg(1, "com.zerodha.kite3", "Order rejected", "BUY 10 INFY rejected: insufficient margin", now - hour, category = "TRADING"),
                msg(2, "com.delhivery", "Delivery attempt failed", "AWB 1490: you were not available", now - hour, category = "DELIVERY")
            ),
            nowMillis = now
        )
        assertEquals(setOf("Order rejected", "Delivery failed"), lanes.needsYou.map { it.reason.chip }.toSet())
        assertTrue(lanes.needsYou.all { it.reason.urgency == SmartInboxModel.Urgency.FAILURE })
    }
```

- [ ] **Step 2: Run the tests and confirm they fail.**

Run: `./gradlew --no-daemon :app:testDebugUnitTest --tests "com.marksy.os.intelligence.SmartInboxModelTest"`
Expected: FAIL to compile, with unresolved reference `lanes`, `Lanes`, `InboxSummary` and so on.

- [ ] **Step 3: Implement.** In `SmartInboxModel.kt`, add `import com.marksy.os.notification.CaptureMedium` and `import java.util.Locale`, then add right after `inbox()`:

```kotlin
    // ---- Inbox redesign (spec 2026-10-02): lanes + source stacks ------------------------

    data class NeedThread(val thread: InboxThread, val reason: NeedReason, val otpCode: String?)

    data class SourceStack(val key: String, val label: String, val isSms: Boolean, val threads: List<InboxThread>) {
        val unread: Int get() = threads.count { it.unread }
        val latestAt: Long get() = threads.maxOf { it.latest.postedAt }
        val topAttention: Int get() = threads.maxOf { it.attentionScore }
    }

    data class InboxSummary(val needsYou: Int, val newUnread: Int)

    data class Lanes(
        val needsYou: List<NeedThread>,
        val fresh: List<SourceStack>,
        val earlier: List<SourceStack>,
        val snoozedCount: Int
    ) {
        val isEmpty: Boolean get() = needsYou.isEmpty() && fresh.isEmpty() && earlier.isEmpty()
        val summary: InboxSummary get() = InboxSummary(needsYou.size, fresh.sumOf { it.unread })
    }

    /** [seenThisVisit] holds thread keys opened since the user entered the tab; they keep their lane until the next visit. */
    fun lanes(
        events: List<NotificationEventEntity>,
        filter: Filter = Filter.ALL,
        query: String = "",
        nowMillis: Long = System.currentTimeMillis(),
        profile: PersonalLearning.Profile = PersonalLearning.Profile.EMPTY,
        seenThisVisit: Set<String> = emptySet(),
        zone: java.time.ZoneId = java.time.ZoneId.systemDefault()
    ): Lanes {
        val inbox = inbox(events, filter, query, nowMillis, profile, zone)
        val needs = mutableListOf<NeedThread>()
        val rest = mutableListOf<InboxThread>()
        inbox.sections.filterKeys { it != Bucket.RESOLVED }.values.flatten().forEach { thread ->
            val reason = needReason(thread, profile, nowMillis)
            if (reason == null) rest += thread
            else needs += NeedThread(thread, reason, if (thread.latest.category == "OTP") otpCode(thread.latest.body) else null)
        }
        val (fresh, earlier) = rest.partition { t ->
            nowMillis - t.latest.postedAt < NEW_WINDOW_MS && (t.unread || t.key in seenThisVisit)
        }
        return Lanes(
            needs.sortedWith(compareByDescending<NeedThread> { it.thread.attentionScore }.thenByDescending { it.thread.latest.postedAt }),
            stacks(fresh), stacks(earlier), inbox.snoozedCount
        )
    }

    // Action beats an explicit "Always important", which beats a bare high score: the chip names the most useful reason.
    private fun needReason(thread: InboxThread, profile: PersonalLearning.Profile, nowMillis: Long): NeedReason? {
        val open = thread.events.filter { it.lifecycleState != EventLifecycle.State.RESOLVED.name }
        actionFor(open, nowMillis)?.let { return it.second }
        val pinned = PersonalLearning.subjectsOf(thread.latest)
            .any { profile.of(it.type, it.key)?.override == PersonalLearning.Preference.ALWAYS_IMPORTANT }
        if (pinned) return NeedReason("Always important", Urgency.FLAGGED)
        return if (thread.bucket == Bucket.PRIORITY) NeedReason("High attention", Urgency.FLAGGED) else null
    }

    fun isSms(event: NotificationEventEntity): Boolean = CaptureMedium.of(event.sourcePackage) == CaptureMedium.SMS

    // One SMS app carries the bank, the telco and OTPs, so SMS stacks by sender; every other app is one stack.
    private fun stackKey(event: NotificationEventEntity): String {
        val pkg = event.sourcePackage.trim().lowercase(Locale.ROOT)
        return if (isSms(event)) "$pkg|${event.title.trim().lowercase(Locale.ROOT)}" else pkg
    }

    private fun stacks(threads: List<InboxThread>): List<SourceStack> =
        threads.groupBy { stackKey(it.latest) }
            .map { (key, group) ->
                val latest = group.maxBy { it.latest.postedAt }.latest
                val sms = isSms(latest)
                val label = if (sms) latest.title.ifBlank { latest.sourceName } else latest.sourceName.ifBlank { "System" }
                SourceStack(key, label, sms, group.sortedWith(compareByDescending<InboxThread> { it.attentionScore }.thenByDescending { it.latest.postedAt }))
            }
            .sortedWith(compareByDescending<SourceStack> { it.topAttention }.thenByDescending { it.latestAt })
```

Add `private const val NEW_WINDOW_MS = 24 * 60 * 60 * 1000L` to the constants block.

- [ ] **Step 4: Run the tests and confirm they pass.**

Run: `./gradlew --no-daemon :app:testDebugUnitTest --tests "com.marksy.os.intelligence.SmartInboxModelTest"`
Expected: PASS, with all old and new tests green. If `rowsInAStackRankByAttentionThenRecency` or `lanesSeparateNeedsYouNewAndEarlier` fails because `AdaptiveRanker` scores a WhatsApp message at 90 or more, check `AdaptiveRanker.rank` for that boost and lower the test priority so the score lands under 90. Do not change the 90 threshold.

- [ ] **Step 5: Commit.**

```bash
git add app/src/main/java/com/marksy/os/intelligence/SmartInboxModel.kt app/src/test/java/com/marksy/os/intelligence/SmartInboxModelTest.kt
git commit -m "feat(inbox): lanes model with per-source stacks and read-in-place

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 3: Retire seen, low-attention items after 2 days

**Files:**
- Modify: `app/src/main/java/com/marksy/os/intelligence/EventExpiry.kt`
- Modify: `app/src/main/java/com/marksy/os/data/local/NotificationEventDao.kt` (next to `findRetirable`, :181-182)
- Modify: `app/src/main/java/com/marksy/os/data/NotificationRepository.kt` (`retireExpired`, :77-86)
- Test: `app/src/test/java/com/marksy/os/intelligence/EventExpiryTest.kt`, `app/src/test/java/com/marksy/os/data/RetirementTest.kt`

**Interfaces:**
- Produces: `fun EventExpiry.staleSeen(event: NotificationEventEntity): EventExpiry.Expiry?`, `const val EventExpiry.REASON_STALE_SEEN`, `suspend fun NotificationEventDao.findSeenOpen(): List<NotificationEventEntity>`. `retireExpired` keeps its signature.

- [ ] **Step 1: Write the failing tests.** Add to `EventExpiryTest`:

```kotlin
    // Inbox redesign: seen, low-attention items leave the active views two days after posting.
    @Test
    fun seenLowAttentionItemsGoStaleAfterTwoDays() {
        val seen = event("BANKING", "HDFC Bank", "Rs 640 debited").copy(isRead = true, lifecycleState = "ACTIVE")
        assertEquals(seen.postedAt + 2 * day, EventExpiry.staleSeen(seen)?.atMillis)
        assertEquals(EventExpiry.REASON_STALE_SEEN, EventExpiry.staleSeen(seen)?.reason)
    }

    @Test
    fun unreadKeptRemindedDueFailedAndImportantItemsNeverGoStale() {
        val seen = event("BANKING", "HDFC Bank", "Rs 640 debited").copy(isRead = true, lifecycleState = "ACTIVE")
        assertNull(EventExpiry.staleSeen(seen.copy(isRead = false)))
        assertNull(EventExpiry.staleSeen(seen.copy(kept = true)))
        assertNull(EventExpiry.staleSeen(seen.copy(remindAt = seen.postedAt + day)))
        assertNull(EventExpiry.staleSeen(seen.copy(category = "BILLS")))
        assertNull(EventExpiry.staleSeen(seen.copy(category = "REMINDERS")))
        assertNull(EventExpiry.staleSeen(seen.copy(priority = 60)))
        assertNull(EventExpiry.staleSeen(seen.copy(importanceScore = 75)))
        assertNull(EventExpiry.staleSeen(seen.copy(category = "TRADING", isTrading = true, deliveryState = "FAILED")))
        assertNull(EventExpiry.staleSeen(seen.copy(lifecycleState = "RESOLVED")))
    }
```

Add to `RetirementTest`:

```kotlin
    // Inbox redesign: a seen, low-attention item leaves the Inbox after two days; history keeps it, unread stays.
    @Test
    fun seenLowAttentionItemRetiresAfterTwoDays() = runBlocking {
        dao.insert(event("BANKING", "Rs 500 debited").copy(isRead = true, lifecycleState = "ACTIVE"))
        dao.insert(event("MESSAGES", "Call me"))

        repository.retireExpired(t0 + 2 * 24 * 60 * minute)

        assertEquals(listOf("MESSAGES"), dao.observeActive().first().map { it.category })
        assertEquals(2, dao.observeHistory().first().size)
    }
```

- [ ] **Step 2: Run the tests and confirm they fail.**

Run: `./gradlew --no-daemon :app:testDebugUnitTest --tests "com.marksy.os.intelligence.EventExpiryTest" --tests "com.marksy.os.data.RetirementTest"`
Expected: FAIL to compile, with unresolved `staleSeen` and `REASON_STALE_SEEN`.

- [ ] **Step 3: Implement.** In `EventExpiry.kt`, add below `REASON_PREFIX`:

```kotlin
    const val REASON_STALE_SEEN = "${REASON_PREFIX}seen and older than 2 days"
    private const val STALE_ATTENTION = 60
    private val NEVER_STALE = setOf("BILLS", "REMINDERS")
```

Add this function after `of(...)`:

```kotlin
    /** Seen, low-attention items retire two days after posting; due, kept, reminded and failed items never do. */
    fun staleSeen(event: NotificationEventEntity): Expiry? {
        if (!event.isRead || event.kept || event.remindAt != null || event.archived) return null
        if (event.lifecycleState != EventLifecycle.State.NEW.name && event.lifecycleState != EventLifecycle.State.ACTIVE.name) return null
        if (event.category in NEVER_STALE || (event.isTrading && event.deliveryState == "FAILED")) return null
        if (maxOf(event.priority, event.importanceScore) >= STALE_ATTENTION) return null
        return Expiry(event.postedAt + 2 * DAY, REASON_STALE_SEEN)
    }
```

In `NotificationEventDao.kt`, add after `findRetirable`:

```kotlin
    // Seen open items EventExpiry.staleSeen may retire (Inbox redesign).
    @Query("SELECT * FROM notification_events WHERE lifecycleState IN ('NEW', 'ACTIVE') AND archived = 0 AND isRead = 1 AND kept = 0 AND remindAt IS NULL")
    suspend fun findSeenOpen(): List<NotificationEventEntity>
```

In `NotificationRepository.retireExpired`, add this right after the existing `open.forEach { … }` loop and before the `return`:

```kotlin
        dao.findSeenOpen().forEach { e ->
            if (e.id !in reasons) com.marksy.os.intelligence.EventExpiry.staleSeen(e)?.takeIf { it.atMillis <= nowMillis }?.let { reasons[e.id] = it.reason }
        }
```

- [ ] **Step 4: Run the tests and confirm they pass.**

Run: `./gradlew --no-daemon :app:testDebugUnitTest --tests "com.marksy.os.intelligence.EventExpiryTest" --tests "com.marksy.os.data.RetirementTest"`
Expected: PASS, including the existing `expiredOtpLeavesActiveViewsButStaysInHistory` (its BANKING row is unread) and `unseenRetiredItemStillCountsAsIgnored`.

- [ ] **Step 5: Commit.**

```bash
git add app/src/main/java/com/marksy/os/intelligence/EventExpiry.kt app/src/main/java/com/marksy/os/data/local/NotificationEventDao.kt app/src/main/java/com/marksy/os/data/NotificationRepository.kt app/src/test/java/com/marksy/os/intelligence/EventExpiryTest.kt app/src/test/java/com/marksy/os/data/RetirementTest.kt
git commit -m "feat(inbox): retire seen low-attention items after two days

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 4: Rewrite `SmartInboxScreen` to render lanes

**Files:**
- Modify (rewrite): `app/src/main/java/com/marksy/os/ui/SmartInboxScreen.kt`

**Interfaces:**
- Consumes: `SmartInboxModel.lanes`, `Lanes`, `NeedThread`, `SourceStack`, `Urgency`, `isSms` (Task 2); existing `InboxActions`, `ThreadActionsDialog`, `SwipeActionsRow(onDelete, onArchive, onHide, modifier, content)`, `Pill(text, selected, enabled, compact, onClick)` (WatchlistScreen.kt:361, `internal`, same package), `compactTime(Long): String?`, `EventText.body(title, body)`, `OneHandControls`, `OneHandListBottomPadding`, `EmptyState`, `MarksyTheme`.
- Produces: `SmartInboxScreen(…, onOpenHistory: () -> Unit = {})`, a new trailing parameter with every existing parameter unchanged; and top-level `fun inboxTitleNote(summary: SmartInboxModel.InboxSummary, filterName: String): String?` (used by Task 5).

No unit tests for this task (layout; test budget). Verify by compiling here and with the visual check in Task 6.

- [ ] **Step 1: Replace the file.** Write `SmartInboxScreen.kt` with the content below. `InboxActions`, `ThreadActionsDialog` and `nextMorningMillis` are carried over unchanged. `InboxNotificationCard` and the pill half of `SourceStyle` are removed.

```kotlin
package com.marksy.os.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.marksy.os.EmptyState
import com.marksy.os.data.local.NotificationEventEntity
import com.marksy.os.intelligence.PersonalLearning
import com.marksy.os.intelligence.SmartInboxModel
import com.marksy.os.intelligence.SmartInboxModel.InboxThread

private const val NEEDS_VISIBLE = 3
private const val STACK_VISIBLE = 2
private const val NEEDS_KEY = "needs"
private const val EARLIER_PREFIX = "e:"
private const val HOUR_MS = 60 * 60 * 1000L

@Composable
fun SmartInboxScreen(
    events: List<NotificationEventEntity>,
    padding: PaddingValues,
    onEventSelected: (NotificationEventEntity) -> Unit,
    selectedFilterName: String,
    onFilterSelected: (String) -> Unit,
    // Swipe actions act on one thread (every row incl. folded duplicates).
    onArchive: (List<NotificationEventEntity>) -> Unit = {},
    onDelete: (List<NotificationEventEntity>) -> Unit = {},
    onHide: (List<NotificationEventEntity>) -> Unit = {},
    actions: InboxActions = InboxActions(),
    learningProfile: PersonalLearning.Profile = PersonalLearning.Profile.EMPTY,
    onOpenHistory: () -> Unit = {}
) {
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var actionThreadKey by rememberSaveable { mutableStateOf<String?>(null) }
    var expanded by rememberSaveable { mutableStateOf(listOf<String>()) }
    var earlierOpen by rememberSaveable { mutableStateOf(false) }
    // Opened this visit keeps its place until the user leaves the tab, so the list never jumps under the thumb.
    var seenThisVisit by remember { mutableStateOf(setOf<String>()) }
    val clipboard = LocalClipboardManager.current

    val filter = SmartInboxModel.Filter.entries.firstOrNull { it.name == selectedFilterName }
        ?: SmartInboxModel.Filter.ALL

    // Re-evaluated every minute so snoozes, OTP countdowns and the 24 h window move without new data.
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            kotlinx.coroutines.delay(60_000)
            value = System.currentTimeMillis()
        }
    }
    val lanes = remember(events, filter, searchQuery, now, learningProfile, seenThisVisit) {
        SmartInboxModel.lanes(events, filter, searchQuery, now, learningProfile, seenThisVisit)
    }
    val byId = remember(events) { events.associateBy { it.id } }
    fun rowsOf(thread: InboxThread) = thread.allIds.mapNotNull { byId[it] }
    fun openThread(thread: InboxThread) {
        seenThisVisit = seenThisVisit + thread.key
        actions.markSeen(thread.allIds)
        onEventSelected(thread.latest)
    }
    fun toggle(key: String) { expanded = if (key in expanded) expanded - key else expanded + key }
    val swipe = ThreadSwipe(
        archive = { onArchive(rowsOf(it)) },
        delete = { onDelete(rowsOf(it)) },
        hide = { onHide(rowsOf(it)) }
    )

    Box(
        Modifier
            .fillMaxSize()
            .background(MarksyTheme.Background)
            .padding(padding)
            .consumeWindowInsets(padding)
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(bottom = OneHandListBottomPadding)
        ) {
            if (lanes.isEmpty) {
                item(key = "empty") {
                    EmptyState("Nothing here yet.", "New notifications matching this filter will appear here.")
                }
            }
            if (lanes.needsYou.isNotEmpty()) {
                val needsOpen = NEEDS_KEY in expanded
                item(key = "lane-needs") { LaneLabel("Needs you", lanes.needsYou.size, MarksyTheme.RedUrgent) }
                items(if (needsOpen) lanes.needsYou else lanes.needsYou.take(NEEDS_VISIBLE), key = { "n-" + it.thread.key }) { need ->
                    Swipeable(need.thread, swipe) {
                        NeedCard(
                            need,
                            onClick = { openThread(need.thread) },
                            onLongClick = { actionThreadKey = need.thread.key },
                            onDone = { actions.resolve(need.thread.allIds) },
                            onSnooze = { actions.snooze(need.thread.allIds, System.currentTimeMillis() + HOUR_MS) },
                            onCopy = { clipboard.setText(AnnotatedString(it)) }
                        )
                    }
                }
                if (lanes.needsYou.size > NEEDS_VISIBLE) item(key = "needs-more") {
                    MoreRow(
                        open = needsOpen,
                        hidden = lanes.needsYou.size - NEEDS_VISIBLE,
                        names = lanes.needsYou.drop(NEEDS_VISIBLE).map { it.thread.latest.title.ifBlank { it.thread.latest.sourceName } },
                        suffix = " need you",
                        standalone = true
                    ) { toggle(NEEDS_KEY) }
                }
            }
            if (lanes.fresh.isNotEmpty()) {
                item(key = "lane-new") { LaneLabel("New", lanes.fresh.sumOf { it.threads.size }, MarksyTheme.PrimaryEmerald) }
                items(lanes.fresh, key = { "s-" + it.key }) { stack ->
                    SourceStackCard(
                        stack,
                        expanded = stack.key in expanded,
                        swipe = swipe,
                        onToggle = { toggle(stack.key) },
                        onOpen = { openThread(it) },
                        onLongClick = { actionThreadKey = it.key },
                        onMarkAllRead = {
                            seenThisVisit = seenThisVisit + stack.threads.map { it.key }
                            actions.markSeen(stack.threads.flatMap { it.allIds })
                        }
                    )
                }
            }
            if (lanes.earlier.isNotEmpty()) {
                item(key = "earlier-fold") { EarlierFold(lanes.earlier, earlierOpen) { earlierOpen = !earlierOpen } }
                if (earlierOpen) item(key = "earlier-list") {
                    EarlierList(
                        lanes.earlier,
                        expanded,
                        onToggle = { toggle(it) },
                        onOpen = { openThread(it) },
                        onLongClick = { actionThreadKey = it.key },
                        onClearAll = {
                            onArchive(lanes.earlier.flatMap { s -> s.threads.flatMap { rowsOf(it) } })
                            earlierOpen = false
                        }
                    )
                }
            }
            item(key = "footer") { InboxFooter(lanes.snoozedCount, onOpenHistory) }
        }
        OneHandControls(
            filters = SmartInboxModel.Filter.entries.map { it.name to it.label },
            selectedFilter = filter.name,
            onFilterSelected = onFilterSelected,
            searchQuery = searchQuery,
            onSearchChange = { searchQuery = it },
            searchPlaceholder = "Search notifications..."
        )
    }

    val actionThread = actionThreadKey?.let { key ->
        (lanes.needsYou.map { it.thread } + (lanes.fresh + lanes.earlier).flatMap { it.threads }).firstOrNull { it.key == key }
    }
    if (actionThread != null) {
        ThreadActionsDialog(actionThread, actions, onDismiss = { actionThreadKey = null })
    }
}

/** Header note for the Inbox tab: what needs the user and how much is new, or the active filter. */
fun inboxTitleNote(summary: SmartInboxModel.InboxSummary, filterName: String): String? {
    val filter = SmartInboxModel.Filter.entries.firstOrNull { it.name == filterName } ?: SmartInboxModel.Filter.ALL
    if (filter != SmartInboxModel.Filter.ALL) return filter.label
    return when {
        summary.needsYou > 0 -> "${summary.needsYou} need you · ${summary.newUnread} new"
        summary.newUnread > 0 -> "${summary.newUnread} new"
        else -> null
    }
}

/** Thread-level inbox actions; each receives every row id the thread represents. */
data class InboxActions(
    val markSeen: (List<Long>) -> Unit = {},
    val resolve: (List<Long>) -> Unit = {},
    val reopen: (List<Long>) -> Unit = {},
    val snooze: (List<Long>, Long) -> Unit = { _, _ -> },
    val archive: (List<Long>) -> Unit = {},
    val prefer: (PersonalLearning.Subject, PersonalLearning.Preference?) -> Unit = { _, _ -> }
)

private data class ThreadSwipe(
    val archive: (InboxThread) -> Unit,
    val delete: (InboxThread) -> Unit,
    val hide: (InboxThread) -> Unit
)

@Composable
private fun Swipeable(thread: InboxThread, swipe: ThreadSwipe, content: @Composable () -> Unit) {
    SwipeActionsRow(
        onDelete = { swipe.delete(thread) },
        onArchive = { swipe.archive(thread) },
        onHide = { swipe.hide(thread) },
        content = content
    )
}

@Composable
private fun LaneLabel(text: String, count: Int, dot: Color) {
    Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(dot))
        Spacer(Modifier.width(8.dp))
        Text(text.uppercase(), color = MarksyTheme.TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp)
        Spacer(Modifier.width(6.dp))
        Text("$count", color = MarksyTheme.TextMuted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(8.dp))
        Box(Modifier.weight(1f).height(1.dp).background(MarksyTheme.BorderGlow))
    }
}

private fun urgencyColors(urgency: SmartInboxModel.Urgency): Pair<Color, Color> = when (urgency) {
    SmartInboxModel.Urgency.FAILURE -> MarksyTheme.RedUrgent to MarksyTheme.BadgeUrgentBg
    SmartInboxModel.Urgency.DUE -> MarksyTheme.YellowImportant to MarksyTheme.BadgeImportantBg
    SmartInboxModel.Urgency.FLAGGED -> MarksyTheme.PrimaryEmerald to MarksyTheme.BadgeTradingBg
}

private fun urgencyIcon(urgency: SmartInboxModel.Urgency, otp: Boolean): ImageVector = when {
    otp -> Icons.Default.VpnKey
    urgency == SmartInboxModel.Urgency.FAILURE -> Icons.Default.Error
    urgency == SmartInboxModel.Urgency.DUE -> Icons.Default.Schedule
    else -> Icons.Default.Star
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
private fun NeedCard(
    need: SmartInboxModel.NeedThread,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onDone: () -> Unit,
    onSnooze: () -> Unit,
    onCopy: (String) -> Unit
) {
    val event = need.thread.latest
    val (tint, tintBg) = urgencyColors(need.reason.urgency)
    val shape = RoundedCornerShape(16.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MarksyTheme.Surface)
            .border(1.dp, lerp(MarksyTheme.BorderGlow, tint, 0.38f), shape)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick, onLongClickLabel = "Thread actions")
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier.clip(RoundedCornerShape(50)).background(tintBg).padding(start = 7.dp, end = 9.dp, top = 3.dp, bottom = 3.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(urgencyIcon(need.reason.urgency, need.otpCode != null), contentDescription = null, tint = tint, modifier = Modifier.size(13.dp))
                Spacer(Modifier.width(5.dp))
                Text(need.reason.chip, color = tint, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.weight(1f))
            Text(compactTime(event.postedAt).orEmpty(), color = MarksyTheme.TextMuted, fontSize = 11.sp)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            SourceIcon(resolveSourceStyle(event), 18.dp, 11.dp)
            Spacer(Modifier.width(7.dp))
            Text(
                if (SmartInboxModel.isSms(event)) "SMS" else event.sourceName.ifBlank { "System" },
                color = MarksyTheme.TextMuted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
        Text(
            event.title.ifBlank { event.sourceName.ifBlank { "Notification event" } },
            color = MarksyTheme.TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold,
            maxLines = 2, overflow = TextOverflow.Ellipsis
        )
        val body = EventText.body(event.title, event.body)
        if (body.isNotBlank()) {
            Text(body, color = MarksyTheme.TextSecondary, fontSize = 12.sp, lineHeight = 17.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        FlowRow(
            Modifier.padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Pill("Done", onClick = onDone)
            Pill("Snooze 1h", onClick = onSnooze)
            need.otpCode?.let { code -> Pill("Copy $code", onClick = { onCopy(code) }) }
        }
    }
}

@Composable
private fun SourceIcon(style: SourceStyle, size: Dp, iconSize: Dp) {
    Box(Modifier.size(size).clip(CircleShape).background(style.iconBg), contentAlignment = Alignment.Center) {
        Icon(style.icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(iconSize))
    }
}

private data class RowText(val headline: String, val detail: String, val bodyLed: Boolean)

// In an SMS stack the label already names the sender, so the message itself leads.
private fun rowText(event: NotificationEventEntity, smsStack: Boolean): RowText {
    val body = EventText.body(event.title, event.body)
    return if (smsStack) RowText(body.ifBlank { event.title }, "", true)
    else RowText(event.title.ifBlank { event.sourceName }, body, false)
}

@Composable
private fun SourceStackCard(
    stack: SmartInboxModel.SourceStack,
    expanded: Boolean,
    swipe: ThreadSwipe,
    onToggle: () -> Unit,
    onOpen: (InboxThread) -> Unit,
    onLongClick: (InboxThread) -> Unit,
    onMarkAllRead: () -> Unit
) {
    val hidden = stack.threads.size - STACK_VISIBLE
    val peek = hidden > 0 && !expanded
    val shape = RoundedCornerShape(16.dp)
    Box(Modifier.fillMaxWidth()) {
        // A second card edge peeking out underneath says "there is more in here".
        if (peek) Box(
            Modifier.matchParentSize().padding(start = 12.dp, end = 12.dp, top = 6.dp)
                .clip(shape).background(MarksyTheme.Surface).border(1.dp, MarksyTheme.BorderGlow, shape)
        )
        Column(
            Modifier
                .fillMaxWidth()
                .padding(bottom = if (peek) 6.dp else 0.dp)
                .clip(shape)
                .background(MarksyTheme.Surface)
                .border(1.dp, MarksyTheme.BorderGlow, shape)
                .animateContentSize()
        ) {
            Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                SourceIcon(resolveSourceStyle(stack.threads.first().latest), 28.dp, 15.dp)
                Spacer(Modifier.width(10.dp))
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stack.label, color = MarksyTheme.TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false)
                    )
                    if (stack.isSms) {
                        Spacer(Modifier.width(6.dp))
                        Text("SMS", color = MarksyTheme.TextMuted, fontSize = 11.sp)
                    }
                }
                if (stack.unread > 0) {
                    Text("${stack.unread} new", color = MarksyTheme.PrimaryEmerald, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(8.dp))
                }
                Text(compactTime(stack.latestAt).orEmpty(), color = MarksyTheme.TextMuted, fontSize = 11.sp)
                if (stack.unread > 0) {
                    IconButton(onClick = onMarkAllRead, modifier = Modifier.size(40.dp)) {
                        Icon(Icons.Default.DoneAll, contentDescription = "Mark all from ${stack.label} read", tint = MarksyTheme.TextSecondary, modifier = Modifier.size(18.dp))
                    }
                } else {
                    Spacer(Modifier.width(12.dp))
                }
            }
            (if (expanded) stack.threads else stack.threads.take(STACK_VISIBLE)).forEachIndexed { index, thread ->
                if (index > 0) HorizontalDivider(Modifier.padding(start = 50.dp), color = MarksyTheme.BorderGlow.copy(alpha = 0.5f))
                Swipeable(thread, swipe) {
                    StackRow(thread, stack.isSms, first = index == 0, onClick = { onOpen(thread) }, onLongClick = { onLongClick(thread) })
                }
            }
            if (hidden > 0) {
                HorizontalDivider(color = MarksyTheme.BorderGlow.copy(alpha = 0.5f))
                MoreRow(
                    open = expanded,
                    hidden = hidden,
                    names = stack.threads.drop(STACK_VISIBLE).map { t ->
                        rowText(t.latest, stack.isSms).let { if (it.bodyLed) compactTime(t.latest.postedAt).orEmpty() else it.headline }
                    },
                    suffix = "",
                    standalone = false,
                    onClick = onToggle
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun StackRow(thread: InboxThread, smsStack: Boolean, first: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    val text = rowText(thread.latest, smsStack)
    val unread = thread.unread
    Box(
        Modifier
            .fillMaxWidth()
            .background(MarksyTheme.Surface)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick, onLongClickLabel = "Thread actions")
            .padding(start = 50.dp, end = 14.dp, top = 7.dp, bottom = 9.dp)
    ) {
        if (unread) Box(Modifier.offset(x = (-19).dp, y = 6.dp).size(7.dp).clip(CircleShape).background(MarksyTheme.PrimaryEmerald))
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text.headline,
                    color = MarksyTheme.TextPrimary,
                    fontSize = if (text.bodyLed) 13.sp else 13.5.sp,
                    fontWeight = when {
                        text.bodyLed && unread -> FontWeight.Medium
                        text.bodyLed -> FontWeight.Normal
                        unread -> FontWeight.Bold
                        else -> FontWeight.Medium
                    },
                    maxLines = if (text.bodyLed) 2 else 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (thread.count > 1) {
                    Spacer(Modifier.width(6.dp))
                    Box(
                        Modifier.heightIn(min = 18.dp).widthIn(min = 18.dp).clip(RoundedCornerShape(50))
                            .background(MarksyTheme.BadgeTradingBg).padding(horizontal = 5.dp),
                        contentAlignment = Alignment.Center
                    ) { Text("${thread.count}", color = MarksyTheme.PrimaryEmerald, fontSize = 10.sp, fontWeight = FontWeight.Bold) }
                }
                Spacer(Modifier.width(8.dp))
                Text(compactTime(thread.latest.postedAt).orEmpty(), color = MarksyTheme.TextMuted, fontSize = 11.sp)
            }
            if (text.detail.isNotBlank()) {
                Text(
                    text.detail, color = MarksyTheme.TextSecondary, fontSize = 12.sp, lineHeight = 17.sp,
                    maxLines = if (first) 2 else 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
    }
}

@Composable
private fun MoreRow(open: Boolean, hidden: Int, names: List<String>, suffix: String, standalone: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    val rotation by animateFloatAsState(if (open) 180f else 0f, label = "more-chevron")
    val base = if (standalone) Modifier.fillMaxWidth().clip(shape).background(MarksyTheme.Surface).border(1.dp, MarksyTheme.BorderGlow, shape)
    else Modifier.fillMaxWidth()
    Row(
        base.clickable(onClickLabel = if (open) "Show less" else "Show $hidden more", onClick = onClick)
            .padding(start = if (standalone) 14.dp else 50.dp, end = 14.dp, top = 9.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Default.ExpandMore, contentDescription = null, tint = MarksyTheme.PrimaryEmerald, modifier = Modifier.size(18.dp).rotate(rotation))
        Spacer(Modifier.width(6.dp))
        Text(if (open) "Show less" else "$hidden more$suffix", color = MarksyTheme.PrimaryEmerald, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        if (!open) {
            Spacer(Modifier.width(8.dp))
            Text(names.joinToString(", "), color = MarksyTheme.TextMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun EarlierFold(stacks: List<SmartInboxModel.SourceStack>, open: Boolean, onToggle: () -> Unit) {
    val count = stacks.sumOf { it.threads.size }
    val rotation by animateFloatAsState(if (open) 180f else 0f, label = "earlier-chevron")
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 6.dp)
            .clip(RoundedCornerShape(16.dp))
            .drawBehind {
                drawRoundRect(
                    color = MarksyTheme.BorderGlow,
                    cornerRadius = CornerRadius(16.dp.toPx()),
                    style = Stroke(width = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx())))
                )
            }
            .clickable(onClickLabel = if (open) "Fold earlier" else "Show earlier", onClick = onToggle)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Default.History, contentDescription = null, tint = MarksyTheme.TextSecondary, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(color = MarksyTheme.TextPrimary, fontWeight = FontWeight.Bold)) { append("Earlier") }
                    append(" · $count item${if (count == 1) "" else "s"} from ${stacks.size} source${if (stacks.size == 1) "" else "s"}")
                },
                color = MarksyTheme.TextSecondary, fontSize = 13.sp
            )
            Text("Seen, or older than a day", color = MarksyTheme.TextMuted, fontSize = 11.sp)
        }
        Icon(Icons.Default.ExpandMore, contentDescription = null, tint = MarksyTheme.TextSecondary, modifier = Modifier.size(20.dp).rotate(rotation))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EarlierList(
    stacks: List<SmartInboxModel.SourceStack>,
    expanded: List<String>,
    onToggle: (String) -> Unit,
    onOpen: (InboxThread) -> Unit,
    onLongClick: (InboxThread) -> Unit,
    onClearAll: () -> Unit
) {
    val shape = RoundedCornerShape(16.dp)
    Column(
        Modifier.fillMaxWidth().clip(shape).background(MarksyTheme.Surface)
            .border(1.dp, MarksyTheme.BorderGlow.copy(alpha = 0.6f), shape)
            .animateContentSize()
            .padding(vertical = 4.dp)
    ) {
        stacks.forEach { stack ->
            val key = EARLIER_PREFIX + stack.key
            val open = key in expanded
            val top = rowText(stack.threads.first().latest, stack.isSms)
            Row(
                Modifier.fillMaxWidth().clickable { onToggle(key) }.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.alpha(0.8f)) { SourceIcon(resolveSourceStyle(stack.threads.first().latest), 24.dp, 13.dp) }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("${stack.label} · ${stack.threads.size}", color = MarksyTheme.TextSecondary, fontSize = 12.5.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        if (top.bodyLed || top.detail.isBlank()) top.headline else "${top.headline}: ${top.detail}",
                        color = MarksyTheme.TextMuted, fontSize = 11.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(compactTime(stack.latestAt).orEmpty(), color = MarksyTheme.TextMuted, fontSize = 11.sp)
                Icon(
                    Icons.Default.ExpandMore, contentDescription = if (open) "Fold ${stack.label}" else "Show ${stack.label}",
                    tint = MarksyTheme.TextMuted, modifier = Modifier.size(18.dp).rotate(if (open) 180f else 0f)
                )
            }
            if (open) stack.threads.forEach { thread ->
                val t = rowText(thread.latest, stack.isSms)
                Row(
                    Modifier.fillMaxWidth()
                        .combinedClickable(onClick = { onOpen(thread) }, onLongClick = { onLongClick(thread) }, onLongClickLabel = "Thread actions")
                        .padding(start = 46.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        if (t.bodyLed || t.detail.isBlank()) t.headline else "${t.headline} · ${t.detail}",
                        color = MarksyTheme.TextSecondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(compactTime(thread.latest.postedAt).orEmpty(), color = MarksyTheme.TextMuted, fontSize = 11.sp)
                }
            }
        }
        Row(Modifier.padding(start = 12.dp, top = 6.dp, bottom = 4.dp)) {
            Pill("Clear all ${stacks.sumOf { it.threads.size }}", onClick = onClearAll)
        }
    }
}

@Composable
private fun InboxFooter(snoozed: Int, onOpenHistory: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        if (snoozed > 0) Text("$snoozed snoozed  ·  ", color = MarksyTheme.TextMuted, fontSize = 11.sp)
        Text(
            "History", color = MarksyTheme.TextSecondary, fontSize = 11.sp, textDecoration = TextDecoration.Underline,
            modifier = Modifier.clickable(onClickLabel = "Open history", onClick = onOpenHistory).padding(4.dp)
        )
    }
}
```

After that block, paste the existing `ThreadActionsDialog` and `nextMorningMillis` functions **verbatim** from the old file (old lines 172-213). Then add the reduced source style:

```kotlin
private data class SourceStyle(val icon: ImageVector, val iconBg: Color)

/** Source identity only; importance is shown by lane and reason chip, never by app. */
private fun resolveSourceStyle(event: NotificationEventEntity): SourceStyle {
    // SMS rows carry the sender in the title, so a bank SMS still gets the bank icon.
    val src = (if (SmartInboxModel.isSms(event)) "${event.sourceName} ${event.title}" else event.sourceName).lowercase()
    val category = event.category.uppercase()
    return when {
        event.isTrading || src.contains("zerodha") || src.contains("groww") || src.contains("upstox") || src.contains("kite") ->
            SourceStyle(Icons.Default.ShowChart, Color(0xFFC62828))
        src.contains("whatsapp") -> SourceStyle(Icons.Default.Chat, Color(0xFF2E7D32))
        src.contains("gmail") || src.contains("mail") || category == "WORK" -> SourceStyle(Icons.Default.Email, Color(0xFF1565C0))
        src.contains("icici") || src.contains("bank") || src.contains("upi") || category == "PAYMENTS" || category == "BANKING" ->
            SourceStyle(Icons.Default.AccountBalance, Color(0xFF0288D1))
        src.contains("swiggy") || src.contains("zomato") || category == "DELIVERY" -> SourceStyle(Icons.Default.LocalShipping, Color(0xFFE65100))
        category == "OTP" -> SourceStyle(Icons.Default.VpnKey, Color(0xFF0288D1))
        else -> SourceStyle(Icons.Default.Notifications, Color(0xFF37474F))
    }
}
```

- [ ] **Step 2: Compile.**

Run: `./gradlew --no-daemon :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL. Deprecation warnings (`LocalClipboardManager`, filled `ShowChart`/`Chat`) are acceptable because warnings are not errors in this project. If the `rememberScrollState`/`verticalScroll` imports become unused after pasting `ThreadActionsDialog`, they are still used by it; leave them.

- [ ] **Step 3: Commit.**

```bash
git add app/src/main/java/com/marksy/os/ui/SmartInboxScreen.kt
git commit -m "feat(inbox): render Needs you, source stacks and folded Earlier

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 5: Header note, tab badge and History link in MainActivity

**Files:**
- Modify: `app/src/main/java/com/marksy/os/MainActivity.kt` (titleNote `when` at :387-395; NavigationBarItem `icon` at :602; SmartInboxScreen call at :728-748)

**Interfaces:**
- Consumes: `SmartInboxModel.lanes(...).summary` (Task 2); `inboxTitleNote` and the `onOpenHistory` parameter (Task 4); existing `inboxEvents` (:209), `inboxHidden` (:314), `inboxFilterName` (:239), `learningProfile` (:206), `showTimeline`.

- [ ] **Step 1: Compute the summary.** Insert immediately above `val titleNote = when {`:

```kotlin
        // Unfiltered, so the badge and note are right before the Inbox tab is ever opened.
        val inboxSummary = remember(inboxEvents, inboxHidden, learningProfile) {
            SmartInboxModel.lanes(inboxEvents.filterNot { it.id in inboxHidden }, profile = learningProfile).summary
        }
```

- [ ] **Step 2: Header note.** In the `titleNote` `when`, add this branch directly after the `selectedTab == 4 -> …` branch:

```kotlin
            selectedTab == 1 -> com.marksy.os.ui.inboxTitleNote(inboxSummary, inboxFilterName)
```

- [ ] **Step 3: Tab badge.** Replace `icon = { Icon(icon, contentDescription = label) },` in the `NavigationBarItem` with:

```kotlin
                            icon = {
                                if (index == 1 && inboxSummary.needsYou > 0) {
                                    BadgedBox(badge = {
                                        Badge(containerColor = MarksyTheme.RedUrgent, contentColor = Color.White) { Text("${inboxSummary.needsYou}", fontSize = 10.sp) }
                                    }) { Icon(icon, contentDescription = "$label, ${inboxSummary.needsYou} need you") }
                                } else Icon(icon, contentDescription = label)
                            },
```

- [ ] **Step 4: History link.** In the `SmartInboxScreen(` call, add after `learningProfile = learningProfile,`:

```kotlin
                    onOpenHistory = { showTimeline = true },
```

- [ ] **Step 5: Compile and run the affected unit tests.**

Run: `./gradlew --no-daemon :app:compileDebugKotlin :app:testDebugUnitTest --tests "com.marksy.os.intelligence.*" --tests "com.marksy.os.data.RetirementTest"`
Expected: BUILD SUCCESSFUL, all green.

- [ ] **Step 6: Commit.**

```bash
git add app/src/main/java/com/marksy/os/MainActivity.kt
git commit -m "feat(inbox): needs-you header note, tab badge and History link

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 6: Full verification, visual check and PR

**Files:** none in the repo. The scratch render test stays out of git.

- [ ] **Step 1: Full unit suite and debug build.**

Run: `./gradlew --no-daemon :app:testDebugUnitTest :app:assembleDebug`
Expected: BUILD SUCCESSFUL with 0 test failures. Paste the summary line in the PR.

- [ ] **Step 2: Offscreen visual check.** The emulator does not boot on this machine, so render with Robolectric. Create a **throwaway** test at `app/src/test/java/com/marksy/os/ui/ScratchInboxPreview.kt` (never commit it). It should:
  - use `@RunWith(RobolectricTestRunner::class)`, `@GraphicsMode(GraphicsMode.Mode.NATIVE)` and `@Config(sdk = [34], qualifiers = "w400dp-h2600dp-xxhdpi")`;
  - call `createComposeRule().setContent { MarksyMaterialTheme { SmartInboxScreen(events = sample, padding = PaddingValues(), onEventSelected = {}, selectedFilterName = "ALL", onFilterSelected = {}) } }`. Build `sample` from the mockup's data: an SBI OTP, an HDFC failed payment, a rejected Kite order, an Airtel bill, a failed Delhivery delivery, 4 WhatsApp chats, 4 Gmail mails, 3 HDFC SMS, a Kite stack and 10 older seen rows;
  - write `onRoot().captureToImage().asAndroidBitmap()` to a PNG in the session scratchpad.

  Render once collapsed, then once after `onNodeWithText("2 more", substring = true).performClick()` and `onNodeWithText("Earlier", substring = true).performClick()`. Also render at `w360dp`. Look at the PNGs and check:
  - no clipped text and no overlap with the floating buttons;
  - chips are readable;
  - the peek edge shows under a collapsed stack;
  - the look matches the mockup and other Marksy screens.

  Then delete the scratch test: `git status` must show it untracked, and it must be removed before the PR.

- [ ] **Step 3: Device check.** Only if the user's phone is connected and Marksy has window focus. Follow the safety gate in memory `marksy-os-local-run-env`: check `mCurrentFocus` and the call state, never tap other apps, and never `connectedAndroidTest`. Install with `assembleDebug` plus `adb install -r`, open the Inbox, and take one `screencap`. If the gate fails, skip this step and say so in the PR.

- [ ] **Step 4: Whole-branch review.** Request one whole-phase code review (superpowers:requesting-code-review) against the spec, fix findings, re-review once.

- [ ] **Step 5: Push and open the PR.**

```bash
git push -u origin feat/inbox-redesign
gh pr create --base main --title "Inbox redesign: Needs you, source stacks, folded Earlier, stale retirement" --body "$(cat <<'EOF'
Implements docs/superpowers/specs/2026-10-02-marksy-inbox-redesign-design.md.

- Needs you lane with reason chips, inline Done/Snooze/Copy code
- One stack per app (SMS per sender), top 2 shown, expander names the rest
- Earlier folded by default with Clear all (undo)
- Seen low-attention items retire after 2 days; History keeps them
- Header note "N need you · M new" and Inbox tab badge

Tests: <paste testDebugUnitTest summary>. Visual: <offscreen render / device result>.

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```

- [ ] **Step 6: Merge** when the required checks are green. The user has authorized merging their own marksy-os PRs once required tests pass. Then report: what changed, what was validated, the PR link, and any remaining blocker.
