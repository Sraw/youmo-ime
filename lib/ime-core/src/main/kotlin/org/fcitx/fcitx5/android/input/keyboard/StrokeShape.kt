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
 * Which of 横竖撇捺折 a stroke drawn on the keyboard is (笔画筛选), as `Strokes.KEYS` has them, by
 * its shape alone, as 触宝's patent (CN101620480B) reads one: each lift a stroke, wherever the one
 * before ended.
 *
 * - One that bends (it strays from the line between its ends by more than [BENT] of it, or turns
 *   by more than [TURN] degrees where it strays most, both arms of it no shorter than [ARM] of the
 *   other) is 折.
 * - Else by the direction from its start to its end: within [FLAT] degrees of level either way
 *   横, and up to the right as far as [RISE] too (提, which the strokes count as 横); within [STEEP]
 *   of straight down 竖; down to the left 撇, down to the right 点 or 捺.
 * - Up, or up to the left: no stroke.
 */
object StrokeShape {

    /** How far from the line between its ends a bent stroke strays, as a share of that line. */
    const val BENT = 0.35f

    /** How many degrees a stroke turns where it strays most, at least, to be bent. */
    const val TURN = 60.0

    /** How long the shorter arm of a bend is, at least, as a share of the longer: a hook at a stroke's end is no bend. */
    const val ARM = 0.25f

    /** Degrees either side of level that are 横. */
    const val FLAT = 30.0

    /** Degrees up to the right that are 横 still (提). */
    const val RISE = 75.0

    /** Degrees either side of straight down that are 竖. */
    const val STEEP = 20.0

    // Strokes.KEYS: 横竖撇捺折
    private const val HENG = 'h'
    private const val SHU = 's'
    private const val PIE = 'p'
    private const val NA = 'n'
    private const val ZHE = 'z'

    private const val DOWN = -90.0
    private const val HALF_TURN = 180.0

    /**
     * The stroke [path] is, `h s p n z`, or null for none: [count] of its places, x and y in turn,
     * across and down in the same units (a key's width), y growing down as on the screen.
     */
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

    // whether the path bends: strays far from the line between its ends, or turns sharply where it strays most
    private fun bent(path: FloatArray, count: Int, x0: Float, y0: Float, x1: Float, y1: Float, length: Float): Boolean {
        var far = -1f
        var at = -1
        for (k in 0 until count step 2) {
            val d = abs((x1 - x0) * (y0 - path[k + 1]) - (x0 - path[k]) * (y1 - y0)) / length
            if (d > far) {
                far = d
                at = k
            }
        }
        if (at < 0 || far <= 0f) return false
        if (far > BENT * length) return true
        val ax = path[at] - x0
        val ay = path[at + 1] - y0
        val bx = x1 - path[at]
        val by = y1 - path[at + 1]
        val a = hypot(ax, ay)
        val b = hypot(bx, by)
        if (a <= 0f || b <= 0f || minOf(a, b) < ARM * maxOf(a, b)) return false
        val cos = ((ax * bx + ay * by) / (a * b)).coerceIn(-1f, 1f)
        return Math.toDegrees(acos(cos.toDouble())) > TURN
    }
}
