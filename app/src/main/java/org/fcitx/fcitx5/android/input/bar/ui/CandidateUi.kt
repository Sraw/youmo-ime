/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.bar.ui

import android.content.Context
import android.view.View
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.input.bar.KawaiiBarComponent
import splitties.dimensions.dp
import splitties.views.dsl.constraintlayout.before
import splitties.views.dsl.constraintlayout.centerVertically
import splitties.views.dsl.constraintlayout.constraintLayout
import splitties.views.dsl.constraintlayout.endOfParent
import splitties.views.dsl.constraintlayout.lParams
import splitties.views.dsl.constraintlayout.startOfParent
import splitties.views.dsl.core.Ui
import splitties.views.dsl.core.add

class CandidateUi(override val ctx: Context, theme: Theme, private val horizontalView: View) : Ui {

    val expandButton = ToolButton(ctx, R.drawable.ic_baseline_expand_more_24, theme).apply {
        id = R.id.expand_candidate_btn
        visibility = View.INVISIBLE
        fixIconSize(ctx.dp(KawaiiBarComponent.ICON_DP))
    }

    override val root = ctx.constraintLayout {
        // the bar's height (候选字号), never narrower than a 40 dp touch target
        add(expandButton, lParams(dp(maxOf(KawaiiBarComponent.HEIGHT, KawaiiBarComponent.MIN_TOUCH_DP)), dp(KawaiiBarComponent.HEIGHT)) {
            centerVertically()
            endOfParent()
        })
        add(horizontalView, lParams {
            centerVertically()
            startOfParent()
            before(expandButton)
        })
    }
}
