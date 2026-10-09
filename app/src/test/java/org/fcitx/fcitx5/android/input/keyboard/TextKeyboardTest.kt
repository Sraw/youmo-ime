/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.fcitx.fcitx5.android.input.popup.PopupOverrides
import org.fcitx.fcitx5.android.input.popup.PopupPreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A letter's swipe is the first of its long press ([swipeOf]). Under Shift the long press shows
 * the upper-case label's list, the user's edit upper-cased; the swipe resolved the lower-case
 * one, so with Shift on it typed é where the long press offered É.
 */
class TextKeyboardTest {

    @Test
    fun noEditKeepsTheBuiltInSwipe() {
        assertNull(PopupOverrides.Empty.swipeOf("q", "q"))
        assertNull(PopupOverrides.Empty.swipeOf("q", "Q"))
    }

    @Test
    fun theSwipeIsTheFirstOfTheLongPress() {
        assertEquals("é", PopupOverrides.parse("e é è").swipeOf("e", "e"))
    }

    @Test
    fun underShiftTheSwipeIsWhatTheLongPressOffersFirst() {
        val overrides = PopupOverrides.parse("e é è")
        assertEquals("É", overrides.swipeOf("e", "E"))
        assertEquals(overrides.resolve("E", PopupPreset["E"])?.first(), overrides.swipeOf("e", "E"))
        assertEquals("QU", PopupOverrides.parse("q qu").swipeOf("q", "Q"))
    }

    @Test
    fun aLongPressTurnedOffSwipesToNothingWithOrWithoutShift() {
        val overrides = PopupOverrides.parse("q")
        assertEquals("", overrides.swipeOf("q", "q"))
        assertEquals("", overrides.swipeOf("q", "Q"))
    }

    @Test
    fun anEditOfTheUpperCaseLabelOnlyCountsUnderShift() {
        val overrides = PopupOverrides.parse("Q 7 8")
        assertNull(overrides.swipeOf("q", "q"))
        assertEquals("7", overrides.swipeOf("q", "Q"))
    }
}
