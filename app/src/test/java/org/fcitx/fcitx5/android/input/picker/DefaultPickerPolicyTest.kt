/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.picker

import org.fcitx.fcitx5.android.input.keyboard.KeyDef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The policy used by the symbol picker: pass everything through untouched. The emoji picker's
 * policy is not covered here -- it leans on `EmojiModifier`, whose glyph checks go through
 * `android.icu` and `TextPaint.hasGlyph`, neither of which behaves like a real device under
 * Robolectric. Testing it there would assert fiction rather than behaviour.
 */
class DefaultPickerPolicyTest {

    private val policy = DefaultPickerPolicy()

    @Test
    fun everySymbolIsKept() {
        for (symbol in listOf("!", "，", "😀", "", "   ", "multi char")) {
            assertTrue("filtered out $symbol", policy.filter(symbol))
        }
    }

    @Test
    fun symbolsAreCommittedVerbatim() {
        for (symbol in listOf("!", "，", "😀", "", "a b")) {
            assertEquals(symbol, policy.transform(symbol))
        }
    }

    @Test
    fun everySymbolGetsAPopupLabelledWithItself() {
        val popup = policy.popup("！")
        assertTrue(popup is KeyDef.Popup.Keyboard.Preset)
        assertEquals("！", (popup as KeyDef.Popup.Keyboard.Preset).label)
    }

    /**
     * The picker shows symbols the user chose literally, so its long-press popup must not
     * re-map punctuation the way a keyboard key would.
     */
    @Test
    fun thePopupDoesNotTransformPunctuation() {
        val popup = policy.popup(",") as KeyDef.Popup.Keyboard.Preset
        assertFalse(popup.transformPunctuation)
    }

    /** A constant invalidate key means the picker never rebuilds its pages on attach. */
    @Test
    fun theInvalidateKeyIsStable() {
        assertEquals(policy.invalidateKey(), policy.invalidateKey())
        assertEquals(DefaultPickerPolicy().invalidateKey(), policy.invalidateKey())
    }
}
