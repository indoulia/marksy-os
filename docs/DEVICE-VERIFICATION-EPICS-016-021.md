# Device verification checklist: EPIC-016, 018, 019, 020, 021

Target: OnePlus 12R, Android 16 (API 36). AICore / Gemini Nano is not available on this device.
Build under test: branch `feat/intelligence-epics-completion`. Fill in `Commit` before starting.

Commit: `________`   Date: `________`   Tester: `________`

Nothing in this document has been run on a device yet. The Observed, PASS/FAIL and Evidence
columns are filled in during the device session.

## 0. Setup

1. Resolve the serial: `SER=$(adb devices | awk '/device$/{print $1}' | head -1)`. The phone switches between a USB serial and a wireless `adb-tls` serial.
2. Before any `am start` or tap, confirm that Marksy or the launcher is in the foreground: `adb -s $SER shell dumpsys activity activities | grep mResumedActivity`. This is the user's personal phone.
3. Install the build: `ANDROID_SERIAL=$SER ./gradlew :app:installDebug`.
4. Start a filtered log capture:
   ```
   adb -s $SER logcat -c
   adb -s $SER logcat -s MarksyAi MarksyAsk MarksyConnector MarksyMemory MarksyRules MarksyNotificationListener > device-run.log
   ```
5. Post test notifications with `adb -s $SER shell 'cmd notification post -t "Title" tag "Body"'`. Quote the whole remote command. If lock-screen sensitive-content hiding is on, turn it off first; otherwise the text arrives as "Sensitive notification content".
6. To read the database (debug build): `adb -s $SER exec-out run-as com.marksy.os cat databases/marksy_os.db > x.db`, and copy the `-wal` file the same way.

Privacy check for logs: at the end of the session, grep `device-run.log` for notification bodies, email subjects, tokens and addresses. The expected result is no matches. The tags above log only ids, states, counts and exception type names.

## 1. Rules 2.0 (EPIC-018)

| # | Step | Expected | Observed | PASS/FAIL | Evidence/log |
|---|------|----------|----------|-----------|--------------|
| R1 | Rules → new rule. Root AND: `app package is com.android.shell` plus an OR group (`text contains invoice` OR `text contains bill`). Save. | Saves without validation errors. The summary shows `app package is com.android.shell AND (text contains invoice OR text contains bill)`. | | | |
| R2 | Toggle NOT on the OR group. Save. | The summary shows `NOT (…)`. | | | |
| R3 | Leave the Rules screen, return, then force-stop and relaunch the app (`am force-stop com.marksy.os`). Open the rule. | The same nested structure, the NOT flag and the action are restored. The version does not change after re-saving without edits. | | | |
| R4 | Post `-t "Acme" tag "Your invoice is ready"`, then post `-t "Acme" tag "Hello"`. | R1 without NOT: the first notification matches, the second does not. With NOT (R2): the opposite. | | | |
| R5 | Add an empty sub-group and try to save. | Save is blocked with "Empty group: add a condition or remove it". | | | |
| R6 | Enter hour `between 25..3` and priority `between 80..10`. | Inline errors: "Hours are whole numbers 0–23" and "Range low must not exceed high". | | | |
| R7 | Corrupt the stored rule (debug build): `run-as com.marksy.os` and edit `shared_prefs/marksy_rules.xml` so that one condition has `"field":"GPS"`. Relaunch. | That rule is shown but disabled and never matches. Other rules are unaffected. `MarksyRules: rule #N disabled: unreadable condition` appears in the log. | | | |
| R8 | Change the display font size, or rotate the phone, while the editor is open. | The editor state is kept, or the change can be abandoned without corrupting the saved rule. | | | |

## 2. Ask Marksy (EPIC-016)

Setup: post a few payment notifications first, for example `-t "HDFC" tag "Rs 1299 debited to Amazon UPI Ref 426700000001"` and `-t "HDFC" tag "Rs 450 debited to Swiggy UPI Ref 426700000002"`.

