/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.host

import org.fcitx.fcitx5.android.core.FormattedText
import org.fcitx.fcitx5.android.core.TextFormatFlag

/** The Unicode mode's texts (`unicodetempmode.cpp:404-408`), translated by the app. */
data class UnicodeStrings(
    val aux: String = "Unicode: ",
    val hint: String = "(Type to search unicode by code or description)",
    val names: CharNames = CharNames(),
)

/**
 * Unicode input, fcitx's unicode temp mode (`modules/unicode/unicodetempmode.cpp`, `unicode.cpp`):
 * Ctrl+Alt+Shift+U searches characters by name, alias or code ([CharSelectData]); Ctrl+Shift+U types
 * a code in hex (`unicode.h:26-34`, no setting, design Q6). [trigger] is the keyboard's button
 * (`native-lib.cpp:298-303`).
 *
 * Opened with nothing typed, the search lists the characters of the clipboard's latest text
 * (`:342-395`). fcitx also lists the editor's selection, but only when the context reports
 * surrounding text, which this frontend never does, and the X primary selection, which Android does
 * not have; [clipboard] gives the latest clipboard text ("" for none). No compose sequences (see
 * [QuickPhrase]).
 *
 * [loadData] reads `charselectdata` once, at the first trigger; when it gives null the mode never
 * starts (`CharSelectData::load`, `charselectdata.cpp:76-101`, remembers the failure too).
 */
