/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene.theme

import org.fcitx.fcitx5.android.scene.theme.ShapePath.Segment
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * A theme icon's `d=` (060-theme-sounds-icons §2.1) read once for both drawers: every SVG path command
 * made absolute M, L, Q, C and Z, so the app's `android.graphics.Path` and the web's `Path2D` are given the
 * same segments and neither parses SVG. H and V are lines, S and T take the reflected control point SVG's
 * way, an arc is up to four cubics (SVG 1.1 appendix F.6). Numbers are read by [ShapePath]'s reader.
 */
object IconPath {

    /** [segments] the path's; [numbers] how many numbers it was written with (an icon's are counted across its paths). */
    sealed interface Read {
        data class Ok(val segments: List<Segment>, val numbers: Int) : Read
        data class Bad(val message: String) : Read

        /** More than the budget of numbers: the caller says so once for the whole icon. */
        data object TooMany : Read
    }

    /** [d] read, at most [budget] numbers in it. */
    fun parse(d: String, budget: Int = SvgSubset.MAX_NUMBERS): Read = Reader(d, budget).read()

    /** [segments] as absolute path data, what `Path2D` takes. */
    fun text(segments: List<Segment>): String = buildString {
        for (s in segments) {
            if (isNotEmpty()) append(' ')
            when (s) {
                is Segment.Move -> append("M").append(n(s.x)).append(' ').append(n(s.y))
                is Segment.Line -> append("L").append(n(s.x)).append(' ').append(n(s.y))
                is Segment.Quad -> append("Q").append(n(s.cx)).append(' ').append(n(s.cy)).append(' ').append(n(s.x)).append(' ').append(n(s.y))
                is Segment.Cubic -> append("C").append(n(s.c1x)).append(' ').append(n(s.c1y)).append(' ').append(n(s.c2x)).append(' ')
                    .append(n(s.c2y)).append(' ').append(n(s.x)).append(' ').append(n(s.y))
                Segment.Close -> append("Z")
            }
        }
    }

    // a whole number without ".0"; for the web's Path2D only (jsMain): the JVM writes 1e7 and past, and below 1e-3,
    // as "1.0E7", which is no path data, where JS writes "10000000" (and 1e-7 as "1e-7", which is)
    private fun n(v: Double): String = if (v == kotlin.math.floor(v) && abs(v) < WHOLE) v.toLong().toString() else v.toString()

    /** Whether [segments] draw anything: some segment past a move (a lone M, or Ms only, fills nothing). */
    fun draws(segments: List<Segment>): Boolean = segments.any { it !is Segment.Move && it != Segment.Close }

    private const val COMMANDS = "MmLlHhVvCcSsQqTtAaZz"
    private const val WHOLE = 1e15

    // the arity of each command's argument group
    private fun arity(c: Char): Int = when (c.uppercaseChar()) {
        'M', 'L', 'T' -> 2
        'H', 'V' -> 1
        'Q', 'S' -> 4
        'C' -> 6
        'A' -> 7
        else -> 0
    }

    private class Reader(private val d: String, private val budget: Int) {
        private val out = ArrayList<Segment>()
        private var i = 0
        private var numbers = 0
        private var x = 0.0
        private var y = 0.0
        private var sx = 0.0
        private var sy = 0.0

        // the last segment's second control point when it was a cubic (S reflects it) or its control when a quad (T does)
        private var lastCubic: Pair<Double, Double>? = null
        private var lastQuad: Pair<Double, Double>? = null

        // why the last number or flag was not read (null: none was there, too few numbers)
        private var error: Read? = null

        // the command that takes the numbers that follow
        private var cmd: Char? = null

        fun read(): Read {
            skip()
            if (i >= d.length) return Read.Bad("empty")
            while (true) {
                skip()
                if (i >= d.length) return Read.Ok(out, numbers)
                step()?.let { return it }
            }
        }

        // the next command or argument group read and drawn, [cmd] the one taking the numbers after it; else why not
        private fun step(): Read? {
            val ch = d[i]
            var c = cmd
            if (ch in COMMANDS) {
                if (c == null && ch != 'M' && ch != 'm') return Read.Bad("a path starts with M")
                i++
                if (ch == 'Z' || ch == 'z') {
                    close()
                    cmd = ch
                    return null
                }
                c = ch
            } else if (c == null) {
                return Read.Bad(if (ShapePath.number(d, i) < 0) noCommand() else "a path starts with M")
            } else if (c == 'Z' || c == 'z') {
                return Read.Bad("${shown()} at ${i + 1}: Z takes no numbers")
            }
            segment(c, args(c) ?: return error ?: Read.Bad(incomplete(c)))
            // more pairs after a move are lines (SVG's)
            cmd = when (c) {
                'M' -> 'L'
                'm' -> 'l'
                else -> c
            }
            return null
        }

