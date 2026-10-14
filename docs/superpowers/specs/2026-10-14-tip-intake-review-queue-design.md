# Tip intake review queue — design

Status: approved 2026-10-14 (G1), with D7 and D11 as recorded in the plan · Repos: marksy-api (owner), admin-app, marksy-os (this spec only)
Amends: `2026-09-29-marksy-central-tip-ledger-design.md` §2, §4, §5.2, §6.2, §6.6, §8.1, §9, §11 (see §11 here).
Approval of this spec authorizes no production survey, migration, merge or deployment (§12).

## 1. Goal

- `tips` holds only calls that passed every intake check. Anything else waits in `tip_intake_reviews`. A pending
  call enters `tips` only when the tracker's official pass verifies it (§8); a rejected one only when an admin
  corrects and resubmits it through the same checks (§6). Otherwise it stays queued or is dismissed.
- Scoring starts at the server receive time. An earlier claimed creation time is used only when §4 proves it
  cannot improve the result.
- No admin decision can use outcome knowledge to improve a record (§6, mandatory anti-hindsight control).
- Scorecard definitions and the trust score are unchanged; displayed counts do not move at migration (§7).
- The channel Trust Score (`app/tip_scorecard.py`) and the Prediction Score / Prediction Trust Score
  (`predictions`, `prediction_outcomes`, `app/prediction_trust_score.py`) stay separate. The queue feeds only
  the channel scorecard and never writes a prediction score or outcome (§7).

Not changed: the tracker's rules (central §6.1, §6.3–§6.7), the one-sided 10% entry rule, the phone app,
scorecard definitions, the frozen SPG/ML tables (none of them read `tips`).

## 2. Terms

- **R** — server receive time of a call's first receipt (`received_at`).
- **C** — claimed creation time, when the sender supplies one: phone `devicePostedAt`; `tipAsOf` on `POST /tips`,
  report paste and the Supabase webhook; the report date when a report carries only a date (date-only). Admin
  re-parse uses the receipt's `device_posted_at`, with R = the receipt's `recorded_at`. Marksy calls have no C
  (R = publish time).
- **Scoring start** — `tips.first_seen_at`. Every tracker rule runs from it, unchanged.
- **S(t)**, the session of an instant — the latest NSE trading day whose 09:15 IST open is at or before t, from
  `last_trading_day_on_or_before` over the active market calendar (weekends and recorded holidays are not trading
  days). A holiday missing from the calendar only makes S stricter. A calendar lookup failure is ambiguous.
- **Market minute** — any instant in [09:15, 15:30) IST on a trading day.

## 3. The gate

One function, `admit_or_queue` (`app/tip_ledger.py`), replaces the insert in `_new_tip`. Every path that creates
a tip calls it: `_record_tip`, `_record_revision`, `_reparse`, `register_prediction`, `open_rating_tip`, and admin
resubmit (§6). In order:

1. Scoring start T by §4, or a queue reason.
2. The central §6.2 checks at T: NO_DIRECTION, INCONSISTENT_LEVELS, UNRESOLVED_SYMBOL, ENTRY_OFF_MARKET (one-sided
   10% against `check_entry` at T; a Marksy call against `scan_bar_check`, unchanged).
3. Pass with a price → `tips` row, ACTIVE, exactly as today. Pass but no same-session price yet → queue
   PENDING_CHECK. Any failure → queue REJECTED with the reason.

Matching (central §5.2): `find_matchable` returns the ACTIVE tip with the key, else an open queue item with it
(PENDING_CHECK, or REJECTED or DISMISSED within its horizon, so copies of a dismissed call never grow the counts), and the receipt attaches to that item
(`tip_receipts.intake_review_id`). Copies never create a second item. The EPIC-803 `(source, source_reference)`
idempotency reads queue items too. EXIT receipts still attach only to ACTIVE tips.

