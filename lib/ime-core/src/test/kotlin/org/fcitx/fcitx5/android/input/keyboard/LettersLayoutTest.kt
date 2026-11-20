/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.fcitx.fcitx5.android.input.keyboard.LayoutSwitchPolicy.Outcome
import org.fcitx.fcitx5.android.input.keyboard.LayoutSwitchPolicy.Target
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** On 九键 the letters are the nine keys: every way back to the letters goes there; an input method change shows them unless another layout is up. */
class LettersLayoutTest {

    private val layout = LettersLayout(setOf("Text", "T9", "Number"), text = "Text", symbolPicker = "Symbol")

    @Test
    fun onThe26KeysTheSwitchesAreThePolicys() {
        assertEquals(Outcome(Target.Keyboard("Text"), null),
            layout.switchTo("Text", lastSymbol = "Number", current = "Number", remember = true))
        assertEquals(Outcome(Target.Unchanged, null),
            layout.switchTo("Text", lastSymbol = "Number", current = "Text", remember = true))
        assertEquals(Outcome(Target.Keyboard("Number"), "Number"),
            layout.switchTo("", lastSymbol = "Number", current = "Text", remember = true))
    }

    @Test
    fun aNineKeyInputMethodShowsItsLettersAtOnce() {
        assertEquals("T9", layout.inputMethodChanged("T9", current = "Text"))
        assertEquals("T9", layout.letters)
        assertNull("no change", layout.inputMethodChanged("T9", current = "T9"))
        assertEquals("Text", layout.inputMethodChanged("Text", current = "T9"))
        assertEquals("Text", layout.letters)
    }

    @Test
    fun theSwitchKeysGoBackToTheNineKeys() {
        layout.inputMethodChanged("T9", current = "Text")
        assertEquals(Outcome(Target.Keyboard("Number"), "Number"),
            layout.switchTo("Number", lastSymbol = "Symbol", current = "T9", remember = true))
        assertEquals("not remembered as a symbol destination", Outcome(Target.Keyboard("T9"), null),
            layout.switchTo("Text", lastSymbol = "Number", current = "Number", remember = true))
        assertEquals(Outcome(Target.Unchanged, null),
            layout.switchTo("Text", lastSymbol = "Number", current = "T9", remember = true))
        assertEquals(Outcome(Target.SymbolPicker, "Symbol"),
            layout.switchTo("", lastSymbol = "Symbol", current = "T9", remember = true))
    }

    /** A text field starting after a number field is no switch the user asked for, and still goes to 九键. */
    @Test
    fun aFieldStartingGoesToTheInputMethodsLetters() {
        layout.inputMethodChanged("T9", current = "Text")
        assertEquals(Outcome(Target.Keyboard("T9"), null),
            layout.switchTo("Text", lastSymbol = "Symbol", current = "Number", remember = false))
    }

    /** Changed under the number keyboard, the input method's letters are where its ABC goes back to. */
    @Test
    fun aChangeUnderAnotherLayoutLeavesItShowing() {
        assertNull(layout.inputMethodChanged("T9", current = "Number"))
        assertEquals(Outcome(Target.Keyboard("T9"), null),
            layout.switchTo("Text", lastSymbol = "Number", current = "Number", remember = true))
        assertNull(layout.inputMethodChanged("Text", current = "Number"))
        assertEquals(Outcome(Target.Keyboard("Text"), null),
            layout.switchTo("Text", lastSymbol = "Number", current = "Number", remember = true))
    }
}
