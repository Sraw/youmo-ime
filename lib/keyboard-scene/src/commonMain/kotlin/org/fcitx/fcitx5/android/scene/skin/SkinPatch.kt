/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene.skin

import kotlin.math.roundToInt

/** What the theme formats' readers ([TrimeSkin], [FlorisSkin]) share: uncompiled nine-patches, CSS colours, a board. */
object SkinPatch {
    /**
     * An uncompiled Android `.9.png`: a one-pixel border whose black marks (top, left) are the stretchable band, as
     * the picture inside it and that band's insets; one too small for a border, as it is.
     */
    fun nine(px: Pixels): SkinFill.Image {
        if (px.width < MIN_NINE || px.height < MIN_NINE) return SkinFill.Image(px, null)
        val inner = px.crop(1, 1, px.width - 2, px.height - 2)!!
        val across = marked((1 until px.width - 1).map { px[it, 0] })
        val down = marked((1 until px.height - 1).map { px[0, it] })
        val l = across?.first ?: 0
        val r = across?.let { inner.width - 1 - it.last } ?: 0
        val t = down?.first ?: 0
        val b = down?.let { inner.height - 1 - it.last } ?: 0
        return SkinFill.Image(inner, if (across == null && down == null) null else Pixels.Box(l, t, r, b))
    }

    // the first to the last black, opaque mark of a border's line
    private fun marked(line: List<Int>): IntRange? {
        val on = line.indices.filter {
            val p = line[it]
            p ushr ALPHA_SHIFT > HALF && (p shr RED and BYTE) < DARK && (p shr GREEN and BYTE) < DARK && (p and BYTE) < DARK
        }
        return if (on.isEmpty()) null else on.first()..on.last()
    }

    /**
     * The board of a theme whose keyboard picture ([board], under the keys only) has the bar above it: the bar's
     * height (a seventh of the width, as the app's) on top of the picture, the bar's fill there, the picture's mean
     * colour under it all; or, no picture, the colours alone.
     */
    fun board(look: SkinLook, board: SkinFill?, bar: SkinFill?) {
        val barArgb = when (bar) {
            is SkinFill.Color -> bar.argb
            is SkinFill.Image -> bar.pixels.average(keepAlpha = false)
            null -> null
        }
        if (board !is SkinFill.Image) {
            look.boardColor = (board as? SkinFill.Color)?.argb
            look.barColor = barArgb ?: look.boardColor
            return
        }
        val kb = fitted(board.pixels, BAR_RATIO)
        val colour = kb.average(keepAlpha = false) ?: GREY
        val h = (kb.width * BAR_RATIO).roundToInt().coerceAtLeast(1)
        val out = Pixels.filled(kb.width, h + kb.height, colour)
        out.over(kb, 0, h)
        when (bar) {
            is SkinFill.Image -> out.over(bar.pixels.nine(bar.insets, kb.width, h), 0, 0)
            is SkinFill.Color -> out.over(Pixels.filled(kb.width, h, bar.argb), 0, 0)
            null -> Unit
        }
        look.board = out
        look.boardColor = colour
        look.barColor = out.crop(0, 0, kb.width, h)?.average(keepAlpha = false) ?: colour
    }

    /**
     * [kb] shrunk where a board of it, a bar [barPerWidth] of its width on top, would be past
     * [SkinTheme.MAX_PICTURE_SIDE] either way (the theme's board is cut to that side all the same): a picture of an odd
     * shape (100000×120) is never put together at its size, which memory would not hold.
     */
    fun fitted(kb: Pixels, barPerWidth: Double): Pixels {
        val side = SkinTheme.MAX_PICTURE_SIDE.toDouble()
        val s = minOf(side / kb.width, side / (kb.height + kb.width * barPerWidth))
        if (s >= 1) return kb
        return kb.resized(maxOf(1, (kb.width * s).roundToInt()), maxOf(1, (kb.height * s).roundToInt()))
    }

    /** Whether two fills are the same (two of none too): equal colours, or images of the same pixels and insets. */
    fun same(a: SkinFill?, b: SkinFill?): Boolean = when {
        a is SkinFill.Image && b is SkinFill.Image ->
            a.insets == b.insets && a.pixels.width == b.pixels.width && (a.pixels === b.pixels || a.pixels.argb.contentEquals(b.pixels.argb))
        else -> a == b
    }

    /**
     * A CSS colour as FlorisBoard writes it: `#rgb`, `#rrggbb`, `#rrggbbaa` (alpha last), `rgb(r, g, b)`,
     * `rgba(r, g, b, a)` (a from 0 to 1), `transparent`; as ARGB, or null.
     */
    fun cssColor(value: String?): Int? {
        val v = value?.trim()?.lowercase() ?: return null
        if (v == "transparent") return 0
        HEX_COLOR.matchEntire(v)?.let { m ->
            val h = m.groupValues[1]
            val full = if (h.length == SHORT) h.map { "$it$it" }.joinToString("") else h
            val rgb = full.substring(0, RGB_DIGITS).toInt(HEX)
            val a = if (full.length == RGBA_DIGITS) full.substring(RGB_DIGITS).toInt(HEX) else BYTE
            return (a shl ALPHA_SHIFT) or rgb
        }
        val m = RGB_FUNCTION.matchEntire(v) ?: return null
        val (r, g, b) = (1..3).map { m.groupValues[it].toDoubleOrNull()?.roundToInt()?.coerceIn(0, BYTE) ?: return null }
        val a = m.groupValues[4].takeIf { it.isNotEmpty() }?.toDoubleOrNull()?.let { (it * BYTE).roundToInt().coerceIn(0, BYTE) } ?: BYTE
        return Pixels.argbOf(a, r, g, b)
    }

    private val HEX_COLOR = Regex("#([0-9a-f]{3}|[0-9a-f]{6}|[0-9a-f]{8})")
    private val RGB_FUNCTION = Regex("rgba?\\(\\s*([\\d.]+)\\s*,\\s*([\\d.]+)\\s*,\\s*([\\d.]+)\\s*(?:,\\s*([\\d.]+)\\s*)?\\)")
    private const val MIN_NINE = 3
    private const val ALPHA_SHIFT = 24
    private const val RED = 16
    private const val GREEN = 8
    private const val BYTE = 0xFF
    private const val HALF = 128
    private const val DARK = 64
    private const val SHORT = 3
    private const val RGB_DIGITS = 6
    private const val RGBA_DIGITS = 8
    private const val HEX = 16
    private const val BAR_RATIO = 0.115
    private const val GREY = 0xFFD1D4DB.toInt()
}
