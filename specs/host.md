# The host: fcitx5 in Kotlin

The app's input method framework is fcitx5's, ported to Kotlin (`dev/design/fcitx-replacement.md`, v3):
`lib/ime-core/.../host` (package `org.fcitx.fcitx5.android.host`), run by the app's `KotlinHost`. It is
the only host; the native fcitx5, libime and chinese-addons sources are gone. This page is the contract
the pieces that plug into it follow: the engine host, the modules (punctuation, fullwidth, chttrans,
status area), the temp modes (quick phrase, Unicode), the config files.

Every class names the fcitx function it ports and its line. `fcitx/…` and `fcitx-utils/…` are
`src/lib/…` of upstream fcitx5 at 442edbc9, `ca/…` is `modules/…` of fcitx5-chinese-addons at 0d3fd040;
`lib/ime-core/tools/upstream.sh fcitx5|chinese-addons` downloads either (SHA-256 pinned, cached in
`~/.cache/youmo-upstream`) and prints its directory. `androidengine.cpp`, `androidfrontend.cpp`,
`native-lib.cpp` and `Fcitx.kt` are the former native host, under `app/src/main/cpp/` and
`app/src/main/java/…/core/` at the tag `native-last`. The contract tests (`KeyPipelineContractTest`,
`InputContextsTest`, `KeyNormalizeTest`) cite the same lines. A behaviour that differs from fcitx on
purpose is said so where it is written (the design's Q9 list; CHANGELOG 0.4.0).

## A key through `KeyPipeline`

`KeyPipeline.dispatch(ic, KeyEvent)` runs fcitx's phases (`fcitx/instance.cpp:1651-1676`). At each,
the handlers run in list order and the first that **filters** ends the key; a key nobody **accepted**
goes back to the application. The two flags are different things:

| Flag | Means | Who sets only one of them |
|---|---|---|
| `filtered` | no later handler sees the key | a temp mode filters every key, accepts only those it used; a hook may filter without accepting (the key is handed back) |
| `accepted` | the key is used up, not handed back | fullwidth's key conversion accepts without filtering, so the key still reaches ReservedLast |

| # | Phase | What runs | Extension point |
|---|---|---|---|
| 1 | — | `KeyEvent` normalizes `origKey` into `key` (`Key.normalize`, `fcitx-utils/key.cpp:493-533`); handlers match on `key` | — |
| 2 | ReservedFirst | e.g. hide the input method name overlay on a press | `PreKeyHook` list |
| 3 | PreInputMethod | an active temp mode takes every key; the engine never sees it. A grouped keyboard's code (`NineKeyLetters.CODES`: 18 键's U+F8C0–F8D9, 14 键's U+F8DA–F8F3, 九键双拼's U+F8F4–F8FC) or a tap (`Keyboard.TAPS`, a layout's own keyboard's `Keyboard.PLACES` and codes `NineKeyLetters.CUSTOM_BLOCK`) reaches the mode as its letter (`Keyboard.typedLetter`: the half tapped, the first at the centre; on its own keyboard the letter tapped nearest), and the modes type no private use character | `TempMode` list in `TempModeManager` |
| 4 | InputMethod | the engine's `keyEvent`; a grouped keyboard's code goes only to the input method that reads it (`InputMethodCatalog.readsCode`: pinyin G14's U+F8DA–F8F3, 双拼 G18's U+F8C0–F8D9 and NINE_SP's U+F8F4–F8FC; see engine.md *Codes*), and a tap (`Keyboard.TAPS`, `Keyboard.PLACES`) and a layout's own keyboard's code (`NineKeyLetters.CUSTOM_BLOCK`) are pinyin's alone; `KeyRouter` swallows them for any other input method | `KeyEngine` (`KeyRouter`) |
| 5 | PostInputMethod | temp mode triggers first, then the hooks: fullwidth's conversion, Ctrl+. (punctuation), Ctrl+Shift+F (chttrans) | `PostKeyHook` list |
| 6 | ReservedLast | the engine's `filterKey`, then `KeyResultListener`s (punctuation's last letter/digit) | `KeyResultListener` list |
| 7 | — | not accepted: `HostOutput.handBack(origKey, release, timestamp)`, **press and release alike** | — |

Releases go through every phase too; each handler that only wants presses checks `e.release` itself, as
each fcitx module does. Other entry points:

