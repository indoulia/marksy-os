# Marksy IPO home and detail redesign

Date: 2026-10-02 · Branch `feat/ipo-redesign` · Mockup https://claude.ai/artifact/WoF828Fc3gx27uu6HdCq8J (approved)

## Goal

Answer an applicant's first question, "what can I still bid on, and by when", in one scroll. Then make the detail page lead with the decision for the issue's current stage. The page uses the same lane pattern as the Inbox redesign.

## Decisions

User decisions (2026-10-02):
1. No broker hand-off of any kind: no deep links and no "Copy bid details". Marksy stays the master.
2. GMP shows on the home cards and on the detail page. It is always labelled "unofficial" and shows its as-of time.
3. "I applied" is out of scope: no local storage and no backend route.
4. The UPI mandate cutoff is 5 pm IST on the closing day, shown as the usual cutoff (an assumption).
5. GMP comes from multiple sources ("try fetching GMP from multiple sources"). The app shows every reading the API returns (`gmp.readings[]`, one per source). Adding sources is a separate marksy-api PR (`feat/gmp-multi-source`); this app work does not depend on it.

Recommendations the user can override:
- When sources disagree, a card shows the premium range (`+9–11%`) and the source count. The lot calculator uses the lowest premium, so it never flatters the outcome.
- Retail odds come from `retailAllocationEstimate.probability`. When that is missing, they fall back to 1 ÷ the retail subscription, labelled as an estimate.
- Category limits are worked out in the app. Mainboard: Retail up to ₹2 lakh, Small HNI ₹2–10 lakh, Big HNI over ₹10 lakh. SME: Individual exactly 2 lots, HNI 3 lots and up. Retail and SME Individual bid at cut-off. HNI bids at the upper band.
- Reminders reuse the Plan reminders (kind `FOLLOW_UP`, which fires at its due time, plus a new origin `IPO`). They show in Plan with every other reminder and work without the network.

Out of scope: registrar link, small/big HNI split, broker deep links, the reader's own allotment result, "I applied", the "price band is out" alert (no band date in the API), the market-holiday note, and the "now" price on listed issues (that needs a live quote).

## Problems fixed

- `IpoScreen.kt:95-98`, `:118-121`: sideways stage pills, one stage at a time, no All view. Now lanes, with stage and board filters on the floating stack.
- `MainActivity.kt:387-395`, `IpoDetailScreen.kt:73-88`: no IPO header note; an 18sp title row in the content. Now the note says what is open, or names the IPO and its stage. The in-page title row is gone.
- `IpoScreen.kt:179-186`: every card shows the same facts plus a raw uppercase stage badge. Now the stage picks the line and the headline number.
- `IpoDetailScreen.kt:62-66`: rich data comes out as raw key/value rows. Now subscription bars, verdicts, a calculator and a timeline. Less-used sections fold below, still through `IpoDetailFormatter`.
- `IpoModels.kt:19-20`: `display()` ignores `state`. Now STALE values show "as of …", missing ones "not out yet", and conflicting ones "sources disagree".
- `IpoDetailScreen.kt:77-84`: Watch is the only action. Now there are reminders per date, a lot calculator and an allotment check.
- `IpoScreen.kt:82-86`: back loses the list scroll. The list data and `LazyListState` are now hoisted above the detail branch.

## Data (no backend changes)

The API already returns all of this; the app now parses it in `market/IpoModels.kt`. Shapes were read from marksy-api `api/services/ipo.py`, `app/ipo_subscription.py`, `app/ipo_gmp.py`, `app/ipo_decision.py` and `app/ipo_outcomes.py`.

- `summary.gmp`: `{state, note, readings:[{source, premium, premiumPercent, observedAt}]}` → `IpoGmpDto`.
- `summary.subscription`: `{state, asOf, series:{CAT:[{timesSubscribed, observedAt}]}, latest:{CAT:{…}}}`, with categories QIB, NII, RETAIL, EMPLOYEE, SHAREHOLDER and OVERALL → `IpoSubscriptionDto`.
- `summary.retailAllocationEstimate`: `{probability:{value}, oversubscription:{value}}` → `IpoAllocationDto`.
- `detail.keyDates`: `[{label, date}]`, with labels Opens, Closes, Allotment, Refunds, Shares in demat and Lists.
- `detail.decisionContexts`: `[{context, question, verdict, confidence:{value}, supporting, opposing, uncertainties, answeredBy}]`. Verdicts are APPLY, WATCH, AVOID and NO_DECISION.
- `detail.outcome`: `{issuePrice, listingPrice, listingReturnPercent, expectedReturnPercent, predictionErrorPercent}`.
- `detail.anchorBook` (`state`, `totalAmountCrore`), `detail.companyOverview`.

Home loads `/ipos/counts`, then `/ipos?stage=` in parallel for each live stage with a non-zero count. The live stages are OPEN, CLOSING_SOON, CLOSED, ALLOTMENT, UPCOMING and RECENTLY_LISTED. HANDED_OVER and WITHDRAWN are history and are not fetched, because the unfiltered list is hundreds of issues.

## Home

One `LazyColumn` of lanes. An empty lane is not drawn. Each lane opens with the Inbox `LaneLabel` look: a dot, an uppercase name and a count.

