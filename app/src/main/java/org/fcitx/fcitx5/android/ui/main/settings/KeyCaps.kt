/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.ViewCompat
import com.google.android.material.R as MaterialR
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.data.theme.ThemePrefs.PunctuationPosition
import org.fcitx.fcitx5.android.input.AutoScaleTextView
import org.fcitx.fcitx5.android.input.keyboard.KeyMarks
import org.fcitx.fcitx5.android.utils.styledColor
import splitties.dimensions.dp
import splitties.views.dsl.core.add
import splitties.views.dsl.core.lParams
import splitties.views.dsl.core.matchParent
import splitties.views.dsl.core.verticalLayout
import splitties.views.dsl.core.wrapContent

/**
 * Keys drawn as keys in the settings that edit what a key does (long press, Chinese punctuation),
 * so a page reads as the keyboard it changes rather than as a list of strings.
 */
object KeyCaps {

    // as the keyboard's marks (KeyView): an icon's side, and a text's share of the key's width
    private const val MARK_ICON_DP = 11
    private const val MARK_TEXT_WIDTH = 0.45f

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

    /** What a key draws beside its label, as the keyboard draws its swipes' marks ([KeyMarks]). */
    sealed interface Mark {
        /** A character: the corner symbol, or a text shortcut's whole, shrunk sideways to fit as on the key. */
        data class Text(val text: String) : Mark

        /** A command's icon. */
        data class Icon(@DrawableRes val res: Int) : Mark

        /** The key's symbol where the page labels the key by it (九键's digits): not drawn, its corner kept from the shortcut. */
        data object Unshown : Mark
    }

    /**
     * A key with [text] on it and [hint] small where the keyboard puts the first long-press
     * character (the theme's [PunctuationPosition], as [KeyView][org.fcitx.fcitx5.android.input.keyboard.KeyView]
     * places it); [dimmed] for a key whose long press is turned off. [state] is what a screen
     * reader says of what the outline and the fading show, which only the caller knows.
     * [marks], placed as the keyboard places them, in the hint's stead; [said], what a screen reader
     * says of the shortcut among them.
     */
    fun key(
        ctx: Context, text: String, hint: String, marked: Boolean, dimmed: Boolean, selected: Boolean = false,
        state: String? = null,
        marks: KeyMarks.Placed<Mark>? = null,
        said: String? = null,
    ) = FrameLayout(ctx).apply {
        background = background(ctx, marked, selected = selected)
        isClickable = true
        isFocusable = true
        foreground = ctx.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground)).run {
            getDrawable(0).also { recycle() }
        }
        contentDescription = listOfNotNull(text, hint, said).filter { it.isNotEmpty() }.joinToString(", ")
        ViewCompat.setStateDescription(this, state)
        isSelected = selected
        val label = label(ctx, text, 20f).apply { alpha = if (dimmed) 0.4f else 1f }
        if (marks != null) return@apply addMarked(ctx, label, marks)
        val small = TextView(ctx).apply {
            this.text = hint
            textSize = 10f
            maxLines = 1
            setTextColor(ctx.styledColor(MaterialR.attr.colorOnSurfaceVariant))
        }
        when (hintPosition(ctx)) {
            PunctuationPosition.TopRight -> {
                add(label, lParams(matchParent, matchParent))
                add(small, lParams(wrapContent, wrapContent, Gravity.TOP or Gravity.END) {
                    setMargins(0, ctx.dp(2), ctx.dp(4), 0)
                })
            }
            PunctuationPosition.Bottom -> add(ctx.verticalLayout {
                gravity = Gravity.CENTER_HORIZONTAL
                add(label, lParams(wrapContent, wrapContent))
                if (hint.isNotEmpty()) add(small, lParams(wrapContent, wrapContent))
            }, lParams(wrapContent, wrapContent, Gravity.CENTER))
            PunctuationPosition.None -> add(label, lParams(matchParent, matchParent))
        }
    }

    // [label] with [marks]: under it, and in the right corners over the key, sized as the keyboard's
    private fun FrameLayout.addMarked(ctx: Context, label: TextView, marks: KeyMarks.Placed<Mark>) {
        val below = marks.below?.let { markView(ctx, it) }
        if (below == null) {
            add(label, lParams(matchParent, matchParent))
        } else {
            add(ctx.verticalLayout {
                gravity = Gravity.CENTER_HORIZONTAL
                add(label, lParams(wrapContent, wrapContent))
                add(below, lParams(wrapContent, wrapContent))
            }, lParams(wrapContent, wrapContent, Gravity.CENTER))
        }
        val corners = ConstraintLayout(ctx)
        listOf(marks.topRight, marks.bottomRight).forEachIndexed { corner, mark ->
            val v = mark?.let { markView(ctx, it) } ?: return@forEachIndexed
            val text = mark is Mark.Text
            val icon = ctx.dp(MARK_ICON_DP)
            corners.addView(v, ConstraintLayout.LayoutParams(if (text) 0 else icon, if (text) wrapContent else icon).apply {
                if (text) matchConstraintPercentWidth = MARK_TEXT_WIDTH
                endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
                marginEnd = ctx.dp(4)
                if (corner == 0) {
                    topToTop = ConstraintLayout.LayoutParams.PARENT_ID
                    topMargin = ctx.dp(2)
                } else {
                    bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
                    bottomMargin = ctx.dp(2)
                }
            })
        }
        add(corners, lParams(matchParent, matchParent))
    }

    private fun markView(ctx: Context, mark: Mark): View? {
        val color = ctx.styledColor(MaterialR.attr.colorOnSurfaceVariant)
        return when (mark) {
            is Mark.Text -> AutoScaleTextView(ctx).apply {
                text = mark.text
                textSize = 10f
                setSingleLine()
                gravity = Gravity.END
                scaleMode = AutoScaleTextView.Mode.Horizontal
                setTextColor(color)
            }
            is Mark.Icon -> ImageView(ctx).apply {
                setImageResource(mark.res)
                imageTintList = ColorStateList.valueOf(color)
            }
            Mark.Unshown -> null
        }?.apply {
            isClickable = false
            isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
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
