/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.uat

import org.fcitx.fcitx5.android.engine.InputMethodCatalog
import org.fcitx.fcitx5.android.engine.glide.GlideLayout
import org.fcitx.fcitx5.android.engine.runtime.EngineSettings
import org.fcitx.fcitx5.android.engine.runtime.Engines
import org.fcitx.fcitx5.android.engine.runtime.Keyboard
import org.fcitx.fcitx5.android.engine.session.Action
import org.fcitx.fcitx5.android.engine.session.Choice
import org.fcitx.fcitx5.android.engine.session.Offer
import org.fcitx.fcitx5.android.engine.session.OfferedWord
import org.fcitx.fcitx5.android.engine.session.Snapshot
import org.fcitx.fcitx5.android.engine.session.WordOffer
import org.fcitx.fcitx5.android.host.CandidateEntry
import org.fcitx.fcitx5.android.host.ChineseConverter
import org.fcitx.fcitx5.android.host.ChineseModules
import org.fcitx.fcitx5.android.host.EngineHost
import org.fcitx.fcitx5.android.host.FilePkgDataDir
import org.fcitx.fcitx5.android.host.HostCore
import org.fcitx.fcitx5.android.host.HostEvent
import org.fcitx.fcitx5.android.host.HostEvents
import org.fcitx.fcitx5.android.host.HostStrings
import org.fcitx.fcitx5.android.host.Key
import org.fcitx.fcitx5.android.host.KeyRouter
import org.fcitx.fcitx5.android.host.KeyStates as HostKeyStates
import org.fcitx.fcitx5.android.host.KeySyms
import org.fcitx.fcitx5.android.host.ModuleInputs
import org.fcitx.fcitx5.android.host.OpenCCData
import org.fcitx.fcitx5.android.host.Profile
import org.fcitx.fcitx5.android.host.PunctuationConfig
import org.fcitx.fcitx5.android.host.PunctuationProfile
import org.fcitx.fcitx5.android.host.QuickPhraseTable
import org.fcitx.fcitx5.android.host.VirtualHostClock
import org.fcitx.fcitx5.android.input.FieldPolicy
import org.fcitx.fcitx5.android.input.editing.ContextMemory
import org.fcitx.fcitx5.android.input.editing.FakeEditor
import org.fcitx.fcitx5.android.input.keyboard.PendingInputPolicy
import org.fcitx.fcitx5.android.input.preedit.CodeRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileChannel

