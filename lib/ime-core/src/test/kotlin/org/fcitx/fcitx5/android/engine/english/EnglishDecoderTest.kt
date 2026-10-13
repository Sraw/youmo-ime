/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.english

import org.fcitx.fcitx5.android.engine.data.DataFormatException
import org.fcitx.fcitx5.android.engine.data.NgramModel
import org.fcitx.fcitx5.android.engine.data.PinyinDataBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

class EnglishDecoderTest {

    private val data = EnglishDecoderTest.data()
    private val decoder = EnglishDecoder(data)
    private val none = data.model.context(NgramModel.NO_WORD, NgramModel.NO_WORD)

    private fun words(keys: String, context: Long = none, limit: Int = 5) =
        decoder.suggest(keys, context, limit).map { data.word(it.word) }

    @Test
    fun theWordsAreFoundByTheirKeysAndAsWritten() {
        assertEquals("I'm", data.word(data.id("I'm")))
        assertEquals(NgramModel.NO_WORD, data.id("im"))
        assertEquals("dont", EnglishData.key("don't"))
        assertEquals("london", EnglishData.key("London"))
        // in key order, the model's own words in none
        val keys = (0 until data.keyed).map { data.keyAt(it) }
        assertEquals(keys.sorted(), keys)
        assertFalse((0 until data.keyed).any { data.keyedWord(it) == data.start || data.keyedWord(it) == data.unknown })
    }

    @Test
    fun keysOfAWordFindItFirstWithWhatTheyStart() {
        assertEquals(listOf("hello", "help", "hell"), words("hel", limit = 3))
        val hel = decoder.suggest("hel", none, 5).associateBy { data.word(it.word) }
        assertTrue(hel.getValue("hello").completes)
    }

    @Test
    fun anApostropheOrACapitalIsNoKey() {
        assertEquals("don't", words("dont").first())
        assertEquals("I'm", words("im").first())
        assertEquals("London", words("london").first())
    }

    @Test
    fun aSlipIsTheWordMeant() {
        // swapped, a key beside, one missed, one doubled
        assertEquals("the", words("teh").first())
        assertEquals("hello", words("hwllo").first())
        assertEquals("hello", words("helo").first())
        assertEquals("hello", words("helllo").first())
    }

    // se, a shorter prefix, is see at a doubled letter's cost: the word as typed is still found whole
    @Test
    fun aWordTypedWholeIsFoundAsSuchThoughAShorterPrefixIsNear() {
        val see = decoder.suggest("see", none, 5).first { data.word(it.word) == "see" }
        assertEquals(0f, see.cost)
        assertFalse(see.completes)
    }

    @Test
    fun keysFarFromAnyWordFindNone() {
        assertEquals(emptyList<String>(), words("qqqqqq"))
        assertEquals(emptyList<String>(), words(""))
    }

    // short keys start too many words: only what they spell is offered
    @Test
    fun twoKeysOfferNoWordTheyMightStartWithASlip() {
        assertFalse("there" in words("rh", limit = 10))
        assertTrue("there" in words("th", limit = 10))
    }

    @Test
    fun theWordsBeforeWeighTheWordAfter() {
        val start = decoder.context(listOf(EnglishText.START))
        assertEquals("I", data.word(decoder.predict(start, 1).single().word))
        val afterI = decoder.context(listOf(EnglishText.START, "I"))
        assertEquals(listOf("don't", "I'm"), decoder.predict(afterI, 2).map { data.word(it.word) })
        // i is beside u, not a: cut alone, cat after the
        assertEquals("cut", words("cit").first())
        assertEquals("cat", words("cit", decoder.context(listOf("the"))).first())
        // a word the data lacks: no context
        assertEquals(none, decoder.context(listOf("zzzz")))
    }

    // the dict tool counted a sentence's first word as the commoner of it and its lower case, and a
    // spelling it has none of as its letters' commonest: the words before the cursor are read the same
    @Test
    fun theWordsBeforeAreReadAsTheyWereCounted() {
        // The, which the data lacks, after a sentence's start: the, and cat follows it
        val afterThe = decoder.context(listOf(EnglishText.START, "The"))
        assertEquals(decoder.context(listOf(EnglishText.START, "the")), afterThe)
        assertEquals(listOf("cat"), decoder.predict(afterThe, 1).map { data.word(it.word) })
        assertEquals("cat", words("cit", afterThe).first())
        assertEquals(decoder.context(listOf("don't")), decoder.context(listOf("dont")))
        // its key between a commoner one that starts it (he) and a commoner one it starts (hello): its own
        assertEquals(decoder.context(listOf("hell")), decoder.context(listOf("HELL")))
        // both written: the commoner at a sentence's start, as written elsewhere (a title)
        val both = EnglishDataBuilder()
            .unigram("<unk>", -7f, 0f)
            .unigram(EnglishText.START, -9f, 0f)
            .unigram("the", -1.3f, 0f)
            .unigram("The", -3.0f, 0f)
            .unigram("cat", -4.0f, 0f)
            .unigram("End", -5.0f, 0f)
            .bigram(EnglishText.START, "the", -1.0f, 0f)
            .bigram("the", "cat", -1.0f, 0f)
            .bigram("The", "End", -1.0f, 0f)
        val titled = EnglishData.load(ByteBuffer.wrap(both.build().toByteArray()))
        val titledDecoder = EnglishDecoder(titled)
        fun next(words: List<String>) = titledDecoder.predict(titledDecoder.context(words), 1).map { titled.word(it.word) }
        assertEquals(listOf("cat"), next(listOf(EnglishText.START, "The")))
        assertEquals(listOf("End"), next(listOf("The")))
    }

