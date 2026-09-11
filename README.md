# MARKSY OS

**Your Life. One Intelligent View.**

Marksy OS is a private, single-user Android application that turns notification noise into an intelligent, focused view of what matters.

The first version is intentionally narrow: **capture Android notifications, classify them locally, make the Smart Inbox useful, and route only valuable trading events to Marksy for further analysis.**

Marksy OS is not the trading brain. **Marksy remains the intelligence layer.** The Android app is the collection, local-processing, presentation and eventual action layer.

> **V1 is a private APK for one user. Play Store publication, multi-user accounts and live brokerage execution are not part of V1.**

---

## Product Vision

Phones produce a continuous stream of notifications from WhatsApp, banks, trading platforms, email, delivery apps, work tools and dozens of other sources.

Most of those notifications are noise. Some are useful. A small number are genuinely important. A few may represent an opportunity that deserves immediate attention.

Marksy OS is intended to progressively transform:

```text
83 notifications
       |
       v
   Understand
       |
       v
  Categorize
       |
       v
   Prioritize
       |
       v
  What matters?
       |
       v
   Take action
```

For V1, the most important vertical is **Trading** because the user continuously receives trading-related information from multiple sources such as Upstox, ICICI Direct, ET Money and other platforms/channels.

The long-term product is broader than trading. Trading is the first intelligence workflow because it provides a concrete, high-value use case for validating the architecture.

---

# What Marksy OS Does

## 1. Collect

Marksy OS listens to Android notifications using the native Android notification listener capability.

Example:

```text
Upstox
"RELIANCE crossed ₹2,950"
```

or:

```text
WhatsApp
"Family Group: 12 new messages"
```

The application captures the useful notification information and can remove the consumed notification from the Android notification shade.

### V1 primary sources

- WhatsApp notifications
- Trading applications
- Other Android notification-producing applications

The collector is intentionally generic. Adding a new source should normally be a configuration/classification concern rather than a new notification-capture implementation.

---

## 2. Understand

Captured notifications are converted into a normalized internal event.

Example:

```text
RAW NOTIFICATION

App: Upstox
Title: RELIANCE
Text: Crossed ₹2,950 with high volume

        |
        v

MARKSY EVENT

Category: TRADING
Priority: HIGH
Confidence: 0.97
Source: UPSTOX
```

V1 starts with deterministic, local classification. The classifier is deliberately designed behind an abstraction so more sophisticated AI-assisted classification can be introduced later without redesigning the collector or UI.

---

## 3. Organize

V1 categories:

- **TRADING**
- **BANKING**
- **BILLS**
- **PAYMENTS**
- **OTP**
- **REMINDERS**
- **MESSAGES**
- **WORK**
- **DELIVERY**
- **PROMOTIONS**
- **SYSTEM**
- **OTHER**

Category and priority are separate concepts.

For example:

```text
WhatsApp message
Category: MESSAGES
Priority: NORMAL

Bank fraud alert
Category: BANKING
Priority: CRITICAL

Reliance breakout alert
Category: TRADING
Priority: HIGH
```

---

## 4. Prioritize

Marksy OS should not treat every notification equally.

The product experience should answer:

> **What happened?**
>
> **What matters?**
>
> **What needs my attention?**

Trading events are the first category that gets special downstream treatment.

---

## 5. Route

Marksy OS is intentionally **local-first**.

The phone should not dump every notification into PostgreSQL or send every notification to an AI model.

Instead:

```text
Notification
     |
     v
Capture
     |
     v
Local Classification
     |
     +----------------------+
     |                      |
     v                      v
Normal notification      TRADING event
     |                      |
     v                      v
Local storage          Marksy Gateway
                            |
                            v
                         Marksy
                            |
                            v
                         Analysis
                            |
                            v
                       Marksy OS
```

This keeps notification noise off the backend and controls AI/network usage.

---

# Trading Intelligence Workflow

The core V1 trading workflow is:

```text
Android notification
        |
        v
NotificationListenerService
        |
        v
Marksy OS Collector
        |
        v
Local Classifier
        |
        v
TRADING Event
        |
        v
Marksy Gateway
        |
        v
Marksy Intelligence
        |
        v
Analysis / Insight
        |
        v
Trading Intelligence UI
```

A future example could look like:

