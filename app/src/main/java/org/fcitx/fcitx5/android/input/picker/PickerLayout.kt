/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.picker

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Typeface
import androidx.constraintlayout.widget.ConstraintLayout
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.input.broadcast.ReturnKeyAppearance
import org.fcitx.fcitx5.android.input.keyboard.*
import splitties.views.dsl.constraintlayout.above
import splitties.views.dsl.constraintlayout.after
import splitties.views.dsl.constraintlayout.below
import splitties.views.dsl.constraintlayout.bottomOfParent
import splitties.views.dsl.constraintlayout.centerHorizontally
import splitties.views.dsl.constraintlayout.endOfParent
import splitties.views.dsl.constraintlayout.lParams
import splitties.views.dsl.constraintlayout.startOfParent
import splitties.views.dsl.constraintlayout.topOfParent
import splitties.views.dsl.core.add

/**
 * The picker over a row of keys. The emoji: one list, its tabs on the bar. The symbol [panel]:
 * the categories at the side, the one picked beside them, and a lock in the row of keys.
 */
@SuppressLint("ViewConstructor")
class PickerLayout(context: Context, theme: Theme, switchKey: KeyDef, density: PickerGridView.Density, panel: Boolean) :
    ConstraintLayout(context) {

    class Keyboard(context: Context, theme: Theme, keys: List<KeyDef>) : BaseKeyboard(context, theme, listOf(keys)) {

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

        /** the symbol panel's lock, none in the emoji's */
        val lock: ImageKeyView? by lazy { findViewById(R.id.button_panel_lock) }

        fun showLocked(locked: Boolean) {
            lock?.apply {
                img.setImageResource(if (locked) R.drawable.ic_baseline_lock_24 else R.drawable.ic_baseline_lock_open_24)
                // what a tap does
                contentDescription = context.getString(if (locked) R.string.picker_unlock else R.string.picker_lock)
            }
        }

        override fun onReturnDrawableUpdate(appearance: ReturnKeyAppearance) {
            `return`.showReturnAction(appearance)
        }
    }

    val embeddedKeyboard = Keyboard(context, theme, if (panel) panelKeys(context, switchKey) else listKeys(switchKey))

    val grid = PickerGridView(context, theme, density)

    val tabsUi by lazy { PickerTabsUi(context, theme) }

    val side: PickerSideUi? = if (panel) PickerSideUi(context, theme) else null

    init {
        if (side != null) {
            add(side, lParams {
                topOfParent()
                startOfParent()
                above(embeddedKeyboard)
                matchConstraintPercentWidth = SIDE_WIDTH
            })
        }
        add(grid, lParams {
            topOfParent()
            if (side != null) {
                after(side)
                endOfParent()
            } else {
                centerHorizontally()
            }
            above(embeddedKeyboard)
        })
        add(embeddedKeyboard, lParams {
            below(grid)
            centerHorizontally()
            bottomOfParent()
            matchConstraintPercentHeight = 0.25f
        })
    }

    companion object {
        private const val SIDE_WIDTH = 0.18f

        private fun listKeys(switchKey: KeyDef) = listOf(
            LayoutSwitchKey("ABC", TextKeyboard.Name),
            Keyboard.PunctuationKey(","),
            switchKey,
            SpaceKey(),
            Keyboard.PunctuationKey("."),
            // here, not in the list: it stays put while the list scrolls
            BackspaceKey(),
            ReturnKey()
        )

        /** Sogou's: 返回 where ABC was, the lock beside it; the punctuation is in the panel */
        private fun panelKeys(context: Context, switchKey: KeyDef) = listOf(
            KeyDef(
                KeyDef.Appearance.Text(
                    displayText = context.getString(R.string.picker_back),
                    textSize = 16f,
                    textStyle = Typeface.BOLD,
                    percentWidth = 0.15f,
                    variant = KeyDef.Appearance.Variant.Alternative
                ),
                setOf(KeyDef.Behavior.Press(KeyAction.PanelBackAction))
            ),
            KeyDef(
                KeyDef.Appearance.Image(
                    src = R.drawable.ic_baseline_lock_open_24,
                    percentWidth = 0.12f,
                    variant = KeyDef.Appearance.Variant.Alternative,
                    viewId = R.id.button_panel_lock,
                    contentDescription = R.string.picker_lock
                ),
                setOf(KeyDef.Behavior.Press(KeyAction.PanelLockAction))
            ),
            switchKey,
            SpaceKey(),
            BackspaceKey(),
            ReturnKey()
        )
    }
}
