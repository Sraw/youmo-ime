/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input

import kotlin.math.abs

/** Where the keyboard's notice goes, in window pixels, sliding out of the keyboard toward the screen's middle. */
object UndoChipPlacement {

    /** It goes back the other way, into the keyboard. */
    enum class Slide { Up, Down, Left, Right }

    /** Window pixels. */
    data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val width get() = right - left
        val height get() = bottom - top
    }

    data class Place(val left: Int, val top: Int, val slide: Slide)

    /**
     * Docked: with no candidates, over the bar near its top; with candidates, above the keyboard over the app (over the
     * bar it hid the second candidate). No room above: below [belowBottom] if the chip fits above [bottomLimit], else
     * over the bar after all.
     */
    fun top(
        candidates: Boolean,
        barTop: Int,
        aboveTop: Int,
        chipHeight: Int,
        gap: Int,
        belowBottom: Int? = null,
        bottomLimit: Int = Int.MAX_VALUE,
    ): Int {
        if (!candidates) return barTop + gap
        val above = aboveTop - gap - chipHeight
        return when {
            above >= 0 -> above
            belowBottom != null && belowBottom + gap + chipHeight <= bottomLimit -> belowBottom + gap
            else -> barTop + gap
        }
    }

    /** Across the middle; up out of the keyboard, or down when below. [stripTop]: the floating code strip's, which it goes above too. */
    fun docked(
        windowWidth: Int,
        chipWidth: Int,
        candidates: Boolean,
        barTop: Int,
        aboveTop: Int,
        chipHeight: Int,
        gap: Int,
        belowBottom: Int? = null,
        bottomLimit: Int = Int.MAX_VALUE,
        stripTop: Int? = null,
    ): Place {
        val above = if (stripTop == null) aboveTop else minOf(aboveTop, stripTop)
        val top = top(candidates, barTop, above, chipHeight, gap, belowBottom, bottomLimit)
        val below = candidates && belowBottom != null && top == belowBottom + gap
        return Place((windowWidth - chipWidth) / 2, top, if (below) Slide.Down else Slide.Up)
    }

    /**
     * Floating: on the side of the keyboard toward [screen]'s middle, the axis it is further off on winning (up and down
     * on a tie); not fitting, the opposite side, then the other axis's, then over the keyboard's top. The floating code
     * [strip] counts as the keyboard's.
     */
    fun floating(screen: Box, board: Box, chipWidth: Int, chipHeight: Int, gap: Int, strip: Box? = null): Place {
        val keyboard = if (strip == null) board else Box(
            minOf(board.left, strip.left), minOf(board.top, strip.top),
            maxOf(board.right, strip.right), maxOf(board.bottom, strip.bottom),
        )
        val dx = off(keyboard.left + keyboard.right, screen.left + screen.right, screen.width)
        val dy = off(keyboard.top + keyboard.bottom, screen.top + screen.bottom, screen.height)
        val vertical = if (dy >= 0f) listOf(Slide.Up, Slide.Down) else listOf(Slide.Down, Slide.Up)
        val horizontal = if (dx > 0f) listOf(Slide.Left, Slide.Right) else listOf(Slide.Right, Slide.Left)
        val order = if (abs(dx) > abs(dy)) horizontal + vertical else vertical + horizontal
        for (slide in order) {
            val place = beside(slide, screen, keyboard, chipWidth, chipHeight, gap)
            if (place != null) return place
        }
        return Place(centred(keyboard.left, keyboard.right, chipWidth, screen.left, screen.right), keyboard.top + gap, Slide.Down)
    }

    // -1 to 1 on the screen
    private fun off(twiceCentre: Int, twiceMiddle: Int, size: Int): Float =
        if (size <= 0) 0f else (twiceCentre - twiceMiddle).toFloat() / size

    private fun beside(slide: Slide, screen: Box, keyboard: Box, w: Int, h: Int, gap: Int): Place? = when (slide) {
        Slide.Up -> (keyboard.top - gap - h).takeIf { it >= screen.top && w <= screen.width }
            ?.let { Place(centred(keyboard.left, keyboard.right, w, screen.left, screen.right), it, slide) }
        Slide.Down -> (keyboard.bottom + gap).takeIf { it + h <= screen.bottom && w <= screen.width }
            ?.let { Place(centred(keyboard.left, keyboard.right, w, screen.left, screen.right), it, slide) }
        Slide.Right -> (keyboard.right + gap).takeIf { it + w <= screen.right && h <= screen.height }
            ?.let { Place(it, centred(keyboard.top, keyboard.bottom, h, screen.top, screen.bottom), slide) }
        Slide.Left -> (keyboard.left - gap - w).takeIf { it >= screen.left && h <= screen.height }
            ?.let { Place(it, centred(keyboard.top, keyboard.bottom, h, screen.top, screen.bottom), slide) }
    }

    // kept within [min]..[max] while it fits
    private fun centred(from: Int, to: Int, size: Int, min: Int, max: Int): Int =
        ((from + to - size) / 2).coerceAtMost(max - size).coerceAtLeast(min)
}
