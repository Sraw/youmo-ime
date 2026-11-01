/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.user.imports

import org.fcitx.fcitx5.android.engine.data.PinyinData
import org.fcitx.fcitx5.android.engine.data.PinyinDataBuilder
import org.fcitx.fcitx5.android.engine.pinyin.Syllables
import org.fcitx.fcitx5.android.engine.user.UserFixtures.entry
import org.fcitx.fcitx5.android.engine.user.UserFixtures.syl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.ByteBuffer

class WordTextTest {

    @Test
    fun aWordIsReadAsLibimeSpellsIt() {
        assertEquals(entry("你好", "ni", "hao"), WordText.entry("你好", "ni'hao"))
        assertEquals(entry("绿", "lv"), WordText.entry("绿", "lü"))
        assertEquals(entry("虐", "nve"), WordText.entry("虐", "nüe"))
        assertEquals(entry("略", "lve"), WordText.entry("略", "lue"))
        // no such syllable; a reading of another length; nothing read
        assertNull(WordText.entry("你", "nii"))
        assertNull(WordText.entry("你好", "ni"))
        assertNull(WordText.entry("你", ""))
        assertNull(WordText.entry("", "ni"))
    }

    @Test
    fun aLineOfTheUserDictionaryIsTextReadingAndCost() {
        assertEquals(entry("你好", "ni", "hao"), WordText.dictionaryEntry("你好 ni'hao 0"))
        assertEquals(entry("你好", "ni", "hao"), WordText.dictionaryEntry("\"你好\" ni'hao -1.5"))
        // as libime reads it: the cost may be left out, and a tab is whitespace like any
        assertEquals(entry("你好", "ni", "hao"), WordText.dictionaryEntry("你好 ni'hao"))
        assertEquals(entry("你好", "ni", "hao"), WordText.dictionaryEntry("  你好\tni'hao\t0\r"))
        assertNull(WordText.dictionaryEntry(""))
        assertNull(WordText.dictionaryEntry("你好"))
        assertNull(WordText.dictionaryEntry("你好 ni'hao x"))
        assertNull(WordText.dictionaryEntry("你好\tni'hao 0 0"))
        // `#` starts no comment: two values are a word and its reading, whatever the word
        assertEquals(entry("#", "hao"), WordText.dictionaryEntry("# hao"))
    }

    @Test
    fun aDictionaryIsKeptAsTheEngineReadsIt() {
        val text = WordText.dictionaryText(
            sequenceOf("你好\tni'hao\t0", "绿 lü", "\"你\\\"\" ni'hao -2.5", "你 nii 0", "你好 ni'hao cost", "", "虐 nüe 1e2"),
        ).toList()
        assertEquals(listOf("你好 ni'hao 0.0", "绿 lü 0.0", "\"你\\\"\" ni'hao -2.5", "虐 nüe 100.0"), text)
        assertEquals(listOf(entry("你好", "ni", "hao"), entry("绿", "lv")), text.take(2).map { WordText.dictionaryEntry(it) })
    }

    @Test
    fun theDictionaryReadsAWordKeptWithoutItsReading() {
        val d = polyphones
        val readings = WordText.readings(d.dictionary, d.vocabulary, setOf("行", "银行", "好", "ABC", "银好"))
        assertEquals(mapOf("行" to "xing", "银行" to "yin hang", "好" to "hao"), readings.mapValues { (_, s) -> s.joinToString(" ") { Syllables.spelling(it) } })
        assertEquals(emptyMap<String, IntArray>(), WordText.readings(d.dictionary, d.vocabulary, emptySet()))
    }

    @Test
    fun aTextAloneIsReadByTheSyllablesOfItsCharacters() {
        val d = polyphones
        fun reading(text: String) = WordText.readings(d.dictionary, d.vocabulary, setOf(text))[text]?.joinToString(" ") { Syllables.spelling(it) }
        // only yin then hang or xing walked: 行's rarer reading too
        assertEquals("yin hang", reading("银行"))
        assertEquals("xing", reading("行"))
        assertNull(reading("银好"))
        // 河 is no word of its own: after yin, every syllable is walked for it
        assertEquals("yin he", reading("银河"))
    }

    @Test
    fun aCharacterIsReadInsideAWordAsItIsNeverAlone() {
        val d = polyphones
        fun reading(text: String) = WordText.readings(d.dictionary, d.vocabulary, setOf(text))[text]?.joinToString(" ") { Syllables.spelling(it) }
        // 儿 alone is er: read char by char, 这儿 would be zhe er
        assertEquals("zhe r", reading("这儿"))
        // a character past U+FFFF: two chars, one syllable
        assertEquals("qiu", reading("\uD840\uDC00"))
        // neither of 𠀀儿's syllables is its character's alone, more than the walk strays: so 𠀀's own were known, two chars as it is
        assertNull(reading("\uD840\uDC00儿"))
    }

    @Test
    fun aFirstCharacterIsReadAsItIsNeverAloneOnlyInAnImport() {
        val d = polyphones
        fun reading(text: String, strayFirst: Boolean) =
            WordText.readings(d.dictionary, d.vocabulary, setOf(text), strayFirst)[text]?.joinToString(" ") { Syllables.spelling(it) }
        // 了 alone is le: liao, at the first depth, is walked only as an import asks
        assertNull(reading("了解", strayFirst = false))
        assertEquals("liao jie", reading("了解", strayFirst = true))
        // one stray a path still
        assertNull(reading("\uD840\uDC00儿", strayFirst = true))
    }

    private companion object {
        // 行 is read xing more often than hang; 银行 only yin hang; 河 only in 银河; 儿 r only in 这儿;
        // 𠀀 (U+20000) qiu, and 𠀀儿 as neither of its characters is alone; 了 le alone, liao in 了解
        val polyphones = PinyinData.load(
            ByteBuffer.wrap(
                PinyinDataBuilder()
                    .unigram("<unk>", -7f, 0f)
                    .unigram("行", -2f, 0f)
                    .unigram("银", -3f, 0f)
                    .unigram("银行", -3f, 0f)
                    .unigram("好", -2.5f, 0f)
                    .entry("行", syl("hang"), -1f)
                    .entry("行", syl("xing"))
                    .entry("银", syl("yin"))
                    .entry("银行", syl("yin", "hang"))
                    .entry("好", syl("hao"))
                    .entry("银河", syl("yin", "he"))
                    .entry("这", syl("zhe"))
                    .entry("儿", syl("er"))
                    .entry("这儿", syl("zhe", "r"))
                    .entry("\uD840\uDC00", syl("qiu"))
                    .entry("\uD840\uDC00儿", syl("ba", "r"))
                    .entry("了", syl("le"))
                    .entry("解", syl("jie"))
                    .entry("了解", syl("liao", "jie"))
                    .build().toByteArray(),
            ),
        )
    }
}
