/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.candidates

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.text.bold
import androidx.core.text.buildSpannedString
import androidx.core.text.color
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.input.popup.CharCardLayout
import org.fcitx.fcitx5.android.utils.styledColorOrDefault
import splitties.dimensions.dp
import splitties.resources.styledDrawable

/** [wraps]: more Han characters than a card is made of; nothing to tap. */
@SuppressLint("ViewConstructor")
class CompactLineView(
    context: Context,
    val text: String,
    val reading: String,
    accent: Int,
    wraps: Boolean,
    openLabel: String? = null,
    open: (() -> Unit)? = null,
) : LinearLayout(context) {

    private val secondary = context.styledColorOrDefault(android.R.attr.textColorSecondary, Color.GRAY)

    private fun line() = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, TEXT_SP)
        ellipsize = TextUtils.TruncateAt.END
    }

    val shown: TextView = line().apply {
        maxLines = 1
        text = buildSpannedString {
            bold { color(accent) { append(this@CompactLineView.text) } }
            if (!wraps && reading.isNotEmpty()) color(secondary) { append(SPACE + reading) }
        }
    }

    val wrapped: TextView? = if (wraps) line().apply {
        maxLines = MAX_LINES
        text = reading
        setTextColor(secondary)
    } else null

    init {
        minimumHeight = dp(CharCardLayout.MIN_ROW_DP)
        gravity = Gravity.CENTER_VERTICAL
        val pad = dp(PADDING)
        setPadding(pad, 0, pad, 0)
        if (wrapped != null) {
            orientation = VERTICAL
            setPadding(pad, dp(PADDING / 2), pad, dp(PADDING / 2))
            addView(shown, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(wrapped, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            // words, not a button: nothing behind it
            contentDescription = listOf(text, context.getString(R.string.char_card_readings) + " " + reading).joinToString("，")
            isClickable = false
            // a stop of its own for TalkBack, read whole, not skipped as a layout
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            isFocusable = true
        } else {
            orientation = HORIZONTAL
            addView(shown, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (open != null) {
                addView(TextView(context).apply {
                    this.text = CHEVRON
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, TEXT_SP)
                    setTextColor(secondary)
                }, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(PADDING) })
                background = context.styledDrawable(android.R.attr.selectableItemBackground)
                setOnClickListener { open() }
                openLabel?.let { tapsTo(it) }
            }
            contentDescription = listOf(text, reading).filter { it.isNotEmpty() }.joinToString("，")
        }
        for (i in 0 until childCount) getChildAt(i).importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    private companion object {
        const val TEXT_SP = 16f
        const val PADDING = 16
        const val MAX_LINES = 4
        const val SPACE = "　"
        const val CHEVRON = "›"
    }
}
