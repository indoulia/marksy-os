# Tip Ledger Phase 3a (Admin APIs) + Phase 5 (Admin-App Screens) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give operators the ledger's admin surface and the admin-app screens on top of it:
- list, rename and re-horizon channels, and manage their scoped aliases;
- merge channels (open tips are re-keyed, duplicates retired) and callers;
- browse tips with their receipts and customers;
- review unparsed and orphan-exit receipts, and re-parse the unparsed ones.

**Architecture:**
- **Part A (marksy-api, one PR).**
  - `app/tip_ledger_admin.py` (new) holds the domain rules: `stated_terms_of`, `merge_channels`, `merge_callers`, channel updates, aliases and caller renames. It never writes a call term.
  - `app/tip_ledger.py` gains `reparse_receipt` (invariant 7), and its alias lookup takes a `FOR SHARE` row lock so a merge and an intake serialize.
  - `app/tip_tracking_job.py` re-reads each tip under a row lock and skips one a merge has retired.
  - `api/schemas/admin_tip_ledger.py`, `api/services/admin_tip_ledger.py` and `api/routers/admin_tip_ledger.py` (new) serve `/api/v1/admin/{channels,callers,tips,users/{userId}/tips,receipts}` behind the existing `admin` scope.
  - `ingest_message`'s parse-to-intake mapping becomes `message_intake`, which live intake and re-parse share.
  - No migration.
- **Part B (admin-app, one PR, after Part A is in production).**
  - `src/api/client.ts` gains `http.del` and `http.getPage` (cursor pages). `src/api/tipLedger.ts` (new) wraps the endpoints.
  - New pages: Channels, Callers, Tip Ledger (with a receipts drawer), Receipt Review, and customer → tips at `/users/:userId/tips`. They are built only from the existing `Toolbar`, `DataTable`, `Modal`, `Tabs` and the `tokens.css`/`global.css` classes.

**Tech Stack:**
- Part A: Python 3.12, FastAPI 0.139, SQLAlchemy 2.0.52 (`Mapped`/`mapped_column`), Alembic 1.19, pytest on SQLite 3.49 (tests) and PostgreSQL (prod).
- Part B: React 18.3, TypeScript 5.6, Vite 5.4, @tanstack/react-query 5.59, react-router-dom 6.27, vitest 2.1 with Testing Library 16 and MSW 2.6 on jsdom.

**Spec:** `C:\AIAgent\marksy-os\docs\superpowers\specs\2026-09-29-marksy-central-tip-ledger-design.md`. This plan uses:
- §2 invariants 5, 7 and 10;
- §4 `channels`, `channel_aliases`, `callers`, `tips`, `tip_receipts`;
- §5.2 match_key v1 and "Merging (admin)";
- §9 "Admin (admin scope)", except `/admin/scorecards`;
- §11, except Scorecards.

In-flight plans this builds on. Both merge before Part A starts:
- `C:\AIAgent\marksy-os\docs\superpowers\plans\2026-09-30-tip-ledger-phase-4a-capture.md`, Part A: `channel_aliases.scope` with unique `(scope, alias)`, `is_mask_only`, `MaskedChannelLabelError`, migration `0184_channel_alias_scope`.
- `C:\AIAgent\marksy-os\docs\superpowers\plans\2026-09-30-tip-ledger-phase-2b-marksy-predictions.md`: Marksy tips carry `prediction_id` and have no receipts; identical engine calls become MERGED_DUPLICATE; `MARKSY_CHANNEL_NAME`; migration `0183_tips_prediction_unique`.

## Global Constraints

