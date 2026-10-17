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
The bar above is `KawaiiBarComponent`: idle (toolbar, number row, the result of a sum just written, clipboard suggestion, inline
autofill, decided by `IdleUiPolicy`; the sum is read off the field when what the service commits
ends with `=`, worked out by `Calculation` in ime-core, and dropped as the cursor leaves the `=`) or candidates (`KawaiiBarStateMachine`,
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

On a wide screen held sideways the letters split in two (`TextKeyboard.Split`, sized by
`SplitKeyboard` in ime-core, the setting `ThemePrefs.splitKeyboard`: auto / always / off): Gboard's,
five keys a half, G and V on both (`QWERT|YUIOP`, `ASDFG|GHJKL`, `⇧ZXCV|VBNM⌫`), each half a grid
from the edge to the room between them; that room what ten letters 48 dp wide leave of the keyboard
(the screen less its side padding), 12–40% of it; the space spans it, the rest of the bottom row as
wide in letters as before. `GapKey`
is the room, a plain view that takes no touch. Decided as the keyboard is made: a turn of the screen
makes the input view anew (the theme editor's preview too, `KeyboardPreviewUi`). A first try with
the halves against the gap and T, G, B on both left the edges ragged and empty (2026-10-14).

While the letters split, every other board (number pad, nine keys, handwriting, the pickers, the
clipboard: anything but the letters, `SplitKeyboard.side`) goes to one side one thumb wide, the bar
over it too, as Gboard's one-handed mode: across a 850 dp screen the number pad's middle was out of
either thumb's reach. `SplitKeyboard.dockWidth`: 42% of the keyboard, 320–420 dp (about a phone
upright). `InputView.placeBoard` gives the window that width at the side and the far padding space the
rest, with an arrow that moves it across (`ThemePrefs.splitDock`: right / left / full, read live —
`ThemeManager` does not make the input view anew for it, which would drop the board shown). It follows
the window attached (`InputBroadcastReceiver.onWindowAttached`, its `DockFollower`, reading
`InputWindowManager.currentWindow`) and the keyboard window's layout
(`KeyboardWindow.onLayoutShown`).

One-handed (`ThemePrefs.oneHanded`: off / right / left, read live the same way; the status area's
单手模式 turns it on at the right or off): while the letters do not split, every board, the letters
too, goes to that side, 80% of the width, a phone's 420 dp at most (`SplitKeyboard.placement`, which
decides both). The room beside it has the arrow, which then sets `oneHanded` rather than
`splitDock`, and a button back to the whole width.

### Hardware keyboard

A key pressed on one hides the touch keyboard and shows candidates by the cursor; a tap on the field
brings the touch keyboard back (`InputDeviceManager`, upstream's). Chrome's web fields tell no input
method of a tap (no `onViewClicked`, no `onUpdateEditorToolType`), so the floating window
(`CandidatesView`) always shows, a keyboard button at its end — alone when nothing is typed, after the
cursor on its line where it fits, else under it (under it always, it covered a note's next line; in a
corner, an app's send button; before the cursor, what was just typed) — that brings the touch keyboard back (`showTouchKeyboard`), as Gboard's floating button. Where the editor tells no
cursor (no CursorAnchorInfo or a NaN marker: the bottom-left fallback, `CandidatesView.cursorKnown`) the lone
button stands at the top right (in RTL too), and it can be dragged: dropped with no cursor, its place is kept as fractions
of the window (`internal.keyboardButtonX/Y`), with a cursor only as an offset for the field
(`FloatingButtonPlacement` in ime-core); its
touch window follows the lone button once the cursor rests (150 ms), not at every report. The touch
keyboard brought back mid-field is started for that field (`InputView.startInput` from the
`InputDeviceManager` callback). The
keyboard last typed on going away (`InputManager.InputDeviceListener.onInputDeviceRemoved`, the
device id of the last key) brings it back too, hidden or shown: the configuration's `keyboard` cannot
tell, a device with one built in (the emulator's `qwerty2`) reports QWERTY throughout.

`KeyStates.fromKeyEvent` gives fcitx a modifier key's state as X11 does, before the key: Android
sets Shift in its own press and clears it in its release, so a lone Shift never matched itself.

The shortcuts are the app's, not fcitx's (`HardwareShortcuts`, `InputMethodSwitch` in ime-core,
applied by `HardwareKeyboard` (app, `input/`) from `FcitxInputMethodService.onKeyDown/onKeyUp`, only in
a text field: not `EditorInfo.isTypeNull`, where fcitx has no focus and every key is the app's): a lone Shift tap (≤250 ms, fcitx's
ModifierOnlyKeyTimeout, nothing pressed between) and Ctrl+Space switch between the Chinese input
method and English, back to the Chinese one left; Ctrl+Shift (either order) goes to the next enabled
one. fcitx's own toggle goes to the group's first, which here is pinyin (`Fcitx.onFirstRun` puts it
first so a field starts in it), so from wubi it went to pinyin; its TriggerKeys (Ctrl+Space, Zenkaku_Hankaku, Hangul) and
AltTrigger Shift are turned off and saved at startup and again after each read of fcitx's config
(`native-lib` `clearFcitxHotkeys`, which clears fcitx's Ctrl+Alt+P TogglePreedit too) so the two do
not both switch, and the setting below leaves the keys to the app alone. Ctrl+Space is
consumed where it switches: unconsumed, Android goes to the next system input method (seen with Sogou
and Gboard). Where it has nowhere to go (one input method, or English not enabled) it is no shortcut
and goes on, through fcitx (none of its hotkeys left), to the app (an editor's completion). The setting `AppPrefs.candidates.switchShortcuts` (实体键盘快捷键, on) turns all three off:
the keys then go to fcitx and the app as any other.
The input method's name flashes by the cursor (fcitx's ShowInputMethodInformation).

With the hardware keyboard, handwriting and the nine keys are skipped by Ctrl+Shift
(`Engines.takesKeyboard`), and a character key pressed in one is typed by pinyin (全拼 or 双拼,
whichever is listed first), else the first enabled Chinese input method that takes a keyboard, else
English (`InputMethodSwitch.forKeyboard`), as Sogou and Baidu type pinyin whatever their touch
layout; the touch one comes back with the touch keyboard (`HardwareKeyboard.touchInputMethod`, the fcitx thread's; the hand-off runs in the key's own fcitx job).
`-`/`=` page the candidates while composing (`androidengine.cpp`, physical keys only).

Testing one (`uat/tools/emu-keyboard.sh`, the cases in `uat/17-hardware-keyboard.md`): the emulator, never redroid (redroid shares the host kernel, and a uinput keyboard there
is the host's too). The emulator's own `qwerty2` is an ALPHA keyboard (sticky Shift, `unicodeChar` 0
in the IME): register a USB keyboard with `/system/bin/uinput` (JSON `register`, vid/pid unknown so it
reads `Generic.kl`/`Generic.kcm`) and `sendevent` to its `/dev/input/eventN` as root.

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
- **Listeners see a snapshot.** `ManagedPreference.fireChange` and
  `ManagedPreferenceProvider.fireChange` walk a copy of their listeners: a listener may make views
  anew that unregister and register others in the same set (a theme change makes the input view
  anew, and `InputView` listens to `splitDock`), which threw ConcurrentModificationException.
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
