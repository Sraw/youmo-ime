/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.floating

import android.view.View
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.updateLayoutParams
import org.fcitx.fcitx5.android.input.keyboard.SplitKeyboard
import org.fcitx.fcitx5.android.utils.unset

/**
 * What goes with the docked board at a side (单手模式, 分体时其他面板), under the same thumb: the
 * bars over it (a picker's tabs, the toolbar, the row of the code being typed) and the docked
 * handle (始终显示悬浮条), its grip at the board's middle (the owner, 2026-10-22). Across, the whole
 * width, [padding] in from each side as the board is.
 */
object BoardFollowers {

    fun place(views: List<View>, board: View, side: SplitKeyboard.Dock?, padding: Int) {
        val inset = if (side == null) padding else 0
        for (v in views) {
            v.updateLayoutParams<ConstraintLayout.LayoutParams> {
                leftToLeft = unset
                rightToRight = unset
                width = 0
                val to = if (side == null) ConstraintLayout.LayoutParams.PARENT_ID else board.id
                leftToLeft = to
                rightToRight = to
            }
            v.setPadding(inset, 0, inset, 0)
        }
    }
}