**Both parts**
- Commit once per task. Commit messages end with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`. PR bodies end with `🤖 Generated with [Claude Code](https://claude.com/claude-code)`.
- **Merging and deploying are authorized.**
  - Merge with `gh pr merge --merge --delete-branch` once the part's final test set is green.
  - Deploy with `ssh marksy@103.178.160.233 'bash ~/deployment/server/vps-deploy.sh'`.
  - GitHub Actions don't run (account billing), so an UNSTABLE status is expected and doesn't block the merge.
- Comments are one line at most and explain only a non-obvious WHY. Make no unrelated refactors.
- **Out of scope:**
  - `/admin/scorecards`, every §8 item, and the admin-app Scorecards screen. These are Phase 3b, after the tracker and Marksy predictions land.
  - Customer APIs: `/me/*`, `/instruments`, `/scorecards`.
- Spec §2.5, verbatim: "Call terms (channel, symbol, direction, stated entry, target, stop-loss, stated horizon) and `first_seen_at` are immutable. Tips and receipts are never deleted."
- Spec §2.7, verbatim: "Re-parsing applies only to unparsed receipts. It may create a tip (dated from the receipt's original server time) but never alters or duplicates an existing tip."
- Spec §2.10, verbatim: "Customer APIs never expose another customer's identity; admin APIs may."
- Spec §5.2, verbatim: "Merging (admin) sets `canonical_channel_id` (or `canonical_caller_id`). Historical tips keep `source_channel_id`; scorecards aggregate through the canonical id. ACTIVE tips of both channels are re-keyed; if two now share a key, the later `first_seen_at` becomes MERGED_DUPLICATE with `merged_into_tip_id` set, its receipts move to the earlier tip, and it is excluded from scorecards but kept."

**Part A (marksy-api)**
- Repo: `C:\AIAgent\marksy-api`. Work in the worktree `C:\AIAgent\marksy-api-phase3a` on branch `feat/tip-admin-api`, created from `origin/main` **after** Phase 4a Part A and Phase 2b have merged. Task A1 Step 1 checks this and stops if either is missing.
- **No migration.** Part A adds no table, column or index, so there is nothing to chain or re-parent. Task A8 still checks that `alembic heads` prints exactly one head, whatever main's head is then (`0184_channel_alias_scope` or later). If it prints two, stop: main itself is broken.
- **Tests:**
  - Run only the `python -m pytest ...` commands given in each step.
  - Never run the full suite: it takes about 2 hours, and 4 tests already fail on main locally.
  - If a listed test fails, run the same file on `main` in `C:\AIAgent\marksy-api`. If it fails there too, it is pre-existing: note it in the PR and don't fix it.
- `tests/conftest.py` gives the app engine a file DB at `%TEMP%\marksy-pytest-default.db`, and `create_all` never alters an existing table. Part A changes no ORM schema, but Phases 4a and 2b did, so Task A8 deletes that file before the regression set.
- Follow the repo's code style:
  - `Mapped`/`mapped_column`, and no ORM relationships;
  - camelCase DTO fields;
  - the `success(...)` and `cursor_paginated(...)` envelopes;
  - `MRA_*` error codes, raised through `NotFoundError`, `ConflictError` and `ValidationError`.
- **SQLite and time zones.** SQLite drops tzinfo on read, and it stores the wall time of whatever zone it is handed. So pass only UTC-aware datetimes into queries, and pass every datetime read back through `_aware`/`_utc` (assume UTC).
- **The app session.** `SessionLocal` is `autoflush=False, expire_on_commit=False` (`app/db.py:9`). Flush explicitly before a query that must see pending changes. A loaded object is not refreshed by a commit.
- **OpenAPI.** `docs/api/openapi.json` is checked byte for byte by `tests/test_openapi_contract_freshness.py`. Regenerate it with `python scripts/export_openapi.py` in every task that adds routes, and commit it with that task.

**Part B (admin-app)**
- Repo: `C:\AIAgent\admin-app`. Its checkout is on the unmerged branch `feat/gateway-qr-provisioning`; leave it there. Work in the worktree `C:\AIAgent\admin-app-phase5` on branch `feat/tip-ledger-admin`, created from `origin/main` (`59e4c62`, the Ingest Tips page).
- **Part B starts only after Part A is deployed.** Task B1 Step 1 checks production and stops if the admin routes are missing.
- **The agent cannot run node, npm, vitest or tsc on this machine.** Node comes from fnm, which activates only in the user's interactive shell (memory `dev-env-node-fnm-gotcha`).
  - Every Part B verify step therefore reads: the controller asks the user to run the given `! npm ...` command in `C:\AIAgent\admin-app-phase5` and paste the output.
  - Tasks still carry full test code, and each RED/GREEN expectation is what the user's run must show.
- **Existing design system only.** No new library, component library or CSS file.
  - `main` has no `PageHeader`. Pages title themselves with `<h2 className="section-title">`, as `TipsIngestPage` does.
  - There is no drawer component. The receipts drawer is the existing `Modal` with `wide`.
- **Routing and nav** follow `src/App.tsx` (one `<Route>` inside the `RequireAuth`/`Layout` element) and `src/components/Layout.tsx` (one `NAV` entry with an inline SVG icon). The topbar crumb is `NAV.find((n) => pathname.startsWith(n.to))`, so no new path may start with `/tips`.
- **react-query keys:**
  - `["channels"]`, `["callers", { channelId }]`, `["receipts", kind]`;
  - `["tip-ledger", "list", filters]`, `["tip-ledger", "customer", userId, filters]` and `["tip-ledger", "receipts", tipId]`, so that invalidating `["tip-ledger"]` refreshes every tip view.
- Tests guard only real logic (memory `test-budget-feedback`): merge gating, filter-to-query mapping, cursor paging and re-parse outcomes. No layout tests.
- **Deploy.** `vps-deploy.sh` pulls `~/admin-app`, builds `marksy-admin:local` from it and restarts `deploy/marksy-admin-web` (`deployment/server/vps-deploy.sh:24,33,47,88`). The same command that ships Part A also ships Part B.

## Review Focus

These are the inputs the spec implies but its happy paths never exercise. Each one has a test in the owning task:

1. **A customer who received the same call through both channels on the same medium.** Moving the duplicate's receipts would break the unique `(tip_id, user_id, medium)` index for kind TIP. That receipt must stay on the duplicate (which points at the earlier tip), and every other receipt must move, with no IntegrityError. Test: Task A2, `test_a_merge_leaves_a_receipt_the_customer_already_holds_on_the_duplicate`.
2. **Re-keying a tip whose row holds values that never entered its key.** The tracker fills a first-seen entry, and a horizonless tip stores the channel default. If either leaks into the new key, the merged tips stop matching later copies. Test: Task A1, `test_stated_terms_rebuild_the_stored_key_after_a_first_seen_entry_fill`.
3. **Merging a channel that others were already merged into.** Updating only the named channel would leave the third channel's tips keyed on a canonical id that no longer exists. A repeat of the same merge must change nothing. Test: Task A2, `test_merging_a_canonical_repoints_the_channels_already_merged_into_it_and_a_repeat_changes_nothing`.
4. **A merge while the tracker holds a stale ACTIVE copy of a tip.** `track_tips` loads its list once, and production sessions never expire loaded objects. Without a re-read, the tracker would close the retired duplicate and score one call twice. Test: Task A3, `test_a_tip_merged_after_the_tracker_loaded_it_is_not_tracked`.
5. **Re-parsing a receipt whose call was open when it arrived but has since closed.** `find_matchable` sees only ACTIVE tips, so a naive re-parse would create a second, back-dated tip of a call that already has a result. Test: Task A5, `test_a_reparsed_call_joins_the_tip_open_when_it_arrived_even_after_that_tip_closed`.

## Resolved ambiguities (decisions this plan makes)

1. **Channel merge semantics (§5.2).**
   - **Direction.** `POST /admin/channels/{id}/merge {intoChannelId}`. `{id}` is absorbed. The target's canonical root survives with its id, name and type unchanged.
     - `{id}` must itself be a canonical root; otherwise the answer is 409 `MRA_CHANNEL_ALREADY_MERGED`. So merging a member can never silently drag its whole group along.
     - A repeat of a merge that already happened answers 200 with `alreadyMerged: true` and changes nothing.
   - **Canonical ids.** Every channel whose `canonical_channel_id` is `{id}` (itself, and anything merged into it earlier) is re-pointed to the survivor root. Chains therefore stay one hop deep, which is all the ledger reads: `match_key_v1(channel.canonical_channel_id, …)` (`app/tip_ledger.py:263`) and `_active_tips` (`app/tip_ledger.py:317`) both use a single hop.
   - **Re-keying.** Every tip with `status = 'ACTIVE'` and a `match_key` in either group gets `match_key_v1(survivor_id, stated_terms_of(tip))`.
     - `stated_terms_of` drops the entry when `entry_basis = FIRST_SEEN_PRICE` and the horizon when `horizon_basis = CHANNEL_DEFAULT`. The row stores the tracker's filled entry and the channel default (`_new_tip`, `app/tip_ledger.py:356-360`), and §5.2 builds the key "from stated terms only".
   - **Collisions.** Tips are grouped by their new key.
     - In each group, the earliest `(first_seen_at, id)` wins.
     - Every other tip becomes `MERGED_DUPLICATE`, with `merged_into_tip_id` = the winner, `reason = CHANNEL_MERGE` and `closed_at` = the merge time.
     - The duplicates are flushed before any tip takes its new key. So the partial unique index `uq_tips_active_match_key` never sees two ACTIVE tips on one key.
   - **Receipts.** All of the duplicate's receipts move to the winner, with one exception.
     - A TIP receipt whose customer already holds a TIP receipt on the winner, on the same medium, stays on the duplicate. The index `uq_tip_receipt_tip_user_medium` forbids the move. The duplicate points at the winner through `merged_into_tip_id`, so that customer still holds the call exactly once.
     - A pending `TipSourceExit` moves to the winner when the winner has none (`uq_tip_source_exit_tip`), because its EXIT receipt moves too.
     - Progress rows stay on the duplicate: tracking belongs to the tip it ran on.
   - **Aliases stay on the merged channel.** `resolve_channel` returns the aliased channel, so a later receipt still records `source_channel_id` "as received" (§4) and keys through the canonical id. Moving the aliases would rewrite where later receipts say they came from.
   - **Concurrency with intake is closed by row locks. The match-key advisory lock alone cannot close it:** an intake of a brand-new call locks a key nobody else holds, and could then insert a tip keyed on the old canonical id after the merge commits.
     - The merge locks, in this order: the two named channel rows; then every member row of both groups, `FOR UPDATE` in id order; then the ACTIVE tips, `FOR UPDATE`; then `_lock_match_key` on every new key, sorted, to follow the same protocol as intake and Marksy registration.
     - Intake's alias lookup takes `FOR SHARE OF channels` on the channel it resolves (`_alias_lookup`). An in-flight intake therefore finishes before the merge re-keys, and a later one waits and then reads the new canonical id.
     - Row locks come before advisory locks on both sides, so the two can't deadlock. SQLite ignores `FOR UPDATE`/`FOR SHARE` and has one writer.
   - **Concurrency with the tracker.** `track_tips` re-reads each tip with `refresh(..., with_for_update=True)` and skips one that is no longer ACTIVE. Its tip list is read once, and `SessionLocal` never expires objects (Review Focus 4).
   - **Idempotency.** Each merge is one transaction, and a repeat is a no-op.
   - **The guard is unchanged and not weakened.** A merge writes `canonical_channel_id`, `match_key`, `match_key_version`, `status`, `merged_into_tip_id`, `reason`, `closed_at`, and receipt and source-exit `tip_id`s. None of these is in `TERM_FIELDS` (`app/tip_ledger_guard.py:11-24`), and no call term is touched.
   - **Marksy.** Either side being MARKSY answers 409 `MRA_MARKSY_CHANNEL_READ_ONLY` (2b: Marksy tips are never merged into a customer channel).
2. **Caller merge.**
   - `POST /admin/callers/{id}/merge {intoCallerId}` sets `canonical_caller_id` on `{id}` and on every caller already pointing at it. The same root, idempotency and Marksy rules apply as for channels.
   - Both callers must sit in one canonical channel group, or the answer is 409 `MRA_CALLER_CHANNEL_MISMATCH`. A caller belongs to a channel (`Caller.channel_id`), and "Rahul of Zerodha" is not "Rahul of a WhatsApp group".
   - Tips keep `caller_id`. It is a fill-once term field (`TERM_FIELDS`), and the caller never enters `match_key_v1` (`app/tip_matching.py:82-94`), so nothing is re-keyed.
3. **Re-parse of UNPARSED receipts (invariant 7).**
   - **Parser.** `app.trade_call_parser.parse_message` (`PARSER_VERSION` = `TCP-001`), run over the stored, already-cleaned `receipt.text`. Its result goes through `message_intake`, extracted from `ingest_message` (`api/services/tips.py:378-414`) so that live intake and re-parse can never map a parse differently.
   - **Channel.** A receipt stores no channel id, so it is resolved again from the receipt's `medium`, `app_package` and `channel_label` through `resolve_channel`. A label that is only masks now answers 422, as at intake (4a).
   - **Which tip it joins,** in order:
     - the tip that was matchable when the receipt arrived: first seen at or before `recorded_at`, and either still ACTIVE or closed after `recorded_at`, or else an intake rejection still inside its horizon then;
     - else the tip matchable now (`find_matchable`);
     - else a tip first seen later, inside the horizon a tip dated `recorded_at` would have had.

     Otherwise it creates a tip with `first_seen_at = received_at = recorded_at`. Each rule prevents a second, overlapping copy of one call, which invariant 7 forbids.
   - **Never alters.** Joining adds a receipt link only. The caller is filled only on a tip the re-parse creates. Re-parse does not run the EPIC-803 `compare_tip`, which serves the phone's live card.
   - **ALREADY_HELD.** If the customer already holds that tip through the same medium, the index allows no second TIP receipt, so the receipt is left UNPARSED and unlinked, and the answer names the tip.
   - **EXIT.** A re-parsed exit becomes `kind = EXIT`, unlinked, with no `TipSourceExit`. Recording one would close an existing tip, which invariant 7 forbids. A late exit also can't be priced at its own time: FINAL progress rows are never rewritten, and `exit_instruction_for` (`app/tip_tracker.py:160-173`) would price it at the next due open, days late.
   - **Orphan exits are review-only** (`/admin/receipts?kind=EXIT`), with no re-link, for the same two reasons.
   - **Response:** `{receiptId, kind, outcome, tipId | null, parserVersion}`. `outcome` is one of CREATED, ATTACHED, ALREADY_HELD, ORPHAN_EXIT or STILL_UNPARSED. `parser_version` records each attempt, so a receipt still in the queue shows which parser last failed on it.
4. **`/admin/tips` filters and pagination.**
   - **Pagination** is cursor-based like `GET /tips` (`api/services/tips.py:884-922`): an offset cursor, `pageSize` ≤ 100, `CursorEnvelope`, newest `first_seen_at` first.
   - **Period** is `startDate`/`endDate`: inclusive IST dates on `first_seen_at`, the §8.3 "Custom" form. The §8.3 presets are left to Phase 3b's shared scorecard resolver, so there is exactly one preset implementation. An admin list needs a window, not "Last 7 trading days".
   - **Filters:**
     - `status`: exact.
     - `channelId`: the canonical group, so merged channels roll up (§8).
     - `callerId`: the canonical caller group.
     - `userId`: `EXISTS` a receipt, so each tip appears once.
     - `symbol`: a prefix.
   - **Rows included.** Marksy's tips are included: the admin sees every call (§1, "no call goes in hiding"), and they show `predictionId` and 0 receipts. MERGED_DUPLICATE tips are included and marked. Pre-ledger rows (`status` NULL) are excluded; like the tracker, they wait for the §12 backfill.
   - `/admin/users/{userId}/tips` is the same query, with the user taken from the path.
5. **Auth scope: reuse `admin`.** Every admin router gates at router level with `require_scope(SCOPE_ADMIN)` (`api/routers/admin_users.py:28-30`). `app/scopes.py` reserves new scopes for new products. `forbid_read_only_mutation` already refuses mutations from a read-only principal.
6. **admin-app information architecture.**
   - **Nav:** Channels (`/channels`), Callers (`/callers`), Tip Ledger (`/tip-ledger`) and Receipt Review (`/receipt-review`), after Ingest Tips.
   - **Customer → tips** is `/users/:userId/tips`. It is reached from a "Tips" button on each Users row and from every customer id in the receipts drawer, so its crumb reads "Users".
   - **The receipts drawer** is the wide `Modal`, opened by clicking a tip row.
   - **Merges** (channels and callers) need a "cannot be undone" checkbox before the danger button enables. The modal then shows what changed.
   - **Invalidation:**
     - a channel edit invalidates `["channels"]`, `["callers"]` and `["tip-ledger"]`;
     - a caller edit invalidates `["callers"]` and `["tip-ledger"]`;
     - a re-parse invalidates `["receipts"]` and `["tip-ledger"]`.
   - **Cursor lists** use `useInfiniteQuery` with a "Load more" button. `DataTable` keeps its client-side pages over the rows loaded so far.
7. **Channel PATCH also sets `captureEnabled`.** `Channel.capture_enabled` is documented as "admin-approved" (`app/models.py:5264`) and has no admin API today.
   - The default horizon is 0–250 sessions and applies only to tips first seen afterwards, because a stored horizon is a term.
   - A rename never changes `Tip.source` (the EPIC-803 column). No channel other than Marksy's may be named "Marksy".
8. **Admin aliases are stored in the form intake compares.**
   - A label goes through `clean_tip_text`, then `normalize_alias`, then the 4a mask-only check (422), exactly as `channel_label_for` and `resolve_channel` do.
   - A PACKAGE alias needs scope `APP_NOTIFICATION` (422).
   - `(scope, alias)` is unique, so a taken one answers 409 `MRA_ALIAS_EXISTS`.
   - Aliases can be created, edited (text, scope, kind) and deleted. The no-delete rule covers tips and receipts only. A deleted alias leaves resolved tips alone.
9. **Error codes:** `MRA_MARKSY_CHANNEL_READ_ONLY`, `MRA_CHANNEL_ALREADY_MERGED`, `MRA_CALLER_ALREADY_MERGED`, `MRA_CALLER_CHANNEL_MISMATCH`, `MRA_ALIAS_EXISTS`, `MRA_CALLER_EXISTS` and `MRA_RECEIPT_NOT_UNPARSED`, all 409. Domain validation answers 422 `MRA_VALIDATION_FAILED`. They are passed to `ConflictError` as `admin_directory` passes `MRA_USER_EXISTS`.

## File Structure

**Part A (`C:\AIAgent\marksy-api-phase3a`)**
- Modify `app/tip_vocabulary.py`: `REASON_CHANNEL_MERGE` and the five `REPARSE_*` outcomes.
- Create `app/tip_ledger_admin.py`: `stated_terms_of`, `merge_channels`, `merge_callers`, `update_channel`, `add_alias`, `update_alias`, `delete_alias`, `rename_caller` and the `LedgerAdmin*` errors.
- Modify `app/tip_ledger.py`: `_alias_lookup` (`FOR SHARE`), and `reparse_receipt`, `_reparse_match`, `_held`, `ReparseResult`, `NotUnparsedError`.
- Modify `app/tip_tracking_job.py` (`track_tips` loop): a locked re-read that skips retired tips.
- Modify `api/services/tips.py`: extract `message_intake` from `ingest_message`.
- Create `api/schemas/admin_tip_ledger.py`, `api/services/admin_tip_ledger.py` and `api/routers/admin_tip_ledger.py`. Modify `api/app.py` to register the router.
- Regenerate `docs/api/openapi.json`.
- Tests: create `tests/test_tip_ledger_admin.py`, `tests/test_tip_reparse.py` and `tests/test_api_admin_tip_ledger.py`. Modify `tests/test_tip_ledger.py` and `tests/test_tip_tracking_job.py`.

**Part B (`C:\AIAgent\admin-app-phase5`)**
- Modify `src/api/client.ts` (`del`, `getPage`) and `src/api/types.ts` (the ledger DTOs). Create `src/api/tipLedger.ts`.
- Create `src/pages/ChannelsPage.tsx`, `src/pages/CallersPage.tsx`, `src/pages/TipLedgerPage.tsx` (exports `TipLedgerPage`, `TipFiltersBar` and `TipsTable`), `src/pages/CustomerTipsPage.tsx` and `src/pages/ReceiptReviewPage.tsx`.
- Modify `src/App.tsx` (routes), `src/components/Layout.tsx` (nav) and `src/pages/UsersPage.tsx` (the "Tips" button).
- Tests: modify `src/api/client.test.ts`. Create `src/pages/ChannelsPage.test.tsx`, `src/pages/CallersPage.test.tsx`, `src/pages/TipLedgerPage.test.tsx` and `src/pages/ReceiptReviewPage.test.tsx`.

---

## Part A — marksy-api: the ledger's admin surface

### Task A1: Worktree, prerequisites, and the stated terms a key was built from

**Files:**
- Create: `app/tip_ledger_admin.py`
- Test: `tests/test_tip_ledger_admin.py`

**Interfaces:**
- Produces `stated_terms_of(tip: Tip) -> StatedTerms`: the terms `match_key_v1` was built from. For any ledger tip, `match_key_v1(canonical_channel_id, stated_terms_of(tip)) == tip.match_key` holds on the channel it was keyed on.

- [ ] **Step 1: Create the worktree and check the prerequisites**

```bash
cd /c/AIAgent/marksy-api && git fetch origin && git worktree add ../marksy-api-phase3a -b feat/tip-admin-api origin/main
cd /c/AIAgent/marksy-api-phase3a && python -m alembic heads
grep -n "uq_channel_alias_scope_alias" app/models.py
grep -n "def is_mask_only" app/tip_text_cleaning.py
grep -n "MARKSY_CHANNEL_NAME" app/tip_vocabulary.py
ls app/marksy_tips.py
```

Expected:
- One head, `0184_channel_alias_scope (head)` or later.
- All three greps print a line, and `app/marksy_tips.py` exists.

If any check misses, **stop**: Phase 4a Part A or Phase 2b has not merged, and this plan builds on both. All later commands run in `C:\AIAgent\marksy-api-phase3a`.

- [ ] **Step 2: Write the failing test**

Create `tests/test_tip_ledger_admin.py`:

```python
"""tip-ledger spec §5.2 merging and §9 admin: re-keying, duplicates, receipts, canonical ids, aliases."""

from __future__ import annotations

from datetime import datetime, timedelta, timezone
from decimal import Decimal

import pytest
from sqlalchemy import create_engine, select
from sqlalchemy.orm import sessionmaker

from app.db import Base
from app.models import Channel, ChannelAlias, Stock, Tip
from app.tip_ledger import Intake, ReceiptInput, TipExtras, record_intake
from app.tip_ledger_admin import stated_terms_of
from app.tip_matching import match_key_v1, stated_terms
from app.tip_vocabulary import (
    ALIAS_PACKAGE,
    CHANNEL_BROKER_APP,
    ENTRY_BASIS_FIRST_SEEN_PRICE,
    HORIZON_BASIS_CHANNEL_DEFAULT,
    HORIZON_BASIS_STATED,
    KIND_TIP,
    MEDIUM_APP_NOTIFICATION,
)

NOW = datetime(2026, 9, 28, 5, 0, tzinfo=timezone.utc)  # Monday, 10:30 IST


@pytest.fixture
def session():
    engine = create_engine("sqlite:///:memory:")
    Base.metadata.create_all(engine)
    db = sessionmaker(bind=engine)()
    db.add_all([Stock(symbol="RENUKA", exchange="NSE", is_active=True), Stock(symbol="RELIANCE", exchange="NSE", is_active=True)])
    upstox = Channel(name="Upstox", type=CHANNEL_BROKER_APP, default_horizon_sessions=20, capture_enabled=True)
    db.add(upstox)
    db.flush()
    upstox.canonical_channel_id = upstox.id
    db.add(ChannelAlias(channel_id=upstox.id, alias="com.upstox.pro", kind=ALIAS_PACKAGE))
    db.commit()
    try:
        yield db
    finally:
        db.close()


def _terms(**overrides):
    values = dict(
        symbol="RENUKA", direction="BUY", entry_low=Decimal("23.62"), entry_high=Decimal("23.62"),
        target=Decimal("26"), stop_loss=Decimal("22.25"), horizon_sessions=None,
    )
    values.update(overrides)
    return stated_terms(**values)


def _receipt(user="user-1", key="e-1", package="com.upstox.pro", label="Upstox",
             text="BUY RENUKA CMP 23.62 SL 22.25 TGT 26"):
    return ReceiptInput(
        user_id=user, device_event_key=key, medium=MEDIUM_APP_NOTIFICATION, app_package=package,
        channel_label=label, text=text, device_posted_at=None, parser_version="TCP-001",
    )


def _intake(session, *, terms=None, caller=None, now=NOW, **receipt):
    intake = Intake(kind=KIND_TIP, terms=terms or _terms(), caller_name=caller, extras=TipExtras())
    return record_intake(session, _receipt(**receipt), intake, now=now)


def _channel(session, package):
    return session.scalar(
        select(Channel).join(ChannelAlias, ChannelAlias.channel_id == Channel.id).where(ChannelAlias.alias == package)
    )


def test_stated_terms_rebuild_the_stored_key_after_a_first_seen_entry_fill(session):
    unstated = _intake(session, terms=_terms(entry_low=None, entry_high=None)).tip
    unstated.entry_low = unstated.entry_high = unstated.entry_price = Decimal("23.60")  # the 2a tracker's one-time fill
    session.commit()
    stated = _intake(session, key="e-2", terms=_terms(
        symbol="RELIANCE", entry_low=Decimal("2500"), entry_high=Decimal("2510"), target=Decimal("2600"),
        stop_loss=Decimal("2400"), horizon_sessions=5,
    )).tip
    canonical = _channel(session, "com.upstox.pro").canonical_channel_id

    assert (unstated.entry_basis, unstated.horizon_basis, unstated.horizon_sessions) == (
        ENTRY_BASIS_FIRST_SEEN_PRICE, HORIZON_BASIS_CHANNEL_DEFAULT, 20,
    )
    assert stated.horizon_basis == HORIZON_BASIS_STATED
    assert [match_key_v1(canonical, stated_terms_of(tip)) for tip in (unstated, stated)] == [
        unstated.match_key, stated.match_key,
    ]
```

- [ ] **Step 3: Run it to verify it fails**

Run: `python -m pytest tests/test_tip_ledger_admin.py -v`
Expected: collection ERROR, with `ModuleNotFoundError: No module named 'app.tip_ledger_admin'`.

- [ ] **Step 4: Create `app/tip_ledger_admin.py`**

```python
"""Admin operations on the tip ledger (tip-ledger spec §5.2 merging, §9 admin): channel and caller merges,
renames and aliases. Marksy's own channel is read-only here, and a tip is re-keyed, never re-termed."""

from __future__ import annotations

from .models import Tip
from .tip_matching import StatedTerms
from .tip_vocabulary import ENTRY_BASIS_FIRST_SEEN_PRICE, HORIZON_BASIS_CHANNEL_DEFAULT


def stated_terms_of(tip: Tip) -> StatedTerms:
    """The terms the tip's match_key was built from (§5.2): a first-seen entry and a channel-default horizon never were."""
    first_seen_entry = tip.entry_basis == ENTRY_BASIS_FIRST_SEEN_PRICE
    return StatedTerms(
        symbol=tip.symbol,
        direction=tip.direction,
        entry_low=None if first_seen_entry else tip.entry_low,
        entry_high=None if first_seen_entry else tip.entry_high,
        target=tip.target_price,
        stop_loss=tip.stop_loss,
        horizon_sessions=None if tip.horizon_basis == HORIZON_BASIS_CHANNEL_DEFAULT else tip.horizon_sessions,
    )
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `python -m pytest tests/test_tip_ledger_admin.py -v`
Expected: 1 PASS.

- [ ] **Step 6: Commit**

```bash
git add app/tip_ledger_admin.py tests/test_tip_ledger_admin.py
git commit -m "Tip ledger admin: rebuild the stated terms a tip's match key was built from

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task A2: Channel merge — re-key, retire duplicates, move receipts (`merge_channels`)

**Files:**
- Modify: `app/tip_vocabulary.py` (directly after `REASON_NO_INTRADAY_BARS`)
- Modify: `app/tip_ledger_admin.py` (replace the whole file)
- Test: `tests/test_tip_ledger_admin.py`

**Interfaces:**
- Produces `REASON_CHANNEL_MERGE = "CHANNEL_MERGE"` in `app/tip_vocabulary.py`.
- Produces, in `app/tip_ledger_admin.py`:
  - `class LedgerAdminNotFound(LookupError)` with `.resource: str` and `.identifier: str`;
  - `class LedgerAdminConflict(ValueError)` with `.code: str`;
  - `CODE_MARKSY_READ_ONLY = "MRA_MARKSY_CHANNEL_READ_ONLY"` and `CODE_CHANNEL_ALREADY_MERGED = "MRA_CHANNEL_ALREADY_MERGED"`;
  - `@dataclass(frozen=True) class ChannelMergeResult`: `channel_id: int`, `into_channel_id: int` (the survivor root), `already_merged: bool`, `repointed_channel_ids: list[int]`, `rekeyed: int`, `duplicates: list[tuple[int, int]]` (as `(duplicate_tip_id, kept_tip_id)`), `receipts_moved: int` and `receipts_kept: int`;
  - `merge_channels(session: Session, channel_id: int, into_channel_id: int, *, now: datetime) -> ChannelMergeResult`. It commits. It raises `LedgerAdminNotFound`, or `LedgerAdminConflict` with `CODE_MARKSY_READ_ONLY` or `CODE_CHANNEL_ALREADY_MERGED`.
- Consumes, from `app/tip_ledger.py`: `_lock_match_key(session, key)` (a `pg_advisory_xact_lock` on PostgreSQL, a no-op on SQLite).

- [ ] **Step 1: Write the failing tests**

In `tests/test_tip_ledger_admin.py`:
- Add `TipReceipt` and `TipSourceExit` to the `from app.models import (...)` line.
- Change `from app.tip_ledger_admin import stated_terms_of` to:

```python
from app.tip_ledger_admin import (
    CODE_CHANNEL_ALREADY_MERGED,
    CODE_MARKSY_READ_ONLY,
    LedgerAdminConflict,
    merge_channels,
    stated_terms_of,
)
```

- Add `CHANNEL_MARKSY`, `KIND_EXIT`, `REASON_CHANNEL_MERGE`, `STATUS_ACTIVE` and `STATUS_MERGED_DUPLICATE` to the `from app.tip_vocabulary import (...)` block.

Then append:

```python
def _marksy(session):
    marksy = Channel(name="Marksy", type=CHANNEL_MARKSY, default_horizon_sessions=20, capture_enabled=False)
    session.add(marksy)
    session.flush()
    marksy.canonical_channel_id = marksy.id
    session.commit()
    return marksy


def test_a_merge_leaves_a_receipt_the_customer_already_holds_on_the_duplicate(session):
    kept = _intake(session, user="user-1", key="p-1")
    duplicate = _intake(session, user="user-1", key="l-1", package="com.upstox.lite", label="Upstox Lite",
                        now=NOW + timedelta(minutes=5))
    other = _intake(session, user="user-2", key="l-2", package="com.upstox.lite", label="Upstox Lite",
                    now=NOW + timedelta(minutes=6))
    exit_ = record_intake(
        session, _receipt(user="user-2", key="l-3", package="com.upstox.lite", label="Upstox Lite", text="Exit RENUKA"),
        Intake(kind=KIND_EXIT, exit_symbol="RENUKA"), now=NOW + timedelta(minutes=7),
    )
    lite, upstox = _channel(session, "com.upstox.lite"), _channel(session, "com.upstox.pro")

    result = merge_channels(session, lite.id, upstox.id, now=NOW + timedelta(hours=1))

    retired, earlier = session.get(Tip, duplicate.tip.id), session.get(Tip, kept.tip.id)
    assert (retired.status, retired.merged_into_tip_id, retired.reason) == (
        STATUS_MERGED_DUPLICATE, earlier.id, REASON_CHANNEL_MERGE,
    )
    assert (retired.match_key, earlier.status) == (earlier.match_key, STATUS_ACTIVE)
    links = {receipt.id: receipt.tip_id for receipt in session.scalars(select(TipReceipt))}
    assert links[duplicate.receipt.id] == retired.id  # user-1 already holds the earlier tip on APP_NOTIFICATION
    assert links[other.receipt.id] == links[exit_.receipt.id] == earlier.id
    assert session.scalar(select(TipSourceExit.tip_id)) == earlier.id
    assert (result.receipts_moved, result.receipts_kept, result.duplicates) == (2, 1, [(retired.id, earlier.id)])


def test_merging_a_canonical_repoints_the_channels_already_merged_into_it_and_a_repeat_changes_nothing(session):
    lite_tip = _intake(session, key="l-1", package="com.upstox.lite", label="Upstox Lite",
                       terms=_terms(target=Decimal("27"))).tip
    _intake(session, key="m-1", package="com.upstox.mini", label="Upstox Mini", terms=_terms(target=Decimal("28")))
    _intake(session, key="z-1", package="com.zerodha.kite", label="Kite", terms=_terms(target=Decimal("29")))
    upstox, lite, mini, kite = (
        _channel(session, package) for package in ("com.upstox.pro", "com.upstox.lite", "com.upstox.mini", "com.zerodha.kite")
    )
    merge_channels(session, mini.id, lite.id, now=NOW)

    result = merge_channels(session, lite.id, upstox.id, now=NOW)
    repeat = merge_channels(session, lite.id, upstox.id, now=NOW)

    assert {c.id: c.canonical_channel_id for c in session.scalars(select(Channel))} == {
        upstox.id: upstox.id, lite.id: upstox.id, mini.id: upstox.id, kite.id: kite.id,
    }
    assert (sorted(result.repointed_channel_ids), result.rekeyed) == (sorted([lite.id, mini.id]), 2)
    assert (repeat.already_merged, repeat.rekeyed, repeat.duplicates) == (True, 0, [])
    later = _intake(session, user="user-2", key="l-9", package="com.upstox.lite", label="Upstox Lite",
                    terms=_terms(target=Decimal("27")))
    assert (later.matched, later.tip.id) == (True, lite_tip.id)
    with pytest.raises(LedgerAdminConflict) as refused:
        merge_channels(session, mini.id, kite.id, now=NOW)
    assert refused.value.code == CODE_CHANNEL_ALREADY_MERGED


def test_the_marksy_channel_is_never_merged(session):
    marksy, upstox = _marksy(session), _channel(session, "com.upstox.pro")
    for channel_id, into_channel_id in ((marksy.id, upstox.id), (upstox.id, marksy.id)):
        with pytest.raises(LedgerAdminConflict) as refused:
            merge_channels(session, channel_id, into_channel_id, now=NOW)
        assert refused.value.code == CODE_MARKSY_READ_ONLY
```

- [ ] **Step 2: Run them to verify they fail**

Run: `python -m pytest tests/test_tip_ledger_admin.py -v`
Expected: collection ERROR, with `ImportError: cannot import name 'CODE_CHANNEL_ALREADY_MERGED' from 'app.tip_ledger_admin'`.

- [ ] **Step 3: Add the merge reason**

In `app/tip_vocabulary.py`, directly after `REASON_NO_INTRADAY_BARS = "NO_INTRADAY_BARS"`, add:

```python
# An ACTIVE tip that an admin channel merge made a duplicate of an earlier call (§5.2).
REASON_CHANNEL_MERGE = "CHANNEL_MERGE"
```

- [ ] **Step 4: Implement `merge_channels`**

Replace `app/tip_ledger_admin.py` with:

```python
"""Admin operations on the tip ledger (tip-ledger spec §5.2 merging, §9 admin): channel and caller merges,
renames and aliases. Marksy's own channel is read-only here, and a tip is re-keyed, never re-termed."""

from __future__ import annotations

from dataclasses import dataclass, field
from datetime import datetime

from sqlalchemy import select
from sqlalchemy.orm import Session

from .models import Channel, Tip, TipReceipt, TipSourceExit
from .tip_ledger import _lock_match_key
from .tip_matching import MATCH_KEY_VERSION, StatedTerms, match_key_v1
from .tip_vocabulary import (
    CHANNEL_MARKSY,
    ENTRY_BASIS_FIRST_SEEN_PRICE,
    HORIZON_BASIS_CHANNEL_DEFAULT,
    KIND_TIP,
    REASON_CHANNEL_MERGE,
    STATUS_ACTIVE,
    STATUS_MERGED_DUPLICATE,
)

CODE_MARKSY_READ_ONLY = "MRA_MARKSY_CHANNEL_READ_ONLY"
CODE_CHANNEL_ALREADY_MERGED = "MRA_CHANNEL_ALREADY_MERGED"


class LedgerAdminNotFound(LookupError):
    def __init__(self, resource: str, identifier: object) -> None:
        super().__init__(f"{resource} '{identifier}' was not found")
        self.resource, self.identifier = resource, str(identifier)


class LedgerAdminConflict(ValueError):
    def __init__(self, code: str, message: str) -> None:
        super().__init__(message)
        self.code = code


@dataclass(frozen=True)
class ChannelMergeResult:
    channel_id: int
    into_channel_id: int
    already_merged: bool
    repointed_channel_ids: list[int] = field(default_factory=list)
    rekeyed: int = 0
    duplicates: list[tuple[int, int]] = field(default_factory=list)
    receipts_moved: int = 0
    receipts_kept: int = 0


def stated_terms_of(tip: Tip) -> StatedTerms:
    """The terms the tip's match_key was built from (§5.2): a first-seen entry and a channel-default horizon never were."""
    first_seen_entry = tip.entry_basis == ENTRY_BASIS_FIRST_SEEN_PRICE
    return StatedTerms(
        symbol=tip.symbol,
        direction=tip.direction,
        entry_low=None if first_seen_entry else tip.entry_low,
        entry_high=None if first_seen_entry else tip.entry_high,
        target=tip.target_price,
        stop_loss=tip.stop_loss,
        horizon_sessions=None if tip.horizon_basis == HORIZON_BASIS_CHANNEL_DEFAULT else tip.horizon_sessions,
    )


def _canonical_channel_id(channel: Channel) -> int:
    return channel.canonical_channel_id or channel.id


def _locked_channels(session: Session, ids: tuple[int, ...]) -> dict[int, Channel]:
    # FOR UPDATE in id order; intake's alias lookup holds FOR SHARE, so in-flight intake finishes first.
    rows = session.scalars(
        select(Channel).where(Channel.id.in_(ids)).order_by(Channel.id).with_for_update()
        .execution_options(populate_existing=True)
    ).all()
    return {row.id: row for row in rows}


def merge_channels(session: Session, channel_id: int, into_channel_id: int, *, now: datetime) -> ChannelMergeResult:
    """§5.2 merging: `channel_id` and every channel already merged into it take `into_channel_id`'s canonical id.
    Their ACTIVE tips are re-keyed; where two now share a key, the later becomes MERGED_DUPLICATE of the earlier."""
    named = _locked_channels(session, (channel_id, into_channel_id))
    channel, target = named.get(channel_id), named.get(into_channel_id)
    if channel is None:
        raise LedgerAdminNotFound("Channel", channel_id)
    if target is None:
        raise LedgerAdminNotFound("Channel", into_channel_id)
    survivor_id = _canonical_channel_id(target)
    survivor = session.get(Channel, survivor_id)
    if CHANNEL_MARKSY in (channel.type, target.type, survivor.type):
        raise LedgerAdminConflict(CODE_MARKSY_READ_ONLY, "Marksy's own channel is never merged")
    if _canonical_channel_id(channel) == survivor_id or survivor_id == channel.id:
        return ChannelMergeResult(channel.id, survivor_id, already_merged=True)
    if _canonical_channel_id(channel) != channel.id:
        raise LedgerAdminConflict(
            CODE_CHANNEL_ALREADY_MERGED,
            f"channel {channel.id} is merged into {channel.canonical_channel_id}; merge that channel instead",
        )
    members = session.scalars(
        select(Channel).where(Channel.canonical_channel_id.in_((channel.id, survivor_id)))
        .order_by(Channel.id).with_for_update()
    ).all()
    absorbed = [member for member in members if member.canonical_channel_id == channel.id]
    for member in absorbed:
        member.canonical_channel_id = survivor_id
    session.flush()
    tips = session.scalars(
        select(Tip)
        .where(Tip.status == STATUS_ACTIVE, Tip.match_key.is_not(None), Tip.source_channel_id.in_([m.id for m in members]))
        .order_by(Tip.first_seen_at, Tip.id)
        .with_for_update()
    ).all()
    keys = {tip.id: match_key_v1(survivor_id, stated_terms_of(tip)) for tip in tips}
    for key in sorted(set(keys.values())):
        _lock_match_key(session, key)
    duplicates = _retire_duplicates(tips, keys, now=now)
    session.flush()  # a duplicate leaves the ACTIVE-key index before any tip takes its new key
    rekeyed = 0
    for tip in tips:
        if tip.match_key != keys[tip.id]:
            tip.match_key, tip.match_key_version = keys[tip.id], MATCH_KEY_VERSION
            rekeyed += 1
    session.flush()
    moved, kept = _move_receipts(session, duplicates)
    _move_source_exits(session, duplicates)
    session.commit()
    return ChannelMergeResult(
        channel.id, survivor_id, already_merged=False, repointed_channel_ids=[member.id for member in absorbed],
        rekeyed=rekeyed, duplicates=[(loser.id, winner.id) for loser, winner in duplicates],
        receipts_moved=moved, receipts_kept=kept,
    )


def _retire_duplicates(tips: list[Tip], keys: dict[int, str], *, now: datetime) -> list[tuple[Tip, Tip]]:
    """`tips` come oldest first, so the first tip per key is the earlier call and stays ACTIVE."""
    winners: dict[str, Tip] = {}
    duplicates: list[tuple[Tip, Tip]] = []
    for tip in tips:
        winner = winners.setdefault(keys[tip.id], tip)
        if winner is not tip:
            tip.status, tip.merged_into_tip_id = STATUS_MERGED_DUPLICATE, winner.id
            tip.reason, tip.closed_at = REASON_CHANNEL_MERGE, now
            duplicates.append((tip, winner))
    return duplicates


def _move_receipts(session: Session, duplicates: list[tuple[Tip, Tip]]) -> tuple[int, int]:
    moved = kept = 0
    for loser, winner in duplicates:
        for receipt in session.scalars(select(TipReceipt).where(TipReceipt.tip_id == loser.id).order_by(TipReceipt.id)).all():
            held = receipt.kind == KIND_TIP and session.scalar(
                select(TipReceipt.id).where(
                    TipReceipt.tip_id == winner.id, TipReceipt.user_id == receipt.user_id,
                    TipReceipt.medium == receipt.medium, TipReceipt.kind == KIND_TIP,
                )
            ) is not None
            if held:
                kept += 1  # uq_tip_receipt_tip_user_medium: the customer already holds the earlier tip here
                continue
            receipt.tip_id = winner.id
            session.flush()
            moved += 1
    return moved, kept


def _move_source_exits(session: Session, duplicates: list[tuple[Tip, Tip]]) -> None:
    for loser, winner in duplicates:
        pending = session.scalar(select(TipSourceExit).where(TipSourceExit.tip_id == loser.id))
        if pending is not None and session.scalar(select(TipSourceExit.id).where(TipSourceExit.tip_id == winner.id)) is None:
            pending.tip_id = winner.id  # its EXIT receipt moved with it; the earlier tip is the call now
            session.flush()
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `python -m pytest tests/test_tip_ledger_admin.py tests/test_tip_ledger_guard.py -v`
Expected: all PASS. The guard file passes unchanged, which confirms that a merge writes no `TERM_FIELDS` column.

- [ ] **Step 6: Commit**

```bash
git add app/tip_vocabulary.py app/tip_ledger_admin.py tests/test_tip_ledger_admin.py
git commit -m "Tip ledger admin: channel merge re-keys ACTIVE tips and retires the later duplicate (spec 5.2)

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task A3: Merge concurrency — intake's alias lookup locks, the tracker re-reads

**Files:**
- Modify: `app/tip_ledger.py` (add `_alias_lookup` above `resolve_channel`; use it in the lookup loop)
- Modify: `app/tip_tracking_job.py` (`track_tips` loop, lines 311–318)
- Test: `tests/test_tip_ledger.py`, `tests/test_tip_tracking_job.py`

**Interfaces:**
- Produces `_alias_lookup(medium: str, alias: str) -> Select`: the scoped alias lookup with `FOR SHARE OF channels` (ignored on SQLite). The signature of `resolve_channel` is unchanged.
- `track_tips(session, *, now, minute_bars, provisional=False) -> TrackingRun`: the signature is unchanged. Before tracking each tip, it re-reads the row under `FOR UPDATE` and skips a tip that is no longer ACTIVE.

- [ ] **Step 1: Write the failing tests**

Append to `tests/test_tip_ledger.py`, and add `from sqlalchemy.dialects import postgresql` to its imports (`tip_ledger` and `MEDIUM_WHATSAPP` are already imported):

```python
def test_the_alias_lookup_share_locks_the_channel_so_a_merge_waits_for_intake():
    sql = str(tip_ledger._alias_lookup(MEDIUM_WHATSAPP, "stocktips").compile(dialect=postgresql.dialect()))
    assert sql.rstrip().endswith("FOR SHARE OF channels")
```

Append to `tests/test_tip_tracking_job.py`:
- add `STATUS_MERGED_DUPLICATE` to its `from app.tip_vocabulary import (...)` block;
- add `from app import tip_tracking_job` below its imports (`select` and `update` are already imported).

```python
def test_a_tip_merged_after_the_tracker_loaded_it_is_not_tracked(session, monkeypatch):
    session.expire_on_commit = False  # production's SessionLocal: a loaded tip stays stale across commits
    tip = _tip(session, seen=ist(MON, 16, 0))
    _bar(session, TUE, 23.7, 26.5, 23.5, 26.1)
    session_dates = tip_tracking_job._session_dates

    def merged_meanwhile(db, since):
        db.execute(
            update(Tip).where(Tip.id == tip.id).values(status=STATUS_MERGED_DUPLICATE)
            .execution_options(synchronize_session=False)
        )
        db.commit()
        return session_dates(db, since)

    monkeypatch.setattr(tip_tracking_job, "_session_dates", merged_meanwhile)
    track_tips(session, now=RUN_AT, minute_bars=None)

    assert session.scalar(select(Tip.status).where(Tip.id == tip.id)) == STATUS_MERGED_DUPLICATE
    assert _rows(session, tip) == []
```

- [ ] **Step 2: Run them to verify they fail**

Run: `python -m pytest tests/test_tip_ledger.py tests/test_tip_tracking_job.py -v -k "share_locks or merged_after"`
Expected: 2 FAIL.
- The ledger test: `AttributeError: module 'app.tip_ledger' has no attribute '_alias_lookup'`.
- The tracker test: `assert 'TARGET_HIT' == 'MERGED_DUPLICATE'`. The stale copy closed the retired tip.

- [ ] **Step 3: Share-lock the alias lookup**

In `app/tip_ledger.py`, directly above `def resolve_channel`, add:

```python
def _alias_lookup(medium: str, alias: str):
    # FOR SHARE: a channel merge (Phase 3a) waits for in-flight intake, and later intake reads its canonical id.
    return (
        select(Channel)
        .join(ChannelAlias, ChannelAlias.channel_id == Channel.id)
        .where(ChannelAlias.scope == medium, ChannelAlias.alias == alias)
        .with_for_update(read=True, of=Channel)
    )
```

In `resolve_channel`, replace the lookup statement inside `for alias, _kind in keys:` (Phase 4a's `session.scalar(select(Channel).join(...).where(ChannelAlias.scope == medium, ChannelAlias.alias == alias))`) with:

```python
        channel = session.scalar(_alias_lookup(medium, alias))
```

The rest of the loop (the Marksy check and `return channel`) is unchanged.

- [ ] **Step 4: Re-read each tip under a row lock in the tracker**

In `app/tip_tracking_job.py`, `track_tips`, replace:

```python
        try:
            tracking = _TipTracking(session, tip, market, exits.get(tip.id), minute_bars, now, run)
```

with:

```python
        try:
            # Phase 3a: a channel merge may have retired this tip since the list was read; the lock makes a merge wait.
            session.refresh(tip, with_for_update=True)
            if tip.status != STATUS_ACTIVE:
                session.commit()
                continue
            tracking = _TipTracking(session, tip, market, exits.get(tip.id), minute_bars, now, run)
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `python -m pytest tests/test_tip_ledger.py tests/test_tip_tracking_job.py tests/test_tip_tracker.py tests/test_run_tip_tracker.py tests/test_marksy_prediction_tips.py tests/test_epic853_confirmation.py -v`
Expected: all PASS, including every existing caller of `track_tips` (2a's, and 2b's Marksy and confirmation tests). `tipsExamined` still counts each loaded tip.

- [ ] **Step 6: Commit**

```bash
git add app/tip_ledger.py app/tip_tracking_job.py tests/test_tip_ledger.py tests/test_tip_tracking_job.py
git commit -m "Tip ledger: intake share-locks its channel and the tracker skips tips a merge retired

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task A4: Caller merge, renames, default horizon, capture, and scoped aliases

**Files:**
- Modify: `app/tip_ledger_admin.py` (replace the import block and constants; append the functions)
- Test: `tests/test_tip_ledger_admin.py`

**Interfaces:**
- Produces, in `app/tip_ledger_admin.py`:
  - `class LedgerAdminInvalid(ValueError)` with `.field: str`;
  - `CODE_CALLER_ALREADY_MERGED`, `CODE_CALLER_CHANNEL_MISMATCH`, `CODE_ALIAS_EXISTS` and `CODE_CALLER_EXISTS`, whose values are those names prefixed with `MRA_`;
  - `update_channel(session, channel_id: int, *, name: str | None = None, default_horizon_sessions: int | None = None, capture_enabled: bool | None = None) -> Channel`;
  - `add_alias(session, channel_id: int, *, alias: str, scope: str, kind: str) -> ChannelAlias`;
  - `update_alias(session, channel_id: int, alias_id: int, *, alias: str | None = None, scope: str | None = None, kind: str | None = None) -> ChannelAlias`;
  - `delete_alias(session, channel_id: int, alias_id: int) -> None`;
  - `rename_caller(session, caller_id: int, name: str) -> Caller`;
  - `@dataclass(frozen=True) class CallerMergeResult`: `caller_id: int`, `into_caller_id: int`, `already_merged: bool` and `repointed_caller_ids: list[int]`;
  - `merge_callers(session, caller_id: int, into_caller_id: int) -> CallerMergeResult`.

  Each function commits on success. Each raises `LedgerAdminNotFound`, `LedgerAdminConflict` or `LedgerAdminInvalid`.
- Consumes: `normalize_alias` (`app/tip_ledger.py`), `clean_tip_text` and `is_mask_only` (`app/tip_text_cleaning.py`, 4a), and `MARKSY_CHANNEL_NAME` (`app/tip_vocabulary.py`, 2b).

- [ ] **Step 1: Write the failing tests**

In `tests/test_tip_ledger_admin.py`:
- Add `Caller` to the `from app.models import (...)` line.
- Add `resolve_channel` to the `from app.tip_ledger import (...)` line.
- Add `from app.tip_text_cleaning import channel_label_for`.
- Extend the `from app.tip_ledger_admin import (...)` block with `CODE_ALIAS_EXISTS`, `CODE_CALLER_CHANNEL_MISMATCH`, `LedgerAdminInvalid`, `add_alias`, `merge_callers` and `update_channel`.
- Add `ALIAS_LABEL`, `MEDIUM_SMS`, `MEDIUM_TELEGRAM` and `MEDIUM_WHATSAPP` to the vocabulary import block.

Then append:

```python
def test_the_marksy_channel_is_never_renamed_or_given_an_alias(session):
    marksy = _marksy(session)
    attempts = (
        lambda: update_channel(session, marksy.id, name="Marksy Picks"),
        lambda: add_alias(session, marksy.id, alias="marksy", scope=MEDIUM_TELEGRAM, kind=ALIAS_LABEL),
    )
    for attempt in attempts:
        with pytest.raises(LedgerAdminConflict) as refused:
            attempt()
        assert refused.value.code == CODE_MARKSY_READ_ONLY
    with pytest.raises(LedgerAdminInvalid):
        update_channel(session, _channel(session, "com.upstox.pro").id, name=" marksy ")


def test_an_admin_alias_is_stored_as_intake_compares_it_and_is_unique_within_its_medium(session):
    upstox = _channel(session, "com.upstox.pro")

    row = add_alias(session, upstox.id, alias="StockTips  98765 43210", scope=MEDIUM_WHATSAPP, kind=ALIAS_LABEL)
    label = channel_label_for(MEDIUM_WHATSAPP, "StockTips 98765 43210", username=None)

    assert (row.alias, row.scope) == ("stocktips [phone]", MEDIUM_WHATSAPP)
    assert resolve_channel(session, medium=MEDIUM_WHATSAPP, app_package=None, channel_label=label).id == upstox.id
    add_alias(session, upstox.id, alias="stocktips [phone]", scope=MEDIUM_TELEGRAM, kind=ALIAS_LABEL)  # another medium
    with pytest.raises(LedgerAdminConflict) as taken:
        add_alias(session, upstox.id, alias="STOCKTIPS 98765 43210", scope=MEDIUM_WHATSAPP, kind=ALIAS_LABEL)
    assert taken.value.code == CODE_ALIAS_EXISTS
    for alias, scope, kind in (("+91 98765 43210", MEDIUM_WHATSAPP, ALIAS_LABEL), ("com.upstox.sms", MEDIUM_SMS, ALIAS_PACKAGE)):
        with pytest.raises(LedgerAdminInvalid):
            add_alias(session, upstox.id, alias=alias, scope=scope, kind=kind)


def test_a_caller_merge_repoints_its_group_within_one_canonical_channel_and_leaves_tips_alone(session):
    tip = _intake(session, key="p-1", caller="Rahul S").tip
    _intake(session, key="p-2", caller="Rahul Sharma", terms=_terms(target=Decimal("27")))
    _intake(session, key="z-1", package="com.zerodha.kite", label="Kite", caller="Priya", terms=_terms(target=Decimal("28")))
    short, full, priya = (session.scalar(select(Caller).where(Caller.name == n)) for n in ("Rahul S", "Rahul Sharma", "Priya"))

    result = merge_callers(session, short.id, full.id)

    assert (result.into_caller_id, result.repointed_caller_ids) == (full.id, [short.id])
    assert (session.get(Caller, short.id).canonical_caller_id, session.get(Tip, tip.id).caller_id) == (full.id, short.id)
    assert merge_callers(session, short.id, full.id).already_merged
    with pytest.raises(LedgerAdminConflict) as across:
        merge_callers(session, priya.id, full.id)
    assert across.value.code == CODE_CALLER_CHANNEL_MISMATCH
```

- [ ] **Step 2: Run them to verify they fail**

Run: `python -m pytest tests/test_tip_ledger_admin.py -v`
Expected: collection ERROR, with `ImportError: cannot import name 'CODE_ALIAS_EXISTS' from 'app.tip_ledger_admin'`.

- [ ] **Step 3: Replace the import block and constants**

In `app/tip_ledger_admin.py`, replace everything from `from dataclasses import dataclass, field` down to `CODE_CHANNEL_ALREADY_MERGED = "MRA_CHANNEL_ALREADY_MERGED"` with:

```python
import re
from dataclasses import dataclass, field
from datetime import datetime

from sqlalchemy import func, select
from sqlalchemy.orm import Session

from .models import Caller, Channel, ChannelAlias, Tip, TipReceipt, TipSourceExit
from .tip_ledger import _lock_match_key, normalize_alias
from .tip_matching import MATCH_KEY_VERSION, StatedTerms, match_key_v1
from .tip_text_cleaning import clean_tip_text, is_mask_only
from .tip_vocabulary import (
    ALIAS_PACKAGE,
    CHANNEL_MARKSY,
    ENTRY_BASIS_FIRST_SEEN_PRICE,
    HORIZON_BASIS_CHANNEL_DEFAULT,
    KIND_TIP,
    MARKSY_CHANNEL_NAME,
    MEDIUM_APP_NOTIFICATION,
    REASON_CHANNEL_MERGE,
    STATUS_ACTIVE,
    STATUS_MERGED_DUPLICATE,
)

CODE_MARKSY_READ_ONLY = "MRA_MARKSY_CHANNEL_READ_ONLY"
CODE_CHANNEL_ALREADY_MERGED = "MRA_CHANNEL_ALREADY_MERGED"
CODE_CALLER_ALREADY_MERGED = "MRA_CALLER_ALREADY_MERGED"
CODE_CALLER_CHANNEL_MISMATCH = "MRA_CALLER_CHANNEL_MISMATCH"
CODE_ALIAS_EXISTS = "MRA_ALIAS_EXISTS"
CODE_CALLER_EXISTS = "MRA_CALLER_EXISTS"
_NAME_MAX = 128
```

The line `from __future__ import annotations` above it stays.

- [ ] **Step 4: Append the channel, alias and caller operations**

Append to `app/tip_ledger_admin.py`:

```python
class LedgerAdminInvalid(ValueError):
    def __init__(self, message: str, field_name: str) -> None:
        super().__init__(message)
        self.field = field_name


def _clean_name(value: str, field_name: str) -> str:
    clean = re.sub(r"\s+", " ", value.strip())[:_NAME_MAX]
    if not clean:
        raise LedgerAdminInvalid(f"{field_name} is required", field_name)
    return clean


def _editable_channel(session: Session, channel_id: int) -> Channel:
    channel = session.get(Channel, channel_id)
    if channel is None:
        raise LedgerAdminNotFound("Channel", channel_id)
    if channel.type == CHANNEL_MARKSY:
        raise LedgerAdminConflict(CODE_MARKSY_READ_ONLY, "Marksy's own channel is read-only")
    return channel


def update_channel(session: Session, channel_id: int, *, name: str | None = None,
                   default_horizon_sessions: int | None = None, capture_enabled: bool | None = None) -> Channel:
    """A new name or default horizon applies from now on; stored tips keep their `source` and horizon (§2.5)."""
    channel = _editable_channel(session, channel_id)
    if name is not None:
        clean = _clean_name(name, "name")
        if clean.casefold() == MARKSY_CHANNEL_NAME.casefold():
            raise LedgerAdminInvalid("only Marksy's own channel is named Marksy", "name")
        channel.name = clean
    if default_horizon_sessions is not None:
        channel.default_horizon_sessions = default_horizon_sessions
    if capture_enabled is not None:
        channel.capture_enabled = capture_enabled
    session.commit()
    return channel


def _alias_value(alias: str, *, scope: str, kind: str) -> str:
    """The form intake compares: a label cleaned, then normalized, as `channel_label_for` and `resolve_channel` do."""
    if kind == ALIAS_PACKAGE and scope != MEDIUM_APP_NOTIFICATION:
        raise LedgerAdminInvalid("a package alias belongs to APP_NOTIFICATION", "kind")
    raw = alias if kind == ALIAS_PACKAGE else clean_tip_text(alias, username=None)
    value = normalize_alias(raw) if raw.strip() else ""
    if not value:
        raise LedgerAdminInvalid("alias is required", "alias")
    if kind != ALIAS_PACKAGE and is_mask_only(value):
        raise LedgerAdminInvalid(f"'{value}' is only masked personal data, not a channel", "alias")
    return value


def _refuse_taken(session: Session, value: str, scope: str, *, alias_id: int | None) -> None:
    statement = select(ChannelAlias).where(ChannelAlias.scope == scope, ChannelAlias.alias == value)
    if alias_id is not None:
        statement = statement.where(ChannelAlias.id != alias_id)
    owner = session.scalar(statement)
    if owner is not None:
        raise LedgerAdminConflict(CODE_ALIAS_EXISTS, f"'{value}' already names channel {owner.channel_id} on {scope}")


def add_alias(session: Session, channel_id: int, *, alias: str, scope: str, kind: str) -> ChannelAlias:
    channel = _editable_channel(session, channel_id)
    value = _alias_value(alias, scope=scope, kind=kind)
    _refuse_taken(session, value, scope, alias_id=None)
    row = ChannelAlias(channel_id=channel.id, alias=value, kind=kind, scope=scope)
    session.add(row)
    session.commit()
    return row


def _channel_alias(session: Session, channel_id: int, alias_id: int) -> ChannelAlias:
    _editable_channel(session, channel_id)
    row = session.get(ChannelAlias, alias_id)
    if row is None or row.channel_id != channel_id:
        raise LedgerAdminNotFound("Alias", alias_id)
    return row


def update_alias(session: Session, channel_id: int, alias_id: int, *, alias: str | None = None,
                 scope: str | None = None, kind: str | None = None) -> ChannelAlias:
    row = _channel_alias(session, channel_id, alias_id)
    scope, kind = scope or row.scope, kind or row.kind
    value = _alias_value(alias if alias is not None else row.alias, scope=scope, kind=kind)
    _refuse_taken(session, value, scope, alias_id=row.id)
    row.alias, row.scope, row.kind = value, scope, kind
    session.commit()
    return row


def delete_alias(session: Session, channel_id: int, alias_id: int) -> None:
    """Resolved tips keep their channel; a later receipt naming this alias resolves anew (§5.2 step 2)."""
    session.delete(_channel_alias(session, channel_id, alias_id))
    session.commit()


@dataclass(frozen=True)
class CallerMergeResult:
    caller_id: int
    into_caller_id: int
    already_merged: bool
    repointed_caller_ids: list[int] = field(default_factory=list)


def _caller_and_channel(session: Session, caller_id: int) -> tuple[Caller, Channel]:
    caller = session.get(Caller, caller_id)
    if caller is None:
        raise LedgerAdminNotFound("Caller", caller_id)
    channel = session.get(Channel, caller.channel_id)
    if channel.type == CHANNEL_MARKSY:
        raise LedgerAdminConflict(CODE_MARKSY_READ_ONLY, "Marksy's engines are read-only")
    return caller, channel


def rename_caller(session: Session, caller_id: int, name: str) -> Caller:
    """`resolve_caller` matches names case-insensitively, so a rename may not collide even by case."""
    caller = _caller_and_channel(session, caller_id)[0]
    clean = _clean_name(name, "name")
    clash = session.scalar(
        select(Caller.id).where(
            Caller.channel_id == caller.channel_id, func.lower(Caller.name) == clean.lower(), Caller.id != caller.id
        )
    )
    if clash is not None:
        raise LedgerAdminConflict(CODE_CALLER_EXISTS, f"channel {caller.channel_id} already has a caller named '{clean}'")
    caller.name = clean
    session.commit()
    return caller


def merge_callers(session: Session, caller_id: int, into_caller_id: int) -> CallerMergeResult:
    """§5.2: `caller_id` and its group take `into_caller_id`'s canonical id. Tips keep `caller_id` (fill-once, §4)
    and nothing is re-keyed, because the caller never enters match_key."""
    caller, channel = _caller_and_channel(session, caller_id)
    target, target_channel = _caller_and_channel(session, into_caller_id)
    if _canonical_channel_id(channel) != _canonical_channel_id(target_channel):
        raise LedgerAdminConflict(CODE_CALLER_CHANNEL_MISMATCH, "callers merge only within one canonical channel")
    survivor_id = target.canonical_caller_id or target.id
    current = caller.canonical_caller_id or caller.id
    if current == survivor_id or survivor_id == caller.id:
        return CallerMergeResult(caller.id, survivor_id, already_merged=True)
    if current != caller.id:
        raise LedgerAdminConflict(
            CODE_CALLER_ALREADY_MERGED, f"caller {caller.id} is merged into {current}; merge that caller instead"
        )
    absorbed = list(session.scalars(select(Caller).where(Caller.canonical_caller_id == caller.id).order_by(Caller.id)))
    for member in absorbed:
        member.canonical_caller_id = survivor_id
    session.commit()
    return CallerMergeResult(caller.id, survivor_id, already_merged=False,
                             repointed_caller_ids=[member.id for member in absorbed])
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `python -m pytest tests/test_tip_ledger_admin.py tests/test_tip_ledger.py tests/test_tip_text_cleaning.py -v`
Expected: all PASS.

- [ ] **Step 6: Commit**

```bash
git add app/tip_ledger_admin.py tests/test_tip_ledger_admin.py
git commit -m "Tip ledger admin: caller merge, renames, default horizon, capture and scoped aliases

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task A5: Re-parse an UNPARSED receipt without altering or duplicating a tip (`reparse_receipt`)

**Files:**
- Modify: `app/tip_vocabulary.py` (directly after `REASON_CHANNEL_MERGE`)
- Modify: `app/tip_ledger.py` (vocabulary imports; append the re-parse code)
- Modify: `api/services/tips.py` (extract `message_intake` from `ingest_message`)
- Test: `tests/test_tip_reparse.py`

**Interfaces:**
- Produces, in `app/tip_vocabulary.py`: `REPARSE_CREATED`, `REPARSE_ATTACHED`, `REPARSE_ALREADY_HELD`, `REPARSE_ORPHAN_EXIT` and `REPARSE_STILL_UNPARSED`, whose values are `"CREATED"`, `"ATTACHED"`, `"ALREADY_HELD"`, `"ORPHAN_EXIT"` and `"STILL_UNPARSED"`.
- Produces, in `app/tip_ledger.py`:
  - `class NotUnparsedError(ValueError)`;
  - `@dataclass(frozen=True) class ReparseResult`: `receipt: TipReceipt`, `tip: Tip | None`, `kind: str` and `outcome: str`;
  - `reparse_receipt(session: Session, receipt: TipReceipt, intake: Intake, *, parser_version: str, now: datetime) -> ReparseResult`. It commits, except for ALREADY_HELD, which rolls back. It raises `NotUnparsedError`, plus whatever `resolve_channel` raises (`ChannelLabelRequiredError`, including 4a's `MaskedChannelLabelError`, and `ReservedChannelError`).
- Produces, in `api/services/tips.py`: `message_intake(parsed: ParsedMessage) -> Intake`. `ingest_message` now uses it, and its behaviour is unchanged.

- [ ] **Step 1: Write the failing tests**

Create `tests/test_tip_reparse.py`:

```python
"""Invariant 7 (tip-ledger spec §2): re-parsing an UNPARSED receipt never alters or duplicates an existing tip."""

from __future__ import annotations

import uuid
from datetime import datetime, timedelta, timezone
from decimal import Decimal

import pytest
from sqlalchemy import create_engine, select
from sqlalchemy.orm import sessionmaker

from api.services.tips import message_intake
from app.db import Base
from app.models import Channel, ChannelAlias, Stock, Tip, TipReceipt, TipSourceExit
from app.tip_ledger import Intake, NotUnparsedError, ReceiptInput, TipExtras, record_intake, reparse_receipt
from app.tip_matching import stated_terms
from app.trade_call_parser import PARSER_VERSION, parse_message
from app.tip_vocabulary import (
    ALIAS_PACKAGE,
    CHANNEL_BROKER_APP,
    KIND_EXIT,
    KIND_TIP,
    KIND_UNPARSED,
    MEDIUM_APP_NOTIFICATION,
    OUTCOME_SUCCESS,
    REPARSE_ALREADY_HELD,
    REPARSE_ATTACHED,
    REPARSE_CREATED,
    REPARSE_ORPHAN_EXIT,
    STATUS_ACTIVE,
    STATUS_TARGET_HIT,
)

NOW = datetime(2026, 9, 28, 5, 0, tzinfo=timezone.utc)  # Monday, 10:30 IST
CALL = "BUY RENUKA CMP 23.62 SL 22.25 TGT 26"


@pytest.fixture
def session():
    engine = create_engine("sqlite:///:memory:")
    Base.metadata.create_all(engine)
    db = sessionmaker(bind=engine)()
    db.add(Stock(symbol="RENUKA", exchange="NSE", is_active=True))
    upstox = Channel(name="Upstox", type=CHANNEL_BROKER_APP, default_horizon_sessions=20, capture_enabled=True)
    db.add(upstox)
    db.flush()
    upstox.canonical_channel_id = upstox.id
    db.add(ChannelAlias(channel_id=upstox.id, alias="com.upstox.pro", kind=ALIAS_PACKAGE))
    db.commit()
    try:
        yield db
    finally:
        db.close()


def _aware(value):
    return value if value.tzinfo is not None else value.replace(tzinfo=timezone.utc)


def _live_tip(session, *, user="user-1", key="e-1", now=NOW):
    receipt = ReceiptInput(
        user_id=user, device_event_key=key, medium=MEDIUM_APP_NOTIFICATION, app_package="com.upstox.pro",
        channel_label="Upstox", text=CALL, device_posted_at=None, parser_version=PARSER_VERSION,
    )
    terms = stated_terms(symbol="RENUKA", direction="BUY", entry_low=Decimal("23.62"), entry_high=Decimal("23.62"),
                         target=Decimal("26"), stop_loss=Decimal("22.25"), horizon_sessions=None)
    return record_intake(session, receipt, Intake(kind=KIND_TIP, terms=terms, extras=TipExtras()), now=now)


def _unparsed(session, *, user="user-2", text=CALL, at=NOW):
    # What an older parser left behind: the cleaned text, no tip.
    receipt = TipReceipt(
        public_id=str(uuid.uuid4()), tip_id=None, user_id=user, device_event_key=f"u-{uuid.uuid4()}", kind=KIND_UNPARSED,
        medium=MEDIUM_APP_NOTIFICATION, app_package="com.upstox.pro", channel_label="Upstox", text=text,
        device_posted_at=None, recorded_at=at, parser_version="TCP-000",
    )
    session.add(receipt)
    session.commit()
    return receipt


def _reparse(session, receipt, *, now=NOW + timedelta(days=5)):
    intake = message_intake(parse_message(receipt.text))
    return reparse_receipt(session, receipt, intake, parser_version=PARSER_VERSION, now=now)


def test_a_reparsed_call_becomes_a_tip_dated_from_the_receipts_own_arrival(session):
    receipt = _unparsed(session, at=NOW)

    result = _reparse(session, receipt)

    tip = session.get(Tip, result.tip.id)
    assert (result.outcome, result.kind, receipt.kind, receipt.tip_id) == (REPARSE_CREATED, KIND_TIP, KIND_TIP, tip.id)
    assert _aware(tip.first_seen_at) == _aware(tip.received_at) == NOW
    assert (tip.status, receipt.parser_version) == (STATUS_ACTIVE, PARSER_VERSION)


def test_a_reparsed_call_joins_the_tip_open_when_it_arrived_even_after_that_tip_closed(session):
    first = _live_tip(session).tip
    first.status, first.outcome, first.closed_at = STATUS_TARGET_HIT, OUTCOME_SUCCESS, NOW + timedelta(days=2)
    session.commit()
    receipt = _unparsed(session, text=f"{CALL}\nAnalyst: Rahul Sharma", at=NOW + timedelta(days=1))

    result = _reparse(session, receipt)

    closed = session.get(Tip, first.id)
    assert (result.outcome, receipt.tip_id, session.query(Tip).count()) == (REPARSE_ATTACHED, first.id, 1)
    assert (closed.status, _aware(closed.closed_at), closed.caller_id) == (STATUS_TARGET_HIT, NOW + timedelta(days=2), None)


def test_a_reparsed_copy_the_customer_already_holds_stays_unparsed(session):
    held = _live_tip(session, user="user-2").tip
    receipt = _unparsed(session, user="user-2", at=NOW + timedelta(minutes=30))

    result = _reparse(session, receipt)

    stored = session.get(TipReceipt, receipt.id)
    assert (result.outcome, result.tip.id) == (REPARSE_ALREADY_HELD, held.id)
    assert (stored.kind, stored.tip_id) == (KIND_UNPARSED, None)


def test_a_reparsed_exit_stays_unlinked_and_closes_no_tip(session):
    active = _live_tip(session).tip
    receipt = _unparsed(session, text="Exit RENUKA at 25", at=NOW + timedelta(hours=1))

    result = _reparse(session, receipt)

    assert (result.outcome, receipt.kind, receipt.tip_id) == (REPARSE_ORPHAN_EXIT, KIND_EXIT, None)
    assert session.scalar(select(TipSourceExit.id)) is None
    assert session.get(Tip, active.id).status == STATUS_ACTIVE


def test_only_an_unparsed_receipt_is_reparsed(session):
    receipt = _live_tip(session).receipt
    with pytest.raises(NotUnparsedError):
        _reparse(session, receipt)
```

- [ ] **Step 2: Run them to verify they fail**

Run: `python -m pytest tests/test_tip_reparse.py -v`
Expected: collection ERROR, with `ImportError: cannot import name 'message_intake' from 'api.services.tips'`.

- [ ] **Step 3: Add the re-parse outcomes**

In `app/tip_vocabulary.py`, directly after `REASON_CHANNEL_MERGE = "CHANNEL_MERGE"`, add:

```python
REPARSE_CREATED = "CREATED"
REPARSE_ATTACHED = "ATTACHED"
REPARSE_ALREADY_HELD = "ALREADY_HELD"
REPARSE_ORPHAN_EXIT = "ORPHAN_EXIT"
REPARSE_STILL_UNPARSED = "STILL_UNPARSED"
```

- [ ] **Step 4: Extract `message_intake`**

In `api/services/tips.py`:
- Change `from app.trade_call_parser import PARSER_VERSION, parse_message` to `from app.trade_call_parser import PARSER_VERSION, ParsedMessage, parse_message`.
- Directly above `def ingest_message`, add:

```python
def message_intake(parsed: ParsedMessage) -> Intake:
    """One parsed message as ledger intake; admin re-parse (Phase 3a) maps a parse exactly as live intake does."""
    terms = None
    if parsed.kind in (KIND_TIP, KIND_REVISION):
        terms = stated_terms(
            symbol=parsed.symbol,
            direction=parsed.side,
            entry_low=parsed.entry_low,
            entry_high=parsed.entry_high,
            target=parsed.target,
            stop_loss=parsed.stop_loss,
            horizon_sessions=parsed.horizon_sessions,
        )
    is_exit = parsed.kind == KIND_EXIT
    return Intake(
        kind=parsed.kind,
        terms=terms,
        exit_symbol=parsed.symbol if is_exit else None,
        exit_direction=parsed.side if is_exit else None,
        exit_price=parsed.exit_price,
        caller_name=parsed.caller,
    )
```

- In `ingest_message`:
  - delete the lines from `terms = None` through `is_exit = parsed.kind == KIND_EXIT`;
  - replace the whole `intake = Intake(...)` statement with `intake = message_intake(parsed)`.

  `parsed = parse_message(text)`, the `receipt = ReceiptInput(...)` statement and everything after it stay as they are.

- [ ] **Step 5: Add `reparse_receipt`**

In `app/tip_ledger.py`, add `REPARSE_ALREADY_HELD`, `REPARSE_ATTACHED`, `REPARSE_CREATED`, `REPARSE_ORPHAN_EXIT`, `REPARSE_STILL_UNPARSED` and `STATUS_MERGED_DUPLICATE` to the `from .tip_vocabulary import (...)` block, keeping it alphabetical. Then append:

```python
class NotUnparsedError(ValueError):
    """Invariant 7: re-parsing applies only to an UNPARSED receipt."""


@dataclass(frozen=True)
class ReparseResult:
    receipt: TipReceipt
    tip: Tip | None
    kind: str
    outcome: str


def reparse_receipt(session: Session, receipt: TipReceipt, intake: Intake, *, parser_version: str,
                    now: datetime) -> ReparseResult:
    """Invariant 7: an UNPARSED receipt read again. A call joins the tip it already is, or becomes a tip dated from
    the receipt's own server time; no existing tip changes, so an exit stays unlinked."""
    if receipt.kind != KIND_UNPARSED:
        raise NotUnparsedError(f"receipt {receipt.public_id} is {receipt.kind}, not UNPARSED")
    receipt.parser_version = parser_version
    if intake.kind == KIND_EXIT:
        receipt.kind = KIND_EXIT
        session.commit()
        return ReparseResult(receipt, None, KIND_EXIT, REPARSE_ORPHAN_EXIT)
    if intake.kind == KIND_UNPARSED or intake.terms is None:
        session.commit()
        return ReparseResult(receipt, None, KIND_UNPARSED, REPARSE_STILL_UNPARSED)
    seen = _aware(receipt.recorded_at)
    channel = resolve_channel(
        session, medium=receipt.medium, app_package=receipt.app_package, channel_label=receipt.channel_label
    )
    terms, kind, original = intake.terms, KIND_TIP, None
    if intake.kind == KIND_REVISION:
        earlier = [tip for tip in _active_tips(session, channel, terms.symbol, terms.direction)
                   if _aware(tip.first_seen_at) < seen]
        if earlier:
            original, kind = earlier[0], KIND_REVISION
            if not terms.direction:
                terms = replace(terms, direction=original.direction)
    key = match_key_v1(channel.canonical_channel_id, terms)
    _lock_match_key(session, key)
    horizon = terms.horizon_sessions if terms.horizon_sessions is not None else channel.default_horizon_sessions
    tip = _reparse_match(session, key, seen=seen, now=now, horizon=horizon)
    if tip is not None and _held(session, tip, receipt):
        session.rollback()
        return ReparseResult(receipt, tip, KIND_UNPARSED, REPARSE_ALREADY_HELD)
    outcome = REPARSE_ATTACHED
    if tip is None:
        receipt_in = ReceiptInput(
            user_id=receipt.user_id, device_event_key=receipt.device_event_key, medium=receipt.medium,
            app_package=receipt.app_package, channel_label=receipt.channel_label, text=receipt.text,
            device_posted_at=receipt.device_posted_at, parser_version=parser_version,
        )
        tip = _new_tip(session, channel, receipt_in, intake, terms, now=seen,
                       revises_tip_id=original.id if original is not None else None)
        _fill_caller(session, tip, intake.caller_name)
        outcome = REPARSE_CREATED
    receipt.kind, receipt.tip_id = kind, tip.id
    session.commit()
    return ReparseResult(receipt, tip, kind, outcome)


def _reparse_match(session: Session, key: str, *, seen: datetime, now: datetime, horizon: int) -> Tip | None:
    """The tip a late-read call already is (invariant 7: never a second copy): the one matchable when the receipt
    arrived, else the one matchable now, else one first seen later inside the horizon a tip dated `seen` would get."""
    candidates = session.scalars(
        select(Tip)
        .where(Tip.match_key == key, Tip.status != STATUS_MERGED_DUPLICATE)
        .order_by(Tip.first_seen_at.desc(), Tip.id.desc())
    ).all()
    earlier = [tip for tip in candidates if _aware(tip.first_seen_at) <= seen]
    for tip in earlier:
        if tip.status == STATUS_ACTIVE or (tip.closed_at is not None and _aware(tip.closed_at) > seen):
            return tip
    for tip in earlier:
        rejected = tip.status in REJECTED_AT_INTAKE and tip.reason in INTAKE_REJECTION_REASONS
        if rejected and _sessions_since(session, tip.first_seen_at, seen) <= tip.horizon_sessions:
            return tip
    current = find_matchable(session, key, now=now)
    if current is not None:
        return current
    for tip in reversed(candidates):
        if _aware(tip.first_seen_at) > seen and _sessions_since(session, seen, tip.first_seen_at) <= horizon:
            return tip
    return None


def _held(session: Session, tip: Tip, receipt: TipReceipt) -> bool:
    """`_attach`'s rule: one TIP or REVISION holding per customer, tip and medium."""
    return session.scalar(
        select(TipReceipt.id).where(
            TipReceipt.tip_id == tip.id, TipReceipt.user_id == receipt.user_id, TipReceipt.medium == receipt.medium,
            TipReceipt.kind.in_((KIND_TIP, KIND_REVISION)),
        )
    ) is not None
```

- [ ] **Step 6: Run the tests to verify they pass**

Run: `python -m pytest tests/test_tip_reparse.py tests/test_tip_ledger.py tests/test_api_tips_ingest.py tests/test_api_tips_ledger.py -v`
Expected: all PASS. The ingest files show that `ingest_message` behaves as before.

- [ ] **Step 7: Commit**

```bash
git add app/tip_vocabulary.py app/tip_ledger.py api/services/tips.py tests/test_tip_reparse.py
git commit -m "Tip ledger: re-parse an UNPARSED receipt into the tip its call already is, or a tip dated from its arrival

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task A6: Admin API — channels, aliases, channel merge, callers

**Files:**
- Create: `api/schemas/admin_tip_ledger.py`, `api/services/admin_tip_ledger.py`, `api/routers/admin_tip_ledger.py`
- Modify: `api/app.py` (import and include the router)
- Regenerate: `docs/api/openapi.json`
- Test: `tests/test_api_admin_tip_ledger.py`

**Interfaces (all under `/api/v1`, `admin` scope, `{data, meta}` envelopes):**
- `GET /admin/channels` → `AdminChannelView[]`, ordered by name. Each item has:
  - `id: int`, `name: str`, `type: str`;
  - `canonicalChannelId: int`, `canonicalChannelName: str`;
  - `defaultHorizonSessions: int`, `captureEnabled: bool`;
  - `aliases: [{id: int, alias: str, kind: "PACKAGE"|"LABEL", scope: Medium}]`;
  - `tipCount: int`, `createdAt: datetime | null`.
- `PATCH /admin/channels/{channelId}`, body `{name?: str, defaultHorizonSessions?: 0..250, captureEnabled?: bool}` → `AdminChannelView`.
- `POST /admin/channels/{channelId}/aliases`, body `{alias: str, scope: Medium, kind?: "PACKAGE"|"LABEL" = "LABEL"}` → `AdminChannelView`.
- `PATCH /admin/channels/{channelId}/aliases/{aliasId}`, body `{alias?, scope?, kind?}` → `AdminChannelView`.
- `DELETE /admin/channels/{channelId}/aliases/{aliasId}` → `AdminChannelView`.
- `POST /admin/channels/{channelId}/merge`, body `{intoChannelId: int}` → `ChannelMergeView`:
  - `channelId: int`, `intoChannelId: int` (the survivor root), `alreadyMerged: bool`;
  - `repointedChannelIds: int[]`, `tipsRekeyed: int`, `tipsMerged: [{tipId: str, mergedIntoTipId: str}]`;
  - `receiptsMoved: int`, `receiptsKept: int`.
- `GET /admin/callers?channelId=` (canonical group) → `AdminCallerView[]`. Each item has `id`, `name`, `channelId`, `channelName`, `channelType`, `canonicalChannelId`, `canonicalCallerId`, `canonicalCallerName`, `tipCount` and `createdAt`.
- `PATCH /admin/callers/{callerId}`, body `{name: str}` → `AdminCallerView`.
- `POST /admin/callers/{callerId}/merge`, body `{intoCallerId: int}` → `CallerMergeView`: `callerId`, `intoCallerId`, `alreadyMerged` and `repointedCallerIds`.
- `Medium` is one of `"APP_NOTIFICATION"`, `"SMS"`, `"WHATSAPP"`, `"TELEGRAM"`, `"EMAIL"` or `"MANUAL"`.
- Errors: 404 `MRA_NOT_FOUND`; 409 with a `CODE_*` from Task A2 or A4; 422 `MRA_VALIDATION_FAILED`.

- [ ] **Step 1: Write the failing tests**

Create `tests/test_api_admin_tip_ledger.py`. Task A7 appends to it, so its imports and helpers already cover both tasks:

```python
"""Contract tests for /api/v1/admin/{channels,callers,tips,users/{userId}/tips,receipts} (tip-ledger spec §9 admin)."""

from __future__ import annotations

import uuid
from datetime import datetime, timedelta, timezone
from decimal import Decimal

import pytest
from fastapi.testclient import TestClient
from sqlalchemy import create_engine, select
from sqlalchemy.orm import sessionmaker
from sqlalchemy.pool import StaticPool

from api.deps import get_db
from app.auth_session import create_session
from app.db import Base
from app.main import app
from app.models import Caller, Channel, ChannelAlias, Stock, TipReceipt, User
from app.scopes import SCOPE_ADMIN, SCOPE_MARKSY
from app.tip_ledger import Intake, ReceiptInput, TipExtras, record_intake
from app.tip_matching import stated_terms
from app.tip_vocabulary import (
    ALIAS_PACKAGE,
    CHANNEL_BROKER_APP,
    CHANNEL_MARKSY,
    KIND_EXIT,
    KIND_TIP,
    KIND_UNPARSED,
    MEDIUM_APP_NOTIFICATION,
)

NOW = datetime(2026, 9, 28, 5, 0, tzinfo=timezone.utc)  # Monday, 10:30 IST


@pytest.fixture
def session():
    engine = create_engine("sqlite:///:memory:", connect_args={"check_same_thread": False}, poolclass=StaticPool)
    Base.metadata.create_all(engine)
    db = sessionmaker(bind=engine)()
    db.add_all([Stock(symbol="RENUKA", exchange="NSE", is_active=True), Stock(symbol="RELIANCE", exchange="NSE", is_active=True)])
    for name, channel_type in (("Upstox", CHANNEL_BROKER_APP), ("Marksy", CHANNEL_MARKSY)):
        channel = Channel(name=name, type=channel_type, default_horizon_sessions=20,
                          capture_enabled=channel_type == CHANNEL_BROKER_APP)
        db.add(channel)
        db.flush()
        channel.canonical_channel_id = channel.id
    upstox_id = db.scalar(select(Channel.id).where(Channel.name == "Upstox"))
    db.add(ChannelAlias(channel_id=upstox_id, alias="com.upstox.pro", kind=ALIAS_PACKAGE))
    db.commit()
    try:
        yield db
    finally:
        db.close()


@pytest.fixture
def client(session):
    def override_get_db():
        yield session

    app.dependency_overrides[get_db] = override_get_db
    try:
        yield TestClient(app)
    finally:
        app.dependency_overrides.clear()


def _terms(**overrides):
    values = dict(
        symbol="RENUKA", direction="BUY", entry_low=Decimal("23.62"), entry_high=Decimal("23.62"),
        target=Decimal("26"), stop_loss=Decimal("22.25"), horizon_sessions=None,
    )
    values.update(overrides)
    return stated_terms(**values)


def _receipt(user="user-1", key="e-1", package="com.upstox.pro", label="Upstox",
             text="BUY RENUKA CMP 23.62 SL 22.25 TGT 26"):
    return ReceiptInput(
        user_id=user, device_event_key=key, medium=MEDIUM_APP_NOTIFICATION, app_package=package,
        channel_label=label, text=text, device_posted_at=None, parser_version="TCP-001",
    )


def _intake(session, *, terms=None, caller=None, now=NOW, **receipt):
    intake = Intake(kind=KIND_TIP, terms=terms or _terms(), caller_name=caller, extras=TipExtras())
    return record_intake(session, _receipt(**receipt), intake, now=now)


def _exit(session, *, symbol, now=NOW + timedelta(hours=1), **receipt):
    return record_intake(session, _receipt(text=f"Exit {symbol}", **receipt),
                         Intake(kind=KIND_EXIT, exit_symbol=symbol), now=now)


def _unparsed(session, *, user, text, at=NOW):
    receipt = TipReceipt(
        public_id=str(uuid.uuid4()), tip_id=None, user_id=user, device_event_key=f"u-{uuid.uuid4()}", kind=KIND_UNPARSED,
        medium=MEDIUM_APP_NOTIFICATION, app_package="com.upstox.pro", channel_label="Upstox", text=text,
        device_posted_at=None, recorded_at=at, parser_version="TCP-000",
    )
    session.add(receipt)
    session.commit()
    return receipt


def _channel(session, package):
    return session.scalar(
        select(Channel).join(ChannelAlias, ChannelAlias.channel_id == Channel.id).where(ChannelAlias.alias == package)
    )


@pytest.mark.real_auth
def test_the_ledger_admin_surface_needs_the_admin_scope(client, session):
    def bearer(user_id, scopes):
        session.add(User(user_id=user_id, scopes=list(scopes), disabled=False))
        session.commit()
        issued = create_session(session, user_id=user_id, issued_at=datetime.now(timezone.utc))
        return {"Authorization": f"Bearer {issued.session_token}"}

    marksy_only = client.get("/api/v1/admin/channels", headers=bearer("marksy-user", [SCOPE_MARKSY]))
    admin = client.get("/api/v1/admin/channels", headers=bearer("admin-user", [SCOPE_ADMIN]))

    assert (marksy_only.status_code, marksy_only.json()["error"]["code"]) == (403, "MRA_FORBIDDEN")
    assert admin.status_code == 200


def test_channels_list_their_scoped_aliases_and_marksys_channel_is_read_only(client, session):
    upstox = _channel(session, "com.upstox.pro")
    marksy = session.scalar(select(Channel).where(Channel.type == CHANNEL_MARKSY))

    listed = {c["name"]: c for c in client.get("/api/v1/admin/channels").json()["data"]}
    renamed = client.patch(f"/api/v1/admin/channels/{upstox.id}", json={"name": "Upstox Pro", "defaultHorizonSessions": 10})
    impostor = client.patch(f"/api/v1/admin/channels/{upstox.id}", json={"name": "Marksy"})
    frozen = client.patch(f"/api/v1/admin/channels/{marksy.id}", json={"defaultHorizonSessions": 5})

    assert [(a["alias"], a["kind"], a["scope"]) for a in listed["Upstox"]["aliases"]] == [
        ("com.upstox.pro", "PACKAGE", "APP_NOTIFICATION"),
    ]
    assert (listed["Marksy"]["aliases"], listed["Upstox"]["canonicalChannelId"]) == ([], upstox.id)
    assert (renamed.json()["data"]["name"], renamed.json()["data"]["defaultHorizonSessions"]) == ("Upstox Pro", 10)
    assert (impostor.status_code, impostor.json()["error"]["code"]) == (422, "MRA_VALIDATION_FAILED")
    assert (frozen.status_code, frozen.json()["error"]["code"]) == (409, "MRA_MARKSY_CHANNEL_READ_ONLY")


def test_an_alias_is_created_with_its_medium_and_a_masked_label_is_refused(client, session):
    url = f"/api/v1/admin/channels/{_channel(session, 'com.upstox.pro').id}/aliases"

    created = client.post(url, json={"alias": "StockTips", "scope": "WHATSAPP"})
    masked = client.post(url, json={"alias": "+91 98765 43210", "scope": "WHATSAPP"})
    unknown_medium = client.post(url, json={"alias": "StockTips", "scope": "FAX"})

    assert ("stocktips", "LABEL", "WHATSAPP") in [
        (a["alias"], a["kind"], a["scope"]) for a in created.json()["data"]["aliases"]
    ]
    assert [response.status_code for response in (masked, unknown_medium)] == [422, 422]


def test_a_channel_merge_reports_what_it_changed_and_a_repeat_is_already_merged(client, session):
    earlier = _intake(session, key="p-1").tip
    later = _intake(session, key="l-1", package="com.upstox.lite", label="Upstox Lite",
                    now=NOW + timedelta(minutes=5)).tip
    lite, upstox = _channel(session, "com.upstox.lite"), _channel(session, "com.upstox.pro")

    first = client.post(f"/api/v1/admin/channels/{lite.id}/merge", json={"intoChannelId": upstox.id})
    repeat = client.post(f"/api/v1/admin/channels/{lite.id}/merge", json={"intoChannelId": upstox.id})

    assert first.status_code == 200
    assert first.json()["data"] == {
        "channelId": lite.id, "intoChannelId": upstox.id, "alreadyMerged": False, "repointedChannelIds": [lite.id],
        "tipsRekeyed": 1, "tipsMerged": [{"tipId": later.public_id, "mergedIntoTipId": earlier.public_id}],
        "receiptsMoved": 0, "receiptsKept": 1,
    }
    assert repeat.json()["data"]["alreadyMerged"] is True


def test_callers_roll_up_by_canonical_channel_and_merge_only_within_it(client, session):
    _intake(session, key="p-1", caller="Rahul S")
    _intake(session, key="l-1", package="com.upstox.lite", label="Upstox Lite", caller="Rahul Sharma",
            terms=_terms(target=Decimal("27")))
    _intake(session, key="z-1", package="com.zerodha.kite", label="Kite", caller="Priya", terms=_terms(target=Decimal("28")))
    lite, upstox = _channel(session, "com.upstox.lite"), _channel(session, "com.upstox.pro")
    client.post(f"/api/v1/admin/channels/{lite.id}/merge", json={"intoChannelId": upstox.id})

    callers = {c["name"]: c for c in client.get(f"/api/v1/admin/callers?channelId={upstox.id}").json()["data"]}
    merged = client.post(f"/api/v1/admin/callers/{callers['Rahul Sharma']['id']}/merge",
                         json={"intoCallerId": callers["Rahul S"]["id"]})
    priya = session.scalar(select(Caller).where(Caller.name == "Priya"))
    across = client.post(f"/api/v1/admin/callers/{priya.id}/merge", json={"intoCallerId": callers["Rahul S"]["id"]})

    assert sorted(callers) == ["Rahul S", "Rahul Sharma"]
    assert (merged.status_code, merged.json()["data"]["intoCallerId"]) == (200, callers["Rahul S"]["id"])
    assert (across.status_code, across.json()["error"]["code"]) == (409, "MRA_CALLER_CHANNEL_MISMATCH")
```

- [ ] **Step 2: Run them to verify they fail**

Run: `python -m pytest tests/test_api_admin_tip_ledger.py -v`
Expected: 5 FAIL. Every route answers 404. The scope test fails with `assert (404, 'MRA_NOT_FOUND') == (403, 'MRA_FORBIDDEN')`, and the others on their status or `KeyError: 'data'`.

- [ ] **Step 3: Create the DTOs**

Create `api/schemas/admin_tip_ledger.py`:

```python
"""DTOs for the tip-ledger admin surface, /api/v1/admin/* (tip-ledger spec §9). Admin views may name customers
(invariant 10), so receipts carry their `userId`."""

from __future__ import annotations

from datetime import datetime
from decimal import Decimal
from typing import Literal

from pydantic import BaseModel, Field

Medium = Literal["APP_NOTIFICATION", "SMS", "WHATSAPP", "TELEGRAM", "EMAIL", "MANUAL"]
AliasKind = Literal["PACKAGE", "LABEL"]
TipStatus = Literal[
    "ACTIVE", "TARGET_HIT", "STOP_LOSS_HIT", "SOURCE_EXIT", "HORIZON_EXPIRED", "DIRECTION_HORIZON",
    "INVALIDATED", "UNSCORABLE", "DATA_UNRESOLVED", "MERGED_DUPLICATE",
]
ReviewKind = Literal["UNPARSED", "EXIT"]


class ChannelAliasView(BaseModel):
    id: int
    alias: str
    kind: str
    scope: str


class AdminChannelView(BaseModel):
    id: int
    name: str
    type: str
    canonicalChannelId: int
    canonicalChannelName: str
    defaultHorizonSessions: int
    captureEnabled: bool
    aliases: list[ChannelAliasView]
    tipCount: int
    createdAt: datetime | None = None


class UpdateChannelRequest(BaseModel):
    name: str | None = None
    defaultHorizonSessions: int | None = Field(default=None, ge=0, le=250)
    captureEnabled: bool | None = None


class CreateAliasRequest(BaseModel):
    alias: str
    scope: Medium
    kind: AliasKind = "LABEL"


class UpdateAliasRequest(BaseModel):
    alias: str | None = None
    scope: Medium | None = None
    kind: AliasKind | None = None


class MergeChannelRequest(BaseModel):
    intoChannelId: int


class MergedTipView(BaseModel):
    tipId: str
    mergedIntoTipId: str


class ChannelMergeView(BaseModel):
    channelId: int
    intoChannelId: int
    alreadyMerged: bool
    repointedChannelIds: list[int]
    tipsRekeyed: int
    tipsMerged: list[MergedTipView]
    receiptsMoved: int
    receiptsKept: int


class AdminCallerView(BaseModel):
    id: int
    name: str
    channelId: int
    channelName: str
    channelType: str
    canonicalChannelId: int
    canonicalCallerId: int
    canonicalCallerName: str
    tipCount: int
    createdAt: datetime | None = None


class UpdateCallerRequest(BaseModel):
    name: str


class MergeCallerRequest(BaseModel):
    intoCallerId: int


class CallerMergeView(BaseModel):
    callerId: int
    intoCallerId: int
    alreadyMerged: bool
    repointedCallerIds: list[int]


class AdminTipView(BaseModel):
    tipId: str
    symbol: str
    direction: str | None
    entryLow: Decimal | None
    entryHigh: Decimal | None
    entryBasis: str | None
    target: Decimal | None
    stopLoss: Decimal | None
    horizonSessions: int | None
    horizonBasis: str | None
    firstSeenAt: datetime | None
    status: str | None
    entryStatus: str | None
    outcome: str | None
    reason: str | None
    closedAt: datetime | None
    exitPrice: Decimal | None
    promisedReturn: Decimal | None
    actualReturn: Decimal | None
    channelId: int | None
    channelName: str | None
    canonicalChannelId: int | None
    canonicalChannelName: str | None
    callerId: int | None
    callerName: str | None
    predictionId: int | None
    mergedIntoTipId: str | None
    revisesTipId: str | None
    receiptCount: int


class AdminReceiptView(BaseModel):
    receiptId: str
    tipId: str | None
    userId: str
    kind: str
    medium: str
    appPackage: str | None
    channelLabel: str | None
    text: str
    devicePostedAt: datetime | None
    recordedAt: datetime
    parserVersion: str


class ReparseView(BaseModel):
    receiptId: str
    kind: str
    outcome: str
    tipId: str | None
    parserVersion: str
```

- [ ] **Step 4: Create the service**

Create `api/services/admin_tip_ledger.py`. The imports already cover Task A7's tip and receipt functions, so Task A7 only appends code:

```python
"""Backing logic for the tip-ledger admin surface (tip-ledger spec §9 admin): channels, callers, tips, receipts.

Admin views may name customers (invariants 5 and 10). The ledger rules live in `app.tip_ledger_admin` and
`app.tip_ledger`; this module maps them to DTOs and API errors."""

from __future__ import annotations

from collections.abc import Iterator
from contextlib import contextmanager
from dataclasses import dataclass
from datetime import date, datetime, time, timedelta, timezone

from sqlalchemy import func, select
from sqlalchemy.orm import Session

from app.market_data.quality import NSE_TIMEZONE
from app.models import Caller, Channel, ChannelAlias, Tip, TipReceipt
from app.tip_ledger import ChannelLabelRequiredError, NotUnparsedError, ReservedChannelError, reparse_receipt
from app.tip_ledger_admin import (
    LedgerAdminConflict,
    LedgerAdminInvalid,
    LedgerAdminNotFound,
    add_alias,
    delete_alias,
    merge_callers,
    merge_channels,
    rename_caller,
    update_alias,
    update_channel,
)
from app.tip_vocabulary import KIND_EXIT
from app.trade_call_parser import PARSER_VERSION, parse_message

from ..errors import ConflictError, NotFoundError, ValidationError
from ..pagination import DEFAULT_PAGE_SIZE, decode_offset_cursor, encode_offset_cursor
from ..schemas.admin_tip_ledger import (
    AdminCallerView,
    AdminChannelView,
    AdminReceiptView,
    AdminTipView,
    CallerMergeView,
    ChannelAliasView,
    ChannelMergeView,
    CreateAliasRequest,
    MergedTipView,
    ReparseView,
    UpdateAliasRequest,
    UpdateCallerRequest,
    UpdateChannelRequest,
)
from .tips import message_intake


@dataclass
class AdminTipQuery:
    channel_id: int | None = None
    caller_id: int | None = None
    user_id: str | None = None
    status: str | None = None
    symbol: str | None = None
    start_date: date | None = None
    end_date: date | None = None
    page_size: int = DEFAULT_PAGE_SIZE
    cursor: str | None = None


@dataclass
class AdminPage:
    items: list
    next_cursor: str | None


@contextmanager
def _ledger_errors() -> Iterator[None]:
    try:
        yield
    except LedgerAdminNotFound as exc:
        raise NotFoundError(exc.resource, exc.identifier) from exc
    except LedgerAdminConflict as exc:
        raise ConflictError(exc.code, str(exc)) from exc
    except LedgerAdminInvalid as exc:
        raise ValidationError(str(exc), field_errors={exc.field: str(exc)}) from exc
    except (ChannelLabelRequiredError, ReservedChannelError) as exc:
        raise ValidationError(str(exc)) from exc


def _utc(value: datetime | None) -> datetime | None:
    # SQLite drops tzinfo on a DateTime(timezone=True) round-trip.
    if value is None:
        return None
    return value if value.tzinfo is not None else value.replace(tzinfo=timezone.utc)


def _canonical(channel: Channel) -> int:
    return channel.canonical_channel_id or channel.id


def _channel_views(db: Session, channels: list[Channel]) -> list[AdminChannelView]:
    ids = [channel.id for channel in channels]
    names = dict(db.execute(select(Channel.id, Channel.name).where(Channel.id.in_({_canonical(c) for c in channels}))).all())
    aliases: dict[int, list[ChannelAliasView]] = {}
    for alias in db.scalars(
        select(ChannelAlias).where(ChannelAlias.channel_id.in_(ids)).order_by(ChannelAlias.scope, ChannelAlias.alias)
    ):
        aliases.setdefault(alias.channel_id, []).append(
            ChannelAliasView(id=alias.id, alias=alias.alias, kind=alias.kind, scope=alias.scope)
        )
    counts = dict(db.execute(
        select(Tip.source_channel_id, func.count()).where(Tip.source_channel_id.in_(ids)).group_by(Tip.source_channel_id)
    ).all())
    return [
        AdminChannelView(
            id=channel.id, name=channel.name, type=channel.type, canonicalChannelId=_canonical(channel),
            canonicalChannelName=names.get(_canonical(channel), channel.name),
            defaultHorizonSessions=channel.default_horizon_sessions, captureEnabled=channel.capture_enabled,
            aliases=aliases.get(channel.id, []), tipCount=counts.get(channel.id, 0), createdAt=_utc(channel.created_at),
        )
        for channel in channels
    ]


def _channel_view(db: Session, channel_id: int) -> AdminChannelView:
    return _channel_views(db, [db.get(Channel, channel_id)])[0]


def list_channels(db: Session) -> list[AdminChannelView]:
    return _channel_views(db, list(db.scalars(select(Channel).order_by(func.lower(Channel.name), Channel.id))))


def patch_channel(db: Session, channel_id: int, request: UpdateChannelRequest) -> AdminChannelView:
    with _ledger_errors():
        update_channel(db, channel_id, name=request.name, default_horizon_sessions=request.defaultHorizonSessions,
                       capture_enabled=request.captureEnabled)
    return _channel_view(db, channel_id)


def post_alias(db: Session, channel_id: int, request: CreateAliasRequest) -> AdminChannelView:
    with _ledger_errors():
        add_alias(db, channel_id, alias=request.alias, scope=request.scope, kind=request.kind)
    return _channel_view(db, channel_id)


def patch_alias(db: Session, channel_id: int, alias_id: int, request: UpdateAliasRequest) -> AdminChannelView:
    with _ledger_errors():
        update_alias(db, channel_id, alias_id, alias=request.alias, scope=request.scope, kind=request.kind)
    return _channel_view(db, channel_id)


def remove_alias(db: Session, channel_id: int, alias_id: int) -> AdminChannelView:
    with _ledger_errors():
        delete_alias(db, channel_id, alias_id)
    return _channel_view(db, channel_id)


def merge_channel(db: Session, channel_id: int, into_channel_id: int) -> ChannelMergeView:
    with _ledger_errors():
        result = merge_channels(db, channel_id, into_channel_id, now=datetime.now(timezone.utc))
    ids = {tip_id for pair in result.duplicates for tip_id in pair}
    public = dict(db.execute(select(Tip.id, Tip.public_id).where(Tip.id.in_(ids))).all())
    return ChannelMergeView(
        channelId=result.channel_id, intoChannelId=result.into_channel_id, alreadyMerged=result.already_merged,
        repointedChannelIds=result.repointed_channel_ids, tipsRekeyed=result.rekeyed,
        tipsMerged=[MergedTipView(tipId=public[loser], mergedIntoTipId=public[winner]) for loser, winner in result.duplicates],
        receiptsMoved=result.receipts_moved, receiptsKept=result.receipts_kept,
    )


def _caller_views(db: Session, callers: list[Caller]) -> list[AdminCallerView]:
    channels = {c.id: c for c in db.scalars(select(Channel).where(Channel.id.in_({caller.channel_id for caller in callers})))}
    names = dict(db.execute(
        select(Caller.id, Caller.name).where(Caller.id.in_({caller.canonical_caller_id or caller.id for caller in callers}))
    ).all())
    counts = dict(db.execute(
        select(Tip.caller_id, func.count()).where(Tip.caller_id.in_([caller.id for caller in callers])).group_by(Tip.caller_id)
    ).all())
    views = []
    for caller in callers:
        channel = channels[caller.channel_id]
        canonical_id = caller.canonical_caller_id or caller.id
        views.append(AdminCallerView(
            id=caller.id, name=caller.name, channelId=channel.id, channelName=channel.name, channelType=channel.type,
            canonicalChannelId=_canonical(channel), canonicalCallerId=canonical_id,
            canonicalCallerName=names.get(canonical_id, caller.name), tipCount=counts.get(caller.id, 0),
            createdAt=_utc(caller.created_at),
        ))
    return views


def list_callers(db: Session, *, channel_id: int | None) -> list[AdminCallerView]:
    statement = select(Caller).order_by(func.lower(Caller.name), Caller.id)
    if channel_id is not None:
        channel = db.get(Channel, channel_id)
        if channel is None:
            raise NotFoundError("Channel", str(channel_id))
        group = select(Channel.id).where(Channel.canonical_channel_id == _canonical(channel))
        statement = statement.where(Caller.channel_id.in_(group))
    return _caller_views(db, list(db.scalars(statement)))


def patch_caller(db: Session, caller_id: int, request: UpdateCallerRequest) -> AdminCallerView:
    with _ledger_errors():
        rename_caller(db, caller_id, request.name)
    return _caller_views(db, [db.get(Caller, caller_id)])[0]


def merge_caller(db: Session, caller_id: int, into_caller_id: int) -> CallerMergeView:
    with _ledger_errors():
        result = merge_callers(db, caller_id, into_caller_id)
    return CallerMergeView(callerId=result.caller_id, intoCallerId=result.into_caller_id,
                           alreadyMerged=result.already_merged, repointedCallerIds=result.repointed_caller_ids)
```

- [ ] **Step 5: Create the router and register it**

Create `api/routers/admin_tip_ledger.py`. Its imports also cover Task A7's routes:

```python
"""/api/v1/admin/{channels,callers,tips,users/{userId}/tips,receipts} (tip-ledger spec §9 admin).

Router-level `require_scope(admin)`, as in `admin_users`: every route here names customers or changes how calls
roll up. Mutations are also refused for a read-only principal by the `/api/v1` router's `forbid_read_only_mutation`.
"""

from __future__ import annotations

from dataclasses import replace
from datetime import date

from fastapi import APIRouter, Depends, Query
from sqlalchemy.orm import Session

from app.scopes import SCOPE_ADMIN

from ..deps import get_db, require_scope
from ..envelope import cursor_paginated, success
from ..pagination import DEFAULT_PAGE_SIZE, MAX_PAGE_SIZE
from ..schemas.admin_tip_ledger import (
    AdminCallerView,
    AdminChannelView,
    AdminReceiptView,
    AdminTipView,
    CallerMergeView,
    ChannelMergeView,
    CreateAliasRequest,
    MergeCallerRequest,
    MergeChannelRequest,
    ReparseView,
    ReviewKind,
    TipStatus,
    UpdateAliasRequest,
    UpdateCallerRequest,
    UpdateChannelRequest,
)
from ..schemas.common import CursorEnvelope, SuccessEnvelope
from ..services import admin_tip_ledger
from ..services.admin_tip_ledger import AdminTipQuery

router = APIRouter(prefix="/admin", tags=["admin"], dependencies=[Depends(require_scope(SCOPE_ADMIN))])


@router.get("/channels", response_model=SuccessEnvelope[list[AdminChannelView]])
def get_admin_channels(db: Session = Depends(get_db)):
    return success(admin_tip_ledger.list_channels(db))


@router.patch("/channels/{channel_id}", response_model=SuccessEnvelope[AdminChannelView])
def patch_admin_channel(channel_id: int, request: UpdateChannelRequest, db: Session = Depends(get_db)):
    return success(admin_tip_ledger.patch_channel(db, channel_id, request))


@router.post("/channels/{channel_id}/aliases", response_model=SuccessEnvelope[AdminChannelView])
def post_admin_channel_alias(channel_id: int, request: CreateAliasRequest, db: Session = Depends(get_db)):
    return success(admin_tip_ledger.post_alias(db, channel_id, request))


@router.patch("/channels/{channel_id}/aliases/{alias_id}", response_model=SuccessEnvelope[AdminChannelView])
def patch_admin_channel_alias(channel_id: int, alias_id: int, request: UpdateAliasRequest,
                              db: Session = Depends(get_db)):
    return success(admin_tip_ledger.patch_alias(db, channel_id, alias_id, request))


@router.delete("/channels/{channel_id}/aliases/{alias_id}", response_model=SuccessEnvelope[AdminChannelView])
def delete_admin_channel_alias(channel_id: int, alias_id: int, db: Session = Depends(get_db)):
    return success(admin_tip_ledger.remove_alias(db, channel_id, alias_id))


@router.post("/channels/{channel_id}/merge", response_model=SuccessEnvelope[ChannelMergeView])
def post_admin_channel_merge(channel_id: int, request: MergeChannelRequest, db: Session = Depends(get_db)):
    """Irreversible (§5.2): the channel's group joins the target's canonical channel; a repeat is a no-op."""
    return success(admin_tip_ledger.merge_channel(db, channel_id, request.intoChannelId))


@router.get("/callers", response_model=SuccessEnvelope[list[AdminCallerView]])
def get_admin_callers(db: Session = Depends(get_db), channel_id: int | None = Query(default=None, alias="channelId")):
    return success(admin_tip_ledger.list_callers(db, channel_id=channel_id))


@router.patch("/callers/{caller_id}", response_model=SuccessEnvelope[AdminCallerView])
def patch_admin_caller(caller_id: int, request: UpdateCallerRequest, db: Session = Depends(get_db)):
    return success(admin_tip_ledger.patch_caller(db, caller_id, request))


@router.post("/callers/{caller_id}/merge", response_model=SuccessEnvelope[CallerMergeView])
def post_admin_caller_merge(caller_id: int, request: MergeCallerRequest, db: Session = Depends(get_db)):
    return success(admin_tip_ledger.merge_caller(db, caller_id, request.intoCallerId))
```

In `api/app.py`, directly below `from .routers import admin_clients, admin_sessions, admin_users`, add:

```python
# tip-ledger spec §9: the ledger's admin surface, gated on `admin`. Separate line, per the EPIC-309 note.
from .routers import admin_tip_ledger
```

and directly below `api_router.include_router(admin_sessions.router)`, add:

```python
api_router.include_router(admin_tip_ledger.router)
```

- [ ] **Step 6: Regenerate the contract and run the tests**

```bash
python scripts/export_openapi.py
python -m pytest tests/test_api_admin_tip_ledger.py tests/test_openapi_contract_freshness.py \
  tests/test_security_authorization.py tests/test_epic331_authentication_coverage.py \
  tests/test_admin_scope_enforcement.py tests/test_read_only_principal.py -v
```

Expected: all PASS.
- The inventory tests pass because every new route is admin-gated.
- `test_admin_identity_routes_stay_admin_scope_gated` finds no ungated `/api/v1/admin/` route.

- [ ] **Step 7: Commit**

```bash
git add api/schemas/admin_tip_ledger.py api/services/admin_tip_ledger.py api/routers/admin_tip_ledger.py api/app.py \
  docs/api/openapi.json tests/test_api_admin_tip_ledger.py
git commit -m "Admin API: ledger channels, scoped aliases, channel merge and callers (tip-ledger spec 9)

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task A7: Admin API — tips, a tip's receipts, a customer's tips, receipt review and re-parse

**Files:**
- Modify: `api/services/admin_tip_ledger.py` (append)
- Modify: `api/routers/admin_tip_ledger.py` (append)
- Regenerate: `docs/api/openapi.json`
- Test: `tests/test_api_admin_tip_ledger.py`

**Interfaces (all under `/api/v1`, `admin` scope):**
- `GET /admin/tips` → `CursorEnvelope<AdminTipView>`, with `meta.nextCursor`. Query parameters:
  - `channelId` (the canonical group), `callerId` (the canonical caller group), `userId` (tips the customer holds a receipt for, each once);
  - `status` (a `TipStatus`), `symbol` (a prefix);
  - `startDate`/`endDate` (`YYYY-MM-DD`, inclusive IST dates on `first_seen_at`; 422 if start is after end);
  - `pageSize` (1–100, default 20) and `cursor`.

  `AdminTipView` has these fields:
  - `tipId: str`, `symbol: str`, `direction: str | null`;
  - `entryLow`, `entryHigh`, `target`, `stopLoss`, `exitPrice`, `promisedReturn`, `actualReturn`: decimal strings or null;
  - `entryBasis`, `horizonSessions`, `horizonBasis`, `firstSeenAt`;
  - `status`, `entryStatus`, `outcome`, `reason`, `closedAt`;
  - `channelId`, `channelName`, `canonicalChannelId`, `canonicalChannelName`, `callerId`, `callerName`;
  - `predictionId: int | null`, `mergedIntoTipId: str | null`, `revisesTipId: str | null`;
  - `receiptCount: int`.
- `GET /admin/tips/{tipId}/receipts` → `AdminReceiptView[]`, oldest first; 404 for an unknown tip. Each item has:
  - `receiptId`, `tipId`, `userId`, `kind`, `medium`, `appPackage`, `channelLabel`, `text`;
  - `devicePostedAt`, `recordedAt`, `parserVersion`.
- `GET /admin/users/{userId}/tips` → the same as `/admin/tips` with the user taken from the path.
- `GET /admin/receipts?kind=UNPARSED|EXIT` → `CursorEnvelope<AdminReceiptView>`, newest first. `EXIT` means orphan exits only (`tip_id IS NULL`).
- `POST /admin/receipts/{receiptId}/reparse` → `ReparseView`: `receiptId`, `kind`, `outcome`, `tipId | null` and `parserVersion`. Errors: 404; 409 `MRA_RECEIPT_NOT_UNPARSED`; 422 for a label that now resolves to nothing usable.

- [ ] **Step 1: Write the failing tests**

Append to `tests/test_api_admin_tip_ledger.py`:

```python
def test_admin_tips_roll_merged_channels_up_count_a_customers_tip_once_and_page_by_cursor(client, session):
    tip = _intake(session, key="p-1").tip
    _intake(session, user="user-2", key="p-2")
    _exit(session, symbol="RENUKA", key="p-3")  # user-1's second receipt on the same tip
    lite_tip = _intake(session, key="l-1", package="com.upstox.lite", label="Upstox Lite",
                       terms=_terms(target=Decimal("27")), now=NOW + timedelta(hours=2)).tip
    _intake(session, user="user-3", key="z-1", package="com.zerodha.kite", label="Kite", terms=_terms(target=Decimal("28")))
    lite, upstox = _channel(session, "com.upstox.lite"), _channel(session, "com.upstox.pro")
    client.post(f"/api/v1/admin/channels/{lite.id}/merge", json={"intoChannelId": upstox.id})

    by_channel = client.get(f"/api/v1/admin/tips?channelId={upstox.id}").json()["data"]
    mine = client.get("/api/v1/admin/tips?userId=user-1").json()["data"]
    first_page = client.get("/api/v1/admin/tips?pageSize=1").json()
    second_page = client.get(f"/api/v1/admin/tips?pageSize=1&cursor={first_page['meta']['nextCursor']}").json()

    assert [t["tipId"] for t in by_channel] == [lite_tip.public_id, tip.public_id]
    assert [(t["tipId"], t["receiptCount"], t["canonicalChannelName"]) for t in mine] == [
        (lite_tip.public_id, 1, "Upstox"), (tip.public_id, 3, "Upstox"),
    ]
    assert [t["tipId"] for t in first_page["data"]] == [lite_tip.public_id]
    assert len(second_page["data"]) == 1 and second_page["data"][0]["tipId"] != lite_tip.public_id


def test_the_period_is_inclusive_ist_dates_on_first_seen(client, session):
    inside = _intake(session, key="d-1", terms=_terms(target=Decimal("27")),
                     now=datetime(2026, 9, 27, 19, 0, tzinfo=timezone.utc)).tip  # 28 Sep 00:30 IST
    _intake(session, key="d-2", terms=_terms(target=Decimal("28")),
            now=datetime(2026, 9, 27, 18, 20, tzinfo=timezone.utc))  # 27 Sep 23:50 IST
    _intake(session, key="d-3", terms=_terms(target=Decimal("29")),
            now=datetime(2026, 9, 28, 18, 40, tzinfo=timezone.utc))  # 29 Sep 00:10 IST

    day = client.get("/api/v1/admin/tips?startDate=2026-09-28&endDate=2026-09-28")
    backwards = client.get("/api/v1/admin/tips?startDate=2026-09-29&endDate=2026-09-28")

    assert [t["tipId"] for t in day.json()["data"]] == [inside.public_id]
    assert backwards.status_code == 422


def test_a_tips_receipts_name_each_customer_and_medium(client, session):
    tip = _intake(session, key="p-1").tip
    _intake(session, user="user-2", key="p-2", now=NOW + timedelta(minutes=3))

    listed = client.get(f"/api/v1/admin/tips/{tip.public_id}/receipts")
    missing = client.get("/api/v1/admin/tips/no-such-tip/receipts")

    assert [(r["userId"], r["medium"], r["kind"]) for r in listed.json()["data"]] == [
        ("user-1", "APP_NOTIFICATION", "TIP"), ("user-2", "APP_NOTIFICATION", "TIP"),
    ]
    assert missing.status_code == 404


def test_receipt_review_lists_unparsed_and_orphan_exits_and_reparses_one(client, session):
    unparsed = _unparsed(session, user="user-9", text="BUY RELIANCE CMP 2500 SL 2400 TGT 2600")
    _exit(session, symbol="TCS", user="user-4", key="x-1")  # no TCS tip: an orphan
    _intake(session, user="user-5", key="t-1")
    _exit(session, symbol="RENUKA", user="user-5", key="t-2")  # linked to user-5's tip: not an orphan

    queue = client.get("/api/v1/admin/receipts?kind=UNPARSED").json()["data"]
    orphans = client.get("/api/v1/admin/receipts?kind=EXIT").json()["data"]
    reparsed = client.post(f"/api/v1/admin/receipts/{unparsed.public_id}/reparse")
    again = client.post(f"/api/v1/admin/receipts/{unparsed.public_id}/reparse")
    holder = client.get("/api/v1/admin/users/user-9/tips").json()["data"]

    assert [r["receiptId"] for r in queue] == [unparsed.public_id]
    assert [(r["userId"], r["tipId"]) for r in orphans] == [("user-4", None)]
    assert (reparsed.json()["data"]["outcome"], reparsed.json()["data"]["kind"]) == ("CREATED", KIND_TIP)
    assert (again.status_code, again.json()["error"]["code"]) == (409, "MRA_RECEIPT_NOT_UNPARSED")
    assert [t["tipId"] for t in holder] == [reparsed.json()["data"]["tipId"]]
    assert client.get("/api/v1/admin/receipts?kind=UNPARSED").json()["data"] == []
```

- [ ] **Step 2: Run them to verify they fail**

Run: `python -m pytest tests/test_api_admin_tip_ledger.py -v -k "admin_tips or period or receipts_name or receipt_review"`
Expected: 4 FAIL. The new routes answer 404, so each fails on `KeyError: 'data'` or a status assertion.

- [ ] **Step 3: Append the tip and receipt service functions**

Append to `api/services/admin_tip_ledger.py`:

```python
def _ist_window(start: date | None, end: date | None) -> tuple[datetime | None, datetime | None]:
    """§8.3's custom range: inclusive IST dates, as UTC bounds, because SQLite compares stored UTC wall times."""
    if start is not None and end is not None and start > end:
        raise ValidationError("startDate must not be after endDate.", field_errors={"startDate": "after endDate"})
    lower = datetime.combine(start, time(0), NSE_TIMEZONE).astimezone(timezone.utc) if start else None
    upper = datetime.combine(end + timedelta(days=1), time(0), NSE_TIMEZONE).astimezone(timezone.utc) if end else None
    return lower, upper


def _tip_views(db: Session, tips: list[Tip]) -> list[AdminTipView]:
    channel_ids = {tip.source_channel_id for tip in tips if tip.source_channel_id is not None}
    channels = {c.id: c for c in db.scalars(select(Channel).where(Channel.id.in_(channel_ids)))}
    missing = {_canonical(c) for c in channels.values()} - channels.keys()
    channels.update({c.id: c for c in db.scalars(select(Channel).where(Channel.id.in_(missing)))})
    callers = {c.id: c for c in db.scalars(
        select(Caller).where(Caller.id.in_({tip.caller_id for tip in tips if tip.caller_id is not None}))
    )}
    refs = {ref for tip in tips for ref in (tip.merged_into_tip_id, tip.revises_tip_id) if ref is not None}
    public = dict(db.execute(select(Tip.id, Tip.public_id).where(Tip.id.in_(refs))).all())
    counts = dict(db.execute(
        select(TipReceipt.tip_id, func.count()).where(TipReceipt.tip_id.in_([tip.id for tip in tips]))
        .group_by(TipReceipt.tip_id)
    ).all())
    views = []
    for tip in tips:
        channel = channels.get(tip.source_channel_id)
        canonical = channels.get(_canonical(channel)) if channel is not None else None
        caller = callers.get(tip.caller_id)
        views.append(AdminTipView(
            tipId=tip.public_id, symbol=tip.symbol, direction=tip.direction, entryLow=tip.entry_low,
            entryHigh=tip.entry_high, entryBasis=tip.entry_basis, target=tip.target_price, stopLoss=tip.stop_loss,
            horizonSessions=tip.horizon_sessions, horizonBasis=tip.horizon_basis, firstSeenAt=_utc(tip.first_seen_at),
            status=tip.status, entryStatus=tip.entry_status, outcome=tip.outcome, reason=tip.reason,
            closedAt=_utc(tip.closed_at), exitPrice=tip.exit_price, promisedReturn=tip.promised_return,
            actualReturn=tip.actual_return,
            channelId=channel.id if channel is not None else None,
            channelName=channel.name if channel is not None else None,
            canonicalChannelId=canonical.id if canonical is not None else None,
            canonicalChannelName=canonical.name if canonical is not None else None,
            callerId=caller.id if caller is not None else None,
            callerName=caller.name if caller is not None else None,
            predictionId=tip.prediction_id, mergedIntoTipId=public.get(tip.merged_into_tip_id),
            revisesTipId=public.get(tip.revises_tip_id), receiptCount=counts.get(tip.id, 0),
        ))
    return views


def list_admin_tips(db: Session, query: AdminTipQuery) -> AdminPage:
    """Ledger tips, newest first; Marksy's are included and pre-ledger rows (status NULL) wait for the §12 backfill."""
    statement = select(Tip).where(Tip.status.is_not(None)).order_by(Tip.first_seen_at.desc(), Tip.id.desc())
    if query.channel_id is not None:
        channel = db.get(Channel, query.channel_id)
        if channel is None:
            raise NotFoundError("Channel", str(query.channel_id))
        statement = statement.where(
            Tip.source_channel_id.in_(select(Channel.id).where(Channel.canonical_channel_id == _canonical(channel)))
        )
    if query.caller_id is not None:
        caller = db.get(Caller, query.caller_id)
        if caller is None:
            raise NotFoundError("Caller", str(query.caller_id))
        canonical_caller = caller.canonical_caller_id or caller.id
        statement = statement.where(Tip.caller_id.in_(select(Caller.id).where(Caller.canonical_caller_id == canonical_caller)))
    if query.user_id:
        statement = statement.where(
            select(TipReceipt.id).where(TipReceipt.tip_id == Tip.id, TipReceipt.user_id == query.user_id).exists()
        )
    if query.status:
        statement = statement.where(Tip.status == query.status)
    if query.symbol and query.symbol.strip():
        statement = statement.where(Tip.symbol.startswith(query.symbol.strip().upper(), autoescape=True))
    lower, upper = _ist_window(query.start_date, query.end_date)
    if lower is not None:
        statement = statement.where(Tip.first_seen_at >= lower)
    if upper is not None:
        statement = statement.where(Tip.first_seen_at < upper)
    offset = decode_offset_cursor(query.cursor) if query.cursor else 0
    tips = list(db.scalars(statement.offset(offset).limit(query.page_size + 1)))
    has_more = len(tips) > query.page_size
    return AdminPage(
        items=_tip_views(db, tips[: query.page_size]),
        next_cursor=encode_offset_cursor(offset + query.page_size) if has_more else None,
    )


def _receipt_view(receipt: TipReceipt, tip_id: str | None) -> AdminReceiptView:
    return AdminReceiptView(
        receiptId=receipt.public_id, tipId=tip_id, userId=receipt.user_id, kind=receipt.kind, medium=receipt.medium,
        appPackage=receipt.app_package, channelLabel=receipt.channel_label, text=receipt.text,
        devicePostedAt=_utc(receipt.device_posted_at), recordedAt=_utc(receipt.recorded_at),
        parserVersion=receipt.parser_version,
    )


def tip_receipts(db: Session, tip_id: str) -> list[AdminReceiptView]:
    """Every customer who received this tip (invariant 10 allows it here); a Marksy tip has none by design."""
    tip = db.scalar(select(Tip).where(Tip.public_id == tip_id))
    if tip is None:
        raise NotFoundError("Tip", tip_id)
    receipts = db.scalars(
        select(TipReceipt).where(TipReceipt.tip_id == tip.id).order_by(TipReceipt.recorded_at, TipReceipt.id)
    )
    return [_receipt_view(receipt, tip.public_id) for receipt in receipts]


def list_review_receipts(db: Session, *, kind: str, page_size: int, cursor: str | None) -> AdminPage:
    statement = (
        select(TipReceipt).where(TipReceipt.kind == kind)
        .order_by(TipReceipt.recorded_at.desc(), TipReceipt.id.desc())
    )
    if kind == KIND_EXIT:
        statement = statement.where(TipReceipt.tip_id.is_(None))  # §5.2: an exit with no ACTIVE tip is an orphan
    offset = decode_offset_cursor(cursor) if cursor else 0
    rows = list(db.scalars(statement.offset(offset).limit(page_size + 1)))
    return AdminPage(
        items=[_receipt_view(receipt, None) for receipt in rows[:page_size]],
        next_cursor=encode_offset_cursor(offset + page_size) if len(rows) > page_size else None,
    )


def reparse(db: Session, receipt_id: str) -> ReparseView:
    receipt = db.scalar(select(TipReceipt).where(TipReceipt.public_id == receipt_id).with_for_update())
    if receipt is None:
        raise NotFoundError("Receipt", receipt_id)
    intake = message_intake(parse_message(receipt.text))
    try:
        result = reparse_receipt(db, receipt, intake, parser_version=PARSER_VERSION, now=datetime.now(timezone.utc))
    except NotUnparsedError as exc:
        raise ConflictError("MRA_RECEIPT_NOT_UNPARSED", str(exc)) from exc
    except (ChannelLabelRequiredError, ReservedChannelError) as exc:
        raise ValidationError(str(exc)) from exc
    return ReparseView(
        receiptId=result.receipt.public_id, kind=result.kind, outcome=result.outcome,
        tipId=result.tip.public_id if result.tip is not None else None, parserVersion=result.receipt.parser_version,
    )
```

- [ ] **Step 4: Append the routes**

Append to `api/routers/admin_tip_ledger.py`:

```python
def _tip_filters(
    channel_id: int | None = Query(default=None, alias="channelId"),
    caller_id: int | None = Query(default=None, alias="callerId"),
    status: TipStatus | None = Query(default=None),
    symbol: str | None = Query(default=None, description="Symbol prefix"),
    start_date: date | None = Query(default=None, alias="startDate", description="IST date, inclusive, on first_seen_at"),
    end_date: date | None = Query(default=None, alias="endDate", description="IST date, inclusive, on first_seen_at"),
    pageSize: int = Query(default=DEFAULT_PAGE_SIZE, ge=1, le=MAX_PAGE_SIZE),
    cursor: str | None = Query(default=None),
) -> AdminTipQuery:
    return AdminTipQuery(channel_id=channel_id, caller_id=caller_id, status=status, symbol=symbol,
                         start_date=start_date, end_date=end_date, page_size=pageSize, cursor=cursor)


@router.get("/tips", response_model=CursorEnvelope[AdminTipView])
def get_admin_tips(query: AdminTipQuery = Depends(_tip_filters),
                   user_id: str | None = Query(default=None, alias="userId"), db: Session = Depends(get_db)):
    """`userId` narrows to the tips that customer holds a receipt for, each counted once."""
    page = admin_tip_ledger.list_admin_tips(db, replace(query, user_id=user_id))
    return cursor_paginated(page.items, page_size=query.page_size, next_cursor=page.next_cursor)


@router.get("/tips/{tip_id}/receipts", response_model=SuccessEnvelope[list[AdminReceiptView]])
def get_admin_tip_receipts(tip_id: str, db: Session = Depends(get_db)):
    return success(admin_tip_ledger.tip_receipts(db, tip_id))


@router.get("/users/{user_id}/tips", response_model=CursorEnvelope[AdminTipView])
def get_admin_user_tips(user_id: str, query: AdminTipQuery = Depends(_tip_filters), db: Session = Depends(get_db)):
    page = admin_tip_ledger.list_admin_tips(db, replace(query, user_id=user_id))
    return cursor_paginated(page.items, page_size=query.page_size, next_cursor=page.next_cursor)


@router.get("/receipts", response_model=CursorEnvelope[AdminReceiptView])
def get_admin_receipts(
    kind: ReviewKind = Query(...),
    pageSize: int = Query(default=DEFAULT_PAGE_SIZE, ge=1, le=MAX_PAGE_SIZE),
    cursor: str | None = Query(default=None),
    db: Session = Depends(get_db),
):
    """UNPARSED receipts, or EXIT receipts that found no ACTIVE tip (orphans), newest first."""
    page = admin_tip_ledger.list_review_receipts(db, kind=kind, page_size=pageSize, cursor=cursor)
    return cursor_paginated(page.items, page_size=pageSize, next_cursor=page.next_cursor)


@router.post("/receipts/{receipt_id}/reparse", response_model=SuccessEnvelope[ReparseView])
def post_admin_receipt_reparse(receipt_id: str, db: Session = Depends(get_db)):
    """Invariant 7: UNPARSED receipts only (409 otherwise); an existing tip is never changed."""
    return success(admin_tip_ledger.reparse(db, receipt_id))
```

- [ ] **Step 5: Regenerate the contract and run the tests**

```bash
python scripts/export_openapi.py
python -m pytest tests/test_api_admin_tip_ledger.py tests/test_openapi_contract_freshness.py \
  tests/test_security_authorization.py tests/test_epic331_authentication_coverage.py -v
```

Expected: all PASS. `test_no_endpoint_accepts_a_user_identity_from_the_client` exempts the `userId`/`user_id` routes as admin management routes, because every one of them is admin-gated.

- [ ] **Step 6: Commit**

```bash
git add api/services/admin_tip_ledger.py api/routers/admin_tip_ledger.py docs/api/openapi.json tests/test_api_admin_tip_ledger.py
git commit -m "Admin API: ledger tips with receipts and customers, receipt review and re-parse (tip-ledger spec 9)

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task A8: Regression set, PR, merge, deploy, verify

**Files:** none new.

- [ ] **Step 1: Run the regression set**

```bash
rm -f "$TEMP/marksy-pytest-default.db"
python -m pytest tests/test_tip_*.py tests/test_api_admin_tip_ledger.py tests/test_api_tips*.py tests/test_api_channels.py \
  tests/test_channel_alias_scope_migration.py tests/test_marksy_prediction_tips.py tests/test_external_tip_*.py \
  tests/test_prediction_outcome_monitor.py tests/test_epic853_confirmation.py tests/test_run_tip_tracker.py \
  tests/test_security_authorization.py tests/test_epic331_authentication_coverage.py \
  tests/test_admin_scope_enforcement.py tests/test_admin_management_api.py tests/test_read_only_principal.py \
  tests/test_openapi_contract_freshness.py tests/test_alembic_single_head.py -v
python -m alembic heads
```

Expected:
- All PASS. The `tests/test_tip_*.py` glob covers the ledger, guard, matching, cleaning, tracker, tracking-job, admin and re-parse files, plus the 4a and 2b migration tests on main.
- Exactly one head: main's (`0184_channel_alias_scope` or later). Part A adds none.

Handle any failure as the Global Constraints describe.

- [ ] **Step 2: Push and open the PR**

```bash
git push -u origin feat/tip-admin-api
gh pr create --title "Tip ledger Phase 3a: admin APIs for channels, callers, tips, receipts and re-parse" --body "$(cat <<'EOF'
The ledger's admin surface (tip-ledger spec §9, admin scope), minus `/admin/scorecards` (Phase 3b).

- `/admin/channels`: list, rename, default horizon, capture flag, scoped aliases (4a), merge
- Merge (§5.2): ACTIVE tips of both groups re-keyed on the survivor from stated terms only; a later tip that now
  shares a key becomes MERGED_DUPLICATE (reason CHANNEL_MERGE) and its receipts move, except a TIP receipt the
  customer already holds on the earlier tip; aliases stay; no term field changes (guard untouched)
- Intake's alias lookup takes FOR SHARE on its channel and the merge takes FOR UPDATE, so neither re-keys under
  the other; the tracker re-reads each tip under a row lock and skips one a merge retired
- `/admin/callers`: list, rename, merge within one canonical channel
- `/admin/tips` (channel/caller roll-up, customer, status, symbol, IST date range, cursor pages),
  `/admin/tips/{id}/receipts`, `/admin/users/{id}/tips`
- `/admin/receipts?kind=UNPARSED|EXIT` and `POST /admin/receipts/{id}/reparse` (invariant 7: joins the tip the
  call already is, else a tip dated from the receipt's own arrival; an exit stays unlinked)
- No migration

Tests: tip ledger admin, re-parse, admin API, tracker, tip regression files, auth inventory, OpenAPI freshness.

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```

- [ ] **Step 3: Merge and deploy**

```bash
gh pr merge --merge --delete-branch
ssh marksy@103.178.160.233 'bash ~/deployment/server/vps-deploy.sh'
```

Expected: the deploy script finishes, `market-agent-migrate` completes with no new revision, and every rollout reports success.

- [ ] **Step 4: Smoke-test production with a short-lived admin key**

No admin smoke credential exists, so mint one inside the API pod, use it, then revoke it through the API:

```bash
read -r KEY_ID KEY <<<"$(ssh marksy@103.178.160.233 'KUBECONFIG=~/.kube/config kubectl -n market-agent exec -i deploy/market-agent-api -- python -' <<'EOF'
from app.api_keys import mint_api_key
from app.db import SessionLocal
raw, row = mint_api_key(SessionLocal(), client_name="smoke-phase3a", scopes=["admin"])
print(row.id, raw)
EOF
)"
BASE=https://admin.indoulia.com/api/v1
curl -sS -o /dev/null -w "unauthenticated: %{http_code}\n" "$BASE/admin/channels"
curl -sS -H "X-API-Key: $KEY" "$BASE/admin/channels" | python -c "import json,sys; d=json.load(sys.stdin)['data']; print('channels', len(d), sorted({c['type'] for c in d}))"
curl -sS -H "X-API-Key: $KEY" "$BASE/admin/callers" | python -c "import json,sys; print('callers', len(json.load(sys.stdin)['data']))"
curl -sS -H "X-API-Key: $KEY" "$BASE/admin/tips?pageSize=5" | python -c "import json,sys; b=json.load(sys.stdin); print('tips', [(t['symbol'], t['status'], t['receiptCount']) for t in b['data']], b['meta']['nextCursor'])"
curl -sS -H "X-API-Key: $KEY" "$BASE/admin/receipts?kind=UNPARSED&pageSize=5" | python -c "import json,sys; print('unparsed', len(json.load(sys.stdin)['data']))"
curl -sS -H "X-API-Key: $KEY" "$BASE/admin/receipts?kind=EXIT&pageSize=5" | python -c "import json,sys; print('orphan exits', len(json.load(sys.stdin)['data']))"
curl -sS -X POST -H "X-API-Key: $KEY" "$BASE/admin/clients/$KEY_ID/revoke" | python -c "import json,sys; print('revoked', json.load(sys.stdin)['data']['revoked'])"
curl -sS -o /dev/null -w "after revoke: %{http_code}\n" -H "X-API-Key: $KEY" "$BASE/admin/channels"
```

Expected:
- `unauthenticated: 401`.
- A channel count, with types including `BROKER_APP` and `MARKSY`.
- A caller count.
- Up to 5 tips with statuses, and a `nextCursor` if more exist.
- Two receipt counts.
- `revoked True`, then `after revoke: 401`.

If `python -` fails in the pod with `ModuleNotFoundError: app`, rerun the heredoc through `sh -c 'cd /app && python -'`. Nothing is mutated in production, and the key never leaves this shell.

- [ ] **Step 5: Clean up the worktree**

```bash
cd /c/AIAgent/marksy-api && git worktree remove ../marksy-api-phase3a && git checkout main && git pull
```

---

## Part B — admin-app: ledger screens

### Task B1: Production gate, worktree, cursor pages in the API client, ledger DTOs

**Files:**
- Modify: `src/api/client.ts` (from `async function request<T>` to the end of the file)
- Modify: `src/api/types.ts` (append)
- Create: `src/api/tipLedger.ts`
- Test: `src/api/client.test.ts`

**Interfaces:**
- `http.del<T>(path: string): Promise<T>` sends DELETE and unwraps `.data`.
- `http.getPage<T>(path: string): Promise<Page<T>>` returns `{ items: T[]; nextCursor: string | null }` from a `cursor_paginated` envelope (`meta.nextCursor`).
- `src/api/types.ts` mirrors `api/schemas/admin_tip_ledger.py` field for field:
  - the constants `TIP_MEDIA` and `TIP_STATUSES`;
  - the types `TipMedium`, `AliasKind`, `ReceiptReviewKind`, `Page<T>`, `ChannelAliasView`, `AdminChannelView`, `UpdateChannelRequest`, `CreateAliasRequest`, `UpdateAliasRequest`, `MergedTipView`, `ChannelMergeView`, `AdminCallerView`, `CallerMergeView`, `AdminTipView`, `AdminReceiptView`, `ReparseView` and `TipFilters`.

  Decimals arrive as strings.
- `src/api/tipLedger.ts`:
  - `channelsApi`: `list`, `update`, `addAlias`, `updateAlias`, `removeAlias`, `merge`;
  - `callersApi`: `list`, `rename`, `merge`;
  - `ledgerApi`: `tips`, `customerTips`, `receipts`;
  - `receiptsApi`: `list`, `reparse`.

  Every list requests `pageSize=100` and omits empty filters.

- [ ] **Step 1: Check that Part A is in production, then create the worktree**

```bash
curl -sS -o /dev/null -w "%{http_code}\n" https://admin.indoulia.com/api/v1/admin/channels
cd /c/AIAgent/admin-app && git fetch origin && git worktree add ../admin-app-phase5 -b feat/tip-ledger-admin origin/main
cd /c/AIAgent/admin-app-phase5 && git log --oneline -1
```

Expected:
- `401`. The route exists and wants a credential. If it prints `404`, **stop**: Part A is not deployed.
- HEAD is `59e4c62 Admin: Ingest Tips page ...` or a later main commit.

`C:\AIAgent\admin-app` stays on `feat/gateway-qr-provisioning`. All later paths are in `C:\AIAgent\admin-app-phase5`.

Controller: ask the user to run `! npm ci` in `C:\AIAgent\admin-app-phase5`, and wait for it to finish.

- [ ] **Step 2: Write the failing test**

In `src/api/client.test.ts`, add these imports:

```ts
import { http as mswHttp, HttpResponse } from "msw";
import { server } from "../test/server";
```

and add this case at the end of the `describe("api client", ...)` block:

```ts
  it("reads a cursor page's items and next cursor from the envelope meta", async () => {
    server.use(
      mswHttp.get("http://localhost/api/v1/admin/tips", () =>
        HttpResponse.json({
          data: [{ tipId: "t-1" }],
          meta: { requestId: "t", timestamp: "", pageSize: 100, nextCursor: "c2" },
        }),
      ),
    );
    setToken("test-token-abc");
    await expect(http.getPage<{ tipId: string }>("/admin/tips")).resolves.toEqual({
      items: [{ tipId: "t-1" }],
      nextCursor: "c2",
    });
  });
```

- [ ] **Step 3: Run it to verify it fails**

Controller: ask the user to run `! npm test -- src/api/client.test.ts` in `C:\AIAgent\admin-app-phase5`.
Expected: the new case FAILs with `TypeError: http.getPage is not a function`. The existing cases pass.

- [ ] **Step 4: Add cursor pages and DELETE to the client**

In `src/api/client.ts`, change `import type { ApiErrorBody, Envelope } from "./types";` to:

```ts
import type { ApiErrorBody, Envelope, Page } from "./types";
```

Then replace everything from `async function request<T>(path: string, opts: RequestOptions = {}): Promise<T> {` to the end of the file with:

```ts
async function requestEnvelope<T>(path: string, opts: RequestOptions = {}): Promise<Envelope<T>> {
  const { method = "GET", body, auth = true } = opts;
  const headers: Record<string, string> = { Accept: "application/json" };
  if (body !== undefined) headers["Content-Type"] = "application/json";
  if (auth) {
    const token = getToken();
    if (token) headers["Authorization"] = `Bearer ${token}`;
  }

  let res: Response;
  try {
    res = await fetch(`${apiBaseUrl()}${path}`, {
      method,
      headers,
      body: body === undefined ? undefined : JSON.stringify(body),
    });
  } catch (e) {
    // Network / CORS / DNS failure — no HTTP status at all.
    throw new ApiError(0, null, e instanceof Error ? e.message : "Network error");
  }

  if (res.status === 204) return { data: undefined as T, meta: {} };

  let payload: unknown = null;
  const text = await res.text();
  if (text) {
    try {
      payload = JSON.parse(text);
    } catch {
      payload = null;
    }
  }

  if (!res.ok) {
    const errBody = (payload as ApiErrorBody | null)?.error ?? null;
    throw new ApiError(res.status, errBody, `Request failed (${res.status})`);
  }

  return payload as Envelope<T>;
}

async function request<T>(path: string, opts: RequestOptions = {}): Promise<T> {
  return (await requestEnvelope<T>(path, opts)).data;
}

// Cursor-paginated lists (api/envelope.py cursor_paginated) carry the next cursor in meta, not in data.
async function requestPage<T>(path: string): Promise<Page<T>> {
  const envelope = await requestEnvelope<T[]>(path);
  const next = envelope.meta.nextCursor;
  return { items: envelope.data, nextCursor: typeof next === "string" ? next : null };
}

export const http = {
  get: <T>(path: string) => request<T>(path),
  post: <T>(path: string, body?: unknown, auth = true) =>
    request<T>(path, { method: "POST", body, auth }),
  patch: <T>(path: string, body?: unknown) => request<T>(path, { method: "PATCH", body }),
  del: <T>(path: string) => request<T>(path, { method: "DELETE" }),
  getPage: <T>(path: string) => requestPage<T>(path),
};
```

- [ ] **Step 5: Add the ledger DTOs**

Append to `src/api/types.ts`:

```ts
// --- Tip ledger admin (api/schemas/admin_tip_ledger.py, tip-ledger spec §9) ---
export const TIP_MEDIA = ["APP_NOTIFICATION", "SMS", "WHATSAPP", "TELEGRAM", "EMAIL", "MANUAL"] as const;
export type TipMedium = (typeof TIP_MEDIA)[number];
export type AliasKind = "PACKAGE" | "LABEL";
export const TIP_STATUSES = [
  "ACTIVE",
  "TARGET_HIT",
  "STOP_LOSS_HIT",
  "SOURCE_EXIT",
  "HORIZON_EXPIRED",
  "DIRECTION_HORIZON",
  "INVALIDATED",
  "UNSCORABLE",
  "DATA_UNRESOLVED",
  "MERGED_DUPLICATE",
] as const;
export type ReceiptReviewKind = "UNPARSED" | "EXIT";

export interface Page<T> {
  items: T[];
  nextCursor: string | null;
}

export interface ChannelAliasView {
  id: number;
  alias: string;
  kind: AliasKind;
  scope: TipMedium;
}

export interface AdminChannelView {
  id: number;
  name: string;
  type: string;
  canonicalChannelId: number;
  canonicalChannelName: string;
  defaultHorizonSessions: number;
  captureEnabled: boolean;
  aliases: ChannelAliasView[];
  tipCount: number;
  createdAt: string | null;
}

export interface UpdateChannelRequest {
  name?: string;
  defaultHorizonSessions?: number;
  captureEnabled?: boolean;
}

export interface CreateAliasRequest {
  alias: string;
  scope: TipMedium;
  kind: AliasKind;
}

export interface UpdateAliasRequest {
  alias?: string;
  scope?: TipMedium;
  kind?: AliasKind;
}

export interface MergedTipView {
  tipId: string;
  mergedIntoTipId: string;
}

export interface ChannelMergeView {
  channelId: number;
  intoChannelId: number;
  alreadyMerged: boolean;
  repointedChannelIds: number[];
  tipsRekeyed: number;
  tipsMerged: MergedTipView[];
  receiptsMoved: number;
  receiptsKept: number;
}

export interface AdminCallerView {
  id: number;
  name: string;
  channelId: number;
  channelName: string;
  channelType: string;
  canonicalChannelId: number;
  canonicalCallerId: number;
  canonicalCallerName: string;
  tipCount: number;
  createdAt: string | null;
}

export interface CallerMergeView {
  callerId: number;
  intoCallerId: number;
  alreadyMerged: boolean;
  repointedCallerIds: number[];
}

export interface AdminTipView {
  tipId: string;
  symbol: string;
  direction: string | null;
  entryLow: string | null;
  entryHigh: string | null;
  entryBasis: string | null;
  target: string | null;
  stopLoss: string | null;
  horizonSessions: number | null;
  horizonBasis: string | null;
  firstSeenAt: string | null;
  status: string | null;
  entryStatus: string | null;
  outcome: string | null;
  reason: string | null;
  closedAt: string | null;
  exitPrice: string | null;
  promisedReturn: string | null;
  actualReturn: string | null;
  channelId: number | null;
  channelName: string | null;
  canonicalChannelId: number | null;
  canonicalChannelName: string | null;
  callerId: number | null;
  callerName: string | null;
  predictionId: number | null;
  mergedIntoTipId: string | null;
  revisesTipId: string | null;
  receiptCount: number;
}

export interface AdminReceiptView {
  receiptId: string;
  tipId: string | null;
  userId: string;
  kind: string;
  medium: string;
  appPackage: string | null;
  channelLabel: string | null;
  text: string;
  devicePostedAt: string | null;
  recordedAt: string;
  parserVersion: string;
}

export interface ReparseView {
  receiptId: string;
  kind: string;
  outcome: string;
  tipId: string | null;
  parserVersion: string;
}

// Query parameters of /admin/tips; empty values are never sent.
export type TipFilters = {
  status?: string;
  channelId?: number | null;
  callerId?: number | null;
  userId?: string;
  symbol?: string;
  startDate?: string;
  endDate?: string;
};
```

- [ ] **Step 6: Create the ledger API module**

Create `src/api/tipLedger.ts`:

```ts
import { http } from "./client";
import type {
  AdminCallerView,
  AdminChannelView,
  AdminReceiptView,
  AdminTipView,
  CallerMergeView,
  ChannelMergeView,
  CreateAliasRequest,
  ReceiptReviewKind,
  ReparseView,
  TipFilters,
  UpdateAliasRequest,
  UpdateChannelRequest,
} from "./types";

// Tip-ledger admin (tip-ledger spec §9; /api/v1/admin/*, admin scope). Lists are cursor pages of 100.
const PAGE_SIZE = 100;

function qs(params: Record<string, string | number | null | undefined>): string {
  const q = new URLSearchParams();
  for (const [key, value] of Object.entries(params)) {
    if (value !== null && value !== undefined && value !== "") q.set(key, String(value));
  }
  const s = q.toString();
  return s ? `?${s}` : "";
}

export const channelsApi = {
  list: () => http.get<AdminChannelView[]>("/admin/channels"),
  update: (id: number, body: UpdateChannelRequest) =>
    http.patch<AdminChannelView>(`/admin/channels/${id}`, body),
  addAlias: (id: number, body: CreateAliasRequest) =>
    http.post<AdminChannelView>(`/admin/channels/${id}/aliases`, body),
  updateAlias: (id: number, aliasId: number, body: UpdateAliasRequest) =>
    http.patch<AdminChannelView>(`/admin/channels/${id}/aliases/${aliasId}`, body),
  removeAlias: (id: number, aliasId: number) =>
    http.del<AdminChannelView>(`/admin/channels/${id}/aliases/${aliasId}`),
  merge: (id: number, intoChannelId: number) =>
    http.post<ChannelMergeView>(`/admin/channels/${id}/merge`, { intoChannelId }),
};

export const callersApi = {
  list: (channelId?: number | null) => http.get<AdminCallerView[]>(`/admin/callers${qs({ channelId })}`),
  rename: (id: number, name: string) => http.patch<AdminCallerView>(`/admin/callers/${id}`, { name }),
  merge: (id: number, intoCallerId: number) =>
    http.post<CallerMergeView>(`/admin/callers/${id}/merge`, { intoCallerId }),
};

export const ledgerApi = {
  tips: (filters: TipFilters, cursor: string | null) =>
    http.getPage<AdminTipView>(`/admin/tips${qs({ ...filters, pageSize: PAGE_SIZE, cursor })}`),
  customerTips: (userId: string, filters: Omit<TipFilters, "userId">, cursor: string | null) =>
    http.getPage<AdminTipView>(
      `/admin/users/${encodeURIComponent(userId)}/tips${qs({ ...filters, pageSize: PAGE_SIZE, cursor })}`,
    ),
  receipts: (tipId: string) =>
    http.get<AdminReceiptView[]>(`/admin/tips/${encodeURIComponent(tipId)}/receipts`),
};

export const receiptsApi = {
  list: (kind: ReceiptReviewKind, cursor: string | null) =>
    http.getPage<AdminReceiptView>(`/admin/receipts${qs({ kind, pageSize: PAGE_SIZE, cursor })}`),
  reparse: (receiptId: string) =>
    http.post<ReparseView>(`/admin/receipts/${encodeURIComponent(receiptId)}/reparse`),
};
```

- [ ] **Step 7: Run the tests and the typecheck**

Controller: ask the user to run `! npm test -- src/api/client.test.ts` and `! npm run typecheck` in `C:\AIAgent\admin-app-phase5`.
Expected: all PASS, and the typecheck is clean.

- [ ] **Step 8: Commit**

```bash
cd /c/AIAgent/admin-app-phase5
git add src/api/client.ts src/api/client.test.ts src/api/types.ts src/api/tipLedger.ts
git commit -m "Admin: cursor pages and DELETE in the API client; tip-ledger admin DTOs and endpoints

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task B2: Channels page — edit, scoped aliases, confirmed merge

**Files:**
- Create: `src/pages/ChannelsPage.tsx`
- Modify: `src/App.tsx` (route `/channels`), `src/components/Layout.tsx` (a "Channels" entry after "Ingest Tips")
- Test: `src/pages/ChannelsPage.test.tsx`

**Interfaces:**
- `ChannelsPage` reads `["channels"]`. After any mutation it invalidates `["channels"]`, `["callers"]` and `["tip-ledger"]`.
- Merge targets are other groups' canonical channels, never MARKSY. The "Merge permanently" button stays disabled until a target is chosen and the "cannot be undone" box is ticked.
- Marksy's row has no actions, only a "read-only" badge.

- [ ] **Step 1: Write the failing tests**

Create `src/pages/ChannelsPage.test.tsx`:

```tsx
import { describe, expect, it } from "vitest";
import { screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { server } from "../test/server";
import { renderApp, seedAuth } from "../test/render";
import type { AdminChannelView } from "../api/types";
import { ChannelsPage } from "./ChannelsPage";

const BASE = "http://localhost/api/v1";

function ok(data: unknown) {
  return HttpResponse.json({ data, meta: { requestId: "t", timestamp: new Date().toISOString() } });
}

function fail(status: number, code: string, message: string) {
  return HttpResponse.json(
    { error: { code, message, details: {}, retryable: false }, meta: { requestId: "t", timestamp: "" } },
    { status },
  );
}

function channel(overrides: Partial<AdminChannelView>): AdminChannelView {
  return {
    id: 1,
    name: "Upstox",
    type: "BROKER_APP",
    canonicalChannelId: 1,
    canonicalChannelName: "Upstox",
    defaultHorizonSessions: 20,
    captureEnabled: true,
    aliases: [{ id: 1, alias: "com.upstox.pro", kind: "PACKAGE", scope: "APP_NOTIFICATION" }],
    tipCount: 3,
    createdAt: "2026-09-29T10:00:00Z",
    ...overrides,
  };
}

const LITE = channel({ id: 2, name: "Upstox Lite", canonicalChannelId: 2, canonicalChannelName: "Upstox Lite", aliases: [], tipCount: 1 });
const CHANNELS = [
  channel({}),
  LITE,
  channel({ id: 3, name: "Marksy", type: "MARKSY", canonicalChannelId: 3, canonicalChannelName: "Marksy", aliases: [], captureEnabled: false }),
];

async function openRow(name: string, action: string) {
  const row = (await screen.findByText(name)).closest("tr")!;
  await userEvent.click(within(row).getByRole("button", { name: action }));
  return screen.getByRole("dialog");
}

describe("ChannelsPage", () => {
  it("merges only after the permanent-merge acknowledgment and reports what changed", async () => {
    let body: unknown = null;
    server.use(
      http.get(`${BASE}/admin/channels`, () => ok(CHANNELS)),
      http.post(`${BASE}/admin/channels/2/merge`, async ({ request }) => {
        body = await request.json();
        return ok({
          channelId: 2,
          intoChannelId: 1,
          alreadyMerged: false,
          repointedChannelIds: [2],
          tipsRekeyed: 1,
          tipsMerged: [{ tipId: "t-2", mergedIntoTipId: "t-1" }],
          receiptsMoved: 2,
          receiptsKept: 1,
        });
      }),
    );
    seedAuth();
    renderApp(<ChannelsPage />);

    const dialog = await openRow("Upstox Lite", "Merge");
    expect(within(dialog).queryByRole("option", { name: /Marksy/ })).toBeNull();
    await userEvent.selectOptions(within(dialog).getByLabelText("Merge into"), "1");
    const merge = within(dialog).getByRole("button", { name: "Merge permanently" });
    expect(merge).toBeDisabled();
    await userEvent.click(within(dialog).getByRole("checkbox", { name: /cannot be undone/ }));
    await userEvent.click(merge);

    expect(await screen.findByRole("status")).toHaveTextContent("1 retired as duplicate");
    expect(body).toEqual({ intoChannelId: 1 });
  });

  it("offers no edit, alias or merge action on Marksy's own channel", async () => {
    server.use(http.get(`${BASE}/admin/channels`, () => ok(CHANNELS)));
    seedAuth();
    renderApp(<ChannelsPage />);

    const row = (await screen.findByText("Marksy")).closest("tr")!;
    expect(within(row).queryByRole("button")).toBeNull();
    expect(within(row).getByText("read-only")).toBeInTheDocument();
  });

  it("adds an alias with its medium and shows the server's refusal of a masked label", async () => {
    const bodies: unknown[] = [];
    server.use(
      http.get(`${BASE}/admin/channels`, () => ok(CHANNELS)),
      http.post(`${BASE}/admin/channels/2/aliases`, async ({ request }) => {
        const body = (await request.json()) as { alias: string };
        bodies.push(body);
        if (body.alias.includes("98765")) {
          return fail(422, "MRA_VALIDATION_FAILED", "'[phone]' is only masked personal data, not a channel");
        }
        return ok({ ...LITE, aliases: [{ id: 9, alias: "stocktips", kind: "LABEL", scope: "WHATSAPP" }] });
      }),
    );
    seedAuth();
    renderApp(<ChannelsPage />);

    const dialog = await openRow("Upstox Lite", "Aliases");
    await userEvent.type(within(dialog).getByLabelText("Alias"), "+91 98765 43210");
    await userEvent.selectOptions(within(dialog).getByLabelText("Medium"), "WHATSAPP");
    await userEvent.click(within(dialog).getByRole("button", { name: "Add alias" }));
    expect(await within(dialog).findByRole("alert")).toHaveTextContent("only masked personal data");

    await userEvent.clear(within(dialog).getByLabelText("Alias"));
    await userEvent.type(within(dialog).getByLabelText("Alias"), "StockTips");
    await userEvent.click(within(dialog).getByRole("button", { name: "Add alias" }));

    expect(await within(dialog).findByText("stocktips")).toBeInTheDocument();
    expect(bodies).toEqual([
      { alias: "+91 98765 43210", scope: "WHATSAPP", kind: "LABEL" },
      { alias: "StockTips", scope: "WHATSAPP", kind: "LABEL" },
    ]);
  });
});
```

- [ ] **Step 2: Run them to verify they fail**

Controller: ask the user to run `! npm test -- src/pages/ChannelsPage.test.tsx` in `C:\AIAgent\admin-app-phase5`.
Expected: the file fails to load with `Failed to resolve import "./ChannelsPage"`.

- [ ] **Step 3: Create the page**

Create `src/pages/ChannelsPage.tsx`:

```tsx
import { useMemo, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { channelsApi } from "../api/tipLedger";
import { TIP_MEDIA } from "../api/types";
import type { AdminChannelView, AliasKind, ChannelAliasView, ChannelMergeView, TipMedium } from "../api/types";
import type { Column } from "../components/DataTable";
import { DataTable } from "../components/DataTable";
import { Modal } from "../components/Modal";
import { Toolbar } from "../components/Toolbar";

const MARKSY = "MARKSY";

function errorText(error: unknown): string | null {
  if (!error) return null;
  return error instanceof Error ? error.message : "Request failed.";
}

function plural(n: number): string {
  return n === 1 ? "" : "s";
}

// Tip-ledger channels (spec §9): rename, default horizon, capture, scoped aliases, irreversible merges.
export function ChannelsPage() {
  const qc = useQueryClient();
  const channelsQuery = useQuery({ queryKey: ["channels"], queryFn: channelsApi.list });
  const [search, setSearch] = useState("");
  const [type, setType] = useState("");
  const [editing, setEditing] = useState<AdminChannelView | null>(null);
  const [aliasing, setAliasing] = useState<AdminChannelView | null>(null);
  const [merging, setMerging] = useState<AdminChannelView | null>(null);

  const types = useMemo(
    () => [...new Set((channelsQuery.data ?? []).map((c) => c.type))].sort(),
    [channelsQuery.data],
  );
  const filtered = useMemo(() => {
    const q = search.trim().toLowerCase();
    return (channelsQuery.data ?? []).filter(
      (c) =>
        (!type || c.type === type) &&
        (!q || c.name.toLowerCase().includes(q) || c.aliases.some((a) => a.alias.includes(q))),
    );
  }, [channelsQuery.data, search, type]);

  // A merge re-keys tips and retires duplicates, so every ledger view is stale afterwards.
  const invalidate = () => {
    qc.invalidateQueries({ queryKey: ["channels"] });
    qc.invalidateQueries({ queryKey: ["callers"] });
    qc.invalidateQueries({ queryKey: ["tip-ledger"] });
  };

  const columns: Column<AdminChannelView>[] = [
    {
      key: "name",
      header: "Channel",
      sortValue: (c) => c.name,
      render: (c) => (
        <>
          <strong>{c.name}</strong>
          {c.canonicalChannelId !== c.id && <div className="muted">merged into {c.canonicalChannelName}</div>}
        </>
      ),
    },
    {
      key: "type",
      header: "Type",
      sortValue: (c) => c.type,
      render: (c) => <span className="badge badge-muted">{c.type}</span>,
    },
    {
      key: "aliases",
      header: "Aliases",
      render: (c) =>
        c.aliases.length === 0 ? (
          <span className="muted">—</span>
        ) : (
          c.aliases.map((a) => (
            <span key={a.id} className="chip">
              {a.scope} · {a.alias}
            </span>
          ))
        ),
    },
    {
      key: "horizon",
      header: "Default horizon",
      className: "nowrap",
      sortValue: (c) => c.defaultHorizonSessions,
      render: (c) => `${c.defaultHorizonSessions} sessions`,
    },
    {
      key: "capture",
      header: "Capture",
      sortValue: (c) => (c.captureEnabled ? 1 : 0),
      render: (c) =>
        c.captureEnabled ? <span className="badge badge-ok">on</span> : <span className="badge badge-muted">off</span>,
    },
    { key: "tips", header: "Tips", sortValue: (c) => c.tipCount, render: (c) => c.tipCount },
    {
      key: "actions",
      header: "",
      align: "right",
      render: (c) =>
        c.type === MARKSY ? (
          <span className="badge badge-muted">read-only</span>
        ) : (
          <>
            <button className="btn btn-sm" onClick={() => setEditing(c)}>
              Edit
            </button>{" "}
            <button className="btn btn-sm" onClick={() => setAliasing(c)}>
              Aliases
            </button>{" "}
            {c.canonicalChannelId === c.id && (
              <button className="btn btn-sm btn-danger" onClick={() => setMerging(c)}>
                Merge
              </button>
            )}
          </>
        ),
    },
  ];

  return (
    <>
      <Toolbar
        search={search}
        onSearch={setSearch}
        searchPlaceholder="Search channels or aliases…"
        filters={
          <select
            className="select"
            style={{ maxWidth: 200 }}
            aria-label="Channel type"
            value={type}
            onChange={(e) => setType(e.target.value)}
          >
            <option value="">All types</option>
            {types.map((t) => (
              <option key={t} value={t}>
                {t}
              </option>
            ))}
          </select>
        }
      />
      <DataTable
        columns={columns}
        rows={filtered}
        getRowKey={(c) => c.id}
        isLoading={channelsQuery.isLoading}
        error={channelsQuery.error}
        emptyLabel="No channels yet. They appear as receipts arrive."
        isFiltered={search.trim().length > 0 || type !== ""}
        noMatchLabel="No channels match."
      />

      {editing && <EditChannelModal channel={editing} onDone={invalidate} onClose={() => setEditing(null)} />}
      {aliasing && <AliasesModal channel={aliasing} onDone={invalidate} onClose={() => setAliasing(null)} />}
      {merging && (
        <MergeChannelModal
          channel={merging}
          channels={channelsQuery.data ?? []}
          onDone={invalidate}
          onClose={() => setMerging(null)}
        />
      )}
    </>
  );
}

function EditChannelModal({
  channel,
  onDone,
  onClose,
}: {
  channel: AdminChannelView;
  onDone: () => void;
  onClose: () => void;
}) {
  const [name, setName] = useState(channel.name);
  const [horizon, setHorizon] = useState(String(channel.defaultHorizonSessions));
  const [capture, setCapture] = useState(channel.captureEnabled);
  const horizonValue = Number(horizon);
  const horizonValid = horizon.trim() !== "" && Number.isInteger(horizonValue) && horizonValue >= 0 && horizonValue <= 250;
  const mutation = useMutation({
    mutationFn: () =>
      channelsApi.update(channel.id, { name: name.trim(), defaultHorizonSessions: horizonValue, captureEnabled: capture }),
    onSuccess: () => {
      onDone();
      onClose();
    },
  });

  return (
    <Modal
      title={`Edit ${channel.name}`}
      onClose={onClose}
      footer={
        <>
          <button className="btn" onClick={onClose}>
            Cancel
          </button>
          <button
            className="btn btn-primary"
            disabled={!name.trim() || !horizonValid || mutation.isPending}
            onClick={() => mutation.mutate()}
          >
            {mutation.isPending ? "Saving…" : "Save"}
          </button>
        </>
      }
    >
      <div className="field">
        <label htmlFor="channel-name">Name</label>
        <input id="channel-name" className="input" value={name} onChange={(e) => setName(e.target.value)} />
      </div>
      <div className="field">
        <label htmlFor="channel-horizon">Default horizon (sessions)</label>
        <input
          id="channel-horizon"
          className="input"
          type="number"
          min={0}
          max={250}
          value={horizon}
          onChange={(e) => setHorizon(e.target.value)}
        />
      </div>
      <p className="muted" style={{ marginTop: 0 }}>
        Applies to tips first seen from now on. Existing tips keep their horizon.
      </p>
      <label style={{ display: "flex", alignItems: "center", gap: "var(--space-2)" }}>
        <input type="checkbox" checked={capture} onChange={(e) => setCapture(e.target.checked)} />
        Phones capture this app's notifications
      </label>
      {errorText(mutation.error) && (
        <p className="error-text" role="alert">
          {errorText(mutation.error)}
        </p>
      )}
    </Modal>
  );
}

function AliasesModal({
  channel: initial,
  onDone,
  onClose,
}: {
  channel: AdminChannelView;
  onDone: () => void;
  onClose: () => void;
}) {
  const [channel, setChannel] = useState(initial);
  const [editingId, setEditingId] = useState<number | null>(null);
  const [alias, setAlias] = useState("");
  const [scope, setScope] = useState<TipMedium>("APP_NOTIFICATION");
  const [kind, setKind] = useState<AliasKind>("LABEL");

  const reset = () => {
    setEditingId(null);
    setAlias("");
    setScope("APP_NOTIFICATION");
    setKind("LABEL");
  };
  const applied = (updated: AdminChannelView) => {
    setChannel(updated);
    reset();
    onDone();
  };
  const save = useMutation({
    mutationFn: () =>
      editingId === null
        ? channelsApi.addAlias(channel.id, { alias: alias.trim(), scope, kind })
        : channelsApi.updateAlias(channel.id, editingId, { alias: alias.trim(), scope, kind }),
    onSuccess: applied,
  });
  const remove = useMutation({
    mutationFn: (aliasId: number) => channelsApi.removeAlias(channel.id, aliasId),
    onSuccess: applied,
  });
  const edit = (a: ChannelAliasView) => {
    setEditingId(a.id);
    setAlias(a.alias);
    setScope(a.scope);
    setKind(a.kind);
  };
  // A package names an app, so it exists only for app notifications.
  const onScope = (value: TipMedium) => {
    setScope(value);
    if (value !== "APP_NOTIFICATION") setKind("LABEL");
  };
  const error = errorText(save.error ?? remove.error);

  return (
    <Modal
      title={`Aliases of ${channel.name}`}
      wide
      onClose={onClose}
      footer={
        <button className="btn" onClick={onClose}>
          Done
        </button>
      }
    >
      {channel.aliases.length === 0 ? (
        <p className="muted" style={{ marginTop: 0 }}>
          No aliases yet.
        </p>
      ) : (
        <div className="table-scroll">
          <table className="table">
            <thead>
              <tr>
                <th>Medium</th>
                <th>Kind</th>
                <th>Alias</th>
                <th className="actions" />
              </tr>
            </thead>
            <tbody>
              {channel.aliases.map((a) => (
                <tr key={a.id}>
                  <td>{a.scope}</td>
                  <td>{a.kind}</td>
                  <td className="mono">{a.alias}</td>
                  <td className="actions">
                    <button className="btn btn-sm" onClick={() => edit(a)}>
                      Edit
                    </button>{" "}
                    <button
                      className="btn btn-sm btn-danger"
                      disabled={remove.isPending}
                      onClick={() => remove.mutate(a.id)}
                    >
                      Remove
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      <h3 className="section-title">{editingId === null ? "New alias" : "Edit alias"}</h3>
      <div className="field">
        <label htmlFor="alias-text">Alias</label>
        <input id="alias-text" className="input mono" value={alias} onChange={(e) => setAlias(e.target.value)} />
      </div>
      <div className="field">
        <label htmlFor="alias-scope">Medium</label>
        <select
          id="alias-scope"
          className="select"
          value={scope}
          onChange={(e) => onScope(e.target.value as TipMedium)}
        >
          {TIP_MEDIA.map((m) => (
            <option key={m} value={m}>
              {m}
            </option>
          ))}
        </select>
      </div>
      <div className="field">
        <label htmlFor="alias-kind">Kind</label>
        <select id="alias-kind" className="select" value={kind} onChange={(e) => setKind(e.target.value as AliasKind)}>
          <option value="LABEL">LABEL</option>
          <option value="PACKAGE" disabled={scope !== "APP_NOTIFICATION"}>
            PACKAGE
          </option>
        </select>
      </div>
      <div style={{ display: "flex", gap: "var(--space-3)" }}>
        <button
          className="btn btn-primary btn-sm"
          disabled={!alias.trim() || save.isPending}
          onClick={() => save.mutate()}
        >
          {editingId === null ? "Add alias" : "Save alias"}
        </button>
        {editingId !== null && (
          <button className="btn btn-sm" onClick={reset}>
            Cancel edit
          </button>
        )}
      </div>
      {error && (
        <p className="error-text" role="alert">
          {error}
        </p>
      )}
    </Modal>
  );
}

function MergeChannelModal({
  channel,
  channels,
  onDone,
  onClose,
}: {
  channel: AdminChannelView;
  channels: AdminChannelView[];
  onDone: () => void;
  onClose: () => void;
}) {
  const [intoId, setIntoId] = useState("");
  const [confirmed, setConfirmed] = useState(false);
  const [result, setResult] = useState<ChannelMergeView | null>(null);
  // Other groups' canonical channels only; Marksy's own channel is never merged.
  const targets = channels.filter(
    (c) => c.id === c.canonicalChannelId && c.canonicalChannelId !== channel.canonicalChannelId && c.type !== MARKSY,
  );
  const mutation = useMutation({
    mutationFn: () => channelsApi.merge(channel.id, Number(intoId)),
    onSuccess: (merged) => {
      setResult(merged);
      onDone();
    },
  });

  if (result) {
    const into = channels.find((c) => c.id === result.intoChannelId)?.name ?? `#${result.intoChannelId}`;
    return (
      <Modal
        title="Channels merged"
        onClose={onClose}
        footer={
          <button className="btn btn-primary" onClick={onClose}>
            Done
          </button>
        }
      >
        <p role="status" style={{ margin: 0 }}>
          {result.alreadyMerged
            ? `${channel.name} was already part of ${into}.`
            : `${channel.name} now rolls up into ${into}: ${result.tipsRekeyed} tip${plural(result.tipsRekeyed)} re-keyed, ` +
              `${result.tipsMerged.length} retired as duplicate${plural(result.tipsMerged.length)}, ` +
              `${result.receiptsMoved} receipt${plural(result.receiptsMoved)} moved.`}
        </p>
      </Modal>
    );
  }

  return (
    <Modal
      title={`Merge ${channel.name}`}
      onClose={onClose}
      footer={
        <>
          <button className="btn" onClick={onClose}>
            Cancel
          </button>
          <button
            className="btn btn-danger"
            disabled={!intoId || !confirmed || mutation.isPending}
            onClick={() => mutation.mutate()}
          >
            {mutation.isPending ? "Merging…" : "Merge permanently"}
          </button>
        </>
      }
    >
      <div className="field">
        <label htmlFor="merge-into">Merge into</label>
        <select id="merge-into" className="select" value={intoId} onChange={(e) => setIntoId(e.target.value)}>
          <option value="">Choose a channel…</option>
          {targets.map((c) => (
            <option key={c.id} value={c.id}>
              {c.name} ({c.type})
            </option>
          ))}
        </select>
      </div>
      <p className="muted">
        Tips keep the channel they arrived on. Open tips are re-keyed to the chosen channel. Where two become the same
        call, the later one is retired as a duplicate and its receipts move to the earlier one.
      </p>
      <label style={{ display: "flex", alignItems: "center", gap: "var(--space-2)" }}>
        <input type="checkbox" checked={confirmed} onChange={(e) => setConfirmed(e.target.checked)} />I understand a
        merge cannot be undone
      </label>
      {errorText(mutation.error) && (
        <p className="error-text" role="alert">
          {errorText(mutation.error)}
        </p>
      )}
    </Modal>
  );
}
```

- [ ] **Step 4: Add the route and the nav entry**

In `src/App.tsx`:
- below `import { TipsIngestPage } from "./pages/TipsIngestPage";`, add `import { ChannelsPage } from "./pages/ChannelsPage";`;
- below `<Route path="/tips" element={<TipsIngestPage />} />`, add `<Route path="/channels" element={<ChannelsPage />} />`.

In `src/components/Layout.tsx`, add this entry to `NAV` directly after the `to: "/tips"` entry:

```tsx
  {
    to: "/channels",
    label: "Channels",
    icon: icon(
      <>
        <path d="M4 11a9 9 0 0 1 9 9" />
        <path d="M4 4a16 16 0 0 1 16 16" />
        <circle cx="5" cy="19" r="1" />
      </>,
    ),
  },
```

- [ ] **Step 5: Run the tests to verify they pass**

Controller: ask the user to run `! npm test -- src/pages/ChannelsPage.test.tsx` and `! npm run typecheck` in `C:\AIAgent\admin-app-phase5`.
Expected: 3 PASS, and the typecheck is clean.

- [ ] **Step 6: Commit**

```bash
git add src/pages/ChannelsPage.tsx src/pages/ChannelsPage.test.tsx src/App.tsx src/components/Layout.tsx
git commit -m "Admin: Channels page with scoped aliases, default horizon and a confirmed, irreversible merge

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task B3: Callers page — rename and merge within one canonical channel

**Files:**
- Create: `src/pages/CallersPage.tsx`
- Modify: `src/App.tsx` (route `/callers`), `src/components/Layout.tsx` (a "Callers" entry after "Channels")
- Test: `src/pages/CallersPage.test.tsx`

**Interfaces:**
- `CallersPage` reads `["callers", { channelId: null }]`. After a mutation it invalidates `["callers"]` and `["tip-ledger"]`.
- Merge targets are callers with the same `canonicalChannelId` that are their own canonical and sit in a different caller group. The same "cannot be undone" gate applies as for channels.

- [ ] **Step 1: Write the failing test**

Create `src/pages/CallersPage.test.tsx`:

```tsx
import { describe, expect, it } from "vitest";
import { screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { server } from "../test/server";
import { renderApp, seedAuth } from "../test/render";
import type { AdminCallerView } from "../api/types";
import { CallersPage } from "./CallersPage";

const BASE = "http://localhost/api/v1";

function ok(data: unknown) {
  return HttpResponse.json({ data, meta: { requestId: "t", timestamp: new Date().toISOString() } });
}

function caller(overrides: Partial<AdminCallerView>): AdminCallerView {
  return {
    id: 1,
    name: "Rahul S",
    channelId: 1,
    channelName: "Upstox",
    channelType: "BROKER_APP",
    canonicalChannelId: 1,
    canonicalCallerId: 1,
    canonicalCallerName: "Rahul S",
    tipCount: 2,
    createdAt: null,
    ...overrides,
  };
}

describe("CallersPage", () => {
  it("offers merge targets only from the same canonical channel", async () => {
    server.use(
      http.get(`${BASE}/admin/callers`, () =>
        ok([
          caller({}),
          // Upstox Lite was merged into Upstox, so its callers share Upstox's canonical channel.
          caller({ id: 2, name: "Rahul Sharma", channelId: 2, channelName: "Upstox Lite", canonicalCallerId: 2, canonicalCallerName: "Rahul Sharma" }),
          caller({ id: 3, name: "Priya", channelId: 4, channelName: "Zerodha", canonicalChannelId: 4, canonicalCallerId: 3, canonicalCallerName: "Priya" }),
          caller({ id: 4, name: "Rahul", canonicalCallerId: 2, canonicalCallerName: "Rahul Sharma" }),
        ]),
      ),
    );
    seedAuth();
    renderApp(<CallersPage />);

    const row = (await screen.findByText("Rahul S")).closest("tr")!;
    await userEvent.click(within(row).getByRole("button", { name: "Merge" }));
    const dialog = screen.getByRole("dialog");
    const options = within(within(dialog).getByLabelText("Merge into"))
      .getAllByRole("option")
      .map((o) => o.textContent);

    expect(options).toEqual(["Choose a caller…", "Rahul Sharma (Upstox Lite)"]);
  });
});
```

- [ ] **Step 2: Run it to verify it fails**

Controller: ask the user to run `! npm test -- src/pages/CallersPage.test.tsx` in `C:\AIAgent\admin-app-phase5`.
Expected: the file fails to load with `Failed to resolve import "./CallersPage"`.

- [ ] **Step 3: Create the page**

Create `src/pages/CallersPage.tsx`:

```tsx
import { useMemo, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { callersApi } from "../api/tipLedger";
import type { AdminCallerView, CallerMergeView } from "../api/types";
import type { Column } from "../components/DataTable";
import { DataTable } from "../components/DataTable";
import { Modal } from "../components/Modal";
import { Toolbar } from "../components/Toolbar";

function errorText(error: unknown): string | null {
  if (!error) return null;
  return error instanceof Error ? error.message : "Request failed.";
}

// Callers inside channels (spec §9). Merging never re-keys a tip: the caller is not part of a call's key.
export function CallersPage() {
  const qc = useQueryClient();
  const callersQuery = useQuery({ queryKey: ["callers", { channelId: null }], queryFn: () => callersApi.list() });
  const [search, setSearch] = useState("");
  const [channel, setChannel] = useState("");
  const [renaming, setRenaming] = useState<AdminCallerView | null>(null);
  const [merging, setMerging] = useState<AdminCallerView | null>(null);

  const channelNames = useMemo(
    () => [...new Set((callersQuery.data ?? []).map((c) => c.channelName))].sort(),
    [callersQuery.data],
  );
  const filtered = useMemo(() => {
    const q = search.trim().toLowerCase();
    return (callersQuery.data ?? []).filter(
      (c) => (!channel || c.channelName === channel) && (!q || c.name.toLowerCase().includes(q)),
    );
  }, [callersQuery.data, search, channel]);

  const invalidate = () => {
    qc.invalidateQueries({ queryKey: ["callers"] });
    qc.invalidateQueries({ queryKey: ["tip-ledger"] });
  };

  const columns: Column<AdminCallerView>[] = [
    {
      key: "name",
      header: "Caller",
      sortValue: (c) => c.name,
      render: (c) => (
        <>
          <strong>{c.name}</strong>
          {c.canonicalCallerId !== c.id && <div className="muted">counts as {c.canonicalCallerName}</div>}
        </>
      ),
    },
    { key: "channel", header: "Channel", sortValue: (c) => c.channelName, render: (c) => c.channelName },
    { key: "tips", header: "Tips", sortValue: (c) => c.tipCount, render: (c) => c.tipCount },
    {
      key: "actions",
      header: "",
      align: "right",
      render: (c) =>
        c.channelType === "MARKSY" ? (
          <span className="badge badge-muted">read-only</span>
        ) : (
          <>
            <button className="btn btn-sm" onClick={() => setRenaming(c)}>
              Rename
            </button>{" "}
            {c.canonicalCallerId === c.id && (
              <button className="btn btn-sm btn-danger" onClick={() => setMerging(c)}>
                Merge
              </button>
            )}
          </>
        ),
    },
  ];

  return (
    <>
      <Toolbar
        search={search}
        onSearch={setSearch}
        searchPlaceholder="Search callers…"
        filters={
          <select
            className="select"
            style={{ maxWidth: 200 }}
            aria-label="Channel"
            value={channel}
            onChange={(e) => setChannel(e.target.value)}
          >
            <option value="">All channels</option>
            {channelNames.map((name) => (
              <option key={name} value={name}>
                {name}
              </option>
            ))}
          </select>
        }
      />
      <DataTable
        columns={columns}
        rows={filtered}
        getRowKey={(c) => c.id}
        isLoading={callersQuery.isLoading}
        error={callersQuery.error}
        emptyLabel="No callers yet. They appear when a parsed tip names one."
        isFiltered={search.trim().length > 0 || channel !== ""}
        noMatchLabel="No callers match."
      />

      {renaming && <RenameCallerModal caller={renaming} onDone={invalidate} onClose={() => setRenaming(null)} />}
      {merging && (
        <MergeCallerModal
          caller={merging}
          callers={callersQuery.data ?? []}
          onDone={invalidate}
          onClose={() => setMerging(null)}
        />
      )}
    </>
  );
}

function RenameCallerModal({
  caller,
  onDone,
  onClose,
}: {
  caller: AdminCallerView;
  onDone: () => void;
  onClose: () => void;
}) {
  const [name, setName] = useState(caller.name);
  const mutation = useMutation({
    mutationFn: () => callersApi.rename(caller.id, name.trim()),
    onSuccess: () => {
      onDone();
      onClose();
    },
  });

  return (
    <Modal
      title={`Rename ${caller.name}`}
      onClose={onClose}
      footer={
        <>
          <button className="btn" onClick={onClose}>
            Cancel
          </button>
          <button
            className="btn btn-primary"
            disabled={!name.trim() || mutation.isPending}
            onClick={() => mutation.mutate()}
          >
            {mutation.isPending ? "Saving…" : "Save"}
          </button>
        </>
      }
    >
      <div className="field">
        <label htmlFor="caller-name">Name</label>
        <input id="caller-name" className="input" value={name} onChange={(e) => setName(e.target.value)} />
      </div>
      <p className="muted" style={{ marginTop: 0 }}>
        Later tips that name the old spelling create a new caller, which you can then merge.
      </p>
      {errorText(mutation.error) && (
        <p className="error-text" role="alert">
          {errorText(mutation.error)}
        </p>
      )}
    </Modal>
  );
}

function MergeCallerModal({
  caller,
  callers,
  onDone,
  onClose,
}: {
  caller: AdminCallerView;
  callers: AdminCallerView[];
  onDone: () => void;
  onClose: () => void;
}) {
  const [intoId, setIntoId] = useState("");
  const [confirmed, setConfirmed] = useState(false);
  const [result, setResult] = useState<CallerMergeView | null>(null);
  // A caller belongs to a channel, so merges stay inside one canonical channel.
  const targets = callers.filter(
    (c) =>
      c.canonicalChannelId === caller.canonicalChannelId &&
      c.id === c.canonicalCallerId &&
      c.canonicalCallerId !== caller.canonicalCallerId,
  );
  const mutation = useMutation({
    mutationFn: () => callersApi.merge(caller.id, Number(intoId)),
    onSuccess: (merged) => {
      setResult(merged);
      onDone();
    },
  });

  if (result) {
    const into = callers.find((c) => c.id === result.intoCallerId)?.name ?? `#${result.intoCallerId}`;
    return (
      <Modal
        title="Callers merged"
        onClose={onClose}
        footer={
          <button className="btn btn-primary" onClick={onClose}>
            Done
          </button>
        }
      >
        <p role="status" style={{ margin: 0 }}>
          {result.alreadyMerged ? `${caller.name} already counts as ${into}.` : `${caller.name} now counts as ${into}.`}
        </p>
      </Modal>
    );
  }

  return (
    <Modal
      title={`Merge ${caller.name}`}
      onClose={onClose}
      footer={
        <>
          <button className="btn" onClick={onClose}>
            Cancel
          </button>
          <button
            className="btn btn-danger"
            disabled={!intoId || !confirmed || mutation.isPending}
            onClick={() => mutation.mutate()}
          >
            {mutation.isPending ? "Merging…" : "Merge permanently"}
          </button>
        </>
      }
    >
      <div className="field">
        <label htmlFor="merge-caller-into">Merge into</label>
        <select id="merge-caller-into" className="select" value={intoId} onChange={(e) => setIntoId(e.target.value)}>
          <option value="">Choose a caller…</option>
          {targets.map((c) => (
            <option key={c.id} value={c.id}>
              {c.name} ({c.channelName})
            </option>
          ))}
        </select>
      </div>
      <p className="muted">Tips keep the caller they named; scorecards count them under the chosen caller.</p>
      <label style={{ display: "flex", alignItems: "center", gap: "var(--space-2)" }}>
        <input type="checkbox" checked={confirmed} onChange={(e) => setConfirmed(e.target.checked)} />I understand a
        merge cannot be undone
      </label>
      {errorText(mutation.error) && (
        <p className="error-text" role="alert">
          {errorText(mutation.error)}
        </p>
      )}
    </Modal>
  );
}
```

- [ ] **Step 4: Add the route and the nav entry**

In `src/App.tsx`:
- below the `ChannelsPage` import, add `import { CallersPage } from "./pages/CallersPage";`;
- below the `/channels` route, add `<Route path="/callers" element={<CallersPage />} />`.

In `src/components/Layout.tsx`, add this entry to `NAV` directly after the `/channels` entry:

```tsx
  {
    to: "/callers",
    label: "Callers",
    icon: icon(
      <>
        <circle cx="12" cy="8" r="4" />
        <path d="M4 21v-1a6 6 0 0 1 6-6h4a6 6 0 0 1 6 6v1" />
      </>,
    ),
  },
