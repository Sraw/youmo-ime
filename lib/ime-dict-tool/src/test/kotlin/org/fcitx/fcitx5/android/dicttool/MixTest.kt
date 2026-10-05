/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.dicttool

import org.fcitx.fcitx5.android.engine.data.SourceException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.log10
import kotlin.math.pow

class MixTest {

    private val words = listOf("<unk>", "你", "好", "你好", "吗", "我", "们", "我们", "，")

    // every word as likely: a model with the vocabulary and nothing else
    private val uniform = model(
        "\\data\\\nngram 1=${words.size}\n\n\\1-grams:\n" +
            words.joinToString("") { "${log10(1.0 / words.size)}\t$it\n" } + "\n\\end\\\n",
    )

    private val chat = listOf("你 好 吗 ， 我们 好", "我们 好 ， 你 好 吗", "你 们 好", "我 好", "你好 吗 ， 我 好 吗")
    private val news = listOf("我们 好", "你好 ， 我们", "我 们", "你 好 ， 我们 好")

    private fun model(text: String) = ArpaModel.read(text.reader().buffered(), "test")

    private fun counts(texts: List<String>) = ChatCounts(uniform).apply { texts.forEach(::add) }

    /** The chat's own Kneser-Ney model, in backoff form: normalized, unlike a made-up one. */
    private fun normalized(texts: List<String>): ArpaModel {
        val counts = counts(texts)
        return Mixer(uniform, counts, KneserNey(counts), 0.0, 1, 1).mix()
    }

    private val ids = words.indices

    @Test
    fun longKeysGetIndicesInTheOrderAdded() {
        val index = LongIndex(4)
        val keys = (0 until 100_000).map { it * 7_919L + (it.toLong() shl 40) }
        keys.forEachIndexed { i, key -> assertEquals(i, index.add(key)) }
        assertEquals(keys.size, index.size)
        keys.forEachIndexed { i, key ->
            assertEquals(i, index.indexOf(key))
            assertEquals(key, index.keyAt(i))
        }
        assertEquals(5, index.add(keys[5]))
        assertEquals(-1, index.indexOf(3L))
        assertThrows(IllegalArgumentException::class.java) { index.add(-1L) }
        val column = IntColumn(1).apply { add(1000, 3); add(1000, 2) }
        assertEquals(5, column[1000])
        assertEquals(0, column[5000])
    }

    @Test
    fun ngramKeysHoldTheirWords() {
        val key = NgramKey.of(270_000, 1, NgramKey.MAX_WORDS - 1)
        assertEquals(listOf(270_000, 1, NgramKey.MAX_WORDS - 1), (0..2).map { NgramKey.word(key, 3, it) })
        assertEquals(listOf(5, 0), (0..1).map { NgramKey.word(NgramKey.of(5, 0), 2, it) })
    }

    @Test
    fun aConversationIsAJsonArrayOfStrings() {
        assertEquals(listOf("你 好", "a\"b\\c/", "你\n"), JsonStrings.parse(""" [ "你 好" , "a\"b\\c\/", "\u4f60\n" ] """))
        assertEquals(emptyList<String>(), JsonStrings.parse("[]"))
        val odd = listOf("你 好", "a\"b\\c", "tab\there\n", "")
        assertEquals(odd, JsonStrings.parse(JsonStrings.format(odd)))
        for (bad in listOf("{}", "[\"a\"", "[\"a\" \"b\"]", "[\"a\"] x", "[\"\\x\"]", "[1]")) {
            assertThrows(bad, IllegalArgumentException::class.java) { JsonStrings.parse(bad) }
        }
    }

    @Test
    fun chatIsSplitIntoTheModelsWordsAndCountedWithinWhatTheyCover() {
        val counts = ChatCounts(model(words.joinToString("", "\\data\\\nngram 1=${words.size}\n\n\\1-grams:\n", "\n\\end\\\n") {
            // 你好 whole likelier than 你 and 好 apart
            "${if (it == "你好") -1.0 else -1.5}\t$it\n"
        }))
        counts.add("你 好 吗 ， x 我")
        fun id(w: String) = words.indexOf(w)
        // 你好 吗 ， 我
        assertEquals(4L, counts.tokens)
        for (bigram in listOf(counts.start to id("你好"), id("你好") to id("吗"), id("吗") to id("，"), counts.start to id("我"))) {
            assertEquals(1, counts.bigramCount[counts.bigrams.indexOf(NgramKey.of(bigram.first, bigram.second))])
        }
        // not across the letter
        assertEquals(-1, counts.bigrams.indexOf(NgramKey.of(id("，"), id("我"))))
        // 你好 吗 ， after the start; 我 alone makes none
        assertEquals(2, counts.trigrams.size)
    }

    @Test
    fun kneserNeyGivesEachContextAWholeDistribution() {
        val counts = counts(chat)
        val kn = KneserNey(counts)
        assertEquals(1.0, ids.sumOf { kn.p1(it) }, 1e-9)
        for (v in ids) assertEquals(1.0, ids.sumOf { kn.p2(v, it) }, 1e-9)
        for (u in ids) for (v in ids) assertEquals(1.0, ids.sumOf { kn.p3(u, v, it) }, 1e-9)
        // what was seen after a context is likelier there than anywhere (你好 whole: fewer words, all as likely)
        val (u, v, w) = listOf("你好", "吗", "，").map(words::indexOf)
        assertTrue(kn.p3(u, v, w) > kn.p1(w))
    }

