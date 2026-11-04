/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.theme

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import org.fcitx.fcitx5.android.input.theme.KeyFace
import org.fcitx.fcitx5.android.input.theme.KeyFaceGeometry
import org.fcitx.fcitx5.android.input.theme.KeyKind
import org.fcitx.fcitx5.android.input.theme.ThemeColors
import org.fcitx.fcitx5.android.input.theme.ThemeLook
import org.fcitx.fcitx5.android.input.theme.cornerRadius

/**
 * A theme's keys in little, over its thumbnail's base: four rows as the 26 keys have them, each
 * kind's face in its colour, in the theme's shape (blocks or flat, corners, gaps, shadow, border) at
 * about a third of its size. A kind of an image or the picture is its colour (thumbnails decode no images).
 * The bar's strip above them is [barPx] tall, not drawn here.
 */
class ThemeThumbnailKeys(private val look: ThemeLook, private val density: Float, private val barPx: Float) : Drawable() {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    // each row: the kinds and their widths in tenths of the board
    private val rows = listOf(
        List(10) { KeyKind.Letter to 1f },
        List(9) { KeyKind.Letter to 1f },
        listOf(KeyKind.Function to 1.5f) + List(7) { KeyKind.Letter to 1f } + listOf(KeyKind.Function to 1.5f),
        listOf(KeyKind.Function to 1.5f, KeyKind.Letter to 1f, KeyKind.Space to 5f, KeyKind.Letter to 1f, KeyKind.Enter to 1.5f),
    )

    override fun draw(canvas: Canvas) {
        val b = bounds
        val top = b.top + barPx
        val rowHeight = (b.bottom - top) / rows.size
        val unit = b.width() / 10f
        // the shape at a little of its size: the gaps and corners as they look beside the little keys
        val d = density * SCALE
        rows.forEachIndexed { r, row ->
            val width = row.sumOf { it.second.toDouble() }.toFloat() * unit
            var x = b.left + (b.width() - width) / 2
            val y = top + r * rowHeight
            for ((kind, w) in row) {
                val face = KeyFaceGeometry.of(w * unit, rowHeight, kind, look.keys, false, d)
                draw(canvas, face, kind, x, y)
                x += w * unit
            }
        }
    }

    private fun draw(canvas: Canvas, f: KeyFace, kind: KeyKind, x: Float, y: Float) {
        val area = f.face ?: return
        val p = look.palette
        f.shadow?.let {
            paint.style = Paint.Style.FILL
            paint.color = p.keyShadowColor
            shape(canvas, it.left + x, it.top + y, it.right + x, it.bottom + y, f)
        }
        paint.style = Paint.Style.FILL
        // an image or the picture shown through: the kind's colour, as the key draws it till the image is in
        paint.color = ThemeColors.face(p, kind, look.keys.blocks)
        if (paint.color ushr 24 != 0) shape(canvas, area.left + x, area.top + y, area.right + x, area.bottom + y, f)
        if (f.border > 0f) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = f.border
            paint.color = p.keyBorderColor
            val h = f.border / 2
            shape(canvas, area.left + x + h, area.top + y + h, area.right + x - h, area.bottom + y - h, f)
        }
    }

    private fun shape(canvas: Canvas, l: Float, t: Float, r: Float, b: Float, f: KeyFace) {
        rect.set(l, t, r, b)
        if (f.oval) {
            canvas.drawOval(rect, paint)
        } else {
            val radius = cornerRadius(f.radius, rect.width(), rect.height())
            canvas.drawRoundRect(rect, radius, radius, paint)
        }
    }

    override fun setAlpha(alpha: Int) = Unit

    override fun setColorFilter(colorFilter: ColorFilter?) = Unit

    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT

    private companion object {
        // a thumbnail is about a third of the keyboard across
        const val SCALE = 0.35f
    }
}
