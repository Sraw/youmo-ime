/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

/** The room between a split keyboard's halves, where a stroke may start: two same-row letters at least [MIN_WIDTH] of a key apart (key spacing is far narrower, function keys sit at row ends). */
object SplitGap {
    /** In key widths: SplitKeyboard.MIN_GAP (0.12 of the width) is about 1.2 keys on a ten-letter row. */
    const val MIN_WIDTH = 0.5f

    /** [boxes]: left, top, right, bottom of each letter (the nine keys' may share one). */
    fun contains(boxes: FloatArray, x: Float, y: Float, keyWidth: Float): Boolean {
        var left = Float.NEGATIVE_INFINITY
        var right = Float.POSITIVE_INFINITY
        for (i in 0 until boxes.size / 4 * 4 step 4) {
            val l = boxes[i]
            val r = boxes[i + 2]
            // another row's: its letters stand elsewhere (⇧ is under A, not between letters)
            if (y < boxes[i + 1] || y >= boxes[i + 3]) continue
            if (x >= l && x < r) return false
            if (r <= x) left = maxOf(left, r)
            if (l >= x) right = minOf(right, l)
        }
        return left.isFinite() && right.isFinite() && right - left >= MIN_WIDTH * keyWidth
    }
}
