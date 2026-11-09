/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

/**
 * The room between a split keyboard's halves (分体), where a stroke (笔画筛选) may start though no
 * key is there ([GlideTouch.down]'s inGap). Found from where the letters are, so a layout that
 * splits has it and one that does not has none: in a row of letters, the space between two of them
 * at least [MIN_WIDTH] of a key wide. The keys' own spacing is far narrower, and a function key
 * (⇧, ⌫) sits at a row's end, never between its letters.
 */
object SplitGap {
    /** In key widths: the narrowest room between the halves is wider than a key (SplitKeyboard.MIN_GAP). */
    const val MIN_WIDTH = 0.5f

    /**
     * Whether [x], [y] is between the halves: on none of the letters [boxes] (left, top, right,
     * bottom of each in turn; several letters may share one, the nine keys'), with a letter of the
     * same row (one whose height [y] is in) on each side, those two [MIN_WIDTH] of a [keyWidth] apart or more.
     */
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