```text
09:31  Upstox
       RELIANCE crossed ₹2,950

09:32  ET Money
       Unusual volume detected

09:33  Telegram
       Breakout confirmation

09:34  ICICI Direct
       Positive research update

             |
             v

           MARKSY

4 independent sources aligned
Unusual volume
Price breakout
Positive supporting event

Potential opportunity detected.
```

Marksy OS does not make this trading decision itself. It supplies the relevant event(s) to Marksy, receives the intelligence response, and presents it clearly to the user.

---

# V1 Scope

V1 is deliberately focused on proving the real product loop rather than implementing every screen shown in the product vision.

### Build now

- Android application foundation
- Marksy OS visual system
- Notification capture
- Notification removal from the notification shade
- Local classification
- Local event storage
- WhatsApp notification handling
- Trading notification handling
- Smart Inbox
- Home Dashboard
- Timeline
- Trading Intelligence UI
- Marksy Gateway integration
- Marksy response presentation
- Privacy/permission controls
- Real-device validation
- Private APK build and installation

### UI shell / Coming Soon

The following may exist visually but are not fully implemented in V1:

- AI Insight Center advanced analytics
- Notification heatmap
- Daily Digest automation
- Search Anything
- Rules & Automation engine
- Voice assistant
- Direct Telegram integration
- Direct Gmail integration
- Direct WhatsApp API integration
- Live brokerage execution

The UI should explicitly communicate `COMING SOON` where functionality is not implemented. V1 must never pretend that a deferred capability is operational.

---

# What V1 Does NOT Do

The following are explicitly outside V1:

- Live Upstox order placement
- Autonomous trading
- Direct brokerage execution
- Portfolio management
- Full WhatsApp API integration
- WhatsApp database access/scraping
- Full Telegram API integration
- Full Gmail API integration
- Multi-user registration
- Subscription/payment system
- Play Store publication
- Sending every notification to an LLM
- Using PostgreSQL as a warehouse for every phone notification

A `BUY` or `SELL` shown in the V1 Trading UI is **presentation/simulation only**. There must be no live broker execution path.

---

# Technology Stack

Marksy OS is a **native Android application**. Native Android is preferred because notification access is a core product capability and should not be hidden behind a cross-platform abstraction.

## Android

| Area | Technology |
|---|---|
| Language | **Kotlin** |
| UI | **Jetpack Compose** |
| Architecture | **MVVM + Repository / Clean Architecture boundaries** |
| Dependency Injection | **Hilt** |
| Navigation | **Navigation Compose** |
| Async / reactive state | **Kotlin Coroutines + Flow** |
| Local database | **Room / SQLite** |
| App preferences | **Jetpack DataStore** |
| Notification capture | **NotificationListenerService** |
| Background work | **WorkManager** |
| Networking | **Retrofit + OkHttp** |
| JSON/API serialization | **kotlinx.serialization** |
| Secrets | **Android Keystore / secure platform storage** |
| Testing | **JUnit + AndroidX Test + Compose UI tests** |
| Build system | **Gradle Kotlin DSL** |

Exact dependency versions will be pinned when the Android project is scaffolded rather than hard-coded in this product document.

---

# Why Native Kotlin + Compose?

Marksy OS is not a generic CRUD application. Its most important capability is deep integration with Android's notification system.

Native Kotlin gives us:

- Direct access to Android notification APIs.
- Predictable lifecycle/service behavior.
- Better control over notification cancellation.
- Native background execution mechanisms.
- Strong integration with Android permissions.
- Excellent Compose support for the intended visual design.
- Fewer cross-platform abstractions in the critical collector path.

Flutter and React Native are intentionally not the V1 choice.

---

# Architecture

The intended architecture is:

```text
                         MARKSY OS
                             |
             +---------------+---------------+
             |                               |
             v                               v
        Android UI                   Notification Listener
        Jetpack Compose                      |
             |                               v
             v                         Collector Layer
        ViewModels                           |
             |                               v
             +----------------------> Domain/Event Layer
                                           |
                                  +--------+--------+
                                  |                 |
                                  v                 v
                              Classifier       Event Router
                                  |                 |
                                  v                 v
                              Room DB         Marksy Client
                                                    |
                                                    v
                                               HTTPS / JSON
                                                    |
                                                    v
                                             Marksy Gateway
                                                    |
                                                    v
                                             Marksy Intelligence
```

## Architectural responsibilities

### Notification Collector

Responsible for Android notification access only.

It should not:

- make trading decisions
- call the LLM
- write directly to PostgreSQL
- contain UI logic