- `forwardKey(ic, key, release)`: a key the engine sends on (a cursor move). `ForwardKeyListener`s see
  it (punctuation updates its last character, `ca/punctuation/punctuation.cpp:249-254`), then it is
  handed back with timestamp -1.
- `invokeAction(ic, InvokeActionEvent)`: a tap in the code row; an active temp mode's, else the engine's.
- `reset`, `focusOut`, `switchedInputMethod`: active temp modes leave (`fcitx/tempmodemanager.cpp:28-52`);
  `ContextListener`s get `onReset` / `onFocusIn` / `onFocusOut` (punctuation clears its state on reset,
  `punctuation.cpp:287-302`).

### Output and its order

- `commit(ic, text)`: `CommitFilter`s in list order, then `HostOutput.commit` **at once**. The order
  modules register in is **punctuation → fullwidth → chttrans**: punctuation records whether the last
  character is an ASCII letter or digit before fullwidth changes it; fullwidth converts `wc > 32` only,
  so not the space (`ca/fullwidth/fullwidth.cpp:84-103`); chttrans converts the result.
- `filterOutput(ic, FormattedText)`: `OutputFilter`s in order, for preedit, aux, every candidate fetched
  and tips. `WholeTextFilter` is chttrans's way (`ca/chttrans/chttrans.cpp:133-183`): convert the joined
  segments as one string, cut it back into the old segments' lengths in characters (code points),
  keep each segment's flags, keep the cursor's count of characters. The frontend checks a selected
  candidate against the **filtered** text (`androidfrontend.cpp:302-305`).
- `deleteSurrounding` and `handBack` go out at once too. `updatePanel(ic)` marks the panel; it is sent
  **once, at the end of the event** (`fcitx/inputcontext.cpp:433-466`), so a key's commit precedes the
  panel that clears its preedit.

### The interfaces and who implements them

| Interface | Implemented by | Notes |
|---|---|---|
| `KeyEngine` | `KeyRouter` (`androidengine.cpp:550-687`) | filterAndAccept what it takes; `filterKey` at ReservedLast |
| `EngineHost` | app `EngineBridge`; tests: `Engines` | `event` returns null for an engine that cannot load |
| `HostOutput` | `HostCore` → `KotlinHost` events | commit/delete/handBack at once, panel coalesced |
| `TempMode` | `QuickPhrase`, `UnicodeInput` | `trigger` makes itself active; ignore releases there |
| `PostKeyHook`, `CommitFilter` | `FullWidth` (both), punctuation and chttrans hotkeys | only on contexts whose status area has the module (a Chinese input method) |
| `KeyResultListener`, `ForwardKeyListener`, `ContextListener` | `Punctuation` | per-context state in an `IcProperty` |
| `OutputFilter` | chttrans via `WholeTextFilter(applies, ChineseConverter::convert)` | |

Per-context state a module keeps is an `IcProperty` (`val state = IcProperty { PuncState() }`, read
with `ic.propertyFor(state)`); state that must follow the user across apps is not, it is `InputContexts`'.

## Which input method: `InputContexts`

One context per app uid (`activate(uid)`, LRU of 80 like `androidfrontend/inputcontextcache.h`), each
with an `InputState` (`active`, `localIm`). `ShareInputState=All`, fcitx's default on Android
(`fcitx/globalconfig.cpp:189-191`):

- a change to one context's state is every context's (`propagateProperty`); a new context starts from an
  existing one's (`inputcontextmanager.cpp:159-185`). So every app is on the same input method;
- `inputMethod(ic)` (`fcitx/instance.cpp:1735-1768`): `keyboard-us` in a password or disabled field;
  otherwise the local input method or the group's default when active, the group's first when not;
- `setCapabilities` on a focused context that crosses the password/disabled line switches the old input
  method out and the new one in (`:725-764`): the engine commits what was typed, and leaving the field
  brings the user's input method back. The state is not touched;
- `toggle`, `enumerate`, `setCurrentInputMethod`, `setInputMethods` port `instance.cpp:2231-2242`,
  `:2311-2350`, `:2102-2160` and `InputMethodManager::setGroup`. Every focused context whose input method
  changed is reported to `onSwitch(ic, reason, old, new)`, the reason `SwitchReason` in the app's
  `SwitchInputMethodEvent.Reason` order; the host deactivates the old engine and activates the new one
  (`instance.cpp:1178-1188`), and leaves temp modes (`KeyPipeline.switchedInputMethod`).

