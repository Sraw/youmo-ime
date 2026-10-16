/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.handwriting

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * What the handwriting recognizers are shown, computed from the pen's strokes as the training code
 * computed it (`portable.py` in the recognizers' training sources, held to the training pipeline
 * there): the single character as a raster thinned to its skeleton with 8 gradient-direction maps
 * ([charImage]) and as a path ([charPath]).
 *
 * A stroke is a [FloatArray] of x, y pairs in screen pixels, y downward. Every function takes the
 * strokes in writing order and needs at least one point (else IllegalArgumentException).
 */
object InkFeatures {

    const val RASTER = 128
    const val SIZE = 64
    const val DIRS = 8
    const val CHAR_PATH_DIM = 7

    private const val PAD = 8
    private const val RADIUS = 2.5
    private const val SEQ_STEP = 0.04

    /** The single character's image input: (1 + [DIRS]) planes of [SIZE] x [SIZE], plane-major. */
    fun charImage(strokes: List<FloatArray>): FloatArray = planes(rasterize(strokes))

    private fun check(strokes: List<FloatArray>) {
        require(strokes.all { it.size % 2 == 0 }) { "a stroke is x, y pairs" }
        require(strokes.any { it.isNotEmpty() }) { "no ink" }
    }

    /**
     * The pen's strokes drawn into a [RASTER] square, aspect kept: every pixel within [RADIUS] of a
     * segment, or of a lone point. Row-major.
     */
    fun rasterize(strokes: List<FloatArray>, size: Int = RASTER): BooleanArray {
        check(strokes)
        var loX = Double.MAX_VALUE
        var loY = Double.MAX_VALUE
        var hiX = -Double.MAX_VALUE
        var hiY = -Double.MAX_VALUE
        for (s in strokes) for (i in s.indices step 2) {
            loX = min(loX, s[i].toDouble()); hiX = max(hiX, s[i].toDouble())
            loY = min(loY, s[i + 1].toDouble()); hiY = max(hiY, s[i + 1].toDouble())
        }
        val span = max(max(hiX - loX, hiY - loY), 1e-3)
        val k = (size - 2 * PAD - 1) / span
        val offX = (size - (hiX - loX) * k) / 2
        val offY = (size - (hiY - loY) * k) / 2
        val img = BooleanArray(size * size)
        for (s in strokes) {
            val n = s.size / 2
            val qx = DoubleArray(n) { (s[2 * it] - loX) * k + offX }
            val qy = DoubleArray(n) { (s[2 * it + 1] - loY) * k + offY }
            if (n == 1) {
                segment(img, size, qx[0], qy[0], qx[0], qy[0])
            } else {
                for (i in 0 until n - 1) segment(img, size, qx[i], qy[i], qx[i + 1], qy[i + 1])
            }
        }
        return img
    }

    private fun segment(img: BooleanArray, size: Int, ax: Double, ay: Double, bx: Double, by: Double) {
        val x0 = max(0.0, floor(min(ax, bx) - RADIUS)).toInt()
        val x1 = min(size - 1.0, ceil(max(ax, bx) + RADIUS)).toInt()
        val y0 = max(0.0, floor(min(ay, by) - RADIUS)).toInt()
        val y1 = min(size - 1.0, ceil(max(ay, by) + RADIUS)).toInt()
        val dx = bx - ax
        val dy = by - ay
        val n = dx * dx + dy * dy
        for (y in y0..y1) for (x in x0..x1) {
            val t = if (n > 0) (((x - ax) * dx + (y - ay) * dy) / n).coerceIn(0.0, 1.0) else 0.0
            val ex = x - ax - t * dx
            val ey = y - ay - t * dy
            if (ex * ex + ey * ey <= RADIUS * RADIUS) img[y * size + x] = true
        }
    }

    // scikit-image's Zhang–Suen table (BSD-3): a pixel's 8 neighbours, bit 0 the upper left, then
    // clockwise, to 1 (removed in the first pass), 2 (in the second), 3 (in either), 0 (kept)
    private val THIN = intArrayOf(
        0, 0, 0, 1, 0, 0, 1, 3, 0, 0, 3, 1, 1, 0, 1, 3, 0, 0, 0, 0, 0, 0, 0, 0, 2, 0, 2, 0, 3, 0, 3, 3,
        0, 0, 0, 0, 0, 0, 0, 0, 3, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 2, 0, 0, 0, 3, 0, 2, 2,
        0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
        2, 0, 0, 0, 0, 0, 0, 0, 2, 0, 0, 0, 2, 0, 0, 0, 3, 0, 0, 0, 0, 0, 0, 0, 3, 0, 0, 0, 3, 0, 2, 0,
        0, 0, 3, 1, 0, 0, 1, 3, 0, 0, 0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1,
        3, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 2, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
        2, 3, 1, 3, 0, 0, 1, 3, 0, 0, 0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
        2, 3, 0, 1, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 3, 3, 0, 1, 0, 0, 0, 0, 2, 2, 0, 0, 2, 0, 0, 0,
    )