```

- [ ] **Step 5: Run the tests to verify they pass**

Controller: ask the user to run `! npm test -- src/pages/CallersPage.test.tsx` and `! npm run typecheck` in `C:\AIAgent\admin-app-phase5`.
Expected: 1 PASS, and the typecheck is clean.

- [ ] **Step 6: Commit**

```bash
git add src/pages/CallersPage.tsx src/pages/CallersPage.test.tsx src/App.tsx src/components/Layout.tsx
git commit -m "Admin: Callers page with rename and a merge limited to one canonical channel

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task B4: Tip Ledger page with the receipts drawer, and customer → tips

**Files:**
- Create: `src/pages/TipLedgerPage.tsx` (exports `TipLedgerPage`, `TipFiltersBar` and `TipsTable`), `src/pages/CustomerTipsPage.tsx`
- Modify: `src/App.tsx` (routes `/tip-ledger` and `/users/:userId/tips`), `src/components/Layout.tsx` (a "Tip Ledger" entry after "Callers"), `src/pages/UsersPage.tsx` (a "Tips" button per row)
- Test: `src/pages/TipLedgerPage.test.tsx`

**Interfaces:**
- `TipFiltersBar({ filters, onChange, showCustomer = true })`:
  - the Toolbar search is the symbol;
  - selects: Status, Channel (canonical roots) and, once a channel is chosen, Caller (its canonical callers);
  - inputs: Customer (hidden when `showCustomer` is false), From and To dates.
  - It reads `["channels"]` and `["callers", { channelId }]`.
