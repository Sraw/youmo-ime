/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.core

import org.fcitx.fcitx5.android.assertRows
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * fcitx counts its preedit cursor in UTF-8 bytes; Android counts in UTF-16 chars. A CJK character
 * is 3 bytes and 1 char, an emoji 4 bytes and 2 chars.
 */
class FormattedTextTest {

    private fun cursor(byteCursor: Int, vararg strings: String) =
        FormattedText.fromByteCursor(arrayOf(*strings), IntArray(strings.size), byteCursor).cursor

    @Test
    fun aByteCursorIsTheCharsItCovers() = assertRows(
        "ASCII bytes are chars" to {
            assertEquals(2, cursor(2, "abc"))
            assertEquals(3, cursor(3, "ab", "cd"))
            // at the end of one string
            assertEquals(2, cursor(2, "ab", "cd"))
        },
        "a CJK character is three bytes and one char" to {
            assertEquals(1, cursor(3, "中文"))
            assertEquals(2, cursor(6, "中文"))
            assertEquals(3, cursor(5, "ni", "你好"))
        },
        "an emoji is four bytes and two chars" to {
            assertEquals(1, cursor(1, "a😀b"))
            assertEquals(3, cursor(5, "a😀b"))
            assertEquals(4, cursor(6, "a😀b"))
        },
        "a cursor inside a BMP character is after it" to {
            // 1 and 2 bytes into 文, which fcitx never asks for: the partial bytes decode to one
            // U+FFFD, a char as long as 文 (inside an emoji's 4 bytes it would fall between its surrogates)
            assertEquals(2, cursor(4, "中文"))
            assertEquals(2, cursor(5, "中文"))
        },
        "a cursor past the end is at the end" to {
            assertEquals(5, cursor(100, "ab", "中😀"))
        },
        "no cursor stays no cursor" to {
            assertEquals(0, cursor(0, "abc"))
            assertEquals(-1, cursor(-1, "abc"))
        },
    )

    @Test
    fun theTextAndItsFlagsAreKept() {
        val flags = intArrayOf(TextFormatFlag.Underline.flag, TextFormatFlag.HighLight.flag)
        val text = FormattedText.fromByteCursor(arrayOf("ni", "你"), flags, 2)
        assertArrayEquals(arrayOf("ni", "你"), text.strings)
        assertArrayEquals(flags, text.flags)
        assertEquals(2, text.cursor)
    }
}
