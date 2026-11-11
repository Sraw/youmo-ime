# The app (`app/`)

Android glue, kept thin: read Android state and preferences, pass values into `lib/ime-core`,
apply what comes back. If a change adds an `if` that decides behaviour, ask whether it belongs in
an ime-core class with a test.

## Packages (`app/src/main/java/org/fcitx/fcitx5/android/`)

| Package | Holds |
|---|---|
| `core/` | The host's Android side: `KotlinHost` (the `HostApi` over ime-core's `HostCore`, its lifecycle and config files, [host.md](host.md#the-apps-side-kotlinhost)), `HostDispatcher` (the host thread and clock), `HostResources` (`AppHostStrings`, the host's texts from app resources), `HostApi` (suspend calls into the host; its `eventFlow`, the host's own `HostEvent`s out of it), `EngineBridge` (ime-core's `Engines` with their Android side: assets, user dir, saves; the host's `EngineHost`), `SubtypeManager`, `InputMethodNames`, `NativeMatrixKernel`; the Android-free DTOs of the same package (`KeySym`, `KeyStates`, `CandidateWord`, `InputMethodEntry`, `FormattedText`) are in ime-core, their `KeyEvent` conversions here (`KeySymEvents.kt`, `KeyStateEvents.kt`) |
| `daemon/` | `HostDaemon`: the one fcitx instance, shared by connections (`HostConnection`) |
| `input/` | The `InputMethodService` (`FcitxInputMethodService`), `InputView`, and what the keyboard shows: `bar/` (the candidate bar / toolbar, `KawaiiBarComponent`), `candidates/` (horizontal bar, expanded grid, floating window for physical keyboards), `keyboard/` (`TextKeyboard`, `T9Keyboard`, `NumberKeyboard`, `KeyDef`/`KeyDefPreset`, `KeyGesture`, `CommonKeyActionListener`, `canvas/`: the scene drawing), `picker/` (symbols, emoji), `popup/` (key previews, long-press keyboards), `clipboard/` (the history panel; 「切词」, `ClipboardChunksWindow`, shows ime-core's `ClipboardChunks` pieces, picked by tap or by a sideways sweep (ime-core's `SwipePick`), Chinese split by `HostApi.wordBoundaries`; the history at scale measured by `ClipboardScale`, JVM and device: on redroid at 10 000 entries opening ~30 ms, clearing ~0.2 s, a backup ~0.2 s; at 100 000 opening ~0.1 s, a page half way down ~0.35 s, clearing ~1.5 s, a backup ~2 s), `editing/` (text editing panel, `InputConnectionEditor`), `status/` (the "more" panel), `preedit/`, `broadcast/`, `wm/` (`InputWindowManager`), `handwriting/`, `floating/` (the floating keyboard), `dialog/`, `editorinfo/` (the `EditorInfo` inspector), `learning/` (a candidate's long-press menu worded by the app from the engine's `LearningResult.Menu`, `CandidateLearningMenu`; the undo chip, `UndoChip`, the keyboard's one notice: see [Notices](#notices)) |
| `data/` | File-backed managers: `pack/` (the resource packs: `PackStore` the installed shelf and its state, `PackImports` an import from the picker in the app's scope with its progress and results, `AppData` every data file's door, a pack's file or the APK's asset; data-and-build.md#resource-packs), `prefs/` (`AppPrefs`, the managed preference framework), `theme/`, `clipboard/` (Room), `pinyin/` (imported dictionaries, custom phrases; sources are `<name>.txt.gz[.disable]`, named through `SourceNames`; `MergedDictionary` compiles the dictionaries turned on into `merged.ymd` on one `dictionary-compile` thread, keyed by a stamp of the sources, the base data and a compiler revision; the engine maps the file, uses a stale one until the new one is made, and is reloaded through `made`. An older build's plain `.txt` is work by itself (`SourceNames.unconverted`: `current()`, `pending()`, `mergedWords` count it): the next compile gzips it first (`Step.CONVERT`, the progress's first share, 正在转换 on the 词库 page, the idle bar's 正在升级词库格式) through `<name>.txt.gz.convert.tmp`, and is an upgrade (UPGRADE) only by what it finds: a text converted or not, a v1 `merged.ymd` dropped, or `.merged.upgrading` (written before the first conversion or the drop, deleted when a non-import compile ends, so a kill in between is still an upgrade at the next start); never by files' times. A text that cannot be read, or a `.gz` that is not gzip or is cut short, is skipped and listed in `.merged.unreadable` (the page: 读不了 / 文件损坏，可能不全, delete and import again; a damaged `.gz` keeps what was read before the damage); a text whose gzip cannot be written (the disk full) stays as it is, listed in `.merged.unwritten` (the page: 没转换成, free space and reopen). A text converted whose delete is refused is listed in `.merged.left`: not converted again while its `.gz` of the same state is there (its words are in it); each compile tries its delete again (no reconversion, no UPGRADE) and drops the entry once it is gone, or once that `.gz` is (deleted outside the page, or a restore left only the other state), the text then converted like any other. The entry is the text's name on, length and mtime (`leftKey`), so a turn keeps it. Listed files are skipped at each start and reload till they change; only a `.merged.left` text leaves `pending()` false, an unreadable or unwritten one keeps it true (its words are owed). Opening the 词库 page (`retry`) clears `.merged.unwritten` and `.merged.unreadable` (but a damaged `.gz`'s entry), never `.merged.left`. Imported words are missing until it ends. `turn`/`delete` resolve a dictionary by name under the sources lock, never the `File` the page listed, and act on all its files together: `turn` renames a text and its `.gz` both (the text turned back if the `.gz`'s rename is refused), `delete` deletes the text first and keeps the `.gz` if that is refused, so the two never split), `punctuation/`, `quickphrase/` (`QuickPhraseManager`: the user's quick input, `mine.mb` (entries with notes, and folders) and `mine.used` read, changed and written whole under one lock, the settings page and the keyboard both writing; files of before read converted and written as version 2 at the next change, host.md `QuickPhraseFiles`; backups take `data/quickphrase.d` as a followed directory, an old backup restored read the same way), `UserDataManager` (backup/restore) |
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
bar) and, given the situation (not a password, chttrans offered, one-handed
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

A setting only the keys read (`remakeKeyboardsPrefs`: each input method's keyboard, 九键's letters and
symbols, the marks' places, the long presses, the letters' case) does not make the view anew: `InputView.remakeKeyboards` ends what was typed the same way, drops
`KeyboardWindow`'s keyboards and the bar's number row (`IdleUi.remakeNumberRow`) and shows the letters as a new view does, folds the bar's toolbar, starts the
field again and replays the engine's state (`onStartHandleFcitxEvent`), which picks the letters and the
code row's height (060-render-perf-2 #1; `RemakeKeyboardsTest` holds it to what a view made with the setting shows).
The bar's `ToolButton`s are made as they are first placed (`IdleUi.button`, wired and lit by
`KawaiiBarComponent`'s `onButtonMade`), the toolbar's as it first opens. A component's scope key
(`UniqueComponent.type`, the library's kotlin-reflect walk) is found once per class (`ComponentTypes`,
`TypedComponent`).

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

A theme's own key motion (060-render-p3 A1) is apart from these: a key's `Motion` in the scene (its look's
`transition`, 0–600 ms, CSS's five curves; the pressed look's `press-scale` 85–110% and `press-sink` 0–4dp, their
`transition` no longer than 80 ms), drawn by `KeyboardCanvasView` on the two nodes each key has. The pressed face is
shown whole at its first frame (CanvasFrame's alpha swap, never faded in); only its scale (about the key's middle) and
sink move. Let go, the pressed node fades out over the normal one, scale and sink back; a state change (Shift, locked)
where the key was draws the face it had over the new one, fading (from where a release fade had it); a key laid out
anew (a size, one-handed) or relabelled in its state is shown at once, and a held key keeps its press scale. A `ValueAnimator` each (`Moving`), its frames setting the nodes'
`alpha`/`scaleX`/`scaleY`/`translationY` alone and asking a frame (trace section `canvas anim`): nothing recorded or
laid out; ended, nothing asked. A press while it moves jumps to pressed, and any key's press ends the other keys'
release fades and crossfades (typing fast, a key's frame draws no fade with it). A key pressed sooner after the last
key's press than its own `transition` (typed fast) does not move at all: pressed at its press scale at once, let go
with no fade, as with motion off; so a long `transition` plays only on keys typed slower than it. The nodes have no overlapping rendering, so a fading
one is drawn at its alpha straight, not through a layer each frame. `KeyMotionSwitch` says whether keys may move
now, asked at each press of a key that has motion: 键盘动效 (`AppPrefs.advanced.keyboardMotion`, default on) on, 禁用动画
off, battery saver off (kept from `ACTION_POWER_SAVE_MODE_CHANGED`, not asked of `PowerManager` on a keystroke) and
`ValueAnimator.areAnimatorsEnabled()`; not, or a theme without motion (every built-in one), no `Moving` and no animator
is made and the keys draw as before. Detached or its window hidden (the keyboard hidden keeps the view attached), every animation stops; a key's motion not carried into a new scene stops too. `KeyMotionTest`.
A theme 4.0's animated `<picture animated="true">` (A4, `input/AnimatedPicture.kt`): InputView's base is a
`PictureBaseView` whose background is the still picture till `AnimatedBoardDrawable.later` has read the file once
(`ThemeAssetLimits.animated(File)`: the header walk bounded, a padded file refused) and decoded it by `ImageDecoder`, off
the main thread, sized to the board, into an `AnimatedImageDrawable` (RenderThread steps its frames: no main-thread
frame); `PictureHost.play` then puts it under the keyboard, the views showing the picture drawn again see-through and
the keys' canvas on a hardware layer. It is laid as `PictureWindow.centerCrop` lays
it (the `src`, not a cut: the cuts are its still picture), black over it at the picture's brightness, never blurred. It
plays only while the view is shown (`onVisibilityAggregated`: the window hidden, the view detached as a theme changes,
it stops) and `KeyMotionSwitch.on` (键盘动效, 禁用动画, battery saver, the system's animations); the prefs and battery
saver turned while shown (`KeyMotionSwitch.listen`) stop or start it at once. A key, the bar or anything else showing the
picture (`Op.Picture`, `ThemePictureDrawable`) over it draws no picture of its own: see-through, only black over it to
take it from the board's brightness to its own (`PictureWindow.seeThroughDarken`; never lighter). One past
`ThemeAssetLimits`' animated limits (4 MB, 720 px a side (decoding is most of the cost and goes with the pixels: 060-render-perf, 2026-11-10), 300 frames, a frame under 50 ms; read from its chunk heads,
`AnimatedWebp`), or not decoded as an animation, is drawn as a still picture (its cuts, as any picture), a warning as the
pack is imported (`theme_pack_animated_still`). Elsewhere (the settings' preview, thumbnails, the voice glass, the
floating code strip) it is still. `AnimatedPictureTest`.

A theme's popups move too (060-render-p3 A2), asked of the engine (`SceneBuilder.popupMotion`, a `PopupMotion`):
`appear` on `popup`, `preview` or `menu` (`fade`, `scale` from 60% or `rise` 12dp, each faded in, 0–250 ms, the
app's enter curve) and `transition` on `popup` or `preview`, the fade it goes in. `PopupComponent` plays them by the
view's `animate()` (`PopupMoves`: alpha, scale about the key's middle, translation; nothing drawn again); a focus
moved is a new scene in the same view and plays nothing again, and a finger slides over a long press's keys while
they come up (focus follows the finger's position, not the view's scale). Without `appear` a long press's keys and a
menu pop in as ever (`popIn`) and a bubble shows at once; without `transition` a popup goes at once. A bubble
replaced by a long press's keys or a menu goes at once. Like keys typed fast, a bubble that comes up sooner after the
last than its own motion (the longer of its `appear` and `transition`) neither comes up nor goes moving, and a new
bubble ends any fading out at once. With TalkBack's touch exploration on, a popup goes at once (a fading one would take the hover over it). The engine is asked only where a style sets popup motion (`PopupScenes.moves`, once a theme), and `KeyMotionSwitch` only then; off, everything as before.
`PopupMotionTest`.

A key's press effect (A3, `effect=` on its pressed look: `ripple-image(file, radius%, ms)`, the radius of the face's half
diagonal, 10–200%; `particles(file, count ≤ 12, spread ≤ 64dp, ms)`; 1–600 ms; `none`) is the scene's `Motion.effect`
(`KeyEffect`, px), played by `KeyEffects` over every key at each press, from where the finger came down (`SceneKey.hotspot` →
`touchedAt`; a press without a touch, the key's middle). Each image (a pack's file, ≤ 256 px a side, ≤ 128 KB, PNG or WebP,
`ThemeAssetLimits`; decoded with the key images, `ThemePackFiles.images`) is recorded once into a node; a ripple is a node at the
key's face clipped to it (a rounded rectangle of one radius by its outline, `RenderNode.setClipToOutline`; another shape by its
path, even-odd, recorded in the node at each press: an outline's path clips from API 33 alone) round a node drawing the image (filtered), scaled 0 → the radius (ease-out) and fading; a particle is a node drawing the image (3 of
its file's px a dp, however it was decoded) flying to its end (`KeyEffect.Particles.ends`, seeded by the view's press count: the same presses, the same flights,
JVM and JS alike) and fading. Pools of at most 8 ripples and 48 particles, the one taken longest ago taken past them; the fading nodes drawn with no layer
of their own (`setHasOverlappingRendering(false)`); made at the first effect played (none for a theme without effects); one `TimeAnimator` while any
plays whose frames set alpha, scale and translation alone (trace `canvas anim`), ended when none does. Effects play on every press,
typed fast too (unlike the press scale and the release fade: the pools bound them), where `KeyMotionSwitch` says keys may move;
hidden or detached, they stop (detached, every node's drawing let go). `KeyEffectTest`.

### Keys

A layout is a list of rows of `KeyDef`s (`TextKeyboard.Layout`, `T9Keyboard`, `NumberKeyboard`, `HandwritingKeyboard`):
an appearance (text, image, width, variant) and behaviours (`Press`, `LongPress`, `Repeat`,
`Swipe`, `DoubleTap`), each a `KeyAction` (`HostKeyAction`, `SymAction`, `CommitAction`,
`LayoutSwitchAction`, `QuickPhraseAction`, `UnicodeAction`, `LangSwitchAction`,
`SpaceLongPressAction` …). `CommonKeyActionListener` performs them. A key's long-press
characters come from `PopupPreset` (built in) overridden by the user's `PopupOverrides` (the full
keyboard's, by label, `popup_overrides`), the other keyboards' from the keyboard (`GroupedKeyboard`'s
made as they open …) overridden by name (`key_popups`, `PopupOverridesCache.byName`: `BaseKeyboard`
swaps a `ShowKeyboardAction`'s list for the user's, and wires one on a key that had none; the function keys' comma and period too, every keyboard's at once, `KeyShortcutPages.FUNCTION_POPUPS`; the settings' editor starts a key with none set from `OwnLongPress`, its board's node as the key cap drew it, resolved as the keyboards resolve it for the first of pinyin, 双拼, English whose keyboard the tab is, the key's corner first, and stores nothing while the list is that, an empty list where every one is deleted (off); a list set by name opens in the own's shape, `OwnLongPress.shaped`: its row with the first in the middle, as-is where the own is); the first
of them (长按首选) is the small label in its corner. Every key has two slots, up and down
(060-swipe-slots; `SwipeSlots` in ime-core, `KeyDef.shortcut` naming the key, `fn/<name>` a function
key; stored in `key_swipes` as `@1\t<first-up|first-down>` then a line a slot over `SwipeSlotPreset`'s),
each the long press's first, a command, a text or nothing; 上滑 and 下滑 (`swipe_up`, `swipe_down`)
switch a way on or off. 0.5's 滑动 (`SwipeLayers`), `key_shortcuts` and the two places are migrated
once (`SwipeMigration`, from `AppPrefs.dropRetired`; an upgrade keeps 0.5's default, shortcuts up).
`KeySwipe` (ime-core) decides the lift: an empty slot, a first with no character, or a way off, is a
tap; swiped past and back, a cancel; both ways off, a slide off the key takes the press back. Function
keys (space, backspace …) reach their own up and down slots, nothing there leaving them as they were;
space and backspace lock a touch to one axis (`KeyGestureRecognizer.axisLock`); in a multi-line field the
space slid sideways then moves a line a vertical step (`KeyGestureRecognizer.crossStepY`, `SpaceCursor`,
060-space-2d): not while composing, and no Up at the field's very start nor Down at its end, so focus never
leaves the field. `KeySwipes` (app)
wires it on the typing keyboards (`shortcuts = true`), buzzes at the threshold, opens the edit menu
(`EditMenuLayout`, `PopupMenuUi.Fixed`) there: up where a notice its size goes (`EditMenuLayout.place`
on `UndoChipPlacement`'s place, which `InputView` hands `PopupComponent.menuRoom`; over the bar where
that is off the view), 粘贴 across from the key, not under the finger (the user, 0.5.1's test build),
picked by how far the finger moves (`PopupFocus`), a notice shown put away or, a failure, hidden till the menu goes (`onMenuShown`, `onMenuGone`,
`UndoChip.covered`/`uncovered`). 滑选 (`KeyCommand.SwipeSelect`) reached there takes the touch
instead: its sideways steps (`SwipeSteps`, the same counting and the same 10 dp threshold the
backspace's selection gets from `KeyGestureRecognizer`) go to `CommonKeyActionListener`'s
`SwipeSelection` (ime-core), which begins after what is typed is committed (`afterPendingInput`) and
the editor reported it (`whenSelectionSettled`, woken by the report, a fifth of a second at most; the steps meanwhile
kept), reads the field around the selection once (`readAround`, not in a password)
to step a grapheme a step (the read dropped where its cursor is not the tracked one, or is only the
tracked one while a selection predicted is not yet reported), and sets the selection from its anchor (`EditingSession.select`).
Right of what is known to be in the field a step is tried alone, the steps after it kept till the
editor reports it (`reportedSelection`, `selectionSettled`), as one past the end is ignored; one
given up (the finger back off it) or still unreported at the lift is no longer predicted
(`forgetSelection`), one reported by then kept for the next touch's anchor. The buzz is the listener's, where the selection moved. Any other key action
or another field (not the same one restarted, `sameField`) cancels it (a second finger); the lift (`EndSwipeSelectAction`) leaves the
selection, the steps moved before it began still taken as it begins. A
shortcut named in the key's bubble (`PopupAction.PreviewUpdateAction.words`) is fitted by
`PreviewLabel`: a function key's size scaled as the keys' text (a letter's too, `engine/Popups.SYMBOL_DP`),
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
two settings with one list of places (`KeyMarkPosition`, `KeyMarks.At`: 右上 右下 左上 左下 底部 无), 上滑图标位置 (`AppPrefs.keyboard.swipeUpPosition`, key `swipe_up_position`, top right) and 下滑图标位置 (`swipeDownPosition`, top left), each way's slot drawn at its own (`SceneMarks.settings`, keyboard-scene's `Content.charSlots`: a first as the corner symbol, a command an icon, a text its first two graphemes and 「…」, `KeyMarkText`; a way off draws nothing). A function key's two keep 0.5's place: both at the place of the way that is not the first's, its down one mirrored top for bottom (`Content.functionSlots`). 底部 is a shortcut's bottom edge middle, a symbol's under the letter; on landscape's and 紧凑's low keys (`KeyMarks.onKeys`) the symbol's 底部 is the top right (bottom right where the other is), the shortcut's the first of top left, top right, bottom left, bottom right the symbol does not take. A mark with no room in its slot is not moved to another corner (S4e): it is drawn smaller, else left out (above). The settings page never lets the two share a place but 无 (`ManagedPreferenceUi.Apart`: the other's place greyed out in `MyListPreferenceDialogFragment`, noted whose); two stored in one place (an import, a backup) are read apart by `KeyMarks.settled`, the symbol yielding top for bottom on its side, 底部 to top right (`swipeSpots()` is what every key and preview reads), and stored so at the next start (`AppPrefs.dropRetired` → `settleMarkPositions`; `Apart.read` reads them so on the page till then, and a list read again as the other changes: the summary is what the keyboard draws). Both are in the service's `remakeKeyboardsPrefs`: the keyboards (the bar's number row too) are made anew as either changes (`KeyMarksUatTest.eitherChangedTheKeyboardIsMadeAnew`). (0.5.1's testers saw the symbols pushed bottom right, then the shortcuts there after 滑动 changed ways: placement followed the swipe; `KeyMarksUatTest` holds the first open to what any setting change leaves, `KeyMarksTest` every combination.)

拼音键字母大小写 (`AppPrefs.keyboard.letterCase`, key `letter_case`, `LetterCaseChoice`: 总是小写 / 跟随 Shift / 总是大写, default 跟随 Shift) cases a Chinese input method's letter faces and press bubbles only; what is typed and Shift are untouched. The decision is keyboard-scene's pure `LetterCaseRule` (`forIme`: English always follows Shift, its case being what it types; `upper`: the setting, Shift's state, `shiftless` for 九键 and 14/18 键, which show capitals following Shift, and the hidden keep-uppercase). The keyboards pass it as `Texts.letterCase`; `Expand` reads it in `{case:x}`, `{nine-letters:N}` and `{group:qw}` (k14/k18's group faces); a key's screen reader text is the label as 跟随 Shift shows it, so it reads the same whichever is chosen. `TextKeyboard`/`GroupedKeyboard`/`T9Keyboard` case their bubbles by the same rule; `KeyCaps.board` (`SceneKeyCell.Board.letterCase`) and the 九键 letters page show it too. The hidden 键盘字母保持大写 (`keepLettersUppercase`, `keep_keyboard_letters_uppercase`) still holds under 跟随 Shift, 总是小写 over it; with it on, English's press bubble shows the capital its face shows while the letter typed still follows Shift. A table's letter with no root (`{root:x}` empty in `Texts.other`) falls back to `{case:x}`. In `remakeKeyboardsPrefs`. Held by `LetterCaseRuleTest`, `ExpandTest`, `LetterCaseScreenshotTest`.
五笔's 全部字根 leaves the letters' shortcuts unmarked (`TextKeyboard.charShortcutMarks`: its roots start in the top left corner), shown as 键名 at 紧凑 though (`TableKeyboard.atHeight`, the setting kept); at 键盘高度 紧凑 (`KeyboardHeightLevels.isCompact`, read as the keys
are made by `keyboardCompact()`) the symbol never under the letter, the shortcuts drawn as anywhere, smaller, and the keys' text 0.9 (`KeyTextScale`, the smaller of it and floating's; 大千's symbols 0.9 again, `KeyTextScale.dachen`, cut off on a 360 dp phone otherwise). The service
makes the keys anew only when a height change crosses into or out of 紧凑 (`compactBuilt`, as
`floatingBuilt`); the theme page's preview does the same in `recalculateSize`. A key's glyph (label, icon) and its marks (shortcut icons and texts, a letter's symbol) keep apart only where their ink meets, worked out by the scene engine as it builds a key's content (`lib/keyboard-scene` `rules/KeyMarkYield`, run from `engine/Content`; 060-render-p2 S4e): one size for each class of glyph across a keyboard, no mark moved to another corner, a mark that cannot fit at its least size left out (the most expendable first: a function key's down shortcut, a shortcut; a symbol last); a mark is kept from the glyph by the gap less half a px (`FIT_SLACK`, L62/L65/L72: as L77's half px, too little to see), everywhere, so none jumps where it stops fitting. `KeyMarksEverywhereUatTest` measures every key of every keyboard (standard, 紧凑, floating, one-handed, landscape; every pair of places the settings allow) for nothing drawn over anything else and prints what was left out; 紧凑 with one-handed and the symbol at 左下, 右下 or 底部 leaves out 22 corner symbols, `a`'s `@` and `w`'s `2` (28 before `FIT_SLACK`, which saved `m`'s `\`; recorded in 060-pending-decisions, S4e). The icon 8 dp at 紧凑. `KeyCaps`' caps are scenes and yield as every scene key does (below, S6). 注音 keyboards name their keys `zyfull/<QWERTY letter>` and
`zy9/1`…`zy9/11`, the groups by their place (`ZhuyinShortcutNames`; the tones and 注音九键's slot none, as 九键's 1). What each punctuation key types (。 or . by the punctuation mode) is the host's (`HostCore.startPunctuationKeys`), kept from the last run in `filesDir/punctuation-start-keys` (`PunctuationStartKeys`, written by the host as it changes, read on a thread of its own at the service's `onCreate`) so the keys show it before the host is up (060-render-p2 S7a); `PunctuationComponent.known` says whether it is known. None kept (a first install, the host not up yet): `KeyboardWindow` holds a punctuation key's tap back (not typed, its bubble not shown) and buzzes once. A long press's keys (`PopupKeyboardUi`, `PopupMenuUi`) are one small size whatever the
key (`lib/keyboard-scene` `engine/Popups`: `KEY_MIN_WIDTH_DP`, `KEY_HEIGHT_DP`, `LIFT_DP`; as wide as the key pressed, 九键's 7 filled the screen),
floating above it, the bubble gone; the finger picks by how far it moved since they opened, not
where it is (`PopupFocus` in ime-core: 0.6 of a key a step, held past a row's ends, an empty slot's
nearest key, a little hysteresis, only down off the key cancelling). Every layout's keys are laid as the full keyboard's (060-edge-popup, 用户 2026-11-10): the key's own (`PopupKeyboardUi`'s `inRow`, the 14/18 键 corner or 九键 digit; the first otherwise) on the column `calcInitialFocusedColumn` picks, the rest as listed (`PopupFocus.ownFirst`) right, left, right … round it (`createColumnOrder`), so by the screen's edge the key's own is at that end and the finger only slides inward; each new key a buzz under 按键振动
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
`splitDock`, and a button back to the whole width. These side buttons, split's and one-handed's, are
drawn as the theme's `side-button` says (060-render-p3 T7a, `SceneWindow.sideButton`; none set: the
function keys' text colour on nothing, a round ripple of the pressed colour, as before; the ripple
`side-button:pressed`'s overlay over `side-button`'s), dressed once a placement (`InputView.dressSideButtons`);
split's room between the halves is filled with the theme's `split-gap` where it sets one (the engine draws it,
`SceneBuilder`), else it shows the keyboard's own. The window's scenes are the screen's and settings' and the
board's placement, `@one-handed` and `@split` (`SceneWindow.placement`, as the engine's `Env.scenes` has them):
`InputView.placeScenes` tells the side buttons, the docked handle (`FloatingHandle.dress`, the look
worked out by `FloatingController.handleLook`: the view reads no settings) and the navigation bar as the
board is placed anew or the keyboard's height changes `@compact`.

The navigation bar under a docked keyboard is the theme's `navbar { color }` where it sets a colour
(T7a; `follow`, the default, and floating: as before, the colour under the keys): where the keyboard
is drawn behind the bar (导航栏背景 全部; always from Android 15) a strip in the bar's room under the
keys has it (`InputView.navbarStrip`), and 仅颜色 sets the window's bar colour
(`NavigationBarManager`, its buttons black or white, whichever stands out more on it:
`SceneWindow.darkButtonsOn`; a mid grey's luminance is 0.22, yet black on it stands out more). The
hardware keyboard's bar (its candidates alone) is the theme's whatever the floating setting says.

The candidate bar, the expanded candidates and the hardware keyboard's window are drawn by their views
as the theme's `candidate`, `candidates-divider`, `bar` and `candidates-panel` say (060-render-p3 T6a):
the engine works out a `BarLook` (keyboard-scene `engine/BarLook`: per candidate plain, `:first`,
`:selected` and both, its fill, pressed overlay, colours of the words, `label` and `comment`, font and
weight; the corners, the inset from the cell, the padding, the `gap`, `label-style`, the lines' colour,
the bar's base and the panel's) in the window's scenes but floating (`SceneWindow.scenes`), and
`candidates/SceneBar` turns it into drawables, a typeface and px (`(dp × density).toInt()`, as the
engine's sample bar). A theme setting none of it, every built-in one, has the bar as before: no fill,
the pressed colour over the whole cell, 3.0's candidate colours and font (LookTheme writes 3.0's candidate
font and weight as the theme's `candidate` style, its font `<font name="candidate">`; a font's name is read
as the keys' are, `SceneFonts`: `candidate`, `key`, else the system's till a theme's own fonts are, T8a), the padding of 候选字号
(`KawaiiBarComponent.candidatePaddingDp`), the divider's 1 dp line, the panel the bar's colour over flat
keys; a look that made no theme the same of its colours (`SceneBar.plain`). A candidate's shape is a
`LayerDrawable` inset, not an `InsetDrawable`, whose inset a view takes as its padding. Corners not all the same
(`radius="12 0 0 0"`) are clamped differently: the engine (`Faces.radii`, the sample bar and the web's preview) keeps each
to half the box's shorter side, while `GradientDrawable` (Skia's round rect) keeps them as written unless two on one side add
up to more than it, then scales all down together; so a corner over half the height but not over the side is rounder on the phone
than in the preview (060-render-p3 T6a review L3, kept: the same where every corner fits, as every built-in theme's do). `gap` widens
the horizontal bar's room between two (the line in its middle, `FlexboxVerticalDecoration`); the bar's one row is laid out
by `CandidateRowLayoutManager` (as many as fit, then the leftover shared: FlexboxLayoutManager's first line to the pixel,
without its lines or caches, the most of a key's layout while typing; 060-perf-cands), the expanded window keeps Flexbox; a
transparent divider draws nothing but keeps its room. `label-style` (number: 1 2 3 of the whole list,
the expanded grid's numbers going on from the bar's; dot: •; none, the default) stands before the
words, a space after it; the hardware keyboard's window, whose default is fcitx's own label, shows
fcitx's label for number; a screen reader reads the candidate without the theme's number or dot (the
text's content description). `:first` is the list's first in the bar (scrolled, it is still the list's
first, out of sight), the page's first in the hardware keyboard's window (each page's), and never in the
expanded grid (its cells go on past the bar's). `gap` is the horizontal bar's alone; `padding` the bar's
and the flexbox expanded window's (the grid one and the hardware keyboard's window keep their own); the
hardware keyboard's window has no pressed colour.
One BarLook is worked out for a bar and handed to its holders (`HorizontalCandidateViewAdapter.bar`,
`PagingCandidateViewAdapter.bar`, `PagedCandidatesUi`). `CandidateViewHolder` draws a holder bound at another index again where its
look or label differs (the first's own look, its number). The candidate's size is still 候选字号's
(the bar's height and the grid's rows follow it): a theme's `size` there is warned of, not read.
`BarThemeTest` holds the views to the theme, and to `BarSample.scene` (the engine's sample bar, its
snapshots `bar-*.txt`): each candidate's box within a pixel, its fill and colours the same, of a theme of
none of it, pills and a dotted one, a candidate with a comment among them. The sample is the bar at its words'
own widths (`NeverFillWidth`); filling its width (Auto, Always), the app stretches each candidate, which the
web's preview does not show.

T6b adds three to `BarLook`. `bar height=` (80%–140%, on a style of bars alone, a scene allowed) scales the
bar 候选字号 gives (`CandidateBarHeight.dp(…, percent)`, rounded to a dp; a line of pinyin readings is added
after, unscaled): `KawaiiBarComponent.heightDp(context)` takes the active theme's in the window's scenes of
that context (`SceneBar.heightPercent`, kept per theme file and scenes), so every view sized by the bar follows,
the expanded grid's rows and a hint reading's baseline (CandidateItemUi, the grid's measure) too; the keys
keep their height, the keyboard grows by the difference (the 70% cap, `KeyboardHeightCap`, counts the bar as
before), docked, floating and one-handed alike (`BarToolbarThemeTest`). The bar is one over every window
form (`SceneWindow.scenes` is never floating, split or one-handed), so a bar widget's selector under
`@floating`, `@split` or `@one-handed` never matches and is warned of. `toolbar` (fill, radius, icon-color;
`:pressed` overlay; `:selected`, a toggle on, fill and icon-color) is every `ToolButton`'s (IdleUi works it
out once for the bar's buttons): nothing set, the press highlight's circle and `toolbarIcon`, on `accentKey`,
as before; a state with a fill or a radius is a shape (`SceneBar.shape`; no radius: a circle in the button's
middle, `ToolButton.Circle`) with the overlay over it pressed, a state with neither the press highlight as
ever (`toolbar:selected fill` alone leaves every button off as it was; `ToolButton.setOn`). `preedit` (color, cursor-color,
underline) is the code row's, the floating strip's and the hardware keyboard window's code (`PreeditUi`)
and the syllables' text colour: nothing set, `keyText` and fcitx's `UnderlineSpan`; an `underline` of the
theme is drawn by `PreeditUi.PreeditTextView` under each span fcitx underlines, at the font's underline
position (`Paint.underlinePosition`, API 29), to each line's end edge where it wraps (`segments`): no public
API sets an underline's colour or thickness (`TextPaint.underlineColor`, `underlineThickness` are hidden at
every level). `none`, or 0 wide, none. The floating code strip's colour and the settings
page's preview bar (its base and height, of the theme shown) are the theme's `bar` too. A candidate's
`size` stays unread: 候选字号 is the user's setting, and the bar's height the theme's.

Floating (`ThemePrefs.floatingKeyboard`, the status area's 悬浮键盘; the input view is made anew for
it): `keyboardView` is `FloatingKeyboard.widthDp` wide (70% of the screen, 300–420 dp), its boards as
much shorter (85% at least), with `FloatingHandle` at the bottom (24 dp: a short bar and the dock button;
`ThemePrefs.floatingHandle` puts it on top, the view made anew). The dock button is 56×24 dp, a
smaller touch target than 48 dp, traded for a thin handle (review R11). The view is clipped to rounded corners
with a shadow (`FloatingCorners`; users asked for both). Frame and handle are the theme's `frame` and
`handle` (060-render-p3 T7a: radius, border, shadow; fill, grip colour, dock icon colour, grip
width and height), read through `SceneWindow` from the engine's `WindowLooks`, whose defaults are the
rules the app worked out itself before: the radius is the keys' radius plus their
least margin to the edge (`WindowLooks.cornerDp`, held to theme 3.0's `FloatingKeyboard.cornerDp` at
every radius and margin by keyboard-scene's `WindowDefaultsTest`; the nested-corner rule: the curve runs
alongside the corner keys' and clips none), square where the keys are (按键圆角 直角, the owner's one setting for corners); the
handle is a shade of the bar towards the key text (`WindowLooks.handleColor`), as some themes'
bar is the keyboard's colour (on top; at the bottom it is the keyboard's own); the grip 36×4 dp of
the function keys' text colour; the shadow 6 dp. A frame's border is a stroke over the keyboard
(`FloatingCorners.border`), its shadow's colour the outline shadow's (Android 9+; its offset is the
system's, a theme's ignored; the system scales its alpha by its own shadow alphas, ~4% ambient and
~19% spot, so a theme's shadow colour shows faintly). The frame is worked out once a screen (and `@compact`), its corner
once a size too (`FloatingController.frame`, `cornerPx`), as the outline and the border ask for it as they
draw. A look that made no theme (`SceneThemes` refused it) has the window of its keys as before:
`FloatingKeyboard.cornerDp`, the handle's shade of the bar (`SceneWindow`). As Gboard's, it never
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

The same touch is a stroke (笔画筛选) where that is chosen instead: one setting,
`AppPrefs.keyboard.keyDrawing` (`KeyDrawing`: Off, Glide, StrokeFilter, Handwriting; the page lists
Handwriting always, labelled as needing the text pack while that is not ready, as a ListPreference
cannot disable one entry), so the two are never both on and English glides none while strokes are
chosen. 0.5's two switches (`glide_typing`, 0.4's key too, and `stroke_filter`) move over at the
start (`AppPrefs.migrateKeyDrawing`, a backup's at the start after its import): glide if it was on,
else strokes if they were, else off (both on, which only a backup or a hand edit holds, is glide),
the two old keys dropped. The page's row is an `AppChoice` whose summary is the choice alone.
`GlideInput` decides at the finger's down (`strokes`): the switch, a word field, pinyin typed (`BaseKeyboard.imTyping`, which
`KeyboardWindow` sets from the preedit, not in a temporary mode: its panel alone has an aux line above) and a keyboard whose input method filters (`filters`:
`InputMethodCatalog.filtersByStrokes` of the one shown, on `TextKeyboard`, `T9Keyboard` and
`GroupedKeyboard`, the 14 and 18 keys, which have no glide). `GlideGesture` starts it far sooner than
a glide (`STROKE`, 0.3 of a key any way, keys counted as wide and tall, before the long press): a
竖 from the bottom row or a short 点 must be one before the key's swipe (36dp) is, so while pinyin
is typed a key's swipe is a stroke; on the lift the path, in key widths both ways, is read by `StrokeShape` (ime-core) and
sent as the engine's stroke key, `KeyAction.SymAction` of `Keyboard.strokeKey`; a shape it reads
as none (up, up to the left) is dropped. A stroke may also start between a split keyboard's halves
(`SplitGap`, ime-core: in a row of letters, room between two of them at least half a key wide, found
from where the letters are, so only the 26 keys split have it), passed to `GlideTouch.down` as
`inGap`, and `GlideInput` keeps that down though no key takes it (else its moves would go nowhere);
a tap there still does nothing, and a glide starts on a letter only.

Writing on the keys (键盘手写, `KeyDrawing.Handwriting`; design 060-kbd-handwriting) is `GlideInput`'s
third mode, decided at the down (`writes`: the setting, a word field, no temporary mode, TalkBack's
touch exploration off, `InputMethodCatalog.writesOnKeys` of the keyboard's input method, the
recognizer loadable). `KeyInk` (`input/keyboard`, one per keyboard made with its glide) holds the
pad (`InkPad`), the gesture (ime-core's `KeyInkGesture`, fed by `GlideTouch`) and the ink drawn over
the keys after them in `dispatchDraw` (the pad's colour by default: key text, 3.5 dp; faded over
150 ms as a piece ends). The recognizer is the shared one (`HandwritingRecognition.shared`), made
and warmed up the first time a touch may start a stroke, never for English. Keys go through
`InkKeys` (shared with `HandwritingKeyboard`: in order behind the reads, backspace a stroke first, a
key for the engine wiping the piece) only once the keyboard was written on and in an input method
that writes. Readings go out through `KeyboardWindow` as `HostApi.offerInk(onKeys = true)`, the first
of a piece whose first dot was a tap taken back marked `takesBack` (the engine deletes the letter if
that tap alone is typed: no backspace is sent from here); pieces the engine ended come back through
`EngineState.handwritingEnded`, and `EngineState.keysTyped` (re-emitted after every key) tells the
gesture whether the input method has its own input typed. `restartInk` drops the pad at each new
input; detaching finishes it.

The 060 redraw (`input/keyboard/canvas/`; the only drawing since 060-render-p2 S8a, which deleted the
key views and the 开发者 → 新绘制键盘 switch, its stored key retired): `TextKeyboard` (qwerty.xml) and, since 060-render-p2 S4a, `GroupedKeyboard` (the 14
and 18 keys: k14.xml, k18.xml, their bottom row grouped-bottom.xml), since S4b `NumberKeyboard` (number.xml,
its seven pads by `when="pad=…"`) and, since S4c, `ZhuyinKeyboard`'s 大千
(zhuyin.xml) and, since S4d, its 注音九键 (zhuyin-nine.xml) draw their keys with a `KeyboardCanvasView`,
no view a key — the next paragraph but one says how a keyboard moves. The canvas draws the scene `lib/keyboard-scene` makes of its layout in the look converted by keyboard-scene's
`LookTheme` (`scene/look/`) (a 3.0 single key's name the selector of that key, written by `ThemeWriter` with what selectors read escaped: `zhuyin/,` its comma
key, `number/*` the star; a board's key of a role, `qwerty/shift`, has on its own styles what it does not set of the role's `*/shift`, as 3.0's `KeyStyleResolver` drew it, and a key of a face of its own and no pressed one its kind's pressed face by its name; the look's files named as theme 4 takes a file's name (`LookTheme.fileNames`: `a (1).png` is `a__1_.png`, at most 128 characters, numbered where two meet), the theme's name cut to 64; `ScenePainter`: one `RenderNode` a key a face, both kept in the tree and a press only their alpha, since a node left out of a frame loses its recording; a
software canvas, a screenshot's, gets the ops drawn straight); `TextKeyboard.scene` builds the
`Env` from its state (`TextBoardState`, `SceneMarks`, the texts) through `SceneEnvs.of`, which adds
what every keyboard's has alike (size, density, landscape, compact, floating's text scale in the input
method only, dark, flat; one-handed and dock FULL, InputView having placed the keyboard), asked again on `invalidateScene()`,
which each of its show functions calls. It is made again only when what it is made of changed: the
same theme, layout and measurer (by identity: `SceneThemes`, `SceneLayouts` keep theirs a process)
and an equal `Env` return the last scene, which the view then neither records nor tells TalkBack
again. `composing` reaches the `Input` only with a table (qwerty.xml asks it of nothing else) and on
注音九键, its slot's typing (`ZhuyinKeyboard.typing`: the engine's, or a group or symbol just sent to pinyin, not a
tone; `showTyping` calls `invalidateScene()`; zhuyin-nine.xml's `r4c4` variant), so
pinyin's first key and its committed word make no scene elsewhere; in a field with an action, Return's label
changing with the composing (Done to Enter) still does. `AndroidTextMeasurer` keeps the last 512
texts it measured (text, font, weight, size, letter spacing: `Paint.letterSpacing`), so a scene made again measures only what is new.
A text's `text-shadow` and `stroke` (060-render-p3 T4a) `ScenePainter` draws under its fill: the shadow (the glyphs
again, moved, in its colour, the outline's where there is one, blurred by a `BlurMaskFilter` of the blur as its radius,
as a key's `shadow` is, the radius `setShadowLayer` takes too; drawn so, not with `setShadowLayer`, it is sharp where
the blur is 0 and there under a transparent fill as in CSS), the outline at twice its width
(`FILL_AND_STROKE`, its inner half under the fill), then the fill; the mask let go after, so the next op starts clean.
A text in runs (`font-han`/`font-latin`) comes as every run's outline and shadow, then every run's fill (`Paint.content`).
A face's `inner-shadow` (T4b, `Op.InnerShadow`) `ScenePainter` draws clipped to the face (`clipPath`): its ring less its
hole (the face moved by the offset; `Path.op` `DIFFERENCE`, the hole filled by the winding rule as the face is, so a path
crossing itself has no shadow inside its middle) in the colour, blurred by a `BlurMaskFilter` of the blur, as CSS's
inset shadow; the ring reaches far enough past the face that the blur is whole at its edge. `CanvasFrame.extent` takes the
face's bounds for it (nothing of it outside). A cap's band (`depth`) is an `Op.Fill` with `less` the face, drawn by
`Path.op` too, so a translucent face shows none of it through. The blur filters (`Op.Shadow`, `Op.InnerShadow`, a
text's shadow) are kept a radius each, not made every draw (`PopupSceneView` draws its scene straight each frame).
`CapPictureTest` draws a path key with an inner shadow and a depth to build/t4b/cap.png, and checks the band under a
translucent face and a star's inner shadow by pixels.
`Paint.letterSpacing` on Android 10 leaves the last character's spacing out of `measureText` (its ink the same; later
versions keep it), so there a spaced label is that much narrower than the engine's arithmetic has it.
An icon name the app has no drawable of (a layout's) draws nothing and is logged once a process (`SceneIcons.of`).
A theme's `<icon>` (060-theme-sounds-icons §2) is drawn in place of the app's of its name, or under a name of its own:
`PackIcons.of(theme)` of the theme the keys are actually drawn in (`SceneBoard.drawnTheme`; the built-in's, none, where they
fell back), made with the painter in `SceneBoard.drawn` (carried by `KeyboardCanvasView.Drawn`, the painter made anew when the
look or the icons change), `PopupScenes` and `SceneKeyCell`. A file is read the first time an icon of it is drawn, on
`ThemeAssets`' decode thread (`ThemeAssets.runner`): its length checked first (64 KB), then `SvgSubset.read` (the import's
checks again: a restored backup's pack was never held to them) and its `IconPath` segments made `android.graphics.Path`s,
kept by path on the main thread (the last 256, every keyboard's). Till it is in, the app's icon of that name is drawn, the
painter `missed` and the view invalidated once it is (as a key image). Gone, too long or broken: the app's icon (none:
nothing), logged once till the icons are let go. `PackIconDrawable` rasters it once at the size it shows (the canvas's
scale applied) in the tint, a path's own colour kept and faded by the tint's alpha, placed by `SvgSubset.Icon.fit`
(uniform, centred) as the web's `CanvasPainter.icon` places it. Why not on the main thread (060-theme-sounds-icons §2.4):
on youmo29 (API 29 emulator, a dex of keyboard-scene run with `app_process`) 15 worst-case icons (64 KB of arcs, 20 000
numbers, 11 400 cubics each) read and made Paths in 786 ms the first time, 220 ms warm, against a frame's 16 ms.
The theme's styles matched to a key (`Cascade.of`) are kept by target, a key no selector names
as one with no key (the 26 letters share theirs), with what it inherits; `Cascades` keeps a theme's
cascade by the scenes it names (`flat` and not; what is typed only where the theme names it, so a
theme of no `@im=` makes no more of them; the last `Cascades.KEPT` = 32 used, as a theme naming many
scenes has one for each set of them met together), and a cascade keeps the looks the builder worked out of it for
a key (`Cascade.kept`: by board, id, role, classes and states, a `Cascade.KeptKey<T>` naming what is
kept under it). Kept a process too (main thread), for
a keyboard made again (each theme switch builds the input view): `SceneThemes` the last 4 themes read
and their `Cascades`, `TextKeyboard` a look's `AndroidTextMeasurer` and the last 4 scenes made,
`ScenePainter` the last 64 icons it inflated or rastered (`IconKey`: name, the theme icon's file or none, tint and
sizes), the least used let go first (all let go when night mode, density or font scale changes, not for the rest of the
configuration; with the theme icons read, by `SceneThemes.forgetAll`, when a theme's files are written over in place). A theme switched back to
reads, matches, measures and builds nothing again. The `Env` asked again on every change the views
show (a key typed) makes none of its slow parts again: `TextKeyboard` keeps a layout's shortcut names,
the marks' settings by what they are made of (the shortcuts by identity, the swipe layers, where marks
go, `charShortcutMarks`, the names) and the letters' corners by `cornerOf`'s inputs (a table's labels,
the punctuation map, the user's long presses by identity, Shift), so an `Env` equal to the last one is
equal at once. The canvas view keeps a key's pressed state and what its view said of its state with
its nodes, carried to the next scene by id and copy, and `CanvasFrame.faces` hands out its eight
answers made once: a frame allocates nothing a key.
What is kept is kept by the look's value, which names its files by path: a theme's file replaced in
place (a font or image written over under the same path) changes no key, so a kept look's measurer
keeps the old font's typeface and widths, and its kept scenes and themes are the old ones, until the
process ends or four other looks push them out, or a theme's files change: `ThemeManager.filesChanged`
lets them all go (`SceneThemes.forgetAll`: themes, measurers, icons, scenes).
Systrace sections: `scene` (the view asking), in it `theme`, `env`, and the builder's phases
(`expand`, `place`, `cascade.of`, `faces`, `content`, `paint`; `Phases`, `SceneTrace`), `measure
text` under them.

The scene's keys take their touches themselves (060-render-p2 S1): `BaseKeyboard` intercepts every
touch and hands each finger (`TouchTarget`) to the `SceneKey` the scene's
`keyAt` names (`SceneTouch.find`), a key by its `KeyRef` (id, and which copy of a split row's G or V). A
key's touch is `KeyGesture`, `CustomGestureView`'s state machine and timers taken out of the view, read
from the corner of the key's cell as a view's was from its own (a finger in a row's blank end is left of
it, as it was left of A's view). `BaseKeyboard.wire` gives a `KeyHandle` its sound (`SceneBehavior.sound`, the class a theme's `<sounds>` plays,
and `systemSound`, the system click it always had: `InputFeedbacks.soundEffect` → `KeySoundChoice`; the
theme's samples in `ThemeSounds`, a SoundPool on its own HandlerThread, made anew per theme and stamp), the space's and
backspace's swipes, its press, long press, repeat, double tap, swipes (`KeySwipes`) and popups: the
`SceneKey` made of its node's `SceneKeyDef` (`drawKeysWithScene`), so a key does what its view did
before S8a. Its press is shown by the canvas (`showPressed(KeyRef)`), a popup over it placed
at its face across and its cell down in the window (`popupBounds`), as a view's `bounds` were; glide
typing reads its cell (`KeyPlace`) (`SceneTouchTest`). Without a scene nothing is drawn and no key
takes a touch (logged). TalkBack reads the canvas's `ExploreByTouchHelper`: a virtual node a key, all it says the scene's (ime-core's
`SceneA11y.of`): its text the label, its description the scene's `a11y` (an `a11y_*` name the app's
string; a letter "q, 1", label then corner), Shift's state (`a11y_state_shift_*`), the old view's
resource name by role (`KeyNames.RESOURCES`: `button_space`, `button_caps`…, what UAT and
`SoftKeyboardTest` find keys by) and in its extras its scene id and corner (`youmo.key`, `youmo.alt`:
not the hint, which TalkBack would read, 060-render-p2 U6); class FrameLayout, as the views', so no
role is read. What it can do is its `SceneKey`'s (`Host.key`): clickable, long clickable where the
key's wiring has a long click, the two actions where enabled, and a click or long click on the node is
the key's own `click`/`longClick`, which tell TalkBack `TYPE_VIEW_CLICKED`/`TYPE_VIEW_LONG_CLICKED`
on the node as a view's performClick/performLongClick did (a split row's second G or V on its own
node, though its wiring is the first copy's: `SceneTouch.click(KeyRef)`). A node with words is read a
character, word or paragraph at a time as a view's description was (`NodeReading`, `TextSegments`:
View.traverseAtGranularity's moves and events, its cursor, `ACTION_SET_SELECTION` a cursor only).
A key whose state changed (Shift) since the last scene is told as `CONTENT_CHANGE_TYPE_STATE_DESCRIPTION`
on its node. A corner not drawn is not told: with 符号位置 无 (`KeyMarks.At.None`) a letter's
`Texts.alts` is empty, so its node says the letter alone, as the view whose corner was hidden. The table of what each key
says, the views' against the nodes', is `SceneSpeechTest`: the same, a letter's two words in either
order (its view had no description, and TalkBack read its texts in the order the view laid them out:
letter first on a device, as the node; corner first in Robolectric at SDK 30 and up).
A scene's key's popups are the engine's too (060-render-p2 S2): `PopupComponent` gives a key the canvas
draws (`KeyRef.isScene`: not a view's, so a picker's key of no id, whose `viewId` is `NO_VIEW` too,
is drawn by its picker), and a theme that converted (else the built-in theme of its lightness, as a keyboard's), `PopupScenes`, and its bubble
(`PopupEntryUi.showScene`), long press (`PopupKeyboardUi`) and menus (`PopupMenuUi`, the edit menu
too) are a `PopupSceneView` drawing a small scene `SceneBuilder.preview`/`popup`/`menu` makes, its
sizes the engine's (`engine/Popups`: theme 3.0's in dp, its text dp, not sp; its corners the keys'
radius; a theme's `shape="bubble"` (a head over the key, a tail down from it), `path(…)`, `size` and `offset`
(060-render-p3 T4b) all inside the scene, the scene's bottom still the key's, so the placing is unchanged (its width whole
pixels at any `size`); a rectangle or a path moved down by `offset` reaches that much past the scene's bottom, below the
key's, drawn in the `outset`: over a bottom-row key the input view clips what goes past its own bottom (accepted, a
theme's choice to move it down); its shadow drawn, rules D4.7/D4.9, which the views' elevation had and Robolectric never drew
in their goldens; the view as large as the scene and `outset` round it for the shadow, placed
that much up and left of where the views' popup goes; the edit menu's lift, `MENU_LIFT_DP`, wherever
the scenes are). A focus moved is the scene made again, the key
in focus `Selected`; where it goes stays `PopupComponent`'s. The engine's entry points never throw
(`Parsed`); what it could not make is logged (`Timber.w`) and not shown, never a view instead,
and never a popup not drawn that a lift still types: the long press as one with no
popup (`unmade`, not in `showingContainerUi`); a focus it could not draw keeps the scene and the
focus before. A menu's icon is a `SceneIcons` name, a drawable's id read back only as a menu gave it. The symbol and emoji grids'
cells' popups are scenes too since S8a (`SwipePopupScreenshotTest`'s `canvas_*` goldens). A scene's popup has a node
a key for TalkBack (`PopupSceneView`'s `ExploreByTouchHelper`): its words, place, selected, enabled, its
class the view's (a text's TextView, a menu icon's ImageView), read a piece at a time as the views'
text was (`NodeReading`; not a TextView's line or page: one line, no page), and no click, as the views
had none (picked as the finger lifts; `PopupMenuUiTest.onTheCanvasEachCellIsToldAsItsViewWas`). The
harness reads its keys, which is in focus, its cells and text sizes, and a menu's icons from the scene
(`KeyboardHarness.popupChoices`/`sceneChoices`/`shownIcons`).

How a keyboard is drawn by the scene (060-render-p2 S4: the 14/18 keys first, S4b–S5 the rest the same way):
`BaseKeyboard.drawKeysWithScene(layout id)`, last in its init, does it for every keyboard (the only
drawing since S8a: no switch, no key views). A keyboard whose layout does not read still gets the canvas and
draws nothing (logged once; never the views again, U8: `SceneKeyDefTest.aMovedKeyboardWhoseLayoutDoesNotReadDrawsNothing`).
The layouts are read through a `LayoutSource` by id (`BuiltInLayouts`: `lib/keyboard-scene/src/commonMain/layouts/<id>.xml`,
copied by the build as raw resources `scene_layout_<id>`, `-` made `_`), each
once a process with the fragments it includes, kept by the source's key and the id: `SceneLayouts.source`'s
first, and where it has none or it does not read, one step back to the app's own (`fallback`);
`invalidate(key)` has a source's read again. The source is asked each time (`ThemePackLayouts.current`): while a
theme 4.0 pack is in use, `ThemePackLayouts` (key `theme:<file>@<theme.xml's time>`, so a pack imported again is read
again; a theme change makes the input view anew) reads its `layouts/<id>.xml`, only for an id the app has
(`BuiltInLayouts.has`: a pack replaces keyboards, never adds one), and refuses (`LayoutSource.refuses`, logged once,
the app's drawn) one that is not as typable as the app's of that id (`ThemePackLayouts.judge`: its `id` and `family`,
and every slot kind, function key of role backspace/enter/lang and digit key the app's has, its includes in place); a
fragment it includes that the pack has not is the app's, a fragment of the pack's is read only by the pack's own
keyboards. Block by block: a bad `k14.xml` falls back alone (`ThemePackLayoutsTest`). `SceneLayouts.of` drops what was
read of other sources once the source's key changes. The keyboard choices (the input method pages, ⋯'s 键盘) name
the theme's layout a keyboard is drawn with (`KeyboardLabels`, `keyboard_themed`, `18 键（主题：17 键）`, the name cut
at 24 characters; not where T9b draws the app's instead, 双拼 on own groups, which it does not log for them); ids stay as the theme format names the boards (`k14/r1c1`,
`ThemeKeyNamesTest.aSceneLayoutsKeysAreItsBoardsNames`). `SceneBoard` makes the canvas view and its scenes
(the U8 fallback, the kept scenes, the marks' settings) for any keyboard; the keyboard gives its state as
`sceneEnv` (its `Input`, texts, marks; `GroupedKeyboard` through `TextBoardState`) and calls
`invalidateScene()` on every change of what the keys show (punctuation, address keys, the language key,
Return, the input method; a keyboard kept while another was shown is told the punctuation as it is now
when `KeyboardWindow.attachLayout` attaches it again, `PunctuationComponent.mapping`). What a key does: a keyboard moved wires each scene key from its node (U4 B; the keys and the touch that
finds them are `SceneKeys`, a node's key kept while it does the same): ime-core's `SceneBehavior.of`
reads press, long press, repeat, swipe, double tap, bubble, long-press popup, menu, sound and axes from
the layout (the bubble's corner what a swipe types, or, on a letter key with a shortcut and no swipe of its
own, its own face, so the bubble names the shortcut as it is swiped to: 大千's symbols' `AltPreview(c, c)`; a key's
`preview="on|off"` over its kind, which gives letters and typing roles one: 注音九键's tones and `{punct:?}` `{punct:!}`
`{punct:~}` theirs though function keys, its slot none though a letter, as their views; the key's word over its
`<defaults>`, the board's `preview="off"` the last, `SceneKeyDef.onBoard`; what types and bubbles is what offers
its character's preset long press where none is written; a bubble shown as punctuation maps it, as the key's face
and what it types: `ZhuyinKeyboard.onPopupAction`, as `GroupedKeyboard`'s), and `SceneKeyDef` makes the `KeyDef` the old wiring takes (the `action:` table: `sym:`
keysyms by name, `layout:`, `picker:` (alone, the page shown last), `commit:`, `code:U+…`, `keypad:`,
`caps`, `shortcut:`, `reading`, `emoji-search:`, the bare words `lang`, `ime-picker`, `quickphrase`,
`unicode`, `space-long-press`…; `action:<name>` the keyboard's `sceneTable`: 大千's `action:zhuyin` the
code of the symbol the key shows and `action:tone-1`…`tone-5` a tone's (`ZhuyinKeyboard.placeholder`, which
reads 注音九键's too: a group's code by the symbols it shows, its tones, `zhuyin-slot` the separator while typing and
the comma key else; its groups write no `letters=` but `<popup from="group"/>`, their symbols the long press,
`sceneTable.popup`); anything
else logged and doing nothing: `code:` a scalar value (U+10FFFF at most, no surrogate), `layout:` a name,
`emoji-search:` on or off, the reader and `SceneAction.of` the same test; a `send=` that reads as nothing
is no tap, not its group's code). A key that does the same in a scene made anew keeps its `SceneKey`.
A group key writes its letters (`letters="qw"`, 060-render-v2 §15): its tap is its group's code (the
keyboard's `sceneTable.group`, as `wire` sends it, its place added by `pressed`), its long press with no
`popup` written (or `from="group"`) the letters' row the views' was (`sceneTable.groupPopup`). The
app collects a layout's groups (`SceneGroups.of`, row by row, a fragment's where included) into a
`NineKeyLetters` and takes it as the keyboard is made (`GroupedKeyboard.grouping`, `SceneGroups.taken`,
060-render-p3 T9b): the keyboard's own groups (G14, G18) as always, their codes, `wire` and learning
unchanged; on pinyin's 14 and 18 keys (`ownGroups`, KeyboardWindow) a grouping of its own that makes a
keyboard (`NineKeyLetters.custom` over `SceneGroups.written`, engine.md) is typed as such: each key's code
its key's place among the keyboard's groups (`SceneGroups.ids`, by the node's id; `CUSTOM_BLOCK`, sent as
it is, no `wire`; a letter, or the same letters, on two keys each key its own), a leaning tap `Keyboard.tapKey(code, x, y)` with where across and down its scene cell the
finger came down (`KeyGesture.clickAt`, `clickDown`; a click no touch made, the code alone), and the groups
told the engine as the keyboard is shown, not as it is made (`onAttach`, `EngineState.ownKeys`; the bridge's `syncOwnKeys` before
each key sets `NineKeyLetters.inUse` and `EngineSettings.ownKeys`, `configure` and `reload` keep them). Other groups told while
a code is typed on the ones before (`EngineBridge.replaces`: groups told before, now others): the code is committed first
as Return commits it (`HostCore.commitCode`, pinyin's letters as typed), not lost with the session made anew; no reset, so
the next word keeps its 前文. The field's composing text (handwriting's, English's word) is no such code and is left alone
(`KotlinHostOwnKeysTest`).
Anything else is refused, logged with why, and the app's own layout drawn instead (`SceneLayouts.builtIn`):
groups that make no keyboard, the other built-in keyboard's groups, and any grouping of its own on 双拼's
keyboards (`GroupedKeyboardOwnGroupsTest`, `KeyboardCustomTest`). The theme's board stays `k14`/`k18`.
A group key's long press is made as it opens only where its layout wrote none (or `from="group"`); a key
that swipes no corner gets its letters alone (`longPressOf`). `letters` keep their rows (`"pq rs"` 2 × 2, a space a row):
where a tap leans and, P3, where the default label draws; the built-ins write their labels (`QW`, one
word centred), so no label is drawn by the rows yet. Whether a tap leans (按落点加权) is the layout's:
a group of two letters or more without `lean="off"` (`SceneGroups.leaning`, read whichever draws the
keys), the 18 keys writing `lean="off"`; a layout whose groups are not the keyboard's, or none read, leans
as built in (`SceneGroups.leaningOf`: G14's two-letter groups); the engine places taps of G14's codes and a
layout's own codes alone (`Keyboard.placesTaps`), so 双拼's 14 keys (sent as `shuangpin14Key`) and the built-in
18 keys send the code only, as before; the built-in 14 keys' taps are their `TAPS` character, wherever down the key.

The number keyboard (S4b) is one layout, `number.xml`, for all seven pads: `NumberKeyboard` gives its
`NumberPad` as `Input.pad` (`full`, `signed-decimal`…) and the keys beside the digits are chosen by
`when="pad=…"`; one id, `number`, as the theme names the board (P3 styles `number/<key>`), each pad's
own `NumberKeyboard` drawing it. Its digits and `+ - * / , = .` are `sym:KP_…`: the `action:` table sends
every `KP_` keysym with NumLock (`NumLockState`), as `NumPadKey` did (`keypad:`, a typing keyboard's
corner digit, stays Virtual). The board's `preview="off" popups="off"` are applied to each key
(`SceneKeyDef.onBoard`): no bubble, no long press's keyboard, but a menu written on a key pops up, so
Return offers Emoji as on every other keyboard (the views swallowed it: rules C 疑问 8, fixed on the
canvas alone, `NumberSceneGesturesTest`). A place a pad has no key for is a `<gap look>`: drawn
`:disabled`, faded to `Popups.DISABLED_ALPHA` op by op as the blank key's view was as a whole, no touch,
no node. The space is still `button_mini_space` to TalkBack (`KeyNames.BOARD_RESOURCES`, read by board).

Any key in the `:disabled` state is faded the same way by the engine (`SceneBuilder.node`: every op of the face,
words and marks at `Popups.DISABLED_ALPHA`), and `SceneTouch.Pointer` takes no press on it (no tap, no long press,
no repeat; TalkBack's double tap does nothing either): 展开候选's page key that cannot turn is the one so far.

The small boards and cells (S6). Three more families: `emoji-search` (表情搜索's letters, its 返回 key
`emoji-search:off` labelled `{back}`, ⌫ `sym:BackSpace` repeating), `expanded-side` (展开候选's column: `page-up`
and `page-down`, `action:page-up|page-down`, then ⌫ and Return with its Emoji menu) and `number-row` (the
password field's bar row, ten digits `sym:0`…`sym:9`, `face="none" margin="false"`); each the keyboard's
`drawKeysWithScene(<id>)` over its `BaseKeyboard`. A keyboard's `sceneTable` is a `SceneKeyDef.Table`: its
`action:` names, and `repeat(press, node)`, a key's repeat as the keyboard has it (表情搜索's ⌫ tells a hold
from a tap: a hold clears the query and stops short of the field). `BaseKeyboard.sceneDrawsBase` (true by
default) says whether the canvas draws the board's base under the keys (`KeyboardCanvasView.drawsBase`): the
three small boards say no and sit on what their panel draws. A picker's bottom row is a layout of
its own (`PickerLayout`: symbols.xml in a panel, emoji.xml with 搜索, emoticon.xml without), its keys the scene's.
`ExpandedCandidateLayout.Keyboard.showPages(up, down)` marks a page key `:disabled` where the list cannot turn
(`BaseExpandedCandidateWindow`). `NumberRow`'s collapse swipe (`checkGesture`) is still its own, seen before
the scene's touch; a swipe told as one cancels the keys' press.

`SceneKeyCell` (`input/keyboard/canvas`) is one key drawn by the engine on its own: a scene of a one-key layout
the view's size, in the keyboard's theme, its words `attrs`, its `states` and `marks`, no board under it.
It takes the size its `attrs` give, never the keyboard's 紧凑 or floating text scale. The symbol and emoji grids'
cells are these (`PickerGridAdapter`: label, kind letter, the grid's text size, a face and
margin where the grid is bordered, its a11y and `contentDescription` the item, set again on every bind), and a
popup over one is over its face (`SceneKeyCell.bounds`: the face across, rounded as
`KeyboardCanvasView.popupBounds`). 按键字符和快捷键's caps (`KeyCaps`) are too: their marks from
`SceneMarks.settings` and the cap's `Shortcut`; a cap whose long press is off fades its words alone
(`SceneKeyCell.labelAlpha`, 0.4, never `:disabled`), and the edited one is outlined along its face
(`SceneKeyCell.outline`, an `Op.Stroke` of the key's shape) in the keyboard theme's accent, so the caps are the
keyboard theme's whatever the settings' own. A label the engine cannot fit across its key's room is shrunk
alone, and one whose ink stands out of it moved back in (060-render-rules B §8a): a long 颜文字 is never cut.

What the scene is given, so it draws what the views would: qwerty.xml is
`lib/keyboard-scene/src/commonMain/layouts/qwerty.xml`, the one file the app copies and the engine's
tests read. A letter's corner is `{alt:x}`, the app's `Texts.alts` (`SceneLetters.alts` of
`TextKeyboard.cornerOf`: the user's own long press first, else Shift's swipe override, else the
swipe; through `transformPunctuation`; the letter itself under a table's labels); a table's roots or
names replace the letter through `when="roots"` and `{root:x}` (`SceneLetters.roots` of
`TableKeyboard.label`), in two lines at 0.55 above three roots, as the views drew them. A table's 确定
is `{confirm}`, the app's `key_confirm` (OK in English), as the view's text.

The scene is made only as the view draws (`onDraw`, timed at debug level): a touch, a press shown
and TalkBack find keys in the scene drawn last (made on the spot only before the first frame), and a
touch finds only a key whose view is VISIBLE (one hidden since that scene finds nothing). Each key's
node is the size of what it draws (`CanvasFrame.extent`: its cell, its shadow's blur, a stroke), and
which faces a frame draws at what alpha is `CanvasFrame.faces`. A key showing the theme's picture
(`Op.Picture`) is recorded where the view is over the picture's base; a pre-draw listener records it
again when that moves (one-handed to the other side, the floating window dragged). The painter's
`missed` (record again next frame) is set only while an image or the picture is still being decoded
(`PictureHost.decoding`), not for one that never will be (no host, a file that does not read).

No scene of the theme (one the engine refuses or cannot build): the built-in theme of its lightness
(`pixel#light`/`pixel#dark`, 060-render-p2 U8) is drawn instead, the whole of it: its look,
its fonts and measurer, its painter (`KeyboardCanvasView.Drawn`), none of the broken theme's; the
warning logged once a theme's look (`SceneBoard.fellBack`). That failing too (a layout that does not
read, the builder's problems), nothing is drawn and an error logged, never anything else; a new scene is tried as anything changes (`invalidateScene`). `SceneLayouts` keeps a layout
that did not read as such, not read again a frame; `SceneThemes` a look that made no theme (read and
logged once, not again each scene asked).

The row's indent before A and after L is the end key's touch area (the user's P0 decision in
060-render-v2 §3): a finger down there is A's, its swipes and long press A's, though it is outside A's
view (`theIndentBeforeAIsAs`). Before, with `expand_keypress_area` off, it was nothing.

How the canvas compared with the old key views pixel for pixel before S8a deleted them, and the performance gates and measurements of the drawing (060-render-perf §4, 2026-11-07), are in `dev/design/060-render-perf.md`; the old views are gone, so neither is compared again. Performance is measured again, and then cut, after P2 (060-render-perf).

In systrace on the benchmark build (`atrace -a io.github.sraw.youmo.bench view`) a scene made anew is
0.79 ms at the median (`cascade.of` 0.06, `content` 0.14, `paint` 0.15, `measure text` 0.06), one
asked again 0.26; a debug build's was 4.9 ms, 4.2 of it the cascade, before the cascade kept its
matches. Four of 401 took about 21 ms, each in a different phase (a pause of the process, not a
phase's work). Making the scene a key at a time or off the main thread is not needed for its cost.

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
the keys beside the digits blank (`BlankKey`, a `KeyDef`: no press, nothing for TalkBack; a `<gap look>` of number.xml, drawn faded as `:disabled`, no node) where the field takes none, a plain number
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
finger came down: `KeyGesture.downAt` (x / the key's cell width, `KeyGesture.Key.keyWidth`) at ACTION_DOWN,
made `clickAt` at ACTION_UP before `resetState` (NaN if the touch left the key or swiped past the
threshold; a few pixels' MOVE keep it), cleared once the click returns and at DOWN and CANCEL;
`BaseKeyboard.pressed(clickAt, action)`, which `GroupedKeyboard` overrides, sends `Keyboard.tapKey`
for it where k14.xml has the group lean (its `lean=`, above). TalkBack's click, a swipe's own Tap, L and M send the code alone; a swipe its corner (1–0, `~ ! @ ?`), a
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
- **跟随主题 of the keyboard's size** (060-render-p3 T5). 键盘高度, 两侧边距 and 底部边距 have 跟随主题 first
  (`levels(..., follow =)`: a `PBool` of `AppPrefs.Keyboard`, `keyboard_*_follow_theme`, default true,
  `hidden(setByLevels = true)`): picked, it is set and the ints are removed (their defaults); a level
  picked unsets it. `KeyboardArea.sizes()` (ime-core `KeyboardSizes`) is the theme's size
  (`Theme.keyboardSizes`, `keyboard { height; side-padding; bottom-padding }`, of the theme in use as
  `SceneThemes` reads it; a test's `KeyboardArea.themeSizes`) where a setting follows and the theme sets
  one, else the ints; 紧凑 and 贴底 are read from it too (a theme's bottom padding followed is no
  贴底), `KeyboardHeightCap` after it as before, floating still without paddings. A theme 3.0 and the
  built-in ones set no size. `ThemePrefs.migrateSizeFollow` (from `ThemeManager.prefs`) once moves an
  upgrade's sizes: each at its defaults (贴底's flush is not) follows, one set does not; the mark
  `keyboard_sizes_follow_migrated` is a key of no preference, so the hidden reset keeps it and an older
  version's backup brought back, which has none, is moved again. The mark is copied to device-encrypted
  storage with the theme preferences, and a stored `keyboard_*_follow_theme` counts as done too (only
  0.6.0 writes one), so a start before the first unlock never makes a default level picked follow; a
  number that is no int is taken as set. The three flags are listened to as the ints are (the service's
  紧凑 check, `ThemeFragment`'s `SIZE_KEYS`, the preview's), since a level whose ints are stored already
  changes only its flag; the preview measures itself again on a theme switch. `TextKeyboard.widthDp`
  (floating's width, the split placement) reads the side padding from `KeyboardArea.sizes()` too.
  `ThemePrefsMigrationTest`, `KeyboardAreaTest`, `ThemeSizesUatTest`, ime-core `KeyboardSizesTest`.
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
`text`. It is keyboard-scene's `ThemeLook` (`scene/look/`, with `LookDefaults` and `LookTheme`: the web converts 3.0 with the same code, and reads its json with `ThemeLookReader`, held to this one by `ThemeLookReaderParityTest`) one to one, through `ThemeLookJson.kt` (the
only place the two meet): no field has a default, a field missing or not known is an error, and a
value out of range is refused as the `ThemeLook` is made. `keys.overrides` maps a key's name
(`KeyNames`: `<board>/<key>`, `*/<role>` for a function key on every board) to its own fill,
pressed face and label; a name no board here has (a later build's board) is read, kept and drawn
by none. A key's style is its board's own name, then `*/` (roles only), then its kind's; each field
falls through by itself (`KeyStyleResolver`). Every key a theme can reach has `KeyDef.name`, its
keyboard the board (`BaseKeyboard.themeBoard`, the scene key's id); `ThemeKeyNamesTest` holds the
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
(`ThemeLookMigration.MONET_KEY`), and Monet's look (`Theme.Builtin.followsSystem`) is the defaults with them
(`ThemeLookMigration.monetLook`). The page's 按键样式 has four settings of its own, new keys (`key_border_choice`, `key_h_spacing_choice`, `key_v_spacing_choice`, `key_radius_choice`; not 0.5.0's, which the migration reads): each 跟随主题 (the default) or a value over the theme's `blocks`, the across or the up-and-down half of `gap` (`h`/`hLand`, `v`/`vLand`), `radiusDp` (ime-core's `KeyStyleChoice`; a block theme made flat gets a transparent bar's colour, as `LegacyLook.applyTo`); then a line on what following means (标点位置 moved to 按键). 0.5.2 had one spacing, `key_spacing_choice`: `ThemePrefs.migrateKeySpacing` (from `ThemeManager.prefs`, at every start, so a restored backup's too) moves it into both, each the same level by name, a stored one kept, the old key dropped (`KeySpacingMigrationTest`). At the keyboard height 紧凑 the room above and below a key is `Gap.vCompact`/`vLandCompact` (keyboard-scene, `Gap.COMPACT_V_PERCENT` of it), which `LookTheme` writes as `@compact key` and `@compact @landscape key` insets, so it holds for the theme's gap and a chosen level alike, and the keys get the room (the board's height is the level's); the built-in theme files carry the two lines (`BuiltinThemeEquivalenceTest`). A 4.0 pack is drawn as its file says (`SceneThemes.of`): neither the key style settings' gap and radius nor this rule reach it; its author writes `@compact key`.

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

The app's own panels take what the theme in use sets of them (060-render-p3 T7b; §16: their native
views know the colours and a few properties), read through `ScenePanels` (input/keyboard/canvas) from the
engine's `PanelLooks`, whose defaults are the look the app worked out itself before, so a theme 3.0's
looks as it did; the scenes are the window's (`SceneWindow.scenes`: not `@floating` nor a placement's), a fill a
colour alone (a picture's warned of and ignored), and a look that made no theme has that
look of before worked out of its colours. `clipboard` (fill, radius, color): an entry's card and a split
entry's pieces (`ClipboardEntryUi`, `ClipboardChunksUi`), not set the `clipboardEntry` colour, the keys'
text, half the keys' radius (as 按键圆角 sets it, whatever `@flat`: the P2 rule, once
`ThemeColors.entryRadius`). `picker` (fill, under the grid) and its part `categories` (fill, color; the one
shown `picker:selected categories`): the symbols' column (`PickerSideUi`: the bar's colour, the one shown
the keyboard's, the text the keys' at 60% and, shown, opaque) and the emoji's tabs (`PickerTabsUi`: on the bar, at 50%).
`editing` (fill, radius): its buttons (`TextEditingButton`), not set the keys' colours, blocks or the flat
grid of lines as the keys (a radius then nothing), the corners twice the keys' dp as as many px (its old
setting's look), pills where the keys are; a fill set is a block whatever the keys. `voice` (fill, blur 0–25 dp) and its part `mic` (fill, icon-color): the mask's
colour (not set, the keyboard's at `PanelLooks.MASK_ALPHA`), its frosted glass (`VoiceGlass.Blur.of`: three
box blurs of the radius, as the picture's, on the keys taken 2 to 8 times smaller, under 4 px a little more; 0, none, the keys plain
under the colour; not set, the glass of before), the microphone's bubble and bars (the accent colour at
`MIC_ALPHA`, and whole). The glass is one picture of the keys taken once on every API: RenderEffect is API 31's
and blurs a view's own drawing, not what is under it. The handwriting pad's `ink-color`, `ink-width` (1–8 dp)
and `grid` / `grid-color` are on its slot's style (`handwriting/pad`, as its fill): the engine puts the
strokes' `Ink` on the slot (`HandwritingKeyboard` gives it to `HandwritingPadView`; not set, the keys' text,
3.5 dp) and a 田字格 of 1 dp lines over the pad's face in its ops (`engine/Pads`; not set, none).

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

The app does not edit themes (user, 2026-11-03). 新增主题 is a menu (`ThemeAddMenu`, 060-render-p3 M5,
replacing 0.5.x's import picker opened directly): 导入主题文件, the import picker; 在网页上制作 (a second
line 做好后回到这里导入), the web theme maker (`web/theme-maker`, `Const.themeMakerUrl`,
https://sraw.github.io/youmo-ime/theme-maker/ as `pages.yml` publishes it) opened in a browser by
`openUrl`: the app has no network permission, so no WebView. The user docs link the page too. A theme file is `<name>.theme`, a zip inside (user, 2026-11-04, so it is not taken for any zip): exported under that name as `application/octet-stream` (a provider may add `.zip` for `application/zip`), and the import picker shows every file (`*/*`, `.theme` has no MIME type) and takes only a display name ending `.theme` (ime-core's `ThemeFileName`; `.zip`, an earlier 0.5.1's or the page's, or no name, is refused with `exception_theme_filename`, which says to rename it). An export is always a theme 4.0 pack (below). The theme list
applies, imports, and on a long press exports or deletes one of the user's (Monet exports
`Theme.Builtin.toCustom()`: its colours now and the key style it keeps, fixed, named as 0.5 named it,
`MonetLight#<primary>`). A built-in theme of two versions is one card (`ThemeCards`, the theme page's
list and the light and dark slots' dialogs alike): a small 浅/深 on its thumbnail shows the other version
and picks nothing (its touch target 48 dp, the chip in its corner; read out as the version shown and what a
tap does, `theme_version_*_switch`), a tap on the card picks the one shown; the version a slot or the theme
in use is shows, and its mark only while it is shown (dark over light over in use; a slot's dialog opens on
the slot's version and keeps the theme picked, not the card: `SimpleThemeListAdapter.selectedTheme`). The fcitx edit page
(`CustomThemeActivity`, its cropper) went with the old 选择图片… and 复制内置主题…; themes made by them
before are listed, used, exported and deleted like any other. Thumbnails (`ThemeThumbnailUi`) draw four rows of the
theme's keys in little (`ThemeThumbnailKeys`, colours only: no key image decoded) over its base
(its colour, or its picture: `ThemeAssets.thumbnail`).

**Built-in themes** (060-render-p3 T3) are theme 4.0 files, `lib/keyboard-scene/src/commonMain/themes/`
(material, pixel, monet with `variants="light dark"`; amoled-black dark only; nord, deep-blue, monokai
are kept there for the web's starting points and the tests' packs, not listed by the app since 0.6.0),
which the web reads as well; the build copies them as raw resources `scene_theme_<name>` (`-` → `_`)
beside the layouts. **Built-in packs** (060-builtin-themes) are theme 4.0 packs with pictures, sounds and
effects (简蓝 … 多巴胺), raw resources `builtin_theme_<id>.theme`: `BuiltinPacks` unpacks them once a
version into the device-protected storage's `noBackupFilesDir/builtin-themes/v4/<name>/`
(`createDeviceProtectedStorageContext()`: so the packs are there before the first unlock too) and lists them as `Theme.Pack(builtin = true)`
through the same `ThemePackFiles.list` as the user's (`ThemeManager.BuiltinPackThemes`, listed last); never
deleted, exported as a pack is; one of the user's of the same name (imported before) is listed instead, an
import of such a name refused. The names the settings had for the dropped ones (`BuiltinThemeFiles.RETIRED`,
and `LEGACY`'s 0.5 names of them) move to the closest built-in pack (`ThemePrefs.migrateThemeRefs`) and stay
reserved (`ThemeManager.clashesBuiltin`). Picked in the list while following the system (`ThemeSlots`), a theme
of two versions sets each slot its version, one of one version both slots. `BuiltinThemeFiles` reads them (`ThemeManager.BuiltinThemes`, Monet's first): each
version a `Theme.Builtin` of that version's 24 colours (ThemeReader.REQUIRED_COLORS; the native code reads
those as before) drawn as `LookDefaults` with them, which is what the files' styles say
(keyboard-scene's `BuiltinThemeEquivalenceTest`: every version × board × form × popup, scene text alike;
the colours were held to the deleted `ThemePreset`/`Theme.Monet` by `BuiltinThemesPresetTest`, commit
bb317aba3). Monet's colours are `system(role, fallback)`: `ThemeMonet.systemColors` gives the roles from
Android 14 (the light bar `surface-container-highest`, the dark `surface-container-high`), on 12 and 13
the palette tones 0.5 approximated them with (every role, the two 0.5 had none for, the light high and
the dark highest, the tones nearest Material's between their neighbours), before 12 none (the fallbacks: 0.5's static MD3 colours);
re-read on a configuration change (`onSystemPlatteChange`) and when the key style is settled. Transparent's
two are no built-in theme: test data, `commonTest/data/transparent.xml`.

The settings name a built-in theme by **reference**: its file's name and version, `pixel#dark`, or its
name alone where it has one (`deep-blue`; `deep-blue#dark` finds it too). `ThemeManager.getTheme` finds
the user's theme of that name first, then the built-in, then the built-in of that 0.5 name (a slot not
moved yet). `ThemeManager.listCustomThemes` lists the user's themes and sets the slots right with them,
at the start and at every `refreshThemes` (themes not settled at the start may be since, A4; a refresh
that changed a slot syncs the device-encrypted copy). A json whose file name is not its theme's name (put
there by hand) is renamed to `<name>.json` as it is listed (not listed where that is another's json), as
save, delete and the moves write that file. A theme of the user's named as a reference is now
(`monokai`, `pixel#dark`: 0.5's import refused only 0.5's names) would hide the built-in one, so before
the slots are moved `ThemeFilesManager.moveClashing` saves it again under a UUID of its name (its files
as they are, its json's age kept), `ThemePrefs.renameThemeRefs` points every slot naming it at the copy
(committed), and only then its old json goes: a kill anywhere leaves each slot on a json there is, the
next start finishes onto the same UUID (where that name holds another theme already, the first of
`<name>#1`, `<name>#2`… free or holding this copy). Not while the themes are not settled or before the
first unlock (none listed). Then `ThemePrefs.migrateThemeRefs` moves a slot (`normal_mode_theme`,
`light_mode_theme`, `dark_mode_theme`) naming one by its 0.5 name (`PixelDark`, `MonetLight`,
`BuiltinThemeFiles.LEGACY`) to its reference, unless a theme of the user's has that name or may have it
(`ThemeFilesManager.mayHoldThemes`, the directory read once: its json there unread, or the directory not
read; such a slot is read as the built-in theme meanwhile); a name of no theme stays (the default). No
mark: no old name is written any more and an import of one (or of a reference) is refused
(`exception_theme_name_clash`), so it runs at every start and a backup of an older version, or the
device-encrypted copy an older version wrote, is moved as it is read; `syncToDeviceEncryptedStorage`
copies the references as they are now. Before the first unlock nothing is moved, so a user's theme name
there that is a reference now (`monokai`, in a copy synced before the theme was moved) reads as the
built-in theme until the unlock.

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
next start; `ThemeManager.saveTheme` tells the user (`theme_not_saved`, no path) and returns false. Export (a 3.0 theme's, now as 4.0: below)
takes only files of the theme dir (a planted json's other paths are refused before anything is written); 3.0's own zip
(`exportJson`, each named file once, then the json) stays for the web theme maker's fixtures only; delete removes
the json and every named file that is in the theme dir. Listing skips a theme with a file missing, and
one that does not read (told once, `user_data_unreadable`), leaving its file as it is.

### Theme 4.0 packs (060-render-p3 T8a)

What a theme author may write (elements, selectors, properties, limits) is [docs/theme-format.md](../docs/theme-format.md); keep it true with any change to the reader or the import's limits.

A pack is `files/host-theme/v4/<name>/`: its `theme.xml` (keyboard-scene's theme format 4, `ThemeReader`), the
files it names (picture and cuts, `image()` fills, fonts, icons, the `<meta preview>`) and `layouts/*.xml`
(`ThemePackFiles`). Each version of it (`variants`) is a `Theme.Pack` named as a built-in's are, `<name>#light`;
one of no variants is `<name>`. Its keys, bar and popups are the engine's drawing of its own file (`Pack.drawn`:
the file as read, its files' names made paths in its folder; `SceneThemes.of`, kept by the pack and the system
colours, as `system()` colours are re-read on `onSystemPlatteChange`), not a look written again; the views drawn
from a look (the theme page's preview, the thumbnail, the picture under the keys, key text font) read
`Pack.look`: `LookDefaults` with its 24 colours, its picture, and the fonts its plain `key` and `candidate`
styles name. Icons are checked and kept but not drawn yet (the engine draws its built-in icons by name).
The list is packs, then 3.0 themes, then built-ins; a pack's versions are one card (`ThemeCards`);
`getTheme` finds a 3.0 theme of the name first, then a pack's version, then a built-in, then the built-in of
that 0.5 name (a pack is never named as either: refused at import, skipped at listing). A `Theme.Pack` keeps
no text of its file (its `stamp` is the file's time, so one imported again is another theme): `scene` reads
`theme.xml` when first drawn, and one that no longer reads is drawn as the built-in Pixel theme
(`ThemePackFiles.scene`, `user_data_unreadable` told once). The bar and panels are drawn from the pack's own
file too (`SceneBar`, `ScenePanels`: `SceneThemes.of(theme, look)`). `PackDrawGuard` writes
`noBackupFilesDir/theme-pack-drawing` (the pack's name; written, not synced: a crash keeps it) as a
`KeyboardCanvasView` is about to draw a pack's first frame of a process (`onDraw`, keyed by name and file
time: one imported again is guarded again) and deletes it once that frame is drawn: at once on a software
canvas, on a hardware one when a frame is committed (`registerFrameCommitCallback`, its glyphs and pictures
drawn on the RenderThread; `ViewRootImpl.performDraw` takes the callbacks before it draws, so one registered in
`onDraw` goes with the next frame, which `postInvalidateOnAnimation` asks for while the mark is pending, at most
`PackDrawGuard.MAX_ASKED` (3) times a pack a process: a device that calls back no commit draws no more, the mark
kept till a later frame's), only for the pack marked. A pending mark is also cleared as a keyboard canvas of the
input method (one not in an activity, whichever instance: a theme change builds the views anew) draws anything
other than that pack (`PackDrawGuard.shows`): that pack is no longer shown; a canvas in an activity (a settings
preview, a theme card) clears nothing. A window hidden before the frame after the first, the keyboard then not
drawn again, leaves the mark pending till the next show's first commit; a process ended in that gap resets the
pack falsely. A pack built and
not drawn (the keyboard made again while hidden, a settings page's sizes) marks nothing, so a process ended
otherwise resets nothing. Found at the first listing of the packs of a process (`ThemeManager.init`, or the
first `refreshThemes` once the files are settled: kept till then), the slots
naming that pack's versions are reset to their defaults (`ThemePrefs.dropThemeRefs`) and
`theme_pack_draw_failed` toasted; the pack is kept. `onSystemPlatteChange` reads the packs again (and draws
a pack in use anew) only when `ThemeMonet.systemColors` differ from the ones they took.

Import (`ThemeFilesManager.openTheme`): a zip with a top-level `theme.xml` is a pack, else it is 3.0
(`importTheme`, as above). A pack is unpacked under `cacheDir/theme-import/<uuid>` (swept at the process's
first open): its top-level files and `layouts/` only (`ZipStream.extract(keep = ...)`), the rest counted and
metered as above; a folder but `layouts/` or `__MACOSX/`, or one in `layouts/`, is refused, and a zip whose
`theme.xml` is only in a folder says it must be at the top (`exception_theme_xml_top`). It is checked whole before the user is
asked (`ThemePackFiles.check`; `ThemeAssetLimits`): `theme.xml` read at this app's version (refused: does not
read, the first five problems with their lines; `min-app` newer, said as such (`ThemePackFiles.minAppVersion`: a pre-release `0.6.0-beta1` compared as 0.6.0, a development build's `-<n>-g<hash>` or `-untagged` not checked); Shift, Backspace,
Space or Enter left blank, its words and icon hidden and its face no image, as the engine resolves the key
(`BlankKeys`, `SceneBuilder.blanks`) on the app's boards and the pack's own, by role, in every union of at most three
key styles' scene sets and state sets (exact: the three deciding styles win there too; sets the app is never in at
once skipped; past 512 scene sets refused, `exception_theme_too_many_scenes`), said as `board/key`;
`ThemePackCheck.hidesRequired`, the theme maker's `blankKeys` too; a pack that passes keeps a `.checked` stamp,
a hash of theme.xml, its layouts, `BlankKeys.RULE` and the build, so a listing checks only a pack without one), at most 1 MB, `LookTheme.MAX_STYLES` (4100: a 3.0
theme of 400 single keys written as 4.0) styles, 4 fonts, 200 icons (the reader's); its name plain, not
`.`-led, not ending `#light`/`#dark`, no built-in's file or reference, no 0.5 name
(`ThemePackFiles.builtinClash`). A 3.0 theme of the user's named by one of its references is replaced by it
(`Opened.Pack.customs`; their names recorded in `noBackupFilesDir/theme-pack-replaces` before the pack is placed,
the record put back as it was where the place fails; finished on the main thread once the pack is listed, `ThemeManager.finishReplacements`
at `packPlaced`, or at the next start's listing after a kill, for the recorded names only: a 3.0 theme beside a
pack of its name no import recorded, as a backup restored over the pack leaves, is kept and found by its name;
a recorded name no pack lists has its record dropped at a start's listing, not later, as an import may be
placing it then; the slots written with the change fired once, as the list has the pack, and the copy for before
the first unlock synced only where a slot named the theme: the slots keep the name, which then finds the
pack; a theme named `mine` replaced by a pack `mine` of two versions, the slots naming it name its version of
the same lightness, `mine#dark`, and the copy for before the first unlock is synced; then its json and the files
no other theme names are deleted, not where the slots were not written: tried again at the next listing, the
3.0 theme found by its name till then), while a 3.0 import named as a built-in theme, a 0.5 name the settings move to one, or a pack's reference is saved as `<name>-N` (`importJson`; `Imported.renamedFrom`, told by a toast, 060-pending-decisions). A folder of its name there but not listed is refused only where its
`theme.xml` is a later build's (`ThemeReader.laterBuilds`: a format past 4.x, or a newer `min-app`;
`exception_theme_newer_kept`; a `theme.xml` past 1 MB is not read for it, so not); any other (one that no longer reads, misses a file, hides required text, past
the packs listed) is replaced, as it could not be deleted otherwise. A new pack past `ThemeAssetLimits.MAX_PACKS` (100) too
(`exception_theme_packs`). Every file it names inside its folder (never `theme.xml`) and present; pictures
(the source photo too) and key images as a 3.0 theme's, a preview `.png`/`.webp` of 512 KB and 2048 px a side, fonts that load, icons of 64 KB in
`SvgSubset` (paths and plain fills only, numbers within ±1e6 and viewBox sides from 1e-3; no script, link, style or entity: `Xml.parse` takes no DTD); at most
16 layouts, `.xml`, 64 KB each. Warnings (not refusals): what the reader skips (a later version's names), and
key text under 3:1 on its keys in a version (`ThemePackCheck`'s contrast). The confirmation
(`ThemePackDialogs`) shows the preview, author, version, license, the format, that it replaces a pack or a
3.0 theme of the name, the keyboards it replaces as they would be drawn (`ThemePackLayouts.report`: read by the
engine's reader and judged as above) with the count of their `commit:` actions (from the parsed layouts and the
pack's fragments they include, each once: keys', variants', defaults', popup items' `send`, menu items' `do`; a
character reference counts as what it reads as), the files never used (not an app id, unreadable, refused, a fragment
no keyboard used includes), and the warnings; 导入 places it, 取消 (or back) deletes what was unpacked.

Skins of other input methods (`ThemeFilesManager.openSkin`, picked by `ThemeFileName.isSkinFile`: `.bds`, `.bdi`,
`.ssf`, `.flex`, `.yaml`/`.yml`, `.zip`): read whole into memory by `SkinZip.readSkin` (a lone YAML as itself, 1 MB at
most; a ZIP holding one `.flex` and no `extension.json` as that `.flex`; a `.zip` that is no skin refused with
`exception_theme_filename`, as a theme exported under the old name most likely is) and unpacked there by `SkinZip` (ime-core; central directory, else a local-header
scan; `SkinLimits`: 64 MB file, 5000 entries, 32 MB an entry and 128 MB in all as they inflate, a zipped `.flex`'s
counted with the ZIP's own, ratio 200 past 1 MB, no encrypted entries, a name with `..`, a leading `/` or a drive
refused), converted by keyboard-scene's `SkinConverter` (`AndroidSkinCodec` decodes and writes PNG; an image past
12 M pixels not decoded; `SkinFiles` keeps decoded images up to 12 M pixels in all, then lets them go; a keyboard
picture shrunk before the bar is put on it, so a board is never past 4096 a side; an `OutOfMemoryError` or
`StackOverflowError` all the same a refusal, as a backstop), then its
`theme.xml` and files written under the converter's own names into the same `theme-import/<uuid>` and checked and
offered by the same `pack()` as a pack's; `Opened.Pack.notes` (`SkinNote`) are listed in the confirmation as not
carried over. `SkinError.Reason` maps to `exception_skin_*`. The theme keeps the skin's name and author (no suffix:
a name clash replaces, as a pack's does). The theme maker's web entry for skins is not done yet.

Placing (`ThemePackFiles.place`) copies it into `v4/.saving/new-<uuid>/`, each file synced, renames an old
pack of the name aside into `v4/.saving/old-<uuid>/<name>`, renames the new one into place, syncs `v4/`, and
only then deletes the old; a failure deletes what it wrote and puts the old back. A kill between the renames
leaves the old aside: the first listing of a process puts each `old-*/<name>` back where no `v4/<name>` took
its place, then clears `v4/.saving`. A listing reads the newest `MAX_PACKS` folders and skips (logged, told
once) a pack an import would refuse: one that does not read, whose name is not its folder's, a bad or a
built-in's name, hiding the required keys' labels, or missing a file, leaving it as it is. Delete (either
version) removes the folder. Export writes `theme.xml`, its files by name, then `layouts/`; a 3.0 theme
exports as `LookTheme.theme` of its look through `ThemeWriter` (`ThemePackFiles.of`, its files named by
`LookTheme.fileNames`, its candidates' font and weight as T6a's `candidate` style), Monet's as its
colours now. Backups take `v4/` with the themes; a restore replaces each pack the backup has whole (the phone's folder of
its name deleted first, `UserDataManager.dropRestoredPacks`), the phone's others kept; an older build restoring
one lists json only. A 3.0 theme whose source photo is past `MAX_PICTURE_SIDE` exports with its portrait cut
as the source (`ThemeFilesManager.exportedLook`), so the pack imports.

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
`learn.log`, its word lists (`words.added`, `words.blocked`, and English's blocked words
`words.en.blocked`, `EnglishBlocks.FILE`, a word a line, no reading), its old `*.user` logs and its backups stay; what the backup has of
learning (the zip's learning backup, or its record, or an older build's logs to convert, with its
lists, `words.en.blocked` with them: `RestoreStash.read` reads it into the blocked words, no reading to
them, as a backup's bytes carry them) is moved into a directory of its own under `RestoreStash.DIR`, and the engine merges it as it
next starts (beside any stash not merged yet). An emoji blocked in its word's reading (👍🏻 hao) is a
line of `words.blocked` like any, its syllables not one per code point (`WordLists.blockedEntry`). A `learn.log.unreadable*` (a later build's log kept
aside) stays. An export with the learning as a backup leaves out the files it is made of and the old
logs (`*.user`, `*.user.told`) and `learn.table-logs-read`, the phone's own as the record is; with
that mark (the old logs read into the record) an export without the learning leaves the old logs out
too, and the mark goes with the record into the stash, whose logs are then not read again. A zip of this app from before 0.3.1 (its metadata's `versionCode`),
or with old logs but neither the record nor a backup, is refused before anything is copied
(`exception_user_data_too_old`); one from before the renaming is not, having none of the engine's
learning.

## Permissions and privacy

The app asks for `VIBRATE` and `RECORD_AUDIO` (the latter at runtime, on first voice use only); nothing else. The build fails
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

## Voice and the voice pack

0.6.0 has one APK (the `speech` flavors are gone, dev/design/060-asset-packs.md): voice's code is in
main, its models in the voice pack. The keyboard reaches voice through `VoiceFeature` (`credits`,
`hold` for the space, `tap` for the bar's microphone and the `Voice` key shortcut). Each first asks
`PackStore.status(VOICE)`: without a usable pack a notice says 语音输入需要导入语音包 (or which version)
and a tap opens the 资源包 page; then the permission. An input method cannot ask for
a permission itself, so `VoicePermissionActivity` asks and closes; it is asked on first use only.
The microphone is on the bar for everyone (no longer per build), and is what asks for the pack. The
space's hold is voice by default, but only with the voice pack ready (`SpaceLongPressBehavior.talks`'s
`voiceReady`, and `spaceLongPressTakesTouch`): without it holding the space does nothing, as 0.5.x's
text build: no notice, no microphone badge, a slow drag's cursor not taken (user's decision,
2026-11-08: a hold is no place to advertise a pack). The keyboard is made anew when the voice pack's
readiness changes, for the badge. A value the user set is left as it is. Voice decisions (number
normalisation, reranking hypotheses with the pinyin model, blocked words, hotwords, the hold's and
the tap's presses, what is typed as it is said) are in ime-core's `input/voice/` and tested there.

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
- The record's buffer is 2 s, the least the HAL takes as a last try.
- Debug line the UAT parses: `voice ready: fromStartMs=` (start() to the first sample not zero).

### Voice: streaming, the segments and the polish

- One `OnlineRecognizer` (sherpa-onnx's streaming zipformer transducer, chunk 1920 ms, modified beam search 16,
  n-best) decodes as the audio comes; no VAD cuts the segments (silero only says whether a short Latin segment was speech, **Quiet** below). Its endpoint rules (2.4 s of silence before
  anything, 1.2 s after something (set in `VoiceEngine.config()`; the Kotlin API's default is 1.4 s), 20 s at most) close a segment: `VoiceEngine.choose` picks its text among the
  n-best (`VoiceChoice`, the pinyin engine's model), and the stream is reset. The user's blocked words are in the
  search graph (`createStream(hotwords, blocked)`), not searched again. After the microphone closes, 2.92 s of
  zeros pad the tail (`TAIL_PADDING`: a chunk and its right context, else the last words wait for a chunk that
  never comes) and the input is finished. Why endpoints and not the VAD: measured on six sets
  (`voice-eval/seg_app.py`, `ENDPOINTS=1`, 060-voice-tagger.md), endpoint segments are as good after the polish
  (written CER within ±0.5 points either way) and the segments do not wait on the VAD.
- `VoiceLive` (ime-core) keeps the closed segments and the one being said: joined with nothing where neither has a
  mark (a mark at a pause is the tagger's, its after and num heads: the owner's decision 2026-11-10; the tagger's
  training data, voice-eval `fusion_exp.py`'s `joined`, still has a 「，」 there, retraining is a later task), a
  space between English words or numbers in digits, and a segment that starts with a mark (the recognizer's context
  outliving the reset) ends the one before with it, not two marks in a row. 语音整理 is always on (no switch; `voice_polish` retired): its
  `VoicePolishStream` (K = 16, window 64) runs the polish tagger (`VoicePolisher`: ELECTRA-small int8 through
  `OrtModel.runLongs`, `voice/polish-tagger.ort` + `.json`; outputs op, after and num) on the text as it grows,
  freezing each character's three labels once 16 characters follow. Numbers are the tagger's alone: adjacent
  characters of a num class not none (deleted ones skipped) are one run, written as its majority class by
  `ChineseNumbers.written` (value, digits, date, time, money, percent, model, ratio; conversion only, no rules on
  whether; a time's 半, 刻, 整 and a bare hour stay characters: 8点半, 7点整, 8点; hour and minutes 10:15). `VoiceText.clean` (spaces, spelled letters, noise) runs after it. A tagger that fails to load, or
  fails at the end, leaves the raw recognized text typed, numbers in characters.
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
- One talk is a `VoiceSession` (app `input/voice/`), whatever starts and stops it: the listener, what is composed and
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
  or failed, tapped or held (`VoiceSession.endTypingWhatWasHeard`, every session), what was heard last is typed too (what was said stays, the user's rule), and the message is
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
  `VoiceSegments` (ime-core) cuts the talk into those segments and asks it; `VoiceRecognition` is
  its seam to the recognizer (sherpa-onnx in VoiceListener, a script in VoiceSegmentsTest); the one
  `VoiceQuiet`, fed by the capture thread, is handed in.
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
  code for a cost the record's buffer already absorbs.

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
  112) typed 「欢迎」 0 times. Extra text: nihao-q44 at R−66 typed 「你好好」 2 of 8 (h3 and h4 alike; measured before h2 was taken, not rerun under it);
  zouba-q44-room at R−60 was misheard 4 of 8 (我们走了, 我没走到), as the q44 clips were before.

  VO-32's occasional 「你好。欢迎您」 on the emulator is not explained; suspected, not shown: the
  room clip's hard-cut edges. Offline (quiet_eval's `edge-*`: nihao-q44-room as cut hard, kept outside
  the repo at voice-clips/hard/, k = 0..496 samples in, 32 offsets, 「欢迎」 in the typed text):
  repro, the clip mic's floor (±1 in 128) before and 3 s after: 0/32; (i) −60 pink room before and
  after instead, joined at the clip's hard edges: 15/32; i_fade, (i) with the clip faded 50 ms: 9/32;
  (ii) the floor with the clip faded: 0/32. The emulator's case (the floor around it) was never
  reproduced here, and fading lowers (i) but does not end it, so the edge is at most part of it; in
  the one traced (i) talk the 「欢迎您好」 tokens sat at 1.96–2.32 s, the clip ending at 2.11 s, no window
  voiced. On the emulator (2026-11-07, 8 runs each, nihao-q44-room 3 tries a run) the hard-cut
  clips failed VO-32 in 4 runs, 5 of 24 tries wrong (「你好。欢迎您好」 among them); the faded ones
  passed all 8, 0 of 24: the edge is the likely trigger there, though offline it is not reproduced.
  The clips fade now (voice-clips.py). No rule changed: h4, every segment after a typed one held to the short Latin bar, ends
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
