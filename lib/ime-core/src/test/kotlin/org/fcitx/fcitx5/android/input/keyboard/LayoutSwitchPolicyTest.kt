/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.fcitx.fcitx5.android.input.keyboard.LayoutSwitchPolicy.Outcome
import org.fcitx.fcitx5.android.input.keyboard.LayoutSwitchPolicy.Target
import org.junit.Assert.assertEquals
import org.junit.Test

class LayoutSwitchPolicyTest {

    private val policy = LayoutSwitchPolicy(setOf("Text", "Number"), textKeyboard = "Text", symbolPicker = "Symbol")

    @Test
    fun theSymbolKeyGoesWhereTheUserLastWent() {
        assertEquals(Outcome(Target.Keyboard("Number"), "Number"),
            policy.switchTo("", lastSymbol = "Number", current = "Text", remember = true))
        assertEquals(Outcome(Target.SymbolPicker, "Symbol"),
            policy.switchTo("", lastSymbol = "Symbol", current = "Text", remember = true))
    }

    @Test
    fun goingToTheNumberKeyboardIsRemembered() {
        assertEquals(Outcome(Target.Keyboard("Number"), "Number"),
            policy.switchTo("Number", lastSymbol = "Symbol", current = "Text", remember = true))
    }

    @Test
    fun goingBackToLettersIsNotASymbolDestination() {
        assertEquals(Outcome(Target.Keyboard("Text"), null),
            policy.switchTo("Text", lastSymbol = "Number", current = "Number", remember = true))
    }

    /** `!?#` on the number keyboard names the picker, which is not a keyboard layout. */
    @Test
    fun anythingThatIsNotAKeyboardOpensTheSymbolPicker() {
        assertEquals(Outcome(Target.SymbolPicker, "Symbol"),
            policy.switchTo("Symbol", lastSymbol = "Number", current = "Number", remember = true))
        assertEquals("even a stale memory", Outcome(Target.SymbolPicker, "Symbol"),
            policy.switchTo("", lastSymbol = "Emoji", current = "Text", remember = true))
    }

    @Test
    fun theCurrentLayoutIsLeftAloneButStillRemembered() {
        assertEquals(Outcome(Target.Unchanged, "Number"),
            policy.switchTo("Number", lastSymbol = "Symbol", current = "Number", remember = true))
    }

    /** A number field opening the number keyboard is not the user choosing it. */
    @Test
    fun automaticSwitchesRememberNothing() {
        assertEquals(Outcome(Target.Keyboard("Number"), null),
            policy.switchTo("Number", lastSymbol = "Symbol", current = "Text", remember = false))
        assertEquals(Outcome(Target.SymbolPicker, null),
            policy.switchTo("", lastSymbol = "Symbol", current = "Text", remember = false))
        assertEquals(Outcome(Target.Unchanged, null),
            policy.switchTo("Number", lastSymbol = "Symbol", current = "Number", remember = false))
    }
}
