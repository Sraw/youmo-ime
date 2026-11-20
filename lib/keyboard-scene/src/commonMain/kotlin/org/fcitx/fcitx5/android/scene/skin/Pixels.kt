/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene.skin

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** An image as ARGB ints, not premultiplied, row by row; the platform decodes and encodes, nothing here reads a file format. */
class Pixels(val width: Int, val height: Int, val argb: IntArray = IntArray(width * height)) {
    init {
        require(width > 0 && height > 0 && argb.size == width * height) { "$width×$height: ${argb.size} pixels" }
    }

    operator fun get(x: Int, y: Int): Int = argb[y * width + x]

    fun crop(x: Int, y: Int, w: Int, h: Int): Pixels? {
        val l = max(0, x)
        val t = max(0, y)
        val r = min(width, x.toLong().plus(w).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt())
        val b = min(height, y.toLong().plus(h).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt())
        if (r <= l || b <= t) return null
        if (r - l == width && b - t == height) return this
        val out = Pixels(r - l, b - t)
        for (row in t until b) argb.copyInto(out.argb, (row - t) * out.width, row * width + l, row * width + r)
        return out
    }

    fun opaqueBounds(): Box? {
        var l = width
        var t = height
        var r = -1
        var b = -1
        for (y in 0 until height) {
            for (x in 0 until width) {
                if (argb[y * width + x] ushr ALPHA_SHIFT != 0) {
                    if (x < l) l = x
                    if (x > r) r = x
                    if (y < t) t = y
                    b = y
                }
            }
        }
        return if (r < 0) null else Box(l, t, r + 1, b + 1)
    }

    fun translucent(): Boolean = argb.any { it ushr ALPHA_SHIFT != OPAQUE }

    /** Each pixel counted by its alpha; at most [SAMPLES]² pixels are read. */
    fun average(keepAlpha: Boolean = true): Int? {
        var r = 0L
        var g = 0L
        var bl = 0L
        var a = 0L
        var n = 0L
        sample { p ->
            val al = p ushr ALPHA_SHIFT
            r += (p shr RED and BYTE) * al
            g += (p shr GREEN and BYTE) * al
            bl += (p and BYTE) * al
            a += al
            n++
        }
        if (a == 0L) return null
        val alpha = (a.toDouble() / n).roundToInt()
        val rgb = argbOf(OPAQUE, (r.toDouble() / a).roundToInt(), (g.toDouble() / a).roundToInt(), (bl.toDouble() / a).roundToInt())
        return if (!keepAlpha || alpha >= NEARLY_OPAQUE) rgb else (rgb and RGB) or (alpha shl ALPHA_SHIFT)
    }

    fun glyphColor(): Int? {
        var r = 0L
        var g = 0L
        var b = 0L
        var n = 0L
        sample { p ->
            if (p ushr ALPHA_SHIFT > GLYPH_ALPHA) {
                r += p shr RED and BYTE
                g += p shr GREEN and BYTE
                b += p and BYTE
                n++
            }
        }
        if (n == 0L) return null
        return argbOf(OPAQUE, (r.toDouble() / n).roundToInt(), (g.toDouble() / n).roundToInt(), (b.toDouble() / n).roundToInt())
    }

    private inline fun sample(each: (Int) -> Unit) {
        val sx = max(1, width / SAMPLES)
        val sy = max(1, height / SAMPLES)
        var y = 0
        while (y < height) {
            var x = 0
            while (x < width) {
                each(argb[y * width + x])
                x += sx
            }
            y += sy
        }
    }

    /** Alpha-weighted, so a transparent pixel's colour does not bleed. */
    fun resized(w: Int, h: Int): Pixels {
        if (w == width && h == height) return this
        val rows = resample(argb, width, height, w, horizontal = true)
        return Pixels(w, h, resample(rows, w, height, h, horizontal = false))
    }

    /** Insets that leave no middle along an axis (a skin's inner rect outside its source) are none along it, so nothing is left undrawn. */
    fun nine(insets: Box?, w: Int, h: Int): Pixels {
        val given = insets ?: Box(0, 0, 0, 0)
        val across = given.left.toLong() + given.right < width
        val down = given.top.toLong() + given.bottom < height
        val ins = Box(if (across) given.left else 0, if (down) given.top else 0, if (across) given.right else 0, if (down) given.bottom else 0)
        val l = min(min(ins.left, w / 2), width)
        val r = min(min(ins.right, w / 2), width - l)
        val t = min(min(ins.top, h / 2), height)
        val b = min(min(ins.bottom, h / 2), height - t)
        val out = Pixels(w, h)
        val xs = listOf(intArrayOf(0, l, 0, l), intArrayOf(l, width - r, l, w - r), intArrayOf(width - r, width, w - r, w))
        val ys = listOf(intArrayOf(0, t, 0, t), intArrayOf(t, height - b, t, h - b), intArrayOf(height - b, height, h - b, h))
        for (y in ys.filter { it[1] > it[0] && it[3] > it[2] }) {
            for (x in xs.filter { it[1] > it[0] && it[3] > it[2] }) {
                val part = crop(x[0], y[0], x[1] - x[0], y[1] - y[0]) ?: continue
                out.paste(part.resized(x[3] - x[2], y[3] - y[2]), x[2], y[2])
            }
        }
        return out
    }

