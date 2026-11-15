/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.bar.ui.idle

import android.content.Context
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import com.google.android.flexbox.AlignItems
import com.google.android.flexbox.FlexboxLayout
import com.google.android.flexbox.JustifyContent
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.input.bar.KawaiiBarComponent
import org.fcitx.fcitx5.android.input.bar.ui.ToolButton
import splitties.dimensions.dp
import splitties.views.dsl.core.Ui
import splitties.views.dsl.core.add
import splitties.views.dsl.core.lParams
import splitties.views.dsl.core.matchParent
import splitties.views.dsl.core.view
import splitties.views.dsl.core.wrapContent

class ButtonsBarUi(override val ctx: Context, private val theme: Theme) : Ui {

    private val buttons = view(::FlexboxLayout) {
        alignItems = AlignItems.CENTER
        justifyContent = JustifyContent.SPACE_AROUND
    }

    // spread out where the bar has room; where it has not (a narrow screen, side padding, the
    // emoji and voice beside it, the floating keyboard), squeezed to MIN_SIZE, then scrolled
    private val scroll = view(::HorizontalScrollView) {
        isFillViewport = true
        isHorizontalScrollBarEnabled = false
        add(buttons, lParams(wrapContent, matchParent))
    }

    // the last button, ⋯ (the way to the settings and out of floating): never scrolled away. Its slot is its share of
    // the row, as the others spread out around it (so with room the row looks as if it were among them), and no
    // narrower than MIN_SIZE: where they are scrolled, it stays at the end
    private val end = FrameLayout(ctx)
    private var buttonWidth = 0

    override val root = object : LinearLayout(ctx) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val last = if (end.childCount == 0) null else end.getChildAt(0)
            val slot = if (last == null) 0 else maxOf(MeasureSpec.getSize(widthMeasureSpec) / (buttons.childCount + 1), ctx.dp(MIN_SIZE))
            end.layoutParams.width = slot
            // as wide as the others, or its slot where that is less: squeezed as they are
            last?.layoutParams?.width = minOf(buttonWidth, slot)
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        }
    }.apply {
        orientation = LinearLayout.HORIZONTAL
        add(scroll, LinearLayout.LayoutParams(0, matchParent, 1f))
        add(end, LinearLayout.LayoutParams(0, matchParent))
    }

    // as tall as the bar (候选字号) and as wide as the other bar buttons; narrower where the bar is
    // (the floating keyboard's), down to MIN_SIZE, then scrolled; the icon its size all the same
    /** [toolButtons] in this order, none of them in another parent; [last] after them, always in view. */
    fun show(toolButtons: List<ToolButton>, last: ToolButton? = null) {
        buttons.removeAllViews()
        end.removeAllViews()
        buttonWidth = ctx.dp(KawaiiBarComponent.buttonWidthDp(ctx))
        val height = ctx.dp(KawaiiBarComponent.heightDp(ctx))
        toolButtons.forEach {
            buttons.addView(it, FlexboxLayout.LayoutParams(buttonWidth, height).apply { minWidth = ctx.dp(MIN_SIZE) })
        }
        last?.let { end.addView(it, FrameLayout.LayoutParams(buttonWidth, height, Gravity.CENTER)) }
        root.requestLayout()
    }

    private companion object {
        const val MIN_SIZE = 28
    }
}
