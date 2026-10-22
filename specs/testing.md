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
| Python tools | `lexicon/tools`, `lib/ime-eval/tools` | `python3 -m unittest discover -s lexicon/tools` (and `-s lib/ime-eval/tools`) | the lexicon and evaluation scripts |
| Static | all Kotlin modules | `./gradlew detekt` | syntax rules (type resolution does not work here); findings frozen in `config/detekt/baseline-*.xml` |
| Native host tests | `app/src/test/cpp/host-test.sh`, `lib/sherpa-onnx/host-test.sh` | the scripts | C++ helpers, compiled for the host |
| Instrumented | `app/src/androidTest` | CI (`emulator` label / weekly); locally `connectedTextDebugAndroidTest` | the real native engine and keyboard: `FcitxTest`, `SoftKeyboardTest`, `TypingHostActivityTest`, `NativeMatrixKernelTest`, `FcitxConfigTest`, `FcitxEnglishTest`, `FcitxPreeditTest`, `SetupActivityDeviceTest`, `FileUtilTest`; `EngineEvalRunner` is the device runner behind `run-on-device.sh` |
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
costs, learning through the learning record as the app does, `EvalUser`; rows typed once, again and
twice; `--user-counts none|multi|full` and `--short-guard off` change a channel of what is learned in
the scores, `LearningUse`, to see what it is worth; `--misses <out.tsv>` writes what was not first), `learn-ux` (scripted user behaviour,
`data/learn-ux/*.txt`: type, pick, a context, then the order the learning spec says, each
expectation PASS, FAIL or PENDING where the engine cannot do it yet; `--seed <n>` commits n real
samples first, `--gate` fails on a FAIL or when nothing was checked; a `space` with only 联想 shown stops the scenario), `ksc` (keystrokes), `t9` (a set turned into digits, then read with `pinyin --scheme
t9-strict`, as the app types it; `--presses once`, 连按 off, the default, or `letters`, each letter
spelt by 连按, a letter past its key's first as the letter typed as itself, as the session has it), `english-glide` (glide
typing on simulated paths, `--wobble` the key-width noise), `pinyin-glide` (the same per dictionary word
of a pinyin set; `--shuangpin <scheme>`, `--keys nine`, `--table <table.data>`, `--word <pinyin>` one
clean path's readings), `tune` (penalties), `score` (compare a result with a baseline), `handwriting` (handwriting's sentence reading), `table` (a table's codes, optionally with pinyin and the models), `sentences` (each sample's sentences written out), `predict-learn` (联想 after learning), `learn-log-bench` (a heavy year's learning record opened, folded and opened again; `--legacy <pinyin.data>`: a heavy user's old logs converted at the first open, and caught up after an older build wrote them; `--counts <pinyin.data>`: what the ranking's counts cost on that record, attached, made anew and per key typed; not together with `--legacy`, which reuses `<dir>/legacy`), `predict-set` (a 联想 set made from a set), `shuangpin` (a set turned into a 双拼 scheme's keys), `slips` (a set with slips put in), `chars` (the data's characters), `lm` (a sentence model's scores of texts), `libime` (reading libime's pinyin, history or table files);
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
  bar. Scripted, that is `uat/tools/uat.py` (below); `dev/redroid/` is only an uncommitted
  workbench. Root via `docker exec`.
- **An emulator** for what redroid cannot do: another Android version, real telephony, a browser
  (redroid has none, and the UAT's test page needs Chrome), a hardware keyboard (a uinput one in
  redroid is the host's too). Close it before `assembleTextRelease`: R8 and a running emulator
  (qemu ~6 GB resident) together make earlyoom kill the emulator. Voice (UAT 12) and the hardware
  keyboard (17) run on a headless one, `uat.py emu-up` (port 5554, Xvfb; voice from a host PulseAudio
  virtual source playing clips made by `uat/tools/voice-clips.py`).
- A real phone (arm64) for performance, voice latency and a real microphone.

Worth checking by hand for keyboard changes: portrait and landscape; a password field (nothing
learned, no hold-to-talk); a URL and an email field (address keys); a search field (Enter acts);
switching input methods mid-input; rotating with Chinese half-typed; the app upgraded in place over
the last release (`adb install -r`), not only a clean install.

Observing the keyboard: `uiautomator dump` does not see the keys or popups (the IME window is not in
its tree), and icon-only controls have no text; tap by coordinates and judge by screenshots and by
reading the field's text back. In Chrome, read a field through DevTools (`adb forward tcp:9222
localabstract:chrome_devtools_remote`, then `Runtime.evaluate` over the page's websocket): exact,
composing text included. A web field's `autocomplete="off"` reaches the input method as
`TYPE_TEXT_FLAG_NO_SUGGESTIONS` (English then shows no candidates).

## User acceptance tests (`uat/`)

What a user does and sees, case by case, each on its own from a stated baseline (B0: reset, pinyin and
English, the test page `uat/fixtures/fields.html` open), run on a device by hand or by script, never in
place of the unit tests. A change in behaviour changes its cases in the same change; when a case and
`docs/` disagree, find which is wrong. A run is written down in `uat/runs/<date>-<device>-<version>.md`;
the case files hold no results. `uat/README.md` has the format, the baselines and the reset.

The runner, `uat/tools/uat.py`: `up` / `down` (two redroids, `youmo-redroid` on 5556 and
`youmo-redroid2` on 5557), `run [--areas] [--ids] [--devices]` (`UAT_BUILD=release`: on the signed x86_64
release APK, the debug package uninstalled first), `mark` (a visual case after its sheet
is seen), `emu-up` / `emu-down`. One script per case file (`uat/scripts/<file>.py`; handwriting's
strokes in `hw_strokes.py`); the UI read back by a resident `Dumper.java`; visual cases go on contact
sheets, reviewed per `uat/tools/sheet-review.md`. Details in `uat/README.md`.
