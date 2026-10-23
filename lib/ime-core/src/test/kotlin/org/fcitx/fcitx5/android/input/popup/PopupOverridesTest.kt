/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.popup

import org.fcitx.fcitx5.android.assertRows
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PopupOverridesTest {

    private val preset = arrayOf("1", "Q")

    @Test
    fun anOverrideOrThePresetIsWhatPopsUp() = assertRows(
        "without an override the preset is used" to {
            assertSame(preset, PopupOverrides.Empty.resolve("q", preset))
            assertNull(PopupOverrides.Empty.resolve("q", null))
        },
        "an override replaces the preset rather than merging with it" to {
            val o = PopupOverrides.parse("q ， 。")
            assertArrayEquals(arrayOf("，", "。"), o.resolve("q", preset))
        },
        "an override can add popup to a key that has none" to {
            assertArrayEquals(arrayOf("x"), PopupOverrides.parse("， x").resolve("，", null))
        },
        "a label with no candidates switches the popup off" to {
            assertNull(PopupOverrides.parse("q").resolve("q", preset))
        },
        "other keys keep their preset" to {
            assertSame(preset, PopupOverrides.parse("w 2").resolve("q", preset))
        },
    )

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
    fun eachLineIsALabelAndItsCandidates() = assertRows(
        "a later line for the same label wins" to {
            assertEquals(listOf("b"), PopupOverrides.parse("q a\nq b")["q"])
        },
        "a label can be the punctuation the format uses" to {
            val o = PopupOverrides.parse("= a b\n\\ c")
            assertEquals(listOf("a", "b"), o["="])
            assertEquals(listOf("c"), o["\\"])
        },
        // Typing in full-width mode puts U+3000 between candidates.
        "full width and no break spaces separate words too" to {
            assertEquals(listOf("，", "。", "a"), PopupOverrides.parse("q\u3000，\u3000。\u00a0a")["q"])
            assertEquals(listOf("a", "b"), PopupOverrides.tokens(" a\u3000b\n"))
        },
        "parse tolerates blank lines tabs and crlf" to {
            val o = PopupOverrides.parse("\n  q\t1   2 \r\n\r\n   \nw 3\n")
            assertEquals(setOf("q", "w"), o.labels)
            assertEquals(listOf("1", "2"), o["q"])
        },
    )

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

    @Test
    fun shiftDoesNotShowTheSameCandidateTwice() {
        assertArrayEquals(arrayOf("1", "Q", "É"), PopupOverrides.parse("q 1 Q é").resolve("Q", preset))
        // q and Q both upper-case to Q
        assertArrayEquals(arrayOf("Q", "1"), PopupOverrides.parse("q q Q 1").resolve("Q", preset))
    }
}
