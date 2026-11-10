/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

/**
 * A finger's distance along one axis as whole steps of a threshold, the remainder carried so a slow
 * drag still adds up. A key's swipe counts its steps with it ([KeyGestureRecognizer]: the backspace's
 * selection, the space's cursor, the vertical swipes), and 滑选 its sideways steps ([SwipeSelection]),
 * the backspace's threshold, so a character there is as far as one deleted.
 */
class SwipeSteps {
    /** Where the finger was at the last sample. */
    var last = -1f

    private var unconsumed = 0f

    /** The steps since [start] (or [reset]), signed. */
    var total = 0
        private set

    /** A drag beginning at [at]: nothing carried, no steps yet. */
    fun start(at: Float) {
        reset()
        last = at
    }

    /**
     * How many whole [threshold]s the finger crossed from [last] to [current], carrying the
     * remainder forward; [last] is left where it was (the recognizer moves it itself).
     */
    fun consume(current: Float, threshold: Float): Int {
        val moved = current - last + unconsumed
        unconsumed = moved % threshold
        val count = (moved / threshold).toInt()
        total += count
        return count
    }

    /** [consume], then [last] moved to [current]. */
    fun step(current: Float, threshold: Float): Int = consume(current, threshold).also { last = current }

    fun reset() {
        unconsumed = 0f
        total = 0
    }
}
