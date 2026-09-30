/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.lattice

import org.fcitx.fcitx5.android.engine.data.NgramModel.Companion.NO_WORD
import org.fcitx.fcitx5.android.engine.data.PinyinData
import org.fcitx.fcitx5.android.engine.data.PinyinDataBuilder
import org.fcitx.fcitx5.android.engine.pinyin.Fuzzy
import org.fcitx.fcitx5.android.engine.pinyin.PinyinSegmenter
import org.fcitx.fcitx5.android.engine.pinyin.Syllables
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

class PinyinDecoderTest {

    private fun syl(vararg s: String) = s.map { Syllables.id(it) }.toIntArray()

    // few enough values that every one quantises exactly
    private val data = PinyinData.load(
        ByteBuffer.wrap(
            PinyinDataBuilder()
                .unigram("<unk>", -7f, 0f)
                .unigram("你", -2f, 0f)
                .unigram("好", -2.5f, 0f)
                .unigram("你好", -3f, 0f)
                .unigram("吗", -3.5f, 0f)
                .unigram("拟", -5f, 0f)
                .unigram("在", -2f, 0f)
                .unigram("再", -2.5f, 0f)
                .unigram("见", -3f, 0f)
                .unigram("我", -2f, -1f)
                .unigram("张", -3f, 0f)
                .unigram("中", -3.5f, 0f)
                .unigram("中华", -1.5f, 0f)
                .unigram("人民", -3f, 0f)
                .bigram("你", "好", -0.75f, 0f)
                .bigram("你好", "吗", -0.5f, 0f)
                .bigram("再", "见", -0.5f, 0f)
                .bigram("我", "再", -0.5f, 0f)
                .trigram("我", "再", "见", -0.25f)
                .entry("你", syl("ni"))
                .entry("拟", syl("ni"))
                .entry("好", syl("hao"))
                .entry("你好", syl("ni", "hao"))
                .entry("吗", syl("ma"))
                .entry("在", syl("zai"))
                .entry("再", syl("zai"))
                .entry("见", syl("jian"))
                .entry("我", syl("wo"))
                .entry("张", syl("zhang"))
                .entry("中", syl("zhong"))
                .entry("中华", syl("zhong", "hua"))
                .entry("人民", syl("ren", "min"))
                .build().toByteArray(),
        ),
    )

    private val plain = PinyinSegmenter()

    private fun id(word: String) = (0 until data.vocabulary.size).first { data.vocabulary.word(it) == word }

    private fun decoder(beam: Int = PinyinDecoder.DEFAULT_BEAM, words: Int = PinyinDecoder.DEFAULT_WORDS_PER_READING) =
        PinyinDecoder(data.dictionary, data.vocabulary, WordScorer.of(data.model), Penalties(), beam, words)

    private fun decode(input: String, segmenter: PinyinSegmenter = plain, prev: Int = NO_WORD) =
        decoder().decode(segmenter.segment(input), prev = prev)

    private fun texts(c: List<Candidate>) = c.map { it.text }

    @Test
    fun theLanguageModelPicksTheSentence() {
        val nihaoma = decode("nihaoma").sentences
        assertEquals("你好吗", nihaoma.first().text)
        // 你好, then 吗 after it: -3 - 0.5
        assertEquals(-3.5f, nihaoma.first().score, 1e-4f)
        assertEquals(listOf(id("你好"), id("吗")), nihaoma.first().words.toList())
        assertEquals("再见", decode("zaijian").sentences.first().text)
    }

    @Test
    fun sentencesAreDistinctAndBestFirst() {
        val s = decode("nihao").sentences
        // 你好 is read both as one word and as 你 + 好, and is listed once
        assertEquals(texts(s).distinct(), texts(s))
        assertEquals(setOf("你好", "拟好"), texts(s).toSet())
        assertEquals(s.sortedByDescending { it.score }, s)
        assertEquals(1, decoder().decode(plain.segment("nihao"), sentences = 1).sentences.size)
    }

    @Test
    fun theCommittedTextIsContext() {
        assertEquals("在", decode("zai").sentences.first().text)
        // 我 then 再 is a bigram; 我 then 在 backs off
        assertEquals("再", decode("zai", prev = id("我")).sentences.first().text)
        assertEquals("再", decode("zai", prev = id("我")).words.first().text)
    }

    @Test
    fun firstWordsAreEveryWordTheInputStartsWith() {
        val words = decode("nihaoma").words
        assertEquals(listOf("你", "你好", "拟"), texts(words))
        assertEquals(listOf(2, 5, 2), words.map { it.end })
        assertEquals(words.sortedByDescending { it.score }, words)
    }

