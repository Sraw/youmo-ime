/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene.theme

import org.fcitx.fcitx5.android.scene.Box
import org.fcitx.fcitx5.android.scene.Point
import org.fcitx.fcitx5.android.scene.Shape
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * `shape="path(…)"`: SVG's path data, coordinates 0–1 across and down the box, to six decimals so [ThemeWriter]'s text
 * is never longer than [MAX_LENGTH]. [flatten] makes straight edges, so neither the scene, the backends nor the hit test know curves.
 */
data class ShapePath(val segments: List<Segment>) {
    sealed interface Segment {
        data class Move(val x: Double, val y: Double) : Segment
        data class Line(val x: Double, val y: Double) : Segment
        data class Quad(val cx: Double, val cy: Double, val x: Double, val y: Double) : Segment
        data class Cubic(val c1x: Double, val c1y: Double, val c2x: Double, val c2y: Double, val x: Double, val y: Double) : Segment
        data object Close : Segment
    }

    /** Where so many curves would make more than [MAX_FLAT_POINTS], the whole path's tolerance coarser till they do not. */
    fun flatten(box: Box, tolerance: Double = TOLERANCE): Shape.Path {
        var t = tolerance
        var p = outline(box, t)
        var n = p.ops.count { it !is Shape.Path.Op.Close }
        while (n > MAX_FLAT_POINTS) {
            // a curve's chords go by the square root of the tolerance: the share too many, squared, at least fourfold
            t *= maxOf(COARSER, (n.toDouble() / MAX_FLAT_POINTS).let { it * it })
            p = outline(box, t)
            n = p.ops.count { it !is Shape.Path.Op.Close }
        }
        return p
    }

    private fun outline(box: Box, tolerance: Double): Shape.Path {
        fun x(v: Double) = box.left + v * box.width
        fun y(v: Double) = box.top + v * box.height
        val ops = ArrayList<Shape.Path.Op>()
        var px = 0.0
        var py = 0.0
        var startX = 0.0
        var startY = 0.0
        var open = false
        // a drawing segment after a Z goes on from the sub-path's start (SVG's)
        fun from() {
            if (!open) {
                ops += Shape.Path.Op.MoveTo(px, py)
                open = true
            }
        }
        for (s in segments) {
            when (s) {
                is Segment.Move -> {
                    px = x(s.x)
                    py = y(s.y)
                    startX = px
                    startY = py
                    ops += Shape.Path.Op.MoveTo(px, py)
                    open = true
                }
                is Segment.Line -> {
                    from()
                    px = x(s.x)
                    py = y(s.y)
                    ops += Shape.Path.Op.LineTo(px, py)
                }
                is Segment.Quad -> {
                    from()
                    val cx = x(s.cx)
                    val cy = y(s.cy)
                    val ex = x(s.x)
                    val ey = y(s.y)
                    val n = steps(2 * hypot(px - 2 * cx + ex, py - 2 * cy + ey), tolerance)
                    for (i in 1..n) {
                        val t = i.toDouble() / n
                        val u = 1 - t
                        ops += Shape.Path.Op.LineTo(u * u * px + 2 * u * t * cx + t * t * ex, u * u * py + 2 * u * t * cy + t * t * ey)
                    }
                    px = ex
                    py = ey
                }
                is Segment.Cubic -> {
                    from()
                    val c1x = x(s.c1x)
                    val c1y = y(s.c1y)
                    val c2x = x(s.c2x)
                    val c2y = y(s.c2y)
                    val ex = x(s.x)
                    val ey = y(s.y)
                    val bend = maxOf(hypot(px - 2 * c1x + c2x, py - 2 * c1y + c2y), hypot(c1x - 2 * c2x + ex, c1y - 2 * c2y + ey))
                    val n = steps(CUBIC_BEND * bend, tolerance)
                    for (i in 1..n) {
                        val t = i.toDouble() / n
                        val u = 1 - t
                        val a = u * u * u
                        val b = 3 * u * u * t
                        val c = 3 * u * t * t
                        val d = t * t * t
                        ops += Shape.Path.Op.LineTo(a * px + b * c1x + c * c2x + d * ex, a * py + b * c1y + c * c2y + d * ey)
                    }
                    px = ex
                    py = ey
                }
                Segment.Close -> {
                    if (open) ops += Shape.Path.Op.Close
                    open = false
                    px = startX
                    py = startY
                }
            }
        }
        return Shape.Path(ops)
    }