`ImGroup` is fcitx's group: the default is never the first while there is a second
(`inputmethodgroup.cpp:93-114`). `ImGroup.firstRun(name, pinyin, available)` is a new install: fcitx's
`_("Default")` group of `keyboard-us` (`inputmethodmanager.cpp:147-162`) with pinyin put first
(`Fcitx.kt:437-447`), so `[pinyin, keyboard-us]`, default `keyboard-us`; `name` is the app's translated
"Default".

## The chinese-addons modules: `Punctuation`, `FullWidth`, `Chttrans`, `StatusArea`

Each module hands the pipeline its hooks; register them in this order (`ChineseAddonsTest` builds it):
post hooks `fullWidth.keyHook, punctuation.hotkey, chttrans.hotkey`; result `punctuation.keyResult`;
forward `punctuation.forwardKey`; commit filters `punctuation.commitFilter, fullWidth.commitFilter,
chttrans.commitFilter`; output filter `chttrans.outputFilter`; context `punctuation.contextListener`.
`FullWidth` commits through `KeyPipeline.commit` (so the filters see its output); `Chttrans` asks for
the panel again through `updatePanel`.

- **`StatusArea`**: the modules `register` their `StatusAction` (ids 1.. in registration order); when
  the host activates an input method it calls `setInputMethodActions(ic, …)`: `chttrans, punctuation,
  fullwidth` for a Chinese one, none otherwise (`instance.cpp:2491` clears, `androidengine.cpp:500-509`
  adds). A module acts on a context only while its action is in that list. `entries(ic)` is what the
  panel shows (`StatusEntry`: id, name, icon, shortText; the app labels the three by `name`), `activate(ic, id)` a tap.
- **Tips** (`Tip`): Ctrl+. gives `PunctuationEnabled/Disabled` ("标点: 已启用全角标点。" / "标点: 全角标点已禁用。"),
  Ctrl+Shift+F `TraditionalEnabled` / `SimplifiedEnabled` ("切换到繁体中文: 已启用繁体中文。" / "切换到简体中文: 已启用简体中文。"):
  the app makes the text, "summary: body" as `androidnotification.cpp:84`. A tap in the status area tips nothing.
- **`Punctuation`**: `PunctuationProfile.load(system, user)`: a user file replaces the built-in table
  wholesale; `parse` keeps fcitx's line rules, `fromEntries` the edit page's, `format` its save format.
  Per-context state (`lastIsEngOrDigit`, `notConverted`, the pair stack) clears on reset; the key router
  calls `pushPunctuationV2`, `cancelLast`, `getPunctuation(Candidates)` as `androidengine` calls
  `IPunctuation`. `PunctuationConfig` is `punctuation.conf`; `save` is told of every change.
  Rebuilding the state from surrounding text is not ported (unreachable today).
- **`FullWidth`**: off at start, not saved, no hotkey; a press with no modifier of U+0020–U+007E commits
  its full-width form and is accepted, not filtered; the commit filter converts above space only.
- **`Chttrans`**: per input method (`EnabledIM`); converts when the IM's language is zh_CN, it is
  listed and the toggle is in the status area. No traditional-to-simplified (no zh_TW/zh_HK IM exists).
- **`ChineseConverter`** is OpenCC 1.2.0 `s2tw` (segmentation on STPhrases, each segment converted on
  its own, group STPhrases→STCharacters taking the first dictionary with a match, then TWVariants; a NUL
  ends the text). Its data is `engine/opencc-s2tw.data` (`OpenCCData`), compiled by
  `ime-dict-tool opencc` (`compileOpenccData`, part of every app build) from the 1.2.0 dictionaries
  committed in `lib/ime-dict-tool/opencc/`. `lib/ime-eval`'s `ChineseConverterGoldenTest` holds it to
  OpenCC's own output, all 82,800 cases.

## Keys

`Key(sym, states)` with fcitx's bits (`KeyStates`) and keysyms (`KeySyms`). `KeySyms.toUnicode`,
`fromUnicode`, `fromString` and `toString` are `Key::keySymToUnicode`, `keySymFromUnicode`,
`keySymFromString` (the soft keyboard's `sendKey(String)`) and the portable `keySymToString`. Their
tables, `KeySymUnicodeTable.kt` (777 pairs) and `KeySymNameTable.kt`, are generated by
`lib/ime-core/tools/keysyms-gen.py`, which downloads fcitx's headers at `442edbc9` (SHA-256 pinned, no
checkout needed) and also writes the golden files `KeySymsGoldenTest` checks; don't edit them by hand.

