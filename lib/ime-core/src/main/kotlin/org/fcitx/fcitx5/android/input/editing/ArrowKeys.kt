/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.editing

import org.fcitx.fcitx5.android.input.cursor.CursorTracker
import org.fcitx.fcitx5.android.input.editing.EditorKeyPolicy.ArrowAction
import org.fcitx.fcitx5.android.input.editing.EditorKeyPolicy.Direction

/**
 * The arrow keys over a run of presses, which the editor's cursor reports lag behind: a
 * space-bar swipe sends a press a step, and a slow editor (a WebView, a long document) reports a
 * step well after the next one is pressed.
 *
 * Each press moves on from where the presses before it put the cursor, not from [selection],
 * which only a report moves: [EditorKeyPolicy.onArrow] steps the width of the code point at the
 * editor's live cursor, and added to an older position that width can land between the halves
 * of an emoji. And the text beside the cursor is read once a report and walked here, rather than
 * at every press: each read waits on the app's UI thread.
 *
 * The presses start over from [selection] when the editor reports a cursor none of them put
 * there and the IME did not expect (the user or the app moved it), when [selection] moves
 * between reports (an edit of the IME's own), and at [forget].
 */
class ArrowKeys(
    private val selection: CursorTracker,
    private val readAhead: Int = READ_AHEAD,
) {

    /** Where the presses put the cursor, oldest first, that the editor has not reported yet. */
    private val moves = ArrayDeque<Int>()

    /** [CursorTracker.latest] as last seen here. */
    private var seenStart = -1
    private var seenEnd = -1

    /** Text read beside the cursor since the last report, from [textStart] in the editor. */
    private var text: CharSequence? = null
    private var textStart = 0

    /** [text] is empty, the editor having nothing before (after) [textStart]: an end of the text. */
    private var noneBefore = false
    private var noneAfter = false

    /**
     * [EditorKeyPolicy.onArrow] from where the presses so far put the cursor. [textBefore] and
     * [textAfter] read the editor at its live cursor, as `getTextBeforeCursor`/`getTextAfterCursor`
     * do, and are only called past what was read since the last report.
     */
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
        // a target past an end of the text, where nothing was stepped over, the editor ignores:
        // no move to wait for. A selection collapses to one of its own ends. A read the app left
        // unanswered (null) tells nothing: the unit still moved is taken to be text, unless before 0
        var steps = start != end
        val action = EditorKeyPolicy.onArrow(
            traits, direction, start, end,
            textBefore = { length -> before(start, length, textBefore).also { steps = it == null || it.isNotEmpty() } },
            textAfter = { length -> after(end, length, textAfter).also { steps = it == null || it.isNotEmpty() } },
        )
        if (action is ArrowAction.MoveCursor && steps && action.position >= 0) moves.addLast(action.position)
        return action
    }

    /** The editor reported its cursor at [start] to [end], which [selection] has taken in. */
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

    /** Forgets the presses and what they read, for a new input. */
    fun forget() {
        moves.clear()
        text = null
    }

    /** Up to [length] units before [p], as `getTextBeforeCursor` there gives them. */
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

    /** Up to [length] units after [p], as `getTextAfterCursor` there gives them. */
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
        /** Units read on a side at a time: a run of presses rarely goes further between two reports. */
        const val READ_AHEAD = 32
    }
}