Responses: `POST /tips/ingest-text` returns `tipId: null` for a queued call (the phone already records a blank
`tipId` as a receipt only, as for UNPARSED — `gateway/MarksyTipsApiClient.kt:22-23`). `POST /tips` and the Supabase webhook return `TipAccepted` with `tipId` = the item's reserved
`public_id` and `status: IN_REVIEW` — accepted, not an error, so senders don't retry. A queued Marksy call sets
`Prediction.status` as a rejected tip does today (INVALIDATED). An identical later Marksy prediction follows the
queued item and is closed INVALIDATED, where it used to become a MERGED_DUPLICATE of the rejected tip.

## 4. Scoring start (claimed-time policy)

The first rule that matches decides. R is the default; a claim never moves the start without proof.

- **CT1** No C → T = R (`SERVER_RECEIPT`).
- **CT2** C is date-only: its date before S(R) → queue `CLAIMED_TIME_OLDER`; otherwise T = R (a date is not a
  time).
- **CT3** C has no timezone, or C > R + 120 s → queue `CLAIMED_TIME_INVALID`. A missing timezone is never
  inferred. This interim rule stands until the G3 format evidence (§12) is reviewed and the user sets the final
  acceptance policy.
- **CT4** R − 120 s < C ≤ R + 120 s → T = R (`SERVER_RECEIPT`). A claim within two minutes of receipt is treated
  as no claim and is kept for the record only (decision D11, 2026-10-14). It needs no market data, so an Upstox
  outage never queues an everyday phone receipt.
- **CT5** S(C) or S(R) cannot be computed → queue `CLAIMED_TIME_UNVERIFIED`.
- **CT6** S(C) < S(R) → queue `CLAIMED_TIME_OLDER`.
- **CT7** Same session and no market minute in [C, R) → T = C (`CLAIMED_NO_TRADING`). Tracking is identical to
  starting at R (central §6.1); C is kept for the record.
- **CT8** Same session with market minutes. W = [max(C, 09:15), min(R, 15:30)) on S(R). Read that day's 1-minute
  bars from the tracker's source (`MinuteBarSource`).
  - Evidence required: the bars load and the existing covering-bar rule (`_intraday_price`) prices both ends of W.
    Otherwise → queue `CLAIMED_TIME_UNVERIFIED`. An older claim that cannot be verified, for example during an
    Upstox outage, is queued for review; it is never admitted silently at a new scoring start.
  - T = C (`CLAIMED_MARKET_VERIFIED`) only if every bar overlapping W — including the minute covering C, since a
    touch inside it cannot be ordered against C — shows:
    a. no touch of a stated entry — BUY `low ≤ entry_high`, SELL `high ≥ entry_low`;
    b. no touch of the target or stop — BUY `high ≥ target` or `low ≤ stop_loss`, SELL `low ≤ target` or
       `high ≥ stop_loss`;
    c. with no stated entry, the price at W's start (`P_C`) is no better for the call than the price at its end
       (`P_R`) — BUY `P_C ≥ P_R`, SELL `P_C ≤ P_R`.
  - Any of a–c fails → T = R (`SERVER_RECEIPT_CLAIM_REJECTED`). The claim could only have helped the record; R is
    the outcome-neutral default, so no review is needed.

Every tip and queue item stores `received_at` (R), `claimed_created_at` and `claimed_time_source`
(`DEVICE_POSTED_AT` | `TIP_AS_OF` | `REPORT_DATE`), `scoring_start_at` (T) and `scoring_start_basis`; CT8 also
stores its evidence (window, both prices, which check failed). An item queued by CT2, CT3, CT5 or CT6 stores
T = R (`SERVER_RECEIPT`), so every item has one scoring start; its reason records why the claim was refused.

## 5. Queue, audit and the validated-only invariant

