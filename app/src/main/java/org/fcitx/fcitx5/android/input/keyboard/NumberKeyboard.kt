/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import android.annotation.SuppressLint
import android.content.Context
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.input.broadcast.ReturnKeyAppearance
import org.fcitx.fcitx5.android.input.picker.PickerWindow
import org.fcitx.fcitx5.android.input.popup.PopupAction
import org.fcitx.fcitx5.android.input.shortcut.KeyShortcutPreset
import org.fcitx.fcitx5.android.input.shortcut.KeyShortcuts
import org.fcitx.fcitx5.android.input.theme.KeyNames

@SuppressLint("ViewConstructor")
class NumberKeyboard(
    context: Context,
    theme: Theme,
    pad: NumberPad = NumberPad.FULL,
) : BaseKeyboard(context, theme, layout(pad), themeBoard = KeyNames.NUMBER, shortcuts = true) {

    companion object {
        const val Name = NumberPad.FULL_LAYOUT

        // the keypad's own keys where it has them: no input method takes them for punctuation of its own (，for ,).
        // Before Layout, which reads it as the companion is made
        private val KEYPAD = mapOf("+" to 0xffab, "-" to 0xffad, "*" to 0xffaa, "/" to 0xffaf, "," to 0xffac, "=" to 0xffbd, "." to 0xffae)

        val Layout: List<List<KeyDef>> = layout(NumberPad.FULL)

        private fun digit(d: Int, percentWidth: Float = 0f) = NumPadKey("$d", 0xffb0 + d, 30f, percentWidth, shortcut = KeyShortcuts.charKey(KeyShortcutPreset.NUMBER, "$d"))

        // a key beside the digits: blank where the pad has none there, the digits staying where they are on every pad
        private fun side(label: String, percentWidth: Float, variant: KeyDef.Appearance.Variant = KeyDef.Appearance.Variant.Alternative): KeyDef {
            if (label.isEmpty()) return BlankKey(percentWidth, variant)
            return when (val sym = KEYPAD[label]) {
                null -> KeyDef(
                    KeyDef.Appearance.Text(label, 23f, percentWidth = percentWidth, variant = variant),
                    setOf(KeyDef.Behavior.Press(KeyAction.CommitAction(label))),
                    name = label,
                )
                else -> NumPadKey(label, sym, 23f, percentWidth, variant)
            }
        }

        /** [pad]'s keys: the digits as a phone's, [NumberPad]'s beside them. */
        fun layout(pad: NumberPad): List<List<KeyDef>> = listOf(
            listOf(side(pad.left[0], 0.15f), digit(1), digit(2), digit(3), side(pad.corner, 0.15f)),
            listOf(side(pad.left[1], 0.15f), digit(4), digit(5), digit(6), MiniSpaceKey()),
            listOf(side(pad.left[2], 0.15f), digit(7), digit(8), digit(9), BackspaceKey()),
            listOf(
                LayoutSwitchKey("ABC", TextKeyboard.Name),
                side(pad.bottom[0], 0.1f),
                LayoutSwitchKey("!?#", PickerWindow.Key.Symbol.name, 0.13333f, KeyDef.Appearance.Variant.AltForeground),
                digit(0, 0.23334f),
                side(pad.bottom[1], 0.13333f, KeyDef.Appearance.Variant.AltForeground),
                side(pad.bottom[2], 0.1f),
                ReturnKey()
            )
        )
    }

    val backspace: ImageKeyView by lazy { findViewById(R.id.button_backspace) }
    val `return`: ImageKeyView by lazy { findViewById(R.id.button_return) }

    override fun onReturnDrawableUpdate(appearance: ReturnKeyAppearance) {
        `return`.showReturnAction(appearance)
    }

    // no preview nor long press popup here; the edit menu a swipe opens (the space's) and what picks from it go on
    override fun onPopupAction(action: PopupAction) {
        when (action) {
            is PopupAction.PreviewAction, is PopupAction.PreviewUpdateAction,
            is PopupAction.ShowKeyboardAction, is PopupAction.ShowMenuAction -> {}
            else -> super.onPopupAction(action)
        }
    }

}