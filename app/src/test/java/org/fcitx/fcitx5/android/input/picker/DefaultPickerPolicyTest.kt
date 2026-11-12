/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.picker

import org.fcitx.fcitx5.android.input.keyboard.KeyDef
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
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

    /**
     * The picker shows symbols the user chose literally, so its long-press popup must not re-map punctuation
     * the way a keyboard key would; its items the built-in ones, no setting's (060-keys-unify C1).
     */
    @Test
    fun thePopupIsBuiltInAndCommittedAsShown() {
        val popup = policy.popup(",") as KeyDef.Popup.Keyboard.Explicit
        assertTrue(popup.asIs)
        // the lists' contents are PickerLongPressTest's: here only that the popup is that list
        for (raw in listOf(",", "$", "1")) {
            assertArrayEquals(raw, PickerLongPress[raw], (policy.popup(raw) as KeyDef.Popup.Keyboard.Explicit).items)
        }
    }

    // a letter's list is a keyboard key's, not a cell's; a symbol with none built in has none
    @Test
    fun noLettersNoneForTheRest() {
        assertNull(policy.popup("a"))
        assertNull(policy.popup("ё"))
        assertNull(policy.popup("《"))
    }
}
