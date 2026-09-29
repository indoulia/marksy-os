# Tip Ledger Phase 4a (Phone Capture) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The phone sends each captured market message to `POST /tips/ingest-text` as the signed-in customer: cleaned text, a channel label and a device event key, never a title, body or sender. The server first has to stop pooling unrelated chats into one channel, because `source_channel_id` can never be rewritten.

**Architecture:**
- **Part A (marksy-api, ships first).**
  - A cleaned label made only of masks (for example `[PHONE]`) is rejected with 422.
  - `channel_aliases` gains a `scope`: the medium whose names an alias belongs to. Each alias is unique per scope, so a WhatsApp group named "Zerodha" can never resolve to the Zerodha broker, and the reverse holds too.
  - Migration `0184_channel_alias_scope` backfills existing aliases and removes mask-only labels.
- **Part B (marksy-os, ships only after Part A is in production).**
  - `TipTextCleaner` mirrors the server cleaner rule for rule, checked against the same test vectors.
  - The listener records MessagingStyle sender names locally.
  - One pure gate (`CaptureGate`) decides, per queued trading row, whether it leaves the phone, and builds the §5.1 payload. The payload carries the app name, the SMS sender id, or the allow-listed chat name without its sender; the text has sender names stripped and masked; the device event key is salted.
  - The delivery worker loads a cached `GET /channels/capture-list` and posts to `/tips/ingest-text` instead of `POST /tips`.

**Tech Stack:**
- Part A: Python 3.12, FastAPI 0.139, SQLAlchemy 2.0.52, Alembic 1.19, pytest on SQLite (tests), PostgreSQL (prod).
- Part B: Kotlin 2.3.21, AGP 9.4.0, Gradle wrapper 9.7.1, Room, WorkManager, JUnit 4 + Robolectric 4.17, `org.json:json:20240303` in unit tests.

**Spec:** `C:\AIAgent\marksy-os\docs\superpowers\specs\2026-09-29-marksy-central-tip-ledger-design.md`. The relevant parts are:
- §2 invariant 11;
- §5.1 and §5.2 steps 1–2;
- §10, the "Add" bullet only;
- §15, 1:1 labels.

The Phase 1 plan gives context: `C:\AIAgent\marksy-os\docs\superpowers\plans\2026-09-29-tip-ledger-phase-0-1.md`, resolved ambiguities 9, 10 and 14 and Tasks 4, 11, 13 and 14.

## Global Constraints

