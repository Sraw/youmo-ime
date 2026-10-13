/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.input.popup.PopupOverrides
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A letter's swipe is the first of its long press ([swipeOf], through [longPressOf]). Under Shift
 * the long press shows the upper-case label's list, the user's edit upper-cased; the swipe resolved
 * the lower-case one, so with Shift on it typed é where the long press offered É.
 */
class TextKeyboardTest {

    @Test
    fun theLongPressOffersTheUsersListOrTheBuiltInOne() {
        assertArrayEquals(arrayOf("1", "Q"), PopupOverrides.Empty.longPressOf("q"))
        assertArrayEquals(arrayOf("é", "è"), PopupOverrides.parse("e é è").longPressOf("e"))
        assertNull(PopupOverrides.parse("q").longPressOf("q"))
    }

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
        assertArrayEquals(arrayOf("É", "È"), overrides.longPressOf("E"))
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

    /** An address's keys commit as they are: under Chinese the full stop's bubble showed 。 while it typed . */
    @Test
    fun anAddressKeysBubbleShowsWhatItCommits() {
        val zh = mapOf("." to "。", "/" to "、")
        val punctuation = { p: String -> zh.getOrDefault(p, p) }
        val shift = { c: String -> c.uppercase() }
        assertEquals(".", previewOf(".", R.id.button_dot, shift, punctuation))
        assertEquals("/", previewOf("/", R.id.button_slash, shift, punctuation))
        assertEquals(".com", previewOf(".com", R.id.button_domain, shift, punctuation))
        // the full stop beside the space is the input method's, and a letter is as Shift has it
        assertEquals("。", previewOf(".", R.id.button_period, shift, punctuation))
        assertEquals("Q", previewOf("q", -1, shift, punctuation))
    }
}
