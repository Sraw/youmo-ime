/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.uat

import org.fcitx.fcitx5.android.engine.glide.Glide
import org.fcitx.fcitx5.android.engine.glide.GlideLayout
import org.fcitx.fcitx5.android.engine.host.EngineEvent
import org.fcitx.fcitx5.android.engine.host.Engines
import org.fcitx.fcitx5.android.engine.host.Keyboard
import org.fcitx.fcitx5.android.engine.learn.control.LearningOp
import org.fcitx.fcitx5.android.engine.learn.control.LearningResult
import org.fcitx.fcitx5.android.engine.session.Choice
import org.fcitx.fcitx5.android.engine.session.Offer
import org.fcitx.fcitx5.android.engine.session.Snapshot
import org.fcitx.fcitx5.android.host.Capability
import org.fcitx.fcitx5.android.host.EngineHost
import org.fcitx.fcitx5.android.host.HostCore
import org.fcitx.fcitx5.android.host.HostEvent
import org.fcitx.fcitx5.android.host.Key
import org.fcitx.fcitx5.android.host.KeySyms
import org.fcitx.fcitx5.android.host.KeyStates as HostKeyStates
import org.fcitx.fcitx5.android.host.Profile
import org.fcitx.fcitx5.android.host.VirtualHostClock
import org.fcitx.fcitx5.android.input.FieldPolicy
import org.fcitx.fcitx5.android.input.editing.ContextMemory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/**
 * A uat/ case's 操作 replayed through [engines] as the app's host has them: keys in, as fcitx hands
 * them to the androidengine addon (X keysyms), and what the field shows out: the text before the
 * cursor ([text]), the code typed ([preedit]) and the candidates ([candidates]).
 *
 * What happens to a key is the shipped host's: each goes through a [HostCore] with no modules
 * plugged in, so its [KeyRouter][org.fcitx.fcitx5.android.host.KeyRouter] picks the event, shows
 * the snapshot and runs the refine timer on a [VirtualHostClock]. What fcitx's modules would do is
 * not here: a key no session takes is the app's, typed as it is (the punctuation module would make
 * `.` 。), so a case types a mark with [commit] (the cases that need the punctuation module, the
 * panel or the field's editor are [HostReplay]'s). The user pauses after each step, so the refining
 * the timer would run is run before anything is read ([pause]).
 */
