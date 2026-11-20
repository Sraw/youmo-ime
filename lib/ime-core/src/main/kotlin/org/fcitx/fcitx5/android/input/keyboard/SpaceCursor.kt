/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

/** After the space's sideways slide has moved the cursor, a slide up or down moves it a line. */
object SpaceCursor {
    enum class Line { Up, Down }

    /** Finger up negative; only in a [multiLine] field (a single line's Up moves the focus); nothing while [composing]. */
    fun vertical(steps: Int, multiLine: Boolean, composing: Boolean): List<Line> =
        if (steps == 0 || !multiLine || composing) emptyList()
        else List(kotlin.math.abs(steps)) { if (steps < 0) Line.Up else Line.Down }

    /** Not at the very start (end): Android's `Selection.moveUp` gives the key back and focus moves to another field. */
    fun sendable(line: Line, before: CharSequence?, after: CharSequence?, rushed: Int = 0): Boolean {
        val side = if (line == Line.Up) before else after
        if (side.isNullOrEmpty()) return false
        // [rushed] lines not landed yet make the read stale: another goes only while more newlines lie that way
        return rushed == 0 || side.count { it == '\n' } > rushed
    }

    /** Rushed lines bringing the cursor onto the first (last) line go to the very start (end) instead; only for a surely stale ([LineRush.unmoved]) [whole] read. */
    fun toEdge(line: Line, before: CharSequence?, after: CharSequence?, rushed: Int, whole: Boolean): Boolean {
        val side = if (line == Line.Up) before else after
        return whole && rushed > 0 && !side.isNullOrEmpty() && side.count { it == '\n' } <= rushed
    }

    /** Lines sent a moment ago the editor may not have moved yet; counted one way only. */
    class LineRush(private val settleMs: Long = SETTLE_MS) {
        private var lastAt = 0L
        private var last: Line? = null
        private var count = 0

        fun rushed(line: Line, now: Long): Int = if (line != last || now - lastAt >= settleMs) 0 else count

        // the side read when the rush began, its length and newlines: a read of another length is one the editor moved since
        private var readAt = -1
        private var linesAt = 0

        /** Lines rushed and the side as long as at the rush's start; relies on [read] being the whole side (capped reads are the caller's check). */
        fun unmoved(line: Line, now: Long, read: Int): Boolean = rushed(line, now) > 0 && read == readAt

        /** Once [side] changed, as many landed as the newlines it lost; a side cut at a read limit errs toward holding a line, never skipping one. */
        fun unlanded(line: Line, now: Long, side: CharSequence?): Int {
            val rushed = rushed(line, now)
            if (rushed == 0 || side == null || side.length == readAt) return rushed
            val landed = (linesAt - side.count { it == '\n' }).coerceAtLeast(1)
            return (rushed - landed).coerceAtLeast(0)
        }

        /** [read] the length of the side read before it, [lines] its newlines. */
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
            const val SETTLE_MS = 80L
        }
    }
}
