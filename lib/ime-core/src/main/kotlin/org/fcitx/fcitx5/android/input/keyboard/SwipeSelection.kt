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
 * 滑选: a swipe that reached the shortcut then moved sideways, the free end ([head]) a character a step, the
 * [anchor] where the cursor was. Steps moved before [begin] (the host commits what is typed first) are kept.
 * A character is a grapheme of the text read around the selection, else a UTF-16 unit. To the right, where
 * the field may end unseen, a step past what is known is a [probe] tried alone until the editor reports it
 * taken (an editor ignores a selection past its end); one given up is handed back ([untaken]).
 * Up and down the editor moves the free end a line itself ([lineSent], [lineLanded]).
 */
class SwipeSelection {

    private var touch = 0
    private var live = false
    private var begun = false
    private var pending = 0

    private var linesOut = 0
    private var liningSteps = 0

    // lines given up on unreported that the editor may yet report, late
    private var linesLate = 0

    private var anchor = 0
    private var head = 0

    private var text: String? = null
    private var base = 0
    private val graphemes: BreakIterator = BreakIterator.getCharacterInstance()

    // the field known to reach this far: the selection as it began, what was read, a probe the editor took
    private var sure = 0

    // a head past [sure] not yet reported: -1, none
    private var probe = -1
    private var queued = 0

    /** Set, never reported: no longer to be predicted. */
    var untaken: CursorRange? = null
        private set

    // the selection the last touch left (-1: none): a touch beginning on it goes on from there. Ints: CursorRange's equality is identity
    private var leftStart = -1
    private var leftEnd = -1
    private var leftAnchor = 0

    // a probe the last touch gave up unconfirmed: the editor may yet report it taken
    private var leftProbe = -1

    /** Its number, for [begin]; steps from here on wait for it. */
    fun reached(): Int {
        touch++
        live = true
        begun = false
        pending = 0
        linesOut = 0
        liningSteps = 0
        linesLate = 0
        return touch
    }

    /** Steps before [begin] still count; a [probe] the editor has not reported taken gives back null so the caller stops predicting it. */
    fun end(reported: CursorRange? = null, settled: Boolean = false): CursorRange? {
        live = false
        return giveBack(reported, settled)
    }

    /** Another key acted (a second finger) or the field changed. */
    fun cancel(reported: CursorRange? = null, settled: Boolean = false): CursorRange? {
        live = false
        pending = 0
        if (!begun) touch++
        return giveBack(reported, settled)
    }

    private fun giveBack(reported: CursorRange?, settled: Boolean): CursorRange? {
        queued = 0
        untaken = null
        if (!begun || probe < 0) return null
        if (settled && reported?.endsAt(probe) == true) {
            sure = probe
            probe = -1
            leave()
            return null
        }
        val given = probe
        val range = giveUp()
        leave()
        leftProbe = given
        return range
    }

    private fun leave() {
        leftStart = min(anchor, head)
        leftEnd = max(anchor, head)
        leftAnchor = anchor
        leftProbe = -1
    }

    private fun giveUp(): CursorRange {
        val given = CursorRange(min(anchor, probe), max(anchor, probe))
        head = sure
        probe = -1
        return given
    }

    /**
     * The anchor at the selection's end, the start the free end, unless it is the one the last touch left. Null
     * when no steps moved meanwhile, or [touch] is no longer the one going on.
     */
    fun begin(touch: Int, selection: CursorRange, read: EditPlan.Field?, at: Int = -1): CursorRange? {
        if (touch != this.touch || begun) return null
        begun = true
        val start = min(selection.start, selection.end)
        val end = max(selection.start, selection.end)
        if (leftStart == start && leftEnd == end) {
            anchor = leftAnchor
            head = if (anchor == start) end else start
        } else if (leftProbe >= 0 && min(leftAnchor, leftProbe) == start && max(leftAnchor, leftProbe) == end) {
            // the probe given up taken after all, its report late
            anchor = leftAnchor
            head = leftProbe
        } else {
            anchor = end
            head = start
        }
        // a touch that moves nothing leaves the selection as it found it, for the next to go on from
        leftStart = start
        leftEnd = end
        leftAnchor = anchor
        leftProbe = -1
        probe = -1
        queued = 0
        sure = end
        readAt(start, end, read, at)
        val steps = pending
        pending = 0
        return if (steps != 0) step(steps, probing = live) else null
    }

