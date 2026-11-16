/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.cards

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import org.fcitx.fcitx5.android.utils.styledColor
import splitties.dimensions.dp
import com.google.android.material.R as MaterialR

/**
 * A keyboard card's picture (dev/design/061-settings-cards.md §4.1): a small square a key, [rows] the
 * keys in each row, so the full keyboard, 九键 and 14 键 tell apart without reading; [bars] draws
 * three bars instead (the function keys). Shorter rows are centred. Only a picture: no node.
 */
class KeyboardGlyphView(ctx: Context, private val rows: List<Int>, private val bars: Boolean = false) : View(ctx) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ctx.styledColor(MaterialR.attr.colorOutline) }
    private val gap = ctx.dp(2).toFloat()
    private val rect = RectF()

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    /** The colour of its keys: the card's text colour, so a chosen card's picture is as its name. */
    fun setColor(color: Int) {
        paint.color = color
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val lines = if (bars) BARS else rows.size
        if (lines == 0 || width == 0) return
        // the middle part of the card's width, the badge's corner left clear
        val span = width * WIDTH_PART
        val h = (height - gap * (lines - 1)) / lines
        val most = if (bars) 1 else rows.max()
        val w = if (bars) span else (span - gap * (most - 1)) / most
        val r = minOf(w, h) / RADIUS_PART
        for (line in 0 until lines) {
            val n = if (bars) 1 else rows[line]
            val top = line * (h + gap)
            var left = (width - (n * w + (n - 1) * gap)) / 2
            repeat(n) {
                rect.set(left, top, left + w, top + h)
                canvas.drawRoundRect(rect, r, r, paint)
                left += w + gap
            }
        }
    }

    companion object {
        const val HEIGHT_DP = 28

        /** The part of its width drawn on, in the middle: the badge in the card's corner stays off it. */
        const val WIDTH_PART = 0.7f

        private const val BARS = 3
        private const val RADIUS_PART = 4f
    }
}
