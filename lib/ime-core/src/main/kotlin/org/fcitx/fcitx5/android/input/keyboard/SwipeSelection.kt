/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.fcitx.fcitx5.android.input.cursor.CursorRange
import org.fcitx.fcitx5.android.input.shortcut.EditPlan
import java.text.BreakIterator
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 滑选: a key's swipe that reached the shortcut, then moved sideways without lifting, the selection's
 * free end ([head]) a character a step (its steps [SwipeSteps]'), the other ([anchor]) where the cursor
 * was. Lifted, the selection stays as it is.
 *
 * A touch [reached] it on the main thread as the swipe passes its threshold; it [begin]s there too,
 * but after what is typed is committed, which the host does on its own thread first, so the steps
 * moved in between are kept and taken as it begins.
 *
 * A character is a grapheme cluster (a flag, a family, an accent are one) of the text read around the
 * selection as it begins; past what was read, or with nothing read (a password, an app that gives
 * none), a UTF-16 unit, the start of the field the end to the left, none known to the right (an
 * editor ignores a selection past its text).
 */
class SwipeSelection {

    private var touch = 0
    private var begun = false
    private var pending = 0

    private var anchor = 0
    private var head = 0

    // what was read as it began: [text] from [base] in the field, whether more was there each side
    private var text: String? = null
    private var base = 0
    private var moreBefore = false
    private var moreAfter = false
    private val graphemes: BreakIterator = BreakIterator.getCharacterInstance()

    // the selection the last touch left (none: -1), and its anchor: a touch beginning on it goes on from there.
    // Ints, not a CursorRange: its equality is its array's identity
    private var leftStart = -1
    private var leftEnd = -1
    private var leftAnchor = 0

    /** A touch reached 滑选: its number, for [begin]; steps from here on wait for it. */
    fun reached(): Int {
        touch++
        begun = false
        pending = 0
        return touch
    }

    /**
     * [touch] begins at the field's [selection], [read] around it ([READ] each side, the selection
     * itself; null where none was): the anchor at its end, its start the free end, unless it is the
     * one the last touch left, whose anchor it keeps. The selection to set for the steps moved
     * meanwhile, or null: none moved, or [touch] is no longer the one going on.
     */
    fun begin(touch: Int, selection: CursorRange, read: EditPlan.Field?): CursorRange? {
        if (touch != this.touch || begun) return null
        begun = true
        val start = min(selection.start, selection.end)
        val end = max(selection.start, selection.end)
        // its anchor one of its ends, as move left it
        if (leftStart == start && leftEnd == end) {
            anchor = leftAnchor
            head = if (anchor == start) end else start
        } else {
            anchor = end
            head = start
        }
        leftStart = -1
        // the selection read is the one the editor has: else its text is not what lies between
        text = read?.takeIf { it.selected.length == end - start && start - it.before.length >= 0 }?.let {
            base = start - it.before.length
            moreBefore = it.before.length >= READ
            moreAfter = it.after.length >= READ
            it.before + it.selected + it.after
        }
        text?.let(graphemes::setText)
        val steps = pending
        pending = 0
        return if (steps != 0) move(steps) else null
    }

    /** The free end moved [steps] characters, negative to the left: the selection to set, or null where it did not move. */
    fun move(steps: Int): CursorRange? {
        if (!begun) {
            pending += steps
            return null
        }
        var h = head
        for (i in 0 until abs(steps)) h = next(h, steps > 0) ?: break
        if (h == head) return null
        head = h
        leftStart = min(anchor, head)
        leftEnd = max(anchor, head)
        leftAnchor = anchor
        return CursorRange(leftStart, leftEnd)
    }

    // the next character's edge from [at]; null at the end of the field
    private fun next(at: Int, right: Boolean): Int? {
        val t = text
        val i = at - base
        if (t != null && i in 0..t.length) {
            val edge = if (right) {
                if (i < t.length) graphemes.following(i) else BreakIterator.DONE
            } else {
                if (i > 0) graphemes.preceding(i) else BreakIterator.DONE
            }
            if (edge != BreakIterator.DONE) return base + edge
            // the field's end where all of that side was read
            if (if (right) !moreAfter else !moreBefore) return null
        }
        return if (right) at + 1 else (at - 1).takeIf { it >= 0 }
    }

    companion object {
        /** UTF-16 units read each side: far more than a finger reaches, a step a character. */
        const val READ = 500
    }
}
