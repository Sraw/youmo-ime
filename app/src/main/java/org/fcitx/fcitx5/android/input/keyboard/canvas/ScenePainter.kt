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
import org.fcitx.fcitx5.android.input.theme.Area
import org.fcitx.fcitx5.android.input.theme.NineSliceLayout
import org.fcitx.fcitx5.android.scene.Op
import org.fcitx.fcitx5.android.scene.Shape
import org.fcitx.fcitx5.android.scene.TextStroke
import org.fcitx.fcitx5.android.scene.look.Insets
import org.fcitx.fcitx5.android.scene.look.LookTheme
import org.fcitx.fcitx5.android.scene.look.NineSlice
import org.fcitx.fcitx5.android.scene.look.ThemeLook
import java.lang.ref.WeakReference
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Draws a scene's ops on a canvas: it computes nothing of where anything goes, only how Android draws
 * each. [missed] is set when an image or the picture is still being decoded (the view drawn again once
 * in, to be recorded again); not for what will never come, which would record every frame for nothing.
 */
class ScenePainter(
    private val view: View,
    private val look: ThemeLook,
    private val images: BoardImages,
    private val fonts: SceneFonts,
    private val packIcons: PackIcons = PackIcons.EMPTY,
) {

    // one reference a painter, not one an icon a frame; not holding the view: the icon cache outlives it
    private val redraw: () -> Unit = WeakReference(view).let { drawn -> { drawn.get()?.invalidate() } }
    var missed = false

    // once
    private var waiting = false

    private val files: Map<String, String> = LookTheme.fileNames(look).entries.associate { (path, name) -> name to path }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val imagePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val picture = PicturePaint()
    private val path = Path()
    private val other = Path()
    private val rect = RectF()
    private val src = Rect()

    // one a size, drawn moved to its place: a vector keeps the raster of its first draw, which another scale would
    // stretch. [source]: two themes' icons of one name are two
    private data class IconKey(
        val name: String,
        val source: String?,
        val tint: Int,
        val width: Int,
        val height: Int,
        val pixelWidth: Int,
        val pixelHeight: Int,
    )

    fun draw(canvas: Canvas, ops: List<Op>) = ops.forEach { draw(canvas, it) }

    private fun draw(canvas: Canvas, op: Op) {
        when (op) {
            is Op.Fill -> {
                val less = op.less
                if (less == null) fill(canvas, op.shape, op.color.value) else fillLess(canvas, op.shape, less, op.color.value)
            }
            is Op.Ripple -> fill(canvas, op.shape, op.color.value)
            is Op.Shadow -> {
                paint.maskFilter = blur(op.blur)
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
            is Op.Sticker -> sticker(canvas, op)
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

    // each filled by winding, so what is left is what shows however either crosses itself
    private fun fillLess(canvas: Canvas, shape: Shape, less: Shape, color: Int) {
        plain(color)
        path.rewind()
        add(path, shape)
        other.rewind()
        add(other, less)
        path.op(other, Path.Op.DIFFERENCE)
        canvas.drawPath(path, paint)
    }

    // BlurMaskFilter draws on a hardware canvas since API 28. The face clipped first, the hole taken out by Path.op
    // filled by winding, so a path crossing itself has as much inside its clip as inside its hole
    private fun innerShadow(canvas: Canvas, op: Op.InnerShadow) {
        canvas.save()
        canvas.clipPath(pathOf(op.shape))
        path.rewind()
        val r = op.ring
        path.addRect(r.left.toFloat(), r.top.toFloat(), r.right.toFloat(), r.bottom.toFloat(), Path.Direction.CW)
        other.rewind()
        add(other, op.hole)
        path.op(other, Path.Op.DIFFERENCE)
        plain(op.color.value)
        paint.maskFilter = blur(op.blur)
        canvas.drawPath(path, paint)
        paint.maskFilter = null
        canvas.restore()
    }

    // one filter a radius: a popup's scene is drawn straight to its view, a new one every frame
    private fun blur(radius: Double): BlurMaskFilter? = if (radius > 0) {
        blurs.getOrPut(radius.toFloat()) { BlurMaskFilter(radius.toFloat(), BlurMaskFilter.Blur.NORMAL) }
    } else {
        null
    }

    private val blurs = lruMap<Float, BlurMaskFilter>(KEPT_BLURS)

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
        // a paint's alpha scales its shader's colours, and plain(0)'s none drew nothing (ScenePainterGradientTest)
        plain(OPAQUE)
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
            // till the image is in, or where none reads
            op.fallback?.let { fill(canvas, op.shape, it.value) }
            if (!images.ready) {
                missed = true
                // not holding the view: one the keyboard let go is not kept till the images are in
                if (!waiting) {
                    waiting = true
                    val drawn = WeakReference(view)
                    // this painter not held either (it holds the view): ready once, ready for good
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

    private fun picture(canvas: Canvas, op: Op.Picture) {
        pictureItself(canvas, op)
        // with the picture or without (theme 3.0's key face laid its colour on it)
        op.over?.let { fill(canvas, op.shape, it.value) }
    }

    private fun pictureItself(canvas: Canvas, op: Op.Picture) {
        val p = look.picture ?: return
        val blur = op.blurDp?.roundToInt() ?: p.blurDp
        val brightness = op.brightness?.roundToInt() ?: p.brightness
        val host = PictureHost.of(view) ?: return
        if (host.animated != null) {
            // darkened alone: no frame blurred
            host.seeThrough(brightness)?.let { canvas.drawPath(pathOf(op.shape), it) }
            return
        }
        val paint = host.paint(view, blur, brightness, picture)
        if (paint == null) {
            // none that reads, nothing
            if (host.decoding(blur)) missed = true
            return
        }
        canvas.drawPath(pathOf(op.shape), paint)
    }

    // shadow, outline, then fill: the stroke twice its width, half under the fill. The shadow is the glyphs again,
    // moved and blurred (BlurMaskFilter), not setShadowLayer: so it is there under a transparent fill as CSS has
    // it (setShadowLayer's takes the fill's alpha) and sharp with no blur (setShadowLayer's draws none)
    private fun text(canvas: Canvas, op: Op.Text) {
        val x = op.x.toFloat()
        val y = op.baseline.toFloat()
        if (op.rotation != 0.0) {
            canvas.save()
            canvas.rotate(op.rotation.toFloat(), x, y)
        }
        op.shadow?.let { shadow ->
            textPaint(op, shadow.color.value, op.stroke)
            paint.maskFilter = blur(shadow.blur)
            canvas.drawText(op.text, x + shadow.dx.toFloat(), y + shadow.dy.toFloat(), paint)
            // fill keeps a shadow's mask (Op.Shadow's)
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
        // a vector's tint and bounds are the drawable's own, which a display list reads when drawn, not when
        // recorded: two sizes must be two drawables; places need not be
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
        pixels(canvas, w, h)
        val pw = pixels[0]
        val ph = pixels[1]
        // read on the decode thread, the app's of that name drawn till it is in, or for good where its file is broken
        val file = packIcons.file(op.name)
        val own = file?.let { f ->
            packIcons.icon(f, pw, ph, redraw).also { if ((it == null || it.lent()) && packIcons.pending(f)) missed = true }
        }
        val source = file.takeIf { own != null }
        // one lent till its own size is in is drawn once, not kept for the key
        val d = if (own.lent()) PackIconDrawable(own!!, op.tint, pw, ph, op.tintMask).apply { setBounds(0, 0, w, h) }
        else icons.getOrPut(IconKey(op.name, source, op.tint.value, w, h, pw, ph)) {
            own?.let { PackIconDrawable(it, op.tint, pw, ph, op.tintMask).apply { setBounds(0, 0, w, h) } }
                ?: SceneIcons.of(op.name)?.let { ContextCompat.getDrawable(view.context, it)?.mutate() }?.also {
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

    private val pixels = IntArray(2)

    // the matrix the canvas draws with so far: its scale is what a raster is made at
    private fun pixels(canvas: Canvas, w: Int, h: Int) {
        @Suppress("DEPRECATION")
        canvas.getMatrix(scale)
        scale.getValues(scaleValues)
        // a matrix gone NaN taken as 1: roundToInt throws on NaN, and this is the draw
        pixels[0] = (w * scaleOf(scaleValues[Matrix.MSCALE_X])).roundToInt()
        pixels[1] = (h * scaleOf(scaleValues[Matrix.MSCALE_Y])).roundToInt()
    }

    // decoded on the decode thread at the size drawn; the label and icon it hides are drawn till then, or for good where it never reads
    private fun sticker(canvas: Canvas, op: Op.Sticker) {
        val b = op.box
        val w = b.right.roundToInt() - b.left.roundToInt()
        val h = b.bottom.roundToInt() - b.top.roundToInt()
        pixels(canvas, w, h)
        val raster = packIcons.sticker(op.file, pixels[0], pixels[1], redraw)
        // none yet or one lent, its own decoding
        if (raster?.exact != true && packIcons.stickerPending(op.file)) missed = true
        if (raster == null) {
            draw(canvas, op.standIn)
            return
        }
        val sw = raster.width * w.toFloat() / pixels[0]
        val sh = raster.height * h.toFloat() / pixels[1]
        val cx = (b.left.roundToInt() + b.right.roundToInt()) / 2f
        val cy = (b.top.roundToInt() + b.bottom.roundToInt()) / 2f
        rect.set(cx - sw / 2, cy - sh / 2, cx + sw / 2, cy + sh / 2)
        imagePaint.alpha = (op.alpha * FULL_ALPHA).roundToInt().coerceIn(0, FULL_ALPHA)
        canvas.drawBitmap(raster.bitmap, null, rect, imagePaint)
        imagePaint.alpha = FULL_ALPHA
    }

    private fun PackIcons.PackIcon?.lent() = this is PackIcons.Raster && !exact

    internal companion object {
        fun scaleOf(v: Float): Float = abs(v).takeIf { it.isFinite() } ?: 1f

        const val RADII = 8
        private const val KEPT_ICONS = 64
        private const val KEPT_BLURS = 16
        private const val OPAQUE = 0xFF000000.toInt()
        private const val FULL_ALPHA = 255

        // every painter's (main thread): let go when what inflating reads changes (night mode, density, font
        // scale), not for the rest of the configuration
        private val icons = lruMap<IconKey, Drawable>(KEPT_ICONS)

        fun forgetIcons() {
            icons.clear()
            PackIcons.forget()
        }

        // not looked for again each frame
        private val NO_ICON: Drawable = ColorDrawable()
        private var iconsUiMode = 0
        private var iconsDensity = 0
        private var iconsFontScale = Float.NaN
    }
}
