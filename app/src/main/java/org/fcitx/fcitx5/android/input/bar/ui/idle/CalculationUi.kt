/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.bar.ui.idle

import android.content.Context
import android.text.TextUtils
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.input.keyboard.CustomGestureView
import org.fcitx.fcitx5.android.utils.rippleDrawable
import splitties.dimensions.dp
import splitties.views.dsl.constraintlayout.centerInParent
import splitties.views.dsl.constraintlayout.constraintLayout
import splitties.views.dsl.constraintlayout.lParams
import splitties.views.dsl.constraintlayout.matchConstraints
import splitties.views.dsl.core.Ui
import splitties.views.dsl.core.add
import splitties.views.dsl.core.lParams
import splitties.views.dsl.core.matchParent
import splitties.views.dsl.core.textView
import splitties.views.dsl.core.verticalMargin
import splitties.views.dsl.core.wrapContent
import splitties.views.horizontalPadding

/** The result of the sum just written ([org.fcitx.fcitx5.android.input.bar.Calculation]): `= 42`, a tap puts it in. */
class CalculationUi(override val ctx: Context, private val theme: Theme) : Ui {

    val text = textView {
        isSingleLine = true
        ellipsize = TextUtils.TruncateAt.MIDDLE
        textSize = 18f
        setTextColor(theme.keyTextColor)
        horizontalPadding = dp(12)
    }

    val suggestionView = CustomGestureView(ctx).apply {
        add(text, lParams(wrapContent, matchParent))
        background = rippleDrawable(theme.keyPressHighlightColor)
    }

    override val root = constraintLayout {
        add(suggestionView, lParams(wrapContent, matchConstraints) {
            centerInParent()
            verticalMargin = dp(4)
        })
    }
}
