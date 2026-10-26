/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.uat

import org.fcitx.fcitx5.android.engine.host.EngineEvent
import org.fcitx.fcitx5.android.engine.host.Engines
import org.fcitx.fcitx5.android.engine.host.Keyboard
import org.fcitx.fcitx5.android.engine.session.Choice
import org.fcitx.fcitx5.android.engine.session.Offer
import org.fcitx.fcitx5.android.engine.session.Snapshot
import org.fcitx.fcitx5.android.host.CandidateEntry
import org.fcitx.fcitx5.android.host.ChineseModules
import org.fcitx.fcitx5.android.host.EngineHost
import org.fcitx.fcitx5.android.host.HostCore
import org.fcitx.fcitx5.android.host.HostEvent
import org.fcitx.fcitx5.android.host.HostStrings
import org.fcitx.fcitx5.android.host.ChineseConverter
import org.fcitx.fcitx5.android.host.FilePkgDataDir
import org.fcitx.fcitx5.android.host.Key
import org.fcitx.fcitx5.android.host.OpenCCData
import org.fcitx.fcitx5.android.host.KeySyms
import org.fcitx.fcitx5.android.host.ModuleInputs
import org.fcitx.fcitx5.android.host.Profile
import org.fcitx.fcitx5.android.host.PunctuationConfig
import org.fcitx.fcitx5.android.host.PunctuationProfile
import org.fcitx.fcitx5.android.host.QuickPhraseTable
import org.fcitx.fcitx5.android.host.VirtualHostClock
import org.fcitx.fcitx5.android.input.FieldPolicy
import org.fcitx.fcitx5.android.input.keyboard.PendingInputPolicy
import org.fcitx.fcitx5.android.input.editing.ContextMemory
import org.fcitx.fcitx5.android.input.editing.FakeEditor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import org.fcitx.fcitx5.android.host.KeyStates as HostKeyStates

/**
 * A uat/ case's 操作 replayed through the Kotlin host ([HostCore]) as the app runs it:
 * the same keys as [EngineReplay]'s (whose host has no modules), here with the rest of the host's
 * code (the punctuation module with the table the app ships, the panel, paging,
 * activate and deactivate), the refine timer a [VirtualHostClock] the user's pause runs out, and
 * the field a [FakeEditor] the host's events edit (commits, deletions around the cursor, text after
 * it) and that tells the engine what is around the cursor as the service does after each change.
 */