- `TipsTable({ rows, isLoading, error, hasMore, loadingMore, onLoadMore, isFiltered })`:
  - clicking a row opens the receipts drawer (the wide `Modal`), which reads `["tip-ledger", "receipts", tipId]`;
  - each customer id links to `/users/:userId/tips`.
- `TipLedgerPage` reads `useInfiniteQuery(["tip-ledger", "list", filters])` through `ledgerApi.tips`.
- `CustomerTipsPage` (route `/users/:userId/tips`) reads `["tip-ledger", "customer", userId, filters]` through `ledgerApi.customerTips`.

- [ ] **Step 1: Write the failing tests**

Create `src/pages/TipLedgerPage.test.tsx`:

```tsx
import { describe, expect, it } from "vitest";
import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { server } from "../test/server";
import { renderApp, seedAuth } from "../test/render";
import type { AdminChannelView, AdminReceiptView, AdminTipView } from "../api/types";
import { TipLedgerPage } from "./TipLedgerPage";

const BASE = "http://localhost/api/v1";

function ok(data: unknown, meta: Record<string, unknown> = {}) {
  return HttpResponse.json({ data, meta: { requestId: "t", timestamp: new Date().toISOString(), ...meta } });
}

function tip(overrides: Partial<AdminTipView>): AdminTipView {
  return {
    tipId: "t-1",
    symbol: "RENUKA",
    direction: "BUY",
    entryLow: "23.620000",
    entryHigh: "23.620000",
    entryBasis: "STATED",
    target: "26.000000",
    stopLoss: "22.250000",
    horizonSessions: 20,
    horizonBasis: "CHANNEL_DEFAULT",
    firstSeenAt: "2026-09-28T05:00:00Z",
    status: "ACTIVE",
    entryStatus: "WAITING",
    outcome: null,
    reason: null,
    closedAt: null,
    exitPrice: null,
    promisedReturn: null,
    actualReturn: null,
    channelId: 1,
    channelName: "Upstox",
    canonicalChannelId: 1,
    canonicalChannelName: "Upstox",
    callerId: null,
    callerName: null,
    predictionId: null,
    mergedIntoTipId: null,
    revisesTipId: null,
    receiptCount: 2,
    ...overrides,
  };
}

function receipt(overrides: Partial<AdminReceiptView>): AdminReceiptView {
  return {
    receiptId: "r-1",
    tipId: "t-1",
    userId: "user-1",
    kind: "TIP",
    medium: "APP_NOTIFICATION",
    appPackage: "com.upstox.pro",
    channelLabel: "Upstox",
    text: "BUY RENUKA CMP 23.62 SL 22.25 TGT 26",
    devicePostedAt: null,
    recordedAt: "2026-09-28T05:00:00Z",
    parserVersion: "TCP-001",
    ...overrides,
  };
}

const UPSTOX: AdminChannelView = {
  id: 1,
  name: "Upstox",
  type: "BROKER_APP",
  canonicalChannelId: 1,
  canonicalChannelName: "Upstox",
  defaultHorizonSessions: 20,
  captureEnabled: true,
  aliases: [],
  tipCount: 2,
  createdAt: null,
};

describe("TipLedgerPage", () => {
  it("sends the filters to the server and loads the next page with its cursor", async () => {
    const urls: URL[] = [];
    server.use(
      http.get(`${BASE}/admin/channels`, () => ok([UPSTOX])),
      http.get(`${BASE}/admin/callers`, () => ok([])),
      http.get(`${BASE}/admin/tips`, ({ request }) => {
        const url = new URL(request.url);
        urls.push(url);
        return url.searchParams.get("cursor") === "c2"
          ? ok([tip({ tipId: "t-3", symbol: "TCS" })], { pageSize: 100, nextCursor: null })
          : ok([tip({}), tip({ tipId: "t-2", symbol: "RELIANCE" })], { pageSize: 100, nextCursor: "c2" });
      }),
    );
    seedAuth();
    renderApp(<TipLedgerPage />);

    await screen.findByText("RELIANCE");
    await screen.findByRole("option", { name: "Upstox" });
    await userEvent.selectOptions(screen.getByLabelText("Status"), "ACTIVE");
    await userEvent.selectOptions(screen.getByLabelText("Channel"), "1");
    await userEvent.type(screen.getByLabelText("Customer"), "user-1");

    await waitFor(() => expect(urls[urls.length - 1].searchParams.get("userId")).toBe("user-1"));
    expect(Object.fromEntries(urls[urls.length - 1].searchParams)).toEqual({
      status: "ACTIVE",
      channelId: "1",
      userId: "user-1",
      pageSize: "100",
    });
    await userEvent.click(await screen.findByRole("button", { name: "Load more" }));

    expect(await screen.findByText("TCS")).toBeInTheDocument();
    const last = urls[urls.length - 1];
    expect([last.searchParams.get("cursor"), last.searchParams.get("userId")]).toEqual(["c2", "user-1"]);
  });

  it("opens the receipts drawer with each customer's id and medium", async () => {
    server.use(
      http.get(`${BASE}/admin/channels`, () => ok([UPSTOX])),
      http.get(`${BASE}/admin/tips`, () => ok([tip({})], { pageSize: 100, nextCursor: null })),
      http.get(`${BASE}/admin/tips/t-1/receipts`, () =>
        ok([
          receipt({}),
          receipt({ receiptId: "r-2", userId: "user-2", medium: "WHATSAPP", appPackage: null, channelLabel: "stocktips" }),
        ]),
      ),
    );
    seedAuth();
    renderApp(<TipLedgerPage />);

    await userEvent.click(await screen.findByText("RENUKA"));
    const dialog = await screen.findByRole("dialog");

    expect(await within(dialog).findByRole("link", { name: "user-2" })).toHaveAttribute("href", "/users/user-2/tips");
    expect(within(dialog).getByText("WHATSAPP")).toBeInTheDocument();
  });
});
```

