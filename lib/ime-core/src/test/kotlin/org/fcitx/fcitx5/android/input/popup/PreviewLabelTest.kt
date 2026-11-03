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

    // team-lead, 0.5.1: the letter in the bubble grows and shrinks with the keys' text, as the words do
    @Test
    fun aCharacterIsScaledAsTheKeys() {
        assertEquals(23f, PreviewLabel.symbolSize(1f), 0f)
        assertEquals(23f * 0.9f, PreviewLabel.symbolSize(0.9f), 1e-4f)
    }

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
        assertEquals(PreviewLabel.MAX_TEXT, longest)
        val emoji = "a".repeat(PreviewLabel.MAX_TEXT - 1) + "😀"
        assertEquals("not half an emoji", "a".repeat(PreviewLabel.MAX_TEXT - 1), PreviewLabel.fit(emoji, 1f, 1f, 1000f, ems).text)
    }

    @Test
    fun noRoomAtAllIsTheEllipsis() {
        assertEquals(Fit("…", 12f), PreviewLabel.fit("全选", 16f, 12f, 1f, ems))
    }
}
