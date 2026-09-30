/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.popup

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PopupOverridesTest {

    private val preset = arrayOf("1", "Q")

    @Test
    fun withoutAnOverrideThePresetIsUsed() {
        assertSame(preset, PopupOverrides.Empty.resolve("q", preset))
        assertNull(PopupOverrides.Empty.resolve("q", null))
    }

    @Test
    fun anOverrideReplacesThePresetRatherThanMergingWithIt() {
        val o = PopupOverrides.parse("q ， 。")
        assertArrayEquals(arrayOf("，", "。"), o.resolve("q", preset))
    }

    @Test
    fun anOverrideCanAddPopupToAKeyThatHasNone() {
        assertArrayEquals(arrayOf("x"), PopupOverrides.parse("， x").resolve("，", null))
    }

    @Test
    fun aLabelWithNoCandidatesSwitchesThePopupOff() {
        assertNull(PopupOverrides.parse("q").resolve("q", preset))
    }

    @Test
    fun otherKeysKeepTheirPreset() {
        assertSame(preset, PopupOverrides.parse("w 2").resolve("q", preset))
    }

    @Test
    fun shiftCarriesALowerCaseEditOver() {
        assertArrayEquals(arrayOf("Q", "1"), PopupOverrides.parse("q q 1").resolve("Q", preset))
        // "ß".uppercase() is "SS", which is not a candidate the user typed
        assertArrayEquals(arrayOf("ß"), PopupOverrides.parse("q ß").resolve("Q", preset))
    }

    @Test
    fun anExplicitUpperCaseOverrideBeatsTheCarriedOne() {
        val o = PopupOverrides.parse("q a\nQ b")
        assertArrayEquals(arrayOf("b"), o.resolve("Q", preset))
    }

    @Test
    fun shiftDoesNotTouchNonLetterLabels() {
        assertSame(preset, PopupOverrides.parse("a x").resolve("AB", preset))
        assertSame(preset, PopupOverrides.parse("a x").resolve("1", preset))
    }

    @Test
    fun aLaterLineForTheSameLabelWins() {
        assertEquals(listOf("b"), PopupOverrides.parse("q a\nq b")["q"])
    }

    @Test
    fun parseToleratesBlankLinesTabsAndCrlf() {
        val o = PopupOverrides.parse("\n  q\t1   2 \r\n\r\n   \nw 3\n")
        assertEquals(setOf("q", "w"), o.labels)
        assertEquals(listOf("1", "2"), o["q"])
    }

    @Test
    fun aLabelCanBeThePunctuationTheFormatUses() {
        val o = PopupOverrides.parse("= a b\n\\ c")
        assertEquals(listOf("a", "b"), o["="])
        assertEquals(listOf("c"), o["\\"])
    }

    @Test
    fun emptyTextIsEmpty() {
        assertTrue(PopupOverrides.parse("").isEmpty)
        assertTrue(PopupOverrides.parse(" \n\t\n").isEmpty)
        assertSame(PopupOverrides.Empty, PopupOverrides.parse(""))
    }

    @Test
    fun serializeRoundTrips() {
        val o = PopupOverrides.parse("q 1 Q\nw\n， ！ ？")
        assertEquals(o, PopupOverrides.parse(o.serialize()))
        assertEquals("q 1 Q\nw\n， ！ ？", o.serialize())
    }

    @Test
    fun withReplacesAndDropsEmptyCandidates() {
        val o = PopupOverrides.Empty.with("q", listOf("1", "", "2")).with("q", listOf("3"))
        assertEquals(listOf("3"), o["q"])
        assertEquals("q 3", o.serialize())
    }

    @Test
    fun withoutRemovesAndIsNoOpForUnknownLabels() {
        val o = PopupOverrides.parse("q 1")
        assertTrue(o.without("q").isEmpty)
        assertSame(o, o.without("z"))
    }

    @Test
    fun editingDoesNotMutateTheOriginal() {
        val o = PopupOverrides.parse("q 1")
        o.with("w", listOf("2"))
        assertEquals(setOf("q"), o.labels)
    }

    /** Typing in full-width mode puts U+3000 between candidates. */
    @Test
    fun fullWidthAndNoBreakSpacesSeparateWordsToo() {
        assertEquals(listOf("，", "。", "a"), PopupOverrides.parse("q\u3000，\u3000。\u00a0a")["q"])
        assertEquals(listOf("a", "b"), PopupOverrides.tokens(" a\u3000b\n"))
    }

    @Test
    fun shiftDoesNotShowTheSameCandidateTwice() {
        assertArrayEquals(arrayOf("1", "Q", "É"), PopupOverrides.parse("q 1 Q é").resolve("Q", preset))
    }
}
