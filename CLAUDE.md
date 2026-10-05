# youmo-ime, 幽默输入法 (branched from fcitx5-android; never merged upstream)

Free to restructure; upstream compatibility is not a constraint.

## Where code goes

- `lib/ime-core` — plain JVM module (`java-library`, no Android on the classpath). **Decision logic
  belongs here**: cursor/composing tracking, gesture recognition, key policy, state machines.
  Written so it is testable in milliseconds. Guarded by animal-sniffer (Android API 23 surface)
  and Kover floors (90% overall, 60% per package).
- `app` — Android glue: Views, the `InputMethodService`, preferences, file-backed managers,
  JNI-facing `core/`. Keep it thin: read prefs / Android state, pass *values* into ime-core
  classes, apply the returned decision.
- Pattern to follow (see `PendingInputPolicy`, `LayoutSwitchPolicy`, `BackspaceSwipeBehavior`):
  pure class, preferences passed in as arguments, no `AppPrefs`, no `Context`.
- Deliberately **not** done (measured, see `dev/PLAN.md` P6 if present): an `ImeHost` interface
  over the service, injecting `AppPrefs` everywhere, a DI framework change.

## Commands

| What | Command |
|---|---|
| Core logic tests + API-level check + coverage floors | `./gradlew :lib:ime-core:check` |
| App JVM tests (Robolectric where needed) | `./gradlew :app:testOfflineDebugUnitTest` |
| Static analysis (syntax rules only; type resolution doesn't work here) | `./gradlew detekt` |
| Accept current findings after a deliberate batch (run alone, not with `detekt`; `:<module>:detektBaseline` for one module, bare `detektBaseline` rewrites every module) | `./gradlew :<module>:detektBaseline` |
| App coverage (report only, no floor) | `./gradlew :app:koverLogOfflineDebug` |
| Engine data into app assets (part of every app build; downloads libime's sources, the mixed model of this fork's release `engine-data-*` and the curated new-word pack of `words-*`, SHA-256 checked, once) | `./gradlew :app:compileEngineData` |
| A new `engine-data` release's files (CommonCrawl crawl, cleaning, mix, new words, evaluation; ~100 GB down, an hour on a rented 16-core box via `dev/training/jobs/engine-data.json`) | `TOOL=… EVAL=… SETS=… lib/ime-dict-tool/engine-data.sh <work dir>` |
| Sentence models into app assets (part of every app build; fetched from this fork's release `sentence-models-*`, SHA-256 checked) | `./gradlew :app:copySentenceModels` |

Unit tests need no submodules, NDK or CMake. Instrumented tests (`FcitxTest`, `SoftKeyboardTest`)
need the native build and an emulator; CI runs them weekly / on PRs labelled `emulator`.

## Conventions

- New logic gets a unit test in the same change. Prefer fakes over mockk: `FakeEditor`
  (ime-core testFixtures) and `FakeFcitxAPI` (app test sources). `FakeEditor` is held to the real
  `BaseInputConnection` by a contract test; `FakeFcitxAPI` is not (the real engine needs JNI), so keep
  it dumb: store config, record calls, simulate no input.
- `unitTests.isReturnDefaultValues = false` on purpose: a JVM test that reaches an android.jar
  stub should fail loudly. Anything needing the framework runs under Robolectric.
- `FcitxAPI.getAddonReverseDependencies` and friends are `suspend` and run on the fcitx thread.
  Do not add `runBlocking` on the main thread; `FcitxConnection.runImmediately` is for cached
  reads and `eventFlow` only.
- detekt findings are frozen in `config/detekt/baseline-*.xml`. New code must not add to them;
  don't regenerate the baseline just to silence a new finding.
- Comments explain *why* (a bug that bit, an Android quirk); match the surrounding density.
