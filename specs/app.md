# The app (`app/`)

Android glue, kept thin: read Android state and preferences, pass values into `lib/ime-core`,
apply what comes back. If a change adds an `if` that decides behaviour, ask whether it belongs in
an ime-core class with a test.

## Packages (`app/src/main/java/org/fcitx/fcitx5/android/`)

| Package | Holds |
|---|---|
| `core/` | The host's Android side: `KotlinHost` (the `HostApi` over ime-core's `HostCore`, its lifecycle and config files, [host.md](host.md#the-apps-side-kotlinhost)), `HostDispatcher` (the host thread and clock), `HostResources` (`AppHostStrings`, the host's texts from app resources), `HostApi` (suspend calls into the host; its `eventFlow`, the host's own `HostEvent`s out of it), `EngineBridge` (ime-core's `Engines` with their Android side: assets, user dir, saves; the host's `EngineHost`), `SubtypeManager`, `InputMethodNames`, `NativeMatrixKernel`; the Android-free DTOs of the same package (`KeySym`, `KeyStates`, `CandidateWord`, `InputMethodEntry`, `FormattedText`) are in ime-core, their `KeyEvent` conversions here (`KeySymEvents.kt`, `KeyStateEvents.kt`) |
| `daemon/` | `HostDaemon`: the one fcitx instance, shared by connections (`HostConnection`) |
| `input/` | The `InputMethodService` (`FcitxInputMethodService`), `InputView`, and what the keyboard shows: `bar/` (the candidate bar / toolbar, `KawaiiBarComponent`), `candidates/` (horizontal bar, expanded grid, floating window for physical keyboards), `keyboard/` (`TextKeyboard`, `T9Keyboard`, `NumberKeyboard`, `KeyDef`/`KeyDefPreset`, `KeyView`, `CommonKeyActionListener`), `picker/` (symbols, emoji), `popup/` (key previews, long-press keyboards), `clipboard/` (the history panel; 「切词」, `ClipboardChunksWindow`, shows ime-core's `ClipboardChunks` pieces, picked by tap or by a sideways sweep (ime-core's `SwipePick`), Chinese split by `HostApi.wordBoundaries`; the history at scale measured by `ClipboardScale`, JVM and device: on redroid at 10 000 entries opening ~30 ms, clearing ~0.2 s, a backup ~0.2 s; at 100 000 opening ~0.1 s, a page half way down ~0.35 s, clearing ~1.5 s, a backup ~2 s), `editing/` (text editing panel, `InputConnectionEditor`), `status/` (the "more" panel), `preedit/`, `broadcast/`, `wm/` (`InputWindowManager`), `handwriting/`, `floating/` (the floating keyboard), `dialog/`, `editorinfo/` (the `EditorInfo` inspector), `learning/` (a candidate's long-press menu worded by the app from the engine's `LearningResult.Menu`, `CandidateLearningMenu`; the undo chip, `UndoChip`, the keyboard's one notice: see [Notices](#notices)) |
| `data/` | File-backed managers: `prefs/` (`AppPrefs`, the managed preference framework), `theme/`, `clipboard/` (Room), `pinyin/` (imported dictionaries, custom phrases; sources are `<name>.txt.gz[.disable]`, named through `SourceNames`; `MergedDictionary` compiles the dictionaries turned on into `merged.ymd` on one `dictionary-compile` thread, keyed by a stamp of the sources, the base data and a compiler revision; the engine maps the file, uses a stale one until the new one is made, and is reloaded through `made`. An older build's plain `.txt` is work by itself (`SourceNames.unconverted`: `current()`, `pending()`, `mergedWords` count it): the next compile gzips it first (`Step.CONVERT`, the progress's first share, 正在转换 on the 词库 page, the idle bar's 正在升级词库格式) through `<name>.txt.gz.convert.tmp`, and is an upgrade (UPGRADE) only by what it finds: a text converted or not, a v1 `merged.ymd` dropped, or `.merged.upgrading` (written before the first conversion or the drop, deleted when a non-import compile ends, so a kill in between is still an upgrade at the next start); never by files' times. A text that cannot be read, or a `.gz` that is not gzip or is cut short, is skipped and listed in `.merged.unreadable` (the page: 读不了 / 文件损坏，可能不全, delete and import again; a damaged `.gz` keeps what was read before the damage); a text whose gzip cannot be written (the disk full) stays as it is, listed in `.merged.unwritten` (the page: 没转换成, free space and reopen). A text converted whose delete is refused is listed in `.merged.left`: not converted again while its `.gz` of the same state is there (its words are in it); each compile tries its delete again (no reconversion, no UPGRADE) and drops the entry once it is gone, or once that `.gz` is (deleted outside the page, or a restore left only the other state), the text then converted like any other. The entry is the text's name on, length and mtime (`leftKey`), so a turn keeps it. Listed files are skipped at each start and reload till they change; only a `.merged.left` text leaves `pending()` false, an unreadable or unwritten one keeps it true (its words are owed). Opening the 词库 page (`retry`) clears `.merged.unwritten` and `.merged.unreadable` (but a damaged `.gz`'s entry), never `.merged.left`. Imported words are missing until it ends. `turn`/`delete` resolve a dictionary by name under the sources lock, never the `File` the page listed, and act on all its files together: `turn` renames a text and its `.gz` both (the text turned back if the `.gz`'s rename is refused), `delete` deletes the text first and keeps the `.gz` if that is refused, so the two never split), `punctuation/`, `quickphrase/` (`QuickPhraseManager`: the user's quick input, `mine.mb` and `mine.used` read, changed and written whole under one lock, the settings page and the keyboard both writing; backups take `data/quickphrase.d` as a followed directory), `UserDataManager` (backup/restore) |
| `ui/` | Settings: `main/` (`MainActivity`, `MainFragment` = the home page, `settings/` pages and `SettingsRoute`, `settings/search/`), `setup/` (the first-run wizard), `common/` |
| `utils/` | Small helpers (`AppUtil`, `Const`, extensions) |

## The input view

`FcitxInputMethodService` owns the editing session (`EditingSession` from ime-core over
`InputConnectionEditor`), decodes `EditorInfo` into `EditorTraits` and `FieldPolicy`, and makes an
`InputView` per configuration. `InputView` is a tree of components (`dependency/`, a small
`UniqueViewComponent` injection) under one `InputWindowManager`, which shows one window at a time
in the keyboard area: `KeyboardWindow` (the layouts), `PickerWindow` (symbols / emoji),
`ClipboardWindow`, `TextEditingWindow`, `StatusAreaWindow`, and `EditorInfoWindow` (debug, `internal.editorInfoInspector`). Voice has no window: its two ways in (the space held, the bar's microphone tapped) put an overlay over the keys as the keyboard view's own child (`InputView.overKeys`: `VoiceHoldOverlay` over all of it, `VoiceTapOverlay` from under the bar to the keys' bottom), so it moves and scales with a floating keyboard; the window under it left as it was.
The bar above is `KawaiiBarComponent`: idle (toolbar, number row, the result of a sum just written, clipboard suggestion, inline
autofill, decided by `IdleUiPolicy`; the sum is read off the field when what the service commits
ends with `=`, worked out by `Calculation` in ime-core, and dropped as the cursor leaves the `=`) or candidates (`KawaiiBarStateMachine`,
`ExpandButtonStateMachine` in ime-core). Its buttons besides the menu key are the user's
(`internal.toolbarButtons`, one string): `ToolbarConfig` in ime-core parses it (empty is the v0.4
bar) and, given the situation (voice build and not a password, chttrans offered, one-handed
offered, the input method with keyboards to pick from: `KeyboardPicker` through `PanelActions.chooseKeyboard`, the ⋯ panel's 键盘 too) and how many fit at the right, says which go on the opened toolbar (⋯ fixed at its end,
the right's overflow before it) and which at the right; `IdleUi.arrange` places them, again as the
bar's width changes. The ⋯ panel's own actions live in `PanelActions`, called by
`StatusAreaWindow` and the bar's buttons alike. A new panel entry that should be a button too is a
`ToolbarButton`, its `ToolbarButtonLooks`, its action in `PanelActions`, and an `on(…)` line in
`KawaiiBarComponent`.
Where `IdleUiPolicy` shows nothing (`IdleUi.emptyBar`), an email's or a
URL's field gets its address suggestions (`input/address/AddressStrip`, the choice ime-core's `AddressSuggestions`:
the field, the text around the cursor, the two lists, the counts in; the text to put in, what it replaces, the cursor out).
A field left for another (`AddressLearning`, kept by the service: the keyboard hidden, the view rebuilt, the same field
restarted do not leave it; the same field is the same `ContextMemory.field` name *and* the text last read, as a Compose
screen's or a web page's fields share their view's name; the service destroyed learns the last one; a 「全部清空」 since
the field started, `AddressStore.forgotten`, keeps what it held then from being learned again), `AddressBook.left` learns from the text it was left with, if it changed while the keyboard was up
(the whole text as the field started, its selection too, is what a change is told by):
the suffixes' counts and the whole email addresses (`keyboard.rememberAddresses`; off, none offered nor learned, those kept kept),
never in a password's or an incognito field (`CapabilityFlag.PasswordOrSensitive`), before the first unlock, or with the habits off
(`internal.useHabits`, which also hides what was learned). The book goes with the learning backups (`LearnBackup`
`ADDRESS_COUNTS`, `ADDRESSES`, opaque to the engine: `Engines(addresses, putAddresses)` → `AddressStore`), merged in
on a restore by `AddressBook.merged`, put back whole by 退回恢复前; a backup without them (v0.4.0's) leaves them as they are.
A 「备份全部」 zip carrying a learning backup keeps the phone's two prefs over the zip's (`UserDataManager.keepAddressBook`):
the zip's book comes in through the stash's merge, so 退回恢复前 holds the phone's.

Work tied to the view runs in the view's scope (`dependency/Functions.kt`): a theme or layout
change replaces the view and must cancel what touched the old one. Work that must survive it goes
on the service's scope.

A view made anew (A2) takes the engine's whole state as it starts handling events
(`BaseInputView.onStartHandleFcitxEvent`, run once subscribed to `eventFlow` so no event between the
read and the collector is lost): the input panel, input method and status area, and the last
candidate list (`HostApi.candidatesCached`; `CandidatesView` the page, `pagedCached`), never only
what later events bring. `KotlinHost.stop` empties the caches, so a view made after a restart shows
no dead engine's state. Why it is made anew is `ViewRebuild` (ime-core): a setting
(`recreateInputViewPrefs`, the candidates' prefs, any theme pref but floating) first ends what was
typed as leaving the field does (`focusOutIn`: pinyin's code dropped, the field's composing text,
English's word or handwriting's, committed; user, 2026-10-27 and 2026-10-31), so no code typed on the
14 keys reaches the full keyboard's session and no code row is left over an empty bar; then it tells
the engine its context again, as the field stays and no input restarts to. `onConfigurationChanged`
ends it the same way, so of dark mode's two (it and the theme's rebuild, in either order) the last
tell follows the last end. Floating and docked (`FLOATING`, told from a theme change by the
`floatingKeyboard` value the views were made for), the system's `onCreateInputView` and a language
picked anew keep it.

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
   (`steady`): the key it failed on reaches the keyboard after it; said once, it stays 6 s too.
   The edit menu opened over the notice's window (`UndoChip.covered` with the menu's box,
   `NoticeState.cover`) puts away what typing would; a steady one is hidden, its time stopped, and
   back for its 6 s as the menu goes (`uncover`). One said while the menu is up where it meets it is
   hidden from the start (`show(hidden)`), shown and timed as the menu goes; one elsewhere (over
   the bar) is left alone. A
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
`Swipe`, `DoubleTap`), each a `KeyAction` (`HostKeyAction`, `SymAction`, `CommitAction`,
`LayoutSwitchAction`, `QuickPhraseAction`, `UnicodeAction`, `LangSwitchAction`,
`SpaceLongPressAction` …). `CommonKeyActionListener` performs them. A key's long-press
characters come from `PopupPreset` (built in) overridden by the user's `PopupOverrides`; the first
of them is also what swiping the key types and the small label in its corner: one concept, one
setting. A vertical swipe reaches one of two layers, the corner symbol or the key's shortcut
(`KeyDef.shortcut` names it, `KeyShortcuts` in ime-core holds the user's over `KeyShortcutPreset`'s,
stored in `key_shortcuts`); the 滑动 setting (`SwipeLayers`, seven choices) says which way reaches
which. `KeySwipe` (ime-core) decides the lift: a layer with nothing, or a way off, is a tap; swiped
past and back, a cancel; both ways off, a slide off the key takes the press back. Function keys
(space, backspace …) reach their own up and down shortcuts, a way off leaving them as they were;
space and backspace lock a touch to one axis (`KeyGestureRecognizer.axisLock`). `KeySwipes` (app)
wires it on the typing keyboards (`shortcuts = true`), buzzes at the threshold, opens the edit menu
(`EditMenuLayout`, `PopupMenuUi.Fixed`) there: up where a notice its size goes (`EditMenuLayout.place`
on `UndoChipPlacement`'s place, which `InputView` hands `PopupComponent.menuRoom`; over the bar where
that is off the view), 粘贴 across from the key, not under the finger (the user, 0.5.1's test build),
picked by how far the finger moves (`PopupFocus`), a notice shown put away or, a failure, hidden till the menu goes (`onMenuShown`, `onMenuGone`,
`UndoChip.covered`/`uncovered`). A
shortcut named in the key's bubble (`PopupAction.PreviewUpdateAction.words`) is fitted by
`PreviewLabel`: a function key's size scaled as the keys' text (a letter's too, `symbolSize`),
characters counted and cut as seen (`BreakIterator` graphemes: a flag, a family, an accent are one), a bubble five of them wide, smaller
to a least, then cut with an ellipsis; `KeyShortcutRunner` runs a shortcut after what is
typed is in, the field's edits as `EditPlan` steps. 清除 (`EditPlan.clear`: what is typed, else the
selection, else all before the cursor, else all after) is kept in memory (`ClearedText`, under the EditorInfo input started with, by identity, forgotten as
input starts in another field or finishes; at most 16) with the runs of deletions (`ClearedText.deleting`:
Backspace pressed, held, the panel's, or the backspace swipe's selection deleted, one after another,
from `handleBackspaceKey`, `sendBackspaceKey`, `deleteSelection`; not in a sensitive field nor a
`TYPE_NULL` one). A run's first deletion reads the field (1000 before, 64 after; one round trip,
`getSurroundingText`, from API 31, its offset telling whether the text before is all there is, else
each side and the cursor tracked: `ClearedText.Read.whole`), the rest only counted: a key event takes what the editor calls a character, so what a run took is told when it is
put back, from what is left (`ClearedText.settled`: all of it read, by its length; cut short, the one
place its end meets, each press a code point at least and a character, `BreakIterator`'s, at most;
what follows the cursor unchanged); a selection's deletion in a run reads again and settles it so
far. Whatever else changes the field ends a run and drops the last run and all kept before it, a
clear since kept (`edited`) unless it read short (`Cleared.whole` false: it cannot tell text typed before the cursor, so it goes like a run): the service's editor is a `WatchedEditor` (commits, compositions,
selections, deletions, but a Backspace's or the swipe's own and the 复制 shortcut's, made `quietly`),
and Enter, the arrows, key events sent, the context menu (`changing`), a hardware key, another input
method (not the same one focused again as the screen turns), a preedit shown on the bar tell it too.
A tap on the field the editor tells (`onViewClicked`, not a WebView's or Compose's) closes the run
(`moved`): the next deletion starts another, the last still put back where the field reads as it
left it. An empty pair Backspace takes whole changes what follows the cursor: that run is never put back. 恢复 putting the last clear or run
back first while the field reads as each left it and nothing is typed (a clear that read short
(an app giving less before the cursor than asked) matched by the selection and what is after the
cursor alone, and put back at the cursor, not the field's start; a run's text before the
cursor, where it was; pinyin never reaching the field, a clear of what is typed drops those before it;
a Backspace after one put back starts a run of its own); what was typed is dropped and typed
again by `HostCore.clearTyped`/`retype` (the router's log of the keys since nothing was typed: plain
keys alone, a pick, a key's pick (a digit, space: `Snapshot.picked`) or a candidate's action ending it,
as sent again by its index it could land on another word), on the keyboard it was typed with alone
(`HostCore.Retyping.keyboard`, the app's `KeyShortcutRunner.keyboardOf`: `ImKeyboards.typedWith`, the
layout shown and 双拼's scheme; another's keys' codes go to a session of their own). A selection the
editor's report lags is cleared, not kept. A field kept from learning (`PasswordOrSensitive`) is cleared with nothing kept;
an app's own `restartInput` is another input, its clears forgotten. The
marks on a key are placed by `KeyMarks` in slots of its face (`KeyMarks.Slot`: the corners, the edges' middles, under the
label), on the keyboard and on 按键字符和快捷键's keys (`KeyCaps`) alike:
two settings with one list of places (`KeyMarkPosition`, `KeyMarks.At`: 右上 右下 左上 左下 底部 无), 标点位置 (`AppPrefs.keyboard.punctuationPosition`, key `punctuation_position`, the theme's until 0.5.1, its stored TopRight/Bottom/None read as before; top right by default) for the symbol, 快捷键图标位置 (`shortcutMarkPosition`, top left) for the shortcuts, each in one slot on every key whichever way swipes to it (a function key's down one in the shortcut's slot mirrored top for bottom). 底部 is a shortcut's bottom edge middle, a symbol's under the letter; on landscape's and 紧凑's low keys (`KeyMarks.onKeys`) the symbol's 底部 is the top right (bottom right where the shortcuts are), the shortcut's the first of top left, top right, bottom left, bottom right the symbol does not take. A shortcut with no room on its key is tried in that key's other free corners in that order before it is left out (`KeyMarks.elsewhere`, `KeyMarkYield`; a function key's two together, still mirrored): `KeyMarksEverywhereUatTest` finds none left out on any keyboard, mode or pair. The settings page never lets the two share a place but 无 (`ManagedPreferenceUi.Apart`: the other's place greyed out in `MyListPreferenceDialogFragment`, noted whose); two stored in one place (an import, a backup) are read apart by `KeyMarks.settled`, the symbol yielding top for bottom on its side, 底部 to top right (`markSpots()` is what every key and preview reads), and stored so at the next start (`AppPrefs.dropRetired` → `settleMarkPositions`; `Apart.read` reads them so on the page till then, and a list read again as the other changes: the summary is what the keyboard draws). Both are in the service's `recreateInputViewPrefs`: a key no swipe marks lays its symbol out as it is made (`KeyMarksUatTest.eitherChangedTheKeyboardIsMadeAnew`); an `AltTextKeyView` turned works its symbol's slot out again for the new orientation. (0.5.1's testers saw the symbols pushed bottom right, then the shortcuts there after 滑动 changed ways: placement followed the swipe; `KeyMarksUatTest` holds the first open to what any setting change leaves, `KeyMarksTest` every combination.)
五笔's 全部字根 leaves the letters' shortcuts unmarked (`TextKeyboard.charShortcutMarks`: its roots start in the top left corner), shown as 键名 at 紧凑 though (`TableKeyboard.atHeight`, the setting kept); at 键盘高度 紧凑 (`KeyboardHeightLevels.isCompact`, read as the keys
are made by `keyboardCompact()`) the symbol never under the letter, the shortcuts drawn as anywhere, smaller, and the keys' text 0.9 (`KeyTextScale`, the smaller of it and floating's; 大千's symbols 0.9 again, `KeyTextScale.dachen`, cut off on a 360 dp phone otherwise). The service
makes the keys anew only when a height change crosses into or out of 紧凑 (`compactBuilt`, as
`floatingBuilt`); the theme page's preview does the same in `recalculateSize`. A key's glyph (label, icon) and its marks (shortcut icons and texts, a letter's symbol) keep apart only where their ink meets (`KeyMarkYield`, by `AutoScaleTextView.inkBounds`; an icon's box), every slot worked out as the top right one mirrored. `KeyView.settleMarks` after the face's layout (`KeyView.glyphs`: a `TextKeyView`'s label, an `ImageKeyView`'s icon, an `ImageTextKeyView`'s two as one; `KeyMarkYield.key`): marks meeting each other first smaller about their corners, as much, the least that parts them (`keptApart`), else left out (no move parts them), then the marks into their corners (a corner's sideways or out, the less; an edge's middle only out), then the glyph moves (any way, the least, in the face), then the marks smaller (a symbol or text to 6 dp, an icon to 7, `MIN_SYMBOL_DP`, `MIN_ICON_DP`), then the glyph smaller about its middle, a fifth at most, then a shortcut still meeting tried in the key's other free corners (`Mark.elsewhere`), then left out, the most expendable first (`Mark.drop`: a function key's down shortcut, a shortcut); a symbol is never left out (`DROP_SYMBOL`: kept, the glyph at its smallest; drawn over it were that not enough, which the walk finds nowhere). An `AltTextKeyView` (letters; `KeyMarkYield.letters`, run by `BaseKeyboard.onLayout` over all its letter keys once they are laid out): the letter stays, its marks into their corners and smaller; what still meets, every letter of the keyboard the same smaller about its baseline, a quarter at most (a shortcut that would need more is tried elsewhere, then left out; a symbol that would, the letters a quarter smaller). The views are moved by `KeyView.settle`: scaled from their own top left, translated to the ink `KeyMarkYield` gives; a mark left out is drawn at alpha 0. `KeyMarksEverywhereUatTest` measures every key of every keyboard (standard, 紧凑, floating, one-handed, landscape; every pair of places the settings allow) for nothing drawn over anything else and no symbol left out, (the system's font size changes none of it: the keys are sized in dp), and prints what was left out (nothing) and moved. The icon 8 dp at 紧凑. `KeyCaps`' keys don't yield. 注音 keyboards name their keys `zyfull/<QWERTY letter>` and
`zy9/1`…`zy9/11`, the groups by their place (`ZhuyinShortcutNames`; the tones and 注音九键's slot none, as 九键's 1). A long press's keys (`PopupKeyboardUi`, `PopupMenuUi`) are one small size whatever the
key (`PopupComponent.LONG_PRESS_KEY_*`; as wide as the key pressed, 九键's 7 filled the screen),
floating above it, the bubble gone; the finger picks by how far it moved since they opened, not
where it is (`PopupFocus` in ime-core: 0.6 of a key a step, held past a row's ends, an empty slot's
nearest key, a little hysteresis, only down off the key cancelling), each new key a buzz under 按键振动
(`InputFeedbacks.hapticFeedback`). 微软 双拼 gets a `;` key right of L (`semicolonKey(ime, scheme)` in ime-core's
`input/keyboard/SemicolonKey.kt`, which `TextKeyboard.showSemicolonKey` asks); the split halves have none for them, the row's other keys widen to fill (`fitRow`/`showKey`),
and `;` comes from l's long press. A table's letters keyboard is told by the engine
(`EngineState.tableKeyboard`, ime-core's `TableKeyboard`, set in `EngineBridge.followed` after each
answer and context, not for an input method latched unloadable; its code keys once its session is made,
the roots and 拼 key from the settings alone): a code key its face has nowhere else (`extraKey`: 二笔's `;` in that slot, 仓颉's `*` in the
`*` one next to it while its corners show letters; split, the same slots after L in the right half, the
gap between the halves filling what is left, so that half is six keys while one shows), its roots over the letters (`label`, the letter in
the corner; 仓颉's by default, 五笔's 键名 or all its roots as its page says) and on a long press, in
the key's bubble kept inside the keyboard (`rootsOf`), and its 拼 key (`LookUpKey`, two keys, the one
shown dark while `lookingUp`) in Shift's place, sending `Keyboard.LOOKUP`. While a code is typed
(`ReturnKeyAppearance.composing`) 确定 (`ConfirmKey`, Return's action) stands in Return's place.
The keyboard area never passes 70% of the screen's height
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

Glide typing (`keyboard/GlideInput`, held by `TextKeyboard`, `T9Keyboard` and, for strokes alone, `GroupedKeyboard`): it sees every touch
before the keys (`dispatch`), `GlideGesture` deciding when a finger glides; as a glide starts the keys
are cancelled (their preview and swipe with them), other fingers are swallowed until all lift, and on
the lift the path, in key widths over the letters, goes out as `KeyAction.GlideAction`, which
`CommonKeyActionListener` hands to `HostApi.offerGlide` (the engine's side: specs/engine.md).
Glide is off in password, address (URL) and email fields (`BaseKeyboard.wordField`): a rule the
owner set (2026-10-19), not a gap. A glide can only be read as a word of the lexicon and their text
is no word (a domain, a user name, a password); a wrong guess in a password is not even seen, and a
password is neither read nor learned; the space a glide puts before its word would break an address.
Keys there are typed as they are, and a finger sliding across them never starts a glide.

The same touch is a stroke (笔画筛选) where its switch is on (`AppPrefs.keyboard.strokeFilter`
beside `glideTyping`, which keeps 0.4's `glide_typing` key, so a 0.4 user's glide carries over). The
two are never both on: the shared page's `AppToggle.turnsOff` turns the other off as one is turned on,
its switch flipped where it is seen, no dialog; so English glides none while strokes are on.
`GlideInput` decides at the finger's down (`strokes`): the switch, a word field, pinyin typed (`BaseKeyboard.imTyping`, which
`KeyboardWindow` sets from the preedit, not in a temporary mode: its panel alone has an aux line above) and a keyboard whose input method filters (`filters`:
`InputMethodCatalog.filtersByStrokes` of the one shown, on `TextKeyboard`, `T9Keyboard` and
`GroupedKeyboard`, the 14 and 18 keys, which have no glide). `GlideGesture` starts it far sooner than
a glide (`STROKE`, 0.3 of a key any way, keys counted as wide and tall, before the long press): a
竖 from the bottom row or a short 点 must be one before the key's swipe (36dp) is, so while pinyin
is typed a key's swipe is a stroke; on the lift the path, in key widths both ways, is read by `StrokeShape` (ime-core) and
sent as the engine's stroke key, `KeyAction.SymAction` of `Keyboard.strokeKey`; a shape it reads
as none (up, up to the left) is dropped.

The 060 redraw's first step (`input/keyboard/canvas/`, `AppPrefs.Internal.canvasKeyboard`, 开发者 →
新绘制 26 键, off): `TextKeyboard` adds a `KeyboardCanvasView` over its rows and makes them
INVISIBLE, still laid out (the popups, glide's key map and the gestures use their places). The canvas
draws the scene `lib/keyboard-scene` makes of qwerty.xml in the look converted by ime-core's
`LookTheme` (`ScenePainter`: one `RenderNode` a key a face, both kept in the tree and a press only their alpha, since a node left out of a frame loses its recording; a
software canvas, a screenshot's, gets the ops drawn straight); `TextKeyboard.scene` builds the
`Env` from its state (`TextBoardState`, `SceneMarks`, the texts), asked again on `invalidateScene()`,
which each of its show functions calls. It is made again only when what it is made of changed: the
same theme, layout and measurer (by identity: `SceneThemes`, `SceneLayouts` keep theirs a process)
and an equal `Env` return the last scene, which the view then neither records nor tells TalkBack
again. `composing` reaches the `Input` only with a table (qwerty.xml asks it of nothing else), so
pinyin's first key and its committed word make no scene; in a field with an action, Return's label
changing with the composing (Done to Enter) still does. `AndroidTextMeasurer` keeps the last 512
texts it measured (text, font, weight, size), so a scene made again measures only what is new.
Systrace sections: `scene` (the view asking), in it `theme`, `env`, and the builder's phases
(`expand`, `place`, `cascade`, `paint`; `Phases`, `SceneTrace`), `measure text` under them. `BaseKeyboard` intercepts every touch as the vivo workaround
does and hands it to the view of the key the scene's `keyAt` names (`findTouchTarget`), so a key's
gestures are its view's, unchanged; the view's `onPressedChanged` (CustomGestureView) shows the press.
TalkBack reads the canvas's `ExploreByTouchHelper`: a virtual node a key, its words the scene's
`a11y` (an `a11y_*` name the app's string), id, state and actions its view's; a key whose view's state
changed (Shift) since the last scene is told as `CONTENT_CHANGE_TYPE_STATE_DESCRIPTION` on its node.

What the scene is given, so it draws what the views would: qwerty.xml is
`lib/keyboard-scene/src/commonMain/layouts/qwerty.xml`, the one file the app copies and the engine's
tests read. A letter's corner is `{alt:x}`, the app's `Texts.alts` (`SceneLetters.alts` of
`TextKeyboard.cornerOf`: the user's own long press first, else Shift's swipe override, else the
swipe; through `transformPunctuation`; the letter itself under a table's labels); a table's roots or
names replace the letter through `when="roots"` and `{root:x}` (`SceneLetters.roots` of
`TableKeyboard.label`), in two lines at 0.55 above three roots, as the views drew them.

The scene is made only as the view draws (`onDraw`, timed at debug level): a touch, a press shown
and TalkBack find keys in the scene drawn last (made on the spot only before the first frame), and a
touch finds only a key whose view is VISIBLE (one hidden since that scene finds nothing). Each key's
node is the size of what it draws (`CanvasFrame.extent`: its cell, its shadow's blur, a stroke), and
which faces a frame draws at what alpha is `CanvasFrame.faces`. A key showing the theme's picture
(`Op.Picture`) is recorded where the view is over the picture's base; a pre-draw listener records it
again when that moves (one-handed to the other side, the floating window dragged). The painter's
`missed` (record again next frame) is set only while an image or the picture is still being decoded
(`PictureHost.decoding`), not for one that never will be (no host, a file that does not read).

No scene (the theme the engine refuses, a layout that does not read, the builder's problems): the
keyboard goes back to the views, told once (`Host.noScene`): the canvas removed, the rows VISIBLE,
a warning logged; `SceneLayouts` keeps a layout that did not read as such, not read again a frame.

The row's indent before A and after L is the end key's touch area (the user's P0 decision in
060-render-v2 §3): a finger down there is A's, its swipes and long press A's, though it is outside A's
view (`theIndentBeforeAIsAs`). Before, with `expand_keypress_area` off, it was nothing.

Where the canvas still differs from the views, pixel for pixel (`sideBySide`, the share of pixels past
anti-aliasing, each under 1.5%: PixelLight 0.27%, MaterialLight 0.26%, NordLight 0.27%, PixelDark
0.26%, MaterialDark 0.26%, AMOLED black 0.27%, compact height 0.20%, landscape 0.10%, landscape
split 0.16%): the engine puts row and key edges on whole pixels as Android laid
the views out (rows 176, 175, 176, 175 px of a 702 px board; this fixed the bottom row a pixel up and
Return's blurred top edge), and a shortcut's icon from the pixel its margins reach, its size whole
pixels (fixed the corner icons a pixel off). Left: a label is centred in its face to the fraction of a
pixel where the view centred a TextView of whole pixels (its top rounded) and drew the text in it, so
on rows of odd height (the middle row's letters, 符, 123, the comma and period) the text lands a pixel
up or down; the backspace's right-hand corner icons a pixel left (its view's right margin rounded the
other way). Fixing them means pixel rules for text in the engine, left to P2's theme work.

What it costs (`KeyboardPerfBaselineTest`, 060-render-perf §4; `perfCanvas=true|false` the
switch; youmo29: Android 10 (API 29), "Android SDK built for arm64" emulator, `-gpu host`, one
session on 2026-11-07, both switches one after the other, 20 runs after 3 warm-ups; median / p90 ms;
two such pairs, the second in brackets):

| | views (off) | canvas (on) |
|---|---|---|
| a key pressed, to the frame | 27.4 / 31.2 (26.3 / 31.0) | 30.2 / 39.6 (27.6 / 36.3) |
| ten keys, each, to the frame | 28.2 / 31.0 (28.8 / 30.7) | 27.8 / 34.9 (28.7 / 31.7) |
| Shift tapped, to the frame | 22.1 / 26.2 (23.8 / 26.2) | 27.9 / 32.1 (27.0 / 30.1) |
| Shift tapped, the frame's draw | 0.3 (0.2) | 3.7 (4.6) |
| the 26 keys built and shown, to the frame | 50.0 / 63.3 (74.6 / 88.9) | 71.4 / 130.8 (96.8 / 130.1) |
| a theme switched (block to flat), to the frame | 49–55 (81.7 / 92.5) | 60–66 (69.6 / 93.0) |
| a scene made anew (Shift, a theme), timed alone | | 4.27 / 5.14 (3.64 / 4.40) |
| a scene asked again, nothing changed (pinyin's first key) | | 0.52 / 0.70 (0.37 / 0.92) |

Typing costs the same; a scene asked for nothing is half a millisecond, where pinyin's first key and
its commit had each made one (4.7 to 9.6 ms in the frame). Shift costs a scene made anew in its
frame's draw, 3 to 6 ms more to the frame; the keyboard's first showing costs about 22 ms more (its
scene, every key's node recorded, the canvas's own layout), past 060-render-perf's bounds (median
+10%, p90 +15%). In systrace (the same emulator) a scene made anew is 6.0 ms at the median, 4.9 of
them `cascade` (the theme's styles matched key by key and state by state; measuring 0.1), and its
first after a theme 26 ms at p90. That is over 5 ms, so P2 does not make the whole scene in the
frame: either a key at a time (what changed: Shift's letters, Return; the cascade kept by key and
state, it does not depend on sizes) or off the main thread, the frame drawing the last scene until
the new one is ready (a touch already finds its keys in the scene drawn last).

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
(`InputMethodCatalog.takesKeyboard`), and a character key pressed in one is typed by pinyin (全拼 or 双拼,
whichever is listed first), else the first enabled Chinese input method that takes a keyboard, else
English (`InputMethodSwitch.forKeyboard`), as Sogou and Baidu type pinyin whatever their touch
layout; the touch one comes back with the touch keyboard (`HardwareKeyboard.touchInputMethod`, the host thread's; the hand-off runs in the key's own host job).
`-`/`=` page the candidates while composing (`KeyRouter`, physical keys only).

Testing one (`uat/tools/emu-keyboard.sh`, the cases in `uat/17-hardware-keyboard.md`): the emulator only, never a container device such as redroid (it shares the host kernel, and a uinput
keyboard there is the host's too). The emulator's own `qwerty2` is an ALPHA keyboard (sticky Shift, `unicodeChar` 0
in the IME): register a USB keyboard with `/system/bin/uinput` (JSON `register`, vid/pid unknown so it
reads `Generic.kl`/`Generic.kcm`) and `sendevent` to its `/dev/input/eventN` as root.

A number's, a phone's or a date's field opens a number pad of its own (`NumberPad.of(EditorTraits.number)`:
the keys beside the digits blank (`BlankKey`: no press, nothing for TalkBack) where the field takes none, a plain number
field's the whole pad, a PIN's the digits alone; `FieldPad` in `KeyboardWindow` maps the 123 key and
`Unchanged` to that pad within the field; a pad is never remembered, so the next text field gets its letters).

Address fields swap the comma and the full stop for `/` or `@` and `.com` (`AddressKeys`); a key's
digit or symbol goes in there as it is, what is typed committed as typed
(`PendingInputPolicy.typesAsIs`, in `CommonKeyActionListener`), but for a key the input method
types with (`InputMethodCatalog.readsAsKey`: the nine keys' digits, the `;` of every 双拼 scheme whose layout types it (微软), the pinyins' `'` and a
table's keys, 二笔's `;`, while something is typed, the table's from `Engines.tableKeys`); the nine
keys' column, separator and multi-tap are in `T9Keyboard` with decisions in `MultiTap`, `NineKeys`.
A nine key's long press shows one row, its letters in capitals, its digit, its letters (A B C 2 a
b c, `Explicit.inRow` the digit's place: in that order, the digit under the finger, narrower keys
when they don't fit; owner 2026-10-20), each committed as it is (`CommitAction`, after the first
candidate), then in rows of their own above, no line between, its symbols (`NineKeySymbols` in ime-core, the pref `t9Symbols`, set in the 「九键」 section's
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

Each of 拼音, 双拼 and English picks its touch keyboard (I1, 0.5; 0.4's separate 九键拼音 input method
is gone). The choice logic is `ImKeyboards` (ime-core `input/keyboard/`): `ImKeyboard` (`FULL`,
`KEYS18`, `KEYS14`, `NINE_KEYS`), `Chosen(pinyin, shuangpin, english)`, `choices(im, scheme)` (pinyin
all four, 双拼 all four but 14 where `has14(scheme)` is false, i.e. 微软 whose `;` is a
final, English full and nine), `effective` (a 14 chosen under such a scheme shows the 18 keys, the nearest), `fallback` (that
keyboard where it differs from the chosen one: `EngineBridge.configure` stores it as the scheme
changes, and `HostCore.configure` tells 双拼's keyboard anew with an `InputMethodChanged`, so the
view in use changes at once), `layoutOf` → `Layout`, and `session(im, keyboard)`.
The prefs are `AppPrefs.keyboard.pinyinKeyboard` (`PinyinKeyboard`: `Full`, `Keys18`, `Keys14`,
`NineKeys`; `Full`/`Keys14` stored as in 0.4; pinyin's 18 keys show `L`, no `;`, which only
微软 双拼 reads: `GroupedKeyboard(semicolon = false)`), `shuangpinKeyboard` (`ShuangpinKeyboard`, the same
four) and `englishKeyboard` (`EnglishKeyboard`: `Full`, `NineKeys`, key `english_keyboard`, never
following pinyin's); each is in `recreateInputViewPrefs`. The router's IM stays `engine-pinyin` on
every pinyin keyboard. The ⋯ panel's 「键盘」 entry is `KeyboardPicker` (`show(anchor)`): the current
input method's `choices`, the one in use ticked; a pick sets that input method's pref, so the view is
rebuilt at once. No entry where there is no choice (the tables, handwriting). The settings pages
show a section 「九键」 (`InputMethodSettings.NINE_KEYS`: 连按, 按键字母, 按键长按, one pref each, shared by
pinyin's and English's nine keys; English's 九键英文打法 first in it; 双拼's holds 按键长按 alone, its
letters the scheme's and no multi-tap) while 九键 is chosen, hidden and shown at once as 键盘 changes
(`Section.shown`, re-run by the page's refreshers; owner 2026-11-01: no row to a page of their own, none
on 按键). A hardware keyboard types full pinyin / the full layout whatever is chosen.

The 14 keys (`GroupedKeyboard`, `GroupedKeyboard.Name14`) are 拼音's touch keyboard when
`pinyinKeyboard` is `Keys14`.
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
from `EngineState.syllables` for the current input method; a tap takes one, backspace at the end
gives the last back. The composing row is 32 dp only while the input method shown has the strip
(`CodeRowHeight.showsStrip(layout)`: pinyin's and 双拼's 14 and 18 keys, 九键双拼; pinyin's nine keys
show their syllables in the column) and 18 dp in every other (`CodeRowHeight` in
ime-core, through `KawaiiBarComponent.preeditHeight(im)`); `InputView` sets it anew on each
`IMChangeEvent` and recomputes the keys' cap (`KeyboardHeightCap`), so the keyboard changes by 14 dp
between the two kinds (accepted, the user 2026-10-26) and never while typing in one; where the cap
binds (landscape, 很高) the area stays put and the keys give up the 14 dp instead. The settings
preview draws the full keyboard, with the thin row. The prefs are in `recreateInputViewPrefs`. The
bar's buttons keep their own 40 dp floor (`MIN_TOUCH_DP`), a different thing.

That is the embedded row (`ThemePrefs.codeRow` = `CodeRowStyle.Embedded`, 0.5.0's). The default,
`Floating`, puts the same `CodeRowUi` (wrapped: as wide as its text) in a `FloatingCodeStrip` over
the app, while there is a code: `InputView` adds it to itself, not to `keyboardView`, which floating
clips to its outline (`FloatingCorners`), and places it each frame from a pre-draw listener
(`CodeStripPlacement` in ime-core: outside the board's top-left as drawn, the bar's left less its
padding, so one-handed and floating it follows; under the board where there is no room above; at
most the board's width), hidden while a floating keyboard is dragged or scaled
(`FloatingController.moving`). The keyboard keeps no row for it (`CodeRowHeight.keyboardDp` 0, the
cap counting the bar alone); the strip is 24 dp, 32 with the syllables (`stripDp`). Whether the
syllables show is asked of the layout (`syllablesOwned(owns, showsStrip)`), no longer of the row's
height. Docked the service's touchable insets become a region, the keyboard's band ∪ the strip
(`InputView.codeStripRegion`), the content insets still the keyboard's top so the app never moves;
floating, the strip is added to the keyboard's region. The undo chip keeps off it
(`UndoChipPlacement` `stripTop` / `strip`). The physical keyboard's `CandidatesView` is unaffected.

Pinyin's 18 keys (`Keys18`) are the grouped keyboard over `G18`, sending `Keyboard.pinyin18Key(code)`
(L; is just L on pinyin); 双拼's 18 keys (`shuangpinKeyboard == Keys18`) the same keyboard with 双拼's
codes, and 双拼's 14 keys (`Keys14`) the 14 keys' groups sending `Keyboard.shuangpin14Key(code)`; each
with the syllable strip and the 32 dp row. 九键双拼 (`NineKeys`) is `T9Keyboard`
built for 双拼: its digits send `NINE_SP`'s codes, its 1 key `'` while typing, a wider
space, no 连按 or custom letters; its syllables in its own left column, the strip off
(`ownsSyllables`; its 123 shows the strip), the row 32 dp as the 18 keys'.

Pinyin's nine keys send their own codes, `Keyboard.pinyinNineKey(digit)` (plane 15, 0xF0400+), for
2–9, and for every digit while typing: 1 the separator; 0 (and a key emptied of letters) ends the
input as 0.4's 九键拼音 did, the first candidate then the digit, which `Keyboard.char` commits after
it (the host swallows a code handed back; sent as a plain digit, the pair would read it as a
hardware digit and pick the tenth candidate). With nothing typed 0 is a plain `0`. Because the keys are codes, not
digits, a digit typed from elsewhere (the number row) in an address field goes in as it is even on
the nine keys. No key of the nine keys switches language (0.4's 中/英, `NineKeyLanguageKey`,
`englishOnNineKeys` and `EnglishLayoutPolicy` are deleted): 🌐 runs the enabled list
(`enumerateIme`) as on every keyboard, the space shows 「拼音」.

九键英文 (`T9Keyboard.EnglishName`, a second `T9Keyboard` built with `english = true`) is fcitx's
English shown on the nine keys, whenever `englishKeyboard` is `NineKeys` and English is
however reached; the 九键英文打法 row is on English's page, shown only then. Its keys send
`Keyboard.nineKey(digit, upper)` (predictive, the engine's `Action.NineKey`) where
`ImKeyboards.spellsWords` says English suggests
(`t9EnglishTyping` Predictive, not a password, an address or NoSpellCheck, English's suggestions
on, read from `EngineState.englishSuggests`), a long press's letter then pinned into the word
(`Keyboard.nineLetter`, owner 2026-10-20), else commit letters by `MultiTap` themselves
(`CommitAction` replacing the last of a run), a long press's as it is. Whether a word is being spelled, for 1 (an
apostrophe then, else `. , ? ! ' -` in turn) and the column, comes from
`EngineState.englishTyping`. ⇧ stands where 重输 is, its state `NineKeyShift` (ime-core), lit by
auto-caps through `AutoCapsKeys`, which both boards implement. No glide on it.

Handwriting's keyboard (`HandwritingKeyboard`, the letters layout of `InputMethodCatalog.HANDWRITING`) is a pad
(`HandwritingPadView` over `InkPad`) and a column of backspace and marks. Each read goes to
`HandwritingRecognition` (input/handwriting), its reading back to the engine through
the key `Keyboard.INK` carrying it (`EngineBridge.ink`), with the piece it is of; the pad wipes the piece
it writes once the engine has ended it (`EngineState.handwritingEnded`, the latest piece ended, so
a value missed while detached is still seen), and ends it itself when detached and at each new
input (`restart`, which also drops what was still being read). The
recognizer skips a read only for a newer one of the same piece: a piece's last read is what it shows.
How long the pen stays up before a piece is done (`InkPad.finishMillis`) is the app's setting
`AppPrefs.keyboard.handwritingPause` (`HandwritingPause`, 0.3–1.5 s), on the handwriting page,
read at each pen up. A piece so done is told to the engine (an Ink key carrying
the piece and no reading, after the piece's readings: `HandwritingRecognition.afterReads`), for the large sentence model
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
   glide typing, paired brackets and replacing a sum), over `HostApi.imOptions`/`setImOptions` (a page's options by path).
   The options every input method shares are one group in `EngineOptions`, so they are on one page,
   通用输入 (`InputMethodSettings.SHARED`, `SharedInputFragment`, saved through pinyin's config),
   each saying which input methods it is no use to (`notFor`); 每页候选数 is on 实体键盘
   (`HARDWARE_KEYBOARD`). An option no use to pinyin on the 14 keys names `InputMethodCatalog.PINYIN_KEYS`
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
  (`levels(...)`, e.g. 紧凑/矮/标准/高/很高) over the int preferences, which are `hidden(setByLevels = true)`.
  The level shown is the nearest to what the preferences hold, each key's distance over its levels'
  span (`LevelPick` in ime-core); the level itself is not stored, so adding one can show an old odd
  value as a neighbouring level, its numbers unchanged. 键盘高度's levels are `KeyboardHeightLevels`'
  (ime-core), as the keyboard reads 紧凑 by the same numbers.
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
3. Engine behaviour: add an option to `EngineOptions`, a field of `EngineSettings` it fills in `toSettings`, show it
   with an `Item` in `InputMethodSettings`; `nearest` maps old or odd stored values to a shown one.
4. Strings: `values/strings.xml` (English) and `values-zh-rCN`, `values-zh-rTW` at least; the other
   locales fall back to English. Write the summary as what the user gets, not how it works.
5. Test the decision in ime-core; if the page is hand-drawn, the search index too.
6. Describe it in `docs/settings.md`.

## Theme files (`data/theme/`)

A custom theme is `files/host-theme/<name>.json` and the files it names beside it, by file name
(a picture's source and its portrait and landscape cuts, key images, fonts). The json is format
**3.0**: `version` first, then `name`, `isDark`, `palette` (24 colours: the 21 of 2.1 and
`keyBorderColor`, `keyHintColor`, `toolbarIconColor`), `picture` (or null), `base`, `bar`, `keys`,
`text`. It is ime-core's `ThemeLook` (`input/theme/`) one to one, through `ThemeLookJson.kt` (the
only place the two meet): no field has a default, a field missing or not known is an error, and a
value out of range is refused as the `ThemeLook` is made. `keys.overrides` maps a key's name
(`KeyNames`: `<board>/<key>`, `*/<role>` for a function key on every board) to its own fill,
pressed face and label; a name no board here has (a later build's board) is read, kept and drawn
by none. A key's style is its board's own name, then `*/` (roles only), then its kind's; each field
falls through by itself (`KeyStyleResolver`). Every key a theme can reach has `KeyDef.name`, its
keyboard the board (`BaseKeyboard.themeBoard`, `KeyView.themeKey`); `ThemeKeyNamesTest` holds the
two to `KeyNames`.

Reading an older json (`CustomThemeSerializer`, newest first: 3.0 ← 2.1 ← 2.0 ← 1.0): 2.1 → 3.0
keeps the colours and the picture (one portrait cut, its rect and turn, brightness; no blur), and
gives the default keys, text and a transparent bar (`LookDefaults.look`). The key style settings
before 3.0 were global (`ThemePrefs`' border, radius and gaps as the page's levels set them; the
stroke, ripple and the two panels' radii were hidden, never set by a user, so read as the defaults
whatever an old backup has, and removed with the rest); `ThemeLookMigration`
moves them into the themes by one rule: **a theme read in an older format gets the settings as its
keys** (`LegacyLook.applyTo`, while they are in the prefs and not the defaults), saved again as 3.0 with
its mtime kept. A 3.0 theme is never changed by them, so a backup from before restored (the
settings come back with it) leaves the themes made since as they were. `ThemeLookMigration.settle`
(copy a built-in theme in use with them, once a theme however many slots have it, a failed save
too; put the copy in its place, remove the settings, tell once) runs at `ThemeManager`'s start (`settleKeyStyle`), not while the themes are not
settled or a migration's write-back failed this start (`ThemeFilesManager.rewriteFailed`): a copy
not saved leaves its slot and every setting for the next start. A Monet theme in use is not copied
(its colours follow the wallpaper): the settings are kept, encoded, as hidden `monet_key_style`
(`ThemeLookMigration.MONET_KEY`), and `Theme.Monet`'s look is the defaults with them
(`ThemeLookMigration.monetLook`). The page's 按键样式 has three settings of its own, new keys (`key_border_choice`, `key_spacing_choice`, `key_radius_choice`; not 0.5.0's, which the migration reads): each 跟随主题 (the default) or a value over the theme's `blocks`, `gap`, `radiusDp` (ime-core's `KeyStyleChoice`; a block theme made flat gets a transparent bar's colour, as `LegacyLook.applyTo`); then a line on what following means (标点位置 moved to 按键).

### Drawing a theme

All the keyboard draws reads `ThemeManager.shownLook(theme)`: the theme's look under the 按键样式 settings (the theme's own when all follow it; one cached look, the same object while the theme's look and the settings stay, as `ThemeAssets` keys on it). Every reader under `input/` and the theme page's preview use it, even those that read colours only, so a guard test (`KeyStyleThemeTest`) can forbid `.look` there. A theme's thumbnail, export, save and the migration read `Theme.look`, the theme's own.

Every key is drawn by `KeyFaceDrawable` (`input/keyboard/`; `View.drawAsKey`): shadow, fill, border, in
the shape ime-core's `KeyFaceGeometry` gives (blocks or flat, `KeyDef.Appearance.Border` →
`KeyFaceGeometry.Border`: a flat keyboard's T9 column and handwriting pad keep their face, `On`). The
fill is a colour, a nine-slice image (`NineSliceLayout`, drawn at the sample it was decoded at; the
colour till the board's images are in, then `invalidateSelf`) or the picture showing through
(`ThemePictureDrawable`/`PictureHost`: the picture as `PictureWindow.centerCrop` lays it under the
whole keyboard, found at the view's place in the window, so a key, the bar and the base line up).
Pressed: a darker shade (`Tint`), a colour or an image as a `StateListDrawable` foreground, or a
`RippleDrawable` (`Ripple`). The bar is none (blocks: the base shows), its colour, or the picture's
part (`ThemePictureDrawable.bar`); panels (expanded candidates, clipboard, status area) use the bar's
colour only over flat keys (`ThemeColors.panel`). Key text is `keyScale` of its size in the theme's
font (`ThemeAssets.typeface`, the weight asked); hints, marks and the bar's icons take
`keyHintColor` and `toolbarIconColor`.

`ThemeAssets` (`data/theme/`) decodes off the main thread and caches for one theme's files at a time:
the picture once an orientation at the width it is drawn (halved while wider, and till it is at most
2048² pixels and 4096 high, so a 4096 cut is not held whole), its blurs from that (`BoxBlur`), each
once a width it is drawn at within a tenth (the last three widths a blur is drawn at kept, never one still being made or waited for; a floating keyboard's blurred as much as the docked one),
decoded ahead when the theme is picked and when the screen turns at every blur it shows (`prefetch`,
`ThemeLook.pictureBlurs`; decoding and blurring outside the cache's lock, one decode a picture however
many ask; a prefetch run after another theme was picked decodes nothing and keeps nothing). A view
drawing does not wait for a picture another thread decodes (`pictureOrLater`): it draws the colour and
is drawn again once it is in; a board's key
images when the board is made (`boardImages`, the names it can reach: `KeyStyleResolver.imagesOn`; a
keyboard of no board name, the number row or a search's keys, its kinds' and roles'), at the sample
`KeyImageBudget` gives, the boards least recently made dropped past 24 MB; fonts once. A file that
does not decode, or runs out of memory, is none (its colour drawn). A theme of other files (another
picture, a crop, a key image) drops it all, a theme of none too (a plain theme picked lets a picture
go); a colour, brightness or blur changed keeps what was decoded. A save, a delete and an import clear
it (`ThemeManager.filesChanged`: a file of the same name may have new bytes) and count a `generation`;
the theme in use written anew is told as changed even if it reads the same (imported again with another
picture), so the keyboard and the settings' preview are made anew with the new files. `PictureHost`
asks it once a blur, base size and generation, then lays the picture by the views' places added up from their parents (the
window asked only under a scaled view); `KeyFaceDrawable` lays a key image's nine pieces once a size.
The theme list's thumbnails (`ThemeAssets.thumbnail`) are its portrait cut decoded 160 dp wide on the
same thread, blurred as on the keyboard, 12 MB of them kept; one that does not read is not kept.

The app does not edit themes (user, 2026-11-03) and in 0.5.x does not link the web theme maker
(user, 2026-11-04: making themes moves to a later version, now 0.6.0): 新增主题 opens the import picker directly, no menu; the page
(`web/theme-maker`) stays on GitHub Pages for 0.6.0, unlinked from the app and the user docs. A theme file is `<name>.theme`, a zip inside (user, 2026-11-04, so it is not taken for any zip): exported under that name as `application/octet-stream` (a provider may add `.zip` for `application/zip`), and the import picker shows every file (`*/*`, `.theme` has no MIME type) and takes only a display name ending `.theme` (ime-core's `ThemeFileName`; `.zip`, an earlier 0.5.1's or the page's, or no name, is refused with `exception_theme_filename`, which says to rename it). The format version is unchanged. The theme list
applies, imports, and on a long press exports or deletes one of the user's (a Monet theme exports
`Theme.Monet.toCustom()`: its colours now and the key style it keeps, fixed). The fcitx edit page
(`CustomThemeActivity`, its cropper) went with the old 选择图片… and 复制内置主题…; themes made by them
before are listed, used, exported and deleted like any other. Thumbnails (`ThemeThumbnailUi`) draw four rows of the
theme's keys in little (`ThemeThumbnailKeys`, colours only: no key image decoded) over its base
(its colour, or its picture: `ThemeAssets.thumbnail`).

Import (`ThemeFilesManager.importTheme`) takes the json and every file it names, by file name only
(never a path, never a `.json`, never `.saving`); it checks all before copying any, so a refusal leaves nothing.
Only the zip's top-level files are taken (`extract(flat = true)`): an entry in a folder (a Mac's `__MACOSX/`)
is counted and its bytes metered, never written. Limits (`ThemeAssetLimits`, the numbers in the messages
read from it), counted as the zip is unpacked (a zip bomb stops at the first entry past one; a directory
is an entry): 300 entries, 32 MB a file, 64 MB in all; a picture's cuts 4096 px a side (the source photo
is not limited); a key image 1024 px a side and 2 MB; a font a file's 32 MB and it must load; 400
overrides. The json is the zip's top `.json` that is not a Mac's `._` attributes file. A file another
theme names is a clash (`exception_theme_file_clash`): a listed theme's files, and of a json not listed
(a later build's, one that does not decode) every string in it that is the name of a file in the dir,
read loosely; one no json names (an orphan: an older backup's json put back over a 3.0 theme's) is taken
over. A json of the theme's name that is not listed is replaced, its files with it if it decodes (a file
of it missing), except a later build's: kept, the import refused (`exception_theme_newer_kept`). A
later build's format imported is refused in the user's words (`CustomThemeSerializer.UnknownVersion`).

Every write is whole or not at all: a theme's json and an import's files are written in `.saving/` in
the theme dir (a directory: no import's file can be one there, no listing reads one), synced, then
renamed into place (a migration's json given its old time first), the dir synced after (where the
system lets a directory be: Linux does); what a kill left there, or a plain file of that name, is
cleared at the process's first listing. An import writes its files and its json there, then renames
the files into place and the json last, and only then deletes the files the theme it replaces had and
it does not name (never one another theme names, a json or `.saving`); if any step fails, the files it added are deleted (one of the old theme's renamed
over by then keeps the new bytes: only a rename in one directory can fail there). A migration saved
again while listing that fails (a full disk) is logged and drawn migrated all the same, saved at the
next start; `ThemeManager.saveTheme` tells the user (`theme_not_saved`, no path) and returns false. Export writes each named file once (only files of the theme dir:
a planted json's other paths are refused before anything is written), then the json; delete removes
the json and every named file that is in the theme dir. Listing skips a theme with a file missing, and
one that does not read (told once, `user_data_unreadable`), leaving its file as it is.

## Persistence and backups

`UserDataManager` exports one zip: `shared_prefs`, the clipboard database (less what an app marked
sensitive), the host's config, fcitx's data and the themes (`files/host-config`, `files/host-data`,
`files/host-theme`, under `external/config`, `external/data`, `external/theme` in the zip where every
backup had them, so older builds restore them and this one theirs; settled first by `HostConfigDir.inUse`,
so an export takes, and a restore writes into, what is read: an older build's moved in before a restore
can pass it over; an old copy not yet deleted (named by the home's `ConfigHome.MOVED`, deleted at a later start) is not taken), the rest of the external
files dir; in the data, custom phrases and imported dictionaries as their `.txt.gz` sources (the compiled `merged.ymd`/`.ymc`, any `.txt.gz.tmp`/`.txt.gz.convert.tmp` and the markers `.merged.failed`, `.merged.unreadable`, `.merged.unwritten`, `.merged.left`, `.merged.upgrading` are left out (`MergedDictionary.isCompiled`) and made again after a restore). A restore overlays the dictionaries directory: an older backup's `.txt` is converted again over a same-name `.gz`; both `.gz` states of one name keep the newer (equal mtimes: the one turned on)
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
aside) stays. An export with the learning as a backup leaves out the files it is made of and the old
logs (`*.user`, `*.user.told`) and `learn.table-logs-read`, the phone's own as the record is; with
that mark (the old logs read into the record) an export without the learning leaves the old logs out
too, and the mark goes with the record into the stash, whose logs are then not read again. A zip of this app from before 0.3.1 (its metadata's `versionCode`),
or with old logs but neither the record nor a backup, is refused before anything is copied
(`exception_user_data_too_old`); one from before the renaming is not, having none of the engine's
learning.

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
remembering text goes through it. One exception: in an email's or a URL's field the address bar
(`AddressStrip`) reads at most 256 units either side of the cursor of a sensitive field too, to offer
an address's end there, and keeps nothing of it (not even to compare: `AddressLearning` holds no text
of a field not learned from).

## The two flavors

`speech` dimension: `text` and `voice`. Main code reaches voice only through `VoiceFeature`
(`AVAILABLE`, `credits`, `hold` for the space, `tap` for the bar's microphone and the `Voice` key shortcut), defined in both source sets with the same
signatures; the text one is empty. `VoiceDataPlugin` adds the recognizer and models to the voice
build only; `RECORD_AUDIO` is in `app/src/voice/AndroidManifest.xml`. An input method cannot ask for
a permission itself, so `VoicePermissionActivity` asks and closes. Voice decisions (number
normalisation, reranking hypotheses with the pinyin model, blocked words, hotwords, the hold's and
the tap's presses, what is typed as it is said) are in ime-core's `input/voice/` and tested there. `:app:compileVoiceDebugUnitTestKotlin`
in CI keeps the voice source set compiling.

### Voice: the start

- 正在听 only once the microphone is really on: `VoiceOnset` (ime-core) reports the first sample not zero,
  `VoiceListener.Events.ready` → `VoiceSession.Ui.ready`; before it both overlays say 准备中…. Why:
  the press-to-first-sample trace (dev/design/voice-start-latency.md) showed 正在听 lit hundreds of ms before
  anything was recorded, and the user chose to show it rather than record before the press (no pre-roll: the
  status bar's microphone dot would flash on every space). Not after the stop or a drop (the session's guard).
- 2 s of nothing but zeroes (`VoiceOnset.silent`, the privacy toggle off or the capture taken) say 麦克风没有声音
  (the user's rule), back to 正在听 at the first sound.
- The cue's vibration and the voice ticks go through `VoiceStyle.vibrate` → `InputFeedbacks.hapticFeedback`, so
  they follow 按键时振动, its length and strength.
- The microphone is read from the moment it opens, never waiting on the models: what it reads goes into an
  unbounded channel in order, and the recognizer's coroutine (`Dispatchers.Default.limitedParallelism(1)`)
  takes it once `VoiceEngine.acquire` is done. The recognizer's load holds `VoiceEngine.lock`; the models stay
  `KEEP_MS` (2 min) after the last listener lets go.
- The record's buffer is 2 s (`VoiceCaptureEnd`'s backlog cap follows it), the least the HAL takes as a last try.
- Debug line the UAT parses: `voice ready: fromStartMs=` (start() to the first sample not zero).

### Voice: streaming, the segments and the polish

- One `OnlineRecognizer` (sherpa-onnx's streaming zipformer transducer, chunk 1920 ms, modified beam search 16,
  n-best) decodes as the audio comes; no VAD. Its endpoint rules (sherpa's defaults: 2.4 s of silence before
  anything, 1.4 s after something, 20 s at most) close a segment: `VoiceEngine.choose` picks its text among the
  n-best (`VoiceChoice`, the pinyin engine's model), and the stream is reset. The user's blocked words are in the
  search graph (`createStream(hotwords, blocked)`), not searched again. After the microphone closes, 2.92 s of
  zeros pad the tail (`TAIL_PADDING`: a chunk and its right context, else the last words wait for a chunk that
  never comes) and the input is finished. Why endpoints and not the VAD: measured on six sets
  (`voice-eval/seg_app.py`, `ENDPOINTS=1`, 060-voice-tagger.md), endpoint segments are as good after the polish
  (written CER within ±0.5 points either way) and it is one model less.
- `VoiceLive` (ime-core) keeps the closed segments and the one being said: joined with 「，」 where neither has a
  mark, a space between English words, and a segment that starts with a mark (the recognizer's context
  outliving the reset) ends the one before with it. 语音整理 on (`keyboard.voicePolish`, default on), its
  `VoicePolishStream` (K = 16, window 64) runs the polish tagger (`VoicePolisher`: ELECTRA-small int8 through
  `OrtModel.runLongs`, `voice/polish-tagger.ort` + `.json`) on the text as it grows, freezing what is 16 characters
  behind; `VoiceText.clean` (numbers, spaces) runs after it. A tagger that fails to load leaves voice on without it.
- What is heard so far is the field's composing text (`FcitxInputMethodService.composeVoice`): voice's text is the
  text itself, as English's word (pitfalls.md). Not in a password field, a raw-key (`TYPE_NULL`) one, nor when the
  field composes a word of fcitx's when the talk starts (only the card or the mask shows it then), nor over a
  preedit of fcitx's come meanwhile. It is `EditingSession.provisional`: the commit, through fcitx (`CommitAction`),
  replaces it, never finished in place first (`commitAndReset`'s `FinishComposing`, the input methods not Chinese:
  finished, it was typed twice), and fcitx's empty preedit (its reset) leaves it; the cursor leaving it (a tap in
  the field, an app's edit) takes it away, the cursor where it went, and the next text heard composes there; gone
  from the field with no span reported (the app sent or cleared the text: the text around the cursor no longer it,
  `EditingSession.provisionalTaken(consumed = true)`), it is not taken away nor typed again (`VoiceTalk.consumed`:
  all heard so far counts as typed; the editor's composing region over the app's text let go) and the talk goes on;
  input restarted in the same field (`provisionalRestarted`; the same field: `VoiceCommit.sameField`, the same
  package, view id, input type, field name and hint, as `ContextMemory.field` names it, so a WebView's inputs are
  told apart; not the same `EditorInfo`) takes it away, the cursor's place predicted (or finds it taken), another
  field's restart leaves it as text; input finishing in the field, or the keyboard
  hidden, takes it away (not finished in place) before its connection goes; a drop, a give-up or nothing to type
  takes it away, in the same field only. A preedit of fcitx's in the field meanwhile (another finger) stops typing as
  it is said till it is gone (`voiceOwnsComposing`): the engine's text is never finished in place by voice.
- One talk is a `VoiceSession` (app/src/voice), whatever starts and stops it: the listener, what is composed and
  typed, the 15 s give-up, the end mark, `finish` (stopped: the tail, then the rest typed through fcitx), `cut`,
  `drop`, `typeNow`. `VoiceHoldSession` adds the held finger (`VoiceHold`'s cancel zone, the lift);
  `VoiceTapOverlay` adds the tap's: what each press does is `VoiceTap` (ime-core): the microphone, the mask or a
  press off it (the bar's other buttons, the candidates) stop it (no ×: the user's decision 2026-11-07, what is
  not wanted is deleted after), a press after the stop cuts the tail,
  another field (`inputEnded(LEFT)`, the overlay detached; `STARTED` in another field) types now, the keyboard
  hidden (`HIDDEN`, stopped or not) stops it as a tap does, the tail taken away now and what is left typed straight
  into the field once recognized (`VoiceSession.hidden`, `commitVoice`, the connection still there); another field
  before that (no view to finish then: `onFinishInput` tells `LEFT` first, its connection and `EditorInfo` still the
  first field's) types what was heard into the first at once; input started again in the
  same field (`STARTED`: a restart, the keyboard shown again) talks on, 60 s with nothing heard
  (`VoiceTap.QUIET_MS`, counted from the last thing heard) stops it. A press off the mask is told by where the bar's
  microphone is now (`InputView.voiceButton`), so a talk started from a key's shortcut is not stopped by the
  microphone's own touch before its tap; the floating keyboard's handle (`InputView.handleShown`) is not off it:
  dragging the keyboard talks on. No Enter on the mask (the user's decision 2026-11-06: it would not follow
  the theme): to send, stop it, then the keyboard's Enter.
- Tapped, what is settled is typed as it is said (060-voice-tap-to-talk.md, the user's decision): `VoiceLive.Heard`
  carries `settled`, the end of the part that cannot change: of the closed segments only (`VoiceChoice` may still
  choose the open one otherwise), of the tagger's frozen labels only (`VoicePolishStream.frozenPrefix`), and cut
  just after a pause or end mark followed by something said (`VoiceLive.settledEnd`), so neither the tagger's
  marks nor `VoiceText.clean`'s joins straddle the cut. `VoiceTalk` (ime-core) types what newly settled (cleaned on
  its own) and composes the rest; were what was typed rewritten after all, only the part after the common prefix is
  typed at the end. The raw text instead (the polish failed: mid-talk, `Heard.fallback`, or at the end,
  `Listening.finish`'s `raw`), what was typed is found in it as a subsequence, its marks skipped and a character of
  raw's it lacks (the 「嗯」 the polish took out) passed over (`VoiceTalk.rest(raw = true)`): 「我到了。」 typed, raw
  「嗯我到了马上走」, only 「马上走」 is left. `VoiceLive.settled` types nothing where what shows does not start with
  the frozen part (out of step: it should not be). Mid-talk parts go straight in (`FcitxInputMethodService.voiceHeard`: `commitVoice`, synchronous,
  then the tail composed, in one batch edit); the last part goes through fcitx as the hold's does.  Another field mid-talk types what was heard so far into the first at once (`typeNow`,
  `commitVoice`: its connection is about to go); what the recognizer had not decoded yet is lost. Given up (15 s)
  or failed, tapped, what was heard last is typed too (what was said stays, the user's rule), and the message is
  `voice_tap_cut_short` if anything went in (as it was said or now), `voice_hold_nothing` only if nothing did. The polish failing (`VoiceLive.Heard.fallback`: the raw text, not in step with the
  polished part typed) stops typing as it is said for the rest of the talk. Each part is cleaned on its own, so a
  sound alone between two marks (「嗯，」「哦。」) can go where the whole cleaned at once keeps it: accepted. Held,
  nothing is typed before the lift (`asItGoes = false`).
- The end mark: `VoiceEnd` (ime-core) by the field (`EditorTraits.sends`, `isMultiLine`, `isShortMessage`, a chat
  app's multi-line field) and 语音句末句号 (`keyboard.voiceEndMark`): a one-line field takes no end mark, a chat no
  「。」, anything else as said. Applied at the commit only; the composing text shows it as said.
- Debug line at the recognizer's end: `voice segments: speechStart=` (the first token's frame, from the opening)
  `segments=` (closed with something in them), for the UAT's lift offset (VO-23/24).

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
  zone, `drop()` and `inputEnded()` close at once, as before. Tapped, the stop (the microphone, the mask, a
  press off the mask, the keyboard hidden, the quiet minute) is `finish(now)`: the same tail from the tap.
  A new press after the lift (`ACTION_DOWN`/`POINTER_DOWN` anywhere on the input view for the space
  card; the microphone, the mask or a press off it once tapped to stop) calls `Listening.cut()`: the tail ends,
  what was heard is still typed. On the card (it covers the keys only) the press counts for nothing; a press on the toolbar or the candidates ends the tail the same way and then acts as usual (design N2). The UI does not change (识别中… at once).
  The space card covers the keys, so there any
  press only ends the tail and counts for nothing (VO-25, unchanged by owner decision 2026-10-26).
- **Gain**: `VoiceGain` (ime-core), one per microphone opening, on each window after `read()` and
  before the recognizer and the level bar, so both hear the same. Level: the 3rd
  loudest window RMS of the last 31 (~1 s), the first 3 windows left out; env falls at most 3 dB/s;
  gain clamp(−30 − env, 0, +20) dB, held so a window's peak stays under 0.99; rises ramp across the
  window. Normal speech passes unchanged, but for its first two windows (~64 ms) after a quiet
  stretch, lifted up to the peak limit until the 3rd loudest catches up. Why: VOICE_RECOGNITION has no AGC (CDD 5.4.2), soft speech comes in at −50 to −60 dBFS where
  silero (the VAD before 0.6.0) did not fire; at −26 dB CER 30.87% → 7.17%, no-result 15% → 0, noise 3·2·1·1 of 159.
  It is tailquiet's `Gain2` sample for sample (`VoiceGainTest`'s golden sample, ≤ 1e-5; its generator, with Gain2 copied in, is
  `lib/ime-core/src/test/resources/voice/gain_golden.py`, and each parameter changed alone changes it). Debug
  builds log one line of numbers at the close (`voice capture:` liftFrame, stopAt, read, envPeak,
  peakGainDb: the gain where env peaked) for the UAT (uat/12 VO-23/24/25), with `voice segments:` above.
- **Quiet**: `VoiceQuiet` (ime-core) decides per recognizer segment whether it is typed (and shown).
  The gain lifts a quiet room 20 dB and the recognizer reads the lifted floor (a fan, traffic, plain
  zeroes) as a short English phrase, 「I'm sorry.」 in 118 of 126 such results measured (a OnePlus 6T at
  −66 dBFS typed 「I'm sory」, 2026-11-06; an emulator's silent microphone 「H」「I'm」). A segment with a
  Chinese character, or of more than 12 letters, is typed as before; a short Latin one only if its
  windows are loud (3rd loudest −50 dBFS or louder, or 8 dB over the talk's 10th-percentile floor;
  raw windows before the gain; all-zero ones, ones under −100 dBFS (digital silence: the UAT clip
  microphone's sparse ±1 between clips is −111, and would put the floor 50 dB under the room) and the
  first 3 with sound left out) **and** silero VAD
  (threshold 0.2, on the gained samples, its flag read each window; its segments unused) heard speech
  in them. No VAD (its model failing to load): everything is typed (`VoiceOpening`). The VAD is
  taken after `startRecording()` (the record's 2 s buffer holds the first words meanwhile), a spare
  made beside the models' load and kept from opening to opening (`VoiceSpare`), let go with them;
  debug builds log its time per window (`voice capture:` vadMeanUs, vadMostUs).
  The spare is made when a talk starts (`load()`, beside the models): the first press after the
  process starts finds none ready and makes one after `startRecording()` (some tens of ms, the
  record's buffer holding what is said meanwhile); from the second press on the last one is reused.
  Not made when the voice key shows (0.5.x's prepareVadSoon): that would need a hook in the main
  code and the text flavor for a cost the record's buffer already absorbs.

  Measured by `lib/ime-core/src/test/resources/voice/quiet_eval.py` (Gain2 → the 060 streaming models with the app's
  endpoints, silero per window; aishell 0–39, ascend 0–39, Kokoro's English phrases, the q44 clips,
  FSD50K; R the room's white noise, L the speech's 3rd loudest window, dBFS; CER / share of
  "没有听清"; noise rows: talks typed). Rules: d4d0981c6 none; 077374d11 the talk's level; (a) rise
  6 dB, 3 windows in a row; (b) silero 0.2 heard any speech; chosen: short Latin and loud and voiced.

  | case | d4d0981c6 | 077374d11 | (a) | (b) | chosen |
  |---|---|---|---|---|---|
  | aishell R−66 L−58 | 20.6% / 0% | 26.7% / 8% | 20.6% / 0% | 33.8% / 20% | 20.6% / 0% |
  | aishell R−66 L−60 | 29.9% / 0% | 95.3% / 90% | 54.1% / 30% | 62.6% / 52% | 29.9% / 0% |
  | aishell R−66 L−62 | 38.1% / 0% | 100% / 100% | 97.6% / 92% | 93.4% / 90% | 38.1% / 0% |
  | aishell R−60 L−54 | 29.9% / 0% | 96.6% / 92% | 61.6% / 40% | 43.7% / 25% | 29.9% / 0% |
  | aishell R−60 L−58 | 49.9% / 12% | 100% / 100% | 100% / 100% | 97.6% / 98% | 49.9% / 12% |
  | aishell R−60 L−62 | 85.3% / 42% | 100% / 100% | 100% / 100% | 100% / 100% | 85.3% / 45% |
  | q44 clips in R−60 / R−66 | 0% / 0% | 100% / 100% | 100% and 67% | 100% and 67% | 0% / 0% |
  | 2 s of room, then aishell L−58, R−60 | 47.9% / 8% | 100% / 100% | 100% / 100% | 100% / 100% | 47.9% / 8% |
  | ascend R−60 L−54 / L−58 | 56.6 / 71.7% | 99.3 / 100% | 62.1 / 100% | 59.6 / 96.2% | 57.1 / 72.2% (+3% 没有听清 each) |
  | English phrases (48) R−66 L−30 to −56 | 4.7–13.6% / 0% | the same | the same | the same | the same |
  | English phrases R−66 L−58 / L−60 | 22.1 / 19.0% / 0% | 22.9 / 100% | 22.1 / 19.0% | 26.4 / 39.9% | **27.1% / 6%, 98.8% / 90%** |
  | English phrases R−60 L−50 / L−52 | 18.6 / 12.8% / 0% | 18.6 / 16.7% | the same as d4d0981c6 | the same | 18.6% / 0%, 16.7% / 4% |
  | English phrases R−60 L−54 / −56 / −58 / −60 | 11–26% / 0–2% | 100% | 11% to 100% | 11% to 100% | **100% / 98%, 98%, 96%, 94%** |
  | room alone: white/pink, −66/−60 (32) | 31 typed | 1 | 5 | 0 | 0 |
  | FSD50K room, household, keyboard, traffic 30 dB down (60) | 52 | 25 | 22 | 2 | 2 |
  | the same at their level (60) | 40 | 35 | 35 | 10 | 9 |
  | zeroes | 0 (the app: 「H」「I'm」) | 0 | 0 | 0 | 0 |

  The cost is English said softly: a short English phrase (「OK」「Thank you」「Good night」…, 12
  letters or fewer, no Chinese) whose 3rd loudest window is under about −52 dBFS in a −60 room, or
  −60 in a −66 room, is dropped nearly always (没有听清). Said at a normal level (−30 to −50) it is
  typed, a lone 「OK」「No」「Yes」「Hi」 too (silero hears them: VoiceQuietTest's golden cases, VO-33).
  Typing a loud short Latin segment without the VAD (so a lone word shorter than silero's 0.25 s of
  speech could pass) was measured and not taken: no English phrase more was typed, and FSD50K's
  room typed 8 of 15 talks (0), keyboard 9 (2), traffic 10 (5), 30 dB down 16 of 60 (2).

  The room going on after the speech (quiet_eval's `tail`: 你好/我们走吧/今天天气很好, normal and
  q44, and the faded *-q44-room clips; 3 s of pink room after, R−60/−66, the app's hotwords; 8 each,
  112) typed 「欢迎」 0 times. Extra text: nihao-q44 at R−66 typed 「你好好」 2 of 8 (h3 and h4 alike);
  zouba-q44-room at R−60 was misheard 4 of 8 (我们走了, 我没走到), as the q44 clips were before.

  VO-32's occasional 「你好。欢迎您」 on the emulator is not explained; suspected, not shown: the
  room clip's hard-cut edges. Offline (quiet_eval's `edge-*`: nihao-q44-room as cut hard, kept outside
  the repo at voice-clips/hard/, k = 0..496 samples in, 32 offsets, 「欢迎」 in the typed text):
  repro, the clip mic's floor (±1 in 128) before and 3 s after: 0/32; (i) −60 pink room before and
  after instead, joined at the clip's hard edges: 15/32; i_fade, (i) with the clip faded 50 ms: 9/32;
  (ii) the floor with the clip faded: 0/32. The emulator's case (the floor around it) was never
  reproduced here, and fading lowers (i) but does not end it, so the edge is at most part of it; in
  the one traced (i) talk the 「欢迎您好」 tokens sat at 1.96–2.32 s, the clip ending at 2.11 s, no window
  voiced. The clips fade now (voice-clips.py) as a cleaner stimulus; the emulator A/B is in uat/12-voice.md
  (VO-32). No rule changed: h4, every segment after a typed one held to the short Latin bar, ends
  「欢迎」 in all four (0/32) but loses a second soft sentence 1.5 s after the first (`two`, CER R−60
  L−54 29.1% → 67.0%, L−58 48.1% → 73.6%, R−66 L−58 24.7% → 35.4%).

  A premise: a pause is a segment of its own only past the recognizer's endpoint (1.2 s of silence
  after text). Room heard as text and Chinese begun within 1.2 s of it would be one segment, the
  room's text typed with it. Not seen: in 160 talks of 1 or 2 s of room before aishell at L−58 the
  room was never heard as text (it takes a longer stretch of room alone), so no Latin was typed with
  the Chinese. The q44 clips: 3rd loudest window −59.5 dBFS as made, the debug log's env −60 to −54
  on the emulator. VoiceQuietTest's golden cases: `lib/ime-core/src/test/resources/voice/quiet_golden.py`.
- **Polish guard**: `VoicePolishStream` applies `VoicePolishTags.guarded` to the tagger's labels:
  an English letter is kept, nothing written after a full stop. Its del took the second r of
  「sorry」 (「I'm sory.。」); on ascend's 142 texts with English it changed letters in 10, guarded
  in 0, the Chinese unchanged; repeated English words (「big big」) are no longer taken out.
  `apply` stays voice-eval's tags.py to the letter (`guard = false` for its goldens).

## Strings and languages

The app speaks 简体中文, 繁體中文 and English (`AppLanguage`, chosen in the settings, independent of
the phone's language) and nothing else: `androidResources.localeFilters` drops libraries'
other translations. The host's own texts (the status area's toggles, tips, the input method name
overlay, the default group's name) are app string resources too (`AppHostStrings`); there is no gettext.