    /** A row-major boolean image thinned to one-pixel lines (scikit-image's `skeletonize`). */
    fun thin(img: BooleanArray, w: Int, h: Int): BooleanArray {
        val pw = w + 2
        val a = ByteArray(pw * (h + 2))
        for (y in 0 until h) for (x in 0 until w) if (img[y * w + x]) a[(y + 1) * pw + x + 1] = 1
        val kill = BooleanArray(a.size)
        var removed = true
        while (removed) {
            removed = false
            for (pass in 1..2) if (thinPass(a, kill, w, h, pass)) removed = true
        }
        return BooleanArray(w * h) { a[(it / w + 1) * pw + it % w + 1].toInt() != 0 }
    }

    /** One of the two passes over [a] (bordered by a blank pixel): whether it removed any. */
    private fun thinPass(a: ByteArray, kill: BooleanArray, w: Int, h: Int, pass: Int): Boolean {
        val pw = w + 2
        var any = false
        for (y in 1..h) for (x in 1..w) {
            val i = y * pw + x
            val code = if (a[i].toInt() == 0) 0 else a[i - pw - 1] + 2 * a[i - pw] + 4 * a[i - pw + 1] + 8 * a[i + 1] +
                16 * a[i + pw + 1] + 32 * a[i + pw] + 64 * a[i + pw - 1] + 128 * a[i - 1]
            val kind = if (a[i].toInt() == 0) 0 else THIN[code]
            kill[i] = kind == 3 || kind == pass
            any = any || kill[i]
        }
        if (any) for (i in a.indices) if (kill[i]) a[i] = 0
        return any
    }

    /**
     * Ink and [DIRS] gradient-direction maps of a [RASTER] square thinned to its skeleton, each
     * halved to [SIZE]: the skeleton widened (3 x 3) and blurred (OpenCV's 5 x 5 Gaussian, sigma 1),
     * its Sobel gradient's magnitude shared between the two nearest of [DIRS] directions; the ink
     * plane and the direction planes each scaled to a maximum of 1.
     */
    fun planes(ink: BooleanArray, size: Int = RASTER, out: Int = SIZE): FloatArray {
        val dil = widen(thin(ink, size, size), size)
        val g = gaussian5()
        val blur = correlate(dil, size, size, g, g)
        val gx = correlate(blur, size, size, floatArrayOf(-1f, 0f, 1f), floatArrayOf(1f, 2f, 1f))
        val gy = correlate(blur, size, size, floatArrayOf(1f, 2f, 1f), floatArrayOf(-1f, 0f, 1f))
        val full = Array(1 + DIRS) { FloatArray(size * size) }
        System.arraycopy(blur, 0, full[0], 0, blur.size)
        val turn = (2 * PI).toFloat()
        for (i in 0 until size * size) {
            val x = gx[i]
            val y = gy[i]
            // no gradient adds nothing: most of the square is blank
            if (x == 0f && y == 0f) continue
            val mag = sqrt(x * x + y * y)
            var angle = atan2(y, x) % turn
            if (angle < 0) angle += turn
            val a = angle / turn * DIRS
            val lo = a.toInt() % DIRS
            val w = a - floor(a)
            full[1 + lo][i] += mag * (1 - w)
            full[1 + (lo + 1) % DIRS][i] += mag * w
        }
        val res = FloatArray((1 + DIRS) * out * out)
        for (p in 0..DIRS) shrink(full[p], size, out, res, p * out * out)
        val plane = out * out
        val inkMax = max(1e-6f, peak(res, 0, plane))
        for (i in 0 until plane) res[i] /= inkMax
        val dirMax = max(1e-6f, peak(res, plane, res.size))
        for (i in plane until res.size) res[i] /= dirMax
        return res
    }

    // a loop, not maxOf over a range: that boxes each index
    private fun peak(v: FloatArray, from: Int, to: Int): Float {
        var m = v[from]
        for (i in from + 1 until to) m = max(m, v[i])
        return m
    }

    /** Each pixel on where any of its 3 x 3 neighbourhood is (OpenCV's `dilate`, the border blank). */
    private fun widen(sk: BooleanArray, size: Int): FloatArray {
        val out = FloatArray(size * size)
        // each skeleton pixel lights its neighbourhood: the same, without a look around every blank one
        for (i in sk.indices) {
            if (!sk[i]) continue
            val x = i % size
            val y = i / size
            for (yy in max(0, y - 1)..min(size - 1, y + 1)) for (xx in max(0, x - 1)..min(size - 1, x + 1)) out[yy * size + xx] = 1f
        }
        return out
    }

    /** [plane] ([size] square) averaged down to [out] square, written into [res] from [at]. */
    private fun shrink(plane: FloatArray, size: Int, out: Int, res: FloatArray, at: Int) {
        val f = size / out
        for (y in 0 until out) for (x in 0 until out) {
            var s = 0f
            for (dy in 0 until f) for (dx in 0 until f) s += plane[(y * f + dy) * size + x * f + dx]
            res[at + y * out + x] = s / (f * f)
        }
    }

