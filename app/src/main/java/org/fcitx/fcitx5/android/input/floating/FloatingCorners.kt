/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.floating

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewOutlineProvider

/**
 * The floating keyboard's frame, as users asked and the theme has it (`frame`, 060-render-p3 T7a):
 * [view] and all it holds, the theme's picture too, clipped to a rectangle with corners [radius]
 * pixels round (read as the outline is made again, as the view's size changes: a turn of the screen
 * changes the keys' margins the default follows, WindowLooks.cornerDp), and a shadow [elevation]
 * deep, so it stands apart from the app under it, of the theme's colour where it sets one.
 */
object FloatingCorners {

    fun frame(view: View, radius: () -> Float, elevation: Float, shadowColor: Int? = null) {
        view.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, radius())
            }
        }
        view.clipToOutline = true
        view.elevation = elevation
        // the system's shadow, black, but where the theme colours it (API 28; minSdk is 29)
        if (shadowColor != null) {
            view.outlineAmbientShadowColor = shadowColor
            view.outlineSpotShadowColor = shadowColor
        }
    }

    /** A line [width] px of [color] round the inside of [view]'s frame, over all it holds, as round as it. */
    fun border(view: View, width: Float, color: Int, radius: () -> Float) {
        view.foreground = Border(width, color, radius)
    }

    /**
     * [layer] (the popups: a key's bubble, a long press's keys) drawn over [view], the keyboard
     * [frame]d: once a child has a Z its parent draws its children by Z, and the keyboard lifted
     * covered the popups added after it.
     */
    fun keepAbove(layer: View, view: View) {
        layer.translationZ = view.elevation + 1f
    }

    // the line's middle half its width in, so all of it is inside the outline that clips it
    private class Border(private val width: Float, color: Int, private val radius: () -> Float) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = width
            this.color = color
        }

        override fun draw(canvas: Canvas) {
            val b = bounds
            val half = width / 2
            val r = maxOf(0f, radius() - half)
            canvas.drawRoundRect(b.left + half, b.top + half, b.right - half, b.bottom - half, r, r, paint)
        }

        override fun setAlpha(alpha: Int) {
            paint.alpha = alpha
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            paint.colorFilter = colorFilter
        }

        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }
}
