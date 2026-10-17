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
}
