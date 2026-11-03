/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

/**
 * How a key's label gets out of the way of what its top right corner draws (a shortcut's icon or
 * text, a letter's symbol), measured by their ink, not their views: a view is the line's height and
 * advance, and on 大千's keys it met the icon where the glyphs did not, moving symbols that touched
 * nothing. Nothing while they do not meet; else the label moves left or down, whichever clears it in
 * less; neither alone: left as far as the face's edge and down as far as it has room, then a text
 * mark is smaller towards its corner (an icon is small already), then the label smaller towards its
 * bottom left: 大千's narrow keys, a letter beside its
 * symbol on the floating and one-handed keyboards at 紧凑.
 */
object KeyMarkYield {

    /** A box's sides, in one view's coordinates. */
    data class Ink(val left: Float, val top: Float, val right: Float, val bottom: Float) {
        val width: Float get() = right - left
        val height: Float get() = bottom - top
    }

    /** The label moved by [dx], [dy], then scaled by [labelScale] about its ink's bottom left; the mark by [markScale] about its top right. */
    data class Yield(val dx: Float, val dy: Float, val labelScale: Float, val markScale: Float) {
        companion object {
            val None = Yield(0f, 0f, 1f, 1f)
        }
    }

    /** A mark's text a quarter smaller at most: less, a symbol of 9 dp (紧凑's) is hard to read. */
    const val MAX_MARK_SHRINK = 0.25f

    /** The label a fifth smaller at most. */
    const val MAX_LABEL_SHRINK = 0.2f

    /**
     * How [label] yields to [mark] in [face], kept [gap] apart; [markShrinks] for a text mark. The
     * mark is the top right corner's: the label clears it by being left of it or below it.
     */
    fun of(label: Ink, mark: Ink, face: Ink, gap: Float, markShrinks: Boolean): Yield {
        val meets = label.right > mark.left - gap && label.top < mark.bottom + gap &&
            label.left < mark.right && label.bottom > mark.top
        if (!meets) return Yield.None
        val over = label.right - (mark.left - gap)
        val under = mark.bottom + gap - label.top
        val leftRoom = (label.left - face.left - gap).coerceAtLeast(0f)
        val downRoom = (face.bottom - gap - label.bottom).coerceAtLeast(0f)
        // one way clears it: the shorter of them (a wide label under a text mark's bottom edge: down a
        // little, not across the key), left when both are as short
        if (over <= leftRoom && (over <= under || under > downRoom)) return Yield(-over, 0f, 1f, 1f)
        if (under <= downRoom) return Yield(0f, under, 1f, 1f)
        val left = leftRoom.coerceAtMost(over)
        val lower = downRoom.coerceAtMost(under)
        val moved = Ink(label.left - left, label.top + lower, label.right - left, label.bottom + lower)
        // shrunk about its top right, it clears the label by its left side or its bottom, whichever needs less
        val markScale = if (!markShrinks) 1f else maxOf(
            (mark.right - (moved.right + gap)) / mark.width,
            (moved.top - gap - mark.top) / mark.height,
        ).coerceIn(1f - MAX_MARK_SHRINK, 1f)
        val shrunk = Ink(mark.right - mark.width * markScale, mark.top, mark.right, mark.top + mark.height * markScale)
        // what is left, about its bottom left: its right side in by width × the shrink, its top down by height × it
        val still = minOf((moved.right - (shrunk.left - gap)) / moved.width, (shrunk.bottom + gap - moved.top) / moved.height)
        val labelScale = if (still > 0f) 1f - still.coerceAtMost(MAX_LABEL_SHRINK) else 1f
        return Yield(-left, lower, labelScale, markScale)
    }
}
