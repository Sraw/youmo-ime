/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.picker

import android.annotation.SuppressLint
import android.content.Context
import androidx.constraintlayout.widget.ConstraintLayout
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.input.broadcast.ReturnKeyAppearance
import org.fcitx.fcitx5.android.input.keyboard.*
import splitties.views.dsl.constraintlayout.above
import splitties.views.dsl.constraintlayout.below
import splitties.views.dsl.constraintlayout.bottomOfParent
import splitties.views.dsl.constraintlayout.centerHorizontally
import splitties.views.dsl.constraintlayout.lParams
import splitties.views.dsl.constraintlayout.topOfParent
import splitties.views.dsl.core.add

@SuppressLint("ViewConstructor")
class PickerLayout(context: Context, theme: Theme, switchKey: KeyDef, density: PickerGridView.Density) :
    ConstraintLayout(context) {

    class Keyboard(context: Context, theme: Theme, switchKey: KeyDef) : BaseKeyboard(
        context, theme, listOf(
            listOf(
                LayoutSwitchKey("ABC", TextKeyboard.Name),
                PunctuationKey(","),
                switchKey,
                SpaceKey(),
                PunctuationKey("."),
                // here, not in the list: it stays put while the list scrolls
                BackspaceKey(),
                ReturnKey()
            )
        )
    ) {

        class PunctuationKey(val symbol: String) : KeyDef(
            Appearance.Text(
                displayText = symbol,
                textSize = 23f,
                percentWidth = 0.1f,
                variant = Appearance.Variant.Alternative
            ),
            setOf(
                Behavior.Press(KeyAction.FcitxKeyAction(symbol))
            )
        )

        val `return`: ImageKeyView by lazy { findViewById(R.id.button_return) }

        override fun onReturnDrawableUpdate(appearance: ReturnKeyAppearance) {
            `return`.showReturnAction(appearance)
        }
    }

    val embeddedKeyboard = Keyboard(context, theme, switchKey)

    val grid = PickerGridView(context, theme, density)

    val tabsUi = PickerTabsUi(context, theme)

    init {
        add(grid, lParams {
            topOfParent()
            centerHorizontally()
            above(embeddedKeyboard)
        })
        add(embeddedKeyboard, lParams {
            below(grid)
            centerHorizontally()
            bottomOfParent()
            matchConstraintPercentHeight = 0.25f
        })
    }
}