/** A uat/ case's 操作 replayed through the Kotlin host ([HostCore]) with its modules, a [VirtualHostClock] and a [FakeEditor] field. */
class HostReplay(
    val engines: Engines = UatEngines.fresh(),
    im: String = InputMethodCatalog.PINYIN,
    field: EngineReplay.Field = EngineReplay.Field.TEXT,
    /** [ContextMemory]'s fresh: short in a case, as the engine's own timers run on the clock too. */
    freshMillis: Long = ContextMemory.FRESH_MILLIS,
    /** The input methods enabled, in order (a parity file's header gives a device's). */
    private val inputMethods: List<String> = InputMethodCatalog.LISTED.map { it.name },
    /** fcitx's English unless given (a parity replay gives the device's, [ResourceHostStrings]). */
    strings: HostStrings = HostStrings.ENGLISH,
    /** The first field's name, as ContextMemory.field names it. */
    private val firstField: String = "field1",
) {
    val clock = VirtualHostClock()

    // the router catches an engine throw (KeyRouter.call) and goes on: rethrown after the key or at the next pause
    private var crash: Throwable? = null

    var field: EngineReplay.Field = field
        private set

    var uid = 0
        private set

    var editor = FakeEditor()
        private set

    var preedit: String = ""
        private set

    var page: List<String> = emptyList()
        private set

    var hasNextPage = false
        private set

    /** The code row as PreeditUi draws it: the aux text, then the preedit with `|` at its cursor unless at the end. */
    var row: String = ""
        private set

    var panelUpdates = 0
        private set

    /** How often [ime] switched input method: on a device each switch's name overlay ends what another app left on the bar. */
    var switches = 0
        private set

    /** Hardware keys with a character pressed: each shows the floating candidates window (InputDeviceManager.evaluateOnKeyDown). */
    var floatingShown = 0
        private set

    /** The page as the bar shows it: each text, its comment after a space if any. */
    var shown: List<String> = emptyList()
        private set

    val commits = ArrayList<String>()

    /** A table's words to remember as last told; null once withdrawn. */
    var offer: WordOffer? = null
        private set

    val remembered = ArrayList<OfferedWord>()

    private val port = object : EngineHost {
        // every call into the engines: a throw from the bar's paging is a crash as much as a key's
        private fun <T> caught(call: () -> T): T =
            try {
                call()
            } catch (@Suppress("TooGenericExceptionCaught") e: Throwable) {
                crash = crash ?: e
                throw e
            }
        override fun event(im: String, event: Int, arg: Int, learning: Boolean, payload: Action?): Snapshot =
            caught { engines.onEvent(im, event, arg, learning, payload) }
        override fun candidates(im: String, from: Int, count: Int): List<Choice> = caught { engines.candidates(im, from, count) }
        override fun offers(im: String, index: Int): Set<Offer> = caught { engines.offers(im, index) }
        override fun settings(settings: EngineSettings) = Unit
    }

    private val modules = ChineseModules(
        ModuleInputs(
            punctuationProfile = PunctuationProfile.parse(PUNCTUATION),
            punctuationConfig = PunctuationConfig(halfWidthAfterLatinOrNumber = false),
            converter = { CONVERTER },
            quickPhraseTable = QUICK_PHRASES,
            charSelectData = { CHAR_SELECT },
        ),
        strings,
    )

    val core = HostCore(
        port, clock, Profile.load(null, InputMethodCatalog.LISTED.map { it.name }.toSet(), "Default"),
        firstRun = true, showInputMethodInformation = false, modules = modules::build,
    )

    val im: String get() = core.currentInputMethod()

    val text: String get() = editor.text

    val candidates: List<String> get() = engines.candidates(im, 0, ALL).map { it.text }

    var left: String? = null
        private set

    /** A new field focused; the old one loses focus as the service leaves it (fcitx commits nothing, the app finishes composing). */
    fun focus(
        before: String,
        field: EngineReplay.Field = this.field,
        after: String = "",
        // null: the given app's uid names it, else the next new one's
        name: String? = null,
        /** The field's app (one focused before: its input context again); a new app's unless given. */
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
        switched = false
        told(started = true)
        pause()
    }

    /** Whether the editor itself answered the last context told ([Action.Context.said]). */
    var contextSaid: Boolean = false
        private set

    var contextTold: String = ""
        private set

    fun away(millis: Long) = advance(millis)

    // every wait moves both clocks: engine timing (a table's space after a commit) sees a device's gaps
    private fun advance(millis: Long) {
        clock.advance(millis)
        UatEngines.Clock.away(millis)
    }

    /** The client preedit (with the preedit capability). */
    var clientPreedit: String = ""
        private set

    /** The candidate list paged by the host (floating window, hardware keyboard) rather than by the bar. */
    fun pagedList() {
        core.setCandidatePagingMode(1)
    }

    private val memory = ContextMemory(fresh = freshMillis)
    private val policy = FieldPolicy(memory)

    // the service's commitText: field and memory, nothing told (the engine knows its own commits)
    private fun commit(text: String) {
        editor.commitText(text, 1)
        memory.committed(text, -1, clock.uptimeMillis())
    }
    private var recall = false

    // a hardware key being sent: one handed back is the editor's own key event, not the screen's
    private var pressing = false

    // a switch the session told the field for (FcitxInputMethodService's IMChangeEvent)
    private var switched = false

    init {
        core.events += HostEvents(::apply)
        // the router's half of HostCore.configure: without it English suggests regardless of settings
        core.router.options = KeyRouter.Options(engines.settings.pageSize, engines.settings.englishSuggest)
        core.setInputMethods(inputMethods)
        focus("", name = firstField)
        // on the field's context, as the language key switches (fcitx switches the active context's)
        core.setCurrentInputMethod(im)
        check(core.currentInputMethod() == im) { "$im not active" }
        // the first field focused again so its session gets the field's start, not the switch's empty context
        if (im != inputMethods.first()) focus("", app = uid, name = firstField) else pause()
    }

    /** The field tapped at the cursor: session reset, the engine told the field again (RECALL). */
    fun tapField() {
        core.reset()
        told()
        recalled()
        pause()
    }

    /** Enters a single-line field took as its editor action (EditorKeyPolicy's PerformEditorAction). */
    var editorActions = 0
        private set

    /** The keyboard put away (Back, an editor action): onFinishInputView, composing finished, fcitx focused out and in. */
    fun keyboardHidden() {
        editor.finishComposingText()
        core.focusOutIn()
    }

    /** The keyboard brought back: the field told anew. [tapped]: at its text's end, an unpredicted cursor report, as [tapField]. */
    fun keyboardShown(tapped: Boolean) {
        told()
        if (tapped) tappedAtEnd()
    }

    /** The field tapped at the end of its text, as [tapField]. */
    fun tappedAtEnd() {
        val end = editor.text.length
        if (editor.selectionStart == end && editor.selectionEnd == end) return
        editor.setSelection(end, end)
        tapField()
    }

    fun keyboardHiddenAndShown() {
        keyboardHidden()
        keyboardShown(tapped = true)
        pause()
    }

    fun ime(name: String) {
        if (name != im) switches++
        core.setCurrentInputMethod(name)
        pause()
    }

    /** [keys] tapped one by one as a device's driver does, then a pause. */
    fun type(keys: String) {
        keys.codePoints().toArray().forEachIndexed { i, key ->
            if (i > 0) advance(TAP_MILLIS)
            key(key)
        }
        pause()
    }

    fun space() = tap(EngineReplay.SPACE)

    /** A character written on the pad, read as [readings] (the recognizer's readings, then the ink key). */
    fun write(vararg readings: String) {
        val ink = UatEngines.ink(engines, im, readings.toList(), lastPiece)
        lastPiece = ink.piece
        key(EngineReplay.symOf(Keyboard.INK), HostKeyStates.VIRTUAL, payload = ink)
        engines.inkSent(ink, im)
        pause()
    }

    private var lastPiece = -1

    /** A glide across [keys]' centres on [layout], as the keyboard hands it over: the GLIDE key (KotlinHost.offerGlide). */
    fun glide(keys: String, layout: GlideLayout = GlideLayout.QWERTY, shift: Boolean = false) {
        key(EngineReplay.symOf(Keyboard.GLIDE), HostKeyStates.VIRTUAL, payload = UatEngines.glide(engines, im, keys, layout, shift))
        pause()
    }

    /** The code row tapped at [offset] into [row] (the `|` counts): the cursor placed as CodeRow.tapAt finds it, sent via HostCore.moveCursor. */
    fun tapRow(offset: Int) {
        core.moveCursor(CodeRow.tapAt(offset, rowCursor, rowAux, preedit))
        recalled()
        pause()
    }

    fun tapRowAfter(text: String) {
        val at = row.indexOf(text)
        assertTrue("「$text」 not in the row 「$row」", at >= 0)
        tapRow(at + text.length)
    }

    fun tapRowEnd() = tapRow(row.length)

    /** [digits] tapped on pinyin's nine keys as [type] taps, each as T9Keyboard sends it ([Keyboard.pinyinNineKey]); 1 is the separator. */
    fun pinyinNine(digits: String) {
        digits.forEachIndexed { i, d ->
            if (i > 0) advance(TAP_MILLIS)
            key(EngineReplay.symOf(Keyboard.pinyinNineKey(d)))
        }
        pause()
    }

    /** Codes as the screen sends them, each a character with no keysym of its own. */
    fun keys(codes: String) {
        codes.forEach { key(EngineReplay.symOf(it.code)) }
        pause()
    }

    // the row's aux length and cursor as drawn (-1 at the end), as CodeRowUi keeps them for a tap
    private var rowAux = 0
    private var rowCursor = -1

    fun backspace(times: Int = 1) = repeat(times) { tap(EngineReplay.BACKSPACE) }

    /** The offer's [index]th tapped on the notice strip, the field's text before the cursor read then. */
    fun remember(index: Int) {
        val o = offer ?: throw AssertionError("nothing offered")
        key(EngineReplay.symOf(Keyboard.REMEMBER), HostKeyStates.VIRTUAL, Action.Remember(o.id, index, editor.text.substring(0, editor.selectionStart)))
        pause()
    }

    /** The notice's 撤销 after a word remembered. */
    fun unremember(word: OfferedWord) {
        key(EngineReplay.symOf(Keyboard.REMEMBER), HostKeyStates.VIRTUAL, Action.Unremember(word.text, word.code))
        pause()
    }
    fun enter() = tap(EngineReplay.RETURN)

    /** A chat box's send: Enter empties the box with no new input started, so the service tells the field again. */
    fun send() {
        sending = true
        try {
            tap(EngineReplay.RETURN)
        } finally {
            sending = false
        }
    }

    // only while [send]'s Enter goes through: Enter is then the page's send, not a new line
    private var sending = false

    fun pageDown() {
        core.offsetCandidatePage(1)
        pause()
    }

    /** The bar's [index]th candidate tapped as shown (converted, with 繁 on). */
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

    /** The status area's entry [name] tapped (chttrans, punctuation, fullwidth); nothing if the input method lacks it. */
    fun toggle(name: String) {
        core.statusArea().firstOrNull { it.name == name }?.let { core.activateAction(it.id) }
        pause()
    }

    fun quick() {
        commitAndReset()
        core.triggerQuickPhrase()
        pause()
    }

    fun unicode() {
        commitAndReset()
        core.triggerUnicode()
        pause()
    }

    /** What the 符 and 123 keys do first: the pending input resolved per PendingInputPolicy, then fcitx's reset. */
    fun commitAndReset(): PendingInputPolicy.Resolution {
        val language = InputMethodCatalog.listed(im)?.language.orEmpty()
        val address = field == EngineReplay.Field.URL || field == EngineReplay.Field.EMAIL
        val resolution = PendingInputPolicy.resolve(language, hasPreedit = clientPreedit.isNotEmpty() || preedit.isNotEmpty(), address = address)
        when (resolution) {
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
        return resolution
    }

    /** Text the keyboard commits over the engine (a long press's letter, .com, @), as FcitxInputMethodService.commitOverEngine. */
    fun commitOverEngine(text: String): PendingInputPolicy.Resolution {
        val resolution = commitAndReset()
        editor.commitText(text, 1)
        pause()
        return resolution
    }

    /** A hardware key pressed and released, as `input keyevent` sends it; one no one takes goes to the app. */
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

    /** A screen key; one no one takes comes back as a key event the app types. */
    fun key(sym: Int, states: Int = 0, payload: Action? = null) {
        core.sendKey(Key(sym, states), virtual = true, payload = payload)
        recalled()
    }

    // the service's RECALL after it told the context, sent after that event (Fcitx.engineContext), not inside it
    private fun recalled() {
        if (switched) {
            switched = false
            told(started = true)
        }
        while (recall) {
            recall = false
            core.sendKey(Key(EngineReplay.UNICODE_KEYSYM + Keyboard.RECALL, HostKeyStates.VIRTUAL), virtual = true)
        }
        // after every key, not only at a pause: a case ending on a bare key() must not pass over a crash
        rethrow()
    }

    private fun rethrow() {
        crash?.let { crash = null; throw AssertionError("the engine threw", it) }
    }

    private fun tap(sym: Int) {
        key(sym)
        pause()
    }

    fun pause() {
        recalled()
        advance(PAUSE_MILLIS)
        rethrow()
    }

    private fun app(sym: Int, virtual: Boolean) {
        when (sym) {
            EngineReplay.BACKSPACE -> if (editor.selectionEnd > 0) {
                val selected = editor.selectionStart != editor.selectionEnd
                val units = if (selected) 0 else Character.charCount(editor.text.codePointBefore(editor.selectionStart))
                // the screen's: off the field's memory too, as handleBackspaceKey does
                if (virtual) memory.backspace(editor.selectionStart, editor.selectionEnd)
                editor.deleteSurroundingText(1, 0, true)
                // the screen's Backspace has its cursor report predicted a unit back: told only if the editor took more
                if (!virtual || selected || units != 1) told()
            }
            // a key event the editor handles, its report not predicted; single-line fields take Enter as the editor action
            EngineReplay.RETURN -> if (sending) {
                editor.setSelection(0, editor.text.length)
                editor.commitText("", 1)
                told()
            } else if (field == EngineReplay.Field.TEXT) {
                editor.commitText("\n", 1)
                told()
            } else {
                editorActions++
            }
            // an arrow no one took moves the cursor as an EditText's does; an unpredicted report: told again
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
                // in code points, as the service deletes
                editor.deleteSurroundingText(event.before, event.after, true)
                repeat(event.before) { memory.deleted() }
            }
            is HostEvent.ClientPreedit -> {
                clientPreedit = event.text.toString()
                // an empty one clears the composing text, as EditingSession.updateComposingText does
                editor.setComposingText(clientPreedit)
            }
            is HostEvent.InputPanel -> {
                preedit = event.preedit.toString()
                val up = event.auxUp.toString() + preedit
                val cursor = if (event.preedit.cursor < 0) -1 else event.auxUp.toString().length + event.preedit.cursor
                row = if (cursor < 0 || cursor >= up.length) up else up.substring(0, cursor) + "|" + up.substring(cursor)
                rowAux = event.auxUp.toString().length
                rowCursor = if (cursor < 0 || cursor >= up.length) -1 else cursor
                panelUpdates++
            }
            is HostEvent.Candidates -> showList(event.candidates, hasNext = false)
            is HostEvent.Paged -> showList(event.candidates, event.hasNext)
            is HostEvent.Key -> if (!event.up) app(event.sym, virtual = !pressing)
            is HostEvent.InputMethodChanged -> {
                if (policy.inputMethodChanged(event.im)) switched = true
            }
            is HostEvent.WordOffer -> offer = event.offer
            is HostEvent.WordRemembered -> remembered += event.word
            else -> {}
        }
    }

    private fun showList(candidates: List<CandidateEntry>, hasNext: Boolean) {
        panelUpdates++
        page = candidates.map { it.text }
        shown = candidates.map { if (it.comment.isEmpty()) it.text else "${it.text} ${it.comment}" }
        hasNextPage = hasNext
    }

    // the service tells the engine the cursor's surroundings after a change, through the policy
    private fun told(started: Boolean = false) {
        val cursor = editor.selectionStart
        val text = editor.text
        val selected = editor.selectionEnd != cursor
        val c = policy.told(clock.uptimeMillis(), { text.substring(0, cursor) }, { text.substring(cursor) }, started, selected) ?: return
        contextTold = c.before
        contextSaid = c.said
        val offers = engines.context(
            im, c.before, learning = !policy.sensitive, cleared = c.cleared, learnFlags = c.learnFlags ?: 0,
            started = c.started, after = c.after, selected = c.selected, said = c.said,
        )
        if (offers) recall = true
    }

    fun expectText(text: String) = assertEquals("the field", text, this.text)

    fun expectPreedit(preedit: String) = assertEquals("the code typed", preedit, this.preedit)

    fun expectFirst(first: String) = assertEquals("first of ${candidates.take(SHOWN)}", first, candidates.firstOrNull())

    private companion object {
        const val ALL = 200
        const val SHOWN = 12
        const val PAUSE_MILLIS = 5_000L
        const val TAP_MILLIS = 200L

        // required, so a missing dir fails rather than replaying with no data
        private val FCITX5: File? get() = System.getProperty("uat.fcitx5")?.let(::File)
            ?.also { check(it.isDirectory) { "uat.fcitx5 $it is not a directory" } }

        val QUICK_PHRASES: QuickPhraseTable by lazy { FCITX5?.let { QuickPhraseTable.load(listOf(FilePkgDataDir(it))) } ?: QuickPhraseTable.EMPTY }

        val CHAR_SELECT: ByteArray? by lazy { FCITX5?.let { File(it, "unicode/charselectdata") }?.takeIf { it.isFile }?.readBytes() }

        // OpenCC s2tw as the app maps it (compileOpenccData)
        val CONVERTER: ChineseConverter? by lazy {
            System.getProperty("uat.assets")?.split(File.pathSeparator)?.map { File(it, "engine/opencc-s2tw.data") }?.firstOrNull { it.isFile }
                ?.let { f -> RandomAccessFile(f, "r").use { ChineseConverter.s2tw(OpenCCData.read(it.channel.map(FileChannel.MapMode.READ_ONLY, 0, it.length()))) } }
        }

        // the punctuation table the app ships (punc.mb.zh_CN)
        val PUNCTUATION: String by lazy {
            File(checkNotNull(System.getProperty("uat.punctuation")) { "no uat.punctuation given: run by Gradle" }).readText()
        }
    }
}