- [ ] **Step 2: Run them to verify they fail**

Controller: ask the user to run `! npm test -- src/pages/TipLedgerPage.test.tsx` in `C:\AIAgent\admin-app-phase5`.
Expected: the file fails to load with `Failed to resolve import "./TipLedgerPage"`.

- [ ] **Step 3: Create the Tip Ledger page**

Create `src/pages/TipLedgerPage.tsx`:

```tsx
import { useState } from "react";
import { Link } from "react-router-dom";
import { useInfiniteQuery, useQuery } from "@tanstack/react-query";
import { callersApi, channelsApi, ledgerApi } from "../api/tipLedger";
import { TIP_STATUSES } from "../api/types";
import type { AdminReceiptView, AdminTipView, TipFilters } from "../api/types";
import type { Column } from "../components/DataTable";
import { DataTable } from "../components/DataTable";
import { Modal } from "../components/Modal";
import { Toolbar } from "../components/Toolbar";
import { formatDateTime } from "../lib/format";

function price(value: string | null): string {
  return value === null ? "—" : String(Number(value));
}

function entry(tip: AdminTipView): string {
  if (tip.entryLow === null) return "at first seen";
  const low = price(tip.entryLow);
  const high = price(tip.entryHigh);
  const range = low === high ? low : `${low}–${high}`;
  return tip.entryBasis === "FIRST_SEEN_PRICE" ? `${range} (first seen)` : range;
}

function percent(value: string | null): string {
  return value === null ? "—" : `${(Number(value) * 100).toFixed(2)}%`;
}

function hasFilters(filters: TipFilters): boolean {
  return Object.values(filters).some((value) => value !== undefined && value !== null && value !== "");
}

// The canonical tips of the ledger (spec §9 /admin/tips), Marksy's included; each receipt names its customer.
export function TipLedgerPage() {
  const [filters, setFilters] = useState<TipFilters>({});
  const tips = useInfiniteQuery({
    queryKey: ["tip-ledger", "list", filters],
    queryFn: ({ pageParam }) => ledgerApi.tips(filters, pageParam),
    initialPageParam: null as string | null,
    getNextPageParam: (last) => last.nextCursor,
  });

  return (
    <>
      <TipFiltersBar filters={filters} onChange={setFilters} />
      <TipsTable
        rows={tips.data?.pages.flatMap((page) => page.items)}
        isLoading={tips.isLoading}
        error={tips.error}
        hasMore={tips.hasNextPage}
        loadingMore={tips.isFetchingNextPage}
        onLoadMore={() => void tips.fetchNextPage()}
        isFiltered={hasFilters(filters)}
      />
    </>
  );
}

export function TipFiltersBar({
  filters,
  onChange,
  showCustomer = true,
}: {
  filters: TipFilters;
  onChange: (next: TipFilters) => void;
  showCustomer?: boolean;
}) {
  const channels = useQuery({ queryKey: ["channels"], queryFn: channelsApi.list });
  const channelId = filters.channelId ?? null;
  const callers = useQuery({
    queryKey: ["callers", { channelId }],
    queryFn: () => callersApi.list(channelId),
    enabled: channelId !== null,
  });
  const set = (patch: Partial<TipFilters>) => onChange({ ...filters, ...patch });
  // Filters roll up by canonical id server-side, so only canonical channels and callers are offered.
  const channelRoots = (channels.data ?? []).filter((c) => c.id === c.canonicalChannelId);
  const callerRoots = (callers.data ?? []).filter((c) => c.id === c.canonicalCallerId);

  return (
    <Toolbar
      search={filters.symbol ?? ""}
      onSearch={(symbol) => set({ symbol })}
      searchPlaceholder="Symbol…"
      narrowSearch
      filters={
        <>
          <select
            className="select"
            style={{ maxWidth: 190 }}
            aria-label="Status"
            value={filters.status ?? ""}
            onChange={(e) => set({ status: e.target.value })}
          >
            <option value="">All statuses</option>
            {TIP_STATUSES.map((status) => (
              <option key={status} value={status}>
                {status}
              </option>
            ))}
          </select>
          <select
            className="select"
            style={{ maxWidth: 200 }}
            aria-label="Channel"
            value={channelId ?? ""}
            onChange={(e) => set({ channelId: e.target.value ? Number(e.target.value) : null, callerId: null })}
          >
            <option value="">All channels</option>
            {channelRoots.map((c) => (
              <option key={c.id} value={c.id}>
                {c.name}
              </option>
            ))}
          </select>
          {channelId !== null && (
            <select
              className="select"
              style={{ maxWidth: 180 }}
              aria-label="Caller"
              value={filters.callerId ?? ""}
              onChange={(e) => set({ callerId: e.target.value ? Number(e.target.value) : null })}
            >
              <option value="">All callers</option>
              {callerRoots.map((c) => (
                <option key={c.id} value={c.id}>
                  {c.name}
                </option>
              ))}
            </select>
          )}
          {showCustomer && (
            <input
              className="input"
              style={{ maxWidth: 160 }}
              aria-label="Customer"
              placeholder="Customer id"
              value={filters.userId ?? ""}
              onChange={(e) => set({ userId: e.target.value.trim() })}
            />
          )}
          <input
            className="input"
            type="date"
            style={{ maxWidth: 160 }}
            aria-label="From"
            value={filters.startDate ?? ""}
            onChange={(e) => set({ startDate: e.target.value })}
          />
          <input
            className="input"
            type="date"
            style={{ maxWidth: 160 }}
            aria-label="To"
            value={filters.endDate ?? ""}
            onChange={(e) => set({ endDate: e.target.value })}
          />
        </>
      }
    />
  );
}

function StatusBadge({ tip }: { tip: AdminTipView }) {
  const tone =
    tip.status === "ACTIVE" || tip.outcome === "SUCCESS"
      ? "badge-ok"
      : tip.outcome === "FAILURE"
        ? "badge-off"
        : "badge-muted";
  return (
    <>
      <span className={`badge ${tone}`}>{tip.status ?? "—"}</span>
      {tip.reason && <div className="muted">{tip.reason}</div>}
    </>
  );
}

export function TipsTable({
  rows,
  isLoading,
  error,
  hasMore,
  loadingMore,
  onLoadMore,
  isFiltered,
}: {
  rows: AdminTipView[] | undefined;
  isLoading: boolean;
  error: unknown;
  hasMore: boolean;
  loadingMore: boolean;
  onLoadMore: () => void;
  isFiltered: boolean;
}) {
  const [open, setOpen] = useState<AdminTipView | null>(null);
  const columns: Column<AdminTipView>[] = [
    {
      key: "seen",
      header: "First seen",
      className: "nowrap",
      sortValue: (t) => (t.firstSeenAt ? new Date(t.firstSeenAt).getTime() : 0),
      render: (t) => <span className="muted">{formatDateTime(t.firstSeenAt)}</span>,
    },
    {
      key: "call",
      header: "Call",
      sortValue: (t) => t.symbol,
      render: (t) => (
        <>
          <span className="mono">{t.symbol}</span> <span className="muted">{t.direction ?? "no direction"}</span>
        </>
      ),
    },
    {
      key: "terms",
      header: "Entry · Target · SL",
      render: (t) => (
        <span className="mono">
          {entry(t)} · {price(t.target)} · {price(t.stopLoss)}
        </span>
      ),
    },
    {
      key: "horizon",
      header: "Horizon",
      sortValue: (t) => t.horizonSessions ?? -1,
      render: (t) => `${t.horizonSessions ?? "—"}${t.horizonBasis === "CHANNEL_DEFAULT" ? " (default)" : ""}`,
    },
    {
      key: "channel",
      header: "Channel",
      sortValue: (t) => t.canonicalChannelName ?? "",
      render: (t) => (
        <>
          {t.channelName ?? "—"}
          {t.canonicalChannelId !== t.channelId && <div className="muted">rolls up to {t.canonicalChannelName}</div>}
        </>
      ),
    },
    {
      key: "caller",
      header: "Caller",
      sortValue: (t) => t.callerName ?? "",
      render: (t) => t.callerName ?? <span className="muted">—</span>,
    },
    { key: "status", header: "Status", sortValue: (t) => t.status ?? "", render: (t) => <StatusBadge tip={t} /> },
    {
      key: "return",
      header: "Return",
      sortValue: (t) => (t.actualReturn === null ? -Infinity : Number(t.actualReturn)),
      render: (t) => percent(t.actualReturn),
    },
    { key: "receipts", header: "Receipts", sortValue: (t) => t.receiptCount, render: (t) => t.receiptCount },
  ];

  return (
    <>
      <DataTable
        columns={columns}
        rows={rows}
        getRowKey={(t) => t.tipId}
        isLoading={isLoading}
        error={error}
        emptyLabel="No tips yet."
        isFiltered={isFiltered}
        noMatchLabel="No tips match these filters."
        pageSize={25}
        onRowClick={setOpen}
        rowClassName={(t) => (t.status === "MERGED_DUPLICATE" ? "row-muted" : undefined)}
      />
      {hasMore && (
        <div style={{ display: "flex", justifyContent: "center", marginTop: "var(--space-4)" }}>
          <button className="btn btn-sm" disabled={loadingMore} onClick={onLoadMore}>
            {loadingMore ? "Loading…" : "Load more"}
          </button>
        </div>
      )}
      {open && <ReceiptsModal tip={open} onClose={() => setOpen(null)} />}
    </>
  );
}

function ReceiptsModal({ tip, onClose }: { tip: AdminTipView; onClose: () => void }) {
  const receipts = useQuery({
    queryKey: ["tip-ledger", "receipts", tip.tipId],
    queryFn: () => ledgerApi.receipts(tip.tipId),
  });
  const columns: Column<AdminReceiptView>[] = [
    {
      key: "customer",
      header: "Customer",
      sortValue: (r) => r.userId,
      render: (r) => (
        <Link className="mono" to={`/users/${encodeURIComponent(r.userId)}/tips`}>
          {r.userId}
        </Link>
      ),
    },
    { key: "medium", header: "Medium", sortValue: (r) => r.medium, render: (r) => r.medium },
    { key: "kind", header: "Kind", sortValue: (r) => r.kind, render: (r) => r.kind },
    { key: "label", header: "Channel label", render: (r) => r.channelLabel ?? r.appPackage ?? "—" },
    {
      key: "posted",
      header: "Device posted",
      className: "nowrap",
      render: (r) => <span className="muted">{formatDateTime(r.devicePostedAt)}</span>,
    },
    {
      key: "recorded",
      header: "Recorded",
      className: "nowrap",
      sortValue: (r) => new Date(r.recordedAt).getTime(),
      render: (r) => <span className="muted">{formatDateTime(r.recordedAt)}</span>,
    },
  ];

  return (
    <Modal title={`${tip.symbol} receipts`} wide onClose={onClose}>
      <p className="muted" style={{ marginTop: 0 }}>
        {tip.channelName ?? "—"} · first seen {formatDateTime(tip.firstSeenAt)} · {tip.status ?? "—"}
        {tip.mergedIntoTipId && (
          <>
            {" "}
            · duplicate of <span className="mono">{tip.mergedIntoTipId}</span>
          </>
        )}
      </p>
      <DataTable
        columns={columns}
        rows={receipts.data}
        getRowKey={(r) => r.receiptId}
        isLoading={receipts.isLoading}
        error={receipts.error}
        emptyLabel={tip.predictionId !== null ? "Marksy's own calls have no customer receipts." : "No receipts."}
      />
    </Modal>
  );
}
```

