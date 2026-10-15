# The app (`app/`)

Android glue, kept thin: read Android state and preferences, pass values into `lib/ime-core`,
apply what comes back. If a change adds an `if` that decides behaviour, ask whether it belongs in
an ime-core class with a test.

## Packages (`app/src/main/java/org/fcitx/fcitx5/android/`)

| Package | Holds |
|---|---|
| `core/` | The JNI side of fcitx: `Fcitx` (native lifecycle, the fcitx thread), `FcitxAPI` (suspend calls into fcitx), `FcitxEvent` (events out of it), `EngineBridge` (where the native `androidengine` addon reaches ime-core's `Engines`), `EngineMigration`, `SubtypeManager`, `InputMethodNames`, `NativeMatrixKernel` |
| `daemon/` | `FcitxDaemon`: the one fcitx instance, shared by connections (`FcitxConnection`) |
| `input/` | The `InputMethodService` (`FcitxInputMethodService`), `InputView`, and what the keyboard shows: `bar/` (the candidate bar / toolbar, `KawaiiBarComponent`), `candidates/` (horizontal bar, expanded grid, floating window for physical keyboards), `keyboard/` (`TextKeyboard`, `T9Keyboard`, `NumberKeyboard`, `KeyDef`/`KeyDefPreset`, `KeyView`, `CommonKeyActionListener`), `picker/` (symbols, emoji), `popup/` (key previews, long-press keyboards), `clipboard/`, `editing/` (text editing panel, `InputConnectionEditor`), `status/` (the "more" panel), `preedit/`, `broadcast/`, `wm/` (`InputWindowManager`) |
| `data/` | File-backed managers: `prefs/` (`AppPrefs`, the managed preference framework), `theme/`, `clipboard/` (Room), `pinyin/` (imported dictionaries, custom phrases), `punctuation/`, `UserDataManager` (backup/restore), `DataManager` (syncs `assets/usr`) |
| `ui/` | Settings: `main/` (`MainActivity`, `MainFragment` = the home page, `settings/` pages and `SettingsRoute`, `settings/search/`), `setup/` (the first-run wizard), `common/` |
| `utils/` | Small helpers (`AppUtil`, `Const`, extensions) |
| `src/main/cpp/` | `native-lib.cpp` (JNI), `androidfrontend` (fcitx frontend for the service), `androidengine` (the addon in front of ime-core's input methods), `androidaddonloader`, `androidnotification` (fcitx tips as toasts), `matrix-kernel.cpp` |

## The input view

`FcitxInputMethodService` owns the editing session (`EditingSession` from ime-core over
`InputConnectionEditor`), decodes `EditorInfo` into `EditorTraits` and `FieldPolicy`, and makes an
`InputView` per configuration. `InputView` is a tree of components (`dependency/`, a small
`UniqueViewComponent` injection) under one `InputWindowManager`, which shows one window at a time
in the keyboard area: `KeyboardWindow` (the layouts), `PickerWindow` (symbols / emoji),
`ClipboardWindow`, `TextEditingWindow`, `StatusAreaWindow`, and in the voice build `VoiceWindow`.
The bar above is `KawaiiBarComponent`: idle (toolbar, number row, clipboard suggestion, inline
autofill, decided by `IdleUiPolicy`) or candidates (`KawaiiBarStateMachine`,
`ExpandButtonStateMachine` in ime-core).

Work tied to the view runs in the view's scope (`dependency/Functions.kt`): a theme or layout
change replaces the view and must cancel what touched the old one. Work that must survive it goes
on the service's scope.

### Keys

A layout is a list of rows of `KeyDef`s (`TextKeyboard.Layout`, `T9Keyboard`, `NumberKeyboard`, `HandwritingKeyboard`):
an appearance (text, image, width, variant) and behaviours (`Press`, `LongPress`, `Repeat`,
`Swipe`, `DoubleTap`), each a `KeyAction` (`FcitxKeyAction`, `SymAction`, `CommitAction`,
`LayoutSwitchAction`, `QuickPhraseAction`, `UnicodeAction`, `LangSwitchAction`,
`SpaceLongPressAction` …). `CommonKeyActionListener` performs them. A key's long-press
characters come from `PopupPreset` (built in) overridden by the user's `PopupOverrides`; the first
of them is also what swiping the key types and the small label in its corner: one concept, one
setting.

Address fields swap the comma and the full stop for `/` or `@` and `.com` (`AddressKeys`); a key's
digit or symbol goes in there as it is, what is typed committed as typed
(`PendingInputPolicy.typesAsIs`, in `CommonKeyActionListener`), but for a key the input method
types with (`Engines.readsAsKey`: the nine keys' digits, 微软双拼's `;`, the pinyins' `'` and a
table's keys, 二笔's `;`, while something is typed, the table's from `Engines.tableKeys`); the nine
keys' column, separator and multi-tap are in `T9Keyboard` with decisions in `MultiTap`, `NineKeys`.

Handwriting's keyboard (`HandwritingKeyboard`, the letters layout of `Engines.HANDWRITING`) is a pad
(`HandwritingPadView` over `InkPad`) and a column of backspace and marks. Each read goes to
`HandwritingRecognition` (input/handwriting), its reading back to the engine through
`EngineBridge.offerInk` and the key `Keyboard.INK`, with the piece it is of; the pad wipes the piece
it writes once the engine has ended it (`EngineBridge.handwritingEnded`, the latest piece ended, so
a value missed while detached is still seen), and ends it itself when detached and at each new
input (`restart`, which also drops what was still being read). The
recognizer skips a read only for a newer one of the same piece: a piece's last read is what it shows.
How long the pen stays up before a piece is done (`InkPad.finishMillis`) is the app's setting
`AppPrefs.keyboard.handwritingPause` (`HandwritingPause`, 0.3–1.5 s), on the handwriting page,
read at each pen up. A piece so done is told to the engine (`EngineBridge.inkFinished` and an Ink
key, after the piece's readings: `HandwritingRecognition.afterReads`), for the large sentence model
to weigh the finished character's sentence.

## Settings

Two stores, deliberately:

1. **App preferences** (`SharedPreferences`), through the managed preference framework in
   `data/prefs`: a `ManagedPreferenceCategory` (`AppPrefs.Keyboard`, `Candidates`, `Clipboard`,
   `Symbols`, `Advanced`; `ThemePrefs`) declares each preference with its UI in one line
   (`switch`, `int`, `enumList`, `twinInt`, `levels`). A page is a `ManagedPreferenceFragment` over
   a category. These are what the keyboard reads.
2. **fcitx config** of the input methods: the `androidengine` addon's options (C++,
   `androidengine.h`), read by the engine (`EngineSettings`). The pages for the app's own input
   methods are built by `InputMethodSettings` from a list of `Item`s (`Toggle`, `Choice`, `Flags`,
   `Page`, and `AppChoice`/`AppToggle` for the few app preferences that belong to one input method,
   such as 九键's multi-tap or English's auto-caps), over `FcitxAPI.getImConfig`/`setImConfig`.

Rules for settings, all decided:

- **Few settings.** The home page is grouped by what the user wants to adjust (输入 / 词语 / 键盘与外观
  / 剪贴板与表情 / 其他), each input method has its own page, fcitx's global options and addon list
  are not shown. Before adding a setting, ask whether a good default would do.
- **One concept, one setting.** Do not split what the user sees as one thing into two knobs (the
  long-press character is the swipe character is the corner label).
- **Levels over numbers.** Sizes, delays, vibration and volume are shown as a few named levels
  (`levels(...)`, e.g. 矮/标准/高/很高) over the int preferences, which are `hidden(setByLevels = true)`.
- **`hidden { }`** marks a preference with no UI: it is reset to its default once on upgrade and after
  a restore (`resetHiddenSettings`), so an old value set where the UI used to be cannot linger.
- **Searchable.** `SettingsIndex` builds the search from the same declarations (managed categories
  are walked, input method pages read from `InputMethodSettings`); a page drawn by hand must add
  its entries there, with a test in `SettingsIndexTest`.
- **Direct boot.** Preferences the keyboard needs before the first unlock (in a password prompt)
  are copied to device-protected storage (`AppPrefs.syncToDeviceEncryptedStorage`); a new
  keyboard-facing category belongs in that list.

### Adding a setting, the usual way

1. Decide the default first; the setting exists only if a user will want both values.
2. App-side behaviour: add it to the right `AppPrefs` category (title and summary in strings);
   pass its value into the ime-core class that decides, never read `AppPrefs` from ime-core.
3. Engine behaviour: add an `Option` to `androidengine.h`, read it in `EngineSettings.parse`, show it
   with an `Item` in `InputMethodSettings`; `nearest` maps old or odd stored values to a shown one.
4. Strings: `values/strings.xml` (English) and `values-zh-rCN`, `values-zh-rTW` at least; the other
   locales fall back to English. Write the summary as what the user gets, not how it works.
5. Test the decision in ime-core; if the page is hand-drawn, the search index too.
6. Describe it in `docs/settings.md`.

## Persistence and backups

`UserDataManager` exports one zip: `shared_prefs`, the clipboard database (less what an app marked
sensitive), the external files dir (fcitx config, custom phrases, imported dictionaries, themes)
and the engine's `files/engine/` (learned words, word lists).
Restoring stops fcitx, closes the clipboard database, replaces the files, then restarts the
process (`AppUtil.restart`); a failed restore reopens what it stopped (`restartAfterFailedImport`).
`ReplaceFile` replaces a directory in one rename so a kill leaves the old or the new, never half.
New user data must be added to the export, and must survive a restore made by an older version
(missing files mean defaults).

## Permissions and privacy

The app asks for `VIBRATE`, and the voice build for `RECORD_AUDIO`; nothing else. The build fails
if the merged manifest asks for anything more (`PermissionCheckTask` in `build-logic`), so a
dependency cannot add one unnoticed: if a feature needs a new permission, that is a product
decision for the owner, then the allowlist and `PRIVACY.md` change together. No network
permission, ever: no cloud features, no analytics, no crash upload. No notifications.

`FieldPolicy` decides what may be done with a field: a password or a field that asks not to be
learned from is *sensitive* (its text is never read for context, nothing is learned or kept in
`ContextMemory`, voice never types into a password). Any new feature reading the editor or
remembering text goes through it.

## The two flavors

`speech` dimension: `text` and `voice`. Main code reaches voice only through `VoiceFeature`
(`AVAILABLE`, the voice window, hold-to-talk), defined in both source sets with the same
signatures; the text one is empty. `VoiceDataPlugin` adds the recognizer and models to the voice
build only; `RECORD_AUDIO` is in `app/src/voice/AndroidManifest.xml`. An input method cannot ask for
a permission itself, so `VoicePermissionActivity` asks and closes. Voice decisions (number
normalisation, reranking hypotheses with the pinyin model, blocked words, hotwords, hold-to-talk
gestures) are in ime-core's `input/voice/` and tested there. `:app:compileVoiceDebugUnitTestKotlin`
in CI keeps the voice source set compiling.

## Strings and languages

The app speaks 简体中文, 繁體中文 and English (`AppLanguage`, chosen in the settings, independent of
the phone's language); de/es/ja/ko/ru are partial and fall back to English. fcitx's own texts
(menus from the addon) are translated through gettext `.po` files in `src/main/cpp/po` (domain
`fcitx5-android`) and chinese-addons' domain for texts reused from libime.