### Domain/Event Layer

Converts platform notifications into application-level events.

### Classifier

Determines category and priority locally.

### Local Store

Room stores the information required by the Android experience. Retention is bounded so notification noise does not become a permanent database.

### Marksy Client

The only layer responsible for communicating with the Marksy backend.

### UI

Compose screens consume application state and render it. UI code should not know how Android notifications are captured or how Marksy backend requests are authenticated.

---

# Local-First Data Strategy

A central product principle is:

> **PostgreSQL is not the notification warehouse.**

If the phone receives hundreds of notifications every day, storing all of them permanently in the backend is unnecessary and expensive.

The intended lifecycle is:

```text
Android notification
        |
        v
Local event
        |
        v
Classify
        |
        +-------------------+
        |                   |
        v                   v
Low value             High value / Trading
        |                   |
        v                   v
Short retention       Longer local retention
        |                   |
        v                   v
Expire              Optional Marksy routing
```

Only events explicitly eligible for downstream intelligence should leave the device.

---

# Canonical Event Concept

The fundamental unit inside Marksy OS is an **Event**, not simply a database row representing a notification.

Conceptually:

```text
Notification
     |
     v
Event
     |
     +--> category
     +--> priority
     +--> confidence
     +--> source
     +--> timestamp
     +--> normalized content
     +--> lifecycle state
     +--> provenance
```

A future event may contain structured trading information such as:

```text
source: UPSTOX
category: TRADING
symbol: RELIANCE
event_type: PRICE_BREAKOUT
priority: HIGH
confidence: 0.97
timestamp: ...
```

The event model should remain extensible for future sources such as Telegram, email and other applications.

---

# Notification Lifecycle

The expected V1 lifecycle is:

```text
CAPTURED
   |
   v
CLASSIFIED
   |
   v
STORED LOCALLY
   |
   +--------------------------+
   |                          |
   v                          v
DISPLAYED                 TRADING?
                              |
                         +----+----+
                         |         |
                        NO        YES
                         |         |
                         v         v
                     LOCAL ONLY  FORWARD
                                   |
                                   v
                                MARKSY
                                   |
                                   v
                                RESPONSE
                                   |
                                   v
                               DISPLAYED
```

Successfully consumed notifications can be cancelled from the Android notification shade.

---

# Core V1 Screens

The supplied Marksy OS visual references are the **visual north star**, but not every screen needs full functionality in V1.

## 1. Lock Screen / Marksy Pulse

A focused glanceable surface showing:

- Current time
- Market pulse
- Important notification count
- Trading events
- Priority information

Advanced lock-screen behavior may remain limited in V1 depending on Android platform constraints.

## 2. Home Dashboard

The primary overview:

- Good Morning/header
- Notification totals
- Category breakdown
- Important events
- Trading activity
- Marksy summary

## 3. Smart Inbox

The most important V1 screen.

Users can view:

- All
- Important
- Trading
- Messages

Each event shows source, content, category, priority and recency.

## 4. Trading Intelligence

Displays Marksy's response:

- Instrument/symbol
- Price/context
- BUY / SELL / WATCH / NO TRADE state where supplied
- Entry
- Target
- Stop loss
- Confidence
- Supporting evidence
- Source alignment

No live order is placed.

## 5. Timeline

A chronological view of meaningful events.

## 6. Ask Marksy

V1 UI shell for future conversational intelligence.

Example prompts:

- What happened today?
- Summarize my WhatsApp.
- Show trading opportunities.
- Any important emails?
- What did I miss?

Advanced conversation/voice capabilities are deferred.

## 7. Insights

V1 shell for future:

- Pattern detection
- Trends
- Notification statistics
- Cross-source insights

## 8. More

Settings, permission status, diagnostics and future integrations.

---

# Permissions

The primary V1 permission is Android **Notification Access**.

This permission is powerful because it can expose notification contents from other applications. Marksy OS therefore follows a local-first model.

V1 should:

- Explain the permission clearly.
- Show whether Notification Access is enabled.
- Handle permission revocation gracefully.
- Avoid logging sensitive notification content.
- Avoid transmitting ordinary notification content.
- Keep backend credentials out of source control and APK source/configuration.

The app is initially a private single-user application, so we can optimize the V1 experience around one trusted device rather than building a multi-user permission system.

---

# Marksy Integration

Marksy OS connects to the existing Marksy ecosystem through the established gateway boundary.

