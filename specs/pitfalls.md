# Pitfalls

Things that cost hours before. Each says how it shows and what to do.

## Build

### Kotlin IC and interface delegation

After adding a method to `HostApi` (or any interface implemented elsewhere with `by`), the
incremental Kotlin build can leave a delegating class (`object : HostApi by fcitx` in another
file) without the new method. It compiles; the app crashes with `AbstractMethodError` the first
time the method is called. Unit tests and Robolectric miss it (they use `FakeHostApi`).
Before building an APK to test or ship after such a change, delete
`app/build/intermediates/built_in_kotlinc/<variant>` (and `app/build/kotlin/compile<Variant>Kotlin`
if present). Check with `javap`/`dexdump` that the delegate has the method.

### The engine data rebuilds

Editing anything under `build-logic` changes the data task's implementation, so the next build
compiles `pinyin.data` and the tables again (minutes, ~4 GB). Editing ime-core reruns it only if the
change reaches the dict tool's code (`ToolFingerprint`): `engine/data`, `pinyin`, parts of `user`,
`table`. Expected, not a bug.

### Memory

The data compile, a release build and an emulator do not fit together in 15 GB. A stray
`KotlinCompileDaemon` (Xmx3g, idle for hours) came from build-logic once; it now compiles in
process. If memory looks high, `pgrep -fa KotlinCompileDaemon`.

### detekt sees syntax only

Type resolution does not work in this setup, so detekt rules that need types do not run. Do not
take a clean detekt as proof of type-level correctness.

### Native build outputs keep what is no longer built

