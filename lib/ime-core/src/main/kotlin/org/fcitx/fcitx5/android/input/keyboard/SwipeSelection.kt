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
 * none), a UTF-16 unit, the start of the field the end to the left. To the right, where the field
 * may end unseen, a step past what is known to be there is tried alone ([probe]): the steps after it
 * wait, counted, for the editor to report it taken (every prediction reported, [move]'s `settled`, so
 * an older report cannot pass for it), as one past the field's end it ignores, its selection left as
 * it was; a step back from one not taken counts from the finger, the selection back where the editor
 * still is till the finger is back on what is known. Given up so, or the touch [end]ing on it, its
 * selection is given back ([untaken]), for the caller to stop predicting it.
 */
class SwipeSelection {

    private var touch = 0
    private var live = false
    private var begun = false
    private var pending = 0

    private var anchor = 0
    private var head = 0

    // what was read as it began: [text] from [base] in the field, whether more was there after it
    private var text: String? = null
    private var base = 0
    private var moreAfter = false
    private val graphemes: BreakIterator = BreakIterator.getCharacterInstance()

    // the field known to reach this far: the selection as it began, what was read, a probe the editor took
    private var sure = 0

    // a head past [sure] set, not yet seen in the editor's report: -1, none; the steps moved since, waiting for it
    private var probe = -1
    private var queued = 0

    /** The selection of a probe given up by the last [move]: set, never reported, no longer to be predicted. */
    var untaken: CursorRange? = null
        private set

    // the selection the last touch left (none: -1), and its anchor: a touch beginning on it goes on from there.
    // Ints, not a CursorRange: its equality is its array's identity
    private var leftStart = -1
    private var leftEnd = -1
    private var leftAnchor = 0

    /** A touch reached 滑选: its number, for [begin]; steps from here on wait for it. */
    fun reached(): Int {
        touch++
        live = true
        begun = false
        pending = 0
        return touch
    }

    /**
     * The touch lifted: its steps from here on nothing, those moved before it [begin]s still taken
     * there (what is typed committed first may take the host longer than a quick swipe), none past
     * what is known to be in the field. The selection last set where it was a [probe] the editor has
     * not reported taking, or null: the editor may never take it (one past the field's end), so the
     * caller stops predicting it; the next touch goes on from where the editor was.
     */
    fun end(): CursorRange? {
        live = false
        return giveBack()
    }

    /** As [end], the touch no longer to begin either: another key acted (a second finger), the field changed. */
    fun cancel(): CursorRange? {
        live = false
        pending = 0
        if (!begun) touch++
        return giveBack()
    }

    private fun giveBack(): CursorRange? {
        queued = 0
        untaken = null
        if (!begun || probe < 0) return null
        val given = giveUp()
        leftStart = min(anchor, head)
        leftEnd = max(anchor, head)
        leftAnchor = anchor
        return given
    }

    // the probe not taken: the head back where the editor still has it, the probe's selection given back
    private fun giveUp(): CursorRange {
        val given = CursorRange(min(anchor, probe), max(anchor, probe))
        head = sure
        probe = -1
        return given
    }

    /**
     * [touch] begins at the field's [selection], [read] around it ([READ] each side, the selection
     * itself; null where none was), the read's cursor [at] that far into the field (-1: not known):
     * the anchor at its end, its start the free end, unless it is the one the last touch left, whose
     * anchor it keeps. The selection to set for the steps moved meanwhile, or null: none moved, or
     * [touch] is no longer the one going on.
     */
    fun begin(touch: Int, selection: CursorRange, read: EditPlan.Field?, at: Int = -1): CursorRange? {
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
        // a touch that moves nothing leaves the selection as it found it, for the next to go on from
        leftStart = start
        leftEnd = end
        leftAnchor = anchor
        probe = -1
        queued = 0
        // the selection read is the one the editor has, where the read says (a cursor tracked behind
        // the editor's, its text shifted, would split a character): else its text is not what lies between
        text = read?.takeIf { it.selected.length == end - start && start - it.before.length >= 0 && (at < 0 || at == start) }?.let {
            base = start - it.before.length
            moreAfter = it.after.length >= READ
            it.before + it.selected + it.after
        }
        text?.let(graphemes::setText)
        sure = max(end, text?.let { base + it.length } ?: end)
        val steps = pending
        pending = 0
        return if (steps != 0) step(steps, probing = live) else null
    }

    /**
     * The free end moved [steps] characters, negative to the left: the selection to set, or null where
     * it did not move. [reported] the selection the editor last reported, [settled] whether it has
     * reported every one predicted: a [probe] is taken only where both say so.
     */
    fun move(steps: Int, reported: CursorRange? = null, settled: Boolean = false): CursorRange? {
        untaken = null
        if (!live) return null
        if (!begun) {
            pending += steps
            return null
        }
        var n = steps
        if (probe >= 0) {
            n += queued
            queued = 0
            if (settled && reported?.endsAt(probe) == true) {
                sure = probe
                probe = -1
            } else if (n >= 0) {
                // still waiting: kept, as far as the finger went
                queued = n
                return null
            } else {
                // not taken: the editor's end is where it was, the probe's step, the finger's, counted
                untaken = giveUp()
                n++
            }
        }
        return step(n, probing = true)
    }

    private fun CursorRange.endsAt(at: Int) = start == at || end == at

    // [n] steps from [head]; [probing], one past what is known tried, else none
    private fun step(n: Int, probing: Boolean): CursorRange? {
        var h = head
        var left = abs(n)
        // a probe the last: the steps past it wait for it
        while (left > 0 && probe < 0) {
            val e = next(h, n > 0)?.takeIf { probing || it <= sure } ?: break
            h = e
            left--
            if (h > sure) probe = h
        }
        if (probe >= 0) queued = left
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
            // the field's end where all of that side was read: to the left, where the read began at
            // its start (an app may give less than asked of a longer field, review M7)
            if (if (right) !moreAfter else base == 0) return null
        }
        return if (right) at + 1 else (at - 1).takeIf { it >= 0 }
    }

    companion object {
        /** UTF-16 units read each side: far more than a finger reaches, a step a character. */
        const val READ = 500
    }
}
