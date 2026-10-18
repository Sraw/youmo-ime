/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.english

import org.fcitx.fcitx5.android.engine.english.EnglishText.START
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EnglishTextTest {

    @Test
    fun aPieceOfTheTextStartsNoSentence() {
        assertEquals(listOf(EnglishText.START), EnglishText.context(""))
        assertEquals(emptyList<String>(), EnglishText.context("", start = false))
        assertEquals(listOf(EnglishText.START, "Hello"), EnglishText.context("Hello "))
        assertEquals(listOf("Hello"), EnglishText.context("Hello ", start = false))
        // after a full stop it is known all the same
        assertEquals(listOf(EnglishText.START), EnglishText.context("hello. ", start = false))
    }

    private fun runs(text: String): List<String> {
        val out = ArrayList<String>()
        EnglishText.runs(text) { words, sentence -> out += (if (sentence) "^" else "") + words.joinToString(" ") }
        return out
    }

    @Test
    fun sentencesAreRunsThatStartOne() {
        assertEquals(listOf("^Hello there", "^How are you", "^Fine"), runs("Hello there. How are you?\nFine"))
    }

    // any Unicode space separates words; U+2028, U+2029 and U+0085 break lines as \n does
    @Test
    fun unicodeSpacesSeparateAndUnicodeLineBreaksEndASentence() {
        assertEquals(listOf("^Hi there", "^Fine", "^Ok", "^Yes"), runs("Hi\u00A0there.\u2003Fine\u2028Ok\u2029Yes"))
        // a vertical tab and a form feed are line breaks too
        assertEquals(listOf("^Hi", "^Yes", "^Ok"), runs("Hi\u000BYes\u000COk"))
        assertEquals(listOf(START), EnglishText.context("Hi.\u00A0"))
        assertEquals(listOf(START), EnglishText.context("Hi\u0085"))
    }

    @Test
    fun commasQuotesAndDashesKeepARunGoing() {
        assertEquals(listOf("^Well I said yes and no"), runs("Well, I said \"yes\" - and (no)"))
    }

    // the curly apostrophe as the straight one; quotes round a word are none of it
    @Test
    fun anApostropheBetweenLettersIsTheWords() {
        assertEquals(listOf("^I don't know it's John's"), runs("I don’t know 'it's' John's"))
    }

    @Test
    fun aNumberASymbolOrALetterPastAsciiBreaksTheRunStartingNoSentence() {
        assertEquals(listOf("^I have", "cats and", "dogs", "is here"), runs("I have 3 cats and & dogs café is here"))
    }

    @Test
    fun anAbbreviationOrAnInitialEndsNoSentence() {
        assertEquals(listOf("^Mr Smith met J Doe", "^Then"), runs("Mr. Smith met J. Doe. Then"))
    }

    // the word after them was read inside a sentence: its capital a name's, corrected only for a key beside
    @Test
    fun noEtcAndIEndASentence() {
        assertEquals(listOf("^I said no", "^Then so do I", "^Pens etc", "^Last"), runs("I said no. Then so do I. Pens etc. Last"))
        assertEquals(listOf(START), EnglishText.context("So do I. "))
        // a number after No. breaks the run as it did
        assertEquals(listOf("^No", "is here"), runs("No. 5 is here"))
    }

    // e.g, example.com: no word the keyboard types whole
    @Test
    fun aFullStopInsideAWordSpoilsIt() {
        assertEquals(listOf("^see", "for more"), runs("see example.com for more"))
    }

    @Test
    fun aWordTooLongIsNone() {
        assertEquals(listOf("^a", "b"), runs("a ${"x".repeat(EnglishText.MAX_WORD + 1)} b"))
    }

    @Test
    fun theContextIsTheLastTwoWordsWithTheStartOfASentence() {
        assertEquals(listOf(START), EnglishText.context(""))
        assertEquals(listOf(START, "Hello"), EnglishText.context("Hello "))
        assertEquals(listOf("are", "you"), EnglishText.context("Hi. How are you "))
        assertEquals(listOf(START), EnglishText.context("Hi. "))
        assertEquals(listOf("you"), EnglishText.context("I have 3 you, "))
        assertEquals(emptyList<String>(), EnglishText.context("I have 3 "))
        // the next key goes on with the word
        assertEquals(emptyList<String>(), EnglishText.context("Hello"))
    }

    // as the context reads it: an initial's or an abbreviation's full stop ends none
    @Test
    fun aSentenceEndsAsTheContextReadsIt() {
        listOf("Hi. ", "Hi.", "So do I.", "Wait… ", "Why? ", "Hi\n", "Hi \r\n ", "Hi.\u00A0", "Hi.\u2003", "Hi\u2028", "Hi\u2029 ", "Hi\u0085", "Hi\u000B", "Hi\u000C").forEach {
            assertTrue(it, EnglishText.endsSentence(it))
        }
        listOf("", "abc ", "abc", "J. ", "Mr. ", "J.\u00A0", "Mr.\u00A0", "Hi. Hello", "I have 3 ", "c   ", "c\u00A0").forEach {
            assertFalse(it, EnglishText.endsSentence(it))
        }
    }

    // only the end is looked at: where it is cut, no sentence is known to start
    @Test
    fun aLongTextIsReadFromItsEnd() {
        val long = "word ".repeat(EnglishText.CONTEXT_CHARS)
        assertEquals(listOf("word", "word"), EnglishText.context(long))
        assertEquals(listOf("word"), EnglishText.context("word ".repeat(EnglishText.CONTEXT_CHARS / 5 - 1) + "x 3 word "))
    }
}