| # | Query | Expected | Observed | PASS/FAIL | Evidence/log |
|---|-------|----------|----------|-----------|--------------|
| A1 | "How much did I spend at Amazon?" | Only Amazon debits in the last 7 days, with a total. Items link to real events. | | | |
| A2 | "Show my Amazon transactions" | Filtered to Amazon. The Swiggy payment is not listed. | | | |
| A3 | "What did I spend last month?" | All merchants, labelled "last month". Payments from this month are excluded. | | | |
| A4 | "Show transactions between 1 September and 15 September" | Range label "1 Sep – 15 Sep". Only events in that window. | | | |
| A5 | "How much did I spend at a merchant that does not exist?" | "I found no payments matching …". No items and no invented amount. | | | |
| A6 | "What did Rahul send me?" (with two different Rahuls in data) | Asks which Rahul (needs clarification), then answers the one chosen. | | | |
| A7 | Any query on this device | Answer is attributed to `deterministic`. Log shows `MarksyAsk: interpret: no local model; deterministic fallback`. | | | |
| A8 | Ask with airplane mode on | Same answers. Ask Marksy never needs the network. | | | |

## 3. On-device AI (EPIC-019)

Gemini Nano is not expected to work on this device. These checks pass when unavailability is handled honestly.

| # | Step | Expected | Observed | PASS/FAIL | Evidence/log |
|---|------|----------|----------|-----------|--------------|
| I1 | Open Learning → On-device AI. | `gemini-nano-aicore · on-device · <not available>`. Nothing claims the model is ready. | | | |
| I2 | Log after I1 | `MarksyAi: availability: NOT_AVAILABLE`, or a FAILED probe with an exception type only. No crash. | | | |
| I3 | Tap the download/prepare action, if shown. | No download starts. The state stays not available and the UI stays responsive. | | | |
| I4 | Ask several questions quickly (A1–A5). | Each answer arrives in under about 3 s (the service timeout is 3 s, the probe timeout 2 s). There is no ANR and no frozen UI. | | | |
| I5 | Force-stop, relaunch, open Learning. | The state is re-probed (UNKNOWN, then NOT_AVAILABLE). Nothing stale is shown. | | | |
| I6 | Settings → Apps → Marksy → Storage. | The APK contains no model file. Installed size stays about the same. | | | |

The following need an AICore-capable device and cannot be tested here: READY, download progress, warm-up time, real inference latency, and model eviction followed by a re-probe.

## 4. Personal memory (EPIC-020)

| # | Step | Expected | Observed | PASS/FAIL | Evidence/log |
|---|------|----------|----------|-----------|--------------|
| M1 | `adb shell dumpsys package com.marksy.os \| grep -i location` | Only `ACCESS_COARSE_LOCATION`, which the weather card uses. No FINE or BACKGROUND location. | | | |
| M2 | Post `-t "Swiggy" tag "Your order was delivered to your Home"` three times, then open Memory. | "Home" place with its sources and observation count. The expiry is about 90 days after the last observation. | | | |
| M3 | Post a chat-like notification: `-t "Rahul" tag "meet me 📍 Koregaon Park, Pune"`. | No new place is learned. (Shell posts are not WhatsApp, so for a strict test use a real WhatsApp message if possible.) | | | |
| M4 | Post `-t "Delivered" tag "Delivered to Flat 402, Tower 3, Baner, Pune 411045"`. | Place label "Baner, Pune". No flat, tower or PIN number anywhere in Memory. | | | |
| M5 | Correct a place label, then post the same evidence again. | The corrected label stays and never expires. | | | |
| M6 | Forget a place, then post the same evidence again. | The place is not relearned. | | | |
| M7 | Force-stop, relaunch, open Memory. | Places persist and observation counts do not jump (only new events are scanned). | | | |
| M8 | Log after M2–M7 | `MarksyMemory: ingest events=N upserts=M` with counts only, no labels. | | | |

## 5. Calendar connector (EPIC-021)

