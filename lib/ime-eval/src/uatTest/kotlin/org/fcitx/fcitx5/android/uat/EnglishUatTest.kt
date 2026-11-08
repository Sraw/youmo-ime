/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.uat

import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.engine.InputMethodCatalog
import org.fcitx.fcitx5.android.engine.runtime.EngineSettings
import org.junit.Assert.assertTrue
import org.junit.Test

/** uat/08-english.md: each case from B0-英文, in the field the case names. */
class EnglishUatTest {

    private fun english(field: EngineReplay.Field = EngineReplay.Field.TEXT, s: EngineSettings? = null) =
        EngineReplay(if (s == null) UatEngines.fresh() else settings(s), im = InputMethodCatalog.ENGLISH, field = field)

    @Test
    @Uat("EN-03")
    fun theApostropheAdded() = with(english()) {
        type("dont")
        space()
        expectText("don't ")
    }

    @Test
    @Uat("EN-06")
    fun aMarkTakesTheSpaceAfterIt() = with(english()) {
        type("hi")
        space()
        type(".")
        expectText("hi. ")
    }

    @Test
    @Uat("EN-07")
    fun twoSpacesAFullStop() = with(english()) {
        type("ok")
        space()
        space()
        expectText("ok. ")
    }

    @Test
    @Uat("EN-08")
    fun theNextWordPredicted() = with(english()) {
        type("thank")
        space()
        assertTrue("predicted $page", page.take(5).any { it.lowercase() == "you" })
    }

    @Test
    @Uat("EN-12")
    fun aWordAddedIsNotCorrected() = with(english()) {
        engines.addEnglishWord("qwzx")
        type("qwz")
        expectAmong("qwzx")
        type("x")
        space()
        expectText("qwzx ")
    }

    @Test
    @Uat("EN-13")
    fun anEmailFieldNeitherSuggestsNorCorrects() = with(english(EngineReplay.Field.EMAIL)) {
        type("teh")
        expectText("teh")
        assertTrue("offered $page", page.isEmpty())
    }

    @Test
    @Uat("EN-14")
    fun aFieldAskingNoSuggestionsGetsNone() = with(english(EngineReplay.Field.NO_SUGGESTIONS)) {
        type("teh")
        assertTrue("offered $page", page.isEmpty())
        space()
        expectText("teh ")
    }

    @Test
    @Uat("EN-15")
    fun suggestionsTurnedOff() = with(english(s = EngineSettings(englishSuggest = false))) {
        type("teh")
        assertTrue("offered $page", page.isEmpty())
        space()
        expectText("teh ")
    }

    @Test
    @Uat("EN-26")
    fun theWordAsTypedPickedCountsAsACorrectionTakenBack() = with(english()) {
        type("teh")
        pick("teh")
        // a word picked is followed by its space
        expectText("teh ")
        type("teh")
        space()
        assertTrue("「$text」", text.endsWith("teh teh "))
        assertTrue("teh not never corrected", engines.englishWords().single { it.text == "teh" }.neverCorrected)
    }

    // where the keyboard turns Shift on by itself (a sentence's start) is EnglishCapsUatTest's
    @Test
    @Uat("EN-21")
    fun aGlideTakesShiftAWordsOwnCapitalAndI() {
        with(HostReplay(im = InputMethodCatalog.ENGLISH)) {
            glide("hello", shift = true)
            glide("london")
            type(".")
            space()
            glide("im", shift = true)
            expectText("Hello London. I'm ")
            focus("")
            // the s auto capitalised, Shift off after it
            type("So ")
            glide("im")
            expectText("So I'm ")
        }
        with(HostReplay(im = InputMethodCatalog.ENGLISH, field = EngineReplay.Field.NO_SUGGESTIONS)) {
            type("ok ")
            glide("im")
            expectText("ok I'm ")
            focus("")
            glide("hello", shift = true)
            expectText("Hello ")
        }
    }

    // the cursor put into the corrected word, as a tap there puts it, and the field told again (tapField)
    @Test
    @Uat("EN-11")
    fun aCorrectionTappedBackIntoAndUndoneIsNotMadeAgain() = with(HostReplay(im = InputMethodCatalog.ENGLISH)) {
        type("youmo ")
        val corrected = text.trimEnd()
        assertTrue("youmo corrected: 「$text」", corrected != "youmo")
        type("hi ")
        expectText("$corrected hi ")
        editor.setSelection(corrected.length, corrected.length)
        tapField()
        assertTrue("in 「$corrected」: $page", page.firstOrNull() == "youmo")
        pick("youmo")
        expectText("youmo hi ")
        tappedAtEnd()
        type("youmo ")
        expectText("youmo hi youmo ")
    }

    // the keyboard half (a glide drawn there at all, its trail): KeyboardFieldsUatTest
    @Test
    @Uat("EN-19")
    fun aGlideInANoSuggestionsFieldAndWithSuggestionsOffTypesTheWord() {
        with(HostReplay(im = InputMethodCatalog.ENGLISH, field = EngineReplay.Field.NO_SUGGESTIONS)) {
            glide("hello")
            expectText("hello ")
        }
        with(HostReplay(settings(EngineSettings(englishSuggest = false)), im = InputMethodCatalog.ENGLISH)) {
            glide("hello")
            expectText("hello ")
        }
        // the keys type themselves there (KeyRouter.asIs): abc as typed, then the glide's word spaced from it
        with(HostReplay(settings(EngineSettings(englishSuggest = false)), im = InputMethodCatalog.ENGLISH)) {
            type("abc")
            glide("hello")
            expectText("abc hello ")
            backspace()
            expectText("abc")
            glide("hello")
            type(",")
            expectText("abc hello, ")
        }
    }

    @Test
    @Uat("EN-27")
    fun aWordBlockedIsNoLongerCompletedNorCorrectedTo() = with(english()) {
        type("th")
        val at = page.indexOf("the")
        assertTrue("th $page", at > 0)
        val done = learn(org.fcitx.fcitx5.android.engine.learn.control.LearningOp.Block(at, "the"))
        assertTrue("$done", done is org.fcitx.fcitx5.android.engine.learn.control.LearningResult.Done)
        backspace(2)
        type("th")
        assertTrue("th after $page", "the" !in page)
        backspace(2)
        type("thw")
        space()
        // corrected still, to the next likeliest (they), never to the word blocked
        assertTrue("thw corrected to $text", text != "the ")
    }
}
