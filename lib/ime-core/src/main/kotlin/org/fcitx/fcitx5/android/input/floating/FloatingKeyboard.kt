/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.floating

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
    fun height(docked: Int, widthDp: Int, screenWidthDp: Int): Int =
        (docked * (widthDp.toFloat() / screenWidthDp.coerceAtLeast(1)).coerceIn(MIN_HEIGHT_SHARE, 1f)).toInt()

    /** Its left and top, in pixels, at [spot] in a parent [parentW] by [parentH], it [w] by [h]. */
    fun place(spot: Spot, w: Float, h: Float, parentW: Float, parentH: Float): Pair<Float, Float> =
        spot.x.coerceIn(0f, 1f) * (parentW - w).coerceAtLeast(0f) to spot.y.coerceIn(0f, 1f) * (parentH - h).coerceAtLeast(0f)

    /** The [Spot] of a keyboard dragged to [left], [top], kept within the parent. */
    fun spot(left: Float, top: Float, w: Float, h: Float, parentW: Float, parentH: Float): Spot =
        Spot(share(left, parentW - w), share(top, parentH - h))

    // where in a room [room] wide [at] is; no room, the far edge, which is where it is all the same
    private fun share(at: Float, room: Float) = if (room <= 0f) 1f else (at / room).coerceIn(0f, 1f)
}
