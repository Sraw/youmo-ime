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
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import androidx.annotation.VisibleForTesting
import androidx.core.view.doOnPreDraw
import timber.log.Timber
import kotlin.math.roundToInt

/**
 * The keys under the mask blurred. RenderEffect is API 31's (minSdk 29) and blurs a view's own drawing,
 * not what is under it, so the keyboard's view is drawn once, [SCALE] times smaller, into a bitmap,
 * box-blurred and drawn back: the keys do not change while talking. Drawn in the card's parent's
 * coordinates, it lines up however the keyboard is moved or scaled.
 */
class VoiceGlass(color: Int, @get:VisibleForTesting internal val blur: Blur? = Blur.DEFAULT) : Drawable() {
    private var shot: Bitmap? = null
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val fill = Paint()

    // nothing taken: the mask opaque, the keys not seen through it plain
    private var failed = false

    var color: Int = color
        set(value) {
            field = value
            invalidateSelf()
        }

    fun under(card: View, board: ViewGroup) {
        if (blur == null) return
        card.doOnPreDraw { take(board, card) }
        card.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = Unit

            override fun onViewDetachedFromWindow(v: View) {
                shot?.recycle()
                shot = null
                // the card made anew for each talk; were it kept, not freed twice
                v.removeOnAttachStateChangeListener(this)
            }
        })
    }

    @VisibleForTesting
    internal fun take(board: ViewGroup, card: View) {
        val b = blur ?: return
        val start = SystemClock.elapsedRealtimeNanos()
        shot = capture(board, card, b)
        failed = shot == null
        Timber.d("voice glass %d×%d taken and blurred in %.2f ms", card.width, card.height, (SystemClock.elapsedRealtimeNanos() - start) / 1e6)
        invalidateSelf()
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        shot?.takeUnless { it.isRecycled }?.let {
            canvas.save()
            canvas.clipRect(b)
            canvas.translate(b.left.toFloat(), b.top.toFloat())
            val scale = (blur ?: Blur.DEFAULT).scale.toFloat()
            canvas.scale(scale, scale)
            canvas.drawBitmap(it, 0f, 0f, paint)
            canvas.restore()
        }
        fill.color = if (failed) color or OPAQUE else color
        canvas.drawRect(b, fill)
    }

    override fun setAlpha(alpha: Int) = Unit

    override fun setColorFilter(colorFilter: ColorFilter?) = Unit

    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT

    data class Blur(val scale: Int, val radius: Int) {
        companion object {
            /** 8 times smaller, 2 px (16 px of the screen's, whatever its density). */
            val DEFAULT = Blur(SCALE, RADIUS)

            /** A small blur is a little more than asked, never the keys taken whole; under half a pixel, none. */
            fun of(px: Double): Blur? {
                if (px < HALF) return null
                val scale = (px / MIN_RADIUS).toInt().coerceIn(MIN_SCALE, SCALE)
                return Blur(scale, maxOf(1, (px / scale).roundToInt()))
            }

            private const val MIN_RADIUS = 2
            private const val MIN_SCALE = 2
            private const val HALF = 0.5
        }
    }

    companion object {
        /** blurred anyway, and a few ms on the UI thread */
        const val SCALE = 8

        /** 3 passes: near a Gaussian */
        private const val RADIUS = 2
        private const val PASSES = 3

        /** the last part-cell counted whole, so it covers it all */
        @VisibleForTesting
        internal fun small(size: Int, scale: Int = SCALE) = (size + scale - 1) / scale

        private const val OPAQUE = 0xFF shl 24

        /** The card is taken before its first frame, at alpha 0, so left out; a mask still fading out is taken as seen. */
        @VisibleForTesting
        internal fun capture(board: ViewGroup, card: View, blur: Blur = Blur.DEFAULT): Bitmap? {
            val w = small(card.width, blur.scale)
            val h = small(card.height, blur.scale)
            if (w <= 0 || h <= 0) return null
            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.scale(1f / blur.scale, 1f / blur.scale)
            canvas.translate(-card.left.toFloat(), -card.top.toFloat())
            @Suppress("TooGenericExceptionCaught", "SwallowedException")
            try {
                board.draw(canvas)
            } catch (e: RuntimeException) {
                // a hardware bitmap somewhere under it (a software canvas cannot draw one): the plain mask
                bitmap.recycle()
                return null
            }
            val pixels = IntArray(w * h)
            bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
            repeat(PASSES) { boxBlur(pixels, w, h, blur.radius) }
            bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
            return bitmap
        }

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
            var alpha = 0
            var red = 0
            var green = 0
            var blue = 0
            fun at(i: Int) = line[i.coerceIn(0, n - 1)]
            for (i in -r..r) {
                val p = at(i)
                alpha += p ushr 24
                red += p shr 16 and 0xFF
                green += p shr 8 and 0xFF
                blue += p and 0xFF
            }
            for (i in 0 until n) {
                pixels[start + i * step] = (alpha / span shl 24) or (red / span shl 16) or (green / span shl 8) or (blue / span)
                val out = at(i - r)
                val come = at(i + r + 1)
                alpha += (come ushr 24) - (out ushr 24)
                red += (come shr 16 and 0xFF) - (out shr 16 and 0xFF)
                green += (come shr 8 and 0xFF) - (out shr 8 and 0xFF)
                blue += (come and 0xFF) - (out and 0xFF)
            }
        }
    }
}