**tip_intake_reviews**
- `id`, `public_id` (unique; becomes the tip's `public_id` on promotion; migrated rows keep theirs)
- `state` PENDING_CHECK | REJECTED | PROMOTED | DISMISSED · `reason` · `rejected_status` (UNSCORABLE |
  DATA_UNRESOLVED: what `intake_rejection` returns, the original status for migrated rows, UNSCORABLE for the
  CLAIMED_TIME reasons) · `origin` INTAKE | MIGRATED_0193 · `origin_tip_id` (unique)
- the call as the gate saw it: `source_channel_id`, `caller_id`, `prediction_id`, `stock_id`, `symbol`,
  `direction`, `entry_low`, `entry_high`, `target`, `stop_loss`, `horizon_sessions`, `horizon_basis`,
  `match_key`, `match_key_version`, `parser_version`, `source`, `client_name`, `source_reference`
- §4 times and basis, `first_seen_price`, `entry_check_price`, `entry_check_basis`, `check_evidence` (JSON)
- `payload` (JSON): the full row the gate would have inserted; for migrated rows `to_jsonb` of the original tips
  row, verbatim, plus `legacy_rows` (§9)
- `closed_at` (the scoring start for new items; the original `tips.closed_at` for migrated ones, since backfilled
  rejections closed at a time that can differ from `first_seen_at`)
- `promoted_tip_id` (unique), `decided_at`, `decided_by`, `created_at`, `updated_at`
- Uniqueness mirrors `tips`: `prediction_id`, `(source, source_reference)`, one TIP receipt per
  `(intake_review_id, user_id, medium)`.

**tip_intake_review_events** — append-only (the ORM guard refuses update and delete): `review_id`, `at`,
`actor` (`system` | `tracker` | `admin:<user id>`), `action` (QUEUED, RECEIPT_ATTACHED, RECHECKED, PROMOTED,
RESUBMITTED, DISMISSED, MIGRATED), `before`, `after` (JSON), `note`.

**Columns added** — `tip_receipts.intake_review_id` (kept after promotion, for history); on `tips`:
`claimed_created_at`, `claimed_time_source`, `scoring_start_basis`, `intake_review_id` (no FK:
`promoted_tip_id` already links the pair, and a two-way FK cycle cannot be built on the suite's SQLite schema).

**Invariant** — `tips` CHECK `ck_tips_validated_only`:
`status <> 'PENDING_CHECK' AND status <> 'UNSCORABLE' AND COALESCE(reason, '') NOT IN ('NO_DIRECTION',
'INCONSISTENT_LEVELS', 'UNRESOLVED_SYMBOL', 'ENTRY_OFF_MARKET', 'CLAIMED_TIME_OLDER', 'CLAIMED_TIME_INVALID',
'CLAIMED_TIME_UNVERIFIED')`. UNSCORABLE comes only from intake; the tracker's DATA_UNRESOLVED reasons
(MISSING_BARS, NO_INTRADAY_BARS, NO_HISTORY) stay allowed. The ORM guard (`app/tip_ledger_guard.py`) refuses the
same on insert and update with a named error, before the database does. The CHECK is declared on the model as well
as in 0193, because the test suite builds its schema from the models. A read-only preflight runs the same
predicate over every `tips` row and must return zero (§9 step 8, §12 G3).

## 6. Admin review and the anti-hindsight control (mandatory)

Admin API (existing admin tip-ledger router, admin scope):
- `GET /api/v1/admin/intake-reviews?state=&reason=&channel=` (cursor) and `GET /api/v1/admin/intake-reviews/{id}`
  with receipts, events and check evidence (siblings of `/admin/receipts` and `/admin/tips`).
- `POST /api/v1/admin/intake-reviews/{id}/resubmit` — corrected `symbol`, `direction`, `entryLow`, `entryHigh`,
  `target`, `stopLoss`, `horizonSessions`; `note` required. REJECTED items only.
- `POST /api/v1/admin/intake-reviews/{id}/dismiss` — `reason` (NOT_A_TIP | UNFIXABLE_TERMS | DUPLICATE |
  OFF_MARKET_CONFIRMED | OTHER),
  `note` required. REJECTED items only: a PENDING_CHECK item is decided by the tracker, so a pending loser cannot
  be dismissed.

No overrides: a resubmit runs the full gate. An admin cannot set, keep or choose a claimed time, and cannot pass an
off-market entry or a failed time check.

**Anti-hindsight rule.** Let T0 be the item's stored `scoring_start_at` (R, or a §4-verified C; §4 is not re-run at
resubmit, so a refused claim can never return) and A the admission time (server clock at resubmit).
- **AH1** Scoring starts at T0 only if no market minute lies in [T0, A) — nothing about the call's outcome could
  have been seen. This covers both cases: admitted after the first session following receipt, and admitted
  inside it after trading.
- **AH2** Otherwise scoring starts at A (`ADMIN_ADMISSION`): the gate's checks run at A, and the central §6.1/§6.3
  rules then put every scored price at or after A — the covering minute bar at A in market hours, else the next
  session's open. R, A and the review delay A − R stay on the tip and in the event.
- AH1/AH2 need no market data, so missing evidence cannot weaken them.
- Admin re-parse of an UNPARSED receipt (`POST /admin/receipts/{id}/reparse`) is an admission too and follows
  AH1/AH2 (amends central invariant 7). A re-parse whose claim §4 refuses scores from the re-parse once a market
  minute has passed since R; before that it is queued with the refusal reason.
- The tracker's promotion of PENDING_CHECK items (§8) is mechanical, outcome-blind and keeps T0.

Dismissed items stay auditable and count as rejected (§7); they never count as scored outcomes. Every admission,
dismissal and edit writes an event with actor, time, before/after and note.

## 7. Scorecards

Definitions are unchanged. No queue transition (queue, attach, promote, resubmit, dismiss, migrate) changes an
existing tip's outcome, status, close or returns, or any prediction's score or outcome. The only prediction write
is today's: a Marksy call that fails intake closes its prediction INVALIDATED. Migration leaves every scorecard
figure unchanged, and an admission is scored only from its §6 start.

The fact loader (`app/tip_scorecard_query.py`) also reads queue items that are not
PROMOTED, under the conditions it applies to tips (`match_key` present, scoring start ≤ `as_of`), so counts stay
as they are:
- PENDING_CHECK → status PENDING_CHECK, open (as today).
- REJECTED or DISMISSED → `rejected_status`, closed at the item's `closed_at` (§5); no outcome, no returns, so never in a percentage, return or trust score (central §8.1, §8.2).
- Channel, caller, engine, symbol and horizon filters read the item's columns; customer scope reads
  `tip_receipts.intake_review_id`; date basis `first_seen_at` reads `scoring_start_at`.

Queue items leave the customer and admin tip lists (`/me/tips`, instrument calls, `/admin/tips`); admins see them
on the review page. `GET /tips/{id}` for a queued `public_id` returns 404. Alerts are unchanged: intake failures were
never alerted; a promoted tip gets ENTERED/CLOSED alerts like any tip, and TIP_NEW only while its `first_seen_at` is
inside the sweep window (`app/tip_alerts.py:_new_candidates`), exactly as a PENDING_CHECK tip that turns ACTIVE
today.

## 8. Promotion

`resolve_pending_entry_checks` (`app/tip_tracking_job.py`) reads PENDING_CHECK queue items instead of tips. One
transaction per item:
1. Lock the item (`SELECT … FOR UPDATE`); skip unless still PENDING_CHECK.
2. Re-run the checks at its stored scoring start, with today's evidence order (first-seen price, else session
   range).
