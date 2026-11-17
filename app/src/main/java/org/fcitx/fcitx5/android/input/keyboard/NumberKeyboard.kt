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
import org.fcitx.fcitx5.android.input.keyboard.canvas.SceneBoard
import org.fcitx.fcitx5.android.input.keyboard.canvas.SceneEnvs
import org.fcitx.fcitx5.android.input.keyboard.canvas.SceneIcons
import org.fcitx.fcitx5.android.input.keyboard.canvas.SceneLayout
import org.fcitx.fcitx5.android.input.keyboard.canvas.SceneLayouts
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
    /** The user's number board drawn in place of the app's pad ([customLayout]: `id@stamp`, 061-custom-layout §7); null for the app's. */
    private val custom: String? = null,
) : BaseKeyboard(context, theme, layout(pad, context.getString(R.string.picker_back)), themeBoard = KeyNames.NUMBER) {

    companion object {
        const val Name = NumberPad.FULL_LAYOUT

        /**
         * The layout its keys are drawn from (lib/keyboard-scene's number.xml):
         * every pad's, each by its `pad=` (xsd README 24), one board a theme styles (`number/<key>`).
         */
        internal const val LAYOUT = KeyNames.NUMBER

        // the keypad's own keys where it has them: no input method takes them for punctuation of its own (，for ,)
        private val KEYPAD = mapOf("+" to 0xffab, "-" to 0xffad, "*" to 0xffaa, "/" to 0xffaf, "," to 0xffac, "=" to 0xffbd, "." to 0xffae)

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

        /** [pad]'s keys: the digits as a phone's, [NumberPad]'s beside them; [back] the way back's label (返回). */
        fun layout(pad: NumberPad, back: String): List<List<KeyDef>> = listOf(
            listOf(side(pad.left[0], 0.16f), digit(1), digit(2), digit(3), BackspaceKey(0.18f)),
            listOf(side(pad.left[1], 0.16f), digit(4), digit(5), digit(6), MiniSpaceKey()),
            listOf(side(pad.left[2], 0.16f), digit(7), digit(8), digit(9), side(pad.besideNine, 0.18f)),
            listOf(
                LayoutSwitchKey("符", PickerWindow.Key.Symbol.name, 0.16f),
                LayoutSwitchKey(back, TextKeyboard.Name, 0.14f),
                side(pad.bottom[0], 0.08f),
                digit(0, 0.22f),
                side(pad.bottom[1], 0.12f, KeyDef.Appearance.Variant.AltForeground),
                side(pad.bottom[2], 0.1f),
                ReturnKey(0.18f)
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
            // the corners as said (Texts.alts by key name)
            alts = keyReadings(layout),
            returnLabel = returnAppearance.label(context).toString(),
            returnIcon = SceneIcons.returnName(returnAppearance.icon),
            // the way back to the letters, 返回 as the symbol panel's (061-layout-survey Q2)
            back = context.getString(R.string.picker_back),
        )
        return SceneEnvs.of(this, look, theme.isDark, width, height, Input(pad = pad.id), texts = texts, marks = keyMarks(layout))
    }

    // last: the scene reads the pad (the keys drawn by the scene engine; number.xml's preview="off" popups="off"); the
    // user's board where it reads and is taken (its pads by its own when=pad=…), the app's where it is not
    init {
        drawKeysWithScene(custom?.let { customLayout(context, it) } ?: SceneLayouts.of(context, LAYOUT))
    }
}