        // [cmd]'s argument group, or null with [error] saying why (none: too few numbers)
        private fun args(cmd: Char): DoubleArray? {
            val args = DoubleArray(arity(cmd))
            for (k in args.indices) {
                skip()
                args[k] = (if (cmd.uppercaseChar() == 'A' && (k == FLAG_LARGE || k == FLAG_SWEEP)) flag() else number()) ?: return null
            }
            return args
        }

        private fun skip() {
            while (i < d.length && d[i] in ShapePath.SPACES) i++
        }

        private fun shown(): String = "'${d[i]}'"

        private fun noCommand(): String = "${shown()} at ${i + 1} is no command or number"

        private fun incomplete(c: Char): String =
            if (i < d.length && d[i] !in COMMANDS) noCommand() else "$c needs ${arity(c)} numbers at ${i + 1}"

        private fun number(): Double? {
            if (i >= d.length || d[i] in COMMANDS) return null
            val end = ShapePath.number(d, i)
            if (end < 0) {
                error = Read.Bad(noCommand())
                return null
            }
            val text = d.substring(i, end)
            val v = text.toDoubleOrNull()
            if (v == null || abs(v) > SvgSubset.MAX_NUMBER) {
                error = Read.Bad("$text at ${i + 1} is past a number's range")
                return null
            }
            if (++numbers > budget) {
                error = Read.TooMany
                return null
            }
            i = end
            return v
        }

        // an arc's flag: one character, 0 or 1, which needs nothing after it ("a1 1 0 011 1")
        private fun flag(): Double? {
            if (i >= d.length || (d[i] != '0' && d[i] != '1')) {
                error = if (i < d.length && d[i] !in COMMANDS) Read.Bad("${shown()} at ${i + 1} is no arc flag (0 or 1)") else null
                return null
            }
            if (++numbers > budget) {
                error = Read.TooMany
                return null
            }
            return (d[i++] - '0').toDouble()
        }

        private fun close() {
            if (out.isNotEmpty() && out.last() != Segment.Close) out += Segment.Close
            x = sx
            y = sy
            lastCubic = null
            lastQuad = null
        }

        @Suppress("CyclomaticComplexMethod")
        private fun segment(c: Char, a: DoubleArray) {
            val rel = c.isLowerCase()
            val ox = if (rel) x else 0.0
            val oy = if (rel) y else 0.0
            var cubic: Pair<Double, Double>? = null
            var quad: Pair<Double, Double>? = null
            when (c.uppercaseChar()) {
                'M' -> {
                    x = ox + a[0]
                    y = oy + a[1]
                    sx = x
                    sy = y
                    out += Segment.Move(x, y)
                }
                'L' -> line(ox + a[0], oy + a[1])
                'H' -> line(ox + a[0], y)
                'V' -> line(x, oy + a[0])
                'C' -> {
                    out += Segment.Cubic(ox + a[0], oy + a[1], ox + a[2], oy + a[3], ox + a[4], oy + a[5])
                    cubic = ox + a[2] to oy + a[3]
                    x = ox + a[4]
                    y = oy + a[5]
                }
                'S' -> {
                    val (c1x, c1y) = lastCubic?.let { (px, py) -> 2 * x - px to 2 * y - py } ?: (x to y)
                    out += Segment.Cubic(c1x, c1y, ox + a[0], oy + a[1], ox + a[2], oy + a[3])
                    cubic = ox + a[0] to oy + a[1]
                    x = ox + a[2]
                    y = oy + a[3]
                }
                'Q' -> {
                    out += Segment.Quad(ox + a[0], oy + a[1], ox + a[2], oy + a[3])
                    quad = ox + a[0] to oy + a[1]
                    x = ox + a[2]
                    y = oy + a[3]
                }
                'T' -> {
                    val (cx, cy) = lastQuad?.let { (px, py) -> 2 * x - px to 2 * y - py } ?: (x to y)
                    out += Segment.Quad(cx, cy, ox + a[0], oy + a[1])
                    quad = cx to cy
                    x = ox + a[0]
                    y = oy + a[1]
                }
                'A' -> arc(a[0], a[1], a[2], a[FLAG_LARGE] != 0.0, a[FLAG_SWEEP] != 0.0, ox + a[ARC_X], oy + a[ARC_Y])
            }
            lastCubic = cubic
            lastQuad = quad
        }