    @Test
    fun noWordsAskedForAreNone() {
        val start = decoder.context(listOf(EnglishText.START))
        assertTrue(decoder.predict(start, 0).isEmpty())
        assertTrue(decoder.suggest("hel", none, 0).isEmpty())
    }

    // a key or two start thousands of words: only those below FREQUENT are scored, as the dict tool
    // numbers the words commonest first, and the likeliest of them are offered
    @Test
    fun aLongRunOfCompletionsIsScoredForItsCommonerWordsAlone() {
        val count = EnglishDecoder.FREQUENT + 100
        // ids 2 on: the rare word's is FREQUENT, the first past the commoner, and the last of them just before it
        val rare = EnglishDecoder.FREQUENT - 2
        val last = rare - 1
        val b = EnglishDataBuilder()
            .unigram("<unk>", -7f, 0f)
            .unigram(EnglishText.START, -9f, 0f)
        // q and four letters each, the lower the id the likelier; but the rare word the likeliest of all, the last next
        for (i in 0 until count) b.unigram(spelt(i), if (i == rare) -1f else if (i == last) -1.5f else -2f - i * 1e-4f, 0f)
        val many = EnglishData.load(ByteBuffer.wrap(b.build().toByteArray()))
        val manyDecoder = EnglishDecoder(many)
        val start = many.model.context(NgramModel.NO_WORD, NgramModel.NO_WORD)
        assertTrue(count > EnglishDecoder.MAX_SCAN)
        // the rare word is not looked at, likeliest as it is; the last of the commoner is, and offered first
        assertEquals(listOf(last + 2, 2, 3), manyDecoder.suggest("q", start, 3).map { it.word })
        // a short run, all of it: the keys of the rare word's first four start it and one other, it first
        assertEquals(rare + 2, manyDecoder.suggest(spelt(rare).dropLast(1), start, 3).first().word)
    }

    private fun spelt(i: Int): String {
        val b = StringBuilder("q")
        var n = i
        repeat(4) {
            b.append('a' + n % 26)
            n /= 26
        }
        return b.toString()
    }

    @Test
    fun anotherKindOfDataIsRefused() {
        val bytes = PinyinDataBuilder().unigram("<unk>", -1f, 0f).build().toByteArray()
        assertThrows(DataFormatException::class.java) { EnglishData.load(ByteBuffer.wrap(bytes)) }
    }

    companion object {
        /** A few words and n-grams, enough to tell slips and contexts apart. */
        fun data(): EnglishData {
            val b = EnglishDataBuilder()
                .unigram("<unk>", -7f, 0f)
                .unigram(EnglishText.START, -9f, -0.5f)
            val unigrams = mapOf(
                "the" to -1.3f, "they" to -2.5f, "then" to -2.6f, "there" to -2.4f, "he" to -2.2f,
                "hello" to -3.0f, "help" to -3.4f, "hell" to -4.0f, "I" to -1.8f, "I'm" to -3.0f,
                "don't" to -2.9f, "its" to -3.2f, "it's" to -3.0f, "US" to -3.8f, "us" to -3.2f,
                "London" to -4.0f, "form" to -3.8f, "from" to -2.3f, "cat" to -4.2f, "cut" to -3.6f,
                "and" to -1.5f, "nad" to -7.0f, "years" to -3.5f, "Steve" to -4.5f, "it" to -3.2f, "see" to -3.3f, "seed" to -4.5f,
            )
            unigrams.forEach { (w, p) -> b.unigram(w, p, if (w == "I" || w == "the") -0.3f else 0f) }
            b.bigram(EnglishText.START, "I", -0.9f, 0f)
            b.bigram(EnglishText.START, "the", -1.5f, 0f)
            b.bigram("I", "don't", -1.0f, 0f)
            b.bigram("I", "I'm", -2.5f, 0f)
            b.bigram("the", "cat", -1.5f, 0f)
            return EnglishData.load(ByteBuffer.wrap(b.build().toByteArray()))
        }
    }
}
