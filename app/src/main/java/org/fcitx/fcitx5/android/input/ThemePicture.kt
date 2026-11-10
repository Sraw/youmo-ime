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
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.theme.ThemeAssets
import org.fcitx.fcitx5.android.input.theme.PictureWindow
import org.fcitx.fcitx5.android.input.theme.ThemeColors
import org.fcitx.fcitx5.android.scene.look.BoardBase
import org.fcitx.fcitx5.android.scene.look.ThemeLook
import org.fcitx.fcitx5.android.utils.DarkenColorFilter
import java.lang.ref.WeakReference

/**
 * The theme's picture under a keyboard: laid over [base] (the view under the whole keyboard) as
 * CENTER_CROP lays it, so a view anywhere over it — a key, the bar — shows the part under it, as
 * the theme maker draws it ([PictureWindow]). Found by the views over it through [host]'s tag.
 * [animated]: the base plays the picture (060-render-p3 A4, [AnimatedBoardDrawable]); a view showing it is
 * see-through then, only darkened to its brightness ([seeThrough]), never blurred.
 */
class PictureHost(private val look: ThemeLook, val base: View, private val landscape: Boolean, val animated: AnimatedBoardDrawable? = null) {

    private val dark = Paint()

    /**
     * Over an [animated] base: the paint that makes a view showing the picture at [brightness] (in its
     * coordinates, the base under it showing through), null where nothing is to be drawn over it.
     */
    fun seeThrough(brightness: Int): Paint? {
        val board = animated?.brightness ?: return null
        val darken = PictureWindow.seeThroughDarken(board, brightness)
        if (darken == 0) return null
        dark.color = android.graphics.Color.argb(darken * 255 / 100, 0, 0, 0)
        return dark
    }

    // a blur's picture as laid over the base at its size: asked of ThemeAssets once, not each key a frame;
    // asked again once the files were let go as changed (a theme imported again under its name, its
    // picture another: the theme itself equal, nothing made anew for it)
    private class Placed(
        val bitmap: Bitmap?, val w: Int, val h: Int, val scale: Float, val dx: Float, val dy: Float, val generation: Int,
    )

    private val placed = SparseArray<Placed>(2)
    private val baseAt = IntArray(2)
    private val viewAt = IntArray(2)
    private val matrix = Matrix()

    /**
     * [into]'s paint set to the picture blurred [blurDp] at [brightness] percent lying where [view] is
     * over the base, for drawing in [view]'s coordinates less ([left], [top]) (a drawable's bounds'
     * corner, the canvas moved there); null with no picture or none that reads, or while it is decoded
     * on another thread ([view] drawn again once it is in: not a frame waited for it).
     */
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

    // the blurs asked for and still being decoded ([decoding])
    private val decodingBlurs = HashSet<Int>()

    /**
     * Whether [paint] found nothing for [blurDp] only because the picture is still being decoded: what
     * asked is drawn again once it is in. False where nothing will come (no picture, none that reads,
     * the base not laid out): no point recording a view again each frame for it.
     */
    fun decoding(blurDp: Int): Boolean = blurDp in decodingBlurs

    // null while it is decoded on another thread
    private fun place(view: View, blurDp: Int, w: Int, h: Int): Placed? {
        // before it is asked for: let go while asked, it is asked again
        val generation = ThemeAssets.generation
        // not holding the view, nor this host (whose base is the input view's), till the decode is done:
        // a keyboard let go meanwhile (its theme changed, the keys' canvas given up) is not kept
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

    // where [view] is over the base, from where each is in their root view (its parents' places added
    // up): no window asked twice a key a frame. False under a scaled or turned parent, or for views of
    // two roots (asked of the window then)
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

    // not scaled or turned: its place is its parent's plus its own offset
    private fun View.plain() = scaleX == 1f && scaleY == 1f && rotation == 0f && rotationX == 0f && rotationY == 0f

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
        val host = view?.let { PictureHost.of(it) }
        if (host?.animated != null) {
            // see-through to the picture playing under it
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
        /**
         * Under [look]'s keyboard: its picture as cut for it, or the colour ([ThemeColors.board]); [animated]
         * the picture playing (A4), where it plays.
         */
        fun board(look: ThemeLook, animated: AnimatedBoardDrawable? = null): Drawable {
            val color = ThemeColors.board(look.palette, look.keys)
            val p = look.picture
            if (animated != null && p != null && look.base == BoardBase.Picture) return animated
            return if (p != null && look.base == BoardBase.Picture) {
                ThemePictureDrawable(p.blurDp, p.brightness, 0, color)
            } else {
                ColorDrawable(color)
            }
        }
    }
}