## The host core: `HostCore`

`HostCore(host: EngineHost, clock: HostClock, profile: Profile, …, persist, modules)` is fcitx's
`Instance` with the `androidfrontend` and `androidengine` addons, on one host thread. Its entry
points are the frontend's calls (`activate`, `focus`, `setCapabilities`, `sendKey`, `select`,
`candidates`, `candidateActions`/`triggerCandidateAction`, `setCandidatePagingMode`,
`offsetCandidatePage`, `moveCursor`, `reset`, `toggle`, `enumerate`, `setCurrentInputMethod`,
`setInputMethods`); each is one event (`KeyPipeline.batch`). Output is `events`, a list of
`HostEvents` listeners called in turn on the host thread, nothing buffered or dropped (the app's
`KotlinHost` is the one listener, and wraps it as its flow). Commits, deletes and keys go out at once; the client preedit at
once too (`updatePreedit`, only with the Preedit capability, two empty ones once); the panel and
the candidates once at the end of the event, for the active context only.

| Piece | Ports | Notes |
|---|---|---|
| `KeyRouter` | `androidengine.cpp` `keyEvent`/`send`/`show`/`activate`/`deactivate`/`reset`/`invokeActionImpl` | the pipeline's `KeyEngine`; a session that throws: panel cleared, session reset unless it never answered, one whose reset fails not reset again (`native-lib.cpp:722-745`) |
| `KeyRouter.EngineCandidateList` | `EngineCandidateList` (`:75-239`) | page from the snapshot, the rest a chunk of 32 at a time, those before the page once; long press asked, then checked again on the same candidate |
| `CommonCandidateList` | fcitx's | punctuation's marks; temp modes may use it |
| `Panel` | `InputPanel` | per context (`InputContext.panel`); `reset` clears the overlay too; `empty` ignores it |
| `Refiner` | `refine_` (`:26-28,706-721`) | 30 ms after a key, a slice at once after a slice; any event cancels it, a long-press menu holds it |
| `Profile` | `InputMethodManager` load/save | fcitx's INI rules, through `IniFile`; defaults written as `# Key=`; other groups kept |
| `HostClock` | the event loop's timers | `VirtualHostClock` (testFixtures) is also the `LearnClock`, so a replay learns the same |
| `EnginesHost` | — | `EngineHost` on `Engines` alone, for tests and the replay |

