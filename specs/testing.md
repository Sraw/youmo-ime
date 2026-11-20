# Testing

A change is done when its tests pass, the checks pass, and, where it touches what a user sees or
how the engine ranks, it has been measured or tried. Reporting "done" on a change that was only
compiled is not done.

## The layers

| Layer | Where | Run | For |
|---|---|---|---|
| ime-core unit tests | `lib/ime-core/src/test` | `./gradlew :lib:ime-core:check` (tests + animal-sniffer + Kover floors) | every decision and the whole engine; milliseconds each |
| Drawing engine tests | `lib/keyboard-scene/src/commonTest` | `./gradlew :lib:keyboard-scene:check` (the same tests on the JVM and in node, animal-sniffer, Kover floors 90/60) | parsing, cascade, placement, hit, marks; scene snapshots in `src/commonTest/data/snapshots` (qwerty in every form, the other boards in block, split and landscape; `SnapshotTest.noBoardWarnsInAnyForm` draws every board in every form; the layout maker's examples in block only) (and the texts `ThemeWriter` and `LookTheme` write, in `data/writer` and `data/look-theme`), written by `jvmTest -Psnapshots.record=true` and compared on both targets, so JVM and JS must agree |
| App JVM tests | `app/src/test` | `./gradlew :app:testDebugUnitTest` | glue that needs Android types: Robolectric where the framework is reached; the scene's wiring key by key against `app/src/test/resources/wiring/`, recorded from the old keys by `WIRING_RECORD=1 ./gradlew :app:testDebugUnitTest --tests '*SceneWiringTest' --tests '*NineSceneGesturesTest'` |
| App JVM tests at minSdk | `app/src/test` | `./gradlew :app:testDebugUnitTestAt29` | the same tests at SDK 29, Android 10, which must keep working (below) |
| Engine UAT replay | `lib/ime-eval/src/uatTest` | `./gradlew :lib:ime-eval:uatTest` (not in `check`) | the uat/ cases whose 预期 is what the engine types, through `Engines` with the app's own data: needs `:app`'s data tasks (minutes the first time), and a 2 GB heap; no network (its named keys are held to the host's `KeySyms`, which `KeySymsGoldenTest` holds to fcitx) |
| Tools | `lib/ime-dict-tool/src/test`, `lib/ime-eval/src/test`, `build-logic/convention/src/test` | `:lib:ime-dict-tool:test`, `:lib:ime-eval:test`, `./gradlew -p build-logic test` | data compilation, the evaluation, build tasks |
| Python tools | `lexicon/tools`, `lib/ime-eval/tools` | `python3 -m unittest discover -s lexicon/tools` (and `-s lib/ime-eval/tools`) | the lexicon and evaluation scripts |
| Static | all Kotlin modules | `./gradlew detekt` | syntax rules (type resolution does not work here); findings frozen in `config/detekt/baseline-*.xml` |
| Native host tests | `lib/sherpa-onnx/host-test.sh` | the script | sherpa-onnx's C++ helpers, compiled for the host |
| Instrumented | `app/src/androidTest` | CI (`emulator` label / weekly); locally `connectedDebugAndroidTest`, after the two packs are imported (`uat/tools/uat.py packs`; CI's emulator job does the same) | the real host, engine and keyboard (`KotlinHost`, `TestHost`): `HostTest`, `SoftKeyboardTest`, `TypingHostActivityTest`, `NativeMatrixKernelTest`, `HostConfigTest`, `HostEnglishTest`, `HostPreeditTest`, `SetupActivityDeviceTest`, `FileUtilTest`; `EngineEvalRunner` is the device runner behind `run-on-device.sh` |
| Evaluation | `lib/ime-eval` | see below | ranking: whether the first candidate is the sentence meant |
| On a device | the Android emulator (arm64) or a phone | by hand / scripts | what a user sees: layout, gestures, rotation, upgrade |

Instrumented tests one at a time, or again and again on one emulator: `connectedDebugAndroidTest` uninstalls the app when
it ends, and the imported packs with it, so install once and run through `am instrument` instead:
`./gradlew :app:assembleDebug :app:assembleDebugAndroidTest`, `adb install -r -t -g` both APKs, `uat/tools/uat.py packs --port <port>`, then
`adb shell am instrument -w -e class org.fcitx.fcitx5.android.input.SoftKeyboardTest#<test> io.github.sraw.youmo.debug.test/androidx.test.runner.AndroidJUnitRunner`
(`OK (1 test)` on a pass). Kill uat's Dumper first (`adb shell pkill -f Dumper`): it holds the one UiAutomation. `am instrument`
force-stops the package, and with it the input method's service; `SoftKeyboardTest` binds it again before each run (its
`@Before`), as without that the field's show request failed on API 36 and no keyboard came up.

Minimum before a commit: `./gradlew :lib:keyboard-scene:check :lib:ime-core:check :app:testDebugUnitTest :app:testDebugUnitTestAt29 :app:lintDebug detekt --continue`,
and `cd uat/tools && python3 -m unittest test_uat` when `uat/` changed, plus
the tool or build-logic tests (`./gradlew -p build-logic test`) if those changed. Unit tests need no NDK or CMake.

Gesture records (`app/src/test/resources/gestures/*.txt`, `GestureSnapshot`): what each key of a board does, line by line; recorded anew by `GESTURES_RECORD=1 ./gradlew :app:testDebugUnitTest --tests '<class>'` (review the diff before committing). `WindowThemeTest`'s T7a pictures are drawn only with `WINDOW_SHOTS=1`.

The app's test tasks run two test JVMs at once (`maxParallelForks`, `-PtestForks=<n>` for another
count; `1` to rule out one test's state reaching another), each a fresh one every 40 classes. A test
that leaves a preference, a file or a static set where the next test in its JVM reads it fails only
in some orders: `KeyboardHarness.close` puts back what a harness test may change, and a test
that moves the floating keyboard puts its place back in its own teardown (`FloatingUatTest`, `FloatingCodeStripUatTest`, `FloatingPlacerTest`,
`FloatingControllerTest`; `KeyboardHarness.close` cannot, `FloatingUatTest` reads the place a closed
harness left; `PrefsRule` sets the place, the 竖屏 and 横屏 places and widths back to never kept at each test's start), and a screenshot of the floating keyboard pins the place it is drawn at. Memory the same way: a JVM runs 40 classes in one 3 GB heap, so a harness's
close lets go of every Android object it made (the input method's window, its field's activity, a
`SettingsHarness`'s MainActivity destroyed: one alive keeps its views in the main thread's GapWorker
list for the rest of the JVM), held by `HarnessLeakTest`; an OOM ends the JVM and leaves its heap in
`app/build/test-oom/<task>/<run>/` (the newest two runs kept; `-PtestGcLog` writes the GC's log beside it). `-PskipExhaustive` leaves out `KeyMarksEverywhereUatTest` (every key
of every keyboard, every pair of mark places; half an app run's time): CI's app jobs do, and
`key_marks.yml` runs it alone, at 35 and 29, when the keys' code changes and weekly. Run it locally after a
change to how keys are laid out or marked (not with `-PskipExhaustive`: the exclusion wins over `--tests`).

## Writing tests

- A key on the keyboard is a `ShownKey` (060-render-p2 S3): `KeyboardHarness.key(label)`,
  `keysLabelled`, `keyNamed(id, copy)` (the scene's id, a view's theme name: `q`, `space`, a split
  row's second G copy 1), `describedKey(description)`, `keys()`; tap, press, long press, swipe, drag
  take it, by its place in the window: the canvas's scene key (`SceneShownKey`). A keyboard made
  alone (no input view) is read through `shownKeys()` once laid out (`laidOut()`, BoardKeys.kt). `keyWithId(R.id.button_*)` finds a key by its view's id, the scene's node's resource
  name; `drawn(text)`, `textSize(text)`, `drawnTexts` and `drawnIcons` read what is drawn on a key
  (a scene's text's box a guess from its size: compare places, not pixels). A popup's keys are
  `popupChoices()` (`focused`, `cell`, `size`) and a menu's icons `shownIcons()`. The tests that
  compared the scene with the key views keep the scene's side since S8a deleted the views
  (`*SceneGesturesTest`, `SceneTouchTest`, `SceneSpeechTest`).
- New logic gets a test in the same change, in ime-core where the logic is.
- Prefer fakes over mockk. `FakeEditor` (ime-core `testFixtures`) stands in for an
  `InputConnection` and is held to the real `BaseInputConnection` by a contract test. Its app-side
  actions (`appSetsText`, `appReplace`, `appDropSpan`, `appSelect`: an app's own edits, a `TextWatcher`'s,
  a selection): `appReplace` and `appDropSpan` are held there too (`InputEditorContractTest.appEdits`),
  `appSetsText` and `appSelect` not yet; its `rewriter` (an `InputFilter`
  upper-casing or cutting the whole text) and `isAvailable = false` (a connection the framework closed
  after the app finished the text) are the fake's alone: a closed connection's dropped calls are the
  framework's wrapper, not `BaseInputConnection`'s, and were measured on devices (061-code-in-field M2, M3).
  `CodeInFieldPropertyTest` drives them at random against the code in the field.
  `FakeHostApi` (app test sources) cannot be held to the real engine (JNI), so keep it dumb:
  store config, record calls, simulate no input.
- The engine's user files: `MemoryFiles` (ime-core testFixtures), a `UserFiles` in memory that
  behaves as a disk does (a write needs its directory, a rename replaces, an appender on a file
  renamed over writes no more). Use a `TemporaryFolder` with `JvmFiles` only where the bytes on the
  disk, a symbolic link or a directory the disk refuses to write are what is tested: the record's
  byte golden, the upgrade tests, `RecordStore`, `CodedIndex`, `DictionaryFile`.
- `unitTests.isReturnDefaultValues = false` on purpose: a JVM test that reaches an android.jar stub
  fails loudly. Anything needing the framework runs under Robolectric.
- Android 10 (API 29, minSdk) must keep working. Every debug Robolectric test runs at SDK 29 and 35
  (`robolectric.properties`), one SDK a task and a JVM (`robolectric.enabledSdks`): the 35s in
  `testDebugUnitTest`, the 29s in `testDebugUnitTestAt29`, every test drawing natively there
  (one sandbox a JVM: Robolectric's native runtime goes into the first sandbox's class loader only).
  The normal tasks enable 30 to 36 alone, so a pin outside 29..36 runs nowhere, unreported. `RobolectricSdks` fails
  if 29 leaves `robolectric.properties` (the At29 task would pass with nothing left in it).
  Screenshots and the theme maker's testdata are compared at 35 alone (`SCREENSHOT_SDK`); at 29 a
  capture still lays out and draws. A test of behaviour that only exists from some level on pins
  `@Config(sdk = [35])`, as does a check that is itself above 29 (`View.hasOnLongClickListeners`).
  Two layers, each catching what the other cannot: lint's `NewApi` is fatal (a release build's
  lintVital fails on a call above minSdk), but lint does not see an implicit cast to an interface a
  class implements only later (`TypedArray.use {}`: AutoCloseable from 31), which only running the
  code at 29 shows. 0.5.1 shipped both kinds (`e1e5c7489`). On a device: the AVD `youmo29`
  (`UAT_AVD=youmo29 UAT_API=29 uat/tools/avd.sh`, then `UAT_AVD=youmo29 uat.py up`), no test page
  browser there (the WebView shell needs 34), the debug build's `TypingHostActivity` as the field;
  CI's instrumented tests run at API 29 and 35.
- A class reading `AppPrefs` in its initialiser needs `AppPrefs.init(sharedPreferences)` in the test
  (a relaxed mock is enough); the error message says so.
- 键盘样式 in an app test: `PrefsRule.setStyle(style, landscape = null /* both */, side)` and
  `setStyleDefault()` (each shape's default by its width), or `TestStyles` (uat/) outside the rule, which
  writes what is under before the style, as the service and the input view are told of the style. Put
  back with the store after the test.
- Test the behaviour a user would notice, named so ("a coarse word the user typed is not lowered"),
  with the case that bit as the example. A test that only restates the implementation is not worth
  its maintenance.
- Engine tests build small data in memory (`PinyinDataBuilder`, text tables) rather than loading the
  real model (`pinyin.data`); the evaluation is where the real data is judged.
- 生僻字屏蔽's tests, what each covers (`./gradlew verify` runs the first two, not the third):
  - `RareCharContractTest` (ime-core, small data): 全拼, 双拼, the nine keys, the pinyin glide,
    strokes (`u`), parts (`u`), 五笔 typed and glided, predictions, handwriting over pinyin's keys, the user's own word;
    hold → let through → hold on one `Engines`, with a gate made directly (so even 的 is held) and one
    through the default choice, a character of the basic plane and one beyond it (a surrogate pair).
    Not every `LISTED` method: the other tables, 注音 and the 14/18 keys go through the same session
    code as 五笔 and 全拼. A new place a candidate comes from is added here.
  - `HeldWordsTest` (ime-core): the gate by code point, the dictionary's words by id, the user's,
    imported words and learned whole phrases (continuations) by text.
  - `RareCharsUatTest` (ime-eval `uatTest`, the real data, minutes): every `LISTED` Chinese method
    with every group held (注音, 双拼, the 14/18 keys, every table, handwriting over their keys), the real table's edges, 五笔's
    auto-commit, 借手机.
  The levels in tests are `TestCharLevels` (ime-core testFixtures); the app's JVM tests have no APK
  assets and pass them to `RareCharsManager.useLevels`.
  The page itself: `RareCharsPageUatTest`, `RareCharsCardsUatTest` (over `RarePageUat`'s grid), `GroupCardTest`.
- The card pages laid out (061-settings-cards §8.1): `SettingsCardsLayoutTest` opens 生僻字屏蔽, 按键字符和快捷键,
  its multi-select and a key's editor on the phones and font sizes that matter (360×640 and its 360×568 window,
  393×851 at 1.3, on the side; Chinese and English) and asserts what must show unscrolled is in the window, no
  name or count cut, the badge clear of the picture and the name, the editor's titles and OK in view with its
  buttons stacked or not. Robolectric has no status or navigation bar: a window is given as the screen's
  qualifiers. A change to a card's size or a page's top keeps it green.
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
`--keys nine|14|18`, the nine keys by default, `14` the 14 keys' codes read with `--scheme g14-strict`,
`18` pinyin's 18 keys' read with `--scheme g18-strict`, neither taking `--presses letters`; the schemes
`g14`/`g14-strict` and `g18`/`g18-strict` are `T9Segmenter` over `NineKeyLetters.G14` and `G18`, only
the `-strict` ones reading as the app does (its grouped sessions take no abbreviations); `release-report.sh` reports `g14-chat` and `g14-pinyin` at weight 0, as
`t9-chat`, never in the weighted score; `shuangpin <scheme> … --keys 18|14|nine [--same <kept.tsv>]` types
a set on 双拼's grouped keys, samples with a Latin letter left out, `--same` keeping the samples typed
as they were for the full keyboard to be scored on, read with `--scheme <scheme>-18`, `<scheme>-14` or `<scheme>-nine`
(`GroupedShuangpinSegmenter`; on the 14 keys a sample needing `;`, 微软's ing, is left out too); `sp18-chat` and `spnine-chat` (小鹤) are reported at weight 0 too; a `--keys` a command does not
take is a usage error, exit 2), `english-glide` (glide
typing on simulated paths, `--wobble` the key-width noise), `pinyin-glide` (the same per dictionary word
of a pinyin set; `--shuangpin <scheme>`, `--keys nine`, `--table <table.data>`, `--word <pinyin>` one
clean path's readings), `tune` (penalties); `pinyin --typos off` reads no common slip (`zhagn`, 双拼's `jv`), as with 纠错 off; `pinyin --touch <keyboard>:<hand>:<clean|slip>[:<seed>]` taps each key with a place (`TouchSim`: qwerty, qwerty-ms, nine, k14, k18 (laid out as GroupedKeyboard lays the 18 keys); a hand `s<σ>[down]` or `s<σ>/<dx>/<dy>/<halves>` in key widths), `clean` each in its own key, `slip` one key a sample landed on a neighbour, and `--touched <set.tsv>` writes the set so tapped, each key's `Touch.text` in a fifth column, for `run-on-device.sh` to type the same (061-neighbour-correction); on k14 and k18 every tap goes with where across its key it fell, to the nearest of the app's 17 places (`TouchSim.placed`, as the app sends it, a key of one letter's for a slip from it; 061-neighbour-18key), in a sixth column of `--touched` for `run-on-device.sh` to send as the keys do, and `--taps <hand>:<α>[:<seed>]` taps the 14 or the 18 keys aiming at the letter meant (`Taps`, k14-tap-position); a slip floor is picked by the clean gate (the user's 2026-11-16): typed on target is a tap in the key's middle, not by its edge, so `clean` at the hand `s0.15` (95% of taps in the middle 60% of the key), seeds 1–3, both halves, must turn no sentence or word wrong with the slips read against without, while recovery is measured on `slip` at `s0.2` and `s0.3` (061-neighbour-correction's amendment), `score` (compare a result with a baseline), `handwriting` (handwriting's sentence reading), `table` (a table's codes, optionally with pinyin and the models), `table-slips` (a table's codes tapped as `TouchSim` taps QWERTY, clean and with one key slipped, each word in a fresh session, with the slips read and without: what was typed on target and changed (0 at σ 0.15 at the topping floor −1, the loosest so, by the user's 2026-11-16 rule; 9 of 315 000 at σ 0.2 and 0.3), the slips that made an empty code and how they went off → on, right / wrong / none; each word's code then the next word's first key, as a typist types on over a code that waits; `--floor` the `SlipCost` floor, `--topping-floor` the floor where the key would commit what is shown (顶字, or a code read so that waits), `--plan prefix` each word typed by the shortest part of a code that shows it first (a whole code too) then the next word's first key, as one who watches the candidates types, `--words <pinyin.data>` the app's word model for the tables that have one (as the app ranks and weighs a topped key's neighbour; without it no word is weighed), `--touched` the taps as a set for `run-on-device.sh`; 061-neighbour-table), `sentences` (each sample's sentences written out), `table-offers` (how often a table offers a word to remember, `TableWordOffers` with the real word boundaries, every character typed alone: after each character, after a sentence's last, and `--names` typed alone offered whole; rerun as its rules change), `predict-learn` (联想 after learning; `--continuations off`: without the whole phrases, U-73), `learn-log-bench` (a heavy year's learning record opened, folded and opened again; `--counts <pinyin.data>`: what the ranking's counts cost on that record, attached, made anew and per key typed), `predict-set` (a 联想 set made from a set), `history` (a history of the dictionary's likeliest words, for `pinyin --history` and `run-on-device.sh`'s `HISTORY`), `shuangpin` (a set turned into a 双拼 scheme's keys), `slips` (a set with slips put in; `--scheme <双拼 scheme>`: typed in that scheme, its fuzzy partners, ü typed with its own key and a key slipped onto its neighbour), `chars` (the data's characters), `lm` (a sentence model's scores of texts);
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
wins (McNemar, Bonferroni-corrected), learning must bring back what was typed, and the candidate readings (候选词注音) must read
`data/readings/chat.tsv` right at `ReadingsRun.GATE` (98%; `readings --gate`).
A held candidate's card reads it as the bar does: `EnginesWordCardTest` (each keyboard's way to 银行, on the
fixture) and `WordCardUatTest` (`:lib:ime-eval:uatTest`: the first 100 of `pinyin-chat.tsv` and every
`misreadings.tsv` word, both styles) hold the card's readings to `Snapshot.readings`, a code point at a time.

`run-on-device.sh` runs a set through the APK's own engine on a device, for checking that the app
decodes as the tool does (`NINE=1`: a set of digits typed as pinyin's nine keys send them; `GROUPED=14` or `18`: a
set of pinyin's 14 or 18 keys' codes as those keys send them; `sp18`, `sp14`, `sp9`, `zy9`, `zy`: 双拼's 18, 14 and nine
keys' and 注音九键's and 大千's alike; `HISTORY=<words.txt>`: typed on that history, pushed to the app and put in
place of its learning record, as `pinyin --history` counts it, `HistoryLines` in ime-core for both, for timing what
hangs on words typed; a history from the tree is `ime-eval history <pinyin.data> <n> <words.txt>`).

Roborazzi goldens (`app/src/test/screenshots/`) show the bar with its microphone and the space's
voice hint: since 0.6.0 there is one build, and voice is everyone's default (the voice pack's absence
is a notice, not a hidden button).

The resource packs in JVM tests: `TestShelves` (app test sources) makes tiny packs signed with the
committed test key (`TestPacks`, ime-core testFixtures) and installs them through `PackStore.useShelf`;
`KeyboardHarness` installs both, so a keyboard test sees the keyboard, not the gate. A test of the
gate or the voice pack's notice takes one away (`TestShelves.use(text = false)`, `PacksUatTest`). The
import's checks (signature, a file's hash, format, space, a killed import, the same pack again, voice
before text) are `PackShelfTest`'s in ime-core, on real zips in a temporary directory. Engine-data
tests (`TablesSettingsUatTest`, `:lib:ime-eval:uatTest`) read the build's generated data as before:
the packs are made from those same directories.

Every app JVM test runs with 编码行 = 嵌入 (`OneDefaultPreferencesShadow`), not the floating strip a new install gets: this keeps 0.5.0's goldens and geometry. A test of the floating strip sets it itself and puts it back.

`ThemeLookScreenshotTest` draws five sample themes (colours, a frosted picture, the picture cut out
by the keys, nine-slice images with fonts, an image per key), light and dark, on eight boards in
portrait, one-handed, floating and turned split; its pictures and images are made in the test. Only
a few of those 320 are goldens (each theme's 26 keys, every board of the per-key theme, one of each
mode). `-PthemeShots=<dir>` writes all 320 there as full-size PNGs, for looking through after a
drawing change (the goldens still compare).

## Trying it on a device

Unit tests do not see the keyboard. Anything a user sees or touches (layout, a gesture, the bar,
a settings page, rotation, a field type, an upgrade) is tried on a device before it is called done.

- **The Android emulator** is the default (since 2026-11, development on an Apple-silicon Mac;
  redroid, Android in a Docker container on a Linux host kernel, was until then): the AVD `youmo`,
  android-36 google_apis **arm64-v8a**, native under Hypervisor.framework, 1080x2340 at 440 dpi with
  three-button nav as redroid had (the scripts' coordinates hold), booted headless from a snapshot
  read-only in ~5 s, so every boot is a clean device and two run at once (2.6-3.9 GB resident each).
  It runs the release's own ABI (build `-PbuildABI=arm64-v8a`), voice included, and
  has its own kernel: the hardware keyboard (a uinput one, `uat/tools/emu-keyboard.sh`) is tested on
  the same device as the rest; a uinput keyboard is still made nowhere but an emulator. Install,
  `ime enable` / `ime set` the service, drive it with `adb shell input tap/swipe` at measured key
  coordinates, read the field back, screenshot the bar. Scripted, that is `uat/tools/uat.py`
  (below); `uat/tools/avd.sh` makes the AVD and its snapshot `clean`. Root via `adb root`.
  Voice (UAT 12) is said with clips made by `uat/tools/voice-clips.py` (Kokoro, offline, into
  `dev/uat-out/voice-clips/`, not in git), played by the debug build's clip microphone (the voice pack imported):
  `VoiceClipMic` stands in for what `VoiceListener` reads from the AudioRecord (still opened,
  started and released; the permission still checked), a window at a time at the microphone's
  pace, the queued clips and pauses in order, then a faint noise (never exact zero) till the talk
  ends, so the endpoint, the live text and the level run as on a phone. The shell drives it
  through `VoiceClipProvider` (debug source set only, shell or root uid only): `content write`
  a clip into the app's files, `content call --method queue --arg a.wav,800,b.wav` (on, clear,
  state too); `Device.say` does both. It is reached only under `BuildConfig.DEBUG`, so R8 drops it
  from a release. What it cannot test, left to a phone and a person: a real microphone and its
  HAL (AudioRecord's failures, its timestamps, the zeroes before it opens), noise and distance, accents.
- A real phone (arm64) for performance, voice latency and a real microphone.
- Performance numbers are taken on the **benchmark** build type, not debug: release's R8, not
  debuggable (a debuggable app runs its code interpreted and JIT'd differently, its frames up to
  twice as slow and noisier), signed with the debug key, id `io.github.sraw.youmo.bench`, with debug's
  typing host (`TypingHostActivity`) and its pack provider (import the text pack as for debug: it trusts
  the test key as debug does), no unit tests of its own. Instrumentation
  runs against it as against debug (same key):
  `./gradlew :app:connectedBenchmarkAndroidTest -Pyoumo.testBuildType=benchmark
  -Pandroid.testInstrumentationRunnerArguments.class=org.fcitx.fcitx5.android.input.KeyboardPerfBaselineTest
  -Pandroid.testInstrumentationRunnerArguments.perfRuns=20`,
  its rows (`KeyboardPerf` in logcat) read with `adb logcat -d -s KeyboardPerf:I`: median, p90 and the
  mean of all samples, ms, and what each step allocated (`Debug.getRuntimeStat`'s
  `art.gc.bytes-allocated` and `art.gc.gc-count` read before and after it, the process's, a press's
  shared by the keys of its run) as KiB and GCs. Two builds' numbers are
  compared only from one emulator session, in at least three pairs, the order alternated (one first,
  then the other, ...): a run's medians move by up to 30% from session to session, and neither
  side should always be the one run on the emulator longer up. The canvas's gates are in specs/app.md.
  `uat/tools/perf.py A [B]` does all of that for two commits or trees: builds each as benchmark with
  its text pack, runs them alternately (A1 B1 B2 A2 ...), installs and imports the text pack through
  `PackImportProvider` each run (not the connected task: it uninstalls the app and the pack with it),
  keeps the `KeyboardPerf` lines and writes a table of both and B against A; `--trace` builds profileable
  and sums the `canvas anim` sections, `--lock DIR` holds a mkdir mutex for each run on a shared device.
  `uat/tools/perf_picture.py` makes the animated picture of the A4 rows (`perf-picture.webp` in
  `app/src/androidTest/assets`).
  Systrace sections of the app: `adb shell atrace --async_start -c -b 32768
  -a io.github.sraw.youmo.bench view`, the test, then `atrace --async_dump` (a non-debuggable app's
  `Trace` sections show only when it is named with `-a`).

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

The runner, `uat/tools/uat.py`: `up` / `down` (two emulators, emulator-5554 and emulator-5556, from the
AVD's snapshot), `run [--areas] [--ids] [--devices]` (`UAT_BUILD=release`: on the signed arm64
release APK, the debug package uninstalled first and installed back only for the cases that type into
its `TypingHostActivity`; the canvas is the app's default, so no switch is written), `mark` (a visual case after its sheet is seen). One
script per case file (`uat/scripts/<file>.py`; handwriting's strokes in `hw_strokes.py`); a case in a
.md with no script is reported blocked (没有脚本), never left out, unless it has a **JVM** line: then it is a
`jvm:` case, its tests the ones carrying its `@Uat`; a case whose `needs=` names another
place (a run without the voice pack, a release by hand, a real phone, the JVM) is listed as 在别处跑, not counted
as skipped. The UI is read back by a resident `Dumper.java`, which waits for the screen to settle, so
the runner polls for what it waits on rather than sleeping; visual cases go on contact sheets, reviewed
per `uat/tools/sheet-review.md`. The debug build's `TypingHostActivity` is the native field for what the
WebView test page cannot set (an IME_ACTION_NEXT field, no personalized learning, a sensitive clip).
The composing row is read by its ids (`Device.code_row`, `preedit` and `syllables`: `code_row_text`, and
`code_row_syllables` for the 14 keys' syllable strip), not by its class: the strip scrolls too.
The two emulators share the Mac's cores unpinned (redroid's per-device `cpusets()` once took the
steady cases from 30/45 to 39/45, 2026-10-23); how flaky the timing-bound cases are on the emulators
is not measured yet: before taking a case that fails then passes for an app bug, compare
`uat.py flakes` over a few two-emulator runs (and `UAT_SLOW=1`), and record the counts here.
Details in `uat/README.md`.

Release gates 2 and 3 of the host replacement (0.4.0) (in-place upgrade from the `uat/fixtures/upgrade`
releases, backups both ways with them) are one command each, `uat/gates/gates.py upgrade|backups`, run
before every release from 0.6.1 (DECISIONS.md B14; gate 2 is paused, see below), each on uat.py's emulator of its own (`--port`,
default 5560, up from the clean snapshot and down after); the new build gets the text pack after the
fixture's files are in place. Results in `uat/runs/` (`uat/gates/README.md`). A `.debug` build is
upgraded by putting the fixture's files into its own directories and gets backups retargeted to its id;
the release APK is upgraded with `install -r`. Gate 5 (start, latency, memory side by side) is gone:
`uat/tools/perf.py` measures that. `uat/fixtures/upgrade/make.py` still exits (made for redroid): the
fixtures are the ones it made on 0.3.x/0.4.0, an in-place upgrade this build no longer supports, so gate 2
is paused till there are 0.5.x fixtures.

## The testbed app (`:testbed`)

`testbed/` is a separate app, `org.fcitx.fcitx5.android.testbed` (输入测试台), with every kind of
native field (input types, imeOptions, focus chains), app behaviours an IME meets (clearing on send with
and without `restartInput`, rewriting TextWatchers, timed `setText`/`setSelection`, an InputConnection
that answers null, TYPE_NULL, dialogs, a shared view id) and a local WebView page, one page each,
opened by `--es page <name>`. It is a tool, not shipped and not depended on; framework Views only, so
it adds nothing to verification-metadata, and minSdk 29 like the app (the WebView test page's shell
needs 34). Each page logs its fields' events to a folding panel and to logcat (tag `Testbed`, JSON);
its debug build's `StateProvider` hands the fields' text, selection and composing span to `adb shell
content query` and to no app (it answers the shell and root uids only). `uat.py`'s `Device.testbed(page)`
and `testbed_state()`; pages and fields in `uat/TESTBED.md`. Compose fields are not there yet (no
Compose dependency in the repository).

## The theme maker's shared files (`web/theme-maker/test/testdata/`)

In 0.5.x the app and the user docs did not link the page; from 0.6.0 they do (新增主题 → 在网页上制作, `Const.themeMakerUrl`).
Its tests below run either way: the app reads the same format.
UAT cases that need a theme file take `from-app.theme` from this directory and rewrite it in python (as `push_theme` does), as each says.

The theme maker page reads the app's theme files (a 3.0 one it opens and makes 4.0), so the app makes
the files its tests read (`ThemeMakerRulesTest`): `migration.json` (a 2.1 theme, a whole one and one
missing what an older 2.1 left out, and each brought to 3.0 as the app brings it), `theme.json` (a 3.0
theme with everything), `from-app.theme` and `from-app-2.1.theme` (an export of this app's and of one
before 3.0). Each test compares its file with the repo's and fails on a difference;
`./gradlew :app:testDebugUnitTest --tests '*ThemeMakerRulesTest' -PupdateThemeMaker` writes them again,
and the page's own test then shows what it must follow.

The page's side: `node --test web/theme-maker/test/format.test.mjs` (Node 24; CI's unit test job and
the Pages workflow run it) loads `src/lib.js` as the page runs it and holds it to `migration.json`
(2.1 → 3.0 as the app does it), reads `theme.json` and writes it again as the app writes it, opens both
`from-app` theme files and refuses what the app's import refuses (the zip's limits, names, image sizes).
`from-web.theme` is the 3.0 file of `theme.json` the maker before 0.6.0 exported (that page is gone since
0.6.3; the file stays as it was).
`ThemeMakerFormatTest` (app JVM) closes the loop: `from-web.theme` imports as `theme.json`, and an app
export of it imports the same; and the maker's generated block `youmo-format` (the file's version, every
type's fields in the order written, held to what keyboard-scene's `ThemeLookReader` reads and its
`ThemeLookWriter` writes: each field there, none left out, none more, every kind of each sealed type and
every value met; the limits; the defaults) is what the app has, through `ThemeMakerTestdata.block`, which
`-PupdateThemeMaker` lets write it, to `web/theme-maker/generated/<id>.json` (in git; the pages read
them from there). The test task takes `web/theme-maker` as an
input, so a change to the page reruns it. Nothing in those blocks is edited by hand.

