# The app (`app/`)

Android glue, kept thin: read Android state and preferences, pass values into `lib/ime-core`,
apply what comes back. If a change adds an `if` that decides behaviour, ask whether it belongs in
an ime-core class with a test.

## Packages (`app/src/main/java/org/fcitx/fcitx5/android/`)

| Package | Holds |
|---|---|
| `core/` | The host's Android side: `KotlinFcitx` (the `FcitxAPI` over ime-core's `HostCore`, its lifecycle and config files, [host.md](host.md#the-apps-side-kotlinfcitx)), `HostDispatcher` (the host thread and clock), `HostResources` (`AppHostStrings`, the host's texts from app resources), `FcitxAPI` (suspend calls into the host), `FcitxEvent` (events out of it), `EngineBridge` (ime-core's `Engines` with their Android side: assets, user dir, saves; the host's `EngineHost`), `EngineMigration`, `SubtypeManager`, `InputMethodNames`, `NativeMatrixKernel`; the Android-free DTOs of the same package (`FcitxEvent`, `KeySym`, `KeyStates`, `CandidateWord`, `InputMethodEntry`, `Action`, `FormattedText`) are in ime-core, their `KeyEvent` conversions here (`KeySymEvents.kt`, `KeyStateEvents.kt`) |
| `daemon/` | `FcitxDaemon`: the one fcitx instance, shared by connections (`FcitxConnection`) |
| `input/` | The `InputMethodService` (`FcitxInputMethodService`), `InputView`, and what the keyboard shows: `bar/` (the candidate bar / toolbar, `KawaiiBarComponent`), `candidates/` (horizontal bar, expanded grid, floating window for physical keyboards), `keyboard/` (`TextKeyboard`, `T9Keyboard`, `NumberKeyboard`, `KeyDef`/`KeyDefPreset`, `KeyView`, `CommonKeyActionListener`), `picker/` (symbols, emoji), `popup/` (key previews, long-press keyboards), `clipboard/`, `editing/` (text editing panel, `InputConnectionEditor`), `status/` (the "more" panel), `preedit/`, `broadcast/`, `wm/` (`InputWindowManager`), `handwriting/`, `floating/` (the floating keyboard), `dialog/`, `editorinfo/` (the `EditorInfo` inspector), `learning/` (a candidate's long-press menu worded by the app from the engine's `LearningResult.Menu`, `CandidateLearningMenu`; the undo chip, `UndoChip`, the keyboard's one notice: see [Notices](#notices)) |
| `data/` | File-backed managers: `prefs/` (`AppPrefs`, the managed preference framework), `theme/`, `clipboard/` (Room), `pinyin/` (imported dictionaries, custom phrases; sources are `<name>.txt.gz[.disable]`, named through `SourceNames`; `MergedDictionary` compiles the dictionaries turned on into `merged.ymd` on one `dictionary-compile` thread, keyed by a stamp of the sources, the base data and a compiler revision; the engine maps the file, uses a stale one until the new one is made, and is reloaded through `made`. An older build's plain `.txt` is work by itself (`SourceNames.unconverted`: `current()`, `pending()`, `mergedWords` count it): the next compile gzips it first (`Step.CONVERT`, the progress's first share, 正在转换 on the 词库 page, the idle bar's 正在升级词库格式) through `<name>.txt.gz.convert.tmp`, and is an upgrade (UPGRADE) only by what it finds: a text converted or not, a v1 `merged.ymd` dropped, or `.merged.upgrading` (written before the first conversion or the drop, deleted when a non-import compile ends, so a kill in between is still an upgrade at the next start); never by files' times. A text that cannot be read, or a `.gz` that is not gzip or is cut short, is skipped and listed in `.merged.unreadable` (the page: 读不了 / 文件损坏，可能不全, delete and import again; a damaged `.gz` keeps what was read before the damage); a text whose gzip cannot be written (the disk full) stays as it is, listed in `.merged.unwritten` (the page: 没转换成, free space and reopen). A text converted whose delete is refused is listed in `.merged.left`: not converted again while its `.gz` of the same state is there (its words are in it); each compile tries its delete again (no reconversion, no UPGRADE) and drops the entry once it is gone, or once that `.gz` is (deleted outside the page, or a restore left only the other state), the text then converted like any other. The entry is the text's name on, length and mtime (`leftKey`), so a turn keeps it. Listed files are skipped at each start and reload till they change; only a `.merged.left` text leaves `pending()` false, an unreadable or unwritten one keeps it true (its words are owed). Opening the 词库 page (`retry`) clears `.merged.unwritten` and `.merged.unreadable` (but a damaged `.gz`'s entry), never `.merged.left`. Imported words are missing until it ends. `turn`/`delete` resolve a dictionary by name under the sources lock, never the `File` the page listed, and act on all its files together: `turn` renames a text and its `.gz` both (the text turned back if the `.gz`'s rename is refused), `delete` deletes the text first and keeps the `.gz` if that is refused, so the two never split), `punctuation/`, `UserDataManager` (backup/restore) |
| `ui/` | Settings: `main/` (`MainActivity`, `MainFragment` = the home page, `settings/` pages and `SettingsRoute`, `settings/search/`), `setup/` (the first-run wizard), `common/` |
| `utils/` | Small helpers (`AppUtil`, `Const`, extensions) |

## The input view

`FcitxInputMethodService` owns the editing session (`EditingSession` from ime-core over
`InputConnectionEditor`), decodes `EditorInfo` into `EditorTraits` and `FieldPolicy`, and makes an
`InputView` per configuration. `InputView` is a tree of components (`dependency/`, a small
`UniqueViewComponent` injection) under one `InputWindowManager`, which shows one window at a time
in the keyboard area: `KeyboardWindow` (the layouts), `PickerWindow` (symbols / emoji),
`ClipboardWindow`, `TextEditingWindow`, `StatusAreaWindow`, `EditorInfoWindow` (debug, `internal.editorInfoInspector`), and in the voice build `VoiceWindow`.
The bar above is `KawaiiBarComponent`: idle (toolbar, number row, the result of a sum just written, clipboard suggestion, inline
autofill, decided by `IdleUiPolicy`; the sum is read off the field when what the service commits
ends with `=`, worked out by `Calculation` in ime-core, and dropped as the cursor leaves the `=`) or candidates (`KawaiiBarStateMachine`,
`ExpandButtonStateMachine` in ime-core).

Work tied to the view runs in the view's scope (`dependency/Functions.kt`): a theme or layout
change replaces the view and must cancel what touched the old one. Work that must survive it goes
on the service's scope.

### Notices

What the keyboard says goes in one place, `UndoChip` (`input/learning/`), reached through
`FcitxInputMethodService.say` (a string, optionally an `UndoChip.Undo`), `BaseInputView.say`, or
`core.EngineNotices` from the engine's own threads (`tip`, a 2 s line; `failed`, steady, 6 s).
Its lifecycle (shown, lapsed, typed away, put away, undone) is ime-core's `NoticeState`, which
`UndoChip` calls; the window, animation and rows stay in `UndoChip`. Where it goes is ime-core's
`UndoChipPlacement`: docked above the keyboard (a long press's chip over the bar while it shows no
candidates; below a window with no room above when it fits), floating on the keyboard's side
toward the screen's middle (`floating`, fed the keyboard's rect and the room by
`InputView.undoFloating`). The rules, which a review checks every new switch or act against:

1. **A state the keyboard does not show is said when it changes, both ways**: 不用我的输入习惯
   off and back on each say so. A switch said one way only is half done.
2. **What the keyboard shows is not said**: one-handed, floating, a theme, another input method.
   The keyboard changing is the notice.
3. **A result that lands elsewhere is said**: copied, cut, cleared, a report copied, could not undo.
4. **A failure is always said** (a disk that failed, an engine that cannot load, the list changed
   under a menu).
5. **One at a time**: a new notice takes the shown one's place in the same box (words crossfaded,
   size eased); the one replaced lapses, its act standing. With 撤销 it stays 6 s, a line said 2 s
   (longer as accessibility asks); a tap puts it away, typing too, except a failure `EngineNotices.failed` says
   (`steady`): the key it failed on reaches the keyboard after it; said once, it stays 6 s too. A
   replaced row's 撤销 takes nothing back, and the fading row swallows taps (none reaches the box).
6. **In the keyboard only this notice**: no `Toast` (Android 13 drops an input method's, see
   [pitfalls](pitfalls.md)), no `Snackbar`. A toast only when no keyboard is shown to say it on.

Its colours are the theme's: `popupBackgroundColor` under a `dividerColor` hairline, 撤销 a text
button in `accentKeyBackgroundColor` kept at 4.5:1 on it (ime-core's `Contrast.legible`, blended
toward `popupTextColor`; `UndoChipColourTest` checks every preset) with a rounded
`keyPressHighlightColor` ripple, a thin line between it and the words. 撤销 takes the chip's end and,
by a `TouchDelegate`, the line beside it: a near miss is not a tap that lapses the act.

### Motion

Every animation takes its length and curve from ime-core's `Motion` (Short 100 ms, Medium 120,
Long 150: nothing longer than 150 ms, the owner's rule of 2026-10-23; going is three quarters of coming) and `utils/Motion.kt` (`motionSettings()`, the
curves' interpolators): no literal durations. Android's 移除动画 or 禁用动画 makes each instant. What
typing does is never animated: a key's preview, the candidates changing, a layout switched (the
pickers included), the bar going from idle to candidates. Short: the long-press keys and a
candidate's menu growing out of what was pressed (`utils/PopIn.kt`), a notice replaced. Medium: a
notice coming, one window for another (`InputWindowManager`, unless the window sets its own), the
idle bar's views and the ⋯ icon. Long: the candidates expanded (`BaseExpandedCandidateWindow`'s
slide from the top) and the whole keyboard moving.

