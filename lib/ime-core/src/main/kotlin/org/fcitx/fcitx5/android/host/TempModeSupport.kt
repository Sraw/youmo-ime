/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.host

import org.fcitx.fcitx5.android.core.FormattedText

/** What a temp mode asks of the host: commit through the commit filters and show its panel, sent at the event's end. */
interface TempModeHost {
    fun commit(ic: InputContext, text: String)

    /** Past the commit filters (traditional, full width): the user's own quick phrase. */
    fun commitAsIs(ic: InputContext, text: String) = commit(ic, text)

    /** [TempPanel.EMPTY] when it leaves: fcitx resets the panel, and the engine's comes back with its next event. */
    fun showPanel(ic: InputContext, panel: TempPanel)
}

/** [clientPreedit] goes in the editor when the context has [Capability.PREEDIT]. */
class TempPanel(
    val preedit: FormattedText = FormattedText.Empty,
    val clientPreedit: FormattedText = FormattedText.Empty,
    val auxUp: FormattedText = FormattedText.Empty,
    val candidates: TempCandidateList? = null,
    /** The app lists its candidates a row each and opens one long pressed. */
    val quickPhrase: Boolean = false,
) {
    companion object {
        val EMPTY = TempPanel()
    }
}

/** [select] null: display only; [comment] computed on first read (Unicode's names, for the page sent only). */
class TempCandidate(val text: String, comment: () -> String, internal val select: ((InputContext) -> Unit)?, val asIs: Boolean = false) {
    constructor(text: String, comment: String, select: ((InputContext) -> Unit)?) : this(text, { comment }, select)

    val comment: String by lazy(LazyThreadSafetyMode.NONE, comment)
}

/**
 * The part of fcitx's `CommonCandidateList` the temp modes and punctuation use: pages, a global cursor that
 * wraps, paging that resets it or not; no placeholders, `cursorKeepInSamePage` off.
 */
class TempCandidateList(
    val candidates: List<TempCandidate>,
    val pageSize: Int,
    /** `keyToLabel`'s, padded to 10 with "". */
    labels: List<String>,
    val layoutHint: LayoutHint = LayoutHint.NotSet,
    private val resetCursorAfterPaging: Boolean = false,
) {
    private val labels = labels + List(maxOf(0, REGULAR_LABEL_SIZE - labels.size)) { "" }

    var currentPage = 0
        private set

    /** -1 for none. */
    var globalCursorIndex = -1
        private set

    /** Up on the first page is still used once Down has been. */
    var usedNextBefore = false
        private set

    val totalSize get() = candidates.size

    val size get() = (totalSize - currentPage * pageSize).coerceIn(0, pageSize)

    val totalPages get() = (totalSize + pageSize - 1) / pageSize

    val hasPrev get() = currentPage > 0
    val hasNext get() = currentPage + 1 < totalPages

    /** -1 when it is elsewhere or none. */
    val cursorIndex: Int
        get() = if (globalCursorIndex >= 0 && globalCursorIndex / pageSize == currentPage) {
            globalCursorIndex % pageSize
        } else {
            -1
        }

    fun candidate(index: Int): TempCandidate {
        require(index in 0 until size) { "invalid index $index" }
        return candidates[currentPage * pageSize + index]
    }

    fun label(index: Int): String = labels[index]

    fun setGlobalCursorIndex(index: Int) {
        require(index < totalSize) { "invalid global index $index" }
        globalCursorIndex = if (index < 0) -1 else index
    }

    fun prev() {
        if (hasPrev) setPage(currentPage - 1)
    }

    fun next() {
        if (!hasNext) return
        setPage(currentPage + 1)
        usedNextBefore = true
    }

    fun prevCandidate() = moveCursor(prev = true)

    fun nextCandidate() = moveCursor(prev = false)

    /** `moveCursor` with nothing a placeholder. */
    private fun moveCursor(prev: Boolean) {
        if (totalSize <= 0 || size <= 0) return
        if (cursorIndex < 0) {
            setGlobalCursorIndex(currentPage * pageSize + if (prev) size - 1 else 0)
            return
        }
        val moved = globalCursorIndex + if (prev) -1 else 1
        globalCursorIndex = when {
            moved < 0 -> totalSize - 1
            moved >= totalSize -> 0
            else -> moved
        }
        setPage(globalCursorIndex / pageSize)
    }

    /** `setPage` and `fixCursorAfterPaging`. */
    private fun setPage(page: Int) {
        if (page == currentPage) return
        val old = cursorIndex
        currentPage = page
        if (old >= 0 && resetCursorAfterPaging) globalCursorIndex = page * pageSize
    }

    companion object {
        const val REGULAR_LABEL_SIZE = 10

        /** `keyToLabel` (not Apple) for a character key: modifiers as C-/A-/S-/M-, the character, ". ". */
        internal fun keyToLabel(key: Key): String {
            if (key.sym == TempKeys.NONE) return ""
            val chr = KeySyms.toUnicode(key.sym)
            return buildString {
                if (key.states and KeyStates.CTRL != 0) append("C-")
                if (key.states and KeyStates.ALT != 0) append("A-")
                if (key.states and KeyStates.SHIFT != 0) append("S-")
                if (key.states and KeyStates.SUPER != 0) append("M-")
                appendCodePoint(chr)
                append(". ")
            }
        }
    }
}