- [ ] **Step 4: Create the customer → tips page**

Create `src/pages/CustomerTipsPage.tsx`:

```tsx
import { useState } from "react";
import { useParams } from "react-router-dom";
import { useInfiniteQuery } from "@tanstack/react-query";
import { ledgerApi } from "../api/tipLedger";
import type { TipFilters } from "../api/types";
import { TipFiltersBar, TipsTable } from "./TipLedgerPage";

// Customer → tips (spec §9 /admin/users/{id}/tips): each tip this customer holds a receipt for, once.
export function CustomerTipsPage() {
  const { userId = "" } = useParams();
  const [filters, setFilters] = useState<TipFilters>({});
  const tips = useInfiniteQuery({
    queryKey: ["tip-ledger", "customer", userId, filters],
    queryFn: ({ pageParam }) => ledgerApi.customerTips(userId, filters, pageParam),
    initialPageParam: null as string | null,
    getNextPageParam: (last) => last.nextCursor,
  });

  return (
    <>
      <h2 className="section-title">
        Tips received by <span className="mono">{userId}</span>
      </h2>
      <TipFiltersBar filters={filters} onChange={setFilters} showCustomer={false} />
      <TipsTable
        rows={tips.data?.pages.flatMap((page) => page.items)}
        isLoading={tips.isLoading}
        error={tips.error}
        hasMore={tips.hasNextPage}
        loadingMore={tips.isFetchingNextPage}
        onLoadMore={() => void tips.fetchNextPage()}
        isFiltered={Object.values(filters).some((value) => value !== undefined && value !== null && value !== "")}
      />
    </>
  );
}
```

