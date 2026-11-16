/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.search

import org.fcitx.fcitx5.android.ui.search.SettingsSearch.Entry
import org.fcitx.fcitx5.android.ui.search.SettingsSearch.Kind
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
        // 空格键上滑 starts with it; 长按空格 has it in its title; 按键声音 has it nowhere
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

    // 061-settings-search.md §4.5's example: 笔画筛选 itself, a title that starts with it, then what is also called so, the shorter first
    private val strokes = SettingsSearch(
        listOf(
            Entry("在键上划", "", listOf("通用输入"), 1, "pref:key_drawing"),
            Entry("笔画：横、竖、撇、点、折", "", listOf("按键"), 2, "cmd:stroke", Kind.COMMAND, listOf("笔画筛选", "笔顺")),
            Entry("滑行输入", "", listOf("通用输入", "在键上划"), 3, "value:key_drawing=Glide", Kind.VALUE),
            Entry("笔画筛选", "", listOf("通用输入", "在键上划"), 4, "value:key_drawing=StrokeFilter", Kind.VALUE),
            Entry("笔画筛选灵敏度", "", listOf("通用输入"), 5, "pref:stroke_lift"),
            Entry("首页至少几个单字", "", listOf("通用输入"), 6, "pref:StrokeSingles", keywords = listOf("笔画筛选")),
            Entry("关于", "", emptyList(), 7, "page:About"),
        )
    )

    @Test
    fun theQueryItselfThenTitlesStartingWithItOrOtherNamesThatAreIt() {
        val found = strokes.find("笔画筛选")
        assertEquals(listOf(4, 5, 6, 2), found.map { it.target })
        assertEquals(listOf(null, null, "笔画筛选", "笔画筛选"), found.map { it.alias })
    }

    @Test
    fun anOtherNameWithEveryWordComesBeforeWhatIsOnlyInThePath() {
        // 笔顺 only in the command's other names: found by it, and said so
        assertEquals(listOf(2), strokes.find("笔顺").map { it.target })
        assertEquals("笔顺", strokes.find("笔顺").single().alias)
        // a part of an other name: the fourth tier, after a title that has it
        val search = SettingsSearch(
            listOf(
                Entry("夜里", "", listOf("外观"), 1, "pref:a", keywords = listOf("深色模式")),
                Entry("深色模式主题", "", listOf("外观"), 2, "pref:b"),
                Entry("主题", "深色", listOf("外观"), 3, "pref:c"),
            )
        )
        assertEquals(listOf(2, 1, 3), search.find("深色").map { it.target })
    }

    @Test
    fun aChoiceIsFoundOnlyByItsOwnName() {
        // 在键上划 is in their path, not their names: the setting alone
        assertEquals(listOf(1), strokes.find("在键上划").map { it.target })
        assertEquals(listOf(3), strokes.find("滑行").map { it.target })
        // one word its own, the other its path's: found
        assertEquals(listOf(3), strokes.find("在键上划 滑行").map { it.target })
    }

    @Test
    fun theDevelopersPageIsATierLower() {
        val search = SettingsSearch(
            listOf(
                Entry("日志", "", listOf("开发者"), 1, "pref:log", demoted = true),
                Entry("日志记录方式", "", listOf("关于"), 2, "pref:other"),
            )
        )
        // the developer's exact title is a start's tier, and the shorter title first among those
        assertEquals(listOf(1, 2), search.find("日志").map { it.target })
        val after = SettingsSearch(
            listOf(
                Entry("记录日志", "", listOf("开发者"), 1, "pref:log", demoted = true),
                Entry("日志记录方式很长的名字", "", listOf("关于"), 2, "pref:other"),
            )
        )
        // a title with it, a tier below one starting with it, however short
        assertEquals(listOf(2, 1), after.find("日志").map { it.target })
    }

    @Test
    fun fullWidthLettersAreFoundAsTheirOwn() {
        assertEquals(listOf(6), found("ＳＯＵＮＤ"))
        assertEquals(listOf(6), found("Sound ON"))
    }

    @Test
    fun anIdIsOfferedOnceAndTwoIdsTwiceUnderOneName() {
        val search = SettingsSearch(
            listOf(
                Entry("九键", "", listOf("拼音"), 1, "value:pinyin_keyboard=NineKeys@pinyin", Kind.VALUE),
                Entry("九键", "", listOf("双拼"), 2, "value:shuangpin_keyboard=NineKeys@shuangpin", Kind.VALUE),
                Entry("九键 again", "", listOf("拼音"), 3, "value:pinyin_keyboard=NineKeys@pinyin", Kind.VALUE),
            )
        )
        assertEquals(listOf(1, 2), search.find("九键").map { it.target })
    }

    @Test
    fun aChoiceOfOneCharacterTwoLettersOrANumberIsWorthNone() {
        for (label in listOf("高", "关", "Up", "On", "1", "1.5", "20%", " 中 ", "")) assertFalse(label, SettingsSearch.isWorthAValue(label))
        for (label in listOf("关闭", "18 键", "Low", "an ↔ ang", "中（0.6 秒）", "全键盘")) assertTrue(label, SettingsSearch.isWorthAValue(label))
    }

    @Test
    fun aResultFoundByItsTitleNamesNoOtherName() {
        assertNull(strokes.find("首页").single().alias)
    }
}
