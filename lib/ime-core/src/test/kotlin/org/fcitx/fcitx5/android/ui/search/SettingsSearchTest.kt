/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.search

import org.fcitx.fcitx5.android.ui.search.SettingsSearch.Entry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsSearchTest {

    private val search = SettingsSearch(
        listOf(
            Entry("按键声音", "按键时播放声音", listOf("虚拟键盘"), 1),
            Entry("长按空格", "空格键长按行为", listOf("虚拟键盘"), 2),
            Entry("空格键上滑", "", listOf("虚拟键盘"), 3),
            Entry("中英混输", "拼音里直接打英文单词", listOf("输入法", "拼音"), 4),
            Entry("中英混输", "拼音里直接打英文单词", listOf("输入法", "拼音"), 5),
            Entry("Sound on keypress", "Play a sound", listOf("Virtual keyboard"), 6),
            Entry("", "untitled", emptyList(), 7),
        )
    )

    private fun found(query: String) = search.find(query).map { it.target }

    @Test
    fun titlesStartingWithTheQueryComeFirstThenTitlesThenTheRest() {
        // 空格键上滑 starts with it; 长按空格 has it in its title; 按键声音 only under it
        assertEquals(listOf(3, 2), found("空格"))
        // in the titles of 按键声音 and 空格键上滑, in the pages' order; 长按空格 only says it below
        assertEquals(listOf(1, 3, 2), found("键"))
    }

    @Test
    fun everyWordMustBeSomewhereAndCaseDoesNotMatter() {
        assertEquals(listOf(6), found("SOUND keyboard"))
        assertEquals(listOf(4), found("拼音　英文"))
        assertTrue(found("拼音 声音").isEmpty())
    }

    @Test
    fun theSameSettingTwiceAndUntitledOnesAreLeftOut() {
        assertEquals(listOf(4), found("中英"))
        assertTrue(found("untitled").isEmpty())
        assertTrue(found("  ").isEmpty())
    }

    @Test
    fun thePagesAreSearchedToo() {
        assertEquals(listOf(1, 2, 3), found("虚拟"))
    }
}
