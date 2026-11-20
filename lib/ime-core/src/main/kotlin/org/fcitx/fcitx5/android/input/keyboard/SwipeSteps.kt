/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

/** Whole steps of a threshold along an axis, the remainder carried so a slow drag adds up; 滑选 uses the backspace's threshold, so a character there is as far as one deleted. */
class SwipeSteps {
    /** At the last sample. */
    var last = -1f

    private var unconsumed = 0f

    /** Signed. */
    var total = 0
        private set

    fun start(at: Float) {
        reset()
        last = at
    }

    /** [last] is left where it was (the recognizer moves it itself). */
    fun consume(current: Float, threshold: Float): Int {
        val moved = current - last + unconsumed
        unconsumed = moved % threshold
        val count = (moved / threshold).toInt()
        total += count
        return count
    }

    fun step(current: Float, threshold: Float): Int = consume(current, threshold).also { last = current }

    fun reset() {
        unconsumed = 0f
        total = 0
    }
}

/** Vertical steps only once a sideways one was taken, so a swipe's own drift moves no line. */
class CrossSteps {
    private val steps = SwipeSteps()
    private var crossing = false

    /** [sideways]: the steps sideways this sample took. */
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
