/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.reading

import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.assertRows
import org.fcitx.fcitx5.android.engine.data.PinyinData
import org.fcitx.fcitx5.android.engine.data.PinyinDataBuilder
import org.fcitx.fcitx5.android.engine.lattice.Predictor
import org.fcitx.fcitx5.android.engine.learn.Simplifier
import org.fcitx.fcitx5.android.engine.pinyin.Syllables
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.nio.ByteBuffer

class CandidateReadingsTest {

    private fun syl(vararg s: String) = s.map { Syllables.id(it) }.toIntArray()

    private val data = PinyinData.load(
        ByteBuffer.wrap(
            PinyinDataBuilder().unigram("<unk>", -7f, 0f)
                .unigram("银行", -2f, 0f).entry("银行", syl("yin", "hang"))
                .unigram("行", -2f, 0f).entry("行", syl("xing")).entry("行", syl("hang"), -1f)
                .unigram("走", -2f, 0f).entry("走", syl("zou"))
                .unigram("银", -3f, 0f).entry("银", syl("yin"))
                .unigram("你", -2f, 0f).entry("你", syl("ni"))
                .entry("𠀀", syl("he"))
                .unigram("说话", -2f, 0f).entry("说话", syl("shuo", "hua"))
                .unigram("我们", -2f, 0f).entry("我们", syl("wo", "men"))
                .entry("说", syl("shuo")).entry("话", syl("hua")).entry("我", syl("wo")).entry("们", syl("men"))
                .charTone('说'.code, Syllables.id("shuo"), 1)
                .charTone('话'.code, Syllables.id("hua"), 4)
                .charTone('我'.code, Syllables.id("wo"), 3)
                // 们 as Unihan has it: no tone but the neutral one, kept in 我们
                .charTone('们'.code, Syllables.id("men"), ToneMark.NEUTRAL)
                .wordTones("我们", syl("wo", "men"), byteArrayOf(3, 5))
                .charTone('银'.code, Syllables.id("yin"), 2)
                .charTone('行'.code, Syllables.id("xing"), 2)
                .charTone('行'.code, Syllables.id("hang"), 2)
                .charTone('走'.code, Syllables.id("zou"), 3)
                .charTone('你'.code, Syllables.id("ni"), 3)
                .build().toByteArray(),
        ),
    )

    private val readings = CandidateReadings(data, data.tones) { Predictor.charReadingsOf(data.dictionary, data.vocabulary) }

    @Test
    fun offShowsNothingAndReadsNoTable() {
        var asked = 0
        val counting = object : ReadingTable by data.tones {
            override fun tones(text: String, syllables: IntArray, word: Int): ByteArray? = data.tones.tones(text, syllables, word).also { asked++ }
        }
        var charsRead = false
        val r = CandidateReadings(data, counting) { charsRead = true; Predictor.charReadingsOf(data.dictionary, data.vocabulary) }
        assertEquals(emptyList<String>(), r.of("银行", ReadingStyle.OFF))
        assertEquals(0, asked)
        assertFalse(charsRead)
        r.of("银行", ReadingStyle.PINYIN)
        assertEquals(1, asked)
    }

    @Test
    fun theSessionsReadingWins() {
        val typed = listOf(WordReading("行", -1, syl("hang")), WordReading("走", -1, syl("zou")))
        assertEquals(listOf("háng", "zǒu"), readings.of("行走", ReadingStyle.PINYIN, typed))
        // not of the text: read by it instead
        assertEquals(listOf("xíng", "zǒu"), readings.of("行走", ReadingStyle.PINYIN, typed.take(1)))
    }

    @Test
    fun byTextTheLongestWordFirstThenEachCharacterItsLikeliestWay() {
        assertEquals(listOf("yín", "háng", "xíng", "zǒu"), readings.of("银行行走", ReadingStyle.PINYIN))
        // the same in 注音
        assertEquals(listOf("ㄧㄣˊ", "ㄏㄤˊ"), readings.of("银行", ReadingStyle.ZHUYIN))
    }

    @Test
    fun whatIsNoHanCharacterOrHasNoKnownToneIsLeftEmpty() {
        assertEquals(listOf("nǐ", "", ""), readings.of("你A😀", ReadingStyle.PINYIN))
        assertEquals(emptyList<String>(), readings.of("hello", ReadingStyle.PINYIN))
        // a character of the dictionary only, with no tone known
        assertEquals(emptyList<String>(), readings.of("𠀀", ReadingStyle.PINYIN))
    }

    @Test
    fun stylesByKey() {
        assertEquals(ReadingStyle.ZHUYIN, ReadingStyle.of("Zhuyin"))
        assertEquals(ReadingStyle.OFF, ReadingStyle.of(null))
        assertEquals(ReadingStyle.OFF, ReadingStyle.of("x"))
    }

