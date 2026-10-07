/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.session

import org.fcitx.fcitx5.android.engine.data.PinyinData
import org.fcitx.fcitx5.android.engine.data.PinyinDataBuilder
import org.fcitx.fcitx5.android.engine.pinyin.LatinWords
import org.fcitx.fcitx5.android.engine.pinyin.PinyinSegmenter
import org.fcitx.fcitx5.android.engine.session.Action.Key
import org.fcitx.fcitx5.android.engine.session.Action.Select
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

class PinyinSessionLatinTest {

    private fun letters(word: String) = word.uppercase().map { it.toString() }.toTypedArray()

    private val data = PinyinData.load(
        ByteBuffer.wrap(
            PinyinDataBuilder()
                .unigram("<unk>", -7f, 0f)
                .unigram("我", -2f, 0f)
                .unigram("的", -1.5f, 0f)
                .unigram("爱", -3f, 0f)
                .unigram("iPhone", -5f, 0f)
                .unigram("app", -4.5f, 0f)
                .unigram("APP", -4.7f, 0f)
                .unigram("AI", -5.5f, 0f)
                .bigram("我", "的", -0.5f, 0f)
                .entry("我", syl("wo"))
                .entry("的", syl("de"))
                .entry("爱", syl("ai"))
                .entry("iPhone", syl(*letters("iphone")))
                .entry("app", syl(*letters("app")))
                .entry("APP", syl(*letters("app")))
                .entry("AI", syl(*letters("ai")))
                .build().toByteArray(),
        ),
    )

    private val latin = LatinWords.of(data.dictionary)

    private fun session(latin: LatinWords? = this.latin) = PinyinSession(data, PinyinSegmenter(latin = latin))

    private fun Session.type(keys: String): Snapshot = keys.map { apply(Key(it)) }.last()

    @Test
    fun theWordsSpeltInLettersAreFoundWhereTheyAreTyped() {
        assertEquals(3, latin.size)
        fun at(input: String, at: Int) = ArrayList<Int>().also { found -> latin.forEachAt(input, at) { found += it } }
        assertEquals(listOf(6), at("wodeiphone", 4))
        assertEquals(emptyList<Int>(), at("wodeiphone", 0))
        // what auto-capitalisation touched is pinyin's to read as letters
        assertEquals(emptyList<Int>(), at("Iphone", 0))
    }

    @Test
    fun aLatinWordTypedInLowerCaseIsOfferedAsWritten() {
        val s = session().type("wodeiphone")
        assertEquals("我的iPhone", s.candidates.first())
        // shown as typed, not a letter at a time
        assertEquals("wo de iphone", s.preedit)
        assertEquals("我的iPhone", session().also { it.type("wodeiphone") }.apply(Select(0)).commit)
        assertEquals("iPhone", session().type("iphone").candidates.first())
        // without the Latin words: what it was
        assertNotEquals("iPhone", session(latin = null).type("iphone").candidates.first())
    }

    @Test
    fun eachWritingIsACandidateAndPinyinComesFirstWhereItIsCommoner() {
        assertEquals(listOf("app", "APP"), session().type("app").candidates.take(2))
        val ai = session().type("ai").candidates
        assertEquals("爱", ai.first())
        assertTrue("AI" in ai)
    }
}
