/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input

import kotlin.math.abs

/**
 * Where the keyboard's notice (the undo chip) goes, in the window's pixels, and which way it slides
 * out of the keyboard as it comes (dev/design/motion-notices-handle.md: always out of the keyboard,
 * toward the screen's middle, where the eyes are).
 */
object UndoChipPlacement {

    /** Which way the chip moves as it comes; it goes back the other way, into the keyboard. */
    enum class Slide { Up, Down, Left, Right }

    /** A rectangle in the window's pixels. */
    data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val width get() = right - left
        val height get() = bottom - top
    }

    /** The chip's top-left corner, and the way it slides out. */
    data class Place(val left: Int, val top: Int, val slide: Slide)

    /**
     * Where the chip's top goes docked (user decision 2026-10-22, PY-80: over the bar it hid the
     * second candidate). With no candidates shown it sits over the bar, near its top ([barTop] + [gap]).
     * With candidates it goes above the keyboard ([aboveTop] - [gap] - [chipHeight]): over the app,
     * under no key and no candidate the user is about to tap. Not the toolbar row: while composing
     * that row is the candidates. With no room above (a window at the screen's top), below
     * [belowBottom] when there is somewhere below and the whole chip fits there above [bottomLimit]
     * (the lowest window y it may reach, the navigation bar left out), else over the bar after all.
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

    /**
     * Docked: [top]'s place, across the middle of a window [windowWidth] wide. Up out of the
     * keyboard, or down when it went below it.
     */
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
    ): Place {
        val top = top(candidates, barTop, aboveTop, chipHeight, gap, belowBottom, bottomLimit)
        val below = candidates && belowBottom != null && top == belowBottom + gap
        return Place((windowWidth - chipWidth) / 2, top, if (below) Slide.Down else Slide.Up)
    }

    /**
     * Floating: on the side of [keyboard] toward the middle of [screen] (the room the keyboard may
     * float in, status and navigation bars left out), sliding that way. Off the middle, each axis by
     * the part of half the screen it is off by; the one off more wins, up and down on a tie, as
     * docked: a keyboard pinned to the top has it below, pinned left to its right, pinned right to
     * its left, low on the screen above. Where the chip does not fit (the screen's edge), the
     * opposite side, then the other axis's; with no room anywhere, over the keyboard's top.
     */
    fun floating(screen: Box, keyboard: Box, chipWidth: Int, chipHeight: Int, gap: Int): Place {
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

    // how far [twiceCentre]/2 is from [twiceMiddle]/2, as a part of half of [size]: -1 to 1 on the screen
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

    // [size] centred on [from]..[to], kept within [min]..[max] while it fits
    private fun centred(from: Int, to: Int, size: Int, min: Int, max: Int): Int =
        ((from + to - size) / 2).coerceAtMost(max - size).coerceAtLeast(min)
}
