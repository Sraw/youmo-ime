/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.SystemClock
import android.view.View

/**
 * Rings going out from a round button while it listens, wider the louder it hears ([level]), as
 * a ripple on water; they fade out once it stops, to a soft halo. [inner] is the button's radius: they start at
 * its edge. Drawn a frame at a time while there is something to draw, nothing otherwise.
 */
class VoicePulseView(context: Context) : View(context) {

    var color: Int
        get() = paint.color
        set(value) {
            paint.color = value
            invalidate()
        }

    /** the button's radius, in pixels */
    var inner = 0f

    var active = false
        set(value) {
            field = value
            if (value) postInvalidateOnAnimation()
        }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    private var target = 0f
    private var shown = 0f

    // the rings' own opacity: in as it starts listening, out as it stops
    private var presence = 0f

    fun level(level: Float) {
        target = level.coerceIn(0f, 1f)
    }

    override fun onDraw(canvas: Canvas) {
        presence += ((if (active) 1f else 0f) - presence) * FADE
        shown += ((if (active) target else 0f) - shown) * EASE
        val cx = width / 2f
        val cy = height / 2f
        val outer = minOf(cx, cy)
        val reach = (outer - inner) * (QUIET_REACH + (1f - QUIET_REACH) * shown)
        val alpha = paint.alpha
        // at rest, a soft halo round the button: it stands out of the panel even before a press
        paint.alpha = (HALO_ALPHA * (1f - presence)).toInt()
        canvas.drawCircle(cx, cy, inner * HALO_SCALE, paint)
        val t = SystemClock.uptimeMillis() / PERIOD_MS
        for (i in 0 until RINGS) {
            // each ring a share of the period behind the last
            val p = (t + i.toFloat() / RINGS) % 1f
            paint.alpha = (MAX_ALPHA * (1f - p) * presence).toInt()
            canvas.drawCircle(cx, cy, inner + reach * p, paint)
        }
        paint.alpha = alpha
        if (isShown && (active || presence > SETTLED)) postInvalidateOnAnimation()
    }

    companion object {
        private const val RINGS = 3
        private const val PERIOD_MS = 1500f
        private const val MAX_ALPHA = 110
        private const val EASE = 0.2f
        private const val FADE = 0.12f
        private const val QUIET_REACH = 0.45f
        private const val SETTLED = 0.01f
        private const val HALO_ALPHA = 36
        private const val HALO_SCALE = 1.16f
    }
}