### Keys

A layout is a list of rows of `KeyDef`s (`TextKeyboard.Layout`, `T9Keyboard`, `NumberKeyboard`, `HandwritingKeyboard`):
an appearance (text, image, width, variant) and behaviours (`Press`, `LongPress`, `Repeat`,
`Swipe`, `DoubleTap`), each a `KeyAction` (`FcitxKeyAction`, `SymAction`, `CommitAction`,
`LayoutSwitchAction`, `QuickPhraseAction`, `UnicodeAction`, `LangSwitchAction`,
`SpaceLongPressAction` …). `CommonKeyActionListener` performs them. A key's long-press
characters come from `PopupPreset` (built in) overridden by the user's `PopupOverrides`; the first
of them is also what swiping the key types and the small label in its corner: one concept, one
setting. Which way a vertical swipe types it is `SymbolSwipe` (ime-core): the chosen way the corner
character, the other way the letter as a tap, swiping off nothing (a slide off the key takes the
press back). A long press's keys (`PopupKeyboardUi`, `PopupMenuUi`) are one small size whatever the
key (`PopupComponent.LONG_PRESS_KEY_*`; as wide as the key pressed, 九键's 7 filled the screen),
floating above it, the bubble gone; the finger picks by how far it moved since they opened, not
where it is (`PopupFocus` in ime-core: 0.6 of a key a step, held past a row's ends, an empty slot's
nearest key, a little hysteresis, only down off the key cancelling), each new key a buzz under 按键振动
(`InputFeedbacks.hapticFeedback`). 微软 and 紫光 双拼 get a `;` key right of L (`semicolonKey(ime, scheme)` in ime-core's
`input/keyboard/SemicolonKey.kt`, which `TextKeyboard.showSemicolonKey` asks); the split halves have none, the row's other keys widen to fill (`fitRow`/`showKey`),
and `;` comes from l's long press. The keyboard area never passes 70% of the screen's height
(`KeyboardHeightCap`), so 很高 in landscape leaves the field room.

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
单手模式 turns it on at the side last used, hidden `ThemePrefs.oneHandedSide`, right at first, or off): while the letters do not split, every board, the letters
too, goes to that side, 80% of the width, a phone's 420 dp at most (`SplitKeyboard.placement`, which
decides both). The room beside it has the arrow, which then sets `oneHanded` rather than
`splitDock`, and a button back to the whole width.