class UnicodeInput(
    private val host: TempModeHost,
    private val loadData: () -> ByteArray?,
    private val clipboard: () -> String = { "" },
    private val strings: UnicodeStrings = UnicodeStrings(),
    // where the search's index is built after the first trigger: off the host thread, which the
    // trigger's own panel (the clipboard's characters, names only) does not wait for. Priority 4 is
    // Android's nice 10 (THREAD_PRIORITY_BACKGROUND), not MIN's 19: a key typed meanwhile waits on
    // this build, which at 19 gets next to no CPU while the app and keyboard draw
    private val background: (() -> Unit) -> Unit = { Thread(it, "host-unicode").apply { priority = Thread.NORM_PRIORITY - 1 }.start() },
) : PanelTempMode {

    private enum class Mode { Off, Search, Direct }

    private class State {
        var active = false
        var mode = Mode.Off
        val buffer = TempInputBuffer(MAX_SIZE)
        var list: TempCandidateList? = null
    }

    private val state = IcProperty { State() }

    // read at the first trigger, as fcitx does (CharSelectData::load, charselectdata.cpp:76-101):
    // nothing held till Unicode is used; the index then built in the background
    private val data: CharSelectData? by lazy { loadData()?.let { CharSelectData(it, strings.names).also { d -> background(d::prepare) } } }

    override fun isActive(ic: InputContext) = ic.propertyFor(state).active

    /** `triggerTempMode` (`:107-122`). */
    override fun trigger(ic: InputContext, e: KeyEvent): Boolean {
        if (e.release) return false
        val mode = when {
            TempKeys.check(e.key, SEARCH_KEY) -> Mode.Search
            TempKeys.check(e.key, DIRECT_KEY) -> Mode.Direct
            else -> return false
        }
        return trigger(ic, mode)
    }

    /** The keyboard's button: search (`:103-105`). */
    fun trigger(ic: InputContext): Boolean = trigger(ic, Mode.Search)

    /** `:143-152`. */
    private fun trigger(ic: InputContext, mode: Mode): Boolean {
        if (data == null) return false
        val s = ic.propertyFor(state)
        s.mode = mode
        s.active = true
        updateUI(ic, trigger = true)
        return true
    }

    override fun keyEvent(ic: InputContext, e: KeyEvent): Boolean {
        if (e.release) return false
        return if (ic.propertyFor(state).mode == Mode.Search) handleSearch(ic, e.key) else handleDirect(ic, e.key)
    }

    /** `handleSearch` (`:154-248`). */
    @Suppress("CyclomaticComplexMethod", "ReturnCount")
    private fun handleSearch(ic: InputContext, key: Key): Boolean {
        val s = ic.propertyFor(state)
        s.list?.let { list ->
            val idx = TempKeys.digitSelection(key, KeyStates.ALT)
            if (idx >= 0) {
                if (idx < list.size) list.candidate(idx).select?.invoke(ic)
                return true
            }
            if (TempKeys.check(key, TempKeys.PREV_PAGE)) {
                if (list.hasPrev) {
                    list.prev()
                    show(ic, trigger = false)
                    return true
                }
                if (list.usedNextBefore) return true
            }
            when {
                TempKeys.check(key, TempKeys.NEXT_PAGE) -> list.next()
                TempKeys.check(key, TempKeys.PREV_CANDIDATE) -> list.prevCandidate()
                TempKeys.check(key, TempKeys.NEXT_CANDIDATE) -> list.nextCandidate()
                key.sym == KeySyms.RETURN && key.states == 0 || key.sym == KeySyms.KP_ENTER && key.states == 0 -> {
                    if (list.totalSize > 0 && list.cursorIndex >= 0) list.candidate(list.cursorIndex).select?.invoke(ic)
                    return true
                }
                else -> null
            }?.let {
                show(ic, trigger = false)
                return true
            }
        }
        if (TempKeys.isModifier(key) || key.hasModifier) return false
        when (key.sym) {
            KeySyms.ESCAPE -> reset(ic)
            KeySyms.BACKSPACE -> if (s.buffer.isEmpty()) {
                reset(ic)
            } else if (s.buffer.backspace()) {
                if (s.buffer.isEmpty()) reset(ic) else updateUI(ic)
            }
            else -> {
                val chr = KeySyms.toUnicode(key.sym)
                s.buffer.type(if (chr == 0 || KeySyms.isPrivateUse(chr)) "" else String(Character.toChars(chr)))
                updateUI(ic)
            }
        }
        return true
    }

    /** `handleDirect` (`:250-296`): every key is used. */
    @Suppress("ReturnCount")
    private fun handleDirect(ic: InputContext, key: Key): Boolean {
        val s = ic.propertyFor(state)
        if (TempKeys.isModifier(key) || key.hasModifier) return true
        if (key.sym == KeySyms.ESCAPE) {
            reset(ic)
            return true
        }
        if (key.sym == KeySyms.BACKSPACE) {
            if (s.buffer.backspace()) updateUI(ic) else reset(ic)
            return true
        }
        if (isHexKey(key)) {
            if (!s.buffer.type(String(Character.toChars(KeySyms.toUnicode(key.sym))))) return true
            if (codeOf(s.buffer.userInput) != null) updateUI(ic) else s.buffer.backspace()
            return true
        }
        if (key.sym in COMMIT_KEYS) {
            if (!s.buffer.isEmpty()) {
                val value = codeOf(s.buffer.userInput)
                if (value != null && value != 0) host.commit(ic, String(Character.toChars(value)))
                reset(ic)
            }
        }
        updateUI(ic)
        return true
    }

    /** Leaves the mode (`:134-139`, `UnicodeState::reset`, `:92-100`). */
    override fun reset(ic: InputContext) {
        val s = ic.propertyFor(state)
        s.active = false
        s.mode = Mode.Off
        s.buffer.clear()
        s.list = null
        host.showPanel(ic, TempPanel.EMPTY)
    }

    /** No invoke action of its own: fcitx's default takes it and does nothing (`fcitx/tempmode.cpp:26-29`). */
    override fun invokeAction(ic: InputContext, e: InvokeActionEvent) = true

    override fun selectCandidate(ic: InputContext, globalIndex: Int): Boolean {
        val c = ic.propertyFor(state).list?.candidates?.getOrNull(globalIndex) ?: return false
        c.select?.invoke(ic)
        return true
    }

    /** `updateUI` (`:298-417`): rebuilds the list. */
    private fun updateUI(ic: InputContext, trigger: Boolean = false) {
        val s = ic.propertyFor(state)
        if (s.mode == Mode.Search) {
            if (!s.buffer.isEmpty()) {
                val d = data!!
                val found = d.find(s.buffer.userInput).filter { isValid(it) }
                // a bare code too, first: the 14 and 18 keys type no "U+"
                val bare = bareCodeOf(s.buffer.userInput)
                val chars = if (bare == null) found else listOf(bare) + found.filter { it != bare }
                val words = chars.map { chr ->
                    val text = if (chr == 0) "" else String(Character.toChars(chr))
                    // the name looked up only for the candidates sent: a prefix matches thousands
                    TempCandidate(text, { d.name(chr) }) { c ->
                        host.commit(c, text)
                        reset(c)
                    }
                }
                s.list = list(words, SEARCH_LABELS)
            } else if (trigger) {
                s.list = list(clipboardChars(), emptyList())
            } else {
                s.list = null
            }
        } else {
            s.list = null
        }
        show(ic, trigger)
    }

    private fun list(words: List<TempCandidate>, labels: List<String>) =
        TempCandidateList(words, TempKeys.PAGE_SIZE, labels, TempLayoutHint.Vertical).also {
            if (it.totalSize > 0) it.setGlobalCursorIndex(0)
        }

    /** Up to 21 distinct characters of the clipboard text, each with its code and name (`:365-395`). */
    private fun clipboardChars(): List<TempCandidate> {
        val text = clipboard()
        val d = data!!
        val out = ArrayList<TempCandidate>()
        var counter = 0
        // a lone surrogate is no character (fcitx skips text that is not valid UTF-8); a nameless
        // one shows its code alone (`:368-386`)
        val chars = text.codePoints().distinct().filter { it !in Character.MIN_SURROGATE.code..Character.MAX_SURROGATE.code }
        for (chr in chars) {
            val name = d.name(chr)
            out.add(TempCandidate(String(Character.toChars(chr)), if (name.isEmpty()) "U+%04X".format(chr) else "U+%04X %s".format(chr, name), null))
            if (counter >= CLIPBOARD_LIMIT) break
            counter++
        }
        return out
    }

    private fun show(ic: InputContext, trigger: Boolean) {
        val s = ic.propertyFor(state)
        val panel = when (s.mode) {
            Mode.Direct -> {
                // capabilityFlags(), masked as setEnablePreedit left it (inputcontext.cpp:69-75)
                val client = ic.has(Capability.PREEDIT)
                val format = TextFormatFlag.DontCommit.flag or if (client) TextFormatFlag.Underline.flag else 0
                val segments = listOf("U+", s.buffer.userInput).filter { it.isNotEmpty() }
                val preedit = FormattedText(
                    segments.toTypedArray(),
                    IntArray(segments.size) { format },
                    segments.sumOf { it.length },
                )
                if (client) TempPanel(clientPreedit = preedit) else TempPanel(preedit = preedit)
            }
            Mode.Search -> TempPanel(
                preedit = TempKeys.text(
                    s.buffer.userInput,
                    cursor = if (s.buffer.isEmpty()) -1 else s.buffer.cursorInString,
                ),
                auxUp = TempKeys.text(if (trigger) strings.aux + strings.hint else strings.aux),
                candidates = s.list,
            )
            Mode.Off -> TempPanel.EMPTY
        }
        host.showPanel(ic, panel)
    }

    companion object {
        private const val MAX_SIZE = 30
        private const val CLIPBOARD_LIMIT = 20
        private val COMMIT_KEYS = setOf(KeySyms.SPACE, KeySyms.KP_SPACE, KeySyms.RETURN, KeySyms.KP_ENTER)

        /** `TriggerKey` and `DirectUnicodeMode` defaults (`unicode.h:26-34`). */
        val SEARCH_KEY = Key('U'.code, KeyStates.CTRL_ALT_SHIFT)
        val DIRECT_KEY = Key('U'.code, KeyStates.CTRL or KeyStates.SHIFT)

        /** Alt+1 … Alt+0 (`unicode.cpp:19-26`). */
        private val SEARCH_LABELS =
            (1..10).map { TempCandidateList.keyToLabel(Key(KeySyms.DIGIT_0 + it % 10, KeyStates.ALT)) }

        /** `utf8::UCS4IsValid` (`fcitx-utils/utf8.cpp:17-19`), on fcitx's unsigned value. */
        @Suppress("MagicNumber")
        internal fun isValid(code: Int) = Integer.compareUnsigned(code, 0x110000) < 0 && code and 0xFFFFF800.toInt() != 0xD800

        /**
         * A search that is a code without its `U+`: 2–6 ASCII hex digits, one at least a digit (so "face"
         * and "bead" stay words); the character, or null. No control character: "1b" would commit an
         * escape into the field.
         */
        @Suppress("MagicNumber")
        internal fun bareCodeOf(text: String): Int? {
            if (text.length !in 2..6 || text.none { it in '0'..'9' }) return null
            if (!text.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
            return codeOf(text)?.takeIf { Character.getType(it) != Character.CONTROL.toInt() }
        }

        /** `isHexKey` (`:39-47`). */
        private fun isHexKey(key: Key) = key.isDigit ||
            (key.isUAZ || key.isLAZ) && Character.digit(key.sym, 16) >= 0

        /**
         * `bufferIsValid` (`:49-64`): the hex digits as `std::stoi` reads them (past `int`, invalid)
         * and a character; null when not.
         */
        @Suppress("MagicNumber")
        internal fun codeOf(hex: String): Int? {
            val v = hex.toBigIntegerOrNull(16) ?: return null
            if (v > Int.MAX_VALUE.toBigInteger()) return null
            return v.toInt().takeIf { isValid(it) }
        }
    }
}