| # | Step | Expected | Observed | PASS/FAIL | Evidence/log |
|---|------|----------|----------|-----------|--------------|
| C1 | Health → Direct sources → Calendar toggle. Deny the permission. | The toggle stays off. The text shows the mechanism, and nothing is shown as syncing. | | | |
| C2 | Toggle again and grant. | Log shows `calendar-provider: sync start` then `sync ok added=N`. The card shows "Last sync … (+N new…)". | | | |
| C3 | Add an event with location "WeWork Baner" and attendees in the Calendar app. Wait up to 30 min, or toggle off and on to trigger a sync now. | One Marksy event. The body has the Location line and no attendee names. Memory may learn "WeWork Baner" and never learns an attendee's name. | | | |
| C4 | Toggle off and on, or wait for the next periodic run. | No duplicate rows (`unchanged` in the log). | | | |
| C5 | Edit the event's title, then sync. | The row is updated in place, not duplicated. | | | |
| C6 | Delete the event, then sync. | The row is resolved: `removed=1`. | | | |
| C7 | Force-stop, relaunch, sync. | Incremental sync from the persisted cursor: no re-import. | | | |
| C8 | Revoke Calendar permission in system settings, return to Health. | Card shows "Permission was removed; reconnect…". The log shows `skipped, state=NEEDS_PERMISSION`. No crash. | | | |
| C9 | Airplane mode, then sync. | Calendar still syncs, because it is a local provider. | | | |

## 6. Gmail connector (EPIC-021)

| # | Step | Expected | Observed | PASS/FAIL | Evidence/log |
|---|------|----------|----------|-----------|--------------|
| G1 | Health → Direct sources → Gmail. | "Not available yet: needs Google sign-in setup…". The toggle is disabled. | | | |
| G2 | DB check after a full session | No rows with `sourceKey LIKE 'gmail-api:%'`. Gmail arrives only through its notifications. | | | |
| G3 | Log check | No Gmail sync start lines (Gmail is NOT_CONFIGURED). There is never a token in any log. | | | |

The auth-required, expired, revoked and rate-limited states are covered only by unit tests. They need a configured Google OAuth client (package plus signing SHA-1 plus the restricted `gmail.readonly` scope verification) before they can be seen on a device.

## 7. Health / connector UI and worker

| # | Step | Expected | Observed | PASS/FAIL | Evidence/log |
|---|------|----------|----------|-----------|--------------|
| H1 | `adb shell dumpsys jobscheduler \| grep -A3 marksy`, or WorkManager diagnostics | Periodic `marksy-connector-sync` is scheduled only while a connector is enabled. | | | |
| H2 | Disable all connectors. | Periodic work is cancelled. | | | |
| H3 | Card after a failure (for example C8) | Shows the last problem and the attempt count, and does not claim "syncing". There is no live progress indicator by design. | | | |
| H4 | `adb shell am kill com.marksy.os` while a sync is running (hard to time), then relaunch. | No duplicate rows. The cursor is either the old one or the new one, never partial. | | | |

## 8. SMS

Expected runtime behaviour: Marksy has no SMS permission and is not the default SMS app. SMS content reaches Marksy only as notifications from the messaging app, through the notification listener. The Health card says so.

| # | Step | Expected | Observed | PASS/FAIL | Evidence/log |
|---|------|----------|----------|-----------|--------------|
| S1 | `dumpsys package com.marksy.os \| grep -i sms` | No SMS permissions. | | | |
| S2 | Receive a real bank SMS. | It appears as a notification-sourced event from the messaging app package. | | | |

## Known limitations to confirm, not fix, on the device

- Cross-source dedup exists only for money events (transaction reference, or amount plus direction within 10 minutes). A Google Calendar reminder notification and the Calendar-provider record for the same meeting stay as two rows.
- Calendar location fields holding only a place name, with no venue word and no address shape (for example "Baner, Pune"), are not learned from calendar records. This is deliberate, so that "Last, First" names are never stored as places.