    // the editor's selection must be where the read says, or a lagging cursor would split a character
    private fun readAt(start: Int, end: Int, read: EditPlan.Field?, at: Int) {
        text = read?.takeIf { it.selected.length == end - start && start - it.before.length >= 0 && (at < 0 || at == start) }?.let {
            base = start - it.before.length
            it.before + it.selected + it.after
        }
        text?.let(graphemes::setText)
        sure = max(sure, text?.let { base + it.length } ?: end)
    }

    val anchorAt: Int get() = anchor

    val headAt: Int get() = head

    val lineable: Boolean get() = live && begun && probe < 0

    /** What the editor reports now is a line landing. */
    val lining: Boolean get() = linesOut > 0

    val awaitingLines: Boolean get() = linesOut > 0 || linesLate > 0

    fun lineSent() {
        linesOut++
    }

    /**
     * One line landed: the free end where the report has it. Once all landed, the steps moved meanwhile are
     * applied. A report not moving the free end is no line (an editor echoing in its own order). Late reports of
     * given-up lines are taken until [linesForgotten]. Past what was read, the field is [read] anew.
     */
    fun lineLanded(reported: CursorRange, read: () -> Pair<EditPlan.Field?, Int>? = { null }): CursorRange? {
        if (linesOut == 0 && linesLate == 0) return null
        val moved = when (anchor) {
            reported.start -> reported.end
            reported.end -> reported.start
            else -> {
                linesOut = 0
                liningSteps = 0
                linesLate = 0
                return null
            }
        }
        if (moved == head) return null
        val late = linesOut == 0
        if (late) linesLate-- else linesOut--
        head = moved
        sure = max(sure, head)
        if (text.let { it == null || head - base !in 0..it.length }) {
            read()?.let { (field, at) -> readAt(min(anchor, head), max(anchor, head), field, at) }
        }
        leave()
        return when {
            late -> if (live) null else CursorRange(leftStart, leftEnd)
            linesOut == 0 -> afterLines()
            else -> null
        }
    }

    /** For an editor that ignores lines: the waiting steps taken from where the end is. */
    fun linesUnreported(): CursorRange? {
        if (linesOut == 0) return null
        linesLate += linesOut
        linesOut = 0
        return afterLines()
    }

    fun linesForgotten() {
        linesLate = 0
    }

    private fun afterLines(): CursorRange? {
        val n = liningSteps
        liningSteps = 0
        return when {
            !live -> {
                if (n != 0) step(n, probing = false)
                CursorRange(leftStart, leftEnd)
            }
            n != 0 -> step(n, probing = true)
            else -> null
        }
    }

    /** [settled]: the editor has reported every prediction; a [probe] is taken only when both say so. */
    fun move(steps: Int, reported: CursorRange? = null, settled: Boolean = false): CursorRange? {
        untaken = null
        if (!live) return null
        if (!begun) {
            pending += steps
            return null
        }
        if (linesOut > 0) {
            liningSteps += steps
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

    // a probe moves the right end alone: a start there is the cursor put there by something else
    private fun CursorRange.endsAt(at: Int) = end == at

    private fun step(n: Int, probing: Boolean): CursorRange? {
        var h = head
        var left = abs(n)
        while (left > 0 && probe < 0) {
            val e = next(h, n > 0)?.takeIf { probing || it <= sure } ?: break
            h = e
            left--
            if (h > sure) probe = h
        }
        if (probe >= 0) queued = left
        if (h == head) return null
        head = h
        leave()
        return CursorRange(leftStart, leftEnd)
    }

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
            // an app may give less than asked; to the right nothing says where the field ends, so a step past it is a probe
            if (!right && base == 0) return null
        }
        return if (right) at + 1 else (at - 1).takeIf { it >= 0 }
    }

    companion object {
        const val READ = 500
    }
}
