/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.learn

import org.junit.Assert.assertEquals
import org.junit.Test

/** [LastWord] and [Simplifier]: the word before, the same whether committed here or read from the app. */
class LastWordTest {
    private val words = setOf("汪小明", "小明", "点了", "你好", "手机", "覆盖")
    private val simplifier = openccSimplifier
    private val last = LastWord({ it in words }, simplifier::simplify)

    @Test
    fun theLongestWordAtTheEnd() {
        assertEquals("汪小明", last.of("我叫汪小明"))
        assertEquals("小明", last.of("是小明"))
        assertEquals("明", LastWord({ false }).of("小明"))
        assertEquals("你好", last.of("你好 "))
    }

    // as the model reads the run (uat-triage-pinyin T9-54): its last word, or one of the user's ending it at a word's start
    @Test
    fun withASplitTheLastWordAsReadOrTheUsersEndingIt() {
        // each character a word but 小明 and 了他, read 了｜他
        fun split(run: String): IntArray {
            val ends = arrayListOf(0)
            var i = 0
            while (i < run.length) {
                i += if (run.startsWith("小明", i)) 2 else 1
                ends += i
            }
            return ends.toIntArray()
        }
        val read = LastWord({ it in words || it == "了他" }, split = ::split, isMine = { it == "汪小明" || it == "明天好" })
        assertEquals("他", read.of("他点了他"))
        assertEquals("汪小明", read.of("我叫汪小明"))
        assertEquals("小明", read.of("是小明"))
        // the user's, but starting inside a word read: not the 前文
        assertEquals("好", read.of("小明天好"))
    }

    @Test
    fun noneAfterPunctuationDigitsEmojiAndAddresses() {
        for (text in listOf("你好，", "你好\n", "123", "你好😀", "a@b.com", "https://x.cn/a", "www.youmo", "example.com", ""))
            assertEquals(text, "", last.of(text))
    }

    @Test
    fun lettersAsTyped() {
        assertEquals("iPhone", last.of("我的iPhone"))
        assertEquals("ok", last.of("说 ok"))
        assertEquals("v2", last.of("版本v2"))
    }

    @Test
    fun traditionalTextIsReadSimplified() {
        assertEquals("点了", last.of("我點了"))
        assertEquals("手机", last.of("我的手機"))
        assertEquals("覆盖", last.of("已覆盖"))
        assertEquals("你好", Simplifier.NONE.simplify("你好"))
    }
}
