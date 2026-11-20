/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.floating

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

/** The floating keyboard: kept at a [Spot], a share of its room each way, so turning the screen keeps the corner. */
object FloatingKeyboard {

    const val SHARE = 0.7f
    const val MIN_DP = 300
    const val MAX_DP = 420

    const val MIN_HEIGHT_SHARE = 0.85f

    /** 0 the left (top) of the room it moves in, 1 the right (bottom). */
    data class Spot(val x: Float, val y: Float)

    val DEFAULT = Spot(0.5f, 1f)

    fun widthDp(screenWidthDp: Int): Int =
        (screenWidthDp * SHARE).toInt().coerceIn(MIN_DP, MAX_DP).coerceAtMost(screenWidthDp)

    /** The docked height narrowed as its width is, but not past [MIN_HEIGHT_SHARE]. */
    fun height(docked: Int, widthDp: Int, screenWidthDp: Int): Int = (docked * share(widthDp, screenWidthDp)).toInt()

    /** Shorter keys, smaller text: at the docked size a letter ran into the digit under it. */
    fun textScale(widthDp: Int, screenWidthDp: Int): Float = share(widthDp, screenWidthDp)

    private fun share(widthDp: Int, screenWidthDp: Int) =
        (widthDp.toFloat() / screenWidthDp.coerceAtLeast(1)).coerceIn(MIN_HEIGHT_SHARE, 1f)

    /** The strip's three buttons fit here; fixed, so the keyboard does not jump as the layout changes. */
    const val MIN_RESIZE_DP = 260

    /** Everything scales by [resize] from its default width so keys keep their shape; the bars only widen. */
    data class Size(val widthDp: Int, val keysHeightPx: Int, val textScale: Float, val resize: Float)

    data class Bounds(val minDp: Int, val maxDp: Int)

    /** Up to the docked width or what fits in [roomPx] height; never narrower nor wider than its default [widthDp]. */
    fun bounds(screenWidthDp: Int, dockedWidthDp: Int, dockedKeysPx: Int, barsPx: Int, roomPx: Int): Bounds {
        val w0 = widthDp(screenWidthDp)
        val keysPxPerDp = dockedKeysPx * share(w0, screenWidthDp) / w0.coerceAtLeast(1)
        val fits = if (keysPxPerDp <= 0f) Int.MAX_VALUE else ((roomPx - barsPx) / keysPxPerDp).toInt()
        return Bounds(minOf(MIN_RESIZE_DP, w0), maxOf(w0, minOf(dockedWidthDp, fits)))
    }

    /** [keptWidthDp] negative or NaN (a hand-edited backup): never resized, the default. */
    fun size(keptWidthDp: Float, screenWidthDp: Int, dockedKeysPx: Int, bounds: Bounds): Size {
        val w0 = widthDp(screenWidthDp)
        // NaN is not below 0 and roundToInt throws on it
        val w = if (!(keptWidthDp >= 0f)) w0 else keptWidthDp.roundToInt().coerceIn(bounds.minDp, bounds.maxDp)
        val resize = if (w == w0) 1f else w.toFloat() / w0.coerceAtLeast(1)
        val textScale = textScale(w0, screenWidthDp)
        return Size(w, (dockedKeysPx * share(w0, screenWidthDp) * resize).toInt(), textScale, resize)
    }

    /** Kept per orientation; negative or NaN: never, so [old]. */
    fun keptSpot(x: Float, y: Float, old: Spot): Spot = if (!(x >= 0f) || !(y >= 0f)) old else Spot(x, y)

    enum class Limit { None, Min, Max }

    /** Grows away from the strip, unless it touches the room's edge on that side, where it could not grow. */
    fun growsDown(handleBottom: Boolean, top: Float, bottom: Float, roomTop: Float, roomBottom: Float): Boolean =
        if (handleBottom) bottom < roomBottom - EDGE_PX else top <= roomTop + EDGE_PX

    /** A resize by the left-end handle, a right corner staying; [acrossPx]/[alongPx] the room to the screen edges. */
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

        // never pushed along, it stops at an edge; never less than it is
        private val maxDp: Int = run {
            val across = (acrossPx / density).toInt()
            val along = if (keysPxPerDp <= 0f) Int.MAX_VALUE else ((alongPx - barsPx) / keysPxPerDp).toInt()
            maxOf(minOf(bounds.maxDp, across, along), minOf(startWidthDp, bounds.maxDp), bounds.minDp)
        }

        /** By the move's share of its diagonal away from the staying corner. */
        fun widthDp(dx: Float, dy: Float): Int {
            val diagonal = hypot(widthPx, heightPx)
            if (diagonal <= 0f) return startWidthDp
            val out = (-dx * widthPx + (if (down) dy else -dy) * heightPx) / diagonal
            return within(startWidthDp * (diagonal + out) / diagonal)
        }

        /** TalkBack's 放大 or 缩小: a tenth of it. */
        fun step(nowDp: Int, bigger: Boolean): Int = within(nowDp * if (bigger) 1f + STEP else 1f - STEP)

        fun limit(widthDp: Int): Limit = when {
            widthDp >= maxDp -> Limit.Max
            widthDp <= bounds.minDp -> Limit.Min
            else -> Limit.None
        }

        private fun within(dp: Float): Int = dp.roundToInt().coerceIn(bounds.minDp, maxDp)
    }

    const val STEP = 0.1f

    fun place(spot: Spot, w: Float, h: Float, parentW: Float, parentH: Float): Pair<Float, Float> =
        spot.x.coerceIn(0f, 1f) * (parentW - w).coerceAtLeast(0f) to spot.y.coerceIn(0f, 1f) * (parentH - h).coerceAtLeast(0f)

    fun spot(left: Float, top: Float, w: Float, h: Float, parentW: Float, parentH: Float): Spot =
        Spot(share(left, parentW - w), share(top, parentH - h))

    /** Let go of by the docked grip: the drag goes on from [gripX]/[gripY] under the finger, kept within the room. */
    fun underFinger(fingerX: Float, fingerY: Float, gripX: Float, gripY: Float, w: Float, h: Float, parentW: Float, room: Float): Spot =
        spot(fingerX - gripX, fingerY - gripY, w, h, parentW, room)

    /** Docked to floating, along one axis: the docked size over its own. */
    fun startScale(fromSize: Float, toSize: Float): Float = if (toSize <= 0f) 1f else fromSize / toSize

    /** The pivot its [startScale] is about, so scaled it covers the docked keyboard exactly and a drag carries the shrink. */
    fun pivot(from: Float, fromSize: Float, to: Float, toSize: Float): Float {
        val scale = startScale(fromSize, toSize)
        return if (abs(1f - scale) < NO_SCALE) toSize / 2 else (from - to) / (1f - scale)
    }

    // closer to 1 than this the keyboard does not change size, and a pivot would be far off
    private const val NO_SCALE = 0.001f

    // within a pixel of the room's edge: touching it (a place kept as a share comes back a hair off)
    private const val EDGE_PX = 1f

    /**
     * As round as the keys, nested round the corner keys; unbordered keys are square, so the margin alone.
     * keyboard-scene's WindowDefaultsTest holds the engine's theme default to this rule (a copy, as it cannot see ime-core).
     */
    fun cornerDp(keyRadius: Int, hMargin: Int, vMargin: Int, bordered: Boolean = true): Int =
        if (keyRadius == 0) 0 else (if (bordered) keyRadius else 0) + minOf(hMargin, vMargin)

    private fun share(at: Float, room: Float) = if (room <= 0f) 1f else (at / room).coerceIn(0f, 1f)
}
