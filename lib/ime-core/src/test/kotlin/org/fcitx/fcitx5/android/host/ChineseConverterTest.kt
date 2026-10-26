/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.host

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.nio.ByteBuffer

/**
 * OpenCC 1.2.0's rules on small dictionaries (the whole data against OpenCC itself is
 * `lib/ime-eval`'s ChineseConverterGoldenTest). Files are 1.2.0's `src/`.
 */
class ChineseConverterTest {

    @Test
    fun `the group asks STPhrases first, and takes a dictionary's longest key (DictGroup cpp MatchPrefix)`() {
        // a segment that is a phrase converts as the phrase, though STCharacters has each character
        assertEquals("P", converter(phrases = mapOf("ab" to "P"), characters = mapOf("a" to "x", "b" to "y")).convert("ab"))
        assertEquals("Q", converter(characters = mapOf("x" to "1", "xy" to "Q")).convert("xy"))
    }

    @Test
    fun `each segment converts on its own - no match crosses a segment's end (Conversion cpp, MaxMatchSegmentation cpp)`() {
        val c = converter(phrases = mapOf("bc" to "BC"), characters = mapOf("ab" to "X", "cd" to "Y"))
        // segments a | bc | d: neither ab nor cd is inside one
        assertEquals("aBCd", c.convert("abcd"))
        // unmatched characters gather into one segment, where ab does match
        assertEquals("Xz", c.convert("abz"))
    }

    @Test
    fun `the variants step runs on the first step's output, segment by segment (s2tw json)`() {
        val c = converter(characters = mapOf("里" to "裡"), variants = mapOf("裡" to "裏"))
        assertEquals("裏面", c.convert("里面"))
        // ab | c: the variant Bc would span the two
        assertEquals("ABc", converter(phrases = mapOf("ab" to "AB"), variants = mapOf("Bc" to "Z")).convert("abc"))
    }

    @Test
    fun `a NUL ends the text (MaxMatchSegmentation cpp - the loop stops at it)`() {
        val c = converter(characters = mapOf("后" to "後"))
        assertEquals("後", c.convert("后\u0000后"))
        assertEquals("", c.convert("\u0000"))
        assertEquals("", c.convert(""))
    }

    @Test
    fun `a character is a code point, a lone surrogate one character`() {
        val c = converter(characters = mapOf("𠀀" to "A", "后" to "後"), phrases = mapOf("😀后" to "E"))
        assertEquals("A後", c.convert("𠀀后"))
        assertEquals("E", c.convert("😀后"))
        assertEquals("\ud800後\udc00", c.convert("\ud800后\udc00"))
    }

    @Test
    fun `the data file reads back what was written, keys in UTF-16 order`() {
        val dicts = OpenCCData.read(ByteBuffer.wrap(OpenCCData.write(listOf(listOf("b" to "2", "a" to "1", "𠀀" to "3", "ｚ" to "4"), emptyList()))))
        assertEquals(2, dicts.size)
        assertEquals(4, dicts[0].size)
        assertEquals(2, dicts[0].maxKeyLength)
        val i = dicts[0].matchPrefix("x𠀀", 1, 3)
        assertEquals("3", dicts[0].value(i))
        assertEquals("ｚ sorts before the surrogates as a char", "4", dicts[0].value(dicts[0].matchPrefix("ｚ", 0, 1)))
        assertEquals(-1, dicts[1].matchPrefix("a", 0, 1))
        // a longer key that fails falls back to the shorter one matched; the run narrows past keys of other lengths
        val back = OpenCCData.read(ByteBuffer.wrap(OpenCCData.write(listOf(listOf("a" to "1", "abc" to "3", "abcde" to "5")))))[0]
        assertEquals("1", back.value(back.matchPrefix("abd", 0, 3)))
        assertEquals("3", back.value(back.matchPrefix("abcdx", 0, 5)))
        assertThrows(IllegalArgumentException::class.java) { OpenCCData.write(listOf(listOf("a" to "1", "a" to "2"))) }
        assertThrows(IllegalArgumentException::class.java) { OpenCCData.read(ByteBuffer.wrap(ByteArray(12))) }
    }

    @Test
    fun `dictionary text takes the first value, skips comments and lines with no tab (Lexicon cpp 27-98)`() {
        val text = "# Open Chinese Convert (OpenCC) Dictionary\n\n干\t幹 乾 干\n后\t後\nno tab\n"
        assertEquals(listOf("干" to "幹", "后" to "後"), OpenCCData.parse(text))
    }

    companion object {
        fun converter(
            phrases: Map<String, String> = emptyMap(),
            characters: Map<String, String> = emptyMap(),
            variants: Map<String, String> = emptyMap(),
        ): ChineseConverter = ChineseConverter.s2tw(
            OpenCCData.read(ByteBuffer.wrap(OpenCCData.write(listOf(phrases, characters, variants).map { it.toList() }))),
        )
    }
}
