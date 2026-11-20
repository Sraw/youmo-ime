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
import android.util.SparseArray
import android.view.View
import android.view.ViewGroup
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.theme.ThemeAssets
import org.fcitx.fcitx5.android.input.keyboard.canvas.KeyboardCanvasView
import org.fcitx.fcitx5.android.input.theme.PictureWindow
import org.fcitx.fcitx5.android.input.theme.ThemeColors
import org.fcitx.fcitx5.android.scene.look.BoardBase
import org.fcitx.fcitx5.android.scene.look.ThemeLook
import org.fcitx.fcitx5.android.utils.DarkenColorFilter
import java.lang.ref.WeakReference

/**
 * Laid over [base] as CENTER_CROP lays it, so a view anywhere over it shows the part under it, as the
 * theme maker draws it. [animated]: the base plays it, and a view showing it is see-through then, only
 * darkened to its brightness, never blurred.
 */
class PictureHost(private val look: ThemeLook, val base: View, private val landscape: Boolean) {

    var animated: AnimatedBoardDrawable? = null
        private set

    /** The keys' canvas is recorded again, on a layer of its own: the frames under it change, not the keys. */
    fun play(frames: AnimatedBoardDrawable) {
        animated = frames
        (base as? PictureBaseView)?.play(frames) ?: run { base.background = frames }
        (base.parent as? ViewGroup)?.let { redraw(it) }
    }

    private fun redraw(view: View) {
        if (view is KeyboardCanvasView) view.pictureChanged() else view.invalidate()
        if (view is ViewGroup) for (i in 0 until view.childCount) redraw(view.getChildAt(i))
    }

    private val dark = Paint()

    /** Null where nothing is to be drawn over it. */
    fun seeThrough(brightness: Int): Paint? {
        val board = animated?.brightness ?: return null
        val darken = PictureWindow.seeThroughDarken(board, brightness)
        if (darken == 0) return null
        dark.color = android.graphics.Color.argb(darken * 255 / 100, 0, 0, 0)
        return dark
    }

    // asked of ThemeAssets once, not each key a frame; again once the files were let go as changed (a theme
    // imported again under its name: the theme equal, nothing made anew for it)
    private class Placed(
        val bitmap: Bitmap?, val w: Int, val h: Int, val scale: Float, val dx: Float, val dy: Float, val generation: Int,
    )

    private val placed = SparseArray<Placed>(2)
    private val baseAt = IntArray(2)
    private val viewAt = IntArray(2)
    private val matrix = Matrix()

    /** Null with no picture, or while it is decoded on another thread ([view] drawn again once in: no frame waited). */
    fun paint(view: View, blurDp: Int, brightness: Int, into: PicturePaint, left: Int = 0, top: Int = 0): Paint? {
        val w = base.width
        val h = base.height
        if (w == 0 || h == 0) return null
        val at = placed[blurDp]?.takeIf { it.w == w && it.h == h && it.generation == ThemeAssets.generation }
            ?: place(view, blurDp, w, h) ?: return null
        val bitmap = at.bitmap ?: return null
        matrix.setScale(at.scale, at.scale)
        if (!offsetOf(view)) {
            base.getLocationInWindow(baseAt)
            view.getLocationInWindow(viewAt)
            offset[0] = (viewAt[0] - baseAt[0]).toFloat()
            offset[1] = (viewAt[1] - baseAt[1]).toFloat()
        }
        matrix.postTranslate(at.dx - offset[0] - left, at.dy - offset[1] - top)
        return into.of(bitmap, matrix, brightness)
    }

    private val decodingBlurs = HashSet<Int>()

    /** False where nothing will come: no point recording a view again each frame for it. */
    fun decoding(blurDp: Int): Boolean = blurDp in decodingBlurs

    private fun place(view: View, blurDp: Int, w: Int, h: Int): Placed? {
        // before it is asked for: let go while asked, it is asked again
        val generation = ThemeAssets.generation
        // not holding the view, nor this host, till the decode is done: a keyboard let go meanwhile is not kept
        val asking = WeakReference(view)
        val host = WeakReference(this)
        val got = ThemeAssets.pictureOrLater(look, landscape, blurDp, w, base.resources.displayMetrics.density) {
            host.get()?.decodingBlurs?.remove(blurDp)
            asking.get()?.invalidate()
        }
        if (got == null) {
            decodingBlurs.add(blurDp)
            return null
        }
        decodingBlurs.remove(blurDp)
        val bitmap = got.bitmap
        val p = bitmap?.let { PictureWindow.centerCrop(it.width, it.height, w.toFloat(), h.toFloat()) }
        return Placed(bitmap, w, h, p?.scale ?: 1f, p?.dx ?: 0f, p?.dy ?: 0f, generation).also { placed.put(blurDp, it) }
    }

    private val offset = FloatArray(2)
    private val baseOffset = FloatArray(2)

    // from where each is in their root view: no window asked twice a key a frame. False under a scaled or
    // turned parent, or for views of two roots
    private fun offsetOf(view: View): Boolean {
        val root = inRoot(view, offset) ?: return false
        if (inRoot(base, baseOffset) !== root) return false
        offset[0] -= baseOffset[0]
        offset[1] -= baseOffset[1]
        return true
    }

    private fun inRoot(view: View, out: FloatArray): View? {
        var x = 0f
        var y = 0f
        var at = view
        while (true) {
            if (!at.plain()) return null
            val parent = at.parent as? View ?: break
            x += at.left + at.translationX - parent.scrollX
            y += at.top + at.translationY - parent.scrollY
            at = parent
        }
        out[0] = x
        out[1] = y
        return at
    }

    private fun View.plain() = scaleX == 1f && scaleY == 1f && rotation == 0f && rotationX == 0f && rotationY == 0f

    companion object {
        fun install(on: View, host: PictureHost?) = on.setTag(R.id.theme_picture_host, host)

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

/** Its shader made again only for another bitmap. */
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
        val host = view?.let { PictureHost.of(it) }
        if (host?.animated != null) {
            host.seeThrough(brightness)?.let { canvas.drawRect(bounds, it) }
            if (over ushr 24 != 0) {
                solid.color = over
                canvas.drawRect(bounds, solid)
            }
            return
        }
        val p = view?.let { v -> host?.paint(v, blurDp, brightness, picture) }
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
        /** An animated one its still picture till its frames are decoded. */
        fun board(look: ThemeLook): Drawable {
            val color = ThemeColors.board(look.palette, look.keys)
            val p = look.picture
            return if (p != null && look.base == BoardBase.Picture) {
                ThemePictureDrawable(p.blurDp, p.brightness, 0, color)
            } else {
                ColorDrawable(color)
            }
        }
    }
}