3. Pass → insert the tip with the item's `public_id` and `intake_review_id`; mark the item PROMOTED with
   `promoted_tip_id`; set `tip_id` on its receipts; write the event. Fail → REJECTED with the reason. Still no
   price → unchanged.

Retries are safe: the state check runs under the row lock, and `tips.public_id`, `promoted_tip_id` and
`uq_tips_active_match_key` are unique. A crash before commit rolls everything back. If an ACTIVE tip with the same
key appeared meanwhile, the item's receipts attach to it and the item is PROMOTED to that tip — never a second tip.

## 9. Migration 0193

**Upgrade** — one transaction:
1. Create the §5 tables and `tip_receipts.intake_review_id`. The new `tips` columns wait for step 8, so each payload
   is the exact 0192-shaped row and the downgrade checksum compares like with like.
2. Select the move set M: tips with status PENDING_CHECK or UNSCORABLE, or reason in the intake rejection reasons.
3. Inventory every reference to M and handle or abort:
   - `tip_receipts.tip_id` — handled: relinked to the item (step 5).
   - `external_tip_comparisons`, `external_tip_outcomes` (retired EPIC-803 tables) — handled: copied verbatim with
     `to_jsonb` into the item's `payload.legacy_rows`, then removed.
   - `tip_daily_progress`, `tip_source_exits`, `stock_ratings.short_tip_id`/`long_tip_id`, and `revises_tip_id` /
     `merged_into_tip_id` on tips outside M — unhandled: abort and list the ids. None is expected: rejected tips are
     never tracked, exits and revisions attach to ACTIVE tips, rating tips are never rejected.
   - `recommendation_alerts` rows with `source_table = 'tips'` and `source_id` in M (a non-FK reference) — abort if
     any (intake failures were never alerted).
