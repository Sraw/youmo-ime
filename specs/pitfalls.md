# Pitfalls

Things that cost hours before. Each says how it shows and what to do.

## Build

### Kotlin IC and interface delegation

After adding a method to `FcitxAPI` (or any interface implemented elsewhere with `by`), the
incremental Kotlin build can leave a delegating class (`object : FcitxAPI by fcitx` in another
file) without the new method. It compiles; the app crashes with `AbstractMethodError` the first
time the method is called. Unit tests and Robolectric miss it (they use `FakeFcitxAPI`).
Before building an APK to test or ship after such a change, delete
`app/build/intermediates/built_in_kotlinc/<variant>` (and `app/build/kotlin/compile<Variant>Kotlin`
if present). Check with `javap`/`dexdump` that the delegate has the method.

### The engine data rebuilds

Editing anything under `build-logic` changes the data task's implementation, so the next build
compiles `pinyin.data` and the tables again (minutes, ~4 GB). Editing ime-core reruns it only if the
change reaches the dict tool's code (`ToolFingerprint`): `engine/data`, `pinyin`, parts of `user`,
`table`. Expected, not a bug.

### Memory

The data compile, a native rebuild and an emulator do not fit together in 15 GB. A stray
`KotlinCompileDaemon` (Xmx3g, idle for hours) came from build-logic once; it now compiles in
process. If memory looks high, `pgrep -fa KotlinCompileDaemon`.

### detekt sees syntax only

Type resolution does not work in this setup, so detekt rules that need types do not run. Do not
take a clean detekt as proof of type-level correctness.

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
text first too. So only what is the text itself (English's word, handwriting's characters) goes
into the app as composing text; pinyin and a table's code show above the candidates
([architecture.md](architecture.md#a-key-from-the-finger-to-the-editor)). An input method whose preedit is a code must
not be added to `composesText`.

### `EditorInfo` decides a lot

Password, URL, email, search, no-suggestions and no-personalized-learning flags change what the
keyboard does (`EditorTraits`, `FieldPolicy`, `AddressKeys`, the Return key's action). Test a change
in a password field and an address field, not only a plain one.

### Restoring user data needs a new process

fcitx holds the config and the engine holds its logs open; replacing them under a running process
leaves it writing to the old files. A restore stops fcitx, replaces, then restarts the process.

### Direct boot

Before the first unlock there is no credential-protected storage: `EngineBridge.userDir()` is null,
learning stays in memory, and the keyboard reads the preferences copied to device-protected storage.
A new preference the keyboard needs at a lock screen must be in that copy.

A toast from the keyboard is dropped while the app may not post notifications, Android 13's default
(logcat: `Suppressing toast from package … by user request`): the input method is never the
foreground app. What the keyboard says goes above it instead, `FcitxInputMethodService.say` (the
long press's undo chip with nothing to undo). The settings' toasts are fine.

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
- A downgrade to 0.3.0 while 0.3.1's first conversion is still owed (`learn.converting`) loses the
  older build's counts: when 0.3.1 is installed again it finds a log that is not empty and has no
  marker, takes only the words (`LegacyConversion.since`), and writes the marker. Rare, and accepted.
- Never write a mapped file in place (`merged.ymd`, the tables' `.ymc`): the engine reads it while
  you write, and a page changed under a mapping is garbage to it, or a SIGBUS once the file is
  shorter. Write `<name>.tmp`, fsync, rename over it; the old mapping keeps the old inode.
- A user's text dictionary is never parsed on the fcitx thread at start: 0.3.1 did, and 1.9M words
  ran the 256 MB heap out at every focus. Compile it off the thread (`MergedDictionary`).
- Data releases are pinned by SHA-256; a re-uploaded asset with the same name fails the build. Make
  a new release instead.

## Tests

- A class that reads `AppPrefs` in its initialiser fails in a JVM test until
  `AppPrefs.init(...)` is called; the message says so.
- `isReturnDefaultValues = false`: reaching an android.jar stub throws. Use Robolectric for that test.
- `FakeFcitxAPI` simulates no input; a test that needs the engine to type belongs in ime-core
  against `Engines` / a `Session`, or in the instrumented tests.

## Shell on the dev machine

- zsh does not split a variable on spaces: `A="adb -s host:port"; $A shell …` fails. Put multi-word
  commands in a bash script or an array.
- `pgrep -f <pattern>` inside a wait loop matches the loop's own shell. Wait on a file marker or a
  PID instead.
