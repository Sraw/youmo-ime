/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.data

import org.fcitx.fcitx5.android.engine.data.NgramModel.Companion.NO_WORD
import org.fcitx.fcitx5.android.engine.pinyin.Syllables
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class PinyinDataTest {

    private fun syl(vararg s: String) = s.map { Syllables.id(it) }.toIntArray()

    /** A model small enough that every value quantises exactly (fewer values than levels). */
    private fun builder() = PinyinDataBuilder()
        .unigram("<unk>", -7f, 0f)
        .unigram("你", -2f, -0.5f)
        .unigram("好", -2.5f, -0.25f)
        .unigram("你好", -3f, -0.75f)
        .unigram("拟", -5f, 0f)
        .unigram("吗", -3.5f, 0f)
        .bigram("你", "好", -0.8f, -0.1f)
        .bigram("你好", "吗", -0.4f, 0f)
        .bigram("好", "吗", -1.1f, 0f)
        .trigram("你", "好", "吗", -0.2f)
        .entry("你", syl("ni"))
        .entry("拟", syl("ni"))
        .entry("好", syl("hao"))
        .entry("你好", syl("ni", "hao"))
        .entry("吗", syl("ma"))
        .entry("妳好", syl("ni", "hao")) // not in the model

    private fun load(b: PinyinDataBuilder = builder(), meta: Map<String, String> = emptyMap()) =
        PinyinData.load(ByteBuffer.wrap(b.build(meta).toByteArray()))

    private fun PinyinData.id(word: String) = (0 until vocabulary.size).first { vocabulary.word(it) == word }

    @Test
    fun theDictionaryFindsWordsBySyllablesMostProbableFirst() {
        val data = load()
        val ni = data.dictionary.find(syl("ni"))
        assertEquals(listOf("你", "拟"), (0 until data.dictionary.wordCount(ni)).map { data.vocabulary.word(data.dictionary.word(ni, it)) })
        val nihao = data.dictionary.find(syl("ni", "hao"))
        // 妳好 is unknown to the model, so it scores as <unk> and sorts last
        assertEquals(listOf("你好", "妳好"), (0 until data.dictionary.wordCount(nihao)).map { data.vocabulary.word(data.dictionary.word(nihao, it)) })
        assertEquals(-1, data.dictionary.find(syl("hao", "ni")))
        assertEquals(-1, data.dictionary.find(syl("ni", "ma")))
    }

    @Test
    fun aRareReadingSortsByItsWeightAndKeepsIt() {
        // 你 read as hao (weight -3.5): -2 + -3.5 = -5.5 sorts after 好 at -2.5
        val data = load(builder().entry("你", syl("hao"), -3.5f).entry("你", syl("hao"), -4f))
        val d = data.dictionary
        val hao = d.find(syl("hao"))
        assertEquals(listOf("好", "你"), (0 until d.wordCount(hao)).map { data.vocabulary.word(d.word(hao, it)) })
        assertEquals(listOf(0f, -3.5f), (0 until d.wordCount(hao)).map { d.weight(hao, it) })
    }

    @Test
    fun theTrieCanBeWalkedChildByChild() {
        val d = load().dictionary
        val children = (0 until d.childCount(d.root)).map { Syllables.spelling(d.syllable(d.firstChild(d.root) + it)) }
        assertEquals(listOf("hao", "ma", "ni").sortedBy { Syllables.id(it) }, children)
        val ni = d.child(d.root, Syllables.id("ni"))
        assertEquals(1, d.childCount(ni))
        assertEquals(0, d.wordCount(d.root))
        assertEquals(5, d.nodeCount) // root, ni, hao, ma, ni-hao
    }

    @Test
    fun wordsTheModelNeverSawComeAfterItsOwn() {
        val data = load()
        assertEquals(6, data.model.vocabularySize)
        assertEquals(7, data.vocabulary.size)
        assertEquals("妳好", data.vocabulary.word(6))
    }

    @Test
    fun seenNgramsScoreTheirOwnProbability() {
        val data = load()
        val m = data.model
        assertEquals(-2f, m.score(data.id("你")), 0f)
        assertEquals(-0.8f, m.score(data.id("你"), data.id("好")), 0f)
        assertEquals(-0.2f, m.score(data.id("你"), data.id("好"), data.id("吗")), 0f)
    }

    @Test
    fun unseenNgramsBackOff() {
        val data = load()
        val m = data.model
        // P(拟 | 你) = bo(你) + P(拟)
        assertEquals(-0.5f + -5f, m.score(data.id("你"), data.id("拟")), 1e-6f)
        // P(拟 | 你 好): bigram 你 好 exists, trigram does not = bo(你 好) + P(拟 | 好) = -0.1 + (-0.25 + -5)
        assertEquals(-0.1f + -0.25f + -5f, m.score(data.id("你"), data.id("好"), data.id("拟")), 1e-6f)
        // P(吗 | 拟 好): no bigram 拟 好, so straight to P(吗 | 好)
        assertEquals(-1.1f, m.score(data.id("拟"), data.id("好"), data.id("吗")), 1e-6f)
    }

    @Test
    fun theWordsSeenAfterAContextAreWalkedOnceWithTheirScores() {
        // 拟 after 你 好 but never after 好 alone
        val data = load(builder().bigram("好", "你", -1.3f, 0f).trigram("你", "好", "拟", -0.6f))
        val m = data.model
        fun after(prev2: Int, prev: Int): List<String> {
            val context = m.context(prev2, prev)
            val words = ArrayList<Int>()
            m.forEachAfter(context) { word, score ->
                words += word
                assertEquals(m.scoreAfter(context, word), score, 0f)
            }
            assertEquals(words.distinct().sorted(), words)
            return words.map { data.vocabulary.word(it) }
        }
        assertEquals(listOf("你", "吗"), after(NO_WORD, data.id("好")))
        // 吗 is a trigram after 你 好 as well as a bigram after 好, and comes once; 你 backs off
        assertEquals(listOf("你", "拟", "吗"), after(data.id("你"), data.id("好")))
        assertEquals(listOf("好"), after(data.id("拟"), data.id("你")))
        assertEquals(emptyList<String>(), after(data.id("你"), NO_WORD))
        assertEquals(emptyList<String>(), after(NO_WORD, data.id("吗")))
        // a word the model never saw is <unk>, which nothing follows here
        assertEquals(emptyList<String>(), after(NO_WORD, data.id("妳好")))
    }

    @Test
    fun unknownWordsAndMissingContextsAreHandled() {
        val data = load()
        val m = data.model
        assertEquals(-7f, m.score(data.id("妳好")), 0f)
        assertEquals(-2.5f, m.score(NO_WORD, data.id("好")), 0f)
        assertEquals(-0.8f, m.score(NO_WORD, data.id("你"), data.id("好")), 0f)
        // unknown context: <unk> has backoff 0
        assertEquals(-2.5f, m.score(data.id("妳好"), data.id("好")), 0f)
        // an unknown word two back: no bigram (<unk>, 你), so P(好 | 你)
        assertEquals(-0.8f, m.score(data.id("妳好"), data.id("你"), data.id("好")), 0f)
        // no previous word at all: prev2 cannot matter
        assertEquals(-2.5f, m.score(data.id("你"), NO_WORD, data.id("好")), 0f)
    }

    @Test
    fun backoffWeightsAreReadable() {
        val data = load()
        val m = data.model
        assertEquals(-0.5f, m.backoff(data.id("你")), 0f)
        assertEquals(0f, m.backoff(data.id("妳好")), 0f)
        assertEquals(-0.1f, m.backoff(data.id("你"), data.id("好")), 0f)
        assertEquals(0f, m.backoff(data.id("好"), data.id("你")), 0f)
    }

    @Test
    fun quantisedBackoffsStillAddUp() {
        // enough distinct bigram backoffs that the codebook cannot hold them all
        val b = builder()
        val words = (0 until 400).map { "w$it" }
        words.forEach { b.unigram(it, -4f, -0.2f) }
        words.forEachIndexed { i, w -> b.bigram("你", w, -2f, -0.001f * (i + 1)) }
        val data = load(b)
        val m = data.model
        words.forEachIndexed { i, w ->
            val bo = m.backoff(data.id("你"), data.id(w))
            assertEquals(-0.001f * (i + 1), bo, 0.002f)
            // P(拟 | 你 w) = bo(你 w) + bo(w) + P(拟)
            assertEquals(bo + -0.2f + -5f, m.score(data.id("你"), data.id(w), data.id("拟")), 1e-5f)
        }
    }

    @Test
    fun sectionsOfTheWrongSizeAreAFormatError() {
        val good = PinyinData.load(ByteBuffer.wrap(builder().build().toByteArray()))
        assertEquals(6, good.model.vocabularySize)
        // rebuild the file with one array cut short, keeping everything else
        val bytes = builder().build().toByteArray()
        val file = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val count = file.getInt(16)
        val entry = (0 until count).map { 24 + it * 24 }.first { file.getInt(it) == PinyinData.Section.UNI_BACKOFF }
        file.putLong(entry + 16, file.getLong(entry + 16) - 4)
        try {
            PinyinData.load(ByteBuffer.wrap(bytes), verify = false)
            fail("loaded a short backoff array")
        } catch (e: DataFormatException) {
            assertTrue(e.message, e.message!!.contains("unigram arrays"))
        }
    }

    @Test(expected = IllegalStateException::class)
    fun aBuilderBuildsOnce() {
        val b = builder()
        b.build()
        b.build()
    }

    @Test
    fun callerMetaIsKeptAlongsideTheBuildersOwn() {
        val meta = load(meta = mapOf("source" to "test")).meta
        assertEquals("test", meta["source"])
        assertEquals(Syllables.checksum().toString(), meta[PinyinData.META_SYLLABLE_CHECKSUM])
        assertEquals(Syllables.count.toString(), meta[PinyinData.META_SYLLABLE_COUNT])
    }

    @Test
    fun theCallerCannotOverrideTheSyllableChecksum() {
        val meta = load(meta = mapOf(PinyinData.META_SYLLABLE_CHECKSUM to "0")).meta
        assertEquals(Syllables.checksum().toString(), meta[PinyinData.META_SYLLABLE_CHECKSUM])
    }

    @Test
    fun dataBuiltForAnotherSyllableTableIsRejected() {
        val bytes = builder().build().toByteArray()
        val needle = "${PinyinData.META_SYLLABLE_CHECKSUM}=${Syllables.checksum()}".toByteArray()
        val at = (0..bytes.size - needle.size).first { i -> needle.indices.all { bytes[i + it] == needle[it] } }
        val digit = at + needle.size - 1
        bytes[digit] = if (bytes[digit] == '1'.code.toByte()) '2'.code.toByte() else '1'.code.toByte()
        try {
            PinyinData.load(ByteBuffer.wrap(bytes), verify = false)
            fail("accepted a foreign syllable table")
        } catch (e: DataFormatException) {
            assertTrue(e.message, e.message!!.contains("different syllable table"))
        }
    }

    @Test
    fun theBuilderRefusesInconsistentModels() {
        fun rejects(block: PinyinDataBuilder.() -> Unit) {
            try {
                builder().block()
                fail("accepted")
            } catch (_: IllegalArgumentException) {
            }
        }
        rejects { unigram("你", -1f, 0f) }
        rejects { bigram("你", "她", -1f, 0f) }
        rejects { entry("她", intArrayOf()) }
        rejects { entry("她", intArrayOf(Syllables.count)) }
        rejects { entry("她", syl("ta"), Float.NaN) }
        rejects { unigram("她", Float.NaN, 0f) }
        rejects { bigram("好", "你", -1f, Float.NEGATIVE_INFINITY) }
        rejects { trigram("你", "好", "你", Float.POSITIVE_INFINITY) }
        rejects { bigram("你", "好", -1f, 0f).build() }
        rejects { trigram("好", "你", "吗", -1f).build() }
        rejects { trigram("你", "好", "吗", -1f).build() }
        try {
            PinyinDataBuilder().unigram("你", -1f, 0f).build()
            fail("built a model without <unk>")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("<unk>"))
        }
    }

    @Test
    fun aTrieNodeThatIsItsOwnChildIsRejected() {
        fun packed(vararg v: Int) = BitPacked(ByteBuffer.wrap(BitPacked.encode(v)).order(ByteOrder.LITTLE_ENDIAN))
        try {
            // node 1's children start at 1: it would be its own child, and a walk down would never end
            PinyinDictionary(1, packed(0, 5), packed(1, 1, 2), packed(0, 0, 0), packed(), ByteBuffer.allocate(0), FloatArray(256))
            fail("accepted")
        } catch (e: DataFormatException) {
            assertTrue(e.message, e.message!!.contains("node 1 precedes its parent"))
        }
    }
}
