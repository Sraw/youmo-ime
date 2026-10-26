/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.host

import org.fcitx.fcitx5.android.core.FcitxEvent
import org.fcitx.fcitx5.android.core.FormattedText

/**
 * What a temp mode ([QuickPhrase], [UnicodeInput]) asks of the host: commit through the pipeline's
 * commit filters (`InputContext::commitString`) and show its panel. The host keeps the last panel per
 * context and sends it once at the end of the event, as [KeyPipeline.updatePanel] does.
 */
interface TempModeHost {
    fun commit(ic: InputContext, text: String)

    /**
     * The mode's whole panel ([TempPanel.EMPTY] when it leaves: fcitx resets the input panel then,
     * `quickphrasetempmode.cpp:67-83`, `unicodetempmode.cpp:92-100`, and the engine's panel comes back
     * with the engine's next event).
     */
    fun showPanel(ic: InputContext, panel: TempPanel)
}

/** A temp mode with a panel of its own: the candidate bar and code row while it is active. */
interface PanelTempMode : TempMode {
    /** The candidate at [globalIndex] of the whole list tapped (`candidateFromAll(idx).select`). */
    fun selectCandidate(ic: InputContext, globalIndex: Int): Boolean
}

/**
 * fcitx's input panel as a temp mode fills it: [preedit] (shown in the code row) or [clientPreedit]
 * (in the editor, when the context has [Capability.PREEDIT]), [auxUp], and the candidate list.
 */
class TempPanel(
    val preedit: FormattedText = FormattedText.Empty,
    val clientPreedit: FormattedText = FormattedText.Empty,
    val auxUp: FormattedText = FormattedText.Empty,
    val candidates: TempCandidateList? = null,
) {
    companion object {
        val EMPTY = TempPanel()
    }
}

/** fcitx's `CandidateLayoutHint`: the app's own enum, as the paged event carries it. */
typealias TempLayoutHint = FcitxEvent.PagedCandidateEvent.LayoutHint

/**
 * A candidate: [select] is what `CandidateWord::select` does, null for a display-only one; [comment]
 * worked out the first time it is read (Unicode's names, for the page sent only).
 */
class TempCandidate(val text: String, comment: () -> String, internal val select: ((InputContext) -> Unit)?) {
    constructor(text: String, comment: String, select: ((InputContext) -> Unit)?) : this(text, { comment }, select)

    val comment: String by lazy(LazyThreadSafetyMode.NONE, comment)
}

/**
 * The part of fcitx's `CommonCandidateList` (`fcitx/candidatelist.cpp:365-800`) the temp modes use:
 * pages of [pageSize], a global cursor, paging that resets the cursor to the page's first
 * ([resetCursorAfterPaging], `CursorPositionAfterPaging::ResetToFirst`) or leaves it, and cursor
 * moves that run over the whole list and wrap (no placeholders, `cursorIncludeUnselected` and
 * `cursorKeepInSamePage` off, as both modes leave them).
 */