Input method switches: `InputContexts.onSwitch` → old engine deactivated (a switch sends Enter,
committing what was typed), the new activated (`ActivationListener`s, then Reset), the app told
(`InputMethodChanged` for the active context; `SwitchInputMethod` but for a capability or group
change), temp modes left, and the name overlay (the entry's label, 1 s, hidden by a key press) for
every reason but CapabilityChanged and GroupChange; a new list shows it 30 ms after on the focused
context. A new list switches every focused context, changed or not (`instance.cpp:687-711`).

Plug-ins: `HostModules` (made with the core's `HostServices`) carries the pipeline's lists, the
`ActivationListener`s and `EnginePunctuation` (what the engine asks of punctuation:
`candidates`/`get`/`push`). State that belongs in a file is told as it changes: `persist` gets
`Persist.ProfileChanged` (input method list, default) and `Persist.ConfChanged(conf)` from a
module's `HostServices.persistConf`, so the INI writer saves it without waiting for a settings page
to stop. Texts gettext gave are `HostStrings` (the app's resources). `Engines(clock)`
takes the platform `Clock` (the wall time learning stamps and dates by, and the uptime tables read; `LearningFacade` and
`SessionFactory` are handed it as their `learnClock`), and the date and time phrases (`rq`, `sj`, `xq`, those 日期 brings
in a table) are filled in at `learnClock.calendar()`, so nothing in a replay reads the system clock
(`HostCoreEnginesTest`); `Engines(background)` runs compactions and backups where a test can run them in place.

## Temp modes: quick input and Unicode

Both are `PanelTempMode`s registered in this order in `TempModeManager` (fcitx loads quickphrase
before unicode). They talk to the host through `TempModeHost`: `commit` goes through the pipeline's
commit filters, `showPanel(ic, TempPanel)` hands over the mode's whole panel (preedit or client
preedit, aux, `TempCandidateList`), `TempPanel.EMPTY` when the mode leaves — fcitx resets the input
panel then, and the engine's panel only comes back with the engine's next event. A tapped candidate
is `selectCandidate(ic, globalIndex)`. Paging constants (5 a page, Up/Down, Tab/Shift+Tab) are
`TempKeys`' (design §1.4); the lists' labels are fcitx's `keyToLabel` ("1. ", "A-1. ").

- `QuickPhrase` (`modules/quickphrase/quickphrasetempmode.cpp`): Super+grave / Super+semicolon or
  `trigger(ic)` (the keyboard button). Matches come from `QuickPhraseTable` only: no callback
  providers, no spell hints, so every match commits and the digits select. Space commits the
  cursor's, Return what was typed, Backspace/Delete on empty leave, Home/End/Left/Right move,
  30 characters at most. Beyond fcitx (design 051-quickphrase): `readsCodes`, so it gets the
  keyboard's private-use codes and `QuickPhraseKeys` turns each into the set of characters it may be
  (nine keys by the user's letters, a 14/18 key's whole group), kept in step with the buffer; with
  nothing typed it lists the user's entries and a ＋添加 (`QuickPhraseHooks.add`), Space then leaves
  with a space and digits are typed; the user's entries commit through `commitAsIs` (no chttrans or
  full width) and their use goes to `QuickPhraseHooks.used` except in a `PASSWORD_OR_SENSITIVE`
  field; `withMine` false (借手机) hides them. `Panel.quickPhrase` tells the app the panel is quick
  input's (the handwriting pad swaps for the full keyboard); `HostCore.quickPhraseAt` gives the
  entry under a long press.
- `QuickPhraseBook`: the user's entries (`data/quickphrase.d/mine.mb`) in order of use (`mine.used`), then the built-in
  `QuickPhraseTable`; the user's match by key prefix case aside, the built-in ones case kept. An entry
  (`QuickPhraseEntry`) is an ASCII key (visible characters, `QuickPhraseFiles.validKey`), its content, a
  note (the candidate's comment, else the key) and a folder (the settings page's alone: matching and
  order ignore it). Two entries whose keys differ only in case and whose note and content are the
  same are one (`sameAs`): refused when saved, skipped when imported, the first kept when read.
- `QuickPhraseFiles`, version 2 (design 060-quick-input-folders): `mine.mb` is still fcitx's `.mb`, so
  fcitx5 on a computer and older builds read it and the export is the same format: a first line
  `#youmo-quickphrase-v2` and folder lines `【目录】name` (no whitespace: fcitx skips them; read through
  `QuickPhraseTable.parse(bytes, bare, each)`), entry lines `key｜note content` (the note after a
  full-width bar, its spaces as U+00A0). A key splits from its note at its first character that is
  not visible ASCII (`split`), one `｜` at the note's start dropped and its U+00A0 spaces again,
  the same for version 1 (no first line: a file of before, an old backup, a computer's `.mb`, or one
  of now a build before 0.6.0 wrote again): `#wz百度` reads as `#wz` + 百度, and a key Chinese from
  its start as no key (`keyless`) with the old key as the note: listed with nothing typed, found by
  none, said on the page (待补关键词) until given a key. A line with neither key nor note is no entry,
  read or imported. Notes and folder names keep no whitespace but spaces (`note`), so they read back
  as written; a folder cannot take a shelf's name (`RESERVED_FOLDERS`). A read never writes; the
  next change writes version 2. `mine.used` (`#youmo-quickphrase-used-v2`, then
  `millis<TAB>key｜note<TAB>content escaped as fcitx's value`) keys times by the entry out of its folder,
  so a move keeps its time; one without its first line splits as version 1. Of the user's directory
  only `mine.*` and `*.disable` are read. `mine.mb` is read with a leading UTF-8 BOM dropped; the
  lines fcitx skips, a duplicate, and a content over `MAX_CONTENT` (5000 UTF-16 units, the page's
  limit) are left out, and so are gone from the file at its next write; the entries are written
  grouped by folder (none first, then the folders in order, an empty one a line alone). The app's
  `QuickPhraseManager` writes both files whole under one lock (page and keyboard both write), and
  finds the keyboard's entry (used, deleted) by key, note and content, its folder aside.
  `HostCore.refreshQuickPhrase` (after `reloadConfig`) shows an open quick input's list again from
  the new book, so an entry deleted from its card goes at once.
- `QuickPhraseTable` (`quickphraseprovider.cpp:29-104`): `data/QuickPhrase.mb` from the first
  `PkgDataDir` that has it, then `data/quickphrase.d/*.mb` by name (an earlier directory's file hides
  a later one's of the same name; `<name>.disable` anywhere hides it), multimap in code point order.
  `QuickPhraseBook.load` passes only the user directory's `.disable` files, then the built-in one;
  `reload` swaps the book.
- `UnicodeInput` (`modules/unicode/unicodetempmode.cpp`): Ctrl+Alt+Shift+U or `trigger(ic)` searches
  (opening on the clipboard's latest text's characters), Ctrl+Shift+U types hex. `CharSelectData`
  (`charselectdata.cpp`) reads the same `charselectdata` file, at the first trigger as fcitx does
  (nothing held before: warming it at start cost 9.5 MB of Java heap and, on the host thread, seconds of
  cold start). The trigger's panel needs only names, read from the bytes; the search's index is built
  on a background thread then (`CharSelectData.prepare`), a search typed meanwhile waits for it, so
  the long press's panel is not empty (QU-06).
  Names fcitx makes up and the aux texts come in as `CharNames`/`UnicodeStrings`, translated by the app.

Parity: `TempModeGoldenTest` compares `CharSelectData.find` (168 queries), every code point's name
and `QuickPhraseTable.populate` (the shipped tables, a loading-rules fixture) with goldens written by
fcitx's own code compiled from upstream fcitx5 at 442edbc9 (`lib/ime-core/tools/host-golden`: `build.sh`,
`gen.py`; the tree comes from `tools/upstream.sh`, SHA-256 pinned). The tests read the app's own copy of
`charselectdata`, the three `.mb` tables and `punc.mb.zh_CN` (`app/src/main/host-assets/pkgdata`, sources and
SHA-256 in `host-assets.md`; on ime-core's test classpath as `/pkgdata/…`), so they need no submodule.

## The modules together: `ChineseModules`

`ChineseModules(ModuleInputs, HostStrings).build` is what the app passes as `HostCore`'s `modules`:
the chinese-addons modules in the order above, quick input then Unicode as temp modes, the status
area (a Chinese input method activated puts `chttrans, punctuation, fullwidth` up, any other clears
it), and the engine's `EnginePunctuation` on `Punctuation`. A module's change of saved state calls
`persistConf(ModuleConfigs.PUNCTUATION / CHTTRANS)`; `conf(path, old)` gives the file's new text
(`ModuleConfigs`: the three punctuation values and `EnabledIM`, the rest of the file kept, a default
commented out). Tips go out as `HostEvent.Tip(strings.tip(tip))`, the app's words.

`HostCore` keeps a temp mode's panel as the context's panel (`showPanel`: reset, then its preedit or
client preedit, aux and list, sent at the event's end; a tap is the mode's candidate's own select);
`triggerQuickPhrase` turns the preedit off first, as `native-lib.cpp:286-296`; the status area goes
out as `HostEvent.StatusArea` once at the end of an event that changed it (`HostOutput.flushed`);
`configure(EngineOptions)` sets the router's page size and English suggestions and the engines'
settings (`EngineOptions.toSettings`). The app takes the `HostEvent`s as they are (a tip goes to
`EngineNotices`, not the flow); an input method's entry is the one androidengine lists
(`InputMethodEntries.of`), and the status area is its `StatusEntry`s, the native `Action`'s menu,
separator and checked state never having been set.

## Config files

The host keeps fcitx's files, same paths, same INI semantics (design §2), so an old install and an old
backup read as they are, and v0.3.x reads what the host writes.

- `IniFile` is `fcitx-config/iniparser.cpp`: lines trimmed, `#` lines skipped (a default is written
  `# Key=Value`, a whole default group `# [Group]`, so a file says nothing of a default and a later
  change of default reaches whoever never touched it), **nothing trimmed around `=`** (`a = b` is the
  key `a `, which no option has), values unquoted/escaped as `stringutils.cpp:390-425`. Written without
  fcitx's description comments (translated text fcitx never reads). A node is implicit if it or a node
  above it is (`RawConfig::isImplicit`). `ConfigTree` is fcitx's `RawConfig`.
- `EngineOptions` is `androidengine.h`'s option table with `Configuration`'s rules: a value that does
  not read falls back to its default, never clamped; a key it has no option for is dropped at the next
  save; a table group missing from the file is the table's defaults (`engineTable(hint)`: 仓颉 and 二笔
  without hints), a group there with an item missing gets a fresh group's (hints on), as fcitx does.
  `page(im)` is `getConfigForInputMethod` (a table's own keys at the top), `setPage` the partial save;
  `toSettings()` is what the engine reads: every value, defaults too, each table's group among them,
  as typed `EngineSettings` (no text between the two: the addon's `pushSettings` flattened and the
  engine parsed again, a round trip with nothing to gain once both are Kotlin).
- `ConfigWriter`: every change is written in the background, merged per file, atomically (`.new` →
  rename), without waiting for a settings page to close; `flush` at stop and before `save()` returns.
  The drain is `synchronized`, so a flush waits for a write the background thread already took
  instead of returning while that write is still in flight (`ConfigWriterTest`). Before the user unlocks
  nothing is written, stop included.
  A file `hold` names is never written this run (what was pending for it dropped): one that did not
  read.
- **Where they are, and a read that fails (A4).** In the app's own files, `files/host-config`
  (`ConfigHome.CONFIG`), not fcitx's `config/` on the external storage, which a phone cleaner empties and
  which can be away at a start; fcitx's data and the themes the same way (`ConfigHome.DATA`,
  `files/host-data`, from `data/`; `ConfigHome.THEME`, `files/host-theme`, from `theme/`), each settled
  on its own, once a process (`ConfigHome.Settled`, held by `HostConfigDir`): whoever asks first decides,
  a failed move too, and the engine, the editors and a backup then all use that one directory; the next
  process tries again. A move in the middle of a process would leave an editor writing where the engine
  no longer reads. `ConfigHome.settle` (ime-core, over `UserFiles`) moves an older build's in once: the
  external one, else the `files/<old>` an older build fell back to, streamed to `<name>.new`, each file
  read back and compared, then renamed in, and the old deleted (going back to 0.4 needs an
  uninstall, which takes both). Only a home the move made deletes: `ConfigHome.MOVED`, written into
  the copy before the rename, names the source, and goes once that is deleted; a delete that fails,
  or a kill before it, is tried again at the next start, the external one only while the storage
  lists. A home made empty never deletes an older build's directory that appears later; a backup
  leaves the marker out. A directory that does not list (`UserFiles.listable`: FUSE's EIO/EACCES) fails the copy rather
  than moving nothing, and an external storage not `MEDIA_MOUNTED` is away. A failed copy reads and
  writes the old where it is and tries again next start; no external storage and nothing moved yet:
  read what is there, write nothing (`EXTERNAL_AWAY`), unless certainly nothing is old (no
  `files/engine` yet, the first run not done, no fallback: a new install), then its own directory is
  made. Not writable, an editor's save (custom phrases, dictionaries, themes), a backup and a restore
  refuse with `user_data_not_settled` (`HostConfigDir.inUse`), as what they wrote would be lost or
  passed over at the next start; with none settled the editors read an empty stand-in under the
  cache, never `files/<old>`, which a later start would take for an older build's fallback. A copy
  lists each directory once (`UserFiles.listing`), a null listing failing it. The DATA home is settled
  at the app's start on a background thread (`FcitxApplication`), only with the external storage
  `MEDIA_MOUNTED`, so neither an editor on the main thread nor the host waits on its copy; this changes
  when, never where. An absent file is the defaults, as INI says; a file that is there but does not read is **not**, and neither is one `exists()` calls absent where
  a failed stat may hide it (`KotlinHost.statFailed`: its directory lists it, a directory on the way is
  there but does not list, or the home itself is missing before the move): `KotlinHost.readConfig`
  logs it, `hold`s it so the defaults typed with are never written over it, and tells the user once per
  run (`config_unreadable`, on the keyboard through `EngineNotices.failedUntilSeen`, said again the next
  time the keyboard shows if it was only a toast). A config not settled writable (`EXTERNAL_AWAY`, a
  failed `mkdirs`) is told as `config_not_settled`; data not settled, a punctuation table, the custom
  phrases, the quick phrases or a theme that does not read, as `user_data_unreadable`
  (`EngineNotices.failedOnce`: once a process). An unreadable punctuation table gives the built-in one
  and is never saved over (`PunctuationMaps.save` throws); unreadable custom phrases are not saved.
- `PunctuationTable` / app `PunctuationMaps`: `punc.mb.<lang>` as `ca/punctuation/punctuation.cpp:75-173`
  reads and writes it; the user's file replaces the built-in table whole.

The golden files under `lib/ime-core/src/test/resources/host/ini` come from fcitx's own config code built
on the host from upstream fcitx5 at 442edbc9 (`tools/upstream.sh`) with the former native engine's option
table (`lib/ime-core/tools/fcitx-ini/options.inc`, `androidengine.h`'s as of `native-last`), including the
v0.3.0–0.3.2 devices' `androidengine.conf` from
`uat/fixtures/upgrade`.

## The app's side: `KotlinHost`

`HostDaemon` starts `KotlinHost`, the only `HostInstance`. It implements the `HostApi` and runs
every call on `HostDispatcher`, the single "fcitx-main" thread ([architecture.md](architecture.md#processes-and-threads)).
`startup` settles the host's config and data directories (`ConfigHome.settle`), starts the engine, reads the profile, `androidengine.conf`,
`punctuation.conf` and `chttrans.conf`, starts the quick phrase tables loading on a thread of their own,
builds `ChineseModules` and `HostCore`, then posts Ready. What each punctuation key types (`HostCore.punctuationKeys`; before a context, `startPunctuationKeys`: under the group's first input method, full width off) is written for the next start (`KotlinHost.keepStartKeys` → the app's `PunctuationStartKeys`, `filesDir/punctuation-start-keys`, only when changed), so the keyboard draws the punctuation keys before the host is up ([app.md](app.md)). Built-in data comes from the APK's `pkgdata/`
assets (`BUILT_IN`), OpenCC's from `engine/opencc-s2tw.data`, mapped at the first traditional text;
the gettext translations are gone, the host's own texts are app resources (`AppHostStrings`).

- **The `HostApi`, by part.** `HostApi` is the host's cached state (`eventFlow`, the input method,
  status area, preedit and panel as last told), `save` and `reloadConfig`, over one interface per
  part: `TypingApi` (keys, candidates, the field, the status area, the engine's typing-time calls),
  `InputMethodsApi`, `EngineSettingsApi`, `PunctuationApi`, `LexiconApi` (the user's words) and
  `LearningApi`. Every call is `suspend` and runs on the host thread.
- **The `EngineSide` seam.** `KotlinHost` owns its `EngineBridge` (`bridge`, made with it: one per
  host) and takes an `EngineSide`: the engine host, `data()` (fcitx's data directory and whether it is written, `HostConfigDir.settle(DATA)`), `config()` (the config's
  directory and whether it is written, `HostConfigDir.settle`), `start()` and `close()`. The app
  passes none, and gets `EngineSide.App` over its bridge. The JVM tests pass `ScriptedEngine` (app
  test sources), so they need no JNI. What the keyboards watch of the engine (the syllables
  offered, English's word, the handwriting piece ended, the 双拼 scheme, refining) is
  `EngineState`'s, one per process as the keyboards are.
- **Settings pages.** `imOptions(im)` is the page's options by path (`Fuzzy/L_N`) to value, the
  leaves of `EngineOptions.page`, null for an input method with no page. `setImOptions` takes those
  a page shows (`InputMethodSettings.only`) into `EngineOptions.setPage`, a partial load that keeps
  the rest. `punctuationMap(lang)`/`setPunctuationMap` are zh_CN's map as entries, another
  language's empty.
- **Reloading config.** `reloadConfig` reads the module files again. It calls
  `Chttrans.reload(enabled)` (chttrans.cpp:218), then sends the status area out again.
- **Focus out.** Pinyin's typing is dropped when the field loses focus, as on the native host:
  androidengine.cpp:765-771 resets on focus out (only an input-method switch keeps the typing), and
  instance.cpp:1131-1138 makes no server-side commit while ClientUnfocusCommit is set, a flag in the
  app's DefaultFlags. English keeps its composing word, because it composes in the app's field
  (`KeyRouter.composesText`).
- **The first key after a cold start.** A key sent before the host is ready is lost, as on the native
  host of 0.3.x, which let "nihao" through raw on a cold start followed at once by `input text`, 3 times
  out of 3. The frontend queued nothing before ready (androidfrontend.cpp has no key queue), and the
  host does the same. It is ready in about 420-650 ms.
