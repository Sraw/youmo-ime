/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.clipboard

import android.content.Context
import androidx.constraintlayout.widget.ConstraintLayout
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.theme.Theme
import splitties.dimensions.dp
import splitties.resources.drawable
import splitties.views.dsl.constraintlayout.above
import splitties.views.dsl.constraintlayout.below
import splitties.views.dsl.constraintlayout.bottomOfParent
import splitties.views.dsl.constraintlayout.constraintLayout
import splitties.views.dsl.constraintlayout.endOfParent
import splitties.views.dsl.constraintlayout.lParams
import splitties.views.dsl.constraintlayout.matchConstraints
import splitties.views.dsl.constraintlayout.startOfParent
import splitties.views.dsl.constraintlayout.topOfParent
import splitties.views.dsl.core.Ui
import splitties.views.dsl.core.add
import splitties.views.dsl.core.imageView
import splitties.views.dsl.core.styles.AndroidStyles
import splitties.views.dsl.core.textView
import splitties.views.dsl.core.wrapContent
import splitties.views.imageDrawable
import splitties.views.setPaddingDp

sealed class ClipboardInstructionUi(override val ctx: Context, protected val theme: Theme) : Ui {

    class Enable(ctx: Context, theme: Theme) : ClipboardInstructionUi(ctx, theme) {

        private val androidStyles = AndroidStyles(ctx)

        private val instructionText = textView {
            setText(R.string.instruction_enable_clipboard_listening)
            setPaddingDp(12, 8, 12, 8)
            setTextColor(theme.keyTextColor)
        }

        val enableButton = androidStyles.button.borderless {
            setText(R.string.clipboard_enable)
            setTextColor(theme.accentKeyBackgroundColor)
        }

        override val root = constraintLayout {
            add(instructionText, lParams(wrapContent, wrapContent) {
                topOfParent()
                startOfParent()
                endOfParent()
            })
            add(enableButton, lParams(wrapContent, wrapContent) {
                below(instructionText)
                endOfParent(dp(8))
            })
        }
    }

    class Empty(ctx: Context, theme: Theme) : ClipboardInstructionUi(ctx, theme) {

        private val icon = imageView {
            imageDrawable = drawable(R.drawable.ic_baseline_content_paste_24)!!.apply {
                setTint(theme.altKeyTextColor)
            }
        }

        private val instructionText = textView {
            setText(R.string.instruction_copy)
            setTextColor(theme.keyTextColor)
        }

        // the icon 90 dp where there is room, smaller where not: turned, the panel can be four rows of 36 dp
        // high (061-landscape-height), where a 90 dp icon pushed the words out of the bottom. The two a chain, the
        // gap between them the text's 16 dp alone: a margin on each side of the link adds up (32 dp, R26 F8363)
        override val root = constraintLayout {
            add(icon, lParams(dp(90), matchConstraints) {
                topOfParent(dp(24))
                above(instructionText)
                startOfParent()
                endOfParent()
                matchConstraintMaxHeight = dp(90)
                verticalChainStyle = ConstraintLayout.LayoutParams.CHAIN_PACKED
                verticalBias = 0f
            })
            add(instructionText, lParams(wrapContent, wrapContent) {
                below(icon, dp(16))
                bottomOfParent(dp(8))
                startOfParent()
                endOfParent()
            })
        }
    }
}
