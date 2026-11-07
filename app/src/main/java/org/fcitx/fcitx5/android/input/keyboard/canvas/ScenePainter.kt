/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard.canvas

import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
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
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Draws a scene's ops ([Op]) on a canvas, in [view]'s coordinates: it computes nothing of where
 * anything goes (the engine did), only how Android draws each. The theme's files are [look]'s,
 * named by their names alone ([LookTheme.fileName]); its key images [images]', the picture
 * [PictureHost]'s under [view].
 *
 * [missed] is set when something was not drawn as it will be (an image or the picture still being
 * decoded): what was recorded then is to be recorded again.
 */
class ScenePainter(
    private val view: View,
    private val look: ThemeLook,
    private val images: BoardImages,
    private val fonts: SceneFonts,
) {
    var missed = false

    private val files: Map<String, String> = look.files().associateBy(LookTheme::fileName)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val imagePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val picture = PicturePaint()
    private val path = Path()
    private val rect = RectF()
    private val src = Rect()
    private val icons = HashMap<Op.Icon, Drawable?>()

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

    private fun pathOf(shape: Shape): Path {
        path.rewind()
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
        return path
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
            // drawn as nothing till it is in, then again
            if (!images.ready) {
                missed = true
                images.whenReady { view.invalidate() }
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
        val p = look.picture ?: return
        val blur = op.blurDp?.roundToInt() ?: p.blurDp
        val brightness = op.brightness?.roundToInt() ?: p.brightness
        val paint = PictureHost.of(view)?.paint(view, blur, brightness, picture)
        if (paint == null) {
            missed = true
            return
        }
        canvas.drawPath(pathOf(op.shape), paint)
    }

    private fun text(canvas: Canvas, op: Op.Text) {
        plain(op.color.value)
        paint.typeface = fonts.typeface(op.font, op.weight)
        paint.textSize = op.size.toFloat()
        paint.textScaleX = op.scaleX.toFloat()
        val x = op.x.toFloat()
        val y = op.baseline.toFloat()
        if (op.rotation != 0.0) {
            canvas.save()
            canvas.rotate(op.rotation.toFloat(), x, y)
        }
        canvas.drawText(op.text, x, y, paint)
        if (op.rotation != 0.0) canvas.restore()
    }

    private fun icon(canvas: Canvas, op: Op.Icon) {
        // one drawable a place and tint: a vector's tint and bounds are the drawable's own, which a
        // display list recorded with it reads when drawn, not when recorded
        val d = icons.getOrPut(op) {
            SceneIcons.of(op.name)?.let { ContextCompat.getDrawable(view.context, it)?.mutate() }?.also {
                it.setTint(op.tint.value)
                val b = op.box
                it.setBounds(b.left.roundToInt(), b.top.roundToInt(), b.right.roundToInt(), b.bottom.roundToInt())
            }
        } ?: return
        val b = op.box
        if (op.rotation != 0.0) {
            canvas.save()
            canvas.rotate(op.rotation.toFloat(), ((b.left + b.right) / 2).toFloat(), ((b.top + b.bottom) / 2).toFloat())
        }
        d.draw(canvas)
        if (op.rotation != 0.0) canvas.restore()
    }

    private companion object {
        const val RADII = 8
    }
}