class TempCandidateList(
    val candidates: List<TempCandidate>,
    val pageSize: Int,
    /** The selection keys' labels (`keyToLabel`, `candidatelist.cpp:479-516`), padded to 10 with "". */
    labels: List<String>,
    val layoutHint: TempLayoutHint = TempLayoutHint.NotSet,
    private val resetCursorAfterPaging: Boolean = false,
) {
    private val labels = labels + List(maxOf(0, REGULAR_LABEL_SIZE - labels.size)) { "" }

    var currentPage = 0
        private set

    /** The cursor over the whole list, -1 for none. */
    var globalCursorIndex = -1
        private set

    /** `usedNextBefore`: Up on the first page is still used once Down has been. */
    var usedNextBefore = false
        private set

    val totalSize get() = candidates.size

    /** The candidates on the current page. */
    val size get() = (totalSize - currentPage * pageSize).coerceIn(0, pageSize)

    val totalPages get() = (totalSize + pageSize - 1) / pageSize

    val hasPrev get() = currentPage > 0
    val hasNext get() = currentPage + 1 < totalPages

    /** The cursor on the current page, -1 when it is elsewhere or none (`candidatelist.cpp:540-547`). */
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

    /** `moveCursor` (`candidatelist.cpp:709-756`) with nothing a placeholder. */
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

    /** `setPage` (`candidatelist.cpp:778-790`) and `fixCursorAfterPaging` (`:440-462`). */
    private fun setPage(page: Int) {
        if (page == currentPage) return
        val old = cursorIndex
        currentPage = page
        if (old >= 0 && resetCursorAfterPaging) globalCursorIndex = page * pageSize
    }

    companion object {
        /** `regularLabelSize` in `candidatelist.cpp`. */
        const val REGULAR_LABEL_SIZE = 10

        /**
         * `keyToLabel` (`candidatelist.cpp:479-516`, not Apple) for a key that types a character (the
         * modes' selection keys are digits): modifiers as C-/A-/S-/M-, the character, ". ".
         */
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

/**
 * fcitx's `InputBuffer` (`fcitx-utils/inputbuffer.cpp`) as the temp modes use it: characters (code
 * points) with a cursor, at most [maxSize] of them (both modes set 30).
 */
class TempInputBuffer(private val maxSize: Int) {
    private val chars = ArrayList<Int>()

    /** The cursor, in characters. */
    var cursor = 0
        private set

    val size get() = chars.size

    fun isEmpty() = chars.isEmpty()

    val userInput: String
        get() = buildString { chars.forEach { appendCodePoint(it) } }

    /** The cursor in UTF-16 units of [userInput], what [FormattedText] counts. */
    val cursorInString: Int
        get() = chars.subList(0, cursor).sumOf { Character.charCount(it) }

    /** `typeImpl` (`inputbuffer.cpp:79-110`): false, and nothing typed, when it would pass [maxSize]. */
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

    /**
     * fcitx's `setCursor` checks the old cursor rather than the new one (`inputbuffer.cpp:144`), so a
     * position past the end is taken and later reads run off the buffer; here it is capped at the end.
     */
    fun setCursor(position: Int) {
        cursor = position.coerceIn(0, size)
    }

    fun clear() {
        chars.clear()
        cursor = 0
    }
}

/** Keysyms and checks the temp modes need beyond [KeySyms] (`fcitx-utils/keysymgen.h`, `key.cpp`). */
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

    /** fcitx's `DefaultPrevPage`/`NextPage`/`PrevCandidate`/`NextCandidate` (`fcitx/globalconfig.cpp:129-157`), constants here. */
    val PREV_PAGE = Key(UP)
    val NEXT_PAGE = Key(DOWN)
    val PREV_CANDIDATE = Key(KeySyms.TAB, KeyStates.SHIFT)
    val NEXT_CANDIDATE = Key(KeySyms.TAB)

    /** fcitx's `DefaultPageSize` (`globalconfig.cpp`), a constant here (design §1.4). */
    const val PAGE_SIZE = CommonCandidateList.DEFAULT_PAGE_SIZE

    /** `Key::isModifier` (`key.cpp:460-467`), [Key.isModifier]'s one set. */
    fun isModifier(key: Key) = key.isModifier

    /**
     * `Key::check` (`key.cpp:385-411`) for the keys the temp modes compare with, none of which is a
     * modifier key itself: the same sym and the same Ctrl/Alt/Shift/Super/Mod3.
     */
    fun check(key: Key, target: Key): Boolean {
        if (target.sym == NONE || target.sym == VOID_SYMBOL) return false
        val states = key.states and (KeyStates.CTRL_ALT_SHIFT or KeyStates.SUPER or KeyStates.MOD3)
        return target.sym == key.sym && target.states == states
    }

    /** `Key::digit` (`key.cpp:418-429`): 0–9 for a digit or keypad digit with no modifier, else -1. */
    fun digit(key: Key): Int = when {
        key.states != 0 -> -1
        key.sym in KeySyms.DIGIT_0..KeySyms.DIGIT_9 -> key.sym - KeySyms.DIGIT_0
        key.sym in KeySyms.KP_0..KeySyms.KP_9 -> key.sym - KeySyms.KP_0
        else -> -1
    }

    /** `Key::digitSelection` (`key.cpp:431-444`): 1 is 0, …, 0 is 9, with exactly [states] held. */
    fun digitSelection(key: Key, states: Int): Int {
        val held = key.states and (KeyStates.CTRL_ALT_SHIFT or KeyStates.SUPER or KeyStates.MOD3)
        if (held != states) return -1
        val idx = digit(Key(key.sym))
        return if (idx >= 0) (idx + 9) % 10 else -1
    }

    /** Text of one segment with [flags] (`Text::append`). */
    fun text(s: String, flags: Int = 0, cursor: Int = -1) = FormattedText(arrayOf(s), intArrayOf(flags), cursor)
}