- [ ] **Step 5: Wire the routes, the nav entry and the Users "Tips" button**

In `src/App.tsx`:
- below the `CallersPage` import, add:

```tsx
import { TipLedgerPage } from "./pages/TipLedgerPage";
import { CustomerTipsPage } from "./pages/CustomerTipsPage";
```

- below the `/callers` route, add `<Route path="/tip-ledger" element={<TipLedgerPage />} />`;
- below `<Route path="/users" element={<UsersPage />} />`, add `<Route path="/users/:userId/tips" element={<CustomerTipsPage />} />`.

In `src/components/Layout.tsx`, add this entry to `NAV` directly after the `/callers` entry. `"/tip-ledger"` does not start with `"/tips"`, so the topbar crumb stays right:

```tsx
  {
    to: "/tip-ledger",
    label: "Tip Ledger",
    icon: icon(
      <>
        <path d="M4 19V5a2 2 0 0 1 2-2h12v18H6a2 2 0 0 1-2-2Z" />
        <path d="M8 7h6M8 11h6M8 15h4" />
      </>,
    ),
  },
```

In `src/pages/UsersPage.tsx`:
- add `import { useNavigate } from "react-router-dom";`;
- inside `UsersPage`, directly below `const qc = useQueryClient();`, add `const navigate = useNavigate();`;
- in the `actions` column, directly before the `Edit` button, add:

