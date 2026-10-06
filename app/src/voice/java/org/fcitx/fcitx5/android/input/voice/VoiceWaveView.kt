/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.view.View
import kotlin.math.PI
import kotlin.math.sin

/**
 * Bars that rise and fall with how loud the microphone hears it ([level]), each a little out of
 * step with the next, as WeChat's and Sogou's keyboards show listening; while not [active], a slow
 * low swell, there but still. Drawn a frame at a time while shown, nothing otherwise.
 */
class VoiceWaveView(context: Context) : View(context) {

    var color: Int
        get() = paint.color
        set(value) {
            paint.color = value
            invalidate()
        }

    /** hearing, the bars follow [level]; else they settle and rest, not redrawn */
    var active = false
        set(value) {
            field = value
            if (value) postInvalidateOnAnimation()
        }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    // the level heard, eased towards so the bars move rather than jump
    private var target = 0f
    private var shown = 0f

    // the middle bars the tallest
    private val shape = FloatArray(BARS) { i -> 1f - 0.55f * kotlin.math.abs(i - (BARS - 1) / 2f) / ((BARS - 1) / 2f) }

    /** how loud, 0 to 1 */
    fun level(level: Float) {
        target = level.coerceIn(0f, 1f)
    }

    override fun onDraw(canvas: Canvas) {
        val t = SystemClock.uptimeMillis() / 1000f
        shown += ((if (active) target else 0f) - shown) * EASE
        val bar = width / (BARS * 2f - 1f)
        val mid = height / 2f
        for (i in 0 until BARS) {
            // each bar its own phase: a wave running across, faster and larger the louder
            val wave = (sin(2 * PI * (t * (SWELL_HZ + shown * TALK_HZ) - i * PHASE)).toFloat() + 1f) / 2f
            val rest = REST * (0.6f + 0.4f * wave)
            val h = (rest + shape[i] * shown * (0.55f + 0.45f * wave)).coerceAtMost(1f) * height
            val x = i * bar * 2f
            rect.set(x, mid - maxOf(h, bar) / 2f, x + bar, mid + maxOf(h, bar) / 2f)
            canvas.drawRoundRect(rect, bar / 2f, bar / 2f, paint)
        }
        // at rest, nothing moves: no frame drawn for nothing while the panel sits paused
        if (isShown && (active || shown > SETTLED)) postInvalidateOnAnimation()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility == VISIBLE) postInvalidateOnAnimation()
    }

    companion object {
        private const val BARS = 7
        private const val EASE = 0.25f
        private const val REST = 0.12f
        private const val SWELL_HZ = 0.6f
        private const val TALK_HZ = 2.4f
        private const val PHASE = 0.13f
        private const val SETTLED = 0.005f
    }
}