    private fun gaussian5(): FloatArray {
        val k = DoubleArray(5) { exp(-((it - 2) * (it - 2)) / 2.0) }
        val sum = k.sum()
        return FloatArray(5) { (k[it] / sum).toFloat() }
    }

    /** Separable correlation with OpenCV's default border: reflected, the edge pixel not repeated. */
    private fun correlate(a: FloatArray, w: Int, h: Int, kx: FloatArray, ky: FloatArray): FloatArray {
        val r = kx.size / 2
        fun reflect(i: Int, n: Int): Int = when {
            i < 0 -> -i
            i >= n -> 2 * n - 2 - i
            else -> i
        }
        val tmp = FloatArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            var s = 0f
            for (i in kx.indices) s += kx[i] * a[y * w + reflect(x + i - r, w)]
            tmp[y * w + x] = s
        }
        val res = FloatArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            var s = 0f
            for (i in ky.indices) s += ky[i] * tmp[reflect(y + i - r, h) * w + x]
            res[y * w + x] = s
        }
        return res
    }

    /**
     * The single character's path: points every [SEQ_STEP] of its size along each stroke, each x,
     * y in the character's box about its middle, the move from the point before, that move's
     * direction, and 1 where the pen came down. [CHAR_PATH_DIM] values a point.
     */
    fun charPath(strokes: List<FloatArray>): FloatArray {
        check(strokes)
        var loX = Float.MAX_VALUE
        var loY = Float.MAX_VALUE
        var hiX = -Float.MAX_VALUE
        var hiY = -Float.MAX_VALUE
        for (s in strokes) for (i in s.indices step 2) {
            loX = min(loX, s[i]); hiX = max(hiX, s[i])
            loY = min(loY, s[i + 1]); hiY = max(hiY, s[i + 1])
        }
        val span = max(max(hiX - loX, hiY - loY), 1e-3f)
        val cx = (loX + hiX) / 2
        val cy = (loY + hiY) / 2
        val out = ArrayList<Float>()
        walk(strokes, cx, cy, span, SEQ_STEP) { x, y, dx, dy, first ->
            val n = (hypot(dx, dy).toDouble() + 1e-6).toFloat()
            out.add(x); out.add(y); out.add(dx); out.add(dy)
            out.add(dx / n); out.add(dy / n); out.add(if (first) 1f else 0f)
        }
        return out.toFloatArray()
    }

    private inline fun walk(
        strokes: List<FloatArray>, cx: Float, cy: Float, scale: Float, step: Double,
        point: (x: Float, y: Float, dx: Float, dy: Float, first: Boolean) -> Unit,
    ) {
        var px = 0f
        var py = 0f
        var started = false
        for (s in strokes) {
            val moved = FloatArray(s.size) { if (it % 2 == 0) (s[it] - cx) / scale else (s[it] - cy) / scale }
            val q = resample(moved, step)
            for (i in q.indices step 2) {
                val dx = if (started) q[i] - px else 0f
                val dy = if (started) q[i + 1] - py else 0f
                point(q[i], q[i + 1], dx, dy, i == 0)
                px = q[i]; py = q[i + 1]
                started = true
            }
        }
    }

    /** Points every [step] along a polyline of x, y pairs, its last point too (numpy's `interp`). */
    fun resample(pts: FloatArray, step: Double): FloatArray {
        val n = pts.size / 2
        if (n < 2) return pts.copyOf()
        val dist = DoubleArray(n)
        var acc = 0f
        for (i in 1 until n) {
            val dx = pts[2 * i] - pts[2 * i - 2]
            val dy = pts[2 * i + 1] - pts[2 * i - 1]
            acc += sqrt(dx * dx + dy * dy)
            dist[i] = acc.toDouble()
        }
        val total = dist[n - 1]
        if (total < 1e-6) return floatArrayOf(pts[0], pts[1])
        val count = ceil(total / step).toInt()
        val out = FloatArray(2 * (count + 1))
        var j = 0
        for (k in 0..count) {
            val t = if (k < count) k * step else total
            while (j < n - 2 && dist[j + 1] <= t) j++
            out[2 * k] = interp(t, dist, pts, j, 0)
            out[2 * k + 1] = interp(t, dist, pts, j, 1)
        }
        return out
    }

    private fun interp(t: Double, dist: DoubleArray, pts: FloatArray, j: Int, axis: Int): Float {
        val last = dist.size - 1
        if (t >= dist[last]) return pts[2 * last + axis]
        val f0 = pts[2 * j + axis].toDouble()
        val f1 = pts[2 * j + 2 + axis].toDouble()
        val slope = (f1 - f0) / (dist[j + 1] - dist[j])
        var v = slope * (t - dist[j]) + f0
        if (v.isNaN()) {
            v = slope * (t - dist[j + 1]) + f1
            if (v.isNaN() && f0 == f1) v = f0
        }
        return v.toFloat()
    }
}
