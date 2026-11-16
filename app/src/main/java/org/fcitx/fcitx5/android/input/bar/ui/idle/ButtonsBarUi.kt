/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.bar.ui.idle

import android.content.Context
import android.view.Gravity
import android.widget.LinearLayout
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.input.bar.KawaiiBarComponent
import org.fcitx.fcitx5.android.input.bar.ui.ToolButton
import splitties.dimensions.dp
import splitties.views.dsl.core.Ui

/**
 * The open toolbar: its buttons in the bar's slots after the menu key, ⋯ last, none scrolled (the ones with no slot are
 * in ⋯'s panel: ToolbarConfig.Layout.slots, IdleUi).
 */
class ButtonsBarUi(override val ctx: Context, private val theme: Theme) : Ui {

    /** Each button's slot, px: the menu key's and the right's the same (IdleUi). */
    var cellPx = 0
        set(value) {
            if (value == field) return
            field = value
            for (i in 0 until root.childCount) {
                val cell = root.getChildAt(i)
                if (cell.layoutParams.width != value) {
                    cell.layoutParams.width = value
                    cell.requestLayout()
                }
            }
        }

    override val root = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    // as tall as the bar (候选字号), [cellPx] wide; the icon its size all the same
    /** [toolButtons] in this order, ⋯ the last of them, none of them in another parent. */
    fun show(toolButtons: List<ToolButton>) {
        root.removeAllViews()
        val height = ctx.dp(KawaiiBarComponent.heightDp(ctx))
        toolButtons.forEach { root.addView(it, LinearLayout.LayoutParams(cellPx, height)) }
    }
}
