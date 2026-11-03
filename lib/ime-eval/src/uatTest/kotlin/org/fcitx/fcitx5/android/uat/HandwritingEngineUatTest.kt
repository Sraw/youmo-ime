/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.uat

import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.engine.InputMethodCatalog
import org.fcitx.fcitx5.android.engine.reading.CharCard
import org.fcitx.fcitx5.android.input.keyboard.PendingInputPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * uat/09-handwriting.md, what the engine makes of what was written: the sentence read with the
 * app's model, space, picks, ⌫, punctuation, 联想 and learning. A character's readings are given
 * as the recognizer hands them (its own reading of strokes is its tests' and the device's HW-03).
 */
class HandwritingEngineUatTest {

    private val da = arrayOf("大", "太", "犬")
    private val ren = arrayOf("人", "入", "八")
    private val wo = arrayOf("我", "找", "伐")

    private fun replay() = EngineReplay(im = InputMethodCatalog.HANDWRITING)

    @Test
    @Uat("HW-03")
    fun spaceCommitsTheSentenceWritten() = with(HostReplay(im = InputMethodCatalog.HANDWRITING)) {
        write(*da)
        write(*ren)
        space()
        expectText("大人")
        assertEquals(-1 to -1, editor.composingStart to editor.composingEnd)
    }

    // chttrans re-sends the composing text (chttrans.cpp:214-215): what is written and not yet in
    // shows traditional at the toggle, with nothing more written; it goes in as shown
    @Test
    @Uat("HW-26")
    fun aCharacterNotYetInIsShownTraditionalAtTheToggle() = with(HostReplay(im = InputMethodCatalog.HANDWRITING)) {
        write("国", "图", "固")
        assertEquals("国", clientPreedit)
        toggle("chttrans")
        assertEquals("國", clientPreedit)
        space()
        assertEquals("國", text)
    }

    @Test
    @Uat("HW-04")
    fun aCharacterWrittenAfterCorrectsTheOneBefore() = with(replay()) {
        // left side between 讠 and 氵: read 淡 first, 谈 second
        write("淡", "谈", "炎")
        expectPreedit("淡")
        assertEquals("谈", candidates[1])
        write("话", "活", "诂")
        expectPreedit("谈话")
    }

    @Test
    @Uat("HW-05")
    fun aCandidatePickedPutsInTheSentenceEndingWithIt() = with(replay()) {
        write(*da)
        write(*ren)
        val c = candidates[1]
        pickAt(1)
        assertTrue("$text, $c", text.length == 2 && text.endsWith(c))
        expectPreedit("")
    }

    @Test
    @Uat("HW-07")
    fun backspaceTakesTheUnwrittenCharacterThenTheText() = with(replay()) {
        focus("好")
        write(*ren)
        backspace()
        expectText("好")
        expectPreedit("")
        backspace()
        expectText("")
    }

    @Test
    @Uat("HW-08")
    fun punctuationPutsTheSentenceInFirst() = with(replay()) {
        write(*ren)
        // the pad's 。 is a commit over the engine (commitOverEngine): what is written first, as its policy says.
        // The policy is applied by hand here; the service's wiring of it is CommonKeyActionListenerTest's
        // (app tests), the policy's own cases PendingInputPolicyTest's: this holds the engine's half
        assertEquals(PendingInputPolicy.Resolution.SelectFirstCandidate, PendingInputPolicy.resolve("zh_CN", hasPreedit = preedit.isNotEmpty()))
        pickAt(0)
        commit("。")
        expectText("人。")
    }

    @Test
    @Uat("HW-10")
    fun aSpaceThenPredictions() = with(replay()) {
        write(*wo)
        space()
        expectText("我")
        val next = page
        assertTrue("predictions $next", next.isNotEmpty())
        pickAt(0)
        expectText("我" + next.first())
    }

    @Test
    @Uat("HW-11")
    fun returnPutsInWithoutPredictions() = with(replay()) {
        write(*wo)
        enter()
        assertTrue(text, text.startsWith("我"))
        assertTrue("predictions $page", page.isEmpty())
    }

    @Test
    @Uat("HW-14")
    fun aCharacterPickedRanksUpTwiceFirst() = with(replay()) {
        write(*ren)
        assertEquals(listOf("人", "入", "八"), candidates.take(3))
        pick("入")
        focus("")
        write(*ren)
        assertEquals(listOf("人", "入"), candidates.take(2))
        pick("入")
        focus("")
        write(*ren)
        assertEquals("入", candidates.first())
    }

    @Test
    @Uat("HW-23")
    fun aCharacterDeletedRightAwayIsNotUsed() = with(replay()) {
        write(*ren)
        val order = candidates.take(3)
        pickAt(2)
        backspace()
        expectText("")
        // written again right away, as the device's step: no new focus
        write(*ren)
        assertEquals(order, candidates.take(3))
    }

    // a character's card with the app's data
    private fun card(vararg written: String): CharCard = with(HostReplay(im = InputMethodCatalog.HANDWRITING)) {
        write(*written)
        checkNotNull(engines.charCard(InputMethodCatalog.HANDWRITING, candidates.indexOf(written[0]), written[0]))
    }

    // a reading's words a dictionary's, not the model's others: 荣和, 去呢, 弦数 are two words run together (the user, 2026-11-02)
    @Test
    @Uat("HW-29")
    fun aCardsWordsAreADictionarys() {
        val he = card("和", "口")
        assertEquals(listOf("hé", "hè", "huò", "hú", "huó", "huo"), he.readings.map { it.pinyin })
        assertEquals(listOf("和谐", "和平"), he.readings[0].examples)
        // 和牌 is no dictionary's: hú has none
        assertEquals(emptyList<String>(), he.readings[3].examples)
        assertEquals(listOf("掺和", "搅和"), he.readings[5].examples)
        assertEquals(listOf("呢喃", "毛呢"), card("呢", "口").readings.single { it.pinyin == "ní" }.examples)
        assertEquals(emptyList<String>(), card("呢", "口").readings.single { it.pinyin == "ne" }.examples)
        val shu = card("数", "口").readings.associate { it.pinyin to it.examples }
        assertEquals(listOf("倒数", "数不胜数"), shu["shǔ"])
        val words = listOf(he, card("呢", "口"), card("数", "口")).flatMap { c -> c.readings.flatMap { it.examples } }
        assertTrue(words.toString(), words.none { it in setOf("荣和", "去呢", "弦数") })
    }

    // the mainland's stroke order (the user, 2026-11-02), where rime-stroke's first is not: 行 ppshhz,
    // 出 szszs, 必 nznnp, 我 phzhznp
    @Test
    @Uat("HW-29")
    fun aCardsStrokeOrderIsTheMainlands() {
        assertEquals("丿丿丨一一丨", card("行", "口").strokeOrder)
        assertEquals("乛丨丨乛丨", card("出", "口").strokeOrder)
        assertEquals("丶乛丶丿丶", card("必", "口").strokeOrder)
        assertEquals("丿一丨一乛丿丶", card("我", "口").strokeOrder)
        assertEquals("丶丿一丨丿丶乛丿一丿一丿丶", card("数", "口").strokeOrder)
    }
}
