/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard.canvas

import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.view.View
import androidx.core.content.ContextCompat
import org.fcitx.fcitx5.android.data.theme.BoardImages
import org.fcitx.fcitx5.android.input.PictureHost
import org.fcitx.fcitx5.android.input.PicturePaint
import org.fcitx.fcitx5.android.input.scene.LookTheme
import org.fcitx.fcitx5.android.input.theme.Area
import org.fcitx.fcitx5.android.input.theme.Insets
import org.fcitx.fcitx5.android.input.theme.NineSlice
import org.fcitx.fcitx5.android.input.theme.NineSliceLayout
import org.fcitx.fcitx5.android.input.theme.ThemeLook
import org.fcitx.fcitx5.android.scene.Op
import org.fcitx.fcitx5.android.scene.Shape
import org.fcitx.fcitx5.android.scene.TextStroke
import java.lang.ref.WeakReference
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Draws a scene's ops ([Op]) on a canvas, in [view]'s coordinates: it computes nothing of where
 * anything goes (the engine did), only how Android draws each. The theme's files are [look]'s,
 * named as the theme written names them ([LookTheme.fileNames]); its key images [images]', the
 * picture [PictureHost]'s under [view].
 *
 * [missed] is set when something was not drawn as it will be: an image or the picture still being
 * decoded, which draws the view again once in; what was recorded then is to be recorded again. Not
 * for what will never come (no picture host, a file that does not decode): that would record every
 * frame again for nothing.
 */
