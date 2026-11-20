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
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sin

/** Drawn a frame at a time while shown, nothing otherwise. */
class VoiceWaveView(context: Context) : View(context) {

    var color: Int
        get() = paint.color
        set(value) {
            paint.color = value
            invalidate()
        }

    var active = false
        set(value) {
            field = value
            if (value) postInvalidateOnAnimation()
        }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    private var target = 0f
    private var shown = 0f
    // the easing by the time gone, not the frame (a 120 Hz screen's twice as many)
    private var lastFrame = 0L

    private val shape = FloatArray(BARS) { i -> 1f - 0.55f * abs(i - (BARS - 1) / 2f) / ((BARS - 1) / 2f) }

    fun level(level: Float) {
        target = level.coerceIn(0f, 1f)
    }

    override fun onDraw(canvas: Canvas) {
        val now = SystemClock.uptimeMillis()
        val t = now / 1000f
        val frames = if (lastFrame == 0L) 1f else ((now - lastFrame) / FRAME_MS).coerceIn(0f, MAX_FRAMES)
        lastFrame = now
        shown += ((if (active) target else 0f) - shown) * (1f - (1f - EASE).pow(frames))
        val bar = width / (BARS * 2f - 1f)
        val mid = height / 2f
        for (i in 0 until BARS) {
            val wave = (sin(2 * PI * (t * (SWELL_HZ + shown * TALK_HZ) - i * PHASE)).toFloat() + 1f) / 2f
            val rest = REST * (0.6f + 0.4f * wave)
            val h = (rest + shape[i] * shown * (0.55f + 0.45f * wave)).coerceAtMost(1f) * height
            val x = i * bar * 2f
            rect.set(x, mid - maxOf(h, bar) / 2f, x + bar, mid + maxOf(h, bar) / 2f)
            canvas.drawRoundRect(rect, bar / 2f, bar / 2f, paint)
        }
        // at rest, no frame drawn for nothing
        if (isShown && (active || shown > SETTLED)) postInvalidateOnAnimation()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility == VISIBLE) postInvalidateOnAnimation()
    }

    companion object {
        private const val BARS = 7
        // per 60 Hz frame
        private const val EASE = 0.25f
        private const val FRAME_MS = 1000f / 60
        private const val MAX_FRAMES = 10f
        private const val REST = 0.12f
        private const val SWELL_HZ = 0.6f
        private const val TALK_HZ = 2.4f
        private const val PHASE = 0.13f
        private const val SETTLED = 0.005f
    }
}
