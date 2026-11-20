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

class ButtonsBarUi(override val ctx: Context, private val theme: Theme) : Ui {

    /** The menu key's and the right's the same (IdleUi). */
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

    /** None of them in another parent. */
    fun show(toolButtons: List<ToolButton>) {
        root.removeAllViews()
        val height = ctx.dp(KawaiiBarComponent.heightDp(ctx))
        toolButtons.forEach { root.addView(it, LinearLayout.LayoutParams(cellPx, height)) }
    }
}
