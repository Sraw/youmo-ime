/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.floating

import android.view.View
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.updateLayoutParams
import org.fcitx.fcitx5.android.scene.rules.SplitKeyboard
import org.fcitx.fcitx5.android.utils.unset

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
