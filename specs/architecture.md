# Architecture

## Modules

| Module | Kind | What it holds |
|---|---|---|
| `lib/ime-core` | plain JVM (`java-library`), no Android on the classpath | The engine (`engine/`: pinyin, tables, English, strokes, phrases, learning, ranking, data formats, the host that lists the input methods), the input method framework (`host/`: fcitx5's instance, key pipeline, input contexts and the modules the app uses, ported to Kotlin; [host.md](host.md)), the keyboard's decisions (`input/`: cursor and composing tracking, key and gesture policies, bar state machines, voice decisions, field policy), `core/` (`CoreLog`, `FcitxUtils`, `FormattedText`), `ui/search/SettingsSearch` and `utils/EventStateMachine`. Tested in milliseconds. |
| `app` | Android application | The `InputMethodService`, the keyboard's views and windows, the settings UI, file-backed managers (clipboard, themes, dictionaries, backups), `core/`: `KotlinFcitx` (the `FcitxAPI` over ime-core's host, on one thread), `EngineBridge` (the engines' Android side). No native code of its own. |
| `app/src/text`, `app/src/voice` | flavor source sets (`speech` dimension) | Each defines `VoiceFeature` with the same signatures; only `voice` has voice input, sherpa-onnx and `RECORD_AUDIO`. |
| `lib/ime-eval` | JVM CLI | Typing simulated over test sets; top-1 accuracy, predictions, English, learning. Never shipped. |
| `lib/ime-dict-tool` | JVM CLI | Compiles text sources into the engine's data files (pinyin, tables, strokes, English), mixes models, packs word packs, finds new words. Runs on the build host only. |
| `lib/sherpa-onnx` | native | The speech recognizer, vendored and built from source; voice build only. |
| `lib/handwriting` | Android library, native | The handwriting recognizers' runtime: onnxruntime (the very file sherpa-onnx links, so a voice build packs one) and a small JNI to it, `OrtModel`, which runs an ORT-format model mapped from the APK; and `libyoumo-matrix`, the sentence models' kernel, without onnxruntime. Both builds. |
| `codegen` | annotation processing | Generates the app's key and scancode mapping tables. |
| `build-logic` | included build | Gradle convention plugins: Android conventions, data download and compile, voice data, permission check, version from tags, detekt. |
| `lexicon/` | data | The curated new words, removed and corrected readings, misreadings, Latin words: decisions kept in the repository. |

Dependencies point one way: `app` → `lib/ime-core`; `lib/ime-eval` and `lib/ime-dict-tool` →
`lib/ime-core`. Nothing in `ime-core` knows Android or the app.

## Where logic goes

Decision logic goes in `lib/ime-core`; `app` reads Android state and preferences, passes plain
values into an `ime-core` class, and applies what it returns. The pattern to copy is
`PendingInputPolicy`, `LayoutSwitchPolicy`, `BackspaceSwipeBehavior`: a pure class, preferences as
arguments, no `AppPrefs`, no `Context`. Measured and deliberately not done: an `ImeHost`
interface over the service, injecting `AppPrefs` everywhere, a DI framework.

`ime-core` is checked against Android's API 29 surface by animal-sniffer (it runs on the phone),
and held to Kover floors: 90% line coverage overall, 60% per package.

## Processes and threads

One process. Inside it:

- **Main thread**: the service, views, settings UI.
- **The host thread** (`fcitx-main`, still called the fcitx thread): `HostDispatcher`, one
  scheduled executor, is both the coroutine dispatcher every `FcitxAPI` call (`suspend`) runs on
  and the `HostClock` whose timers (the refine pause, the input method name overlay) run strictly
  in order with the calls around them. `HostCore` and the engine's sessions live on it alone, so
  nothing in them locks. `KotlinFcitx.stop` runs a last job (flush the config writer, close the
  engine) and drops timers not yet due.
- **`host-config`** (`ConfigWriter`): writes each changed config file in the background, merged
  per file, atomically; flushed at stop and before `save()` returns.
- **`host-quickphrase`**: reads the quick phrase tables once at start, beside the engine's load; a
  lookup before they are read waits. charselect's search index is built on a thread of its own
  after the first Unicode trigger.
- **IO / Default dispatchers**: file work (imports, backups, the clipboard database), and the
  engine's own background saves.
- **`engine-phrases`** (`EngineBridge`): one thread saving the custom phrases file, one save at a
  time, in order.
- **`record-compaction`** (`Engines`): a daemon thread for each compaction of a user log
  (`RecordStore`), at most every few thousand records. The host thread, which learns, reads the
  counts; this thread writes and syncs them to a new file, which the next append on the host
  thread puts in place.
- **`engine-warm-up`** (`Engines`): a daemon thread for each warm-up, once for each `Engines` (made
  anew as the host starts again after it stopped, `EngineBridge.close()`): pinyin's data and word index
  as the first field starts in a Chinese input method (全拼, 双拼, 九键, handwriting, a table; not in
  English: a switch from English to one reads them on the host thread as its session is made),
  English's keys and word index as its session is first made, and the tables' shared words, coded
  for the tables (`TableRegistry`). A session
  made meanwhile waits for them: `EngineAssets.pinyinData` and the word indexes are synchronized
  lazies.
- **Voice** (voice build): the microphone reader, the VAD and the recognizer on threads of their own
  (`VoiceListener`).
- **`handwriting`** (`HandwritingRecognition`): the handwriting recognizers, one read at a time; a
  read made stale by a newer stroke is skipped. Its reading goes to the engine as a host job: the
  reading offered (`EngineBridge.offerInk`), then a key (`Keyboard.INK`) that shows it, in order
  with the keys and taps around it.

Access to the host from outside the service goes through `FcitxDaemon` → `FcitxConnection`
(`runOnReady`, `runIfReady`, `runImmediately` for cached reads and `eventFlow` only). Never
`runBlocking` on the main thread to wait for the host. `FcitxDaemon` starts `KotlinFcitx` with the
first client and stops it after the last.

## A key, from the finger to the editor

1. A `KeyView` (in a `BaseKeyboard`: `TextKeyboard`, `T9Keyboard`, `NumberKeyboard`) recognizes the
   gesture (`KeyGestureRecognizer` in ime-core decides tap, swipe, long press, repeat) and emits a
   `KeyAction` from its `KeyDef` (`KeyDefPreset.kt`).
2. `CommonKeyActionListener` turns it into a call on the service: most become
   `service.postFcitxJob { sendKey(...) }`, onto the host thread.
3. `KotlinFcitx.sendKey` calls `HostCore.sendKey` on the active input context, which runs the key
   through `KeyPipeline`, fcitx's phases in order: an active temp mode (quick phrase, Unicode) takes
   every key; else the engine (`KeyRouter`) maps it to an `EngineEvent` (`engine/host/Keyboard.kt`)
   for the input method's session; then the modules' hooks (fullwidth, Ctrl+. for punctuation,
   Ctrl+Shift+F for 简繁); a key nobody accepted goes back to the app. See [host.md](host.md).
4. Through the `EngineHost` port (the app's `EngineBridge`), `Engines` (ime-core) applies an
   `Action` to the input method's `Session` (`PinyinSession`, `TableSession`, `EnglishSession`, …)
   and gets a `Snapshot` back: text to commit, the preedit, the page of candidates, hints, whether
   there is more to refine.
5. `KeyRouter` shows the snapshot as the context's panel; commits go through the commit filters
   (punctuation, 简繁) and the panel through the output filters (简繁 converts every candidate).
   `HostCore` emits `HostEvent`s at once for commits and keys handed back, the panel once at the end
   of the key; `KotlinFcitx` turns them into `FcitxEvent`s (`CandidateListEvent`,
   `CommitStringEvent`, `ClientPreeditEvent`, `InputPanelEvent` …) on `eventFlow`, which keeps
   every event however slow its collector.
6. On the main thread, `FcitxInputMethodService` applies commits and the composing text through
   `EditingSession` (ime-core), which keeps its own idea of cursor and selection ahead of the
   editor's lagging reports (`CursorTracker`); `InputView` broadcasts panel events to the bar
   (`KawaiiBarComponent`), the candidate views and the preedit.
7. While the user pauses, `Refiner` sends `Refine` after a short delay so the 25M model can pick a
   better first candidate; any key drops what is left of that work.

The engine also reads the editor: on a field focused or the cursor moved, the service reads the
text before the cursor (`ContextReads`, gated by `FieldPolicy`: nothing from a password or a field
that asks not to be learned from) and sends it as `Action.Context`, so the language model and the
sentence models score what follows it. `ContextMemory` keeps the end of what was written in a chat
box that clears itself, for the reply typed next; told as `Action.Context.cleared` (`FieldPolicy.told`),
as the field is empty: English scores after it but puts no glide's space after it, nor has a
Backspace take any of it.

Where the preedit shows is the input method's (`KeyRouter.composesText`, set as the
input context's `preeditEnabled` on activation and again at each event the engine sends; turned off as
quick phrase or Unicode starts, so their code shows above the candidates in English and handwriting too): English's
word and handwriting's characters are the text itself and go into the app's text as composing
text, where the field takes it (`CapabilityFlag.Preedit`), kept there as the keyboard goes. A code
(pinyin, the nine keys', a table's) is not text, and is shown in the keyboard's own row above the
candidates (`PreeditComponent`, `KawaiiBarComponent.PREEDIT_HEIGHT`, there whether or not a code is
typed so the keyboard keeps its height; the floating window's `PreeditUi`), as 搜狗 shows its own,
covering nothing of the app: in the app,
a tap on another field or app had the app finish it as text before the input method heard of it
(`InputMethodManager`), leaving `ni` in the field, and an app that made its activity anew on a
turn saved it with the field's text. Dropped with the engine's input as the keyboard goes, a code
never reaches the app's text, so nothing has to be taken out of it afterwards. The row scrolls to
keep the cursor in sight, and a tap on it puts the cursor there (`FcitxAPI.moveCursor`, the
engine's `EngineEvent.CURSOR` with the place in the preedit), for a key missed in the middle of a
long code to be typed or deleted there; arrows and the swipe on space move it too (`Action.Step`).

## The engine host

`Engines` (`engine/host`) lists the input methods by name (`Engines.PINYIN`, `T9`, `SHUANGPIN`,
`TABLES`, `HANDWRITING`, `ENGLISH` = `keyboard-us`) and makes each session at first use. `EngineAssets` loads the data
from the APK's assets: `engine/pinyin.data`, `engine/table/<name>.data`, `engine/english.data`,
`engine/stroke.data`, `engine/emoji.data`, `engine/chaizi.data` and the two `.safetensors` models. They are stored uncompressed in the APK
(`noCompress`) and memory-mapped in place (`EngineBridge.asset`), so the language model (about
156 MB of `pinyin.data`) costs no heap. `TableRegistry` loads tables on demand; `UserLexicon`
owns the user's words (learned, added, blocked, imported dictionaries, custom phrases) shared
across every input method.

Settings for the engine live where fcitx kept them, `config/conf/androidengine.conf`, in fcitx's INI
(`EngineOptions`, the option table of the former `androidengine` addon, keyed as libime's pinyin
config was so old settings carry over; `LibimeMigration`/`EngineMigration` move them). `HostCore.configure`
gives the router the page size and English suggestions and the engines the rest, flattened and
parsed by `EngineSettings`. The settings UI edits it through `FcitxAPI.getImConfig`/`setImConfig`
(see [app.md](app.md#settings)).

## Where data lives on the phone

| What | Where | Written by |
|---|---|---|
| Engine data (read-only) | APK assets, memory-mapped | build (`EngineDataPlugin`, `VoiceDataPlugin`, `HandwritingDataPlugin`: the handwriting models, `.ort`/`.data` uncompressed, both builds) |
| fcitx's built-in data (the zh_CN punctuation table, quick phrase's emoji and LaTeX tables, `charselectdata`) | APK assets under `pkgdata/`, read from assets (`charselectdata` whole into the heap the first time Unicode input is opened, the tables parsed; `KotlinFcitx.BUILT_IN`; sources and SHA-256 in `app/src/main/host-assets.md`) | repository |
| What the engine learned | `files/engine/` (`EngineBridge.userDir`): pinyin's user log, each table's and English's, the word lists (added, blocked) | engine: append-only logs (`RecordStore`), lists written whole |
| fcitx config and data home | the external files dir (`Android/data/<package>/files`, `EngineBridge.fcitxHome()`; the paths fcitx used): `config/` (the profile, `conf/*.conf` incl. the input methods' options), `data/pinyin/customphrase`, `data/pinyin/dictionaries/` (imported dictionaries, as text, and `merged.ymd` compiled from those on; not in a backup), `data/data/quickphrase.d`, `theme/` | the host (`ConfigWriter`), the app's managers |
| App preferences | `shared_prefs` (`AppPrefs`, `ThemePrefs`) | app |
| Clipboard | Room database in `databases/` (`ClipboardManager`) | app |

In direct-boot mode (before the first unlock) there is no `userDir`: the engine keeps what it
learns in memory, and the app restarts into normal mode on unlock. A backup (`UserDataManager`)
is one zip of `shared_prefs`, the clipboard database (without entries an app marked sensitive), the
external files dir and `files/engine/`: everything above but the read-only data.
