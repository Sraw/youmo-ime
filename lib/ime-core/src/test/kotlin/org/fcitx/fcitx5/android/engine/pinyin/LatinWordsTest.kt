/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.pinyin

import org.fcitx.fcitx5.android.engine.data.PinyinData
import org.fcitx.fcitx5.android.engine.data.PinyinDataBuilder
import org.fcitx.fcitx5.android.engine.data.syl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import kotlin.random.Random

class LatinWordsTest {

    private fun letters(word: String) = syl(*word.uppercase().map { it.toString() }.toTypedArray())

    private val latin = LatinWords.of(
        PinyinData.load(
            ByteBuffer.wrap(
                PinyinDataBuilder()
                    .unigram("<unk>", -7f, 0f)
                    .entry("app", letters("app"))
                    .entry("Apple", letters("apple"))
                    .entry("A", letters("a"))
                    .entry("A股", syl("A", "gu"))
                    .entry("QQ群", syl("Q", "Q", "qun"))
                    .build().toByteArray(),
            ),
        ).dictionary,
    )

    private fun at(input: String, at: Int) = ArrayList<Int>().also { found -> latin.forEachAt(input, at) { found += it } }

    @Test
    fun eachWordOfTwoLettersOrMoreSpeltInLettersAloneIsFoundFromWhereItStarts() {
        // app and apple: not A, nor A股 and QQ群, spelt with a syllable that is no letter
        assertEquals(2, latin.size)
        assertEquals(listOf(3, 5), at("apple", 0))
        assertEquals(listOf(3), at("woappde", 2))
        assertEquals(emptyList<Int>(), at("a", 0))
        assertEquals(emptyList<Int>(), at("agu", 0))
        assertEquals(emptyList<Int>(), at("qq", 0))
        assertEquals(listOf(3), at("app'le", 0))
        assertEquals(emptyList<Int>(), at("Apple", 0))
    }

    @Test
    fun theUsersWordsAreSpeltFromTwoLettersTillALetterStops() {
        val user = UserLatinWords()
        user.set(setOf("a", "ab", "abc", "xyz"))
        fun at(input: String, from: Int) = ArrayList<Int>().also { l -> user.forEachAt(input, from) { l += it } }
        // one letter is no word; each word the input spells from there, shortest first
        assertEquals(listOf(2, 3), at("abcd", 0))
        assertEquals(listOf(3), at("qxyz", 1))
        assertEquals(listOf(2), at("ab'c", 0))
        assertEquals(emptyList<Int>(), at("Abc", 0))
        user.set(setOf("xy"))
        assertEquals(setOf("xy"), user.words)
        assertEquals(emptyList<Int>(), at("abc", 0))
        assertEquals(listOf(2), at("xyz", 0))
    }

    @Test
    fun editingTheCustomKeyboardKeepsTheBuiltInKeyboardsTries() {
        val before = NineKeyLetters.custom(listOf("abc", "def", "ghi", "jkl", "mno", "pqrs", "tuv", "wxyz", "a"))
        val after = NineKeyLetters.custom(listOf("abc", "def", "ghi", "jkl", "mno", "pqrs", "tuv", "wxyz", "b"))
        val builtIn = listOf(NineKeyLetters.STANDARD, NineKeyLetters.G14, NineKeyLetters.G18)
        for (keys in builtIn + before + after) latin.warmUp(keys)
        for (keys in builtIn + after) assertTrue(keys.tag(), latin.keyTrieBuilt(keys))
    }

    @Test
    fun aKeyboardWithLettersOnSeveralKeysTypesEveryWordByItsFirstKeysTheTrieBounded() {
        val random = Random(1)
        val words = List(3000) { String(CharArray(8) { 'a' + random.nextInt(26) }) }.distinct()
        val builder = PinyinDataBuilder().unigram("<unk>", -7f, 0f)
        words.forEach { builder.entry(it, letters(it)) }
        val many = LatinWords.of(PinyinData.load(ByteBuffer.wrap(builder.build().toByteArray())).dictionary)
        // each letter on three keys: 64 typings of each word, millions of nodes unbounded
        val three = NineKeyLetters.custom(List(26) { k -> String(CharArray(3) { 'a' + (k + it) % 26 }) })
        fun found(typed: String) = ArrayList<Int>().also { l -> many.forEachAtKeys(typed, 0, three) { l += it } }.contains(typed.length)
        val size = many.keyTrieSize(three)
        assertTrue("$size nodes", size in LatinWords.MAX_KEY_NODES..LatinWords.MAX_KEY_NODES + 64 * 8)
        val byFirstKeys = words.filterNot { w -> found(String(CharArray(w.length) { three.key(w[it]) })) }
        assertEquals("not typed by their first keys", emptyList<String>(), byFirstKeys)
        // the other keys still type the words the bound leaves room for
        assertTrue(found(three.typings(words.sorted().first()).last()))
        // a letter typed as itself stands for its first key: every word its keys type is still typed with one fixed
        val lostWhenFixed = words.flatMap { w ->
            three.typings(w).filter(::found).flatMap { t ->
                w.indices.map { p -> t.substring(0, p) + w[p].uppercaseChar() + t.substring(p + 1) }.filterNot(::found)
            }
        }
        assertEquals(emptyList<String>(), lostWhenFixed)
        // a keyboard with each letter on one key is far below it
        assertTrue(many.keyTrieSize(NineKeyLetters.STANDARD) < LatinWords.MAX_KEY_NODES / 2)
    }
}