    @Test
    @Uat("PY-111")
    fun aHintIsOneReadingACharacterWithItsTones() {
        // a misreading's, toned already
        assertEquals(listOf("jué", "sè"), readings.ofHint("角色", "jué sè"))
        // a stroke lookup's or a part's: the syllable as hinted, with its tone in the character
        assertEquals(listOf("háng"), readings.ofHint("行", "hang"))
        assertEquals(listOf("xíng"), readings.ofHint("行", "xing"))
        // no tone known, or no syllable: as hinted
        assertEquals(listOf("he"), readings.ofHint("𠀀", "he"))
        assertEquals(listOf("hm"), readings.ofHint("你", "hm"))
        // in zhuyin: the hint's tones, or the character's
        assertEquals(listOf("ㄅㄛ", "ㄖㄜˇ"), readings.ofHint("般若", "bō rě", ReadingStyle.ZHUYIN))
        assertEquals(listOf("ㄒㄧㄥˊ"), readings.ofHint("行", "xing", ReadingStyle.ZHUYIN))
        assertEquals(listOf("ㄏㄜ"), readings.ofHint("𠀀", "he", ReadingStyle.ZHUYIN))
    }

    // the readings of 了, 能, 行 and their words as Unihan and the dictionaries have them
    private val polyphones = ReadingData.data.let { d -> CandidateReadings(d, d.tones) { Predictor.charReadingsOf(d.dictionary, d.vocabulary) } }

    // a polyphone's several readings hinted (a part's or a stroke lookup's "le/liao"): one, toned, the one it has
    // after what was put before it, else its commonest (A5, the user, 2026-11-06)
    @Test
    @Uat("PY-111", "PY-118")
    fun aHintOfACharactersSeveralReadingsIsTheOneItHasThere() = assertRows(
        "了 alone: le" to { assertEquals(listOf("le"), polyphones.ofHint("了", "le/liao")) },
        "了 after 好 (好了): le" to { assertEquals(listOf("le"), polyphones.ofHint("了", "le/liao", before = "好")) },
        "能 alone: néng" to { assertEquals(listOf("néng"), polyphones.ofHint("能", "neng/nai")) },
        "行 alone: xíng" to { assertEquals(listOf("xíng"), polyphones.ofHint("行", "xing/hang/heng")) },
        "行 after 银 (银行): háng" to { assertEquals(listOf("háng"), polyphones.ofHint("行", "xing/hang/heng", before = "银")) },
        "行 after 进 (进行): xíng" to { assertEquals(listOf("xíng"), polyphones.ofHint("行", "hang/xing", before = "进")) },
        "in zhuyin" to { assertEquals(listOf("ㄏㄤˊ"), polyphones.ofHint("行", "xing/hang", ReadingStyle.ZHUYIN, before = "银")) },
        "no reading known: the hint's first" to { assertEquals(listOf("he"), polyphones.ofHint("𠀀", "he/huo")) },
        // the hint lists at most three: one it does not list is neither drawn nor blocked (oneOf, PartsLookup.shownIn)
        "行 after 银, the hint without hang: of those it lists" to { assertEquals(listOf("xíng"), polyphones.ofHint("行", "xing/heng", before = "银")) },
        // a character the data has, hinted none of its readings: the hint's first, as one it has none of
        "行 hinted none of its readings: the hint's first" to { assertEquals(listOf("zou"), polyphones.ofHint("行", "zou/mou")) },
        "blocked in the one drawn" to { assertEquals("xing", polyphones.oneOf("行", "银", "xing/heng")?.let { Syllables.spelling(it.syllable) }) },
        "no syllables, or more than a character: no reading" to {
            assertEquals(emptyList<String>(), polyphones.ofHint("行", "xing/~"))
            assertEquals(emptyList<String>(), polyphones.ofHint("银行", "yin/hang"))
        },
    )

    // a word read by its text (a table's, a prediction's): each polyphone as the word has it
    @Test
    @Uat("PY-118")
    fun aPolyphoneInAWordIsReadAsTheWordHasIt() = assertRows(
        "好了" to { assertEquals(listOf("hǎo", "le"), polyphones.of("好了", ReadingStyle.PINYIN)) },
        "了解" to { assertEquals(listOf("liǎo", "jiě"), polyphones.of("了解", ReadingStyle.PINYIN)) },
        "银行" to { assertEquals(listOf("yín", "háng"), polyphones.of("银行", ReadingStyle.PINYIN)) },
        "行走" to { assertEquals(listOf("xíng", "zǒu"), polyphones.of("行走", ReadingStyle.PINYIN)) },
        "能" to { assertEquals(listOf("néng"), polyphones.of("能", ReadingStyle.PINYIN)) },
    )

    @Test
    fun whatIsNoReadingOfEachCharacterStaysAHint() {
        assertEquals(emptyList<String>(), readings.ofHint("你", ""))
        assertEquals(emptyList<String>(), readings.ofHint("😀", "smile"))
        assertEquals(emptyList<String>(), readings.ofHint("你A", "ni a"))
        assertEquals(emptyList<String>(), readings.ofHint("银行", "yinhang"))
        assertEquals(emptyList<String>(), readings.ofHint("你", "~ni"))
    }

    @Test
    fun traditionalTextGoesThroughOpenCCsSimplifier() {
        val simplifier = Simplifier.read("說\t说\n話\t话\n們\t们\n".toByteArray(), "说\t說\n".toByteArray())
        val r = CandidateReadings(data, data.tones, simplifier::simplify) { Predictor.charReadingsOf(data.dictionary, data.vocabulary) }
        assertEquals(listOf("shuō", "huà"), r.of("說話", ReadingStyle.PINYIN))
        assertEquals(listOf("wǒ", "men"), r.of("我們", ReadingStyle.PINYIN))
    }
}
