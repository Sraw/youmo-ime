/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.TextView
import com.google.android.material.R as MaterialR
import org.fcitx.fcitx5.android.utils.styledColor
import splitties.dimensions.dp

/**
 * Keys drawn as keys in the settings that edit what a key does (long press, Chinese punctuation),
 * so a page reads as the keyboard it changes rather than as a list of strings.
 */
object KeyCaps {

    /** A key's face: [marked] (the user changed it) outlined in the accent colour */
    fun background(ctx: Context, marked: Boolean = false, radius: Int = 8) = GradientDrawable().apply {
        cornerRadius = ctx.dp(radius).toFloat()
        setColor(ctx.styledColor(MaterialR.attr.colorSurfaceContainerHighest))
        if (marked) setStroke(ctx.dp(2), ctx.styledColor(android.R.attr.colorPrimary))
    }

    fun label(ctx: Context, text: String, size: Float = 18f) = TextView(ctx).apply {
        this.text = text
        textSize = size
        gravity = Gravity.CENTER
        typeface = Typeface.MONOSPACE
        setTextColor(ctx.styledColor(MaterialR.attr.colorOnSurface))
    }

    /**
     * A key with [text] on it and [hint] small in its corner, as the keyboard shows the first
     * long-press character; [dimmed] for a key whose long press is turned off.
     */
    fun key(ctx: Context, text: String, hint: String, marked: Boolean, dimmed: Boolean) = FrameLayout(ctx).apply {
        background = background(ctx, marked)
        isClickable = true
        isFocusable = true
        foreground = ctx.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground)).run {
            getDrawable(0).also { recycle() }
        }
        contentDescription = text
        addView(label(ctx, text, 20f).apply { alpha = if (dimmed) 0.4f else 1f })
        addView(TextView(ctx).apply {
            this.text = hint
            textSize = 10f
            maxLines = 1
            setTextColor(ctx.styledColor(MaterialR.attr.colorOnSurfaceVariant))
        }, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply {
            setMargins(0, ctx.dp(2), ctx.dp(4), 0)
        })
    }
}