A checkout that built 0.3.x still holds native fcitx's `cmake --install` output in
`app/src/main/assets/usr` (and `descriptor.json`) and its `.so` files in AGP's `obj/` dirs: nothing
deletes them. The app build stops on the first two (`app/build.gradle.kts`: "0.3.x leftovers would go
into the APK, delete them"): delete them and build again; they are no longer in `.gitignore`, so
`git status` shows them too. The old libraries stay excluded through `packaging.jniLibs.excludes`
(`app/build.gradle.kts`). Check a release APK with `unzip -l` (no `libFcitx5*`, `libnative-lib`,
`libpunctuation`, no `assets/usr/`). The same holds for any native library dropped from
`lib/handwriting` or `lib/sherpa-onnx` later: exclude it through `packaging.jniLibs.excludes`.

On the phone, 0.3.x's data sync left fcitx's files in device-protected storage (`usr/`, `learn/`,
`README.md`, listed in `descriptor.json`; 7.4 MB from 0.3.2). Nothing reads or removes them since 0.6.3
(the one-time cleanup went, 063-slim RU4); the backup rules exclude them. User data was never there: fcitx's config and data were in the external files dir (now `files/host-config`, `files/host-data`, A4).

## Android behaviour

### The cursor reports lag

Editors report selection changes late (a WebView, a long document much later). Never compute the
next edit from the last report: `EditingSession` keeps the IME's own prediction and reconciles
(`CursorTracker`); arrow-key runs step from where the presses put the cursor (`ArrowKeys`).
Deletions are asked in code points so one press removes a whole emoji (`CodePoints`).

### A selection looks like a cut

With text selected, `getTextBeforeCursor` gives what is before the selection and
`getTextAfterCursor` what is after it: the same as if the selection had been cut. Anything that
reads a deletion from the text around the cursor (the commit journal, U-03) must be told a
selection is there (`Action.Context.selected`), or it writes UNDOs for text still on screen.
Likewise an empty text before the cursor may be an editor that would not say (`FieldPolicy`), and
every keyboard is told every context, also of text another keyboard wrote.

### Composing text in the app outlives the keyboard

Most apps recreate their activity on rotation and save the field's text, composing text included,
before the input method hears of it; a tap on another field or app has the app finish it as plain
text first too. So only what is the text itself (English's word) goes
into the app as composing text; pinyin and a table's code show above the candidates
([architecture.md](architecture.md#a-key-from-the-finger-to-the-editor)). An input method whose preedit is a code must
not be added to `composesText`.

编码行 输入框里 (实验性, 0.6.1) writes the code into the field all the same, as the user's choice, under
one rule (dev/design/061-code-in-field.md §0): nothing the keyboard cannot prove it wrote is deleted or
replaced. A code in the field (`EditingSession.Kind.CODE`, `FieldCode`) ends one of two ways only: taken
away once read back character for character where it was written, else let go of as text
(`finishComposingText`). The read and the delete are both around the editor's own cursor
(`deleteSurroundingText` after the span is finished, or `setComposingText("")` on the span the editor
itself reports), never at the session's offsets, which run behind the editor's when reports come late
(`CodeInFieldPropertyTest` found an app's letter deleted that way). The lengths read and deleted are
still the session's: they hold only while the editor's cursor is the one the session thinks, so a code
is judged by a report only once the session has no write of its own still unreported (a report behind
the latest write judges nothing), and a write an editor has not yet reported reads the field first.
A read back proves the text, not its writer: an app that deletes part of the code and leaves its own
text there alike, cursor and all, has it taken away with the code (the property test leaves this out).
A read back alike does not prove the span is still there: an app that ends the composing text as the
field changes (a search box's `clearComposingText()`, posted) and whose report has not come by the
next key makes the next `setComposingText` an insert at the cursor, a copy of the code beside the letters
it left. Before a write whose last report is not in, `FieldCode.mayWrite` asks the editor's region at once
(`InputEditor.composingRegion`, Android 13's `takeSnapshot`; never called below 33, where it does not
exist) and floats the code when it is gone. That covers a write the editor has not reported yet only:
an `EditText` sends no `onUpdateSelection` at all when the app's `clearComposingText()` removes the span
(measured on the API 36 emulator, `finish_composing_async`), so with the last write reported the next goes
in unasked and a copy of the code as last written is left beside it, which floats from the next report
(LC-30): one letter where the app clears at every change (`nni`), the whole code typed before a pause
where it clears only after one (a debounce: `ABnihniha`).
`takeSnapshot` is only as good as the editor's own: an app's `InputConnection` written by hand (not over
`BaseInputConnection` and an `Editable`) may answer `null` (the interface's default, which is read as
"cannot say", the write goes ahead) or a snapshot whose composition is always -1 though it keeps one. The
second reads as the span gone: the code is taken away, proven ours, and floats the first time a key comes
before the last write's report, though nothing was wrong. Nothing is lost, but such a field falls back to the
strip on a fast typist; there is no way to tell it from an app that did end the span.
An app's own change whose report comes while a write of ours is still unreported matches no write the
session predicted, so it takes `FieldCode.reconcile`'s unpredicted path (the code released as text, the
engine told `CursorUpdate.FocusOutIn`) rather than being judged as a late report of our write: a code
being typed can be left in the field as text. Not verified on real Chrome.
The reads are synchronous, and a WebView answers them only when its renderer is free. Measured on the
API 36 emulator (2026-11-14, a debug build timing `placeCode` on the main thread; 16 letters by `input
keyevent`, four times): in a web field whose `input` handler takes 40 ms of JavaScript, the code in the
field spent a median 23 ms (p90 42) of the main thread per key, and a key reached the field a median
65 ms after it was pressed at a typist's pace (420 ms in a burst, keys queued behind the renderer); the
code on the strip, 0.05 ms and 6 ms (31 ms in a burst). In a plain web field: 0.5 ms and 17 ms against
0.05 ms and 7 ms. Most of it is the renderer's own time, which the field's own echo of each letter needs
anyway, but the strip never waits on it. Left as it is (061-code-in-field: the setting is experimental, and
the strip is the default). Asking the
snapshot before every write would close that, at one synchronous call per key.
A restart that is not the input method's own (`again`) may be another field under the same view, so
the code is let go of there unread. And `setComposingText("")` with no span left replaces the
selection: never send it to take away a code the editor no longer marks.

### A field that takes a commit and drops it

A WebView whose Android view lost the focus while the keyboard stayed up takes every write of the
connection and does nothing with it: `commitText` and `setComposingText` return true, no selection is
reported, reads answer with the text as it was, and the input method is told nothing (the client's
`mServedView` is still that view, the connection active, no `onStartInput`). Chromium's renderer drops
IME events for a widget without focus. Measured on the API 36 emulator (WebView 133, 2026-11-14,
`dumpsys activity top`): turning the screen with the keyboard up in webview_shell, where landscape
leaves the page 42 px under a 771 px keyboard, the WebView had no focus after 4 turns of 22 with nothing
typed, 3 of 5 in UAT LC-24, and never in 15 turns under Gboard (which leaves it 141 px); the three
turns that lost it each had a passing inset of the keyboard's first (window 2340x944, content top 173,
then 2340x1014 and 243). The view's height is thought to pass through nothing, at which Android clears
a view's focus and, in touch mode, gives it back to nobody (`View.sizeChange`; inferred, not seen in
the host). The focus stays lost after turning back; a tap on the page brings it back, with no new input
started.

Some fields use the text and empty themselves (terminals, remote desktops, scan or command boxes
that clear the buffer in the same batch edit). To tell them from a drop, a first commit whose text
before the cursor is not known is read again right after the write: text seen then and gone when the
wait ends was used, not dropped. A field that empties itself inside the write call itself still can't
be told from a drop. `kept` holds at most its last `CommitCheck.KEPT_MAX` (256) units, and a
surrogate pair is never cut (R26 F8168).

So every commit is checked (`CommitCheck`, `EditingSession.commitText`): the text before the cursor is
read before it (32 units), and it has landed when the editor reports the cursor where the commit was
to leave it. With no such report
800 ms after that commit (`CommitCheck.WAIT_MS`; the service's `judgeCommit` timer, set again for the
time left, `waitLeft`, where it was set for an earlier commit that reported or a field since left: an
editor whose reads lag a frame has not shown a commit just made) the text before the cursor is read
again: the same, and not already ending in what was committed, nothing went in. The text is then kept
(`CommitCheck.kept`, commits typed on into the same field added in order), the session's cursor put
back to the last one reported, and the code row says it (`FcitxInputMethodService.keptLine`, the
panel's line below, first, aux after it as formatted: 「没写进去：…，点一下输入框」, its last 24 units
where long, announced by the view that shows it). A tap on that line, that part of it alone, commits it
again (`writeKept`, the session's commit alone: the first try was already told to the journal and the
context), and it is kept again if that does not go in either; tapped while a later commit waits, it is
written once that wait ends (`CommitCheck.toWrite`, posted after whatever ended it, and only while input
is started: a finished connection drops it; or at once, before a commit made ahead of the post, not
before an address suggestion's replacement, whose units around the cursor were counted without it), and
that commit landing does not let it go meanwhile (written at once, it would join the commit waiting and be judged
after it, out of the order typed). It is let go when a
later commit lands (a report, or read back in), when input starts in a field that says it is another
(package, id, type, name or hint: a WebView's fields say nothing apart, and the screen turned or the
page tapped is the same field) and when the keyboard is put away (`onFinishInputView`).

What keeps it off fields that are only odd: the commit's own report ends the wait; a read that differs is
landed (an app that upper-cases, cuts short, or reports late: its reads are in order with its writes);
a read not given judges nothing. Not checked at all: a password or a field not to be learned from
(never read), a field of no type, and an editor that has not said where its cursor is (a terminal's or
a game's `BaseInputConnection` with no `Editable` reads back empty before and after a commit it did
take). Left as they are: an editor of a type and a cursor that empties itself at each commit would get
the line at every word (none known); a field that refuses the text and reports nothing (full to its
length, digits only) gets it too, which is true, and the tap does not help; a commit that is a row of
what already ends the field (哈 after 哈哈哈) cannot be told and is taken as landed; a cursor report
that is not the commit's while it waits (the field tapped within the 800 ms) leaves the cursor where no
read can tell, and that one commit is not judged (what was kept before stays; the next commit reads and
waits on its own). So too anything else done to the field while a commit waits (`CommitCheck.moved`): the session's deletions and cursor moves,
the service's key events (Backspace, Enter, arrows, lines, shortcuts, a physical key's press replayed), the
editor's action, a context-menu action, and input started anew in the same field (the app's
restartInput, its text set maybe): a commit then deleted, sent or cleared away reads back as before it
in an editor that reports late. Enter or send, a paste and a physical key's press replayed end the wait so as
Backspace does, and a kept text asked for is written after their effect. As any start of input in the same field ends the wait so, a commit
within 800 ms before the app's `restartInput`, or before a WebView's restart as the screen turns, is
not judged: dropped there, it is lost as before. The read before a commit is a synchronous binder
round trip that waits for the app's main thread (on the OnePlus 6T, in a page busy 12 ms of every 16,
7 ms at the median and 14 ms at p90 per commit, against 0.35 ms without it). So it is not made where
the text before the cursor is known: a commit that landed where it was to leave the cursor (reported)
leaves what was read before it and what it committed, and the next commit at that cursor, nothing
composing in the field, takes that instead (`CommitCheck.before`). Anything done to the field in
between (`moved`), another field (`forget`), a commit that is not a plain insertion at the cursor
(the cursor left inside it, a long text in pieces, a composition replaced) read again, and so does an
editor that gives less before the cursor than asked. An app's rewrite in place of the same length that
it does not report (a space made a no-break one, 「,」 made 「，」) leaves what is known stale till the
next `moved`: a commit dropped meanwhile reads back other than that and is not kept, as before this
check; dropped text is kept again once the next read is made. Typing on, a
read is made at the first commit after the field is started, tapped, deleted in or composed in; none
for a commit made while another waits, and none after it where the editor reports in time. A key the
engine hands back and the service replays to the app (a hardware key's digit, `KeyEventRelay`) is no
commit and is not checked: its press ends the wait as any key the service sends to the app does. Its release, which the engine
hands back for every key it took (the space that chose the word too), ends nothing: when it did,
every commit typed on a hardware keyboard lost its wait and read before it. A release that does
change the field is caught by its cursor report or by input started anew; a rewrite of the same
length at the same cursor is not, as for any edit of the app's own.

### `EditorInfo` decides a lot

Password, URL, email, search, no-suggestions and no-personalized-learning flags change what the
keyboard does (`EditorTraits`, `FieldPolicy`, `AddressKeys`, the Return key's action). Test a change
in a password field and an address field, not only a plain one.

"The same field" is not one rule across the app. A posted commit (`PostedCommit.lands`) goes in
only under the very `EditorInfo` it was asked under: a restart that brings a new one drops it.
Voice (`VoiceCommit.sameField`) takes a restart with the same app, view id, input type, field name
and hint as the same field, as `ContextMemory.field` names it, and goes on typing there. Left as
it is (a paste lost on a restart is rare and harmless); mind it when one of them changes.

### Restoring user data needs a new process

The host holds the config and the engine holds its logs open; replacing them under a running process
leaves it writing to the old files. A restore stops the host, replaces, then restarts the process.

### Android 10 lacks what lint does not see

minSdk is 29 and the tests ran at 35 alone till 0.5.1, which crashed on Android 10-12 twice: Kotlin's
`.use {}` on a `TypedArray` casts it to `AutoCloseable`, an interface it has only from 31
(`ClassCastException`, no lint warning), and `Paint.getFontMetricsInt(CharSequence, ...)` is 33
(`NoSuchMethodError`; lint's `NewApi`, now fatal). Never `.use {}` a framework type unless it is
`Closeable` at 29; recycle a `TypedArray` by hand. The tests at SDK 29 (testing.md) catch both.

### A long commit does not fit the binder

`InputConnection.commitText` is a one-way binder call, and one-way calls into a process share half
of its 1 MB binder buffer. A clip of 297 000 characters pasted from the clipboard panel (a 567 KB
parcel) failed with `FAILED BINDER TRANSACTION` in logcat and nothing in the field, while the
IME's cursor prediction moved on; the editor's own paste of it worked (it reads the clipboard
itself). Several one-way calls sent at once hold their buffers together until the editor's binder
thread has taken each (milliseconds; not until its main thread has handled it): 14 pieces of 32 K
sent back to back lost 3 in the middle, `commitText` returning false for those.
`EditingSession.commitText` sends a text of more than `CommitPieces.MAX` units in pieces a short
pause apart, sends a refused one again after a pause, and stops (predicting nothing more) when the
pauses are spent. Waiting on a read instead blocks the main thread as long as the editor lays the
text out, up to 2 s a read.

### A hidden keyboard is told GONE by hand

From Android 11 `InputMethodService.hideWindow` calls `dispatchWindowVisibilityChanged(GONE)` on the input view
and leaves the window alone; `showWindow` tells nothing back. The view hears VISIBLE again only from the window
itself, when it really went away in between. The app's screen remade (the system's dark mode switched)
hides and shows the keyboard at once with the window staying, and a view that stops on GONE (the theme's pet)
stayed stopped till the process died. `FcitxInputMethodService.onWindowShown` tells VISIBLE where the window
stayed since `onWindowHidden` (`InputViewShown`). `View.getWindowVisibility()` reads the window, not this.

### Direct boot

Before the first unlock there is no credential-protected storage: `EngineBridge.userDir()` is null,
learning stays in memory, and the keyboard reads the preferences copied to device-protected storage.
A new preference the keyboard needs at a lock screen must be in that copy.

A toast from the keyboard is dropped while the app may not post notifications, Android 13's default
(logcat: `Suppressing toast from package … by user request`): the input method is never the
foreground app. What the keyboard says goes in its notice instead, `FcitxInputMethodService.say`
(`tell` from another thread), see [Notices](app.md#notices). The settings' toasts are fine.

## Device testing

- `adb shell input text` goes around the input method: useless for testing typing. Tap keys.
- `uiautomator dump` does not see the keyboard's keys, popups or the candidate long-press menu (the
  IME window is not in its tree), and icon-only controls have no text. Use coordinates, screenshots,
  and the field's text read back.
- `am force-stop` of the app makes the system switch to another input method (the setup wizard then
  shows on the next launch). Use `ime set` again, or restart the activity instead.
- The debug build has a `.debug` package suffix: its IME id differs from the release's.
- `connectedAndroidTest` uninstalls the test APK at the end, and instrumentation restarts the app
  process at the start.
- The first `dumpsys meminfo` of the process can read high (237 MB, then 124 MB stable): sample
  several times before concluding.
- Coordinates measured on one screen size, orientation or theme are wrong on another.

## Data and formats

- `Syllables` is append-only; data files store syllable ids ([engine.md](engine.md#data-files-and-compatibility)).
- A data format change without a version bump is read as garbage by an old reader, or crashes on a
  key press. Bump, and refuse what cannot be read.
- User logs must stay readable by the next build and skippable by the previous one.
- Never write a mapped file in place (`merged.ymd`, the tables' `.ymc`): the engine reads it while
  you write, and a page changed under a mapping is garbage to it, or a SIGBUS once the file is
  shorter. Write `<name>.tmp`, fsync, rename over it; the old mapping keeps the old inode.
- A user's text dictionary is never parsed on the host thread at start: 0.3.1 did, and 1.9M words
  ran the 256 MB heap out at every focus. Compile it off the thread (`MergedDictionary`).
- Never wait on the dictionary compile (`refreshNow`, `refresh(…).get()`, `compiles.submit(…).get()`)
  while holding the sources lock (`ImportedDictionaries`' monitor): the compile thread takes it to
  commit a conversion and to sweep, so the wait deadlocks. Commit a source in a short locked block,
  leave it, then compile; gzip and deflate run outside the lock into a `.tmp`.
- A dictionary's source files (`.txt`, `.txt.gz`, on or off) are made, renamed and deleted under the
  sources lock, a restore's copy of them too (`UserDataManager.layOver`); `merged.ymd`, the
  `.merged.*` records, the compile's work files and the `.tmp`s written before a commit are not (the
  bullet above: a wait on the compile there deadlocks). A conversion that lists an older build's text
  as a copy made it, not yet written to, finds its length and time unchanged at the commit, commits it
  empty and deletes it under the write, its words gone; under the lock the commit waits for the copy
  and sees it changed.
- Never turn or delete a dictionary by the `File` a page listed: a conversion may have replaced
  `x.txt` by `x.txt.gz` since. Resolve the name under the lock.
- After an upgrade from 0.3.1/0.3.2 imported words are missing until the conversion and compile end
  (accepted).
- A downgrade to 0.3.2 lists no `.txt.gz`: imported base dictionaries are hidden there (the files
  stay; 0.3.2 deletes `merged.ymd` v2 and the `.ymc`s) and come back on re-upgrade; a same-name
  import made in 0.3.2 wins then (its `.txt` is converted over the `.gz`).
- The clipboard's `pinned` column holds 收藏 (the property is `favorite`). Renaming the column or bumping
  `clbdb`'s version makes 0.6.2 and before, restoring a newer backup, drop the whole clipboard: they
  fall back destructively on a downgrade.
- Data releases are pinned by SHA-256; a re-uploaded asset with the same name fails the build. Make
  a new release instead.

- A list page (`BaseDynamicListUi`) shows what it read from its file with `DynamicListAdapter.showAll`, never `addItems`: an add offers 「已添加 N 个项目 / 撤销」, and its undo deletes everything read (0.6.1 UAT, custom phrases and common words lost that way).

## Tests

- A class that reads `AppPrefs` in its initialiser fails in a JVM test until
  `AppPrefs.init(...)` is called; the message says so.
- `isReturnDefaultValues = false`: reaching an android.jar stub throws. Use Robolectric for that test.
- `FakeHostApi` simulates no input; a test that needs the engine to type belongs in ime-core
  against `Engines` / a `Session`, or in the instrumented tests.

## Shell on the dev machine

- zsh does not split a variable on spaces: `A="adb -s host:port"; $A shell …` fails. Put multi-word
  commands in a bash script or an array.
- `pgrep -f <pattern>` inside a wait loop matches the loop's own shell. Wait on a file marker or a
  PID instead.
