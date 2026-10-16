/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.dicttool

import org.fcitx.fcitx5.android.engine.english.EnglishText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EnglishCountsTest {

    private val pages = listOf("I am here.", "I go there.", "The cat is here.", "You and I are here.")

    private fun vocabulary(vararg elsewhere: Pair<String, Int>, first: List<String> = emptyList()) = EnglishVocabulary().apply {
        for ((word, times) in elsewhere) add(List(times) { word }, sentence = false)
        for (word in first) add(listOf(word), sentence = true)
    }

    private fun counted(pages: List<String>, size: Int = 100): Pair<EnglishVocabulary.Words, KneserNey> {
        val vocabulary = EnglishVocabulary()
        pages.forEach { page -> EnglishText.runs(page, vocabulary::add) }
        val words = vocabulary.words(size)
        val counts = EnglishCounts(words, vocabulary.tokens)
        pages.forEach { page -> EnglishText.runs(page, counts::add) }
        return words to KneserNey(counts)
    }

    // nothing comes before <s>: no continuation count, so its bigrams count as seen, as KenLM has them
    @Test
    fun aSentencesFirstWordIsAsLikelyAsItIsSeenFirst() {
        val (words, model) = counted(pages)
        val start = words.id(EnglishText.START, first = false)
        val i = words.id("I", first = false)
        // first in two sentences of four: near half, far more than its share of what words follow
        assertTrue(model.p2(start, i) > 0.3)
        assertTrue(model.p2(start, i) > 2 * model.p1(i))
        assertTrue(model.p2(start, i) > model.p2(start, words.id("You", first = false)))
    }

    // f6406aea's <s> fix, where a run starts after no word of the model's: pm EST is only ever after a number
    @Test
    fun aRunAfterANumberIsCountedFromAStartAsARunAfterAWordIs() {
        val (words, model) = counted(listOf("It starts at 3 pm EST.", "Doors open at 5 pm EST.", "I am here.", "I am there."))
        val pm = words.id("pm", first = false)
        val est = words.id("EST", first = false)
        assertTrue(model.p2(pm, est) > 2 * model.p1(est))
    }

    // the same where a word the model lacks breaks the run: pm EST only ever after ten or nine, each
    // seen once and left out of the eight words kept
    @Test
    fun aRunAfterAWordTheModelLacksIsCountedFromAStartToo() {
        val (words, model) = counted(listOf("We meet at ten pm EST.", "We meet at nine pm EST.", "I am here.", "I am here."), size = 8)
        assertEquals(-1, words.id("ten", first = false))
        assertEquals(-1, words.id("nine", first = false))
        val pm = words.id("pm", first = false)
        val est = words.id("EST", first = false)
        assertTrue(model.p2(pm, est) > 2 * model.p1(est))
    }

    @Test
    fun aSpellingFarRarerThanItsGroupsCommonestIsCountedAsIt() {
        val words = vocabulary("don't" to 20, "dont" to 1, "I" to 20, "i" to 1, "us" to 10, "US" to 5).words(100)
        assertFalse(words.list.toString(), "dont" in words.list || "i" in words.list)
        assertEquals(words.id("don't", first = false), words.id("dont", first = false))
        assertEquals(words.id("I", first = false), words.id("i", first = false))
        // the same letters, half as common: a word of its own
        assertTrue(words.list.toString(), "us" in words.list && "US" in words.list)
        assertNotEquals(words.id("us", first = false), words.id("US", first = false))
    }

    @Test
    fun aSpellingWithAnApostropheIsHeldToTheCommonestWithOne() {
        // we'll a fifteenth as common as well: a word all the same, and We'll and wel'l counted as it
        val words = vocabulary("well" to 300, "we'll" to 20, "We'll" to 1, "wel'l" to 1, "WELL" to 1).words(100)
        assertTrue(words.list.toString(), "we'll" in words.list && "well" in words.list)
        assertFalse(words.list.toString(), "We'll" in words.list || "wel'l" in words.list || "WELL" in words.list)
        val weWill = words.id("we'll", first = false)
        assertEquals(weWill, words.id("We'll", first = false))
        assertEquals(weWill, words.id("wel'l", first = false))
        assertEquals(words.id("well", first = false), words.id("WELL", first = false))
    }

    @Test
    fun aSpellingWithAnApostropheIsHeldToAHundredthOfItsGroupsCommonestToo() {
        // we'l'l alone beside well's 300: the commonest with an apostrophe itself, yet a slip, none
        val slip = vocabulary("well" to 300, "we'l'l" to 2).words(100)
        assertFalse(slip.list.toString(), "we'l'l" in slip.list)
        assertEquals(slip.id("well", first = false), slip.id("we'l'l", first = false))
        assertEquals(0, slip.exempted)
    }

    @Test
    fun aSpellingWithAnApostropheOfAnyOtherGroupIsHeldToATenthOfItsCommonest() {
        // th'e beside the's 300: a thirtieth as common, over a hundredth yet under a tenth, is none, as
        // the tenth would have it and a hundredth would not; once is none too
        for (times in listOf(1, 10)) {
            val the = vocabulary("the" to 300, "th'e" to times).words(100)
            assertFalse(the.list.toString(), "th'e" in the.list)
            assertEquals(the.id("the", first = false), the.id("th'e", first = false))
            assertEquals(0, the.exempted)
        }
    }

    /** A possessive has a plural's letters, no word's: it is held to the plural as any spelling to its group's commonest. */
    @Test
    fun aPossessiveIsAWordOnlyAsCommonAsAnyOtherSpelling() {
        // user's a fiftieth of users: counted as users, where it would take a word's place in the list
        val rare = vocabulary("users" to 300, "user's" to 6).words(100)
        assertFalse(rare.list.toString(), "user's" in rare.list)
        assertEquals(rare.id("users", first = false), rare.id("user's", first = false))
        assertEquals(0, rare.exempted)
        // a fifth: a word of its own
        val common = vocabulary("users" to 300, "user's" to 60).words(100)
        assertTrue(common.list.toString(), "user's" in common.list)
        assertNotEquals(common.id("users", first = false), common.id("user's", first = false))
    }

    @Test
    fun aRealWordWithAContractionsLettersIsHeldToItsOwnSpellings() {
        // wed a twentieth as common as we'd: a word, and WED counted as it; dont, as rare beside don't, a slip
        val words = vocabulary("we'd" to 300, "wed" to 15, "WED" to 1, "don't" to 300, "dont" to 15).words(100)
        assertTrue(words.list.toString(), "wed" in words.list && "we'd" in words.list)
        assertFalse(words.list.toString(), "WED" in words.list || "dont" in words.list)
        assertEquals(words.id("wed", first = false), words.id("WED", first = false))
        assertNotEquals(words.id("we'd", first = false), words.id("wed", first = false))
        assertEquals(words.id("don't", first = false), words.id("dont", first = false))
        assertEquals(1, words.exempted)
    }

    @Test
    fun aSentencesFirstWordIsCountedAsTheSpellingsItMayBeInTheirShares() {
        // The first in five sentences, the elsewhere ten times: the 15, ahead of dog's 13; Apple's
        // eight firsts shared 30 to 10 with apple
        val vocabulary = vocabulary(
            "the" to 10, "dog" to 13, "Apple" to 30, "apple" to 10,
            first = List(5) { "The" } + List(8) { "Apple" },
        )
        val words = vocabulary.words(2)
        assertEquals(listOf(EnglishVocabulary.UNKNOWN, EnglishText.START, "Apple", "the"), words.list)
        assertEquals(words.id("the", first = false), words.id("The", first = true))
        val all = vocabulary.words(100)
        // apple's 12 is more than a tenth of Apple's 36: a word of its own, and the rarer of the two first in a sentence
        assertEquals(listOf("Apple", "the", "dog", "apple"), all.list.drop(2))
        assertEquals(all.id("Apple", first = false), all.id("Apple", first = true))
        assertEquals(all.list.indexOf("apple"), all.id("apple", first = false))
    }

    @Test
    fun aGroupOneEditFromOneFarCommonerIsASlipOfIt() {
        val vocabulary = vocabulary("the" to 300, "teh" to 1)
        val words = vocabulary.words(100)
        assertEquals(listOf(EnglishVocabulary.UNKNOWN, EnglishText.START, "the"), words.list)
        assertEquals(mapOf("teh" to "the"), words.slips)
        assertEquals(words.id("the", first = false), words.id("teh", first = false))
        // a word a dictionary has is no slip, however rare
        val known = vocabulary.words(100, known = setOf("teh"))
        assertTrue(known.list.toString(), "teh" in known.list)
        assertTrue(known.slips.isEmpty())
    }
}
