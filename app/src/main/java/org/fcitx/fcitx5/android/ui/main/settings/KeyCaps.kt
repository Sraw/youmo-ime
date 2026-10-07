/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings

import android.content.Context
import android.content.res.Configuration
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.R as MaterialR
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.data.theme.ThemePrefs.PunctuationPosition
import org.fcitx.fcitx5.android.utils.styledColor
import splitties.dimensions.dp

/**
 * Keys drawn as keys in the settings that edit what a key does (long press, Chinese punctuation),
 * so a page reads as the keyboard it changes rather than as a list of strings.
 */
object KeyCaps {

    /** A key's face: [marked] outlined in the accent colour, [selected] filled with it */
    fun background(ctx: Context, marked: Boolean = false, radius: Int = 8, selected: Boolean = false) = GradientDrawable().apply {
        cornerRadius = ctx.dp(radius).toFloat()
        setColor(ctx.styledColor(if (selected) MaterialR.attr.colorPrimaryContainer else MaterialR.attr.colorSurfaceContainerHighest))
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
     * A key with [text] on it and [hint] small where the keyboard puts the first long-press
     * character (the theme's [PunctuationPosition], as [KeyView][org.fcitx.fcitx5.android.input.keyboard.KeyView]
     * places it); [dimmed] for a key whose long press is turned off.
     */
    fun key(ctx: Context, text: String, hint: String, marked: Boolean, dimmed: Boolean, selected: Boolean = false) = FrameLayout(ctx).apply {
        background = background(ctx, marked, selected = selected)
        isClickable = true
        isFocusable = true
        foreground = ctx.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground)).run {
            getDrawable(0).also { recycle() }
        }
        contentDescription = text
        val label = label(ctx, text, 20f).apply { alpha = if (dimmed) 0.4f else 1f }
        val small = TextView(ctx).apply {
            this.text = hint
            textSize = 10f
            maxLines = 1
            setTextColor(ctx.styledColor(MaterialR.attr.colorOnSurfaceVariant))
        }
        when (hintPosition(ctx)) {
            PunctuationPosition.TopRight -> {
                addView(label)
                addView(small, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply {
                    setMargins(0, ctx.dp(2), ctx.dp(4), 0)
                })
            }
            PunctuationPosition.Bottom -> addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                addView(label, LinearLayout.LayoutParams(-2, -2))
                if (hint.isNotEmpty()) addView(small, LinearLayout.LayoutParams(-2, -2))
            }, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
            PunctuationPosition.None -> addView(label)
        }
    }

    // under the letter only in portrait: a landscape key is too short for two lines, so the
    // keyboard puts it in the corner there
    private fun hintPosition(ctx: Context): PunctuationPosition {
        val at = ThemeManager.prefs.punctuationPosition.getValue()
        val landscape = ctx.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        return if (at == PunctuationPosition.Bottom && landscape) PunctuationPosition.TopRight else at
    }
}