        private fun line(px: Double, py: Double) {
            out += Segment.Line(px, py)
            x = px
            y = py
        }

        // SVG 1.1 F.6.5 (endpoint to centre) and F.6.6 (radii too small scaled up), then a cubic per quarter turn at most
        private fun arc(rxIn: Double, ryIn: Double, degrees: Double, large: Boolean, sweep: Boolean, ex: Double, ey: Double) {
            if (ex == x && ey == y) return // F.6.2: an arc to its own start is drawn as nothing
            var rx = abs(rxIn)
            var ry = abs(ryIn)
            if (rx == 0.0 || ry == 0.0) return line(ex, ey)
            val phi = degrees * PI / HALF_TURN
            val cosP = cos(phi)
            val sinP = sin(phi)
            val dx = (x - ex) / 2
            val dy = (y - ey) / 2
            val x1 = cosP * dx + sinP * dy
            val y1 = -sinP * dx + cosP * dy
            val lambda = x1 * x1 / (rx * rx) + y1 * y1 / (ry * ry)
            if (lambda > 1) {
                rx *= sqrt(lambda)
                ry *= sqrt(lambda)
            }
            val num = rx * rx * ry * ry - rx * rx * y1 * y1 - ry * ry * x1 * x1
            val den = rx * rx * y1 * y1 + ry * ry * x1 * x1
            var k = sqrt(maxOf(0.0, num / den))
            if (large == sweep) k = -k
            val cx1 = k * rx * y1 / ry
            val cy1 = -k * ry * x1 / rx
            val cx = cosP * cx1 - sinP * cy1 + (x + ex) / 2
            val cy = sinP * cx1 + cosP * cy1 + (y + ey) / 2
            val theta = atan2((y1 - cy1) / ry, (x1 - cx1) / rx)
            var delta = atan2((-y1 - cy1) / ry, (-x1 - cx1) / rx) - theta
            if (sweep && delta < 0) delta += 2 * PI
            if (!sweep && delta > 0) delta -= 2 * PI
            val n = ceil(abs(delta) / (PI / 2) - EPSILON).toInt().coerceIn(1, MAX_ARC_CUBICS)
            val step = delta / n
            // a cubic's control arms for a unit circle's arc of [step]
            val t = 4.0 / 3 * tan(step / 4)
            val from = out.size
            var a0 = theta
            for (j in 0 until n) {
                val a1 = a0 + step
                val (p0x, p0y) = point(cx, cy, rx, ry, cosP, sinP, cos(a0), sin(a0))
                val last = j == n - 1
                val (p3x, p3y) = if (last) ex to ey else point(cx, cy, rx, ry, cosP, sinP, cos(a1), sin(a1))
                // derivatives of the ellipse at a0 and a1, times t
                val (d0x, d0y) = turn(rx * -sin(a0), ry * cos(a0), cosP, sinP)
                val (d1x, d1y) = turn(rx * -sin(a1), ry * cos(a1), cosP, sinP)
                out += Segment.Cubic(p0x + t * d0x, p0y + t * d0y, p3x - t * d1x, p3y - t * d1y, p3x, p3y)
                a0 = a1
            }
            // radii or a chord so small their squares are 0 (1e-200): no centre is found, the cubics are NaN, which
            // Skia draws nothing of and Path2D stops reading at; such an arc is as good as a line (F.6.2's spirit)
            if (!out.subList(from, out.size).all { it is Segment.Cubic && finite(it) }) {
                while (out.size > from) out.removeAt(out.size - 1)
                return line(ex, ey)
            }
            x = ex
            y = ey
        }

        private fun finite(s: Segment.Cubic): Boolean =
            s.c1x.isFinite() && s.c1y.isFinite() && s.c2x.isFinite() && s.c2y.isFinite() && s.x.isFinite() && s.y.isFinite()

        private fun point(cx: Double, cy: Double, rx: Double, ry: Double, cosP: Double, sinP: Double, ca: Double, sa: Double): Pair<Double, Double> {
            val (u, v) = turn(rx * ca, ry * sa, cosP, sinP)
            return cx + u to cy + v
        }

        private fun turn(u: Double, v: Double, cosP: Double, sinP: Double): Pair<Double, Double> = cosP * u - sinP * v to sinP * u + cosP * v
    }

    /** The cubics one arc becomes at most: a quarter turn each. */
    const val MAX_ARC_CUBICS = 4

    private const val FLAG_LARGE = 3
    private const val FLAG_SWEEP = 4
    private const val ARC_X = 5
    private const val ARC_Y = 6
    private const val HALF_TURN = 180.0
    private const val EPSILON = 1e-9
}