| Lane | Rule | Sort | Stat | Line |
|---|---|---|---|---|
| Closes today (red) | OPEN/CLOSING_SOON, close date = today IST | close | overall x, "subscribed" | "Bid and approve the UPI mandate by 5 pm · min ₹…" plus a chip "Closes today · 3 h 18 m left" and a "Remind me at 3 pm" button until 3 pm |
| Open (emerald) | OPEN/CLOSING_SOON otherwise | close | overall x | "Closes Mon 5 Oct · min ₹…" |
| Allotment (yellow) | CLOSED/ALLOTMENT | list date | final x | "Retail about 1 in N · lists Wed 7 Oct" |
| Opens soon (blue) | UPCOMING | open | open date, "opens" | "₹412–434 · min ₹…" or "Price band not out yet · bids 6–8 Oct" |
| Listed (muted) | RECENTLY_LISTED | list date desc | — | "Listed Wed 7 Oct" |

- Listed folds into one row ("Listed · N recent") under the All view with no search. Expanding it shows the cards.
- Each card carries a GMP line: "GMP +₹48 (+11%) · unofficial · 10:15", or "GMP +9–11% · 3 sources · unofficial · 10:15". It is hidden when there is no reading.
- The SME tag and the watched mark stay on the card.
- Floating stack (Market's `OneHandControls`):
  - The Market sections filter stays.
  - Search filters on name and sector, client-side.
  - A Tune action opens a `MarksyDialog` with two wrapping `FlowRow`s of `Pill`s. Stage: All, Open, Allotment, Opens soon, Listed, Watching. Board: All, Mainboard, SME.
- Header note: "IPOs · 3 open · 1 closing today". A filter or search on shows "IPOs · SME · Open".

## Detail

There is no title row. The header note shows "Kaveri Hospitals · closes 5 pm". The floating stack holds a Reminders bell, which opens a `MarksyDialog` with a toggle per future event, and Watch.

1. **Top card, shaped by stage**: a source line (sector · board · exchanges), a stage chip, a big line, a sub line, three stats and the GMP block.
   - Open: time left or close day; UPI cutoff text; stats are Price band, Min to bid, Subscribed (live / so far).
   - Upcoming: "In N days" or "Opens tomorrow"; stats are Price band (or "Not out yet"), Min to bid, Issue size.
   - Allotment: "Results <day>" or "Check by PAN"; stats are Subscribed (final), Retail (odds), Lists. A Check allotment button opens a `MarksyDialog` explaining the PAN check at the registrar or broker. It shows retail odds, refund and listing dates, and the allotment reminder toggle, but no link.
   - Listed: "+11.8% on listing"; "Marksy expected +9.5% before listing, off by 2.3 points" (or "Marksy made no call"); stats are Issue price, Listed at, Marksy expected.
   - GMP block (not shown once listed): one row per source, e.g. "ipoji.com +₹48 · +11.1% · 10:15", then "Unofficial grey-market premium". "No grey-market quotes yet" when there are none.
2. **Sections**: each is a collapsible card with a title, a one-line summary and a chevron. Order and default-open state follow the mockup:
   - Open: subscription, view, calculator, dates.
   - Upcoming: calculator, view, dates, subscription.
   - Allotment: subscription, dates, view.
   - Listed: gain, subscription, view, dates.
   - Then folded: About the company, Issue and anchor book, Financials and valuation, Risks, Marksy prediction history, Other details (the rest of the payload through `IpoDetailFormatter`).
   - **Subscription**: a bar per category with a 1x tick, and Day pills built from the series grouped by IST date ("Day 2 · Wed 30 · live"). The legend reads "1x = fully subscribed · live, updated 10:40 / final / end of day 2".
   - **Marksy view**: four rows, each with a short question (Listing day, Should you bid, After listing, Long term), a verdict chip (Apply / Watch / Avoid / No call yet), High/Medium/Low confidence and the first reason. The legend reads "Four separate questions and no overall score."
   - **Lot calculator**: category pills, a −/+ stepper, amount blocked, the category range and the chance of allotment. If allotted at the lowest GMP: "+₹…". If the pool is past 1x and lots are above the minimum, a callout: "Retail is 3.9x subscribed, so allotment is a lottery for 1 lot. Bidding 5 lots blocks ₹73,780 for the same chance as 1 lot (₹14,756)." It hints at the next category at the max. There is no bid action. Before opening it offers "Remind me when bidding opens".
   - **If you were allotted** (listed): a lots stepper, cost at the issue price, and the gain if sold at listing.
   - **Key dates**: a timeline from `keyDates`: past rows dimmed, the next one highlighted, and a bell on Opens (10:00), Closes (3 pm, two hours before the 5 pm cutoff), Allotment (6 pm) and Lists (9:55).

## Structure

- `market/IpoModels.kt`: new DTOs, `number`/`localDate`/`bounds` helpers, `IpoValueDto.stateNote()`.
- `market/IpoLifecycle.kt` (new, pure, tested): lanes, card facts, home and detail notes, GMP summary, subscription days, lot calculator, odds text, reminder events. It takes `now: ZonedDateTime` (IST).
- `plan/PlanRepository.kt`: `setIpoReminder(key, title, at)` / `clearIpoReminder(key)`, plus `PlanOrigin.IPO`.
- `ui/IpoScreen.kt` (home), `ui/IpoDetailScreen.kt` (detail), `ui/IpoUiState.kt` (state hoisted in MainActivity: filters, query, opened id, list data, watched set and the note).
- `ui/MarketScreen.kt`: IPO floating actions and search. `MainActivity.kt`: the IPO `titleNote`.

## Testing

Logic only (test budget): `IpoModelsTest` (parsing the new blocks, the state note) and `IpoLifecycleTest` (lanes, card line per stage, GMP range, subscription days, calculator callout and limits, reminder events, notes). No layout tests. A visual check uses offscreen Robolectric renders at w400dp and w360dp, in a scratch test kept out of git. Never `connectedAndroidTest`.