**Both parts**
- Commit once per task. Commit messages end with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`, and PR bodies end with `🤖 Generated with [Claude Code](https://claude.com/claude-code)`.
- Merging is authorized: use `gh pr merge --merge --delete-branch` once the part's final test set is green. GitHub Actions don't run (account billing), so an UNSTABLE status is expected and does not block the merge.
- Comments are one line at most and explain only a non-obvious WHY. Make no unrelated refactors.
- Spec §2.11, verbatim: "The only customer data leaving the phone is the customer id (from the signed-in session). Private data (inbox, timeline, contacts, allow-lists) stays on the phone."
- Spec §5.1, verbatim: "Capture set: packages from `GET /channels/capture-list` plus the customer's own WhatsApp/Telegram allow-list. Nothing outside that set leaves the phone."
- Phase 4b is out of scope: the §10 "Delete" list and the new screens. The one exception is the old `POST /tips` payload, which this plan retires (decision 12).

**Part A (marksy-api)**
- Repo: `C:\AIAgent\marksy-api`. Work in the worktree `C:\AIAgent\marksy-api-phase4a` on branch `feat/tip-label-scope`, created from `origin/main` when the part starts.
- Deploying is authorized. After the merge, run `ssh marksy@103.178.160.233 'bash ~/deployment/server/vps-deploy.sh'`. The script's migrate Job runs Alembic, so no manual `alembic upgrade` is needed.
- Tests: run only the `python -m pytest ...` commands given in each step. Never run the full suite: it takes about 2 hours, and 4 tests already fail on main locally. If a listed test fails, run the same file on `main` in `C:\AIAgent\marksy-api`. If it fails there too, it is pre-existing: note it in the PR and don't fix it.
- `tests/conftest.py` gives the app engine a file DB at `%TEMP%\marksy-pytest-default.db`, and `create_all` never alters an existing table. Delete that file whenever the ORM schema changes (Task A3 says when).
- Follow the repo's code style: `Mapped`/`mapped_column`, no ORM relationships. Alembic revision ids must be at most 32 characters, and migrations must run on both SQLite and PostgreSQL.
- Phase 2a (`0182_tip_daily_progress`) and Phase 2b (`0183_tips_prediction_unique`) are built on other branches. Task A1 Step 1 reads the real head, and Task A2 chains from it.

**Part B (marksy-os)**
- Repo: `C:\AIAgent\marksy-os`. Work in the worktree `C:\AIAgent\marksy-os-phase4a` on branch `feat/tip-capture-payload`, created from `origin/main`. Leave `C:\AIAgent\marksy-os` on its current branch.
- **Part B starts only after Part A is deployed.** Task B1 Step 1 checks production and stops if Part A is not there.
- Unit-test command, all from `C:\AIAgent\marksy-os-phase4a`:
  - The Windows user `Path` has a corrupted entry that kills forked test JVMs. Strip it first.
  - Stop Gradle once per shell session, then run with `--no-daemon`.
  - Each step names its own `--tests` filter. Task B1's filter is shown here:

  ```bash
  cd /c/AIAgent/marksy-os-phase4a
  export PATH="$(echo "$PATH" | tr ':' '\n' | grep -vE '"|%' | paste -sd:)"
  ./gradlew --stop
  ./gradlew --no-daemon :app:testDebugUnitTest --tests 'com.marksy.os.notification.TipTextCleanerTest'
  ```
- **Never run `connectedAndroidTest`.** It uninstalls the app and wipes the user's data. This plan has no on-device step: no install, no `adb` input, no `am start`. The OnePlus is the user's personal phone. Any later on-device check needs the window-focus safety gate from the `marksy-os-local-run-env` memory.
- Tests only guard real logic (the user's test budget). Prefer extending an existing test file, and write no UI-layout tests.
- Android runs regexes on ICU, while unit tests run on the JVM. Escape every literal brace or bracket. The cleaner spells out Unicode classes (`\p{L}`, `\p{Nd}`, `\p{Z}`) so that the JVM, ICU and Python's Unicode-aware `\w`, `\d` and `\s` agree.
- UI: no new screens or components. A row the gate keeps local shows the existing "Local only" delivery status.

## Review Focus

These are the inputs the spec implies but its happy paths never exercise. Each one has a test in the owning task:

1. **A 1:1 chat whose title is a phone number.** The customer allow-listed "+91 98765 43210". The label cleans to `[PHONE]`, and the message must stay on the phone. The server must also reject such a label (422) rather than pool every such chat into one channel. Test: Task B3, `aOneToOneChatTitledWithAPhoneNumberNeverLeavesThePhone`. The server backstop is Task A1, `test_a_mask_only_label_is_rejected_and_pools_nothing`.
2. **A group label with the sender prefix.** WhatsApp stores group titles as "StockTips: Rahul" and Telegram as "Rahul @ StockTips". The label must be "StockTips", and no sender name may appear in the text. Test: Task B3, `aGroupMessageSendsTheGroupNameAndNoSenderName`.
3. **A WhatsApp group named "Zerodha".** It must get its own WHATSAPP_GROUP channel, never the broker's; and an app label never resolves to a WhatsApp group. Test: Task A3, `test_a_whatsapp_group_named_zerodha_is_not_the_zerodha_broker`.
4. **A retry after the server already recorded the message.** This is a response lost after the commit. The retry must carry the same `deviceEventKey`, so the server returns the stored receipt instead of a second one. The key must reveal nothing of the Android notification key. Test: Task B3, `theDeviceEventKeyIsStableAcrossRetriesAndRevealsNoNotificationKey`.
5. **The first delivery run, with no capture list cached yet** (for example, offline right after the upgrade). App notifications must wait: they are neither sent unfiltered nor dropped. Allow-listed chats still go. Test: Task B3, `appNotificationsWaitUntilTheCaptureListIsCached`. The run-level half is Task B4, `aRowWaitingForTheCaptureListEndsTheRunWithARetry`.

## Resolved ambiguities (decisions this plan makes)

1. **Label scoping is a `scope` column on `channel_aliases`, not a type filter at lookup.** `scope` is the medium whose names the alias belongs to, and `(scope, alias)` is unique, replacing the global unique `alias`. Five reasons:
   - The seeded WhatsApp and WhatsApp Business app channels have type WHATSAPP_GROUP and carry LABEL aliases "whatsapp" and "whatsapp business". Resolving WhatsApp labels among WHATSAPP_GROUP channels would pool a group named "WhatsApp" into the app's channel, which Phase 1 decision 9 forbids. A scope records what the alias names (an app name, so APP_NOTIFICATION), not what its channel is.
   - Moneycontrol is seeded as NEWS_PORTAL, but its label is an app name. EMAIL and MANUAL both create NEWS_PORTAL channels. A type filter can't separate these namespaces; a scope can.
   - A type filter can't be a constraint. `alias` is globally unique today, so a WhatsApp "zerodha" alias next to the broker's "zerodha" would raise IntegrityError. `(scope, alias)` is enforceable in one table, and a concurrent first sighting still collides on it and is retried by `record_intake`.
   - `tips.source_channel_id` is immutable, so a wrong resolution can never be undone later. Scoping has to be exact when the alias is written. Admin merges and renames (Phase 5) change the canonical id or the name, never the medium an alias was seen on.
   - The seeded broker labels ("upstox", "zerodha", …) become APP_NOTIFICATION. The legacy `POST /tips` (APP_NOTIFICATION, label = `source`) and the capture list therefore behave exactly as before.
2. **Backfill (migration 0184):**
   - PACKAGE aliases, and the LABEL aliases of any channel that owns a package, become APP_NOTIFICATION. These are the seeded app names.
   - Every other label takes the medium its channel type was created for (`_CHANNEL_TYPE_FOR_MEDIUM` in reverse): SMS_SENDER → SMS, WHATSAPP_GROUP → WHATSAPP, TELEGRAM_CHANNEL → TELEGRAM, NEWS_PORTAL → MANUAL, BROKER_APP → APP_NOTIFICATION. NEWS_PORTAL maps to MANUAL because the admin paste is the only NEWS_PORTAL producer; no EMAIL client exists.
   - Mask-only labels are deleted. Their channels and tips stay (§2.5), and no future receipt can reach them.
   - Downgrade restores one global alias, keeping the oldest row for each alias text. Deleted mask-only aliases are not restored.
3. **Mask-only means:** the label contains at least one mask token and has no letter outside the masks. So "+1 [NUMBER]" counts as mask-only, while "Rahul [PHONE]" and "56161" do not.
   - For APP_NOTIFICATION with a package, a mask-only label is ignored and the package names the app.
   - Otherwise `resolve_channel` raises `MaskedChannelLabelError`, a subclass of `ChannelLabelRequiredError`, so the existing mapping in `api/services/tips.py` turns it into a 422 with no service change.
4. **`scope` has an ORM and server default, `APP_NOTIFICATION`**: the scope of every package and app name. Existing fixtures, including the Phase 2a/2b tests that build `ChannelAlias(..., kind=ALIAS_PACKAGE)`, stay valid. `resolve_channel` always passes the scope explicitly.
5. **The capture set on the phone:**
   - APP_NOTIFICATION: the package is on the cached capture list.
   - WhatsApp (`com.whatsapp`, `com.whatsapp.w4b`) and Telegram (`org.telegram.messenger`, `org.telegram.messenger.web`): the chat name is on the customer's allow-list. The allow-list is the existing local `WhatsAppSenderWatchlist` store, which Phase 4b presents as the chat allow-list.
   - SMS (`com.google.android.apps.messaging`, `com.android.mms`, `com.samsung.android.messaging`): the sender id is on the same allow-list. §5.1 gives SMS a label rule but names no SMS gate, and the allow-list is the customer's only consent.
   - Only rows the classifier already queues as TRADING are candidates, so OTPs never are. The existing check (`isTrading && category == TRADING && sourceKey` not blank) moves from `toMarksyTradingEventRequest` into the gate.
6. **Channel labels:**
   - App notifications use the app name (`sourceName`).
   - Chats use the first allow-listed candidate of the title. The candidates, in order, are the part after the last " @ " (Telegram's "Rahul @ StockTips"), the part before the last ": " (WhatsApp's "StockTips: Rahul"; see `Adapters.WHATSAPP`, `connector/ConnectorFramework.kt:64`), and then the whole title. So the individual sender is never part of the label.
   - SMS uses the sender id, with the TRAI DLT operator/circle prefix and type suffix removed ("JD-ZERODH-S" → "ZERODH"). One sender is then one channel across telecom circles.
   - The label then goes through the mirrored `channel_label_for`, and a mask-only result stays on the phone.
7. **Chat text carries no sender.**
   - The listener records MessagingStyle sender names in a local store (never sent), because the stored body already reads "Rahul: …".
   - For a chat row, the gate strips a known sender's "Name: " line prefix and masks that sender's other whole-word mentions as `[SENDER]`, a phone-only token. It does this for senders of this row, meaning those in its title or starting one of its lines.
   - Chat rows posted before sender tracking began (`captureSince`) stay local, because their senders are unknown.
8. **§15 1:1 labels:** an allow-listed 1:1 chat sends its chat name as the label, as §15 chose. The allow-list entry is the customer's consent, and without new storage the phone can't tell a 1:1 title from a single-message group title. A title that cleans to masks only never leaves the phone (Review Focus 1). This conflicts with the user's rule that contact names are never sent; see the hand-off note.
9. **`deviceEventKey`** is `"n1-" + sha256(salt|package|sourceKey)`. The salt is a random per-install value, so the key reveals nothing of the Android notification key, whose tag may hold a chat id.
   - Ingestion already gives each new call its own row and `sourceKey` (`"<key>#<hash>"`, `ConnectorFramework.kt:155,175`), so one row is one message.
   - The same row always yields the same key, and the server's unique `(user_id, device_event_key)` turns a retry into the stored receipt.
10. **Delivery states:**
    - Send → the POST, then DELIVERED with an insight.
    - Keep → NOT_APPLICABLE, shown as "Local only" and never retried. It logs a fixed reason code, never content.
    - Wait → PENDING, and the run ends with a retry.
    - HTTP errors keep today's policy: 422 and other 4xx → FAILED; 401, 403, 408, 425, 429, 5xx and IO errors → PENDING with WorkManager's exponential backoff.
11. **Response mapping:**
    - With a `tipId` (TIP, REVISION or a linked EXIT), the client still reads `GET /tips/{tipId}`, as today, for Marksy's comparison on the trading card.
    - Without one (UNPARSED, or an orphan EXIT), it stores a short local "recorded" insight.
    - If the POST succeeds but the GET fails, the row retries. The retried POST returns the same receipt, and the GET runs again.
12. **The legacy path:**
    - The app never calls `POST /tips` again.
    - `MarksyTipPayload`, `MarksyTipPayloadBuilder.from`, `MarksyTradingEventRequest` and `toMarksyTradingEventRequest` are deleted, so no code path can send a title or a body.
    - `MarksyTipPayloadBuilder.symbolOf` stays, because the trading card still uses it until Phase 4b.
    - The server's `POST /tips` is unchanged (§9 keeps it until its readers move).
13. **On-device parsing:**
    - `TradeCallParser`, `CallHorizon`, `DailySetups` and the classifier's trade-call rule keep running for the local UI, and as the TRADING pre-filter that decides what is queued at all. Nothing they compute is sent: the server parses.
    - When Phase 4b deletes the trade-call rule, it must replace that pre-filter.
14. **No kill switch or feature flag:**
    - Part B ships only after Task B1 Step 1 has confirmed Part A in production.
    - The server-side capture list is already a remote off switch for app notifications: setting `capture_enabled` to false empties it at the phone's next refresh. Chats need the customer's own allow-list entry.
    - Failures are per row (4xx) or retried (5xx and IO).
    - The app has one user, and a rollback is reinstalling the previous APK.
15. **The capture-list cache:**
    - It is refreshed at the start of a delivery run when it is older than 6 hours. A failed refresh keeps the cached list.
    - Until the first successful fetch the list is null, so app rows Wait.
    - An empty list sends nothing from apps.
16. **The username masked on the phone is the signed-in user id** (`AuthSessionStore.getUserId()`). The server masks `customer_id` the same way (`api/services/tips.py:152`).

## File Structure

**Part A (marksy-api)**
- Modify `app/tip_text_cleaning.py`: `is_mask_only`.
- Modify `app/tip_ledger.py`: `MaskedChannelLabelError`, and `resolve_channel` (the mask check, then scoped lookup and creation).
- Modify `app/models.py`: `ChannelAlias.scope` and `uq_channel_alias_scope_alias`.
- Create `migrations/versions/0184_channel_alias_scope.py`.
- Tests: modify `tests/test_tip_text_cleaning.py`, `tests/test_tip_ledger.py` and `tests/test_api_tips_ingest.py`; create `tests/test_channel_alias_scope_migration.py`.

**Part B (marksy-os, paths under `app/src/`)**
- Create:
  - `main/java/com/marksy/os/notification/TipTextCleaner.kt`: `CaptureMedium` and `TipTextCleaner`.
  - `main/java/com/marksy/os/notification/ChatLabels.kt`.
  - `main/java/com/marksy/os/gateway/CaptureStore.kt`.
  - `main/java/com/marksy/os/gateway/CaptureGate.kt`: `CapturedMessage`, `CaptureContext`, `CaptureDecision`, `CaptureGate` and `parseCaptureList`.
- Modify:
  - `main/java/com/marksy/os/notification/NotificationTextExtractor.kt`: `senders`.
  - `main/java/com/marksy/os/notification/MarksyNotificationListenerService.kt`: record senders and `captureSince`.
  - `main/java/com/marksy/os/gateway/MarksyGatewayClient.kt`, `MarksyTipsApiClient.kt`, `TradingDeliveryWorker.kt`, `MarksyTipPayload.kt` and `MarksyGatewayContract.kt`.
- Tests:
  - Create `test/java/com/marksy/os/notification/TipTextCleanerTest.kt` and `test/java/com/marksy/os/gateway/CaptureGateTest.kt`.
  - Modify `NotificationTextExtractorTest.kt`, `TradingDeliveryRunTest.kt`, `MarksyGatewayClientTest.kt` and `MarksyTipsApiClientTest.kt`.
  - Delete `MarksyTipPayloadFixtureTest.kt` and `MarksyTradingEventMappingTest.kt`.

---

## Part A — marksy-api: scoped channel labels

### Task A1: Worktree, and mask-only labels rejected

**Files:**
- Modify: `app/tip_text_cleaning.py`, adding code after `_GROUP_SENDER` and after `channel_label_for`.
- Modify: `app/tip_ledger.py`: the imports, the error classes, and `resolve_channel` (lines 129–162).
- Test: `tests/test_tip_text_cleaning.py`, `tests/test_tip_ledger.py`, `tests/test_api_tips_ingest.py`.

**Interfaces:**
- Produces:
  - `app.tip_text_cleaning.is_mask_only(label: str) -> bool`
  - `app.tip_ledger.MaskedChannelLabelError(ChannelLabelRequiredError)`
- `resolve_channel(session, *, medium, app_package, channel_label) -> Channel`: the signature is unchanged. It now raises `MaskedChannelLabelError` for a mask-only label unless APP_NOTIFICATION has a package to fall back on.
- `api/services/tips.py` is unchanged: `_record_and_compare` already maps `ChannelLabelRequiredError`, and so its new subclass, to a 422 `ValidationError`.

- [ ] **Step 1: Create the worktree and read the Alembic head**

```bash
cd /c/AIAgent/marksy-api && git fetch origin && git worktree add ../marksy-api-phase4a -b feat/tip-label-scope origin/main
cd /c/AIAgent/marksy-api-phase4a && git log --oneline -1 && python -m alembic heads
```

Expected: one head. It is `0183_tips_prediction_unique (head)` if Phases 2a and 2b have merged. Write the printed id down: Task A2 uses it as `down_revision`.
- If the head is a different single id (for example `0181_tip_ledger` or `0182_tip_daily_progress`), use it, and name it in the PR body.
- If there are two heads, stop and tell the user.

All later Part A commands run in `C:\AIAgent\marksy-api-phase4a`.

- [ ] **Step 2: Write the failing tests**

In `tests/test_tip_text_cleaning.py`:
- Add `import pytest` below `from __future__ import annotations`.
- Add `is_mask_only` to the `from app.tip_text_cleaning import (...)` block.
- Append:

```python
@pytest.mark.parametrize("label, expected", [
    ("[PHONE]", True), ("[phone]", True), ("+1 [NUMBER]", True), ("[USER], [EMAIL]", True),
    ("Rahul [PHONE]", False), ("StockTips", False), ("56161", False), ("", False),
])
def test_a_label_that_is_only_masks_digits_and_punctuation_names_no_channel(label, expected):
    assert is_mask_only(label) is expected


def test_a_mobile_number_split_by_a_no_break_space_is_masked():
    assert clean_tip_text("Queries: 98765\u00a043210", username=None) == f"Queries: {MASK_PHONE}"
```

In `tests/test_tip_ledger.py`:
- Change the `from app.tip_ledger import ...` line to `from app.tip_ledger import ChannelLabelRequiredError, Intake, MaskedChannelLabelError, ReceiptInput, ReservedChannelError, TipExtras, record_intake`.
- Add `MEDIUM_SMS` to the `from app.tip_vocabulary import (...)` block.
- Append:

```python
# --- Phase 4a: a mask-only label names nobody's channel ---


@pytest.mark.parametrize("medium, label", [
    (MEDIUM_WHATSAPP, "[PHONE]"), (MEDIUM_SMS, "[NUMBER]"), (MEDIUM_TELEGRAM, "[USER] [EMAIL]"),
])
def test_a_mask_only_label_is_rejected_and_pools_nothing(session, medium, label):
    for user, key in (("user-1", "e-1"), ("user-2", "e-2")):
        with pytest.raises(MaskedChannelLabelError):
            _tip_intake(session, _receipt(user=user, key=key, medium=medium, package=None, label=label), _terms())
    assert session.query(Channel).count() == 1 and session.query(TipReceipt).count() == 0


def test_a_mask_only_app_label_falls_back_to_the_package(session):
    tip = _tip_intake(session, _receipt(package="com.new.broker", label="[PHONE]"), _terms()).tip
    assert session.get(Channel, tip.source_channel_id).name == "com.new.broker"
    assert session.scalar(select(ChannelAlias).where(ChannelAlias.alias == "[phone]")) is None
```

Append to `tests/test_api_tips_ingest.py`:

```python
def test_a_chat_titled_with_a_phone_number_is_rejected_not_pooled(client, session):
    res = client.post("/api/v1/tips/ingest-text", json=_message(channelLabel="+91 98765 43210"), headers=_bearer(client))

    assert res.status_code == 422
    assert "masked personal data" in res.json()["error"]["message"]
    assert session.scalars(select(TipReceipt)).all() == []
```

- [ ] **Step 3: Run the tests to verify they fail**

Run: `python -m pytest tests/test_tip_text_cleaning.py tests/test_tip_ledger.py tests/test_api_tips_ingest.py -v`

Expected:
- A collection ERROR for `test_tip_text_cleaning.py` (`ImportError: cannot import name 'is_mask_only'`) and for `test_tip_ledger.py` (`cannot import name 'MaskedChannelLabelError'`).
- In `test_api_tips_ingest.py`, the new test FAILs: the status is 200 and a `[PHONE]` WHATSAPP_GROUP channel is created.

- [ ] **Step 4: Add `is_mask_only`**

In `app/tip_text_cleaning.py`, directly after `_GROUP_SENDER = " @ "`, add:

```python
_MASK_TOKEN = re.compile(r"\[(?:EMAIL|PHONE|PAN|NUMBER|USER)\]", re.IGNORECASE)
_LETTER = re.compile(r"[^\W\d_]")
```

At the end of the file, add:

```python
def is_mask_only(label: str) -> bool:
    """A cleaned label with nothing but masks, digits and punctuation left names a person, not a channel."""
    return _MASK_TOKEN.search(label) is not None and _LETTER.search(_MASK_TOKEN.sub("", label)) is None
```

- [ ] **Step 5: Reject mask-only labels in `resolve_channel`**

In `app/tip_ledger.py`:
- Directly after the `from .tip_matching import ...` line, add `from .tip_text_cleaning import is_mask_only`.
- Directly after `class ChannelLabelRequiredError`, add:

```python
class MaskedChannelLabelError(ChannelLabelRequiredError):
    """A label that is only masked personal data, e.g. a 1:1 chat titled with a phone number (Phase 4a)."""
```

In `resolve_channel`, directly after the `label = normalize_alias(...)` line, insert:

```python
    if label is not None and is_mask_only(label):
        if medium != MEDIUM_APP_NOTIFICATION or package is None:
            raise MaskedChannelLabelError(f"channel label '{label}' is only masked personal data, not a channel")
        label = channel_label = None
```

The rest of the function is unchanged. With `channel_label` cleared, a new channel takes its name from the package.

- [ ] **Step 6: Run the tests to verify they pass**

Run: `python -m pytest tests/test_tip_text_cleaning.py tests/test_tip_ledger.py tests/test_api_tips_ingest.py -v`
Expected: all PASS.

- [ ] **Step 7: Commit**

```bash
git add app/tip_text_cleaning.py app/tip_ledger.py tests/test_tip_text_cleaning.py tests/test_tip_ledger.py tests/test_api_tips_ingest.py
git commit -m "Tip ledger: reject channel labels that are only masked personal data

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task A2: Migration 0184 — alias scope, backfill, mask-only cleanup

**Files:**
- Create: `migrations/versions/0184_channel_alias_scope.py`
- Test: `tests/test_channel_alias_scope_migration.py`

**Interfaces:**
- Produces revision `0184_channel_alias_scope`. Its `down_revision` is the head printed in Task A1 Step 1.
- Adds the column `channel_aliases.scope VARCHAR(24) NOT NULL DEFAULT 'APP_NOTIFICATION'`.
- Adds the unique constraint `uq_channel_alias_scope_alias (scope, alias)`, replacing `uq_channel_alias_alias (alias)`.

- [ ] **Step 1: Write the failing migration test**

Create `tests/test_channel_alias_scope_migration.py`:

```python
"""0184_channel_alias_scope on SQLite: labels keep the medium they were seen on, mask-only labels go,
and one alias text may exist once per scope."""
from __future__ import annotations

import pytest
import sqlalchemy as sa
from sqlalchemy.exc import IntegrityError

from tests._migration_helpers import run_revision

_CHANNELS = (
    (1, "Zerodha", "BROKER_APP"),
    (2, "StockTips", "WHATSAPP_GROUP"),
    (3, "VM-ZERODH", "SMS_SENDER"),
    (4, "AiTradingAgent", "NEWS_PORTAL"),
    (5, "[PHONE]", "WHATSAPP_GROUP"),
    (6, "Moneycontrol", "NEWS_PORTAL"),
    (7, "WhatsApp", "WHATSAPP_GROUP"),
    (8, "New Broker", "BROKER_APP"),
)
_ALIASES = (
    (1, "com.zerodha.kite3", "PACKAGE"),
    (1, "zerodha", "LABEL"),
    (2, "stocktips", "LABEL"),
    (3, "vm-zerodh", "LABEL"),
    (4, "aitradingagent", "LABEL"),
    (5, "[phone]", "LABEL"),
    (6, "com.divum.moneycontrol", "PACKAGE"),
    (6, "moneycontrol", "LABEL"),
    (7, "com.whatsapp", "PACKAGE"),
    (7, "whatsapp", "LABEL"),
    (8, "new broker", "LABEL"),
)


@pytest.fixture
def engine():
    engine = sa.create_engine("sqlite:///:memory:")
    with engine.begin() as conn:
        conn.execute(sa.text("CREATE TABLE channels (id INTEGER PRIMARY KEY, name VARCHAR(128) NOT NULL, type VARCHAR(24) NOT NULL)"))
        conn.execute(sa.text(
            "CREATE TABLE channel_aliases (id INTEGER PRIMARY KEY, channel_id INTEGER NOT NULL REFERENCES channels (id),"
            " alias VARCHAR(128) NOT NULL, kind VARCHAR(16) NOT NULL, created_at DATETIME,"
            " CONSTRAINT uq_channel_alias_alias UNIQUE (alias))"
        ))
        conn.execute(sa.text("CREATE INDEX ix_channel_aliases_channel_id ON channel_aliases (channel_id)"))
        conn.execute(
            sa.text("INSERT INTO channels (id, name, type) VALUES (:id, :name, :type)"),
            [{"id": i, "name": n, "type": t} for i, n, t in _CHANNELS],
        )
        conn.execute(
            sa.text("INSERT INTO channel_aliases (channel_id, alias, kind) VALUES (:channel, :alias, :kind)"),
            [{"channel": c, "alias": a, "kind": k} for c, a, k in _ALIASES],
        )
        run_revision(conn, "0184_channel_alias_scope")
    return engine


def _scopes(conn):
    return dict(conn.execute(sa.text("SELECT alias, scope FROM channel_aliases")).all())


def test_existing_aliases_take_the_medium_their_channel_was_created_for(engine):
    with engine.begin() as conn:
        assert _scopes(conn) == {
            "com.zerodha.kite3": "APP_NOTIFICATION", "zerodha": "APP_NOTIFICATION",
            "stocktips": "WHATSAPP", "vm-zerodh": "SMS", "aitradingagent": "MANUAL",
            "com.divum.moneycontrol": "APP_NOTIFICATION", "moneycontrol": "APP_NOTIFICATION",
            "com.whatsapp": "APP_NOTIFICATION", "whatsapp": "APP_NOTIFICATION",
            "new broker": "APP_NOTIFICATION",
        }


def test_a_mask_only_label_is_removed_but_its_channel_stays(engine):
    with engine.begin() as conn:
        assert "[phone]" not in _scopes(conn)
        assert conn.execute(sa.text("SELECT name FROM channels WHERE id = 5")).scalar_one() == "[PHONE]"


def test_one_alias_text_may_exist_once_per_scope(engine):
    insert = sa.text("INSERT INTO channel_aliases (channel_id, alias, kind, scope) VALUES (2, 'zerodha', 'LABEL', 'WHATSAPP')")
    with engine.begin() as conn:
        conn.execute(insert)
    with pytest.raises(IntegrityError), engine.begin() as conn:
        conn.execute(insert)


def test_downgrade_restores_one_global_alias_keeping_the_oldest(engine):
    with engine.begin() as conn:
        conn.execute(sa.text("INSERT INTO channel_aliases (channel_id, alias, kind, scope) VALUES (2, 'zerodha', 'LABEL', 'WHATSAPP')"))
        run_revision(conn, "0184_channel_alias_scope", "downgrade")
        assert conn.execute(sa.text("SELECT channel_id, alias FROM channel_aliases WHERE alias = 'zerodha'")).all() == [(1, "zerodha")]
        assert "scope" not in {column["name"] for column in sa.inspect(conn).get_columns("channel_aliases")}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `python -m pytest tests/test_channel_alias_scope_migration.py -v`
Expected: 4 ERROR in fixture setup, with `FileNotFoundError` for `0184_channel_alias_scope.py`.

- [ ] **Step 3: Write the migration**

Create `migrations/versions/0184_channel_alias_scope.py`. If Task A1 Step 1 printed a head other than `0183_tips_prediction_unique`, put that id in `down_revision`.

```python
"""Channel aliases are scoped by medium (tip-ledger Phase 4a).

One global alias namespace let a WhatsApp group named "Zerodha" resolve to the Zerodha broker, and a
masked label like "[PHONE]" pool unrelated 1:1 chats into one channel. `tips.source_channel_id` is
immutable, so neither could be undone. An alias is now unique per `scope`: the medium whose names it
belongs to. Package names and app names are APP_NOTIFICATION.

Backfill: package aliases, and the labels of channels that own a package (the seeded app names), are
APP_NOTIFICATION. Any other label takes the medium its channel type was created for: SMS_SENDER -> SMS,
WHATSAPP_GROUP -> WHATSAPP, TELEGRAM_CHANNEL -> TELEGRAM, NEWS_PORTAL -> MANUAL (the admin paste; no
EMAIL client exists), BROKER_APP -> APP_NOTIFICATION. Mask-only labels are deleted; their channels
and tips stay.

Downgrade restores the global unique alias, keeping the oldest row of each alias text. Deleted
mask-only aliases are not restored.
"""
from __future__ import annotations

import re

import sqlalchemy as sa
from alembic import op

revision = "0184_channel_alias_scope"
down_revision = "0183_tips_prediction_unique"
branch_labels = None
depends_on = None

# Copied from app/tip_text_cleaning.py, not imported: a migration must replay identically after that module changes.
_MASK_TOKEN = re.compile(r"\[(?:EMAIL|PHONE|PAN|NUMBER|USER)\]", re.IGNORECASE)
_LETTER = re.compile(r"[^\W\d_]")
_LABEL_SCOPE_BY_CHANNEL_TYPE = (
    "CASE (SELECT c.type FROM channels c WHERE c.id = channel_aliases.channel_id)"
    " WHEN 'SMS_SENDER' THEN 'SMS' WHEN 'WHATSAPP_GROUP' THEN 'WHATSAPP'"
    " WHEN 'TELEGRAM_CHANNEL' THEN 'TELEGRAM' WHEN 'NEWS_PORTAL' THEN 'MANUAL' ELSE 'APP_NOTIFICATION' END"
)


def _mask_only(alias: str) -> bool:
    return _MASK_TOKEN.search(alias) is not None and _LETTER.search(_MASK_TOKEN.sub("", alias)) is None


def upgrade() -> None:
    op.add_column(
        "channel_aliases",
        sa.Column("scope", sa.String(24), nullable=False, server_default="APP_NOTIFICATION"),
    )
    op.execute(
        f"UPDATE channel_aliases SET scope = {_LABEL_SCOPE_BY_CHANNEL_TYPE}"
        " WHERE kind = 'LABEL' AND NOT EXISTS (SELECT 1 FROM channel_aliases p"
        " WHERE p.channel_id = channel_aliases.channel_id AND p.kind = 'PACKAGE')"
    )
    bind = op.get_bind()
    labels = bind.execute(sa.text("SELECT id, alias FROM channel_aliases WHERE kind = 'LABEL'")).all()
    masked = [row.id for row in labels if _mask_only(row.alias)]
    if masked:
        bind.execute(
            sa.text("DELETE FROM channel_aliases WHERE id IN :ids").bindparams(sa.bindparam("ids", expanding=True)),
            {"ids": masked},
        )
    with op.batch_alter_table("channel_aliases") as batch:
        batch.drop_constraint("uq_channel_alias_alias", type_="unique")
        batch.create_unique_constraint("uq_channel_alias_scope_alias", ["scope", "alias"])


def downgrade() -> None:
    op.execute("DELETE FROM channel_aliases WHERE id NOT IN (SELECT MIN(id) FROM channel_aliases GROUP BY alias)")
    with op.batch_alter_table("channel_aliases") as batch:
        batch.drop_constraint("uq_channel_alias_scope_alias", type_="unique")
        batch.create_unique_constraint("uq_channel_alias_alias", ["alias"])
        batch.drop_column("scope")
```

- [ ] **Step 4: Run the migration tests and check the graph**

```bash
python -m pytest tests/test_channel_alias_scope_migration.py tests/test_tip_ledger_migration.py tests/test_alembic_single_head.py -v
python -m alembic heads
```

Expected: all PASS, and exactly one head, `0184_channel_alias_scope`.

- [ ] **Step 5: Commit**

```bash
git add migrations/versions/0184_channel_alias_scope.py tests/test_channel_alias_scope_migration.py
git commit -m "Tip ledger: migration 0184 scopes channel aliases by medium and drops mask-only labels

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task A3: Scoped channel resolution (ORM + `resolve_channel`)

**Files:**
- Modify: `app/models.py` (`class ChannelAlias`, line 5269)
- Modify: `app/tip_ledger.py` (`resolve_channel`)
- Test: `tests/test_tip_ledger.py`

**Interfaces:**
- Produces: `ChannelAlias.scope: Mapped[str]`, defaulting to `"APP_NOTIFICATION"`, and `__table_args__ = (UniqueConstraint("scope", "alias", name="uq_channel_alias_scope_alias"),)`.
- `resolve_channel(session, *, medium, app_package, channel_label) -> Channel`: the signature is unchanged. It looks up and creates aliases with `scope == medium`.

- [ ] **Step 1: Write the failing tests**

Append to `tests/test_tip_ledger.py`:

```python
# --- Phase 4a: a label only resolves among aliases seen on the same medium ---


def test_a_whatsapp_group_named_zerodha_is_not_the_zerodha_broker(session):
    zerodha = Channel(name="Zerodha", type=CHANNEL_BROKER_APP, default_horizon_sessions=20, capture_enabled=True)
    session.add(zerodha)
    session.flush()
    session.add(ChannelAlias(channel_id=zerodha.id, alias="zerodha", kind=ALIAS_LABEL))
    session.commit()

    group = _tip_intake(session, _receipt(medium=MEDIUM_WHATSAPP, package="com.whatsapp", label="Zerodha"), _terms()).tip
    broker = _tip_intake(session, _receipt(user="user-2", key="e-2", package=None, label="Zerodha"), _terms()).tip

    assert session.get(Channel, group.source_channel_id).type == CHANNEL_WHATSAPP_GROUP
    assert broker.source_channel_id == zerodha.id and broker.id != group.id
    scopes = set(session.execute(select(ChannelAlias.alias, ChannelAlias.scope).where(ChannelAlias.alias == "zerodha")).all())
    assert scopes == {("zerodha", MEDIUM_APP_NOTIFICATION), ("zerodha", MEDIUM_WHATSAPP)}


def test_an_app_label_never_resolves_to_a_whatsapp_group_of_the_same_name(session):
    group = _tip_intake(session, _receipt(medium=MEDIUM_WHATSAPP, package="com.whatsapp", label="StockTips"), _terms()).tip
    app = _tip_intake(session, _receipt(user="user-2", key="e-2", package="com.stocktips.app", label="StockTips"), _terms()).tip

    assert session.get(Channel, app.source_channel_id).type == CHANNEL_BROKER_APP
    assert app.source_channel_id != group.source_channel_id
```

- [ ] **Step 2: Run them to verify they fail**

Run: `python -m pytest tests/test_tip_ledger.py -v -k "zerodha or same_name"`

Expected: 2 FAIL.
- The Zerodha test: `assert 'BROKER_APP' == 'WHATSAPP_GROUP'`. The group landed on the broker, which is the review's bug.
- The StockTips test: `assert 'WHATSAPP_GROUP' == 'BROKER_APP'`.

- [ ] **Step 3: Add `scope` to the ORM**

In `app/models.py`, replace `class ChannelAlias` with:

```python
class ChannelAlias(Base):
    """A package name or label that resolves to a channel (tip-ledger spec §4), unique within its medium."""

    __tablename__ = "channel_aliases"
    __table_args__ = (UniqueConstraint("scope", "alias", name="uq_channel_alias_scope_alias"),)
    id: Mapped[int] = mapped_column(BigInteger().with_variant(Integer, "sqlite"), primary_key=True)
    channel_id: Mapped[int] = mapped_column(ForeignKey("channels.id"), index=True)
    alias: Mapped[str] = mapped_column(String(128))
    kind: Mapped[str] = mapped_column(String(16))
    # The medium whose names this is (Phase 4a); packages and app names are APP_NOTIFICATION.
    scope: Mapped[str] = mapped_column(String(24), default="APP_NOTIFICATION", server_default="APP_NOTIFICATION")
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=func.now())
```

Then delete the stale test DB: `rm -f "$TEMP/marksy-pytest-default.db"`.

- [ ] **Step 4: Scope the lookup and the new aliases**

In `app/tip_ledger.py`, replace `resolve_channel` with:

```python
def resolve_channel(session: Session, *, medium: str, app_package: str | None, channel_label: str | None) -> Channel:
    """App notifications by package then label; every other medium by label only (binding decision 9).
    An alias only resolves among aliases seen on the same medium, so one medium's names never capture another's."""
    package = normalize_alias(app_package) if app_package and app_package.strip() else None
    label = normalize_alias(channel_label) if channel_label and channel_label.strip() else None
    if label is not None and is_mask_only(label):
        if medium != MEDIUM_APP_NOTIFICATION or package is None:
            raise MaskedChannelLabelError(f"channel label '{label}' is only masked personal data, not a channel")
        label = channel_label = None
    if medium == MEDIUM_APP_NOTIFICATION:
        keys = [(alias, kind) for alias, kind in ((package, ALIAS_PACKAGE), (label, ALIAS_LABEL)) if alias]
        if not keys:
            raise ChannelLabelRequiredError("a receipt needs an app package or a channel label")
    else:
        if not label:
            raise ChannelLabelRequiredError(f"a {medium} receipt needs a channel label")
        keys = [(label, ALIAS_LABEL)]
    for alias, _kind in keys:
        channel = session.scalar(
            select(Channel)
            .join(ChannelAlias, ChannelAlias.channel_id == Channel.id)
            .where(ChannelAlias.scope == medium, ChannelAlias.alias == alias)
        )
        if channel is not None:
            if channel.type == CHANNEL_MARKSY:
                raise ReservedChannelError(f"'{alias}' names Marksy's own channel")
            return channel
    name = ((channel_label or "").strip() or (app_package or "").strip())[:_ALIAS_MAX]
    channel = Channel(
        name=name,
        type=_CHANNEL_TYPE_FOR_MEDIUM[medium],
        default_horizon_sessions=DEFAULT_HORIZON_SESSIONS,
        capture_enabled=False,
    )
    session.add(channel)
    session.flush()
    channel.canonical_channel_id = channel.id
    session.add_all([ChannelAlias(channel_id=channel.id, alias=alias, kind=kind, scope=medium) for alias, kind in keys])
    session.flush()
    return channel
```

- [ ] **Step 5: Scope the reserved-channel test's alias**

`test_a_customer_label_never_lands_on_marksys_channel` adds a Marksy alias that a Telegram label must hit. Scoped, it has to be a Telegram alias. In that test, change:

```python
    session.add(ChannelAlias(channel_id=marksy.id, alias="marksy official", kind=ALIAS_LABEL))
```

to:

```python
    session.add(ChannelAlias(channel_id=marksy.id, alias="marksy official", kind=ALIAS_LABEL, scope=MEDIUM_TELEGRAM))
```

- [ ] **Step 6: Run the ledger and API tests**

Run: `python -m pytest tests/test_tip_ledger.py tests/test_api_tips_ingest.py tests/test_api_channels.py tests/test_tip_text_cleaning.py -v`
Expected: all PASS, including the Phase 1 tests (a WhatsApp group is not the WhatsApp app, the capture list is unchanged, and retries return the same receipt).

- [ ] **Step 7: Commit**

```bash
git add app/models.py app/tip_ledger.py tests/test_tip_ledger.py
git commit -m "Tip ledger: resolve channel labels only among aliases of the same medium

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task A4: Regression set, PR, merge, deploy, verify

**Files:** none new.

- [ ] **Step 1: Run the regression set**

```bash
rm -f "$TEMP/marksy-pytest-default.db"
python -m pytest tests/test_tip_*.py tests/test_channel_alias_scope_migration.py tests/test_api_tips*.py \
  tests/test_api_channels.py tests/test_external_tip_*.py tests/test_alembic_single_head.py tests/test_read_only_principal.py -v
python -m alembic heads
```

Expected: all PASS, and one head, `0184_channel_alias_scope`. The `tests/test_tip_*.py` glob also covers the Phase 2a/2b files if they are on main; their `ChannelAlias(...)` fixtures take the APP_NOTIFICATION default. Handle any failure as the Global Constraints describe.

- [ ] **Step 2: Push and open the PR**

If Task A1 Step 1 printed a head other than `0183_tips_prediction_unique`, add one line to the body. The line names that printed id as the parent of 0184, and says that any unmerged migration branch must re-parent onto `0184_channel_alias_scope`.

```bash
git push -u origin feat/tip-label-scope
gh pr create --title "Tip ledger Phase 4a (A): scope channel labels by medium, reject masked labels" --body "$(cat <<'EOF'
Fixes the Phase 1 review finding that blocks phone capture: LABEL aliases were one global namespace, and
`source_channel_id` is immutable, so a wrong resolution could never be undone.

- A cleaned label made only of masks (a 1:1 chat titled with a phone number, a numeric SMS sender) is rejected
  with 422 instead of pooling unrelated chats into one channel; an app notification with such a label resolves by its package
- `channel_aliases.scope` (migration 0184): an alias is unique per medium, so a WhatsApp group named "Zerodha" gets its
  own channel and never the broker's, and an app label never lands on a WhatsApp group
- Backfill: packages and app-name labels are APP_NOTIFICATION; other labels take the medium their channel type was
  created for; mask-only labels are removed (their channels and tips stay)

Tests: tip text cleaning, tip ledger, alias-scope migration, ingest-text and capture-list API, tip regression files, single alembic head.

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```

- [ ] **Step 3: Merge and deploy**

```bash
gh pr merge --merge --delete-branch
ssh marksy@103.178.160.233 'bash ~/deployment/server/vps-deploy.sh'
```

Expected: the deploy script finishes, and its migrate Job applies `0184_channel_alias_scope`.

- [ ] **Step 4: Verify production**

```bash
echo "select version_num from alembic_version; select scope, kind, count(*) from channel_aliases group by scope, kind order by scope, kind; select conname from pg_constraint where conrelid = 'channel_aliases'::regclass and contype = 'u';" \
  | ssh marksy@103.178.160.233 'KUBECONFIG=~/.kube/config kubectl -n market-agent exec -i deploy/postgres -- sh -c '"'"'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB"'"'"''
```

Expected:
- `alembic_version` is `0184_channel_alias_scope`.
- `APP_NOTIFICATION | PACKAGE` is at least 16, and `APP_NOTIFICATION | LABEL` is at least 12 (the seeds).
- Any `MANUAL | LABEL` rows are admin-paste sources.
- The only unique constraint is `uq_channel_alias_scope_alias`.

- [ ] **Step 5: Clean up the worktree**

```bash
cd /c/AIAgent/marksy-api && git worktree remove ../marksy-api-phase4a && git checkout main && git pull
```

---

## Part B — marksy-os: capture and the §5.1 payload

### Task B1: Production gate, worktree, and the tip text cleaner

**Files:**
- Create: `app/src/main/java/com/marksy/os/notification/TipTextCleaner.kt`
- Test: `app/src/test/java/com/marksy/os/notification/TipTextCleanerTest.kt`

**Interfaces:**
- Produces:
  - `enum class CaptureMedium { APP_NOTIFICATION, SMS, WHATSAPP, TELEGRAM }`, with `val isChat: Boolean` and `companion fun of(packageName: String): CaptureMedium`.
  - `object TipTextCleaner`, with:
    - `const val MASK_EMAIL`, `MASK_PHONE`, `MASK_PAN`, `MASK_NUMBER`, `MASK_USER` and `LABEL_MAX_LENGTH`
    - `fun clean(text: String, username: String?): String`, which mirrors `clean_tip_text`
    - `fun channelLabel(medium: CaptureMedium, label: String?, username: String?): String?`, which mirrors `channel_label_for`
    - `fun isMaskOnly(label: String): Boolean`, which mirrors `is_mask_only`

- [ ] **Step 1: Confirm Part A is in production**

```bash
echo "select count(*) from information_schema.columns where table_name = 'channel_aliases' and column_name = 'scope';" \
  | ssh marksy@103.178.160.233 'KUBECONFIG=~/.kube/config kubectl -n market-agent exec -i deploy/postgres -- sh -c '"'"'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -tA'"'"''
```

Expected: `1`. If it prints `0`, **stop and tell the user**: Part A is not deployed, and a WhatsApp group would still pool into a broker channel.

- [ ] **Step 2: Create the worktree**

```bash
cd /c/AIAgent/marksy-os && git fetch origin && git worktree add ../marksy-os-phase4a -b feat/tip-capture-payload origin/main
cp /c/AIAgent/marksy-os/local.properties /c/AIAgent/marksy-os-phase4a/local.properties
cd /c/AIAgent/marksy-os-phase4a && git log --oneline -1
```

`local.properties` holds `sdk.dir`, is untracked, and git ignores it. All later Part B commands run in `C:\AIAgent\marksy-os-phase4a`.

- [ ] **Step 3: Write the failing test**

Create `app/src/test/java/com/marksy/os/notification/TipTextCleanerTest.kt`:

```kotlin
package com.marksy.os.notification

import com.marksy.os.notification.TipTextCleaner.MASK_EMAIL
import com.marksy.os.notification.TipTextCleaner.MASK_NUMBER
import com.marksy.os.notification.TipTextCleaner.MASK_PAN
import com.marksy.os.notification.TipTextCleaner.MASK_PHONE
import com.marksy.os.notification.TipTextCleaner.MASK_USER
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// Same vectors as marksy-api tests/test_tip_text_cleaning.py; change both files together.
class TipTextCleanerTest {
    @Test
    fun contactDetailsAndTheUsernameAreMasked() {
        val text = "Priya, call +91 98765 43210 or 9123456780, mail priya.k@example.com"
        assertEquals("$MASK_USER, call $MASK_PHONE or $MASK_PHONE, mail $MASK_EMAIL", TipTextCleaner.clean(text, "priya"))
    }

    @Test
    fun panCodesAndLongDigitRunsAreMasked() {
        assertEquals("PAN $MASK_PAN folio $MASK_NUMBER", TipTextCleaner.clean("PAN ABCDE1234F folio 1234567890123", null))
    }

    @Test
    fun pricesStrikesAndShortNumbersSurviveCleaning() {
        val text = "BUY RENUKA CMP : 23.62 SL : 22.25 TGT : 26 | SELL NIFTY 24500 PE @ 120 | Qty 1500"
        assertEquals(text, TipTextCleaner.clean(text, "user-1"))
    }

    @Test
    fun aMobileNumberBesidePricesIsMaskedWithoutTouchingThePrices() {
        val text = "BUY TATASTEEL CMP 612.50 SL 598 TGT 650. Queries: 98765 43210"
        assertEquals("BUY TATASTEEL CMP 612.50 SL 598 TGT 650. Queries: $MASK_PHONE", TipTextCleaner.clean(text, null))
    }

    @Test
    fun aMobileNumberSplitByANoBreakSpaceIsMasked() {
        assertEquals("Queries: $MASK_PHONE", TipTextCleaner.clean("Queries: 98765\u00a043210", null))
    }

    @Test
    fun theUsernameIsMaskedOnlyAsAWholeWordAndOnlyWhenLongEnough() {
        assertEquals("$MASK_USER: BUY RAVIKUMAR CMP 10 SL 9", TipTextCleaner.clean("Ravi: BUY RAVIKUMAR CMP 10 SL 9", "ravi"))
        assertEquals("al buys ALKEM", TipTextCleaner.clean("al buys ALKEM", "al"))
    }

    @Test
    fun aGroupLabelDropsTheIndividualSender() {
        assertEquals("StockTips", TipTextCleaner.channelLabel(CaptureMedium.WHATSAPP, "Rahul @ StockTips", "user-1"))
    }

    @Test
    fun aOneToOneChatTitledWithAPhoneNumberIsMasked() {
        assertEquals(MASK_PHONE, TipTextCleaner.channelLabel(CaptureMedium.WHATSAPP, "+91 98765 43210", "user-1"))
    }

    @Test
    fun anAppLabelIsKeptWholeAndABlankLabelIsNull() {
        assertEquals("Angel @ One", TipTextCleaner.channelLabel(CaptureMedium.APP_NOTIFICATION, "Angel @ One", null))
        assertNull(TipTextCleaner.channelLabel(CaptureMedium.SMS, "   ", null))
    }

    @Test
    fun aLabelThatIsOnlyMasksDigitsAndPunctuationNamesNoChannel() {
        mapOf(
            "[PHONE]" to true, "[phone]" to true, "+1 [NUMBER]" to true, "[USER], [EMAIL]" to true,
            "Rahul [PHONE]" to false, "StockTips" to false, "56161" to false, "" to false
        ).forEach { (label, expected) -> assertEquals(label, expected, TipTextCleaner.isMaskOnly(label)) }
    }
}
```

- [ ] **Step 4: Run it to verify it fails**

Run the unit-test command with `--tests 'com.marksy.os.notification.TipTextCleanerTest'`.
Expected: FAIL at `compileDebugUnitTestKotlin` with `Unresolved reference 'TipTextCleaner'`.

- [ ] **Step 5: Write the cleaner**

Create `app/src/main/java/com/marksy/os/notification/TipTextCleaner.kt`:

```kotlin
package com.marksy.os.notification

import java.util.Locale

/** The medium of a captured message, spelled as `POST /tips/ingest-text` expects (tip-ledger spec §4). */
enum class CaptureMedium {
    APP_NOTIFICATION, SMS, WHATSAPP, TELEGRAM;

    val isChat: Boolean get() = this == WHATSAPP || this == TELEGRAM

    companion object {
        private val WHATSAPP_PACKAGES = setOf("com.whatsapp", "com.whatsapp.w4b")
        private val TELEGRAM_PACKAGES = setOf("org.telegram.messenger", "org.telegram.messenger.web")
        private val SMS_PACKAGES = setOf("com.google.android.apps.messaging", "com.android.mms", "com.samsung.android.messaging")

        fun of(packageName: String): CaptureMedium = when (packageName.trim().lowercase(Locale.ROOT)) {
            in WHATSAPP_PACKAGES -> WHATSAPP
            in TELEGRAM_PACKAGES -> TELEGRAM
            in SMS_PACKAGES -> SMS
            else -> APP_NOTIFICATION
        }
    }
}

/** Mirrors marksy-api `app/tip_text_cleaning.py` rule for rule (tip-ledger spec §5.1); the server re-applies it. */
object TipTextCleaner {
    const val MASK_EMAIL = "[EMAIL]"
    const val MASK_PHONE = "[PHONE]"
    const val MASK_PAN = "[PAN]"
    const val MASK_NUMBER = "[NUMBER]"
    const val MASK_USER = "[USER]"
    const val LABEL_MAX_LENGTH = 128

    // Python's \w, \d and \s are Unicode-aware; spelled out so the JVM and Android ICU agree with them.
    private const val WORD = "\\p{L}\\p{N}_"
    private const val SPACE_OR_DASH = "[\\s\\p{Z}-]"
    private const val GROUP_SENDER = " @ "
    private val EMAIL = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")
    private val PHONE = Regex("(?<![$WORD+])(?:(?:\\+91|0091|91|0)$SPACE_OR_DASH?)?[6-9]\\p{Nd}{4}$SPACE_OR_DASH?\\p{Nd}{5}(?![$WORD])")
    private val PAN = Regex("(?<![$WORD])[A-Z]{5}[0-9]{4}[A-Z](?![$WORD])")
    private val DIGIT_RUN = Regex("\\p{Nd}{8,}")
    private val MASK_TOKEN = Regex("\\[(?:EMAIL|PHONE|PAN|NUMBER|USER)\\]", RegexOption.IGNORE_CASE)
    private val LETTER = Regex("[\\p{L}\\p{Nl}\\p{No}]")

    fun clean(text: String, username: String?): String {
        var cleaned = EMAIL.replace(text, MASK_EMAIL)
        cleaned = PHONE.replace(cleaned, MASK_PHONE)
        cleaned = PAN.replace(cleaned, MASK_PAN)
        cleaned = DIGIT_RUN.replace(cleaned, MASK_NUMBER)
        val name = username?.trim().orEmpty()
        if (name.length >= 3) {
            cleaned = Regex("(?<![$WORD])${Regex.escape(name)}(?![$WORD])", RegexOption.IGNORE_CASE).replace(cleaned, MASK_USER)
        }
        return cleaned
    }

    /** The channel a message came through, never the person who sent it inside a group. */
    fun channelLabel(medium: CaptureMedium, label: String?, username: String?): String? {
        if (label.isNullOrBlank()) return null
        var value = label.trim()
        if (medium.isChat && GROUP_SENDER in value) value = value.substringAfterLast(GROUP_SENDER).trim()
        return clean(value, username).take(LABEL_MAX_LENGTH).ifEmpty { null }
    }

    fun isMaskOnly(label: String): Boolean =
        MASK_TOKEN.containsMatchIn(label) && !LETTER.containsMatchIn(MASK_TOKEN.replace(label, ""))
}
```

- [ ] **Step 6: Run it to verify it passes**

Run the unit-test command with `--tests 'com.marksy.os.notification.TipTextCleanerTest'`.
Expected: 10 PASS.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/marksy/os/notification/TipTextCleaner.kt app/src/test/java/com/marksy/os/notification/TipTextCleanerTest.kt
git commit -m "Tip capture: tip text cleaner mirroring the server's masks, same test vectors

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task B2: Chat senders recorded on the phone

**Files:**
- Modify: `app/src/main/java/com/marksy/os/notification/NotificationTextExtractor.kt`: `messageLines` at lines 95–112, and a new `senders` after `extract`.
- Create: `app/src/main/java/com/marksy/os/gateway/CaptureStore.kt`
- Modify: `app/src/main/java/com/marksy/os/notification/MarksyNotificationListenerService.kt` (`onListenerConnected`, `captureUnsafe`)
- Test: `app/src/test/java/com/marksy/os/notification/NotificationTextExtractorTest.kt`

**Interfaces:**
- Produces:
  - `NotificationTextExtractor.senders(extras: Bundle): List<String>`
  - `class CaptureStore(context: Context)`, with:
    - `deviceSalt(): String`
    - `captureSince(now: Long): Long`
    - `capturePackages(): Set<String>?`
    - `isCaptureListStale(now: Long): Boolean`
    - `saveCapturePackages(packages: Set<String>, now: Long)`
    - `chatSenders(): Set<String>`
    - `rememberChatSenders(senders: Collection<String>)`
- Consumes: `CaptureMedium` (Task B1).

- [ ] **Step 1: Write the failing test**

Append to `NotificationTextExtractorTest`, after `includesMessagingStyleHistoryWithSenders`:

```kotlin
    // Tip capture strips these from chat text before anything leaves the phone.
    @Test
    fun sendersListsEachMessagingStyleSenderOnce() {
        val extras = Bundle().apply {
            putParcelableArray("android.messages", arrayOf(
                Bundle().apply { putCharSequence("sender", "Mom"); putCharSequence("text", "Dinner tonight?") },
                Bundle().apply { putCharSequence("sender", "Dad"); putCharSequence("text", "See you at 8") },
                Bundle().apply { putCharSequence("sender", "Mom"); putCharSequence("text", "Bring bread") }
            ))
        }

        assertEquals(listOf("Mom", "Dad"), NotificationTextExtractor.senders(extras))
    }
```

- [ ] **Step 2: Run it to verify it fails**

Run the unit-test command with `--tests 'com.marksy.os.notification.NotificationTextExtractorTest'`.
Expected: FAIL at compile with `Unresolved reference 'senders'`.

- [ ] **Step 3: Share the MessagingStyle parsing and add `senders`**

In `NotificationTextExtractor.kt`, replace the whole `messageLines` function, from `@Suppress("DEPRECATION")` down to its closing brace, with:

```kotlin
    @Suppress("DEPRECATION")
    private fun messageBundles(extras: Bundle): Sequence<Bundle> {
        val raw: Array<Parcelable>? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            extras.getParcelableArray("android.messages", Parcelable::class.java)
        } else {
            extras.getParcelableArray("android.messages")
        }
        return raw.orEmpty().asSequence().mapNotNull { it as? Bundle }
    }

    private fun senderName(message: Bundle): String? =
        message.getCharSequence("sender")?.toString()?.trim()
            ?: if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) senderPerson(message)?.name?.toString()?.trim() else null

    private fun messageLines(extras: Bundle): Sequence<String> = messageBundles(extras).mapNotNull { message ->
        val body = message.getCharSequence("text")?.toString()?.trim().orEmpty()
        if (body.isBlank()) return@mapNotNull null
        val sender = senderName(message)
        if (sender.isNullOrBlank()) body else "$sender: $body"
    }
```

Directly after the `extract` function, add:

```kotlin
    /** Distinct MessagingStyle sender names, as they appear in the extracted body. */
    fun senders(extras: Bundle): List<String> =
        messageBundles(extras).mapNotNull { senderName(it)?.let(::stripMarkup)?.trim() }
            .filter { it.isNotBlank() }.distinct().toList()
```

- [ ] **Step 4: Add the local capture store**

Create `app/src/main/java/com/marksy/os/gateway/CaptureStore.kt`:

```kotlin
package com.marksy.os.gateway

import android.content.Context
import java.util.UUID

/** Device-local tip-capture state (tip-ledger spec §5.1); none of it is ever sent as stored. */
class CaptureStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun deviceSalt(): String = synchronized(LOCK) {
        prefs.getString(KEY_SALT, null) ?: UUID.randomUUID().toString().also { prefs.edit().putString(KEY_SALT, it).apply() }
    }

    /** When sender tracking began; set once. */
    fun captureSince(now: Long): Long = synchronized(LOCK) {
        prefs.getLong(KEY_SINCE, -1L).takeIf { it >= 0 } ?: now.also { prefs.edit().putLong(KEY_SINCE, it).apply() }
    }

    /** Null until the first successful fetch of `GET /channels/capture-list`. */
    fun capturePackages(): Set<String>? =
        if (!prefs.contains(KEY_FETCHED_AT)) null else prefs.getStringSet(KEY_PACKAGES, emptySet()).orEmpty().toSet()

    fun isCaptureListStale(now: Long): Boolean = now - prefs.getLong(KEY_FETCHED_AT, 0L) >= CAPTURE_LIST_MAX_AGE_MS

    fun saveCapturePackages(packages: Set<String>, now: Long) {
        prefs.edit().putStringSet(KEY_PACKAGES, packages).putLong(KEY_FETCHED_AT, now).apply()
    }

    fun chatSenders(): Set<String> = prefs.getStringSet(KEY_SENDERS, emptySet()).orEmpty().toSet()

    fun rememberChatSenders(senders: Collection<String>) = synchronized(LOCK) {
        val fresh = senders.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        val known = chatSenders()
        if (known.containsAll(fresh)) return@synchronized
        // Over the cap the newest names win; an evicted name only matters to an old undelivered row.
        val merged = if (known.size + fresh.size <= MAX_SENDERS) known + fresh
        else fresh + known.take((MAX_SENDERS - fresh.size).coerceAtLeast(0))
        prefs.edit().putStringSet(KEY_SENDERS, merged).apply()
    }

    private companion object {
        val LOCK = Any()
        const val PREFS = "tip_capture"
        const val KEY_SALT = "device_salt"
        const val KEY_SINCE = "capture_since"
        const val KEY_PACKAGES = "capture_packages"
        const val KEY_FETCHED_AT = "capture_list_fetched_at"
        const val KEY_SENDERS = "chat_senders"
        const val MAX_SENDERS = 5_000
        const val CAPTURE_LIST_MAX_AGE_MS = 6 * 60 * 60 * 1000L
    }
}
```

- [ ] **Step 5: Record senders and the tracking start in the listener**

In `MarksyNotificationListenerService.kt`, add the import `import com.marksy.os.gateway.CaptureStore`.

In `onListenerConnected`, directly after `super.onListenerConnected()`, add:

```kotlin
        // Chat rows captured before sender tracking have unknown senders and never leave the phone.
        CaptureStore(applicationContext).captureSince(System.currentTimeMillis())
```

In `captureUnsafe`, directly after `if (title.isBlank() && text.isBlank()) return`, add:

```kotlin
        if (CaptureMedium.of(packageName).isChat) {
            CaptureStore(applicationContext).rememberChatSenders(NotificationTextExtractor.senders(extras))
        }
```

- [ ] **Step 6: Run the tests to verify they pass**

Run the unit-test command with `--tests 'com.marksy.os.notification.NotificationTextExtractorTest' --tests 'com.marksy.os.connector.IngestionPipelineTest'`.
Expected: all PASS. The existing MessagingStyle tests prove `messageLines` still renders "Sender: text".

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/marksy/os/notification/NotificationTextExtractor.kt app/src/main/java/com/marksy/os/gateway/CaptureStore.kt \
  app/src/main/java/com/marksy/os/notification/MarksyNotificationListenerService.kt app/src/test/java/com/marksy/os/notification/NotificationTextExtractorTest.kt
git commit -m "Tip capture: record chat sender names on the phone so chat text can be sent without them

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task B3: The capture gate — what leaves the phone, with which label and text

**Files:**
- Create: `app/src/main/java/com/marksy/os/notification/ChatLabels.kt`
- Create: `app/src/main/java/com/marksy/os/gateway/CaptureGate.kt`
- Test: `app/src/test/java/com/marksy/os/gateway/CaptureGateTest.kt`

**Interfaces:**
- Produces:
  - `object ChatLabels`, with:
    - `const val MASK_SENDER = "[SENDER]"`
    - `fun allowListedChat(title: String, allowList: Collection<String>): String?`
    - `fun smsSender(title: String): String`
    - `fun allowListedSmsSender(title: String, allowList: Collection<String>): String?`
    - `fun withoutSenders(body: String, title: String, senders: Collection<String>): String`
  - `data class CapturedMessage(deviceEventKey, medium, appPackage, channelLabel, text, devicePostedAt: String)`, with `fun toJson(): JSONObject`.
  - `data class CaptureContext(capturePackages: Set<String>?, chatAllowList: Set<String>, chatSenders: Set<String>, username: String, deviceSalt: String, captureSince: Long)`
  - `sealed interface CaptureDecision { Send(message), Wait, Keep(reason) }`
  - `object CaptureGate`, with:
    - `fun decide(event: NotificationEventEntity, context: CaptureContext): CaptureDecision`
    - `fun deviceEventKey(salt: String, sourcePackage: String, sourceKey: String): String`
    - the reason codes `NOT_TRADING`, `OUTSIDE_CAPTURE_SET`, `BEFORE_SENDER_TRACKING`, `NO_LABEL`, `MASKED_LABEL`, `EMPTY_TEXT`
  - `fun parseCaptureList(data: JSONObject): Set<String>`
- Consumes: `CaptureMedium` and `TipTextCleaner` (Task B1), and `WhatsAppSenderWatchlist.matches`.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/marksy/os/gateway/CaptureGateTest.kt`:

```kotlin
package com.marksy.os.gateway

import com.marksy.os.data.local.DeliveryState
import com.marksy.os.data.local.NotificationEventEntity
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureGateTest {
    private val context = CaptureContext(
        capturePackages = setOf("com.upstox.pro"),
        chatAllowList = setOf("stocktips", "+91 98765 43210", "zerodh"),
        chatSenders = setOf("Rahul", "Amit"),
        username = "prsingh",
        deviceSalt = "salt-1",
        captureSince = 1_000L
    )

    @Test
    fun anAppNotificationSendsTheAppNameAndItsCleanedTextOnly() {
        val sent = send(event(title = "RENUKA call for prsingh", body = "BUY RENUKA CMP 23.62 SL 22.25 TGT 26. Call 9876543210"))

        assertEquals(
            setOf("deviceEventKey", "medium", "appPackage", "channelLabel", "text", "devicePostedAt"),
            sent.toJson().keys().asSequence().toSet()
        )
        assertEquals("APP_NOTIFICATION" to "Upstox", sent.medium to sent.channelLabel)
        assertEquals("RENUKA call for [USER]\nBUY RENUKA CMP 23.62 SL 22.25 TGT 26. Call [PHONE]", sent.text)
        assertEquals("1970-01-01T00:00:02Z", sent.devicePostedAt)
    }

    @Test
    fun aGroupMessageSendsTheGroupNameAndNoSenderName() {
        val whatsapp = send(event(
            pkg = "com.whatsapp", source = "WhatsApp", title = "StockTips: Rahul",
            body = "Rahul: BUY RENUKA CMP 23.62 SL 22.25 TGT 26\nAmit: thanks Rahul"
        ))
        assertEquals("WHATSAPP" to "StockTips", whatsapp.medium to whatsapp.channelLabel)
        assertEquals("BUY RENUKA CMP 23.62 SL 22.25 TGT 26\nthanks [SENDER]", whatsapp.text)

        val telegram = send(event(pkg = "org.telegram.messenger", source = "Telegram", title = "Rahul @ StockTips"))
        assertEquals("TELEGRAM" to "StockTips", telegram.medium to telegram.channelLabel)
        assertFalse(telegram.text.contains("Rahul"))
    }

    @Test
    fun aOneToOneChatTitledWithAPhoneNumberNeverLeavesThePhone() {
        val chat = event(pkg = "com.whatsapp", source = "WhatsApp", title = "+91 98765 43210")

        assertEquals(CaptureDecision.Keep(CaptureGate.MASKED_LABEL), CaptureGate.decide(chat, context))
    }

    @Test
    fun onlyTheCaptureSetLeavesThePhone() {
        fun decide(event: NotificationEventEntity) = CaptureGate.decide(event, context)
        val outside = CaptureDecision.Keep(CaptureGate.OUTSIDE_CAPTURE_SET)

        assertEquals(CaptureDecision.Keep(CaptureGate.NOT_TRADING), decide(event(trading = false)))
        assertEquals(outside, decide(event(pkg = "com.zerodha.kite3", source = "Zerodha")))
        assertEquals(outside, decide(event(pkg = "com.whatsapp", source = "WhatsApp", title = "Family Group")))
        assertEquals(outside, decide(event(pkg = "com.google.android.apps.messaging", source = "Messages", title = "Amit")))
        assertEquals(
            CaptureDecision.Keep(CaptureGate.BEFORE_SENDER_TRACKING),
            decide(event(pkg = "com.whatsapp", source = "WhatsApp", title = "StockTips", postedAt = 999L))
        )
        val sms = send(event(pkg = "com.google.android.apps.messaging", source = "Messages", title = "JD-ZERODH-S"))
        assertEquals("SMS" to "ZERODH", sms.medium to sms.channelLabel)
    }

    @Test
    fun appNotificationsWaitUntilTheCaptureListIsCached() {
        val uncached = context.copy(capturePackages = null)

        assertEquals(CaptureDecision.Wait, CaptureGate.decide(event(), uncached))
        assertTrue(CaptureGate.decide(event(pkg = "com.whatsapp", source = "WhatsApp", title = "StockTips"), uncached) is CaptureDecision.Send)
    }

    @Test
    fun theDeviceEventKeyIsStableAcrossRetriesAndRevealsNoNotificationKey() {
        val row = event(key = "0|com.upstox.pro|7|client-919876543210|10123")
        val first = send(row).deviceEventKey

        assertEquals(first, send(row).deviceEventKey)
        assertFalse(first.contains("9876543210"))
        assertTrue(first.startsWith("n1-") && first.length <= 128)
        val otherInstall = CaptureGate.decide(row, context.copy(deviceSalt = "salt-2")) as CaptureDecision.Send
        assertNotEquals(first, otherInstall.message.deviceEventKey)
    }

    @Test
    fun theCaptureListKeepsOnlyPackageNames() {
        val data = JSONObject("""{"packages":[{"package":"COM.UPSTOX.PRO","channelId":3},{"package":""},{"channelId":4}]}""")

        assertEquals(setOf("com.upstox.pro"), parseCaptureList(data))
    }

    private fun send(event: NotificationEventEntity) = (CaptureGate.decide(event, context) as CaptureDecision.Send).message

    private fun event(
        pkg: String = "com.upstox.pro",
        source: String = "Upstox",
        title: String = "RENUKA call",
        body: String = "BUY RENUKA CMP 23.62 SL 22.25 TGT 26",
        trading: Boolean = true,
        key: String = "k-1",
        postedAt: Long = 2_000L
    ) = NotificationEventEntity(
        sourcePackage = pkg, sourceName = source, sourceKey = key, eventFingerprint = key, title = title, body = body,
        postedAt = postedAt, category = if (trading) "TRADING" else "MESSAGES", priority = 100, confidence = 0.96f,
        isTrading = trading, deliveryState = if (trading) DeliveryState.PENDING.name else DeliveryState.NOT_APPLICABLE.name
    )
}
```

- [ ] **Step 2: Run it to verify it fails**

Run the unit-test command with `--tests 'com.marksy.os.gateway.CaptureGateTest'`.
Expected: FAIL at compile with `Unresolved reference 'CaptureContext'`.

- [ ] **Step 3: Write the chat and SMS label rules**

Create `app/src/main/java/com/marksy/os/notification/ChatLabels.kt`:

```kotlin
package com.marksy.os.notification

import java.util.Locale

/** Channel labels and sender-free text for chat and SMS captures (tip-ledger spec §5.1). */
object ChatLabels {
    const val MASK_SENDER = "[SENDER]"
    private const val GROUP_SENDER = " @ "
    private const val SENDER_SUFFIX = ": "
    private const val MIN_MASKED_SENDER_LENGTH = 3
    // TRAI DLT sender ids: the operator/circle prefix and type suffix vary per message, the 6-character header does not.
    private val DLT_HEADER = Regex("^[A-Za-z]{2}-([A-Za-z0-9]{6})(?:-[PSTGpstg])?$")

    /** The allow-listed chat a title names: "Rahul @ StockTips" (Telegram), "StockTips: Rahul" (WhatsApp) or "StockTips". */
    fun allowListedChat(title: String, allowList: Collection<String>): String? {
        val value = title.trim()
        val candidates = listOfNotNull(
            value.substringAfterLast(GROUP_SENDER, "").trim().takeIf { GROUP_SENDER in value },
            value.substringBeforeLast(SENDER_SUFFIX, "").trim().takeIf { SENDER_SUFFIX in value },
            value
        )
        return candidates.firstOrNull { it.isNotBlank() && WhatsAppSenderWatchlist.matches(allowList, it) }
    }

    fun smsSender(title: String): String {
        val value = title.trim()
        return DLT_HEADER.matchEntire(value)?.groupValues?.get(1)?.uppercase(Locale.ROOT) ?: value
    }

    fun allowListedSmsSender(title: String, allowList: Collection<String>): String? {
        val sender = smsSender(title)
        return sender.takeIf { it.isNotBlank() && WhatsAppSenderWatchlist.matches(allowList.map(::smsSender), it) }
    }

    /** This row's senders lose their "Name: " line prefix, and any other mention of them is masked. */
    fun withoutSenders(body: String, title: String, senders: Collection<String>): String {
        val lines = body.lines()
        val present = senders.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
            .filter { name -> title.contains(name) || lines.any { it.startsWith("$name$SENDER_SUFFIX") } }
            .sortedByDescending { it.length }
        if (present.isEmpty()) return body
        val masks = present.filter { it.length >= MIN_MASKED_SENDER_LENGTH }
            .map { Regex("(?<![\\p{L}\\p{N}_])${Regex.escape(it)}(?![\\p{L}\\p{N}_])", RegexOption.IGNORE_CASE) }
        return lines.joinToString("\n") { line ->
            val unprefixed = present.firstOrNull { line.startsWith("$it$SENDER_SUFFIX") }
                ?.let { line.removePrefix("$it$SENDER_SUFFIX") } ?: line
            masks.fold(unprefixed) { text, mask -> mask.replace(text, MASK_SENDER) }
        }
    }
}
```

- [ ] **Step 4: Write the gate and the payload**

Create `app/src/main/java/com/marksy/os/gateway/CaptureGate.kt`:

```kotlin
package com.marksy.os.gateway

import com.marksy.os.data.local.NotificationEventEntity
import com.marksy.os.notification.CaptureMedium
import com.marksy.os.notification.ChatLabels
import com.marksy.os.notification.TipTextCleaner
import org.json.JSONObject
import java.security.MessageDigest
import java.time.Instant
import java.util.Locale

/** The tip-ledger spec §5.1 body of `POST /tips/ingest-text`: no title, body or sender. */
data class CapturedMessage(
    val deviceEventKey: String,
    val medium: String,
    val appPackage: String,
    val channelLabel: String,
    val text: String,
    val devicePostedAt: String
) {
    fun toJson(): JSONObject = JSONObject()
        .put("deviceEventKey", deviceEventKey)
        .put("medium", medium)
        .put("appPackage", appPackage)
        .put("channelLabel", channelLabel)
        .put("text", text)
        .put("devicePostedAt", devicePostedAt)
}

/** Everything the gate reads from the phone, loaded once per delivery run. */
data class CaptureContext(
    /** Null until the capture list was fetched once. */
    val capturePackages: Set<String>?,
    val chatAllowList: Set<String>,
    val chatSenders: Set<String>,
    val username: String,
    val deviceSalt: String,
    val captureSince: Long
)

sealed interface CaptureDecision {
    data class Send(val message: CapturedMessage) : CaptureDecision
    /** The capture list is not cached yet; the row stays queued. */
    data object Wait : CaptureDecision
    /** The row stays on the phone for good; `reason` is a fixed code, never content. */
    data class Keep(val reason: String) : CaptureDecision
}

/** The only way a captured notification leaves the phone (tip-ledger spec §2.11, §5.1). */
object CaptureGate {
    const val NOT_TRADING = "not-trading"
    const val OUTSIDE_CAPTURE_SET = "outside-capture-set"
    const val BEFORE_SENDER_TRACKING = "before-sender-tracking"
    const val NO_LABEL = "no-label"
    const val MASKED_LABEL = "masked-label"
    const val EMPTY_TEXT = "empty-text"

    fun decide(event: NotificationEventEntity, context: CaptureContext): CaptureDecision {
        if (!event.isTrading || event.category != "TRADING" || event.sourceKey.isBlank()) return CaptureDecision.Keep(NOT_TRADING)
        val appPackage = event.sourcePackage.trim().lowercase(Locale.ROOT)
        val medium = CaptureMedium.of(appPackage)
        val label = when (medium) {
            CaptureMedium.APP_NOTIFICATION -> {
                val packages = context.capturePackages ?: return CaptureDecision.Wait
                if (appPackage !in packages) return CaptureDecision.Keep(OUTSIDE_CAPTURE_SET)
                event.sourceName
            }
            CaptureMedium.SMS -> ChatLabels.allowListedSmsSender(event.title, context.chatAllowList)
                ?: return CaptureDecision.Keep(OUTSIDE_CAPTURE_SET)
            CaptureMedium.WHATSAPP, CaptureMedium.TELEGRAM -> {
                if (event.postedAt < context.captureSince) return CaptureDecision.Keep(BEFORE_SENDER_TRACKING)
                ChatLabels.allowListedChat(event.title, context.chatAllowList) ?: return CaptureDecision.Keep(OUTSIDE_CAPTURE_SET)
            }
        }
        val channelLabel = TipTextCleaner.channelLabel(medium, label, context.username) ?: return CaptureDecision.Keep(NO_LABEL)
        if (TipTextCleaner.isMaskOnly(channelLabel)) return CaptureDecision.Keep(MASKED_LABEL)
        val raw = when (medium) {
            CaptureMedium.APP_NOTIFICATION -> listOf(event.title, event.body).filter { it.isNotBlank() }.joinToString("\n")
            CaptureMedium.SMS -> event.body
            CaptureMedium.WHATSAPP, CaptureMedium.TELEGRAM -> ChatLabels.withoutSenders(event.body, event.title, context.chatSenders)
        }
        val text = TipTextCleaner.clean(raw, context.username).trim()
        if (text.isEmpty()) return CaptureDecision.Keep(EMPTY_TEXT)
        return CaptureDecision.Send(
            CapturedMessage(
                deviceEventKey = deviceEventKey(context.deviceSalt, appPackage, event.sourceKey),
                medium = medium.name,
                appPackage = appPackage,
                channelLabel = channelLabel,
                text = text,
                devicePostedAt = Instant.ofEpochMilli(event.postedAt).toString()
            )
        )
    }

    /** One key per notification row, so a retry is the same receipt; salted so it reveals no notification key. */
    fun deviceEventKey(salt: String, sourcePackage: String, sourceKey: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest("$salt|$sourcePackage|$sourceKey".toByteArray(Charsets.UTF_8))
        return "n1-" + digest.joinToString("") { "%02x".format(it) }
    }
}

/** `GET /channels/capture-list` data: the app packages a phone may send (tip-ledger spec §5.1). */
fun parseCaptureList(data: JSONObject): Set<String> {
    val packages = data.optJSONArray("packages") ?: return emptySet()
    return (0 until packages.length()).mapNotNull { i ->
        // Android's optString turns a JSON null into the text "null".
        packages.optJSONObject(i)?.optString("package")?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotBlank() && it != "null" }
    }.toSet()
}
```

- [ ] **Step 5: Run it to verify it passes**

Run the unit-test command with `--tests 'com.marksy.os.gateway.CaptureGateTest'`.
Expected: 7 PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/marksy/os/notification/ChatLabels.kt app/src/main/java/com/marksy/os/gateway/CaptureGate.kt \
  app/src/test/java/com/marksy/os/gateway/CaptureGateTest.kt
git commit -m "Tip capture: one gate decides what leaves the phone and builds the §5.1 payload

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task B4: Send to `POST /tips/ingest-text`; retire the `POST /tips` payload

**Files:**
- Modify: `app/src/main/java/com/marksy/os/gateway/MarksyGatewayClient.kt`: the whole file.
- Modify: `app/src/main/java/com/marksy/os/gateway/MarksyTipsApiClient.kt`: lines 17–41 and `fetchTip`.
- Modify: `app/src/main/java/com/marksy/os/gateway/TradingDeliveryWorker.kt`: `deliverPending` and `TradingDeliveryRun`.
- Modify: `app/src/main/java/com/marksy/os/gateway/MarksyTipPayload.kt`: keep `symbolOf` only.
- Modify: `app/src/main/java/com/marksy/os/gateway/MarksyGatewayContract.kt`: delete `MarksyTradingEventRequest`.
- Test:
  - Modify `TradingDeliveryRunTest.kt`, `MarksyGatewayClientTest.kt` and `MarksyTipsApiClientTest.kt`.
  - Delete `MarksyTipPayloadFixtureTest.kt` and `MarksyTradingEventMappingTest.kt`: their subjects are deleted, and the routing-flag checks now live in `CaptureGateTest.onlyTheCaptureSetLeavesThePhone`.

**Interfaces:**
- Produces:
  - `interface MarksyGatewayClient`, with `suspend fun capture(eventId: Long, message: CapturedMessage): Result<MarksyInsight>` and `suspend fun captureList(): Result<Set<String>>`.
  - `internal class TradingDeliveryRun(dao, client, capture: CaptureContext, isStopped: () -> Boolean = { false })`
- Consumes: `CaptureGate`, `CaptureContext`, `CapturedMessage` and `parseCaptureList` (Task B3), `CaptureStore` (Task B2), and `WhatsAppSenderWatchlist.get`.
- Removes: `MarksyTradingEventRequest`, `NotificationEventEntity.toMarksyTradingEventRequest()`, `MarksyTipPayload`, `MarksyTipPayloadBuilder.from`, `MarksyGatewayClient.analyze`, `MarksyTipsApiClient.postTip`.

- [ ] **Step 1: Write the failing tests**

Replace `app/src/test/java/com/marksy/os/gateway/TradingDeliveryRunTest.kt` with:

```kotlin
package com.marksy.os.gateway

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.marksy.os.data.local.DeliveryState
import com.marksy.os.data.local.MarksyDatabase
import com.marksy.os.data.local.NotificationEventEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TradingDeliveryRunTest {
    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), MarksyDatabase::class.java)
        .allowMainThreadQueries().build()
    private val dao = db.notificationEventDao()
    private val capture = CaptureContext(
        capturePackages = setOf("com.fivepaisa.trade"), chatAllowList = emptySet(), chatSenders = emptySet(),
        username = "user-1", deviceSalt = "salt", captureSince = 0L
    )
    private val client = object : MarksyGatewayClient {
        override suspend fun capture(eventId: Long, message: CapturedMessage) = Result.success(MarksyInsight(eventId, "ok"))
        override suspend fun captureList() = Result.success(emptySet<String>())
    }

    @After
    fun tearDown() = db.close()

    // Regression: one run sent only the first 10 calls; the rest waited for the 15-minute periodic run.
    @Test
    fun oneRunDeliversEveryPendingCall() = runBlocking {
        repeat(12) { i -> dao.insert(row("k$i", i.toLong())) }

        TradingDeliveryRun(dao, client, capture).drain()

        assertEquals(0, dao.findPendingTrading(50).size)
    }

    // A row put back to PENDING must end the run with a retry, not spin the drain loop.
    @Test
    fun aRowWaitingForTheCaptureListEndsTheRunWithARetry() = runBlocking {
        dao.insert(row("k1", 1L))

        val retry = TradingDeliveryRun(dao, client, capture.copy(capturePackages = null)).drain()

        assertTrue(retry)
        assertEquals(1, dao.findPendingTrading(5).size)
    }

    private fun row(key: String, postedAt: Long) = NotificationEventEntity(
        sourcePackage = "com.fivepaisa.trade", sourceName = "5paisa", sourceKey = key, eventFingerprint = "f-$key",
        title = "Call $key", body = "BUY RENUKA CMP 23 SL 22", postedAt = postedAt, category = "TRADING", priority = 10,
        confidence = 1f, isTrading = true, deliveryState = DeliveryState.PENDING.name
    )
}
```

Replace `app/src/test/java/com/marksy/os/gateway/MarksyGatewayClientTest.kt` with:

```kotlin
package com.marksy.os.gateway

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class MarksyGatewayClientTest {
    @Test
    fun unconfiguredClientDoesNotPretendDeliverySucceeded() = runBlocking {
        val message = CapturedMessage("n1-key", "APP_NOTIFICATION", "com.upstox.pro", "Upstox", "BUY RENUKA CMP 23 SL 22", "2026-09-28T04:45:00Z")

        assertEquals(false, UnconfiguredMarksyGatewayClient().capture(1L, message).isSuccess)
        assertEquals(false, UnconfiguredMarksyGatewayClient().captureList().isSuccess)
    }
}
```

In `MarksyTipsApiClientTest.notSignedInFailsRetryableNotTerminal`, replace the `val request = MarksyTradingEventRequest(...)` block and `val result = client.analyze(request)` with:

```kotlin
        val message = CapturedMessage(
            deviceEventKey = "n1-key",
            medium = "APP_NOTIFICATION",
            appPackage = "com.upstox.pro",
            channelLabel = "Upstox",
            text = "Symbol: RELIANCE BUY order executed at 1450",
            devicePostedAt = "2023-11-14T22:13:20Z"
        )

        val result = client.capture(1L, message)
