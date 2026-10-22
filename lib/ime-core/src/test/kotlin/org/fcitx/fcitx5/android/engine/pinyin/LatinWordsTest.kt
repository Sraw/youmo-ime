/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.pinyin

import org.fcitx.fcitx5.android.engine.data.PinyinData
import org.fcitx.fcitx5.android.engine.data.PinyinDataBuilder
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer

class LatinWordsTest {

    private fun syl(vararg s: String) = s.map { Syllables.id(it) }.toIntArray()

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
        // a word ends where the letters do
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
        // a word ends where the letters do
        assertEquals(listOf(2), at("ab'c", 0))
        assertEquals(emptyList<Int>(), at("Abc", 0))
        // a new set replaces the words before it
        user.set(setOf("xy"))
        assertEquals(setOf("xy"), user.words)
        assertEquals(emptyList<Int>(), at("abc", 0))
        assertEquals(listOf(2), at("xyz", 0))
    }
}
