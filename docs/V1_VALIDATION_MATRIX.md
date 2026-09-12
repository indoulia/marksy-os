# Marksy OS V1 Validation Matrix

This is the release-gate checklist for the first private device validation. It is intentionally manual-first: no APK build, CI run, or brokerage execution is required while development continues.

## 1. Capture → Store → Remove

| ID | Scenario | Expected result | Gate |
|---|---|---|---|
| CAP-01 | Post a normal supported notification | Event appears in Smart Inbox with correct source/category | ☐ |
| CAP-02 | Post a notification with title + body | Both are captured, normalized, and bounded | ☐ |
| CAP-03 | Post a notification with multiline/big text | Useful text is retained without unbounded payload growth | ☐ |
| CAP-04 | Post an ongoing notification | Event is ignored | ☐ |
| CAP-05 | Post a group-summary notification | Event is ignored | ☐ |
| CAP-06 | Marksy OS posts its own notification | Event is ignored | ☐ |
| CAP-07 | Valid notification is persisted | Notification is removed from Android shade after successful local insert | ☐ |
| CAP-08 | Local insert fails | Source notification is not removed | ☐ |

## 2. Classification

| ID | Scenario | Expected result | Gate |
|---|---|---|---|
| CLS-01 | Broker order executed | `TRADING` | ☐ |
| CLS-02 | Broker OTP | `OTP`, not `TRADING` | ☐ |
| CLS-03 | Broker payment/statement notification | Appropriate non-trading category | ☐ |
| CLS-04 | WhatsApp message | `MESSAGES` | ☐ |
| CLS-05 | Delivery notification | `DELIVERY` | ☐ |
| CLS-06 | Promotional notification | `PROMOTIONS` | ☐ |
| CLS-07 | Unknown app with trading-looking text | Not automatically `TRADING` | ☐ |
| CLS-08 | Mixed/ambiguous notification | Highest-confidence matching category wins; no fabricated trading event | ☐ |

## 3. Trading Delivery

| ID | Scenario | Expected result | Gate |
|---|---|---|---|
| TRD-01 | Trading event created | Delivery state starts as `PENDING` | ☐ |
| TRD-02 | Marksy endpoint unavailable | Event remains retryable; no data loss | ☐ |
| TRD-03 | Successful Marksy response | `PENDING → IN_FLIGHT → DELIVERED` and insight is persisted | ☐ |
| TRD-04 | Terminal Marksy rejection | `PENDING → IN_FLIGHT → FAILED`; no endless retry | ☐ |
| TRD-05 | Transient network/server failure | Event returns to `PENDING` and worker requests retry | ☐ |
| TRD-06 | Stale `IN_FLIGHT` event | Worker recovers it to `PENDING` | ☐ |
| TRD-07 | Duplicate source event | Unique source/package key prevents duplicate local event | ☐ |
| TRD-08 | Worker stopped between claim and delivery | Claimed event is safely returned to `PENDING` | ☐ |

## 4. Marksy Tips API Contract

| ID | Scenario | Expected result | Gate |
|---|---|---|---|
| API-01 | POST `/tips` with canonical fields | 201 response is accepted | ☐ |
| API-02 | Repeat same `(source, sourceReference)` | Existing tip identity is accepted without creating a duplicate | ☐ |
| API-03 | `COMPARED` response | Tip ID is persisted and comparison can be fetched | ☐ |
| API-04 | `UNRESOLVED_SYMBOL` response | Tip is retained as a valid result; UI does not fabricate a recommendation | ☐ |
| API-05 | `FAILED` response | Treated as terminal failure when no usable tip ID is returned | ☐ |
| API-06 | GET tip comparison | Verdict/reasons are parsed | ☐ |
| API-07 | GET marksy view | Recommendation, probability, scores, levels, evidence and decision fields are parsed | ☐ |
| API-08 | HTTP error | Error is bounded and classified as retryable unless explicitly terminal | ☐ |
| API-09 | Non-HTTPS base URL | Client refuses configuration | ☐ |
| API-10 | Response contains large/unexpected data | Stored response remains bounded | ☐ |

## 5. Rich Trading Payload

| ID | Scenario | Expected result | Gate |
|---|---|---|---|
| PAY-01 | Full order notification | Symbol, source, reference, direction and available levels are sent | ☐ |
| PAY-02 | Entry/target/SL absent | Missing optional fields remain absent; no invented values | ☐ |
| PAY-03 | Confidence percentage present | Converted to 0..1 API confidence | ☐ |
| PAY-04 | Horizon present | Valid day count is sent | ☐ |
| PAY-05 | Full event context | Event ID, package, title, body, category, priority and timestamps are retained for forward compatibility | ☐ |
| PAY-06 | No safe symbol | Payload is rejected locally rather than guessing | ☐ |

## 6. Response Persistence → UI

| ID | Scenario | Expected result | Gate |
|---|---|---|---|
| UI-01 | Marksy response received | Trading card shows Marksy response status | ☐ |
| UI-02 | Open trading insight | Detail view shows verdict and reasons | ☐ |
| UI-03 | Rich marksy view present | Detail view exposes recommendation, probability, opportunity/trust, levels, horizon and evidence | ☐ |
| UI-04 | Malformed persisted response | Card remains usable; missing rich fields do not crash UI | ☐ |
| UI-05 | No Marksy response yet | Pending/failed state is clear and non-misleading | ☐ |
| UI-06 | Execution action viewed | UI explicitly states V1 execution is disabled | ☐ |

## 7. Local Privacy & Lifecycle

| ID | Scenario | Expected result | Gate |
|---|---|---|---|
| PRV-01 | Ordinary non-trading event older than 7 days | Automatically removed | ☐ |
| PRV-02 | Trading event older than 30 days | Automatically removed | ☐ |
| PRV-03 | Clear local data | All notification events are removed | ☐ |
| PRV-04 | Marksy OS has no notification access | UI clearly directs user to enable it | ☐ |
| PRV-05 | Integration key absent | Gateway shown as not configured; no delivery attempted | ☐ |
| PRV-06 | Notification body contains sensitive data | Logs do not print body/title/source key | ☐ |
| PRV-07 | Network configuration is invalid | Client refuses unsafe configuration | ☐ |

## 8. Device Validation Order

Run the device checks in this order to isolate failures:

1. Install the private V1 build when we intentionally reach device validation.
2. Grant Notification Access.
3. Verify Home/Inbox/Timeline navigation.
4. Generate WhatsApp and ordinary Android notifications.
5. Confirm capture, classification, local display, and notification removal.
6. Generate a broker trading notification.
7. Confirm it becomes `TRADING` and remains locally persisted.
8. Confirm Marksy delivery only after the API key/base URL are configured.
9. Confirm Marksy tip creation and response persistence.
10. Open the trading detail view and compare displayed fields against the API response.
11. Test retry behavior by temporarily making the endpoint unavailable.
12. Test clear-data and retention behavior.
13. Only after all gates pass should private V1 release readiness be marked complete.

## Release Gate

V1 is **not release-ready** until all mandatory CAP, CLS, TRD, API, PAY, UI and PRV checks pass on a real Android device.

APK generation, CI, Play Store publication, and live Upstox order execution are explicitly outside this validation pass.