```

Delete the two retired test files:

```bash
git rm app/src/test/java/com/marksy/os/gateway/MarksyTipPayloadFixtureTest.kt app/src/test/java/com/marksy/os/gateway/MarksyTradingEventMappingTest.kt
```

- [ ] **Step 2: Run them to verify they fail**

Run the unit-test command with `--tests 'com.marksy.os.gateway.*'`.
Expected: FAIL at compile with `Unresolved reference 'capture'` and `Too many arguments for constructor TradingDeliveryRun`.

- [ ] **Step 3: Replace the gateway interface**

Replace `app/src/main/java/com/marksy/os/gateway/MarksyGatewayClient.kt` with:

```kotlin
package com.marksy.os.gateway

interface MarksyGatewayClient {
    /** Records one captured message as the signed-in customer's receipt (tip-ledger spec §5.1). */
    suspend fun capture(eventId: Long, message: CapturedMessage): Result<MarksyInsight>

    /** The app packages the server wants captured. */
    suspend fun captureList(): Result<Set<String>>
}

/**
 * Safe default until the existing Marksy Gateway exposes a confirmed Android-facing contract.
 * It prevents accidental network traffic rather than inventing an endpoint.
 */
class UnconfiguredMarksyGatewayClient : MarksyGatewayClient {
    override suspend fun capture(eventId: Long, message: CapturedMessage): Result<MarksyInsight> = notConfigured()

