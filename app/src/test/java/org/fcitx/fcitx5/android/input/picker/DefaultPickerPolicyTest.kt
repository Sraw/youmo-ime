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

/** The emoji picker's policy is not tested: `android.icu` and `TextPaint.hasGlyph` do not behave as on a device under Robolectric. */
class DefaultPickerPolicyTest {

    private val policy = DefaultPickerPolicy()

    /** Symbols the user chose literally: the popup must not re-map punctuation as a keyboard key would. */
    @Test
    fun thePopupIsBuiltInAndCommittedAsShown() {
        val popup = policy.popup(",") as KeyDef.Popup.Keyboard.Explicit
        assertTrue(popup.asIs)
        // the lists' contents are PickerLongPressTest's
        for (raw in listOf(",", "$", "1")) {
            assertArrayEquals(raw, PickerLongPress[raw], (policy.popup(raw) as KeyDef.Popup.Keyboard.Explicit).items)
        }
    }

    @Test
    fun noLettersNoneForTheRest() {
        assertNull(policy.popup("a"))
        assertNull(policy.popup("ё"))
        assertNull(policy.popup("《"))
    }
}
