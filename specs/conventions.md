# Conventions

## Product rules (decided; do not reopen without the owner)

- **Offline, always.** No network permission, no cloud features, no analytics or crash upload. Two
  builds only: `text` and `voice`. No on-phone LLM layer beyond the two sentence models.
- **Accuracy over size.** A 1–2 GB app is acceptable; spend size on accuracy, not on slimming.
- **The user's words come first.** What the user typed, added or blocked outranks the dictionary:
  a word they keep picking must not stay below one they never picked; a word they typed is theirs
  (a coarse word they typed is neither lowered nor hidden): respect their choice.
- **One general rule over many special cases.** Fix the class of problem, not the example. Edge-case
  branches pile up and are hard to maintain (the owner's words). If a fix needs a special case,
  look for the rule it is an instance of; if there is none, say so rather than adding the branch.
- **One concept, one setting.** Derive related behaviour from one setting instead of adding another.
- **Few settings, good defaults.** Settings are grouped by what a user wants to adjust; each input
  method has its own page; fcitx's internals are not shown.
- **Never upstream.** The fork does not go back to fcitx5-android: no upstream PRs, no
  compatibility shims for it. Change whatever should change; prefer the proper fix over a shim.
- **Licences.** Nothing research-only or non-commercial ships, and nothing that ships is trained on
  it (LCCC only measures, and helps choose parameters such as the model's mix weight). Check a
  dataset's README/data card as well as its LICENSE, and a model's training data.
- **Privacy.** Nothing is read from or learned in sensitive fields (`FieldPolicy`); a backup is a
  plain zip, so what an app marks sensitive stays out of it.

## Code

- **Where:** decisions in `lib/ime-core` as pure classes with values passed in; `app` reads state and
  applies results (see [architecture.md](architecture.md#where-logic-goes)).
- **Kotlin**, matching the surrounding code: idiomatic, small functions, `val` over `var`, sealed
  classes for states and actions, data classes for values. No new DI framework, no `AppPrefs` in
  ime-core, no `runBlocking` on the main thread.
- **Allocation-free hot paths.** The decode path runs on every key: primitive collections
  (`Counts`, `IndexCounts`), reused buffers, no boxing; measure before and after.
- **Models** are int8, mapped from the APK where they lie (never read into the heap), and made and
  run by one onnxruntime version: see [data-and-build.md](data-and-build.md#models-int8-mapped-in-place-one-onnxruntime).
- **Untrusted files** (imports, models, backups) are bounds-checked before allocating and fail with a
  message saying what did not fit.
- **File header** on new source files:
  ```kotlin
  /*
   * SPDX-License-Identifier: LGPL-2.1-or-later
   * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
   */
  ```
- **detekt**: new code adds nothing to `config/detekt/baseline-*.xml`. Do not regenerate a baseline
  to silence a new finding; fix it, or suppress it at the site with the reason.

## Comments and KDoc

The code's comments are a large part of its documentation and are written with care:

- **Explain why**, not what: the bug that bit, the Android quirk, the measurement that decided ("on
  the evaluation set, replacing the decoder's order lost 2.2 points; adding gained 3 to 5").
- **Class KDoc** says what the thing is and the decisions in it, in plain sentences, naming the
  classes it works with in `[brackets]`. Read the KDoc of a class before changing it; update it when
  the change makes it wrong.
- **Plain English**, short words, concrete examples from the domain (`xiaoxue` 小学 / 小雪) rather
  than abstractions. Match the density around you: a terse file stays terse.
- No commented-out code, no TODOs left as placeholders, no changelog in comments (that is git's).

## Strings

User-facing text says what the user gets, in their words, not how it works ("纠正错拼和按偏的键", not
"enable typo model"). Chinese is the first language: write `values-zh-rCN` with care, then
`values-zh-rTW` and English `values`. Keep the three in step; other locales may lag.

## Commits

- Subject: `Area: what changed, as a user or reader would put it`. Examples from the history:
  `Nine keys: a key pressed again soon is read as keys and as its letter`,
  `Coarse words: past the first page, and the user's once typed`,
  `Permissions: no notifications, only those listed may merge in`.
- Body: what was wrong (with the case that showed it), what it does now, and the numbers if it was
  measured; wrapped at ~72 columns. Code identifiers as they are.
- One concern per commit; review fixes may be their own commit (`Review fixes: …`).
- Commit email `15711730+Sraw@users.noreply.github.com`. Agent-made commits end with the agent's
  `Co-Authored-By:` (and session) trailer.
- Branch: work lands on `main`; there are no long-lived branches.

## Review

A change is reviewed by someone (or an agent) other than its author before it is pushed: small,
contained changes (one module, tens of lines, no concurrency, persistence format, security or
permissions) by a verifier; larger or risky ones by a thorough code review. Pure docs and config
values need no reviewer, but are checked by whoever writes them. Findings are fixed or answered
with a reason, not ignored.

## Docs

- `README.md`, for users: what the app is, its features, the measured accuracy, releases and plans,
  data and licences (bilingual). `BUILDING.md`: building, the data releases, signing, where code is.
- `docs/`: the user's manual, in Chinese: every feature and every setting. A change to behaviour or
  a setting updates it in the same commit.
- `specs/`: this, for whoever changes the code. Keep it true as you change the code.
- `CLAUDE.md`: the one-page summary for agents; it links here.
- `PRIVACY.md`: what the app reads, keeps and asks for; changes with any permission or data change.
- `lexicon/README.md`: how words are curated.
- `dev/` is a local workbench (plans, experiments, device scripts), not committed.
