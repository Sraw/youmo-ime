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

    private val buttons = view(::LinearLayout) {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    // where the bar has no room for them all at [cellPx] (a narrow screen, side padding, the floating keyboard), scrolled
    private val scroll = view(::HorizontalScrollView) {
        isFillViewport = true
        isHorizontalScrollBarEnabled = false
        add(buttons, lParams(wrapContent, matchParent))
    }

    // the last button, ⋯ (the way to the settings and out of floating): never scrolled away, right after the others
    // where they all fit, else at the end with them scrolled before it
    private val end = FrameLayout(ctx)

    /**
     * Each button's cell, px: the menu key's and the right's too while the toolbar is open, so all stand evenly apart
     * (IdleUi, ToolbarConfig.Layout.cellPx). The room left past them all is after ⋯, before the right's.
     */
    var cellPx = 0
        set(value) {
            if (value == field) return
            field = value
            val cells = (0 until buttons.childCount).map(buttons::getChildAt) + listOfNotNull(end.getChildAt(0))
            for (cell in cells) {
                if (cell.layoutParams.width != value) {
                    cell.layoutParams.width = value
                    cell.requestLayout()
                }
            }
            root.requestLayout()
        }

    override val root = object : LinearLayout(ctx) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val tail = if (end.childCount == 0) 0 else cellPx
            end.layoutParams.width = tail
            scroll.layoutParams.width = minOf(buttons.childCount * cellPx, MeasureSpec.getSize(widthMeasureSpec) - tail).coerceAtLeast(0)
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        }
    }.apply {
        orientation = LinearLayout.HORIZONTAL
        add(scroll, LinearLayout.LayoutParams(0, matchParent))
        add(end, LinearLayout.LayoutParams(0, matchParent))
    }

    // as tall as the bar (候选字号), [cellPx] wide; the icon its size all the same
    /** [toolButtons] in this order, none of them in another parent; [last] after them, always in view. */
    fun show(toolButtons: List<ToolButton>, last: ToolButton? = null) {
        buttons.removeAllViews()
        end.removeAllViews()
        val height = ctx.dp(KawaiiBarComponent.heightDp(ctx))
        toolButtons.forEach { buttons.addView(it, LinearLayout.LayoutParams(cellPx, height)) }
        last?.let { end.addView(it, FrameLayout.LayoutParams(cellPx, height, Gravity.CENTER)) }
        root.requestLayout()
    }

    companion object {
        /** The narrowest a cell gets where the bar has no room for them all, dp: scrolled past that. */
        const val MIN_SIZE = 28
    }
}
