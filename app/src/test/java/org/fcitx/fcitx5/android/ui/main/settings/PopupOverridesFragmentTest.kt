/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings

import org.fcitx.fcitx5.android.input.popup.PopupOverrides
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * What the long-press editor shows is what the popup shows. Putting the built-in list back on
 * "Q" while "q" was edited used to drop "Q"'s override, so the popup showed "q"'s edit upper-cased.
 */
class PopupOverridesFragmentTest {

    private val presetLower = arrayOf("1", "Q")
    private val presetUpper = arrayOf("1", "q")

    @Test
    fun theBuiltInListPutBackOnAnUpperCaseKeyIsStoredWhenItsLowerCaseKeyIsEdited() {
        val after = PopupOverrides.parse("q x y").withEdit("Q", presetUpper.toList(), presetUpper)
        assertEquals(presetUpper.toList(), after["Q"])
        assertArrayEquals(presetUpper, after.resolve("Q", presetUpper))
    }

    @Test
    fun theBuiltInListPutBackIsNotAnEdit() {
        val after = PopupOverrides.parse("q x y").withEdit("q", presetLower.toList(), presetLower)
        assertEquals(PopupOverrides.Empty, after)
    }

    @Test
    fun anUpperCaseKeySetToWhatItsLowerCaseEditGivesFollowsThatEdit() {
        val after = PopupOverrides.parse("q x y\nQ m").withEdit("Q", listOf("X", "Y"), presetUpper)
        assertNull(after["Q"])
        assertArrayEquals(arrayOf("X", "Y"), after.resolve("Q", presetUpper))
    }
}
