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
}