Floating (`ThemePrefs.floatingKeyboard`, the status area's 悬浮键盘; the input view is made anew for
it): `keyboardView` is `FloatingKeyboard.widthDp` wide (70% of the screen, 300–420 dp), its boards as
much shorter (85% at least), with `FloatingHandle` at the bottom (24 dp: a short bar and the dock button;
`ThemePrefs.floatingHandle` puts it on top, the view made anew). The dock button is 56×24 dp, a
smaller touch target than 48 dp, traded for a thin handle (review R11). The view is clipped to rounded corners
with a shadow (`FloatingCorners`; users asked for both). The radius is the keys' radius plus their
least margin to the edge (`FloatingKeyboard.cornerDp`, the nested-corner rule: the curve runs
alongside the corner keys' and clips none), square where the keys are (按键圆角 直角, the owner's one setting for corners); the
handle is a shade of the bar towards the key text (`FloatingKeyboard.handleColor`), as some themes'
bar is the keyboard's colour (on top; at the bottom it is the keyboard's own). As Gboard's, it never
goes into a strip of `FloatingPlacer.DOCK_MARK_DP` above the bottom (the IME's own nav bar, gesture
navigation's switch/collapse row, counted by `BaseInputView.floatingBottomInset`); dragged down to that
it is in the docking zone (`FloatingDrag` in ime-core; where a drag began at the bottom, it first
floats there, only a push of `DOCK_SLOP_DP` on down): the strip shows the mark (accent, opening up from
the bottom), a buzz under 按键振动, and let go there it docks: only a real lift docks (a cancel or a second finger never does), and docking keeps the last floating place. It is moved by translation
(`FloatingPlacer`) to a spot kept as shares of the room it moves in (`AppPrefs.internal.floatingKeyboardX/Y`),
so a turn keeps its corner. `FcitxInputMethodService.onComputeInsets` then gives the app the whole
height (it is not laid out again) and only `InputView.floatingRegion` takes touches
(`TOUCHABLE_INSETS_REGION`). Floating, the letters never split (`TextKeyboard.splits`, `widthDp`).

`ThemePrefs.alwaysShowHandle` (始终显示悬浮条, the view made anew for it) puts the same `FloatingHandle`
on the docked keyboard, top or bottom as `floatingHandle` says, nothing at its right end; with the board
at a side it goes with it as the bars do (`floating/BoardFollowers`), its grip at the board's middle. Its 24 dp
is added to the keyboard: the keys keep their height (the owner's rule, 2026-10-22: keys never give
up room to what is put round them), except where `KeyboardHeightCap` binds (landscape, a tall level):
the strip counts with the bars there and the cap wins (the owner, 2026-10-22), so the field still shows. All of this is
`floating/FloatingController` (the handle, the placer, the dock mark, the switches), the input view
only placing its views and passing its insets, attach and detach on. The grip held for the system's
long-press time (`GripPress` in ime-core) buzzes and sets `floatingKeyboard`: ThemeManager makes the
view anew at once, `setInputView` takes the old one out of the service's input area (the framework
cancels its touch) and the rest of that touch reaches the input area itself, unheld and unsplit.
`FloatingHandoff`, its touch listener till the finger lifts, passes it to the new view's controller
(`carried`), following only the grip's finger in that touch (`HandoffTouch` in ime-core: its pointer
id; a thumb resting on a key is in the same stream). Not the down time: events injected one at a
time (adb's `input motionevent`, as the UAT drives it) each carry their own. A new touch coming down,
unheld at the frame or on the new input view (`InputView.onInterceptTouchEvent` →
`FloatingHandoff.newTouch`), ends a handoff whose end was never seen. Grabbed with the finger below
where the floating keyboard can go (the docked grip is lower), that overshoot is slack in
`FloatingDrag`, taken up first as the finger rises, so the grip comes under it; it is no push into
the docking zone. TalkBack's long press has no touch: the keyboard floats with its grip where
the grip was, and no handoff starts. The new view, once laid out (`FloatingSwitch` in ime-core keeps
the moves till then, and the lift if it came first), puts its grip under the finger
(`FloatingKeyboard.underFinger`, kept within the room; the place is kept as a drag's is, so dragged
straight back into the zone the last floating place stays) and shrinks from the docked keyboard's
bounds about one point (`startScale`, `pivot`), so the drag moving it as it shrinks takes the shrink
along. Switched to floating any other way while shown (the status area's 悬浮键盘), the docked view
leaves its bounds as it is taken out (`DockedBounds`, taken only by the next view put in the same
frame; a shrink not shown before the keyboard hides is dropped) and the floating one shrinks from them
at its kept spot. Docking slides it down (`FloatingSwitch.dock`), its drag over meanwhile; the slide
ended, cancelled or its view made anew under it, it docks. One-handed to a side or across slides the
board and its bars (`floating/BoardMover`, `BoardMove` in ime-core); back to the whole width they
widen from where they are drawn, about the same pivot; a window or layout putting the board at a
side is never animated, nor a move while the keyboard is hidden.

Glide typing (`keyboard/GlideInput`, held by `TextKeyboard` and `T9Keyboard`): it sees every touch
before the keys (`dispatch`), `GlideGesture` deciding when a finger glides; as a glide starts the keys
are cancelled (their preview and swipe with them), other fingers are swallowed until all lift, and on
the lift the path, in key widths over the letters, goes out as `KeyAction.GlideAction`, which
`CommonKeyActionListener` hands to `FcitxAPI.offerGlide` (the engine's side: specs/engine.md).
Glide is off in password, address (URL) and email fields (`BaseKeyboard.wordField`): a rule the
owner set (2026-10-19), not a gap. A glide can only be read as a word of the lexicon and their text
is no word (a domain, a user name, a password); a wrong guess in a password is not even seen, and a
password is neither read nor learned; the space a glide puts before its word would break an address.
Keys there are typed as they are, and a finger sliding across them never starts a glide.

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
a text field: not `EditorInfo.isTypeNull`, where the host has no focus and every key is the app's): a lone Shift tap (≤250 ms, fcitx's
ModifierOnlyKeyTimeout, nothing pressed between) and Ctrl+Space switch between the Chinese input
method and English, back to the Chinese one left; Ctrl+Shift (either order) goes to the next enabled
one. fcitx's own toggle went to the group's first, which here is pinyin (the first run puts it
first so a field starts in it), so from wubi it went to pinyin; the host ports none of fcitx's hotkeys
(TriggerKeys, AltTrigger, TogglePreedit, the group switch; `config/config` is not read), so the two
never both switch, and the setting below leaves the keys to the app alone. Ctrl+Space is
consumed where it switches: unconsumed, Android goes to the next system input method (seen with Sogou
and Gboard). Where it has nowhere to go (one input method, or English not enabled) it is no shortcut
and goes on, through the host (no hotkeys of its own), to the app (an editor's completion). The setting `AppPrefs.candidates.switchShortcuts` (实体键盘快捷键, on) turns all three off:
the keys then go to the host and the app as any other.
The input method's name flashes by the cursor (fcitx's ShowInputMethodInformation).

With the hardware keyboard, handwriting and the nine keys are skipped by Ctrl+Shift
(`Engines.takesKeyboard`), and a character key pressed in one is typed by pinyin (全拼 or 双拼,
whichever is listed first), else the first enabled Chinese input method that takes a keyboard, else
English (`InputMethodSwitch.forKeyboard`), as Sogou and Baidu type pinyin whatever their touch
layout; the touch one comes back with the touch keyboard (`HardwareKeyboard.touchInputMethod`, the host thread's; the hand-off runs in the key's own host job).
`-`/`=` page the candidates while composing (`KeyRouter`, physical keys only).

Testing one (`uat/tools/emu-keyboard.sh`, the cases in `uat/17-hardware-keyboard.md`): the emulator, never redroid (redroid shares the host kernel, and a uinput keyboard there
is the host's too). The emulator's own `qwerty2` is an ALPHA keyboard (sticky Shift, `unicodeChar` 0
in the IME): register a USB keyboard with `/system/bin/uinput` (JSON `register`, vid/pid unknown so it
reads `Generic.kl`/`Generic.kcm`) and `sendevent` to its `/dev/input/eventN` as root.

Address fields swap the comma and the full stop for `/` or `@` and `.com` (`AddressKeys`); a key's
digit or symbol goes in there as it is, what is typed committed as typed
(`PendingInputPolicy.typesAsIs`, in `CommonKeyActionListener`), but for a key the input method
types with (`Engines.readsAsKey`: the nine keys' digits, the `;` of every 双拼 scheme whose layout types it (微软, 紫光), the pinyins' `'` and a
table's keys, 二笔's `;`, while something is typed, the table's from `Engines.tableKeys`); the nine
keys' column, separator and multi-tap are in `T9Keyboard` with decisions in `MultiTap`, `NineKeys`.
A nine key's long press shows one row, its letters in capitals, its digit, its letters (A B C 2 a
b c, `Explicit.inRow` the digit's place: in that order, the digit under the finger, narrower keys
when they don't fit; owner 2026-10-20), each committed as it is (`CommitAction`, after the first
candidate), then in rows of their own above, no line between, its symbols (`NineKeySymbols` in ime-core, the pref `t9Symbols`, set on 九键拼音's page
按键长按, `NineKeySymbolsFragment`; separate from 按键字符, owner 2026-10-19): by default 2–9's are
the full keyboard's corner characters of their letters (`PopupPresetTest` holds them to it), 1's
the rest of 搜狗's column. `KeyDef.Popup.Keyboard.Explicit.symbolsFrom` sets 2–9's apart: shown
as Chinese punctuation maps them and typed as keys to the engine, as the full keyboard's long
press is (the first candidate, then the mark: quotes in turn, brackets paired; `'` while
typing is the syllable separator, as there). 1's are
`asIs`, committed as they are (`.` not `。`). A digit or a lone letter is never a symbol: the
long press has those already. In an address field the column offers what addresses are made of
(`AddressKeys.column`: `/` or `@`, `.`, `.com` …) in place of punctuation, pinyin's syllables still
while typing, from the same `address` both boards take from `KeyboardWindow`; the bottom row is as
always (keys of their own there left the space an eighth of the width, its label pushed off). (A probe with `adb shell input text` showed the nine
keys committing the pinyin as typed before a mark: that is a hardware key's path, not the screen's.)

The 14 keys (`GroupedKeyboard`, `GroupedKeyboard.Name14`) are 拼音's touch keyboard when
`AppPrefs.keyboard.pinyinKeyboard` is `Keys14` (`PinyinKeyboard`, stored by name: `Full`, `Keys14`).
A tap sends its key's code (`SymAction`, as a syllable is), on a key of two letters with where the
finger came down: `CustomGestureView.downAt` (x / width, the view's, its margins too) at ACTION_DOWN,
made `clickAt` at ACTION_UP before `resetState` (NaN if the touch left the key or swiped past the
threshold; a few pixels' MOVE keep it), cleared once the click returns and at DOWN and CANCEL;
`BaseKeyboard.pressed(view, action)`, which `GroupedKeyboard` overrides, sends `Keyboard.tapKey`
for it. TalkBack's click, a swipe's own Tap, L and M send the code alone; a swipe its corner (1–0, `~ ! @ ?`), a
digit as a keypad key; a long press one row as the nine keys' (`Q W 1 q w`), the letters' full
keyboard symbols above. `'` is the separator key, ⌫ backspace, no ⇧, no glide. No 中/英 (R16 F4346,
owner 2026-10-26): English is the language key's round or the space's long press, as on the full
keyboard (with the language key off and the long press at its default, none: not handled, owner
2026-10-26); the bottom row's space takes what is left, ≥ 0.15 in every field. The syllables of the leftmost keys not yet taken are on the composing row's right
(`SyllableStrip`, id `code_row_syllables`, at most 60% of the row; the preedit is `code_row_text`),
from `EngineBridge.syllables` for the current input method; a tap takes one, backspace at the end
gives the last back. The composing row is 32 dp only while the input method shown has the strip
(pinyin on the 14 keys, 双拼 on the 18 or nine keys) and 18 dp in every other (`CodeRowHeight` in
ime-core, through `KawaiiBarComponent.preeditHeight(im)`); `InputView` sets it anew on each
`IMChangeEvent` and recomputes the keys' cap (`KeyboardHeightCap`), so the keyboard changes by 14 dp
between the two kinds (accepted, the user 2026-10-26) and never while typing in one; where the cap
binds (landscape, 很高) the area stays put and the keys give up the 14 dp instead. The settings
preview draws the full keyboard, with the thin row. The prefs are in `recreateInputViewPrefs`. The
bar's buttons keep their own 40 dp floor (`MIN_TOUCH_DP`), a different thing.

双拼's 18 keys (`shuangpinKeyboard == Keys18`) are the same grouped keyboard over `G18`, with the same
syllable strip, no 中/英 and 32 dp row while 双拼 is shown. 九键双拼 (`NineKeys`) is `T9Keyboard`
built for 双拼: its digits send `NINE_SP`'s codes, its 1 key `'` while typing, no 中/英 (a wider
space), no 连按 or custom letters; its syllables in its own left column, the strip off
(`ownsSyllables`; its 123 shows the strip), the row 32 dp as the 18 keys'.

九键英文 (`T9Keyboard.EnglishName`, a second `T9Keyboard` built with `english = true`) is fcitx's
English shown on the nine keys, reached only by 九键's 中/英 key (`NineKeyLanguageKey`, beside the
space as 搜狗's; owner 2026-10-20, replacing a 「英文键盘」 setting and English following
九键拼音 however switched to; its label `T9Keyboard.languageLabel`, the language in use full size, the other smaller and fainter, drawn because `AutoScaleTextView` lays a spanned text out with `StaticLayout`). `CommonKeyActionListener` sets `internal.englishOnNineKeys` and
activates English, or 九键拼音 back (`EnglishLayoutPolicy.toggled`); `KeyboardWindow.onImeUpdate`
shows the nine keys for English while the pref holds (`EnglishLayoutPolicy.nineKeys`), dropping it
as anything but English shows, unless fcitx's cached IM is English again by then (`endsNineKeys`: an
update queued behind 中/英 pressed again), and on the language key's round; a round that lands on English itself changes no IM, so
`CommonKeyActionListener.englishShownAnew` (KeyboardWindow's from its first attach, kept when detached: the picker's space runs the round too) shows it anew. Not a
listener on the pref: dropped before another IM comes, it would flash the full keyboard. So English come to any other way is the full keyboard and the input view
made anew (a turn, a setting) shows it as it was. The language key's round (and the space's long press set to it) goes on
from 九键拼音 when in 九键英文: straight to the one after it in the enabled list
(`EnglishLayoutPolicy.nextFromNineKeys`), the pref dropped first, not through 九键拼音's keyboard.
中/英 does nothing where the other is not enabled (`toggled(current, enabled)`: fcitx would switch to none, quietly). Its keys send `Keyboard.nineKey(digit, upper)` (predictive,
the engine's `Action.NineKey`) where `EnglishLayoutPolicy.spellsWords` says English suggests
(`t9EnglishTyping` Predictive, not a password, an address or NoSpellCheck, English's suggestions
on, read from `EngineBridge.englishSuggests`), a long press's letter then pinned into the word
(`Keyboard.nineLetter`, owner 2026-10-20), else commit letters by `MultiTap` themselves
(`CommitAction` replacing the last of a run), a long press's as it is. Whether a word is being spelled, for 1 (an
apostrophe then, else `. , ? ! ' -` in turn) and the column, comes from
`EngineBridge.englishTyping`. ⇧ stands where 重输 is, its state `NineKeyShift` (ime-core), lit by
auto-caps through `AutoCapsKeys`, which both boards implement. No glide on it.

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
   a category, or one section of it (`createUi(group, section)`: 按键 and 声音与振动 are two
   sections of `Keyboard`). These are what the keyboard reads.
2. **fcitx config** of the input methods: `conf/androidengine.conf`, its options in ime-core's
   `EngineOptions` (the former addon's table), read by the engine (`EngineSettings`). The pages for the app's own input
   methods are built by `InputMethodSettings` from a list of `Item`s (`Toggle`, `Choice`, `Flags`,
   `Page`, `Action` (a confirmed button that runs on the host thread, such as 恢复码表顺序: only
   `InputMethodConfigFragment` passes the `act` that runs it; the default `act = {}` of `create`/`addTo`
   ignores it, so an `Action` on another page does nothing), and `AppChoice`/`AppToggle` for the few app preferences that belong to one input method,
   such as 九键's multi-tap or English's auto-caps, or to all of them on the shared page, such as
   glide typing, paired brackets and replacing a sum), over `FcitxAPI.getImConfig`/`setImConfig`.
   The options every input method shares are one group in `EngineOptions`, so they are on one page,
   通用输入 (`InputMethodSettings.SHARED`, `SharedInputFragment`, saved through pinyin's config),
   each saying which input methods it is no use to (`notFor`); 每页候选数 is on 实体键盘
   (`HARDWARE_KEYBOARD`). An option no use to pinyin on the 14 keys names `Engines.PINYIN_KEYS`
   in `notFor` (said 「拼音 14 键」, `InputMethodNames`), shown whichever keyboard is chosen, not
   hidden: the hardware keyboard types full pinyin still. 拼音's 键盘 (`AppChoice` over
   `pinyinKeyboard`) is the first row of 拼音's page, in no section. 双拼's 键盘 (`AppChoice` over `shuangpinKeyboard`) is in its 方案 section, nothing on the page hidden; glide says not for 「双拼 18 键」 and 「九键双拼」 (`SHUANGPIN_KEYS18`, `SHUANGPIN_NINE`: neither glides), fuzzy u↔v not for 「九键双拼」 (`SHUANGPIN_NINE`, u and v one key). A page saves only the paths it shows (`InputMethodSettings.only`), so one
   left open behind another cannot write back the shared options it read before.

Rules for settings, all decided:

- **Few settings.** The home page is one flat list without headers, each row a title and a line of
  what is in it (`MainFragment.HOME`: 输入法 / 通用输入 / 词库与短语 / 外观 / 按键 / 声音与振动 /
  剪贴板与表情 / 实体键盘 / 备份与恢复 / 界面语言), each page's toolbar title is its row's; each
  input method's page has only its own options under a first row to 通用输入; fcitx's global
  options and addon list are not shown. Before adding a setting, ask whether a good default would do.
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
3. Engine behaviour: add an option to `EngineOptions`, read it in `EngineSettings.parse`, show it
   with an `Item` in `InputMethodSettings`; `nearest` maps old or odd stored values to a shown one.
4. Strings: `values/strings.xml` (English) and `values-zh-rCN`, `values-zh-rTW` at least; the other
   locales fall back to English. Write the summary as what the user gets, not how it works.
5. Test the decision in ime-core; if the page is hand-drawn, the search index too.
6. Describe it in `docs/settings.md`.

## Persistence and backups

`UserDataManager` exports one zip: `shared_prefs`, the clipboard database (less what an app marked
sensitive), the external files dir (fcitx config, custom phrases, imported dictionaries as their `.txt.gz` sources; the compiled `merged.ymd`/`.ymc`, any `.txt.gz.tmp`/`.txt.gz.convert.tmp` and the markers `.merged.failed`, `.merged.unreadable`, `.merged.unwritten`, `.merged.left`, `.merged.upgrading` are left out (`MergedDictionary.isCompiled`) and made again after a restore, themes). A restore overlays the dictionaries directory: an older backup's `.txt` is converted again over a same-name `.gz`; both `.gz` states of one name keep the newer (equal mtimes: the one turned on)
and the engine's `files/engine/` (learned words, word lists).
Restoring stops the host, closes the clipboard database, replaces the files, then restarts the
process (`AppUtil.restart`); a failed restore reopens what it stopped (`restartAfterFailedImport`).
`ReplaceFile` replaces a directory in one rename so a kill leaves the old or the new, never half.
New user data must be added to the export, and must survive a restore made by an older version
(missing files mean defaults).
The learning is merged, not replaced (U-100, `UserDataManager.importEngine`): the phone's
`learn.log`, its word lists, its old `*.user` logs and its backups stay; what the backup has of
learning (the zip's learning backup, or its record, or an older build's logs to convert, with its
lists) is moved into a directory of its own under `RestoreStash.DIR`, and the engine merges it as it
next starts (beside any stash not merged yet). A `learn.log.unreadable*` (a later build's log kept
aside) stays.

## Permissions and privacy

The app asks for `VIBRATE`, and the voice build for `RECORD_AUDIO`; nothing else. The build fails
if the merged manifest asks for anything more (`PermissionCheckTask` in `build-logic`), so a
dependency cannot add one unnoticed: if a feature needs a new permission, that is a product
decision for the owner, then the allowlist and `PRIVACY.md` change together. No network
permission, ever: no cloud features, no analytics, no crash upload. No notifications.

`FieldPolicy` decides what may be done with a field: a password or a field that asks not to be
learned from is *sensitive* (its text is never read for context, nothing is learned or kept in
`ContextMemory`, voice never types into a password). A field of this app itself (its settings, the dictionary
editors: `FieldPolicy.ownApp`, applied in `CapabilityFlags.forField`) is sensitive too, so what is
typed there is never learned. Any new feature reading the editor or
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

### Voice: the start

- 正在听 only once the microphone is really on: `VoiceOnset` (ime-core) reports the first sample not zero,
  `VoiceListener.Events.ready` → `VoiceHoldSession.Ui.ready`; before it the overlay and the panel say 准备中…. Why:
  the press-to-first-sample trace (dev/design/voice-start-latency.md) showed 正在听 lit hundreds of ms before
  anything was recorded, and the user chose to show it rather than record before the press (no pre-roll: the
  status bar's microphone dot would flash on every space). Not after the lift or a drop (the session's guard).
- 2 s of nothing but zeroes (`VoiceOnset.silent`, the privacy toggle off or the capture taken) say 麦克风没有声音
  (the user's rule), back to 正在听 at the first sound.
- The cue's vibration and the voice ticks go through `VoiceStyle.vibrate` → `InputFeedbacks.hapticFeedback`, so
  they follow 按键时振动, its length and strength.
- The microphone is read from the moment it opens, never waiting on the models: the VAD is built beside the
  reading (`VoiceEngine.takeVad`, a spare made ahead in a lock-free `VoiceSpare`; the voice key shown makes one,
  `letGo` frees it, and so does `letGoSpare` `KEEP_MS` (2 min) after the last show with no use), and what is read before it is in waits in `VoiceBacklog`, handed over in order. The handoff
  (held until the VAD, fed in order, dropped on a close, a late VAD still fed on a lift or cut, stretch
  starts shifted by what the backlog dropped) is `VoiceVadHandoff` in ime-core, tested there. The
  recognizer's load holds `VoiceEngine.lock`; the spare once shared it and 准备中 lasted the whole load.
- The record's buffer is 2 s (`VoiceCaptureEnd`'s backlog cap follows it), the least the HAL takes as a last try.
  If the HAL refuses the 2 s buffer and falls back to its minimum, the backlog fed when the VAD arrives (up to
  several seconds, synchronously on the capture thread) can outlast the buffer; audio read meanwhile is lost.
  Only on that fallback path; accepted rather than feeding in chunks between reads.
- Debug lines the UAT parses: `voice ready: fromStartMs=` (start() to the first sample not zero) and
  `voice speech started` (the VAD's onset, VO-23's lift cue).

### Voice: the tail after the lift and the gain

- **Tail**: lifting to type keeps the microphone on for `VoiceCaptureEnd.tail` (0.3 s, counted in
  samples at the record's rate) from the lift. The lift's `MotionEvent.eventTime` goes
  `VoiceHoldSession.lift(at)` → `Listening.finish(at, done)`; the capture thread turns it into a
  frame with `AudioRecord.getTimestamp(TIMEBASE_MONOTONIC)` (`VoiceCaptureEnd.frameAt`; a timestamp
  of nanoTime 0 taken as none). The stop point, lift frame + tail, is clamped to
  [read, max(read, min(captured, read + bufferFrames)) + tail + 0.2 s] (captured: the timestamp's framePosition, so a reader running behind still gets all up to the lift + tail; a framePosition past what the buffer can hold unread is the HAL's error and not believed at all, the tail then counted from what was read, as it kept the microphone open some 2.5 s past the lift); without a timestamp it is what was read + tail. Capture stops at the
  first window boundary at or past it, or after 1 s with no samples since the lift; a read error
  after the lift ends it as a stop does (the sentence typed). `VoiceCaptureEnd.window` is the
  per-window step, so its order (the lift seen before the window is counted) is tested in ime-core.
  Why: lifting 300 ms before the speech ends lost the last character 57.7% of the time, at the end
  10.7%; with the tail, lifting 300 ms early lost it 10.7% of the time, as lifting at the end did
  (`~/voice-eval/tailquiet`, table 1 of `dev/design/voice-tail-and-quiet.md`). Lifting in the cancel
  zone, `drop()`, `inputEnded()` and the panel's `onFinishInput`/`onStartInput` close at once, as before.
  A new press after the lift (`ACTION_DOWN`/`POINTER_DOWN` anywhere on the input view for the space
  card, the microphone for the panel) calls `pressedAgain()` → `Listening.cut()`: the tail ends,
  what was heard is still typed. On the card (it covers the keys only) the press counts for nothing; a press on the toolbar or the candidates ends the tail the same way and then acts as usual (design N2). The UI does not change (识别中… at once).
  On the panel only, Enter pressed after the lift and before the end (`VoiceTailEnter`, ime-core;
  R16 F4486) calls `pressedAgain()` once and waits, however often pressed: it is sent once the
  session ends, typed, nothing heard or the model/microphone failing, in the field it was pressed in;
  dropped with the session (`drop()`, another field) and by the 15 s give-up (`VoiceHoldSession.gaveUp`:
  that late, it would send a forgotten draft). It goes from `Ui.ended` through `postFcitxJob` and then
  the main thread: queued after the text's `commitOverEngine` job, which posts its own putting in to
  the main thread first, so the text is in the field before the Enter is pressed (`VoiceHoldSession.lift`
  commits before `end` for this). Backspace acts at once. The space card covers the keys, so there any
  press only ends the tail and counts for nothing (VO-25, unchanged by owner decision 2026-10-26).
- **Gain**: `VoiceGain` (ime-core), one per microphone opening, on each window after `read()` and
  before `AudioHistory`, the VAD and the level bar, so all of them hear the same. Level: the 3rd
  loudest window RMS of the last 31 (~1 s), the first 3 windows left out; env falls at most 3 dB/s;
  gain clamp(−30 − env, 0, +20) dB, held so a window's peak stays under 0.99; rises ramp across the
  window. Normal speech passes unchanged, but for its first two windows (~64 ms) after a quiet
  stretch, lifted up to the peak limit until the 3rd loudest catches up. Why: VOICE_RECOGNITION has no AGC (CDD 5.4.2), soft speech comes in at −50 to −60 dBFS where
  silero does not fire; at −26 dB CER 30.87% → 7.17%, no-result 15% → 0, noise 3·2·1·1 of 159.
  It is tailquiet's `Gain2` sample for sample (`VoiceGainTest`'s golden sample, ≤ 1e-5; its generator, with Gain2 copied in, is
  `lib/ime-core/src/test/resources/voice/gain_golden.py`, and each parameter changed alone changes it). Debug
  builds log one line of numbers at the close (`voice capture:` liftFrame, stopAt, read,
  segment.start, segments (stretches cut), envPeak, peakGainDb: the gain where env peaked) for the UAT (uat/12 VO-23/24/25).

## Strings and languages

The app speaks 简体中文, 繁體中文 and English (`AppLanguage`, chosen in the settings, independent of
the phone's language) and nothing else: `androidResources.localeFilters` drops libraries'
other translations. The host's own texts (the status area's toggles, tips, the input method name
overlay, the default group's name) are app string resources too (`AppHostStrings`); there is no gettext.
