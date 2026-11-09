/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import android.annotation.SuppressLint
import android.content.Context
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.input.broadcast.ReturnKeyAppearance
import org.fcitx.fcitx5.android.input.keyboard.canvas.SceneBoard
import org.fcitx.fcitx5.android.input.keyboard.canvas.SceneEnvs
import org.fcitx.fcitx5.android.input.keyboard.canvas.SceneIcons
import org.fcitx.fcitx5.android.input.keyboard.canvas.SceneLayout
import org.fcitx.fcitx5.android.input.picker.PickerWindow
import org.fcitx.fcitx5.android.input.shortcut.KeyShortcutPreset
import org.fcitx.fcitx5.android.input.shortcut.KeyShortcuts
import org.fcitx.fcitx5.android.input.theme.KeyNames
import org.fcitx.fcitx5.android.scene.engine.Env
import org.fcitx.fcitx5.android.scene.engine.Input
import org.fcitx.fcitx5.android.scene.engine.Texts
import org.fcitx.fcitx5.android.scene.look.ThemeLook

@SuppressLint("ViewConstructor")
class NumberKeyboard(
    context: Context,
    theme: Theme,
    private val pad: NumberPad = NumberPad.FULL,
) : BaseKeyboard(context, theme, layout(pad), themeBoard = KeyNames.NUMBER, shortcuts = true) {

    companion object {
        const val Name = NumberPad.FULL_LAYOUT

        /**
         * The layout its keys are drawn from (lib/keyboard-scene's number.xml):
         * every pad's, each by its `pad=` (xsd README 24), one board a theme styles (`number/<key>`).
         */
        internal const val LAYOUT = KeyNames.NUMBER

        /** [pad] as number.xml's `pad=` names it: `full`, `signed-decimal`… */
        internal fun padOf(pad: NumberPad): String = pad.name.lowercase().replace('_', '-')

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

    // Return as last shown: its icon on the scene's key too
    private var returnAppearance = ReturnKeyAppearance.Enter

    override fun onReturnDrawableUpdate(appearance: ReturnKeyAppearance) {
        returnAppearance = appearance
        canvas?.invalidateScene()
    }

    // what the keys show now as the scene engine's environment (number.xml's when= and bindings): the pad
    // (its keys beside the digits), Return's label and icon
    override fun sceneEnv(look: ThemeLook, layout: SceneLayout, width: Int, height: Int): Env {
        val texts = Texts(
            returnLabel = returnAppearance.label(context).toString(),
            returnIcon = SceneIcons.returnName(returnAppearance.icon),
        )
        return SceneEnvs.of(this, look, theme.isDark, width, height, Input(pad = padOf(pad)), texts = texts, marks = SceneBoard.marks(layout, charShortcutMarks))
    }

    // last: the scene reads the pad (the keys drawn by the scene engine; number.xml's preview="off" popups="off")
    init {
        drawKeysWithScene(LAYOUT)
    }
}
