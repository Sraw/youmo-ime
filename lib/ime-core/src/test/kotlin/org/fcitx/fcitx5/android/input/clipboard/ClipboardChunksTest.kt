/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.clipboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipboardChunksTest {

    private fun split(text: String, vararg words: Pair<String, IntArray>) = ClipboardChunks.split(text, words.toMap())

    @Test
    fun anEmailIsNameAtAndDomain() {
        assertEquals(listOf("li.ming+x", "@", "mail.example.com"), split("li.ming+x@mail.example.com"))
    }

    @Test
    fun aUrlIsSchemeHostEachPathPartQueryAndFragment() {
        assertEquals(
            listOf("https://", "example.com:8080", "/docs", "/a-b", "/", "?q=1&r=a/b", "#top"),
            split("https://example.com:8080/docs/a-b/?q=1&r=a/b#top"),
        )
        assertEquals(listOf("www.example.com", "/x"), split("www.example.com/x"))
        assertEquals(listOf("http://", "a.cn"), split("http://a.cn"))
    }

    @Test
    fun aUrlEndsWhereTheSentenceGoesOn() {
        assertEquals(listOf("看", "https://", "a.cn", "/p", "。"), split("看https://a.cn/p。"))
        assertEquals(listOf("see", "https://", "a.cn", "/p", "."), split("see https://a.cn/p."))
        assertEquals(listOf("(", "mail", "me", ":", "a", "@", "b.org", ")"), split("(mail me: a@b.org)"))
    }

    @Test
    fun chineseGoesAsTheModelSplitsIt() {
        assertEquals(listOf("今天", "天气", "很好"), split("今天天气很好", "今天天气很好" to intArrayOf(0, 2, 4, 6)))
        assertEquals(listOf("今天天气很好"), ClipboardChunks.hanRuns("今天天气很好"))
    }

    @Test
    fun chineseTheModelHasNotSplitGoesCharByChar() {
        assertEquals(listOf("你", "好"), split("你好"))
        // boundaries that do not cover the run are not used
        assertEquals(listOf("你", "好"), split("你好", "你好" to intArrayOf(0, 1)))
    }

    @Test
    fun englishWordsNumbersAndMarksAreEachTheirOwn() {
        assertEquals(
            listOf("Don't", "pay", "3.14", "or", "1,000", "!", "abc", "123", "well-known"),
            split("Don't pay 3.14 or 1,000! abc123 well-known"),
        )
    }

    @Test
    fun mixedScriptsAreCutByScriptFirst() {
        assertEquals(
            listOf("我", "用", "iPhone", "15", "拍", "的", "，", "好看", "😀"),
            split("我用iPhone15拍的，好看😀", "我用" to intArrayOf(0, 1, 2), "拍的" to intArrayOf(0, 1, 2), "好看" to intArrayOf(0, 2)),
        )
        assertEquals(listOf("我用", "拍的", "好看"), ClipboardChunks.hanRuns("我用iPhone15拍的，好看😀"))
    }

    @Test
    fun spacesAreNoPieceAndNothingIsNothing() {
        assertEquals(emptyList<String>(), split(""))
        assertEquals(emptyList<String>(), split(" \n\t "))
        assertEquals(listOf("a", "b"), split("  a \n b "))
    }

    @Test
    fun theSameRunIsAskedOnce() {
        assertEquals(listOf("好"), ClipboardChunks.hanRuns("好 a 好"))
        assertEquals(emptyList<String>(), ClipboardChunks.hanRuns("https://a.cn/x a@b.cn"))
    }

    @Test
    fun aGraphemeClusterIsOnePieceAndNothingInvisibleIsOne() {
        assertEquals(listOf("生日", "快乐", "🎂", "👨\u200D👩\u200D👧"), split("生日快乐🎂👨\u200D👩\u200D👧", "生日快乐" to intArrayOf(0, 2, 4)))
        assertEquals(listOf("👍🏽", "🇨🇳", "❤\uFE0F", "1\uFE0F\u20E3"), split("👍🏽🇨🇳❤\uFE0F 1\uFE0F\u20E3"))
        assertEquals(listOf("a", "b"), split("a\u200Bb\u200D"))
        assertEquals(listOf("cafe\u0301"), split("cafe\u0301"))
    }

    @Test
    fun theModelsEndsInsideASurrogatePairAreDropped() {
        // 王𬭚的: 𬭚 is two chars, the model's unknown char by char
        val text = "王\uD872\uDF5A的"
        assertEquals(listOf("王", "\uD872\uDF5A", "的"), split(text, text to intArrayOf(0, 1, 2, 3, 4)))
        assertEquals(listOf("王", "\uD872\uDF5A", "的"), split(text))
    }

    @Test
    fun aCutEndsBetweenClusters() {
        assertEquals("ab", ClipboardChunks.cut("ab", 5))
        assertEquals("a", ClipboardChunks.cut("a😀", 2))
        assertEquals("a😀", ClipboardChunks.cut("a😀b", 3))
        assertEquals("a", ClipboardChunks.cut("a👍🏽", 4))
    }

    @Test
    fun linksNeedABoundaryBeforeThemAndKeepAnOpenedParen() {
        assertEquals(listOf("Visithttps", ":", "/", "/", "a", ".", "cn"), split("Visithttps://a.cn"))
        assertEquals(listOf("https://", "en.wikipedia.org", "/wiki", "/Foo_(bar)"), split("https://en.wikipedia.org/wiki/Foo_(bar)"))
        assertEquals(listOf("(", "https://", "a.cn", ")"), split("(https://a.cn)"))
        assertEquals(listOf("https://", "user:pw@h.cn", "/a%20b"), split("https://user:pw@h.cn/a%20b"))
        // ASCII only after the scheme: an IDN's Chinese goes as Chinese
        assertEquals(listOf("https", ":", "/", "/", "例", "子", ".", "cn"), split("https://例子.cn"))
    }

    @Test
    fun aLongTokenWithoutAtIsQuick() {
        val token = "a".repeat(20_000)
        val start = System.nanoTime()
        assertEquals(listOf(token), split(token))
        // milliseconds when linear; a scan per character would take far past the bound, loose for a busy CI
        val ms = (System.nanoTime() - start) / 1_000_000
        assertTrue("${ms}ms", ms < 2_000)
    }

    private fun chunk(text: String, piece: String, nth: Int = 0) = ClipboardChunks.chunks(text).filter { it.text == piece }[nth]

    @Test
    fun neighboursJoinWithoutTheInvisibleBetweenThem() {
        // the zero-width space between foo and bar is no piece and not committed either
        val text = "foo\u200Bbar a\u200D b"
        val all = ClipboardChunks.chunks(text)
        assertEquals(listOf("foo", "bar", "a", "b"), all.map { it.text })
        assertEquals("foobar", ClipboardChunks.join(text, all, all.subList(0, 2)))
        assertEquals("a b", ClipboardChunks.join(text, all, all.subList(2, 4)))
    }

    @Test
    fun picksJoinInTapOrderWithTheTextBetweenNeighbours() {
        val text = "Hello world 你好 1.5 kg"
        val all = ClipboardChunks.chunks(text)
        fun join(vararg pieces: String) = ClipboardChunks.join(text, all, pieces.map { chunk(text, it) })
        // neighbours in the text: the space between kept
        assertEquals("Hello world", join("Hello", "world"))
        // tapped out of order: Latin pairs get one space, others none
        assertEquals("world Hello", join("world", "Hello"))
        assertEquals("你Hello", join("你", "Hello"))
        assertEquals("1.5 kg", join("1.5", "kg"))
        assertEquals("kg 1.5", join("kg", "1.5"))
        assertEquals("world", join("world"))
        assertEquals("", ClipboardChunks.join(text, all, emptyList()))
    }

    @Test
    fun neighboursWithNothingBetweenJoinDirectly() {
        val text = "a@b.cn"
        val all = ClipboardChunks.chunks(text)
        assertEquals("a@b.cn", ClipboardChunks.join(text, all, all))
        assertEquals("b.cn a", ClipboardChunks.join(text, all, listOf(all[2], all[0])))
    }

    @Test
    fun aWordEndingInACombiningMarkIsStillSpacedFromTheNext() {
        val text = "bar cafe\u0301"
        val all = ClipboardChunks.chunks(text)
        // picked out of order: café then bar, the é spelled e and U+0301
        assertEquals("cafe\u0301 bar", ClipboardChunks.join(text, all, listOf(all[1], all[0])))
    }
}