class EngineReplay(
    engines: Engines = UatEngines.fresh(),
    im: String = Engines.PINYIN,
    field: Field = Field.TEXT,
) {
    /** The kind of field focused, as its EditorInfo makes fcitx's capability flags. */
    enum class Field(val sensitive: Boolean) {
        TEXT(false),
        PASSWORD(true),
        URL(false),
        EMAIL(false),

        /** 不要提示: a text field flagged TYPE_TEXT_FLAG_NO_SUGGESTIONS. */
        NO_SUGGESTIONS(false),
        /** IME_FLAG_NO_PERSONALIZED_LEARNING (an incognito tab's): fcitx's Sensitive, as a password's, but a text field's keyboard. */
        NO_LEARNING(true),
        ;

        /** The capability flags the service sets for the field (CapabilityFlag.kt). */
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

    /** What the panel shows: the last snapshot shown. */
    var panel: Snapshot = IDLE
        private set

    /** Each text the engine committed, in order. */
    val commits = ArrayList<String>()

    private val clock = VirtualHostClock()

    // the sessions as the host reaches them: each snapshot the router shows is the panel
    private val port = object : EngineHost {
        override fun event(im: String, event: Int, arg: Int, learning: Boolean): Snapshot = try {
            this@EngineReplay.engines.onEvent(im, event, arg, learning)
        } catch (@Suppress("TooGenericExceptionCaught") e: Throwable) {
            // the router catches it (KeyRouter.call) and goes on: a case expecting nothing would pass over
            // a crash, so it is thrown at the next flush
            crash = crash ?: e
            throw e
        }.also { s ->
                // KeyRouter.send's rule: a slice of refining or a tap that changed nothing leaves the panel as it is
                if ((event != EngineEvent.REFINE && event != EngineEvent.CURSOR) || s.handled) panel = s
            }
        override fun candidates(im: String, from: Int, count: Int): List<Choice> = this@EngineReplay.engines.candidates(im, from, count)
        override fun offers(im: String, index: Int): Set<Offer> = this@EngineReplay.engines.offers(im, index)
        override fun settings(flat: String) = Unit
    }

    private val core = HostCore(
        port, clock, Profile.load(null, Engines.LISTED.map { it.name }.toSet(), "Default"),
        firstRun = true, showInputMethodInformation = false,
    )

    // what the host sent back during a call: the field changed (told after it), the keys it did not take (the app's)
    private var changed = false
    private var crash: Throwable? = null
    private val handedBack = ArrayList<Int>()

    init {
        requirePlain(field)
        core.events.handlers += ::apply
        core.setInputMethods(Engines.LISTED.map { it.name })
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

    /** The candidates, not only the page shown: all of them, or the first [ALL] of a list not counted yet (total -1). */
    val candidates: List<String> get() = engines.candidates(im, 0, if (panel.total >= 0) panel.total else ALL).map { it.text }

    /** The page shown, as the bar has it. */
    val page: List<String> get() = panel.candidates

    // ------------------------------------------------------------------ the field

    /** A field focused with [text] in it, the cursor at its end: the engine told, as the service tells it at onStartInput. */
    fun focus(text: String, field: Field = this.field) {
        // fcitx resets the input context a field focuses: the session made then, if it was not, as a device's
        // is by its activation, so it has a context to be told (Engines.context tells only the sessions made)
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

    // a password field switches the host to its fallback (InputContexts.setCapabilities), while im and
    // the candidates here would stay the old one's: HostReplay has the input method the host has
    private fun requirePlain(field: Field) =
        require(field != Field.PASSWORD) { "a password field is HostReplay's: the host switches its input method" }

    /** The app force-stopped and opened again on this field: the engines read the user's files afresh, the field focused anew. */
    fun restart() {
        engines = UatEngines.restart()
        panel = IDLE
        focus(text)
    }

    /**
     * Text the app commits itself (a mark of fcitx's punctuation): in the field, the engine not told,
     * as the service tells it only on a cursor report it did not expect (tellContextLater). UAT PY-64
     * showed it: told 「点了吗。他」 the engine ranked 的脸 first, the device 带来.
     */
    fun commit(text: String) {
        before.append(text)
    }

    /** Text put in the field by other means (a paste, the app's own text): the engine told of it, as the cursor report the app did not expect tells it. */
    fun paste(text: String) {
        before.append(text)
        tell()
    }

    // the field read again, as the service reads it at each selection update (FieldPolicy.told): a
    // sensitive field's text not read, else the editor answered (said), nothing after the cursor
    private fun tell() {
        if (field.sensitive) engines.context(im, "", learning = false)
        else engines.context(im, before.toString(), learning = true, after = "", said = true)
    }

    /**
     * The field emptied by the app, as a chat's box once a message is sent: the engine told as the
     * service tells it ([FieldPolicy.told]), what was written there remembered ([ContextMemory]).
     */
    fun sent() {
        val now = memoryClock++
        // the field as it is now: a sensitive one's text neither remembered nor told (focusAnother focuses
        // the memory on the field, on none for a sensitive one); never a password's, which requirePlain sends to HostReplay
        policy.focusAnother(FIELD, password = false, sensitive = field.sensitive)
        memory.context(before.toString(), now) { "" }
        before.setLength(0)
        panel = IDLE
        val told = checkNotNull(policy.told(now, { "" }, { "" }))
        engines.context(im, told.before, learning = !field.sensitive, cleared = told.cleared, after = told.after, said = told.said)
    }

    private val memory = ContextMemory()
    private val policy = FieldPolicy(memory)
    private var memoryClock = 1L

    /**
     * A long press's [op] on a candidate, as the menu does it (KotlinFcitx.learning): its event sent
     * only if the menu offers it on the candidate still shown (HostCore.triggerCandidateAction).
     */
    fun learn(op: LearningOp): LearningResult {
        val candidate = (op as? LearningOp.OnPage)?.op ?: op as? LearningOp.OnCandidate
        return engines.learning(im, op) { event, _ ->
            if (candidate == null || core.candidateActions(candidate.index).none { it.id == event }) return@learning false
            core.triggerCandidateAction(candidate.index, event, candidate.text)
            flush()
            true
        }.also { pause() }
    }

    /** A glide across the centres of [keys] on [layout], as the keyboard hands it to the engine. */
    fun glide(keys: String, layout: GlideLayout = GlideLayout.QWERTY) {
        val path = Glide(keys.flatMap { layout.centre(it).toList() }.toFloatArray(), layout)
        assertTrue("$im does not glide", engines.offerGlide(im, path))
        key(symOf(Keyboard.GLIDE), virtual = true)
        pause()
    }

    /** The hint shown beside the candidate [text] (a reading, a 拆字's pinyin), "" for none. */
    fun hintOf(text: String): String =
        engines.candidates(im, 0, if (panel.total >= 0) panel.total else ALL).first { it.text == text }.hint

    /** Another input method, as the globe key or the language key switches: what was typed committed as typed (deactivate's Enter). */
    fun ime(name: String) {
        if (name == im) return
        // the host's switch: the old one's Enter and reset (KeyRouter.deactivate), the new one's activate
        core.setCurrentInputMethod(name)
        flush()
        check(core.currentInputMethod() == name) { "$name not active" }
        im = name
        panel = IDLE
    }

    // ------------------------------------------------------------------ keys

    /** Each character of [keys] tapped on the screen's keyboard, a space as the space bar; then the user looks. */
    fun type(keys: String) {
        keys.codePoints().forEach { c -> key(symOf(c), virtual = true) }
        pause()
    }

    fun space() = tap(SPACE)

    fun backspace(times: Int = 1) = repeat(times) { tap(BACKSPACE) }

    fun enter() = tap(RETURN)

    /** The candidate [text], wherever it is among them all. */
    fun pick(text: String) {
        val all = candidates
        val index = all.indexOf(text)
        assertTrue("「$text」 not among ${all.take(SHOWN)}", index >= 0)
        pickAt(index)
    }

    /** The candidate at [index] among them all. */
    fun pickAt(index: Int) {
        // as the bar picks it (HostCore.select): the list shown takes the index among all of them
        assertTrue("candidate $index not taken of ${candidates.take(SHOWN)}", core.select(index))
        flush()
        pause()
    }

    /**
     * On the handwriting pad, a character written and the pen paused: the recognizer's [readings]
     * (best first, as it scores them) offered for the next piece, then the pad's Ink key.
     */
    fun write(vararg readings: String) {
        lastPiece = UatEngines.offerInk(engines, readings.toList(), lastPiece)
        key(symOf(Keyboard.INK), virtual = true)
        pause()
    }

    private var lastPiece = -1

    /** On the nine keys, the syllable [syllable] in the column of them. */
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

    /**
     * A key as the service hands it to the host: [sym] an X keysym ([symOf] a character's); [virtual]
     * the screen's, not a hardware keyboard's. The router decides what the session gets; a key no
     * one took comes back and is the app's.
     */
    fun key(sym: Int, virtual: Boolean = true) {
        // the router reads the option as the engine options set it (AndroidEngineConfig)
        core.router.options = core.router.options.copy(englishSuggest = engines.settings.englishSuggest)
        // the screen's keys flagged as the service flags them (KotlinFcitx.sendVirtual)
        core.sendKey(Key(sym, if (virtual) HostKeyStates.VIRTUAL else 0), virtual = virtual)
        flush()
    }

    /** Each character of [keys] pressed on a hardware keyboard, a space as its space bar; then the user looks. */
    fun press(keys: String) {
        keys.codePoints().forEach { key(symOf(it), virtual = false) }
        pause()
    }

    /** A hardware keyboard's key [sym] (Return, PageDown), then the user looks. */
    fun pressKey(sym: Int) {
        key(sym, virtual = false)
        pause()
    }

    /** The user pauses: the refine timer runs out, each slice shown that changed something. */
    fun pause() {
        clock.advance(PAUSE_MILLIS)
        flush()
    }

    private fun apply(event: HostEvent) {
        when (event) {
            is HostEvent.Commit -> {
                before.append(event.text)
                commits += event.text
                changed = true
            }
            // in code points, as the service deletes (EditingSession.deleteSurrounding, inCodePoints)
            is HostEvent.DeleteSurrounding -> if (event.before > 0) {
                val n = minOf(event.before, before.codePointCount(0, before.length))
                before.setLength(before.offsetByCodePoints(before.length, -n))
                changed = true
            }
            is HostEvent.Key -> if (!event.up) handedBack += event.sym
            else -> {}
        }
    }

    // after each call into the host: the field told if it changed (the selection update after it
    // reads it again, as the service's does), then the keys handed back typed by the app
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

    // a key no session took, the app's: typed as it is (fcitx's punctuation module not here)
    private fun app(sym: Int) {
        val unicode = KeySyms.toUnicode(sym)
        when {
            sym == BACKSPACE -> if (before.isNotEmpty()) before.setLength(before.offsetByCodePoints(before.length, -1)) else return
            sym == RETURN || sym == KP_ENTER -> before.append('\n')
            unicode >= SPACE && unicode != DELETE -> before.appendCodePoint(unicode)
            else -> return
        }
        tell()
    }

    // ------------------------------------------------------------------ expectations

    fun expectText(text: String) = assertEquals("the field", text, this.text)

    fun expectPreedit(preedit: String) = assertEquals("the code typed", preedit, this.preedit)

    /** [first] is the first candidate. */
    fun expectFirst(first: String) {
        val all = candidates
        assertEquals("first of ${all.take(SHOWN)}", first, all.firstOrNull())
    }

    /** The candidates start with [texts], in this order. */
    fun expectStart(vararg texts: String) {
        val all = candidates
        assertEquals("start of ${all.take(SHOWN)}", texts.toList(), all.take(texts.size))
    }

    /** Each of [texts] is on the first page (as many as the bar shows by default). */
    fun expectOnFirstPage(vararg texts: String) {
        val shown = page
        for (t in texts) assertTrue("「$t」 not on the first page $shown", t in shown)
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
        // X keysyms (fcitx's FcitxKey_*), as keyEvent switches on them
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

        /** The named keys the cases press, by fcitx's names: what [EngineReplayContractTest] holds to the host's KeySyms. */
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

        /** fcitx's keysym of a character with no keysym of its own: 0x1000000 plus its code point. */
        const val UNICODE_KEYSYM = KeySyms.UNICODE_OFFSET

        /** The keysym the service sends for the character [c] (KeySyms.fromString of it): the host's own table, not a copy. */
        fun symOf(c: Int): Int = KeySyms.fromUnicode(c)

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