    override suspend fun captureList(): Result<Set<String>> = notConfigured()

    private fun <T> notConfigured(): Result<T> = Result.failure(IllegalStateException("Marksy Gateway endpoint is not configured"))
}
```

- [ ] **Step 4: Post captured messages, and read the capture list**

In `MarksyTipsApiClient.kt`, replace `override suspend fun analyze(...)` (lines 17–31) with:

```kotlin
    override suspend fun capture(eventId: Long, message: CapturedMessage): Result<MarksyInsight> = try {
        val data = execute("POST", "$apiBaseUrl/tips/ingest-text", message.toJson()).getJSONObject("data")
        val kind = data.str("kind").ifBlank { "RECORDED" }.boundedText(MAX_STATUS_CHARS)
        val tipId = data.str("tipId").trim()
        Result.success(if (tipId.isBlank()) recordedOnly(eventId, kind) else fetchTip(tipId, eventId, kind))
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Throwable) {
        Result.failure(error)
    }

    override suspend fun captureList(): Result<Set<String>> = try {
        Result.success(parseCaptureList(execute("GET", "$apiBaseUrl/channels/capture-list").getJSONObject("data")))
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Throwable) {
        Result.failure(error)
    }
```

Replace `postTip` (lines 36–41) with:

```kotlin
    // UNPARSED and orphan EXIT receipts have no tip, so there is no comparison to fetch.
    private fun recordedOnly(eventId: Long, kind: String) = MarksyInsight(
        eventId = eventId,
        summary = if (kind == "EXIT") "Exit recorded; no open call to close" else "Recorded by Marksy; not read as a call yet",
        action = kind
    )
