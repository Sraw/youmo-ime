/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.uat

import org.fcitx.fcitx5.android.core.UNICODE
import org.fcitx.fcitx5.android.engine.InputMethodCatalog
import org.fcitx.fcitx5.android.engine.glide.GlideLayout
import org.fcitx.fcitx5.android.engine.learn.control.LearningOp
import org.fcitx.fcitx5.android.engine.learn.control.LearningResult
import org.fcitx.fcitx5.android.engine.runtime.EngineEvent
import org.fcitx.fcitx5.android.engine.runtime.EngineSettings
import org.fcitx.fcitx5.android.engine.runtime.Engines
import org.fcitx.fcitx5.android.engine.runtime.Keyboard
import org.fcitx.fcitx5.android.engine.session.Action
import org.fcitx.fcitx5.android.engine.session.Choice
import org.fcitx.fcitx5.android.engine.session.Offer
import org.fcitx.fcitx5.android.engine.session.Snapshot
import org.fcitx.fcitx5.android.host.Capability
import org.fcitx.fcitx5.android.host.EngineHost
import org.fcitx.fcitx5.android.host.HostCore
import org.fcitx.fcitx5.android.host.HostEvent
import org.fcitx.fcitx5.android.host.HostEvents
import org.fcitx.fcitx5.android.host.Key
import org.fcitx.fcitx5.android.host.KeyStates as HostKeyStates
import org.fcitx.fcitx5.android.host.KeySyms
import org.fcitx.fcitx5.android.host.Profile
import org.fcitx.fcitx5.android.host.VirtualHostClock
import org.fcitx.fcitx5.android.input.FieldPolicy
import org.fcitx.fcitx5.android.input.editing.ContextMemory
import org.fcitx.fcitx5.android.input.keyboard.PendingInputPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/** A uat/ case's 操作 replayed through [engines]: X keysyms in, [text], [preedit] and [candidates] out. */
class EngineReplay(
    engines: Engines = UatEngines.fresh(),
    im: String = InputMethodCatalog.PINYIN,
    field: Field = Field.TEXT,
) {
    enum class Field(val sensitive: Boolean) {
        TEXT(false),
        PASSWORD(true),
        URL(false),
        EMAIL(false),

        NO_SUGGESTIONS(false),
        /** IME_FLAG_NO_PERSONALIZED_LEARNING (an incognito tab's): fcitx's Sensitive, but a text field's keyboard. */
        NO_LEARNING(true),
        ;

        /** The capability flags the service sets (CapabilityFlag.kt). */
        val capabilities: Long get() = Capability.PREEDIT or Capability.CLIENT_UNFOCUS_COMMIT or when (this) {
            TEXT -> 0L
            PASSWORD -> Capability.PASSWORD
            URL -> Capability.URL
            EMAIL -> Capability.EMAIL
            // TYPE_TEXT_FLAG_NO_SUGGESTIONS is NoSpellCheck, IME_FLAG_NO_PERSONALIZED_LEARNING Sensitive
            NO_SUGGESTIONS -> Capability.NO_SPELL_CHECK
            NO_LEARNING -> Capability.SENSITIVE
        }
    }

    var engines: Engines = engines
        private set

    var im: String = im
        private set

    var field: Field = field
        private set

    private val before = StringBuilder()

    var panel: Snapshot = IDLE
        private set

    val commits = ArrayList<String>()

    private val clock = VirtualHostClock()

    private val port = object : EngineHost {
        override fun event(im: String, event: Int, arg: Int, learning: Boolean, payload: Action?): Snapshot = try {
            this@EngineReplay.engines.onEvent(im, event, arg, learning, payload)
        } catch (@Suppress("TooGenericExceptionCaught") e: Throwable) {
            // the router catches it (KeyRouter.call) and goes on: thrown at the next flush instead
            crash = crash ?: e
            throw e
        }.also { s ->
                // KeyRouter.send's rule: a tap that changed nothing leaves the panel as it is
                if (event != EngineEvent.CURSOR || s.handled) panel = s
            }
        override fun candidates(im: String, from: Int, count: Int): List<Choice> = this@EngineReplay.engines.candidates(im, from, count)
        override fun offers(im: String, index: Int): Set<Offer> = this@EngineReplay.engines.offers(im, index)
        override fun settings(settings: EngineSettings) = Unit
    }

    private val core = HostCore(
        port, clock, Profile.load(null, InputMethodCatalog.LISTED.map { it.name }.toSet(), "Default"),
        firstRun = true, showInputMethodInformation = false,
    )

    // during a call: the field changed (told after it), the keys it did not take (the app's)
    private var changed = false
    private var crash: Throwable? = null
    private val handedBack = ArrayList<Int>()

    init {
        requirePlain(field)
        core.events += HostEvents(::apply)
        core.setInputMethods(InputMethodCatalog.LISTED.map { it.name })
        core.activate(UID)
        core.setCapabilities(field.capabilities)
        core.focus(true)
        core.setCurrentInputMethod(im)
        check(core.currentInputMethod() == im) { "$im not active" }
        flush()
        focus("")
    }

    /** The field's text before the cursor: all of it, as no case moves the cursor back. */
    val text: String get() = before.toString()

    val preedit: String get() = panel.preedit

    /** All candidates, not only the page: or the first [ALL] of a list not counted yet (total -1). */
    val candidates: List<String> get() = engines.candidates(im, 0, if (panel.total >= 0) panel.total else ALL).map { it.text }

    val page: List<String> get() = panel.candidates

    /** A field focused with [text], cursor at its end: the engine told, as at onStartInput. */
    fun focus(text: String, field: Field = this.field) {
        // fcitx resets the context a field focuses: the session made then, so it has a context to be told
        requirePlain(field)
        this.field = field
        core.setCapabilities(field.capabilities)
        core.reset()
        flush()
        before.setLength(0)
        before.append(text)
        engines.context(im, text, learning = !field.sensitive, started = true)
        panel = IDLE
    }

    // a password field switches the host to its fallback, which only HostReplay follows
    private fun requirePlain(field: Field) =
        require(field != Field.PASSWORD) { "a password field is HostReplay's: the host switches its input method" }

    /** The app force-stopped and reopened on this field: the user's files read afresh, the field focused anew. */
    fun restart() {
        engines = UatEngines.restart()
        panel = IDLE
        focus(text)
    }

    /** Text the app commits itself: in the field, the engine not told (it is told only on an unexpected cursor report). */
    fun commit(text: String) {
        before.append(text)
    }

    /** Text put in the field by other means (a paste): the engine told, as an unexpected cursor report tells it. */
    fun paste(text: String) {
        before.append(text)
        tell()
    }

    // the field read again as at each selection update: a sensitive field's text not read
    private fun tell() {
        if (field.sensitive) engines.context(im, "", learning = false)
        else engines.context(im, before.toString(), learning = true, after = "", said = true)
    }

    /** The field emptied by the app, as a chat box once sent: Enter, then the engine told, what was written remembered. */
    fun sent() {
        sending = true
        try {
            enter()
        } finally {
            sending = false
        }
        val now = memoryClock++
        // a sensitive field's text neither remembered nor told; never a password's (requirePlain sends it to HostReplay)
        policy.focusAnother(FIELD, password = false, sensitive = field.sensitive)
        memory.context(before.toString(), now) { "" }
        before.setLength(0)
        panel = IDLE
        val told = checkNotNull(policy.told(now, { "" }, { "" }))
        engines.context(im, told.before, learning = !field.sensitive, cleared = told.cleared, after = told.after, said = told.said)
    }

    private var sending = false

    private val memory = ContextMemory()
    private val policy = FieldPolicy(memory)
    private var memoryClock = 1L

    /** A long press's [op] on a candidate (KotlinHost.learning), sent only if the menu offers it on the candidate still shown. */
    fun learn(op: LearningOp): LearningResult =
        engines.learning(im, op, core::shownText) { event, index, text -> core.actOnCandidate(index, event, text).also { flush() } }.also { pause() }

    /** A glide across [keys]' centres on [layout], as the keyboard hands it to the engine (UatEngines.glide). */
    fun glide(keys: String, layout: GlideLayout = GlideLayout.QWERTY, shift: Boolean = false) {
        key(symOf(Keyboard.GLIDE), virtual = true, payload = UatEngines.glide(engines, im, keys, layout, shift))
        pause()
    }

    /** [strokes] drawn on the keyboard (笔画筛选), `h s p n z` for 横竖撇点折 (Keyboard.strokeKey). */
    fun stroke(strokes: String) {
        for (c in strokes) key(symOf(Keyboard.strokeKey(c)), virtual = true)
        pause()
    }

    /** The reading shown above the candidate [text], syllables space-separated, "" for none. */
    fun readingOf(text: String): String = choice(text).readings.joinToString(" ")

    /** The hint beside the candidate [text] (a table's code, an emoji's name), "" for none. */
    fun hintOf(text: String): String = choice(text).hint

    private fun choice(text: String) = engines.candidates(im, 0, if (panel.total >= 0) panel.total else ALL).first { it.text == text }

    /** Another input method, as the globe or language key switches: what was typed committed as typed. */
    fun ime(name: String) {
        if (name == im) return
        // KeyRouter.deactivate's Enter and reset for the old one, activate for the new
        core.setCurrentInputMethod(name)
        flush()
        check(core.currentInputMethod() == name) { "$name not active" }
        im = name
        panel = IDLE
    }

    /** Each character of [keys] tapped on the screen's keyboard (space as the space bar); then the user looks. */
    fun type(keys: String) {
        keys.codePoints().forEach { c -> key(symOf(c), virtual = true) }
        pause()
    }

    fun space() = tap(SPACE)

    fun backspace(times: Int = 1) = repeat(times) { tap(BACKSPACE) }

    fun enter() = tap(RETURN)

    fun pick(text: String) {
        val all = candidates
        val index = all.indexOf(text)
        assertTrue("「$text」 not among ${all.take(SHOWN)}", index >= 0)
        pickAt(index)
    }

    fun pickAt(index: Int) {
        // as the bar picks it (HostCore.select): the index among all of them
        assertTrue("candidate $index not taken of ${candidates.take(SHOWN)}", core.select(index))
        flush()
        pause()
    }

    /** Text the keyboard commits over the engine (a long press's letter, .com), as FcitxInputMethodService.commitOverEngine. */
    fun commitOverEngine(text: String): PendingInputPolicy.Resolution {
        val language = InputMethodCatalog.listed(im)?.language.orEmpty()
        val address = field == Field.URL || field == Field.EMAIL
        val resolution = PendingInputPolicy.resolve(language, hasPreedit = preedit.isNotEmpty(), address = address)
        when (resolution) {
            PendingInputPolicy.Resolution.SelectFirstCandidate -> core.select(0)
            // the service's sendKey(FcitxKey_Return): no virtual state, a key of its own
            PendingInputPolicy.Resolution.CommitAsTyped -> core.sendKey(Key(RETURN), virtual = false)
            // the editor keeps what is composing: the code as typed
            PendingInputPolicy.Resolution.FinishComposing -> before.append(preedit)
            PendingInputPolicy.Resolution.None -> {}
        }
        flush()
        core.reset()
        flush()
        commit(text)
        pause()
        return resolution
    }

    /** The handwriting pad: the recognizer's [readings] (best first) offered for the next piece, then the pad's Ink key. */
    fun write(vararg readings: String) {
        val ink = UatEngines.ink(engines, im, readings.toList(), lastPiece)
        lastPiece = ink.piece
        key(symOf(Keyboard.INK), virtual = true, payload = ink)
        engines.inkSent(ink, im)
        pause()
    }

    private var lastPiece = -1

    fun syllable(syllable: String) {
        val index = panel.syllables.indexOf(syllable)
        assertTrue("syllable $syllable not among ${panel.syllables}", index >= 0)
        key(symOf(Keyboard.syllableKey(index, panel.syllablesId).code), virtual = true)
        pause()
    }

    private fun tap(sym: Int) {
        key(sym, virtual = true)
        pause()
    }

    /** A key as the service hands it to the host: [sym] an X keysym, [virtual] the screen's; one no one took is the app's. */
    fun key(sym: Int, virtual: Boolean = true, payload: Action? = null) {
        // the router reads the option as AndroidEngineConfig sets it
        core.router.options = core.router.options.copy(englishSuggest = engines.settings.englishSuggest)
        core.sendKey(Key(sym, if (virtual) HostKeyStates.VIRTUAL else 0), virtual = virtual, payload = payload)
        flush()
    }

    /** Each character of [keys] pressed on a hardware keyboard; then the user looks. */
    fun press(keys: String) {
        keys.codePoints().forEach { key(symOf(it), virtual = false) }
        pause()
    }

    fun pressKey(sym: Int) {
        key(sym, virtual = false)
        pause()
    }

    /** The user pauses: the host's timers run out. */
    fun pause() {
        clock.advance(PAUSE_MILLIS)
        // the engines' uptime too, as HostReplay's waits move it (a table's space taken runs out)
        UatEngines.Clock.away(PAUSE_MILLIS)
        flush()
    }

    private fun apply(event: HostEvent) {
        when (event) {
            is HostEvent.Commit -> {
                before.append(event.text)
                commits += event.text
                changed = true
            }
            // in code points, as the service deletes
            is HostEvent.DeleteSurrounding -> if (event.before > 0) {
                val n = minOf(event.before, before.codePointCount(0, before.length))
                before.setLength(before.offsetByCodePoints(before.length, -n))
                changed = true
            }
            is HostEvent.Key -> if (!event.up) handedBack += event.sym
            else -> {}
        }
    }

    // after each host call: the field told if it changed, then the keys handed back typed by the app
    private fun flush() {
        crash?.let { crash = null; throw AssertionError("the engine threw", it) }
        if (changed) {
            changed = false
            tell()
        }
        val keys = handedBack.toList()
        handedBack.clear()
        keys.forEach(::app)
    }

    // a key no session took is the app's, typed as is (no fcitx punctuation module here)
    private fun app(sym: Int) {
        val unicode = KeySyms.toUnicode(sym)
        when {
            sym == BACKSPACE -> if (before.isNotEmpty()) before.setLength(before.offsetByCodePoints(before.length, -1)) else return
            sym == RETURN || sym == KP_ENTER -> if (sending) return else before.append('\n')
            unicode >= SPACE && unicode != DELETE -> before.appendCodePoint(unicode)
            else -> return
        }
        tell()
    }

    fun expectText(text: String) = assertEquals("the field", text, this.text)

    fun expectPreedit(preedit: String) = assertEquals("the code typed", preedit, this.preedit)

    fun expectFirst(first: String) {
        val all = candidates
        assertEquals("first of ${all.take(SHOWN)}", first, all.firstOrNull())
    }

    fun expectStart(vararg texts: String) {
        val all = candidates
        assertEquals("start of ${all.take(SHOWN)}", texts.toList(), all.take(texts.size))
    }

    /** Each of [texts] is on the first page (as many as the bar shows by default). */
    fun expectOnFirstPage(vararg texts: String) {
        val shown = page
        for (t in texts) assertTrue("「$t」 not on the first page $shown", t in shown)
    }

    /** [texts] in this order among the candidates, others between them or not. */
    fun expectInOrder(vararg texts: String) {
        val all = candidates
        val at = texts.map { all.indexOf(it) }
        assertTrue("${texts.toList()} at $at of ${all.take(SHOWN)}", at.all { it >= 0 } && at == at.sorted())
    }

    fun expectAmong(vararg texts: String) {
        val all = candidates
        for (t in texts) assertTrue("「$t」 not among ${all.take(SHOWN)}", t in all)
    }

    fun expectNotAmong(vararg texts: String) {
        val all = candidates
        for (t in texts) assertTrue("「$t」 among ${all.take(SHOWN)}", t !in all)
    }

    companion object {
        // X keysyms (fcitx's FcitxKey_*)
        const val SPACE = 0x20
        const val MINUS = 0x2d
        const val EQUAL = 0x3d
        const val DELETE = 0x7f
        const val BACKSPACE = 0xff08
        const val TAB = 0xff09
        const val RETURN = 0xff0d
        const val ESCAPE = 0xff1b
        const val HOME = 0xff50
        const val LEFT = 0xff51
        const val RIGHT = 0xff53
        const val PAGE_UP = 0xff55
        const val PAGE_DOWN = 0xff56
        const val END = 0xff57
        const val KP_ENTER = 0xff8d

        /** The named keys the cases press, by fcitx's names; [EngineReplayContractTest] holds them to KeySyms. */
        val NAMED_KEYS: Map<String, Int> = linkedMapOf(
            "FcitxKey_BackSpace" to BACKSPACE,
            "FcitxKey_Return" to RETURN,
            "FcitxKey_KP_Enter" to KP_ENTER,
            "FcitxKey_Escape" to ESCAPE,
            "FcitxKey_Left" to LEFT,
            "FcitxKey_Right" to RIGHT,
            "FcitxKey_Page_Up" to PAGE_UP,
            "FcitxKey_Page_Down" to PAGE_DOWN,
        )

        const val UNICODE_KEYSYM = UNICODE

        /** The service's keysym for [c]: the host's own table, not a copy. */
        fun symOf(c: Int): Int = KeySyms.fromUnicode(c)

        fun nineKeys(digits: String): String = digits.map { String(Character.toChars(Keyboard.pinyinNineKey(it))) }.joinToString("")

        /** Whether [s] has no private-use character: a key's code (14 and 18 keys) never reaches the field or row. */
        fun noPrivateUse(s: String): Boolean = s.codePoints().noneMatch { Character.getType(it) == Character.PRIVATE_USE.toInt() }

        private const val UID = 1
        private const val PAUSE_MILLIS = 5_000L
        private const val FIELD = "uat-field"
        private const val SHOWN = 12
        private const val ALL = 200

        private val IDLE = Snapshot(
            commit = "", preedit = "", candidates = emptyList(), page = 0,
            hasPreviousPage = false, hasNextPage = false, handled = false, predicting = false,
        )
    }
}