4. Insert one item per M row: origin MIGRATED_0193, `origin_tip_id`, the tip's `public_id`, PENDING_CHECK or
   REJECTED, the original reason, terms and times (`scoring_start_at` = `first_seen_at`,
   `scoring_start_basis = LEGACY`), `payload` = `to_jsonb(tip)`, one MIGRATED event each.
   `ON CONFLICT (origin_tip_id) DO NOTHING`, so a re-run moves nothing twice.
5. Relink receipts; abort unless the relinked count equals the count of receipts that pointed into M.
6. Abort unless the md5 of M's ordered `to_jsonb` rows, taken before step 4, equals the md5 of the stored
   payloads (without `legacy_rows`).
7. Delete M from `tips` (migration SQL; the ORM delete guard stays for application code).
8. Add the new `tips` columns, then the preflight: abort, listing ids, if any remaining `tips` row violates the §5
   predicate.
9. Add `ck_tips_validated_only`.

**Downgrade** — the exact inverse for migrated rows: re-insert each tip from its payload with its original id,
restore receipt links and `legacy_rows`, drop the CHECK, columns and tables, and verify the checksums against the
pre-upgrade values. It refuses if any data exists that the old schema cannot hold without loss: an INTAKE-origin
item, an event other than MIGRATED, or a tip with a claimed time or a basis other than `SERVER_RECEIPT`. After that point, recovery means rolling forward or
restoring the §12 backup.

**Application references changed**: `app/tip_ledger.py`, `app/marksy_tips.py`, `app/tip_tracking_job.py`,
`app/tip_scorecard_query.py`, `app/tip_ledger_guard.py`, `app/tip_vocabulary.py`, `app/tip_ledger_admin.py`,
`api/services/tips.py`, `api/services/admin_tip_ledger.py`, `api/routers/admin_tip_ledger.py`. Read-only
effect only: `api/services/ledger_tips.py`, `app/tip_alerts.py`, `app/user_follows.py`, `api/services/alerts.py`,
`app/external_tip_scoring.py`. `app/tip_backfill.py` (one-off central §12) cannot recreate a failed row: the guard
and CHECK refuse it.

## 10. Tests (TDD, marksy-api — each red first)

