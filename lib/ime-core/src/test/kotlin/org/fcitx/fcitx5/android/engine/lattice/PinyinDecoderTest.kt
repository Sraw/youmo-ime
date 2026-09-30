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
import kotlin.random.Random

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
        // zhan finished, read as the start of zhang
        assertEquals(-3f + p.extended, decode("zhan").sentences.first { it.text == "张" }.score, 1e-4f)
        assertEquals(-2f + p.fuzzy, decode("li", PinyinSegmenter(setOf(Fuzzy.L_N))).sentences.first().score, 1e-4f)
        val zagn = decode("zagn", PinyinSegmenter(setOf(Fuzzy.Z_ZH))).sentences.first()
        assertEquals("张", zagn.text)
        assertEquals(-3f + p.fuzzy + p.typo, zagn.score, 1e-4f)
    }

    @Test
    fun aSyllableStillBeingTypedIsScoredAsTheLongerWordItStarts() {
        val p = Penalties()
        // zh may go on to 中华 (-1.5): 中 is scored as that, above 张 (-3) and 中 alone (-3.5)
        val s = decode("zh").sentences
        assertEquals("中", s.first().text)
        assertTrue(texts(s).indexOf("张") > 0)
        assertEquals(-1.5f + p.initial + p.lookAhead, s.first().score, 1e-4f)
        // what was typed is what it reads
        assertEquals(listOf(id("中")), s.first().words.toList())
        assertEquals(s.first().score, decode("zh").words.first { it.text == "中" }.score, 1e-4f)
        // nor once the syllable is finished
        assertEquals(-3.5f, decode("zhong").sentences.first().score, 1e-4f)
        // where the longer word is no likelier, the word's own score stands: 你好 after 你 is rarer than 你
        assertEquals(-2f + p.initial, decode("n").sentences.first { it.text == "你" }.score, 1e-4f)
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
        // zhon reads zhong as a slip (first) and as unfinished (better, and as the 中华 it may become);
        // 中 is listed once, at the best
        val s = decode("zhon").sentences
        assertEquals(listOf("中"), texts(s))
        assertEquals(-1.5f + Penalties().partial + Penalties().lookAhead, s.first().score, 1e-4f)
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

    private fun describe(d: Decoding) = (d.sentences + listOf(null) + d.words).map { c ->
        c?.let { "${it.text} ${it.end} ${it.score} ${it.words.toList()}" }
    }

    @Test
    fun buildingOnTheLastDecodeChangesNothing() {
        val random = Random(7)
        val pieces = listOf("n", "i", "h", "a", "o", "z", "m", "r", "g", "'", "1", "nihao", "zaijian", "zhong", "wo")
        val kept = decoder(beam = 3)
        val segmenter = PinyinSegmenter(setOf(Fuzzy.Z_ZH, Fuzzy.L_N))
        var input = ""
        var prev2 = NO_WORD
        var prev = NO_WORD
        repeat(4000) { step ->
            val at = random.nextInt(input.length + 1)
            val piece = pieces[random.nextInt(pieces.size)]
            when (random.nextInt(24)) {
                in 0..11 -> input += piece
                in 12..16 -> input = input.dropLast(1 + random.nextInt(2))
                17 -> input = input.drop(1)
                18 -> input = input.substring(0, at) + piece + input.substring(at)
                19 -> input = input.substring(0, at) + piece[0] + input.substring(minOf(at + 1, input.length))
                20 -> prev = if (prev == NO_WORD) id("我") else NO_WORD
                21 -> prev2 = if (prev2 == NO_WORD) id("我") else NO_WORD
                22 -> prev = if (prev == NO_WORD) id("再") else NO_WORD
                else -> input = ""
            }
            val graph = segmenter.segment(input)
            val fresh = decoder(beam = 3).decode(graph, prev2, prev)
            assertEquals("step $step: $input", describe(fresh), describe(kept.decode(graph, prev2, prev)))
        }
    }

    @Test
    fun aKeyAtTheEndSearchesOnlyTheEnd() {
        var calls = 0
        val counting = WordScorer { p2, p, w -> calls++; WordScorer.of(data.model).score(p2, p, w) }
        fun count(d: PinyinDecoder, input: String): Int {
            calls = 0
            d.decode(plain.segment(input))
            return calls
        }
        val d = PinyinDecoder(data.dictionary, data.vocabulary, counting)
        // the same key costs the same however much input comes before it, once the beams are full
        val building = listOf(8, 16, 32).map { n ->
            val input = "nihao".repeat(n)
            count(d, input)
            count(d, input + "m")
        }
        assertEquals(1, building.distinct().size)
        d.reset()
        assertTrue(building.first() * 10 < count(d, "nihao".repeat(32) + "m"))
    }

    @Test
    fun aResetSearchesWithTheNewScores() {
        var favour = id("在")
        val scorer = WordScorer { _, _, w -> if (w == favour) -1f else -2f }
        val d = PinyinDecoder(data.dictionary, data.vocabulary, scorer)
        assertEquals("在我", d.decode(plain.segment("zaiwo")).sentences.first().text)
        favour = id("再")
        // the search up to wo is kept, with the old scores
        assertEquals("在我吗", d.decode(plain.segment("zaiwoma")).sentences.first().text)
        d.reset()
        assertEquals("再我吗", d.decode(plain.segment("zaiwoma")).sentences.first().text)
    }

    @Test
    fun aDecodeThatThrewIsNotBuiltOn() {
        var fail = false
        val scorer = WordScorer { p2, p, w -> check(!fail); WordScorer.of(data.model).score(p2, p, w) }
        val d = PinyinDecoder(data.dictionary, data.vocabulary, scorer)
        d.decode(plain.segment("nihaonihao"))
        fail = true
        assertThrows(IllegalStateException::class.java) { d.decode(plain.segment("nihaonihaoma")) }
        fail = false
        val input = plain.segment("nihaonihaom")
        assertEquals(describe(decoder().decode(input)), describe(d.decode(input)))
    }
}