Two pages: `index.html`, the maker (060-render-p3 M1) (`src/`: `page.html` with slots for the rest,
`style.css`, `maker.js`: the document, held to the engine by `test/maker.test.mjs`, `form.js` and
`panels/*.js`: the editing's forms (M2: one file a tab, put in as one script in their names' order),
held to the engine by `test/form.test.mjs`, `ui.js`: the page), and `examples/` (`examples.json` and a folder of each example, put in the
maker's `youmo-examples` block); and `layout/index.html`, the layout maker (061-custom-layout §12; `src/layout/`: its `page.html`, `style.css`, `layout-maker.js`, `layout-edit.js`, `ui.js`, with the maker's engine, `lib.js`, the theme file's format, `form.js` and `maker.js`: a `.theme` opened is read as the maker reads it), its examples `examples/layouts/` (`layouts.json` and a `.layout` each, put in its `youmo-layout-examples` block as they are, with `name=` as the label; `ExampleLayoutsTest` in keyboard-scene checks each clean and holds its scenes, upright, turned, compact and split, the number one in each of its pads, to `src/commonTest/data/example-layouts/` on the JVM and in node). The maker and the layout maker run `src/lib.js` (everything the tests hold to the app's
3.0 file and zip). Neither page is in git (R25 B12): `node web/theme-maker/tools/assemble.mjs` makes
them from their sources (`web/theme-maker/.gitignore` keeps them out): the sources put in, each page's
generated blocks read from `generated/<id>.json` (each carries only the ones its `page.html` has a slot
for: the maker `youmo-format`, `youmo-engine-icons`, `youmo-bar-icons` and `youmo-examples` (from `examples/`), the layout maker `youmo-layout-examples`, `youmo-format`,
`youmo-engine-icons` and the engine), and the
Content-Security-Policy's `script-src` set to each script's SHA-256 (six on the maker, seven on the layout maker;
nothing else runs, a script not hashed is refused). The pages' tests (`maker.test.mjs`, `layout-maker.test.mjs` for the layout page) assemble them in memory and fails
when a generated block is missing or the CSP's hashes are not its scripts'. Commit `src/` and
`generated/`; CI, the tests and `dev/serve.mjs` assemble the pages.
`test/maker.test.mjs` (node, needs the built engine; CI runs it with the other two): a document
saved opens the same (theme.xml first, files, `layouts/` kept as they are), 3.0 and 2.1 files open
as 4.0 through the engine's `convert30`, undo and redo (at most 100 back), the draft kept and given
back (a failing store is no draft, never a throw), every board and form of the preview's
environment builds; a picture picked always encoded anew (`PICTURE_SAVE`: long side ≤ 2560, JPEG
0.85, PNG only with transparency; one not an image refused before a decode), only images within the
app's limits decoded, save refusing what the import would (`saveProblems`), a pack's odd entries
opened as the app unpacks them. `test/form.test.mjs` (the same): each tab's form operations write
the style text meant (a form edit changes the `<style>` of exactly that `select=`, the last that gives
the property, else adds one at the end; a style left with only its `select=` goes; what the form does
not know, comments and spacing kept), the engine reads every result, and the cascade check
(`YoumoEngine.sources`, `Cascade.sources`) names the style each value comes from; the forms write numbers within the engine's bounds (`YoumoEngine.limits`), and an icon the app would refuse (a PNG, an SVG past `SvgSubset`, which the engine's `readIcon` runs) is named and blocks the save. `from-maker.theme` and `from-maker-picture.theme` (the panels' save: a real PNG cut for each orientation, see-through keys, a gradient, an SVG icon) are
its saves, which `ThemePackImportTest` imports. `test/names-and-files.test.mjs` (the same): names and files as theme.xml has them: a name with XML's special characters, values with `>`, which files are still named, the effect images the app checks (≤ 256 px a side, size limit, .png/.webp only), theme.xml's size limit, the draft's files written once. The
site is `index.html` with the engine put in: the Pages workflow leaves `src/`, `tools/`, `test/`,
`fonts/`, `examples/`, `generated/`, `dev/` and `build/` out and writes its `index.html` (and `layout/index.html` beside it) with `assemble.mjs --engine <file>`; the workflow fails where the layout page lacks its script, its examples or its engine.
`test/layout-maker.test.mjs` (node, with the others) holds the layout page's document, editing and examples to the engine, a `.theme`'s boards offered as the app's import makes them (`themeBoards`), and its size to 2,100,000 bytes; `node web/theme-maker/test/layout-smoke.mjs` (CI's unit test job, Chrome as panels-smoke) drives the page in a browser in a 1400 px wide window (`cdp.mjs`) and at 360 px: new from a base and from each example, editing, save, help, a `.theme` opened (its theme the preview's, a board of it started from).

### The engine in the page (060-render-p3 W1)

The page carries `lib/keyboard-scene` as a library: `jsMain`'s `YoumoEngine` (`@JsExport`: read a
theme 4 or a layout with problems as values, build a scene or a popup in an environment given as
JSON (`scene/json/EnvJson`), paint it on a 2D context (`web/CanvasPainter`, ScenePainter op for
op), convert a theme 3.0, write a theme, the built-in layouts and themes (`web/BuiltIns` over
`BuiltInFiles`, which the `builtInFiles` task writes of `src/commonMain/layouts` and `themes`), the
browser's `TextMeasurer` (`web/CanvasMeasurer`, measureText's boxes), and where a key gets each
property (`sources`: the line of the winning style, `Cascade.sources` laid over as `Cascade.of` is;
the maker's cascade check)).
`./gradlew :lib:keyboard-scene:youmoEngine` writes `build/youmo-engine/youmo-engine.js` (UMD, global
`youmo-engine`; indents and the source map's line dropped, `<!--` and `</` escaped). The engine is not
in git: the page assembled without `--engine` has `NO_ENGINE` (a comment) in its `youmo-engine` script. `node web/theme-maker/tools/assemble.mjs --engine [out]` writes
the page with the build's engine in that script and the CSP's hash of it (`out`: `build/index.html`,
not in git); the Pages workflow publishes that, and `dev/serve.mjs` serves it at `/page.html`. The
four subset fonts (`fonts/`, `tools/subset-fonts.py`: Roboto and Noto Sans CJK SC from Robolectric's
runtime, cut to what a keyboard shows, 160 KB) are in git, in a `youmo-fonts` style as data URLs: they
depend on no engine code, as `youmo-engine-icons` depends on the app's drawables only. The page's test
holds the page assembled without it to `NO_ENGINE`, and the page assembled with the build's engine (in
memory) to the build, to 1.2 MB, to its scripts and their hashes, and to the other but for that script; CI builds
the engine and assembles the page before the node tests, in the unit test job and the Pages workflow.
A page that uses the engine needs it put in: opened straight from the repository, `index.html` has none.

What holds the library to the app:
- `FacadeShotsTest` (commonTest) writes `data/facade/shots.json`: how each snapshot was asked for (the
  board, the environment as EnvJson, the theme as a recipe of a test file, its styles and fonts; a
  popup's request), and `data/facade/look-3.0.xml`/`look-3.0-files.txt`: the test theme 3.0 made 4.0
  by `LookTheme`. Recorded with the snapshots (`-Psnapshots.record=true`), compared otherwise.
- `web/theme-maker/test/engine.test.mjs` (node, needs the built engine; CI's unit test job runs it
  with format.test.mjs) builds every snapshot through the facade from shots.json and compares
  `scene.text()` with it, converts look-3.0.json, writes a theme, reads layouts with and without
  their fragments, and paints each scene of `testdata/paint.txt` on a recording 2D context.
- `paint.txt` is the app's `EnginePaintTest` (Robolectric, NATIVE graphics):
  ScenePainter's calls on a recording Canvas for 20 shots, normalized to device pixels to a tenth
  (fills, fills less a shape, blurs as their sigma, strokes, text with its weight, size, squeeze,
  spacing, stroke and shadow, icons by name, clips); the node test compares them line for line, a
  tenth's difference allowed (floats against doubles). Its recording context makes the painter's
  layers and writes what is drawn on one as it is laid (a shape cut out of a fill its `less=`, the
  alpha laid with the colour's, a shadow's colour and blur). A `blur=` holds the painter's sigma
  arithmetic to the app's and no more: both sides write the sigma from the op's radius by the same
  formula (the app's line from the op, not from Skia's mask, which a Canvas does not show), so the
  comparison cannot see a blur drawn wrong; the pixel smoke below does. The test also writes the icons the scenes name
  into the page's `youmo-engine-icons` block and each shot's picture to `app/build/w1-pixel/`.
  `-PupdateThemeMaker` writes them again.
- The nine-slice, the picture, a gradient and a ripple, which no snapshot has, are `jsTest`'s
  `CanvasPainterTest` (NineSliceCut held to NineSliceLayout's cases), and the layers' calls: a shape
  less a path that crosses itself (cut by the nonzero rule, as Path.op's DIFFERENCE of winding fills),
  an inner shadow, outlined text not opaque laid once, an outlined text's shadow, letters set one by
  one on a canvas without `letterSpacing`, an icon's stroke defaults; `BuiltInsTest` holds the built-in
  files to `src/commonMain`'s and the measurer to its cache (the last 1024 asked for; none kept while
  its font is not in: a page awaits `document.fonts` before it builds), the page's fonts' extents
  (their sources' head yMax and yMin, Android's FontMetrics top and bottom) and fallbacks.
  `ScenePainterGradientTest` (app, NATIVE graphics) holds the app's gradient to its colours in pixels.
  `VectorIconJsonTest`: a drawable the page would draw otherwise (a clip-path, a path of no colour or
  of a fill and a stroke, a stroke's alpha, cap, join or miter limit not VectorDrawable's default) is
  refused when the icons are written.
- `node web/theme-maker/test/pixel-smoke.mjs` (local, not CI: needs Playwright's headless shell or
  `CHROMIUM=`) draws six groups of shots in Chromium through `dev/engine.html` and compares them
  with `app/build/w1-pixel/`: under 2% of pixels off by more than 48, under 0.2% in 5x5 areas off by
  more than 16 (the plain board against the app's with q pressed must fail it, and does).
- `node web/theme-maker/test/panels-smoke.mjs` (CI's unit test job on every push and PR, with the runner's
  Chrome, DECISIONS.md B13; locally the same Chromium as pixel-smoke) drives the maker's 字和图标 tab by mouse and by touch: a field edited, then the next field typed into or a box tapped, both kept (a value edit updates the controls in place, never draws the tab anew); after undo with a field focused every field shows the document; a colour row after undoing a rename edits the colour it names, and the crop shows a picture replaced by one of its size (a row of another name, a box of another data-key is put in whole). After 用这个裁切 replaces its box the focus stays in the tab; the tabs 栏, 弹出, 面板和窗口, 尺寸, 动效 and 信息 each draw, an 信息 edit keeps the next field focused, 试一下 plays, and the environment's typing bar grows the preview.
- `node web/theme-maker/dev/serve.mjs` serves `dev/engine.html`, a preview of any board, theme,
  size and form drawn by the engine; development only, never published.

## Custom layouts (061-custom-layout L1–L8)

The `.layout` format and its check are keyboard-scene's, held alike on the JVM and in JS: `LayoutReaderTest`,
`LayoutWriterTest`, `LayoutCheckTest` (every finding's key, by `LayoutCheck.KEYS`, said by the app's strings and the
page's table), `LayoutCheckThreadsTest`, `ScaledRowsTest` (`split-scaled`), `PackConvertTest` (a theme pack's board made
a layout of the user's), `ExampleLayoutsTest` (the page's examples). ime-core holds what the app decides with them:
`LayoutImportTest` and `LayoutImportGroupedTest` (an import, its warnings and refusals), `LayoutCheckHeightsTest`,
`CustomLayoutFilesTest`, `CustomLayoutChoicesTest`, `ImKeyboardsTest`, `PanelLayoutsTest`, `KeyShortcutPagesTest`,
`RenamedKeysTest`, `ThemeLayoutSwitchTest`. The app's: `CustomLayoutsTest`, `CustomKeyboardTest`, `BackupLayoutsTest`,
`PopupOverridesLayoutTabTest`, `SceneLayoutsTest`, `ThemeLayoutsToListTest`, `LayoutTextsTest`, `LayoutAddMenuTest`
(and At29). The layout page: `test/layout-maker.test.mjs` (node: its edits, the page assembled and its budget), with
`test/layout-smoke.mjs` driving it in a browser. Devices: uat ST-150–188, PY-153–155, SP-51.

## The keys' golden table (060-keys-unify)

Every key's long press, first and marks on every keyboard is one reviewed table,
`lib/ime-core/src/test/resources/keys-golden.tsv`: columns `keyid board state items first up down`; in `items` a
bare text is `AsKey`, a `=` suffix `AsIs`, `#N` `Keypad(N)`, `@action` an `Action`, the row and the symbols split by `|`.
The boards (`board`, as `KeyResolverGoldenTest.boards` names them) are pinyin full, nine, 14, 18, 双拼 nine and 18 (微软),
English full and nine, a theme's own groups (`theme-k18`: 0.6.0's theme maker example 薄荷 17 键's `layouts/k18.xml`, keyboard-scene's test data `mint-k17` since L8), both 注音,
the number pad, the symbol picker, emoji, kaomoji, emoji search and the expanded candidates; the states
`default`, `shift`, `zh` (Chinese punctuation) and `roots` (a table's roots).

- Made once: before the resolver, a throwaway export test on the old code wrote what each key did then,
  `keys-before.tsv` (kept in `dev/`, not committed with its test); the resolver wrote `keys-golden.tsv`; the two were
  diffed and every difference tied in the commit message to the design's D1–D9 / I1–I18. A later change to the
  golden table says why in its commit message.
- `KeyResolverGoldenTest` (ime-core) holds the resolver to it; it is the only reader of the table. App
  `KeysEverywhereUatTest` holds what the keyboards really pop up and draw to hand-written expectations, and
  `DisplayIsTypedUatTest` the real popups to the resolver's result: neither reads the table.
- `GeneratorParityTest` (app test sources) held the long-press generators moved into keyboard-scene `keys/` to the old
  ones key by key while both existed; it went with the old code in S6.
- The format and the migration: `KeyListsFormatTest` (every rule of the grammar), `KeyIdRoundTripTest` (every name the
  built-in layouts and 0.6.0's 17 keys example pack (`mint-k17`) give, and odd orphan names, through `serialize → parse`), `KeyListsWithTest`
  (when a line is dropped; the `@3` marks), `DowngradeTo060Test` (0.6.0's own reading of `key_lists` and `key_swipes`,
  frozen: a marked list its defaults never written over, a `vtext:` slot its default, the rest kept), `KeyResolverMarksTest`
  (an item of variables filled in and filled in again as picked, an item as it is only where punctuation converts it),
  `KeyListsMigrationTest` (0.5.2 and beta1 prefs in
  `lib/ime-core/src/test/resources/keys-migration/` to `@1`, twice the same; `upgrade`'s `@1` → `@2` cases), app `KeyListsUpgradeTest` (0.5.2
  backups built in memory, `v052()`, imported through `UserDataManager.import`, restarted, reset, and
  before the first unlock; `uat/fixtures/upgrade/` holds only v0.3.x and v0.4.0 backups).
- The web page and the app agree: keyboard-scene `DefaultMarksTest` compares `DefaultMarks.of` for every built-in
  layout with `src/commonTest/data/default-marks/<layout>.json` on the JVM and in node (recorded with
  `-Psnapshots.record=true`). The app has no test of its own holding `KeyResolver.marks` with no user settings to the same json: the resolver
  and the page share keyboard-scene `keys/` (`KeyDefaults`, `SlotDefaults`), which is what keeps them alike.
- Lint: keyboard-scene `BuiltInLayoutLintTest` reads only the built-in `layouts/` (a theme's own layouts may keep the
  old attributes): no `swipe-up`/`alt` on a character key, no `<popup>`/`long-press`/`swipe-up` on a function key, no
  `mark="false"` but on the address keys, qwerty's `;` and `*` and 注音's two layouts (`zhuyin.xml`, `zhuyin-nine.xml`:
  no room for a corner) (`markOffKept`), a `shortcut=` on every editable key that `KeyId.of` reads but the address keys,
  `number-row.xml`, `emoji-search.xml`, the roles back/lock/reading/lookup, the keys search/emoji/emoticon/page-up/page-down,
  the number pad's operator keys and qwerty's `;`/`*` (`unnamedKept`).

## Golden files from upstream fcitx

The host is fcitx ported to Kotlin, and the native sources are not in the tree. What fcitx does is kept
as golden files, made by scripts that fetch upstream at the commits the host was ported from, so they
regenerate without a checkout of fcitx:

- Key symbols: `lib/ime-core/tools/keysyms-gen.py` downloads fcitx5's `keydata.h`, `keynametable.h`,
  `keynametable-compat.h` and `keysymgen.h` at 442edbc9 (SHA-256 pinned) and
  writes the committed `host/KeySymUnicodeTable.kt`, `host/KeySymNameTable.kt` and `src/test/resources/keysyms/*.tsv` (what
  `Key::keySymFromString`/`keySymFromUnicode`/`keySymToUnicode` return);
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
  (`ImportFiles.Scel.writeTextDictionary`); `ImportFilesTest` holds it to these byte for byte, the two broken files
  failing as `IOException`.

## Parity of the two hosts (history)

While the native fcitx host and the Kotlin one both built (fcitx-replacement §7.2), a device tool
(`uat/parity`: a test editor app, a recorder, `ime-eval parity-*` to generate, seal and compare key
sequences, with the engine's fingerprint `ENGINE_BEHAVIOUR` in each file's header) ran the same
sequences on both and compared what the user saw, call by call. The native host is gone (the tag
`native-last`), its recording can never be made again, and the device side was made for redroid; it
was deleted in 0.6.1 (the user decided on 2026-11-13 not to keep a device tool no device can run; git history before that has it). What is left is the JVM side's
model of a device run: `ParityReplay` (`lib/ime-eval/src/uatTest`, with `Step`, `Sequence`, `State`
and `Output` of `eval/parity/ParityFile.kt`, given the input methods as a list) runs a sequence on `HostReplay` as the driver did
(the editor apps' uids and fields, the bar kept over another app, the keyboard put away by a single-line
field's Enter, keys 200 ms apart on the replay's clock, `HostReplay.TAP_MILLIS`), and
`HostReplayUatTest` holds the host to what the devices showed.
