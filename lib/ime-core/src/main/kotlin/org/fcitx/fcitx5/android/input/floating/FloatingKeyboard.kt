/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.floating

import kotlin.math.abs
import kotlin.math.hypot
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

    /**
     * The narrowest the user's resize makes it, dp (061-floating-resize Q1): the strip's three (resize 56, grip 120,
     * dock 56) fit, and every panel's keys and bar were looked at there. A fixed floor, not the widest row's keys: one
     * worked out from the layout would make the keyboard jump as the layout changes.
     */
    const val MIN_RESIZE_DP = 260

    /**
     * Its size: [widthDp] wide, its keys [keysHeightPx] high, their text at [textScale] (0.6.0's, the default size's).
     * All of it, the keys' gaps, corners and marks too, [resize] times as big as at its default width: the keys keep
     * their shape (061-floating-resize §2.1). The bars over and under the keys only get wider (the user's Q6).
     */
    data class Size(val widthDp: Int, val keysHeightPx: Int, val textScale: Float, val resize: Float)

    /** How wide the user may make it, dp. */
    data class Bounds(val minDp: Int, val maxDp: Int)

    /**
     * How wide the user may make it on a screen [screenWidthDp] wide: down to [MIN_RESIZE_DP], up to the docked
     * keyboard's width [dockedWidthDp] or as wide as fits in [roomPx] of height, its keys as tall as that width makes
     * them (of the docked keys' [dockedKeysPx]) and the bars ([barsPx]) under or over them, whichever is less. Never
     * narrower nor wider than its default width ([widthDp]): that it always has, as it had in 0.6.0.
     */
    fun bounds(screenWidthDp: Int, dockedWidthDp: Int, dockedKeysPx: Int, barsPx: Int, roomPx: Int): Bounds {
        val w0 = widthDp(screenWidthDp)
        val keysPxPerDp = dockedKeysPx * share(w0, screenWidthDp) / w0.coerceAtLeast(1)
        val fits = if (keysPxPerDp <= 0f) Int.MAX_VALUE else ((roomPx - barsPx) / keysPxPerDp).toInt()
        return Bounds(minOf(MIN_RESIZE_DP, w0), maxOf(w0, minOf(dockedWidthDp, fits)))
    }

    /**
     * Its size on a screen [screenWidthDp] wide, the docked keys [dockedKeysPx] high: [keptWidthDp] wide, the width the
     * user resized it to (negative: never resized, the default [widthDp]), within [bounds]; its keys as tall, and their
     * text as big, as their default's times its width over the default's. Never resized, as 0.6.0 had it to the pixel.
     */
    fun size(keptWidthDp: Float, screenWidthDp: Int, dockedKeysPx: Int, bounds: Bounds): Size {
        val w0 = widthDp(screenWidthDp)
        val w = if (keptWidthDp < 0f) w0 else keptWidthDp.roundToInt().coerceIn(bounds.minDp, bounds.maxDp)
        val resize = if (w == w0) 1f else w.toFloat() / w0.coerceAtLeast(1)
        val textScale = textScale(w0, screenWidthDp)
        return Size(w, (dockedKeysPx * share(w0, screenWidthDp) * resize).toInt(), textScale, resize)
    }

    /**
     * Where it is kept on a screen held one way (竖屏 and 横屏 each their own, 061-floating-resize §2.6): its own [x],
     * [y] once kept (negative: never), else [old], the one place 0.6.0 kept for both, for both.
     */
    fun keptSpot(x: Float, y: Float, old: Spot): Spot = if (x < 0f || y < 0f) old else Spot(x, y)

    /** A resize at its limits: [Min] as narrow as it goes, [Max] as wide. */
    enum class Limit { None, Min, Max }

    /**
     * Whether a resize grows it down, its top right staying, or up, its bottom right staying: towards the strip
     * ([handleBottom] or on top), the handle's corner moving; but touching the room's edge on that side (at the
     * bottom, where it first floats), the other way, or it could not grow at all (the user's choice, 2026-11-15).
     * Its [top] and [bottom], the room's [roomTop] and [roomBottom], px.
     */
    fun growsDown(handleBottom: Boolean, top: Float, bottom: Float, roomTop: Float, roomBottom: Float): Boolean =
        if (handleBottom) bottom < roomBottom - EDGE_PX else top <= roomTop + EDGE_PX

    /**
     * The user's resize by the handle at the keyboard's left end ([FloatingKeyboard] floating), a right corner staying
     * where it is: the top one as it grows [down], else the bottom one ([growsDown]). Begun at [startWidthDp]
     * ([widthPx] by [heightPx], all of it; its keys [keysHeightPx] of that, the rest the bars, which keep their
     * height), [density] px a dp, within [bounds] and the screen: [acrossPx] from the staying corner to the screen's
     * left, [alongPx] to the room's edge it grows towards (its bottom, or its top).
     */
    class Resize(
        private val startWidthDp: Int,
        private val density: Float,
        private val widthPx: Float,
        private val heightPx: Float,
        keysHeightPx: Float,
        private val down: Boolean,
        private val bounds: Bounds,
        acrossPx: Float,
        alongPx: Float,
    ) {
        private val barsPx = heightPx - keysHeightPx
        private val keysPxPerDp = keysHeightPx / startWidthDp.coerceAtLeast(1)

        // as wide as goes on the screen from the staying corner: never pushed along, it stops at an edge; never less
        // than it is (laid out past an edge some other way, a resize does not shrink it on its own)
        private val maxDp: Int = run {
            val across = (acrossPx / density).toInt()
            val along = if (keysPxPerDp <= 0f) Int.MAX_VALUE else ((alongPx - barsPx) / keysPxPerDp).toInt()
            maxOf(minOf(bounds.maxDp, across, along), minOf(startWidthDp, bounds.maxDp), bounds.minDp)
        }

        /**
         * Its width, dp, the handle moved by [dx], [dy] px since it went down: by the move's share of its diagonal
         * away from the staying corner (out grows it, in shrinks it), within its bounds and the screen.
         */
        fun widthDp(dx: Float, dy: Float): Int {
            val diagonal = hypot(widthPx, heightPx)
            if (diagonal <= 0f) return startWidthDp
            // the far corner from the staying one: to the left, and down or up
            val out = (-dx * widthPx + (if (down) dy else -dy) * heightPx) / diagonal
            return within(startWidthDp * (diagonal + out) / diagonal)
        }

        /** TalkBack's 放大 ([bigger]) or 缩小 from [nowDp]: a tenth of it, within its bounds and the screen. */
        fun step(nowDp: Int, bigger: Boolean): Int = within(nowDp * if (bigger) 1f + STEP else 1f - STEP)

        /** Whether [widthDp] is at a limit, and which. */
        fun limit(widthDp: Int): Limit = when {
            widthDp >= maxDp -> Limit.Max
            widthDp <= bounds.minDp -> Limit.Min
            else -> Limit.None
        }

        private fun within(dp: Float): Int = dp.roundToInt().coerceIn(bounds.minDp, maxDp)
    }

    /** TalkBack's 放大 and 缩小: a tenth each. */
    const val STEP = 0.1f

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

    // within a pixel of the room's edge: touching it (a place kept as a share comes back a hair off)
    private const val EDGE_PX = 1f

    /**
     * Its corners' radius, in dp, as round as the keys (按键圆角): the keys' own [keyRadius] and
     * the least of their margins to its edge ([hMargin] at the sides, [vMargin] at the bottom), so
     * its curve runs alongside the corner keys' (nested round them) and clips none of them. Square
     * keys ([keyRadius] 0, 直角), square corners: the user's one setting for corners. Keys not
     * [bordered] are drawn square whatever their radius: the margin alone, or a big radius would
     * clip a corner key's face.
     *
     * Theme 3.0's rule, which the app no longer works out (060-render-p3 T7a): the engine's default
     * of a theme's `frame` (WindowLooks.cornerDp) is, held to this rule at every radius and margin by
     * keyboard-scene's WindowDefaultsTest (a copy of it there: keyboard-scene sees nothing of ime-core).
     */
    fun cornerDp(keyRadius: Int, hMargin: Int, vMargin: Int, bordered: Boolean = true): Int =
        if (keyRadius == 0) 0 else (if (bordered) keyRadius else 0) + minOf(hMargin, vMargin)

    // where in a room [room] wide [at] is; no room, the far edge, which is where it is all the same
    private fun share(at: Float, room: Float) = if (room <= 0f) 1f else (at / room).coerceIn(0f, 1f)
}
