/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.fcitx.fcitx5.android.input.picker.PickerWindow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Sogou's bottom row has no quick-phrase key, so on the 26 keys holding 符 and 123 opens quick
 * phrase and Unicode input, while tapping them still switches the layout. The nine keys send only
 * digits, which can type neither a quick-phrase keyword nor a hex code, so there they only tap.
 */
@RunWith(RobolectricTestRunner::class)
class LayoutSwitchKeyTest {

    private fun List<List<KeyDef>>.bottomKey(label: String) =
        last().single { (it.appearance as? KeyDef.Appearance.Text)?.displayText == label }

    private fun KeyDef.tapped() = behaviors.filterIsInstance<KeyDef.Behavior.Press>().single().action

    private fun KeyDef.held() = behaviors.filterIsInstance<KeyDef.Behavior.LongPress>().singleOrNull()?.action

    @Test
    fun holdingThe26KeysSwitchKeysOpensQuickPhraseAndUnicode() {
        val symbols = TextKeyboard.Layout.bottomKey("符")
        assertEquals(KeyAction.LayoutSwitchAction(PickerWindow.Key.Symbol.name), symbols.tapped())
        assertEquals(KeyAction.QuickPhraseAction, symbols.held())
        val numbers = TextKeyboard.Layout.bottomKey("123")
        assertEquals(KeyAction.LayoutSwitchAction(NumberKeyboard.Name), numbers.tapped())
        assertEquals(KeyAction.UnicodeAction, numbers.held())
    }

    @Test
    fun theNineKeysSwitchKeysOnlyTap() {
        val symbols = T9Keyboard.Layout.bottomKey("符")
        assertEquals(KeyAction.LayoutSwitchAction(PickerWindow.Key.Symbol.name), symbols.tapped())
        assertNull(symbols.held())
        val numbers = T9Keyboard.Layout.bottomKey("123")
        assertEquals(KeyAction.LayoutSwitchAction(NumberKeyboard.Name), numbers.tapped())
        assertNull(numbers.held())
    }

    @Test
    fun aSwitchKeyHoldsNothingUnlessGivenALongPress() {
        val key = LayoutSwitchKey("ABC", TextKeyboard.Name)
        assertEquals(KeyAction.LayoutSwitchAction(TextKeyboard.Name), key.tapped())
        assertNull(key.held())
    }
}
