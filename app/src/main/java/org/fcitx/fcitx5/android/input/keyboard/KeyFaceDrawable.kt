/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.view.View
import androidx.annotation.ColorInt
import org.fcitx.fcitx5.android.data.theme.BoardImages
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.input.PictureHost
import org.fcitx.fcitx5.android.input.PicturePaint
import org.fcitx.fcitx5.android.input.theme.Area
import org.fcitx.fcitx5.android.input.theme.KeyFace
import org.fcitx.fcitx5.android.input.theme.KeyFill
import org.fcitx.fcitx5.android.input.theme.KeyKind
import org.fcitx.fcitx5.android.input.theme.KeyPressed
import org.fcitx.fcitx5.android.input.theme.KeyStyle
import org.fcitx.fcitx5.android.input.theme.NineSlice
import org.fcitx.fcitx5.android.input.theme.NineSliceLayout
import org.fcitx.fcitx5.android.input.theme.PressEffect
import org.fcitx.fcitx5.android.input.theme.ThemeColors
import kotlin.math.min

/**
 * What a key's face is filled with: a colour, a nine-slice image of its keyboard's ([BoardImages];
 * its [Image.fallback] colour till it is decoded, or if it does not read), or the theme's picture
 * where the key is, blurred, at a brightness, a colour laid over it ([Picture.over]).
 */
sealed interface FaceFill {
    data class Solid(@ColorInt val color: Int) : FaceFill
    class Image(val images: BoardImages, val slice: NineSlice, @ColorInt val fallback: Int) : FaceFill
    class Picture(val blurDp: Int, val brightness: Int, @ColorInt val over: Int) : FaceFill
}

/**
 * [this] drawn as a key of [theme] styled [s] (KeyStyleResolver), of [kind], in the shape [geometry]
 * gives: its face the background, its images [images]'s; a press laid over it ([KeyFaceDrawable.highlight])
 * if [press] and its own pressed face does not show it. A key; the nine keys' column, the handwriting pad.
 */
fun View.drawAsKey(
    theme: Theme,
    s: KeyStyle,
    kind: KeyKind,
    images: BoardImages,
    geometry: (Float, Float) -> KeyFace,
    press: Boolean = true,
) {
    val look = theme.look
    val palette = look.palette
    val color = ThemeColors.face(palette, kind, look.keys.blocks)
    val fill = when (val f = s.fill) {
        KeyFill.Color -> FaceFill.Solid(color)
        is KeyFill.Image -> FaceFill.Image(images, f.slice, color)
        is KeyFill.Picture -> FaceFill.Picture(f.blurDp, f.brightness, color)
    }
    val pressed = when (val p = s.pressed) {
        KeyPressed.Darken -> null
        is KeyPressed.Color -> FaceFill.Solid(p.argb)
        is KeyPressed.Image -> FaceFill.Image(images, p.slice, color)
    }
    background = KeyFaceDrawable(geometry, fill, pressed, palette.keyBorderColor, palette.keyShadowColor)
    if (!press) return
    // a press shown by its own face needs no tint; a ripple is a ripple whatever the face
    val ripple = look.keys.press == PressEffect.Ripple
    foreground = if (ripple || pressed == null) KeyFaceDrawable.highlight(geometry, palette.keyPressHighlightColor, ripple) else null
}

/**
 * A key's face as the theme draws it, in the shape [geometry] gives for its size (KeyFaceGeometry:
 * the gap inside, the shadow under it, the border on its edge, flat keys' bar and circle): the
 * shadow, the face [fill]ed, the border. Pressed, filled with [pressed] if it has one (a colour or
 * image of its own); a press that darkens is the [highlight] laid over the key, as before 3.0.
 * Replaces the shadowed and bordered faces drawn by hand before.
 */