Conceptually:

```text
MARKSY OS
    |
    | Trading Events
    v
MARKSY GATEWAY
    |
    v
MARKSY INTELLIGENCE
    |
    v
Analysis / Insight
    |
    v
MARKSY OS
```

The gateway remains a thin transport/intermediary layer. Trading intelligence belongs to the Marksy agent, not to Android or the gateway.

Marksy OS must not duplicate:

- stock-selection logic
- technical-analysis logic
- prediction scoring
- trading strategy
- learning logic
- portfolio reasoning

---

# V1 EPIC ROADMAP

All V1 EPICs are tracked as GitHub Issues in this repository.

## EPIC-001 — Foundation & Product Shell

**Issue:** #1

Establish the Android project, Kotlin/Compose foundation, architecture boundaries, navigation shell, Marksy visual language and private APK build foundation.

**Goal:** A clean, buildable Android application shell.

---

## EPIC-002 — Android Notification Capture & Notification Removal

**Issue:** #2

Implement `NotificationListenerService`, Notification Access onboarding, normalized notification capture, deduplication, service lifecycle handling and cancellation/removal of consumed notifications.

**Goal:** A real notification can enter Marksy OS and disappear from the Android notification shade after successful capture.

---

## EPIC-003 — Notification Classification & Local Event Store

**Issue:** #3

Create the canonical event model, local classification, priority handling, Room persistence, retention and cleanup rules.

**Goal:** Notifications become useful local events without polluting PostgreSQL.

---

## EPIC-004 — Marksy Trading Event Gateway Integration

**Issue:** #4

Create the versioned Android-to-Marksy contract and asynchronous authenticated network path for trading events only. Receive structured Marksy responses and make them available to the UI.

**Goal:** A trading notification can travel from the phone to Marksy and back.

---

## EPIC-005 — WhatsApp & Source App Handling

**Issue:** #5

Make WhatsApp a first-class notification source and establish a configurable source registry for trading and other applications.

**Goal:** WhatsApp works well without private WhatsApp API/database access, while the collector remains generic.

---

## EPIC-006 — V1 Core UI: Home, Smart Inbox & Timeline

**Issue:** #6

Implement the primary user experience using real local events: Home Dashboard, Smart Inbox and Timeline, with the supplied designs as the visual reference.

**Goal:** The app becomes genuinely useful for understanding the notification stream.

---

## EPIC-007 — Trading Intelligence UI & Marksy Response Presentation

**Issue:** #7

Implement the Trading Intelligence experience and Ask Marksy shell. Present Marksy analysis, evidence, confidence and suggested states without allowing live brokerage execution.

**Goal:** The user can understand what Marksy thinks about a trading event.

---

## EPIC-008 — Privacy, Permissions, Security & Local Data Lifecycle

**Issue:** #8

Harden notification permission handling, local retention, backend credentials, HTTPS communication, diagnostics and user data clearing.

**Goal:** The private V1 handles highly sensitive notification information predictably and safely.

---

## EPIC-009 — V1 Integration Validation & Private APK Release

**Issue:** #9

Validate the entire workflow on the target Android device, including WhatsApp, trading notifications, notification removal, local storage, Marksy round-trip, offline behavior, restart/reboot and APK installation/update.

**Goal:** Produce the first reliable private validation APK and prove the end-to-end loop.

---

# V1 Delivery Sequence

The intended implementation sequence is:

```text
EPIC-001
Foundation
    |
    v
EPIC-002
Capture
    |
    v
EPIC-003
Classify + Local Store
    |
    +--------------------+
    |                    |
    v                    v
EPIC-005              EPIC-006
WhatsApp              Core UI
    |                    |
    +---------+----------+
              |
              v
          EPIC-004
       Marksy Gateway
              |
              v
          EPIC-007
      Trading Intelligence
              |
              v
          EPIC-008
       Security / Privacy
              |
              v
          EPIC-009
     Device Validation / APK
```

Some EPICs can be developed in parallel after the core contracts are established, but the notification collector and event model are the foundation for everything else.

---

# Definition of V1 Success

V1 is successful when this scenario works on the real Android device:

### Step 1 — Notification arrives

```text
Upstox
RELIANCE crossed ₹2,950
```

### Step 2 — Marksy OS captures it

The notification is captured by the Android notification listener.

### Step 3 — Notification is classified

```text
Category: TRADING
Priority: HIGH
```

