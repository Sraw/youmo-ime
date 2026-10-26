# Testing

A change is done when its tests pass, the checks pass, and, where it touches what a user sees or
how the engine ranks, it has been measured or tried. Reporting "done" on a change that was only
compiled is not done.

## The layers

| Layer | Where | Run | For |
|---|---|---|---|
| ime-core unit tests | `lib/ime-core/src/test` | `./gradlew :lib:ime-core:check` (tests + animal-sniffer + Kover floors) | every decision and the whole engine; milliseconds each |
| App JVM tests | `app/src/test` | `./gradlew :app:testTextDebugUnitTest` | glue that needs Android types: Robolectric where the framework is reached |
| Engine UAT replay | `lib/ime-eval/src/uatTest` | `./gradlew :lib:ime-eval:uatTest` (not in `check`) | the uat/ cases whose 预期 is what the engine types, through `Engines` with the app's own data: needs `:app`'s data tasks (minutes the first time), and a 2 GB heap; no network (its named keys are held to the host's `KeySyms`, which `KeySymsGoldenTest` holds to fcitx) |
| Tools | `lib/ime-dict-tool/src/test`, `lib/ime-eval/src/test`, `build-logic/convention/src/test` | `:lib:ime-dict-tool:test`, `:lib:ime-eval:test`, `./gradlew -p build-logic test` | data compilation, the evaluation, build tasks |
| Python tools | `lexicon/tools`, `lib/ime-eval/tools` | `python3 -m unittest discover -s lexicon/tools` (and `-s lib/ime-eval/tools`) | the lexicon and evaluation scripts |
| Static | all Kotlin modules | `./gradlew detekt` | syntax rules (type resolution does not work here); findings frozen in `config/detekt/baseline-*.xml` |
| Native host tests | `lib/sherpa-onnx/host-test.sh` | the script | sherpa-onnx's C++ helpers, compiled for the host |
| Instrumented | `app/src/androidTest` | CI (`emulator` label / weekly); locally `connectedTextDebugAndroidTest` | the real host, engine and keyboard (`KotlinFcitx`, `TestFcitx`): `FcitxTest`, `SoftKeyboardTest`, `TypingHostActivityTest`, `NativeMatrixKernelTest`, `FcitxConfigTest`, `FcitxEnglishTest`, `FcitxPreeditTest`, `SetupActivityDeviceTest`, `FileUtilTest`; `EngineEvalRunner` is the device runner behind `run-on-device.sh` |
| Evaluation | `lib/ime-eval` | see below | ranking: whether the first candidate is the sentence meant |
| On a device | redroid or a phone | by hand / scripts | what a user sees: layout, gestures, rotation, upgrade |

Minimum before a commit: `./gradlew :lib:ime-core:check :app:testTextDebugUnitTest detekt`, plus
the tool or build-logic tests if those changed. Unit tests need no NDK or CMake.

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

Keeping the suite lean. In 2026-10 a one-off PIT mutation run found 22 tests that killed no
mutant: 3 were deleted, 1 merged and 18 kept with a reason. It also found about 150 tests that
differed only in their inputs; they became 27 tables.

- A new case of a behaviour already tested is a row in that test, not a new test. Look in the class's
  test file first.
- Tests that differ only in inputs (two or more, where the reasons read better as a list) become one
  table: `assertRows("why" to { … }, …)`, or `assertRows(rows, why) { … }` for rows of data (ime-core testFixtures, `AssertRows.kt`: one implementation). Each row keeps its own setup and
  state. The table checks every row, then fails once, naming each failed row by its why.
- Don't write a test of a bare default (`startsIdle`). Assert the default as the first line of a
  test that uses it, unless it is a default the user sees.
- A test pinned to a bug names the bug in a comment (UAT id, review id, commit). It is never deleted
  or folded into another test's assertions. It may become a row, keeping its name as the row's why
  and its comment.
- A test of a contract that mutation cannot see (a C++ header, a data file, a format, a fixture)
  says `contract:` in a comment.
- A big end-to-end test that happens to cover a small one is no reason to delete the small one.
  Only a sibling testing the same behaviour is.
- In a mutation run, when one is at hand: a test that kills nothing its siblings miss becomes a row
  or gets a reason from the rules above. A method the tool made no mutants of is a blind spot, not
  a sign the test is redundant.

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
spelt by 连按, a letter past its key's first as the letter typed as itself, as the session has it;
`--keys nine|14`, the nine keys by default, `14` the 14 keys' codes read with `--scheme g14-strict`,
which takes no `--presses letters`; the schemes `g14` and `g14-strict` are `T9Segmenter` over
`NineKeyLetters.G14`; `release-report.sh` reports `g14-chat` and `g14-pinyin` at weight 0, as
`t9-chat`, never in the weighted score; `shuangpin <scheme> … --keys 18|nine [--same <kept.tsv>]` types
a set on 双拼's grouped keys, samples with a Latin letter left out, `--same` keeping the samples typed
as they were for the full keyboard to be scored on, read with `--scheme <scheme>-18` or `<scheme>-nine`
(`GroupedShuangpinSegmenter`); `sp18-chat` and `spnine-chat` (小鹤) are reported at weight 0 too; a `--keys` a command does not
take is a usage error, exit 2), `english-glide` (glide
typing on simulated paths, `--wobble` the key-width noise), `pinyin-glide` (the same per dictionary word
of a pinyin set; `--shuangpin <scheme>`, `--keys nine`, `--table <table.data>`, `--word <pinyin>` one
clean path's readings), `tune` (penalties), `score` (compare a result with a baseline), `handwriting` (handwriting's sentence reading), `table` (a table's codes, optionally with pinyin and the models), `sentences` (each sample's sentences written out), `predict-learn` (联想 after learning), `learn-log-bench` (a heavy year's learning record opened, folded and opened again; `--legacy <pinyin.data>`: a heavy user's old logs converted at the first open, and caught up after an older build wrote them; `--counts <pinyin.data>`: what the ranking's counts cost on that record, attached, made anew and per key typed; not together with `--legacy`, which reuses `<dir>/legacy`), `predict-set` (a 联想 set made from a set), `shuangpin` (a set turned into a 双拼 scheme's keys), `slips` (a set with slips put in), `chars` (the data's characters), `lm` (a sentence model's scores of texts), `libime` (reading libime's pinyin, history or table files);
run `ime-eval` with no arguments for the full usage.

A change to `merged.ymd`'s layout or reader is gated per key: `ime-eval pinyin <pinyin.data> <set.tsv>
<out.tsv> --merged <source.txt> [--stale]` compiles the text with `DictionaryCompiler.compile` into a
temp directory (the base split from the eval's own data) and reads it as the app does; `--stale` sets
`mergedStale`. Old reader against new, same frost source, same idle box, runs alternated, median of
≥ 3: p95 ms per key within +10%, candidates identical (2026-10-23 v1 → v2: p95 3.2 → 3.3 ms,
`--stale` 3.2 → 3.5; every sample's ten the same). A table's `.ymc` over it is timed once with it
(`CodedIndex.build`, wbx over frost, ~1.9M text lookups, 3 runs each): v1 5.0–5.6 s, v2 5.9–6.1 s
(5.2 → 6.0 s by median, +15%), once a stamp, in the background.

Report top-1 (and the change in samples won and lost) on at least the main, web, chat and new sets,
with the default reading (neighbouring keys on, both models). `release-report.sh` runs the full gate
used for a data release: against a baseline, no gate set may lose significantly more samples than it
wins (McNemar, Bonferroni-corrected), and learning must bring back what was typed.

`run-on-device.sh` runs a set through the APK's own engine on a device, for checking that the app
decodes as the tool does.

Roborazzi goldens (`app/src/test/screenshots/`) are compared in the text flavor only, since the voice
build's bar adds a microphone. `app/build.gradle.kts` turns record, compare and verify off for every
`testVoice*` task, which makes `captureRoboImage` a no-op there; every other assertion still runs. A
voice case that is about how the microphone looks would need its own golden in a voice-only directory.

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
release APK, the debug package uninstalled first and installed back only for the cases that type into
its `TypingHostActivity`), `mark` (a visual case after its sheet is seen), `emu-up` / `emu-down`. One
script per case file (`uat/scripts/<file>.py`; handwriting's strokes in `hw_strokes.py`); a case in a
.md with no script is reported blocked (没有脚本), never left out; a case whose `needs=` names another
place (the emulator, the voice build, a release by hand, a real phone) is listed as 在别处跑, not counted
as skipped. The UI is read back by a resident `Dumper.java`, which waits for the screen to settle, so
the runner polls for what it waits on rather than sleeping; visual cases go on contact sheets, reviewed
per `uat/tools/sheet-review.md`. The debug build's `TypingHostActivity` is the native field for what the
WebView test page cannot set (an IME_ACTION_NEXT field, no personalized learning, a sensitive clip).
The composing row is read by its ids (`Device.code_row`, `preedit` and `syllables`: `code_row_text`, and
`code_row_syllables` for the 14 keys' syllable strip), not by its class: the strip scrolls too.
Details in `uat/README.md`.

Release gates 2, 3 and 5 of the host replacement (0.4.0) (in-place upgrade from the `uat/fixtures/upgrade`
releases, backups both ways with them, start/latency/memory side by side) are one command each,
`uat/gates/gates.py upgrade|backups|perf`, on a redroid of their own; results in `uat/runs/`
(`uat/gates/README.md`). A `.debug` build is upgraded by putting the fixture's files into its own
directories and gets backups retargeted to its id; the release APK is upgraded with `install -r`.

## Golden files from upstream fcitx

The host is fcitx ported to Kotlin, and the native sources are not in the tree. What fcitx does is kept
as golden files, made by scripts that fetch upstream at the commits the host was ported from, so they
regenerate without a checkout of fcitx:

- Key symbols: `lib/ime-core/tools/keysyms-gen.py` downloads fcitx5's `keydata.h`, `keynametable.h`,
  `keynametable-compat.h` and `keysymgen.h` at 442edbc9 (SHA-256 pinned) and
  writes the committed `host/KeySymUnicodeTable.kt`, `host/KeySymNameTable.kt` and `src/test/resources/keysyms/*.tsv` (what
  `Key::keySymFromString`/`keySymToString`/`keySymFromUnicode`/`keySymToUnicode` return);
  `KeySymsGoldenTest` runs `KeySyms` over every name, every keysym to 0xffff and every code point against them.
  Not part of the build; rerun by hand.
- Quick input, Unicode and the INI rules: `lib/ime-core/tools/host-golden` and `tools/fcitx-ini` compile
  fcitx5's own code at 442edbc9, fetched by `tools/upstream.sh` (GitHub tarball, SHA-256 pinned; also
  fcitx5-chinese-addons 0d3fd040), and write `src/test/resources/host/{charselect,quickphrase}-*.tsv` and
  `host/ini/` (specs/host.md). Rerun by hand: `build.sh <out>` then `gen.py`.
- OpenCC: `lib/ime-eval/tools/opencc/build.sh <work>` builds OpenCC ver.1.2.0 from its tarball
  (SHA-256 pinned; its `.ocd2` came out byte-identical to those 0.3.x shipped), and `golden.py <work>`
  runs `opencc -c <config>` for all 14 shipped configs over every dictionary key of the release, every
  Chinese sentence of `lib/ime-eval/data` and edge cases, into
  `lib/ime-eval/src/test/resources/opencc/golden.tsv.gz` (82,800 cases, 1.6 MB).
- scel2org5: `uat/fixtures/scel/` holds `.scel` inputs and what the 0.3.2 release's `libscel2org5.so`
  writes for each, made on redroid by its `make.sh` (README there). The import now reads `.scel` in Kotlin
  (`ImportFiles.Scel.textDictionary`); `ScelGoldenTest` holds it to these byte for byte, the two broken files
  failing as `IOException`.

## Parity of the two hosts (`uat/parity`)

The native fcitx host and the Kotlin one were run on the same key sequences and compared
(fcitx-replacement §7.2) while both built. Only the Kotlin host builds now: the native side is a debug
APK built at the tag `native-last`, or a recorded native run (`dev/parity-runs/r3`). Dev tooling only:
nothing here is in an APK.

- **Sequence files** (JSON lines): a header with the engine (below), the data releases
  `EngineDataPlugin` names, the input methods enabled in order and the hash of the sequences; then one
  sequence a line (start input method and field, steps: `type`, `space`, `enter`, `bksp`, `pick`,
  `pickText`, `hw`, `ime`, `field`, `app`, `pause`, `toggle`, `quick`, `unicode`). `ime-eval parity-generate --seed --count` writes
  random ones (`SequenceGenerator`); `uat/parity/parity.py uat-export` takes the uat scripts' key
  sequences, sealed with `ime-eval parity-seal`. The header's engine (`Provenance`) is
  `ENGINE_BEHAVIOUR` (`EngineBehaviour.VERSION` in ime-core) and a digest of the data and settings in the
  tree (`Provenance.CONFIG_DIRS`: `app/src/main/host-assets`, `app/src/main/assets`, `lexicon` (only the files EngineDataPlugin reads),
  `lib/ime-dict-tool/opencc`); the data releases beside it. Not the source tree: a comment or a rename would make
  every recording stale, and the native host's (r3, recorded at `native-last`) can never be made again.
  A JVM comparison against a recording of another engine still runs, its report headed "engine changed
  since this recording: differences may be intended", exit 3 (0 a pass, 1 differences); `parity-check`
  says so and passes. Outputs whose headers name other sequences, input methods or engine than the
  sequence file (two device runs from different builds' files) are refused. A JVM replay's output also
  names the engine it ran on (`engine-run`); `parity-compare --jvm` holds that to the recording, not the
  checkout that compares. `parity-rehead` gives old files this checkout's fingerprint, the first one kept
  as `engine-was` however often it runs.
- **When to bump `ENGINE_BEHAVIOUR`**: any change that can change what a key sequence shows or writes:
  ranking, decoding, segmentation, learning, refine scheduling, the host's key handling, settings
  defaults. Not for comments, renames, refactors, tests or tooling. The review checks it: a behaviour
  change with no bump makes the next JVM-vs-recording comparison count it as the host's.
- **Device**: `parity.py` with uat.py's `Device`. The field is a test editor app outside the input
  method (`uat/parity/editor`, built with the SDK's tools, installed as `io.github.sraw.parity.a` and
  `.b` for two uids) that logs every `InputConnection` edit with the field after it; password fields
  are logged masked. Keys are tapped where the Dumper reads them; hardware keys are `input keyevent`
  (no uinput on redroid). Each sequence starts from a root snapshot of the app's data
  (`prepare`), restored with `chown -R` to the current uid and `restorecon -R`; the builds are swapped
  with `adb install -r -d`.
- **JVM**: `:lib:ime-eval:parityReplay` runs a file on `HostReplay` (about a second a sequence; `k/n`
  shards). What the service does around the host is modelled too: the host's texts in the devices'
  zh-CN, read from the app's `values-zh-rCN` (`ResourceHostStrings`); a tap on the bar picks the
  candidate as shown (繁 with 简繁 on); an input method change tells the session the field
  (`FieldPolicy.inputMethodChanged`); fields are named as the service names them for its memory of
  what was written (`ContextMemory.field`: the editor's package and the kind of field); another app's
  bar stays shown until the host updates it or the language key shows the overlay of a new input
  method's name, and a hardware Return shows the floating window over it until the screen's keyboard
  is used again. So is the driver's way around the keyboard: a single-line field's Enter (its editor
  action) puts the keyboard away, and the next tap the driver needs brings it back with a tap on the
  field that puts the cursor at the end, as after a hardware Return; a status toggle under next-word
  suggestions puts the keyboard away and back first (`uat.py` `expand_toolbar`). A Backspace key event
  from the screen tells the engine nothing (its cursor report is predicted, `EditingSession.backspace`);
  a hardware one, or Enter and the arrows, does. The 符 and 123 keys
  resolve what is typed and reset fcitx before quick input or Unicode, as `commitAndReset`. Keys typed in one step
  are 200 ms apart on the replay's clock (`HostReplay.TAP_MILLIS`) and on the device (`parity.py`
  `KEY_GAP`, Tapper's sleep between taps): the refine after each key runs out before the next on both, so
  the tool makes no timing difference of its own. Keep the two equal.
- **Comparison**: `ime-eval parity-compare` — device against device every step and call identical;
  `--jvm` against a device allows one swap of neighbouring candidates whose sentence-model score
  difference is under ε, listed apart, the rest of that sequence uncompared. A step read busy (the
  recorder's idle wait ran out) is device timing, an allowed difference (DECISIONS.md 2026-10-25): it
  is skipped and the next step's state compared, its calls held to the calls since the last idle step;
  a busy last step's difference is listed apart as `TIMING` and does not fail, when both outputs ran the
  whole sequence and an idle step before it was compared (busy throughout, or stopped short, still fails).
  `uat/parity/record.sh` does a whole round: this checkout's APK, then `--native-apk <native-last APK>`
  to run the native side too or `--native-out <native.jsonl>` to compare with a recorded one.
