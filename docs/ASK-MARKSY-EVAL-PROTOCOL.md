# Ask Marksy evaluation protocol (ASK-EVAL-002)

Status: **prepared, not run.** It needs the independent people below; no evaluation set has been written yet.

Goal: measure, on questions nobody tuned against, how well Ask Marksy picks a question's type (intent) with the rules alone, with Gemma alone, and as shipped (rules first, Gemma only where the rules find nothing).

Why a new set: the 2026-10-16 audit found the earlier "Gemma 24/24" measured the rules plus Gemma together, and its "held out" questions had been used to write rules (see EPIC-016 in `ROADMAP-NEXT.md`).

## People needed

- **Two question authors** who have not read `AskMarksy.kt`, `PromptTemplates.kt`, `AskMarksyEvalTest.kt` or `GemmaInterpretEval.kt`, and did not take part in tuning them. Marksy users outside the development loop are best. Each writes at least 75 questions in their own words, as they would ask their phone.
- **Two labellers** (the authors can label each other's questions) who give each question its expected intent from the 13 in `AskMarksy.Intent`, using the intent descriptions in the prompt. Ambiguous questions get a list of acceptable intents or are dropped, before any run.
- **One adjudicator** who settles labelling disagreements and records the agreement rate (Cohen's kappa).
- **One custodian**, not the developer who changes rules or prompts, who keeps the sealed set and runs it.

## Before any run: freeze

Record in a manifest committed beside the results:

- app commit SHA; rules file SHA-256 (`AskMarksy.kt`), including the intent keyword lists and `INTENT_HINTS`
- prompt id and version (`ask.interpret` v4 today) and the output schema
- model file name and SHA-256 (`gemma3-1b-it-int4.task`), runtime (`mediapipe-tasks-genai` version), backend (GPU or CPU), decoding (top-k 1, temperature 0)
- the service settings: timeout (5 s), minimum confidence (0.6)
- device model and Android version
- the question file's SHA-256 and the answer key's SHA-256

Any change to an item above after the run starts makes a new evaluation.

## The question set

- At least 150 questions, about 10–15 per intent, plus questions meant as plain search.
- Questions used to change a rule or the prompt go into a separate tuning set. The locked set is then replaced, never edited, and its replacement is written by the same independent authors.

## Runs (on the phone, through instrumentation, never `connectedAndroidTest`)

Three arms on the same questions:

1. **Rules alone:** `AskMarksy.parse`.
2. **Gemma alone:** the model's raw reply. Its intent is compared with the key; a reply that fails the schema or has low confidence counts as wrong, and is also counted separately.
3. **Shipped:** `AskMarksy.interpret` with `ModelQueryInterpreter`.

For each question, record the rules' intent, Gemma's raw reply, the final intent, which part decided it, the service outcome (OK, invalid output, low confidence, timeout, error) and the latency. Stream logcat live; the phone's log buffer rotates within a minute.

## Report

Per arm: number of questions, correct, accuracy with a 95% Wilson interval.

Also:
- **coverage:** the share of questions the rules can read, where the model is now skipped
- **questions the rules cannot read:** how many Gemma got right and wrong, how many it had rejected right and wrong, and the shipped accuracy there
- invalid outputs, low-confidence replies, timeouts
- latency: median, 90th percentile and maximum per question, plus first load
- rules alone vs shipped compared on the questions where they disagree (McNemar)

A sample of 150 has an interval of about ±7 points at 75% accuracy, and the subset the rules cannot read will be much smaller, so results are reported with their intervals and never as general accuracy.
