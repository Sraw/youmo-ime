# youmo-ime, 幽默输入法 (branched from fcitx5-android; never merged upstream)

Free to restructure; upstream compatibility is not a constraint.

This page is the summary. The long form is [`specs/`](specs/README.md): architecture, the engine,
the app, data and build, testing, conventions, pitfalls. Read the page for the area before changing
it, and keep it true. What a user sees and every setting: [`docs/`](docs/README.md), updated with
any change to behaviour or settings.

## Where code goes

- `lib/ime-core` — plain JVM module (`java-library`, no Android on the classpath). **Decision logic
  belongs here**: cursor/composing tracking, gesture recognition, key policy, state machines.
  Written so it is testable in milliseconds. Guarded by animal-sniffer (Android API 29 surface)
  and Kover floors (90% overall, 60% per package).
- `app` — Android glue: Views, the `InputMethodService`, preferences, file-backed managers,
  `core/` (`KotlinHost`, which runs ime-core's `host/` — the fcitx host ported to Kotlin — on one thread). Keep it thin: read prefs / Android state, pass *values* into ime-core
  classes, apply the returned decision.
- One APK, no dictionaries or models in it: they are the two **resource packs** the user imports
  (`dev/design/060-asset-packs.md`; specs/data-and-build.md). The 文字包 (pinyin, English, 形码,
  strokes, sentence models, handwriting) and the optional 语音包 (sherpa-onnx streaming, X-ASR
  fine-tuned, the polish tagger; `VoiceDataPlugin`) are made by `AssetPacksPlugin` (`:app:packs`),
  Ed25519-signed; `PackShelf` (ime-core `pack/`) checks and installs them, `PackStore`/`AppData` (app
  `data/pack/`) read them. Without the text pack the keyboard is `PackGateView`; without the voice pack
  the microphone says so. `RECORD_AUDIO` is asked for on first voice use. JVM tests get packs from
  `TestShelves` (KeyboardHarness installs both); devices through the debug build's `PackImportProvider`.
- Pattern to follow (see `PendingInputPolicy`, `LayoutSwitchPolicy`, `BackspaceSwipeBehavior`):
  pure class, preferences passed in as arguments, no `AppPrefs`, no `Context`.
- Deliberately **not** done (measured, see `dev/archive/PLAN.md` P6 if present): an `ImeHost` interface
  over the service, injecting `AppPrefs` everywhere, a DI framework change.

## Commands

| What | Command |
|---|---|
| Core logic tests + API-level check + coverage floors | `./gradlew :lib:ime-core:check` |
| Drawing engine (060 redraw) tests on JVM and JS, API-level check, coverage floors; snapshots anew: `:lib:keyboard-scene:jvmTest -Psnapshots.record=true` | `./gradlew :lib:keyboard-scene:check` |
| App JVM tests (Robolectric where needed) | `./gradlew :app:testDebugUnitTest` |
| The same at SDK 29 (Android 10, minSdk: must keep working; specs/testing.md) | `./gradlew :app:testDebugUnitTestAt29` |
| Engine UAT replay (uat/ cases through Engines with the app's data; not in check, minutes the first time) | `./gradlew :lib:ime-eval:uatTest` |
| The two resource packs (signed with `youmo.packKey` of local.properties if set, else the committed test key, which only debug and benchmark builds trust; the voice pack needs `youmo.voiceModelDir`, absolute or `~/`, in ~/.gradle/gradle.properties: without it `:app:packTextPack`, the text pack alone) | `./gradlew :app:packs` → `app/build/packs/` |
| A release's downloads: the APK with the text pack (文字版), with both (语音版), and which packs changed (`-PbuildABI=arm64-v8a`, the release key) | `./gradlew :app:releaseBundles` |
| Import the built packs into a debug build on an emulator (`--no-voice`: the text pack only; installs the debug APK first; a phone imports in Settings → 资源包) | `uat/tools/uat.py packs --port <port>` |
| Static analysis (syntax rules only; type resolution doesn't work here) | `./gradlew detekt` |
| Accept current findings after a deliberate batch (run alone, not with `detekt`; `:<module>:detektBaseline` for one module, bare `detektBaseline` rewrites every module) | `./gradlew :<module>:detektBaseline` |
| The testbed app (every kind of field and app behaviour, for emulator and phone tests; uat/TESTBED.md) | `./gradlew :testbed:installDebug` |
| App coverage (report only, no floor) | `./gradlew :app:koverLogDebug` |
| Engine data into the text pack (part of `:app:packs`; downloads libime's sources, the mixed model of this fork's release `engine-data-*` and the curated new-word pack of `words-*`, SHA-256 checked, once) | `./gradlew :app:compileEngineData` |
| A new `engine-data` release's files (CommonCrawl crawl, cleaning, mix, new words, evaluation; ~100 GB down, an hour on a rented 16-core box via `dev/training/jobs/engine-data.json`) | `TOOL=… EVAL=… SETS=… lib/ime-dict-tool/engine-data.sh <work dir>` |
| Sentence models into the text pack (part of `:app:packs`; fetched from this fork's release `sentence-models-*`, SHA-256 checked) | `./gradlew :app:copySentenceModels` |
| English's words and model into the text pack (part of `:app:packs`; the arpa of this fork's release `english-data-*`, SHA-256 checked, compiled) | `./gradlew :app:compileEnglishData` |
| A new `english-data` release's arpa (FineWeb sample-10BT shards, ~3 min, 7 GB resident; its manifest says which), then pruned (~1 min, 3.3 GB) | `JAVA_OPTS=-Xmx9g ime-dict-tool english-lm -o full.arpa --known en_dict.fscd --tokens 300000000 <shard.parquet>...` (`en_dict.fscd`: fcitx's spell dictionary, no longer in the repo; `spell-dict/en_dict.fscd` of github.com/fcitx5-android/prebuilt at 86ce2c95, SHA-256 4f1a4416ed0ce8ba7d13c9280bf6f83e66cee3903b330e5e1d136288fa841452, https://raw.githubusercontent.com/fcitx5-android/prebuilt/86ce2c95d42f1132746fbf60c278193aa1f4b758/spell-dict/en_dict.fscd); `JAVA_OPTS=-Xmx6g ime-dict-tool prune -o english.arpa --lm full.arpa --threshold 3e-8` |
| English typing measured: committed as meant, wrongly corrected, slips fixed, words offered before a key | `ime-eval english <english.data> lib/ime-eval/data/english.txt [--misses <out.tsv>]` |
| English glide typing measured on simulated paths: first, first three, time a word | `ime-eval english-glide <english.data> lib/ime-eval/data/english.txt [--wobble 0.3] [--misses <out.tsv>]` |
| Pinyin glide typing measured the same way, a dictionary word at a time (`--word nihao`: one clean path's readings; 双拼, the nine keys, a table's codes instead) | `ime-eval pinyin-glide <pinyin.data> lib/ime-eval/data/pinyin-chat.tsv [--shuangpin Ziranma \| --keys nine \| --table <wbx.data>] [--misses <out.tsv>]` |
| Voice post-processing measured on recorded stretches (voice-eval's `seg_app.py <set>`, `ENDPOINTS=1` for the app's endpoint segments: the recognizer and rerank, each segment's n-best and time; no recognizer runs here): CER, written CER, marks P/R/F1, ends | `ime-eval voice-post <seg-<set>.jsonl>... [--source app\|top] [--misses <out.tsv>] [--texts <out.tsv>]` |

Unit tests need no NDK or CMake (the repo has no submodules). Instrumented tests (`HostTest`, `SoftKeyboardTest`)
need an APK build and an emulator; CI runs them weekly / on PRs labelled `emulator`.

## Conventions

- New logic gets a unit test in the same change. Prefer fakes over mockk: `FakeEditor`
  (ime-core testFixtures) and `FakeHostApi` (app test sources). `FakeEditor` is held to the real
  `BaseInputConnection` by a contract test; `FakeHostApi` is not (the real `KotlinHost` needs the engine's data), so keep
  it dumb: store config, record calls, simulate no input.
- `unitTests.isReturnDefaultValues = false` on purpose: a JVM test that reaches an android.jar
  stub should fail loudly. Anything needing the framework runs under Robolectric.
- `HostApi` (its parts `TypingApi`, `EngineSettingsApi.imOptions`, `PunctuationApi.punctuationMap` and friends) is `suspend` and runs on the fcitx thread.
  Do not add `runBlocking` on the main thread; `HostConnection.runImmediately` is for cached
  reads and `eventFlow` only.
- detekt findings are frozen in `config/detekt/baseline-*.xml`. New code must not add to them;
  don't regenerate the baseline just to silence a new finding.
- Comments explain *why* (a bug that bit, an Android quirk); match the surrounding density.
