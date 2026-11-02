/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.preedit

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.FrameLayout
import androidx.core.graphics.ColorUtils
import org.fcitx.fcitx5.android.data.theme.Theme
import splitties.dimensions.dp

/**
 * The code row floating over the app (编码行 浮在键盘上方): [content] ([CodeRowUi] wrapped) on a
 * patch of the bar's colour, nine tenths opaque, its two corners away from the keyboard rounded.
 * Outside the keyboard's view, which floating clips to its outline (FloatingCorners); InputView
 * places it ([CodeStripPlacement]) and shows it while there is a code.
 */
@SuppressLint("ViewConstructor")
class FloatingCodeStrip(ctx: Context, theme: Theme, keyBorder: Boolean, content: View) : FrameLayout(ctx) {

    /** As wide as the board at most, in pixels; 0 for no cap. */
    var maxWidth = 0
        set(value) {
            if (field == value) return
            field = value
            requestLayout()
        }

    /** Hanging under the board, its rounded corners at the bottom. */
    var below = false
        set(value) {
            if (field == value) return
            field = value
            round()
        }

    private val radius = dp(CORNER_DP).toFloat()

    // the colour the bar looks: the keyboard's under bordered keys, the bar's own else; a theme's
    // picture is not carried out to the strip. A see-through bar (the Transparent themes, 15% black)
    // is the keyboard's colour seen through it: made 90% opaque as it is, black text sat on black
    private val shape = GradientDrawable().apply {
        val picture = theme is Theme.Custom && theme.backgroundImage != null
        val looks = if (keyBorder || picture) theme.backgroundColor else ColorUtils.compositeColors(theme.barColor, theme.backgroundColor)
        setColor(ColorUtils.setAlphaComponent(looks, ALPHA))
    }

    init {
        background = shape
        round()
        addView(content, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
        visibility = GONE
    }

    private fun round() {
        val r = radius
        shape.cornerRadii = if (below) floatArrayOf(0f, 0f, 0f, 0f, r, r, r, r) else floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val size = MeasureSpec.getSize(widthMeasureSpec)
        val cap = if (maxWidth > 0 && (size == 0 || maxWidth < size)) maxWidth else size
        super.onMeasure(MeasureSpec.makeMeasureSpec(cap, MeasureSpec.AT_MOST), heightMeasureSpec)
    }

    companion object {
        const val CORNER_DP = 8

        // nine tenths opaque: the app under it seen faintly, the code still clear (the user, 2026-11-01)
        const val ALPHA = 230
    }
}
