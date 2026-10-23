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
    override val root = view(::HorizontalScrollView) {
        isFillViewport = true
        isHorizontalScrollBarEnabled = false
        add(buttons, lParams(wrapContent, matchParent))
    }

    // as tall as the bar (候选字号) and as wide as the other bar buttons; narrower where the bar is
    // (the floating keyboard's), down to MIN_SIZE, then scrolled; the icon its size all the same
    private fun toolButton(@DrawableRes icon: Int) = ToolButton(ctx, icon, theme).also {
        val width = ctx.dp(maxOf(KawaiiBarComponent.HEIGHT, KawaiiBarComponent.MIN_TOUCH_DP))
        it.fixIconSize(ctx.dp(KawaiiBarComponent.ICON_DP))
        buttons.addView(
            it,
            FlexboxLayout.LayoutParams(width, ctx.dp(KawaiiBarComponent.HEIGHT)).apply { minWidth = ctx.dp(MIN_SIZE) }
        )
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

    private companion object {
        const val MIN_SIZE = 28
    }
}
