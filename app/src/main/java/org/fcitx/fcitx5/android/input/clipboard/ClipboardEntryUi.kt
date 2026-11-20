/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.clipboard

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.TextUtils
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.scene.engine.PanelLooks
import org.fcitx.fcitx5.android.input.keyboard.CustomGestureView
import splitties.dimensions.dp
import splitties.views.dsl.constraintlayout.centerVertically
import splitties.views.dsl.constraintlayout.constraintLayout
import splitties.views.dsl.constraintlayout.lParams
import splitties.views.dsl.core.Ui
import splitties.views.dsl.core.add
import splitties.views.dsl.core.lParams
import splitties.views.dsl.core.matchParent
import splitties.views.dsl.core.textView
import splitties.views.dsl.core.wrapContent
import splitties.views.setPaddingDp

class ClipboardEntryUi(override val ctx: Context, private val theme: Theme, look: PanelLooks.Clipboard, radius: Float) : Ui {

    val textView = textView {
        minLines = 1
        maxLines = 4
        textSize = 14f
        setPaddingDp(8, 4, 8, 4)
        ellipsize = TextUtils.TruncateAt.END
        setTextColor(look.color.value)
    }

    val layout = constraintLayout {
        add(textView, lParams(matchParent, wrapContent) {
            centerVertically()
        })
    }

    override val root = CustomGestureView(ctx).apply {
        isClickable = true
        minimumHeight = dp(30)
        foreground = RippleDrawable(
            ColorStateList.valueOf(theme.keyPressHighlightColor), null,
            GradientDrawable().apply {
                cornerRadius = radius
                setColor(Color.WHITE)
            }
        )
        background = GradientDrawable().apply {
            cornerRadius = radius
            setColor(look.fill.value)
        }
        add(layout, lParams(matchParent, matchParent))
    }

    /** [masked]: read as hidden, not as a row of dots. */
    fun setEntry(text: String, masked: Boolean) {
        textView.text = text
        root.contentDescription = if (masked) ctx.getString(R.string.a11y_sensitive_hidden) else null
    }
}