/** fcitx's `InputBuffer` as the temp modes use it: code points with a cursor, at most [maxSize] (both use 30). */
class TempInputBuffer(private val maxSize: Int) {
    private val chars = ArrayList<Int>()

    var cursor = 0
        private set

    val size get() = chars.size

    fun isEmpty() = chars.isEmpty()

    val userInput: String
        get() = buildString { chars.forEach { appendCodePoint(it) } }

    /** In UTF-16 units, what [FormattedText] counts. */
    val cursorInString: Int
        get() = chars.subList(0, cursor).sumOf { Character.charCount(it) }

    /** `typeImpl`: false, nothing typed, past [maxSize]. */
    fun type(text: String): Boolean {
        val typed = text.codePoints().toArray()
        if (maxSize > 0 && typed.size + size > maxSize) return false
        chars.addAll(cursor, typed.toList())
        cursor += typed.size
        return true
    }

    fun backspace(): Boolean {
        if (cursor == 0) return false
        chars.removeAt(--cursor)
        return true
    }

    fun del(): Boolean {
        if (cursor >= size) return false
        chars.removeAt(cursor)
        return true
    }

    /** fcitx's `setCursor` checks the old cursor, so a position past the end ran off the buffer; capped here. */
    fun setCursor(position: Int) {
        cursor = position.coerceIn(0, size)
    }

    fun clear() {
        chars.clear()
        cursor = 0
    }
}

/** Keysyms and checks the temp modes need beyond [KeySyms]. */
@Suppress("MagicNumber")
internal object TempKeys {
    const val NONE = 0
    const val VOID_SYMBOL = 0xffffff
    const val GRAVE = 0x0060
    const val SEMICOLON = 0x003b
    const val HOME = 0xff50
    const val LEFT = KeySyms.LEFT
    const val UP = 0xff52
    const val RIGHT = 0xff53
    const val DOWN = 0xff54
    const val END = 0xff57
    const val KP_HOME = 0xff95
    const val KP_LEFT = 0xff96
    const val KP_RIGHT = 0xff98
    const val KP_END = 0xff9c

    /** fcitx's `DefaultPrevPage` and kin, constants here. */
    val PREV_PAGE = Key(UP)
    val NEXT_PAGE = Key(DOWN)
    val PREV_CANDIDATE = Key(KeySyms.TAB, KeyStates.SHIFT)
    val NEXT_CANDIDATE = Key(KeySyms.TAB)

    /** fcitx's `DefaultPageSize`, a constant here. */
    const val PAGE_SIZE = 5

    fun isModifier(key: Key) = key.isModifier

    /** `Key::check` for keys none of which is a modifier: the same sym and Ctrl/Alt/Shift/Super/Mod3. */
    fun check(key: Key, target: Key): Boolean {
        if (target.sym == NONE || target.sym == VOID_SYMBOL) return false
        val states = key.states and (KeyStates.CTRL_ALT_SHIFT or KeyStates.SUPER or KeyStates.MOD3)
        return target.sym == key.sym && target.states == states
    }

    /** `Key::digit`. */
    fun digit(key: Key): Int = when {
        key.states != 0 -> -1
        key.sym in KeySyms.DIGIT_0..KeySyms.DIGIT_9 -> key.sym - KeySyms.DIGIT_0
        key.sym in KeySyms.KP_0..KeySyms.KP_9 -> key.sym - KeySyms.KP_0
        else -> -1
    }

    /** `Key::digitSelection`: 1 is 0, …, 0 is 9, with exactly [states] held. */
    fun digitSelection(key: Key, states: Int): Int {
        val held = key.states and (KeyStates.CTRL_ALT_SHIFT or KeyStates.SUPER or KeyStates.MOD3)
        if (held != states) return -1
        val idx = digit(Key(key.sym))
        return if (idx >= 0) (idx + 9) % 10 else -1
    }

    /** `Text::append`. */
    fun text(s: String, flags: Int = 0, cursor: Int = -1) = FormattedText(arrayOf(s), intArrayOf(flags), cursor)
}
