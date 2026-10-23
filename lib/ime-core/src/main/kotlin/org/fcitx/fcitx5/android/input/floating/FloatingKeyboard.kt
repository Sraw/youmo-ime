/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.floating

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The keyboard floating over the app, as Gboard's and Sogou's on a tablet: smaller than docked,
 * the app laid out under it as if there were no keyboard, dragged by its handle anywhere on the
 * screen. Where it is kept is a [Spot], a share of the room it can move in each way, so a turn of
 * the screen keeps it at the same corner rather than off the edge.
 */
object FloatingKeyboard {

    /** Its width: a share of the screen's, within a phone's upright. */
    const val SHARE = 0.7f
    const val MIN_DP = 300
    const val MAX_DP = 420

    /** Its keys no shorter than this share of the docked keyboard's, however narrow it is. */
    const val MIN_HEIGHT_SHARE = 0.85f

    /** Where it is: 0 the left (top) of the room it moves in, 1 the right (bottom). */
    data class Spot(val x: Float, val y: Float)

    /** Where it first floats: the middle, at the bottom, where the docked keyboard was. */
    val DEFAULT = Spot(0.5f, 1f)

    /** How wide it is, in dp, on a screen [screenWidthDp] wide. */
    fun widthDp(screenWidthDp: Int): Int =
        (screenWidthDp * SHARE).toInt().coerceIn(MIN_DP, MAX_DP).coerceAtMost(screenWidthDp)

    /** The board's height, the docked one's [docked] as narrowed as its width is, but not past [MIN_HEIGHT_SHARE]. */
    fun height(docked: Int, widthDp: Int, screenWidthDp: Int): Int = (docked * share(widthDp, screenWidthDp)).toInt()

    /**
     * The keys' text, as much smaller as the keys are shorter: at the docked size a letter and the
     * digit under it ran into each other (y's tail on its 6).
     */
    fun textScale(widthDp: Int, screenWidthDp: Int): Float = share(widthDp, screenWidthDp)

    private fun share(widthDp: Int, screenWidthDp: Int) =
        (widthDp.toFloat() / screenWidthDp.coerceAtLeast(1)).coerceIn(MIN_HEIGHT_SHARE, 1f)

    /** Its left and top, in pixels, at [spot] in a parent [parentW] by [parentH], it [w] by [h]. */
    fun place(spot: Spot, w: Float, h: Float, parentW: Float, parentH: Float): Pair<Float, Float> =
        spot.x.coerceIn(0f, 1f) * (parentW - w).coerceAtLeast(0f) to spot.y.coerceIn(0f, 1f) * (parentH - h).coerceAtLeast(0f)

    /** The [Spot] of a keyboard dragged to [left], [top], kept within the parent. */
    fun spot(left: Float, top: Float, w: Float, h: Float, parentW: Float, parentH: Float): Spot =
        Spot(share(left, parentW - w), share(top, parentH - h))

    /**
     * Where it goes let go of by the docked keyboard (始终显示悬浮条, its grip long pressed): the
     * finger at [fingerX], [fingerY] (from the room's top left) on its grip, [gripX], [gripY] in it,
     * so the drag goes on from there; within the room, as a drag keeps it, where the finger is
     * too near an edge for that (the bottom above all: the docked grip is lower than it floats).
     */
    fun underFinger(fingerX: Float, fingerY: Float, gripX: Float, gripY: Float, w: Float, h: Float, parentW: Float, room: Float): Spot =
        spot(fingerX - gripX, fingerY - gripY, w, h, parentW, room)

    /** Docked to floating, along one axis: its scale as it starts to shrink, the docked keyboard's size [fromSize] over its own [toSize]. */
    fun startScale(fromSize: Float, toSize: Float): Float = if (toSize <= 0f) 1f else fromSize / toSize

    /**
     * Docked to floating, it shrinks from the docked keyboard ([from], [fromSize] along one axis) to
     * its own place ([to], [toSize]): the pivot, from its own edge, its [startScale] is about, so
     * scaled it covers the docked keyboard exactly. About that one point, a drag moving it as it
     * shrinks takes the shrink along. No change in size, the middle (any would do).
     */
    fun pivot(from: Float, fromSize: Float, to: Float, toSize: Float): Float {
        val scale = startScale(fromSize, toSize)
        return if (abs(1f - scale) < NO_SCALE) toSize / 2 else (from - to) / (1f - scale)
    }

    // closer to 1 than this the keyboard does not change size, and a pivot would be far off
    private const val NO_SCALE = 0.001f

    /**
     * Its corners' radius, in dp, as round as the keys (按键圆角): the keys' own [keyRadius] and
     * the least of their margins to its edge ([hMargin] at the sides, [vMargin] at the bottom), so
     * its curve runs alongside the corner keys' (nested round them) and clips none of them. Square
     * keys ([keyRadius] 0, 直角), square corners: the user's one setting for corners. Keys not
     * [bordered] are drawn square whatever their radius: the margin alone, or a big radius would
     * clip a corner key's face.
     */
    fun cornerDp(keyRadius: Int, hMargin: Int, vMargin: Int, bordered: Boolean = true): Int =
        if (keyRadius == 0) 0 else (if (bordered) keyRadius else 0) + minOf(hMargin, vMargin)

    /** How much of the keys' text colour the drag handle's strip has over the bar's. */
    const val HANDLE_INK = 0.1f

    /**
     * The drag handle's colour: [base], the bar's, with [ink], the keys' text colour, laid over by
     * [HANDLE_INK]. In a theme whose bar is the keyboard's colour the strip was not told apart from
     * the bar under it; the text's colour makes it a shade darker on a light theme, lighter on a dark.
     */
    fun handleColor(base: Int, ink: Int): Int {
        fun mix(shift: Int): Int {
            val b = base ushr shift and 0xFF
            val i = ink ushr shift and 0xFF
            return (b + (i - b) * HANDLE_INK).roundToInt() shl shift
        }
        return (base and 0xFF000000.toInt()) or mix(16) or mix(8) or mix(0)
    }

    // where in a room [room] wide [at] is; no room, the far edge, which is where it is all the same
    private fun share(at: Float, room: Float) = if (room <= 0f) 1f else (at / room).coerceIn(0f, 1f)
}