class HostReplay(
    val engines: Engines = UatEngines.fresh(),
    im: String = Engines.PINYIN,
    field: EngineReplay.Field = EngineReplay.Field.TEXT,
    /** How long a field's text is remembered ([ContextMemory]'s fresh): short in a case, as the engine's own timers run on the clock too. */
    freshMillis: Long = ContextMemory.FRESH_MILLIS,
    /** The input methods enabled, in their order: every listed one unless given (a parity file's header gives a device's). */
    private val inputMethods: List<String> = Engines.LISTED.map { it.name },
    /** The host's texts in the app's language: fcitx's English unless given (a parity replay gives the device's, [ResourceHostStrings]). */
    strings: HostStrings = HostStrings.ENGLISH,
    /** The first field's name, as the service names a field for its memory of it (ContextMemory.field). */
    private val firstField: String = "field1",
) {
    val clock = VirtualHostClock()

    // declared before init, whose activation already sends events through the port that reads them
    /** How long the engine takes over a refine slice and over any other event, on the [clock]: a device's pace. */
    var refineMillis = 0L
    var keyMillis = 0L

    /** The refine slices the router sent the engine: what a timer that ran was for. */
    var refines = 0
        private set

    // what the engine threw: the router catches it (KeyRouter.call) and goes on, so a case that expects
    // nothing would pass over a crash; thrown after the key or at the next pause instead
    private var crash: Throwable? = null

    var field: EngineReplay.Field = field
        private set

    /** The uid of the app focused. */
    var uid = 0
        private set

    var editor = FakeEditor()
        private set

    /** What the panel shows: the last preedit, and the last list sent. */
    var preedit: String = ""
        private set

    var page: List<String> = emptyList()
        private set

    var hasNextPage = false
        private set

    /**
     * The code row as the keyboard draws it (PreeditUi): the aux text above, then the preedit, a `|`
     * at its cursor unless the cursor is at the end.
     */
    var row: String = ""
        private set

    /** How many panel and candidate updates the host has sent: whether what is shown changed. */
    var panelUpdates = 0
        private set

    /**
     * How often [ime] went to another input method: the language key's presses each show fcitx's
     * overlay of the name on the bar (the device's panel shows it, on either host), which ends what
     * another app left shown there. The host here is made without it (showInputMethodInformation).
     */
    var switches = 0
        private set

    /**
     * How many hardware keys with a character were pressed: each shows the floating candidates
     * window over the bar (InputDeviceManager.evaluateOnKeyDown), until the next field's input view.
     */
    var floatingShown = 0
        private set

    /** The page as the bar shows each candidate: its text, and its comment after a space if it has one. */
    var shown: List<String> = emptyList()
        private set

    val commits = ArrayList<String>()

    private val port = object : EngineHost {
        // every call the host makes into the engines: a throw from the bar's paging is a crash as much as a key's
        private fun <T> caught(call: () -> T): T =
            try {
                call()
            } catch (@Suppress("TooGenericExceptionCaught") e: Throwable) {
                crash = crash ?: e
                throw e
            }
        override fun event(im: String, event: Int, arg: Int, learning: Boolean): Snapshot =
            caught { engines.onEvent(im, event, arg, learning) }.also {
                if (event == EngineEvent.REFINE) refines++
                clock.spend(if (event == EngineEvent.REFINE) refineMillis else keyMillis)
            }
        override fun candidates(im: String, from: Int, count: Int): List<Choice> = caught { engines.candidates(im, from, count) }
        override fun offers(im: String, index: Int): Set<Offer> = caught { engines.offers(im, index) }
        override fun settings(flat: String) = Unit
    }

    private val modules = ChineseModules(
        ModuleInputs(
            punctuationProfile = PunctuationProfile.parse(PUNCTUATION),
            // the app writes HalfWidthPuncAfterLetterOrNumber=False into punctuation.conf (AddonDefaults)
            punctuationConfig = PunctuationConfig(halfWidthAfterLatinOrNumber = false),
            converter = { CONVERTER },
            quickPhraseTable = QUICK_PHRASES,
            charSelectData = { CHAR_SELECT },
        ),
        strings,
    )

    val core = HostCore(
        port, clock, Profile.load(null, Engines.LISTED.map { it.name }.toSet(), "Default"),
        firstRun = true, showInputMethodInformation = false, modules = modules::build,
    )

    val im: String get() = core.currentInputMethod()

    /** The field's text, all of it. */
    val text: String get() = editor.text

    /** The candidates among them all (the engine's list, as [EngineReplay.candidates]). */
    val candidates: List<String> get() = engines.candidates(im, 0, ALL).map { it.text }

    // ------------------------------------------------------------------ the field

    /** The field left by the last [focus], as it was left. */
    var left: String? = null
        private set

    /**
     * A new field focused with [before] and [after] the cursor. The old one loses focus as the
     * service leaves it: fcitx commits nothing, the app's field keeping the client preedit as its
     * composing text (ClientUnfocusCommit, instance.cpp:1131-1138; the app finishes the composing
     * text, FcitxInputMethodService.finishComposing), and androidengine drops what was typed
     * (androidengine.cpp:765-771).
     */
    fun focus(
        before: String,
        field: EngineReplay.Field = this.field,
        after: String = "",
        // null: named by its app's uid, the one given (a field of another app by its own), else the next new one's
        name: String? = null,
        /** The uid of the app the field is in (one focused before: its input context again); a new app's unless given. */
        app: Int? = null,
    ) {
        if (uid > 0) {
            // the service finishes the composing text as it leaves the field, before fcitx loses focus
            editor.finishComposingText()
            core.focus(false)
            core.deactivate(uid)
            left = editor.text
        }
        this.field = field
        editor = FakeEditor(before + after, before.length)
        uid = app ?: (uid + 1)
        core.activate(uid)
        core.setCapabilities(field.capabilities)
        core.focus(true)
        preedit = ""
        row = ""
        clientPreedit = ""
        page = emptyList()
        shown = emptyList()
        policy.focusAnother(name ?: "field$uid", password = field == EngineReplay.Field.PASSWORD, sensitive = field.sensitive)
        // the new field is told below: a switch the activation made needs no telling of its own
        switched = false
        told(started = true)
        pause()
    }

    /** Whether the editor itself answered the last context told ([Action.Context.said]), as the service tells it. */
    var contextSaid: Boolean = false
        private set

    /** What the engine was last told is before the cursor. */
    var contextTold: String = ""
        private set

    /** The user is away [millis]. */
    fun away(millis: Long) = clock.advance(millis)

    /** The code shown in the field (the client preedit, with the preedit capability). */
    var clientPreedit: String = ""
        private set

    /** The candidate list paged by the host (the floating window's and the hardware keyboard's mode) rather than by the bar. */
    fun pagedList() {
        core.setCandidatePagingMode(1)
    }

    // what the service decides of each field, and what it remembers across them (FieldPolicy.context)
    private val memory = ContextMemory(fresh = freshMillis)
    private val policy = FieldPolicy(memory)

    // the service's commitText: the field and the memory of it, nothing told (the engine knows its own
    // commits; the app tells it only on a cursor report it did not expect, tellContextLater)
    private fun commit(text: String) {
        editor.commitText(text, 1)
        memory.committed(text, -1, clock.uptimeMillis())
    }
    private var recall = false

    // a hardware key being sent: one handed back is the editor's own key event (the service replays it), not the screen's
    private var pressing = false

    // an input method change the policy has the session told the field for (FcitxInputMethodService's IMChangeEvent)
    private var switched = false

    init {
        core.events.handlers += ::apply
        // every listed input method in the group, as a user who enabled them all: any can be switched to
        core.setInputMethods(inputMethods)
        focus("", name = firstField)
        // on the field's context, as the language key switches (fcitx switches the active context's)
        core.setCurrentInputMethod(im)
        check(core.currentInputMethod() == im) { "$im not active" }
        // the field focused again with [im] current, as the app's first field is when [im] was left on:
        // its session made at the activation and told the field's start (English ranks a sentence's
        // first word by it), not made later by the switch with no context
        if (im != inputMethods.first()) focus("", app = uid, name = firstField) else pause()
    }

    /** The field tapped where the cursor is: the session reset, the engine told the field again, what it offers shown (RECALL). */
    fun tapField() {
        core.reset()
        told()
        recalled()
        pause()
    }

    /**
     * How many Enters a single-line field took as its editor action (EditorKeyPolicy's
     * PerformEditorAction): the EditText's Done puts the keyboard away.
     */
    var editorActions = 0
        private set

    /**
     * The keyboard put away in the field (Back, an editor action): the service's onFinishInputView,
     * the composing text finished, fcitx focused out and in, the engine told it paused.
     */
    fun keyboardHidden() {
        editor.finishComposingText()
        core.focusOutIn()
        engines.paused()
    }

    /**
     * The keyboard brought back in the field it was put away in: the field told anew
     * (onStartInputView's contextDropped). [tapped] at the end of its text, as a tap on a short field
     * puts the cursor there: a cursor report not predicted, as [tapField].
     */
    fun keyboardShown(tapped: Boolean) {
        told()
        if (tapped) tappedAtEnd()
    }

    /** The field tapped at the end of its text: the cursor put there, if not there already, as [tapField]. */
    fun tappedAtEnd() {
        val end = editor.text.length
        if (editor.selectionStart == end && editor.selectionEnd == end) return
        editor.setSelection(end, end)
        tapField()
    }

    /** The keyboard put away and brought back with a tap on the field, the cursor already at its end. */
    fun keyboardHiddenAndShown() {
        keyboardHidden()
        keyboardShown(tapped = true)
        pause()
    }

    /** Another input method by its name, as the language key picks it. */
    fun ime(name: String) {
        if (name != im) switches++
        core.setCurrentInputMethod(name)
        pause()
    }

    // ------------------------------------------------------------------ keys

    /** [keys] tapped [gapMillis] apart, as a device's driver taps them; then the user pauses. */
    fun typeAt(keys: String, gapMillis: Long) {
        keys.codePoints().forEach {
            key(it)
            clock.advance(gapMillis)
        }
        pause()
    }

    /**
     * [keys] tapped one after another, as a device's driver taps them: each `input tap` a process of
     * its own, a few hundred milliseconds, so the refine after each key runs out before the next
     * (the engine's sentences after a key depend on how far the last ones were refined).
     */
    fun type(keys: String) {
        keys.codePoints().toArray().forEachIndexed { i, key ->
            if (i > 0) clock.advance(TAP_MILLIS)
            key(key)
        }
        pause()
    }

    fun space() = tap(EngineReplay.SPACE)

    /** A character written on the pad, read as [readings] (as EngineReplay.write: the recognizer's readings, then the ink key). */
    fun write(vararg readings: String) {
        lastPiece = UatEngines.offerInk(engines, readings.toList(), lastPiece)
        key(EngineReplay.symOf(Keyboard.INK), HostKeyStates.VIRTUAL)
        pause()
    }

    private var lastPiece = -1
    fun backspace(times: Int = 1) = repeat(times) { tap(EngineReplay.BACKSPACE) }
    fun enter() = tap(EngineReplay.RETURN)
    fun pageDown() {
        core.offsetCandidatePage(1)
        pause()
    }

    /** The bar's [index]th candidate tapped, as shown (converted, with 繁 on): what the bar sends, its index and text. */
    fun pickShown(index: Int) {
        val text = page[index]
        assertTrue("「$text」 not taken at $index of $page", core.select(index, text))
        recalled()
        pause()
    }

    fun pick(text: String) {
        val index = candidates.indexOf(text)
        assertTrue("「$text」 not among ${candidates.take(SHOWN)}", index >= 0)
        assertTrue("「$text」 not taken at $index", core.select(index, text))
        recalled()
        pause()
    }

    /** The status area's entry [name] tapped (chttrans, punctuation, fullwidth); nothing if the input method has none. */
    fun toggle(name: String) {
        core.statusArea().firstOrNull { it.name == name }?.let { core.activateAction(it.id) }
        pause()
    }

    /** Quick input opened (the 符 key held). */
    fun quick() {
        commitAndReset()
        core.triggerQuickPhrase()
        pause()
    }

    /** Unicode search opened (the 123 key held). */
    fun unicode() {
        commitAndReset()
        core.triggerUnicode()
        pause()
    }

    // what the keyboard's 符 and 123 keys do first (CommonKeyActionListener.afterPendingInput): what
    // is typed resolved as PendingInputPolicy says, then fcitx's reset, which leaves English knowing
    // nothing of the text before the cursor (FcitxInputMethodService.commitAndReset)
    private fun commitAndReset() {
        val language = Engines.listed(im)?.language.orEmpty()
        val address = field == EngineReplay.Field.URL || field == EngineReplay.Field.EMAIL
        when (PendingInputPolicy.resolve(language, hasPreedit = clientPreedit.isNotEmpty() || preedit.isNotEmpty(), address = address)) {
            PendingInputPolicy.Resolution.SelectFirstCandidate -> core.select(0)
            // the service's sendKey(FcitxKey_Return): no virtual state, a key of its own
            PendingInputPolicy.Resolution.CommitAsTyped -> {
                pressing = true
                try {
                    core.sendKey(Key(KeySyms.RETURN))
                } finally {
                    pressing = false
                }
            }
            PendingInputPolicy.Resolution.FinishComposing -> editor.finishComposingText()
            PendingInputPolicy.Resolution.None -> {}
        }
        core.reset()
    }

    /** A hardware key pressed and let go, as `adb shell input keyevent` sends it; one no one takes the app's. */
    fun hardware(sym: Int) {
        if (sym == KeySyms.RETURN) floatingShown++
        pressing = true
        try {
            core.sendKey(Key(sym))
            core.sendKey(Key(sym), release = true)
        } finally {
            pressing = false
        }
        recalled()
        pause()
    }

    /** A key the screen's keyboard sends; one no one takes comes back as a key event, typed by the app as it is. */
    fun key(sym: Int, states: Int = 0) {
        core.sendKey(Key(sym, states), virtual = true)
        recalled()
    }

    // what the engine asked to show again after it was told the context: the service's RECALL key,
    // sent after the event that told it (Fcitx.engineContext), not inside it
    private fun recalled() {
        // the service's tellContext on an IMChangeEvent, a job after the switch's
        if (switched) {
            switched = false
            told(started = true)
        }
        while (recall) {
            recall = false
            core.sendKey(Key(EngineReplay.UNICODE_KEYSYM + Keyboard.RECALL, HostKeyStates.VIRTUAL), virtual = true)
        }
        // after every key, not only at a pause: a case ending on a bare key() must not pass over a crash (F4304)
        rethrow()
    }

    private fun rethrow() {
        crash?.let { crash = null; throw AssertionError("the engine threw", it) }
    }

    private fun tap(sym: Int) {
        key(sym)
        pause()
    }

    /** The user pauses: the refine timer and the overlays run out. */
    fun pause() {
        recalled()
        clock.advance(PAUSE_MILLIS)
        rethrow()
    }

    // a key nothing took, as the service types it
    private fun app(sym: Int, virtual: Boolean) {
        when (sym) {
            EngineReplay.BACKSPACE -> if (editor.selectionEnd > 0) {
                val selected = editor.selectionStart != editor.selectionEnd
                val units = if (selected) 0 else Character.charCount(editor.text.codePointBefore(editor.selectionStart))
                // the screen's: what the memory of the field takes off, as handleBackspaceKey has it
                if (virtual) memory.backspace(editor.selectionStart, editor.selectionEnd)
                editor.deleteSurroundingText(1, 0, true)
                // the screen's Backspace key event has its cursor report predicted a unit back
                // (EditingSession.backspace): nothing told unless the editor took more (a surrogate
                // pair), or a selection, which handleBackspaceKey tells. A hardware one is the
                // editor's own, its report not predicted: the field told again (tellContextLater)
                if (!virtual || selected || units != 1) told()
            }
            // a key event the editor handles, its cursor report not predicted: the field told again
            // (tellContextLater). A new line only in a field that has lines (the parity editor's text
            // field; its password, URL and email fields are single-line, where Enter is the editor action)
            EngineReplay.RETURN -> if (field == EngineReplay.Field.TEXT) {
                editor.commitText("\n", 1)
                told()
            } else {
                editorActions++
            }
            // an arrow no one took moves the field's cursor, as an EditText's does: a cursor report the
            // service did not expect, so it tells the engine the field again (tellContextLater)
            KeySyms.LEFT -> editor.selectionStart.let { if (it > 0) moved(it - 1) }
            EngineReplay.RIGHT -> editor.selectionEnd.let { if (it < editor.text.length) moved(it + 1) }
            else -> KeySyms.toUnicode(sym).takeIf { it >= EngineReplay.SPACE }?.let { commit(String(Character.toChars(it))) }
        }
    }

    private fun moved(cursor: Int) {
        editor.setSelection(cursor, cursor)
        told()
    }

    private fun apply(event: HostEvent) {
        when (event) {
            is HostEvent.Commit -> {
                commit(event.text)
                commits += event.text
            }
            is HostEvent.DeleteSurrounding -> {
                // in code points, as the service deletes (EditingSession.deleteSurrounding, inCodePoints = true)
                editor.deleteSurroundingText(event.before, event.after, true)
                // handleDeleteSurrounding: each one off the memory too
                repeat(event.before) { memory.deleted() }
            }
            is HostEvent.ClientPreedit -> {
                clientPreedit = event.text.toString()
                // an empty one takes what was composing out, as EditingSession.updateComposingText does
                // (a commit before it has already replaced it)
                editor.setComposingText(clientPreedit)
            }
            is HostEvent.InputPanel -> {
                preedit = event.preedit.toString()
                val up = event.auxUp.toString() + preedit
                val cursor = if (event.preedit.cursor < 0) -1 else event.auxUp.toString().length + event.preedit.cursor
                row = if (cursor < 0 || cursor >= up.length) up else up.substring(0, cursor) + "|" + up.substring(cursor)
                panelUpdates++
            }
            is HostEvent.Candidates -> showList(event.candidates, hasNext = false)
            is HostEvent.Paged -> showList(event.candidates, event.hasNext)
            is HostEvent.Key -> if (!event.up) app(event.sym, virtual = !pressing)
            is HostEvent.InputMethodChanged -> {
                if (policy.inputMethodChanged(event.im)) switched = true
            }
            else -> {}
        }
    }

    private fun showList(candidates: List<CandidateEntry>, hasNext: Boolean) {
        panelUpdates++
        page = candidates.map { it.text }
        shown = candidates.map { if (it.comment.isEmpty()) it.text else "${it.text} ${it.comment}" }
        hasNextPage = hasNext
    }

    // the service tells the engine what is around the cursor after the field changed, through the
    // policy at the clock's time (an empty context for a password, the memory of a field gone back to)
    private fun told(started: Boolean = false) {
        val cursor = editor.selectionStart
        val text = editor.text
        val selected = editor.selectionEnd != cursor
        val c = policy.told(clock.uptimeMillis(), { text.substring(0, cursor) }, { text.substring(cursor) }, started, selected) ?: return
        contextTold = c.before
        contextSaid = c.said
        // every flag passed on, as the service's tell does (FcitxInputMethodService.tell)
        val offers = engines.context(
            im, c.before, learning = !policy.sensitive, cleared = c.cleared, learnFlags = c.learnFlags ?: 0,
            started = c.started, after = c.after, selected = c.selected, said = c.said,
        )
        if (offers) recall = true
    }

    // ------------------------------------------------------------------ expectations

    fun expectText(text: String) = assertEquals("the field", text, this.text)

    fun expectPreedit(preedit: String) = assertEquals("the code typed", preedit, this.preedit)

    fun expectFirst(first: String) = assertEquals("first of ${candidates.take(SHOWN)}", first, candidates.firstOrNull())

    private companion object {
        const val ALL = 200
        const val SHOWN = 12
        const val PAUSE_MILLIS = 5_000L
        const val TAP_MILLIS = 200L

        // fcitx's data as the app ships it (host-assets/pkgdata): quick phrases, charselectdata. Required: a
        // missing dir would replay quick input and Unicode with no data rather than fail
        private val FCITX5: File? get() = System.getProperty("uat.fcitx5")?.let(::File)
            ?.also { check(it.isDirectory) { "uat.fcitx5 $it is not a directory" } }

        val QUICK_PHRASES: QuickPhraseTable by lazy { FCITX5?.let { QuickPhraseTable.load(listOf(FilePkgDataDir(it))) } ?: QuickPhraseTable.EMPTY }

        val CHAR_SELECT: ByteArray? by lazy { FCITX5?.let { File(it, "unicode/charselectdata") }?.takeIf { it.isFile }?.readBytes() }

        // OpenCC s2tw as the app maps it (compileOpenccData, among uat.assets)
        val CONVERTER: ChineseConverter? by lazy {
            System.getProperty("uat.assets")?.split(File.pathSeparator)?.map { File(it, "engine/opencc-s2tw.data") }?.firstOrNull { it.isFile }
                ?.let { f -> RandomAccessFile(f, "r").use { ChineseConverter.s2tw(OpenCCData.read(it.channel.map(FileChannel.MapMode.READ_ONLY, 0, it.length()))) } }
        }

        // the punctuation table the app ships (chinese-addons' punc.mb.zh_CN)
        val PUNCTUATION: String by lazy {
            File(checkNotNull(System.getProperty("uat.punctuation")) { "no uat.punctuation given: run by Gradle" }).readText()
        }
    }
}
