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
    fun sendable(line: Line, before: CharSequence?, after: CharSequence?, rushed: Boolean = false): Boolean {
        val side = if (line == Line.Up) before else after
        if (side.isNullOrEmpty()) return false
        // [rushed]: the line before went out a moment ago and the editor may not have moved yet, so what
        // is read is stale: another goes out only with a newline that way, a line surely left to go to
        return !rushed || '\n' in side
    }
}
