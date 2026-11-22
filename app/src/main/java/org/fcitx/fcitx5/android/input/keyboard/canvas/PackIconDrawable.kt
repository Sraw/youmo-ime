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
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RectF
import android.graphics.drawable.Drawable
import org.fcitx.fcitx5.android.scene.Box
import org.fcitx.fcitx5.android.scene.theme.Argb

/**
 * Rastered once at the size it shows at and drawn as that bitmap after: a key's display list redrawn each frame
 * does not fill thousands of curves again. The paths drawn as the web's preview draws them (CanvasPainter.icon).
 * A PNG or WebP is decoded at that size already ([PackIcons.Raster]), centred: in [tint] where [tintMask], else in its
 * own colours at [tint]'s alpha.
 */
class PackIconDrawable(
    private val icon: PackIcons.PackIcon,
    private val tint: Argb,
    private val pixelWidth: Int,
    private val pixelHeight: Int,
    private val tintMask: Boolean = false,
) : Drawable() {

    private val raster: Bitmap? by lazy { raster() }
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private var alpha = OPAQUE.toInt()
    private val dst = RectF()

    init {
        if (icon is PackIcons.Raster && tintMask) paint.colorFilter = PorterDuffColorFilter(tint.value or OPAQUE_RGB, PorterDuff.Mode.SRC_IN)
    }

    private fun raster(): Bitmap? {
        if (pixelWidth <= 0 || pixelHeight <= 0 || tint.transparent) return null
        val outline = when (icon) {
            is PackIcons.Raster -> return icon.bitmap
            is PackIcons.Outline -> icon
        }
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
        val r = raster ?: return
        if (icon !is PackIcons.Raster) {
            paint.alpha = alpha
            canvas.drawBitmap(r, null, bounds, paint)
            return
        }
        // the drawn size is in pixels of the box, the bounds in the canvas's units; a bitmap lent is of another size
        val w = icon.width * bounds.width().toFloat() / pixelWidth
        val h = icon.height * bounds.height().toFloat() / pixelHeight
        dst.set(bounds.exactCenterX() - w / 2, bounds.exactCenterY() - h / 2, bounds.exactCenterX() + w / 2, bounds.exactCenterY() + h / 2)
        paint.alpha = Math.round(alpha * tint.alpha / OPAQUE).toInt()
        canvas.drawBitmap(r, null, dst, paint)
    }

    // an ImageView's wrap_content as a vector's 24 dp would be
    override fun getIntrinsicWidth(): Int = pixelWidth

    override fun getIntrinsicHeight(): Int = pixelHeight

    override fun setAlpha(alpha: Int) {
        this.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    private companion object {
        const val OPAQUE = 255.0
        const val OPAQUE_RGB = 0xFF000000.toInt()
    }
}
