# Architecture

## Modules

| Module | Kind | What it holds |
|---|---|---|
| `lib/ime-core` | plain JVM (`java-library`), no Android on the classpath | The engine (`engine/`: pinyin, tables, English, strokes, phrases, learning, ranking, data formats, the host that lists the input methods) and the keyboard's decisions (`input/`: cursor and composing tracking, key and gesture policies, bar state machines, voice decisions, field policy). Tested in milliseconds. |
| `app` | Android application | The `InputMethodService`, the keyboard's views and windows, the settings UI, file-backed managers (clipboard, themes, dictionaries, backups), the JNI side of fcitx (`core/`), native code (`src/main/cpp`). |
| `app/src/text`, `app/src/voice` | flavor source sets (`speech` dimension) | Each defines `VoiceFeature` with the same signatures; only `voice` has voice input, sherpa-onnx and `RECORD_AUDIO`. |
| `lib/ime-eval` | JVM CLI | Typing simulated over test sets; top-1 accuracy, predictions, English, learning. Never shipped. |
| `lib/ime-dict-tool` | JVM CLI | Compiles text sources into the engine's data files (pinyin, tables, strokes, English), mixes models, packs word packs, finds new words. Runs on the build host only. |
| `lib/fcitx5`, `lib/fcitx5-chinese-addons` | native (CMake) | Fcitx5 (with its quickphrase, unicode, clipboard, imselector and spell modules) and, of chinese-addons, only punctuation, chttrans (简繁), fullwidth and scel2org5 (reading 搜狗 `.scel`), as submodules built for Android. Its pinyin and table engines are not built: the input methods are `ime-core`'s. |
| `lib/sherpa-onnx` | native | The speech recognizer, vendored and built from source; voice build only. |
| `lib/handwriting` | Android library, native | The handwriting recognizers' runtime: onnxruntime (the very file sherpa-onnx links, so a voice build packs one) and a small JNI to it, `OrtModel`, which runs an ORT-format model mapped from the APK. Both builds. |
| `codegen` | annotation processing | Generates the app's key and scancode mapping tables. |
| `build-logic` | included build | Gradle convention plugins: Android/native conventions, data download and compile, voice data, permission check, version from tags, detekt. |
| `lexicon/` | data | The curated new words, removed and corrected readings, misreadings, Latin words: decisions kept in the repository. |

Dependencies point one way: `app` → `lib/ime-core`; `lib/ime-eval` and `lib/ime-dict-tool` →
`lib/ime-core`. Nothing in `ime-core` knows Android, fcitx's JNI or the app.

## Where logic goes

Decision logic goes in `lib/ime-core`; `app` reads Android state and preferences, passes plain
values into an `ime-core` class, and applies what it returns. The pattern to copy is
`PendingInputPolicy`, `LayoutSwitchPolicy`, `BackspaceSwipeBehavior`: a pure class, preferences as
arguments, no `AppPrefs`, no `Context`. Measured and deliberately not done: an `ImeHost`
interface over the service, injecting `AppPrefs` everywhere, a DI framework.

`ime-core` is checked against Android's API 23 surface by animal-sniffer (it runs on the phone),
and held to Kover floors: 90% line coverage overall, 60% per package.

## Processes and threads

One process. Inside it:

- **Main thread**: the service, views, settings UI.
- **The fcitx thread**: Fcitx5 runs its event loop here (`Fcitx`, `FcitxDispatcher`). Every call
  into fcitx goes through `FcitxAPI`, whose methods are `suspend` and dispatched to this thread.
  The engine's sessions run here too: the native `androidengine` addon calls `EngineBridge` through
  JNI on this thread, so the sessions are this thread's alone and nothing in them locks.
- **IO / Default dispatchers**: file work (imports, backups, the clipboard database), and the
  engine's own background saves (the custom phrases file).
- **`record-compaction`** (`Engines`): a daemon thread for each compaction of a user log
  (`RecordStore`), at most every few thousand records. The fcitx thread, which learns, reads the
  counts; this thread writes and syncs them to a new file, which the next append on the fcitx
  thread puts in place.
- **`engine-warm-up`** (`Engines`): a daemon thread for each warm-up, once for each `Engines` (made
  anew as fcitx starts again after it exited, `EngineBridge.close()`): pinyin's data and word index
  as the first field starts in one of the pinyins (全拼, 双拼, 九键; not in English or a table),
  English's keys and word index as its session is first made. A session
  made meanwhile waits for them: `EngineAssets.pinyinData` and the word indexes are synchronized
  lazies.
- **Voice** (voice build): the microphone reader, the VAD and the recognizer on threads of their own
  (`VoiceListener`).
- **`handwriting`** (`HandwritingRecognition`): the handwriting recognizers, one read at a time; a
  read made stale by a newer stroke is skipped. Its reading goes to the engine as a fcitx job: the
  reading offered (`EngineBridge.offerInk`), then a key (`Keyboard.INK`) that shows it, in order
  with the keys and taps around it.

Access to fcitx from outside the service goes through `FcitxDaemon` → `FcitxConnection`
(`runOnReady`, `runIfReady`, `runImmediately` for cached reads and `eventFlow` only). Never
`runBlocking` on the main thread to wait for fcitx.

## A key, from the finger to the editor

1. A `KeyView` (in a `BaseKeyboard`: `TextKeyboard`, `T9Keyboard`, `NumberKeyboard`) recognizes the
   gesture (`KeyGestureRecognizer` in ime-core decides tap, swipe, long press, repeat) and emits a
   `KeyAction` from its `KeyDef` (`KeyDefPreset.kt`).
