# EPIC-045 — Trust Score vs Target Progress: audit and contract freeze

**Formerly:** EPIC-036, renumbered 2026-10-15 because EPIC-036 also names a play-safe capture epic.

**Status:** DONE 2026-10-15; merged in marksy-os #143 and marksy-api #102, not deployed  
**Priority:** P1  
**Owner:** Cross-repository; contract coordinated from marksy-os  
**Repositories:** `indoulia/marksy-api`, `indoulia/marksy-os`  
**Spec:** [Trust Score and Target Progress design](../superpowers/specs/2026-10-15-trust-and-prediction-score-engine-design.md)

## Goal

Confirm and preserve the distinction between entity-level historical Trust Score and per-open-tip Target Progress (the 0–100 position between stop-loss and target; not a probability). "Prediction Score" is reserved for a future evidence-calibrated estimate and is not built by this workstream. Audit before coding because the existing ledger, scorecard and screens are already substantial.

## Stories (4; maximum 5)

### S1 — Audit existing backend score paths
Inspect `app/tip_scorecard.py`, `app/tip_scorecard_query.py`, scorecard routes/schemas, `app/prediction_trust_score.py`, active/closed prediction routes, tip tracker and relevant tests. Record which code calculates each score and its subject, including the existing `POS-001` prediction opportunity score and `OPR-001` composite score (`docs/api/SCORE-METRICS.md`).

**Acceptance:** a path-level gap matrix distinguishes canonical-tip Trust Score, Marksy/model-level prediction trust, prediction/composite opportunity scores, model confidence/rating, current return, risk–reward and the proposed open-tip Target Progress.

### S2 — Audit existing Android integration
Inspect `TrustScreen.kt`, `ScorecardsView.kt`, `PredictionsView.kt`, DTOs, repository methods and existing tests.

**Acceptance:** existing working screens/components are identified for reuse; no duplicate screen or navigation is proposed.

### S3 — Freeze score semantics and response contract
Use the existing `SCR-001` Wilson lower-bound Trust Score as baseline. Specify Target Progress inputs, status, freshness, unavailable reasons and version, under names that say "target progress", never "prediction score". Reuse current routes when possible.

**Acceptance:** contract documents subject, units, as-of semantics, invalid states, compatibility behavior and no-look-ahead rules.

### S4 — Add/adjust contract golden tests
Cover entity vs tip identity, open tips excluded from historical trust, sample size below 10, duplicate exclusion, terminal tips and stale/missing price states.

**Acceptance:** tests fail for a score-subject mix-up and pass without altering existing expected scorecard results.

## Non-goals

No new Trust Score algorithm; no probability-based Prediction Score; no production ranking or publish-gate change; no screen redesign; no schema migration unless proven necessary by the audit.

## Audit result (2026-10-15)

Gap matrix. "Correct" means reuse unchanged.

**Backend (`marksy-api`)**
- Correct — Trust Score `SCR-001`: `app/tip_scorecard.py` (`compute_metrics`, `_trust`, Wilson 95% lower bound × return quality, `MIN_COMPLETED_FOR_TRUST = 10`). It is terminal only when `closed_at <= as_of`, so open tips and later closes never count, and merged duplicates are dropped before counting. Golden tests: `tests/test_tip_scorecard.py`. The read path `app/tip_scorecard_query.py` rolls channels/callers to canonical roots and reads canonical tips, never receipts. Routes: `/scorecards*`, `/me/scorecard`, `/admin/scorecards`; filter echo carries `asOf`.
- Correct, different subject — `app/prediction_trust_score.py` (EPIC-M1.77): model-level evidence trust per Marksy prediction. Served as `ActivePrediction.trustScore`/`trustQuality` on `/predictions/active`. Same key name as the entity Trust Score; kept for compatibility, not displayed by Marksy OS.
- Correct, different subject — `POS-001` prediction opportunity score (`Prediction.opportunity_score`, 0–100) and `OPR-001` composite opportunity score (0–1) in `docs/api/SCORE-METRICS.md`; `ActivePrediction.confidence` (model confidence).
- Correct — tracker `app/tip_tracker.py`: a level is touched at `price >= target` / `price <= stop` (BUY; mirrored for SELL); entry is the stated midpoint or the first-seen open the tracking job fills in (`tip.entry_price` once `entry_low` is set). Only the official pass closes a tip (`app/tip_tracking_job.py`).
- Correct — price freshness: `app/market_data/freshness.py` `assess_market_data_freshness(stock_id=…)` (`MDF-335-001`: CURRENT / AWAITING_PUBLICATION / OVERDUE / NO_DATA / UNKNOWN). Daily bars live in `market_prices` (`source` tells provisional `upstox-v3-quote-ohlc` from official).
- Correct — distance-to-level and return fields: `ActivePrediction.distanceToTargetPercent`/`distanceToStopLossPercent`, `ProgressPoint.returnToDate`/`toTargetPct`/`toStopPct`, `LedgerTipView.promisedReturn`/`actualReturn`.
- Missing — no per-open-tip position between stop-loss and target exists anywhere. The one builder of the canonical tip view, `api/services/ledger_tips.py` `ledger_view()` → `LedgerTipView`, is embedded in `/predictions/active`, `/tracking/predictions`, `/me/tips`, `/tips/{id}`, `/instruments/{symbol}?include=calls` and source-detail `recent`. One additive field there covers every surface, so no new route is needed.
- Defective — none found in SCR-001.

