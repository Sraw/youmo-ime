/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.popup

import org.fcitx.fcitx5.android.assertRows
import org.fcitx.fcitx5.android.engine.host.Keyboard
import org.fcitx.fcitx5.android.engine.pinyin.NineKeyLetters
import org.fcitx.fcitx5.android.engine.pinyin.g14
import org.fcitx.fcitx5.android.engine.pinyin.nineSp
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    // whether a long press's item is refused: one code point and internal (any private use) alone
    @Test
    fun anInternalCodeAloneIsRejected() = assertRows(
        "the 14 keys' codes, first and last" to { assertTrue(PopupOverrides.rejects(g14("as")) && PopupOverrides.rejects(g14("zx"))) },
        "the 18 keys' codes, first and last" to { assertTrue(PopupOverrides.rejects("\uF8C0") && PopupOverrides.rejects(String(Character.toChars(NineKeyLetters.G18_BLOCK.last)))) },
        "the nine keys' 双拼's codes" to { assertTrue(PopupOverrides.rejects(nineSp("2")) && PopupOverrides.rejects(nineSp("9"))) },
        "the syllables' codes" to { assertTrue(PopupOverrides.rejects(Keyboard.SYLLABLES.first.toString())) },
        "a tap: supplementary, two chars, one code point" to { assertTrue(PopupOverrides.rejects(String(Character.toChars(Keyboard.TAPS.first)))) },
        // the codes of an earlier build (⒜–⒵, ⒉–⒐), and their neighbours: characters a user may want
        "enclosed letters and digits are text" to {
            for (c in listOf("⒜", "⒵", "⒉", "⒐", "①", "⑴", "⒈", "Ⓐ")) assertFalse(c, PopupOverrides.rejects(c))
        },
        // text of more characters is committed as text: never read as a key
        "a code within text is text" to {
            assertFalse(PopupOverrides.rejects("${g14("as")}x") || PopupOverrides.rejects("a${g14("bn")}") || PopupOverrides.rejects(nineSp("234")))
        },
    )

    // the keyboards' own codes (design §1.1): never kept, and one an older build or a backup wrote is
    // kept as written but never offered
    @Test
    fun aKeyboardsCodeIsNeverKeptNorReadBack() {
        val qw = g14("qw")
        assertEquals(listOf("1", "x", "⒈⒉⒊"), PopupOverrides.Empty.with("q", listOf("1", qw, "x", "⒈⒉⒊")).get("q"))
        assertEquals(listOf("⒜", "⒉"), PopupOverrides.Empty.with("q", listOf("⒜", "⒉")).get("q"))
        val written = "q 1 $qw x \uF8C1\n${g14("bn")} 2"
        val read = PopupOverrides.parse(written)
        assertEquals(written, read.serialize())
        assertArrayEquals(arrayOf("1", "x"), read.resolve("q", preset))
        assertEquals(listOf("x"), PopupOverrides.Empty.with("w", listOf("\uF8C1", "x")).get("w"))
    }

    // a stored line emptied only by refused codes falls back to the preset; one written empty switches it off
    @Test
    fun aLineOfCodesAloneShowsThePreset() {
        assertArrayEquals(arrayOf("1"), PopupOverrides.parse("q ${g14("qw")}").resolve("q", arrayOf("1")))
        assertNull(PopupOverrides.parse("q").resolve("q", arrayOf("1")))
    }
}
