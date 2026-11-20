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
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.input.candidates.SceneBar
import org.fcitx.fcitx5.android.scene.engine.BarLook
import org.fcitx.fcitx5.android.scene.look.BoardBase
import splitties.dimensions.dp

/** Outside the keyboard's view, which floating clips to its outline (FloatingCorners). */
@SuppressLint("ViewConstructor")
class FloatingCodeStrip(ctx: Context, theme: Theme, content: View) : FrameLayout(ctx) {

    /** 0 for no cap. */
    var maxWidth = 0
        set(value) {
            if (field == value) return
            field = value
            requestLayout()
        }

    var below = false
        set(value) {
            if (field == value) return
            field = value
            round()
        }

    private val radius = dp(CORNER_DP).toFloat()

    // a theme's picture is not carried out to the strip. A see-through bar is the keyboard's colour seen through it:
    // made 90% opaque as it is, black text sat on black
    private val shape = GradientDrawable().apply {
        val look = ThemeManager.shownLook(theme)
        val picture = look.picture != null && look.base == BoardBase.Picture
        val bar = (SceneBar.of(ctx, theme).base as? BarLook.Base.Solid)?.color?.value
        val looks = if (bar == null || picture) theme.backgroundColor else ColorUtils.compositeColors(bar, theme.backgroundColor)
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

        const val ALPHA = 230
    }
}
