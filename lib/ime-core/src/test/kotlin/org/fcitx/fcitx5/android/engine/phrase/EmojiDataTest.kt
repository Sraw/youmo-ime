/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.phrase

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EmojiDataTest {

    @Test
    fun theGroupsAndWordsReadAsWritten() {
        val groups = listOf("smileys" to listOf("😀", "☺️"), "flags" to listOf("🏁"))
        val words = mapOf("哈哈" to listOf("😃", "😄"), "晚安" to listOf("😴"), "空" to emptyList())
        val text = EmojiData.format(groups, words, header = "a header\nof two lines")
        assertTrue(text.startsWith("# a header\n# of two lines\n@smileys\t😀 ☺️\n"))
        val data = EmojiData.read(text)
        assertEquals(groups, data.groups)
        assertEquals(listOf("😃", "😄"), data.of("哈哈"))
        assertEquals(listOf("😴"), data.of("晚安"))
        // a word with none is not written
        assertEquals(2, data.size)
        assertEquals(emptyList<String>(), data.of("空"))
        assertEquals(emptyList<String>(), data.of("没有"))
    }

    @Test
    fun whatIsNoWordOrGroupIsSkippedAndAWordKeepsItsFirstEmojiOnce() {
        val many = (1..12).joinToString(" ") { "e$it" }
        val data = EmojiData.read("# 哈哈\t😃\nno tab\n\t😃\n超过八个字的一个长词\t😃\n笑\t😄  😄 😆\n多\t$many\n")
        assertEquals(listOf("😄", "😆"), data.of("笑"))
        assertEquals(EmojiData.MAX_EMOJI, data.of("多").size)
        assertEquals(2, data.size)
        assertEquals(emptyList<Pair<String, List<String>>>(), data.groups)
        assertEquals(0, EmojiData.EMPTY.size)
    }

    private val searched = EmojiData.read(
        EmojiData.format(
            emptyList(),
            // in the order of their use, as the tool writes them
            linkedMapOf("笑" to listOf("😄"), "笑脸" to listOf("😃", "😄"), "笑哭" to listOf("😂"), "点赞" to listOf("👍"), "小" to listOf("🤏"), "无读音" to listOf("❓")),
            pinyin = mapOf("笑" to "xiao", "笑脸" to "xiao lian", "笑哭" to "xiao ku", "点赞" to "dian zan", "小" to "xiao", "无读音" to null),
        ),
    )

    @Test
    fun aSearchFindsWordsTypedInFullThenTheirStartThenEachSyllablesStart() {
        // in full first, fewer syllables first, then as written; then the words xiao starts, each emoji once
        assertEquals(listOf("😄", "🤏", "😃", "😂"), searched.search("xiao"))
        // the last syllable in part: the start of 笑脸
        assertEquals(listOf("😃", "😄"), searched.search("xiaol"))
        assertEquals(listOf("😃", "😄"), searched.search("XiaoLian"))
        // each syllable by its start
        assertEquals(listOf("👍"), searched.search("dz"))
        assertEquals(listOf("👍"), searched.search("d'zan"))
        assertEquals(listOf("👍"), searched.search("dianz"))
        assertEquals(listOf("😄", "🤏"), searched.search("xiao", limit = 2))
        // past a word, or no letter: none
        assertEquals(emptyList<String>(), searched.search("dianzana"))
        assertEquals(emptyList<String>(), searched.search("zan"))
        assertEquals(emptyList<String>(), searched.search("12"))
        assertEquals(emptyList<String>(), EmojiData.EMPTY.search("xiao"))
    }

    @Test
    fun aWordsPinyinIsKeptAsWrittenAndReadBack() {
        val text = EmojiData.format(emptyList(), mapOf("点赞" to listOf("👍")), pinyin = mapOf("点赞" to "dian zan"))
        assertEquals("点赞\t👍\tdian zan\n", text)
        assertEquals(listOf("👍"), EmojiData.read(text).of("点赞"))
    }
}
