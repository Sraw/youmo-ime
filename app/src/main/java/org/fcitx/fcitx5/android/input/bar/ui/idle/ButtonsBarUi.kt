/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.bar.ui.idle

import android.content.Context
import android.widget.HorizontalScrollView
import androidx.annotation.DrawableRes
import com.google.android.flexbox.AlignItems
import com.google.android.flexbox.FlexboxLayout
import com.google.android.flexbox.JustifyContent
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.theme.Theme
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
    // emoji and voice beside it), scrolled rather than squeezed below a button's 40dp
    override val root = view(::HorizontalScrollView) {
        isFillViewport = true
        add(buttons, lParams(wrapContent, matchParent))
    }

    private fun toolButton(@DrawableRes icon: Int) = ToolButton(ctx, icon, theme).also {
        val size = ctx.dp(40)
        buttons.addView(it, FlexboxLayout.LayoutParams(size, size))
    }

    val undoButton = toolButton(R.drawable.ic_baseline_undo_24).apply {
        contentDescription = ctx.getString(R.string.undo)
    }

    val redoButton = toolButton(R.drawable.ic_baseline_redo_24).apply {
        contentDescription = ctx.getString(R.string.redo)
    }

    val cursorMoveButton = toolButton(R.drawable.ic_cursor_move).apply {
        contentDescription = ctx.getString(R.string.text_editing)
    }

    val clipboardButton = toolButton(R.drawable.ic_clipboard).apply {
        contentDescription = ctx.getString(R.string.clipboard)
    }

    val moreButton = toolButton(R.drawable.ic_baseline_more_horiz_24).apply {
        contentDescription = ctx.getString(R.string.status_area)
    }

}
