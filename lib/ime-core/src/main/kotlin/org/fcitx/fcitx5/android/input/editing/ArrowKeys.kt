/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.editing

import org.fcitx.fcitx5.android.input.cursor.CursorTracker
import org.fcitx.fcitx5.android.input.editing.EditorKeyPolicy.ArrowAction
import org.fcitx.fcitx5.android.input.editing.EditorKeyPolicy.Direction

/**
 * Arrow presses run ahead of the editor's cursor reports (a space-bar swipe, a slow WebView): each moves on from where
 * the presses before put the cursor, not from [selection], or a code point's width added to an older position could
 * split an emoji. Text beside the cursor is read once a report (each read waits on the app's UI thread). Starts over
 * from [selection] on an unexpected report, on [selection] moving between reports, and at [forget].
 */
class ArrowKeys(
    private val selection: CursorTracker,
    private val readAhead: Int = READ_AHEAD,
) {

    /** Oldest first, not yet reported. */
    private val moves = ArrayDeque<Int>()

    private var seenStart = -1
    private var seenEnd = -1

    /** From [textStart] in the editor. */
    private var text: CharSequence? = null
    private var textStart = 0

    /** An end of the text. */
    private var noneBefore = false
    private var noneAfter = false

    /** [textBefore]/[textAfter] read at the live cursor, only past what was read since the last report. */
    fun onArrow(
        traits: EditorTraits,
        direction: Direction,
        textBefore: (length: Int) -> CharSequence?,
        textAfter: (length: Int) -> CharSequence?,
    ): ArrowAction {
        val latest = selection.latest
        if (!latest.rangeEquals(seenStart, seenEnd)) forget()
        seenStart = latest.start
        seenEnd = latest.end
        val last = moves.lastOrNull()
        val start = last ?: latest.start
        val end = last ?: latest.end
        // a target past an end the editor ignores: no move to wait for; an unanswered read (null) counts the unit as text, unless before 0
        var steps = start != end
        val action = EditorKeyPolicy.onArrow(
            traits, direction, start, end,
            textBefore = { length -> before(start, length, textBefore).also { steps = it == null || it.isNotEmpty() } },
            textAfter = { length -> after(end, length, textAfter).also { steps = it == null || it.isNotEmpty() } },
        )
        if (action is ArrowAction.MoveCursor && steps && action.position >= 0) moves.addLast(action.position)
        return action
    }

    /** [selection] has taken it in. */
    fun cursorReported(start: Int, end: Int) {
        // the text may have changed with it
        text = null
        val reached = if (start == end) moves.indexOf(start) else -1
        if (reached >= 0) {
            repeat(reached + 1) { moves.removeFirst() }
        } else if (!selection.latest.rangeEquals(seenStart, seenEnd)) {
            moves.clear()
        }
        seenStart = selection.latest.start
        seenEnd = selection.latest.end
    }

    /** For a new input. */
    fun forget() {
        moves.clear()
        text = null
    }

    private fun before(p: Int, length: Int, read: (Int) -> CharSequence?): CharSequence? {
        val t = text
        val i = p - textStart
        if (t != null && i in 0..t.length) {
            if (i >= length || noneBefore) return t.subSequence((i - length).coerceAtLeast(0), i)
        }
        val wanted = maxOf(length, readAhead)
        val got = read(wanted) ?: return null
        text = got
        textStart = p - got.length
        noneBefore = got.isEmpty()
        noneAfter = false
        return got.subSequence((got.length - length).coerceAtLeast(0), got.length)
    }

    private fun after(p: Int, length: Int, read: (Int) -> CharSequence?): CharSequence? {
        val t = text
        val i = p - textStart
        if (t != null && i in 0..t.length) {
            if (t.length - i >= length || noneAfter) return t.subSequence(i, (i + length).coerceAtMost(t.length))
        }
        val wanted = maxOf(length, readAhead)
        val got = read(wanted) ?: return null
        text = got
        textStart = p
        noneBefore = false
        noneAfter = got.isEmpty()
        return got.subSequence(0, length.coerceAtMost(got.length))
    }

    companion object {
        /** A run of presses rarely goes further between two reports. */
        const val READ_AHEAD = 32
    }
}