Claimed time (pure policy, fixed calendar fixture):
- No claim → R. Date-only, same session → R; previous session → OLDER.
- Naive C → INVALID. C > R + 120 s → INVALID. R − 120 s < C ≤ R + 120 s → R, with no bar read: C = R − 119 s
  with Upstox down → R, not queued; C = R − 120 s with Upstox down → UNVERIFIED; C = R − 30 s across the 09:15
  open (previous session) → R, not OLDER.
- No trading in between: Fri 16:00 → Sat 10:00 and Fri 16:00 → Mon 09:14 → C. Fri 16:00 → Mon 09:15 → OLDER.
- Pre-open claim Mon 09:00, R Mon 10:00 → OLDER (S(C) is Friday).
- Holiday Wed: Tue 16:00 → Wed 12:00 → C; claim on the holiday with R Thu 10:00 → OLDER.
- Boundaries with R 10:00: C 09:14:59 → OLDER, C 09:15:00 → CT8. C 15:30:00 with R 16:00 → CT7 (no market
  minute). C 15:29:59 with R 15:32:00 → CT8 over one minute.
- Calendar failure → UNVERIFIED.
- CT8: neutral window → C; target, stop or entry touched in W → R; no stated entry with a better P_C → R, worse →
  C; bars unavailable, or not pricing both ends of W → UNVERIFIED.
- Forged past: hours earlier in the same session with a favourable move → R; previous day → OLDER.

Gate and queue:
- Each failure reason queues and inserts no tip; no price queues PENDING_CHECK; a pass inserts.
- A later copy attaches its receipt to the open item; no second item; source-reference idempotency spans the queue.
- Marksy corrupt price → queued, Prediction INVALIDATED; rating tips pass through the gate.
- `POST /tips` and the webhook → accepted, IN_REVIEW; ingest-text → `tipId` null.

Promotion:
- Pending → promoted (one tip, `public_id` reused, receipts relinked); pending → rejected; still pending.
- Run twice, and from two sessions at once → exactly one tip; a failure before commit leaves no partial state; an
  ACTIVE same-key tip → attach, no second tip.

Admin and anti-hindsight:
- Resubmit with fixed terms → tip; still failing → REJECTED with an event; off-market terms resubmitted unchanged →
  REJECTED; no request field can set a start or claimed time.
- Dismiss REJECTED → DISMISSED and counted as unscorable; dismissing PENDING_CHECK → refused.
- Every action writes an immutable event with actor, before/after and note.
- Received 18:00, admitted 21:00 → start T0. Received 10:00, admitted 14:00 the same day → start A. Admitted after
  the first session → start A, scored prices at or after A.
- Winner selection: received Mon 10:00, target touched Mon 11:00, admitted Tue → no TARGET_HIT from Monday; the
  channel's trust score and success rate equal those without the admission.
- Loser dismissal: stop touched before the decision; dismissing and admitting give the same trust score and success
  rate (admission is forward-only); the dismissed item counts as unscorable.
- Re-parse of an UNPARSED receipt after trading since its R → dated at the re-parse.

Invariant and migration:
- Raw SQL insert of a PENDING_CHECK, UNSCORABLE or intake-reason row → IntegrityError; ORM insert → guard error.
- 0193 on a Postgres fixture DB (its SQL uses `to_jsonb`, `md5` and `ON CONFLICT`; the suite's SQLite cannot run
  it, CI has no Postgres server, so these run against the local Postgres container): moves exactly M; payloads equal the original rows; receipts relinked; checksums equal;
  legacy rows copied; aborts on each unhandled reference; a re-run is a no-op; downgrade restores ids, rows and
  checksums; downgrade refuses after new intake data.
- Scorecard parity before and after for every channel, caller, engine, customer and all.

Regression: the existing tip suites (`tests/test_tip_*`, `tests/test_api_tips*`, `tests/test_external_tip_*`).
admin-app: page tests in the existing RTL style — list and filters, a failed resubmit shows its reason, dismiss
requires a reason.

## 11. Conflicts with the central spec, resolved

