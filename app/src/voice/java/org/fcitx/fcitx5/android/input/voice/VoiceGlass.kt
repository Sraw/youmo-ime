/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import androidx.annotation.VisibleForTesting
import androidx.core.view.doOnPreDraw

/**
 * The mask's frosted glass (the user's decision 2026-11-07): the keys under it blurred, only their
 * shapes and colours seen, [color] over them, so what the mask says reads clearly. RenderEffect is
 * API 31's and minSdk is 29, so the keyboard's view is drawn once, [SCALE] times smaller, into a
 * bitmap, box-blurred and drawn back as large: the keys do not change while talking. Drawn in the
 * card's own parent's coordinates, it lines up with the keys however the keyboard is moved,
 * scaled or rounded (floating, one-handed, sideways, a picture theme): those are the parent's.
 */
class VoiceGlass(color: Int) : Drawable() {
    private var shot: Bitmap? = null
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val fill = Paint().apply { this.color = color }

    var color: Int
        get() = fill.color
        set(value) {
            fill.color = value
            invalidateSelf()
        }

    /** Once [card], a child of [board], is laid out: what is under it taken; freed as it goes. */
    fun under(card: View, board: ViewGroup) {
        card.doOnPreDraw { shot = capture(board, card)?.also { invalidateSelf() } }
        card.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = Unit

            override fun onViewDetachedFromWindow(v: View) {
                shot?.recycle()
                shot = null
            }
        })
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        shot?.takeUnless { it.isRecycled }?.let {
            canvas.save()
            canvas.clipRect(b)
            canvas.translate(b.left.toFloat(), b.top.toFloat())
            canvas.scale(SCALE.toFloat(), SCALE.toFloat())
            canvas.drawBitmap(it, 0f, 0f, paint)
            canvas.restore()
        }
        canvas.drawRect(b, fill)
    }

    override fun setAlpha(alpha: Int) = Unit

    override fun setColorFilter(colorFilter: ColorFilter?) = Unit

    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT

    companion object {
        /** how much smaller the keys are taken: blurred anyway, and a few ms on the UI thread */
        const val SCALE = 8

        /** the blur's radius in the small picture's pixels, and how many times it runs (3: near a Gaussian) */
        private const val RADIUS = 2
        private const val PASSES = 3

        /** [size] pixels taken [SCALE] times smaller: the last part-cell counted whole, so it covers it all */
        @VisibleForTesting
        internal fun small(size: Int) = (size + SCALE - 1) / SCALE

        /** Where [board]'s point ([x], [y]) is in the small picture of [card]: its own place taken away, then scaled. */
        @VisibleForTesting
        internal fun toSmall(card: Rect, x: Float, y: Float) = floatArrayOf((x - card.left) / SCALE, (y - card.top) / SCALE)

        /** [board] under [card] (the card itself left out), small and blurred; null where it cannot be drawn. */
        @VisibleForTesting
        internal fun capture(board: ViewGroup, card: View): Bitmap? {
            val w = small(card.width)
            val h = small(card.height)
            if (w <= 0 || h <= 0) return null
            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.scale(1f / SCALE, 1f / SCALE)
            canvas.translate(-card.left.toFloat(), -card.top.toFloat())
            @Suppress("TooGenericExceptionCaught", "SwallowedException")
            try {
                board.background?.draw(canvas)
                for (i in 0 until board.childCount) {
                    val child = board.getChildAt(i)
                    if (child === card || child.visibility != View.VISIBLE) continue
                    canvas.save()
                    canvas.translate(child.left.toFloat(), child.top.toFloat())
                    canvas.concat(child.matrix)
                    child.draw(canvas)
                    canvas.restore()
                }
            } catch (e: RuntimeException) {
                // a hardware bitmap somewhere under it (a software canvas cannot draw one): the plain mask
                bitmap.recycle()
                return null
            }
            val pixels = IntArray(w * h)
            bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
            repeat(PASSES) { boxBlur(pixels, w, h, RADIUS) }
            bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
            return bitmap
        }

        /** [pixels] ([w] × [h], ARGB) box-blurred in place, across then down, the edges held. */
        @VisibleForTesting
        internal fun boxBlur(pixels: IntArray, w: Int, h: Int, radius: Int) {
            val line = IntArray(maxOf(w, h))
            for (y in 0 until h) blurLine(pixels, y * w, 1, w, radius, line)
            for (x in 0 until w) blurLine(pixels, x, w, h, radius, line)
        }

        // one row or column: a running sum over 2r+1 pixels, each channel on its own
        @Suppress("LongParameterList")
        private fun blurLine(pixels: IntArray, start: Int, step: Int, n: Int, r: Int, line: IntArray) {
            for (i in 0 until n) line[i] = pixels[start + i * step]
            val span = 2 * r + 1
            var a = 0
            var red = 0
            var g = 0
            var b = 0
            fun at(i: Int) = line[i.coerceIn(0, n - 1)]
            for (i in -r..r) {
                val p = at(i)
                a += p ushr 24
                red += p shr 16 and 0xFF
                g += p shr 8 and 0xFF
                b += p and 0xFF
            }
            for (i in 0 until n) {
                pixels[start + i * step] = (a / span shl 24) or (red / span shl 16) or (g / span shl 8) or (b / span)
                val out = at(i - r)
                val come = at(i + r + 1)
                a += (come ushr 24) - (out ushr 24)
                red += (come shr 16 and 0xFF) - (out shr 16 and 0xFF)
                g += (come shr 8 and 0xFF) - (out shr 8 and 0xFF)
                b += (come and 0xFF) - (out and 0xFF)
            }
        }
    }
}