### Step 4 — Notification is removed

The consumed notification disappears from the Android notification shade.

### Step 5 — Smart Inbox shows it

The user sees a polished Marksy OS trading event.

### Step 6 — Event is routed

Only because it is a trading event, it is sent through the Marksy integration boundary.

### Step 7 — Marksy analyzes it

Marksy can combine the event with its own intelligence and other available information.

### Step 8 — Marksy OS presents the response

The Trading Intelligence UI displays the response.

### Step 9 — User decides

The user reviews the information. **No live order is placed by V1.**

---

# Development Principles

## 1. Local first

Do not send data to the backend unless there is a reason.

## 2. Trading is a routing decision, not an Android intelligence engine

Marksy OS identifies and routes trading events. Marksy provides trading intelligence.

## 3. UI should never fabricate intelligence

Mock data is allowed during UI development, but real and mock states must remain clearly separated.

## 4. Fail honestly

If Marksy is unavailable, show that it is unavailable. Do not display stale or fabricated analysis as if it were live.

## 5. Keep the collector simple

Notification capture should not know about trading strategies or UI details.

## 6. Avoid premature integrations

Notification access is the V1 integration mechanism for WhatsApp, Telegram, email and trading apps. Direct APIs can be added later when they provide enough value to justify their complexity.

## 7. No live trading in V1

The architecture may prepare for future execution, but V1 must not have a path capable of placing a real broker order.

## 8. Build the real loop before the complete product

The screenshots describe the destination. V1 proves the core behavior first.

---

# Future Direction

After V1 validation, Marksy OS can grow into the broader product shown in the design references:

```text
                    MARKSY OS
                        |
        +---------------+----------------+
        |               |                |
     Personal        Trading         Automation
   Intelligence     Intelligence      & Actions
        |               |                |
        v               v                v
 Notifications       Marksy          Rules
 WhatsApp            Trading          Search
 Email               Insights        Digest
 Telegram            Opportunities   Voice
 Banking             Monitoring      Integrations
```

Potential future capabilities include:

- AI-powered notification classification
- Cross-source correlation
- Trading opportunity detection
- Notification heatmap
- Daily digest
- Natural-language search
- Rules and automation
- Voice assistant
- Telegram integration
- Gmail integration
- Deeper WhatsApp workflows where appropriate
- Broker integration with explicit user confirmation

These are **future capabilities**, not V1 requirements.

---

# Repository Structure Target

The Android project is expected to evolve toward a structure similar to:

```text
marksy-os/
├── app/
│   ├── src/main/
│   │   ├── java/.../
│   │   │   ├── data/
│   │   │   │   ├── local/
│   │   │   │   ├── remote/
│   │   │   │   └── repository/
│   │   │   ├── domain/
│   │   │   │   ├── model/
│   │   │   │   ├── classifier/
│   │   │   │   └── usecase/
│   │   │   ├── notification/
│   │   │   │   ├── service/
│   │   │   │   ├── parser/
│   │   │   │   └── source/
│   │   │   ├── ui/
│   │   │   │   ├── home/
│   │   │   │   ├── inbox/
│   │   │   │   ├── trading/
│   │   │   │   ├── timeline/
│   │   │   │   ├── insights/
│   │   │   │   ├── ask/
│   │   │   │   └── more/
│   │   │   └── core/
│   │   └── AndroidManifest.xml
│   └── build.gradle.kts
├── docs/
│   ├── architecture/
│   ├── api/
│   └── ui/
├── gradle/
├── .gitignore
├── build.gradle.kts
├── settings.gradle.kts
└── README.md
```

This is a target structure, not a requirement to create every directory before it is needed.

---

# Security Notes

Never commit:

- API keys
- backend authentication secrets
- broker credentials
- Telegram bot tokens
- private certificates
- production environment files
- notification dumps containing sensitive personal information

The Android application should use platform secure storage for credentials/tokens and communicate with the backend over HTTPS.

---

# Status

**Product:** Marksy OS  
**Version:** V1  
**Platform:** Android  
**Distribution:** Private APK / sideloaded  
**Primary V1 input:** Android notifications  
**Primary V1 source:** WhatsApp + trading applications  
**Primary intelligence backend:** Marksy  
**Live brokerage execution:** Not enabled  
**Play Store:** Not planned for V1

---

## Marksy OS

**Less Noise. More Intelligence.**

> From notifications to opportunities.
