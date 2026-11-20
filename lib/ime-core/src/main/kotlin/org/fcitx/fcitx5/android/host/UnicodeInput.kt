/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.host

import org.fcitx.fcitx5.android.core.FormattedText
import org.fcitx.fcitx5.android.core.TextFormatFlag

/** The Unicode mode's texts, translated by the app. */
data class UnicodeStrings(
    val aux: String = "Unicode: ",
    val hint: String = "(Type to search unicode by code or description)",
    val names: CharNames = CharNames(),
)

/**
 * fcitx's unicode temp mode: Ctrl+Alt+Shift+U searches characters by name, alias or code; Ctrl+Shift+U types a hex
 * code. Opened with nothing typed, the search lists the latest clipboard text's characters (fcitx's editor selection
 * and X primary selection have no source here). When [loadData] gives null the mode never starts.
 */
class UnicodeInput(
    private val host: TempModeHost,
    private val loadData: () -> ByteArray?,
    private val clipboard: () -> String = { "" },
    private val strings: UnicodeStrings = UnicodeStrings(),
    // the index is built off the host thread; priority 4 (nice 10), not MIN's 19, as a key typed meanwhile waits on it
    private val background: (() -> Unit) -> Unit = { Thread(it, "host-unicode").apply { priority = Thread.NORM_PRIORITY - 1 }.start() },
) : TempMode {

    private enum class Mode { Off, Search, Direct }

    private class State {
        var active = false
        var mode = Mode.Off
        val buffer = TempInputBuffer(MAX_SIZE)
        var list: TempCandidateList? = null
    }

    private val state = IcProperty { State() }

    // read at the first trigger, as fcitx does: nothing held till Unicode is used
    private val data: CharSelectData? by lazy { loadData()?.let { CharSelectData(it, strings.names).also { d -> background(d::prepare) } } }

    override fun isActive(ic: InputContext) = ic.propertyFor(state).active

    /** `triggerTempMode`. */
    override fun trigger(ic: InputContext, e: KeyEvent): Boolean {
        if (e.release) return false
        val mode = when {
            TempKeys.check(e.key, SEARCH_KEY) -> Mode.Search
            TempKeys.check(e.key, DIRECT_KEY) -> Mode.Direct
            else -> return false
        }
        return trigger(ic, mode)
    }

    /** The keyboard's button: search. */
    fun trigger(ic: InputContext): Boolean = trigger(ic, Mode.Search)

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

    /** `handleSearch`. */
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

    /** `handleDirect`: every key is used. */
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

    /** `UnicodeState::reset`. */
    override fun reset(ic: InputContext) {
        val s = ic.propertyFor(state)
        s.active = false
        s.mode = Mode.Off
        s.buffer.clear()
        s.list = null
        host.showPanel(ic, TempPanel.EMPTY)
    }

    /** fcitx's default takes it and does nothing. */
    override fun invokeAction(ic: InputContext, e: InvokeActionEvent) = true

    /** `updateUI`: rebuilds the list. */
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
                    TempCandidate(text, comment = { d.name(chr) }, select = { c ->
                        host.commit(c, text)
                        reset(c)
                    }, asIs = false)
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
        TempCandidateList(words, TempKeys.PAGE_SIZE, labels, LayoutHint.Vertical).also {
            if (it.totalSize > 0) it.setGlobalCursorIndex(0)
        }

    /** Up to 21 distinct characters, each with its code and name. */
    private fun clipboardChars(): List<TempCandidate> {
        val text = clipboard()
        val d = data!!
        val out = ArrayList<TempCandidate>()
        var counter = 0
        // a lone surrogate is no character (fcitx skips invalid UTF-8); a nameless one shows its code alone
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
                // capabilityFlags(), masked as setEnablePreedit left it
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

        /** `TriggerKey` and `DirectUnicodeMode` defaults. */
        val SEARCH_KEY = Key('U'.code, KeyStates.CTRL_ALT_SHIFT)
        val DIRECT_KEY = Key('U'.code, KeyStates.CTRL or KeyStates.SHIFT)

        /** Alt+1 … Alt+0. */
        private val SEARCH_LABELS =
            (1..10).map { TempCandidateList.keyToLabel(Key(KeySyms.DIGIT_0 + it % 10, KeyStates.ALT)) }

        /** `utf8::UCS4IsValid`, on fcitx's unsigned value. */
        @Suppress("MagicNumber")
        internal fun isValid(code: Int) = Integer.compareUnsigned(code, 0x110000) < 0 && code and 0xFFFFF800.toInt() != 0xD800

        /** 2–6 hex digits, at least one a digit (so "face" stays a word); no control character ("1b" would commit an escape). */
        @Suppress("MagicNumber")
        internal fun bareCodeOf(text: String): Int? {
            if (text.length !in 2..6 || text.none { it in '0'..'9' }) return null
            if (!text.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
            return codeOf(text)?.takeIf { Character.getType(it) != Character.CONTROL.toInt() }
        }

        /** `isHexKey`. */
        private fun isHexKey(key: Key) = key.isDigit ||
            (key.isUAZ || key.isLAZ) && Character.digit(key.sym, 16) >= 0

        /** `bufferIsValid`: as `std::stoi` reads it (past `int`, invalid), and a character; null when not. */
        @Suppress("MagicNumber")
        internal fun codeOf(hex: String): Int? {
            val v = hex.toBigIntegerOrNull(16) ?: return null
            if (v > Int.MAX_VALUE.toBigInteger()) return null
            return v.toInt().takeIf { isValid(it) }
        }
    }
}
