/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings

import org.fcitx.fcitx5.android.engine.host.Engines.UserWord
import org.junit.Assert.assertEquals
import org.junit.Test

/** A list of the user's words is searched by the word or its pinyin, those that start with the query first. */
class UserWordListFragmentTest {

    private fun word(text: String, pinyin: String) = UserWord(text, pinyin, UserWord.Kind.ADDED)

    private fun found(words: List<UserWord>, query: String) = searchWords(words, searchKey(query)).map { it.text }

    @Test
    fun thePinyinIsMatchedHoweverItIsSpacedOrCased() {
        assertEquals("nihao", searchKey(" Ni'Hao "))
        assertEquals("nihao", searchKey("ni hao"))
        assertEquals(listOf("你好"), found(listOf(word("你好", "ni hao"), word("世界", "shi jie")), "NI'hao"))
    }

    @Test
    fun thoseThatStartWithTheQueryComeFirstInTheListsOrder() {
        val words = listOf(
            word("我你好", "wo ni hao"),
            word("你好", "ni hao"),
            word("说你好", "shuo ni hao"),
            word("你好吗", "ni hao ma"),
            word("世界", "shi jie"),
        )
        assertEquals(listOf("你好", "你好吗", "我你好", "说你好"), found(words, "nihao"))
        // the word itself too, a letter word in any case
        assertEquals(listOf("你好", "你好吗", "我你好", "说你好"), found(words, "你好"))
        assertEquals(listOf("WiFi密码"), found(listOf(word("WiFi密码", "wifi mi ma")), "WIFI密"))
    }

    // the query loses its `'`, so the word must too: don't typed in whole found nothing
    @Test
    fun anEnglishWordIsFoundWithItsApostropheOrWithout() {
        val words = listOf("don't", "I'm", "done").map { UserWord(it, "", UserWord.Kind.LEARNED) }
        assertEquals(listOf("don't"), found(words, "don't"))
        assertEquals(listOf("don't"), found(words, "DONT"))
        assertEquals(listOf("don't", "done"), found(words, "don"))
        assertEquals(listOf("I'm"), found(words, "i'm"))
    }
}