2. `CommonKeyActionListener` turns it into a call on the service: most become
   `service.postFcitxJob { sendKey(...) }`, onto the fcitx thread.
3. Fcitx5 routes the key to the current input method. For the app's own input methods that is the
   `androidengine` addon (`app/src/main/cpp/androidengine`), which maps it to an engine event
   (`androidengine_public.h`; mirrored in ime-core's `engine/host/Keyboard.kt`) and calls
   `EngineBridge.onEvent` through JNI.
4. `EngineBridge` hands it to `Engines` (ime-core), which applies an `Action` to the input method's
   `Session` (`PinyinSession`, `TableSession`, `EnglishSession`, …) and gets a `Snapshot` back:
   text to commit, the preedit, the page of candidates, hints, whether there is more to refine.
5. The addon renders the snapshot as fcitx's input panel and commit; fcitx emits `FcitxEvent`s
   (`CandidateListEvent`, `CommitStringEvent`, `ClientPreeditEvent`, `InputPanelEvent` …) back
   over JNI.
6. On the main thread, `FcitxInputMethodService` applies commits and the composing text through
   `EditingSession` (ime-core), which keeps its own idea of cursor and selection ahead of the
   editor's lagging reports (`CursorTracker`); `InputView` broadcasts panel events to the bar
   (`KawaiiBarComponent`), the candidate views and the preedit.
7. While the user pauses, the addon sends `Refine` after a short delay so the 25M model can pick a
   better first candidate; any key drops what is left of that work.

The engine also reads the editor: on a field focused or the cursor moved, the service reads the
text before the cursor (`ContextReads`, gated by `FieldPolicy`: nothing from a password or a field
that asks not to be learned from) and sends it as `Action.Context`, so the language model and the
sentence models score what follows it. `ContextMemory` keeps the end of what was written in a chat
box that clears itself, for the reply typed next.

Where the preedit shows is the input method's (`composesText` in `androidengine.cpp`, set as the
input context's `setEnablePreedit` on activation, so quick phrase and Unicode follow it): English's
word and handwriting's characters are the text itself and go into the app's text as composing
text, where the field takes it (`CapabilityFlag.Preedit`), kept there as the keyboard goes. A code
(pinyin, the nine keys', a table's) is not text, and is shown above the candidates (the bar's
`PreeditComponent`, the floating window's `PreeditUi`) as 搜狗 and 百度 show theirs: in the app,
a tap on another field or app had the app finish it as text before the input method heard of it
(`InputMethodManager`), leaving `ni` in the field, and an app that made its activity anew on a
turn saved it with the field's text. Dropped with the engine's input as the keyboard goes, a code
never reaches the app's text, so nothing has to be taken out of it afterwards.

## The engine host

`Engines` (`engine/host`) lists the input methods by name (`Engines.PINYIN`, `T9`, `SHUANGPIN`,
`TABLES`, the English keyboard) and makes each session at first use. `EngineAssets` loads the data
from the APK's assets: `engine/pinyin.data`, `engine/table/<name>.data`, `engine/english.data`,
`engine/stroke.data` and the two `.safetensors` models. They are stored uncompressed in the APK
(`noCompress`) and memory-mapped in place (`EngineBridge.asset`), so the language model (about
156 MB of `pinyin.data`) costs no heap. `TableRegistry` loads tables on demand; `UserLexicon`
owns the user's words (learned, added, blocked, imported dictionaries, custom phrases) shared
across every input method.

Settings for the engine live in fcitx's config of the `androidengine` addon (its options are in
`androidengine.h`, keyed as libime's pinyin config was so old settings carry over;
`LibimeMigration`/`EngineMigration` move them). The addon passes the config to
`EngineBridge.configure`, parsed by `EngineSettings`. The settings UI edits it through
`FcitxAPI.getImConfig`/`setImConfig` (see [app.md](app.md#settings)).

## Where data lives on the phone

| What | Where | Written by |
|---|---|---|
| Engine data (read-only) | APK assets, memory-mapped | build (`EngineDataPlugin`, `VoiceDataPlugin`) |
| fcitx's own data (punctuation tables, addon configs, locale) | `assets/usr` synced into the app's data dir at start (`DataManager`, keyed by `descriptor.json`) | build |
| What the engine learned | `files/engine/` (`EngineBridge.userDir`): pinyin's user log, each table's and English's, the word lists (added, blocked) | engine: append-only logs (`RecordStore`), lists written whole |
| fcitx config and data home | the external files dir (`Android/data/<package>/files`, `HOME` / `FCITX_CONFIG_HOME` / `FCITX_DATA_HOME` set in `native-lib.cpp`): `config/` (the profile, `conf/*.conf` incl. the input methods' options), `data/pinyin/customphrase`, `data/pinyin/dictionaries/` (imported dictionaries, as text), `data/data/quickphrase.d`, `theme/` | fcitx, the app's managers |
| App preferences | `shared_prefs` (`AppPrefs`, `ThemePrefs`) | app |
| Clipboard | Room database in `databases/` (`ClipboardManager`) | app |

In direct-boot mode (before the first unlock) there is no `userDir`: the engine keeps what it
learns in memory, and the app restarts into normal mode on unlock. A backup (`UserDataManager`)
is one zip of `shared_prefs`, the clipboard database (without entries an app marked sensitive), the
external files dir and `files/engine/`: everything above but the read-only data.
