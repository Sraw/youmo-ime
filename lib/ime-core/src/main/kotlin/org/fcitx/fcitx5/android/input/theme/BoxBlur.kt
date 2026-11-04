/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.theme

/**
 * The theme's blur, the app's and the theme maker's alike to the pixel: three box blurs, across then
 * down each time (near a Gaussian), on 0xAARRGGBB pixels, edges clamped, integers rounded half up.
 * Done on a picture [shrink]ed to a quarter a side: as blurred, no difference seen, a 16th the memory.
 */
object BoxBlur {

    const val PASSES = 3

    /** Each [factor]×[factor] block of [pixels] ([width]×[height]) averaged into one pixel; a partial block at an edge is left out. */
    fun shrink(pixels: IntArray, width: Int, height: Int, factor: Int): IntArray {
        require(factor >= 1)
        val w = maxOf(1, width / factor)
        val h = maxOf(1, height / factor)
        val fx = minOf(factor, width)
        val fy = minOf(factor, height)
        val n = fx * fy
        val out = IntArray(w * h)
        val sums = LongArray(4)
        for (y in 0 until h) for (x in 0 until w) {
            sums.fill(0)
            for (yy in 0 until fy) for (xx in 0 until fx) add(sums, pixels[(y * fy + yy) * width + x * fx + xx])
            out[y * w + x] = pack(sums, n)
        }
        return out
    }

    /** [pixels] ([width]×[height]) blurred by [radius] px; radius 0, as they are. */
    fun blur(pixels: IntArray, width: Int, height: Int, radius: Int): IntArray {
        require(radius >= 0)
        if (radius == 0) return pixels.copyOf()
        var cur = pixels.copyOf()
        val tmp = IntArray(cur.size)
        repeat(PASSES) {
            pass(cur, tmp, width, height, radius, horizontal = true)
            pass(tmp, cur, width, height, radius, horizontal = false)
        }
        return cur
    }

    // one box along rows (or columns), clamped at the ends
    private fun pass(src: IntArray, dst: IntArray, width: Int, height: Int, r: Int, horizontal: Boolean) {
        val lines = if (horizontal) height else width
        val len = if (horizontal) width else height
        val n = 2 * r + 1
        val sums = LongArray(4)
        for (line in 0 until lines) {
            fun at(i: Int) = src[if (horizontal) line * width + i.coerceIn(0, len - 1) else i.coerceIn(0, len - 1) * width + line]
            sums.fill(0)
            for (i in -r..r) add(sums, at(i))
            for (i in 0 until len) {
                dst[if (horizontal) line * width + i else i * width + line] = pack(sums, n)
                add(sums, at(i - r), -1)
                add(sums, at(i + r + 1))
            }
        }
    }

    private fun add(s: LongArray, c: Int, sign: Int = 1) {
        s[0] += sign * (c ushr 24 and 0xff).toLong()
        s[1] += sign * (c ushr 16 and 0xff).toLong()
        s[2] += sign * (c ushr 8 and 0xff).toLong()
        s[3] += sign * (c and 0xff).toLong()
    }

    private fun pack(s: LongArray, n: Int): Int {
        fun ch(v: Long) = ((v * 2 + n) / (2 * n)).toInt()
        return (ch(s[0]) shl 24) or (ch(s[1]) shl 16) or (ch(s[2]) shl 8) or ch(s[3])
    }
}
