/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.english

import org.fcitx.fcitx5.android.engine.data.DataFormatException
import org.fcitx.fcitx5.android.engine.data.NgramModel
import org.fcitx.fcitx5.android.engine.data.PinyinDataBuilder
import org.fcitx.fcitx5.android.engine.pinyin.Near
import org.fcitx.fcitx5.android.engine.pinyin.NineKeyLetters
import org.fcitx.fcitx5.android.engine.pinyin.SlipCost
import org.fcitx.fcitx5.android.engine.pinyin.Touch
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
        assertEquals("the", words("teh").first())
        assertEquals("hello", words("hwllo").first())
        assertEquals("hello", words("helo").first())
        assertEquals("hello", words("helllo").first())
    }

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
        assertEquals("cut", words("cit").first())
        assertEquals("cat", words("cit", decoder.context(listOf("the"))).first())
        assertEquals(none, decoder.context(listOf("zzzz")))
    }

    // the words before the cursor are read as the dict tool counted them (a sentence's first word as its commoner case)
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

    private val nine = EnglishDecoder(data, EnglishDecoder.NINE_KEY_COSTS, EnglishDecoder.nineKeyCost(NineKeyLetters.STANDARD::letters))

    private fun nineWords(keys: String) = nine.suggest(keys, none, 10, exactFirst = true).map { data.word(it.word) }

    @Test
    fun onTheNineKeysAKeyIsAnyOfItsLettersTheWordsAsLongAsTheKeysFirst() {
        assertEquals(listOf("hell", "hello"), nineWords("4355"))
        assertEquals(listOf("he", "hello", "help", "hell"), nineWords("43"))
        assertEquals(listOf("don't"), nineWords("3668"))
        assertEquals(listOf("hell", "hello"), nineWords("4e55"))
        assertEquals(emptyList<String>(), nineWords("4f55"))
    }

    @Test
    fun onTheNineKeysNoKeyIsASlip() {
        // hlel, swapped; hel, one missed; helll, one extra: none of them the nine keys' hell
        assertEquals(emptyList<String>(), nineWords("4535"))
        assertEquals(listOf("hello"), nineWords("43556"))
        assertEquals(emptyList<String>(), nineWords("435556"))
    }

    // w tapped [dx] across from its middle (a QWERTY key 40 by 56 dp), q and e beside it
    private fun onW(dx: Float) = Touch(dx, 0f, 40f, 56f, listOf(Near('q', -1f, 0f), Near('e', 1f, 0f)))

    private fun helloCost(touches: Array<Touch?>?) =
        decoder.suggest("hwllo", none, 5, touches = touches).first { data.word(it.word) == "hello" }.cost

    @Test
    fun aTapsPlaceSetsWhatTheKeyBesideItCosts() {
        assertEquals(1.0f, helloCost(null), 1e-4f)
        // tapped in the middle of w: no likelier e than any key (far); at its edge by e, nearly as likely as w
        assertEquals(3.0f, helloCost(arrayOf(null, onW(0f), null, null, null)), 1e-4f)
        val edge = helloCost(arrayOf(null, onW(0.45f), null, null, null))
        assertEquals(-SlipCost().of(onW(0.45f), Near('e', 1f, 0f), legal = false), edge, 1e-4f)
        assertTrue("$edge", edge < 0.5f)
        // at the edge away from e: dearer than in the middle, never past far
        assertEquals(3.0f, helloCost(arrayOf(null, onW(-0.45f), null, null, null)), 1e-4f)
        assertEquals(1.0f, helloCost(arrayOf(onW(0f))), 1e-4f)
    }

    // 6 (2.2 keys wide) tapped [dx] across from its middle, 5 left of it
    private fun onSix(dx: Float) = Touch(dx, 0f, 88f, 56f, listOf(Near('5', -1f, 0f), Near('3', 0f, -1f), Near('9', 0f, 1f)))

    private val nineTapped = EnglishDecoder(
        data, EnglishDecoder.NINE_KEY_COSTS.copy(limit = 3f), EnglishDecoder.nineKeyCost(NineKeyLetters.STANDARD::letters),
        EnglishDecoder.nineKeyTap(NineKeyLetters.STANDARD::letters, SlipCost(floor = -3f)),
    )

    private fun nineTapped(keys: String, touches: Array<Touch?>) = nineTapped.suggest(keys, none, 10, exactFirst = true, touches = touches).map { data.word(it.word) }

    @Test
    fun onTheNineKeysADigitTappedByTheNextIsItsLettersToo() {
        assertEquals(listOf("hell"), nineTapped("4356", arrayOf(null, null, null, onSix(-0.45f))))
        assertEquals(emptyList<String>(), nineTapped("4356", arrayOf(null, null, null, onSix(0f))))
        assertEquals(listOf("he", "hello", "help", "hell"), nineTapped("43", arrayOf(null, null)))
    }

    @Test
    fun aLetterOnTwoKeysAroundCostsTheCheaperSlip() {
        val letters = { d: Char -> if (d == '9') "lwxyz" else NineKeyLetters.STANDARD.letters(d) }
        val slip = SlipCost(floor = -3f)
        val touch = Touch(-0.45f, 0f, 88f, 56f, listOf(Near('9', 0f, 1f), Near('5', -1f, 0f)))
        assertTrue(slip.of(touch, touch.near[0], legal = false).isNaN())
        assertEquals(-slip.of(touch, touch.near[1], legal = false), EnglishDecoder.nineKeyTap(letters, slip)(touch, '6', 'l'), 1e-4f)
    }

    // as the app's NineKeyWords: a slip 2 dearer where a word of the keys as typed is among the words
    private val nineLegal = EnglishDecoder(
        data, EnglishDecoder.NINE_KEY_COSTS.copy(limit = 3f), EnglishDecoder.nineKeyCost(NineKeyLetters.STANDARD::letters),
        EnglishDecoder.nineKeyTap(NineKeyLetters.STANDARD::letters, SlipCost(floor = -3f)), legalSlip = -2f,
    )

    private fun EnglishDecoder.costs(keys: String, touches: Array<Touch?>) =
        suggest(keys, none, 10, exactFirst = true, touches = touches).associate { data.word(it.word) to it.cost }

    @Test
    fun aSlipOnTheNineKeysCostsMoreWhereTheKeysAsTypedSpellAWord() {
        // 8439 they, its 9 tapped at the top edge by 6: then (8436) through the slip
        val onNine = arrayOf(null, null, null, Touch(0f, -0.45f, 88f, 56f, listOf(Near('6', 0f, -1f), Near('8', -1f, 0f))))
        val plain = nineTapped.costs("8439", onNine)
        val legal = nineLegal.costs("8439", onNine)
        assertEquals(0f, legal.getValue("they"), 0f)
        assertEquals(plain.getValue("then") + 2f, legal.getValue("then"), 1e-4f)
        val onSix = arrayOf(null, null, null, onSix(-0.45f))
        assertEquals(nineTapped.costs("4356", onSix), nineLegal.costs("4356", onSix))
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
