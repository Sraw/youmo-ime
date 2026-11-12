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

/**
 * A finger's vertical steps once its touch has taken a sideways one, none before: the space's cursor
 * lines ([KeyGestureRecognizer.crossStepY]) and 滑选's ([SwipeSelection.lineSent]), so a swipe's own drift
 * up or down, or the one that reached 滑选, moves no line.
 */
class CrossSteps {
    private val steps = SwipeSteps()
    private var crossing = false

    /** The vertical steps of [threshold] to [at], [sideways] the steps sideways this sample took. */
    fun step(at: Float, sideways: Int, threshold: Float): Int {
        if (threshold <= 0f) return 0
        if (!crossing) {
            if (sideways == 0) return 0
            crossing = true
            steps.start(at)
            return 0
        }
        return steps.step(at, threshold)
    }

    fun reset() {
        crossing = false
        steps.reset()
    }
}
