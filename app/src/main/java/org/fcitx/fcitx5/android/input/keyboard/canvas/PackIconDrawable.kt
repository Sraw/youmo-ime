/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard.canvas

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import org.fcitx.fcitx5.android.scene.Box
import org.fcitx.fcitx5.android.scene.theme.Argb

/**
 * A theme's icon ([PackIcons.Outline]) in [tint] (its icon-color), rastered once at [pixelWidth] × [pixelHeight]
 * (the size it shows at, the canvas's scale applied, as a vector drawable's raster is) and drawn as that bitmap
 * in its bounds after: a key's display list redrawn each frame does not fill thousands of curves again.
 * The paths drawn as the web's preview draws them (CanvasPainter.icon): placed by [SvgSubset.Icon.fit], one
 * without a colour of its own in the tint, one with its own colour faded by the tint's alpha, each times its opacity.
 */
class PackIconDrawable(private val outline: PackIcons.Outline, private val tint: Argb, private val pixelWidth: Int, private val pixelHeight: Int) : Drawable() {

    private val raster: Bitmap? by lazy { raster() }
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)

    private fun raster(): Bitmap? {
        if (pixelWidth <= 0 || pixelHeight <= 0 || tint.transparent) return null
        val b = Bitmap.createBitmap(pixelWidth, pixelHeight, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val fit = outline.icon.fit(Box(0.0, 0.0, pixelWidth.toDouble(), pixelHeight.toDouble()))
        c.concat(Matrix().apply { setScale(fit.scale.toFloat(), fit.scale.toFloat()); postTranslate(fit.dx.toFloat(), fit.dy.toFloat()) })
        val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        outline.icon.paths.forEachIndexed { i, p ->
            val own = p.color
            val color = own ?: tint
            val alpha = color.alpha * (if (own != null) tint.alpha / OPAQUE else 1.0) * p.alpha
            fill.color = Argb.of(Math.round(alpha).toInt(), color.red, color.green, color.blue).value
            c.drawPath(outline.paths[i], fill)
        }
        return b
    }

    override fun draw(canvas: Canvas) {
        raster?.let { canvas.drawBitmap(it, null, bounds, paint) }
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    private companion object {
        const val OPAQUE = 255.0
    }
}