class ScenePainter(
    private val view: View,
    private val look: ThemeLook,
    private val images: BoardImages,
    private val fonts: SceneFonts,
) {
    var missed = false

    // asked to be told when the images are in (BoardImages.whenReady): once
    private var waiting = false

    private val files: Map<String, String> = LookTheme.fileNames(look).entries.associate { (path, name) -> name to path }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val imagePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val picture = PicturePaint()
    private val path = Path()
    private val rect = RectF()
    private val src = Rect()

    // an icon's drawable by what it is drawn as, not where: one a size, drawn moved to its place
    // [pixelWidth], [pixelHeight]: the size it is drawn at on the device, the canvas's scale applied (a settings page's
    // smaller preview, a key's own): a vector keeps the raster of its first draw, which another scale would stretch
    private data class IconKey(
        val name: String,
        val tint: Int,
        val width: Int,
        val height: Int,
        val pixelWidth: Int,
        val pixelHeight: Int,
    )

    fun draw(canvas: Canvas, ops: List<Op>) = ops.forEach { draw(canvas, it) }

    private fun draw(canvas: Canvas, op: Op) {
        when (op) {
            is Op.Fill -> fill(canvas, op.shape, op.color.value)
            is Op.Ripple -> fill(canvas, op.shape, op.color.value)
            is Op.Shadow -> {
                if (op.blur > 0) paint.maskFilter = BlurMaskFilter(op.blur.toFloat(), BlurMaskFilter.Blur.NORMAL)
                fill(canvas, op.shape, op.color.value)
                paint.maskFilter = null
            }
            is Op.InnerShadow -> innerShadow(canvas, op)
            is Op.Stroke -> {
                plain(op.color.value)
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = op.width.toFloat()
                canvas.drawPath(pathOf(op.shape), paint)
                paint.style = Paint.Style.FILL
            }
            is Op.Gradient -> gradient(canvas, op)
            is Op.NineSlice -> nineSlice(canvas, op)
            is Op.Picture -> picture(canvas, op)
            is Op.Text -> text(canvas, op)
            is Op.Icon -> icon(canvas, op)
        }
    }

    private fun plain(color: Int) {
        paint.reset()
        paint.isAntiAlias = true
        paint.color = color
    }

    private fun fill(canvas: Canvas, shape: Shape, color: Int) {
        val mask = paint.maskFilter
        plain(color)
        paint.maskFilter = mask
        canvas.drawPath(pathOf(shape), paint)
    }

    // inside the face, its ring less its hole filled even-odd and blurred (BlurMaskFilter draws on a hardware
    // canvas since API 28): the face clipped first, so the ring's path may be built in the one path after
    private fun innerShadow(canvas: Canvas, op: Op.InnerShadow) {
        canvas.save()
        canvas.clipPath(pathOf(op.shape))
        path.rewind()
        path.fillType = Path.FillType.EVEN_ODD
        val r = op.ring
        path.addRect(r.left.toFloat(), r.top.toFloat(), r.right.toFloat(), r.bottom.toFloat(), Path.Direction.CW)
        add(path, op.hole)
        plain(op.color.value)
        if (op.blur > 0) paint.maskFilter = BlurMaskFilter(op.blur.toFloat(), BlurMaskFilter.Blur.NORMAL)
        canvas.drawPath(path, paint)
        paint.maskFilter = null
        path.fillType = Path.FillType.WINDING
        canvas.restore()
    }

    private fun pathOf(shape: Shape): Path {
        path.rewind()
        add(path, shape)
        return path
    }

    private fun add(path: Path, shape: Shape) {
        when (shape) {
            is Shape.Rect -> {
                val b = shape.box
                rect.set(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat())
                val r = shape.radii
                if (r.all { it <= 0.0 }) {
                    path.addRect(rect, Path.Direction.CW)
                } else {
                    val radii = FloatArray(RADII) { r[it / 2].toFloat() }
                    path.addRoundRect(rect, radii, Path.Direction.CW)
                }
            }
            is Shape.Path -> shape.ops.forEach {
                when (it) {
                    is Shape.Path.Op.MoveTo -> path.moveTo(it.x.toFloat(), it.y.toFloat())
                    is Shape.Path.Op.LineTo -> path.lineTo(it.x.toFloat(), it.y.toFloat())
                    Shape.Path.Op.Close -> path.close()
                }
            }
        }
    }

    // CSS's: 0deg up, 90deg right, the line long enough for the corners to reach the ends' colours
    private fun gradient(canvas: Canvas, op: Op.Gradient) {
        val b = op.shape.bounds
        val a = Math.toRadians(op.angle)
        val dx = sin(a)
        val dy = -cos(a)
        val half = abs(b.width / 2 * dx) + abs(b.height / 2 * dy)
        val cx = (b.left + b.right) / 2
        val cy = (b.top + b.bottom) / 2
        plain(0)
        paint.shader = LinearGradient(
            (cx - dx * half).toFloat(), (cy - dy * half).toFloat(), (cx + dx * half).toFloat(), (cy + dy * half).toFloat(),
            op.stops.map { it.first.value }.toIntArray(), op.stops.map { it.second.toFloat() }.toFloatArray(), Shader.TileMode.CLAMP,
        )
        canvas.drawPath(pathOf(op.shape), paint)
        paint.shader = null
    }

    private fun nineSlice(canvas: Canvas, op: Op.NineSlice) {
        val file = files[op.file] ?: op.file
        val bitmap = images.bitmap(file)
        val size = images.size(file)
        if (bitmap == null || size == null) {
            // the colour theme 3.0 showed there till the image is in, or where none reads
            op.fallback?.let { fill(canvas, op.shape, it.value) }
            // drawn again once it is in
            if (!images.ready) {
                missed = true
                // once, and not holding the view: one the keyboard let go (its keys drawn by their views
                // again, the theme changed) is not kept till the images are in
                if (!waiting) {
                    waiting = true
                    val drawn = WeakReference(view)
                    // this painter not held either (it holds the view): ready once, ready for good, never asked again
                    images.whenReady { drawn.get()?.invalidate() }
                }
            }
            return
        }
        val b = op.shape.bounds
        val i = op.insets
        val slice = NineSlice(file, Insets(i[0], i[1], i[2], i[3]), op.pxPerDp)
        val pieces = NineSliceLayout.of(
            size[0], size[1], slice,
            Area(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat()), view.resources.displayMetrics.density,
        )
        // the insets are in the file's pixels; it may have been decoded smaller (KeyImageBudget)
        val sx = bitmap.width.toFloat() / size[0]
        val sy = bitmap.height.toFloat() / size[1]
        canvas.save()
        canvas.clipPath(pathOf(op.shape))
        for (p in pieces) {
            src.set((p.src.left * sx).toInt(), (p.src.top * sy).toInt(), (p.src.right * sx).toInt(), (p.src.bottom * sy).toInt())
            rect.set(p.dst.left, p.dst.top, p.dst.right, p.dst.bottom)
            canvas.drawBitmap(bitmap, src, rect, imagePaint)
        }
        canvas.restore()
    }

    // the theme's picture where the shape is over the keyboard's base, as the old keys' faces showed it
    private fun picture(canvas: Canvas, op: Op.Picture) {
        pictureItself(canvas, op)
        // the colour over it, there with the picture or without (theme 3.0's key face laid its colour on
        // the picture, and the colour alone where there was none to show)
        op.over?.let { fill(canvas, op.shape, it.value) }
    }

    private fun pictureItself(canvas: Canvas, op: Op.Picture) {
        val p = look.picture ?: return
        val blur = op.blurDp?.roundToInt() ?: p.blurDp
        val brightness = op.brightness?.roundToInt() ?: p.brightness
        val host = PictureHost.of(view) ?: return
        val paint = host.paint(view, blur, brightness, picture)
        if (paint == null) {
            // drawn as nothing till it is in (the host draws the view again then); none that reads, nothing
            if (host.decoding(blur)) missed = true
            return
        }
        canvas.drawPath(pathOf(op.shape), paint)
    }

    // a theme's text-shadow under the glyphs (the outline's, where there is one), then the outline, then the
    // fill over it: the stroke's paint twice its width, half of it under the fill, so its width shows outside.
    // The shadow is the glyphs again, moved, in its colour, blurred as a key's shadow is (BlurMaskFilter's
    // radius, setShadowLayer's too): drawn so, not by setShadowLayer, it is there under a transparent fill as
    // CSS has it (setShadowLayer's of an opaque colour takes the fill's alpha) and sharp where it has no blur (setShadowLayer's draws none)
    private fun text(canvas: Canvas, op: Op.Text) {
        val x = op.x.toFloat()
        val y = op.baseline.toFloat()
        if (op.rotation != 0.0) {
            canvas.save()
            canvas.rotate(op.rotation.toFloat(), x, y)
        }
        op.shadow?.let { shadow ->
            textPaint(op, shadow.color.value, op.stroke)
            if (shadow.blur > 0.0) paint.maskFilter = BlurMaskFilter(shadow.blur.toFloat(), BlurMaskFilter.Blur.NORMAL)
            canvas.drawText(op.text, x + shadow.dx.toFloat(), y + shadow.dy.toFloat(), paint)
            // what comes next starts from a paint of none (fill keeps a shadow's mask: Op.Shadow's)
            paint.maskFilter = null
        }
        op.stroke?.let { stroke ->
            textPaint(op, stroke.color.value, stroke)
            canvas.drawText(op.text, x, y, paint)
        }
        if (!op.color.transparent) {
            textPaint(op, op.color.value, null)
            canvas.drawText(op.text, x, y, paint)
        }
        if (op.rotation != 0.0) canvas.restore()
    }

    private fun textPaint(op: Op.Text, color: Int, stroke: TextStroke?) {
        plain(color)
        paint.typeface = fonts.typeface(op.font, op.weight)
        paint.textSize = op.size.toFloat()
        paint.textScaleX = op.scaleX.toFloat()
        paint.letterSpacing = op.letterSpacing.toFloat()
        if (stroke != null) {
            paint.style = Paint.Style.FILL_AND_STROKE
            paint.strokeWidth = (2 * stroke.width).toFloat()
            paint.strokeJoin = Paint.Join.ROUND
        }
    }

    private val scale = Matrix()
    private val scaleValues = FloatArray(9)

    private fun icon(canvas: Canvas, op: Op.Icon) {
        // one drawable a name, tint and size, its bounds at 0, 0 and the canvas moved to the place: a
        // vector's tint and bounds are the drawable's own, which a display list recorded with it reads
        // when drawn, not when recorded, so two sizes must be two drawables; places need not be
        val b = op.box
        val left = b.left.roundToInt()
        val top = b.top.roundToInt()
        val w = b.right.roundToInt() - left
        val h = b.bottom.roundToInt() - top
        val config = view.resources.configuration
        if (config.uiMode != iconsUiMode || config.densityDpi != iconsDensity || config.fontScale != iconsFontScale) {
            icons.clear()
            iconsUiMode = config.uiMode
            iconsDensity = config.densityDpi
            iconsFontScale = config.fontScale
        }
        @Suppress("DEPRECATION") // the matrix the canvas draws with so far: its scale is what the raster is made at
        canvas.getMatrix(scale)
        scale.getValues(scaleValues)
        // a scale not finite (a matrix gone NaN) taken as 1: roundToInt throws on NaN, and this is the draw
        val pw = (w * scaleOf(scaleValues[Matrix.MSCALE_X])).roundToInt()
        val ph = (h * scaleOf(scaleValues[Matrix.MSCALE_Y])).roundToInt()
        val d = icons.getOrPut(IconKey(op.name, op.tint.value, w, h, pw, ph)) {
            SceneIcons.of(op.name)?.let { ContextCompat.getDrawable(view.context, it)?.mutate() }?.also {
                it.setTint(op.tint.value)
                it.setBounds(0, 0, w, h)
            } ?: NO_ICON
        }.takeIf { it !== NO_ICON } ?: return
        canvas.save()
        canvas.translate(left.toFloat(), top.toFloat())
        if (op.rotation != 0.0) canvas.rotate(op.rotation.toFloat(), w / 2f, h / 2f)
        d.draw(canvas)
        canvas.restore()
    }

    internal companion object {
        /** A canvas's scale as an icon's raster size reads it: its size, 1 when not finite. */
        fun scaleOf(v: Float): Float = abs(v).takeIf { it.isFinite() } ?: 1f

        const val RADII = 8
        private const val KEPT_ICONS = 64

        // the icons drawn, every painter's (main thread), the last KEPT_ICONS used: a keyboard made again (a
        // theme switched) inflates none again; let go when what inflating one reads changes (night mode,
        // density, font scale), not for the rest of the configuration (turning the screen keeps them)
        private val icons = object : LinkedHashMap<IconKey, Drawable>(KEPT_ICONS, LOAD, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<IconKey, Drawable>?) = size > KEPT_ICONS
        }

        // kept for a name with no icon (none in SceneIcons, or none inflated): not looked for again each frame
        private val NO_ICON: Drawable = ColorDrawable()
        private const val LOAD = 0.75f
        private var iconsUiMode = 0
        private var iconsDensity = 0
        private var iconsFontScale = Float.NaN
    }
}
