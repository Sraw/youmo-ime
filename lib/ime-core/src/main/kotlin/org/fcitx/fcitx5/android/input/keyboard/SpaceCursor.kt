/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

/**
 * The space's cursor moved up and down (060-space-2d): after its sideways slide has moved the
 * cursor, a slide up or down moves it a line ([KeyGestureRecognizer.crossStepY]).
 */
object SpaceCursor {
    enum class Line { Up, Down }

    /**
     * What [steps] vertical steps send, finger up negative: a line each, in a field of more than one
     * line ([multiLine]) alone, as a single line's Up moves the focus away; nothing while [composing],
     * whose Up and Down move among the candidates.
     */
    fun vertical(steps: Int, multiLine: Boolean, composing: Boolean): List<Line> =
        if (steps == 0 || !multiLine || composing) emptyList()
        else List(kotlin.math.abs(steps)) { if (steps < 0) Line.Up else Line.Down }

    /**
     * Whether the editor's Up (Down) may be sent with [before] (or [after]) the cursor: not at the
     * very start (end), where Android's `Selection.moveUp` gives the key back and it moves the focus
     * to another field. Elsewhere on the first (last) line it goes to the start (end), as a wrapped
     * paragraph's lines are not told by a newline.
     */
    fun sendable(line: Line, before: CharSequence?, after: CharSequence?, rushed: Int = 0): Boolean {
        val side = if (line == Line.Up) before else after
        if (side.isNullOrEmpty()) return false
        // [rushed]: the lines rushed a moment ago not landed yet ([LineRush.unlanded]), so what is read is
        // stale by them: another goes out only while more newlines lie that way than those lines, a line surely left
        return rushed == 0 || side.count { it == '\n' } > rushed
    }

    /**
     * Whether a line [sendable] holds goes to the field's very start (end) instead: rushed lines bring the
     * cursor onto the first (last) line ([side], read before they landed, has no more newlines than
     * [rushed], the rushed lines not landed yet: all of them, as only an [LineRush.unmoved] read comes
     * here), where the key would have gone to the start (end), and [side] was read [whole] so the
     * start (end) is where it ends. Not at the very start (end) itself, nor with no lines rushed. Only
     * for a read surely stale ([LineRush.unmoved]): read after the lines landed, the edge would skip lines.
     */
    fun toEdge(line: Line, before: CharSequence?, after: CharSequence?, rushed: Int, whole: Boolean): Boolean {
        val side = if (line == Line.Up) before else after
        return whole && rushed > 0 && !side.isNullOrEmpty() && side.count { it == '\n' } <= rushed
    }

    /**
     * Lines sent a moment ago, the editor maybe not moved yet: what [sendable]'s `rushed` takes. Counted
     * one way only: a line the other way reads the side it goes to stale by fewer newlines, never more.
     */
    class LineRush(private val settleMs: Long = SETTLE_MS) {
        private var lastAt = 0L
        private var last: Line? = null
        private var count = 0

        /** The lines rushed before one to [line] at [now] (ms). */
        fun rushed(line: Line, now: Long): Int = if (line != last || now - lastAt >= settleMs) 0 else count

        // the side read when the rush began, its length and newlines: a read of another length is one the editor moved since
        private var readAt = -1
        private var linesAt = 0

        /**
         * Whether a read of [read] chars of the side [line] goes to is surely stale: lines are rushed and
         * the side reads as long as when the rush began, so none of them landed yet. A read that changed
         * is the editor having moved some of them, how many unknown: not stale for sure. Relies on [read]
         * being the side's whole length: a side cut at a read limit reads as long each time, so a caller
         * that reads a capped side checks that itself (the service's `whole` for [toEdge]).
         */
        fun unmoved(line: Line, now: Long, read: Int): Boolean = rushed(line, now) > 0 && read == readAt

        /**
         * The rushed lines not landed yet, judged by [side] read now: all of them while it reads as at the
         * rush's start ([unmoved]); once it changed, at least one landed, and as many as the newlines it
         * lost, so [sendable] counts only the rest and a line the user slid is sent, not held. A side cut at a
         * read limit (more than the service's LINE_REACH chars that way) reads as long each time, and its
         * newlines are a window's, not comparable with a whole read's: there it counts all rushed lines (or
         * one landed) as before, erring toward holding a line, never toward skipping one.
         */
        fun unlanded(line: Line, now: Long, side: CharSequence?): Int {
            val rushed = rushed(line, now)
            if (rushed == 0 || side == null || side.length == readAt) return rushed
            val landed = (linesAt - side.count { it == '\n' }).coerceAtLeast(1)
            return (rushed - landed).coerceAtLeast(0)
        }

        /** A line to [line] sent at [now], [read] the length of the side it goes to read before it, [lines] its newlines. */
        fun sent(line: Line, now: Long, read: Int = -1, lines: Int = 0) {
            if (rushed(line, now) == 0) {
                readAt = read
                linesAt = lines
            }
            count = rushed(line, now) + 1
            last = line
            lastAt = now
        }

        companion object {
            /** How long after a line key the editor may not have moved yet. */
            const val SETTLE_MS = 80L
        }
    }
}
