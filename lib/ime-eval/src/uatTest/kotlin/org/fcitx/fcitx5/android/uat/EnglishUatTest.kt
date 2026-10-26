/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.uat

import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.engine.host.EngineSettings
import org.fcitx.fcitx5.android.engine.host.Engines
import org.junit.Assert.assertTrue
import org.junit.Test

/** uat/08-english.md: each case from B0-英文, in the field the case names. */
class EnglishUatTest {

    private fun english(field: EngineReplay.Field = EngineReplay.Field.TEXT, s: EngineSettings? = null) =
        EngineReplay(if (s == null) UatEngines.fresh() else settings(s), im = Engines.ENGLISH, field = field)

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
}
