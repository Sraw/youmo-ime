/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * Which of 横竖撇捺折 a stroke drawn on the keyboard is (笔画筛选), by its shape alone (触宝's CN101620480B).
 * One that bends (strays past [BENT], or turns past [TURN] where it strays most, both arms ≥ [ARM] and each
 * [STRAIGHT] or bending) is 折; a curve turns all along, so a bowed 撇 with a tick is not. Else by direction:
 * within [FLAT] of level (or up-right to [RISE], 提) 横, within [STEEP] of down 竖, down-left 撇, down-right 捺; up: none.
 */
object StrokeShape {

    /** As a share of the line between its ends. */
    const val BENT = 0.35f

    const val TURN = 60.0

    /** As a share of the longer: a hook at a stroke's end is no bend. */
    const val ARM = 0.25f

    /** As a share of the turn's longer arm, not the arm's own: a short arm takes half a round corner, as much as the long one. */
    const val STRAIGHT = 0.15f

    // 竖折折钩's three corners
    private const val DEPTH = 2

    const val FLAT = 30.0

    /** 提. */
    const val RISE = 75.0

    const val STEEP = 20.0

    // Strokes.KEYS: 横竖撇捺折
    private const val HENG = 'h'
    private const val SHU = 's'
    private const val PIE = 'p'
    private const val NA = 'n'
    private const val ZHE = 'z'

    private const val DOWN = -90.0
    private const val HALF_TURN = 180.0

    /** `h s p n z` or null; [count] places, x and y in turn, in key widths, y down. */
    fun classify(path: FloatArray, count: Int = path.size): Char? {
        require(count % 2 == 0 && count <= path.size) { "count $count: x and y pairs of path's ${path.size}" }
        if (count < 4) return null
        val x0 = path[0]
        val y0 = path[1]
        val x1 = path[count - 2]
        val y1 = path[count - 1]
        val dx = x1 - x0
        val dy = y1 - y0
        val length = hypot(dx, dy)
        // back where it began: no direction to read
        if (length <= 0f) return null
        if (bent(path, count, x0, y0, x1, y1, length)) return ZHE
        // degrees from level to the right, up positive
        val angle = Math.toDegrees(atan2(-dy.toDouble(), dx.toDouble()))
        return when {
            abs(angle) <= FLAT || abs(angle) >= HALF_TURN - FLAT -> HENG
            angle > 0 && angle <= RISE -> HENG
            angle > 0 -> null
            abs(angle - DOWN) <= STEEP -> SHU
            angle < DOWN -> PIE
            else -> NA
        }
    }

    private fun bent(path: FloatArray, count: Int, x0: Float, y0: Float, x1: Float, y1: Float, length: Float): Boolean =
        bent(path, 0, count / 2 - 1, x0, y0, x1, y1, length, DEPTH)

    // indexes of places, not of floats
    @Suppress("LongParameterList")
    private fun bent(path: FloatArray, from: Int, to: Int, x0: Float, y0: Float, x1: Float, y1: Float, length: Float, depth: Int): Boolean {
        val at = farthest(path, from, to, x0, y0, x1, y1, length)
        val far = if (at < 0) 0f else strays(path, at, x0, y0, x1, y1, length)
        if (far <= 0f) return false
        if (far > BENT * length) return true
        val ax = path[2 * at] - x0
        val ay = path[2 * at + 1] - y0
        val bx = x1 - path[2 * at]
        val by = y1 - path[2 * at + 1]
        val a = hypot(ax, ay)
        val b = hypot(bx, by)
        if (a <= 0f || b <= 0f || minOf(a, b) < ARM * maxOf(a, b)) return false
        val cos = ((ax * bx + ay * by) / (a * b)).coerceIn(-1f, 1f)
        if (Math.toDegrees(acos(cos.toDouble())) <= TURN) return false
        // each arm against the longer: a short arm carries half a round corner, which strays far for its own length
        val ref = maxOf(a, b)
        return arm(path, from, at, depth, ref) && arm(path, at, to, depth, ref)
    }

    // near straight as [ref] measures it, or turning at a corner of its own
    private fun arm(path: FloatArray, from: Int, to: Int, depth: Int, ref: Float): Boolean {
        val x0 = path[2 * from]
        val y0 = path[2 * from + 1]
        val x1 = path[2 * to]
        val y1 = path[2 * to + 1]
        val length = hypot(x1 - x0, y1 - y0)
        if (length <= 0f || to - from < 2) return true
        val at = farthest(path, from, to, x0, y0, x1, y1, length)
        if (at < 0 || strays(path, at, x0, y0, x1, y1, length) <= STRAIGHT * ref) return true
        return depth > 0 && bent(path, from, to, x0, y0, x1, y1, length, depth - 1)
    }

    // -1 for none
    @Suppress("LongParameterList")
    private fun farthest(path: FloatArray, from: Int, to: Int, x0: Float, y0: Float, x1: Float, y1: Float, length: Float): Int {
        var far = -1f
        var at = -1
        for (k in from..to) {
            val d = strays(path, k, x0, y0, x1, y1, length)
            if (d > far) {
                far = d
                at = k
            }
        }
        return at
    }

    @Suppress("LongParameterList")
    private fun strays(path: FloatArray, k: Int, x0: Float, y0: Float, x1: Float, y1: Float, length: Float): Float =
        abs((x1 - x0) * (y0 - path[2 * k + 1]) - (x0 - path[2 * k]) * (y1 - y0)) / length
}
