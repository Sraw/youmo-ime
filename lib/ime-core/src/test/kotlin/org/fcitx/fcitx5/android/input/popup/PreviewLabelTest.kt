/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.popup

import org.fcitx.fcitx5.android.input.popup.PreviewLabel.Fit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewLabelTest {

    // every character one em of the size, as a Chinese one is
    private val ems: (String, Float) -> Float = { s, size -> s.codePointCount(0, s.length) * size }

    // a phone's letter key 36 wide, the words 16 (no scale), the bubble's padding 6 a side
    private val room = PreviewLabel.room(keyWidth = 36f, size = 16f, padding = 6f)

    private fun fit(label: String) = PreviewLabel.fit(label, 16f, 12f, room, ems)

    @Test
    fun oneCharacterIsNoWordsTwoAre() {
        assertFalse(PreviewLabel.isWords("你"))
        assertFalse("an emoji's two chars are one character", PreviewLabel.isWords("😀"))
        assertFalse("its spaces left out", PreviewLabel.isWords(" @\n"))
        assertTrue(PreviewLabel.isWords("全选"))
        assertTrue(PreviewLabel.isWords("ok"))
    }

    @Test
    fun theRoomIsFiveOfTheWordsOrTheKeysFaceWhereWider() {
        assertEquals(80f, room)
        assertEquals("landscape's wide key", 100f - 12f, PreviewLabel.room(100f, 16f, 6f))
        assertEquals("紧凑's words smaller, the room too", 72f, PreviewLabel.room(36f, 14.4f, 6f))
    }

    @Test
    fun wordsThatFitAreAtTheirSize() {
        assertEquals(Fit("全选", 16f), fit("全选"))
        assertEquals(Fit("快速输入", 16f), fit("快速输入"))
        assertEquals("five, the most at the size", Fit("光标到开头", 16f), fit("光标到开头"))
    }

    @Test
    fun longerOnesShrinkDownToTheLeast() {
        // six in five's room: 16 * 5 / 6
        assertEquals(Fit("恢复刚清除的", 80f / 6), fit("恢复刚清除的"))
        assertEquals("seven would be below the least: cut", Fit("一二三四五…", 12f), fit("一二三四五六七"))
    }

    @Test
    fun longerStillAreCutAtTheLeast() {
        // at 12 the room takes 80 / 12 = 6.67 characters: five and the ellipsis
        assertEquals(Fit("一二三四五…", 12f), fit("一二三四五六七八九"))
        assertEquals("the space before the cut dropped", Fit("我的邮箱是…", 12f), fit("我的邮箱是 someone@example.com"))
        assertEquals("an emoji kept whole", Fit("一二三四😀…", 12f), fit("一二三四😀😀😀😀"))
    }

    @Test
    fun aTextsLinesAreOneLine() {
        assertEquals(Fit("你好 再见", 16f), fit("  你好\n\n再见 "))
    }

    @Test
    fun aVeryLongTextIsMeasuredOnlyAsFarAsItCouldShow() {
        var longest = 0
        PreviewLabel.fit("字".repeat(200), 16f, 12f, room) { s, size -> longest = maxOf(longest, s.length); ems(s, size) }
        assertEquals("its first MAX_TEXT and the ellipsis", PreviewLabel.MAX_TEXT + 1, longest)
        // review L4: cut there, told by the ellipsis however much room there is
        val emoji = "a".repeat(PreviewLabel.MAX_TEXT - 1) + "😀"
        assertEquals("not half an emoji, the cut told", "a".repeat(PreviewLabel.MAX_TEXT - 1) + "…", PreviewLabel.fit(emoji, 1f, 1f, 1000f, ems).text)
        val forty = "字".repeat(PreviewLabel.MAX_TEXT)
        assertEquals("all of it there: no ellipsis", forty, PreviewLabel.fit(forty, 1f, 1f, 1000f, ems).text)
        assertEquals("only spaces past it: nothing cut", forty, PreviewLabel.fit("$forty  \n ", 1f, 1f, 1000f, ems).text)
        assertTrue("a cut one is words", PreviewLabel.isWords(" ".repeat(PreviewLabel.MAX_TEXT) + "字字"))
    }

    // review L2: measured by UTF-16 units, as a paint may weigh a pair: a cut never leaves half of one
    private val units: (String, Float) -> Float = { s, size -> s.length * size }

    private fun String.whole(): Boolean = indices.none { i ->
        (this[i].isHighSurrogate() && (i + 1 >= length || !this[i + 1].isLowSurrogate())) ||
            (this[i].isLowSurrogate() && (i == 0 || !this[i - 1].isHighSurrogate()))
    }

    @Test
    fun aCutNeverSplitsASurrogatePair() {
        for (roomUnits in 2..12) {
            for (label in listOf("一二三😀😀😀😀😀😀", "😀😀😀😀😀😀😀😀", "a😀b😀c😀d😀e😀f😀")) {
                val got = PreviewLabel.fit(label, 1f, 1f, roomUnits.toFloat() - 0.5f, units).text
                assertTrue("$label in $roomUnits: $got", got.whole())
            }
        }
    }

    // review L3: a character as seen is one, however many code points: a symbol's size, never cut apart
    @Test
    fun aCharacterAsSeenIsOne() {
        for (one in listOf("❤️", "🇨🇳", "👍🏽", "👨‍👩‍👧", "e\u0301")) {
            assertFalse(one, PreviewLabel.isWords(one))
        }
        assertTrue(PreviewLabel.isWords("🇨🇳🇯🇵"))
        // at the least 80 / 12 = 6.67 units: the family (8) does not go in, nor any part of it
        val family = "👨‍👩‍👧"
        assertEquals(Fit("一二…", 12f), PreviewLabel.fit("一二$family$family$family", 16f, 12f, room, units))
        assertEquals("a flag's two halves together", Fit("一二三…", 12f), PreviewLabel.fit("一二三🇨🇳🇨🇳", 16f, 12f, room, units))
        assertEquals("a skin tone with its hand", Fit("一二三…", 12f), PreviewLabel.fit("一二三👍🏽👍🏽", 16f, 12f, room, units))
        // abcdé… is 7 units: the é goes whole, not its e kept without the accent
        assertEquals("an accent with its letter", Fit("abcd…", 12f), PreviewLabel.fit("abcde\u0301fgh", 16f, 12f, room, units))
        // the MAX_TEXT cut too: not inside the family that straddles it
        val straddling = "a".repeat(PreviewLabel.MAX_TEXT - 3) + family
        assertEquals("a".repeat(PreviewLabel.MAX_TEXT - 3) + "…", PreviewLabel.fit(straddling, 1f, 1f, 1000f, units).text)
    }

    @Test
    fun noRoomAtAllIsTheEllipsis() {
        assertEquals(Fit("…", 12f), PreviewLabel.fit("全选", 16f, 12f, 1f, ems))
    }
}