**Android (`marksy-os`)**
- Correct — Trust tab `ui/TrustScreen.kt` → `ui/ScorecardsView.kt`, `ui/ScorecardDetailScreen.kt` (`TrustRing`, completed/minimum, "not enough history"), DTOs in `market/LedgerModels.kt`. Entities: channel, caller and Marksy engine. A FinFluencer appears as its canonical channel (e.g. a YouTube channel) or caller; no separate identity exists or is invented.
- Missing — the Trust tab parses `filter.asOf` (`ScorecardFilterEchoDto.asOf`) but never shows it.
- Correct — open Marksy calls render through `OpenCallRow` in `ui/PredictionsView.kt` (used by `ui/SetupsView.kt` and the Results "Ended" list). It already shows SL/entry/target, a live stop→target bar and the model confidence ("conf N%"). Closed calls use `ClosedCallRow` (outcome and return, no live value).
- Missing — `LedgerTipDto` has no Target Progress; `ActivePredictionDto.trustScore` is parsed and never displayed (keep it that way).

## Frozen contract (2026-10-15)

The full field contract lives with the backend in `marksy-api/docs/api/SCORE-METRICS.md` ("Target Progress"). In short:
- New nullable object `targetProgress` on `LedgerTipView`, version `TPR-001`; absent on an older backend.
- `status` is `AVAILABLE` or `UNAVAILABLE`. Reasons, checked in this order: `NOT_OPEN`, `INSUFFICIENT_TERMS`, `INVALID_GEOMETRY`, `AWAITING_FIRST_CLOSE`, `AWAITING_ENTRY`, `PRICE_UNAVAILABLE`, `PRICE_STALE`, `LIFECYCLE_RECONCILIATION_REQUIRED`.
- `value` is 0–100 with 2 dp, strictly between 0 and 100 while open (the tracker treats a level as touched at equality). `startValue` is the entry's own position, so a fresh tip's starting point is visible.
- The observation is the latest stored daily close whose session closed at or before `evaluatedAt`, with its session date, source, data basis and per-stock `MDF-335-001` freshness. Only CURRENT and AWAITING_PUBLICATION count as fresh. It is never a live price.
- A close is eligible only if its session closed after the call was first seen (the tracker's session 0). Until then the reason is `AWAITING_FIRST_CLOSE` and no observation is echoed (added 2026-10-15, before release).
- `currentReturn` uses the tracker's `signed_return` on the same observation.
- Deviations from the spec proposal, both from the existing lifecycle:
  - `AWAITING_ENTRY` is added, because a waiting buy-on-dip call has not started.
  - The boundary check uses the observed close only; intrabar high/low touches are left to the tracker.
- Trust Score is unchanged.

**Golden tests:** `marksy-api/tests/test_score_semantics.py` covers subject separation, open tips excluded from Trust Score at any count, fewer than 10 completed tips, duplicates and later closes. Target Progress goldens are written red-first under EPIC-046/854 S1.