    @Test
    fun readingsOtherThanWholeSyllablesCostTheirPenalty() {
        val p = Penalties()
        // 你好 through two initials, best as 你 then 好 (-2 - 0.75) rather than the word (-3)
        assertEquals(-2.75f + 2 * p.initial, decode("nh").sentences.first { it.text == "你好" }.score, 1e-4f)
        assertEquals(-2.75f + p.partial, decode("nih").sentences.first { it.text == "你好" }.score, 1e-4f)
        assertEquals(-2f + p.fuzzy, decode("li", PinyinSegmenter(setOf(Fuzzy.L_N))).sentences.first().score, 1e-4f)
        val zagn = decode("zagn", PinyinSegmenter(setOf(Fuzzy.Z_ZH))).sentences.first()
        assertEquals("张", zagn.text)
        assertEquals(-3f + p.fuzzy + p.typo, zagn.score, 1e-4f)
    }

    @Test
    fun inputThatIsNoPinyinIsKeptAsTyped() {
        val s = decode("ni1").sentences.first()
        assertEquals("你1", s.text)
        assertEquals(listOf(id("你"), NO_WORD), s.words.toList())
        assertEquals(-2f + Penalties().raw, s.score, 1e-4f)
        assertEquals(listOf("iu"), texts(decode("iu").sentences))
        assertTrue(decode("iu").words.isEmpty())
        // a syllable the dictionary has no word for
        assertEquals("你o", decode("nio").sentences.first().text)
    }

    @Test
    fun twoCommittedWordsAreTrigramContext() {
        assertEquals(-0.25f, decoder().decode(plain.segment("jian"), id("我"), id("再")).sentences.first().score, 1e-4f)
        // one word of context: the bigram
        assertEquals(-0.5f, decode("jian", prev = id("再")).sentences.first().score, 1e-4f)
    }

    @Test
    fun zhSplitIntoInitials() {
        // z and h for 中华, r and m for 人民; zh alone for 张 is the runner-up
        assertEquals(listOf("中华人民", "张人民"), texts(decode("zhrm").sentences).take(2))
        assertEquals("中华人民", decode("zhrm").sentences.first().text)
    }

    @Test
    fun aBetterReadingOfTheSameWordReplacesAWorseOne() {
        // zhon reads zhong as a slip (first) and as unfinished (better); 中 is listed once, at the better
        val s = decode("zhon").sentences
        assertEquals(listOf("中"), texts(s))
        assertEquals(-3.5f + Penalties().partial, s.first().score, 1e-4f)
    }

    @Test
    fun leadingSeparatorsAreSkipped() {
        val d = decode("'nihao")
        assertEquals("你好", d.sentences.first().text)
        assertEquals(3, d.words.first { it.text == "你" }.end)
    }

    @Test
    fun theWordListIsCapped() {
        assertEquals(1, decoder().decode(plain.segment("nihaoma"), words = 1).words.size)
    }

    @Test
    fun pruningKeepsTheBestSentence() {
        for (input in listOf("nihaoma", "zaijian", "nihaonihao", "zhrm", "zhonnihao")) {
            val narrow = decoder(beam = 2).decode(plain.segment(input)).sentences.first()
            val wide = decoder(beam = 1000).decode(plain.segment(input)).sentences.first()
            assertEquals(input, wide.text, narrow.text)
            assertEquals(input, wide.score, narrow.score, 1e-4f)
        }
    }

    @Test
    fun theEmptyInputHasNoCandidates() {
        for (input in listOf("", "''")) {
            val d = decode(input)
            assertTrue(d.sentences.isEmpty() && d.words.isEmpty())
        }
    }

    @Test
    fun onlyTheFirstWordTakesEveryWordOfAReading() {
        val one = decoder(words = 1)
        // 拟 is the second word for ni: listed first, but not taken later in the sentence
        assertTrue("拟" in texts(one.decode(plain.segment("nini")).words))
        assertEquals(setOf("你你", "拟你"), texts(one.decode(plain.segment("nini")).sentences).toSet())
    }

    @Test
    fun aNarrowBeamStillReachesTheEnd() {
        assertEquals(listOf("你好吗"), texts(decoder(beam = 1).decode(plain.segment("nihaoma")).sentences))
    }

    @Test
    fun theBeamAndWordLimitAreChecked() {
        assertThrows(IllegalArgumentException::class.java) { decoder(beam = 0) }
        assertThrows(IllegalArgumentException::class.java) { decoder(words = 0) }
    }

    @Test
    fun theDecoderCanBeReused() {
        val d = decoder()
        val first = texts(d.decode(plain.segment("nihaoma")).sentences)
        d.decode(plain.segment("zaijiannihaoma"))
        d.decode(plain.segment("n"))
        assertEquals(first, texts(d.decode(plain.segment("nihaoma")).sentences))
    }
}