```

In `fetchTip`:
- Rename the parameter `createdStatus: String` to `kind: String`.
- Change `action = (recommendation ?: createdStatus).boundedText(MAX_LONG_TEXT_CHARS)` to `action = (recommendation ?: kind).boundedText(MAX_LONG_TEXT_CHARS)`.

Delete `private data class CreatedTip(val tipId: String, val status: String)`.

- [ ] **Step 5: Route delivery through the gate**

In `TradingDeliveryWorker.kt`:
- Add the import `import com.marksy.os.notification.WhatsAppSenderWatchlist`.
- In `deliverPending`, replace the final line, `return if (TradingDeliveryRun(dao, client) { isStopped }.drain()) Result.retry() else Result.success()`, with:

```kotlin
        val capture = loadCaptureContext(client, now)
        if (capture == null) {
            Log.i(TAG, "Trading delivery deferred: no signed-in customer id")
            return Result.success()
        }

        return if (TradingDeliveryRun(dao, client, capture) { isStopped }.drain()) Result.retry() else Result.success()
    }

    private suspend fun loadCaptureContext(client: MarksyGatewayClient, now: Long): CaptureContext? {
        val username = AuthSessionStore(applicationContext).getUserId()?.takeIf { it.isNotBlank() } ?: return null
        val store = CaptureStore(applicationContext)
        if (store.isCaptureListStale(now)) {
            client.captureList()
                .onSuccess { store.saveCapturePackages(it, now) }
                .onFailure { Log.w(TAG, "Capture list refresh failed; using the cached list") }
        }
        return CaptureContext(
            capturePackages = store.capturePackages(),
            chatAllowList = WhatsAppSenderWatchlist.get(applicationContext),
            chatSenders = store.chatSenders(),
            username = username,
            deviceSalt = store.deviceSalt(),
            captureSince = store.captureSince(now)
        )
```

The existing closing brace of `deliverPending` now closes `loadCaptureContext`.

Next, change the `TradingDeliveryRun` constructor to:

```kotlin
internal class TradingDeliveryRun(
    private val dao: NotificationEventDao,
    private val client: MarksyGatewayClient,
    private val capture: CaptureContext,
    private val isStopped: () -> Boolean = { false }
) {
```

In `deliverBatch`, replace the block from `val request = event.toMarksyTradingEventRequest()` down to and including `client.analyze(request)` with:

```kotlin
            val message = when (val decision = CaptureGate.decide(event, capture)) {
                is CaptureDecision.Send -> decision.message
                CaptureDecision.Wait -> {
                    dao.updateInFlightDeliveryState(event.id, DeliveryState.PENDING.name, attempts, System.currentTimeMillis())
                    retryRequested = true
                    continue
                }
                is CaptureDecision.Keep -> {
                    dao.updateInFlightDeliveryState(event.id, DeliveryState.NOT_APPLICABLE.name, attempts, System.currentTimeMillis())
                    Log.i(TAG, "Trading event ${event.id} stays on the phone (${decision.reason})")
                    continue
                }
            }

            val result: KotlinResult<MarksyInsight> = try {
                client.capture(event.id, message)
```

The `catch` branches and `result.fold(...)` that follow are unchanged.

- [ ] **Step 6: Retire the old payload types**

- In `MarksyGatewayContract.kt`, delete `data class MarksyTradingEventRequest` and its KDoc (lines 3–16). `MarksyInsight` stays.
- Replace `MarksyTipPayload.kt` with the lines below. The body of `extractSymbol` is copied unchanged from the current file.

```kotlin
package com.marksy.os.gateway

/** The symbol shown on a trading card until Phase 4b reads the server's parsed terms instead. */
object MarksyTipPayloadBuilder {
    fun symbolOf(title: String, body: String): String? = extractSymbol(title, body, "$title $body")

    private fun extractSymbol(title: String, body: String, text: String): String? {
        // Case-sensitive capture: "Stock Alert" must not yield ALERT.
        val labelled = Regex("(?i:symbol|scrip|ticker|stock)\\s*[:=-]?\\s*([A-Z][A-Z0-9.-]{2,14})\\b")
            .find(text)?.groupValues?.getOrNull(1)
        if (!labelled.isNullOrBlank()) return labelled.uppercase()
        // A parsed call names the instrument after the side; the first caps word is often the SMS sender.
        com.marksy.os.notification.TradeCallParser.parse(title, body)?.let { return it.symbol }

        // Do not guess a ticker from arbitrary ALL-CAPS notification prose.
        // An unlabelled symbol is accepted only when strong trade context exists.
        val hasTradeContext = Regex(
            "(?i)\\b(?:BUY|SELL|ORDER|EXECUTED|FILLED|TRADE|POSITION|QTY|QUANTITY|ENTRY|TARGET|STOP\\s*LOSS|AVG(?:ERAGE)?\\s*PRICE)\\b"
        ).containsMatchIn(text)
        if (!hasTradeContext) return null

        val excluded = setOf(
            "BUY", "SELL", "ORDER", "EXECUTED", "FILLED", "TRADE", "POSITION", "OPENED", "CLOSED",
            "PRICE", "ENTRY", "TARGET", "STOP", "LOSS", "MARKET", "LIMIT", "QTY", "QUANTITY",
            "PERCENT", "CONFIDENCE", "PROBABILITY", "UNUSUAL", "VOLUME", "ALERT", "DETECTED",
            "NSE", "BSE", "INR", "UPI", "P&L", "PNL"
        )
        return Regex("\\b[A-Z][A-Z0-9.-]{2,14}\\b").findAll(text)
            .map { it.value.uppercase() }
            .firstOrNull { it !in excluded }
    }
}
```

- [ ] **Step 7: Run the gateway tests and prove the old payload is gone**

Run the unit-test command with `--tests 'com.marksy.os.gateway.*'`. Expected: all PASS.

Then:

```bash
grep -rnF '"$apiBaseUrl/tips"' app/src/main || echo "no POST /tips"
grep -rnE 'put\("(title|body|sender)"' app/src/main/java/com/marksy/os/gateway || echo "no title/body/sender in any payload"
grep -rn 'MarksyTradingEventRequest\|toMarksyTradingEventRequest\|MarksyTipPayloadBuilder.from' app/src || echo "old payload types removed"
```

Expected: all three `echo` lines print.

- [ ] **Step 8: Commit**

```bash
git add -A app/src/main/java/com/marksy/os/gateway app/src/test/java/com/marksy/os/gateway
git status
git commit -m "Tip capture: send captured messages to /tips/ingest-text; retire the POST /tips payload

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

Check that `git status` shows only the gateway files listed in this task, including the two deletions.

---

### Task B5: Unit suite, PR, merge

**Files:** none new.

- [ ] **Step 1: Run the whole unit-test suite**

The change touches the shared capture and delivery path, so run the full JVM suite. It is still never `connectedAndroidTest`.

```bash
cd /c/AIAgent/marksy-os-phase4a
export PATH="$(echo "$PATH" | tr ':' '\n' | grep -vE '"|%' | paste -sd:)"
./gradlew --stop
./gradlew --no-daemon :app:testDebugUnitTest
```

Expected: BUILD SUCCESSFUL. If a test outside this plan's files fails, run the same test on `origin/main` in a scratch checkout. If it fails there too, note it in the PR; otherwise fix it.

- [ ] **Step 2: Push and open the PR**

```bash
git push -u origin feat/tip-capture-payload
gh pr create --title "Tip ledger Phase 4a (B): send captured messages to /tips/ingest-text" --body "$(cat <<'EOF'
The phone now sends each captured market message to `POST /tips/ingest-text` as the signed-in customer (tip-ledger
spec §5.1); the server parses. Requires marksy-api Phase 4a (A) in production (checked before this branch was cut).

- `TipTextCleaner` mirrors the server's masks (username, phones, emails, PAN, 8+ digit runs) with the same test vectors
- `CaptureGate` is the only way out: capture-list packages, or allow-listed WhatsApp/Telegram chats and SMS senders;
  everything else stays "Local only"
- Labels: app name, SMS sender id (DLT header), or the allow-listed chat name without its sender; a label that cleans
  to masks only (a chat titled with a phone number) never leaves the phone
- Chat text: MessagingStyle sender names are recorded on the phone, stripped and masked before sending
- Salted per-row `deviceEventKey`, so retries return the same receipt; the capture list is cached for 6 h
- `POST /tips` and its title/body payload are retired; on-device parsing keeps serving the local UI until Phase 4b

Tests: full JVM unit suite (`testDebugUnitTest`).

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```

- [ ] **Step 3: Merge and clean up**

```bash
gh pr merge --merge --delete-branch
test -z "$(git -C /c/AIAgent/marksy-os-phase4a status --porcelain)" && cd /c/AIAgent/marksy-os && git worktree remove --force ../marksy-os-phase4a && git fetch origin
```

`--force` is only needed for the ignored `local.properties` and the build outputs. The `status --porcelain` check makes sure nothing uncommitted is lost. Leave `C:\AIAgent\marksy-os` on its current branch.

## Summary

Part A closes the Phase 1 entry criterion for capture. A label made only of masks is rejected instead of pooling strangers into one channel, and every alias lives in its medium's namespace, so no WhatsApp group can ever become a broker's record, or the reverse. Part B turns the phone into a capture-only client:
- One pure gate decides what leaves the phone.
- Nothing outside the capture list or the customer's allow-list is sent, and no title, body or sender name is sent at all.
- The payload is the §5.1 body under the bearer session, with a salted, retry-stable event key.

Phase 4b (after Phase 3) deletes the on-device parsing and adds the calls box, My tips and Scorecards screens. It must also replace the classifier's TRADING pre-filter that still decides what is queued.

## Validation

- Task A1: `tests/test_tip_text_cleaning.py`, `tests/test_tip_ledger.py`, `tests/test_api_tips_ingest.py`
- Task A2: `tests/test_channel_alias_scope_migration.py`, `tests/test_tip_ledger_migration.py`, `tests/test_alembic_single_head.py`, `alembic heads`
- Task A3: `tests/test_tip_ledger.py`, `tests/test_api_tips_ingest.py`, `tests/test_api_channels.py`, `tests/test_tip_text_cleaning.py`
- Task A4: the regression set in Task A4 Step 1, and the production checks in Task A4 Step 4
- Task B1: the production gate in Task B1 Step 1, and `TipTextCleanerTest`
- Task B2: `NotificationTextExtractorTest`, `IngestionPipelineTest`
- Task B3: `CaptureGateTest`
- Task B4: `com.marksy.os.gateway.*`, and the three greps in Task B4 Step 7
- Task B5: the full `:app:testDebugUnitTest`