class KeyFaceDrawable(
    private val geometry: (width: Float, height: Float) -> KeyFace,
    private val fill: FaceFill,
    private val pressed: FaceFill?,
    @ColorInt private val borderColor: Int,
    @ColorInt private val shadowColor: Int,
) : Drawable() {

    private var face: KeyFace? = null
    private var down = false
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val imagePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val picture = PicturePaint()
    private val rect = RectF()
    private val src = Rect()
    private val clip = Path()
    private var waiting = false

    override fun onBoundsChange(bounds: Rect) {
        super.onBoundsChange(bounds)
        face = if (bounds.isEmpty) null else geometry(bounds.width().toFloat(), bounds.height().toFloat())
    }

    override fun isStateful() = pressed != null

    override fun onStateChange(state: IntArray): Boolean {
        val now = android.R.attr.state_pressed in state
        if (now == down) return false
        down = now
        return pressed != null
    }

    override fun draw(canvas: Canvas) {
        val f = face ?: return
        val b = bounds
        canvas.save()
        canvas.translate(b.left.toFloat(), b.top.toFloat())
        val area = f.face
        if (area != null) {
            f.shadow?.let {
                paint.style = Paint.Style.FILL
                paint.color = shadowColor
                drawShape(canvas, it, f, paint)
            }
            drawFill(canvas, area, f, if (down && pressed != null) pressed else fill)
            if (f.border > 0f && borderColor ushr 24 != 0) {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = f.border
                paint.color = borderColor
                val half = f.border / 2
                drawShape(canvas, Area(area.left + half, area.top + half, area.right - half, area.bottom - half), f, paint, f.radius - half)
                paint.style = Paint.Style.FILL
            }
        }
        canvas.restore()
    }

    private fun drawShape(canvas: Canvas, a: Area, f: KeyFace, p: Paint, radius: Float = f.radius) {
        rect.set(a.left, a.top, a.right, a.bottom)
        if (f.oval) {
            canvas.drawOval(rect, p)
        } else {
            val r = radius.coerceIn(0f, min(rect.width(), rect.height()) / 2)
            canvas.drawRoundRect(rect, r, r, p)
        }
    }

    private fun drawFill(canvas: Canvas, area: Area, f: KeyFace, fill: FaceFill) {
        when (fill) {
            is FaceFill.Solid -> solid(canvas, area, f, fill.color)
            is FaceFill.Image -> {
                val bitmap = fill.images.bitmap(fill.slice.file)
                val size = fill.images.size(fill.slice.file)
                if (bitmap == null || size == null) {
                    solid(canvas, area, f, fill.fallback)
                    if (!fill.images.ready && !waiting) {
                        waiting = true
                        fill.images.whenReady { waiting = false; invalidateSelf() }
                    }
                    return
                }
                val density = (callback as? View)?.resources?.displayMetrics?.density ?: 1f
                // the insets are in the file's pixels; it may have been decoded smaller (KeyImageBudget)
                val sx = bitmap.width.toFloat() / size[0]
                val sy = bitmap.height.toFloat() / size[1]
                canvas.save()
                clipTo(canvas, area, f)
                for (piece in NineSliceLayout.of(size[0], size[1], fill.slice, area, density)) {
                    src.set((piece.src.left * sx).toInt(), (piece.src.top * sy).toInt(), (piece.src.right * sx).toInt(), (piece.src.bottom * sy).toInt())
                    rect.set(piece.dst.left, piece.dst.top, piece.dst.right, piece.dst.bottom)
                    canvas.drawBitmap(bitmap, src, rect, imagePaint)
                }
                canvas.restore()
            }
            is FaceFill.Picture -> {
                val view = callback as? View
                val p = view?.let { PictureHost.of(it)?.paint(it, fill.blurDp, fill.brightness, picture) }
                if (p == null) {
                    solid(canvas, area, f, fill.over)
                    return
                }
                // the picture lies in the view's coordinates; this face is at the bounds' corner in it
                canvas.save()
                canvas.translate(-bounds.left.toFloat(), -bounds.top.toFloat())
                val a = Area(area.left + bounds.left, area.top + bounds.top, area.right + bounds.left, area.bottom + bounds.top)
                drawShape(canvas, a, f, p)
                canvas.restore()
                if (fill.over ushr 24 != 0) solid(canvas, area, f, fill.over)
            }
        }
    }

    private fun solid(canvas: Canvas, area: Area, f: KeyFace, color: Int) {
        if (color ushr 24 == 0) return
        paint.style = Paint.Style.FILL
        paint.color = color
        drawShape(canvas, area, f, paint)
    }

    private fun clipTo(canvas: Canvas, a: Area, f: KeyFace) {
        rect.set(a.left, a.top, a.right, a.bottom)
        clip.reset()
        if (f.oval) {
            clip.addOval(rect, Path.Direction.CW)
        } else {
            val r = f.radius.coerceIn(0f, min(rect.width(), rect.height()) / 2)
            clip.addRoundRect(rect, r, r, Path.Direction.CW)
        }
        canvas.clipPath(clip)
    }

    override fun setAlpha(alpha: Int) = Unit

    override fun setColorFilter(colorFilter: ColorFilter?) = Unit

    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT

    companion object {
        /**
         * The press laid over a key in its pressed shape: [color] while pressed (a tint), or a ripple of
         * it ([ripple]); none for a key whose own pressed face shows the press and no ripple.
         */
        fun highlight(geometry: (Float, Float) -> KeyFace, @ColorInt color: Int, ripple: Boolean): Drawable =
            if (ripple) {
                RippleDrawable(ColorStateList.valueOf(color), null, PressShape(geometry, android.graphics.Color.WHITE))
            } else {
                StateListDrawable().apply {
                    addState(intArrayOf(android.R.attr.state_pressed), PressShape(geometry, color))
                }
            }
    }

    /** The pressed shape of a key filled with [color]: a highlight, a ripple's mask. */
    private class PressShape(private val geometry: (Float, Float) -> KeyFace, @ColorInt color: Int) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
        private val rect = RectF()
        private var face: KeyFace? = null

        override fun onBoundsChange(bounds: Rect) {
            face = if (bounds.isEmpty) null else geometry(bounds.width().toFloat(), bounds.height().toFloat())
        }

        override fun draw(canvas: Canvas) {
            val f = face ?: return
            val a = f.pressed
            rect.set(a.left + bounds.left, a.top + bounds.top, a.right + bounds.left, a.bottom + bounds.top)
            if (f.oval) {
                canvas.drawOval(rect, paint)
            } else {
                val r = f.radius.coerceIn(0f, min(rect.width(), rect.height()) / 2)
                canvas.drawRoundRect(rect, r, r, paint)
            }
        }

        override fun setAlpha(alpha: Int) {
            paint.alpha = alpha
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            paint.colorFilter = colorFilter
        }

        @Deprecated("Deprecated in Java")
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }
}