    @Test
    fun withTheChatAloneTheMixtureIsItsKneserNeyModel() {
        val counts = counts(chat)
        val kn = KneserNey(counts)
        val mixed = Mixer(uniform, counts, kn, 0.0, 1, 1).mix()
        for (u in ids) for (v in ids) for (w in ids) assertEquals(log10(kn.p3(u, v, w)), mixed.log10(u, v, w), 1e-9)
    }

    @Test
    fun theMixtureGivesEachContextAWholeDistribution() {
        val base = normalized(news)
        val counts = counts(chat)
        for (cutoff in 1..2) {
            val mixed = Mixer(base, counts, KneserNey(counts), 0.6, cutoff, cutoff).mix()
            for (v in ids) assertEquals(1.0, ids.sumOf { 10.0.pow(mixed.log10(v, it)) }, 1e-9)
            for (u in ids) for (v in ids) assertEquals(1.0, ids.sumOf { 10.0.pow(mixed.log10(u, v, it)) }, 1e-9)
        }
    }

    @Test
    fun atFullWeightTheBaseIsKept() {
        val base = normalized(news)
        val counts = counts(chat)
        val mixed = Mixer(base, counts, KneserNey(counts), 1.0, 1, 1).mix()
        // with more n-grams than it had, each worth what the base made of it
        assertTrue(mixed.trigrams.size > base.trigrams.size)
        for (u in ids) for (v in ids) for (w in ids) assertEquals(base.log10(u, v, w), mixed.log10(u, v, w), 1e-9)
    }

    @Test
    fun cutoffsKeepOnlyWhatWasCountedOftenEnough() {
        val base = normalized(news)
        val counts = counts(chat)
        val kn = KneserNey(counts)
        val all = Mixer(base, counts, kn, 0.5, 1, 1).mix()
        val often = Mixer(base, counts, kn, 0.5, 2, 2).mix()
        assertTrue(often.bigrams.size < all.bigrams.size)
        assertTrue(often.trigrams.size < all.trigrams.size)
        // the base's own are always kept
        for (i in 0 until base.trigrams.size) assertTrue(often.trigrams.indexOf(base.trigrams.keyAt(i)) >= 0)
        assertThrows(IllegalArgumentException::class.java) { Mixer(base, counts, kn, 0.5, 3, 2) }
        assertThrows(IllegalArgumentException::class.java) { Mixer(base, counts, kn, 1.5, 1, 1) }
    }

    @Test
    fun aContextWithAnNgramForEveryWordMustSumToOne() {
        fun full(sum: Double) = model(
            "\\data\\\nngram 1=3\nngram 2=3\n\n\\1-grams:\n" +
                "${log10(0.2)}\t<unk>\t0\n${log10(0.4)}\t你\t0\n${log10(0.4)}\t好\t0\n\n\\2-grams:\n" +
                listOf("<unk>" to 0.2, "你" to 0.4, "好" to 0.4).joinToString("") { (w, p) -> "${log10(p * sum)}\t你 $w\n" } +
                "\n\\end\\\n",
        )
        fun mix(base: ArpaModel): ArpaModel {
            val counts = ChatCounts(base).apply { add("你 好") }
            return Mixer(base, counts, KneserNey(counts), 1.0, 1, 1).mix()
        }
        // at full weight, the base as it is: every word after 你 has its own, so no backoff
        assertEquals(0.0, mix(full(1.0)).unigramBackoff[1], 1e-12)
        val e = assertThrows(IllegalArgumentException::class.java) { mix(full(0.9)) }
        assertTrue(e.message, e.message!!.contains("\"你\""))
    }

    @Test
    fun anNgramTwiceIsAnError() {
        val twice = "\\data\\\nngram 1=2\nngram 2=2\n\n\\1-grams:\n-1\t<unk>\t0\n-1\t你\t0\n\n\\2-grams:\n-1\t你 你\n-1\t你 你\n\n\\end\\\n"
        val e = assertThrows(SourceException::class.java) { model(twice) }
        assertTrue(e.message, e.message!!.contains("twice"))
    }

    @Test
    fun aModelWrittenReadsBackAsItWas() {
        val counts = counts(chat)
        val mixed = Mixer(normalized(news), counts, KneserNey(counts), 0.5, 1, 1).mix()
        val text = StringBuilder().also { mixed.write(it) }.toString()
        val read = model(text)
        assertEquals(mixed.words, read.words)
        assertEquals(mixed.bigrams.size, read.bigrams.size)
        assertEquals(mixed.trigrams.size, read.trigrams.size)
        // six significant digits, as the file has them
        for (u in ids) for (v in ids) for (w in ids) assertEquals(mixed.log10(u, v, w), read.log10(u, v, w), 1e-4)
    }

    @Test
    fun addedUnigramsTakeTheirShareAndTheRestScales() {
        val added = uniform.withUnigrams(listOf("你们" to log10(0.1), "你" to -1.0, "你们" to -2.0, "好吗" to log10(0.2)))
        assertEquals(words + listOf("你们", "好吗"), added.words)
        assertEquals(log10(0.1), added.unigramProb[words.size], 1e-12)
        // the file is read as floats
        assertEquals(log10(1.0 / words.size * 0.7), added.unigramProb[1], 1e-6)
        assertEquals(1.0, added.unigramProb.sumOf { 10.0.pow(it) }, 1e-6)
        assertEquals(0.0, added.unigramBackoff.last(), 0.0)
        assertThrows(IllegalArgumentException::class.java) { uniform.withUnigrams(listOf("你们" to 0.0)) }
    }
}