- **Invariant 5, "tips and receipts are never deleted"** — still true for every tip that passed intake.
  Migration 0193 is the one exception: it moves rows that are not tips under this spec, verbatim (original id,
  `public_id`, every column), into the queue, with a backup, checksums and an exact downgrade. Receipts are never
  deleted; only their link moves.
- **Invariant 5 / §4, `first_seen_at` = "server time of the first receipt"** — `first_seen_at` is now the scoring
  start: R by default, a §4-verified C, or the admission time (§6). `received_at` keeps R. It stays immutable once a
  tip exists.
- **Invariant 7, re-parse "dated from the receipt's original server time"** — only under AH1; otherwise from the
  re-parse (§6).
- **§4 statuses** — PENDING_CHECK and UNSCORABLE no longer occur in `tips`; DATA_UNRESOLVED comes only from the
  tracker. Lifecycle columns are unchanged.
- **§5.2 matchable "rejected at intake"** and **§6.2 rejections** — the same rules; the result is a queue item, not a
  tip. **§6.6** `closed_at` for intake rejections no longer applies.
- **§8.1 "Total tips includes unscorable"** — kept by reading queue items as facts (§7); no definition changes.
- **§9 / §11** — adds the admin review routes and screen.
- **The original 2026-10-14 request to score an old tip from its generation time** is superseded by §4 and §6
  (user decisions, 2026-10-14): an older claim goes to review, and an admission after trading is scored from the
  admission.

## 12. Files and rollout gates

marksy-api: alembic `0193_tip_intake_reviews`; `app/tip_claimed_time.py` (new, the §4 policy, pure); the §9
application files; `scripts/sql/tip_intake_review_survey.sql` (read-only inventory, checksums, preflight; pure SQL so
it runs through psql in the prod pod before the PR merges, with a `.py` wrapper for local databases) and
`scripts/tip_intake_review_verify.py` (counts, checksums, receipt links, scorecard parity).
admin-app: `src/pages/IntakeReviewPage.tsx` with its test, `src/api/tipLedger.ts`, navigation.
marksy-os: this spec and the central-spec amendments only.

No production read, change, merge or deployment happens until the gate before it passes:
- **G1** This spec approved.
- **G2** Implementation reviewed and green locally. The marksy-api PR stays unmerged: any deploy builds `main` and
  would apply 0193.
- **G3** Read-only prod survey (the user runs it): counts of M by status and reason, every §9 reference count, alert
  references, checksums of M, NULL-status rows, the preflight predicate over rows outside M — checked against the
  expectations above. It also counts claimed-time formats (`tipAsOf` by source: zoned, naive, date-only, other),
  never raw values, as the evidence for the final CT3 policy.
- **G4** Backup: `pg_dump` of `tips`, `tip_receipts`, `external_tip_comparisons`, `external_tip_outcomes`, plus a
  schema-only dump, kept on the VPS and restore-tested there into a throwaway Postgres pod that is deleted
  afterwards (row counts and checksums match G3). No production data or secret is copied to the laptop (D7). The
  off-VPS copy is the existing encrypted nightly backup to Google Drive (`market-agent-backup`), run on demand just
  before G6, with its restore drill proven at G4.
- **G5** Rehearsal on a throwaway local Postgres, never on production data (D7): schema from `alembic upgrade
  0192_tip_entry_check`, synthetic minimum fixtures covering every status, reason and reference category the G3
  survey reports. Upgrade → verify → downgrade (checksums equal the pre-upgrade values) → upgrade again, plus
  every abort path, receipt links and scorecard parity. The container and its data are deleted afterwards.
- **G6** The user approves production: merge, deploy (the migrate Job runs 0193), then the same verify on prod.
  `vps-deploy.sh` runs under `set -euo pipefail` and waits for the migrate Job to complete before rolling out, so
  an aborted 0193 leaves the old code and the old schema serving.
