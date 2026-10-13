# Testing

A change is done when its tests pass, the checks pass, and, where it touches what a user sees or
how the engine ranks, it has been measured or tried. Reporting "done" on a change that was only
compiled is not done.

## The layers

| Layer | Where | Run | For |
|---|---|---|---|
| ime-core unit tests | `lib/ime-core/src/test` | `./gradlew :lib:ime-core:check` (tests + animal-sniffer + Kover floors) | every decision and the whole engine; milliseconds each |
| App JVM tests | `app/src/test` | `./gradlew :app:testTextDebugUnitTest` | glue that needs Android types: Robolectric where the framework is reached |
| Tools | `lib/ime-dict-tool/src/test`, `lib/ime-eval/src/test`, `build-logic/convention/src/test` | `:lib:ime-dict-tool:test`, `:lib:ime-eval:test`, `./gradlew -p build-logic test` | data compilation, the evaluation, build tasks |
| Static | all Kotlin modules | `./gradlew detekt` | syntax rules (type resolution does not work here); findings frozen in `config/detekt/baseline-*.xml` |
| Native host tests | `app/src/test/cpp/host-test.sh`, `lib/sherpa-onnx/host-test.sh` | the scripts | C++ helpers, compiled for the host |
| Instrumented | `app/src/androidTest` | CI (`emulator` label / weekly); locally `connectedTextDebugAndroidTest` | the real native engine and keyboard: `FcitxTest`, `SoftKeyboardTest`, `TypingHostActivityTest`, `NativeMatrixKernelTest` |
| Evaluation | `lib/ime-eval` | see below | ranking: whether the first candidate is the sentence meant |
| On a device | redroid or a phone | by hand / scripts | what a user sees: layout, gestures, rotation, upgrade |

Minimum before a commit: `./gradlew :lib:ime-core:check :app:testTextDebugUnitTest detekt`, plus
the tool or build-logic tests if those changed. Unit tests need no submodules, NDK or CMake.

## Writing tests

- New logic gets a test in the same change, in ime-core where the logic is.
- Prefer fakes over mockk. `FakeEditor` (ime-core `testFixtures`) stands in for an
  `InputConnection` and is held to the real `BaseInputConnection` by a contract test.
  `FakeFcitxAPI` (app test sources) cannot be held to the real engine (JNI), so keep it dumb:
  store config, record calls, simulate no input.
- `unitTests.isReturnDefaultValues = false` on purpose: a JVM test that reaches an android.jar stub
  fails loudly. Anything needing the framework runs under Robolectric.
- A class reading `AppPrefs` in its initialiser needs `AppPrefs.init(sharedPreferences)` in the test
  (a relaxed mock is enough); the error message says so.
- Test the behaviour a user would notice, named so ("a coarse word the user typed is not lowered"),
  with the case that bit as the example. A test that only restates the implementation is not worth
  its maintenance.
- Engine tests build small data in memory (`PinyinDataBuilder`, text tables) rather than loading the
  real model (`pinyin.data`); the evaluation is where the real data is judged.
- No `@Ignore`, `.only`, skipped or placeholder tests left behind.
- Kover: 90% lines overall in ime-core, 60% per package. A new package needs its tests.

## The evaluation (`lib/ime-eval`)

Required for any change to segmentation, decoding, penalties, scoring, the sentence models'
use, learning, layers, the dictionary or the data: a change that "looks right" on three examples
routinely costs a point on the sets.

```shell
./gradlew :app:compileEngineData :app:copySentenceModels :lib:ime-eval:installDist
E=lib/ime-eval/build/install/ime-eval/bin/ime-eval
D=app/build/generated/engine-assets/engine/pinyin.data
M=app/build/generated/engine-models/engine
$E pinyin $D lib/ime-eval/data/pinyin.tsv out.tsv --neighbours on \
   --rerank $M/sentence-model.safetensors --refine $M/sentence-model-large.safetensors
```

The sets (`lib/ime-eval/data/*.tsv`): `pinyin` (main, everyday and written sentences), `pinyin-web`
(recent pages, held out from training), `pinyin-chat` and `pinyin-dialog` (LCCC: research use, for
measuring only), `pinyin-new` (new words), `pinyin-collide` (a common word that reads as a new
one), `pinyin-context`, `pinyin-slips`, `pinyin-mixed`, `shuangpin-xiaohe`, `predict/`,
`english.txt`. Other commands: `predict` (联想), `english`, `learn` (what learning brings back and
costs), `ksc` (keystrokes), `t9` (a set turned into digits, then read with `pinyin --scheme
t9-strict`, as the app types it), `tune` (penalties), `score` (compare a result with a baseline);
run `ime-eval` with no arguments for the full usage.

Report top-1 (and the change in samples won and lost) on at least the main, web, chat and new sets,
with the default reading (neighbouring keys on, both models). `release-report.sh` runs the full gate
used for a data release: against a baseline, no gate set may lose significantly more samples than it
wins (McNemar, Bonferroni-corrected), and learning must bring back what was typed.

`run-on-device.sh` runs a set through the APK's own engine on a device, for checking that the app
decodes as the tool does.

## Trying it on a device

Unit tests do not see the keyboard. Anything a user sees or touches (layout, a gesture, the bar,
a settings page, rotation, a field type, an upgrade) is tried on a device before it is called done.

- **redroid** is the default: Android 14 in a Docker container on the host kernel, ~10 s to boot,
  ~1.3 GB. Build `-PbuildABI=x86_64`, install, `ime enable` / `ime set` the service, drive it
  with `adb shell input tap/swipe` at measured key coordinates, read the field back, screenshot the
  bar. The project's local scripts for this live outside the repository (`dev/redroid/`, not
  committed). Root via `docker exec`.
- **An emulator** only for what redroid cannot do (another Android version, real telephony).
- A real phone (arm64) for performance and voice.

Worth checking by hand for keyboard changes: portrait and landscape; a password field (nothing
learned, no hold-to-talk); a URL and an email field (address keys); a search field (Enter acts);
switching input methods mid-input; rotating with Chinese half-typed; the app upgraded in place over
the last release (`adb install -r`), not only a clean install.

Observing the keyboard: `uiautomator dump` does not see the keys or popups (the IME window is not in
its tree), and icon-only controls have no text; tap by coordinates and judge by screenshots and by
reading the field's text back.