```tsx
          <button className="btn btn-sm" onClick={() => navigate(`/users/${encodeURIComponent(u.userId)}/tips`)}>
            Tips
          </button>{" "}
```

- [ ] **Step 6: Run the tests to verify they pass**

Controller: ask the user to run `! npm test -- src/pages/TipLedgerPage.test.tsx src/pages/UsersPage.test.tsx` and `! npm run typecheck` in `C:\AIAgent\admin-app-phase5`.
Expected: all PASS (2 new, 3 existing Users tests), and the typecheck is clean.

- [ ] **Step 7: Commit**

```bash
git add src/pages/TipLedgerPage.tsx src/pages/TipLedgerPage.test.tsx src/pages/CustomerTipsPage.tsx src/pages/UsersPage.tsx \
  src/App.tsx src/components/Layout.tsx
git commit -m "Admin: Tip Ledger page with server filters, cursor paging and a receipts drawer; customer tips

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task B5: Receipt Review page — unparsed and orphan exits, re-parse

**Files:**
- Create: `src/pages/ReceiptReviewPage.tsx`
- Modify: `src/App.tsx` (route `/receipt-review`), `src/components/Layout.tsx` (a "Receipt Review" entry after "Tip Ledger")
- Test: `src/pages/ReceiptReviewPage.test.tsx`

**Interfaces:**
- `ReceiptReviewPage` has two `Tabs`, "Unparsed" and "Orphan exits", and reads `useInfiniteQuery(["receipts", kind])`.
- Only the Unparsed tab has a "Re-parse" action. A re-parse shows its outcome in a `role="status"` line, then invalidates `["receipts"]` and `["tip-ledger"]`.

- [ ] **Step 1: Write the failing test**

Create `src/pages/ReceiptReviewPage.test.tsx`:

```tsx
import { describe, expect, it } from "vitest";
import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { server } from "../test/server";
import { renderApp, seedAuth } from "../test/render";
import type { AdminReceiptView } from "../api/types";
import { ReceiptReviewPage } from "./ReceiptReviewPage";

const BASE = "http://localhost/api/v1";

function ok(data: unknown) {
  return HttpResponse.json({
    data,
    meta: { requestId: "t", timestamp: new Date().toISOString(), pageSize: 100, nextCursor: null },
  });
}

function receipt(overrides: Partial<AdminReceiptView>): AdminReceiptView {
  return {
    receiptId: "r-9",
    tipId: null,
    userId: "user-7",
    kind: "UNPARSED",
    medium: "APP_NOTIFICATION",
    appPackage: "com.upstox.pro",
    channelLabel: "Upstox",
    text: "Nifty looks strong, RENUKA 23.62 to 26",
    devicePostedAt: null,
    recordedAt: "2026-09-28T05:00:00Z",
    parserVersion: "TCP-000",
    ...overrides,
  };
}

describe("ReceiptReviewPage", () => {
  it("re-parses an unparsed receipt, reports the outcome, and keeps orphan exits review-only", async () => {
    let reparsed = false;
    server.use(
      http.get(`${BASE}/admin/receipts`, ({ request }) => {
        if (new URL(request.url).searchParams.get("kind") === "EXIT") {
          return ok([receipt({ receiptId: "r-5", userId: "user-4", kind: "EXIT", text: "Exit TCS" })]);
        }
        return ok(reparsed ? [] : [receipt({})]);
      }),
      http.post(`${BASE}/admin/receipts/r-9/reparse`, () => {
        reparsed = true;
        return HttpResponse.json({
          data: { receiptId: "r-9", kind: "TIP", outcome: "CREATED", tipId: "t-9", parserVersion: "TCP-001" },
          meta: { requestId: "t", timestamp: new Date().toISOString() },
        });
      }),
    );
    seedAuth();
    renderApp(<ReceiptReviewPage />);

    const row = (await screen.findByText("user-7")).closest("tr")!;
    await userEvent.click(within(row).getByRole("button", { name: "Re-parse" }));

    expect(await screen.findByRole("status")).toHaveTextContent("Created a new tip");
    await waitFor(() => expect(screen.queryByText("user-7")).not.toBeInTheDocument());
    await userEvent.click(screen.getByRole("tab", { name: "Orphan exits" }));
    const exitRow = (await screen.findByText("user-4")).closest("tr")!;
    expect(within(exitRow).queryByRole("button")).toBeNull();
  });
});
```

- [ ] **Step 2: Run it to verify it fails**

Controller: ask the user to run `! npm test -- src/pages/ReceiptReviewPage.test.tsx` in `C:\AIAgent\admin-app-phase5`.
Expected: the file fails to load with `Failed to resolve import "./ReceiptReviewPage"`.

- [ ] **Step 3: Create the page**

Create `src/pages/ReceiptReviewPage.tsx`:

```tsx
import { useState } from "react";
import { useInfiniteQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { receiptsApi } from "../api/tipLedger";
import type { AdminReceiptView, ReceiptReviewKind, ReparseView } from "../api/types";
import type { Column } from "../components/DataTable";
import { DataTable } from "../components/DataTable";
import { Tabs } from "../components/Tabs";
import { formatDateTime } from "../lib/format";

const TABS = [
  { key: "UNPARSED", label: "Unparsed" },
  { key: "EXIT", label: "Orphan exits" },
];

const OUTCOMES: Record<string, string> = {
  CREATED: "Created a new tip dated from when the receipt arrived.",
  ATTACHED: "Joined the tip this call already was.",
  ALREADY_HELD: "This customer already holds that tip on this medium, so the receipt stays unparsed.",
  ORPHAN_EXIT: "Read as an exit. It stays unlinked and closes no tip.",
  STILL_UNPARSED: "Today's parser still cannot read it.",
};

// Spec §9 /admin/receipts: unparsed receipts can be re-parsed (invariant 7); orphan exits are review-only.
export function ReceiptReviewPage() {
  const qc = useQueryClient();
  const [kind, setKind] = useState<ReceiptReviewKind>("UNPARSED");
  const [last, setLast] = useState<ReparseView | null>(null);
  const receipts = useInfiniteQuery({
    queryKey: ["receipts", kind],
    queryFn: ({ pageParam }) => receiptsApi.list(kind, pageParam),
    initialPageParam: null as string | null,
    getNextPageParam: (page) => page.nextCursor,
  });
  const reparse = useMutation({
    mutationFn: (receiptId: string) => receiptsApi.reparse(receiptId),
    onSuccess: (result) => {
      setLast(result);
      qc.invalidateQueries({ queryKey: ["receipts"] });
      qc.invalidateQueries({ queryKey: ["tip-ledger"] });
    },
  });

  const base: Column<AdminReceiptView>[] = [
    {
      key: "recorded",
      header: "Recorded",
      className: "nowrap",
      sortValue: (r) => new Date(r.recordedAt).getTime(),
      render: (r) => <span className="muted">{formatDateTime(r.recordedAt)}</span>,
    },
    { key: "customer", header: "Customer", sortValue: (r) => r.userId, render: (r) => <span className="mono">{r.userId}</span> },
    { key: "medium", header: "Medium", sortValue: (r) => r.medium, render: (r) => r.medium },
    { key: "label", header: "Channel label", render: (r) => r.channelLabel ?? r.appPackage ?? "—" },
    { key: "text", header: "Text", render: (r) => <span className="mono">{r.text}</span> },
    { key: "parser", header: "Parser", render: (r) => <span className="muted">{r.parserVersion}</span> },
  ];
  const reparseColumn: Column<AdminReceiptView> = {
    key: "actions",
    header: "",
    align: "right",
    render: (r) => (
      <button className="btn btn-sm" disabled={reparse.isPending} onClick={() => reparse.mutate(r.receiptId)}>
        Re-parse
      </button>
    ),
  };
  const columns = kind === "UNPARSED" ? [...base, reparseColumn] : base;
  const reparseError = reparse.error instanceof Error ? reparse.error.message : null;

  return (
    <>
      <Tabs
        tabs={TABS}
        active={kind}
        onChange={(key) => {
          setKind(key as ReceiptReviewKind);
          setLast(null);
        }}
      />
      <p className="muted">
        {kind === "UNPARSED"
          ? "Receipts the parser could not read. Re-parsing never changes an existing tip; a call it finds joins its tip or becomes one dated from when the receipt arrived."
          : "Exit messages that found no open tip. They stay unlinked for review and never close a tip."}
      </p>
      {last && (
        <p role="status" className="card" style={{ padding: "var(--space-4)" }}>
          {OUTCOMES[last.outcome] ?? last.outcome}
        </p>
      )}
      {reparseError && (
        <p className="error-text" role="alert">
          {reparseError}
        </p>
      )}
      <DataTable
        columns={columns}
        rows={receipts.data?.pages.flatMap((page) => page.items)}
        getRowKey={(r) => r.receiptId}
        isLoading={receipts.isLoading}
        error={receipts.error}
        emptyLabel={kind === "UNPARSED" ? "No unparsed receipts." : "No orphan exits."}
        pageSize={25}
      />
      {receipts.hasNextPage && (
        <div style={{ display: "flex", justifyContent: "center", marginTop: "var(--space-4)" }}>
          <button
            className="btn btn-sm"
            disabled={receipts.isFetchingNextPage}
            onClick={() => void receipts.fetchNextPage()}
          >
            {receipts.isFetchingNextPage ? "Loading…" : "Load more"}
          </button>
        </div>
      )}
    </>
  );
}
```

- [ ] **Step 4: Add the route and the nav entry**

In `src/App.tsx`:
- below the `CustomerTipsPage` import, add `import { ReceiptReviewPage } from "./pages/ReceiptReviewPage";`;
- below the `/tip-ledger` route, add `<Route path="/receipt-review" element={<ReceiptReviewPage />} />`.

In `src/components/Layout.tsx`, add this entry to `NAV` directly after the `/tip-ledger` entry:

```tsx
  {
    to: "/receipt-review",
    label: "Receipt Review",
    icon: icon(
      <>
        <path d="M9 11l2 2 4-4" />
        <path d="M5 3h14v18l-3-2-2 2-2-2-2 2-2-2-3 2Z" />
      </>,
    ),
  },
```

- [ ] **Step 5: Run the tests to verify they pass**

Controller: ask the user to run `! npm test -- src/pages/ReceiptReviewPage.test.tsx` and `! npm run typecheck` in `C:\AIAgent\admin-app-phase5`.
Expected: 1 PASS, and the typecheck is clean.

- [ ] **Step 6: Commit**

```bash
git add src/pages/ReceiptReviewPage.tsx src/pages/ReceiptReviewPage.test.tsx src/App.tsx src/components/Layout.tsx
git commit -m "Admin: Receipt Review page for unparsed receipts (re-parse) and orphan exits (review only)

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task B6: Full admin-app checks, visual check, PR, merge, deploy, verify

**Files:** none new.

- [ ] **Step 1: Run the whole admin-app check set**

Controller: ask the user to run these, in order, in `C:\AIAgent\admin-app-phase5`:
- `! npm test`
- `! npm run typecheck`
- `! npm run lint`
- `! npm run build`

Expected:
- Every vitest file passes: the 8 new cases, plus `client`, `Login`, `Users`, `Clients`, `Sessions` and `TipsIngest`.
- The typecheck is clean.
- Lint reports no errors. `react-refresh/only-export-components` is satisfied, because `TipLedgerPage.tsx` exports components only.
- `dist/` builds.

The VPS Docker build runs the same `npm run build`, so a failure here would also fail the deploy.

- [ ] **Step 2: Check the rendered screens at desktop and phone widths**

Controller: ask the user to run `! npm run dev` in `C:\AIAgent\admin-app-phase5`, sign in at `http://localhost:5174`, and check each screen at a desktop width and at about 390 px (browser device mode):
- `/channels`, `/callers`, `/tip-ledger` (with the receipts drawer open), `/receipt-review` (both tabs), and `/users` → Tips.
- Tables collapse into the existing labelled-card rows, modals fit the screen, and the Toolbar filters wrap.

The dev server proxies `/api` to production (`vite.config.ts`), so this check is **read-only**: do not merge, rename, edit aliases or re-parse from it. Fix any layout break with existing `global.css` classes or inline tokens only, re-run Step 1, and commit.

- [ ] **Step 3: Push and open the PR**

```bash
cd /c/AIAgent/admin-app-phase5
git push -u origin feat/tip-ledger-admin
gh pr create --title "Admin: tip-ledger screens for channels, callers, tips with receipts, and receipt review" --body "$(cat <<'EOF'
Screens for the tip-ledger admin APIs (tip-ledger spec §9/§11; marksy-api Phase 3a). Scorecards follow in Phase 3b.

- Channels: rename, default horizon, capture flag, aliases with their medium (scope), and merge behind a
  "cannot be undone" confirmation that reports tips re-keyed, duplicates retired and receipts moved; Marksy is read-only
- Callers: rename, and merge limited to one canonical channel
- Tip Ledger: server-side filters (status, channel and caller roll-up, customer, symbol, IST dates), cursor
  "Load more", and a receipts drawer naming each customer and medium
- Customer → tips at /users/:userId/tips, reached from Users and from the drawer
- Receipt Review: re-parse unparsed receipts (outcome shown); orphan exits review-only
- API client gains DELETE and cursor pages; built only on the existing Toolbar, DataTable, Modal and Tabs

Tests: client cursor page, Channels (merge gate, Marksy read-only, scoped alias refusal), Callers merge targets,
Tip Ledger (filters to query and cursor, receipts drawer), Receipt Review (re-parse outcome, exits review-only).

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```

- [ ] **Step 4: Merge and deploy**

```bash
gh pr merge --merge --delete-branch
ssh marksy@103.178.160.233 'bash ~/deployment/server/vps-deploy.sh'
```

Expected: the script pulls `~/admin-app`, builds `marksy-admin:local`, and reports the `marksy-admin-web` rollout as successful (`deployment/server/vps-deploy.sh:24,33,47,88`).

- [ ] **Step 5: Verify production**

```bash
ssh marksy@103.178.160.233 'git -C ~/admin-app log --oneline -1 && KUBECONFIG=~/.kube/config kubectl -n market-agent rollout status deploy/marksy-admin-web --timeout=60s'
BUNDLE=$(curl -sS https://admin.indoulia.com/ | grep -o '/assets/index-[^"]*\.js' | head -1)
curl -sS "https://admin.indoulia.com$BUNDLE" | grep -c "Receipt Review"
```

Expected:
- The VPS checkout's HEAD is this PR's merge commit, and the rollout reports it has rolled out.
- A count of 1 or more: the live bundle contains the new nav.

Controller: ask the user to open `https://admin.indoulia.com/tip-ledger`, confirm that tips and the receipts drawer load, and report back.

- [ ] **Step 6: Clean up the worktree**

```bash
cd /c/AIAgent/admin-app && git worktree remove ../admin-app-phase5 && git fetch origin
```

If `git worktree remove` refuses because of the ignored `node_modules`, add `--force`; the branch is merged, so nothing is lost. `C:\AIAgent\admin-app` stays on `feat/gateway-qr-provisioning`.

## Summary

Phase 3a gives the ledger its admin surface.
- Operators can rename channels and callers, set default horizons and capture, and manage aliases per medium.
- They can merge channels and callers. A merge re-keys open tips from stated terms only, retires the later copy of a call as MERGED_DUPLICATE, moves its receipts and pending exit, and never writes a call term. Row locks keep intake and the tracker from racing it.
- Every tip can be browsed with its receipts and customers, and UNPARSED receipts can be re-parsed under invariant 7, with orphan exits left for review.

Phase 5 builds the matching admin-app screens on the existing components.

Phase 3b adds the §8 scorecard function, `/admin/scorecards`, the customer scorecard APIs, and the admin-app Scorecards screen with the §8.3 filters.

## Validation

- Task A1: `tests/test_tip_ledger_admin.py`
- Task A2: `tests/test_tip_ledger_admin.py`, `tests/test_tip_ledger_guard.py`
- Task A3: `tests/test_tip_ledger.py`, `tests/test_tip_tracking_job.py`, `tests/test_tip_tracker.py`
- Task A4: `tests/test_tip_ledger_admin.py`, `tests/test_tip_ledger.py`, `tests/test_tip_text_cleaning.py`
- Task A5: `tests/test_tip_reparse.py`, `tests/test_tip_ledger.py`, `tests/test_api_tips_ingest.py`, `tests/test_api_tips_ledger.py`
- Task A6: `tests/test_api_admin_tip_ledger.py`, `tests/test_openapi_contract_freshness.py`, `tests/test_security_authorization.py`, `tests/test_epic331_authentication_coverage.py`, `tests/test_admin_scope_enforcement.py`, `tests/test_read_only_principal.py`
- Task A7: `tests/test_api_admin_tip_ledger.py`, `tests/test_openapi_contract_freshness.py`, `tests/test_security_authorization.py`, `tests/test_epic331_authentication_coverage.py`
- Task A8: the regression set in Task A8 Step 1, `alembic heads`, and the production smoke test in Task A8 Step 4
- Task B1: `src/api/client.test.ts`, typecheck (both run by the user)
- Task B2: `src/pages/ChannelsPage.test.tsx`, typecheck
- Task B3: `src/pages/CallersPage.test.tsx`, typecheck
- Task B4: `src/pages/TipLedgerPage.test.tsx`, `src/pages/UsersPage.test.tsx`, typecheck
- Task B5: `src/pages/ReceiptReviewPage.test.tsx`, typecheck
- Task B6: `npm test`, typecheck, lint, build, the desktop/phone visual check, and the production bundle check
