/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

/** The letters' layout is the input method's (九键 or [text]); [LayoutSwitchPolicy] knows only [text], so this switches through it. */
class LettersLayout(keyboards: Set<String>, private val text: String, symbolPicker: String) {

    private val policy = LayoutSwitchPolicy(keyboards, text, symbolPicker)

    var letters = text
        private set

    /** [current] and the target being the letters as they are. */
    fun switchTo(requested: String, lastSymbol: String, current: String, remember: Boolean): LayoutSwitchPolicy.Outcome {
        val outcome = policy.switchTo(requested, lastSymbol, if (current == letters) text else current, remember)
        val target = outcome.target
        return if (target is LayoutSwitchPolicy.Target.Keyboard && target.name == text) {
            outcome.copy(target = LayoutSwitchPolicy.Target.Keyboard(letters))
        } else {
            outcome
        }
    }

    /** Null leaves [current]; under another layout the new letters wait for the switch back. */
    fun inputMethodChanged(layout: String, current: String): String? {
        if (layout == letters) return null
        val showing = current == letters
        letters = layout
        return layout.takeIf { showing }
    }
}