    /** Replaced, not blended. */
    fun paste(src: Pixels, x: Int, y: Int) {
        val from = max(0, -x)
        val to = min(src.width, width - x)
        if (to <= from) return
        for (row in max(0, -y) until min(src.height, height - y)) {
            src.argb.copyInto(argb, (y + row) * width + x + from, row * src.width + from, row * src.width + to)
        }
    }

    fun over(src: Pixels, x: Int, y: Int) {
        for (row in max(0, -y) until min(src.height, height - y)) {
            for (col in max(0, -x) until min(src.width, width - x)) {
                val i = (y + row) * width + x + col
                argb[i] = blend(src.argb[row * src.width + col], argb[i])
            }
        }
    }

    data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val width get() = right - left
        val height get() = bottom - top
    }

    companion object {
        const val SAMPLES = 96
        const val OPAQUE = 0xFF
        const val NEARLY_OPAQUE = 250
        private const val GLYPH_ALPHA = 160
        private const val ALPHA_SHIFT = 24
        private const val RED = 16
        private const val GREEN = 8
        private const val BYTE = 0xFF
        private const val RGB = 0xFFFFFF

        fun argbOf(a: Int, r: Int, g: Int, b: Int): Int =
            (a.coerceIn(0, BYTE) shl ALPHA_SHIFT) or (r.coerceIn(0, BYTE) shl RED) or (g.coerceIn(0, BYTE) shl GREEN) or b.coerceIn(0, BYTE)

        fun filled(w: Int, h: Int, argb: Int) = Pixels(w, h, IntArray(w * h) { argb })

        private fun blend(s: Int, d: Int): Int {
            val sa = s ushr ALPHA_SHIFT
            if (sa == BYTE) return s
            if (sa == 0) return d
            val da = d ushr ALPHA_SHIFT
            val outA = sa + da * (BYTE - sa) / BYTE
            if (outA == 0) return 0
            fun ch(shift: Int) = ((s shr shift and BYTE) * sa + (d shr shift and BYTE) * da * (BYTE - sa) / BYTE) / outA
            return argbOf(outA, ch(RED), ch(GREEN), ch(0))
        }

        private fun resample(src: IntArray, w: Int, h: Int, n: Int, horizontal: Boolean): IntArray {
            val old = if (horizontal) w else h
            val lines = if (horizontal) h else w
            val newW = if (horizontal) n else w
            val out = IntArray(if (horizontal) n * h else w * n)
            val scale = old.toDouble() / n
            for (line in 0 until lines) {
                val at = if (horizontal) { k: Int -> src[line * w + k] } else { k: Int -> src[k * w + line] }
                for (i in 0 until n) {
                    val px = covered(i * scale, (i + 1) * scale, old, at)
                    if (horizontal) out[line * newW + i] = px else out[i * newW + line] = px
                }
            }
            return out
        }

        private fun covered(a0: Double, a1: Double, old: Int, at: (Int) -> Int): Int {
            var wa = 0.0
            var wr = 0.0
            var wg = 0.0
            var wb = 0.0
            var total = 0.0
            var k = a0.toInt()
            while (k < a1 && k < old) {
                val cover = min(a1, k + 1.0) - max(a0, k.toDouble())
                val p = at(k)
                val al = (p ushr ALPHA_SHIFT) * cover
                wa += al
                wr += (p shr RED and BYTE) * al
                wg += (p shr GREEN and BYTE) * al
                wb += (p and BYTE) * al
                total += cover
                k++
            }
            return if (wa <= 0.0) 0 else argbOf((wa / total).roundToInt(), (wr / wa).roundToInt(), (wg / wa).roundToInt(), (wb / wa).roundToInt())
        }
    }
}

/** Read a part at a time: an atlas of several megapixels is not copied whole. */
interface SkinImage {
    val width: Int
    val height: Int

    fun pixels(x: Int, y: Int, w: Int, h: Int): Pixels
}

interface SkinPlatform {
    /** Past [SkinLimits.MAX_IMAGE_PIXELS] is checked before decoding. */
    fun image(bytes: ByteArray): SkinImage?

    /** GB18030, as old Chinese skins are written. */
    fun legacyText(bytes: ByteArray): String
}

class PixelsImage(private val p: Pixels) : SkinImage {
    override val width get() = p.width
    override val height get() = p.height

    override fun pixels(x: Int, y: Int, w: Int, h: Int): Pixels = p.crop(x, y, w, h) ?: Pixels(1, 1)
}