    companion object {
        const val TOLERANCE = 0.5

        const val MAX_POINTS = 256

        /** A theme comes from anywhere. */
        const val MAX_LENGTH = 8192

        /** A path of [MAX_POINTS] has no more than twice that, so the tolerance coarsened always gets there. */
        const val MAX_FLAT_POINTS = 1024
        private const val COARSER = 4.0

        // past the tolerance only on a curve some 20 000 px across
        private const val MAX_STEPS = 256

        // a cubic's second derivative is at most 6 times its control points' greatest second difference
        private const val CUBIC_BEND = 6.0
        private const val CHORD = 8.0

        // ±0 past the box's edges is in it: a relative path's sums are not exact
        private const val SLACK = 1e-9

        private fun steps(bend: Double, tolerance: Double): Int =
            ceil(sqrt(bend / (CHORD * tolerance))).toInt().coerceIn(1, MAX_STEPS)

        /** `M0 0 L1 1 Z` is refused; a line there and back off it encloses nothing yet passes, as a bowtie must. */
        fun parse(data: String): ShapePath? {
            if (data.length > MAX_LENGTH) return null
            return Reader(tokens(data) ?: return null).read()?.takeIf { it.encloses() }
        }

        // not the signed (shoelace) area, which is nought for a bowtie or a figure of eight that fill as much as any
        private fun ShapePath.encloses(): Boolean {
            val polygons = ArrayList<MutableList<Point>>()
            for (op in outline(UNIT, UNIT_TOLERANCE).ops) {
                when (op) {
                    is Shape.Path.Op.MoveTo -> polygons += mutableListOf(Point(op.x, op.y))
                    is Shape.Path.Op.LineTo -> polygons.last() += Point(op.x, op.y)
                    Shape.Path.Op.Close -> Unit
                }
            }
            return polygons.any { p ->
                val o = p[0]
                (1 until p.size - 1).sumOf { i -> abs((p[i].x - o.x) * (p[i + 1].y - o.y) - (p[i + 1].x - o.x) * (p[i].y - o.y)) } / 2 > SLACK
            }
        }

        private val UNIT = Box(0.0, 0.0, 1.0, 1.0)
        private const val UNIT_TOLERANCE = 1e-3

        private class Reader(private val tokens: List<Any>) {
            private val out = ArrayList<Segment>()
            private var i = 0
            private var x = 0.0
            private var y = 0.0
            private var startX = 0.0
            private var startY = 0.0
            private var points = 0

            fun read(): ShapePath? {
                var cmd: Char? = null
                while (i < tokens.size) {
                    val t = tokens[i]
                    if (t is Char) {
                        cmd = t
                        i++
                    } else if (cmd == null || cmd == 'Z' || cmd == 'z') {
                        return null // numbers with no command to take them; a Z takes none
                    }
                    val c = cmd ?: return null
                    if (out.isEmpty() && c != 'M' && c != 'm') return null
                    add(segment(c) ?: return null)
                    if (points > MAX_POINTS) return null
                    // more pairs after a move are lines (SVG's)
                    cmd = when (c) {
                        'M' -> 'L'
                        'm' -> 'l'
                        else -> c
                    }
                }
                return ShapePath(out)
            }

            private fun segment(c: Char): Segment? {
                val ox = if (c.isLowerCase()) x else 0.0
                val oy = if (c.isLowerCase()) y else 0.0
                return when (c.uppercaseChar()) {
                    'M' -> pair(ox, oy)?.let { (px, py) -> Segment.Move(px, py) }
                    'L' -> pair(ox, oy)?.let { (px, py) -> Segment.Line(px, py) }
                    'H' -> coord(ox)?.let { Segment.Line(it, y) }
                    'V' -> coord(oy)?.let { Segment.Line(x, it) }
                    'Q' -> {
                        val (cx, cy) = pair(ox, oy) ?: return null
                        pair(ox, oy)?.let { (px, py) -> Segment.Quad(cx, cy, px, py) }
                    }
                    'C' -> {
                        val (c1x, c1y) = pair(ox, oy) ?: return null
                        val (c2x, c2y) = pair(ox, oy) ?: return null
                        pair(ox, oy)?.let { (px, py) -> Segment.Cubic(c1x, c1y, c2x, c2y, px, py) }
                    }
                    'Z' -> Segment.Close
                    else -> null
                }
            }

            private fun add(s: Segment) {
                if (s == Segment.Close && out.last() == Segment.Close) return
                out += s
                when (s) {
                    is Segment.Move -> {
                        startX = s.x
                        startY = s.y
                        moveTo(s.x, s.y, 1)
                    }
                    is Segment.Line -> moveTo(s.x, s.y, 1)
                    is Segment.Quad -> moveTo(s.x, s.y, 2)
                    is Segment.Cubic -> moveTo(s.x, s.y, CUBIC_POINTS)
                    Segment.Close -> {
                        x = startX
                        y = startY
                    }
                }
            }

            private fun moveTo(px: Double, py: Double, written: Int) {
                x = px
                y = py
                points += written
            }

            private fun coord(o: Double): Double? {
                val v = tokens.getOrNull(i) as? Double ?: return null
                i++
                return (o + v).takeIf { it >= -SLACK && it <= 1 + SLACK }?.let { round(it.coerceIn(0.0, 1.0) * GRID) / GRID + 0.0 }
            }

            private fun pair(ox: Double, oy: Double): Pair<Double, Double>? {
                val a = coord(ox) ?: return null
                return coord(oy)?.let { a to it }
            }
        }

        private fun tokens(s: String): List<Any>? {
            val out = ArrayList<Any>()
            var i = 0
            while (i < s.length) {
                val ch = s[i]
                when {
                    ch in SPACES -> i++
                    ch in COMMANDS -> {
                        out += ch
                        i++
                    }
                    else -> {
                        val end = number(s, i)
                        if (end < 0) return null
                        out += s.substring(i, end).toDoubleOrNull() ?: return null
                        i = end
                    }
                }
            }
            return out
        }

        // a second point starts the next number, SVG's "0.5.5"
        internal fun number(s: String, start: Int): Int {
            var i = start
            if (s[i] == '-' || s[i] == '+') i++
            fun digits(): Int {
                val from = i
                while (i < s.length && s[i] in '0'..'9') i++
                return i - from
            }
            var n = digits()
            if (i < s.length && s[i] == '.') {
                i++
                n += digits()
            }
            if (n == 0) return -1
            // an exponent, as SVG tools write (5e-7); an 'e' with no digits after it is not one
            if (i < s.length && (s[i] == 'e' || s[i] == 'E')) {
                val mantissa = i
                i++
                if (i < s.length && (s[i] == '-' || s[i] == '+')) i++
                if (digits() == 0) i = mantissa
            }
            return i
        }

        private const val GRID = 1e6
        internal const val SPACES = " ,\t\n\r"
        private const val CUBIC_POINTS = 3
        private const val COMMANDS = "MmLlHhVvCcQqZz"

        fun roundedOutline(box: Box, radii: List<Double>, tail: Pair<Double, Double>? = null, tolerance: Double = TOLERANCE): Shape.Path {
            val ops = ArrayList<Shape.Path.Op>()
            val tl = radii[0]
            val tr = radii[1]
            val br = radii[2]
            val bl = radii[CORNER_BOTTOM_LEFT]
            ops += Shape.Path.Op.MoveTo(box.left + tl, box.top)
            ops += Shape.Path.Op.LineTo(box.right - tr, box.top)
            arc(ops, box.right - tr, box.top + tr, tr, -HALF_PI, tolerance)
            ops += Shape.Path.Op.LineTo(box.right, box.bottom - br)
            arc(ops, box.right - br, box.bottom - br, br, 0.0, tolerance)
            if (tail != null) {
                val (half, height) = tail
                val cx = box.centerX
                ops += Shape.Path.Op.LineTo(cx + half, box.bottom)
                ops += Shape.Path.Op.LineTo(cx, box.bottom + height)
                ops += Shape.Path.Op.LineTo(cx - half, box.bottom)
            }
            ops += Shape.Path.Op.LineTo(box.left + bl, box.bottom)
            arc(ops, box.left + bl, box.bottom - bl, bl, HALF_PI, tolerance)
            ops += Shape.Path.Op.LineTo(box.left, box.top + tl)
            arc(ops, box.left + tl, box.top + tl, tl, PI, tolerance)
            ops += Shape.Path.Op.Close
            return Shape.Path(ops)
        }

        private fun arc(ops: MutableList<Shape.Path.Op>, cx: Double, cy: Double, r: Double, from: Double, tolerance: Double) {
            if (r <= 0) return
            val step = if (r <= tolerance) HALF_PI else 2 * acos(1 - tolerance / r)
            val n = ceil(HALF_PI / step).toInt().coerceIn(1, MAX_STEPS)
            for (i in 1..n) {
                val a = from + HALF_PI * i / n
                ops += Shape.Path.Op.LineTo(cx + r * cos(a), cy + r * sin(a))
            }
        }

        private const val HALF_PI = PI / 2
        private const val CORNER_BOTTOM_LEFT = 3

        internal fun distance(px: Double, py: Double, ax: Double, ay: Double, bx: Double, by: Double): Double {
            val dx = bx - ax
            val dy = by - ay
            val len = dx * dx + dy * dy
            val t = if (len == 0.0) 0.0 else (((px - ax) * dx + (py - ay) * dy) / len).coerceIn(0.0, 1.0)
            return hypot(px - (ax + t * dx), py - (ay + t * dy))
        }
    }
}
