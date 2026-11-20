/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.bar.ui

import android.content.Context
import android.view.View
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.input.bar.BarIcons
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

    val expandButton = ToolButton(ctx, BarIcons.CANDIDATES_EXPAND, theme).apply {
        id = R.id.expand_candidate_btn
        visibility = View.INVISIBLE
        fixIconSize(ctx.dp(KawaiiBarComponent.ICON_DP))
    }

    // shown with 联想's words alone, at the far right, the ⌄ before it
    val dropPredictionsButton = ToolButton(ctx, BarIcons.PREDICTIONS_CLOSE, theme).apply {
        id = R.id.drop_predictions_btn
        visibility = View.GONE
        contentDescription = ctx.getString(R.string.drop_predictions)
        fixIconSize(ctx.dp(KawaiiBarComponent.ICON_DP))
    }

    // turned off from here as a character is written
    val handwritingButton = ToolButton(ctx, BarIcons.HANDWRITING, theme).apply {
        id = R.id.candidate_handwriting_btn
        visibility = View.GONE
        contentDescription = ctx.getString(R.string.handwriting)
        fixIconSize(ctx.dp(KawaiiBarComponent.ICON_DP))
    }

    override val root = ctx.constraintLayout {
        // never narrower than a 40 dp touch target
        add(dropPredictionsButton, lParams(dp(KawaiiBarComponent.buttonWidthDp(ctx)), dp(KawaiiBarComponent.heightDp(ctx))) {
            centerVertically()
            endOfParent()
        })
        add(expandButton, lParams(dp(KawaiiBarComponent.buttonWidthDp(ctx)), dp(KawaiiBarComponent.heightDp(ctx))) {
            centerVertically()
            before(dropPredictionsButton)
        })
        add(handwritingButton, lParams(dp(KawaiiBarComponent.buttonWidthDp(ctx)), dp(KawaiiBarComponent.heightDp(ctx))) {
            centerVertically()
            before(expandButton)
        })
        add(horizontalView, lParams {
            centerVertically()
            startOfParent()
            before(handwritingButton)
        })
    }
}
