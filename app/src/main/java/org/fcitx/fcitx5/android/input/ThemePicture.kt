/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Shader
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.view.View
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.theme.ThemeAssets
import org.fcitx.fcitx5.android.input.theme.BarBase
import org.fcitx.fcitx5.android.input.theme.BoardBase
import org.fcitx.fcitx5.android.input.theme.PictureWindow
import org.fcitx.fcitx5.android.input.theme.ThemeColors
import org.fcitx.fcitx5.android.input.theme.ThemeLook
import org.fcitx.fcitx5.android.utils.DarkenColorFilter

/**
 * The theme's picture under a keyboard: laid over [base] (the view under the whole keyboard) as
 * CENTER_CROP lays it, so a view anywhere over it — a key, the bar — shows the part under it, as
 * the theme maker draws it ([PictureWindow]). Found by the views over it through [host]'s tag.
 */
class PictureHost(private val look: ThemeLook, val base: View, private val landscape: Boolean) {

    private val baseAt = IntArray(2)
    private val viewAt = IntArray(2)
    private val matrix = Matrix()

    /**
     * [into]'s paint set to the picture blurred [blurDp] at [brightness] percent lying where [view] is
     * over the base, for drawing in [view]'s coordinates; null with no picture or none that reads.
     */
    fun paint(view: View, blurDp: Int, brightness: Int, into: PicturePaint): Paint? {
        if (base.width == 0 || base.height == 0) return null
        val dm = base.resources.displayMetrics
        val bitmap = ThemeAssets.picture(look, landscape, blurDp, base.width, dm.density) ?: return null
        val p = PictureWindow.centerCrop(bitmap.width, bitmap.height, base.width.toFloat(), base.height.toFloat())
        base.getLocationInWindow(baseAt)
        view.getLocationInWindow(viewAt)
        matrix.setScale(p.scale, p.scale)
        matrix.postTranslate(p.dx - (viewAt[0] - baseAt[0]), p.dy - (viewAt[1] - baseAt[1]))
        return into.of(bitmap, matrix, brightness)
    }

    companion object {
        /** Puts [host] where the views in [on] find it. */
        fun install(on: View, host: PictureHost?) = on.setTag(R.id.theme_picture_host, host)

        /** The picture host [v] is over: the nearest of its parents that has one. */
        fun of(v: View): PictureHost? {
            var at: Any? = v
            while (at is View) {
                (at.getTag(R.id.theme_picture_host) as? PictureHost)?.let { return it }
                at = at.parent
            }
            return null
        }
    }
}

/** A drawable's paint of the picture: its shader made again only for another bitmap. */
class PicturePaint {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private var bitmap: Bitmap? = null
    private var shader: BitmapShader? = null
    private var brightness = 100

    fun of(bitmap: Bitmap, matrix: Matrix, brightness: Int): Paint {
        val s = shader.takeIf { this.bitmap === bitmap }
            ?: BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).also {
                this.bitmap = bitmap
                shader = it
                paint.shader = it
            }
        s.setLocalMatrix(matrix)
        if (brightness != this.brightness || paint.colorFilter == null && brightness < 100) {
            this.brightness = brightness
            paint.colorFilter = if (brightness < 100) DarkenColorFilter(100 - brightness) else null
        }
        return paint
    }
}

/**
 * The picture's part where its view is, blurred [blurDp] at [brightness] percent, [over] laid on it
 * (a colour, 0 none); [fallback] where there is no picture to show (one that does not read, or a view
 * over none). The keyboard's base, the bar's.
 */
class ThemePictureDrawable(
    private val blurDp: Int,
    private val brightness: Int,
    private val over: Int,
    private val fallback: Int,
) : Drawable() {

    private val picture = PicturePaint()
    private val solid = Paint()

    override fun draw(canvas: Canvas) {
        val view = callback as? View
        val p = view?.let { v -> PictureHost.of(v)?.paint(v, blurDp, brightness, picture) }
        if (p == null) {
            solid.color = fallback
            canvas.drawRect(bounds, solid)
            return
        }
        canvas.drawRect(bounds, p)
        if (over ushr 24 != 0) {
            solid.color = over
            canvas.drawRect(bounds, solid)
        }
    }

    override fun setAlpha(alpha: Int) = Unit

    override fun setColorFilter(colorFilter: ColorFilter?) = Unit

    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT

    companion object {
        /** Under [look]'s keyboard: its picture as cut for it, or the colour ([ThemeColors.board]). */
        fun board(look: ThemeLook): Drawable {
            val color = ThemeColors.board(look.palette, look.keys)
            val p = look.picture
            return if (p != null && look.base == BoardBase.Picture) {
                ThemePictureDrawable(p.blurDp, p.brightness, 0, color)
            } else {
                ColorDrawable(color)
            }
        }

        /** Under [look]'s bar: none (the keyboard's base seen through), its colour, or the picture's part there. */
        fun bar(look: ThemeLook): Drawable? = when (val bar = look.bar) {
            BarBase.Transparent -> null
            BarBase.Color -> ColorDrawable(look.palette.barColor)
            is BarBase.Picture -> ThemePictureDrawable(bar.blurDp, bar.brightness, 0, look.palette.barColor)
        }
    }
}
